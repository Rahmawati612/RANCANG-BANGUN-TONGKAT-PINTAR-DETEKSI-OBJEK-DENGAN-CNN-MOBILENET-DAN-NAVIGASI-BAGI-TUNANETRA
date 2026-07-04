package com.skripsi.smart_cane

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BleViewModel(application: Application) : AndroidViewModel(application) {
    val bleManager = BleManager(application)

    val distance = bleManager.distance.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val connected = bleManager.connected.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val isScanning = bleManager.isScanning.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    // Mengambil data GPS reaktif dari BleManager
    val gpsData = bleManager.gpsData.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "Mencari koordinat..."
    )

    // Menyimpan nilai batas jarak (threshold) dari Slider secara reaktif
    private val _thresholdCm = MutableStateFlow(100f)
    val thresholdCm: StateFlow<Float> = _thresholdCm

    fun updateThreshold(value: Float) {
        _thresholdCm.value = value
    }

    // Logika alert otomatis: membandingkan jarak asli (mm) dengan threshold dari slider (cm)
    val alertLevel: StateFlow<AlertLevel> = combine(distance, _thresholdCm) { mm, threshold ->
        val currentCc = mm / 10
        when {
            currentCc <= 0                  -> AlertLevel.SAFE     // Jika data 0 (tidak valid / di luar jangkauan)
            currentCc <= (threshold * 0.3f) -> AlertLevel.DANGER   // Sangat dekat dari batas threshold (Bahaya)
            currentCc <= threshold          -> AlertLevel.WARNING  // Di bawah batas threshold (Waspada)
            else                            -> AlertLevel.SAFE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertLevel.SAFE)

    init {
        viewModelScope.launch {
            alertLevel.collect { level ->
                if (connected.value) { // Hanya mengirim perintah getar jika tongkat terhubung
                    when (level) {
                        AlertLevel.DANGER  -> bleManager.sendVibrate(5) // Getar kuat 500ms
                        AlertLevel.WARNING -> bleManager.sendVibrate(2) // Getar tipis 200ms
                        AlertLevel.SAFE    -> {}
                    }
                }
            }
        }
    }

    fun toggleScan() {
        if (isScanning.value) {
            bleManager.stopScan()
        } else {
            bleManager.startScan()
        }
    }

    override fun onCleared() {
        bleManager.disconnect()
    }
}

enum class AlertLevel { SAFE, WARNING, DANGER }