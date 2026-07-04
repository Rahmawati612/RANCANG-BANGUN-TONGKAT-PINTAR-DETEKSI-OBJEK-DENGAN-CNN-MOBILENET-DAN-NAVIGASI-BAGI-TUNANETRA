package com.skripsi.smart_cane

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.IOException

data class NavInstruksi(
    val teks             : String,
    val jarakMeter       : Int,
    var sudahDiucapkan   : Boolean = false,
    var sudahPeringatan  : Boolean = false,
    var sudahPeringatan2 : Boolean = false
)

data class NavState(
    val instruksiSaatIni : String  = "",
    val jarakKeTujuan    : String  = "",
    val isNavigating     : Boolean = false,
    val isLoading        : Boolean = false,
    val error            : String  = "",
    val sudahSampai      : Boolean = false
)

class NavigasiManager(private val context: Context) {

    private val client = OkHttpClient()
    private val gson   = Gson()

    private val mainScope = CoroutineScope(Dispatchers.Main)

    private val _navState      = MutableStateFlow(NavState())
    val navState: StateFlow<NavState> = _navState

    private val _lokasiSaatIni = MutableStateFlow<Location?>(null)
    val lokasiSaatIni: StateFlow<Location?> = _lokasiSaatIni

    private var daftarInstruksi     = mutableListOf<NavInstruksi>()
    private var indexInstruksi      = 0
    private var tujuanLat           = 0.0
    private var tujuanLon           = 0.0
    private var jarakTotalAwalRute  = 0
    private var sisaTotalSebelumnya = Int.MAX_VALUE
    private var jarakSudahDitempuh  = 0

    var onInstruksiBaru: ((String) -> Unit)? = null

    private val fusedLocation = LocationServices.getFusedLocationProviderClient(context)

    //  0f agar callback tetap dipanggil meski diam menunggu GPS
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).setMinUpdateIntervalMillis(2000L)
        .setMinUpdateDistanceMeters(0f)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return

            //  Selalu update posisi agar mintaRute() tidak stuck "GPS belum siap"
            _lokasiSaatIni.value = loc
            Log.d("NavigasiManager", "📍 GPS: ${loc.latitude}, ${loc.longitude} | Akurasi: ${loc.accuracy}m")

            //  Filter akurasi hanya berlaku saat navigasi aktif
            if (_navState.value.isNavigating) {
                if (loc.accuracy > 20f) {
                    Log.w("NavigasiManager", "⚠️ Skip navigasi, akurasi buruk: ${loc.accuracy}m")
                    return
                }
                updateProgress(loc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startGPS() {
        fusedLocation.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
        Log.d("NavigasiManager", "GPS aktif")
    }

    fun stopGPS() {
        fusedLocation.removeLocationUpdates(locationCallback)
        Log.d("NavigasiManager", "GPS berhenti")
    }

    fun mintaRute(
        namaTemp   : String,
        tujuanLatIn: Double,
        tujuanLonIn: Double,
        onInstruksi: (String) -> Unit
    ) {
        val lokasi = _lokasiSaatIni.value
        if (lokasi == null) {
            onInstruksi("GPS belum siap, tunggu sebentar")
            return
        }

        tujuanLat           = tujuanLatIn
        tujuanLon           = tujuanLonIn
        sisaTotalSebelumnya = Int.MAX_VALUE
        jarakSudahDitempuh  = 0

        _navState.value = _navState.value.copy(
            isLoading        = true,
            error            = "",
            instruksiSaatIni = "Menghitung rute ke $namaTemp"
        )

        val url = "https://router.project-osrm.org/route/v1/foot/" +
                "${lokasi.longitude},${lokasi.latitude};" +
                "${tujuanLon},${tujuanLat}" +
                "?steps=true&overview=false"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainScope.launch {
                    _navState.value = _navState.value.copy(
                        isLoading = false,
                        error     = "Gagal koneksi internet"
                    )
                    onInstruksi("Gagal terhubung ke server navigasi")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                mainScope.launch {
                    parseResponse(body, onInstruksi)
                }
            }
        })
    }

    private fun parseResponse(json: String, onInstruksi: (String) -> Unit) {
        try {
            val obj    = gson.fromJson(json, Map::class.java)
            val routes = obj["routes"] as? List<*> ?: return
            val route  = routes[0] as? Map<*, *> ?: return
            val legs   = route["legs"] as? List<*> ?: return
            val leg    = legs[0] as? Map<*, *> ?: return
            val steps  = leg["steps"] as? List<*> ?: return

            jarakTotalAwalRute = ((leg["distance"] as? Double) ?: 0.0).toInt()

            daftarInstruksi.clear()
            indexInstruksi     = 0
            jarakSudahDitempuh = 0

            for (step in steps) {
                val s        = step as? Map<*, *> ?: continue
                val maneuver = s["maneuver"] as? Map<*, *> ?: continue
                val tipe     = maneuver["type"] as? String ?: ""
                val modifier = maneuver["modifier"] as? String ?: ""
                val jarak    = ((s["distance"] as? Double) ?: 0.0).toInt()
                val teks     = buatInstruksi(tipe, modifier, jarak)
                if (teks.isNotEmpty()) {
                    daftarInstruksi.add(NavInstruksi(teks, jarak))
                }
            }

            val pertama = daftarInstruksi.firstOrNull()?.teks ?: "Mulai berjalan lurus"

            _navState.value = _navState.value.copy(
                isLoading        = false,
                isNavigating     = true,
                instruksiSaatIni = pertama,
                jarakKeTujuan    = formatJarak(jarakTotalAwalRute)
            )

            onInstruksi("Rute ditemukan. $pertama")

            if (daftarInstruksi.isNotEmpty()) {
                daftarInstruksi[0] = daftarInstruksi[0].copy(sudahDiucapkan = true)
            }

        } catch (e: Exception) {
            Log.e("NavigasiManager", "Parse error: ${e.message}")
            _navState.value = _navState.value.copy(
                isLoading = false,
                error     = "Gagal memproses rute"
            )
            onInstruksi("Gagal memproses rute")
        }
    }

    private fun updateProgress(lokasi: Location) {
        val hasil = FloatArray(1)
        Location.distanceBetween(
            lokasi.latitude, lokasi.longitude,
            tujuanLat, tujuanLon, hasil
        )
        val sisaTotal = hasil[0].toInt()

        // hanya proses kalau user benar-benar maju
        if (sisaTotal >= sisaTotalSebelumnya) {
            Log.d("NavigasiManager", "⏸ Tidak maju, skip. Sisa: $sisaTotal")
            return
        }
        val deltaJalan      = sisaTotalSebelumnya - sisaTotal
        jarakSudahDitempuh += deltaJalan
        sisaTotalSebelumnya = sisaTotal

        // Sampai tujuan
        if (sisaTotal < 20) {
            _navState.value = _navState.value.copy(
                sudahSampai      = true,
                isNavigating     = false,
                instruksiSaatIni = "Anda telah tiba di tujuan",
                jarakKeTujuan    = "0 meter"
            )
            onInstruksiBaru?.invoke("Selamat, Anda telah tiba di tujuan")
            return
        }

        // Advance index berdasarkan jarak nyata yang sudah ditempuh
        if (indexInstruksi < daftarInstruksi.size - 1 &&
            jarakSudahDitempuh >= daftarInstruksi[indexInstruksi].jarakMeter
        ) {
            jarakSudahDitempuh = 0
            indexInstruksi++
            Log.d("NavigasiManager", "➡️ Index maju ke $indexInstruksi")
        }

        val instruksiSekarang = daftarInstruksi.getOrNull(indexInstruksi)
        val instruksiBesok    = daftarInstruksi.getOrNull(indexInstruksi + 1)

        // Sisa jarak segmen aktif
        val sisaJarakLangkahAktif = (daftarInstruksi[indexInstruksi].jarakMeter - jarakSudahDitempuh)
            .coerceAtLeast(0)

        val teksDinamis = if (instruksiSekarang != null) {
            formatTeksDinamis(instruksiSekarang.teks, sisaJarakLangkahAktif)
        } else {
            "Terus berjalan lurus"
        }

        _navState.value = _navState.value.copy(
            jarakKeTujuan    = formatJarak(sisaTotal),
            instruksiSaatIni = teksDinamis
        )

        // TTS instruksi utama
        if (instruksiSekarang != null && !instruksiSekarang.sudahDiucapkan) {
            Log.d("NavigasiManager", "🔊 Instruksi: $teksDinamis")
            if (onInstruksiBaru != null) {
                onInstruksiBaru?.invoke(teksDinamis)
            } else {
                Log.e("NavigasiManager", "❌ onInstruksiBaru NULL!")
            }
            instruksiSekarang.sudahDiucapkan = true
        }

        //  Peringatan 1 — 30 meter (siap-siap)
        if (instruksiBesok != null && !instruksiBesok.sudahPeringatan && sisaJarakLangkahAktif <= 30) {
            val peringatan = ekstrakPeringatan(instruksiBesok.teks)
            Log.d("NavigasiManager", "🔊 Peringatan 1: $peringatan")
            onInstruksiBaru?.invoke(peringatan)
            instruksiBesok.sudahPeringatan = true
        }

        //  Peringatan 2 — trigger 13 meter, selesai diucapkan ~10 meter
        if (instruksiBesok != null && !instruksiBesok.sudahPeringatan2 && sisaJarakLangkahAktif <= 13) {
            val peringatan2 = ekstrakPeringatan2(instruksiBesok.teks)
            Log.d("NavigasiManager", "🔊 Peringatan 2: $peringatan2")
            onInstruksiBaru?.invoke(peringatan2)
            instruksiBesok.sudahPeringatan2 = true
        }

        Log.d("NavigasiManager", "Sisa: $sisaTotal m | Segmen sisa: $sisaJarakLangkahAktif m | Ditempuh: $jarakSudahDitempuh m | Index: $indexInstruksi")
    }

    private fun formatTeksDinamis(teksAsli: String, jarakBaru: Int): String {
        val regexJarak = "\\d+\\s*meter".toRegex()
        if (teksAsli.contains(regexJarak)) {
            return teksAsli.replace(regexJarak, formatJarak(jarakBaru))
        }
        return teksAsli
    }

    private fun buatInstruksi(tipe: String, modifier: String, jarak: Int): String {
        val j = formatJarak(jarak)
        return when (tipe) {
            "depart"     -> "Mulai berjalan lurus sejauh $j"
            "turn"       -> when (modifier) {
                "left"         -> "Belok kiri, lalu jalan lurus $j"
                "right"        -> "Belok kanan, lalu jalan lurus $j"
                "slight left"  -> "Sedikit ke kiri, lalu jalan lurus $j"
                "slight right" -> "Sedikit ke kanan, lalu jalan lurus $j"
                "sharp left"   -> "Belok tajam ke kiri, lalu jalan $j"
                "sharp right"  -> "Belok tajam ke kanan, lalu jalan $j"
                "uturn"        -> "Putar balik, lalu jalan $j"
                else           -> "Belok, lalu jalan lurus $j"
            }
            "arrive"     -> "Anda telah tiba di tujuan"
            "continue"   -> "Terus lurus sejauh $j"
            "roundabout" -> "Masuk bundaran, ambil jalan keluar, lalu jalan $j"
            else         -> ""
        }
    }

    private fun ekstrakPeringatan(teks: String): String = when {
        teks.contains("kiri")     -> "Persiapkan diri, segera belok kiri"
        teks.contains("kanan")    -> "Persiapkan diri, segera belok kanan"
        teks.contains("putar")    -> "Persiapkan diri, segera putar balik"
        teks.contains("bundaran") -> "Persiapkan diri, masuk bundaran"
        else                      -> "Persiapkan diri"
    }

    private fun ekstrakPeringatan2(teks: String): String = when {
        teks.contains("kiri")     -> "Sekarang belok kiri"
        teks.contains("kanan")    -> "Sekarang belok kanan"
        teks.contains("putar")    -> "Sekarang putar balik"
        teks.contains("bundaran") -> "Sekarang masuk bundaran"
        else                      -> "Sekarang belok"
    }

    fun stopNavigasi() {
        daftarInstruksi.clear()
        indexInstruksi      = 0
        jarakTotalAwalRute  = 0
        sisaTotalSebelumnya = Int.MAX_VALUE
        jarakSudahDitempuh  = 0
        _navState.value     = NavState()
    }

    private fun formatJarak(meter: Int) = when {
        meter < 100  -> "$meter meter"
        meter < 1000 -> "${(meter / 10) * 10} meter"
        else         -> "${"%.1f".format(meter / 1000.0)} kilometer"
    }
}