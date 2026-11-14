package com.teknologigawai.gepy.core.web

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.config.GeminiKeyPurpose
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import com.teknologigawai.gepy.core.skills.WebSearchFastPathSkill
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Jembatan **Credible Agentic Web Intelligence & Lean Context Engineering** untuk **Gepy Native**:
 * 1. **Zero-Token Domain Authority Filter ([DomainAuthorityFilter])**:
 *    Memprioritaskan sumber resmi pemerintah (`.go.id`, BMKG, BI, OJK, BPS) dan pers nasional
 *    terverifikasi Dewan Pers (Antara, Kompas, Tempo, CNN Indonesia, Detik, Bisnis, Kontan),
 *    serta memblokir blog pribadi (`blogspot`, `wordpress`, `kompasiana`) dan forum tidak valid.
 * 2. **Smart In-Memory LRU + TTL Cache (`20 Menit`)**:
 *    Menyimpan hingga 64 hasil kueri terverifikasi di memori agar pertanyaan berulang dijawab
 *    dalam `< 5 ms` dengan **`0 Token`**.
 * 3. **Autonomous 2-Hop Lead-Paragraph Deep Reader**:
 *    Mengambil 2–3 paragraf pembuka (*Lead Paragraphs / Piramida Terbalik*, maks `1.200` karakter)
 *    dari artikel otoritas tertinggi menggunakan Jina Reader (`https://r.jina.ai/<url>`) ketika
 *    ringkasan RSS terlalu pendek.
 * 4. **Strict Anti-Hallucination Synthesizer (`temperature = 0.0`, `maxOutputTokens = 160`)**:
 *    Mewajibkan penyebutan sumber secara eksplisit dan melarang keras menebak angka di luar fakta.
 */
class WebSearchAndReaderBridge(
    private val gatewayBaseUrl: String = EnvConfig.gatewayBaseUrl,
    private val httpTransport: WebHttpTransport = OkHttpWebTransport(),
    private val sessionTokenProvider: () -> String? = { null },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val multiSourceParsers = MultiSourceWebSearchParsers(httpTransport) { url, body ->
        parseJinaResponse(url, body)
    }
    private val llmSynthesizer = WebSearchLlmSynthesizer(httpTransport) {
        tripGroundingCircuitBreaker()
    }
    private val developerEcosystemClient = DeveloperEcosystemWebClient(httpTransport)

    private data class CachedSearchEntry(
        val result: WebSearchResult,
        val timestampMs: Long
    )

    private val cacheLock = Any()
    private val searchCache = object : LinkedHashMap<String, CachedSearchEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSearchEntry>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    @Volatile
    private var groundQuotaExhaustedUntilEpochMs: Long = 0L

    /** Memeriksa apakah circuit breaker direct grounding sedang aktif karena limit kuota/429. */
    fun isGroundingCircuitBreakerActive(): Boolean {
        return clockMs() < groundQuotaExhaustedUntilEpochMs
    }

    /** Mereset circuit breaker agar grounding dapat dicoba kembali secara normal. */
    fun resetGroundingCircuitBreaker() {
        groundQuotaExhaustedUntilEpochMs = 0L
    }

    internal fun tripGroundingCircuitBreaker(durationMs: Long = DEFAULT_CIRCUIT_BREAKER_DURATION_MS) {
        groundQuotaExhaustedUntilEpochMs = clockMs() + durationMs
        GepyLog.w(TAG, "Circuit breaker Grounding aktif selama ${durationMs / 60_000} mnt (Bypass langsung ke Ultra-Fast Parallel Retrieval).")
    }

    /** Menghapus seluruh cache pencarian web di memori. */
    fun clearCache() {
        synchronized(cacheLock) {
            searchCache.clear()
        }
    }

    private fun getCachedResult(cleanQuery: String): WebSearchResult? {
        if (cacheTtlMs <= 0L) return null
        val key = cleanQuery.lowercase().replace(Regex("""\s+"""), " ").trim()
        val now = clockMs()
        synchronized(cacheLock) {
            val entry = searchCache[key] ?: return null
            if (now - entry.timestampMs <= cacheTtlMs) {
                return entry.result
            }
            searchCache.remove(key)
            return null
        }
    }

    private fun putCachedResult(cleanQuery: String, result: WebSearchResult) {
        if (cacheTtlMs <= 0L || !result.success || result.spokenAnswer.isBlank()) return
        val key = cleanQuery.lowercase().replace(Regex("""\s+"""), " ").trim()
        val now = clockMs()
        synchronized(cacheLock) {
            searchCache[key] = CachedSearchEntry(result = result, timestampMs = now)
        }
    }

    companion object {
        internal const val TAG = "WebSearchBridge"
        private const val MAX_MARKDOWN_CHARS = 8_000
        internal const val MAX_LEAD_PARAGRAPH_CHARS = 1_200
        private const val DEFAULT_CACHE_TTL_MS = 20 * 60_000L
        private const val DEFAULT_CIRCUIT_BREAKER_DURATION_MS = 30 * 60_000L
        private const val MAX_CACHE_ENTRIES = 64

        internal val GEMINI_SYNTHESIS_MODELS = listOf(
            "gemini-3.1-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash"
        )

        val INDO_MONTHS = arrayOf(
            "", "Januari", "Februari", "Maret", "April", "Mei", "Juni",
            "Juli", "Agustus", "September", "Oktober", "November", "Desember"
        )

        /**
         * Mengubah tanggal publikasi mentah (RFC 822, RFC 1123, ISO 8601) dari feed RSS menjadi
         * format Bahasa Indonesia lisan yang alami dan informatif untuk asisten suara personal
         * (contoh: "Hari ini, 7 November (14:30 WIB)", "2 jam yang lalu", "Kemarin, 6 November", "7 November 2026").
         */
        /**
         * Mengubah tanggal ISO-8601 (contoh: "2026-11-06T03:03:34Z") menjadi format tanggal lisan
         * Bahasa Indonesia alami yang ringkas (contoh: "6 November 2026").
         */
        fun formatIsoDateToIndonesian(iso: String?): String {
            if (iso.isNullOrBlank()) return ""
            return try {
                val clean = iso.trim()
                val datePart = clean.substringBefore("T")
                val parts = datePart.split("-")
                if (parts.size == 3) {
                    val year = parts[0]
                    val month = INDO_MONTHS.getOrElse(parts[1].toIntOrNull() ?: 0) { parts[1] }
                    val day = parts[2].toIntOrNull()?.toString() ?: parts[2]
                    "$day $month $year"
                } else {
                    formatFriendlyIndonesianDate(clean).ifBlank { clean.take(10) }
                }
            } catch (_: Exception) {
                iso.orEmpty().take(10)
            }
        }

        fun formatFriendlyIndonesianDate(rawPubDate: String): String {
            val trimmed = rawPubDate.trim()
            if (trimmed.isEmpty()) return ""

            val parsedZdt: java.time.ZonedDateTime? = tryParsePubDate(trimmed)
            if (parsedZdt != null) {
                val nowZone = java.time.ZoneId.of("Asia/Jakarta")
                val articleZdt = parsedZdt.withZoneSameInstant(nowZone)
                val nowZdt = java.time.ZonedDateTime.now(nowZone)

                val duration = java.time.Duration.between(articleZdt, nowZdt)
                val diffMinutes = duration.toMinutes()
                val diffHours = duration.toHours()

                val monthName = INDO_MONTHS.getOrElse(articleZdt.monthValue) { "" }
                val timeStr = String.format(java.util.Locale.ROOT, "%02d:%02d WIB", articleZdt.hour, articleZdt.minute)

                val articleDate = articleZdt.toLocalDate()
                val nowDate = nowZdt.toLocalDate()

                return when {
                    articleDate == nowDate -> {
                        when {
                            diffMinutes in 0..2 -> "Baru saja dirilis"
                            diffMinutes in 3..59 -> "$diffMinutes menit yang lalu"
                            diffHours in 1..11 -> "$diffHours jam yang lalu (hari ini, $timeStr)"
                            else -> "Hari ini, ${articleZdt.dayOfMonth} $monthName ($timeStr)"
                        }
                    }
                    articleDate == nowDate.minusDays(1) -> {
                        "Kemarin, ${articleZdt.dayOfMonth} $monthName ($timeStr)"
                    }
                    articleZdt.year == nowZdt.year -> {
                        "${articleZdt.dayOfMonth} $monthName ($timeStr)"
                    }
                    else -> {
                        "${articleZdt.dayOfMonth} $monthName ${articleZdt.year}"
                    }
                }
            }

            return cleanForSpeech(trimmed)
        }

        private fun tryParsePubDate(rawDate: String): java.time.ZonedDateTime? {
            val clean = rawDate.trim()
            if (clean.isEmpty()) return null

            try {
                return java.time.ZonedDateTime.parse(clean, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            } catch (_: Throwable) {
            }

            try {
                return java.time.ZonedDateTime.parse(clean, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
            } catch (_: Throwable) {
            }
            try {
                return java.time.OffsetDateTime.parse(clean, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toZonedDateTime()
            } catch (_: Throwable) {
            }

            val customPatterns = listOf(
                "EEE, d MMM yyyy HH:mm:ss z",
                "EEE, dd MMM yyyy HH:mm:ss z",
                "EEE, d MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "d MMM yyyy HH:mm:ss z",
                "dd MMM yyyy HH:mm:ss z",
                "d MMM yyyy HH:mm:ss Z",
                "dd MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            )
            for (pattern in customPatterns) {
                try {
                    val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US)
                    return java.time.ZonedDateTime.parse(clean, formatter)
                } catch (_: Throwable) {
                    try {
                        val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US)
                        val offset = java.time.OffsetDateTime.parse(clean, formatter)
                        return offset.toZonedDateTime()
                    } catch (_: Throwable) {
                    }
                }
            }

            return null
        }

        /** Membersihkan simbol markdown dan entitas HTML agar nyaman dibaca di Chat maupun dibacakan TTS. */
        fun cleanForSpeech(raw: String): String {
            return raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
                .replace(Regex("""\*([^*]+)\*"""), "$1")
                .replace(Regex("""#{1,6}\s*"""), "")
                .replace(Regex("""`([^`]+)`"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace(Regex("""[ \t]+"""), " ")
                .replace(Regex(""" *\n *"""), "\n")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        }

        /** Memotong teks panjang tepat pada batas kata/kalimat agar tidak terpotong di tengah kata. */
        internal fun truncateAtWordBoundary(raw: String, maxChars: Int): String {
            val clean = raw.replace(Regex("""[ \t\r\n]+"""), " ").trim()
            if (clean.length <= maxChars) return clean
            val slice = clean.take(maxChars)
            val lastSpace = slice.lastIndexOf(' ')
            val cut = if (lastSpace >= (maxChars * 0.45).toInt()) {
                slice.substring(0, lastSpace)
            } else {
                slice
            }
            return cut.trimEnd(' ', ',', ';', ':', '-', '(', '[').ifEmpty { slice.trimEnd() } + "..."
        }

        /** Mengekstrak jumlah item yang diminta pengguna ("satu saja", "top 3", dll.). */
        internal fun extractRequestedItemLimit(query: String, defaultLimit: Int = 5): Int {
            val lower = query.lowercase()
            val explicitOne = Regex(
                """(?i)(?:\b(?:satu|1)\s+(?:saja|aja|doang|repo(?:sitory)?|projec?t|proyek|model|item|hasil|buah)\b|\b(?:cuma|hanya|cukup|pilih(?:kan)?|ambil(?:kan)?|kasih|berikan|tampilkan|sebut(?:kan)?|top)\s*(?:satu|1)\b|(?:\.|,|\s)\s*(?:satu|1)\s+(?:saja|aja)\b)"""
            )
            if (explicitOne.containsMatchIn(lower) || lower.trim() == "satu" || lower.trim() == "1") {
                return 1
            }
            val digitWordMap = listOf(
                2 to Regex("""(?i)\b(?:dua|2)\s+(?:saja|aja|doang|repo(?:sitory)?|projec?t|proyek|model|teratas|trending)\b|\b(?:top|cuma|hanya|cukup|kasih|sebut(?:kan)?)\s*(?:dua|2)\b"""),
                3 to Regex("""(?i)\b(?:tiga|3)\s+(?:saja|aja|doang|repo(?:sitory)?|projec?t|proyek|model|teratas|trending)\b|\b(?:top|cuma|hanya|cukup|kasih|sebut(?:kan)?)\s*(?:tiga|3)\b"""),
                4 to Regex("""(?i)\b(?:empat|4)\s+(?:saja|aja|doang|repo(?:sitory)?|projec?t|proyek|model|teratas|trending)\b|\b(?:top|cuma|hanya|cukup|kasih|sebut(?:kan)?)\s*(?:empat|4)\b"""),
                5 to Regex("""(?i)\b(?:lima|5)\s+(?:saja|aja|doang|repo(?:sitory)?|projec?t|proyek|model|teratas|trending)\b|\b(?:top|cuma|hanya|cukup|kasih|sebut(?:kan)?)\s*(?:lima|5)\b""")
            )
            for ((count, regex) in digitWordMap) {
                if (regex.containsMatchIn(lower)) return count
            }
            return if (isConciseQuery(lower)) minOf(defaultLimit, 3) else defaultLimit
        }

        internal fun isConciseQuery(query: String): Boolean {
            val lower = query.lowercase()
            return Regex("""(?i)\b(singkat|ringkas|pendek|intinya|secukupnya|brief|short|tldr|tl;dr)\b""")
                .containsMatchIn(lower)
        }

        /** Menghapus kata pembatas format ("satu saja", "singkat") sebelum dikirim sebagai kata kunci pencarian API. */
        internal fun stripFormatConstraintsFromSearchTerm(query: String): String {
            return query
                .replace(Regex("""(?i)\b(?:cuma|hanya|cukup)?\s*(?:satu|dua|tiga|empat|lima|\d+)\s*(?:saja|aja|doang)\b"""), " ")
                .replace(Regex("""(?i)\b(?:top)\s*\d+\b"""), " ")
                .replace(Regex("""(?i)\b(?:singkat|ringkas|pendek|brief|short|tldr)\b"""), " ")
                .replace(Regex("""[.,;]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        /** Menormalisasi URL agar selalu diawali `https://` atau `http://`. */
        fun normalizeUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }
    }

    /**
     * Melakukan pencarian fakta terkini di internet secara berlapis:
     * 1. Cloudflare Worker Gateway (`/api/web/search`) jika sesi aktif.
     * 2. Direct Gemini Google Search Grounding (`tools: [{"google_search": {}}]`).
     * 3. Multi-Source Real-Time Web Retrieval (**Bing Web Search RSS + Google News Indonesia RSS + Wikipedia ID**)
     *    yang disintesis menjadi Bahasa Indonesia lisan alami menggunakan **Gemini 3.1 Flash Lite**,
     *    **Fireworks AI**, atau pembacaan ringkasan langsung (*Zero-Quota-Error Fallback*).
     */
    suspend fun searchWebRealtime(
        query: String,
        geminiApiKey: String = EnvConfig.defaultGeminiApiKey,
        fireworksApiKey: String = EnvConfig.defaultFireworksApiKey,
        sessionToken: String? = null,
        geminiSecondaryApiKey: String = ""
    ): WebSearchResult {
        val rawTrimmed = query.trim()
        if (rawTrimmed.isEmpty()) {
            return WebSearchResult(
                success = false,
                query = "",
                spokenAnswer = "Pertanyaan pencarian web kosong.",
                errorMessage = "EMPTY_QUERY"
            )
        }

        val cleanQuery = WebSearchFastPathSkill.extractCleanSearchQuery(rawTrimmed)

        // Jalur 0 (Smart In-Memory TTL Cache — 0 Token & < 5 ms untuk kueri berulang dalam 20 menit)
        getCachedResult(cleanQuery)?.let { cached ->
            GepyLog.d(TAG, "Cache hit pencarian web (0 token): $cleanQuery")
            return cached
        }

        val cleanPrimaryKey = GeminiKeyPool.sanitizeKey(geminiApiKey.ifBlank { EnvConfig.defaultGeminiApiKey })
        val cleanSecondaryKey = GeminiKeyPool.sanitizeKey(geminiSecondaryApiKey)
        val geminiKeys = GeminiKeyPool.orderedKeys(
            purpose = GeminiKeyPurpose.WEB_GROUNDING,
            primaryKey = cleanPrimaryKey,
            secondaryKey = cleanSecondaryKey
        )
        val effectiveGeminiKey = geminiKeys.firstOrNull().orEmpty()
        val effectiveFireworksKey = fireworksApiKey.trim().ifEmpty { EnvConfig.defaultFireworksApiKey.trim() }

        val candidateModel = EnvConfig.geminiModel.ifBlank { "gemini-3.1-flash-lite" }

        // Eksekusi paralel cerdas v5.5.0:
        // Utamakan Google Search Grounding resmi sebagai jalur akurasi tinggi global bila API key tersedia.
        // Jalankan multi-source retrieval (Jina Search, DDG, Wiki, RSS) secara simultan sebagai fallback
        // tanpa jeda sekuensial (hemat 2-4 detik!).
        val (snippets, directGroundingResult) = coroutineScope {
            val groundingJob = if (geminiKeys.isNotEmpty() && !isGroundingCircuitBreakerActive()) {
                async {
                    try {
                        withTimeoutOrNull(2_800L) {
                            callDirectGeminiGrounding(cleanQuery, candidateModel, geminiKeys)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        GepyLog.w(TAG, "Direct Gemini Grounding ($candidateModel) dilewati: ${e.message}")
                        null
                    }
                }
            } else {
                null
            }

            val multiSourceJob = async {
                fetchMultiSourceWebSnippets(cleanQuery)
            }

            val gRes = groundingJob?.await()
            if (gRes != null && gRes.success) {
                // Grounding berhasil dengan indeks Google global
                multiSourceJob.cancel()
                Pair(emptyList(), gRes)
            } else {
                // Fallback ke multi-source retrieval jika grounding timeout/429/null
                val fetched = multiSourceJob.await()
                Pair(fetched, null)
            }
        }

        if (directGroundingResult != null && directGroundingResult.success) {
            putCachedResult(cleanQuery, directGroundingResult)
            return directGroundingResult
        }

        val resolvedSessionToken = sessionToken?.takeIf { it.isNotBlank() && it != "offline_session_token" }
            ?: sessionTokenProvider()?.takeIf { it.isNotBlank() && it != "offline_session_token" }

        // Fallback Gateway jika multi-source dan direct grounding kosong
        if (snippets.isEmpty() && !resolvedSessionToken.isNullOrBlank() && gatewayBaseUrl.isNotBlank()) {
            try {
                val gatewayRes = withTimeoutOrNull(2_500L) {
                    callGatewaySearch(cleanQuery, effectiveGeminiKey, resolvedSessionToken)
                }
                if (gatewayRes != null && gatewayRes.success) {
                    putCachedResult(cleanQuery, gatewayRes)
                    return gatewayRes
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                GepyLog.w(TAG, "Gateway search fallback ke sintesis LLM: ${e.message}")
            }
        }

        // Jalur 3 (Credible Agentic Grounding + Autonomous 2-Hop Lead-Paragraph Deep Reader):
        // Ambil hasil web real-time dari Bing RSS + Google News ID + Wikipedia ID, saring dengan DomainAuthorityFilter,
        // baca paragraf pembuka artikel #1 yang paling kredibel, lalu rangkum secara ketat (temperature = 0.0).
        return try {
            val sourcesList = snippets.map { it.source }.distinct().take(5)

            if (snippets.isNotEmpty()) {
                val synthesized = synthesizeSnippetsWithLlm(
                    query = cleanQuery,
                    snippets = snippets,
                    geminiKeys = geminiKeys,
                    fireworksApiKey = effectiveFireworksKey
                )
                if (!synthesized.isNullOrBlank()) {
                    val result = WebSearchResult(
                        success = true,
                        query = cleanQuery,
                        spokenAnswer = cleanForSpeech(synthesized),
                        sources = sourcesList
                    )
                    putCachedResult(cleanQuery, result)
                    return result
                }

                // Fallback Tanpa LLM: Bacakan langsung 2 hasil teratas yang sudah dibersihkan beserta sitasi sumbernya & waktu rilis
                val directSpoken = snippets.take(2).joinToString(". ") { item ->
                    val cleanTitle = cleanForSpeech(item.title)
                    val cleanDesc = cleanForSpeech(item.deepLeadExcerpt.ifBlank { item.snippet })
                    val datePrefix = if (item.publishedDate.isNotBlank()) " yang dirilis ${item.publishedDate}" else ""
                    val sourcePrefix = if (item.source.isNotBlank()) "Menurut ${item.source}$datePrefix, " else ""
                    if (cleanDesc.isNotEmpty() && !cleanDesc.equals(cleanTitle, ignoreCase = true)) {
                        "$sourcePrefix$cleanTitle: $cleanDesc"
                    } else {
                        "$sourcePrefix$cleanTitle"
                    }
                }
                val result = WebSearchResult(
                    success = true,
                    query = cleanQuery,
                    spokenAnswer = truncateAtWordBoundary(cleanForSpeech(directSpoken), 450),
                    sources = sourcesList
                )
                putCachedResult(cleanQuery, result)
                return result
            }

            // Jika feed RSS tidak menemukan snippet spesifik, jawab menggunakan pengetahuan LLM aktif dengan pengaman jujur
            val fallbackLlmAnswer = synthesizeSnippetsWithLlm(
                query = cleanQuery,
                snippets = emptyList(),
                geminiKeys = geminiKeys,
                fireworksApiKey = effectiveFireworksKey
            )
            if (!fallbackLlmAnswer.isNullOrBlank()) {
                val result = WebSearchResult(
                    success = true,
                    query = cleanQuery,
                    spokenAnswer = cleanForSpeech(fallbackLlmAnswer),
                    sources = listOf("Penelusuran AI")
                )
                putCachedResult(cleanQuery, result)
                return result
            }

            WebSearchResult(
                success = false,
                query = cleanQuery,
                spokenAnswer = "Maaf, informasi terverifikasi mengenai $cleanQuery belum ditemukan saat ini.",
                errorMessage = "NO_WEB_RESULTS"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            GepyLog.e(TAG, "Kesalahan saat mencari di web: ${e.message}", e)
            WebSearchResult(
                success = false,
                query = cleanQuery,
                spokenAnswer = "Maaf, terjadi gangguan koneksi saat mencari informasi di internet.",
                errorMessage = e.message
            )
        }
    }

    private object GeminiLiveSanitizer {
        fun sanitize(raw: String): String {
            return raw.trim().trim('"', '\'', '`').replace("\\s+".toRegex(), "")
        }
    }

    private suspend fun callDirectGeminiGrounding(
        cleanQuery: String,
        modelName: String,
        geminiKeys: List<String>
    ): WebSearchResult? = llmSynthesizer.callDirectGeminiGrounding(cleanQuery, modelName, geminiKeys)

    internal suspend fun fetchMultiSourceWebSnippets(query: String): List<WebSearchSnippet> =
        multiSourceParsers.fetchMultiSourceWebSnippets(query)

    internal suspend fun fetchJinaSearchSnippets(query: String, encoded: String): List<WebSearchSnippet> =
        multiSourceParsers.fetchJinaSearchSnippets(query, encoded)

    internal fun parseJinaSearchResponse(rawBody: String): List<WebSearchSnippet> =
        multiSourceParsers.parseJinaSearchResponse(rawBody)

    internal suspend fun fetchDuckDuckGoSnippets(query: String, encoded: String): List<WebSearchSnippet> =
        multiSourceParsers.fetchDuckDuckGoSnippets(query, encoded)

    internal suspend fun fetchGoogleNewsSnippets(encoded: String): List<WebSearchSnippet> =
        multiSourceParsers.fetchGoogleNewsSnippets(encoded)

    internal suspend fun fetchBingRssSnippets(encoded: String): List<WebSearchSnippet> =
        multiSourceParsers.fetchBingRssSnippets(encoded)

    internal suspend fun fetchWikipediaSnippets(query: String, encoded: String): List<WebSearchSnippet> =
        multiSourceParsers.fetchWikipediaSnippets(query, encoded)

    internal fun parseDuckDuckGoJson(rawJson: String): List<WebSearchSnippet> =
        multiSourceParsers.parseDuckDuckGoJson(rawJson)

    internal fun parseWikipediaRestSummaryJson(rawJson: String): WebSearchSnippet? =
        multiSourceParsers.parseWikipediaRestSummaryJson(rawJson)

    internal suspend fun fetchTopArticleLeadExcerpt(articleUrl: String): String =
        multiSourceParsers.fetchTopArticleLeadExcerpt(articleUrl)

    internal fun parseRssItems(rawXml: String, defaultSource: String, maxItems: Int = 6): List<WebSearchSnippet> =
        multiSourceParsers.parseRssItems(rawXml, defaultSource, maxItems)

    internal fun parseWikipediaSearchJson(
        rawJson: String,
        maxItems: Int = 3,
        defaultSource: String = "Wikipedia Indonesia",
        defaultBaseUrl: String = "https://id.wikipedia.org"
    ): List<WebSearchSnippet> = multiSourceParsers.parseWikipediaSearchJson(rawJson, maxItems, defaultSource, defaultBaseUrl)

    private suspend fun synthesizeSnippetsWithLlm(
        query: String,
        snippets: List<WebSearchSnippet>,
        geminiKeys: List<String>,
        fireworksApiKey: String
    ): String? = llmSynthesizer.synthesizeSnippetsWithLlm(query, snippets, geminiKeys, fireworksApiKey)

    private suspend fun callGatewaySearch(
        query: String,
        geminiApiKey: String,
        sessionToken: String
    ): WebSearchResult? {
        val url = "${gatewayBaseUrl.trimEnd('/')}/api/web/search"
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $sessionToken"
        )
        if (geminiApiKey.isNotBlank()) {
            headers["x-goog-api-key"] = geminiApiKey.trim()
        }
        val payload = JSONObject().put("query", query).toString()
        val resp = httpTransport.postJson(url, headers, payload)
        if (resp.statusCode !in 200..299) return null

        val json = JSONObject(resp.body)
        val rawAnswer = json.optString("answer", "").trim()
        if (rawAnswer.isEmpty()) return null

        val sourcesList = mutableListOf<String>()
        val sourcesArr = json.optJSONArray("sources")
        if (sourcesArr != null) {
            for (i in 0 until sourcesArr.length()) {
                val item = sourcesArr.optJSONObject(i)
                val title = item?.optString("title", "")?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    sourcesList.add(title)
                }
            }
        }
        return WebSearchResult(
            success = true,
            query = query,
            spokenAnswer = cleanForSpeech(rawAnswer),
            sources = sourcesList
        )
    }

    internal fun parseGeminiGroundingResponse(query: String, rawJson: String): WebSearchResult =
        llmSynthesizer.parseGeminiGroundingResponse(query, rawJson)

    /**
     * Membaca dan mengekstrak isi lengkap halaman web/artikel menggunakan **Jina Reader (`r.jina.ai`)**
     * menjadi Markdown bersih bebas iklan.
     */
    suspend fun readWebpageContent(
        url: String,
        sessionToken: String? = null
    ): WebpageReadResult {
        val targetUrl = normalizeUrl(url)
        if (targetUrl.isEmpty()) {
            return WebpageReadResult(
                success = false,
                url = url,
                title = "",
                markdownContent = "",
                spokenSummary = "Alamat URL halaman web tidak valid.",
                errorMessage = "INVALID_URL"
            )
        }

        val resolvedSessionToken = sessionToken?.takeIf { it.isNotBlank() && it != "offline_session_token" }
            ?: sessionTokenProvider()?.takeIf { it.isNotBlank() && it != "offline_session_token" }

        // Jalur 1: Coba melalui Cloudflare Gateway jika pengguna memiliki sesi login aktif
        if (!resolvedSessionToken.isNullOrBlank() && gatewayBaseUrl.isNotBlank()) {
            try {
                val gwUrl = "${gatewayBaseUrl.trimEnd('/')}/api/web/read"
                val resp = httpTransport.postJson(
                    url = gwUrl,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $resolvedSessionToken"
                    ),
                    jsonBody = JSONObject().put("url", targetUrl).toString()
                )
                if (resp.statusCode in 200..299) {
                    val json = JSONObject(resp.body)
                    val title = PromptInjectionSanitizer.sanitizeUiText(json.optString("title", targetUrl), maxLength = 200)
                    val content = json.optString("content", "").trim()
                    if (content.isNotEmpty()) {
                        val truncated = PromptInjectionSanitizer.sanitizeUiText(content, maxLength = MAX_MARKDOWN_CHARS)
                        val preview = cleanForSpeech(truncated.take(450))
                        return WebpageReadResult(
                            success = true,
                            url = targetUrl,
                            title = title,
                            markdownContent = truncated,
                            spokenSummary = "Halaman $title berhasil dibaca. $preview"
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                GepyLog.w(TAG, "Gateway web read fallback ke direct Jina Reader: ${e.message}")
            }
        }

        // Jalur 2: Direct Jina Reader API (`https://r.jina.ai/<url>`)
        return try {
            val jinaUrl = "https://r.jina.ai/$targetUrl"
            val resp = httpTransport.get(
                url = jinaUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Return-Format" to "markdown",
                    "X-Retain-Images" to "none",
                    "X-With-Links-Summary" to "false"
                )
            )

            if (resp.statusCode !in 200..299) {
                return WebpageReadResult(
                    success = false,
                    url = targetUrl,
                    title = "",
                    markdownContent = "",
                    spokenSummary = "Maaf, halaman web tersebut tidak dapat diakses saat ini.",
                    errorMessage = "HTTP_${resp.statusCode}"
                )
            }

            parseJinaResponse(targetUrl, resp.body)
        } catch (e: Throwable) {
            GepyLog.e(TAG, "Kesalahan membaca halaman web via Jina Reader: ${e.message}", e)
            WebpageReadResult(
                success = false,
                url = targetUrl,
                title = "",
                markdownContent = "",
                spokenSummary = "Maaf, terjadi gangguan saat membaca halaman web.",
                errorMessage = e.message
            )
        }
    }

    internal fun parseJinaResponse(targetUrl: String, rawBody: String): WebpageReadResult {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                val dataObj = json.optJSONObject("data") ?: json
                val title = PromptInjectionSanitizer.sanitizeUiText(dataObj.optString("title", targetUrl).ifBlank { targetUrl }, maxLength = 200)
                val content = dataObj.optString("content", "").trim()
                if (content.isNotEmpty()) {
                    val truncated = PromptInjectionSanitizer.sanitizeUiText(content, maxLength = MAX_MARKDOWN_CHARS)
                    val leadText = DomainAuthorityFilter.extractLeadParagraphs(truncated, maxChars = 600)
                    val spokenExcerpt = cleanForSpeech(leadText.ifBlank { truncated.take(450) })
                    return WebpageReadResult(
                        success = true,
                        url = targetUrl,
                        title = title,
                        markdownContent = truncated,
                        spokenSummary = "$title. $spokenExcerpt"
                    )
                }
            } catch (_: Throwable) {
                // Fallback ke teks mentah di bawah jika bukan JSON standar
            }
        }

        if (trimmed.isEmpty()) {
            return WebpageReadResult(
                success = false,
                url = targetUrl,
                title = targetUrl,
                markdownContent = "",
                spokenSummary = "Halaman web kosong atau tidak memiliki teks yang dapat dibaca.",
                errorMessage = "EMPTY_CONTENT"
            )
        }

        val truncated = PromptInjectionSanitizer.sanitizeUiText(trimmed, maxLength = MAX_MARKDOWN_CHARS)
        val leadText = DomainAuthorityFilter.extractLeadParagraphs(truncated, maxChars = 600)
        val spokenExcerpt = cleanForSpeech(leadText.ifBlank { truncated.take(450) })
        return WebpageReadResult(
            success = true,
            url = targetUrl,
            title = targetUrl,
            markdownContent = truncated,
            spokenSummary = spokenExcerpt
        )
    }

    /**
     * Mengeksekusi tool call `search_web_realtime` atau `read_webpage_content` dari sesi
     * **Gemini 3.8 Live** atau orchestrator.
     */
    suspend fun queryHuggingFaceTrending(query: String = ""): WebSearchResult =
        developerEcosystemClient.queryHuggingFaceTrending(query)

    suspend fun getHuggingFaceModelDetails(modelIdQuery: String): WebSearchResult =
        developerEcosystemClient.getHuggingFaceModelDetails(modelIdQuery)

    suspend fun queryGitHubTrendingOrSearch(query: String = "topic:agentic-ai"): WebSearchResult =
        developerEcosystemClient.queryGitHubTrendingOrSearch(query)

    internal suspend fun fetchDailyTrendingRepos(
        query: String,
        limit: Int = 5,
        concise: Boolean = false
    ): WebSearchResult? = developerEcosystemClient.fetchDailyTrendingRepos(query, limit, concise)

    internal fun parseGitHubTrendingHtml(
        html: String,
        maxItems: Int = 5,
        maxDescChars: Int = 100
    ): List<String> = developerEcosystemClient.parseGitHubTrendingHtml(html, maxItems, maxDescChars)

    suspend fun getGitHubRepoDetails(repoQuery: String): WebSearchResult =
        developerEcosystemClient.getGitHubRepoDetails(repoQuery)

    suspend fun getGitHubReadme(repoQuery: String): WebSearchResult =
        developerEcosystemClient.getGitHubReadme(repoQuery)

    internal fun distillReadmeContent(rawMarkdown: String, repoName: String): String =
        developerEcosystemClient.distillReadmeContent(rawMarkdown, repoName)

    suspend fun executeWebToolCall(
        name: String,
        args: Map<String, Any?>,
        geminiApiKey: String = EnvConfig.defaultGeminiApiKey,
        fireworksApiKey: String = EnvConfig.defaultFireworksApiKey,
        sessionToken: String? = null,
        geminiSecondaryApiKey: String = ""
    ): Map<String, Any?> {
        return when (name) {
            "search_web_realtime" -> {
                val query = args["query"]?.toString()?.trim().orEmpty()
                val res = searchWebRealtime(
                    query = query,
                    geminiApiKey = geminiApiKey,
                    fireworksApiKey = fireworksApiKey,
                    sessionToken = sessionToken,
                    geminiSecondaryApiKey = geminiSecondaryApiKey
                )
                res.toToolResponseMap()
            }

            "read_webpage_content" -> {
                val url = args["url"]?.toString()?.trim().orEmpty()
                val res = readWebpageContent(
                    url = url,
                    sessionToken = sessionToken
                )
                res.toToolResponseMap()
            }

            "query_huggingface_trending" -> {
                val query = args["query"]?.toString()?.trim().orEmpty()
                val res = queryHuggingFaceTrending(query)
                res.toToolResponseMap()
            }

            "get_huggingface_model_details" -> {
                val modelId = args["model_id"]?.toString()?.trim()
                    ?: args["query"]?.toString()?.trim().orEmpty()
                val res = getHuggingFaceModelDetails(modelId)
                res.toToolResponseMap()
            }

            "query_github_trending_or_search" -> {
                val query = args["query"]?.toString()?.trim().orEmpty()
                val res = queryGitHubTrendingOrSearch(query)
                res.toToolResponseMap()
            }

            "get_github_repo_details" -> {
                val repo = args["repo"]?.toString()?.trim()
                    ?: args["query"]?.toString()?.trim().orEmpty()
                val res = getGitHubRepoDetails(repo)
                res.toToolResponseMap()
            }

            "get_github_readme" -> {
                val repo = args["repo"]?.toString()?.trim()
                    ?: args["query"]?.toString()?.trim().orEmpty()
                val res = getGitHubReadme(repo)
                res.toToolResponseMap()
            }

            else -> mapOf(
                "status" to "ERROR",
                "error" to "Web tool tidak dikenal: $name"
            )
        }
    }
}
