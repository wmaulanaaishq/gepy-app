package com.teknologigawai.gepy.features.voice

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.logging.GepyLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Ringkasan kuota credit karakter ElevenLabs (`GET /v1/user/subscription`). */
data class ElevenLabsSubscriptionInfo(
    val characterCount: Int,
    val characterLimit: Int,
    val tier: String
) {
    val remainingCredits: Int
        get() = (characterLimit - characterCount).coerceAtLeast(0)
}

/** Hasil sintesis audio MP3 ke berkas lokal dari ElevenLabs REST API v1. */
sealed interface ElevenLabsSynthResult {
    data class Success(
        val file: File,
        val bytesWritten: Long
    ) : ElevenLabsSynthResult

    data class Failure(
        val reason: String,
        val httpStatus: Int? = null,
        val circuitOpened: Boolean = false,
        val transient: Boolean = false
    ) : ElevenLabsSynthResult
}

/**
 * Klien HTTP ringan berbasis streaming `OkHttpClient` untuk **ElevenLabs Text-to-Speech API v1**.
 *
 * Dirancang khusus untuk perangkat entry-level (Samsung Galaxy A06, RAM 4 GB, plafon aman `< 240 MB` Total PSS):
 * - Menggunakan streaming `ResponseBody.byteStream()` langsung ke berkas `.tmp` diikuti *atomic rename*
 *   sehingga tidak pernah mengalokasikan seluruh berkas audio ke dalam heap JVM.
 * - Toleran terhadap fluktuasi seluler 4G: menyediakan hingga `2x` retry backoff berbobot jitter,
 *   pembatalan soket seketika saat coroutine dibatalkan (*barge-in*), serta *Soft Circuit Breaker*
 *   yang hanya membuka cooldown singkat (`8s`) setelah 3 kegagalan transien beruntun agar satu
 *   gangguan sesaat tidak membungkam seluruh kalimat berikutnya.
 */
class ElevenLabsTtsClient(
    private val baseUrl: String = EnvConfig.elevenLabsBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(EnvConfig.elevenLabsConnectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(EnvConfig.elevenLabsReadTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(EnvConfig.elevenLabsConnectTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(EnvConfig.elevenLabsCallTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val transientFailureThreshold: Int = 3,
    private val softCooldownMs: Long = 8_000L,
    private val retryBackoffMs: List<Long> = listOf(250L, 650L),
    private val jitterMaxMs: Long = 100L
) {
    companion object {
        private const val TAG = "ElevenLabsTtsClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_OUTPUT_FORMAT = "mp3_44100_64"

        internal fun isQuotaExhausted429(errorBody: String): Boolean {
            val lower = errorBody.lowercase()
            return lower.contains("quota_exceeded") ||
                lower.contains("character_limit") ||
                lower.contains("insufficient_credits")
        }
    }

    private val circuitLock = Any()
    private val tempFileSeq = AtomicLong(0L)
    private val consecutiveTransientFailures = AtomicInteger(0)

    @Volatile
    private var circuitOpenUntilMs: Long = 0L

    @Volatile
    private var lastCircuitReason: String? = null

    @Volatile
    var lastStatusSummary: String = "Siap digunakan"
        private set

    val currentTransientFailureCount: Int
        get() = consecutiveTransientFailures.get()

    val isCircuitOpen: Boolean
        get() = synchronized(circuitLock) {
            clockMs() < circuitOpenUntilMs
        }

    fun resetCircuitBreaker() {
        synchronized(circuitLock) {
            circuitOpenUntilMs = 0L
            lastCircuitReason = null
            consecutiveTransientFailures.set(0)
        }
    }

    private fun openCircuit(durationMs: Long, reason: String) {
        synchronized(circuitLock) {
            val until = clockMs() + durationMs
            if (until > circuitOpenUntilMs) {
                circuitOpenUntilMs = until
            }
            lastCircuitReason = reason
            lastStatusSummary = "Fallback TTS (${durationMs / 1000}s): $reason"
        }
        GepyLog.w(TAG, "Circuit breaker ElevenLabs terbuka (${durationMs / 1000}s): $reason")
    }

    private fun recordSuccess(bytesWritten: Long) {
        synchronized(circuitLock) {
            consecutiveTransientFailures.set(0)
            circuitOpenUntilMs = 0L
            lastCircuitReason = null
            lastStatusSummary = "Aktif • Sukses (${bytesWritten / 1024} KB)"
        }
    }

    private fun recordTransientFailure(reason: String): Boolean {
        synchronized(circuitLock) {
            val count = consecutiveTransientFailures.incrementAndGet()
            val threshold = transientFailureThreshold.coerceAtLeast(1)
            if (count >= threshold) {
                val until = clockMs() + softCooldownMs
                if (until > circuitOpenUntilMs) {
                    circuitOpenUntilMs = until
                }
                lastCircuitReason = reason
                lastStatusSummary = "Fallback TTS (${softCooldownMs / 1000}s): $reason"
                GepyLog.w(TAG, "Soft cooldown ElevenLabs terbuka setelah $count kegagalan seluler beruntun (${softCooldownMs / 1000}s): $reason")
                return true
            }
            lastStatusSummary = "Gangguan transien ($count/$threshold): $reason"
            return false
        }
    }

    private fun backoffDelayWithJitter(attemptIndex: Int): Long {
        val base = retryBackoffMs.getOrElse(attemptIndex) { retryBackoffMs.lastOrNull() ?: 250L }
        val jitter = if (jitterMaxMs > 0L) Random.nextLong(0L, jitterMaxMs + 1L) else 0L
        return base + jitter
    }

    /**
     * Mensintesis [text] menggunakan suara [voiceId] dan menyimpan stream MP3 langsung ke [destinationFile].
     */
    suspend fun synthesizeToFile(
        apiKey: String,
        voiceId: String,
        text: String,
        destinationFile: File,
        modelId: String = EnvConfig.elevenLabsModel,
        languageCode: String = "id",
        stability: Float = 0.45f,
        similarityBoost: Float = 0.75f,
        outputFormat: String = DEFAULT_OUTPUT_FORMAT,
        bypassCircuitBreaker: Boolean = false
    ): ElevenLabsSynthResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanVoice = voiceId.trim()
        val cleanText = text.trim()

        if (cleanKey.isEmpty() || cleanVoice.isEmpty()) {
            return@withContext ElevenLabsSynthResult.Failure("API Key atau Voice ID ElevenLabs belum diatur.")
        }
        if (cleanText.isEmpty()) {
            return@withContext ElevenLabsSynthResult.Failure("Teks sintesis kosong.")
        }
        if (!bypassCircuitBreaker && isCircuitOpen) {
            return@withContext ElevenLabsSynthResult.Failure(
                reason = "ElevenLabs sementara dilewati (${lastCircuitReason ?: "circuit open"}).",
                circuitOpened = true
            )
        }

        val normalizedBase = baseUrl.trimEnd('/')
        val url = "$normalizedBase/text-to-speech/$cleanVoice?output_format=$outputFormat"

        val payloadJson = JSONObject().apply {
            put("text", cleanText)
            put("model_id", modelId.ifBlank { EnvConfig.elevenLabsModel })
            if (languageCode.isNotBlank()) {
                put("language_code", languageCode.trim())
            }
            put(
                "voice_settings",
                JSONObject()
                    .put("stability", stability.coerceIn(0f, 1f).toDouble())
                    .put("similarity_boost", similarityBoost.coerceIn(0f, 1f).toDouble())
            )
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", cleanKey)
            .header("Accept", "audio/mpeg")
            .header("Content-Type", "application/json")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        destinationFile.parentFile?.mkdirs()
        val tempFile = File(
            destinationFile.parentFile,
            "${destinationFile.name}.${tempFileSeq.incrementAndGet()}.${System.nanoTime()}.tmp"
        )

        val maxAttempts = (1 + retryBackoffMs.size).coerceAtLeast(1)
        for (attempt in 0 until maxAttempts) {
            currentCoroutineContext().ensureActive()
            if (attempt > 0 && !bypassCircuitBreaker && isCircuitOpen) {
                tempFile.delete()
                return@withContext ElevenLabsSynthResult.Failure(
                    reason = "ElevenLabs sementara dilewati (${lastCircuitReason ?: "circuit open"}).",
                    circuitOpened = true
                )
            }

            val call = client.newCall(request)
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    call.cancel()
                }
            }

            var shouldRetryAfterDelayMs: Long? = null
            try {
                call.execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful) {
                        val errSnippet = try {
                            response.body?.string()?.take(200).orEmpty()
                        } catch (_: Throwable) {
                            ""
                        }
                        val reason = "HTTP $code dari ElevenLabs${if (errSnippet.isNotBlank()) ": $errSnippet" else ""}"

                        if (code == 429 && isQuotaExhausted429(errSnippet)) {
                            if (attempt == 0 && retryBackoffMs.isNotEmpty()) {
                                GepyLog.w(TAG, "HTTP 429 quota_exceeded dari ElevenLabs, mencoba ulang 1x...")
                                shouldRetryAfterDelayMs = backoffDelayWithJitter(0)
                                return@use
                            }
                            if (!bypassCircuitBreaker) {
                                openCircuit(20_000L, reason)
                            }
                            return@withContext ElevenLabsSynthResult.Failure(
                                reason = reason,
                                httpStatus = code,
                                circuitOpened = !bypassCircuitBreaker,
                                transient = false
                            )
                        }

                        val isTransientHttp = (code == 429 || code in 500..599)
                        if (isTransientHttp) {
                            if (attempt < maxAttempts - 1) {
                                GepyLog.w(TAG, "HTTP $code transien dari ElevenLabs, menjadwalkan retry #${attempt + 1}...")
                                shouldRetryAfterDelayMs = backoffDelayWithJitter(attempt)
                                return@use
                            }
                            val opened = if (bypassCircuitBreaker) false else recordTransientFailure(reason)
                            return@withContext ElevenLabsSynthResult.Failure(
                                reason = reason,
                                httpStatus = code,
                                circuitOpened = opened,
                                transient = true
                            )
                        }

                        val backoffMs = when (code) {
                            401, 403 -> 120_000L
                            402 -> 90_000L
                            else -> 15_000L
                        }
                        if (!bypassCircuitBreaker) {
                            openCircuit(backoffMs, reason)
                        }
                        return@withContext ElevenLabsSynthResult.Failure(
                            reason = reason,
                            httpStatus = code,
                            circuitOpened = !bypassCircuitBreaker,
                            transient = false
                        )
                    }

                    val body = response.body
                        ?: return@withContext ElevenLabsSynthResult.Failure("Body respons ElevenLabs kosong.", httpStatus = code)

                    var totalBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                if (read > 0) {
                                    output.write(buffer, 0, read)
                                    totalBytes += read
                                }
                            }
                            output.flush()
                        }
                    }

                    if (totalBytes <= 0L || !tempFile.exists()) {
                        tempFile.delete()
                        val opened = if (bypassCircuitBreaker) false else recordTransientFailure("Stream MP3 dari ElevenLabs 0 byte.")
                        return@withContext ElevenLabsSynthResult.Failure(
                            reason = "Stream MP3 dari ElevenLabs 0 byte.",
                            httpStatus = code,
                            circuitOpened = opened,
                            transient = true
                        )
                    }

                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                    if (!tempFile.renameTo(destinationFile)) {
                        tempFile.copyTo(destinationFile, overwrite = true)
                        tempFile.delete()
                    }

                    if (!bypassCircuitBreaker) {
                        recordSuccess(totalBytes)
                    }
                    return@withContext ElevenLabsSynthResult.Success(file = destinationFile, bytesWritten = totalBytes)
                }
            } catch (ce: CancellationException) {
                tempFile.delete()
                throw ce
            } catch (ioe: IOException) {
                tempFile.delete()
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Sintesis ElevenLabs dibatalkan.", ioe)
                }
                if (attempt < maxAttempts - 1) {
                    GepyLog.w(TAG, "Gangguan jaringan transien ElevenLabs (${ioe.message}), menjadwalkan retry #${attempt + 1}...")
                    shouldRetryAfterDelayMs = backoffDelayWithJitter(attempt)
                } else {
                    val reason = "Gangguan jaringan ElevenLabs: ${ioe.message ?: "IOException"}"
                    val opened = if (bypassCircuitBreaker) false else recordTransientFailure(reason)
                    return@withContext ElevenLabsSynthResult.Failure(
                        reason = reason,
                        circuitOpened = opened,
                        transient = true
                    )
                }
            } catch (t: Throwable) {
                tempFile.delete()
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Sintesis ElevenLabs dibatalkan.", t)
                }
                val reason = "Kesalahan sintesis ElevenLabs: ${t.message ?: t.javaClass.simpleName}"
                if (!bypassCircuitBreaker) {
                    lastStatusSummary = reason
                }
                return@withContext ElevenLabsSynthResult.Failure(reason = reason)
            } finally {
                cancelHandle?.dispose()
            }

            val retryDelayMs = shouldRetryAfterDelayMs
            if (retryDelayMs != null) {
                delay(retryDelayMs)
            }
        }
        return@withContext ElevenLabsSynthResult.Failure(
            reason = "Gagal menyintesis suara ElevenLabs setelah percobaan ulang.",
            transient = true
        )
    }

    /**
     * Mengambil sisa kuota credit karakter dari `GET /v1/user/subscription`.
     */
    suspend fun fetchSubscriptionQuota(apiKey: String): Result<ElevenLabsSubscriptionInfo> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("API Key ElevenLabs belum diisi."))
        }
        val url = "${baseUrl.trimEnd('/')}/user/subscription"
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", cleanKey)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: ${rawBody.take(160)}")
                    )
                }
                val json = JSONObject(rawBody)
                val count = json.optInt("character_count", 0)
                val limit = json.optInt("character_limit", 0)
                val tier = json.optString("tier", "free").ifBlank { "free" }
                Result.success(
                    ElevenLabsSubscriptionInfo(
                        characterCount = count,
                        characterLimit = limit,
                        tier = tier
                    )
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
