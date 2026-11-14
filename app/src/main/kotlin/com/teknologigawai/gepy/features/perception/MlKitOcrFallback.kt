package com.teknologigawai.gepy.features.perception

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.perception.models.TextBoxSource
import com.teknologigawai.gepy.service.GepyAccessibilityService
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Layanan *fallback* pengenalan teks optik (*On-Device OCR*) berbasis Google ML Kit.
 *
 * Menggunakan tangkapan layar senyap di memori (`AccessibilityService.takeScreenshot` API 30+)
 * dari [GepyAccessibilityService] sehingga **tidak pernah** menulis berkas sementara ke penyimpanan
 * eMMC maupun memicu suara rana / `com.samsung.android.app.smartcapture`.
 */
class MlKitOcrFallback(
    private var recognizer: TextRecognizer? = null
) {
    private val activeRecognizer: TextRecognizer
        get() = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { recognizer = it }

    /** Mengambil tangkapan layar senyap di memori tanpa memicu aksi global screenshot sistem. */
    suspend fun triggerAccessibilityScreenshot(): ByteArray? {
        return GepyAccessibilityService.instance?.takeSilentScreenshotBytes()
    }

    /** Mengambil tangkapan layar senyap `Bitmap` lalu mengekstrak elemen teks OCR secara lokal di memori. */
    suspend fun extractElementsFromSilentCapture(
        foregroundPackage: String,
        minConfidence: Double = 0.3,
        startIndex: Int = 1
    ): List<PrunedUiElement> {
        val service = GepyAccessibilityService.instance ?: return emptyList()
        val bitmap = service.takeSilentScreenshotBitmap() ?: return emptyList()
        return try {
            extractElementsFromBitmap(
                bitmap = bitmap,
                foregroundPackage = foregroundPackage,
                minConfidence = minConfidence,
                startIndex = startIndex
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Mengekstrak daftar [PrunedUiElement] langsung dari `Bitmap` di memori. */
    suspend fun extractElementsFromBitmap(
        bitmap: Bitmap,
        foregroundPackage: String,
        minConfidence: Double = 0.3,
        startIndex: Int = 1
    ): List<PrunedUiElement> = suspendCancellableCoroutine { cont ->
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            activeRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val elements = ArrayList<PrunedUiElement>()
                    var runningIdx = startIndex
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val clean = PromptInjectionSanitizer.sanitizeUiText(line.text)
                            val confidence = line.confidence.toDouble()
                            if (clean.isEmpty() ||
                                confidence < minConfidence ||
                                SafetyRules.isCredentialField(label = clean)
                            ) {
                                continue
                            }
                            val box = line.boundingBox ?: continue
                            val x = box.left
                            val y = box.top
                            val w = box.width()
                            val h = box.height()
                            if (w <= 4 || h <= 4) {
                                continue
                            }
                            elements.add(
                                PrunedUiElement(
                                    index = runningIdx,
                                    nodeId = "ocr_$runningIdx",
                                    packageName = foregroundPackage,
                                    text = clean,
                                    isClickable = true,
                                    isEditable = false,
                                    isScrollable = false,
                                    rect = PrunedUiElement.buildRect(x, y, w, h),
                                    source = TextBoxSource.PIXELS
                                )
                            )
                            runningIdx++
                        }
                    }
                    if (cont.isActive) {
                        cont.resume(elements)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                }
        } catch (_: Throwable) {
            if (cont.isActive) {
                cont.resume(emptyList())
            }
        }
    }

    /** Menutup sumber daya native `TextRecognizer` Google ML Kit secara aman. */
    fun close() {
        val active = recognizer
        recognizer = null
        try {
            active?.close()
        } catch (_: Throwable) {
            // Aman diabaikan pada lingkungan unit test JVM.
        }
    }
}
