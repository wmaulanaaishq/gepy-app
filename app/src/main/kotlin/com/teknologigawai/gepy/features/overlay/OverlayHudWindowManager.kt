package com.teknologigawai.gepy.features.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.features.voice.OverlayHudState
import com.teknologigawai.gepy.features.voice.OverlayVisualMode
import com.teknologigawai.gepy.features.voice.VoiceAudioEngine
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pemilik siklus hidup mandiri (*Standalone LifecycleOwner*) agar `ComposeView` dapat
 * dirender langsung di atas `WindowManager` (`TYPE_APPLICATION_OVERLAY`) di luar `Activity`.
 */
internal class OverlayViewLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var isRestored = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onAttach() {
        if (!isRestored) {
            savedStateController.performRestore(null)
            isRestored = true
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDetach() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Pengelola jendela mengambang native (`OverlayHudWindowManager`) yang merender
 * [OverlayHudComposable] di dalam `ComposeView` di atas `WindowManager` (`TYPE_APPLICATION_OVERLAY`).
 *
 * Keunggulan arsitektur **Single-Process Zero-Isolate**:
 * - Mengobservasi `voiceAudioEngine.hudStateFlow` langsung di memori (`0 ms` IPC overhead).
 * - Menghemat ~45–60 MB RAM dibanding menjalankan mesin Flutter kedua pada proses terpisah.
 * - Mengalihkan `FLAG_NOT_TOUCHABLE` secara dinamis ketika mode `THINKING` aktif agar
 *   tangkapan ketukan/sapuan `GepyAccessibilityService` tembus 100% (*click-through*) ke aplikasi di bawahnya.
 */
class OverlayHudWindowManager(
    context: Context,
    private val voiceAudioEngine: VoiceAudioEngine
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayViewLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached = false

    private var idleOffsetX = 0
    private var idleOffsetY = 140
    private var hasCustomIdlePosition = false
    private var appliedMode: OverlayVisualMode? = null
    private var appliedClickThrough: Boolean? = null

    init {
        scope.launch {
            voiceAudioEngine.hudStateFlow.collect { state ->
                syncWindowWithState(state)
            }
        }
    }

    private fun syncWindowWithState(state: OverlayHudState) {
        mainHandler.post {
            if (!state.isVisible || !Settings.canDrawOverlays(appContext)) {
                detachOverlayView()
                return@post
            }

            val nextClickThrough = isClickThroughState(state)
            if (!isAttached) {
                attachOverlayView(state)
            } else if (appliedMode != state.mode || appliedClickThrough != nextClickThrough) {
                // HANYA panggil WindowManager.updateViewLayout ketika mode atau kebijakan sentuhan berubah!
                // Perubahan teks transkrip ditangani langsung di memori oleh ComposeView (0 ms IPC, bebas lag).
                updateWindowFlagsAndPosition(state)
            }
        }
    }

    private fun isClickThroughState(state: OverlayHudState): Boolean {
        return state.mode == OverlayVisualMode.THINKING && !state.isLiveCallActive
    }

    private fun defaultRightEdgeIdleOffsets(): Pair<Int, Int> {
        val dm = appContext.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val rightEdgeX = ((dm.widthPixels / 2) - (42f * density).roundToInt()).coerceAtLeast(80)
        val safeTopY = (116f * density).roundToInt().coerceAtLeast(190)
        return rightEdgeX to safeTopY
    }

    private fun buildLayoutParams(state: OverlayHudState): WindowManager.LayoutParams {
        val mode = state.mode
        val clickThrough = isClickThroughState(state)
        val flags = if (clickThrough) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        val dm = appContext.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val (defaultIdleX, defaultIdleY) = defaultRightEdgeIdleOffsets()
        if (!hasCustomIdlePosition) {
            idleOffsetX = defaultIdleX
            idleOffsetY = defaultIdleY
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (mode == OverlayVisualMode.IDLE) {
                x = idleOffsetX
                y = idleOffsetY.coerceAtLeast(24)
            } else {
                // Kartu proses & pil status tampil terpusat bersih di bagian atas layar di bawah kamera & bilah status
                x = 0
                y = (38f * density).roundToInt().coerceAtLeast(42)
            }
        }
    }

    private fun attachOverlayView(initialState: OverlayHudState) {
        if (isAttached || !Settings.canDrawOverlays(appContext)) return
        try {
            val owner = OverlayViewLifecycleOwner().also { it.onAttach() }
            val params = buildLayoutParams(initialState)

            val view = ComposeView(appContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    val liveHudState by voiceAudioEngine.hudStateFlow.collectAsState()
                    OverlayHudComposable(
                        hudState = liveHudState,
                        onMicTap = { voiceAudioEngine.onOverlayMicTap() },
                        onMicLongPress = { voiceAudioEngine.onOverlayMicLongPress() },
                        onCancelListen = { voiceAudioEngine.onOverlayCancelListen() },
                        onDisambiguationSelect = { choice ->
                            voiceAudioEngine.onDisambiguationChosen(choice)
                        },
                        onHitlDecision = { approved ->
                            voiceAudioEngine.onHitlDecision(approved)
                        },
                        onWhisperAction = { payload ->
                            voiceAudioEngine.onOverlayWhisperAction(payload)
                        },
                        onWhisperDismiss = {
                            voiceAudioEngine.onOverlayWhisperDismiss()
                        },
                        onDragDelta = { dx, dy ->
                            handleDragDelta(dx, dy)
                        }
                    )
                }
            }

            windowManager.addView(view, params)
            lifecycleOwner = owner
            composeView = view
            layoutParams = params
            appliedMode = initialState.mode
            appliedClickThrough = isClickThroughState(initialState)
            isAttached = true
            GepyLog.i("OverlayHud", "OverlayHudWindowManager attached (${initialState.mode.wireName})")
        } catch (e: Throwable) {
            GepyLog.e("OverlayHud", "Gagal menampilkan Overlay HUD: ${e.message}", e)
        }
    }

    private fun updateWindowFlagsAndPosition(state: OverlayHudState) {
        val view = composeView ?: return
        if (!isAttached) return

        val updatedParams = buildLayoutParams(state)
        layoutParams = updatedParams
        appliedMode = state.mode
        appliedClickThrough = isClickThroughState(state)
        try {
            windowManager.updateViewLayout(view, updatedParams)
        } catch (e: Throwable) {
            GepyLog.w("OverlayHud", "Gagal memperbarui layout Overlay HUD: ${e.message}")
        }
    }

    private fun handleDragDelta(dx: Float, dy: Float) {
        val view = composeView ?: return
        val params = layoutParams ?: return
        if (!isAttached) return

        mainHandler.post {
            try {
                val dm = appContext.resources.displayMetrics
                val maxAbsX = ((dm.widthPixels / 2) - 36).coerceAtLeast(120)
                val maxY = (dm.heightPixels - 140).coerceAtLeast(240)
                params.x = (params.x + dx.roundToInt()).coerceIn(-maxAbsX, maxAbsX)
                params.y = (params.y + dy.roundToInt()).coerceIn(20, maxY)
                if (appliedMode == OverlayVisualMode.IDLE) {
                    idleOffsetX = params.x
                    idleOffsetY = params.y
                    hasCustomIdlePosition = true
                }
                windowManager.updateViewLayout(view, params)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Memastikan Floating Glass Bubble tetap menempel pada `WindowManager` apabila izin Overlay aktif
     * dan `hudStateFlow.value.isVisible == true` (Self-Heal setelah Recents "Tutup Semua").
     */
    fun ensureAttachedIfVisible() {
        mainHandler.post {
            val state = voiceAudioEngine.hudStateFlow.value
            if (!state.isVisible || !Settings.canDrawOverlays(appContext)) return@post
            val viewDetachedBySystem = composeView != null && composeView?.isAttachedToWindow == false
            if (viewDetachedBySystem) {
                detachOverlayView()
            }
            if (!isAttached) {
                attachOverlayView(state)
            }
        }
    }

    /** Menutup jendela mengambang dan membebaskan `ComposeView` dari `WindowManager`. */
    fun detachOverlayView() {
        if (!isAttached) return
        try {
            composeView?.let { windowManager.removeViewImmediate(it) }
        } catch (_: Throwable) {
        }
        try {
            lifecycleOwner?.onDetach()
        } catch (_: Throwable) {
        }
        composeView = null
        lifecycleOwner = null
        layoutParams = null
        appliedMode = null
        appliedClickThrough = null
        isAttached = false
    }
}
