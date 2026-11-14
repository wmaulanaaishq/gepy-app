package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.laptop.LaptopCompanionBridge
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopCompanionSkillTest {

    private fun createMockSkill(
        responsePayload: String = """{"status": "SUCCESS", "summary_id": "Aksi sukses.", "summary_tts": "Semua 28 unit test lulus sempurna."}""",
        isEnabled: Boolean = true
    ): LaptopCompanionSkill {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responsePayload.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val bridge = LaptopCompanionBridge(client = client)

        return LaptopCompanionSkill(
            laptopBridge = bridge,
            hostProvider = { "100.64.0.20:8888" },
            isEnabledProvider = { isEnabled }
        )
    }

    private val dummyMemory = ShortTermMemorySnapshot(
        recentSteps = emptyList(),
        scratchpad = emptyMap()
    )

    @Test
    fun testCanHandleVoiceIntent_MatchingIntents() {
        val skill = createMockSkill()

        assertTrue(skill.canHandleVoiceIntent("Gep tolong kunci laptop saya"))
        assertTrue(skill.canHandleVoiceIntent("lock pc sekarang"))
        assertTrue(skill.canHandleVoiceIntent("kunci pc dong"))
        assertTrue(skill.canHandleVoiceIntent("tidurkan laptop"))
        assertTrue(skill.canHandleVoiceIntent("mode tidur laptop"))
        assertTrue(skill.canHandleVoiceIntent("sleep laptop"))
        assertTrue(skill.canHandleVoiceIntent("istirahatkan pc"))
        assertTrue(skill.canHandleVoiceIntent("laptop mode tidur"))
        assertTrue(skill.canHandleVoiceIntent("matikan laptop"))
        assertTrue(skill.canHandleVoiceIntent("shutdown pc"))
        assertTrue(skill.canHandleVoiceIntent("turn off laptop"))
        assertTrue(skill.canHandleVoiceIntent("batalkan shutdown"))
        assertTrue(skill.canHandleVoiceIntent("batal matikan laptop"))
        assertTrue(skill.canHandleVoiceIntent("volume laptop kecilkan"))
        assertTrue(skill.canHandleVoiceIntent("mute laptop ya"))
        // Perintah buka vscode tanpa kata 'di laptop' WAJIB didukung
        assertTrue(skill.canHandleVoiceIntent("buka vscode"))
        assertTrue(skill.canHandleVoiceIntent("tolong buka vscode"))
        assertTrue(skill.canHandleVoiceIntent("buka vs code"))
        assertTrue(skill.canHandleVoiceIntent("buka visual studio code"))
        assertTrue(skill.canHandleVoiceIntent("jalankan vscode"))
        assertTrue(skill.canHandleVoiceIntent("buka notepad"))
        assertTrue(skill.canHandleVoiceIntent("buka vscode di laptop"))
        assertTrue(skill.canHandleVoiceIntent("buka vscode di pc"))
        assertTrue(skill.canHandleVoiceIntent("buka chrome di laptop"))
        assertTrue(skill.canHandleVoiceIntent("buka antigravity"))
        assertTrue(skill.canHandleVoiceIntent("buka whatsapp di laptop"))
        assertTrue(skill.canHandleVoiceIntent("buka kalkulator di pc"))
        assertTrue(skill.canHandleVoiceIntent("buka discord di laptop"))
        assertTrue(skill.canHandleVoiceIntent("buka notion di laptop"))
        assertTrue(skill.canHandleVoiceIntent("buka notion calendar di pc"))
        assertTrue(skill.canHandleVoiceIntent("buka steam di laptop"))
        assertTrue(skill.canHandleVoiceIntent("buka figma di pc"))
        assertTrue(skill.canHandleVoiceIntent("buka excel di laptop"))
        assertTrue(skill.canHandleVoiceIntent("buka postman di pc"))
        assertTrue(skill.canHandleVoiceIntent("status laptop"))
        assertTrue(skill.canHandleVoiceIntent("cek laptop baterai berapa"))
        assertTrue(skill.canHandleVoiceIntent("salin dari laptop"))
        assertTrue(skill.canHandleVoiceIntent("jeda lagu laptop"))
        assertTrue(skill.canHandleVoiceIntent("suruh antigravity jalankan tes"))
        assertTrue(skill.canHandleVoiceIntent("tanya antigravity status proyek"))
        assertTrue(skill.canHandleVoiceIntent("antigravity tolong audit repo"))
        assertTrue(skill.canHandleVoiceIntent("cek status git di laptop"))
        assertTrue(skill.canHandleVoiceIntent("jalankan verifikasi di laptop"))
        assertTrue(skill.canHandleVoiceIntent("rakit apk di laptop"))

        // Negatif / Perintah HP biasa
        assertFalse(skill.canHandleVoiceIntent("buka whatsapp di hp"))
        assertFalse(skill.canHandleVoiceIntent("kirim pesan ke Budi"))
        assertFalse(skill.canHandleVoiceIntent("kunci pintu rumah"))
    }

    @Test
    fun testCanHandleVoiceIntent_WhenDisabled_ReturnsFalse() {
        val disabledSkill = createMockSkill(isEnabled = false)
        assertFalse(disabledSkill.canHandleVoiceIntent("kunci laptop"))
        assertFalse(disabledSkill.canHandleVoiceIntent("buka vscode di laptop"))
    }

    @Test
    fun testProposeFastPathAction_LockLaptop() {
        val payload = """
            {
                "status": "SUCCESS",
                "action": "lock",
                "summary_id": "Layar laptop berhasil dikunci."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "kunci laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertEquals("Layar laptop berhasil dikunci.", action?.completionTts)
        assertTrue(action?.thought.orEmpty().contains("Fast Path Laptop"))
    }

    @Test
    fun testProposeFastPathAction_SleepLaptop() {
        val payload = """
            {
                "status": "SUCCESS",
                "action": "sleep",
                "summary_id": "Laptop dialihkan ke mode Sleep (Standby S3). Layar mati, daya hemat, dan pekerjaan Anda tetap aman di memori."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "tidurkan laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertEquals("Laptop dialihkan ke mode Sleep (Standby S3). Layar mati, daya hemat, dan pekerjaan Anda tetap aman di memori.", action?.completionTts)
        assertTrue(action?.thought.orEmpty().contains("Fast Path Laptop"))
    }

    @Test
    fun testProposeFastPathAction_Shutdown_RequiresConfirmation() {
        val skill = createMockSkill()
        assertEquals(0L, skill.pendingShutdownTimestamp)

        val action = skill.proposeFastPathAction(
            userGoal = "matikan laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("Yakin mau dimatikan sekarang?"))
        assertFalse(action?.completionTts.orEmpty().contains("Anda"))
        assertTrue(skill.pendingShutdownTimestamp > 0L)
    }

    @Test
    fun testProposeFastPathAction_Shutdown_ConfirmedWithinWindow() {
        val payload = """
            {
                "status": "SUCCESS",
                "action": "shutdown",
                "grace_period_seconds": 30,
                "summary_id": "Laptop sedang dalam proses shutdown (hitung mundur 30 detik)."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        // Langkah 1: Minta shutdown
        skill.proposeFastPathAction("matikan laptop", "com.android.launcher", emptyList(), dummyMemory)
        assertTrue(skill.pendingShutdownTimestamp > 0L)

        // Langkah 2: Konfirmasi dalam batas waktu
        val confirmAction = skill.proposeFastPathAction("ya matikan", "com.android.launcher", emptyList(), dummyMemory)
        assertNotNull(confirmAction)
        assertEquals(AgentActionType.DONE, confirmAction?.action)
        assertTrue(confirmAction?.completionTts.orEmpty().contains("shutdown"))
        // Timestamp harus direset ke 0 setelah eksekusi
        assertEquals(0L, skill.pendingShutdownTimestamp)
    }

    @Test
    fun testProposeFastPathAction_Shutdown_ConfirmationExpired() {
        val skill = createMockSkill()
        // Simulasikan timestamp telah kedaluwarsa (> 25 detik yang lalu)
        skill.pendingShutdownTimestamp = System.currentTimeMillis() - 30_000L

        val action = skill.proposeFastPathAction("ya matikan", "com.android.launcher", emptyList(), dummyMemory)
        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("waktu konfirmasi"))
        assertFalse(action?.completionTts.orEmpty().contains("Anda"))
        assertEquals(0L, skill.pendingShutdownTimestamp)
    }

    @Test
    fun testProposeFastPathAction_Shutdown_Cancelled() {
        val payload = """
            {
                "status": "SUCCESS",
                "action": "cancel_shutdown",
                "summary_id": "Proses shutdown laptop berhasil dibatalkan."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        // Langkah 1: Minta shutdown
        skill.proposeFastPathAction("matikan laptop", "com.android.launcher", emptyList(), dummyMemory)
        assertTrue(skill.pendingShutdownTimestamp > 0L)

        // Langkah 2: Batalkan
        val cancelAction = skill.proposeFastPathAction("batalkan", "com.android.launcher", emptyList(), dummyMemory)
        assertNotNull(cancelAction)
        assertEquals(AgentActionType.DONE, cancelAction?.action)
        assertTrue(cancelAction?.completionTts.orEmpty().contains("dibatalkan"))
        assertEquals(0L, skill.pendingShutdownTimestamp)
    }

    @Test
    fun testProposeFastPathAction_OpenVsCode() {
        val payload = """
            {
                "status": "SUCCESS",
                "action": "open_app",
                "target": "vscode",
                "summary_id": "Aplikasi 'vscode' berhasil diluncurkan di laptop."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "buka vscode di laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("vscode"))
    }

    @Test
    fun testProposeFastPathAction_OpenVsCode_WithoutDiLaptopKeyword() {
        val payload = """
            {
                "status": "SUCCESS",
                "action": "open_app",
                "target": "vscode",
                "summary_id": "Aplikasi 'vscode' berhasil diluncurkan di laptop."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "tolong buka vscode",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("vscode"))
    }

    @Test
    fun testProposeFastPathAction_AntigravityA2A_RunTests() {
        val payload = """
            {
                "protocol": "GEPY_A2A_v1",
                "status": "SUCCESS",
                "action": "RUN_TESTS",
                "summary_tts": "Pengujian unit JVM sukses. Seluruh test lulus 100%."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "Gepy, suruh Antigravity jalankan tes",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("Pengujian unit JVM sukses"))
    }

    @Test
    fun testProposeFastPathAction_AntigravityA2A_GitStatus() {
        val payload = """
            {
                "protocol": "GEPY_A2A_v1",
                "status": "SUCCESS",
                "action": "GIT_STATUS",
                "summary_tts": "Branch main bersih. Tidak ada file termodifikasi."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "cek status git di laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("Branch main bersih"))
    }

    @Test
    fun testProposeFastPathAction_AntigravityA2A_BuildRelease_Async() {
        val payload = """
            {
                "protocol": "GEPY_A2A_v1",
                "status": "PENDING",
                "action": "BUILD_RELEASE",
                "summary_tts": "Antigravity sedang merakit release APK di latar belakang laptop. Saya kabari begitu selesai!"
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "rakit release di laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("latar belakang"))
    }

    @Test
    fun testProposeFastPathAction_AntigravityA2A_CheckTaskStatus() {
        val payload = """
            {
                "protocol": "GEPY_A2A_v1",
                "status": "RUNNING",
                "action": "CHECK_TASK_STATUS",
                "summary_tts": "Antigravity saat ini sedang aktif menjalankan 'BUILD_RELEASE' di latar belakang laptop."
            }
        """.trimIndent()
        val skill = createMockSkill(responsePayload = payload)

        val action = skill.proposeFastPathAction(
            userGoal = "cek status tugas di laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts.orEmpty().contains("sedang aktif menjalankan"))
    }

    @Test
    fun testProposeFastPathAction_UnmatchedGoal_ReturnsNull() {
        val skill = createMockSkill()
        val action = skill.proposeFastPathAction(
            userGoal = "cari resep masakan di google",
            foregroundPackage = "com.android.chrome",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNull(action)
    }

    @Test
    fun testProposeFastPathAction_GirlfriendPersonaNaturalSpeech() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"status":"SUCCESS","action":"lock"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val girlSkill = LaptopCompanionSkill(
            laptopBridge = LaptopCompanionBridge(client = client),
            hostProvider = { "100.64.0.20:8888" },
            isEnabledProvider = { true },
            speechContextProvider = {
                SovereignSpeechContext(
                    configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
                    ownerName = "Maulana",
                    wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = false)
                )
            }
        )

        val shutdownPrompt = girlSkill.proposeFastPathAction(
            userGoal = "matikan laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNotNull(shutdownPrompt)
        assertTrue(shutdownPrompt?.completionTts.orEmpty().startsWith("[softly] "))
        assertTrue(shutdownPrompt?.completionTts.orEmpty().contains("Maulana"))
        assertFalse(shutdownPrompt?.completionTts.orEmpty().contains("sayang", ignoreCase = true))

        val lockAction = girlSkill.proposeFastPathAction(
            userGoal = "kunci laptop",
            foregroundPackage = "com.android.launcher",
            screenElements = emptyList(),
            memory = dummyMemory
        )
        assertNotNull(lockAction)
        assertTrue(lockAction?.completionTts.orEmpty().contains("Maulana"))
        assertFalse(lockAction?.completionTts.orEmpty().contains("sayang", ignoreCase = true))
        assertTrue(lockAction?.completionTts.orEmpty().contains("dikunci") || lockAction?.completionTts.orEmpty().contains("kunci"))
    }
}
