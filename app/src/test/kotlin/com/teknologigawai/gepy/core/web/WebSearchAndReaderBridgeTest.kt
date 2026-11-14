package com.teknologigawai.gepy.core.web

import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.skills.WebSearchFastPathSkill
import com.teknologigawai.gepy.core.skills.WhatsAppMessageIntent
import com.teknologigawai.gepy.core.system.LiveCallPowerGuard
import com.teknologigawai.gepy.core.system.SamsungBatteryOptimizationGuard
import com.teknologigawai.gepy.features.voice.GeminiLiveClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian pencarian web real-time, RSS fallback, TTL cache, circuit breaker, failover, Wikipedia/DDG & authority filter.
 */
class WebSearchAndReaderBridgeTest {
    @Test
    fun `searchWebRealtime parses Gemini Google Search Grounding response and sources`() = runTest {
        val fakeGeminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "Kurs 1 Dolar AS hari ini berada di kisaran Rp15.850 berdasarkan data pasar terkini."
                      }
                    ]
                  },
                  "groundingMetadata": {
                    "groundingChunks": [
                      {
                        "web": {
                          "uri": "https://www.bi.go.id/id/statistik/informasi-kurs",
                          "title": "Bank Indonesia"
                        }
                      },
                      {
                        "web": {
                          "uri": "https://keuangan.kontan.co.id/kurs",
                          "title": "Kontan Keuangan"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val bridge = WebSearchAndReaderBridge(
            gatewayBaseUrl = "",
            httpTransport = FakeWebHttpTransport(
                postHandler = { url, _ ->
                    if (url.contains("generativelanguage.googleapis.com")) {
                        WebHttpResponse(200, fakeGeminiResponse)
                    } else {
                        WebHttpResponse(503, "{}")
                    }
                }
            )
        )

        val result = bridge.searchWebRealtime(
            query = "berapa kurs dollar ke rupiah hari ini",
            geminiApiKey = "AIzaSyFakeTestKey1234567890"
        )

        assertTrue(result.success)
        assertTrue(result.spokenAnswer.contains("Rp15.850"))
        assertEquals(2, result.sources.size)
        assertTrue(result.sources[0].contains("Bank Indonesia"))
    }

    @Test
    fun `WebSearchFastPathSkill distinguishes pure web queries from Android UI automation commands`() {
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("berapa kurs dollar ke rupiah hari ini"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("bagaimana cuaca di yogyakarta besok pagi"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("tolong baca artikel https://kompas.com/berita-terbaru"))

        // Perintah otomasi UI aplikasi atau baca layar tidak boleh dibajak oleh WebSearchFastPathSkill
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("buka whatsapp kirim pesan ke ibu"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("ketuk tombol kirim di layar"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("apa itu tombol di layar sekarang"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("pasang alarm jam 5 pagi"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("nyalain senter"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("pasang timer 5 menit"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("balas notif wa budi bilang oke"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("ini lagu apa yang lagi diputar"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("turunkan volume media ke 50%"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("buka panel wifi"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("cara nyalain senter hp samsung"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("kenapa timer penting untuk pomodoro"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("apa fungsi panel pengaturan cepat"))

        val extractedUrl = WebSearchFastPathSkill.extractTargetUrl("tolong rangkum isi https://detik.com/edu/123, terima kasih")
        assertNotNull(extractedUrl)
        assertEquals("https://detik.com/edu/123", extractedUrl)

        val bareDomainUrl = WebSearchFastPathSkill.extractTargetUrl("tolong baca artikel kompas.com/tekno/2026")
        assertEquals("https://kompas.com/tekno/2026", bareDomainUrl)
        assertNull(WebSearchFastPathSkill.extractTargetUrl("berita bola hari ini"))

        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("Gepy tolong carikan di internet tentang jadwal timnas"))
        assertEquals(
            "jadwal timnas",
            WebSearchFastPathSkill.extractCleanSearchQuery("Gepy tolong carikan di internet tentang jadwal timnas?")
        )
    }

    @Test
    fun `searchWebRealtime falls back to multi-source RSS and Gemini 3_1 Flash Lite when grounding returns 429`() = runTest {
        val fakeRssXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>Timnas Indonesia Menang 2-0 di Laga Uji Coba - Kompas.com</title>
                  <description>Skuad Garuda berhasil meraih kemenangan meyakinkan di Stadion Utama GBK.</description>
                  <pubDate>Sat, 07 Nov 2026 04:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val fakeSynthesisResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "Timnas Indonesia berhasil menang 2-0 pada laga uji coba terbaru di Stadion Utama GBK."
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val bridge = WebSearchAndReaderBridge(
            gatewayBaseUrl = "",
            httpTransport = FakeWebHttpTransport(
                postHandler = { url, body ->
                    if (body.contains("google_search")) {
                        // Simulasikan kuota google_search Free Tier habis (HTTP 429)
                        WebHttpResponse(429, """{"error":{"code":429,"message":"RESOURCE_EXHAUSTED"}}""")
                    } else if (url.contains("gemini-3.1-flash-lite")) {
                        WebHttpResponse(200, fakeSynthesisResponse)
                    } else {
                        WebHttpResponse(404, "{}")
                    }
                },
                getHandler = { url ->
                    if (url.contains("bing.com") || url.contains("news.google.com")) {
                        WebHttpResponse(200, fakeRssXml)
                    } else {
                        WebHttpResponse(200, "{}")
                    }
                }
            )
        )

        val result = bridge.searchWebRealtime(
            query = "Gepy tolong carikan di internet berita timnas indonesia",
            geminiApiKey = "AIzaSyFakeTestKey1234567890"
        )

        assertTrue(result.success)
        assertEquals("berita timnas indonesia", result.query)
        assertTrue(result.spokenAnswer.contains("menang 2-0"))
        assertTrue(result.sources.isNotEmpty())
    }

    @Test
    fun `WhatsAppMessageIntent strips disambiguation suffix and GeminiLiveClient updates memory context`() {
        val parsed = WhatsAppMessageIntent.tryParse(
            "Kirim WA ke Budi bilang Halo apa kabar (Gunakan pilihan yang dikonfirmasi pengguna: \"Budi Santoso\")"
        )
        assertNotNull(parsed)
        assertEquals("Budi", parsed?.recipient)
        assertEquals("Halo apa kabar", parsed?.message)

        val liveClient = GeminiLiveClient(apiKey = "AIzaSyTestKey")
        liveClient.updateUserMemoryContext("Nama Pengguna: Maulana")
        val setupJson = liveClient.buildSetupMessageJson(userContext = "Nama Pengguna: Maulana").toString()
        assertTrue(setupJson.contains("Nama Pengguna: Maulana"))
    }

    @Test
    fun `LiveCallPowerGuard and SamsungBatteryOptimizationGuard manage state accurately`() {
        val powerGuard = LiveCallPowerGuard(context = null)
        assertFalse(powerGuard.isHeld)

        assertTrue(powerGuard.acquireForActiveSession(timeoutMs = 60_000L))
        assertTrue(powerGuard.isHeld)
        assertTrue(powerGuard.acquiredAtEpochMs > 0L)

        powerGuard.release()
        assertFalse(powerGuard.isHeld)

        assertTrue(SamsungBatteryOptimizationGuard.isSamsungDevice("samsung", "samsung"))
        assertTrue(SamsungBatteryOptimizationGuard.isSamsungDevice("Samsung Electronics", "galaxy"))
        assertFalse(SamsungBatteryOptimizationGuard.isSamsungDevice("Google", "Pixel"))
    }

    @Test
    fun `DomainAuthorityFilter ranks Tier 1 official and Tier 2 verified press while blocking unreliable blogs`() {
        val biEval = DomainAuthorityFilter.evaluateSource(
            urlOrDomain = "https://www.bi.go.id/id/statistik/informasi-kurs",
            publisherName = "Bank Indonesia"
        )
        assertEquals(CredibilityTier.TIER_1_OFFICIAL, biEval.tier)
        assertEquals(100, biEval.score)
        assertTrue(biEval.isTrusted)

        val antaraEval = DomainAuthorityFilter.evaluateSource(
            urlOrDomain = "https://www.antaranews.com/berita/12345/ekonomi",
            publisherName = "ANTARA News"
        )
        assertEquals(CredibilityTier.TIER_2_VERIFIED_PRESS, antaraEval.tier)
        assertTrue(antaraEval.score >= 85)
        assertTrue(antaraEval.isTrusted)

        val blogEval = DomainAuthorityFilter.evaluateSource(
            urlOrDomain = "https://cerita-opini-acak.blogspot.com/2026/info.html",
            publisherName = "Blog Pribadi"
        )
        assertEquals(CredibilityTier.BLOCKED_UNRELIABLE, blogEval.tier)
        assertTrue(blogEval.isBlocked)

        val kompasianaEval = DomainAuthorityFilter.evaluateSource(
            urlOrDomain = "https://www.kompasiana.com/user123/opini",
            publisherName = "Kompasiana"
        )
        assertEquals(CredibilityTier.BLOCKED_UNRELIABLE, kompasianaEval.tier)
        assertTrue(kompasianaEval.isBlocked)

        val rawSnippets = listOf(
            WebSearchSnippet(
                title = "Rumor Hoaks Tanpa Sumber - Blogspot",
                snippet = "Kabar burung tidak jelas",
                source = "blogspot.com",
                credibilityTier = blogEval.tier,
                authorityScore = blogEval.score
            ),
            WebSearchSnippet(
                title = "Berita Ekonomi Nasional - ANTARA News",
                snippet = "Pertumbuhan ekonomi kuartal ini stabil.",
                source = antaraEval.canonicalPublisher,
                credibilityTier = antaraEval.tier,
                authorityScore = antaraEval.score
            ),
            WebSearchSnippet(
                title = "Pengumuman Resmi Suku Bunga BI - Bank Indonesia",
                snippet = "BI-Rate dipertahankan pada level stabil.",
                source = biEval.canonicalPublisher,
                credibilityTier = biEval.tier,
                authorityScore = biEval.score
            )
        )

        val filtered = DomainAuthorityFilter.filterAndRankSnippets(rawSnippets, maxSelected = 5)
        assertEquals(2, filtered.size)
        assertEquals(CredibilityTier.TIER_1_OFFICIAL, filtered[0].credibilityTier)
        assertEquals(CredibilityTier.TIER_2_VERIFIED_PRESS, filtered[1].credibilityTier)
    }

    @Test
    fun `DomainAuthorityFilter extractLeadParagraphs strips boilerplate and keeps inverted pyramid facts`() {
        val noisyMarkdown = """
            # Judul Berita Utama
            ![Foto Ilustrasi](https://example.com/img.jpg)
            Baca juga: [Berita Lainnya yang Tidak Relevan](https://example.com/other)
            Ikuti kami di WhatsApp Channel untuk update harian.
            
            Jakarta (ANTARA) - Badan Meteorologi, Klimatologi, dan Geofisika (BMKG) memprakirakan cuaca di wilayah DKI Jakarta dan sekitarnya akan mengalami hujan ringan pada sore hari dengan suhu udara berkisar antara 24 hingga 31 derajat Celcius.
            
            Masyarakat diimbau untuk tetap waspada terhadap potensi angin kencang sesaat di wilayah Jakarta Selatan dan Jakarta Timur menjelang malam hari.
            
            Copyright 2026 All Rights Reserved.
        """.trimIndent()

        val cleanLead = DomainAuthorityFilter.extractLeadParagraphs(noisyMarkdown, maxChars = 600)
        assertTrue(cleanLead.contains("Badan Meteorologi, Klimatologi, dan Geofisika"))
        assertTrue(cleanLead.contains("24 hingga 31 derajat Celcius"))
        assertFalse(cleanLead.contains("Baca juga"))
        assertFalse(cleanLead.contains("Copyright"))
    }

    @Test
    fun `searchWebRealtime uses Smart TTL Cache for zero-token instant response and enriches #1 article via 2-hop reader`() = runTest {
        var httpCallCount = 0
        var capturedSynthesisPrompt = ""

        val fakeBingRss = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>BMKG: Prakiraan Cuaca Jakarta Hari Ini Cerah Berawan - BMKG</title>
                  <link>https://www.bmkg.go.id/cuaca/prakiraan-jakarta</link>
                  <description>Ringkasan singkat cuaca Jakarta.</description>
                  <pubDate>Sat, 07 Nov 2026 06:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>Ramalan Cuaca Versi Blog - Blogspot</title>
                  <link>https://ramalan-abal.blogspot.com/cuaca</link>
                  <description>Opini pribadi tentang cuaca.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val fakeJinaLeadArticle = """
            {
              "code": 200,
              "data": {
                "title": "Prakiraan Cuaca Resmi BMKG DKI Jakarta",
                "content": "Badan Meteorologi, Klimatologi, dan Geofisika (BMKG) melaporkan cuaca DKI Jakarta pada siang hari cerah berawan dengan suhu 29 derajat Celcius dan kelembapan 75 persen."
              }
            }
        """.trimIndent()

        val fakeSynthesisJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "Menurut data resmi BMKG, cuaca DKI Jakarta hari ini cerah berawan dengan suhu sekitar 29 derajat Celcius."
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val bridge = WebSearchAndReaderBridge(
            gatewayBaseUrl = "",
            cacheTtlMs = 20 * 60_000L,
            httpTransport = FakeWebHttpTransport(
                postHandler = { url, body ->
                    httpCallCount++
                    if (body.contains("google_search")) {
                        WebHttpResponse(429, "{}")
                    } else if (url.contains("gemini-3.1-flash-lite")) {
                        capturedSynthesisPrompt = body
                        WebHttpResponse(200, fakeSynthesisJson)
                    } else {
                        WebHttpResponse(404, "{}")
                    }
                },
                getHandler = { url ->
                    httpCallCount++
                    when {
                        url.contains("bing.com/search") -> WebHttpResponse(200, fakeBingRss)
                        url.contains("r.jina.ai/https://www.bmkg.go.id") -> WebHttpResponse(200, fakeJinaLeadArticle)
                        else -> WebHttpResponse(200, "")
                    }
                }
            )
        )

        val firstRes = bridge.searchWebRealtime(
            query = "bagaimana cuaca jakarta hari ini",
            geminiApiKey = "AIzaSyFakeTestKey1234567890"
        )
        assertTrue(firstRes.success)
        assertTrue(firstRes.spokenAnswer.contains("BMKG"))
        assertTrue(capturedSynthesisPrompt.contains("Kutipan Fakta Utama BMKG Resmi"))
        assertTrue(capturedSynthesisPrompt.contains("29 derajat Celcius"))
        assertFalse(capturedSynthesisPrompt.contains("ramalan-abal.blogspot.com"))

        val callsAfterFirstQuery = httpCallCount
        assertTrue(callsAfterFirstQuery > 0)

        // Kueri kedua yang sama dalam rentang 20 menit harus diambil dari Smart TTL Cache (0 HTTP calls tambahan!)
        val secondRes = bridge.searchWebRealtime(
            query = "Tolong carikan di internet bagaimana cuaca jakarta hari ini?",
            geminiApiKey = "AIzaSyFakeTestKey1234567890"
        )
        assertTrue(secondRes.success)
        assertEquals(firstRes.spokenAnswer, secondRes.spokenAnswer)
        assertEquals(callsAfterFirstQuery, httpCallCount)
    }

    @Test
    fun `parseDuckDuckGoJson extracts direct instant answer and encyclopedic abstract`() {
        val bridge = WebSearchAndReaderBridge(gatewayBaseUrl = "")

        val directAnswerJson = """
            {
              "Heading": "Nilai Tukar USD",
              "Answer": "1 Dolar AS = 15.850 Rupiah Indonesia",
              "AbstractText": "",
              "AbstractURL": "https://duckduckgo.com/?q=1+usd+to+idr"
            }
        """.trimIndent()

        val parsedAnswer = bridge.parseDuckDuckGoJson(directAnswerJson)
        assertEquals(1, parsedAnswer.size)
        assertEquals("Nilai Tukar USD", parsedAnswer[0].title)
        assertTrue(parsedAnswer[0].snippet.contains("15.850"))
        assertEquals(CredibilityTier.TIER_2_VERIFIED_PRESS, parsedAnswer[0].credibilityTier)

        val abstractJson = """
            {
              "Heading": "Gunung Semeru",
              "AbstractText": "Gunung Semeru adalah sebuah gunung berapi kerucut di Jawa Timur, Indonesia.",
              "AbstractSource": "Wikipedia",
              "AbstractURL": "https://id.wikipedia.org/wiki/Semeru",
              "RelatedTopics": [
                {
                  "FirstURL": "https://duckduckgo.com/c/Gunung_berapi_Indonesia",
                  "Text": "Daftar gunung berapi aktif di wilayah kepulauan Indonesia"
                }
              ]
            }
        """.trimIndent()

        val parsedAbstract = bridge.parseDuckDuckGoJson(abstractJson)
        assertTrue(parsedAbstract.isNotEmpty())
        assertEquals("Gunung Semeru", parsedAbstract[0].title)
        assertTrue(parsedAbstract[0].snippet.contains("gunung berapi kerucut"))
        assertEquals("Wikipedia Indonesia", parsedAbstract[0].source)
        assertEquals(CredibilityTier.TIER_3_ACADEMIC_REFERENCE, parsedAbstract[0].credibilityTier)
    }

    @Test
    fun `parseWikipediaRestSummaryJson extracts clean formal article summary`() {
        val bridge = WebSearchAndReaderBridge(gatewayBaseUrl = "")
        val wikiRestJson = """
            {
              "title": "Mahameru",
              "extract": "Puncak Mahameru adalah titik tertinggi di Gunung Semeru dengan ketinggian 3.676 meter di atas permukaan laut.",
              "content_urls": {
                "desktop": {
                  "page": "https://id.wikipedia.org/wiki/Semeru"
                }
              }
            }
        """.trimIndent()

        val snippet = bridge.parseWikipediaRestSummaryJson(wikiRestJson)
        assertNotNull(snippet)
        assertEquals("Mahameru", snippet?.title)
        assertTrue(snippet?.snippet?.contains("3.676 meter") == true)
        assertEquals("Wikipedia Indonesia", snippet?.source)
        assertEquals(CredibilityTier.TIER_3_ACADEMIC_REFERENCE, snippet?.credibilityTier)
    }

    @Test
    fun `searchWebRealtime trips circuit breaker on 429 and bypasses grounding on next query`() = runTest {
        var groundingAttemptCount = 0
        var currentClockMs = 1_000_000L

        val bridge = WebSearchAndReaderBridge(
            gatewayBaseUrl = "",
            cacheTtlMs = 0L, // Nonaktifkan result cache agar kita bisa menguji pemanggilan grounding berulang
            clockMs = { currentClockMs },
            httpTransport = FakeWebHttpTransport(
                postHandler = { _, body ->
                    if (body.contains("google_search")) {
                        groundingAttemptCount++
                        WebHttpResponse(429, """{"error":{"code":429,"message":"RESOURCE_EXHAUSTED"}}""")
                    } else {
                        WebHttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"Jawaban ringkas."}]}}]}""")
                    }
                },
                getHandler = { _ ->
                    WebHttpResponse(200, """
                        <?xml version="1.0" encoding="utf-8"?>
                        <rss version="2.0">
                          <channel>
                            <item>
                              <title>Berita Resmi - ANTARA News</title>
                              <description>Informasi penting nasional.</description>
                            </item>
                          </channel>
                        </rss>
                    """.trimIndent())
                }
            )
        )

        assertFalse(bridge.isGroundingCircuitBreakerActive())

        // Panggilan 1: Grounding dipanggil dan mengembalikan 429
        val res1 = bridge.searchWebRealtime(
            query = "berita gempa bumi hari ini",
            geminiApiKey = "AIzaSyFakeKey"
        )
        assertTrue(res1.success)
        assertEquals(1, groundingAttemptCount)
        assertTrue(bridge.isGroundingCircuitBreakerActive())

        // Panggilan 2: Circuit breaker aktif -> grounding HARUS di-bypass (0 percobaan grounding baru!)
        val res2 = bridge.searchWebRealtime(
            query = "prakiraan cuaca esok hari",
            geminiApiKey = "AIzaSyFakeKey"
        )
        assertTrue(res2.success)
        assertEquals(1, groundingAttemptCount) // Tetap 1! Tidak mencoba memanggil grounding lagi

        // Majukan waktu 31 menit (melewati TTL circuit breaker 30 menit)
        currentClockMs += 31 * 60_000L
        assertFalse(bridge.isGroundingCircuitBreakerActive())
    }

    @Test
    fun `searchWebRealtime skips Jina Reader when aggregated snippets are already rich`() = runTest {
        var jinaReaderCalled = false
        val longRichSnippet = "A".repeat(350)

        val bridge = WebSearchAndReaderBridge(
            gatewayBaseUrl = "",
            httpTransport = FakeWebHttpTransport(
                postHandler = { _, body ->
                    if (body.contains("google_search")) {
                        WebHttpResponse(429, "{}")
                    } else {
                        WebHttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"Jawaban sintetis terverifikasi."}]}}]}""")
                    }
                },
                getHandler = { url ->
                    if (url.contains("r.jina.ai")) {
                        jinaReaderCalled = true
                    }
                    if (url.contains("news.google.com") || url.contains("bing.com")) {
                        WebHttpResponse(200, """
                            <?xml version="1.0" encoding="utf-8"?>
                            <rss version="2.0">
                              <channel>
                                <item>
                                  <title>Laporan Lengkap Ekonomi - ANTARA News</title>
                                  <link>https://www.antaranews.com/berita/lengkap</link>
                                  <description>$longRichSnippet</description>
                                </item>
                              </channel>
                            </rss>
                        """.trimIndent())
                    } else {
                        WebHttpResponse(200, "{}")
                    }
                }
            )
        )

        val res = bridge.searchWebRealtime(
            query = "kondisi ekonomi nasional terkini",
            geminiApiKey = "AIzaSyFakeKey"
        )
        assertTrue(res.success)
        assertFalse("Jina Reader seharusnya dilewati jika snippet sudah kaya fakta (hemat 3s)", jinaReaderCalled)
    }

    @Test
    fun `WebSearchFastPathSkill handles broad agentic queries including science tutorials and comparisons`() {
        // Sains & Fakta Dunia
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("kenapa dinosaurus punah"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("mengapa langit berwarna biru"))

        // Tutorial & Panduan
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("bagaimana cara merawat kucing anggora"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("cara membuat donat kentang empuk"))

        // Komparasi & Rekomendasi
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("apa perbedaan kotlin dan java"))
        assertTrue(WebSearchFastPathSkill.isPureWebInformationQuery("rekomendasi laptop untuk coding tahun ini"))

        // Kontrol Negatif: Perintah yang menargetkan aksi fisik aplikasi di HP
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("buka aplikasi whatsapp"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("kirim pesan wa ke Maulana"))
        assertFalse(WebSearchFastPathSkill.isPureWebInformationQuery("baca isi layar sekarang"))
    }

    @Test
    fun `searchWebRealtime performs dual-key failover on HTTP 429 without tripping circuit breaker`() = runTest {
        GeminiKeyPool.resetCooldownsForTest()
        val attemptedKeys = mutableListOf<String>()
        var groundingAttemptCount = 0

        val fakeGeminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "Menurut Bank Indonesia hari ini, kurs 1 Dolar AS berada di angka Rp16.100."
                      }
                    ]
                  },
                  "groundingMetadata": {
                    "groundingChunks": [
                      {
                        "web": {
                          "uri": "https://www.bi.go.id/id/statistik/informasi-kurs",
                          "title": "Bank Indonesia"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val failoverTransport = object : WebHttpTransport {
            override suspend fun postJson(
                url: String,
                headers: Map<String, String>,
                jsonBody: String
            ): WebHttpResponse {
                if (url.contains("generativelanguage.googleapis.com")) {
                    groundingAttemptCount++
                    val usedKey = headers["x-goog-api-key"].orEmpty()
                    attemptedKeys.add(usedKey)
                    return if (usedKey == "web_k1_429") {
                        WebHttpResponse(429, """{"error":{"code":429,"message":"RESOURCE_EXHAUSTED"}}""")
                    } else {
                        WebHttpResponse(200, fakeGeminiResponse)
                    }
                }
                return WebHttpResponse(404, "{}")
            }

            override suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                return WebHttpResponse(404, "")
            }
        }

        val bridge = WebSearchAndReaderBridge(
            gatewayBaseUrl = "",
            httpTransport = failoverTransport,
            cacheTtlMs = 0L
        )

        val result = bridge.searchWebRealtime(
            query = "kurs dollar rupiah hari ini",
            geminiApiKey = "web_k1_429",
            geminiSecondaryApiKey = "web_k2_ok"
        )

        assertTrue(result.success)
        assertTrue(result.spokenAnswer.contains("Rp16.100"))
        assertEquals(2, groundingAttemptCount)
        assertEquals(listOf("web_k1_429", "web_k2_ok"), attemptedKeys)
        assertTrue(GeminiKeyPool.isInCooldown("web_k1_429"))
        assertFalse(GeminiKeyPool.isInCooldown("web_k2_ok"))
        assertFalse(bridge.isGroundingCircuitBreakerActive())

        GeminiKeyPool.resetCooldownsForTest()
    }
}
