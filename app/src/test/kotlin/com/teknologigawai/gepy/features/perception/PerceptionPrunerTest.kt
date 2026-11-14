package com.teknologigawai.gepy.features.perception

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.service.RawAccessibilityNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerceptionPrunerTest {

    private lateinit var pruner: AccessibilityTreePruner

    @Before
    fun setUp() {
        pruner = AccessibilityTreePruner()
    }

    @Test
    fun `menyaring 100 persen node milik com_teknologigawai_gepy dan memangkas lebih dari 80 persen noise`() {
        val decorativeNoiseNodes = (0 until 25).map { idx ->
            val w = if (idx % 2 == 0) 0 else 1080
            val h = if (idx % 2 == 0) 0 else 2400
            RawAccessibilityNodeSnapshot(
                mapId = "noise_$idx",
                viewResourceId = "com.whatsapp:id/decor_frame_$idx",
                packageName = "com.whatsapp",
                text = "",
                isClickable = false,
                isEditable = false,
                isScrollable = false,
                left = 0,
                top = 0,
                right = w,
                bottom = h,
                width = w,
                height = h
            )
        }

        // Node milik overlay Gepy sendiri yang WAJIB diabaikan (Self-Package Blindness)
        val gepyOverlayNode = RawAccessibilityNodeSnapshot(
            mapId = "gepy_pill_1",
            viewResourceId = "${EnvConfig.SELF_PACKAGE_NAME}:id/floating_mic",
            packageName = EnvConfig.SELF_PACKAGE_NAME,
            text = "Ya, Eksekusi",
            isClickable = true,
            left = 900,
            top = 1000,
            right = 1060,
            bottom = 1160,
            width = 160,
            height = 160
        )

        val searchInput = RawAccessibilityNodeSnapshot(
            mapId = "wa_entry",
            viewResourceId = "com.whatsapp:id/entry",
            packageName = "com.whatsapp",
            text = "Ketik pesan",
            isClickable = true,
            isEditable = true,
            left = 40,
            top = 2100,
            right = 900,
            bottom = 2220,
            width = 860,
            height = 120
        )

        val sendButton = RawAccessibilityNodeSnapshot(
            mapId = "wa_send",
            viewResourceId = "com.whatsapp:id/send",
            packageName = "com.whatsapp",
            text = "Kirim",
            isClickable = true,
            left = 920,
            top = 2100,
            right = 1040,
            bottom = 2220,
            width = 120,
            height = 120
        )

        val root = RawAccessibilityNodeSnapshot(
            mapId = "root_wa",
            packageName = "com.whatsapp",
            text = "",
            isClickable = false,
            left = 0,
            top = 0,
            right = 1080,
            bottom = 2400,
            width = 1080,
            height = 2400,
            subNodes = decorativeNoiseNodes + listOf(gepyOverlayNode, searchInput, sendButton)
        )

        val snapshot = pruner.pruneTreeWithMetrics(root)

        assertFalse(snapshot.elements.any { it.packageName == EnvConfig.SELF_PACKAGE_NAME })
        assertFalse(snapshot.elements.any { it.text == "Ya, Eksekusi" })

        assertEquals(2, snapshot.elements.size)
        assertEquals(1, snapshot.elements.first().index)
        assertTrue(snapshot.elements.first().isEditable)
        assertEquals(2, snapshot.elements.last().index)
        assertEquals("com.whatsapp:id/send", snapshot.elements.last().viewResourceId)

        assertTrue(
            "Expected pruningRatio > 0.80 but was ${snapshot.pruningRatio}",
            snapshot.pruningRatio > 0.80
        )
    }

    @Test
    fun `menyaring com_samsung_android_app_smartcapture dan IME agar tidak membajak foreground package`() {
        val smartCaptureNode = RawAccessibilityNodeSnapshot(
            mapId = "smartcapture_toolbar",
            viewResourceId = "com.samsung.android.app.smartcapture:id/crop_btn",
            packageName = "com.samsung.android.app.smartcapture",
            text = "Pangkas",
            isClickable = true,
            left = 100,
            top = 1400,
            right = 260,
            bottom = 1520,
            width = 160,
            height = 120
        )

        val honeyboardNode = RawAccessibilityNodeSnapshot(
            mapId = "samsung_ime",
            viewResourceId = "com.samsung.android.honeyboard:id/key_enter",
            packageName = "com.samsung.android.honeyboard",
            text = "Enter",
            isClickable = true,
            left = 600,
            top = 1450,
            right = 710,
            bottom = 1580,
            width = 110,
            height = 130
        )

        val waSearchIcon = RawAccessibilityNodeSnapshot(
            mapId = "wa_search",
            viewResourceId = "com.whatsapp:id/menuitem_search",
            packageName = "com.whatsapp",
            text = "Cari",
            isClickable = true,
            left = 560,
            top = 80,
            right = 640,
            bottom = 160,
            width = 80,
            height = 80
        )

        val root = RawAccessibilityNodeSnapshot(
            mapId = "root_wa_2",
            packageName = "com.whatsapp",
            text = "",
            isClickable = false,
            subNodes = listOf(waSearchIcon, smartCaptureNode, honeyboardNode)
        )

        val snapshot = pruner.pruneTreeWithMetrics(root)

        assertEquals("com.whatsapp", pruner.currentForegroundPackage)
        assertEquals(1, snapshot.elements.size)
        assertEquals("Cari", snapshot.elements.single().text)
        assertFalse(snapshot.elements.any { it.packageName.contains("smartcapture") })
        assertFalse(snapshot.elements.any { it.packageName.contains("honeyboard") })
    }

    @Test
    fun `SetOfMarksGrounder menyuntikkan label semantik pada tombol ikon nir-teks dan computeSignature kebal kedipan kursor`() {
        val unlabeledSend = PrunedUiElement(
            index = 1,
            nodeId = "s1_n1",
            viewResourceId = "com.whatsapp:id/send",
            packageName = "com.whatsapp",
            text = "",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            rect = PrunedUiElement.buildRect(920, 2100, 120, 120)
        )
        val grounder = SetOfMarksGrounder(defaultScreenWidth = 1080, defaultScreenHeight = 2400)
        val enriched = grounder.enrichUnlabeledElements(listOf(unlabeledSend))

        assertEquals("[SoM #1 • Kirim @ kanan-bawah]", enriched.single().text)

        val editA = PrunedUiElement(
            index = 1,
            nodeId = "s1_n1",
            viewResourceId = "com.whatsapp:id/entry",
            packageName = "com.whatsapp",
            text = "Halo Alif|",
            isClickable = true,
            isEditable = true,
            isScrollable = false,
            rect = PrunedUiElement.buildRect(40, 2100, 800, 120)
        )
        val editB = editA.copy(nodeId = "s2_n1", text = "Halo Alif")
        assertEquals(pruner.computeSignature(listOf(editA)), pruner.computeSignature(listOf(editB)))
    }
}
