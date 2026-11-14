package com.teknologigawai.gepy.core.config

import com.teknologigawai.gepy.BuildConfig
import com.teknologigawai.gepy.core.logging.GepyLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvConfigTest {
    @Test
    fun `verified model IDs and package constants match docs MODELS_MD`() {
        assertEquals("com.teknologigawai.gepy", EnvConfig.SELF_PACKAGE_NAME)
        assertEquals("com.teknologigawai.gepy", EnvConfig.selfPackageName)
        assertEquals(
            "accounts/fireworks/models/deepseek-v4p1-flash",
            EnvConfig.fireworksModel
        )
        assertEquals("gemini-2.5-flash", EnvConfig.geminiModel)
        assertEquals("gemini-3.8-live", EnvConfig.geminiLiveModel)
        assertEquals("gemini-embedding-2", EnvConfig.geminiEmbeddingModel)
        assertEquals(768, EnvConfig.geminiEmbeddingDimensions)
        assertEquals("eleven_v4_turbo", EnvConfig.elevenLabsModel)
        assertEquals("https://api.elevenlabs.io/v1", EnvConfig.elevenLabsBaseUrl)
        assertEquals("gepy_core.db", EnvConfig.sqliteDbFileName)
        assertEquals(BuildConfig.GOOGLE_WEB_CLIENT_ID.trim(), EnvConfig.googleWebClientId)
        assertEquals(BuildConfig.GATEWAY_BASE_URL.trim(), EnvConfig.gatewayBaseUrl)
        assertEquals(BuildConfig.LAPTOP_TAILSCALE_HOST.trim(), EnvConfig.defaultLaptopTailscaleHost)
        assertEquals(5_000L, EnvConfig.defaultWaitStableTimeoutMs)
        assertEquals(400L, EnvConfig.defaultPollIntervalMs)
        assertEquals(2, EnvConfig.defaultSettleCount)
        assertEquals(20, EnvConfig.maxStepsPerSession)
    }

    @Test
    fun `GepyLog formats tag and message without throwing on JVM`() {
        val formatted = GepyLog.formatMessage("Perception", "Pruned 120 nodes to 14")
        assertEquals("[Perception] Pruned 120 nodes to 14", formatted)
        GepyLog.d("Test", "Debug message")
        GepyLog.i("Test", "Info message")
        GepyLog.w("Test", "Warn message")
        GepyLog.e("Test", "Error message", IllegalStateException("simulated"))
        assertTrue(formatted.startsWith("[Perception]"))
    }
}
