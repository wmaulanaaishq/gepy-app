package com.teknologigawai.gepy.features.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.teknologigawai.gepy.core.logging.GepyLog

/** Sinyal perubahan fokus audio Android yang relevan bagi pemutar suara & Live Call Gepy. */
enum class VoiceAudioFocusEvent {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK
}

/**
 * Kontrak pengelolaan siklus hidup **AudioFocus** yang abstrak dan deterministik:
 * - Memisahkan logika akuisisi/pelepasan fokus media ucapan (`USAGE_MEDIA` + `MAY_DUCK`)
 *   dan fokus panggilan dua arah (`USAGE_VOICE_COMMUNICATION` + `AUDIOFOCUS_GAIN_TRANSIENT`).
 * - Memungkinkan pengujian unit JVM penuh tanpa emulator/Robolectric.
 */
interface VoiceAudioFocusCoordinator {
    fun acquireSpeechFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean
    fun abandonSpeechFocus()
    fun acquireListeningFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit = {}): Boolean
    fun abandonListeningFocus()
    fun acquireCallFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean
    fun abandonCallFocus()
}

/** Implementasi tanpa-aksi untuk unit test JVM ketika `Context` Android tidak tersedia. */
object NoOpVoiceAudioFocusCoordinator : VoiceAudioFocusCoordinator {
    override fun acquireSpeechFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean = true
    override fun abandonSpeechFocus() {}
    override fun acquireListeningFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean = true
    override fun abandonListeningFocus() {}
    override fun acquireCallFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean = true
    override fun abandonCallFocus() {}
}

/**
 * Pengelola `AudioFocusRequest` native Android (API 26+, kompatibel penuh `minSdk = 29`)
 * yang patuh terhadap karakteristik Samsung One UI 6.1 (`USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`).
 */
class AndroidVoiceAudioFocusCoordinator(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : VoiceAudioFocusCoordinator {

    companion object {
        private const val TAG = "VoiceAudioFocus"
    }

    private val lock = Any()
    private val audioManager: AudioManager? = try {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Throwable) {
        null
    }

    private var activeSpeechFocusRequest: AudioFocusRequest? = null
    private var activeListeningFocusRequest: AudioFocusRequest? = null
    private var activeCallFocusRequest: AudioFocusRequest? = null

    override fun acquireSpeechFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
        val am = audioManager ?: return true
        return synchronized(lock) {
            abandonListeningFocusLocked(am)
            abandonSpeechFocusLocked(am)
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener({ focusChange ->
                        mapFocusChange(focusChange)?.let(onFocusChange)
                    }, mainHandler)
                    .build()
                val result = am.requestAudioFocus(req)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    activeSpeechFocusRequest = req
                    true
                } else {
                    GepyLog.w(TAG, "Permintaan Speech AudioFocus ditolak oleh sistem (result=$result).")
                    false
                }
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Gagal meminta Speech AudioFocus: ${t.message}")
                true
            }
        }
    }

    override fun abandonSpeechFocus() {
        val am = audioManager ?: return
        synchronized(lock) {
            abandonSpeechFocusLocked(am)
        }
    }

    override fun acquireListeningFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
        val am = audioManager ?: return true
        return synchronized(lock) {
            abandonSpeechFocusLocked(am)
            abandonListeningFocusLocked(am)
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener({ focusChange ->
                        mapFocusChange(focusChange)?.let(onFocusChange)
                    }, mainHandler)
                    .build()
                val result = am.requestAudioFocus(req)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    activeListeningFocusRequest = req
                    true
                } else {
                    GepyLog.w(TAG, "Permintaan Listening AudioFocus ditolak oleh sistem (result=$result).")
                    false
                }
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Gagal meminta Listening AudioFocus: ${t.message}")
                true
            }
        }
    }

    override fun abandonListeningFocus() {
        val am = audioManager ?: return
        synchronized(lock) {
            abandonListeningFocusLocked(am)
        }
    }

    override fun acquireCallFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
        val am = audioManager ?: return true
        return synchronized(lock) {
            abandonListeningFocusLocked(am)
            abandonSpeechFocusLocked(am)
            abandonCallFocusLocked(am)
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener({ focusChange ->
                        mapFocusChange(focusChange)?.let(onFocusChange)
                    }, mainHandler)
                    .build()
                val result = am.requestAudioFocus(req)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    activeCallFocusRequest = req
                    true
                } else {
                    GepyLog.w(TAG, "Permintaan Call AudioFocus ditolak oleh sistem (result=$result).")
                    false
                }
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Gagal meminta Call AudioFocus: ${t.message}")
                false
            }
        }
    }

    override fun abandonCallFocus() {
        val am = audioManager ?: return
        synchronized(lock) {
            abandonCallFocusLocked(am)
        }
    }

    private fun abandonSpeechFocusLocked(am: AudioManager) {
        val existing = activeSpeechFocusRequest ?: return
        activeSpeechFocusRequest = null
        try {
            am.abandonAudioFocusRequest(existing)
        } catch (t: Throwable) {
            GepyLog.w(TAG, "Gagal melepas Speech AudioFocus: ${t.message}")
        }
    }

    private fun abandonListeningFocusLocked(am: AudioManager) {
        val existing = activeListeningFocusRequest ?: return
        activeListeningFocusRequest = null
        try {
            am.abandonAudioFocusRequest(existing)
        } catch (t: Throwable) {
            GepyLog.w(TAG, "Gagal melepas Listening AudioFocus: ${t.message}")
        }
    }

    private fun abandonCallFocusLocked(am: AudioManager) {
        val existing = activeCallFocusRequest ?: return
        activeCallFocusRequest = null
        try {
            am.abandonAudioFocusRequest(existing)
        } catch (t: Throwable) {
            GepyLog.w(TAG, "Gagal melepas Call AudioFocus: ${t.message}")
        }
    }

    private fun mapFocusChange(focusChange: Int): VoiceAudioFocusEvent? = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> VoiceAudioFocusEvent.GAIN
        AudioManager.AUDIOFOCUS_LOSS -> VoiceAudioFocusEvent.LOSS
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> VoiceAudioFocusEvent.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> VoiceAudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK
        else -> null
    }
}
