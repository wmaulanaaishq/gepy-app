package com.teknologigawai.gepy.features.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.scale
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import java.io.ByteArrayOutputStream

internal enum class SoMImageFormat {
    PNG,
    JPEG
}

internal interface SoMBitmapHandle {
    val width: Int
    val height: Int
    val isMutable: Boolean
    val isRecycled: Boolean

    fun createScaled(targetW: Int, targetH: Int): SoMBitmapHandle
    fun copyMutable(): SoMBitmapHandle?
    fun annotateAndCompress(
        elements: List<PrunedUiElement>,
        scale: Float,
        strokeWidth: Float,
        badgeTextSize: Float,
        badgeMinW: Float,
        badgeMaxW: Float,
        badgeHeight: Float,
        badgeRadius: Float,
        badgeTextOffset: Float,
        format: SoMImageFormat,
        quality: Int
    ): ByteArray

    fun recycle()
}

private class AndroidSoMBitmapHandle(
    private val bitmap: Bitmap
) : SoMBitmapHandle {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override val isMutable: Boolean get() = bitmap.isMutable
    override val isRecycled: Boolean get() = bitmap.isRecycled

    override fun createScaled(targetW: Int, targetH: Int): SoMBitmapHandle {
        val scaled = bitmap.scale(targetW, targetH)
        return if (scaled === bitmap) this else AndroidSoMBitmapHandle(scaled)
    }

    override fun copyMutable(): SoMBitmapHandle? {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        return if (copy === bitmap) this else AndroidSoMBitmapHandle(copy)
    }

    override fun annotateAndCompress(
        elements: List<PrunedUiElement>,
        scale: Float,
        strokeWidth: Float,
        badgeTextSize: Float,
        badgeMinW: Float,
        badgeMaxW: Float,
        badgeHeight: Float,
        badgeRadius: Float,
        badgeTextOffset: Float,
        format: SoMImageFormat,
        quality: Int
    ): ByteArray {
        val canvas = Canvas(bitmap)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (format == SoMImageFormat.PNG) 230 else 235, 225, 29, 72)
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (format == SoMImageFormat.PNG) 242 else 245, 225, 29, 72)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = badgeTextSize
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        for (el in elements) {
            if (!el.isClickable && !el.isEditable) continue
            val left = (el.x * scale).coerceIn(0f, (width - 2f).coerceAtLeast(0f))
            val top = (el.y * scale).coerceIn(0f, (height - 2f).coerceAtLeast(0f))
            val w = (el.w * scale).coerceIn(4f, (width - left).coerceAtLeast(4f))
            val h = (el.h * scale).coerceIn(4f, (height - top).coerceAtLeast(4f))

            canvas.drawRect(left, top, left + w, top + h, boxPaint)

            val tagText = el.index.toString()
            val textWidth = textPaint.measureText(tagText)
            val badgePadding = if (format == SoMImageFormat.PNG) 14f else 12f
            val badgeW = (textWidth + badgePadding).coerceIn(badgeMinW, badgeMaxW)
            val badgeTop = if (top >= badgeHeight) top - badgeHeight else top
            val badgeRect = RectF(left, badgeTop, left + badgeW, badgeTop + badgeHeight)
            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgePaint)
            canvas.drawText(tagText, left + (badgeW / 2f), badgeTop + badgeTextOffset, textPaint)
        }

        val baos = ByteArrayOutputStream()
        val compressFormat = if (format == SoMImageFormat.PNG) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        bitmap.compress(compressFormat, quality, baos)
        return baos.toByteArray()
    }

    override fun recycle() {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

/**
 * Mesin *Set-of-Marks (SoM) Grounding* untuk **Hybrid Perception 2.0** Gepy (`com.teknologigawai.gepy`).
 *
 * Menyelesaikan dua titik buta utama persepsi murni *Accessibility Tree*:
 * 1. **Tombol Ikon Tanpa Label Teks**: Menyuntikkan deskripsi semantik & zona spasial
 *    layar (`[SoM #idx • Kirim @ kanan-bawah]`) berdasarkan kamus `viewResourceId`
 *    Android dan koordinat ternormalisasi agar LLM maupun *Safety Gatekeeper*
 *    mengenali fungsi tombol ikon.
 * 2. **Kanvas Kustom / Non-XML**: Mendeteksi layar minim node interaktif dan
 *    menggambar kotak pembatas bernomor `[1..N]` secara *in-memory* menggunakan
 *    Android `Canvas` di atas `ByteArray` tangkapan layar tanpa menyentuh penyimpanan disk.
 */
class SetOfMarksGrounder(
    val defaultScreenWidth: Int = 1080,
    val defaultScreenHeight: Int = 2400
) {
    data class VisualCacheEntry(
        val visualHash: Long,
        val jpegBytes: ByteArray,
        val timestampMs: Long
    )

    private var lastVisualCache: VisualCacheEntry? = null

    internal var bitmapDecoder: (ByteArray) -> SoMBitmapHandle? = { raw ->
        BitmapFactory.decodeByteArray(raw, 0, raw.size)?.let { AndroidSoMBitmapHandle(it) }
    }

    /**
     * Menghitung nilai visual dHash 64-bit dari tangkapan layar mentah.
     */
    fun computeScreenDHash(rawScreenshotBytes: ByteArray): Long {
        return PerceptualHashEngine.computeDHash(rawScreenshotBytes)
    }

    /**
     * Mengembalikan byte tangkapan layar beranotasi yang sudah pernah di-cache jika layar secara visual
     * identik dengan frame sebelumnya (Hamming distance <= threshold), menghemat kompresi & token.
     */
    fun getCachedAnnotatedJpegIfSimilar(
        rawScreenshotBytes: ByteArray,
        threshold: Int = PerceptualHashEngine.DEFAULT_SIMILARITY_THRESHOLD
    ): ByteArray? {
        val currentHash = computeScreenDHash(rawScreenshotBytes)
        val cached = lastVisualCache ?: return null
        return if (PerceptualHashEngine.areVisuallySimilar(currentHash, cached.visualHash, threshold)) {
            cached.jpegBytes
        } else {
            null
        }
    }

    /**
     * Memperkaya elemen interaktif yang tidak memiliki label teks dengan penanda
     * semantik dan posisi spasial *Set-of-Marks*.
     */
    fun enrichUnlabeledElements(
        elements: List<PrunedUiElement>,
        screenWidth: Int? = null,
        screenHeight: Int? = null
    ): List<PrunedUiElement> {
        if (elements.isEmpty()) {
            return elements
        }

        val inferredW = inferMaxDimension(
            elements = elements,
            isHorizontal = true,
            fallback = screenWidth ?: defaultScreenWidth
        )
        val inferredH = inferMaxDimension(
            elements = elements,
            isHorizontal = false,
            fallback = screenHeight ?: defaultScreenHeight
        )

        return elements.map { el ->
            if (el.text.trim().isNotEmpty() || (!el.isClickable && !el.isEditable)) {
                return@map el
            }

            val zone = computeSpatialZone(
                centerX = el.centerX,
                centerY = el.centerY,
                screenWidth = inferredW,
                screenHeight = inferredH
            )
            val inferredRole = inferSemanticRoleFromId(el.viewResourceId)
            val shortId = extractShortId(el.viewResourceId)

            val synthesizedLabel = when {
                inferredRole != null -> "[SoM #${el.index} • $inferredRole @ $zone]"
                el.isEditable -> {
                    val idPart = if (shortId != null) " ($shortId)" else ""
                    "[Hint] Kolom Input$idPart @ $zone"
                }
                else -> {
                    val idPart = if (shortId != null) " ($shortId)" else ""
                    "[SoM #${el.index} • Ikon Klik$idPart @ $zone]"
                }
            }

            el.copy(text = synthesizedLabel)
        }
    }

    /**
     * Menentukan apakah layar saat ini terindikasi sebagai kanvas grafis kustom
     * (*Canvas / Game / Custom WebView*) yang membutuhkan *fallback* visual/OCR.
     */
    fun isCanvasBlindSpot(
        foregroundPackage: String,
        rawPrunedElements: List<PrunedUiElement>
    ): Boolean {
        val pkg = foregroundPackage.trim()
        if (pkg.isEmpty() ||
            pkg == EnvConfig.SELF_PACKAGE_NAME ||
            pkg.lowercase().contains("launcher")
        ) {
            return false
        }

        val interactive = rawPrunedElements.filter {
            it.isClickable || it.isEditable || it.isScrollable
        }
        if (interactive.size < 2) {
            return true
        }

        val unlabeledCount = interactive.count { it.text.trim().isEmpty() }
        return interactive.size >= 4 &&
            (unlabeledCount.toDouble() / interactive.size.toDouble()) >= 0.85
    }

    private fun recycleSafely(
        target: SoMBitmapHandle?,
        excluding1: SoMBitmapHandle? = null,
        excluding2: SoMBitmapHandle? = null
    ) {
        if (target != null && target !== excluding1 && target !== excluding2 && !target.isRecycled) {
            runCatching { target.recycle() }
        }
    }

    /**
     * Menggambar kotak pembatas dan lencana indeks `[1..N]` *Set-of-Marks* secara
     * *in-memory* di atas tangkapan layar [rawScreenshotBytes] dan mengembalikan PNG.
     */
    fun renderAnnotatedScreenshotPng(
        rawScreenshotBytes: ByteArray,
        elements: List<PrunedUiElement>
    ): ByteArray? {
        if (rawScreenshotBytes.isEmpty()) {
            return null
        }
        var decoded: SoMBitmapHandle? = null
        var mutable: SoMBitmapHandle? = null
        return try {
            decoded = bitmapDecoder(rawScreenshotBytes) ?: return null
            mutable = decoded.copyMutable() ?: return null
            if (mutable !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }

            mutable.annotateAndCompress(
                elements = elements,
                scale = 1.0f,
                strokeWidth = 3f,
                badgeTextSize = 18f,
                badgeMinW = 24f,
                badgeMaxW = 52f,
                badgeHeight = 24f,
                badgeRadius = 5f,
                badgeTextOffset = 18f,
                format = SoMImageFormat.PNG,
                quality = 100
            )
        } catch (_: Throwable) {
            null
        } finally {
            recycleSafely(mutable, excluding1 = decoded)
            recycleSafely(decoded)
        }
    }

    /**
     * Merender tangkapan layar *Set-of-Marks* beresolusi hemat memori (`maks 720px`, JPEG kualitas 72)
     * khusus untuk **Multimodal Vision Grounding** di Samsung Galaxy A06 (RAM 4 GB, target Total PSS `<= 180 MB`).
     *
     * Langsung memanggil `Bitmap.recycle()` sesudah kompresi (dan dalam blok `finally` saat eksepsi)
     * untuk mencegah penumpukan heap native.
     * Pada lingkungan unit test JVM murni (di mana `BitmapFactory` bernilai `null`), mengembalikan
     * [rawScreenshotBytes] apa adanya agar pengujian deterministik berjalan mulus.
     */
    fun renderCompactAnnotatedJpeg(
        rawScreenshotBytes: ByteArray,
        elements: List<PrunedUiElement>,
        maxDimension: Int = 720,
        jpegQuality: Int = 72
    ): ByteArray? {
        if (rawScreenshotBytes.isEmpty()) return null
        var decoded: SoMBitmapHandle? = null
        var scaledBitmap: SoMBitmapHandle? = null
        var mutable: SoMBitmapHandle? = null
        return try {
            decoded = bitmapDecoder(rawScreenshotBytes) ?: return rawScreenshotBytes
            val origW = decoded.width.coerceAtLeast(1)
            val origH = decoded.height.coerceAtLeast(1)
            val maxOrig = maxOf(origW, origH)
            val scale = if (maxOrig > maxDimension) {
                maxDimension.toFloat() / maxOrig.toFloat()
            } else {
                1.0f
            }

            val targetW = (origW * scale).toInt().coerceAtLeast(1)
            val targetH = (origH * scale).toInt().coerceAtLeast(1)
            scaledBitmap = if (scale < 1.0f) {
                val created = decoded.createScaled(targetW, targetH)
                if (created !== decoded && !decoded.isRecycled) {
                    decoded.recycle()
                }
                created
            } else {
                val copy = decoded.copyMutable()
                if (copy !== decoded && !decoded.isRecycled) {
                    decoded.recycle()
                }
                copy
            } ?: return rawScreenshotBytes

            mutable = if (scaledBitmap.isMutable) {
                scaledBitmap
            } else {
                val copy = scaledBitmap.copyMutable()
                if (copy !== scaledBitmap && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                }
                copy ?: return rawScreenshotBytes
            }

            val resultBytes = mutable.annotateAndCompress(
                elements = elements,
                scale = scale,
                strokeWidth = 2.5f,
                badgeTextSize = 15f,
                badgeMinW = 20f,
                badgeMaxW = 46f,
                badgeHeight = 20f,
                badgeRadius = 4f,
                badgeTextOffset = 15f,
                format = SoMImageFormat.JPEG,
                quality = jpegQuality.coerceIn(40, 95)
            )
            // Bebaskan seluruh bitmap sebelum menghitung dHash frame agar puncak native heap tetap rendah.
            recycleSafely(mutable, excluding1 = scaledBitmap, excluding2 = decoded)
            recycleSafely(scaledBitmap, excluding1 = decoded)
            recycleSafely(decoded)

            val currentHash = computeScreenDHash(rawScreenshotBytes)
            lastVisualCache = VisualCacheEntry(
                visualHash = currentHash,
                jpegBytes = resultBytes,
                timestampMs = System.currentTimeMillis()
            )
            resultBytes
        } catch (_: Throwable) {
            rawScreenshotBytes
        } finally {
            recycleSafely(mutable, excluding1 = scaledBitmap, excluding2 = decoded)
            recycleSafely(scaledBitmap, excluding1 = decoded)
            recycleSafely(decoded)
        }
    }

    companion object {
        private val resourceIdLexicon: List<Pair<List<String>, String>> = listOf(
            listOf("send", "kirim", "btn_send", "reply_send") to "Kirim",
            listOf(
                "my_search_bar",
                "menuitem_search",
                "search_holder",
                "search_button",
                "search_bar",
                "action_search"
            ) to "Cari",
            listOf("search_src_text", "search_input") to "[Hint] Cari",
            listOf("save", "simpan", "action_save", "done_button") to "Simpan",
            listOf("delete", "hapus", "trash", "action_delete") to "Hapus",
            listOf("navigate_up", "back", "btn_back", "toolbar_back") to "Kembali",
            listOf("close", "cancel", "clear", "dismiss") to "Tutup",
            listOf("fab", "add", "create", "new_chat", "compose") to "Tambah / Buat Baru",
            listOf("attach", "attachment", "paperclip") to "Lampirkan",
            listOf("camera", "cam_btn") to "Kamera",
            listOf("voice_note", "ptt", "mic", "voice") to "Suara",
            listOf("overflow", "more_options", "menuitem_overflow") to "Menu Lainnya",
            listOf("share", "action_share") to "Bagikan"
        )

        /** Menyimpulkan peran semantik tombol ikon dari akhiran `viewResourceId`. */
        fun inferSemanticRoleFromId(viewResourceId: String?): String? {
            val shortId = extractShortId(viewResourceId)?.lowercase() ?: return null
            if (shortId.isEmpty()) return null
            for ((keywords, label) in resourceIdLexicon) {
                for (kw in keywords) {
                    if (shortId == kw ||
                        shortId.startsWith("${kw}_") ||
                        shortId.endsWith("_$kw") ||
                        shortId.contains(kw)
                    ) {
                        return label
                    }
                }
            }
            return null
        }

        /** Memetakan koordinat titik ke dalam zona grid 3x3 Bahasa Indonesia. */
        fun computeSpatialZone(
            centerX: Int,
            centerY: Int,
            screenWidth: Int,
            screenHeight: Int
        ): String {
            val safeW = if (screenWidth <= 0) 1080 else screenWidth
            val safeH = if (screenHeight <= 0) 2400 else screenHeight
            val rx = centerX.toDouble() / safeW.toDouble()
            val ry = centerY.toDouble() / safeH.toDouble()

            val col = when {
                rx < 0.33 -> "kiri"
                rx > 0.67 -> "kanan"
                else -> "tengah"
            }
            val row = when {
                ry < 0.25 -> "atas"
                ry > 0.75 -> "bawah"
                else -> "tengah"
            }

            if (col == "tengah" && row == "tengah") {
                return "tengah"
            }
            return "$col-$row"
        }

        private fun extractShortId(viewResourceId: String?): String? {
            if (viewResourceId.isNullOrBlank()) return null
            val trimmed = viewResourceId.trim()
            val slashIdx = trimmed.lastIndexOf('/')
            if (slashIdx != -1 && slashIdx + 1 < trimmed.length) {
                return trimmed.substring(slashIdx + 1)
            }
            return trimmed
        }

        private fun inferMaxDimension(
            elements: List<PrunedUiElement>,
            isHorizontal: Boolean,
            fallback: Int
        ): Int {
            var maxCoord = fallback
            for (el in elements) {
                val edge = if (isHorizontal) el.x + el.w else el.y + el.h
                if (edge > maxCoord) {
                    maxCoord = edge
                }
            }
            return maxCoord
        }
    }
}
