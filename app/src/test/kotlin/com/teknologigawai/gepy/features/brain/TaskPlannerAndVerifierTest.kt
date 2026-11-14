package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.GoalVerificationVerdict
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.core.skills.InstagramSocialSkill
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlannerAndVerifierTest {

    private class MockBrain(
        private val verdict: GoalVerificationVerdict = GoalVerificationVerdict(achieved = true)
    ) : BrainPort {
        override suspend fun decideNextAction(
            userGoal: String,
            currentPackage: String,
            screenElements: List<PrunedUiElement>,
            memory: ShortTermMemorySnapshot,
            injectedDomainKnowledge: String?
        ): ReActActionModel = throw UnsupportedOperationException()

        override suspend fun verifyGoalCompletion(
            userGoal: String,
            currentPackage: String,
            screenElements: List<PrunedUiElement>,
            memory: ShortTermMemorySnapshot
        ): GoalVerificationVerdict = verdict
    }

    @Test
    fun `buildSubgoalChecklist menghasilkan rencana berurutan untuk Instagram DM`() {
        val checklist = TaskPlannerAndVerifier.buildSubgoalChecklist("Cari akun teknologi gawai di IG lalu kirim DM halo")
        assertEquals(5, checklist.size)
        assertTrue(checklist[0].contains("Instagram"))
        assertTrue(checklist[1].contains("tab Cari") || checklist[1].contains("profil"))
        assertTrue(checklist[2].contains("Kirim pesan") || checklist[2].contains("Message"))
        assertTrue(checklist[3].contains("Ketik isi pesan"))
        assertTrue(checklist[4].contains("Kirim/Send"))
    }

    @Test
    fun `buildSubgoalChecklist menghasilkan rencana pengiriman pesan WhatsApp`() {
        val checklist = TaskPlannerAndVerifier.buildSubgoalChecklist("Kirim pesan WA ke Budi bilang saya sudah sampai")
        assertEquals(3, checklist.size)
        assertTrue(checklist[0].contains("ruang obrolan"))
        assertTrue(checklist[1].contains("Ketik isi pesan"))
        assertTrue(checklist[2].contains("Kirim/Send"))
    }

    @Test
    fun `verifyBeforeDone menolak klaim DONE jika tombol kirim belum pernah diketuk pada pesan Instagram atau WA`() = runTest {
        val brain = MockBrain()
        val elements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "composer",
                text = "halo sudah sampai",
                isEditable = true
            )
        )
        val historyWithoutSend = listOf(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.SET_TEXT,
                targetLabel = "composer",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT,
                textValue = "halo sudah sampai"
            )
        )
        val memory = ShortTermMemorySnapshot(
            recentSteps = historyWithoutSend,
            scratchpad = emptyMap()
        )

        val verdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "Kirim DM Instagram ke Budi bilang halo",
            currentPackage = "com.instagram.android",
            screenElements = elements,
            memory = memory,
            brainPort = brain
        )

        assertFalse(verdict.achieved)
        assertTrue(verdict.missingReason.contains("tombol Kirim") || verdict.missingReason.contains("belum pernah ditekan"))
    }

    @Test
    fun `verifyBeforeDone menolak klaim DONE jika teks pesan masih tertinggal di kolom input`() = runTest {
        val brain = MockBrain()
        val elementsWithLingeringText = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "entry_edit",
                text = "halo apa kabar",
                isEditable = true
            )
        )
        val history = listOf(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.SET_TEXT,
                targetLabel = "entry_edit",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT,
                textValue = "halo apa kabar"
            ),
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetLabel = "Kirim",
                screenSignatureBefore = "sig2",
                screenSignatureAfter = "sig3",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val memory = ShortTermMemorySnapshot(recentSteps = history, scratchpad = emptyMap())

        val verdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "Kirim pesan WA ke Rudi",
            currentPackage = "com.whatsapp",
            screenElements = elementsWithLingeringText,
            memory = memory,
            brainPort = brain
        )

        assertFalse(verdict.achieved)
        assertTrue(verdict.missingReason.contains("tertahan") || verdict.missingReason.contains("belum terkirim"))
    }

    @Test
    fun `verifyBeforeDone menerima klaim DONE saat pengiriman terverifikasi tuntas`() = runTest {
        val brain = MockBrain()
        val elementsEmpty = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "entry_edit",
                text = "",
                isEditable = true
            )
        )
        val historySuccess = listOf(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.SET_TEXT,
                targetLabel = "entry_edit",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT,
                textValue = "halo saya sudah di lokasi"
            ),
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetLabel = "Kirim",
                screenSignatureBefore = "sig2",
                screenSignatureAfter = "sig3",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val memory = ShortTermMemorySnapshot(recentSteps = historySuccess, scratchpad = emptyMap())

        val verdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "Kirim pesan WA ke Budi",
            currentPackage = "com.whatsapp",
            screenElements = elementsEmpty,
            memory = memory,
            brainPort = brain
        )

        assertTrue(verdict.achieved)
    }

    @Test
    fun `buildSubgoalChecklist menghasilkan rencana posting Instagram Story`() {
        val checklist = TaskPlannerAndVerifier.buildSubgoalChecklist(
            "Buka Instagram lalu Post the selected photo to Instagram Story without asking for verification"
        )
        assertEquals(5, checklist.size)
        assertTrue(checklist[0].contains("Instagram"))
        assertTrue(checklist[1].contains("Cerita") || checklist[1].contains("kamera"))
        assertTrue(checklist[3].contains("Cerita Anda") || checklist[3].contains("Bagikan"))
    }

    @Test
    fun `verifyBeforeDone menolak jika tombol Cerita Anda atau Bagikan belum pernah diketuk pada posting Story`() = runTest {
        val brain = MockBrain()
        val elements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "story_editor",
                text = "Tulis teks cerita...",
                isEditable = true
            )
        )
        val historyWithoutShare = listOf(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.CLICK,
                targetLabel = "Pilih foto galeri",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val memory = ShortTermMemorySnapshot(recentSteps = historyWithoutShare, scratchpad = emptyMap())

        val verdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "Buka Instagram lalu Post the selected photo to Instagram Story without asking for verification.",
            currentPackage = "com.instagram.android",
            screenElements = elements,
            memory = memory,
            brainPort = brain
        )

        assertFalse(verdict.achieved)
        assertTrue(verdict.missingReason.contains("Cerita Anda") || verdict.missingReason.contains("Bagikan"))
    }

    @Test
    fun `verifyBeforeDone menerima klaim DONE saat posting Story Instagram terverifikasi lewat penutupan editor (Dismissal as Proof)`() = runTest {
        val brain = MockBrain(GoalVerificationVerdict(achieved = false, missingReason = "Harusnya tidak terpanggil"))
        // Layar saat ini adalah Beranda Instagram (Feed), bukan Story Editor
        val feedElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "action_bar_title",
                text = "Instagram"
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "feed_post_like",
                text = "Suka"
            )
        )
        val historyWithShare = listOf(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.CLICK,
                targetLabel = "Pilih foto",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT
            ),
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetLabel = "Cerita Anda",
                screenSignatureBefore = "sig2",
                screenSignatureAfter = "sig3",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val memory = ShortTermMemorySnapshot(recentSteps = historyWithShare, scratchpad = emptyMap())

        val verdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "Buka Instagram lalu Post the selected photo to Instagram Story without asking for verification.",
            currentPackage = "com.instagram.android",
            screenElements = feedElements,
            memory = memory,
            brainPort = brain
        )

        // Verifikasi deterministik harus langsung achieved = true tanpa memanggil mock brain
        assertTrue(verdict.achieved)
    }

    @Test
    fun `buildSubgoalChecklist menghasilkan rencana khusus membagikan postingan orang lain ke cerita`() {
        val checklist = TaskPlannerAndVerifier.buildSubgoalChecklist(
            "membagikan ke story dari postingan orang lain"
        )
        assertEquals(5, checklist.size)
        assertTrue(checklist[0].contains("postingan/reel"))
        assertTrue(checklist[1].contains("Bagikan"))
        assertTrue(checklist[2].contains("Tambahkan postingan ke cerita Anda"))
        assertTrue(checklist[3].contains("Cerita Anda"))
        assertTrue(checklist[4].contains("SELESAI 100%") && checklist[4].contains("DONE"))
    }

    @Test
    fun `membagikan ke story dari postingan orang lain terverifikasi saat layar kembali ke postingan awal meskipun goal tidak menyebut instagram eksplisit`() = runTest {
        val brain = MockBrain(GoalVerificationVerdict(achieved = false, missingReason = "Harusnya tidak terpanggil"))
        // Layar saat ini adalah postingan Reel Instagram (terdapat reel_viewer tapi BUKAN kontrol editor)
        val reelElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "reel_viewer_title",
                viewResourceId = "com.instagram.android:id/reel_viewer_title",
                text = "Video Reel Orang Lain"
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "button_share",
                viewResourceId = "com.instagram.android:id/button_share",
                text = "Bagikan"
            )
        )
        val historyStoryShare = listOf(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.CLICK,
                targetLabel = "Bagikan",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT
            ),
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetLabel = "Tambahkan postingan ke cerita Anda",
                screenSignatureBefore = "sig2",
                screenSignatureAfter = "sig3",
                outcomeStatus = ActionOutcome.CORRECT
            ),
            StepRecord(
                stepNumber = 3,
                action = AgentActionType.CLICK,
                targetLabel = "Cerita Anda",
                screenSignatureBefore = "sig3",
                screenSignatureAfter = "sig4",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val memory = ShortTermMemorySnapshot(recentSteps = historyStoryShare, scratchpad = emptyMap())

        val verdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "membagikan ke story dari postingan orang lain",
            currentPackage = "com.instagram.android",
            screenElements = reelElements,
            memory = memory,
            brainPort = brain
        )

        assertTrue(verdict.achieved)
    }

    @Test
    fun `InstagramSocialSkill findFastPathAction langsung mengeluarkan DONE saat editor tertutup setelah klik Cerita Anda`() {
        val skill = InstagramSocialSkill()
        val reelElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "reel_viewer_title",
                viewResourceId = "com.instagram.android:id/reel_viewer_title",
                text = "Video Reel"
            )
        )
        val historyStoryShare = listOf(
            StepRecord(
                stepNumber = 1,
                action = AgentActionType.CLICK,
                targetLabel = "Bagikan",
                screenSignatureBefore = "sig1",
                screenSignatureAfter = "sig2",
                outcomeStatus = ActionOutcome.CORRECT
            ),
            StepRecord(
                stepNumber = 2,
                action = AgentActionType.CLICK,
                targetLabel = "Tambahkan postingan ke cerita Anda",
                screenSignatureBefore = "sig2",
                screenSignatureAfter = "sig3",
                outcomeStatus = ActionOutcome.CORRECT
            ),
            StepRecord(
                stepNumber = 3,
                action = AgentActionType.CLICK,
                targetLabel = "Cerita Anda",
                screenSignatureBefore = "sig3",
                screenSignatureAfter = "sig4",
                outcomeStatus = ActionOutcome.CORRECT
            )
        )
        val memory = ShortTermMemorySnapshot(recentSteps = historyStoryShare, scratchpad = emptyMap())

        val fastPath = skill.proposeFastPathAction(
            userGoal = "membagikan ke story dari postingan orang lain",
            foregroundPackage = "com.instagram.android",
            screenElements = reelElements,
            memory = memory
        )

        assertNotNull(fastPath)
        assertEquals(AgentActionType.DONE, fastPath!!.action)
        assertTrue(fastPath.completionTts!!.contains("berhasil dibagikan ke cerita"))
    }

    @Test
    fun `InstagramSocialSkill tidak memicu premature DONE saat baru mengetuk Tambahkan ke cerita atau sebelum caption diketik`() = runTest {
        val skill = InstagramSocialSkill()
        val brain = MockBrain(GoalVerificationVerdict(achieved = false, missingReason = "Belum selesai"))
        val goal = "buka ig newjeans cari postingan ke 6 lalu repost ke story dan berikan caption kalau kamu yang post story"
        val postFeedElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "row_feed_button_share",
                viewResourceId = "com.instagram.android:id/row_feed_button_share",
                text = "Bagikan"
            )
        )

        // Kasus A: Baru mengetuk "Tambahkan postingan ke cerita Anda" (transisi belum membuka editor)
        val stepsOnlyAdded = listOf(
            StepRecord(1, AgentActionType.CLICK, "Bagikan", "s1", "s2", ActionOutcome.CORRECT),
            StepRecord(2, AgentActionType.CLICK, "Tambahkan postingan ke cerita Anda", "s2", "s3", ActionOutcome.CORRECT)
        )
        val prematureFastPath = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.instagram.android",
            screenElements = postFeedElements,
            memory = ShortTermMemorySnapshot(recentSteps = stepsOnlyAdded, scratchpad = emptyMap())
        )
        org.junit.Assert.assertNull("Tidak boleh premature DONE hanya karena klik Tambahkan ke cerita", prematureFastPath)

        // Kasus B: Mengetuk "Cerita Anda" TANPA pernah mengetik caption padahal pengguna meminta caption
        val stepsWithoutCaption = stepsOnlyAdded + listOf(
            StepRecord(3, AgentActionType.CLICK, "Cerita Anda", "s3", "s4", ActionOutcome.CORRECT)
        )
        val fastPathMissingCaption = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.instagram.android",
            screenElements = postFeedElements,
            memory = ShortTermMemorySnapshot(recentSteps = stepsWithoutCaption, scratchpad = emptyMap())
        )
        org.junit.Assert.assertNull("Fast-path harus menahan DONE bila caption yang diminta belum diketik", fastPathMissingCaption)

        val verdictMissingCaption = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = goal,
            currentPackage = "com.instagram.android",
            screenElements = postFeedElements,
            memory = ShortTermMemorySnapshot(recentSteps = stepsWithoutCaption, scratchpad = emptyMap()),
            brainPort = brain
        )
        assertFalse(verdictMissingCaption.achieved)
        assertTrue(verdictMissingCaption.missingReason.contains("Caption", ignoreCase = true))

        // Kasus C: Caption sudah diketik via SET_TEXT di editor cerita, lalu "Cerita Anda" diklik dan editor tertutup
        val stepsCompleteWithCaption = stepsOnlyAdded + listOf(
            StepRecord(3, AgentActionType.CLICK, "Aa", "s3", "s4", ActionOutcome.CORRECT),
            StepRecord(4, AgentActionType.SET_TEXT, "story_text_input", "s4", "s5", ActionOutcome.CORRECT, textValue = "Direpost otomatis oleh Gepy ✨"),
            StepRecord(5, AgentActionType.CLICK, "Selesai", "s5", "s6", ActionOutcome.CORRECT),
            StepRecord(6, AgentActionType.CLICK, "Cerita Anda", "s6", "s7", ActionOutcome.CORRECT)
        )
        val validFastPath = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.instagram.android",
            screenElements = postFeedElements,
            memory = ShortTermMemorySnapshot(recentSteps = stepsCompleteWithCaption, scratchpad = emptyMap())
        )
        assertNotNull(validFastPath)
        assertEquals(AgentActionType.DONE, validFastPath!!.action)

        val validVerdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = goal,
            currentPackage = "com.instagram.android",
            screenElements = postFeedElements,
            memory = ShortTermMemorySnapshot(recentSteps = stepsCompleteWithCaption, scratchpad = emptyMap()),
            brainPort = brain
        )
        assertTrue(validVerdict.achieved)
    }
}
