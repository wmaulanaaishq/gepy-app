package com.teknologigawai.gepy.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.teknologigawai.gepy.R
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.system.LiveCallPowerGuard
import com.teknologigawai.gepy.ui.GepyRuntimeContainer
import com.teknologigawai.gepy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Layanan latar depan ringan (*On-Demand Foreground Service*) `GepyVoiceForegroundService`.
 *
 * Dirancang khusus untuk mencegah daemon penghemat memori agresif Samsung One UI 6.1 (`Freecess`)
 * membekukan aliran mikrofon `AudioRecord` atau memutus WebSocket **Gemini 3.8 Live** ketika
 * pengguna berpindah ke aplikasi berat (WhatsApp, Chrome, Maps) pada **Samsung Galaxy A06 (RAM 4 GB)**.
 *
 * Layanan ini **hanya berjalan saat sesi suara atau otomatisasi aktif** dan langsung memanggil
 * `stopSelf()` begitu sesi kembali ke `IDLE` sehingga membebankan `0 MB` RAM saat siaga.
 */
class GepyVoiceForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "gepy_active_session_channel"
        const val NOTIFICATION_ID = 3001
        const val ACTION_START_OR_UPDATE = "com.teknologigawai.gepy.action.START_OR_UPDATE_FGS"
        const val ACTION_STOP_GEPY = "com.teknologigawai.gepy.action.STOP_GEPY_FGS"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"

        fun startOrUpdate(
            context: Context?,
            title: String = "Gepy Aktif",
            subtitle: String = "Menjalankan asisten suara di latar belakang"
        ) {
            val ctx = context?.applicationContext ?: return
            try {
                val intent = Intent(ctx, GepyVoiceForegroundService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_SUBTITLE, subtitle)
                }
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Throwable) {
                GepyLog.w("ForegroundService", "Gagal memulai GepyVoiceForegroundService: ${e.message}")
            }
        }

        fun stopIfRunning(context: Context?) {
            val ctx = context?.applicationContext ?: return
            try {
                val intent = Intent(ctx, GepyVoiceForegroundService::class.java)
                ctx.stopService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var powerGuard: LiveCallPowerGuard? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        powerGuard = LiveCallPowerGuard(applicationContext)
        ensureNotificationChannel()
        // WAJIB: Panggil promoteToForeground() secepatnya di onCreate()
        // untuk memenuhi kontrak Context.startForegroundService() sebelum batas waktu Android terlampaui!
        promoteToForeground(
            title = "Gepy Aktif",
            subtitle = "Menyiapkan sesi latar depan..."
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_GEPY) {
            powerGuard?.release()
            val orchestrator = GepyRuntimeContainer.peekOrchestrator()
            if (orchestrator != null) {
                orchestrator.cancelActiveGoal("Perintah dihentikan melalui tombol notifikasi.")
                if (orchestrator.state.isLiveCallActive) {
                    serviceScope.launch {
                        orchestrator.toggleGeminiLiveCall()
                    }
                }
            }
            // Pastikan telah dipromosikan sebelum memanggil stopForeground & stopSelf
            promoteToForeground("Gepy Dihentikan", "Menutup sesi latar depan...")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Tahan CPU Partial WakeLock & Low-Latency Wi-Fi Lock hanya selama sesi suara/Live Call aktif
        powerGuard?.acquireForActiveSession()

        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: "Gepy Aktif"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }
            ?: "Lagi memproses perintah suaramu"

        promoteToForeground(title, subtitle)

        return START_STICKY
    }

    /**
     * Mempromosikan service ke Foreground dengan strategi fallback bertingkat (Tiered Fallback).
     *
     * Mencegah crash fatal [android.app.RemoteServiceException.ForegroundServiceDidNotStartInTimeException]
     * pada Android 14/15/16 (API 34+) ketika tipe `MICROPHONE` ditolak oleh sistem karena
     * aplikasi dipanggil dari latar belakang (*background execution restriction*).
     */
    private fun promoteToForeground(title: String, subtitle: String) {
        val notification = buildNotification(title, subtitle)
        var promoted = false

        // Tingkat 1: Coba FOREGROUND_SERVICE_TYPE_MICROPHONE jika izin RECORD_AUDIO diberikan (Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMicPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMicPerm) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                    promoted = true
                } catch (e: Throwable) {
                    GepyLog.w(
                        "ForegroundService",
                        "Gagal startForeground MICROPHONE (pembatasan background Android 14+): ${e.message}"
                    )
                }
            }
        }

        // Tingkat 2 (Fallback Aman): Gunakan FOREGROUND_SERVICE_TYPE_SPECIAL_USE (Android 14+ / API 34+)
        // Khusus untuk agen aksesibilitas suara yang terdaftar di AndroidManifest.xml
        if (!promoted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                promoted = true
            } catch (e: Throwable) {
                GepyLog.w(
                    "ForegroundService",
                    "Gagal startForeground SPECIAL_USE: ${e.message}"
                )
            }
        }

        // Tingkat 3 (Fallback Pamungkas): startForeground standar tanpa parameter tipe
        // Menjamin sistem Android menganggap kontrak startForegroundService() terpenuhi (mencegah ForegroundServiceDidNotStartInTimeException)
        if (!promoted) {
            try {
                startForeground(NOTIFICATION_ID, notification)
                promoted = true
            } catch (e: Throwable) {
                GepyLog.e(
                    "ForegroundService",
                    "Kritis: Seluruh varian startForeground gagal: ${e.message}",
                    e
                )
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Jangan matikan layanan suara atau Floating Glass Bubble saat pengguna menekan "Tutup Semua" di Recents
        super.onTaskRemoved(rootIntent)
        GepyAccessibilityService.onEnsureRuntimeAndOverlayAlive?.invoke()
    }

    override fun onDestroy() {
        powerGuard?.release()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sesi Suara & Otomasi Gepy",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Menjaga mikrofon Gepy Live dan eksekusi langkah UI tetap aktif di latar belakang"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GepyVoiceForegroundService::class.java).apply {
            action = ACTION_STOP_GEPY
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Tutup Telepon",
                stopPending
            )
            .build()
    }
}
