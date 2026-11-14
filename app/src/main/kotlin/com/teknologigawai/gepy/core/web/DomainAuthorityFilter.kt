package com.teknologigawai.gepy.core.web

import java.net.URI
import java.util.Locale

/** Tingkat kredibilitas sumber informasi web pada arsitektur *Credible Agentic Grounding*. */
enum class CredibilityTier(val baseScore: Int, val labelId: String) {
    /** Lembaga resmi pemerintah, bank sentral, otoritas negara, dan organisasi internasional resmi. */
    TIER_1_OFFICIAL(100, "Otoritas Resmi"),

    /** Kantor berita nasional (LKBN Antara) dan media pers arus utama terverifikasi Dewan Pers. */
    TIER_2_VERIFIED_PRESS(85, "Pers Terverifikasi"),

    /** Ensiklopedia terkurasi, jurnal ilmiah, dan institusi akademik resmi (`.ac.id` / `.edu`). */
    TIER_3_ACADEMIC_REFERENCE(75, "Referensi Akademik & Ensiklopedia"),

    /** Situs web umum yang tidak termasuk daftar blokir namun bukan lembaga resmi/pers utama. */
    TIER_4_GENERAL_WEB(40, "Situs Web Umum"),

    /** Blog pribadi tanpa redaksi, opini pengguna tidak terverifikasi, forum anonim, atau situs spam. */
    BLOCKED_UNRELIABLE(-100, "Sumber Tidak Terverifikasi (Diblokir)")
}

/** Hasil evaluasi otoritas dan kredibilitas suatu sumber web. */
data class SourceAuthorityEvaluation(
    val domain: String,
    val canonicalPublisher: String,
    val tier: CredibilityTier,
    val score: Int
) {
    val isTrusted: Boolean
        get() = tier == CredibilityTier.TIER_1_OFFICIAL ||
            tier == CredibilityTier.TIER_2_VERIFIED_PRESS ||
            tier == CredibilityTier.TIER_3_ACADEMIC_REFERENCE

    val isBlocked: Boolean
        get() = tier == CredibilityTier.BLOCKED_UNRELIABLE || score < 0
}

/**
 * Gerbang Kurasi Kredibilitas Sumber & **Lean Context Engineering** (`0 Token` Overhead):
 * 1. Mengevaluasi domain dan nama penerbit berita secara lokal di perangkat Android.
 * 2. Memblokir blog pribadi (`blogspot`, `wordpress`, `kompasiana`), forum anonim, dan situs clickbait atau spam.
 * 3. Memprioritaskan otoritas resmi (`.go.id`, BMKG, Bank Indonesia, OJK, BPS) serta pers nasional
 *    terverifikasi (Antara, Kompas, Tempo, CNN Indonesia, Detik, Bisnis, Kontan, Republika).
 * 4. Mengekstrak **2–3 paragraf pembuka (*Lead Paragraphs / Piramida Terbalik*)** bebas sampah navigasi
 *    agar konsumsi token LLM tetap sangat hemat (`~350–500 token`) namun kaya fakta akurat.
 */
object DomainAuthorityFilter {

    private val TIER_1_EXACT_OR_SUFFIX_DOMAINS = setOf(
        "bi.go.id",
        "bmkg.go.id",
        "ojk.go.id",
        "bps.go.id",
        "kemkes.go.id",
        "kemenkeu.go.id",
        "kemdikbud.go.id",
        "kominfo.go.id",
        "komdigi.go.id",
        "setkab.go.id",
        "setneg.go.id",
        "presidenri.go.id",
        "kpk.go.id",
        "polri.go.id",
        "bpom.go.id",
        "bnpb.go.id",
        "esdm.go.id",
        "kemenag.go.id",
        "kemenhub.go.id",
        "kemlu.go.id",
        "bkn.go.id",
        "pajak.go.id",
        "beacukai.go.id",
        "imigrasi.go.id",
        "idx.co.id",
        "ksei.co.id",
        "who.int",
        "un.org",
        "imf.org",
        "worldbank.org",
        "asean.org"
    )

    private val TIER_1_PUBLISHER_KEYWORDS = listOf(
        "bank indonesia",
        "bmkg",
        "otoritas jasa keuangan",
        "badan pusat statistik",
        "kementerian kesehatan",
        "kemenkes",
        "kementerian keuangan",
        "kemenkeu",
        "sekretariat kabinet",
        "setkab",
        "bpom",
        "bnpb",
        "bursa efek indonesia",
        "world health organization"
    )

    private val TIER_2_VERIFIED_PRESS_DOMAINS = mapOf(
        "antaranews.com" to "ANTARA News",
        "kompas.com" to "Kompas.com",
        "kompas.id" to "Harian Kompas",
        "tempo.co" to "Tempo.co",
        "cnnindonesia.com" to "CNN Indonesia",
        "detik.com" to "Detik.com",
        "cnbcindonesia.com" to "CNBC Indonesia",
        "bisnis.com" to "Bisnis.com",
        "kontan.co.id" to "Kontan.co.id",
        "republika.co.id" to "Republika",
        "liputan6.com" to "Liputan6.com",
        "mediaindonesia.com" to "Media Indonesia",
        "katadata.co.id" to "Katadata",
        "tirto.id" to "Tirto.id",
        "kumparan.com" to "Kumparan",
        "idntimes.com" to "IDN Times",
        "jawapos.com" to "Jawa Pos",
        "pikiran-rakyat.com" to "Pikiran Rakyat",
        "suara.com" to "Suara.com",
        "sindonews.com" to "SINDOnews",
        "okezone.com" to "Okezone",
        "tribunnews.com" to "Tribunnews",
        "tvonenews.com" to "tvOneNews",
        "metrotvnews.com" to "MetroTVNews",
        "rri.co.id" to "RRI",
        "tvri.go.id" to "TVRI",
        "beritasatu.com" to "BeritaSatu",
        "investor.id" to "Investor.id",
        "hukumonline.com" to "Hukumonline",
        "bbc.com" to "BBC News",
        "reuters.com" to "Reuters",
        "bloomberg.com" to "Bloomberg",
        "apnews.com" to "Associated Press",
        "aljazeera.com" to "Al Jazeera",
        "goal.com" to "Goal Indonesia",
        "bola.com" to "Bola.com",
        "bola.net" to "Bola.net"
    )

    private val TIER_2_PUBLISHER_ALIASES = mapOf(
        "antara" to "ANTARA News",
        "antaranews" to "ANTARA News",
        "lkbn antara" to "ANTARA News",
        "kompas.com" to "Kompas.com",
        "kompas.id" to "Harian Kompas",
        "harian kompas" to "Harian Kompas",
        "kompas" to "Kompas.com",
        "tempo.co" to "Tempo.co",
        "tempo" to "Tempo.co",
        "cnn indonesia" to "CNN Indonesia",
        "detikcom" to "Detik.com",
        "detik.com" to "Detik.com",
        "detiknews" to "Detik.com",
        "detikfinance" to "Detik.com",
        "detiksport" to "Detik.com",
        "cnbc indonesia" to "CNBC Indonesia",
        "bisnis.com" to "Bisnis.com",
        "bisnis indonesia" to "Bisnis.com",
        "kontan.co.id" to "Kontan.co.id",
        "kontan" to "Kontan.co.id",
        "republika" to "Republika",
        "liputan6.com" to "Liputan6.com",
        "liputan6" to "Liputan6.com",
        "media indonesia" to "Media Indonesia",
        "katadata" to "Katadata",
        "tirto.id" to "Tirto.id",
        "kumparan" to "Kumparan",
        "idn times" to "IDN Times",
        "jawa pos" to "Jawa Pos",
        "pikiran rakyat" to "Pikiran Rakyat",
        "suara.com" to "Suara.com",
        "sindonews" to "SINDOnews",
        "okezone" to "Okezone",
        "rri" to "RRI",
        "tvri" to "TVRI",
        "beritasatu" to "BeritaSatu",
        "hukumonline" to "Hukumonline",
        "reuters" to "Reuters",
        "bbc" to "BBC News",
        "bloomberg" to "Bloomberg",
        "goal.com" to "Goal Indonesia"
    )

    private val TIER_3_ACADEMIC_DOMAINS = setOf(
        "wikipedia.org",
        "id.wikipedia.org",
        "en.wikipedia.org",
        "britannica.com",
        "scholar.google.com",
        "ncbi.nlm.nih.gov",
        "pubmed.ncbi.nlm.nih.gov",
        "arxiv.org",
        "nature.com",
        "sciencedirect.com",
        "kemkes.go.id",
        "lipi.go.id",
        "brin.go.id"
    )

    private val BLOCKED_UNRELIABLE_DOMAINS = listOf(
        "blogspot.",
        "blogger.com",
        "wordpress.com",
        "tumblr.com",
        "medium.com",
        "kompasiana.com",
        "retizen.republika.co.id",
        "kumparan.com/user",
        "kaskus.co.id",
        "quora.com",
        "reddit.com",
        "scribd.com",
        "academia.edu",
        "pinterest.",
        "wattpad.com",
        "brainly.co.id",
        "coursehero.com",
        "studocu.com",
        "tiktok.com",
        "facebook.com",
        "instagram.com",
        "threads.net",
        "x.com",
        "twitter.com"
    )

    private val SPAM_OR_GAMBLING_REGEX = Regex(
        """\b(slot\s+gacor|judi\s+online|togel\s+hari\s+ini|rtp\s+live|maxwin|situs\s+slot|deposit\s+pulsa\s+tanpa\s+potongan|pinjol\s+ilegal|bokep|link\s+alternatif\s+slot)\b""",
        RegexOption.IGNORE_CASE
    )

    private val BOILERPLATE_LINE_REGEX = Regex(
        """^(baca\s+juga|lihat\s+juga|simak\s+juga|pilihan\s+editor|berita\s+terkait|artikel\s+terkait|iklan|advertisement|sponsored|share|bagikan|ikuti\s+kami|follow\s+us|unduh\s+aplikasi|copyright|hak\s+cipta|all\s+rights\s+reserved|dapatkan\s+update|gabung\s+whatsapp\s+channel|klik\s+di\s+sini|tags?:|topik:|penulis\s*:|editor\s*:|reporter\s*:|sumber\s*:|foto\s*:| keterangan\s+foto)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Mengekstrak host/domain bersih dari URL lengkap maupun string domain. */
    fun extractHost(urlOrDomain: String): String {
        val trimmed = urlOrDomain.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return ""
        return try {
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val host = URI(withScheme).host?.lowercase(Locale.ROOT).orEmpty()
            host.removePrefix("www.").removePrefix("m.").removePrefix("amp.")
        } catch (_: Throwable) {
            trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore('?')
                .removePrefix("www.")
                .removePrefix("m.")
        }
    }

    /**
     * Mengevaluasi kredibilitas suatu sumber berdasarkan URL/domain, nama penerbit, dan judul.
     */
    fun evaluateSource(
        urlOrDomain: String,
        publisherName: String = "",
        titleAndSnippet: String = ""
    ): SourceAuthorityEvaluation {
        val rawLowerUrl = urlOrDomain.trim().lowercase(Locale.ROOT)
        val host = extractHost(urlOrDomain)
        val pubLower = publisherName.trim().lowercase(Locale.ROOT)

        // 1. Cek konten spam / judi / tidak layak
        if (SPAM_OR_GAMBLING_REGEX.containsMatchIn(titleAndSnippet) ||
            SPAM_OR_GAMBLING_REGEX.containsMatchIn(rawLowerUrl)
        ) {
            return SourceAuthorityEvaluation(
                domain = host,
                canonicalPublisher = publisherName.ifBlank { host },
                tier = CredibilityTier.BLOCKED_UNRELIABLE,
                score = CredibilityTier.BLOCKED_UNRELIABLE.baseScore
            )
        }

        // 2. Cek daftar domain blog pribadi / UGC tidak terverifikasi (seperti Kompasiana / Blogspot)
        if (BLOCKED_UNRELIABLE_DOMAINS.any { blocked -> rawLowerUrl.contains(blocked) || host.contains(blocked) } ||
            pubLower.contains("kompasiana") ||
            pubLower.contains("blogspot") ||
            pubLower.contains("wordpress") ||
            pubLower.contains("brainly")
        ) {
            return SourceAuthorityEvaluation(
                domain = host,
                canonicalPublisher = publisherName.ifBlank { host },
                tier = CredibilityTier.BLOCKED_UNRELIABLE,
                score = CredibilityTier.BLOCKED_UNRELIABLE.baseScore
            )
        }

        // 3. Cek Tier 1: Otoritas Resmi Pemerintah (.go.id, Bank Indonesia, BMKG, OJK, BPS, WHO)
        val isGovDomain = host.endsWith(".go.id") ||
            host.endsWith(".gov") ||
            host.endsWith(".mil.id") ||
            TIER_1_EXACT_OR_SUFFIX_DOMAINS.any { d -> host == d || host.endsWith(".$d") }
        val isGovPublisher = TIER_1_PUBLISHER_KEYWORDS.any { kw -> pubLower.contains(kw) }

        if (isGovDomain || isGovPublisher) {
            val label = when {
                host.contains("bi.go.id") || pubLower.contains("bank indonesia") -> "Bank Indonesia (bi.go.id)"
                host.contains("bmkg.go.id") || pubLower.contains("bmkg") -> "BMKG Resmi"
                host.contains("ojk.go.id") || pubLower.contains("ojk") -> "OJK Resmi"
                host.contains("bps.go.id") || pubLower.contains("badan pusat statistik") -> "BPS Resmi"
                host.contains("kemkes.go.id") || pubLower.contains("kemenkes") -> "Kementerian Kesehatan RI"
                host.contains("kemenkeu.go.id") || pubLower.contains("kemenkeu") -> "Kementerian Keuangan RI"
                publisherName.isNotBlank() && !publisherName.equals("Bing Web", ignoreCase = true) -> publisherName.trim()
                host.isNotBlank() -> "Portal Resmi Pemerintah ($host)"
                else -> "Lembaga Resmi Pemerintah"
            }
            return SourceAuthorityEvaluation(
                domain = host,
                canonicalPublisher = label,
                tier = CredibilityTier.TIER_1_OFFICIAL,
                score = CredibilityTier.TIER_1_OFFICIAL.baseScore
            )
        }

        // 4. Cek Tier 2: Kantor Berita Nasional & Pers Terverifikasi Dewan Pers
        for ((pressDomain, canonicalName) in TIER_2_VERIFIED_PRESS_DOMAINS) {
            if (host == pressDomain || host.endsWith(".$pressDomain")) {
                val bonus = if (pressDomain == "antaranews.com") 5 else 0
                return SourceAuthorityEvaluation(
                    domain = host,
                    canonicalPublisher = canonicalName,
                    tier = CredibilityTier.TIER_2_VERIFIED_PRESS,
                    score = CredibilityTier.TIER_2_VERIFIED_PRESS.baseScore + bonus
                )
            }
        }
        for ((alias, canonicalName) in TIER_2_PUBLISHER_ALIASES) {
            if (pubLower == alias || pubLower.contains(alias)) {
                val bonus = if (canonicalName == "ANTARA News") 5 else 0
                return SourceAuthorityEvaluation(
                    domain = host,
                    canonicalPublisher = canonicalName,
                    tier = CredibilityTier.TIER_2_VERIFIED_PRESS,
                    score = CredibilityTier.TIER_2_VERIFIED_PRESS.baseScore + bonus
                )
            }
        }

        // 5. Cek Tier 3: Ensiklopedia Terkurasi & Institusi Akademik (.ac.id / .edu / Wikipedia)
        val isAcademicOrWiki = host.endsWith(".ac.id") ||
            host.endsWith(".edu") ||
            TIER_3_ACADEMIC_DOMAINS.any { d -> host == d || host.endsWith(".$d") } ||
            pubLower.contains("wikipedia") ||
            pubLower.contains("universitas") ||
            pubLower.contains("institut")
        if (isAcademicOrWiki) {
            val label = when {
                host.contains("wikipedia.org") || pubLower.contains("wikipedia") -> "Wikipedia Indonesia"
                publisherName.isNotBlank() && !publisherName.equals("Bing Web", ignoreCase = true) -> publisherName.trim()
                host.isNotBlank() -> host
                else -> "Referensi Akademik"
            }
            return SourceAuthorityEvaluation(
                domain = host,
                canonicalPublisher = label,
                tier = CredibilityTier.TIER_3_ACADEMIC_REFERENCE,
                score = CredibilityTier.TIER_3_ACADEMIC_REFERENCE.baseScore
            )
        }

        // 6. Tier 4: Situs Web Umum yang tidak masuk daftar blokir
        val fallbackLabel = when {
            publisherName.isNotBlank() &&
                !publisherName.equals("Bing Web", ignoreCase = true) &&
                !publisherName.equals("Google News ID", ignoreCase = true) -> publisherName.trim()
            host.isNotBlank() -> host
            else -> "Sumber Web"
        }
        return SourceAuthorityEvaluation(
            domain = host,
            canonicalPublisher = fallbackLabel,
            tier = CredibilityTier.TIER_4_GENERAL_WEB,
            score = CredibilityTier.TIER_4_GENERAL_WEB.baseScore
        )
    }

    /**
     * Menyaring sumber tidak kredibel (`BLOCKED_UNRELIABLE`) dan mengurutkan potongan hasil pencarian
     * dari tingkat otoritas tertinggi (`TIER_1` -> `TIER_2` -> `TIER_3` -> `TIER_4`).
     * Jika sudah ada minimal 2 sumber terverifikasi (`TIER_1`..`TIER_3`), sumber `TIER_4` tidak tepercaya
     * dibuang agar konteks LLM tetap murni berisi fakta valid dan hemat token.
     */
    internal fun filterAndRankSnippets(
        snippets: List<WebSearchSnippet>,
        maxSelected: Int = 5
    ): List<WebSearchSnippet> {
        val nonBlocked = snippets
            .filterNot { it.credibilityTier == CredibilityTier.BLOCKED_UNRELIABLE || it.authorityScore < 0 }
            .distinctBy { it.title.lowercase(Locale.ROOT).trim() }

        val trustedOnly = nonBlocked.filter {
            it.credibilityTier == CredibilityTier.TIER_1_OFFICIAL ||
                it.credibilityTier == CredibilityTier.TIER_2_VERIFIED_PRESS ||
                it.credibilityTier == CredibilityTier.TIER_3_ACADEMIC_REFERENCE
        }

        val pool = if (trustedOnly.size >= 2) {
            trustedOnly
        } else {
            nonBlocked
        }

        return pool
            .sortedByDescending { it.authorityScore }
            .take(maxSelected)
    }

    /**
     * **Lean Context Engineering — Lead Paragraph Extractor (Prinsip Jurnalisme Piramida Terbalik)**:
     * Membersihkan hasil Jina Reader dari menu navigasi, *footer*, iklan, dan daftar tautan,
     * lalu hanya mengambil 2–3 paragraf pembuka yang memuat inti fakta (5W+1H) dengan batas
     * maksimum [maxChars] (`~350 token`).
     */
    fun extractLeadParagraphs(rawMarkdown: String, maxChars: Int = 1_400): String {
        if (rawMarkdown.isBlank()) return ""

        val cleanedLines = mutableListOf<String>()
        var accumulatedChars = 0

        val lines = rawMarkdown
            .replace("\r\n", "\n")
            .lineSequence()

        for (rawLine in lines) {
            // Hapus gambar markdown, URL tautan panjang, dan tag HTML
            val stripped = rawLine
                .replace(Regex("""!\[[^\]]*]\([^)]+\)"""), "")
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace(Regex("""https?://\S+"""), "")
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(Regex("""^[#>*\-+|\s]+"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (stripped.length < 45) continue
            if (BOILERPLATE_LINE_REGEX.containsMatchIn(stripped)) continue
            if (SPAM_OR_GAMBLING_REGEX.containsMatchIn(stripped)) continue

            // Pastikan baris memiliki struktur kalimat nyata (mengandung titik, koma, atau angka fakta)
            val wordCount = stripped.split(' ').count { it.length > 1 }
            if (wordCount < 7) continue

            cleanedLines.add(stripped)
            accumulatedChars += stripped.length + 1
            if (accumulatedChars >= maxChars || cleanedLines.size >= 4) {
                break
            }
        }

        val joined = cleanedLines.joinToString(" ").trim()
        return if (joined.length > maxChars) {
            val cut = joined.substring(0, maxChars)
            val lastPeriod = cut.lastIndexOf('.')
            if (lastPeriod > maxChars / 2) {
                cut.substring(0, lastPeriod + 1)
            } else {
                cut
            }
        } else {
            joined
        }
    }
}
