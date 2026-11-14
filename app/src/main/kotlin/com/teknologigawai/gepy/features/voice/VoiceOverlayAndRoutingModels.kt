package com.teknologigawai.gepy.features.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.teknologigawai.gepy.core.config.EnvConfig
import java.io.File

/** Mode visual jendela mengambang (*Overlay HUD*) Gepy. */
enum class OverlayVisualMode(val wireName: String) {
    /** Kapsul mengambang ringkas (`80x80 dp`) di tepi layar. */
    IDLE("IDLE"),

    /** Panel pendengaran suara pengguna atau sesi Gepy Live Call (`340x120 dp`). */
    LISTENING("LISTENING"),

    /** Banner status pemikiran dan eksekusi langkah ReAct (`340x96 dp`, *click-through*). */
    THINKING("THINKING"),

    /** Panel pilihan disambiguasi kandidat kontak/entitas kembar (`340x240 dp`). */
    DISAMBIGUATION("DISAMBIGUATION"),

    /** Modal persetujuan *Human-in-the-Loop* (`360x240 dp`) untuk aksi ireversibel. */
    HITL_CONFIRM("HITL_CONFIRM"),

    /** Bisikan proaktif non-intrusif kapsul kaca (*Dynamic Island Frosted Glass*, `320x68 dp`). */
    PROACTIVE_WHISPER("PROACTIVE_WHISPER")
}

/** Jenis aktivitas konteks aktif yang ditampilkan pada kapsul & pil *Bubble-Up* mengambang. */
enum class OverlayHudActivityKind(val defaultPillLabel: String) {
    IDLE("Siaga"),
    LISTENING("Mendengarkan..."),
    THINKING("Berpikir..."),
    EXECUTING("Menjalankan aksi..."),
    LIVE_LISTENING("Mendengarkan..."),
    LIVE_SPEAKING("Gepy Berbicara..."),
    CONNECTING("Menyambung...")
}

/** State imutabel tampilan *Overlay HUD* yang dikonsumsi langsung oleh `ComposeView` di memori (`0 ms` IPC). */
data class OverlayHudState(
    val isVisible: Boolean = false,
    val mode: OverlayVisualMode = OverlayVisualMode.IDLE,
    val headline: String = "Gepy Siaga",
    val subtitle: String = "Ketuk untuk perintah suara",
    val options: List<String> = emptyList(),
    val isLiveCallActive: Boolean = false,
    val whisperActionLabel: String = "",
    val whisperGoalPayload: String = "",
    val activityKind: OverlayHudActivityKind = OverlayHudActivityKind.IDLE,
    val statusPillLabel: String = "",
    val showStopButton: Boolean = false
)

/** Antarmuka pembicara TTS untuk memudahkan pengujian unit tanpa mesin TTS Android. */
fun interface TtsSpeaker {
    fun speak(text: String)
}

/** Antarmuka pemutar berkas MP3 lokal untuk pengujian unit deterministik tanpa hardware MediaPlayer. */
fun interface Mp3FilePlayer {
    suspend fun playFile(file: File): Boolean
}

/** Backend kontrol volume panggilan & mode audio sistem untuk mendukung pengujian unit JVM. */
interface SystemAudioRoutingBackend {
    fun getVoiceCallVolume(): Int
    fun getVoiceCallMaxVolume(): Int
    fun setVoiceCallVolume(volume: Int)
    fun enterCommunicationMode()
    fun exitCommunicationMode()
}

/** Abstraksi manajemen rute audio panggilan dua arah (`STREAM_VOICE_CALL` + `MODE_IN_COMMUNICATION`). */
interface LiveCallAudioRouter {
    fun enterLiveCallRouting()
    fun exitLiveCallRouting()
}

/**
 * Pengelola siklus hidup rute audio Live Call (`STREAM_VOICE_CALL` + `MODE_IN_COMMUNICATION`)
 * yang menyimpan volume asal sebelum menaikkan ke minimum 85%, lalu memulihkannya saat panggilan berakhir.
 */
class ManagedLiveCallAudioRouter(
    private val backend: SystemAudioRoutingBackend
) : LiveCallAudioRouter {

    companion object {
        fun computeMinimumCallVolume(currentVolume: Int, maxVolume: Int): Int? {
            if (maxVolume <= 0) return null
            val targetMin = kotlin.math.round(maxVolume * 0.85f).toInt().coerceIn(1, maxVolume)
            return if (currentVolume < targetMin) targetMin else null
        }
    }

    private var hasCapturedState = false
    private var savedVoiceCallVolume = -1
    private var didBoostVoiceCallVolume = false

    @Synchronized
    override fun enterLiveCallRouting() {
        val currentVol = runCatching { backend.getVoiceCallVolume() }.getOrDefault(-1)
        val maxVol = runCatching { backend.getVoiceCallMaxVolume() }.getOrDefault(0)
        if (!hasCapturedState) {
            savedVoiceCallVolume = currentVol
            didBoostVoiceCallVolume = false
            hasCapturedState = true
        }
        runCatching { backend.enterCommunicationMode() }
        if (currentVol >= 0) {
            val targetMin = computeMinimumCallVolume(currentVol, maxVol)
            if (targetMin != null) {
                runCatching {
                    backend.setVoiceCallVolume(targetMin)
                    didBoostVoiceCallVolume = true
                }
            }
        }
    }

    @Synchronized
    override fun exitLiveCallRouting() {
        runCatching { backend.exitCommunicationMode() }
        if (hasCapturedState && didBoostVoiceCallVolume && savedVoiceCallVolume >= 0) {
            runCatching { backend.setVoiceCallVolume(savedVoiceCallVolume) }
        }
        hasCapturedState = false
        savedVoiceCallVolume = -1
        didBoostVoiceCallVolume = false
    }
}

internal class AndroidSystemAudioRoutingBackend(
    private val context: Context
) : SystemAudioRoutingBackend {
    private val audioManager: AudioManager?
        get() = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun getVoiceCallVolume(): Int {
        return audioManager?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: -1
    }

    override fun getVoiceCallMaxVolume(): Int {
        return audioManager?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
    }

    override fun setVoiceCallVolume(volume: Int) {
        val am = audioManager ?: return
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        if (maxVol <= 0) return
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume.coerceIn(0, maxVol), 0)
    }

    override fun enterCommunicationMode() {
        val am = audioManager ?: return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferredDevice = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (preferredDevice != null) {
                am.setCommunicationDevice(preferredDevice)
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    override fun exitCommunicationMode() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }
}

/** Konfigurasi runtime sintesis suara personal hasil cloning ElevenLabs (`gepy`). */
data class ElevenLabsRuntimeConfig(
    val enabled: Boolean = true,
    val apiKey: String = EnvConfig.defaultElevenLabsApiKey,
    val voiceId: String = EnvConfig.defaultElevenLabsVoiceId,
    val modelId: String = EnvConfig.elevenLabsModel,
    val stability: Float = 0.45f,
    val similarityBoost: Float = 0.75f,
    val allowMidUtteranceSystemFallback: Boolean = false
) {
    /** Layak memutar klip audio brankas cache lokal berdasarkan [voiceId] meskipun API Key kosong. */
    val canReadCache: Boolean
        get() = enabled && voiceId.trim().isNotEmpty()

    /** Layak menyintesis audio baru secara online melalui ElevenLabs cloud API. */
    val canSynthesizeOnline: Boolean
        get() = enabled && apiKey.trim().isNotEmpty() && voiceId.trim().isNotEmpty()

    val isReady: Boolean
        get() = canSynthesizeOnline
}
