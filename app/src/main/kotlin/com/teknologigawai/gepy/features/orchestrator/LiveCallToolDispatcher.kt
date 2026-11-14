package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.errors.ForbiddenPackageException
import com.teknologigawai.gepy.core.orchestrator.UnifiedActionSupervisor
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.core.web.WebSearchAndReaderBridge
import com.teknologigawai.gepy.core.wellness.DailyBriefingEngine
import com.teknologigawai.gepy.features.brain.TaskPlannerAndVerifier
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.features.voice.GeminiLiveToolCall
import com.teknologigawai.gepy.features.voice.SpeculativePreFetcher
import com.teknologigawai.gepy.features.voice.VoiceClauseChunker
import java.util.Locale

/**
 * Dispatcher eksekusi *function calling* (`BidiGenerateContent`) selama sesi panggilan suara
 * Gemini Live full-duplex berlangsung.
 */
internal class LiveCallToolDispatcher(
    private val perceptionPort: PerceptionPort,
    private val voiceHudPort: VoiceHudPort,
    private val appLauncher: AppIntentLauncher,
    private val systemToolsBridge: AndroidSystemToolsBridge,
    private val webSearchBridge: WebSearchAndReaderBridge,
    private val deepLinkRouter: IntentDeepLinkRouter,
    private val hapticManager: HapticFeedbackManager,
    private val byokPreferencesManager: ByokPreferencesManager,
    private val dailyBriefingEngine: DailyBriefingEngine?,
    private val unifiedSupervisor: UnifiedActionSupervisor,
    private val speculativePreFetcher: SpeculativePreFetcher,
    private val goalHost: OrchestratorGoalHost
) {
    suspend fun handleLiveToolCall(toolCall: GeminiLiveToolCall): Map<String, Any?> {
        return when (toolCall.name) {
            "read_current_screen" -> {
                val activePkg = perceptionPort.currentForegroundPackage
                val speculative = speculativePreFetcher.consumeFreshSnapshotIfAvailable(
                    currentForegroundPackage = activePkg
                )
                val snap = speculative?.snapshot ?: perceptionPort.capturePruneSnapshot()
                val pkg = speculative?.foregroundPackage?.ifEmpty { activePkg }
                    ?: perceptionPort.currentForegroundPackage
                val visibleTexts = snap.elements
                    .filter { it.text.isNotBlank() }
                    .take(25)
                    .map { "[${it.index}] ${it.text}" }
                mapOf(
                    "status" to "SUCCESS",
                    "speculative_hit" to (speculative != null),
                    "foreground_package" to pkg,
                    "element_count" to snap.elements.size,
                    "visible_elements" to visibleTexts
                )
            }

            "cancel_android_goal" -> {
                goalHost.cancelActiveGoal("Dibatalkan melalui perintah suara Gemini Live.")
                mapOf(
                    "status" to "CANCELLED",
                    "message" to "Eksekusi tugas Android telah dihentikan."
                )
            }

            "get_active_task_status" -> {
                unifiedSupervisor.getCurrentSnapshot().toToolResponseMap()
            }

            "pause_or_resume_goal" -> {
                val action = (toolCall.args["action"]?.toString() ?: "").trim().lowercase()
                val reason = toolCall.args["reason"]?.toString()?.trim() ?: "Permintaan suara pengguna"
                when (action) {
                    "pause" -> {
                        val paused = unifiedSupervisor.pauseForVoiceIntervention(reason)
                        mapOf(
                            "status" to if (paused) "PAUSED" else "IGNORED",
                            "message" to if (paused) "Tugas berhasil dijeda sementara." else "Tidak ada tugas aktif yang sedang berjalan."
                        )
                    }
                    "resume" -> {
                        val resumed = unifiedSupervisor.resumeExecution()
                        mapOf(
                            "status" to if (resumed) "RESUMED" else "IGNORED",
                            "message" to if (resumed) "Tugas dilanjutkan kembali." else "Tugas tidak dalam status jeda."
                        )
                    }
                    else -> mapOf("status" to "ERROR", "error" to "Parameter action harus bernilai 'pause' atau 'resume'.")
                }
            }

            "open_application" -> {
                val appName = (toolCall.args["app_name"]?.toString() ?: "").trim()
                if (appName.isEmpty()) {
                    return mapOf(
                        "status" to "ERROR",
                        "error" to "Parameter app_name kosong."
                    )
                }

                val lowerApp = appName.lowercase()
                val isDesktopExclusive = lowerApp in listOf(
                    "vscode", "vs code", "visual studio code", "code", "notepad"
                ) || lowerApp.contains("di laptop") || lowerApp.contains("di pc") || lowerApp.contains("di komputer")

                if (isDesktopExclusive && byokPreferencesManager.currentSettings.laptopCompanionEnabled) {
                    val cleanTarget = when {
                        lowerApp.contains("notepad") -> "notepad"
                        lowerApp.contains("chrome") -> "chrome"
                        lowerApp.contains("spotify") -> "spotify"
                        lowerApp.contains("terminal") -> "terminal"
                        else -> "vscode"
                    }
                    val res = systemToolsBridge.executeSystemToolCall(
                        "execute_laptop_command",
                        mapOf("action" to "open_app", "target" to cleanTarget)
                    )
                    hapticManager.vibrateSuccess()
                    val summary = res["summary_id"]?.toString() ?: "Aplikasi '$cleanTarget' di laptop berhasil dibuka via Tailscale."
                    return mapOf(
                        "status" to (res["status"] ?: "SUCCESS"),
                        "target" to cleanTarget,
                        "resolved_package" to "com.microsoft.vscode.laptop",
                        "message" to summary,
                        "summary_id" to summary
                    )
                }

                goalHost.cancelAsyncGoalForImmediatePath()
                return try {
                    val res = appLauncher.openApp(appName)
                    if (res.success) {
                        hapticManager.vibrateSuccess()
                        goalHost.updateStateDirectly {
                            it.copy(
                                fsmState = GepyFsmState.COMPLETED,
                                currentGoal = "Buka $appName",
                                currentPackage = res.resolvedPackage,
                                lastThought = "Membuka aplikasi $appName.",
                                lastActionLabel = "OPEN_APP"
                            )
                        }
                        mapOf(
                            "status" to "SUCCESS",
                            "resolved_package" to res.resolvedPackage,
                            "message" to "Aplikasi $appName berhasil dibuka di layar."
                        )
                    } else if (byokPreferencesManager.currentSettings.laptopCompanionEnabled &&
                        (lowerApp.contains("code") || lowerApp.contains("studio") || lowerApp.contains("terminal") || lowerApp.contains("pc") || lowerApp.contains("laptop"))
                    ) {
                        val resLaptop = systemToolsBridge.executeSystemToolCall(
                            "execute_laptop_command",
                            mapOf("action" to "open_app", "target" to "vscode")
                        )
                        hapticManager.vibrateSuccess()
                        val summary = resLaptop["summary_id"]?.toString() ?: "Aplikasi '$appName' dibuka di laptop via Tailscale."
                        mapOf(
                            "status" to "SUCCESS",
                            "target" to "vscode",
                            "message" to summary,
                            "summary_id" to summary
                        )
                    } else {
                        mapOf(
                            "status" to "ERROR",
                            "error" to res.message
                        )
                    }
                } catch (e: ForbiddenPackageException) {
                    hapticManager.vibrateError()
                    mapOf(
                        "status" to "BLOCKED_BY_SAFETY",
                        "error" to e.message
                    )
                }
            }

            "execute_android_goal" -> {
                val goal = (toolCall.args["goal"]?.toString() ?: "").trim()
                if (goal.isEmpty()) {
                    return mapOf(
                        "status" to "ERROR",
                        "error" to "Parameter goal kosong."
                    )
                }
                val initialApp = toolCall.args["initial_app"]?.toString()?.trim().orEmpty()
                val enrichedGoal = if (initialApp.isNotEmpty() &&
                    !goal.lowercase().startsWith("buka ") &&
                    !goal.lowercase().startsWith("masuk ke ")
                ) {
                    "Buka $initialApp lalu $goal"
                } else {
                    goal
                }

                // Local-or-Escalate Dual-Path Duplex Routing (arXiv:2611.01639v1):
                // 1. Jalur Lokal Instan (< 15 ms): jika murni deep-link pengaturan atau murni peluncuran aplikasi,
                //    eksekusi langsung tanpa eskalasi asinkron agar model merespons 1 kali dengan tuntas!
                val fastDeepLink = deepLinkRouter.tryExecuteImmediateDeepLinkGoal(enrichedGoal)
                if (fastDeepLink.handled && !fastDeepLink.requiresSubsequentUiVerify) {
                    goalHost.cancelAsyncGoalForImmediatePath()
                    speculativePreFetcher.invalidate()
                    hapticManager.vibrateSuccess()
                    goalHost.updateStateDirectly {
                        it.copy(
                            fsmState = GepyFsmState.COMPLETED,
                            currentGoal = enrichedGoal,
                            lastThought = fastDeepLink.summaryId,
                            lastActionLabel = fastDeepLink.routeType
                        )
                    }
                    return mapOf(
                        "status" to "SUCCESS_IMMEDIATE",
                        "routing_mode" to "LOCAL_FAST_PATH",
                        "message" to fastDeepLink.summaryId
                    )
                }

                val parsedLaunch = AppIntentLauncher.parseVoiceAppLaunchIntent(enrichedGoal)
                if (parsedLaunch != null && parsedLaunch.isPureLaunchOnly) {
                    goalHost.cancelAsyncGoalForImmediatePath()
                    speculativePreFetcher.invalidate()
                    val res = appLauncher.openApp(parsedLaunch.appName)
                    if (res.success) {
                        hapticManager.vibrateSuccess()
                        val openMsg = SovereignNaturalSpeech.appOpened(goalHost.currentSpeechContext(), parsedLaunch.appName)
                        val displayOpen = VoiceClauseChunker.stripAudioTagsForDisplay(openMsg)
                        goalHost.updateStateDirectly {
                            it.copy(
                                fsmState = GepyFsmState.COMPLETED,
                                currentGoal = enrichedGoal,
                                currentPackage = res.resolvedPackage,
                                lastThought = displayOpen,
                                lastActionLabel = AgentActionType.LAUNCH_APP.wireName
                            )
                        }
                        return mapOf(
                            "status" to "SUCCESS_IMMEDIATE",
                            "routing_mode" to "LOCAL_FAST_PATH",
                            "resolved_package" to res.resolvedPackage,
                            "message" to displayOpen
                        )
                    }
                }

                // 2. Jalur Eskalasi Agen Multi-Langkah: jalankan non-blocking dengan ringkasan sub-tujuan
                val plannedSubgoals = TaskPlannerAndVerifier.buildSubgoalChecklist(enrichedGoal)
                goalHost.launchAsyncGoal(enrichedGoal)
                mapOf(
                    "status" to "ESCALATED_ASYNC_RUNNING",
                    "routing_mode" to "EXPERT_AGENTIC_ESCALATION",
                    "goal" to enrichedGoal,
                    "subgoal_count" to plannedSubgoals.size,
                    "planned_subgoals" to plannedSubgoals.take(3),
                    "instruction" to "Katakan dengan santai (1 kalimat singkat) bahwa kamu sedang mengerjakannya di layar sekarang."
                )
            }

            "get_device_and_time_status",
            "get_personal_wellness_status",
            "search_phone_contacts",
            "get_recent_notifications",
            "get_current_location",
            "set_system_alarm",
            "open_system_settings_panel",
            "open_maps_navigation",
            "open_browser_url",
            "execute_laptop_command",
            "get_laptop_status",
            "sync_laptop_clipboard",
            "execute_antigravity_rpc",
            "control_spotify_playback",
            "control_device_system" -> {
                systemToolsBridge.executeSystemToolCall(toolCall.name, toolCall.args)
            }

            "manage_notifications" -> {
                val notifAction = toolCall.args["action"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (notifAction == "reply" && !goalHost.currentState.byokSettings.sovereignPersonalMode) {
                    val target = toolCall.args["sender_or_app"]?.toString()?.trim().orEmpty()
                        .ifEmpty { toolCall.args["target"]?.toString()?.trim().orEmpty() }
                    val replyMsg = toolCall.args["reply_message"]?.toString()?.trim().orEmpty()
                        .ifEmpty { toolCall.args["message"]?.toString()?.trim().orEmpty() }
                    val confirmPrompt = SovereignNaturalSpeech.notificationReplyConfirmPrompt(
                        ctx = goalHost.currentSpeechContext(),
                        senderName = target.ifEmpty { "pengirim" },
                        replyMessage = replyMsg
                    )
                    val approved = voiceHudPort.requestHitlApproval(
                        summaryPrompt = confirmPrompt,
                        targetLabel = target.ifEmpty { "Notifikasi" }
                    )
                    if (!approved) {
                        hapticManager.vibrateError()
                        return mapOf(
                            "status" to "BLOCKED_BY_HITL",
                            "action" to "reply",
                            "summary_id" to "Oke, balasan notifikasinya aku batalin ya."
                        )
                    }
                }
                systemToolsBridge.executeSystemToolCall(toolCall.name, toolCall.args)
            }

            "get_daily_briefing" -> {
                val engine = dailyBriefingEngine
                if (engine == null) {
                    mapOf(
                        "status" to "ERROR",
                        "summary_id" to "Mesin ringkasan harian belum aktif."
                    )
                } else {
                    val result = engine.buildOnDemandBriefing()
                    mapOf(
                        "status" to "SUCCESS",
                        "kind" to result.kind.name,
                        "date" to result.dateKey,
                        "schedule_count" to result.scheduleCount,
                        "today_steps" to result.todaySteps,
                        "yesterday_steps" to result.yesterdaySteps,
                        "battery_percent" to result.batteryPercent,
                        "summary_id" to result.spokenText
                    )
                }
            }

            "search_web_realtime",
            "read_webpage_content",
            "query_huggingface_trending",
            "get_huggingface_model_details",
            "query_github_trending_or_search",
            "get_github_repo_details",
            "get_github_readme" -> {
                val byok = goalHost.currentState.byokSettings
                webSearchBridge.executeWebToolCall(
                    name = toolCall.name,
                    args = toolCall.args,
                    geminiApiKey = byok.effectiveGeminiApiKey,
                    fireworksApiKey = byok.effectiveFireworksApiKey,
                    geminiSecondaryApiKey = byok.effectiveGeminiSecondaryApiKey
                )
            }

            else -> mapOf(
                "status" to "ERROR",
                "error" to "Tool tidak dikenal: ${toolCall.name}"
            )
        }
    }
}
