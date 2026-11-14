package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyFastPathSkillTest {

    private val dummyMemory = ShortTermMemorySnapshot(
        recentSteps = emptyList(),
        scratchpad = emptyMap()
    )

    private fun createSkill(
        isEnabled: Boolean = true,
        speechCtx: SovereignSpeechContext = SovereignSpeechContext()
    ): SpotifyFastPathSkill {
        val bridge = AndroidSystemToolsBridge().apply {
            speechContextProvider = { speechCtx }
        }
        return SpotifyFastPathSkill(
            systemBridge = bridge,
            isEnabledProvider = { isEnabled },
            speechContextProvider = { speechCtx }
        )
    }

    @Test
    fun testCanHandleVoiceIntent_PlayIntents() {
        val skill = createSkill()

        assertTrue(skill.canHandleVoiceIntent("putar lagu Bernadya di spotify"))
        assertTrue(skill.canHandleVoiceIntent("tolong setel bohemian rhapsody"))
        assertTrue(skill.canHandleVoiceIntent("play musik di spotify"))
        assertTrue(skill.canHandleVoiceIntent("play musik"))
        assertTrue(skill.canHandleVoiceIntent("play lagu tulus di spotify"))
        assertTrue(skill.canHandleVoiceIntent("dengarkan coldplay di spotify"))
        assertTrue(skill.canHandleVoiceIntent("mainkan playlist koding"))
        assertTrue(skill.canHandleVoiceIntent("putar lagu tulus di laptop"))
        assertTrue(skill.canHandleVoiceIntent("setel spotify di pc"))
        assertTrue(skill.canHandleVoiceIntent("putar lagu wave to earth seasons"))
        assertTrue(skill.canHandleVoiceIntent("putar lagu seasons di youtube"))
        assertTrue(skill.canHandleVoiceIntent("mainkan tulus di yt music"))
    }

    @Test
    fun testCanHandleVoiceIntent_MediaControlIntents() {
        val skill = createSkill()

        assertTrue(skill.canHandleVoiceIntent("jeda lagu"))
        assertTrue(skill.canHandleVoiceIntent("pause musik spotify"))
        assertTrue(skill.canHandleVoiceIntent("lanjutkan lagu"))
        assertTrue(skill.canHandleVoiceIntent("lagu berikutnya"))
        assertTrue(skill.canHandleVoiceIntent("next lagu dong"))
        assertTrue(skill.canHandleVoiceIntent("lagu sebelumnya"))
        assertTrue(skill.canHandleVoiceIntent("stop lagu di spotify"))
    }

    @Test
    fun testCanHandleVoiceIntent_NegativeIntents() {
        val skill = createSkill()

        assertFalse(skill.canHandleVoiceIntent("kunci laptop"))
        assertFalse(skill.canHandleVoiceIntent("buka whatsapp di hp"))
        assertFalse(skill.canHandleVoiceIntent("cari resep rendang di google"))
        assertFalse(skill.canHandleVoiceIntent("buka vscode"))
        assertFalse(skill.canHandleVoiceIntent("setel timer 10 menit"))
        assertFalse(skill.canHandleVoiceIntent("setel volume 50%"))
        assertFalse(skill.canHandleVoiceIntent("setel mode getar"))
        assertFalse(skill.canHandleVoiceIntent("ini lagu apa yang lagi muter?"))
        assertFalse(skill.canHandleVoiceIntent("nyalain senter"))
    }

    @Test
    fun testCanHandleVoiceIntent_WhenDisabled_ReturnsFalse() {
        val disabledSkill = createSkill(isEnabled = false)
        assertFalse(disabledSkill.canHandleVoiceIntent("putar lagu bernadya di spotify"))
        assertFalse(disabledSkill.canHandleVoiceIntent("jeda lagu"))
    }

    @Test
    fun testProposeFastPathAction_PlaySongOnPhone() {
        val skill = createSkill()
        val action = skill.proposeFastPathAction(
            userGoal = "putar lagu Bernadya di spotify",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("Bernadya"))
        assertTrue(action?.thought.orEmpty().contains("Fast Path Spotify"))
    }

    @Test
    fun testProposeFastPathAction_MultiWordSongQuery() {
        val skill = createSkill()
        val action = skill.proposeFastPathAction(
            userGoal = "putar lagu bernadya satu bulan di spotify",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("bernadya satu bulan"))
    }

    @Test
    fun testProposeFastPathAction_WaveToEarthSeasons() {
        val skill = createSkill()
        val action = skill.proposeFastPathAction(
            userGoal = "putar lagu wave to earth seasons",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("wave to earth seasons"))
    }

    @Test
    fun testProposeFastPathAction_YouTube() {
        val skill = createSkill()
        val action = skill.proposeFastPathAction(
            userGoal = "putar lagu wave to earth seasons di youtube",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("YouTube"))
        assertTrue(action?.thought.orEmpty().contains("Fast Path YouTube"))
    }

    @Test
    fun testProposeFastPathAction_PlaySongOnLaptop() {
        val skill = createSkill()
        val action = skill.proposeFastPathAction(
            userGoal = "putar lagu Coldplay di laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.thought.orEmpty().contains("laptop"))
    }

    @Test
    fun testProposeFastPathAction_MediaControls() {
        val skill = createSkill()

        // 1. Pause
        val pauseAction = skill.proposeFastPathAction(
            userGoal = "jeda lagu spotify",
            foregroundPackage = "com.spotify.music",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNotNull(pauseAction)
        assertEquals(AgentActionType.DONE, pauseAction?.action)
        assertTrue(pauseAction?.completionTts.orEmpty().contains("dijeda"))

        // 2. Next
        val nextAction = skill.proposeFastPathAction(
            userGoal = "lagu berikutnya",
            foregroundPackage = "com.spotify.music",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNotNull(nextAction)
        assertEquals(AgentActionType.DONE, nextAction?.action)
        assertTrue(nextAction?.completionTts.orEmpty().contains("berikutnya"))

        // 3. Resume
        val resumeAction = skill.proposeFastPathAction(
            userGoal = "lanjutkan musik",
            foregroundPackage = "com.spotify.music",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNotNull(resumeAction)
        assertEquals(AgentActionType.DONE, resumeAction?.action)
        assertTrue(resumeAction?.completionTts.orEmpty().contains("dilanjutkan"))
    }

    @Test
    fun testProposeFastPathAction_Unmatched_ReturnsNull() {
        val skill = createSkill()
        val action = skill.proposeFastPathAction(
            userGoal = "kirim pesan ke Budi",
            foregroundPackage = "com.whatsapp",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNull(action)
    }

    @Test
    fun testProposeFastPathAction_PlayMusikDiSpotify_OpensSpotifyNotGenericMediaToggle() {
        val girlCtx = SovereignSpeechContext(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            ownerName = "Maulana",
            wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = false)
        )
        val skill = createSkill(speechCtx = girlCtx)

        for (goal in listOf("play musik di spotify", "play musik", "putar musik di spotify")) {
            val action = skill.proposeFastPathAction(
                userGoal = goal,
                foregroundPackage = "com.teknologigawai.gepy",
                screenElements = emptyList(),
                memory = dummyMemory
            )
            assertNotNull("Harus ditangani SpotifyFastPathSkill: $goal", action)
            assertEquals(AgentActionType.DONE, action?.action)
            assertTrue(action?.thought.orEmpty().contains("Fast Path Spotify"))
            assertFalse(
                "Tidak boleh masuk ke play_pause generik kaku!",
                action?.completionTts.orEmpty().contains("Status pemutaran media diubah")
            )
            assertFalse(
                "Query tidak boleh berisi sisa 'di spotify'!",
                action?.completionTts.orEmpty().contains("'di spotify'", ignoreCase = true)
            )
            assertEquals(
                SovereignNaturalSpeech.spotifyPlay(girlCtx, query = "", targetDevice = "phone"),
                action?.completionTts
            )
        }
    }

    @Test
    fun testProposeFastPathAction_PlayLaguTulusDiSpotify_AndPersonaVariants() {
        val girlCtx = SovereignSpeechContext(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            ownerName = "Maulana",
            wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = false)
        )
        val girlPrivacyCtx = girlCtx.copy(
            wellness = girlCtx.wellness.copy(isGuestPrivacyShieldActive = true)
        )
        val mentorCtx = SovereignSpeechContext(
            configuredMode = SovereignPersonaMode.HONEST_MENTOR,
            ownerName = "Maulana"
        )

        val girlAction = createSkill(speechCtx = girlCtx).proposeFastPathAction(
            userGoal = "play lagu tulus di spotify",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNotNull(girlAction)
        val girlTts = girlAction?.completionTts.orEmpty()
        assertTrue(girlTts.startsWith("[warmly] "))
        assertTrue(girlTts.contains("Maulana"))
        assertFalse(girlTts.contains("sayang", ignoreCase = true))
        assertTrue(girlTts.contains("tulus"))

        val privacyAction = createSkill(speechCtx = girlPrivacyCtx).proposeFastPathAction(
            userGoal = "play lagu tulus di spotify",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertFalse(privacyAction?.completionTts.orEmpty().contains("[warmly]"))
        assertFalse(privacyAction?.completionTts.orEmpty().contains("sayang"))

        val mentorAction = createSkill(speechCtx = mentorCtx).proposeFastPathAction(
            userGoal = "play lagu tulus di spotify",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertTrue(mentorAction?.completionTts.orEmpty().contains("Tetap fokus"))
        assertTrue(mentorAction?.completionTts.orEmpty().contains("tulus"))
    }
}
