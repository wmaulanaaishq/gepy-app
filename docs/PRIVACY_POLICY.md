# Kebijakan Privasi — Gepy Native (`com.teknologigawai.gepy`)

**Terakhir Diperbarui:** 5 November 2026  
**Pengembang:** Teknologi Gawai Indonesia (`com.teknologigawai.gepy`)  
**Kontak Privasi:** `privacy@teknologigawai.com`

---

## 1. Pendahuluan & Komitmen Privasi
Aplikasi **Gepy Native** ("Gepy", "kami", atau "aplikasi") berkomitmen penuh untuk melindungi privasi, kedaulatan data, dan keamanan pengguna. Gepy dirancang sebagai **Personal AI Agent** otonom dengan kontrol suara dan otomasi antarmuka Android (**Accessibility Tool**) untuk membantu pengguna menavigasi perangkat dan menyelesaikan tugas aplikasi secara hands-free melalui perintah suara atau teks.

Kebijakan Privasi ini menjelaskan bagaimana kami mengelola data Anda sesuai dengan **Kebijakan Pengembang Google Play (Google Play Developer Policy)**, khususnya terkait penggunaan **Accessibility API**, **Izin Mikrofon**, dan data sensitif pengguna.

---

## 2. Penggunaan Layanan Aksesibilitas (Accessibility API)

Gepy terdaftar sebagai alat bantu aksesibilitas resmi (`isAccessibilityTool="true"` pada Android Accessibility Service).

### A. Data yang Diakses
Ketika Layanan Aksesibilitas diaktifkan oleh pengguna, Gepy mengakses pohon antarmuka pengguna (`AccessibilityNodeInfo`) dari aplikasi yang sedang berjalan di layar. Data ini mencakup:
- Teks label tombol, judul layar, dan elemen interaktif.
- Posisi koordinat tombol untuk memandu ketukan atau gestur navigasi.

### B. Tujuan Penggunaan
Data tampilan layar diakses semata-mata untuk:
1. **Membacakan Layar (Screen Reading)**: Membacakan atau merangkum teks informasi di layar secara hands-free (*eyes-free operation*).
2. **Navigasi Otomatis Berbasis Suara**: Menemukan elemen UI sesuai perintah suara pengguna (contoh: *"Buka WhatsApp lalu kirim pesan ke Budi"*).

### C. Batasan & Perlindungan Privasi Accessibility API
- **Pemrosesan Sementara (In-Memory Only)**: Data pohon elemen layar hanya diproses sementara di RAM perangkat selama siklus perintah suara aktif. Gepy **TIDAK PERNAH** menyimpan rekaman layar atau teks tampilan ke penyimpanan permanen.
- **TIDAK Dijual / Dibagikan**: Data layar tidak pernah dijual, tidak dibagikan kepada broker data, dan tidak digunakan untuk penargetan iklan.

---

## 3. Perlindungan Mutlak Aplikasi Keuangan & Kata Sandi (`SafetyRules`)

Keamanan pengguna adalah prioritas tertinggi kami. Gepy memiliki mekanisme perlindungan berbasis kode permanen (*hardcoded safety gatekeeper*):
1. **Daftar Hitam Aplikasi Perbankan & Dompet Digital**:
   Gepy secara mutlak menolak untuk membaca atau berinteraksi dengan aplikasi keuangan, termasuk namun tidak terbatas pada:
   - BCA Mobile (`com.bca`)
   - Livin' by Mandiri (`id.bmri.livin`)
   - BRImo (`id.co.bri.brimo`)
   - BNI Mobile Banking
   - DANA (`id.dana`), OVO, GoPay, ShopeePay, LinkAja.
2. **Perlindungan Kolom Sandi & PIN**:
   Gepy secara otomatis mendeteksi elemen input yang memiliki atribut `isPassword == true` atau berlabel kata sandi/PIN/OTP dan seketika membatalkan pembacaan maupun injeksi teks.
3. **Konfirmasi Tindakan Kritis (Human-in-the-Loop)**:
   Untuk tindakan ireversibel (seperti menekan tombol "Kirim", "Hapus", atau transfer), Gepy mewajibkan persetujuan eksplisit dari pengguna sebelum aksi dieksekusi.

---

## 4. Izin Mikrofon & Rekaman Audio (`RECORD_AUDIO`)

- **Aktivasi Terkontrol**: Mikrofon hanya aktif saat pengguna secara sengaja menekan tombol mikrofon mengambang (*Overlay HUD*) atau mengaktifkan mode panggilan dua arah (*Gemini Live Voice Mode*).
- **Tidak Ada Perekaman Latar Belakang Tersembunyi**: Gepy tidak pernah menguping atau merekam audio secara pasif di latar belakang tanpa sepengetahuan pengguna. Status aktif mikrofon selalu ditandai oleh indikator sistem Android dan jendela animasi Gepy.
- **Transkripsi Suara**: Audio diubah menjadi teks melalui pengenal suara pada perangkat (*Android SpeechRecognizer*) atau API suara yang dipilih pengguna.

---

## 5. Model Pemrosesan AI & Bring Your Own Key (BYOK)

Gepy mendukung arsitektur *Bring Your Own Key (BYOK)*:
- Pengguna dapat memasukkan kunci API mandiri (Google AI Studio, Fireworks AI, OpenRouter, Groq, atau DeepSeek).
- Kunci API pengguna disimpan secara lokal di perangkat menggunakan `SharedPreferences` terenkripsi dan hanya digunakan untuk mengirimkan instruksi pengguna langsung ke endpoint AI yang dipilih.

---

## 6. Telemetri & Diagnostik Aplikasi (Sentry Android)

Untuk menjaga stabilitas aplikasi dan memantau crash pada berbagai varian perangkat Android, Gepy menggunakan pustaka telemetri resmi **Sentry Android SDK**:
- Data yang dikirim mencakup: tipe perangkat (misal: Samsung Galaxy A06), versi OS Android, jenis kesalahan/crash log, dan latensi kompresi pohon UI.
- **Pembersihan Data Sensitif (PII Scrubbing)**: Seluruh nama kontak, nomor telepon, dan teks pesan disaring sebelum laporan diagnostik dikirimkan ke dasbor telemetri Sentry (jika dikonfigurasi).

---

## 7. Hak Pengguna & Kontrol Data

1. **Pencabutan Izin Kapan Saja**: Anda dapat sewaktu-waktu mematikan Layanan Aksesibilitas atau izin Mikrofon melalui menu **Pengaturan Android > Aksesibilitas > Gepy**.
2. **Pembersihan Data Lokal**: Anda dapat menghapus seluruh riwayat perintah dan *scratchpad memory* melalui tombol "Hapus Memori" di tab Telemetri aplikasi Gepy.
3. **Penghapusan Aplikasi**: Menghapus (*uninstall*) aplikasi Gepy dari perangkat Anda akan secara otomatis memusnahkan seluruh basis data lokal SQLite (`gepy_core.db`) dan preferensi yang tersimpan.

---

## 8. Persetujuan & Kepatuhan Kebijakan Google Play

Dengan memasang dan menggunakan Gepy Native, Anda menyatakan telah membaca, memahami, dan menyetujui ketentuan dalam Kebijakan Privasi ini serta tampilan *Prominent Disclosure* yang disajikan di dalam aplikasi.

Jika ada pertanyaan atau masukan mengenai kebijakan privasi ini, silakan hubungi kami di:
**Email:** `privacy@teknologigawai.com`  
**Alamat:** Yogyakarta, Indonesia
