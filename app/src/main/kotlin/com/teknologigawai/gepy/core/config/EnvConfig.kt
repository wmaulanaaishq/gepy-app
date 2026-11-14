package com.teknologigawai.gepy.core.config

import com.teknologigawai.gepy.BuildConfig

/**
 * Konfigurasi lingkungan, identitas paket, endpoint LLM & STT, serta konstanta waktu
 * untuk sistem Gepy (`com.teknologigawai.gepy`).
 *
 * Seluruh ID model AI telah diverifikasi sesuai kontrak pada `docs/MODELS.md`.
 */
object EnvConfig {
    /**
     * Nama paket resmi aplikasi Gepy di Android.
     *
     * Seluruh node pohon aksesibilitas dengan `packageName` ini wajib disaring
     * (*Self-Package Blindness*) agar Gepy tidak pernah mengetuk overlay sendiri.
     */
    const val SELF_PACKAGE_NAME: String = "com.teknologigawai.gepy"
    const val selfPackageName: String = SELF_PACKAGE_NAME

    /** Versi rilis resmi aplikasi dari `BuildConfig.VERSION_NAME`. */
    val appVersionName: String
        get() = BuildConfig.VERSION_NAME.ifBlank { "6.6.0" }

    /** Kunci API Fireworks AI utama yang diinjeksi melalui `BuildConfig` (`local_secrets.json`). */
    val defaultFireworksApiKey: String
        get() = BuildConfig.FIREWORKS_API_KEY

    /** Nama model serverless Fireworks AI default berlatensi sangat rendah (`docs/MODELS.md`). */
    val fireworksModel: String
        get() = BuildConfig.FIREWORKS_MODEL.ifBlank {
            "accounts/fireworks/models/deepseek-v4p1-flash"
        }

    /** Endpoint Chat Completions resmi Fireworks AI Inference API v1. */
    const val fireworksChatCompletionsUrl: String =
        "https://api.fireworks.ai/inference/v1/chat/completions"

    /** Kunci API Gemini (failover ReAct & Gemini Live 3.8) dari `BuildConfig`. */
    val defaultGeminiApiKey: String
        get() = BuildConfig.GEMINI_API_KEY

    /** Kunci API Gemini kedua (dedikasi Semantic Vector Embedding & failover kuota 429) dari `BuildConfig`. */
    val defaultGeminiSecondaryApiKey: String
        get() = BuildConfig.GEMINI_API_KEY_2

    /** Nama model Gemini Flash untuk failover berlatensi rendah (`docs/MODELS.md`). */
    val geminiModel: String
        get() = BuildConfig.GEMINI_MODEL.ifBlank { "gemini-2.5-flash" }

    /** Model embedding resmi Google Gemini (`docs/MODELS.md`) untuk Layer 2 Semantic Memory. */
    val geminiEmbeddingModel: String
        get() = BuildConfig.GEMINI_EMBEDDING_MODEL.ifBlank { "gemini-embedding-2" }

    /** Dimensi terkompresi MRL (Matryoshka Representation Learning) untuk perangkat hemat RAM (`3 KB/vektor`). */
    const val geminiEmbeddingDimensions: Int = 768

    /** Memeriksa apakah string menyerupai format resmi Google Gemini / AI Studio API Key (`AIza...` atau `AQ....`). */
    fun isGeminiApiKeyFormat(raw: String): Boolean {
        val clean = raw.trim().trim('"', '\'', '`')
        return clean.startsWith("AIza") || clean.startsWith("AQ.")
    }

    /** Base URL REST API Google Generative Language (Gemini `v1beta`). */
    const val geminiBaseUrl: String =
        "https://generativelanguage.googleapis.com/v1beta"

    /** Endpoint WebSocket BidiGenerateContent resmi Gemini Live API (`v1beta`). */
    const val geminiLiveWsUrl: String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** Model default Gemini Live API untuk panggilan suara full-duplex dua arah (`docs/MODELS.md`). */
    val geminiLiveModel: String
        get() = BuildConfig.GEMINI_LIVE_MODEL.ifBlank { "gemini-3.8-live" }

    /** Base URL resmi ElevenLabs REST API v1 untuk sintesis suara personal (`gepy`). */
    const val elevenLabsBaseUrl: String = "https://api.elevenlabs.io/v1"

    /** Kunci API ElevenLabs default dari `BuildConfig` (`local_secrets.json`). */
    val defaultElevenLabsApiKey: String
        get() = BuildConfig.ELEVENLABS_API_KEY

    /** Voice ID ElevenLabs hasil cloning (`gepy`) dari `BuildConfig` (`local_secrets.json`). */
    val defaultElevenLabsVoiceId: String
        get() = BuildConfig.ELEVENLABS_VOICE_ID

    /** Model default ElevenLabs TTS (`docs/MODELS.md`). */
    val elevenLabsModel: String
        get() = BuildConfig.ELEVENLABS_MODEL.ifBlank { "eleven_v4_turbo" }

    /** Batas waktu koneksi jaringan HTTP ke ElevenLabs API (10 detik, tahan cold DNS/TLS seluler). */
    const val elevenLabsConnectTimeoutMs: Long = 10_000L

    /** Batas waktu baca unduhan stream audio MP3 ElevenLabs API (20 detik, tahan jitter seluler). */
    const val elevenLabsReadTimeoutMs: Long = 20_000L

    /** Batas waktu total maksimum per percobaan panggilan HTTP ElevenLabs (28 detik). */
    const val elevenLabsCallTimeoutMs: Long = 28_000L

    /** Endpoint Azure OpenAI cadangan dari `BuildConfig`. */
    val defaultAzureEndpoint: String
        get() = BuildConfig.AZURE_OPENAI_ENDPOINT

    /** Kunci API Azure OpenAI cadangan dari `BuildConfig`. */
    val defaultAzureApiKey: String
        get() = BuildConfig.AZURE_OPENAI_KEY

    /** Nama deployment Azure OpenAI untuk failover otomatis. */
    val azureDeploymentName: String
        get() = BuildConfig.AZURE_OPENAI_DEPLOYMENT.ifBlank { "gpt-4o-mini" }

    /** Versi API Azure OpenAI REST endpoint. */
    const val azureApiVersion: String = "2024-08-01-preview"

    /** Batas waktu koneksi jaringan HTTP ke penyedia LLM (6 detik). */
    const val llmConnectTimeoutMs: Long = 6_000L

    /** Batas waktu penerimaan respons inferensi dari penyedia LLM (14 detik agar failover cepat saat hang). */
    const val llmReceiveTimeoutMs: Long = 14_000L

    /** Batas waktu maksimum default untuk fungsi sinkronisasi layar `waitStable` (5000 ms). */
    const val defaultWaitStableTimeoutMs: Long = 5_000L

    /** Interval pemeriksaan tanda tangan layar di dalam fungsi `waitStable` (400 ms). */
    const val defaultPollIntervalMs: Long = 400L

    /** Jumlah siklus berturut-turut dengan tanda tangan layar identik agar layar dinyatakan stabil. */
    const val defaultSettleCount: Int = 2

    /** Jumlah maksimum langkah eksekusi dalam satu sesi perintah suara untuk mencegah perulangan tak berujung. */
    const val maxStepsPerSession: Int = 20

    /** Kapasitas maksimum ring buffer riwayat langkah di memori jangka pendek. */
    const val shortTermHistoryLimit: Int = 12

    /** Nama berkas database SQLite lokal Gepy. */
    const val sqliteDbFileName: String = "gepy_core.db"

    /** Alamat default Laptop Companion via Tailscale (`local_secrets.json`, kosong secara bawaan). */
    val defaultLaptopTailscaleHost: String
        get() = BuildConfig.LAPTOP_TAILSCALE_HOST

    /** Google OAuth Web Client ID resmi untuk Android Credential Manager & Backend Auth (`local_secrets.json`). */
    val googleWebClientId: String
        get() = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /** URL Gateway Edge Cloudflare Workers untuk proxy AI dan memori vektor (`local_secrets.json`). */
    val gatewayBaseUrl: String
        get() = BuildConfig.GATEWAY_BASE_URL
}
