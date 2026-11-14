package com.teknologigawai.gepy.core.web

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mesin **Gemini Google Search Grounding** dan sintesis multi-provider (**Gemini Flash Lite -> Fireworks**)
 * anti-halusinasi berbasis sitasi sumber kredibel.
 */
internal class WebSearchLlmSynthesizer(
    private val httpTransport: WebHttpTransport,
    private val onTripCircuitBreaker: () -> Unit
) {
    suspend fun callDirectGeminiGrounding(
        cleanQuery: String,
        modelName: String,
        geminiKeys: List<String>
    ): WebSearchResult? {
        if (geminiKeys.isEmpty()) return null
        val todayIso = try {
            java.time.LocalDate.now().toString()
        } catch (_: Throwable) {
            "2026"
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
        val requestBody = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Anda adalah Gepy, Personal AI Agent suara berbahasa Indonesia. " +
                                "Tanggal hari ini: $todayIso. " +
                                "Jawab pertanyaan pengguna secara faktual, akurat, dan terkini berdasarkan hasil Google Search dari sumber resmi (.go.id, BMKG, BI, OJK) atau pers terverifikasi (Antara, Kompas, Tempo, Detik, CNN Indonesia). Patuhi batasan jumlah atau panjang jawaban dari pengguna ('satu saja', 'singkat'). " +
                                "WAJIB sebutkan nama sumber berita/lembaga resmi serta waktu rilis/terbit beritanya secara singkat di dalam jawaban (contoh: 'Menurut Antara News hari ini...' atau 'Berdasarkan berita yang dirilis kemarin'). " +
                                "DILARANG KERAS menebak angka, kurs, atau fakta yang tidak ditemukan. " +
                                "Gunakan Bahasa Indonesia yang alami, rapi, dan mudah dipindai (pisahkan baris secara bersih bila ada lebih dari satu poin). " +
                                "DILARANG menggunakan simbol bintang markdown (**), header #, atau URL mentah."
                        )
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("maxOutputTokens", 350)
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", cleanQuery)))
                )
            )
            put(
                "tools",
                JSONArray().put(
                    JSONObject().put("google_search", JSONObject())
                )
            )
        }

        var quotaFailures = 0
        for (activeKey in geminiKeys) {
            val response = httpTransport.postJson(
                url = url,
                headers = mapOf(
                    "x-goog-api-key" to activeKey,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                jsonBody = requestBody.toString()
            )

            if (GeminiKeyPool.isQuotaOrRateLimitResponse(response.statusCode, response.body)) {
                GepyLog.w(WebSearchAndReaderBridge.TAG, "Direct Grounding 429/kuota penuh pada key aktif, mencoba kunci cadangan...")
                GeminiKeyPool.markQuotaExhausted(activeKey)
                quotaFailures++
                continue
            }
            if (GeminiKeyPool.isKeyRejectedResponse(response.statusCode)) {
                GeminiKeyPool.markQuotaExhausted(activeKey, GeminiKeyPool.INVALID_KEY_COOLDOWN_MS)
                continue
            }
            if (response.statusCode !in 200..299) {
                return null
            }

            val parsed = parseGeminiGroundingResponse(cleanQuery, response.body)
            if (parsed.success) return parsed
            return null
        }

        if (quotaFailures >= geminiKeys.size) {
            onTripCircuitBreaker()
        }
        return null
    }

    suspend fun synthesizeSnippetsWithLlm(
        query: String,
        snippets: List<WebSearchSnippet>,
        geminiKeys: List<String>,
        fireworksApiKey: String
    ): String? {
        val todayIso = try {
            java.time.LocalDate.now().toString()
        } catch (_: Throwable) {
            "2026"
        }

        val contextBlock = if (snippets.isNotEmpty()) {
            snippets.mapIndexed { idx, s ->
                val tierLabel = s.credibilityTier.labelId
                val datePart = if (s.publishedDate.isNotBlank()) " • Waktu Rilis: ${PromptInjectionSanitizer.sanitizeUiText(s.publishedDate, 40)}" else ""
                val baseLine = "${idx + 1}. [$tierLabel: ${PromptInjectionSanitizer.sanitizeUiText(s.source, 80)}$datePart] ${PromptInjectionSanitizer.sanitizeUiText(s.title, 200)}" +
                    if (s.snippet.isNotBlank()) " — ${PromptInjectionSanitizer.sanitizeUiText(s.snippet, 320)}" else ""
                if (s.deepLeadExcerpt.isNotBlank()) {
                    "$baseLine\n   [Kutipan Fakta Utama ${PromptInjectionSanitizer.sanitizeUiText(s.source, 80)}]: ${PromptInjectionSanitizer.sanitizeUiText(s.deepLeadExcerpt, 480)}"
                } else {
                    baseLine
                }
            }.joinToString("\n")
        } else {
            "(Tidak ada kutipan berita terkini. Gunakan hanya fakta dasar yang sudah pasti kebenarannya; jika menyangkut angka/kurs/berita hari ini yang tidak diketahui, katakan secara jujur bahwa data belum tersedia)"
        }

        val systemPrompt =
            "Anda adalah Gepy, Personal AI Agent suara berbahasa Indonesia. " +
                "Tanggal hari ini: $todayIso. " +
                "ATURAN KREDIBILITAS & ANTI-HALUSINASI MUTLAK:\n" +
                "1. Jawab HANYA berdasarkan [FAKTA TERVERIFIKASI] yang diberikan di bawah.\n" +
                "2. WAJIB sebutkan sumber berita atau lembaga resmi secara alami di dalam kalimat (contoh: 'Menurut laporan ANTARA News...' atau 'Berdasarkan data resmi BMKG...').\n" +
                "3. WAJIB sebutkan kapan berita atau peristiwa tersebut dirilis/terjadi jika informasi waktu rilis tersedia (contoh: 'dirilis hari ini, 7 November...', 'berita kemarin', atau '2 jam yang lalu') agar pengguna langsung mengetahui kebaruan informasinya.\n" +
                "4. DILARANG KERAS mengarang angka, kurs, harga, skor, tanggal, atau peristiwa yang tidak tertulis di dalam fakta.\n" +
                "5. Gunakan Bahasa Indonesia yang hangat, rapi, dan jelas (2-4 kalimat padat atau daftar bernomor bersih jika menjawab beberapa item; patuhi batasan jumlah atau keringkasan dari pengguna). " +
                "DILARANG menggunakan format markdown yang sulit dibaca suara, simbol bintang ganda berlebihan, atau URL mentah."

        val userPrompt =
            "Pertanyaan pengguna: $query\n\n[FAKTA TERVERIFIKASI DARI SUMBER KREDIBEL]:\n$contextBlock\n\n" +
                "Berikan jawaban Bahasa Indonesia yang jelas dan komprehensif (2-4 kalimat padat beserta sitasi sumbernya dan waktu rilis):"

        // 1. Coba sintesis via Gemini Flash Lite (Key 1 -> Failover Key 2 bila HTTP 429)
        if (geminiKeys.isNotEmpty()) {
            val payload = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt))
                    )
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.0)
                        .put("maxOutputTokens", 350)
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                    )
                )
            }
            val payloadStr = payload.toString()

            keyLoop@ for (activeKey in geminiKeys) {
                for (modelName in WebSearchAndReaderBridge.GEMINI_SYNTHESIS_MODELS) {
                    try {
                        var hitKeyQuota = false
                        var hitKeyRejected = false
                        var hitFatalError = false
                        val spoken = withTimeoutOrNull(2_200L) {
                            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
                            val resp = httpTransport.postJson(
                                url = url,
                                headers = mapOf(
                                    "x-goog-api-key" to activeKey,
                                    "Content-Type" to "application/json",
                                    "Accept" to "application/json"
                                ),
                                jsonBody = payloadStr
                            )
                            if (resp.statusCode in 200..299) {
                                val parsed = parseGeminiGroundingResponse(query, resp.body)
                                if (parsed.success && parsed.spokenAnswer.isNotBlank()) {
                                    parsed.spokenAnswer
                                } else null
                            } else if (GeminiKeyPool.isQuotaOrRateLimitResponse(resp.statusCode, resp.body)) {
                                hitKeyQuota = true
                                GeminiKeyPool.markQuotaExhausted(activeKey)
                                null
                            } else if (GeminiKeyPool.isKeyRejectedResponse(resp.statusCode)) {
                                hitKeyRejected = true
                                GeminiKeyPool.markQuotaExhausted(activeKey, GeminiKeyPool.INVALID_KEY_COOLDOWN_MS)
                                null
                            } else {
                                if (resp.statusCode != 404) {
                                    hitFatalError = true
                                }
                                null
                            }
                        }
                        if (!spoken.isNullOrBlank()) {
                            return spoken
                        }
                        if (hitKeyQuota || hitKeyRejected) {
                            break
                        }
                        if (hitFatalError) {
                            break@keyLoop
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        // 2. Fallback sintesis via Fireworks AI (deepseek-v4p1-flash, temperature = 0.0, max_tokens = 350)
        if (fireworksApiKey.isNotEmpty()) {
            try {
                val fireworksSpoken = withTimeoutOrNull(2_000L) {
                    val payload = JSONObject().apply {
                        put("model", EnvConfig.fireworksModel)
                        put("temperature", 0.0)
                        put("max_tokens", 350)
                        put("reasoning_effort", "none")
                        put(
                            "messages",
                            JSONArray()
                                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                                .put(JSONObject().put("role", "user").put("content", userPrompt))
                        )
                    }
                    val resp = httpTransport.postJson(
                        url = EnvConfig.fireworksChatCompletionsUrl,
                        headers = mapOf(
                            "Authorization" to "Bearer $fireworksApiKey",
                            "Content-Type" to "application/json",
                            "Accept" to "application/json"
                        ),
                        jsonBody = payload.toString()
                    )
                    if (resp.statusCode in 200..299) {
                        val json = JSONObject(resp.body)
                        val content = json.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                            ?.trim()
                            .orEmpty()
                        if (content.isNotBlank()) {
                            WebSearchAndReaderBridge.cleanForSpeech(content)
                        } else null
                    } else null
                }
                if (!fireworksSpoken.isNullOrBlank()) {
                    return fireworksSpoken
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }

        return null
    }

    internal fun parseGeminiGroundingResponse(query: String, rawJson: String): WebSearchResult {
        val root = JSONObject(rawJson)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return WebSearchResult(
                success = false,
                query = query,
                spokenAnswer = "Maaf, tidak ditemukan hasil pencarian untuk pertanyaan tersebut.",
                errorMessage = "NO_CANDIDATES"
            )
        }

        val first = candidates.optJSONObject(0) ?: JSONObject()
        val parts = first.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                if (partText.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(partText.trim())
                }
            }
        }

        val sources = mutableListOf<String>()
        val groundingMeta = first.optJSONObject("groundingMetadata")
        val chunks = groundingMeta?.optJSONArray("groundingChunks")
        if (chunks != null) {
            for (i in 0 until chunks.length()) {
                val webObj = chunks.optJSONObject(i)?.optJSONObject("web")
                val title = webObj?.optString("title", "")?.trim().orEmpty()
                val uri = webObj?.optString("uri", "")?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    val eval = DomainAuthorityFilter.evaluateSource(
                        urlOrDomain = uri.ifBlank { title },
                        publisherName = title
                    )
                    if (!eval.isBlocked) {
                        val label = title.ifBlank { eval.canonicalPublisher }
                        if (label.isNotEmpty() && !sources.contains(label)) {
                            sources.add(label)
                        }
                    }
                }
            }
        }

        val cleanedAnswer = WebSearchAndReaderBridge.cleanForSpeech(sb.toString())
        if (cleanedAnswer.isEmpty()) {
            return WebSearchResult(
                success = false,
                query = query,
                spokenAnswer = "Maaf, informasi belum ditemukan.",
                errorMessage = "EMPTY_ANSWER"
            )
        }

        return WebSearchResult(
            success = true,
            query = query,
            spokenAnswer = cleanedAnswer,
            sources = sources.take(5)
        )
    }
}
