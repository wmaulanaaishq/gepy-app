package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.logging.GepyLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Respons mentah HTTP untuk klien embedding semantik agar 100% dapat diuji pada JVM Unit Test. */
data class EmbeddingHttpResponse(
    val statusCode: Int,
    val body: String
)

/** Abstraksi transport HTTP minimal untuk [GeminiEmbeddingClient]. */
fun interface EmbeddingHttpTransport {
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String
    ): EmbeddingHttpResponse
}

/** Implementasi produksi [EmbeddingHttpTransport] berbatas waktu singkat agar ramah RAM & UI HP. */
class OkHttpEmbeddingTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4_000L, TimeUnit.MILLISECONDS)
        .readTimeout(6_000L, TimeUnit.MILLISECONDS)
        .writeTimeout(4_000L, TimeUnit.MILLISECONDS)
        .build()
) : EmbeddingHttpTransport {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String
    ): EmbeddingHttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        client.newCall(builder.build()).execute().use { resp ->
            EmbeddingHttpResponse(
                statusCode = resp.code,
                body = resp.body?.string().orEmpty()
            )
        }
    }
}

/** Hasil pemanggilan API `embedContent` Gemini Embedding 2. */
sealed class EmbeddingResult {
    data class Success(
        val vector: FloatArray,
        val modelId: String
    ) : EmbeddingResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return vector.contentEquals(other.vector) && modelId == other.modelId
        }

        override fun hashCode(): Int {
            var result = vector.contentHashCode()
            result = 31 * result + modelId.hashCode()
            return result
        }
    }

    data class Failure(
        val reason: String,
        val statusCode: Int = 0,
        val isNetworkError: Boolean = false
    ) : EmbeddingResult()
}

/**
 * Klien **Google Gemini Embedding 2 (`gemini-embedding-2`, 768-dim MRL)** untuk memori semantik
 * jangka panjang Layer 2 on-device (**Gepy v5.7.0**).
 *
 * Fitur utama:
 * - Memakai format prompt resmi Google untuk *asymmetric retrieval* dokumen vs kueri.
 * - Menggunakan autentikasi header `x-goog-api-key` dan `embedContentConfig.outputDimensionality = 768`.
 * - Mendukung rotasi kunci ganda (**Key 2 utama -> Failover otomatis ke Key 1 bila HTTP 429**).
 * - Dilengkapi *circuit cooldown* jaringan 30 detik agar mode offline tidak memblokir pencarian lokal Layer 1.
 * - Normalisasi L2 otomatis & enkoding biner kompak `Float32 Little-Endian` (`~3 KB` per memori).
 */
class GeminiEmbeddingClient(
    private val httpTransport: EmbeddingHttpTransport = OkHttpEmbeddingTransport(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val networkCooldownMs: Long = NETWORK_FAILURE_COOLDOWN_MS,
    private val targetDimensions: Int = EnvConfig.geminiEmbeddingDimensions
) {
    companion object {
        private const val TAG = "GeminiEmbedding"
        const val NETWORK_FAILURE_COOLDOWN_MS: Long = 30_000L

        /** Format dokumen resmi Gemini Embedding 2 untuk pencocokan asimetris memori personal. */
        fun formatDocumentPrompt(title: String?, content: String): String {
            val cleanTitle = title?.trim()?.ifEmpty { "none" } ?: "none"
            val cleanText = content.trim().take(2048)
            return "title: $cleanTitle | text: $cleanText"
        }

        /** Format kueri resmi Gemini Embedding 2 untuk pencarian memori personal. */
        fun formatQueryPrompt(query: String): String {
            val cleanQuery = query.trim().take(1024)
            return "task: search result | query: $cleanQuery"
        }

        /** Melakukan normalisasi L2 ($v / \|v\|_2$) agar dot product setara dengan cosine similarity. */
        fun l2Normalize(vector: FloatArray): FloatArray {
            if (vector.isEmpty()) return FloatArray(0)
            var sumSq = 0.0
            for (v in vector) {
                sumSq += v.toDouble() * v.toDouble()
            }
            val norm = sqrt(sumSq)
            if (norm <= 1e-12) {
                return FloatArray(vector.size)
            }
            val invNorm = (1.0 / norm).toFloat()
            return FloatArray(vector.size) { idx -> vector[idx] * invNorm }
        }

        /**
         * Menghitung kemiripan kosinus ($\cos(\theta) \in [-1, 1]$) secara aman dan cepat (~0.01 ms
         * per 300 vektor 768-dim pada CPU ARM).
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return 0f
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                val va = a[i].toDouble()
                val vb = b[i].toDouble()
                dot += va * vb
                normA += va * va
                normB += vb * vb
            }
            val denom = sqrt(normA * normB)
            if (denom <= 1e-12) return 0f
            return (dot / denom).toFloat().coerceIn(-1f, 1f)
        }

        /** Mengodekan array `FloatArray` ke BLOB biner `Float32 Little-Endian` (`dimensions * 4` byte). */
        fun encodeFloat32LittleEndian(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in vector) {
                buffer.putFloat(v)
            }
            return buffer.array()
        }

        /** Mendekodekan BLOB biner `Float32 Little-Endian` menjadi `FloatArray`. */
        fun decodeFloat32LittleEndian(blob: ByteArray): FloatArray {
            if (blob.isEmpty() || blob.size % 4 != 0) return FloatArray(0)
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val count = blob.size / 4
            return FloatArray(count) { buffer.getFloat() }
        }

        /** Menghitung hash SHA-256 heksadesimal untuk deduplikasi konten sebelum memanggil API cloud. */
        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(text.trim().lowercase().toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(((b.toInt() ushr 4) and 0xF).toString(16))
                sb.append((b.toInt() and 0xF).toString(16))
            }
            return sb.toString()
        }
    }

    @Volatile
    private var networkBackoffUntilMs: Long = 0L

    fun isNetworkBackoffActive(nowMs: Long = clockMs()): Boolean = nowMs < networkBackoffUntilMs

    internal fun resetForTest() {
        networkBackoffUntilMs = 0L
    }

    suspend fun embedDocument(
        title: String? = null,
        content: String,
        keyPool: List<String>,
        modelId: String = EnvConfig.geminiEmbeddingModel
    ): EmbeddingResult {
        val cleanContent = content.trim()
        if (cleanContent.length < 3) {
            return EmbeddingResult.Failure("Konten memori terlalu singkat untuk embedding.")
        }
        return requestEmbedding(
            promptText = formatDocumentPrompt(title, cleanContent),
            keyPool = keyPool,
            modelId = modelId
        )
    }

    suspend fun embedQuery(
        query: String,
        keyPool: List<String>,
        modelId: String = EnvConfig.geminiEmbeddingModel
    ): EmbeddingResult {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) {
            return EmbeddingResult.Failure("Kueri pencarian terlalu singkat untuk embedding.")
        }
        return requestEmbedding(
            promptText = formatQueryPrompt(cleanQuery),
            keyPool = keyPool,
            modelId = modelId
        )
    }

    private suspend fun requestEmbedding(
        promptText: String,
        keyPool: List<String>,
        modelId: String
    ): EmbeddingResult {
        val nowMs = clockMs()
        if (isNetworkBackoffActive(nowMs)) {
            return EmbeddingResult.Failure(
                reason = "Cooldown offline embedding aktif selama 30 detik (degradasi instan Layer 1).",
                isNetworkError = true
            )
        }

        val usableKeys = keyPool
            .map { GeminiKeyPool.sanitizeKey(it) }
            .filter {
                it.isNotEmpty() &&
                    !it.equals("test_key", ignoreCase = true) &&
                    !it.equals("dummy_key", ignoreCase = true)
            }
            .distinct()

        if (usableKeys.isEmpty()) {
            return EmbeddingResult.Failure("Tidak ada kunci Gemini aktif untuk embedding.")
        }

        val cleanModel = modelId.trim().ifBlank { EnvConfig.geminiEmbeddingModel }.removePrefix("models/")
        val url = "${EnvConfig.geminiBaseUrl}/models/$cleanModel:embedContent"
        val payload = JSONObject().apply {
            put("model", "models/$cleanModel")
            put(
                "content",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", promptText))
                )
            )
            put(
                "embedContentConfig",
                JSONObject().put("outputDimensionality", targetDimensions)
            )
        }
        val payloadJson = payload.toString()

        var lastStatus = 0
        var lastReason = "Gagal menghitung vektor semantik."

        for (activeKey in usableKeys) {
            try {
                val resp = httpTransport.postJson(
                    url = url,
                    headers = mapOf(
                        "x-goog-api-key" to activeKey,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                    jsonBody = payloadJson
                )
                lastStatus = resp.statusCode

                if (resp.statusCode in 200..299) {
                    val values = parseEmbeddingValues(resp.body)
                    if (values.isNotEmpty()) {
                        val clipped = if (values.size > targetDimensions) {
                            values.copyOf(targetDimensions)
                        } else {
                            values
                        }
                        return EmbeddingResult.Success(
                            vector = l2Normalize(clipped),
                            modelId = cleanModel
                        )
                    }
                    lastReason = "Respons Gemini Embedding kosong."
                    break
                }

                if (GeminiKeyPool.isQuotaOrRateLimitResponse(resp.statusCode, resp.body)) {
                    GepyLog.w(TAG, "Kuota embedding penuh (HTTP ${resp.statusCode}), failover ke kunci cadangan...")
                    GeminiKeyPool.markQuotaExhausted(activeKey, nowMs = clockMs())
                    lastReason = "HTTP ${resp.statusCode}: kuota embedding penuh."
                    continue
                }

                if (GeminiKeyPool.isKeyRejectedResponse(resp.statusCode)) {
                    GepyLog.w(TAG, "Kunci embedding ditolak (HTTP ${resp.statusCode}), beralih ke kunci cadangan...")
                    GeminiKeyPool.markQuotaExhausted(activeKey, GeminiKeyPool.INVALID_KEY_COOLDOWN_MS, nowMs = clockMs())
                    lastReason = "HTTP ${resp.statusCode}: API key embedding ditolak."
                    continue
                }

                lastReason = "Gemini Embedding HTTP ${resp.statusCode}: ${resp.body.take(160)}"
                break
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                networkBackoffUntilMs = clockMs() + networkCooldownMs
                GepyLog.w(TAG, "Kegagalan jaringan embedding (${t.message}), cooldown ${networkCooldownMs / 1000}d diaktifkan.")
                return EmbeddingResult.Failure(
                    reason = "Gangguan jaringan embedding: ${t.message}",
                    isNetworkError = true
                )
            }
        }

        return EmbeddingResult.Failure(
            reason = lastReason,
            statusCode = lastStatus
        )
    }

    internal fun parseEmbeddingValues(rawJson: String): FloatArray {
        return try {
            val obj = JSONObject(rawJson)
            val arr = obj.optJSONObject("embedding")?.optJSONArray("values")
                ?: obj.optJSONArray("embeddings")?.optJSONObject(0)?.optJSONArray("values")
                ?: return FloatArray(0)
            if (arr.length() == 0) return FloatArray(0)
            FloatArray(arr.length()) { idx -> arr.optDouble(idx, 0.0).toFloat() }
        } catch (_: Throwable) {
            FloatArray(0)
        }
    }
}
