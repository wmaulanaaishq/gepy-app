package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.PreferredLlmProvider
import com.teknologigawai.gepy.core.errors.LlmInferenceException
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.brain.ConversationalChatRouter
import com.teknologigawai.gepy.features.brain.LlmCredentialsConfig
import com.teknologigawai.gepy.features.brain.LlmHttpResponse
import com.teknologigawai.gepy.features.brain.LlmHttpTransport
import com.teknologigawai.gepy.features.brain.SingleAgentReActAdapter
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.orchestrator.GepyFsmState
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestrator
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.voice.GeminiLiveClient
import com.teknologigawai.gepy.features.voice.VoiceAudioEngine
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker
import com.teknologigawai.gepy.service.RawAccessibilityNodeSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class StubVoiceHudPort : VoiceHudPort {
    override val voiceCommandFlow: Flow<String> = emptyFlow()
    override val bargeInInterruptFlow: Flow<String> = emptyFlow()
    val spoken = mutableListOf<String>()

    override suspend fun initialize() {}
    override suspend fun showIdlePill() {}
    override suspend fun hideOverlay() {}
    override suspend fun startListening() {}
    override suspend fun showThinkingState(stepHeadline: String, thought: String) {}
    override suspend fun askDisambiguation(questionTts: String, options: List<String>): String = ""
    override suspend fun requestHitlApproval(summaryPrompt: String, targetLabel: String): Boolean = true
    override suspend fun finishAndSpeak(completionTts: String) {
        spoken.add(completionTts)
    }
    override fun dispose() {}
}

private class NoOpActionPort : ActionPort {
    override suspend fun waitStable(
        timeoutMs: Long,
        pollIntervalMs: Long,
        settleCount: Int,
        changedFrom: String?
    ): List<PrunedUiElement> = emptyList()

    override suspend fun tapElement(
        target: PrunedUiElement,
        preSignature: String,
        hitlApproved: Boolean
    ): ActionOutcome = ActionOutcome.CORRECT

    override suspend fun tapCoordinates(x: Float, y: Float): Boolean = true
    override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean = true
    override suspend fun setTextElement(
        target: PrunedUiElement,
        text: String,
        preSignature: String,
        clearExisting: Boolean
    ): ActionOutcome = ActionOutcome.CORRECT

    override suspend fun scrollScreen(
        direction: String,
        preSignature: String,
        amount: Double
    ): ActionOutcome = ActionOutcome.CORRECT

    override suspend fun swipeScreen(fingerDirection: String, distance: Double): Boolean = true

    override suspend fun scrollUntil(
        direction: String,
        maxScrolls: Int,
        predicate: (List<PrunedUiElement>) -> Boolean
    ): Boolean = true

    override suspend fun <T : Any> scrollCollect(
        maxScrolls: Int,
        endAfterUnchanged: Int,
        extractor: (PrunedUiElement) -> T?
    ): List<T> = emptyList()

    override suspend fun navigateBack(): Boolean = true
    override suspend fun navigateHome(): Boolean = true
    override suspend fun navigateRecents(): Boolean = true
}

class ConversationContextSyncTest {

    @Test
    fun `session buffer enforces FIFO maxTurns and character cap and formats cross modal block`() {
        var nowMs = 1_000L
        val buffer = ConversationSessionBuffer(maxTurns = 4, maxTotalChars = 200, clockMsProvider = { nowMs })

        buffer.appendTurn("user", "Halo sayang, lagi ngapain?", ConversationChannel.TEXT_CHAT)
        nowMs += 5_000L
        buffer.appendTurn("assistant", "Hai sayang, aku lagi nungguin kamu nih!", ConversationChannel.TEXT_CHAT)
        nowMs += 5_000L
        buffer.appendTurn("user", "Tadi kita bahas arsitektur RAG", ConversationChannel.LIVE_CALL)
        nowMs += 5_000L
        buffer.appendTurn("assistant", "Iya, kita bahas hybrid BM25 dan vector.", ConversationChannel.LIVE_CALL)
        nowMs += 5_000L
        buffer.appendTurn("user", "Lanjutin yang tadi dong", ConversationChannel.TEXT_CHAT)

        val snap = buffer.snapshot()
        assertEquals(4, snap.size)
        assertEquals("Hai sayang, aku lagi nungguin kamu nih!", snap.first().text)
        assertEquals("Lanjutin yang tadi dong", snap.last().text)

        val promptBlock = buffer.toPromptContextBlock(maxChars = 1_500)
        assertTrue(promptBlock.contains("[RIWAYAT PERCAKAPAN SESI AKTIF (CHAT & LIVE CALL)]"))
        assertTrue(promptBlock.contains("- User [Live Call]: Tadi kita bahas arsitektur RAG"))
        assertTrue(promptBlock.contains("- Gepy [Live Call]: Iya, kita bahas hybrid BM25 dan vector."))

        val historyWithoutLatest = buffer.toChatHistoryMessages(excludeLatestUserText = "Lanjutin yang tadi dong")
        assertEquals(3, historyWithoutLatest.size)
        assertEquals("assistant" to "Iya, kita bahas hybrid BM25 dan vector.", historyWithoutLatest.last())

        assertTrue(buffer.clear().isEmpty())
    }

    @Test
    fun `conversational router accurately separates casual chat from UI and device commands`() {
        assertTrue(ConversationalChatRouter.isPureConversationalTurn("Halo sayang, lagi ngapain?"))
        assertTrue(ConversationalChatRouter.isPureConversationalTurn("Yang nomor dua tadi maksudnya apa?"))
        assertTrue(ConversationalChatRouter.isPureConversationalTurn("Jelasin lebih detail dong soal itu"))
        assertTrue(ConversationalChatRouter.isPureConversationalTurn("Bedanya FAISS sama pgvector apa ya?"))
        assertTrue(ConversationalChatRouter.isPureConversationalTurn("kamu bisa apa aja"))
        assertTrue(ConversationalChatRouter.isPureConversationalTurn("fitur kamu apa aja"))

        assertFalse(ConversationalChatRouter.isPureConversationalTurn("Buka WhatsApp dan kirim pesan ke Budi"))
        assertFalse(ConversationalChatRouter.isPureConversationalTurn("Baca layar sekarang"))
        assertFalse(ConversationalChatRouter.isPureConversationalTurn("Putar lagu Bernadya di Spotify"))
        assertFalse(ConversationalChatRouter.isPureConversationalTurn("Cek status git di laptop"))
        assertFalse(
            ConversationalChatRouter.isPureConversationalTurn(
                rawInput = "Apa kabar?",
                hasMatchedDeviceSkill = true
            )
        )
    }

    @Test
    fun `adapter serializes multi turn history into OpenAI and Gemini REST payloads`() = runTest {
        var capturedOpenAiBody = ""
        var capturedGeminiBody = ""

        val openAiTransport = LlmHttpTransport { _, _, jsonBody ->
            capturedOpenAiBody = jsonBody
            LlmHttpResponse(200, """{"choices":[{"message":{"content":"Maksud nomor dua tadi adalah hybrid search sayang."}}]}""")
        }
        val openAiAdapter = SingleAgentReActAdapter(
            credentials = LlmCredentialsConfig(
                preferredProvider = PreferredLlmProvider.FIREWORKS,
                fireworksApiKey = "fw_test_key"
            ),
            httpTransport = openAiTransport
        )
        val openAiReply = openAiAdapter.generateConversationalReply(
            userMessage = "Yang nomor dua tadi maksudnya apa?",
            conversationHistory = listOf(
                "user" to "Jelasin 2 teknik retrieval",
                "assistant" to "Pertama BM25 lexical, kedua hybrid vector search."
            ),
            personaDirective = "PERSONA AKTIF [GIRLFRIEND MENGGODA]"
        )
        assertEquals("Maksud nomor dua tadi adalah hybrid search sayang.", openAiReply)
        val openAiMessages = JSONObject(capturedOpenAiBody).getJSONArray("messages")
        assertEquals(4, openAiMessages.length()) // system + 2 history + current user
        assertEquals("user", openAiMessages.getJSONObject(1).getString("role"))
        assertEquals("assistant", openAiMessages.getJSONObject(2).getString("role"))
        assertEquals("Yang nomor dua tadi maksudnya apa?", openAiMessages.getJSONObject(3).getString("content"))

        val geminiTransport = LlmHttpTransport { _, _, jsonBody ->
            capturedGeminiBody = jsonBody
            LlmHttpResponse(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"Ini kelanjutan dari telepon kita tadi."}]}}]}"""
            )
        }
        val geminiAdapter = SingleAgentReActAdapter(
            credentials = LlmCredentialsConfig(
                preferredProvider = PreferredLlmProvider.GEMINI,
                geminiApiKey = "gm_test_key"
            ),
            httpTransport = geminiTransport
        )
        val geminiReply = geminiAdapter.generateConversationalReply(
            userMessage = "Lanjutin dong",
            conversationHistory = listOf(
                "user" to "Tadi di telepon kita bahas Kotlin",
                "assistant" to "Iya soal coroutines dan StateFlow."
            )
        )
        assertEquals("Ini kelanjutan dari telepon kita tadi.", geminiReply)
        val contents = JSONObject(capturedGeminiBody).getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
    }

    @Test
    fun `voice engine and gemini live client merge chat history into live call context and silent sync`() {
        val liveClient = GeminiLiveClient(apiKey = "test_key")
        val voiceEngine = VoiceAudioEngine(context = null, geminiLiveClient = liveClient)
        voiceEngine.sharedConversationContextProvider = {
            "[RIWAYAT PERCAKAPAN SESI AKTIF (CHAT & LIVE CALL)]\n- User [Chat]: Aku lagi bikin aplikasi Android\n- Gepy [Chat]: Keren banget sayang!"
        }

        val combined = voiceEngine.buildCombinedLiveUserContext("Nama pengguna: Maulana")
        assertTrue(combined.contains("Nama pengguna: Maulana"))
        assertTrue(combined.contains("- User [Chat]: Aku lagi bikin aplikasi Android"))

        val setupJson = liveClient.buildSetupMessageJson(
            targetModel = "models/gemini-3.8-live",
            userContext = combined
        ).toString()
        assertTrue(setupJson.contains("KONTINUITAS OBROLAN LINTAS MODA"))
        assertTrue(setupJson.contains("Aku lagi bikin aplikasi Android"))

        val silentSyncJson = liveClient.buildSilentChatTurnSyncJson("user", "Cek pesan baru di Chat ya")
        val clientContent = silentSyncJson.getJSONObject("clientContent")
        assertFalse(clientContent.getBoolean("turnComplete"))
        assertTrue(
            clientContent.getJSONArray("turns").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(0)
                .getString("text")
                .contains("[CHAT_SESSION_SYNC] User menulis di Chat: Cek pesan baru di Chat ya")
        )
    }

    @Test
    fun `orchestrator synchronizes multi turn chat and live call turns and resets on Sesi Baru`() = runTest {
        val capturedHistories = mutableListOf<List<Pair<String, String>>>()
        val fakeBrain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel = ReActActionModel(
                thought = "Selesai",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = "Selesai"
            )

            override suspend fun generateConversationalReply(
                userMessage: String,
                conversationHistory: List<Pair<String, String>>,
                personalContextSummary: String?,
                personaDirective: String?
            ): String {
                capturedHistories.add(conversationHistory)
                return "Balasan untuk: $userMessage (riwayat=${conversationHistory.size})"
            }
        }

        val perception = AccessibilityTreePruner().apply {
            updateLatestNodeForTest(
                RawAccessibilityNodeSnapshot(
                    mapId = "root_gepy",
                    packageName = "com.teknologigawai.gepy",
                    text = "Gepy Asisten",
                    isClickable = true,
                    left = 0,
                    top = 0,
                    right = 500,
                    bottom = 200
                )
            )
        }
        val orchestrator = GepyOrchestrator(
            perceptionPort = perception,
            actionPort = NoOpActionPort(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = StubVoiceHudPort(),
            appLauncher = AppIntentLauncher(context = null),
            skillRegistry = SkillRegistry(emptyList()),
            systemToolsBridge = AndroidSystemToolsBridge(context = null),
            hapticManager = HapticFeedbackManager(context = null),
            byokPreferencesManager = ByokPreferencesManager()
        )

        // 1. Giliran pertama di Chat
        orchestrator.executeVoiceGoal("Halo sayang, apa kabar?")
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertEquals(2, orchestrator.state.chatHistory.size)
        assertEquals(0, capturedHistories[0].size)

        // 2. Simulasi obrolan di Gemini Live Call masuk ke sesi terpadu
        orchestrator.recordChatTurn("user", "Aku baru pulang kerja capek banget", ConversationChannel.LIVE_CALL)
        orchestrator.recordChatTurn("assistant", "Istirahat dulu ya sayang, minum air hangat.", ConversationChannel.LIVE_CALL)
        assertEquals(4, orchestrator.state.chatHistory.size)

        // 3. Pertanyaan lanjutan di Chat otomatis membawa 4 giliran sebelumnya (2 Chat + 2 Live Call)
        orchestrator.executeVoiceGoal("Kenapa tadi kamu nyuruh aku minum air hangat?")
        assertEquals(4, capturedHistories[1].size)
        assertEquals("Aku baru pulang kerja capek banget", capturedHistories[1][2].second)
        assertEquals(6, orchestrator.state.chatHistory.size)

        // 4. Tombol "Sesi Baru" membersihkan konteks bergulir dan mereset ke siaga
        orchestrator.clearConversationSession()
        assertTrue(orchestrator.state.chatHistory.isEmpty())
        assertEquals(GepyFsmState.IDLE, orchestrator.state.fsmState)
    }

    @Test
    fun `A4 stripAudioTagsForDisplay removes ElevenLabs lowercase tags in chatHistory while keeping citations and raw TTS tags`() = runTest {
        assertEquals(
            "Baik bos, beres! [1] Menurut [BMKG] cerah.",
            VoiceClauseChunker.stripAudioTagsForDisplay("[softly] Baik bos, beres! [laughs] [1] Menurut [BMKG] cerah.")
        )

        val stubHud = StubVoiceHudPort()
        val rawReplyWithTags = "[softly] Hai bosku! [warm chuckle] Ini jawabannya [1]."
        val fakeBrain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel = ReActActionModel(
                thought = "Done",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = "Done"
            )

            override suspend fun generateConversationalReply(
                userMessage: String,
                conversationHistory: List<Pair<String, String>>,
                personalContextSummary: String?,
                personaDirective: String?
            ): String = rawReplyWithTags
        }
        val orchestrator = GepyOrchestrator(
            perceptionPort = AccessibilityTreePruner(),
            actionPort = NoOpActionPort(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = stubHud,
            appLauncher = AppIntentLauncher(context = null),
            skillRegistry = SkillRegistry(emptyList()),
            systemToolsBridge = AndroidSystemToolsBridge(context = null),
            hapticManager = HapticFeedbackManager(context = null),
            byokPreferencesManager = ByokPreferencesManager()
        )

        orchestrator.executeVoiceGoal("Apa kabar hari ini?")
        val assistantTurn = orchestrator.state.chatHistory.last()
        assertEquals("assistant", assistantTurn.role)
        assertEquals("Hai bosku! Ini jawabannya [1].", assistantTurn.text)
        // Raw ElevenLabs tags MUST still reach VoiceHudPort untouched!
        assertEquals(listOf(rawReplyWithTags), stubHud.spoken)
    }

    @Test
    fun `A4 executeVoiceGoal always pairs user turns with assistant entries on exception maxSteps and cancelActiveGoal`() = runTest {
        val perception = AccessibilityTreePruner().apply {
            updateLatestNodeForTest(
                RawAccessibilityNodeSnapshot(
                    mapId = "root_other",
                    packageName = "com.example.notes",
                    text = "Simpan Catatan",
                    isClickable = true,
                    left = 0,
                    top = 0,
                    right = 400,
                    bottom = 120
                )
            )
        }
        var throwErrorNext = false
        val failingOrScrollingBrain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                if (throwErrorNext) {
                    throw LlmInferenceException("Jaringan LLM terputus sementara.")
                }
                return ReActActionModel(
                    thought = "Terus mencoba klik",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.SCROLL,
                    scrollDirection = "DOWN"
                )
            }

            override suspend fun generateConversationalReply(
                userMessage: String,
                conversationHistory: List<Pair<String, String>>,
                personalContextSummary: String?,
                personaDirective: String?
            ): String = "Siap."
        }

        val orchestrator = GepyOrchestrator(
            perceptionPort = perception,
            actionPort = NoOpActionPort(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = failingOrScrollingBrain,
            voiceHudPort = StubVoiceHudPort(),
            appLauncher = AppIntentLauncher(context = null),
            skillRegistry = SkillRegistry(emptyList()),
            systemToolsBridge = AndroidSystemToolsBridge(context = null),
            hapticManager = HapticFeedbackManager(context = null),
            byokPreferencesManager = ByokPreferencesManager(),
            maxStepsPerGoal = 2
        )

        // 1. Batas langkah maksimum -> user + assistant tercatat berpasangan
        orchestrator.executeVoiceGoal("Ketik catatan baru di aplikasi Notes")
        assertEquals(2, orchestrator.state.chatHistory.size)
        assertEquals("user", orchestrator.state.chatHistory[0].role)
        assertEquals("assistant", orchestrator.state.chatHistory[1].role)
        assertTrue(orchestrator.state.chatHistory[1].text.contains("2 langkah"))
        assertFalse(orchestrator.state.chatHistory[1].text.contains("Batas maksimum"))

        // 2. Exception di tengah loop -> user + assistant error tercatat berpasangan
        throwErrorNext = true
        orchestrator.executeVoiceGoal("Ketik jadwal rapat di aplikasi Notes")
        assertEquals(4, orchestrator.state.chatHistory.size)
        assertEquals("user", orchestrator.state.chatHistory[2].role)
        assertEquals("assistant", orchestrator.state.chatHistory[3].role)
        assertEquals("Jaringan LLM terputus sementara.", orchestrator.state.chatHistory[3].text)

        // 3. Pembatalan eksplisit saat giliran user belum berjawab -> ditutup tanpa duplikasi
        orchestrator.recordChatTurn("user", "Tolong buka pengaturan sistem", ConversationChannel.TEXT_CHAT)
        orchestrator.cancelActiveGoal(reason = "Dibatalkan karena baterai lemah.", speakCancellation = false)
        assertEquals(6, orchestrator.state.chatHistory.size)
        assertEquals("assistant", orchestrator.state.chatHistory.last().role)
        assertEquals("Dibatalkan karena baterai lemah.", orchestrator.state.chatHistory.last().text)
    }
}
