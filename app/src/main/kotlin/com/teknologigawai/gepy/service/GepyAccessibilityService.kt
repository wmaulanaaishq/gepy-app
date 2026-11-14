package com.teknologigawai.gepy.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.LruCache
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.security.PromptInjectionSanitizer
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Representasi in-memory satu node pohon aksesibilitas tanpa overhead serialisasi IPC. */
data class RawAccessibilityNodeSnapshot(
    val mapId: String,
    val viewResourceId: String? = null,
    val packageName: String,
    val className: String = "",
    val text: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isFocused: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val width: Int = (right - left).coerceAtLeast(0),
    val height: Int = (bottom - top).coerceAtLeast(0),
    val subNodes: List<RawAccessibilityNodeSnapshot> = emptyList()
)

/** Cuplikan pohon jendela aktif di memori untuk `AccessibilityTreePruner`. */
data class WindowTreeDump(
    val foregroundPackage: String,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val rawNodeCount: Int = 0,
    val rawCharLength: Int = 0,
    val nodes: List<RawAccessibilityNodeSnapshot> = emptyList()
)

/** Entri notifikasi sistem terbaru di memori (telah disaring dari aplikasi perbankan & OTP). */
data class CapturedNotificationEntry(
    val packageName: String,
    val summaryText: String,
    val timestampMs: Long
)

/**
 * Layanan Aksesibilitas Native khusus Gepy (`com.teknologigawai.gepy`).
 *
 * Berjalan dalam satu proses ART (*Single-Process Zero-Isolate*) dengan:
 * 1. Pembacaan pohon penuh jendela aktif (`getWindows()` / `TYPE_APPLICATION`), bukan hanya `event.source`.
 * 2. Ekstraksi `text`, `contentDescription` (ikon Cari/Kirim WhatsApp), serta `hintText` (`EditText` kosong).
 * 3. Penyaringan ketat paket milik sendiri (*Self-Package Blindness*) dan overlay sistem Samsung (`smartcapture`, `systemui`, IME).
 * 4. Pengembalian nilai boolean asli dari `performAction` serta protokol *Focus-Before-Type* + fallback `Clipboard Paste`.
 * 5. Tangkapan layar senyap di memori (`takeScreenshot` API 30+) tanpa memicu `com.samsung.android.app.smartcapture`.
 */
class GepyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GepyAccessibilityService? = null
            private set

        const val SELF_PACKAGE: String = EnvConfig.SELF_PACKAGE_NAME

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

        private val _foregroundPackageFlow = MutableStateFlow("")
        val foregroundPackageFlow: StateFlow<String> = _foregroundPackageFlow.asStateFlow()

        /** Callback opsional saat pengguna menekan pintasan tombol aksesibilitas Android (atau tahan tombol Volume). */
        @Volatile
        var onAccessibilityShortcutTriggered: (() -> Unit)? = null

        /**
         * Watchdog Self-Heal untuk memastikan `GepyRuntimeContainer` dan `OverlayHudWindowManager`
         * (Floating Glass Bubble) tetap tampil meskipun `MainActivity` disapu dari Recents.
         */
        @Volatile
        var onEnsureRuntimeAndOverlayAlive: (() -> Unit)? = null

        private const val MAX_RECENT_NOTIFICATIONS = 15
        private val recentNotificationBuffer = ArrayDeque<CapturedNotificationEntry>()

        /** Mengembalikan daftar notifikasi terbaru yang aman (tanpa m-Banking/OTP). */
        @Synchronized
        fun getRecentNotifications(limit: Int = 8): List<CapturedNotificationEntry> {
            return recentNotificationBuffer.takeLast(limit.coerceIn(1, MAX_RECENT_NOTIFICATIONS)).reversed()
        }

        /** Menambahkan notifikasi ke buffer (digunakan oleh service maupun unit test). */
        @Synchronized
        fun recordNotificationIfSafe(
            packageName: String,
            rawText: String,
            timestampMs: Long = System.currentTimeMillis()
        ): Boolean {
            val cleanPkg = packageName.trim()
            val cleanText = PromptInjectionSanitizer.sanitizeUiText(rawText)
            if (cleanPkg.isEmpty() || cleanText.isEmpty() || cleanPkg == SELF_PACKAGE) {
                return false
            }
            if (SafetyRules.isPackageForbidden(cleanPkg) ||
                SafetyRules.isCredentialField(label = cleanText)
            ) {
                return false
            }
            if (recentNotificationBuffer.size >= MAX_RECENT_NOTIFICATIONS) {
                recentNotificationBuffer.removeFirst()
            }
            recentNotificationBuffer.addLast(
                CapturedNotificationEntry(
                    packageName = cleanPkg,
                    summaryText = cleanText,
                    timestampMs = timestampMs
                )
            )
            return true
        }

        @Synchronized
        fun clearRecentNotifications() {
            recentNotificationBuffer.clear()
        }

        /**
         * Daftar paket transien sistem/overlay/keyboard yang dilarang menimpa pembacaan aplikasi target.
         */
        val IGNORED_SYSTEM_PACKAGES: Set<String> = setOf(
            SELF_PACKAGE,
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
         * Memeriksa apakah layanan aksesibilitas Gepy sudah diaktifkan di Pengaturan Sistem Android.
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) {
                return true
            }
            val enabled = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
            } catch (_: Throwable) {
                0
            }
            if (enabled != 1) {
                return false
            }
            val enabledServices = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (_: Throwable) {
                null
            } ?: return false

            val pkg = context.packageName
            val fullComponent = "$pkg/${GepyAccessibilityService::class.java.canonicalName}"
            val shortComponent = "$pkg/.service.GepyAccessibilityService"
            val legacyShortComponent = "$pkg/.GepyAccessibilityService"
            return enabledServices.split(':').any { entry ->
                entry.equals(fullComponent, ignoreCase = true) ||
                    entry.equals(shortComponent, ignoreCase = true) ||
                    entry.equals(legacyShortComponent, ignoreCase = true)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private val snapshotCounter = AtomicInteger(0)

    private val nodeCache = object : LruCache<String, AccessibilityNodeInfo>(1500) {}

    @Volatile
    private var lastObservedAppPackage: String = ""

    @Volatile
    private var lastOverlayWatchdogTimestampMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnectedFlow.value = true
        GepyLog.i("AccessibilityService", "GepyAccessibilityService connected (Zero-Isolate In-Memory)")
        mainHandler.post {
            try {
                com.teknologigawai.gepy.ui.GepyRuntimeContainer.getOrCreateOrchestrator(applicationContext)
                onEnsureRuntimeAndOverlayAlive?.invoke()
            } catch (_: Throwable) {
            }
        }
        try {
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
                serviceInfo = info
            }
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController?) {
                        GepyLog.i("AccessibilityService", "Pintasan tombol Aksesibilitas diketuk — memicu pendengaran Gepy")
                        onAccessibilityShortcutTriggered?.invoke()
                    }
                }
            )
        } catch (_: Exception) {
            // Gunakan konfigurasi XML bawaan apabila OS menolak perubahan dinamis.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) {
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val textJoined = event.text
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" - ")
            recordNotificationIfSafe(packageName = pkg, rawText = textJoined)
            return
        }
        if (IGNORED_SYSTEM_PACKAGES.contains(pkg)) {
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            lastObservedAppPackage = pkg
            _foregroundPackageFlow.value = pkg
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastOverlayWatchdogTimestampMs >= 10_000L) {
                lastOverlayWatchdogTimestampMs = nowMs
                mainHandler.post {
                    try {
                        onEnsureRuntimeAndOverlayAlive?.invoke()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        // Tidak ada state yang perlu dibatalkan paksa.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        nodeCache.evictAll()
        if (instance === this) {
            instance = null
            _isConnectedFlow.value = false
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        nodeCache.evictAll()
        if (instance === this) {
            instance = null
            _isConnectedFlow.value = false
        }
        workerExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Membaca dimensi piksel riil layar perangkat (contoh: 720x1600 pada Samsung Galaxy A06).
     */
    fun getScreenMetrics(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width().coerceAtLeast(320), bounds.height().coerceAtLeast(480))
            } else {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(dm)
                Pair(dm.widthPixels.coerceAtLeast(320), dm.heightPixels.coerceAtLeast(480))
            }
        } catch (_: Exception) {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels.coerceAtLeast(320), dm.heightPixels.coerceAtLeast(480))
        }
    }

    private data class ActiveWindowSelection(
        val rootNode: AccessibilityNodeInfo?,
        val foregroundPackage: String
    )

    /**
     * Menemukan root `AccessibilityNodeInfo` dari jendela aplikasi aktif teratas,
     * melewati overlay milik Gepy sendiri (*Self-Package Blindness*) dan jendela sistem/IME.
     */
    private fun findActiveAppWindow(): ActiveWindowSelection {
        var sawSelfAppWindow = false
        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                val appWindows = windowList
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedWith(
                        compareByDescending<AccessibilityWindowInfo> { it.isActive }
                            .thenByDescending { it.isFocused }
                            .thenByDescending { it.layer }
                    )

                for (window in appWindows) {
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString()?.trim().orEmpty()
                    if (pkg == SELF_PACKAGE) {
                        sawSelfAppWindow = true
                        continue
                    }
                    if (pkg.isEmpty() || IGNORED_SYSTEM_PACKAGES.contains(pkg)) {
                        continue
                    }
                    lastObservedAppPackage = pkg
                    _foregroundPackageFlow.value = pkg
                    return ActiveWindowSelection(rootNode = root, foregroundPackage = pkg)
                }
            }
        } catch (_: Exception) {
            // Lanjut ke fallback rootInActiveWindow.
        }

        val fallbackRoot = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }

        if (fallbackRoot != null) {
            val pkg = fallbackRoot.packageName?.toString()?.trim().orEmpty()
            if (pkg == SELF_PACKAGE) {
                return ActiveWindowSelection(rootNode = null, foregroundPackage = SELF_PACKAGE)
            }
            if (pkg.isNotEmpty() && !IGNORED_SYSTEM_PACKAGES.contains(pkg)) {
                lastObservedAppPackage = pkg
                _foregroundPackageFlow.value = pkg
                return ActiveWindowSelection(rootNode = fallbackRoot, foregroundPackage = pkg)
            }
        }

        val resolvedPkg = if (sawSelfAppWindow) SELF_PACKAGE else lastObservedAppPackage
        return ActiveWindowSelection(rootNode = null, foregroundPackage = resolvedPkg)
    }

    /**
     * Mengekstrak pohon UI jendela aktif secara sinkron di memori (`< 1.5 ms`) untuk `AccessibilityTreePruner`.
     */
    fun dumpActiveWindowTree(includeNonInteractive: Boolean = true): WindowTreeDump {
        val (screenW, screenH) = getScreenMetrics()
        val selection = findActiveAppWindow()
        val root = selection.rootNode
        if (root == null) {
            return WindowTreeDump(
                foregroundPackage = selection.foregroundPackage,
                screenWidth = screenW,
                screenHeight = screenH,
                rawNodeCount = 0,
                rawCharLength = 0,
                nodes = emptyList()
            )
        }

        val snapId = snapshotCounter.incrementAndGet()
        nodeCache.evictAll()
        val nodesOut = ArrayList<RawAccessibilityNodeSnapshot>(64)
        var rawCount = 0
        var rawCharLen = 0
        var localIdx = 0

        fun hasVisibleChildLabel(parent: AccessibilityNodeInfo, depthLeft: Int): Boolean {
            if (depthLeft <= 0) return false
            val count = parent.childCount
            for (i in 0 until count) {
                val child = parent.getChild(i) ?: continue
                if (!child.isVisibleToUser) continue
                val t = child.text?.toString()?.trim().orEmpty()
                val cd = child.contentDescription?.toString()?.trim().orEmpty()
                val h = child.hintText?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() || cd.isNotEmpty() || h.isNotEmpty()) {
                    return true
                }
                if (hasVisibleChildLabel(child, depthLeft - 1)) {
                    return true
                }
            }
            return false
        }

        fun traverse(
            node: AccessibilityNodeInfo,
            depth: Int,
            clickableAncestorDepth: Int,
            inheritedViewId: String?
        ) {
            if (depth > 28 || nodesOut.size >= 250) {
                return
            }
            rawCount++

            if (!node.isVisibleToUser) {
                return
            }

            val pkg = node.packageName?.toString()?.trim().orEmpty()
            if (pkg.isNotEmpty() && IGNORED_SYSTEM_PACKAGES.contains(pkg)) {
                return
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()

            val actionIds = node.actionList?.map { it.id }?.toSet() ?: emptySet()
            val className = node.className?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }

            val directClickable = node.isClickable ||
                actionIds.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)
            val editable = node.isEditable ||
                actionIds.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id) ||
                className.contains("EditText", ignoreCase = true)
            val scrollable = node.isScrollable ||
                actionIds.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id) ||
                actionIds.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)

            val rawText = node.text?.toString()?.trim().orEmpty()
            val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
            val hintText = node.hintText?.toString()?.trim().orEmpty()
            val showingHint = node.isShowingHintText

            rawCharLen += 140 + rawText.length + contentDesc.length + hintText.length +
                className.length + (viewId?.length ?: 0) + pkg.length

            val resolvedLabel = when {
                editable && (showingHint || (rawText.isEmpty() && hintText.isNotEmpty())) -> {
                    val hintVal = hintText.ifEmpty { rawText }
                    if (hintVal.isNotEmpty()) "[Hint] $hintVal" else ""
                }
                rawText.isNotEmpty() && rawText != "null" -> rawText
                contentDesc.isNotEmpty() && contentDesc != "null" -> contentDesc
                hintText.isNotEmpty() && hintText != "null" -> "[Hint] $hintText"
                else -> ""
            }

            val inheritedClickable = !directClickable &&
                resolvedLabel.isNotEmpty() &&
                clickableAncestorDepth in 1..6
            val effectiveClickable = directClickable || inheritedClickable

            val validSize = width > 4 && height > 4 &&
                rect.right > 0 && rect.bottom > 0 &&
                rect.left < screenW && rect.top < screenH

            val isRedundantBlankWrapper = directClickable &&
                !editable &&
                !scrollable &&
                resolvedLabel.isEmpty() &&
                node.childCount > 0 &&
                hasVisibleChildLabel(node, 4)

            val isInteractive = effectiveClickable || editable || scrollable
            val isMeaningful = !isRedundantBlankWrapper &&
                (isInteractive || (includeNonInteractive && resolvedLabel.isNotEmpty()))

            if (validSize && isMeaningful) {
                localIdx++
                val mapId = "s${snapId}_n$localIdx"
                nodeCache.put(mapId, node)

                val effectiveViewId = viewId ?: if (inheritedClickable) inheritedViewId else null
                nodesOut.add(
                    RawAccessibilityNodeSnapshot(
                        mapId = mapId,
                        viewResourceId = effectiveViewId,
                        packageName = pkg.ifEmpty { selection.foregroundPackage },
                        className = className,
                        text = resolvedLabel,
                        isClickable = effectiveClickable,
                        isEditable = editable,
                        isScrollable = scrollable,
                        isFocused = node.isFocused,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        width = width,
                        height = height
                    )
                )
            }

            val nextClickableDepth = when {
                directClickable -> 1
                clickableAncestorDepth in 1..5 -> clickableAncestorDepth + 1
                else -> 0
            }
            val nextInheritedId = when {
                directClickable && viewId != null -> viewId
                clickableAncestorDepth in 1..5 -> inheritedViewId
                else -> null
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1, nextClickableDepth, nextInheritedId)
            }
        }

        traverse(root, 0, 0, null)

        return WindowTreeDump(
            foregroundPackage = selection.foregroundPackage,
            screenWidth = screenW,
            screenHeight = screenH,
            rawNodeCount = rawCount,
            rawCharLength = rawCharLen,
            nodes = nodesOut
        )
    }

    /**
     * Mengeksekusi klik logis (`ACTION_CLICK`) pada node atau leluhur terdekatnya yang `clickable`,
     * serta mengembalikan status keberhasilan boolean yang sebenarnya.
     */
    fun clickNode(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int
    ): Boolean {
        val target = resolveLiveNode(
            mapId = mapId,
            viewResourceId = viewResourceId,
            centerX = centerX,
            centerY = centerY,
            requireEditable = false
        ) ?: return false

        return performClickSelfOrAncestor(target)
    }

    private fun performClickSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops <= 7) {
            try {
                current.refresh()
            } catch (_: Exception) {
                // Abaikan jika refresh tidak didukung pada node sintetis.
            }
            val canClick = current.isClickable ||
                (current.actionList?.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id
                } == true)
            if (canClick) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            current = current.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Versi coroutine `suspend` untuk *Focus-Before-Type*. */
    suspend fun typeText(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        text: String,
        clearExisting: Boolean = false,
        forceSubmit: Boolean = false
    ): Boolean = suspendCancellableCoroutine { cont ->
        typeTextAsync(
            mapId = mapId,
            viewResourceId = viewResourceId,
            centerX = centerX,
            centerY = centerY,
            text = text,
            clearExisting = clearExisting,
            forceSubmit = forceSubmit
        ) { ok ->
            if (cont.isActive) {
                cont.resume(ok)
            }
        }
    }

    /**
     * Menjalankan protokol *Focus-Before-Type* secara asinkron di thread pekerja latar belakang,
     * mencari ulang node `FOCUS_INPUT` yang segar (mengatasi bilah pencarian dinamis WhatsApp),
     * menyuntikkan teks via `ACTION_SET_TEXT`, dan menyediakan fallback `Clipboard + ACTION_PASTE`.
     */
    fun typeTextAsync(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        text: String,
        clearExisting: Boolean,
        forceSubmit: Boolean = false,
        callback: (Boolean) -> Unit
    ) {
        workerExecutor.execute {
            var success = false
            try {
                val initialNode = resolveLiveNode(
                    mapId = mapId,
                    viewResourceId = viewResourceId,
                    centerX = centerX,
                    centerY = centerY,
                    requireEditable = false
                )

                var focusedOrClicked = false
                if (initialNode != null) {
                    val fOk = initialNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val cOk = performClickSelfOrAncestor(initialNode)
                    focusedOrClicked = fOk || cOk
                }

                if (!focusedOrClicked && centerX > 0 && centerY > 0) {
                    dispatchTapSync(centerX.toFloat(), centerY.toFloat(), 85L)
                }

                // Beri waktu singkat bagi aplikasi target (mis. WhatsApp/Instagram/YouTube) untuk memunculkan EditText fokus
                Thread.sleep(240)

                val initialEditable = if (initialNode != null && isNodeEditable(initialNode)) initialNode else null
                var editableTarget = findFreshEditableNode(viewResourceId, centerX, centerY)
                    ?: initialEditable

                // Jika EditText belum muncul (misal baru mengetuk tombol/bilah Cari yang membuka halaman pencarian baru),
                // lakukan polling singkat hingga 4x (total ~880ms) dan fallback ketuk fisik koordinat bila perlu
                var pollAttempt = 0
                while (editableTarget == null && pollAttempt < 4) {
                    if (pollAttempt == 1 && centerX > 0 && centerY > 0) {
                        dispatchTapSync(centerX.toFloat(), centerY.toFloat(), 90L)
                    }
                    Thread.sleep(220)
                    editableTarget = findFreshEditableNode(viewResourceId, centerX, centerY)
                        ?: findFreshEditableNode(null, centerX, centerY)
                    pollAttempt++
                }

                if (editableTarget != null) {
                    try {
                        editableTarget.refresh()
                    } catch (_: Exception) {
                    }
                    editableTarget.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                    if (clearExisting) {
                        val emptyArgs = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                ""
                            )
                        }
                        editableTarget.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            emptyArgs
                        )
                    }

                    val setArgs = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    success = editableTarget.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        setArgs
                    )

                    // Fallback Clipboard + ACTION_PASTE jika komponen kustom menolak ACTION_SET_TEXT
                    if (!success) {
                        success = pasteViaClipboard(editableTarget, text)
                    }

                    // Jika penyuntikan teks berhasil pada kolom pencarian aplikasi (selain WhatsApp & Instagram
                    // yang menggunakan live dropdown) atau saat forceSubmit diminta (SET_TEXT_AND_SUBMIT),
                    // tekan tombol Search/Enter IME secara otomatis.
                    if (success && (forceSubmit || shouldTriggerSearchImeEnter(editableTarget))) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Thread.sleep(120)
                            editableTarget.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                success = false
            }

            mainHandler.post {
                callback(success)
            }
        }
    }

    private fun pasteViaClipboard(targetNode: AccessibilityNodeInfo, text: String): Boolean {
        val latch = CountDownLatch(1)
        var previousClip: ClipData? = null
        mainHandler.post {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                previousClip = runCatching { clipboard.primaryClip }.getOrNull()
                clipboard.setPrimaryClip(ClipData.newPlainText("gepy_text", text))
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val pasted = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        val clipToRestore = previousClip
        mainHandler.postDelayed({
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val current = clipboard.primaryClip
                val isStillOurs = current?.description?.label?.toString() == "gepy_text" &&
                    current.itemCount > 0 &&
                    current.getItemAt(0).text?.toString() == text
                if (isStillOurs) {
                    if (clipToRestore != null) {
                        clipboard.setPrimaryClip(clipToRestore)
                    } else {
                        clipboard.clearPrimaryClip()
                    }
                }
            } catch (_: Exception) {
            }
        }, 280L)
        return pasted
    }

    private fun resolveLiveNode(
        mapId: String?,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        requireEditable: Boolean
    ): AccessibilityNodeInfo? {
        if (!mapId.isNullOrEmpty()) {
            val cached = nodeCache.get(mapId)
            if (cached != null) {
                val refreshed = try {
                    cached.refresh()
                } catch (_: Exception) {
                    false
                }
                if (refreshed && (!requireEditable || isNodeEditable(cached))) {
                    return cached
                }
            }
        }

        val root = findActiveAppWindow().rootNode ?: return null
        return searchTreeBestMatch(
            root = root,
            viewResourceId = viewResourceId,
            centerX = centerX,
            centerY = centerY,
            requireEditable = requireEditable
        )
    }

    private fun findFreshEditableNode(
        viewResourceId: String?,
        centerX: Int,
        centerY: Int
    ): AccessibilityNodeInfo? {
        val root = findActiveAppWindow().rootNode ?: return null

        // Prioritas 1: Node yang sedang memegang FOCUS_INPUT di jendela aplikasi target,
        // HANYA jika cocok dengan viewResourceId atau berada di sekitar koordinat target (mencegah salah ketik pada form multi-input).
        val inputFocused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (inputFocused != null && isNodeEditable(inputFocused)) {
            val pkg = inputFocused.packageName?.toString()?.trim().orEmpty()
            if (!IGNORED_SYSTEM_PACKAGES.contains(pkg)) {
                val focusedId = inputFocused.viewIdResourceName?.trim()
                val idMatches = viewResourceId.isNullOrBlank() ||
                    (!focusedId.isNullOrEmpty() && focusedId.equals(viewResourceId, ignoreCase = true))
                val rect = Rect().also { inputFocused.getBoundsInScreen(it) }
                val coordMatches = (centerX <= 0 && centerY <= 0) ||
                    rect.contains(centerX, centerY) ||
                    (abs(rect.centerX() - centerX) + abs(rect.centerY() - centerY) <= 180)
                if (idMatches && coordMatches) {
                    return inputFocused
                }
            }
        }

        // Prioritas 2: Cari node editable berdasarkan viewResourceId atau kedekatan koordinat
        return searchTreeBestMatch(
            root = root,
            viewResourceId = viewResourceId,
            centerX = centerX,
            centerY = centerY,
            requireEditable = true
        ) ?: inputFocused?.takeIf {
            val pkg = it.packageName?.toString()?.trim().orEmpty()
            !IGNORED_SYSTEM_PACKAGES.contains(pkg)
        }
    }

    private fun isNodeEditable(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val hasSetText = node.actionList?.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
        } == true
        return node.isEditable || hasSetText || cls.contains("EditText", ignoreCase = true)
    }

    /**
     * Menentukan apakah kolom input adalah kotak pencarian yang perlu menekan `ACTION_IME_ENTER`
     * secara otomatis setelah penyuntikan teks (misal YouTube, TikTok, Shopee, Tokopedia, Chrome, Play Store).
     * Dikecualikan untuk WhatsApp & Instagram karena keduanya menampilkan daftar hasil langsung (*live filter*).
     */
    private fun shouldTriggerSearchImeEnter(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString()?.trim()?.lowercase().orEmpty()
        if (pkg in setOf("com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.instagram.lite")) {
            return false
        }
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val hint = node.hintText?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val combined = "$viewId $hint $desc"

        val chatExclusionKeywords = listOf(
            "message", "pesan", "chat", "entry", "composer", "comment", "komentar", "reply", "balas", "caption"
        )
        if (chatExclusionKeywords.any { combined.contains(it) }) {
            return false
        }

        val searchKeywords = listOf("search", "cari", "telusuri", "query", "url_bar", "et_search")
        return searchKeywords.any { combined.contains(it) }
    }

    private fun searchTreeBestMatch(
        root: AccessibilityNodeInfo,
        viewResourceId: String?,
        centerX: Int,
        centerY: Int,
        requireEditable: Boolean
    ): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 28) return
            if (!node.isVisibleToUser) return

            val editableOk = !requireEditable || isNodeEditable(node)
            if (editableOk) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val nodeId = node.viewIdResourceName?.trim()
                val idMatches = !viewResourceId.isNullOrEmpty() &&
                    !nodeId.isNullOrEmpty() &&
                    nodeId.equals(viewResourceId, ignoreCase = true)

                val cx = rect.centerX()
                val cy = rect.centerY()
                val manhattan = if (centerX > 0 || centerY > 0) {
                    abs(cx - centerX) + abs(cy - centerY)
                } else {
                    1000
                }
                val containsPoint = centerX > 0 && centerY > 0 && rect.contains(centerX, centerY)

                if (idMatches || containsPoint || requireEditable) {
                    var score = manhattan
                    if (idMatches) score -= 50_000
                    if (containsPoint) score -= 10_000
                    if (node.isFocused) score -= 5_000

                    if (score < bestScore) {
                        bestScore = score
                        bestNode = node
                    }
                }
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return bestNode
    }

    private fun dispatchTapSync(x: Float, y: Float, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            dispatchLinearGesture(
                startX = x,
                startY = y,
                endX = x,
                endY = y,
                durationMs = durationMs
            ) { completed ->
                ok = completed
                latch.countDown()
            }
        }
        latch.await(durationMs + 700L, TimeUnit.MILLISECONDS)
        return ok
    }

    /** Versi coroutine `suspend` untuk injeksi gestur sentuhan/sapuan. */
    suspend fun dispatchGestureSuspend(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        dispatchLinearGesture(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs
        ) { ok ->
            if (cont.isActive) {
                cont.resume(ok)
            }
        }
    }

    /**
     * Mengeksekusi gestur ketukan/sapuan dengan koordinat yang diamankan terhadap batas resolusi layar perangkat.
     */
    fun dispatchLinearGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        callback: (Boolean) -> Unit
    ) {
        val (screenW, screenH) = getScreenMetrics()
        val maxX = (screenW - 2).coerceAtLeast(2).toFloat()
        val maxY = (screenH - 2).coerceAtLeast(2).toFloat()

        val sx = startX.coerceIn(1f, maxX)
        val sy = startY.coerceIn(1f, maxY)
        val ex = endX.coerceIn(1f, maxX)
        val ey = endY.coerceIn(1f, maxY)
        val safeDuration = durationMs.coerceIn(50L, 4000L)

        try {
            val path = Path().apply {
                moveTo(sx, sy)
                if (sx != ex || sy != ey) {
                    lineTo(ex, ey)
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        callback(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        callback(false)
                    }
                },
                mainHandler
            )
            if (!dispatched) {
                callback(false)
            }
        } catch (_: Exception) {
            callback(false)
        }
    }

    /**
     * Menjalankan aksi navigasi sistem global (`BACK`, `HOME`, `RECENTS`).
     * Tidak pernah memanggil `GLOBAL_ACTION_TAKE_SCREENSHOT` agar bebas dari bug `smartcapture` Samsung.
     */
    fun executeGlobalNavigation(actionName: String): Boolean {
        return when (actionName.trim().uppercase()) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }

    /**
     * Mengambil `Bitmap` tangkapan layar senyap di memori pada Android 11+ (`API >= 30`)
     * tanpa menyentuh disk dan tanpa memicu `com.samsung.android.app.smartcapture`.
     */
    suspend fun takeSilentScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        workerExecutor.execute {
                            val hwBuffer = screenshot.hardwareBuffer
                            var swBitmap: Bitmap? = null
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    hwBuffer,
                                    screenshot.colorSpace
                                )
                                if (hwBitmap != null) {
                                    swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                }
                            } catch (_: Exception) {
                                swBitmap = null
                            } finally {
                                try {
                                    hwBuffer.close()
                                } catch (_: Exception) {
                                }
                            }
                            if (cont.isActive) {
                                cont.resume(swBitmap)
                            } else {
                                swBitmap?.recycle()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }
                }
            )
        } catch (_: Exception) {
            if (cont.isActive) {
                cont.resume(null)
            }
        }
    }

    /**
     * Mengambil tangkapan layar senyap dalam format byte JPEG di memori.
     */
    suspend fun takeSilentScreenshotBytes(): ByteArray? {
        val bitmap = takeSilentScreenshotBitmap() ?: return null
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
            baos.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Memantau dan mengklik secara otomatis hasil pencarian Spotify (Play button, Top Result, atau baris lagu)
     * sesaat setelah intent pencarian diluncurkan, sehingga musik langsung berputar otomatis (Zero-UI Autoplay).
     */
    fun autoPlaySpotifySearchResult(query: String) {
        workerExecutor.execute {
            try {
                val cleanQuery = query.trim().lowercase()
                val queryTokens = cleanQuery.split(Regex("""[\s\-._,]+""")).filter { it.length >= 2 }
                val (screenW, screenH) = getScreenMetrics()
                val searchAreaTop = (screenH * 0.08f).toInt()
                val searchAreaBottom = (screenH * 0.82f).toInt() // Kecualikan mini player dan bottom navigation bar

                // 1. Tunggu hingga com.spotify.music berada di latar depan (maks 3.5 detik, poll tiap 150ms)
                var spotifyActive = false
                for (i in 0 until 24) {
                    Thread.sleep(150)
                    val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
                    val currentPkg = activeRoot?.packageName?.toString() ?: _foregroundPackageFlow.value.ifEmpty { lastObservedAppPackage }
                    if (currentPkg == "com.spotify.music") {
                        spotifyActive = true
                        break
                    }
                }
                if (!spotifyActive) {
                    GepyLog.w("SpotifyAutoPlay", "Spotify tidak terdeteksi di latar depan dalam 3.5 detik.")
                    return@execute
                }

                // 2. Beri jeda 400ms agar Spotify selesai merender tampilan
                Thread.sleep(400)

                fun findNodeDeep(node: AccessibilityNodeInfo?, depth: Int = 0, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                    if (node == null || depth > 25) return null
                    return try {
                        if (predicate(node)) return node
                        for (i in 0 until node.childCount) {
                            val f = findNodeDeep(node.getChild(i), depth + 1, predicate)
                            if (f != null) return f
                        }
                        null
                    } catch (_: Throwable) {
                        null
                    }
                }

                // 2a. Periksa apakah antarmuka sedang menampilkan Now Playing layar penuh lagu lama
                // (misal judul lagu lama, cover art besar, tombol ciutkan "<" atau "Memainkan dari Playlist")
                val initialRoot = try { rootInActiveWindow } catch (_: Exception) { null }
                if (initialRoot != null) {
                    val isNowPlayingScreen = findNodeDeep(initialRoot) { node ->
                        val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
                        val text = node.text?.toString()?.lowercase().orEmpty()
                        cd.contains("ciutkan") || cd.contains("collapse") || cd.contains("minimize") ||
                            text.contains("memainkan dari") || text.contains("playing from")
                    }
                    if (isNowPlayingScreen != null) {
                        GepyLog.i("SpotifyAutoPlay", "Now Playing layar penuh lagu lama terdeteksi, menciutkan...")
                        val collapseBtn = findNodeDeep(initialRoot) { node ->
                            val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
                            cd.contains("ciutkan") || cd.contains("collapse") || cd.contains("minimize")
                        }
                        if (collapseBtn != null) {
                            performClickSelfOrAncestor(collapseBtn)
                        } else {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        Thread.sleep(450)
                    }
                }

                // 3. Polling pencarian elemen Hasil Pencarian (Hasil Teratas, Baris Lagu/Artis, atau Play)
                var clicked = false
                for (attempt in 0 until 6) {
                    val root = try {
                        rootInActiveWindow
                    } catch (_: Exception) {
                        null
                    }
                    if (root == null) {
                        Thread.sleep(250)
                        continue
                    }

                    val candidateNodes = mutableListOf<AccessibilityNodeInfo>()
                    fun collect(node: AccessibilityNodeInfo?, depth: Int) {
                        if (node == null || depth > 25) return
                        try {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            val centerY = rect.centerY()
                            val inSearchArea = centerY in searchAreaTop..searchAreaBottom && rect.width() > 8 && rect.height() > 8

                            if (node.isVisibleToUser && inSearchArea) {
                                candidateNodes.add(node)
                            }
                            for (i in 0 until node.childCount) {
                                collect(node.getChild(i), depth + 1)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    collect(root, 0)

                    // Prioritas 1: Baris Lagu / Hasil Teratas yang cocok dengan query atau query tokens
                    val matchRow = candidateNodes.firstOrNull { node ->
                        val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
                        val text = node.text?.toString()?.lowercase().orEmpty()
                        val fullMatch = cleanQuery.isNotEmpty() && (text.contains(cleanQuery) || cd.contains(cleanQuery))
                        val tokenMatch = queryTokens.isNotEmpty() && queryTokens.any { t -> text.contains(t) || cd.contains(t) }
                        val isSongOrArtist = text.contains("lagu") || text.contains("song") ||
                            text.contains("artis") || text.contains("artist") ||
                            text.contains("hasil teratas") || text.contains("top result")
                        val isClickable = node.isClickable || node.parent?.isClickable == true
                        val className = node.className?.toString().orEmpty()
                        (fullMatch || (tokenMatch && isSongOrArtist)) && isClickable && !node.isEditable && !className.contains("EditText", ignoreCase = true)
                    }

                    if (matchRow != null) {
                        val rect = Rect()
                        matchRow.getBoundsInScreen(rect)
                        val cOk = performClickSelfOrAncestor(matchRow)
                        if (!cOk && rect.centerX() > 0 && rect.centerY() > 0) {
                            dispatchTapSync(rect.centerX().toFloat(), rect.centerY().toFloat(), 80L)
                        }
                        clicked = true
                        GepyLog.i("SpotifyAutoPlay", "Baris hasil cocok query diklik: ${matchRow.text ?: matchRow.contentDescription}")
                        break
                    }

                    // Prioritas 2: Tombol eksplisit Play / Putar di area hasil pencarian
                    val playButton = candidateNodes.firstOrNull { node ->
                        val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
                        val text = node.text?.toString()?.lowercase().orEmpty()
                        val hasPlayKeyword = cd.contains("putar") || cd.contains("play") || cd.contains("mainkan") ||
                            text.contains("putar") || text.contains("play")
                        val notTab = !cd.contains("daftar putar") && !text.contains("daftar putar") &&
                            !cd.contains("playlist") && !text.contains("playlist")
                        hasPlayKeyword && notTab && !node.isEditable
                    }

                    if (playButton != null) {
                        val rect = Rect()
                        playButton.getBoundsInScreen(rect)
                        val cOk = performClickSelfOrAncestor(playButton)
                        if (!cOk && rect.centerX() > 0 && rect.centerY() > 0) {
                            dispatchTapSync(rect.centerX().toFloat(), rect.centerY().toFloat(), 80L)
                        }
                        clicked = true
                        GepyLog.i("SpotifyAutoPlay", "Play button diklik: ${playButton.contentDescription ?: playButton.text}")
                        break
                    }

                    // Prioritas 3: Baris Lagu / Hasil Teratas generik jika belum cocok spesifik
                    val genericRow = candidateNodes.firstOrNull { node ->
                        val text = node.text?.toString()?.lowercase().orEmpty()
                        val isSongOrArtist = text.contains("lagu") || text.contains("song") ||
                            text.contains("artis") || text.contains("artist") ||
                            text.contains("hasil teratas") || text.contains("top result")
                        val isClickable = node.isClickable || node.parent?.isClickable == true
                        val className = node.className?.toString().orEmpty()
                        isSongOrArtist && isClickable && !node.isEditable && !className.contains("EditText", ignoreCase = true)
                    }

                    if (genericRow != null) {
                        val rect = Rect()
                        genericRow.getBoundsInScreen(rect)
                        val cOk = performClickSelfOrAncestor(genericRow)
                        if (!cOk && rect.centerX() > 0 && rect.centerY() > 0) {
                            dispatchTapSync(rect.centerX().toFloat(), rect.centerY().toFloat(), 80L)
                        }
                        clicked = true
                        GepyLog.i("SpotifyAutoPlay", "Baris hasil generik diklik: ${genericRow.text ?: genericRow.contentDescription}")
                        break
                    }

                    // Prioritas 4: Fallback item clickable pertama berteks
                    if (attempt >= 2) {
                        val firstClickable = candidateNodes.firstOrNull { node ->
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            val hasText = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim().isNotEmpty()
                            val isClickable = node.isClickable || node.parent?.isClickable == true
                            val className = node.className?.toString().orEmpty()
                            isClickable && !node.isEditable && !className.contains("EditText", ignoreCase = true) && hasText && rect.top > searchAreaTop
                        }
                        if (firstClickable != null) {
                            val rect = Rect()
                            firstClickable.getBoundsInScreen(rect)
                            val cOk = performClickSelfOrAncestor(firstClickable)
                            if (!cOk && rect.centerX() > 0 && rect.centerY() > 0) {
                                dispatchTapSync(rect.centerX().toFloat(), rect.centerY().toFloat(), 80L)
                            }
                            clicked = true
                            GepyLog.i("SpotifyAutoPlay", "Fallback baris clickable diklik: ${firstClickable.text}")
                            break
                        }
                    }

                    Thread.sleep(250)
                }

                // 4. Deteksi Spotify Free Upsell Modal ("Ingin mengontrol caramu mendengarkan?" / "Dengarkan lagu serupa")
                // Pada akun gratis, memilih lagu akan memicu modal sheet penawaran Premium.
                // Mengklik "Dengarkan lagu serupa" langsung memulai pemutaran radio gratis dari lagu tersebut.
                Thread.sleep(450)
                for (modalAttempt in 0 until 5) {
                    val modalRoot = try { rootInActiveWindow } catch (_: Exception) { null }
                    if (modalRoot != null) {
                        val similarSongNode = findNodeDeep(modalRoot) { node ->
                            val text = node.text?.toString()?.lowercase().orEmpty()
                            val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
                            (text.contains("lagu serupa") || cd.contains("lagu serupa") ||
                                text.contains("layanan gratis") || cd.contains("layanan gratis") ||
                                text.contains("similar songs") || cd.contains("similar songs") ||
                                text.contains("free service") || cd.contains("free service")) &&
                                !node.isEditable
                        }
                        if (similarSongNode != null) {
                            val cOk = performClickSelfOrAncestor(similarSongNode)
                            if (!cOk) {
                                val rect = Rect()
                                similarSongNode.getBoundsInScreen(rect)
                                if (rect.centerX() > 0 && rect.centerY() > 0) {
                                    dispatchTapSync(rect.centerX().toFloat(), rect.centerY().toFloat(), 80L)
                                }
                            }
                            GepyLog.i("SpotifyAutoPlay", "Modal Spotify Free terdeteksi: 'Dengarkan lagu serupa' diklik!")
                            break
                        }
                    }
                    Thread.sleep(250)
                }

                // 5. Jika yang terbuka adalah halaman Artis/Playlist/Album, cari dan klik tombol Play/Shuffle besar
                Thread.sleep(400)
                val profileRoot = try { rootInActiveWindow } catch (_: Exception) { null }
                if (profileRoot != null) {
                    fun findProfilePlay(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                        if (node == null || depth > 20) return null
                        val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
                        val text = node.text?.toString()?.lowercase().orEmpty()
                        val isPlayOrShuffle = (cd.contains("putar") || cd.contains("play") || cd.contains("acak") || cd.contains("shuffle")) &&
                            !cd.contains("daftar putar") && !text.contains("daftar putar")
                        if (isPlayOrShuffle) {
                            return node
                        }
                        for (i in 0 until node.childCount) {
                            val f = findProfilePlay(node.getChild(i), depth + 1)
                            if (f != null) return f
                        }
                        return null
                    }
                    val profilePlayBtn = findProfilePlay(profileRoot, 0)
                    if (profilePlayBtn != null) {
                        val rect = Rect()
                        profilePlayBtn.getBoundsInScreen(rect)
                        val cOk = performClickSelfOrAncestor(profilePlayBtn)
                        if (!cOk && rect.centerX() > 0 && rect.centerY() > 0) {
                            dispatchTapSync(rect.centerX().toFloat(), rect.centerY().toFloat(), 80L)
                        }
                        GepyLog.i("SpotifyAutoPlay", "Tombol Play/Shuffle besar di profil artis/album diklik.")
                    }
                }

            } catch (e: Exception) {
                GepyLog.e("SpotifyAutoPlay", "Error pada auto-play watcher Spotify", e)
            }
        }
    }
}
