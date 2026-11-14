package com.teknologigawai.gepy.core.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Penjaga & Pembimbing Izin Ketahanan Baterai Latar Belakang (*Background Survival*)
 * khusus **Samsung One UI 6.x (`Freecess` & `MARS`)** dan Android 14+.
 *
 * Pada perangkat Samsung (seperti **Galaxy A06 `SM-A065F`**), terdapat dua lapis manajemen daya:
 * 1. **Standar AOSP Doze Whitelist** (`PowerManager.isIgnoringBatteryOptimizations`).
 *    Saat pengguna menyetujui dialog `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, One UI 6.x
 *    secara otomatis mengubah status baterai aplikasi dari *Dioptimalkan* menjadi
 *    **Tidak dibatasi (*Unrestricted*)** dan mengecualikannya dari daftar *Aplikasi Tidur Pulas (Deep Sleeping Apps)*.
 * 2. **Samsung Device Care (`com.samsung.android.lool`)**:
 *    Menyediakan pintasan langsung ke halaman pengaturan baterai aplikasi jika pengguna ingin
 *    memverifikasi status *Tidak dibatasi* secara manual.
 */
object SamsungBatteryOptimizationGuard {

    /** Memeriksa apakah produsen perangkat adalah Samsung (`One UI`). */
    fun isSamsungDevice(
        manufacturer: String = Build.MANUFACTURER ?: "",
        brand: String = Build.BRAND ?: ""
    ): Boolean {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return m.contains("samsung") || b.contains("samsung")
    }

    /**
     * Memeriksa apakah Gepy sudah berada dalam daftar *Unrestricted / Mengabaikan Optimasi Baterai*.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Memunculkan dialog sistem 1-klik resmi Android (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
     * agar pengguna langsung mengaktifkan mode **Tidak dibatasi (*Unrestricted*)**.
     */
    @SuppressLint("BatteryLife")
    fun requestUnrestrictedBattery(context: Context): Boolean {
        return try {
            val directIntent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(directIntent)
            true
        } catch (_: Throwable) {
            try {
                val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(listIntent)
                true
            } catch (_: Throwable) {
                openAppDetailsSettings(context)
            }
        }
    }

    /**
     * Membuka halaman **Info Aplikasi -> Baterai** atau **Samsung Device Care (`com.samsung.android.lool`)**
     * agar pengguna Samsung One UI dapat memastikan opsi **"Tidak dibatasi" (*Unrestricted*)** aktif.
     */
    fun openSamsungDeviceCareBatterySettings(context: Context): Boolean {
        val samsungCandidates = listOf(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            ),
            ComponentName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
        )

        for (component in samsungCandidates) {
            try {
                val intent = Intent().apply {
                    setComponent(component)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {
                // Lanjut ke kandidat berikutnya atau Info Aplikasi
            }
        }

        return openAppDetailsSettings(context)
    }

    /** Membuka halaman detail aplikasi (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`). */
    fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Panduan singkat ketahanan baterai sesuai merek perangkat. */
    fun getBatterySurvivalGuideText(isSamsung: Boolean = isSamsungDevice()): String {
        return if (isSamsung) {
            "Perangkat Samsung One UI terdeteksi: Izinkan mode 'Tidak dibatasi (Unrestricted)' agar Samsung Freecess tidak memutus Floating Bubble & Gepy Live Call saat layar terkunci."
        } else {
            "Izinkan Gepy mengabaikan optimasi baterai agar Floating Bubble & Gepy Live Call tetap aktif di latar belakang tanpa terputus."
        }
    }
}
