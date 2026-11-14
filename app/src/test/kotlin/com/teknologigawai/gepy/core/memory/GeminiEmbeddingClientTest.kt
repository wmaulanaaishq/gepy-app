package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.GeminiKeyPool
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiEmbeddingClientTest {

    @Before
    fun setUp() {
        GeminiKeyPool.resetCooldownsForTest()
    }

    @After
    fun tearDown() {
        GeminiKeyPool.resetCooldownsForTest()
    }

    @Test
    fun `format prompt asimetris normalisasi L2 cosine Float32 LittleEndian dan SHA-256 bekerja presisi`() {
        assertEquals("title: none | text: Pengguna alergi udang", GeminiEmbeddingClient.formatDocumentPrompt(null, "  Pengguna alergi udang "))
        assertEquals("title: health | text: Pengguna alergi udang", GeminiEmbeddingClient.formatDocumentPrompt("health", "Pengguna alergi udang"))
        assertEquals("task: search result | query: pantangan makan", GeminiEmbeddingClient.formatQueryPrompt("  pantangan makan "))

        val norm = GeminiEmbeddingClient.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, norm[0], 1e-5f)
        assertEquals(0.8f, norm[1], 1e-5f)
        assertEquals(1.0f, GeminiEmbeddingClient.cosineSimilarity(norm, norm), 1e-5f)
        assertEquals(0.0f, GeminiEmbeddingClient.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
        assertEquals(0.0f, GeminiEmbeddingClient.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 0f)), 1e-5f)

        val original = FloatArray(768) { idx -> (idx - 384) / 500f }
        val encoded = GeminiEmbeddingClient.encodeFloat32LittleEndian(original)
        assertEquals(768 * 4, encoded.size)
        val decoded = GeminiEmbeddingClient.decodeFloat32LittleEndian(encoded)
        assertArrayEquals(original, decoded, 1e-6f)
        assertEquals(0, GeminiEmbeddingClient.decodeFloat32LittleEndian(byteArrayOf(1, 2, 3)).size)

        val hashA = GeminiEmbeddingClient.sha256Hex("  Halo Dunia Gepy  ")
        val hashB = GeminiEmbeddingClient.sha256Hex("halo dunia gepy")
        assertEquals(64, hashA.length)
        assertEquals(hashA, hashB)
    }

    @Test
    fun `embedDocument dan embedQuery memanggil endpoint official gemini-embedding-2 dengan header x-goog-api-key dan MRL 768`() = runTest {
        var capturedUrl = ""
        var capturedHeaders = emptyMap<String, String>()
        var capturedJson = JSONObject()

        val transport = EmbeddingHttpTransport { url, headers, jsonBody ->
            capturedUrl = url
            capturedHeaders = headers
            capturedJson = JSONObject(jsonBody)

            val values = JSONArray()
            values.put(3.0)
            values.put(4.0)
            repeat(766) { values.put(0.0) }
            val respObj = JSONObject().put(
                "embedding",
                JSONObject().put("values", values)
            )
            EmbeddingHttpResponse(200, respObj.toString())
        }

        val client = GeminiEmbeddingClient(httpTransport = transport)
        val result = client.embedDocument(
            title = "family: siblings",
            content = "Adik pengguna bernama Farhan",
            keyPool = listOf("embed_k2_active")
        )

        assertTrue(result is EmbeddingResult.Success)
        val success = result as EmbeddingResult.Success
        assertEquals("${EnvConfig.geminiBaseUrl}/models/gemini-embedding-2:embedContent", capturedUrl)
        assertEquals("embed_k2_active", capturedHeaders["x-goog-api-key"])
        assertEquals("models/gemini-embedding-2", capturedJson.getString("model"))
        assertEquals(768, capturedJson.getJSONObject("embedContentConfig").getInt("outputDimensionality"))
        val sentText = capturedJson.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("title: family: siblings | text: Adik pengguna bernama Farhan", sentText)
        assertEquals(768, success.vector.size)
        assertEquals(0.6f, success.vector[0], 1e-5f)
        assertEquals(0.8f, success.vector[1], 1e-5f)

        val qResult = client.embedQuery(query = "siapa adik saya", keyPool = listOf("embed_k2_active"))
        assertTrue(qResult is EmbeddingResult.Success)
        val querySentText = capturedJson.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("task: search result | query: siapa adik saya", querySentText)
    }

    @Test
    fun `embedQuery melakukan failover otomatis Key 2 ke Key 1 saat HTTP 429 dan HTTP 403`() = runTest {
        val seenKeys = mutableListOf<String>()
        val transport = EmbeddingHttpTransport { _, headers, _ ->
            val key = headers["x-goog-api-key"].orEmpty()
            seenKeys.add(key)
            when (key) {
                "k2_429" -> EmbeddingHttpResponse(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""")
                "k3_403" -> EmbeddingHttpResponse(403, """{"error":{"code":403,"message":"API_KEY_INVALID"}}""")
                else -> {
                    val values = JSONArray(List(768) { idx -> if (idx == 0) 1.0 else 0.0 })
                    EmbeddingHttpResponse(200, JSONObject().put("embedding", JSONObject().put("values", values)).toString())
                }
            }
        }

        val client = GeminiEmbeddingClient(httpTransport = transport)
        val result = client.embedQuery(
            query = "dimana rumah pengguna",
            keyPool = listOf("k2_429", "k3_403", "k1_ok")
        )

        assertTrue(result is EmbeddingResult.Success)
        assertEquals(listOf("k2_429", "k3_403", "k1_ok"), seenKeys)
        assertTrue(GeminiKeyPool.isInCooldown("k2_429"))
        assertTrue(GeminiKeyPool.isInCooldown("k3_403"))
        assertFalse(GeminiKeyPool.isInCooldown("k1_ok"))
    }

    @Test
    fun `kegagalan jaringan memicu cooldown 30 detik lalu pulih otomatis`() = runTest {
        var fakeNow = 10_000_000L
        var networkDown = true
        var httpCallCount = 0

        val transport = EmbeddingHttpTransport { _, _, _ ->
            httpCallCount++
            if (networkDown) {
                throw IOException("Unable to resolve host")
            }
            val values = JSONArray(List(768) { idx -> if (idx == 0) 1.0 else 0.0 })
            EmbeddingHttpResponse(200, JSONObject().put("embedding", JSONObject().put("values", values)).toString())
        }

        val client = GeminiEmbeddingClient(
            httpTransport = transport,
            clockMs = { fakeNow },
            networkCooldownMs = 30_000L
        )

        val firstAttempt = client.embedQuery("kopi favorit", listOf("real_embed_key"))
        assertTrue(firstAttempt is EmbeddingResult.Failure)
        assertTrue((firstAttempt as EmbeddingResult.Failure).isNetworkError)
        assertEquals(1, httpCallCount)
        assertTrue(client.isNetworkBackoffActive(fakeNow + 1_000L))

        // Selama masa cooldown 30 detik, transport tidak dipanggil lagi
        fakeNow += 15_000L
        val throttledAttempt = client.embedQuery("kopi favorit", listOf("real_embed_key"))
        assertTrue(throttledAttempt is EmbeddingResult.Failure)
        assertEquals(1, httpCallCount)

        // Setelah melewati 30 detik, transport kembali dipanggil dan pulih
        networkDown = false
        fakeNow += 16_000L
        assertFalse(client.isNetworkBackoffActive(fakeNow))
        val recoveredAttempt = client.embedQuery("kopi favorit", listOf("real_embed_key"))
        assertTrue(recoveredAttempt is EmbeddingResult.Success)
        assertEquals(2, httpCallCount)
    }

    @Test
    fun `key dummy atau test_key disaring sehingga tidak memanggil API jaringan`() = runTest {
        var httpCallCount = 0
        val client = GeminiEmbeddingClient(
            httpTransport = EmbeddingHttpTransport { _, _, _ ->
                httpCallCount++
                EmbeddingHttpResponse(500, "{}")
            }
        )

        val res = client.embedDocument(
            content = "Catatan penting pengguna",
            keyPool = listOf("test_key", "dummy_key", " ")
        )
        assertTrue(res is EmbeddingResult.Failure)
        assertEquals(0, httpCallCount)
    }
}
