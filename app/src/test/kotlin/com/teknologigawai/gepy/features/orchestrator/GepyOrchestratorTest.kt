package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ByokProviderPreset
import com.teknologigawai.gepy.core.config.PreferredLlmProvider
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.voice.GeminiLiveToolCall
import com.teknologigawai.gepy.service.RawAccessibilityNodeSnapshot
import com.teknologigawai.gepy.core.skills.AndroidSystemToolSkill
import com.teknologigawai.gepy.core.system.PhoneContactItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian unit inti GepyOrchestrator: Fast-Path skills, playbook self-healing, HITL, BYOK & Live Call routing.
 */
class GepyOrchestratorTest {
    @Test
    fun `fast path AndroidSystemToolSkill completes battery and time query with 0 LLM calls`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "root_1",
                packageName = "com.sec.android.app.launcher",
                text = "Beranda",
                isClickable = true,
                left = 0,
                top = 0,
                right = 500,
                bottom = 200
            )
        )

        var llmCallCount = 0
        val fakeBrain = FakeBrainPort {
            llmCallCount++
            ReActActionModel(
                thought = "Fallback LLM",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = "Fallback"
            )
        }

        val fakeHud = FakeVoiceHud()
        val memory = SqliteMemoryRepository()
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = memory,
            brainPort = fakeBrain,
            voiceHudPort = fakeHud,
            appLauncher = AppIntentLauncher(),
            skillRegistry = SkillRegistry(),
            systemToolsBridge = AndroidSystemToolsBridge(),
            hapticManager = HapticFeedbackManager(),
            byokPreferencesManager = ByokPreferencesManager()
        )

        orchestrator.executeVoiceGoal("Cek status baterai dan jam berapa sekarang")

        assertEquals(0, llmCallCount)
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertEquals(1, fakeHud.spokenCompletions.size)
        assertTrue(fakeHud.spokenCompletions.first().contains("Baterai"))
    }

    @Test
    fun `HITL interception blocks irreversible send button when user rejects`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "send_btn",
                viewResourceId = "com.whatsapp:id/send",
                packageName = "com.whatsapp",
                text = "Kirim",
                isClickable = true,
                left = 900,
                top = 1800,
                right = 1040,
                bottom = 1920
            )
        )

        val fakeBrain = FakeBrainPort {
            ReActActionModel(
                thought = "Mengetuk tombol Kirim pesan",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.CLICK,
                targetIndex = 1
            )
        }

        val fakeHud = FakeVoiceHud(hitlApprovalResponse = false)
        val fakeAction = FakeActionAdapter()
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = fakeAction,
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = fakeHud,
            appLauncher = AppIntentLauncher()
        )
        orchestrator.setFullAccessZeroHitl(false)

        orchestrator.executeVoiceGoal("Ketuk tombol kirim di layar")

        assertEquals(1, fakeHud.hitlRequestedCount)
        assertTrue(fakeAction.tappedElements.isEmpty())
        assertEquals("ABORTED", orchestrator.state.lastActionLabel)
    }

    @Test
    fun `handleLiveToolCall routes screen reader and OS system tools accurately`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "n1",
                packageName = "com.whatsapp",
                text = "Budi Santoso",
                isClickable = true,
                left = 20,
                top = 120,
                right = 600,
                bottom = 220
            )
        )

        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = FakeBrainPort {
                ReActActionModel(
                    thought = "Done",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE
                )
            },
            voiceHudPort = FakeVoiceHud(),
            appLauncher = AppIntentLauncher()
        )

        val screenRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(id = "tc_1", name = "read_current_screen", args = emptyMap())
        )
        assertEquals("SUCCESS", screenRes["status"])
        assertEquals("com.whatsapp", screenRes["foreground_package"])

        val statusRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(id = "tc_2", name = "get_device_and_time_status", args = emptyMap())
        )
        assertEquals("SUCCESS", statusRes["status"])
        assertEquals(85, statusRes["battery_percent"])

        val alarmRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_3",
                name = "set_system_alarm",
                args = mapOf("hour" to 6, "minute" to 30, "label" to "Bangun Pagi")
            )
        )
        assertEquals("SUCCESS", alarmRes["status"])

        val rpcRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_rpc",
                name = "execute_antigravity_rpc",
                args = mapOf("action" to "GIT_STATUS")
            )
        )
        assertTrue(rpcRes.containsKey("status"))
        assertTrue(rpcRes.containsKey("summary_id"))
    }

    @Test
    fun `applyByokPreset switches active provider to CUSTOM_OPENAI with OpenRouter preset`() {
        val orchestrator = GepyOrchestrator(
            perceptionPort = AccessibilityTreePruner(),
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = FakeBrainPort {
                ReActActionModel(
                    thought = "Done",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE
                )
            },
            voiceHudPort = FakeVoiceHud(),
            appLauncher = AppIntentLauncher()
        )

        orchestrator.applyByokPreset(ByokProviderPreset.OPENROUTER, apiKey = "sk-or-v1-test")

        assertEquals(PreferredLlmProvider.CUSTOM_OPENAI, orchestrator.state.byokSettings.preferredProvider)
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            orchestrator.state.byokSettings.normalizedCustomOpenAiUrl
        )
        assertEquals(
            "deepseek/deepseek-chat-v3-0324",
            orchestrator.state.byokSettings.customOpenAiModel
        )
        assertTrue(orchestrator.state.byokSettings.hasCustomOpenAi)

        orchestrator.resetByokSettings()
        assertFalse(orchestrator.state.byokSettings.hasCustomOpenAi)
    }

    @Test
    fun `self-healing playbook matcher resolves renamed or Compose button by semantic label`() = runTest {
        val orchestrator = GepyOrchestrator(
            perceptionPort = AccessibilityTreePruner(),
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = FakeBrainPort {
                ReActActionModel(
                    thought = "Done",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE
                )
            },
            voiceHudPort = FakeVoiceHud(),
            appLauncher = AppIntentLauncher()
        )

        // Simulasikan elemen Compose yang tidak lagi memakai id "com.whatsapp:id/menuitem_search"
        // melainkan hanya memiliki label teks "Cari"
        val elements = listOf(
            PrunedUiElement(
                index = 4,
                nodeId = "compose_search_btn",
                viewResourceId = null,
                packageName = "com.whatsapp",
                text = "Cari",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                rect = PrunedUiElement.buildRect(800, 80, 120, 100)
            )
        )

        val decision = orchestrator.tryBuildPlaybookDecision(
            targetResourceId = "com.whatsapp:id/menuitem_search",
            actionType = "CLICK",
            successCount = 3,
            elements = elements
        )
        assertEquals(AgentActionType.CLICK, decision?.action)
        assertEquals(4, decision?.targetIndex)
    }

    @Test
    fun `sequential live call app switching opens WhatsApp then Instagram with zero LLM calls`() = runTest {
        var llmCalls = 0
        val voiceHud = FakeVoiceHud()
        val orchestrator = GepyOrchestrator(
            perceptionPort = AccessibilityTreePruner(),
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = FakeBrainPort {
                llmCalls++
                ReActActionModel(
                    thought = "Unexpected LLM call",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE
                )
            },
            voiceHudPort = voiceHud,
            appLauncher = AppIntentLauncher()
        )

        // 1. Pertama pengguna meminta "buka wa"
        orchestrator.executeVoiceGoal("buka wa")
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertEquals("com.whatsapp", orchestrator.state.currentPackage)
        assertEquals(0, llmCalls)

        // 2. Saat berada di dalam WhatsApp, pengguna meminta "buka instagram"
        orchestrator.executeVoiceGoal("buka instagram")
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertEquals("com.instagram.android", orchestrator.state.currentPackage)
        assertEquals(0, llmCalls)

        // 3. Uji juga lewat tool call langsung open_application dari Gemini Live
        val igToolRes = orchestrator.handleLiveToolCall(
            GeminiLiveToolCall(
                id = "tc_ig",
                name = "open_application",
                args = mapOf("app_name" to "Instagram")
            )
        )
        assertEquals("SUCCESS", igToolRes["status"])
        assertEquals("com.instagram.android", igToolRes["resolved_package"])
    }

    @Test
    fun `cross-platform Instagram search and DM goal routes to InstagramSocialSkill instead of WhatsApp`() = runTest {
        val registry = SkillRegistry(
            listOf(
                com.teknologigawai.gepy.core.skills.WhatsAppMessagingSkill(),
                com.teknologigawai.gepy.core.skills.InstagramSocialSkill(),
                com.teknologigawai.gepy.core.skills.UniversalAppNavigationSkill()
            )
        )

        val igGoal = "Cari IG budi_santoso lalu kirim DM halo apa kabar"
        assertEquals("com.instagram.android", AppIntentLauncher.detectTargetAppPackageInGoal(igGoal))

        // Bahkan ketika pengguna sedang berada di WhatsApp, perintah "Cari IG ... kirim DM" harus cocok ke InstagramSocialSkill
        val matched = registry.matchSkills(
            voiceTranscript = igGoal,
            activePackage = "com.whatsapp"
        )
        assertTrue(matched.any { it.skillId == "instagram_social_dm" })
        assertFalse(matched.any { it.skillId == "whatsapp_messaging" })

        // Dan bila perintah multi-platform menyebutkan IG lebih dulu lalu WA, aplikasi awal yang dideteksi adalah Instagram
        val crossGoal = "Cari akun budi di IG lalu kirim ke WA"
        assertEquals("com.instagram.android", AppIntentLauncher.detectTargetAppPackageInGoal(crossGoal))
    }

    @Test
    fun `disambiguate preserves Telepon prefix and executes dialer with 0 LLM calls`() = runTest {
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

        val allContacts = listOf(
            PhoneContactItem("Budi Santoso", "081234567890"),
            PhoneContactItem("Budi Hartono", "089876543210")
        )
        val bridge = AndroidSystemToolsBridge(context = null).apply {
            phoneContactsOverrideProvider = { query, _ ->
                if (query.isEmpty() || query.contains("Budi", ignoreCase = true)) {
                    allContacts
                } else emptyList()
            }
        }

        val hud = FakeVoiceHud(disambiguationChoice = "Budi Santoso (081234567890)")
        val skill = AndroidSystemToolSkill(systemBridge = bridge)
        val registry = SkillRegistry(skills = listOf(skill))
        val gatekeeper = com.teknologigawai.gepy.features.brain.IntentPrecisionGatekeeper(
            phoneContactsProvider = { allContacts }
        )

        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = fakeBrain,
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher(),
            skillRegistry = registry,
            systemToolsBridge = bridge,
            intentPrecisionGatekeeper = gatekeeper
        )

        orchestrator.executeVoiceGoal("Telepon Budi")

        // Goal berhasil diselesaikan via fast-path dialer setelah disambiguasi, tanpa memanggil LLM
        assertEquals(0, llmCalls)
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertTrue(hud.spokenCompletions.last().contains("Budi Santoso"))
    }
}
