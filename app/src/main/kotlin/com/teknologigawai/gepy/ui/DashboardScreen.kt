package com.teknologigawai.gepy.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import com.teknologigawai.gepy.core.accessibility.GepyFeedbackPattern
import com.teknologigawai.gepy.core.auth.GoogleAuthManager
import com.teknologigawai.gepy.core.auth.GoogleAuthResult
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.memory.backup.VaultImportResult
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.network.CloudflareGatewayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge
import com.teknologigawai.gepy.core.persona.GuardianTriggerReason
import com.teknologigawai.gepy.core.persona.SovereignPersonaEngine
import com.teknologigawai.gepy.core.wellness.WellnessGuardianSnapshot
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestrator
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestratorState
import com.teknologigawai.gepy.features.voice.ElevenLabsTtsClient
import com.teknologigawai.gepy.features.voice.VoiceAudioCacheManager
import com.teknologigawai.gepy.features.voice.VoiceAudioEngine
import androidx.compose.ui.tooling.preview.Preview
import com.teknologigawai.gepy.ui.theme.CyberpunkTheme
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Layar Utama **Gepy v3.0 Native** dengan 3 Tab Navigasi Bawah:
 * 1. **Asisten**: Pusat kendali perintah suara/teks, status siklus ReAct (`Observe -> Think -> Act -> Verify`),
 *    panggilan dua arah **Gemini 3.8 Live (Gepy Live)**, dan *Scratchpad Memory*.
 * 2. **Telemetri**: Metrik kompresi pohon XML (`> 80%`), latensi penalaran, status *Playbook Graph*,
 *    dan riwayat log SQLite (`gepy_core.db`).
 * 3. **Pengaturan**: Konfigurasi **BYOK Multi-Provider** (Fireworks, Gemini, OpenRouter, Groq, DeepSeek Direct, Together AI),
 *    sakelar *Zero-HITL*, karakter suara Gemini Live, pilihan mesin STT, dan status izin sistem Android.
 */
@Composable
fun DashboardScreen(
    orchestrator: GepyOrchestrator,
    authManager: GoogleAuthManager = GepyRuntimeContainer.getOrCreateAuthManager(LocalContext.current),
    gatewayClient: CloudflareGatewayClient = GepyRuntimeContainer.getOrCreateGatewayClient(),
    userMemoryManager: UserMemoryManager = GepyRuntimeContainer.getOrCreateUserMemoryManager(LocalContext.current),
    espCsiBridge: EspCsiAmbientBridge? = GepyRuntimeContainer.peekEspCsiBridge(),
    elevenLabsClient: ElevenLabsTtsClient = GepyRuntimeContainer.getOrCreateElevenLabsClient(),
    voiceCacheManager: VoiceAudioCacheManager = GepyRuntimeContainer.getOrCreateVoiceCacheManager(LocalContext.current),
    onOpenPermissionWizard: () -> Unit,
    onSignedOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val state by orchestrator.stateFlow.collectAsState()
    val fallbackWellnessFlow = remember { MutableStateFlow(WellnessGuardianSnapshot()) }
    val wellnessSnapshot by (orchestrator.wellnessEngine?.stateFlow ?: fallbackWellnessFlow).collectAsState()
    val fallbackCsiRunningFlow = remember { MutableStateFlow(false) }
    val isCsiServerRunning by (espCsiBridge?.isRunningFlow ?: fallbackCsiRunningFlow).collectAsState()
    val currentUser by authManager.currentUser.collectAsState()
    var isSigningIn by remember { mutableStateOf(false) }
    var showDeleteMemoryDialog by rememberSaveable { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var activeSection by rememberSaveable { mutableStateOf(GepyMainSection.ASSISTANT) }
    var promptText by rememberSaveable { mutableStateOf("") }
    var permissionStatus by remember { mutableStateOf(checkPermissionChecklist(context)) }
    var showByokDialog by rememberSaveable { mutableStateOf(false) }
    var showPlaybookDialog by rememberSaveable { mutableStateOf(false) }
    var showTemporalMemoryDialog by rememberSaveable { mutableStateOf(false) }
    var showVoiceDatasetDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoteIotSheet by rememberSaveable { mutableStateOf(false) }
    var showProactiveGuardianSheet by rememberSaveable { mutableStateOf(false) }
    var showSkillsCatalogSheet by rememberSaveable { mutableStateOf(false) }
    val voiceCacheStats by voiceCacheManager.statsFlow.collectAsState()

    BackHandler(enabled = drawerState.isOpen || activeSection != GepyMainSection.ASSISTANT) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            activeSection = GepyMainSection.ASSISTANT
        }
    }

    fun refreshPermissions() {
        permissionStatus = checkPermissionChecklist(context)
        voiceCacheManager.getStats()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var pendingLiveCallAfterPerm by remember { mutableStateOf(false) }

    val runtimeMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        orchestrator.wellnessEngine?.registerHardwareStepSensorIfPermitted()
        if (pendingLiveCallAfterPerm && checkPermissionChecklist(context).microphoneGranted) {
            pendingLiveCallAfterPerm = false
            scope.launch {
                orchestrator.toggleGeminiLiveCall()
            }
        } else {
            pendingLiveCallAfterPerm = false
        }
    }

    val exportVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        userMemoryManager.exportVaultToStream(os)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Memori berhasil dicadangkan ke Google Drive / Berkas!", Toast.LENGTH_LONG).show()
                        orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Gagal mencadangkan: ${t.localizedMessage ?: t.message}", Toast.LENGTH_LONG).show()
                        orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
                    }
                }
            }
        }
    }

    val importVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val result = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        userMemoryManager.importVaultFromStream(inputStream)
                    }
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is VaultImportResult.Success -> {
                                Toast.makeText(
                                    context,
                                    "Pemulihan berhasil: ${result.importedCount} memori baru, ${result.updatedCount} diperbarui.",
                                    Toast.LENGTH_LONG
                                ).show()
                                orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                orchestrator.voiceHudPort.finishAndSpeak("Memori personal berhasil dipulihkan.")
                            }
                            is VaultImportResult.Failure -> {
                                Toast.makeText(context, "Gagal memulihkan: ${result.reason}", Toast.LENGTH_LONG).show()
                                orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
                            }
                            null -> {
                                Toast.makeText(context, "Gagal membuka berkas cadangan.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Kesalahan sistem: ${t.localizedMessage ?: t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val exportVoiceVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val exportedCount = context.contentResolver.openOutputStream(uri)?.use { os ->
                        voiceCacheManager.exportVaultToZipStream(os)
                    } ?: 0
                    voiceCacheManager.getStats()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "$exportedCount klip suara berhasil dicadangkan ke Google Drive / Berkas!",
                            Toast.LENGTH_LONG
                        ).show()
                        orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Gagal mencadangkan suara: ${t.localizedMessage ?: t.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
                    }
                }
            }
        }
    }

    val importVoiceVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val res = context.contentResolver.openInputStream(uri)?.use { input ->
                        voiceCacheManager.importVaultFromZipStream(input)
                    }
                    voiceCacheManager.getStats()
                    withContext(Dispatchers.Main) {
                        if (res != null) {
                            Toast.makeText(
                                context,
                                "Pemulihan suara berhasil: ${res.importedCount} klip baru, ${res.skippedCount} sudah ada.",
                                Toast.LENGTH_LONG
                            ).show()
                            orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                        } else {
                            Toast.makeText(context, "Gagal membuka arsip cadangan suara.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Gagal memulihkan suara: ${t.localizedMessage ?: t.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
                    }
                }
            }
        }
    }

    fun submitTextGoal() {
        val goal = promptText.trim()
        if (goal.isEmpty() || state.isExecutingGoal) return
        promptText = ""
        scope.launch {
            orchestrator.executeVoiceGoal(goal)
        }
    }

    fun handleToggleLiveCall() {
        if (!permissionStatus.microphoneGranted && !state.isLiveCallActive) {
            pendingLiveCallAfterPerm = true
            runtimeMicLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            scope.launch { orchestrator.toggleGeminiLiveCall() }
        }
    }

    fun handleQuickActionOption(option: QuickActionOption) {
        activeSection = GepyMainSection.ASSISTANT
        when {
            option.id == QUICK_ACTION_LIVE_CALL_ID -> handleToggleLiveCall()
            option.isImmediateExecute -> scope.launch { orchestrator.executeVoiceGoal(option.promptText) }
            else -> promptText = option.promptText
        }
    }

    val ownerDisplayName = remember(currentUser?.displayName, userMemoryManager) {
        currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: userMemoryManager.getUserDisplayName()
    }

    if (showByokDialog) {
        ByokConfigurationDialog(
            currentSettings = state.byokSettings,
            onDismiss = { showByokDialog = false },
            onSave = { updated, startLiveNow ->
                orchestrator.updateByokSettings(updated, startLiveCallAfterSave = startLiveNow)
                showByokDialog = false
                scope.launch(Dispatchers.IO) {
                    voiceCacheManager.enforceCacheBudget()
                }
                if (startLiveNow) {
                    activeSection = GepyMainSection.ASSISTANT
                }
            },
            onResetDefault = {
                orchestrator.resetByokSettings()
                showByokDialog = false
                scope.launch(Dispatchers.IO) {
                    voiceCacheManager.enforceCacheBudget()
                }
            },
            onTestElevenLabsVoice = { draftRuntime ->
                val engine = orchestrator.voiceHudPort as? VoiceAudioEngine
                val sample = SovereignNaturalSpeech.voicePreviewSample(orchestrator.currentSpeechContext())
                engine?.previewUnsavedElevenLabsVoice(draftRuntime, sampleText = sample)
                    ?: Result.failure(IllegalStateException("Mesin suara Gepy belum aktif."))
            }
        )
    }

    if (showPlaybookDialog) {
        PlaybookMemoryDialog(
            orchestrator = orchestrator,
            onDismiss = { showPlaybookDialog = false }
        )
    }

    if (showTemporalMemoryDialog) {
        TemporalMemoryDialog(
            userMemoryManager = userMemoryManager,
            onDismiss = { showTemporalMemoryDialog = false },
            onExportVault = {
                val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                exportVaultLauncher.launch("gepy_memory_vault_$dateTag.json")
            },
            onImportVault = {
                importVaultLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
        )
    }

    fun simulateCsiEvent(jsonEvent: String, forceMentorNudge: Boolean) {
        scope.launch {
            if (espCsiBridge != null) {
                espCsiBridge.processIncomingEventJson(jsonEvent)
            }
            if (forceMentorNudge) {
                val owner = userMemoryManager.getUserDisplayName()
                val snap = orchestrator.wellnessEngine?.currentSnapshot ?: WellnessGuardianSnapshot(sedentaryBedMinutes = 185)
                val ctx = snap.copy(sedentaryBedMinutes = maxOf(snap.sedentaryBedMinutes, 185))
                    .toPersonalWellnessContext(
                        ownerName = owner,
                        doomscrollLimitMinutes = state.byokSettings.doomscrollLimitMinutes
                    )
                val speech = SovereignPersonaEngine.buildGuardianInterventionSpeech(
                    configuredMode = state.byokSettings.sovereignPersonaMode,
                    reason = GuardianTriggerReason.SEDENTARY_BED_ALERT,
                    wellness = ctx,
                    autoHomeTriggered = false
                )
                orchestrator.deliverGuardianIntervention(speech, triggerAutoHome = false)
            }
        }
    }

    if (showVoiceDatasetDialog) {
        VoiceDatasetInspectorDialog(
            voiceCacheManager = voiceCacheManager,
            onStatsChanged = { voiceCacheManager.getStats() },
            onExportVoiceVault = {
                val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                exportVoiceVaultLauncher.launch("gepy_voice_vault_$dateTag.zip")
            },
            onDismiss = { showVoiceDatasetDialog = false }
        )
    }

    if (showRemoteIotSheet) {
        RemoteAndIotSheet(
            byok = state.byokSettings,
            wellnessSnapshot = wellnessSnapshot,
            isCsiServerRunning = isCsiServerRunning,
            onUpdateByokSettings = { orchestrator.updateByokSettings(it) },
            onExecuteQuickGoal = { goal ->
                activeSection = GepyMainSection.ASSISTANT
                scope.launch { orchestrator.executeVoiceGoal(goal) }
            },
            onSimulateCsiEvent = { jsonEvent, forceMentorNudge ->
                simulateCsiEvent(jsonEvent, forceMentorNudge)
            },
            onOpenFullSettings = {
                refreshPermissions()
                activeSection = GepyMainSection.SETTINGS
            },
            onDismiss = { showRemoteIotSheet = false }
        )
    }

    if (showProactiveGuardianSheet) {
        ProactiveGuardianSheet(
            byok = state.byokSettings,
            wellnessSnapshot = wellnessSnapshot,
            onUpdateByokSettings = { orchestrator.updateByokSettings(it) },
            onPlayDailyBriefing = {
                scope.launch { orchestrator.triggerOnDemandBriefing() }
            },
            onPrefillPrompt = { prefill ->
                activeSection = GepyMainSection.ASSISTANT
                promptText = prefill
            },
            onDismiss = { showProactiveGuardianSheet = false }
        )
    }

    if (showSkillsCatalogSheet) {
        SkillsCatalogSheet(
            skills = orchestrator.skillRegistry.skills,
            onPrefillSkillPrompt = { prefill ->
                activeSection = GepyMainSection.ASSISTANT
                promptText = prefill
            },
            onDismiss = { showSkillsCatalogSheet = false }
        )
    }

    if (showDeleteMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteMemoryDialog = false },
            title = { Text("Hapus Riwayat Memori Cloud?") },
            text = { Text("Semua fakta, lokasi penting, preferensi personal, dan ingatan pribadimu bakal dihapus permanen dari server.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteMemoryDialog = false
                        userMemoryManager.clearLocalMemory()
                        val user = currentUser
                        if (user != null && user.sessionToken.isNotBlank()) {
                            scope.launch {
                                val res = gatewayClient.clearMemories(user.sessionToken)
                                if (res.isSuccess && res.getOrDefault(false)) {
                                    orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                    orchestrator.voiceHudPort.finishAndSpeak("Semua memori personal lokal dan cloud udah dihapus.")
                                } else {
                                    orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
                                    orchestrator.voiceHudPort.finishAndSpeak("Memori lokal udah dihapus, tapi hapus memori cloud-nya lagi gagal.")
                                }
                            }
                        } else {
                            scope.launch {
                                orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                orchestrator.voiceHudPort.finishAndSpeak("Semua memori personal lokal udah dihapus.")
                            }
                        }
                    }
                ) {
                    Text("Hapus Permanen", color = Color(0xFFD93025))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMemoryDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = activeSection == GepyMainSection.ASSISTANT || drawerState.isOpen,
        drawerContent = {
            GepyDrawerSheetContent(
                drawerState = drawerState,
                activeSection = activeSection,
                ownerDisplayName = ownerDisplayName,
                ownerEmail = currentUser?.email,
                isRemoteOrCsiActive = state.byokSettings.laptopCompanionEnabled || isCsiServerRunning,
                chatHistory = state.chatHistory,
                telemetryHistory = state.telemetryHistory,
                onSelectSection = { targetSection ->
                    scope.launch { drawerState.close() }
                    activeSection = targetSection
                    when (targetSection) {
                        GepyMainSection.TELEMETRY -> scope.launch { orchestrator.refreshTelemetryHistory() }
                        GepyMainSection.SETTINGS -> refreshPermissions()
                        GepyMainSection.ASSISTANT -> Unit
                    }
                },
                onOpenFeature = { featureTarget ->
                    scope.launch { drawerState.close() }
                    when (featureTarget) {
                        DrawerFeatureTarget.MEMORY_TEMPORAL -> showTemporalMemoryDialog = true
                        DrawerFeatureTarget.PLAYBOOK_GRAPH -> showPlaybookDialog = true
                        DrawerFeatureTarget.REMOTE_AND_IOT -> showRemoteIotSheet = true
                        DrawerFeatureTarget.PROACTIVE_GUARDIAN -> showProactiveGuardianSheet = true
                        DrawerFeatureTarget.SKILLS_CATALOG -> showSkillsCatalogSheet = true
                    }
                },
                onSelectPinnedWorkflow = { pinned ->
                    scope.launch { drawerState.close() }
                    activeSection = GepyMainSection.ASSISTANT
                    if (pinned.isImmediateExecute) {
                        scope.launch { orchestrator.executeVoiceGoal(pinned.goalPrompt) }
                    } else {
                        promptText = pinned.goalPrompt
                    }
                },
                onSelectRecentEntry = { entry ->
                    scope.launch { drawerState.close() }
                    activeSection = GepyMainSection.ASSISTANT
                    if (!entry.isActiveSession && entry.promptToPrefill.isNotBlank()) {
                        promptText = entry.promptToPrefill
                    }
                },
                onStartNewChat = {
                    scope.launch { drawerState.close() }
                    activeSection = GepyMainSection.ASSISTANT
                    promptText = ""
                    if (!state.isExecutingGoal) {
                        orchestrator.clearConversationSession()
                    }
                }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                TopHeaderBar(
                    state = state,
                    activeSection = activeSection,
                    permissionStatus = permissionStatus,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNavigateBack = { activeSection = GepyMainSection.ASSISTANT },
                    onToggleOverlay = { scope.launch { orchestrator.toggleOverlayHud() } },
                    onOpenPermissionWizard = onOpenPermissionWizard,
                    onStartNewChat = {
                        promptText = ""
                        orchestrator.clearConversationSession()
                    }
                )
                if (activeSection != GepyMainSection.ASSISTANT) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (activeSection) {
                        GepyMainSection.ASSISTANT -> AssistantTabContent(
                            state = state,
                            wellnessSnapshot = wellnessSnapshot,
                            permissionStatus = permissionStatus,
                            ownerName = ownerDisplayName,
                            promptText = promptText,
                            onPromptTextChange = { promptText = it },
                            onPrefillPrompt = { promptText = it },
                            onSelectQuickAction = { option -> handleQuickActionOption(option) },
                            onSubmitPrompt = { submitTextGoal() },
                            onMicTrigger = {
                                scope.launch { orchestrator.triggerVoiceCommandListen() }
                            },
                            onQuickGoal = { quickText ->
                                scope.launch { orchestrator.executeVoiceGoal(quickText) }
                            },
                            onCancelGoal = { orchestrator.cancelActiveGoal() },
                            onClearSession = { orchestrator.clearConversationSession() },
                            onRollbackSession = { sessionId ->
                                scope.launch { orchestrator.rollbackSession(sessionId) }
                            },
                            onToggleLiveCall = { handleToggleLiveCall() },
                            onOpenAccessibilitySettings = { openAccessibilitySettings(context) }
                        )

                        GepyMainSection.TELEMETRY -> TelemetryTabContent(
                            state = state,
                            userMemoryManager = userMemoryManager,
                            memoryPort = orchestrator.memoryPort
                        )

                        GepyMainSection.SETTINGS -> SettingsTabContent(
                            state = state,
                            wellnessSnapshot = wellnessSnapshot,
                            isCsiServerRunning = isCsiServerRunning,
                            permissionStatus = permissionStatus,
                            currentUser = currentUser,
                            isSigningIn = isSigningIn,
                            voiceCacheStats = voiceCacheStats,
                            elevenLabsClient = elevenLabsClient,
                            onOpenVoiceDatasetDialog = { showVoiceDatasetDialog = true },
                            onClearVoiceCache = {
                                voiceCacheManager.clearAllCache()
                                Toast.makeText(context, "Cache suara lokal dibersihkan.", Toast.LENGTH_SHORT).show()
                            },
                            onExportVoiceVault = {
                                val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportVoiceVaultLauncher.launch("gepy_voice_vault_$dateTag.zip")
                            },
                            onImportVoiceVault = {
                                importVoiceVaultLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            onSignInGoogle = {
                                if (isSigningIn) return@SettingsTabContent
                                isSigningIn = true
                                scope.launch {
                                    try {
                                        when (val res = authManager.signIn(context)) {
                                            is GoogleAuthResult.Success -> {
                                                val authRes = gatewayClient.authenticateWithGoogle(res.idToken)
                                                if (authRes.isSuccess) {
                                                    val data = authRes.getOrThrow()
                                                    authManager.saveSession(data.user, data.sessionToken)
                                                    orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                                    orchestrator.voiceHudPort.finishAndSpeak("Berhasil masuk sebagai ${data.user.displayName}. Memori Cloud aktif.")
                                                } else {
                                                    authManager.saveSession(res.user, "offline_session_token")
                                                    orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                                    orchestrator.voiceHudPort.finishAndSpeak("Berhasil masuk sebagai ${res.user.displayName}.")
                                                }
                                            }
                                            is GoogleAuthResult.Cancelled -> {}
                                            is GoogleAuthResult.Failure -> {
                                                orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
                                                orchestrator.voiceHudPort.finishAndSpeak("Gagal masuk: ${res.message}")
                                            }
                                        }
                                    } finally {
                                        isSigningIn = false
                                    }
                                }
                            },
                            onSignOutGoogle = {
                                scope.launch {
                                    authManager.signOut()
                                    userMemoryManager.hasSkippedOpeningAuth = false
                                    orchestrator.hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                    activeSection = GepyMainSection.ASSISTANT
                                    onSignedOut()
                                }
                            },
                            onClearCloudMemories = {
                                showDeleteMemoryDialog = true
                            },
                            onOpenByokDialog = { showByokDialog = true },
                            onOpenPlaybookDialog = { showPlaybookDialog = true },
                            onOpenTemporalMemoryDialog = { showTemporalMemoryDialog = true },
                            onPlayDailyBriefing = {
                                scope.launch {
                                    orchestrator.triggerOnDemandBriefing()
                                }
                            },
                            onUpdateByokSettings = { updated ->
                                orchestrator.updateByokSettings(updated)
                            },
                            onRequestStepPermission = {
                                runtimeMicLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                            },
                            onSimulateCsiEvent = { jsonEvent, forceMentorNudge ->
                                simulateCsiEvent(jsonEvent, forceMentorNudge)
                            },
                            onToggleZeroHitl = { orchestrator.setFullAccessZeroHitl(it) },
                            onSelectVoicePersona = { orchestrator.setLiveVoicePersona(it) },
                            onSelectNativeStt = { orchestrator.setUseNativeStt(it) },
                            onRefreshPermissions = { refreshPermissions() },
                            onRequestMicAndContext = {
                                val perms = buildList {
                                    add(Manifest.permission.RECORD_AUDIO)
                                    add(Manifest.permission.READ_CONTACTS)
                                    add(Manifest.permission.READ_CALENDAR)
                                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                runtimeMicLauncher.launch(perms.toTypedArray())
                            },
                            onOpenOverlaySettings = { openOverlayPermissionSettings(context) },
                            onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                            onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
                            onOpenNotificationListenerSettings = { openNotificationListenerSettings(context) },
                            onOpenPermissionWizard = onOpenPermissionWizard
                        )
                    }
                }
            }
        }
    }
}


@Preview(showBackground = true, showSystemUi = false)
@Composable
private fun AssistantHeroCanvasPreview() {
    val previewPermissions = PermissionChecklistStatus(
        microphoneGranted = true,
        overlayGranted = true,
        accessibilityEnabled = true,
        batteryUnrestricted = true
    )
    CyberpunkTheme(darkTheme = false) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopHeaderBar(
                state = GepyOrchestratorState(),
                activeSection = GepyMainSection.ASSISTANT,
                permissionStatus = previewPermissions,
                onOpenDrawer = {},
                onNavigateBack = {},
                onToggleOverlay = {},
                onOpenPermissionWizard = {},
                onStartNewChat = {}
            )
            Box(modifier = Modifier.weight(1f)) {
                DeveloperAssistantContent(
                    state = GepyOrchestratorState(),
                    wellnessSnapshot = WellnessGuardianSnapshot(),
                    permissionStatus = previewPermissions,
                    ownerName = "Wahyu Irwan",
                    promptText = "",
                    onPromptTextChange = {},
                    onPrefillPrompt = {},
                    onSelectQuickAction = {},
                    onSubmitPrompt = {},
                    onMicTrigger = {},
                    onQuickGoal = {},
                    onCancelGoal = {},
                    onClearSession = {},
                    onRollbackSession = {},
                    onToggleLiveCall = {},
                    onOpenAccessibilitySettings = {}
                )
            }
        }
    }
}
