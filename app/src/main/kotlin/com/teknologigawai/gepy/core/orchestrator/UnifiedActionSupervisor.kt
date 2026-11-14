package com.teknologigawai.gepy.core.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Status siklus supervisi eksekusi pada **Unified Action Supervisor (Gepy v5.4)**.
 */
enum class SupervisorState {
    IDLE,
    RUNNING,
    PAUSED,
    ABORTED
}

/**
 * Snapshot status tugas terkini untuk dilaporkan ke **Gemini 3.8 Live** (`get_active_task_status`).
 */
data class LiveTaskSnapshot(
    val state: SupervisorState,
    val activeGoal: String,
    val currentStep: Int,
    val maxSteps: Int,
    val currentPackage: String,
    val lastAction: String,
    val lastThought: String,
    val plannedSubgoals: List<String>,
    val isPaused: Boolean,
    val pauseReason: String?
) {
    fun toToolResponseMap(): Map<String, Any?> {
        return mapOf(
            "state" to state.name,
            "active_goal" to activeGoal,
            "current_step" to currentStep,
            "max_steps" to maxSteps,
            "current_package" to currentPackage,
            "last_action" to lastAction,
            "last_thought" to lastThought,
            "planned_subgoals" to plannedSubgoals,
            "is_paused" to isPaused,
            "pause_reason" to pauseReason,
            "summary" to buildHumanReadableSummary()
        )
    }

    private fun buildHumanReadableSummary(): String {
        if (state == SupervisorState.IDLE) return "Tidak ada tugas UI yang sedang berjalan."
        if (isPaused) return "Tugas dijeda sementara: '$activeGoal' (${pauseReason ?: "menunggu konfirmasi suara"})."
        return "Sedang menjalankan: '$activeGoal' pada aplikasi $currentPackage (langkah $currentStep dari $maxSteps). Aksi terakhir: $lastAction."
    }
}

/**
 * Pengawas terpadu (*Unified Action Supervisor*) yang mengoordinasikan interaksi antara
 * panggilan suara dua arah (*Gemini 3.8 Live*) dan eksekusi otomasi UI (*ReAct FSM*).
 *
 * Mencegah *race condition* melalui:
 * 1. **Cooperative Preemptive Pause**: Gemini Live dapat menjeda ReAct di batas aksi aman
 *    untuk bertanya pada pengguna tanpa membatalkan tugas.
 * 2. **State Transparency**: Gemini Live dapat menginspeksi apa yang terjadi di layar secara instan.
 * 3. **Action Barrier Mutex**: Menjamin tidak ada aksi ganda yang dieksekusi secara tumpang tindih.
 */
class UnifiedActionSupervisor {

    private val _snapshotFlow = MutableStateFlow(
        LiveTaskSnapshot(
            state = SupervisorState.IDLE,
            activeGoal = "",
            currentStep = 0,
            maxSteps = 15,
            currentPackage = "",
            lastAction = "",
            lastThought = "",
            plannedSubgoals = emptyList(),
            isPaused = false,
            pauseReason = null
        )
    )
    val snapshotFlow: StateFlow<LiveTaskSnapshot> = _snapshotFlow.asStateFlow()

    private val actionBarrierMutex = Mutex()
    private val pauseLock = Any()

    @Volatile
    private var resumeSignal: (() -> Unit)? = null

    fun onGoalStarted(goal: String, subgoals: List<String>, maxSteps: Int = 15) {
        _snapshotFlow.update {
            it.copy(
                state = SupervisorState.RUNNING,
                activeGoal = goal.trim(),
                currentStep = 1,
                maxSteps = maxSteps,
                currentPackage = "",
                lastAction = "START",
                lastThought = "Memulai tujuan: \"$goal\"",
                plannedSubgoals = subgoals,
                isPaused = false,
                pauseReason = null
            )
        }
    }

    fun onStepStarted(step: Int, currentPackage: String) {
        _snapshotFlow.update {
            it.copy(
                currentStep = step,
                currentPackage = currentPackage
            )
        }
    }

    fun onActionCompleted(actionLabel: String, thought: String) {
        _snapshotFlow.update {
            it.copy(
                lastAction = actionLabel,
                lastThought = thought
            )
        }
    }

    fun onGoalCompleted(outcomeMessage: String) {
        _snapshotFlow.update {
            it.copy(
                state = SupervisorState.IDLE,
                lastAction = "COMPLETED",
                lastThought = outcomeMessage,
                isPaused = false,
                pauseReason = null
            )
        }
    }

    /**
     * Menjeda eksekusi ReAct secara kooperatif pada batas langkah berikutnya.
     */
    fun pauseForVoiceIntervention(reason: String): Boolean {
        if (_snapshotFlow.value.state != SupervisorState.RUNNING) return false
        _snapshotFlow.update {
            it.copy(
                state = SupervisorState.PAUSED,
                isPaused = true,
                pauseReason = reason
            )
        }
        return true
    }

    /**
     * Melanjutkan kembali eksekusi ReAct yang sedang dijeda.
     */
    fun resumeExecution(): Boolean {
        if (!_snapshotFlow.value.isPaused) return false
        _snapshotFlow.update {
            it.copy(
                state = SupervisorState.RUNNING,
                isPaused = false,
                pauseReason = null
            )
        }
        synchronized(pauseLock) {
            resumeSignal?.invoke()
            resumeSignal = null
        }
        return true
    }

    /**
     * Membatalkan tujuan yang sedang berjalan.
     */
    fun abortGoal(reason: String) {
        _snapshotFlow.update {
            it.copy(
                state = SupervisorState.ABORTED,
                isPaused = false,
                pauseReason = reason,
                lastThought = "Tugas dibatalkan: $reason"
            )
        }
        synchronized(pauseLock) {
            resumeSignal?.invoke()
            resumeSignal = null
        }
    }

    /**
     * Batas keamanan aksi (*Action Barrier*): ReAct memanggil metode ini sebelum melakukan klik/ketik.
     * Jika tugas sedang dijeda oleh suara, coroutine akan menunda hingga dipanggil `resumeExecution()`.
     */
    suspend fun checkActionBarrier(): Boolean {
        val current = _snapshotFlow.value
        if (current.state == SupervisorState.ABORTED) return false

        if (current.isPaused) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                synchronized(pauseLock) {
                    resumeSignal = {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }

        return _snapshotFlow.value.state != SupervisorState.ABORTED
    }

    suspend fun <T> withActionLock(block: suspend () -> T): T {
        return actionBarrierMutex.withLock {
            block()
        }
    }

    fun getCurrentSnapshot(): LiveTaskSnapshot = _snapshotFlow.value
}
