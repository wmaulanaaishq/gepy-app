package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.GoalVerificationVerdict
import com.teknologigawai.gepy.core.ports.OsQueryStatus
import com.teknologigawai.gepy.core.ports.OsStateCheckResult
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.OsVerificationBaseline
import com.teknologigawai.gepy.core.ports.OsVerifyDomain
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.ports.PredictedEdgeOutcome
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.models.PruneMetricsSnapshot
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.perception.models.TextBox
import com.teknologigawai.gepy.features.voice.GeminiLiveToolCall
import com.teknologigawai.gepy.service.RawAccessibilityNodeSnapshot
import com.teknologigawai.gepy.service.ActiveNotificationSnapshot
import com.teknologigawai.gepy.service.NotificationOpStatus
import com.teknologigawai.gepy.service.NotificationReplyAttemptResult
import com.teknologigawai.gepy.core.system.ActiveMediaSessionInfo
import com.teknologigawai.gepy.core.skills.AndroidSystemToolSkill
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian keamanan berlapis & notifikasi GepyOrchestrator: SEWM guard, OS-State Verifier, RemoteInput & blokir finansial.
 */
class GepyOrchestratorSafetyAndNotificationTest {
    @Test
    fun `SEWM pre-click guard mencegat klik PAYWALL berisiko tinggi tanpa menyentuh ActionPort dan mengarahkan Brain memilih tombol aman`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "root_paywall_test",
                packageName = "com.music.demo",
                text = "",
                isClickable = false,
                left = 0,
                top = 0,
                right = 720,
                bottom = 1280,
                subNodes = listOf(
                    RawAccessibilityNodeSnapshot(
                        mapId = "btn_pro",
                        viewResourceId = "com.music.demo:id/btn_upgrade_pro",
                        packageName = "com.music.demo",
                        text = "Coba Pro",
                        isClickable = true,
                        left = 40,
                        top = 300,
                        right = 680,
                        bottom = 400
                    ),
                    RawAccessibilityNodeSnapshot(
                        mapId = "btn_skip",
                        viewResourceId = "com.music.demo:id/btn_skip",
                        packageName = "com.music.demo",
                        text = "Lewati",
                        isClickable = true,
                        left = 40,
                        top = 450,
                        right = 680,
                        bottom = 550
                    )
                )
            )
        )

        val initialSnap = pruner.capturePruneSnapshot()
        val structuralHash = AccessibilityTreePruner.computeStructuralLayoutHash(
            foregroundPackage = "com.music.demo",
            elements = initialSnap.elements
        )
        val memoryRepo = SqliteMemoryRepository()
        repeat(3) {
            memoryRepo.observeEdgeTransition(
                packageName = "com.music.demo",
                screenSignatureHash = structuralHash,
                edgeKey = "com.music.demo:id/btn_upgrade_pro",
                actionType = "CLICK",
                targetLabelSample = "Coba Pro",
                outcome = PredictedEdgeOutcome.PAYWALL
            )
        }

        val fakeAction = FakeActionAdapter()
        var brainTurn = 0
        var sawSewmAdviceOrVetoFeedback = false
        val adaptiveBrain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                brainTurn++
                if (injectedDomainKnowledge?.contains("SEWM PRE-ACTION RISK MODEL") == true &&
                    memory.verifierFeedback?.contains("PAYWALL") == true
                ) {
                    sawSewmAdviceOrVetoFeedback = true
                }
                val proIdx = screenElements.first { it.viewResourceId == "com.music.demo:id/btn_upgrade_pro" }.index
                val skipIdx = screenElements.first { it.viewResourceId == "com.music.demo:id/btn_skip" }.index
                return when (brainTurn) {
                    1 -> ReActActionModel(
                        thought = "Mencoba ketuk tombol Pro",
                        reflectionOnLastStep = ReflectionStatus.INITIAL,
                        action = AgentActionType.CLICK,
                        targetIndex = proIdx
                    )
                    2 -> {
                        assertTrue(memory.verifierFeedback.orEmpty().contains("PAYWALL"))
                        ReActActionModel(
                            thought = "Berpindah ke tombol Lewati setelah veto SEWM",
                            reflectionOnLastStep = ReflectionStatus.INEFFECTIVE,
                            action = AgentActionType.CLICK,
                            targetIndex = skipIdx
                        )
                    }
                    else -> ReActActionModel(
                        thought = "Selesai membuka jalur gratis",
                        reflectionOnLastStep = ReflectionStatus.CORRECT,
                        action = AgentActionType.DONE,
                        completionTts = "Oke, iklan dan tawaran sudah dilewati."
                    )
                }
            }
        }

        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = fakeAction,
            memoryPort = memoryRepo,
            brainPort = adaptiveBrain,
            voiceHudPort = FakeVoiceHud(),
            appLauncher = AppIntentLauncher()
        )

        orchestrator.executeVoiceGoal("Ketuk tombol lewati di aplikasi demo")

        assertTrue(sawSewmAdviceOrVetoFeedback)
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        // Tombol berisiko "Coba Pro" TIDAK boleh pernah disentuh oleh ActionPort!
        assertFalse(fakeAction.tappedElements.any { it.text == "Coba Pro" })
        assertTrue(fakeAction.tappedElements.any { it.text == "Lewati" })
    }

    @Test
    fun `Programmatic OS-State Verifier menolak fake DONE langkah 1 lalu meloloskan DONE dengan 0 LLM Critic saat OS state terbukti`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "btn_save",
                viewResourceId = "com.google.android.calendar:id/save",
                packageName = "com.google.android.calendar",
                text = "Simpan",
                isClickable = true,
                left = 40,
                top = 120,
                right = 680,
                bottom = 220
            )
        )

        var osConfirmed = false
        var llmCriticCalls = 0
        val fakeOsVerifier = object : OsStateVerifierPort {
            override fun captureBaseline(): OsVerificationBaseline = OsVerificationBaseline(maxCalendarEventId = 10L)
            override fun verifyGoalOsState(
                userGoal: String,
                baseline: OsVerificationBaseline?
            ): OsStateCheckResult {
                return if (osConfirmed) {
                    OsStateCheckResult(
                        domain = OsVerifyDomain.CALENDAR_EVENT,
                        status = OsQueryStatus.MATCH_CONFIRMED,
                        evidenceSummary = "Terbukti di CalendarContract.Events: acara rapat tersimpan (id=11)."
                    )
                } else {
                    OsStateCheckResult(
                        domain = OsVerifyDomain.CALENDAR_EVENT,
                        status = OsQueryStatus.NOT_FOUND,
                        missingReason = "Acara kalender belum tercatat di CalendarContract.Events."
                    )
                }
            }
        }

        val fakeAction = object : ActionPort by FakeActionAdapter() {
            override suspend fun tapElement(
                target: PrunedUiElement,
                preSignature: String,
                hitlApproved: Boolean
            ): ActionOutcome {
                osConfirmed = true
                return ActionOutcome.CORRECT
            }
        }

        var stepTurn = 0
        val brain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                stepTurn++
                return when (stepTurn) {
                    1 -> ReActActionModel(
                        thought = "Mengira selesai lebih awal",
                        reflectionOnLastStep = ReflectionStatus.INITIAL,
                        action = AgentActionType.DONE,
                        completionTts = "Selesai."
                    )
                    2 -> {
                        assertTrue(memory.verifierFeedback.orEmpty().contains("CalendarContract.Events"))
                        ReActActionModel(
                            thought = "Mengetuk Simpan sesuai umpan balik verifier",
                            reflectionOnLastStep = ReflectionStatus.INEFFECTIVE,
                            action = AgentActionType.CLICK,
                            targetIndex = 1
                        )
                    }
                    else -> ReActActionModel(
                        thought = "Sekarang acara sudah tersimpan",
                        reflectionOnLastStep = ReflectionStatus.CORRECT,
                        action = AgentActionType.DONE,
                        completionTts = "Beres, acara rapat sudah tersimpan di kalendermu."
                    )
                }
            }

            override suspend fun verifyGoalCompletion(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot
            ): GoalVerificationVerdict {
                llmCriticCalls++
                return GoalVerificationVerdict(achieved = true)
            }
        }

        val hud = FakeVoiceHud()
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = fakeAction,
            memoryPort = SqliteMemoryRepository(),
            brainPort = brain,
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher(),
            osStateVerifier = fakeOsVerifier
        )

        orchestrator.executeVoiceGoal("Ketuk simpan dan jadwalkan rapat produk di Kalender")

        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertTrue(osConfirmed)
        assertEquals(0, llmCriticCalls)
        assertEquals(3, stepTurn)
        assertTrue(hud.spokenCompletions.last().contains("tersimpan"))
    }

    @Test
    fun `API-First RemoteInput reply executes directly in Sovereign mode with 0 LLM calls`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "home_root",
                packageName = "com.sec.android.app.launcher",
                text = "Beranda",
                isClickable = false,
                left = 0,
                top = 0,
                right = 720,
                bottom = 1280
            )
        )

        var llmCalls = 0
        val fakeBrain = FakeBrainPort {
            llmCalls++
            ReActActionModel(thought = "fallback", reflectionOnLastStep = ReflectionStatus.INITIAL, action = AgentActionType.DONE)
        }

        var repliedKey = ""
        var repliedMsg = ""
        val sampleSnap = ActiveNotificationSnapshot(
            key = "k_wa_budi_01",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Budi Santoso",
            text = "Halo Maulana, sudah sampai mana?",
            timestampMs = System.currentTimeMillis(),
            hasRemoteInput = true
        )

        val bridge = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { true }
            activeNotificationsProvider = { _ -> listOf(sampleSnap) }
            notificationReplyByKeyExecutor = { key, _, msg ->
                repliedKey = key.orEmpty()
                repliedMsg = msg
                NotificationReplyAttemptResult(
                    status = NotificationOpStatus.SUCCESS,
                    targetPackage = "com.whatsapp",
                    targetTitle = "Budi Santoso",
                    message = msg
                )
            }
        }

        val hud = FakeVoiceHud()
        val byok = ByokPreferencesManager(context = null)
        byok.updateSettings(ByokSettings(sovereignPersonalMode = true))

        val skill = AndroidSystemToolSkill(
            systemBridge = bridge,
            sovereignDirectExecutionProvider = { byok.currentSettings.sovereignPersonalMode }
        )
        val registry = SkillRegistry(skills = listOf(skill))

        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher(),
            skillRegistry = registry,
            systemToolsBridge = bridge,
            byokPreferencesManager = byok
        )

        orchestrator.executeVoiceGoal("Balas pesan WA dari Budi bilang oke meluncur sekarang")

        assertEquals(0, llmCalls)
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertEquals(0, hud.hitlRequestedCount)
        assertEquals("k_wa_budi_01", repliedKey)
        assertEquals("oke meluncur sekarang", repliedMsg)
        assertTrue(hud.spokenCompletions.last().contains("Budi Santoso"))
    }

    @Test
    fun `RemoteInput reply falls back to UI and BrainPort when listener disabled or not found`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "home_root",
                packageName = "com.sec.android.app.launcher",
                text = "Beranda",
                isClickable = false,
                left = 0,
                top = 0,
                right = 720,
                bottom = 1280
            )
        )

        var llmCalls = 0
        val fakeBrain = FakeBrainPort {
            llmCalls++
            ReActActionModel(
                thought = "UI-Fallback: Membuka aplikasi WhatsApp untuk membalas",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = "Membuka WhatsApp."
            )
        }

        // Listener disabled
        val bridge = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { false }
        }
        val hud = FakeVoiceHud()
        val skill = AndroidSystemToolSkill(systemBridge = bridge)
        val registry = SkillRegistry(skills = listOf(skill))

        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher(),
            skillRegistry = registry,
            systemToolsBridge = bridge
        )

        orchestrator.executeVoiceGoal("Balas pesan WA dari Budi bilang oke sebentar")

        // Harus masuk ke UI-Fallback (BrainPort dipanggil)
        assertTrue("Ketika listener disabled, wajib masuk ke UI-Fallback", llmCalls > 0)
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
    }

    @Test
    fun `RemoteInput reply in non-sovereign mode requests HITL approval and honors user rejection`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "home_root",
                packageName = "com.sec.android.app.launcher",
                text = "Beranda",
                isClickable = false,
                left = 0,
                top = 0,
                right = 720,
                bottom = 1280
            )
        )

        var llmCalls = 0
        val fakeBrain = FakeBrainPort {
            llmCalls++
            ReActActionModel(thought = "fallback", reflectionOnLastStep = ReflectionStatus.INITIAL, action = AgentActionType.DONE)
        }

        val sampleSnap = ActiveNotificationSnapshot(
            key = "k_wa_citra",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Citra",
            text = "Kapan rapatnya?",
            timestampMs = System.currentTimeMillis(),
            hasRemoteInput = true
        )

        var replySent = false
        val bridge = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { true }
            activeNotificationsProvider = { _ -> listOf(sampleSnap) }
            notificationReplyByKeyExecutor = { _, _, _ ->
                replySent = true
                NotificationReplyAttemptResult(NotificationOpStatus.SUCCESS)
            }
        }

        val byok = ByokPreferencesManager(context = null)
        byok.updateSettings(ByokSettings(sovereignPersonalMode = false))

        val skill = AndroidSystemToolSkill(
            systemBridge = bridge,
            sovereignDirectExecutionProvider = { byok.currentSettings.sovereignPersonalMode }
        )
        val registry = SkillRegistry(skills = listOf(skill))

        // 1. Pengguna MENOLAK konfirmasi HITL
        val rejectingHud = FakeVoiceHud(hitlApprovalResponse = false)
        val orchestratorReject = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = rejectingHud,
            appLauncher = AppIntentLauncher(),
            skillRegistry = registry,
            systemToolsBridge = bridge,
            byokPreferencesManager = byok
        )

        orchestratorReject.executeVoiceGoal("Balas pesan WA dari Citra bilang jam 2 siang")
        assertEquals(1, rejectingHud.hitlRequestedCount)
        assertFalse("Jika HITL ditolak, balasan RemoteInput tidak boleh terkirim", replySent)
        assertEquals("ABORTED", orchestratorReject.state.lastActionLabel)
        assertTrue(rejectingHud.spokenCompletions.last().contains("batal"))

        // 2. Pengguna MENYETUJUI konfirmasi HITL
        val approvingHud = FakeVoiceHud(hitlApprovalResponse = true)
        val orchestratorApprove = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = approvingHud,
            appLauncher = AppIntentLauncher(),
            skillRegistry = registry,
            systemToolsBridge = bridge,
            byokPreferencesManager = byok
        )

        orchestratorApprove.executeVoiceGoal("Balas pesan WA dari Citra bilang jam 2 siang")
        assertEquals(1, approvingHud.hitlRequestedCount)
        assertTrue("Jika HITL disetujui, balasan RemoteInput harus terkirim", replySent)
        assertEquals("REMOTE_INPUT_REPLY_HITL", orchestratorApprove.state.lastActionLabel)
        assertEquals(GepyFsmState.COMPLETED, orchestratorApprove.state.fsmState)
    }

    @Test
    fun `handleLiveToolCall handles control_device_system and manage_notifications accurately`() = runTest {
        val pruner = AccessibilityTreePruner()
        val sampleSnap = ActiveNotificationSnapshot(
            key = "k_sample",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Budi Santoso",
            text = "Halo",
            timestampMs = System.currentTimeMillis(),
            hasRemoteInput = true
        )

        val bridge = AndroidSystemToolsBridge(context = null).apply {
            isNotificationListenerEnabledProvider = { true }
            activeNotificationsProvider = { _ -> listOf(sampleSnap) }
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

        val hud = FakeVoiceHud(hitlApprovalResponse = true)
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = FakeBrainPort { ReActActionModel(thought = "", reflectionOnLastStep = ReflectionStatus.INITIAL, action = AgentActionType.DONE) },
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher(),
            systemToolsBridge = bridge
        )

        // 1. control_device_system: flashlight_on
        val flashRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_fl",
                name = "control_device_system",
                args = mapOf("action" to "flashlight_on")
            )
        )
        assertEquals("SUCCESS", flashRes["status"])

        // 2. control_device_system: set_timer
        val timerRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_tm",
                name = "control_device_system",
                args = mapOf("action" to "set_timer", "duration_seconds" to 180, "label" to "Teh")
            )
        )
        assertEquals("SUCCESS", timerRes["status"])

        // 3. control_device_system: now_playing
        val nowRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_np",
                name = "control_device_system",
                args = mapOf("action" to "now_playing")
            )
        )
        assertEquals("SUCCESS", nowRes["status"])
        assertEquals("Satu Bulan", nowRes["title"])

        // 4. manage_notifications: dismiss all
        val dismissRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_dis",
                name = "manage_notifications",
                args = mapOf("action" to "dismiss", "sender_or_app" to "all")
            )
        )
        assertEquals("SUCCESS", dismissRes["status"])

        // 5. manage_notifications: reply in non-sovereign with rejection -> BLOCKED_BY_HITL
        hud.hitlApprovalResponse = false
        orchestrator.updateByokSettings(orchestrator.state.byokSettings.copy(sovereignPersonalMode = false))
        val replyRejectRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_rep_hitl",
                name = "manage_notifications",
                args = mapOf("action" to "reply", "sender_or_app" to "Budi", "reply_message" to "Oke")
            )
        )
        assertEquals("BLOCKED_BY_HITL", replyRejectRes["status"])
    }

    @Test
    fun `catastrophicActionsAlwaysRequireHitlEvenInSovereignMode`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "btn_transfer",
                packageName = "com.example.bank",
                text = "Transfer Sekarang",
                isClickable = true,
                left = 100,
                top = 100,
                right = 300,
                bottom = 200,
                viewResourceId = "com.example.bank:id/btn_transfer"
            )
        )

        var stepTurn = 0
        val fakeBrain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                stepTurn++
                return if (stepTurn == 1) {
                    ReActActionModel(
                        thought = "Mencoba transfer dana",
                        reflectionOnLastStep = ReflectionStatus.INITIAL,
                        action = AgentActionType.CLICK,
                        targetIndex = 1
                    )
                } else {
                    ReActActionModel(
                        thought = "Selesai",
                        reflectionOnLastStep = ReflectionStatus.CORRECT,
                        action = AgentActionType.DONE,
                        completionTts = "Transfer dikonfirmasi."
                    )
                }
            }

            override suspend fun verifyGoalCompletion(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot
            ): GoalVerificationVerdict {
                return GoalVerificationVerdict(achieved = true)
            }
        }

        val hud = FakeVoiceHud(hitlApprovalResponse = true)
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher()
        )

        orchestrator.setFullAccessZeroHitl(true)
        assertTrue(orchestrator.state.fullAccessZeroHitl)

        orchestrator.executeVoiceGoal("Lakukan transfer uang")

        // Memastikan requestHitlApproval TETAP dipanggil untuk aksi katastrofik finansial walau fullAccessZeroHitl = true
        assertTrue(
            "requestHitlApproval harus dipanggil untuk aksi katastrofik walau fullAccessZeroHitl = true",
            hud.hitlRequestedCount >= 1
        )
    }

    @Test
    fun `LONG_PRESS action on forbidden financial target node throws ForbiddenPackageException even in mixed window`() = runTest {
        val forbiddenNode = PrunedUiElement(
            index = 1,
            nodeId = "bca_node",
            viewResourceId = "com.bca:id/secret_item",
            packageName = "com.bca",
            text = "Rekening",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            rect = PrunedUiElement.buildRect(10, 10, 100, 60)
        )
        val mixedWindowPerception = object : PerceptionPort {
            override val currentForegroundPackage: String = "com.example.launcher"
            override suspend fun captureAndPruneScreen(forceOcrFallback: Boolean, includeNonInteractive: Boolean) = listOf(forbiddenNode)
            override suspend fun capturePruneSnapshot(forceOcrFallback: Boolean) =
                PruneMetricsSnapshot(elements = listOf(forbiddenNode), rawNodeCount = 1, rawCharLength = 42, compactJson = "[]")
            override suspend fun readScreenTextBoxes(minConfidence: Double) = emptyList<TextBox>()
            override suspend fun findText(query: String, exact: Boolean) = emptyList<PrunedUiElement>()
            override suspend fun waitForText(query: String, timeoutMs: Long, intervalMs: Long): PrunedUiElement? = null
            override suspend fun captureScreenshot(): ByteArray? = null
            override fun computeSignature(elements: List<PrunedUiElement>) = "sig_${elements.size}"
            override fun dispose() {}
        }

        var longPressCalled = false
        val recordingAction = object : ActionPort by FakeActionAdapter() {
            override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean {
                longPressCalled = true
                return true
            }
        }
        val brain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ) = ReActActionModel(
                thought = "Tekan lama item ini",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.LONG_PRESS,
                targetIndex = 1
            )

            override suspend fun verifyGoalCompletion(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot
            ) = GoalVerificationVerdict(achieved = false)
        }

        val orchestrator = GepyOrchestrator(
            perceptionPort = mixedWindowPerception,
            actionPort = recordingAction,
            memoryPort = SqliteMemoryRepository(),
            brainPort = brain,
            voiceHudPort = FakeVoiceHud(),
            appLauncher = AppIntentLauncher()
        )

        orchestrator.executeVoiceGoal("Tekan lama rekening")

        assertFalse("longPressCoordinates tidak boleh dipanggil pada node paket terlarang", longPressCalled)
        assertEquals(GepyFsmState.ERROR, orchestrator.state.fsmState)
    }
}
