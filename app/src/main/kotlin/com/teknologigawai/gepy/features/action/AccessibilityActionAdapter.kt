package com.teknologigawai.gepy.features.action

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.errors.ForbiddenPackageException
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ActionPort
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.service.GepyAccessibilityService
import kotlinx.coroutines.delay

/**
 * Antarmuka eksekutor tingkat rendah ke `GepyAccessibilityService` agar dapat
 * diuji deterministik pada JVM tanpa memerlukan perangkat fisik.
 */
interface NativeAccessibilityDriver {
    val screenWidthPx: Float
    val screenHeightPx: Float

    suspend fun clickNode(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int
    ): Boolean

    suspend fun typeText(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        text: String,
        clearExisting: Boolean
    ): Boolean

    suspend fun typeText(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        text: String,
        clearExisting: Boolean,
        forceSubmit: Boolean
    ): Boolean = typeText(mapId, viewResourceId, centerX, centerY, text, clearExisting)

    suspend fun dispatchGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean

    suspend fun performGlobalAction(action: String): Boolean
}

/** Implementasi default yang memanggil `GepyAccessibilityService.instance` secara langsung di memori. */
object InMemoryAccessibilityDriver : NativeAccessibilityDriver {
    override val screenWidthPx: Float
        get() = (GepyAccessibilityService.instance?.getScreenMetrics()?.first ?: 1080).toFloat()

    override val screenHeightPx: Float
        get() = (GepyAccessibilityService.instance?.getScreenMetrics()?.second ?: 2400).toFloat()

    override suspend fun clickNode(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int
    ): Boolean {
        val svc = GepyAccessibilityService.instance ?: return false
        return svc.clickNode(mapId, viewResourceId, centerX, centerY)
    }

    override suspend fun typeText(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        text: String,
        clearExisting: Boolean
    ): Boolean {
        val svc = GepyAccessibilityService.instance ?: return false
        return svc.typeText(mapId, viewResourceId, centerX, centerY, text, clearExisting, forceSubmit = false)
    }

    override suspend fun typeText(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        text: String,
        clearExisting: Boolean,
        forceSubmit: Boolean
    ): Boolean {
        val svc = GepyAccessibilityService.instance ?: return false
        return svc.typeText(mapId, viewResourceId, centerX, centerY, text, clearExisting, forceSubmit)
    }

    override suspend fun dispatchGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val svc = GepyAccessibilityService.instance ?: return false
        return svc.dispatchGestureSuspend(startX, startY, endX, endY, durationMs)
    }

    override suspend fun performGlobalAction(action: String): Boolean {
        val svc = GepyAccessibilityService.instance ?: return false
        return svc.executeGlobalNavigation(action)
    }
}

/**
 * Implementasi `ActionPort` yang mengeksekusi aksi sentuhan/ketikan langsung melalui
 * [GepyAccessibilityService], memverifikasi transisi layar secara reaktif dengan `waitStable()`,
 * serta menegakkan *Hardcoded Safety Gatekeeper* (`SafetyRules`).
 */
class AccessibilityActionAdapter(
    private val perceptionPort: PerceptionPort,
    private val driver: NativeAccessibilityDriver = InMemoryAccessibilityDriver,
    private val sovereignDirectExecutionProvider: (() -> Boolean)? = null
) : ActionPort {

    override suspend fun waitStable(
        timeoutMs: Long,
        pollIntervalMs: Long,
        settleCount: Int,
        changedFrom: String?
    ): List<PrunedUiElement> {
        val startMs = System.currentTimeMillis()
        var lastSignature = ""
        var stableIterations = 0
        var hasChanged = changedFrom == null
        var currentElements: List<PrunedUiElement>

        while (true) {
            currentElements = perceptionPort.captureAndPruneScreen()

            // Jika layar depan adalah Gepy sendiri, Self-Package Blindness sengaja mengosongkan pohon UI.
            if (perceptionPort.currentForegroundPackage == EnvConfig.SELF_PACKAGE_NAME) {
                return currentElements
            }

            val currentSig = perceptionPort.computeSignature(currentElements)
            if (!hasChanged && currentSig != changedFrom) {
                hasChanged = true
            }

            if (hasChanged && currentSig == lastSignature) {
                stableIterations++
                if (stableIterations >= settleCount) {
                    return currentElements
                }
            } else {
                stableIterations = 0
                lastSignature = currentSig
            }

            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed + pollIntervalMs > timeoutMs) {
                return currentElements
            }
            delay(pollIntervalMs)
        }
    }

    override suspend fun tapElement(
        target: PrunedUiElement,
        preSignature: String,
        hitlApproved: Boolean
    ): ActionOutcome {
        // 1. Pemeriksaan aplikasi terlarang (m-Banking / dompet digital)
        if (SafetyRules.isPackageForbidden(target.packageName)) {
            throw ForbiddenPackageException(targetIdentifier = target.packageName)
        }

        // 2. Hardcoded Safety Gatekeeper: cegat tombol ireversibel jika belum disetujui HITL
        val needsHitl = SafetyRules.requiresHitlConfirmation(
            label = target.text,
            resourceId = target.viewResourceId ?: target.nodeId,
            sovereignDirectExecution = sovereignDirectExecutionProvider?.invoke() ?: false
        )
        if (needsHitl && !hitlApproved) {
            return ActionOutcome.BLOCKED_BY_HITL
        }

        // 3. Jalur Pertama: Klik logis via GepyAccessibilityService (beserta dukungan klik ancestor baris)
        val logicalSuccess = driver.clickNode(
            mapId = target.nodeId,
            viewResourceId = target.viewResourceId,
            centerX = target.centerX,
            centerY = target.centerY
        )

        var postElements = waitStable(
            timeoutMs = 2_200L,
            pollIntervalMs = 250L,
            changedFrom = preSignature
        )
        var postSignature = perceptionPort.computeSignature(postElements)

        // Jika klik logis berhasil namun tanda tangan belum berubah, beri jeda transisi animasi (350 ms)
        if (logicalSuccess && postSignature == preSignature) {
            delay(350L)
            postElements = perceptionPort.captureAndPruneScreen()
            postSignature = perceptionPort.computeSignature(postElements)
        }

        // 4. Closed-Loop Checker: Fallback ketukan koordinat fisik via dispatchGesture.
        // HANYA jika klik logis GAGAL (!logicalSuccess) pada elemen non-aksesibel atau kanvas grafis.
        // JANGAN pernah memicu fallback fisik bila klik logis sudah berhasil (logicalSuccess == true)
        // untuk mencegah terjadinya ketukan ganda (double-click/toggle glitch) pada tombol yang sama!
        val allowPhysicalFallback = !logicalSuccess && (!needsHitl || hitlApproved)
        if (allowPhysicalFallback) {
            tapCoordinates(target.centerX.toFloat(), target.centerY.toFloat())
            postElements = waitStable(
                timeoutMs = 2_400L,
                pollIntervalMs = 250L,
                changedFrom = preSignature
            )
            postSignature = perceptionPort.computeSignature(postElements)
        }

        return if (postSignature != preSignature) {
            ActionOutcome.CORRECT
        } else {
            ActionOutcome.INEFFECTIVE
        }
    }

    override suspend fun tapCoordinates(x: Float, y: Float): Boolean {
        return driver.dispatchGesture(
            startX = x,
            startY = y,
            endX = x,
            endY = y,
            durationMs = 95L
        )
    }

    override suspend fun longPressCoordinates(
        x: Float,
        y: Float,
        durationMs: Long
    ): Boolean {
        return driver.dispatchGesture(
            startX = x,
            startY = y,
            endX = x,
            endY = y,
            durationMs = durationMs
        )
    }

    override suspend fun setTextElement(
        target: PrunedUiElement,
        text: String,
        preSignature: String,
        clearExisting: Boolean
    ): ActionOutcome {
        if (SafetyRules.isPackageForbidden(target.packageName)) {
            throw ForbiddenPackageException(targetIdentifier = target.packageName)
        }
        if (SafetyRules.isCredentialField(
                label = target.text,
                resourceId = target.viewResourceId ?: target.nodeId
            )
        ) {
            throw ForbiddenPackageException(
                targetIdentifier = target.text.ifEmpty { target.viewResourceId ?: target.nodeId },
                message = "Gepy dilarang mengetikkan PIN, kata sandi, atau kode OTP secara otomatis."
            )
        }

        // Aturan Mutlak #3: Focus-Before-Type + Live FOCUS_INPUT + Clipboard Paste Fallback
        val setOk = driver.typeText(
            mapId = target.nodeId,
            viewResourceId = target.viewResourceId,
            centerX = target.centerX,
            centerY = target.centerY,
            text = text,
            clearExisting = clearExisting
        )

        val postElements = waitStable(
            timeoutMs = 2_200L,
            pollIntervalMs = 250L,
            changedFrom = preSignature
        )
        perceptionPort.computeSignature(postElements)

        return if (setOk) {
            ActionOutcome.CORRECT
        } else {
            ActionOutcome.INEFFECTIVE
        }
    }

    override suspend fun setTextAndSubmitElement(
        target: PrunedUiElement,
        text: String,
        preSignature: String,
        clearExisting: Boolean
    ): ActionOutcome {
        if (SafetyRules.isPackageForbidden(target.packageName)) {
            throw ForbiddenPackageException(targetIdentifier = target.packageName)
        }
        if (SafetyRules.isCredentialField(
                label = target.text,
                resourceId = target.viewResourceId ?: target.nodeId
            )
        ) {
            throw ForbiddenPackageException(
                targetIdentifier = target.text.ifEmpty { target.viewResourceId ?: target.nodeId },
                message = "Gepy dilarang mengetikkan PIN, kata sandi, atau kode OTP secara otomatis."
            )
        }

        // Jalankan Focus-Before-Type disertai pengiriman ACTION_IME_ENTER otomatis (AutoDroid Compound Action)
        val setOk = driver.typeText(
            mapId = target.nodeId,
            viewResourceId = target.viewResourceId,
            centerX = target.centerX,
            centerY = target.centerY,
            text = text,
            clearExisting = clearExisting,
            forceSubmit = true
        )

        var postElements = waitStable(
            timeoutMs = 2_200L,
            pollIntervalMs = 250L,
            changedFrom = preSignature
        )

        // Fallback tombol Cari eksplisit di dekat kolom input (HANYA tombol pencarian aman, BUKAN tombol Kirim HITL)
        if (setOk) {
            val explicitSearchBtn = postElements.firstOrNull { el ->
                if (!el.isClickable || el.isEditable) return@firstOrNull false
                val labelLower = el.text.trim().lowercase()
                val resLower = el.viewResourceId?.lowercase().orEmpty()
                val isSearchSubmit = labelLower in setOf("cari", "search", "telusuri", "go") ||
                    resLower.endsWith("search_go_btn") ||
                    resLower.endsWith("search_mag_icon") ||
                    resLower.endsWith("btn_search")
                isSearchSubmit && !SafetyRules.requiresHitlConfirmation(el.text, el.viewResourceId)
            }
            if (explicitSearchBtn != null) {
                val midSig = perceptionPort.computeSignature(postElements)
                driver.clickNode(
                    mapId = explicitSearchBtn.nodeId,
                    viewResourceId = explicitSearchBtn.viewResourceId,
                    centerX = explicitSearchBtn.centerX,
                    centerY = explicitSearchBtn.centerY
                )
                postElements = waitStable(
                    timeoutMs = 2_000L,
                    pollIntervalMs = 250L,
                    changedFrom = midSig
                )
            }
        }

        perceptionPort.computeSignature(postElements)
        return if (setOk) ActionOutcome.CORRECT else ActionOutcome.INEFFECTIVE
    }

    override suspend fun scrollScreen(
        direction: String,
        preSignature: String,
        amount: Double
    ): ActionOutcome {
        val normalizedDir = direction.trim().uppercase()
        // Semantik SKILL.md: direction = "DOWN" menampilkan konten bawah -> jari menyapu ke ATAS (UP)
        val fingerDir = when (normalizedDir) {
            "UP" -> "DOWN"
            "LEFT" -> "RIGHT"
            "RIGHT" -> "LEFT"
            else -> "UP"
        }

        swipeScreen(
            fingerDirection = fingerDir,
            distance = amount.coerceIn(0.2, 0.8)
        )

        val postElements = waitStable(
            timeoutMs = 2_400L,
            pollIntervalMs = 250L,
            changedFrom = preSignature
        )
        val postSignature = perceptionPort.computeSignature(postElements)
        return if (postSignature != preSignature) {
            ActionOutcome.CORRECT
        } else {
            ActionOutcome.INEFFECTIVE
        }
    }

    override suspend fun swipeScreen(
        fingerDirection: String,
        distance: Double
    ): Boolean {
        val screenW = driver.screenWidthPx
        val screenH = driver.screenHeightPx

        val clamped = distance.coerceIn(0.15, 0.72).toFloat()
        val midX = screenW * 0.5f
        val midY = screenH * 0.5f
        val deltaY = screenH * clamped * 0.5f
        val deltaX = screenW * clamped * 0.5f

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (fingerDirection.trim().uppercase()) {
            "DOWN" -> {
                startX = midX
                startY = midY - deltaY
                endX = midX
                endY = midY + deltaY
            }
            "LEFT" -> {
                startX = midX + deltaX
                startY = midY
                endX = midX - deltaX
                endY = midY
            }
            "RIGHT" -> {
                startX = midX - deltaX
                startY = midY
                endX = midX + deltaX
                endY = midY
            }
            else -> {
                startX = midX
                startY = midY + deltaY
                endX = midX
                endY = midY - deltaY
            }
        }

        return driver.dispatchGesture(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = 360L
        )
    }

    override suspend fun scrollUntil(
        direction: String,
        maxScrolls: Int,
        predicate: (List<PrunedUiElement>) -> Boolean
    ): Boolean {
        var elements = perceptionPort.captureAndPruneScreen()
        if (predicate(elements)) {
            return true
        }

        for (i in 0 until maxScrolls) {
            val preSig = perceptionPort.computeSignature(elements)
            val outcome = scrollScreen(direction, preSig)
            elements = perceptionPort.captureAndPruneScreen()
            if (predicate(elements)) {
                return true
            }
            if (outcome == ActionOutcome.INEFFECTIVE) {
                return false
            }
        }
        return false
    }

    override suspend fun <T : Any> scrollCollect(
        maxScrolls: Int,
        endAfterUnchanged: Int,
        extractor: (PrunedUiElement) -> T?
    ): List<T> {
        val collected = ArrayList<T>()
        val seen = HashSet<T>()
        var unchangedRounds = 0

        for (i in 0..maxScrolls) {
            val elements = perceptionPort.captureAndPruneScreen()
            for (el in elements) {
                val item = extractor(el)
                if (item != null && seen.add(item)) {
                    collected.add(item)
                }
            }
            if (i == maxScrolls) {
                break
            }
            val preSig = perceptionPort.computeSignature(elements)
            val outcome = scrollScreen("DOWN", preSig)
            if (outcome == ActionOutcome.INEFFECTIVE) {
                unchangedRounds++
                if (unchangedRounds >= endAfterUnchanged) {
                    break
                }
            } else {
                unchangedRounds = 0
            }
        }
        return collected
    }

    override suspend fun navigateBack(): Boolean {
        val ok = driver.performGlobalAction("BACK")
        waitStable(timeoutMs = 2_200L)
        return ok
    }

    override suspend fun navigateHome(): Boolean {
        val ok = driver.performGlobalAction("HOME")
        waitStable(timeoutMs = 2_200L)
        return ok
    }

    override suspend fun navigateRecents(): Boolean {
        val ok = driver.performGlobalAction("RECENTS")
        waitStable(timeoutMs = 2_200L)
        return ok
    }
}
