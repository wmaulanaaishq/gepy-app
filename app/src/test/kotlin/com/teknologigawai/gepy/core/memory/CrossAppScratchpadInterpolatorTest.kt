package com.teknologigawai.gepy.core.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit Test Komprehensif untuk [CrossAppScratchpadInterpolator] pada **Gepy v5.9.5**.
 */
class CrossAppScratchpadInterpolatorTest {

    @Test
    fun testInterpolateText_purePronounResolvesToScratchpad() {
        val scratchpad = mapOf(
            "alamat" to "Jl. Tebet Raya No. 10, Jakarta Selatan",
            "link" to "https://shopee.co.id/product/12345",
            "harga" to "Rp 12.500.000",
            "materi" to "Rangkuman Kuliah AI & Mobile Architecture"
        )

        // Kata ganti "alamatnya"
        val resAlamat = CrossAppScratchpadInterpolator.interpolateText(
            inputText = "alamatnya",
            scratchpad = scratchpad
        )
        assertEquals("Jl. Tebet Raya No. 10, Jakarta Selatan", resAlamat)

        // Kata ganti "linknya"
        val resLink = CrossAppScratchpadInterpolator.interpolateText(
            inputText = "linknya",
            scratchpad = scratchpad
        )
        assertEquals("https://shopee.co.id/product/12345", resLink)

        // Kata ganti "harganya"
        val resHarga = CrossAppScratchpadInterpolator.interpolateText(
            inputText = "harganya",
            scratchpad = scratchpad
        )
        assertEquals("Rp 12.500.000", resHarga)

        // Kata ganti "materinya"
        val resMateri = CrossAppScratchpadInterpolator.interpolateText(
            inputText = "materinya",
            scratchpad = scratchpad
        )
        assertEquals("Rangkuman Kuliah AI & Mobile Architecture", resMateri)
    }

    @Test
    fun testInterpolateText_bracketPlaceholders() {
        val scratchpad = mapOf(
            "alamat" to "Jl. Gatot Subroto No. 42",
            "kode_voucher" to "DISKON50PERSEN"
        )

        val input = "Halo Mas Budi, lokasinya di {alamat} ya. Gunakan voucher {kode_voucher}!"
        val result = CrossAppScratchpadInterpolator.interpolateText(
            inputText = input,
            scratchpad = scratchpad
        )
        assertEquals(
            "Halo Mas Budi, lokasinya di Jl. Gatot Subroto No. 42 ya. Gunakan voucher DISKON50PERSEN!",
            result
        )
    }

    @Test
    fun testInterpolateText_pronounInsideSentence() {
        val scratchpad = mapOf(
            "alamat" to "Jl. Sudirman Kav 20",
            "clipboard_last" to "Jl. Sudirman Kav 20"
        )

        val input = "Halo Mas, ini alamatnya yang tadi kamu minta"
        val result = CrossAppScratchpadInterpolator.interpolateText(
            inputText = input,
            scratchpad = scratchpad
        )
        assertEquals("Halo Mas, ini Jl. Sudirman Kav 20 yang tadi kamu minta", result)
    }

    @Test
    fun testInterpolateText_emptyInputFallback() {
        val scratchpad = mapOf("alamat" to "Jl. Merdeka Barat No. 1")

        // Jika input null, ambil data terbaik dari scratchpad
        val resNull = CrossAppScratchpadInterpolator.interpolateText(
            inputText = null,
            scratchpad = scratchpad
        )
        assertEquals("Jl. Merdeka Barat No. 1", resNull)

        // Jika input blank, ambil data terbaik dari scratchpad
        val resBlank = CrossAppScratchpadInterpolator.interpolateText(
            inputText = "   ",
            scratchpad = scratchpad
        )
        assertEquals("Jl. Merdeka Barat No. 1", resBlank)

        // Jika scratchpad kosong, fallback ke clipboard
        val resClip = CrossAppScratchpadInterpolator.interpolateText(
            inputText = "",
            scratchpad = emptyMap(),
            clipboardFallback = "Pesan Penting Dari Clipboard"
        )
        assertEquals("Pesan Penting Dari Clipboard", resClip)
    }

    @Test
    fun testInterpolateText_normalTextPreservedWhenNoTokens() {
        val input = "Halo Selamat Pagi Apa Kabar?"
        val result = CrossAppScratchpadInterpolator.interpolateText(
            inputText = input,
            scratchpad = mapOf("alamat" to "Jl. Sudirman")
        )
        assertEquals("Halo Selamat Pagi Apa Kabar?", result)
    }
}
