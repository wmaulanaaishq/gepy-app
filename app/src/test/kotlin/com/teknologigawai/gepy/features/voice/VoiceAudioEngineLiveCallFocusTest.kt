package com.teknologigawai.gepy.features.voice

import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pengujian AudioFocus panggilan dua arah, boost STREAM_VOICE_CALL, HITL speech await, skala volume & konkurensi overlay.
 */
class VoiceAudioEngineLiveCallFocusTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `VoiceAudioEngine never calls ElevenLabs or plays audio when Gemini Live Call is connected`() = runBlocking {
        val apiCalls = AtomicInteger(0)
        val server = LoopbackHttpServer {
            apiCalls.incrementAndGet()
            LoopbackHttpResponse(200, "audio/mpeg", byteArrayOf(0x01, 0x02))
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1")
            val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_live_guard"))
            val playedFiles = CopyOnWriteArrayList<File>()

            val connectedLiveClient = object : GeminiLiveClient(apiKey = "AIzaSyLiveConnected") {
                val sentRealtimeTexts = mutableListOf<String>()

                override val isConnected: Boolean = true

                override fun sendRealtimeText(text: String) {
                    sentRealtimeTexts.add(text)
                }
            }

            val engine = VoiceAudioEngine(
                context = null,
                geminiLiveClient = connectedLiveClient,
                elevenLabsClient = client,
                voiceCacheManager = cacheManager,
                customMp3Player = { file ->
                    playedFiles.add(file)
                    true
                }
            )
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_test_live_guard",
                    voiceId = "gepy_voice"
                )
            )

            engine.finishAndSpeak("Jangan bunyikan TTS lokal saat telepon aktif.")
            delay(120L)

            assertEquals(0, apiCalls.get())
            assertTrue(playedFiles.isEmpty())
            assertEquals(1, connectedLiveClient.sentRealtimeTexts.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `startLiveCallSession and stopLiveCallSession pair call AudioFocus cleanly and handle AUDIOFOCUS_LOSS`() = runBlocking {
        val fakeLiveClient = object : GeminiLiveClient(apiKey = "AIzaTestCallFocusKey") {
            override var isConnected: Boolean = false
            override suspend fun connectSession(
                userContext: String,
                onToolCallRequested: (suspend (GeminiLiveToolCall) -> Map<String, Any?>)?
            ): Boolean {
                isConnected = true
                return true
            }

            override fun sendInitialGreetingPrompt(customPrompt: String?) {}
            override fun disconnect() {
                isConnected = false
            }
        }
        val focusCoordinator = RecordingVoiceAudioFocusCoordinator()
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = fakeLiveClient,
            audioFocusCoordinator = focusCoordinator
        )

        val started = engine.startLiveCallSession { emptyMap() }
        assertTrue(started)
        assertEquals(1, focusCoordinator.callAcquireCount.get())
        assertEquals(0, focusCoordinator.callAbandonCount.get())

        // Simulasikan panggilan GSM sistem masuk mengambil alih AudioFocus -> sesi ditutup & focus dilepas
        focusCoordinator.lastCallListener?.invoke(VoiceAudioFocusEvent.LOSS)
        waitUntil { focusCoordinator.callAbandonCount.get() == 1 }
        assertFalse(fakeLiveClient.isConnected)
    }

    @Test
    fun `A1 ManagedLiveCallAudioRouter boosts STREAM_VOICE_CALL to min 85 percent and restores exact volume and mode on stop and dispose`() = runBlocking {
        class FakeSystemAudioRoutingBackend(
            var currentVol: Int = 4,
            val maxVol: Int = 15
        ) : SystemAudioRoutingBackend {
            val timeline = mutableListOf<String>()
            var inCommMode = false
            override fun getVoiceCallVolume(): Int = currentVol
            override fun getVoiceCallMaxVolume(): Int = maxVol
            override fun setVoiceCallVolume(volume: Int) {
                currentVol = volume
                timeline.add("set_voice_call:$volume")
            }
            override fun enterCommunicationMode() {
                inCommMode = true
                timeline.add("enter_comm")
            }
            override fun exitCommunicationMode() {
                inCommMode = false
                timeline.add("exit_comm")
            }
        }

        // 1. Jika volume asal rendah (4 dari 15), naikkan ke 13 (85%), lalu pulihkan ke 4 saat stop/dispose
        val backendLow = FakeSystemAudioRoutingBackend(currentVol = 4, maxVol = 15)
        val routerLow = ManagedLiveCallAudioRouter(backendLow)
        val fakeLiveClient = object : GeminiLiveClient(apiKey = "AIzaTestRoutingKey") {
            override var isConnected: Boolean = false
            override suspend fun connectSession(
                userContext: String,
                onToolCallRequested: (suspend (GeminiLiveToolCall) -> Map<String, Any?>)?
            ): Boolean {
                isConnected = true
                return true
            }
            override fun sendInitialGreetingPrompt(customPrompt: String?) {}
            override fun disconnect() {
                isConnected = false
            }
        }
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = fakeLiveClient,
            audioFocusCoordinator = RecordingVoiceAudioFocusCoordinator(),
            liveCallAudioRouter = routerLow
        )

        assertTrue(engine.startLiveCallSession { emptyMap() })
        assertTrue(backendLow.inCommMode)
        assertEquals(13, backendLow.currentVol)

        engine.stopLiveCallSession("Selesai")
        assertFalse(backendLow.inCommMode)
        assertEquals(4, backendLow.currentVol)
        assertEquals(listOf("enter_comm", "set_voice_call:13", "exit_comm", "set_voice_call:4"), backendLow.timeline)

        engine.dispose()
        assertEquals(4, backendLow.currentVol)

        // 2. Jika volume asal sudah >= 85% (14 dari 15), jangan menimpa set_voice_call
        val backendHigh = FakeSystemAudioRoutingBackend(currentVol = 14, maxVol = 15)
        val routerHigh = ManagedLiveCallAudioRouter(backendHigh)
        routerHigh.enterLiveCallRouting()
        assertEquals(14, backendHigh.currentVol)
        assertEquals(listOf("enter_comm"), backendHigh.timeline)
        routerHigh.exitLiveCallRouting()
        assertEquals(listOf("enter_comm", "exit_comm"), backendHigh.timeline)
    }

    @Test
    fun `A3 askDisambiguation and requestHitlApproval await critical speech before listening focus and release listening focus in finally`() = runBlocking {
        val timeline = CopyOnWriteArrayList<String>()
        val server = LoopbackHttpServer { req ->
            val txt = JSONObject(req.body.toString(Charsets.UTF_8)).getString("text")
            timeline.add("tts_fetch:$txt")
            Thread.sleep(60L)
            LoopbackHttpResponse(200, "audio/mpeg", byteArrayOf(0x01, 0x02))
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1")
            val focus = RecordingVoiceAudioFocusCoordinator().apply {
                // Sekalipun sistem menolak SpeechFocus untuk audio biasa, pertanyaan kritis HITL/Disambiguasi tetap wajib terdengar!
                allowSpeechFocus = false
            }
            val played = CopyOnWriteArrayList<String>()
            var sttReply = "ya kirim"
            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "a3_hitl_cache")),
                customMp3Player = {
                    timeline.add("tts_played")
                    played.add("ok")
                    true
                },
                audioFocusCoordinator = focus,
                shortVoiceReplyStarter = { onSpoken ->
                    timeline.add("stt_started")
                    onSpoken(sttReply)
                }
            )
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_a3_test",
                    voiceId = "gepy"
                )
            )

            val approved = engine.requestHitlApproval("Kirim pesan penting?", "Kirim")
            assertTrue(approved)
            assertEquals(1, played.size)

            // Urutan wajib: TTS selesai diputar ("tts_played") SEBELUM Listening Focus & STT ("stt_started") dimulai,
            // lalu focus dilepas cleanly ("abandon_listening")!
            val ttsIdx = timeline.indexOf("tts_played")
            val sttIdx = timeline.indexOf("stt_started")
            assertTrue("TTS must play before STT starts: timeline=$timeline", ttsIdx in 0 until sttIdx)
            assertTrue(focus.listeningAcquireCount.get() >= 1)
            assertTrue(focus.listeningAbandonCount.get() >= 1)

            // Disambiguasi via suara menunggu prompt selesai dulu sebelum memulai STT dan melepas focus cleanly
            sttReply = "budi hartono"
            val chosen = engine.askDisambiguation("Pilih kontak:", listOf("Budi Santoso", "Budi Hartono"))
            assertEquals("Budi Hartono", chosen)
            assertEquals(2, focus.listeningAcquireCount.get())
            assertEquals(2, focus.listeningAbandonCount.get())

            // UI tap di tengah prompt disambiguasi tetap segera menyelesaikan tanpa menunggu TTS/STT
            val disambigScopeResult = CopyOnWriteArrayList<String>()
            val engineUiTap = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "a3_disambig_cache")),
                customMp3Player = {
                    delay(40L)
                    true
                },
                audioFocusCoordinator = focus,
                shortVoiceReplyStarter = { _ -> }
            )
            engineUiTap.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(enabled = true, apiKey = "sk_a3_test", voiceId = "gepy")
            )
            val askJob = launch {
                disambigScopeResult.add(engineUiTap.askDisambiguation("Pilih kontak?", listOf("Andi", "Budi")))
            }
            waitUntil { engineUiTap.hudStateFlow.value.mode == OverlayVisualMode.DISAMBIGUATION }
            engineUiTap.onDisambiguationChosen("Andi")
            askJob.join()
            assertEquals(listOf("Andi"), disambigScopeResult.toList())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `A3 onOverlayMicLongPress skips conflicting startup TTS and sequences hangup announcement after stopLiveCallSession`() = runBlocking {
        val sequence = CopyOnWriteArrayList<String>()
        var isFakeConnected = false
        val fakeLiveClient = object : GeminiLiveClient(apiKey = "AIzaTestOverlayLongPress") {
            override val isConnected: Boolean
                get() = isFakeConnected
            override fun disconnect() {
                isFakeConnected = false
            }
        }
        val spokenFallback = CopyOnWriteArrayList<String>()
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = fakeLiveClient,
            audioFocusCoordinator = RecordingVoiceAudioFocusCoordinator(),
            systemTtsFallbackHook = { text, _ ->
                sequence.add("speak:$text")
                spokenFallback.add(text)
            }
        )
        engine.onLiveCallToggleSuspendRequested = {
            if (!isFakeConnected) {
                isFakeConnected = true
                sequence.add("live_connected")
            } else {
                delay(40L)
                engine.stopLiveCallSession("Panggilan diakhiri.")
                sequence.add("live_disconnected")
            }
        }

        // 1. Long press untuk mulai Live Call -> tidak ada TTS "Menghubungkan telepon." yang dibunuh sendiri!
        engine.onOverlayMicLongPress()
        waitUntil { sequence.contains("live_connected") }
        assertTrue(spokenFallback.isEmpty())

        // 2. Long press untuk menutup Live Call -> live_disconnected HARUS selesai sebelum TTS penutup panggilan
        val expectedHangupTts = SovereignNaturalSpeech.liveCallEnded(SovereignSpeechContext())
        engine.onOverlayMicLongPress()
        waitUntil { spokenFallback.contains(expectedHangupTts) }
        val disconnectIdx = sequence.indexOf("live_disconnected")
        val speakEndIdx = sequence.indexOf("speak:$expectedHangupTts")
        assertTrue("Expected disconnect before hangup TTS, got $sequence", disconnectIdx in 0 until speakEndIdx)
    }

    @Test
    fun `during active live call session finishAndSpeak suppresses local playback to protect call audio channel`() = runBlocking {
        val playedClauses = CopyOnWriteArrayList<String>()
        val fallbackClauses = CopyOnWriteArrayList<String>()
        val focusCoord = RecordingVoiceAudioFocusCoordinator()
        val cacheDir = tempFolder.newFolder("live_suppression_cache")
        val cacheManager = VoiceAudioCacheManager(cacheDir)

        val connectedLiveClient = object : GeminiLiveClient(apiKey = "AIzaSyTest") {
            val realtimeTexts = CopyOnWriteArrayList<String>()
            override val isConnected: Boolean = true
            override fun sendRealtimeText(text: String) {
                realtimeTexts.add(text)
            }
        }

        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = connectedLiveClient,
            elevenLabsClient = null,
            voiceCacheManager = cacheManager,
            customMp3Player = { file ->
                playedClauses.add(file.name)
                true
            },
            audioFocusCoordinator = focusCoord,
            systemTtsFallbackHook = { text, _ -> fallbackClauses.add(text) }
        )

        engine.updateElevenLabsConfig(
            ElevenLabsRuntimeConfig(
                enabled = true,
                voiceId = "gepy",
                modelId = "eleven_flash_v2_5"
            )
        )

        engine.finishAndSpeak("Tugas selesai dijalankan.")

        // Memastikan tidak ada MP3 atau fallback TTS yang diputar saat Live Call aktif
        assertTrue("Tidak boleh memutar file MP3 selama Live Call aktif", playedClauses.isEmpty())
        assertTrue("Tidak boleh memanggil fallback TTS selama Live Call aktif", fallbackClauses.isEmpty())
        // Memastikan status dialihkan via realtime text ke stream Gemini Live
        assertEquals(1, connectedLiveClient.realtimeTexts.size)
        assertTrue(connectedLiveClient.realtimeTexts.first().contains("Tugas selesai dijalankan"))
    }

    @Test
    fun `setLiveCallOutputScale clamps 0 to 2, mutes playback timer cleanly, and barge-in advances liveAudioEpoch`() = runBlocking {
        val fakeLiveClient = GeminiLiveClient(apiKey = "AIzaSyTestLiveVolume")
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = fakeLiveClient,
            audioFocusCoordinator = RecordingVoiceAudioFocusCoordinator()
        )
        waitUntil { fakeLiveClient.eventSubscriptionCountForTest > 0 }

        // Clamp ke rentang aman 0f..2f
        engine.setLiveCallOutputScale(3.5f)
        assertEquals(2.0f, engine.liveSpeakerGain, 0.001f)
        engine.setLiveCallOutputScale(-1.0f)
        assertEquals(0.0f, engine.liveSpeakerGain, 0.001f)

        // Saat volume skala 0% (mute), estimasi pemutaran harus tetap 0 agar VAD tidak salah mengira speaker berbunyi
        fakeLiveClient.directAudioChunkCallback?.invoke(ByteArray(960) { 0x20 })
        assertEquals(0L, engine.currentPlaybackEndEpochForTest)

        // Saat volume dinaikkan ke 125% (1.25f), chunk masuk memperbarui estimasi playback lalu Barge-In langsung menaikkan epoch & reset ke 0
        engine.setLiveCallOutputScale(1.25f)
        val epochBefore = engine.currentLiveAudioEpochForTest
        fakeLiveClient.directAudioChunkCallback?.invoke(ByteArray(960) { 0x20 })
        assertTrue(engine.currentPlaybackEndEpochForTest > 0L)

        // Simulasi Barge-In dari event interupsi server
        fakeLiveClient.emitParsedEventForTest(GeminiLiveParsedEvent(interrupted = true))
        waitUntil {
            engine.currentLiveAudioEpochForTest > epochBefore && engine.currentPlaybackEndEpochForTest == 0L
        }
    }

    @Test
    fun `pushHudState preserves isLiveCallActive true while Gemini Live is RECONNECTING`() = runBlocking {
        val liveClient = GeminiLiveClient(apiKey = "AIzaSyTestReconnectHud")
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = liveClient,
            audioFocusCoordinator = RecordingVoiceAudioFocusCoordinator()
        )

        liveClient.emitConnectionStateForTest(LiveConnectionState.RECONNECTING)
        waitUntil {
            engine.hudStateFlow.value.headline.contains("Menyambung Ulang", ignoreCase = true)
        }
        assertTrue(
            "OverlayHudState.isLiveCallActive harus tetap true saat RECONNECTING",
            engine.hudStateFlow.value.isLiveCallActive
        )
    }

    @Test
    fun `live call output transcription updates overlay bubble text and transitions cleanly on turnComplete`() = runBlocking {
        val liveClient = GeminiLiveClient(apiKey = "AIzaSyTestLiveTranscriptHud")
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = liveClient,
            audioFocusCoordinator = RecordingVoiceAudioFocusCoordinator()
        )
        waitUntil { liveClient.eventSubscriptionCountForTest > 0 }

        liveClient.emitConnectionStateForTest(LiveConnectionState.CONNECTED)
        waitUntil {
            engine.hudStateFlow.value.activityKind == OverlayHudActivityKind.LIVE_LISTENING
        }

        liveClient.emitParsedEventForTest(
            GeminiLiveParsedEvent(outputTranscript = "Halo,")
        )
        liveClient.emitParsedEventForTest(
            GeminiLiveParsedEvent(outputTranscript = "cuaca hari ini cerah.")
        )
        waitUntil {
            engine.hudStateFlow.value.activityKind == OverlayHudActivityKind.LIVE_SPEAKING &&
                engine.hudStateFlow.value.subtitle.contains("cuaca hari ini cerah.")
        }

        val speakingHud = engine.hudStateFlow.value
        assertEquals("Gepy Berbicara...", speakingHud.statusPillLabel)
        assertTrue(speakingHud.showStopButton)
        assertTrue(speakingHud.subtitle.contains("Halo, cuaca hari ini cerah."))

        liveClient.emitParsedEventForTest(GeminiLiveParsedEvent(turnComplete = true))
        waitUntil {
            engine.hudStateFlow.value.activityKind == OverlayHudActivityKind.LIVE_LISTENING
        }

        val postTurnHud = engine.hudStateFlow.value
        assertEquals("Mendengarkan...", postTurnHud.statusPillLabel)
        assertTrue(postTurnHud.isLiveCallActive)
        assertTrue(postTurnHud.subtitle.contains("Halo, cuaca hari ini cerah."))
    }

    @Test
    fun `overlay stop button Option A interrupts speaking turn without closing live call and closes call when listening`() = runBlocking {
        val liveClient = GeminiLiveClient(apiKey = "AIzaSyTestStopOptionA")
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = liveClient,
            audioFocusCoordinator = RecordingVoiceAudioFocusCoordinator()
        )
        waitUntil { liveClient.eventSubscriptionCountForTest > 0 }

        liveClient.emitConnectionStateForTest(LiveConnectionState.CONNECTED)
        waitUntil { engine.isLiveCallActive }

        // Gepy sedang berbicara (LIVE_SPEAKING) -> tekan Stop (Opsi A): hentikan giliran bicara saja, call tetap aktif
        liveClient.emitParsedEventForTest(
            GeminiLiveParsedEvent(outputTranscript = "Sedang membacakan berita panjang...")
        )
        waitUntil {
            engine.hudStateFlow.value.activityKind == OverlayHudActivityKind.LIVE_SPEAKING
        }

        engine.onOverlayCancelListen()
        waitUntil {
            engine.hudStateFlow.value.activityKind == OverlayHudActivityKind.LIVE_LISTENING &&
                engine.hudStateFlow.value.statusPillLabel == "Mendengarkan..."
        }
        assertTrue(
            "Opsi A: menekan Stop saat Gepy berbicara harus mempertahankan sesi Live Call tetap aktif",
            engine.isLiveCallActive
        )

        // Gepy tidak sedang berbicara (LIVE_LISTENING) -> tekan Stop lagi: tutup sesi Live Call
        engine.onOverlayCancelListen()
        waitUntil {
            !engine.isLiveCallActive &&
                engine.hudStateFlow.value.mode == OverlayVisualMode.IDLE
        }
        assertFalse(engine.isLiveCallActive)
        assertEquals(OverlayVisualMode.IDLE, engine.hudStateFlow.value.mode)
    }

    @Test
    fun `concurrent live output transcript and cancel listen remain thread safe and dispose cleans up safely`() = runBlocking {
        val liveClient = GeminiLiveClient(apiKey = "AIzaSyTestConcurrentTranscript")
        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = liveClient,
            audioFocusCoordinator = RecordingVoiceAudioFocusCoordinator()
        )
        waitUntil { liveClient.eventSubscriptionCountForTest > 0 }
        liveClient.emitConnectionStateForTest(LiveConnectionState.CONNECTED)
        waitUntil { engine.isLiveCallActive }

        for (i in 1..25) {
            liveClient.emitParsedEventForTest(GeminiLiveParsedEvent(outputTranscript = "Kata $i "))
            if (i % 5 == 0) {
                waitUntil { engine.isLiveAssistantCurrentlySpeaking() }
                // Emit token susulan tepat bersamaan dengan interrupt Opsi A untuk menguji thread-safety buffer
                liveClient.emitParsedEventForTest(GeminiLiveParsedEvent(outputTranscript = "susulan "))
                engine.onOverlayCancelListen()
            }
        }
        liveClient.emitParsedEventForTest(GeminiLiveParsedEvent(turnComplete = true))
        waitUntil {
            engine.isLiveCallActive &&
                engine.hudStateFlow.value.activityKind == OverlayHudActivityKind.LIVE_LISTENING
        }

        // Pastikan dispose tidak memicu exception meski diikuti panggilan stop
        engine.dispose()
        assertFalse(engine.isLiveCallActive)
    }
}
