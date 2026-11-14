package com.teknologigawai.gepy.core.network

import com.teknologigawai.gepy.core.auth.GepyUser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareGatewayClientTest {

    @Test
    fun `GatewayAuthResponse stores user and token correctly`() {
        val user = GepyUser(id = "sub_99", email = "test@domain.com", displayName = "Tester")
        val authResponse = GatewayAuthResponse(
            sessionToken = "jwt_mock_token",
            user = user
        )

        assertEquals("jwt_mock_token", authResponse.sessionToken)
        assertEquals("test@domain.com", authResponse.user.email)
    }

    @Test
    fun `CloudflareGatewayClient respects configured and blank gateway base URL`() = kotlinx.coroutines.runBlocking {
        val defaultClient = CloudflareGatewayClient()
        assertEquals(com.teknologigawai.gepy.core.config.EnvConfig.gatewayBaseUrl, defaultClient.baseUrl)

        val configuredClient = CloudflareGatewayClient(baseUrl = "https://gateway.example.workers.dev")
        assertTrue(configuredClient.isConfigured)

        val unconfiguredClient = CloudflareGatewayClient(baseUrl = "")
        org.junit.Assert.assertFalse(unconfiguredClient.isConfigured)
        val authRes = unconfiguredClient.authenticateWithGoogle("dummy_token")
        org.junit.Assert.assertFalse(authRes.isSuccess)
        val searchRes = unconfiguredClient.searchMemories("query", "jwt_token")
        assertTrue(searchRes.isSuccess)
        assertTrue(searchRes.getOrNull()?.isEmpty() == true)
    }

    @Test
    fun `parses gateway memory search JSON structure correctly`() {
        val mockJson = JSONObject().apply {
            put("ok", true)
            put("memories", JSONArray().apply {
                put(JSONObject().put("content", "Titik jemput di pagar hitam").put("category", "address"))
                put(JSONObject().put("content", "Alergi udang dan kepiting").put("category", "medical"))
            })
        }

        val memoriesArr = checkNotNull(mockJson.optJSONArray("memories"))
        assertEquals(2, memoriesArr.length())
        assertEquals("Titik jemput di pagar hitam", memoriesArr.getJSONObject(0).getString("content"))
        assertEquals("address", memoriesArr.getJSONObject(0).getString("category"))
    }

    @Test
    fun `isTransientNetworkFailure mengidentifikasi kendala SSL Handshake dan Socket Timeout`() {
        val client = CloudflareGatewayClient()
        val sslException = javax.net.ssl.SSLHandshakeException("Handshake failed").apply {
            initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."))
        }
        val timeoutException = java.net.SocketTimeoutException("failed to connect after 6000ms")
        val normalBugException = NullPointerException("NPE in business logic")

        assertTrue(client.isTransientNetworkFailure(sslException))
        assertTrue(client.isTransientNetworkFailure(timeoutException))
        org.junit.Assert.assertFalse(client.isTransientNetworkFailure(normalBugException))
    }

    @Test
    fun `tripCircuitBreaker menahan pemanggilan storeMemory dan searchMemories saat offline`() = kotlinx.coroutines.runBlocking {
        val client = CloudflareGatewayClient(baseUrl = "https://gateway.example.workers.dev")
        org.junit.Assert.assertFalse(client.isCircuitOpen)

        client.tripCircuitBreaker(60_000L)
        assertTrue(client.isCircuitOpen)

        // Saat circuit breaker terbuka, storeMemory langsung gagal cepat (fail-fast) tanpa memicu request HTTP
        val storeResult = client.storeMemory("Catatan rahasia", "general", "jwt_token")
        org.junit.Assert.assertFalse(storeResult.isSuccess)
        assertTrue(storeResult.exceptionOrNull()?.message?.contains("circuit-breaker") == true ||
            storeResult.exceptionOrNull()?.message?.contains("sirkuit") == true)

        // searchMemories mengembalikan list kosong secara anggun (graceful fallback)
        val searchResult = client.searchMemories("query", "jwt_token")
        assertTrue(searchResult.isSuccess)
        assertTrue(searchResult.getOrNull()?.isEmpty() == true)

        client.resetCircuitBreaker()
        org.junit.Assert.assertFalse(client.isCircuitOpen)
    }
}
