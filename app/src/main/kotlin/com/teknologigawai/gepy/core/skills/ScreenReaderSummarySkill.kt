package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Plugin kemampuan aksesibilitas untuk membacakan dan merangkum isi layar saat ini
 * (*Screen Reader Summary Skill*) secara lisan tanpa memerlukan ketukan interaktif.
 *
 * Dirancang untuk pengguna Personal AI Agent yang membutuhkan rangkuman cepat isi layar
 * pemahaman cepat atas informasi visual ponsel tanpa mengoperasikan TalkBack secara rumit.
 */
class ScreenReaderSummarySkill(
    private val speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) : GepySkillPlugin {
    companion object {
        private val clockRegex = Regex("""^\d{1,2}:\d{2}$""")
    }

    override val skillId: String = "screen_reader_summary"

    override val supportedPackages: List<String> = emptyList()

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        val lower = transcript.lowercase()
        val isSummaryIntent = lower.contains("bacakan layar") ||
            lower.contains("baca layar") ||
            lower.contains("ada apa di layar") ||
            lower.contains("isi layar") ||
            lower.contains("ringkas layar") ||
            lower.contains("apa di layar") ||
            lower.contains("apa yang ada di layar") ||
            (lower.contains("baca") && lower.contains("pesan"))

        // Kecualikan jika pengguna meminta mengirim pesan secara spesifik
        val isSendIntent = lower.contains("kirim") || lower.contains("balas")
        return isSummaryIntent && !isSendIntent
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: PEMBACA LAYAR AKSESIBILITAS (SCREEN READER SUMMARY)]
Ketika pengguna meminta membacakan layar atau menanyakan isi layar:
1. Rangkum informasi utama yang terlihat di layar dalam 1-2 kalimat lisan ringkas, jelas, dan santun.
2. Hindari membaca kode teknis, angka koordinat, atau resource-id.
3. Sebutkan nama aplikasi, status halaman (misal: "Percakapan dengan Budi" atau "Daftar Obrolan"), serta isi pesan atau judul utama yang paling relevan.
4. Akhiri aksi langsung dengan tipe "DONE" disertai pesan suara pada field "completion_tts".
        """.trimIndent()

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        if (!canHandleVoiceIntent(userGoal)) {
            return null
        }

        val summary = generateScreenSummary(
            foregroundPackage = foregroundPackage,
            screenElements = screenElements
        )

        return ReActActionModel(
            action = AgentActionType.DONE,
            reflectionOnLastStep = ReflectionStatus.INITIAL,
            thought = "Membacakan rangkuman isi layar secara hands-free untuk pengguna.",
            completionTts = summary
        )
    }

    /** Menghasilkan rangkuman deskriptif dalam Bahasa Indonesia dari [screenElements]. */
    fun generateScreenSummary(
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>
    ): String {
        val pkg = foregroundPackage.lowercase()
        val speechCtx = speechContextProvider()

        // 1. Kasus Layar WhatsApp
        if (pkg.contains("whatsapp")) {
            return summarizeWhatsApp(screenElements, speechCtx)
        }

        // 2. Kasus Home Screen / Launcher
        if (pkg.contains("launcher") || pkg.contains("home")) {
            val appNames = screenElements
                .filter { it.text.trim().isNotEmpty() && it.isClickable }
                .map { it.text.trim() }
                .take(5)
            return SovereignNaturalSpeech.screenSummaryLauncher(speechCtx, appNames)
        }

        // 3. Kasus Aplikasi Umum
        val meaningfulTexts = screenElements
            .map { it.text.trim() }
            .filter { t ->
                t.isNotEmpty() &&
                    !t.startsWith("[Hint]") &&
                    !clockRegex.matches(t)
            }

        if (meaningfulTexts.isEmpty()) {
            return SovereignNaturalSpeech.screenSummaryEmpty(speechCtx)
        }

        val topTexts = meaningfulTexts.take(3).joinToString(". ")
        return SovereignNaturalSpeech.screenSummaryGeneric(speechCtx, topTexts)
    }

    private fun summarizeWhatsApp(
        elements: List<PrunedUiElement>,
        speechCtx: SovereignSpeechContext
    ): String {
        val hasChatEntry = elements.any { e ->
            e.viewResourceId.orEmpty().contains("entry") ||
                e.text.lowercase().contains("ketik pesan") ||
                e.text.lowercase().contains("type a message")
        }

        if (hasChatEntry) {
            val contactTitle = elements
                .filter { e ->
                    e.viewResourceId.orEmpty().contains("conversation_contact_name") ||
                        (e.isClickable && e.index <= 5 && e.text.trim().isNotEmpty())
                }
                .map { it.text.trim() }
                .firstOrNull()

            val messages = elements
                .filter { e ->
                    e.text.trim().isNotEmpty() &&
                        !e.text.lowercase().contains("ketik pesan") &&
                        !e.text.lowercase().contains("type a message") &&
                        e.text != contactTitle
                }
                .map { it.text.trim() }

            return SovereignNaturalSpeech.screenSummaryWhatsAppChat(
                ctx = speechCtx,
                contactTitle = contactTitle,
                lastMsg = messages.lastOrNull()
            )
        }

        val chatNames = elements
            .filter { e -> e.viewResourceId.orEmpty().contains("conversations_row_contact_name") }
            .map { it.text.trim() }
            .take(3)

        if (chatNames.isNotEmpty()) {
            return SovereignNaturalSpeech.screenSummaryWhatsAppList(speechCtx, chatNames)
        }

        return SovereignNaturalSpeech.screenSummaryWhatsAppOther(speechCtx)
    }
}
