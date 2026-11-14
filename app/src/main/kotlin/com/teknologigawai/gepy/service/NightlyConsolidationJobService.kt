package com.teknologigawai.gepy.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.ui.GepyRuntimeContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Layanan Konsolidasi Memori Tidur Malam (**Cold-Path Nightly Sleep Consolidation**)
 * berbasis API resmi Android OS `JobScheduler` (`android.app.job.JobService`).
 *
 * Sesuai praktik terbaik industri (*Apple Intelligence / Android System Intelligence*):
 * - Hanya dieksekusi oleh OS Android ketika:
 *   1. Perangkat sedang diisi daya (`setRequiresCharging(true)`)
 *   2. Perangkat dalam kondisi menganggur / pengguna tidur (`setRequiresDeviceIdle(true)`)
 *   3. Tersambung ke jaringan Wi-Fi tanpa kuota seluler (`NETWORK_TYPE_UNMETERED`)
 * - Beban baterai siang hari: **0%**.
 * - Beban pustaka tambahan: **0 KB** (100% Native Android SDK).
 */
class NightlyConsolidationJobService : JobService() {

    companion object {
        private const val TAG = "NightlyConsolidationJob"
        const val JOB_ID = 5301
        private const val PERIODIC_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 Jam

        /**
         * Mendaftarkan jadwal konsolidasi memori periodik 24 jam ke `JobScheduler` sistem Android
         * jika belum terdaftar.
         */
        fun scheduleNightlyJob(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                    ?: return

                val alreadyScheduled = scheduler.allPendingJobs.any { it.id == JOB_ID }
                if (alreadyScheduled) {
                    return
                }

                val component = ComponentName(context, NightlyConsolidationJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID, component)
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(PERIODIC_INTERVAL_MS)
                    .build()

                val result = scheduler.schedule(jobInfo)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    GepyLog.i(TAG, "Jadwal konsolidasi memori tidur (JobId=$JOB_ID) berhasil didaftarkan (Charging + Idle + Wi-Fi).")
                }
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Gagal mendaftarkan NightlyConsolidationJobService: ${t.message}")
            }
        }
    }

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        GepyLog.i(TAG, "Memulai eksekusi NightlyConsolidationJobService (pemangkasan Ebbinghaus & refleksi tidur)...")
        jobScope.launch {
            try {
                val appCtx = applicationContext
                val memoryManager = GepyRuntimeContainer.getOrCreateUserMemoryManager(appCtx)
                memoryManager.initializeTemporalStore()
                val report = memoryManager.runNightlyCognitiveConsolidation(allowCloudLlm = true)
                GepyLog.i(TAG, "Nightly consolidation selesai: ${report.summaryMessage}")
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Kesalahan saat menjalankan konsolidasi memori malam: ${t.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        jobScope.cancel()
        return true // Jadwalkan ulang jika terputus karena charger dicabut di tengah jalan
    }

    override fun onDestroy() {
        jobScope.cancel()
        super.onDestroy()
    }
}
