package com.teknologigawai.gepy.core.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiKeyPoolTest {

    @Before
    fun setUp() {
        GeminiKeyPool.resetCooldownsForTest()
    }

    @After
    fun tearDown() {
        GeminiKeyPool.resetCooldownsForTest()
    }

    @Test
    fun `sanitizeKey membersihkan kutip bearer assignment dan spasi tersembunyi`() {
        assertEquals("AIzaTestKey123", GeminiKeyPool.sanitizeKey("  \"Bearer AIza Test Key 123\"  "))
        assertEquals("AQ.Ab8SampleKey", GeminiKeyPool.sanitizeKey("GEMINI_API_KEY_2='AQ.Ab8SampleKey'"))
        assertEquals("", GeminiKeyPool.sanitizeKey("   `  `   "))
    }

    @Test
    fun `orderedKeys memisahkan beban embedding ke Key 2 dan chat live ke Key 1`() {
        val k1 = "primary_key_1"
        val k2 = "secondary_key_2"

        assertEquals(listOf(k2, k1), GeminiKeyPool.orderedKeys(GeminiKeyPurpose.EMBEDDING, k1, k2))
        assertEquals(listOf(k1, k2), GeminiKeyPool.orderedKeys(GeminiKeyPurpose.CHAT_REASONING, k1, k2))
        assertEquals(listOf(k1, k2), GeminiKeyPool.orderedKeys(GeminiKeyPurpose.LIVE_VOICE, k1, k2))
        assertEquals(listOf(k1, k2), GeminiKeyPool.orderedKeys(GeminiKeyPurpose.WEB_GROUNDING, k1, k2))
        assertEquals(listOf(k1, k2), GeminiKeyPool.orderedKeys(GeminiKeyPurpose.MEMORY_SYNTHESIS, k1, k2))
    }

    @Test
    fun `orderedKeys menduplikasi unik dan menyaring kunci kosong`() {
        assertEquals(listOf("only_k1"), GeminiKeyPool.orderedKeys(GeminiKeyPurpose.EMBEDDING, "only_k1", ""))
        assertEquals(listOf("same_key"), GeminiKeyPool.orderedKeys(GeminiKeyPurpose.CHAT_REASONING, "same_key", " same_key "))
        assertTrue(GeminiKeyPool.orderedKeys(GeminiKeyPurpose.EMBEDDING, " ", "").isEmpty())
    }

    @Test
    fun `cooldown 60 detik memindahkan traffic ke kunci sehat lalu pulih setelah kadaluarsa`() {
        val t0 = 1_000_000L
        val k1 = "key_one"
        val k2 = "key_two"

        GeminiKeyPool.markQuotaExhausted(k1, cooldownMs = 60_000L, nowMs = t0)

        assertTrue(GeminiKeyPool.isInCooldown(k1, nowMs = t0 + 10_000L))
        assertFalse(GeminiKeyPool.isInCooldown(k2, nowMs = t0 + 10_000L))

        // Chat melewati k1 yang cooldown dan langsung memakai k2
        assertEquals(
            listOf(k2),
            GeminiKeyPool.orderedKeys(GeminiKeyPurpose.CHAT_REASONING, k1, k2, nowMs = t0 + 10_000L)
        )

        // Setelah 60 detik berlalu, k1 kembali dipulihkan ke urutan utama
        assertFalse(GeminiKeyPool.isInCooldown(k1, nowMs = t0 + 60_001L))
        assertEquals(
            listOf(k1, k2),
            GeminiKeyPool.orderedKeys(GeminiKeyPurpose.CHAT_REASONING, k1, k2, nowMs = t0 + 60_001L)
        )
    }

    @Test
    fun `jika semua kunci cooldown orderedKeys melakukan fallback aman ke urutan preferensi`() {
        val t0 = 5_000_000L
        val k1 = "exhausted_k1"
        val k2 = "exhausted_k2"

        GeminiKeyPool.markQuotaExhausted(k1, cooldownMs = 60_000L, nowMs = t0)
        GeminiKeyPool.markQuotaExhausted(k2, cooldownMs = 60_000L, nowMs = t0)

        assertEquals(
            listOf(k2, k1),
            GeminiKeyPool.orderedKeys(GeminiKeyPurpose.EMBEDDING, k1, k2, nowMs = t0 + 5_000L)
        )
        assertEquals(
            listOf(k1, k2),
            GeminiKeyPool.orderedKeys(GeminiKeyPurpose.LIVE_VOICE, k1, k2, nowMs = t0 + 5_000L)
        )
    }

    @Test
    fun `deteksi HTTP 429 RESOURCE_EXHAUSTED dan penolakan kunci 401 atau 403 akurat`() {
        assertTrue(GeminiKeyPool.isQuotaOrRateLimitResponse(429, "{}"))
        assertTrue(GeminiKeyPool.isQuotaOrRateLimitResponse(503, """{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))
        assertTrue(GeminiKeyPool.isQuotaOrRateLimitResponse(400, "Quota exceeded for embedContent"))
        assertTrue(GeminiKeyPool.isQuotaOrRateLimitResponse(500, "Rate limit reached"))
        assertFalse(GeminiKeyPool.isQuotaOrRateLimitResponse(400, """{"error":"INVALID_ARGUMENT"}"""))

        assertTrue(GeminiKeyPool.isKeyRejectedResponse(401))
        assertTrue(GeminiKeyPool.isKeyRejectedResponse(403))
        assertFalse(GeminiKeyPool.isKeyRejectedResponse(429))
        assertFalse(GeminiKeyPool.isKeyRejectedResponse(200))
    }
}
