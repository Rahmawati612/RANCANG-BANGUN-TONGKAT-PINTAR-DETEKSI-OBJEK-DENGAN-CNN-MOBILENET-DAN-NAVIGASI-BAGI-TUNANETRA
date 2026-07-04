package com.skripsi.smart_cane

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.skripsi.smart_cane.ui.theme.SmartCaneTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val bleViewModel: BleViewModel by viewModels()

    // Deklarasikan ObjectDetector di tingkat Activity agar siklus memorinya aman
    private lateinit var objectDetector: ObjectDetector

    // ── PROPERTY DETEKSI TOMBOL VOLUME UNTUK VOICE COMMAND ──
    var onVolumeUp        : (() -> Unit)? = null
    var onVolumeDown      : (() -> Unit)? = null
    var onVolumeUpDouble  : (() -> Unit)? = null
    var onVolumeDownDouble: (() -> Unit)? = null

    private var lastVolumeUpTime   = 0L
    private var lastVolumeDownTime = 0L
    private val DOUBLE_PRESS_MS    = 500L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isEmpty()) {
            Toast.makeText(this, "Semua izin diberikan. Aplikasi siap.", Toast.LENGTH_SHORT).show()
        } else {
            val msg = buildString {
                append("Izin berikut ditolak: ")
                append(denied.joinToString { it.substringAfterLast(".") })
                append(". Beberapa fitur mungkin tidak berjalan.")
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minta izin runtime Android
        checkAndRequestPermissions()

        // Jalankan background service Firebase Uploader
        FirebaseUploader.mulai(this)

        // Inisialisasi Otak TFLite di sini
        try {
            objectDetector = ObjectDetector(this)
            objectDetector.initialize()
            Log.d("MainActivity", "🎉 TFLite ObjectDetector Berhasil Dimuat Semuanya!")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Gagal memuat model TFLite kustom skripsi: ${e.message}")
        }

        // ──  HUBUNGAN  DATA LIDAR KE DETECTOR AI ──
        // Mengamati data jarak dari BleViewModel secara reaktif dan realtime
        lifecycleScope.launch {
            bleViewModel.distance.collect { mmValue ->
                // Mengubah satuan milimeter (mm) dari ESP32 menjadi centimeter (cm)
                val distanceInCm = mmValue / 10f

                if (::objectDetector.isInitialized) {
                    // Set langsung nilai jarak terbaru ke dalam variabel detector
                    objectDetector.currentLidarDistance = distanceInCm
                }
            }
        }

        setContent {
            SmartCaneTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onMulaiClick = { navController.navigate("deteksi") },
                            onPengaturanClick = { navController.navigate("pengaturan") }
                        )
                    }
                    composable("deteksi") {
                        // Sukses mengoper objectDetector ke DeteksiScreen kustommu
                        DeteksiScreen(
                            onBack = { navController.popBackStack() },
                            bleViewModel = bleViewModel,
                            objectDetector = objectDetector
                        )
                    }
                    composable("pengaturan") {
                        PengaturanScreen(
                            onBack = { navController.popBackStack() },
                            bleViewModel = bleViewModel
                        )
                    }
                }
            }
        }
    }

    // ──  INTERCEPTOR TOMBOL VOLUME FISIK REAKTIF UNTUK TUNANETRA  ──
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val now = System.currentTimeMillis()
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (onVolumeUp == null) {
                    // Jika tidak ada handler yang terpasang, kembalikan fungsi ke sistem bawaan HP
                    super.onKeyDown(keyCode, event)
                } else {
                    if (now - lastVolumeUpTime < DOUBLE_PRESS_MS) {
                        // Aksi jika ditekan dua kali berturut-turut (Double Press)
                        onVolumeUpDouble?.invoke()
                    } else {
                        // Aksi jika ditekan satu kali (Single Press)
                        onVolumeUp?.invoke()
                    }
                    lastVolumeUpTime = now
                    true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (onVolumeDown == null) {
                    // Jika tidak ada handler yang terpasang, kembalikan fungsi ke sistem bawaan HP (atur kecil suara)
                    super.onKeyDown(keyCode, event)
                } else {
                    if (now - lastVolumeDownTime < DOUBLE_PRESS_MS) {
                        // Aksi jika ditekan dua kali berturut-turut (Double Press)
                        onVolumeDownDouble?.invoke()
                    } else {
                        // Aksi jika ditekan satu kali (Single Press)
                        onVolumeDown?.invoke()
                    }
                    lastVolumeDownTime = now
                    true
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = buildList {
            // Kamera untuk pratinjau dan analisis frame CameraX
            add(Manifest.permission.CAMERA)

            // Rekam audio untuk input Voice Command navigasi tunanetra nanti
            add(Manifest.permission.RECORD_AUDIO)

            // Manajemen Bluetooth BLE & Akurasi Lokasi GPS sesuai regulasi Google
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                // Wajib sepasang Fine & Coarse agar lolos validasi security manifest
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                // Android 11 ke bawah
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        FirebaseUploader.stop()
        // Bersihkan interpreter TFLite dari RAM saat aplikasi ditutup total
        if (::objectDetector.isInitialized) {
            objectDetector.close()
        }
    }
}