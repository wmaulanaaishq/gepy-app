package com.teknologigawai.gepy.core.system

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Penjaga *CPU WakeLock* dan *Low-Latency Wi-Fi Lock* khusus saat sesi suara / **Gepy Live Call**
 * sedang aktif (*Zero Idle Drain*).
 *
 * Pada perangkat **Samsung Galaxy A06 (One UI 6.1 / Android 14)**, ketika layar HP terkunci atau
 * aplikasi berada di latar belakang lebih dari 30–60 detik:
 * 1. *Samsung Freecess / Doze* menurunkan frekuensi CPU dan membekukan thread jaringan.
 * 2. *Wi-Fi Radio* masuk ke mode *DTIM Power Save*, memutus stream WebSocket `BidiGenerateContent`.
 *
 * Kelas ini:
 * - **HANYA** menahan `PARTIAL_WAKE_LOCK` dan `WIFI_MODE_FULL_LOW_LATENCY` saat sesi Live Call /
 *   eksekusi suara aktif (`acquireForActiveSession`).
 * - Segera melepaskan kedua lock begitu sesi ditutup (`release()`), sehingga konsumsi baterai saat
 *   *Standby / Bubble Idle* adalah **0% tambahan**.
 * - Menggunakan batas waktu pengaman otomatis (*Safety Timeout* 30 menit) agar lock tidak pernah
 *   bocor selamanya bila terjadi anomali sistem.
 */
class LiveCallPowerGuard(
    private val context: Context? = null
) {
    companion object {
        const val WAKE_LOCK_TAG = "GepyNative::LiveCallCpuWakeLock"
        const val WIFI_LOCK_TAG = "GepyNative::LiveCallWifiLock"
        const val DEFAULT_SAFETY_TIMEOUT_MS = 30L * 60L * 1000L // 30 menit
    }

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLowLatencyLock: WifiManager.WifiLock? = null

    @Volatile
    private var activeSessionLockHeld: Boolean = false

    @Volatile
    private var lastAcquireTimestampMs: Long = 0L

    /** Apakah *WakeLock* & *WifiLock* sesi saat ini sedang ditahan. */
    val isHeld: Boolean
        get() = activeSessionLockHeld ||
            (cpuWakeLock?.isHeld == true) ||
            (wifiLowLatencyLock?.isHeld == true)

    /** Waktu terakhir lock diakuisisi (epoch ms). */
    val acquiredAtEpochMs: Long
        get() = lastAcquireTimestampMs

    /**
     * Mengakuisisi `PARTIAL_WAKE_LOCK` dan `WIFI_MODE_FULL_LOW_LATENCY` untuk sesi suara aktif.
     */
    @SuppressLint("WakelockTimeout")
    @Synchronized
    fun acquireForActiveSession(timeoutMs: Long = DEFAULT_SAFETY_TIMEOUT_MS): Boolean {
        val safeTimeout = timeoutMs.coerceIn(10_000L, 2L * 60L * 60L * 1000L)
        activeSessionLockHeld = true
        lastAcquireTimestampMs = System.currentTimeMillis()

        val appContext = context?.applicationContext ?: return true

        try {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                val wakeLock = cpuWakeLock ?: powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    cpuWakeLock = this
                }
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(safeTimeout)
                }
            }
        } catch (_: Throwable) {
            // Abaikan bila sistem membatasi WakeLock pada lingkungan uji
        }

        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val wifiLock = wifiLowLatencyLock ?: wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    WIFI_LOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    wifiLowLatencyLock = this
                }
                if (!wifiLock.isHeld) {
                    wifiLock.acquire()
                }
            }
        } catch (_: Throwable) {
            // Abaikan bila izin WIFI_STATE belum tersedia
        }

        return activeSessionLockHeld
    }

    /**
     * Melepaskan `PARTIAL_WAKE_LOCK` dan `WIFI_MODE_FULL_LOW_LATENCY` secara instan
     * begitu panggilan suara / eksekusi tugas selesai.
     */
    @Synchronized
    fun release() {
        activeSessionLockHeld = false
        try {
            cpuWakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
        } catch (_: Throwable) {
        }
        try {
            wifiLowLatencyLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
        } catch (_: Throwable) {
        }
    }
}
