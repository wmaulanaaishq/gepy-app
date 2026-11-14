package com.teknologigawai.gepy.core.memory

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalKnowledgeGraphTest {

    private val zone: ZoneId = ZoneId.of("Asia/Jakarta")
    private val baseMs: Long = ZonedDateTime.of(2026, 11, 8, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `extractTriple mengenali pekerjaan sebagai triplet bernilai tunggal`() {
        val triple = TemporalKnowledgeGraph.extractTriple(
            factText = "Pengguna bekerja di PT Teknologi Nusantara",
            nowMs = baseMs,
            zoneId = zone
        )
        assertEquals("work", triple.category)
        assertEquals("user_work", triple.subject)
        assertEquals("works_at", triple.predicate)
        assertEquals("PT Teknologi Nusantara", triple.objectValue)
        assertTrue(triple.isSingleValued)
        assertNull(triple.validUntilMs)
    }

    @Test
    fun `penanda waktu hari ini dan besok menghasilkan batas kedaluwarsa`() {
        val today = TemporalKnowledgeGraph.extractTriple("Hari ini saya lembur di kantor", nowMs = baseMs, zoneId = zone)
        val endToday = ZonedDateTime.of(2026, 11, 8, 23, 59, 59, 0, zone).toInstant().toEpochMilli()
        assertEquals(endToday, today.validUntilMs)

        val tomorrow = TemporalKnowledgeGraph.extractTriple("Besok jam 9 ada rapat tim", nowMs = baseMs, zoneId = zone)
        val endTomorrow = ZonedDateTime.of(2026, 11, 9, 23, 59, 59, 0, zone).toInstant().toEpochMilli()
        assertEquals("schedule", tomorrow.category)
        assertEquals("has_schedule", tomorrow.predicate)
        assertEquals(endTomorrow, tomorrow.validUntilMs)

        val explicit = TemporalKnowledgeGraph.extractTriple("Catatan sementara", validHours = 3, nowMs = baseMs, zoneId = zone)
        assertEquals(baseMs + 3 * 3_600_000L, explicit.validUntilMs)
    }

    @Test
    fun `soft contradiction menyimpan riwayat pekerjaan lama dan timeline menjawab masa lalu`() = runBlocking {
        var clock = baseMs
        val manager = UserMemoryManager(clockMsProvider = { clock })
        manager.clearLocalMemory()

        manager.recordMemory(category = "", fact = "Pengguna bekerja di PT Alpha")
        clock += 86_400_000L
        manager.recordMemory(category = "", fact = "Pengguna bekerja di PT Beta")

        val all = manager.getAllTemporalFactsSnapshot()
        val alpha = all.first { it.factText.contains("Alpha") }
        val beta = all.first { it.factText.contains("Beta") }

        assertFalse("Fakta lama harus nonaktif", alpha.isActive)
        assertEquals(beta.id, alpha.supersededById)
        assertTrue(beta.isActive)

        val active = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue(active.contains("Pengguna bekerja di PT Beta"))
        assertFalse(active.contains("Pengguna bekerja di PT Alpha"))

        val timeline = manager.queryMemoryTimeline("bekerja")
        assertTrue(timeline.contains("PT Beta"))
        assertTrue(timeline.contains("Riwayat sebelumnya"))
        assertTrue(timeline.contains("PT Alpha"))
    }

    @Test
    fun `fakta berbatas waktu kedaluwarsa otomatis dan hilang dari konteks aktif`() = runBlocking {
        var clock = baseMs
        val manager = UserMemoryManager(clockMsProvider = { clock })
        manager.clearLocalMemory()

        manager.recordMemory(category = "", fact = "Hari ini ada rapat jam 13")
        assertTrue(manager.getSystemInstructionContext().contains("rapat jam 13"))
        assertEquals(1, manager.getTodaySchedules().size)

        clock += 2 * 86_400_000L
        assertFalse(manager.getSystemInstructionContext().contains("rapat jam 13"))
        assertTrue(manager.getTodaySchedules().isEmpty())
        val history = manager.getAllTemporalFactsSnapshot().first { it.factText.contains("rapat jam 13") }
        assertFalse(history.isActive)
    }

    @Test
    fun `rankRelevantFacts memprioritaskan fakta yang cocok dengan tujuan`() = runBlocking {
        val manager = UserMemoryManager(clockMsProvider = { baseMs })
        manager.clearLocalMemory()
        manager.recordMemory(category = "preference", fact = "Pengguna suka kopi hitam")
        manager.recordMemory(category = "location", fact = "Pengguna tinggal di Bandung")

        val ctx = manager.buildCompactReActContext("pesan kopi di aplikasi", maxFacts = 1)
        assertNotNull(ctx)
        assertTrue(ctx!!.contains("kopi hitam"))
    }

    @Test
    fun `extractTriple mengenali teman dan adik sebagai bernilai majemuk (isSingleValued = false)`() {
        val friendTriple = TemporalKnowledgeGraph.extractTriple(
            factText = "Teman dekat saya bernama Budi",
            nowMs = baseMs,
            zoneId = zone
        )
        assertEquals("friends", friendTriple.category)
        assertEquals("social_friends", friendTriple.subject)
        assertEquals("has_friend", friendTriple.predicate)
        assertFalse("Teman harus multi-valued (tidak boleh single-valued)", friendTriple.isSingleValued)

        val siblingTriple = TemporalKnowledgeGraph.extractTriple(
            factText = "Adik saya bernama Farah",
            nowMs = baseMs,
            zoneId = zone
        )
        assertEquals("family", siblingTriple.category)
        assertEquals("family_siblings", siblingTriple.subject)
        assertFalse("Adik kandung harus multi-valued agar tidak saling menimpa", siblingTriple.isSingleValued)

        val fatherTriple = TemporalKnowledgeGraph.extractTriple(
            factText = "Ayah saya bernama Herman",
            nowMs = baseMs,
            zoneId = zone
        )
        assertEquals("family", fatherTriple.category)
        assertEquals("family_father", fatherTriple.subject)
        assertTrue("Ayah kandung adalah entitas tunggal", fatherTriple.isSingleValued)
    }

    @Test
    fun `inferSubject membedakan panggilan nama pribadi dengan panggilan taksi atau polisi`() {
        val userNameTriple = TemporalKnowledgeGraph.extractTriple(
            factText = "Panggil aku Rizky saja",
            nowMs = baseMs,
            zoneId = zone
        )
        assertEquals("user_name", userNameTriple.subject)

        val taxiTriple = TemporalKnowledgeGraph.extractTriple(
            factText = "Tolong panggil taksi ke lobi",
            nowMs = baseMs,
            zoneId = zone
        )
        assertTrue("Panggilan taksi tidak boleh menjadi user_name", taxiTriple.subject != "user_name")

        val policeTriple = TemporalKnowledgeGraph.extractTriple(
            factText = "Ada adegan panggil polisi di film itu",
            nowMs = baseMs,
            zoneId = zone
        )
        assertTrue("Panggilan polisi di film tidak boleh menjadi user_name", policeTriple.subject != "user_name")
    }
}
