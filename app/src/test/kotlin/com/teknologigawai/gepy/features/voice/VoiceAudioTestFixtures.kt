package com.teknologigawai.gepy.features.voice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue

internal suspend fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!predicate() && System.currentTimeMillis() < deadline) {
        delay(25L)
    }
    assertTrue("Timeout menunggu pemutaran audio selesai", predicate())
}

internal class RecordingVoiceAudioFocusCoordinator : VoiceAudioFocusCoordinator {
        val speechAcquireCount = AtomicInteger(0)
        val speechAbandonCount = AtomicInteger(0)
        val callAcquireCount = AtomicInteger(0)
        val callAbandonCount = AtomicInteger(0)
        val listeningAcquireCount = AtomicInteger(0)
        val listeningAbandonCount = AtomicInteger(0)
        val timeline = CopyOnWriteArrayList<String>()

        @Volatile
        var allowSpeechFocus: Boolean = true

        @Volatile
        var allowListeningFocus: Boolean = true

        @Volatile
        var lastSpeechListener: ((VoiceAudioFocusEvent) -> Unit)? = null

        @Volatile
        var lastCallListener: ((VoiceAudioFocusEvent) -> Unit)? = null

        @Volatile
        var lastListeningListener: ((VoiceAudioFocusEvent) -> Unit)? = null

        override fun acquireSpeechFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
            lastSpeechListener = onFocusChange
            speechAcquireCount.incrementAndGet()
            timeline.add(if (allowSpeechFocus) "acquire_speech" else "deny_speech")
            return allowSpeechFocus
        }

        override fun abandonSpeechFocus() {
            speechAbandonCount.incrementAndGet()
            timeline.add("abandon_speech")
        }

        override fun acquireCallFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
            lastCallListener = onFocusChange
            callAcquireCount.incrementAndGet()
            timeline.add("acquire_call")
            return true
        }

        override fun abandonCallFocus() {
            callAbandonCount.incrementAndGet()
            timeline.add("abandon_call")
        }

        override fun acquireListeningFocus(onFocusChange: (VoiceAudioFocusEvent) -> Unit): Boolean {
            lastListeningListener = onFocusChange
            listeningAcquireCount.incrementAndGet()
            timeline.add(if (allowListeningFocus) "acquire_listening" else "deny_listening")
            return allowListeningFocus
        }

        override fun abandonListeningFocus() {
            listeningAbandonCount.incrementAndGet()
            timeline.add("abandon_listening")
        }
    }
