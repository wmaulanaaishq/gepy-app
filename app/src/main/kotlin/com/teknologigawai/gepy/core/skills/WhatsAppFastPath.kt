package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/** Intent pengiriman pesan WhatsApp hasil ekstraksi dari perintah suara. */
data class WhatsAppMessageIntent(
    /** Nama kontak atau grup penerima (tanpa awalan `grup` dan tanda kutip). */
    val recipient: String,
    /** Isi pesan yang akan diketik. */
    val message: String
) {
    companion object {
        private val pattern = Regex(
            """\b(?:ke|kepada)\s+(.+?)\s*,?\s*(?:\b(?:bahwa|isinya|dengan isi|dengan pesan|pesannya|yang berisi|berisi|bilang)\b\s*:?\s*|:\s*)(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        private val recipientPrefix = Regex(
            """^(?:grup|group|kontak|contact)\s+""",
            RegexOption.IGNORE_CASE
        )

        private val quotes = setOf('"', '\'', '“', '”', '‘', '’')

        private val disambiguationSuffixRegex = Regex(
            """\s*\(Gunakan pilihan yang dikonfirmasi pengguna:\s*"?.*?"?\)\s*$""",
            RegexOption.IGNORE_CASE
        )

        /** Mengurai [goal] menjadi intent, atau `null` jika penerima/isi pesan tidak jelas. */
        fun tryParse(goal: String): WhatsAppMessageIntent? {
            val cleanedGoal = goal.trim().replace(disambiguationSuffixRegex, "").trim()
            val match = pattern.find(cleanedGoal) ?: return null
            var recipient = stripQuotes(match.groupValues[1])
            recipient = stripQuotes(recipient.replaceFirst(recipientPrefix, ""))
            val message = stripQuotes(match.groupValues[2])
            if (recipient.isEmpty() || recipient.length > 48 || message.isEmpty()) {
                return null
            }
            return WhatsAppMessageIntent(recipient = recipient, message = message)
        }

        private fun stripQuotes(raw: String): String {
            var s = raw.trim()
            while (s.length >= 2 && quotes.contains(s.first()) && quotes.contains(s.last())) {
                s = s.substring(1, s.length - 1).trim()
            }
            return s
        }
    }
}

/**
 * Resolver *Fast Path* deterministik untuk alur kirim pesan WhatsApp.
 *
 * Mengenali 4 layar (di luar WhatsApp, daftar obrolan, pencarian aktif, ruang
 * obrolan) dan mengusulkan aksi berikutnya tanpa LLM. Setiap ketidakpastian
 * (layar tak dikenal, judul obrolan tidak cocok, hasil pencarian kosong)
 * menghasilkan `null` sehingga orkestrator kembali ke LLM.
 */
class WhatsAppFastPath(
    val supportedPackages: List<String> = listOf("com.whatsapp", "com.whatsapp.w4b"),
    private val speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) {
    companion object {
        private const val TAG = "[Fast Path]"
        private val whitespaceRegex = Regex("""\s+""")

        private fun lastDisambiguationChoice(steps: List<StepRecord>): String? {
            for (step in steps.asReversed()) {
                if (step.action == AgentActionType.ASK_USER_DISAMBIGUATION &&
                    step.targetLabel.trim().isNotEmpty()
                ) {
                    return step.targetLabel.trim()
                }
            }
            return null
        }

        private fun contactRows(elements: List<PrunedUiElement>): List<PrunedUiElement> {
            return elements.filter { e ->
                val id = e.viewResourceId.orEmpty()
                e.text.trim().isNotEmpty() &&
                    (id.endsWith(":id/conversations_row_contact_name") ||
                        id.endsWith(":id/contactpicker_row_name"))
            }
        }

        private fun byId(elements: List<PrunedUiElement>, id: String): PrunedUiElement? {
            return elements.firstOrNull { e ->
                e.viewResourceId.orEmpty().endsWith(":id/$id")
            }
        }

        /** Nilai aktual kolom input; label `[Hint] ...` dianggap kosong. */
        private fun fieldValue(field: PrunedUiElement): String {
            val text = field.text.trim()
            return if (text.startsWith("[Hint]")) "" else text
        }

        private fun norm(raw: String): String =
            raw.trim().lowercase().replace(whitespaceRegex, " ")
    }

    /** Mengusulkan aksi berikutnya atau `null` bila tidak yakin. */
    fun propose(
        userGoal: String,
        foregroundPackage: String,
        elements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        val intent = WhatsAppMessageIntent.tryParse(userGoal) ?: return null
        val steps = memory.recentSteps
        val reflection = if (steps.isEmpty()) ReflectionStatus.INITIAL else ReflectionStatus.CORRECT

        // 1. Di luar WhatsApp: luncurkan sekali saja, sisanya serahkan ke LLM.
        if (!supportedPackages.contains(foregroundPackage)) {
            if (steps.any { it.action == AgentActionType.LAUNCH_APP }) {
                return null
            }
            return ReActActionModel(
                thought = "$TAG Membuka WhatsApp untuk mengirim pesan ke \"${intent.recipient}\".",
                reflectionOnLastStep = reflection,
                action = AgentActionType.LAUNCH_APP,
                appName = "WhatsApp"
            )
        }

        val alreadySent = steps.any {
            it.action == AgentActionType.CONFIRM_AND_CLICK &&
                it.outcomeStatus == ActionOutcome.CORRECT
        }
        val chosenName = lastDisambiguationChoice(steps)
        val targetName = chosenName ?: intent.recipient

        // 2. Ruang obrolan (kolom pesan `entry` terlihat).
        val entry = byId(elements, "entry")
        if (entry != null) {
            val entryValue = fieldValue(entry)
            if (alreadySent) {
                if (entryValue.isEmpty()) {
                    return ReActActionModel(
                        thought = "$TAG Pesan sudah terkirim dan kolom pesan kembali kosong.",
                        reflectionOnLastStep = reflection,
                        action = AgentActionType.DONE,
                        completionTts = SovereignNaturalSpeech.whatsappSent(speechContextProvider(), targetName)
                    )
                }
                return null
            }
            if (!chatMatches(elements, targetName, steps)) {
                return null
            }
            if (entryValue.isEmpty()) {
                return ReActActionModel(
                    thought = "$TAG Ruang obrolan \"$targetName\" terbuka; mengetik isi pesan.",
                    reflectionOnLastStep = reflection,
                    action = AgentActionType.SET_TEXT,
                    targetIndex = entry.index,
                    textValue = intent.message
                )
            }
            if (norm(entryValue) == norm(intent.message)) {
                val send = byId(elements, "send") ?: elements.firstOrNull { e ->
                    e.isClickable && setOf("kirim", "send").contains(norm(e.text))
                } ?: return null
                return ReActActionModel(
                    thought = "$TAG Isi pesan sudah benar; meminta konfirmasi sebelum menekan Kirim.",
                    reflectionOnLastStep = reflection,
                    action = AgentActionType.CONFIRM_AND_CLICK,
                    targetIndex = send.index,
                    confirmationPrompt = SovereignNaturalSpeech.whatsappConfirmPrompt(
                        ctx = speechContextProvider(),
                        targetName = targetName,
                        message = intent.message
                    )
                )
            }
            return null
        }

        if (alreadySent) {
            return null
        }

        // 3. Pencarian aktif.
        val searchField = byId(elements, "search_input") ?: byId(elements, "search_src_text")
        if (searchField != null) {
            val query = norm(fieldValue(searchField))
            val queryOk = query == norm(intent.recipient) || query == norm(targetName)
            if (!queryOk) {
                val lastStep = steps.lastOrNull()
                val retypedAlready = lastStep != null &&
                    lastStep.action == AgentActionType.SET_TEXT &&
                    lastStep.targetIndex == searchField.index
                if (retypedAlready) {
                    return null
                }
                return ReActActionModel(
                    thought = "$TAG Mengetik nama \"${intent.recipient}\" pada kolom pencarian.",
                    reflectionOnLastStep = reflection,
                    action = AgentActionType.SET_TEXT,
                    targetIndex = searchField.index,
                    textValue = intent.recipient
                )
            }
            return pickContactRow(elements, targetName, reflection)
        }

        // 4. Daftar obrolan utama.
        val exactRows = contactRows(elements).filter { norm(it.text) == norm(targetName) }
        if (exactRows.size == 1) {
            return clickRow(exactRows.first(), reflection)
        }
        val searchBar = byId(elements, "my_search_bar")
            ?: byId(elements, "search_holder")
            ?: byId(elements, "menuitem_search")
        if (searchBar != null) {
            return ReActActionModel(
                thought = "$TAG Kontak belum terlihat; membuka bilah pencarian.",
                reflectionOnLastStep = reflection,
                action = AgentActionType.CLICK,
                targetIndex = searchBar.index
            )
        }
        return null
    }

    private fun pickContactRow(
        elements: List<PrunedUiElement>,
        targetName: String,
        reflection: ReflectionStatus
    ): ReActActionModel? {
        val wanted = norm(targetName)
        val rows = contactRows(elements)
        val exact = rows.filter { norm(it.text) == wanted }
        if (exact.size == 1) {
            return clickRow(exact.first(), reflection)
        }
        val candidates = LinkedHashMap<String, PrunedUiElement>()
        for (row in rows) {
            if (norm(row.text).contains(wanted)) {
                candidates.putIfAbsent(row.text.trim(), row)
            }
        }
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size == 1) {
            return clickRow(candidates.values.first(), reflection)
        }
        val options = candidates.keys.take(5)
        return ReActActionModel(
            thought = "$TAG Ditemukan ${candidates.size} kontak yang cocok dengan \"$targetName\".",
            reflectionOnLastStep = reflection,
            action = AgentActionType.ASK_USER_DISAMBIGUATION,
            questionTts = SovereignNaturalSpeech.whatsappDisambiguate(speechContextProvider(), targetName),
            options = options
        )
    }

    private fun clickRow(row: PrunedUiElement, reflection: ReflectionStatus): ReActActionModel {
        return ReActActionModel(
            thought = "$TAG Membuka obrolan \"${row.text}\".",
            reflectionOnLastStep = reflection,
            action = AgentActionType.CLICK,
            targetIndex = row.index
        )
    }

    private fun chatMatches(
        elements: List<PrunedUiElement>,
        targetName: String,
        steps: List<StepRecord>
    ): Boolean {
        val wanted = norm(targetName)
        val title = byId(elements, "conversation_contact_name")
        if (title != null && title.text.trim().isNotEmpty()) {
            val shown = norm(title.text)
            return shown.contains(wanted) || wanted.contains(shown)
        }
        if (steps.isEmpty()) {
            return false
        }
        val last = steps.last()
        return last.action == AgentActionType.CLICK &&
            last.outcomeStatus == ActionOutcome.CORRECT &&
            norm(last.targetLabel).contains(wanted)
    }
}
