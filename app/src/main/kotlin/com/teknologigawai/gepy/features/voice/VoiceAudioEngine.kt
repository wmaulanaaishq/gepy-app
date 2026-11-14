package com.teknologigawai.gepy.features.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mesin audio hibrida native (`VoiceAudioEngine`) yang mengimplementasikan [VoiceHudPort]
 * di dalam satu proses ART (*Single-Process Zero-Isolate*):
 * - **100% Native Android `SpeechRecognizer` (`id-ID`)** untuk perintah suara cepat (cepat, stabil, bebas kuota).
 * - **Gemini 3.8 Live Full-Duplex** (`AudioRecord` `16 kHz` dengan hardware `AcousticEchoCanceler` +
 *   `AudioTrack` `24 kHz` `USAGE_VOICE_COMMUNICATION` + *Instant Local VAD Barge-In*).
 * - **ElevenLabs Custom Voice (`gepy`) + Bounded Parallel Prefetch & Auto-Cache MP3 Lokal**
 *   dengan perlindungan anti suara campur di tengah kalimat, `AudioFocusRequest` terkelola per-ucapan,
 *   serta fallback bersih ke **Native Android `TextToSpeech` (`id-ID`)**, otomatis dibungkam saat *Live Call* aktif.
 * - **In-Memory `StateFlow<OverlayHudState>`** untuk `OverlayHudWindowManager` (`ComposeView`).
 */
class VoiceAudioEngine(
    private val context: Context? = null,
    val geminiLiveClient: GeminiLiveClient = GeminiLiveClient(),
    private val customTtsSpeaker: TtsSpeaker? = null,
    initialUserMemoryManager: com.teknologigawai.gepy.core.memory.UserMemoryManager? = null,
    var hapticManager: com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager? = null,
    var onLiveCallToggleRequested: (() -> Unit)? = null,
    /** Callback suspend untuk men-toggle Live Call secara berurutan dari Overlay HUD tanpa race condition. */
    var onLiveCallToggleSuspendRequested: (suspend () -> Unit)? = null,
    /** Listener transkrip parsial (*Speculative Listening*) saat pengguna masih berbicara pada Live Call. */
    var onInterimSpeechListener: ((String) -> Unit)? = null,
    /** Callback ketika sesi Live Call dihentikan/ditutup (agar tugas otomatisasi berjalan dibatalkan secara bersih). */
    var onLiveCallTerminated: ((reason: String) -> Unit)? = null,
    val elevenLabsClient: ElevenLabsTtsClient? = null,
    val voiceCacheManager: VoiceAudioCacheManager? = null,
    private val customMp3Player: Mp3FilePlayer? = null,
    private val audioFocusCoordinator: VoiceAudioFocusCoordinator = if (context != null) {
        AndroidVoiceAudioFocusCoordinator(context)
    } else {
        NoOpVoiceAudioFocusCoordinator
    },
    private val systemTtsFallbackHook: ((text: String, queueMode: Int) -> Unit)? = null,
    private val liveCallAudioRouter: LiveCallAudioRouter? = context?.let {
        ManagedLiveCallAudioRouter(AndroidSystemAudioRoutingBackend(it))
    },
    private val shortVoiceReplyStarter: ((onSpoken: (String) -> Unit) -> Unit)? = null,
    private val criticalSpeechAwaitTimeoutMs: Long = 15_000L
) : VoiceHudPort {

    var userMemoryManager: com.teknologigawai.gepy.core.memory.UserMemoryManager? = initialUserMemoryManager
        set(value) {
            field = value
            value?.onMemoryChangedListener = { updatedContext ->
                syncMemoryContextToLiveSession(updatedContext)
            }
        }

    init {
        initialUserMemoryManager?.onMemoryChangedListener = { updatedContext ->
            syncMemoryContextToLiveSession(updatedContext)
        }
    }

    companion object {
        private val APPROVE_WORDS = listOf("ya", "iya", "setuju", "eksekusi", "kirim", "lanjut")
        private val REJECT_WORDS = listOf("batal", "batalkan", "tidak", "jangan")

        val HANGUP_KEYWORDS = listOf(
            "tutup telepon", "matikan telepon", "akhiri panggilan",
            "stop telepon", "tutup panggilan", "sudah dulu ya",
            "matikan gepy", "bye gepy", "selesai gepy",
            "sampai jumpa gepy", "sampai jumpa", "stop panggilan",
            "akhiri telepon", "berhenti telepon", "matikan suara",
            "tutup telpon", "matikan telpon", "akhiri telpon", "stop telpon",
            "matikan panggilan", "selesai panggilan", "tutup call", "akhiri call", "end call"
        )

        private val MESSAGE_DICTATION_EXCLUSION_REGEX = Regex(
            """\b(kirim|wa|whatsapp|chat|pesan|dm|bilang|ketik|balas|tulis)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveAudioTrackPlayer = LiveAudioTrackPlayer(context)
    private val micCaptureController = LiveMicCaptureController(scope) { hasRecordPermission() }

    private val _voiceCommandFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val voiceCommandFlow: Flow<String> = _voiceCommandFlow.asSharedFlow()

    private val _bargeInInterruptFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val bargeInInterruptFlow: Flow<String> = _bargeInInterruptFlow.asSharedFlow()

    private val _hudStateFlow = MutableStateFlow(OverlayHudState())
    val hudStateFlow: StateFlow<OverlayHudState> = _hudStateFlow.asStateFlow()

    private val speechFocusManager = VoiceSpeechFocusManager(
        audioFocusCoordinator = audioFocusCoordinator,
        onSpeechFocusLost = { stopTtsOnly() },
        onSpeechDuckChanged = { ducked -> updateMediaPlayerDuckVolume(ducked) }
    )

    private val ttsPlaybackController: ElevenLabsAndTtsPlaybackController = ElevenLabsAndTtsPlaybackController(
        context = context,
        scope = scope,
        mainHandler = mainHandler,
        focusManager = speechFocusManager,
        customTtsSpeaker = customTtsSpeaker,
        elevenLabsClient = elevenLabsClient,
        voiceCacheManager = voiceCacheManager,
        customMp3Player = customMp3Player,
        systemTtsFallbackHook = systemTtsFallbackHook,
        criticalSpeechAwaitTimeoutMs = criticalSpeechAwaitTimeoutMs,
        isLiveSessionConnected = { geminiLiveClient.isConnected },
        isLiveCallActiveProvider = { isLiveCallActive },
        speechContextProvider = { speechContextProvider() }
    )

    var elevenLabsConfig: ElevenLabsRuntimeConfig
        get() = ttsPlaybackController.elevenLabsConfig
        set(value) {
            ttsPlaybackController.elevenLabsConfig = value
        }

    internal val currentLiveAudioEpochForTest: Long
        get() = liveAudioTrackPlayer.currentEpoch
    internal val currentPlaybackEndEpochForTest: Long
        get() = liveAudioTrackPlayer.playbackEstimatedEndEpochMs

    @Volatile
    private var disambiguationDeferred: CompletableDeferred<String>? = null
    @Volatile
    private var hitlDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var whisperDismissJob: Job? = null

    private val speechRecognitionRunner = AndroidSpeechRecognitionRunner(
        context = context,
        mainHandler = mainHandler,
        shortVoiceReplyStarter = shortVoiceReplyStarter,
        acquireListeningFocus = { speechFocusManager.acquireListeningFocusSafe() },
        abandonListeningFocus = { abandonListeningFocusSafe() },
        isModalWaiting = { (hitlDeferred?.isActive == true) || (disambiguationDeferred?.isActive == true) },
        onMicUnavailable = {
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.THINKING,
                headline = "Mikrofon Tidak Tersedia",
                subtitle = "Pastikan izin mikrofon aktif di pengaturan sistem Android."
            )
        },
        onPartialTranscript = { partial ->
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.LISTENING,
                headline = "Kamu",
                subtitle = partial,
                activityKind = OverlayHudActivityKind.LISTENING,
                statusPillLabel = "Mendengarkan...",
                showStopButton = true
            )
        },
        onIdleFallback = {
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.IDLE,
                headline = "Gepy Siaga",
                subtitle = "Ketuk untuk perintah suara"
            )
            _bargeInInterruptFlow.tryEmit("STT_TIMEOUT_OR_ERROR")
        }
    )

    private val liveTurnTranscriptBuffer = StringBuilder()
    private val callSessionTurns = mutableListOf<com.teknologigawai.gepy.core.memory.CallTranscriptTurn>()

    private fun clearLiveTranscriptBuffer() {
        synchronized(liveTurnTranscriptBuffer) {
            liveTurnTranscriptBuffer.clear()
        }
    }

    private fun hasPendingLiveTranscript(): Boolean =
        synchronized(liveTurnTranscriptBuffer) {
            liveTurnTranscriptBuffer.isNotEmpty()
        }

    private fun appendLiveTranscriptTokens(incomingTokens: String): String =
        synchronized(liveTurnTranscriptBuffer) {
            if (liveTurnTranscriptBuffer.isNotEmpty() &&
                !liveTurnTranscriptBuffer.endsWith(" ") &&
                !incomingTokens.startsWith(" ") &&
                !incomingTokens.startsWith(",") &&
                !incomingTokens.startsWith(".") &&
                !incomingTokens.startsWith("!") &&
                !incomingTokens.startsWith("?") &&
                !incomingTokens.startsWith(";") &&
                !incomingTokens.startsWith(":")
            ) {
                liveTurnTranscriptBuffer.append(' ')
            }
            liveTurnTranscriptBuffer.append(incomingTokens)
            liveTurnTranscriptBuffer.toString()
        }

    private fun drainLiveTranscriptBuffer(): String =
        synchronized(liveTurnTranscriptBuffer) {
            val snapshot = liveTurnTranscriptBuffer.toString().trim()
            liveTurnTranscriptBuffer.clear()
            snapshot
        }

    @Volatile
    private var turnHadLiveAudio: Boolean = false
    @Volatile
    private var isInitialized: Boolean = false

    /** Penyedia konteks obrolan aktif (Chat & Live Call) dari orkestrator untuk disuntikkan ke Gemini Live. */
    var sharedConversationContextProvider: (() -> String)? = null

    /** Callback real-time ketika satu giliran ucapan pengguna atau balasan Gepy selesai di Gemini Live Call. */
    var onLiveTurnRecorded: ((role: String, text: String) -> Unit)? = null

    /** Penyedia konteks kepribadian & kondisi pemilik untuk kalimat sistem VoiceAudioEngine. */
    @Volatile
    var speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }

    @Volatile
    private var muteSttUntilEpochMs: Long = 0L

    /** Penguatan & skala volume suara Live Call (`0.0f` = bisu/mute, `1.0f` = 100% hardware, `1.0f..2.0f` = boost digital). */
    var liveSpeakerGain: Float
        get() = liveAudioTrackPlayer.liveSpeakerGain
        set(value) {
            liveAudioTrackPlayer.liveSpeakerGain = value
        }

    /** Mengatur skala volume output Gemini Live (`0.0f..2.0f`) secara instan di tengah panggilan aktif. */
    fun setLiveCallOutputScale(scale: Float) = liveAudioTrackPlayer.setOutputScale(scale)

    private fun writePcm24kChunk(chunk: ByteArray) {
        if (liveAudioTrackPlayer.writePcm24kChunk(chunk)) {
            turnHadLiveAudio = true
        }
    }

    private fun stopAudioTrackPlayback() = liveAudioTrackPlayer.stopPlayback()

    val hasGeminiLiveKey: Boolean
        get() = geminiLiveClient.hasApiKey

    val isGeminiLiveConnected: Boolean
        get() = geminiLiveClient.connectionStateFlow.value == LiveConnectionState.CONNECTED

    val activeGeminiLiveModel: String
        get() = geminiLiveClient.activeModelId

    val lastGeminiLiveError: String?
        get() = geminiLiveClient.lastFailureReason

    override val isLiveCallActive: Boolean
        get() = geminiLiveClient.isConnected ||
                geminiLiveClient.connectionStateFlow.value == LiveConnectionState.CONNECTED ||
                geminiLiveClient.connectionStateFlow.value == LiveConnectionState.CONNECTING ||
                geminiLiveClient.connectionStateFlow.value == LiveConnectionState.RECONNECTING

    internal var androidTtsSpeakingProvider: (() -> Boolean)? = null

    override val isPlaybackActive: Boolean
        get() = liveAudioTrackPlayer.isWindowActive() ||
                ttsPlaybackController.isMediaPlayerPlaying ||
                (androidTtsSpeakingProvider?.invoke() == true) ||
                ttsPlaybackController.isAndroidTtsSpeaking

    override fun injectLiveAgentInterruption(announcementText: String): Boolean {
        if (geminiLiveClient.isConnected) {
            return geminiLiveClient.injectLiveAgentInterruption(announcementText)
        }
        return false
    }

    init {
        geminiLiveClient.directAudioChunkCallback = { chunk ->
            writePcm24kChunk(chunk)
        }
        geminiLiveClient.isPlaybackActiveProvider = {
            liveAudioTrackPlayer.isWindowActive() ||
                ttsPlaybackController.isMediaPlayerPlaying ||
                (androidTtsSpeakingProvider?.invoke() == true) ||
                ttsPlaybackController.isAndroidTtsSpeaking
        }

        scope.launch {
            geminiLiveClient.bargeInFlow.collect {
                liveAudioTrackPlayer.clearEstimatedEnd()
                clearLiveTranscriptBuffer()
                turnHadLiveAudio = false
                stopAudioTrackPlayback()
                stopTtsOnly()
                _bargeInInterruptFlow.tryEmit("GEMINI_LIVE_BARGE_IN")
            }
        }
        scope.launch {
            geminiLiveClient.eventsFlow.collect { event ->
                handleGeminiLiveEvent(event)
            }
        }
        scope.launch {
            geminiLiveClient.connectionStateFlow.collect { connState ->
                val active = connState == LiveConnectionState.CONNECTED ||
                             connState == LiveConnectionState.CONNECTING ||
                             connState == LiveConnectionState.RECONNECTING
                if (_hudStateFlow.value.isLiveCallActive != active) {
                    _hudStateFlow.value = _hudStateFlow.value.copy(isLiveCallActive = active)
                }

                when (connState) {
                    LiveConnectionState.RECONNECTING -> {
                        pushHudState(
                            isVisible = true,
                            mode = OverlayVisualMode.LISTENING,
                            headline = "Gepy Live • Menyambung Ulang...",
                            subtitle = "Sinyal agak goyang, mencoba menyambung ulang...",
                            activityKind = OverlayHudActivityKind.CONNECTING,
                            statusPillLabel = "Menyambung ulang...",
                            showStopButton = true
                        )
                    }
                    LiveConnectionState.CONNECTED -> {
                        pushHudState(
                            isVisible = true,
                            mode = OverlayVisualMode.LISTENING,
                            headline = "Gepy Live",
                            subtitle = SovereignNaturalSpeech.liveCallConnected(speechContextProvider()),
                            activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                            statusPillLabel = "Mendengarkan...",
                            showStopButton = true
                        )
                    }
                    LiveConnectionState.FAILED -> {
                        if (_hudStateFlow.value.isVisible && _hudStateFlow.value.mode == OverlayVisualMode.LISTENING) {
                            val failMsg = SovereignNaturalSpeech.liveCallFailed(speechContextProvider())
                            pushHudState(
                                isVisible = true,
                                mode = OverlayVisualMode.IDLE,
                                headline = "Gepy Siaga",
                                subtitle = failMsg
                            )
                            speakSafe("$failMsg Ketuk tombol mic kapan saja kalau mau lanjut ngobrol ya!")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun updateGeminiApiKey(apiKey: String) {
        geminiLiveClient.updateApiKey(apiKey)
    }

    fun updateGeminiApiKeys(primaryKey: String, secondaryKey: String) {
        geminiLiveClient.updateApiKeys(primaryKey, secondaryKey)
    }

    fun updateElevenLabsConfig(config: ElevenLabsRuntimeConfig) {
        elevenLabsConfig = config
        elevenLabsClient?.resetCircuitBreaker()
    }

    fun setLiveVoiceName(voiceName: String) {
        geminiLiveClient.voiceName = voiceName.trim().ifEmpty { "Aoede" }
    }

    override suspend fun initialize() {
        if (isInitialized) return
        isInitialized = true
        ttsPlaybackController.initializeSystemTts()
    }

    override suspend fun showIdlePill() {
        initialize()
        pushHudState(
            isVisible = true,
            mode = OverlayVisualMode.IDLE,
            headline = "Gepy Siaga",
            subtitle = "Ketuk untuk perintah suara"
        )
    }

    override suspend fun hideOverlay() {
        stopAllSpeechAndTts()
        pushHudState(
            isVisible = false,
            mode = OverlayVisualMode.IDLE,
            headline = "Gepy Siaga",
            subtitle = "Ketuk untuk perintah suara"
        )
    }

    override suspend fun startListening() {
        initialize()
        pushHudState(
            isVisible = true,
            mode = OverlayVisualMode.LISTENING,
            headline = "Mendengarkan",
            subtitle = SovereignNaturalSpeech.liveCallListeningPrompt(speechContextProvider()),
            activityKind = OverlayHudActivityKind.LISTENING,
            statusPillLabel = "Mendengarkan...",
            showStopButton = true
        )

        stopAudioTrackPlayback()
        stopTtsOnly()
        if (!geminiLiveClient.isConnected) {
            micCaptureController.stop()
        }
        if (context == null) return

        // 100% Native Android SpeechRecognizer (id-ID) — Cepat, Akurat, Tanpa Kuota API
        speechRecognitionRunner.startRecognition { recognized ->
            val clean = recognized.trim()
            if (clean.isNotEmpty()) {
                _voiceCommandFlow.tryEmit(clean)
            }
        }
    }

    override suspend fun showThinkingState(stepHeadline: String, thought: String) {
        val cleanHeadline = stepHeadline.trim().ifBlank { "Berpikir..." }
        val kind = if (isExecutingHeadline(cleanHeadline)) {
            OverlayHudActivityKind.EXECUTING
        } else {
            OverlayHudActivityKind.THINKING
        }
        val pillLabel = if (kind == OverlayHudActivityKind.EXECUTING) {
            cleanHeadline.trimEnd('.') + "..."
        } else {
            OverlayHudActivityKind.THINKING.defaultPillLabel
        }
        pushHudState(
            isVisible = true,
            mode = OverlayVisualMode.THINKING,
            headline = cleanHeadline,
            subtitle = thought,
            activityKind = kind,
            statusPillLabel = pillLabel,
            showStopButton = isLiveCallActive
        )
    }

    override suspend fun askDisambiguation(questionTts: String, options: List<String>): String {
        if (options.isEmpty()) return ""
        val deferred = CompletableDeferred<String>()
        disambiguationDeferred = deferred

        return try {
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.DISAMBIGUATION,
                headline = questionTts,
                subtitle = "Pilih salah satunya di bawah ini ya:",
                options = options
            )

            if (geminiLiveClient.isConnected) {
                geminiLiveClient.sendRealtimeText(
                    "[KONFIRMASI DISAMBIGUASI]: $questionTts Pilihan kandidat: ${options.joinToString(", ")}. " +
                        "Tanyakan pilihan ini kepada pengguna sekarang secara singkat."
                )
            } else {
                speakSafeAndAwaitCompletion(questionTts, critical = true, timeoutMs = criticalSpeechAwaitTimeoutMs)
                if (deferred.isCompleted) {
                    return deferred.await()
                }
                if (context == null && shortVoiceReplyStarter == null) {
                    return options.first()
                }
                listenForShortVoiceReply { rawSpoken ->
                    val spoken = rawSpoken.trim().lowercase()
                    val matched = options.firstOrNull { candidate ->
                        candidate.lowercase().contains(spoken) || spoken.contains(candidate.lowercase())
                    }
                    if (matched != null && deferred.complete(matched)) {
                        if (disambiguationDeferred === deferred) {
                            disambiguationDeferred = null
                        }
                        stopAllSpeechAndTts()
                    }
                }
            }

            if (context == null && shortVoiceReplyStarter == null && !geminiLiveClient.isConnected) {
                return options.first()
            }

            withTimeoutOrNull(35_000L) { deferred.await() } ?: run {
                if (disambiguationDeferred === deferred) {
                    disambiguationDeferred = null
                }
                stopAllSpeechAndTts()
                ""
            }
        } finally {
            if (disambiguationDeferred === deferred) {
                disambiguationDeferred = null
            }
            abandonListeningFocusSafe()
        }
    }

    override suspend fun requestHitlApproval(summaryPrompt: String, targetLabel: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        hitlDeferred = deferred

        return try {
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.HITL_CONFIRM,
                headline = "Konfirmasi Aksi ($targetLabel)",
                subtitle = summaryPrompt
            )

            if (geminiLiveClient.isConnected) {
                geminiLiveClient.sendRealtimeText(
                    "[KONFIRMASI KEAMANAN HITL]: $summaryPrompt " +
                        "Tanyakan persetujuan Ya atau Batal kepada pengguna sekarang secara singkat."
                )
            } else {
                speakSafeAndAwaitCompletion(summaryPrompt, critical = true, timeoutMs = criticalSpeechAwaitTimeoutMs)
                if (deferred.isCompleted) {
                    return deferred.await()
                }
                if (context == null && shortVoiceReplyStarter == null) {
                    return false
                }
                listenForShortVoiceReply { rawSpoken ->
                    val spoken = rawSpoken.trim().lowercase()
                    when {
                        REJECT_WORDS.any { spoken.contains(it) } -> {
                            if (deferred.complete(false)) {
                                if (hitlDeferred === deferred) {
                                    hitlDeferred = null
                                }
                                stopAllSpeechAndTts()
                            }
                        }
                        APPROVE_WORDS.any { spoken.contains(it) } -> {
                            if (deferred.complete(true)) {
                                if (hitlDeferred === deferred) {
                                    hitlDeferred = null
                                }
                                stopAllSpeechAndTts()
                            }
                        }
                    }
                }
            }

            if (context == null && shortVoiceReplyStarter == null && !geminiLiveClient.isConnected) {
                return false
            }

            withTimeoutOrNull(40_000L) { deferred.await() } ?: run {
                if (hitlDeferred === deferred) {
                    hitlDeferred = null
                }
                stopAllSpeechAndTts()
                false
            }
        } finally {
            if (hitlDeferred === deferred) {
                hitlDeferred = null
            }
            abandonListeningFocusSafe()
        }
    }

    override suspend fun finishAndSpeak(completionTts: String) {
        val displaySafe = VoiceClauseChunker.stripAudioTagsForDisplay(completionTts)
        if (geminiLiveClient.isConnected) {
            // 1. Kirim status penyelesaian ke sesi Gemini Live via Realtime Text
            geminiLiveClient.sendRealtimeText(
                "[STATUS SISTEM ANDROID]: Tugas telah selesai dijalankan. Hasil: $displaySafe. " +
                    "Sampaikan status ini kepada pengguna secara santai, luwes, dan ringkas."
            )
            // 2. Pertahankan HUD dalam mode Live Call (LIVE_LISTENING) dengan ringkasan hasil
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.LISTENING,
                headline = "Gepy Live",
                subtitle = displaySafe,
                activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                statusPillLabel = "Mendengarkan...",
                showStopButton = true
            )
            // 3. JANGAN panggil TTS lokal saat Live Call aktif agar tidak bocor ke mikrofon!
            return
        }

        showIdlePill()
        speakSafe(completionTts)
    }

    /**
     * Memulai sesi panggilan dua arah **Gepy Live Call** (Gemini 3.8 Live Full-Duplex
     * `BidiGenerateContent` WebSocket) bila `GEMINI_API_KEY` tersedia.
     */
    suspend fun startLiveCallSession(
        onToolCall: suspend (GeminiLiveToolCall) -> Map<String, Any?>
    ): Boolean {
        initialize()
        stopAllSpeechAndTts()
        clearLiveTranscriptBuffer()
        synchronized(callSessionTurns) { callSessionTurns.clear() }

        if (geminiLiveClient.hasApiKey) {
            val memoryContext = buildCombinedLiveUserContext(
                userMemoryManager?.getSystemInstructionContext().orEmpty()
            )

            // Intersep pemanggilan tool memori dan 'end_live_call'
            val wrappedToolCall: suspend (GeminiLiveToolCall) -> Map<String, Any?> = { call ->
                when (call.name) {
                    "save_user_memory" -> {
                        val fact = (call.args["fact"] as? String)?.trim().orEmpty()
                        val category = (call.args["category"] as? String)?.trim().orEmpty()
                        val subject = (call.args["subject"] as? String)?.trim().orEmpty()
                        val predicate = (call.args["predicate"] as? String)?.trim().orEmpty()
                        val objectValue = (call.args["object_value"] as? String)?.trim().orEmpty()
                        val validHours = when (val raw = call.args["valid_hours"]) {
                            is Number -> raw.toInt()
                            is String -> raw.trim().toIntOrNull()
                            else -> null
                        }?.takeIf { it > 0 }
                        if (fact.isNotBlank()) {
                            userMemoryManager?.recordMemory(
                                category = category,
                                fact = fact,
                                subject = subject,
                                predicate = predicate,
                                objectValue = objectValue,
                                validHours = validHours
                            )
                            userMemoryManager?.inspectTranscriptForProfile(fact)
                            syncMemoryContextToLiveSession(
                                userMemoryManager?.getSystemInstructionContext().orEmpty()
                            )
                        }
                        val expiryNote = if (validHours != null) " (berlaku $validHours jam)" else ""
                        mapOf("status" to "success", "message" to "Memori berhasil disimpan ke graf temporal lokal$expiryNote.")
                    }
                    "query_memory_history" -> {
                        val topic = (call.args["topic"] as? String)?.trim().orEmpty()
                        val timeline = userMemoryManager?.queryMemoryTimeline(topic)
                            ?: "Memori temporal belum tersedia."
                        val hybridMatches = userMemoryManager?.searchHybridMemories(
                            query = topic,
                            maxResults = 4
                        ).orEmpty()
                        val extraSemantic = hybridMatches
                            .map { it.factText }
                            .filter { fact -> !timeline.contains(fact, ignoreCase = true) }
                        val combinedSummary = if (extraSemantic.isNotEmpty()) {
                            "$timeline Memori semantik terkait: ${extraSemantic.joinToString("; ")}."
                        } else {
                            timeline
                        }
                        mapOf("status" to "success", "summary_id" to combinedSummary)
                    }
                    "update_or_correct_memory" -> {
                        val target = (call.args["target_to_replace"] as? String)?.trim().orEmpty()
                        val correctedFact = (call.args["corrected_fact"] as? String)?.trim().orEmpty()
                        val category = (call.args["category"] as? String)?.trim()?.ifBlank { "general" } ?: "general"
                        if (correctedFact.isNotBlank()) {
                            userMemoryManager?.updateOrCorrectMemory(
                                targetToReplace = target,
                                correctedFact = correctedFact,
                                category = category
                            )
                            userMemoryManager?.inspectTranscriptForProfile(correctedFact)
                            syncMemoryContextToLiveSession(
                                userMemoryManager?.getSystemInstructionContext().orEmpty()
                            )
                        }
                        mapOf("status" to "success", "message" to "Memori berhasil diperbarui dengan fakta yang benar.")
                    }
                    "delete_user_memory" -> {
                        val target = (call.args["target"] as? String)?.trim().orEmpty()
                        if (target.isNotBlank()) {
                            userMemoryManager?.deleteMemory(target)
                            syncMemoryContextToLiveSession(
                                userMemoryManager?.getSystemInstructionContext().orEmpty()
                            )
                        }
                        mapOf("status" to "success", "message" to "Memori berhasil dihapus.")
                    }
                    "end_live_call" -> {
                        val farewell = (call.args["farewell"] as? String)?.trim().orEmpty()
                            .ifBlank { "Siap, sampai ketemu lagi ya!" }
                        GepyLog.i("VoiceEngine", "Menerima panggilan tool end_live_call: $farewell")
                        scope.launch {
                            stopLiveCallSession("Panggilan ditutup oleh Gemini Live.")
                            speakSafe(farewell)
                        }
                        mapOf("status" to "success", "message" to "Panggilan telepon berhasil ditutup.")
                    }
                    else -> onToolCall(call)
                }
            }

            val connected = geminiLiveClient.connectSession(
                userContext = memoryContext,
                onToolCallRequested = wrappedToolCall
            )
            if (connected) {
                val gotCallFocus = audioFocusCoordinator.acquireCallFocus { focusEvent ->
                    if (focusEvent == VoiceAudioFocusEvent.LOSS ||
                        focusEvent == VoiceAudioFocusEvent.LOSS_TRANSIENT
                    ) {
                        GepyLog.w("VoiceEngine", "Fokus audio Live Call diambil alih ($focusEvent), menutup sesi panggilan.")
                        scope.launch {
                            stopLiveCallSession("Fokus audio diambil alih oleh panggilan sistem atau aplikasi lain.")
                        }
                    }
                }
                if (!gotCallFocus) {
                    GepyLog.w("VoiceEngine", "Sistem menunda/menolak AudioFocus panggilan; tetap mengaktifkan rute suara.")
                }

                // Aktifkan MODE_IN_COMMUNICATION + Speakerphone AEC + volume aman pada STREAM_VOICE_CALL
                liveCallAudioRouter?.enterLiveCallRouting()

                // Pre-warm AudioTrack agar siap menerima aliran audio instan bebas jitter
                liveAudioTrackPlayer.prewarm()

                if (context != null && hasRecordPermission()) {
                    micCaptureController.start(enableHardwareDsp = true) { pcmBytes ->
                        geminiLiveClient.processAndSendMicPcm16k(pcmBytes)
                    }
                }

                // Kirim pemicu sapaan awal agar asisten langsung menyapa pengguna secara ramah & santai
                geminiLiveClient.sendInitialGreetingPrompt()

                pushHudState(
                    isVisible = true,
                    mode = OverlayVisualMode.LISTENING,
                    headline = "Gepy Live",
                    subtitle = SovereignNaturalSpeech.liveCallConnected(speechContextProvider()),
                    activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                    statusPillLabel = "Mendengarkan...",
                    showStopButton = true
                )
                return true
            }
        }

        return false
    }

    /**
     * Menggabungkan memori jangka panjang [baseMemoryContext] dengan konteks obrolan sesi aktif
     * dari Tab Chat sehingga Gemini Live langsung nyambung dengan topik sebelum panggilan.
     */
    internal fun buildCombinedLiveUserContext(baseMemoryContext: String): String {
        val activeChatContext = sharedConversationContextProvider?.invoke().orEmpty().trim()
        return listOf(baseMemoryContext.trim(), activeChatContext)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    /**
     * Menyinkronkan pesan Chat yang diketik saat panggilan Live sedang terhubung secara diam-diam
     * tanpa mengganggu audio percakapan.
     */
    fun syncActiveChatTurnToLiveSession(role: String, text: String): Boolean {
        if (!geminiLiveClient.isConnected) return false
        return geminiLiveClient.sendSilentChatTurnSync(role, text)
    }

    /**
     * Menyinkronkan pembaruan memori pengguna secara instan (< 10 ms) ke sesi aktif Gemini Live
     * melalui *Silent Context Anchor Pin* (BidiGenerateContentClientContent, `turnComplete: false`)
     * sehingga asisten langsung mengingat fakta baru pada giliran berikutnya tanpa jeda 8 giliran.
     */
    fun syncMemoryContextToLiveSession(userContext: String) {
        val combined = buildCombinedLiveUserContext(userContext)
        geminiLiveClient.updateUserMemoryContext(combined)
        if (geminiLiveClient.isConnected && combined.isNotEmpty()) {
            val compact = geminiLiveClient.compressAnchorContextTrc(combined, maxChars = 2000)
            if (compact.isNotEmpty()) {
                geminiLiveClient.sendSilentContextAnchorPin(compact)
                GepyLog.i("VoiceEngine", "Silent memory anchor pin berhasil disuntikkan ke sesi Gemini Live (${compact.length} karakter).")
            }
        }
    }

    /** Menghentikan sesi **Gepy Live Call** dan kembali ke kapsul siaga. */
    suspend fun stopLiveCallSession(reason: String = SovereignNaturalSpeech.liveCallEnded(speechContextProvider())) {
        val turnsSnapshot = synchronized(callSessionTurns) {
            val copy = callSessionTurns.toList()
            callSessionTurns.clear()
            copy
        }
        clearLiveTranscriptBuffer()
        geminiLiveClient.disconnect()
        stopAllSpeechAndTts()
        liveAudioTrackPlayer.release()
        audioFocusCoordinator.abandonCallFocus()
        liveCallAudioRouter?.exitLiveCallRouting()
        showIdlePill()
        _bargeInInterruptFlow.tryEmit("LIVE_CALL_HANGUP")
        onLiveCallTerminated?.invoke(reason)

        // Sintesis Kognitif Pasca-Panggilan: bersihkan serpihan derau & petakan memori penting
        if (turnsSnapshot.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    userMemoryManager?.synthesizePostCallMemories(turnsSnapshot)
                } catch (t: Throwable) {
                    GepyLog.w("VoiceEngine", "Sintesis memori pasca-panggilan dilewati: ${t.message}")
                }
            }
        }
    }

    /** Dipanggil langsung oleh `OverlayHudComposable` ketika kapsul mikrofon diketuk (Ketuk Singkat / Single Tap). */
    fun onOverlayMicTap() {
        GepyLog.i("VoiceEngine", "Overlay HUD diketuk 1x (Single Tap): membuka mode LISTENING")
        hapticManager?.triggerFeedback(com.teknologigawai.gepy.core.accessibility.GepyFeedbackPattern.LISTENING_STARTED)
        if (isLiveCallActive) {
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.LISTENING,
                headline = "Gepy Live",
                subtitle = "Mendengarkan suaramu...",
                activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                statusPillLabel = "Mendengarkan...",
                showStopButton = true
            )
            return
        }
        scope.launch { startListening() }
    }

    /** Dipanggil langsung oleh `OverlayHudComposable` ketika kapsul mikrofon ditekan lama (Long-Press ~600 ms). */
    fun onOverlayMicLongPress() {
        GepyLog.i("VoiceEngine", "Overlay HUD ditekan lama (Long-Press): memicu Gepy Live Call")
        hapticManager?.triggerFeedback(com.teknologigawai.gepy.core.accessibility.GepyFeedbackPattern.STEP_SUCCESS)
        val wasLive = isLiveCallActive
        scope.launch {
            if (!wasLive) {
                pushHudState(
                    isVisible = true,
                    mode = OverlayVisualMode.LISTENING,
                    headline = "Menyambung Gepy Live",
                    subtitle = SovereignNaturalSpeech.liveCallConnecting(speechContextProvider()),
                    activityKind = OverlayHudActivityKind.CONNECTING,
                    statusPillLabel = "Menyambung...",
                    showStopButton = true
                )
            }
            val suspendToggle = onLiveCallToggleSuspendRequested
            if (suspendToggle != null) {
                suspendToggle.invoke()
            } else {
                onLiveCallToggleRequested?.invoke()
            }
            if (wasLive) {
                speakSafe(SovereignNaturalSpeech.liveCallEnded(speechContextProvider()))
            }
        }
    }

    internal fun isLiveAssistantCurrentlySpeaking(): Boolean {
        val state = _hudStateFlow.value
        return isLiveCallActive && (
            liveAudioTrackPlayer.isWindowActive(graceMs = 0L) ||
                hasPendingLiveTranscript() ||
                state.activityKind == OverlayHudActivityKind.LIVE_SPEAKING
            )
    }

    /**
     * Dipanggil langsung oleh `OverlayHudComposable` ketika pengguna menekan tombol Stop (`■`) / Batal:
     * - Saat STT cepat: membatalkan perekaman dan kembali ke mode siaga.
     * - Saat Live Call & Gepy sedang berbicara (Opsi A): menginterupsi/membungkam giliran bicara saat ini saja.
     * - Saat Live Call & tidak sedang berbicara: menutup sesi Live Call.
     */
    fun onOverlayCancelListen() {
        if (isLiveCallActive) {
            if (isLiveAssistantCurrentlySpeaking()) {
                _bargeInInterruptFlow.tryEmit("LIVE_TURN_STOP")
                liveAudioTrackPlayer.clearEstimatedEnd()
                clearLiveTranscriptBuffer()
                turnHadLiveAudio = false
                stopAudioTrackPlayback()
                stopTtsOnly()
                pushHudState(
                    isVisible = true,
                    mode = OverlayVisualMode.LISTENING,
                    headline = "Gepy Live",
                    subtitle = "Giliran bicara dihentikan — silakan lanjut bicara.",
                    activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                    statusPillLabel = "Mendengarkan...",
                    showStopButton = true
                )
            } else {
                _bargeInInterruptFlow.tryEmit("OVERLAY_CANCEL")
                scope.launch {
                    stopLiveCallSession("Panggilan dibatalkan melalui antarmuka.")
                }
            }
            return
        }
        _bargeInInterruptFlow.tryEmit("OVERLAY_CANCEL")
        scope.launch {
            stopAllSpeechAndTts()
            showIdlePill()
        }
    }

    /** Dipanggil langsung oleh `OverlayHudComposable` saat pengguna memilih kandidat disambiguasi. */
    fun onDisambiguationChosen(choice: String) {
        val clean = choice.trim()
        val deferred = disambiguationDeferred
        if (clean.isNotEmpty() && deferred != null && deferred.complete(clean)) {
            if (disambiguationDeferred === deferred) {
                disambiguationDeferred = null
            }
            stopAllSpeechAndTts()
        }
    }

    /** Dipanggil langsung oleh `OverlayHudComposable` saat pengguna menekan [Ya, Eksekusi] atau [Batalkan]. */
    fun onHitlDecision(approved: Boolean) {
        val deferred = hitlDeferred
        if (deferred != null && deferred.complete(approved)) {
            if (hitlDeferred === deferred) {
                hitlDeferred = null
            }
            stopAllSpeechAndTts()
        }
    }

    /** Dipanggil langsung oleh `OverlayHudComposable` ketika pengguna mengetuk tombol aksi bisikan proaktif. */
    fun onOverlayWhisperAction(payload: String) {
        whisperDismissJob?.cancel()
        val clean = payload.trim()
        GepyLog.i("VoiceEngine", "Overlay Whisper Action diketuk: '$clean'")
        hapticManager?.triggerFeedback(com.teknologigawai.gepy.core.accessibility.GepyFeedbackPattern.STEP_SUCCESS)
        scope.launch {
            showIdlePill()
            if (clean.isNotEmpty()) {
                _voiceCommandFlow.emit(clean)
            }
        }
    }

    /** Dipanggil saat pengguna menutup kartu bisikan proaktif (tombol silang X). */
    fun onOverlayWhisperDismiss() {
        whisperDismissJob?.cancel()
        GepyLog.d("VoiceEngine", "Overlay Whisper ditutup pengguna.")
        scope.launch {
            showIdlePill()
        }
    }

    private fun handleGeminiLiveEvent(event: GeminiLiveParsedEvent) {
        val isWaitingModal = (hitlDeferred?.isActive == true) || (disambiguationDeferred?.isActive == true)
        if (event.interrupted) {
            liveAudioTrackPlayer.clearEstimatedEnd()
            clearLiveTranscriptBuffer()
            turnHadLiveAudio = false
            stopAudioTrackPlayback()
            if (isLiveCallActive && !isWaitingModal) {
                pushHudState(
                    isVisible = true,
                    mode = OverlayVisualMode.LISTENING,
                    headline = "Gepy Live",
                    subtitle = "Mendengarkan suaramu...",
                    activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                    statusPillLabel = "Mendengarkan...",
                    showStopButton = true
                )
            }
            return
        }

        if (event.audioChunksPcm24k.isNotEmpty() &&
            geminiLiveClient.directAudioChunkCallback == null) {
            for (chunk in event.audioChunksPcm24k) {
                writePcm24kChunk(chunk)
            }
        }

        val textToCheck = (event.finalInputTranscript ?: event.interimInputTranscript)?.lowercase()?.trim().orEmpty()
        if (textToCheck.isNotBlank()) {
            // 1. Jika sedang menunggu persetujuan keamanan HITL di tengah Live Call, tangkap jawaban suara pengguna
            val activeHitl = hitlDeferred
            if (activeHitl != null && activeHitl.isActive) {
                when {
                    REJECT_WORDS.any { word ->
                        Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(textToCheck)
                    } -> {
                        if (activeHitl.complete(false) && hitlDeferred === activeHitl) {
                            hitlDeferred = null
                        }
                        return
                    }
                    APPROVE_WORDS.any { word ->
                        Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(textToCheck)
                    } -> {
                        if (activeHitl.complete(true) && hitlDeferred === activeHitl) {
                            hitlDeferred = null
                        }
                        return
                    }
                }
            }

            // 2. Jika sedang menunggu pemilihan kandidat Disambiguasi di tengah Live Call, tangkap pilihan suara pengguna
            val activeDisambig = disambiguationDeferred
            if (activeDisambig != null && activeDisambig.isActive) {
                val currentOptions = _hudStateFlow.value.options
                val matched = currentOptions.firstOrNull { candidate ->
                    val cleanCand = candidate.lowercase().trim()
                    cleanCand.isNotEmpty() && (cleanCand.contains(textToCheck) || textToCheck.contains(cleanCand))
                }
                if (matched != null) {
                    if (activeDisambig.complete(matched) && disambiguationDeferred === activeDisambig) {
                        disambiguationDeferred = null
                    }
                    return
                }

                // Jika pengguna membatalkan atau beralih ke instruksi lain, batalkan penantian disambiguasi
                val isCancelOrNewCommand = REJECT_WORDS.any { textToCheck.contains(it) } ||
                    textToCheck.startsWith("buka ") || textToCheck.startsWith("pindah ke ") ||
                    textToCheck.contains("gajadi") || textToCheck.contains("nggak jadi")
                if (isCancelOrNewCommand) {
                    if (activeDisambig.complete("") && disambiguationDeferred === activeDisambig) {
                        disambiguationDeferred = null
                    }
                    return
                }
            }

            // 3. Deteksi kata kunci tutup telepon (kecuali pengguna sedang mendikte isi pesan WA/DM)
            val isDictatingMessage = MESSAGE_DICTATION_EXCLUSION_REGEX.containsMatchIn(textToCheck)
            val isHangup = !isDictatingMessage && HANGUP_KEYWORDS.any { textToCheck.contains(it) }
            if (isHangup) {
                GepyLog.i("VoiceEngine", "Perintah suara penutup telepon terdeteksi: $textToCheck")
                scope.launch {
                    val bye = SovereignNaturalSpeech.liveCallEnded(speechContextProvider())
                    stopLiveCallSession("Panggilan ditutup oleh suara pengguna.")
                    speakSafe(bye)
                }
                return
            }
        }

        val finalIn = event.finalInputTranscript
        if (!finalIn.isNullOrBlank()) {
            synchronized(callSessionTurns) {
                callSessionTurns.add(com.teknologigawai.gepy.core.memory.CallTranscriptTurn("user", finalIn))
            }
            userMemoryManager?.inspectTranscriptForProfile(finalIn)
            onLiveTurnRecorded?.invoke("user", finalIn)
        }

        val userInterim = event.interimInputTranscript ?: event.finalInputTranscript
        if (!userInterim.isNullOrEmpty() && !isWaitingModal) {
            onInterimSpeechListener?.invoke(userInterim)
            pushHudState(
                isVisible = true,
                mode = OverlayVisualMode.LISTENING,
                headline = "Kamu",
                subtitle = userInterim,
                activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                statusPillLabel = "Mendengarkan...",
                showStopButton = true
            )
        }

        val incomingTokens = when {
            event.modelTextParts.isNotEmpty() -> event.modelTextParts.joinToString("")
            !event.outputTranscript.isNullOrEmpty() -> event.outputTranscript
            else -> null
        }
        if (!incomingTokens.isNullOrEmpty()) {
            val transcriptSnapshot = appendLiveTranscriptTokens(incomingTokens)
            if (!isWaitingModal) {
                pushHudState(
                    isVisible = true,
                    mode = OverlayVisualMode.LISTENING,
                    headline = "Gepy Berbicara",
                    subtitle = transcriptSnapshot,
                    activityKind = OverlayHudActivityKind.LIVE_SPEAKING,
                    statusPillLabel = "Gepy Berbicara...",
                    showStopButton = true
                )
            }
        }

        if (event.turnComplete) {
            val hadAudio = turnHadLiveAudio
            val spokenReply = drainLiveTranscriptBuffer()
            if (spokenReply.isNotEmpty()) {
                synchronized(callSessionTurns) {
                    callSessionTurns.add(com.teknologigawai.gepy.core.memory.CallTranscriptTurn("assistant", spokenReply))
                }
                onLiveTurnRecorded?.invoke("assistant", spokenReply)
            }
            turnHadLiveAudio = false
            if (isLiveCallActive && !isWaitingModal) {
                pushHudState(
                    isVisible = true,
                    mode = OverlayVisualMode.LISTENING,
                    headline = "Gepy Live",
                    subtitle = spokenReply.ifBlank { "Mendengarkan suaramu..." },
                    activityKind = OverlayHudActivityKind.LIVE_LISTENING,
                    statusPillLabel = "Mendengarkan...",
                    showStopButton = true
                )
            }
            if (!geminiLiveClient.isConnected && !hadAudio && spokenReply.isNotEmpty()) {
                speakSafe(spokenReply)
            }
        }
    }

    private fun abandonListeningFocusSafe() = speechFocusManager.abandonListeningFocusSafe()

    private fun updateMediaPlayerDuckVolume(ducked: Boolean) {
        ttsPlaybackController.updateMediaPlayerDuckVolume(ducked)
    }

    /**
     * Menguji suara ElevenLabs menggunakan konfigurasi **draf yang belum disimpan** (tanpa memengaruhi
     * [VoiceAudioCacheManager] dan tanpa memicu *circuit breaker* sesi utama).
     */
    suspend fun previewUnsavedElevenLabsVoice(
        draftConfig: ElevenLabsRuntimeConfig,
        sampleText: String = ""
    ): Result<String> = ttsPlaybackController.previewUnsavedElevenLabsVoice(draftConfig, sampleText)

    private fun speakSafe(message: String) = ttsPlaybackController.speakSafe(message)

    private suspend fun speakSafeAndAwaitCompletion(
        message: String,
        critical: Boolean = false,
        timeoutMs: Long = criticalSpeechAwaitTimeoutMs
    ) = ttsPlaybackController.speakSafeAndAwaitCompletion(message, critical, timeoutMs)

    private fun stopTtsOnly() = ttsPlaybackController.stopTtsOnly()

    fun stopAllSpeechAndTts() {
        speechRecognitionRunner.stopSession()
        stopAudioTrackPlayback()
        stopTtsOnly()
        abandonListeningFocusSafe()
        // Jangan matikan mikrofon apabila sesi panggilan dua arah Live Call sedang aktif
        if (!geminiLiveClient.isConnected) {
            micCaptureController.stop()
        }
    }

    private fun hasRecordPermission(): Boolean = speechRecognitionRunner.hasRecordPermission()

    private fun listenForShortVoiceReply(onSpoken: (String) -> Unit) =
        speechRecognitionRunner.listenForShortVoiceReply(onSpoken)

    override suspend fun showProactiveWhisper(
        title: String,
        subtitle: String,
        actionLabel: String,
        goalPayload: String,
        durationMs: Long
    ) {
        // Jangan timpa bila user sedang berbicara pada sesi LISTENING atau Live Call
        if (_hudStateFlow.value.mode == OverlayVisualMode.LISTENING || _hudStateFlow.value.isLiveCallActive) {
            return
        }

        whisperDismissJob?.cancel()
        pushHudState(
            isVisible = true,
            mode = OverlayVisualMode.PROACTIVE_WHISPER,
            headline = title,
            subtitle = subtitle,
            whisperActionLabel = actionLabel,
            whisperGoalPayload = goalPayload
        )

        whisperDismissJob = scope.launch {
            delay(durationMs)
            if (_hudStateFlow.value.mode == OverlayVisualMode.PROACTIVE_WHISPER) {
                showIdlePill()
            }
        }
    }

    private fun pushHudState(
        isVisible: Boolean,
        mode: OverlayVisualMode,
        headline: String,
        subtitle: String,
        options: List<String> = emptyList(),
        whisperActionLabel: String = "",
        whisperGoalPayload: String = "",
        activityKind: OverlayHudActivityKind = inferActivityKind(mode, isLiveCallActive, headline),
        statusPillLabel: String = activityKind.defaultPillLabel,
        showStopButton: Boolean = mode == OverlayVisualMode.LISTENING ||
            (isLiveCallActive && mode == OverlayVisualMode.THINKING)
    ) {
        _hudStateFlow.value = OverlayHudState(
            isVisible = isVisible,
            mode = mode,
            headline = VoiceClauseChunker.stripAudioTagsForDisplay(headline),
            subtitle = VoiceClauseChunker.stripAudioTagsForDisplay(subtitle),
            options = options,
            isLiveCallActive = this@VoiceAudioEngine.isLiveCallActive,
            whisperActionLabel = whisperActionLabel,
            whisperGoalPayload = whisperGoalPayload,
            activityKind = activityKind,
            statusPillLabel = statusPillLabel.ifBlank { activityKind.defaultPillLabel },
            showStopButton = showStopButton
        )
    }

    private fun inferActivityKind(
        mode: OverlayVisualMode,
        liveActive: Boolean,
        headline: String
    ): OverlayHudActivityKind {
        return when (mode) {
            OverlayVisualMode.IDLE -> OverlayHudActivityKind.IDLE
            OverlayVisualMode.LISTENING -> when {
                headline.contains("Menyambung", ignoreCase = true) -> OverlayHudActivityKind.CONNECTING
                liveActive -> OverlayHudActivityKind.LIVE_LISTENING
                else -> OverlayHudActivityKind.LISTENING
            }
            OverlayVisualMode.THINKING -> when {
                liveActive && (headline.contains("Berbicara", ignoreCase = true) ||
                    headline.contains("Menjawab", ignoreCase = true)) -> OverlayHudActivityKind.LIVE_SPEAKING
                isExecutingHeadline(headline) -> OverlayHudActivityKind.EXECUTING
                else -> OverlayHudActivityKind.THINKING
            }
            else -> OverlayHudActivityKind.IDLE
        }
    }

    private fun isExecutingHeadline(headline: String): Boolean {
        val lower = headline.lowercase()
        return lower.contains("membuka") ||
            lower.contains("mengetuk") ||
            lower.contains("menekan") ||
            lower.contains("mengetik") ||
            lower.contains("menggulir") ||
            lower.contains("menggeser") ||
            lower.contains("kembali") ||
            lower.contains("menunggu") ||
            lower.contains("menyalin") ||
            lower.contains("menempel") ||
            lower.contains("menjalankan") ||
            lower.contains("mencari") ||
            lower.contains("verifikasi") ||
            lower.contains("mencegat") ||
            lower.contains("menyelesaikan")
    }

    override fun dispose() {
        stopAllSpeechAndTts()
        geminiLiveClient.disconnect()
        liveAudioTrackPlayer.release()
        audioFocusCoordinator.abandonCallFocus()
        liveCallAudioRouter?.exitLiveCallRouting()
        speechFocusManager.abandonListeningFocusSafe()
        speechFocusManager.forceAbandonSpeechFocus()
        ttsPlaybackController.shutdown()
        speechRecognitionRunner.destroy()
        liveAudioTrackPlayer.shutdown()
        runCatching { scope.cancel() }
    }
}
