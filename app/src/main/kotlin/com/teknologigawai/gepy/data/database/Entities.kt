package com.teknologigawai.gepy.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.teknologigawai.gepy.core.ports.EdgeRiskRule
import com.teknologigawai.gepy.core.ports.PlaybookTransition
import com.teknologigawai.gepy.core.ports.PredictedEdgeOutcome
import com.teknologigawai.gepy.core.ports.TelemetryEntry

/** Tabel `app_package_cache`: menyimpan pemetaan alias lisan ke `packageName` Android. */
@Entity(tableName = "app_package_cache")
data class AppPackageCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "alias_name")
    val aliasName: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "last_launched_at")
    val lastLaunchedAt: Long
)

/** Tabel `contact_disambiguation_cache`: menyimpan preferensi disambiguasi kontak pengguna. */
@Entity(
    tableName = "contact_disambiguation_cache",
    primaryKeys = ["query_name", "package_name", "resolved_display_name"]
)
data class ContactDisambiguationEntity(
    @ColumnInfo(name = "query_name")
    val queryName: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "resolved_display_name")
    val resolvedDisplayName: String,
    @ColumnInfo(name = "selection_count")
    val selectionCount: Int = 1
)

/** Tabel `app_knowledge_graph`: menyimpan jejak transisi deterministik *Playbook Fast Path*. */
@Entity(
    tableName = "app_knowledge_graph",
    indices = [Index(value = ["package_name"], name = "idx_knowledge_pkg")]
)
data class AppKnowledgeGraphEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "screen_signature_hash")
    val screenSignatureHash: String,
    @ColumnInfo(name = "subgoal_intent")
    val subgoalIntent: String,
    @ColumnInfo(name = "target_resource_id")
    val targetResourceId: String,
    @ColumnInfo(name = "action_type")
    val actionType: String,
    @ColumnInfo(name = "success_count")
    val successCount: Int = 1,
    @ColumnInfo(name = "failure_count")
    val failureCount: Int = 0,
    @ColumnInfo(name = "last_session_id")
    val lastSessionId: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_disabled")
    val isDisabled: Boolean = false
) {
    fun toDomainModel(): PlaybookTransition = PlaybookTransition(
        id = id,
        packageName = packageName,
        screenSignatureHash = screenSignatureHash,
        subgoalIntent = subgoalIntent,
        targetResourceId = targetResourceId,
        actionType = actionType,
        successCount = successCount,
        failureCount = failureCount,
        lastSessionId = lastSessionId,
        updatedAt = updatedAt,
        isDisabled = isDisabled
    )
}

/** Tabel `telemetry_logs`: menyimpan metrik kompresi konteks dan latensi langkah. */
@Entity(tableName = "telemetry_logs")
data class TelemetryLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "step_number")
    val stepNumber: Int,
    @ColumnInfo(name = "user_goal")
    val userGoal: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "raw_node_count")
    val rawNodeCount: Int,
    @ColumnInfo(name = "pruned_node_count")
    val prunedNodeCount: Int,
    @ColumnInfo(name = "pruning_ratio")
    val pruningRatio: Double,
    @ColumnInfo(name = "llm_latency_ms")
    val llmLatencyMs: Int,
    @ColumnInfo(name = "total_step_latency_ms")
    val totalStepLatencyMs: Int,
    @ColumnInfo(name = "action_type")
    val actionType: String,
    @ColumnInfo(name = "verification_outcome")
    val verificationOutcome: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    fun toDomainModel(): TelemetryEntry = TelemetryEntry(
        sessionId = sessionId,
        stepNumber = stepNumber,
        userGoal = userGoal,
        packageName = packageName,
        rawNodeCount = rawNodeCount,
        prunedNodeCount = prunedNodeCount,
        pruningRatio = pruningRatio,
        llmLatencyMs = llmLatencyMs,
        totalStepLatencyMs = totalStepLatencyMs,
        actionType = actionType,
        verificationOutcome = verificationOutcome,
        createdAt = createdAt
    )

    companion object {
        fun fromDomainModel(entry: TelemetryEntry): TelemetryLogEntity = TelemetryLogEntity(
            sessionId = entry.sessionId,
            stepNumber = entry.stepNumber,
            userGoal = entry.userGoal,
            packageName = entry.packageName,
            rawNodeCount = entry.rawNodeCount,
            prunedNodeCount = entry.prunedNodeCount,
            pruningRatio = entry.pruningRatio,
            llmLatencyMs = entry.llmLatencyMs,
            totalStepLatencyMs = entry.totalStepLatencyMs,
            actionType = entry.actionType,
            verificationOutcome = entry.verificationOutcome,
            createdAt = entry.createdAt
        )
    }
}

/**
 * Tabel `episodic_lessons` (Room v3): menyimpan aturan pelajaran taktis mandiri (*Self-Evolving Episodic Memory*)
 * yang didistilasi secara otomatis ketika Gepy berhasil menyelesaikan tujuan setelah sempat mengalami
 * kegagalan langkah (`INEFFECTIVE` atau `VERIFY_REJECTED`).
 */
@Entity(
    tableName = "episodic_lessons",
    indices = [Index(value = ["package_name"], name = "idx_episodic_pkg")]
)
data class EpisodicLessonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "goal_pattern")
    val goalPattern: String,
    @ColumnInfo(name = "lesson_rule")
    val lessonRule: String,
    @ColumnInfo(name = "hit_count")
    val hitCount: Int = 1,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Tabel `personal_wellness_logs` (Room v3): menyimpan rekam jejak kesehatan harian, durasi layar medsos,
 * dan telemetri sensor ruangan ESP32-S3 Wi-Fi CSI 100% lokal milik pengguna sendiri.
 */
@Entity(tableName = "personal_wellness_logs")
data class PersonalWellnessLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "date_key")
    val dateKey: String,
    @ColumnInfo(name = "step_count")
    val stepCount: Int = 0,
    @ColumnInfo(name = "social_scroll_seconds")
    val socialScrollSeconds: Int = 0,
    @ColumnInfo(name = "sedentary_bed_minutes")
    val sedentaryBedMinutes: Int = 0,
    @ColumnInfo(name = "interventions_count")
    val interventionsCount: Int = 0,
    @ColumnInfo(name = "last_csi_event")
    val lastCsiEvent: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Tabel `temporal_memory_facts` (Room v4): Graf Memori Personal Temporal (terinspirasi Mem0 / Graphiti,
 * 100% lokal SQLite on-device tanpa ketergantungan login atau cloud).
 *
 * Menyimpan fakta dalam struktur triplet `(subject, predicate, object_value)` dengan masa berlaku
 * (`valid_from_ms`..`valid_until_ms`) serta jejak riwayat perubahan (`is_active`, `superseded_by_id`)
 * agar Gepy dapat menjawab pertanyaan masa lalu maupun masa kini secara akurat.
 */
@Entity(
    tableName = "temporal_memory_facts",
    indices = [
        Index(value = ["subject", "is_active"], name = "idx_temporal_subject_active"),
        Index(value = ["valid_until_ms"], name = "idx_temporal_valid_until"),
        Index(value = ["category"], name = "idx_temporal_category")
    ]
)
data class TemporalMemoryFactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "predicate")
    val predicate: String,
    @ColumnInfo(name = "object_value")
    val objectValue: String,
    @ColumnInfo(name = "fact_text")
    val factText: String,
    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f,
    @ColumnInfo(name = "valid_from_ms")
    val validFromMs: Long,
    @ColumnInfo(name = "valid_until_ms")
    val validUntilMs: Long? = null,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "superseded_by_id")
    val supersededById: Long? = null,
    @ColumnInfo(name = "hit_count")
    val hitCount: Int = 0,
    @ColumnInfo(name = "source")
    val source: String = "voice",
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Tabel `memory_vectors` (Room v5): Indeks vektor semantik on-device Layer 2 (`gemini-embedding-2`, 768 dimensi MRL).
 * Setiap baris merepresentasikan vektor L2-normalized berformat biner `Float32 Little-Endian BLOB` (`~3 KB` per fakta),
 * dibatasi maksimal `500` entri pada penyimpanan SQLite lokal agar tetap ringan pada Galaxy A06.
 */
@Entity(
    tableName = "memory_vectors",
    indices = [
        Index(value = ["fact_id"], name = "idx_vector_fact_id"),
        Index(value = ["content_hash"], name = "idx_vector_hash"),
        Index(value = ["category"], name = "idx_vector_category")
    ]
)
data class MemoryVectorEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "fact_id")
    val factId: Long? = null,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "content_text")
    val contentText: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "embedding_blob", typeAffinity = ColumnInfo.BLOB)
    val embeddingBlob: ByteArray,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "dimensions")
    val dimensions: Int,
    @ColumnInfo(name = "importance")
    val importance: Int = 5,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryVectorEntity) return false
        return id == other.id &&
            factId == other.factId &&
            category == other.category &&
            subject == other.subject &&
            contentText == other.contentText &&
            contentHash == other.contentHash &&
            embeddingBlob.contentEquals(other.embeddingBlob) &&
            modelId == other.modelId &&
            dimensions == other.dimensions &&
            importance == other.importance &&
            createdAtMs == other.createdAtMs &&
            updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (factId?.hashCode() ?: 0)
        result = 31 * result + category.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + contentText.hashCode()
        result = 31 * result + contentHash.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + dimensions
        result = 31 * result + importance
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }
}

/**
 * Tabel `ui_transition_rules` (Room v6 — **Symbolic Edge World Model / SEWM**):
 * Memprediksi risiko sebelum menyentuh tombol (`PAYWALL`, `AD_POPUP`, `PERMISSION_MODAL`,
 * `ERROR_DIALOG`, `INEFFECTIVE_DEADEND`) secara deterministik tanpa NPU.
 *
 * Kapasitas dibatasi keras `<= 2.000` baris (`~0,9 MB`, selalu `< 1,5 MB` SQLite termasuk 3 indeks)
 * dan disinkronkan dengan peta RAM `O(1)` (`< 0,1 ms` query).
 */
@Entity(
    tableName = "ui_transition_rules",
    indices = [
        Index(
            value = ["package_name", "screen_signature_hash", "edge_key", "action_type"],
            unique = true,
            name = "idx_sewm_unique_edge"
        ),
        Index(
            value = ["package_name", "screen_signature_hash"],
            name = "idx_sewm_pkg_screen"
        ),
        Index(
            value = ["updated_at_ms"],
            name = "idx_sewm_updated"
        )
    ]
)
data class UiTransitionRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "screen_signature_hash")
    val screenSignatureHash: String,
    @ColumnInfo(name = "edge_key")
    val edgeKey: String,
    @ColumnInfo(name = "action_type")
    val actionType: String,
    @ColumnInfo(name = "target_label_sample")
    val targetLabelSample: String = "",
    @ColumnInfo(name = "dominant_outcome")
    val dominantOutcome: String,
    @ColumnInfo(name = "safe_count")
    val safeCount: Int = 0,
    @ColumnInfo(name = "paywall_count")
    val paywallCount: Int = 0,
    @ColumnInfo(name = "ad_popup_count")
    val adPopupCount: Int = 0,
    @ColumnInfo(name = "permission_modal_count")
    val permissionModalCount: Int = 0,
    @ColumnInfo(name = "error_count")
    val errorCount: Int = 0,
    @ColumnInfo(name = "ineffective_count")
    val ineffectiveCount: Int = 0,
    @ColumnInfo(name = "risk_score")
    val riskScore: Float = 0f,
    @ColumnInfo(name = "mitigation_hint")
    val mitigationHint: String = "",
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toDomainModel(): EdgeRiskRule = EdgeRiskRule(
        id = id,
        packageName = packageName,
        screenSignatureHash = screenSignatureHash,
        edgeKey = edgeKey,
        actionType = actionType,
        targetLabelSample = targetLabelSample,
        dominantOutcome = PredictedEdgeOutcome.fromWireName(dominantOutcome),
        safeCount = safeCount,
        paywallCount = paywallCount,
        adPopupCount = adPopupCount,
        permissionModalCount = permissionModalCount,
        errorCount = errorCount,
        ineffectiveCount = ineffectiveCount,
        riskScore = riskScore,
        mitigationHint = mitigationHint,
        updatedAtMs = updatedAtMs
    )
}
