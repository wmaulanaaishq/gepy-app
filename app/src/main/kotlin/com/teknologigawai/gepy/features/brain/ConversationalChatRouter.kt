package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.features.launcher.AppIntentLauncher

/**
 * Penentu rute cepat deterministik (`0 ms`, `0` token) untuk membedakan antara:
 * 1. **Obrolan Percakapan / Pertanyaan Lanjutan Multi-Turn** (dijawab langsung oleh otak percakapan
 *    tanpa perlu memindai atau mengetuk tombol UI di layar), versus
 * 2. **Perintah Otomasi Perangkat / Aplikasi / Layar** (dieksekusi melalui loop ReAct atau Fast-Path Skill).
 */
object ConversationalChatRouter {

    private val DEVICE_OR_UI_ACTION_KEYWORDS = listOf(
        "buka ", "klik ", "ketuk ", "pencet ", "tekan ", "scroll ", "gulir ", "geser ", "swipe ",
        "kirim ", "kirimkan ", "chat ", "dm ", "telepon ", "telpon ", "telfon ", "hubungi ",
        "putar ", "play ", "setel ", "jeda", "pause", "lanjutkan lagu", "lagu berikutnya", "lagu sebelumnya",
        "lagu apa", "judul lagu", "musik apa", "muter lagu apa", "now playing",
        "baca layar", "lihat layar", "isi layar", "layar sekarang", "layar ini", "screenshot", "tangkapan layar",
        "alarm", "bangunin", "bangunkan", "timer ", "hitung mundur", "penghitung waktu", "pengingat ", "jadwalkan ", "catat jadwal",
        "matikan ", "matiin ", "nyalakan ", "nyalain ", "hidupkan ", "hidupin ", "kunci ", "tidurkan ", "sleep ", "shutdown",
        "volume ", "naikkan suara", "turunkan suara", "besarkan suara", "kecilkan suara", "setel suara",
        "mode getar", "mode senyap", "mode normal", "mode hening",
        "panel wifi", "panel wi-fi", "panel volume", "panel suara", "panel internet", "panel nfc",
        "balas notif", "balas pesan", "hapus notif", "bersihkan notif", "bersihin notif",
        "kirim email", "buat email", "tulis email", "bikin email",
        "baterai", "lokasi", "notifikasi", "clipboard", "salin ", "tempel ", "paste",
        "hapus ", "install ", "unduh ", "download ", "cari di ", "search di ", "buka di ",
        "git ", "gradle", "apk", "antigravity", "laptop", "komputer", "pc", "vscode", "vs code",
        "senter", "flashlight", "torch", "wifi", "wi-fi", "bluetooth", "hotspot", "kecerahan", "brightness"
    )

    private val CONVERSATIONAL_CUES = listOf(
        "halo", "hai", "hei", "hey", "pagi", "siang", "sore", "malam",
        "sayang", "cinta", "kangen", "rindu", "beb", "babe", "honey",
        "apa kabar", "lagi apa", "lagi ngapain", "sedang apa", "kamu siapa", "siapa kamu",
        "kamu bisa", "bisa apa", "bisa ngapain", "kemampuanmu", "kemampuan kamu",
        "fiturmu", "fitur kamu", "fungsimu", "fungsi kamu", "nama kamu", "siapa namamu", "kenalan",
        "kenapa", "mengapa", "gimana", "bagaimana", "jelasin", "jelaskan", "maksudnya", "artinya",
        "yang tadi", "tadi itu", "nomor 1", "nomor 2", "nomor 3", "nomor satu", "nomor dua", "nomor tiga",
        "pertama", "kedua", "ketiga", "terus", "lalu apa", "selanjutnya apa", "lebih detail", "contohnya",
        "menurutmu", "pendapatmu", "saranmu", "ceritain", "ceritakan", "ngobrol", "temenin", "temani",
        "capek", "lelah", "pusing", "stres", "sedih", "senang", "bosan", "gabut", "ngantuk", "lapar",
        "makasih", "terima kasih", "thanks", "oke", "okey", "ok", "sip", "siap", "mantap", "keren",
        "iya", "iyalah", "enggak", "nggak", "tidak", "bukan", "boleh", "yuk", "ayok", "gas"
    )

    private val CONTEXTUAL_FOLLOWUP_CUES = listOf(
        "tadi", "kamu bilang", "kamu nyuruh", "kamu sebut", "maksudmu", "maksudnya",
        "yang itu", "nomor 1", "nomor 2", "nomor 3", "nomor satu", "nomor dua", "nomor tiga",
        "lanjutin", "lanjutkan", "teruskan", "lebih detail", "kenapa kamu", "kenapa gitu",
        "gimana maksudnya", "terus gimana", "lalu gimana"
    )

    private val EXPLICIT_WEB_OVERRIDE_KEYWORDS = listOf(
        "google", "internet", "web", "berita", "cuaca", "kurs", "github", "huggingface", "http://", "https://"
    )

    /**
     * Mengembalikan `true` jika pengguna mengajukan pertanyaan lanjutan yang merujuk pada
     * percakapan sesi aktif sebelumnya (Chat maupun Live Call), sehingga tidak boleh dialihkan
     * ke pencarian berita/web umum.
     */
    fun isContextualFollowUpTurn(
        rawInput: String,
        hasPriorConversationTurns: Boolean
    ): Boolean {
        if (!hasPriorConversationTurns) return false
        val clean = rawInput.trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase()
        if (AppIntentLauncher.detectTargetAppPackageInGoal(clean) != null) return false
        if (DEVICE_OR_UI_ACTION_KEYWORDS.any { lower.contains(it) }) return false
        if (EXPLICIT_WEB_OVERRIDE_KEYWORDS.any { lower.contains(it) }) return false
        return CONTEXTUAL_FOLLOWUP_CUES.any { cue -> lower.contains(cue) }
    }

    /**
     * Mengembalikan `true` jika teks pengguna [rawInput] adalah obrolan percakapan alami atau
     * pertanyaan lanjutan yang sebaiknya dijawab langsung tanpa menjalankan loop klik UI.
     */
    fun isPureConversationalTurn(
        rawInput: String,
        hasMatchedDeviceSkill: Boolean = false
    ): Boolean {
        val clean = rawInput.trim()
        if (clean.isEmpty() || hasMatchedDeviceSkill) return false

        val lower = clean.lowercase()

        // 1. Jika menyebut target aplikasi Android spesifik (WA, IG, YouTube, TikTok, Shopee, Maps, dll.),
        // biarkan jalur automasi/launcher yang menangani.
        if (AppIntentLauncher.detectTargetAppPackageInGoal(clean) != null) {
            return false
        }

        // 2. Jika mengandung kata kerja aksi perangkat, layar, atau kontrol sistem, jangan dianggap obrolan murni.
        if (DEVICE_OR_UI_ACTION_KEYWORDS.any { keyword -> lower.contains(keyword) }) {
            return false
        }

        // 3. Jika cocok dengan pola sapaan, perasaan, refleksi, diskusi, atau pertanyaan lanjutan multi-turn.
        if (CONVERSATIONAL_CUES.any { cue ->
                lower == cue ||
                    lower.startsWith("$cue ") ||
                    lower.startsWith("$cue,") ||
                    lower.startsWith("$cue?") ||
                    lower.startsWith("$cue!") ||
                    lower.contains(" $cue ") ||
                    lower.endsWith(" $cue") ||
                    lower.endsWith(" $cue?") ||
                    lower.endsWith(" $cue!")
            }
        ) {
            return true
        }

        // 4. Kalimat tanya umum tanpa kata kerja kontrol UI/perangkat (misal "apa bedanya X dan Y?")
        if (clean.endsWith("?") && !lower.contains("layar") && !lower.contains("aplikasi")) {
            return true
        }

        return false
    }
}
