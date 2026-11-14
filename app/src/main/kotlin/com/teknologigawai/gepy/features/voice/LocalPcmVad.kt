package com.teknologigawai.gepy.features.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Hasil evaluasi satu frame PCM16 oleh [LocalPcmVad]. */
data class VadFrameState(
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val rms: Double,
    val isDeepSilence: Boolean = false
)

/**
 * Detektor aktivitas suara lokal (*On-Device Energy VAD*) berlatensi `< 120 ms`
 * pada aliran audio PCM 16-bit little-endian mono (`16 kHz`).
 *
 * Digunakan untuk tiga fungsi kritis pada **Gepy Native**:
 * 1. **Instant Barge-In**: Memicu pemotongan TTS/aksi segera saat pengguna mulai
 *    berbicara tanpa menunggu RTT server.
 * 2. **Hybrid VAD**: Mengirim sinyal `{"realtimeInput": {"audioStreamEnd": true}}`
 *    tepat ketika keheningan terdeteksi di ujung kalimat.
 * 3. **ReFrame Silence-Aware Reclamation** (`arXiv:2611.01835v1`): Menandai keheningan dua arah
 *    berkelanjutan ([isDeepSilence] >= `1,5 detik`) agar pengiriman paket audio hening dapat dihemat.
 */
class LocalPcmVad(
    val rmsThreshold: Double = 0.012,
    val speechTriggerFrames: Int = 3,
    val silenceEndpointFrames: Int = 50,
    val deepSilenceThresholdFrames: Int = 75,
    val playbackActiveRmsThreshold: Double = 0.18,
    val playbackActiveTriggerFrames: Int = 8
) {
    private var activeRun: Int = 0
    private var silenceRun: Int = 0
    private var idleSilenceFrames: Int = 0

    @Volatile
    private var inSpeech: Boolean = false

    /** Menandakan apakah pengguna saat ini sedang aktif berbicara. */
    val isInSpeech: Boolean
        get() = inSpeech

    /** Menandakan apakah mikrofon berada dalam fase keheningan panjang di luar percakapan aktif. */
    val isDeepSilence: Boolean
        @Synchronized get() = !inSpeech && idleSilenceFrames >= deepSilenceThresholdFrames

    /**
     * Memproses satu frame PCM16 dan mengembalikan transisi VAD (`speechStarted`, `speechEnded`).
     * Saat [playbackActive] bernilai `true` (suara asisten sedang diputar di speaker), ambang RMS
     * dinaikkan ke [playbackActiveRmsThreshold] (0.18) dan [playbackActiveTriggerFrames] (8 frame = 160 ms)
     * agar kebocoran fisik speaker bawah HP (RMS ~0.05-0.12) tidak memicu false speechStarted yang memotong audio asisten.
     */
    @Synchronized
    fun processFrame(pcm16Bytes: ByteArray, playbackActive: Boolean = false): VadFrameState {
        val rms = computeNormalizedRms(pcm16Bytes)
        val effectiveThreshold = if (playbackActive) playbackActiveRmsThreshold else rmsThreshold
        val effectiveTriggerFrames = if (playbackActive) playbackActiveTriggerFrames else speechTriggerFrames

        if (rms >= effectiveThreshold) {
            activeRun++
            silenceRun = 0
            idleSilenceFrames = 0
            if (!inSpeech && activeRun >= effectiveTriggerFrames) {
                inSpeech = true
                return VadFrameState(speechStarted = true, speechEnded = false, rms = rms, isDeepSilence = false)
            }
            return VadFrameState(speechStarted = false, speechEnded = false, rms = rms, isDeepSilence = false)
        }

        activeRun = 0
        if (inSpeech) {
            silenceRun++
            if (silenceRun >= silenceEndpointFrames) {
                inSpeech = false
                silenceRun = 0
                idleSilenceFrames = 0
                return VadFrameState(speechStarted = false, speechEnded = true, rms = rms, isDeepSilence = false)
            }
            return VadFrameState(speechStarted = false, speechEnded = false, rms = rms, isDeepSilence = false)
        }

        idleSilenceFrames++
        val deepSilentNow = idleSilenceFrames >= deepSilenceThresholdFrames
        return VadFrameState(
            speechStarted = false,
            speechEnded = false,
            rms = rms,
            isDeepSilence = deepSilentNow
        )
    }

    /** Mereset status internal VAD. */
    @Synchronized
    fun reset() {
        activeRun = 0
        silenceRun = 0
        idleSilenceFrames = 0
        inSpeech = false
    }

    companion object {
        /** Menghitung energi RMS ternormalisasi (`0.0` hingga `1.0`) dari byte PCM16 little-endian. */
        fun computeNormalizedRms(pcm16Bytes: ByteArray): Double {
            val sampleCount = pcm16Bytes.size / 2
            if (sampleCount == 0) {
                return 0.0
            }
            val buffer = ByteBuffer.wrap(pcm16Bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sumSquares = 0.0
            for (i in 0 until sampleCount) {
                val sample = buffer.getShort(i * 2).toDouble() / 32768.0
                sumSquares += sample * sample
            }
            return sqrt(sumSquares / sampleCount.toDouble())
        }
    }
}
