package com.teknologigawai.gepy.core.system

import android.content.Context
import com.teknologigawai.gepy.core.laptop.LaptopCompanionBridge
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.service.NotificationOpStatus
import com.teknologigawai.gepy.service.NotificationVaultFilter
import java.util.Locale

/**
 * Router eksekusi pemanggilan tool sistem (`executeSystemToolCall`) untuk Gemini Live 3.8 & ReAct.
 */
internal class SystemToolCallRouter(
    private val bridge: AndroidSystemToolsBridge,
    private val context: Context?,
    private val laptopBridge: LaptopCompanionBridge
) {
    fun executeSystemToolCall(name: String, args: Map<String, Any?>): Map<String, Any?> {
        val speechCtx = bridge.speechContextProvider()
        return when (name) {
            "open_browser_url" -> {
                val urlOrQuery = args["url"]?.toString()?.trim().orEmpty()
                val ok = bridge.openBrowserUrl(urlOrQuery)
                mapOf(
                    "status" to if (ok) "SUCCESS" else "ERROR",
                    "url" to urlOrQuery,
                    "summary_id" to SovereignNaturalSpeech.browserOpened(speechCtx, urlOrQuery, ok)
                )
            }

            "get_device_and_time_status" -> bridge.getDeviceAndTimeStatus().toMap(speechCtx)

            "search_phone_contacts" -> {
                val query = args["query"]?.toString()?.trim().orEmpty()
                val contacts = bridge.searchPhoneContacts(query)
                mapOf(
                    "status" to "SUCCESS",
                    "query" to query,
                    "count" to contacts.size,
                    "contacts" to contacts.map {
                        mapOf("name" to it.displayName, "phone" to it.phoneNumber)
                    },
                    "summary_id" to if (contacts.isEmpty()) {
                        SovereignNaturalSpeech.contactsNotFound(speechCtx, query)
                    } else {
                        contacts.joinToString("; ") { "${it.displayName}: ${it.phoneNumber}" }
                    }
                )
            }

            "get_recent_notifications" -> {
                if (bridge.isPrivacyShieldActive()) {
                    return mapOf(
                        "status" to "BLOCKED_BY_PRIVACY_SHIELD",
                        "count" to 0,
                        "notifications" to emptyList<Map<String, String>>(),
                        "summary_id" to SovereignNaturalSpeech.notificationsPrivacyBlocked(speechCtx)
                    )
                }
                val items = bridge.getRecentNotifications(limit = 6)
                mapOf(
                    "status" to "SUCCESS",
                    "count" to items.size,
                    "notifications" to items.map {
                        mapOf(
                            "package" to it.packageName,
                            "app_label" to NotificationVaultFilter.deriveFriendlyAppName(it.packageName),
                            "text" to it.summaryText
                        )
                    },
                    "summary_id" to if (items.isEmpty()) {
                        SovereignNaturalSpeech.notificationsEmpty(speechCtx)
                    } else {
                        items.joinToString(". ") {
                            "${NotificationVaultFilter.deriveFriendlyAppName(it.packageName)}: ${it.summaryText}"
                        }
                    }
                )
            }

            "get_current_location" -> {
                val loc = bridge.getLastKnownLocation()
                if (loc == null) {
                    mapOf(
                        "status" to "UNAVAILABLE",
                        "summary_id" to SovereignNaturalSpeech.locationUnavailable(speechCtx)
                    )
                } else {
                    mapOf(
                        "status" to "SUCCESS",
                        "latitude" to loc.latitude,
                        "longitude" to loc.longitude,
                        "accuracy_meters" to loc.accuracyMeters,
                        "provider" to loc.provider,
                        "summary_id" to loc.toNaturalSummaryId(speechCtx)
                    )
                }
            }

            "set_system_alarm" -> {
                val hour = (args["hour"] as? Number)?.toInt()
                    ?: args["hour"]?.toString()?.toIntOrNull()
                    ?: -1
                val minute = (args["minute"] as? Number)?.toInt()
                    ?: args["minute"]?.toString()?.toIntOrNull()
                    ?: 0
                val label = args["label"]?.toString()?.trim().orEmpty().ifEmpty { "Alarm Gepy" }
                val ok = bridge.setSystemAlarm(hour = hour, minute = minute, label = label)
                mapOf(
                    "status" to if (ok) "SUCCESS" else "ERROR",
                    "hour" to hour,
                    "minute" to minute,
                    "label" to label,
                    "summary_id" to SovereignNaturalSpeech.alarmSet(speechCtx, hour, minute, label, ok)
                )
            }

            "open_system_settings_panel" -> {
                val panel = args["panel"]?.toString()?.trim().orEmpty()
                val action = IntentDeepLinkRouter.resolveSystemSettingsAction(panel)
                val router = IntentDeepLinkRouter(context = context, systemToolsBridge = bridge)
                val ok = action != null && router.launchSettingsAction(action)
                mapOf(
                    "status" to if (ok) "SUCCESS" else "ERROR",
                    "panel" to panel,
                    "summary_id" to if (ok) {
                        SovereignNaturalSpeech.deepLinkSettingsOpened(speechCtx, panel)
                    } else {
                        SovereignNaturalSpeech.deepLinkSettingsUnknown(speechCtx, panel)
                    }
                )
            }

            "open_maps_navigation" -> {
                val destination = args["destination"]?.toString()?.trim().orEmpty()
                val navDirectly = (args["navigate_directly"] as? Boolean)
                    ?: (args["navigate_directly"]?.toString()?.equals("true", ignoreCase = true) == true)
                val uri = IntentDeepLinkRouter.buildMapsDeepLinkUri(destination, navigateDirectly = navDirectly)
                val router = IntentDeepLinkRouter(context = context, systemToolsBridge = bridge)
                val ok = uri.isNotEmpty() && router.launchUriIntent(uri, preferredPackage = "com.google.android.apps.maps")
                mapOf(
                    "status" to if (ok) "SUCCESS" else "ERROR",
                    "destination" to destination,
                    "uri" to uri,
                    "summary_id" to when {
                        ok && navDirectly -> SovereignNaturalSpeech.deepLinkMapsNavigation(speechCtx, destination)
                        ok -> SovereignNaturalSpeech.deepLinkMapsSearch(speechCtx, destination)
                        else -> SovereignNaturalSpeech.mapsOpenError(speechCtx)
                    }
                )
            }

            "execute_laptop_command" -> {
                val action = args["action"]?.toString()?.trim().orEmpty().ifEmpty { "status" }
                val target = args["target"]?.toString()?.trim().orEmpty()
                val host = bridge.laptopHostProvider()
                val res = laptopBridge.executeAction(
                    action = action,
                    target = target,
                    params = args,
                    host = host
                )
                if (res["summary_id"] == null) {
                    val fallback = when (action.lowercase()) {
                        "lock", "lock_screen", "kunci_layar" -> SovereignNaturalSpeech.laptopLockFallback(speechCtx)
                        "sleep", "suspend", "tidur", "mode_tidur" -> SovereignNaturalSpeech.laptopSleepFallback(speechCtx)
                        "volume", "audio" -> SovereignNaturalSpeech.laptopVolumeFallback(speechCtx)
                        "open_app", "launch", "buka" -> SovereignNaturalSpeech.laptopOpenAppFallback(speechCtx, target)
                        "media", "media_play_pause", "play_pause" -> SovereignNaturalSpeech.laptopMediaFallback(speechCtx)
                        else -> SovereignNaturalSpeech.laptopGenericFallback(speechCtx)
                    }
                    res + ("summary_id" to fallback)
                } else {
                    res
                }
            }

            "get_laptop_status" -> {
                val host = bridge.laptopHostProvider()
                val res = laptopBridge.getLaptopStatus(host = host)
                if (res["summary_id"] == null) {
                    res + ("summary_id" to SovereignNaturalSpeech.laptopStatusFallback(speechCtx))
                } else {
                    res
                }
            }

            "sync_laptop_clipboard" -> {
                val mode = args["mode"]?.toString()?.trim()?.uppercase().orEmpty().ifEmpty { "READ" }
                val host = bridge.laptopHostProvider()
                if (mode == "WRITE") {
                    val text = args["text"]?.toString().orEmpty()
                    laptopBridge.setClipboardText(text = text, host = host)
                } else {
                    laptopBridge.getClipboardText(host = host)
                }
            }

            "execute_antigravity_rpc" -> {
                val action = args["action"]?.toString()?.trim()?.uppercase().orEmpty().ifEmpty { "WORKSPACE_HEALTH" }
                val target = args["target"]?.toString()?.trim().orEmpty()
                val prompt = args["prompt"]?.toString()?.trim().orEmpty()
                val isAsync = args["async"]?.toString()?.toBooleanStrictOrNull()
                    ?: (action in listOf("BUILD_RELEASE", "VERIFY_ALL", "RUN_TESTS", "CODE_TASK"))
                val host = bridge.laptopHostProvider()
                val callbackUrl = "http://${com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge.resolveDeviceBestIpv4()}:8765/api/antigravity/callback"
                val res = laptopBridge.executeAntigravityRpc(
                    request = com.teknologigawai.gepy.core.laptop.AntigravityRpcRequest(
                        action = action,
                        target = target,
                        prompt = prompt,
                        isAsync = isAsync,
                        callbackUrl = callbackUrl
                    ),
                    host = host
                )
                mapOf(
                    "status" to res.status,
                    "action" to action,
                    "summary_id" to res.summaryTts,
                    "data" to res.resultData
                )
            }

            "control_spotify_playback",
            "play_music" -> {
                val action = args["action"]?.toString()?.trim().orEmpty().ifEmpty { "play_search" }
                val query = args["query"]?.toString()?.trim().orEmpty()
                val device = args["device"]?.toString()?.trim().orEmpty().ifEmpty { "phone" }
                val app = args["app"]?.toString()?.trim()?.lowercase().orEmpty()
                if ((action == "youtube" || app == "youtube" || device == "youtube") && query.isNotEmpty()) {
                    bridge.playYouTubeMusic(query = query)
                } else if (action in listOf("play_search", "search", "play") && (query.isNotEmpty() || app == "spotify" || name == "control_spotify_playback")) {
                    bridge.playSpotifyMusic(query = query, targetDevice = device)
                } else {
                    bridge.controlMediaPlayback(action = action, targetDevice = device)
                }
            }

            "control_device_system" -> {
                val action = args["action"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val target = args["target"]?.toString()?.trim().orEmpty()
                val value = args["value"]?.toString()?.trim().orEmpty()
                val durationSeconds = (args["duration_seconds"] as? Number)?.toInt()
                    ?: args["duration_seconds"]?.toString()?.toIntOrNull()
                    ?: value.toIntOrNull()
                    ?: 0
                val label = args["label"]?.toString()?.trim().orEmpty()
                val subject = args["subject"]?.toString()?.trim().orEmpty()
                val body = args["body"]?.toString()?.trim().orEmpty()

                when (action) {
                    "flashlight_on", "torch_on" -> bridge.setFlashlightTorch(enabled = true)
                    "flashlight_off", "torch_off" -> bridge.setFlashlightTorch(enabled = false)
                    "flashlight", "torch" -> {
                        val normVal = value.lowercase(Locale.ROOT)
                        val turnOff = normVal == "off" || normVal == "mati" || normVal == "false" || normVal == "0"
                        bridge.setFlashlightTorch(enabled = !turnOff)
                    }
                    "set_timer", "timer" -> bridge.setSystemTimer(
                        durationSeconds = durationSeconds,
                        label = label.ifEmpty { target }.ifEmpty { "Timer Gepy" }
                    )
                    "show_alarms", "list_alarms" -> bridge.showSystemAlarms()
                    "set_volume", "adjust_volume", "volume" -> {
                        val pct = (args["percent"] as? Number)?.toInt()
                            ?: args["percent"]?.toString()?.toIntOrNull()
                            ?: value.removeSuffix("%").trim().toIntOrNull()
                        bridge.adjustAudioVolume(
                            stream = target.ifEmpty { "media" },
                            targetPercent = pct,
                            direction = if (pct == null) value.ifEmpty { "up" } else null
                        )
                    }
                    "set_ringer_mode", "ringer_mode" -> bridge.setRingerModeSafe(
                        mode = value.ifEmpty { target }.ifEmpty { "normal" }
                    )
                    "open_dialer", "dial_phone", "dial" -> bridge.openPhoneDialer(
                        targetQuery = target.ifEmpty { value }
                    )
                    "compose_email", "send_email" -> bridge.openEmailComposer(
                        recipientQuery = target,
                        subject = subject,
                        body = body
                    )
                    "open_panel", "floating_panel" -> bridge.openFloatingSettingsPanel(
                        panelKeyword = target.ifEmpty { value }
                    )
                    "now_playing", "media_now_playing" -> bridge.getActiveMediaPlaybackInfo()
                    else -> mapOf(
                        "status" to "ERROR",
                        "error" to "Aksi control_device_system tidak dikenali: $action"
                    )
                }
            }

            "manage_notifications" -> {
                if (bridge.isPrivacyShieldActive()) {
                    return mapOf(
                        "status" to "BLOCKED_BY_PRIVACY_SHIELD",
                        "count" to 0,
                        "notifications" to emptyList<Map<String, String>>(),
                        "summary_id" to SovereignNaturalSpeech.notificationsPrivacyBlocked(speechCtx)
                    )
                }
                val action = args["action"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty().ifEmpty { "read" }
                val target = args["sender_or_app"]?.toString()?.trim().orEmpty()
                    .ifEmpty { args["target"]?.toString()?.trim().orEmpty() }
                val replyMessage = args["reply_message"]?.toString()?.trim().orEmpty()
                    .ifEmpty { args["message"]?.toString()?.trim().orEmpty() }

                when (action) {
                    "reply" -> {
                        val res = bridge.replyToNotification(senderOrAppQuery = target, replyMessage = replyMessage)
                        when (res.status) {
                            NotificationOpStatus.SUCCESS -> mapOf(
                                "status" to "SUCCESS",
                                "action" to "reply",
                                "target" to res.targetTitle,
                                "package" to res.targetPackage,
                                "summary_id" to SovereignNaturalSpeech.notificationReplied(
                                    speechCtx,
                                    res.targetTitle.ifEmpty { target }
                                )
                            )
                            NotificationOpStatus.PERMISSION_REQUIRED -> mapOf(
                                "status" to "PERMISSION_REQUIRED",
                                "action" to "reply",
                                "should_ui_fallback" to true,
                                "summary_id" to SovereignNaturalSpeech.notificationAccessRequired(speechCtx)
                            )
                            NotificationOpStatus.PRIVACY_SHIELD_BLOCKED -> mapOf(
                                "status" to "BLOCKED_BY_PRIVACY_SHIELD",
                                "action" to "reply",
                                "summary_id" to SovereignNaturalSpeech.notificationsPrivacyBlocked(speechCtx)
                            )
                            NotificationOpStatus.SAFETY_BLOCKED -> mapOf(
                                "status" to "BLOCKED_BY_SAFETY",
                                "action" to "reply",
                                "summary_id" to SovereignNaturalSpeech.notificationsSafetyBlocked(speechCtx)
                            )
                            NotificationOpStatus.NOT_FOUND, NotificationOpStatus.NO_REMOTE_INPUT -> mapOf(
                                "status" to res.status.name,
                                "action" to "reply",
                                "target" to target,
                                "should_ui_fallback" to true,
                                "summary_id" to SovereignNaturalSpeech.notificationTargetNotFound(speechCtx, target)
                            )
                            NotificationOpStatus.ERROR -> mapOf(
                                "status" to "ERROR",
                                "action" to "reply",
                                "error" to res.message,
                                "should_ui_fallback" to true,
                                "summary_id" to SovereignNaturalSpeech.notificationTargetNotFound(speechCtx, target)
                            )
                        }
                    }

                    "dismiss" -> {
                        val res = bridge.dismissNotifications(targetQuery = target)
                        when (res.status) {
                            NotificationOpStatus.SUCCESS -> mapOf(
                                "status" to "SUCCESS",
                                "action" to "dismiss",
                                "dismissed_count" to res.dismissedCount,
                                "summary_id" to SovereignNaturalSpeech.notificationDismissed(
                                    speechCtx,
                                    res.dismissedCount,
                                    res.dismissedLabels
                                )
                            )
                            NotificationOpStatus.PERMISSION_REQUIRED -> mapOf(
                                "status" to "PERMISSION_REQUIRED",
                                "action" to "dismiss",
                                "summary_id" to SovereignNaturalSpeech.notificationAccessRequired(speechCtx)
                            )
                            NotificationOpStatus.PRIVACY_SHIELD_BLOCKED -> mapOf(
                                "status" to "BLOCKED_BY_PRIVACY_SHIELD",
                                "action" to "dismiss",
                                "summary_id" to SovereignNaturalSpeech.notificationsPrivacyBlocked(speechCtx)
                            )
                            else -> mapOf(
                                "status" to res.status.name,
                                "action" to "dismiss",
                                "target" to target,
                                "summary_id" to SovereignNaturalSpeech.notificationTargetNotFound(
                                    speechCtx,
                                    target.ifEmpty { "semua" }
                                )
                            )
                        }
                    }

                    else -> {
                        val items = bridge.getRecentNotifications(limit = 6)
                            .let { list ->
                                if (target.isBlank() || target.equals("all", ignoreCase = true)) list
                                else {
                                    val q = target.lowercase(Locale.ROOT)
                                    list.filter {
                                        it.summaryText.lowercase(Locale.ROOT).contains(q) ||
                                            it.packageName.lowercase(Locale.ROOT).contains(q) ||
                                            NotificationVaultFilter.deriveFriendlyAppName(it.packageName)
                                                .lowercase(Locale.ROOT).contains(q)
                                    }
                                }
                            }
                        mapOf(
                            "status" to "SUCCESS",
                            "action" to "read",
                            "count" to items.size,
                            "notifications" to items.map {
                                mapOf(
                                    "package" to it.packageName,
                                    "app_label" to NotificationVaultFilter.deriveFriendlyAppName(it.packageName),
                                    "text" to it.summaryText
                                )
                            },
                            "summary_id" to if (items.isEmpty()) {
                                SovereignNaturalSpeech.notificationsEmpty(speechCtx)
                            } else {
                                items.joinToString(". ") {
                                    "${NotificationVaultFilter.deriveFriendlyAppName(it.packageName)}: ${it.summaryText}"
                                }
                            }
                        )
                    }
                }
            }

            "get_personal_wellness_status" -> {
                bridge.wellnessStatusProvider?.invoke() ?: mapOf(
                    "status" to "SUCCESS",
                    "step_count" to 0,
                    "social_scroll_minutes" to 0,
                    "has_external_csi_telemetry" to false,
                    "sedentary_bed_minutes" to 0,
                    "summary_id" to SovereignNaturalSpeech.wellnessEmptyFallback(speechCtx)
                )
            }

            else -> mapOf(
                "status" to "ERROR",
                "error" to "System tool tidak dikenali: $name"
            )
        }
    }
}
