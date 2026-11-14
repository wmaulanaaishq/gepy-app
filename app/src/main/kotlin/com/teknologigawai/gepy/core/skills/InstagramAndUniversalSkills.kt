package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Plugin kemampuan domain khusus untuk **Instagram Android (`com.instagram.android`)**:
 * - Mencari akun/pengguna Instagram (`search_tab` -> `action_bar_search_edit_text` -> baris profil).
 * - Mengirim pesan langsung (*Direct Message / DM*) baik dari halaman profil target (`"Kirim pesan"` / `"Message"`)
 *   maupun melalui kotak masuk Direct (`action_bar_inbox_button`).
 */
class InstagramSocialSkill(
    private val speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) : GepySkillPlugin {
    override val skillId: String = "instagram_social_dm"

    override val supportedPackages: List<String> = listOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    private val igRegex = Regex("""\b(instagram|ig|insta|dm)\b""", RegexOption.IGNORE_CASE)

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        val lower = transcript.lowercase()
        return igRegex.containsMatchIn(lower) ||
            lower.contains("direct message") ||
            lower.contains("story") ||
            lower.contains("cerita") ||
            lower.contains("repost")
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: INSTAGRAM ANDROID (com.instagram.android)]
1. MENCARI AKUN / PROFIL ORANG DI INSTAGRAM:
   - Jika saat ini berada di Beranda (Feed) dan belum ada kolom pencarian di atas, ketuk ("CLICK") tab Pencarian di bilah navigasi bawah (`com.instagram.android:id/search_tab` atau elemen berlabel "Cari dan jelajahi" / "Search and explore" / "Cari"). JANGAN mengetuk tombol Buat/Kamera/Reels.
   - Saat berada di halaman Explore (Jelajahi), terlihat bilah pencarian di bagian atas (`com.instagram.android:id/action_bar_search_edit_text` atau berlabel "Cari" / "Search" / "[Hint] Cari"). Gunakan "SET_TEXT" pada elemen tersebut dengan nama atau username orang yang dicari (tanpa kata "akun" atau "ig", cukup namanya saja).
   - Jika langkah sebelumnya ("SET_TEXT" atau "CLICK") baru membuka halaman input pencarian dan kolom `action_bar_search_edit_text` masih kosong (`[Hint] Cari`), gunakan "SET_TEXT" sekarang untuk mengetikkan nama target.
   - Setelah nama diketik di kolom pencarian, perhatikan daftar hasil di bawahnya:
     * Prioritaskan mengetuk ("CLICK") langsung baris akun pengguna yang cocok (`com.instagram.android:id/row_search_user_container`, `row_search_user_username`, atau `row_search_user_fullname`).
     * Jika terdapat lebih dari 1 akun berbeda dengan nama mirip dan pengguna belum menentukan username spesifik, gunakan "ASK_USER_DISAMBIGUATION" dengan daftar kandidat pada "options".
     * Jika daftar akun belum muncul dan hanya terlihat baris `com.instagram.android:id/echo_text` (misal `Cari "nama"` / `Search "nama"` atau tab "Akun" / "Accounts"), ketuk ("CLICK") baris tersebut untuk memuat daftar akun penuh.
   - Jika tujuan pengguna HANYA mencari atau membuka profil IG seseorang dan halaman profil akun tersebut sudah terbuka (terlihat tombol "Ikuti"/"Follow"/"Mengikuti"/"Following" atau "Kirim pesan"/"Message" serta jumlah postingan/pengikut), SEGERA keluarkan action "DONE".

2. MENGIRIM DM (DIRECT MESSAGE) DARI PROFIL ATAU KOTAK MASUK:
   - Cara A (Dari Halaman Profil Akun Target):
     * Di halaman profil orang tersebut, cari tombol berlabel "Kirim pesan" atau "Message" lalu gunakan "CLICK".
     * Jika tombol "Kirim pesan" / "Message" tidak tampil langsung di profil, ketuk tombol menu tiga titik di pojok kanan atas profil ("Opsi" / "Options" / `action_bar_overflow_icon`), lalu pada menu yang muncul ketuk "Kirim pesan" / "Send message".
   - Cara B (Dari Kotak Masuk Direct / Beranda):
     * Jika pengguna meminta membuka DM / pesan Instagram dari Beranda, ketuk ikon pesan di pojok kanan atas (`com.instagram.android:id/action_bar_inbox_button` atau berlabel "Pesan" / "Direct" / "Messenger"), lalu gunakan bilah "Cari" di dalam Direct untuk mencari nama akun dan ketuk baris akunnya.
   - Di Dalam Ruang Obrolan DM Instagram:
     * Ketik isi pesan pada kolom `EDITABLE` (`com.instagram.android:id/row_thread_composer_edittext`, berlabel `[Hint] Pesan...` atau `[Hint] Message...` atau `[Hint] Kirim pesan...`) menggunakan "SET_TEXT".
     * PENTING: Tombol "Kirim" / "Send" (`com.instagram.android:id/row_thread_composer_button_send`) di Instagram DM baru muncul SETELAH teks berhasil diketik ke dalam kolom pesan.
     * Setelah kolom pesan berisi teks (atau setelah langkah "SET_TEXT" berstatus "CORRECT"), ketuk tombol "Kirim" / "Send" (`row_thread_composer_button_send`) menggunakan "CONFIRM_AND_CLICK".
     * Begitu "CONFIRM_AND_CLICK" pada tombol Kirim selesai ("CORRECT") dan kolom input kembali kosong (`[Hint] Pesan...`), SEGERA keluarkan action "DONE" dan JANGAN mengetik atau mengirim ulang pesan.

3. MEMILIH POSTINGAN KE-N DI HALAMAN PROFIL AKUN (GRID 3 KOLOM):
   - Halaman profil Instagram menampilkan postingan dalam kisi 3 kolom (Baris 1 kiri-tengah-kanan = Postingan #1, #2, #3; Baris 2 kiri-tengah-kanan = Postingan #4, #5, #6; dst.).
   - WAJIB periksa field "spatial_grid_map" di payload! Field tersebut sudah menghitung nomor urut `Postingan #1 -> idx=...`, `Postingan #6 -> idx=...` secara presisi.
   - JIKA pengguna meminta `postingan ke-N` (misal ke-6) dan di halaman profil baru terlihat kurang dari N thumbnail (misal baru terlihat #1 sampai #3 karena tertutup bio profil):
     * Gunakan action "SCROLL" dengan `scroll_direction = "DOWN"` pada halaman profil terlebih dahulu agar baris ke-2 (#4, #5, #6) muncul di layar! JANGAN mengetuk Postingan #1!
   - PEMULIHAN SALAH KLIK: Jika agen telanjur masuk ke tampilan postingan tunggal pertama (terlihat indikator `1/5`, tombol Suka/Komentar/Bagikan pada postingan ke-1) padahal pengguna meminta postingan ke-N (N >= 2), JANGAN bingung atau scroll buta: langsung gunakan action "BACK" 1 kali untuk kembali ke Grid Profil, lalu ketuk `Postingan #N` sesuai `spatial_grid_map`!

4. MEMBAGIKAN / REPOST POSTINGAN ATAU REELS KE CERITA (STORY) + CAPTION:
   - Pastikan postingan ke-N yang benar sudah terbuka terlebih dahulu sebelum membagikan!
   - Langkah 1: Ketuk tombol "Bagikan" (ikon pesawat kertas / 'Bagikan' / 'Share' / `row_feed_button_share`) pada postingan tersebut.
   - Langkah 2: Pada menu lembaran bawah (*bottom sheet*), ketuk "Tambahkan postingan ke cerita Anda" atau "Tambahkan ke cerita" (Add post to your story).
   - Langkah 3 (KHUSUS JIKA PENGGUNA MEMINTA CAPTION / TEKS DI STORY):
     * Di layar Editor Cerita, JANGAN langsung mengetuk "Cerita Anda"!
     * Ketuk tombol alat teks berlabel "Aa" / "Tambahkan teks" / "Add text" (`add_text_button` / `text_tool_button`) di bagian atas editor.
     * Setelah kolom teks cerita terbuka (`EDITABLE`), gunakan "SET_TEXT" untuk menulis kalimat caption sesuai permintaan pengguna (contoh: bila diminta *"berikan caption kalau kamu yang post story"*, ketik `"Direpost otomatis ke Story oleh asisten AI Gepy ✨"`).
     * Ketuk tombol "Selesai" / "Done" di pojok kanan atas untuk menyimpan teks caption ke atas kanvas cerita.
   - Langkah 4: Di layar Editor Cerita, ketuk tombol "Cerita Anda" (Your story) di pojok kiri bawah (atau tombol panah "Bagikan" -> "Cerita Anda").
   - Langkah 5: BEGITU TOMBOL "CERITA ANDA" DIKETUK, Instagram otomatis menutup editor cerita dan layar kembali ke postingan awal atau Beranda. INI ADALAH BUKTI VALID 100% TUGAS SELESAI -> langsung keluarkan action "DONE" (DILARANG mengetuk tombol Bagikan lagi)!
        """.trimIndent()

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        val lowerGoal = userGoal.lowercase()
        val isStoryGoal = lowerGoal.contains("story") || lowerGoal.contains("cerita")
        if (!isStoryGoal) return null

        // PENTING: Hanya anggap cerita telah dipublikasikan jika tombol publikasi AKHIR ("Cerita Anda" / "Your story")
        // yang sudah diketuk — BUKAN "Tambahkan ke cerita" (yang baru membuka editor cerita)!
        val addToStoryIdx = memory.recentSteps.indexOfLast { step ->
            step.outcomeStatus == ActionOutcome.CORRECT &&
                (step.targetLabel.contains("tambahkan ke cerita", ignoreCase = true) ||
                    step.targetLabel.contains("tambahkan postingan", ignoreCase = true) ||
                    step.targetLabel.contains("add post to", ignoreCase = true))
        }

        val finalPublishIdx = memory.recentSteps.indexOfLast { step ->
            (step.action == AgentActionType.CLICK || step.action == AgentActionType.CONFIRM_AND_CLICK) &&
                step.outcomeStatus == ActionOutcome.CORRECT &&
                !step.targetLabel.contains("tambahkan ke cerita", ignoreCase = true) &&
                !step.targetLabel.contains("tambahkan postingan", ignoreCase = true) &&
                (step.targetLabel.contains("cerita anda", ignoreCase = true) ||
                    step.targetLabel.contains("your story", ignoreCase = true) ||
                    step.targetLabel.contains("post_capture_share", ignoreCase = true) ||
                    (addToStoryIdx >= 0 && memory.recentSteps.indexOf(step) > addToStoryIdx &&
                        (step.targetLabel.equals("bagikan", ignoreCase = true) ||
                            step.targetLabel.equals("share", ignoreCase = true))))
        }

        if (finalPublishIdx == -1) return null

        // Jika pengguna secara eksplisit meminta penambahan caption pada Story, pastikan SET_TEXT sudah dieksekusi
        val requiresCaption = lowerGoal.contains("caption") ||
            lowerGoal.contains("berikan teks") ||
            lowerGoal.contains("kasih teks") ||
            lowerGoal.contains("tuliskan teks")
        if (requiresCaption) {
            val typedCaptionInEditor = memory.recentSteps.take(finalPublishIdx + 1).any { step ->
                (step.action == AgentActionType.SET_TEXT || step.action == AgentActionType.SET_TEXT_AND_SUBMIT) &&
                    step.outcomeStatus == ActionOutcome.CORRECT &&
                    (addToStoryIdx == -1 || memory.recentSteps.indexOf(step) > addToStoryIdx)
            }
            if (!typedCaptionInEditor) return null
        }

        // Periksa apakah kontrol editor cerita saat ini sudah tertutup
        val editorOpen = screenElements.any { el ->
            val textLower = el.text.lowercase()
            val idLower = el.viewResourceId?.lowercase().orEmpty()
            textLower.contains("tulis teks cerita") ||
                textLower.contains("tambahkan ke cerita anda") ||
                textLower.contains("tambahkan postingan ke cerita") ||
                textLower.contains("stiker") ||
                textLower.contains("buang cerita") ||
                idLower.contains("quick_capture") ||
                idLower.contains("camera_shutter") ||
                idLower.contains("post_capture") ||
                idLower.contains("creation_secondary_actions")
        }

        if (!editorOpen) {
            // Editor tertutup pasca klik Cerita Anda -> selesai instan 0 ms tanpa panggil LLM!
            return ReActActionModel(
                thought = "Editor Cerita telah tertutup setelah tombol Cerita Anda diketuk. Postingan telah berhasil dipublikasikan ke Story Instagram.",
                reflectionOnLastStep = ReflectionStatus.CORRECT,
                action = AgentActionType.DONE,
                completionTts = SovereignNaturalSpeech.instagramStoryShared(speechContextProvider())
            )
        }

        return null
    }

    override suspend fun validateBeforeAction(
        proposedAction: ReActActionModel,
        screenElements: List<PrunedUiElement>
    ): ReActActionModel {
        if (proposedAction.action == AgentActionType.CLICK && proposedAction.targetIndex != null) {
            val match = screenElements.firstOrNull { it.index == proposedAction.targetIndex }
            if (match != null) {
                val id = (match.viewResourceId ?: match.nodeId).lowercase()
                val label = match.text.trim().lowercase()
                if (id.contains("button_send") || label == "kirim" || label == "send") {
                    return proposedAction.copy(
                        action = AgentActionType.CONFIRM_AND_CLICK,
                        confirmationPrompt = proposedAction.confirmationPrompt
                            ?: SovereignNaturalSpeech.instagramDmConfirm(speechContextProvider())
                    )
                }
            }
        }
        return proposedAction
    }
}

/**
 * Plugin panduan navigasi universal lintas aplikasi Android populer
 * (YouTube, TikTok, Telegram, Shopee, Tokopedia, Spotify, Chrome).
 */
class UniversalAppNavigationSkill : GepySkillPlugin {
    override val skillId: String = "universal_app_navigation"

    override val supportedPackages: List<String> = listOf(
        "com.google.android.youtube",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically",
        "org.telegram.messenger",
        "com.shopee.id",
        "com.tokopedia.tkpd",
        "com.spotify.music",
        "com.android.chrome"
    )

    override val preferredInitialPackage: String? = null

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        val lower = transcript.lowercase()
        return lower.contains("youtube") ||
            lower.contains("tiktok") ||
            lower.contains("tik tok") ||
            lower.contains("telegram") ||
            lower.contains("shopee") ||
            lower.contains("tokopedia") ||
            lower.contains("spotify") ||
            lower.contains("chrome") ||
            lower.contains("cari ")
    }

    override val domainKnowledgePrompt: String =
        """
[PANDUAN EKSEKUSI LINTAS APLIKASI ANDROID (YOUTUBE, TIKTOK, TELEGRAM, SHOPEE, SPOTIFY, CHROME)]
1. PENCARIAN UNIVERSAL (SEARCH FLOW):
   - Jika belum ada kolom `[EditText]` (`editable=true`) di layar, cari dan ketuk ("CLICK") ikon atau bilah "Cari" / "Search" / "Telusuri" (`search_button`, `menu_search`, `search_bar`, `ic_search`) terlebih dahulu.
   - Begitu kolom `editable=true` muncul, gunakan "SET_TEXT" dengan kata kunci bersih (jangan sertakan kata perintah seperti "tolong cari di youtube", cukup isi kueri pencariannya saja).
   - Setelah "SET_TEXT" berhasil, jika hasil pencarian sudah tampil di layar, langsung ketuk ("CLICK") item hasil yang paling relevan. Jika baru muncul daftar saran kueri (`suggestion`), ketuk baris saran teratas yang sesuai.
2. PENANGANAN POPUP / IKLAN / DIALOG:
   - Jika muncul popup promosi, izin opsional, atau banner yang menghalangi layar, ketuk tombol penutup ("Tutup", "Close", "Nanti", "Tidak Sekarang", "Skip", "Lewati", atau ikon "X") atau gunakan "BACK" satu kali.
3. KRITERIA SELESAI ("DONE"):
   - Segera keluarkan action "DONE" begitu tujuan akhir pengguna tercapai di layar (misal video/lagu sudah mulai diputar, halaman profil/produk yang dicari sudah terbuka, atau pesan sudah terkirim).
        """.trimIndent()
}
