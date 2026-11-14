package com.teknologigawai.gepy.features.sota

import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.persona.GuardianTriggerReason
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignPersonaEngine
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.core.wellness.WellnessAndGuardianEngine
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.brain.LlmCredentialsConfig
import com.teknologigawai.gepy.features.brain.LlmHttpResponse
import com.teknologigawai.gepy.features.brain.SingleAgentReActAdapter
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.orchestrator.GepyFsmState
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestrator
import com.teknologigawai.gepy.features.perception.models.PruneMetricsSnapshot
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.perception.models.TextBox
import com.teknologigawai.gepy.features.voice.GeminiLiveClient
import com.teknologigawai.gepy.service.GepyAccessibilityService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.time.LocalDateTime
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignSuperintelligenceV5Test {

    @Test
    fun `Expanded Mobile-Use actions parse salvage and execute end-to-end with SafetyRules intact`() = runTest {
        // 1. Verifikasi alias wireName & salvage JSON terpotong untuk 6 aksi baru
        assertEquals(AgentActionType.LONG_PRESS, AgentActionType.fromWireName("HOLD"))
        assertEquals(AgentActionType.SWIPE, AgentActionType.fromWireName("SWIPE_LEFT"))
        assertEquals(AgentActionType.HOME, AgentActionType.fromWireName("NAVIGATE_HOME"))
        assertEquals(AgentActionType.WAIT, AgentActionType.fromWireName("SLEEP"))
        assertEquals(AgentActionType.CLIPBOARD_COPY, AgentActionType.fromWireName("COPY"))
        assertEquals(AgentActionType.CLIPBOARD_PASTE, AgentActionType.fromWireName("PASTE"))

        val truncatedSwipe = """{"thought":"Geser slide carousel ke kiri","action":"SWIPE","scroll_direction":"LEFT""""
        val salvaged = ReActActionModel.fromRawJsonString(truncatedSwipe)
        assertEquals(AgentActionType.SWIPE, salvaged.action)
        assertEquals("LEFT", salvaged.scrollDirection)

        // 2. Uji eksekusi berantai di GepyOrchestrator:
        //    LONG_PRESS -> SWIPE(LEFT) -> CLIPBOARD_COPY -> CLIPBOARD_PASTE (normal) -> CLIPBOARD_PASTE (PIN diblokir SafetyRules) -> WAIT -> HOME -> DONE
        val executedEvents = mutableListOf<String>()
        var pastedValue = ""
        val elements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "news_headline",
                text = "ESP32-S3 Radar Biometrik Resmi Rilis",
                isClickable = true,
                rect = PrunedUiElement.buildRect(x = 400, y = 350, w = 200, h = 100)
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "note_editor",
                viewResourceId = "com.notes:id/body_input",
                text = "Tulis catatan...",
                isEditable = true,
                rect = PrunedUiElement.buildRect(x = 400, y = 850, w = 200, h = 100)
            ),
            PrunedUiElement(
                index = 3,
                nodeId = "pin_input",
                viewResourceId = "com.app:id/pin_otp_field",
                text = "Masukkan PIN",
                isEditable = true,
                rect = PrunedUiElement.buildRect(x = 400, y = 1150, w = 200, h = 100)
            )
        )

        val fakePerception = FakeSovereignPerceptionPort(
            pkg = "com.notes",
            elements = elements
        )
        val fakeAction = object : FakeSovereignActionPort() {
            override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean {
                executedEvents.add("LONG_PRESS(${x.toInt()},${y.toInt()},${durationMs})")
                return true
            }

            override suspend fun swipeScreen(fingerDirection: String, distance: Double): Boolean {
                executedEvents.add("SWIPE($fingerDirection)")
                return true
            }

            override suspend fun setTextElement(
                target: PrunedUiElement,
                text: String,
                preSignature: String,
                clearExisting: Boolean
            ): ActionOutcome {
                pastedValue = text
                executedEvents.add("PASTE_TO(${target.nodeId}:$text)")
                return ActionOutcome.CORRECT
            }

            override suspend fun navigateHome(): Boolean {
                executedEvents.add("HOME")
                return true
            }
        }

        val scriptedActions = ArrayDeque(
            listOf(
                ReActActionModel(
                    thought = "Tekan tahan judul untuk menu konteks",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.LONG_PRESS,
                    targetIndex = 1
                ),
                ReActActionModel(
                    thought = "Geser carousel ke kiri",
                    reflectionOnLastStep = ReflectionStatus.CORRECT,
                    action = AgentActionType.SWIPE,
                    scrollDirection = "LEFT"
                ),
                ReActActionModel(
                    thought = "Salin teks judul ke clipboard",
                    reflectionOnLastStep = ReflectionStatus.CORRECT,
                    action = AgentActionType.CLIPBOARD_COPY,
                    targetIndex = 1
                ),
                ReActActionModel(
                    thought = "Tempel teks dari clipboard ke editor catatan",
                    reflectionOnLastStep = ReflectionStatus.CORRECT,
                    action = AgentActionType.CLIPBOARD_PASTE,
                    targetIndex = 2
                ),
                ReActActionModel(
                    thought = "Percobaan menempel ke kolom PIN harus ditolak SafetyRules",
                    reflectionOnLastStep = ReflectionStatus.CORRECT,
                    action = AgentActionType.CLIPBOARD_PASTE,
                    targetIndex = 3
                ),
                ReActActionModel(
                    thought = "Tunggu sebentar pemuatan sinkronisasi",
                    reflectionOnLastStep = ReflectionStatus.INEFFECTIVE,
                    action = AgentActionType.WAIT
                ),
                ReActActionModel(
                    thought = "Kembali ke layar utama",
                    reflectionOnLastStep = ReflectionStatus.CORRECT,
                    action = AgentActionType.HOME
                ),
                ReActActionModel(
                    thought = "Rangkaian tugas lintas aplikasi selesai",
                    reflectionOnLastStep = ReflectionStatus.CORRECT,
                    action = AgentActionType.DONE,
                    completionTts = "Judul berita berhasil disalin dan ditempel ke catatan."
                )
            )
        )

        val fakeBrain = object : BrainPort {
            override suspend fun decideNextAction(
                userGoal: String,
                currentPackage: String,
                screenElements: List<PrunedUiElement>,
                memory: ShortTermMemorySnapshot,
                injectedDomainKnowledge: String?
            ): ReActActionModel = scriptedActions.removeFirst()
        }

        val memoryRepo = SqliteMemoryRepository(dao = null)
        val systemBridge = AndroidSystemToolsBridge(context = null)
        val orchestrator = GepyOrchestrator(
            perceptionPort = fakePerception,
            actionPort = fakeAction,
            memoryPort = memoryRepo,
            brainPort = fakeBrain,
            voiceHudPort = NoOpSovereignVoiceHud(),
            appLauncher = AppIntentLauncher(context = null),
            systemToolsBridge = systemBridge,
            maxStepsPerGoal = 12
        )

        orchestrator.executeVoiceGoal("Salin berita dan tempel ke catatan lalu kembali ke home")

        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        assertEquals("ESP32-S3 Radar Biometrik Resmi Rilis", pastedValue)
        assertEquals("ESP32-S3 Radar Biometrik Resmi Rilis", memoryRepo.getScratchpadNotes()["clipboard_last"])
        assertTrue(executedEvents.contains("LONG_PRESS(500,400,800)"))
        assertTrue(executedEvents.contains("SWIPE(LEFT)"))
        assertTrue(executedEvents.contains("PASTE_TO(note_editor:ESP32-S3 Radar Biometrik Resmi Rilis)"))
        // Pastikan kolom PIN/OTP TIDAK pernah menerima paste!
        assertFalse(executedEvents.any { it.startsWith("PASTE_TO(pin_input") })
        assertTrue(executedEvents.contains("HOME"))

        // Karena langkah ke-5 (paste ke PIN) menghasilkan INEFFECTIVE lalu berhasil pulih hingga DONE,
        // pastikan pelajaran episodik mandiri tercatat otomatis!
        val episodicHint = memoryRepo.getEpisodicLessonsHint("com.notes")
        assertNotNull(episodicHint)
        assertTrue(episodicHint!!.contains("PELAJARAN EPISODIK"))
    }

    @Test
    fun `Multimodal Vision Set-of-Marks attaches inlineData JPEG to Gemini on canvas blind spot and injects personal memory`() = runTest {
        var capturedUrl = ""
        var capturedRequestJson = ""

        val fakeTransport = SingleAgentReActAdapter(
            credentials = LlmCredentialsConfig(
                fireworksApiKey = "fw-test-key",
                geminiApiKey = "AIzaSyTestVisionKey"
            ),
            httpTransport = { url, _, body ->
                capturedUrl = url
                capturedRequestJson = body
                val geminiReply = JSONObject()
                    .put(
                        "candidates",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "content",
                                JSONObject().put(
                                    "parts",
                                    org.json.JSONArray().put(
                                        JSONObject().put(
                                            "text",
                                            """{"thought":"Melihat tombol dari tangkapan visual SoM","reflection_on_last_step":"INITIAL","action":"DONE","completion_tts":"Selesai Bos Maulana."}"""
                                        )
                                    )
                                )
                            )
                        )
                    )
                LlmHttpResponse(statusCode = 200, body = geminiReply.toString())
            }
        )

        val userMemory = UserMemoryManager(context = null)
        userMemory.recordMemory(category = "profile", fact = "Nama pemilik adalah Maulana", subject = "user_name")
        userMemory.recordMemory(category = "preference", fact = "Suka kopi hitam tanpa gula", subject = "coffee")

        val dummyJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02)
        // Kanvas dengan hanya 1 elemen interaktif (< 2) memicu isCanvasBlindSpot == true
        val sparseCanvasElements = listOf(
            PrunedUiElement(index = 1, nodeId = "canvas_root", text = "Custom Flutter Game View", isClickable = true)
        )
        val fakePerception = FakeSovereignPerceptionPort(
            pkg = "com.custom.canvasapp",
            elements = sparseCanvasElements,
            screenshotBytes = dummyJpegBytes
        )

        val orchestrator = GepyOrchestrator(
            perceptionPort = fakePerception,
            actionPort = FakeSovereignActionPort(),
            memoryPort = SqliteMemoryRepository(dao = null),
            brainPort = fakeTransport,
            voiceHudPort = NoOpSovereignVoiceHud(),
            appLauncher = AppIntentLauncher(context = null),
            userMemoryManager = userMemory
        )

        orchestrator.executeVoiceGoal("Pesan kopi favorit untuk Maulana di layar kanvas")

        assertEquals(GepyFsmState.COMPLETED, orchestrator.state.fsmState)
        // Saat visualSoMImageBytes != null, SingleAgentReActAdapter memprioritaskan Gemini Vision!
        assertTrue(capturedUrl.contains("generativelanguage.googleapis.com"))
        val parsedReq = JSONObject(capturedRequestJson)
        val parts = parsedReq.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())

        val textPayload = JSONObject(parts.getJSONObject(0).getString("text"))
        assertTrue(textPayload.getString("personal_memory").contains("Maulana"))
        assertTrue(textPayload.getString("personal_memory").contains("kopi hitam"))

        val inlineData = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("image/jpeg", inlineData.getString("mimeType"))
        assertEquals(Base64.getEncoder().encodeToString(dummyJpegBytes), inlineData.getString("data"))

        val sysPrompt = parsedReq.getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(sysPrompt.contains("PERSONA AKTIF"))
        assertTrue(sysPrompt.contains("LONG_PRESS"))
        assertTrue(sysPrompt.contains("CLIPBOARD_COPY"))
    }

    @Test
    fun `SovereignPersonaEngine switches dynamically from Jarvis Tactical to Honest Mentor and enforces Privacy Shield`() {
        val normalWellness = PersonalWellnessContext(
            ownerName = "Maulana",
            todaySteps = 4_200,
            activeSocialScrollMinutes = 5,
            doomscrollLimitMinutes = 15,
            sedentaryBedMinutes = 20
        )
        assertEquals(
            SovereignPersonaMode.JARVIS_TACTICAL,
            SovereignPersonaEngine.resolveEffectivePersona(SovereignPersonaMode.ADAPTIVE, normalWellness)
        )

        val doomscrollWellness = normalWellness.copy(activeSocialScrollMinutes = 18)
        assertEquals(
            SovereignPersonaMode.HONEST_MENTOR,
            SovereignPersonaEngine.resolveEffectivePersona(SovereignPersonaMode.ADAPTIVE, doomscrollWellness)
        )
        val mentorSpeech = SovereignPersonaEngine.buildGuardianInterventionSpeech(
            configuredMode = SovereignPersonaMode.ADAPTIVE,
            reason = GuardianTriggerReason.DOOMSCROLL_LIMIT,
            wellness = doomscrollWellness,
            autoHomeTriggered = true
        )
        assertTrue(mentorSpeech.contains("Maulana"))
        assertTrue(mentorSpeech.contains("18 menit"))
        assertTrue(mentorSpeech.contains("layar utama"))

        val sedentaryGuestWellness = normalWellness.copy(
            todaySteps = 120,
            sedentaryBedMinutes = 190,
            isOwnerVerifiedInRoom = false,
            isGuestPrivacyShieldActive = true
        )
        val liveDirective = SovereignPersonaEngine.buildLiveVoicePersonaDirective(
            configuredMode = SovereignPersonaMode.ADAPTIVE,
            wellness = sedentaryGuestWellness
        )
        assertTrue(liveDirective.contains("MENTOR JUJUR & TEGAS"))
        assertTrue(liveDirective.contains("PRIVACY SHIELD AKTIF"))

        // 4. Verifikasi mode persona baru ROMANTIC_GIRLFRIEND (Girlfriend Menggoda & Manja)
        assertEquals(
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            SovereignPersonaEngine.resolveEffectivePersona(SovereignPersonaMode.ROMANTIC_GIRLFRIEND, normalWellness)
        )
        val gfReActDirective = SovereignPersonaEngine.buildReActPersonaDirective(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            wellness = normalWellness
        )
        assertTrue(gfReActDirective.contains("GIRLFRIEND MENGGODA & MANJA"))
        assertTrue(gfReActDirective.contains("Maulana"))

        val gfLiveDirective = SovereignPersonaEngine.buildLiveVoicePersonaDirective(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            wellness = normalWellness
        )
        assertTrue(gfLiveDirective.contains("GIRLFRIEND MENGGODA, GENIT & PENUH PERHATIAN"))

        val gfGuardianSpeech = SovereignPersonaEngine.buildGuardianInterventionSpeech(
            configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
            reason = GuardianTriggerReason.DOOMSCROLL_LIMIT,
            wellness = doomscrollWellness,
            autoHomeTriggered = false
        )
        assertTrue(gfGuardianSpeech.contains("Maulana"))
        assertFalse(gfGuardianSpeech.contains("sayang", ignoreCase = true))
        assertTrue(gfGuardianSpeech.contains("18 menit"))

        // 5. Verifikasi mode persona baru CUSTOM (Kustom Berdaulat & Mutlak Patuh pada Pengguna)
        assertEquals(
            SovereignPersonaMode.CUSTOM,
            SovereignPersonaEngine.resolveEffectivePersona(SovereignPersonaMode.CUSTOM, normalWellness)
        )
        val customPrompt = "Panggil aku Bos, selalu patuhi dan prioritaskan tugasku."
        val customReAct = SovereignPersonaEngine.buildReActPersonaDirective(
            configuredMode = SovereignPersonaMode.CUSTOM,
            wellness = normalWellness,
            customPersonaPrompt = customPrompt
        )
        assertTrue(customReAct.contains("KUSTOM BERDAULAT - MUTLAK PATUH PADA PENGGUNA"))
        assertTrue(customReAct.contains("ATURAN KEPATUHAN MUTLAK"))
        assertTrue(customReAct.contains("Panggil aku Bos"))

        val customLive = SovereignPersonaEngine.buildLiveVoicePersonaDirective(
            configuredMode = SovereignPersonaMode.CUSTOM,
            wellness = normalWellness,
            customPersonaPrompt = customPrompt
        )
        assertTrue(customLive.contains("KUSTOM BERDAULAT - MUTLAK PATUH PADA PENGGUNA"))
        assertTrue(customLive.contains("ATURAN KEPATUHAN MUTLAK"))
        assertTrue(customLive.contains("Panggil aku Bos"))

        val customSpeech = SovereignPersonaEngine.buildGuardianInterventionSpeech(
            configuredMode = SovereignPersonaMode.CUSTOM,
            reason = GuardianTriggerReason.DOOMSCROLL_LIMIT,
            wellness = doomscrollWellness,
            autoHomeTriggered = false
        )
        assertTrue(customSpeech.contains("sesuai instruksi fokusmu"))
    }

    @Test
    fun `ByokPreferencesManager preserves custom persona mode and prompt`() {
        val byok = ByokPreferencesManager(context = null)
        byok.updateSettings(
            ByokSettings(
                sovereignPersonaMode = SovereignPersonaMode.CUSTOM,
                customPersonaPrompt = "Panggil aku Bos, bicara santai gaul, patuhi semua perintah."
            )
        )
        assertEquals(SovereignPersonaMode.CUSTOM, byok.currentSettings.sovereignPersonaMode)
        assertEquals(
            "Panggil aku Bos, bicara santai gaul, patuhi semua perintah.",
            byok.currentSettings.customPersonaPrompt
        )
    }

    @Test
    fun `WellnessAndGuardianEngine ignores agent-driven Instagram tasks and triggers Auto-Home on user doomscrolling`() = runTest {
        val byok = ByokPreferencesManager(context = null)
        byok.updateSettings(
            ByokSettings(
                sovereignPersonaMode = SovereignPersonaMode.ADAPTIVE,
                antiDoomscrollEnabled = true,
                doomscrollLimitMinutes = 10,
                antiDoomscrollAutoHome = true
            )
        )

        var isAgentBusy = true
        val triggeredInterventions = mutableListOf<Pair<String, Boolean>>()
        val engine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            isAgentExecutingProvider = { isAgentBusy },
            onGuardianIntervention = { speech, autoHome ->
                triggeredInterventions.add(speech to autoHome)
            },
            clockProvider = { LocalDateTime.of(2026, 11, 8, 14, 0) }
        )

        // 1. Saat Gepy sedang menjalankan tugas otomatisasi di Instagram, waktu scroll TIDAK dihitung!
        engine.tickForegroundObservation("com.instagram.android", elapsedSeconds = 700, nowWallMs = 1_000_000L)
        assertEquals(0, engine.currentSnapshot.continuousSocialScrollSeconds)
        assertTrue(triggeredInterventions.isEmpty())

        // 2. Saat pengguna sendiri melakukan scroll manual >= 10 menit (600 detik), intervensi + Auto-Home terpicu!
        isAgentBusy = false
        engine.tickForegroundObservation("com.instagram.android", elapsedSeconds = 605, nowWallMs = 2_000_000L)
        assertEquals(1, triggeredInterventions.size)
        assertTrue(triggeredInterventions.first().second) // triggerAutoHome == true
        assertTrue(triggeredInterventions.first().first.contains("Maulana"))
        // Setelah Auto-Home, counter kontinu direset ke 0
        assertEquals(0, engine.currentSnapshot.continuousSocialScrollSeconds)

        // 3. Uji sensor langkah perangkat keras (baseline + delta)
        engine.onHardwareStepCounterUpdate(10_000) // baseline awal
        engine.onHardwareStepCounterUpdate(10_245)
        assertEquals(245, engine.currentSnapshot.todaySteps)
    }

    @Test
    fun `EspCsiAmbientBridge handles LAN HTTP webhook events enforces biometric wake and activates notification Privacy Shield`() = runTest {
        val byok = ByokPreferencesManager(context = null)
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            clockProvider = { LocalDateTime.of(2026, 11, 8, 15, 30) }
        )

        var airGestureWakeCount = 0
        val csiBridge = EspCsiAmbientBridge(
            wellnessEngine = wellnessEngine,
            onAirGestureWake = { airGestureWakeCount++ }
        )

        // 1. Verifikasi filter IP Zero-Trust LAN (tolak IP publik 8.8.8.8, izinkan loopback & 192.168.x.x)
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("192.168.1.42")))
        assertFalse(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("8.8.8.8")))

        // 2. Jalankan server lokal asli di port dinamis (0) dan kirim HTTP POST /csi-event dari socket loopback
        assertTrue(csiBridge.startServer(port = 0))
        try {
            val port = csiBridge.boundPort
            assertTrue(port > 0)

            // a) AIR_GESTURE_WAKE dari tamu (biometric_owner_match = false) -> DITOLAK & aktifkan Privacy Shield!
            val guestResp = sendLocalHttpPost(
                port = port,
                path = "/csi-event",
                jsonBody = """{"event":"AIR_GESTURE_WAKE","room":"kamar","biometric_owner_match":false,"confidence":0.91}"""
            )
            assertTrue(guestResp.contains("200 OK"))
            assertTrue(guestResp.contains("\"privacy_shield\":true"))
            assertEquals(0, airGestureWakeCount)
            assertTrue(wellnessEngine.currentSnapshot.isGuestPrivacyShieldActive)

            // Verifikasi AndroidSystemToolsBridge menahan pembacaan notifikasi pribadi saat Privacy Shield aktif!
            GepyAccessibilityService.clearRecentNotifications()
            GepyAccessibilityService.recordNotificationIfSafe("com.whatsapp", "Pesan rahasia proyek pribadi")
            val systemBridge = AndroidSystemToolsBridge(context = null).apply {
                wellnessStatusProvider = { wellnessEngine.currentSnapshot.toToolSummaryMap() }
            }
            val blockedNotif = systemBridge.executeSystemToolCall("get_recent_notifications", emptyMap())
            assertEquals("BLOCKED_BY_PRIVACY_SHIELD", blockedNotif["status"])

            // b) AIR_GESTURE_WAKE dari pemilik sah (biometric_owner_match = true) -> DITERIMA & buka Privacy Shield!
            val ownerResp = sendLocalHttpPost(
                port = port,
                path = "/csi-event",
                jsonBody = """{"event":"AIR_GESTURE_WAKE","room":"kamar","biometric_owner_match":true,"respiration_bpm":14}"""
            )
            assertTrue(ownerResp.contains("200 OK"))
            assertTrue(ownerResp.contains("\"privacy_shield\":false"))
            assertEquals(1, airGestureWakeCount)
            assertFalse(wellnessEngine.currentSnapshot.isGuestPrivacyShieldActive)
            assertEquals(14, wellnessEngine.currentSnapshot.respirationBpm)

            val allowedNotif = systemBridge.executeSystemToolCall("get_recent_notifications", emptyMap())
            assertEquals("SUCCESS", allowedNotif["status"])

            // Verifikasi GeminiLiveClient mendeklarasikan get_personal_wellness_status & direktif persona
            val liveClient = GeminiLiveClient(apiKey = "AIzaSyTestLiveKey").apply {
                sovereignPersonaDirectiveProvider = {
                    SovereignPersonaEngine.buildLiveVoicePersonaDirective(
                        configuredMode = SovereignPersonaMode.ADAPTIVE,
                        wellness = wellnessEngine.currentSnapshot.toPersonalWellnessContext("Maulana", 15)
                    )
                }
            }
            val setupJson = liveClient.buildSetupMessageJson().toString()
            assertTrue(setupJson.contains("get_personal_wellness_status"))
            assertTrue(setupJson.contains("KARAKTER KEPRIBADIAN SOVEREIGN"))
        } finally {
            csiBridge.stopServer()
            GepyAccessibilityService.clearRecentNotifications()
        }
    }

    @Test
    fun `ProactivityLevel ZEN_PASSIVE membungkam PRESENCE_ENTER dan intervensi pasif sambil menjaga Privacy Shield dan AIR_GESTURE_WAKE`() = runTest {
        val byok = ByokPreferencesManager(context = null)
        byok.updateSettings(
            ByokSettings(
                proactivityLevel = ProactivityLevel.ZEN_PASSIVE,
                antiDoomscrollEnabled = true,
                doomscrollLimitMinutes = 5
            )
        )

        val interventions = mutableListOf<String>()
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            onGuardianIntervention = { speech, _ -> interventions.add(speech) },
            clockProvider = { LocalDateTime.of(2026, 11, 11, 14, 0) }
        )

        var airWakeCount = 0
        var welcomeCount = 0
        val csiBridge = EspCsiAmbientBridge(
            wellnessEngine = wellnessEngine,
            onAirGestureWake = { airWakeCount++ },
            onOwnerPresenceGreeting = { welcomeCount++ },
            proactivityLevelProvider = { byok.currentSettings.proactivityLevel }
        )

        // 1. PRESENCE_ENTER dalam ZEN_PASSIVE TIDAK boleh menyapa otomatis
        csiBridge.processIncomingEventJson(
            rawJson = """{"event":"PRESENCE_ENTER","room":"kamar","biometric_owner_match":true}""",
            nowWallMs = 1_000_000L
        )
        assertEquals(0, welcomeCount)

        // 2. AIR_GESTURE_WAKE eksplisit TETAP aktif & Privacy Shield biometrik tamu TETAP bekerja
        csiBridge.processIncomingEventJson(
            rawJson = """{"event":"AIR_GESTURE_WAKE","room":"kamar","biometric_owner_match":true}""",
            nowWallMs = 1_100_000L
        )
        assertEquals(1, airWakeCount)

        // 3. SEDENTARY_BED_ALERT & doomscrolling pasif TIDAK boleh memicu suara intervensi saat ZEN_PASSIVE
        wellnessEngine.tickForegroundObservation("com.instagram.android", elapsedSeconds = 400, nowWallMs = 2_000_000L)
        wellnessEngine.ingestCsiTelemetry("SEDENTARY_ALERT", room = "kamar", durationMinutes = 200, biometricOwnerMatch = true, nowWallMs = 3_000_000L)
        assertEquals(400, wellnessEngine.currentSnapshot.continuousSocialScrollSeconds)
        assertEquals(0, interventions.size)

        // 4. Beralih ke CONTEXT_COPILOT -> PRESENCE_ENTER kembali aktif (tidak terkunci cooldown ZEN_PASSIVE)
        byok.updateSettings(byok.currentSettings.copy(proactivityLevel = ProactivityLevel.CONTEXT_COPILOT))
        csiBridge.processIncomingEventJson(
            rawJson = """{"event":"PRESENCE_ENTER","room":"kamar","biometric_owner_match":true}""",
            nowWallMs = 4_000_000L
        )
        assertEquals(1, welcomeCount)

        csiBridge.processIncomingEventJson(
            rawJson = """{"event":"SEDENTARY_ALERT","room":"kamar","duration_minutes":220,"biometric_owner_match":true}""",
            nowWallMs = 5_000_000L
        )
        assertEquals(1, interventions.size)
    }

    private fun sendLocalHttpPost(port: Int, path: String, jsonBody: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
            val req = "POST $path HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(req.toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()
            return BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readText()
        }
    }

    private class FakeSovereignPerceptionPort(
        private val pkg: String,
        private val elements: List<PrunedUiElement>,
        private val screenshotBytes: ByteArray? = null
    ) : PerceptionPort {
        private var stepTick = 0
        override val currentForegroundPackage: String get() = pkg

        override suspend fun captureAndPruneScreen(
            forceOcrFallback: Boolean,
            includeNonInteractive: Boolean
        ): List<PrunedUiElement> = elements

        override suspend fun capturePruneSnapshot(forceOcrFallback: Boolean): PruneMetricsSnapshot =
            PruneMetricsSnapshot(
                elements = elements,
                rawNodeCount = 20,
                rawCharLength = 800,
                compactJson = "[]"
            )

        override suspend fun readScreenTextBoxes(minConfidence: Double): List<TextBox> = emptyList()
        override suspend fun findText(query: String, exact: Boolean): List<PrunedUiElement> = emptyList()
        override suspend fun waitForText(query: String, timeoutMs: Long, intervalMs: Long): PrunedUiElement? = null
        override suspend fun captureScreenshot(): ByteArray? = screenshotBytes
        override fun computeSignature(elements: List<PrunedUiElement>): String = "sig_${pkg}_${stepTick++}"
        override fun dispose() = Unit
    }

    private open class FakeSovereignActionPort : ActionPort {
        override suspend fun waitStable(
            timeoutMs: Long,
            pollIntervalMs: Long,
            settleCount: Int,
            changedFrom: String?
        ): List<PrunedUiElement> = emptyList()

        override suspend fun tapElement(
            target: PrunedUiElement,
            preSignature: String,
            hitlApproved: Boolean
        ): ActionOutcome = ActionOutcome.CORRECT

        override suspend fun tapCoordinates(x: Float, y: Float): Boolean = true
        override suspend fun longPressCoordinates(x: Float, y: Float, durationMs: Long): Boolean = true

        override suspend fun setTextElement(
            target: PrunedUiElement,
            text: String,
            preSignature: String,
            clearExisting: Boolean
        ): ActionOutcome = ActionOutcome.CORRECT

        override suspend fun scrollScreen(
            direction: String,
            preSignature: String,
            amount: Double
        ): ActionOutcome = ActionOutcome.CORRECT

        override suspend fun swipeScreen(fingerDirection: String, distance: Double): Boolean = true

        override suspend fun scrollUntil(
            direction: String,
            maxScrolls: Int,
            predicate: (List<PrunedUiElement>) -> Boolean
        ): Boolean = false

        override suspend fun <T : Any> scrollCollect(
            maxScrolls: Int,
            endAfterUnchanged: Int,
            extractor: (PrunedUiElement) -> T?
        ): List<T> = emptyList()

        override suspend fun navigateBack(): Boolean = true
        override suspend fun navigateHome(): Boolean = true
        override suspend fun navigateRecents(): Boolean = true
    }

    private class NoOpSovereignVoiceHud : VoiceHudPort {
        override val voiceCommandFlow: Flow<String> = emptyFlow()
        override val bargeInInterruptFlow: Flow<String> = emptyFlow()
        override suspend fun initialize() = Unit
        override suspend fun showIdlePill() = Unit
        override suspend fun hideOverlay() = Unit
        override suspend fun startListening() = Unit
        override suspend fun showThinkingState(stepHeadline: String, thought: String) = Unit
        override suspend fun askDisambiguation(questionTts: String, options: List<String>): String =
            options.firstOrNull().orEmpty()
        override suspend fun requestHitlApproval(summaryPrompt: String, targetLabel: String): Boolean = true
        override suspend fun finishAndSpeak(completionTts: String) = Unit
        override fun dispose() = Unit
    }
}
