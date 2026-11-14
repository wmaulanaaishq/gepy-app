package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.laptop.AntigravityRpcResponse
import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ByokProviderPreset
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.memory.ConversationChannel
import com.teknologigawai.gepy.core.memory.ConversationSessionBuffer
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.orchestrator.UnifiedActionSupervisor
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignPersonaEngine
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.MemoryPort
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.system.AndroidOsStateVerifier
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.core.web.WebSearchAndReaderBridge
import com.teknologigawai.gepy.core.wellness.CoPilotContextSentinel
import com.teknologigawai.gepy.core.wellness.DailyBriefingEngine
import com.teknologigawai.gepy.features.brain.IntentPrecisionGatekeeper
import com.teknologigawai.gepy.core.wellness.WellnessAndGuardianEngine
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.features.perception.SetOfMarksGrounder
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.voice.GeminiLiveToolCall
import com.teknologigawai.gepy.features.voice.LiveConnectionState
import com.teknologigawai.gepy.features.voice.SpeculativePreFetcher
import com.teknologigawai.gepy.features.voice.VoiceAudioEngine
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orkestrator Utama **Gepy v3.0 Native** (`Observe -> Think -> Act -> Verify`).
 *
 * Berjalan dalam satu proses ART (*Single-Process Zero-Isolate*) dan mengoordinasikan:
 * - `PerceptionPort` (*In-Memory XML Context Pruner* + *ML Kit OCR Fallback*)
 * - `BrainPort` (*Fireworks DeepSeek V4.1* + *Gemini 2.5 Flash* + *BYOK Multi-Provider*)
 * - `ActionPort` (*Focus-Before-Type* + *Reactive `waitStable()`* + *Safety Gatekeeper*)
 * - `MemoryPort` (*Room SQLite `gepy_core.db`*, *Playbook Graph*, *Loop Detector*)
 * - `VoiceHudPort` (*Gemini 3.8 Live Full-Duplex*, *Native Android SpeechRecognizer*, *Overlay HUD*)
 * - `AndroidSystemToolsBridge` (*Zero-UI Fast System Access* `< 5 ms`)
 */
class GepyOrchestrator(
    val perceptionPort: PerceptionPort,
    val actionPort: ActionPort,
    val memoryPort: MemoryPort,
    val brainPort: BrainPort,
    val voiceHudPort: VoiceHudPort,
    val appLauncher: AppIntentLauncher,
    val skillRegistry: SkillRegistry = SkillRegistry(),
    val systemToolsBridge: AndroidSystemToolsBridge = AndroidSystemToolsBridge(),
    val webSearchBridge: WebSearchAndReaderBridge = WebSearchAndReaderBridge(),
    val deepLinkRouter: IntentDeepLinkRouter = IntentDeepLinkRouter(systemToolsBridge = systemToolsBridge),
    val hapticManager: HapticFeedbackManager = HapticFeedbackManager(),
    val byokPreferencesManager: ByokPreferencesManager = ByokPreferencesManager(),
    val userMemoryManager: UserMemoryManager? = null,
    val wellnessEngine: WellnessAndGuardianEngine? = null,
    val dailyBriefingEngine: DailyBriefingEngine? = null,
    val setOfMarksGrounder: SetOfMarksGrounder = SetOfMarksGrounder(),
    val unifiedSupervisor: UnifiedActionSupervisor = UnifiedActionSupervisor(),
    val intentPrecisionGatekeeper: IntentPrecisionGatekeeper = IntentPrecisionGatekeeper(
        phoneContactsProvider = { systemToolsBridge.searchPhoneContacts("", limit = 100) }
    ),
    val coPilotSentinel: CoPilotContextSentinel = CoPilotContextSentinel(
        proactivityLevelProvider = { byokPreferencesManager.currentSettings.proactivityLevel }
    ),
    val conversationBuffer: ConversationSessionBuffer = ConversationSessionBuffer(),
    val osStateVerifier: OsStateVerifierPort = AndroidOsStateVerifier(),
    val maxStepsPerGoal: Int = EnvConfig.maxStepsPerSession,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private val API_FIRST_ZERO_UI_SKILL_IDS = setOf(
            "android_system_tools",
            "laptop_companion",
            "spotify_fast_path",
            "personal_memory_vault"
        )
    }

    /** Mesin *Speculative Listening Pre-Fetcher* (`arXiv:2611.06982v1`) untuk pra-tangkapan pohon UI `0 ms`. */
    val speculativePreFetcher: SpeculativePreFetcher = SpeculativePreFetcher(
        perceptionPortProvider = { perceptionPort },
        scope = scope
    )

    private val _stateFlow = MutableStateFlow(
        GepyOrchestratorState(
            byokSettings = byokPreferencesManager.currentSettings
        )
    )
    val stateFlow: StateFlow<GepyOrchestratorState> = _stateFlow.asStateFlow()

    val state: GepyOrchestratorState
        get() = _stateFlow.value

    @Volatile
    private var lastProactivePulseWallMs: Long = 0L

    @Volatile
    private var abortRequested: Boolean = false
    private var activeGoalJob: Job? = null
    private val activeGoalEpoch = AtomicLong(0L)
    private val goalLifecycleLock = Any()

    private suspend fun ensureGoalRunActive(expectedEpoch: Long) {
        currentCoroutineContext().ensureActive()
        if (abortRequested || activeGoalEpoch.get() != expectedEpoch) {
            throw GoalSupersededAbortException()
        }
    }

    private fun updateStateIfGoalActive(
        expectedEpoch: Long,
        transform: (GepyOrchestratorState) -> GepyOrchestratorState
    ) {
        synchronized(goalLifecycleLock) {
            if (abortRequested || activeGoalEpoch.get() != expectedEpoch) return
            _stateFlow.update { current ->
                if (abortRequested || activeGoalEpoch.get() != expectedEpoch) {
                    current
                } else {
                    ProcessTimelineTracker.applyProcessTimelineUpdate(current, transform(current))
                }
            }
        }
    }

    private val goalHost: OrchestratorGoalHost = object : OrchestratorGoalHost {
        override val currentState: GepyOrchestratorState
            get() = _stateFlow.value

        override fun isGoalEpochActiveAndNotAborted(expectedEpoch: Long): Boolean =
            activeGoalEpoch.get() == expectedEpoch && !abortRequested

        override suspend fun ensureGoalRunActive(expectedEpoch: Long) =
            this@GepyOrchestrator.ensureGoalRunActive(expectedEpoch)

        override fun updateStateIfGoalActive(
            expectedEpoch: Long,
            transform: (GepyOrchestratorState) -> GepyOrchestratorState
        ) = this@GepyOrchestrator.updateStateIfGoalActive(expectedEpoch, transform)

        override fun updateStateDirectly(
            transform: (GepyOrchestratorState) -> GepyOrchestratorState
        ) {
            _stateFlow.update(transform)
        }

        override fun currentSpeechContext(): SovereignSpeechContext =
            this@GepyOrchestrator.currentSpeechContext()

        override fun buildCurrentReActPersonaDirective(): String =
            this@GepyOrchestrator.buildCurrentReActPersonaDirective()

        override fun recordChatTurn(role: String, text: String, channel: ConversationChannel) =
            this@GepyOrchestrator.recordChatTurn(role, text, channel)

        override fun recordClosingAssistantTurnIfPending(text: String) =
            this@GepyOrchestrator.recordClosingAssistantTurnIfPending(text)

        override fun cancelActiveGoal(reason: String) =
            this@GepyOrchestrator.cancelActiveGoal(reason = reason)

        override fun launchAsyncGoal(goalText: String) =
            this@GepyOrchestrator.launchAsyncGoal(goalText)

        override fun cancelAsyncGoalForImmediatePath() =
            this@GepyOrchestrator.cancelAsyncGoalForImmediatePath()

        override suspend fun triggerOnDemandBriefing(): String =
            this@GepyOrchestrator.triggerOnDemandBriefing()
    }

    private val playbookCoordinator = PlaybookAndLaunchCoordinator(
        perceptionPort = perceptionPort,
        appLauncher = appLauncher,
        skillRegistry = skillRegistry,
        deepLinkRouter = deepLinkRouter
    )

    private val stepActionDispatcher = StepActionDispatcher(
        perceptionPort = perceptionPort,
        actionPort = actionPort,
        memoryPort = memoryPort,
        voiceHudPort = voiceHudPort,
        appLauncher = appLauncher,
        systemToolsBridge = systemToolsBridge,
        hapticManager = hapticManager,
        byokPreferencesManager = byokPreferencesManager,
        goalHost = goalHost
    )

    private val liveCallToolDispatcher = LiveCallToolDispatcher(
        perceptionPort = perceptionPort,
        voiceHudPort = voiceHudPort,
        appLauncher = appLauncher,
        systemToolsBridge = systemToolsBridge,
        webSearchBridge = webSearchBridge,
        deepLinkRouter = deepLinkRouter,
        hapticManager = hapticManager,
        byokPreferencesManager = byokPreferencesManager,
        dailyBriefingEngine = dailyBriefingEngine,
        unifiedSupervisor = unifiedSupervisor,
        speculativePreFetcher = speculativePreFetcher,
        goalHost = goalHost
    )

    private val fastPathHandler = VoiceGoalFastPathHandler(
        perceptionPort = perceptionPort,
        memoryPort = memoryPort,
        brainPort = brainPort,
        voiceHudPort = voiceHudPort,
        appLauncher = appLauncher,
        skillRegistry = skillRegistry,
        systemToolsBridge = systemToolsBridge,
        webSearchBridge = webSearchBridge,
        deepLinkRouter = deepLinkRouter,
        hapticManager = hapticManager,
        userMemoryManager = userMemoryManager,
        unifiedSupervisor = unifiedSupervisor,
        intentPrecisionGatekeeper = intentPrecisionGatekeeper,
        conversationBuffer = conversationBuffer,
        osStateVerifier = osStateVerifier,
        maxStepsPerGoal = maxStepsPerGoal,
        apiFirstZeroUiSkillIds = API_FIRST_ZERO_UI_SKILL_IDS,
        stepActionDispatcher = stepActionDispatcher,
        goalHost = goalHost
    )

    private val reActRunner = VoiceGoalReActRunner(
        perceptionPort = perceptionPort,
        actionPort = actionPort,
        memoryPort = memoryPort,
        brainPort = brainPort,
        voiceHudPort = voiceHudPort,
        skillRegistry = skillRegistry,
        hapticManager = hapticManager,
        userMemoryManager = userMemoryManager,
        wellnessEngine = wellnessEngine,
        setOfMarksGrounder = setOfMarksGrounder,
        unifiedSupervisor = unifiedSupervisor,
        conversationBuffer = conversationBuffer,
        osStateVerifier = osStateVerifier,
        maxStepsPerGoal = maxStepsPerGoal,
        speculativePreFetcher = speculativePreFetcher,
        playbookCoordinator = playbookCoordinator,
        stepActionDispatcher = stepActionDispatcher,
        fastPathHandler = fastPathHandler,
        goalHost = goalHost
    )

    private fun launchAsyncGoal(goalText: String) {
        synchronized(goalLifecycleLock) {
            activeGoalJob?.cancel()
            abortRequested = false
            val launchedEpoch = activeGoalEpoch.incrementAndGet()
            activeGoalJob = scope.launch {
                try {
                    executeVoiceGoalWithEpoch(goalText, expectedEpoch = launchedEpoch)
                } finally {
                    synchronized(goalLifecycleLock) {
                        if (activeGoalEpoch.get() == launchedEpoch) {
                            activeGoalJob = null
                        }
                    }
                }
            }
        }
    }

    private fun cancelAsyncGoalForImmediatePath() {
        synchronized(goalLifecycleLock) {
            activeGoalEpoch.incrementAndGet()
            activeGoalJob?.cancel()
            activeGoalJob = null
        }
    }

    init {
        coPilotSentinel.onWhisperAvailable = { whisper ->
            val currentLevel = byokPreferencesManager.currentSettings.proactivityLevel
            val durationMs = if (currentLevel == ProactivityLevel.AUTONOMOUS) 12_000L else 8_000L
            scope.launch {
                voiceHudPort.showProactiveWhisper(
                    title = whisper.title,
                    subtitle = whisper.subtitle,
                    actionLabel = whisper.actionLabel,
                    goalPayload = whisper.goalPayload,
                    durationMs = durationMs
                )
                if (currentLevel == ProactivityLevel.AUTONOMOUS &&
                    whisper.priority >= 3 &&
                    !state.isExecutingGoal &&
                    !voiceHudPort.isLiveCallActive &&
                    wellnessEngine?.currentSnapshot?.isGuestPrivacyShieldActive != true
                ) {
                    voiceHudPort.finishAndSpeak("${whisper.title}. ${whisper.subtitle}")
                }
            }
        }
        systemToolsBridge.laptopHostProvider = {
            _stateFlow.value.byokSettings.laptopTailscaleHost
        }
        syncVoiceCredentialsFromByok(byokPreferencesManager.currentSettings)
        userMemoryManager?.let { memMgr ->
            scope.launch(Dispatchers.IO) {
                memMgr.initializeTemporalStore()
            }
        }

        scope.launch {
            byokPreferencesManager.settingsFlow.collect { newByok ->
                syncVoiceCredentialsFromByok(newByok)
                _stateFlow.update {
                    it.copy(
                        byokSettings = newByok
                    )
                }
            }
        }

        if (wellnessEngine != null && systemToolsBridge.wellnessStatusProvider == null) {
            systemToolsBridge.wellnessStatusProvider = {
                wellnessEngine.currentSnapshot.toToolSummaryMap()
            }
        }
        val speechCtxLambda: () -> SovereignSpeechContext = { currentSpeechContext() }
        systemToolsBridge.speechContextProvider = speechCtxLambda
        systemToolsBridge.isLiveCallActiveProvider = { state.isLiveCallActive || voiceHudPort.isLiveCallActive }
        systemToolsBridge.liveCallVolumeGetter = { byokPreferencesManager.currentSettings.liveCallVolumeScale }
        systemToolsBridge.onLiveCallVolumeChanged = { newScale -> setLiveCallVolumeScale(newScale) }
        deepLinkRouter.speechContextProvider = speechCtxLambda
        appLauncher.speechContextProvider = speechCtxLambda
        intentPrecisionGatekeeper.speechContextProvider = speechCtxLambda

        val voiceEngine = voiceHudPort as? VoiceAudioEngine
        if (voiceEngine != null) {
            voiceEngine.speechContextProvider = speechCtxLambda
            voiceEngine.setLiveCallOutputScale(byokPreferencesManager.currentSettings.liveCallVolumeScale)
            voiceEngine.geminiLiveClient.sovereignPersonaDirectiveProvider = {
                buildCurrentLiveVoicePersonaDirective()
            }
            voiceEngine.sharedConversationContextProvider = {
                conversationBuffer.toPromptContextBlock()
            }
            voiceEngine.onLiveTurnRecorded = { role, text ->
                recordChatTurn(role = role, text = text, channel = ConversationChannel.LIVE_CALL)
            }
            voiceEngine.onInterimSpeechListener = { interimTranscript ->
                speculativePreFetcher.onInterimTranscript(interimTranscript)
            }
            voiceEngine.onLiveCallTerminated = { reason ->
                cancelActiveGoal(reason = reason, speakCancellation = false)
            }
            scope.launch {
                voiceEngine.geminiLiveClient.connectionStateFlow.collect { connState ->
                    val isLive = (connState == LiveConnectionState.CONNECTED ||
                                  connState == LiveConnectionState.CONNECTING ||
                                  connState == LiveConnectionState.RECONNECTING) ||
                                 voiceEngine.isLiveCallActive
                    val wasActive = _stateFlow.value.isLiveCallActive
                    _stateFlow.update { it.copy(isLiveCallActive = isLive) }
                    if (wasActive && !isLive && (
                            activeGoalJob != null ||
                                _stateFlow.value.isExecutingGoal ||
                                _stateFlow.value.fsmState == GepyFsmState.LISTENING
                        )
                    ) {
                        cancelActiveGoal(reason = "Panggilan live diakhiri.", speakCancellation = false)
                    }
                }
            }
        }

        scope.launch {
            voiceHudPort.voiceCommandFlow.collect { command ->
                val clean = command.trim()
                if (clean.isEmpty()) return@collect

                val lower = clean.lowercase()
                val isStopWord = lower in setOf("batal", "batalkan", "stop", "berhenti", "cukup", "cancel") ||
                    lower.startsWith("batalkan ") || lower.startsWith("berhenti ")
                if (isStopWord) {
                    if (state.isExecutingGoal || state.isBusy) {
                        cancelActiveGoal("Perintah dihentikan oleh suara pengguna.")
                    }
                    return@collect
                }

                // Saat Live Call Hands-Free aktif dan agen sedang mengeksekusi langkah UI (isExecutingGoal),
                // abaikan serpihan suara pendek (< 3 kata, bukan perintah "buka ...") agar gema/napas
                // mikrofon tidak membatalkan tugas lintas aplikasi yang sedang berjalan.
                if (state.isExecutingGoal && state.isLiveCallActive) {
                    val wordCount = clean.split(Regex("""\s+""")).size
                    val isExplicitNewLaunch = lower.startsWith("buka ") || lower.startsWith("pindah ke ")
                    if (wordCount < 3 && !isExplicitNewLaunch) {
                        return@collect
                    }
                }

                launchAsyncGoal(clean)
            }
        }

        scope.launch {
            voiceHudPort.bargeInInterruptFlow.collect { signal ->
                if (signal == "LIVE_CALL_HANGUP") {
                    cancelActiveGoal(reason = "Panggilan live diakhiri.", speakCancellation = false)
                    return@collect
                }
                if (signal == "GEMINI_LIVE_BARGE_IN") {
                    // Barge-In mikrofon lokal hanya menghentikan pemutaran suara Gemini Live,
                    // JANGAN membatalkan eksekusi otomatisasi UI latar belakang kecuali pengguna
                    // meminta pembatalan eksplisit melalui tool cancel_android_goal.
                    return@collect
                }
                if (signal == "STT_TIMEOUT_OR_ERROR") {
                    if (state.fsmState == GepyFsmState.LISTENING && !state.isLiveCallActive) {
                        _stateFlow.update {
                            it.copy(
                                fsmState = GepyFsmState.IDLE,
                                lastThought = "Tidak ada suara terdeteksi. Ketuk mikrofon untuk berbicara."
                            )
                        }
                    }
                    return@collect
                }
                if (signal == "OVERLAY_CANCEL" && state.isLiveCallActive) {
                    _stateFlow.update { it.copy(isLiveCallActive = false) }
                }
                if (state.isBusy) {
                    cancelActiveGoal("Perintah dihentikan oleh selaan suara (Barge-In).")
                }
            }
        }

        scope.launch {
            refreshTelemetryHistory()
        }
    }

    private fun syncVoiceCredentialsFromByok(settings: ByokSettings) {
        val voiceEngine = voiceHudPort as? VoiceAudioEngine ?: return
        voiceEngine.updateGeminiApiKeys(
            primaryKey = settings.effectiveGeminiApiKey,
            secondaryKey = settings.effectiveGeminiSecondaryApiKey
        )
        voiceEngine.updateElevenLabsConfig(
            com.teknologigawai.gepy.features.voice.ElevenLabsRuntimeConfig(
                enabled = settings.elevenLabsEnabled,
                apiKey = settings.effectiveElevenLabsApiKey,
                voiceId = settings.effectiveElevenLabsVoiceId,
                modelId = settings.effectiveElevenLabsModel,
                stability = settings.elevenLabsStability,
                similarityBoost = settings.elevenLabsSimilarityBoost,
                allowMidUtteranceSystemFallback = false
            )
        )
        voiceEngine.setLiveCallOutputScale(settings.liveCallVolumeScale)
    }

    /** Mengatur volume output suara Gemini Live Call (`0.0f..2.0f`) secara instan dan persisten. */
    fun setLiveCallVolumeScale(scale: Float) {
        val clamped = scale.coerceIn(0f, 2f)
        val updated = byokPreferencesManager.currentSettings.copy(liveCallVolumeScale = clamped)
        byokPreferencesManager.updateSettings(updated)
        (voiceHudPort as? VoiceAudioEngine)?.setLiveCallOutputScale(clamped)
        val pct = kotlin.math.round(clamped * 100f).toInt()
        val msg = if (pct <= 0) "Suara Gepy Live dibisukan (0%)." else "Volume suara Gepy Live diatur ke $pct%."
        _stateFlow.update {
            it.copy(
                byokSettings = updated,
                lastThought = msg
            )
        }
    }

    /** Menyusun cuplikan konteks kepribadian, nama panggilan, & status sensor saat ini (`0 ms`). */
    fun currentSpeechContext(): SovereignSpeechContext {
        val currentByok = _stateFlow.value.byokSettings
        val ownerName = userMemoryManager?.getUserDisplayName().orEmpty()
        val snap = wellnessEngine?.currentSnapshot
        val wellnessCtx = snap?.toPersonalWellnessContext(
            ownerName = ownerName,
            doomscrollLimitMinutes = currentByok.doomscrollLimitMinutes
        ) ?: PersonalWellnessContext(
            ownerName = ownerName,
            doomscrollLimitMinutes = currentByok.doomscrollLimitMinutes
        )
        return SovereignSpeechContext(
            configuredMode = currentByok.sovereignPersonaMode,
            ownerName = ownerName,
            wellness = wellnessCtx,
            customPersonaPrompt = currentByok.customPersonaPrompt
        )
    }

    /** Membangun direktif persona aktif untuk panggilan suara dua arah `Gemini 3.8 Live`. */
    fun buildCurrentLiveVoicePersonaDirective(): String {
        return SovereignPersonaEngine.buildLiveVoicePersonaDirective(currentSpeechContext())
    }

    /** Membangun direktif persona aktif untuk penalaran Chat & ReAct teks. */
    fun buildCurrentReActPersonaDirective(): String {
        return SovereignPersonaEngine.buildReActPersonaDirective(currentSpeechContext())
    }

    /**
     * Mencatat satu giliran percakapan (`user` atau `assistant`) ke penyangga sesi terpadu
     * dan menyinkronkannya secara diam-diam ke sesi `Gemini 3.8 Live` bila sedang terhubung.
     */
    fun recordChatTurn(
        role: String,
        text: String,
        channel: ConversationChannel = ConversationChannel.TEXT_CHAT
    ) {
        val clean = if (role == "assistant") {
            VoiceClauseChunker.stripAudioTagsForDisplay(text)
        } else {
            text.trim()
        }
        if (clean.isEmpty()) return
        val updated = conversationBuffer.appendTurn(role = role, text = clean, channel = channel)
        _stateFlow.update { it.copy(chatHistory = updated) }
        if (channel == ConversationChannel.TEXT_CHAT) {
            (voiceHudPort as? VoiceAudioEngine)?.syncActiveChatTurnToLiveSession(role, clean)
        }
    }

    private fun recordClosingAssistantTurnIfPending(text: String) {
        val lastTurn = conversationBuffer.snapshot().lastOrNull() ?: return
        if (lastTurn.role == "user") {
            recordChatTurn(role = "assistant", text = text, channel = lastTurn.channel)
        }
    }

    /** Mereset konteks obrolan sesi aktif (*Sesi Baru*) tanpa menghapus memori permanen Room. */
    fun clearConversationSession() {
        conversationBuffer.clear()
        _stateFlow.update {
            it.copy(
                fsmState = if (it.isLiveCallActive) it.fsmState else GepyFsmState.IDLE,
                chatHistory = emptyList(),
                processTimeline = emptyList(),
                goalStartedAtMs = 0L,
                goalElapsedMs = 0L,
                currentGoal = "",
                currentStep = 0,
                lastThought = "Sesi percakapan baru dimulai.",
                lastActionLabel = "-",
                errorMessage = null
            )
        }
    }

    /**
     * Menyampaikan intervensi proaktif dari `WellnessAndGuardianEngine` atau sapaan sensor ruangan
     * `EspCsiAmbientBridge` (termasuk opsi `Auto-Home Hard Lock` saat batas *doomscrolling* terlampaui).
     */
    suspend fun deliverGuardianIntervention(speechText: String, triggerAutoHome: Boolean = false) {
        val clean = speechText.trim()
        if (clean.isEmpty() || state.isExecutingGoal) return
        hapticManager.vibrateHitlWarning()
        if (triggerAutoHome) {
            actionPort.navigateHome()
        }
        _stateFlow.update {
            it.copy(
                lastThought = VoiceClauseChunker.stripAudioTagsForDisplay(clean),
                lastActionLabel = if (triggerAutoHome) "GUARDIAN_AUTO_HOME" else "GUARDIAN_NUDGE"
            )
        }
        voiceHudPort.finishAndSpeak(clean)
    }

    /**
     * Menangani notifikasi asinkron (callback webhook) dari Antigravity di laptop saat tugas berat
     * (BUILD_RELEASE, VERIFY_ALL, RUN_TESTS, CODE_TASK) selesai diproses.
     *
     * Dual-Mode Delivery:
     * 1. Jika Live Voice Call aktif: suntikkan interupsi turn Gemini Live (`turnComplete: true`) agar Samantha
     *    langsung mengumumkan hasil koding laptop secara alami di tengah percakapan.
     * 2. Jika siaga / layar mati: getar haptic sukses, tampilkan Dynamic Island Frosted Glass Whisper Card,
     *    dan bacakan ringkasan via Text-to-Speech lokal Android.
     */
    suspend fun deliverAntigravityNotification(response: AntigravityRpcResponse) {
        GepyLog.i("Orchestrator", "Menerima notifikasi selesai Antigravity: action=${response.action}, status=${response.status}")

        if (response.status == "SUCCESS") {
            hapticManager.vibrateSuccess()
        } else {
            hapticManager.vibrateError()
        }

        val announcement = response.summaryTts.ifEmpty {
            "Laporan dari laptop: tugas ${response.action} selesai dengan status ${response.status}."
        }

        val injected = if (voiceHudPort.isLiveCallActive) {
            // Anti-Collision: jika asisten sedang aktif melafalkan kalimat, tunggu sejenak (maks. 5 detik)
            // agar kalimatnya selesai secara alami sebelum mengumumkan hasil tugas latar belakang laptop.
            var waitedMs = 0L
            while (voiceHudPort.isPlaybackActive && waitedMs < 5_000L) {
                delay(200L)
                waitedMs += 200L
            }
            voiceHudPort.injectLiveAgentInterruption(announcement)
        } else {
            false
        }

        if (!injected) {
            val title = if (response.status == "SUCCESS") {
                "⚡ Antigravity • ${response.action} Selesai"
            } else {
                "⚠️ Antigravity • ${response.action} ${response.status}"
            }
            voiceHudPort.showProactiveWhisper(
                title = title,
                subtitle = announcement,
                actionLabel = "Lihat",
                goalPayload = "ANTIGRAVITY_${response.requestId}",
                durationMs = 8_000L
            )
            voiceHudPort.finishAndSpeak(announcement)
        }

        _stateFlow.update {
            it.copy(
                lastThought = "Antigravity menyelesaikan ${response.action}: ${response.status}.",
                lastActionLabel = "A2A_${response.action}"
            )
        }
    }

    /** Memuat ulang daftar riwayat telemetri terbaru dari `MemoryPort`. */
    suspend fun refreshTelemetryHistory() {
        val logs = memoryPort.getRecentTelemetryLogs(limit = 50)
        _stateFlow.update { it.copy(telemetryHistory = logs) }
    }

    /** Mengaktifkan atau menonaktifkan kapsul mengambang *Overlay HUD*. */
    suspend fun toggleOverlayHud() {
        if (state.overlayActive) {
            voiceHudPort.hideOverlay()
            _stateFlow.update { it.copy(overlayActive = false) }
        } else {
            voiceHudPort.showIdlePill()
            _stateFlow.update { it.copy(overlayActive = true) }
        }
    }

    /** Memulai pendengaran perintah suara dari aplikasi maupun kapsul *Overlay HUD*. */
    suspend fun triggerVoiceCommandListen() {
        val listenHint = VoiceClauseChunker.stripAudioTagsForDisplay(
            SovereignNaturalSpeech.orchestratorVoiceListenStarted(currentSpeechContext())
        )
        _stateFlow.update {
            it.copy(
                fsmState = GepyFsmState.LISTENING,
                lastThought = listenHint,
                overlayActive = true,
                errorMessage = null
            )
        }
        voiceHudPort.startListening()
    }

    /** Mengaktifkan atau mematikan Mode **Akses Penuh (*Zero-HITL*)**. */
    fun setFullAccessZeroHitl(enabled: Boolean) {
        val msg = VoiceClauseChunker.stripAudioTagsForDisplay(
            SovereignNaturalSpeech.orchestratorFullAccessChanged(currentSpeechContext(), enabled)
        )
        _stateFlow.update {
            it.copy(
                fullAccessZeroHitl = enabled,
                lastThought = msg
            )
        }
    }

    /** Mengubah karakter suara **Gemini 3.8 Live** (`Aoede`, `Kore`, `Puck`, `Charon`, `Fenrir`). */
    fun setLiveVoicePersona(voiceName: String) {
        val clean = voiceName.trim()
        if (clean.isEmpty()) return
        (voiceHudPort as? VoiceAudioEngine)?.setLiveVoiceName(clean)
        _stateFlow.update {
            it.copy(
                liveVoicePersona = clean,
                lastThought = "Suara Gepy Live diatur ke karakter $clean."
            )
        }
    }

    /** Memilih mesin STT utama (100% Native Android SpeechRecognizer). */
    fun setUseNativeStt(useNative: Boolean = true) {
        _stateFlow.update {
            it.copy(
                useNativeStt = useNative,
                lastThought = "Mesin suara diatur ke Native Android SpeechRecognizer (id-ID)."
            )
        }
    }

    /** Memperbarui konfigurasi kredensial *Bring Your Own Key (BYOK)* secara langsung di runtime. */
    fun updateByokSettings(newSettings: ByokSettings, startLiveCallAfterSave: Boolean = false) {
        byokPreferencesManager.updateSettings(newSettings)
        syncVoiceCredentialsFromByok(newSettings)
        val geminiStatus = if (newSettings.hasGeminiApiKey) {
            " • Gemini API Key Aktif (${newSettings.maskedGeminiApiKey})"
        } else {
            ""
        }
        _stateFlow.update {
            it.copy(
                byokSettings = newSettings,
                errorMessage = null,
                lastThought = "Pengaturan API Key & Penyedia LLM (${newSettings.preferredProvider.displayLabel})$geminiStatus berhasil disimpan."
            )
        }

        val voiceEngine = voiceHudPort as? VoiceAudioEngine
        val shouldAutoConnectLive = startLiveCallAfterSave ||
            (state.isLiveCallActive && newSettings.hasGeminiApiKey && voiceEngine?.isGeminiLiveConnected != true)

        if (shouldAutoConnectLive && voiceEngine != null) {
            scope.launch {
                if (state.isLiveCallActive) {
                    voiceEngine.stopLiveCallSession()
                    _stateFlow.update { it.copy(isLiveCallActive = false) }
                }
                toggleGeminiLiveCall()
            }
        }
    }

    /** Menerapkan preset cepat penyedia OpenAI-Compatible (OpenRouter, Groq, DeepSeek Direct, Together AI). */
    fun applyByokPreset(preset: ByokProviderPreset, apiKey: String) {
        byokPreferencesManager.applyCustomOpenAiPreset(preset, apiKey)
        val updated = byokPreferencesManager.currentSettings
        syncVoiceCredentialsFromByok(updated)
        _stateFlow.update {
            it.copy(
                byokSettings = updated,
                lastThought = "Preset BYOK ${preset.label} (${preset.recommendedModel}) diaktifkan."
            )
        }
    }

    /** Mengembalikan konfigurasi BYOK ke nilai bawaan `BuildConfig` (`local_secrets.json`). */
    fun resetByokSettings() {
        byokPreferencesManager.clearOverrides()
        val updated = byokPreferencesManager.currentSettings
        syncVoiceCredentialsFromByok(updated)
        _stateFlow.update {
            it.copy(
                byokSettings = updated,
                lastThought = "Pengaturan BYOK dikembalikan ke kredensial bawaan sistem."
            )
        }
    }

    /** Memulai atau menghentikan sesi panggilan dua arah **Gemini 3.8 Live Full-Duplex (Gepy Live)**. */
    suspend fun toggleGeminiLiveCall() {
        val voiceEngine = voiceHudPort as? VoiceAudioEngine
        if (voiceEngine == null) {
            _stateFlow.update {
                it.copy(
                    fsmState = GepyFsmState.ERROR,
                    errorMessage = "Mesin VoiceAudioEngine tidak tersedia untuk Gemini Live."
                )
            }
            return
        }

        // Pastikan kredensial BYOK terbaru tersinkronisasi ke klien Gemini Live sebelum menyambung
        syncVoiceCredentialsFromByok(state.byokSettings)

        if (state.isLiveCallActive) {
            voiceEngine.stopLiveCallSession("Sesi Gepy Live 3.8 dihentikan.")
            cancelActiveGoal(reason = "Panggilan live selesai.", speakCancellation = false)
            _stateFlow.update {
                it.copy(
                    isLiveCallActive = false,
                    fsmState = GepyFsmState.IDLE,
                    lastThought = "Sesi Gepy Live 3.8 ditutup."
                )
            }
            return
        }

        _stateFlow.update {
            it.copy(
                fsmState = GepyFsmState.LISTENING,
                currentGoal = if (it.isExecutingGoal) it.currentGoal else "",
                currentStep = if (it.isExecutingGoal) it.currentStep else 0,
                lastActionLabel = if (it.isExecutingGoal) it.lastActionLabel else "-",
                lastThought = "Menghubungkan sesi panggilan dua arah Gepy Live...",
                errorMessage = null
            )
        }

        val ok = voiceEngine.startLiveCallSession { toolCall ->
            handleLiveToolCall(toolCall)
        }
        if (ok) {
            hapticManager.vibrateActionTick()
            val geminiConnected = voiceEngine.isGeminiLiveConnected
            val thoughtText = if (geminiConnected) {
                "Gepy Live (Gemini 3.8 Full-Duplex • ${voiceEngine.activeGeminiLiveModel}) aktif. Silakan bicara bebas kapan saja."
            } else {
                "Gepy Live (Gemini 3.8 Full-Duplex • Hands-Free) aktif. Silakan bicara bebas kapan saja."
            }
            val warningError = if (!geminiConnected && state.byokSettings.hasGeminiApiKey) {
                voiceEngine.lastGeminiLiveError
            } else {
                null
            }
            _stateFlow.update {
                it.copy(
                    isLiveCallActive = true,
                    overlayActive = true,
                    fsmState = GepyFsmState.LISTENING,
                    lastThought = thoughtText,
                    errorMessage = warningError
                )
            }
        } else {
            val detailError = voiceEngine.lastGeminiLiveError
                ?: "Gagal terhubung ke Gemini 3.8 Live. Pastikan GEMINI_API_KEY aktif di Pengaturan BYOK dan izin Mikrofon diberikan."
            _stateFlow.update {
                it.copy(
                    isLiveCallActive = false,
                    fsmState = GepyFsmState.ERROR,
                    errorMessage = detailError
                )
            }
        }
    }

    /**
     * Menangani pemanggilan fungsi (*Function Calling / Tool Calling*) dari sesi **Gemini 3.8 Live**:
     * - `read_current_screen`: membaca konteks pohon UI layar saat ini secara instan
     * - `execute_android_goal`: mendelegasikan tujuan UI multi-langkah ke loop ReAct di latar belakang
     * - `cancel_android_goal`: membatalkan eksekusi tujuan yang sedang berjalan
     * - `get_device_and_time_status`, `search_phone_contacts`, `get_recent_notifications`,
     *   `get_current_location`, `set_system_alarm`: dieksekusi langsung oleh [AndroidSystemToolsBridge] (`< 5 ms`).
     */
    suspend fun handleLiveToolCall(toolCall: GeminiLiveToolCall): Map<String, Any?> =
        liveCallToolDispatcher.handleLiveToolCall(toolCall)

    /** Memicu dan membacakan ringkasan harian terverifikasi secara langsung sesuai permintaan pengguna. */
    suspend fun triggerOnDemandBriefing(): String {
        val engine = dailyBriefingEngine ?: return "Mesin briefing harian belum tersedia."
        val briefing = engine.buildOnDemandBriefing()
        _stateFlow.update {
            it.copy(
                lastThought = VoiceClauseChunker.stripAudioTagsForDisplay(briefing.spokenText),
                lastActionLabel = "DAILY_BRIEFING (${briefing.kind.name})"
            )
        }
        if (wellnessEngine?.currentSnapshot?.isGuestPrivacyShieldActive != true) {
            voiceHudPort.finishAndSpeak(briefing.spokenText)
        }
        return briefing.spokenText
    }

    /** Mengevaluasi sapaan pagi otomatis 1x/hari saat pengguna mulai berinteraksi dengan HP di pagi hari. */
    suspend fun triggerMorningWakeupBriefingIfDue(): String? {
        if (state.isExecutingGoal || voiceHudPort.isLiveCallActive) return null
        val fgPkg = try {
            perceptionPort.currentForegroundPackage
        } catch (_: Throwable) {
            ""
        }
        if (coPilotSentinel.isDistractionActive(fgPkg)) return null

        val engine = dailyBriefingEngine ?: return null
        val briefing = engine.tryTriggerMorningWakeupBriefing() ?: return null
        _stateFlow.update {
            it.copy(
                lastThought = VoiceClauseChunker.stripAudioTagsForDisplay(briefing.spokenText),
                lastActionLabel = "MORNING_BRIEFING"
            )
        }
        voiceHudPort.finishAndSpeak(briefing.spokenText)
        return briefing.spokenText
    }

    /**
     * Mengevaluasi **Evening Reflection** otomatis (`20:30 - 23:30`, 1x/malam) khusus mode
     * [ProactivityLevel.AUTONOMOUS] dengan tetap menghormati Privacy Shield & fokus layar aktif.
     */
    suspend fun triggerEveningReflectionIfDue(): String? {
        if (state.isExecutingGoal || voiceHudPort.isLiveCallActive) return null
        val fgPkg = try {
            perceptionPort.currentForegroundPackage
        } catch (_: Throwable) {
            ""
        }
        if (coPilotSentinel.isDistractionActive(fgPkg)) return null

        val engine = dailyBriefingEngine ?: return null
        val briefing = engine.tryTriggerEveningReflection() ?: return null
        _stateFlow.update {
            it.copy(
                lastThought = VoiceClauseChunker.stripAudioTagsForDisplay(briefing.spokenText),
                lastActionLabel = "EVENING_REFLECTION"
            )
        }
        voiceHudPort.finishAndSpeak(briefing.spokenText)
        return briefing.spokenText
    }

    /**
     * Evaluasi ringan berkala (`0` token LLM, bebas WakeLock tambahan) untuk memicu
     * Morning Briefing (`CONTEXT_COPILOT`/`AUTONOMOUS`), Evening Reflection (`AUTONOMOUS`),
     * serta Nightly Battery Guard secara konsisten sesuai [ProactivityLevel].
     */
    suspend fun evaluateProactivePulseIfDue(nowWallMs: Long = System.currentTimeMillis()) {
        val currentLevel = byokPreferencesManager.currentSettings.proactivityLevel
        if (currentLevel == ProactivityLevel.ZEN_PASSIVE) return
        if (state.isExecutingGoal || voiceHudPort.isLiveCallActive) return
        if (lastProactivePulseWallMs != 0L && (nowWallMs - lastProactivePulseWallMs) < 60_000L) return
        lastProactivePulseWallMs = nowWallMs

        triggerMorningWakeupBriefingIfDue()
        if (currentLevel == ProactivityLevel.AUTONOMOUS) {
            triggerEveningReflectionIfDue()
        }

        val fgPkg = try {
            perceptionPort.currentForegroundPackage
        } catch (_: Throwable) {
            ""
        }
        val devStatus = systemToolsBridge.getDeviceAndTimeStatus()
        coPilotSentinel.evaluateBatteryState(
            batteryPercent = devStatus.batteryPercent,
            isCharging = devStatus.isCharging,
            currentForegroundPackage = fgPkg
        )
    }

    /** Membatalkan tujuan otomatisasi yang sedang berjalan secara instan (*Kill-Switch*). */
    fun cancelActiveGoal(
        reason: String = SovereignNaturalSpeech.orchestratorTaskCancelled(currentSpeechContext()),
        speakCancellation: Boolean = true
    ) {
        synchronized(goalLifecycleLock) {
            activeGoalEpoch.incrementAndGet()
            abortRequested = true
            unifiedSupervisor.abortGoal(reason)
            activeGoalJob?.cancel()
            activeGoalJob = null
            memoryPort.discardStagedTransitions()
            recordClosingAssistantTurnIfPending(reason)
            _stateFlow.update {
                it.copy(
                    fsmState = GepyFsmState.IDLE,
                    lastThought = VoiceClauseChunker.stripAudioTagsForDisplay(reason),
                    lastActionLabel = "ABORTED"
                )
            }
        }
        if (speakCancellation) {
            scope.launch {
                voiceHudPort.finishAndSpeak(reason)
            }
        }
    }

    /** Membatalkan memori Playbook dari sesi tertentu (umpan balik 👎 pengguna). */
    suspend fun rollbackSession(sessionId: String) {
        val cleanSessionId = sessionId.trim()
        if (cleanSessionId.isEmpty()) return
        memoryPort.rollbackSessionMemory(cleanSessionId)
        _stateFlow.update {
            it.copy(
                lastThought = "Memori Playbook dari sesi ini telah dibatalkan & dinonaktifkan."
            )
        }
    }

    /**
     * Menjalankan siklus lengkap **`Observe -> Think -> Act -> Verify`** untuk menyelesaikan
     * instruksi pengguna [rawVoiceGoal].
     */
    suspend fun executeVoiceGoal(rawVoiceGoal: String) {
        val directEpoch: Long
        synchronized(goalLifecycleLock) {
            abortRequested = false
            directEpoch = activeGoalEpoch.incrementAndGet()
        }
        executeVoiceGoalWithEpoch(rawVoiceGoal, expectedEpoch = directEpoch)
    }


    private suspend fun executeVoiceGoalWithEpoch(
        rawVoiceGoal: String,
        expectedEpoch: Long
    ) = reActRunner.executeVoiceGoalWithEpoch(rawVoiceGoal, expectedEpoch)

    internal fun tryBuildPlaybookDecision(
        targetResourceId: String,
        actionType: String,
        successCount: Int,
        elements: List<PrunedUiElement>
    ): ReActActionModel? = playbookCoordinator.tryBuildPlaybookDecision(
        targetResourceId = targetResourceId,
        actionType = actionType,
        successCount = successCount,
        elements = elements
    )

    /**
     * Pencocokan fungsional multi-atribut (*AutoDroid & AppAgent v2 Functional Selector*,
     * `arXiv:2308.15272v4`, `arXiv:2408.11824v4`) apabila `viewResourceId` aplikasi target
     * berubah setelah pembaruan versi atau migrasi ke Jetpack Compose.
     */
    internal fun findSelfHealingPlaybookTarget(
        savedResourceId: String,
        elements: List<PrunedUiElement>,
        requireEditable: Boolean = false
    ): PrunedUiElement? = playbookCoordinator.findSelfHealingPlaybookTarget(
        savedResourceId = savedResourceId,
        elements = elements,
        requireEditable = requireEditable
    )
}
