package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.speech.IndonesianSpeechNormalizer
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.service.NotificationOpStatus
import java.util.Locale

/** Hasil penguraian deterministik perintah pemasangan alarm bahasa Indonesia. */
data class ParsedAlarmRequest(
    val hour: Int,
    val minute: Int,
    val label: String,
    val usedDefaultMorningTime: Boolean = false
)

/** Hasil penguraian perintah senter kamera (`on` / `off`). */
data class ParsedFlashlightRequest(val enabled: Boolean)

/** Hasil penguraian perintah timer hitung mundur (`durationSeconds` dalam `1..86400`). */
data class ParsedTimerRequest(val durationSeconds: Int, val label: String)

/** Hasil penguraian kontrol volume audio atau mode dering HP. */
sealed class ParsedAudioSystemRequest {
    data class Volume(
        val stream: String,
        val targetPercent: Int? = null,
        val direction: String? = null
    ) : ParsedAudioSystemRequest()

    data class RingerMode(val mode: String) : ParsedAudioSystemRequest()
}

/** Hasil penguraian perintah membuka dialer telepon (`Intent.ACTION_DIAL`). */
data class ParsedDialRequest(val targetQuery: String)

/** Hasil penguraian perintah membuka komposer draf email (`mailto:`). */
data class ParsedEmailComposeRequest(
    val recipientQuery: String,
    val subject: String = "",
    val body: String = ""
)

/** Hasil penguraian perintah membuka panel pengaturan mengambang (`Settings.Panel.*`). */
data class ParsedFloatingPanelRequest(val panelKeyword: String)

/** Hasil penguraian perintah menghapus notifikasi aktif (`NotificationListenerService`). */
data class ParsedNotificationDismissRequest(val targetQuery: String)

/** Hasil penguraian perintah balas cepat notifikasi latar belakang (`RemoteInput`). */
data class ParsedNotificationReplyRequest(
    val targetQuery: String,
    val replyMessage: String,
    val explicitAppKeyword: String? = null
)

/**
 * Plugin *Fast-Path Skill* untuk akses langsung ke informasi dan utilitas sistem OS Android
 * (`< 15 ms`, `0` panggilan LLM Cloud) berdasarkan arsitektur **API-First, UI-Fallback**:
 * - Pemasangan alarm (`ACTION_SET_ALARM`), cek daftar alarm (`ACTION_SHOW_ALARMS`), & timer (`ACTION_SET_TIMER`)
 * - Senter hardware (`CameraManager.setTorchMode`), volume & mode dering (`AudioManager`)
 * - Dialer telepon (`ACTION_DIAL`), komposer email (`ACTION_SENDTO`), & panel mengambang (`Settings.Panel.*`)
 * - Notifikasi real-time terpadu (baca, balas cepat `RemoteInput` + UI fallback, bersihkan notifikasi)
 * - Informasi lagu/media sedang diputar (`MediaSessionManager`)
 * - Status baterai, jam/tanggal, langkah kesehatan CSI, & lokasi GPS
 */
class AndroidSystemToolSkill(
    private val systemBridge: AndroidSystemToolsBridge = AndroidSystemToolsBridge(),
    private val speechContextProvider: () -> SovereignSpeechContext = { systemBridge.speechContextProvider() },
    private val preferredWakeTimeProvider: () -> Pair<Int, Int>? = { null },
    private val sovereignDirectExecutionProvider: () -> Boolean = { true }
) : GepySkillPlugin {

    companion object {
        const val DEFAULT_MORNING_ALARM_HOUR = 5
        const val DEFAULT_MORNING_ALARM_MINUTE = 0

        private val informationalQuestionExclusionRegex = Regex(
            """\b(cara(?:nya)?|bagaimana|gimana\s+cara|mengapa|kenapa|apa\s+itu|apa\s+maksud|apa\s+fungsi|fungsi\s+dari|apa\s+beda(?:nya)?|perbedaan|tutorial|pengertian|definisi)\b""",
            RegexOption.IGNORE_CASE
        )
        private val laptopTargetRegex = Regex(
            """\b(laptop|pc|komputer|windows)\b""",
            RegexOption.IGNORE_CASE
        )
        private val messagingCallExclusionRegex = Regex(
            """\b(wa|whatsapp|telegram|tele|ig|instagram|messenger|discord|line|video\s+call|vc|vidcall|meet|zoom)\b""",
            RegexOption.IGNORE_CASE
        )

        private val deviceOrTimeRegex = Regex(
            """\b(baterai|batre|battery|jam berapa|tanggal berapa|hari apa|status perangkat|status hp|sinyal|wifi|wi-fi)\b""",
            RegexOption.IGNORE_CASE
        )
        private val notificationRegex = Regex(
            """\b(ada notifikasi|baca notifikasi|notifikasi baru|notifikasi masuk|cek notifikasi|notifikasi apa|ada notif|baca notif|cek notif|notif baru|notif masuk|notif apa)\b""",
            RegexOption.IGNORE_CASE
        )
        private val locationRegex = Regex(
            """\b(lokasi saya|posisi saya|koordinat gps|aku di mana sekarang|saya di mana sekarang)\b""",
            RegexOption.IGNORE_CASE
        )
        private val wellnessRegex = Regex(
            """\b(langkah kaki|berapa langkah|data kesehatan|status kesehatan|data gerak|durasi rebahan|status csi)\b""",
            RegexOption.IGNORE_CASE
        )

        // Batch 1: Senter, Timer, Lihat Alarm, Volume/Ringer, Dialer, Email
        private val flashlightNounRegex = Regex(
            """\b(senter|lampu\s+senter|flashlight|torch)\b""",
            RegexOption.IGNORE_CASE
        )
        private val flashlightOffCueRegex = Regex(
            """\b(mati(?:in|kan)?|padam(?:kan)?|nonaktif(?:in|kan)?|tutup|off)\b""",
            RegexOption.IGNORE_CASE
        )
        private val flashlightOnCueRegex = Regex(
            """\b(nyala(?:in|kan)?|hidup(?:in|kan)?|aktif(?:in|kan)?|buka|on)\b""",
            RegexOption.IGNORE_CASE
        )

        private val timerKeywordRegex = Regex(
            """\b(timer|penghitung\s+waktu|hitung\s+mundur)\b""",
            RegexOption.IGNORE_CASE
        )
        private val relativeReminderCueRegex = Regex(
            """\b(?:ingat(?:in|kan)?|ingetin|ingatkan|pengingat|remind(?:er)?)\b""",
            RegexOption.IGNORE_CASE
        )
        private val relativeTimeWindowRegex = Regex(
            """\b(?:(?:(\d+)\s*(?:menit|jam|detik)|setengah\s+jam)\s+(?:lagi|dari\s+sekarang|ke\s+depan|kemudian)|dalam\s+(\d+)\s*(?:menit|jam|detik))\b""",
            RegexOption.IGNORE_CASE
        )
        private val relativeAlarmCueRegex = Regex(
            """\b(?:pasang|set|setel|bikin|buat)\s+(?:alarm|weker)\s+(\d+\s*(?:menit|jam|detik)|setengah\s+jam)\s+lagi\b""",
            RegexOption.IGNORE_CASE
        )
        private val reminderDirectLabelRegex = Regex(
            """\b(?:ingat(?:in|kan)?|ingetin|ingatkan)\s+(?:aku\s+|saya\s+)?(?:untuk\s+|buat\s+)?([a-zA-Z0-9]+(?:\s+[a-zA-Z0-9]+){0,3})""",
            RegexOption.IGNORE_CASE
        )
        private val timerExclusionRegex = Regex(
            """\b(hapus|delete|matikan|nonaktifkan|batalkan|cancel|reset|cek|lihat|berapa)\b""",
            RegexOption.IGNORE_CASE
        )
        private val timerHoursRegex = Regex("""(\d+)\s*jam\b""", RegexOption.IGNORE_CASE)
        private val timerMinutesRegex = Regex("""(\d+)\s*menit\b""", RegexOption.IGNORE_CASE)
        private val timerSecondsRegex = Regex("""(\d+)\s*detik\b""", RegexOption.IGNORE_CASE)
        private val timerHalfHourRegex = Regex("""\bsetengah\s+jam\b""", RegexOption.IGNORE_CASE)

        private val showAlarmsCueRegex = Regex(
            """\b(cek|lihat|tampil(?:kan)?|buka(?:kan)?|daftar|list|apa\s+aja|ada\s+berapa|jam\s+berapa|aktif|terdekat|kapan)\b""",
            RegexOption.IGNORE_CASE
        )

        private val ringerModeRegex = Regex(
            """(?:\bmode\s+(getar|vibrate|senyap|silent|hening|diam|normal|dering)\b|\b(?:aktifkan|nyalakan|ubah(?:\s+ke)?|ganti(?:\s+ke)?|pindah(?:\s+ke)?|set(?:el)?|masuk(?:\s+ke)?)\s+(?:hp\s+(?:ke\s+)?)?(?:mode\s+)?(getar|vibrate|senyap|silent|hening|diam|normal)\b|\bbisukan\s+(?:nada\s+)?(dering|hp|telepon)\b|^\s*(getar|mode\s+getar|mode\s+senyap|mode\s+normal)\s*[.?!]*$)""",
            RegexOption.IGNORE_CASE
        )
        private val volumeNounRegex = Regex("""\b(volume|suara|audio|nada\s+dering)\b""", RegexOption.IGNORE_CASE)
        private val volumeExplicitPercentRegex = Regex(
            """\b(\d{1,3})\s*%|\b(?:atur|set|setel|stel|jadikan|jadi|ubah|ke|di)\s+(?:\w+\s+){0,2}?(\d{1,3})\b(?:\s*(?:%|persen))?|\b(?:volume|suara)\s+(?:media|alarm|dering|notifikasi|telepon)?\s*(?:ke|jadi)?\s*(\d{1,3})\b(?:\s*(?:%|persen))?""",
            RegexOption.IGNORE_CASE
        )
        private val volumeMaxCueRegex = Regex("""\b(maksimal|max|penuh|full|mentok)\b""", RegexOption.IGNORE_CASE)
        private val volumeMuteCueRegex = Regex("""\b(mute|bisukan|senyapkan|nolkan|matikan\s+(?:suara|volume))\b""", RegexOption.IGNORE_CASE)
        private val volumeUnmuteCueRegex = Regex("""\b(unmute|bunyikan(?:\s+lagi)?|nyalakan(?:\s+lagi)?)\b""", RegexOption.IGNORE_CASE)
        private val volumeUpCueRegex = Regex("""\b(naik(?:in|kan)?|besarin|besarkan|kerasin|keraskan|tinggiin|tinggikan|tambah(?:in|kan)?|gedein|gedekan|kencangin|kencangkan|up)\b""", RegexOption.IGNORE_CASE)
        private val volumeDownCueRegex = Regex("""\b(turun(?:in|kan)?|kecilin|kecilkan|pelanin|pelankan|kurang(?:i|in)?|rendahin|rendahkan|down)\b""", RegexOption.IGNORE_CASE)

        private val dialTriggerRegex = Regex(
            """^(?:(?:halo|hai|hei|gepy|tolong|coba|bantu)\s+)*(?:telepon|telpon|tlp|telfon|hubungi|call|dial)(?:\s+(?:ke|nomor))?\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )

        private val emailComposeTriggerRegex = Regex(
            """\b(kirim(?:kan)?|buat(?:kan)?|tulis(?:kan)?|bikin(?:kan)?|draft|draf|siapkan)\s+(?:pesan\s+)?(?:e-?mail|surel)\b(.*)$""",
            RegexOption.IGNORE_CASE
        )
        private val emailReadExclusionRegex = Regex(
            """\b(cek|baca|lihat|kotak\s+masuk|inbox|cari)\b.*\b(e-?mail|gmail)\b""",
            RegexOption.IGNORE_CASE
        )
        private val emailLiteralRegex = Regex("""([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})""")
        private val emailRecipientClauseRegex = Regex(
            """\bke\s+(.+?)(?=\s+(?:subjek|subject|judul|perihal|isi(?:\s+pesan(?:nya)?)?|pesannya|bilang|dengan\s+isi|tentang)\b|$)""",
            RegexOption.IGNORE_CASE
        )
        private val emailSubjectClauseRegex = Regex(
            """\b(?:subjek|subject|judul|perihal)\s+(.+?)(?=\s+(?:isi(?:\s+pesan)?|pesannya|bilang|dengan\s+isi|ke)\b|$)""",
            RegexOption.IGNORE_CASE
        )
        private val emailBodyClauseRegex = Regex(
            """\b(?:isi\s+pesannya|isi\s+pesan|isi|pesannya|pesan|bilang|yang\s+isinya)\s+(.+?)(?=\s+(?:subjek|subject|judul|perihal)\b|$)""",
            RegexOption.IGNORE_CASE
        )

        // Batch 2: Now Playing (MediaSessionManager) & Notifikasi (Dismiss / RemoteInput Reply)
        private val nowPlayingRegex = Regex(
            """\b(lagi\s+(?:muter|memutar|putar|play|dengerin|diputar)\s+lagu\s+apa|lagu\s+apa\s+(?:yang\s+)?(?:lagi\s+)?(?:diputar|muter|main|play)|ini\s+(?:judul\s+)?lagu(?:nya)?\s+apa(?:an)?|judul\s+lagu(?:\s+ini|\s+yang\s+(?:lagi\s+)?diputar)?|musik\s+apa\s+(?:yang\s+)?(?:lagi\s+)?diputar|now\s+playing|sedang\s+memutar\s+(?:lagu\s+)?apa)\b""",
            RegexOption.IGNORE_CASE
        )
        private val notificationDismissRegex = Regex(
            """\b(hapus|bersihkan|bersihin|tutup|buang|kosongkan|sapu|clear|dismiss)\s+(?:(semua|seluruh)\s+)?(?:daftar\s+)?(?:notif|notifikasi|notifications?)\b(?:\s+(?:dari\s+)?(.+))?""",
            RegexOption.IGNORE_CASE
        )
        private val notificationReplyRegex = Regex(
            """^(?:(?:halo|hai|hei|gepy|tolong|coba|bantu)\s+)*(?:balas|bales|reply)(?:\s+(notif|notifikasi|pesan|chat|wa|whatsapp|telegram|tele|dm|instagram|ig))?(?:\s+(?:(?:di|lewat|via)\s+)?(wa|whatsapp|telegram|tele|instagram|ig))?\s+(?:dari\s+|ke\s+|nya\s+)?(.+?)(?:\s+(?:bilang|isi(?:\s+pesan)?|pesannya|katakan|dengan\s+(?:isi|pesan|balasan)|kalau|bahwa)|(?:\s*:))\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )

        private val alarmReadOrManageExclusionRegex = Regex(
            """\b(cek|lihat|daftar|list|hapus|delete|matikan|nonaktifkan|batalkan|cancel|tunda|snooze|ganti|ubah|edit|ada berapa|jam berapa|kapan)\b""",
            RegexOption.IGNORE_CASE
        )
        private val alarmSetupVerbRegex = Regex(
            """\b(pasang(?:in|kan)?|set|setel(?:in|kan)?|stel(?:in|kan)?|atur(?:in|kan)?|buat(?:in|kan)?|bikin(?:in|kan)?|hapus|delete|matikan|nonaktifkan|batalkan|cancel|tunda|snooze)\b""",
            RegexOption.IGNORE_CASE
        )
        private val relativeDurationRegex = Regex(
            """\b(\d+\s*(?:menit|detik|jam)\s+lagi|setengah\s+jam\s+lagi|dalam\s+\d+\s*(?:menit|jam|detik)|timer)\b""",
            RegexOption.IGNORE_CASE
        )
        private val alarmActionTriggerRegex = Regex(
            """(?:\b(?:pasang(?:in|kan)?|set|setel(?:in|kan)?|stel(?:in|kan)?|atur(?:in|kan)?|buat(?:in|kan)?|bikin(?:in|kan)?|aktif(?:in|kan)?|nyala(?:in|kan)?|tambah(?:in|kan)?|kasih)\s+(?:(?:saya|aku|gue|gua|gw|dong|tolong)\s+)*(?:pengingat\s+)?alarm\b|\balarm\s+(?:pagi|subuh|sahur|jam|pukul|setengah|untuk|buat)\b|\balarm\s+\d{1,2}[:.]\d{2}\b|\b(?:bangunin|bangunkan)\s+(?:aku|saya|gue|gua)?\s*(?:besok\s+)?(?:(?:di\s+)?(?:jam|pukul|setengah)|subuh|sahur)\b)""",
            RegexOption.IGNORE_CASE
        )
        private val halfHourRegex = Regex(
            """\b(?:(?:jam|pukul)\s+)?setengah\s+(\d{1,2})(?:\s*(am|pm|pagi|subuh|sahur|siang|sore|petang|malam|dini\s+hari))?\b""",
            RegexOption.IGNORE_CASE
        )
        private val offsetHourRegex = Regex(
            """\b(?:jam|pukul)\s+(\d{1,2})\s+(lewat|lebih|kurang)\s+(\d{1,2})(?:\s*(am|pm|pagi|subuh|sahur|siang|sore|petang|malam|dini\s+hari))?\b""",
            RegexOption.IGNORE_CASE
        )
        private val standardTimeRegex = Regex(
            """\b(?:(jam|pukul)\s+)?(\d{1,2})(?:[:.](\d{1,2}))?(?:\s*(am|pm|pagi|subuh|sahur|siang|sore|petang|malam|dini\s+hari))?\b""",
            RegexOption.IGNORE_CASE
        )
        private val explicitLabelRegex = Regex(
            """\b(?:untuk|buat|judul|label|nama)\s+([a-zA-Z0-9][a-zA-Z0-9\s-]{0,31})""",
            RegexOption.IGNORE_CASE
        )
        private val labelNoiseWordRegex = Regex(
            """\b(alarm|timer|besok|nanti|setiap\s+hari|tiap\s+hari|pagi|siang|sore|malam|dini\s+hari|ya|dong|tolong|dongg|plis|please|gepy|saja|aja|bangun|lagi|dari\s+sekarang|sekarang|kamu|aku|saya)\b""",
            RegexOption.IGNORE_CASE
        )
        private val trailingPolitenessRegex = Regex(
            """\s*\b(?:ya|dong|tolong|sekarang|gepy|plis|please|aja|saja)\b[.?!]*$""",
            RegexOption.IGNORE_CASE
        )

        /** Mengurai string fakta/kalimat jam bangun singkat (mis. `"5 pagi"`, `"05:30"`) menjadi `(hour, minute)`. */
        fun extractTimeHourMinute(rawText: String): Pair<Int, Int>? {
            val clean = rawText.trim()
            if (clean.isEmpty()) return null

            halfHourRegex.find(clean)?.let { match ->
                val rawTarget = match.groupValues[1].toIntOrNull() ?: return null
                val meridiem = match.groupValues[2]
                if (rawTarget !in 1..12 && rawTarget !in 0..23) return null
                val base24 = resolveHourWithMeridiem(rawTarget, meridiem, isHalfPastTarget = true)
                val totalMin = ((base24 * 60) - 30 + 1440) % 1440
                return (totalMin / 60) to (totalMin % 60)
            }

            offsetHourRegex.find(clean)?.let { match ->
                val rawHour = match.groupValues[1].toIntOrNull() ?: return null
                val op = match.groupValues[2].lowercase(Locale.ROOT)
                val offsetMin = match.groupValues[3].toIntOrNull() ?: return null
                val meridiem = match.groupValues[4]
                if (rawHour !in 0..24 || offsetMin !in 1..59) return null
                val base24 = resolveHourWithMeridiem(rawHour, meridiem, isHalfPastTarget = false)
                val delta = if (op == "kurang") -offsetMin else offsetMin
                val totalMin = ((base24 * 60) + delta + 1440) % 1440
                return (totalMin / 60) to (totalMin % 60)
            }

            for (match in standardTimeRegex.findAll(clean)) {
                val prefix = match.groupValues[1]
                val rawHour = match.groupValues[2].toIntOrNull() ?: continue
                val minStr = match.groupValues[3]
                val meridiem = match.groupValues[4]
                val hasCue = prefix.isNotEmpty() || minStr.isNotEmpty() || meridiem.isNotEmpty()
                if (!hasCue) continue
                val minute = if (minStr.isEmpty()) 0 else (minStr.toIntOrNull() ?: continue)
                if (rawHour !in 0..24 || minute !in 0..59) return null
                if (rawHour == 24 && minute != 0) return null
                val hour24 = resolveHourWithMeridiem(rawHour, meridiem, isHalfPastTarget = false)
                return hour24 to minute
            }
            return null
        }

        private fun resolveHourWithMeridiem(
            rawHour: Int,
            rawMeridiem: String,
            isHalfPastTarget: Boolean
        ): Int {
            val meridiem = rawMeridiem.trim().lowercase(Locale.ROOT)
            if (rawHour == 24) return if (isHalfPastTarget) 24 else 0
            if (rawHour in 13..23) return rawHour

            return when {
                meridiem == "pm" || meridiem == "sore" || meridiem == "petang" ->
                    if (rawHour in 1..11) rawHour + 12 else rawHour
                meridiem == "siang" ->
                    if (rawHour in 1..4) rawHour + 12 else rawHour
                meridiem == "malam" -> when {
                    rawHour in 6..11 -> rawHour + 12
                    rawHour == 12 -> if (isHalfPastTarget) 24 else 0
                    else -> rawHour // 0..5 malam -> dini hari (00..05)
                }
                meridiem == "am" || meridiem == "pagi" ->
                    if (rawHour == 12) 0 else rawHour
                else -> rawHour
            }
        }
    }

    override val skillId: String = "android_system_tools"

    override val supportedPackages: List<String> = emptyList()

    /**
     * Mengurai transkrip menjadi [ParsedAlarmRequest] jika perintah secara jelas dan aman
     * meminta pemasangan alarm baru. Mengembalikan `null` jika instruksi ambigu, destruktif
     * (hapus/matikan), hanya bertanya daftar alarm, atau meminta timer/durasi relatif.
     */
    fun parseAlarmRequest(transcript: String): ParsedAlarmRequest? {
        val clean = IndonesianSpeechNormalizer.normalize(transcript)
        if (clean.isEmpty()) return null
        if (relativeDurationRegex.containsMatchIn(clean)) return null
        val lower = clean.lowercase(Locale.ROOT)

        if (alarmReadOrManageExclusionRegex.containsMatchIn(lower) &&
            (lower.contains("alarm") || lower.contains("bangun"))
        ) {
            return null
        }
        if (!alarmActionTriggerRegex.containsMatchIn(clean)) {
            return null
        }

        // Cek apakah user menyebut angka jam yang jelas (valid atau invalid seperti jam 25)
        val hasNumericTimeCue = halfHourRegex.containsMatchIn(clean) ||
            offsetHourRegex.containsMatchIn(clean) ||
            standardTimeRegex.findAll(clean).any { m ->
                m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty() || m.groupValues[4].isNotEmpty()
            }

        val parsedTime = extractTimeHourMinute(clean)
        if (hasNumericTimeCue && parsedTime == null) {
            return null
        }

        val (hour, minute, usedDefaultMorning) = when {
            parsedTime != null -> Triple(parsedTime.first, parsedTime.second, false)
            lower.contains("subuh") -> Triple(4, 30, false)
            lower.contains("sahur") -> Triple(3, 30, false)
            lower.contains("pagi") -> {
                val memWake = preferredWakeTimeProvider()
                    ?.takeIf { (h, m) -> h in 0..23 && m in 0..59 }
                val target = memWake ?: (DEFAULT_MORNING_ALARM_HOUR to DEFAULT_MORNING_ALARM_MINUTE)
                Triple(target.first, target.second, memWake == null)
            }
            else -> return null
        }

        val label = extractAlarmLabel(clean, lower, isMorningPreset = (parsedTime == null && lower.contains("pagi")))
        return ParsedAlarmRequest(
            hour = hour,
            minute = minute,
            label = label,
            usedDefaultMorningTime = usedDefaultMorning
        )
    }

    fun parseFlashlightRequest(transcript: String): ParsedFlashlightRequest? {
        val clean = IndonesianSpeechNormalizer.normalize(transcript)
        if (clean.isEmpty()) return null
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) || laptopTargetRegex.containsMatchIn(clean)) {
            return null
        }
        if (!flashlightNounRegex.containsMatchIn(clean)) return null
        if (flashlightOffCueRegex.containsMatchIn(clean)) return ParsedFlashlightRequest(enabled = false)
        if (flashlightOnCueRegex.containsMatchIn(clean) ||
            Regex("""^(?:senter|lampu\s+senter|flashlight|torch)\s*[!.]*$""", RegexOption.IGNORE_CASE).matches(clean)
        ) {
            return ParsedFlashlightRequest(enabled = true)
        }
        return null
    }

    fun parseTimerRequest(transcript: String): ParsedTimerRequest? {
        val clean = IndonesianSpeechNormalizer.normalize(transcript)
        if (clean.isEmpty()) return null
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) || laptopTargetRegex.containsMatchIn(clean)) {
            return null
        }
        val hasTimerKeyword = timerKeywordRegex.containsMatchIn(clean)
        val hasRelativeReminder = relativeReminderCueRegex.containsMatchIn(clean) &&
            (relativeTimeWindowRegex.containsMatchIn(clean) || relativeDurationRegex.containsMatchIn(clean))
        val hasRelativeAlarm = relativeAlarmCueRegex.containsMatchIn(clean)
        val hasRelativeAction = (clean.contains("pasang", true) || clean.contains("setel", true) || clean.contains("bikin", true)) &&
            relativeTimeWindowRegex.containsMatchIn(clean)

        if (!hasTimerKeyword && !hasRelativeReminder && !hasRelativeAlarm && !hasRelativeAction) return null
        if (timerExclusionRegex.containsMatchIn(clean)) return null

        val hours = timerHoursRegex.findAll(clean).sumOf { it.groupValues[1].toIntOrNull() ?: 0 }
        val minutes = timerMinutesRegex.findAll(clean).sumOf { it.groupValues[1].toIntOrNull() ?: 0 }
        val seconds = timerSecondsRegex.findAll(clean).sumOf { it.groupValues[1].toIntOrNull() ?: 0 }
        val halfHourSecs = if (timerHalfHourRegex.containsMatchIn(clean)) 1800 else 0
        val totalSeconds = hours * 3600 + minutes * 60 + seconds + halfHourSecs
        if (totalSeconds !in 1..86_400) return null

        val strippedDuration = clean
            .replace(timerHoursRegex, " ")
            .replace(timerMinutesRegex, " ")
            .replace(timerSecondsRegex, " ")
            .replace(timerHalfHourRegex, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val labelMatch = explicitLabelRegex.find(strippedDuration)
        val label = if (labelMatch != null) {
            val cleaned = labelMatch.groupValues[1]
                .replace(labelNoiseWordRegex, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (cleaned.length >= 2) {
                cleaned.split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
                    }
            } else if (hasRelativeReminder) {
                "Pengingat Gepy"
            } else {
                "Timer Gepy"
            }
        } else {
            val directMatch = reminderDirectLabelRegex.find(strippedDuration)
            val directClean = directMatch?.groupValues?.getOrNull(1)
                ?.replace(labelNoiseWordRegex, " ")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
            if (!directClean.isNullOrBlank() && directClean.length >= 2) {
                directClean.split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
                    }
            } else if (hasRelativeReminder) {
                "Pengingat Gepy"
            } else {
                "Timer Gepy"
            }
        }
        return ParsedTimerRequest(durationSeconds = totalSeconds, label = label)
    }

    fun parseShowAlarmsRequest(transcript: String): Boolean {
        val clean = transcript.trim()
        if (clean.isEmpty()) return false
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) || laptopTargetRegex.containsMatchIn(clean)) {
            return false
        }
        if (!Regex("""\balarm\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return false
        if (relativeDurationRegex.containsMatchIn(clean)) return false
        if (alarmSetupVerbRegex.containsMatchIn(clean)) return false
        if (parseAlarmRequest(clean) != null) return false
        return showAlarmsCueRegex.containsMatchIn(clean)
    }

    fun parseVolumeOrRingerRequest(transcript: String): ParsedAudioSystemRequest? {
        val clean = transcript.trim()
        if (clean.isEmpty()) return null
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) || laptopTargetRegex.containsMatchIn(clean)) {
            return null
        }
        if (Regex("""\b(tv|televisi|speaker\s+bluetooth)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return null
        }
        if (parseFloatingPanelRequest(clean) != null) return null

        ringerModeRegex.find(clean)?.let { m ->
            val rawMode = (1..4).firstNotNullOfOrNull { idx -> m.groupValues[idx].takeIf { it.isNotBlank() } }
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            val mapped = when {
                rawMode.contains("getar") || rawMode.contains("vibrate") -> "vibrate"
                rawMode.contains("senyap") || rawMode.contains("silent") ||
                    rawMode.contains("hening") || rawMode.contains("diam") ||
                    clean.lowercase(Locale.ROOT).contains("bisukan") -> "silent"
                else -> "normal"
            }
            return ParsedAudioSystemRequest.RingerMode(mapped)
        }

        if (!volumeNounRegex.containsMatchIn(clean)) return null
        val lower = clean.lowercase(Locale.ROOT)
        val stream = when {
            lower.contains("alarm") -> "alarm"
            lower.contains("dering") || lower.contains("ringtone") -> "ring"
            lower.contains("notif") -> "notification"
            lower.contains("telepon") || lower.contains("panggilan") -> "call"
            else -> "media"
        }

        volumeExplicitPercentRegex.find(clean)?.let { m ->
            val rawNum = (1..3).firstNotNullOfOrNull { idx -> m.groupValues[idx].takeIf { it.isNotBlank() } }
                ?.toIntOrNull()
            if (rawNum != null && rawNum in 0..100) {
                return ParsedAudioSystemRequest.Volume(stream = stream, targetPercent = rawNum, direction = null)
            }
        }

        val direction = when {
            volumeMaxCueRegex.containsMatchIn(clean) -> "max"
            volumeMuteCueRegex.containsMatchIn(clean) -> "mute"
            volumeUnmuteCueRegex.containsMatchIn(clean) -> "unmute"
            volumeUpCueRegex.containsMatchIn(clean) -> "up"
            volumeDownCueRegex.containsMatchIn(clean) -> "down"
            else -> return null
        }
        return ParsedAudioSystemRequest.Volume(stream = stream, targetPercent = null, direction = direction)
    }

    fun parseDialRequest(transcript: String): ParsedDialRequest? {
        val clean = transcript.trim().trimEnd('.', '?', '!')
        if (clean.isEmpty()) return null
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) ||
            laptopTargetRegex.containsMatchIn(clean) ||
            messagingCallExclusionRegex.containsMatchIn(clean)
        ) {
            return null
        }
        val m = dialTriggerRegex.find(clean) ?: return null
        val target = m.groupValues[1]
            .replace(trailingPolitenessRegex, "")
            .trim()
        if (target.isEmpty()) return null
        val lowerTarget = target.lowercase(Locale.ROOT)
        if (lowerTarget in setOf("aplikasi", "app", "menu", "telepon", "telpon", "dialer", "kontak") ||
            lowerTarget.startsWith("aplikasi ") ||
            lowerTarget.startsWith("menu ") ||
            lowerTarget.startsWith("buka ")
        ) {
            return null
        }
        return ParsedDialRequest(targetQuery = target)
    }

    fun parseEmailComposeRequest(transcript: String): ParsedEmailComposeRequest? {
        val clean = transcript.trim().trimEnd('.', '?', '!')
        if (clean.isEmpty()) return null
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) || laptopTargetRegex.containsMatchIn(clean)) {
            return null
        }
        if (emailReadExclusionRegex.containsMatchIn(clean)) return null
        val triggerMatch = emailComposeTriggerRegex.find(clean) ?: return null
        val tail = triggerMatch.groupValues[2].trim()

        val literalEmail = emailLiteralRegex.find(tail)?.groupValues?.get(1).orEmpty()
        val recipient = literalEmail.ifEmpty {
            emailRecipientClauseRegex.find(tail)?.groupValues?.get(1)
                ?.replace(trailingPolitenessRegex, "")
                ?.trim()
                .orEmpty()
        }
        val subject = emailSubjectClauseRegex.find(tail)?.groupValues?.get(1)
            ?.replace(trailingPolitenessRegex, "")
            ?.trim()
            .orEmpty()
        val body = emailBodyClauseRegex.find(tail)?.groupValues?.get(1)
            ?.replace(trailingPolitenessRegex, "")
            ?.trim()
            .orEmpty()

        return ParsedEmailComposeRequest(
            recipientQuery = recipient,
            subject = subject,
            body = body
        )
    }

    fun parseFloatingPanelRequest(transcript: String): ParsedFloatingPanelRequest? {
        val clean = transcript.trim()
        if (clean.isEmpty() || informationalQuestionExclusionRegex.containsMatchIn(clean)) return null
        val panelKeyword = IntentDeepLinkRouter.extractFloatingPanelKeyword(clean) ?: return null
        return if (IntentDeepLinkRouter.resolveFloatingSettingsPanelAction(panelKeyword) != null) {
            ParsedFloatingPanelRequest(panelKeyword = panelKeyword)
        } else {
            null
        }
    }

    fun parseMediaNowPlayingQuery(transcript: String): Boolean {
        val clean = transcript.trim()
        if (clean.isEmpty()) return false
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) || laptopTargetRegex.containsMatchIn(clean)) {
            return false
        }
        return nowPlayingRegex.containsMatchIn(clean)
    }

    fun parseNotificationDismissRequest(transcript: String): ParsedNotificationDismissRequest? {
        val clean = transcript.trim().trimEnd('.', '?', '!')
        if (clean.isEmpty() || informationalQuestionExclusionRegex.containsMatchIn(clean)) return null
        val m = notificationDismissRegex.find(clean) ?: return null
        val cleanedTarget = m.groupValues[3]
            .replace(Regex("""^(?:dari|aplikasi|punya|yang|masuk)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(trailingPolitenessRegex, "")
            .trim()
        val isTargetGenericAll = cleanedTarget.isEmpty() ||
            cleanedTarget.lowercase(Locale.ROOT) in setOf("semua", "semuanya", "seluruh", "all", "notif", "notifikasi")
        if (isTargetGenericAll) {
            return ParsedNotificationDismissRequest(targetQuery = "all")
        }
        return ParsedNotificationDismissRequest(targetQuery = cleanedTarget)
    }

    fun parseNotificationReplyRequest(transcript: String): ParsedNotificationReplyRequest? {
        val clean = transcript.trim()
        if (clean.isEmpty()) return null
        if (informationalQuestionExclusionRegex.containsMatchIn(clean) || laptopTargetRegex.containsMatchIn(clean)) {
            return null
        }
        val m = notificationReplyRegex.find(clean) ?: return null
        val nounHint = m.groupValues[1].trim().lowercase(Locale.ROOT)
        val appHint2 = m.groupValues[2].trim().lowercase(Locale.ROOT)
        var target = m.groupValues[3]
            .replace(Regex("""^(?:wa|whatsapp|telegram|tele|instagram|ig)\s+(?:dari\s+)?""", RegexOption.IGNORE_CASE), "")
            .trim()
        val message = m.groupValues[4].trim()
        if (target.isEmpty() || message.isEmpty()) return null

        val explicitApp = appHint2.ifEmpty {
            if (nounHint in setOf("wa", "whatsapp", "telegram", "tele", "instagram", "ig")) nounHint else ""
        }.ifEmpty { null }
        return ParsedNotificationReplyRequest(
            targetQuery = target,
            replyMessage = message,
            explicitAppKeyword = explicitApp
        )
    }

    private fun extractAlarmLabel(clean: String, lower: String, isMorningPreset: Boolean): String {
        val withoutTimes = clean
            .replace(halfHourRegex, " ")
            .replace(offsetHourRegex, " ")
            .replace(standardTimeRegex) { m ->
                val hasCue = m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty() || m.groupValues[4].isNotEmpty()
                if (hasCue) " " else m.value
            }
            .replace(Regex("""\s+"""), " ")
            .trim()

        val labelMatch = explicitLabelRegex.find(withoutTimes)
        if (labelMatch != null) {
            val cleaned = labelMatch.groupValues[1]
                .replace(labelNoiseWordRegex, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (cleaned.length >= 2) {
                return cleaned.split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
                    }
            }
        }

        return when {
            lower.contains("subuh") -> "Subuh"
            lower.contains("sahur") -> "Sahur"
            isMorningPreset -> "Alarm Pagi"
            else -> "Alarm Gepy"
        }
    }

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        val clean = IndonesianSpeechNormalizer.normalize(transcript)
        if (clean.isEmpty()) return false
        if (informationalQuestionExclusionRegex.containsMatchIn(clean)) return false

        if (parseAlarmRequest(clean) != null) return true
        if (parseTimerRequest(clean) != null) return true
        if (parseShowAlarmsRequest(clean)) return true
        if (parseFlashlightRequest(clean) != null) return true
        if (parseFloatingPanelRequest(clean) != null) return true
        if (parseVolumeOrRingerRequest(clean) != null) return true
        if (parseDialRequest(clean) != null) return true
        if (parseEmailComposeRequest(clean) != null) return true
        if (parseMediaNowPlayingQuery(clean)) return true
        if (parseNotificationDismissRequest(clean) != null) return true
        if (parseNotificationReplyRequest(clean) != null) return true

        val lower = clean.lowercase(Locale.ROOT)
        if (Regex("""\b(alarm|timer)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
            (lower.contains("bangun") &&
                (alarmReadOrManageExclusionRegex.containsMatchIn(lower) ||
                    relativeDurationRegex.containsMatchIn(lower)))
        ) {
            return false
        }
        return deviceOrTimeRegex.containsMatchIn(clean) ||
            notificationRegex.containsMatchIn(clean) ||
            locationRegex.containsMatchIn(clean) ||
            wellnessRegex.containsMatchIn(clean)
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: ANDROID OS SYSTEM TOOLS (API-FIRST)]
Untuk alarm, timer, senter, volume, mode dering, dialer telepon, draf email, panel cepat, notifikasi real-time (baca/balas RemoteInput/hapus), lagu sedang diputar, baterai, jam/tanggal, atau GPS, utamakan OS API langsung tanpa menyentuh UI layar.
        """.trimIndent()

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        val speechCtx = speechContextProvider()
        val normalizedGoal = IndonesianSpeechNormalizer.normalize(userGoal)

        parseAlarmRequest(normalizedGoal)?.let { alarmReq ->
            val ok = systemBridge.setSystemAlarm(
                hour = alarmReq.hour,
                minute = alarmReq.minute,
                label = alarmReq.label
            )
            return ReActActionModel(
                thought = "[Fast Path OS Alarm] Memasang alarm sistem pukul ${String.format(Locale.US, "%02d:%02d", alarmReq.hour, alarmReq.minute)} (${alarmReq.label}) langsung via AlarmClock.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = SovereignNaturalSpeech.alarmSet(
                    ctx = speechCtx,
                    hour = alarmReq.hour,
                    minute = alarmReq.minute,
                    label = alarmReq.label,
                    success = ok
                )
            )
        }

        parseTimerRequest(normalizedGoal)?.let { timerReq ->
            val res = systemBridge.setSystemTimer(
                durationSeconds = timerReq.durationSeconds,
                label = timerReq.label
            )
            return ReActActionModel(
                thought = "[Fast Path OS Timer] Memasang timer ${timerReq.durationSeconds} detik (${timerReq.label}) langsung via AlarmClock.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        if (parseShowAlarmsRequest(normalizedGoal)) {
            val res = systemBridge.showSystemAlarms()
            return ReActActionModel(
                thought = "[Fast Path OS Alarm] Membaca jadwal alarm terdekat dan membuka daftar alarm sistem.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        parseFlashlightRequest(normalizedGoal)?.let { torchReq ->
            val res = systemBridge.setFlashlightTorch(enabled = torchReq.enabled)
            return ReActActionModel(
                thought = "[Fast Path Hardware Torch] Mengatur senter kamera ke ${if (torchReq.enabled) "ON" else "OFF"} via CameraManager.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        parseFloatingPanelRequest(normalizedGoal)?.let { panelReq ->
            val res = systemBridge.openFloatingSettingsPanel(panelKeyword = panelReq.panelKeyword)
            return ReActActionModel(
                thought = "[Fast Path Settings.Panel] Menampilkan panel mengambang OS untuk '${panelReq.panelKeyword}'.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        parseVolumeOrRingerRequest(normalizedGoal)?.let { audioReq ->
            val res = when (audioReq) {
                is ParsedAudioSystemRequest.RingerMode -> systemBridge.setRingerModeSafe(audioReq.mode)
                is ParsedAudioSystemRequest.Volume -> systemBridge.adjustAudioVolume(
                    stream = audioReq.stream,
                    targetPercent = audioReq.targetPercent,
                    direction = audioReq.direction
                )
            }
            return ReActActionModel(
                thought = "[Fast Path AudioManager] Mengatur audio/mode dering sistem secara langsung (< 5 ms).",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        parseDialRequest(normalizedGoal)?.let { dialReq ->
            val res = systemBridge.openPhoneDialer(targetQuery = dialReq.targetQuery)
            return ReActActionModel(
                thought = "[Fast Path Phone Dialer] Membuka dialer telepon ACTION_DIAL untuk '${dialReq.targetQuery}'.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        parseEmailComposeRequest(normalizedGoal)?.let { mailReq ->
            val res = systemBridge.openEmailComposer(
                recipientQuery = mailReq.recipientQuery,
                subject = mailReq.subject,
                body = mailReq.body
            )
            return ReActActionModel(
                thought = "[Fast Path Email Composer] Membuka draf email mailto: tujuan '${mailReq.recipientQuery}'.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        if (parseMediaNowPlayingQuery(normalizedGoal)) {
            val res = systemBridge.getActiveMediaPlaybackInfo()
            return ReActActionModel(
                thought = "[Fast Path MediaSession] Membaca sesi pemutaran media aktif dari OS.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = res["summary_id"]?.toString()
            )
        }

        parseNotificationDismissRequest(normalizedGoal)?.let { dismissReq ->
            val res = systemBridge.dismissNotifications(dismissReq.targetQuery)
            val speech = when (res.status) {
                NotificationOpStatus.SUCCESS -> SovereignNaturalSpeech.notificationDismissed(
                    speechCtx,
                    res.dismissedCount,
                    res.dismissedLabels
                )
                NotificationOpStatus.PRIVACY_SHIELD_BLOCKED ->
                    SovereignNaturalSpeech.notificationsPrivacyBlocked(speechCtx)
                NotificationOpStatus.PERMISSION_REQUIRED ->
                    SovereignNaturalSpeech.notificationAccessRequired(speechCtx)
                else ->
                    SovereignNaturalSpeech.notificationTargetNotFound(speechCtx, dismissReq.targetQuery)
            }
            return ReActActionModel(
                thought = "[Fast Path Notification Dismiss] Mengelola pembersihan notifikasi OS (${res.status}).",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        parseNotificationReplyRequest(normalizedGoal)?.let { replyReq ->
            val isPrivacyShieldActive = systemBridge.wellnessStatusProvider?.invoke()?.get("privacy_shield_active") == true
            if (isPrivacyShieldActive) {
                return ReActActionModel(
                    thought = "[Fast Path Notification Reply] Privacy Shield aktif, memblokir balas notifikasi.",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE,
                    completionTts = SovereignNaturalSpeech.notificationsPrivacyBlocked(speechCtx)
                )
            }
            if (!systemBridge.isNotificationListenerEnabledProvider()) {
                // UI-Fallback: izin listener belum aktif -> serahkan ke alur UI aplikasi chat
                return null
            }
            val combinedMatch = systemBridge.findReplyableNotificationMatch(replyReq.targetQuery)
                ?.takeIf { snap ->
                    val appHint = replyReq.explicitAppKeyword?.lowercase(Locale.ROOT) ?: return@takeIf true
                    val normalizedPkg = snap.packageName.lowercase(Locale.ROOT)
                    val normalizedLabel = snap.appLabel.lowercase(Locale.ROOT)
                    when (appHint) {
                        "wa", "whatsapp" -> normalizedPkg.contains("whatsapp") || normalizedLabel.contains("whatsapp")
                        "tele", "telegram" -> normalizedPkg.contains("telegram") || normalizedLabel.contains("telegram")
                        "ig", "instagram" -> normalizedPkg.contains("instagram") || normalizedLabel.contains("instagram")
                        else -> normalizedPkg.contains(appHint) || normalizedLabel.contains(appHint)
                    }
                }
            if (combinedMatch == null || !combinedMatch.hasRemoteInput) {
                // UI-Fallback: sasaran tidak ada di notifikasi aktif -> buka aplikasi UI secara mulus
                return null
            }

            if (!sovereignDirectExecutionProvider()) {
                val senderLabel = combinedMatch.title.ifEmpty { combinedMatch.appLabel }
                return ReActActionModel(
                    thought = "[Fast Path Notification HITL] Meminta konfirmasi pengguna sebelum mengirim RemoteInput ke '$senderLabel'.",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.CONFIRM_AND_CLICK,
                    appName = combinedMatch.key,
                    textValue = replyReq.replyMessage,
                    confirmationPrompt = SovereignNaturalSpeech.notificationReplyConfirmPrompt(
                        ctx = speechCtx,
                        senderName = senderLabel,
                        replyMessage = replyReq.replyMessage
                    )
                )
            }

            val res = systemBridge.replyToNotification(
                senderOrAppQuery = replyReq.targetQuery,
                replyMessage = replyReq.replyMessage,
                exactNotificationKey = combinedMatch.key
            )
            return when (res.status) {
                NotificationOpStatus.SUCCESS -> ReActActionModel(
                    thought = "[Fast Path Notification Reply] Membalas notifikasi '${combinedMatch.title}' langsung via RemoteInput (< 15 ms).",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE,
                    completionTts = SovereignNaturalSpeech.notificationReplied(
                        speechCtx,
                        res.targetTitle.ifEmpty { combinedMatch.title }
                    )
                )
                NotificationOpStatus.PRIVACY_SHIELD_BLOCKED -> ReActActionModel(
                    thought = "[Fast Path Notification Reply] Privacy Shield aktif.",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE,
                    completionTts = SovereignNaturalSpeech.notificationsPrivacyBlocked(speechCtx)
                )
                NotificationOpStatus.SAFETY_BLOCKED -> ReActActionModel(
                    thought = "[Fast Path Notification Reply] Diblokir oleh SafetyRules (konten sensitif/perbankan).",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE,
                    completionTts = SovereignNaturalSpeech.notificationsSafetyBlocked(speechCtx)
                )
                // Gagal RemoteInput / tidak ditemukan -> jatuhkan ke UI-Fallback
                NotificationOpStatus.PERMISSION_REQUIRED,
                NotificationOpStatus.NOT_FOUND,
                NotificationOpStatus.NO_REMOTE_INPUT,
                NotificationOpStatus.ERROR -> null
            }
        }

        if (!canHandleVoiceIntent(userGoal)) {
            return null
        }

        val summaryTts = when {
            wellnessRegex.containsMatchIn(userGoal) -> {
                val res = systemBridge.executeSystemToolCall("get_personal_wellness_status", emptyMap())
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.wellnessEmptyFallback(speechCtx)
            }
            notificationRegex.containsMatchIn(userGoal) -> {
                val res = systemBridge.executeSystemToolCall("get_recent_notifications", emptyMap())
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.notificationsEmpty(speechCtx)
            }
            locationRegex.containsMatchIn(userGoal) -> {
                val res = systemBridge.executeSystemToolCall("get_current_location", emptyMap())
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.locationUnavailable(speechCtx)
            }
            else -> {
                systemBridge.getDeviceAndTimeStatus().toNaturalSummaryId(speechCtx)
            }
        }

        return ReActActionModel(
            thought = "[Fast Path OS] Mengambil informasi sistem Android secara langsung tanpa membuka UI.",
            reflectionOnLastStep = ReflectionStatus.INITIAL,
            action = AgentActionType.DONE,
            completionTts = summaryTts
        )
    }
}
