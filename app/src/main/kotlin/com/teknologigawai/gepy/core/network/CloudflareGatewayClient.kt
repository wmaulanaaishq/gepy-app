package com.teknologigawai.gepy.core.network

import com.teknologigawai.gepy.core.auth.GepyUser
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.logging.GepyLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Model respons dari gateway saat autentikasi Google berhasil. */
data class GatewayAuthResponse(
    val sessionToken: String,
    val user: GepyUser
)

/**
 * Klien HTTP untuk berkomunikasi dengan Cloudflare Workers Edge Gateway (`gepy-api-gateway`).
 *
 * Mengelola:
 * - Verifikasi ID Token Google ke backend.
 * - Penyimpanan dan pencarian memori semantik (pgvector) personal pengguna.
 * - Proksi inferensi AI Gemini 2.5 Flash yang terlindungi.
 */
class CloudflareGatewayClient(
    val baseUrl: String = EnvConfig.gatewayBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    companion object {
        private const val TAG = "CloudflareGatewayClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CIRCUIT_BREAKER_DURATION_MS: Long = 180_000L // 3 menit
    }

    @Volatile
    private var offlineCircuitBreakerUntil: Long = 0L

    /** Memeriksa apakah gerbang sirkuit sedang terbuka (menahan panggilan jaringan latar belakang sementara). */
    val isCircuitOpen: Boolean
        get() = System.currentTimeMillis() < offlineCircuitBreakerUntil

    /** Menunjukkan apakah URL Cloudflare Gateway telah dikonfigurasi. */
    val isConfigured: Boolean
        get() {
            val clean = baseUrl.trim()
            return clean.startsWith("http://") || clean.startsWith("https://")
        }

    /** Mengaktifkan jeda sirkuit saat terjadi kegagalan jaringan atau SSL berulang. */
    fun tripCircuitBreaker(durationMs: Long = CIRCUIT_BREAKER_DURATION_MS) {
        offlineCircuitBreakerUntil = System.currentTimeMillis() + durationMs
        GepyLog.w(TAG, "Circuit breaker aktif: menahan panggilan cloud gateway selama ${durationMs / 1000} detik.")
    }

    /** Mereset jeda sirkuit (misal saat pengguna login manual atau jaringan pulih). */
    fun resetCircuitBreaker() {
        offlineCircuitBreakerUntil = 0L
    }

    /** Memverifikasi `id_token` dari Android Credential Manager dan mendapatkan token sesi JWT. */
    suspend fun authenticateWithGoogle(idToken: String): Result<GatewayAuthResponse> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) {
                return@withContext Result.failure(
                    IllegalStateException("Cloudflare Gateway URL belum dikonfigurasi.")
                )
            }
            try {
                resetCircuitBreaker()
                val url = "${baseUrl.trimEnd('/')}/api/auth/google"
                val jsonPayload = JSONObject().apply {
                    put("id_token", idToken)
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        GepyLog.w(TAG, "Autentikasi gateway gagal (HTTP ${response.code}): $body")
                        return@withContext Result.failure(
                            IOException("Gagal verifikasi ke gateway: HTTP ${response.code} ($body)")
                        )
                    }

                    val json = JSONObject(body)
                    val sessionToken = json.optString("session_token")
                    val userObj = json.optJSONObject("user")
                    val user = if (userObj != null) {
                        GepyUser(
                            id = userObj.optString("id"),
                            email = userObj.optString("email"),
                            displayName = userObj.optString("name"),
                            avatarUrl = userObj.optString("picture"),
                            sessionToken = sessionToken
                        )
                    } else {
                        GepyUser(id = "user", email = "", displayName = "Pengguna", sessionToken = sessionToken)
                    }

                    GepyLog.i(TAG, "Autentikasi gateway berhasil untuk: ${user.email}")
                    Result.success(GatewayAuthResponse(sessionToken = sessionToken, user = user))
                }
            } catch (e: Exception) {
                if (isTransientNetworkFailure(e)) {
                    tripCircuitBreaker()
                    GepyLog.w(TAG, "Kendala jaringan sementara saat autentikasi gateway: ${e.message}")
                } else {
                    GepyLog.e(TAG, "Kesalahan saat autentikasi gateway: ${e.message}", e)
                }
                Result.failure(e)
            }
        }

    /** Menyimpan preferensi/fakta personal pengguna ke basis data memori jangka panjang. */
    suspend fun storeMemory(
        content: String,
        category: String = "general",
        sessionToken: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (isCircuitOpen) {
            GepyLog.w(TAG, "Sinkronisasi memori cloud dilewati (circuit breaker aktif).")
            return@withContext Result.failure(
                IOException("Gateway sedang dalam masa jeda sirkuit offline.")
            )
        }
        if (!isConfigured) {
            return@withContext Result.failure(
                IllegalStateException("Cloudflare Gateway URL belum dikonfigurasi.")
            )
        }

        try {
            val url = "${baseUrl.trimEnd('/')}/api/memory/store"
            val jsonPayload = JSONObject().apply {
                put("content", content)
                put("category", category)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $sessionToken")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    GepyLog.w(TAG, "Simpan memori gagal (HTTP ${response.code}): $body")
                    return@withContext Result.failure(IOException("Gagal simpan memori: HTTP ${response.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            if (isTransientNetworkFailure(e)) {
                tripCircuitBreaker()
                GepyLog.w(TAG, "Kendala jaringan sementara saat simpan memori ke cloud: ${e.message}")
            } else {
                GepyLog.e(TAG, "Kesalahan saat simpan memori: ${e.message}", e)
            }
            Result.failure(e)
        }
    }

    /** Melakukan pencarian semantik terhadap riwayat ingatan personal pengguna. */
    suspend fun searchMemories(
        query: String,
        sessionToken: String,
        limit: Int = 3
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (!isConfigured || isCircuitOpen) {
            return@withContext Result.success(emptyList())
        }

        try {
            val url = "${baseUrl.trimEnd('/')}/api/memory/search"
            val jsonPayload = JSONObject().apply {
                put("query", query)
                put("limit", limit)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $sessionToken")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Gagal cari memori: HTTP ${response.code}"))
                }
                val json = JSONObject(body)
                val memoriesArr = json.optJSONArray("memories") ?: JSONArray()
                val results = mutableListOf<String>()
                for (i in 0 until memoriesArr.length()) {
                    val m = memoriesArr.optJSONObject(i)
                    if (m != null) {
                        val c = m.optString("content")
                        if (c.isNotBlank()) results.add(c)
                    }
                }
                Result.success(results)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Menghapus seluruh data memori pengguna dari database cloud (Kepatuhan Store Data Deletion). */
    suspend fun clearMemories(sessionToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.failure(
                IllegalStateException("Cloudflare Gateway URL belum dikonfigurasi.")
            )
        }
        try {
            val url = "${baseUrl.trimEnd('/')}/api/memory"
            val request = Request.Builder()
                .url(url)
                .delete()
                .header("Authorization", "Bearer $sessionToken")
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Mengirim prompt percakapan AI melalui Cloudflare Gateway yang otomatis menginjeksi memori. */
    suspend fun sendAiChatPrompt(
        prompt: String,
        sessionToken: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.failure(
                IllegalStateException("Cloudflare Gateway URL belum dikonfigurasi.")
            )
        }
        try {
            val url = "${baseUrl.trimEnd('/')}/api/ai/chat"
            val jsonPayload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            }.toString()

            val reqBuilder = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")

            if (!sessionToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $sessionToken")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Gateway AI error: HTTP ${response.code} ($body)"))
                }
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mengidentifikasi kegagalan jaringan sementara (koneksi seluler terputus, timeout, captive portal, SSL DNS redirection)
     * yang tidak mencerminkan bug internal kode, sehingga ditangani secara damai tanpa memicu alarm Sentry.
     */
    internal fun isTransientNetworkFailure(e: Throwable): Boolean {
        if (e is java.net.SocketTimeoutException ||
            e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e is javax.net.ssl.SSLHandshakeException ||
            e is java.security.cert.CertPathValidatorException ||
            e is java.security.cert.CertificateException
        ) {
            return true
        }
        val cause = e.cause
        return cause != null && isTransientNetworkFailure(cause)
    }
}
