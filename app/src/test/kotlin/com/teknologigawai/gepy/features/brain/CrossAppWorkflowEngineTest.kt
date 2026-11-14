package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Test Komprehensif untuk [CrossAppWorkflowEngine] & Integrasi Deterministic Verification
 * pada **Gepy v5.9.5**.
 */
class CrossAppWorkflowEngineTest {

    private fun createStep(
        stepNumber: Int,
        action: AgentActionType,
        targetLabel: String,
        outcomeStatus: ActionOutcome = ActionOutcome.CORRECT,
        textValue: String? = null
    ): StepRecord = StepRecord(
        stepNumber = stepNumber,
        action = action,
        targetLabel = targetLabel,
        screenSignatureBefore = "sig_before_$stepNumber",
        screenSignatureAfter = "sig_after_$stepNumber",
        outcomeStatus = outcomeStatus,
        textValue = textValue
    )

    @Test
    fun testIsCrossAppCommand_detectsMultiAppInstructions() {
        // Kasus majemuk Maps -> WhatsApp
        val mapsWa = "Cari alamat Kopi Kenangan Tebet di Google Maps, lalu kirim alamatnya ke WhatsApp Mas Budi"
        assertTrue("Perintah Maps -> WA harus terdeteksi cross-app", CrossAppWorkflowEngine.isCrossAppCommand(mapsWa))

        // Kasus Shopee -> Telegram
        val shopeeTele = "Cek harga iPhone 15 di Shopee, salin infonya, kemudian kirim ke chat Telegram Siti"
        assertTrue("Perintah Shopee -> Telegram harus terdeteksi cross-app", CrossAppWorkflowEngine.isCrossAppCommand(shopeeTele))

        // Kasus Keep -> Instagram
        val keepIg = "Buka catatan materi di Keep, salin teksnya, lalu posting ke Instagram Story"
        assertTrue("Perintah Keep -> Instagram harus terdeteksi cross-app", CrossAppWorkflowEngine.isCrossAppCommand(keepIg))

        // Kasus Chrome -> WhatsApp
        val chromeWa = "Cari resep nasi goreng di Chrome terus kirim tautannya ke WhatsApp Ibu"
        assertTrue("Perintah Chrome -> WA harus terdeteksi cross-app", CrossAppWorkflowEngine.isCrossAppCommand(chromeWa))
    }

    @Test
    fun testIsCrossAppCommand_ignoresSingleAppInstructions() {
        val singleApp1 = "Buka WhatsApp dan chat Budi bilang halo"
        assertFalse("Perintah tunggal WA tidak boleh terdeteksi cross-app", CrossAppWorkflowEngine.isCrossAppCommand(singleApp1))

        val singleApp2 = "Putar lagu Bohemian Rhapsody di Spotify"
        assertFalse("Perintah tunggal Spotify tidak boleh terdeteksi cross-app", CrossAppWorkflowEngine.isCrossAppCommand(singleApp2))

        val emptyCmd = "   "
        assertFalse("Perintah kosong tidak boleh terdeteksi cross-app", CrossAppWorkflowEngine.isCrossAppCommand(emptyCmd))
    }

    @Test
    fun testParseWorkflowPlan_mapsToWhatsAppDecomposition() {
        val goal = "Cari alamat Kopi Kenangan Tebet di Google Maps, lalu kirim alamatnya ke WhatsApp Mas Budi"
        val plan = CrossAppWorkflowEngine.parseWorkflowPlan(goal)

        assertTrue("Rencana harus multi-stage", plan.isMultiStage)
        assertEquals("Harus ada 2 tahapan", 2, plan.stages.size)

        val stage1 = plan.stages[0]
        assertEquals("Tahap 1 index", 1, stage1.stageIndex)
        assertEquals("Tahap 1 package hint", "com.google.android.apps.maps", stage1.targetPackageHint)
        assertEquals("Tahap 1 intent", StageIntent.SEARCH_AND_EXTRACT, stage1.intent)
        assertEquals("Tahap 1 variabel target", "alamat", stage1.expectedOutputVariable)
        assertFalse("Tahap 1 belum selesai", stage1.isCompleted)

        val stage2 = plan.stages[1]
        assertEquals("Tahap 2 index", 2, stage2.stageIndex)
        assertEquals("Tahap 2 package hint", "com.whatsapp", stage2.targetPackageHint)
        assertEquals("Tahap 2 intent", StageIntent.COMPOSE_AND_SEND, stage2.intent)
        assertFalse("Tahap 2 belum selesai", stage2.isCompleted)
    }

    @Test
    fun testStageProgression_transitionsProperly() {
        val goal = "Cek harga iPhone 15 di Shopee kemudian kirim infonya ke Telegram Siti"
        val plan = CrossAppWorkflowEngine.parseWorkflowPlan(goal)

        assertEquals("Tahap awal index 0", 0, plan.currentStageIndex)
        assertEquals("Stage aktif adalah tahap 1", 1, plan.activeStage?.stageIndex)
        assertFalse("Belum semua selesai", plan.allStagesCompleted)

        // Tahap 1 selesai
        val hasNext = plan.markCurrentStageCompleted()
        assertTrue("Harus ada tahap selanjutnya", hasNext)
        assertEquals("Tahap aktif menjadi index 1", 1, plan.currentStageIndex)
        assertEquals("Stage aktif adalah tahap 2", 2, plan.activeStage?.stageIndex)
        assertTrue("Tahap 1 sudah selesai", plan.stages[0].isCompleted)
        assertFalse("Tahap 2 belum selesai", plan.stages[1].isCompleted)

        // Tahap 2 selesai
        val hasNext2 = plan.markCurrentStageCompleted()
        assertFalse("Tidak ada tahap lagi setelah tahap akhir", hasNext2)
        assertTrue("Seluruh tahap selesai", plan.allStagesCompleted)
    }

    @Test
    fun testBuildActiveStageGuidance_forbidsPrematureDoneOnStageOne() {
        val goal = "Cari alamat Kopi Kenangan Tebet di Google Maps lalu kirim ke WhatsApp Budi"
        val plan = CrossAppWorkflowEngine.parseWorkflowPlan(goal)

        val guidanceStage1 = plan.buildActiveStageGuidance(emptyMap())
        assertTrue("Harus ada larangan DONE", guidanceStage1.contains("DILARANG mengeluarkan action DONE"))
        assertTrue("Harus menyebut aplikasi Maps", guidanceStage1.contains("com.google.android.apps.maps"))

        // Pindah ke tahap 2
        plan.markCurrentStageCompleted()
        val scratchpad = mapOf("alamat" to "Jl. Tebet Raya No. 10")
        val guidanceStage2 = plan.buildActiveStageGuidance(scratchpad)
        assertTrue("Tahap 2 harus menyebut tahap akhir", guidanceStage2.contains("tahap akhir"))
        assertTrue("Tahap 2 harus mengindikasikan variabel alamat", guidanceStage2.contains("Jl. Tebet Raya No. 10"))
    }

    @Test
    fun testTaskPlannerAndVerifier_evaluateDeterministic_blocksPrematureDone() {
        val goal = "Cari alamat Kopi Kenangan Tebet di Google Maps, lalu kirim alamatnya ke WhatsApp Mas Budi"

        // Kasus 1: Baru di Maps, belum pernah buka WhatsApp -> Harus DITOLAK
        val memoryBeforeWa = ShortTermMemorySnapshot(
            recentSteps = listOf(
                createStep(1, AgentActionType.LAUNCH_APP, "Maps"),
                createStep(2, AgentActionType.CLIPBOARD_COPY, "Jl. Tebet Raya No. 10", textValue = "Jl. Tebet Raya No. 10")
            ),
            scratchpad = mapOf("alamat" to "Jl. Tebet Raya No. 10")
        )

        val verdict1 = TaskPlannerAndVerifier.evaluateDeterministic(
            userGoal = goal,
            currentPackage = "com.google.android.apps.maps",
            screenElements = emptyList(),
            memory = memoryBeforeWa
        )
        assertNotNull("Harus ada putusan deterministik", verdict1)
        assertFalse("Klaim DONE harus ditolak", verdict1!!.achieved)
        assertTrue("Alasan penolakan harus melarang DONE prematur", verdict1.missingReason.contains("DILARANG"))
        assertTrue("Alasan penolakan harus mengarahkan ke WhatsApp", verdict1.missingReason.contains("com.whatsapp"))

        // Kasus 2: Sudah buka WhatsApp tetapi belum menekan tombol Kirim -> Harus DITOLAK
        val memoryInWaWithoutSend = ShortTermMemorySnapshot(
            recentSteps = listOf(
                createStep(1, AgentActionType.LAUNCH_APP, "Maps"),
                createStep(2, AgentActionType.CLIPBOARD_COPY, "Jl. Tebet Raya No. 10", textValue = "Jl. Tebet Raya No. 10"),
                createStep(3, AgentActionType.LAUNCH_APP, "com.whatsapp"),
                createStep(4, AgentActionType.SET_TEXT, "Ketik pesan", textValue = "Jl. Tebet Raya No. 10")
            ),
            scratchpad = mapOf("alamat" to "Jl. Tebet Raya No. 10")
        )

        val verdict2 = TaskPlannerAndVerifier.evaluateDeterministic(
            userGoal = goal,
            currentPackage = "com.whatsapp",
            screenElements = emptyList(),
            memory = memoryInWaWithoutSend
        )
        assertNotNull(verdict2)
        assertFalse("Klaim DONE harus ditolak jika tombol kirim belum ditekan", verdict2!!.achieved)
        assertTrue("Harus menyebut tombol kirim/send", verdict2.missingReason.contains("Kirim/Send"))

        // Kasus 3: Sudah buka WhatsApp, ketik pesan, dan klik Kirim dengan sukses -> DITERIMA
        val memoryInWaWithSend = ShortTermMemorySnapshot(
            recentSteps = listOf(
                createStep(1, AgentActionType.LAUNCH_APP, "Maps"),
                createStep(2, AgentActionType.CLIPBOARD_COPY, "Jl. Tebet Raya No. 10", textValue = "Jl. Tebet Raya No. 10"),
                createStep(3, AgentActionType.LAUNCH_APP, "com.whatsapp"),
                createStep(4, AgentActionType.SET_TEXT, "Ketik pesan", textValue = "Jl. Tebet Raya No. 10"),
                createStep(5, AgentActionType.CONFIRM_AND_CLICK, "Kirim")
            ),
            scratchpad = mapOf("alamat" to "Jl. Tebet Raya No. 10")
        )

        val verdict3 = TaskPlannerAndVerifier.evaluateDeterministic(
            userGoal = goal,
            currentPackage = "com.whatsapp",
            screenElements = emptyList(),
            memory = memoryInWaWithSend
        )
        assertNotNull(verdict3)
        assertTrue("Klaim DONE harus disetujui setelah tahap 2 tuntas", verdict3!!.achieved)
    }
}
