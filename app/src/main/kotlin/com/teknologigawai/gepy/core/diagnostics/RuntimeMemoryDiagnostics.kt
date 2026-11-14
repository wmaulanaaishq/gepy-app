package com.teknologigawai.gepy.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.memory.VectorMemoryStats
import com.teknologigawai.gepy.core.ports.EdgeWorldModelStats
import com.teknologigawai.gepy.core.ports.MemoryPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Klasifikasi kesehatan konsumsi memori proses Android (`Total PSS`) pada target perangkat RAM 4 GB (Galaxy A06).
 */
enum class MemoryHealthLevel(val label: String) {
    OPTIMAL("Optimal"),
    NORMAL("Normal"),
    ELEVATED("Padat"),
    HIGH("Tinggi")
}

/**
 * Snapshot diagnostik konsumsi memori nyata aplikasi Gepy (*Proportional Set Size / PSS*)
 * beserta kapasitas Memori Vektor jangka panjang lokal.
 */
data class RuntimeMemorySnapshot(
    val capturedAtMs: Long,
    val totalPssMb: Double,
    val javaHeapMb: Double,
    val nativeHeapMb: Double,
    val graphicsMb: Double,
    val codeAndStackMb: Double,
    val privateOtherMb: Double,
    val maxAppHeapMb: Double,
    val systemAvailRamMb: Double,
    val systemTotalRamMb: Double,
    val isLowRamDeviceWarning: Boolean,
    val healthLevel: MemoryHealthLevel,
    val vectorStats: VectorMemoryStats? = null,
    val sewmStats: EdgeWorldModelStats? = null
) {
    val ceilingUtilizationFraction: Float
        get() = (totalPssMb / RuntimeMemoryDiagnostics.SAFE_LMK_PSS_CEILING_MB)
            .toFloat()
            .coerceIn(0f, 1f)
}

/**
 * Pengukur PSS (*Proportional Set Size*) internal real-time tanpa membebani UI saat tab lain dibuka.
 *
 * Menggunakan `Debug.getMemoryInfo(Debug.MemoryInfo)` yang akurat di Android 14/15 tanpa dibatasi
 * *rate-limit* `ActivityManager.getProcessMemoryInfo`.
 */
object RuntimeMemoryDiagnostics {

    /** Ambang aman sebelum proses UI + Accessibility berisiko masuk antrean *Low Memory Killer* di RAM 4 GB. */
    const val SAFE_LMK_PSS_CEILING_MB: Double = 240.0
    const val NORMAL_PSS_CEILING_MB: Double = 180.0
    const val OPTIMAL_PSS_CEILING_MB: Double = 120.0

    fun kbToMb(kilobytes: Int): Double = kilobytes.coerceAtLeast(0) / 1024.0

    fun bytesToMb(bytes: Long): Double = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)

    fun evaluateMemoryHealth(
        totalPssMb: Double,
        isLowMemory: Boolean = false
    ): MemoryHealthLevel {
        return when {
            isLowMemory || totalPssMb > SAFE_LMK_PSS_CEILING_MB -> MemoryHealthLevel.HIGH
            totalPssMb > NORMAL_PSS_CEILING_MB -> MemoryHealthLevel.ELEVATED
            totalPssMb > OPTIMAL_PSS_CEILING_MB -> MemoryHealthLevel.NORMAL
            else -> MemoryHealthLevel.OPTIMAL
        }
    }

    internal fun parseStatKb(memInfo: Debug.MemoryInfo, statKey: String, fallbackKb: Int = 0): Int {
        val raw = runCatching { memInfo.getMemoryStat(statKey) }.getOrNull()
        return raw?.toIntOrNull()?.coerceAtLeast(0) ?: fallbackKb.coerceAtLeast(0)
    }

    /**
     * Mengambil snapshot PSS nyata di thread [Dispatchers.IO].
     */
    suspend fun capture(
        context: Context,
        userMemoryManager: UserMemoryManager? = null,
        memoryPort: MemoryPort? = null
    ): RuntimeMemorySnapshot = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext ?: context
        val debugMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemInfo)

        val totalPssKb = debugMemInfo.totalPss.coerceAtLeast(0)
        val javaHeapKb = parseStatKb(debugMemInfo, "summary.java-heap", debugMemInfo.dalvikPss)
        val nativeHeapKb = parseStatKb(debugMemInfo, "summary.native-heap", debugMemInfo.nativePss)
        val graphicsKb = parseStatKb(debugMemInfo, "summary.graphics", 0)
        val codeKb = parseStatKb(debugMemInfo, "summary.code", 0)
        val stackKb = parseStatKb(debugMemInfo, "summary.stack", 0)
        val privateOtherKb = parseStatKb(debugMemInfo, "summary.private-other", debugMemInfo.otherPss)

        val sysMemInfo = ActivityManager.MemoryInfo()
        val activityManager = runCatching {
            appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        if (activityManager != null) {
            runCatching { activityManager.getMemoryInfo(sysMemInfo) }
        }

        val totalPssMb = kbToMb(totalPssKb)
        val vectorStats = userMemoryManager?.let { umm ->
            runCatching { umm.getVectorMemoryStats() }.getOrElse { umm.peekVectorRamStats() }
        }
        val sewmStats = memoryPort?.let { mp ->
            runCatching { mp.getEdgeWorldModelStats() }.getOrNull()
        }

        RuntimeMemorySnapshot(
            capturedAtMs = System.currentTimeMillis(),
            totalPssMb = totalPssMb,
            javaHeapMb = kbToMb(javaHeapKb),
            nativeHeapMb = kbToMb(nativeHeapKb),
            graphicsMb = kbToMb(graphicsKb),
            codeAndStackMb = kbToMb(codeKb + stackKb),
            privateOtherMb = kbToMb(privateOtherKb),
            maxAppHeapMb = bytesToMb(Runtime.getRuntime().maxMemory()),
            systemAvailRamMb = bytesToMb(sysMemInfo.availMem),
            systemTotalRamMb = bytesToMb(sysMemInfo.totalMem),
            isLowRamDeviceWarning = sysMemInfo.lowMemory,
            healthLevel = evaluateMemoryHealth(totalPssMb, sysMemInfo.lowMemory),
            vectorStats = vectorStats,
            sewmStats = sewmStats
        )
    }

    /**
     * Memicu petunjuk *Garbage Collector* ringan secara terkendali saat pengguna menekan tombol
     * pembersihan memori pada layar diagnostik.
     */
    fun requestLightGcTrim() {
        runCatching {
            System.gc()
            Runtime.getRuntime().gc()
        }
    }
}
