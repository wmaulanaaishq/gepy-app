package com.teknologigawai.gepy.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Snapshot notifikasi aktif yang telah disanitasi dan bebas konten sensitif/perbankan. */
data class ActiveNotificationSnapshot(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestampMs: Long,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val hasRemoteInput: Boolean = false
) {
    val displaySummary: String
        get() {
            val cleanTitle = title.trim()
            val cleanText = text.trim()
            return when {
                cleanTitle.isNotEmpty() && cleanText.isNotEmpty() -> "$cleanTitle: $cleanText"
                cleanTitle.isNotEmpty() -> cleanTitle
                else -> cleanText
            }
        }
}

enum class NotificationOpStatus {
    SUCCESS,
    PERMISSION_REQUIRED,
    PRIVACY_SHIELD_BLOCKED,
    SAFETY_BLOCKED,
    NOT_FOUND,
    NO_REMOTE_INPUT,
    ERROR
}

data class NotificationReplyAttemptResult(
    val status: NotificationOpStatus,
    val targetPackage: String = "",
    val targetTitle: String = "",
    val message: String = ""
)

data class NotificationDismissAttemptResult(
    val status: NotificationOpStatus,
    val dismissedCount: Int = 0,
    val dismissedLabels: List<String> = emptyList(),
    val errorDetail: String = ""
)

/**
 * Penyaring deterministik berbasis JVM murni untuk notifikasi aktif & operasi `RemoteInput`:
 * 1. Memblokir mutlak aplikasi m-Banking, e-Wallet, dan dialog keamanan ([SafetyRules.isPackageForbidden]).
 * 2. Memblokir pesan yang berisi OTP, PIN, kata sandi, atau kode rahasia ([SafetyRules.isCredentialField]).
 * 3. Menyanitasi seluruh teks notifikasi terhadap *Indirect Prompt Injection* ([PromptInjectionSanitizer]).
 * 4. Menyediakan pencocokan target balas / hapus yang aman dan deterministik.
 */
object NotificationVaultFilter {

    private val extraOtpAndSecretRegex = Regex(
        """\b(?:kode\s*(?:otp|verifikasi|rahasia|keamanan|akses|autentikasi|autentikator|masuk|login)|one[- ]?time\s*password|two[- ]?factor|2fa)\b""",
        RegexOption.IGNORE_CASE
    )

    private val allClearKeywords = setOf(
        "semua", "semuanya", "seluruh", "all", "notif", "notifikasi"
    )

    private val appPackageAliasMap: Map<String, Set<String>> = mapOf(
        "wa" to setOf("com.whatsapp", "com.whatsapp.w4b"),
        "whatsapp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
        "telegram" to setOf("org.telegram.messenger", "org.telegram.plus"),
        "tele" to setOf("org.telegram.messenger", "org.telegram.plus"),
        "instagram" to setOf("com.instagram.android", "com.instagram.lite"),
        "ig" to setOf("com.instagram.android", "com.instagram.lite"),
        "gmail" to setOf("com.google.android.gm"),
        "email" to setOf("com.google.android.gm", "com.samsung.android.email.provider"),
        "sms" to setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
        "pesan" to setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
        "shopee" to setOf("com.shopee.id"),
        "tokopedia" to setOf("com.tokopedia.tkpd"),
        "gojek" to setOf("com.gojek.app"),
        "grab" to setOf("com.grabtaxi.passenger"),
        "youtube" to setOf("com.google.android.youtube")
    )

    /** Mengembalikan `true` bila konten teks mengandung indikasi kredensial/OTP/PIN sensitif. */
    fun isSensitiveCredentialContent(rawTitle: String, rawText: String): Boolean {
        val combined = "$rawTitle $rawText".trim()
        if (combined.isEmpty()) return false
        if (SafetyRules.isCredentialField(label = rawTitle) ||
            SafetyRules.isCredentialField(label = rawText) ||
            SafetyRules.isCredentialField(label = combined)
        ) {
            return true
        }
        return extraOtpAndSecretRegex.containsMatchIn(combined)
    }

    /** Menyanitasi & memvalidasi satu notifikasi; mengembalikan `null` jika tidak aman/kosong. */
    fun sanitizeOrDrop(
        key: String,
        packageName: String,
        appLabel: String,
        rawTitle: String,
        rawText: String,
        timestampMs: Long,
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
        hasRemoteInput: Boolean = false
    ): ActiveNotificationSnapshot? {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || cleanPkg == EnvConfig.SELF_PACKAGE_NAME) {
            return null
        }
        if (SafetyRules.isPackageForbidden(cleanPkg)) {
            return null
        }
        if (isSensitiveCredentialContent(rawTitle = rawTitle, rawText = rawText)) {
            return null
        }

        val cleanTitle = PromptInjectionSanitizer.sanitizeUiText(rawTitle, maxLength = 120)
        val cleanText = PromptInjectionSanitizer.sanitizeUiText(rawText, maxLength = 260)
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) {
            return null
        }

        val cleanAppLabel = appLabel.trim().ifEmpty { deriveFriendlyAppName(cleanPkg) }

        return ActiveNotificationSnapshot(
            key = key,
            packageName = cleanPkg,
            appLabel = cleanAppLabel,
            title = cleanTitle,
            text = cleanText,
            timestampMs = timestampMs,
            isOngoing = isOngoing,
            isGroupSummary = isGroupSummary,
            hasRemoteInput = hasRemoteInput
        )
    }

    /** Memilih notifikasi aktif terbaru yang aman untuk dibacakan (`!isOngoing`, `!isGroupSummary`). */
    fun selectReadableNotifications(
        entries: Collection<ActiveNotificationSnapshot>,
        limit: Int = 6
    ): List<ActiveNotificationSnapshot> {
        val bounded = limit.coerceIn(1, 20)
        return entries
            .asSequence()
            .filter { !it.isOngoing && !it.isGroupSummary }
            .filter { !SafetyRules.isPackageForbidden(it.packageName) && it.packageName != EnvConfig.SELF_PACKAGE_NAME }
            .filter { !isSensitiveCredentialContent(it.title, it.text) }
            .sortedByDescending { it.timestampMs }
            .take(bounded)
            .toList()
    }

    /** Mencari target notifikasi ber-`RemoteInput` yang paling tepat untuk membalas [senderOrAppQuery]. */
    fun findMatchingReplyTarget(
        entries: Collection<ActiveNotificationSnapshot>,
        senderOrAppQuery: String
    ): ActiveNotificationSnapshot? {
        val query = senderOrAppQuery.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return null

        val replyable = entries
            .asSequence()
            .filter { it.hasRemoteInput && !it.isOngoing && !it.isGroupSummary }
            .filter { !SafetyRules.isPackageForbidden(it.packageName) && it.packageName != EnvConfig.SELF_PACKAGE_NAME }
            .filter { !isSensitiveCredentialContent(it.title, it.text) }
            .sortedByDescending { it.timestampMs }
            .toList()

        if (replyable.isEmpty()) return null

        // 1. Kecocokan tepat pada judul/pengirim
        replyable.firstOrNull { it.title.trim().lowercase(Locale.ROOT) == query }?.let { return it }

        // 2. Kecocokan sebagian pada judul/pengirim (mis. "Budi" cocok ke "Budi Santoso" atau "Grup Keluarga: Budi")
        replyable.firstOrNull {
            val titleLower = it.title.trim().lowercase(Locale.ROOT)
            titleLower.isNotEmpty() && (titleLower.contains(query) || query.contains(titleLower))
        }?.let { return it }

        // 3. Jika query menyebut nama aplikasi (mis. "whatsapp" / "telegram") DAN hanya cocok lewat aplikasi
        val aliasPackages = appPackageAliasMap[query]
        if (aliasPackages != null) {
            return replyable.firstOrNull { it.packageName.lowercase(Locale.ROOT) in aliasPackages }
        }

        return null
    }

    /** Memilih notifikasi non-ongoing yang aman untuk dihapus sesuai [targetQuery]. */
    fun findMatchingDismissTargets(
        entries: Collection<ActiveNotificationSnapshot>,
        targetQuery: String
    ): List<ActiveNotificationSnapshot> {
        val cleanQuery = targetQuery.trim().lowercase(Locale.ROOT)
        val dismissible = entries
            .asSequence()
            .filter { !it.isOngoing }
            .filter { !SafetyRules.isPackageForbidden(it.packageName) && it.packageName != EnvConfig.SELF_PACKAGE_NAME }
            .sortedByDescending { it.timestampMs }
            .toList()

        if (cleanQuery.isEmpty() || cleanQuery in allClearKeywords) {
            return dismissible
        }

        val aliasPackages = appPackageAliasMap[cleanQuery].orEmpty()
        return dismissible.filter { entry ->
            val pkgLower = entry.packageName.lowercase(Locale.ROOT)
            val appLower = entry.appLabel.lowercase(Locale.ROOT)
            val titleLower = entry.title.lowercase(Locale.ROOT)
            val textLower = entry.text.lowercase(Locale.ROOT)
            pkgLower in aliasPackages ||
                appLower.contains(cleanQuery) ||
                titleLower.contains(cleanQuery) ||
                textLower.contains(cleanQuery) ||
                pkgLower.contains(cleanQuery)
        }
    }

    fun deriveFriendlyAppName(packageName: String): String {
        val lower = packageName.trim().lowercase(Locale.ROOT)
        return when {
            lower == "com.whatsapp" || lower == "com.whatsapp.w4b" -> "WhatsApp"
            lower == "org.telegram.messenger" -> "Telegram"
            lower.startsWith("com.instagram.") -> "Instagram"
            lower == "com.spotify.music" -> "Spotify"
            lower == "com.google.android.youtube" -> "YouTube"
            lower == "com.google.android.gm" -> "Gmail"
            lower == "com.shopee.id" -> "Shopee"
            lower == "com.tokopedia.tkpd" -> "Tokopedia"
            lower == "com.gojek.app" -> "Gojek"
            lower == "com.grabtaxi.passenger" -> "Grab"
            else -> lower.substringAfterLast('.').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }.ifEmpty { "Aplikasi" }
        }
    }
}

private data class LiveNotificationRecord(
    val snapshot: ActiveNotificationSnapshot,
    val replyPendingIntent: PendingIntent?,
    val remoteInputs: Array<RemoteInput>?
)

/**
 * Layanan pendengar notifikasi OS Android (`NotificationListenerService`) untuk **Gepy v6.6.0**.
 *
 * Berjalan dalam satu proses ART (*Zero-Polling Event-Driven*) untuk:
 * 1. Membaca notifikasi aktif terbaru secara akurat dari baki notifikasi OS.
 * 2. Membalas pesan langsung di latar belakang via `Notification.Action` + `RemoteInput` tanpa membuka UI aplikasi.
 * 3. Membersihkan notifikasi aman via `cancelNotification(key)`.
 * 4. Menyediakan `ComponentName` resmi untuk `MediaSessionManager.getActiveSessions(...)`.
 */
class GepyNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "GepyNotifListener"
        private const val MAX_ACTIVE_NOTIFICATIONS = 30

        @Volatile
        var instance: GepyNotificationListenerService? = null
            private set

        private val liveRecords = ConcurrentHashMap<String, LiveNotificationRecord>()
        private val appLabelCache = ConcurrentHashMap<String, String>()

        /** Mengembalikan `ComponentName` layanan untuk `MediaSessionManager` & `NotificationManager`. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.packageName, GepyNotificationListenerService::class.java.name)

        /** Memeriksa apakah izin Akses Notifikasi (`BIND_NOTIFICATION_LISTENER_SERVICE`) telah diberikan pengguna. */
        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.isNotificationListenerAccessGranted(componentName(context))) {
                    return true
                }
                val flat = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                val full = componentName(context).flattenToString()
                val short = "${context.packageName}/.service.GepyNotificationListenerService"
                flat.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
            } catch (_: Throwable) {
                false
            }
        }

        /** Mengembalikan daftar notifikasi aktif aman terbaru (diurutkan paling baru di atas). */
        fun getSafeActiveNotifications(limit: Int = 6): List<ActiveNotificationSnapshot> {
            instance?.syncFromSystemActiveNotifications()
            val snapshots = liveRecords.values.map { it.snapshot }
            return NotificationVaultFilter.selectReadableNotifications(snapshots, limit = limit)
        }

        /** Memeriksa apakah ada notifikasi ber-`RemoteInput` aktif yang cocok untuk [senderOrAppQuery]. */
        fun findReplyableMatch(senderOrAppQuery: String): ActiveNotificationSnapshot? {
            instance?.syncFromSystemActiveNotifications()
            val snapshots = liveRecords.values.map { it.snapshot }
            return NotificationVaultFilter.findMatchingReplyTarget(snapshots, senderOrAppQuery)
        }

        /** Mengeksekusi balasan langsung di latar belakang via `RemoteInput.addResultsToIntent` + `PendingIntent.send`. */
        fun executeRemoteInputReply(
            senderOrAppQuery: String,
            replyMessage: String,
            exactNotificationKey: String? = null
        ): NotificationReplyAttemptResult {
            val service = instance ?: return NotificationReplyAttemptResult(
                status = NotificationOpStatus.PERMISSION_REQUIRED,
                message = "Akses Notifikasi Gepy belum aktif."
            )
            val cleanReply = replyMessage.trim()
            if (cleanReply.isEmpty()) {
                return NotificationReplyAttemptResult(
                    status = NotificationOpStatus.ERROR,
                    message = "Isi balasan kosong."
                )
            }

            service.syncFromSystemActiveNotifications()
            val matchedRecord = if (!exactNotificationKey.isNullOrBlank()) {
                liveRecords[exactNotificationKey]?.takeIf {
                    it.snapshot.hasRemoteInput &&
                        !SafetyRules.isPackageForbidden(it.snapshot.packageName) &&
                        !NotificationVaultFilter.isSensitiveCredentialContent(it.snapshot.title, it.snapshot.text)
                }
            } else {
                val snap = NotificationVaultFilter.findMatchingReplyTarget(
                    entries = liveRecords.values.map { it.snapshot },
                    senderOrAppQuery = senderOrAppQuery
                )
                snap?.let { liveRecords[it.key] }
            } ?: return NotificationReplyAttemptResult(
                status = NotificationOpStatus.NOT_FOUND,
                message = "Tidak ada notifikasi aktif yang bisa dibalas untuk '$senderOrAppQuery'."
            )

            if (SafetyRules.isPackageForbidden(matchedRecord.snapshot.packageName) ||
                NotificationVaultFilter.isSensitiveCredentialContent(matchedRecord.snapshot.title, matchedRecord.snapshot.text)
            ) {
                return NotificationReplyAttemptResult(
                    status = NotificationOpStatus.SAFETY_BLOCKED,
                    targetPackage = matchedRecord.snapshot.packageName,
                    targetTitle = matchedRecord.snapshot.title,
                    message = "Notifikasi sensitif atau perbankan diblokir oleh SafetyRules."
                )
            }

            val pendingIntent = matchedRecord.replyPendingIntent
            val remoteInputs = matchedRecord.remoteInputs
            if (pendingIntent == null || remoteInputs.isNullOrEmpty()) {
                return NotificationReplyAttemptResult(
                    status = NotificationOpStatus.NO_REMOTE_INPUT,
                    targetPackage = matchedRecord.snapshot.packageName,
                    targetTitle = matchedRecord.snapshot.title,
                    message = "Notifikasi tidak memiliki saluran balas cepat."
                )
            }

            return try {
                val fillInIntent = Intent()
                val resultsBundle = Bundle()
                for (remoteInput in remoteInputs) {
                    if (remoteInput.allowFreeFormInput) {
                        resultsBundle.putCharSequence(remoteInput.resultKey, cleanReply)
                    }
                }
                if (resultsBundle.isEmpty) {
                    resultsBundle.putCharSequence(remoteInputs.first().resultKey, cleanReply)
                }
                RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, resultsBundle)
                pendingIntent.send(service, 0, fillInIntent)

                GepyLog.i(
                    TAG,
                    "Direct RemoteInput reply terkirim ke '${matchedRecord.snapshot.title}' (${matchedRecord.snapshot.packageName})"
                )
                NotificationReplyAttemptResult(
                    status = NotificationOpStatus.SUCCESS,
                    targetPackage = matchedRecord.snapshot.packageName,
                    targetTitle = matchedRecord.snapshot.title.ifEmpty { matchedRecord.snapshot.appLabel },
                    message = cleanReply
                )
            } catch (ce: PendingIntent.CanceledException) {
                GepyLog.w(TAG, "PendingIntent balasan kedaluwarsa/dibatalkan: ${ce.message}")
                NotificationReplyAttemptResult(
                    status = NotificationOpStatus.ERROR,
                    targetPackage = matchedRecord.snapshot.packageName,
                    targetTitle = matchedRecord.snapshot.title,
                    message = "Aksi balas notifikasi sudah kedaluwarsa."
                )
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Gagal mengirim RemoteInput reply: ${t.message}")
                NotificationReplyAttemptResult(
                    status = NotificationOpStatus.ERROR,
                    targetPackage = matchedRecord.snapshot.packageName,
                    targetTitle = matchedRecord.snapshot.title,
                    message = t.message ?: "Gagal mengirim balasan lewat notifikasi."
                )
            }
        }

        /** Menghapus satu atau seluruh notifikasi aman yang sedang tampil di baki sistem. */
        fun dismissMatchingNotifications(targetQuery: String): NotificationDismissAttemptResult {
            val service = instance ?: return NotificationDismissAttemptResult(
                status = NotificationOpStatus.PERMISSION_REQUIRED,
                errorDetail = "Akses Notifikasi Gepy belum aktif."
            )
            service.syncFromSystemActiveNotifications()
            val targets = NotificationVaultFilter.findMatchingDismissTargets(
                entries = liveRecords.values.map { it.snapshot },
                targetQuery = targetQuery
            )
            if (targets.isEmpty()) {
                return NotificationDismissAttemptResult(
                    status = NotificationOpStatus.NOT_FOUND,
                    errorDetail = "Tidak ada notifikasi aktif yang cocok."
                )
            }

            var removed = 0
            val removedLabels = mutableListOf<String>()
            for (target in targets) {
                try {
                    service.cancelNotification(target.key)
                    liveRecords.remove(target.key)
                    removed++
                    val label = target.title.ifEmpty { target.appLabel }
                    if (label.isNotBlank() && label !in removedLabels) {
                        removedLabels.add(label)
                    }
                } catch (t: Throwable) {
                    GepyLog.w(TAG, "Gagal membatalkan notifikasi ${target.key}: ${t.message}")
                }
            }

            return if (removed > 0) {
                NotificationDismissAttemptResult(
                    status = NotificationOpStatus.SUCCESS,
                    dismissedCount = removed,
                    dismissedLabels = removedLabels.take(4)
                )
            } else {
                NotificationDismissAttemptResult(
                    status = NotificationOpStatus.ERROR,
                    errorDetail = "Tidak berhasil menghapus notifikasi dari sistem."
                )
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        syncFromSystemActiveNotifications()
        GepyLog.i(TAG, "GepyNotificationListenerService terhubung (${liveRecords.size} notifikasi aktif aman).")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
        }
        liveRecords.clear()
        GepyLog.i(TAG, "GepyNotificationListenerService terputus.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        ingestStatusBarNotification(sbn)
        trimBoundedCache()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        liveRecords.remove(sbn.key)
    }

    private fun syncFromSystemActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            val seenKeys = HashSet<String>(active.size)
            for (sbn in active) {
                seenKeys.add(sbn.key)
                ingestStatusBarNotification(sbn)
            }
            liveRecords.keys.removeIf { it !in seenKeys }
            trimBoundedCache()
        } catch (_: Throwable) {
        }
    }

    private fun ingestStatusBarNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == EnvConfig.SELF_PACKAGE_NAME || SafetyRules.isPackageForbidden(pkg)) {
            liveRecords.remove(sbn.key)
            return
        }

        val notif = sbn.notification ?: return
        val extras = notif.extras
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?.ifEmpty { extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty() }
            .orEmpty()
        val rawText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?.ifEmpty { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() }
            .orEmpty()

        val isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val replyAction = findReplyAction(notif)
        val appLabel = resolveAppLabel(pkg)

        val snapshot = NotificationVaultFilter.sanitizeOrDrop(
            key = sbn.key,
            packageName = pkg,
            appLabel = appLabel,
            rawTitle = rawTitle,
            rawText = rawText,
            timestampMs = sbn.postTime,
            isOngoing = sbn.isOngoing,
            isGroupSummary = isGroupSummary,
            hasRemoteInput = replyAction != null
        )

        if (snapshot == null) {
            liveRecords.remove(sbn.key)
            return
        }

        liveRecords[sbn.key] = LiveNotificationRecord(
            snapshot = snapshot,
            replyPendingIntent = replyAction?.actionIntent,
            remoteInputs = replyAction?.remoteInputs
        )
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        val candidates = actions.filter { action ->
            action.actionIntent != null &&
                action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { action ->
            val t = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
            t.contains("balas") || t.contains("reply") || t.contains("jawab")
        } ?: candidates.first()
    }

    private fun resolveAppLabel(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val resolved = try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString().trim()
        } catch (_: Throwable) {
            ""
        }.ifEmpty { NotificationVaultFilter.deriveFriendlyAppName(packageName) }
        if (appLabelCache.size < 80) {
            appLabelCache[packageName] = resolved
        }
        return resolved
    }

    private fun trimBoundedCache() {
        if (liveRecords.size <= MAX_ACTIVE_NOTIFICATIONS) return
        val overflow = liveRecords.size - MAX_ACTIVE_NOTIFICATIONS
        val oldestKeys = liveRecords.values
            .sortedBy { it.snapshot.timestampMs }
            .take(overflow)
            .map { it.snapshot.key }
        for (oldKey in oldestKeys) {
            liveRecords.remove(oldKey)
        }
    }
}
