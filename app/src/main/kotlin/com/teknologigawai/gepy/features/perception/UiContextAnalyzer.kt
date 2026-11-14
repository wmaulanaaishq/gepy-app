package com.teknologigawai.gepy.features.perception

import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Arketipe layar aplikasi Android untuk membantu pemahaman konteks tata letak UI (UI Context Understanding).
 */
enum class ScreenArchetype(val label: String) {
    SEARCH_RESULTS("SEARCH_RESULTS"),
    INPUT_FORM_OR_DIALOG("INPUT_FORM_OR_DIALOG"),
    MEDIA_FEED_OR_STREAM("MEDIA_FEED_OR_STREAM"),
    DETAIL_OR_ARTICLE("DETAIL_OR_ARTICLE"),
    NAVIGATION_OR_HOME("NAVIGATION_OR_HOME"),
    EMPTY_OR_LOADING("EMPTY_OR_LOADING"),
    GENERIC_VIEW("GENERIC_VIEW")
}

/**
 * Hasil analisis semantik struktur layar aplikasi.
 */
data class UiContextAnalysis(
    val archetype: ScreenArchetype,
    val focusedIndex: Int? = null,
    val screenSummary: String,
    val navigationHint: String
)

/**
 * Penganalisis Konteks Layar UI (UI Context Analyzer) deterministik (0 ms, 0 overhead):
 * Mengklasifikasikan layar ke dalam arketipe struktural dan memberikan panduan
 * navigasi yang jelas agar LLM tidak salah langkah atau terjebak dalam perulangan buta.
 */
object UiContextAnalyzer {

    fun analyzeScreen(
        elements: List<PrunedUiElement>,
        currentPackage: String
    ): UiContextAnalysis {
        if (elements.isEmpty()) {
            return UiContextAnalysis(
                archetype = ScreenArchetype.EMPTY_OR_LOADING,
                focusedIndex = null,
                screenSummary = "Layar kosong atau sedang memuat halaman.",
                navigationHint = "Gunakan action WAIT bila layar masih memuat, atau BACK jika macet."
            )
        }

        val focusedEl = elements.firstOrNull { it.isEditable && it.text.isNotBlank() }
            ?: elements.firstOrNull { it.isEditable }
        val focusedIdx = focusedEl?.index

        val hasSearchBar = elements.any { el ->
            val id = (el.viewResourceId ?: el.nodeId).lowercase()
            val text = el.text.lowercase()
            (el.isEditable && (id.contains("search") || id.contains("cari") || text.contains("cari") || text.contains("search")))
        }

        val editableCount = elements.count { it.isEditable }
        val clickableCount = elements.count { it.isClickable }
        val scrollableCount = elements.count { it.isScrollable }

        val lowerLabels = elements.map { it.text.lowercase() }
        val lowerIds = elements.map { (it.viewResourceId ?: it.nodeId).lowercase() }

        val isDialogOrModal = lowerIds.any { it.contains("dialog") || it.contains("modal") || it.contains("bottomsheet") } ||
            elements.any { el ->
                val text = el.text.lowercase()
                (text == "batal" || text == "cancel") && elements.any { it.text.lowercase() in setOf("ok", "ya", "setuju", "simpan", "kirim") }
            }

        val isEmptyOrLoading = lowerLabels.any { it.contains("tidak ada") || it.contains("no results") || it.contains("memuat") || it.contains("loading") } ||
            lowerIds.any { it.contains("progress_bar") || it.contains("shimmer") }

        val isMediaFeed = lowerIds.any { it.contains("feed") || it.contains("reel") || it.contains("video_player") || it.contains("grid_pattern") } ||
            lowerLabels.any { it == "suka" || it == "like" || it == "komentar" || it == "bagikan" || it == "share" }

        val isDetailOrArticle = lowerIds.any { it.contains("detail") || it.contains("article") || it.contains("product_desc") || it.contains("desc") } ||
            lowerLabels.any { it.contains("beli sekarang") || it.contains("keranjang") || it.contains("rincian") || it.contains("spesifikasi") }

        val archetype = when {
            isEmptyOrLoading -> ScreenArchetype.EMPTY_OR_LOADING
            isDialogOrModal || (editableCount >= 2 && clickableCount >= 1) -> ScreenArchetype.INPUT_FORM_OR_DIALOG
            hasSearchBar && clickableCount >= 2 -> ScreenArchetype.SEARCH_RESULTS
            isMediaFeed -> ScreenArchetype.MEDIA_FEED_OR_STREAM
            isDetailOrArticle -> ScreenArchetype.DETAIL_OR_ARTICLE
            scrollableCount >= 1 && clickableCount >= 4 -> ScreenArchetype.NAVIGATION_OR_HOME
            else -> ScreenArchetype.GENERIC_VIEW
        }

        val summary = when (archetype) {
            ScreenArchetype.SEARCH_RESULTS -> "Halaman Hasil Pencarian ($clickableCount item interaktif terlihat)."
            ScreenArchetype.INPUT_FORM_OR_DIALOG -> "Formulir Input / Dialog Pop-up ($editableCount kolom teks terdeteksi)."
            ScreenArchetype.MEDIA_FEED_OR_STREAM -> "Aliran Konten / Media Feed aktif."
            ScreenArchetype.DETAIL_OR_ARTICLE -> "Halaman Detail / Informasi Konten."
            ScreenArchetype.NAVIGATION_OR_HOME -> "Halaman Menu Utama / Navigasi Aplikasi."
            ScreenArchetype.EMPTY_OR_LOADING -> "Status Memuat / Indikasi Halaman Kosong."
            ScreenArchetype.GENERIC_VIEW -> "Tampilan antarmuka aplikasi standar."
        }

        val hint = when (archetype) {
            ScreenArchetype.SEARCH_RESULTS -> "Pilih salah satu item hasil dari daftar di bawah. JANGAN mengetuk kolom pencarian berulang kali jika sudah ada hasil!"
            ScreenArchetype.INPUT_FORM_OR_DIALOG -> "Ketik data pada kolom edit=true yang sesuai, lalu ketuk tombol Simpan/Kirim/OK untuk menyelesaikan."
            ScreenArchetype.MEDIA_FEED_OR_STREAM -> "Pilih item postingan/media yang diminta, atau gunakan tombol interaksi (Bagikan/Komentar/Suka)."
            ScreenArchetype.DETAIL_OR_ARTICLE -> "Periksa informasi yang dibutuhkan, atau ketuk tombol aksi target."
            ScreenArchetype.NAVIGATION_OR_HOME -> "Pilih tab menu atau kolom pencarian untuk masuk ke halaman tujuan."
            ScreenArchetype.EMPTY_OR_LOADING -> "Tunggu sesaat (WAIT) jika sedang memuat, atau gunakan BACK jika halaman macet."
            ScreenArchetype.GENERIC_VIEW -> "Pilih elemen tujuan yang paling sesuai dengan instruksi pemilik."
        }

        return UiContextAnalysis(
            archetype = archetype,
            focusedIndex = focusedIdx,
            screenSummary = summary,
            navigationHint = hint
        )
    }
}
