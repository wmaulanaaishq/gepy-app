package com.teknologigawai.gepy.features.voice

import java.util.concurrent.atomic.AtomicLong

/**
 * Pengelola siklus hidup **AudioFocus** untuk ucapan Gepy (`USAGE_MEDIA / AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`)
 * serta fokus mikrofon saat perintah suara cepat (`SpeechRecognizer`).
 */
internal class VoiceSpeechFocusManager(
    private val audioFocusCoordinator: VoiceAudioFocusCoordinator,
    private val onSpeechFocusLost: () -> Unit,
    private val onSpeechDuckChanged: (ducked: Boolean) -> Unit
) {
    private val speechFocusTagSeq = AtomicLong(0L)
    private val speechFocusLock = Any()
    private val listeningFocusLock = Any()

    @Volatile
    private var speechFocusOwnerTag: Long = -1L

    @Volatile
    private var hasListeningFocus: Boolean = false

    @Volatile
    private var activeSystemTtsUtteranceId: String? = null

    @Volatile
    private var systemTtsFocusTag: Long = -1L

    @Volatile
    var isSpeechDucked: Boolean = false

    fun nextFocusTag(): Long = speechFocusTagSeq.incrementAndGet()

    private fun onSpeechAudioFocusChanged(event: VoiceAudioFocusEvent) {
        when (event) {
            VoiceAudioFocusEvent.LOSS,
            VoiceAudioFocusEvent.LOSS_TRANSIENT -> {
                isSpeechDucked = false
                onSpeechFocusLost()
            }
            VoiceAudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK -> {
                isSpeechDucked = true
                onSpeechDuckChanged(true)
            }
            VoiceAudioFocusEvent.GAIN -> {
                isSpeechDucked = false
                onSpeechDuckChanged(false)
            }
        }
    }

    fun acquireSpeechFocusForTag(tag: Long): Boolean {
        synchronized(speechFocusLock) {
            if (speechFocusOwnerTag != -1L) {
                audioFocusCoordinator.abandonSpeechFocus()
                speechFocusOwnerTag = -1L
            }
            val granted = audioFocusCoordinator.acquireSpeechFocus { event ->
                onSpeechAudioFocusChanged(event)
            }
            if (granted) {
                speechFocusOwnerTag = tag
            }
            return granted
        }
    }

    fun abandonSpeechFocusForTag(tag: Long) {
        synchronized(speechFocusLock) {
            if (speechFocusOwnerTag == tag) {
                speechFocusOwnerTag = -1L
                audioFocusCoordinator.abandonSpeechFocus()
            }
        }
    }

    fun forceAbandonSpeechFocus() {
        synchronized(speechFocusLock) {
            activeSystemTtsUtteranceId = null
            systemTtsFocusTag = -1L
            if (speechFocusOwnerTag != -1L) {
                speechFocusOwnerTag = -1L
                audioFocusCoordinator.abandonSpeechFocus()
            }
        }
    }

    fun registerSystemTtsUtterance(utteranceId: String, focusTag: Long) {
        synchronized(speechFocusLock) {
            activeSystemTtsUtteranceId = utteranceId
            systemTtsFocusTag = focusTag
        }
    }

    fun clearSystemTtsUtteranceIfMatching(utteranceId: String) {
        synchronized(speechFocusLock) {
            if (activeSystemTtsUtteranceId == utteranceId) {
                activeSystemTtsUtteranceId = null
                systemTtsFocusTag = -1L
            }
        }
    }

    fun clearSystemTtsUtterance() {
        synchronized(speechFocusLock) {
            activeSystemTtsUtteranceId = null
            systemTtsFocusTag = -1L
        }
    }

    fun abandonSpeechFocusUnlessHandedToSystemTts(focusTag: Long) {
        val handedToSystemTts = synchronized(speechFocusLock) {
            activeSystemTtsUtteranceId != null && systemTtsFocusTag == focusTag
        }
        if (!handedToSystemTts) {
            abandonSpeechFocusForTag(focusTag)
        }
    }

    fun abandonSystemTtsFocusIfMatching(utteranceId: String?) {
        val tagToAbandon = synchronized(speechFocusLock) {
            val currentUtt = activeSystemTtsUtteranceId
            if (currentUtt == null || utteranceId == null || currentUtt == utteranceId) {
                val tag = systemTtsFocusTag
                activeSystemTtsUtteranceId = null
                systemTtsFocusTag = -1L
                tag
            } else {
                -1L
            }
        }
        if (tagToAbandon != -1L) {
            abandonSpeechFocusForTag(tagToAbandon)
        }
    }

    fun acquireListeningFocusSafe(): Boolean {
        synchronized(listeningFocusLock) {
            if (hasListeningFocus) return true
            val granted = audioFocusCoordinator.acquireListeningFocus { focusEvent ->
                if (focusEvent == VoiceAudioFocusEvent.LOSS) {
                    synchronized(listeningFocusLock) {
                        hasListeningFocus = false
                    }
                }
            }
            if (granted) {
                hasListeningFocus = true
            }
            return granted
        }
    }

    fun abandonListeningFocusSafe() {
        synchronized(listeningFocusLock) {
            if (hasListeningFocus) {
                hasListeningFocus = false
                audioFocusCoordinator.abandonListeningFocus()
            }
        }
    }
}
