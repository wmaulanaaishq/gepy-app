package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Plugin *Fast-Path Skill* untuk pemutaran dan kontrol media **Spotify & YouTube Instan**
 * (< 50 ms, 0 token LLM Cloud):
 * - Memutar lagu/artis/album spesifik (*"putar lagu Bernadya di spotify"*, *"play musik di spotify"*, *"putar lagu di youtube"*)
 * - Memutar lagu di laptop (*"putar lagu tulus di laptop"*, *"setel spotify di pc"*)
 * - Kontrol media playback (*"jeda lagu"*, *"lanjutkan lagu"*, *"lagu berikutnya"*, *"lagu sebelumnya"*)
 */
class SpotifyFastPathSkill(
    private val systemBridge: AndroidSystemToolsBridge = AndroidSystemToolsBridge(),
    private val isEnabledProvider: () -> Boolean = { true },
    private val speechContextProvider: () -> SovereignSpeechContext = { systemBridge.speechContextProvider() }
) : GepySkillPlugin {

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        // Regex untuk pemutaran musik / pencarian lagu:
        // Mendukung multi-kata judul lagu/artis ("bernadya satu bulan", "tulus hati-hati di jalan", "wave to earth seasons")
        private val playMusicRegex = Regex(
            """\b(?:putar|setel|play|mainkan|dengarkan)\s+(?:lagu|musik|artis|album|playlist|lagunya)?\s*(.*?)(?:\s+(?:di|lewat)\s+(spotify|youtube|yt|youtube music|yt music|laptop|pc|komputer|hp|ponsel))?[.?!]*$""",
            RegexOption.IGNORE_CASE
        )

        // Regex kontrol playback murni (jeda, lanjut, skip, next, prev) — tidak menangkap "play musik di spotify":
        private val mediaControlRegex = Regex(
            """\b(?:jeda|pause|stop|hentikan|matikan|lanjutkan|resume|ganti|next|skip|prev|previous)\s+(?:lagu|musik|lagunya|pemutaran|spotify|youtube)(?:\s+(?:di\s+)?(?:laptop|pc|komputer|hp|ponsel))?\b|\b(?:lagu|musik)\s+(?:berikutnya|selanjutnya|sebelumnya)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    override val skillId: String = "spotify_fast_path"

    override val supportedPackages: List<String> = listOf(SPOTIFY_PACKAGE, YOUTUBE_PACKAGE)

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        if (!isEnabledProvider()) return false
        val clean = transcript.trim()
        if (clean.isEmpty()) return false

        // Hindari bentrok dengan tugas laptop non-musik jika ada
        val lower = clean.lowercase()
        if (lower.contains("kunci laptop") || lower.contains("status laptop") || lower.contains("buka vscode")) {
            return false
        }

        // Cek apakah ada kata kunci eksplisit musik/spotify/youtube
        val isExplicitSpotify = lower.contains("spotify")
        val isExplicitYouTube = lower.contains("youtube") || lower.contains("yt music")
        val isExplicitMusic = lower.contains("lagu") || lower.contains("musik") ||
            lower.contains("playlist") || lower.contains("album") || lower.contains("artis")

        // Jangan biarkan "setel timer 10 menit" / "setel volume 50%" / "setel alarm" / "setel mode getar"
        // atau pertanyaan "lagi muter lagu apa?" dibajak menjadi pencarian Spotify
        if (!isExplicitSpotify && !isExplicitYouTube) {
            if (!isExplicitMusic &&
                Regex("""\b(alarm|timer|penghitung\s+waktu|hitung\s+mundur|volume|suara|mode\s+(?:getar|senyap|hening|normal)|senter|flashlight|panel\s+\w+|pengaturan)\b""")
                    .containsMatchIn(lower)
            ) {
                return false
            }
            if (Regex("""\b(?:lagu\s+apa|muter\s+lagu\s+apa|judul\s+lagu)\b""").containsMatchIn(lower)) {
                return false
            }
        }

        if (isExplicitSpotify || isExplicitYouTube || isExplicitMusic) {
            return playMusicRegex.containsMatchIn(clean) || mediaControlRegex.containsMatchIn(clean)
        }

        // Jika hanya "setel X" atau "play X"
        return playMusicRegex.containsMatchIn(clean)
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: SPOTIFY & YOUTUBE INSTANT CONTROL]
Untuk instruksi memutar musik, lagu, artis, atau playlist di Spotify maupun YouTube serta kontrol playback (pause, resume, next, prev), gunakan alat control_spotify_playback atau play_music secara instan tanpa membedah antarmuka layar HP.
        """.trimIndent()

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        if (!canHandleVoiceIntent(userGoal)) return null

        val clean = userGoal.trim()
        val lower = clean.lowercase()

        // 1. Tentukan perangkat target
        val targetDevice = when {
            lower.contains("laptop") || lower.contains("pc") || lower.contains("komputer") -> "laptop"
            else -> "phone"
        }

        // 2. Evaluasi apakah ini perintah kontrol playback (pause, next, prev, resume)
        if (mediaControlRegex.containsMatchIn(clean)) {
            val action = when {
                lower.contains("jeda") || lower.contains("pause") || lower.contains("stop") || lower.contains("hentikan") -> "pause"
                lower.contains("lanjut") || lower.contains("resume") -> "resume"
                lower.contains("berikutnya") || lower.contains("selanjutnya") || lower.contains("next") || lower.contains("ganti") || lower.contains("skip") -> "next"
                lower.contains("sebelumnya") || lower.contains("prev") -> "previous"
                else -> "play_pause"
            }

            val ctx = speechContextProvider()
            val res = systemBridge.controlMediaPlayback(action = action, targetDevice = targetDevice)
            val tts = res["summary_id"]?.toString()
                ?: SovereignNaturalSpeech.mediaControl(ctx, action = action, targetDevice = targetDevice)

            return ReActActionModel(
                thought = "[Fast Path Spotify] Mengirim perintah kontrol media ($action) ke $targetDevice secara instan tanpa membuka layar.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = tts
            )
        }

        // 3. Evaluasi perintah pemutaran musik / pencarian lagu
        val cleanPunctuation = clean.trimEnd('.', '?', '!', ' ')
        val match = playMusicRegex.find(cleanPunctuation)
        var songQuery = match?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val isYouTube = lower.contains("youtube") || lower.contains("yt music")

        // Bersihkan imbuhan pencarian atau kata platform telanjang ("musik", "di spotify", "youtube")
        songQuery = songQuery
            .replace(Regex("""^(?:lagu|musik|musiknya|artis|album|playlist|lagunya)(?:\s+|$)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?:^|\s+)(?:di|lewat)\s+(?:spotify|youtube|yt|youtube music|yt music|laptop|pc|komputer|hp|ponsel)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:spotify|youtube|yt|youtube music|yt music|laptop|pc|komputer|hp|ponsel)$""", RegexOption.IGNORE_CASE), "")
            .trim()

        val ctx = speechContextProvider()
        if (isYouTube) {
            val res = systemBridge.playYouTubeMusic(query = songQuery)
            val tts = res["summary_id"]?.toString()
                ?: SovereignNaturalSpeech.youtubePlay(ctx, query = songQuery)

            return ReActActionModel(
                thought = "[Fast Path YouTube] Meluncurkan pemutaran YouTube '$songQuery' secara instan.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = tts
            )
        }

        val res = systemBridge.playSpotifyMusic(query = songQuery, targetDevice = targetDevice)
        val tts = res["summary_id"]?.toString()
            ?: SovereignNaturalSpeech.spotifyPlay(ctx, query = songQuery, targetDevice = targetDevice)

        return ReActActionModel(
            thought = "[Fast Path Spotify] Meluncurkan pemutaran instan '$songQuery' ke $targetDevice dan otomatis mengeksekusi klik putar.",
            reflectionOnLastStep = ReflectionStatus.INITIAL,
            action = AgentActionType.DONE,
            completionTts = tts
        )
    }
}
