package com.teknologigawai.gepy.core.memory

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite pengujian konkurensi **Temuan 4 (P2 Race Condition `clearLocalMemory()` vs
 * `initializeTemporalStore()` & lazy-load `searchHybridMemories()`)**.
 */
class UserMemoryManagerConcurrencyTest {

    private class ThreadSafeDelayedGepyDao(
        private val deleteDelayMs: Long = 60L
    ) : GepyDao {
        private val nextFactId = AtomicLong(1L)
        private val nextVectorId = AtomicLong(1L)
        val temporalFacts = CopyOnWriteArrayList<TemporalMemoryFactEntity>()
        val memoryVectors = CopyOnWriteArrayList<MemoryVectorEntity>()
        val deleteFactsCount = AtomicInteger(0)
        val deleteVectorsCount = AtomicInteger(0)

        override suspend fun lookupPackageAlias(alias: String): String? = null
        override suspend fun upsertPackageAlias(entity: AppPackageCacheEntity) {}
        override suspend fun findContactPreference(queryName: String, packageName: String, resolvedDisplayName: String): ContactDisambiguationEntity? = null
        override suspend fun upsertContactPreference(entity: ContactDisambiguationEntity) {}
        override suspend fun getContactPreferencesForQuery(queryName: String, packageName: String): List<ContactDisambiguationEntity> = emptyList()
        override suspend fun getTopKnowledgeForPackage(packageName: String): List<AppKnowledgeGraphEntity> = emptyList()
        override suspend fun getAllKnowledgeEntries(): List<AppKnowledgeGraphEntity> = emptyList()
        override suspend fun deleteKnowledgeById(id: Long) {}
        override suspend fun deleteAllKnowledge() {}
        override suspend fun disableSessionKnowledge(sessionId: String, nowMs: Long) {}
        override suspend fun findKnowledgeEntry(packageName: String, screenSignatureHash: String, subgoalIntent: String, targetResourceId: String, actionType: String): AppKnowledgeGraphEntity? = null
        override suspend fun upsertKnowledgeEntry(entity: AppKnowledgeGraphEntity) {}
        override suspend fun findBestPlaybookTransition(packageName: String, screenSignatureHash: String, subgoalIntent: String, minSuccessCount: Int): AppKnowledgeGraphEntity? = null
        override suspend fun insertTelemetryLog(entity: TelemetryLogEntity) {}
        override suspend fun getRecentTelemetryLogs(limit: Int): List<TelemetryLogEntity> = emptyList()
        override fun observeRecentTelemetryLogs(limit: Int): Flow<List<TelemetryLogEntity>> = emptyFlow()
        override suspend fun getEpisodicLessonsForPackage(packageName: String, limit: Int): List<EpisodicLessonEntity> = emptyList()
        override suspend fun findEpisodicLesson(packageName: String, lessonRule: String): EpisodicLessonEntity? = null
        override suspend fun upsertEpisodicLesson(entity: EpisodicLessonEntity) {}
        override suspend fun getWellnessLogForDate(dateKey: String): PersonalWellnessLogEntity? = null
        override suspend fun upsertWellnessLog(entity: PersonalWellnessLogEntity) {}

        override suspend fun insertTemporalFact(entity: TemporalMemoryFactEntity): Long {
            val id = if (entity.id > 0L) entity.id else nextFactId.getAndIncrement()
            temporalFacts.removeAll { it.id == id }
            temporalFacts.add(entity.copy(id = id))
            return id
        }

        override suspend fun insertTemporalFacts(entities: List<TemporalMemoryFactEntity>): List<Long> =
            entities.map { insertTemporalFact(it) }

        override suspend fun getActiveTemporalFacts(nowMs: Long, limit: Int): List<TemporalMemoryFactEntity> =
            temporalFacts.filter { it.isActive && (it.validUntilMs == null || it.validUntilMs > nowMs) }.take(limit)

        override suspend fun findActiveFactsBySubjectPredicate(subject: String, predicate: String): List<TemporalMemoryFactEntity> =
            temporalFacts.filter { it.subject == subject && it.predicate == predicate && it.isActive }

        override suspend fun supersedeTemporalFact(oldId: Long, newId: Long, nowMs: Long) {}
        override suspend fun expireOutdatedTemporalFacts(nowMs: Long): Int = 0
        override suspend fun searchTemporalTimeline(query: String, limit: Int): List<TemporalMemoryFactEntity> = emptyList()

        override suspend fun getAllTemporalFacts(limit: Int): List<TemporalMemoryFactEntity> =
            temporalFacts.take(limit)

        override fun observeAllTemporalFacts(limit: Int): Flow<List<TemporalMemoryFactEntity>> = emptyFlow()
        override suspend fun incrementTemporalFactHitCount(id: Long, nowMs: Long) {}
        override suspend fun countTemporalFacts(): Int = temporalFacts.size
        override suspend fun deleteTemporalFactById(id: Long) {
            temporalFacts.removeAll { it.id == id }
        }

        override suspend fun deleteAllTemporalFacts() {
            if (deleteDelayMs > 0L) delay(deleteDelayMs)
            temporalFacts.clear()
            deleteFactsCount.incrementAndGet()
        }

        override suspend fun upsertMemoryVector(entity: MemoryVectorEntity): Long {
            val id = if (entity.id > 0L) entity.id else nextVectorId.getAndIncrement()
            memoryVectors.removeAll { it.id == id }
            memoryVectors.add(entity.copy(id = id))
            return id
        }

        override suspend fun findVectorByFactId(factId: Long): MemoryVectorEntity? =
            memoryVectors.firstOrNull { it.factId == factId }

        override suspend fun findVectorByContentHash(contentHash: String): MemoryVectorEntity? =
            memoryVectors.firstOrNull { it.contentHash == contentHash }

        override suspend fun getRecentMemoryVectors(limit: Int): List<MemoryVectorEntity> =
            memoryVectors.take(limit)

        override suspend fun getAllVectorContentHashes(): List<String> =
            memoryVectors.map { it.contentHash }

        override suspend fun getMemoryVectorsPaged(limit: Int, offset: Int): List<MemoryVectorEntity> =
            memoryVectors.drop(offset).take(limit)

        override suspend fun countMemoryVectors(): Int = memoryVectors.size

        override suspend fun deleteVectorByFactId(factId: Long) {
            memoryVectors.removeAll { it.factId == factId }
        }

        override suspend fun deleteVectorsMatchingKeyword(keyword: String) {}
        override suspend fun pruneExcessVectors(keepCount: Int): Int = 0

        override suspend fun deleteAllMemoryVectors() {
            if (deleteDelayMs > 0L) delay(deleteDelayMs)
            memoryVectors.clear()
            deleteVectorsCount.incrementAndGet()
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

    private fun seedOldData(dao: ThreadSafeDelayedGepyDao, tag: String) {
        val vec = FloatArray(768).apply { this[0] = 1.0f }
        val blob = GeminiEmbeddingClient.encodeFloat32LittleEndian(vec)
        dao.temporalFacts.add(
            TemporalMemoryFactEntity(
                id = 100L,
                category = "health",
                subject = "pemilik",
                predicate = "alergi",
                objectValue = "udang $tag",
                factText = "Pengguna alergi udang $tag",
                confidence = 1.0f,
                validFromMs = 1_000L,
                validUntilMs = null,
                isActive = true,
                source = "test",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L
            )
        )
        dao.memoryVectors.add(
            MemoryVectorEntity(
                id = 200L,
                factId = 100L,
                category = "health",
                subject = "pemilik",
                contentText = "Pengguna alergi udang $tag",
                contentHash = GeminiEmbeddingClient.sha256Hex("Pengguna alergi udang $tag"),
                embeddingBlob = blob,
                modelId = "gemini-embedding-2",
                dimensions = 768,
                importance = 9,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L
            )
        )
    }

    private fun fakeEmbeddingClient(): GeminiEmbeddingClient {
        val arr = JSONArray().apply {
            put(1.0)
            for (i in 1 until 768) put(0.0)
        }
        val body = JSONObject().put("embedding", JSONObject().put("values", arr)).toString()
        return GeminiEmbeddingClient(
            httpTransport = EmbeddingHttpTransport { _, _, _ -> EmbeddingHttpResponse(200, body) }
        )
    }

    @Test
    fun `UserMemoryManagerClearAndInitRaceTest tidak pernah memuat ulang data lama saat clearLocalMemory disusul initializeTemporalStore segera`() = runBlocking {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            repeat(10) { round ->
                val dao = ThreadSafeDelayedGepyDao(deleteDelayMs = 40L)
                val manager = UserMemoryManager(
                    context = null,
                    gepyDao = dao,
                    scope = managerScope,
                    embeddingClient = fakeEmbeddingClient(),
                    embeddingKeyPoolProvider = { listOf("test_embed_key_race") }
                )

                seedOldData(dao, tag = "round_$round")
                manager.initializeTemporalStore()
                assertEquals(1, manager.getAllTemporalFactsSnapshot().size)
                assertEquals(1, manager.cachedVectorCount())

                // Panggil clearLocalMemory() asinkron lalu segera pacu initializeTemporalStore() & searchHybridMemories() berbarengan!
                manager.clearLocalMemory()
                val initJob = async(Dispatchers.Default) { manager.initializeTemporalStore() }
                val searchJob = async(Dispatchers.IO) { manager.searchHybridMemories("alergi udang") }

                initJob.await()
                val searchRes = searchJob.await()

                assertTrue(
                    "Pencarian selama/setelah clear tidak boleh memuat ulang vektor lama: $searchRes",
                    searchRes.isEmpty()
                )
                assertTrue(
                    "temporalFactsCache tidak boleh kembali terisi fakta usang dari SQLite pada round $round",
                    manager.getAllTemporalFactsSnapshot().isEmpty()
                )
                assertEquals(
                    "vectorCache tidak boleh kembali terisi vektor usang dari SQLite pada round $round",
                    0,
                    manager.cachedVectorCount()
                )
                assertEquals(0, dao.countTemporalFacts())
                assertEquals(0, dao.countMemoryVectors())
            }
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `UserMemoryManagerClearAndInitRaceTest tahan balapan paralel initialize clearLocalMemory clearLocalMemorySuspend dan search`() = runBlocking {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dao = ThreadSafeDelayedGepyDao(deleteDelayMs = 20L)
            val manager = UserMemoryManager(
                context = null,
                gepyDao = dao,
                scope = managerScope,
                embeddingClient = fakeEmbeddingClient(),
                embeddingKeyPoolProvider = { listOf("test_embed_key_stress") }
            )

            seedOldData(dao, tag = "stress_seed")
            manager.initializeTemporalStore()

            // Jalankan balapan paralel lintas dispatcher tanpa ada kebuntuan/deadlock atau stale resurrection setelah clear final
            val jobs = (1..12).map { idx ->
                launch(if (idx % 2 == 0) Dispatchers.Default else Dispatchers.IO) {
                    when (idx % 4) {
                        0 -> manager.clearLocalMemory()
                        1 -> manager.initializeTemporalStore()
                        2 -> manager.searchHybridMemories("alergi")
                        else -> manager.clearLocalMemorySuspend()
                    }
                }
            }
            jobs.forEach { it.join() }

            // Tutup dengan clearLocalMemorySuspend lalu initializeTemporalStore -> harus bersih 100%
            manager.clearLocalMemorySuspend()
            manager.initializeTemporalStore()

            assertTrue(manager.getAllTemporalFactsSnapshot().isEmpty())
            assertEquals(0, manager.cachedVectorCount())
            assertEquals(0, dao.countTemporalFacts())
            assertEquals(0, dao.countMemoryVectors())
            assertTrue(dao.deleteFactsCount.get() >= 1)
            assertTrue(dao.deleteVectorsCount.get() >= 1)
        } finally {
            managerScope.cancel()
        }
    }
}
