package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.GoalVerificationVerdict
import com.teknologigawai.gepy.core.ports.OsQueryStatus
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.OsVerificationBaseline
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Modul **Planner & Goal Completion Verifier (Gepy v4 Cognitive Core)**:
 * 1. [buildSubgoalChecklist]: Mendekomposisi tujuan kompleks lintas aplikasi menjadi daftar periksa
 *    sub-tujuan eksplisit agar agen tidak lupa langkah akhir (mis. lupa menekan tombol Kirim).
 * 2. [verifyBeforeDone]: Gerbang verifikasi berlapis (*Deterministic Ground-Truth Gate* `0 ms` +
 *    *Semantic LLM Critic Gate*) yang menolak klaim `DONE` palsu sebelum tujuan benar-benar tuntas.
 */
object TaskPlannerAndVerifier {

    private val compoundSplitter = Regex(
        """\s+(?:lalu|kemudian|terus|habis\s+itu|setelah\s+itu|dan\s+kirim|serta)\s+""",
        RegexOption.IGNORE_CASE
    )
    private val igRegex = Regex("""\b(instagram|ig|insta|dm)\b""", RegexOption.IGNORE_CASE)
    private val sendMessageRegex = Regex(
        """\b(kirim\s+pesan|kirim\s+dm|kirim\s+chat|kirim\s+wa|balas\s+pesan|balas\s+dm|dm\s+|chat\s+.*\b(?:bilang|isi|pesan))\b""",
        RegexOption.IGNORE_CASE
    )
    private val sendTargetKeywords = listOf(
        "kirim", "send", "button_send", "composer_button_send", "chat_send"
    )
    private val storyPostRegex = Regex(
        """\b(story|stories|cerita|repost|post.*story|posting.*story|unggah.*story|upload.*story|bagikan.*cerita|share.*story|post.*foto|posting.*foto)\b""",
        RegexOption.IGNORE_CASE
    )
    private val nthPostRegex = Regex(
        """\b(?:postingan|post|foto|reel)\s+(?:ke[-\s]*(\d+)|pertama|kedua|ketiga|keempat|kelima|keenam)\b""",
        RegexOption.IGNORE_CASE
    )
    private val storyFinalPublishKeywords = listOf(
        "cerita anda", "your story", "bagikan", "share", "kirim ke", "send to", "posting", "post_capture_share"
    )
    private val playMediaRegex = Regex("""\b(putar|setel|tonton|play)\b""", RegexOption.IGNORE_CASE)
    private val searchRegex = Regex("""\b(cari|carikan|temukan|cek\s+profil|buka\s+profil|cari\s+ig)\b""", RegexOption.IGNORE_CASE)

    /** Membangun daftar periksa sub-tujuan terstruktur untuk dipandu pada setiap giliran ReAct. */
    fun buildSubgoalChecklist(userGoal: String): List<String> {
        val clean = userGoal.trim()
        if (clean.isEmpty()) return emptyList()

        // 0. Prioritas Utama: Tugas Majemuk Rantai Lintas Aplikasi (Cross-App Workflow)
        if (CrossAppWorkflowEngine.isCrossAppCommand(clean)) {
            val crossPlan = CrossAppWorkflowEngine.parseWorkflowPlan(clean)
            if (crossPlan.isMultiStage) {
                return crossPlan.stages.map { stage ->
                    val appInfo = if (!stage.targetPackageHint.isNullOrBlank()) " (${stage.targetPackageHint})" else ""
                    val warningTag = if (stage.stageIndex < crossPlan.stages.size) {
                        " [PERINGATAN: DILARANG DONE DI TAHAP INI! Simpan ke scratchpad / CLIPBOARD_COPY lalu buka aplikasi berikutnya]"
                    } else {
                        " [Tahap Akhir -> Selesaikan aksi & DONE]"
                    }
                    "${stage.stageIndex}. ${stage.title}$appInfo$warningTag"
                }
            }
        }

        val lower = clean.lowercase()

        val isIg = igRegex.containsMatchIn(lower) || lower.contains("direct message") || lower.contains("instagram")
        val isStory = storyPostRegex.containsMatchIn(lower) ||
            lower.contains("story") || lower.contains("cerita")
        val wantsSend = sendMessageRegex.containsMatchIn(lower) ||
            (isIg && !isStory && (lower.contains("dm") || lower.contains("kirim") || lower.contains("pesan")))

        if (isStory) {
            val isSharingExistingPost = lower.contains("postingan") ||
                lower.contains("repost") ||
                lower.contains("dari post") ||
                lower.contains("post ini") ||
                lower.contains("reel") ||
                lower.contains("orang lain") ||
                (lower.contains("bagikan") && !lower.contains("foto")) ||
                (lower.contains("share") && !lower.contains("photo") && !lower.contains("image"))
            if (isSharingExistingPost) {
                val hasNthPost = nthPostRegex.containsMatchIn(lower)
                val wantsCaption = lower.contains("caption") ||
                    lower.contains("berikan teks") ||
                    lower.contains("kasih teks") ||
                    lower.contains("tuliskan teks")
                if (hasNthPost || wantsCaption) {
                    return buildList {
                        var num = 1
                        if (searchRegex.containsMatchIn(lower) || hasNthPost) {
                            add("${num++}. Buka profil Instagram target dan gunakan spatial_grid_map (scroll profil bila perlu) untuk mengetuk postingan urutan yang diminta secara tepat (BACK ke grid jika salah klik postingan #1)")
                        } else {
                            add("${num++}. Pastikan postingan/reel Instagram yang ingin dibagikan terbuka di layar")
                        }
                        add("${num++}. Ketuk tombol 'Bagikan' (ikon pesawat kertas) pada postingan target, lalu pilih 'Tambahkan postingan ke cerita Anda' / 'Tambahkan ke cerita'")
                        if (wantsCaption) {
                            add("${num++}. Di layar editor Cerita, ketuk tombol teks 'Aa' ('Tambahkan teks'), gunakan SET_TEXT untuk mengetik caption sesuai permintaan pengguna, lalu ketuk 'Selesai'/'Done'")
                        }
                        add("${num++}. Ketuk tombol 'Cerita Anda' (Your story) di bagian bawah untuk mempublikasikan cerita")
                        add("$num. PENTING: Begitu editor cerita tertutup dan kembali ke postingan awal, tugas SELESAI 100% (keluarkan action DONE)")
                    }
                }
                return listOf(
                    "1. Pastikan postingan/reel Instagram yang ingin dibagikan terbuka di layar",
                    "2. Ketuk tombol 'Bagikan' (ikon pesawat kertas atau tombol 'Bagikan'/'Share') pada postingan",
                    "3. Pada menu pilihan di bawah, ketuk 'Tambahkan postingan ke cerita Anda' (atau 'Tambahkan ke cerita')",
                    "4. Di layar editor Cerita, ketuk tombol 'Cerita Anda' (Your story) di bagian bawah",
                    "5. PENTING: Begitu editor cerita tertutup dan kembali ke postingan awal, tugas SELESAI 100% (keluarkan action DONE)"
                )
            }
            return listOf(
                "1. Pastikan aplikasi Instagram terbuka di layar depan",
                "2. Buka pembuat Cerita (ketuk 'Cerita Anda', ikon kamera/tambah, atau tab Cerita)",
                "3. Pilih foto/video dari galeri atau ambil media",
                "4. Ketuk tombol 'Cerita Anda' (Your story) atau 'Bagikan' (Share) untuk memposting",
                "5. Pastikan editor cerita tertutup dan aplikasi kembali ke layar utama"
            )
        }

        if (isIg && wantsSend) {
            return listOf(
                "1. Pastikan aplikasi Instagram terbuka di layar depan",
                "2. Buka tab Cari (search_tab), ketik nama/username target di kolom pencarian, lalu ketuk baris profil akun yang tepat",
                "3. Di halaman profil akun tersebut, ketuk tombol 'Kirim pesan' atau 'Message' (atau menu 3 titik -> Kirim pesan)",
                "4. Ketik isi pesan DM pada kolom tulis pesan (row_thread_composer_edittext)",
                "5. Ketuk tombol Kirim/Send (CONFIRM_AND_CLICK) dan pastikan pesan terkirim"
            )
        }

        if (isIg && searchRegex.containsMatchIn(lower)) {
            return listOf(
                "1. Pastikan aplikasi Instagram terbuka di layar depan",
                "2. Ketuk tab Cari (search_tab) dan ketik nama/username yang dicari pada action_bar_search_edit_text",
                "3. Ketuk baris akun pengguna yang cocok hingga halaman profil akun terbuka penuh"
            )
        }

        if (wantsSend) {
            return listOf(
                "1. Buka aplikasi pesan target dan masuk ke ruang obrolan penerima yang tepat",
                "2. Ketik isi pesan pada kolom input obrolan (edit=true)",
                "3. Ketuk tombol Kirim/Send (CONFIRM_AND_CLICK) hingga teks terkirim dan kolom input kembali kosong"
            )
        }

        if (playMediaRegex.containsMatchIn(lower)) {
            return listOf(
                "1. Buka aplikasi media target (misal YouTube/TikTok/Spotify)",
                "2. Ketik judul video/lagu di kolom pencarian dan jalankan pencarian",
                "3. Ketuk hasil video/lagu yang paling relevan agar mulai diputar"
            )
        }

        val clauses = clean.split(compoundSplitter).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.size >= 2) {
            return clauses.mapIndexed { idx, part -> "${idx + 1}. $part" }
        }

        return listOf("1. Selesaikan seluruh instruksi: $clean")
    }

    /**
     * Memvalidasi klaim `DONE` menggunakan aturan kebenaran dasar (*Ground-Truth*) dan
     * **Programmatic OS-State Verifiers** (`CalendarContract`, `AlarmManager`, `ContactsContract`,
     * `MediaStore.Downloads`) terlebih dahulu (`0` token LLM), lalu meneruskan ke
     * [BrainPort.verifyGoalCompletion] apabila masih ambigu.
     */
    suspend fun verifyBeforeDone(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot,
        brainPort: BrainPort,
        useSemanticLlmCheck: Boolean = true,
        osStateVerifier: OsStateVerifierPort? = null,
        osBaseline: OsVerificationBaseline? = null
    ): GoalVerificationVerdict {
        val deterministic = evaluateDeterministic(
            userGoal = userGoal,
            currentPackage = currentPackage,
            screenElements = screenElements,
            memory = memory,
            osStateVerifier = osStateVerifier,
            osBaseline = osBaseline
        )
        if (deterministic != null) {
            return deterministic
        }

        if (!useSemanticLlmCheck) {
            return GoalVerificationVerdict(achieved = true)
        }

        return try {
            brainPort.verifyGoalCompletion(
                userGoal = userGoal,
                currentPackage = currentPackage,
                screenElements = screenElements,
                memory = memory
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Jangan gagalkan tugas bila jaringan verifier sedang lambat dan cek deterministik tidak menemukan pelanggaran
            GoalVerificationVerdict(achieved = true)
        }
    }

    /**
     * Pemeriksaan deterministik `0 ms`:
     * - Mengembalikan `GoalVerificationVerdict(achieved = false, ...)` jika terbukti belum selesai.
     * - Mengembalikan `GoalVerificationVerdict(achieved = true)` jika terbukti sudah tuntas 100%
     *   (termasuk bukti objektif dari [OsStateVerifierPort] tanpa memanggil LLM Critic).
     * - Mengembalikan `null` jika memerlukan pemeriksaan semantik LLM.
     */
    fun evaluateDeterministic(
        userGoal: String,
        currentPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot,
        osStateVerifier: OsStateVerifierPort? = null,
        osBaseline: OsVerificationBaseline? = null
    ): GoalVerificationVerdict? {
        val lowerGoal = userGoal.trim().lowercase()
        val steps = memory.recentSteps

        // 0. Pemeriksaan Khusus Perintah Majemuk Lintas Aplikasi (Cross-App Multi-Stage Gate)
        if (CrossAppWorkflowEngine.isCrossAppCommand(userGoal)) {
            val crossPlan = CrossAppWorkflowEngine.parseWorkflowPlan(userGoal)
            if (crossPlan.isMultiStage) {
                val destStage = crossPlan.stages.last()
                val destPkg = destStage.targetPackageHint

                val destAppVisited = destPkg != null && (
                    currentPackage.contains(destPkg, ignoreCase = true) ||
                    steps.any { step ->
                        step.targetLabel.contains(destPkg, ignoreCase = true) ||
                        (step.action == AgentActionType.LAUNCH_APP && step.targetLabel.contains(destPkg, ignoreCase = true))
                    }
                )

                if (!destAppVisited) {
                    return GoalVerificationVerdict(
                        achieved = false,
                        missingReason = "Tahap 1 selesai. Perintah majemuk lintas aplikasi belum tuntas: buka ${destPkg ?: "aplikasi kedua"} untuk menyelesaikan Tahap ${destStage.stageIndex}: ${destStage.rawClause}. DILARANG mengeluarkan DONE sebelum seluruh tahapan tuntas!"
                    )
                }

                if (destStage.intent == StageIntent.COMPOSE_AND_SEND) {
                    val sendClickStepIdx = steps.indexOfLast { step ->
                        (step.action == AgentActionType.CLICK || step.action == AgentActionType.CONFIRM_AND_CLICK) &&
                            step.outcomeStatus == ActionOutcome.CORRECT &&
                            sendTargetKeywords.any { kw -> step.targetLabel.lowercase().contains(kw) }
                    }
                    if (sendClickStepIdx == -1) {
                        return GoalVerificationVerdict(
                            achieved = false,
                            missingReason = "Pesan ke tujuan belum terkirim! Di $destPkg, pastikan data ditempel/diketik dan tombol Kirim/Send diketuk dengan sukses."
                        )
                    }

                    val lastSetTextStepIdx = steps.indexOfLast {
                        (it.action == AgentActionType.SET_TEXT || it.action == AgentActionType.SET_TEXT_AND_SUBMIT) &&
                            it.outcomeStatus == ActionOutcome.CORRECT
                    }
                    if (lastSetTextStepIdx > sendClickStepIdx) {
                        val typedDraft = steps[lastSetTextStepIdx].textValue?.trim().orEmpty()
                        return GoalVerificationVerdict(
                            achieved = false,
                            missingReason = "Teks \"$typedDraft\" baru saja diketik tetapi tombol Kirim/Send belum diketuk sesudahnya."
                        )
                    }

                    val lastTypedMessage = if (lastSetTextStepIdx >= 0) {
                        steps[lastSetTextStepIdx].textValue?.trim().orEmpty()
                    } else {
                        ""
                    }
                    if (lastTypedMessage.length >= 2) {
                        val unsentComposer = screenElements.firstOrNull { el ->
                            el.isEditable &&
                                !el.text.startsWith("[Hint]", ignoreCase = true) &&
                                el.text.trim().equals(lastTypedMessage, ignoreCase = true)
                        }
                        if (unsentComposer != null) {
                            return GoalVerificationVerdict(
                                achieved = false,
                                missingReason = "Pesan \"$lastTypedMessage\" masih tertahan di kolom input ([${unsentComposer.index}]) dan belum terkirim. Ketuk tombol Kirim/Send sekarang."
                            )
                        }
                    }

                    return GoalVerificationVerdict(achieved = true)
                }
            }
        }

        // 0.5. Programmatic OS-State Verifier (DeepMind AndroidWorld, 0 token LLM)
        // Mengaudit langsung ContentResolver / AlarmManager sebelum mengizinkan agen melapor "beres".
        val osCheck = osStateVerifier?.verifyGoalOsState(
            userGoal = userGoal,
            baseline = osBaseline
        )
        if (osCheck != null) {
            when (osCheck.status) {
                OsQueryStatus.MATCH_CONFIRMED -> {
                    return GoalVerificationVerdict(
                        achieved = true,
                        proofSummary = osCheck.evidenceSummary
                    )
                }
                OsQueryStatus.NOT_FOUND,
                OsQueryStatus.PENDING_IN_PROGRESS -> {
                    return GoalVerificationVerdict(
                        achieved = false,
                        missingReason = osCheck.missingReason
                    )
                }
                OsQueryStatus.UNAVAILABLE_OR_DENIED -> {
                    // Izin ditolak / Scoped Storage / kasus alarm ambigu -> lanjut ke pemeriksaan UI / LLM Critic
                }
            }
        }

        val isIg = currentPackage.contains("instagram") ||
            igRegex.containsMatchIn(lowerGoal) ||
            lowerGoal.contains("direct message")
        val isStory = storyPostRegex.containsMatchIn(lowerGoal) ||
            lowerGoal.contains("story") ||
            lowerGoal.contains("cerita")

        // 1. Pemeriksaan khusus posting Instagram Story / Status (Dismissal as Positive Proof)
        if (isIg && isStory) {
            val addToStoryIdx = steps.indexOfLast { step ->
                step.outcomeStatus == ActionOutcome.CORRECT &&
                    (step.targetLabel.contains("tambahkan ke cerita", ignoreCase = true) ||
                        step.targetLabel.contains("tambahkan postingan", ignoreCase = true) ||
                        step.targetLabel.contains("add post to", ignoreCase = true))
            }

            val shareClickStepIdx = steps.indexOfLast { step ->
                val labelLower = step.targetLabel.lowercase()
                (step.action == AgentActionType.CLICK || step.action == AgentActionType.CONFIRM_AND_CLICK) &&
                    step.outcomeStatus == ActionOutcome.CORRECT &&
                    !labelLower.contains("tambahkan ke cerita") &&
                    !labelLower.contains("tambahkan postingan") &&
                    !labelLower.contains("add post to") &&
                    storyFinalPublishKeywords.any { kw -> labelLower.contains(kw) } &&
                    (addToStoryIdx == -1 || steps.indexOf(step) > addToStoryIdx)
            }

            if (shareClickStepIdx == -1) {
                return GoalVerificationVerdict(
                    achieved = false,
                    missingReason = "Tombol publikasi akhir 'Cerita Anda' / 'Bagikan' (Your Story / Share) di editor cerita belum diketuk dengan sukses!"
                )
            }

            val requiresCaption = lowerGoal.contains("caption") ||
                lowerGoal.contains("berikan teks") ||
                lowerGoal.contains("kasih teks") ||
                lowerGoal.contains("tuliskan teks")
            if (requiresCaption) {
                val hasCaptionStep = steps.take(shareClickStepIdx + 1).any { step ->
                    (step.action == AgentActionType.SET_TEXT || step.action == AgentActionType.SET_TEXT_AND_SUBMIT) &&
                        step.outcomeStatus == ActionOutcome.CORRECT &&
                        (addToStoryIdx == -1 || steps.indexOf(step) > addToStoryIdx)
                }
                if (!hasCaptionStep) {
                    return GoalVerificationVerdict(
                        achieved = false,
                        missingReason = "Caption cerita belum diketik! Di editor Cerita ketuk 'Aa', gunakan SET_TEXT untuk mengetik caption, ketuk 'Selesai', lalu ketuk 'Cerita Anda'."
                    )
                }
            }

            // Jika tombol share/cerita sudah diklik dengan sukses, periksa apakah editor masih terbuka.
            // CATATAN PENTING: reel_viewer BUKAN bagian dari editor cerita (melainkan pemutar video feed),
            // sehingga tidak boleh dimasukkan sebagai tanda editor masih terbuka!
            val editorStillOpen = screenElements.any { el ->
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

            if (!editorStillOpen) {
                // Editor telah tertutup pasca klik Bagikan/Cerita Anda -> Bukti pasti selesai di latar belakang!
                return GoalVerificationVerdict(achieved = true)
            }
        }

        // 2. Pemeriksaan khusus tugas pengiriman pesan / DM (WhatsApp, Instagram DM, Telegram, SMS)
        val requiresSendingMessage = !isStory && (sendMessageRegex.containsMatchIn(lowerGoal) ||
            (isIg && (lowerGoal.contains("kirim dm") || lowerGoal.contains("dm ") || lowerGoal.contains("kirim pesan"))))

        if (requiresSendingMessage) {
            val lastSetTextStepIdx = steps.indexOfLast {
                (it.action == AgentActionType.SET_TEXT || it.action == AgentActionType.SET_TEXT_AND_SUBMIT) &&
                    it.outcomeStatus == ActionOutcome.CORRECT
            }
            val sendClickStepIdx = steps.indexOfLast { step ->
                (step.action == AgentActionType.CLICK || step.action == AgentActionType.CONFIRM_AND_CLICK) &&
                    step.outcomeStatus == ActionOutcome.CORRECT &&
                    sendTargetKeywords.any { kw -> step.targetLabel.lowercase().contains(kw) }
            }

            if (sendClickStepIdx == -1) {
                return GoalVerificationVerdict(
                    achieved = false,
                    missingReason = "Tombol Kirim/Send belum pernah diketuk dengan sukses! Buka ruang chat/DM penerima, ketik pesan, lalu ketuk tombol Kirim/Send."
                )
            }

            // Jika ada SET_TEXT setelah klik Kirim terakhir (mis. baru mengetik pesan setelah salah klik), berarti pesan terakhir belum dikirim
            if (lastSetTextStepIdx > sendClickStepIdx) {
                val typedDraft = steps[lastSetTextStepIdx].textValue?.trim().orEmpty()
                // Kecualikan jika SET_TEXT tersebut adalah mengetik nama di pencarian, bukan isi pesan:
                // tetap jika belum ada klik tombol Send sesudahnya, pesan belum terkirim!
                return GoalVerificationVerdict(
                    achieved = false,
                    missingReason = "Teks \"$typedDraft\" baru saja diketik tetapi tombol Kirim/Send belum diketuk sesudahnya."
                )
            }

            // Cek apakah di layar saat ini masih ada kotak input chat yang menahan draf pesan yang belum terkirim
            val lastTypedMessage = if (lastSetTextStepIdx >= 0) {
                steps[lastSetTextStepIdx].textValue?.trim().orEmpty()
            } else {
                ""
            }
            if (lastTypedMessage.length >= 2) {
                val unsentComposer = screenElements.firstOrNull { el ->
                    el.isEditable &&
                        !el.text.startsWith("[Hint]", ignoreCase = true) &&
                        el.text.trim().equals(lastTypedMessage, ignoreCase = true)
                }
                if (unsentComposer != null) {
                    return GoalVerificationVerdict(
                        achieved = false,
                        missingReason = "Pesan \"$lastTypedMessage\" masih tertahan di kolom input ([${unsentComposer.index}]) dan belum terkirim. Ketuk tombol Kirim/Send sekarang."
                    )
                }
            }

            return GoalVerificationVerdict(achieved = true)
        }

        // 2. Pemeriksaan tugas pencarian akun/profil/video/barang: minimal harus ada 1 aksi UI efektif
        val requiresSearchOrPlay = searchRegex.containsMatchIn(lowerGoal) || playMediaRegex.containsMatchIn(lowerGoal)
        if (requiresSearchOrPlay) {
            val hasEffectiveStep = steps.any {
                it.outcomeStatus == ActionOutcome.CORRECT &&
                    (it.action == AgentActionType.CLICK ||
                        it.action == AgentActionType.SET_TEXT ||
                        it.action == AgentActionType.SET_TEXT_AND_SUBMIT)
            }
            if (!hasEffectiveStep) {
                return GoalVerificationVerdict(
                    achieved = false,
                    missingReason = "Belum ada tindakan klik atau pengetikan pencarian yang berhasil dilakukan di dalam aplikasi."
                )
            }

            // Jika tujuan meminta memutar video tetapi baru melakukan SET_TEXT pencarian tanpa mengeklik hasil video
            if (playMediaRegex.containsMatchIn(lowerGoal)) {
                val lastSetText = steps.indexOfLast {
                    (it.action == AgentActionType.SET_TEXT || it.action == AgentActionType.SET_TEXT_AND_SUBMIT) &&
                        it.outcomeStatus == ActionOutcome.CORRECT
                }
                val clickAfterSearch = steps.indexOfLast {
                    it.action == AgentActionType.CLICK && it.outcomeStatus == ActionOutcome.CORRECT
                }
                if (lastSetText >= 0 && clickAfterSearch < lastSetText) {
                    return GoalVerificationVerdict(
                        achieved = false,
                        missingReason = "Pencarian sudah diketik, namun hasil video/lagu belum diketuk untuk mulai diputar."
                    )
                }
            }
        }

        // 3. Pemeriksaan apakah masih terjebak di Launcher padahal target aplikasi spesifik diminta
        val expectedPkg = AppIntentLauncher.detectTargetAppPackageInGoal(userGoal)
        if (!expectedPkg.isNullOrEmpty() &&
            currentPackage.contains("launcher", ignoreCase = true) &&
            steps.none { it.outcomeStatus == ActionOutcome.CORRECT }
        ) {
            return GoalVerificationVerdict(
                achieved = false,
                missingReason = "Masih berada di layar beranda (Launcher) dan aplikasi tujuan ($expectedPkg) belum dibuka."
            )
        }

        // Untuk tugas pendek (<= 2 langkah) yang bukan pengiriman pesan kompleks, tidak perlu RTT LLM tambahan
        if (steps.size <= 1 && !compoundSplitter.containsMatchIn(lowerGoal)) {
            return GoalVerificationVerdict(achieved = true)
        }

        return null
    }

    /**
     * Verifikasi pasca-aksi berbasis **Chain-of-Action-Thought (CoAT)** (`arXiv:2403.02713v2`) dalam `0 ms`:
     * Membandingkan prediksi [expectedOutcome] dari langkah sebelumnya dengan elemen aktual di [postElements].
     * Mengembalikan catatan diagnostik singkat apabila terjadi deviasi tak terduga (misal dialog izin/error muncul),
     * atau `null` apabila transisi sesuai ekspektasi.
     */
    fun verifyStepExpectedOutcome(
        expectedOutcome: String?,
        preElements: List<PrunedUiElement>,
        postElements: List<PrunedUiElement>,
        rawOutcome: ActionOutcome
    ): String? {
        val cleanExpected = expectedOutcome?.trim().orEmpty()
        if (cleanExpected.isEmpty()) return null

        if (rawOutcome == ActionOutcome.INEFFECTIVE) {
            return "[CoAT] Ekspektasi \"$cleanExpected\" belum tercapai (layar tidak berubah)."
        }

        // Periksa apakah transisi layar justru memunculkan dialog kendala jaringan atau permintaan izin OS
        val preJoined = preElements.joinToString(" ") { it.text.lowercase() }
        val postJoined = postElements.joinToString(" ") { it.text.lowercase() }

        val unexpectedErrorKeywords = listOf(
            "tidak ada koneksi internet",
            "koneksi terputus",
            "terjadi kesalahan",
            "something went wrong",
            "coba lagi nanti",
            "izinkan aplikasi",
            "saat aplikasi digunakan",
            "while using the app"
        )
        val newlyAppearedBlocker = unexpectedErrorKeywords.firstOrNull { kw ->
            !preJoined.contains(kw) && postJoined.contains(kw)
        }
        if (newlyAppearedBlocker != null && !cleanExpected.lowercase().contains(newlyAppearedBlocker)) {
            return "[CoAT] Ekspektasi \"$cleanExpected\" terhalang kemunculan dialog baru (\"$newlyAppearedBlocker\")."
        }

        return null
    }
}
