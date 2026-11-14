package com.teknologigawai.gepy.core.web

import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Pengambil dan parser paralel multi-sumber (**Jina Search**, **DuckDuckGo**, **Wikipedia REST**,
 * **Google News ID RSS**, dan **Bing Web RSS**) beserta ekstraksi *Lead Paragraphs* Jina Reader.
 */
internal class MultiSourceWebSearchParsers(
    private val httpTransport: WebHttpTransport,
    private val parseJinaResponseFn: (targetUrl: String, rawBody: String) -> WebpageReadResult
) {
    /**
     * Mengambil hasil pencarian web real-time dari 5 engine secara simultan (**Jina Search s.jina.ai**,
     * **DuckDuckGo**, **Wikipedia REST**, **Google News ID RSS**, dan **Bing Web Search RSS**)
     * menggunakan [coroutineScope] dan [async] (v5.5.0 Broad Multi-Engine Intelligence).
     */
    internal suspend fun fetchMultiSourceWebSnippets(query: String): List<WebSearchSnippet> = coroutineScope {
        val encoded = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Throwable) {
            query.replace(" ", "+")
        }

        val jinaDeferred = async { fetchJinaSearchSnippets(query, encoded) }
        val ddgDeferred = async { fetchDuckDuckGoSnippets(query, encoded) }
        val wikiDeferred = async { fetchWikipediaSnippets(query, encoded) }
        val gnewsDeferred = async { fetchGoogleNewsSnippets(encoded) }
        val bingDeferred = async { fetchBingRssSnippets(encoded) }

        val rawResults = mutableListOf<WebSearchSnippet>()
        try {
            withTimeoutOrNull(2_500L) {
                val results = listOf(jinaDeferred, ddgDeferred, wikiDeferred, gnewsDeferred, bingDeferred).awaitAll()
                for (res in results) {
                    rawResults.addAll(res)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            GepyLog.w(WebSearchAndReaderBridge.TAG, "Parallel multi-engine search interrupted: ${e.message}")
        }

        // Saring blog/forum abal-abal dan urutkan berdasarkan skor otoritas domain (Tier 1 -> Tier 2 -> Tier 3)
        val ranked = DomainAuthorityFilter.filterAndRankSnippets(rawResults, maxSelected = 5).toMutableList()
        if (ranked.isEmpty()) return@coroutineScope emptyList()

        // Evaluasi kekayaan snippet: jika total karakter dari snippet teratas >= 180 karakter,
        // lewati Jina Reader untuk menghemat 1-2 detik waktu tunggu!
        val totalSnippetChars = ranked.take(3).sumOf { it.snippet.length }
        if (totalSnippetChars < 180) {
            val deepReadCandidateIndex = ranked.indexOfFirst { item ->
                val link = item.linkUrl
                link.startsWith("http", ignoreCase = true) &&
                    !link.contains("news.google.com/rss/", ignoreCase = true) &&
                    !link.contains("wikipedia.org", ignoreCase = true) &&
                    !link.contains("duckduckgo.com", ignoreCase = true) &&
                    item.snippet.length < 180
            }

            if (deepReadCandidateIndex >= 0) {
                val candidate = ranked[deepReadCandidateIndex]
                val leadExcerpt = withTimeoutOrNull(1_200L) {
                    fetchTopArticleLeadExcerpt(candidate.linkUrl)
                }.orEmpty()
                if (leadExcerpt.isNotBlank()) {
                    ranked[deepReadCandidateIndex] = candidate.copy(deepLeadExcerpt = leadExcerpt)
                }
            }
        }

        ranked
    }

    internal suspend fun fetchJinaSearchSnippets(query: String, encoded: String): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        try {
            withTimeoutOrNull(2_200L) {
                val jinaUrl = "https://s.jina.ai/$encoded"
                val resp = httpTransport.get(
                    url = jinaUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
                        "Accept" to "application/json",
                        "X-No-Cache" to "true"
                    )
                )
                if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                    list.addAll(parseJinaSearchResponse(resp.body))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        return list
    }

    internal fun parseJinaSearchResponse(rawBody: String): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return list

        try {
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val data = root.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until minOf(data.length(), 5)) {
                        val item = data.optJSONObject(i) ?: continue
                        val title = WebSearchAndReaderBridge.cleanForSpeech(item.optString("title", ""))
                        val url = item.optString("url", "")
                        val desc = WebSearchAndReaderBridge.cleanForSpeech(item.optString("description", ""))
                        val content = WebSearchAndReaderBridge.cleanForSpeech(item.optString("content", ""))
                        val snippet = desc.ifBlank { content.take(350) }
                        if (title.isNotEmpty() && (snippet.isNotEmpty() || url.isNotEmpty())) {
                            val eval = DomainAuthorityFilter.evaluateSource(
                                urlOrDomain = url,
                                publisherName = DomainAuthorityFilter.extractHost(url),
                                titleAndSnippet = "$title $snippet"
                            )
                            if (!eval.isBlocked) {
                                list.add(
                                    WebSearchSnippet(
                                        title = title,
                                        snippet = snippet.take(400),
                                        source = eval.canonicalPublisher,
                                        linkUrl = url,
                                        sourceUrl = url,
                                        credibilityTier = eval.tier,
                                        authorityScore = eval.score,
                                        deepLeadExcerpt = content.take(WebSearchAndReaderBridge.MAX_LEAD_PARAGRAPH_CHARS)
                                    )
                                )
                            }
                        }
                    }
                }
                if (list.isNotEmpty()) return list
            }
        } catch (_: Throwable) {
        }

        // Fallback jika Jina Search mengembalikan teks/markdown terstruktur
        try {
            val sections = trimmed.split(Regex("""(?:\r?\n){2,}(?=\[|\bTitle:\b)"""))
            for (sec in sections.take(5)) {
                val lines = sec.lines()
                var title = ""
                var url = ""
                val contentBuilder = StringBuilder()
                for (line in lines) {
                    val lTrim = line.trim()
                    when {
                        lTrim.startsWith("Title:", ignoreCase = true) -> {
                            title = WebSearchAndReaderBridge.cleanForSpeech(lTrim.substringAfter(":").trim())
                        }
                        lTrim.startsWith("URL Source:", ignoreCase = true) -> {
                            url = lTrim.substringAfter(":").trim()
                        }
                        lTrim.startsWith("[") && lTrim.contains("Title:", ignoreCase = true) -> {
                            title = WebSearchAndReaderBridge.cleanForSpeech(lTrim.substringAfter("Title:").trim())
                        }
                        lTrim.startsWith("[") && lTrim.contains("URL Source:", ignoreCase = true) -> {
                            url = lTrim.substringAfter("URL Source:").trim()
                        }
                        else -> {
                            if (contentBuilder.length < 500 && lTrim.isNotEmpty()) {
                                contentBuilder.append(lTrim).append(" ")
                            }
                        }
                    }
                }
                val snippet = WebSearchAndReaderBridge.cleanForSpeech(contentBuilder.toString()).take(350)
                if (title.isNotEmpty() && (snippet.isNotEmpty() || url.isNotEmpty())) {
                    val eval = DomainAuthorityFilter.evaluateSource(
                        urlOrDomain = url,
                        publisherName = DomainAuthorityFilter.extractHost(url),
                        titleAndSnippet = "$title $snippet"
                    )
                    if (!eval.isBlocked) {
                        list.add(
                            WebSearchSnippet(
                                title = title,
                                snippet = snippet,
                                source = eval.canonicalPublisher,
                                linkUrl = url,
                                sourceUrl = url,
                                credibilityTier = eval.tier,
                                authorityScore = eval.score,
                                deepLeadExcerpt = snippet
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return list
    }

    internal suspend fun fetchDuckDuckGoSnippets(query: String, encoded: String): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        try {
            withTimeoutOrNull(2_000L) {
                val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                val resp = httpTransport.get(
                    url = ddgUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
                        "Accept" to "application/json"
                    )
                )
                if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                    list.addAll(parseDuckDuckGoJson(resp.body))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        return list
    }

    internal suspend fun fetchGoogleNewsSnippets(encoded: String): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        try {
            withTimeoutOrNull(2_000L) {
                val gnewsUrl = "https://news.google.com/rss/search?q=$encoded&hl=id&gl=ID&ceid=ID:id"
                val resp = httpTransport.get(
                    url = gnewsUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14)",
                        "Accept" to "application/rss+xml, application/xml, text/xml"
                    )
                )
                if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                    list.addAll(parseRssItems(resp.body, defaultSource = "Google News ID", maxItems = 6))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        return list
    }

    internal suspend fun fetchBingRssSnippets(encoded: String): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        try {
            withTimeoutOrNull(2_000L) {
                val bingUrl = "https://www.bing.com/search?q=$encoded&setlang=id&cc=ID&format=rss"
                val resp = httpTransport.get(
                    url = bingUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
                        "Accept" to "application/rss+xml, application/xml, text/xml"
                    )
                )
                if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                    list.addAll(parseRssItems(resp.body, defaultSource = "Bing Web", maxItems = 6))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        return list
    }

    internal suspend fun fetchWikipediaSnippets(query: String, encoded: String): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        try {
            withTimeoutOrNull(2_000L) {
                val wikiSearchUrl = "https://id.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json"
                val resp = httpTransport.get(
                    url = wikiSearchUrl,
                    headers = mapOf(
                        "User-Agent" to "GepyAndroidAssistant/3.11 (https://teknologigawai.com)",
                        "Accept" to "application/json"
                    )
                )
                if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                    val parsedSearch = parseWikipediaSearchJson(resp.body, maxItems = 3)
                    val topItem = parsedSearch.firstOrNull()
                    if (topItem != null) {
                        val encodedTitle = try {
                            URLEncoder.encode(topItem.title, "UTF-8")
                        } catch (_: Throwable) {
                            topItem.title.replace(" ", "_")
                        }
                        val restSummaryUrl = "https://id.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"
                        val restResp = httpTransport.get(
                            url = restSummaryUrl,
                            headers = mapOf(
                                "User-Agent" to "GepyAndroidAssistant/3.11 (https://teknologigawai.com)",
                                "Accept" to "application/json"
                            )
                        )
                        if (restResp.statusCode in 200..299 && restResp.body.isNotBlank()) {
                            val restSnippet = parseWikipediaRestSummaryJson(restResp.body)
                            if (restSnippet != null) {
                                list.add(restSnippet)
                            }
                        }
                    }
                    for (item in parsedSearch) {
                        if (list.none { it.title.equals(item.title, ignoreCase = true) }) {
                            list.add(item)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }

        // Fallback ke Wikipedia Global/English jika artikel Wikipedia Bahasa Indonesia belum tersedia
        if (list.isEmpty()) {
            try {
                withTimeoutOrNull(1_500L) {
                    val enUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json"
                    val enResp = httpTransport.get(
                        url = enUrl,
                        headers = mapOf(
                            "User-Agent" to "GepyAndroidAssistant/3.11 (https://teknologigawai.com)",
                            "Accept" to "application/json"
                        )
                    )
                    if (enResp.statusCode in 200..299 && enResp.body.isNotBlank()) {
                        list.addAll(
                            parseWikipediaSearchJson(
                                rawJson = enResp.body,
                                maxItems = 2,
                                defaultSource = "Wikipedia",
                                defaultBaseUrl = "https://en.wikipedia.org"
                            )
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }

        return list
    }

    internal fun parseDuckDuckGoJson(rawJson: String): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        try {
            val root = JSONObject(rawJson)
            val heading = WebSearchAndReaderBridge.cleanForSpeech(root.optString("Heading", ""))
            val abstractText = WebSearchAndReaderBridge.cleanForSpeech(root.optString("AbstractText", ""))
            val abstractSource = WebSearchAndReaderBridge.cleanForSpeech(root.optString("AbstractSource", ""))
            val abstractUrl = root.optString("AbstractURL", "")
            val answer = WebSearchAndReaderBridge.cleanForSpeech(root.optString("Answer", ""))

            if (answer.isNotEmpty()) {
                val eval = DomainAuthorityFilter.evaluateSource(
                    urlOrDomain = "duckduckgo.com",
                    publisherName = "DuckDuckGo Instant Answer",
                    titleAndSnippet = answer
                )
                list.add(
                    WebSearchSnippet(
                        title = heading.ifBlank { "Fakta Terverifikasi" },
                        snippet = answer,
                        source = "Jawaban Langsung Web",
                        linkUrl = abstractUrl,
                        sourceUrl = "https://duckduckgo.com",
                        credibilityTier = CredibilityTier.TIER_2_VERIFIED_PRESS,
                        authorityScore = 88
                    )
                )
            }

            if (abstractText.isNotEmpty()) {
                val eval = DomainAuthorityFilter.evaluateSource(
                    urlOrDomain = abstractUrl.ifBlank { "duckduckgo.com" },
                    publisherName = abstractSource.ifBlank { "Referensi Web" },
                    titleAndSnippet = "$heading $abstractText"
                )
                if (!eval.isBlocked) {
                    list.add(
                        WebSearchSnippet(
                            title = heading.ifBlank { "Ringkasan Fakta" },
                            snippet = abstractText.take(400),
                            source = eval.canonicalPublisher,
                            linkUrl = abstractUrl,
                            sourceUrl = abstractUrl,
                            credibilityTier = eval.tier,
                            authorityScore = eval.score
                        )
                    )
                }
            }

            val relatedTopics = root.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until minOf(relatedTopics.length(), 3)) {
                    val topic = relatedTopics.optJSONObject(i) ?: continue
                    val text = WebSearchAndReaderBridge.cleanForSpeech(topic.optString("Text", ""))
                    val firstUrl = topic.optString("FirstURL", "")
                    if (text.isNotEmpty() && text.length >= 35) {
                        val eval = DomainAuthorityFilter.evaluateSource(
                            urlOrDomain = firstUrl,
                            publisherName = "DuckDuckGo Web",
                            titleAndSnippet = text
                        )
                        if (!eval.isBlocked) {
                            val topicTitle = text.substringBefore(" - ").take(80)
                            list.add(
                                WebSearchSnippet(
                                    title = topicTitle.ifBlank { "Topik Terkait" },
                                    snippet = text.take(300),
                                    source = eval.canonicalPublisher,
                                    linkUrl = firstUrl,
                                    sourceUrl = firstUrl,
                                    credibilityTier = eval.tier,
                                    authorityScore = eval.score
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    internal fun parseWikipediaRestSummaryJson(rawJson: String): WebSearchSnippet? {
        try {
            val root = JSONObject(rawJson)
            val title = WebSearchAndReaderBridge.cleanForSpeech(root.optString("title", ""))
            val extract = WebSearchAndReaderBridge.cleanForSpeech(root.optString("extract", ""))
            val pageUrl = root.optJSONObject("content_urls")
                ?.optJSONObject("desktop")
                ?.optString("page", "")
                .orEmpty()
            if (title.isNotEmpty() && extract.isNotEmpty()) {
                return WebSearchSnippet(
                    title = title,
                    snippet = extract.take(450),
                    source = "Wikipedia Indonesia",
                    linkUrl = pageUrl.ifBlank { "https://id.wikipedia.org/wiki/${title.replace(' ', '_')}" },
                    sourceUrl = "https://id.wikipedia.org",
                    credibilityTier = CredibilityTier.TIER_3_ACADEMIC_REFERENCE,
                    authorityScore = CredibilityTier.TIER_3_ACADEMIC_REFERENCE.baseScore + 5
                )
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * Mengambil 2–3 paragraf pembuka (*Lead Paragraphs*) dari URL artikel menggunakan Jina Reader
     * tanpa memuat seluruh halaman panjang ke dalam konteks LLM.
     */
    internal suspend fun fetchTopArticleLeadExcerpt(articleUrl: String): String {
        val normalized = WebSearchAndReaderBridge.normalizeUrl(articleUrl)
        if (normalized.isEmpty()) return ""
        return try {
            val jinaUrl = "https://r.jina.ai/$normalized"
            val resp = httpTransport.get(
                url = jinaUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Return-Format" to "markdown",
                    "X-Retain-Images" to "none",
                    "X-With-Links-Summary" to "false"
                )
            )
            if (resp.statusCode !in 200..299 || resp.body.isBlank()) {
                return ""
            }
            val parsed = parseJinaResponseFn(normalized, resp.body)
            if (!parsed.success || parsed.markdownContent.isBlank()) {
                return ""
            }
            DomainAuthorityFilter.extractLeadParagraphs(
                rawMarkdown = parsed.markdownContent,
                maxChars = WebSearchAndReaderBridge.MAX_LEAD_PARAGRAPH_CHARS
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            ""
        }
    }

    internal fun parseRssItems(rawXml: String, defaultSource: String, maxItems: Int = 6): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        val itemRegex = Regex("""<item\b[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val titleRegex = Regex("""<title[^>]*>(?:<!\[CDATA\[)?(.*?)(?:]]>)?</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val linkRegex = Regex("""<link[^>]*>(?:<!\[CDATA\[)?(.*?)(?:]]>)?</link>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val descRegex = Regex("""<description[^>]*>(?:<!\[CDATA\[)?(.*?)(?:]]>)?</description>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val sourceRegex = Regex("""<source\b([^>]*)>(?:<!\[CDATA\[)?(.*?)(?:]]>)?</source>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val urlAttrRegex = Regex("""url\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val pubDateRegex = Regex("""<pubDate[^>]*>(.*?)</pubDate>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (match in itemRegex.findAll(rawXml)) {
            if (list.size >= maxItems) break
            val block = match.groupValues[1]
            val rawTitle = titleRegex.find(block)?.groupValues?.get(1).orEmpty()
            val rawLink = linkRegex.find(block)?.groupValues?.get(1).orEmpty().trim()
            val rawDesc = descRegex.find(block)?.groupValues?.get(1).orEmpty()
            val sourceMatch = sourceRegex.find(block)
            val rawSourceAttrs = sourceMatch?.groupValues?.get(1).orEmpty()
            val rawSourceUrl = urlAttrRegex.find(rawSourceAttrs)?.groupValues?.get(1).orEmpty().trim()
            val rawSourceName = sourceMatch?.groupValues?.get(2).orEmpty()
            val rawDate = pubDateRegex.find(block)?.groupValues?.get(1).orEmpty().trim()
            val friendlyDate = WebSearchAndReaderBridge.formatFriendlyIndonesianDate(rawDate)

            val cleanTitle = WebSearchAndReaderBridge.cleanForSpeech(rawTitle)
            var cleanDesc = WebSearchAndReaderBridge.cleanForSpeech(rawDesc)
            if (cleanDesc.equals(cleanTitle, ignoreCase = true)) {
                cleanDesc = ""
            }
            if (friendlyDate.isNotEmpty() && cleanDesc.length < 200) {
                cleanDesc = if (cleanDesc.isEmpty()) "(Rilis: $friendlyDate)" else "$cleanDesc (Rilis: $friendlyDate)"
            }
            val inferredPublisher = WebSearchAndReaderBridge.cleanForSpeech(rawSourceName).ifEmpty {
                cleanTitle.substringAfterLast(" - ", "").trim().ifEmpty { defaultSource }
            }

            if (cleanTitle.isNotEmpty()) {
                val authorityEval = DomainAuthorityFilter.evaluateSource(
                    urlOrDomain = rawSourceUrl.ifBlank { rawLink },
                    publisherName = inferredPublisher,
                    titleAndSnippet = "$cleanTitle $cleanDesc"
                )
                list.add(
                    WebSearchSnippet(
                        title = cleanTitle,
                        snippet = cleanDesc.take(260),
                        source = authorityEval.canonicalPublisher,
                        linkUrl = rawLink,
                        sourceUrl = rawSourceUrl,
                        publishedDate = friendlyDate.ifBlank { rawDate },
                        credibilityTier = authorityEval.tier,
                        authorityScore = authorityEval.score
                    )
                )
            }
        }
        return list
    }

    internal fun parseWikipediaSearchJson(
        rawJson: String,
        maxItems: Int = 3,
        defaultSource: String = "Wikipedia Indonesia",
        defaultBaseUrl: String = "https://id.wikipedia.org"
    ): List<WebSearchSnippet> {
        val list = mutableListOf<WebSearchSnippet>()
        try {
            val root = JSONObject(rawJson)
            val searchArr = root.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            for (i in 0 until minOf(searchArr.length(), maxItems)) {
                val item = searchArr.optJSONObject(i) ?: continue
                val title = WebSearchAndReaderBridge.cleanForSpeech(item.optString("title", ""))
                val snippet = WebSearchAndReaderBridge.cleanForSpeech(item.optString("snippet", ""))
                if (title.isNotEmpty() && snippet.isNotEmpty()) {
                    val wikiUrl = "$defaultBaseUrl/wiki/${title.replace(' ', '_')}"
                    list.add(
                        WebSearchSnippet(
                            title = title,
                            snippet = snippet,
                            source = defaultSource,
                            linkUrl = wikiUrl,
                            sourceUrl = defaultBaseUrl,
                            credibilityTier = CredibilityTier.TIER_3_ACADEMIC_REFERENCE,
                            authorityScore = CredibilityTier.TIER_3_ACADEMIC_REFERENCE.baseScore
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }
}
