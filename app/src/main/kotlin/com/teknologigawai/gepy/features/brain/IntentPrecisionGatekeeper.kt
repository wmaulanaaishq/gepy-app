package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.speech.IndonesianSpeechNormalizer
import com.teknologigawai.gepy.core.system.PhoneContactItem
import java.util.Locale

/**
 * Hasil evaluasi presisi maksud perintah suara pengguna (**Intent Precision Evaluation**).
 */
sealed class IntentEvaluationResult {
    /**
     * Perintah valid, jelas, dan tanpa keraguan — langsung dieksekusi dengan kecepatan penuh (*Zero-HITL*).
     */
    data class Approved(
        val cleanGoal: String,
        val isDirectSovereignFastPass: Boolean = true
    ) : IntentEvaluationResult()

    /**
     * Perintah valid namun memiliki entitas kembar/ambigu (misal: ada 2 kontak bernama sama).
     * Wajib menampilkan pilihan 1-ketukan di HUD agar tidak salah sasaran.
     */
    data class RequiresDisambiguation(
        val questionPrompt: String,
        val candidates: List<String>,
        val originalGoal: String
    ) : IntentEvaluationResult()

    /**
     * Perintah berisiko tinggi atau destruktif (misal: menghapus percakapan/data secara permanen).
     * Wajib meminta persetujuan konfirmasi lisan/tombol sebelum dieksekusi.
     */
    data class RequiresConfirmation(
        val confirmationPrompt: String,
        val actionSummary: String,
        val originalGoal: String
    ) : IntentEvaluationResult()

    /**
     * Suara masukan merupakan desah napas, partikel hening, atau sapaan santai tanpa aksi yang dapat dijalankan.
     */
    data class IgnoredNoise(
        val reason: String,
        val friendlyReply: String? = null
    ) : IntentEvaluationResult()
}

/**
 * Penjaga Presisi Maksud & Anti-Salah Dengar (**Intent Precision & Anti-Mishearing Gatekeeper**)
 * untuk **Gepy v5.9.0**:
 *
 * Menjamin prinsip utama kedaulatan pemilik:
 * 1. **Kebebasan Penuh (Sovereign Fast-Pass)**: Untuk perintah harian yang jelas (buka aplikasi,
 *    navigasi layar, cari info, pasang alarm), Gepy mengeksekusi langsung tanpa cegatan birokratis.
 * 2. **Pencegahan Salah Dengar / Ambigu**: Jika perintah menyebut entitas kembar (misal nama kontak
 *    yang mirip lebih dari 1), Gepy proaktif menyajikan pilihan klarifikasi instan di Floating HUD.
 * 3. **Perlindungan Tindakan Destruktif**: Aksi permanen (hapus chat masif, format, bayar) dicegat
 *    dengan konfirmasi spesifik kalimat yang didengar.
 * 4. **Penolakan Noise Akustik**: Menolak kata desah/noise 1-huruf yang tidak disengaja.
 */
class IntentPrecisionGatekeeper(
    private val phoneContactsProvider: () -> List<PhoneContactItem> = { emptyList() },
    @Volatile var speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) {
    companion object {
        private const val TAG = "IntentPrecision"

        /** Kata sapaan tunggal yang bukan merupakan perintah aksi UI. */
        private val GREETING_WORDS: Set<String> = setOf(
            "halo", "halo gepy", "hai", "hai gepy", "pagi", "pagi gepy", "siang", "malam", "gepy"
        )

        /** Partikel noise / suara terpotong yang tidak bermakna. */
        private val NOISE_PARTICLES: Set<String> = setOf(
            "ah", "eh", "uh", "oh", "ha", "hm", "hmm", "tes", "test", "tes mic", "cek", "ok", "oke"
        )

        /** Pola pemicu pesan/panggilan ke seseorang. */
        private val MESSAGING_TARGET_REGEX = Regex(
            """^(?:kirim(?:\s+pesan)?(?:\s+ke|\s+wa\s+ke)?|wa\s+ke|chat\s+ke|hubungi|telepon|telpon)\s+([a-zA-Z0-9\s]+?)(?:\s+(?:isi\s+pesan|bilang|isi\s+chat|pesan|bahwa)\s+(.+))?$""",
            RegexOption.IGNORE_CASE
        )

        /** Pola tindakan destruktif permanen. */
        private val DESTRUCTIVE_PATTERNS: List<String> = listOf(
            "hapus semua chat",
            "hapus semua pesan",
            "hapus riwayat chat",
            "hapus riwayat percakapan",
            "hapus akun",
            "format data",
            "reset pabrik",
            "factory reset",
            "hapus kontak",
            "transfer uang",
            "bayar sekarang",
            "checkout sekarang"
        )
    }

    /**
     * Mengevaluasi kalimat perintah suara pengguna sebelum masuk ke siklus ReAct.
     *
     * @param rawCommand Kalimat hasil transkripsi STT / Live Audio
     * @param sovereignPersonalMode Apakah mode personal pemilik aktif (true = akses longgar)
     * @return [IntentEvaluationResult]
     */
    fun evaluateIntent(
        rawCommand: String,
        sovereignPersonalMode: Boolean = true
    ): IntentEvaluationResult {
        val clean = IndonesianSpeechNormalizer.normalize(rawCommand)
        if (clean.isEmpty()) {
            return IntentEvaluationResult.IgnoredNoise("Transkrip suara kosong.")
        }

        val lower = clean.lowercase(Locale.ROOT)

        // 1. Cek Partikel Noise Akustik
        if (lower in NOISE_PARTICLES) {
            GepyLog.d(TAG, "Suara diabaikan sebagai partikel noise akustik: '$clean'")
            return IntentEvaluationResult.IgnoredNoise("Suara terdeteksi sebagai noise mikrofon sesaat.")
        }

        // 2. Cek Sapaan Murni (Bukan Perintah Tugas)
        val speechCtx = speechContextProvider()
        if (lower in GREETING_WORDS) {
            return IntentEvaluationResult.IgnoredNoise(
                reason = "Sapaan pembuka santai.",
                friendlyReply = SovereignNaturalSpeech.gatekeeperGreeting(speechCtx)
            )
        }

        // 3. Cek Tindakan Berisiko Destruktif Permanen / Finansial
        for (pattern in DESTRUCTIVE_PATTERNS) {
            if (lower.contains(pattern)) {
                GepyLog.w(TAG, "Tindakan destruktif/finansial terdeteksi: '$clean'")
                return IntentEvaluationResult.RequiresConfirmation(
                    confirmationPrompt = SovereignNaturalSpeech.gatekeeperDestructiveConfirm(speechCtx, clean),
                    actionSummary = clean,
                    originalGoal = clean
                )
            }
        }

        // 4. Cek Ambiguitas Sasaran Kontak (WhatsApp / Telepon)
        val isEmailGoal = Regex("""\be-?mail\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)
        val messagingMatch = if (!isEmailGoal) MESSAGING_TARGET_REGEX.find(clean) else null
        if (messagingMatch != null) {
            val candidateTarget = messagingMatch.groupValues[1].trim()
            val candidateLower = candidateTarget.lowercase(Locale.ROOT)
            if (candidateTarget.isNotEmpty() && candidateLower != "wa" && candidateLower != "nomor") {
                val matchedContacts = findMatchingContacts(candidateTarget)
                if (matchedContacts.size > 1) {
                    GepyLog.i(TAG, "Ambiguitas kontak ditemukan (${matchedContacts.size} kecocokan untuk '$candidateTarget')")
                    val candidateNames = matchedContacts.map { "${it.displayName} (${it.phoneNumber})" }
                    return IntentEvaluationResult.RequiresDisambiguation(
                        questionPrompt = SovereignNaturalSpeech.gatekeeperDisambiguate(
                            ctx = speechCtx,
                            count = matchedContacts.size,
                            candidateTarget = candidateTarget
                        ),
                        candidates = candidateNames,
                        originalGoal = clean
                    )
                }
            }
        }

        // 5. Sovereign Personal Fast-Pass (Akses Longgar & Bebas untuk Pemilik Pribadi)
        GepyLog.i(TAG, "Perintah disetujui untuk dieksekusi langsung: '$clean'")
        return IntentEvaluationResult.Approved(
            cleanGoal = clean,
            isDirectSovereignFastPass = sovereignPersonalMode
        )
    }

    private fun findMatchingContacts(targetQuery: String): List<PhoneContactItem> {
        val queryLower = targetQuery.lowercase(Locale.ROOT).trim()
        val allContacts = phoneContactsProvider()
        if (allContacts.isEmpty() || queryLower.isEmpty()) return emptyList()

        // 1. Cari exact match
        val exactMatches = allContacts.filter { it.displayName.trim().lowercase(Locale.ROOT) == queryLower }
        if (exactMatches.isNotEmpty()) {
            return exactMatches
        }

        // 2. Cari contains / prefix match
        return allContacts.filter {
            val nameLower = it.displayName.lowercase(Locale.ROOT)
            nameLower.contains(queryLower) || queryLower.contains(nameLower)
        }.distinctBy { it.phoneNumber.replace(Regex("""\D"""), "") }
    }
}
