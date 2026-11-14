package com.teknologigawai.gepy.core.security

/**
 * Perisai keamanan *Zero-Trust Indirect Prompt Injection* (`PromptInjectionSanitizer`).
 *
 * Melindungi otak penalaran LLM Gepy dari serangan *Indirect Prompt Injection*
 * yang disisipkan melalui teks elemen UI pihak ketiga (misal isi pesan chat masuk,
 * judul halaman web, atau notifikasi) yang mencoba membajak instruksi agen.
 */
object PromptInjectionSanitizer {

    /**
     * Karakter tak terlihat (*Zero-Width*) dan pembalik arah teks (*Unicode Bidi Override*)
     * yang sering disalahgunakan untuk menyembunyikan instruksi jahat di layar.
     */
    private val invisibleAndBidiRegex = Regex(
        "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]"
    )

    /**
     * Pola kalimat pembajakan instruksi (*Prompt Injection / Jailbreak*) dalam Bahasa Indonesia
     * maupun Bahasa Inggris, atau upaya memalsukan struktur JSON keluaran ReAct.
     */
    private val injectionPatterns: List<Regex> = listOf(
        Regex("""\b(abaikan|lupakan|ganti)\s+(semua\s+)?(instruksi|perintah|aturan|tujuan)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bignore\s+(all\s+)?(previous|prior|above|system)\s+(instructions?|prompts?|rules?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(system\s+override|system\s+prompt|developer\s+mode|jailbreak)\b""", RegexOption.IGNORE_CASE),
        Regex("""["']action["']\s*:\s*["'](CONFIRM_AND_CLICK|CLICK|SET_TEXT|LAUNCH_APP|DONE)["']""", RegexOption.IGNORE_CASE),
        Regex("""\bCONFIRM_AND_CLICK\b"""),
        Regex("""\bsave_to_scratchpad\b""", RegexOption.IGNORE_CASE)
    )

    /** Menghapus karakter zero-width dan Unicode Bidi Override dari [raw]. */
    fun stripInvisibleAndBidiChars(raw: String): String {
        if (raw.isEmpty()) return ""
        return raw.replace(invisibleAndBidiRegex, "")
    }

    /** Memeriksa apakah [raw] mengandung indikasi *Indirect Prompt Injection*. */
    fun containsPromptInjectionPattern(raw: String): Boolean {
        val cleaned = stripInvisibleAndBidiChars(raw).trim()
        if (cleaned.isEmpty()) return false
        return injectionPatterns.any { it.containsMatchIn(cleaned) }
    }

    /**
     * Menyanitasi teks elemen UI [raw] sebelum dimasukkan ke dalam konteks JSON LLM:
     * 1. Menghapus karakter *zero-width* & *bidi-override*.
     * 2. Membatasi panjang maksimum ke [maxLength] karakter agar satu node tidak membanjiri token.
     * 3. Jika terdeteksi pola *Prompt Injection*, menetralkan tanda kurung kurawal JSON
     *    dan membungkusnya dengan penanda `[UNTRUSTED_UI_TEXT]` agar LLM memperlakukannya
     *    murni sebagai data pasif.
     */
    fun sanitizeUiText(raw: String, maxLength: Int = 260): String {
        var clean = stripInvisibleAndBidiChars(raw).trim()
        if (clean.isEmpty()) return ""

        if (clean.length > maxLength) {
            clean = clean.take(maxLength).trimEnd() + "…"
        }

        if (containsPromptInjectionPattern(clean)) {
            val neutralized = clean
                .replace('{', '(')
                .replace('}', ')')
                .replace('"', '\'')
            return "[UNTRUSTED_UI_TEXT] $neutralized"
        }

        return clean
    }
}
