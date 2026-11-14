package com.teknologigawai.gepy.core.memory

/**
 * Filter Semantik Kognitif (Semantic Memory Filter) untuk mencegah
 * penyerapan sampah ujaran kasual, idiom situasional, dan ocehan lisan sementara
 * ke dalam basis data memori personal Gepy (Room v4).
 */
object SemanticMemoryFilter {

    /**
     * Idiom emosional, keluhan kasual, atau kata sifat situasional
     * yang sering diawali kata "suka ..." dalam percakapan lisan santai,
     * yang BUKAN merupakan preferensi kuliner/hobi abadi pengguna.
     */
    private val TRANSIENT_EMOTION_OR_IDIOM_WORDS = setOf(
        "bingung", "pusing", "heran", "lupa", "kaget", "ragu", "capek", "lelah",
        "macet", "ngelag", "lemot", "error", "mikir", "malas", "mager", "panik",
        "takut", "bimbang", "overthinking", "kepikiran", "gemeteran", "mules",
        "batuk", "flu", "kesal", "jengkel", "marah", "baper", "bosan", "jenuh",
        "gagal", "rusak", "mati", "hang", "crash", "pending", "telat", "hilang"
    )

    /**
     * Frasa yang merujuk pada navigasi layar ponsel atau aplikasi saat sesi berlangsung,
     * bukan minat riset atau profil teknologi pengguna.
     */
    private val SCREEN_NAVIGATION_MARKERS = listOf(
        "ke layar", "pada layar", "ke tombol", "pada tombol", "ke menu",
        "ke aplikasi", "pada aplikasi", "ke hp", "ke ponsel", "kesini", "ke sini",
        "kesitu", "ke situ", "dulu ya", "dulu deh", "liat dulu", "lihat dulu",
        "buka dulu", "coba deh", "klik", "tekan", "geser", "scroll", "swipe",
        "benerin bug", "beresin bug", "nyari tombol", "buka shopee", "buka wa",
        "buka ig", "buka youtube"
    )

    /**
     * Lokasi umum, fasilitas publik, atau kondisi tempat situasional
     * yang bukan merupakan alamat domisili atau tempat kerja resmi pengguna.
     */
    private val IMPERSONAL_FACILITY_OR_STATE_MARKERS = listOf(
        "kantor pos", "kantor polisi", "kantor lurah", "kantor camat",
        "kantor cabang", "kantor dinas", "rumah sakit", "rumah makan",
        "rumah duka", "rumah ibadah", "rumah kontrakan", "sebelah warung",
        "sebelah toko", "di jalan", "di perempatan", "di lampu merah",
        "di sana", "di sini", "di atas", "di bawah", "di luar", "di dalam",
        "mati lampu", "padam", "gelap", "ramai", "sepi", "berantakan", "kotor"
    )

    /**
     * Kata-kata sampah (stopwords / filler) yang tidak memiliki nilai fakta mandiri.
     */
    private val INVALID_OBJECT_STANDALONE_WORDS = setOf(
        "itu", "ini", "anu", "sesuatu", "begitu", "begini", "saja", "aja", "doang",
        "dulu", "lagi", "terus", "lalu", "juga", "banget", "sekali", "amat",
        "dong", "deh", "sih", "kan", "kok", "ya", "yah", "nih", "tuh",
        "keras", "cepat", "lambat", "banyak", "sedikit", "cukup", "kurang"
    )

    private val LEADING_VOCATIVE_OR_POLITE_REGEX = Regex(
        """^(?:(?:hey|hei|hai|halo|oke|ok|ya|eh)\s+)*(?:gepy|gep)?[\s,]*(?:tolong|coba|bantu|mohon|silakan)?[\s,]*""",
        RegexOption.IGNORE_CASE
    )

    private val ACTION_COMMAND_PREFIX_REGEX = Regex(
        """^(?:cek|periksa|pantau|lihat|liat|tampilkan|tunjukkan|tunjukin|buka|tutup|kunci|lock|unlock|matikan|nyalakan|hidupkan|tidurkan|sleep|restart|reboot|mulai\s+ulang|cari|carikan|browsing|kirim|kirimkan|balas|hapus|buang|bersihkan|putar|mainkan|play|pause|jeda|stop|hentikan|lanjutkan|resume|lewati|skip|setel|stel|atur|ubah|ganti|pasang|aktifkan|nonaktifkan|mute|bisukan|unmute|keraskan|kecilkan|besarkan|turunkan|naikkan|bacakan|rangkum|ringkas|rekam|screenshot|ambil\s+(?:foto|gambar|screenshot)|panggil|telepon|telpon|hubungi|jalankan|eksekusi|rakit|build)\b""",
        RegexOption.IGNORE_CASE
    )

    private val INTERROGATIVE_PREFIX_REGEX = Regex(
        """^(?:apakah|apa\s+(?:kabar|itu|arti|maksud|status|kondisi|nama|jadwal|alamat|nomor|laptop|hp|bisa|boleh|perlu|ada)|bagaimana|gimana|siapa|kapan|dimana|di\s+mana|kemana|ke\s+mana|darimana|dari\s+mana|berapa|mengapa|kenapa|bisakah|bolehkah|maukah|sudahkah|udahkah|adakah|jelaskan|sebutkan)\b""",
        RegexOption.IGNORE_CASE
    )

    private val DEVICE_OR_SYSTEM_COMMAND_MARKERS = listOf(
        "kondisi laptop", "status laptop", "baterai laptop", "volume laptop",
        "kunci laptop", "matikan laptop", "tidurkan laptop", "restart laptop",
        "di laptop", "ke laptop", "status git", "status tugas"
    )

    /**
     * Mendeteksi kalimat perintah asisten (mis. "cek kondisi laptop", "kunci laptop")
     * atau kalimat tanya interogatif agar tidak pernah disimpan sebagai fakta profil.
     */
    fun isActionCommandOrQuestion(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return false
        if (clean.endsWith("?")) return true
        if (DEVICE_OR_SYSTEM_COMMAND_MARKERS.any { clean.contains(it) }) return true

        val normalized = LEADING_VOCATIVE_OR_POLITE_REGEX.replace(clean, "").trim()
        if (normalized.isEmpty()) return false
        return ACTION_COMMAND_PREFIX_REGEX.containsMatchIn(normalized) ||
            INTERROGATIVE_PREFIX_REGEX.containsMatchIn(normalized) ||
            normalized.startsWith("baca ") && Regex("""^baca\s+(?:notifikasi|pesan|chat|wa|whatsapp|email|layar|berita|artikel|ringkasan)\b""").containsMatchIn(normalized)
    }

    /**
     * Memeriksa apakah sebuah kandidat fakta atau objek percakapan layak
     * disimpan sebagai memori personal abadi atau harus ditolak sebagai derau situasional.
     */
    fun isMeaningfulPersonalFact(
        category: String,
        factText: String,
        objectValue: String = ""
    ): Boolean {
        val cleanFact = factText.trim()
        val cleanObj = objectValue.trim()
        if (cleanFact.length < 5) return false

        val lowerFact = cleanFact.lowercase()
        val lowerObj = cleanObj.lowercase()

        // 0. Tolak kalimat perintah/aksi atau pertanyaan interogatif (mis. "cek kondisi laptop")
        if (isActionCommandOrQuestion(cleanFact) || (cleanObj.isNotEmpty() && isActionCommandOrQuestion(cleanObj))) {
            return false
        }

        // 1. Tolak jika mengandung idiom emosional/keluhan sesaat (suka bingung, suka pusing, dll)
        if (category == "preference" || lowerFact.contains("suka ") || lowerFact.contains("gemar ")) {
            val wordsInObj = lowerObj.split(Regex("""\s+"""))
            val wordsInFact = lowerFact.split(Regex("""\s+"""))
            val hasTransientWord = (wordsInObj + wordsInFact).any { it in TRANSIENT_EMOTION_OR_IDIOM_WORDS }
            if (hasTransientWord) {
                return false
            }
        }

        // 2. Tolak jika merujuk pada navigasi atau elemen layar ponsel saat ini
        if (SCREEN_NAVIGATION_MARKERS.any { lowerFact.contains(it) || lowerObj.contains(it) }) {
            return false
        }

        // 3. Tolak jika berupa fasilitas umum atau kondisi fisik sesaat (mati lampu, rumah makan)
        if (category in setOf("work", "location") || lowerFact.contains("kantor") || lowerFact.contains("rumah")) {
            if (IMPERSONAL_FACILITY_OR_STATE_MARKERS.any { lowerFact.contains(it) || lowerObj.contains(it) }) {
                return false
            }
        }

        // 4. Tolak jika objek hanya berupa kata benda kosong atau kata partikel filler
        if (cleanObj.isNotEmpty()) {
            if (cleanObj.length < 2 || cleanObj.lowercase() in INVALID_OBJECT_STANDALONE_WORDS) {
                return false
            }
            if (cleanObj.matches(Regex("""^(?:ini|itu|anu|sini|situ|sana|aja|doang|deh|sih)$""", RegexOption.IGNORE_CASE))) {
                return false
            }
        }

        // 5. Tolak keluhan rutinitas atau kebiasaan seputar aplikasi/ponsel
        if (category == "routine" || lowerFact.contains("kebiasaan") || lowerFact.contains("rutinitas")) {
            if (lowerFact.contains("aplikasi") || lowerFact.contains("ponsel") || lowerFact.contains("hp") ||
                lowerFact.contains("error") || lowerFact.contains("lemot") || lowerFact.contains("macet")
            ) {
                return false
            }
        }

        return true
    }

    /**
     * Memeriksa apakah ujaran transkrip murni merupakan ocehan/idiom yang tidak memiliki fakta personal.
     */
    fun isTransientIdiomOrNoise(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.length < 4) return true

        if (isActionCommandOrQuestion(lower)) return true
        if (SCREEN_NAVIGATION_MARKERS.any { lower.contains(it) }) return true
        if (lower.startsWith("aku suka ") || lower.startsWith("saya suka ") ||
            lower.contains("suka bingung") || lower.contains("suka pusing") ||
            lower.contains("suka heran") || lower.contains("suka lupa") ||
            lower.contains("suka capek") || lower.contains("suka lelah")
        ) {
            val words = lower.split(Regex("""\s+"""))
            if (words.any { it in TRANSIENT_EMOTION_OR_IDIOM_WORDS }) {
                return true
            }
        }
        return false
    }
}
