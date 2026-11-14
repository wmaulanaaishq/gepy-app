package com.teknologigawai.gepy.core.system

import android.content.Context
import com.teknologigawai.gepy.core.laptop.LaptopCompanionBridge
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.service.ActiveNotificationSnapshot
import com.teknologigawai.gepy.service.CapturedNotificationEntry
import com.teknologigawai.gepy.service.GepyNotificationListenerService
import com.teknologigawai.gepy.service.NotificationDismissAttemptResult
import com.teknologigawai.gepy.service.NotificationReplyAttemptResult
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Jembatan akses langsung ke API sistem OS Android (*Zero-UI Fast System Access*)
 * untuk menjawab pertanyaan status perangkat, waktu/jam, kontak telepon, notifikasi,
 * lokasi GPS, dan pemasangan alarm dalam `< 5 ms` tanpa perlu membuka UI aplikasi.
 *
 * Terintegrasi langsung dengan:
 * 1. **Gemini 3.8 Live Function Calling / Tool Calling** (`BidiGenerateContent`)
 * 2. **AndroidSystemToolSkill** (*Fast-Path Skill* `0` panggilan LLM)
 */
class AndroidSystemToolsBridge(
    private val context: Context? = null,
    private val clockProvider: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
    private val laptopBridge: LaptopCompanionBridge = LaptopCompanionBridge(),
    var laptopHostProvider: () -> String = { "" }
) {
    companion object {
        internal val INDONESIAN_LOCALE = Locale.forLanguageTag("id-ID")
        internal val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "EEEE, d MMMM yyyy 'pukul' HH:mm",
            INDONESIAN_LOCALE
        )
    }

    /** Penyedia ringkasan kebugaran & sensor CSI harian dari `WellnessAndGuardianEngine`. */
    @Volatile
    var wellnessStatusProvider: (() -> Map<String, Any?>)? = null

    /** Penyedia konteks persona, nama panggilan, & status sensor saat ini untuk bahasa alami deterministik. */
    @Volatile
    var speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }

    /** Status apakah panggilan dua arah Gemini Live sedang aktif agar perintah volume otomatis mengarah ke jalur telepon. */
    @Volatile
    var isLiveCallActiveProvider: () -> Boolean = { false }

    /** Pembaca skala volume Live Call (`0.0f..2.0f`) saat panggilan aktif. */
    @Volatile
    var liveCallVolumeGetter: () -> Float = { 1.5f }

    /** Pengubah skala volume Live Call (`0.0f..2.0f`) saat pengguna mengatur volume/mute lewat suara di tengah Live Call. */
    @Volatile
    var onLiveCallVolumeChanged: ((Float) -> Unit)? = null

    /** Kait pengujian JVM opsional untuk kontak buku telepon. */
    @Volatile
    var phoneContactsOverrideProvider: ((String, Int) -> List<PhoneContactItem>)? = null

    /** Kait pengujian JVM opsional untuk kontak email `(displayName, emailAddress)`. */
    @Volatile
    var emailContactsOverrideProvider: ((String) -> List<Pair<String, String>>)? = null

    /** Status aktif/tidaknya `GepyNotificationListenerService`. */
    @Volatile
    var isNotificationListenerEnabledProvider: () -> Boolean = {
        context?.let { GepyNotificationListenerService.isServiceEnabled(it) }
            ?: (GepyNotificationListenerService.instance != null)
    }

    /** Penyedia daftar notifikasi aktif aman dari baki sistem OS. */
    @Volatile
    var activeNotificationsProvider: (Int) -> List<ActiveNotificationSnapshot> = { limit ->
        GepyNotificationListenerService.getSafeActiveNotifications(limit)
    }

    /** Kait eksekutor `RemoteInput` untuk pengujian deterministik (opsional, mengabaikan key). */
    @Volatile
    var notificationReplyExecutor: ((String, String) -> NotificationReplyAttemptResult)? = null

    /** Kait eksekutor `RemoteInput` yang menerima `(exactKey, senderQuery, message)`. */
    @Volatile
    var notificationReplyByKeyExecutor: ((String?, String, String) -> NotificationReplyAttemptResult)? = null

    /** Kait eksekutor hapus notifikasi sistem untuk pengujian deterministik. */
    @Volatile
    var notificationDismissExecutor: ((String) -> NotificationDismissAttemptResult)? = null

    /** Kait pembaca sesi media aktif untuk pengujian deterministik. */
    @Volatile
    var activeMediaSessionProvider: (() -> List<ActiveMediaSessionInfo>)? = null

    /** Kait kontrol transport media aktif untuk pengujian deterministik. */
    @Volatile
    var activeMediaControlExecutor: ((String) -> Boolean)? = null


    private val telemetryDelegate = DeviceTelemetryAndContactsBridge(this, context, clockProvider)
    private val hardwareDelegate = DeviceHardwareAndIntentsBridge(this, context)
    private val notificationsMediaDelegate = NotificationsAndMediaBridge(this, context, laptopBridge)
    private val toolRouter = SystemToolCallRouter(this, context, laptopBridge)

    internal fun isPrivacyShieldActive(): Boolean =
        wellnessStatusProvider?.invoke()?.get("privacy_shield_active") == true

    // --- Delegasi API Publik Terjaga ke Komponen Kohesif (Tahap 6) ---

    fun copyToClipboard(text: String): Boolean = telemetryDelegate.copyToClipboard(text)

    fun readFromClipboard(): String = telemetryDelegate.readFromClipboard()

    fun getDeviceAndTimeStatus(): DeviceSystemStatus = telemetryDelegate.getDeviceAndTimeStatus()

    fun searchPhoneContacts(query: String, limit: Int = 5): List<PhoneContactItem> = telemetryDelegate.searchPhoneContacts(query, limit)

    fun searchEmailContacts(query: String, limit: Int = 5): List<Pair<String, String>> = telemetryDelegate.searchEmailContacts(query, limit)

    fun getRecentNotifications(limit: Int = 6): List<CapturedNotificationEntry> = telemetryDelegate.getRecentNotifications(limit)

    fun getLastKnownLocation(): DeviceLocationSnapshot? = telemetryDelegate.getLastKnownLocation()

    fun setSystemAlarm(hour: Int, minute: Int, label: String = "Alarm Gepy"): Boolean = hardwareDelegate.setSystemAlarm(hour, minute, label)

    fun setFlashlightTorch(enabled: Boolean): Map<String, Any?> = hardwareDelegate.setFlashlightTorch(enabled)

    fun setSystemTimer(
        durationSeconds: Int,
        label: String = "Timer Gepy",
        skipUi: Boolean = true
    ): Map<String, Any?> =
        hardwareDelegate.setSystemTimer(durationSeconds, label, skipUi)

    fun showSystemAlarms(): Map<String, Any?> = hardwareDelegate.showSystemAlarms()

    fun adjustAudioVolume(
        stream: String = "media",
        targetPercent: Int? = null,
        direction: String? = null
    ): Map<String, Any?> =
        hardwareDelegate.adjustAudioVolume(stream, targetPercent, direction)

    fun setRingerModeSafe(mode: String): Map<String, Any?> = hardwareDelegate.setRingerModeSafe(mode)

    fun openPhoneDialer(targetQuery: String, preResolvedPhone: String? = null): Map<String, Any?> = hardwareDelegate.openPhoneDialer(targetQuery, preResolvedPhone)

    fun openEmailComposer(
        recipientQuery: String = "",
        subject: String = "",
        body: String = ""
    ): Map<String, Any?> =
        hardwareDelegate.openEmailComposer(recipientQuery, subject, body)

    fun openFloatingSettingsPanel(panelKeyword: String): Map<String, Any?> = hardwareDelegate.openFloatingSettingsPanel(panelKeyword)

    fun findReplyableNotificationMatch(senderOrAppQuery: String): ActiveNotificationSnapshot? = notificationsMediaDelegate.findReplyableNotificationMatch(senderOrAppQuery)

    fun replyToNotification(
        senderOrAppQuery: String,
        replyMessage: String,
        exactNotificationKey: String? = null
    ): NotificationReplyAttemptResult =
        notificationsMediaDelegate.replyToNotification(senderOrAppQuery, replyMessage, exactNotificationKey)

    fun dismissNotifications(targetQuery: String): NotificationDismissAttemptResult = notificationsMediaDelegate.dismissNotifications(targetQuery)

    fun getActiveMediaPlaybackInfo(): Map<String, Any?> = notificationsMediaDelegate.getActiveMediaPlaybackInfo()

    fun openBrowserUrl(urlOrQuery: String): Boolean = hardwareDelegate.openBrowserUrl(urlOrQuery)

    fun playSpotifyMusic(query: String, targetDevice: String = "phone"): Map<String, Any?> = notificationsMediaDelegate.playSpotifyMusic(query, targetDevice)

    fun playYouTubeMusic(query: String): Map<String, Any?> = notificationsMediaDelegate.playYouTubeMusic(query)

    fun controlMediaPlayback(action: String, targetDevice: String = "phone"): Map<String, Any?> = notificationsMediaDelegate.controlMediaPlayback(action, targetDevice)

    fun executeSystemToolCall(name: String, args: Map<String, Any?>): Map<String, Any?> = toolRouter.executeSystemToolCall(name, args)
}

