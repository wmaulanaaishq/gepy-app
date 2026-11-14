package com.teknologigawai.gepy.features.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pengendali pemutaran suara **ElevenLabs Custom Voice** (prefetch paralel terbatas + cache MP3 lokal)
 * dan fallback terkoordinasi ke **Native Android `TextToSpeech` (`id-ID`)**, lengkap dengan penjaga epoch
 * dan pencegahan suara bercampur di tengah kalimat.
 */
internal class ElevenLabsAndTtsPlaybackController(
    private val context: Context?,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val focusManager: VoiceSpeechFocusManager,
    private val customTtsSpeaker: TtsSpeaker?,
    private val elevenLabsClient: ElevenLabsTtsClient?,
    private val voiceCacheManager: VoiceAudioCacheManager?,
    private val customMp3Player: Mp3FilePlayer?,
    private val systemTtsFallbackHook: ((text: String, queueMode: Int) -> Unit)?,
    private val criticalSpeechAwaitTimeoutMs: Long,
    private val isLiveSessionConnected: () -> Boolean,
    private val isLiveCallActiveProvider: () -> Boolean,
    private val speechContextProvider: () -> SovereignSpeechContext
) {
    private sealed interface ClauseSynthOutcome {
        data class Ready(
            val file: File,
            val isTransientTemp: Boolean
        ) : ClauseSynthOutcome

        data class Failed(
            val transient: Boolean,
            val fatalCircuit: Boolean
        ) : ClauseSynthOutcome
    }

    private var androidTts: TextToSpeech? = null

    @Volatile
    private var ttsReady: Boolean = false

    private val speechEpochCounter = AtomicLong(0L)
    private val currentSpeechEpoch: Long
        get() = speechEpochCounter.get()
    private val utteranceSequence = AtomicLong(0L)
    private val pendingSystemTtsWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    @Volatile
    private var activeSpeechJob: Job? = null

    @Volatile
    var elevenLabsConfig: ElevenLabsRuntimeConfig = ElevenLabsRuntimeConfig()

    val isMediaPlayerPlaying: Boolean
        get() = activeMediaPlayer?.isPlaying == true

    val isAndroidTtsSpeaking: Boolean
        get() = androidTts?.isSpeaking == true

    fun initializeSystemTts() {
        val ctx = context ?: return
        mainHandler.post {
            try {
                androidTts = TextToSpeech(ctx.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        androidTts?.language = Locale.forLanguageTag("id-ID")
                        androidTts?.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        androidTts?.setSpeechRate(1.04f)
                        androidTts?.setPitch(1.0f)
                        androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                completeSystemTtsWaiter(utteranceId)
                                focusManager.abandonSystemTtsFocusIfMatching(utteranceId)
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                completeSystemTtsWaiter(utteranceId)
                                focusManager.abandonSystemTtsFocusIfMatching(utteranceId)
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                completeSystemTtsWaiter(utteranceId)
                                focusManager.abandonSystemTtsFocusIfMatching(utteranceId)
                            }
                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                completeSystemTtsWaiter(utteranceId)
                                focusManager.abandonSystemTtsFocusIfMatching(utteranceId)
                            }
                        })
                        ttsReady = true
                    }
                }
            } catch (e: Throwable) {
                GepyLog.w("VoiceEngine", "Gagal menginisialisasi TextToSpeech: ${e.message}")
            }
        }
    }

    fun updateMediaPlayerDuckVolume(ducked: Boolean) {
        val vol = if (ducked) 0.25f else 1.0f
        try {
            activeMediaPlayer?.setVolume(vol, vol)
        } catch (_: Throwable) {
        }
    }

    private fun completeSystemTtsWaiter(utteranceId: String?) {
        if (utteranceId == null) return
        pendingSystemTtsWaiters.remove(utteranceId)?.complete(Unit)
    }

    fun completeAllSystemTtsWaiters() {
        val entries = pendingSystemTtsWaiters.values.toList()
        pendingSystemTtsWaiters.clear()
        entries.forEach { it.complete(Unit) }
    }

    private suspend fun resolveClauseForPlayback(
        idx: Int,
        myEpoch: Long,
        clause: String,
        elConfig: ElevenLabsRuntimeConfig,
        elClient: ElevenLabsTtsClient?,
        cacheMgr: VoiceAudioCacheManager?,
        networkSemaphore: Semaphore,
        createdTempFiles: MutableList<File>
    ): ClauseSynthOutcome {
        if (currentSpeechEpoch != myEpoch || isLiveSessionConnected()) {
            return ClauseSynthOutcome.Failed(transient = false, fatalCircuit = true)
        }
        val onlineUnavailable = !elConfig.canSynthesizeOnline || elClient == null || elClient.isCircuitOpen
        val cached = cacheMgr?.lookupCachedClip(
            voiceId = elConfig.voiceId,
            text = clause,
            modelId = elConfig.modelId,
            stability = elConfig.stability,
            similarityBoost = elConfig.similarityBoost,
            languageCode = "id",
            allowVoiceOnlyFallback = onlineUnavailable
        )
        if (cached != null && cached.exists() && cached.length() > 0L) {
            return ClauseSynthOutcome.Ready(file = cached, isTransientTemp = false)
        }
        if (onlineUnavailable) {
            return ClauseSynthOutcome.Failed(transient = false, fatalCircuit = true)
        }

        var tempOut: File? = null
        val destFile = if (cacheMgr != null) {
            cacheMgr.prepareTargetFile(
                voiceId = elConfig.voiceId,
                text = clause,
                modelId = elConfig.modelId,
                stability = elConfig.stability,
                similarityBoost = elConfig.similarityBoost,
                languageCode = "id"
            )
        } else {
            val baseDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            File(baseDir, "gepy_el_${myEpoch}_${idx}.mp3").also {
                tempOut = it
                createdTempFiles.add(it)
            }
        }

        return networkSemaphore.withPermit {
            if (currentSpeechEpoch != myEpoch || isLiveSessionConnected()) {
                tempOut?.delete()
                return@withPermit ClauseSynthOutcome.Failed(transient = false, fatalCircuit = true)
            }
            val circuitNowOpen = elClient.isCircuitOpen
            val cachedAfterWait = cacheMgr?.lookupCachedClip(
                voiceId = elConfig.voiceId,
                text = clause,
                modelId = elConfig.modelId,
                stability = elConfig.stability,
                similarityBoost = elConfig.similarityBoost,
                languageCode = "id",
                allowVoiceOnlyFallback = circuitNowOpen
            )
            if (cachedAfterWait != null && cachedAfterWait.exists() && cachedAfterWait.length() > 0L) {
                tempOut?.delete()
                return@withPermit ClauseSynthOutcome.Ready(file = cachedAfterWait, isTransientTemp = false)
            }
            if (circuitNowOpen || !elConfig.canSynthesizeOnline) {
                tempOut?.delete()
                return@withPermit ClauseSynthOutcome.Failed(transient = false, fatalCircuit = true)
            }
            val synthRes = elClient.synthesizeToFile(
                apiKey = elConfig.apiKey,
                voiceId = elConfig.voiceId,
                text = clause,
                destinationFile = destFile,
                modelId = elConfig.modelId,
                languageCode = "id",
                stability = elConfig.stability,
                similarityBoost = elConfig.similarityBoost
            )
            when (synthRes) {
                is ElevenLabsSynthResult.Success -> {
                    cacheMgr?.registerCachedClip(
                        voiceId = elConfig.voiceId,
                        text = clause,
                        mp3File = synthRes.file,
                        modelId = elConfig.modelId,
                        stability = elConfig.stability,
                        similarityBoost = elConfig.similarityBoost,
                        languageCode = "id"
                    )
                    ClauseSynthOutcome.Ready(file = synthRes.file, isTransientTemp = (cacheMgr == null))
                }
                is ElevenLabsSynthResult.Failure -> {
                    tempOut?.delete()
                    val isFatalCircuit = synthRes.httpStatus in listOf(401, 402, 403) ||
                        (synthRes.circuitOpened && !synthRes.transient)
                    ClauseSynthOutcome.Failed(
                        transient = synthRes.transient,
                        fatalCircuit = isFatalCircuit
                    )
                }
            }
        }
    }

    suspend fun previewUnsavedElevenLabsVoice(
        draftConfig: ElevenLabsRuntimeConfig,
        sampleText: String = ""
    ): Result<String> {
        val cleanSample = sampleText.trim().ifEmpty {
            SovereignNaturalSpeech.voicePreviewSample(speechContextProvider())
        }
        if (isLiveSessionConnected()) {
            return Result.failure(IllegalStateException("Hentikan Gepy Live Call dulu sebelum mencoba suara."))
        }
        val cleanKey = draftConfig.apiKey.trim()
        val cleanVoiceId = draftConfig.voiceId.trim()
        if (cleanKey.isEmpty() || cleanVoiceId.isEmpty()) {
            return Result.failure(IllegalArgumentException("API Key atau Voice ID ElevenLabs belum diisi."))
        }
        val elClient = elevenLabsClient
            ?: return Result.failure(IllegalStateException("Klien ElevenLabs belum tersedia."))

        stopTtsOnly()
        val baseDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val tempFile = File(baseDir, "gepy_el_draft_preview.mp3")
        val focusTag = focusManager.nextFocusTag()
        val previewEpoch = speechEpochCounter.incrementAndGet()
        val hasFocus = focusManager.acquireSpeechFocusForTag(focusTag)

        return try {
            val synthRes = withContext(Dispatchers.IO) {
                elClient.synthesizeToFile(
                    apiKey = cleanKey,
                    voiceId = cleanVoiceId,
                    text = cleanSample,
                    destinationFile = tempFile,
                    modelId = draftConfig.modelId,
                    languageCode = "id",
                    stability = draftConfig.stability,
                    similarityBoost = draftConfig.similarityBoost,
                    bypassCircuitBreaker = true
                )
            }
            when (synthRes) {
                is ElevenLabsSynthResult.Success -> {
                    val played = try {
                        customMp3Player?.playFile(synthRes.file)
                            ?: playMp3FileBlocking(synthRes.file, previewEpoch)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        false
                    }
                    if (played) {
                        Result.success("Suara berhasil diputar (${draftConfig.modelId}).")
                    } else {
                        Result.failure(IllegalStateException("Audio terunduh, tetapi pemutar media tidak tersedia."))
                    }
                }
                is ElevenLabsSynthResult.Failure -> {
                    Result.failure(IllegalStateException(synthRes.reason))
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (hasFocus) {
                    focusManager.abandonSpeechFocusForTag(focusTag)
                }
                try {
                    if (tempFile.exists()) tempFile.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun speakSafe(message: String) {
        speakSafeInternal(message, critical = false)
    }

    suspend fun speakSafeAndAwaitCompletion(
        message: String,
        critical: Boolean = false,
        timeoutMs: Long = criticalSpeechAwaitTimeoutMs
    ) {
        val clean = message.trim()
        if (clean.isEmpty() || isLiveSessionConnected()) return
        val waitable = speakSafeInternal(clean, critical = critical) ?: return
        val finished = try {
            withTimeoutOrNull(timeoutMs) {
                waitable.await()
            }
        } catch (ce: CancellationException) {
            stopTtsOnly()
            throw ce
        }
        if (finished == null) {
            GepyLog.w(
                "VoiceEngine",
                "speakSafeAndAwaitCompletion melewati batas ${timeoutMs}ms; mematikan TTS agar AudioFocus tidak bocor ke mikrofon."
            )
            stopTtsOnly()
        }
    }

    private fun speakSafeInternal(
        message: String,
        critical: Boolean
    ): CompletableDeferred<Unit>? {
        val clean = message.trim()
        if (clean.isEmpty() || isLiveSessionConnected()) {
            return null
        }
        customTtsSpeaker?.let {
            it.speak(clean)
            return null
        }

        val elConfig = elevenLabsConfig
        val elClient = elevenLabsClient
        val cacheMgr = voiceCacheManager
        val canReadFromCache = elConfig.canReadCache && cacheMgr != null
        val canSynthesizeOnline = elConfig.canSynthesizeOnline && elClient != null
        val canAttemptElevenLabs = canReadFromCache || canSynthesizeOnline

        if (canAttemptElevenLabs) {
            val myEpoch = speechEpochCounter.incrementAndGet()
            val myFocusTag = focusManager.nextFocusTag()
            activeSpeechJob?.cancel()
            stopMediaPlayerOnly()
            focusManager.isSpeechDucked = false

            val clauses = VoiceClauseChunker.splitIntoClauses(clean).ifEmpty { listOf(clean) }
            val completionDeferred = CompletableDeferred<Unit>()
            val job = scope.launch(Dispatchers.IO) {
                val hasFocus = focusManager.acquireSpeechFocusForTag(myFocusTag)
                val allowWithoutFocus = critical || elConfig.allowMidUtteranceSystemFallback
                if (!hasFocus && !allowWithoutFocus) {
                    GepyLog.w("VoiceEngine", "Melewati pemutaran suara ElevenLabs karena AudioFocus tidak diberikan.")
                    return@launch
                }
                if (!hasFocus) {
                    GepyLog.w("VoiceEngine", "AudioFocus ditolak; tetap melanjutkan ucapan penting ($critical).")
                }

                val createdTempFiles = CopyOnWriteArrayList<File>()
                var handedOffWaiter: CompletableDeferred<Unit>? = null
                try {
                    val onlineUnavailable = !elConfig.canSynthesizeOnline || elClient == null || elClient.isCircuitOpen
                    val preflightMissingOfflineClause = onlineUnavailable &&
                        !elConfig.allowMidUtteranceSystemFallback &&
                        (
                            cacheMgr == null ||
                                clauses.asSequence()
                                    .distinctBy { VoiceClauseChunker.normalizeCacheKeyText(it) }
                                    .any { clause ->
                                        !cacheMgr.hasCachedClip(
                                            voiceId = elConfig.voiceId,
                                            text = clause,
                                            modelId = elConfig.modelId,
                                            stability = elConfig.stability,
                                            similarityBoost = elConfig.similarityBoost,
                                            languageCode = "id",
                                            allowVoiceOnlyFallback = true
                                        )
                                    }
                            )

                    if (preflightMissingOfflineClause) {
                        val fullFallbackText = VoiceClauseChunker.stripAudioTagsForSystemTts(clean)
                        if (fullFallbackText.isNotEmpty() &&
                            isActive &&
                            currentSpeechEpoch == myEpoch &&
                            !isLiveSessionConnected()
                        ) {
                            GepyLog.i(
                                "VoiceEngine",
                                "Mode offline cache-first: sebagian klausa belum tersedia di cache; mengalihkan kalimat utuh ke Android TTS."
                            )
                            handedOffWaiter = speakSystemAndroidTts(
                                text = fullFallbackText,
                                queueMode = TextToSpeech.QUEUE_FLUSH,
                                holdExistingFocusTag = if (hasFocus) myFocusTag else null,
                                critical = critical
                            )
                        }
                    } else {
                        coroutineScope {
                            val networkSemaphore = Semaphore(permits = 2)
                            val prefetchDeferreds = arrayOfNulls<Deferred<ClauseSynthOutcome>>(clauses.size)
                            val prefetchByClauseKey = HashMap<String, Deferred<ClauseSynthOutcome>>()

                            fun ensurePrefetchStarted(idx: Int) {
                                if (idx !in clauses.indices || prefetchDeferreds[idx] != null) return
                                val clauseText = clauses[idx]
                                val normalizedKey = VoiceClauseChunker.normalizeCacheKeyText(clauseText)
                                val existingDeferred = prefetchByClauseKey[normalizedKey]
                                if (existingDeferred != null) {
                                    prefetchDeferreds[idx] = existingDeferred
                                    return
                                }
                                val launched = async(Dispatchers.IO) {
                                    resolveClauseForPlayback(
                                        idx = idx,
                                        myEpoch = myEpoch,
                                        clause = clauseText,
                                        elConfig = elConfig,
                                        elClient = elClient,
                                        cacheMgr = cacheMgr,
                                        networkSemaphore = networkSemaphore,
                                        createdTempFiles = createdTempFiles
                                    )
                                }
                                prefetchByClauseKey[normalizedKey] = launched
                                prefetchDeferreds[idx] = launched
                            }

                            // Jendela lookahead awal (maks. 2 permintaan serentak) agar antarklausa mulus & bebas pembatasan 429
                            val initialWindow = minOf(2, clauses.size)
                            for (w in 0 until initialWindow) {
                                ensurePrefetchStarted(w)
                            }

                            var usedElevenLabsInUtterance = false
                            for (i in clauses.indices) {
                                if (!isActive || currentSpeechEpoch != myEpoch || isLiveSessionConnected()) break

                                ensurePrefetchStarted(i)
                                ensurePrefetchStarted(i + 1)
                                ensurePrefetchStarted(i + 2)

                                var outcome = prefetchDeferreds[i]?.await()
                                    ?: ClauseSynthOutcome.Failed(transient = false, fatalCircuit = true)

                                // Pemulihan ekstra 1x bila klausa lanjutan gagal sementara padahal suara gepy sudah berbunyi
                                if (outcome is ClauseSynthOutcome.Failed &&
                                    usedElevenLabsInUtterance &&
                                    !outcome.fatalCircuit &&
                                    isActive &&
                                    currentSpeechEpoch == myEpoch &&
                                    !isLiveSessionConnected()
                                ) {
                                    GepyLog.w("VoiceEngine", "Mencoba pemulihan ekstra klausa #$i agar suara custom tidak terputus...")
                                    delay(180L)
                                    if (isActive && currentSpeechEpoch == myEpoch && !isLiveSessionConnected()) {
                                        outcome = resolveClauseForPlayback(
                                            idx = i,
                                            myEpoch = myEpoch,
                                            clause = clauses[i],
                                            elConfig = elConfig,
                                            elClient = elClient,
                                            cacheMgr = cacheMgr,
                                            networkSemaphore = networkSemaphore,
                                            createdTempFiles = createdTempFiles
                                        )
                                    }
                                }

                                if (!isActive || currentSpeechEpoch != myEpoch || isLiveSessionConnected()) break

                                val mp3File = (outcome as? ClauseSynthOutcome.Ready)?.file
                                val played = if (mp3File != null && mp3File.exists() && mp3File.length() > 0L) {
                                    val isTemp = outcome.isTransientTemp
                                    try {
                                        val ok = customMp3Player?.playFile(mp3File) ?: playMp3FileBlocking(mp3File, myEpoch)
                                        if (ok) {
                                            usedElevenLabsInUtterance = true
                                        }
                                        ok
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (_: Throwable) {
                                        false
                                    } finally {
                                        if (isTemp) {
                                            mp3File.delete()
                                        }
                                    }
                                } else {
                                    false
                                }

                                if (!played && isActive && currentSpeechEpoch == myEpoch && !isLiveSessionConnected()) {
                                    for (j in (i + 1) until clauses.size) {
                                        prefetchDeferreds[j]?.cancel()
                                    }
                                    if (!usedElevenLabsInUtterance || elConfig.allowMidUtteranceSystemFallback || critical) {
                                        val remainingText = VoiceClauseChunker.stripAudioTagsForSystemTts(
                                            clauses.drop(i).joinToString(" ")
                                        )
                                        if (remainingText.isNotEmpty()) {
                                            handedOffWaiter = speakSystemAndroidTts(
                                                text = remainingText,
                                                queueMode = TextToSpeech.QUEUE_FLUSH,
                                                holdExistingFocusTag = if (hasFocus) myFocusTag else null,
                                                critical = critical
                                            )
                                        }
                                    } else {
                                        GepyLog.w(
                                            "VoiceEngine",
                                            "Klausa #$i tidak tersedia; menjaga konsistensi suara custom gepy tanpa mencampur Android TTS di tengah kalimat."
                                        )
                                    }
                                    break
                                }
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        for (tmp in createdTempFiles) {
                            try {
                                if (tmp.exists()) tmp.delete()
                            } catch (_: Throwable) {
                            }
                        }
                        focusManager.isSpeechDucked = false
                        focusManager.abandonSpeechFocusUnlessHandedToSystemTts(myFocusTag)
                    }
                }
                val waiter = handedOffWaiter
                if (waiter != null && isActive && currentSpeechEpoch == myEpoch && !isLiveSessionConnected()) {
                    waiter.await()
                }
            }
            activeSpeechJob = job
            if (currentSpeechEpoch != myEpoch) {
                if (activeSpeechJob === job) {
                    activeSpeechJob = null
                }
                job.cancel()
            }
            job.invokeOnCompletion {
                if (activeSpeechJob === job) {
                    activeSpeechJob = null
                }
                completionDeferred.complete(Unit)
            }
            return completionDeferred
        }

        val sanitizedFallback = VoiceClauseChunker.stripAudioTagsForSystemTts(clean)
        if (sanitizedFallback.isNotEmpty()) {
            return speakSystemAndroidTts(
                text = sanitizedFallback,
                queueMode = TextToSpeech.QUEUE_FLUSH,
                holdExistingFocusTag = null,
                critical = critical
            )
        }
        return null
    }

    private fun speakSystemAndroidTts(
        text: String,
        queueMode: Int,
        holdExistingFocusTag: Long? = null,
        critical: Boolean = false
    ): CompletableDeferred<Unit>? {
        if (text.isBlank()) return null
        val focusTag = holdExistingFocusTag ?: focusManager.nextFocusTag()

        systemTtsFallbackHook?.let { hook ->
            if (holdExistingFocusTag == null) {
                val hasFocus = focusManager.acquireSpeechFocusForTag(focusTag)
                if (!hasFocus && !critical) return null
                try {
                    hook(text, queueMode)
                } finally {
                    if (hasFocus) {
                        focusManager.abandonSpeechFocusForTag(focusTag)
                    }
                }
            } else {
                hook(text, queueMode)
            }
            return null
        }

        if (context == null || !ttsReady) return null
        val acquiredLocalFocus = if (holdExistingFocusTag == null) {
            val granted = focusManager.acquireSpeechFocusForTag(focusTag)
            if (!granted && !critical) {
                return null
            }
            granted
        } else {
            false
        }
        return try {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                completeAllSystemTtsWaiters()
            }
            val doneDeferred = CompletableDeferred<Unit>()
            val uttId = "gepy_tts_${utteranceSequence.incrementAndGet()}"
            pendingSystemTtsWaiters[uttId] = doneDeferred
            focusManager.registerSystemTtsUtterance(uttId, focusTag)
            val params = if (isLiveCallActiveProvider()) {
                Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                }
            } else {
                null
            }
            val result = androidTts?.speak(text, queueMode, params, uttId)
            if (result != TextToSpeech.SUCCESS) {
                pendingSystemTtsWaiters.remove(uttId)?.complete(Unit)
                focusManager.clearSystemTtsUtteranceIfMatching(uttId)
                if (acquiredLocalFocus) {
                    focusManager.abandonSpeechFocusForTag(focusTag)
                }
                null
            } else {
                doneDeferred
            }
        } catch (_: Throwable) {
            completeAllSystemTtsWaiters()
            focusManager.clearSystemTtsUtterance()
            if (acquiredLocalFocus) {
                focusManager.abandonSpeechFocusForTag(focusTag)
            }
            null
        }
    }

    private suspend fun playMp3FileBlocking(
        file: File,
        expectedEpoch: Long
    ): Boolean {
        if (context == null || !file.exists() || file.length() <= 0L) return false
        val epochMatches = (currentSpeechEpoch == expectedEpoch)
        val liveBlocked = isLiveSessionConnected()
        if (!epochMatches || liveBlocked) return false

        return suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { mp ->
                    if (activeMediaPlayer === mp) {
                        activeMediaPlayer = null
                    }
                    try {
                        mp.release()
                    } catch (_: Throwable) {
                    }
                    if (cont.isActive) cont.resume(true)
                }
                player.setOnErrorListener { mp, _, _ ->
                    if (activeMediaPlayer === mp) {
                        activeMediaPlayer = null
                    }
                    try {
                        mp.release()
                    } catch (_: Throwable) {
                    }
                    if (cont.isActive) cont.resume(false)
                    true
                }
                activeMediaPlayer = player
                player.prepare()
                val epochMatchesPreStart = (currentSpeechEpoch == expectedEpoch)
                val liveBlockedPreStart = isLiveSessionConnected()
                if (!epochMatchesPreStart || liveBlockedPreStart) {
                    if (activeMediaPlayer === player) activeMediaPlayer = null
                    player.release()
                    if (cont.isActive) cont.resume(false)
                    return@suspendCancellableCoroutine
                }
                val gain = if (focusManager.isSpeechDucked) 0.25f else 1.0f
                player.setVolume(gain, gain)
                player.start()
                cont.invokeOnCancellation {
                    if (activeMediaPlayer === player) {
                        activeMediaPlayer = null
                    }
                    try {
                        if (player.isPlaying) player.stop()
                        player.release()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
                if (activeMediaPlayer === player) {
                    activeMediaPlayer = null
                }
                try {
                    player.release()
                } catch (_: Throwable) {
                }
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun stopMediaPlayerOnly() {
        val player = activeMediaPlayer
        activeMediaPlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                player.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun stopTtsOnly() {
        speechEpochCounter.incrementAndGet()
        activeSpeechJob?.cancel()
        activeSpeechJob = null
        stopMediaPlayerOnly()
        focusManager.isSpeechDucked = false
        try {
            androidTts?.stop()
        } catch (_: Throwable) {
        }
        completeAllSystemTtsWaiters()
        focusManager.forceAbandonSpeechFocus()
    }

    fun shutdown() {
        completeAllSystemTtsWaiters()
        mainHandler.post {
            try {
                androidTts?.shutdown()
                androidTts = null
            } catch (_: Throwable) {
            }
        }
    }
}
