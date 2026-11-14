package com.teknologigawai.gepy.core.laptop

import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge
import com.teknologigawai.gepy.core.wellness.WellnessAndGuardianEngine
import com.teknologigawai.gepy.features.voice.GeminiLiveClient
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityAsyncCallbackTest {

    @Test
    fun testAsyncRpcRequestSerialization() {
        var recordedBody = ""
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val buffer = Buffer()
            req.body?.writeTo(buffer)
            recordedBody = buffer.readUtf8()

            val pendingResponse = """
                {
                    "protocol": "GEPY_A2A_v1",
                    "request_id": "test_req_42",
                    "status": "PENDING",
                    "action": "BUILD_RELEASE",
                    "summary_tts": "Tugas BUILD_RELEASE sedang berjalan di latar belakang laptop.",
                    "execution_time_ms": 2,
                    "result_data": {"job_id": "task_build_42"}
                }
            """.trimIndent()

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(pendingResponse.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val bridge = LaptopCompanionBridge(client = client)

        val req = AntigravityRpcRequest(
            action = "BUILD_RELEASE",
            requestId = "test_req_42",
            isAsync = true,
            callbackUrl = "http://100.64.0.10:8765/api/antigravity/callback"
        )

        val resp = bridge.executeAntigravityRpc(req, host = "100.64.0.20:8888")

        // Verifikasi serialisasi JSON request
        val json = JSONObject(recordedBody)
        assertEquals("BUILD_RELEASE", json.optString("action"))
        assertTrue(json.optBoolean("async", false))
        assertEquals("http://100.64.0.10:8765/api/antigravity/callback", json.optString("callback_url"))
        assertEquals("test_req_42", json.optString("request_id"))

        // Verifikasi deserialisasi JSON response
        assertEquals("PENDING", resp.status)
        assertEquals("BUILD_RELEASE", resp.action)
        assertEquals("test_req_42", resp.requestId)
        assertEquals("task_build_42", resp.resultData["job_id"])
        assertTrue(resp.summaryTts.contains("latar belakang"))
    }

    @Test
    fun testEspCsiAmbientBridgeTailscaleIpAllowed() {
        // Tailscale CGNAT 100.64.0.0/10
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("100.64.0.10")))
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("100.64.0.20")))
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("100.64.0.1")))

        // LAN & Loopback
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("192.168.1.100")))
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("10.0.0.5")))
        assertTrue(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("172.16.0.2")))

        // Public IPs (wajib ditolak)
        assertFalse(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(EspCsiAmbientBridge.isPrivateOrLoopbackAddress(InetAddress.getByName("142.250.190.46")))
    }

    @Test
    fun testEspCsiAmbientBridgeCallbackWebhookDelivery() = runBlocking {
        val byok = ByokPreferencesManager(context = null)
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok
        )
        val bridge = EspCsiAmbientBridge(wellnessEngine = wellnessEngine)

        var receivedCallback: AntigravityRpcResponse? = null
        bridge.onAntigravityCallbackListener = { resp ->
            receivedCallback = resp
        }

        val started = bridge.startServer(port = 0)
        assertTrue("Peladen lokal harus berhasil dimulai pada port acak", started)
        val port = bridge.boundPort
        assertTrue("Port harus > 0", port > 0)

        val callbackPayload = """
            {
                "protocol": "GEPY_A2A_v1",
                "request_id": "async_cb_777",
                "status": "SUCCESS",
                "action": "VERIFY_ALL",
                "summary_tts": "Seluruh tes dan verifikasi repo selesai 100% di laptop.",
                "execution_time_ms": 4820,
                "result_data": {
                    "tests_passed": 42,
                    "lint_errors": 0
                }
            }
        """.trimIndent()

        val url = URL("http://127.0.0.1:$port/api/antigravity/callback")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.connectTimeout = 3000
        conn.readTimeout = 3000

        OutputStreamWriter(conn.outputStream, "UTF-8").use {
            it.write(callbackPayload)
            it.flush()
        }

        val responseCode = conn.responseCode
        assertEquals(200, responseCode)
        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
        val respJson = JSONObject(responseBody)
        assertEquals("DELIVERED", respJson.optString("status"))
        assertEquals("async_cb_777", respJson.optString("request_id"))

        // Beri jeda sangat singkat untuk coroutine listener memproses
        kotlinx.coroutines.delay(50)

        assertNotNull("Listener callback harus menerima respon", receivedCallback)
        assertEquals("SUCCESS", receivedCallback?.status)
        assertEquals("VERIFY_ALL", receivedCallback?.action)
        assertEquals("async_cb_777", receivedCallback?.requestId)
        assertEquals(4820L, receivedCallback?.executionTimeMs)
        assertEquals(42, receivedCallback?.resultData?.get("tests_passed"))
        assertTrue(receivedCallback?.summaryTts?.contains("100%") == true)

        bridge.stopServer()
        assertFalse(bridge.isRunningFlow.value)
    }

    @Test
    fun testGeminiLiveClientInterruptionMessageFormatting() {
        val client = GeminiLiveClient(apiKey = "dummy_key_live")
        val announcement = "Build release APK versi v6.3.0 selesai dirakit di laptop."
        val formattedPrompt = "[SISTEM ANTIGRAVITY]: Laporan tugas latar belakang selesai dari laptop: $announcement. Tolong sampaikan hasil ini langsung ke pengguna secara ringkas dan bersahabat."

        val json = client.buildInitialGreetingJson(formattedPrompt)

        assertTrue(json.has("clientContent"))
        val clientContent = json.getJSONObject("clientContent")
        assertTrue(clientContent.getBoolean("turnComplete"))

        val turns = clientContent.getJSONArray("turns")
        assertEquals(1, turns.length())
        val turn0 = turns.getJSONObject(0)
        assertEquals("user", turn0.getString("role"))

        val parts = turn0.getJSONArray("parts")
        val text = parts.getJSONObject(0).getString("text")
        assertTrue(text.contains(announcement))
        assertTrue(text.contains("[SISTEM ANTIGRAVITY]"))
    }
}
