package com.teknologigawai.gepy.core.persona

import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.features.voice.VoiceAudioCacheManager
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignNaturalSpeechTest {

    private val girlCtx = SovereignSpeechContext(
        configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
        ownerName = "Maulana",
        wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = false)
    )

    private val girlPrivacyCtx = SovereignSpeechContext(
        configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
        ownerName = "Maulana",
        wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = true)
    )

    private val jarvisCtx = SovereignSpeechContext(
        configuredMode = SovereignPersonaMode.JARVIS_TACTICAL,
        ownerName = "Maulana",
        wellness = PersonalWellnessContext(ownerName = "Maulana")
    )

    private val mentorCtx = SovereignSpeechContext(
        configuredMode = SovereignPersonaMode.HONEST_MENTOR,
        ownerName = "Maulana",
        wellness = PersonalWellnessContext(ownerName = "Maulana")
    )

    @Test
    fun `output bersifat deterministik dan menghasilkan hash cache SHA-256 ElevenLabs yang identik untuk 0 credit reuse`() {
        val tempRoot: File = Files.createTempDirectory("gepy_natural_speech_cache").toFile()
        try {
            val cache = VoiceAudioCacheManager(rootDir = tempRoot)

            val firstPlay = SovereignNaturalSpeech.spotifyPlay(girlCtx, query = "", targetDevice = "phone")
            val secondPlay = SovereignNaturalSpeech.spotifyPlay(girlCtx, query = "", targetDevice = "phone")
            assertEquals(firstPlay, secondPlay)

            val firstOpen = SovereignNaturalSpeech.appOpened(jarvisCtx, "WhatsApp")
            val secondOpen = SovereignNaturalSpeech.appOpened(jarvisCtx, "WhatsApp")
            assertEquals(firstOpen, secondOpen)

            val hash1 = cache.computeClipHash(voiceId = "voice_1", text = firstPlay)
            val hash2 = cache.computeClipHash(voiceId = "voice_1", text = secondPlay)
            assertEquals(hash1, hash2)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `membedakan gaya bahasa per persona Girlfriend Manja Jarvis Taktis Mentor Jujur dan Adaptive`() {
        val girlPlay = SovereignNaturalSpeech.spotifyPlay(girlCtx, query = "", targetDevice = "phone")
        val jarvisPlay = SovereignNaturalSpeech.spotifyPlay(jarvisCtx, query = "", targetDevice = "phone")
        val mentorPlay = SovereignNaturalSpeech.spotifyPlay(mentorCtx, query = "", targetDevice = "phone")

        assertTrue(girlPlay.startsWith("[warmly] "))
        assertTrue(girlPlay.contains("Maulana"))
        assertFalse(girlPlay.contains("sayang", ignoreCase = true))
        assertTrue(girlPlay.contains("Spotify"))

        assertFalse(jarvisPlay.contains("[warmly]"))
        assertTrue(jarvisPlay.contains("Spotify"))

        assertTrue(mentorPlay.contains("Tetap fokus"))

        // Bila nama pemilik belum terisi, Girlfriend tetap ramah natural tanpa memanggil "sayang" dan tanpa spasi ganda
        val unnamedGirlCtx = SovereignSpeechContext(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            ownerName = ""
        )
        val unnamedPlay = SovereignNaturalSpeech.spotifyPlay(unnamedGirlCtx, query = "", targetDevice = "phone")
        assertEquals("[warmly] Oke, aku bukain Spotify terus play musiknya ya.", unnamedPlay)

        // Mode ADAPTIVE saat doomscrolling aktif otomatis bergeser ke HONEST_MENTOR
        val adaptiveDoomscrollingCtx = SovereignSpeechContext(
            configuredMode = SovereignPersonaMode.ADAPTIVE,
            ownerName = "Maulana",
            wellness = PersonalWellnessContext(
                ownerName = "Maulana",
                activeSocialScrollMinutes = 25,
                doomscrollLimitMinutes = 15
            )
        )
        val adaptiveApp = SovereignNaturalSpeech.appOpened(adaptiveDoomscrollingCtx, "Instagram")
        assertEquals(SovereignNaturalSpeech.appOpened(mentorCtx, "Instagram"), adaptiveApp)
        assertTrue(adaptiveApp.contains("Jangan kelamaan"))
    }

    @Test
    fun `Privacy Shield aktif menyembunyikan tag ekspresi intim dan menjaga kerahasiaan`() {
        val blockedPlay = SovereignNaturalSpeech.spotifyPlay(girlPrivacyCtx, query = "", targetDevice = "phone")
        val blockedCallEnd = SovereignNaturalSpeech.liveCallEnded(girlPrivacyCtx)
        val blockedNotif = SovereignNaturalSpeech.notificationsPrivacyBlocked(girlPrivacyCtx)

        assertFalse(blockedPlay.contains("[warmly]"))
        assertFalse(blockedPlay.contains("sayang", ignoreCase = true))
        assertTrue(blockedPlay.contains("Maulana"))
        assertFalse(blockedCallEnd.contains("[softly]"))
        assertFalse(blockedCallEnd.contains("sayang", ignoreCase = true))
        assertTrue(blockedNotif.contains("Privacy Shield"))
    }

    @Test
    fun `stripAudioTagsForDisplay membersihkan warmly softly untuk UI Chat tanpa menghapus sitasi`() {
        val ttsText = SovereignNaturalSpeech.spotifyPlay(girlCtx, query = "", targetDevice = "phone")
        val display = VoiceClauseChunker.stripAudioTagsForDisplay(ttsText)
        assertFalse(display.contains("[warmly]"))
        assertEquals("Oke Maulana, aku bukain Spotify terus play musiknya ya.", display)

        val citationText = "[softly] Besok cuacanya cerah berawan [1] menurut [BMKG]."
        val displayCitation = VoiceClauseChunker.stripAudioTagsForDisplay(citationText)
        assertEquals("Besok cuacanya cerah berawan [1] menurut [BMKG].", displayCitation)
    }

    @Test
    fun `seluruh kalimat fast path dan klip offline bebas frasa kaku atau korporat`() {
        val unnamedGirlCtx = SovereignSpeechContext(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            ownerName = ""
        )
        val contexts = listOf(girlCtx, girlPrivacyCtx, unnamedGirlCtx, jarvisCtx, mentorCtx)
        val forbiddenPhrases = listOf(
            "Anda",
            "Status pemutaran media diubah",
            "Panggilan diakhiri.",
            "Saya belum memiliki",
            "dikirim ke sistem",
            "  "
        )

        val samples = contexts.flatMap { ctx ->
            listOf(
                SovereignNaturalSpeech.spotifyPlay(ctx, "", "phone"),
                SovereignNaturalSpeech.spotifyPlay(ctx, "tulus", "phone"),
                SovereignNaturalSpeech.youtubePlay(ctx, "seasons"),
                SovereignNaturalSpeech.mediaControl(ctx, "pause", "phone"),
                SovereignNaturalSpeech.mediaControl(ctx, "next", "phone"),
                SovereignNaturalSpeech.appOpened(ctx, "WhatsApp"),
                SovereignNaturalSpeech.deviceStatusSummary(ctx, "Senin, 18:10", 85, false, "Wi-Fi", 70, "Normal"),
                SovereignNaturalSpeech.notificationsPrivacyBlocked(ctx),
                SovereignNaturalSpeech.memoryMissingWork(ctx),
                SovereignNaturalSpeech.memoryMissingRelation(ctx, "adik"),
                SovereignNaturalSpeech.memoryHealthList(ctx, "alergi seafood"),
                SovereignNaturalSpeech.memoryPrefList(ctx, "kopi hitam"),
                SovereignNaturalSpeech.memoryProfileSummary(ctx, "Maulana", "suka ngoding"),
                SovereignNaturalSpeech.memoryGeneralRecall(ctx, "tinggal di Sleman"),
                SovereignNaturalSpeech.memoryGeneralMissing(ctx, "hobi baru"),
                SovereignNaturalSpeech.memoryMissingScheduleOrNullCheck(ctx),
                SovereignNaturalSpeech.screenSummaryLauncher(ctx, listOf("WhatsApp", "Spotify")),
                SovereignNaturalSpeech.screenSummaryGeneric(ctx, "Pengaturan Sistem"),
                SovereignNaturalSpeech.whatsappSent(ctx, "Budi"),
                SovereignNaturalSpeech.whatsappDisambiguate(ctx, "Andi"),
                SovereignNaturalSpeech.whatsappConfirmPrompt(ctx, "Budi", "Halo"),
                SovereignNaturalSpeech.instagramStoryShared(ctx),
                SovereignNaturalSpeech.laptopOpenAppFallback(ctx, "vscode"),
                SovereignNaturalSpeech.laptopShutdownAskConfirm(ctx),
                SovereignNaturalSpeech.laptopShutdownConfirmExpired(ctx),
                SovereignNaturalSpeech.dailyBriefingMorning(ctx, "Senin, 10 November", "07:30", "rapat tim", 4200, 88, false),
                SovereignNaturalSpeech.dailyBriefingEvening(ctx, 4450, 12, 1, 55, false),
                SovereignNaturalSpeech.dailyBriefingDaytime(ctx, "13:00", "meeting produk", 3100, 15, 70),
                SovereignNaturalSpeech.gatekeeperGreeting(ctx),
                SovereignNaturalSpeech.orchestratorMaxSteps(ctx, 10),
                SovereignNaturalSpeech.orchestratorError(ctx, "jaringan putus"),
                SovereignNaturalSpeech.liveCallReconnecting(ctx, 2, 3),
                SovereignNaturalSpeech.liveCallEnded(ctx),
                SovereignNaturalSpeech.voicePreviewSample(ctx),
                SovereignNaturalSpeech.csiOwnerGreeting(ctx, ""),
                // Batch 1 + Batch 2 Method Verification
                SovereignNaturalSpeech.torchToggled(ctx, enabled = true, success = true),
                SovereignNaturalSpeech.torchToggled(ctx, enabled = false, success = true),
                SovereignNaturalSpeech.timerSet(ctx, durationLabel = "5 menit", label = "Masak Mie", success = true),
                SovereignNaturalSpeech.alarmsDisplayed(ctx, nextAlarmFormatted = "05:00", openedUi = true),
                SovereignNaturalSpeech.volumeAdjusted(ctx, streamLabelId = "media", percent = 50, directionLabel = null),
                SovereignNaturalSpeech.volumeError(ctx),
                SovereignNaturalSpeech.ringerModeChanged(ctx, modeLabel = "Getar", needsDndPermission = false),
                SovereignNaturalSpeech.ringerModeChanged(ctx, modeLabel = "Senyap", needsDndPermission = true),
                SovereignNaturalSpeech.dialerOpened(ctx, contactLabel = "Ibu", success = true),
                SovereignNaturalSpeech.emailComposerOpened(ctx, recipientLabel = "budi@contoh.com", success = true),
                SovereignNaturalSpeech.floatingPanelOpened(ctx, panelLabel = "Wi-Fi", success = true),
                SovereignNaturalSpeech.nowPlayingInfo(ctx, title = "Satu Bulan", artist = "Bernadya", appLabel = "Spotify", isPlaying = true),
                SovereignNaturalSpeech.nowPlayingEmpty(ctx, hasPermission = true),
                SovereignNaturalSpeech.nowPlayingEmpty(ctx, hasPermission = false),
                SovereignNaturalSpeech.notificationReplied(ctx, senderName = "Budi"),
                SovereignNaturalSpeech.notificationReplyConfirmPrompt(ctx, senderName = "Budi", replyMessage = "Oke siap"),
                SovereignNaturalSpeech.notificationDismissed(ctx, dismissedCount = 1, dismissedLabels = listOf("WhatsApp")),
                SovereignNaturalSpeech.notificationAccessRequired(ctx),
                SovereignNaturalSpeech.notificationTargetNotFound(ctx, targetQuery = "Budi"),
                SovereignNaturalSpeech.notificationsSafetyBlocked(ctx)
            )
        }

        for (line in samples) {
            for (forbidden in forbiddenPhrases) {
                assertFalse(
                    "Kalimat '$line' tidak boleh mengandung frasa kaku '$forbidden'",
                    line.contains(forbidden)
                )
            }
        }
    }

    @Test
    fun `pola Pembuka Tetap plus Detail memecah pembuka ber-hash SHA-256 identik di VoiceClauseChunker`() {
        val tempRoot: File = Files.createTempDirectory("gepy_opener_split_cache").toFile()
        try {
            val cache = VoiceAudioCacheManager(rootDir = tempRoot)
            val contexts = listOf(girlCtx, girlPrivacyCtx, jarvisCtx, mentorCtx)
            for (ctx in contexts) {
                val pairs = listOf(
                    SovereignNaturalSpeech.spotifyPlay(ctx, "Bernadya Satu Bulan", "phone") to
                        SovereignNaturalSpeech.spotifyPlay(ctx, "Tulus Hati Hati Di Jalan", "phone"),
                    SovereignNaturalSpeech.youtubePlay(ctx, "Compose Tutorial") to
                        SovereignNaturalSpeech.youtubePlay(ctx, "Kotlin Coroutines"),
                    SovereignNaturalSpeech.appOpened(ctx, "WhatsApp") to
                        SovereignNaturalSpeech.appOpened(ctx, "Instagram"),
                    SovereignNaturalSpeech.alarmSet(ctx, 5, 30, "Subuh", true) to
                        SovereignNaturalSpeech.alarmSet(ctx, 8, 0, "Kuliah", true),
                    SovereignNaturalSpeech.deepLinkMapsNavigation(ctx, "Stasiun Gambir") to
                        SovereignNaturalSpeech.deepLinkMapsNavigation(ctx, "Malioboro"),
                    SovereignNaturalSpeech.screenSummaryGeneric(ctx, "Pesan Masuk") to
                        SovereignNaturalSpeech.screenSummaryGeneric(ctx, "Daftar Kontak"),
                    SovereignNaturalSpeech.whatsappSent(ctx, "Budi") to
                        SovereignNaturalSpeech.whatsappSent(ctx, "Siti"),
                    SovereignNaturalSpeech.dailyBriefingMorning(ctx, "Senin, 10 November", "07:00", null, 3000, 90, false) to
                        SovereignNaturalSpeech.dailyBriefingMorning(ctx, "Selasa, 11 November", "08:15", "standup", 5100, 62, true),
                    SovereignNaturalSpeech.dailyBriefingEvening(ctx, 1200, 5, 1, 80, true) to
                        SovereignNaturalSpeech.dailyBriefingEvening(ctx, 6400, 28, 3, 20, false),
                    SovereignNaturalSpeech.dailyBriefingDaytime(ctx, "11:00", null, 1200, 5, 80) to
                        SovereignNaturalSpeech.dailyBriefingDaytime(ctx, "15:45", "demo", 5000, 20, 45),
                    // Batch 1 & 2 Opener Splits
                    SovereignNaturalSpeech.timerSet(ctx, "5 menit", "Masak Mie", true) to
                        SovereignNaturalSpeech.timerSet(ctx, "10 menit", "Belajar", true),
                    SovereignNaturalSpeech.volumeAdjusted(ctx, "media", 50, null) to
                        SovereignNaturalSpeech.volumeAdjusted(ctx, "alarm", 80, null),
                    SovereignNaturalSpeech.dialerOpened(ctx, "Ibu", true) to
                        SovereignNaturalSpeech.dialerOpened(ctx, "Budi", true),
                    SovereignNaturalSpeech.emailComposerOpened(ctx, "budi@test.com", true) to
                        SovereignNaturalSpeech.emailComposerOpened(ctx, "andi@test.com", true),
                    SovereignNaturalSpeech.floatingPanelOpened(ctx, "Wi-Fi", true) to
                        SovereignNaturalSpeech.floatingPanelOpened(ctx, "Volume", true),
                    SovereignNaturalSpeech.notificationReplied(ctx, "Budi") to
                        SovereignNaturalSpeech.notificationReplied(ctx, "Citra"),
                    SovereignNaturalSpeech.notificationDismissed(ctx, 1, listOf("WhatsApp")) to
                        SovereignNaturalSpeech.notificationDismissed(ctx, 2, listOf("Telegram"))
                )

                for ((firstText, secondText) in pairs) {
                    val clausesA = VoiceClauseChunker.splitIntoClauses(firstText)
                    val clausesB = VoiceClauseChunker.splitIntoClauses(secondText)
                    assertTrue("Harus dipecah menjadi minimal 2 klausa: '$firstText'", clausesA.size >= 2)
                    assertTrue("Harus dipecah menjadi minimal 2 klausa: '$secondText'", clausesB.size >= 2)
                    assertEquals("Pembuka klausa 0 wajib identik: '$firstText' vs '$secondText'", clausesA[0], clausesB[0])
                    assertEquals(
                        cache.computeClipHash("gepy", clausesA[0]),
                        cache.computeClipHash("gepy", clausesB[0])
                    )
                }

                // Khusus alarmSet: pembuka (klausa 0) DAN konfirmasi statis (klausa 1) harus identik SHA-256 lintas jam/label
                val alarmA = VoiceClauseChunker.splitIntoClauses(
                    SovereignNaturalSpeech.alarmSet(ctx, 5, 30, "Subuh", true)
                )
                val alarmB = VoiceClauseChunker.splitIntoClauses(
                    SovereignNaturalSpeech.alarmSet(ctx, 21, 15, "Alarm Gepy", true)
                )
                assertEquals(3, alarmA.size)
                assertEquals(3, alarmB.size)
                assertEquals(alarmA[1], alarmB[1])
                assertEquals(
                    cache.computeClipHash("gepy", alarmA[1]),
                    cache.computeClipHash("gepy", alarmB[1])
                )
                assertEquals("Jam 05:30, Subuh.", alarmA[2])
                assertEquals("Jam 21:15.", alarmB[2])
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun SovereignNaturalSpeech.memoryMissingScheduleOrNullCheck(ctx: SovereignSpeechContext): String =
        memoryNoScheduleToday(ctx)
}
