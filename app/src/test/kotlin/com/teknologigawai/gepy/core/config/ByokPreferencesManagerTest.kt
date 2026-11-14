package com.teknologigawai.gepy.core.config

import android.content.SharedPreferences
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian unit JVM untuk model konfigurasi [ByokSettings]
 * dan manajer preferensi [ByokPreferencesManager] pada Personal AI Agent.
 */
class ByokPreferencesManagerTest {

    @Test
    fun byokSettings_defaultsAreConfiguredForPersonalAiAgent() {
        val settings = ByokSettings()
        assertEquals(PreferredLlmProvider.AUTO, settings.preferredProvider)
        assertTrue(settings.sovereignPersonalMode)
        assertEquals(SovereignPersonaMode.ADAPTIVE, settings.sovereignPersonaMode)
        assertEquals(ProactivityLevel.CONTEXT_COPILOT, settings.proactivityLevel)
        assertEquals(1.5f, settings.liveCallVolumeScale, 0.001f)
        assertTrue(
            "Label UI persona tidak boleh menampilkan kata 'Jarvis'",
            SovereignPersonaMode.entries.none { it.label.contains("Jarvis", ignoreCase = true) }
        )
    }

    @Test
    fun byokSettings_allowsUpdatingPersonaAndProactivity() {
        val base = ByokSettings()
        val updated = base.copy(
            sovereignPersonaMode = SovereignPersonaMode.CUSTOM,
            customPersonaPrompt = "Panggil Bos.",
            proactivityLevel = ProactivityLevel.AUTONOMOUS
        )

        assertEquals(SovereignPersonaMode.CUSTOM, updated.sovereignPersonaMode)
        assertEquals("Panggil Bos.", updated.customPersonaPrompt)
        assertEquals(ProactivityLevel.AUTONOMOUS, updated.proactivityLevel)
    }

    @Test
    fun byokPreferencesManager_inMemoryDefaultsAndLiveCallVolumeClamping() {
        val manager = ByokPreferencesManager(null)
        assertFalse(manager.hasAcceptedDisclosure)
        assertEquals(SovereignPersonaMode.ADAPTIVE, manager.currentSettings.sovereignPersonaMode)
        assertEquals(1.5f, manager.currentSettings.liveCallVolumeScale, 0.001f)

        manager.updateSettings(ByokSettings(liveCallVolumeScale = 5.0f))
        assertEquals(2.0f, manager.currentSettings.liveCallVolumeScale, 0.001f)

        manager.updateSettings(ByokSettings(liveCallVolumeScale = -1.0f))
        assertEquals(0.0f, manager.currentSettings.liveCallVolumeScale, 0.001f)
    }

    @Test
    fun byokSettings_defaultElevenLabsCacheMaxMbIs200AndClampedAndHasVoiceProfile() {
        assertEquals(200, ByokPreferencesManager.DEFAULT_ELEVENLABS_CACHE_MAX_MB)
        val defaults = ByokSettings()
        assertEquals(200, defaults.elevenLabsCacheMaxMb)

        val offlineVoiceOnly = ByokSettings(
            elevenLabsEnabled = true,
            elevenLabsApiKeyOverride = "",
            elevenLabsVoiceIdOverride = "gepy_custom_voice"
        )
        assertTrue(offlineVoiceOnly.hasElevenLabsVoiceProfile)

        val disabledVoice = offlineVoiceOnly.copy(elevenLabsEnabled = false)
        assertFalse(disabledVoice.hasElevenLabsVoiceProfile)

        val manager = ByokPreferencesManager(null)
        manager.updateSettings(ByokSettings(elevenLabsCacheMaxMb = 250))
        assertEquals(250, manager.currentSettings.elevenLabsCacheMaxMb)
        manager.updateSettings(ByokSettings(elevenLabsCacheMaxMb = 999))
        assertEquals(250, manager.currentSettings.elevenLabsCacheMaxMb)
        manager.updateSettings(ByokSettings(elevenLabsCacheMaxMb = 1))
        assertEquals(10, manager.currentSettings.elevenLabsCacheMaxMb)
    }

    @Test
    fun byokPreferencesManager_encryptsSecretsAndMigratesLegacyPlaintext() {
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
        val testKey: SecretKey = keyGen.generateKey()

        val fakePrefs = InMemorySharedPreferences()
        // Simulasikan kunci legacy berformat teks polos dari versi lama
        fakePrefs.edit().putString("gemini_api_key", "AIzaLegacyPlaintext123").commit()
        fakePrefs.edit().putString("elevenlabs_api_key", "v1_gcm:INVALID_BASE64!!!").commit()

        val manager = ByokPreferencesManager(fakePrefs) { testKey }

        // 1. Kunci legacy terbaca benar dan langsung dimigrasikan menjadi "v1_gcm:..."
        assertEquals("AIzaLegacyPlaintext123", manager.currentSettings.geminiApiKeyOverride)
        val migratedRaw = fakePrefs.getString("gemini_api_key", "").orEmpty()
        assertTrue(migratedRaw.startsWith("v1_gcm:"))
        assertFalse(migratedRaw.contains("AIzaLegacyPlaintext123"))

        // 2. Ciphertext rusak gagal secara aman (mengembalikan "" tanpa bocor sebagai kunci)
        assertEquals("", manager.currentSettings.elevenLabsApiKeyOverride)

        // 3. Penyimpanan baru mengenkripsi seluruh kunci API dan bisa didekripsi ulang oleh instance baru
        manager.updateSettings(
            manager.currentSettings.copy(
                fireworksApiKeyOverride = "fw_secret_abc",
                customOpenAiApiKey = "sk-or-custom-xyz"
            )
        )
        val storedFw = fakePrefs.getString("fireworks_api_key", "").orEmpty()
        val storedCustom = fakePrefs.getString("custom_openai_api_key", "").orEmpty()
        assertTrue(storedFw.startsWith("v1_gcm:"))
        assertTrue(storedCustom.startsWith("v1_gcm:"))
        assertNotEquals("fw_secret_abc", storedFw)
        assertNotEquals("sk-or-custom-xyz", storedCustom)

        val reloaded = ByokPreferencesManager(fakePrefs) { testKey }
        assertEquals("fw_secret_abc", reloaded.currentSettings.fireworksApiKeyOverride)
        assertEquals("sk-or-custom-xyz", reloaded.currentSettings.customOpenAiApiKey)
        assertEquals("AIzaLegacyPlaintext123", reloaded.currentSettings.geminiApiKeyOverride)
    }

    @Test
    fun updateSettings_resetsProviderCooldownsAndPreservesSavedCustomKeyWhenDisabled() {
        val fakePrefs = InMemorySharedPreferences()
        val manager = ByokPreferencesManager(fakePrefs) { null }
        LlmProviderHealthTracker.markFailure(
            provider = PreferredLlmProvider.CUSTOM_OPENAI,
            signature = "sig-402",
            statusCode = 402,
            rawReason = "can only afford 475"
        )
        assertTrue(LlmProviderHealthTracker.activeCooldownsSnapshot().isNotEmpty())

        manager.updateSettings(
            ByokSettings(
                preferredProvider = PreferredLlmProvider.FIREWORKS,
                customOpenAiEnabled = false,
                customOpenAiUrl = "https://openrouter.ai/api/v1/chat/completions",
                customOpenAiApiKey = "sk-or-kept-safely",
                customOpenAiModel = "deepseek/deepseek-chat-v3-0324"
            )
        )

        assertTrue(
            "Menyimpan BYOK baru wajib memulihkan cooldown penyedia secara instan",
            LlmProviderHealthTracker.activeCooldownsSnapshot().isEmpty()
        )
        assertFalse(manager.currentSettings.customOpenAiEnabled)
        assertFalse(manager.currentSettings.hasCustomOpenAi)
        assertEquals("sk-or-kept-safely", manager.currentSettings.customOpenAiApiKey)
    }

    @Test
    fun byokProviderPreset_aimlApiAppliesEndpointAndFastModel() {
        val manager = ByokPreferencesManager(null)
        manager.applyCustomOpenAiPreset(ByokProviderPreset.AIML_API, apiKey = "aiml-test-key")

        assertEquals(PreferredLlmProvider.CUSTOM_OPENAI, manager.currentSettings.preferredProvider)
        assertTrue(manager.currentSettings.customOpenAiEnabled)
        assertTrue(manager.currentSettings.hasCustomOpenAi)
        assertEquals("https://api.aimlapi.com/v1/chat/completions", manager.currentSettings.normalizedCustomOpenAiUrl)
        assertEquals("google/gemini-3.1-flash-lite", manager.currentSettings.customOpenAiModel)
        assertEquals("aiml-test-key", manager.currentSettings.customOpenAiApiKey)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? MutableSet<String>) ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply {
                if (key != null) {
                    if (value == null) removals.add(key) else pending[key] = value
                }
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                data.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
