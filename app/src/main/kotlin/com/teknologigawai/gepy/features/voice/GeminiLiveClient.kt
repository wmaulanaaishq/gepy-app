package com.teknologigawai.gepy.features.voice

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.config.GeminiKeyPurpose
import com.teknologigawai.gepy.core.logging.GepyLog
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * Klien **Full-Duplex Multimodal Live Call** berbasis protokol resmi
 * Google Gemini Live API (`BidiGenerateContent` WebSocket, `models/gemini-3.8-live`).
 *
 * Mendukung:
 * - Streaming audio dua arah dengan deteksi interupsi instan (*Barge-In*).
 * - Streaming bingkai layar JPEG (*Set-of-Marks* visual context) melalui `realtimeInput.video`.
 * - Pemanggilan fungsi asinkron (`behavior: "NON_BLOCKING"`) baik untuk otomasi UI Android
 *   (`execute_android_goal`, `read_current_screen`, `cancel_android_goal`) maupun akses
 *   cepat OS Android (`get_device_and_time_status`, `search_phone_contacts`,
 *   `get_recent_notifications`, `get_current_location`, `set_system_alarm`).
 */
open class GeminiLiveClient(
    apiKey: String = EnvConfig.defaultGeminiApiKey,
    val model: String = EnvConfig.geminiLiveModel,
    var voiceName: String = "Aoede",
    val localVad: LocalPcmVad = LocalPcmVad(),
    secondaryApiKey: String = ""
) {
    companion object {
        /** Daftar urutan prioritas model GA resmi Gemini Live Audio (`BidiGenerateContent`) sesuai `docs/MODELS.md`. */
        val PREFERRED_LIVE_MODELS: List<String> = listOf(
            "models/gemini-3.8-live",
            "models/gemini-3.8-live-extended-thinking"
        )

        fun sanitizeGeminiKey(raw: String): String {
            var cleaned = raw.trim().trim('"', '\'', '`')
            if (cleaned.startsWith("Bearer ", ignoreCase = true)) {
                cleaned = cleaned.substring(7).trim()
            }
            if (cleaned.contains('=')) {
                val afterEq = cleaned.substringAfter('=').trim().trim('"', '\'')
                if (afterEq.isNotEmpty()) {
                    cleaned = afterEq
                }
            }
            return cleaned.replace("\\s+".toRegex(), "")
        }

        /**
         * Menguatkan amplitudo sampel audio PCM 16-bit little-endian secara linier
         * dengan pengaman saturasi (*soft-knee saturation limiter*) agar tidak terjadi clipping atau distorsi.
         */
        fun boostPcm16Audio(pcmBytes: ByteArray, gainFactor: Float): ByteArray {
            if (gainFactor <= 1.0f || pcmBytes.isEmpty()) return pcmBytes
            val output = pcmBytes.copyOf()
            return boostPcm16AudioInPlace(output, gainFactor)
        }

        /**
         * Menguatkan amplitudo sampel audio PCM 16-bit little-endian langsung di tempat (*zero-allocation*)
         * untuk buffer privat yang tidak dibagikan ke komponen lain.
         */
        fun boostPcm16AudioInPlace(pcmBytes: ByteArray, gainFactor: Float): ByteArray {
            if (gainFactor <= 1.0f || pcmBytes.isEmpty()) return pcmBytes
            val sampleCount = pcmBytes.size / 2
            for (i in 0 until sampleCount) {
                val low = pcmBytes[i * 2].toInt() and 0xFF
                val high = pcmBytes[i * 2 + 1].toInt()
                val sample = (high shl 8) or low
                val amplified = (sample * gainFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcmBytes[i * 2] = (amplified and 0xFF).toByte()
                pcmBytes[i * 2 + 1] = ((amplified shr 8) and 0xFF).toByte()
            }
            return pcmBytes
        }
    }

    /** Penguatan sinyal mikrofon digital (*Digital Mic Pre-Amp*) untuk sensitivitas tinggi. */
    var micPreAmpGain: Float = 2.0f

    private var primaryConfiguredApiKey: String = sanitizeGeminiKey(apiKey)
    private var currentApiKey: String = primaryConfiguredApiKey
    private var secondaryApiKey: String = sanitizeGeminiKey(secondaryApiKey)
    private var resumptionHandle: String? = null
    private var resumptionHandleTimestampMs: Long = 0L

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var setupDeferred: CompletableDeferred<Boolean>? = null
    private var reconnectJob: Job? = null
    private var activeToolCallback: (suspend (GeminiLiveToolCall) -> Map<String, Any?>)? = null
    private val modelDiscovery = GeminiLiveModelDiscovery()

    private val micBatchLock = Any()
    private val micBatchBuffer = ByteArrayOutputStream(3840)
    private var micBatchFrameCount: Int = 0
    private val preRollFrames = java.util.ArrayDeque<ByteArray>(4)
    private var reframeDeepSilenceBatchCounter: Int = 0
    private var wasGatedInDeepSilence: Boolean = false

    /** Jumlah batch PCM hening yang berhasil dihemat oleh *ReFrame Silence Gate* (`arXiv:2611.01835v1`). */
    @Volatile
    var reframeSavedBatchesCount: Int = 0
        private set

    /** Interval giliran untuk penyegaran *Initialization-Anchor Pinning* (`arXiv:2610.32349v1`). */
    var anchorPinIntervalTurns: Int = 8

    @Volatile
    var completedTurnCount: Int = 0
        private set

    @Volatile
    var anchorPinRefreshCount: Int = 0
        private set

    @Volatile
    private var sessionDesiredActive: Boolean = false

    @Volatile
    private var connected: Boolean = false

    @Volatile
    var activeModelId: String = model
        private set

    @Volatile
    var lastFailureReason: String? = null
        private set

    /** Callback jalur cepat PCM 24kHz tanpa melewati antrean SharedFlow agar bebas drop/lag. */
    @Volatile
    var directAudioChunkCallback: ((ByteArray) -> Unit)? = null

    /** Penyedia status apakah AudioTrack asisten sedang aktif berbunyi (untuk Echo-Gate VAD). */
    @Volatile
    var isPlaybackActiveProvider: (() -> Boolean)? = null

    /** Penyedia direktif kepribadian berdaulat (*Sovereign Persona: Gepy Taktis <-> Mentor Tegas*) & konteks fisik. */
    @Volatile
    var sovereignPersonaDirectiveProvider: (() -> String)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStateFlow = MutableStateFlow(LiveConnectionState.CLOSED)
    val connectionStateFlow: StateFlow<LiveConnectionState> = _connectionStateFlow.asStateFlow()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _eventsFlow = MutableSharedFlow<GeminiLiveParsedEvent>(extraBufferCapacity = 64)
    val eventsFlow: SharedFlow<GeminiLiveParsedEvent> = _eventsFlow.asSharedFlow()

    internal fun emitParsedEventForTest(event: GeminiLiveParsedEvent): Boolean {
        return _eventsFlow.tryEmit(event)
    }

    internal fun emitConnectionStateForTest(state: LiveConnectionState) {
        _connectionStateFlow.value = state
    }

    internal val eventSubscriptionCountForTest: Int
        get() = _eventsFlow.subscriptionCount.value

    private val _bargeInFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val bargeInFlow: SharedFlow<Unit> = _bargeInFlow.asSharedFlow()

    open val hasApiKey: Boolean
        get() = GeminiKeyPool.orderedKeys(
            purpose = GeminiKeyPurpose.LIVE_VOICE,
            primaryKey = primaryConfiguredApiKey,
            secondaryKey = secondaryApiKey
        ).isNotEmpty()

    open val isConnected: Boolean
        get() = connected

    fun updateApiKey(newKey: String) {
        primaryConfiguredApiKey = sanitizeGeminiKey(newKey)
        currentApiKey = primaryConfiguredApiKey
    }

    open fun updateApiKeys(primaryKey: String, secondaryKey: String) {
        primaryConfiguredApiKey = sanitizeGeminiKey(primaryKey)
        secondaryApiKey = sanitizeGeminiKey(secondaryKey)
        currentApiKey = primaryConfiguredApiKey.ifEmpty { this.secondaryApiKey }
    }

    /** Membangun URL WebSocket `BidiGenerateContent` lengkap dengan kunci API. */
    fun buildWebSocketUrl(): String {
        return "${EnvConfig.geminiLiveWsUrl}?key=$currentApiKey"
    }

    @Volatile
    private var currentUserMemoryContext: String = ""

    /** Memperbarui konteks memori pengguna agar sesi auto-reconnect tetap menyertakan fakta terbaru. */
    fun updateUserMemoryContext(userContext: String) {
        currentUserMemoryContext = userContext.trim()
    }

    /** Membangun payload JSON `BidiGenerateContentSetup` awal sesuai spesifikasi Gemini 3.8 Live. */
    /** Membangun payload JSON `BidiGenerateContentSetup` awal sesuai spesifikasi Gemini 3.8 Live. */
    fun buildSetupMessageJson(
        targetModel: String = model,
        includeNonBlocking: Boolean = true,
        includeLanguageCode: Boolean = true,
        includeAudioTranscription: Boolean = true,
        includeSessionManagement: Boolean = true,
        userContext: String = ""
    ): JSONObject {
        val isHandleValid = !resumptionHandle.isNullOrBlank() &&
            (System.currentTimeMillis() - resumptionHandleTimestampMs < 8 * 60_000L)
        if (!isHandleValid && resumptionHandle != null) {
            resumptionHandle = null
            resumptionHandleTimestampMs = 0L
        }
        return GeminiLiveSetupPayloadBuilder.buildSetupMessageJson(
            targetModel = targetModel,
            voiceName = voiceName,
            includeNonBlocking = includeNonBlocking,
            includeLanguageCode = includeLanguageCode,
            includeAudioTranscription = includeAudioTranscription,
            includeSessionManagement = includeSessionManagement,
            userContext = userContext,
            sovereignDirective = sovereignPersonaDirectiveProvider?.invoke().orEmpty(),
            activeResumptionHandle = resumptionHandle
        )
    }

    internal fun buildCandidateModelOrder(discovered: List<String>): List<String> =
        modelDiscovery.buildCandidateModelOrder(
            primaryModel = model,
            preferredLiveModels = PREFERRED_LIVE_MODELS,
            discovered = discovered
        )

    /** Membuka koneksi WebSocket `BidiGenerateContent` dan mengaktifkan *Session Supervisor*. */
    open suspend fun connectSession(
        userContext: String = "",
        onToolCallRequested: (suspend (GeminiLiveToolCall) -> Map<String, Any?>)? = null
    ): Boolean {
        currentUserMemoryContext = userContext.trim()
        if (!hasApiKey) {
            lastFailureReason = "GEMINI_API_KEY kosong. Silakan isi Gemini API Key di Pengaturan BYOK."
            _connectionStateFlow.value = LiveConnectionState.FAILED
            return false
        }
        reconnectJob?.cancel()
        reconnectJob = null
        resumptionHandle = null
        resumptionHandleTimestampMs = 0L
        completedTurnCount = 0
        anchorPinRefreshCount = 0
        reframeSavedBatchesCount = 0
        closeSocketInternal()
        activeToolCallback = onToolCallRequested
        sessionDesiredActive = true
        lastFailureReason = null
        _connectionStateFlow.value = LiveConnectionState.CONNECTING

        val ok = establishWebSocketFromCandidates(onToolCallRequested = onToolCallRequested)
        if (!ok) {
            sessionDesiredActive = false
            _connectionStateFlow.value = LiveConnectionState.FAILED
        }
        return ok
    }

    internal fun orderedLiveApiKeys(nowMs: Long = System.currentTimeMillis()): List<String> {
        return GeminiKeyPool.orderedKeys(
            purpose = GeminiKeyPurpose.LIVE_VOICE,
            primaryKey = primaryConfiguredApiKey,
            secondaryKey = secondaryApiKey,
            nowMs = nowMs
        )
    }

    private fun isQuotaOrAuthErrorReason(reason: String?): Boolean {
        val msg = reason.orEmpty()
        if (msg.isBlank()) return false
        return msg.contains("HTTP 429", ignoreCase = true) ||
            msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            msg.contains("quota", ignoreCase = true) ||
            msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("HTTP 401", ignoreCase = true) ||
            msg.contains("HTTP 403", ignoreCase = true) ||
            msg.contains("API key", ignoreCase = true)
    }

    private suspend fun establishWebSocketFromCandidates(
        onToolCallRequested: (suspend (GeminiLiveToolCall) -> Map<String, Any?>)?
    ): Boolean {
        val keyCandidates = orderedLiveApiKeys()
        if (keyCandidates.isEmpty()) {
            lastFailureReason = "GEMINI_API_KEY kosong. Silakan isi Gemini API Key di Pengaturan BYOK."
            return false
        }

        var overallFailureReason: String? = null
        for ((keyIdx, activeKey) in keyCandidates.withIndex()) {
            if (keyIdx > 0) {
                // Handle sesi lama terkait dengan kunci sebelumnya
                resumptionHandle = null
                resumptionHandleTimestampMs = 0L
            }
            currentApiKey = activeKey

            val discovery = modelDiscovery.discoverSupportedBidiModels(apiKey = activeKey)
            if (discovery is LiveModelDiscoveryResult.RateLimited) {
                GeminiKeyPool.markQuotaExhausted(activeKey)
                lastFailureReason = discovery.message
                if (overallFailureReason == null) overallFailureReason = discovery.message
                GepyLog.w("GeminiLive", "${discovery.message} — failover ke kunci cadangan...")
                continue
            }
            if (discovery is LiveModelDiscoveryResult.InvalidApiKey) {
                GeminiKeyPool.markQuotaExhausted(activeKey, GeminiKeyPool.INVALID_KEY_COOLDOWN_MS)
                lastFailureReason = discovery.message
                if (overallFailureReason == null) overallFailureReason = discovery.message
                GepyLog.w("GeminiLive", "${discovery.message} — failover ke kunci cadangan jika ada...")
                continue
            }

            val candidateModels = when (discovery) {
                is LiveModelDiscoveryResult.Success -> buildCandidateModelOrder(discovery.models)
                else -> buildCandidateModelOrder(emptyList())
            }

            var primaryFailureReason: String? = null

            for ((idx, candidateModel) in candidateModels.withIndex()) {
                val supportsNonBlockingByDefault = candidateModel.contains("3.8-live", ignoreCase = true) ||
                    candidateModel.contains("3.1-flash-live", ignoreCase = true) ||
                    candidateModel.contains("native-audio", ignoreCase = true)

                // Percobaan 1: Setup lengkap (termasuk sessionResumption & contextWindowCompression)
                val okPrimary = trySingleWebSocketConnect(
                    candidateModel = candidateModel,
                    includeNonBlocking = supportsNonBlockingByDefault,
                    includeLanguageCode = true,
                    includeAudioTranscription = true,
                    includeSessionManagement = true,
                    onToolCallRequested = onToolCallRequested
                )
                if (okPrimary) {
                    activeModelId = candidateModel.removePrefix("models/")
                    lastFailureReason = null
                    _connectionStateFlow.value = LiveConnectionState.CONNECTED
                    GepyLog.i("GeminiLive", "Terhubung ke Gemini Live model: $activeModelId")
                    return true
                }
                if (idx == 0) {
                    primaryFailureReason = lastFailureReason
                }
                if (isQuotaOrAuthErrorReason(lastFailureReason)) {
                    GeminiKeyPool.markQuotaExhausted(activeKey)
                    if (overallFailureReason == null) overallFailureReason = lastFailureReason
                    GepyLog.w("GeminiLive", "Kuota/akses Gemini Live penuh pada kunci aktif, failover ke kunci cadangan...")
                    break
                }

                // Jika gagal karena resumptionHandle basi/kedaluwarsa, bersihkan handle dan coba sekali lagi
                if (!resumptionHandle.isNullOrBlank()) {
                    GepyLog.w("GeminiLive", "Membersihkan resumptionHandle lama dan mencoba sesi segar...")
                    resumptionHandle = null
                    resumptionHandleTimestampMs = 0L
                    val okFresh = trySingleWebSocketConnect(
                        candidateModel = candidateModel,
                        includeNonBlocking = supportsNonBlockingByDefault,
                        includeLanguageCode = true,
                        includeAudioTranscription = true,
                        includeSessionManagement = true,
                        onToolCallRequested = onToolCallRequested
                    )
                    if (okFresh) {
                        activeModelId = candidateModel.removePrefix("models/")
                        lastFailureReason = null
                        _connectionStateFlow.value = LiveConnectionState.CONNECTED
                        return true
                    }
                    if (idx == 0) {
                        primaryFailureReason = lastFailureReason
                    }
                    if (isQuotaOrAuthErrorReason(lastFailureReason)) {
                        GeminiKeyPool.markQuotaExhausted(activeKey)
                        if (overallFailureReason == null) overallFailureReason = lastFailureReason
                        break
                    }
                }

                // Percobaan kompatibilitas minimal untuk model generasi lama
                val lastErr = lastFailureReason.orEmpty()
                val isModelNotFound = lastErr.contains("not found", ignoreCase = true) ||
                    lastErr.contains("not supported for bidiGenerateContent", ignoreCase = true)
                if (!isModelNotFound) {
                    val okCompat = trySingleWebSocketConnect(
                        candidateModel = candidateModel,
                        includeNonBlocking = false,
                        includeLanguageCode = false,
                        includeAudioTranscription = false,
                        includeSessionManagement = false,
                        onToolCallRequested = onToolCallRequested
                    )
                    if (okCompat) {
                        activeModelId = candidateModel.removePrefix("models/")
                        lastFailureReason = null
                        _connectionStateFlow.value = LiveConnectionState.CONNECTED
                        GepyLog.i("GeminiLive", "Terhubung ke Gemini Live (mode kompatibilitas): $activeModelId")
                        return true
                    }
                    if (isQuotaOrAuthErrorReason(lastFailureReason)) {
                        GeminiKeyPool.markQuotaExhausted(activeKey)
                        if (overallFailureReason == null) overallFailureReason = lastFailureReason
                        break
                    }
                }
            }

            if (overallFailureReason == null && primaryFailureReason != null) {
                overallFailureReason = primaryFailureReason
            }
        }

        if (overallFailureReason != null) {
            lastFailureReason = overallFailureReason
        } else if (lastFailureReason.isNullOrBlank()) {
            lastFailureReason = "Gagal membuka sesi WebSocket Gemini Live. Cek koneksi internet dan kuota Google AI Studio dulu ya."
        }
        return false
    }

    private fun scheduleAutoReconnect(triggerReason: String) {
        if (!sessionDesiredActive) return
        if (reconnectJob?.isActive == true) return
        GepyLog.i("GeminiLive", "Menjadwalkan auto-reconnect adaptif Gemini Live ($triggerReason)...")
        _connectionStateFlow.value = LiveConnectionState.RECONNECTING

        reconnectJob = scope.launch {
            // Skema 7 tahap adaptif dengan random jitter (0.85..1.15) untuk toleransi fluktuasi sinyal s.d ~47 detik
            val baseDelaysMs = longArrayOf(500L, 1_200L, 2_500L, 4_500L, 7_500L, 11_000L, 15_000L)
            for (attempt in baseDelaysMs.indices) {
                if (!sessionDesiredActive) return@launch
                val jitterFactor = 0.85 + (Math.random() * 0.3)
                val sleepTime = (baseDelaysMs[attempt] * jitterFactor).toLong().coerceAtLeast(300L)
                delay(sleepTime)
                if (!sessionDesiredActive) return@launch
                GepyLog.i("GeminiLive", "Mencoba reconnect WebSocket ke-${attempt + 1}/${baseDelaysMs.size}...")
                val ok = establishWebSocketFromCandidates(onToolCallRequested = activeToolCallback)
                if (ok) {
                    GepyLog.i("GeminiLive", "Auto-reconnect Gemini Live berhasil pada percobaan ke-${attempt + 1}!")
                    return@launch
                }
            }
            if (sessionDesiredActive) {
                sessionDesiredActive = false
                connected = false
                _connectionStateFlow.value = LiveConnectionState.FAILED
                GepyLog.w("GeminiLive", "Auto-reconnect habis setelah 7 percobaan: $lastFailureReason")
            }
        }
    }

    private suspend fun trySingleWebSocketConnect(
        candidateModel: String,
        includeNonBlocking: Boolean,
        includeLanguageCode: Boolean,
        includeAudioTranscription: Boolean,
        includeSessionManagement: Boolean,
        onToolCallRequested: (suspend (GeminiLiveToolCall) -> Map<String, Any?>)?
    ): Boolean {
        closeSocketInternal()
        val deferred = CompletableDeferred<Boolean>()
        setupDeferred = deferred
        localVad.reset()
        clearMicBatch()

        val request = Request.Builder()
            .url(buildWebSocketUrl())
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this@GeminiLiveClient.webSocket == null) {
                    this@GeminiLiveClient.webSocket = webSocket
                } else if (webSocket !== this@GeminiLiveClient.webSocket) {
                    return
                }
                val setupPayload = buildSetupMessageJson(
                    targetModel = candidateModel,
                    includeNonBlocking = includeNonBlocking,
                    includeLanguageCode = includeLanguageCode,
                    includeAudioTranscription = includeAudioTranscription,
                    includeSessionManagement = includeSessionManagement,
                    userContext = currentUserMemoryContext
                ).toString()
                webSocket.send(setupPayload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                handleIncomingRawMessage(text, onToolCallRequested)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                handleIncomingRawMessage(bytes.utf8(), onToolCallRequested)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                val wasConnected = connected
                connected = false
                val httpCode = response?.code
                val msg = if (httpCode != null) {
                    "Koneksi WebSocket gagal (HTTP $httpCode): ${t.message ?: "Unknown error"}"
                } else {
                    "Koneksi WebSocket terputus: ${t.message ?: "Cek lagi jaringan internetmu ya"}"
                }
                lastFailureReason = msg
                GepyLog.w("GeminiLive", "WebSocket failure ($candidateModel): $msg")
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                } else if (wasConnected && sessionDesiredActive) {
                    scheduleAutoReconnect("socket_failure")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === this@GeminiLiveClient.webSocket && code != 1000 && reason.isNotBlank()) {
                    lastFailureReason = "Server Gemini Live menolak sesi ($code): $reason"
                    GepyLog.w("GeminiLive", "WebSocket closing ($candidateModel) [$code]: $reason")
                }
                try {
                    webSocket.close(code, reason)
                } catch (_: Throwable) {
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                val wasConnected = connected
                connected = false
                if (code != 1000 && reason.isNotBlank()) {
                    lastFailureReason = "Server Gemini Live menutup sesi ($code): $reason"
                }
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                } else if (wasConnected && sessionDesiredActive) {
                    scheduleAutoReconnect("server_closed_$code")
                }
            }
        }

        return try {
            webSocket = okHttpClient.newWebSocket(request, listener)
            val ready = withTimeoutOrNull(6_000L) { deferred.await() } ?: false
            if (!ready) {
                closeSocketInternal()
            }
            ready
        } catch (e: Throwable) {
            lastFailureReason = "Kesalahan koneksi Gemini Live: ${e.message}"
            closeSocketInternal()
            false
        }
    }

    internal fun handleIncomingRawMessage(
        rawJson: String,
        onToolCallRequested: (suspend (GeminiLiveToolCall) -> Map<String, Any?>)? = null
    ) {
        val parsed = GeminiLiveEventParser.parseServerMessage(rawJson)
        if (parsed.resumptionHandle != null) {
            resumptionHandle = parsed.resumptionHandle
            resumptionHandleTimestampMs = System.currentTimeMillis()
        }
        if (parsed.isSetupComplete) {
            connected = true
            _connectionStateFlow.value = LiveConnectionState.CONNECTED
            setupDeferred?.complete(true)
        }
        if (parsed.interrupted) {
            _bargeInFlow.tryEmit(Unit)
        }

        // Jalur cepat audio PCM 24kHz langsung ke AudioTrack agar bebas drop pada SharedFlow
        val audioCb = directAudioChunkCallback
        if (audioCb != null && parsed.audioChunksPcm24k.isNotEmpty()) {
            for (chunk in parsed.audioChunksPcm24k) {
                audioCb(chunk)
            }
        }

        _eventsFlow.tryEmit(parsed)
        processIncomingTurnLifecycle(parsed)

        // Sinyal GoAway resmi dari server (~sebelum batas koneksi 10 menit berakhir): sambung ulang mulus!
        if (parsed.goAway && sessionDesiredActive) {
            connected = false
            scheduleAutoReconnect("server_go_away")
        }

        if (onToolCallRequested != null && parsed.toolCalls.isNotEmpty()) {
            for (call in parsed.toolCalls) {
                scope.launch {
                    try {
                        val responseMap = onToolCallRequested(call)
                        sendToolResponse(callId = call.id, name = call.name, response = responseMap)
                    } catch (e: Throwable) {
                        sendToolResponse(
                            callId = call.id,
                            name = call.name,
                            response = mapOf("error" to (e.message ?: "Tool execution error"))
                        )
                    }
                }
            }
        }
    }

    /**
     * Memelihara *Initialization-Anchor Pinning (IAP)* & *Two-Resolution Context (TRC)*
     * (`arXiv:2610.32349v1`, `arXiv:2610.34991v2`) agar kompresi `slidingWindow` pada sesi panjang
     * (> 15–30 menit) tidak menghapus identitas dan memori pengguna.
     */
    internal fun processIncomingTurnLifecycle(parsed: GeminiLiveParsedEvent) {
        if (!parsed.turnComplete) return
        completedTurnCount++
        val interval = anchorPinIntervalTurns.coerceAtLeast(1)
        val rawMem = currentUserMemoryContext.trim()
        if (completedTurnCount % interval == 0 && rawMem.isNotEmpty()) {
            val compactAnchor = compressAnchorContextTrc(rawMem, maxChars = 2000)
            if (compactAnchor.isNotEmpty()) {
                anchorPinRefreshCount++
                sendSilentContextAnchorPin(compactAnchor)
            }
        }
    }

    internal fun compressAnchorContextTrc(rawMemory: String, maxChars: Int = 2000): String {
        val compactLines = rawMemory
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("===") && !it.startsWith("---") }
            .joinToString(" | ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (compactLines.length <= maxChars) {
            compactLines
        } else {
            compactLines.substring(0, maxChars).trimEnd() + "..."
        }
    }

    /**
     * Memproses satu frame PCM 16 kHz (20 ms) dari mikrofon:
     * 1. Prapenguatan sinyal mikrofon digital ([micPreAmpGain] = 1.5x) untuk menangkap suara normal/jauh secara jernih.
     * 2. Evaluasi VAD lokal sensitivitas tinggi dengan *Echo-Gate*.
     * 3. *ReFrame Silence-Aware Compute Reclamation* (`arXiv:2611.01835v1`): saat hening dua arah >= 1,5 detik,
     *    menghemat pengiriman paket PCM hening hingga 80% sambil menjaga *pre-roll buffer* 40 ms agar awal ucapan utuh.
     * 4. Pengelompokan (*batching*) 5 frame = `100 ms` per pesan WebSocket saat aktif berbicara.
     */
    fun processAndSendMicPcm16k(pcmBytes: ByteArray) {
        if (!isConnected || pcmBytes.isEmpty()) return
        val amplifiedMic = boostPcm16AudioInPlace(pcmBytes, micPreAmpGain)
        val playbackActive = isPlaybackActiveProvider?.invoke() ?: false
        val vadState = localVad.processFrame(amplifiedMic, playbackActive = playbackActive)

        // 1. Proteksi Anti-Lag & Anti-Stutter: Hanya picu barge-in AudioTrack lokal jika speaker tidak aktif,
        // ATAU jika pengguna sengaja menyela dengan suara lantang (RMS >= localVad.playbackActiveRmsThreshold).
        if (vadState.speechStarted) {
            if (!playbackActive || vadState.rms >= localVad.playbackActiveRmsThreshold) {
                _bargeInFlow.tryEmit(Unit)
            }
        }

        // 2. Acoustic Echo Suppression (AES): Saat asisten sedang berbicara (playbackActive),
        // blokir kebocoran suara speaker ke server Gemini KECUALI jika pengguna benar-benar sedang berbicara (isInSpeech).
        if (playbackActive && !localVad.isInSpeech) {
            reframeSavedBatchesCount++
            synchronized(micBatchLock) {
                micBatchBuffer.reset()
                micBatchFrameCount = 0
            }
            return
        }

        val eligibleForSilenceGate = vadState.isDeepSilence &&
            !vadState.speechStarted &&
            !vadState.speechEnded &&
            vadState.rms < (localVad.rmsThreshold * 0.75)

        var batchToSend: ByteArray? = null
        synchronized(micBatchLock) {
            if (eligibleForSilenceGate) {
                while (preRollFrames.size >= 2) {
                    preRollFrames.removeFirst()
                }
                preRollFrames.addLast(amplifiedMic)

                micBatchBuffer.write(amplifiedMic)
                micBatchFrameCount++
                if (micBatchFrameCount >= 5) {
                    reframeDeepSilenceBatchCounter++
                    if (reframeDeepSilenceBatchCounter % 5 == 0) {
                        // Kirim 1 keep-alive frame setiap 500 ms saat keheningan panjang
                        batchToSend = micBatchBuffer.toByteArray()
                    } else {
                        reframeSavedBatchesCount++
                        wasGatedInDeepSilence = true
                    }
                    micBatchBuffer.reset()
                    micBatchFrameCount = 0
                }
            } else {
                // Jika baru keluar dari keheningan panjang, sertakan pre-roll 40 ms (2 frame) agar suku kata & konsonan pertama utuh!
                if (wasGatedInDeepSilence) {
                    wasGatedInDeepSilence = false
                    reframeDeepSilenceBatchCounter = 0
                    val tempCurrent = if (micBatchFrameCount > 0) micBatchBuffer.toByteArray() else null
                    val tempFrames = micBatchFrameCount
                    micBatchBuffer.reset()
                    micBatchFrameCount = 0
                    while (preRollFrames.isNotEmpty()) {
                        micBatchBuffer.write(preRollFrames.removeFirst())
                        micBatchFrameCount++
                    }
                    if (tempCurrent != null && tempCurrent.isNotEmpty()) {
                        micBatchBuffer.write(tempCurrent)
                        micBatchFrameCount += tempFrames
                    }
                } else {
                    preRollFrames.clear()
                }

                micBatchBuffer.write(amplifiedMic)
                micBatchFrameCount++
                if (micBatchFrameCount >= 5 || vadState.speechEnded) {
                    batchToSend = micBatchBuffer.toByteArray()
                    micBatchBuffer.reset()
                    micBatchFrameCount = 0
                }

                if (vadState.speechEnded) {
                    preRollFrames.clear()
                }
            }
        }
        batchToSend?.let { sendAudioChunkPcm16k(it) }

        // 3. Proteksi Anti-Pengulangan Kata: Jangan kirim audioStreamEnd saat playback masih aktif,
        // agar server Gemini tidak salah mengira giliran asisten selesai dan mengulang jawaban!
        if (vadState.speechEnded && !playbackActive) {
            sendAudioStreamEnd()
        }
    }

    private fun clearMicBatch() {
        synchronized(micBatchLock) {
            micBatchBuffer.reset()
            micBatchFrameCount = 0
            preRollFrames.clear()
            reframeDeepSilenceBatchCounter = 0
            wasGatedInDeepSilence = false
        }
    }

    /** Mengirim potongan audio PCM 16 kHz mono ke aliran `realtimeInput.audio`. */
    open fun sendAudioChunkPcm16k(pcmBytes: ByteArray) {
        val ws = webSocket ?: return
        if (!isConnected || pcmBytes.isEmpty()) return
        val b64 = Base64.getEncoder().encodeToString(pcmBytes)
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("data", b64)
                    .put("mimeType", "audio/pcm;rate=16000")
            )
        )
        ws.send(payload.toString())
    }

    /** Mengirim sinyal akhir ujaran untuk akselerasi *Hybrid VAD*. */
    open fun sendAudioStreamEnd() {
        val ws = webSocket ?: return
        if (!isConnected) return
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put("audioStreamEnd", true)
        )
        ws.send(payload.toString())
    }

    /** Mengirim bingkai layar JPEG ke aliran `realtimeInput.video`. */
    open fun sendScreenFrameJpeg(jpegBytes: ByteArray) {
        val ws = webSocket ?: return
        if (!isConnected || jpegBytes.isEmpty()) return
        val b64 = Base64.getEncoder().encodeToString(jpegBytes)
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "video",
                JSONObject()
                    .put("data", b64)
                    .put("mimeType", "image/jpeg")
            )
        )
        ws.send(payload.toString())
    }

    /** Mengirim teks waktu nyata ke aliran `realtimeInput.text`. */
    open fun sendRealtimeText(text: String) {
        val clean = text.trim()
        val ws = webSocket ?: return
        if (!isConnected || clean.isEmpty()) return
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put("text", clean)
        )
        ws.send(payload.toString())
    }

    /**
     * Mengirim sinyal pembuka sesi ke server Gemini Live (`BidiGenerateContentClientContent` dengan `turnComplete: true`)
     * agar asisten langsung menyapa pengguna secara santai dan manusiawi begitu sambungan telepon aktif.
     */
    open fun sendInitialGreetingPrompt(customPrompt: String? = null) {
        val ws = webSocket ?: return
        if (!isConnected) return
        val greetingText = customPrompt?.trim()?.ifEmpty { null }
            ?: "[PANGGILAN DUA ARAH DIMULAI]: Sambungan telepon langsung baru saja terhubung. Sapa pengguna sekarang secara ramah, santai, dan ringkas dalam 1 kalimat Bahasa Indonesia lisan (contoh: 'Halo, ada yang bisa kubantu?'). JANGAN mengarang riwayat koding bersama atau berasumsi pengguna sedang mengerjakan sesuatu jika belum dibahas."
        val payload = buildInitialGreetingJson(greetingText)
        try {
            ws.send(payload.toString())
        } catch (_: Throwable) {
            sendRealtimeText(greetingText)
        }
    }

    fun buildInitialGreetingJson(promptText: String): JSONObject =
        GeminiLiveSetupPayloadBuilder.buildInitialGreetingJson(promptText)

    /**
     * Menyuntikkan interupsi pengumuman langsung dari Antigravity ke dalam percakapan suara Gemini Live
     * yang sedang aktif (`turnComplete: true`), sehingga Samantha langsung mengumumkan hasilnya ke pengguna
     * secara alami tanpa menunggu input suara pengguna selanjutnya.
     */
    open fun injectLiveAgentInterruption(announcementText: String): Boolean {
        val clean = announcementText.trim()
        val ws = webSocket ?: return false
        if (!isConnected || clean.isEmpty()) return false
        val formattedPrompt = "[SISTEM ANTIGRAVITY]: Laporan tugas latar belakang selesai dari laptop: $clean. Tolong sampaikan hasil ini langsung ke pengguna secara ringkas dan bersahabat."
        val payload = buildInitialGreetingJson(formattedPrompt)
        return try {
            ws.send(payload.toString())
        } catch (e: Exception) {
            GepyLog.w("GeminiLive", "Gagal menyuntikkan interupsi Live Gemini: ${e.message}")
            false
        }
    }

    /**
     * Mengirim pembaruan *Initialization-Anchor Pinning* (`BidiGenerateContentClientContent`
     * dengan `turnComplete: false`) tepat di sela giliran agar memori pengguna tetap tersemat
     * tanpa memicu respons audio tambahan dari server.
     */
    open fun sendSilentContextAnchorPin(compactAnchor: String) {
        val clean = compactAnchor.trim()
        val ws = webSocket ?: return
        if (!isConnected || clean.isEmpty()) return
        val payload = buildSilentContextAnchorPinJson(clean)
        ws.send(payload.toString())
    }

    fun buildSilentContextAnchorPinJson(compactAnchor: String): JSONObject =
        GeminiLiveSetupPayloadBuilder.buildSilentContextAnchorPinJson(compactAnchor)

    /**
     * Menyinkronkan pesan terbaru dari Tab Chat ke sesi aktif Gemini 3.8 Live secara diam-diam
     * (`turnComplete: false`) agar panggilan suara langsung mengetahui apa yang baru diketik di Chat
     * tanpa memotong audio yang sedang berjalan.
     */
    open fun sendSilentChatTurnSync(role: String, text: String): Boolean {
        val clean = text.trim()
        val ws = webSocket ?: return false
        if (!isConnected || clean.isEmpty()) return false
        val payload = buildSilentChatTurnSyncJson(role = role, text = clean)
        return try {
            ws.send(payload.toString())
        } catch (e: Throwable) {
            GepyLog.w("GeminiLive", "Gagal menyinkronkan turn Chat ke Live Call: ${e.message}")
            false
        }
    }

    fun buildSilentChatTurnSyncJson(role: String, text: String): JSONObject =
        GeminiLiveSetupPayloadBuilder.buildSilentChatTurnSyncJson(role, text)

    /** Mengirim hasil eksekusi fungsi asinkron kembali ke sesi Gemini Live. */
    open fun sendToolResponse(
        callId: String,
        name: String,
        response: Map<String, Any?>
    ) {
        val ws = webSocket ?: return
        if (!isConnected) return
        val payload = buildToolResponseJson(callId = callId, name = name, response = response)
        ws.send(payload.toString())
    }

    /** Membangun objek JSON `BidiGenerateContentToolResponse` sesuai skema resmi Gemini Live. */
    fun buildToolResponseJson(
        callId: String,
        name: String,
        response: Map<String, Any?>
    ): JSONObject = GeminiLiveSetupPayloadBuilder.buildToolResponseJson(callId, name, response)

    private fun closeSocketInternal() {
        connected = false
        setupDeferred?.let {
            if (!it.isCompleted) it.complete(false)
        }
        setupDeferred = null
        val oldWs = webSocket
        webSocket = null
        clearMicBatch()
        if (oldWs != null) {
            var closedGracefully = false
            try {
                closedGracefully = oldWs.close(1000, "Normal closure")
            } catch (_: Throwable) {
            }
            if (!closedGracefully) {
                try {
                    oldWs.cancel()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Memutus koneksi WebSocket Gemini Live secara eksplisit atas permintaan pengguna. */
    open fun disconnect() {
        sessionDesiredActive = false
        reconnectJob?.cancel()
        reconnectJob = null
        resumptionHandle = null
        resumptionHandleTimestampMs = 0L
        activeToolCallback = null
        closeSocketInternal()
        _connectionStateFlow.value = LiveConnectionState.CLOSED
    }
}
