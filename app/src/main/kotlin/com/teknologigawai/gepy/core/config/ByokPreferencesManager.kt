package com.teknologigawai.gepy.core.config

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.teknologigawai.gepy.core.logging.GepyLog
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object ByokSecretCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gepy_byok_aes256_gcm_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    internal const val CIPHER_PREFIX = "v1_gcm:"

    private val keyLock = Any()

    fun getOrCreateKeystoreSecretKey(): SecretKey? {
        synchronized(keyLock) {
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
                }
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun encryptWithKey(secretKey: SecretKey, plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(1 + iv.size + ciphertext.size)
        payload[0] = iv.size.toByte()
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(ciphertext, 0, payload, 1 + iv.size, ciphertext.size)
        return CIPHER_PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    fun decryptWithKey(secretKey: SecretKey, stored: String): String? {
        if (!stored.startsWith(CIPHER_PREFIX)) return null
        val encoded = stored.removePrefix(CIPHER_PREFIX)
        val payload = Base64.getDecoder().decode(encoded)
        if (payload.isEmpty()) return null
        val ivLen = payload[0].toInt() and 0xFF
        if (ivLen !in 12..16 || payload.size <= 1 + ivLen) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, payload, 1, ivLen)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintextBytes = cipher.doFinal(payload, 1 + ivLen, payload.size - 1 - ivLen)
        return String(plaintextBytes, Charsets.UTF_8)
    }
}

/**
 * Pilihan urutan penyedia LLM utama (*Primary Provider*) untuk fitur *Bring Your Own Key (BYOK)*.
 */
enum class PreferredLlmProvider(val wireName: String, val displayLabel: String) {
    /** Urutan cerdas default: Fireworks AI -> Gemini 2.5 Flash -> Custom OpenAI -> Azure. */
    AUTO("AUTO", "Otomatis (Fireworks -> Gemini -> Custom)"),

    /** Mengutamakan Fireworks AI (`accounts/fireworks/models/deepseek-v4p1-flash`). */
    FIREWORKS("FIREWORKS", "Fireworks AI (DeepSeek V4.1 Flash)"),

    /** Mengutamakan Google Gemini (`gemini-2.5-flash`). */
    GEMINI("GEMINI", "Google Gemini (gemini-2.5-flash)"),

    /**
     * Mengutamakan endpoint OpenAI-Compatible kustom (OpenRouter, Groq, DeepSeek Direct,
     * Together AI, atau server lokal).
     */
    CUSTOM_OPENAI("CUSTOM_OPENAI", "Custom OpenAI-Compatible (AI/ML API / OpenRouter / Groq / DeepSeek)"),

    /** Mengutamakan Azure OpenAI Deployment. */
    AZURE("AZURE", "Azure OpenAI");

    companion object {
        fun fromWireName(raw: String?): PreferredLlmProvider {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: AUTO
        }
    }
}

/**
 * Mode kepribadian berdaulat (*Sovereign Persona Mode*) untuk **Gepy Personal AI Agent**:
 * - [ADAPTIVE]: **Gepy Taktis** sehari-hari + otomatis beralih ke **Mentor Tegas** saat mendeteksi *doomscrolling* atau rebahan berlebih.
 * - [JARVIS_TACTICAL]: Singkat, cerdas, taktis, fokus eksekusi misi tanpa basa-basi.
 * - [HONEST_MENTOR]: *Brutally Honest Alter-Ego* yang tegas menjaga fokus, produktivitas, dan kesehatan pemiliknya.
 * - [ROMANTIC_GIRLFRIEND]: Pasangan pribadi yang manja, genit, hangat, menggoda secara elegan, dan penuh perhatian.
 */
enum class SovereignPersonaMode(
    val wireName: String,
    val label: String,
    val shortDescription: String
) {
    ADAPTIVE(
        wireName = "ADAPTIVE",
        label = "Adaptif (Gepy Taktis + Mentor)",
        shortDescription = "Taktis sehari-hari, tegas saat mendeteksi doomscrolling/rebahan."
    ),
    JARVIS_TACTICAL(
        wireName = "JARVIS_TACTICAL",
        label = "Gepy Taktis & Efisien",
        shortDescription = "Singkat, presisi, eksekusi cepat tanpa basa-basi."
    ),
    HONEST_MENTOR(
        wireName = "HONEST_MENTOR",
        label = "Mentor Jujur & Tegas",
        shortDescription = "Alter-ego jujur yang menagih disiplin & kesehatanmu."
    ),
    ROMANTIC_GIRLFRIEND(
        wireName = "ROMANTIC_GIRLFRIEND",
        label = "Girlfriend Menggoda & Manja",
        shortDescription = "Pasangan pribadi yang manja, genit, hangat, menggoda, dan penuh perhatian."
    ),
    CUSTOM(
        wireName = "CUSTOM",
        label = "Kustom Berdaulat (Patuh)",
        shortDescription = "Persona kustom buatanmu sendiri: Gepy selalu setia, suportif, dan mutlak mematuhi segala perintahmu."
    );

    companion object {
        fun fromWireName(raw: String?): SovereignPersonaMode {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: ADAPTIVE
        }
    }
}

/**
 * Tingkat proaktif personal AI (*Proactivity Level*):
 * - [ZEN_PASSIVE]: Pasif murni. Membungkam bisikan HUD, sapaan datang CSI, pengingat pasif, & briefing otomatis.
 * - [CONTEXT_COPILOT]: Default efisien (`0` token). Bisikan halus HUD, sapaan datang, & Morning Briefing 1x/hari.
 * - [AUTONOMOUS]: Otonom berdaulat. Seluruh fitur Co-Pilot + Evening Reflection otomatis 1x/malam (`20:30–23:30`) & penjagaan baterai malam proaktif.
 */
enum class ProactivityLevel(val wireName: String, val label: String, val description: String) {
    ZEN_PASSIVE(
        "ZEN_PASSIVE",
        "Pasif (Zen / Hanya Merespons)",
        "Senyap penuh: bisikan HUD, sapaan datang, dan briefing otomatis dimatikan. Hanya merespons perintah langsung."
    ),
    CONTEXT_COPILOT(
        "CONTEXT_COPILOT",
        "Co-Pilot Cerdas (Rekomendasi)",
        "Bisikan HUD cerdas (resi/artikel/baterai), sapaan datang ruangan, & Morning Briefing 1x/hari tanpa cerewet."
    ),
    AUTONOMOUS(
        "AUTONOMOUS",
        "Otonom Berdaulat",
        "Semua fitur Co-Pilot + Evening Reflection otomatis 1x/malam (20:30–23:30) & peringatan baterai malam bersuara."
    );

    companion object {
        fun fromWireName(raw: String?): ProactivityLevel {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: CONTEXT_COPILOT
        }
    }
}

enum class ByokProviderPreset(
    val label: String,
    val chatCompletionsUrl: String,
    val recommendedModel: String
) {
    AIML_API(
        label = "AI/ML API",
        chatCompletionsUrl = "https://api.aimlapi.com/v1/chat/completions",
        recommendedModel = "google/gemini-3.1-flash-lite"
    ),
    OPENROUTER(
        label = "OpenRouter",
        chatCompletionsUrl = "https://openrouter.ai/api/v1/chat/completions",
        recommendedModel = "deepseek/deepseek-chat-v3-0324"
    ),
    GROQ(
        label = "Groq Cloud (LPU)",
        chatCompletionsUrl = "https://api.groq.com/openai/v1/chat/completions",
        recommendedModel = "llama-3.3-70b-versatile"
    ),
    DEEPSEEK_DIRECT(
        label = "DeepSeek API Resmi",
        chatCompletionsUrl = "https://api.deepseek.com/chat/completions",
        recommendedModel = "deepseek-chat"
    ),
    TOGETHER_AI(
        label = "Together AI",
        chatCompletionsUrl = "https://api.together.xyz/v1/chat/completions",
        recommendedModel = "deepseek-ai/DeepSeek-V3"
    )
}

/**
 * Model imutabel konfigurasi *Bring Your Own Key (BYOK)* yang menggabungkan kunci input
 * pengguna di runtime dengan nilai bawaan `EnvConfig` (`local_secrets.json`).
 */
data class ByokSettings(
    val preferredProvider: PreferredLlmProvider = PreferredLlmProvider.AUTO,
    val sovereignPersonalMode: Boolean = true,
    val sovereignPersonaMode: SovereignPersonaMode = SovereignPersonaMode.ADAPTIVE,
    val customPersonaPrompt: String = "",
    val proactivityLevel: ProactivityLevel = ProactivityLevel.CONTEXT_COPILOT,
    val antiDoomscrollEnabled: Boolean = true,
    val doomscrollLimitMinutes: Int = 15,
    val antiDoomscrollAutoHome: Boolean = false,
    val espCsiBridgeEnabled: Boolean = false,
    val espCsiPort: Int = 8765,
    val laptopCompanionEnabled: Boolean = true,
    val laptopTailscaleHost: String = EnvConfig.defaultLaptopTailscaleHost,
    val fireworksApiKeyOverride: String = "",
    val fireworksModelOverride: String = "",
    val geminiApiKeyOverride: String = "",
    val geminiSecondaryApiKeyOverride: String = "",
    val geminiModelOverride: String = "",
    val geminiEmbeddingModelOverride: String = "",
    val customOpenAiEnabled: Boolean = true,
    val customOpenAiUrl: String = "",
    val customOpenAiApiKey: String = "",
    val customOpenAiModel: String = "",
    val elevenLabsEnabled: Boolean = true,
    val elevenLabsApiKeyOverride: String = "",
    val elevenLabsVoiceIdOverride: String = "",
    val elevenLabsModelOverride: String = "",
    val elevenLabsStability: Float = 0.45f,
    val elevenLabsSimilarityBoost: Float = 0.75f,
    val elevenLabsCacheMaxMb: Int = ByokPreferencesManager.DEFAULT_ELEVENLABS_CACHE_MAX_MB,
    val liveCallVolumeScale: Float = 1.5f
) {
    private fun sanitizeApiKey(raw: String): String {
        var cleaned = raw.trim().trim('"', '\'', '`')
        if (cleaned.startsWith("Bearer ", ignoreCase = true)) {
            cleaned = cleaned.substring(7).trim()
        }
        if (cleaned.contains('=')) {
            val afterEq = cleaned.substringAfter('=').trim().trim('"', '\'')
            if (afterEq.isNotEmpty()) {
                cleaned = afterEq
            }
        }
        return cleaned.replace("\\s+".toRegex(), "")
    }

    val effectiveFireworksApiKey: String
        get() = sanitizeApiKey(fireworksApiKeyOverride).ifEmpty { EnvConfig.defaultFireworksApiKey.trim() }

    val effectiveFireworksModel: String
        get() = fireworksModelOverride.trim().ifEmpty { EnvConfig.fireworksModel }

    val effectiveGeminiApiKey: String
        get() {
            val direct = sanitizeApiKey(geminiApiKeyOverride)
            if (direct.isNotEmpty()) return direct
            // Deteksi otomatis bila pengguna tak sengaja menempelkan kunci Google AI Studio (AIza... / AQ....) di kolom lain
            val customCandidate = sanitizeApiKey(customOpenAiApiKey)
            if (EnvConfig.isGeminiApiKeyFormat(customCandidate)) return customCandidate
            val fwCandidate = sanitizeApiKey(fireworksApiKeyOverride)
            if (EnvConfig.isGeminiApiKeyFormat(fwCandidate)) return fwCandidate
            return EnvConfig.defaultGeminiApiKey.trim()
        }

    val effectiveGeminiSecondaryApiKey: String
        get() = sanitizeApiKey(geminiSecondaryApiKeyOverride).ifEmpty {
            EnvConfig.defaultGeminiSecondaryApiKey.trim()
        }

    val hasGeminiSecondaryApiKey: Boolean
        get() = effectiveGeminiSecondaryApiKey.isNotEmpty()

    val hasGeminiApiKey: Boolean
        get() = effectiveGeminiApiKey.isNotEmpty() || hasGeminiSecondaryApiKey

    val maskedGeminiApiKey: String
        get() {
            val key = effectiveGeminiApiKey
            if (key.isEmpty()) return "Belum Diisi"
            if (key.length <= 10) return "Aktif (***)"
            return "${key.take(6)}...${key.takeLast(4)}"
        }

    val maskedGeminiSecondaryApiKey: String
        get() {
            val key = effectiveGeminiSecondaryApiKey
            if (key.isEmpty()) return "Belum Diisi"
            if (key.length <= 10) return "Aktif (***)"
            return "${key.take(6)}...${key.takeLast(4)}"
        }

    val effectiveGeminiModel: String
        get() = geminiModelOverride.trim().ifEmpty { EnvConfig.geminiModel }

    val effectiveGeminiEmbeddingModel: String
        get() = geminiEmbeddingModelOverride.trim().ifEmpty { EnvConfig.geminiEmbeddingModel }

    /** Kolam kunci untuk penalaran LLM, Live Call, Web Grounding & Synthesis (Key 1 -> Failover Key 2). */
    val geminiChatKeyPool: List<String>
        get() = GeminiKeyPool.orderedKeys(
            purpose = GeminiKeyPurpose.CHAT_REASONING,
            primaryKey = effectiveGeminiApiKey,
            secondaryKey = effectiveGeminiSecondaryApiKey
        )

    /** Kolam kunci khusus Semantic Vector Embedding (Key 2 -> Failover Key 1). */
    val geminiEmbeddingKeyPool: List<String>
        get() = GeminiKeyPool.orderedKeys(
            purpose = GeminiKeyPurpose.EMBEDDING,
            primaryKey = effectiveGeminiApiKey,
            secondaryKey = effectiveGeminiSecondaryApiKey
        )

    /**
     * Menormalisasi URL endpoint OpenAI-compatible: jika pengguna hanya memasukkan base URL
     * (misal `https://api.groq.com/openai/v1`), otomatis melengkapi `/chat/completions`.
     */
    val normalizedCustomOpenAiUrl: String
        get() {
            val raw = customOpenAiUrl.trim().trimEnd('/')
            if (raw.isEmpty()) return ""
            return if (raw.endsWith("/chat/completions", ignoreCase = true)) {
                raw
            } else {
                "$raw/chat/completions"
            }
        }

    val hasCustomOpenAiCredentials: Boolean
        get() = normalizedCustomOpenAiUrl.isNotEmpty() &&
            customOpenAiApiKey.trim().isNotEmpty() &&
            customOpenAiModel.trim().isNotEmpty()

    val hasCustomOpenAi: Boolean
        get() = customOpenAiEnabled && hasCustomOpenAiCredentials

    val effectiveElevenLabsApiKey: String
        get() = sanitizeApiKey(elevenLabsApiKeyOverride).ifEmpty { EnvConfig.defaultElevenLabsApiKey.trim() }

    val effectiveElevenLabsVoiceId: String
        get() = elevenLabsVoiceIdOverride.trim().ifEmpty { EnvConfig.defaultElevenLabsVoiceId.trim() }

    val effectiveElevenLabsModel: String
        get() = elevenLabsModelOverride.trim().ifEmpty { EnvConfig.elevenLabsModel }

    val hasElevenLabsConfigured: Boolean
        get() = elevenLabsEnabled &&
            effectiveElevenLabsApiKey.isNotEmpty() &&
            effectiveElevenLabsVoiceId.isNotEmpty()

    /** Menandakan profil suara kustom (`voiceId`) aktif untuk memutar brankas cache lokal secara offline tanpa API Key. */
    val hasElevenLabsVoiceProfile: Boolean
        get() = elevenLabsEnabled && effectiveElevenLabsVoiceId.isNotEmpty()

    val maskedElevenLabsApiKey: String
        get() {
            val key = effectiveElevenLabsApiKey
            if (key.isEmpty()) return "Belum Diisi"
            if (key.length <= 10) return "Aktif (***)"
            return "${key.take(6)}...${key.takeLast(4)}"
        }
}

/**
 * Pengelola persistensi *Bring Your Own Key (BYOK)* berbasis `SharedPreferences`
 * dengan aliran reaktif `StateFlow<ByokSettings>`.
 *
 * Memungkinkan pengguna mengganti API Key atau beralih penyedia LLM (Fireworks, Gemini,
 * OpenRouter, Groq, DeepSeek Direct) langsung dari aplikasi tanpa kompilasi ulang APK.
 */
class ByokPreferencesManager internal constructor(
    private val prefs: SharedPreferences?,
    private val secretKeyProvider: () -> SecretKey?
) {
    constructor(context: Context? = null) : this(
        prefs = try {
            context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        },
        secretKeyProvider = { ByokSecretCipher.getOrCreateKeystoreSecretKey() }
    )

    companion object {
        private const val TAG = "ByokPrefs"
        const val PREFS_NAME: String = "gepy_byok_preferences"
        const val DEFAULT_ELEVENLABS_CACHE_MAX_MB: Int = 200
        private const val KEY_PREFERRED_PROVIDER = "preferred_provider"
        private const val KEY_SOVEREIGN_PERSONAL_MODE = "sovereign_personal_mode"
        private const val KEY_SOVEREIGN_PERSONA_MODE = "sovereign_persona_mode"
        private const val KEY_CUSTOM_PERSONA_PROMPT = "custom_persona_prompt"
        private const val KEY_PROACTIVITY_LEVEL = "proactivity_level"
        private const val KEY_ANTI_DOOMSCROLL_ENABLED = "anti_doomscroll_enabled"
        private const val KEY_DOOMSCROLL_LIMIT_MINUTES = "doomscroll_limit_minutes"
        private const val KEY_ANTI_DOOMSCROLL_AUTO_HOME = "anti_doomscroll_auto_home"
        private const val KEY_ESP_CSI_BRIDGE_ENABLED = "esp_csi_bridge_enabled"
        private const val KEY_ESP_CSI_PORT = "esp_csi_port"
        private const val KEY_LAPTOP_COMPANION_ENABLED = "laptop_companion_enabled"
        private const val KEY_LAPTOP_TAILSCALE_HOST = "laptop_tailscale_host"
        private const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted"
        private const val KEY_FIREWORKS_API_KEY = "fireworks_api_key"
        private const val KEY_FIREWORKS_MODEL = "fireworks_model"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_SECONDARY_API_KEY = "gemini_secondary_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GEMINI_EMBEDDING_MODEL = "gemini_embedding_model"
        private const val KEY_CUSTOM_OPENAI_ENABLED = "custom_openai_enabled"
        private const val KEY_CUSTOM_OPENAI_URL = "custom_openai_url"
        private const val KEY_CUSTOM_OPENAI_API_KEY = "custom_openai_api_key"
        private const val KEY_CUSTOM_OPENAI_MODEL = "custom_openai_model"
        private const val KEY_ELEVENLABS_ENABLED = "elevenlabs_enabled"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"
        private const val KEY_ELEVENLABS_MODEL = "elevenlabs_model"
        private const val KEY_ELEVENLABS_STABILITY = "elevenlabs_stability"
        private const val KEY_ELEVENLABS_SIMILARITY_BOOST = "elevenlabs_similarity_boost"
        private const val KEY_ELEVENLABS_CACHE_MAX_MB = "elevenlabs_cache_max_mb"
        private const val KEY_CACHE_DEFAULT_UPGRADED_V66 = "elevenlabs_cache_default_v66_migrated"
        private const val KEY_LIVE_CALL_VOLUME_SCALE = "live_call_volume_scale"
    }

    private val _settingsFlow = MutableStateFlow(loadFromStorage())
    val settingsFlow: StateFlow<ByokSettings> = _settingsFlow.asStateFlow()

    val currentSettings: ByokSettings
        get() = _settingsFlow.value

    /**
     * Memeriksa apakah pengguna telah menyetujui formulir *Prominent Disclosure* Aksesibilitas
     * yang diwajibkan oleh kebijakan Google Play Store.
     */
    val hasAcceptedDisclosure: Boolean
        get() = prefs?.getBoolean(KEY_DISCLOSURE_ACCEPTED, false) ?: false

    /** Menyimpan status persetujuan formulir *Prominent Disclosure*. */
    fun setDisclosureAccepted(accepted: Boolean) {
        prefs?.edit { putBoolean(KEY_DISCLOSURE_ACCEPTED, accepted) }
    }

    private fun readSecretPref(p: SharedPreferences, key: String, secretKey: SecretKey?): String {
        val raw = p.getString(key, "").orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.startsWith(ByokSecretCipher.CIPHER_PREFIX)) {
            if (secretKey == null) return ""
            return try {
                ByokSecretCipher.decryptWithKey(secretKey, raw).orEmpty()
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Gagal mendekripsi kunci tersimpan '$key': ${t.message}")
                ""
            }
        }
        // Migrasi otomatis satu kali dari penyimpanan teks polos lama ke AES-256-GCM Keystore
        if (secretKey != null) {
            try {
                val encrypted = ByokSecretCipher.encryptWithKey(secretKey, raw)
                if (encrypted.isNotEmpty()) {
                    p.edit { putString(key, encrypted) }
                }
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Migrasi enkripsi kunci '$key' ditunda: ${t.message}")
            }
        }
        return raw
    }

    private fun writeSecretPref(
        editor: SharedPreferences.Editor,
        key: String,
        plainValue: String,
        secretKey: SecretKey?
    ) {
        val clean = plainValue.trim()
        if (clean.isEmpty()) {
            editor.putString(key, "")
            return
        }
        val toStore = if (secretKey != null) {
            try {
                ByokSecretCipher.encryptWithKey(secretKey, clean)
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Gagal mengenkripsi kunci '$key', menyimpan nilai cadangan: ${t.message}")
                clean
            }
        } else {
            clean
        }
        editor.putString(key, toStore)
    }

    private fun loadFromStorage(): ByokSettings {
        val p = prefs ?: return ByokSettings()
        val secretKey = secretKeyProvider()
        val storedCacheMb = p.getInt(KEY_ELEVENLABS_CACHE_MAX_MB, DEFAULT_ELEVENLABS_CACHE_MAX_MB)
        val cacheDefaultMigrated = p.getBoolean(KEY_CACHE_DEFAULT_UPGRADED_V66, false)
        val resolvedCacheMb = if (!cacheDefaultMigrated) {
            val upgraded = if (storedCacheMb == 50) DEFAULT_ELEVENLABS_CACHE_MAX_MB else storedCacheMb
            p.edit {
                putBoolean(KEY_CACHE_DEFAULT_UPGRADED_V66, true)
                if (upgraded != storedCacheMb) {
                    putInt(KEY_ELEVENLABS_CACHE_MAX_MB, upgraded)
                }
            }
            upgraded
        } else {
            storedCacheMb
        }.coerceIn(10, 250)
        return ByokSettings(
            preferredProvider = PreferredLlmProvider.fromWireName(
                p.getString(KEY_PREFERRED_PROVIDER, PreferredLlmProvider.AUTO.wireName)
            ),
            sovereignPersonalMode = p.getBoolean(KEY_SOVEREIGN_PERSONAL_MODE, true),
            sovereignPersonaMode = SovereignPersonaMode.fromWireName(
                p.getString(KEY_SOVEREIGN_PERSONA_MODE, SovereignPersonaMode.ADAPTIVE.wireName)
            ),
            customPersonaPrompt = p.getString(KEY_CUSTOM_PERSONA_PROMPT, "").orEmpty(),
            proactivityLevel = ProactivityLevel.fromWireName(
                p.getString(KEY_PROACTIVITY_LEVEL, ProactivityLevel.CONTEXT_COPILOT.wireName)
            ),
            antiDoomscrollEnabled = p.getBoolean(KEY_ANTI_DOOMSCROLL_ENABLED, true),
            doomscrollLimitMinutes = p.getInt(KEY_DOOMSCROLL_LIMIT_MINUTES, 15).coerceIn(5, 120),
            antiDoomscrollAutoHome = p.getBoolean(KEY_ANTI_DOOMSCROLL_AUTO_HOME, false),
            espCsiBridgeEnabled = p.getBoolean(KEY_ESP_CSI_BRIDGE_ENABLED, false),
            espCsiPort = p.getInt(KEY_ESP_CSI_PORT, 8765).coerceIn(1024, 65535),
            laptopCompanionEnabled = p.getBoolean(KEY_LAPTOP_COMPANION_ENABLED, true),
            laptopTailscaleHost = p.getString(
                KEY_LAPTOP_TAILSCALE_HOST,
                EnvConfig.defaultLaptopTailscaleHost
            ).orEmpty().ifEmpty { EnvConfig.defaultLaptopTailscaleHost },
            fireworksApiKeyOverride = readSecretPref(p, KEY_FIREWORKS_API_KEY, secretKey),
            fireworksModelOverride = p.getString(KEY_FIREWORKS_MODEL, "").orEmpty(),
            geminiApiKeyOverride = readSecretPref(p, KEY_GEMINI_API_KEY, secretKey),
            geminiSecondaryApiKeyOverride = readSecretPref(p, KEY_GEMINI_SECONDARY_API_KEY, secretKey),
            geminiModelOverride = p.getString(KEY_GEMINI_MODEL, "").orEmpty(),
            geminiEmbeddingModelOverride = p.getString(KEY_GEMINI_EMBEDDING_MODEL, "").orEmpty(),
            customOpenAiEnabled = p.getBoolean(KEY_CUSTOM_OPENAI_ENABLED, true),
            customOpenAiUrl = p.getString(KEY_CUSTOM_OPENAI_URL, "").orEmpty(),
            customOpenAiApiKey = readSecretPref(p, KEY_CUSTOM_OPENAI_API_KEY, secretKey),
            customOpenAiModel = p.getString(KEY_CUSTOM_OPENAI_MODEL, "").orEmpty(),
            elevenLabsEnabled = p.getBoolean(KEY_ELEVENLABS_ENABLED, true),
            elevenLabsApiKeyOverride = readSecretPref(p, KEY_ELEVENLABS_API_KEY, secretKey),
            elevenLabsVoiceIdOverride = p.getString(KEY_ELEVENLABS_VOICE_ID, "").orEmpty(),
            elevenLabsModelOverride = p.getString(KEY_ELEVENLABS_MODEL, "").orEmpty(),
            elevenLabsStability = p.getFloat(KEY_ELEVENLABS_STABILITY, 0.45f).coerceIn(0f, 1f),
            elevenLabsSimilarityBoost = p.getFloat(KEY_ELEVENLABS_SIMILARITY_BOOST, 0.75f).coerceIn(0f, 1f),
            elevenLabsCacheMaxMb = resolvedCacheMb,
            liveCallVolumeScale = p.getFloat(KEY_LIVE_CALL_VOLUME_SCALE, 1.5f).coerceIn(0f, 2f)
        )
    }

    /** Menyimpan pembaruan pengaturan BYOK ke `SharedPreferences` (kunci API terenkripsi) dan `StateFlow`. */
    fun updateSettings(newSettings: ByokSettings) {
        val clampedCacheMb = newSettings.elevenLabsCacheMaxMb.coerceIn(10, 250)
        val clampedLiveVol = newSettings.liveCallVolumeScale.coerceIn(0f, 2f)
        val secretKey = if (prefs != null) secretKeyProvider() else null
        prefs?.edit()?.apply {
            putString(KEY_PREFERRED_PROVIDER, newSettings.preferredProvider.wireName)
            putBoolean(KEY_SOVEREIGN_PERSONAL_MODE, newSettings.sovereignPersonalMode)
            putString(KEY_SOVEREIGN_PERSONA_MODE, newSettings.sovereignPersonaMode.wireName)
            putString(KEY_CUSTOM_PERSONA_PROMPT, newSettings.customPersonaPrompt.trim())
            putString(KEY_PROACTIVITY_LEVEL, newSettings.proactivityLevel.wireName)
            putBoolean(KEY_ANTI_DOOMSCROLL_ENABLED, newSettings.antiDoomscrollEnabled)
            putInt(KEY_DOOMSCROLL_LIMIT_MINUTES, newSettings.doomscrollLimitMinutes.coerceIn(5, 120))
            putBoolean(KEY_ANTI_DOOMSCROLL_AUTO_HOME, newSettings.antiDoomscrollAutoHome)
            putBoolean(KEY_ESP_CSI_BRIDGE_ENABLED, newSettings.espCsiBridgeEnabled)
            putInt(KEY_ESP_CSI_PORT, newSettings.espCsiPort.coerceIn(1024, 65535))
            putBoolean(KEY_LAPTOP_COMPANION_ENABLED, newSettings.laptopCompanionEnabled)
            putString(
                KEY_LAPTOP_TAILSCALE_HOST,
                newSettings.laptopTailscaleHost.trim().ifEmpty { EnvConfig.defaultLaptopTailscaleHost }
            )
            writeSecretPref(this, KEY_FIREWORKS_API_KEY, newSettings.fireworksApiKeyOverride, secretKey)
            putString(KEY_FIREWORKS_MODEL, newSettings.fireworksModelOverride.trim())
            writeSecretPref(this, KEY_GEMINI_API_KEY, newSettings.geminiApiKeyOverride, secretKey)
            writeSecretPref(this, KEY_GEMINI_SECONDARY_API_KEY, newSettings.geminiSecondaryApiKeyOverride, secretKey)
            putString(KEY_GEMINI_MODEL, newSettings.geminiModelOverride.trim())
            putString(KEY_GEMINI_EMBEDDING_MODEL, newSettings.geminiEmbeddingModelOverride.trim())
            putBoolean(KEY_CUSTOM_OPENAI_ENABLED, newSettings.customOpenAiEnabled)
            putString(KEY_CUSTOM_OPENAI_URL, newSettings.customOpenAiUrl.trim())
            writeSecretPref(this, KEY_CUSTOM_OPENAI_API_KEY, newSettings.customOpenAiApiKey, secretKey)
            putString(KEY_CUSTOM_OPENAI_MODEL, newSettings.customOpenAiModel.trim())
            putBoolean(KEY_ELEVENLABS_ENABLED, newSettings.elevenLabsEnabled)
            writeSecretPref(this, KEY_ELEVENLABS_API_KEY, newSettings.elevenLabsApiKeyOverride, secretKey)
            putString(KEY_ELEVENLABS_VOICE_ID, newSettings.elevenLabsVoiceIdOverride.trim())
            putString(KEY_ELEVENLABS_MODEL, newSettings.elevenLabsModelOverride.trim())
            putFloat(KEY_ELEVENLABS_STABILITY, newSettings.elevenLabsStability.coerceIn(0f, 1f))
            putFloat(KEY_ELEVENLABS_SIMILARITY_BOOST, newSettings.elevenLabsSimilarityBoost.coerceIn(0f, 1f))
            putInt(KEY_ELEVENLABS_CACHE_MAX_MB, clampedCacheMb)
            putBoolean(KEY_CACHE_DEFAULT_UPGRADED_V66, true)
            putFloat(KEY_LIVE_CALL_VOLUME_SCALE, clampedLiveVol)
            apply()
        }
        LlmProviderHealthTracker.resetAll()
        GeminiKeyPool.resetCooldownsForTest()
        _settingsFlow.value = newSettings.copy(
            elevenLabsCacheMaxMb = clampedCacheMb,
            liveCallVolumeScale = clampedLiveVol
        )
    }

    /** Menerapkan preset cepat penyedia OpenAI-Compatible (OpenRouter, Groq, DeepSeek, Together). */
    fun applyCustomOpenAiPreset(preset: ByokProviderPreset, apiKey: String = currentSettings.customOpenAiApiKey) {
        updateSettings(
            currentSettings.copy(
                preferredProvider = PreferredLlmProvider.CUSTOM_OPENAI,
                customOpenAiEnabled = true,
                customOpenAiUrl = preset.chatCompletionsUrl,
                customOpenAiModel = preset.recommendedModel,
                customOpenAiApiKey = apiKey.trim()
            )
        )
    }

    /** Menghapus seluruh kunci BYOK kustom dan mengembalikan ke konfigurasi bawaan `BuildConfig`. */
    fun clearOverrides() {
        prefs?.edit { clear() }
        LlmProviderHealthTracker.resetAll()
        GeminiKeyPool.resetCooldownsForTest()
        _settingsFlow.value = ByokSettings()
    }
}
