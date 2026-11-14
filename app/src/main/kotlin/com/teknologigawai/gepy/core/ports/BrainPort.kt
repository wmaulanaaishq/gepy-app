package com.teknologigawai.gepy.core.ports

import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/** Hasil evaluasi *Goal Completion Verifier* terhadap klaim `DONE` dari agen. */
data class GoalVerificationVerdict(
    val achieved: Boolean,
    val missingReason: String = "",
    val proofSummary: String? = null
)

/**
 * Port Hexagonal untuk otak penalaran agen (*BrainPort*).
 *
 * Memungkinkan penggantian mesin kecerdasan dari *Single-Agent ReAct*
 * (`SingleAgentReActAdapter`) ke *Multi-Agent Pipeline* (`MultiAgentMinitapAdapter`)
 * tanpa menyentuh kode orkestrator inti.
 */
interface BrainPort {
    /**
     * Memutuskan satu tindakan UI deterministik berikutnya berdasarkan tujuan
     * pengguna [userGoal], elemen layar [screenElements], dan memori sesi [memory].
     */
    suspend fun decideNextAction(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot,
        injectedDomainKnowledge: String? = null
    ): ReActActionModel

    /**
     * Varian **Gepy v5.0 Sovereign Superintelligence** yang mendukung:
     * - [visualSoMImageBytes]: Tangkapan layar beranotasi *Set-of-Marks* (`JPEG`) untuk layar kanvas/buta XML.
     * - [personalContextSummary]: Ringkasan fakta profil & preferensi pemilik dari `UserMemoryManager`.
     * - [personaDirective]: Direktif karakter aktif (*Gepy Taktis*, *Mentor Tegas*, atau *Girlfriend Manja*).
     */
    suspend fun decideNextAction(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot,
        injectedDomainKnowledge: String?,
        visualSoMImageBytes: ByteArray?,
        personalContextSummary: String?,
        personaDirective: String?
    ): ReActActionModel = decideNextAction(
        userGoal = userGoal,
        currentPackage = currentPackage,
        screenElements = screenElements,
        memory = memory,
        injectedDomainKnowledge = listOfNotNull(personaDirective, personalContextSummary, injectedDomainKnowledge)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifEmpty { null }
    )

    /**
     * Varian **Cross-Modal Multi-Turn Context** yang menyertakan riwayat percakapan
     * [conversationHistory] (`(role, content)`) lintas Chat & Gemini Live Call.
     */
    suspend fun decideNextAction(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot,
        injectedDomainKnowledge: String?,
        visualSoMImageBytes: ByteArray?,
        personalContextSummary: String?,
        personaDirective: String?,
        conversationHistory: List<Pair<String, String>>
    ): ReActActionModel = decideNextAction(
        userGoal = userGoal,
        currentPackage = currentPackage,
        screenElements = screenElements,
        memory = memory,
        injectedDomainKnowledge = injectedDomainKnowledge,
        visualSoMImageBytes = visualSoMImageBytes,
        personalContextSummary = personalContextSummary,
        personaDirective = personaDirective
    )

    /**
     * Menghasilkan balasan percakapan Bahasa Indonesia alami untuk obrolan multi-turn
     * tanpa menjalankan loop klik UI, dengan mempertimbangkan [conversationHistory]
     * lintas Chat & Live Call, [personalContextSummary], dan [personaDirective].
     */
    suspend fun generateConversationalReply(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        personalContextSummary: String? = null,
        personaDirective: String? = null
    ): String = "Siap, aku mengerti: $userMessage"

    /**
     * Memverifikasi secara semantik apakah seluruh sub-tujuan [userGoal] benar-benar telah
     * tercapai berdasarkan keadaan layar akhir [screenElements] dan jejak langkah [memory].
     */
    suspend fun verifyGoalCompletion(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): GoalVerificationVerdict = GoalVerificationVerdict(achieved = true)
}
