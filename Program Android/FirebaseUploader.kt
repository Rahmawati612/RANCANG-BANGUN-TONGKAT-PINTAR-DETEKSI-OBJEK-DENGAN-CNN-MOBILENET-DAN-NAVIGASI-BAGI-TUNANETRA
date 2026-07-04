package com.skripsi.smart_cane

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FirebaseUploader {

    // ── Firebase ──────────────────────────────────────────────────────────────
    private val database = FirebaseDatabase.getInstance(
        "https://smart-cane-family-default-rtdb.asia-southeast1.firebasedatabase.app/"
    )
    private val koordinatRef     = database.getReference("SmartCane/koordinat")
    private val logPerjalananRef = database.getReference("SmartCane/log_perjalanan")

    // ── Snapshot data terbaru ─────────────────────────────────────────────────
    private var snapshotTerakhir = mutableMapOf<String, Any>()

    // ── Handler timer ─────────────────────────────────────────────────────────
    private val handler    = Handler(Looper.getMainLooper())
    private var sudahMulai = false

    //  penanda berbasis String "HH:mm" agar hanya ada 1 data per menit
    private var menitTerakhirDiupload = ""

    // ── Mulai semua timer — dipanggil sekali dari LaunchedEffect(Unit) ────────
    fun mulai(context: Context) {
        if (sudahMulai) return
        sudahMulai = true
        jadwalkanResetHarian(context)
        jadwalkan30Detik(context)   // Timer koordinat real-time (10 detik)
        jadwalkan3Menit(context)    // 🛠️ FIX: Panggilan timer log perjalanan diaktifkan kembali
    }

    /**
     * Perbarui snapshot data terbaru.
     * Dipanggil tiap kali kamera/BLE mendeteksi objek baru.
     */
    fun updateSnapshot(
        objek   : String,
        jarakCm : String,
        lat     : Double,
        lng     : Double,
        context : Context
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val waktu = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            //   Filter Jarak LiDAR Out of Range / Error
            val jarakBersih = jarakCm.replace(" cm", "").trim()
            val jarakFinal = if (jarakBersih == "6553" || jarakBersih.toIntOrNull() ?: 0 <= 0 || jarakBersih == "65535") {
                "--" // Tetap di-upload dengan tanda "--" jika jarak bermasalah
            } else {
                jarakCm
            }

            val namaJalan = try {
                val geocoder  = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    addresses[0].getAddressLine(0) ?: "Lokasi tidak diketahui"
                } else {
                    "Lokasi tidak diketahui"
                }
            } catch (e: Exception) {
                "Gagal mengambil nama lokasi"
            }

            withContext(Dispatchers.Main) {
                snapshotTerakhir = mutableMapOf(
                    "objek_terakhir" to objek,
                    "jarak_objek"    to jarakFinal,
                    "waktu_terakhir" to waktu, // Waktu deteksi asli alat
                    "nama_jalan"     to namaJalan,
                    "latitude"       to lat,
                    "longitude"      to lng
                )
            }
        }
    }

    // ── Timer 1: Upload koordinat tiap 10 detik (untuk Maps & Dashboard) ─────
    private fun jadwalkan30Detik(context: Context) {
        handler.postDelayed({
            uploadKoordinat()
            jadwalkan30Detik(context)
        }, 10 * 1000L) // 10 Detik
    }

    // ── Timer 2: Upload log perjalanan tiap 3 menit ───────────────────────────
    private fun jadwalkan3Menit(context: Context) {
        handler.postDelayed({
            uploadLogPerjalanan()
            jadwalkan3Menit(context)
        }, 3 * 60 * 1000L) // 3 Menit
    }

    // ── Upload hanya ke node koordinat (Maps & Dashboard real-time) ──────────
    private fun uploadKoordinat() {
        if (snapshotTerakhir.isEmpty()) return

        koordinatRef.setValue(snapshotTerakhir.toMap())
            .addOnSuccessListener { println("✅ koordinat diperbarui: ${snapshotTerakhir["waktu_terakhir"]}") }
            .addOnFailureListener { println("❌ koordinat gagal: ${it.message}") }
    }

    // ── Upload ke node log_perjalanan/{tanggal}/{jam}/{menit} ─────────────────
    private fun uploadLogPerjalanan() {
        if (snapshotTerakhir.isEmpty()) return

        val tanggal = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        // Ambil waktu asli dari snapshot data
        val waktuTerakhir = snapshotTerakhir["waktu_terakhir"] as? String ?: "00:00"

        // Jika menit ini sudah pernah sukses diupload, batalkan pengiriman duplikat
        if (waktuTerakhir == menitTerakhirDiupload) {
            println("⏳ Batalkan upload log: Data pada menit $waktuTerakhir sudah tersimpan sebelumnya.")
            return
        }

        val splitWaktu    = waktuTerakhir.split(":")
        val jam           = splitWaktu.getOrElse(0) { "00" }
        val menit         = waktuTerakhir

        logPerjalananRef
            .child(tanggal)
            .child(jam)
            .child(menit)
            .setValue(snapshotTerakhir.toMap())
            .addOnSuccessListener {
                println("✅ log tersimpan: $tanggal/$jam/$menit")

                // Catat menit ini agar tidak dicuri atau ditimpa data duplikat pada menit yang sama
                menitTerakhirDiupload = waktuTerakhir
            }
            .addOnFailureListener { println("❌ log gagal: ${it.message}") }
    }

    // ── Reset log_perjalanan tiap tengah malam ────────────────────────────────
    private fun jadwalkanResetHarian(context: Context) {
        val kalender = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val sisaMs = kalender.timeInMillis - System.currentTimeMillis()

        handler.postDelayed({
            val kemarin = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000))
            logPerjalananRef.child(kemarin).removeValue()
                .addOnSuccessListener { println("🗑️ Log $kemarin dihapus otomatis") }

            jadwalkanResetHarian(context)
        }, sisaMs)
    }

    // ── Hentikan semua timer saat aplikasi ditutup ────────────────────────────
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        sudahMulai = false
    }
}