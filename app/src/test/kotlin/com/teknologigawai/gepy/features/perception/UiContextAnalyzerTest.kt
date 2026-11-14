package com.teknologigawai.gepy.features.perception

import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContextAnalyzerTest {

    @Test
    fun `mengklasifikasikan layar kosong atau loading`() {
        val analysisEmpty = UiContextAnalyzer.analyzeScreen(emptyList(), "com.example.app")
        assertEquals(ScreenArchetype.EMPTY_OR_LOADING, analysisEmpty.archetype)
        assertTrue(analysisEmpty.screenSummary.contains("kosong"))

        val loadingElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "pb1",
                viewResourceId = "com.example.app:id/progress_bar",
                text = "Memuat...",
                rect = PrunedUiElement.buildRect(100, 200, 50, 50)
            )
        )
        val analysisLoading = UiContextAnalyzer.analyzeScreen(loadingElements, "com.example.app")
        assertEquals(ScreenArchetype.EMPTY_OR_LOADING, analysisLoading.archetype)
    }

    @Test
    fun `mengklasifikasikan layar hasil pencarian`() {
        val searchElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "search_bar",
                viewResourceId = "com.shopee.id:id/search_edit_text",
                text = "laptop coding",
                isEditable = true,
                rect = PrunedUiElement.buildRect(20, 40, 680, 80)
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "item_1",
                text = "Laptop Asus ROG Zephyrus",
                isClickable = true,
                rect = PrunedUiElement.buildRect(20, 140, 680, 160)
            ),
            PrunedUiElement(
                index = 3,
                nodeId = "item_2",
                text = "Laptop Lenovo ThinkPad",
                isClickable = true,
                rect = PrunedUiElement.buildRect(20, 320, 680, 160)
            )
        )
        val analysis = UiContextAnalyzer.analyzeScreen(searchElements, "com.shopee.id")
        assertEquals(ScreenArchetype.SEARCH_RESULTS, analysis.archetype)
        assertTrue(analysis.navigationHint.contains("Pilih salah satu item"))
        assertNotNull(analysis.focusedIndex)
    }

    @Test
    fun `mengklasifikasikan formulir input atau dialog`() {
        val formElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "input_name",
                text = "Nama",
                isEditable = true,
                rect = PrunedUiElement.buildRect(20, 100, 600, 80)
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "input_phone",
                text = "Nomor Telepon",
                isEditable = true,
                rect = PrunedUiElement.buildRect(20, 200, 600, 80)
            ),
            PrunedUiElement(
                index = 3,
                nodeId = "btn_save",
                text = "Simpan",
                isClickable = true,
                rect = PrunedUiElement.buildRect(20, 300, 200, 80)
            )
        )
        val analysis = UiContextAnalyzer.analyzeScreen(formElements, "com.example.app")
        assertEquals(ScreenArchetype.INPUT_FORM_OR_DIALOG, analysis.archetype)
        assertTrue(analysis.screenSummary.contains("Formulir Input"))
    }

    @Test
    fun `mengklasifikasikan media feed atau stream`() {
        val feedElements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "feed_item_1",
                viewResourceId = "com.instagram.android:id/row_feed_button_like",
                text = "Suka",
                isClickable = true,
                rect = PrunedUiElement.buildRect(20, 400, 60, 60)
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "feed_item_2",
                viewResourceId = "com.instagram.android:id/row_feed_button_share",
                text = "Bagikan",
                isClickable = true,
                rect = PrunedUiElement.buildRect(100, 400, 60, 60)
            )
        )
        val analysis = UiContextAnalyzer.analyzeScreen(feedElements, "com.instagram.android")
        assertEquals(ScreenArchetype.MEDIA_FEED_OR_STREAM, analysis.archetype)
    }
}
