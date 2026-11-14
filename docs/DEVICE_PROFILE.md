# Profil Perangkat Target: Samsung Galaxy A06 (`SM-A065F`)

Dokumen ini mendokumentasikan spesifikasi perangkat keras, batasan sistem operasi, konfigurasi daya tahan latar belakang One UI, serta petunjuk koneksi ADB dan LAN untuk pengujian **Gepy Native v6.6.0**.

---

## 1. Spesifikasi Teknis Perangkat

- **Chipset**: MediaTek Helio G85 (12 nm)
- **CPU**: Octa-core (2x 2.0 GHz Cortex-A75 & 6x 1.8 GHz Cortex-A55)
- **GPU**: Mali-G52 MC2
- **NPU / Neural Accelerator**: **Tidak Ada** (*No dedicated hardware AI acceleration*)
- **Sensor Gerak Bawaan**: Accelerometer & Hardware Pedometer (`Sensor.TYPE_STEP_COUNTER`)
- **RAM Fisik**: **4 GB** (LPDDR4X)
- **Penyimpanan Internal**: 64 GB / 128 GB (eMMC 5.1)
- **Sistem Operasi**: Android 14 / 15 dengan Samsung One UI 6.1 / 7.0
- **Arsitektur CPU (ABI)**: `arm64-v8a` (primer), `armeabi-v7a` (kompatibilitas)

---

## 2. Batasan Kritis & Anggaran Memori

1. **Ketiadaan NPU & Batasan RAM 4 GB**:
   - Model AI lokal besar (seperti Gemma 2B INT4 yang membutuhkan ~1,4 GB RAM) **dilarang dipaksakan berjalan on-device** karena akan memicu Android *Low Memory Killer (LMK)* menutup WhatsApp, Instagram, atau Gepy di latar belakang.
   - Gepy wajib beroperasi sebagai **Cloud-First Thin Client** dengan persepsi pohon UI & fast-path di memori lokal.
   - **Target Konsumsi Memori Gepy Native (Jetpack Compose + ART Runtime)**: **`~80–130 MB` Total PSS saat siaga**, **`<= 180 MB` Total PSS saat aktif** (*Normal*), dan batas plafon aman LMK **`< 240 MB` PSS** pada perangkat RAM 4 GB (`RuntimeMemoryDiagnostics` di Tab **Telemetri** memantau rincian PSS nyata secara real-time). Kapasitas indeks vektor semantik lokal dibatasi **`<= 1.000` vektor di RAM (`~3,0 MB`)** & **`<= 2.500` baris `memory_vectors` (`~7,5 MB` SQLite)** serta tabel **SEWM `ui_transition_rules` (`<= 2.000` aturan, `< 1,5 MB` SQLite, lookup RAM `< 0,1 ms`)** di **Room `v6`**. Setiap `Bitmap` *Set-of-Marks* wajib dibatasi `<= 720px` dan langsung dipanggil `Bitmap.recycle()`.

2. **Karakteristik Audio Samsung One UI**:
   - Stream `AudioAttributes.USAGE_ASSISTANT` dibisukan atau diarahkan ke earpiece jika aplikasi bukan asisten sistem default.
   - Wajib menggunakan `AudioAttributes.USAGE_MEDIA` dengan digital volume boost (`2.0x` + soft-knee limiter) agar suara asisten keluar jernih dari speaker multimedia bawah.
   - Filter hardware DSP Samsung (`noiseSuppress: true`) dapat memotong suku kata awal Bahasa Indonesia; diatasi dengan Native Android `SpeechRecognizer` (`id-ID`) dan `LocalPcmVad` adaptif (jeda hening `700 ms`, mic pre-amp `1.5x`).
   - **Anggaran Penyimpanan Audio Kustom (`VoiceAudioCacheManager`)**: Cache klip suara MP3 (`mp3_44100_64`) menggunakan default **`200 MB`** pada storage internal **eMMC** (dapat diatur di BYOK **`50–250 MB`**, setara `~7,3–9,1 jam` audio), diputar *streaming* via `MediaPlayer` dari disk sehingga mengonsumsi **`0 MB` tambahan RAM/PSS**.

3. **Android 14+ Restricted Settings**:
   - Android 14+ memblokir pengaktifan *Accessibility Service* untuk APK yang diinstal manual (*sideload*).
   - Solusi: Buka `Pengaturan -> Aplikasi -> Gepy -> Titik Tiga Kanan Atas -> Izinkan Pengaturan Terbatas (Allow Restricted Settings)` sebelum mengaktifkan layanan di menu Aksesibilitas.

4. **Daya Tahan Latar Belakang Samsung One UI (`Battery Survival`)**:
   - Agar `GepyAccessibilityService`, `WellnessAndGuardianEngine`, dan sesi panggilan suara panjang tidak dimatikan oleh *Freecess / App Standby* Samsung:
     - Buka `Pengaturan -> Aplikasi -> Gepy -> Baterai -> Tidak Dibatasi (Unrestricted)`.
     - Nonaktifkan *Put unused apps to sleep* untuk Gepy di menu **Perawatan Perangkat (Device Care)**.
   - Saat sesi Gemini Live aktif, `LiveCallPowerGuard` memegang `PARTIAL_WAKE_LOCK` dan `WIFI_MODE_FULL_LOW_LATENCY` dengan batas aman otomatis.

5. **Persyaratan Jaringan Lokal untuk Sensor ESP32-S3 Wi-Fi CSI**:
   - Galaxy A06 dan modul ESP32-S3 wajib terhubung ke jaringan Wi-Fi lokal `2.4 GHz` yang sama dengan fitur **AP Client Isolation = Disabled** di router, sehingga ESP32-S3 dapat mengirim HTTP `POST /csi-event` ke `EspCsiAmbientBridge` pada port `:8765`.

---

## 3. Panduan Koneksi ADB over Wi-Fi (Wireless Debugging)

Jika HP dan PC berada pada jaringan Wi-Fi yang sama:

### Opsi A: Android 11+ Wireless Debugging Resmi (Pairing Code)
1. Pada Galaxy A06, buka **Pengaturan -> Pilihan Pengembang -> Debug Nirkabel (Wireless Debugging)** -> Aktifkan.
2. Ketuk **Pasangkan perangkat dengan kode penyambungan (Pair device with pairing code)**.
3. Catat IP address, Port pairing, dan 6-digit Wi-Fi pairing code (misal: `192.168.1.15:37123`).
4. Di terminal PC (PowerShell), jalankan:
   ```powershell
   <ANDROID_SDK_PATH>\platform-tools\adb.exe pair 192.168.1.15:37123 <pairing_code>
   ```
5. Setelah berhasil dipasangkan, lihat IP dan Port koneksi utama di layar Debug Nirkabel (misal: `192.168.1.15:41235`), lalu sambungkan:
   ```powershell
   <ANDROID_SDK_PATH>\platform-tools\adb.exe connect 192.168.1.15:41235
   ```
6. Verifikasi perangkat terhubung:
   ```powershell
   <ANDROID_SDK_PATH>\platform-tools\adb.exe devices
   ```

### Opsi B: USB-to-TCPIP Sekali Pakai (Jika pernah dicolok USB sekali)
```powershell
<ANDROID_SDK_PATH>\platform-tools\adb.exe tcpip 5555
<ANDROID_SDK_PATH>\platform-tools\adb.exe connect <IP_GALAXY_A06>:5555
```
