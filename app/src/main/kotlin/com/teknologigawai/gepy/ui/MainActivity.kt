package com.teknologigawai.gepy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.teknologigawai.gepy.BuildConfig
import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.diagnostics.GepyStateMirrorServer
import com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.skills.AndroidSystemToolSkill
import com.teknologigawai.gepy.core.skills.CalendarSchedulerSkill
import com.teknologigawai.gepy.core.skills.InstagramSocialSkill
import com.teknologigawai.gepy.core.skills.LaptopCompanionSkill
import com.teknologigawai.gepy.core.skills.PersonalMemoryFastPathSkill
import com.teknologigawai.gepy.core.skills.ScreenReaderSummarySkill
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.skills.SpotifyFastPathSkill
import com.teknologigawai.gepy.core.skills.UniversalAppNavigationSkill
import com.teknologigawai.gepy.core.skills.WebSearchFastPathSkill
import com.teknologigawai.gepy.core.skills.WhatsAppMessagingSkill
import com.teknologigawai.gepy.core.system.AndroidOsStateVerifier
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.core.web.WebSearchAndReaderBridge
import com.teknologigawai.gepy.core.wellness.DailyBriefingEngine
import com.teknologigawai.gepy.core.wellness.WellnessAndGuardianEngine
import com.teknologigawai.gepy.data.database.GepyDatabase
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.action.AccessibilityActionAdapter
import com.teknologigawai.gepy.features.brain.SingleAgentReActAdapter
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestrator
import com.teknologigawai.gepy.features.overlay.OverlayHudWindowManager
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.voice.ElevenLabsTtsClient
import com.teknologigawai.gepy.features.voice.VoiceAudioCacheManager
import com.teknologigawai.gepy.features.voice.VoiceAudioEngine
import com.teknologigawai.gepy.service.GepyAccessibilityService
import com.teknologigawai.gepy.service.GepyVoiceForegroundService
import com.teknologigawai.gepy.service.NightlyConsolidationJobService
import com.teknologigawai.gepy.ui.theme.CyberpunkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Kontainer runtime tunggal (*Single-Process Zero-Isolate Container*) agar instansi
 * `GepyOrchestrator`, `VoiceAudioEngine`, dan `OverlayHudWindowManager` bertahan
 * lintas rotasi layar atau ketika pengguna berpindah ke aplikasi target (WhatsApp/Kalender).
 */
@SuppressLint("StaticFieldLeak")
object GepyRuntimeContainer {
    @Volatile
    private var orchestratorInstance: GepyOrchestrator? = null

    @Volatile
    private var overlayWindowManagerInstance: OverlayHudWindowManager? = null

    @Volatile
    private var authManagerInstance: com.teknologigawai.gepy.core.auth.GoogleAuthManager? = null

    @Volatile
    private var gatewayClientInstance: com.teknologigawai.gepy.core.network.CloudflareGatewayClient? = null

    @Volatile
    private var userMemoryManagerInstance: com.teknologigawai.gepy.core.memory.UserMemoryManager? = null

    @Volatile
    private var wellnessEngineInstance: WellnessAndGuardianEngine? = null

    @Volatile
    private var espCsiBridgeInstance: EspCsiAmbientBridge? = null

    @Volatile
    private var stateMirrorServerInstance: GepyStateMirrorServer? = null

    @Volatile
    private var elevenLabsClientInstance: ElevenLabsTtsClient? = null

    @Volatile
    private var voiceCacheManagerInstance: VoiceAudioCacheManager? = null

    @Volatile
    private var byokManagerInstance: ByokPreferencesManager? = null

    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun peekOrchestrator(): GepyOrchestrator? = orchestratorInstance

    fun peekEspCsiBridge(): EspCsiAmbientBridge? = espCsiBridgeInstance

    @Synchronized
    fun getOrCreateByokPreferencesManager(context: Context): ByokPreferencesManager {
        byokManagerInstance?.let { return it }
        val created = ByokPreferencesManager(context.applicationContext)
        byokManagerInstance = created
        return created
    }

    fun getOrCreateElevenLabsClient(): ElevenLabsTtsClient {
        elevenLabsClientInstance?.let { return it }
        val created = ElevenLabsTtsClient()
        elevenLabsClientInstance = created
        return created
    }

    fun getOrCreateVoiceCacheManager(context: Context): VoiceAudioCacheManager {
        voiceCacheManagerInstance?.let { return it }
        val appCtx = context.applicationContext
        val byok = getOrCreateByokPreferencesManager(appCtx)
        val created = VoiceAudioCacheManager(
            context = appCtx,
            maxCacheMbProvider = { byok.currentSettings.elevenLabsCacheMaxMb }
        )
        voiceCacheManagerInstance = created
        return created
    }

    @Synchronized
    fun getOrCreateAuthManager(context: Context): com.teknologigawai.gepy.core.auth.GoogleAuthManager {
        authManagerInstance?.let { return it }
        val created = com.teknologigawai.gepy.core.auth.GoogleAuthManager(context.applicationContext)
        authManagerInstance = created
        return created
    }

    @Synchronized
    fun getOrCreateGatewayClient(): com.teknologigawai.gepy.core.network.CloudflareGatewayClient {
        gatewayClientInstance?.let { return it }
        val created = com.teknologigawai.gepy.core.network.CloudflareGatewayClient()
        gatewayClientInstance = created
        return created
    }

    @Synchronized
    fun getOrCreateUserMemoryManager(context: Context): com.teknologigawai.gepy.core.memory.UserMemoryManager {
        userMemoryManagerInstance?.let { return it }
        val appCtx = context.applicationContext
        val authManager = getOrCreateAuthManager(appCtx)
        val gatewayClient = getOrCreateGatewayClient()
        val byok = getOrCreateByokPreferencesManager(appCtx)
        val gepyDao = try {
            GepyDatabase.getInstance(appCtx).gepyDao()
        } catch (_: Throwable) {
            null
        }
        val created = com.teknologigawai.gepy.core.memory.UserMemoryManager(
            context = appCtx,
            authManager = authManager,
            gatewayClient = gatewayClient,
            gepyDao = gepyDao,
            embeddingClient = com.teknologigawai.gepy.core.memory.GeminiEmbeddingClient(),
            embeddingKeyPoolProvider = { byok.currentSettings.geminiEmbeddingKeyPool },
            chatKeyPoolProvider = { byok.currentSettings.geminiChatKeyPool },
            embeddingModelProvider = { byok.currentSettings.effectiveGeminiEmbeddingModel }
        )
        userMemoryManagerInstance = created
        return created
    }

    @Synchronized
    fun getOrCreateOrchestrator(context: Context): GepyOrchestrator {
        orchestratorInstance?.let { return it }

        val appCtx = context.applicationContext
        val database = GepyDatabase.getInstance(appCtx)
        val memoryRepo = SqliteMemoryRepository(dao = database.gepyDao())
        val perceptionPruner = AccessibilityTreePruner(selfPackageName = EnvConfig.SELF_PACKAGE_NAME)
        val byokManager = getOrCreateByokPreferencesManager(appCtx)
        val actionAdapter = AccessibilityActionAdapter(
            perceptionPort = perceptionPruner,
            sovereignDirectExecutionProvider = { byokManager.currentSettings.sovereignPersonalMode }
        )
        val appLauncher = AppIntentLauncher(context = appCtx, aliasCache = memoryRepo)
        val brainAdapter = SingleAgentReActAdapter(byokPreferencesManager = byokManager)
        val elevenLabsClient = getOrCreateElevenLabsClient()
        val voiceCacheManager = getOrCreateVoiceCacheManager(appCtx)
        val voiceEngine = VoiceAudioEngine(
            context = appCtx,
            elevenLabsClient = elevenLabsClient,
            voiceCacheManager = voiceCacheManager
        )
        val authManager = getOrCreateAuthManager(appCtx)
        val userMemoryManager = getOrCreateUserMemoryManager(appCtx)
        voiceEngine.userMemoryManager = userMemoryManager
        val lazySpeechCtxProvider: () -> SovereignSpeechContext = {
            orchestratorInstance?.currentSpeechContext() ?: SovereignSpeechContext(
                configuredMode = byokManager.currentSettings.sovereignPersonaMode,
                ownerName = userMemoryManager.getUserDisplayName(),
                wellness = wellnessEngineInstance?.currentSnapshot?.toPersonalWellnessContext(
                    ownerName = userMemoryManager.getUserDisplayName(),
                    doomscrollLimitMinutes = byokManager.currentSettings.doomscrollLimitMinutes
                ) ?: PersonalWellnessContext(
                    ownerName = userMemoryManager.getUserDisplayName(),
                    doomscrollLimitMinutes = byokManager.currentSettings.doomscrollLimitMinutes
                )
            )
        }
        appLauncher.speechContextProvider = lazySpeechCtxProvider
        voiceEngine.speechContextProvider = lazySpeechCtxProvider
        val systemToolsBridge = AndroidSystemToolsBridge(context = appCtx).apply {
            speechContextProvider = lazySpeechCtxProvider
        }
        val webSearchBridge = WebSearchAndReaderBridge(
            sessionTokenProvider = { authManager.currentUser.value?.sessionToken }
        )
        val deepLinkRouter = IntentDeepLinkRouter(
            context = appCtx,
            systemToolsBridge = systemToolsBridge,
            speechContextProvider = lazySpeechCtxProvider
        )
        val hapticManager = HapticFeedbackManager(context = appCtx)

        val skillRegistry = SkillRegistry(
            skills = listOf(
                AndroidSystemToolSkill(
                    systemBridge = systemToolsBridge,
                    speechContextProvider = lazySpeechCtxProvider,
                    preferredWakeTimeProvider = {
                        userMemoryManager.getAllTemporalFactsSnapshot()
                            .firstOrNull { fact ->
                                fact.subject.equals("routine_wake", ignoreCase = true) ||
                                    (fact.category.equals("routine", ignoreCase = true) &&
                                        fact.factText.contains("bangun", ignoreCase = true))
                            }
                            ?.let { fact -> AndroidSystemToolSkill.extractTimeHourMinute(fact.factText) }
                    },
                    sovereignDirectExecutionProvider = { byokManager.currentSettings.sovereignPersonalMode }
                ),
                LaptopCompanionSkill(
                    hostProvider = { byokManager.currentSettings.laptopTailscaleHost },
                    isEnabledProvider = { byokManager.currentSettings.laptopCompanionEnabled },
                    speechContextProvider = lazySpeechCtxProvider
                ),
                SpotifyFastPathSkill(
                    systemBridge = systemToolsBridge,
                    speechContextProvider = lazySpeechCtxProvider
                ),
                PersonalMemoryFastPathSkill(
                    userMemoryManager = userMemoryManager,
                    speechContextProvider = lazySpeechCtxProvider
                ),
                WebSearchFastPathSkill(),
                ScreenReaderSummarySkill(speechContextProvider = lazySpeechCtxProvider),
                WhatsAppMessagingSkill(
                    sovereignDirectExecutionProvider = { byokManager.currentSettings.sovereignPersonalMode },
                    speechContextProvider = lazySpeechCtxProvider
                ),
                InstagramSocialSkill(speechContextProvider = lazySpeechCtxProvider),
                CalendarSchedulerSkill(sovereignDirectExecutionProvider = { byokManager.currentSettings.sovereignPersonalMode }),
                UniversalAppNavigationSkill()
            )
        )

        overlayWindowManagerInstance = OverlayHudWindowManager(
            context = appCtx,
            voiceAudioEngine = voiceEngine
        )

        val wellnessEngine = WellnessAndGuardianEngine(
            context = appCtx,
            dao = database.gepyDao(),
            byokPreferencesManager = byokManager,
            ownerNameProvider = { userMemoryManager.getUserDisplayName() },
            isAgentExecutingProvider = { orchestratorInstance?.state?.isExecutingGoal == true },
            onGuardianIntervention = { speech, autoHome ->
                orchestratorInstance?.deliverGuardianIntervention(speech, autoHome)
            },
            onProactiveLifecycleTick = {
                orchestratorInstance?.evaluateProactivePulseIfDue()
            }
        )
        wellnessEngineInstance = wellnessEngine

        val dailyBriefingEngine = DailyBriefingEngine(
            context = appCtx,
            userMemoryManager = userMemoryManager,
            wellnessEngine = wellnessEngine,
            systemToolsBridge = systemToolsBridge,
            speechContextProvider = lazySpeechCtxProvider,
            proactivityLevelProvider = { byokManager.currentSettings.proactivityLevel }
        )

        val created = GepyOrchestrator(
            perceptionPort = perceptionPruner,
            actionPort = actionAdapter,
            memoryPort = memoryRepo,
            brainPort = brainAdapter,
            voiceHudPort = voiceEngine,
            appLauncher = appLauncher,
            skillRegistry = skillRegistry,
            systemToolsBridge = systemToolsBridge,
            webSearchBridge = webSearchBridge,
            deepLinkRouter = deepLinkRouter,
            hapticManager = hapticManager,
            byokPreferencesManager = byokManager,
            userMemoryManager = userMemoryManager,
            wellnessEngine = wellnessEngine,
            dailyBriefingEngine = dailyBriefingEngine,
            osStateVerifier = AndroidOsStateVerifier(context = appCtx)
        )
        created.coPilotSentinel.attachContext(appCtx)

        val espBridge = EspCsiAmbientBridge(
            wellnessEngine = wellnessEngine,
            onAirGestureWake = {
                created.triggerVoiceCommandListen()
            },
            onOwnerPresenceGreeting = { room ->
                val owner = userMemoryManager.getUserDisplayName().ifBlank { "Bos" }
                val steps = wellnessEngine.currentSnapshot.todaySteps
                val greeting = SovereignNaturalSpeech.csiOwnerGreeting(
                    ctx = lazySpeechCtxProvider(),
                    fallbackText = "Selamat datang di $room, $owner. Langkah hari ini $steps langkah. Aku siap bantu!"
                )
                created.deliverGuardianIntervention(
                    speechText = greeting,
                    triggerAutoHome = false
                )
            },
            proactivityLevelProvider = { byokManager.currentSettings.proactivityLevel }
        )
        espCsiBridgeInstance = espBridge

        // Aktifkan pengawas Anti-Doomscrolling + Sensor Langkah Perangkat Keras
        wellnessEngine.startMonitoring(GepyAccessibilityService.foregroundPackageFlow)

        // Daftarkan jadwal konsolidasi memori tidur malam (Charging + Idle + Wi-Fi)
        NightlyConsolidationJobService.scheduleNightlyJob(appCtx)

        // Hubungkan webhook asinkron Antigravity A2A ke Orkestrator
        espBridge.onAntigravityCallbackListener = { response ->
            containerScope.launch {
                created.deliverAntigravityNotification(response)
            }
        }

        // Kelola siklus hidup Peladen Lokal ESP32-S3 Wi-Fi CSI & Antigravity A2A Callback sesuai sakelar pengguna
        containerScope.launch(Dispatchers.IO) {
            byokManager.settingsFlow.collect { byok ->
                val shouldRun = byok.espCsiBridgeEnabled || byok.laptopCompanionEnabled
                if (shouldRun) {
                    val port = if (byok.espCsiBridgeEnabled) byok.espCsiPort else 8765
                    if (!espBridge.isRunningFlow.value || espBridge.boundPort != port) {
                        espBridge.startServer(port)
                    }
                } else if (espBridge.isRunningFlow.value) {
                    espBridge.stopServer()
                }
            }
        }

        // Hubungkan Haptic & Pemicu Live Call dari Floating Overlay HUD (Long-Press Gesture)
        voiceEngine.hapticManager = hapticManager
        voiceEngine.onLiveCallToggleSuspendRequested = {
            created.toggleGeminiLiveCall()
        }
        voiceEngine.onLiveCallToggleRequested = {
            containerScope.launch {
                created.toggleGeminiLiveCall()
            }
        }

        // Hubungkan pintasan tombol Aksesibilitas Android (atau tahan kedua tombol Volume) ke Gepy
        GepyAccessibilityService.onAccessibilityShortcutTriggered = {
            containerScope.launch {
                created.triggerVoiceCommandListen()
            }
        }

        // Self-Heal Watchdog: pastikan Floating Glass Bubble tetap tampil meski MainActivity ditutup dari Recents
        GepyAccessibilityService.onEnsureRuntimeAndOverlayAlive = {
            overlayWindowManagerInstance?.ensureAttachedIfVisible()
        }

        // Kelola siklus hidup On-Demand Foreground Service secara reaktif (0 MB saat idle)
        containerScope.launch {
            created.stateFlow.collect { st ->
                // Hanya aktifkan Foreground Service saat Live Call berlangsung atau sedang mengeksekusi tujuan multi-langkah
                // Jangan picu pada micro-flicker transien (seperti one-shot STT tap) untuk mencegah race condition start/stop
                if (st.isLiveCallActive || st.isExecutingGoal) {
                    val title = if (st.isLiveCallActive) {
                        "Gepy Live 3.8 Aktif (${st.liveVoicePersona})"
                    } else {
                        "Gepy • ${st.fsmState.name}"
                    }
                    GepyVoiceForegroundService.startOrUpdate(
                        context = appCtx,
                        title = title,
                        subtitle = st.lastThought
                    )
                } else {
                    GepyVoiceForegroundService.stopIfRunning(appCtx)
                }
            }
        }

        if (BuildConfig.DEBUG && stateMirrorServerInstance == null) {
            stateMirrorServerInstance = GepyStateMirrorServer(
                stateProvider = { created.stateFlow.value }
            ).also { it.start(GepyStateMirrorServer.DEFAULT_PORT) }
        }

        orchestratorInstance = created
        return created
    }
}

/**
 * Activity utama **Gepy Native** (`com.teknologigawai.gepy`).
 *
 * Menghubungkan [CyberpunkTheme], [PermissionWizardScreen], dan [DashboardScreen]
 * di dalam satu proses ART (*Zero-Isolate*).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GepyLog.i("MainActivity", "Gepy Native v${EnvConfig.appVersionName} initialized (${EnvConfig.SELF_PACKAGE_NAME})")

        val orchestrator = GepyRuntimeContainer.getOrCreateOrchestrator(applicationContext)
        val authManager = GepyRuntimeContainer.getOrCreateAuthManager(applicationContext)
        val gatewayClient = GepyRuntimeContainer.getOrCreateGatewayClient()
        val memoryManager = GepyRuntimeContainer.getOrCreateUserMemoryManager(applicationContext)
        val initialPermissions = checkPermissionChecklist(applicationContext)
        val byokManager = orchestrator.byokPreferencesManager

        setContent {
            val state by orchestrator.stateFlow.collectAsState()

            LaunchedEffect(state.isLiveCallActive) {
                volumeControlStream = if (state.isLiveCallActive) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.USE_DEFAULT_STREAM_TYPE
                }
            }
            DisposableEffect(Unit) {
                onDispose {
                    volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
                }
            }

            @Composable
            fun MainAppNavigator() {
                val isSovereignPersonal = state.byokSettings.sovereignPersonalMode
                var hasAcceptedDisclosure by rememberSaveable {
                    mutableStateOf(isSovereignPersonal || byokManager.hasAcceptedDisclosure)
                }
                var showAuthScreen by rememberSaveable {
                    mutableStateOf(
                        shouldShowOpeningAuthScreen(
                            isSignedIn = authManager.isSignedIn,
                            hasSkippedOpeningAuth = memoryManager.hasSkippedOpeningAuth
                        )
                    )
                }
                var showWizard by rememberSaveable {
                    mutableStateOf(!initialPermissions.allRequiredReady)
                }

                if (!isSovereignPersonal && !hasAcceptedDisclosure) {
                    ProminentDisclosureScreen(
                        onAccept = {
                            byokManager.setDisclosureAccepted(true)
                            hasAcceptedDisclosure = true
                        },
                        onDecline = {
                            finish()
                        }
                    )
                } else if (showAuthScreen) {
                    WelcomeAuthScreen(
                        authManager = authManager,
                        gatewayClient = gatewayClient,
                        memoryManager = memoryManager,
                        voiceHudPort = orchestrator.voiceHudPort,
                        hapticManager = orchestrator.hapticManager,
                        onAuthCompleted = { showAuthScreen = false },
                        onSkip = { showAuthScreen = false }
                    )
                } else if (showWizard) {
                    PermissionWizardScreen(
                        onCompleted = { showWizard = false }
                    )
                } else {
                    val proactiveScope = rememberCoroutineScope()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    LaunchedEffect(Unit) {
                        memoryManager.initializeTemporalStore()
                        orchestrator.evaluateProactivePulseIfDue()
                    }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                proactiveScope.launch {
                                    orchestrator.evaluateProactivePulseIfDue()
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }
                    DashboardScreen(
                        orchestrator = orchestrator,
                        authManager = authManager,
                        gatewayClient = gatewayClient,
                        espCsiBridge = GepyRuntimeContainer.peekEspCsiBridge(),
                        onOpenPermissionWizard = { showWizard = true },
                        onSignedOut = { showAuthScreen = true }
                    )
                }
            }

            CyberpunkTheme {
                MainAppNavigator()
            }
        }
    }
}

internal fun shouldShowOpeningAuthScreen(
    isSignedIn: Boolean,
    hasSkippedOpeningAuth: Boolean
): Boolean = !isSignedIn && !hasSkippedOpeningAuth
