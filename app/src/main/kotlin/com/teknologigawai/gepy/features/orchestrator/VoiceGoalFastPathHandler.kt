package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.memory.ConversationChannel
import com.teknologigawai.gepy.core.memory.ConversationSessionBuffer
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.orchestrator.UnifiedActionSupervisor
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.MemoryPort
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.OsVerificationBaseline
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.core.skills.WebSearchFastPathSkill
import com.teknologigawai.gepy.core.speech.IndonesianSpeechNormalizer
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.core.web.WebSearchAndReaderBridge
import com.teknologigawai.gepy.features.brain.ConversationalChatRouter
import com.teknologigawai.gepy.features.brain.CrossAppWorkflowEngine
import com.teknologigawai.gepy.features.brain.CrossAppWorkflowPlan
import com.teknologigawai.gepy.features.brain.IntentEvaluationResult
import com.teknologigawai.gepy.features.brain.IntentPrecisionGatekeeper
import com.teknologigawai.gepy.features.brain.TaskPlannerAndVerifier
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker
import com.teknologigawai.gepy.service.NotificationOpStatus
import kotlinx.coroutines.CancellationException

/** Konteks sesi yang disiapkan setelah fase gerbang presisi & fast-path apabila tugas berlanjut ke loop ReAct. */
internal data class PreparedReActSession(
    val activeGoal: String,
    val sessionId: String,
    val osBaseline: OsVerificationBaseline?,
    val crossAppPlan: CrossAppWorkflowPlan?
)

/**
 * Penangan evaluasi presisi maksud (*Intent Precision Gatekeeper*) dan jalur cepat tanpa UI
 * (*Deep-Link Router*, *Pure App Launch*, *API-First Zero-UI Skills*, *Web Search*, & *Conversational Chat*).
 */
internal class VoiceGoalFastPathHandler(
    private val perceptionPort: PerceptionPort,
    private val memoryPort: MemoryPort,
    private val brainPort: BrainPort,
    private val voiceHudPort: VoiceHudPort,
    private val appLauncher: AppIntentLauncher,
    private val skillRegistry: SkillRegistry,
    private val systemToolsBridge: AndroidSystemToolsBridge,
    private val webSearchBridge: WebSearchAndReaderBridge,
    private val deepLinkRouter: IntentDeepLinkRouter,
    private val hapticManager: HapticFeedbackManager,
    private val userMemoryManager: UserMemoryManager?,
    private val unifiedSupervisor: UnifiedActionSupervisor,
    private val intentPrecisionGatekeeper: IntentPrecisionGatekeeper,
    private val conversationBuffer: ConversationSessionBuffer,
    private val osStateVerifier: OsStateVerifierPort,
    private val maxStepsPerGoal: Int,
    private val apiFirstZeroUiSkillIds: Set<String>,
    private val stepActionDispatcher: StepActionDispatcher,
    private val goalHost: OrchestratorGoalHost
) {
    /**
     * Mengevaluasi instruksi suara/teks [rawVoiceGoal] dan mencoba menyelesaikan tugas lewat jalur cepat.
     * Mengembalikan `null` jika perintah selesai/dibatalkan pada fase pra-ReAct, atau [PreparedReActSession]
     * apabila eksekusi harus berlanjut ke siklus *Observe -> Think -> Act -> Verify*.
     */
    suspend fun tryHandlePreReActPath(
        rawVoiceGoal: String,
        expectedEpoch: Long
    ): PreparedReActSession? {
        goalHost.ensureGoalRunActive(expectedEpoch)
        var activeGoal = IndonesianSpeechNormalizer.normalize(rawVoiceGoal)
        if (activeGoal.isEmpty()) return null

        // Evaluasi Presisi Maksud (Intent Precision & Anti-Salah Dengar)
        val evaluation = intentPrecisionGatekeeper.evaluateIntent(
            rawCommand = activeGoal,
            sovereignPersonalMode = goalHost.currentState.byokSettings.sovereignPersonalMode
        )
        goalHost.ensureGoalRunActive(expectedEpoch)

        when (evaluation) {
            is IntentEvaluationResult.IgnoredNoise -> {
                if (evaluation.friendlyReply != null) {
                    voiceHudPort.finishAndSpeak(evaluation.friendlyReply)
                }
                return null
            }
            is IntentEvaluationResult.RequiresDisambiguation -> {
                val displayQuestion = VoiceClauseChunker.stripAudioTagsForDisplay(evaluation.questionPrompt)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.DISAMBIGUATING,
                        lastThought = displayQuestion
                    )
                }
                val selectedCandidate = voiceHudPort.askDisambiguation(
                    questionTts = evaluation.questionPrompt,
                    options = evaluation.candidates
                )
                goalHost.ensureGoalRunActive(expectedEpoch)
                if (selectedCandidate.isBlank()) {
                    voiceHudPort.finishAndSpeak("Pilihan dibatalkan.")
                    goalHost.updateStateIfGoalActive(expectedEpoch) { it.copy(fsmState = GepyFsmState.IDLE) }
                    return null
                }
                val candidateTarget = selectedCandidate.substringBefore(" (").trim()
                val candidatePhone = selectedCandidate.substringAfter(" (", "").substringBefore(")").trim()
                val orig = evaluation.originalGoal.trim()
                val isPhoneDialGoal = Regex(
                    """^(?:(?:halo|hai|hei|gepy|tolong|coba|bantu)\s+)*(?:telepon|telpon|tlp|telfon|hubungi|call|dial)\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(orig)
                val isWhatsAppGoal = Regex("""\b(wa|whatsapp)\b""", RegexOption.IGNORE_CASE).containsMatchIn(orig)
                val msgTail = Regex(
                    """^(?:(?:halo|hai|hei|gepy|tolong|coba|bantu)\s+)*(?:kirim(?:\s+pesan)?(?:\s+wa)?(?:\s+ke|\s+wa\s+ke)?|wa\s+ke|chat\s+ke|hubungi|telepon|telpon)\s+[a-zA-Z0-9\s]+?\s+(?:isi\s+pesan|bilang|isi\s+chat|pesan|bahwa)\s+(.+)$""",
                    RegexOption.IGNORE_CASE
                ).find(orig)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val targetLabelOrPhone = candidateTarget.ifEmpty { candidatePhone }
                activeGoal = when {
                    isPhoneDialGoal && !isWhatsAppGoal -> "Telepon $targetLabelOrPhone"
                    isPhoneDialGoal && isWhatsAppGoal -> "Telepon WA $targetLabelOrPhone"
                    isWhatsAppGoal && msgTail.isNotEmpty() -> "Kirim pesan WA ke $targetLabelOrPhone bilang $msgTail"
                    isWhatsAppGoal -> "Kirim pesan WA ke $targetLabelOrPhone"
                    msgTail.isNotEmpty() -> "Kirim pesan ke $targetLabelOrPhone bilang $msgTail"
                    else -> "Kirim pesan ke $targetLabelOrPhone"
                }
            }
            is IntentEvaluationResult.RequiresConfirmation -> {
                val displayPrompt = VoiceClauseChunker.stripAudioTagsForDisplay(evaluation.confirmationPrompt)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.WAITING_HITL,
                        lastThought = displayPrompt
                    )
                }
                val isApproved = voiceHudPort.requestHitlApproval(
                    summaryPrompt = evaluation.confirmationPrompt,
                    targetLabel = evaluation.actionSummary
                )
                goalHost.ensureGoalRunActive(expectedEpoch)
                if (!isApproved) {
                    voiceHudPort.finishAndSpeak("Oke, tindakan dibatalkan sesuai maumu.")
                    goalHost.updateStateIfGoalActive(expectedEpoch) { it.copy(fsmState = GepyFsmState.IDLE) }
                    return null
                }
            }
            is IntentEvaluationResult.Approved -> {
                activeGoal = evaluation.cleanGoal
            }
        }

        systemToolsBridge.laptopHostProvider = {
            goalHost.currentState.byokSettings.laptopTailscaleHost
        }

        // Ekstraksi fakta baru & Penguatan Spaced Repetition (Ebbinghaus) dari kalimat pengguna
        userMemoryManager?.let { mem ->
            try {
                mem.inspectTranscriptForProfile(activeGoal)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ge: GoalSupersededAbortException) {
                throw ge
            } catch (_: Throwable) {
            }
        }
        goalHost.ensureGoalRunActive(expectedEpoch)
        goalHost.recordChatTurn(role = "user", text = activeGoal, channel = ConversationChannel.TEXT_CHAT)

        memoryPort.resetSession(clearScratchpad = false)
        val sessionId = "ses_${System.currentTimeMillis()}"

        val planChecklist = TaskPlannerAndVerifier.buildSubgoalChecklist(activeGoal)
        memoryPort.setActivePlanChecklist(planChecklist)
        unifiedSupervisor.onGoalStarted(activeGoal, planChecklist, maxStepsPerGoal)
        val osBaseline = try {
            osStateVerifier.captureBaseline()
        } catch (ce: CancellationException) {
            throw ce
        } catch (ge: GoalSupersededAbortException) {
            throw ge
        } catch (_: Throwable) {
            null
        }
        goalHost.ensureGoalRunActive(expectedEpoch)
        val crossAppPlan = if (CrossAppWorkflowEngine.isCrossAppCommand(activeGoal)) {
            CrossAppWorkflowEngine.parseWorkflowPlan(activeGoal)
        } else {
            null
        }

        val goalStartWallMs = System.currentTimeMillis()
        goalHost.updateStateIfGoalActive(expectedEpoch) {
            it.copy(
                fsmState = GepyFsmState.OBSERVING,
                currentGoal = activeGoal,
                currentStep = 0,
                lastThought = "Memahami perintahmu...",
                lastActionLabel = "-",
                errorMessage = null,
                goalStartedAtMs = goalStartWallMs,
                goalElapsedMs = 0L,
                processTimeline = listOf(
                    AgentProcessStep(
                        text = "Memahami perintahmu...",
                        state = ProcessStepState.ACTIVE,
                        timestampMs = goalStartWallMs
                    )
                )
            )
        }

        // Langkah 0A: Zero-UI Deep-Link Router (< 15 ms untuk Pengaturan Sistem & Navigasi Maps)
        val immediateDeepLink = deepLinkRouter.tryExecuteImmediateDeepLinkGoal(activeGoal)
        if (immediateDeepLink.handled && !immediateDeepLink.requiresSubsequentUiVerify) {
            val displayDeepLink = VoiceClauseChunker.stripAudioTagsForDisplay(immediateDeepLink.summaryId)
            goalHost.recordChatTurn(role = "assistant", text = immediateDeepLink.summaryId, channel = ConversationChannel.TEXT_CHAT)
            voiceHudPort.finishAndSpeak(immediateDeepLink.summaryId)
            hapticManager.vibrateSuccess()
            stepActionDispatcher.logStepTelemetry(
                sessionId = sessionId,
                step = 1,
                goal = activeGoal,
                packageName = immediateDeepLink.routeType,
                rawNodes = 0,
                prunedNodes = 0,
                ratio = 1.0,
                llmMs = 0,
                totalMs = 5,
                actionType = immediateDeepLink.routeType,
                outcome = ActionOutcome.CORRECT.wireName
            )
            goalHost.ensureGoalRunActive(expectedEpoch)
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = GepyFsmState.COMPLETED,
                    currentStep = 1,
                    lastThought = displayDeepLink,
                    lastActionLabel = immediateDeepLink.routeType,
                    lastLlmLatencyMs = 0
                )
            }
            return null
        }

        // Langkah 0B: Perintah murni "Buka <Nama Aplikasi>" (misal "Buka WA", "Buka Instagram", "Buka YouTube")
        // Langsung luncurkan aplikasi via PackageManager (< 15 ms, 0 panggilan LLM) dan tuntaskan sesi!
        val parsedAppLaunch = AppIntentLauncher.parseVoiceAppLaunchIntent(activeGoal)
        if (parsedAppLaunch != null && parsedAppLaunch.isPureLaunchOnly) {
            val launchRes = appLauncher.openApp(parsedAppLaunch.appName)
            if (launchRes.success) {
                appLauncher.waitForApp(
                    packageName = launchRes.resolvedPackage,
                    perceptionPort = perceptionPort,
                    timeoutMs = 3_500L
                )
                goalHost.ensureGoalRunActive(expectedEpoch)
                val doneMsg = SovereignNaturalSpeech.appOpened(goalHost.currentSpeechContext(), parsedAppLaunch.appName)
                val displayDone = VoiceClauseChunker.stripAudioTagsForDisplay(doneMsg)
                goalHost.recordChatTurn(role = "assistant", text = doneMsg, channel = ConversationChannel.TEXT_CHAT)
                voiceHudPort.finishAndSpeak(doneMsg)
                hapticManager.vibrateSuccess()
                stepActionDispatcher.logStepTelemetry(
                    sessionId = sessionId,
                    step = 1,
                    goal = activeGoal,
                    packageName = launchRes.resolvedPackage,
                    rawNodes = 0,
                    prunedNodes = 0,
                    ratio = 1.0,
                    llmMs = 0,
                    totalMs = 15,
                    actionType = AgentActionType.LAUNCH_APP.wireName,
                    outcome = ActionOutcome.CORRECT.wireName
                )
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.COMPLETED,
                        currentStep = 1,
                        currentPackage = launchRes.resolvedPackage,
                        lastThought = displayDone,
                        lastActionLabel = AgentActionType.LAUNCH_APP.wireName,
                        lastLlmLatencyMs = 0
                    )
                }
                return null
            }
        }

        // Langkah 0B.1: API-First Zero-UI Fast-Path (< 15 ms, 0 token LLM) SEBELUM peluncuran aplikasi UI
        val preMatchedSkills = skillRegistry.matchSkills(
            voiceTranscript = activeGoal,
            activePackage = perceptionPort.currentForegroundPackage
        )
        val preActionMemorySnap = memoryPort.buildSnapshot()
        for (skill in preMatchedSkills) {
            if (skill.skillId !in apiFirstZeroUiSkillIds) continue
            val apiCandidate = skill.proposeFastPathAction(
                userGoal = activeGoal,
                foregroundPackage = perceptionPort.currentForegroundPackage,
                screenElements = emptyList(),
                memory = preActionMemorySnap
            ) ?: continue

            var resolvedTts: String? = null
            var resolvedActionLabel = "API_FAST_PATH (${skill.skillId})"

            when (apiCandidate.action) {
                AgentActionType.DONE -> {
                    resolvedTts = apiCandidate.completionTts
                }
                AgentActionType.CONFIRM_AND_CLICK -> {
                    // Konfirmasi HITL untuk balas notifikasi latar belakang (RemoteInput) saat mode non-Sovereign
                    val notifKey = apiCandidate.appName?.trim().orEmpty()
                    val replyText = apiCandidate.textValue?.trim().orEmpty()
                    val confirmPrompt = apiCandidate.confirmationPrompt
                        ?: SovereignNaturalSpeech.notificationReplyConfirmPrompt(
                            ctx = goalHost.currentSpeechContext(),
                            senderName = "pengirim",
                            replyMessage = replyText
                        )
                    val displayPrompt = VoiceClauseChunker.stripAudioTagsForDisplay(confirmPrompt)
                    goalHost.updateStateIfGoalActive(expectedEpoch) {
                        it.copy(
                            fsmState = GepyFsmState.WAITING_HITL,
                            lastThought = displayPrompt
                        )
                    }
                    val isApproved = voiceHudPort.requestHitlApproval(
                        summaryPrompt = confirmPrompt,
                        targetLabel = notifKey.ifEmpty { "Notifikasi" }
                    )
                    goalHost.ensureGoalRunActive(expectedEpoch)
                    if (!isApproved) {
                        val cancelMsg = "Oke, balas notifikasinya aku batalin sesuai maumu."
                        goalHost.recordClosingAssistantTurnIfPending(cancelMsg)
                        voiceHudPort.finishAndSpeak(cancelMsg)
                        hapticManager.vibrateError()
                        goalHost.updateStateIfGoalActive(expectedEpoch) {
                            it.copy(
                                fsmState = GepyFsmState.IDLE,
                                lastThought = cancelMsg,
                                lastActionLabel = "ABORTED"
                            )
                        }
                        unifiedSupervisor.abortGoal(cancelMsg)
                        return null
                    }
                    if (notifKey.isNotEmpty() && replyText.isNotEmpty()) {
                        val replyRes = systemToolsBridge.replyToNotification(
                            senderOrAppQuery = notifKey,
                            replyMessage = replyText,
                            exactNotificationKey = notifKey
                        )
                        if (replyRes.status == NotificationOpStatus.SUCCESS) {
                            resolvedTts = SovereignNaturalSpeech.notificationReplied(
                                goalHost.currentSpeechContext(),
                                replyRes.targetTitle.ifEmpty { "pengirim" }
                            )
                            resolvedActionLabel = "REMOTE_INPUT_REPLY_HITL"
                        } else if (replyRes.status == NotificationOpStatus.SAFETY_BLOCKED ||
                            replyRes.status == NotificationOpStatus.PRIVACY_SHIELD_BLOCKED
                        ) {
                            resolvedTts = if (replyRes.status == NotificationOpStatus.SAFETY_BLOCKED) {
                                SovereignNaturalSpeech.notificationsSafetyBlocked(goalHost.currentSpeechContext())
                            } else {
                                SovereignNaturalSpeech.notificationsPrivacyBlocked(goalHost.currentSpeechContext())
                            }
                            resolvedActionLabel = "REMOTE_INPUT_BLOCKED"
                        } else {
                            // RemoteInput tidak lagi tersedia -> lanjut ke UI-Fallback
                            resolvedTts = null
                        }
                    }
                }
                else -> {}
            }

            if (resolvedTts != null) {
                val spoken = resolvedTts.ifBlank {
                    SovereignNaturalSpeech.orchestratorDoneFallback(goalHost.currentSpeechContext())
                }
                val displayText = VoiceClauseChunker.stripAudioTagsForDisplay(spoken)
                goalHost.recordChatTurn(role = "assistant", text = spoken, channel = ConversationChannel.TEXT_CHAT)
                voiceHudPort.finishAndSpeak(spoken)
                hapticManager.vibrateSuccess()
                stepActionDispatcher.logStepTelemetry(
                    sessionId = sessionId,
                    step = 1,
                    goal = activeGoal,
                    packageName = "os.api.${skill.skillId}",
                    rawNodes = 0,
                    prunedNodes = 0,
                    ratio = 1.0,
                    llmMs = 0,
                    totalMs = 10,
                    actionType = resolvedActionLabel,
                    outcome = ActionOutcome.CORRECT.wireName
                )
                goalHost.ensureGoalRunActive(expectedEpoch)
                goalHost.updateStateIfGoalActive(expectedEpoch) {
                    it.copy(
                        fsmState = GepyFsmState.COMPLETED,
                        currentStep = 1,
                        lastThought = displayText,
                        lastActionLabel = resolvedActionLabel,
                        lastLlmLatencyMs = 0,
                        lastSessionId = sessionId
                    )
                }
                unifiedSupervisor.onGoalCompleted(displayText)
                return null
            }
        }

        // Langkah 0B.15: Pertanyaan lanjutan yang merujuk obrolan sesi aktif (Chat / Live Call)
        val priorSessionTurns = conversationBuffer.toChatHistoryMessages(excludeLatestUserText = activeGoal)
        if (preMatchedSkills.isEmpty() &&
            ConversationalChatRouter.isContextualFollowUpTurn(
                rawInput = activeGoal,
                hasPriorConversationTurns = priorSessionTurns.isNotEmpty()
            )
        ) {
            val chatStartMs = System.currentTimeMillis()
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = GepyFsmState.THINKING,
                    currentStep = 1,
                    lastThought = "Menjawab pertanyaan lanjutan..."
                )
            }
            val personaDirective = goalHost.buildCurrentReActPersonaDirective()
            val personalSummary = userMemoryManager?.buildHybridReActContext(activeGoal)
            val conversationalReply = brainPort.generateConversationalReply(
                userMessage = activeGoal,
                conversationHistory = priorSessionTurns,
                personalContextSummary = personalSummary,
                personaDirective = personaDirective
            )
            goalHost.ensureGoalRunActive(expectedEpoch)
            val chatLatencyMs = (System.currentTimeMillis() - chatStartMs).toInt()
            goalHost.recordChatTurn(role = "assistant", text = conversationalReply, channel = ConversationChannel.TEXT_CHAT)
            voiceHudPort.finishAndSpeak(conversationalReply)
            hapticManager.vibrateSuccess()
            stepActionDispatcher.logStepTelemetry(
                sessionId = sessionId,
                step = 1,
                goal = activeGoal,
                packageName = "conversation.multi_turn",
                rawNodes = 0,
                prunedNodes = 0,
                ratio = 1.0,
                llmMs = chatLatencyMs,
                totalMs = chatLatencyMs,
                actionType = "CONVERSATIONAL_CHAT",
                outcome = ActionOutcome.CORRECT.wireName
            )
            val displayReply = VoiceClauseChunker.stripAudioTagsForDisplay(conversationalReply)
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = GepyFsmState.COMPLETED,
                    currentStep = 1,
                    lastThought = displayReply,
                    lastActionLabel = "CHAT",
                    lastLlmLatencyMs = chatLatencyMs,
                    lastSessionId = sessionId
                )
            }
            unifiedSupervisor.onGoalCompleted(displayReply)
            return null
        }

        // Langkah 0B.2: Fast-Path Pencarian Web & Pembaca Artikel (tanpa membuka browser Chrome UI)
        if (WebSearchFastPathSkill.isPureWebInformationQuery(activeGoal)) {
            val startWebMs = System.currentTimeMillis()
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = GepyFsmState.THINKING,
                    currentStep = 1,
                    lastThought = "Mencari informasi terkini di web untuk: \"$activeGoal\"..."
                )
            }
            voiceHudPort.showThinkingState(
                stepHeadline = "Mencari di Web...",
                thought = activeGoal
            )
            val targetUrl = WebSearchFastPathSkill.extractTargetUrl(activeGoal)
            val (webSuccess, spokenAnswer) = if (targetUrl != null) {
                val res = webSearchBridge.readWebpageContent(url = targetUrl)
                res.success to res.spokenSummary
            } else if (activeGoal.contains("huggingface", ignoreCase = true) || activeGoal.contains("hugging face", ignoreCase = true)) {
                val cleanQ = WebSearchFastPathSkill.extractCleanSearchQuery(activeGoal)
                    .replace(Regex("""(?i)\b(huggingface|hugging\s+face|model|di)\b"""), "").trim()
                val res = if (cleanQ.isNotBlank() && (cleanQ.contains("/") || activeGoal.contains("detail", ignoreCase = true) || activeGoal.contains("rilis", ignoreCase = true))) {
                    webSearchBridge.getHuggingFaceModelDetails(cleanQ)
                } else {
                    webSearchBridge.queryHuggingFaceTrending(cleanQ)
                }
                res.success to res.spokenAnswer
            } else if (activeGoal.contains("github", ignoreCase = true) || activeGoal.contains("git hub", ignoreCase = true)) {
                val cleanQ = WebSearchFastPathSkill.extractCleanSearchQuery(activeGoal)
                    .replace(Regex("""(?i)\b(github|git\s+hub|repo|repository|di)\b"""), "").trim()
                val res = if (cleanQ.isNotBlank() && (cleanQ.contains("/") || activeGoal.contains("detail", ignoreCase = true) || activeGoal.contains("rilis", ignoreCase = true))) {
                    webSearchBridge.getGitHubRepoDetails(cleanQ)
                } else {
                    webSearchBridge.queryGitHubTrendingOrSearch(cleanQ)
                }
                res.success to res.spokenAnswer
            } else {
                val byok = goalHost.currentState.byokSettings
                val res = webSearchBridge.searchWebRealtime(
                    query = activeGoal,
                    geminiApiKey = byok.effectiveGeminiApiKey,
                    fireworksApiKey = byok.effectiveFireworksApiKey,
                    geminiSecondaryApiKey = byok.effectiveGeminiSecondaryApiKey
                )
                res.success to res.spokenAnswer
            }
            goalHost.ensureGoalRunActive(expectedEpoch)
            val webLatencyMs = (System.currentTimeMillis() - startWebMs).toInt()
            goalHost.recordChatTurn(role = "assistant", text = spokenAnswer, channel = ConversationChannel.TEXT_CHAT)
            voiceHudPort.finishAndSpeak(spokenAnswer)
            if (webSuccess) {
                hapticManager.vibrateSuccess()
            } else {
                hapticManager.vibrateError()
            }
            val webOutcome = if (webSuccess) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE
            stepActionDispatcher.logStepTelemetry(
                sessionId = sessionId,
                step = 1,
                goal = activeGoal,
                packageName = "web.search.grounding",
                rawNodes = 0,
                prunedNodes = 0,
                ratio = 1.0,
                llmMs = webLatencyMs,
                totalMs = webLatencyMs,
                actionType = if (targetUrl != null) "WEB_READ" else "WEB_SEARCH",
                outcome = webOutcome.wireName
            )
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = if (webSuccess) GepyFsmState.COMPLETED else GepyFsmState.ERROR,
                    currentStep = 1,
                    lastThought = spokenAnswer,
                    lastActionLabel = if (targetUrl != null) "WEB_READ" else "WEB_SEARCH",
                    lastLlmLatencyMs = webLatencyMs
                )
            }
            return null
        }

        // Langkah 0B.3: Jalur Percakapan Multi-Turn Alami (sinkron lintas Chat & Live Call tanpa klik UI)
        if (ConversationalChatRouter.isPureConversationalTurn(
                rawInput = activeGoal,
                hasMatchedDeviceSkill = preMatchedSkills.isNotEmpty()
            )
        ) {
            val chatStartMs = System.currentTimeMillis()
            voiceHudPort.showThinkingState(
                stepHeadline = "Berpikir...",
                thought = "Menyiapkan jawaban untukmu..."
            )
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = GepyFsmState.THINKING,
                    currentStep = 1,
                    lastThought = "Menyiapkan jawaban..."
                )
            }
            val personaDirective = goalHost.buildCurrentReActPersonaDirective()
            val personalSummary = userMemoryManager?.buildHybridReActContext(activeGoal)
            val conversationalReply = brainPort.generateConversationalReply(
                userMessage = activeGoal,
                conversationHistory = priorSessionTurns,
                personalContextSummary = personalSummary,
                personaDirective = personaDirective
            )
            goalHost.ensureGoalRunActive(expectedEpoch)
            val chatLatencyMs = (System.currentTimeMillis() - chatStartMs).toInt()
            goalHost.recordChatTurn(role = "assistant", text = conversationalReply, channel = ConversationChannel.TEXT_CHAT)
            voiceHudPort.finishAndSpeak(conversationalReply)
            hapticManager.vibrateSuccess()
            stepActionDispatcher.logStepTelemetry(
                sessionId = sessionId,
                step = 1,
                goal = activeGoal,
                packageName = "conversation.multi_turn",
                rawNodes = 0,
                prunedNodes = 0,
                ratio = 1.0,
                llmMs = chatLatencyMs,
                totalMs = chatLatencyMs,
                actionType = "CONVERSATIONAL_CHAT",
                outcome = ActionOutcome.CORRECT.wireName
            )
            val displayReply = VoiceClauseChunker.stripAudioTagsForDisplay(conversationalReply)
            goalHost.updateStateIfGoalActive(expectedEpoch) {
                it.copy(
                    fsmState = GepyFsmState.COMPLETED,
                    currentStep = 1,
                    lastThought = displayReply,
                    lastActionLabel = "CHAT",
                    lastLlmLatencyMs = chatLatencyMs,
                    lastSessionId = sessionId
                )
            }
            unifiedSupervisor.onGoalCompleted(displayReply)
            return null
        }

        return PreparedReActSession(
            activeGoal = activeGoal,
            sessionId = sessionId,
            osBaseline = osBaseline,
            crossAppPlan = crossAppPlan
        )
    }
}
