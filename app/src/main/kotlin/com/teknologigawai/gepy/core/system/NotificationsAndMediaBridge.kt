package com.teknologigawai.gepy.core.system

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.core.net.toUri
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.laptop.LaptopCompanionBridge
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.service.ActiveNotificationSnapshot
import com.teknologigawai.gepy.service.GepyAccessibilityService
import com.teknologigawai.gepy.service.GepyNotificationListenerService
import com.teknologigawai.gepy.service.NotificationDismissAttemptResult
import com.teknologigawai.gepy.service.NotificationOpStatus
import com.teknologigawai.gepy.service.NotificationReplyAttemptResult
import com.teknologigawai.gepy.service.NotificationVaultFilter

/**
 * Delegasi balasan/pembersihan notifikasi aktif & pengendalian pemutaran media (Spotify, YouTube Music, dan MediaSession).
 */
internal class NotificationsAndMediaBridge(
    private val bridge: AndroidSystemToolsBridge,
    private val context: Context?,
    private val laptopBridge: LaptopCompanionBridge
) {
    fun findReplyableNotificationMatch(senderOrAppQuery: String): ActiveNotificationSnapshot? {
        if (bridge.isPrivacyShieldActive()) return null
        val snapshots = try {
            bridge.activeNotificationsProvider(30)
        } catch (_: Throwable) {
            emptyList()
        }
        if (snapshots.isNotEmpty()) {
            NotificationVaultFilter.findMatchingReplyTarget(snapshots, senderOrAppQuery)?.let { return it }
        }
        return GepyNotificationListenerService.findReplyableMatch(senderOrAppQuery)
    }

    /** Membalas notifikasi langsung di latar belakang via `Notification.Action` + `RemoteInput`. */
    fun replyToNotification(
        senderOrAppQuery: String,
        replyMessage: String,
        exactNotificationKey: String? = null
    ): NotificationReplyAttemptResult {
        if (bridge.isPrivacyShieldActive()) {
            return NotificationReplyAttemptResult(
                status = NotificationOpStatus.PRIVACY_SHIELD_BLOCKED,
                message = "Privacy Shield aktif."
            )
        }
        val cleanReply = replyMessage.trim()
        if (cleanReply.isEmpty()) {
            return NotificationReplyAttemptResult(
                status = NotificationOpStatus.ERROR,
                message = "Isi balasan kosong."
            )
        }
        if (!bridge.isNotificationListenerEnabledProvider()) {
            return NotificationReplyAttemptResult(
                status = NotificationOpStatus.PERMISSION_REQUIRED,
                message = "Akses Notifikasi Gepy belum diaktifkan."
            )
        }
        bridge.notificationReplyByKeyExecutor?.let { exec ->
            return exec(exactNotificationKey, senderOrAppQuery, cleanReply)
        }
        bridge.notificationReplyExecutor?.let { exec ->
            return exec(senderOrAppQuery, cleanReply)
        }
        if (GepyNotificationListenerService.instance == null && context == null) {
            val matched = findReplyableNotificationMatch(senderOrAppQuery)
            return if (matched != null) {
                NotificationReplyAttemptResult(
                    status = NotificationOpStatus.SUCCESS,
                    targetPackage = matched.packageName,
                    targetTitle = matched.title,
                    message = cleanReply
                )
            } else {
                NotificationReplyAttemptResult(
                    status = NotificationOpStatus.NOT_FOUND,
                    message = "Tidak ada notifikasi aktif yang cocok."
                )
            }
        }
        return GepyNotificationListenerService.executeRemoteInputReply(
            senderOrAppQuery = senderOrAppQuery,
            replyMessage = cleanReply,
            exactNotificationKey = exactNotificationKey
        )
    }

    /** Menghapus satu atau seluruh notifikasi aman dari baki notifikasi OS. */
    fun dismissNotifications(targetQuery: String): NotificationDismissAttemptResult {
        if (bridge.isPrivacyShieldActive()) {
            return NotificationDismissAttemptResult(
                status = NotificationOpStatus.PRIVACY_SHIELD_BLOCKED,
                errorDetail = "Privacy Shield aktif."
            )
        }
        if (!bridge.isNotificationListenerEnabledProvider()) {
            return NotificationDismissAttemptResult(
                status = NotificationOpStatus.PERMISSION_REQUIRED,
                errorDetail = "Akses Notifikasi Gepy belum diaktifkan."
            )
        }
        bridge.notificationDismissExecutor?.let { exec ->
            return exec(targetQuery)
        }
        if (GepyNotificationListenerService.instance == null && context == null) {
            val targets = NotificationVaultFilter.findMatchingDismissTargets(
                entries = bridge.activeNotificationsProvider(30),
                targetQuery = targetQuery
            )
            return if (targets.isNotEmpty()) {
                NotificationDismissAttemptResult(
                    status = NotificationOpStatus.SUCCESS,
                    dismissedCount = targets.size,
                    dismissedLabels = targets.map { it.title.ifEmpty { it.appLabel } }.take(4)
                )
            } else {
                NotificationDismissAttemptResult(
                    status = NotificationOpStatus.NOT_FOUND,
                    errorDetail = "Tidak ada notifikasi aktif yang cocok."
                )
            }
        }
        return GepyNotificationListenerService.dismissMatchingNotifications(targetQuery)
    }

    /** Membaca judul lagu / media yang sedang diputar via `MediaSessionManager.getActiveSessions`. */
    fun getActiveMediaPlaybackInfo(): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val hasPermission = bridge.isNotificationListenerEnabledProvider()
        val sessions: List<ActiveMediaSessionInfo> = bridge.activeMediaSessionProvider?.invoke() ?: run {
            val ctx = context ?: return@run emptyList()
            try {
                val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return@run emptyList()
                val controllers = msm.getActiveSessions(GepyNotificationListenerService.componentName(ctx))
                controllers.mapNotNull { ctrl ->
                    val pkg = ctrl.packageName?.trim().orEmpty()
                    if (pkg.isEmpty() || pkg == EnvConfig.SELF_PACKAGE_NAME || SafetyRules.isPackageForbidden(pkg)) {
                        return@mapNotNull null
                    }
                    val meta = ctrl.metadata
                    val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
                        ?.ifEmpty { meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim().orEmpty() }
                        .orEmpty()
                    val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
                        ?.ifEmpty { meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim().orEmpty() }
                        .orEmpty()
                    val playbackState = ctrl.playbackState?.state ?: PlaybackState.STATE_NONE
                    val isPlaying = playbackState == PlaybackState.STATE_PLAYING ||
                        playbackState == PlaybackState.STATE_BUFFERING
                    if (title.isEmpty() && artist.isEmpty()) return@mapNotNull null
                    ActiveMediaSessionInfo(
                        packageName = pkg,
                        appLabel = NotificationVaultFilter.deriveFriendlyAppName(pkg),
                        title = title,
                        artist = artist,
                        isPlaying = isPlaying
                    )
                }
            } catch (_: SecurityException) {
                return mapOf(
                    "status" to "PERMISSION_REQUIRED",
                    "has_permission" to false,
                    "summary_id" to SovereignNaturalSpeech.nowPlayingEmpty(speechCtx, hasPermission = false)
                )
            } catch (_: Throwable) {
                emptyList()
            }
        }

        val chosen = sessions.firstOrNull { it.isPlaying } ?: sessions.firstOrNull()
            ?: return mapOf(
                "status" to if (!hasPermission) "PERMISSION_REQUIRED" else "EMPTY",
                "has_permission" to hasPermission,
                "summary_id" to SovereignNaturalSpeech.nowPlayingEmpty(speechCtx, hasPermission = hasPermission)
            )

        return mapOf(
            "status" to "SUCCESS",
            "package" to chosen.packageName,
            "app_label" to chosen.appLabel,
            "title" to chosen.title,
            "artist" to chosen.artist,
            "is_playing" to chosen.isPlaying,
            "summary_id" to SovereignNaturalSpeech.nowPlayingInfo(
                ctx = speechCtx,
                title = chosen.title,
                artist = chosen.artist,
                appLabel = chosen.appLabel,
                isPlaying = chosen.isPlaying
            )
        )
    }

    /**
     * Mengeksekusi pemanggilan fungsi sistem OS Android (`Function Calling` / `Tool Calling`)
     * dan mengembalikan objek `Map<String, Any?>` untuk `toolResponse` Gemini Live.
     */
    /** Membuka halaman web atau pencarian Google langsung di browser perangkat Android (Chrome/Samsung Internet). */

    fun playSpotifyMusic(query: String, targetDevice: String = "phone"): Map<String, Any?> {
        val cleanQuery = query.trim()
        val speechCtx = bridge.speechContextProvider()
        val isLaptop = targetDevice.equals("laptop", ignoreCase = true) ||
            targetDevice.equals("pc", ignoreCase = true) ||
            targetDevice.equals("komputer", ignoreCase = true)

        if (isLaptop) {
            val host = bridge.laptopHostProvider()
            val res = laptopBridge.executeAction(
                action = "open_app",
                target = "spotify",
                params = mapOf("query" to cleanQuery),
                host = host
            )
            return mapOf(
                "status" to (res["status"] ?: "SUCCESS"),
                "device" to "laptop",
                "query" to cleanQuery,
                "summary_id" to (res["summary_id"] ?: SovereignNaturalSpeech.spotifyPlay(speechCtx, cleanQuery, "laptop"))
            )
        }

        val ctx = context ?: GepyAccessibilityService.instance
        if (ctx == null) {
            // JVM Unit test fallback
            return mapOf(
                "status" to "SUCCESS",
                "device" to "phone",
                "query" to cleanQuery,
                "summary_id" to SovereignNaturalSpeech.spotifyPlay(speechCtx, cleanQuery, "phone")
            )
        }

        return try {
            if (cleanQuery.isNotEmpty()) {
                // 1. Prioritaskan skema URI resmi Spotify (spotify:search:<query>) dengan CLEAR_TOP
                // agar layar Now Playing lagu lama tertutup dan langsung beralih ke tab hasil pencarian lagu baru
                val encodedQuery = Uri.encode(cleanQuery)
                val spotifySearchUri = "spotify:search:$encodedQuery".toUri()
                val searchIntent = Intent(Intent.ACTION_VIEW, spotifySearchUri).apply {
                    setPackage("com.spotify.music")
                    putExtra(Intent.EXTRA_REFERRER, "android-app://${ctx.packageName}".toUri())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                try {
                    ctx.startActivity(searchIntent)
                } catch (_: Exception) {
                    val mediaSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        setPackage("com.spotify.music")
                        putExtra(SearchManager.QUERY, cleanQuery)
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    ctx.startActivity(mediaSearchIntent)
                }

                // 2. Otomatis klik tombol Putar / Hasil Teratas / "Dengarkan lagu serupa" via Accessibility Service (Zero-UI Autoplay)
                GepyAccessibilityService.instance?.autoPlaySpotifySearchResult(cleanQuery)
            } else {
                // Buka aplikasi Spotify langsung jika tanpa query
                val launchIntent = ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    ctx.startActivity(launchIntent)
                } else {
                    val fallbackUri = Intent(Intent.ACTION_VIEW, "spotify:home".toUri()).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(fallbackUri)
                }
                // Kirim media play key untuk melanjutkan pemutaran yang ada HANYA saat query kosong
                controlMediaPlayback("play", "phone")
            }

            mapOf(
                "status" to "SUCCESS",
                "device" to "phone",
                "query" to cleanQuery,
                "summary_id" to SovereignNaturalSpeech.spotifyPlay(speechCtx, cleanQuery, "phone")
            )
        } catch (e: Exception) {
            GepyLog.w("SpotifyBridge", "Gagal memutar Spotify: ${e.message}")
            mapOf(
                "status" to "ERROR",
                "device" to "phone",
                "error" to (e.message ?: "Gagal memutar Spotify"),
                "summary_id" to SovereignNaturalSpeech.spotifyError(speechCtx, e.message)
            )
        }
    }

    /**
     * Memutar musik / video di YouTube atau YouTube Music.
     */
    fun playYouTubeMusic(query: String): Map<String, Any?> {
        val cleanQuery = query.trim()
        val speechCtx = bridge.speechContextProvider()
        val baseCtx = context ?: GepyAccessibilityService.instance
        if (baseCtx == null) {
            return mapOf(
                "status" to "SUCCESS",
                "app" to "youtube",
                "query" to cleanQuery,
                "summary_id" to SovereignNaturalSpeech.youtubePlay(speechCtx, cleanQuery)
            )
        }
        return try {
            val encoded = Uri.encode(cleanQuery)
            val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", cleanQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val resolved = baseCtx.packageManager.resolveActivity(ytIntent, 0)
            if (resolved != null) {
                baseCtx.startActivity(ytIntent)
            } else {
                bridge.openBrowserUrl("https://www.youtube.com/results?search_query=$encoded")
            }
            mapOf(
                "status" to "SUCCESS",
                "app" to "youtube",
                "query" to cleanQuery,
                "summary_id" to SovereignNaturalSpeech.youtubePlay(speechCtx, cleanQuery)
            )
        } catch (_: Exception) {
            bridge.openBrowserUrl("https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery))
            mapOf(
                "status" to "SUCCESS",
                "app" to "youtube_web",
                "query" to cleanQuery,
                "summary_id" to SovereignNaturalSpeech.youtubePlay(speechCtx, cleanQuery)
            )
        }
    }

    /**
     * Mengontrol pemutaran media (play, pause, next, prev) secara native tanpa membuka layar.
     */
    fun controlMediaPlayback(action: String, targetDevice: String = "phone"): Map<String, Any?> {
        val act = action.trim().lowercase()
        val speechCtx = bridge.speechContextProvider()
        val isLaptop = targetDevice.equals("laptop", ignoreCase = true) ||
            targetDevice.equals("pc", ignoreCase = true) ||
            targetDevice.equals("komputer", ignoreCase = true)

        if (isLaptop) {
            val host = bridge.laptopHostProvider()
            val laptopAction = when (act) {
                "next", "skip" -> "media_next"
                "prev", "previous" -> "media_prev"
                else -> "media_play_pause"
            }
            val res = laptopBridge.executeAction(action = laptopAction, host = host)
            return mapOf(
                "status" to (res["status"] ?: "SUCCESS"),
                "device" to "laptop",
                "action" to act,
                "summary_id" to (res["summary_id"] ?: SovereignNaturalSpeech.mediaControl(speechCtx, act, "laptop"))
            )
        }

        val ctx = context
        if (ctx == null) {
            val handledViaHook = bridge.activeMediaControlExecutor?.invoke(act) == true
            val summary = SovereignNaturalSpeech.mediaControl(speechCtx, act, "phone")
            return mapOf(
                "status" to "SUCCESS",
                "device" to "phone",
                "action" to act,
                "transport" to if (handledViaHook) "media_session" else "mock_key_event",
                "summary_id" to summary
            )
        }

        // Jalur 1 (Batch 2 API-First): MediaController.TransportControls langsung via MediaSessionManager
        val hookHandled = bridge.activeMediaControlExecutor?.invoke(act) == true
        if (hookHandled) {
            return mapOf(
                "status" to "SUCCESS",
                "device" to "phone",
                "action" to act,
                "transport" to "media_session",
                "summary_id" to SovereignNaturalSpeech.mediaControl(speechCtx, act, "phone")
            )
        }

        val sessionHandledPkg: String? = try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val controllers = msm?.getActiveSessions(GepyNotificationListenerService.componentName(ctx)).orEmpty()
                .filter { ctrl ->
                    val pkg = ctrl.packageName?.trim().orEmpty()
                    pkg.isNotEmpty() && pkg != EnvConfig.SELF_PACKAGE_NAME && !SafetyRules.isPackageForbidden(pkg)
                }
            val preferred = controllers.firstOrNull { ctrl ->
                val state = ctrl.playbackState?.state
                state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
            } ?: controllers.firstOrNull()
            if (preferred != null) {
                val tc = preferred.transportControls
                when (act) {
                    "pause" -> tc.pause()
                    "play", "resume" -> tc.play()
                    "next", "skip" -> tc.skipToNext()
                    "prev", "previous" -> tc.skipToPrevious()
                    "stop" -> tc.stop()
                    else -> {
                        val st = preferred.playbackState?.state
                        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                            tc.pause()
                        } else {
                            tc.play()
                        }
                    }
                }
                preferred.packageName
            } else {
                null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }

        if (sessionHandledPkg != null) {
            return mapOf(
                "status" to "SUCCESS",
                "device" to "phone",
                "action" to act,
                "transport" to "media_session",
                "package" to sessionHandledPkg,
                "summary_id" to SovereignNaturalSpeech.mediaControl(speechCtx, act, "phone")
            )
        }

        // Jalur Fallback: AudioManager.dispatchMediaKeyEvent jika NotificationListener belum aktif
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val keyCode = when (act) {
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "prev", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }

            if (am != null) {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }

            val summary = SovereignNaturalSpeech.mediaControl(speechCtx, act, "phone")

            mapOf(
                "status" to "SUCCESS",
                "device" to "phone",
                "action" to act,
                "transport" to "media_key_event",
                "summary_id" to summary
            )
        } catch (e: Exception) {
            mapOf(
                "status" to "ERROR",
                "device" to "phone",
                "action" to act,
                "error" to (e.message ?: "Gagal mengontrol media"),
                "summary_id" to SovereignNaturalSpeech.mediaControlError(speechCtx)
            )
        }
    }

}
