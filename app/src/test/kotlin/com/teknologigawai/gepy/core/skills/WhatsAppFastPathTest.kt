package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppFastPathTest {

    private fun el(
        index: Int,
        text: String,
        resourceId: String? = null,
        nodeId: String = "s1_n1",
        pkg: String = "com.whatsapp",
        clickable: Boolean = true,
        editable: Boolean = false,
        scrollable: Boolean = false,
        x: Int = 40,
        y: Int = 100,
        w: Int = 200,
        h: Int = 80
    ): PrunedUiElement {
        return PrunedUiElement(
            index = index,
            nodeId = nodeId,
            viewResourceId = resourceId,
            packageName = pkg,
            text = text,
            isClickable = clickable,
            isEditable = editable,
            isScrollable = scrollable,
            rect = PrunedUiElement.buildRect(x, y, w, h)
        )
    }

    @Test
    fun `WhatsAppMessageIntent mengekstrak penerima dan isi pesan dengan bersih`() {
        val goal = "Kirim pesan WA ke grup 'Alif' dengan isi: Saya sedikit terlambat"
        val parsed = WhatsAppMessageIntent.tryParse(goal)
        assertNotNull(parsed)
        assertEquals("Alif", parsed!!.recipient)
        assertEquals("Saya sedikit terlambat", parsed.message)
    }

    @Test
    fun `menyelesaikan siklus 4 layar WhatsApp plus Disambiguasi plus HITL tanpa LLM`() {
        val skill = WhatsAppMessagingSkill()
        val goal = "Kirim pesan WA ke grup 'Alif' dengan isi: Saya sedikit terlambat"
        val emptyMem = ShortTermMemorySnapshot(
            recentSteps = emptyList(),
            scratchpad = emptyMap()
        )

        // 1. Di luar WhatsApp -> LAUNCH_APP
        val step1 = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = emptyMem
        )
        assertEquals(AgentActionType.LAUNCH_APP, step1?.action)
        assertEquals("WhatsApp", step1?.appName)

        // 2. Beranda obrolan tanpa baris Alif -> klik bilah pencarian
        val homeElements = listOf(
            el(
                index = 1,
                text = "Cari...",
                resourceId = "com.whatsapp:id/my_search_bar"
            ),
            el(
                index = 2,
                text = "Budi Santoso",
                resourceId = "com.whatsapp:id/conversations_row_contact_name"
            )
        )
        val step2 = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.whatsapp",
            screenElements = homeElements,
            memory = emptyMem
        )
        assertEquals(AgentActionType.CLICK, step2?.action)
        assertEquals(1, step2?.targetIndex)

        // 3a. Layar pencarian terbuka masih kosong -> SET_TEXT "Alif"
        val searchEmpty = listOf(
            el(
                index = 1,
                text = "[Hint] Cari...",
                resourceId = "com.whatsapp:id/search_input",
                editable = true
            )
        )
        val step3a = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.whatsapp",
            screenElements = searchEmpty,
            memory = emptyMem
        )
        assertEquals(AgentActionType.SET_TEXT, step3a?.action)
        assertEquals("Alif", step3a?.textValue)

        // 3b. Muncul 2 kontak mirip -> ASK_USER_DISAMBIGUATION
        val searchAmbiguous = listOf(
            el(
                index = 1,
                text = "Alif",
                resourceId = "com.whatsapp:id/search_input",
                editable = true
            ),
            el(
                index = 2,
                text = "Alif Praktikum",
                resourceId = "com.whatsapp:id/conversations_row_contact_name"
            ),
            el(
                index = 3,
                text = "Alif Ketua Kelas",
                resourceId = "com.whatsapp:id/conversations_row_contact_name"
            )
        )
        val step3b = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.whatsapp",
            screenElements = searchAmbiguous,
            memory = emptyMem
        )
        assertEquals(AgentActionType.ASK_USER_DISAMBIGUATION, step3b?.action)
        assertTrue(step3b?.options?.containsAll(listOf("Alif Praktikum", "Alif Ketua Kelas")) == true)

        // 3c. Setelah pengguna memilih "Alif Praktikum" -> CLICK baris indeks 2
        val memAfterDisambig = ShortTermMemorySnapshot(
            recentSteps = listOf(
                StepRecord(
                    stepNumber = 3,
                    action = AgentActionType.ASK_USER_DISAMBIGUATION,
                    targetLabel = "Alif Praktikum",
                    screenSignatureBefore = "s1",
                    screenSignatureAfter = "s1",
                    outcomeStatus = ActionOutcome.CORRECT
                )
            ),
            scratchpad = emptyMap()
        )
        val step3c = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.whatsapp",
            screenElements = searchAmbiguous,
            memory = memAfterDisambig
        )
        assertEquals(AgentActionType.CLICK, step3c?.action)
        assertEquals(2, step3c?.targetIndex)

        // 4a. Ruang obrolan terbuka, kolom pesan kosong -> SET_TEXT isi pesan
        val chatEmpty = listOf(
            el(
                index = 1,
                text = "Alif Praktikum",
                resourceId = "com.whatsapp:id/conversation_contact_name",
                clickable = false
            ),
            el(
                index = 2,
                text = "[Hint] Ketik pesan",
                resourceId = "com.whatsapp:id/entry",
                editable = true
            )
        )
        val step4a = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.whatsapp",
            screenElements = chatEmpty,
            memory = memAfterDisambig
        )
        assertEquals(AgentActionType.SET_TEXT, step4a?.action)
        assertEquals(2, step4a?.targetIndex)
        assertEquals("Saya sedikit terlambat", step4a?.textValue)

        // 4b. Kolom pesan sudah berisi teks -> WAJIB CONFIRM_AND_CLICK (HITL)
        val chatTyped = listOf(
            el(
                index = 1,
                text = "Alif Praktikum",
                resourceId = "com.whatsapp:id/conversation_contact_name",
                clickable = false
            ),
            el(
                index = 2,
                text = "Saya sedikit terlambat",
                resourceId = "com.whatsapp:id/entry",
                editable = true
            ),
            el(
                index = 3,
                text = "Kirim",
                resourceId = "com.whatsapp:id/send"
            )
        )
        val step4b = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.whatsapp",
            screenElements = chatTyped,
            memory = memAfterDisambig
        )
        assertEquals(AgentActionType.CONFIRM_AND_CLICK, step4b?.action)
        assertEquals(3, step4b?.targetIndex)

        // 4c. Setelah CONFIRM_AND_CLICK sukses & kolom kembali kosong -> DONE
        val memAfterSend = ShortTermMemorySnapshot(
            recentSteps = listOf(
                StepRecord(
                    stepNumber = 3,
                    action = AgentActionType.ASK_USER_DISAMBIGUATION,
                    targetLabel = "Alif Praktikum",
                    screenSignatureBefore = "s1",
                    screenSignatureAfter = "s1",
                    outcomeStatus = ActionOutcome.CORRECT
                ),
                StepRecord(
                    stepNumber = 5,
                    action = AgentActionType.CONFIRM_AND_CLICK,
                    targetIndex = 3,
                    targetLabel = "Kirim",
                    screenSignatureBefore = "s2",
                    screenSignatureAfter = "s3",
                    outcomeStatus = ActionOutcome.CORRECT
                )
            ),
            scratchpad = emptyMap()
        )
        val step4c = skill.proposeFastPathAction(
            userGoal = goal,
            foregroundPackage = "com.whatsapp",
            screenElements = chatEmpty,
            memory = memAfterSend
        )
        assertEquals(AgentActionType.DONE, step4c?.action)
    }

    @Test
    fun `validateBeforeAction pada WhatsApp dan Calendar meningkatkan CLICK menjadi CONFIRM_AND_CLICK`() = runBlocking {
        val waSkill = WhatsAppMessagingSkill()
        val calSkill = CalendarSchedulerSkill()

        val sendElements = listOf(
            el(index = 4, text = "Kirim", resourceId = "com.whatsapp:id/send")
        )
        val rawClickSend = ReActActionModel(
            thought = "Tekan kirim",
            reflectionOnLastStep = ReflectionStatus.CORRECT,
            action = AgentActionType.CLICK,
            targetIndex = 4
        )
        val validatedWa = waSkill.validateBeforeAction(rawClickSend, sendElements)
        assertEquals(AgentActionType.CONFIRM_AND_CLICK, validatedWa.action)

        val saveElements = listOf(
            el(index = 7, text = "Simpan", resourceId = "com.google.android.calendar:id/save")
        )
        val rawClickSave = ReActActionModel(
            thought = "Tekan simpan",
            reflectionOnLastStep = ReflectionStatus.CORRECT,
            action = AgentActionType.CLICK,
            targetIndex = 7
        )
        val validatedCal = calSkill.validateBeforeAction(rawClickSave, saveElements)
        assertEquals(AgentActionType.CONFIRM_AND_CLICK, validatedCal.action)
    }
}
