package com.teknologigawai.gepy.data.database

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.EdgeRiskRule
import com.teknologigawai.gepy.core.ports.EdgeWorldModelStats
import com.teknologigawai.gepy.core.ports.MemoryPort
import com.teknologigawai.gepy.core.ports.PlaybookTransition
import com.teknologigawai.gepy.core.ports.PredictedEdgeOutcome
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.core.ports.TelemetryEntry
import com.teknologigawai.gepy.features.brain.SymbolicEdgeWorldModel
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.launcher.PackageAliasCache
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implementasi `MemoryPort` dan `PackageAliasCache` yang menggabungkan:
 * - *Ring Buffer* di memori (kapasitas default 8 langkah)
 * - *Scratchpad Memory* lintas aplikasi
 * - Pendeteksi *Infinite Loop* deterministik
 * - *Playbook Graph* & *Contact Preference Ranker*
 * - Persistensi Room Database (`gepy_core.db`) dengan fallback in-memory untuk pengujian unit JVM
 */
class SqliteMemoryRepository(
    private val dao: GepyDao? = null,
    val historyLimit: Int = EnvConfig.shortTermHistoryLimit,
    val maxSewmRulesCap: Int = SymbolicEdgeWorldModel.MAX_SEWM_DB_RULES
) : MemoryPort, PackageAliasCache {

    private val stepBuffer = ArrayDeque<StepRecord>()
    private val scratchpad = LinkedHashMap<String, String>()
    private val stagedTransitions = LinkedHashMap<String, PlaybookTransition>()
    private var activePlanChecklist: List<String> = emptyList()
    private var verifierFeedback: String? = null
    private val inMemoryTelemetryFallback = ArrayList<TelemetryEntry>()
    private val inMemoryContactRank = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val inMemoryPlaybookCache = ConcurrentHashMap<String, PlaybookTransition>()
    private val inMemoryAliasCache = ConcurrentHashMap<String, String>()
    private val inMemoryEpisodicCache = ConcurrentHashMap<String, MutableList<EpisodicLessonEntity>>()
    private val inMemorySewmByScreen = ConcurrentHashMap<String, ConcurrentHashMap<String, EdgeRiskRule>>()
    private val sewmWriteMutex = Mutex()
    @Volatile
    private var sewmCachePreloaded: Boolean = false
    private var nextInMemoryPlaybookId: Long = 1L
    private var nextInMemoryEdgeRuleId: Long = 1L

    private val _telemetryFlow = MutableStateFlow<List<TelemetryEntry>>(emptyList())
    val telemetryFlow: StateFlow<List<TelemetryEntry>> = _telemetryFlow.asStateFlow()

    @Synchronized
    override fun resetSession(clearScratchpad: Boolean) {
        stepBuffer.clear()
        stagedTransitions.clear()
        activePlanChecklist = emptyList()
        verifierFeedback = null
        if (clearScratchpad) {
            scratchpad.clear()
        }
    }

    @Synchronized
    override fun setActivePlanChecklist(checklist: List<String>) {
        activePlanChecklist = checklist.toList()
    }

    @Synchronized
    override fun setVerifierFeedback(feedback: String?) {
        verifierFeedback = feedback?.trim()?.ifEmpty { null }
    }

    @Synchronized
    override fun recordStep(record: StepRecord) {
        while (stepBuffer.size >= historyLimit) {
            stepBuffer.removeFirst()
        }
        stepBuffer.addLast(record)
    }

    @Synchronized
    override fun saveScratchpadNote(key: String, value: String) {
        val cleanKey = key.trim()
        val cleanVal = value.trim()
        if (cleanKey.isEmpty() || cleanVal.isEmpty()) {
            return
        }
        scratchpad[cleanKey] = cleanVal
    }

    @Synchronized
    override fun readScratchpadNote(key: String): String? {
        return scratchpad[key.trim()]
    }

    @Synchronized
    override fun getScratchpadNotes(): Map<String, String> {
        return LinkedHashMap(scratchpad)
    }

    @Synchronized
    override fun buildSnapshot(): ShortTermMemorySnapshot {
        val steps = stepBuffer.toList()
        return ShortTermMemorySnapshot(
            recentSteps = steps,
            scratchpad = getScratchpadNotes(),
            loopWarningPrompt = detectLoopWarning(steps),
            activePlanChecklist = activePlanChecklist,
            verifierFeedback = verifierFeedback
        )
    }

    private fun detectLoopWarning(steps: List<StepRecord>): String? {
        if (steps.isEmpty()) {
            return null
        }

        // 1. Deteksi perulangan siklus aksi (Cyclic Action Loop) pada tombol berbagi/cerita
        val shareRepeatCount = steps.count { s ->
            (s.action == AgentActionType.CLICK || s.action == AgentActionType.CONFIRM_AND_CLICK) &&
                (s.targetLabel.contains("bagikan", ignoreCase = true) ||
                 s.targetLabel.contains("share", ignoreCase = true) ||
                 s.targetLabel.contains("cerita", ignoreCase = true))
        }
        if (shareRepeatCount >= 2) {
            return "PERINGATAN SIKLUS LOOP: Tombol berbagi/cerita sudah diketuk $shareRepeatCount kali dalam sesi ini. " +
                "Jika editor cerita telah tertutup dan layar kembali ke postingan awal, proses posting telah selesai di latar belakang! " +
                "JANGAN mengetuk tombol Bagikan lagi, segera keluarkan action DONE."
        }

        val last = steps.last()

        // 2. Deteksi Aksi Identik Berturut-turut (baik INEFFECTIVE maupun CORRECT)
        if (steps.size >= 2) {
            val prev = steps[steps.size - 2]
            val sameAction = prev.action == last.action &&
                ((prev.targetIndex != null && prev.targetIndex == last.targetIndex) ||
                 (prev.targetLabel.isNotBlank() && prev.targetLabel.equals(last.targetLabel, ignoreCase = true))) &&
                prev.textValue == last.textValue

            if (sameAction) {
                if (prev.outcomeStatus == ActionOutcome.INEFFECTIVE && last.outcomeStatus == ActionOutcome.INEFFECTIVE) {
                    return "PERINGATAN ANTI-LOOP: Aksi ${last.action.wireName} pada " +
                        "target_index=${last.targetIndex ?: "-"} (\"${last.targetLabel}\") " +
                        "telah GAGAL mengubah layar sebanyak 2 kali berturut-turut (INEFFECTIVE). " +
                        "DILARANG memilih kombinasi aksi dan indeks tersebut lagi! " +
                        "Gunakan jalur alternatif seperti SCROLL, mengetuk elemen induk, atau BACK."
                } else if (last.action in setOf(AgentActionType.CLICK, AgentActionType.CONFIRM_AND_CLICK, AgentActionType.SET_TEXT, AgentActionType.SET_TEXT_AND_SUBMIT)) {
                    return "PERINGATAN ANTI-LOOP: Aksi ${last.action.wireName} pada " +
                        "target \"${last.targetLabel}\" (idx=${last.targetIndex ?: "-"}) " +
                        "telah dieksekusi 2 kali berturut-turut! DILARANG mengetuk elemen yang sama lagi. " +
                        "Ganti strategi sekarang: tekan BACK untuk memulihkan layar, gunakan SCROLL, atau ketuk elemen lain."
                }
            }
        }

        // 3. Deteksi Siklus Osilasi 2-Langkah (A -> B -> A -> B)
        if (steps.size >= 4) {
            val s1 = steps[steps.size - 4]
            val s2 = steps[steps.size - 3]
            val s3 = steps[steps.size - 2]
            val s4 = steps.last()
            val isOscillating = (s1.action == s3.action && s1.targetLabel == s3.targetLabel) &&
                (s2.action == s4.action && s2.targetLabel == s4.targetLabel)
            if (isOscillating) {
                return "PERINGATAN SIKLUS OSCILLATION: Terdeteksi perulangan siklus bolak-balik antara " +
                    "\"${s1.targetLabel}\" dan \"${s2.targetLabel}\" (A->B->A->B). " +
                    "Hentikan perulangan ini! Evaluasi apakah tugas sudah selesai (DONE) atau ambil jalur navigasi lain."
            }
        }

        // 4. Deteksi Keletihan Scroll (>= 3x berturut-turut ke arah yang sama)
        if (steps.size >= 3) {
            val last3 = steps.takeLast(3)
            val allScroll = last3.all { it.action == AgentActionType.SCROLL }
            if (allScroll && last3.map { it.targetLabel }.distinct().size == 1) {
                return "PERINGATAN SCROLL BERLEBIHAN: Sudah melakukan ${last.targetLabel} sebanyak 3 kali berturut-turut tanpa mencapai target. " +
                    "Gunakan fitur Pencarian (Search/Cari) pada bagian atas aplikasi atau ketuk item yang paling mendekati daripada terus menggulir."
            }
        }

        // 5. Deteksi Stagnasi Tanda Tangan Layar (>= 3 langkah layar sama sekali tidak berubah)
        if (steps.size >= 3) {
            val last3 = steps.takeLast(3)
            val allStagnant = last3.all { it.screenSignatureBefore == it.screenSignatureAfter }
            if (allStagnant) {
                return "PERINGATAN LAYAR MANDET (STAGNATION): Tanda tangan layar tidak berubah setelah 3 langkah berturut-turut. " +
                    "Elemen yang dicoba tidak responsif. Tekan BACK untuk memulihkan layar atau gunakan jalur alternatif."
            }
        }

        // 6. Peringatan Langkah Tunggal INEFFECTIVE
        if (last.outcomeStatus == ActionOutcome.INEFFECTIVE) {
            return "PERHATIAN: Langkah terakhir (${last.action.wireName} pada " +
                "\"${last.targetLabel}\") berstatus INEFFECTIVE (layar tidak berubah). " +
                "Evaluasi ulang target sebelum mengulangi aksi."
        }

        return null
    }

    override suspend fun getAppKnowledgeHint(packageName: String): String? {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty()) {
            return null
        }

        if (dao != null) {
            try {
                val rows = dao.getTopKnowledgeForPackage(cleanPkg)
                if (rows.isNotEmpty()) {
                    val hints = rows.joinToString("\n") { row ->
                        "- Untuk \"${row.subgoalIntent}\": gunakan elemen \"${row.targetResourceId}\" (${row.actionType})"
                    }
                    return "Riwayat pola sukses pada $cleanPkg:\n$hints"
                }
            } catch (_: Throwable) {
                // Lanjut ke fallback in-memory.
            }
        }

        val cached = inMemoryPlaybookCache.values
            .filter { it.packageName == cleanPkg && it.failureCount == 0 && !it.isDisabled }
            .sortedByDescending { it.successCount }
        if (cached.isEmpty()) {
            return null
        }
        val hints = cached.take(5).joinToString("\n") { item ->
            "- Untuk \"${item.subgoalIntent}\": gunakan elemen \"${item.targetResourceId}\" (${item.actionType})"
        }
        return "Riwayat pola sukses pada $cleanPkg:\n$hints"
    }

    @Synchronized
    override fun stageCandidateTransition(
        packageName: String,
        intentGoal: String,
        resourceId: String,
        screenSignatureHash: String,
        actionType: String
    ) {
        val cleanPkg = packageName.trim()
        val cleanRes = resourceId.trim()
        if (cleanPkg.isEmpty() || cleanRes.isEmpty()) return
        val intentBucket = normalizeIntentVerb(intentGoal, cleanPkg)
        val cacheKey = "$cleanPkg|$screenSignatureHash|$intentBucket|$cleanRes|$actionType"
        stagedTransitions[cacheKey] = PlaybookTransition(
            packageName = cleanPkg,
            screenSignatureHash = screenSignatureHash,
            subgoalIntent = intentBucket,
            targetResourceId = cleanRes,
            actionType = actionType,
            successCount = 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    @Synchronized
    override fun discardStagedTransitions() {
        stagedTransitions.clear()
    }

    override suspend fun commitStagedTransitions(sessionId: String) {
        val snapshot: List<PlaybookTransition> = synchronized(this) {
            val list = stagedTransitions.values.toList()
            stagedTransitions.clear()
            list
        }
        for (item in snapshot) {
            persistVerifiedTransition(
                packageName = item.packageName,
                intentBucket = item.subgoalIntent,
                resourceId = item.targetResourceId,
                screenSignatureHash = item.screenSignatureHash,
                actionType = item.actionType,
                sessionId = sessionId
            )
        }
    }

    override suspend fun saveSuccessfulTransition(
        packageName: String,
        intentGoal: String,
        resourceId: String,
        screenSignatureHash: String,
        actionType: String
    ) {
        val cleanPkg = packageName.trim()
        val cleanRes = resourceId.trim()
        if (cleanPkg.isEmpty() || cleanRes.isEmpty()) return
        val intentBucket = normalizeIntentVerb(intentGoal, cleanPkg)
        persistVerifiedTransition(
            packageName = cleanPkg,
            intentBucket = intentBucket,
            resourceId = cleanRes,
            screenSignatureHash = screenSignatureHash,
            actionType = actionType,
            sessionId = "direct"
        )
    }

    private suspend fun persistVerifiedTransition(
        packageName: String,
        intentBucket: String,
        resourceId: String,
        screenSignatureHash: String,
        actionType: String,
        sessionId: String
    ) {
        val nowMs = System.currentTimeMillis()
        val cacheKey = "$packageName|$screenSignatureHash|$intentBucket|$resourceId|$actionType"
        val prevMem = inMemoryPlaybookCache[cacheKey]
        val assignedId = prevMem?.id ?: synchronized(this) { nextInMemoryPlaybookId++ }
        val updatedCount = if (prevMem != null && !prevMem.isDisabled) prevMem.successCount + 1 else 1
        inMemoryPlaybookCache[cacheKey] = PlaybookTransition(
            id = assignedId,
            packageName = packageName,
            screenSignatureHash = screenSignatureHash,
            subgoalIntent = intentBucket,
            targetResourceId = resourceId,
            actionType = actionType,
            successCount = updatedCount,
            failureCount = 0,
            lastSessionId = sessionId,
            updatedAt = nowMs,
            isDisabled = false
        )

        if (dao != null) {
            try {
                val existing = dao.findKnowledgeEntry(
                    packageName = packageName,
                    screenSignatureHash = screenSignatureHash,
                    subgoalIntent = intentBucket,
                    targetResourceId = resourceId,
                    actionType = actionType
                )
                if (existing != null) {
                    val nextSuccess = if (existing.isDisabled) 1 else existing.successCount + 1
                    dao.upsertKnowledgeEntry(
                        existing.copy(
                            successCount = nextSuccess,
                            failureCount = 0,
                            lastSessionId = sessionId,
                            updatedAt = nowMs,
                            isDisabled = false
                        )
                    )
                } else {
                    dao.upsertKnowledgeEntry(
                        AppKnowledgeGraphEntity(
                            packageName = packageName,
                            screenSignatureHash = screenSignatureHash,
                            subgoalIntent = intentBucket,
                            targetResourceId = resourceId,
                            actionType = actionType,
                            successCount = 1,
                            failureCount = 0,
                            lastSessionId = sessionId,
                            updatedAt = nowMs,
                            isDisabled = false
                        )
                    )
                }
            } catch (_: Throwable) {
                // Fallback in-memory tetap terjaga.
            }
        }
    }

    override suspend fun penalizePlaybookTransition(
        packageName: String,
        screenSignatureHash: String,
        intentGoal: String,
        targetResourceId: String,
        actionType: String
    ) {
        val cleanPkg = packageName.trim()
        val cleanRes = targetResourceId.trim()
        if (cleanPkg.isEmpty() || cleanRes.isEmpty()) return
        val intentBucket = normalizeIntentVerb(intentGoal, cleanPkg)
        val nowMs = System.currentTimeMillis()
        val cacheKey = "$cleanPkg|$screenSignatureHash|$intentBucket|$cleanRes|$actionType"
        inMemoryPlaybookCache[cacheKey]?.let { prev ->
            inMemoryPlaybookCache[cacheKey] = prev.copy(
                failureCount = prev.failureCount + 1,
                updatedAt = nowMs,
                isDisabled = true
            )
        }
        if (dao != null) {
            try {
                val existing = dao.findKnowledgeEntry(
                    packageName = cleanPkg,
                    screenSignatureHash = screenSignatureHash,
                    subgoalIntent = intentBucket,
                    targetResourceId = cleanRes,
                    actionType = actionType
                )
                if (existing != null) {
                    dao.upsertKnowledgeEntry(
                        existing.copy(
                            failureCount = existing.failureCount + 1,
                            updatedAt = nowMs,
                            isDisabled = true
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun rollbackSessionMemory(sessionId: String) {
        val cleanSession = sessionId.trim()
        if (cleanSession.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        for ((key, entry) in inMemoryPlaybookCache) {
            if (entry.lastSessionId == cleanSession) {
                inMemoryPlaybookCache[key] = entry.copy(
                    failureCount = entry.failureCount + 1,
                    updatedAt = nowMs,
                    isDisabled = true
                )
            }
        }
        if (dao != null) {
            try {
                dao.disableSessionKnowledge(cleanSession, nowMs)
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun getAllPlaybookEntries(): List<PlaybookTransition> {
        if (dao != null) {
            try {
                return dao.getAllKnowledgeEntries().map { it.toDomainModel() }
            } catch (_: Throwable) {
            }
        }
        return inMemoryPlaybookCache.values.sortedByDescending { it.updatedAt }
    }

    override suspend fun deletePlaybookEntry(id: Long) {
        val keysToRemove = inMemoryPlaybookCache.entries
            .filter { it.value.id == id }
            .map { it.key }
        for (k in keysToRemove) {
            inMemoryPlaybookCache.remove(k)
        }
        if (dao != null) {
            try {
                dao.deleteKnowledgeById(id)
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun clearAllPlaybookMemory() {
        inMemoryPlaybookCache.clear()
        synchronized(this) {
            stagedTransitions.clear()
        }
        if (dao != null) {
            try {
                dao.deleteAllKnowledge()
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun findPlaybookTransition(
        packageName: String,
        screenSignatureHash: String,
        intentGoal: String,
        minSuccessCount: Int
    ): PlaybookTransition? {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || screenSignatureHash.isEmpty()) {
            return null
        }
        val intentBucket = normalizeIntentVerb(intentGoal, cleanPkg)

        if (dao != null) {
            try {
                val dbMatch = dao.findBestPlaybookTransition(
                    packageName = cleanPkg,
                    screenSignatureHash = screenSignatureHash,
                    subgoalIntent = intentBucket,
                    minSuccessCount = minSuccessCount
                )
                if (dbMatch != null) {
                    return dbMatch.toDomainModel()
                }
            } catch (_: Throwable) {
                // Lanjut ke fallback in-memory.
            }
        }

        return inMemoryPlaybookCache.values
            .filter {
                it.packageName == cleanPkg &&
                    it.screenSignatureHash == screenSignatureHash &&
                    it.subgoalIntent == intentBucket &&
                    it.successCount >= minSuccessCount &&
                    it.failureCount == 0 &&
                    !it.isDisabled
            }
            .maxByOrNull { it.successCount }
    }

    override suspend fun recordContactPreference(
        queryName: String,
        packageName: String,
        resolvedDisplayName: String
    ) {
        val normQuery = queryName.trim().lowercase()
        val normPkg = packageName.trim().lowercase()
        val cleanResolved = resolvedDisplayName.trim()
        if (normQuery.isEmpty() || cleanResolved.isEmpty()) {
            return
        }

        val bucketKey = "$normPkg|$normQuery"
        val bucket = inMemoryContactRank.getOrPut(bucketKey) { ConcurrentHashMap() }
        bucket[cleanResolved] = (bucket[cleanResolved] ?: 0) + 1

        if (dao != null) {
            try {
                val existing = dao.findContactPreference(normQuery, normPkg, cleanResolved)
                val nextCount = (existing?.selectionCount ?: 0) + 1
                dao.upsertContactPreference(
                    ContactDisambiguationEntity(
                        queryName = normQuery,
                        packageName = normPkg,
                        resolvedDisplayName = cleanResolved,
                        selectionCount = nextCount
                    )
                )
            } catch (_: Throwable) {
                // Fallback in-memory tetap terjaga.
            }
        }
    }

    override suspend fun rankDisambiguationOptions(
        queryName: String,
        packageName: String,
        options: List<String>
    ): List<String> {
        if (options.size <= 1) {
            return options
        }
        val normQuery = queryName.trim().lowercase()
        val normPkg = packageName.trim().lowercase()
        val scores = HashMap<String, Int>()
        inMemoryContactRank["$normPkg|$normQuery"]?.forEach { (k, v) ->
            scores[k] = v
        }

        if (dao != null) {
            try {
                val rows = dao.getContactPreferencesForQuery(normQuery, normPkg)
                for (row in rows) {
                    if (row.resolvedDisplayName.isNotEmpty()) {
                        scores[row.resolvedDisplayName] = row.selectionCount
                    }
                }
            } catch (_: Throwable) {
                // Gunakan skor in-memory.
            }
        }

        return options.sortedByDescending { scores[it] ?: 0 }
    }

    override suspend fun logTelemetry(entry: TelemetryEntry) {
        synchronized(inMemoryTelemetryFallback) {
            inMemoryTelemetryFallback.add(0, entry)
            if (inMemoryTelemetryFallback.size > 200) {
                inMemoryTelemetryFallback.removeAt(inMemoryTelemetryFallback.lastIndex)
            }
            _telemetryFlow.value = inMemoryTelemetryFallback.toList()
        }

        if (dao != null) {
            try {
                dao.insertTelemetryLog(TelemetryLogEntity.fromDomainModel(entry))
            } catch (_: Throwable) {
                // Fallback in-memory sudah terisi.
            }
        }
    }

    override suspend fun getRecentTelemetryLogs(limit: Int): List<TelemetryEntry> {
        if (dao != null) {
            try {
                val rows = dao.getRecentTelemetryLogs(limit)
                if (rows.isNotEmpty()) {
                    val mapped = rows.map { it.toDomainModel() }
                    _telemetryFlow.value = mapped
                    return mapped
                }
            } catch (_: Throwable) {
                // Gunakan fallback in-memory.
            }
        }
        return synchronized(inMemoryTelemetryFallback) {
            inMemoryTelemetryFallback.take(limit)
        }
    }

    override suspend fun lookupAlias(alias: String): String? {
        val clean = alias.trim().lowercase()
        if (clean.isEmpty()) return null
        if (dao != null) {
            try {
                val dbPkg = dao.lookupPackageAlias(clean)
                if (!dbPkg.isNullOrEmpty()) {
                    inMemoryAliasCache[clean] = dbPkg
                    return dbPkg
                }
            } catch (_: Throwable) {
            }
        }
        return inMemoryAliasCache[clean]
    }

    override suspend fun saveAlias(alias: String, packageName: String) {
        val cleanAlias = alias.trim().lowercase()
        val cleanPkg = packageName.trim()
        if (cleanAlias.isEmpty() || cleanPkg.isEmpty()) return
        inMemoryAliasCache[cleanAlias] = cleanPkg
        if (dao != null) {
            try {
                dao.upsertPackageAlias(
                    AppPackageCacheEntity(
                        aliasName = cleanAlias,
                        packageName = cleanPkg,
                        lastLaunchedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun recordEpisodicRecoveryLesson(
        packageName: String,
        goal: String,
        steps: List<StepRecord>
    ) {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || steps.size < 2) return

        // Cari pola pemulihan: langkah gagal (INEFFECTIVE) yang langsung diikuti langkah sukses (CORRECT)
        var distilledRule: String? = null
        for (i in 0 until steps.size - 1) {
            val failed = steps[i]
            val recovered = steps[i + 1]
            if (failed.outcomeStatus == ActionOutcome.INEFFECTIVE &&
                recovered.outcomeStatus == ActionOutcome.CORRECT
            ) {
                val badTarget = failed.targetLabel.take(28).ifEmpty { "elemen pasif" }
                val goodTarget = recovered.targetLabel.take(28).ifEmpty { recovered.action.wireName }
                distilledRule = "Hindari ${failed.action.wireName} pada '$badTarget'; gunakan ${recovered.action.wireName} pada '$goodTarget'."
                break
            }
        }
        val finalRule = distilledRule ?: return
        val goalBucket = normalizeIntentVerb(goal, cleanPkg)
        val nowMs = System.currentTimeMillis()

        // Simpan ke cache in-memory
        val pkgList = inMemoryEpisodicCache.getOrPut(cleanPkg) { mutableListOf() }
        synchronized(pkgList) {
            val idx = pkgList.indexOfFirst { it.lessonRule == finalRule }
            if (idx >= 0) {
                val cur = pkgList[idx]
                pkgList[idx] = cur.copy(hitCount = cur.hitCount + 1, updatedAt = nowMs)
            } else {
                pkgList.add(
                    EpisodicLessonEntity(
                        packageName = cleanPkg,
                        goalPattern = goalBucket,
                        lessonRule = finalRule,
                        hitCount = 1,
                        updatedAt = nowMs
                    )
                )
            }
        }

        if (dao != null) {
            try {
                val existing = dao.findEpisodicLesson(cleanPkg, finalRule)
                if (existing != null) {
                    dao.upsertEpisodicLesson(
                        existing.copy(hitCount = existing.hitCount + 1, updatedAt = nowMs)
                    )
                } else {
                    dao.upsertEpisodicLesson(
                        EpisodicLessonEntity(
                            packageName = cleanPkg,
                            goalPattern = goalBucket,
                            lessonRule = finalRule,
                            hitCount = 1,
                            updatedAt = nowMs
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun getEpisodicLessonsHint(packageName: String): String? {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty()) return null

        if (dao != null) {
            try {
                val rows = dao.getEpisodicLessonsForPackage(cleanPkg, limit = 3)
                if (rows.isNotEmpty()) {
                    return "PELAJARAN EPISODIK MANDIRI ($cleanPkg):\n" +
                        rows.joinToString("\n") { "- ${it.lessonRule}" }
                }
            } catch (_: Throwable) {
            }
        }

        val memRows = inMemoryEpisodicCache[cleanPkg]?.let { list ->
            synchronized(list) {
                list.sortedWith(compareByDescending<EpisodicLessonEntity> { it.hitCount }.thenByDescending { it.updatedAt })
                    .take(3)
            }
        }.orEmpty()
        if (memRows.isEmpty()) return null
        return "PELAJARAN EPISODIK MANDIRI ($cleanPkg):\n" +
            memRows.joinToString("\n") { "- ${it.lessonRule}" }
    }

    override suspend fun warmEdgeRisksForScreen(
        packageName: String,
        screenSignatureHash: String
    ) {
        val cleanPkg = packageName.trim()
        val cleanHash = screenSignatureHash.trim()
        if (dao == null || sewmCachePreloaded) {
            return
        }
        sewmWriteMutex.withLock {
            if (!sewmCachePreloaded) {
                try {
                    val topRules = dao.getTopEdgeRules(limit = maxSewmRulesCap)
                    for (entity in topRules) {
                        val rule = entity.toDomainModel()
                        val sKey = "${rule.packageName}|${rule.screenSignatureHash}"
                        val bucket = inMemorySewmByScreen.getOrPut(sKey) { ConcurrentHashMap() }
                        bucket["${rule.edgeKey}|${rule.actionType}"] = rule
                    }
                    sewmCachePreloaded = true
                } catch (_: Throwable) {
                    // Tetap aman: jika query gagal, fallback RAM tetap bekerja
                }
            }
            if (cleanPkg.isNotEmpty() && cleanHash.isNotEmpty()) {
                val screenKey = "$cleanPkg|$cleanHash"
                if (!inMemorySewmByScreen.containsKey(screenKey)) {
                    try {
                        val screenRows = dao.getEdgeRulesForScreen(cleanPkg, cleanHash)
                        if (screenRows.isNotEmpty()) {
                            val bucket = inMemorySewmByScreen.getOrPut(screenKey) { ConcurrentHashMap() }
                            for (entity in screenRows) {
                                val rule = entity.toDomainModel()
                                bucket["${rule.edgeKey}|${rule.actionType}"] = rule
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun predictEdgeRisksForScreenFast(
        packageName: String,
        screenSignatureHash: String
    ): List<EdgeRiskRule> {
        val cleanPkg = packageName.trim()
        val cleanHash = screenSignatureHash.trim()
        if (cleanPkg.isEmpty() || cleanHash.isEmpty()) return emptyList()
        val bucket = inMemorySewmByScreen["$cleanPkg|$cleanHash"] ?: return emptyList()
        return bucket.values.toList()
    }

    override suspend fun observeEdgeTransition(
        packageName: String,
        screenSignatureHash: String,
        edgeKey: String,
        actionType: String,
        targetLabelSample: String,
        outcome: PredictedEdgeOutcome,
        mitigationHint: String
    ) {
        val cleanPkg = packageName.trim()
        val cleanHash = screenSignatureHash.trim()
        val cleanEdgeKey = edgeKey.trim().take(SymbolicEdgeWorldModel.MAX_EDGE_KEY_LEN)
        val cleanAction = actionType.trim().uppercase()
        if (cleanPkg.isEmpty() || cleanHash.isEmpty() || cleanEdgeKey.isEmpty() || cleanAction.isEmpty()) {
            return
        }
        val cleanLabel = SymbolicEdgeWorldModel.normalizeLabel(targetLabelSample)
        val nowMs = System.currentTimeMillis()
        val screenKey = "$cleanPkg|$cleanHash"
        val subKey = "$cleanEdgeKey|$cleanAction"

        sewmWriteMutex.withLock {
            val existingMem = inMemorySewmByScreen[screenKey]?.get(subKey)
            val existingDb = if (existingMem == null && dao != null) {
                try {
                    dao.findEdgeRule(cleanPkg, cleanHash, cleanEdgeKey, cleanAction)?.toDomainModel()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            val baseRule = existingMem ?: existingDb

            val nextSafe = (baseRule?.safeCount ?: 0) + if (outcome == PredictedEdgeOutcome.SAFE_PROGRESS) 1 else 0
            val nextPaywall = (baseRule?.paywallCount ?: 0) + if (outcome == PredictedEdgeOutcome.PAYWALL) 1 else 0
            val nextAd = (baseRule?.adPopupCount ?: 0) + if (outcome == PredictedEdgeOutcome.AD_POPUP) 1 else 0
            val nextPerm = (baseRule?.permissionModalCount ?: 0) + if (outcome == PredictedEdgeOutcome.PERMISSION_MODAL) 1 else 0
            val nextErr = (baseRule?.errorCount ?: 0) + if (outcome == PredictedEdgeOutcome.ERROR_DIALOG) 1 else 0
            val nextIneff = (baseRule?.ineffectiveCount ?: 0) + if (outcome == PredictedEdgeOutcome.INEFFECTIVE_DEADEND) 1 else 0

            val (dominant, riskScore) = SymbolicEdgeWorldModel.computeRiskScoreAndDominantOutcome(
                safeCount = nextSafe,
                paywallCount = nextPaywall,
                adPopupCount = nextAd,
                permissionModalCount = nextPerm,
                errorCount = nextErr,
                ineffectiveCount = nextIneff
            )

            val resolvedHint = mitigationHint.trim()
                .ifEmpty { SymbolicEdgeWorldModel.defaultMitigationHint(dominant, cleanLabel) }
                .take(SymbolicEdgeWorldModel.MAX_MITIGATION_HINT_LEN)

            val resolvedLabelSample = cleanLabel.ifEmpty { baseRule?.targetLabelSample.orEmpty() }
                .take(SymbolicEdgeWorldModel.MAX_LABEL_SAMPLE_LEN)

            var assignedId = baseRule?.id ?: 0L
            if (dao != null) {
                try {
                    val rowId = dao.upsertEdgeRule(
                        UiTransitionRuleEntity(
                            id = assignedId,
                            packageName = cleanPkg,
                            screenSignatureHash = cleanHash,
                            edgeKey = cleanEdgeKey,
                            actionType = cleanAction,
                            targetLabelSample = resolvedLabelSample,
                            dominantOutcome = dominant.wireName,
                            safeCount = nextSafe,
                            paywallCount = nextPaywall,
                            adPopupCount = nextAd,
                            permissionModalCount = nextPerm,
                            errorCount = nextErr,
                            ineffectiveCount = nextIneff,
                            riskScore = riskScore,
                            mitigationHint = resolvedHint,
                            updatedAtMs = nowMs
                        )
                    )
                    if (assignedId == 0L && rowId > 0L) {
                        assignedId = rowId
                    }
                    if (dao.countEdgeRules() > maxSewmRulesCap) {
                        dao.pruneExcessEdgeRules(keepCount = maxSewmRulesCap)
                    }
                } catch (_: Throwable) {
                }
            } else if (assignedId == 0L) {
                assignedId = nextInMemoryEdgeRuleId++
            }

            val updatedRule = EdgeRiskRule(
                id = assignedId,
                packageName = cleanPkg,
                screenSignatureHash = cleanHash,
                edgeKey = cleanEdgeKey,
                actionType = cleanAction,
                targetLabelSample = resolvedLabelSample,
                dominantOutcome = dominant,
                safeCount = nextSafe,
                paywallCount = nextPaywall,
                adPopupCount = nextAd,
                permissionModalCount = nextPerm,
                errorCount = nextErr,
                ineffectiveCount = nextIneff,
                riskScore = riskScore,
                mitigationHint = resolvedHint,
                updatedAtMs = nowMs
            )

            val bucket = inMemorySewmByScreen.getOrPut(screenKey) { ConcurrentHashMap() }
            bucket[subKey] = updatedRule
            enforceInMemorySewmCapLocked()
        }
    }

    private fun enforceInMemorySewmCapLocked() {
        var total = 0
        for (bucket in inMemorySewmByScreen.values) {
            total += bucket.size
        }
        if (total <= maxSewmRulesCap) return

        val allEntries = mutableListOf<Triple<String, String, EdgeRiskRule>>()
        for ((sKey, bucket) in inMemorySewmByScreen) {
            for ((rKey, rule) in bucket) {
                allEntries.add(Triple(sKey, rKey, rule))
            }
        }
        // Pertahankan aturan berisiko tertinggi dan paling baru; pangkas sisanya hingga <= maxSewmRulesCap
        val toEvict = allEntries
            .sortedWith(
                compareBy<Triple<String, String, EdgeRiskRule>> { it.third.riskScore }
                    .thenBy { it.third.updatedAtMs }
            )
            .take(total - maxSewmRulesCap)

        for ((sKey, rKey, _) in toEvict) {
            val bucket = inMemorySewmByScreen[sKey] ?: continue
            bucket.remove(rKey)
            if (bucket.isEmpty()) {
                inMemorySewmByScreen.remove(sKey)
            }
        }
    }

    override suspend fun getEdgeWorldModelStats(): EdgeWorldModelStats {
        var ramCount = 0
        var highRiskCount = 0
        for (bucket in inMemorySewmByScreen.values) {
            ramCount += bucket.size
            highRiskCount += bucket.values.count {
                it.dominantOutcome.isAdverse && it.riskScore >= SymbolicEdgeWorldModel.PROMPT_WARNING_THRESHOLD
            }
        }
        val dbCount = if (dao != null) {
            try {
                dao.countEdgeRules()
            } catch (_: Throwable) {
                ramCount
            }
        } else {
            ramCount
        }
        val basisCount = maxOf(ramCount, dbCount)
        return EdgeWorldModelStats(
            totalRulesInRam = ramCount,
            totalRulesInDb = dbCount,
            highRiskRuleCount = highRiskCount,
            maxDbRulesCap = maxSewmRulesCap,
            estimatedSqliteBytes = basisCount.toLong() * SymbolicEdgeWorldModel.ESTIMATED_BYTES_PER_ROW
        )
    }

    override suspend fun clearAllEdgeWorldModelRules() {
        sewmWriteMutex.withLock {
            inMemorySewmByScreen.clear()
            sewmCachePreloaded = false
            if (dao != null) {
                try {
                    dao.deleteAllEdgeRules()
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        private val waWordRegex = Regex("\\bwa\\b")
        private val igWordRegex = Regex("\\b(instagram|ig|insta|dm)\\b")
        private val nonAlnumSpaceRegex = Regex("[^a-z0-9\\s]")
        private val whitespaceRegex = Regex("\\s+")

        fun normalizeIntentVerb(intentGoal: String, packageName: String = ""): String {
            val lower = intentGoal.trim().lowercase()
            val pkgLower = packageName.trim().lowercase()
            val isIg = pkgLower.contains("instagram") || igWordRegex.containsMatchIn(lower)
            val isWa = (pkgLower.contains("whatsapp") || lower.contains("whatsapp") || waWordRegex.containsMatchIn(lower)) && !isIg

            if (isIg) {
                val wantsDm = lower.contains("dm") || lower.contains("pesan") || lower.contains("chat") || lower.contains("kirim")
                return if (wantsDm) "instagram_send_dm" else "instagram_search_profile"
            }
            if (isWa || (lower.contains("kirim pesan") && pkgLower.isEmpty())) {
                return "whatsapp_send_message"
            }
            if (pkgLower.contains("youtube") || lower.contains("youtube") || lower.contains(" yt ")) {
                return if (lower.contains("putar") || lower.contains("tonton") || lower.contains("play")) {
                    "youtube_play_video"
                } else {
                    "youtube_search"
                }
            }
            if (pkgLower.contains("trill") || pkgLower.contains("musically") || lower.contains("tiktok")) {
                return "tiktok_search_video"
            }
            if (pkgLower.contains("shopee") || lower.contains("shopee")) {
                return "shopee_search_item"
            }
            if (lower.contains("kalender") ||
                lower.contains("calendar") ||
                lower.contains("jadwal") ||
                lower.contains("rapat")
            ) {
                return "calendar_schedule"
            }
            val words = lower
                .replace(nonAlnumSpaceRegex, " ")
                .split(whitespaceRegex)
                .filter { it.isNotEmpty() }
                .take(4)
                .joinToString("_")
            return words.ifEmpty { "generic_goal" }
        }
    }
}
