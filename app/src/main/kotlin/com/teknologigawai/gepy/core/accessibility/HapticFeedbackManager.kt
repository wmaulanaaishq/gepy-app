package com.teknologigawai.gepy.core.accessibility

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.teknologigawai.gepy.core.logging.GepyLog

/** Jenis umpan balik sentuhan dan suara (*Earcon & Haptic*) untuk navigasi nir-layar. */
enum class GepyFeedbackPattern {
    /** Memulai sesi mendengarkan suara pengguna (Kapsul aktif). */
    LISTENING_STARTED,

    /** Transisi langkah aksi sukses dieksekusi (*Closed-loop verified*). */
    STEP_SUCCESS,

    /** Meminta konfirmasi persetujuan tindakan sensitif (*Human-in-the-Loop*). */
    AWAITING_HITL,

    /** Seluruh rangkaian tugas telah selesai secara tuntas (*Task Done*). */
    TASK_COMPLETED,

    /** Terjadi gangguan, kesalahan fatal, atau interupsi aksi. */
    ERROR_OR_INTERRUPTED
}

/**
 * Pengelola umpan balik getaran (`VibrationEffect`) dan bunyi nada sistem (*Haptic & Earcon Engine*)
 * untuk memberikan konfirmasi status operasional agen Gepy secara instan dan nyaman
 * saat pengguna mengoperasikan ponsel secara hands-free (ala Siri iPhone).
 */
class HapticFeedbackManager(
    private val context: Context? = null
) {
    @Volatile
    private var sharedToneGenerator: ToneGenerator? = null

    @Synchronized
    private fun getOrCreateToneGenerator(): ToneGenerator? {
        sharedToneGenerator?.let { return it }
        return try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also {
                sharedToneGenerator = it
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Menghasilkan pola getaran dan suara sistem sesuai [pattern]. */
    fun triggerFeedback(pattern: GepyFeedbackPattern) {
        val ctx = context ?: run {
            GepyLog.d("Feedback", "Simulated feedback: ${pattern.name}")
            return
        }
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                val effect = when (pattern) {
                    GepyFeedbackPattern.LISTENING_STARTED ->
                        VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE)
                    GepyFeedbackPattern.STEP_SUCCESS ->
                        VibrationEffect.createOneShot(20L, 90)
                    GepyFeedbackPattern.AWAITING_HITL ->
                        VibrationEffect.createWaveform(longArrayOf(0L, 70L, 60L, 90L), -1)
                    GepyFeedbackPattern.TASK_COMPLETED ->
                        VibrationEffect.createWaveform(longArrayOf(0L, 35L, 50L, 55L), -1)
                    GepyFeedbackPattern.ERROR_OR_INTERRUPTED ->
                        VibrationEffect.createWaveform(longArrayOf(0L, 110L, 70L, 110L), -1)
                }
                vibrator.vibrate(effect)
            }

            // Earcon Tonal Chime ala Siri: Gunakan instance ToneGenerator bersama agar tidak membocorkan AudioFlinger track
            try {
                val toneGen = getOrCreateToneGenerator()
                when (pattern) {
                    GepyFeedbackPattern.LISTENING_STARTED ->
                        toneGen?.startTone(ToneGenerator.TONE_PROP_PROMPT, 130)
                    GepyFeedbackPattern.TASK_COMPLETED ->
                        toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
                    GepyFeedbackPattern.ERROR_OR_INTERRUPTED ->
                        toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 160)
                    else -> {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.playSoundEffect(AudioManager.FX_KEY_CLICK)
                    }
                }
            } catch (_: Throwable) {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.playSoundEffect(AudioManager.FX_KEY_CLICK)
            }

            GepyLog.d("Feedback", "Triggered ${pattern.name}")
        } catch (e: Throwable) {
            GepyLog.w("Feedback", "Gagal memicu haptic feedback: ${e.message}")
        }
    }

    fun vibrateActionTick() = triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)

    fun vibrateHitlWarning() = triggerFeedback(GepyFeedbackPattern.AWAITING_HITL)

    fun vibrateSuccess() = triggerFeedback(GepyFeedbackPattern.TASK_COMPLETED)

    fun vibrateError() = triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
}
