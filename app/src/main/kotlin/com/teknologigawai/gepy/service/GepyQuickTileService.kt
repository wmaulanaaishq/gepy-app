package com.teknologigawai.gepy.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.ui.GepyRuntimeContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Layanan Ubin Pengaturan Cepat (*Quick Settings Tile*) `GepyQuickTileService`.
 *
 * Memungkinkan pengguna memicu pendengaran perintah suara Gepy atau memunculkan kapsul
 * *Overlay HUD* langsung dari panel tarik-turun pusat kontrol Android (*One UI Quick Panel*)
 * hanya dengan 1 ketukan cepat secara hands-free tanpa perlu keluar dari aplikasi yang sedang dibuka.
 */
class GepyQuickTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTileVisuals()
    }

    override fun onClick() {
        super.onClick()
        GepyLog.i("QuickTile", "GepyQuickTileService diketuk oleh pengguna")
        tileScope.launch {
            try {
                val orchestrator = GepyRuntimeContainer.getOrCreateOrchestrator(applicationContext)
                orchestrator.triggerVoiceCommandListen()
                refreshTileVisuals()
            } catch (e: Throwable) {
                GepyLog.w("QuickTile", "Gagal memicu perintah suara dari Quick Tile: ${e.message}")
            }
        }
    }

    private fun refreshTileVisuals() {
        val tile = qsTile ?: return
        val orchestrator = GepyRuntimeContainer.peekOrchestrator()
        val isActive = orchestrator?.state?.let {
            it.overlayActive || it.isLiveCallActive || it.isBusy
        } ?: false

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Gepy Suara"
        tile.subtitle = if (orchestrator?.state?.isLiveCallActive == true) {
            "Gepy Live Aktif"
        } else {
            "Ketuk untuk bicara"
        }
        tile.updateTile()
    }
}
