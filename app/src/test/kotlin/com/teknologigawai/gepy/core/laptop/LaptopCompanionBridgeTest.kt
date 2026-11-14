package com.teknologigawai.gepy.core.laptop

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopCompanionBridgeTest {

    private fun createMockBridge(
        statusCode: Int = 200,
        responseBody: String = "{}",
        onIntercept: (okhttp3.Request) -> Unit = {}
    ): LaptopCompanionBridge {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            onIntercept(req)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        return LaptopCompanionBridge(client = client)
    }

    @Test
    fun testSanitizeHost_HandlesVariousFormats() {
        assertEquals("http://100.64.0.20:8888", LaptopCompanionBridge.sanitizeHost("100.64.0.20:8888"))
        assertEquals("http://100.64.0.20:8888", LaptopCompanionBridge.sanitizeHost("http://100.64.0.20:8888/"))
        assertEquals("https://laptop.example.ts.net", LaptopCompanionBridge.sanitizeHost("https://laptop.example.ts.net/"))
        assertEquals("", LaptopCompanionBridge.sanitizeHost("   "))
    }

    @Test
    fun testGetLaptopStatus_BlankHost_ReturnsUnconfigured() {
        val bridge = createMockBridge(statusCode = 200, responseBody = "{}")
        val result = bridge.getLaptopStatus("")
        assertEquals("UNCONFIGURED", result["status"])
    }

    @Test
    fun testGetLaptopStatus_SuccessParsing() {
        val payload = """
            {
                "status": "ONLINE",
                "hostname": "WORKSTATION",
                "active_window": "Visual Studio Code - gepy_native",
                "battery_percent": 95,
                "is_charging": true,
                "uptime_seconds": 3600,
                "summary_id": "Laptop WORKSTATION aktif. Baterai: 95%."
            }
        """.trimIndent()

        val bridge = createMockBridge(statusCode = 200, responseBody = payload)
        val result = bridge.getLaptopStatus("100.64.0.20:8888")

        assertEquals("SUCCESS", result["status"])
        assertEquals("WORKSTATION", result["hostname"])
        assertEquals("Visual Studio Code - gepy_native", result["active_window"])
        assertEquals(95, result["battery_percent"])
        assertEquals(true, result["is_charging"])
    }

    @Test
    fun testExecuteAction_LockScreen_Success() {
        var recordedUrl = ""
        val payload = """
            {
                "status": "SUCCESS",
                "action": "lock",
                "summary_id": "Layar laptop berhasil dikunci."
            }
        """.trimIndent()

        val bridge = createMockBridge(statusCode = 200, responseBody = payload) { req ->
            recordedUrl = req.url.toString()
        }

        val result = bridge.executeAction(action = "lock", host = "100.64.0.20:8888")
        assertEquals("http://100.64.0.20:8888/api/action", recordedUrl)
        assertEquals("SUCCESS", result["status"])
        assertEquals("lock", result["action"])
        assertTrue(result["summary_id"].toString().contains("dikunci"))
    }

    @Test
    fun testExecuteAction_VolumeAdjustment() {
        val payload = """
            {
                "status": "SUCCESS",
                "action": "volume",
                "summary_id": "Volume laptop dinaikkan."
            }
        """.trimIndent()

        val bridge = createMockBridge(statusCode = 200, responseBody = payload)
        val result = bridge.executeAction(
            action = "volume",
            params = mapOf("value" to "up"),
            host = "100.64.0.20:8888"
        )
        assertEquals("SUCCESS", result["status"])
        assertEquals("volume", result["action"])
    }

    @Test
    fun testGetClipboardText_ParsesTextProperly() {
        val payload = """
            {
                "status": "SUCCESS",
                "text": "https://github.com/wmaulanaaishq/gepy-app",
                "length": 41
            }
        """.trimIndent()

        val bridge = createMockBridge(statusCode = 200, responseBody = payload)
        val result = bridge.getClipboardText("100.64.0.20:8888")

        assertEquals("SUCCESS", result["status"])
        assertEquals("https://github.com/wmaulanaaishq/gepy-app", result["text"])
        assertEquals(41, result["length"])
    }

    @Test
    fun testSetClipboardText_Success() {
        val payload = """
            {
                "status": "SUCCESS",
                "summary_id": "Teks berhasil disalin ke laptop."
            }
        """.trimIndent()

        val bridge = createMockBridge(statusCode = 200, responseBody = payload)
        val result = bridge.setClipboardText("Halo dari HP!", host = "100.64.0.20:8888")

        assertEquals("SUCCESS", result["status"])
        assertEquals("Teks berhasil disalin ke laptop.", result["summary_id"])
    }

    @Test
    fun testSendGuiClick_ReturnsTrueOn200() {
        val payload = """{"status": "SUCCESS"}"""
        val bridge = createMockBridge(statusCode = 200, responseBody = payload)
        val ok = bridge.sendGuiClick(500, 300, "100.64.0.20:8888")
        assertTrue(ok)
    }

    @Test
    fun testExecuteAntigravityRpc_SuccessParsing() {
        var recordedUrl = ""
        val payload = """
            {
                "protocol": "GEPY_A2A_v1",
                "request_id": "a2a_12345",
                "status": "SUCCESS",
                "action": "RUN_TESTS",
                "execution_time_ms": 1200,
                "summary_tts": "Pengujian unit JVM sukses. Seluruh test lulus 100%.",
                "markdown_details": "BUILD SUCCESSFUL",
                "result_data": {
                    "passed": true,
                    "target": "all"
                }
            }
        """.trimIndent()

        val bridge = createMockBridge(statusCode = 200, responseBody = payload) { req ->
            recordedUrl = req.url.toString()
        }

        val request = AntigravityRpcRequest(
            action = "RUN_TESTS",
            target = "all"
        )
        val response = bridge.executeAntigravityRpc(request, host = "100.64.0.20:8888")

        assertEquals("http://100.64.0.20:8888/api/antigravity/rpc", recordedUrl)
        assertEquals("SUCCESS", response.status)
        assertEquals("RUN_TESTS", response.action)
        assertEquals("Pengujian unit JVM sukses. Seluruh test lulus 100%.", response.summaryTts)
        assertEquals(true, response.resultData["passed"])
    }

    @Test
    fun testUnreachableHost_ReturnsGracefulErrorMap() {
        // Interceptor that throws IOException (simulating connection refused/timeout)
        val interceptor = Interceptor {
            throw java.io.IOException("Connection refused")
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val bridge = LaptopCompanionBridge(client = client)

        val result = bridge.getLaptopStatus("100.64.0.20:8888")
        assertEquals("UNREACHABLE", result["status"])
        assertTrue(result["summary_id"].toString().contains("tidak terjangkau"))
    }
}
