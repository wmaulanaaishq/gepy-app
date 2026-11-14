package com.teknologigawai.gepy.features.voice

import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pengujian cache-first ElevenLabs, prefetch paralel, anti-mixed-voice, preview draf & pemutaran offline VoiceAudioEngine.
 */
class VoiceAudioEngineElevenLabsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `finishAndSpeak synthesizes via ElevenLabs on cache miss and replays from disk cache with zero network calls on repeat`() = runBlocking {
        val apiCalls = AtomicInteger(0)
        val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x55, 0x66)

        val server = LoopbackHttpServer {
            apiCalls.incrementAndGet()
            LoopbackHttpResponse(200, "audio/mpeg", fakeMp3)
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1")
            val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_engine_cache"))
            val playedFiles = CopyOnWriteArrayList<File>()

            val engine = VoiceAudioEngine(
                context = null,
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
                    apiKey = "sk_test_engine_key",
                    voiceId = "gepy_voice",
                    modelId = "eleven_v4_turbo"
                )
            )

            // 1. Panggilan pertama -> Cache Miss -> Unduh 1x dari API & simpan ke disk cache
            engine.finishAndSpeak("Halo sayang, pesan sudah terkirim.")
            waitUntil { playedFiles.size >= 1 }
            assertEquals(1, apiCalls.get())
            assertEquals(1, cacheManager.getStats().clipCount)

            // 2. Panggilan kedua dengan kalimat identik -> Cache Hit (0 ms, 0 credit, 0 HTTP call tambahan!)
            engine.finishAndSpeak("Halo sayang, pesan sudah terkirim.")
            waitUntil { playedFiles.size >= 2 }
            assertEquals(1, apiCalls.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `updating ElevenLabs slider or model invalidates stale voice cache and synthesizes fresh audio`() = runBlocking {
        val apiCalls = AtomicInteger(0)
        val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x22)
        val server = LoopbackHttpServer {
            apiCalls.incrementAndGet()
            LoopbackHttpResponse(200, "audio/mpeg", fakeMp3)
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1")
            val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_slider_cache"))
            val playedFiles = CopyOnWriteArrayList<File>()
            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = cacheManager,
                customMp3Player = { f ->
                    playedFiles.add(f)
                    true
                }
            )

            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_slider",
                    voiceId = "gepy_voice",
                    modelId = "eleven_v4_turbo",
                    stability = 0.45f,
                    similarityBoost = 0.75f
                )
            )
            engine.finishAndSpeak("Selamat pagi.")
            waitUntil { playedFiles.size == 1 }
            assertEquals(1, apiCalls.get())

            // Ubah slider stability -> wajib mengunduh audio baru alih-alih memakai audio prosodi lama
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_slider",
                    voiceId = "gepy_voice",
                    modelId = "eleven_v4_turbo",
                    stability = 0.85f,
                    similarityBoost = 0.75f
                )
            )
            engine.finishAndSpeak("Selamat pagi.")
            waitUntil { playedFiles.size == 2 }
            assertEquals(2, apiCalls.get())
            assertEquals(2, cacheManager.getStats().clipCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `parallel clause prefetch runs bounded 2-at-a-time while playing clauses in strict order with utterance-level AudioFocus`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val fetchedTexts = CopyOnWriteArrayList<String>()

        val server = LoopbackHttpServer(handleConcurrently = true) { req ->
            val current = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { prev -> max(prev, current) }
            try {
                val parsedText = JSONObject(req.body.toString(Charsets.UTF_8)).getString("text")
                fetchedTexts.add(parsedText)
                Thread.sleep(70L)
                LoopbackHttpResponse(200, "audio/mpeg", parsedText.toByteArray(Charsets.UTF_8))
            } finally {
                inFlight.decrementAndGet()
            }
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                retryBackoffMs = listOf(10L),
                jitterMaxMs = 0L
            )
            val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_parallel_prefetch"))
            val focusCoordinator = RecordingVoiceAudioFocusCoordinator()
            val playedPayloads = CopyOnWriteArrayList<String>()

            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = cacheManager,
                customMp3Player = { file ->
                    val content = file.readText(Charsets.UTF_8)
                    playedPayloads.add(content)
                    focusCoordinator.timeline.add("play:$content")
                    delay(20L)
                    true
                },
                audioFocusCoordinator = focusCoordinator
            )
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_parallel",
                    voiceId = "gepy"
                )
            )

            engine.finishAndSpeak("Kalimat satu. Kalimat dua. Kalimat tiga. Kalimat empat.")
            waitUntil(timeoutMs = 5_000L) {
                playedPayloads.size == 4 && focusCoordinator.speechAbandonCount.get() >= 1
            }

            // Konkurensi prefetch terikat Semaphore(2)
            assertTrue("Expected concurrent HTTP requests >= 2, was ${maxConcurrent.get()}", maxConcurrent.get() >= 2)
            assertTrue("Expected bounded Semaphore(2), got ${maxConcurrent.get()}", maxConcurrent.get() <= 2)

            // Urutan pemutaran tetap deterministik 1 -> 2 -> 3 -> 4
            assertEquals(
                listOf("Kalimat satu.", "Kalimat dua.", "Kalimat tiga.", "Kalimat empat."),
                playedPayloads.toList()
            )

            // AudioFocus diakuisisi ONCE di awal ucapan & dilepas ONCE di akhir (tidak putus-nyambung antarklausa!)
            assertEquals(1, focusCoordinator.speechAcquireCount.get())
            assertEquals(1, focusCoordinator.speechAbandonCount.get())
            assertEquals("acquire_speech", focusCoordinator.timeline.first())
            assertEquals("abandon_speech", focusCoordinator.timeline.last())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `anti-mixed-voice prevents robotic mid-sentence fallback by default, allows full fallback on clause 0 fail, and honors explicit mid-utterance flag`() = runBlocking {
        val server = LoopbackHttpServer(handleConcurrently = true) { req ->
            val parsedText = JSONObject(req.body.toString(Charsets.UTF_8)).getString("text")
            when {
                parsedText.contains("Pertama sukses") -> LoopbackHttpResponse(200, "audio/mpeg", "OK1".toByteArray())
                parsedText.contains("Kedua gagal") -> {
                    Thread.sleep(60L)
                    LoopbackHttpResponse(402, "application/json", """{"detail":"payment_required"}""".toByteArray())
                }
                else -> LoopbackHttpResponse(401, "application/json", """{"detail":"unauthorized"}""".toByteArray())
            }
        }
        server.start()

        try {
            // 1. Default (allowMidUtteranceSystemFallback=false): klausa 0 sukses -> klausa 1 gagal permanen -> JANGAN campur robot Android TTS
            val client1 = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1", retryBackoffMs = listOf(5L), jitterMaxMs = 0L)
            val cache1 = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "anti_mix_default"))
            val focus1 = RecordingVoiceAudioFocusCoordinator()
            val played1 = CopyOnWriteArrayList<String>()
            val fallback1 = CopyOnWriteArrayList<String>()
            val engine1 = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client1,
                voiceCacheManager = cache1,
                customMp3Player = { f ->
                    played1.add(f.readText())
                    true
                },
                audioFocusCoordinator = focus1,
                systemTtsFallbackHook = { text, _ -> fallback1.add(text) }
            )
            engine1.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_ok",
                    voiceId = "gepy",
                    allowMidUtteranceSystemFallback = false
                )
            )
            engine1.finishAndSpeak("Pertama sukses. Kedua gagal.")
            waitUntil { played1.size == 1 && focus1.speechAbandonCount.get() == 1 }
            assertEquals(1, played1.size)
            assertTrue("Robot TTS tidak boleh mencampur di tengah kalimat saat allowMidUtteranceSystemFallback=false", fallback1.isEmpty())

            // 2. Jika allowMidUtteranceSystemFallback=true: klausa lanjutan tetap dibacakan TTS agar informasi tidak hilang
            val client2 = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1", retryBackoffMs = listOf(5L), jitterMaxMs = 0L)
            val cache2 = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "anti_mix_blind"))
            val focus2 = RecordingVoiceAudioFocusCoordinator()
            val played2 = CopyOnWriteArrayList<String>()
            val fallback2 = CopyOnWriteArrayList<String>()
            val engine2 = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client2,
                voiceCacheManager = cache2,
                customMp3Player = { f ->
                    played2.add(f.readText())
                    true
                },
                audioFocusCoordinator = focus2,
                systemTtsFallbackHook = { text, _ -> fallback2.add(text) }
            )
            engine2.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_ok",
                    voiceId = "gepy",
                    allowMidUtteranceSystemFallback = true
                )
            )
            engine2.finishAndSpeak("Pertama sukses. Kedua gagal.")
            waitUntil { played2.size == 1 && fallback2.size == 1 && focus2.speechAbandonCount.get() == 1 }
            assertEquals(listOf("Kedua gagal."), fallback2.toList())

            // 3. Klausa 0 gagal dari awal -> seluruh kalimat jatuh mulus ke 1 suara Android TTS seragam
            val client3 = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1", retryBackoffMs = listOf(5L), jitterMaxMs = 0L)
            val cache3 = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "anti_mix_clause0_fail"))
            val focus3 = RecordingVoiceAudioFocusCoordinator()
            val fallback3 = CopyOnWriteArrayList<String>()
            val engine3 = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client3,
                voiceCacheManager = cache3,
                audioFocusCoordinator = focus3,
                systemTtsFallbackHook = { text, _ -> fallback3.add(text) }
            )
            engine3.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_ok",
                    voiceId = "gepy",
                    allowMidUtteranceSystemFallback = false
                )
            )
            engine3.finishAndSpeak("Awal gagal. Lanjutannya juga ikut fallback.")
            waitUntil { fallback3.size == 1 && focus3.speechAbandonCount.get() == 1 }
            assertEquals(listOf("Awal gagal. Lanjutannya juga ikut fallback."), fallback3.toList())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `B1 previewUnsavedElevenLabsVoice uses draft parameters bypasses circuit breaker cleans temp file and leaves disk cache untouched`() = runBlocking {
        var capturedPath = ""
        var capturedBody = ""
        var return402 = true
        val hybridServer = LoopbackHttpServer { req ->
            if (return402) {
                LoopbackHttpResponse(402, "application/json", """{"detail":"quota_zero"}""".toByteArray())
            } else {
                capturedPath = req.path
                capturedBody = req.body.toString(Charsets.UTF_8)
                LoopbackHttpResponse(200, "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33, 0x09))
            }
        }
        hybridServer.start()

        try {
            val circuitClient = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${hybridServer.port}/v1",
                retryBackoffMs = listOf(1L),
                jitterMaxMs = 0L
            )
            // Buka circuit breaker sesi utama lebih dulu via HTTP 402
            circuitClient.synthesizeToFile("k", "v", "gagal dulu", File(tempFolder.root, "open_cb.mp3"))
            assertTrue(circuitClient.isCircuitOpen)
            return402 = false

            val cacheDir = File(tempFolder.root, "b1_preview_cache")
            val cacheManager = VoiceAudioCacheManager(rootDir = cacheDir)
            val playedFiles = CopyOnWriteArrayList<String>()
            val focus = RecordingVoiceAudioFocusCoordinator()
            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = circuitClient,
                voiceCacheManager = cacheManager,
                customMp3Player = { file ->
                    playedFiles.add(file.name)
                    true
                },
                audioFocusCoordinator = focus
            )

            val draft = ElevenLabsRuntimeConfig(
                enabled = false, // draf belum diaktifkan/disimpan
                apiKey = "sk_draft_123",
                voiceId = "draft_voice_xyz",
                modelId = "eleven_flash_v2_5",
                stability = 0.22f,
                similarityBoost = 0.91f
            )
            val result = engine.previewUnsavedElevenLabsVoice(draft, "Halo tes suara draf.")
            assertTrue("Expected preview success even while circuit is open, got $result", result.isSuccess)
            assertTrue(capturedPath.contains("/text-to-speech/draft_voice_xyz"))
            val reqJson = JSONObject(capturedBody)
            assertEquals("eleven_flash_v2_5", reqJson.getString("model_id"))
            assertEquals(0.22, reqJson.getJSONObject("voice_settings").getDouble("stability"), 0.02)
            assertEquals(0.91, reqJson.getJSONObject("voice_settings").getDouble("similarity_boost"), 0.02)
            assertEquals(listOf("gepy_el_draft_preview.mp3"), playedFiles.toList())
            assertEquals(0, cacheManager.getStats().clipCount)
            assertEquals(1, focus.speechAcquireCount.get())
            assertEquals(1, focus.speechAbandonCount.get())
            val tempPreview = File(System.getProperty("java.io.tmpdir") ?: ".", "gepy_el_draft_preview.mp3")
            assertFalse(tempPreview.exists())
        } finally {
            hybridServer.stop()
        }
    }

    @Test
    fun `identical repeated clauses in one utterance and repeated playback deduplicate API calls and disk cache entries`() = runBlocking {
        val apiCalls = AtomicInteger(0)
        val server = LoopbackHttpServer(handleConcurrently = true) { req ->
            apiCalls.incrementAndGet()
            val parsedText = JSONObject(req.body.toString(Charsets.UTF_8)).getString("text")
            Thread.sleep(60L)
            LoopbackHttpResponse(200, "audio/mpeg", parsedText.toByteArray(Charsets.UTF_8))
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                retryBackoffMs = listOf(10L),
                jitterMaxMs = 0L
            )
            val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_dedup_cache"))
            val focus = RecordingVoiceAudioFocusCoordinator()
            val playedTexts = CopyOnWriteArrayList<String>()
            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = cacheManager,
                customMp3Player = { f ->
                    playedTexts.add(f.readText(Charsets.UTF_8))
                    true
                },
                audioFocusCoordinator = focus
            )
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_dedup",
                    voiceId = "gepy"
                )
            )

            // 1. Klausa identik berulang di dalam satu pesan -> tetap memutar keduanya berurutan, tapi hanya 1 panggilan API & 1 file cache!
            engine.finishAndSpeak("Oke Maulana. Oke Maulana.")
            waitUntil { playedTexts.size == 2 && focus.speechAbandonCount.get() == 1 }
            assertEquals(listOf("Oke Maulana.", "Oke Maulana."), playedTexts.toList())
            assertEquals(1, apiCalls.get())
            assertEquals(1, cacheManager.getStats().clipCount)
            assertEquals(1, cacheManager.getAllCachedClipsSnapshot().size)

            // 2. Diputar ulang lagi dengan kapitalisasi/spasi berbeda -> 0 panggilan API tambahan & tetap 1 klip di cache!
            engine.finishAndSpeak("  oke   MAULANA. ")
            waitUntil { playedTexts.size == 3 && focus.speechAbandonCount.get() == 2 }
            assertEquals(1, apiCalls.get())
            assertEquals(1, cacheManager.getStats().clipCount)
            assertEquals(1, cacheManager.getAllCachedClipsSnapshot().size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `offline cache-first replays cached custom clips with empty apiKey and null client including slider fallback`() = runBlocking {
        val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_offline_vault"))
        val clause0 = "Oke Maulana."
        val clause1 = "Aku bukain Spotify ya."
        val f0 = cacheManager.prepareTargetFile("gepy_voice", clause0, stability = 0.45f, similarityBoost = 0.75f)
        f0.writeText(clause0, Charsets.UTF_8)
        cacheManager.registerCachedClip("gepy_voice", clause0, f0, stability = 0.45f, similarityBoost = 0.75f)

        val f1 = cacheManager.prepareTargetFile("gepy_voice", clause1, stability = 0.45f, similarityBoost = 0.75f)
        f1.writeText(clause1, Charsets.UTF_8)
        cacheManager.registerCachedClip("gepy_voice", clause1, f1, stability = 0.45f, similarityBoost = 0.75f)

        val focus = RecordingVoiceAudioFocusCoordinator()
        val playedTexts = CopyOnWriteArrayList<String>()
        val ttsFallback = CopyOnWriteArrayList<String>()
        val offlineEngine = VoiceAudioEngine(
            context = null,
            elevenLabsClient = null,
            voiceCacheManager = cacheManager,
            customMp3Player = { f ->
                playedTexts.add(f.readText(Charsets.UTF_8))
                true
            },
            audioFocusCoordinator = focus,
            systemTtsFallbackHook = { text, _ -> ttsFallback.add(text) }
        )
        // API Key dikosongkan dan slider berubah -> tetap putar brankas cache custom Gepy saat offline!
        offlineEngine.updateElevenLabsConfig(
            ElevenLabsRuntimeConfig(
                enabled = true,
                apiKey = "",
                voiceId = "gepy_voice",
                modelId = "eleven_flash_v2_5",
                stability = 0.70f,
                similarityBoost = 0.85f
            )
        )

        offlineEngine.finishAndSpeak("$clause0 $clause1")
        waitUntil { playedTexts.size == 2 && focus.speechAbandonCount.get() == 1 }
        assertEquals(listOf(clause0, clause1), playedTexts.toList())
        assertTrue("Cache offline penuh tidak boleh jatuh ke Android TTS", ttsFallback.isEmpty())
    }

    @Test
    fun `Pembuka Tetap plus Detail pattern reuses cached Clause 0 with zero extra API calls across songs`() = runBlocking {
        val fetchedClauses = CopyOnWriteArrayList<String>()
        val server = LoopbackHttpServer(handleConcurrently = true) { req ->
            val txt = JSONObject(req.body.toString(Charsets.UTF_8)).getString("text")
            fetchedClauses.add(txt)
            LoopbackHttpResponse(200, "audio/mpeg", txt.toByteArray(Charsets.UTF_8))
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1")
            val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_opener_reuse"))
            val focus = RecordingVoiceAudioFocusCoordinator()
            val played = CopyOnWriteArrayList<String>()
            val engine = VoiceAudioEngine(
                context = null,
                elevenLabsClient = client,
                voiceCacheManager = cacheManager,
                customMp3Player = { f ->
                    played.add(f.readText(Charsets.UTF_8))
                    true
                },
                audioFocusCoordinator = focus
            )
            engine.updateElevenLabsConfig(
                ElevenLabsRuntimeConfig(
                    enabled = true,
                    apiKey = "sk_opener_reuse",
                    voiceId = "gepy"
                )
            )

            val girlCtx = SovereignSpeechContext(
                configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
                ownerName = "Maulana",
                wellness = PersonalWellnessContext(
                    ownerName = "Maulana",
                    isGuestPrivacyShieldActive = false
                )
            )
            val utterance1 = SovereignNaturalSpeech.spotifyPlay(girlCtx, "Bernadya Satu Bulan", "phone")
            val utterance2 = SovereignNaturalSpeech.spotifyPlay(girlCtx, "Tulus Hati Hati Di Jalan", "phone")

            engine.finishAndSpeak(utterance1)
            waitUntil { played.size == 2 && focus.speechAbandonCount.get() == 1 }
            assertEquals(2, fetchedClauses.size)
            assertEquals("[warmly] Oke Maulana.", played[0])
            assertTrue(played[1].contains("Bernadya Satu Bulan"))
            assertTrue(fetchedClauses.contains("[warmly] Oke Maulana."))
            assertTrue(fetchedClauses.any { it.contains("Bernadya Satu Bulan") })

            engine.finishAndSpeak(utterance2)
            waitUntil { played.size == 4 && focus.speechAbandonCount.get() == 2 }
            // Hanya klausa detail lagu kedua yang memicu 1 request API baru; pembuka "[warmly] Oke Maulana." 0 request!
            assertEquals(3, fetchedClauses.size)
            assertEquals(1, fetchedClauses.count { it == "[warmly] Oke Maulana." })
            assertTrue(fetchedClauses[2].contains("Tulus Hati Hati Di Jalan"))
            assertEquals("[warmly] Oke Maulana.", played[2])
            assertTrue(played[3].contains("Tulus Hati Hati Di Jalan"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `offline mixed-clause utterance keeps uniform Android TTS by default and plays cached opener first when mid-utterance fallback enabled`() = runBlocking {
        val cacheManager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "el_offline_partial"))
        val cachedOpener = "[warmly] Oke Maulana."
        val openerFile = cacheManager.prepareTargetFile("gepy", cachedOpener)
        openerFile.writeText(cachedOpener, Charsets.UTF_8)
        cacheManager.registerCachedClip("gepy", cachedOpener, openerFile)

        val uncachedDetail = "Aku puterin 'Lagu Baru' di Spotify ya."
        val mixedSentence = "$cachedOpener $uncachedDetail"

        // 1. Default (allowMidUtteranceSystemFallback=false): Klausa 1 miss saat offline -> alihkan seluruh kalimat utuh ke Android TTS
        val focusDefault = RecordingVoiceAudioFocusCoordinator()
        val playedDefault = CopyOnWriteArrayList<String>()
        val fallbackDefault = CopyOnWriteArrayList<String>()
        val defaultEngine = VoiceAudioEngine(
            context = null,
            elevenLabsClient = null,
            voiceCacheManager = cacheManager,
            customMp3Player = { f ->
                playedDefault.add(f.readText(Charsets.UTF_8))
                true
            },
            audioFocusCoordinator = focusDefault,
            systemTtsFallbackHook = { text, _ -> fallbackDefault.add(text) }
        )
        defaultEngine.updateElevenLabsConfig(
            ElevenLabsRuntimeConfig(
                enabled = true,
                apiKey = "",
                voiceId = "gepy",
                allowMidUtteranceSystemFallback = false
            )
        )
        defaultEngine.finishAndSpeak(mixedSentence)
        waitUntil { fallbackDefault.size == 1 && focusDefault.speechAbandonCount.get() == 1 }
        assertTrue("Tidak boleh memutar setengah kalimat custom lalu membuang detail", playedDefault.isEmpty())
        assertEquals(listOf("Oke Maulana. $uncachedDetail"), fallbackDefault.toList())

        // 2. Jika allowMidUtteranceSystemFallback=true: putar klip cache pembuka terlebih dahulu, lalu lanjutkan detail lewat Android TTS
        val focusBlind = RecordingVoiceAudioFocusCoordinator()
        val playedBlind = CopyOnWriteArrayList<String>()
        val fallbackBlind = CopyOnWriteArrayList<String>()
        val blindEngine = VoiceAudioEngine(
            context = null,
            elevenLabsClient = null,
            voiceCacheManager = cacheManager,
            customMp3Player = { f ->
                playedBlind.add(f.readText(Charsets.UTF_8))
                true
            },
            audioFocusCoordinator = focusBlind,
            systemTtsFallbackHook = { text, _ -> fallbackBlind.add(text) }
        )
        blindEngine.updateElevenLabsConfig(
            ElevenLabsRuntimeConfig(
                enabled = true,
                apiKey = "",
                voiceId = "gepy",
                allowMidUtteranceSystemFallback = true
            )
        )
        blindEngine.finishAndSpeak(mixedSentence)
        waitUntil { playedBlind.size == 1 && fallbackBlind.size == 1 && focusBlind.speechAbandonCount.get() == 1 }
        assertEquals(listOf(cachedOpener), playedBlind.toList())
        assertEquals(listOf(uncachedDetail), fallbackBlind.toList())
    }
}
