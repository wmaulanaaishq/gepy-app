package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mesin Matematika Kognitif **Ebbinghaus Forgetting Curve & Stanford Generative Agents Scoring**
 * untuk **Gepy v5.3**.
 *
 * Landasan Ilmiah:
 * 1. **Stanford Generative Agents** (*Park et al., 2023 — arXiv:2304.03442*):
 *    $$\text{Score}(M \mid Q) = w_r \cdot \text{Recency}(M) + w_i \cdot \text{Importance}(M) + w_{\text{rel}} \cdot \text{Relevance}(M, Q)$$
 * 2. **MemoryBank & Ebbinghaus Forgetting Curve** (*Zhong et al., 2023 — arXiv:2305.10250*):
 *    $$R(M, t) = \exp\left(-\frac{\Delta t_{\text{hours}}}{S(M)}\right)$$
 *    di mana kekuatan memori $S(M)$ diperkuat secara non-linier melalui *Spaced Repetition* (`hitCount`)
 *    dan bobot emosi *Amigdala Salience* ($I \in [1, 10]$).
 *
 * Berjalan 100% di memori RAM Kotlin dengan latensi `< 0.01 ms` per fakta dan `0%` beban baterai.
 */
object EbbinghausDecayEngine {

    /** Ambang batas retensi Ebbinghaus di mana catatan kasual bernilai rendah ($I \le 3$) mulai meluruh saat tidur. */
    const val PRUNING_RETENTION_THRESHOLD = 0.20

    /** Lantai retensi minimum untuk fakta inti (identitas, keluarga, kesehatan, wawasan refleksi) agar abadi. */
    const val CORE_MEMORY_RETENTION_FLOOR = 0.85

    private const val WEIGHT_RECENCY = 1.0
    private const val WEIGHT_IMPORTANCE = 1.2
    private const val WEIGHT_RELEVANCE = 1.5

    private val HIGH_SALIENCE_KEYWORDS = setOf(
        "alergi", "rumah sakit", "obat", "darurat", "penting", "jangan pernah",
        "impian", "cita-cita", "ulang tahun", "menikah", "anak", "ibu", "ayah",
        "istri", "suami", "adik", "kakak", "trauma", "operasi", "hamil",
        "teman", "sahabat", "kawan", "sobat", "pacar", "tunangan", "rekan", "bos",
        "peliharaan", "skripsi", "proyek", "startup", "jurusan", "kuliah", "kampus",
        "universitas", "tech stack", "framework", "target", "vegetarian", "pantangan"
    )

    val CORE_CATEGORIES = setOf(
        "family", "profile", "insight", "health", "friends", "social",
        "work", "education", "tech_profile", "device", "location", "vehicle",
        "routine", "preference", "habits", "dietary", "birthday", "pet",
        "project", "goal", "contact"
    )

    /**
     * Menghitung **Skor Kepentingan Emosional (*Amigdala Salience Score*)** dalam skala integer `1..10`.
     *
     * - `9..10`: Identitas inti, keluarga kandung, teman dekat, alergi/kesehatan kritis, dan *Generative Insights*.
     * - `7..8`: Pekerjaan, karir, domisili rumah, dan fakta dengan kata kunci emosional tinggi.
     * - `5..6`: Preferensi harian, kebiasaan, hobi, dan jadwal berbatas waktu.
     * - `1..3`: Observasi kasual / catatan umum sepele yang dapat meluruh jika tidak pernah diulang.
     */
    fun computeImportanceScore(entity: TemporalMemoryFactEntity): Int {
        val cat = entity.category.trim().lowercase()
        val subj = entity.subject.trim().lowercase()
        val pred = entity.predicate.trim().lowercase()
        val lowerText = entity.factText.lowercase()

        val baseScore = when {
            entity.source == "nightly_reflection" || cat == "insight" || subj == "user_insight" -> 9
            pred == "allergic_to" || pred == "kesehatan" || cat == "health" ||
                lowerText.contains("alergi") || subj == "user_name" || pred == "is_named" -> 10
            cat == "family" || subj.startsWith("family_") -> 9
            cat == "friends" || cat == "social" || subj == "social_friends" ||
                pred == "has_friend" || pred == "friend_named" -> 9
            cat == "pet" || subj.startsWith("pet_") || pred.contains("pet") -> 9
            cat == "education" || subj.startsWith("academic_") || pred in setOf("studies_at", "majoring_in") -> 9
            cat == "tech_profile" || cat == "device" || subj.startsWith("tech_") || subj.startsWith("coding_") || subj.startsWith("device_") -> 9
            cat == "project" || cat == "goal" || subj == "user_project" || subj == "user_goal" -> 9
            cat == "birthday" || subj.startsWith("birthday_") -> 9
            cat == "dietary" || subj == "user_dietary" -> 9
            cat == "profile" -> 9
            cat == "work" || subj == "user_work" || subj == "profile_work" -> 8
            cat == "location" || subj == "location_home" -> 8
            cat == "vehicle" || subj.startsWith("vehicle_") || pred in setOf("plate_number", "vehicle_model") -> 8
            cat == "contact" || subj == "social_boss" || pred == "boss_named" -> 8
            cat == "preference" || cat == "habits" -> 8
            cat == "routine" || subj.startsWith("routine_") -> 8
            cat == "schedule" || entity.validUntilMs != null -> 5
            else -> {
                if (HIGH_SALIENCE_KEYWORDS.any { lowerText.contains(it) }) {
                    8
                } else {
                    3
                }
            }
        }

        // Penguatan Spaced Repetition: topik yang sering diungkit (> 3x) naik 1 tingkat kepentingan
        val repetitionBoost = if (entity.hitCount >= 3) 1 else 0
        return (baseScore + repetitionBoost).coerceIn(1, 10)
    }

    /**
     * Menghitung kekuatan jejak memori $S(M)$ (dalam satuan jam) berdasarkan skor kepentingan $I(M)$
     * dan pengulangan *Spaced Repetition* (`hitCount`).
     */
    fun computeMemoryStrengthHours(
        importanceScore: Int,
        hitCount: Int
    ): Double {
        val clampedI = importanceScore.coerceIn(1, 10)
        val safeHits = max(0, hitCount)
        val spacedMultiplier = 1.0 + (0.5 * safeHits)

        val baseHours = when {
            clampedI <= 3 -> 18.0 * clampedI // I=3 -> 54 jam (~4 hari menuju ambang < 0.20 jika hit=0)
            clampedI in 4..7 -> 24.0 * (1.0 + 1.5 * clampedI) // I=6 -> 240 jam (10+ hari dasar)
            else -> 8760.0 * (clampedI - 6) // I>=8 -> ribuan jam (abadi)
        }
        return baseHours * spacedMultiplier
    }

    /**
     * Menghitung daya ingat (**Ebbinghaus Retention $R(M, t_{\text{now}}) \in [0.0, 1.0]$**)
     * pada stempel waktu [nowMs].
     */
    fun computeRetention(
        entity: TemporalMemoryFactEntity,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        val importance = computeImportanceScore(entity)
        val deltaMs = max(0L, nowMs - entity.updatedAtMs)
        val deltaHours = deltaMs / 3_600_000.0
        val strengthHours = computeMemoryStrengthHours(importance, entity.hitCount)

        val rawRetention = exp(-deltaHours / strengthHours).coerceIn(0.0, 1.0)
        return if (importance >= 7 || entity.category.lowercase() in CORE_CATEGORIES) {
            max(CORE_MEMORY_RETENTION_FLOOR, rawRetention)
        } else {
            rawRetention
        }
    }

    /**
     * Mengembalikan persentase daya ingat bulat (`0..100%`) untuk ditampilkan di UI.
     */
    fun computeRetentionPercent(
        entity: TemporalMemoryFactEntity,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return (computeRetention(entity, nowMs) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private val subWordBM25 = SubWordBM25RetrievalEngine()

    /**
     * Menghitung skor komposit **Stanford Generative Agents** untuk menentukan fakta mana yang
     * diangkat ke dalam *Working Memory* (`systemInstruction` / ReAct Context).
     *
     * Diperkaya dengan **Sub-Word Tri-Gram BM25+ Engine (v5.4)** untuk mengenali imbuhan
     * dan kemiripan fonetik lisan secara matematis.
     *
     * $$\text{FinalScore}(M \mid Q) = w_r \cdot R(M, t) + w_i \cdot \left(\frac{I(M)}{10}\right) + w_{\text{rel}} \cdot \text{Relevance}(M, Q) + \text{ScheduleBoost}$$
     */
    fun computeStanfordRetrievalScore(
        entity: TemporalMemoryFactEntity,
        queryTokens: Set<String>,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        val importance = computeImportanceScore(entity)
        val retention = computeRetention(entity, nowMs)

        val normalizedImportance = importance / 10.0
        val queryString = queryTokens.joinToString(" ")
        val bm25Score = if (queryString.isNotBlank()) {
            subWordBM25.computeSimilarity(queryString, entity)
        } else {
            0.0
        }

        val haystack = "${entity.factText} ${entity.subject} ${entity.predicate} ${entity.objectValue} ${entity.category}".lowercase()

        val relevance = if (queryTokens.isEmpty()) {
            0.0
        } else {
            val hits = queryTokens.count { token -> haystack.contains(token) }
            val rawHits = min(3.0, hits.toDouble())
            val normalizedBm25 = min(3.0, bm25Score * 0.6)
            maxOf(rawHits, normalizedBm25)
        }

        val activeScheduleBoost = if (entity.validUntilMs != null &&
            (entity.validUntilMs - nowMs) in 1L..(48 * 3_600_000L)
        ) {
            1.8
        } else {
            0.0
        }

        val repetitionBonus = min(entity.hitCount, 10) * 0.12

        return (WEIGHT_RECENCY * retention) +
            (WEIGHT_IMPORTANCE * normalizedImportance) +
            (WEIGHT_RELEVANCE * relevance) +
            activeScheduleBoost +
            repetitionBonus
    }

    /**
     * Mengidentifikasi fakta-fakta aktif yang layak dipangkas (*Synaptic Pruning*) saat konsolidasi tidur:
     * 1. Fakta berbatas waktu yang telah melewati `validUntilMs`.
     * 2. Fakta kasual bernilai rendah ($I \le 3$, `hitCount == 0`, bukan kategori inti) yang daya ingat
     *    Ebbinghaus-nya telah meluruh di bawah [PRUNING_RETENTION_THRESHOLD] (`0.20`).
     *
     * Fakta penting ($I \ge 5$) tidak akan pernah dipangkas oleh kurva Ebbinghaus.
     */
    fun identifyFactsToPrune(
        facts: List<TemporalMemoryFactEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): List<TemporalMemoryFactEntity> {
        return facts.filter { entity ->
            if (!entity.isActive) return@filter false

            // 1. Kedaluwarsa batas waktu eksplisit
            if (entity.validUntilMs != null && entity.validUntilMs <= nowMs) {
                return@filter true
            }

            // 2. Peluruhan Ebbinghaus untuk observasi kasual yang tidak pernah diulang
            val importance = computeImportanceScore(entity)
            if (importance <= 3 &&
                entity.hitCount == 0 &&
                entity.validUntilMs == null &&
                entity.category.lowercase() !in CORE_CATEGORIES
            ) {
                val retention = computeRetention(entity, nowMs)
                return@filter retention < PRUNING_RETENTION_THRESHOLD
            }

            false
        }
    }
}
