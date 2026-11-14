package com.teknologigawai.gepy.core.system

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.net.toUri
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.service.GepyAccessibilityService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Delegasi kontrol perangkat keras & intent sistem Android (alarm, timer, senter, volume, dering, dialer, email, browser).
 */
internal class DeviceHardwareAndIntentsBridge(
    private val bridge: AndroidSystemToolsBridge,
    private val context: Context?
) {
    /** Memasang alarm sistem Android secara langsung melalui `AlarmClock.ACTION_SET_ALARM`. */
    fun setSystemAlarm(hour: Int, minute: Int, label: String = "Alarm Gepy"): Boolean {
        if (hour !in 0..23 || minute !in 0..59) {
            return false
        }
        val baseCtx = context ?: return true
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun formatTimerDurationIndonesian(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return buildList {
            if (h > 0) add("$h jam")
            if (m > 0) add("$m menit")
            if (s > 0) add("$s detik")
        }.joinToString(" ").ifEmpty { "$totalSeconds detik" }
    }

    /** Menyalakan atau mematikan lampu senter kamera belakang via `CameraManager.setTorchMode` (< 5 ms). */
    fun setFlashlightTorch(enabled: Boolean): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val ctx = context ?: return mapOf(
            "status" to "SUCCESS",
            "enabled" to enabled,
            "summary_id" to SovereignNaturalSpeech.torchToggled(speechCtx, enabled, success = true)
        )
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return mapOf(
                    "status" to "ERROR",
                    "enabled" to enabled,
                    "summary_id" to SovereignNaturalSpeech.torchToggled(speechCtx, enabled, success = false)
                )
            var backFlashCamId: String? = null
            var anyFlashCamId: String? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!hasFlash) continue
                if (anyFlashCamId == null) anyFlashCamId = id
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backFlashCamId = id
                    break
                }
            }
            val targetId = backFlashCamId ?: anyFlashCamId
            if (targetId == null) {
                return mapOf(
                    "status" to "ERROR",
                    "enabled" to enabled,
                    "summary_id" to SovereignNaturalSpeech.torchToggled(speechCtx, enabled, success = false)
                )
            }
            cm.setTorchMode(targetId, enabled)
            mapOf(
                "status" to "SUCCESS",
                "enabled" to enabled,
                "camera_id" to targetId,
                "summary_id" to SovereignNaturalSpeech.torchToggled(speechCtx, enabled, success = true)
            )
        } catch (t: Throwable) {
            GepyLog.w("SystemTools", "Gagal mengatur torch senter: ${t.message}")
            mapOf(
                "status" to "ERROR",
                "enabled" to enabled,
                "error" to (t.message ?: "Gagal mengatur senter"),
                "summary_id" to SovereignNaturalSpeech.torchToggled(speechCtx, enabled, success = false)
            )
        }
    }

    /** Memasang penghitung waktu mundur (*timer*) via `AlarmClock.ACTION_SET_TIMER` tanpa mengklik UI Clock. */
    fun setSystemTimer(
        durationSeconds: Int,
        label: String = "Timer Gepy",
        skipUi: Boolean = true
    ): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val cleanLabel = label.trim().ifEmpty { "Timer Gepy" }
        if (durationSeconds !in 1..86_400) {
            return mapOf(
                "status" to "ERROR",
                "duration_seconds" to durationSeconds,
                "summary_id" to SovereignNaturalSpeech.timerSet(speechCtx, "$durationSeconds detik", cleanLabel, success = false)
            )
        }
        val durationLabel = formatTimerDurationIndonesian(durationSeconds)
        val baseCtx = context ?: return mapOf(
            "status" to "SUCCESS",
            "duration_seconds" to durationSeconds,
            "label" to cleanLabel,
            "summary_id" to SovereignNaturalSpeech.timerSet(speechCtx, durationLabel, cleanLabel, success = true)
        )
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        val ok = try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, cleanLabel)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (t: Throwable) {
            GepyLog.w("SystemTools", "Gagal setSystemTimer: ${t.message}")
            false
        }
        return mapOf(
            "status" to if (ok) "SUCCESS" else "ERROR",
            "duration_seconds" to durationSeconds,
            "label" to cleanLabel,
            "summary_id" to SovereignNaturalSpeech.timerSet(speechCtx, durationLabel, cleanLabel, success = ok)
        )
    }

    /** Membaca jadwal alarm terdekat via `AlarmManager.nextAlarmClock` dan membuka daftar alarm sistem. */
    fun showSystemAlarms(): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val baseCtx = context ?: return mapOf(
            "status" to "SUCCESS",
            "next_alarm" to null,
            "opened_ui" to true,
            "summary_id" to SovereignNaturalSpeech.alarmsDisplayed(speechCtx, nextAlarmFormatted = null, openedUi = true)
        )
        val nextAlarmFormatted = try {
            val am = baseCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val info = am?.nextAlarmClock
            if (info != null && info.triggerTime > 0L) {
                val triggerDt = Instant.ofEpochMilli(info.triggerTime).atZone(ZoneId.systemDefault())
                DateTimeFormatter.ofPattern("HH:mm (EEEE)", AndroidSystemToolsBridge.INDONESIAN_LOCALE).format(triggerDt)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        val openedUi = try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
        return mapOf(
            "status" to if (openedUi || nextAlarmFormatted != null) "SUCCESS" else "ERROR",
            "next_alarm" to nextAlarmFormatted,
            "opened_ui" to openedUi,
            "summary_id" to SovereignNaturalSpeech.alarmsDisplayed(speechCtx, nextAlarmFormatted, openedUi)
        )
    }

    /** Mengatur volume stream (`media`, `ring`, `alarm`, `notification`) via `AudioManager` (< 5 ms). */
    fun adjustAudioVolume(
        stream: String = "media",
        targetPercent: Int? = null,
        direction: String? = null
    ): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val normStream = stream.trim().lowercase(Locale.ROOT)
        val liveCallActive = bridge.isLiveCallActiveProvider()
        val isGenericStreamRequest = normStream.isEmpty() ||
            normStream == "media" ||
            normStream.contains("suara") ||
            normStream.contains("gepy") ||
            normStream.contains("live") ||
            normStream.contains("volume")
        val (streamType, streamLabelId) = when {
            normStream.contains("ring") || normStream.contains("dering") ->
                AudioManager.STREAM_RING to "dering"
            normStream.contains("alarm") ->
                AudioManager.STREAM_ALARM to "alarm"
            normStream.contains("notif") ->
                AudioManager.STREAM_NOTIFICATION to "notifikasi"
            normStream.contains("call") || normStream.contains("telepon") || (liveCallActive && isGenericStreamRequest) ->
                AudioManager.STREAM_VOICE_CALL to "telepon"
            else -> AudioManager.STREAM_MUSIC to "media"
        }
        val normDir = direction?.trim()?.lowercase(Locale.ROOT)
        if (streamType == AudioManager.STREAM_VOICE_CALL && liveCallActive && bridge.onLiveCallVolumeChanged != null) {
            val currentScale = bridge.liveCallVolumeGetter().coerceIn(0f, 2f)
            val newScale = when {
                targetPercent != null -> ((targetPercent.coerceIn(0, 100) / 100f) * 2.0f).coerceIn(0f, 2f)
                normDir == "max" -> 2.0f
                normDir == "mute" -> 0f
                normDir == "unmute" -> if (currentScale <= 0.05f) 1.5f else currentScale
                normDir in listOf("down", "turun", "kecil", "lower") -> (currentScale - 0.35f).coerceIn(0f, 2f)
                else -> (currentScale + 0.35f).coerceIn(0f, 2f)
            }
            bridge.onLiveCallVolumeChanged?.invoke(newScale)
        }
        if (context == null) {
            val effectivePct = targetPercent?.coerceIn(0, 100)
                ?: if (normDir == "max") 100 else null
            return mapOf(
                "status" to "SUCCESS",
                "stream" to streamLabelId,
                "percent" to effectivePct,
                "direction" to normDir,
                "summary_id" to SovereignNaturalSpeech.volumeAdjusted(speechCtx, streamLabelId, effectivePct, normDir)
            )
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return mapOf(
                    "status" to "ERROR",
                    "summary_id" to SovereignNaturalSpeech.volumeError(speechCtx)
                )
            val minVol = am.getStreamMinVolume(streamType)
            val maxVol = am.getStreamMaxVolume(streamType).coerceAtLeast(minVol + 1)
            val span = (maxVol - minVol).coerceAtLeast(1)
            if (targetPercent != null) {
                val pct = targetPercent.coerceIn(0, 100)
                val targetIndex = (minVol + ((span * pct) / 100.0).roundToInt()).coerceIn(minVol, maxVol)
                am.setStreamVolume(streamType, targetIndex, 0)
                val appliedPct = (((am.getStreamVolume(streamType) - minVol) * 100.0) / span).roundToInt().coerceIn(0, 100)
                mapOf(
                    "status" to "SUCCESS",
                    "stream" to streamLabelId,
                    "percent" to appliedPct,
                    "summary_id" to SovereignNaturalSpeech.volumeAdjusted(speechCtx, streamLabelId, appliedPct, null)
                )
            } else {
                when (normDir) {
                    "max" -> {
                        am.setStreamVolume(streamType, maxVol, 0)
                        mapOf(
                            "status" to "SUCCESS",
                            "stream" to streamLabelId,
                            "percent" to 100,
                            "summary_id" to SovereignNaturalSpeech.volumeAdjusted(speechCtx, streamLabelId, 100, null)
                        )
                    }
                    "mute" -> {
                        am.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                        mapOf(
                            "status" to "SUCCESS",
                            "stream" to streamLabelId,
                            "direction" to "mute",
                            "summary_id" to SovereignNaturalSpeech.volumeAdjusted(speechCtx, streamLabelId, null, "mute")
                        )
                    }
                    "unmute" -> {
                        am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                        mapOf(
                            "status" to "SUCCESS",
                            "stream" to streamLabelId,
                            "direction" to "unmute",
                            "summary_id" to SovereignNaturalSpeech.volumeAdjusted(speechCtx, streamLabelId, null, "unmute")
                        )
                    }
                    "down", "turun", "kecil", "lower" -> {
                        am.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
                        val curPct = (((am.getStreamVolume(streamType) - minVol) * 100.0) / span).roundToInt().coerceIn(0, 100)
                        mapOf(
                            "status" to "SUCCESS",
                            "stream" to streamLabelId,
                            "percent" to curPct,
                            "direction" to "down",
                            "summary_id" to SovereignNaturalSpeech.volumeAdjusted(speechCtx, streamLabelId, curPct, "down")
                        )
                    }
                    else -> {
                        am.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
                        val curPct = (((am.getStreamVolume(streamType) - minVol) * 100.0) / span).roundToInt().coerceIn(0, 100)
                        mapOf(
                            "status" to "SUCCESS",
                            "stream" to streamLabelId,
                            "percent" to curPct,
                            "direction" to "up",
                            "summary_id" to SovereignNaturalSpeech.volumeAdjusted(speechCtx, streamLabelId, curPct, "up")
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
            mapOf(
                "status" to "ERROR",
                "stream" to streamLabelId,
                "summary_id" to SovereignNaturalSpeech.volumeError(speechCtx)
            )
        } catch (t: Throwable) {
            GepyLog.w("SystemTools", "Gagal adjustAudioVolume: ${t.message}")
            mapOf(
                "status" to "ERROR",
                "stream" to streamLabelId,
                "summary_id" to SovereignNaturalSpeech.volumeError(speechCtx)
            )
        }
    }

    /** Mengatur mode dering (`normal`, `vibrate`/`getar`, `silent`/`senyap`) secara aman tanpa crash DND. */
    fun setRingerModeSafe(mode: String): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val norm = mode.trim().lowercase(Locale.ROOT)
        val (targetMode, modeLabel) = when {
            norm.contains("getar") || norm.contains("vibrate") ->
                AudioManager.RINGER_MODE_VIBRATE to "Getar"
            norm.contains("senyap") || norm.contains("silent") || norm.contains("hening") || norm.contains("diam") ->
                AudioManager.RINGER_MODE_SILENT to "Senyap"
            else ->
                AudioManager.RINGER_MODE_NORMAL to "Normal"
        }
        val baseCtx = context ?: return mapOf(
            "status" to "SUCCESS",
            "mode" to modeLabel,
            "needs_dnd_permission" to false,
            "summary_id" to SovereignNaturalSpeech.ringerModeChanged(speechCtx, modeLabel, needsDndPermission = false)
        )
        return try {
            val am = baseCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return mapOf(
                    "status" to "ERROR",
                    "summary_id" to SovereignNaturalSpeech.volumeError(speechCtx)
                )
            val nm = baseCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (targetMode == AudioManager.RINGER_MODE_SILENT &&
                nm?.isNotificationPolicyAccessGranted != true
            ) {
                try {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Throwable) {
                }
                val launchCtx = GepyAccessibilityService.instance ?: baseCtx
                try {
                    launchCtx.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (_: Throwable) {
                }
                return mapOf(
                    "status" to "NEEDS_DND_PERMISSION",
                    "mode" to "Getar",
                    "needs_dnd_permission" to true,
                    "summary_id" to SovereignNaturalSpeech.ringerModeChanged(speechCtx, "Senyap", needsDndPermission = true)
                )
            }
            am.ringerMode = targetMode
            mapOf(
                "status" to "SUCCESS",
                "mode" to modeLabel,
                "needs_dnd_permission" to false,
                "summary_id" to SovereignNaturalSpeech.ringerModeChanged(speechCtx, modeLabel, needsDndPermission = false)
            )
        } catch (_: SecurityException) {
            mapOf(
                "status" to "NEEDS_DND_PERMISSION",
                "mode" to modeLabel,
                "needs_dnd_permission" to true,
                "summary_id" to SovereignNaturalSpeech.ringerModeChanged(speechCtx, modeLabel, needsDndPermission = true)
            )
        } catch (_: Throwable) {
            mapOf(
                "status" to "ERROR",
                "summary_id" to SovereignNaturalSpeech.volumeError(speechCtx)
            )
        }
    }

    /** Membuka layar dialer telepon (`Intent.ACTION_DIAL` — aman tanpa auto-call paksa) dari nama kontak atau nomor. */
    fun openPhoneDialer(targetQuery: String, preResolvedPhone: String? = null): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val rawTarget = (preResolvedPhone?.takeIf { it.isNotBlank() } ?: targetQuery).trim()
        val looksLikeNumber = Regex("""^[+]?[0-9\s\-().*#]{4,}$""").matches(rawTarget)
        var resolvedName = rawTarget
        var resolvedNumber = ""

        if (looksLikeNumber) {
            resolvedNumber = rawTarget
        } else {
            val matches = bridge.searchPhoneContacts(rawTarget, limit = 5)
            when {
                matches.isEmpty() -> {
                    return mapOf(
                        "status" to "NOT_FOUND",
                        "query" to rawTarget,
                        "summary_id" to SovereignNaturalSpeech.contactsNotFound(speechCtx, rawTarget)
                    )
                }
                matches.size > 1 -> {
                    val exactMatches = matches.filter { it.displayName.equals(rawTarget, ignoreCase = true) }
                    val chosen = if (exactMatches.size == 1) exactMatches else matches
                    if (chosen.size > 1) {
                        val candidates = chosen.map { "${it.displayName} (${it.phoneNumber})" }
                        return mapOf(
                            "status" to "AMBIGUOUS",
                            "query" to rawTarget,
                            "candidates" to candidates,
                            "summary_id" to SovereignNaturalSpeech.gatekeeperDisambiguate(speechCtx, chosen.size, rawTarget)
                        )
                    }
                    resolvedName = chosen.first().displayName
                    resolvedNumber = chosen.first().phoneNumber
                }
                else -> {
                    resolvedName = matches.first().displayName
                    resolvedNumber = matches.first().phoneNumber
                }
            }
        }

        val telUri = IntentDeepLinkRouter.buildTelDialUri(resolvedNumber)
        if (telUri.isEmpty()) {
            return mapOf(
                "status" to "NOT_FOUND",
                "query" to rawTarget,
                "summary_id" to SovereignNaturalSpeech.contactsNotFound(speechCtx, rawTarget)
            )
        }

        val baseCtx = context ?: return mapOf(
            "status" to "SUCCESS",
            "contact_label" to resolvedName,
            "phone" to resolvedNumber,
            "uri" to telUri,
            "summary_id" to SovereignNaturalSpeech.dialerOpened(speechCtx, resolvedName, success = true)
        )
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        val ok = try {
            val intent = Intent(Intent.ACTION_DIAL, telUri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (t: Throwable) {
            GepyLog.w("SystemTools", "Gagal membuka Dialer: ${t.message}")
            false
        }
        return mapOf(
            "status" to if (ok) "SUCCESS" else "ERROR",
            "contact_label" to resolvedName,
            "phone" to resolvedNumber,
            "uri" to telUri,
            "summary_id" to SovereignNaturalSpeech.dialerOpened(speechCtx, resolvedName, success = ok)
        )
    }

    /** Membuka komposer email (`Intent.ACTION_SENDTO` skema `mailto:`) dengan penerima/subjek/isi terisi. */
    fun openEmailComposer(
        recipientQuery: String = "",
        subject: String = "",
        body: String = ""
    ): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val rawRecipient = recipientQuery.trim()
        var targetEmail = ""
        var displayLabel = ""

        if (rawRecipient.contains('@') && !rawRecipient.contains(' ')) {
            targetEmail = rawRecipient
            displayLabel = rawRecipient
        } else if (rawRecipient.isNotEmpty()) {
            val matches = bridge.searchEmailContacts(rawRecipient, limit = 3)
            if (matches.isNotEmpty()) {
                displayLabel = matches.first().first
                targetEmail = matches.first().second
            }
        }

        val mailtoUri = IntentDeepLinkRouter.buildMailtoUri(
            recipient = targetEmail,
            subject = subject,
            body = body
        )
        val baseCtx = context ?: return mapOf(
            "status" to "SUCCESS",
            "recipient" to targetEmail,
            "subject" to subject,
            "uri" to mailtoUri,
            "summary_id" to SovereignNaturalSpeech.emailComposerOpened(speechCtx, displayLabel, success = true)
        )
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        val ok = try {
            val intent = Intent(Intent.ACTION_SENDTO, mailtoUri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (t: Throwable) {
            GepyLog.w("SystemTools", "Gagal membuka email composer: ${t.message}")
            false
        }
        return mapOf(
            "status" to if (ok) "SUCCESS" else "ERROR",
            "recipient" to targetEmail,
            "subject" to subject,
            "uri" to mailtoUri,
            "summary_id" to SovereignNaturalSpeech.emailComposerOpened(speechCtx, displayLabel, success = ok)
        )
    }

    /** Menampilkan panel pengaturan mengambang (`Settings.Panel.*`) dengan fallback ke Settings penuh. */
    fun openFloatingSettingsPanel(panelKeyword: String): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        val panelAction = IntentDeepLinkRouter.resolveFloatingSettingsPanelAction(panelKeyword)
        val fallbackAction = IntentDeepLinkRouter.resolveSystemSettingsAction(panelKeyword)
        val router = IntentDeepLinkRouter(context = context, systemToolsBridge = bridge)
        val ok = when {
            panelAction != null -> router.launchFloatingSettingsPanel(panelAction, fallbackAction)
            fallbackAction != null -> router.launchSettingsAction(fallbackAction)
            else -> false
        }
        return mapOf(
            "status" to if (ok) "SUCCESS" else "ERROR",
            "panel" to panelKeyword,
            "action" to (panelAction ?: fallbackAction ?: ""),
            "summary_id" to run {
                val displayLabel = when (panelKeyword.lowercase(Locale.ROOT)) {
                    "wifi", "wi-fi" -> "Wi-Fi"
                    "internet" -> "Internet"
                    "volume" -> "Volume"
                    "nfc" -> "NFC"
                    else -> panelKeyword.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
                SovereignNaturalSpeech.floatingPanelOpened(speechCtx, displayLabel, ok)
            }
        )
    }

    /** Mencari notifikasi aktif yang mendukung balas cepat `RemoteInput` secara deterministik. */

    fun openBrowserUrl(urlOrQuery: String): Boolean {
        val trimmed = urlOrQuery.trim()
        if (trimmed.isEmpty()) return false
        val validUrl = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else if (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.startsWith("google.com/search", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
        val baseCtx = context ?: return true
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx
        return try {
            val intent = Intent(Intent.ACTION_VIEW, validUrl.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Memutar musik / lagu / artis / album di Spotify secara instan (< 50 ms)
     * tanpa membuka layar antarmuka pencarian secara manual.
     */
}
