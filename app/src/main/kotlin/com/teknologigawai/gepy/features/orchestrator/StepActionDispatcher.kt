package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.errors.ForbiddenPackageException
import com.teknologigawai.gepy.core.memory.CrossAppScratchpadInterpolator
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.MemoryPort
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.ports.TelemetryEntry
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.features.brain.CrossAppWorkflowEngine
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Eksekutor aksi UI tunggal (*Focus-Before-Type*, *Reactive `waitStable()`*, *Safety Gatekeeper*,
 * *Human-in-the-Loop*, dan pencatatan telemetri langkah).
 */
internal class StepActionDispatcher(
    private val perceptionPort: PerceptionPort,
    private val actionPort: ActionPort,
    private val memoryPort: MemoryPort,
    private val voiceHudPort: VoiceHudPort,
    private val appLauncher: AppIntentLauncher,
    private val systemToolsBridge: AndroidSystemToolsBridge,
    private val hapticManager: HapticFeedbackManager,
    private val byokPreferencesManager: ByokPreferencesManager,
    private val goalHost: OrchestratorGoalHost
) {
    suspend fun executeSingleStepAction(
        decision: ReActActionModel,
        elements: List<PrunedUiElement>,
        preSignature: String,
        expectedEpoch: Long
    ): Pair<ActionOutcome, String> {
        goalHost.ensureGoalRunActive(expectedEpoch)
        goalHost.updateStateIfGoalActive(expectedEpoch) { it.copy(fsmState = GepyFsmState.EXECUTING) }

        return when (decision.action) {
            AgentActionType.LAUNCH_APP -> {
                val appName = decision.appName?.trim().orEmpty()
                val lowerApp = appName.lowercase()
                val isDesktopExclusive = lowerApp in listOf(
                    "vscode", "vs code", "visual studio code", "code", "notepad"
                ) || lowerApp.contains("di laptop") || lowerApp.contains("di pc") || lowerApp.contains("di komputer")

                if (isDesktopExclusive && byokPreferencesManager.currentSettings.laptopCompanionEnabled) {
                    val cleanTarget = when {
                        lowerApp.contains("notepad") -> "notepad"
                        lowerApp.contains("chrome") -> "chrome"
                        lowerApp.contains("spotify") -> "spotify"
                        lowerApp.contains("terminal") -> "terminal"
                        else -> "vscode"
                    }
                    val res = systemToolsBridge.executeSystemToolCall(
                        "execute_laptop_command",
                        mapOf("action" to "open_app", "target" to cleanTarget)
                    )
                    return Pair(
                        if (res["status"] == "SUCCESS") ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE,
                        "LAPTOP_APP_${cleanTarget.uppercase()}"
                    )
                }

                val res = appLauncher.openApp(appName)
                if (!res.success) {
                    return Pair(ActionOutcome.INEFFECTIVE, appName)
                }
                val settled = appLauncher.waitForApp(
                    packageName = res.resolvedPackage,
                    perceptionPort = perceptionPort
                )
                Pair(
                    if (settled) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE,
                    res.resolvedPackage
                )
            }

            AgentActionType.BACK -> {
                val ok = actionPort.navigateBack()
                Pair(
                    if (ok) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE,
                    "GLOBAL_BACK"
                )
            }

            AgentActionType.HOME -> {
                val ok = actionPort.navigateHome()
                Pair(
                    if (ok) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE,
                    "GLOBAL_HOME"
                )
            }

            AgentActionType.WAIT -> {
                actionPort.waitStable(timeoutMs = 2_500L, changedFrom = preSignature)
                Pair(ActionOutcome.CORRECT, "WAIT_2500MS")
            }

            AgentActionType.SCROLL -> {
                val dir = decision.scrollDirection ?: "DOWN"
                val outcome = actionPort.scrollScreen(
                    direction = dir,
                    preSignature = preSignature
                )
                Pair(outcome, "SCROLL_$dir")
            }

            AgentActionType.SWIPE -> {
                val dir = (decision.scrollDirection ?: "LEFT").uppercase()
                hapticManager.vibrateActionTick()
                val ok = actionPort.swipeScreen(fingerDirection = dir)
                if (ok) {
                    actionPort.waitStable(timeoutMs = 1_800L, changedFrom = preSignature)
                }
                Pair(
                    if (ok) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE,
                    "SWIPE_$dir"
                )
            }

            AgentActionType.LONG_PRESS -> {
                val target = findElementByIndex(elements, decision.targetIndex)
                    ?: return Pair(ActionOutcome.INEFFECTIVE, "MISSING_LONG_PRESS_NODE")
                if (SafetyRules.isPackageForbidden(target.packageName)) {
                    throw ForbiddenPackageException(targetIdentifier = target.packageName)
                }
                if (SafetyRules.isCredentialField(label = target.text, resourceId = target.viewResourceId)) {
                    return Pair(ActionOutcome.INEFFECTIVE, "FORBIDDEN_CREDENTIAL_NODE")
                }
                hapticManager.vibrateActionTick()
                val ok = actionPort.longPressCoordinates(
                    x = target.centerX.toFloat(),
                    y = target.centerY.toFloat(),
                    durationMs = 800L
                )
                if (ok) {
                    actionPort.waitStable(timeoutMs = 1_800L, changedFrom = preSignature)
                }
                Pair(
                    if (ok) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE,
                    target.text.ifEmpty { target.nodeId }
                )
            }

            AgentActionType.CLIPBOARD_COPY -> {
                val target = findElementByIndex(elements, decision.targetIndex)
                if (target != null && SafetyRules.isPackageForbidden(target.packageName)) {
                    throw ForbiddenPackageException(targetIdentifier = target.packageName)
                }
                if (target != null && SafetyRules.isCredentialField(label = target.text, resourceId = target.viewResourceId)) {
                    return Pair(ActionOutcome.INEFFECTIVE, "FORBIDDEN_CREDENTIAL_COPY")
                }
                val textToCopy = decision.textValue?.trim().takeIf { !it.isNullOrEmpty() }
                    ?: target?.text?.trim().orEmpty()
                if (textToCopy.isEmpty()) {
                    return Pair(ActionOutcome.INEFFECTIVE, "EMPTY_CLIPBOARD_SOURCE")
                }
                val copied = systemToolsBridge.copyToClipboard(textToCopy)
                if (copied) {
                    val currentGoal = goalHost.currentState.currentGoal
                    memoryPort.saveScratchpadNote("clipboard_last", textToCopy.take(240))
                    if (CrossAppWorkflowEngine.isCrossAppCommand(currentGoal)) {
                        val plan = CrossAppWorkflowEngine.parseWorkflowPlan(currentGoal)
                        val outVar = plan.stages.firstOrNull()?.expectedOutputVariable
                        if (!outVar.isNullOrBlank()) {
                            memoryPort.saveScratchpadNote(outVar, textToCopy.take(240))
                        }
                    }
                }
                Pair(
                    if (copied) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE,
                    "CLIPBOARD_COPY(${textToCopy.take(24)})"
                )
            }

            AgentActionType.CLIPBOARD_PASTE -> {
                val target = findElementByIndex(elements, decision.targetIndex)
                    ?: return Pair(ActionOutcome.INEFFECTIVE, "MISSING_PASTE_NODE")
                if (SafetyRules.isCredentialField(label = target.text, resourceId = target.viewResourceId)) {
                    return Pair(ActionOutcome.INEFFECTIVE, "FORBIDDEN_CREDENTIAL_PASTE")
                }
                val rawClip = systemToolsBridge.readFromClipboard().ifEmpty {
                    decision.textValue?.trim().orEmpty()
                }
                val clipText = CrossAppScratchpadInterpolator.interpolateText(
                    inputText = rawClip,
                    scratchpad = memoryPort.getScratchpadNotes(),
                    clipboardFallback = rawClip
                )
                if (clipText.isEmpty()) {
                    return Pair(ActionOutcome.INEFFECTIVE, "EMPTY_CLIPBOARD_PASTE")
                }
                hapticManager.vibrateActionTick()
                val outcome = actionPort.setTextElement(
                    target = target,
                    text = clipText,
                    preSignature = preSignature
                )
                Pair(outcome, target.text.ifEmpty { target.nodeId })
            }

            AgentActionType.SET_TEXT -> {
                val target = findElementByIndex(elements, decision.targetIndex)
                    ?: return Pair(ActionOutcome.INEFFECTIVE, "MISSING_EDIT_NODE")
                val textVal = CrossAppScratchpadInterpolator.interpolateText(
                    inputText = decision.textValue,
                    scratchpad = memoryPort.getScratchpadNotes(),
                    clipboardFallback = systemToolsBridge.readFromClipboard()
                )
                hapticManager.vibrateActionTick()
                val outcome = actionPort.setTextElement(
                    target = target,
                    text = textVal,
                    preSignature = preSignature
                )
                Pair(outcome, target.text.ifEmpty { target.nodeId })
            }

            AgentActionType.SET_TEXT_AND_SUBMIT -> {
                val target = findElementByIndex(elements, decision.targetIndex)
                    ?: return Pair(ActionOutcome.INEFFECTIVE, "MISSING_EDIT_NODE")
                val textVal = CrossAppScratchpadInterpolator.interpolateText(
                    inputText = decision.textValue,
                    scratchpad = memoryPort.getScratchpadNotes(),
                    clipboardFallback = systemToolsBridge.readFromClipboard()
                )
                hapticManager.vibrateActionTick()
                val outcome = actionPort.setTextAndSubmitElement(
                    target = target,
                    text = textVal,
                    preSignature = preSignature
                )
                Pair(outcome, target.text.ifEmpty { target.nodeId })
            }

            AgentActionType.CLICK,
            AgentActionType.CONFIRM_AND_CLICK -> {
                val target = findElementByIndex(elements, decision.targetIndex)
                    ?: return Pair(ActionOutcome.INEFFECTIVE, "MISSING_CLICK_NODE")

                val isCatastrophic = SafetyRules.requiresHitlConfirmation(
                    label = target.text,
                    resourceId = target.viewResourceId ?: target.nodeId,
                    sovereignDirectExecution = true
                )
                val requiresHitl = decision.action == AgentActionType.CONFIRM_AND_CLICK ||
                    SafetyRules.requiresHitlConfirmation(
                        label = target.text,
                        resourceId = target.viewResourceId ?: target.nodeId,
                        sovereignDirectExecution = false
                    )

                val shouldPromptHitl = isCatastrophic || (requiresHitl && !goalHost.currentState.fullAccessZeroHitl)
                var approved = !shouldPromptHitl
                if (shouldPromptHitl) {
                    val prompt = decision.confirmationPrompt?.trim().orEmpty().ifEmpty {
                        SovereignNaturalSpeech.orchestratorConfirmDefault(goalHost.currentSpeechContext())
                    }
                    val hitlDisplayThought = buildHitlPendingThought(target.text, prompt)
                    goalHost.updateStateIfGoalActive(expectedEpoch) {
                        it.copy(
                            fsmState = GepyFsmState.WAITING_HITL,
                            lastThought = hitlDisplayThought
                        )
                    }
                    hapticManager.vibrateHitlWarning()
                    approved = voiceHudPort.requestHitlApproval(
                        summaryPrompt = prompt,
                        targetLabel = target.text.ifEmpty { target.nodeId }
                    )
                    goalHost.ensureGoalRunActive(expectedEpoch)
                    if (!approved) {
                        goalHost.cancelActiveGoal("Oke, tindakan batal dijalankan.")
                        return Pair(ActionOutcome.BLOCKED_BY_HITL, target.text)
                    }
                    goalHost.updateStateIfGoalActive(expectedEpoch) { it.copy(fsmState = GepyFsmState.EXECUTING) }
                }

                goalHost.ensureGoalRunActive(expectedEpoch)
                hapticManager.vibrateActionTick()
                var outcome = actionPort.tapElement(
                    target = target,
                    preSignature = preSignature,
                    hitlApproved = approved
                )

                if (outcome == ActionOutcome.BLOCKED_BY_HITL) {
                    if (goalHost.currentState.fullAccessZeroHitl && !isCatastrophic) {
                        outcome = actionPort.tapElement(
                            target = target,
                            preSignature = preSignature,
                            hitlApproved = true
                        )
                    } else {
                        val hitlDisplayThought = buildHitlPendingThought(
                            target.text,
                            "Menunggu persetujuan sebelum melanjutkan tindakan ini."
                        )
                        goalHost.updateStateIfGoalActive(expectedEpoch) {
                            it.copy(
                                fsmState = GepyFsmState.WAITING_HITL,
                                lastThought = hitlDisplayThought
                            )
                        }
                        val hitlOk = voiceHudPort.requestHitlApproval(
                            summaryPrompt = "Boleh aku ketuk tombol \"${target.text}\"? Konfirmasi dulu ya.",
                            targetLabel = target.text
                        )
                        goalHost.ensureGoalRunActive(expectedEpoch)
                        if (!hitlOk) {
                            goalHost.cancelActiveGoal("Tindakan dibatalkan pada gerbang keamanan HITL.")
                            return Pair(ActionOutcome.BLOCKED_BY_HITL, target.text)
                        }
                        outcome = actionPort.tapElement(
                            target = target,
                            preSignature = preSignature,
                            hitlApproved = true
                        )
                    }
                }
                Pair(outcome, target.text.ifEmpty { target.nodeId })
            }

            AgentActionType.ASK_USER_DISAMBIGUATION,
            AgentActionType.DONE -> Pair(ActionOutcome.CORRECT, decision.action.wireName)
        }
    }

    private fun buildHitlPendingThought(targetText: String, fallbackPrompt: String): String {
        val cleanedTarget = ProcessTimelineTracker.cleanStepTextForDisplay(targetText)
        val rawThought = cleanedTarget.takeIf { it.isNotEmpty() }?.let {
            "Menunggu persetujuan sebelum mengetuk tombol \"$it\"."
        } ?: fallbackPrompt
        return ProcessTimelineTracker.cleanStepTextForDisplay(rawThought)
            .ifEmpty { "Menunggu persetujuan pengguna sebelum melanjutkan tindakan." }
    }

    fun findElementByIndex(
        elements: List<PrunedUiElement>,
        index: Int?
    ): PrunedUiElement? {
        if (index == null) return null
        return elements.firstOrNull { it.index == index }
    }

    fun findElementMatchingLabel(
        elements: List<PrunedUiElement>,
        label: String
    ): PrunedUiElement? {
        val norm = label.trim().lowercase()
        if (norm.isEmpty()) return null
        val exactClickable = elements.firstOrNull {
            it.isClickable && it.text.trim().lowercase() == norm
        }
        if (exactClickable != null) return exactClickable

        val partialClickable = elements.firstOrNull {
            it.isClickable && it.text.lowercase().contains(norm)
        }
        if (partialClickable != null) return partialClickable

        return elements.firstOrNull { it.text.lowercase().contains(norm) }
    }

    suspend fun logStepTelemetry(
        sessionId: String,
        step: Int,
        goal: String,
        packageName: String,
        rawNodes: Int,
        prunedNodes: Int,
        ratio: Double,
        llmMs: Int,
        totalMs: Int,
        actionType: String,
        outcome: String
    ) {
        val entry = TelemetryEntry(
            sessionId = sessionId,
            stepNumber = step,
            userGoal = goal,
            packageName = packageName,
            rawNodeCount = rawNodes,
            prunedNodeCount = prunedNodes,
            pruningRatio = ratio,
            llmLatencyMs = llmMs,
            totalStepLatencyMs = totalMs,
            actionType = actionType,
            verificationOutcome = outcome,
            createdAt = System.currentTimeMillis()
        )
        memoryPort.logTelemetry(entry)
        val recent = memoryPort.getRecentTelemetryLogs(limit = 50)
        goalHost.updateStateDirectly { it.copy(telemetryHistory = recent) }
    }
}
