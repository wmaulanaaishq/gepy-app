package com.teknologigawai.gepy.features.voice

import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.features.perception.models.PruneMetricsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hasil pra-tangkapan pohon aksesibilitas spekulatif yang masih segar di memori. */
data class CachedSpeculativeScreen(
    val foregroundPackage: String,
    val snapshot: PruneMetricsSnapshot,
    val capturedAtEpochMs: Long
)

/**
 * Modul SOTA **Speculative Listening Pre-Fetcher** (`arXiv:2611.06982v1` — *Decode Before You Hear*):
 * - Mengamati aliran `interimInputTranscript` saat pengguna masih berbicara pada sesi *Live Call*.
 * - Apabila transkrip parsial mengandung kata kunci interaksi layar/aplikasi, modul ini segera
 *   menangkap dan memangkas *Accessibility Tree* di latar belakang sebelum kalimat pengguna selesai.
 * - Ketika server Gemini Live menurunkan `toolCall` (`read_current_screen` atau `execute_android_goal`)
 *   beberapa ratus milidetik kemudian, pohon UI sudah siap di RAM (`0 ms` latensi persepsi!).
 */
class SpeculativePreFetcher(
    private val perceptionPortProvider: () -> PerceptionPort?,
    private val debounceMs: Long = 450L,
    private val defaultMaxAgeMs: Long = 2_500L,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private val SCREEN_INTENT_REGEX = Regex(
            """\b(layar|di\s+layar|baca\s+layar|isi\s+layar|lihat\s+layar|buka|bukakan|kirim|balas|cari|carikan|klik|ketuk|ketik|putar|setel|bagikan|story|cerita)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Mengecek apakah transkrip sementara memuat indikasi aksi UI atau pembacaan layar. */
        fun hasScreenActionIntent(transcript: String): Boolean {
            val clean = transcript.trim()
            if (clean.length < 4) return false
            return SCREEN_INTENT_REGEX.containsMatchIn(clean)
        }
    }

    private val lock = Any()
    private var cachedScreen: CachedSpeculativeScreen? = null
    private var lastTriggerAtMs: Long = -10_000L
    private var activeFetchJob: Job? = null

    @Volatile
    var speculativeHitCount: Int = 0
        private set

    @Volatile
    var speculativePreFetchCount: Int = 0
        private set

    /**
     * Dipanggil setiap kali `interimInputTranscript` diterima selama pengguna berbicara.
     * Menjalankan pre-capture non-blocking apabila kata kunci layar terdeteksi dan melewati jendela *debounce*.
     */
    fun onInterimTranscript(interimText: String) {
        if (!hasScreenActionIntent(interimText)) return
        val now = nowMsProvider()
        synchronized(lock) {
            if (now - lastTriggerAtMs < debounceMs) return
            if (activeFetchJob?.isActive == true) return
            lastTriggerAtMs = now
            activeFetchJob = scope.launch {
                performPreFetchSync()
            }
        }
    }

    /** Versi `suspend` langsung untuk pengujian deterministik maupun pemanasan sinkron. */
    suspend fun performPreFetchSync(): CachedSpeculativeScreen? {
        val port = perceptionPortProvider() ?: return null
        return try {
            val snap = port.capturePruneSnapshot(forceOcrFallback = false)
            val pkg = port.currentForegroundPackage
            val entry = CachedSpeculativeScreen(
                foregroundPackage = pkg,
                snapshot = snap,
                capturedAtEpochMs = nowMsProvider()
            )
            synchronized(lock) {
                cachedScreen = entry
                speculativePreFetchCount++
            }
            entry
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Mengambil dan mengonsumsi *snapshot* spekulatif apabila usianya `<= [maxAgeMs]` dan paket aplikasi
     * latar depan masih sama (mencegah pembacaan layar basi apabila aplikasi baru saja berpindah).
     */
    fun consumeFreshSnapshotIfAvailable(
        currentForegroundPackage: String? = null,
        maxAgeMs: Long = defaultMaxAgeMs
    ): CachedSpeculativeScreen? {
        val now = nowMsProvider()
        synchronized(lock) {
            val candidate = cachedScreen ?: return null
            val age = now - candidate.capturedAtEpochMs
            if (age < 0L || age > maxAgeMs) {
                cachedScreen = null
                return null
            }
            if (!currentForegroundPackage.isNullOrEmpty() &&
                candidate.foregroundPackage.isNotEmpty() &&
                candidate.foregroundPackage != currentForegroundPackage
            ) {
                cachedScreen = null
                return null
            }
            cachedScreen = null
            speculativeHitCount++
            return candidate
        }
    }

    /** Membersihkan cache spekulatif (mis. segera setelah aksi UI mengubah layar). */
    fun invalidate() {
        synchronized(lock) {
            cachedScreen = null
        }
    }
}
