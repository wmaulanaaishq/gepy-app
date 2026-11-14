package com.teknologigawai.gepy.features.perception

import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian **Temuan 2 (P1 Native Heap Bitmap Leak)**:
 * Memastikan seluruh objek bitmap (`decoded`, `scaledBitmap`, dan `mutable`) selalu memanggil
 * `.recycle()` melalui blok `finally` baik pada kondisi normal, kegagalan alokasi salinan,
 * maupun ketika rendering/kompresi melempar eksepsi di tengah proses.
 */
class SetOfMarksBitmapRecycleTest {

    private class FakeBitmapHandle(
        override val width: Int,
        override val height: Int,
        override val isMutable: Boolean,
        private val registry: MutableList<FakeBitmapHandle>,
        private val onScale: ((FakeBitmapHandle) -> SoMBitmapHandle)? = null,
        private val onCopyMutable: ((FakeBitmapHandle) -> SoMBitmapHandle?)? = null,
        private val onAnnotateAndCompress: ((SoMImageFormat) -> ByteArray)? = null
    ) : SoMBitmapHandle {

        override var isRecycled: Boolean = false
            private set

        var recycleCallCount: Int = 0
            private set

        init {
            registry.add(this)
        }

        override fun createScaled(targetW: Int, targetH: Int): SoMBitmapHandle {
            onScale?.let { return it(this) }
            return FakeBitmapHandle(
                width = targetW,
                height = targetH,
                isMutable = false,
                registry = registry,
                onCopyMutable = onCopyMutable,
                onAnnotateAndCompress = onAnnotateAndCompress
            )
        }

        override fun copyMutable(): SoMBitmapHandle? {
            onCopyMutable?.let { return it(this) }
            return FakeBitmapHandle(
                width = width,
                height = height,
                isMutable = true,
                registry = registry,
                onAnnotateAndCompress = onAnnotateAndCompress
            )
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
            onAnnotateAndCompress?.let { return it(format) }
            return byteArrayOf(10, 20, 30, 40)
        }

        override fun recycle() {
            recycleCallCount++
            isRecycled = true
        }
    }

    private fun sampleElements(): List<PrunedUiElement> = listOf(
        PrunedUiElement(
            index = 1,
            nodeId = "wa_send_1",
            packageName = "com.whatsapp",
            text = "Kirim",
            viewResourceId = "com.whatsapp:id/send",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            rect = mapOf("x" to 900, "y" to 2050, "w" to 100, "h" to 100)
        )
    )

    @Test
    fun `SetOfMarksBitmapRecycleTest mendaur ulang seluruh bitmap pada eksekusi normal renderCompactAnnotatedJpeg dan PNG`() {
        val allocated = mutableListOf<FakeBitmapHandle>()
        val rawShot = ByteArray(256) { it.toByte() }
        val expectedJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42)

        val grounder = SetOfMarksGrounder().apply {
            bitmapDecoder = {
                FakeBitmapHandle(
                    width = 1080,
                    height = 2400,
                    isMutable = false,
                    registry = allocated,
                    onAnnotateAndCompress = { fmt ->
                        if (fmt == SoMImageFormat.JPEG) expectedJpeg else byteArrayOf(0x89.toByte(), 0x50)
                    }
                )
            }
        }

        val jpegOut = grounder.renderCompactAnnotatedJpeg(rawShot, sampleElements(), maxDimension = 720)
        assertNotNull(jpegOut)
        assertArrayEquals(expectedJpeg, jpegOut)
        assertEquals("Decoded + scaled (immutable) + mutable copy wajib semuanya tercatat", 3, allocated.size)
        assertTrue("100% bitmap handles wajib di-recycle setelah renderCompactAnnotatedJpeg", allocated.all { it.isRecycled })

        allocated.clear()
        val pngOut = grounder.renderAnnotatedScreenshotPng(rawShot, sampleElements())
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50), pngOut)
        assertEquals(2, allocated.size)
        assertTrue("100% bitmap handles wajib di-recycle setelah renderAnnotatedScreenshotPng", allocated.all { it.isRecycled })
    }

    @Test
    fun `SetOfMarksBitmapRecycleTest tetap mendaur ulang seluruh bitmap via finally saat Canvas atau kompresi melempar exception`() {
        val allocated = mutableListOf<FakeBitmapHandle>()
        val rawShot = ByteArray(256) { (it * 3).toByte() }

        val grounder = SetOfMarksGrounder().apply {
            bitmapDecoder = {
                FakeBitmapHandle(
                    width = 1080,
                    height = 2400,
                    isMutable = false,
                    registry = allocated,
                    onAnnotateAndCompress = {
                        throw IllegalArgumentException("Simulasi gagal alokasi Canvas/kompresi JPEG/PNG")
                    }
                )
            }
        }

        val jpegResult = grounder.renderCompactAnnotatedJpeg(rawShot, sampleElements())
        assertArrayEquals("Saat gagal render JPEG, wajib kembali ke rawScreenshotBytes dengan aman", rawShot, jpegResult)
        assertEquals(3, allocated.size)
        assertTrue(
            "Semua 3 bitmap (decoded, scaled, mutable) wajib dipanggil recycle() pada blok finally meski melempar exception",
            allocated.all { it.isRecycled }
        )

        allocated.clear()
        val pngResult = grounder.renderAnnotatedScreenshotPng(rawShot, sampleElements())
        assertNull("Saat gagal render PNG, wajib mengembalikan null tanpa membocorkan native bitmap", pngResult)
        assertEquals(2, allocated.size)
        assertTrue(
            "Semua bitmap pada jalur PNG wajib tetap di-recycle di finally saat terjadi exception",
            allocated.all { it.isRecycled }
        )
    }

    @Test
    fun `SetOfMarksBitmapRecycleTest tidak membocorkan decoded maupun scaledBitmap saat copyMutable gagal atau return null`() {
        val allocatedNullCopy = mutableListOf<FakeBitmapHandle>()
        val rawShot = ByteArray(256) { (it + 7).toByte() }

        val grounderNullCopy = SetOfMarksGrounder().apply {
            bitmapDecoder = {
                FakeBitmapHandle(
                    width = 1080,
                    height = 2400,
                    isMutable = false,
                    registry = allocatedNullCopy,
                    onScale = {
                        FakeBitmapHandle(
                            width = 324,
                            height = 720,
                            isMutable = false,
                            registry = allocatedNullCopy,
                            onCopyMutable = { null }
                        )
                    }
                )
            }
        }

        val resultNull = grounderNullCopy.renderCompactAnnotatedJpeg(rawShot, sampleElements())
        assertArrayEquals(rawShot, resultNull)
        assertEquals(2, allocatedNullCopy.size)
        assertTrue("decoded & scaledBitmap wajib di-recycle saat copyMutable mengembalikan null", allocatedNullCopy.all { it.isRecycled })

        val allocatedThrowingCopy = mutableListOf<FakeBitmapHandle>()
        val grounderThrowCopy = SetOfMarksGrounder().apply {
            bitmapDecoder = {
                FakeBitmapHandle(
                    width = 720,
                    height = 720,
                    isMutable = false,
                    registry = allocatedThrowingCopy,
                    onCopyMutable = { throw IllegalStateException("Simulasi kegagalan Bitmap.copy") }
                )
            }
        }

        val resultThrow = grounderThrowCopy.renderCompactAnnotatedJpeg(rawShot, sampleElements(), maxDimension = 720)
        assertArrayEquals(rawShot, resultThrow)
        assertEquals(1, allocatedThrowingCopy.size)
        assertTrue("decoded wajib di-recycle di finally saat Bitmap.copy melempar exception", allocatedThrowingCopy.all { it.isRecycled })
    }
}
