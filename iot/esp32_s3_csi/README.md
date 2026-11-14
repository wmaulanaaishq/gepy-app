# Modul IoT Pendamping Gepy v5.0 — Radar Ruangan ESP32-S3 Wi-Fi CSI

Direktori ini berisi panduan perangkat keras dan *starter firmware* untuk menghubungkan node **ESP32-S3** secara lokal (100% LAN, 0% Cloud, tanpa kamera) ke [`EspCsiAmbientBridge.kt`](../../app/src/main/kotlin/com/teknologigawai/gepy/core/iot/EspCsiAmbientBridge.kt) di HP Android Anda.

---

## 1. Rekomendasi Perangkat Keras (Estimasi Rp 150.000 – Rp 350.000)

| Komponen | Spesifikasi Wajib | Fungsi |
| :--- | :--- | :--- |
| **Board Utama** | **ESP32-S3-DevKitC-1 (N16R8)** — *16 MB Flash + 8 MB Octal PSRAM* (konektor antena IPEX/U.FL disarankan) | Mengekstrak fluktuasi *Channel State Information (CSI)* 52–64 *subcarrier* Wi-Fi 2.4 GHz & akselerasi DSP vektor `ESP-DL`. |
| **Antena Eksternal** | Antena 2.4 GHz 3–5 dBi dengan kabel pigtail IPEX ke SMA | Meningkatkan *Signal-to-Noise Ratio (SNR)* agar deteksi napas (BPM) & gerakan jari lebih tajam. |
| **Catu Daya** | Kabel USB-C Data + Adaptor 5V 1A stabil | Menyalakan node radar 24/7 di sudut kamar (`< 0,8 Watt`). |

> **Penempatan Optimal di Kamar**: Letakkan router Wi-Fi (atau node pemancar) dan board **ESP32-S3** secara berseberangan sehingga area kasur dan meja kerja berada di jalur lintasan gelombang (*Fresnel Zone*).

---

## 2. Kontrak Endpoint Lokal (`EspCsiAmbientBridge.kt`)

1. Buka aplikasi **Gepy v5.0** -> Tab **Pengaturan** -> Nyalakan sakelar **Jembatan LAN ESP32-S3 CSI (`:8765`)**.
2. Catat alamat URL yang tampil di layar HP, contoh: `http://192.168.1.25:8765/csi-event`.
3. Keamanan **Zero-Trust LAN Isolation**: Server di HP menolak seluruh paket yang bukan berasal dari jaringan privat lokal (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, `127.0.0.1`).

### Skema JSON `POST /csi-event`

```json
{
  "event": "PRESENCE_ENTER | PRESENCE_EXIT | BED_OCCUPIED | SEDENTARY_ALERT | AIR_GESTURE_WAKE | GAIT_BIOMETRIC",
  "room": "kamar",
  "duration_minutes": 185,
  "biometric_owner_match": true,
  "confidence": 0.94,
  "respiration_bpm": 15
}
```

### Perilaku Reaktif di Gepy v5.0:
- **`AIR_GESTURE_WAKE`**: Jika `biometric_owner_match == true`, Gepy langsung membuka pendengaran perintah suara tanpa menyentuh layar HP. Jika `false` (orang lain), diabaikan!
- **`GAIT_BIOMETRIC` (`biometric_owner_match: false`)**: Mengaktifkan **Privacy Shield** — Gepy otomatis menahan pembacaan isi notifikasi pribadi dan menyensor informasi sensitif saat ada tamu di ruangan.
- **`BED_OCCUPIED` / `SEDENTARY_ALERT`**: Mengirim durasi rebahan (`duration_minutes`) dan laju napas (`respiration_bpm`) ke [`WellnessAndGuardianEngine.kt`](../../app/src/main/kotlin/com/teknologigawai/gepy/core/wellness/WellnessAndGuardianEngine.kt). Jika pada jam aktif (`07:00–21:00`) rebahan `>= 180 menit` & langkah kaki `< 300`, persona **Mentor Tegas** akan menegur Anda secara lisan.
- **`PRESENCE_ENTER`**: Sapaan penyambutan otomatis saat Anda baru masuk kamar (maksimal 1x setiap 4 jam).

---

## 3. Cara Menguji Tanpa Board ESP32 (Dari Laptop / PowerShell)

Pastikan laptop dan HP terhubung ke Wi-Fi yang sama, lalu jalankan perintah berikut (ganti `192.168.1.25` dengan IP HP yang tampil di Pengaturan Gepy):

```powershell
# 1. Cek kesehatan jembatan CSI Gepy
Invoke-RestMethod -Uri "http://192.168.1.25:8765/health" -Method Get

# 2. Uji pemicu mikrofon tanpa sentuh (Air-Gesture Wake)
Invoke-RestMethod -Uri "http://192.168.1.25:8765/csi-event" -Method Post -ContentType "application/json" -Body '{"event":"AIR_GESTURE_WAKE","room":"kamar","biometric_owner_match":true}'

# 3. Uji aktifkan Privacy Shield saat ada tamu masuk kamar
Invoke-RestMethod -Uri "http://192.168.1.25:8765/csi-event" -Method Post -ContentType "application/json" -Body '{"event":"GAIT_BIOMETRIC","room":"kamar","biometric_owner_match":false,"confidence":0.89}'
```

---

## 4. Berkas Firmware di Folder Ini

- [`gepy_csi_webhook_node.ino`](./gepy_csi_webhook_node.ino): Sketsa awal Arduino / ESP-IDF `esp_wifi_set_csi_rx_cb` untuk **ESP32-S3** yang menghitung variansi amplitudo *subcarrier* CSI secara real-time di PSRAM dan mengirimkan webhook JSON ke Gepy.
