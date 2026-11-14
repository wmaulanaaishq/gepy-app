package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.data.database.GepyDao
import com.teknologigawai.gepy.data.database.MemoryVectorEntity
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.coroutines.coroutineContext

/**
 * Mesin penyimpanan, caching (`<= 1.000` vektor RAM), backfill, dan pencarian semantik hibrida Layer 2 (RRF `k = 60`).
 */
internal class HybridVectorMemoryEngine(
    private val gepyDao: GepyDao?,
    private val clockMsProvider: () -> Long,
    private val scope: CoroutineScope,
    private val embeddingClient: GeminiEmbeddingClient?,
    private val embeddingKeyPoolProvider: () -> List<String>,
    private val embeddingModelProvider: () -> String,
    private val subWordBM25: SubWordBM25RetrievalEngine,
    private val isTemporalInitializedProvider: () -> Boolean,
    private val isClearJobActiveProvider: () -> Boolean,
    private val awaitPendingClearJobProvider: suspend () -> Unit,
    private val getUserDisplayName: () -> String,
    private val getActiveTemporalFactsSync: (Long) -> List<TemporalMemoryFactEntity>,
    private val getAllTemporalFactsSnapshot: () -> List<TemporalMemoryFactEntity>
) {
    private val vectorLock = Any()
    private val vectorCache = mutableListOf<CachedVectorEntry>()
    private val pendingVectorJobs = Collections.synchronizedSet(mutableSetOf<Job>())

    internal fun isEmbeddingReady(): Boolean =
        embeddingClient != null && usableEmbeddingKeys().isNotEmpty()

    internal fun isBackfillReady(nowMs: Long): Boolean =
        isEmbeddingReady() && !(embeddingClient?.isNetworkBackoffActive(nowMs) ?: true)

    internal fun clearCacheAndJobsSync() {
        synchronized(pendingVectorJobs) {
            pendingVectorJobs.forEach { it.cancel() }
            pendingVectorJobs.clear()
        }
        synchronized(vectorLock) {
            vectorCache.clear()
        }
    }

    internal fun replaceCacheFromEntities(loadedVectors: List<MemoryVectorEntity>) {
        synchronized(vectorLock) {
            vectorCache.clear()
            for (vec in loadedVectors) {
                val decoded = GeminiEmbeddingClient.decodeFloat32LittleEndian(vec.embeddingBlob)
                if (decoded.isNotEmpty()) {
                    vectorCache.add(
                        CachedVectorEntry(
                            factId = vec.factId,
                            category = vec.category,
                            subject = vec.subject,
                            contentText = vec.contentText,
                            contentHash = vec.contentHash,
                            embedding = decoded,
                            importance = vec.importance,
                            updatedAtMs = vec.updatedAtMs
                        )
                    )
                }
            }
        }
    }

    internal fun getCachedHashes(): Set<String> =
        synchronized(vectorLock) { vectorCache.mapTo(HashSet()) { it.contentHash } }

    internal fun triggerBackfillIfEligible(
        facts: List<TemporalMemoryFactEntity>,
        nowMs: Long,
        dbHashes: Set<String>
    ) {
        if (!isBackfillReady(nowMs)) return
        val existingHashes = dbHashes + getCachedHashes()
        val candidates = facts
            .filter { TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) }
            .filter { GeminiEmbeddingClient.sha256Hex(it.factText) !in existingHashes }
            .sortedByDescending { EbbinghausDecayEngine.computeImportanceScore(it) }
        if (candidates.isNotEmpty()) {
            launchTrackedVectorJob {
                enqueueChunkedVectorBackfill(
                    candidates = candidates,
                    startNowMs = nowMs
                )
            }
        }
    }

    internal fun removeFactIds(factIds: Collection<Long>, targetKeyword: String? = null): Boolean {
        val idSet = if (factIds is Set<Long>) factIds else factIds.toSet()
        return synchronized(vectorLock) {
            vectorCache.removeAll { entry ->
                (entry.factId != null && entry.factId in idSet) ||
                    (!targetKeyword.isNullOrBlank() && entry.contentText.contains(targetKeyword, ignoreCase = true))
            }
        }
    }

    internal fun removeMatchingKeywordOrFactIds(keyword: String, factIds: Collection<Long>): Boolean {
        val idSet = if (factIds is Set<Long>) factIds else factIds.toSet()
        return synchronized(vectorLock) {
            vectorCache.removeAll { entry ->
                (entry.factId != null && entry.factId in idSet) ||
                    entry.contentText.contains(keyword, ignoreCase = true) ||
                    entry.subject.contains(keyword, ignoreCase = true)
            }
        }
    }

    internal fun launchTrackedVectorJob(block: suspend () -> Unit): Job {
        lateinit var jobRef: Job
        jobRef = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                pendingVectorJobs.remove(jobRef)
            }
        }
        pendingVectorJobs.add(jobRef)
        jobRef.start()
        return jobRef
    }

    /** Menunggu seluruh tugas indexing vektor semantik yang sedang antre (untuk sinkronisasi pengujian & backup). */
    suspend fun flushPendingVectorIndexing() {
        val snapshot = synchronized(pendingVectorJobs) { pendingVectorJobs.toList() }
        for (job in snapshot) {
            runCatching { job.join() }
        }
    }

    fun cachedVectorCount(): Int = synchronized(vectorLock) { vectorCache.size }

    /** Mengambil ringkasan sinkron kapasitas vektor RAM tanpa memicu query SQLite. */
    fun peekVectorRamStats(): VectorMemoryStats {
        val ramVecs = synchronized(vectorLock) { vectorCache.size }
        val activeFacts = getActiveTemporalFactsSync(clockMsProvider()).size
        return VectorMemoryStats(
            ramVectorCount = ramVecs,
            maxRamVectors = UserMemoryManager.MAX_VECTOR_RAM_CACHE,
            dbVectorCount = ramVecs,
            maxDbVectors = UserMemoryManager.MAX_VECTOR_DB_ROWS,
            activeTemporalFacts = activeFacts,
            estimatedVectorRamKb = ramVecs * 3
        )
    }

    /** Mengambil ringkasan lengkap kapasitas memori vektor RAM dan Room SQLite v5 untuk diagnostik UI. */
    suspend fun getVectorMemoryStats(): VectorMemoryStats {
        awaitPendingClearJobProvider()
        val ramVecs = synchronized(vectorLock) { vectorCache.size }
        val activeFacts = getActiveTemporalFactsSync(clockMsProvider()).size
        val dbVecs = gepyDao?.let { dao ->
            runCatching { dao.countMemoryVectors() }.getOrNull()
        } ?: ramVecs
        return VectorMemoryStats(
            ramVectorCount = ramVecs,
            maxRamVectors = UserMemoryManager.MAX_VECTOR_RAM_CACHE,
            dbVectorCount = dbVecs,
            maxDbVectors = UserMemoryManager.MAX_VECTOR_DB_ROWS,
            activeTemporalFacts = activeFacts,
            estimatedVectorRamKb = ramVecs * 3
        )
    }

    internal fun usableEmbeddingKeys(): List<String> {
        return embeddingKeyPoolProvider()
            .map { GeminiKeyPool.sanitizeKey(it) }
            .filter {
                it.isNotEmpty() &&
                    !it.equals("test_key", ignoreCase = true) &&
                    !it.equals("dummy_key", ignoreCase = true)
            }
            .distinct()
    }

    internal suspend fun enqueueChunkedVectorBackfill(
        candidates: List<TemporalMemoryFactEntity>,
        startNowMs: Long
    ) {
        val client = embeddingClient ?: return
        val batches = candidates.chunked(10)
        for ((batchIndex, batch) in batches.withIndex()) {
            coroutineContext.ensureActive()
            if (usableEmbeddingKeys().isEmpty() || client.isNetworkBackoffActive(clockMsProvider())) {
                GepyLog.w(UserMemoryManager.TAG, "Backfill vektor dijeda pada batch ${batchIndex + 1}/${batches.size} (backoff jaringan / API key tidak tersedia).")
                break
            }
            for (fact in batch) {
                coroutineContext.ensureActive()
                if (client.isNetworkBackoffActive(clockMsProvider())) break
                indexMemoryVectorSuspend(
                    factId = fact.id,
                    category = fact.category,
                    subject = fact.subject,
                    contentText = fact.factText,
                    importance = EbbinghausDecayEngine.computeImportanceScore(fact),
                    nowMs = fact.updatedAtMs.takeIf { it > 0L } ?: startNowMs
                )
            }
            if (batchIndex < batches.lastIndex) {
                delay(120L)
            }
        }
    }

    /**
     * Mencatat ringkasan memori episodik/percakapan langsung ke indeks vektor Layer 2
     * (`memory_vectors`) dengan batas RAM `<= 1.000` (`~3 MB`) dan batas tabel SQLite `<= 2.500`.
     */
    fun recordEpisodicVectorMemory(
        category: String,
        subject: String,
        contentText: String,
        importance: Int = 6,
        factId: Long? = null
    ) {
        val clean = contentText.trim()
        if (clean.length < 5) return
        if (embeddingClient == null || usableEmbeddingKeys().isEmpty()) return
        val nowMs = clockMsProvider()
        launchTrackedVectorJob {
            indexMemoryVectorSuspend(
                factId = factId,
                category = category.trim().ifBlank { "episodic" },
                subject = subject.trim().ifBlank { "episodic_memory" },
                contentText = clean,
                importance = importance.coerceIn(1, 10),
                nowMs = nowMs
            )
        }
    }

    internal suspend fun indexMemoryVectorSuspend(
        factId: Long?,
        category: String,
        subject: String,
        contentText: String,
        importance: Int,
        nowMs: Long
    ) {
        val client = embeddingClient ?: return
        val cleanText = contentText.trim()
        if (cleanText.length < 5) return
        val keys = usableEmbeddingKeys()
        if (keys.isEmpty()) return

        val contentHash = GeminiEmbeddingClient.sha256Hex(cleanText)

        // 1. Deduplikasi SHA-256 di RAM cache (hindari panggilan API berulang)
        val cachedSameHash = synchronized(vectorLock) {
            val idx = vectorCache.indexOfFirst { it.contentHash == contentHash }
            if (idx >= 0) {
                val existing = vectorCache[idx]
                if (factId != null && existing.factId != factId) {
                    vectorCache[idx] = existing.copy(factId = factId, updatedAtMs = nowMs)
                }
                true
            } else {
                false
            }
        }
        if (cachedSameHash) return

        // 2. Deduplikasi SHA-256 di Room SQLite sebelum panggil cloud embedding
        val dao = gepyDao
        if (dao != null) {
            val existingDb = runCatching { dao.findVectorByContentHash(contentHash) }.getOrNull()
            if (existingDb != null) {
                val decoded = GeminiEmbeddingClient.decodeFloat32LittleEndian(existingDb.embeddingBlob)
                if (decoded.isNotEmpty()) {
                    upsertCachedVectorEntry(
                        CachedVectorEntry(
                            factId = factId ?: existingDb.factId,
                            category = existingDb.category,
                            subject = existingDb.subject,
                            contentText = existingDb.contentText,
                            contentHash = existingDb.contentHash,
                            embedding = decoded,
                            importance = existingDb.importance,
                            updatedAtMs = existingDb.updatedAtMs
                        )
                    )
                    return
                }
            }
        }

        // 3. Hitung vektor 768-dim MRL dari Gemini Embedding 2 (Key 2 -> failover Key 1)
        val result = client.embedDocument(
            title = "$category: $subject",
            content = cleanText,
            keyPool = keys,
            modelId = embeddingModelProvider()
        )
        if (result !is EmbeddingResult.Success) return

        val normalizedVector = result.vector
        val blob = GeminiEmbeddingClient.encodeFloat32LittleEndian(normalizedVector)
        val persistedFactId = factId?.takeIf { it > 0L }

        if (dao != null) {
            runCatching {
                if (persistedFactId != null) {
                    dao.deleteVectorByFactId(persistedFactId)
                }
                dao.upsertMemoryVector(
                    MemoryVectorEntity(
                        factId = persistedFactId,
                        category = category,
                        subject = subject,
                        contentText = cleanText,
                        contentHash = contentHash,
                        embeddingBlob = blob,
                        modelId = result.modelId,
                        dimensions = normalizedVector.size,
                        importance = importance.coerceIn(1, 10),
                        createdAtMs = nowMs,
                        updatedAtMs = nowMs
                    )
                )
                dao.pruneExcessVectors(UserMemoryManager.MAX_VECTOR_DB_ROWS)
            }.onFailure { t ->
                GepyLog.w(UserMemoryManager.TAG, "Gagal menyimpan vektor memori ke Room v5: ${t.message}")
            }
        }

        upsertCachedVectorEntry(
            CachedVectorEntry(
                factId = factId,
                category = category,
                subject = subject,
                contentText = cleanText,
                contentHash = contentHash,
                embedding = normalizedVector,
                importance = importance.coerceIn(1, 10),
                updatedAtMs = nowMs
            )
        )
    }

    private fun upsertCachedVectorEntry(entry: CachedVectorEntry) {
        synchronized(vectorLock) {
            vectorCache.removeAll { existing ->
                existing.contentHash == entry.contentHash ||
                    (entry.factId != null && existing.factId == entry.factId)
            }
            vectorCache.add(entry)
            if (vectorCache.size > UserMemoryManager.MAX_VECTOR_RAM_CACHE) {
                vectorCache.sortWith(
                    compareByDescending<CachedVectorEntry> { it.importance }
                        .thenByDescending { it.updatedAtMs }
                )
                vectorCache.subList(UserMemoryManager.MAX_VECTOR_RAM_CACHE, vectorCache.size).clear()
            }
        }
    }

    /**
     * Pencarian **Dual-Layer Hybrid Memory** yang menggabungkan:
     * - **Layer 1**: Pencarian leksikal *Sub-Word Tri-Gram Okapi BM25+* + *Temporal KG* + *Ebbinghaus Retention* (`0 ms`, offline).
     * - **Layer 2**: Pencarian makna kosinus on-device (`Float32` 768-dim `gemini-embedding-2`, `<= 1.000` vektor di RAM).
     * Kedua peringkat digabungkan menggunakan **Reciprocal Rank Fusion (RRF, $k = 60$)**.
     */
    suspend fun searchHybridMemories(
        query: String,
        nowMs: Long = clockMsProvider(),
        maxResults: Int = 6
    ): List<HybridMemoryMatch> {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return emptyList()

        awaitPendingClearJobProvider()

        val allTemporal = getAllTemporalFactsSnapshot()
        val activeFacts = allTemporal.filter { TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) }
        val inactiveFactIds = allTemporal
            .filter { !TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) }
            .map { it.id }
            .toSet()

        val queryTokens = cleanQuery.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

        // LAYER 1: Deterministik Sub-Word BM25+ + Stanford Retention Score
        val layer1Ranked = activeFacts
            .map { entity ->
                val bm25 = subWordBM25.computeSimilarity(cleanQuery, entity)
                val stanford = EbbinghausDecayEngine.computeStanfordRetrievalScore(entity, queryTokens, nowMs)
                Triple(entity, bm25, bm25 + stanford * 0.15)
            }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Triple<TemporalMemoryFactEntity, Double, Double>> { it.third }
                    .thenByDescending { it.first.updatedAtMs }
            )

        // LAYER 2: On-Device Semantic Cosine Vector Search (<= 1.000 vektor di RAM + scan halaman SQLite hingga 2.500 baris)
        var vectorSnapshot = synchronized(vectorLock) { vectorCache.toList() }
        val daoForLoad = gepyDao
        if (vectorSnapshot.isEmpty() && !isTemporalInitializedProvider() && daoForLoad != null &&
            !isClearJobActiveProvider()
        ) {
            awaitPendingClearJobProvider()
            val dbVecs = if (!isClearJobActiveProvider()) {
                runCatching { daoForLoad.getRecentMemoryVectors(UserMemoryManager.MAX_VECTOR_RAM_CACHE) }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
            if (dbVecs.isNotEmpty() && !isClearJobActiveProvider()) {
                synchronized(vectorLock) {
                    if (vectorCache.isEmpty() && !isClearJobActiveProvider()) {
                        dbVecs.forEach { vec ->
                            val decoded = GeminiEmbeddingClient.decodeFloat32LittleEndian(vec.embeddingBlob)
                            if (decoded.isNotEmpty()) {
                                vectorCache.add(
                                    CachedVectorEntry(
                                        factId = vec.factId,
                                        category = vec.category,
                                        subject = vec.subject,
                                        contentText = vec.contentText,
                                        contentHash = vec.contentHash,
                                        embedding = decoded,
                                        importance = vec.importance,
                                        updatedAtMs = vec.updatedAtMs
                                    )
                                )
                            }
                        }
                    }
                    vectorSnapshot = vectorCache.toList()
                }
            }
        }

        val totalDbVectors = if (daoForLoad != null) {
            runCatching { daoForLoad.countMemoryVectors() }.getOrDefault(0)
        } else {
            0
        }

        val layer2Ranked: List<Pair<CachedVectorEntry, Float>> = if (
            embeddingClient != null &&
            (vectorSnapshot.isNotEmpty() || totalDbVectors > 0) &&
            !embeddingClient.isNetworkBackoffActive(nowMs)
        ) {
            val embedKeys = usableEmbeddingKeys()
            if (embedKeys.isNotEmpty()) {
                when (val qResult = embeddingClient.embedQuery(cleanQuery, embedKeys, embeddingModelProvider())) {
                    is EmbeddingResult.Success -> {
                        val seenHashes = HashSet<String>(vectorSnapshot.size + 64)
                        val seenFactIds = HashSet<Long>()
                        val matches = mutableListOf<Pair<CachedVectorEntry, Float>>()

                        for (entry in vectorSnapshot) {
                            seenHashes.add(entry.contentHash)
                            entry.factId?.let { seenFactIds.add(it) }
                            if (entry.factId != null && entry.factId in inactiveFactIds) continue
                            val sim = GeminiEmbeddingClient.cosineSimilarity(qResult.vector, entry.embedding)
                            if (sim >= UserMemoryManager.MIN_SEMANTIC_SIMILARITY) {
                                matches.add(entry to sim)
                            }
                        }

                        if (daoForLoad != null && totalDbVectors > vectorSnapshot.size) {
                            val pageSize = 200
                            var offset = if (vectorSnapshot.size >= UserMemoryManager.MAX_VECTOR_RAM_CACHE) UserMemoryManager.MAX_VECTOR_RAM_CACHE else 0
                            while (offset < UserMemoryManager.MAX_VECTOR_DB_ROWS) {
                                val page = runCatching {
                                    daoForLoad.getMemoryVectorsPaged(limit = pageSize, offset = offset)
                                }.getOrNull().orEmpty()
                                if (page.isEmpty()) break
                                for (vec in page) {
                                    if (!seenHashes.add(vec.contentHash)) continue
                                    if (vec.factId != null && !seenFactIds.add(vec.factId)) continue
                                    if (vec.factId != null && vec.factId in inactiveFactIds) continue
                                    val decoded = GeminiEmbeddingClient.decodeFloat32LittleEndian(vec.embeddingBlob)
                                    if (decoded.isEmpty()) continue
                                    val sim = GeminiEmbeddingClient.cosineSimilarity(qResult.vector, decoded)
                                    if (sim >= UserMemoryManager.MIN_SEMANTIC_SIMILARITY) {
                                        matches.add(
                                            CachedVectorEntry(
                                                factId = vec.factId,
                                                category = vec.category,
                                                subject = vec.subject,
                                                contentText = vec.contentText,
                                                contentHash = vec.contentHash,
                                                embedding = decoded,
                                                importance = vec.importance,
                                                updatedAtMs = vec.updatedAtMs
                                            ) to sim
                                        )
                                    }
                                }
                                if (page.size < pageSize) break
                                offset += pageSize
                            }
                        }

                        matches.sortedWith(
                            compareByDescending<Pair<CachedVectorEntry, Float>> { it.second }
                                .thenByDescending { it.first.importance }
                                .thenByDescending { it.first.updatedAtMs }
                        )
                    }
                    is EmbeddingResult.Failure -> emptyList()
                }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (layer1Ranked.isEmpty() && layer2Ranked.isEmpty()) {
            return emptyList()
        }

        // Reciprocal Rank Fusion (RRF, k = 60)
        data class RrfAcc(
            val factText: String,
            val category: String,
            val subject: String,
            val factId: Long?,
            val updatedAtMs: Long,
            var rrfScore: Double = 0.0,
            var bm25Score: Double = 0.0,
            var semanticCosine: Float = 0f,
            var inL1: Boolean = false,
            var inL2: Boolean = false
        )

        val fusedMap = LinkedHashMap<String, RrfAcc>()

        layer1Ranked.take(25).forEachIndexed { idx, (entity, bm25, _) ->
            val key = entity.factText.trim().lowercase()
            val acc = fusedMap.getOrPut(key) {
                RrfAcc(
                    factText = entity.factText,
                    category = entity.category,
                    subject = entity.subject,
                    factId = entity.id,
                    updatedAtMs = entity.updatedAtMs
                )
            }
            acc.inL1 = true
            acc.bm25Score = maxOf(acc.bm25Score, bm25)
            acc.rrfScore += 1.0 / (UserMemoryManager.RRF_K + idx + 1)
        }

        layer2Ranked.take(25).forEachIndexed { idx, (entry, sim) ->
            val key = entry.contentText.trim().lowercase()
            val acc = fusedMap.getOrPut(key) {
                RrfAcc(
                    factText = entry.contentText,
                    category = entry.category,
                    subject = entry.subject,
                    factId = entry.factId,
                    updatedAtMs = entry.updatedAtMs
                )
            }
            acc.inL2 = true
            acc.semanticCosine = maxOf(acc.semanticCosine, sim)
            acc.rrfScore += 1.0 / (UserMemoryManager.RRF_K + idx + 1)
        }

        return fusedMap.values
            .map { acc ->
                val layerTag = when {
                    acc.inL1 && acc.inL2 -> "HYBRID_RRF"
                    acc.inL2 -> "LAYER2_VECTOR"
                    else -> "LAYER1_BM25"
                }
                HybridMemoryMatch(
                    factText = acc.factText,
                    category = acc.category,
                    subject = acc.subject,
                    rrfScore = acc.rrfScore,
                    bm25Score = acc.bm25Score,
                    semanticCosine = acc.semanticCosine,
                    matchedLayer = layerTag,
                    factId = acc.factId,
                    updatedAtMs = acc.updatedAtMs
                )
            }
            .sortedWith(
                compareByDescending<HybridMemoryMatch> { it.rrfScore }
                    .thenByDescending { it.semanticCosine }
                    .thenByDescending { it.bm25Score }
                    .thenByDescending { it.updatedAtMs }
            )
            .take(maxResults.coerceIn(1, 20))
    }

    /**
     * Membangun konteks memori hibrida Layer 1 + Layer 2 RRF (`k = 60`) untuk percakapan teks ReAct
     * maupun balasan multi-turn tanpa melampaui anggaran token (`<= 8` fakta terbaik).
     */
    suspend fun buildHybridReActContext(userGoal: String, maxFacts: Int = 8): String? {
        awaitPendingClearJobProvider()
        val name = getUserDisplayName()
        val nowMs = clockMsProvider()
        val activeTemporal = getActiveTemporalFactsSync(nowMs)
        val daoForVectorCount = gepyDao
        val hasVectors = synchronized(vectorLock) { vectorCache.isNotEmpty() } ||
            (daoForVectorCount != null && runCatching { daoForVectorCount.countMemoryVectors() > 0 }.getOrDefault(false))
        if (name.isBlank() && activeTemporal.isEmpty() && !hasVectors) return null

        val limit = maxFacts.coerceIn(1, 15)
        val hybridMatches = searchHybridMemories(
            query = userGoal,
            nowMs = nowMs,
            maxResults = limit
        )

        val selectedFacts = LinkedHashSet<String>()
        for (match in hybridMatches) {
            if (selectedFacts.size >= limit) break
            selectedFacts.add(match.factText.take(120))
        }

        if (selectedFacts.size < limit && activeTemporal.isNotEmpty()) {
            val lexicalFallback = TemporalKnowledgeGraph.rankRelevantFacts(
                facts = activeTemporal,
                query = userGoal,
                nowMs = nowMs,
                maxFacts = limit
            )
            for (entity in lexicalFallback) {
                if (selectedFacts.size >= limit) break
                selectedFacts.add(entity.factText.take(120))
            }
        }

        return buildString {
            if (name.isNotBlank()) {
                append("Pemilik: $name. ")
            }
            if (selectedFacts.isNotEmpty()) {
                append("Fakta Pribadi: ")
                append(selectedFacts.joinToString(" | "))
            }
        }.trim().ifEmpty { null }
    }
}
