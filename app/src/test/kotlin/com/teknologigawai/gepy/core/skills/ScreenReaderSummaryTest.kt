package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderSummaryTest {

    private val skill = ScreenReaderSummarySkill()

    private fun dummyEl(
        index: Int,
        text: String,
        nodeId: String = "n1",
        packageName: String = "com.whatsapp",
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isScrollable: Boolean = false,
        viewResourceId: String? = null
    ): PrunedUiElement {
        return PrunedUiElement(
            index = index,
            nodeId = nodeId,
            packageName = packageName,
            text = text,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            rect = PrunedUiElement.buildRect(0, 0, 100, 50),
            viewResourceId = viewResourceId
        )
    }

    @Test
    fun `mencocokkan voice intent pembaca layar secara tepat`() {
        assertTrue(skill.canHandleVoiceIntent("Gepy tolong bacakan layar"))
        assertTrue(skill.canHandleVoiceIntent("ada apa di layar sekarang?"))
        assertTrue(skill.canHandleVoiceIntent("ringkas layar ini dong"))
        assertTrue(skill.canHandleVoiceIntent("baca pesan"))

        // Jangan mencocokkan jika intentnya mengirim/membalas pesan
        assertFalse(skill.canHandleVoiceIntent("kirim pesan ke Alif di WA"))
        assertFalse(skill.canHandleVoiceIntent("balas pesan Budi"))
    }

    @Test
    fun `merangkum ruang obrolan WhatsApp dengan nama penerima dan pesan terakhir`() {
        val elements = listOf(
            dummyEl(
                index = 1,
                text = "Budi Santoso",
                isClickable = true,
                viewResourceId = "com.whatsapp:id/conversation_contact_name"
            ),
            dummyEl(
                index = 2,
                text = "Nanti sore kita jadi kumpul jam 4?",
                viewResourceId = "com.whatsapp:id/message_text"
            ),
            dummyEl(
                index = 3,
                text = "[Hint] Ketik pesan",
                isClickable = true,
                isEditable = true,
                viewResourceId = "com.whatsapp:id/entry"
            )
        )

        val summary = skill.generateScreenSummary(
            foregroundPackage = "com.whatsapp",
            screenElements = elements
        )

        assertTrue(summary.contains("WhatsApp dengan Budi Santoso"))
        assertTrue(summary.contains("Nanti sore kita jadi kumpul jam 4?"))
    }

    @Test
    fun `merangkum daftar chat WhatsApp teratas`() {
        val elements = listOf(
            dummyEl(
                index = 1,
                text = "Keluarga Besar",
                isClickable = true,
                viewResourceId = "com.whatsapp:id/conversations_row_contact_name"
            ),
            dummyEl(
                index = 2,
                text = "Alif Praktikum",
                isClickable = true,
                viewResourceId = "com.whatsapp:id/conversations_row_contact_name"
            )
        )

        val summary = skill.generateScreenSummary(
            foregroundPackage = "com.whatsapp",
            screenElements = elements
        )

        assertTrue(summary.contains("daftar chat WhatsApp"))
        assertTrue(summary.contains("Keluarga Besar, Alif Praktikum"))
    }

    @Test
    fun `merangkum Layar Utama atau Launcher`() {
        val elements = listOf(
            dummyEl(
                index = 1,
                text = "WhatsApp",
                isClickable = true,
                packageName = "com.sec.android.app.launcher"
            ),
            dummyEl(
                index = 2,
                text = "Telepon",
                isClickable = true,
                packageName = "com.sec.android.app.launcher"
            ),
            dummyEl(
                index = 3,
                text = "Pesan",
                isClickable = true,
                packageName = "com.sec.android.app.launcher"
            )
        )

        val summary = skill.generateScreenSummary(
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = elements
        )

        assertTrue(summary.contains("Layar Utama"))
        assertTrue(summary.contains("WhatsApp, Telepon, Pesan"))
    }

    @Test
    fun `mengembalikan ReActActionModel bertipe DONE dengan completionTts langsung`() {
        val action = skill.proposeFastPathAction(
            userGoal = "bacakan layar",
            foregroundPackage = "com.whatsapp",
            screenElements = listOf(
                dummyEl(
                    index = 1,
                    text = "Ibu",
                    viewResourceId = "com.whatsapp:id/conversation_contact_name"
                ),
                dummyEl(
                    index = 2,
                    text = "Jangan lupa makan siang ya nak"
                ),
                dummyEl(
                    index = 3,
                    text = "[Hint] Ketik pesan",
                    isEditable = true,
                    viewResourceId = "com.whatsapp:id/entry"
                )
            ),
            memory = ShortTermMemorySnapshot(
                recentSteps = emptyList(),
                scratchpad = emptyMap()
            )
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action!!.action)
        val tts = action.completionTts
        assertNotNull(tts)
        assertTrue(tts!!.contains("WhatsApp dengan Ibu"))
        assertTrue(tts.contains("Jangan lupa makan siang ya nak"))
    }

    @Test
    fun `menyesuaikan gaya pembacaan layar sesuai persona Girlfriend Manja dan bebas kata Anda`() {
        val girlSkill = ScreenReaderSummarySkill(
            speechContextProvider = {
                SovereignSpeechContext(
                    configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
                    ownerName = "Maulana",
                    wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = false)
                )
            }
        )
        val summary = girlSkill.generateScreenSummary(
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = listOf(
                dummyEl(1, "WhatsApp", packageName = "com.sec.android.app.launcher", isClickable = true),
                dummyEl(2, "Spotify", packageName = "com.sec.android.app.launcher", isClickable = true)
            )
        )
        assertTrue(summary.startsWith("[softly] "))
        assertTrue(summary.contains("Maulana"))
        assertFalse(summary.contains("sayang", ignoreCase = true))
        assertTrue(summary.contains("Layar Utama"))
        assertFalse(summary.contains("Anda berada"))
    }
}
