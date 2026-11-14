package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal class FakeVoiceHud(
    var hitlApprovalResponse: Boolean = true,
    var disambiguationChoice: String = ""
) : VoiceHudPort {
    override val voiceCommandFlow: Flow<String> = emptyFlow()
    override val bargeInInterruptFlow: Flow<String> = emptyFlow()

    var hitlRequestedCount: Int = 0
    val spokenCompletions = mutableListOf<String>()
    val thinkingStates = mutableListOf<Pair<String, String>>()

    override suspend fun initialize() {}
    override suspend fun showIdlePill() {}
    override suspend fun hideOverlay() {}
    override suspend fun startListening() {}
    override suspend fun showThinkingState(stepHeadline: String, thought: String) {
        thinkingStates.add(stepHeadline to thought)
    }

    override suspend fun askDisambiguation(questionTts: String, options: List<String>): String {
        return disambiguationChoice.ifEmpty { options.firstOrNull().orEmpty() }
    }

    override suspend fun requestHitlApproval(summaryPrompt: String, targetLabel: String): Boolean {
        hitlRequestedCount++
        return hitlApprovalResponse
    }

    override suspend fun finishAndSpeak(completionTts: String) {
        spokenCompletions.add(completionTts)
    }

    override fun dispose() {}
}

internal class FakeActionAdapter : ActionPort {
    var tappedElements = mutableListOf<PrunedUiElement>()

    override suspend fun waitStable(
        timeoutMs: Long,
        pollIntervalMs: Long,
        settleCount: Int,
        changedFrom: String?
    ): List<PrunedUiElement> = emptyList()

    override suspend fun tapElement(
        target: PrunedUiElement,
        preSignature: String,
        hitlApproved: Boolean
    ): ActionOutcome {
        tappedElements.add(target)
        return ActionOutcome.CORRECT
    }

    override suspend fun tapCoordinates(x: Float, y: Float): Boolean = true
    override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean = true

    override suspend fun setTextElement(
        target: PrunedUiElement,
        text: String,
        preSignature: String,
        clearExisting: Boolean
    ): ActionOutcome = ActionOutcome.CORRECT

    override suspend fun scrollScreen(
        direction: String,
        preSignature: String,
        amount: Double
    ): ActionOutcome = ActionOutcome.CORRECT

    override suspend fun swipeScreen(fingerDirection: String, distance: Double): Boolean = true

    override suspend fun scrollUntil(
        direction: String,
        maxScrolls: Int,
        predicate: (List<PrunedUiElement>) -> Boolean
    ): Boolean = true

    override suspend fun <T : Any> scrollCollect(
        maxScrolls: Int,
        endAfterUnchanged: Int,
        extractor: (PrunedUiElement) -> T?
    ): List<T> = emptyList()

    override suspend fun navigateBack(): Boolean = true
    override suspend fun navigateHome(): Boolean = true
    override suspend fun navigateRecents(): Boolean = true
}

internal class FakeBrainPort(
    private val handler: suspend () -> ReActActionModel
) : BrainPort {
    override suspend fun decideNextAction(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot,
        injectedDomainKnowledge: String?
    ): ReActActionModel = handler()
}
