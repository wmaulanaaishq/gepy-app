package com.teknologigawai.gepy.features.voice

import com.teknologigawai.gepy.core.config.GeminiKeyPool
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Hasil penemuan model `bidiGenerateContent` aktif pada Google AI Studio API Key pengguna. */
internal sealed class LiveModelDiscoveryResult {
    data class Success(val models: List<String>) : LiveModelDiscoveryResult()
    data class InvalidApiKey(val message: String) : LiveModelDiscoveryResult()
    data class RateLimited(val message: String) : LiveModelDiscoveryResult()
    data object Unavailable : LiveModelDiscoveryResult()
}

/**
 * Penemu model WebSocket `bidiGenerateContent` dengan *cache* 10 menit agar
 * sambungan ulang (*auto-reconnect*) berlangsung instan (`< 400 ms`).
 */
internal class GeminiLiveModelDiscovery {

    private val discoveryHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private var cachedDiscoveryKey: String = ""
    private var cachedDiscoveryAtMs: Long = 0L
    private var cachedDiscoveredModels: List<String> = emptyList()

    suspend fun discoverSupportedBidiModels(
        apiKey: String,
        forceRefresh: Boolean = false
    ): LiveModelDiscoveryResult = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        if (!forceRefresh &&
            cachedDiscoveryKey == apiKey &&
            cachedDiscoveredModels.isNotEmpty() &&
            nowMs - cachedDiscoveryAtMs < 10 * 60_000L
        ) {
            return@withContext LiveModelDiscoveryResult.Success(cachedDiscoveredModels)
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        try {
            discoveryHttpClient.newCall(req).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (GeminiKeyPool.isQuotaOrRateLimitResponse(resp.code, rawBody)) {
                    return@withContext LiveModelDiscoveryResult.RateLimited(
                        "Kuota Gemini Live penuh pada kunci aktif (HTTP ${resp.code})."
                    )
                }
                if (resp.code == 400 || resp.code == 401 || resp.code == 403) {
                    val detail = try {
                        JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    val reason = if (detail.isNotBlank()) {
                        "API Key Gemini ditolak oleh Google AI Studio (HTTP ${resp.code}): $detail"
                    } else {
                        "API Key Gemini tidak valid atau belum aktif di Google AI Studio (HTTP ${resp.code})."
                    }
                    return@withContext LiveModelDiscoveryResult.InvalidApiKey(reason)
                }
                if (!resp.isSuccessful) {
                    return@withContext LiveModelDiscoveryResult.Unavailable
                }
                val root = JSONObject(rawBody)
                val modelsArr = root.optJSONArray("models") ?: return@withContext LiveModelDiscoveryResult.Unavailable
                val bidiModels = mutableListOf<String>()
                for (i in 0 until modelsArr.length()) {
                    val m = modelsArr.optJSONObject(i) ?: continue
                    val name = m.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var supportsBidi = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j).equals("bidiGenerateContent", ignoreCase = true)) {
                            supportsBidi = true
                            break
                        }
                    }
                    if (supportsBidi) {
                        bidiModels.add(name)
                    }
                }
                val conversationalBidi = bidiModels.filterNot {
                    it.contains("transcribe", ignoreCase = true) || it.contains("translate", ignoreCase = true)
                }
                val resolved = conversationalBidi.ifEmpty { bidiModels }
                if (resolved.isNotEmpty()) {
                    cachedDiscoveryKey = apiKey
                    cachedDiscoveryAtMs = nowMs
                    cachedDiscoveredModels = resolved
                }
                LiveModelDiscoveryResult.Success(resolved)
            }
        } catch (_: Throwable) {
            LiveModelDiscoveryResult.Unavailable
        }
    }

    fun buildCandidateModelOrder(
        primaryModel: String,
        preferredLiveModels: List<String>,
        discovered: List<String>
    ): List<String> {
        val normalizedPrimary = if (primaryModel.startsWith("models/")) primaryModel else "models/$primaryModel"
        if (discovered.isEmpty()) {
            return (listOf(normalizedPrimary) + preferredLiveModels).distinct()
        }
        val ranked = mutableListOf<String>()
        if (discovered.any { it.equals(normalizedPrimary, ignoreCase = true) }) {
            ranked.add(normalizedPrimary)
        }
        for (pref in preferredLiveModels) {
            val match = discovered.firstOrNull { it.equals(pref, ignoreCase = true) }
            if (match != null && !ranked.contains(match)) {
                ranked.add(match)
            }
        }
        for (disc in discovered) {
            if (!ranked.contains(disc) &&
                (disc.contains("live", ignoreCase = true) || disc.contains("native-audio", ignoreCase = true))
            ) {
                ranked.add(disc)
            }
        }
        for (disc in discovered) {
            if (!ranked.contains(disc)) {
                ranked.add(disc)
            }
        }
        if (!ranked.contains(normalizedPrimary)) {
            ranked.add(0, normalizedPrimary)
        }
        return ranked.distinct().take(4)
    }
}
