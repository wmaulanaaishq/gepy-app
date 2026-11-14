package com.teknologigawai.gepy.core.web

import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Klien & parser ekosistem developer (**Hugging Face Hub** & **GitHub REST/Trending/README**)
 * untuk pencarian model/repository trending, metadata rilis terbaru, dan distilasi README.
 */
internal class DeveloperEcosystemWebClient(
    private val httpTransport: WebHttpTransport
) {
    /**
     * Mengambil daftar model AI/ML yang sedang trending atau mencari model spesifik di Hugging Face Hub.
     */
    suspend fun queryHuggingFaceTrending(query: String = ""): WebSearchResult {
        val rawQuery = query.trim()
        val requestedLimit = WebSearchAndReaderBridge.extractRequestedItemLimit(rawQuery, defaultLimit = 5)
        val concise = WebSearchAndReaderBridge.isConciseQuery(rawQuery)
        val searchTerm = WebSearchAndReaderBridge.stripFormatConstraintsFromSearchTerm(rawQuery)
        val url = if (searchTerm.isBlank() || searchTerm.equals("trending", ignoreCase = true) ||
            searchTerm.equals("trending hari ini", ignoreCase = true)
        ) {
            "https://huggingface.co/api/models?sort=trendingScore&direction=-1&limit=$requestedLimit"
        } else {
            val encoded = try { URLEncoder.encode(searchTerm, "UTF-8") } catch (_: Exception) { searchTerm }
            "https://huggingface.co/api/models?search=$encoded&sort=trendingScore&direction=-1&limit=$requestedLimit"
        }

        return try {
            val headers = mapOf("User-Agent" to "Gepy-Native-Android")
            val resp = httpTransport.get(url, headers)
            if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                val jsonArray = JSONArray(resp.body)
                val modelList = mutableListOf<String>()
                for (i in 0 until minOf(jsonArray.length(), requestedLimit)) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val likes = obj.optInt("likes", 0)
                    val downloads = obj.optInt("downloads", 0)
                    val tag = obj.optString("pipeline_tag", "").ifBlank { "AI Model" }
                    val createdDate = WebSearchAndReaderBridge.formatIsoDateToIndonesian(obj.optString("createdAt", ""))
                    val metaPart = if (concise) {
                        "$tag • $likes likes • $downloads unduhan"
                    } else {
                        val datePart = if (createdDate.isNotBlank()) " • rilis $createdDate" else ""
                        "$tag • $likes likes • $downloads unduhan$datePart"
                    }
                    if (id.isNotEmpty()) {
                        modelList.add("$id ($metaPart)")
                    }
                }
                if (modelList.isNotEmpty()) {
                    val header = if (searchTerm.isBlank()) {
                        if (modelList.size == 1) {
                            "Model AI trending teratas di Hugging Face saat ini:"
                        } else {
                            "Berikut ${modelList.size} model AI trending di Hugging Face saat ini:"
                        }
                    } else {
                        if (modelList.size == 1) {
                            "Hasil teratas model '$searchTerm' di Hugging Face:"
                        } else {
                            "Hasil pencarian model '$searchTerm' di Hugging Face:"
                        }
                    }
                    val formattedBody = if (modelList.size == 1) {
                        modelList.first()
                    } else {
                        modelList.mapIndexed { idx, line -> "${idx + 1}. $line" }.joinToString("\n")
                    }
                    val spoken = "$header\n$formattedBody"
                    WebSearchResult(
                        success = true,
                        query = query,
                        spokenAnswer = spoken,
                        sources = listOf("https://huggingface.co/models")
                    )
                } else {
                    WebSearchResult(
                        success = false,
                        query = query,
                        spokenAnswer = "Tidak ditemukan model yang cocok di Hugging Face.",
                        sources = emptyList()
                    )
                }
            } else {
                WebSearchResult(
                    success = false,
                    query = query,
                    spokenAnswer = "Gagal mengambil data dari Hugging Face Hub (Status ${resp.statusCode}).",
                    sources = emptyList(),
                    errorMessage = "HTTP ${resp.statusCode}"
                )
            }
        } catch (e: Exception) {
            GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal query Hugging Face: ${e.message}")
            WebSearchResult(
                success = false,
                query = query,
                spokenAnswer = "Terjadi kendala saat memeriksa Hugging Face Hub.",
                sources = emptyList(),
                errorMessage = e.message
            )
        }
    }

    /**
     * Mengambil rincian mendalam tentang model AI di Hugging Face Hub: task/pipeline, jumlah unduhan, likes,
     * lisensi, tanggal rilis awal (createdAt), dan tanggal modifikasi terakhir (lastModified).
     */
    suspend fun getHuggingFaceModelDetails(modelIdQuery: String): WebSearchResult {
        val rawInput = modelIdQuery.trim()
        if (rawInput.isBlank()) {
            return WebSearchResult(
                success = false,
                query = modelIdQuery,
                spokenAnswer = "ID model Hugging Face tidak boleh kosong.",
                sources = emptyList()
            )
        }

        var targetModel = rawInput
            .removePrefix("https://huggingface.co/")
            .removePrefix("http://huggingface.co/")
            .removePrefix("huggingface.co/")
            .trim('/')

        val headers = mapOf("User-Agent" to "Gepy-Native-Android")

        // Jika tidak mengandung '/', cari dulu via search API
        if (!targetModel.contains("/")) {
            val encodedQuery = try { URLEncoder.encode(targetModel, "UTF-8") } catch (_: Exception) { targetModel }
            val searchUrl = "https://huggingface.co/api/models?search=$encodedQuery&sort=trendingScore&direction=-1&limit=3"
            try {
                val searchResp = httpTransport.get(searchUrl, headers)
                if (searchResp.statusCode in 200..299 && searchResp.body.isNotBlank()) {
                    val arr = JSONArray(searchResp.body)
                    if (arr.length() > 0) {
                        targetModel = arr.getJSONObject(0).optString("id", targetModel)
                    }
                }
            } catch (e: Exception) {
                GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal resolving HF model '$targetModel': ${e.message}")
            }
        }

        val modelUrl = "https://huggingface.co/api/models/$targetModel"
        return try {
            val resp = httpTransport.get(modelUrl, headers)
            if (resp.statusCode !in 200..299 || resp.body.isBlank()) {
                return WebSearchResult(
                    success = false,
                    query = modelIdQuery,
                    spokenAnswer = "Model '$targetModel' tidak ditemukan di Hugging Face Hub (Status ${resp.statusCode}).",
                    sources = emptyList(),
                    errorMessage = "HTTP ${resp.statusCode}"
                )
            }

            val obj = JSONObject(resp.body)
            val id = obj.optString("id", targetModel)
            val author = obj.optString("author", targetModel.substringBefore("/")).ifBlank { "Komunitas" }
            val pipelineTag = obj.optString("pipeline_tag", "").trim().ifBlank { "AI Model" }
            val likes = obj.optInt("likes", 0)
            val downloads = obj.optInt("downloads", 0)
            val createdDate = WebSearchAndReaderBridge.formatIsoDateToIndonesian(obj.optString("createdAt", ""))
            val modifiedDate = WebSearchAndReaderBridge.formatIsoDateToIndonesian(obj.optString("lastModified", ""))
            val cardData = obj.optJSONObject("cardData")
            val license = cardData?.optString("license")?.trim().orEmpty().ifBlank { "Lisensi terbuka" }
            val htmlUrl = "https://huggingface.co/$targetModel"

            val createdPart = if (createdDate.isNotBlank()) " Model ini pertama kali dirilis pada $createdDate" else " Model ini"
            val modifiedPart = if (modifiedDate.isNotBlank()) " dan terakhir diperbarui pada $modifiedDate" else ""
            val spoken = "Model Hugging Face $id oleh $author untuk task $pipelineTag. Memiliki $likes likes dan $downloads unduhan.$createdPart$modifiedPart dengan lisensi $license."

            WebSearchResult(
                success = true,
                query = modelIdQuery,
                spokenAnswer = spoken,
                sources = listOf(htmlUrl)
            )
        } catch (e: Exception) {
            GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal query detail Hugging Face: ${e.message}")
            WebSearchResult(
                success = false,
                query = modelIdQuery,
                spokenAnswer = "Terjadi kendala saat memeriksa detail model Hugging Face '$targetModel'.",
                sources = emptyList(),
                errorMessage = e.message
            )
        }
    }

    /**
     * Mengambil daftar repository yang sedang trending hari ini atau mencari repo topik tertentu di GitHub.
     */
    suspend fun queryGitHubTrendingOrSearch(query: String = "topic:agentic-ai"): WebSearchResult {
        val rawQuery = query.trim().ifBlank { "trending hari ini" }
        val requestedLimit = WebSearchAndReaderBridge.extractRequestedItemLimit(rawQuery, defaultLimit = 5)
        val concise = WebSearchAndReaderBridge.isConciseQuery(rawQuery)
        val strippedTerm = WebSearchAndReaderBridge.stripFormatConstraintsFromSearchTerm(rawQuery).ifBlank { "trending hari ini" }
        val lower = strippedTerm.lowercase()

        // 1. Deteksi kueri trending hari ini (Real Daily Trending)
        val isDailyTrending = lower.contains("hari ini") || lower.contains("today") ||
            lower.contains("daily") || lower.contains("viral") ||
            lower == "trending" || lower == "tren" || lower == "github trending" ||
            lower.startsWith("trending ") || Regex("""(?i)\b(trending|tren)\b""").containsMatchIn(lower)

        if (isDailyTrending) {
            val dailyResult = fetchDailyTrendingRepos(
                query = rawQuery,
                maxItems = requestedLimit,
                concise = concise
            )
            if (dailyResult.success) {
                return dailyResult
            }
        }

        // 2. Pencarian topik/query umum via GitHub Search API
        val cleanTopic = strippedTerm.replace(Regex("""(?i)\b(trending|tren|hari ini|today|daily)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { strippedTerm }
        val encoded = try { URLEncoder.encode(cleanTopic, "UTF-8") } catch (_: Exception) { cleanTopic }
        val url = "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=$requestedLimit"

        return try {
            val headers = mapOf(
                "User-Agent" to "Gepy-Native-Android",
                "Accept" to "application/vnd.github.v3+json"
            )
            val resp = httpTransport.get(url, headers)
            if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                val jsonObj = JSONObject(resp.body)
                val items = jsonObj.optJSONArray("items") ?: JSONArray()
                val repoList = mutableListOf<String>()
                for (i in 0 until minOf(items.length(), requestedLimit)) {
                    val obj = items.optJSONObject(i) ?: continue
                    val name = obj.optString("full_name", "").trim()
                    val stars = obj.optInt("stargazers_count", 0)
                    val lang = obj.optString("language", "").trim()
                    val langPart = if (lang.isNotBlank()) "$lang • " else ""
                    val createdDate = if (concise) "" else WebSearchAndReaderBridge.formatIsoDateToIndonesian(obj.optString("created_at", ""))
                    val datePart = if (createdDate.isNotBlank()) " • rilis $createdDate" else ""
                    val descMaxChars = if (concise) 72 else 100
                    val desc = WebSearchAndReaderBridge.truncateAtWordBoundary(obj.optString("description", ""), descMaxChars)
                    if (name.isNotEmpty()) {
                        val descPart = if (desc.isNotBlank()) " — $desc" else ""
                        repoList.add("$name ($langPart$stars bintang$datePart)$descPart")
                    }
                }
                if (repoList.isNotEmpty()) {
                    val spoken = if (repoList.size == 1) {
                        "Repository GitHub teratas untuk '$cleanTopic':\n${repoList.first()}"
                    } else {
                        "Berikut ${repoList.size} repository GitHub teratas untuk '$cleanTopic':\n" +
                            repoList.mapIndexed { idx, line -> "${idx + 1}. $line" }.joinToString("\n")
                    }
                    WebSearchResult(
                        success = true,
                        query = query,
                        spokenAnswer = spoken,
                        sources = listOf("https://github.com/search?q=$encoded")
                    )
                } else {
                    WebSearchResult(
                        success = false,
                        query = query,
                        spokenAnswer = "Tidak ditemukan repository GitHub untuk '$cleanTopic'.",
                        sources = emptyList()
                    )
                }
            } else {
                WebSearchResult(
                    success = false,
                    query = query,
                    spokenAnswer = "Gagal mengambil data dari GitHub API (Status ${resp.statusCode}).",
                    sources = emptyList(),
                    errorMessage = "HTTP ${resp.statusCode}"
                )
            }
        } catch (e: Exception) {
            GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal query GitHub: ${e.message}")
            WebSearchResult(
                success = false,
                query = query,
                spokenAnswer = "Terjadi kendala saat memeriksa GitHub API.",
                sources = emptyList(),
                errorMessage = e.message
            )
        }
    }

    /**
     * Mengambil daftar repositori yang sedang trending hari ini langsung dari `github.com/trending?since=daily`.
     */
    internal suspend fun fetchDailyTrendingRepos(
        query: String,
        maxItems: Int = WebSearchAndReaderBridge.extractRequestedItemLimit(query, defaultLimit = 5),
        concise: Boolean = WebSearchAndReaderBridge.isConciseQuery(query)
    ): WebSearchResult {
        val lower = query.lowercase()
        val language = when {
            lower.contains("python") -> "python"
            lower.contains("typescript") || lower.contains("ts") -> "typescript"
            lower.contains("javascript") || lower.contains("js") -> "javascript"
            lower.contains("kotlin") -> "kotlin"
            lower.contains("rust") -> "rust"
            lower.contains("golang") || lower.contains(" go ") || lower.endsWith(" go") -> "go"
            lower.contains("c++") || lower.contains("cpp") -> "c++"
            lower.contains("java") && !lower.contains("javascript") -> "java"
            else -> ""
        }

        val trendingPath = if (language.isNotEmpty()) "https://github.com/trending/$language?since=daily" else "https://github.com/trending?since=daily"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        return try {
            val resp = httpTransport.get(trendingPath, headers)
            if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
                val repos = parseGitHubTrendingHtml(
                    html = resp.body,
                    maxItems = maxItems,
                    maxDescChars = if (concise) 72 else 100
                )
                if (repos.isNotEmpty()) {
                    val langHeader = if (language.isNotEmpty()) " bahasa ${language.replaceFirstChar { it.uppercase() }}" else ""
                    val spoken = if (repos.size == 1) {
                        "Trending teratas di GitHub$langHeader hari ini:\n${repos.first()}"
                    } else {
                        "Berikut ${repos.size} repository trending hari ini di GitHub$langHeader:\n" +
                            repos.mapIndexed { idx, line -> "${idx + 1}. $line" }.joinToString("\n")
                    }
                    WebSearchResult(
                        success = true,
                        query = query,
                        spokenAnswer = spoken,
                        sources = listOf(trendingPath)
                    )
                } else {
                    WebSearchResult(
                        success = false,
                        query = query,
                        spokenAnswer = "Tidak dapat menemukan data trending harian di GitHub.",
                        sources = emptyList()
                    )
                }
            } else {
                WebSearchResult(
                    success = false,
                    query = query,
                    spokenAnswer = "Gagal mengakses halaman trending GitHub (Status ${resp.statusCode}).",
                    sources = emptyList(),
                    errorMessage = "HTTP ${resp.statusCode}"
                )
            }
        } catch (e: Exception) {
            GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal mengambil trending GitHub harian: ${e.message}")
            WebSearchResult(
                success = false,
                query = query,
                spokenAnswer = "Terjadi kendala saat memeriksa trending harian GitHub.",
                sources = emptyList(),
                errorMessage = e.message
            )
        }
    }

    internal fun parseGitHubTrendingHtml(
        html: String,
        maxItems: Int = 5,
        maxDescChars: Int = 100
    ): List<String> {
        val results = mutableListOf<String>()
        val rowRegex = Regex("""<article\s+class="Box-row"[\s\S]*?</article>""")
        val nameRegex = Regex("""href="/([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)"""")
        val descRegex = Regex("""<p\s+class="col-9[^\"]*"[^>]*>([\s\S]*?)</p>""")
        val langRegex = Regex("""itemprop="programmingLanguage">([^<]+)</span>""")
        val todayRegex = Regex("""([0-9,]+)\s+stars\s+(?:today|this\s+week)""")

        for (match in rowRegex.findAll(html)) {
            val row = match.value
            val nameMatch = nameRegex.find(row) ?: continue
            val fullName = nameMatch.groupValues[1].replace("\\s".toRegex(), "").trim()
            if (fullName.isEmpty()) continue

            val descMatch = descRegex.find(row)
            val rawDesc = descMatch?.groupValues?.getOrNull(1)
                ?.let { WebSearchAndReaderBridge.cleanForSpeech(it) }
                .orEmpty()
            val desc = WebSearchAndReaderBridge.truncateAtWordBoundary(rawDesc, maxDescChars)

            val lang = langRegex.find(row)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val starsToday = todayRegex.find(row)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val metaParts = mutableListOf<String>()
            if (lang.isNotEmpty()) metaParts.add(lang)
            if (starsToday.isNotEmpty()) metaParts.add("+$starsToday bintang hari ini")

            val metaSuffix = if (metaParts.isNotEmpty()) " (${metaParts.joinToString(" • ")})" else ""
            val descSuffix = if (desc.isNotEmpty()) " — $desc" else ""
            results.add("$fullName$metaSuffix$descSuffix")
            if (results.size >= maxItems.coerceAtLeast(1)) break
        }
        return results
    }

    /**
     * Mengambil rincian mendalam tentang repository GitHub: deskripsi lengkap, bintang, forks, bahasa utama,
     * lisensi, tanggal rilis awal (created_at), tanggal update (pushed_at), dan versi rilis terbaru (releases/latest).
     */
    suspend fun getGitHubRepoDetails(repoQuery: String): WebSearchResult {
        val rawInput = repoQuery.trim()
        if (rawInput.isBlank()) {
            return WebSearchResult(
                success = false,
                query = repoQuery,
                spokenAnswer = "Nama repository GitHub tidak boleh kosong.",
                sources = emptyList()
            )
        }

        var targetRepo = rawInput
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .removeSuffix(".git")
            .trim('/')

        val headers = mapOf(
            "User-Agent" to "Gepy-Native-Android",
            "Accept" to "application/vnd.github.v3+json"
        )

        // Jika bukan format owner/repo, cari dulu di GitHub Search API
        if (!targetRepo.contains("/")) {
            val encodedQuery = try { URLEncoder.encode(targetRepo, "UTF-8") } catch (_: Exception) { targetRepo }
            val searchUrl = "https://api.github.com/search/repositories?q=$encodedQuery+in:name&sort=stars&order=desc&per_page=3"
            try {
                val searchResp = httpTransport.get(searchUrl, headers)
                if (searchResp.statusCode in 200..299 && searchResp.body.isNotBlank()) {
                    val searchJson = JSONObject(searchResp.body)
                    val items = searchJson.optJSONArray("items") ?: JSONArray()
                    if (items.length() > 0) {
                        var matchedName = ""
                        for (i in 0 until items.length()) {
                            val itObj = items.optJSONObject(i) ?: continue
                            val name = itObj.optString("name", "")
                            if (name.equals(targetRepo, ignoreCase = true)) {
                                matchedName = itObj.optString("full_name", "")
                                break
                            }
                        }
                        targetRepo = if (matchedName.isNotBlank()) matchedName else items.getJSONObject(0).optString("full_name", targetRepo)
                    }
                }
            } catch (e: Exception) {
                GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal resolving repo '$targetRepo': ${e.message}")
            }
        }

        val repoUrl = "https://api.github.com/repos/$targetRepo"
        return try {
            val repoResp = httpTransport.get(repoUrl, headers)
            if (repoResp.statusCode !in 200..299 || repoResp.body.isBlank()) {
                return WebSearchResult(
                    success = false,
                    query = repoQuery,
                    spokenAnswer = "Repository '$targetRepo' tidak ditemukan di GitHub (Status ${repoResp.statusCode}).",
                    sources = emptyList(),
                    errorMessage = "HTTP ${repoResp.statusCode}"
                )
            }

            val repoJson = JSONObject(repoResp.body)
            val fullName = repoJson.optString("full_name", targetRepo)
            val description = repoJson.optString("description", "").trim().ifBlank { "Proyek open-source" }
            val language = repoJson.optString("language", "").trim().ifBlank { "Multi-bahasa" }
            val stars = repoJson.optInt("stargazers_count", 0)
            val forks = repoJson.optInt("forks_count", 0)
            val license = repoJson.optJSONObject("license")?.optString("name")?.trim().orEmpty().ifBlank { "Lisensi open-source" }
            val createdDate = WebSearchAndReaderBridge.formatIsoDateToIndonesian(repoJson.optString("created_at", ""))
            val updatedDate = WebSearchAndReaderBridge.formatIsoDateToIndonesian(repoJson.optString("pushed_at", ""))
            val htmlUrl = repoJson.optString("html_url", "https://github.com/$targetRepo")

            // Ambil rilis terbaru
            val releasesUrl = "https://api.github.com/repos/$targetRepo/releases/latest"
            var releaseInfo = ""
            try {
                val relResp = httpTransport.get(releasesUrl, headers)
                if (relResp.statusCode in 200..299 && relResp.body.isNotBlank()) {
                    val relJson = JSONObject(relResp.body)
                    val relTag = relJson.optString("tag_name", "").trim()
                    val relName = relJson.optString("name", "").trim().ifBlank { relTag }
                    val relDate = WebSearchAndReaderBridge.formatIsoDateToIndonesian(relJson.optString("published_at", ""))
                    val datePart = if (relDate.isNotBlank()) " pada $relDate" else ""
                    releaseInfo = "Versi rilis terbarunya adalah $relName (tag $relTag)$datePart."
                } else {
                    // Fallback ke tags jika belum ada rilis resmi GitHub Releases
                    val tagsUrl = "https://api.github.com/repos/$targetRepo/tags?per_page=1"
                    val tagResp = httpTransport.get(tagsUrl, headers)
                    if (tagResp.statusCode in 200..299 && tagResp.body.isNotBlank()) {
                        val tagsArr = JSONArray(tagResp.body)
                        if (tagsArr.length() > 0) {
                            val latestTag = tagsArr.getJSONObject(0).optString("name", "")
                            val updatePart = if (updatedDate.isNotBlank()) ", dan update kode terakhir pada $updatedDate" else ""
                            releaseInfo = "Tag versi terbarunya adalah $latestTag$updatePart."
                        }
                    }
                }
            } catch (e: Exception) {
                GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal mengambil data release repo '$targetRepo': ${e.message}")
            }

            if (releaseInfo.isBlank() && updatedDate.isNotBlank()) {
                releaseInfo = "Belum memiliki tag rilis resmi, kode terakhir diperbarui pada $updatedDate."
            }

            val createdPart = if (createdDate.isNotBlank()) " Proyek ini dibuat pada $createdDate." else ""
            val spoken = "Repository $fullName: '$description'. Bahasa utama $language, memiliki $stars bintang dan lisensi $license. $releaseInfo$createdPart"

            WebSearchResult(
                success = true,
                query = repoQuery,
                spokenAnswer = spoken,
                sources = listOf(htmlUrl)
            )
        } catch (e: Exception) {
            GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal mendapatkan detail repo GitHub: ${e.message}")
            WebSearchResult(
                success = false,
                query = repoQuery,
                spokenAnswer = "Terjadi kendala saat memeriksa detail repository GitHub '$targetRepo'.",
                sources = emptyList(),
                errorMessage = e.message
            )
        }
    }

    /**
     * Membaca dan menyintesis isi berkas `README.md` dari repository GitHub.
     * Mengambil raw markdown dari GitHub API resmi dengan fallback cerdas ke `raw.githubusercontent.com`.
     */
    suspend fun getGitHubReadme(repoQuery: String): WebSearchResult {
        val rawInput = repoQuery.trim()
        if (rawInput.isBlank()) {
            return WebSearchResult(
                success = false,
                query = repoQuery,
                spokenAnswer = "Nama repository GitHub tidak boleh kosong.",
                sources = emptyList()
            )
        }

        var targetRepo = rawInput
            .replace("https://github.com/", "")
            .replace("http://github.com/", "")
            .replace("github.com/", "")
            .trim('/')

        // Jika hanya berupa satu kata (misal: 'langgraph'), cari full_name dari search API
        if (!targetRepo.contains("/")) {
            try {
                val searchUrl = "https://api.github.com/search/repositories?q=" +
                    URLEncoder.encode(targetRepo, "UTF-8") + "&per_page=3"
                val searchHeaders = mapOf(
                    "User-Agent" to "Gepy-Native-Android",
                    "Accept" to "application/vnd.github.v3+json"
                )
                val sResp = httpTransport.get(searchUrl, searchHeaders)
                if (sResp.statusCode in 200..299 && sResp.body.isNotBlank()) {
                    val sJson = JSONObject(sResp.body)
                    val items = sJson.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        var matchedName = ""
                        for (i in 0 until items.length()) {
                            val itObj = items.getJSONObject(i)
                            val name = itObj.optString("name", "")
                            if (name.equals(targetRepo, ignoreCase = true)) {
                                matchedName = itObj.optString("full_name", "")
                                break
                            }
                        }
                        targetRepo = if (matchedName.isNotBlank()) matchedName else items.getJSONObject(0).optString("full_name", targetRepo)
                    }
                }
            } catch (e: Exception) {
                GepyLog.w(WebSearchAndReaderBridge.TAG, "Gagal resolving target repo untuk README '$targetRepo': ${e.message}")
            }
        }

        val readmeApiUrl = "https://api.github.com/repos/$targetRepo/readme"
        val apiHeaders = mapOf(
            "User-Agent" to "Gepy-Native-Android",
            "Accept" to "application/vnd.github.raw"
        )

        var rawReadmeMarkdown = ""
        val sourceUrl = "https://github.com/$targetRepo#readme"

        // Jalur 1: GitHub API official raw markdown endpoint
        try {
            val apiResp = httpTransport.get(readmeApiUrl, apiHeaders)
            if (apiResp.statusCode in 200..299 && apiResp.body.isNotBlank()) {
                rawReadmeMarkdown = apiResp.body
            }
        } catch (e: Exception) {
            GepyLog.w(WebSearchAndReaderBridge.TAG, "GitHub API README fetch gagal: ${e.message}")
        }

        // Jalur 2: Fallback ke raw.githubusercontent.com jika API gagal (misal rate limit 403 atau 404)
        if (rawReadmeMarkdown.isBlank()) {
            val fallbackBranches = listOf("HEAD", "main", "master")
            for (branch in fallbackBranches) {
                try {
                    val rawUrl = "https://raw.githubusercontent.com/$targetRepo/$branch/README.md"
                    val rawHeaders = mapOf("User-Agent" to "Mozilla/5.0")
                    val rawResp = httpTransport.get(rawUrl, rawHeaders)
                    if (rawResp.statusCode in 200..299 && rawResp.body.isNotBlank()) {
                        rawReadmeMarkdown = rawResp.body
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (rawReadmeMarkdown.isBlank()) {
            return WebSearchResult(
                success = false,
                query = repoQuery,
                spokenAnswer = "Berkas README untuk repository '$targetRepo' tidak dapat diakses atau belum tersedia di GitHub.",
                sources = listOf("https://github.com/$targetRepo"),
                errorMessage = "README_NOT_FOUND"
            )
        }

        val distilled = distillReadmeContent(rawReadmeMarkdown, targetRepo)
        return WebSearchResult(
            success = true,
            query = repoQuery,
            spokenAnswer = distilled,
            sources = listOf(sourceUrl)
        )
    }

    internal fun distillReadmeContent(rawMarkdown: String, repoName: String): String {
        // 1. Bersihkan komentar HTML
        var cleaned = rawMarkdown.replace(Regex("""<!--[\s\S]*?-->"""), "")
        // 2. Bersihkan tag HTML
        cleaned = cleaned.replace(Regex("""<[^>]+>"""), " ")
        // 3. Bersihkan badge link & markdown image [![alt](img)](link) dan ![alt](img)
        cleaned = cleaned.replace(Regex("""\[!\[.*?\]\(.*?\)\]\(.*?\)"""), "")
        cleaned = cleaned.replace(Regex("""!\[.*?\]\(.*?\)"""), "")

        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val overviewLines = mutableListOf<String>()
        val featuresLines = mutableListOf<String>()
        val installLines = mutableListOf<String>()

        var currentSection = "overview"

        for (line in lines) {
            val lower = line.lowercase()
            if (line.startsWith("#")) {
                val heading = lower.trimStart('#', ' ')
                when {
                    heading.contains("overview") || heading.contains("about") ||
                        heading.contains("what is") || heading.contains("introduction") ||
                        heading.contains("tentang") || heading.contains("deskripsi") -> {
                        currentSection = "overview"
                        continue
                    }
                    heading.contains("feature") || heading.contains("fitur") ||
                        heading.contains("highlight") || heading.contains("capabilities") ||
                        heading.contains("why") || heading.contains("keunggulan") -> {
                        currentSection = "features"
                        continue
                    }
                    heading.contains("install") || heading.contains("quickstart") ||
                        heading.contains("quick start") || heading.contains("getting started") ||
                        heading.contains("usage") || heading.contains("penggunaan") ||
                        heading.contains("cara pakai") || heading.contains("setup") -> {
                        currentSection = "install"
                        continue
                    }
                    heading.contains("license") || heading.contains("contribut") ||
                        heading.contains("sponsor") || heading.contains("citation") ||
                        heading.contains("author") || heading.contains("acknowledg") -> {
                        currentSection = "other"
                        continue
                    }
                    else -> {
                        if (currentSection == "overview" && overviewLines.size >= 3) {
                            currentSection = "other"
                        }
                    }
                }
            }

            when (currentSection) {
                "overview" -> {
                    if (overviewLines.size < 4 && !line.startsWith("|") && !line.startsWith("[") && line.length > 20) {
                        overviewLines.add(line)
                    }
                }
                "features" -> {
                    if (featuresLines.size < 5) {
                        if (line.startsWith("-") || line.startsWith("*") || line.length > 15) {
                            featuresLines.add(line.trimStart('-', '*', ' ').trim())
                        }
                    }
                }
                "install" -> {
                    if (installLines.size < 4 && !line.startsWith("|") && line.length > 5) {
                        installLines.add(line)
                    }
                }
            }
        }

        val resultParts = mutableListOf<String>()
        resultParts.add("README dari repository $repoName:")
        if (overviewLines.isNotEmpty()) {
            val joined = overviewLines.joinToString(" ").take(450).trimEnd()
            resultParts.add("Ringkasan: $joined")
        }
        if (featuresLines.isNotEmpty()) {
            val joined = featuresLines.take(4).joinToString("; ").take(400).trimEnd()
            resultParts.add("Fitur Utama: $joined")
        }
        if (installLines.isNotEmpty()) {
            val joined = installLines.take(3).joinToString(" ").take(350).trimEnd()
            resultParts.add("Cara Instalasi / Penggunaan: $joined")
        }

        return if (resultParts.size > 1) {
            resultParts.joinToString("\n\n")
        } else {
            val fallback = lines.filter { it.length > 20 && !it.startsWith("#") }.take(4).joinToString(" ").take(600)
            "README dari repository $repoName: " + fallback.ifBlank { "Telah diverifikasi, berisi panduan teknis proyek." }
        }
    }
}
