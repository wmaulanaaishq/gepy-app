package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.logging.GepyLog
import java.util.Locale

/**
 * **CrossAppScratchpadInterpolator** — Resolusi Cerdas & Interpolasi Variabel Scratchpad
 * untuk **Gepy v5.9.5**.
 *
 * Mengeliminasi "Amnesia Lintas Aplikasi" saat berpindah aplikasi. Ketika LLM mengetikkan frasa
 * referensial ambigu seperti *"alamatnya"*, *"linknya"*, *"infonya"*, atau placeholder `{key}`,
 * interpolator ini secara otomatis mengikat variabel konkret dari `scratchpad` atau `Clipboard`
 * ke kolom input target aplikasi tujuan (misalnya chat WhatsApp atau DM Telegram).
 */
object CrossAppScratchpadInterpolator {

    private const val TAG = "ScratchpadInterpolator"

    // Pola pemetaan kata ganti referensial berakhiran -nya di dalam kalimat
    private val SENTENCE_PRONOUN_TO_KEYS = listOf(
        Regex("""\b(alamatnya|lokasinya)\b""", RegexOption.IGNORE_CASE) to listOf("alamat", "lokasi"),
        Regex("""\b(linknya|tautannya|urlnya)\b""", RegexOption.IGNORE_CASE) to listOf("link", "tautan", "url"),
        Regex("""\b(harganya|tarifnya|biayanya)\b""", RegexOption.IGNORE_CASE) to listOf("harga", "tarif", "biaya"),
        Regex("""\b(materinya|catatannya|isinya|rangkumannya)\b""", RegexOption.IGNORE_CASE) to listOf("materi", "catatan", "isi"),
        Regex("""\b(infonya|informasinya|hasilnya|datanya|keterangannya)\b""", RegexOption.IGNORE_CASE) to listOf("info", "clipboard_last", "alamat", "link", "harga", "materi")
    )

    private val PURE_KEY_MAP = mapOf(
        "alamat" to "alamat",
        "alamatnya" to "alamat",
        "lokasi" to "lokasi",
        "lokasinya" to "lokasi",
        "link" to "link",
        "linknya" to "link",
        "tautan" to "tautan",
        "tautannya" to "tautan",
        "url" to "url",
        "urlnya" to "url",
        "harga" to "harga",
        "harganya" to "harga",
        "tarif" to "tarif",
        "tarifnya" to "tarif",
        "biaya" to "biaya",
        "biayanya" to "biaya",
        "materi" to "materi",
        "materinya" to "materi",
        "catatan" to "catatan",
        "catatannya" to "catatan",
        "info" to "info",
        "infonya" to "info",
        "data" to "info",
        "datanya" to "info"
    )

    private val PLACEHOLDER_REGEX = Regex("""\{([a-zA-Z0-9_-]+)\}""")

    /**
     * Melakukan resolusi teks input dengan menyuntikkan data riil dari [scratchpad] atau [clipboardFallback].
     *
     * @param inputText Teks yang hendak disuntikkan (bisa null, blank, mengandung kata ganti, atau placeholder)
     * @param scratchpad Peta data persisten memori scratchpad
     * @param clipboardFallback Teks cadangan dari papan klip sistem Android
     * @return Teks terinterpolasi siap suntik ke elemen target
     */
    fun interpolateText(
        inputText: String?,
        scratchpad: Map<String, String>,
        clipboardFallback: String = ""
    ): String {
        val raw = inputText?.trim().orEmpty()
        val clip = clipboardFallback.trim()

        // 1. Kasus teks kosong sama sekali: ambil nilai variabel terbaik atau clipboard
        if (raw.isEmpty()) {
            val bestFallback = getBestAvailableValue(scratchpad, clip)
            if (bestFallback.isNotEmpty()) {
                GepyLog.i(TAG, "Input kosong: otomatis mengisi dengan data scratchpad/clipboard: '${bestFallback.take(30)}...'")
                return bestFallback
            }
            return ""
        }

        // 2. Interpolasi placeholder eksplisit berformat {variable_name}
        if (PLACEHOLDER_REGEX.containsMatchIn(raw)) {
            val interpolated = PLACEHOLDER_REGEX.replace(raw) { matchResult ->
                val key = matchResult.groupValues[1]
                val value = scratchpad[key]
                    ?: scratchpad[key.lowercase(Locale.ROOT)]
                    ?: if (key.equals("clipboard_last", ignoreCase = true)) clip else null
                value ?: matchResult.value
            }
            GepyLog.i(TAG, "Interpolasi template placeholder: '${interpolated.take(30)}...'")
            return interpolated
        }

        // 3. Jika teks murni hanya berupa satu kata ganti referensial (misal: "alamatnya" atau "linknya")
        val purePronounKey = findMatchingScratchpadKey(raw)
        if (purePronounKey != null) {
            val resolvedVal = scratchpad[purePronounKey]
                ?: scratchpad["clipboard_last"]
                ?: clip.takeIf { it.isNotEmpty() }
            if (!resolvedVal.isNullOrBlank()) {
                GepyLog.i(TAG, "Interpolasi kata ganti murni '$raw' -> '$purePronounKey': '${resolvedVal.take(30)}...'")
                return resolvedVal
            }
        }

        // 4. Interpolasi kata ganti di dalam kalimat (misal: "Halo ini alamatnya ya" -> "Halo ini Jl. Sudirman No 1 ya")
        var result = raw
        for ((regex, candidateKeys) in SENTENCE_PRONOUN_TO_KEYS) {
            if (regex.containsMatchIn(result)) {
                val value = candidateKeys.firstNotNullOfOrNull { scratchpad[it] }
                    ?: scratchpad["clipboard_last"]
                    ?: clip.takeIf { it.isNotEmpty() }

                if (!value.isNullOrBlank()) {
                    result = regex.replace(result) {
                        value
                    }
                    GepyLog.i(TAG, "Interpolasi frasa referensial berhasil disuntikkan: '${value.take(30)}...'")
                    break
                }
            }
        }

        return result
    }

    /**
     * Mencari kunci scratchpad yang cocok jika token merupakan kata ganti tunggal.
     */
    fun findMatchingScratchpadKey(text: String): String? {
        val clean = text.trim().lowercase(Locale.ROOT)
        return PURE_KEY_MAP[clean]
    }

    private fun getBestAvailableValue(scratchpad: Map<String, String>, clip: String): String {
        val priorityKeys = listOf("alamat", "lokasi", "link", "url", "harga", "materi", "catatan", "info", "clipboard_last")
        for (k in priorityKeys) {
            val v = scratchpad[k]?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        val generic = scratchpad.values.firstOrNull { it.isNotBlank() }
        if (!generic.isNullOrBlank()) return generic
        return clip
    }
}
