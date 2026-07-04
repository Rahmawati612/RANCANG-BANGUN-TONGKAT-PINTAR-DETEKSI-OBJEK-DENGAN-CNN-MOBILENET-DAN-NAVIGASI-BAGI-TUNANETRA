#include <Wire.h>
#include "Adafruit_VL53L1X.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── BLE UUIDs ─────────────────────────────────────────────
#define SERVICE_UUID       "12345678-1234-1234-1234-123456789abc"
#define CHAR_DISTANCE_UUID "12345678-1234-1234-1234-123456789ab1"
#define CHAR_VIBRATE_UUID  "12345678-1234-1234-1234-123456789ab2"

// ── Pin Hardware ──────────────────────────────────────────
#define PIN_GETAR  25
#define SDA_PIN    21
#define SCL_PIN    22

// ── Sensor Jarak ───────────────────────────────────────────
Adafruit_VL53L1X lox;

// ── BLE Status ─────────────────────────────────────────────
BLEServer* pServer          = nullptr;
BLECharacteristic* pDistChar    = nullptr;
bool               bleConnected = false;

// ── Pengaturan Waktu (Timing) ──────────────────────────────
unsigned long prevLidarMillis = 0;
const long    INTERVAL_LIDAR  = 300;  // Jeda 300ms agar aman dari silau matahari

// ── Threshold Jarak (mm) ───────────────────────────────────
const int16_t JARAK_BAHAYA     = 500;   // 50 cm
const int16_t JARAK_PERINGATAN = 1000;  // 100 cm
const int16_t JARAK_WASPADA    = 2000;  // 200 cm
const int16_t JARAK_MAX        = 4000;  // 400 cm (Batas jangkauan sensor)

// ── Memori Filter Jarak Valid ──────────────────────────────
int16_t lastValidJarak = 0;

// ── Buffer Filter Moving Average (5 Sample) ────────────────
#define MA_SIZE 5
int16_t maSamples[MA_SIZE] = {0};
uint8_t maIndex = 0;
bool    maFilled = false;

int16_t movingAverage(int16_t newVal) {
  maSamples[maIndex] = newVal;
  maIndex = (maIndex + 1) % MA_SIZE;
  if (maIndex == 0) maFilled = true;

  uint8_t count = maFilled ? MA_SIZE : maIndex;
  int32_t sum = 0;
  for (uint8_t i = 0; i < count; i++) sum += maSamples[i];
  return (int16_t)(sum / count);
}

// ── Struktur Mesin Getar Non-Blocking (State Machine) ──────
struct VibrateState {
  bool          active     = false;
  int           totalTimes = 0;
  int           doneCount  = 0;
  int           durasiMs   = 0;
  unsigned long startMs    = 0;
  bool          pinHigh    = false;
} vib;

void startVibrate(int times, int durasiMs) {
  if (vib.active) return;  // Abaikan perintah getar baru jika getaran lama belum selesai
  vib.active     = true;
  vib.totalTimes = times;
  vib.doneCount  = 0;
  vib.durasiMs   = durasiMs;
  vib.startMs    = millis();
  vib.pinHigh    = true;
  digitalWrite(PIN_GETAR, HIGH);
}

// Mengatur siklus ON/OFF motor getar di latar belakang tanpa menghentikan program
void updateVibrate() {
  if (!vib.active) return;

  unsigned long now = millis();
  unsigned long elapsed = now - vib.startMs;

  if (vib.pinHigh) {
    // Fase ON: Matikan jika waktu aktif sudah habis
    if (elapsed >= (unsigned long)vib.durasiMs) {
      digitalWrite(PIN_GETAR, LOW);
      vib.pinHigh = false;
      vib.doneCount++;
      vib.startMs = now;
    }
  } else {
    // Fase OFF: Beri jeda 150ms sebelum ketukan berikutnya
    if (elapsed >= 150) {
      if (vib.doneCount >= vib.totalTimes) {
        vib.active = false; // Selesai semua ketukan
      } else {
        digitalWrite(PIN_GETAR, HIGH);
        vib.pinHigh = true;
        vib.startMs = now;
      }
    }
  }
}

// ── Jeda Getar ────────────────────────────
unsigned long lastVibrateMillis = 0;
const long    VIBRATE_COOLDOWN  = 800; 

// ── Deklarasi Fungsi ───────────────────────────────────────
void bacaLidar();
void kirimJarakBLE(int16_t jarak);

// ── BLE Callbacks Handler ──────────────────────────────────
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    bleConnected = true;
    Serial.println("[BLE] Android terhubung!");
  }
  void onDisconnect(BLEServer* s) override {
    bleConnected = false;
    Serial.println("[BLE] Terputus, memulai advertising ulang...");
    delay(300);
    s->startAdvertising();
  }
};

class VibrateCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    String val = pChar->getValue();
    if (val.length() == 0) return;
    uint8_t cmd = (uint8_t)val[0];
    startVibrate(cmd, 200);
    Serial.printf("[BLE] Instruksi getar masuk: %d kali\n", cmd);
  }
};

// ──────────────────────────────────────────────────────────
// SETUP
// ──────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  pinMode(PIN_GETAR, OUTPUT);
  digitalWrite(PIN_GETAR, LOW);

  // Inisialisasi Jalur I2C dan Sensor LiDAR
  Wire.begin(SDA_PIN, SCL_PIN);
  if (!lox.begin(0x29, &Wire)) {
    Serial.println("[ERROR] VL53L1X gagal inisialisasi!");
    while (1) delay(500);
  }

  lox.VL53L1X_SetDistanceMode(2);  // Aktifkan Jangkauan Jauh (Long Range Mode ~4 meter)
  lox.setTimingBudget(100);         
  lox.startRanging();
  Serial.println("[LiDAR] VL53L1X aktif | budget=100ms | interval=300ms | mode=Long");

  // Inisialisasi Fitur BLE (Bluetooth Low Energy)
  BLEDevice::init("ESP32-SmartCane");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService* pSvc = pServer->createService(SERVICE_UUID);

  pDistChar = pSvc->createCharacteristic(
    CHAR_DISTANCE_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pDistChar->addDescriptor(new BLE2902());

  BLECharacteristic* pVibChar = pSvc->createCharacteristic(
    CHAR_VIBRATE_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pVibChar->setCallbacks(new VibrateCallbacks());

  pSvc->start();
  BLEDevice::getAdvertising()->addServiceUUID(SERVICE_UUID);
  BLEDevice::getAdvertising()->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[BLE] Siap, menunggu koneksi HP...");

  // Tes getar awal 1x tanda booting sukses
  startVibrate(1, 300);
}

// ──────────────────────────────────────────────────────────
// LOOP UTAMA
// ──────────────────────────────────────────────────────────
void loop() {
  unsigned long now = millis();

  // Terus perbarui status getaran 
  updateVibrate();

  // Pengendali waktu baca sensor (bukan pakai delay)
  if (now - prevLidarMillis >= INTERVAL_LIDAR) {
    prevLidarMillis = now;
    bacaLidar();
  }
}

// ──────────────────────────────────────────────────────────
// FUNGSI UTAMA: BACA & FILTER SENSOR LIDAR
// ──────────────────────────────────────────────────────────
void bacaLidar() {
  if (!lox.dataReady()) {
    lox.clearInterrupt();
    return;
  }

  uint8_t  status = 0;
  int16_t  rawJarak = lox.distance();
  lox.VL53L1X_GetRangeStatus(&status);
  
  lox.clearInterrupt();

  bool bacaanValid = (status == 0 || status == 1) && (rawJarak > 30) && (rawJarak <= JARAK_MAX);

  int16_t jarak;
  if (bacaanValid) {
    // Haluskan data dengan rata-rata 5 sample terakhir
    jarak = movingAverage(rawJarak);
    lastValidJarak = jarak;
    Serial.printf("[LiDAR] Jarak Terbaca: %d mm (%.1f cm)\n", jarak, jarak / 10.0);
    
    // Kirim data nyata yang valid ke Android via BLE
    kirimJarakBLE(jarak);
  } else {
    jarak = -1;
    Serial.printf("[LiDAR BLANK] Status: %d | Mengirim sinyal -1 ke Android\n", status);
    
    kirimJarakBLE(jarak);
  }
  if (!bacaanValid) return;
  unsigned long now = millis();
  if (now - lastVibrateMillis >= VIBRATE_COOLDOWN && !vib.active) {
    if (jarak <= JARAK_BAHAYA) {
      Serial.println("[DANGER] Objek sangat dekat! Menjalankan getar 2x panjang.");
      startVibrate(2, 300);
      lastVibrateMillis = now;
    } else if (jarak <= JARAK_PERINGATAN) {
      Serial.println("[WARNING] Objek mendekat! Menjalankan getar 1x sedang.");
      startVibrate(1, 200);
      lastVibrateMillis = now;
    } else if (jarak <= JARAK_WASPADA) {
      Serial.println("[CAUTION] Ada objek di depan. Menjalankan getar 1x pendek.");
      startVibrate(1, 100);
      lastVibrateMillis = now;
    }
  }
}

// ──────────────────────────────────────────────────────────
// FUNGSI TRANSMISI: KIRIM DATA KE ANDROID VIA BLE
// ──────────────────────────────────────────────────────────
void kirimJarakBLE(int16_t jarak) {
  if (!bleConnected) return;
  uint8_t data[2] = {
    (uint8_t)(jarak >> 8),
    (uint8_t)(jarak & 0xFF)
  };
  pDistChar->setValue(data, 2);
  pDistChar->notify();
}
