package com.teknologigawai.gepy.core.ports

import com.teknologigawai.gepy.features.brain.models.AgentActionType
import org.json.JSONObject

/** Catatan satu langkah eksekusi di dalam ring buffer memori jangka pendek. */
data class StepRecord(
    val stepNumber: Int,
    val action: AgentActionType,
    val targetLabel: String,
    val screenSignatureBefore: String,
    val screenSignatureAfter: String,
    val outcomeStatus: ActionOutcome,
    val targetIndex: Int? = null,
    val textValue: String? = null,
    val thought: String = ""
) {
    fun toPromptJsonObject(): JSONObject = JSONObject().apply {
        put("step", stepNumber)
        put("action", action.wireName)
        if (targetIndex != null) put("target_index", targetIndex)
        put("target", targetLabel)
        if (textValue != null) put("text", textValue)
        put("outcome", outcomeStatus.wireName)
    }
}

/**
 * Cuplikan memori jangka pendek dan catatan lintas aplikasi (*Scratchpad*)
 * yang diumpankan ke `BrainPort` pada setiap giliran penalaran.
 */
data class ShortTermMemorySnapshot(
    val recentSteps: List<StepRecord>,
    val scratchpad: Map<String, String>,
    val loopWarningPrompt: String? = null,
    val activePlanChecklist: List<String> = emptyList(),
    val verifierFeedback: String? = null
) {
    val lastActionOutcome: String
        get() = if (recentSteps.isEmpty()) "INITIAL" else recentSteps.last().outcomeStatus.wireName
}

/** Entri log metrik kinerja per langkah untuk tabel Room/SQLite `telemetry_logs`. */
data class TelemetryEntry(
    val sessionId: String,
    val stepNumber: Int,
    val userGoal: String,
    val packageName: String,
    val rawNodeCount: Int,
    val prunedNodeCount: Int,
    val pruningRatio: Double,
    val llmLatencyMs: Int,
    val totalStepLatencyMs: Int,
    val actionType: String,
    val verificationOutcome: String,
    val createdAt: Long
)

/**
 * Entri jalur aksi deterministik yang telah terverifikasi pada `app_knowledge_graph`
 * untuk diputar ulang oleh *Playbook Fast Path* tanpa memanggil LLM Cloud.
 */
data class PlaybookTransition(
    val packageName: String,
    val screenSignatureHash: String,
    val subgoalIntent: String,
    val targetResourceId: String,
    val actionType: String,
    val successCount: Int,
    val id: Long = 0L,
    val failureCount: Int = 0,
    val lastSessionId: String = "",
    val updatedAt: Long = 0L,
    val isDisabled: Boolean = false
)

/** Klasifikasi hasil transisi simbolik (*Symbolic Edge World Model — SEWM, Room v6*). */
enum class PredictedEdgeOutcome(val wireName: String, val baseSeverity: Float) {
    SAFE_PROGRESS("SAFE_PROGRESS", 0.0f),
    PAYWALL("PAYWALL", 1.0f),
    AD_POPUP("AD_POPUP", 0.95f),
    PERMISSION_MODAL("PERMISSION_MODAL", 0.88f),
    ERROR_DIALOG("ERROR_DIALOG", 0.85f),
    INEFFECTIVE_DEADEND("INEFFECTIVE_DEADEND", 0.80f);

    val isAdverse: Boolean
        get() = this != SAFE_PROGRESS

    companion object {
        fun fromWireName(raw: String): PredictedEdgeOutcome =
            entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) } ?: SAFE_PROGRESS
    }
}

/** Aturan transisi simbolik per elemen UI pada layar spesifik (`< 0,1 ms` query RAM cache). */
data class EdgeRiskRule(
    val id: Long = 0L,
    val packageName: String,
    val screenSignatureHash: String,
    val edgeKey: String,
    val actionType: String,
    val targetLabelSample: String = "",
    val dominantOutcome: PredictedEdgeOutcome = PredictedEdgeOutcome.SAFE_PROGRESS,
    val safeCount: Int = 0,
    val paywallCount: Int = 0,
    val adPopupCount: Int = 0,
    val permissionModalCount: Int = 0,
    val errorCount: Int = 0,
    val ineffectiveCount: Int = 0,
    val riskScore: Float = 0f,
    val mitigationHint: String = "",
    val updatedAtMs: Long = 0L
)

/** Statistik anggaran kapasitas *Symbolic Edge World Model* (target `< 1,5 MB` SQLite, `<= 2.000` baris). */
data class EdgeWorldModelStats(
    val totalRulesInRam: Int,
    val totalRulesInDb: Int,
    val highRiskRuleCount: Int,
    val maxDbRulesCap: Int,
    val estimatedSqliteBytes: Long
)

/**
 * Port Hexagonal untuk manajemen memori sesi, *Scratchpad*, *Knowledge Graph* (*Two-Phase Commit*),
 * *Symbolic Edge World Model (SEWM)*, dan pencatatan telemetri lokal.
 */
interface MemoryPort {
    /** Mereset riwayat langkah sesi saat memulai perintah suara baru. */
    fun resetSession(clearScratchpad: Boolean = true)

    /** Mencatat satu langkah eksekusi ke dalam ring buffer memori jangka pendek. */
    fun recordStep(record: StepRecord)

    /** Menyimpan fakta penting ke dalam *Scratchpad Memory* lintas aplikasi. */
    fun saveScratchpadNote(key: String, value: String)

    /** Membaca nilai catatan *Scratchpad* berdasarkan [key]. */
    fun readScratchpadNote(key: String): String?

    /** Mengembalikan seluruh catatan *Scratchpad* aktif secara tidak dapat dimodifikasi. */
    fun getScratchpadNotes(): Map<String, String>

    /** Membangun cuplikan memori jangka pendek beserta deteksi perulangan untuk LLM. */
    fun buildSnapshot(): ShortTermMemorySnapshot

    /** Memperbarui daftar sub-target/rencana bertingkat yang sedang aktif dijalankan. */
    fun setActivePlanChecklist(checklist: List<String>) {}

    /** Mengatur umpan balik penolakan verifikasi penyelesaian tujuan dari verifier. */
    fun setVerifierFeedback(feedback: String?) {}

    /** Mengambil petunjuk elemen UI yang pernah berhasil pada [packageName] (*Knowledge Injection*). */
    suspend fun getAppKnowledgeHint(packageName: String): String?

    /** Menyimpan jejak transisi elemen UI yang terbukti berhasil secara langsung ke `app_knowledge_graph`. */
    suspend fun saveSuccessfulTransition(
        packageName: String,
        intentGoal: String,
        resourceId: String,
        screenSignatureHash: String = "",
        actionType: String = "CLICK"
    )

    /**
     * Menampung kandidat transisi langkah ke buffer sementara (*Phase 1 — Staging*).
     * Belum ditulis ke database sampai tujuan akhir terverifikasi sukses (*Phase 2 — Commit*).
     */
    fun stageCandidateTransition(
        packageName: String,
        intentGoal: String,
        resourceId: String,
        screenSignatureHash: String,
        actionType: String
    ) {}

    /** Menyimpan permanen seluruh transisi di buffer *staging* setelah tujuan akhir terverifikasi sukses. */
    suspend fun commitStagedTransitions(sessionId: String) {}

    /** Membuang seluruh kandidat transisi di buffer *staging* saat tugas gagal/dibatalkan. */
    fun discardStagedTransitions() {}

    /** Mencatat kegagalan pada entri *Playbook* (`failure_count++` & nonaktifkan otomatis) agar tidak berulang. */
    suspend fun penalizePlaybookTransition(
        packageName: String,
        screenSignatureHash: String,
        intentGoal: String,
        targetResourceId: String,
        actionType: String
    ) {}

    /** Membatalkan (*rollback*) dan menonaktifkan memori *Playbook* yang berasal dari [sessionId] (umpan balik 👎). */
    suspend fun rollbackSessionMemory(sessionId: String) {}

    /** Mengambil seluruh daftar memori *Playbook* untuk ditampilkan dan dikelola pengguna di UI. */
    suspend fun getAllPlaybookEntries(): List<PlaybookTransition> = emptyList()

    /** Menghapus satu entri memori *Playbook* berdasarkan [id]. */
    suspend fun deletePlaybookEntry(id: Long) {}

    /** Menghapus bersih seluruh memori *Playbook* di RAM maupun SQLite. */
    suspend fun clearAllPlaybookMemory() {}

    /**
     * Mencari transisi *Playbook* deterministik yang cocok dengan [packageName],
     * [screenSignatureHash], dan [intentGoal] apabila telah berhasil minimal [minSuccessCount] kali.
     */
    suspend fun findPlaybookTransition(
        packageName: String,
        screenSignatureHash: String,
        intentGoal: String,
        minSuccessCount: Int = 2
    ): PlaybookTransition?

    /** Mencatat pilihan disambiguasi kontak pengguna agar diurutkan paling atas di masa depan. */
    suspend fun recordContactPreference(
        queryName: String,
        packageName: String,
        resolvedDisplayName: String
    )

    /** Mengurutkan kandidat disambiguasi [options] berdasarkan frekuensi pilihan masa lalu. */
    suspend fun rankDisambiguationOptions(
        queryName: String,
        packageName: String,
        options: List<String>
    ): List<String>

    /** Menyimpan log metrik langkah ke tabel `telemetry_logs`. */
    suspend fun logTelemetry(entry: TelemetryEntry)

    /** Mengambil daftar riwayat log telemetri terbaru. */
    suspend fun getRecentTelemetryLogs(limit: Int = 50): List<TelemetryEntry>

    /**
     * Mencatat pelajaran taktis mandiri (*Self-Evolving Episodic Memory*) ketika Gepy berhasil
     * menuntaskan tujuan sesudah melewati langkah `INEFFECTIVE` atau penolakan verifikasi.
     */
    suspend fun recordEpisodicRecoveryLesson(
        packageName: String,
        goal: String,
        steps: List<StepRecord>
    ) {}

    /** Mengambil petunjuk pelajaran episodik teratas untuk [packageName] (`0` token ekstra). */
    suspend fun getEpisodicLessonsHint(packageName: String): String? = null

    // --- Room v6: Symbolic Edge World Model (SEWM) ---

    /** Memastikan cache RAM aturan SEWM untuk layar [packageName] + [screenSignatureHash] siap dipakai `O(1)`. */
    suspend fun warmEdgeRisksForScreen(
        packageName: String,
        screenSignatureHash: String
    ) {}

    /**
     * Prediksi risiko pra-klik deterministik murni di RAM (`< 0,1 ms`, nol I/O SQLite).
     * Mengembalikan seluruh aturan transisi yang tercatat pada layar saat ini.
     */
    fun predictEdgeRisksForScreenFast(
        packageName: String,
        screenSignatureHash: String
    ): List<EdgeRiskRule> = emptyList()

    /** Mencatat hasil transisi simbolik pasca-eksekusi ke RAM cache dan Room `ui_transition_rules`. */
    suspend fun observeEdgeTransition(
        packageName: String,
        screenSignatureHash: String,
        edgeKey: String,
        actionType: String,
        targetLabelSample: String,
        outcome: PredictedEdgeOutcome,
        mitigationHint: String = ""
    ) {}

    /** Mengambil ringkasan kapasitas aturan SEWM di RAM & SQLite (`< 1,5 MB` anggaran Galaxy A06). */
    suspend fun getEdgeWorldModelStats(): EdgeWorldModelStats = EdgeWorldModelStats(
        totalRulesInRam = 0,
        totalRulesInDb = 0,
        highRiskRuleCount = 0,
        maxDbRulesCap = 2_000,
        estimatedSqliteBytes = 0L
    )

    /** Menghapus seluruh aturan risiko *Symbolic Edge World Model* dari RAM & SQLite. */
    suspend fun clearAllEdgeWorldModelRules() {}
}
