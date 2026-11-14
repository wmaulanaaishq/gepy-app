package com.teknologigawai.gepy.features.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.net.toUri
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.skills.WhatsAppMessageIntent
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.features.brain.CrossAppWorkflowEngine
import com.teknologigawai.gepy.service.GepyAccessibilityService
import java.net.URLEncoder

/** Hasil pencocokan dan eksekusi *Semantic Deep-Link / Intent Router*. */
data class DeepLinkRouteResult(
    val handled: Boolean,
    val routeType: String,
    val targetUriOrAction: String = "",
    val summaryId: String = "",
    val requiresSubsequentUiVerify: Boolean = false
)

/**
 * Router **Semantic Intent & Deep-Link** (`IntentDeepLinkRouter`) untuk Gepy Native v3.1.
 *
 * Memungkinkan agen melompati 3–5 langkah navigasi UI secara deterministik (`< 15 ms`):
 * 1. **WhatsApp Direct Deep-Link** (`whatsapp://send?phone=628...&text=...`) apabila kontak penerima
 *    ditemukan secara unik di buku telepon Android (`ContactsContract`), lalu berhenti tepat di
 *    ruang obrolan agar pengguna/HITL dapat memverifikasi tombol **Kirim**.
 * 2. **Google Maps Search & Turn-by-Turn Navigation** (`geo:0,0?q=...` & `google.navigation:q=...`).
 * 3. **System Settings Panel Deep-Link** (`WIFI`, `BLUETOOTH`, `SOUND`, `DISPLAY`, `ACCESSIBILITY`).
 * 4. **Calendar Event Pre-Fill Intent** (`Intent.ACTION_INSERT` pada `CalendarContract.Events`).
 */
class IntentDeepLinkRouter(
    private val context: Context? = null,
    private val systemToolsBridge: AndroidSystemToolsBridge = AndroidSystemToolsBridge(context),
    @Volatile var speechContextProvider: () -> SovereignSpeechContext = { systemToolsBridge.speechContextProvider() }
) {
    companion object {
        private val nonDigitPlusRegex = Regex("[^0-9+]")
        private val mapsNavRegex = Regex(
            """\b(?:navigasi|arahkan|rute|petunjuk arah)\s+(?:ke|menuju)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        private val mapsSearchRegex = Regex(
            """\b(?:cari\s+lokasi|buka\s+maps|cari\s+di\s+maps|cari\s+di\s+peta)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        private val floatingPanelCommandRegex = Regex(
            """\b(?:(?:buka|bukain|tampilkan|munculkan|lihat|atur)\s+(?:panel(?:\s+cepat)?|kontrol(?:\s+cepat)?|pengaturan\s+cepat)\s+|panel\s+(?:cepat\s+)?)(wifi|wi-fi|internet|jaringan|koneksi|data|volume|suara|audio|sound|nfc)\b""",
            RegexOption.IGNORE_CASE
        )
        private val settingsCommandRegex = Regex(
            """\b(?:buka|atur|masuk\s+ke)\s+(?:pengaturan|setelan|settings)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Menormalisasi nomor telepon Indonesia (misal `0812-3456-7890` atau `+62 812 3456 7890`)
         * menjadi format internasional tanpa tanda tambah (`6281234567890`) untuk URI WhatsApp.
         */
        fun normalizeIndonesianPhoneNumber(rawPhone: String): String {
            val cleaned = rawPhone.replace(nonDigitPlusRegex, "").trim()
            if (cleaned.isEmpty()) return ""

            return when {
                cleaned.startsWith("+62") -> cleaned.removePrefix("+")
                cleaned.startsWith("62") -> cleaned
                cleaned.startsWith("08") -> "62" + cleaned.removePrefix("0")
                cleaned.startsWith("8") && cleaned.length >= 9 -> "62$cleaned"
                cleaned.startsWith("+") -> cleaned.removePrefix("+")
                else -> cleaned
            }
        }

        /** Membangun URI `whatsapp://send?phone=...&text=...` yang aman dan terenkode UTF-8. */
        fun buildWhatsAppDeepLinkUri(phone: String, message: String): String {
            val normPhone = normalizeIndonesianPhoneNumber(phone)
            if (normPhone.length < 9) return ""
            val encodedText = urlEncodeUtf8(message.trim())
            return "whatsapp://send?phone=$normPhone&text=$encodedText"
        }

        /** Membangun URI dialer `tel:...` dengan mempertahankan format lokal atau awalan `+`. */
        fun buildTelDialUri(rawPhone: String): String {
            val cleaned = rawPhone.replace(Regex("[^0-9+*#]"), "").trim()
            val digitsOnly = cleaned.count { it.isDigit() }
            if (digitsOnly < 3) return ""
            return "tel:$cleaned"
        }

        /** Membangun URI `mailto:...` untuk membuka draf email langsung dengan aman. */
        fun buildMailtoUri(
            recipient: String,
            subject: String = "",
            body: String = ""
        ): String {
            val cleanRecipient = recipient.trim().takeIf { it.contains('@') && !it.contains(' ') }.orEmpty()
            val queryParams = buildList {
                val cleanSubject = subject.trim()
                val cleanBody = body.trim()
                if (cleanSubject.isNotEmpty()) {
                    add("subject=${urlEncodeUtf8(cleanSubject)}")
                }
                if (cleanBody.isNotEmpty()) {
                    add("body=${urlEncodeUtf8(cleanBody)}")
                }
            }
            val base = "mailto:$cleanRecipient"
            return if (queryParams.isEmpty()) base else "$base?${queryParams.joinToString("&")}"
        }

        /** Membangun URI pencarian atau navigasi langsung Google Maps. */
        fun buildMapsDeepLinkUri(query: String, navigateDirectly: Boolean = false): String {
            val clean = query.trim()
            if (clean.isEmpty()) return ""
            val encoded = urlEncodeUtf8(clean)
            return if (navigateDirectly) {
                "google.navigation:q=$encoded"
            } else {
                "geo:0,0?q=$encoded"
            }
        }

        /** Mengekstrak kata kunci panel cepat mengambang bila pengguna secara eksplisit meminta panel cepat. */
        fun extractFloatingPanelKeyword(utterance: String): String? {
            val clean = utterance.trim()
            if (clean.isEmpty()) return null
            val match = floatingPanelCommandRegex.find(clean) ?: return null
            return match.groupValues[1].trim().ifEmpty { null }
        }

        /** Memetakan nama panel pengaturan mengambang (`Settings.Panel.*`, Android Q / API 29+). */
        fun resolveFloatingSettingsPanelAction(panelKeyword: String): String? {
            val lower = panelKeyword.trim().lowercase()
            return when {
                lower.contains("wifi") || lower.contains("wi-fi") ->
                    Settings.Panel.ACTION_WIFI
                lower.contains("internet") || lower.contains("jaringan") ||
                    lower.contains("koneksi") || lower.contains("data") ->
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                lower.contains("volume") || lower.contains("suara") ||
                    lower.contains("audio") || lower.contains("sound") ->
                    Settings.Panel.ACTION_VOLUME
                lower.contains("nfc") ->
                    Settings.Panel.ACTION_NFC
                else -> null
            }
        }

        /** Memetakan nama panel pengaturan Bahasa Indonesia/Inggris ke konstanta `Settings.ACTION_*`. */
        fun resolveSystemSettingsAction(panelKeyword: String): String? {
            val lower = panelKeyword.trim().lowercase()
            return when {
                lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("internet") ->
                    Settings.ACTION_WIFI_SETTINGS
                lower.contains("bluetooth") ->
                    Settings.ACTION_BLUETOOTH_SETTINGS
                lower.contains("suara") || lower.contains("sound") || lower.contains("volume") ->
                    Settings.ACTION_SOUND_SETTINGS
                lower.contains("layar") || lower.contains("display") || lower.contains("kecerahan") ->
                    Settings.ACTION_DISPLAY_SETTINGS
                lower.contains("aksesibilitas") || lower.contains("accessibility") ->
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                lower.contains("baterai") || lower.contains("battery") || lower.contains("daya") ->
                    Settings.ACTION_BATTERY_SAVER_SETTINGS
                lower.contains("lokasi") || lower.contains("gps") || lower.contains("location") ->
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS
                lower.contains("nfc") ->
                    Settings.ACTION_NFC_SETTINGS
                else -> null
            }
        }

        private fun urlEncodeUtf8(value: String): String {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }
    }

    /**
     * Mencoba melompati langkah pencarian WhatsApp dengan meluncurkan ruang obrolan
     * secara langsung apabila nama kontak penerima ditemukan unik (tepat 1 kontak) di buku telepon.
     *
     * Mengembalikan `requiresSubsequentUiVerify = true` agar loop ReAct / `WhatsAppFastPath`
     * tetap memverifikasi layar ruang obrolan dan meminta persetujuan HITL sebelum mengetuk tombol **Kirim**.
     */
    fun tryPreLaunchWhatsAppChatWithDraft(userGoal: String): DeepLinkRouteResult {
        // Jangan intersep bila perintah merupakan tugas majemuk lintas aplikasi (mis. cari di Maps lalu kirim ke WA)
        if (CrossAppWorkflowEngine.isCrossAppCommand(userGoal)) {
            return DeepLinkRouteResult(handled = false, routeType = "NONE")
        }

        val waIntent = WhatsAppMessageIntent.tryParse(userGoal)
            ?: return DeepLinkRouteResult(handled = false, routeType = "NONE")

        val matchedContacts = systemToolsBridge.searchPhoneContacts(waIntent.recipient, limit = 3)
        if (matchedContacts.size != 1) {
            // Jika 0 kontak atau > 1 kontak kembar di buku telepon, biarkan alur UI / Disambiguasi menangani
            return DeepLinkRouteResult(handled = false, routeType = "NONE")
        }

        val contact = matchedContacts.first()
        val uriString = buildWhatsAppDeepLinkUri(
            phone = contact.phoneNumber,
            message = waIntent.message
        )
        if (uriString.isEmpty()) {
            return DeepLinkRouteResult(handled = false, routeType = "NONE")
        }

        val launched = launchUriIntent(uriString, preferredPackage = "com.whatsapp")
        val speechCtx = speechContextProvider()
        return if (launched) {
            DeepLinkRouteResult(
                handled = true,
                routeType = "WHATSAPP_DIRECT_DRAFT",
                targetUriOrAction = uriString,
                summaryId = SovereignNaturalSpeech.deepLinkWhatsAppDraft(speechCtx, contact.displayName),
                requiresSubsequentUiVerify = true
            )
        } else {
            DeepLinkRouteResult(handled = false, routeType = "NONE")
        }
    }

    /**
     * Mengeksekusi *Zero-UI Deep-Link* yang langsung menyelesaikan tujuan pengguna
     * (seperti membuka halaman Pengaturan Wi-Fi/Bluetooth atau meluncurkan navigasi Google Maps).
     */
    fun tryExecuteImmediateDeepLinkGoal(userGoal: String): DeepLinkRouteResult {
        val clean = userGoal.trim()
        if (clean.isEmpty() || CrossAppWorkflowEngine.isCrossAppCommand(clean)) {
            return DeepLinkRouteResult(handled = false, routeType = "NONE")
        }
        val lowerClean = clean.lowercase()
        val isInformationalQuestion = Regex(
            """\b(?:cara|bagaimana|gimana|apa\s+itu|kenapa|mengapa|fungsi|perbedaan)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lowerClean)
        if (isInformationalQuestion) {
            return DeepLinkRouteResult(handled = false, routeType = "NONE")
        }
        val speechCtx = speechContextProvider()

        // 0. Panel Cepat Mengambang (Settings.Panel.ACTION_*) untuk Wi-Fi, Internet, Volume, NFC
        val floatingMatch = floatingPanelCommandRegex.find(clean)
        if (floatingMatch != null) {
            val panelKeyword = floatingMatch.groupValues[1].trim()
            val panelAction = resolveFloatingSettingsPanelAction(panelKeyword)
            if (panelAction != null && launchFloatingSettingsPanel(
                    panelAction = panelAction,
                    fallbackSettingsAction = resolveSystemSettingsAction(panelKeyword)
                )
            ) {
                return DeepLinkRouteResult(
                    handled = true,
                    routeType = "FLOATING_SETTINGS_PANEL",
                    targetUriOrAction = panelAction,
                    summaryId = SovereignNaturalSpeech.floatingPanelOpened(speechCtx, panelKeyword, true)
                )
            }
        }

        // 1. Cek perintah Pengaturan Sistem spesifik (Wi-Fi, Bluetooth, Suara, Layar, GPS)
        val settingsMatch = settingsCommandRegex.find(clean)
        if (settingsMatch != null) {
            val panelTarget = settingsMatch.groupValues[1].trim()
            val action = resolveSystemSettingsAction(panelTarget)
            if (action != null && launchSettingsAction(action)) {
                return DeepLinkRouteResult(
                    handled = true,
                    routeType = "SYSTEM_SETTINGS_DEEPLINK",
                    targetUriOrAction = action,
                    summaryId = SovereignNaturalSpeech.deepLinkSettingsOpened(speechCtx, panelTarget)
                )
            }
        }

        // 2. Cek perintah Navigasi Rute Google Maps
        val navMatch = mapsNavRegex.find(clean)
        if (navMatch != null) {
            val destination = navMatch.groupValues[1].trim()
            val uri = buildMapsDeepLinkUri(destination, navigateDirectly = true)
            if (uri.isNotEmpty() && launchUriIntent(uri, preferredPackage = "com.google.android.apps.maps")) {
                return DeepLinkRouteResult(
                    handled = true,
                    routeType = "MAPS_NAVIGATION_DEEPLINK",
                    targetUriOrAction = uri,
                    summaryId = SovereignNaturalSpeech.deepLinkMapsNavigation(speechCtx, destination)
                )
            }
        }

        // 3. Cek perintah Pencarian Lokasi di Google Maps
        val searchMatch = mapsSearchRegex.find(clean)
        if (searchMatch != null) {
            val place = searchMatch.groupValues[1].trim()
            val uri = buildMapsDeepLinkUri(place, navigateDirectly = false)
            if (uri.isNotEmpty() && launchUriIntent(uri, preferredPackage = "com.google.android.apps.maps")) {
                return DeepLinkRouteResult(
                    handled = true,
                    routeType = "MAPS_SEARCH_DEEPLINK",
                    targetUriOrAction = uri,
                    summaryId = SovereignNaturalSpeech.deepLinkMapsSearch(speechCtx, place)
                )
            }
        }

        return DeepLinkRouteResult(handled = false, routeType = "NONE")
    }

    /** Membuka formulir pembuatan acara Kalender Android dengan judul dan deskripsi terisi. */
    fun launchCalendarInsertIntent(
        title: String,
        description: String = "",
        beginTimeEpochMs: Long? = null,
        endTimeEpochMs: Long? = null
    ): Boolean {
        val baseCtx = context ?: return true
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title.trim())
                if (description.isNotBlank()) {
                    putExtra(CalendarContract.Events.DESCRIPTION, description.trim())
                }
                if (beginTimeEpochMs != null && beginTimeEpochMs > 0L) {
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeEpochMs)
                }
                if (endTimeEpochMs != null && endTimeEpochMs > 0L) {
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTimeEpochMs)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun launchSettingsAction(settingsAction: String): Boolean {
        val baseCtx = context ?: return true
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        return try {
            val intent = Intent(settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Meluncurkan *Floating Quick Settings Panel* (`Settings.Panel.*`) di atas aplikasi aktif,
     * dan otomatis beralih ke halaman pengaturan penuh [fallbackSettingsAction] bila OEM tidak
     * menyediakan aktivitas panel mengambang tersebut.
     */
    fun launchFloatingSettingsPanel(
        panelAction: String,
        fallbackSettingsAction: String? = null
    ): Boolean {
        if (launchSettingsAction(panelAction)) {
            return true
        }
        return fallbackSettingsAction != null && launchSettingsAction(fallbackSettingsAction)
    }

    fun launchUriIntent(uriString: String, preferredPackage: String? = null): Boolean {
        val baseCtx = context ?: return true
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        return try {
            val parsedUri = uriString.toUri()
            val primaryIntent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                if (!preferredPackage.isNullOrBlank()) {
                    setPackage(preferredPackage)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                launchCtx.startActivity(primaryIntent)
                true
            } catch (_: Throwable) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchCtx.startActivity(fallbackIntent)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
