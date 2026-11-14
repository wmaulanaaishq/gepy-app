package com.teknologigawai.gepy.features.perception

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import com.teknologigawai.gepy.features.perception.models.PruneMetricsSnapshot
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.perception.models.TextBox
import com.teknologigawai.gepy.service.GepyAccessibilityService
import com.teknologigawai.gepy.service.RawAccessibilityNodeSnapshot
import com.teknologigawai.gepy.service.WindowTreeDump
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * Implementasi `PerceptionPort` (*XML-First In-Memory Context Pruner*).
 *
 * Menarik pohon penuh jendela aktif (`TYPE_APPLICATION`) secara langsung di memori dari
 * [GepyAccessibilityService] (`0 ms` IPC overhead), menyaring seluruh node milik paket Gepy sendiri
 * (*Self-Package Blindness*) beserta overlay sistem transien (`smartcapture`, `systemui`, IME),
 * serta menghasilkan daftar elemen berindeks `[1..N]` dengan penghematan token `> 80%`.
 */
class AccessibilityTreePruner(
    val selfPackageName: String = EnvConfig.SELF_PACKAGE_NAME,
    private val ocrFallback: MlKitOcrFallback = MlKitOcrFallback(),
    private val somGrounder: SetOfMarksGrounder = SetOfMarksGrounder(),
    private val treeDumpProvider: ((Boolean) -> WindowTreeDump?)? = null
) : PerceptionPort {

    companion object {
        /**
         * Daftar paket overlay sistem dan keyboard (IME) yang wajib diabaikan agar tidak
         * membajak status `currentForegroundPackage` maupun mencemari pohon UI aplikasi target.
         */
        val ignoredSystemPackages: Set<String> = setOf(
            "com.samsung.android.app.smartcapture",
            "com.samsung.android.app.cocktailbarservice",
            "com.android.systemui",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.sec.android.inputmethod",
            "com.microsoft.swiftkey",
            "com.baidu.input"
        )

        /**
         * Menghitung hash tata letak struktural layar (hanya `packageName`, `viewResourceId`,
         * dan flag interaksi tanpa teks percakapan dinamis) untuk pencocokan *Playbook Graph*.
         */
        fun computeStructuralLayoutHash(
            foregroundPackage: String,
            elements: List<PrunedUiElement>
        ): String {
            val tokens = sortedSetOf<String>()
            for (el in elements) {
                val resId = el.viewResourceId
                if (!resId.isNullOrEmpty()) {
                    val flags = buildString {
                        if (el.isClickable) append('C')
                        if (el.isEditable) append('E')
                        if (el.isScrollable) append('S')
                    }
                    tokens.add("$resId#$flags")
                }
            }
            return "$foregroundPackage|${tokens.joinToString(",")}"
        }

        private fun stripCursorArtifacts(raw: String): String {
            var s = raw.replace("\u200B", "").trim()
            if (s == "|" || s == "│" || s == "▏") {
                return ""
            }
            if (s.endsWith("|") || s.endsWith("│") || s.endsWith("▏")) {
                s = s.substring(0, s.length - 1).trimEnd()
            }
            return s
        }
    }

    @Volatile
    private var lastForegroundPackage: String = ""

    @Volatile
    private var latestRootForTest: RawAccessibilityNodeSnapshot? = null

    override val currentForegroundPackage: String
        get() = lastForegroundPackage

    private fun isIgnoredNodePackage(pkg: String): Boolean {
        return pkg == selfPackageName || ignoredSystemPackages.contains(pkg) || SafetyRules.isPackageForbidden(pkg)
    }

    /** Memperbarui root node secara manual untuk pengujian unit deterministik. */
    fun updateLatestNodeForTest(rootNode: RawAccessibilityNodeSnapshot?) {
        latestRootForTest = rootNode
        val pkg = rootNode?.packageName?.trim().orEmpty()
        if (pkg.isNotEmpty() && !isIgnoredNodePackage(pkg)) {
            lastForegroundPackage = pkg
        }
    }

    /** Memangkas pohon [rootNode] secara murni di memori menjadi [PruneMetricsSnapshot]. */
    fun pruneTreeWithMetrics(
        rootNode: RawAccessibilityNodeSnapshot,
        includeNonInteractive: Boolean = true
    ): PruneMetricsSnapshot {
        val results = ArrayList<PrunedUiElement>()
        val deduplicationKeys = HashSet<String>()
        var runningIndex = 1
        var rawCount = 0
        var rawChars = 0

        fun traverseNode(node: RawAccessibilityNodeSnapshot) {
            rawCount++
            val pkg = node.packageName.trim()
            rawChars += 160 + node.text.length + node.className.length +
                (node.viewResourceId?.length ?: 0) + pkg.length + node.mapId.length

            // Aturan Mutlak: Self-Package Blindness + Filter Overlay Sistem Samsung/IME
            if (isIgnoredNodePackage(pkg)) {
                return
            }
            if (pkg.isNotEmpty()) {
                lastForegroundPackage = pkg
            }

            val cleanText = if (node.text == "null") {
                ""
            } else {
                PromptInjectionSanitizer.sanitizeUiText(node.text)
            }
            if (SafetyRules.isCredentialField(label = cleanText, resourceId = node.viewResourceId)) {
                return
            }
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable

            val x = node.left
            val y = node.top
            val w = if (node.width > 0) node.width else (node.right - node.left).coerceAtLeast(0)
            val h = if (node.height > 0) node.height else (node.bottom - node.top).coerceAtLeast(0)

            val hasNoBoundsSpecified =
                node.left == 0 && node.top == 0 && node.right == 0 && node.bottom == 0 &&
                    node.width == 0 && node.height == 0
            val hasValidSize = hasNoBoundsSpecified || (w > 4 && h > 4)
            val isInteractive = clickable || editable || scrollable
            val isMeaningful = isInteractive || (includeNonInteractive && cleanText.isNotEmpty())

            if (hasValidSize && isMeaningful) {
                val centerX = x + (w / 2)
                val centerY = y + (h / 2)
                val dedupKey = "$cleanText|$centerX|$centerY|$clickable|$editable|${node.viewResourceId ?: ""}"

                if (deduplicationKeys.add(dedupKey)) {
                    val assignedIdx = runningIndex++
                    results.add(
                        PrunedUiElement(
                            index = assignedIdx,
                            nodeId = node.mapId.ifEmpty { "node_$assignedIdx" },
                            viewResourceId = node.viewResourceId,
                            packageName = pkg,
                            text = cleanText,
                            isClickable = clickable,
                            isEditable = editable,
                            isScrollable = scrollable,
                            rect = PrunedUiElement.buildRect(x, y, w, h)
                        )
                    )
                }
            }

            for (child in node.subNodes) {
                traverseNode(child)
            }
        }

        traverseNode(rootNode)
        val enriched = somGrounder.enrichUnlabeledElements(results)
        val compactJson = serializeForLlm(enriched)
        return PruneMetricsSnapshot(
            elements = enriched,
            rawNodeCount = rawCount,
            rawCharLength = rawChars,
            compactJson = compactJson
        )
    }

    /** Fungsi pembantu yang mengembalikan daftar elemen saja dari [rootNode]. */
    fun pruneActiveTree(
        rootNode: RawAccessibilityNodeSnapshot,
        includeNonInteractive: Boolean = true
    ): List<PrunedUiElement> {
        return pruneTreeWithMetrics(
            rootNode = rootNode,
            includeNonInteractive = includeNonInteractive
        ).elements
    }

    /** Mengubah daftar [elements] menjadi JSON string padat untuk prompt LLM. */
    fun serializeForLlm(elements: List<PrunedUiElement>): String {
        val arr = JSONArray()
        for (el in elements) {
            arr.put(el.toCompactJsonObject())
        }
        return arr.toString()
    }

    private suspend fun pullNativeWindowSnapshot(
        includeNonInteractive: Boolean,
        forceOcrFallback: Boolean
    ): PruneMetricsSnapshot {
        val dump = treeDumpProvider?.invoke(includeNonInteractive)
            ?: GepyAccessibilityService.instance?.dumpActiveWindowTree(includeNonInteractive)
            ?: return PruneMetricsSnapshot(
                elements = emptyList(),
                rawNodeCount = 0,
                rawCharLength = 0,
                compactJson = "[]"
            )

        val fgPkg = dump.foregroundPackage.trim()
        if (fgPkg.isNotEmpty() && !ignoredSystemPackages.contains(fgPkg)) {
            lastForegroundPackage = fgPkg
        }

        if (fgPkg == selfPackageName) {
            return PruneMetricsSnapshot(
                elements = emptyList(),
                rawNodeCount = 0,
                rawCharLength = 0,
                compactJson = "[]"
            )
        }

        val results = ArrayList<PrunedUiElement>(dump.nodes.size)
        val deduplicationKeys = HashSet<String>()
        var runningIndex = 1

        for (raw in dump.nodes) {
            val pkg = raw.packageName.trim().ifEmpty { fgPkg }
            if (isIgnoredNodePackage(pkg)) {
                continue
            }

            val cleanText = if (raw.text == "null") {
                ""
            } else {
                PromptInjectionSanitizer.sanitizeUiText(raw.text)
            }
            val clickable = raw.isClickable
            val editable = raw.isEditable
            val scrollable = raw.isScrollable

            val x = raw.left
            val y = raw.top
            val w = raw.width
            val h = raw.height
            if (w <= 4 || h <= 4) {
                continue
            }

            val viewId = raw.viewResourceId?.trim()?.takeIf { it.isNotEmpty() }
            if (SafetyRules.isCredentialField(label = cleanText, resourceId = viewId)) {
                continue
            }
            val mapId = raw.mapId.ifEmpty { "node_$runningIndex" }

            val centerX = x + (w / 2)
            val centerY = y + (h / 2)
            val dedupKey = "$cleanText|$centerX|$centerY|$clickable|$editable|${viewId ?: ""}"
            if (!deduplicationKeys.add(dedupKey)) {
                continue
            }

            val assignedIdx = runningIndex++
            results.add(
                PrunedUiElement(
                    index = assignedIdx,
                    nodeId = mapId,
                    viewResourceId = viewId,
                    packageName = pkg,
                    text = cleanText,
                    isClickable = clickable,
                    isEditable = editable,
                    isScrollable = scrollable,
                    rect = PrunedUiElement.buildRect(x, y, w, h)
                )
            )
        }

        val canvasBlindSpot = somGrounder.isCanvasBlindSpot(
            foregroundPackage = lastForegroundPackage,
            rawPrunedElements = results
        )
        if (forceOcrFallback &&
            canvasBlindSpot &&
            lastForegroundPackage.isNotEmpty() &&
            lastForegroundPackage != selfPackageName
        ) {
            val ocrElements = ocrFallback.extractElementsFromSilentCapture(
                foregroundPackage = lastForegroundPackage,
                startIndex = runningIndex
            )
            if (ocrElements.isNotEmpty()) {
                results.addAll(ocrElements)
            }
        }

        val enriched = somGrounder.enrichUnlabeledElements(
            elements = results,
            screenWidth = dump.screenWidth,
            screenHeight = dump.screenHeight
        )
        val compactJson = serializeForLlm(enriched)
        return PruneMetricsSnapshot(
            elements = enriched,
            rawNodeCount = dump.rawNodeCount,
            rawCharLength = dump.rawCharLength,
            compactJson = compactJson
        )
    }

    override suspend fun capturePruneSnapshot(forceOcrFallback: Boolean): PruneMetricsSnapshot {
        val injectedRoot = latestRootForTest
        if (injectedRoot != null) {
            return pruneTreeWithMetrics(injectedRoot)
        }
        return pullNativeWindowSnapshot(
            includeNonInteractive = true,
            forceOcrFallback = forceOcrFallback
        )
    }

    override suspend fun captureAndPruneScreen(
        forceOcrFallback: Boolean,
        includeNonInteractive: Boolean
    ): List<PrunedUiElement> {
        val injectedRoot = latestRootForTest
        if (injectedRoot != null) {
            return pruneActiveTree(
                rootNode = injectedRoot,
                includeNonInteractive = includeNonInteractive
            )
        }
        return pullNativeWindowSnapshot(
            includeNonInteractive = includeNonInteractive,
            forceOcrFallback = forceOcrFallback
        ).elements
    }

    override suspend fun readScreenTextBoxes(minConfidence: Double): List<TextBox> {
        val elements = captureAndPruneScreen()
        return elements
            .filter { it.text.isNotEmpty() }
            .map { it.toTextBox(confidence = 1.0) }
            .filter { it.confidence >= minConfidence }
    }

    override suspend fun findText(query: String, exact: Boolean): List<PrunedUiElement> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) {
            return emptyList()
        }
        val elements = captureAndPruneScreen()
        return elements.filter { el ->
            val candidate = el.text.lowercase()
            if (exact) candidate == cleanQuery else candidate.contains(cleanQuery)
        }
    }

    override suspend fun waitForText(
        query: String,
        timeoutMs: Long,
        intervalMs: Long
    ): PrunedUiElement? {
        val startMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startMs < timeoutMs) {
            val matches = findText(query)
            if (matches.isNotEmpty()) {
                return matches.first()
            }
            delay(intervalMs)
        }
        return null
    }

    override suspend fun captureScreenshot(): ByteArray? {
        return ocrFallback.triggerAccessibilityScreenshot()
    }

    /**
     * Menghitung tanda tangan layar yang **stabil lintas dump dan kebal kedipan kursor**.
     */
    override fun computeSignature(elements: List<PrunedUiElement>): String {
        val sb = StringBuilder()
        for (el in elements) {
            val normalizedText = if (el.isEditable) {
                stripCursorArtifacts(el.text)
            } else {
                el.text.replace("\u200B", "").trim()
            }
            val qx = el.centerX / 4
            val qy = el.centerY / 4
            sb.append(el.packageName)
                .append(':')
                .append(el.viewResourceId ?: "")
                .append(':')
                .append(normalizedText)
                .append(':')
            if (el.isClickable) sb.append('C')
            if (el.isEditable) sb.append('E')
            if (el.isScrollable) sb.append('S')
            sb.append(':')
                .append(qx)
                .append(',')
                .append(qy)
                .append(';')
        }
        return sb.toString()
    }

    override fun dispose() {
        ocrFallback.close()
    }
}
