package com.teknologigawai.gepy.features.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Pengelola pemutaran PCM 24 kHz untuk Gemini Live Call di atas `AudioTrack` (`USAGE_VOICE_COMMUNICATION`),
 * lengkap dengan kontrol epoch barge-in instan dan eksekutor berurutan anti-`RejectedExecutionException`.
 */
internal class LiveAudioTrackPlayer(
    private val context: Context?
) {
    private val audioPlaybackExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var liveAudioTrack: AudioTrack? = null

    private val liveAudioEpoch = AtomicLong(0L)

    val currentEpoch: Long
        get() = liveAudioEpoch.get()

    /** Estimasi timestamp (ms) saat chunk audio asisten terakhir di speaker selesai dimainkan. */
    @Volatile
    var playbackEstimatedEndEpochMs: Long = 0L
        private set

    /** Penguatan & skala volume suara Live Call (`0.0f` = bisu/mute, `1.0f` = 100% hardware, `1.0f..2.0f` = boost digital). */
    @Volatile
    var liveSpeakerGain: Float = 2.0f

    @Volatile
    private var lastAppliedTrackHardwareVolume: Float = -1f

    fun clearEstimatedEnd() {
        playbackEstimatedEndEpochMs = 0L
    }

    fun isWindowActive(nowMs: Long = System.currentTimeMillis(), graceMs: Long = 80L): Boolean =
        nowMs < (playbackEstimatedEndEpochMs + graceMs)

    /** Mengatur skala volume output Gemini Live (`0.0f..2.0f`) secara instan di tengah panggilan aktif. */
    fun setOutputScale(scale: Float) {
        val clamped = scale.coerceIn(0f, 2f)
        liveSpeakerGain = clamped
        val hwVolume = clamped.coerceIn(0f, 1f)
        if (clamped <= 0.001f) {
            playbackEstimatedEndEpochMs = 0L
        }
        val track = liveAudioTrack
        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
            try {
                track.setVolume(hwVolume)
                lastAppliedTrackHardwareVolume = hwVolume
            } catch (_: Throwable) {
            }
        }
    }

    fun executeTask(task: () -> Unit) {
        if (audioPlaybackExecutor.isShutdown) return
        try {
            audioPlaybackExecutor.execute {
                try {
                    task()
                } catch (_: Throwable) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun prewarm() {
        executeTask { ensureAudioTrack() }
    }

    private fun ensureAudioTrack(): AudioTrack? {
        val current = liveAudioTrack
        if (current != null && current.state == AudioTrack.STATE_INITIALIZED) {
            return current
        }
        if (context == null) return null
        return try {
            val sampleRate = 24_000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Kapasitas buffer diperbesar minimal ~1.5 detik audio untuk mencegah buffer underrun/kresek saat jitter
            val bufferSize = maxOf(minBuf * 3, sampleRate * 3 / 2)
            // USAGE_VOICE_COMMUNICATION memastikan audio terikat dengan pipeline AcousticEchoCanceler (AEC) perangkat keras
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { track ->
                    val initialHwVolume = liveSpeakerGain.coerceIn(0f, 1f)
                    track.setVolume(initialHwVolume)
                    lastAppliedTrackHardwareVolume = initialHwVolume
                    track.play()
                    liveAudioTrack = track
                }
        } catch (_: Throwable) {
            null
        }
    }

    /** Mengirimkan potongan audio PCM 24 kHz ke speaker; mengembalikan `true` jika diterima sebagai audio aktif. */
    fun writePcm24kChunk(chunk: ByteArray): Boolean {
        if (chunk.isEmpty()) return false
        val activeScale = liveSpeakerGain.coerceIn(0f, 2f)
        if (activeScale <= 0.001f) {
            playbackEstimatedEndEpochMs = 0L
            return false
        }
        // 24 kHz 16-bit mono PCM = 48.000 bytes per detik
        val chunkDurationMs = (chunk.size.toLong() * 1000L) / 48_000L
        val now = System.currentTimeMillis()
        playbackEstimatedEndEpochMs = maxOf(playbackEstimatedEndEpochMs, now) + chunkDurationMs

        if (context == null) return true
        val capturedEpoch = liveAudioEpoch.get()
        // Untuk skala <= 1.0f, AudioTrack.setVolume() menanganinya di mixer native OS (0 CPU & 0 alokasi).
        // Untuk skala > 1.0f, gunakan amplifikasi in-place karena chunk hasil Base64.decode() bersifat privat.
        val payload = if (activeScale > 1.0f) {
            GeminiLiveClient.boostPcm16AudioInPlace(chunk, activeScale)
        } else {
            chunk
        }
        val desiredHwVolume = activeScale.coerceIn(0f, 1f)
        executeTask {
            if (capturedEpoch != liveAudioEpoch.get()) return@executeTask
            val track = ensureAudioTrack() ?: return@executeTask
            if (kotlin.math.abs(lastAppliedTrackHardwareVolume - desiredHwVolume) > 0.005f) {
                track.setVolume(desiredHwVolume)
                lastAppliedTrackHardwareVolume = desiredHwVolume
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            if (capturedEpoch != liveAudioEpoch.get()) return@executeTask
            track.write(payload, 0, payload.size)
        }
        return true
    }

    fun stopPlayback() {
        val nextEpoch = liveAudioEpoch.incrementAndGet()
        playbackEstimatedEndEpochMs = 0L
        if (context == null) return
        val track = liveAudioTrack
        try {
            // Panggil pause/flush langsung agar AudioTrack.write(WRITE_BLOCKING) yang tertahan segera terbebas (< 10 ms)
            track?.pause()
            track?.flush()
        } catch (_: Throwable) {
        }
        executeTask {
            if (liveAudioEpoch.get() != nextEpoch) return@executeTask
            liveAudioTrack?.play()
        }
    }

    fun release() {
        liveAudioEpoch.incrementAndGet()
        playbackEstimatedEndEpochMs = 0L
        if (context == null) return
        val trackToRelease = liveAudioTrack
        liveAudioTrack = null
        lastAppliedTrackHardwareVolume = -1f
        try {
            trackToRelease?.pause()
            trackToRelease?.flush()
        } catch (_: Throwable) {
        }
        executeTask {
            trackToRelease?.stop()
            trackToRelease?.release()
        }
    }

    fun shutdown() {
        runCatching { audioPlaybackExecutor.shutdown() }
    }
}
