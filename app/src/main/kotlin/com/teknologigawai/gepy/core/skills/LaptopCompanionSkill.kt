package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.laptop.AntigravityRpcRequest
import com.teknologigawai.gepy.core.laptop.LaptopCompanionBridge
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Plugin *Fast-Path Skill* untuk interaksi instan dengan **Laptop Windows Companion**
 * via jaringan privat **Tailscale** (`< 50 ms`, `0` token LLM Cloud):
 * - Kunci layar laptop (*"kunci laptop"*, *"lock pc"*)
 * - Kontrol volume laptop (*"volume laptop"*, *"mute laptop"*, *"kecilkan suara laptop"*)
 * - Buka aplikasi di laptop (*"buka vscode di laptop"*, *"buka notepad di laptop"*)
 * - Cek status baterai & jendela aktif laptop (*"status laptop"*, *"cek laptop"*)
 * - Membaca clipboard laptop (*"salin dari laptop"*, *"baca clipboard laptop"*)
 * - Play/Pause media laptop (*"jeda lagu laptop"*, *"pause laptop"*)
 */
class LaptopCompanionSkill(
    private val laptopBridge: LaptopCompanionBridge = LaptopCompanionBridge(),
    private val hostProvider: () -> String = { "" },
    private val isEnabledProvider: () -> Boolean = { true },
    private val speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) : GepySkillPlugin {

    companion object {
        private val lockRegex = Regex(
            """\b(?:kunci|lock)\s+(?:layar\s+)?(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
        private val sleepRegex = Regex(
            """\b(?:tidurkan|istirahatkan|sleep|suspend)\s+(?:layar\s+)?(?:laptop|pc|komputer)|(?:mode\s+tidur\s+(?:laptop|pc|komputer))|(?:laptop|pc|komputer)\s+(?:mode\s+tidur|sleep|tidur|istirahat)\b""",
            RegexOption.IGNORE_CASE
        )
        private val shutdownIntentRegex = Regex(
            """\b(?:matikan|shutdown|turn\s*off)\s+(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
        private val confirmShutdownRegex = Regex(
            """\b(?:ya|iya|yes|ok|oke|lanjutkan|konfirmasi)\s+(?:matikan|shutdown)(?:\s+(?:laptop|pc|komputer))?\b|\bmatikan\s+sekarang\b""",
            RegexOption.IGNORE_CASE
        )
        private val cancelShutdownRegex = Regex(
            """\b(?:batal|batalkan|jangan|cancel|tidak|ga\s+jadi|nggak\s+jadi)\s+(?:matikan|shutdown)(?:\s+(?:laptop|pc|komputer))?\b|\bbatalkan\b|\bcancel\b""",
            RegexOption.IGNORE_CASE
        )
        const val SHUTDOWN_CONFIRMATION_TIMEOUT_MS = 25_000L
        private val volumeRegex = Regex(
            """\b(?:volume|suara|mute|kecilkan|besarkan)\s+(?:laptop|pc|komputer)|(?:kecilkan|besarkan)\s+suara\s+(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
        // Perintah A2A Superintelligence Antigravity (Deterministik & Agentic)
        private val antigravityA2ARegex = Regex(
            """\b(?:suruh|minta|tanya|perintahkan)\s+antigravity\s+(.+)\b|\b(?:antigravity|agy)\s+(?:tolong\s+)?(.+)\b|\b(?:cek|status)\s+git\s+(?:di\s+)?(?:laptop|pc|komputer)\b|\b(?:jalankan|run)\s+(?:tes|test|verifikasi)\s+(?:di\s+)?(?:laptop|pc|komputer)\b|\b(?:rakit|build)\s+(?:apk|release)\s+(?:di\s+)?(?:laptop|pc|komputer)\b|\b(?:status|cek)\s+(?:tugas|koding|proses)\s+(?:di\s+)?(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
        // Mendukung pembukaan aplikasi desktop secara alami:
        // 1. Aplikasi desktop-eksklusif (vscode, vs code, visual studio code, code, notepad, proyek, project, antigravity, agy, studio) TANPA wajib menyebut 'di laptop'
        // 2. Aplikasi umum/desktop apa pun bila disertai 'di laptop' / 'di pc' / 'di komputer' (misal: discord, steam, figma, word, excel, postman, dll.)
        private val openAppRegex = Regex(
            """\b(?:buka|jalankan|launch|open)\s+(?:aplikasi\s+)?(vscode|vs code|visual studio code|code|notepad|proyek|project|antigravity|agy|android studio|studio)(?:\s+(?:di\s+)?(?:laptop|pc|komputer))?\b|\b(?:buka|jalankan|launch|open)\s+(?:aplikasi\s+)?([A-Za-z0-9_\-\s]+?)\s+(?:di\s+)?(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
        private val statusRegex = Regex(
            """\b(?:status|cek|baterai|batre|kondisi)\s+(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
        private val clipboardRegex = Regex(
            """\b(?:salin|copy|ambil teks|baca clipboard)\s+(?:dari\s+)?(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
        private val mediaRegex = Regex(
            """\b(?:jeda|pause|play|putar|stop)\s+(?:lagu\s+)?(?:laptop|pc|komputer)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    override val skillId: String = "laptop_companion"

    override val supportedPackages: List<String> = emptyList()

    /** Melacak waktu permintaan shutdown awal untuk validasi konfirmasi 2-langkah (TTL 25 detik). */
    var pendingShutdownTimestamp: Long = 0L
        internal set

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        if (!isEnabledProvider()) return false
        val clean = transcript.trim()
        val hasPendingOrRecentShutdown = pendingShutdownTimestamp > 0L

        return lockRegex.containsMatchIn(clean) ||
            sleepRegex.containsMatchIn(clean) ||
            shutdownIntentRegex.containsMatchIn(clean) ||
            (hasPendingOrRecentShutdown && (confirmShutdownRegex.containsMatchIn(clean) || cancelShutdownRegex.containsMatchIn(clean))) ||
            cancelShutdownRegex.containsMatchIn(clean) ||
            volumeRegex.containsMatchIn(clean) ||
            antigravityA2ARegex.containsMatchIn(clean) ||
            openAppRegex.containsMatchIn(clean) ||
            statusRegex.containsMatchIn(clean) ||
            clipboardRegex.containsMatchIn(clean) ||
            mediaRegex.containsMatchIn(clean)
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: LAPTOP WINDOWS COMPANION (TAILSCALE)]
Untuk perintah yang mengendalikan laptop pengguna (kunci layar laptop, tidurkan laptop ke mode sleep, matikan laptop dengan konfirmasi dua arah, kontrol volume laptop, buka aplikasi laptop, atau baca status/clipboard laptop via Tailscale), gunakan alat execute_laptop_command atau jawab langsung dengan action "DONE".
PENTING: Untuk mematikan laptop, Anda WAJIB meminta konfirmasi pengguna secara lisan terlebih dahulu sebelum mengeksekusi aksi 'shutdown' dengan confirmed=true.
        """.trimIndent()

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        if (!canHandleVoiceIntent(userGoal)) {
            return null
        }

        val host = hostProvider()
        val speechCtx = speechContextProvider()
        val summaryTts: String = when {
            lockRegex.containsMatchIn(userGoal) -> {
                val res = laptopBridge.executeAction(action = "lock", host = host)
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.laptopLockFallback(speechCtx)
            }

            sleepRegex.containsMatchIn(userGoal) -> {
                val res = laptopBridge.executeAction(action = "sleep", host = host)
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.laptopSleepFallback(speechCtx)
            }

            shutdownIntentRegex.containsMatchIn(userGoal) -> {
                pendingShutdownTimestamp = System.currentTimeMillis()
                SovereignNaturalSpeech.laptopShutdownAskConfirm(speechCtx)
            }

            confirmShutdownRegex.containsMatchIn(userGoal) -> {
                val elapsed = System.currentTimeMillis() - pendingShutdownTimestamp
                if (pendingShutdownTimestamp > 0L && elapsed <= SHUTDOWN_CONFIRMATION_TIMEOUT_MS) {
                    pendingShutdownTimestamp = 0L
                    val res = laptopBridge.shutdownLaptop(confirmed = true, host = host)
                    res["summary_id"]?.toString()
                        ?: SovereignNaturalSpeech.laptopShutdownConfirmedFallback(speechCtx)
                } else {
                    pendingShutdownTimestamp = 0L
                    SovereignNaturalSpeech.laptopShutdownConfirmExpired(speechCtx)
                }
            }

            cancelShutdownRegex.containsMatchIn(userGoal) -> {
                val wasPending = pendingShutdownTimestamp > 0L
                pendingShutdownTimestamp = 0L
                val res = laptopBridge.cancelShutdownLaptop(host = host)
                if (wasPending) {
                    SovereignNaturalSpeech.laptopShutdownCancelledLocal(speechCtx)
                } else {
                    res["summary_id"]?.toString()
                        ?: SovereignNaturalSpeech.laptopShutdownCancelledFallback(speechCtx)
                }
            }

            volumeRegex.containsMatchIn(userGoal) -> {
                val mode = when {
                    userGoal.contains("mute", ignoreCase = true) -> "mute"
                    userGoal.contains("kecilkan", ignoreCase = true) || userGoal.contains("turun", ignoreCase = true) -> "down"
                    else -> "up"
                }
                val res = laptopBridge.executeAction(action = "volume", params = mapOf("value" to mode), host = host)
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.laptopVolumeFallback(speechCtx)
            }

            antigravityA2ARegex.containsMatchIn(userGoal) -> {
                val rpcAction: String
                var target = ""
                var prompt = ""

                when {
                    userGoal.contains("status tugas", ignoreCase = true) || userGoal.contains("cek tugas", ignoreCase = true) || userGoal.contains("status proses", ignoreCase = true) -> {
                        rpcAction = "CHECK_TASK_STATUS"
                    }
                    userGoal.contains("status git", ignoreCase = true) || userGoal.contains("cek git", ignoreCase = true) -> {
                        rpcAction = "GIT_STATUS"
                    }
                    userGoal.contains("verifikasi", ignoreCase = true) || userGoal.contains("verify", ignoreCase = true) -> {
                        rpcAction = "VERIFY_ALL"
                    }
                    userGoal.contains("rakit", ignoreCase = true) || userGoal.contains("build", ignoreCase = true) -> {
                        rpcAction = "BUILD_RELEASE"
                    }
                    userGoal.contains("tes", ignoreCase = true) || userGoal.contains("test", ignoreCase = true) -> {
                        rpcAction = "RUN_TESTS"
                    }
                    else -> {
                        val match = antigravityA2ARegex.find(userGoal)
                        val rawQuery = match?.groups?.get(1)?.value?.trim()
                            ?: match?.groups?.get(2)?.value?.trim()
                            ?: userGoal
                        prompt = rawQuery
                        rpcAction = if (rawQuery.contains("buat", ignoreCase = true) || rawQuery.contains("edit", ignoreCase = true) || rawQuery.contains("tulis", ignoreCase = true)) {
                            "CODE_TASK"
                        } else {
                            "ASK_AGENT"
                        }
                    }
                }

                val isAsync = rpcAction in listOf("BUILD_RELEASE", "VERIFY_ALL", "RUN_TESTS", "CODE_TASK")
                val callbackUrl = if (isAsync) {
                    "http://${com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge.resolveDeviceBestIpv4()}:8765/api/antigravity/callback"
                } else ""

                val req = AntigravityRpcRequest(
                    action = rpcAction,
                    target = target,
                    prompt = prompt,
                    isAsync = isAsync,
                    callbackUrl = callbackUrl
                )
                val res = laptopBridge.executeAntigravityRpc(req, host = host)
                res.summaryTts
            }

            openAppRegex.containsMatchIn(userGoal) -> {
                val match = openAppRegex.find(userGoal)
                val rawApp = match?.groups?.get(1)?.value?.trim()
                    ?: match?.groups?.get(2)?.value?.trim()
                    ?: "vscode"

                val targetApp = when {
                    rawApp.contains("vscode", ignoreCase = true) ||
                        rawApp.contains("vs code", ignoreCase = true) ||
                        rawApp.contains("visual studio code", ignoreCase = true) ||
                        rawApp.equals("code", ignoreCase = true) ||
                        rawApp.equals("proyek", ignoreCase = true) ||
                        rawApp.equals("project", ignoreCase = true) -> "vscode"
                    rawApp.equals("notepad", ignoreCase = true) -> "notepad"
                    rawApp.equals("antigravity", ignoreCase = true) || rawApp.equals("agy", ignoreCase = true) -> "antigravity"
                    rawApp.contains("studio", ignoreCase = true) -> "android studio"
                    rawApp.equals("whatsapp", ignoreCase = true) || rawApp.equals("wa", ignoreCase = true) -> "whatsapp"
                    rawApp.equals("telegram", ignoreCase = true) -> "telegram"
                    rawApp.equals("chrome", ignoreCase = true) -> "chrome"
                    rawApp.equals("spotify", ignoreCase = true) -> "spotify"
                    rawApp.equals("terminal", ignoreCase = true) -> "terminal"
                    rawApp.equals("explorer", ignoreCase = true) -> "explorer"
                    rawApp.equals("calc", ignoreCase = true) || rawApp.equals("kalkulator", ignoreCase = true) -> "calc"
                    rawApp.equals("youtube", ignoreCase = true) -> "youtube"
                    rawApp.contains("discord", ignoreCase = true) -> "discord"
                    rawApp.contains("notion", ignoreCase = true) -> if (rawApp.contains("calendar", ignoreCase = true)) "notion calendar" else "notion"
                    else -> rawApp.lowercase()
                }
                val res = laptopBridge.executeAction(action = "open_app", target = targetApp, host = host)
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.laptopOpenAppFallback(speechCtx, targetApp)
            }

            statusRegex.containsMatchIn(userGoal) -> {
                val res = laptopBridge.getLaptopStatus(host = host)
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.laptopStatusFallback(speechCtx)
            }

            clipboardRegex.containsMatchIn(userGoal) -> {
                val res = laptopBridge.getClipboardText(host = host)
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.laptopClipboardFallback(speechCtx)
            }

            mediaRegex.containsMatchIn(userGoal) -> {
                val res = laptopBridge.executeAction(action = "media_play_pause", host = host)
                res["summary_id"]?.toString() ?: SovereignNaturalSpeech.laptopMediaFallback(speechCtx)
            }

            else -> SovereignNaturalSpeech.laptopGenericFallback(speechCtx)
        }

        return ReActActionModel(
            thought = "[Fast Path Laptop Companion] Mengirim perintah ke laptop Windows via Tailscale tanpa membuka antarmuka HP.",
            reflectionOnLastStep = ReflectionStatus.INITIAL,
            action = AgentActionType.DONE,
            completionTts = summaryTts
        )
    }
}
