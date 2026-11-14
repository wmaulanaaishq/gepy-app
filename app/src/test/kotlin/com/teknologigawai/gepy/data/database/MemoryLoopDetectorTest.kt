package com.teknologigawai.gepy.data.database

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.core.ports.TelemetryEntry
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryLoopDetectorTest {

    private lateinit var memory: SqliteMemoryRepository

    @Before
    fun setUp() {
        memory = SqliteMemoryRepository(dao = null, historyLimit = 8)
    }

    @Test
    fun `membatasi ring buffer maksimal 8 langkah terbaru`() {
        for (i in 1..11) {
            memory.recordStep(
                StepRecord(
                    stepNumber = i,
                    action = AgentActionType.CLICK,
                    targetIndex = i,
                    targetLabel = "Elemen $i",
                    screenSignatureBefore = "sig_${i - 1}",
                    screenSignatureAfter = "sig_$i",
                    outcomeStatus = ActionOutcome.CORRECT
                )
            )
        }

        val snapshot = memory.buildSnapshot()
        assertEquals(8, snapshot.recentSteps.size)
        assertEquals(4, snapshot.recentSteps.first().stepNumber)
        assertEquals(11, snapshot.recentSteps.last().stepNumber)
    }

    @Test
    fun `menyimpan dan mempertahankan Scratchpad Memory lintas aplikasi`() {
        memory.saveScratchpadNote(
            "jadwal_kalender",
            "Rapat Proyek Gepy pukul 14:00 WIB"
        )
        assertEquals(
            "Rapat Proyek Gepy pukul 14:00 WIB",
            memory.readScratchpadNote("jadwal_kalender")
        )

        val snapshot = memory.buildSnapshot()
        assertTrue(snapshot.scratchpad["jadwal_kalender"]?.contains("14:00 WIB") == true)

        memory.resetSession(clearScratchpad = true)
        assertTrue(memory.getScratchpadNotes().isEmpty())
    }

    @Test
    fun `memicu peringatan pemutus Infinite Loop setelah 2 aksi INEFFECTIVE identik`() {
        memory.recordStep(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.CLICK,
                targetIndex = 3,
                targetLabel = "Cari",
                screenSignatureBefore = "sig_same",
                screenSignatureAfter = "sig_same",
                outcomeStatus = ActionOutcome.INEFFECTIVE
            )
        )
        val firstWarn = memory.buildSnapshot().loopWarningPrompt
        assertNotNull(firstWarn)
        assertTrue(firstWarn!!.contains("PERHATIAN:"))

        memory.recordStep(
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetIndex = 3,
                targetLabel = "Cari",
                screenSignatureBefore = "sig_same",
                screenSignatureAfter = "sig_same",
                outcomeStatus = ActionOutcome.INEFFECTIVE
            )
        )
        val loopWarning = memory.buildSnapshot().loopWarningPrompt
        assertNotNull(loopWarning)
        assertTrue(loopWarning!!.contains("PERINGATAN ANTI-LOOP:"))
    }

    @Test
    fun `memicu peringatan siklus loop jika tombol bagikan atau cerita diketuk berulang kali`() {
        memory.recordStep(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.CLICK,
                targetIndex = 2,
                targetLabel = "Bagikan",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        assertNull(memory.buildSnapshot().loopWarningPrompt)

        memory.recordStep(
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetIndex = 5,
                targetLabel = "Tambahkan ke cerita",
                screenSignatureBefore = "sig2",
                screenSignatureAfter = "sig3",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val cycleWarning = memory.buildSnapshot().loopWarningPrompt
        assertNotNull(cycleWarning)
        assertTrue(cycleWarning!!.contains("PERINGATAN SIKLUS LOOP:"))
    }

    @Test
    fun `mengurutkan kandidat disambiguasi berdasarkan histori preferensi kontak`() = runTest {
        memory.recordContactPreference(
            queryName = "alif",
            packageName = "com.whatsapp",
            resolvedDisplayName = "Alif Praktikum"
        )
        memory.recordContactPreference(
            queryName = "alif",
            packageName = "com.whatsapp",
            resolvedDisplayName = "Alif Praktikum"
        )

        val ranked = memory.rankDisambiguationOptions(
            queryName = "alif",
            packageName = "com.whatsapp",
            options = listOf("Alif Ketua Kelas", "Alif Praktikum")
        )

        assertEquals("Alif Praktikum", ranked.first())
    }

    @Test
    fun `Playbook Graph mengembalikan hit setelah minimal 2 keberhasilan dan menyimpan telemetri`() = runTest {
        val layoutHash = "com.whatsapp|com.whatsapp:id/my_search_bar#C"

        memory.saveSuccessfulTransition(
            packageName = "com.whatsapp",
            intentGoal = "Kirim pesan WA ke Alif bahwa rapat dimulai",
            resourceId = "com.whatsapp:id/my_search_bar",
            screenSignatureHash = layoutHash
        )

        val firstLookup = memory.findPlaybookTransition(
            packageName = "com.whatsapp",
            screenSignatureHash = layoutHash,
            intentGoal = "Kirim pesan WA ke Budi bahwa dokumen siap"
        )
        assertNull(firstLookup)

        memory.saveSuccessfulTransition(
            packageName = "com.whatsapp",
            intentGoal = "Kirim pesan WA ke Citra bahwa saya di jalan",
            resourceId = "com.whatsapp:id/my_search_bar",
            screenSignatureHash = layoutHash
        )

        val secondLookup = memory.findPlaybookTransition(
            packageName = "com.whatsapp",
            screenSignatureHash = layoutHash,
            intentGoal = "Kirim pesan WA ke Doni bahwa paket sampai"
        )
        assertNotNull(secondLookup)
        assertEquals("com.whatsapp:id/my_search_bar", secondLookup!!.targetResourceId)
        assertEquals(2, secondLookup.successCount)

        memory.logTelemetry(
            TelemetryEntry(
                sessionId = "s_test",
                stepNumber = 1,
                userGoal = "Kirim WA",
                packageName = "com.whatsapp",
                rawNodeCount = 120,
                prunedNodeCount = 8,
                pruningRatio = 0.89,
                llmLatencyMs = 0,
                totalStepLatencyMs = 450,
                actionType = "CLICK",
                verificationOutcome = "CORRECT",
                createdAt = 1000L
            )
        )
        val logs = memory.getRecentTelemetryLogs()
        assertEquals(1, logs.size)
        assertEquals("s_test", logs.single().sessionId)
    }

    @Test
    fun `memicu peringatan anti-loop saat aksi identik diulang meskipun berstatus CORRECT`() {
        memory.recordStep(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.CLICK,
                targetIndex = 4,
                targetLabel = "Tombol Cari",
                screenSignatureBefore = "sig_a",
                screenSignatureAfter = "sig_b",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        assertNull(memory.buildSnapshot().loopWarningPrompt)

        memory.recordStep(
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetIndex = 4,
                targetLabel = "Tombol Cari",
                screenSignatureBefore = "sig_b",
                screenSignatureAfter = "sig_c",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val warn = memory.buildSnapshot().loopWarningPrompt
        assertNotNull(warn)
        assertTrue(warn!!.contains("PERINGATAN ANTI-LOOP:"))
        assertTrue(warn.contains("2 kali berturut-turut"))
    }

    @Test
    fun `memicu peringatan osilasi saat terdeteksi pola bolak-balik A B A B`() {
        // Step 1: Click A
        memory.recordStep(StepRecord(1, AgentActionType.CLICK, targetLabel = "Tab Beranda", screenSignatureBefore = "s1", screenSignatureAfter = "s2", outcomeStatus = ActionOutcome.CORRECT))
        // Step 2: Click B
        memory.recordStep(StepRecord(2, AgentActionType.CLICK, targetLabel = "Tab Profil", screenSignatureBefore = "s2", screenSignatureAfter = "s1", outcomeStatus = ActionOutcome.CORRECT))
        // Step 3: Click A
        memory.recordStep(StepRecord(3, AgentActionType.CLICK, targetLabel = "Tab Beranda", screenSignatureBefore = "s1", screenSignatureAfter = "s2", outcomeStatus = ActionOutcome.CORRECT))
        // Step 4: Click B
        memory.recordStep(StepRecord(4, AgentActionType.CLICK, targetLabel = "Tab Profil", screenSignatureBefore = "s2", screenSignatureAfter = "s1", outcomeStatus = ActionOutcome.CORRECT))

        val warn = memory.buildSnapshot().loopWarningPrompt
        assertNotNull(warn)
        assertTrue(warn!!.contains("PERINGATAN SIKLUS OSCILLATION:"))
        assertTrue(warn.contains("Tab Beranda") && warn.contains("Tab Profil"))
    }

    @Test
    fun `memicu peringatan keletihan scroll setelah 3 kali scroll berturut-turut ke arah sama`() {
        memory.recordStep(StepRecord(1, AgentActionType.SCROLL, targetLabel = "SCROLL_DOWN", screenSignatureBefore = "s1", screenSignatureAfter = "s2", outcomeStatus = ActionOutcome.CORRECT))
        memory.recordStep(StepRecord(2, AgentActionType.SCROLL, targetLabel = "SCROLL_DOWN", screenSignatureBefore = "s2", screenSignatureAfter = "s3", outcomeStatus = ActionOutcome.CORRECT))
        assertNull(memory.buildSnapshot().loopWarningPrompt)

        memory.recordStep(StepRecord(3, AgentActionType.SCROLL, targetLabel = "SCROLL_DOWN", screenSignatureBefore = "s3", screenSignatureAfter = "s4", outcomeStatus = ActionOutcome.CORRECT))
        val warn = memory.buildSnapshot().loopWarningPrompt
        assertNotNull(warn)
        assertTrue(warn!!.contains("PERINGATAN SCROLL BERLEBIHAN:"))
    }

    @Test
    fun `memicu peringatan stagnasi setelah 3 langkah berturut-turut layar tidak berubah`() {
        memory.recordStep(StepRecord(1, AgentActionType.CLICK, targetLabel = "Icon 1", screenSignatureBefore = "same_hash", screenSignatureAfter = "same_hash", outcomeStatus = ActionOutcome.CORRECT))
        memory.recordStep(StepRecord(2, AgentActionType.CLICK, targetLabel = "Icon 2", screenSignatureBefore = "same_hash", screenSignatureAfter = "same_hash", outcomeStatus = ActionOutcome.CORRECT))
        memory.recordStep(StepRecord(3, AgentActionType.CLICK, targetLabel = "Icon 3", screenSignatureBefore = "same_hash", screenSignatureAfter = "same_hash", outcomeStatus = ActionOutcome.CORRECT))

        val warn = memory.buildSnapshot().loopWarningPrompt
        assertNotNull(warn)
        assertTrue(warn!!.contains("PERINGATAN LAYAR MANDET (STAGNATION):"))
    }
}
