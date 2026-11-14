package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.wellness.DailyBriefingEngine
import java.time.ZoneId
import java.time.ZonedDateTime
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.GoalVerificationVerdict
import com.teknologigawai.gepy.core.ports.OsQueryStatus
import com.teknologigawai.gepy.core.ports.OsStateCheckResult
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.OsVerificationBaseline
import com.teknologigawai.gepy.core.ports.OsVerifyDomain
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.service.RawAccessibilityNodeSnapshot
import com.teknologigawai.gepy.core.skills.AndroidSystemToolSkill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian jadwal otonom, kontrol sistem Zero-UI, linimasa proses & pemulihan pembatalan GepyOrchestrator.
 */
class GepyOrchestratorTimelineAndScheduleTest {
    @Test
    fun `Fast-Path alarm pagi berjalan 0 token LLM dan tidak terpicu ulang oleh async AlarmManager delay`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "launcher_root",
                packageName = "com.sec.android.app.launcher",
                text = "Beranda",
                isClickable = false,
                left = 0,
                top = 0,
                right = 720,
                bottom = 1280
            )
        )

        var brainCalls = 0
        var verifierCalls = 0
        val unfailingNoOpBrain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                brainCalls++
                return ReActActionModel(thought = "should never run", reflectionOnLastStep = ReflectionStatus.INITIAL, action = AgentActionType.DONE)
            }

            override suspend fun verifyGoalCompletion(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot
            ): GoalVerificationVerdict {
                verifierCalls++
                return GoalVerificationVerdict(achieved = true)
            }
        }

        // Simulasikan AlarmManager.nextAlarmClock masih mengembalikan NOT_FOUND 2 ms sesudah intent asinkron
        val laggingAlarmOsVerifier = object : OsStateVerifierPort {
            override fun captureBaseline(): OsVerificationBaseline = OsVerificationBaseline()
            override fun verifyGoalOsState(userGoal: String, baseline: OsVerificationBaseline?): OsStateCheckResult =
                OsStateCheckResult(
                    domain = OsVerifyDomain.ALARM,
                    status = OsQueryStatus.NOT_FOUND,
                    missingReason = "Alarm belum terdaftar secara sinkron."
                )
        }

        val hud = FakeVoiceHud()
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = unfailingNoOpBrain,
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher(),
            osStateVerifier = laggingAlarmOsVerifier
        )

        orchestrator.executeVoiceGoal("pasang alarm jam 5 pagi")

        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertEquals(1, orchestrator.state.currentStep)
        assertEquals(0, orchestrator.state.lastLlmLatencyMs)
        assertEquals(0, brainCalls)
        assertEquals(0, verifierCalls)
        assertEquals(1, hud.spokenCompletions.size)
        assertTrue(hud.spokenCompletions.first().contains("Jam 05:00."))
    }

    @Test
    fun `Orchestrator Evening Reflection berjalan di AUTONOMOUS namun menunda saat layar game aktif`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "game_root",
                packageName = "com.mobile.legends",
                text = "Arena",
                isClickable = false,
                left = 0,
                top = 0,
                right = 720,
                bottom = 1280
            )
        )

        val zone = ZoneId.of("Asia/Jakarta")
        val nightClock = ZonedDateTime.of(2026, 11, 11, 21, 15, 0, 0, zone)
        val byok = ByokPreferencesManager(context = null)
        byok.updateSettings(ByokSettings(proactivityLevel = ProactivityLevel.AUTONOMOUS))

        val memoryManager = UserMemoryManager(clockMsProvider = { nightClock.toInstant().toEpochMilli() })
        memoryManager.clearLocalMemory()
        memoryManager.setUserDisplayName("Maulana")

        val briefingEngine = DailyBriefingEngine(
            context = null,
            userMemoryManager = memoryManager,
            systemToolsBridge = AndroidSystemToolsBridge(context = null, clockProvider = { nightClock }),
            clockProvider = { nightClock },
            proactivityLevelProvider = { byok.currentSettings.proactivityLevel }
        )

        val hud = FakeVoiceHud()
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = object : BrainPort {
                override suspend fun decideNextAction(
                    userGoal: String,
                    currentPackage: String,
                    screenElements: List<PrunedUiElement>,
                    memory: ShortTermMemorySnapshot,
                    injectedDomainKnowledge: String?
                ): ReActActionModel = throw AssertionError("Tidak boleh memanggil LLM")
            },
            voiceHudPort = hud,
            appLauncher = AppIntentLauncher(),
            byokPreferencesManager = byok,
            userMemoryManager = memoryManager,
            dailyBriefingEngine = briefingEngine
        )

        // 1. Saat game aktif di depan, refleksi malam DITUNDA (belum terpakai jatah hari ini)
        val deferredResult = orchestrator.triggerEveningReflectionIfDue()
        assertEquals(null, deferredResult)
        assertTrue(hud.spokenCompletions.isEmpty())

        // 2. Saat keluar ke Launcher, AUTONOMOUS langsung membacakan 1x/malam
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
        val spoken = orchestrator.triggerEveningReflectionIfDue()
        assertTrue(spoken != null && spoken.isNotBlank())
        assertEquals("EVENING_REFLECTION", orchestrator.state.lastActionLabel)
        assertEquals(1, hud.spokenCompletions.size)

        // 3. ZEN_PASSIVE menahan sapaan pagi maupun evaluasi proaktif berkala
        byok.updateSettings(ByokSettings(proactivityLevel = ProactivityLevel.ZEN_PASSIVE))
        assertEquals(null, orchestrator.triggerMorningWakeupBriefingIfDue())
        orchestrator.evaluateProactivePulseIfDue(nowWallMs = 999_999L)
        assertEquals(1, hud.spokenCompletions.size)
    }

    @Test
    fun `API-First Zero-UI executes senter and timer with 0 LLM calls`() = runTest {
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

        val hud = FakeVoiceHud()
        val bridge = AndroidSystemToolsBridge(context = null)
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

        // 1. Perintah senter
        orchestrator.executeVoiceGoal("Nyalain senter")
        assertEquals(0, llmCalls)
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertTrue(orchestrator.state.lastActionLabel.startsWith("API_FAST_PATH"))
        assertTrue(hud.spokenCompletions.last().contains("Senter"))

        // 2. Perintah timer
        orchestrator.executeVoiceGoal("Pasang timer 5 menit buat masak mie")
        assertEquals(0, llmCalls)
        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertTrue(hud.spokenCompletions.last().contains("5 menit"))
    }

    @Test
    fun `processTimeline records human readable thinking steps and completes cleanly without technical package ids`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "search_btn",
                packageName = "com.example.shop",
                text = "Cari",
                isClickable = true,
                left = 0,
                top = 0,
                right = 100,
                bottom = 50,
                viewResourceId = "com.example.shop:id/search_btn"
            )
        )

        var step = 0
        val brain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                step++
                return if (step == 1) {
                    ReActActionModel(
                        thought = "Membuka tombol pencarian (com.example.shop)",
                        reflectionOnLastStep = ReflectionStatus.INITIAL,
                        action = AgentActionType.CLICK,
                        targetIndex = 1
                    )
                } else {
                    ReActActionModel(
                        thought = "Hasil pencarian sudah tampil di layar",
                        reflectionOnLastStep = ReflectionStatus.CORRECT,
                        action = AgentActionType.DONE,
                        completionTts = "Sudah aku bukakan pencariannya ya."
                    )
                }
            }

            override suspend fun verifyGoalCompletion(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot
            ): GoalVerificationVerdict = GoalVerificationVerdict(achieved = true)
        }

        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = brain,
            voiceHudPort = FakeVoiceHud(),
            appLauncher = AppIntentLauncher()
        )

        orchestrator.executeVoiceGoal("Cari charger di toko")

        val finalState = orchestrator.state
        assertEquals(GepyFsmState.COMPLETED, finalState.fsmState)
        assertTrue(finalState.goalStartedAtMs > 0L)
        assertTrue(finalState.goalElapsedMs >= 0L)
        assertTrue("processTimeline harus berisi minimal 2 langkah berpikir", finalState.processTimeline.size >= 2)
        assertTrue(finalState.processTimeline.all { it.state == ProcessStepState.DONE })
        assertTrue(
            "processTimeline harus bebas package ID teknis",
            finalState.processTimeline.none { it.text.contains("com.example.shop") }
        )
        assertTrue(finalState.processTimeline.any { it.text.contains("Membuka tombol pencarian") })

        orchestrator.clearConversationSession()
        assertTrue(orchestrator.state.processTimeline.isEmpty())
        assertEquals(0L, orchestrator.state.goalElapsedMs)
    }

    @Test
    fun `processTimeline strips internal completion_tts jargon and maps action headline to human label`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "btn_ok",
                packageName = "com.example.chat",
                text = "Oke",
                isClickable = true,
                left = 0,
                top = 0,
                right = 100,
                bottom = 50,
                viewResourceId = "com.example.chat:id/btn_ok"
            )
        )

        var step = 0
        val brain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                step++
                return if (step == 1) {
                    ReActActionModel(
                        thought = "Pertanyaan kapabilitas, jawab langsung lewat completion_tts.",
                        reflectionOnLastStep = ReflectionStatus.INITIAL,
                        action = AgentActionType.CLICK,
                        targetIndex = 1
                    )
                } else {
                    ReActActionModel(
                        thought = "Sudah siap mengirim jawaban akhir lewat completion_tts ke pengguna.",
                        reflectionOnLastStep = ReflectionStatus.CORRECT,
                        action = AgentActionType.DONE,
                        completionTts = "Siap, sudah kuproses ya."
                    )
                }
            }

            override suspend fun verifyGoalCompletion(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot
            ): GoalVerificationVerdict = GoalVerificationVerdict(achieved = true)
        }

        val fakeHud = FakeVoiceHud()
        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = brain,
            voiceHudPort = fakeHud,
            appLauncher = AppIntentLauncher()
        )

        orchestrator.executeVoiceGoal("Buka tombol oke")

        val timelineTexts = orchestrator.state.processTimeline.map { it.text }
        assertTrue(
            "processTimeline tidak boleh membocorkan token completion_tts: $timelineTexts",
            timelineTexts.none { it.contains("completion_tts", ignoreCase = true) }
        )
        assertTrue(
            "processTimeline harus memanusiakan deskripsi langkah tanpa istilah teknis: $timelineTexts",
            timelineTexts.any { it.contains("Menyiapkan jawaban langsung", ignoreCase = true) }
        )
        assertTrue(
            "Overlay HUD tidak boleh menampilkan label teknis 'Langkah 1 • CLICK': ${fakeHud.thinkingStates}",
            fakeHud.thinkingStates.none { (headline, _) ->
                headline.contains("Langkah", ignoreCase = true) || headline.contains("CLICK")
            }
        )
        assertTrue(
            "Overlay HUD harus menampilkan headline manusiawi untuk aksi klik: ${fakeHud.thinkingStates}",
            fakeHud.thinkingStates.any { (headline, _) -> headline == "Mengetuk elemen layar" }
        )
    }

    @Test
    fun `external coroutine cancellation during ReAct loop resets fsmState back to IDLE`() = runTest {
        val pruner = AccessibilityTreePruner()
        pruner.updateLatestNodeForTest(
            RawAccessibilityNodeSnapshot(
                mapId = "btn_search",
                packageName = "com.example.shop",
                text = "Cari",
                isClickable = true,
                left = 0,
                top = 0,
                right = 100,
                bottom = 50,
                viewResourceId = "com.example.shop:id/btn_search"
            )
        )

        val brainEntered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val brain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel {
                brainEntered.complete(Unit)
                neverCompletes.await()
                return ReActActionModel(
                    thought = "DONE",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE
                )
            }

            override suspend fun verifyGoalCompletion(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot
            ): GoalVerificationVerdict = GoalVerificationVerdict(achieved = true)
        }

        val orchestrator = GepyOrchestrator(
            perceptionPort = pruner,
            actionPort = FakeActionAdapter(),
            memoryPort = SqliteMemoryRepository(),
            brainPort = brain,
            voiceHudPort = FakeVoiceHud(),
            appLauncher = AppIntentLauncher()
        )

        val job = launch {
            orchestrator.executeVoiceGoal("Cari barang")
        }
        brainEntered.await()
        assertEquals(GepyFsmState.THINKING, orchestrator.state.fsmState)

        job.cancel()
        job.join()

        assertEquals(
            "Pembatalan coroutine eksternal wajib mengembalikan fsmState ke IDLE agar UI tidak macet",
            GepyFsmState.IDLE,
            orchestrator.state.fsmState
        )
        assertFalse(orchestrator.state.isExecutingGoal)
    }

    @Test
    fun `cleanStepTextForDisplay membersihkan tag playbook dan resource id mentah serta AppIntentLauncher mengenali varian Gojek`() {
        val cleanedPlaybook = ProcessTimelineTracker.cleanStepTextForDisplay(
            "[Playbook Fast-Path] Menggunakan pola terverifikasi (2x sukses) pada com.gojek.app:id/tv_title."
        )
        assertFalse(
            "Tag internal [Playbook Fast-Path] wajib dibersihkan dari teks HUD",
            cleanedPlaybook.contains("Playbook Fast-Path", ignoreCase = true)
        )
        assertFalse(
            "Resource ID Android mentah wajib dibersihkan dari teks HUD",
            cleanedPlaybook.contains("com.gojek.app:id/tv_title", ignoreCase = true)
        )

        val cleanedBook = ProcessTimelineTracker.cleanStepTextForDisplay(
            "Menunggu persetujuan sebelum mengetuk tombol \"com.gojek.app:id/btn_book Book\"."
        )
        assertFalse(cleanedBook.contains(":id/"))
        assertTrue(cleanedBook.contains("Book"))

        assertEquals("com.gojek.app", AppIntentLauncher.detectTargetAppPackageInGoal("pesan goride ke universitas alma ata"))
        assertEquals("com.gojek.app", AppIntentLauncher.detectTargetAppPackageInGoal("pesankan gocar ke stasiun"))
        assertEquals("com.gojek.app", AppIntentLauncher.detectTargetAppPackageInGoal("order gofood ayam geprek"))
    }
}
