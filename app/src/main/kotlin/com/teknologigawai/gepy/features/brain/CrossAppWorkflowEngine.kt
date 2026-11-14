package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.logging.GepyLog
import java.util.Locale

/** Jenis intensi aksi di setiap tahap rantai lintas aplikasi. */
enum class StageIntent {
    /** Mencari dan mengekstrak informasi (alamat, link, harga, materi) dari aplikasi sumber. */
    SEARCH_AND_EXTRACT,

    /** Membuka atau bernavigasi ke bagian tertentu di aplikasi. */
    NAVIGATE_APP,

    /** Mengetikkan data yang telah disalin dan mengirimkan pesan / membagikan konten. */
    COMPOSE_AND_SEND,

    /** Aksi umum non-spesifik. */
    GENERIC_ACTION
}

/** Representasi satu tahapan terstruktur di dalam alur kerja majemuk lintas aplikasi. */
data class CrossAppStage(
    val stageIndex: Int,
    val title: String,
    val rawClause: String,
    val targetPackageHint: String?,
    val intent: StageIntent,
    val expectedOutputVariable: String? = null,
    var isCompleted: Boolean = false
) {
    fun toChecklistString(): String {
        val appHint = if (!targetPackageHint.isNullOrBlank()) " ($targetPackageHint)" else ""
        val statusLabel = if (isCompleted) "[SELESAI] " else "[BERJALAN] "
        return "$stageIndex. $statusLabel$title$appHint"
    }
}

/**
 * Rencana kerja alur rantai lintas aplikasi (**Cross-App Workflow Plan**).
 */
data class CrossAppWorkflowPlan(
    val originalGoal: String,
    val stages: List<CrossAppStage>,
    var currentStageIndex: Int = 0
) {
    val isMultiStage: Boolean get() = stages.size > 1
    val activeStage: CrossAppStage? get() = stages.getOrNull(currentStageIndex)
    val allStagesCompleted: Boolean get() = stages.isNotEmpty() && stages.all { it.isCompleted }

    /** Menandai tahap aktif selesai dan memajukan penunjuk ke tahap berikutnya. */
    fun markCurrentStageCompleted(): Boolean {
        val current = activeStage ?: return false
        current.isCompleted = true
        GepyLog.i("CrossAppWorkflow", "Tahap ${current.stageIndex} ('${current.title}') ditandai SELESAI.")
        if (currentStageIndex + 1 < stages.size) {
            currentStageIndex++
            GepyLog.i("CrossAppWorkflow", "Maju ke Tahap ${currentStageIndex + 1}: '${activeStage?.title}'")
            return true
        }
        return false
    }

    /** Menghasilkan panduan tahap aktif untuk diumpankan ke prompt LLM. */
    fun buildActiveStageGuidance(scratchpad: Map<String, String>): String {
        val current = activeStage ?: return "Semua tahapan telah selesai."
        val total = stages.size
        return buildString {
            append("TAHAP AKTIF [${current.stageIndex}/$total]: ${current.title}. ")
            if (!current.targetPackageHint.isNullOrBlank()) {
                append("Aplikasi Target: ${current.targetPackageHint}. ")
            }
            if (current.stageIndex < total) {
                append("PERINGATAN: DILARANG mengeluarkan action DONE pada tahap ini! ")
                if (current.intent == StageIntent.SEARCH_AND_EXTRACT) {
                    append("Temukan informasi target lalu simpan ke scratchpad (save_to_scratchpad) atau gunakan CLIPBOARD_COPY. ")
                }
            } else {
                append("Ini adalah tahap akhir. Pastikan pesan/konten terkirim atau terselesaikan sebelum DONE. ")
                val varKey = stages.firstOrNull()?.expectedOutputVariable
                if (!varKey.isNullOrBlank() && scratchpad.containsKey(varKey)) {
                    append("Gunakan data yang tersimpan dari Tahap 1: [$varKey = ${scratchpad[varKey]}]. ")
                } else if (scratchpad.containsKey("clipboard_last")) {
                    append("Gunakan data tersimpan dari clipboard: [clipboard_last = ${scratchpad["clipboard_last"]}]. ")
                }
            }
        }.trim()
    }
}

/**
 * Mesin Pengurai & Pengontrol Alur Majemuk Lintas Aplikasi (**Cross-App Workflow Engine**)
 * untuk **Gepy v5.9.5**:
 *
 * Mendekomposisi instruksi kompleks multi-langkah (misal: "Cari alamat di Maps lalu kirim ke WA Budi")
 * menjadi tahapan deterministik dengan penanda aplikasi, variabel data, dan pencegahan DONE prematur.
 */
object CrossAppWorkflowEngine {

    private const val TAG = "CrossAppWorkflow"

    private val STAGE_SPLITTER = Regex(
        """\s+(?:lalu|kemudian|terus|habis\s+itu|setelah\s+itu|dan\s+kirim(?:\s+ke)?|dan\s+posting(?:\s+ke)?|serta)\s+""",
        RegexOption.IGNORE_CASE
    )

    private val APP_SIGNATURES = mapOf(
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "peta" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "google" to "com.android.chrome",
        "shopee" to "com.shopee.id",
        "tokopedia" to "com.tokopedia.tkpd",
        "keep" to "com.google.android.keep",
        "catatan" to "com.google.android.keep",
        "notes" to "com.google.android.keep",
        "whatsapp" to "com.whatsapp",
        "wa" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "ig" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "youtube" to "com.google.android.youtube",
        "spotify" to "com.spotify.music"
    )

    private val EXTRACT_VARIABLE_PATTERNS = listOf(
        Regex("""\b(alamat|lokasi)\b""", RegexOption.IGNORE_CASE) to "alamat",
        Regex("""\b(link|tautan|url)\b""", RegexOption.IGNORE_CASE) to "link",
        Regex("""\b(harga|biaya|tarif)\b""", RegexOption.IGNORE_CASE) to "harga",
        Regex("""\b(catatan|materi|rangkuman|isi)\b""", RegexOption.IGNORE_CASE) to "materi",
        Regex("""\b(foto|gambar|media)\b""", RegexOption.IGNORE_CASE) to "foto"
    )

    /**
     * Memeriksa apakah kalimat perintah pengguna merupakan instruksi majemuk lintas aplikasi.
     */
    fun isCrossAppCommand(userGoal: String): Boolean {
        val clean = userGoal.trim()
        if (clean.isEmpty()) return false
        val parts = clean.split(STAGE_SPLITTER).filter { it.trim().isNotEmpty() }
        if (parts.size < 2) return false

        val appsMentioned = detectMentionedApps(clean)
        // Wajib melibatkan minimal 2 aplikasi BERBEDA agar tergolong alur kerja lintas aplikasi (Cross-App)
        return appsMentioned.size >= 2
    }

    /**
     * Membedah [userGoal] menjadi [CrossAppWorkflowPlan] yang terstruktur rapi.
     */
    fun parseWorkflowPlan(userGoal: String): CrossAppWorkflowPlan {
        val clean = userGoal.trim()
        val clauses = clean.split(STAGE_SPLITTER).map { it.trim() }.filter { it.isNotEmpty() }

        if (clauses.size < 2) {
            val singleApp = detectPrimaryApp(clean)
            val singleIntent = detectClauseIntent(clean)
            val singleStage = CrossAppStage(
                stageIndex = 1,
                title = clean,
                rawClause = clean,
                targetPackageHint = singleApp,
                intent = singleIntent,
                expectedOutputVariable = detectVariableKey(clean)
            )
            return CrossAppWorkflowPlan(
                originalGoal = clean,
                stages = listOf(singleStage)
            )
        }

        val stages = mutableListOf<CrossAppStage>()
        for (i in clauses.indices) {
            val clause = clauses[i]
            val stageNum = i + 1
            val appHint = detectPrimaryApp(clause)
            val intent = detectClauseIntent(clause)
            val varKey = detectVariableKey(clause) ?: if (intent == StageIntent.SEARCH_AND_EXTRACT) "info_tahap_$stageNum" else null

            val title = when (intent) {
                StageIntent.SEARCH_AND_EXTRACT -> "Cari dan salin data: $clause"
                StageIntent.COMPOSE_AND_SEND -> "Kirim data ke tujuan: $clause"
                StageIntent.NAVIGATE_APP -> "Buka dan arahkan: $clause"
                StageIntent.GENERIC_ACTION -> clause
            }

            stages.add(
                CrossAppStage(
                    stageIndex = stageNum,
                    title = title,
                    rawClause = clause,
                    targetPackageHint = appHint,
                    intent = intent,
                    expectedOutputVariable = varKey
                )
            )
        }

        GepyLog.i(TAG, "Rencana alur kerja lintas aplikasi berhasil dibuat: ${stages.size} tahapan.")
        return CrossAppWorkflowPlan(
            originalGoal = clean,
            stages = stages
        )
    }

    private fun detectMentionedApps(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val result = mutableSetOf<String>()
        for ((keyword, pkg) in APP_SIGNATURES) {
            val regex = Regex("""\b${Regex.escape(keyword)}\b""", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(lower)) {
                result.add(pkg)
            }
        }
        return result
    }

    private fun detectPrimaryApp(clause: String): String? {
        val lower = clause.lowercase(Locale.ROOT)
        for ((keyword, pkg) in APP_SIGNATURES) {
            val regex = Regex("""\b${Regex.escape(keyword)}\b""", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(lower)) {
                return pkg
            }
        }
        return null
    }

    private fun detectClauseIntent(clause: String): StageIntent {
        val lower = clause.lowercase(Locale.ROOT)
        return when {
            lower.contains("kirim") || lower.contains("send") || lower.contains("posting") || lower.contains("balas") || lower.contains("chat") -> {
                StageIntent.COMPOSE_AND_SEND
            }
            lower.contains("cari") || lower.contains("cek") || lower.contains("temukan") || lower.contains("salin") || lower.contains("copy") -> {
                StageIntent.SEARCH_AND_EXTRACT
            }
            lower.contains("buka") || lower.contains("pindah") || lower.contains("masuk") -> {
                StageIntent.NAVIGATE_APP
            }
            else -> StageIntent.GENERIC_ACTION
        }
    }

    private fun detectVariableKey(text: String): String? {
        for ((regex, key) in EXTRACT_VARIABLE_PATTERNS) {
            if (regex.containsMatchIn(text)) {
                return key
            }
        }
        return null
    }

    private fun containsCrossAppVerb(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val hasExtract = lower.contains("cari") || lower.contains("cek") || lower.contains("salin")
        val hasSend = lower.contains("kirim") || lower.contains("posting") || lower.contains("wa") || lower.contains("chat")
        return hasExtract && hasSend
    }
}
