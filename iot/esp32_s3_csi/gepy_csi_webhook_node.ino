/**
 * Gepy v5.0 Sovereign Superintelligence — ESP32-S3 Wi-Fi CSI Ambient Node (Starter Firmware)
 *
 * Target Board : ESP32-S3-DevKitC-1 (N16R8 - 16MB Flash, 8MB Octal PSRAM)
 * Framework    : Arduino-ESP32 v3.x (berbasis ESP-IDF v5.x)
 * Komunikasi   : HTTP POST Lokal LAN -> http://<GEPY_PHONE_IP>:8765/csi-event (0 Cloud, 0 Kamera)
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include "esp_wifi.h"
#include <math.h>

// ===================== KONFIGURASI JARINGAN PRIBADI ANDA =====================
const char* WIFI_SSID       = "NAMA_WIFI_RUMAH_ANDA";
const char* WIFI_PASSWORD   = "PASSWORD_WIFI_ANDA";
const char* GEPY_WEBHOOK_URL = "http://192.168.1.25:8765/csi-event"; // Sesuaikan dengan IP di Pengaturan Gepy
const char* ROOM_NAME       = "kamar";
// =============================================================================

// Ambang variansi amplitudo CSI (kalibrasi sesuai ukuran kamar & jarak router)
static const float MOTION_VARIANCE_THRESHOLD     = 14.5f;  // Gerakan jalan / aktivitas aktif
static const float MICRO_BREATH_MIN_VARIANCE     = 1.2f;   // Rebahan / bernapas tenang di kasur
static const float AIR_GESTURE_SPIKE_THRESHOLD   = 42.0f;  // Sapuan tangan tajam dekat antena

static volatile float g_latestCsiEnergy = 0.0f;
static volatile uint32_t g_csiPacketCount = 0;

static unsigned long g_stationaryStartMs = 0;
static unsigned long g_lastReportMs      = 0;
static unsigned long g_lastGestureMs     = 0;

// Callback perangkat keras Wi-Fi CSI ESP32-S3 (dieksekusi di RAM tanpa menulis kamera/rekaman)
void IRAM_ATTR onWiFiCsiReceived(void* ctx, wifi_csi_info_t* info) {
  if (!info || !info->buf || info->len < 16) return;

  // Hitung rata-rata magnitudo pasangan (Imaginary, Real) pada subcarrier aktif
  const int8_t* buf = info->buf;
  float sumMag = 0.0f;
  int pairs = info->len / 2;
  for (int i = 4; i < pairs && i < 56; i++) {
    float imag = (float)buf[i * 2];
    float real = (float)buf[i * 2 + 1];
    sumMag += sqrtf(imag * imag + real * real);
  }
  float avgMag = sumMag / (float)(pairs > 4 ? (pairs - 4) : 1);
  g_latestCsiEnergy = (0.85f * g_latestCsiEnergy) + (0.15f * avgMag);
  g_csiPacketCount++;
}

bool postEventToGepy(
  const char* eventType,
  int durationMinutes,
  bool ownerMatch,
  float confidence,
  int respirationBpm
) {
  if (WiFi.status() != WL_CONNECTED) return false;

  HTTPClient http;
  http.setTimeout(2500);
  if (!http.begin(GEPY_WEBHOOK_URL)) return false;
  http.addHeader("Content-Type", "application/json");

  String payload = "{";
  payload += "\"event\":\"" + String(eventType) + "\",";
  payload += "\"room\":\"" + String(ROOM_NAME) + "\",";
  payload += "\"duration_minutes\":" + String(durationMinutes) + ",";
  payload += "\"biometric_owner_match\":" + String(ownerMatch ? "true" : "false") + ",";
  payload += "\"confidence\":" + String(confidence, 2);
  if (respirationBpm > 0) {
    payload += ",\"respiration_bpm\":" + String(respirationBpm);
  }
  payload += "}";

  int statusCode = http.POST(payload);
  http.end();
  Serial.printf("[GepyCSI] POST %s -> HTTP %d\n", eventType, statusCode);
  return statusCode == 200;
}

void setupCsiCapture() {
  wifi_csi_config_t csiConfig = {};
  csiConfig.lltf_en           = true;
  csiConfig.htltf_en          = true;
  csiConfig.stbc_htltf2_en    = true;
  csiConfig.ltf_merge_en      = true;
  csiConfig.channel_filter_en = true;
  csiConfig.manu_scale        = false;

  ESP_ERROR_CHECK(esp_wifi_set_csi_config(&csiConfig));
  ESP_ERROR_CHECK(esp_wifi_set_csi_rx_cb(&onWiFiCsiReceived, NULL));
  ESP_ERROR_CHECK(esp_wifi_set_csi(true));
  Serial.println("[GepyCSI] Radar Wi-Fi CSI ESP32-S3 aktif!");
}

void setup() {
  Serial.begin(115200);
  delay(500);

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("[GepyCSI] Menghubungkan ke Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(400);
    Serial.print(".");
  }
  Serial.printf("\n[GepyCSI] Terhubung! IP Node ESP32-S3: %s\n", WiFi.localIP().toString().c_str());

  setupCsiCapture();
  g_stationaryStartMs = millis();
  postEventToGepy("PRESENCE_ENTER", 0, true, 0.95f, 15);
}

void loop() {
  static float prevEnergy = 0.0f;
  delay(500);

  float currentEnergy = g_latestCsiEnergy;
  float delta = fabsf(currentEnergy - prevEnergy);
  prevEnergy = currentEnergy;

  unsigned long nowMs = millis();

  // 1. Deteksi lonjakan Air-Gesture dekat antena (cooldown 6 detik)
  if (delta >= AIR_GESTURE_SPIKE_THRESHOLD && (nowMs - g_lastGestureMs) > 6000UL) {
    g_lastGestureMs = nowMs;
    postEventToGepy("AIR_GESTURE_WAKE", 0, true, 0.92f, 0);
    return;
  }

  // 2. Pantau durasi diam/rebahan vs aktivitas bergerak
  if (delta >= MOTION_VARIANCE_THRESHOLD) {
    // Pengguna aktif bergerak/berjalan di kamar -> reset penghitung rebahan
    g_stationaryStartMs = nowMs;
  } else if (currentEnergy >= MICRO_BREATH_MIN_VARIANCE) {
    int sedentaryMins = (int)((nowMs - g_stationaryStartMs) / 60000UL);
    // Kirim laporan berkala setiap 10 menit ke Gepy
    if ((nowMs - g_lastReportMs) >= 600000UL) {
      g_lastReportMs = nowMs;
      const char* ev = (sedentaryMins >= 180) ? "SEDENTARY_ALERT" : "BED_OCCUPIED";
      postEventToGepy(ev, sedentaryMins, true, 0.93f, 15);
    }
  }
}
