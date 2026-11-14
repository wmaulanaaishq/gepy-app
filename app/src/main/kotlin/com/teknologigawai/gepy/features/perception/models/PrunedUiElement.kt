package com.teknologigawai.gepy.features.perception.models

import org.json.JSONArray
import org.json.JSONObject

/** Sumber ekstraksi kotak teks pada primitif inspeksi layar `screen()`. */
enum class TextBoxSource(val wireName: String) {
    /** Teks diekstrak langsung dari hierarki *Accessibility Tree* (`zero misreads`). */
    TREE("tree"),

    /** Teks diekstrak melalui tangkapan layar dan OCR piksel Google ML Kit. */
    PIXELS("pixels")
}

/** Representasi kotak teks terlihat di layar untuk primitif `screen()`. */
data class TextBox(
    val text: String,
    val confidence: Double,
    val source: TextBoxSource,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) {
    val centerX: Int
        get() = x + (w / 2)

    val centerY: Int
        get() = y + (h / 2)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("text", text)
        put("confidence", confidence)
        put("source", source.wireName)
        put("x", x)
        put("y", y)
        put("w", w)
        put("h", h)
    }
}

/**
 * Model elemen antarmuka pengguna hasil pemangkasan (*Set-of-Marks* `[1..N]`).
 *
 * Menyimpan informasi esensial yang dibutuhkan LLM untuk memilih target aksi
 * sekaligus menyimpan identitas `nodeId` (`mapId`) untuk eksekusi in-memory di `GepyAccessibilityService`.
 */
data class PrunedUiElement(
    val index: Int,
    val nodeId: String,
    val packageName: String = "",
    val text: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val rect: Map<String, Int> = emptyMap(),
    val viewResourceId: String? = null,
    val source: TextBoxSource = TextBoxSource.TREE
) {
    val x: Int
        get() = rect["x"] ?: 0

    val y: Int
        get() = rect["y"] ?: 0

    val w: Int
        get() = rect["w"] ?: 0

    val h: Int
        get() = rect["h"] ?: 0

    val centerX: Int
        get() = rect["centerX"] ?: (x + (w / 2))

    val centerY: Int
        get() = rect["centerY"] ?: (y + (h / 2))

    val flags: List<String>
        get() = buildList {
            if (isClickable) add("CLICKABLE")
            if (isEditable) add("EDITABLE")
            if (isScrollable) add("SCROLLABLE")
        }

    fun toCompactJsonObject(): JSONObject = JSONObject().apply {
        put("idx", index)
        put("id", viewResourceId ?: nodeId)
        if (text.isNotEmpty()) {
            put("label", text)
        }
        put("flags", JSONArray(flags))
        put("center", JSONArray(listOf(centerX, centerY)))
        put("bounds", JSONArray(listOf(x, y, w, h)))
    }

    fun toTextBox(confidence: Double = 1.0): TextBox = TextBox(
        text = text,
        confidence = confidence,
        source = source,
        x = x,
        y = y,
        w = w,
        h = h
    )

    companion object {
        fun buildRect(x: Int, y: Int, w: Int, h: Int): Map<String, Int> {
            val cx = x + (w / 2)
            val cy = y + (h / 2)
            return mapOf(
                "x" to x,
                "y" to y,
                "w" to w,
                "h" to h,
                "centerX" to cx,
                "centerY" to cy
            )
        }
    }
}

/** Hasil pemangkasan pohon aksesibilitas beserta metrik kompresi untuk telemetri. */
data class PruneMetricsSnapshot(
    val elements: List<PrunedUiElement>,
    val rawNodeCount: Int,
    val rawCharLength: Int,
    val compactJson: String
) {
    val prunedNodeCount: Int
        get() = elements.size

    val pruningRatio: Double
        get() {
            if (rawCharLength <= 0) return 0.0
            val saved = rawCharLength - compactJson.length
            if (saved <= 0) return 0.0
            return saved.toDouble() / rawCharLength.toDouble()
        }
}
