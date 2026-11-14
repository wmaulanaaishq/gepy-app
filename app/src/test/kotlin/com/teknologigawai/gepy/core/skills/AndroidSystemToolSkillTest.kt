package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.system.ActiveMediaSessionInfo
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker
import com.teknologigawai.gepy.service.ActiveNotificationSnapshot
import com.teknologigawai.gepy.service.NotificationOpStatus
import com.teknologigawai.gepy.service.NotificationReplyAttemptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemToolSkillTest {

    private fun buildSkill(
        preferredWakeTime: Pair<Int, Int>? = null,
        bridge: AndroidSystemToolsBridge = AndroidSystemToolsBridge(context = null),
        sovereignDirect: Boolean = true,
        speechContext: SovereignSpeechContext = SovereignSpeechContext(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            ownerName = "Maulana"
        )
    ): AndroidSystemToolSkill {
        return AndroidSystemToolSkill(
            systemBridge = bridge,
            speechContextProvider = { speechContext },
            preferredWakeTimeProvider = { preferredWakeTime },
            sovereignDirectExecutionProvider = { sovereignDirect }
        )
    }

    @Test
    fun `parseAlarmRequest mengurai berbagai format jam Indonesia dan preset tanpa angka`() {
        val skill = buildSkill()

        val p5 = skill.parseAlarmRequest("pasang alarm jam 5 pagi")
        assertNotNull(p5)
        assertEquals(5, p5?.hour)
        assertEquals(0, p5?.minute)
        assertEquals("Alarm Gepy", p5?.label)

        val pColloquial = skill.parseAlarmRequest("pasangin aku alarm jam 5 pagi")
        assertNotNull(pColloquial)
        assertEquals(5, pColloquial?.hour)
        assertEquals(0, pColloquial?.minute)

        val p530 = skill.parseAlarmRequest("setel alarm 05:30 buat kerja")
        assertNotNull(p530)
        assertEquals(5, p530?.hour)
        assertEquals(30, p530?.minute)
        assertEquals("Kerja", p530?.label)

        val pHalf6 = skill.parseAlarmRequest("bangunin aku jam setengah 6 pagi")
        assertNotNull(pHalf6)
        assertEquals(5, pHalf6?.hour)
        assertEquals(30, pHalf6?.minute)

        val pKurang = skill.parseAlarmRequest("pasang alarm jam 6 kurang 10 pagi")
        assertNotNull(pKurang)
        assertEquals(5, pKurang?.hour)
        assertEquals(50, pKurang?.minute)

        val pLewat = skill.parseAlarmRequest("buat alarm jam 6 lewat 15")
        assertNotNull(pLewat)
        assertEquals(6, pLewat?.hour)
        assertEquals(15, pLewat?.minute)

        val pSore = skill.parseAlarmRequest("pasang alarm jam 5 sore")
        assertNotNull(pSore)
        assertEquals(17, pSore?.hour)
        assertEquals(0, pSore?.minute)

        val pMalam = skill.parseAlarmRequest("setel alarm jam 12 malam")
        assertNotNull(pMalam)
        assertEquals(0, pMalam?.hour)
        assertEquals(0, pMalam?.minute)

        val pSubuh = skill.parseAlarmRequest("alarm subuh")
        assertNotNull(pSubuh)
        assertEquals(4, pSubuh?.hour)
        assertEquals(30, pSubuh?.minute)
        assertEquals("Subuh", pSubuh?.label)

        val pPagiDefault = skill.parseAlarmRequest("alarm pagi")
        assertNotNull(pPagiDefault)
        assertEquals(AndroidSystemToolSkill.DEFAULT_MORNING_ALARM_HOUR, pPagiDefault?.hour)
        assertEquals(AndroidSystemToolSkill.DEFAULT_MORNING_ALARM_MINUTE, pPagiDefault?.minute)
        assertEquals("Alarm Pagi", pPagiDefault?.label)
        assertTrue(pPagiDefault?.usedDefaultMorningTime == true)
    }

    @Test
    fun `alarm pagi tanpa angka memprioritaskan jam bangun dari memori pengguna`() {
        val skillWithMemory = buildSkill(preferredWakeTime = 5 to 45)
        val parsed = skillWithMemory.parseAlarmRequest("pasang alarm pagi")
        assertNotNull(parsed)
        assertEquals(5, parsed?.hour)
        assertEquals(45, parsed?.minute)
        assertEquals("Alarm Pagi", parsed?.label)
        assertFalse(parsed?.usedDefaultMorningTime == true)
    }

    @Test
    fun `perintah cek hapus matikan dan durasi relatif dikecualikan dari Fast-Path alarm`() {
        val skill = buildSkill()

        // 1. Perintah destruktif / durasi relatif / invalid jam 25 tetap null di alarm dan canHandleVoiceIntent false
        val invalidOrDestructive = listOf(
            "hapus alarm jam 5",
            "matikan semua alarm",
            "batalkan alarm besok",
            "alarm 20 menit lagi",
            "pasang alarm jam 25 pagi"
        )
        for (query in invalidOrDestructive) {
            assertNull("Seharusnya bukan alarm: $query", skill.parseAlarmRequest(query))
            assertFalse("canHandleVoiceIntent seharusnya false: $query", skill.canHandleVoiceIntent(query))
        }

        // 2. Perintah cek daftar alarm dan timer BUKAN alarm, tetapi sekarang ditangani oleh Fast-Path sistem Batch 1
        val batch1HandledQueries = listOf(
            "cek alarm saya jam berapa",
            "lihat daftar alarm",
            "pasang timer 15 menit"
        )
        for (query in batch1HandledQueries) {
            assertNull("Seharusnya bukan pasang alarm: $query", skill.parseAlarmRequest(query))
            assertTrue("canHandleVoiceIntent sekarang true untuk Batch 1: $query", skill.canHandleVoiceIntent(query))
        }
    }

    @Test
    fun `parseFlashlightRequest mengurai ON dan OFF serta menolak tutorial dan laptop`() {
        val skill = buildSkill()

        val onReq = skill.parseFlashlightRequest("nyalain senter hp")
        assertNotNull(onReq)
        assertTrue(onReq?.enabled == true)

        val offReq = skill.parseFlashlightRequest("tolong matikan senter")
        assertNotNull(offReq)
        assertFalse(offReq?.enabled == true)

        val singleWord = skill.parseFlashlightRequest("senter!")
        assertNotNull(singleWord)
        assertTrue(singleWord?.enabled == true)

        assertNull(skill.parseFlashlightRequest("cara nyalain senter hp samsung"))
        assertNull(skill.parseFlashlightRequest("nyalain senter di laptop"))
        assertNull(skill.parseFlashlightRequest(""))
    }

    @Test
    fun `parseTimerRequest mengurai durasi majemuk jam menit detik dan label masak mie`() {
        val skill = buildSkill()

        val mie = skill.parseTimerRequest("pasang timer 5 menit buat masak mie")
        assertNotNull(mie)
        assertEquals(300, mie?.durationSeconds)
        assertEquals("Masak Mie", mie?.label)

        val combo = skill.parseTimerRequest("setel timer 1 jam 30 menit 15 detik untuk Belajar")
        assertNotNull(combo)
        assertEquals(3600 + 1800 + 15, combo?.durationSeconds)
        assertEquals("Belajar", combo?.label)

        val halfHour = skill.parseTimerRequest("hitung mundur setengah jam")
        assertNotNull(halfHour)
        assertEquals(1800, halfHour?.durationSeconds)
        assertEquals("Timer Gepy", halfHour?.label)

        assertNull(skill.parseTimerRequest("hapus timer 5 menit"))
        assertNull(skill.parseTimerRequest("bagaimana cara kerja timer"))
        assertNull(skill.parseTimerRequest("timer di laptop"))
    }

    @Test
    fun `parseTimerRequest menangkap pengingat waktu relatif natural dan mengekstrak label secara akurat`() {
        val skill = buildSkill()

        // 1. Perintah persis yang ditanyakan pengguna di tangkapan layar Samsung Galaxy A06
        val screenshotQuery = skill.parseTimerRequest("coba 2 menit dari sekarang kamu ingetin untuk istirahat")
        assertNotNull("Perintah screenshot pengguna harus dikenali sebagai timer", screenshotQuery)
        assertEquals(120, screenshotQuery?.durationSeconds)
        assertEquals("Istirahat", screenshotQuery?.label)
        assertTrue(skill.canHandleVoiceIntent("coba 2 menit dari sekarang kamu ingetin untuk istirahat"))

        // 1b. Perintah wicara kata angka ("dua menit") dan imbuhan terpisah ("inget in")
        val spokenQuery = skill.parseTimerRequest("coba dua menit dari sekarang kamu ingetin untuk istirahat")
        assertNotNull("Perintah kata wicara dua menit harus dikenali sebagai timer", spokenQuery)
        assertEquals(120, spokenQuery?.durationSeconds)
        assertEquals("Istirahat", spokenQuery?.label)
        assertTrue(skill.canHandleVoiceIntent("coba dua menit dari sekarang kamu ingetin untuk istirahat"))

        val spacedAffix = skill.parseTimerRequest("coba 2 menit lagi kamu inget in untuk minum")
        assertNotNull(spacedAffix)
        assertEquals(120, spacedAffix?.durationSeconds)
        assertEquals("Minum", spacedAffix?.label)

        // 2. Variasi 'ingetin ... X menit lagi buat ...'
        val drink = skill.parseTimerRequest("ingetin aku 5 menit lagi buat minum air")
        assertNotNull(drink)
        assertEquals(300, drink?.durationSeconds)
        assertEquals("Minum Air", drink?.label)
        assertTrue(skill.canHandleVoiceIntent("ingetin aku 5 menit lagi buat minum air"))

        // 3. Variasi label langsung setelah 'ingetin'
        val restDirect = skill.parseTimerRequest("ingetin istirahat 10 menit lagi")
        assertNotNull(restDirect)
        assertEquals(600, restDirect?.durationSeconds)
        assertEquals("Istirahat", restDirect?.label)

        // 4. Pengingat tanpa label eksplisit -> fallback ke Pengingat Gepy
        val general = skill.parseTimerRequest("tolong 15 menit lagi ingetin ya")
        assertNotNull(general)
        assertEquals(900, general?.durationSeconds)
        assertEquals("Pengingat Gepy", general?.label)

        // 5. Variasi setengah jam lagi
        val sleep = skill.parseTimerRequest("setengah jam lagi ingetin tidur")
        assertNotNull(sleep)
        assertEquals(1800, sleep?.durationSeconds)
        assertEquals("Tidur", sleep?.label)

        // 6. Variasi 'alarm 5 menit lagi' diarahkan ke timer relatif
        val alarmRel = skill.parseTimerRequest("pasang alarm 5 menit lagi")
        assertNotNull(alarmRel)
        assertEquals(300, alarmRel?.durationSeconds)

        // 7. Variasi jam dan menit majemuk
        val compound = skill.parseTimerRequest("ingetin aku 1 jam 15 menit lagi untuk meeting")
        assertNotNull(compound)
        assertEquals(4500, compound?.durationSeconds)
        assertEquals("Meeting", compound?.label)

        // 8. Fast-path execution via proposeFastPathAction
        val bridge = AndroidSystemToolsBridge(context = null)
        val skillWithBridge = buildSkill(bridge = bridge)
        val action = skillWithBridge.proposeFastPathAction(
            userGoal = "coba 2 menit dari sekarang kamu ingetin untuk istirahat",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("2 menit"))
        assertTrue(action?.completionTts.orEmpty().contains("Istirahat"))
    }

    @Test
    fun `parseShowAlarmsRequest mengenali kueri melihat daftar alarm`() {
        val skill = buildSkill()

        assertTrue(skill.parseShowAlarmsRequest("lihat daftar alarm"))
        assertTrue(skill.parseShowAlarmsRequest("cek alarm saya jam berapa"))
        assertTrue(skill.parseShowAlarmsRequest("ada alarm apa aja yang aktif"))

        assertFalse(skill.parseShowAlarmsRequest("pasang alarm jam 6 pagi"))
        assertFalse(skill.parseShowAlarmsRequest("hapus alarm jam 6"))
        assertFalse(skill.parseShowAlarmsRequest("cara membuat alarm di hp"))
    }

    @Test
    fun `parseVolumeOrRingerRequest mengurai volume persentase arah dan mode dering`() {
        val skill = buildSkill()

        val vol50 = skill.parseVolumeOrRingerRequest("turunkan volume media ke 50%")
        assertTrue(vol50 is ParsedAudioSystemRequest.Volume)
        val v50 = vol50 as ParsedAudioSystemRequest.Volume
        assertEquals("media", v50.stream)
        assertEquals(50, v50.targetPercent)

        val volAlarmUp = skill.parseVolumeOrRingerRequest("naikkan volume alarm")
        assertTrue(volAlarmUp is ParsedAudioSystemRequest.Volume)
        val vUp = volAlarmUp as ParsedAudioSystemRequest.Volume
        assertEquals("alarm", vUp.stream)
        assertEquals("up", vUp.direction)

        val volMute = skill.parseVolumeOrRingerRequest("bisukan volume media")
        assertTrue(volMute is ParsedAudioSystemRequest.Volume)
        val vMute = volMute as ParsedAudioSystemRequest.Volume
        assertEquals("media", vMute.stream)
        assertEquals("mute", vMute.direction)

        val ringVibrate = skill.parseVolumeOrRingerRequest("ubah ke mode getar")
        assertTrue(ringVibrate is ParsedAudioSystemRequest.RingerMode)
        assertEquals("vibrate", (ringVibrate as ParsedAudioSystemRequest.RingerMode).mode)

        val ringSilent = skill.parseVolumeOrRingerRequest("aktifkan mode senyap")
        assertTrue(ringSilent is ParsedAudioSystemRequest.RingerMode)
        assertEquals("silent", (ringSilent as ParsedAudioSystemRequest.RingerMode).mode)

        val ringNormal = skill.parseVolumeOrRingerRequest("ganti ke mode normal")
        assertTrue(ringNormal is ParsedAudioSystemRequest.RingerMode)
        assertEquals("normal", (ringNormal as ParsedAudioSystemRequest.RingerMode).mode)

        // Panel mengambang tidak boleh tertukar dengan volume request
        assertNull(skill.parseVolumeOrRingerRequest("buka panel volume"))
        assertNull(skill.parseVolumeOrRingerRequest("turunkan volume tv"))
    }

    @Test
    fun `parseDialRequest mengurai target panggilan telepon biasa dan menolak WA atau aplikasi`() {
        val skill = buildSkill()

        val dialIbu = skill.parseDialRequest("telepon ibu")
        assertNotNull(dialIbu)
        assertEquals("ibu", dialIbu?.targetQuery)

        val dialNum = skill.parseDialRequest("hubungi nomor 081234567890 dong")
        assertNotNull(dialNum)
        assertEquals("081234567890", dialNum?.targetQuery)

        // Panggilan via WhatsApp atau aplikasi generic harus ditolak dari dialer biasa
        assertNull(skill.parseDialRequest("telepon wa budi"))
        assertNull(skill.parseDialRequest("telepon lewat telegram"))
        assertNull(skill.parseDialRequest("buka menu telepon"))
        assertNull(skill.parseDialRequest("telepon aplikasi"))
    }

    @Test
    fun `parseEmailComposeRequest mengurai penerima subjek dan isi email`() {
        val skill = buildSkill()

        val parsed = skill.parseEmailComposeRequest(
            "kirim email ke budi@contoh.com subjek Laporan Mingguan isi pesannya sudah selesai ya"
        )
        assertNotNull(parsed)
        assertEquals("budi@contoh.com", parsed?.recipientQuery)
        assertEquals("Laporan Mingguan", parsed?.subject)
        assertEquals("sudah selesai", parsed?.body)

        assertNull(skill.parseEmailComposeRequest("cek kotak masuk email"))
        assertNull(skill.parseEmailComposeRequest("cara kirim email lewat gmail"))
    }

    @Test
    fun `parseFloatingPanelRequest mengurai kata kunci panel settings yang valid`() {
        val skill = buildSkill()

        val wifi = skill.parseFloatingPanelRequest("buka panel wifi")
        assertNotNull(wifi)
        assertEquals("wifi", wifi?.panelKeyword)

        val volume = skill.parseFloatingPanelRequest("tampilkan panel volume cepat")
        assertNotNull(volume)
        assertEquals("volume", volume?.panelKeyword)

        assertNull(skill.parseFloatingPanelRequest("cara buka panel wifi"))
        assertNull(skill.parseFloatingPanelRequest("buka panel yang tidak ada"))
    }

    @Test
    fun `parseMediaNowPlayingQuery mengenali variasi kueri judul lagu yang sedang diputar`() {
        val skill = buildSkill()

        assertTrue(skill.parseMediaNowPlayingQuery("ini lagu apa yang lagi muter?"))
        assertTrue(skill.parseMediaNowPlayingQuery("lagu apa yang lagi diputar sekarang"))
        assertTrue(skill.parseMediaNowPlayingQuery("now playing"))
        assertTrue(skill.parseMediaNowPlayingQuery("judul lagu ini apa"))

        assertFalse(skill.parseMediaNowPlayingQuery("putar lagu bernadya di spotify"))
        assertFalse(skill.parseMediaNowPlayingQuery("cara cari judul lagu"))
    }

    @Test
    fun `parseNotificationDismissRequest mengurai target spesifik dan bersihkan semua notifikasi`() {
        val skill = buildSkill()

        val dismissAll = skill.parseNotificationDismissRequest("bersihkan semua notifikasi")
        assertNotNull(dismissAll)
        assertEquals("all", dismissAll?.targetQuery)

        val dismissWa = skill.parseNotificationDismissRequest("hapus notifikasi dari whatsapp")
        assertNotNull(dismissWa)
        assertEquals("whatsapp", dismissWa?.targetQuery)

        val dismissBudi = skill.parseNotificationDismissRequest("buang notif budi santoso")
        assertNotNull(dismissBudi)
        assertEquals("budi santoso", dismissBudi?.targetQuery)
    }

    @Test
    fun `parseNotificationReplyRequest mengurai pengirim pesan dan hint aplikasi chat`() {
        val skill = buildSkill()

        val reply1 = skill.parseNotificationReplyRequest("balas notif wa dari budi bilang oke meluncur")
        assertNotNull(reply1)
        assertEquals("budi", reply1?.targetQuery)
        assertEquals("oke meluncur", reply1?.replyMessage)
        assertEquals("wa", reply1?.explicitAppKeyword)

        val reply2 = skill.parseNotificationReplyRequest("bales pesan telegram citra: aku sudah di jalan ya")
        assertNotNull(reply2)
        assertEquals("citra", reply2?.targetQuery)
        assertEquals("aku sudah di jalan ya", reply2?.replyMessage)
        assertEquals("telegram", reply2?.explicitAppKeyword)
    }

    @Test
    fun `proposeFastPathAction mengeksekusi DONE langsung dengan respon 3 klausa cache-friendly untuk alarm`() {
        val skill = buildSkill()
        assertTrue(skill.canHandleVoiceIntent("pasang alarm jam 5 pagi buat kuliah"))

        val action = skill.proposeFastPathAction(
            userGoal = "pasang alarm jam 5 pagi buat kuliah",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(
                recentSteps = emptyList(),
                scratchpad = emptyMap()
            )
        )
        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)

        val clauses = VoiceClauseChunker.splitIntoClauses(action?.completionTts.orEmpty())
        assertEquals(3, clauses.size)
        assertTrue(clauses[0].contains("Maulana"))
        assertEquals("Alarm udah aku pasang biar nggak telat.", clauses[1])
        assertEquals("Jam 05:00, Kuliah.", clauses[2])
    }

    @Test
    fun `proposeFastPathAction mengeksekusi senter timer dialer email dan panel secara instan`() {
        val bridge = AndroidSystemToolsBridge(context = null)
        val skill = buildSkill(bridge = bridge)

        // 1. Senter
        val torch = skill.proposeFastPathAction(
            userGoal = "nyalain senter",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(torch)
        assertEquals(AgentActionType.DONE, torch?.action)
        assertTrue(torch?.completionTts.orEmpty().contains("Senter"))

        // 2. Timer
        val timer = skill.proposeFastPathAction(
            userGoal = "pasang timer 5 menit buat mie",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(timer)
        assertEquals(AgentActionType.DONE, timer?.action)
        assertTrue(timer?.completionTts.orEmpty().contains("5 menit"))

        // 3. Dialer
        val dial = skill.proposeFastPathAction(
            userGoal = "telepon 081234567890",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(dial)
        assertEquals(AgentActionType.DONE, dial?.action)
        assertTrue(dial?.completionTts.orEmpty().contains("081234567890"))

        // 4. Email
        val email = skill.proposeFastPathAction(
            userGoal = "kirim email ke andi@contoh.com subjek Tes",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(email)
        assertEquals(AgentActionType.DONE, email?.action)
        assertTrue(email?.completionTts.orEmpty().contains("andi@contoh.com"))

        // 5. Panel Wi-Fi
        val panel = skill.proposeFastPathAction(
            userGoal = "buka panel wifi",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(panel)
        assertEquals(AgentActionType.DONE, panel?.action)
        assertTrue(panel?.completionTts.orEmpty().contains("Wi-Fi"))
    }

    @Test
    fun `proposeFastPathAction membaca media now playing via hook MediaSession`() {
        val bridge = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { true }
            activeMediaSessionProvider = {
                listOf(
                    ActiveMediaSessionInfo(
                        packageName = "com.spotify.music",
                        appLabel = "Spotify",
                        title = "Satu Bulan",
                        artist = "Bernadya",
                        isPlaying = true
                    )
                )
            }
        }
        val skill = buildSkill(bridge = bridge)

        val nowAction = skill.proposeFastPathAction(
            userGoal = "ini lagu apa yang lagi diputar?",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(nowAction)
        assertEquals(AgentActionType.DONE, nowAction?.action)
        assertTrue(nowAction?.completionTts.orEmpty().contains("Satu Bulan"))
        assertTrue(nowAction?.completionTts.orEmpty().contains("Bernadya"))
        assertTrue(nowAction?.completionTts.orEmpty().contains("Spotify"))
    }

    @Test
    fun `proposeFastPathAction mengelola balas notifikasi RemoteInput dengan sovereign vs non-sovereign HITL dan UI-Fallback`() {
        val sampleSnap = ActiveNotificationSnapshot(
            key = "notif_wa_budi",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Budi Santoso",
            text = "Maulana, file sudah siap.",
            timestampMs = System.currentTimeMillis(),
            hasRemoteInput = true
        )

        // Skenario 1: Listener disabled -> proposeFastPathAction mengembalikan NULL agar masuk UI-Fallback!
        val bridgeDisabled = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { false }
        }
        val skillDisabled = buildSkill(bridge = bridgeDisabled)
        val fallbackAction1 = skillDisabled.proposeFastPathAction(
            userGoal = "balas pesan WA dari Budi bilang oke siap",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNull("Ketika listener belum aktif, harus jatuh ke UI-Fallback (return null)", fallbackAction1)

        // Skenario 2: Listener aktif tapi notifikasi tidak ditemukan -> return NULL (UI-Fallback)
        val bridgeNotFound = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { true }
            activeNotificationsProvider = { _ -> emptyList() }
        }
        val skillNotFound = buildSkill(bridge = bridgeNotFound)
        val fallbackAction2 = skillNotFound.proposeFastPathAction(
            userGoal = "balas pesan WA dari Budi bilang oke siap",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNull("Ketika target notifikasi tidak ada, harus jatuh ke UI-Fallback (return null)", fallbackAction2)

        // Skenario 3: Non-Sovereign (HITL wajib) -> return CONFIRM_AND_CLICK
        val bridgeHitl = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { true }
            activeNotificationsProvider = { _ -> listOf(sampleSnap) }
        }
        val skillHitl = buildSkill(bridge = bridgeHitl, sovereignDirect = false)
        val hitlAction = skillHitl.proposeFastPathAction(
            userGoal = "balas pesan WA dari Budi bilang oke siap",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(hitlAction)
        assertEquals(AgentActionType.CONFIRM_AND_CLICK, hitlAction?.action)
        assertEquals("notif_wa_budi", hitlAction?.appName)
        assertEquals("oke siap", hitlAction?.textValue)
        assertTrue(hitlAction?.confirmationPrompt.orEmpty().contains("Budi Santoso"))

        // Skenario 4: Sovereign Direct Execution -> return DONE langsung dan panggil notificationReplyByKeyExecutor
        var repliedMsg = ""
        val bridgeSovereign = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { true }
            activeNotificationsProvider = { _ -> listOf(sampleSnap) }
            notificationReplyByKeyExecutor = { key, target, msg ->
                repliedMsg = msg
                NotificationReplyAttemptResult(
                    status = NotificationOpStatus.SUCCESS,
                    targetPackage = "com.whatsapp",
                    targetTitle = "Budi Santoso",
                    message = msg
                )
            }
        }
        val skillSovereign = buildSkill(bridge = bridgeSovereign, sovereignDirect = true)
        val sovereignAction = skillSovereign.proposeFastPathAction(
            userGoal = "balas pesan WA dari Budi bilang oke siap",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(sovereignAction)
        assertEquals(AgentActionType.DONE, sovereignAction?.action)
        assertEquals("oke siap", repliedMsg)
        assertTrue(sovereignAction?.completionTts.orEmpty().contains("Budi Santoso"))

        // Skenario 5: Privacy Shield aktif -> blokir mutlak dan return DONE tanpa bocor ke UI fallback
        val bridgePrivacy = AndroidSystemToolsBridge(context = null).apply {
            wellnessStatusProvider = { mapOf("privacy_shield_active" to true) }
        }
        val skillPrivacy = buildSkill(
            bridge = bridgePrivacy,
            speechContext = SovereignSpeechContext(
                configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
                ownerName = "Maulana",
                wellness = PersonalWellnessContext(isGuestPrivacyShieldActive = true)
            )
        )
        val privacyAction = skillPrivacy.proposeFastPathAction(
            userGoal = "balas pesan WA dari Budi bilang oke siap",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(privacyAction)
        assertEquals(AgentActionType.DONE, privacyAction?.action)
        assertTrue(privacyAction?.completionTts.orEmpty().contains("Privacy Shield"))
    }

    @Test
    fun `adjustAudioVolume mengarahkan ke stream telepon dan menyinkronkan liveCallVolumeScale saat Live Call aktif`() {
        var recordedScale = 1.5f
        val bridge = AndroidSystemToolsBridge(context = null).apply {
            isLiveCallActiveProvider = { true }
            liveCallVolumeGetter = { recordedScale }
            onLiveCallVolumeChanged = { recordedScale = it }
        }

        // Perintah default "media" selama Live Call aktif wajib diarahkan ke STREAM_VOICE_CALL ("telepon")
        val lowered = bridge.adjustAudioVolume(stream = "media", direction = "down")
        assertEquals("SUCCESS", lowered["status"])
        assertEquals("telepon", lowered["stream"])
        assertEquals(1.15f, recordedScale, 0.01f)

        // Perintah mute harus mengatur skala volume Live Call ke 0.0f
        val muted = bridge.adjustAudioVolume(stream = "media", direction = "mute")
        assertEquals("telepon", muted["stream"])
        assertEquals(0.0f, recordedScale, 0.001f)

        // Target eksplisit "alarm" tetap memengaruhi stream "alarm", tidak membajak alarm ke telepon
        val alarm = bridge.adjustAudioVolume(stream = "alarm", targetPercent = 60)
        assertEquals("alarm", alarm["stream"])
        assertEquals(60, alarm["percent"])
    }
}
