package com.skripsi.smart_cane

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

class BleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var bluetoothGatt: BluetoothGatt? = null
    private var vibrateChar: BluetoothGattCharacteristic? = null

    // StateFlow yang akan di-observe secara reaktif oleh Compose UI
    private val _distance = MutableStateFlow(0)
    val distance: StateFlow<Int> = _distance

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _deviceName = MutableStateFlow("Belum ada perangkat")
    val deviceName: StateFlow<String> = _deviceName

    // Tambahan StateFlow untuk menampung data GPS reaktif dari ESP32
    private val _gpsData = MutableStateFlow("Mencari koordinat...")
    val gpsData: StateFlow<String> = _gpsData

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        _isScanning.value = true
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        _isScanning.value = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Menemukan ESP32 berdasarkan kecocokan nama perangkat bluetooth
            if (device.name != null && device.name.contains("ESP32", ignoreCase = true)) {
                _deviceName.value = device.name
                stopScan()
                connect(device.address) // Otomatis menyambungkan gatt saat ketemu
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: return
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connected.value = false
        _deviceName.value = "Belum ada perangkat"
        _gpsData.value = "Mencari koordinat..."
    }

    @SuppressLint("MissingPermission")
    fun sendVibrate(durasi: Int) {
        vibrateChar?.let { char ->
            char.value = byteArrayOf(durasi.toByte())
            bluetoothGatt?.writeCharacteristic(char)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connected.value = true
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: return

            // 1. Menyimpan karakteristik getar untuk perintah kirim data ke ESP32
            vibrateChar = service.getCharacteristic(UUID.fromString(CHAR_VIBRATE_UUID))

            // 2. Mengaktifkan sistem pemberitahuan (Notify) untuk jarak LiDAR
            val distChar = service.getCharacteristic(UUID.fromString(CHAR_DISTANCE_UUID))
            if (distChar != null) {
                gatt.setCharacteristicNotification(distChar, true)
                val descriptor = distChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }

            // 3. Mengaktifkan sistem pemberitahuan (Notify) untuk Koordinat GPS
            val gpsChar = service.getCharacteristic(UUID.fromString(CHAR_GPS_UUID))
            if (gpsChar != null) {
                gatt.setCharacteristicNotification(gpsChar, true)
                val descriptor = gpsChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                UUID.fromString(CHAR_DISTANCE_UUID) -> {
                    val bytes = characteristic.value
                    // Konversi data byte mentah dari ESP32 menjadi angka milimeter (Int)
                    val mm = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    _distance.value = mm
                }
                UUID.fromString(CHAR_GPS_UUID) -> {
                    // Menerima data string teks mentah dari modul GPS di ESP32 (contoh: "-7.1234,112.5678")
                    val rawGpsString = characteristic.getStringValue(0)
                    if (rawGpsString != null) {
                        _gpsData.value = rawGpsString
                    }
                }
            }
        }
    }
}