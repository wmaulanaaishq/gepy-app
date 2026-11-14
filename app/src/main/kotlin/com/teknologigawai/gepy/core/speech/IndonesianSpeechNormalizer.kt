package com.teknologigawai.gepy.core.speech

import java.util.Locale

/**
 * Utilitas normalisasi teks wicara Bahasa Indonesia (**Indonesian Speech Normalizer**)
 * untuk menangani fenomena *typo* fonetik, angka terucap kata, dan spasi imbuhan lisan
 * dari masukan mikrofon (Native Android `SpeechRecognizer` dan `Gemini 3.8 Live`).
 *
 * Mengatasi 3 masalah utama:
 * 1. **Konversi Angka & Durasi Lisan ke Digit**:
 *    - *"dua menit"* -> *"2 menit"*
 *    - *"semenit"* / *"satu menit"* -> *"1 menit"*
 *    - *"setengah jam"* -> *"30 menit"*
 *    - *"sejam"* -> *"1 jam"*
 *    - *"sepuluh detik"* -> *"10 detik"*
 *    - *"jam tujuh"* -> *"jam 7"*
 * 2. **Penyatuan Imbuhan Lisan yang Terpisah Spasi**:
 *    - *"inget in"* / *"ingat in"* -> *"ingetin"*
 *    - *"mati in"* -> *"matiin"*
 *    - *"nyala in"* -> *"nyalain"*
 *    - *"buka in"* -> *"bukain"*
 * 3. **Koreksi Homofon & Deviasi Fonetik Perintah Asisten**:
 *    - *"center"* (dalam konteks senter HP) -> *"senter"*
 *    - *"spotipai"* / *"spotifai"* -> *"spotify"*
 *    - *"yutub"* / *"yutup"* -> *"youtube"*
 *    - *"watsap"* / *"wasap"* -> *"whatsapp"*
 */
object IndonesianSpeechNormalizer {

    private val SPACED_AFFIXES = listOf(
        Regex("""\b(inget|ingat)\s+in\b""", RegexOption.IGNORE_CASE) to "ingetin",
        Regex("""\b(mati)\s+in\b""", RegexOption.IGNORE_CASE) to "matiin",
        Regex("""\b(nyala)\s+in\b""", RegexOption.IGNORE_CASE) to "nyalain",
        Regex("""\b(buka)\s+in\b""", RegexOption.IGNORE_CASE) to "bukain",
        Regex("""\b(bikin)\s+in\b""", RegexOption.IGNORE_CASE) to "bikinin",
        Regex("""\b(kirim)\s+in\b""", RegexOption.IGNORE_CASE) to "kirimin",
        Regex("""\b(hapus)\s+in\b""", RegexOption.IGNORE_CASE) to "hapusin",
        Regex("""\b(baca)\s+in\b""", RegexOption.IGNORE_CASE) to "bacain",
        Regex("""\b(tutup)\s+in\b""", RegexOption.IGNORE_CASE) to "tutupin",
        Regex("""\b(puter|putar)\s+in\b""", RegexOption.IGNORE_CASE) to "putarin",
        Regex("""\b(setel)\s+in\b""", RegexOption.IGNORE_CASE) to "setelin",
        Regex("""\b(kecil)\s+in\b""", RegexOption.IGNORE_CASE) to "kecilin",
        Regex("""\b(besar)\s+in\b""", RegexOption.IGNORE_CASE) to "besarin",
        Regex("""\b(tambah)\s+in\b""", RegexOption.IGNORE_CASE) to "tambahin",
        Regex("""\b(kurang)\s+in\b""", RegexOption.IGNORE_CASE) to "kurangin",
        Regex("""\b(naik)\s+in\b""", RegexOption.IGNORE_CASE) to "naikin",
        Regex("""\b(turun)\s+in\b""", RegexOption.IGNORE_CASE) to "turunin"
    )

    private val HOMOPHONE_FIXES = listOf(
        Regex("""\b(nyala(?:kan|in)?|hidup(?:kan|in)?|mati(?:kan|in)?)\s+(center|sentr|sen\s+ter)\b""", RegexOption.IGNORE_CASE) to "$1 senter",
        Regex("""\b(center|sentr|sen\s+ter)\s+(nyala|mati|hidup)\b""", RegexOption.IGNORE_CASE) to "senter $2",
        Regex("""\b(spotipai|spotifai|spotifay|spotfy)\b""", RegexOption.IGNORE_CASE) to "spotify",
        Regex("""\b(yutub|yutup|yu\s*tub)\b""", RegexOption.IGNORE_CASE) to "youtube",
        Regex("""\b(watsap|wasap|watshap|whatapp|wa\s+app)\b""", RegexOption.IGNORE_CASE) to "whatsapp",
        Regex("""\b(pesen)\b""", RegexOption.IGNORE_CASE) to "pesan"
    )

    private val COMPOUND_NUMBER_WORDS: List<Pair<Regex, String>> = listOf(
        Regex("""\bsetengah\s+jam\b""", RegexOption.IGNORE_CASE) to "30 menit",
        Regex("""\bseperempat\s+jam\b""", RegexOption.IGNORE_CASE) to "15 menit",
        Regex("""\bsemenit\b""", RegexOption.IGNORE_CASE) to "1 menit",
        Regex("""\bsejam\b""", RegexOption.IGNORE_CASE) to "1 jam",
        Regex("""\bdua\s+puluh\s+lima\b""", RegexOption.IGNORE_CASE) to "25",
        Regex("""\btiga\s+puluh\s+lima\b""", RegexOption.IGNORE_CASE) to "35",
        Regex("""\bempat\s+puluh\s+lima\b""", RegexOption.IGNORE_CASE) to "45",
        Regex("""\blima\s+puluh\s+lima\b""", RegexOption.IGNORE_CASE) to "55",
        Regex("""\bdua\s+puluh\b""", RegexOption.IGNORE_CASE) to "20",
        Regex("""\btiga\s+puluh\b""", RegexOption.IGNORE_CASE) to "30",
        Regex("""\bempat\s+puluh\b""", RegexOption.IGNORE_CASE) to "40",
        Regex("""\blima\s+puluh\b""", RegexOption.IGNORE_CASE) to "50",
        Regex("""\benam\s+puluh\b""", RegexOption.IGNORE_CASE) to "60",
        Regex("""\btujuh\s+puluh\b""", RegexOption.IGNORE_CASE) to "70",
        Regex("""\bdelapan\s+puluh\b""", RegexOption.IGNORE_CASE) to "80",
        Regex("""\bsembilan\s+puluh\b""", RegexOption.IGNORE_CASE) to "90",
        Regex("""\bdua\s+belas\b""", RegexOption.IGNORE_CASE) to "12",
        Regex("""\btiga\s+belas\b""", RegexOption.IGNORE_CASE) to "13",
        Regex("""\bempat\s+belas\b""", RegexOption.IGNORE_CASE) to "14",
        Regex("""\blima\s+belas\b""", RegexOption.IGNORE_CASE) to "15",
        Regex("""\benam\s+belas\b""", RegexOption.IGNORE_CASE) to "16",
        Regex("""\btujuh\s+belas\b""", RegexOption.IGNORE_CASE) to "17",
        Regex("""\bdelapan\s+belas\b""", RegexOption.IGNORE_CASE) to "18",
        Regex("""\bsembilan\s+belas\b""", RegexOption.IGNORE_CASE) to "19",
        Regex("""\bsebelas\b""", RegexOption.IGNORE_CASE) to "11",
        Regex("""\bsepuluh\b""", RegexOption.IGNORE_CASE) to "10",
        Regex("""\bseratus\b""", RegexOption.IGNORE_CASE) to "100"
    )

    private val BASIC_NUMBER_WORDS = mapOf(
        "nol" to "0",
        "satu" to "1",
        "dua" to "2",
        "tiga" to "3",
        "empat" to "4",
        "lima" to "5",
        "enam" to "6",
        "tujuh" to "7",
        "delapan" to "8",
        "sembilan" to "9"
    )

    /**
     * Pola angka satuan sebelum satuan waktu (menit, jam, detik)
     * atau setelah preposisi waktu (jam, pukul, lewat).
     */
    private val TIME_UNITS_OR_CUES = listOf(
        "menit", "jam", "detik", "hari", "minggu", "bulan", "tahun", "persen", "%"
    )

    /**
     * Menormalkan teks ucapan wicara pengguna menjadi bentuk kanonikal yang siap
     * dievaluasi oleh sistem fast-path, matcher regex, dan model LLM.
     */
    fun normalize(rawText: String): String {
        var text = rawText.trim().replace(Regex("""\s+"""), " ")
        if (text.isEmpty()) return ""

        // 1. Satukan imbuhan lisan yang terpisah spasi
        for ((pattern, replacement) in SPACED_AFFIXES) {
            text = pattern.replace(text, replacement)
        }

        // 2. Koreksi homofon perintah asisten
        for ((pattern, replacement) in HOMOPHONE_FIXES) {
            text = pattern.replace(text, replacement)
        }

        // 3. Konversi angka majemuk wicara (contoh: "dua puluh lima" -> "25", "setengah jam" -> "30 menit")
        for ((pattern, replacement) in COMPOUND_NUMBER_WORDS) {
            text = pattern.replace(text, replacement)
        }

        // 4. Konversi angka dasar ketika diikuti unit waktu/persen atau didahului preposisi waktu
        for ((word, digit) in BASIC_NUMBER_WORDS) {
            // Contoh: "dua menit" -> "2 menit", "lima detik" -> "5 detik"
            for (unit in TIME_UNITS_OR_CUES) {
                val beforeUnitRegex = Regex("""\b$word\s+$unit\b""", RegexOption.IGNORE_CASE)
                text = beforeUnitRegex.replace(text, "$digit $unit")
            }
            // Contoh: "jam tujuh" -> "jam 7", "pukul delapan" -> "pukul 8"
            val afterClockCueRegex = Regex("""\b(jam|pukul|lewat)\s+$word\b""", RegexOption.IGNORE_CASE)
            text = afterClockCueRegex.replace(text, "$1 $digit")

            // Contoh: "selama dua" -> "selama 2", "dalam tiga" -> "dalam 3"
            val afterPrepositionRegex = Regex("""\b(dalam|selama|kurang|tambah|sekitar|kira-kira)\s+$word\b""", RegexOption.IGNORE_CASE)
            text = afterPrepositionRegex.replace(text, "$1 $digit")
        }

        return text
    }
}
