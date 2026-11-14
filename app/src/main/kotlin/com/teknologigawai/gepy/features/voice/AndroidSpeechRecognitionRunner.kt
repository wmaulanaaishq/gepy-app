package com.teknologigawai.gepy.features.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.speech.IndonesianSpeechNormalizer

/**
 * Pengelola pengenalan suara on-device Android `SpeechRecognizer` (`id-ID`)
 * termasuk retry transien Samsung One UI dan normalisasi teks lisan Bahasa Indonesia.
 */
internal class AndroidSpeechRecognitionRunner(
    private val context: Context?,
    private val mainHandler: Handler,
    private val shortVoiceReplyStarter: ((onSpoken: (String) -> Unit) -> Unit)?,
    private val acquireListeningFocus: () -> Boolean,
    private val abandonListeningFocus: () -> Unit,
    private val isModalWaiting: () -> Boolean,
    private val onMicUnavailable: () -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onIdleFallback: () -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var activeSttSessionId: Long = 0L

    fun hasRecordPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun listenForShortVoiceReply(onSpoken: (String) -> Unit) {
        val customStarter = shortVoiceReplyStarter
        if (customStarter != null) {
            acquireListeningFocus()
            customStarter(onSpoken)
            return
        }
        if (context == null || !hasRecordPermission()) return
        startRecognition(onFinalResult = onSpoken)
    }

    fun startRecognition(
        retryAttempt: Int = 0,
        onFinalResult: (String) -> Unit
    ) {
        val ctx = context ?: return
        val sessionId = ++activeSttSessionId
        val sessionStartMs = System.currentTimeMillis()
        acquireListeningFocus()

        mainHandler.post {
            if (sessionId != activeSttSessionId) return@post
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                    abandonListeningFocus()
                    onMicUnavailable()
                    return@post
                }

                // Putuskan listener lama dan buat instans segar agar terhindar dari callback asinkron
                // onError(ERROR_CLIENT = 5) akibat recognizer.cancel() pada Samsung One UI 6.1
                speechRecognizer?.let { prev ->
                    try {
                        prev.setRecognitionListener(null)
                        prev.destroy()
                    } catch (_: Throwable) {
                    }
                }
                val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext).also {
                    speechRecognizer = it
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (sessionId != activeSttSessionId) return
                        val elapsedMs = System.currentTimeMillis() - sessionStartMs
                        // Jika Samsung One UI mengirim ERROR_CLIENT (5) atau ERROR_RECOGNIZER_BUSY (8)
                        // di awal sesi (< 1.000 ms) akibat pengetukan cepat (rapid-tap), coba ulang hingga 2x secara transparan!
                        if ((error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) &&
                            elapsedMs < 1000L &&
                            retryAttempt < 2
                        ) {
                            GepyLog.w(
                                "VoiceEngine",
                                "SpeechRecognizer transien ($error pada ${elapsedMs}ms), mencoba ulang otomatis (percobaan #${retryAttempt + 1})..."
                            )
                            mainHandler.postDelayed({
                                if (sessionId == activeSttSessionId) {
                                    startRecognition(retryAttempt = retryAttempt + 1, onFinalResult = onFinalResult)
                                }
                            }, 180L)
                            return
                        }

                        GepyLog.w("VoiceEngine", "SpeechRecognizer onError ($error) — kembali ke mode siaga")
                        abandonListeningFocus()
                        if (!isModalWaiting()) {
                            onIdleFallback()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (sessionId != activeSttSessionId) return
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val rawPartial = matches?.firstOrNull()?.trim().orEmpty()
                        val partial = IndonesianSpeechNormalizer.normalize(rawPartial)
                        if (partial.isNotEmpty()) {
                            onPartialTranscript(partial)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (sessionId != activeSttSessionId) return
                        abandonListeningFocus()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val rawTranscript = matches?.firstOrNull()?.trim().orEmpty()
                        val transcript = IndonesianSpeechNormalizer.normalize(rawTranscript)
                        if (transcript.isNotEmpty()) {
                            onFinalResult(transcript)
                        } else if (!isModalWaiting()) {
                            onIdleFallback()
                        }
                    }
                })

                recognizer.startListening(intent)
            } catch (e: Throwable) {
                abandonListeningFocus()
                GepyLog.w("VoiceEngine", "Fallback SpeechRecognizer gagal: ${e.message}")
            }
        }
    }

    fun stopSession() {
        activeSttSessionId++
        mainHandler.post {
            try {
                speechRecognizer?.setRecognitionListener(null)
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Throwable) {
            }
        }
    }

    fun destroy() {
        activeSttSessionId++
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Throwable) {
            }
        }
    }
}
