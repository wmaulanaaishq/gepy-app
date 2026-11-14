package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.memory.ChatTurnItem
import com.teknologigawai.gepy.core.memory.ConversationChannel
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.TelemetryEntry
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker

/** Status setiap tahap pada linimasa proses berpikir / aksi Gepy di layar utama. */
enum class ProcessStepState {
    ACTIVE,
    DONE,
    ERROR
}

/** Satu langkah proses berpikir atau tindakan Gepy yang ramah dibaca pengguna. */
data class AgentProcessStep(
    val text: String,
    val state: ProcessStepState = ProcessStepState.DONE,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Status mesin keadaan terbatas (*Finite State Machine*) orkestrator Gepy. */
enum class GepyFsmState {
    IDLE,
    LISTENING,
    OBSERVING,
    THINKING,
    DISAMBIGUATING,
    WAITING_HITL,
    EXECUTING,
    COMPLETED,
    ERROR
}

/** State imutabel orkestrator Gepy yang diobservasi langsung oleh Dashboard & Overlay HUD. */
data class GepyOrchestratorState(
    val fsmState: GepyFsmState = GepyFsmState.IDLE,
    val currentGoal: String = "",
    val currentPackage: String = "",
    val currentStep: Int = 0,
    val lastThought: String = "Siaga menunggu perintah suara.",
    val lastActionLabel: String = "-",
    val lastPruningRatio: Double = 0.0,
    val lastLlmLatencyMs: Int = 0,
    val scratchpad: Map<String, String> = emptyMap(),
    val telemetryHistory: List<TelemetryEntry> = emptyList(),
    val errorMessage: String? = null,
    val overlayActive: Boolean = false,
    val isLiveCallActive: Boolean = false,
    val fullAccessZeroHitl: Boolean = true,
    val liveVoicePersona: String = "Aoede",
    val useNativeStt: Boolean = false,
    val byokSettings: ByokSettings = ByokSettings(),
    val lastSessionId: String? = null,
    val chatHistory: List<ChatTurnItem> = emptyList(),
    val processTimeline: List<AgentProcessStep> = emptyList(),
    val goalStartedAtMs: Long = 0L,
    val goalElapsedMs: Long = 0L
) {
    /** Bernilai `true` HANYA ketika siklus otomatisasi UI / ReAct sedang aktif bekerja. */
    val isExecutingGoal: Boolean
        get() = fsmState == GepyFsmState.OBSERVING ||
            fsmState == GepyFsmState.THINKING ||
            fsmState == GepyFsmState.DISAMBIGUATING ||
            fsmState == GepyFsmState.WAITING_HITL ||
            fsmState == GepyFsmState.EXECUTING

    /** Bernilai `true` ketika sedang mengeksekusi tujuan UI atau mendengarkan STT satu-kali (bukan pasif Live Call). */
    val isBusy: Boolean
        get() = isExecutingGoal || (fsmState == GepyFsmState.LISTENING && !isLiveCallActive)
}

/** Sinyal pembatalan internal ketika tujuan aktif digantikan oleh perintah baru atau dihentikan pengguna. */
internal class GoalSupersededAbortException : Exception("Tujuan dibatalkan secara internal.")

/** Kontrak internal yang menghubungkan delegasi eksekusi ke lifecycle & state `GepyOrchestrator`. */
internal interface OrchestratorGoalHost {
    val currentState: GepyOrchestratorState
    fun isGoalEpochActiveAndNotAborted(expectedEpoch: Long): Boolean
    suspend fun ensureGoalRunActive(expectedEpoch: Long)
    fun updateStateIfGoalActive(
        expectedEpoch: Long,
        transform: (GepyOrchestratorState) -> GepyOrchestratorState
    )
    fun updateStateDirectly(transform: (GepyOrchestratorState) -> GepyOrchestratorState)
    fun currentSpeechContext(): SovereignSpeechContext
    fun buildCurrentReActPersonaDirective(): String
    fun recordChatTurn(
        role: String,
        text: String,
        channel: ConversationChannel = ConversationChannel.TEXT_CHAT
    )
    fun recordClosingAssistantTurnIfPending(text: String)
    fun cancelActiveGoal(reason: String)
    fun launchAsyncGoal(goalText: String)
    fun cancelAsyncGoalForImmediatePath()
    suspend fun triggerOnDemandBriefing(): String
}

/** Pembersih label linimasa proses berpikir & pelacak transisi langkah agen Gepy. */
internal object ProcessTimelineTracker {
    fun cleanStepTextForDisplay(raw: String): String {
        var stripped = VoiceClauseChunker.stripAudioTagsForDisplay(raw)
            .replace(Regex("""\[Playbook Fast-Path\]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\([a-z][a-z0-9_]*(\.[a-z0-9_]+)+\)"""), "")
            .replace(Regex("""\s*(?:pada|di|on)?\s*\b[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+:id/[a-zA-Z0-9_.-]+"""), "")
            .replace(Regex("""(?i)\b(?:jawab\s+langsung\s+)?(?:lewat|via|menggunakan|dalam)\s+completion_tts\b\.?"""), "")
            .replace(Regex("""(?i)\b(?:completion_tts|user_goal|screen_elements|target_index|save_to_scratchpad|reasoning_effort)\b"""), "")
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\s*[,.;:]\s*\."""), ".")
            .trim()
        if (stripped.contains("Pertanyaan kapabilitas", ignoreCase = true) && stripped.length < 40) {
            stripped = "Menyiapkan jawaban langsung..."
        }
        return if (stripped.length > 140) {
            val slice = stripped.take(137)
            val sp = slice.lastIndexOf(' ')
            (if (sp >= 70) slice.substring(0, sp) else slice).trimEnd(' ', ',', '.', ';', ':') + "..."
        } else {
            stripped
        }
    }

    fun mapActionTypeToHumanLabel(action: AgentActionType): String {
        return when (action) {
            AgentActionType.LAUNCH_APP -> "Membuka aplikasi"
            AgentActionType.CLICK -> "Mengetuk elemen layar"
            AgentActionType.LONG_PRESS -> "Menekan tahan elemen"
            AgentActionType.SET_TEXT, AgentActionType.SET_TEXT_AND_SUBMIT -> "Mengetik teks"
            AgentActionType.SCROLL -> "Menggulir layar"
            AgentActionType.SWIPE -> "Menggeser layar"
            AgentActionType.BACK -> "Kembali ke layar sebelumnya"
            AgentActionType.HOME -> "Kembali ke layar utama"
            AgentActionType.WAIT -> "Menunggu layar stabil"
            AgentActionType.CLIPBOARD_COPY -> "Menyalin teks"
            AgentActionType.CLIPBOARD_PASTE -> "Menempelkan teks"
            AgentActionType.CONFIRM_AND_CLICK -> "Menunggu konfirmasi"
            AgentActionType.ASK_USER_DISAMBIGUATION -> "Meminta pilihan pengguna"
            AgentActionType.DONE -> "Menyelesaikan tugas"
        }
    }

    fun summarizeCompletedActionForTimeline(next: GepyOrchestratorState): String? {
        val label = next.lastActionLabel.uppercase()
        return when {
            label == "CHAT" -> null
            label == "LAUNCH_APP" || label == "OPEN_APP" -> "Membuka aplikasi di layar"
            label == "WEB_SEARCH" || label == "WEB_READ" -> "Menemukan & merangkum informasi web"
            label.startsWith("API_FAST_PATH") -> "Menjalankan aksi cepat di perangkat"
            label.contains("NAV") || label.contains("SETTINGS") || label.contains("DEEP_LINK") ->
                "Membuka tujuan sistem"
            label == "DONE" -> "Memverifikasi hasil tugas selesai"
            else -> null
        }
    }

    fun applyProcessTimelineUpdate(
        prev: GepyOrchestratorState,
        next: GepyOrchestratorState
    ): GepyOrchestratorState {
        // Awal sesi/tujuan baru yang baru saja me-reset timeline
        if (next.goalStartedAtMs > 0L && prev.goalStartedAtMs != next.goalStartedAtMs) {
            return next
        }
        val finalizedPrevSteps = next.processTimeline.map {
            if (it.state == ProcessStepState.ACTIVE) it.copy(state = ProcessStepState.DONE) else it
        }
        val elapsedMs = if (
            next.goalStartedAtMs > 0L &&
            (next.fsmState == GepyFsmState.COMPLETED || next.fsmState == GepyFsmState.ERROR)
        ) {
            (System.currentTimeMillis() - next.goalStartedAtMs).coerceAtLeast(1L)
        } else {
            next.goalElapsedMs
        }

        val updatedSteps = when (next.fsmState) {
            GepyFsmState.COMPLETED -> {
                val extraSummary = summarizeCompletedActionForTimeline(next)
                if (extraSummary != null &&
                    finalizedPrevSteps.size <= 1 &&
                    finalizedPrevSteps.lastOrNull()?.text != extraSummary
                ) {
                    (finalizedPrevSteps + AgentProcessStep(extraSummary, ProcessStepState.DONE)).takeLast(12)
                } else {
                    finalizedPrevSteps
                }
            }
            GepyFsmState.ERROR -> {
                val errLine = cleanStepTextForDisplay(next.errorMessage?.takeIf { it.isNotBlank() } ?: next.lastThought)
                if (errLine.isNotEmpty() && finalizedPrevSteps.lastOrNull()?.text != errLine) {
                    (finalizedPrevSteps + AgentProcessStep(errLine, ProcessStepState.ERROR)).takeLast(12)
                } else {
                    finalizedPrevSteps
                }
            }
            else -> {
                val thoughtLine = cleanStepTextForDisplay(next.lastThought)
                val changedThought = next.lastThought != prev.lastThought && thoughtLine.isNotEmpty()
                if (changedThought && finalizedPrevSteps.lastOrNull()?.text != thoughtLine) {
                    (finalizedPrevSteps + AgentProcessStep(thoughtLine, ProcessStepState.ACTIVE)).takeLast(12)
                } else {
                    next.processTimeline
                }
            }
        }

        return next.copy(
            processTimeline = updatedSteps,
            goalElapsedMs = elapsedMs
        )
    }
}
