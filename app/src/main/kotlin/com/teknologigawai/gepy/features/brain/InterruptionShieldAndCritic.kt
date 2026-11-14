package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Modul SOTA **Deterministic Interruption Shield & Single-State Candidate Critic** (`0 token`, `< 1 ms`):
 * - Terinspirasi dari *Android Coach: Single State Multiple Actions* (`arXiv:2604.07277v2`)
 *   dan *MobileAgentBench* (`arXiv:2406.08184v2`).
 * - Menangani dua titik kegagalan utama agen GUI mobile secara lokal tanpa biaya inferensi:
 *   1. [detectBlockingPopupDismissal]: Menutup dialog promosi/iklan/rating pengganggu secara deterministik.
 *   2. [refineCandidateAction]: Memvalidasi dan mengoreksi pemilihan `targetIndex` pra-eksekusi apabila
 *      LLM menunjuk label teks pasif di sebelah kontainer tombol atau menunjuk label non-editable saat mengetik.
 */
object InterruptionShieldAndCritic {

    /** Label tombol penutup pop-up yang dijamin aman dan non-destruktif terhadap data pengguna. */
    private val UNAMBIGUOUS_POPUP_DISMISS_LABELS = setOf(
        "lain kali",
        "nanti saja",
        "jangan sekarang",
        "tutup iklan",
        "lewati iklan",
        "abaikan iklan",
        "not now",
        "no thanks",
        "maybe later",
        "skip ad",
        "close ad"
    )

    /** Konteks teks dialog modal yang menandakan interupsi non-tujuan (iklan, rating, pengingat update). */
    private val POPUP_CONTEXT_KEYWORDS = listOf(
        "beri nilai",
        "beri rating",
        "rate this app",
        "enjoying",
        "iklan",
        "sponsored",
        "promo",
        "diskon spesial",
        "aktifkan notifikasi",
        "turn on notifications",
        "pembaruan tersedia",
        "update available",
        "coba gratis",
        "free trial"
    )

    /**
     * Memeriksa apakah layar saat ini terhalang oleh pop-up iklan/promosi/rating yang tidak berkaitan
     * dengan [userGoal], dan mengembalikan aksi penutupan instan (`0 token`) jika aman.
     */
    fun detectBlockingPopupDismissal(
        currentPackage: String,
        elements: List<PrunedUiElement>,
        userGoal: String
    ): ReActActionModel? {
        if (elements.isEmpty() || SafetyRules.isPackageForbidden(currentPackage)) {
            return null
        }
        val goalLower = userGoal.trim().lowercase()

        // Cari kandidat tombol penutup eksplisit yang clickable
        val dismissCandidate = elements.firstOrNull { el ->
            if (!el.isClickable || el.isEditable) return@firstOrNull false
            val cleanLabel = el.text.trim().lowercase()
            if (cleanLabel.isEmpty()) return@firstOrNull false
            val matchesSafeDismiss = cleanLabel in UNAMBIGUOUS_POPUP_DISMISS_LABELS
            if (!matchesSafeDismiss) return@firstOrNull false
            if (SafetyRules.requiresHitlConfirmation(el.text, el.viewResourceId)) return@firstOrNull false
            // Jangan tutup jika pengguna justru menyebut kata tersebut di dalam instruksinya
            !goalLower.contains(cleanLabel)
        } ?: return null

        // Untuk label khusus iklan ("tutup iklan", "lewati iklan", "skip ad", "close ad"), langsung eksekusi.
        // Untuk label umum ("lain kali", "not now", dll.), pastikan layar memang menunjukkan konteks pop-up
        // atau jumlah elemen sedikit (ciri khas modal dialog di atas aplikasi).
        val dismissLower = dismissCandidate.text.trim().lowercase()
        val isExplicitAdClose = dismissLower.contains("iklan") || dismissLower.contains("ad")
        val hasPopupContext = elements.size <= 16 || elements.any { el ->
            val t = el.text.lowercase()
            POPUP_CONTEXT_KEYWORDS.any { kw -> t.contains(kw) }
        }

        if (!isExplicitAdClose && !hasPopupContext) {
            return null
        }

        return ReActActionModel(
            thought = "[Interruption Shield] Menutup pop-up pengganggu (\"${dismissCandidate.text.trim()}\") agar tugas utama dapat berlanjut.",
            reflectionOnLastStep = ReflectionStatus.CORRECT,
            expectedOutcome = "Dialog pop-up tertutup dan layar utama aplikasi kembali terlihat",
            action = AgentActionType.CLICK,
            targetIndex = dismissCandidate.index
        )
    }

    /**
     * *Single-State Candidate Critic* (`0 ms`): memvalidasi kecocokan kapabilitas elemen target
     * dengan jenis aksi sebelum dikirim ke `ActionPort`.
     */
    fun refineCandidateAction(
        decision: ReActActionModel,
        elements: List<PrunedUiElement>
    ): ReActActionModel {
        if (elements.isEmpty()) return decision

        return when (decision.action) {
            AgentActionType.CLICK,
            AgentActionType.CONFIRM_AND_CLICK -> refineClickCandidate(decision, elements)

            AgentActionType.SET_TEXT,
            AgentActionType.SET_TEXT_AND_SUBMIT,
            AgentActionType.CLIPBOARD_PASTE -> refineSetTextCandidate(decision, elements)

            else -> decision
        }
    }

    private fun refineClickCandidate(
        decision: ReActActionModel,
        elements: List<PrunedUiElement>
    ): ReActActionModel {
        val idx = decision.targetIndex ?: return decision
        val selected = elements.firstOrNull { it.index == idx } ?: return decision
        if (selected.isClickable) return decision

        val selectedTextNorm = selected.text.trim().lowercase()

        // Prioritas 1: Elemen clickable lain di layar dengan label semantik yang persis sama
        if (selectedTextNorm.isNotEmpty()) {
            val exactClickableMatch = elements.firstOrNull { el ->
                el.index != selected.index &&
                    el.isClickable &&
                    el.text.trim().lowercase() == selectedTextNorm &&
                    !SafetyRules.isPackageForbidden(el.packageName)
            }
            if (exactClickableMatch != null) {
                return upgradeActionForTarget(decision, exactClickableMatch)
            }
        }

        // Prioritas 2: Kontainer/tombol clickable terdekat pada baris horizontal yang sama (deltaY <= 84px)
        val spatialNeighbor = elements
            .filter { el ->
                el.index != selected.index &&
                    el.isClickable &&
                    !SafetyRules.isPackageForbidden(el.packageName) &&
                    abs(el.centerY - selected.centerY) <= 84 &&
                    abs(el.centerX - selected.centerX) <= 280
            }
            .minByOrNull { el ->
                hypot(
                    (el.centerX - selected.centerX).toDouble(),
                    (el.centerY - selected.centerY).toDouble()
                )
            }

        if (spatialNeighbor != null &&
            !SafetyRules.requiresHitlConfirmation(spatialNeighbor.text, spatialNeighbor.viewResourceId)
        ) {
            return decision.copy(targetIndex = spatialNeighbor.index)
        }

        return decision
    }

    private fun refineSetTextCandidate(
        decision: ReActActionModel,
        elements: List<PrunedUiElement>
    ): ReActActionModel {
        val selected = decision.targetIndex?.let { idx -> elements.firstOrNull { it.index == idx } }
        if (selected != null && selected.isEditable) {
            return decision
        }

        val safeEditables = elements.filter { el ->
            el.isEditable &&
                !SafetyRules.isPackageForbidden(el.packageName) &&
                !SafetyRules.isCredentialField(el.text, el.viewResourceId ?: el.nodeId)
        }
        if (safeEditables.isEmpty()) return decision

        val bestEditable = if (selected != null) {
            safeEditables.minByOrNull { el ->
                hypot(
                    (el.centerX - selected.centerX).toDouble(),
                    (el.centerY - selected.centerY).toDouble()
                )
            }
        } else {
            val isSearchIntent = decision.action == AgentActionType.SET_TEXT_AND_SUBMIT ||
                decision.thought.contains("cari", ignoreCase = true) ||
                decision.thought.contains("search", ignoreCase = true)
            if (isSearchIntent) {
                safeEditables.firstOrNull { el ->
                    val combined = "${el.text} ${el.viewResourceId.orEmpty()}".lowercase()
                    combined.contains("cari") || combined.contains("search") || combined.contains("telusuri")
                } ?: safeEditables.first()
            } else {
                safeEditables.first()
            }
        } ?: return decision

        return decision.copy(targetIndex = bestEditable.index)
    }

    private fun upgradeActionForTarget(
        decision: ReActActionModel,
        newTarget: PrunedUiElement
    ): ReActActionModel {
        val requiresHitl = SafetyRules.requiresHitlConfirmation(
            label = newTarget.text,
            resourceId = newTarget.viewResourceId ?: newTarget.nodeId
        )
        return if (requiresHitl && decision.action == AgentActionType.CLICK) {
            decision.copy(
                action = AgentActionType.CONFIRM_AND_CLICK,
                targetIndex = newTarget.index,
                confirmationPrompt = decision.confirmationPrompt
                    ?: "Konfirmasi mengetuk tombol \"${newTarget.text.ifEmpty { newTarget.nodeId }}\"?"
            )
        } else {
            decision.copy(targetIndex = newTarget.index)
        }
    }
}
