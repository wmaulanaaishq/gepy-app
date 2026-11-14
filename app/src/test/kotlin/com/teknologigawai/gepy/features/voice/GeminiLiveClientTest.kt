package com.teknologigawai.gepy.features.voice

import com.teknologigawai.gepy.core.accessibility.GepyFeedbackPattern
import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.skills.AndroidSystemToolSkill
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.service.GepyAccessibilityService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveClientTest {

    private class FakeConnectedGeminiLiveClient : GeminiLiveClient(apiKey = "dummy_key") {
        val sentRealtimeTexts = mutableListOf<String>()

        override val isConnected: Boolean
            get() = true

        override fun sendRealtimeText(text: String) {
            sentRealtimeTexts.add(text)
        }
    }

    private fun buildPcm16Frame(sampleCount: Int, amplitude: Short): ByteArray {
        val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            buffer.putShort(amplitude)
        }
        return buffer.array()
    }

    @Test
    fun `LocalPcmVad memicu speechStarted dalam 3 frame dan speechEnded saat hening`() {
        val vad = LocalPcmVad(
            rmsThreshold = 0.03,
            speechTriggerFrames = 3,
            silenceEndpointFrames = 4
        )
        val loud = buildPcm16Frame(sampleCount = 320, amplitude = 6000)
        val silent = buildPcm16Frame(sampleCount = 320, amplitude = 10)

        assertFalse(vad.processFrame(loud).speechStarted)
        assertFalse(vad.processFrame(loud).speechStarted)
        val started = vad.processFrame(loud)
        assertTrue(started.speechStarted)
        assertTrue(vad.isInSpeech)

        assertFalse(vad.processFrame(silent).speechEnded)
        assertFalse(vad.processFrame(silent).speechEnded)
        assertFalse(vad.processFrame(silent).speechEnded)
        val ended = vad.processFrame(silent)
        assertTrue(ended.speechEnded)
        assertFalse(vad.isInSpeech)
    }

    @Test
    fun `GeminiLiveClient membangun setup gemini-3_8-live NON_BLOCKING dan mengurai event server`() {
        val client = GeminiLiveClient(apiKey = "test_gemini_key")
        val setup = client.buildSetupMessageJson().getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))

        val genConfig = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", genConfig.getJSONArray("responseModalities").getString(0))
        val speechConfig = genConfig.getJSONObject("speechConfig")
        assertEquals("id-ID", speechConfig.getString("languageCode"))
        val prebuilt = speechConfig
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
        assertEquals("Aoede", prebuilt.getString("voiceName"))

        // Verifikasi penggantian karakter suara secara dinamis
        client.voiceName = "Puck"
        val updatedPrebuilt = client.buildSetupMessageJson()
            .getJSONObject("setup")
            .getJSONObject("generationConfig")
            .getJSONObject("speechConfig")
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
        assertEquals("Puck", updatedPrebuilt.getString("voiceName"))

        val tools = setup.getJSONArray("tools")
        val declarations = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        val toolNames = mutableListOf<String>()
        for (i in 0 until declarations.length()) {
            val decl = declarations.getJSONObject(i)
            toolNames.add(decl.getString("name"))
            assertEquals("NON_BLOCKING", decl.getString("behavior"))
        }

        // Pastikan tool otomasi UI dan tool OS Android lengkap terdaftar
        assertTrue(toolNames.contains("execute_android_goal"))
        assertTrue(toolNames.contains("read_current_screen"))
        assertTrue(toolNames.contains("cancel_android_goal"))
        assertTrue(toolNames.contains("get_active_task_status"))
        assertTrue(toolNames.contains("pause_or_resume_goal"))
        assertTrue(toolNames.contains("search_web_realtime"))
        assertTrue(toolNames.contains("read_webpage_content"))
        assertTrue(toolNames.contains("get_device_and_time_status"))
        assertTrue(toolNames.contains("search_phone_contacts"))
        assertTrue(toolNames.contains("get_recent_notifications"))
        assertTrue(toolNames.contains("get_current_location"))
        assertTrue(toolNames.contains("set_system_alarm"))
        assertTrue(toolNames.contains("query_huggingface_trending"))
        assertTrue(toolNames.contains("get_huggingface_model_details"))
        assertTrue(toolNames.contains("query_github_trending_or_search"))
        assertTrue(toolNames.contains("get_github_repo_details"))
        assertTrue(toolNames.contains("get_github_readme"))
        assertTrue(toolNames.contains("open_browser_url"))
        assertTrue(toolNames.contains("control_spotify_playback"))
        assertTrue(toolNames.contains("execute_antigravity_rpc"))
        assertTrue(toolNames.contains("control_device_system"))
        assertTrue(toolNames.contains("manage_notifications"))

        // Seluruh nama deklarasi fungsi wajib unik (mencegah penolakan setup HTTP 400 dari Google AI Studio)
        assertEquals("Semua nama deklarasi fungsi harus unik tanpa duplikasi!", toolNames.size, toolNames.distinct().size)

        // Verifikasi System Instruction memuat persona Samantha + FRIDAY dan tech stack AI
        val sysInstruction = setup.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue("Harus memuat persona Samantha", sysInstruction.contains("Samantha"))
        assertTrue("Harus memuat persona FRIDAY", sysInstruction.contains("F.R.I.D.A.Y."))
        assertTrue("Harus memuat keahlian LangGraph", sysInstruction.contains("LangGraph"))
        assertTrue("Harus memuat keahlian FastAPI", sysInstruction.contains("FastAPI"))
        assertTrue("Harus memuat Gentle Health Guardian", sysInstruction.contains("Gentle Health Guardian"))
        assertTrue("Harus memuat larangan berpura-pura ngoding bareng", sysInstruction.contains("DILARANG BERPURA-PURA PERNAH NGODING BARENG"))
        assertTrue("Harus memuat prinsip API-First", sysInstruction.contains("API-FIRST"))

        // Uji penguraian multi-part audio + outputTranscription + toolCall + interrupted
        val sampleAudio = byteArrayOf(1, 2, 3, 4)
        val serverJson = JSONObject()
            .put(
                "serverContent",
                JSONObject()
                    .put(
                        "modelTurn",
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "audio/pcm;rate=24000")
                                            .put("data", Base64.getEncoder().encodeToString(sampleAudio))
                                    )
                                )
                                .put(JSONObject().put("text", "Baik, sedang saya buka WhatsApp."))
                        )
                    )
                    .put(
                        "outputTranscription",
                        JSONObject().put("text", "Baik, sedang saya buka WhatsApp.")
                    )
                    .put("interrupted", true)
                    .put("turnComplete", true)
            )
            .put(
                "toolCall",
                JSONObject().put(
                    "functionCalls",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "call_99")
                            .put("name", "execute_android_goal")
                            .put(
                                "args",
                                JSONObject().put("goal", "Kirim pesan WA ke Alif bahwa saya tiba")
                            )
                    )
                )
            )
            .toString()

        val parsed = GeminiLiveEventParser.parseServerMessage(serverJson)
        assertEquals(1, parsed.audioChunksPcm24k.size)
        assertArrayEquals(sampleAudio, parsed.audioChunksPcm24k.single())
        assertEquals("Baik, sedang saya buka WhatsApp.", parsed.outputTranscript)
        assertTrue(parsed.interrupted)
        assertTrue(parsed.turnComplete)
        assertEquals("call_99", parsed.toolCalls.single().id)
        assertEquals("execute_android_goal", parsed.toolCalls.single().name)
        assertEquals("Kirim pesan WA ke Alif bahwa saya tiba", parsed.toolCalls.single().args["goal"])
    }

    @Test
    fun `VoiceAudioEngine finishAndSpeak mengalirkan status ke Gemini Live tanpa memicu TTS lokal`() = runBlocking {
        val liveClient = FakeConnectedGeminiLiveClient()
        val spokenLocalTts = mutableListOf<String>()

        val engine = VoiceAudioEngine(
            context = null,
            geminiLiveClient = liveClient,
            customTtsSpeaker = { text -> spokenLocalTts.add(text) }
        )

        engine.finishAndSpeak("Pesan berhasil terkirim ke Alif.")

        assertEquals(1, liveClient.sentRealtimeTexts.size)
        assertTrue(
            liveClient.sentRealtimeTexts.first().contains(
                "[STATUS SISTEM ANDROID]: Tugas telah selesai dijalankan. Hasil: Pesan berhasil terkirim ke Alif."
            )
        )
        assertTrue(spokenLocalTts.isEmpty())
    }

    @Test
    fun `AndroidSystemToolsBridge dan AndroidSystemToolSkill mengeksekusi tool sistem dan menyaring notifikasi m-Banking`() {
        GepyAccessibilityService.clearRecentNotifications()
        // Notifikasi m-Banking & OTP wajib ditolak oleh SafetyRules
        assertFalse(
            GepyAccessibilityService.recordNotificationIfSafe(
                packageName = "com.bca",
                rawText = "Mutasi rekening Rp 500.000"
            )
        )
        assertFalse(
            GepyAccessibilityService.recordNotificationIfSafe(
                packageName = "com.whatsapp",
                rawText = "Kode OTP rahasia Anda adalah 123456"
            )
        )
        // Notifikasi aman wajib tercatat
        assertTrue(
            GepyAccessibilityService.recordNotificationIfSafe(
                packageName = "com.whatsapp",
                rawText = "Budi Santoso: Halo, sudah sampai mana?"
            )
        )

        val bridge = AndroidSystemToolsBridge(context = null)
        val skill = AndroidSystemToolSkill(systemBridge = bridge)

        val battAction = skill.proposeFastPathAction(
            userGoal = "Gepy baterai tinggal berapa persen dan sekarang jam berapa?",
            foregroundPackage = "com.whatsapp",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(battAction)
        assertEquals(AgentActionType.DONE, battAction!!.action)
        assertTrue(battAction.completionTts!!.contains("Baterai 85%"))

        val notifAction = skill.proposeFastPathAction(
            userGoal = "Ada notifikasi baru apa aja?",
            foregroundPackage = "com.sec.android.app.launcher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )
        assertNotNull(notifAction)
        assertEquals(AgentActionType.DONE, notifAction!!.action)
        assertTrue(notifAction.completionTts!!.contains("Budi Santoso: Halo, sudah sampai mana?"))
    }

    @Test
    fun `HapticFeedbackManager memicu seluruh pola getaran tanpa melempar exception`() {
        val manager = HapticFeedbackManager(context = null)
        for (pattern in GepyFeedbackPattern.entries) {
            manager.triggerFeedback(pattern)
        }
    }

    @Test
    fun `Seluruh deklarasi fungsi GeminiLiveClient memiliki properties non-kosong dan mendukung sanitasi kunci BYOK`() {
        val client = GeminiLiveClient(apiKey = "  \"GEMINI_API_KEY=AIzaSyDummyKey123456789\" \n")
        assertTrue(client.hasApiKey)
        assertTrue(client.buildWebSocketUrl().endsWith("?key=AIzaSyDummyKey123456789"))

        val setup = client.buildSetupMessageJson().getJSONObject("setup")
        val declarations = setup
            .getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")

        for (i in 0 until declarations.length()) {
            val decl = declarations.getJSONObject(i)
            val params = decl.getJSONObject("parameters")
            val props = params.getJSONObject("properties")
            assertTrue(
                "Tool ${decl.getString("name")} tidak boleh memiliki properties kosong",
                props.length() > 0
            )
        }

        // Mode kompatibilitas menghilangkan behavior NON_BLOCKING dan languageCode bila ditolak model
        val compatSetup = client.buildSetupMessageJson(
            targetModel = "models/gemini-3.8-live",
            includeNonBlocking = false,
            includeLanguageCode = false,
            includeAudioTranscription = false
        ).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", compatSetup.getString("model"))
        val compatSpeech = compatSetup.getJSONObject("generationConfig").getJSONObject("speechConfig")
        assertFalse(compatSpeech.has("languageCode"))
        assertFalse(compatSetup.has("inputAudioTranscription"))

        // Verifikasi deteksi otomatis kunci AIza... meskipun pengguna menempelkannya di kolom Custom OpenAI
        val byok = ByokSettings(customOpenAiApiKey = "AIzaSyAutoDetected9999")
        assertEquals("AIzaSyAutoDetected9999", byok.effectiveGeminiApiKey)
        assertTrue(byok.hasGeminiApiKey)
    }

    @Test
    fun `boostPcm16Audio menguatkan amplitudo secara linier tanpa melampaui batas Short (anti-clipping)`() {
        val original = buildPcm16Frame(sampleCount = 10, amplitude = 20_000)
        // Amplitudo 20_000 dikali 2.0x = 40_000 (melebihi Short.MAX_VALUE 32_767) -> harus terpotong aman di 32_767
        val boosted = GeminiLiveClient.boostPcm16Audio(original, gainFactor = 2.0f)
        val buffer = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 10) {
            assertEquals(Short.MAX_VALUE, buffer.getShort(i * 2))
        }

        // Amplitudo normal 5_000 dikali 2.0x = 10_000
        val normal = buildPcm16Frame(sampleCount = 5, amplitude = 5_000)
        val boostedNormal = GeminiLiveClient.boostPcm16Audio(normal, gainFactor = 2.0f)
        val normalBuf = ByteBuffer.wrap(boostedNormal).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 5) {
            assertEquals(10_000.toShort(), normalBuf.getShort(i * 2))
        }

        // Gain <= 1.0f tidak mengubah byte
        val untouched = GeminiLiveClient.boostPcm16Audio(normal, gainFactor = 1.0f)
        assertArrayEquals(normal, untouched)
    }

    @Test
    fun `LocalPcmVad dengan konfigurasi baru 700 ms tidak memotong bicara pada jeda hening 300 ms`() {
        val vad = LocalPcmVad(
            rmsThreshold = 0.015,
            speechTriggerFrames = 3,
            silenceEndpointFrames = 35 // 35 frame * 20 ms = 700 ms
        )
        val speechFrame = buildPcm16Frame(sampleCount = 320, amplitude = 3000) // RMS ~0.091
        val silentFrame = buildPcm16Frame(sampleCount = 320, amplitude = 50)

        // 3 frame bicara memicu status bicara
        vad.processFrame(speechFrame)
        vad.processFrame(speechFrame)
        val started = vad.processFrame(speechFrame)
        assertTrue(started.speechStarted)
        assertTrue(vad.isInSpeech)

        // Jeda hening 300 ms (15 frame) - TIDAK boleh memicu speechEnded
        for (i in 1..15) {
            val state = vad.processFrame(silentFrame)
            assertFalse("Frame ke-$i jeda 300 ms tidak boleh memicu speechEnded", state.speechEnded)
            assertTrue(vad.isInSpeech)
        }

        // Lanjutan hening hingga mencapai 35 frame (total 700 ms) baru memicu speechEnded
        var endedDetected = false
        for (i in 16..35) {
            val state = vad.processFrame(silentFrame)
            if (state.speechEnded) {
                endedDetected = true
                break
            }
        }
        assertTrue("Setelah hening 700 ms penuh (35 frame), speechEnded harus terpicu", endedDetected)
        assertFalse(vad.isInSpeech)
    }

    @Test
    fun `LocalPcmVad mendeteksi ucapan pada ambang sensitivitas baru RMS 0,015`() {
        val vad = LocalPcmVad()
        // Amplitudo 600 menghasilkan RMS = 600 / 32768 = ~0.0183 (di atas ambang baru 0.015, tapi di bawah ambang lama 0.028)
        val softSpeechFrame = buildPcm16Frame(sampleCount = 320, amplitude = 600)
        vad.processFrame(softSpeechFrame)
        vad.processFrame(softSpeechFrame)
        val triggerState = vad.processFrame(softSpeechFrame)
        assertTrue("Suara santai dengan RMS ~0.018 harus terdeteksi oleh ambang baru 0.015", triggerState.speechStarted)
        assertTrue(vad.isInSpeech)
    }

    @Test
    fun `LocalPcmVad menaikkan ambang hanya saat playbackActive bernilai true dan kembali sensitif saat playbackActive false`() {
        val vad = LocalPcmVad()
        val softSpeechFrame = buildPcm16Frame(sampleCount = 320, amplitude = 600)

        // Saat playbackActive = true (speaker asisten sedang bersuara), suara pelan dicegah agar tidak memicu echo-loop
        vad.reset()
        for (i in 1..4) {
            val state = vad.processFrame(softSpeechFrame, playbackActive = true)
            assertFalse("Saat speaker aktif, suara pelan tidak boleh memicu speechStarted", state.speechStarted)
        }
        assertFalse(vad.isInSpeech)

        // Begitu asisten hening (playbackActive = false), suara pelan langsung terdeteksi dalam 3 frame (60 ms)!
        vad.reset()
        vad.processFrame(softSpeechFrame, playbackActive = false)
        vad.processFrame(softSpeechFrame, playbackActive = false)
        val readyState = vad.processFrame(softSpeechFrame, playbackActive = false)
        assertTrue("Saat speaker hening, suara pelan harus segera terdeteksi tanpa terblokir echo-gate", readyState.speechStarted)
        assertTrue(vad.isInSpeech)
    }

    @Test
    fun `PREFERRED_LIVE_MODELS hanya memuat model GA resmi docs MODELS_md dan buildCandidateModelOrder menjaga failover dinamis`() {
        // 1. Daftar statis wajib bersih dari model lama yang sudah shutdown / tidak ada di docs/MODELS.md
        val retiredModels = listOf(
            "models/gemini-2.0-flash-exp",
            "models/gemini-2.0-flash-live-001",
            "models/gemini-live-2.5-flash-preview",
            "models/gemini-2.5-flash-native-audio-latest",
            "models/gemini-2.5-flash-native-audio-preview-09-2025",
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            "models/gemini-3.1-flash-live-preview"
        )
        assertEquals(
            listOf("models/gemini-3.8-live", "models/gemini-3.8-live-extended-thinking"),
            GeminiLiveClient.PREFERRED_LIVE_MODELS
        )
        for (retired in retiredModels) {
            assertFalse(
                "PREFERRED_LIVE_MODELS tidak boleh lagi menyertakan model usang $retired",
                GeminiLiveClient.PREFERRED_LIVE_MODELS.contains(retired)
            )
        }

        val client = GeminiLiveClient(apiKey = "test_key", model = "gemini-3.8-live")
        // Tanpa hasil discovery, fallback murni ke model GA resmi
        assertEquals(
            listOf("models/gemini-3.8-live", "models/gemini-3.8-live-extended-thinking"),
            client.buildCandidateModelOrder(emptyList())
        )

        // Jika endpoint /v1beta/models mengembalikan model aktif baru/cadangan secara dinamis, pengurutan tetap terbounded
        val discoveredFromGoogle = listOf(
            "models/gemini-3.8-live-extended-thinking",
            "models/gemini-3.8-live-regional-alt",
            "models/gemini-3.8-live"
        )
        val candidates = client.buildCandidateModelOrder(discoveredFromGoogle)
        // Model utama yang dikonfigurasi (gemini-3.8-live) tetap diposisikan pertama
        assertEquals("models/gemini-3.8-live", candidates.first())
        assertTrue(candidates.contains("models/gemini-3.8-live-extended-thinking"))
        assertTrue(
            "Model live hasil discovery dinamis /v1beta/models tetap dipertahankan sebagai cadangan",
            candidates.contains("models/gemini-3.8-live-regional-alt")
        )
        // Maksimal 4 kandidat unik
        assertTrue(candidates.size <= 4)
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun `sendInitialGreetingPrompt membangun clientContent pembuka dengan turnComplete true`() {
        val client = GeminiLiveClient(apiKey = "test_key")
        val json = client.buildInitialGreetingJson("Halo Maulana!")
        val content = json.getJSONObject("clientContent")
        assertTrue("turnComplete harus true agar server Gemini Live segera menyapa pengguna secara lisan", content.getBoolean("turnComplete"))
        val turns = content.getJSONArray("turns")
        assertEquals(1, turns.length())
        val firstTurn = turns.getJSONObject(0)
        assertEquals("user", firstTurn.getString("role"))
        assertEquals("Halo Maulana!", firstTurn.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `LocalPcmVad menolak gema speaker bawah saat playbackActive dan hanya memicu jika suara pengguna lantang`() {
        val vad = LocalPcmVad()
        // Gema speaker bawah HP Galaxy A06 (amplitudo 2500, RMS ~0.076)
        val speakerEchoFrame = buildPcm16Frame(sampleCount = 320, amplitude = 2500)

        // 1. Saat playbackActive = true, 10 frame gema TIDAK boleh memicu speechStarted
        vad.reset()
        for (i in 1..10) {
            val state = vad.processFrame(speakerEchoFrame, playbackActive = true)
            assertFalse("Gema speaker RMS 0.076 tidak boleh memicu speechStarted saat playback aktif", state.speechStarted)
        }
        assertFalse(vad.isInSpeech)

        // 2. Pengguna sengaja menyela dengan suara lantang langsung ke mikrofon (amplitudo 7000, RMS ~0.21)
        val intentionalBargeInFrame = buildPcm16Frame(sampleCount = 320, amplitude = 7000)
        var triggered = false
        for (i in 1..8) {
            val state = vad.processFrame(intentionalBargeInFrame, playbackActive = true)
            if (state.speechStarted) {
                triggered = true
                break
            }
        }
        assertTrue("Suara interupsi lantang pengguna (RMS > 0.18) harus berhasil memicu speechStarted saat playback aktif", triggered)
        assertTrue(vad.isInSpeech)
    }

    @Test
    fun `GeminiLiveClient memprioritaskan Key 1 dan failover ke Key 2 saat Key 1 cooldown`() {
        com.teknologigawai.gepy.core.config.GeminiKeyPool.resetCooldownsForTest()
        val client = GeminiLiveClient(
            apiKey = "live_primary_k1",
            secondaryApiKey = "live_backup_k2"
        )

        assertEquals(listOf("live_primary_k1", "live_backup_k2"), client.orderedLiveApiKeys())
        assertTrue(client.hasApiKey)

        // Simulasikan Key 1 kena kuota limit 429 -> Key 2 langsung menjadi kandidat pertama
        com.teknologigawai.gepy.core.config.GeminiKeyPool.markQuotaExhausted("live_primary_k1")
        assertEquals(listOf("live_backup_k2"), client.orderedLiveApiKeys())

        client.updateApiKeys(primaryKey = "new_primary_k1", secondaryKey = "live_backup_k2")
        assertEquals(listOf("new_primary_k1", "live_backup_k2"), client.orderedLiveApiKeys())
        com.teknologigawai.gepy.core.config.GeminiKeyPool.resetCooldownsForTest()
    }

    @Test
    fun `GeminiLiveClient selalu mengalirkan audio chunk PCM 24k langsung ke directAudioChunkCallback untuk latensi instan native`() {
        val client = GeminiLiveClient(apiKey = "test_gemini_key")

        val setup = client.buildSetupMessageJson().getJSONObject("setup")
        val genConfig = setup.getJSONObject("generationConfig")
        val modalities = genConfig.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))
        assertTrue("speechConfig harus selalu disertakan agar tidak ditolak error 1007 oleh Gemini 3.8 Live", genConfig.has("speechConfig"))
        assertTrue("outputAudioTranscription harus disertakan untuk subtitle HUD", setup.has("outputAudioTranscription"))

        // Pastikan audio chunk selalu diteruskan ke speaker
        val receivedAudio = mutableListOf<ByteArray>()
        client.directAudioChunkCallback = { receivedAudio.add(it) }
        val sampleAudio = byteArrayOf(9, 8, 7, 6)
        val serverJson = JSONObject()
            .put(
                "serverContent",
                JSONObject()
                    .put(
                        "modelTurn",
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "audio/pcm;rate=24000")
                                            .put("data", Base64.getEncoder().encodeToString(sampleAudio))
                                    )
                                )
                                .put(JSONObject().put("text", "Halo, ini native voice streaming."))
                        )
                    )
            )

        client.handleIncomingRawMessage(rawJson = serverJson.toString())

        assertEquals(1, receivedAudio.size)
        assertArrayEquals(sampleAudio, receivedAudio[0])
    }

    @Test
    fun `GeminiLiveEventParser readTranscriptText mempertahankan spasi antar token kata`() {
        val json = JSONObject()
            .put(
                "serverContent",
                JSONObject().put(
                    "modelTurn",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", " apa "))
                    )
                )
            )
        val event = GeminiLiveEventParser.parseServerMessage(json.toString())
        assertEquals(" apa ", event.modelTextParts.firstOrNull())
    }

    @Test
    fun `LocalPcmVad mentolerir jeda hening hingga 49 frame dan baru mengakhiri ujaran di frame ke-50`() {
        val vad = LocalPcmVad(rmsThreshold = 0.012, speechTriggerFrames = 3, silenceEndpointFrames = 50)
        val loudFrame = ByteArray(640)
        val buffer = java.nio.ByteBuffer.wrap(loudFrame).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 320) {
            buffer.putShort(i * 2, 5000.toShort())
        }
        val silentFrame = ByteArray(640)

        // Bicara 3 frame agar speechStarted aktif
        assertFalse(vad.processFrame(loudFrame).speechStarted)
        assertFalse(vad.processFrame(loudFrame).speechStarted)
        val speechState = vad.processFrame(loudFrame)
        assertTrue(speechState.speechStarted)
        assertTrue(vad.isInSpeech)

        // Hening selama 49 frame (980 ms) -> belum boleh speechEnded!
        for (f in 1..49) {
            val state = vad.processFrame(silentFrame)
            assertFalse("Frame hening ke-$f tidak boleh memicu speechEnded prematur", state.speechEnded)
            assertTrue(vad.isInSpeech)
        }

        // Frame hening ke-50 (1000 ms = 1.0 detik) -> harus memicu speechEnded!
        val endState = vad.processFrame(silentFrame)
        assertTrue("Frame hening ke-50 wajib memicu speechEnded", endState.speechEnded)
        assertFalse(vad.isInSpeech)
    }

    @Test
    fun `boostPcm16AudioInPlace memodifikasi array asal tanpa alokasi baru dengan hasil amplifikasi identik`() {
        val original = buildPcm16Frame(sampleCount = 8, amplitude = 20_000)
        val expected = GeminiLiveClient.boostPcm16Audio(original, gainFactor = 2.0f)

        val returned = GeminiLiveClient.boostPcm16AudioInPlace(original, gainFactor = 2.0f)
        assertSame("Harus mengembalikan referensi array yang sama (zero-allocation)", original, returned)
        assertArrayEquals(expected, returned)

        // Gain <= 1.0f membiarkan buffer tetap utuh tanpa perubahan
        val normal = buildPcm16Frame(sampleCount = 4, amplitude = 4_000)
        val snapshot = normal.copyOf()
        val unscaled = GeminiLiveClient.boostPcm16AudioInPlace(normal, gainFactor = 1.0f)
        assertSame(normal, unscaled)
        assertArrayEquals(snapshot, unscaled)
    }
}
