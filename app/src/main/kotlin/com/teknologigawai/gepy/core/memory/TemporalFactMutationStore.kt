package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.data.database.GepyDao
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Penyimpanan mutasi & cache graf temporal (Room + RAM cache + cermin legacy) serta orkestrasi pembersihan aman.
 */
internal class TemporalFactMutationStore(
    private val gepyDao: GepyDao?,
    private val clockMsProvider: () -> Long,
    private val scope: CoroutineScope,
    private val vectorEngine: HybridVectorMemoryEngine,
    private val factExtractor: ConversationalFactExtractor,
    private val readRawLocalEntriesUnsafe: (Long) -> List<UserMemoryEntry>,
    private val getLocalEntries: () -> List<UserMemoryEntry>,
    private val persistEntries: (List<UserMemoryEntry>) -> Unit,
    private val onProfileNameUpdated: (String) -> Unit,
    private val clearPrefs: () -> Unit,
    private val syncToCloudIfAuthenticated: (String, String) -> Unit,
    private val notifyMemoryChanged: () -> Unit
) {
    internal val temporalLock = Any()
    internal val temporalFactsCache = mutableListOf<TemporalMemoryFactEntity>()
    private var nextInMemoryFactId = 1L

    private val temporalInitMutex = Mutex()
    private val clearJobLock = Any()

    @Volatile
    private var activeClearJob: Job? = null

    @Volatile
    internal var isTemporalInitialized = false
        private set

    internal fun isClearJobActive(): Boolean = synchronized(clearJobLock) { activeClearJob != null }

    internal fun allocateInMemoryFactId(): Long = synchronized(temporalLock) { nextInMemoryFactId++ }

    internal suspend fun awaitPendingClearJob() {
        while (true) {
            val job = synchronized(clearJobLock) { activeClearJob } ?: break
            runCatching { job.join() }
            if (!job.isActive) {
                synchronized(clearJobLock) {
                    if (activeClearJob === job) {
                        activeClearJob = null
                    }
                }
            }
        }
    }

    /**
     * Memuat graf memori temporal & indeks vektor Layer 2 dari Room `v5`, sekaligus melakukan
     * **Legacy Backfill 1x** dari `SharedPreferences` lama agar seluruh ingatan sebelumnya terpelihara rapi.
     */
    suspend fun initializeTemporalStore() {
        while (true) {
            awaitPendingClearJob()
            var abortedForPendingClear = false
            temporalInitMutex.withLock {
                if (synchronized(clearJobLock) { activeClearJob != null }) {
                    abortedForPendingClear = true
                    return@withLock
                }
                if (isTemporalInitialized) return
                val nowMs = clockMsProvider()
                val dao = gepyDao
                if (dao != null) {
                    try {
                        dao.expireOutdatedTemporalFacts(nowMs)
                        if (dao.countTemporalFacts() == 0) {
                            val legacyEntries = readRawLocalEntriesUnsafe(nowMs)
                            legacyEntries.forEach { entry ->
                                val triple = TemporalKnowledgeGraph.extractTriple(
                                    factText = entry.fact,
                                    rawCategory = entry.category,
                                    rawSubject = entry.subject,
                                    nowMs = entry.updatedAt
                                )
                                val entity = TemporalMemoryFactEntity(
                                    category = triple.category,
                                    subject = triple.subject,
                                    predicate = triple.predicate,
                                    objectValue = triple.objectValue,
                                    factText = entry.fact,
                                    confidence = 1.0f,
                                    validFromMs = entry.updatedAt,
                                    validUntilMs = entry.validUntilMs ?: triple.validUntilMs,
                                    isActive = true,
                                    source = "legacy_backfill",
                                    createdAtMs = entry.updatedAt,
                                    updatedAtMs = entry.updatedAt
                                )
                                dao.insertTemporalFact(entity)
                            }
                            if (legacyEntries.isNotEmpty()) {
                                GepyLog.i(UserMemoryManager.TAG, "Legacy Backfill 1x selesai: ${legacyEntries.size} fakta dipindahkan ke Room v5.")
                            }
                        }
                        val loaded = dao.getAllTemporalFacts(limit = UserMemoryManager.MAX_TEMPORAL_RAM_FACTS)
                        synchronized(temporalLock) {
                            temporalFactsCache.clear()
                            temporalFactsCache.addAll(loaded)
                        }

                        val loadedVectors = dao.getRecentMemoryVectors(limit = UserMemoryManager.MAX_VECTOR_RAM_CACHE)
                        vectorEngine.replaceCacheFromEntities(loadedVectors)

                        // Backfill bertahap Layer 2 memakai deduplikasi hash DB penuh + RAM & chunk(10) agar kuota API tetap terjaga
                        if (vectorEngine.isBackfillReady(nowMs)) {
                            val dbHashes = runCatching { dao.getAllVectorContentHashes() }.getOrNull()?.toHashSet() ?: emptySet()
                            vectorEngine.triggerBackfillIfEligible(loaded, nowMs, dbHashes)
                        }
                    } catch (t: Throwable) {
                        GepyLog.w(UserMemoryManager.TAG, "Gagal menginisialisasi Temporal & Vector Room store: ${t.message}")
                    }
                }
                isTemporalInitialized = true
                return
            }
            if (!abortedForPendingClear) break
        }
    }

    fun recordMemoryAsync(
        category: String,
        fact: String,
        subject: String = "",
        predicate: String = "",
        objectValue: String = "",
        validHours: Int? = null,
        source: String = "voice"
    ) = recordMemory(category, fact, subject, predicate, objectValue, validHours, source)

    /**
     * Menyimpan fakta baru ke memori lokal HP & **Temporal Knowledge Graph (Room `v4`)**:
     * - Mengekstrak triplet `(subject, predicate, object_value)` dan batas kedaluwarsa otomatis.
     * - Melakukan resolusi kontradiksi lunak (*soft contradiction*) agar fakta historis tetap tersimpan.
     * - Hanya menyinkronkan ke cloud apabila pengguna memilih masuk Google secara opsional.
     */
    fun recordMemory(
        category: String,
        fact: String,
        subject: String = "",
        predicate: String = "",
        objectValue: String = "",
        validHours: Int? = null,
        source: String = "voice"
    ) {
        val cleanFact = fact.trim()
        if (cleanFact.length < 3) return

        // SEMANTIC MEMORY FILTER: cegah derau ujaran kasual/idiom jika bersumber dari transkrip percakapan
        if (source.startsWith("transcript") || source == "voice") {
            if (!SemanticMemoryFilter.isMeaningfulPersonalFact(category, cleanFact, objectValue)) {
                GepyLog.w(UserMemoryManager.TAG, "Fakta ditolak oleh SemanticMemoryFilter: $cleanFact (kat=$category)")
                return
            }
        }

        val nowMs = clockMsProvider()
        val triple = TemporalKnowledgeGraph.extractTriple(
            factText = cleanFact,
            rawCategory = category,
            rawSubject = subject,
            rawPredicate = predicate,
            rawObjectValue = objectValue,
            validHours = validHours,
            nowMs = nowMs
        )
        val normalizedCategory = triple.category
        val effectiveSubject = triple.subject
        val isStrictSingle = factExtractor.isSingleEntitySubject(effectiveSubject) ||
            (triple.isSingleValued && factExtractor.isSingleEntitySubject(triple.subject))

        // 1. Perbarui cermin cepat SharedPreferences (fakta aktif)
        val current = getLocalEntries().toMutableList()
        if (effectiveSubject.isNotBlank() && isStrictSingle) {
            val removedCount = current.removeAll { it.subject.equals(effectiveSubject, ignoreCase = true) }
            if (removedCount) {
                GepyLog.i(UserMemoryManager.TAG, "Memperbarui cermin aktif untuk entitas tunggal: $effectiveSubject")
            }
        }
        if (effectiveSubject == "family_siblings" && (cleanFact.contains("ada ") || cleanFact.contains("yaitu ") || cleanFact.contains(","))) {
            current.removeAll { it.subject == "family_siblings" || (it.category == "family" && it.fact.contains("adik", ignoreCase = true)) }
        }
        current.removeAll { it.fact.equals(cleanFact, ignoreCase = true) }

        val newEntry = UserMemoryEntry(
            category = normalizedCategory,
            subject = effectiveSubject,
            fact = cleanFact,
            updatedAt = nowMs,
            validUntilMs = triple.validUntilMs
        )
        current.add(0, newEntry)
        persistEntries(current)

        // 2. Perbarui Graf Memori Temporal in-memory cache seketika (< 1 ms)
        val dao = gepyDao
        val insertedId = synchronized(temporalLock) {
            if (dao != null) -nextInMemoryFactId++ else nextInMemoryFactId++
        }

        val tempEntity = TemporalMemoryFactEntity(
            id = insertedId,
            category = triple.category,
            subject = triple.subject,
            predicate = triple.predicate,
            objectValue = triple.objectValue,
            factText = cleanFact,
            confidence = 1.0f,
            validFromMs = nowMs,
            validUntilMs = triple.validUntilMs,
            isActive = true,
            source = source,
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        )
        val computedImportance = EbbinghausDecayEngine.computeImportanceScore(tempEntity)

        val supersededOldIds = mutableListOf<Long>()
        synchronized(temporalLock) {
            // Tandai kedaluwarsa pada cache in-memory
            for (idx in temporalFactsCache.indices) {
                val existing = temporalFactsCache[idx]
                if (existing.isActive && existing.validUntilMs != null && existing.validUntilMs <= nowMs) {
                    temporalFactsCache[idx] = existing.copy(isActive = false, updatedAtMs = nowMs)
                }
            }

            // Resolusi kontradiksi lunak: tandai fakta aktif lama pada entitas TUNGGAL sebagai historis
            if (isStrictSingle) {
                for (idx in temporalFactsCache.indices) {
                    val existing = temporalFactsCache[idx]
                    val sameTriple = existing.isActive && (
                        (existing.subject.equals(triple.subject, ignoreCase = true) &&
                            existing.predicate.equals(triple.predicate, ignoreCase = true)) ||
                            (factExtractor.isSingleEntitySubject(effectiveSubject) && existing.subject.equals(effectiveSubject, ignoreCase = true))
                        )
                    if (sameTriple && existing.id != insertedId) {
                        temporalFactsCache[idx] = existing.copy(
                            isActive = false,
                            validUntilMs = nowMs,
                            supersededById = insertedId,
                            updatedAtMs = nowMs
                        )
                        supersededOldIds.add(existing.id)
                    }
                }
            } else if (triple.objectValue.isNotBlank() && triple.objectValue.length >= 3) {
                // Untuk relasi majemuk (adik-adik, kakak, anak, teman, hobi):
                // Hanya gantikan jika objek spesifiknya (misal nama adik/teman yang sama) identik!
                for (idx in temporalFactsCache.indices) {
                    val existing = temporalFactsCache[idx]
                    val sameObjectAndSubject = existing.isActive &&
                        existing.subject.equals(triple.subject, ignoreCase = true) &&
                        existing.objectValue.equals(triple.objectValue, ignoreCase = true)
                    if (sameObjectAndSubject && existing.id != insertedId) {
                        temporalFactsCache[idx] = existing.copy(
                            isActive = false,
                            validUntilMs = nowMs,
                            supersededById = insertedId,
                            updatedAtMs = nowMs
                        )
                        supersededOldIds.add(existing.id)
                    }
                }
            }

            // Jika duplikat teks persis sudah aktif, gantikan posisinya di atas
            temporalFactsCache.removeAll { it.isActive && it.factText.equals(cleanFact, ignoreCase = true) && it.id != insertedId }
            temporalFactsCache.add(0, tempEntity)
            if (temporalFactsCache.size > UserMemoryManager.MAX_TEMPORAL_RAM_FACTS) {
                temporalFactsCache.subList(UserMemoryManager.MAX_TEMPORAL_RAM_FACTS, temporalFactsCache.size).clear()
            }
        }

        if (supersededOldIds.isNotEmpty()) {
            vectorEngine.removeFactIds(supersededOldIds)
        }

        // 3. Persistensi ke Room DAO v5 & Indexing Vektor Semantik Layer 2 di background IO
        val supersedesSnapshot = supersededOldIds.toList()
        if (dao != null) {
            vectorEngine.launchTrackedVectorJob {
                try {
                    dao.expireOutdatedTemporalFacts(nowMs)
                    val entity = TemporalMemoryFactEntity(
                        category = triple.category,
                        subject = triple.subject,
                        predicate = triple.predicate,
                        objectValue = triple.objectValue,
                        factText = cleanFact,
                        confidence = 1.0f,
                        validFromMs = nowMs,
                        validUntilMs = triple.validUntilMs,
                        isActive = true,
                        source = source,
                        createdAtMs = nowMs,
                        updatedAtMs = nowMs
                    )
                    val insertedDbId = dao.insertTemporalFact(entity)
                    val effectiveFactId = if (insertedDbId > 0L) insertedDbId else insertedId
                    if (insertedDbId > 0L) {
                        synchronized(temporalLock) {
                            for (idx in temporalFactsCache.indices) {
                                val cached = temporalFactsCache[idx]
                                if (cached.id == insertedId) {
                                    temporalFactsCache[idx] = cached.copy(id = insertedDbId)
                                } else if (cached.supersededById == insertedId) {
                                    temporalFactsCache[idx] = cached.copy(supersededById = insertedDbId)
                                }
                            }
                        }
                    }
                    if (supersedesSnapshot.isNotEmpty()) {
                        supersedesSnapshot.filter { it > 0L }.forEach { oldId ->
                            dao.supersedeTemporalFact(oldId = oldId, newId = effectiveFactId, nowMs = nowMs)
                            dao.deleteVectorByFactId(oldId)
                        }
                    }
                    vectorEngine.indexMemoryVectorSuspend(
                        factId = effectiveFactId,
                        category = normalizedCategory,
                        subject = effectiveSubject,
                        contentText = cleanFact,
                        importance = computedImportance,
                        nowMs = nowMs
                    )
                } catch (t: Throwable) {
                    GepyLog.w(UserMemoryManager.TAG, "Gagal menyisipkan fakta ke Room temporal v5: ${t.message}")
                }
            }
        } else if (vectorEngine.isEmbeddingReady()) {
            vectorEngine.launchTrackedVectorJob {
                vectorEngine.indexMemoryVectorSuspend(
                    factId = insertedId,
                    category = normalizedCategory,
                    subject = effectiveSubject,
                    contentText = cleanFact,
                    importance = computedImportance,
                    nowMs = nowMs
                )
            }
        }

        GepyLog.i(
            UserMemoryManager.TAG,
            "Fakta temporal disimpan: [$normalizedCategory | ${triple.subject}.${triple.predicate}=${triple.objectValue}] $cleanFact"
        )

        syncToCloudIfAuthenticated(normalizedCategory, cleanFact)
        notifyMemoryChanged()
    }

    /**
     * Mengoreksi atau memperbarui fakta memori jangka panjang pengguna yang salah atau salah dengar.
     * Mencari fakta lama yang mengandung [targetToReplace] dan menggantikannya dengan [correctedFact],
     * sembari tetap memelihara jejak audit di graf temporal.
     */
    suspend fun updateOrCorrectMemory(
        targetToReplace: String,
        correctedFact: String,
        category: String = "general",
        subject: String = ""
    ) {
        val cleanTarget = targetToReplace.trim()
        val cleanFact = correctedFact.trim()
        if (cleanFact.length < 3) return

        val nowMs = clockMsProvider()
        val triple = TemporalKnowledgeGraph.extractTriple(
            factText = cleanFact,
            rawCategory = category,
            rawSubject = subject,
            nowMs = nowMs
        )
        val normalizedCategory = triple.category
        val effectiveSubject = triple.subject

        val current = getLocalEntries().toMutableList()
        var updated = false

        if (cleanTarget.isNotBlank()) {
            val iterator = current.listIterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.fact.contains(cleanTarget, ignoreCase = true) || entry.subject.contains(cleanTarget, ignoreCase = true)) {
                    iterator.set(
                        entry.copy(
                            category = normalizedCategory,
                            subject = effectiveSubject.ifBlank { entry.subject },
                            fact = cleanFact,
                            updatedAt = nowMs,
                            validUntilMs = triple.validUntilMs
                        )
                    )
                    updated = true
                    GepyLog.i(UserMemoryManager.TAG, "Memori lama yang mengandung '$cleanTarget' berhasil dikoreksi menjadi: $cleanFact")
                }
            }
        }

        if (!updated && effectiveSubject.isNotBlank()) {
            val iterator = current.listIterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.subject.equals(effectiveSubject, ignoreCase = true)) {
                    iterator.set(
                        entry.copy(
                            category = normalizedCategory,
                            fact = cleanFact,
                            updatedAt = nowMs,
                            validUntilMs = triple.validUntilMs
                        )
                    )
                    updated = true
                    GepyLog.i(UserMemoryManager.TAG, "Memori subject '$effectiveSubject' berhasil diperbarui dengan koreksi: $cleanFact")
                    break
                }
            }
        }

        if (!updated) {
            current.add(
                0,
                UserMemoryEntry(
                    category = normalizedCategory,
                    subject = effectiveSubject,
                    fact = cleanFact,
                    updatedAt = nowMs,
                    validUntilMs = triple.validUntilMs
                )
            )
            GepyLog.i(UserMemoryManager.TAG, "Koreksi memori baru didaftarkan: $cleanFact")
        }

        persistEntries(current)

        // Catat di Graf Temporal dengan soft-invalidation pada entri lama yang dikoreksi
        val dao = gepyDao
        val newEntity = TemporalMemoryFactEntity(
            category = triple.category,
            subject = triple.subject,
            predicate = triple.predicate,
            objectValue = triple.objectValue,
            factText = cleanFact,
            confidence = 1.0f,
            validFromMs = nowMs,
            validUntilMs = triple.validUntilMs,
            isActive = true,
            source = "correction",
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        )
        val insertedId = try {
            dao?.insertTemporalFact(newEntity) ?: synchronized(temporalLock) { nextInMemoryFactId++ }
        } catch (_: Throwable) {
            synchronized(temporalLock) { nextInMemoryFactId++ }
        }

        val supersededIds = mutableListOf<Long>()
        synchronized(temporalLock) {
            for (idx in temporalFactsCache.indices) {
                val existing = temporalFactsCache[idx]
                val matchesTarget = cleanTarget.isNotBlank() &&
                    (existing.factText.contains(cleanTarget, ignoreCase = true) || existing.subject.contains(cleanTarget, ignoreCase = true))
                val matchesSubject = effectiveSubject.isNotBlank() && existing.subject.equals(effectiveSubject, ignoreCase = true)
                if (existing.isActive && (matchesTarget || (triple.isSingleValued && matchesSubject)) && existing.id != insertedId) {
                    temporalFactsCache[idx] = existing.copy(
                        isActive = false,
                        validUntilMs = nowMs,
                        supersededById = insertedId,
                        updatedAtMs = nowMs
                    )
                    supersededIds.add(existing.id)
                }
            }
            temporalFactsCache.add(0, newEntity.copy(id = insertedId))
        }

        if (supersededIds.isNotEmpty()) {
            vectorEngine.removeFactIds(supersededIds, targetKeyword = cleanTarget.takeIf { it.isNotBlank() })
        }
        if (dao != null && supersededIds.isNotEmpty()) {
            try {
                supersededIds.filter { it > 0L }.forEach { oldId ->
                    dao.supersedeTemporalFact(oldId, insertedId, nowMs)
                    dao.deleteVectorByFactId(oldId)
                }
            } catch (_: Throwable) {
            }
        }

        if (vectorEngine.isEmbeddingReady()) {
            val correctedImportance = EbbinghausDecayEngine.computeImportanceScore(newEntity.copy(id = insertedId))
            vectorEngine.launchTrackedVectorJob {
                vectorEngine.indexMemoryVectorSuspend(
                    factId = insertedId,
                    category = normalizedCategory,
                    subject = effectiveSubject,
                    contentText = cleanFact,
                    importance = correctedImportance,
                    nowMs = nowMs
                )
            }
        }

        // Jika koreksi menargetkan identitas nama pengguna, perbarui nama display resmi
        if (effectiveSubject == "user_name" || cleanTarget.contains("nama", ignoreCase = true) ||
            cleanFact.lowercase().contains("nama panggilan") || cleanFact.lowercase().contains("nama saya") ||
            cleanFact.lowercase().contains("namaku")
        ) {
            val potentialName = triple.objectValue.ifBlank {
                UserMemoryManager.NAME_EXTRACTION_REGEX.find(cleanFact)?.groupValues?.getOrNull(1) ?: ""
            }
            if (factExtractor.isValidName(potentialName)) {
                val clean = potentialName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                onProfileNameUpdated(clean)
                GepyLog.i(UserMemoryManager.TAG, "updateOrCorrectMemory memperbarui nama display pengguna menjadi: $clean")
            }
        }

        syncToCloudIfAuthenticated(normalizedCategory, cleanFact)
        notifyMemoryChanged()
    }

    /**
     * Menghapus fakta tertentu dari memori lokal HP & Room `v5` (misal pengguna meminta untuk melupakan fakta).
     */
    fun deleteMemory(targetOrKeyword: String): Boolean {
        val clean = targetOrKeyword.trim()
        if (clean.isBlank()) return false

        val current = getLocalEntries().toMutableList()
        val removedFromPrefs = current.removeAll {
            it.fact.contains(clean, ignoreCase = true) || it.subject.contains(clean, ignoreCase = true)
        }
        if (removedFromPrefs) {
            persistEntries(current)
        }

        val idsToDelete = mutableListOf<Long>()
        var removedFromCache = false
        synchronized(temporalLock) {
            val iterator = temporalFactsCache.iterator()
            while (iterator.hasNext()) {
                val entity = iterator.next()
                if (entity.factText.contains(clean, ignoreCase = true) ||
                    entity.subject.contains(clean, ignoreCase = true) ||
                    entity.objectValue.contains(clean, ignoreCase = true)
                ) {
                    idsToDelete.add(entity.id)
                    iterator.remove()
                    removedFromCache = true
                }
            }
        }

        val removedVector = vectorEngine.removeMatchingKeywordOrFactIds(clean, idsToDelete)

        if (gepyDao != null && (idsToDelete.isNotEmpty() || removedVector || removedFromPrefs)) {
            scope.launch {
                idsToDelete.filter { it > 0L }.forEach { id ->
                    try {
                        gepyDao.deleteVectorByFactId(id)
                        gepyDao.deleteTemporalFactById(id)
                    } catch (_: Throwable) {
                    }
                }
                try {
                    gepyDao.deleteVectorsMatchingKeyword(clean)
                } catch (_: Throwable) {
                }
            }
        }

        val anyRemoved = removedFromPrefs || removedFromCache || removedVector
        if (anyRemoved) {
            GepyLog.i(UserMemoryManager.TAG, "Memori yang memuat '$clean' berhasil dihapus.")
            notifyMemoryChanged()
        }
        return anyRemoved
    }

    /** Menghapus entri graf temporal & indeks vektornya berdasarkan ID (dipanggil dari UI `TemporalMemoryDialog`). */
    suspend fun deleteTemporalFactById(factId: Long) {
        var targetFactText: String? = null
        synchronized(temporalLock) {
            val idx = temporalFactsCache.indexOfFirst { it.id == factId }
            if (idx >= 0) {
                targetFactText = temporalFactsCache[idx].factText
                temporalFactsCache.removeAt(idx)
            }
        }
        vectorEngine.removeFactIds(listOf(factId))
        if (!targetFactText.isNullOrBlank()) {
            val current = getLocalEntries().toMutableList()
            if (current.removeAll { it.fact.equals(targetFactText, ignoreCase = true) }) {
                persistEntries(current)
            }
        }
        try {
            gepyDao?.deleteVectorByFactId(factId)
            gepyDao?.deleteTemporalFactById(factId)
        } catch (_: Throwable) {
        }
        notifyMemoryChanged()
    }

    /** Mengambil seluruh fakta temporal (aktif, berbatas waktu, maupun riwayat lama) untuk UI / inspeksi. */
    fun getAllTemporalFactsSnapshot(): List<TemporalMemoryFactEntity> {
        val nowMs = clockMsProvider()
        synchronized(temporalLock) {
            if (temporalFactsCache.isNotEmpty()) {
                return temporalFactsCache.map { entity ->
                    if (entity.isActive && entity.validUntilMs != null && entity.validUntilMs <= nowMs) {
                        entity.copy(isActive = false)
                    } else {
                        entity
                    }
                }
            }
        }
        // Fallback sinkron jika Room belum selesai dimuat atau pada unit test yang mengisi via legacy prefs
        return getLocalEntries().mapIndexed { index, entry ->
            val triple = TemporalKnowledgeGraph.extractTriple(
                factText = entry.fact,
                rawCategory = entry.category,
                rawSubject = entry.subject,
                nowMs = entry.updatedAt
            )
            TemporalMemoryFactEntity(
                id = -(index + 1L),
                category = triple.category,
                subject = triple.subject,
                predicate = triple.predicate,
                objectValue = triple.objectValue,
                factText = entry.fact,
                validFromMs = entry.updatedAt,
                validUntilMs = entry.validUntilMs ?: triple.validUntilMs,
                isActive = true,
                source = "local_cache",
                createdAtMs = entry.updatedAt,
                updatedAtMs = entry.updatedAt
            )
        }
    }

    /** Mengambil fakta aktif yang masih berlaku pada timestamp [nowMs]. */
    fun getActiveTemporalFactsSync(nowMs: Long = clockMsProvider()): List<TemporalMemoryFactEntity> {
        return getAllTemporalFactsSnapshot().filter { TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) }
    }

    /** Mengambil agenda / fakta berbatas waktu untuk hari ini (digunakan oleh `DailyBriefingEngine`). */
    fun getTodaySchedules(nowMs: Long = clockMsProvider()): List<TemporalMemoryFactEntity> {
        return TemporalKnowledgeGraph.getTodaySchedules(getActiveTemporalFactsSync(nowMs), nowMs)
    }

    /** Menghapus seluruh cache memori lokal di RAM secara sinkron. */
    internal fun clearLocalCachesSync() {
        isTemporalInitialized = false
        clearPrefs()
        vectorEngine.clearCacheAndJobsSync()
        synchronized(temporalLock) {
            temporalFactsCache.clear()
        }
    }

    /** Menghapus seluruh data memori lokal pengguna (SharedPreferences, Room temporal v5, & indeks vektor). */
    fun clearLocalMemory() {
        launchClearLocalMemoryJob()
    }

    /** Versi `suspend` dari [clearLocalMemory] yang menunggu hingga penghapusan Room SQLite selesai. */
    suspend fun clearLocalMemorySuspend() {
        launchClearLocalMemoryJob()?.join()
    }

    private fun launchClearLocalMemoryJob(): Job? {
        clearLocalCachesSync()
        val dao = gepyDao ?: run {
            GepyLog.i(UserMemoryManager.TAG, "Seluruh memori profil lokal, graf temporal, & vektor semantik telah dibersihkan.")
            return null
        }
        lateinit var jobRef: Job
        jobRef = scope.launch(start = CoroutineStart.LAZY) {
            temporalInitMutex.withLock {
                clearLocalCachesSync()
                try {
                    dao.deleteAllTemporalFacts()
                    dao.deleteAllMemoryVectors()
                } catch (_: Throwable) {
                }
            }
        }
        synchronized(clearJobLock) {
            activeClearJob = jobRef
        }
        jobRef.invokeOnCompletion {
            synchronized(clearJobLock) {
                if (activeClearJob === jobRef) {
                    activeClearJob = null
                }
            }
        }
        jobRef.start()
        GepyLog.i(UserMemoryManager.TAG, "Seluruh memori profil lokal, graf temporal, & vektor semantik telah dibersihkan.")
        return jobRef
    }
}
