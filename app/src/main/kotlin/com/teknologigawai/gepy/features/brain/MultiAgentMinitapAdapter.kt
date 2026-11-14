package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Implementasi `BrainPort` masa depan menggunakan arsitektur tiga peran
 * ala *Minitap mobile-use* / *Mobile-Agent-v2* (`Planner -> Cortex/Grounder -> Reflector`).
 *
 * Karena port menggunakan antarmuka `BrainPort` yang sama, `GepyOrchestrator`
 * dapat beralih dari `SingleAgentReActAdapter` ke `MultiAgentMinitapAdapter`
 * hanya dengan mengganti injeksi konstruktor tanpa memodifikasi kode orkestrator.
 */
class MultiAgentMinitapAdapter(
    private val cortexDelegate: BrainPort
) : BrainPort {

    private var cachedSubgoals: List<String> = emptyList()
    private var lastPlannedGoal: String? = null

    override suspend fun decideNextAction(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot,
        injectedDomainKnowledge: String?
    ): ReActActionModel {
        // 1. Peran Planner: dekomposisi tujuan multi-langkah jika instruksi berubah.
        if (lastPlannedGoal != userGoal || cachedSubgoals.isEmpty()) {
            cachedSubgoals = decomposeSubgoals(userGoal)
            lastPlannedGoal = userGoal
        }

        // 2. Peran Reflector: tentukan subgoal aktif berdasarkan progres langkah yang berhasil.
        val completedCount = memory.recentSteps.count {
            it.outcomeStatus == ActionOutcome.CORRECT
        }
        val activeSubgoalIndex = if (completedCount < cachedSubgoals.size) {
            completedCount
        } else {
            cachedSubgoals.size - 1
        }
        val activeSubgoal = cachedSubgoals[activeSubgoalIndex]

        val enrichedKnowledge = buildString {
            if (!injectedDomainKnowledge.isNullOrBlank()) {
                append(injectedDomainKnowledge)
                append(" | ")
            }
            append(
                "[MULTI-AGENT PLANNER SUBGOALS] Subgoal aktif [${activeSubgoalIndex + 1}/${cachedSubgoals.size}]: $activeSubgoal"
            )
        }

        // 3. Peran Cortex/Grounder: eksekusi keputusan pada elemen berindeks Set-of-Marks.
        return cortexDelegate.decideNextAction(
            userGoal = userGoal,
            currentPackage = currentPackage,
            screenElements = screenElements,
            memory = memory,
            injectedDomainKnowledge = enrichedKnowledge
        )
    }

    /** Memecah kalimat instruksi majemuk menjadi daftar sub-tujuan berurutan. */
    fun decomposeSubgoals(userGoal: String): List<String> {
        val parts = userGoal
            .split(Regex("\\s+(?:lalu|kemudian| terus |dan kirim|serta)\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(userGoal.trim()) else parts
    }
}
