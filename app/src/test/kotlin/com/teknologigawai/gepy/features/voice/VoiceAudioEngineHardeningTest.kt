package com.teknologigawai.gepy.features.voice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Suite pengujian konfirmasi keras **Temuan 1 (P1 TTS Timeout -> AudioFocus Collision)**
 * dan **Temuan 3 (P2 Visibilitas Lintas-Thread & Atomisitas Modal Disambiguasi/HITL)**
 * di [`VoiceAudioEngine`].
 */
class VoiceAudioEngineHardeningTest {

    private class RecordingFocusCoordinator : VoiceAudioFocusCoordinator {
        val speechAcquireCount = AtomicInteger(0)
        val speechAbandonCount = AtomicInteger(0)
        val listeningAcquireCount = AtomicInteger(0)
        val listeningAbandonCount = AtomicInteger(0)
        val timeline = CopyOnWriteArrayList<String>()

        override fun acquireSpeechFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
            speechAcquireCount.incrementAndGet()
            timeline.add("acquire_speech")
            return true
        }

        override fun abandonSpeechFocus() {
            speechAbandonCount.incrementAndGet()
            timeline.add("abandon_speech")
        }

        override fun acquireCallFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean = true

        override fun abandonCallFocus() {}

        override fun acquireListeningFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
            listeningAcquireCount.incrementAndGet()
            timeline.add("acquire_listening")
            return true
        }

        override fun abandonListeningFocus() {
            listeningAbandonCount.incrementAndGet()
            timeline.add("abandon_listening")
        }
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `VoiceAudioEngineTimeoutTest mematikan TTS dan melepas speech focus sebelum listening saat askDisambiguation timeout`() = runBlocking {
        val server = LoopbackHttpServer {
            LoopbackHttpResponse(200, "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33, 0x05))
        }
        server.start()

        try {
            val focus = RecordingFocusCoordinator()
            val playerStarted = CountDownLatch(1)
            val playerCompletedNaturally = AtomicBoolean(false)
            val playerCancelled = AtomicBoolean(false)

            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                retryBackoffMs = listOf(1L),
                jitterMaxMs = 0L
            )
            val cache = VoiceAudioCacheManager(rootDir = tempFolder.newFolder("timeout_disambig_cache"))

            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = cache,
                customMp3Player = { _ ->
                    playerStarted.countDown()
                    try {
                        delay(1_200L)
                        playerCompletedNaturally.set(true)
                        true
                    } catch (ce: CancellationException) {
                        playerCancelled.set(true)
                        throw ce
                    }
                },
                audioFocusCoordinator = focus,
                shortVoiceReplyStarter = { onSpoken ->
                    focus.timeline.add("stt_started")
                    onSpoken("budi santoso")
                },
                criticalSpeechAwaitTimeoutMs = 300L
            )
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_timeout_test",
                    voiceId = "gepy"
                )
            )

            val startMs = System.currentTimeMillis()
            val chosen = engine.askDisambiguation(
                questionTts = "Pilih kontak Budi yang mana ya?",
                options = listOf("Budi Santoso", "Budi Hartono")
            )
            val elapsedMs = System.currentTimeMillis() - startMs

            assertEquals("Budi Santoso", chosen)
            assertTrue("Harus menunggu timeout pendek, bukan 1.2s pemutar lambat (elapsed=${elapsedMs}ms)", elapsedMs < 900L)
            assertTrue("Pemutar MP3 harus sempat berjalan lalu dibatalkan", playerStarted.await(2, TimeUnit.SECONDS))
            waitUntil { playerCancelled.get() }
            assertFalse("Pemutar lambat tidak boleh dibiarkan selesai alami saat sudah timeout", playerCompletedNaturally.get())

            val abandonIdx = focus.timeline.indexOf("abandon_speech")
            val listenIdx = focus.timeline.indexOf("acquire_listening")
            val sttIdx = focus.timeline.indexOf("stt_started")
            assertTrue("abandon_speech wajib terjadi sebelum acquire_listening: ${focus.timeline}", abandonIdx in 0 until listenIdx)
            assertTrue("acquire_listening wajib terjadi sebelum stt_started: ${focus.timeline}", listenIdx in 0 until sttIdx)
            assertEquals(1, focus.speechAbandonCount.get())
            assertEquals(1, focus.listeningAcquireCount.get())
            assertEquals(1, focus.listeningAbandonCount.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `VoiceAudioEngineTimeoutTest mematikan TTS dan melepas speech focus sebelum listening saat requestHitlApproval timeout`() = runBlocking {
        val server = LoopbackHttpServer {
            LoopbackHttpResponse(200, "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33, 0x06))
        }
        server.start()

        try {
            val focus = RecordingFocusCoordinator()
            val playerCancelled = AtomicBoolean(false)

            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                retryBackoffMs = listOf(1L),
                jitterMaxMs = 0L
            )
            val cache = VoiceAudioCacheManager(rootDir = tempFolder.newFolder("timeout_hitl_cache"))

            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = cache,
                customMp3Player = { _ ->
                    try {
                        delay(1_200L)
                        true
                    } catch (ce: CancellationException) {
                        playerCancelled.set(true)
                        throw ce
                    }
                },
                audioFocusCoordinator = focus,
                shortVoiceReplyStarter = { onSpoken ->
                    focus.timeline.add("stt_started")
                    onSpoken("ya lanjut kirim")
                },
                criticalSpeechAwaitTimeoutMs = 300L
            )
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_timeout_hitl",
                    voiceId = "gepy"
                )
            )

            val approved = engine.requestHitlApproval(
                summaryPrompt = "Kirim pesan ke Budi sekarang?",
                targetLabel = "Kirim"
            )

            assertTrue(approved)
            waitUntil { playerCancelled.get() }
            val abandonIdx = focus.timeline.indexOf("abandon_speech")
            val listenIdx = focus.timeline.indexOf("acquire_listening")
            assertTrue("abandon_speech wajib mendahului acquire_listening pada timeout HITL: ${focus.timeline}", abandonIdx in 0 until listenIdx)
            assertEquals(1, focus.speechAbandonCount.get())
            assertEquals(1, focus.listeningAbandonCount.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `DisambiguationAndHitlConcurrentChoiceTest menyelesaikan pilihan tunggal secara atomik tanpa double stop atau exception`() = runBlocking {
        val options = listOf("Kandidat Pertama", "Kandidat Kedua")

        repeat(20) { iteration ->
            val focus = RecordingFocusCoordinator()
            val liveClient = object : GeminiLiveClient(apiKey = "AIzaConcurrentDisambig$iteration") {
                override val isConnected: Boolean = false
                override fun sendRealtimeText(text: String) {}
            }
            val capturedSttCallback = AtomicReference<((String) -> Unit)?>(null)
            val sttReady = CountDownLatch(1)

            val engine = VoiceAudioEngine(
                context = null,
                geminiLiveClient = liveClient,
                audioFocusCoordinator = focus,
                customTtsSpeaker = { _ -> },
                shortVoiceReplyStarter = { cb ->
                    capturedSttCallback.set(cb)
                    sttReady.countDown()
                }
            )

            val modalJob = async(Dispatchers.Default) {
                engine.askDisambiguation("Pilih kontak ($iteration)?", options)
            }

            assertTrue("STT disambiguasi tidak kunjung siap", sttReady.await(2, TimeUnit.SECONDS))

            // Adu serentak 3 sumber lintas dispatcher: UI tap, STT lokal, dan event transkrip Gemini Live
            val racers = listOf(
                launch(Dispatchers.Default) { engine.onDisambiguationChosen("Kandidat Pertama") },
                launch(Dispatchers.IO) { capturedSttCallback.get()?.invoke("kandidat kedua") },
                launch(Dispatchers.Default) {
                    liveClient.emitParsedEventForTest(
                        GeminiLiveParsedEvent(finalInputTranscript = "kandidat pertama")
                    )
                },
                launch(Dispatchers.IO) { engine.onDisambiguationChosen("Kandidat Kedua") }
            )
            racers.forEach { it.join() }

            val winner = modalJob.await()
            assertTrue("Pemenang harus berasal dari daftar kandidat valid, dapat='$winner'", winner in options)
            assertEquals("Listening focus harus dilepas tepat 1x per modal", 1, focus.listeningAbandonCount.get())
        }

        // Lakukan balapan atomik yang sama untuk alur persetujuan keamanan HITL
        repeat(20) { iteration ->
            val focus = RecordingFocusCoordinator()
            val liveClient = object : GeminiLiveClient(apiKey = "AIzaConcurrentHitl$iteration") {
                override val isConnected: Boolean = false
                override fun sendRealtimeText(text: String) {}
            }
            val capturedHitlStt = AtomicReference<((String) -> Unit)?>(null)
            val sttReady = CountDownLatch(1)

            val engine = VoiceAudioEngine(
                context = null,
                geminiLiveClient = liveClient,
                audioFocusCoordinator = focus,
                customTtsSpeaker = { _ -> },
                shortVoiceReplyStarter = { cb ->
                    capturedHitlStt.set(cb)
                    sttReady.countDown()
                }
            )

            val hitlJob = async(Dispatchers.Default) {
                engine.requestHitlApproval("Eksekusi aksi ke-$iteration?", "Simpan")
            }

            assertTrue("STT HITL tidak kunjung siap", sttReady.await(2, TimeUnit.SECONDS))

            listOf(
                async(Dispatchers.Default) { engine.onHitlDecision(true) },
                async(Dispatchers.IO) { capturedHitlStt.get()?.invoke("batal jangan") },
                async(Dispatchers.Default) {
                    liveClient.emitParsedEventForTest(
                        GeminiLiveParsedEvent(finalInputTranscript = "ya eksekusi")
                    )
                },
                async(Dispatchers.IO) { engine.onHitlDecision(false) }
            ).awaitAll()

            hitlJob.await()
            assertEquals("Listening focus HITL harus dilepas tepat 1x", 1, focus.listeningAbandonCount.get())
        }
    }

    @Test
    fun `isPlaybackActiveReflectsAndroidTtsSpeaking`() = runBlocking {
        val engine = VoiceAudioEngine()
        assertFalse("Playback awalnya harus tidak aktif", engine.isPlaybackActive)

        var isTtsSpeaking = true
        engine.androidTtsSpeakingProvider = { isTtsSpeaking }

        assertTrue("isPlaybackActive harus bernilai true saat Android TTS sedang berbicara", engine.isPlaybackActive)

        isTtsSpeaking = false
        assertFalse("isPlaybackActive harus bernilai false saat Android TTS selesai", engine.isPlaybackActive)
    }

    @Test
    fun `liveTurnTranscriptSpacingHandlesLeadingSpacesAndPunctuation`() = runBlocking {
        val client = GeminiLiveClient()
        val engine = VoiceAudioEngine(geminiLiveClient = client)

        // Tunggu hingga coroutine VoiceAudioEngine aktif mendengarkan eventsFlow
        waitUntil { client.eventSubscriptionCountForTest > 0 }

        client.emitParsedEventForTest(GeminiLiveParsedEvent(outputTranscript = "Halo"))
        client.emitParsedEventForTest(GeminiLiveParsedEvent(outputTranscript = ", "))
        client.emitParsedEventForTest(GeminiLiveParsedEvent(outputTranscript = "apa"))
        client.emitParsedEventForTest(GeminiLiveParsedEvent(outputTranscript = " kabar"))

        waitUntil { engine.hudStateFlow.value.subtitle == "Halo, apa kabar" }
        assertEquals("Halo, apa kabar", engine.hudStateFlow.value.subtitle)
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_500L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            delay(15L)
        }
        assertTrue("Timeout menunggu kondisi selesai", predicate())
    }
}
