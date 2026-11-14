package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.errors.AgentException
import com.teknologigawai.gepy.core.errors.FlagSecureException
import com.teknologigawai.gepy.core.errors.ForbiddenPackageException
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.memory.ConversationChannel
import com.teknologigawai.gepy.core.memory.ConversationSessionBuffer
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.orchestrator.UnifiedActionSupervisor
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignPersonaEngine
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.GoalVerificationVerdict
import com.teknologigawai.gepy.core.ports.MemoryPort
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.ports.PredictedEdgeOutcome
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.wellness.WellnessAndGuardianEngine
import com.teknologigawai.gepy.features.brain.InterruptionShieldAndCritic
import com.teknologigawai.gepy.features.brain.SymbolicEdgeWorldModel
import com.teknologigawai.gepy.features.brain.TaskPlannerAndVerifier
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.SetOfMarksGrounder
import com.teknologigawai.gepy.features.voice.SpeculativePreFetcher
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker
import kotlinx.coroutines.CancellationException

/**
 * Mesin eksekusi siklus **Observe -> Think -> Act -> Verify** (ReAct) lengkap dengan
 * *Playbook Fast-Path*, *Symbolic Edge World Model* (`SEWM`), *Set-of-Marks Vision Grounding*,
 * *Two-Phase Commit* memori, serta penanganan pengecualian & pembatalan berbasis epoch.
 */
internal class VoiceGoalReActRunner(
    private val perceptionPort: PerceptionPort,
    private val actionPort: ActionPort,
    private val memoryPort: MemoryPort,
    private val brainPort: BrainPort,
    private val voiceHudPort: VoiceHudPort,
    private val skillRegistry: SkillRegistry,
    private val hapticManager: HapticFeedbackManager,
    private val userMemoryManager: UserMemoryManager?,
    private val wellnessEngine: WellnessAndGuardianEngine?,
    private val setOfMarksGrounder: SetOfMarksGrounder,
    private val unifiedSupervisor: UnifiedActionSupervisor,
    private val conversationBuffer: ConversationSessionBuffer,
    private val osStateVerifier: OsStateVerifierPort,
    private val maxStepsPerGoal: Int,
    private val speculativePreFetcher: SpeculativePreFetcher,
    private val playbookCoordinator: PlaybookAndLaunchCoordinator,
    private val stepActionDispatcher: StepActionDispatcher,
    private val fastPathHandler: VoiceGoalFastPathHandler,
    private val goalHost: OrchestratorGoalHost
) {
    suspend fun executeVoiceGoalWithEpoch(
        rawVoiceGoal: String,
        expectedEpoch: Long
    ) {
        try {
            val prepared = fastPathHandler.tryHandlePreReActPath(
                rawVoiceGoal = rawVoiceGoal,
                expectedEpoch = expectedEpoch
            ) ?: return

            var activeGoal = prepared.activeGoal
            val sessionId = prepared.sessionId
            val osBaseline = prepared.osBaseline
            val crossAppPlan = prepared.crossAppPlan
            var verificationRetryCount = 0
            val sewmVetoedEdges = mutableSetOf<String>()
            var sewmVetoCount = 0

            // Langkah 0C: App Launch First / Direct WhatsApp Chat Pre-Launch untuk perintah gabungan
            playbookCoordinator.ensureInitialAppLaunched(activeGoal)
            goalHost.ensureGoalRunActive(expectedEpoch)

            for (step in 1..maxStepsPerGoal) {
                goalHost.ensureGoalRunActive(expectedEpoch)
                val canProceed = unifiedSupervisor.checkActionBarrier()
                goalHost.ensureGoalRunActive(expectedEpoch)
                if (!canProceed) {
                    goalHost.recordClosingAssistantTurnIfPending("Perintah dihentikan sebelum selesai.")
                    return
                }

                val stepStartMs = System.currentTimeMillis()
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.OBSERVING,
                        currentStep = step
                    )
                }

                // 1. OBSERVE: Gunakan snapshot Speculative Pre-Fetcher (0 ms) pada langkah 1 jika masih segar,
                //    atau tangkap & pangkas pohon aksesibilitas aktif di memori (0 ms IPC).
                val speculativeHit = if (step == 1) {
                    speculativePreFetcher.consumeFreshSnapshotIfAvailable(
                        currentForegroundPackage = perceptionPort.currentForegroundPackage
                    )
                } else {
                    null
                }
                var pruneSnap = speculativeHit?.snapshot ?: perceptionPort.capturePruneSnapshot()
                var elements = pruneSnap.elements
                val currentPkg = speculativeHit?.foregroundPackage?.ifEmpty { perceptionPort.currentForegroundPackage }
                    ?: perceptionPort.currentForegroundPackage
                unifiedSupervisor.onStepStarted(step, currentPkg)

                if (SafetyRules.isPackageForbidden(currentPkg)) {
                    throw ForbiddenPackageException(targetIdentifier = currentPkg)
                }

                // Deteksi layar kanvas kosong / FLAG_SECURE
                if (elements.isEmpty() && step >= 2) {
                    pruneSnap = perceptionPort.capturePruneSnapshot(forceOcrFallback = true)
                    elements = pruneSnap.elements
                    if (elements.isEmpty() &&
                        currentPkg.isNotEmpty() &&
                        currentPkg != EnvConfig.SELF_PACKAGE_NAME
                    ) {
                        throw FlagSecureException(packageName = currentPkg)
                    }
                }

                val preSignature = perceptionPort.computeSignature(elements)
                val structuralHash = AccessibilityTreePruner.computeStructuralLayoutHash(
                    foregroundPackage = currentPkg,
                    elements = elements
                )
                val memorySnapshot = memoryPort.buildSnapshot()

                // 1.5. Symbolic Edge World Model (SEWM, Room v6 -> < 0,1 ms cache RAM)
                memoryPort.warmEdgeRisksForScreen(
                    packageName = currentPkg,
                    screenSignatureHash = structuralHash
                )
                val screenEdgeRisks = memoryPort.predictEdgeRisksForScreenFast(
                    packageName = currentPkg,
                    screenSignatureHash = structuralHash
                )
                val sewmPromptAdvice = SymbolicEdgeWorldModel.buildPreActionPromptAdvice(
                    elements = elements,
                    rules = screenEdgeRisks,
                    userGoal = activeGoal
                )

                // 2. THINK: Fast-Path Skill -> Playbook Graph -> LLM BrainPort
                goalHost.ensureGoalRunActive(expectedEpoch)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.THINKING,
                        currentPackage = currentPkg,
                        lastPruningRatio = pruneSnap.pruningRatio
                    )
                }

                val matchedSkills = skillRegistry.matchSkills(
                    voiceTranscript = activeGoal,
                    activePackage = currentPkg
                )
                val skillHint = skillRegistry.buildCombinedDomainKnowledge(
                    voiceTranscript = activeGoal,
                    activePackage = currentPkg
                )
                val dbHint = memoryPort.getAppKnowledgeHint(currentPkg)
                val episodicHint = memoryPort.getEpisodicLessonsHint(currentPkg)
                val combinedKnowledge = listOfNotNull(skillHint, dbHint, episodicHint, sewmPromptAdvice)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .ifEmpty { null }

                // Prioritas 0: Skill Deterministic Fast-Path (0 ms LLM latency)
                var fastPathDecision: ReActActionModel? = null
                for (skill in matchedSkills) {
                    val candidate = skill.proposeFastPathAction(
                        userGoal = activeGoal,
                        foregroundPackage = currentPkg,
                        screenElements = elements,
                        memory = memorySnapshot
                    )
                    if (candidate != null) {
                        fastPathDecision = candidate
                        break
                    }
                }

                // Prioritas 1: Playbook Deterministic Fast-Path dari Room SQLite
                val playbookHit = if (fastPathDecision == null && memorySnapshot.loopWarningPrompt == null) {
                    memoryPort.findPlaybookTransition(
                        packageName = currentPkg,
                        screenSignatureHash = structuralHash,
                        intentGoal = activeGoal
                    )
                } else {
                    null
                }
                val rawPlaybookDecision = if (playbookHit != null) {
                    playbookCoordinator.tryBuildPlaybookDecision(
                        playbookHit.targetResourceId,
                        playbookHit.actionType,
                        playbookHit.successCount,
                        elements
                    )
                } else {
                    null
                }
                val playbookDecision = if (playbookHit != null && rawPlaybookDecision != null &&
                    SymbolicEdgeWorldModel.isPlaybookTargetRisky(
                        targetResourceId = playbookHit.targetResourceId,
                        actionType = playbookHit.actionType,
                        rules = screenEdgeRisks,
                        userGoal = activeGoal
                    )
                ) {
                    GepyLog.i("SEWM", "Melewati Playbook Fast-Path ${playbookHit.targetResourceId} karena prediksi risiko tinggi SEWM.")
                    null
                } else {
                    rawPlaybookDecision
                }

                // Prioritas 1.5: Deterministic Interruption Shield (0 token, arXiv:2604.07277v2)
                val popupShieldDecision = if (fastPathDecision == null && playbookDecision == null) {
                    InterruptionShieldAndCritic.detectBlockingPopupDismissal(
                        currentPackage = currentPkg,
                        elements = elements,
                        userGoal = activeGoal
                    )
                } else {
                    null
                }

                val llmStartMs = System.currentTimeMillis()
                var decision: ReActActionModel
                val llmLatencyMs: Int
                var isPlaybookExecution = false
                var playbookTargetResId: String? = null
                var playbookActionType: String? = null

                when {
                    fastPathDecision != null -> {
                        decision = fastPathDecision
                        llmLatencyMs = 0
                    }
                    playbookDecision != null -> {
                        decision = playbookDecision
                        llmLatencyMs = 0
                        isPlaybookExecution = true
                        playbookTargetResId = playbookHit?.targetResourceId
                        playbookActionType = playbookHit?.actionType
                    }
                    popupShieldDecision != null -> {
                        decision = popupShieldDecision
                        llmLatencyMs = 0
                    }
                    else -> {
                        val ownerName = userMemoryManager?.getUserDisplayName().orEmpty()
                        val currentByok = goalHost.currentState.byokSettings
                        val wellnessCtx = wellnessEngine?.currentSnapshot?.toPersonalWellnessContext(
                            ownerName = ownerName,
                            doomscrollLimitMinutes = currentByok.doomscrollLimitMinutes
                        ) ?: PersonalWellnessContext(
                            ownerName = ownerName,
                            doomscrollLimitMinutes = currentByok.doomscrollLimitMinutes
                        )
                        val personaDirective = SovereignPersonaEngine.buildReActPersonaDirective(
                            configuredMode = currentByok.sovereignPersonaMode,
                            wellness = wellnessCtx,
                            customPersonaPrompt = currentByok.customPersonaPrompt
                        )
                        val personalSummary = userMemoryManager?.buildHybridReActContext(activeGoal)

                        // Conditional Multimodal Vision Grounding (Set-of-Marks):
                        // Aktifkan tangkapan layar beranotasi HANYA jika kanvas buta XML atau 2 langkah terakhir INEFFECTIVE,
                        // dan BUKAN aplikasi finansial/sistem yang diblokir SafetyRules.
                        val recentIneffectiveStreak = memorySnapshot.recentSteps.takeLast(2).let { tail ->
                            tail.size == 2 && tail.all { it.outcomeStatus == ActionOutcome.INEFFECTIVE }
                        }
                        val visualSoMBytes = if (!SafetyRules.isPackageForbidden(currentPkg) &&
                            (setOfMarksGrounder.isCanvasBlindSpot(currentPkg, elements) || recentIneffectiveStreak)
                        ) {
                            perceptionPort.captureScreenshot()?.let { rawShot ->
                                setOfMarksGrounder.getCachedAnnotatedJpegIfSimilar(rawShot)
                                    ?: setOfMarksGrounder.renderCompactAnnotatedJpeg(rawShot, elements)
                            }
                        } else {
                            null
                        }

                        decision = brainPort.decideNextAction(
                            userGoal = activeGoal,
                            currentPackage = currentPkg,
                            screenElements = elements,
                            memory = memorySnapshot,
                            injectedDomainKnowledge = combinedKnowledge,
                            visualSoMImageBytes = visualSoMBytes,
                            personalContextSummary = personalSummary,
                            personaDirective = personaDirective,
                            conversationHistory = conversationBuffer.toChatHistoryMessages(excludeLatestUserText = activeGoal)
                        )
                        llmLatencyMs = (System.currentTimeMillis() - llmStartMs).toInt()
                    }
                }

                goalHost.ensureGoalRunActive(expectedEpoch)

                // Jalankan validasi keamanan pra-eksekusi dari setiap Skill aktif
                for (skill in matchedSkills) {
                    decision = skill.validateBeforeAction(
                        proposedAction = decision,
                        screenElements = elements
                    )
                }

                // Single-State Candidate Critic (0 ms, arXiv:2604.07277v2):
                // Koreksi targetIndex apabila menunjuk teks statis di sebelah tombol/kolom input sebenarnya
                decision = InterruptionShieldAndCritic.refineCandidateAction(
                    decision = decision,
                    elements = elements
                )

                // Symbolic Edge World Model Pre-Click Guard (0 ms, Room v6):
                // Cegat aksi klik sebelum menyentuh OS bila diprediksi memicu paywall/iklan/dialog izin/error/dead-end
                val sewmVeto = if (sewmVetoCount < 2) {
                    SymbolicEdgeWorldModel.evaluatePreActionEdgeGuard(
                        decision = decision,
                        elements = elements,
                        rules = screenEdgeRisks,
                        userGoal = activeGoal,
                        alreadyVetoedEdgeKeys = sewmVetoedEdges
                    )
                } else {
                    null
                }
                if (sewmVeto != null) {
                    sewmVetoedEdges.add(sewmVeto.matchedRule.edgeKey)
                    sewmVetoCount++
                    GepyLog.i("SEWM", "Pre-Click Veto step $step: ${sewmVeto.feedbackForBrain}")
                    memoryPort.setVerifierFeedback(sewmVeto.feedbackForBrain)
                    memoryPort.recordStep(
                        StepRecord(
                            stepNumber = step,
                            action = decision.action,
                            targetLabel = sewmVeto.targetElement.text.ifEmpty { sewmVeto.targetElement.nodeId },
                            targetIndex = decision.targetIndex,
                            textValue = decision.textValue,
                            screenSignatureBefore = preSignature,
                            screenSignatureAfter = preSignature,
                            outcomeStatus = ActionOutcome.BLOCKED_BY_HITL,
                            thought = sewmVeto.feedbackForBrain
                        )
                    )
                    voiceHudPort.showThinkingState(
                        stepHeadline = "SEWM Mencegat Klik Berisiko",
                        thought = sewmVeto.feedbackForBrain
                    )
                    goalHost.updateStateIfGoalActive(expectedEpoch) {
                        it.copy(
                            lastThought = sewmVeto.feedbackForBrain,
                            lastActionLabel = "SEWM_VETO",
                            lastLlmLatencyMs = llmLatencyMs
                        )
                    }
                    stepActionDispatcher.logStepTelemetry(
                        sessionId = sessionId,
                        step = step,
                        goal = activeGoal,
                        packageName = currentPkg,
                        rawNodes = pruneSnap.rawNodeCount,
                        prunedNodes = elements.size,
                        ratio = pruneSnap.pruningRatio,
                        llmMs = llmLatencyMs,
                        totalMs = (System.currentTimeMillis() - stepStartMs).toInt(),
                        actionType = "SEWM_VETO_${decision.action.wireName}",
                        outcome = "SEWM_VETOED"
                    )
                    continue
                }

                unifiedSupervisor.onActionCompleted(decision.action.wireName, decision.thought)

                // Simpan catatan ke Scratchpad Memory bila LLM menginstruksikan save_to_scratchpad
                val scratchEntry = decision.saveToScratchpad?.trim().orEmpty()
                if (scratchEntry.contains('=')) {
                    val splitIdx = scratchEntry.indexOf('=')
                    val key = scratchEntry.substring(0, splitIdx).trim()
                    val value = scratchEntry.substring(splitIdx + 1).trim()
                    memoryPort.saveScratchpadNote(key, value)
                    if (crossAppPlan != null && crossAppPlan.currentStageIndex == 0) {
                        crossAppPlan.markCurrentStageCompleted()
                    }
                }

                goalHost.ensureGoalRunActive(expectedEpoch)
                val cleanThought = ProcessTimelineTracker.cleanStepTextForDisplay(decision.thought)
                voiceHudPort.showThinkingState(
                    stepHeadline = ProcessTimelineTracker.mapActionTypeToHumanLabel(decision.action),
                    thought = cleanThought
                )
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        lastThought = cleanThought,
                        lastActionLabel = decision.action.wireName,
                        lastLlmLatencyMs = llmLatencyMs,
                        scratchpad = memoryPort.getScratchpadNotes()
                    )
                }

                // 3. ACT & VERIFY
                if (decision.action == AgentActionType.DONE) {
                    val isDeterministicFastPathDone = fastPathDecision != null
                    val verdict = if (isDeterministicFastPathDone) {
                        GoalVerificationVerdict(
                            achieved = true,
                            proofSummary = "Fast-Path deterministik mengeksekusi aksi OS/lokal secara langsung."
                        )
                    } else {
                        TaskPlannerAndVerifier.verifyBeforeDone(
                            userGoal = activeGoal,
                            currentPackage = currentPkg,
                            screenElements = elements,
                            memory = memorySnapshot,
                            brainPort = brainPort,
                            osStateVerifier = osStateVerifier,
                            osBaseline = osBaseline
                        )
                    }
                    goalHost.ensureGoalRunActive(expectedEpoch)
                    if (!verdict.achieved && verificationRetryCount < 2) {
                        verificationRetryCount++
                        memoryPort.setVerifierFeedback(verdict.missingReason)
                        voiceHudPort.showThinkingState(
                            stepHeadline = "Verifikasi: Belum Tuntas",
                            thought = verdict.missingReason
                        )
                        goalHost.updateStateIfGoalActive(expectedEpoch) {
                            it.copy(
                                lastThought = "Verifikasi: ${verdict.missingReason}",
                                lastActionLabel = "VERIFY_REJECTED"
                            )
                        }
                        continue
                    }

                    verdict.proofSummary?.takeIf { it.isNotBlank() }?.let { proof ->
                        GepyLog.i("OSVerifier", proof)
                    }
                    memoryPort.setVerifierFeedback(null)

                    // Tujuan diverifikasi sukses: Commit seluruh transisi di buffer staging (Two-Phase Commit Phase 2)
                    memoryPort.commitStagedTransitions(sessionId)
                    memoryPort.recordEpisodicRecoveryLesson(
                        packageName = currentPkg,
                        goal = activeGoal,
                        steps = memorySnapshot.recentSteps
                    )

                    val doneMsg = decision.completionTts?.trim().orEmpty().ifEmpty {
                        SovereignNaturalSpeech.orchestratorDoneFallback(goalHost.currentSpeechContext())
                    }
                    goalHost.recordChatTurn(role = "assistant", text = doneMsg, channel = ConversationChannel.TEXT_CHAT)
                    voiceHudPort.finishAndSpeak(doneMsg)
                    hapticManager.vibrateSuccess()

                    val totalMs = (System.currentTimeMillis() - stepStartMs).toInt()
                    stepActionDispatcher.logStepTelemetry(
                        sessionId = sessionId,
                        step = step,
                        goal = activeGoal,
                        packageName = currentPkg,
                        rawNodes = pruneSnap.rawNodeCount,
                        prunedNodes = elements.size,
                        ratio = pruneSnap.pruningRatio,
                        llmMs = llmLatencyMs,
                        totalMs = totalMs,
                        actionType = decision.action.wireName,
                        outcome = ActionOutcome.CORRECT.wireName
                    )

                    val displayDone = VoiceClauseChunker.stripAudioTagsForDisplay(doneMsg)
                    goalHost.updateStateIfGoalActive(expectedEpoch) {
                        it.copy(
                            fsmState = GepyFsmState.COMPLETED,
                            lastThought = displayDone,
                            lastActionLabel = "DONE",
                            lastSessionId = sessionId
                        )
                    }
                    unifiedSupervisor.onGoalCompleted(displayDone)
                    return
                }

                if (decision.action == AgentActionType.ASK_USER_DISAMBIGUATION) {
                    goalHost.updateStateIfGoalActive(expectedEpoch) { it.copy(fsmState = GepyFsmState.DISAMBIGUATING) }
                    val question = decision.questionTts?.trim().orEmpty().ifEmpty {
                        SovereignNaturalSpeech.orchestratorDisambiguateDefault(goalHost.currentSpeechContext())
                    }
                    val rankedOptions = memoryPort.rankDisambiguationOptions(
                        queryName = activeGoal,
                        packageName = currentPkg,
                        options = decision.options
                    )
                    val chosen = voiceHudPort.askDisambiguation(question, rankedOptions)
                    goalHost.ensureGoalRunActive(expectedEpoch)
                    if (chosen.isEmpty()) {
                        goalHost.cancelActiveGoal("Pemilihan kandidat dibatalkan.")
                        return
                    }
                    memoryPort.recordContactPreference(
                        queryName = activeGoal,
                        packageName = currentPkg,
                        resolvedDisplayName = chosen
                    )
                    activeGoal = "$activeGoal (Gunakan pilihan yang dikonfirmasi pengguna: \"$chosen\")"

                    val chosenElement = stepActionDispatcher.findElementMatchingLabel(elements, chosen)
                    val disambigOutcome = if (chosenElement != null) {
                        hapticManager.vibrateActionTick()
                        actionPort.tapElement(
                            target = chosenElement,
                            preSignature = preSignature,
                            hitlApproved = true
                        )
                    } else {
                        ActionOutcome.CORRECT
                    }
                    goalHost.ensureGoalRunActive(expectedEpoch)

                    val postElements = perceptionPort.captureAndPruneScreen()
                    val postSig = perceptionPort.computeSignature(postElements)
                    memoryPort.recordStep(
                        StepRecord(
                            stepNumber = step,
                            action = AgentActionType.ASK_USER_DISAMBIGUATION,
                            targetLabel = chosen,
                            targetIndex = chosenElement?.index,
                            screenSignatureBefore = preSignature,
                            screenSignatureAfter = postSig,
                            outcomeStatus = disambigOutcome,
                            thought = "Pengguna memilih kandidat disambiguasi: $chosen"
                        )
                    )
                    goalHost.updateStateIfGoalActive(expectedEpoch) { it.copy(currentGoal = activeGoal) }
                    continue
                }

                val execResult = stepActionDispatcher.executeSingleStepAction(
                    decision = decision,
                    elements = elements,
                    preSignature = preSignature,
                    expectedEpoch = expectedEpoch
                )
                val outcome = execResult.first
                val targetLabel = execResult.second
                memoryPort.setVerifierFeedback(null)

                // Otomatis majukan tahap alur majemuk lintas aplikasi jika salin data atau buka app tujuan berhasil
                if (crossAppPlan != null && outcome == ActionOutcome.CORRECT) {
                    if (crossAppPlan.currentStageIndex == 0 &&
                        (decision.action == AgentActionType.CLIPBOARD_COPY || decision.action == AgentActionType.LAUNCH_APP)
                    ) {
                        crossAppPlan.markCurrentStageCompleted()
                    }
                }

                // Jika langkah Playbook Fast-Path ternyata INEFFECTIVE, segera beri penalti & nonaktifkan
                if (isPlaybookExecution && outcome == ActionOutcome.INEFFECTIVE && playbookTargetResId != null) {
                    memoryPort.penalizePlaybookTransition(
                        packageName = currentPkg,
                        screenSignatureHash = structuralHash,
                        intentGoal = activeGoal,
                        targetResourceId = playbookTargetResId,
                        actionType = playbookActionType ?: "CLICK"
                    )
                }

                speculativePreFetcher.invalidate()
                val postElements = perceptionPort.captureAndPruneScreen()
                val postPackage = perceptionPort.currentForegroundPackage
                val postSignature = perceptionPort.computeSignature(postElements)

                // Symbolic Edge World Model (SEWM, Room v6): Klasifikasi transisi pasca-aksi & perbarui model risiko
                val usedTarget = decision.targetIndex?.let { idx -> elements.firstOrNull { it.index == idx } }
                val predictedEdgeOutcome = if (
                    usedTarget != null &&
                    outcome != ActionOutcome.BLOCKED_BY_HITL &&
                    !SafetyRules.isPackageForbidden(currentPkg) &&
                    currentPkg != EnvConfig.SELF_PACKAGE_NAME &&
                    !SafetyRules.isCredentialField(usedTarget.text, usedTarget.viewResourceId ?: usedTarget.nodeId) &&
                    decision.action in setOf(
                        AgentActionType.CLICK,
                        AgentActionType.CONFIRM_AND_CLICK,
                        AgentActionType.LONG_PRESS,
                        AgentActionType.SET_TEXT,
                        AgentActionType.SET_TEXT_AND_SUBMIT
                    )
                ) {
                    val classified = SymbolicEdgeWorldModel.classifyTransition(
                        userGoal = activeGoal,
                        prePackage = currentPkg,
                        preElements = elements,
                        postPackage = postPackage,
                        postElements = postElements,
                        rawOutcome = outcome
                    )
                    memoryPort.observeEdgeTransition(
                        packageName = currentPkg,
                        screenSignatureHash = structuralHash,
                        edgeKey = SymbolicEdgeWorldModel.buildEdgeKey(usedTarget),
                        actionType = decision.action.wireName,
                        targetLabelSample = usedTarget.text,
                        outcome = classified
                    )
                    classified
                } else {
                    null
                }

                // CoAT Post-Action Outcome Verification (0 ms, arXiv:2403.02713v2)
                val coatDiagnostic = TaskPlannerAndVerifier.verifyStepExpectedOutcome(
                    expectedOutcome = decision.expectedOutcome,
                    preElements = elements,
                    postElements = postElements,
                    rawOutcome = outcome
                )
                val enrichedStepThought = if (coatDiagnostic != null) {
                    "${decision.thought} $coatDiagnostic".trim()
                } else {
                    decision.thought
                }

                memoryPort.recordStep(
                    StepRecord(
                        stepNumber = step,
                        action = decision.action,
                        targetLabel = targetLabel,
                        targetIndex = decision.targetIndex,
                        textValue = decision.textValue,
                        screenSignatureBefore = preSignature,
                        screenSignatureAfter = postSignature,
                        outcomeStatus = outcome,
                        thought = enrichedStepThought
                    )
                )

                // Tahap 1 Two-Phase Commit: Simpan ke staging buffer sementara HANYA jika transisi aman (SAFE_PROGRESS)!
                if (outcome == ActionOutcome.CORRECT &&
                    decision.targetIndex != null &&
                    (predictedEdgeOutcome == null || predictedEdgeOutcome == PredictedEdgeOutcome.SAFE_PROGRESS)
                ) {
                    val resId = usedTarget?.viewResourceId
                    if (!resId.isNullOrEmpty()) {
                        memoryPort.stageCandidateTransition(
                            packageName = currentPkg,
                            intentGoal = activeGoal,
                            resourceId = resId,
                            screenSignatureHash = structuralHash,
                            actionType = decision.action.wireName
                        )
                    }
                }

                val totalMs = (System.currentTimeMillis() - stepStartMs).toInt()
                stepActionDispatcher.logStepTelemetry(
                    sessionId = sessionId,
                    step = step,
                    goal = activeGoal,
                    packageName = currentPkg,
                    rawNodes = pruneSnap.rawNodeCount,
                    prunedNodes = elements.size,
                    ratio = pruneSnap.pruningRatio,
                    llmMs = llmLatencyMs,
                    totalMs = totalMs,
                    actionType = decision.action.wireName,
                    outcome = outcome.wireName
                )
            }

            goalHost.ensureGoalRunActive(expectedEpoch)
            memoryPort.discardStagedTransitions()
            val maxStepMsg = SovereignNaturalSpeech.orchestratorMaxSteps(goalHost.currentSpeechContext(), maxStepsPerGoal)
            val displayMaxStepMsg = VoiceClauseChunker.stripAudioTagsForDisplay(maxStepMsg)
            goalHost.recordClosingAssistantTurnIfPending(maxStepMsg)
            voiceHudPort.finishAndSpeak(maxStepMsg)
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = GepyFsmState.COMPLETED,
                    lastThought = displayMaxStepMsg
                )
            }
            unifiedSupervisor.onGoalCompleted(displayMaxStepMsg)
        } catch (e: FlagSecureException) {
            memoryPort.discardStagedTransitions()
            if (goalHost.isGoalEpochActiveAndNotAborted(expectedEpoch)) {
                hapticManager.vibrateError()
                val errMsg = e.message.takeIf { it.isNotBlank() } ?: "Layar ini lagi dilindungi kebijakan keamanan."
                val spokenErr = SovereignNaturalSpeech.orchestratorError(goalHost.currentSpeechContext(), errMsg)
                val displayErr = VoiceClauseChunker.stripAudioTagsForDisplay(spokenErr)
                goalHost.recordClosingAssistantTurnIfPending(spokenErr)
                voiceHudPort.finishAndSpeak(spokenErr)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.ERROR,
                        errorMessage = displayErr,
                        lastThought = displayErr
                    )
                }
            }
        } catch (e: ForbiddenPackageException) {
            memoryPort.discardStagedTransitions()
            if (goalHost.isGoalEpochActiveAndNotAborted(expectedEpoch)) {
                hapticManager.vibrateError()
                val errMsg = e.message.takeIf { it.isNotBlank() } ?: "Aplikasi ini diblokir demi keamanan."
                val spokenErr = SovereignNaturalSpeech.orchestratorError(goalHost.currentSpeechContext(), errMsg)
                val displayErr = VoiceClauseChunker.stripAudioTagsForDisplay(spokenErr)
                goalHost.recordClosingAssistantTurnIfPending(spokenErr)
                voiceHudPort.finishAndSpeak(spokenErr)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.ERROR,
                        errorMessage = displayErr,
                        lastThought = displayErr
                    )
                }
            }
        } catch (e: AgentException) {
            memoryPort.discardStagedTransitions()
            if (goalHost.isGoalEpochActiveAndNotAborted(expectedEpoch)) {
                hapticManager.vibrateError()
                val errMsg = e.message.takeIf { it.isNotBlank() } ?: "ada kendala pas ngerjain perintah tadi."
                goalHost.recordClosingAssistantTurnIfPending(errMsg)
                voiceHudPort.finishAndSpeak(errMsg)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.ERROR,
                        errorMessage = errMsg,
                        lastThought = VoiceClauseChunker.stripAudioTagsForDisplay(errMsg)
                    )
                }
            }
        } catch (_: GoalSupersededAbortException) {
            memoryPort.discardStagedTransitions()
            return
        } catch (e: CancellationException) {
            memoryPort.discardStagedTransitions()
            if (goalHost.isGoalEpochActiveAndNotAborted(expectedEpoch)) {
                goalHost.recordClosingAssistantTurnIfPending("Perintah dihentikan sebelum selesai.")
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.IDLE,
                        lastActionLabel = "ABORTED"
                    )
                }
            }
            throw e
        } catch (e: Throwable) {
            memoryPort.discardStagedTransitions()
            GepyLog.e("Orchestrator", "Kesalahan tidak terduga pada loop ReAct: ${e.message}", e)
            if (goalHost.isGoalEpochActiveAndNotAborted(expectedEpoch)) {
                hapticManager.vibrateError()
                val friendlyMsg = SovereignNaturalSpeech.orchestratorError(
                    goalHost.currentSpeechContext(),
                    "barusan ada kendala teknis sebentar."
                )
                val displayFriendly = VoiceClauseChunker.stripAudioTagsForDisplay(friendlyMsg)
                goalHost.recordClosingAssistantTurnIfPending(friendlyMsg)
                voiceHudPort.finishAndSpeak(friendlyMsg)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.ERROR,
                        errorMessage = e.message ?: displayFriendly,
                        lastThought = displayFriendly
                    )
                }
            }
        }
    }
}
