package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Plugin kemampuan domain untuk pemeriksaan agenda dan penjadwalan di Google Calendar.
 *
 * Menginstruksikan LLM untuk menyimpan informasi rapat/jadwal ke *Scratchpad Memory*
 * saat menjalankan skenario lintas aplikasi (misal Kalender -> WhatsApp).
 */
class CalendarSchedulerSkill(
    private val sovereignDirectExecutionProvider: (() -> Boolean)? = null
) : GepySkillPlugin {
    override val skillId: String = "calendar_scheduler"

    override val supportedPackages: List<String> = listOf(
        "com.google.android.calendar",
        "com.samsung.android.calendar"
    )

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        val lower = transcript.lowercase()
        return lower.contains("kalender") ||
            lower.contains("calendar") ||
            lower.contains("jadwal") ||
            lower.contains("agenda") ||
            lower.contains("rapat")
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: GOOGLE CALENDAR (com.google.android.calendar)]
1. Saat diminta mengecek jadwal/rapat untuk dikirim ke aplikasi lain (seperti WhatsApp), baca judul acara serta rentang jamnya dari daftar jadwal, lalu WAJIB simpan rangkumannya ke field "save_to_scratchpad" sebelum berpindah aplikasi dengan "LAUNCH_APP".
2. Jika acara yang dicari belum terlihat pada layar saat ini, gunakan "SCROLL" dengan arah "DOWN" untuk menelusuri agenda berikutnya.
3. Jika pengguna meminta membuat atau menyimpan jadwal baru, wajib gunakan "CONFIRM_AND_CLICK" sebelum menekan tombol "Simpan" / "Save" (`com.google.android.calendar:id/save`).
        """.trimIndent()

    override suspend fun validateBeforeAction(
        proposedAction: ReActActionModel,
        screenElements: List<PrunedUiElement>
    ): ReActActionModel {
        if (sovereignDirectExecutionProvider?.invoke() == true) {
            return proposedAction
        }
        if (proposedAction.action == AgentActionType.CLICK && proposedAction.targetIndex != null) {
            val match = screenElements.firstOrNull { it.index == proposedAction.targetIndex }
            if (match != null) {
                val id = (match.viewResourceId ?: match.nodeId).lowercase()
                val label = match.text.lowercase()
                if (id.endsWith(":id/save") || label == "simpan" || label == "save") {
                    return proposedAction.copy(
                        action = AgentActionType.CONFIRM_AND_CLICK,
                        confirmationPrompt = proposedAction.confirmationPrompt
                            ?: "Simpan perubahan jadwal di Google Calendar?"
                    )
                }
            }
        }
        return proposedAction
    }
}
