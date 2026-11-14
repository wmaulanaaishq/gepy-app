@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.memory.backup.SovereignBackupManager
import com.teknologigawai.gepy.data.database.AppKnowledgeGraphEntity
import com.teknologigawai.gepy.data.database.AppPackageCacheEntity
import com.teknologigawai.gepy.data.database.ContactDisambiguationEntity
import com.teknologigawai.gepy.data.database.EpisodicLessonEntity
import com.teknologigawai.gepy.data.database.GepyDao
import com.teknologigawai.gepy.data.database.MemoryVectorEntity
import com.teknologigawai.gepy.data.database.PersonalWellnessLogEntity
import com.teknologigawai.gepy.data.database.TelemetryLogEntity
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import com.teknologigawai.gepy.data.database.UiTransitionRuleEntity
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Layer2VectorMemoryTest {

    private class FakeGepyDao : GepyDao {
        private var nextFactId = 1L
        private var nextVectorId = 1L
        val temporalFacts = mutableListOf<TemporalMemoryFactEntity>()
        val memoryVectors = mutableListOf<MemoryVectorEntity>()
        val supersededCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun lookupPackageAlias(alias: String): String? = null
        override suspend fun upsertPackageAlias(entity: AppPackageCacheEntity) {}
        override suspend fun findContactPreference(
            queryName: String,
            packageName: String,
            resolvedDisplayName: String
        ): ContactDisambiguationEntity? = null
        override suspend fun upsertContactPreference(entity: ContactDisambiguationEntity) {}
        override suspend fun getContactPreferencesForQuery(
            queryName: String,
            packageName: String
        ): List<ContactDisambiguationEntity> = emptyList()
        override suspend fun getTopKnowledgeForPackage(packageName: String): List<AppKnowledgeGraphEntity> = emptyList()
        override suspend fun getAllKnowledgeEntries(): List<AppKnowledgeGraphEntity> = emptyList()
        override suspend fun deleteKnowledgeById(id: Long) {}
        override suspend fun deleteAllKnowledge() {}
        override suspend fun disableSessionKnowledge(sessionId: String, nowMs: Long) {}
        override suspend fun findKnowledgeEntry(
            packageName: String,
            screenSignatureHash: String,
            subgoalIntent: String,
            targetResourceId: String,
            actionType: String
        ): AppKnowledgeGraphEntity? = null
        override suspend fun upsertKnowledgeEntry(entity: AppKnowledgeGraphEntity) {}
        override suspend fun findBestPlaybookTransition(
            packageName: String,
            screenSignatureHash: String,
            subgoalIntent: String,
            minSuccessCount: Int
        ): AppKnowledgeGraphEntity? = null
        override suspend fun insertTelemetryLog(entity: TelemetryLogEntity) {}
        override suspend fun getRecentTelemetryLogs(limit: Int): List<TelemetryLogEntity> = emptyList()
        override fun observeRecentTelemetryLogs(limit: Int): Flow<List<TelemetryLogEntity>> = emptyFlow()
        override suspend fun getEpisodicLessonsForPackage(packageName: String, limit: Int): List<EpisodicLessonEntity> = emptyList()
        override suspend fun findEpisodicLesson(packageName: String, lessonRule: String): EpisodicLessonEntity? = null
        override suspend fun upsertEpisodicLesson(entity: EpisodicLessonEntity) {}
        override suspend fun getWellnessLogForDate(dateKey: String): PersonalWellnessLogEntity? = null
        override suspend fun upsertWellnessLog(entity: PersonalWellnessLogEntity) {}

        override suspend fun insertTemporalFact(entity: TemporalMemoryFactEntity): Long {
            val assignedId = if (entity.id > 0L) entity.id else nextFactId++
            temporalFacts.removeAll { it.id == assignedId }
            temporalFacts.add(entity.copy(id = assignedId))
            return assignedId
        }

        override suspend fun insertTemporalFacts(entities: List<TemporalMemoryFactEntity>): List<Long> {
            return entities.map { insertTemporalFact(it) }
        }

        override suspend fun getActiveTemporalFacts(nowMs: Long, limit: Int): List<TemporalMemoryFactEntity> {
            return temporalFacts
                .filter { it.isActive && (it.validUntilMs == null || it.validUntilMs > nowMs) }
                .sortedWith(compareByDescending<TemporalMemoryFactEntity> { it.hitCount }.thenByDescending { it.updatedAtMs })
                .take(limit)
        }

        override suspend fun findActiveFactsBySubjectPredicate(
            subject: String,
            predicate: String
        ): List<TemporalMemoryFactEntity> {
            return temporalFacts.filter { it.subject == subject && it.predicate == predicate && it.isActive }
        }

        override suspend fun supersedeTemporalFact(oldId: Long, newId: Long, nowMs: Long) {
            supersededCalls.add(oldId to newId)
            val idx = temporalFacts.indexOfFirst { it.id == oldId }
            if (idx >= 0) {
                temporalFacts[idx] = temporalFacts[idx].copy(
                    isActive = false,
                    validUntilMs = nowMs,
                    supersededById = newId,
                    updatedAtMs = nowMs
                )
            }
        }

        override suspend fun expireOutdatedTemporalFacts(nowMs: Long): Int {
            var count = 0
            for (idx in temporalFacts.indices) {
                val f = temporalFacts[idx]
                if (f.isActive && f.validUntilMs != null && f.validUntilMs <= nowMs) {
                    temporalFacts[idx] = f.copy(isActive = false, updatedAtMs = nowMs)
                    count++
                }
            }
            return count
        }

        override suspend fun searchTemporalTimeline(query: String, limit: Int): List<TemporalMemoryFactEntity> {
            return temporalFacts.filter {
                it.subject.contains(query, true) ||
                    it.predicate.contains(query, true) ||
                    it.objectValue.contains(query, true) ||
                    it.factText.contains(query, true)
            }.sortedByDescending { it.createdAtMs }.take(limit)
        }

        override suspend fun getAllTemporalFacts(limit: Int): List<TemporalMemoryFactEntity> {
            return temporalFacts
                .sortedWith(compareByDescending<TemporalMemoryFactEntity> { it.isActive }.thenByDescending { it.updatedAtMs })
                .take(limit)
        }

        override fun observeAllTemporalFacts(limit: Int): Flow<List<TemporalMemoryFactEntity>> = emptyFlow()

        override suspend fun incrementTemporalFactHitCount(id: Long, nowMs: Long) {
            val idx = temporalFacts.indexOfFirst { it.id == id }
            if (idx >= 0) {
                temporalFacts[idx] = temporalFacts[idx].copy(
                    hitCount = temporalFacts[idx].hitCount + 1,
                    updatedAtMs = nowMs
                )
            }
        }

        override suspend fun countTemporalFacts(): Int = temporalFacts.size

        override suspend fun deleteTemporalFactById(id: Long) {
            temporalFacts.removeAll { it.id == id }
        }

        override suspend fun deleteAllTemporalFacts() {
            temporalFacts.clear()
        }

        override suspend fun upsertMemoryVector(entity: MemoryVectorEntity): Long {
            val assignedId = if (entity.id > 0L) entity.id else nextVectorId++
            memoryVectors.removeAll { it.id == assignedId }
            memoryVectors.add(entity.copy(id = assignedId))
            return assignedId
        }

        override suspend fun findVectorByFactId(factId: Long): MemoryVectorEntity? {
            return memoryVectors.firstOrNull { it.factId == factId }
        }

        override suspend fun findVectorByContentHash(contentHash: String): MemoryVectorEntity? {
            return memoryVectors.firstOrNull { it.contentHash == contentHash }
        }

        override suspend fun getRecentMemoryVectors(limit: Int): List<MemoryVectorEntity> {
            return memoryVectors
                .sortedWith(compareByDescending<MemoryVectorEntity> { it.importance }.thenByDescending { it.updatedAtMs })
                .take(limit)
        }

        override suspend fun getAllVectorContentHashes(): List<String> {
            return memoryVectors.map { it.contentHash }
        }

        override suspend fun getMemoryVectorsPaged(limit: Int, offset: Int): List<MemoryVectorEntity> {
            if (limit <= 0 || offset < 0) return emptyList()
            return memoryVectors
                .sortedWith(compareByDescending<MemoryVectorEntity> { it.importance }.thenByDescending { it.updatedAtMs })
                .drop(offset)
                .take(limit)
        }

        override suspend fun countMemoryVectors(): Int = memoryVectors.size

        override suspend fun deleteVectorByFactId(factId: Long) {
            memoryVectors.removeAll { it.factId == factId }
        }

        override suspend fun deleteVectorsMatchingKeyword(keyword: String) {
            memoryVectors.removeAll {
                it.contentText.contains(keyword, ignoreCase = true) ||
                    it.subject.contains(keyword, ignoreCase = true)
            }
        }

        override suspend fun pruneExcessVectors(keepCount: Int): Int {
            if (memoryVectors.size <= keepCount) return 0
            val kept = memoryVectors
                .sortedWith(compareByDescending<MemoryVectorEntity> { it.importance }.thenByDescending { it.updatedAtMs })
                .take(keepCount)
            val removedCount = memoryVectors.size - kept.size
            memoryVectors.clear()
            memoryVectors.addAll(kept)
            return removedCount
        }

        override suspend fun deleteAllMemoryVectors() {
            memoryVectors.clear()
        }

        override suspend fun findEdgeRule(
            packageName: String,
            screenSignatureHash: String,
            edgeKey: String,
            actionType: String
        ): UiTransitionRuleEntity? = null

        override suspend fun getEdgeRulesForScreen(
            packageName: String,
            screenSignatureHash: String
        ): List<UiTransitionRuleEntity> = emptyList()

        override suspend fun getTopEdgeRules(limit: Int): List<UiTransitionRuleEntity> = emptyList()

        override suspend fun upsertEdgeRule(entity: UiTransitionRuleEntity): Long = 1L

        override suspend fun countEdgeRules(): Int = 0

        override suspend fun pruneExcessEdgeRules(keepCount: Int): Int = 0

        override suspend fun deleteAllEdgeRules() {}
    }

    private fun semanticVectorFor(text: String): DoubleArray {
        val lower = text.lowercase()
        val vec = DoubleArray(768)
        when {
            lower.contains("migrain") || lower.contains("sakit kepala") || lower.contains("headache") || lower.contains("pusing") -> {
                vec[0] = 0.99
                vec[1] = 0.05
            }
            lower.contains("americano") || lower.contains("kopi") || lower.contains("espresso") -> {
                vec[2] = 0.98
                vec[3] = 0.12
            }
            lower.contains("kotlin") || lower.contains("android") || lower.contains("compose") -> {
                vec[4] = 0.99
            }
            lower.contains("jakarta") || lower.contains("domisili") || lower.contains("bandung") -> {
                vec[5] = 0.99
            }
            else -> {
                val axis = 10 + ((text.hashCode() and 0x7FFFFFFF) % 700)
                vec[axis] = 1.0
            }
        }
        return vec
    }

    private fun buildEmbeddingTransport(
        onCall: (promptText: String) -> Unit = {},
        shouldThrowNetwork: () -> Boolean = { false }
    ): EmbeddingHttpTransport {
        return EmbeddingHttpTransport { _, _, jsonBody ->
            if (shouldThrowNetwork()) {
                throw IOException("Offline test simulation")
            }
            val obj = JSONObject(jsonBody)
            val promptText = obj.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            onCall(promptText)
            val vec = semanticVectorFor(promptText)
            val arr = JSONArray()
            for (v in vec) arr.put(v)
            EmbeddingHttpResponse(200, JSONObject().put("embedding", JSONObject().put("values", arr)).toString())
        }
    }

    private fun TestScope.createManager(
        dao: GepyDao? = null,
        client: GeminiEmbeddingClient? = null,
        clockMs: () -> Long = { 1_700_000_000_000L }
    ): UserMemoryManager {
        return UserMemoryManager(
            context = null,
            gepyDao = dao,
            clockMsProvider = clockMs,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            embeddingClient = client,
            embeddingKeyPoolProvider = { listOf("embed_k2_real", "embed_k1_real") },
            chatKeyPoolProvider = { listOf("embed_k1_real", "embed_k2_real") },
            embeddingModelProvider = { "gemini-embedding-2" }
        )
    }

    @Before
    fun setUp() {
        GeminiKeyPool.resetCooldownsForTest()
    }

    @After
    fun tearDown() {
        GeminiKeyPool.resetCooldownsForTest()
    }

    @Test
    fun `MemoryVectorEntity equals dan hashCode membandingkan isi ByteArray embeddingBlob secara tepat`() {
        val e1 = MemoryVectorEntity(
            id = 1L,
            factId = 10L,
            category = "health",
            subject = "user_health",
            contentText = "Pengguna alergi udang",
            contentHash = "abc",
            embeddingBlob = byteArrayOf(1, 2, 3, 4),
            modelId = "gemini-embedding-2",
            dimensions = 1,
            importance = 9,
            createdAtMs = 100L,
            updatedAtMs = 100L
        )
        val e2 = e1.copy(embeddingBlob = byteArrayOf(1, 2, 3, 4))
        val e3 = e1.copy(embeddingBlob = byteArrayOf(1, 2, 3, 9))

        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
        assertNotEquals(e1, e3)
    }

    @Test
    fun `pencarian Layer 2 menemukan fakta lintas kata atau sinonim tanpa kecocokan token leksikal`() = runTest {
        val client = GeminiEmbeddingClient(httpTransport = buildEmbeddingTransport())
        val manager = createManager(client = client)

        manager.recordMemory(
            category = "health",
            fact = "Pasien terkena migrain berat Oktober",
            subject = "user_health",
            predicate = "has_condition",
            source = "manual"
        )
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        assertEquals(1, manager.cachedVectorCount())

        // "kapan kepala pusing?" tidak berbagi satu pun token kata atau tri-gram dengan "migrain berat Oktober"
        val results = manager.searchHybridMemories(query = "kapan kepala pusing?", maxResults = 4)
        assertEquals(1, results.size)
        assertEquals("Pasien terkena migrain berat Oktober", results[0].factText)
        assertEquals("LAYER2_VECTOR", results[0].matchedLayer)
        assertTrue(results[0].semanticCosine > 0.90f)
    }

    @Test
    fun `RRF k=60 mengunggulkan fakta yang cocok di kedua layer dibanding yang hanya cocok di satu layer`() = runTest {
        val client = GeminiEmbeddingClient(httpTransport = buildEmbeddingTransport())
        val manager = createManager(client = client)

        // Fakta A: cocok secara makna (kopi/americano) DAN cocok leksikal "kopi" -> HYBRID_RRF (2 / 61)
        manager.recordMemory(
            category = "preference",
            fact = "Minuman favorit pengguna: kopi hitam tanpa gula",
            subject = "drink_primary",
            source = "manual"
        )
        // Fakta B: hanya cocok secara vektor (axis kopi), tanpa kata "kopi" -> LAYER2_VECTOR (<= 1 / 61)
        manager.recordMemory(
            category = "preference",
            fact = "Pengguna sering memesan double shot espresso",
            subject = "drink_secondary",
            source = "manual"
        )
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        val matches = manager.searchHybridMemories(query = "kopi americano favorit", maxResults = 5)
        assertTrue(matches.size >= 2)
        assertEquals("Minuman favorit pengguna: kopi hitam tanpa gula", matches[0].factText)
        assertEquals("HYBRID_RRF", matches[0].matchedLayer)
        assertEquals(2.0 / 61.0, matches[0].rrfScore, 1e-6)
        assertTrue(matches[0].rrfScore > matches[1].rrfScore)
    }

    @Test
    fun `deduplikasi SHA-256 mencegah pemanggilan API embedding ulang untuk konten identik`() = runTest {
        var documentEmbedCalls = 0
        val client = GeminiEmbeddingClient(
            httpTransport = buildEmbeddingTransport(onCall = { prompt ->
                if (prompt.startsWith("title:")) documentEmbedCalls++
            })
        )
        val dao = FakeGepyDao()
        val manager = createManager(dao = dao, client = client)

        manager.recordEpisodicVectorMemory("preference", "coffee", "Pengguna minum Americano dingin setiap pagi")
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()
        assertEquals(1, documentEmbedCalls)

        // Simpan ulang dengan huruf kapital & spasi berbeda -> SHA-256 sama -> 0 panggilan baru!
        manager.recordEpisodicVectorMemory("preference", "coffee", "  pengguna minum americano dingin setiap pagi  ")
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()
        assertEquals(1, documentEmbedCalls)
        assertEquals(1, manager.cachedVectorCount())
        assertEquals(1, dao.countMemoryVectors())
    }

    @Test
    fun `soft contradiction deleteMemory dan clearLocalMemory membersihkan vektor usang`() = runTest {
        val dao = FakeGepyDao()
        val client = GeminiEmbeddingClient(httpTransport = buildEmbeddingTransport())
        var now = 1_700_000_000_000L
        val manager = createManager(dao = dao, client = client, clockMs = { now })

        // 1. Soft contradiction pada single-entity subject (location_home)
        manager.recordMemory("location", "Pengguna tinggal di Jakarta", subject = "location_home", source = "manual")
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()
        assertEquals(1, manager.cachedVectorCount())
        assertEquals(1, dao.countMemoryVectors())

        now += 10_000L
        manager.recordMemory("location", "Pengguna tinggal di Bandung", subject = "location_home", source = "manual")
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        // Fakta lama di-supersede di DAO & vektornya ikut dihapus, hanya menyisakan fakta baru (Bandung)
        assertEquals(1, dao.supersededCalls.size)
        assertEquals(1, manager.cachedVectorCount())
        assertEquals(1, dao.countMemoryVectors())
        val searchBandung = manager.searchHybridMemories("Bandung", maxResults = 5)
        assertTrue(searchBandung.none { it.factText.contains("Jakarta") })
        assertTrue(searchBandung.any { it.factText.contains("Bandung") })

        // 2. deleteMemory menghapus vektor dari RAM & DAO
        assertTrue(manager.deleteMemory("Bandung"))
        advanceUntilIdle()
        assertEquals(0, manager.cachedVectorCount())
        assertEquals(0, dao.countMemoryVectors())

        // 3. clearLocalMemory membersihkan seluruh RAM & DAO
        manager.recordMemory("tech_profile", "Bahasa favorit pengguna Kotlin", subject = "tech_stack", source = "manual")
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()
        assertEquals(1, manager.cachedVectorCount())

        manager.clearLocalMemory()
        advanceUntilIdle()
        assertEquals(0, manager.cachedVectorCount())
        assertEquals(0, dao.countMemoryVectors())
        assertEquals(0, dao.countTemporalFacts())
    }

    @Test
    fun `mode offline mendegradasikan pencarian secara aman ke Layer 1 BM25 tanpa memblokir`() = runTest {
        var offline = false
        val client = GeminiEmbeddingClient(
            httpTransport = buildEmbeddingTransport(shouldThrowNetwork = { offline })
        )
        val manager = createManager(client = client)

        manager.recordMemory("tech_profile", "Bahasa koding favorit pengguna Kotlin Compose", source = "manual")
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        // Putus jaringan sebelum melakukan pencarian
        offline = true
        val results = manager.searchHybridMemories(query = "ngoding kotlin", maxResults = 5)
        assertTrue(results.isNotEmpty())
        assertEquals("LAYER1_BM25", results[0].matchedLayer)
        assertTrue(results[0].factText.contains("Kotlin"))
    }

    @Test
    fun `kapasitas RAM vektor dibatasi ketat maksimal 1000 entri (~3 MB) untuk Galaxy A06`() = runTest {
        val client = GeminiEmbeddingClient(httpTransport = buildEmbeddingTransport())
        val manager = createManager(client = client)

        for (i in 1..1015) {
            manager.recordEpisodicVectorMemory(
                category = "episodic",
                subject = "log_$i",
                contentText = "Catatan percakapan harian unik nomor $i dengan konteks panjang",
                importance = if (i <= 30) 2 else 7
            )
        }
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        assertEquals(UserMemoryManager.MAX_VECTOR_RAM_CACHE, manager.cachedVectorCount())
        val stats = manager.getVectorMemoryStats()
        assertEquals(1000, stats.maxRamVectors)
        assertEquals(2500, stats.maxDbVectors)
        assertEquals(3000, stats.estimatedVectorRamKb)
    }

    @Test
    fun `Room v5 menyinkronkan factId memangkas tabel DB ke 2500 baris dan memulihkan RAM saat start ulang`() = runTest {
        val dao = FakeGepyDao()
        val client = GeminiEmbeddingClient(httpTransport = buildEmbeddingTransport())
        val manager = createManager(dao = dao, client = client)

        // 1. Rekam 1 fakta via Room DAO -> factId DB harus tersinkron dan vektor disimpan ke tabel DB
        manager.recordMemory("pet", "Kucing pengguna bernama Miko", subject = "pet_cat", source = "manual")
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        assertEquals(1, dao.temporalFacts.size)
        val dbFact = dao.temporalFacts.first()
        assertTrue(dbFact.id > 0L)
        val dbVec = dao.findVectorByFactId(dbFact.id)
        assertNotNull(dbVec)
        assertEquals("gemini-embedding-2", dbVec!!.modelId)
        assertEquals(768, dbVec.dimensions)

        // 2. Bangun manager baru yang membaca ulang dari Room DB yang sama
        val coldRestartManager = createManager(dao = dao, client = client)
        coldRestartManager.initializeTemporalStore()
        advanceUntilIdle()
        coldRestartManager.flushPendingVectorIndexing()
        assertEquals(1, coldRestartManager.cachedVectorCount())

        val ctx = coldRestartManager.buildHybridReActContext("kucing miko", maxFacts = 4)
        assertNotNull(ctx)
        assertTrue(ctx!!.contains("Miko"))

        // 3. Isi 2510 vektor episodik ke DB -> pruneExcessVectors menjaga batas <= 2500 baris DB & <= 1000 RAM
        val nowMs = 1_700_000_000_000L
        for (i in 1..2510) {
            val content = "Memori episodik tersimpan ke-$i pada pengujian kapasitas Galaxy A06"
            dao.upsertMemoryVector(
                MemoryVectorEntity(
                    category = "episodic",
                    subject = "batch_$i",
                    contentText = content,
                    contentHash = GeminiEmbeddingClient.sha256Hex(content),
                    embeddingBlob = GeminiEmbeddingClient.encodeFloat32LittleEndian(FloatArray(768) { 0.01f }),
                    modelId = "gemini-embedding-2",
                    dimensions = 768,
                    importance = 5,
                    createdAtMs = nowMs + i,
                    updatedAtMs = nowMs + i
                )
            )
        }
        manager.recordEpisodicVectorMemory(
            category = "episodic",
            subject = "batch_prune_trigger",
            contentText = "Memori episodik pemicu pemangkasan kapasitas 2500 baris pada Room v5",
            importance = 8
        )
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()
        assertEquals(UserMemoryManager.MAX_VECTOR_DB_ROWS, dao.countMemoryVectors())
        val finalStats = manager.getVectorMemoryStats()
        assertEquals(UserMemoryManager.MAX_VECTOR_DB_ROWS, finalStats.dbVectorCount)
    }

    @Test
    fun `A2 initializeTemporalStore mutex prevents duplicate backfill and indexes all facts beyond 10 and 20 caps via chunking`() = runTest {
        val dao = FakeGepyDao()
        val baseTime = 1_700_000_000_000L
        // Sisipkan 15 fakta ke DAO tanpa vektor (menguji backfill > 10 sekaligus pemanggilan paralel)
        for (i in 1..15) {
            dao.insertTemporalFact(
                TemporalMemoryFactEntity(
                    category = "preference",
                    subject = "backfill_$i",
                    predicate = "likes",
                    objectValue = "menu_$i",
                    factText = "Fakta backfill unik nomor $i tanpa batas sepuluh",
                    confidence = 1.0f,
                    validFromMs = baseTime + i,
                    isActive = true,
                    createdAtMs = baseTime + i,
                    updatedAtMs = baseTime + i
                )
            )
        }

        var embedCalls = 0
        val client = GeminiEmbeddingClient(
            httpTransport = buildEmbeddingTransport(onCall = { prompt ->
                if (prompt.startsWith("title:")) embedCalls++
            })
        )
        val manager = createManager(dao = dao, client = client, clockMs = { baseTime + 50_000L })

        // Panggil initializeTemporalStore() 5x bersamaan -> Mutex memastikan backfill hanya berjalan 1x
        coroutineScope {
            repeat(5) {
                launch { manager.initializeTemporalStore() }
            }
        }
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        assertEquals(15, embedCalls)
        assertEquals(15, dao.countMemoryVectors())

        // Import vault dengan 25 fakta baru -> tidak terpotong batas lama take(20)!
        val importFacts = (1..25).map { idx ->
            TemporalMemoryFactEntity(
                category = "career",
                subject = "import_subj_$idx",
                predicate = "note",
                objectValue = "val_$idx",
                factText = "Catatan vault impor ke-$idx melampaui dua puluh",
                confidence = 1.0f,
                validFromMs = baseTime + 60_000L + idx,
                isActive = true,
                createdAtMs = baseTime + 60_000L + idx,
                updatedAtMs = baseTime + 60_000L + idx
            )
        }
        val vault = SovereignBackupManager().createVaultBackup(
            facts = importFacts,
            userDisplayName = "",
            appVersion = "6.6.0",
            exportedAtMs = baseTime + 70_000L
        )
        val importRes = manager.importVaultBackup(vault)
        assertTrue(importRes is com.teknologigawai.gepy.core.memory.backup.VaultImportResult.Success)
        advanceUntilIdle()
        manager.flushPendingVectorIndexing()

        assertEquals(40, embedCalls)
        assertEquals(40, dao.countMemoryVectors())
    }

    @Test
    fun `A2 searchHybridMemories recalls SQLite vector rows 1001 to 2500 via paging without exceeding 1000 vectors in RAM`() = runTest {
        val dao = FakeGepyDao()
        val nowMs = 1_700_000_000_000L

        // Masukkan 1010 vektor bernilai importance tinggi (7) agar menduduki 1000 slot top RAM cache,
        // ditambah 1 fakta khusus #1011 berimportance rendah (2) yang berada di halaman SQLite > 1000!
        for (i in 1..1010) {
            val content = "Catatan arsip rutin nomor $i pada sistem"
            val vec = semanticVectorFor(content).map { it.toFloat() }.toFloatArray()
            dao.upsertMemoryVector(
                MemoryVectorEntity(
                    category = "episodic",
                    subject = "archive_$i",
                    contentText = content,
                    contentHash = GeminiEmbeddingClient.sha256Hex(content),
                    embeddingBlob = GeminiEmbeddingClient.encodeFloat32LittleEndian(vec),
                    modelId = "gemini-embedding-2",
                    dimensions = 768,
                    importance = 7,
                    createdAtMs = nowMs + i,
                    updatedAtMs = nowMs + i
                )
            )
        }
        val targetFact = "Dokumen tersembunyi luar RAM tentang gejala migrain berkepanjangan"
        val targetVec = semanticVectorFor(targetFact).map { it.toFloat() }.toFloatArray()
        dao.upsertMemoryVector(
            MemoryVectorEntity(
                category = "health",
                subject = "outside_ram_health",
                contentText = targetFact,
                contentHash = GeminiEmbeddingClient.sha256Hex(targetFact),
                embeddingBlob = GeminiEmbeddingClient.encodeFloat32LittleEndian(targetVec),
                modelId = "gemini-embedding-2",
                dimensions = 768,
                importance = 2,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )

        val client = GeminiEmbeddingClient(httpTransport = buildEmbeddingTransport())
        val coldManager = createManager(dao = dao, client = client, clockMs = { nowMs + 5_000L })
        coldManager.initializeTemporalStore()
        advanceUntilIdle()

        // RAM cache tetap dijaga <= 1000 (~3 MB) untuk Galaxy A06
        assertEquals(UserMemoryManager.MAX_VECTOR_RAM_CACHE, coldManager.cachedVectorCount())

        // Pencarian semantik tanpa kesamaan kata tetap menemukan baris DB ke-1011 lewat paging SQLite!
        val results = coldManager.searchHybridMemories(query = "kapan sakit kepala terjadi?", maxResults = 5)
        assertTrue(
            "Expected out-of-RAM vector row to be recalled via Room paging, got: ${results.map { it.factText }}",
            results.any { it.factText == targetFact && it.matchedLayer == "LAYER2_VECTOR" }
        )
        assertEquals(UserMemoryManager.MAX_VECTOR_RAM_CACHE, coldManager.cachedVectorCount())
    }
}
