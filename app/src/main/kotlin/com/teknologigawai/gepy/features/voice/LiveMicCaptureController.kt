package com.teknologigawai.gepy.features.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.teknologigawai.gepy.core.logging.GepyLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pengelola perekaman mikrofon `AudioRecord` PCM 16 kHz mono beserta inisialisasi
 * opsional `AcousticEchoCanceler` (AEC) perangkat keras untuk Gemini Live Call.
 */
internal class LiveMicCaptureController(
    private val scope: CoroutineScope,
    private val hasRecordPermission: () -> Boolean
) {
    @Volatile
    private var micRecordJob: Job? = null
    private var activeAudioRecord: AudioRecord? = null
    private var activeAec: AcousticEchoCanceler? = null
    private var activeNs: NoiseSuppressor? = null
    private var activeAgc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun start(
        enableHardwareDsp: Boolean,
        onFrame: (ByteArray) -> Unit
    ) {
        stop()
        if (!hasRecordPermission()) return

        micRecordJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1280)

            var recorder: AudioRecord? = null
            var usedSource = MediaRecorder.AudioSource.MIC
            val candidateSources = if (enableHardwareDsp) {
                intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
            } else {
                intArrayOf(MediaRecorder.AudioSource.MIC)
            }

            for (attempt in 0..2) {
                if (!isActive) return@launch
                for (source in candidateSources) {
                    try {
                        val rec = AudioRecord(
                            source,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf * 2
                        )
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            recorder = rec
                            usedSource = source
                            break
                        } else {
                            rec.release()
                        }
                    } catch (e: Throwable) {
                        GepyLog.w("VoiceEngine", "Percobaan AudioRecord (sumber $source, attempt $attempt) gagal: ${e.message}")
                    }
                }
                if (recorder != null) break
                delay(150L)
            }

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                GepyLog.e("VoiceEngine", "AudioRecord gagal diinisialisasi setelah 3 percobaan pada semua sumber audio.")
                return@launch
            }
            activeAudioRecord = recorder
            GepyLog.i("VoiceEngine", "AudioRecord berhasil diinisialisasi dengan audioSource: $usedSource")

            if (enableHardwareDsp && usedSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                val sessionId = recorder.audioSessionId
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        activeAec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }
                } catch (e: Throwable) {
                    GepyLog.w("VoiceEngine", "Hardware AEC opsional gagal diaktifkan: ${e.message}")
                }
            }

            val frameBytes = ByteArray(640) // 20 ms @ 16kHz PCM16 mono
            try {
                recorder.startRecording()
                GepyLog.i("VoiceEngine", "Aliran perekaman mikrofon Live Call aktif.")
                while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = recorder.read(frameBytes, 0, frameBytes.size)
                    if (read > 0) {
                        val copy = if (read == frameBytes.size) {
                            frameBytes.clone()
                        } else {
                            frameBytes.copyOf(read)
                        }
                        onFrame(copy)
                    } else if (read < 0) {
                        GepyLog.w("VoiceEngine", "AudioRecord read error: $read")
                        delay(20L)
                    }
                }
            } catch (e: Throwable) {
                GepyLog.w("VoiceEngine", "Kesalahan loop perekaman mikrofon: ${e.message}")
            } finally {
                releaseRecorder()
                GepyLog.i("VoiceEngine", "Perekam mikrofon dilepaskan.")
            }
        }
    }

    fun stop() {
        micRecordJob?.cancel()
        micRecordJob = null
        releaseRecorder()
    }

    private fun releaseRecorder() {
        try {
            activeAec?.release()
        } catch (_: Throwable) {}
        activeAec = null

        try {
            activeNs?.release()
        } catch (_: Throwable) {}
        activeNs = null

        try {
            activeAgc?.release()
        } catch (_: Throwable) {}
        activeAgc = null

        val rec = activeAudioRecord
        activeAudioRecord = null
        if (rec != null) {
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            } catch (_: Throwable) {
            }
        }
    }
}
