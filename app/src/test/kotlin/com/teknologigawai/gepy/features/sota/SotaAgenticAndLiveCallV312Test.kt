package com.teknologigawai.gepy.features.sota

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.action.AccessibilityActionAdapter
import com.teknologigawai.gepy.features.action.NativeAccessibilityDriver
import com.teknologigawai.gepy.features.brain.InterruptionShieldAndCritic
import com.teknologigawai.gepy.features.brain.TaskPlannerAndVerifier
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.orchestrator.GepyFsmState
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestrator
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.voice.GeminiLiveClient
import com.teknologigawai.gepy.features.voice.GeminiLiveParsedEvent
import com.teknologigawai.gepy.features.voice.GeminiLiveToolCall
import com.teknologigawai.gepy.features.voice.LocalPcmVad
import com.teknologigawai.gepy.features.voice.SpeculativePreFetcher
import com.teknologigawai.gepy.service.RawAccessibilityNodeSnapshot
import com.teknologigawai.gepy.service.WindowTreeDump
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SotaAgenticAndLiveCallV312Test {

    @Test
    fun `CoAT expected_outcome and AutoDroid SET_TEXT_AND_SUBMIT parse and verify accurately`() {
        val rawJson = """
            {
              "thought": "Mengetik kueri video gadget di kolom pencarian YouTube dan langsung menjalankan pencarian",
              "reflection_on_last_step": "INITIAL",
              "expected_outcome": "Daftar hasil pencarian video gadget tampil di layar",
              "action": "TYPE_AND_SUBMIT",
              "target_index": 2,
              "text_value": "review samsung galaxy a06"
            }
        """.trimIndent()

        val parsed = ReActActionModel.fromRawJsonString(rawJson)
        assertEquals(AgentActionType.SET_TEXT_AND_SUBMIT, parsed.action)
        assertEquals("Daftar hasil pencarian video gadget tampil di layar", parsed.expectedOutcome)
        assertEquals(2, parsed.targetIndex)
        assertEquals("review samsung galaxy a06", parsed.textValue)

        val serialized = parsed.toJsonObject()
        assertEquals("SET_TEXT_AND_SUBMIT", serialized.getString("action"))
        assertEquals(
            "Daftar hasil pencarian video gadget tampil di layar",
            serialized.getString("expected_outcome")
        )

        // CoAT Post-Action Verifier: mendeteksi kemunculan dialog koneksi terputus tak terduga
        val preEls = listOf(
            PrunedUiElement(index = 1, nodeId = "search_box", text = "Telusuri YouTube", isEditable = true)
        )
        val blockerPostEls = listOf(
            PrunedUiElement(index = 1, nodeId = "err_dialog", text = "Tidak ada koneksi internet. Coba lagi nanti.")
        )
        val diagnostic = TaskPlannerAndVerifier.verifyStepExpectedOutcome(
            expectedOutcome = parsed.expectedOutcome,
            preElements = preEls,
            postElements = blockerPostEls,
            rawOutcome = ActionOutcome.CORRECT
        )
        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.contains("[CoAT]"))
    }

    @Test
    fun `AccessibilityActionAdapter setTextAndSubmitElement triggers forceSubmit and clicks safe search button`() = runTest {
        val pruner = AccessibilityTreePruner(
            treeDumpProvider = {
                WindowTreeDump(
                    foregroundPackage = "com.shopee.id",
                    screenWidth = 1080,
                    screenHeight = 2400,
                    rawNodeCount = 2,
                    rawCharLength = 120,
                    nodes = listOf(
                        RawAccessibilityNodeSnapshot(
                            mapId = "search_input",
                            viewResourceId = "com.shopee.id:id/search_src_text",
                            packageName = "com.shopee.id",
                            text = "Cari di Shopee",
                            isEditable = true,
                            left = 80,
                            top = 100,
                            right = 860,
                            bottom = 200
                        ),
                        RawAccessibilityNodeSnapshot(
                            mapId = "btn_go",
                            viewResourceId = "com.shopee.id:id/btn_search",
                            packageName = "com.shopee.id",
                            text = "Cari",
                            isClickable = true,
                            left = 870,
                            top = 100,
                            right = 1040,
                            bottom = 200
                        )
                    )
                )
            }
        )

        var forceSubmitCaptured = false
        var clickedSearchBtnId: String? = null
        val fakeDriver = object : NativeAccessibilityDriver {
            override val screenWidthPx: Float = 1080f
            override val screenHeightPx: Float = 2400f

            override suspend fun clickNode(
                mapId: String?,
                viewResourceId: String?,
                centerX: Int,
                centerY: Int
            ): Boolean {
                clickedSearchBtnId = viewResourceId
                return true
            }

            override suspend fun typeText(
                mapId: String?,
                viewResourceId: String?,
                centerX: Int,
                centerY: Int,
                text: String,
                clearExisting: Boolean
            ): Boolean = true

            override suspend fun typeText(
                mapId: String?,
                viewResourceId: String?,
                centerX: Int,
                centerY: Int,
                text: String,
                clearExisting: Boolean,
                forceSubmit: Boolean
            ): Boolean {
                forceSubmitCaptured = forceSubmit
                return true
            }

            override suspend fun dispatchGesture(
                startX: Float,
                startY: Float,
                endX: Float,
                endY: Float,
                durationMs: Long
            ): Boolean = true

            override suspend fun performGlobalAction(action: String): Boolean = true
        }

        val adapter = AccessibilityActionAdapter(perceptionPort = pruner, driver = fakeDriver)
        val screen = pruner.captureAndPruneScreen()
        val preSig = pruner.computeSignature(screen)
        val editTarget = screen.first { it.isEditable }

        val outcome = adapter.setTextAndSubmitElement(
            target = editTarget,
            text = "charger tipe c",
            preSignature = preSig
        )

        assertEquals(ActionOutcome.CORRECT, outcome)
        assertTrue(forceSubmitCaptured)
        assertEquals("com.shopee.id:id/btn_search", clickedSearchBtnId)
    }

    @Test
    fun `InterruptionShieldAndCritic dismisses promo popups with 0 tokens and refines non-interactive targets`() {
        val popupElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "promo_title",
                packageName = "com.tokopedia.tkpd",
                text = "Promo Spesial Pengguna Baru! Aktifkan Notifikasi Sekarang",
                isClickable = false
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "btn_enable",
                packageName = "com.tokopedia.tkpd",
                text = "Aktifkan",
                isClickable = true
            ),
            PrunedUiElement(
                index = 3,
                nodeId = "btn_later",
                packageName = "com.tokopedia.tkpd",
                text = "Nanti Saja",
                isClickable = true
            )
        )

        val shieldAction = InterruptionShieldAndCritic.detectBlockingPopupDismissal(
            currentPackage = "com.tokopedia.tkpd",
            elements = popupElements,
            userGoal = "Cari sepatu lari ukuran 42 di Tokopedia"
        )
        assertNotNull(shieldAction)
        assertEquals(AgentActionType.CLICK, shieldAction!!.action)
        assertEquals(3, shieldAction.targetIndex)

        // Single-State Candidate Critic: koreksi klik pada teks non-clickable ke tombol baris di sebelahnya
        val listElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "passive_label",
                packageName = "com.instagram.android",
                text = "Kirim pesan",
                isClickable = false,
                rect = PrunedUiElement.buildRect(120, 600, 300, 60)
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "clickable_btn",
                packageName = "com.instagram.android",
                text = "Kirim pesan",
                isClickable = true,
                rect = PrunedUiElement.buildRect(100, 590, 340, 80)
            ),
            PrunedUiElement(
                index = 3,
                nodeId = "real_search_edit",
                packageName = "com.google.android.youtube",
                text = "[Hint] Telusuri YouTube",
                isClickable = true,
                isEditable = true,
                rect = PrunedUiElement.buildRect(100, 100, 800, 90)
            )
        )

        val rawClick = ReActActionModel(
            thought = "Ketuk tombol Kirim pesan",
            reflectionOnLastStep = ReflectionStatus.INITIAL,
            action = AgentActionType.CLICK,
            targetIndex = 1
        )
        val refinedClick = InterruptionShieldAndCritic.refineCandidateAction(rawClick, listElements)
        assertEquals(2, refinedClick.targetIndex)

        val rawTypeWrongIdx = ReActActionModel(
            thought = "Cari video di kolom pencarian",
            reflectionOnLastStep = ReflectionStatus.INITIAL,
            action = AgentActionType.SET_TEXT_AND_SUBMIT,
            targetIndex = 1,
            textValue = "tutorial kotlin"
        )
        val refinedType = InterruptionShieldAndCritic.refineCandidateAction(rawTypeWrongIdx, listElements)
        assertEquals(3, refinedType.targetIndex)
    }

    @Test
    fun `SpeculativePreFetcher pre-captures screen on interim speech and serves 0ms hit to read_current_screen`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "chat_1",
                packageName = "com.whatsapp",
                text = "Rapat pukul 14.00 WIB",
                isClickable = true,
                left = 40,
                top = 300,
                right = 900,
                bottom = 420
            )
        )

        var fakeClockMs = 1_000L
        val preFetcher = SpeculativePreFetcher(
            perceptionPortProvider = { pruner },
            debounceMs = 300L,
            defaultMaxAgeMs = 2_500L,
            nowMsProvider = { fakeClockMs }
        )

        assertTrue(SpeculativePreFetcher.hasScreenActionIntent("tolong baca isi layar sekarang"))
        assertFalse(SpeculativePreFetcher.hasScreenActionIntent("halo apa kabar"))

        val preFetched = preFetcher.performPreFetchSync()
        assertNotNull(preFetched)
        assertEquals(1, preFetcher.speculativePreFetchCount)

        fakeClockMs += 400L // masih dalam jendela 2.500 ms
        val consumed = preFetcher.consumeFreshSnapshotIfAvailable(currentForegroundPackage = "com.whatsapp")
        assertNotNull(consumed)
        assertEquals(1, preFetcher.speculativeHitCount)
        assertEquals("Rapat pukul 14.00 WIB", consumed!!.snapshot.elements.first().text)

        // Setelah dikonsumsi sekali, cache kosong agar tidak basi
        assertNull(preFetcher.consumeFreshSnapshotIfAvailable(currentForegroundPackage = "com.whatsapp"))
    }

    @Test
    fun `Local-or-Escalate Duplex Router separates instant app launches from multi-step escalations`() = runTest {
        val noopHud = object : VoiceHudPort {
            override val voiceCommandFlow: Flow<String> = emptyFlow()
            override val bargeInInterruptFlow: Flow<String> = emptyFlow()
            override suspend fun initialize() {}
            override suspend fun showIdlePill() {}
            override suspend fun hideOverlay() {}
            override suspend fun startListening() {}
            override suspend fun showThinkingState(stepHeadline: String, thought: String) {}
            override suspend fun askDisambiguation(questionTts: String, options: List<String>): String = ""
            override suspend fun requestHitlApproval(summaryPrompt: String, targetLabel: String): Boolean = true
            override suspend fun finishAndSpeak(completionTts: String) {}
            override fun dispose() {}
        }
        val noopAction = object : ActionPort {
            override suspend fun waitStable(
                timeoutMs: Long,
                pollIntervalMs: Long,
                settleCount: Int,
                changedFrom: String?
            ): List<PrunedUiElement> = emptyList()
            override suspend fun tapElement(target: PrunedUiElement, preSignature: String, hitlApproved: Boolean): ActionOutcome = ActionOutcome.CORRECT
            override suspend fun tapCoordinates(x: Float, y: Float): Boolean = true
            override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean = true
            override suspend fun setTextElement(target: PrunedUiElement, text: String, preSignature: String, clearExisting: Boolean): ActionOutcome = ActionOutcome.CORRECT
            override suspend fun scrollScreen(direction: String, preSignature: String, amount: Double): ActionOutcome = ActionOutcome.CORRECT
            override suspend fun swipeScreen(fingerDirection: String, distance: Double): Boolean = true
            override suspend fun scrollUntil(direction: String, maxScrolls: Int, predicate: (List<PrunedUiElement>) -> Boolean): Boolean = true
            override suspend fun <T : Any> scrollCollect(maxScrolls: Int, endAfterUnchanged: Int, extractor: (PrunedUiElement) -> T?): List<T> = emptyList()
            override suspend fun navigateBack(): Boolean = true
            override suspend fun navigateHome(): Boolean = true
            override suspend fun navigateRecents(): Boolean = true
        }
        val orchestrator = GepyOrchestrator(
            perceptionPort = AccessibilityTreePruner(),
            actionPort = noopAction,
            memoryPort = SqliteMemoryRepository(),
            brainPort = object : BrainPort {
                override suspend fun decideNextAction(
                    userGoal: String,
                    currentPackage: String,
                    screenElements: List<PrunedUiElement>,
                    memory: ShortTermMemorySnapshot,
                    injectedDomainKnowledge: String?
                ): ReActActionModel = ReActActionModel(
                    thought = "Done",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE
                )
            },
            voiceHudPort = noopHud,
            appLauncher = AppIntentLauncher()
        )

        // 1. Perintah murni "Buka YouTube" pada execute_android_goal mengambil jalur LOCAL_FAST_PATH
        val fastRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_fast",
                name = "execute_android_goal",
                args = mapOf("goal" to "Buka YouTube")
            )
        )
        assertEquals("SUCCESS_IMMEDIATE", fastRes["status"])
        assertEquals("LOCAL_FAST_PATH", fastRes["routing_mode"])

        // 2. Perintah kompleks multi-langkah mengambil jalur EXPERT_AGENTIC_ESCALATION disertai daftar sub-tujuan
        val escalatedRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_esc",
                name = "execute_android_goal",
                args = mapOf("goal" to "Cari akun teknologi gawai di Instagram lalu kirim DM halo")
            )
        )
        assertEquals("ESCALATED_ASYNC_RUNNING", escalatedRes["status"])
        assertEquals("EXPERT_AGENTIC_ESCALATION", escalatedRes["routing_mode"])
        assertTrue((escalatedRes["subgoal_count"] as Int) >= 3)
        orchestrator.cancelActiveGoal("Selesai uji unit")
    }

    @Test
    fun `ReFrame Silence Gate reclaims silent WebSocket batches with 40ms pre-roll and IAP pins memory every 8 turns`() {
        val sentAudioPayloads = mutableListOf<ByteArray>()
        val sentAnchors = mutableListOf<String>()

        val testClient = object : GeminiLiveClient(
            apiKey = "AIzaTestKey123",
            localVad = LocalPcmVad(
                rmsThreshold = 0.02,
                speechTriggerFrames = 2,
                silenceEndpointFrames = 10,
                deepSilenceThresholdFrames = 20 // dipercepat untuk unit test (400 ms)
            )
        ) {
            override val isConnected: Boolean
                get() = true

            override fun sendAudioChunkPcm16k(pcmBytes: ByteArray) {
                sentAudioPayloads.add(pcmBytes)
            }

            override fun sendSilentContextAnchorPin(compactAnchor: String) {
                sentAnchors.add(compactAnchor)
            }
        }
        testClient.micPreAmpGain = 1.0f
        testClient.anchorPinIntervalTurns = 8
        testClient.updateUserMemoryContext(
            "=== PROFIL PENGGUNA ===\nNama panggilan: Mas Wahyu\nPreferensi: Jawaban ringkas & teknis"
        )

        // 1. Kirim 70 frame PCM hening (0 amplitudo):
        // 20 frame pertama (4 batch) masih sebelum deepSilence -> terkirim normal (4 batch).
        // 50 frame berikutnya (10 batch) masuk deepSilence -> hanya 2 batch keep-alive terkirim, 8 batch dihemat!
        val silentFrame = ByteArray(640) // 20 ms @ 16kHz
        repeat(70) {
            testClient.processAndSendMicPcm16k(silentFrame)
        }
        assertTrue("ReFrame harus menghemat batch hening", testClient.reframeSavedBatchesCount >= 6)

        val countBeforeSpeech = sentAudioPayloads.size

        // 2. Pengguna mulai berbicara (frame suara aktif): pre-roll 2 frame (40 ms) harus ikut disertakan!
        val loudFrame = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(320) { putShort(8_000) }
        }.array()
        repeat(3) {
            testClient.processAndSendMicPcm16k(loudFrame)
        }
        // Karena 2 frame pre-roll + 3 frame bicara = 5 frame (100 ms), batch bicara pertama langsung terkirim tanpa terpotong!
        assertEquals(countBeforeSpeech + 1, sentAudioPayloads.size)
        assertEquals(640 * 5, sentAudioPayloads.last().size)

        // 3. Uji Initialization-Anchor Pinning (IAP) & Two-Resolution Context (TRC) pada giliran ke-8
        repeat(8) {
            testClient.processIncomingTurnLifecycle(GeminiLiveParsedEvent(turnComplete = true))
        }
        assertEquals(8, testClient.completedTurnCount)
        assertEquals(1, testClient.anchorPinRefreshCount)
        assertEquals(1, sentAnchors.size)
        assertTrue(sentAnchors.first().contains("Mas Wahyu"))

        val pinJson = testClient.buildSilentContextAnchorPinJson(sentAnchors.first())
        val clientContent = pinJson.getJSONObject("clientContent")
        assertFalse(clientContent.getBoolean("turnComplete"))
    }

    @Test
    fun `Voice hangup cancels active automation goal cleanly and sets FSM to IDLE`() = runBlocking {
        val voiceEngine = com.teknologigawai.gepy.features.voice.VoiceAudioEngine(context = null)
        val orchestrator = GepyOrchestrator(
            perceptionPort = AccessibilityTreePruner(),
            actionPort = object : ActionPort {
                override suspend fun waitStable(timeoutMs: Long, pollIntervalMs: Long, settleCount: Int, changedFrom: String?): List<PrunedUiElement> = emptyList()
                override suspend fun tapElement(target: PrunedUiElement, preSignature: String, hitlApproved: Boolean): ActionOutcome = ActionOutcome.CORRECT
                override suspend fun tapCoordinates(x: Float, y: Float): Boolean = true
                override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean = true
                override suspend fun setTextElement(target: PrunedUiElement, text: String, preSignature: String, clearExisting: Boolean): ActionOutcome = ActionOutcome.CORRECT
                override suspend fun scrollScreen(direction: String, preSignature: String, amount: Double): ActionOutcome = ActionOutcome.CORRECT
                override suspend fun swipeScreen(fingerDirection: String, distance: Double): Boolean = true
                override suspend fun scrollUntil(direction: String, maxScrolls: Int, predicate: (List<PrunedUiElement>) -> Boolean): Boolean = true
                override suspend fun <T : Any> scrollCollect(maxScrolls: Int, endAfterUnchanged: Int, extractor: (PrunedUiElement) -> T?): List<T> = emptyList()
                override suspend fun navigateBack(): Boolean = true
                override suspend fun navigateHome(): Boolean = true
                override suspend fun navigateRecents(): Boolean = true
            },
            memoryPort = SqliteMemoryRepository(),
            brainPort = object : BrainPort {
                override suspend fun decideNextAction(
                    userGoal: String,
                    currentPackage: String,
                    screenElements: List<PrunedUiElement>,
                    memory: ShortTermMemorySnapshot,
                    injectedDomainKnowledge: String?
                ): ReActActionModel {
                    delay(40L)
                    return ReActActionModel(
                        thought = "Sedang bekerja",
                        reflectionOnLastStep = ReflectionStatus.INITIAL,
                        action = AgentActionType.CLICK,
                        targetIndex = 1
                    )
                }
            },
            voiceHudPort = voiceEngine,
            appLauncher = AppIntentLauncher()
        )

        // Simulasikan Live Call aktif dan tunggu hingga tujuan otomatisasi benar-benar mulai berjalan
        orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_run",
                name = "execute_android_goal",
                args = mapOf("goal" to "Cari kemeja putih di Shopee lalu beli")
            )
        )
        withTimeout(5_000L) {
            while (!orchestrator.state.isExecutingGoal) {
                delay(15L)
            }
        }

        // Panggil penghentian sesi live call (seperti saat pengguna berkata "tutup telepon" atau Gemini panggil end_live_call)
        voiceEngine.stopLiveCallSession("Panggilan ditutup oleh suara pengguna.")
        withTimeout(5_000L) {
            while (orchestrator.state.isBusy ||
                orchestrator.state.fsmState != GepyFsmState.IDLE ||
                orchestrator.state.lastActionLabel != "ABORTED"
            ) {
                delay(15L)
            }
        }
        // Pastikan tidak ada race terlambat yang membalikkan FSM dari IDLE kembali ke OBSERVING
        delay(80L)

        // Verifikasi tugas aktif langsung dibatalkan dan status FSM tetap IDLE
        assertFalse("Tugas otomatisasi tidak boleh tetap sibuk setelah live call ditutup", orchestrator.state.isBusy)
        assertEquals(GepyFsmState.IDLE, orchestrator.state.fsmState)
        assertEquals("ABORTED", orchestrator.state.lastActionLabel)
    }

    @Test
    fun `Immediate voice hangup right after async launch never regresses IDLE back to OBSERVING`() = runBlocking {
        val voiceEngine = com.teknologigawai.gepy.features.voice.VoiceAudioEngine(context = null)
        val orchestrator = GepyOrchestrator(
            perceptionPort = AccessibilityTreePruner(),
            actionPort = object : ActionPort {
                override suspend fun waitStable(timeoutMs: Long, pollIntervalMs: Long, settleCount: Int, changedFrom: String?): List<PrunedUiElement> = emptyList()
                override suspend fun tapElement(target: PrunedUiElement, preSignature: String, hitlApproved: Boolean): ActionOutcome = ActionOutcome.CORRECT
                override suspend fun tapCoordinates(x: Float, y: Float): Boolean = true
                override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean = true
                override suspend fun setTextElement(target: PrunedUiElement, text: String, preSignature: String, clearExisting: Boolean): ActionOutcome = ActionOutcome.CORRECT
                override suspend fun scrollScreen(direction: String, preSignature: String, amount: Double): ActionOutcome = ActionOutcome.CORRECT
                override suspend fun swipeScreen(fingerDirection: String, distance: Double): Boolean = true
                override suspend fun scrollUntil(direction: String, maxScrolls: Int, predicate: (List<PrunedUiElement>) -> Boolean): Boolean = true
                override suspend fun <T : Any> scrollCollect(maxScrolls: Int, endAfterUnchanged: Int, extractor: (PrunedUiElement) -> T?): List<T> = emptyList()
                override suspend fun navigateBack(): Boolean = true
                override suspend fun navigateHome(): Boolean = true
                override suspend fun navigateRecents(): Boolean = true
            },
            memoryPort = SqliteMemoryRepository(),
            brainPort = object : BrainPort {
                override suspend fun decideNextAction(
                    userGoal: String,
                    currentPackage: String,
                    screenElements: List<PrunedUiElement>,
                    memory: ShortTermMemorySnapshot,
                    injectedDomainKnowledge: String?
                ): ReActActionModel = ReActActionModel(
                    thought = "Sedang bekerja",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.CLICK,
                    targetIndex = 1
                )
            },
            voiceHudPort = voiceEngine,
            appLauncher = AppIntentLauncher()
        )

        // Tutup panggilan 0 ms tepat setelah tool asinkron diluncurkan (race terberat)
        orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_race",
                name = "execute_android_goal",
                args = mapOf("goal" to "Cari kemeja putih di Shopee lalu beli")
            )
        )
        voiceEngine.stopLiveCallSession("Panggilan ditutup seketika.")
        delay(100L)

        assertFalse(orchestrator.state.isBusy)
        assertEquals(GepyFsmState.IDLE, orchestrator.state.fsmState)
        assertEquals("ABORTED", orchestrator.state.lastActionLabel)
    }

    @Test
    fun `WebSearchAndReaderBridge formatFriendlyIndonesianDate formats relative and absolute dates accurately`() {
        val rfcDate = "Sat, 07 Nov 2026 04:00:00 GMT"
        val friendly = com.teknologigawai.gepy.core.web.WebSearchAndReaderBridge.formatFriendlyIndonesianDate(rfcDate)
        assertTrue("Waktu rilis harus berisi jam WIB atau tanggal Indonesia", friendly.contains("WIB") || friendly.contains("November"))

        val xmlFeed = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>Pemerintah Resmi Mengumumkan Kebijakan Baru - ANTARA News</title>
                  <link>https://www.antaranews.com/berita/123</link>
                  <description>Pemerintah merilis panduan ekonomi digital.</description>
                  <pubDate>Sat, 07 Nov 2026 04:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val bridge = com.teknologigawai.gepy.core.web.WebSearchAndReaderBridge(gatewayBaseUrl = "")
        val items = bridge.parseRssItems(xmlFeed, defaultSource = "Antara")
        assertEquals(1, items.size)
        assertTrue(items[0].publishedDate.contains("WIB") || items[0].publishedDate.contains("November"))
        assertTrue(items[0].snippet.contains("Rilis:"))
    }
}
