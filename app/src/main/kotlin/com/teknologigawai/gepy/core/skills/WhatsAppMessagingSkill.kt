package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Plugin kemampuan domain khusus untuk pengiriman dan pembacaan pesan WhatsApp.
 *
 * Menyuntikkan peta identitas `resource-id` resmi WhatsApp Android serta
 * menegakkan disambiguasi kontak kembar dan konfirmasi `CONFIRM_AND_CLICK`
 * sebelum menekan tombol kirim pesan.
 */
class WhatsAppMessagingSkill(
    private val sovereignDirectExecutionProvider: (() -> Boolean)? = null,
    private val speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) : GepySkillPlugin {
    override val skillId: String = "whatsapp_messaging"

    override val supportedPackages: List<String> = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    private val waWordRegex = Regex("""\bwa\b""", RegexOption.IGNORE_CASE)
    private val otherPlatformRegex = Regex(
        """\b(instagram|ig|insta|dm|telegram|tele|tiktok|tik\s*tok|email|gmail|sms|facebook|fb|twitter|threads|youtube|yt|shopee|tokopedia)\b""",
        RegexOption.IGNORE_CASE
    )
    private val fastPath = WhatsAppFastPath(
        supportedPackages = supportedPackages,
        speechContextProvider = speechContextProvider
    )

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        val lower = transcript.lowercase()
        if (lower.contains("whatsapp") || waWordRegex.containsMatchIn(lower)) {
            return true
        }
        if (otherPlatformRegex.containsMatchIn(lower)) {
            return false
        }
        return lower.contains("kirim pesan") || lower.contains("chat ke")
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: WHATSAPP ANDROID TERBARU (com.whatsapp)]
1. DAFTAR OBROLAN UTAMA:
   - Periksa terlebih dahulu apakah nama kontak sasaran sudah terlihat langsung pada baris daftar obrolan (`com.whatsapp:id/conversations_row_contact_name`, misal "Joe Alif"). Jika tepat 1 kontak cocok, langsung gunakan "CLICK" pada kontak tersebut tanpa perlu membuka pencarian.
   - Jika belum terlihat di layar, ketuk bilah pencarian di bagian atas berlabel "Tanya Meta AI atau cari" / "Ask Meta AI or search" / "Cari" (`com.whatsapp:id/my_search_bar`, `com.whatsapp:id/search_holder`, atau `com.whatsapp:id/menuitem_search`).
   - DILARANG mengetuk tombol mengambang "Meta AI" (`com.whatsapp:id/fab_aux`) atau saran prompt AI ketika pengguna meminta mengirim pesan ke kontak manusia.
2. BILAH PENCARIAN AKTIF:
   - Pada kolom `EDITABLE` pencarian (`com.whatsapp:id/search_input` atau `com.whatsapp:id/search_src_text`), gunakan "SET_TEXT" dengan nama kontak yang dicari.
   - ATURAN DISAMBIGUASI KONTAK: Jika hasil pencarian menampilkan LEBIH DARI 1 kontak manusia yang cocok, WAJIB keluarkan "ASK_USER_DISAMBIGUATION" dengan seluruh nama kandidat pada array "options". Jika hanya 1 kontak manusia yang cocok, gunakan "CLICK" pada baris kontak tersebut (bukan baris "Tanya Meta AI").
3. RUANG OBROLAN & PENGIRIMAN:
   - Ketik isi pesan pada kolom `com.whatsapp:id/entry` (saat kosong berlabel `[Hint] Ketik pesan` atau `[Hint] Message`) menggunakan "SET_TEXT". Jika label `com.whatsapp:id/entry` sudah berisi teks pesan tanpa awalan `[Hint]`, JANGAN memanggil "SET_TEXT" lagi.
   - Sebelum mengetuk tombol Kirim (`com.whatsapp:id/send` atau label "Kirim" / "Send"), WAJIB gunakan "CONFIRM_AND_CLICK" dan tuliskan nama penerima serta isi pesan pada "confirmation_prompt".
   - Begitu "CONFIRM_AND_CLICK" tercatat berhasil ("CORRECT") pada "action_history" dan kolom `com.whatsapp:id/entry` kembali kosong (`[Hint] Ketik pesan`), SEGERA keluarkan aksi "DONE" dan JANGAN mengirim pesan untuk kedua kalinya.
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
                if (id.endsWith(":id/send") || label == "kirim" || label == "send") {
                    return proposedAction.copy(
                        action = AgentActionType.CONFIRM_AND_CLICK,
                        confirmationPrompt = proposedAction.confirmationPrompt
                            ?: SovereignNaturalSpeech.whatsappGenericConfirm(speechContextProvider())
                    )
                }
            }
        }
        return proposedAction
    }

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        return fastPath.propose(
            userGoal = userGoal,
            foregroundPackage = foregroundPackage,
            elements = screenElements,
            memory = memory
        )
    }
}
