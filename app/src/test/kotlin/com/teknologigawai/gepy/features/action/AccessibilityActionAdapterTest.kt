package com.teknologigawai.gepy.features.action

import com.teknologigawai.gepy.core.errors.ForbiddenPackageException
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.features.perception.models.PruneMetricsSnapshot
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.perception.models.TextBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityActionAdapterTest {

    private class FakeDriver : NativeAccessibilityDriver {
        override val screenWidthPx: Float = 720f
        override val screenHeightPx: Float = 1600f

        var logicalClickSuccess = true
        var clickNodeCallCount = 0
        var dispatchGestureCallCount = 0
        var lastGestureCoords: Pair<Float, Float>? = null

        override suspend fun clickNode(
            mapId: String?,
            viewResourceId: String?,
            centerX: Int,
            centerY: Int
        ): Boolean {
            clickNodeCallCount++
            return logicalClickSuccess
        }

        override suspend fun typeText(
            mapId: String?,
            viewResourceId: String?,
            centerX: Int,
            centerY: Int,
            text: String,
            clearExisting: Boolean
        ): Boolean = true

        override suspend fun dispatchGesture(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long
        ): Boolean {
            dispatchGestureCallCount++
            lastGestureCoords = Pair(startX, startY)
            return true
        }

        override suspend fun performGlobalAction(action: String): Boolean = true
    }

    private class FakePerception(
        private val signatures: List<String>
    ) : PerceptionPort {
        var callIndex = 0

        override val currentForegroundPackage: String = "com.example.app"

        override suspend fun captureAndPruneScreen(
            forceOcrFallback: Boolean,
            includeNonInteractive: Boolean
        ): List<PrunedUiElement> = emptyList()

        override suspend fun capturePruneSnapshot(
            forceOcrFallback: Boolean
        ): PruneMetricsSnapshot = PruneMetricsSnapshot(emptyList(), 0, 0, "[]")

        override suspend fun readScreenTextBoxes(minConfidence: Double): List<TextBox> = emptyList()

        override suspend fun findText(query: String, exact: Boolean): List<PrunedUiElement> = emptyList()

        override suspend fun waitForText(query: String, timeoutMs: Long, intervalMs: Long): PrunedUiElement? = null

        override fun computeSignature(elements: List<PrunedUiElement>): String {
            val sig = if (callIndex < signatures.size) signatures[callIndex] else signatures.last()
            callIndex++
            return sig
        }

        override suspend fun captureScreenshot() = null

        override fun dispose() {}
    }

    @Test
    fun `tapElement tidak memanggil dispatchGesture fisik saat logical click berhasil`() = runBlocking {
        val driver = FakeDriver().apply { logicalClickSuccess = true }
        // Signature berubah dari sig_pre ke sig_post
        val perception = FakePerception(listOf("sig_pre", "sig_post"))
        val adapter = AccessibilityActionAdapter(perceptionPort = perception, driver = driver)

        val target = PrunedUiElement(
            index = 1,
            nodeId = "btn_play",
            packageName = "com.example.music",
            text = "Play",
            isClickable = true,
            rect = mapOf("x" to 300, "y" to 750, "w" to 120, "h" to 100)
        )

        val outcome = adapter.tapElement(target = target, preSignature = "sig_pre", hitlApproved = false)

        assertEquals(1, driver.clickNodeCallCount)
        // BUG FIX #2 VERIFIKASI: Tidak boleh ada ketukan fisik tambahan yang memicu double-click!
        assertEquals(0, driver.dispatchGestureCallCount)
        assertEquals(ActionOutcome.CORRECT, outcome)
    }

    @Test
    fun `tapElement melakukan fallback ke dispatchGesture fisik saat logical click gagal`() = runBlocking {
        val driver = FakeDriver().apply { logicalClickSuccess = false }
        // Signature berubah setelah fallback fisik
        val perception = FakePerception(listOf("sig_pre", "sig_pre", "sig_post"))
        val adapter = AccessibilityActionAdapter(perceptionPort = perception, driver = driver)

        val target = PrunedUiElement(
            index = 2,
            nodeId = "custom_canvas_node",
            packageName = "com.example.canvas",
            text = "Tab",
            isClickable = false,
            rect = mapOf("x" to 150, "y" to 350, "w" to 100, "h" to 100) // centerX = 200, centerY = 400
        )

        val outcome = adapter.tapElement(target = target, preSignature = "sig_pre", hitlApproved = false)

        assertEquals(1, driver.clickNodeCallCount)
        // Saat klik logis gagal, fallback fisik wajib berjalan
        assertEquals(1, driver.dispatchGestureCallCount)
        assertEquals(Pair(200f, 400f), driver.lastGestureCoords)
        assertEquals(ActionOutcome.CORRECT, outcome)
    }

    @Test(expected = ForbiddenPackageException::class)
    fun `tapElement melempar ForbiddenPackageException pada aplikasi perbankan terlarang`(): Unit = runBlocking {
        val driver = FakeDriver()
        val perception = FakePerception(listOf("sig_1"))
        val adapter = AccessibilityActionAdapter(perceptionPort = perception, driver = driver)

        val bankingTarget = PrunedUiElement(
            index = 1,
            nodeId = "btn_transfer",
            packageName = "com.bca",
            text = "Transfer",
            isClickable = true,
            rect = mapOf("x" to 300, "y" to 750, "w" to 120, "h" to 100)
        )

        adapter.tapElement(target = bankingTarget, preSignature = "sig_1", hitlApproved = true)
    }
}
