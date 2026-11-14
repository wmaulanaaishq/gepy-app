package com.teknologigawai.gepy.features.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Skema deklarasi 35 tool resmi untuk protokol WebSocket **Gemini Live (`BidiGenerateContent`)**.
 * Dipindai oleh `scripts/evals/runner.py` untuk validasi sinkronisasi `OFFICIAL_TOOLS`.
 */
internal object GeminiLiveToolDeclarations {

    private fun putToolDeclaration(
        array: JSONArray,
        name: String,
        description: String,
        parameters: JSONObject,
        includeNonBlocking: Boolean
    ) {
        val obj = JSONObject()
            .put("name", name)
            .put("description", description)
        if (includeNonBlocking) {
            obj.put("behavior", "NON_BLOCKING")
        }
        obj.put("parameters", parameters)
        array.put(obj)
    }

    fun buildFunctionDeclarations(includeNonBlocking: Boolean): JSONArray {
        val functionDeclarations = JSONArray()
        putToolDeclaration(
            array = functionDeclarations,
            name = "open_application",
            description = "Membuka atau berpindah ke aplikasi Android apa pun secara instan (< 15 ms) HANYA jika pengguna murni meminta membuka aplikasi tanpa tugas lanjutan di dalamnya (contoh: 'Buka WhatsApp', 'Buka Instagram'). Jika ada tugas lanjutan seperti mencari akun, mengirim DM/pesan, atau memutar video, WAJIB gunakan execute_android_goal.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "app_name",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Nama aplikasi yang ingin dibuka (misal: Instagram, WhatsApp, YouTube).")
                    )
                )
                .put("required", JSONArray().put("app_name"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "execute_android_goal",
            description = "Menjalankan tugas otomasi UI multi-langkah di perangkat Android pengguna, termasuk mencari orang/akun di Instagram, mengirim DM Instagram, mengirim pesan WhatsApp, mencari/memutar video di YouTube/TikTok, mencari barang di Shopee/Tokopedia, atau mengoperasikan tombol di dalam aplikasi apa pun.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "goal",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Instruksi lengkap tujuan pengguna dalam Bahasa Indonesia (contoh: 'Cari akun teknologi gawai di Instagram lalu kirim DM halo').")
                        )
                        .put(
                            "initial_app",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Nama aplikasi target utama untuk memulai tugas (misal: Instagram, WhatsApp, YouTube, TikTok, Shopee, Telegram).")
                        )
                )
                .put("required", JSONArray().put("goal"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "read_current_screen",
            description = "Membaca, memeriksa, dan merangkum isi layar HP Android pengguna secara langsung saat ini.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "focus_area",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Fokus informasi yang diminta (misal: semua, pesan).")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "cancel_android_goal",
            description = "Menghentikan seketika eksekusi otomasi UI Android yang sedang berjalan.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "reason",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Alasan pembatalan dari pengguna.")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_active_task_status",
            description = "Membaca status kemajuan tugas otomasi UI Android yang sedang berjalan di layar secara tepat (langkah aktif, aplikasi target, aksi terakhir, dan apakah sedang dijeda) agar asisten dapat memberi tahu pengguna secara akurat.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "detail_level",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Tingkat rincian status: summary atau full.")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "pause_or_resume_goal",
            description = "Menjeda sementara (action: 'pause') atau melanjutkan kembali (action: 'resume') eksekusi tugas UI yang sedang berjalan di layar ketika pengguna ingin mengklarifikasi sesuatu.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("action", JSONObject().put("type", "STRING").put("description", "Aksi: 'pause' untuk menjeda atau 'resume' untuk melanjutkan."))
                        .put("reason", JSONObject().put("type", "STRING").put("description", "Alasan jeda."))
                )
                .put("required", JSONArray().put("action"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "search_web_realtime",
            description = "Mencari informasi terkini di internet (berita hari ini, cuaca, kurs rupiah/dollar, harga emas/saham, skor pertandingan, jadwal, sains, resep, perbandingan spesifikasi barang, atau data luas terverifikasi).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "query",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Kata kunci atau kalimat pencarian informasi web dalam Bahasa Indonesia.")
                    )
                )
                .put("required", JSONArray().put("query"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "read_webpage_content",
            description = "Membaca dan mengekstrak isi lengkap artikel atau halaman web dari alamat URL tertentu menjadi teks bersih bebas iklan.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "url",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Alamat URL tautan web yang ingin dibaca (contoh: 'https://kompas.com/...').")
                    )
                )
                .put("required", JSONArray().put("url"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_device_and_time_status",
            description = "Membaca status sistem HP Android secara instan (< 5 ms): jam/tanggal Bahasa Indonesia, persentase baterai, pengisian daya, koneksi jaringan Wi-Fi/Seluler, dan volume.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "detail_level",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Tingkat rincian status perangkat (misal: full, summary).")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "search_phone_contacts",
            description = "Mencari nama kontak dan nomor telepon langsung dari buku telepon Android (ContactsContract).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "query",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Nama kontak yang ingin dicari.")
                    )
                )
                .put("required", JSONArray().put("query"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_recent_notifications",
            description = "Membaca daftar notifikasi terbaru yang masuk di sistem Android pengguna.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "max_count",
                        JSONObject()
                            .put("type", "INTEGER")
                            .put("description", "Jumlah maksimum notifikasi terbaru yang dibaca (default 5).")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_current_location",
            description = "Membaca koordinat lokasi GPS terakhir perangkat Android pengguna.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "include_accuracy",
                        JSONObject()
                            .put("type", "BOOLEAN")
                            .put("description", "Sertakan akurasi radius meter GPS.")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "set_system_alarm",
            description = "Memasang alarm sistem Android secara instan tanpa membuka aplikasi Jam.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("hour", JSONObject().put("type", "INTEGER").put("description", "Jam (0-23)"))
                        .put("minute", JSONObject().put("type", "INTEGER").put("description", "Menit (0-59)"))
                        .put("label", JSONObject().put("type", "STRING").put("description", "Judul alarm"))
                )
                .put("required", JSONArray().put("hour").put("minute"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "open_system_settings_panel",
            description = "Membuka panel pengaturan sistem Android (Wi-Fi, Bluetooth, Suara, Layar, Aksesibilitas, atau Lokasi) secara instan (< 15 ms).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "panel",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Nama panel pengaturan: wifi, bluetooth, sound, display, accessibility, atau location.")
                    )
                )
                .put("required", JSONArray().put("panel"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "open_maps_navigation",
            description = "Meluncurkan pencarian atau navigasi rute Google Maps menuju lokasi tujuan secara langsung via Deep-Link.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("destination", JSONObject().put("type", "STRING").put("description", "Nama tempat atau alamat tujuan."))
                        .put("navigate_directly", JSONObject().put("type", "BOOLEAN").put("description", "True untuk mulai navigasi rute langsung."))
                )
                .put("required", JSONArray().put("destination"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "save_user_memory",
            description = "Menyimpan fakta penting baru tentang pengguna (nama panggilan, pekerjaan, keluarga, alamat, jadwal sementara, kebiasaan, preferensi) ke Graf Memori Temporal lokal agar Anda mengingatnya di sesi berikutnya. Untuk relasi keluarga majemuk, rangkum seluruh anggota dalam SATU kalimat lengkap. Untuk jadwal/agenda berbatas waktu (seperti 'hari ini lembur' atau 'besok ada rapat'), isi valid_hours agar otomatis kedaluwarsa setelah lewat.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "category",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Kategori memori: profile, work, schedule, preference, family, location, habits, atau general.")
                        )
                        .put(
                            "fact",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Kalimat fakta lengkap, ringkas, dan jelas yang perlu diingat (contoh: 'Nama pengguna adalah Maulana', 'Pengguna bekerja di PT Teknologi', 'Besok jam 9 pagi ada rapat tim').")
                        )
                        .put(
                            "subject",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Topik spesifik: user_work, user_name, location_home, schedule_agenda, family_siblings, family_father, family_mother, dll.")
                        )
                        .put(
                            "predicate",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Relasi triplet opsional: works_at, lives_in, is_named, likes, allergic_to, has_schedule.")
                        )
                        .put(
                            "object_value",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Nilai target spesifik dari fakta (contoh: 'PT Teknologi Nusantara', 'Jakarta', 'Rapat Jam 9').")
                        )
                        .put(
                            "valid_hours",
                            JSONObject()
                                .put("type", "INTEGER")
                                .put("description", "Batas kedaluwarsa fakta dalam jam untuk catatan sementara (contoh: 24 untuk hari ini, 48 untuk besok). Kosongkan jika permanen.")
                        )
                )
                .put("required", JSONArray().put("fact"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "query_memory_history",
            description = "Menelusuri riwayat memori temporal pengguna dari masa lalu ke masa kini (misalnya saat pengguna bertanya 'Dulu saya kerja di mana?' atau 'Alamat lama saya di mana?').",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "topic",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Topik atau kata kunci riwayat yang dicari (contoh: 'kerja', 'kantor', 'alamat', 'rapat').")
                    )
                )
                .put("required", JSONArray().put("topic"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "update_or_correct_memory",
            description = "Mengoreksi, merevisi, atau memperbarui fakta memori jangka panjang pengguna yang keliru, salah dengar, atau telah berubah (contoh: jika pengguna meralat 'Bukan Ricky tapi Rizky', panggil fungsi ini dengan target_to_replace='Ricky' dan corrected_fact='Adik pengguna bernama Rizky, bukan Ricky').",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "target_to_replace",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Nama atau kata kunci fakta lama yang salah dengar atau keliru (contoh: 'Ricky', 'ayah', 'kopi manis').")
                        )
                        .put(
                            "corrected_fact",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Kalimat fakta baru yang benar dan lengkap (contoh: 'Adik pengguna bernama Rizky').")
                        )
                        .put(
                            "category",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Kategori memori: family, profile, preference, location, general.")
                        )
                )
                .put("required", JSONArray().put("target_to_replace").put("corrected_fact"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "delete_user_memory",
            description = "Menghapus fakta tertentu dari memori jangka panjang jika pengguna meminta untuk melupakan atau membatalkan suatu fakta.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "target",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Kata kunci atau nama fakta yang ingin dihapus dari memori.")
                    )
                )
                .put("required", JSONArray().put("target"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "end_live_call",
            description = "Mengakhiri dan menutup sesi panggilan suara Gepy Live Call seketika ketika pengguna berpamitan, mengucapkan selamat tinggal, atau meminta mematikan/menutup telepon (contoh: 'Tutup telepon', 'Matikan panggilan', 'Sudah dulu ya', 'Sampai jumpa', 'Bye Gepy', 'Berhenti bicara').",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "farewell",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Kalimat perpisahan singkat, hangat, dan sopan sebelum sambungan telepon dimatikan (contoh: 'Siap, sampai jumpa lagi!').")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_personal_wellness_status",
            description = "Membaca data terukur nyata: jumlah langkah kaki hari ini dari pedometer hardware HP dan durasi layar media sosial (anti-doomscrolling). Data ruangan CSI hanya tersedia jika modul ESP32-S3 fisik terhubung (lihat field has_external_csi_telemetry).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "detail_level",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Tingkat rincian laporan kesehatan (misal: summary, full).")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_daily_briefing",
            description = "Merakit ringkasan harian jujur berbasis data nyata (agenda hari ini dari memori temporal, langkah pedometer, durasi layar medsos, baterai HP). Panggil saat pengguna meminta 'briefing', 'ringkasan hari ini', atau 'agenda hari ini apa'.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "period",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Opsional: auto, morning, atau evening.")
                    )
                )
        )

        putToolDeclaration(
            array = functionDeclarations,
            name = "query_huggingface_trending",
            description = "Mencari model AI/ML open-source yang sedang trending atau model spesifik di Hugging Face Hub (contoh: DeepSeek, Llama, Qwen, model vision/reasoning terbaru).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "query",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Kata kunci model atau biarkan kosong untuk model trending saat ini.")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_huggingface_model_details",
            description = "Mendapatkan rincian mendalam model AI di Hugging Face Hub: task/pipeline, arsitektur, jumlah unduhan, likes, tanggal rilis/pembuatan, tanggal update terakhir, dan lisensi.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "model_id",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "ID atau nama model di Hugging Face (contoh: 'moonshotai/Kimi-K3.1-Pro', 'Qwen/Qwen3.8-27B', atau 'kimi').")
                    )
                )
                .put("required", JSONArray().put("model_id"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "query_github_trending_or_search",
            description = "Mencari repository kode sumber terbuka di GitHub berdasarkan bintang atau topik (contoh: 'agentic-ai', 'autonomous-agent', framework AI, Kotlin, Python).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "query",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Topik atau nama repo yang dicari di GitHub.")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_github_repo_details",
            description = "Mendapatkan rincian mendalam tentang repository GitHub: deskripsi lengkap, bintang, forks, bahasa utama, lisensi, tanggal rilis/pembuatan awal, tanggal update, dan versi rilis terbaru beserta tanggal rilisnya (kapan rilis).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "repo",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Nama repository atau kata kunci pencarian (contoh: 'langchain-ai/langgraph', 'langgraph', 'fastapi').")
                    )
                )
                .put("required", JSONArray().put("repo"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_github_readme",
            description = "Membaca dan menyintesis isi berkas README.md dari repository GitHub: ikhtisar proyek, fitur-fitur utama, dan panduan instalasi atau cara penggunaan (Quickstart). WAJIB panggil ini jika pengguna meminta membaca README, menanyakan fitur lengkap, atau menanyakan cara instalasi/penggunaan suatu repository GitHub.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "repo",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Nama pemilik dan repository GitHub (misal: 'langchain-ai/langgraph', 'langgraph', 'fastapi').")
                    )
                )
                .put("required", JSONArray().put("repo"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "open_browser_url",
            description = "Membuka halaman web, link URL tertentu (seperti repository GitHub, dokumentasi teknologi, link web), atau pencarian Google langsung di browser Android pengguna (Chrome / Samsung Internet). WAJIB panggil ini jika pengguna meminta membuka website, membuka link repo GitHub di browser, atau mencari halaman web di Google.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "url",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "URL lengkap halaman web (contoh: 'https://github.com/langchain-ai/langgraph') atau kata kunci pencarian yang ingin dibuka di Google.")
                    )
                )
                .put("required", JSONArray().put("url"))
        )

        putToolDeclaration(
            array = functionDeclarations,
            name = "execute_laptop_command",
            description = "Mengirim dan mengeksekusi perintah langsung di laptop Windows pengguna via Tailscale mesh. Aksi yang didukung: 'open_app' (membuka aplikasi laptop seperti vscode, vs code, notepad, chrome, spotify), 'lock' (mengunci layar laptop), 'volume' (mengatur volume: up, down, mute), 'media_play_pause' (play/pause lagu), 'sleep' (mode tidur), 'shutdown' (mematikan laptop total dengan konfirmasi dua arah), 'cancel_shutdown' (membatalkan hitung mundur shutdown). PENTING: Untuk aksi 'shutdown', WAJIB meminta konfirmasi lisan pengguna terlebih dahulu!",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "action",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Aksi yang ingin dijalankan: 'lock', 'volume', 'open_app', 'media_play_pause', 'sleep', 'shutdown', 'cancel_shutdown'.")
                        )
                        .put(
                            "target",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Target aplikasi atau parameter: 'vscode', 'notepad', 'chrome', 'spotify', 'mute', 'up', 'down'.")
                        )
                        .put(
                            "confirmed",
                            JSONObject()
                                .put("type", "BOOLEAN")
                                .put("description", "Wajib bernilai true untuk aksi 'shutdown' setelah pengguna memberikan konfirmasi lisan.")
                        )
                )
                .put("required", JSONArray().put("action"))
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "get_laptop_status",
            description = "Membaca status kesehatan laptop Windows pengguna (baterai, status daya, nama jendela aplikasi yang sedang aktif terbuka, dan apakah laptop online via Tailscale).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "detail_level",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Tingkat rincian status laptop (misal: full, summary).")
                    )
                )
        )
        putToolDeclaration(
            array = functionDeclarations,
            name = "sync_laptop_clipboard",
            description = "Membaca teks dari clipboard laptop Windows pengguna atau menyalin teks ke laptop pengguna.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "mode",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Mode clipboard: 'READ' (membaca clipboard laptop) atau 'WRITE' (menyalin teks ke laptop).")
                        )
                        .put(
                            "text",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Teks yang ingin disalin ke laptop (wajib jika mode WRITE).")
                        )
                )
                .put("required", JSONArray().put("mode"))
        )

        putToolDeclaration(
            array = functionDeclarations,
            name = "control_spotify_playback",
            description = "Mengontrol pemutaran musik Spotify secara langsung tanpa membuka atau menyentuh layar HP. Gunakan ini untuk memutar lagu, artis, atau playlist tertentu di Spotify, atau mengontrol pemutaran (pause, resume, next, previous).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "action",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Aksi: 'play_search' (memutar lagu/artis dari query), 'pause', 'resume', 'next', 'previous'.")
                        )
                        .put(
                            "query",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Judul lagu, nama artis, atau playlist yang ingin diputar (wajib jika action 'play_search').")
                        )
                        .put(
                            "device",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Perangkat target: 'phone' (default) atau 'laptop'.")
                        )
                )
                .put("required", JSONArray().put("action"))
        )

        putToolDeclaration(
            array = functionDeclarations,
            name = "execute_antigravity_rpc",
            description = "Mendelegasikan perintah koding, status repositori git, testing gradle, build APK, cek status proses latar belakang, atau audit arsitektur langsung ke partner AI Developer di laptop pengguna bernama Antigravity via M2M RPC over Tailscale. Aksi: 'GIT_STATUS' (cek branch dan file yang diubah), 'RUN_TESTS' (eksekusi gradlew testDebugUnitTest), 'WORKSPACE_HEALTH' (cek kesehatan laptop & repo), 'BUILD_RELEASE' (rakit APK release), 'VERIFY_ALL' (verifikasi verify.ps1 lengkap), 'READ_STATE' (baca status proyek), 'CHECK_TASK_STATUS' (cek proses koding yang sedang/baru selesai berjalan), 'ASK_AGENT' atau 'CODE_TASK' (instruksi koding atau audit kode spesifik).",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "action",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Aksi A2A: 'GIT_STATUS', 'RUN_TESTS', 'WORKSPACE_HEALTH', 'BUILD_RELEASE', 'VERIFY_ALL', 'READ_STATE', 'CHECK_TASK_STATUS', 'ASK_AGENT', 'CODE_TASK'.")
                        )
                        .put(
                            "target",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Target opsional (misal: nama test class untuk RUN_TESTS).")
                        )
                        .put(
                            "prompt",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Instruksi atau pertanyaan koding untuk Antigravity jika action 'ASK_AGENT' atau 'CODE_TASK'.")
                        )
                )
                .put("required", JSONArray().put("action"))
        )

        putToolDeclaration(
            array = functionDeclarations,
            name = "control_device_system",
            description = "API-FIRST (< 5 ms tanpa sentuh UI layar): Mengontrol perangkat keras & API cepat OS Android: senter ('flashlight_on', 'flashlight_off'), pasang timer ('set_timer' + duration_seconds + label), lihat daftar alarm ('show_alarms'), atur volume ('set_volume' + target='media'/'ring'/'alarm' + value='50%'/'up'/'down'/'mute'), mode dering ('set_ringer_mode' + value='getar'/'senyap'/'normal'), buka layar dialer ('open_dialer' + target), buat draf email ('compose_email' + target + subject + body), panel cepat ('open_panel' + target='wifi'/'internet'/'volume'/'nfc'), dan cek judul lagu yang sedang diputar ('now_playing'). Prioritaskan ini sebelum execute_android_goal.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "action",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Aksi OS API: 'flashlight_on', 'flashlight_off', 'set_timer', 'show_alarms', 'set_volume', 'set_ringer_mode', 'open_dialer', 'compose_email', 'open_panel', 'now_playing'.")
                        )
                        .put(
                            "target",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Target saluran/kontak/email/panel (misal: 'media', 'ring', 'wifi', 'internet', 'volume', 'Budi', 'budi@contoh.com').")
                        )
                        .put(
                            "value",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Nilai aksi: '50%', 'up', 'down', 'mute', 'getar', 'senyap', 'normal'.")
                        )
                        .put(
                            "duration_seconds",
                            JSONObject()
                                .put("type", "INTEGER")
                                .put("description", "Durasi timer dalam detik untuk aksi 'set_timer' (misal: 300 untuk 5 menit).")
                        )
                        .put(
                            "label",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Label opsional untuk timer (misal: 'Masak Mie').")
                        )
                        .put(
                            "subject",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Subjek email opsional untuk 'compose_email'.")
                        )
                        .put(
                            "body",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Isi pesan email opsional untuk 'compose_email'.")
                        )
                )
                .put("required", JSONArray().put("action"))
        )

        putToolDeclaration(
            array = functionDeclarations,
            name = "manage_notifications",
            description = "API-FIRST (< 15 ms via NotificationListenerService): Membaca notifikasi aktif aman ('read'), membalas pesan chat langsung di latar belakang via RemoteInput tanpa membuka aplikasi ('reply' dengan sender_or_app dan reply_message), atau menghapus notifikasi ('dismiss' dengan sender_or_app atau 'all'). Jika reply mengembalikan should_ui_fallback=true, barulah gunakan execute_android_goal sebagai fallback UI.",
            includeNonBlocking = includeNonBlocking,
            parameters = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "action",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Aksi notifikasi: 'read', 'reply', atau 'dismiss'.")
                        )
                        .put(
                            "sender_or_app",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Nama pengirim atau aplikasi (misal: 'Budi', 'WhatsApp', 'Telegram', atau 'all' untuk bersihkan semua).")
                        )
                        .put(
                            "reply_message",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Teks balasan untuk dikirim langsung via RemoteInput (wajib jika action 'reply').")
                        )
                )
                .put("required", JSONArray().put("action"))
        )
        return functionDeclarations
    }
}
