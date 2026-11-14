package com.teknologigawai.gepy.core.ports

import kotlinx.coroutines.flow.Flow

/**
 * Port Hexagonal untuk interaksi suara (*STT/TTS/Gemini Live*) dan jendela mengambang (*Overlay HUD*).
 *
 * Dalam arsitektur Gepy Native (Single-Process Zero-Isolate), port ini berkomunikasi
 * langsung dengan `OverlayHudWindowManager` (`ComposeView` di atas `WindowManager`)
 * dan `VoiceAudioEngine` tanpa IPC/MethodChannel.
 */
interface VoiceHudPort {
    /** Aliran instruksi suara pengguna yang siap dieksekusi oleh `GepyOrchestrator`. */
    val voiceCommandFlow: Flow<String>

    /** Aliran sinyal interupsi (*Barge-In*) ketika pengguna menyela ucapan/aksi agen. */
    val bargeInInterruptFlow: Flow<String>

    /** Menginisialisasi mesin audio, TTS Bahasa Indonesia, dan pengelola jendela HUD. */
    suspend fun initialize()

    /** Menampilkan kapsul mengambang ringkas (`80x80 dp`) di tepi layar. */
    suspend fun showIdlePill()

    /** Menutup dan menyembunyikan jendela mengambang dari layar. */
    suspend fun hideOverlay()

    /** Memperbesar HUD dan mulai mendengarkan perintah suara pengguna (`id_ID`). */
    suspend fun startListening()

    /** Menampilkan banner status pemikiran agen (`340x96 dp`) tanpa menghalangi sentuhan. */
    suspend fun showThinkingState(stepHeadline: String, thought: String)

    /**
     * Membacakan pertanyaan disambiguasi [questionTts] dan menunggu pengguna memilih
     * salah satu kandidat dari [options] pada panel HUD.
     */
    suspend fun askDisambiguation(questionTts: String, options: List<String>): String

    /**
     * Menampilkan modal persetujuan *Human-in-the-Loop* (`360x240 dp`) untuk tindakan
     * sensitif dan mengembalikan `true` hanya jika pengguna menyetujui secara eksplisit.
     */
    suspend fun requestHitlApproval(summaryPrompt: String, targetLabel: String): Boolean

    /** Menyusutkan HUD kembali ke mode kapsul siaga dan membacakan kalimat [completionTts]. */
    suspend fun finishAndSpeak(completionTts: String)

    /**
     * Menampilkan kartu bisikan kontekstual proaktif (*Dynamic Island Frosted Glass Pill*)
     * secara hening tanpa suara mengejutkan, dan otomatis menghilang setelah [durationMs].
     */
    suspend fun showProactiveWhisper(
        title: String,
        subtitle: String,
        actionLabel: String,
        goalPayload: String,
        durationMs: Long = 10_000L
    ) {}

    /** Menunjukkan apakah panggilan suara langsung (Gemini Live) sedang aktif. */
    val isLiveCallActive: Boolean
        get() = false

    /** Menunjukkan apakah pemutaran audio ucapan asisten sedang berlangsung. */
    val isPlaybackActive: Boolean
        get() = false

    /**
     * Menyuntikkan interupsi pengumuman langsung ke dalam sesi suara aktif.
     * Mengembalikan `true` jika berhasil disuntikkan ke stream suara real-time.
     */
    fun injectLiveAgentInterruption(announcementText: String): Boolean = false

    /** Membebaskan seluruh pengontrol suara dan jendela overlay. */
    fun dispose()
}
