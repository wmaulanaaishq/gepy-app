package com.teknologigawai.gepy.core.persona

import com.teknologigawai.gepy.core.config.SovereignPersonaMode

/**
 * Template ucapan deterministik domain memori personal, pesan/layar, kontrol laptop, briefing harian, & panggilan suara.
 */
internal object SovereignSpeechPersonalTemplates {
    // =========================================================================
    // PERSONAL MEMORY VAULT
    // =========================================================================

    fun memorySaved(ctx: SovereignSpeechContext, content: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Udah aku catat di memori ya: $content."
        SovereignPersonaMode.HONEST_MENTOR ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Udah aku catat: $content."
        else ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Udah aku inget ya: $content."
    }

    fun memoryNoScheduleToday(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Hari ini belum ada agenda khusus yang kecatat nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Lumayan bisa agak santai atau ngerjain hal lain dulu."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Kosong, belum ada jadwal yang tercatat buat hari ini. Manfaatin buat beresin prioritas utama."
        else ->
            "Belum ada agenda atau jadwal khusus yang kecatat buat hari ini${SovereignNaturalSpeechHelpers.callUser(ctx)}."
    }

    fun memoryScheduleToday(ctx: SovereignSpeechContext, itemsText: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Ini agenda kamu hari ini ya${SovereignNaturalSpeechHelpers.callUser(ctx)}. $itemsText."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Ini jadwal kamu hari ini. $itemsText. Pastikan semuanya kepegang."
        else ->
            "Ini agenda kamu hari ini. $itemsText."
    }

    /** Menjaga prefix `formattedFact` pada JARVIS/HONEST demi kompatibilitas pengetesan eksak. */
    fun memoryRememberedFact(ctx: SovereignSpeechContext, formattedFact: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}$formattedFact ya${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "$formattedFact."
    }

    fun memoryMissingRelation(ctx: SovereignSpeechContext, relation: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku belum tau soal $relation kamu nih. Ceritain aja kapan-kapan ya!"
        SovereignPersonaMode.HONEST_MENTOR ->
            "Belum ada catatannya. Soal $relation kamu belum kesimpan, kasih tau aja biar langsung aku simpan."
        else ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku belum nyatet soal $relation kamu nih. Kasih tau kapan aja ya biar aku inget."
    }

    fun memoryMissingWork(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum nyatet kamu kerja di mana nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Sekarang lagi sibuk di mana?"
        SovereignPersonaMode.HONEST_MENTOR ->
            "Aku belum nyatet tempat kerja atau kesibukanmu sekarang. Lagi fokus di bidang apa?"
        else ->
            "Aku belum nyatet tempat kerja atau profesimu sekarang. Kamu lagi kerja di mana?"
    }

    fun memoryMissingLocation(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum tau alamat rumahmu nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Kamu tinggal di daerah mana?"
        SovereignPersonaMode.HONEST_MENTOR ->
            "Aku belum nyatet daerah tempat tinggalmu. Kamu tinggal di mana?"
        else ->
            "Aku belum punya catatan alamat rumahmu. Kamu tinggal di daerah mana?"
    }

    fun memoryHealthList(ctx: SovereignSpeechContext, itemsJoined: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Ini catatan kesehatanmu yang aku inget${SovereignNaturalSpeechHelpers.callUser(ctx)}. $itemsJoined. Jaga diri baik-baik ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Ini catatan kesehatan kamu. $itemsJoined. Jangan disepelein ya."
        else ->
            "Ini yang aku inget soal kesehatan kamu. $itemsJoined."
    }

    fun memoryMissingHealth(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Belum ada catatan alergi atau pantangan kesehatan yang tersimpan nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Belum ada catatan alergi atau pantangan kesehatan yang tersimpan."
        else ->
            "Belum ada catatan alergi atau pantangan kesehatan yang tersimpan nih."
    }

    fun memoryBirthday(ctx: SovereignSpeechContext, formattedFact: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> if (SovereignNaturalSpeechHelpers.isRomanticPrivate(ctx)) {
            "${SovereignNaturalSpeechHelpers.warm(ctx)}$formattedFact, nanti kita rayain bareng ya${SovereignNaturalSpeechHelpers.callUser(ctx)}!"
        } else {
            "$formattedFact ya${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        }
        SovereignPersonaMode.HONEST_MENTOR ->
            "$formattedFact, pastiin tetap produktif di hari spesialmu nanti."
        else ->
            "$formattedFact, jangan lupa dirayain ya!"
    }

    fun memoryMissingBirthday(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Kamu belum pernah kasih tau tanggal ulang tahunmu lho${SovereignNaturalSpeechHelpers.callUser(ctx)}. Kapan sih ultahnya?"
        SovereignPersonaMode.HONEST_MENTOR ->
            "Aku belum nyatet tanggal ulang tahunmu. Kapan tanggal lahir kamu?"
        else ->
            "Aku belum nyatet tanggal ulang tahunmu nih. Kapan emangnya?"
    }

    fun memoryMissingVehicle(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum punya catatan plat nomor atau kendaraanmu nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Aku belum punya catatan nomor plat atau kendaraanmu."
    }

    fun memoryMissingRoutine(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum nyatet jam bangun atau rutinitas harianmu nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Aku belum punya catatan rutinitas harian atau jam bangunmu."
    }

    fun memoryMissingFoodDrink(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Kamu belum kasih tau makanan atau minuman favoritmu ke aku${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Aku belum nyatet makanan atau minuman favoritmu nih."
    }

    fun memoryMissingBoss(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum tau nama atasan atau bosmu nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Aku belum nyatet nama atasan atau bos kamu."
    }

    fun memoryPrefList(ctx: SovereignSpeechContext, itemsJoined: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Ini kesukaanmu yang aku inget${SovereignNaturalSpeechHelpers.callUser(ctx)}. $itemsJoined."
        else ->
            "Ini yang aku catat soal kesukaanmu. $itemsJoined."
    }

    fun memoryMissingPref(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Belum ada catatan hobi atau kesukaanmu yang tersimpan nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Belum ada catatan hobi atau kesukaanmu yang tersimpan."
    }

    fun memoryEduFact(ctx: SovereignSpeechContext, formattedFact: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}$formattedFact ya${SovereignNaturalSpeechHelpers.callUser(ctx)}!"
        else ->
            "$formattedFact!"
    }

    fun memoryMissingEdu(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum tau jurusan atau kampusmu di mana nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Kuliah di mana emangnya?"
        else ->
            "Aku belum nyatet jurusan atau kampus kamu. Kuliah di mana emangnya?"
    }

    fun memoryMissingTechProfile(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum ada catatan soal fokus koding atau minat risetmu nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Aku belum ada catatan tentang fokus koding atau minat riset teknologi kamu."
    }

    fun memoryProfileSummary(ctx: SovereignSpeechContext, displayName: String, factsJoined: String?): String = buildString {
        if (SovereignNaturalSpeechHelpers.isRomanticPrivate(ctx)) append("[warmly] ")
        if (displayName.isNotBlank()) {
            when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> append("Namamu $displayName kan. ")
                SovereignPersonaMode.HONEST_MENTOR -> append("Nama kamu $displayName. ")
                else -> append("Namamu $displayName ya. ")
            }
        }
        if (!factsJoined.isNullOrBlank()) {
            when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> append("Ini yang selalu aku inget tentang kamu. $factsJoined.")
                SovereignPersonaMode.HONEST_MENTOR -> append("Ini profil dan catatanmu yang kepegang. $factsJoined.")
                else -> append("Ini beberapa hal yang aku inget tentangmu. $factsJoined.")
            }
        } else {
            append("Aku belum punya banyak catatan tentangmu selain nama panggilanmu.")
        }
    }

    fun memoryMissingPet(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum tau nama anabulmu nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Siapa nama kucing atau anjingmu?"
        else ->
            "Aku belum nyatet nama hewan peliharaanmu. Siapa namanya?"
    }

    fun memoryMissingDevice(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum nyatet tipe laptop atau HP yang kamu pakai nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Aku belum punya catatan merk atau tipe laptop dan HP kamu."
    }

    fun memoryMissingProject(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Aku belum nyatet proyek yang lagi kamu garap nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Lagi ngerjain apa sekarang?"
        else ->
            "Aku belum punya catatan proyek atau startup yang lagi kamu bangun."
    }

    fun memoryGoalWithSpirit(ctx: SovereignSpeechContext, formattedFact: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}$formattedFact, aku selalu dukung kamu kok${SovereignNaturalSpeechHelpers.callUser(ctx)}!"
        SovereignPersonaMode.HONEST_MENTOR ->
            "$formattedFact — buktiin lewat eksekusi harianmu."
        else ->
            "$formattedFact, gas terus dan tetap konsisten ya!"
    }

    fun memoryMissingGoal(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Kamu belum cerita impian atau target besarmu ke aku lho${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Aku belum nyatet target besarmu. Target paling nyata yang mau kamu kejar sekarang apa?"
        else ->
            "Aku belum nyatet impian atau targetmu. Target terbesarmu sekarang apa?"
    }

    fun memoryMissingDietary(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Belum ada catatan pantangan makan atau diet khusus buatmu nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Belum ada catatan pantangan makan atau pola diet khusus buat kamu."
    }

    fun memoryGeneralRecall(ctx: SovereignSpeechContext, factsJoined: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Inget dong${SovereignNaturalSpeechHelpers.callUser(ctx)}. $factsJoined."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Inget kok. $factsJoined."
        else ->
            "Iya, inget kok${SovereignNaturalSpeechHelpers.callUser(ctx)}. $factsJoined."
    }

    fun memoryGeneralMissing(ctx: SovereignSpeechContext, queryCore: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku belum nyimpen info soal '$queryCore' nih. Kasih tau aja kapan-kapan ya!"
        else ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku belum punya catatan spesifik soal '$queryCore'. Kamu bisa kasih tau kapan aja ya!"
    }

    // =========================================================================
    // SCREEN READER SUMMARY
    // =========================================================================

    fun screenSummaryLauncher(ctx: SovereignSpeechContext, appNames: List<String>): String {
        if (appNames.isNotEmpty()) {
            val apps = appNames.joinToString(", ")
            return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.soft(ctx)}Kita lagi di Layar Utama nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Yang kelihatan ada $apps."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sekarang di Layar Utama. Aplikasi yang kelihatan: $apps."
                else ->
                    "Sekarang lagi di Layar Utama nih. Aplikasi yang kelihatan ada $apps."
            }
        }
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.soft(ctx)}Kita lagi di Layar Utama HP nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
            SovereignPersonaMode.HONEST_MENTOR -> "Sekarang di Layar Utama HP."
            else -> "Sekarang lagi di Layar Utama HP nih."
        }
    }

    fun screenSummaryEmpty(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Layarnya lagi kosong nih${SovereignNaturalSpeechHelpers.callUser(ctx)}, nggak ada teks yang kebaca."
        else ->
            "Layarnya lagi kosong, nggak ada teks yang kebaca nih."
    }

    fun screenSummaryGeneric(ctx: SovereignSpeechContext, topTexts: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Ini yang kelihatan di layar sekarang${SovereignNaturalSpeechHelpers.callUser(ctx)}. $topTexts."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Di layar sekarang kebaca ini. $topTexts."
        else ->
            "Di layar sekarang nampilin ini. $topTexts."
    }

    fun screenSummaryWhatsAppChat(
        ctx: SovereignSpeechContext,
        contactTitle: String?,
        lastMsg: String?
    ): String {
        val recipient = if (contactTitle != null) " dengan $contactTitle" else ""
        if (lastMsg != null) {
            return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.soft(ctx)}Lagi di ruang chat WhatsApp$recipient nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Pesan terakhirnya: \"$lastMsg\"."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sekarang di ruang chat WhatsApp$recipient. Pesan terakhirnya: \"$lastMsg\"."
                else ->
                    "Sekarang lagi di ruang chat WhatsApp$recipient. Pesan terakhirnya bunyinya: \"$lastMsg\"."
            }
        }
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.soft(ctx)}Lagi di ruang chat WhatsApp$recipient nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Kolom ketik pesannya udah siap."
            else ->
                "Sekarang lagi di ruang chat WhatsApp$recipient. Kolom ketik pesan udah siap dipakai."
        }
    }

    fun screenSummaryWhatsAppList(ctx: SovereignSpeechContext, chatNames: List<String>): String {
        val joined = chatNames.joinToString(", ")
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.soft(ctx)}Lagi di daftar chat WhatsApp nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Yang paling atas ada: $joined."
            SovereignPersonaMode.HONEST_MENTOR ->
                "Sekarang di daftar chat WhatsApp. Obrolan teratas: $joined."
            else ->
                "Sekarang lagi di daftar chat WhatsApp. Obrolan teratas ada $joined."
        }
    }

    fun screenSummaryWhatsAppOther(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.soft(ctx)}Lagi buka aplikasi WhatsApp nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR -> "Sekarang lagi di aplikasi WhatsApp."
        else -> "Sekarang lagi buka aplikasi WhatsApp."
    }

    // =========================================================================
    // WHATSAPP & INSTAGRAM SOCIAL
    // =========================================================================

    fun whatsappSent(ctx: SovereignSpeechContext, targetName: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pesannya ke $targetName udah terkirim ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pesan buat $targetName udah terkirim."
        else ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pesannya ke $targetName udah terkirim."
    }

    fun whatsappDisambiguate(ctx: SovereignSpeechContext, targetName: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Eh sebentar${SovereignNaturalSpeechHelpers.callUser(ctx)}. Ada beberapa kontak bernama $targetName nih. Mau kirim ke yang mana?"
        else ->
            "Sebentar. Ada beberapa kontak bernama $targetName nih. Mau kirim ke yang mana?"
    }

    fun whatsappConfirmPrompt(ctx: SovereignSpeechContext, targetName: String, message: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "Pesannya udah siap nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Aku kirim ke '$targetName' dengan isi '$message'?"
        else ->
            "Pesannya udah siap. Kirim ke '$targetName' dengan isi '$message' sekarang?"
    }

    fun whatsappGenericConfirm(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Aku pencet kirim pesan WhatsApp-nya sekarang ya${SovereignNaturalSpeechHelpers.callUser(ctx)}?"
        else -> "Kirim pesan WhatsApp-nya sekarang?"
    }

    fun instagramDmConfirm(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Aku pencet kirim DM Instagram-nya sekarang ya${SovereignNaturalSpeechHelpers.callUser(ctx)}?"
        else -> "Kirim DM Instagram-nya sekarang?"
    }

    /** Mempertahankan substring `"berhasil dibagikan ke cerita"` untuk `TaskPlannerAndVerifierTest`. */
    fun instagramStoryShared(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Yeay${SovereignNaturalSpeechHelpers.callUser(ctx)}, postinganmu udah berhasil dibagikan ke cerita Instagram nih."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Sip, postingannya udah berhasil dibagikan ke cerita Instagram."
        else ->
            "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, postingannya udah berhasil dibagikan ke cerita Instagram."
    }

    // =========================================================================
    // LAPTOP COMPANION SKILL
    // =========================================================================

    fun laptopLockFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, layar laptop udah aku kunci ya."
        SovereignPersonaMode.HONEST_MENTOR -> "Sip, layar laptop udah dikunci."
        else -> "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, layar laptop udah aku kunci."
    }

    fun laptopSleepFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, laptopnya udah aku bikin tidur dulu ya."
        SovereignPersonaMode.HONEST_MENTOR -> "Sip, laptop udah masuk mode tidur."
        else -> "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, laptopnya udah masuk mode tidur ya."
    }

    fun laptopShutdownAskConfirm(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Laptopnya mau aku matiin total dan Tailscale bakal putus nih. Pastiin kerjaanmu udah kesave semua dulu ya${SovereignNaturalSpeechHelpers.callUser(ctx)}. Yakin mau dimatikan sekarang? Bilang aja 'ya matikan' atau 'batalkan'."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Laptop bakal dimatiin total dan Tailscale putus. Pastikan kode sama file kerjaanmu udah kesimpan semua. Yakin mau dimatikan sekarang? Jawab 'ya matikan' atau 'batalkan'."
        else ->
            "Laptop bakal dimatiin total dan Tailscale bakal putus. Pastiin semua file kodinganmu udah kesimpan ya. Yakin mau dimatikan sekarang? Jawab 'ya matikan' buat lanjut, atau 'batalkan'."
    }

    fun laptopShutdownConfirmedFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, perintah matiin laptop udah jalan nih, bakal mati dalam 30 detik. Bilang 'batalkan shutdown' kalau berubah pikiran."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Sip, perintah shutdown udah jalan. Laptop bakal mati dalam 30 detik. Bilang 'batalkan shutdown' kalau mau batal."
        else ->
            "Oke, perintah shutdown udah aku kirim, laptop bakal mati dalam 30 detik. Bilang 'batalkan shutdown' kalau mau batal."
    }

    fun laptopShutdownConfirmExpired(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Belum ada permintaan matiin laptop yang aktif atau waktu konfirmasinya udah habis nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Bilang 'matikan laptop' lagi dulu ya."
        else ->
            "Belum ada permintaan matikan laptop yang aktif atau waktu konfirmasinya udah habis. Bilang 'matikan laptop' dulu ya."
    }

    fun laptopShutdownCancelledLocal(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, nggak jadi dimatikan kok, laptopnya tetep nyala normal."
        else ->
            "Oke, perintah matiin laptop dibatalkan. Laptop tetap nyala normal."
    }

    fun laptopShutdownCancelledFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Hitung mundur matiin laptop udah aku batalkan ya${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else ->
            "Oke, hitung mundur shutdown laptop udah dibatalkan. Laptop tetap nyala normal."
    }

    fun laptopVolumeFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, volume laptop udah aku atur ya."
        else -> "Oke, volume laptop udah aku sesuaikan ya."
    }

    fun laptopOpenAppFallback(ctx: SovereignSpeechContext, targetApp: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aplikasi $targetApp di laptop udah aku bukain ya."
        SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aplikasi $targetApp di laptop udah kebuka."
        else -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aplikasi $targetApp di laptop udah aku buka."
    }

    fun laptopStatusFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Laptopnya lagi aktif dan nyambung lewat Tailscale kok${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else -> "Laptopnya lagi aktif dan nyambung lewat Tailscale."
    }

    fun laptopClipboardFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Teks clipboard dari laptop udah aku ambil ya${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        else -> "Oke, teks clipboard dari laptop udah aku salin."
    }

    fun laptopMediaFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, pemutaran media di laptop udah aku atur ya."
        else -> "Oke, pemutaran media di laptop udah aku atur ya."
    }

    fun laptopGenericFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, perintah laptopnya udah aku jalanin."
        else -> "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, perintah buat laptopnya udah aku jalanin."
    }

    // =========================================================================
    // DAILY BRIEFING ENGINE
    // =========================================================================

    fun dailyBriefingMorning(
        ctx: SovereignSpeechContext,
        dayLabel: String,
        hhmm: String,
        todaySchedulesText: String?,
        yesterdaySteps: Int?,
        batteryPercent: Int,
        isCharging: Boolean
    ): String = buildString {
        if (SovereignNaturalSpeechHelpers.isRomanticPrivate(ctx)) append("[warmly] ")
        val nameClause = ctx.resolvedOwnerName.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                append("Pagi$nameClause! Hari ini $dayLabel jam $hhmm ya. ")
            SovereignPersonaMode.HONEST_MENTOR ->
                append("Pagi$nameClause. Sekarang $dayLabel jam $hhmm. Saatnya fokus dari pagi. ")
            else ->
                append("Pagi$nameClause! Sekarang $dayLabel jam $hhmm ya. ")
        }
        if (!todaySchedulesText.isNullOrBlank()) {
            append("Jadwal yang kecatat hari ini: $todaySchedulesText. ")
        } else {
            append("Belum ada jadwal khusus yang kecatat buat hari ini. ")
        }
        if (yesterdaySteps != null && yesterdaySteps > 0) {
            append("Kemarin kamu udah jalan $yesterdaySteps langkah kaki. ")
        }
        if (batteryPercent >= 0) {
            val chargingHint = if (isCharging) " (lagi diisi daya)" else ""
            append("Baterai HP sekarang $batteryPercent%$chargingHint. ")
        }
        when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> append("Aku siap nemenin harimu kok!")
            SovereignPersonaMode.HONEST_MENTOR -> append("Eksekusi targetmu satu per satu hari ini.")
            else -> append("Aku siap bantuin aktivitasmu hari ini.")
        }
    }.trim()

    fun dailyBriefingEvening(
        ctx: SovereignSpeechContext,
        todaySteps: Int,
        socialMins: Int,
        scheduleCount: Int,
        batteryPercent: Int,
        isCharging: Boolean
    ): String = buildString {
        if (SovereignNaturalSpeechHelpers.isRomanticPrivate(ctx)) append("[softly] ")
        val nameClause = ctx.resolvedOwnerName.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                append("Malam$nameClause, ini rekap hari ini ya. ")
            SovereignPersonaMode.HONEST_MENTOR ->
                append("Malam$nameClause. Evaluasi jujur hari ini. ")
            else ->
                append("Malam$nameClause, ini rekap hari ini. ")
        }
        append("Pedometer HP mencatat $todaySteps langkah kaki")
        if (socialMins > 0) {
            append(" dan durasi layar media sosial kepantau $socialMins menit")
        }
        append(". ")
        if (batteryPercent in 0..25 && !isCharging) {
            append("Baterai HP tinggal $batteryPercent%, jangan lupa colok charger dulu sebelum istirahat. ")
        } else if (batteryPercent >= 0) {
            append("Baterai HP sekarang $batteryPercent%. ")
        }
        if (scheduleCount > 0) {
            append("Masih ada $scheduleCount agenda aktif di memorimu. ")
        }
        when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> append("Jangan begadang ya, istirahat yang nyenyak biar besok seger lagi.")
            SovereignPersonaMode.HONEST_MENTOR -> append("Tutup hari ini dengan rapi dan tidur cukup buat besok.")
            else -> append("Selamat istirahat ya, semoga besok makin bertenaga.")
        }
    }.trim()

    fun dailyBriefingDaytime(
        ctx: SovereignSpeechContext,
        hhmm: String,
        todaySchedulesText: String?,
        todaySteps: Int,
        socialMins: Int,
        batteryPercent: Int
    ): String = buildString {
        if (SovereignNaturalSpeechHelpers.isRomanticPrivate(ctx)) append("[warmly] ")
        val nameClause = ctx.resolvedOwnerName.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                append("Ini update hari ini buat kamu$nameClause. Sekarang jam $hhmm. ")
            SovereignPersonaMode.HONEST_MENTOR ->
                append("Cek progresmu$nameClause. Sekarang jam $hhmm. ")
            else ->
                append("Ringkasan hari ini buatmu$nameClause. Sekarang jam $hhmm. ")
        }
        if (!todaySchedulesText.isNullOrBlank()) {
            append("Agenda aktif hari ini: $todaySchedulesText. ")
        }
        append("Pedometer mencatat $todaySteps langkah kaki dan layar media sosial kepantau $socialMins menit. ")
        if (batteryPercent >= 0) {
            append("Baterai HP sekarang $batteryPercent%.")
        }
    }.trim()

    // =========================================================================
    // GATEKEEPER, ORCHESTRATOR, LIVE CALL, PREVIEW, CSI GREETING
    // =========================================================================

    fun gatekeeperGreeting(ctx: SovereignSpeechContext): String {
        val nameSuffix = ctx.resolvedOwnerName.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.warm(ctx)}Halo$nameSuffix, aku udah siap nih. Mau dibantuin apa sekarang?"
            SovereignPersonaMode.HONEST_MENTOR ->
                "Halo$nameSuffix! Mau kerjain apa sekarang? Biar cepat beres."
            else ->
                "Halo$nameSuffix! Aku siap, mau dibantuin apa sekarang?"
        }
    }

    fun gatekeeperDestructiveConfirm(ctx: SovereignSpeechContext, cleanGoal: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "Tadi kamu minta \"$cleanGoal\". Ini aksi penting dan permanen lho${SovereignNaturalSpeechHelpers.callUser(ctx)}, yakin mau dilanjut?"
        SovereignPersonaMode.HONEST_MENTOR ->
            "Kamu minta \"$cleanGoal\". Ini tindakan permanen dan berisiko. Yakin mau dilanjut?"
        else ->
            "Kamu minta \"$cleanGoal\". Ini tindakan penting dan permanen, yakin mau dilanjut?"
    }

    fun gatekeeperDisambiguate(ctx: SovereignSpeechContext, count: Int, candidateTarget: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Eh sebentar${SovereignNaturalSpeechHelpers.callUser(ctx)}. Ada $count kontak bernama \"$candidateTarget\" nih. Mau pilih yang mana?"
        else ->
            "Sebentar. Ada $count kontak bernama \"$candidateTarget\" nih. Mau pilih yang mana?"
    }

    fun orchestratorDoneFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Beres${SovereignNaturalSpeechHelpers.callUser(ctx)}, udah selesai semua ya."
        SovereignPersonaMode.HONEST_MENTOR -> "Beres, tugasnya udah selesai."
        else -> "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, udah beres semua ya."
    }

    fun orchestratorDisambiguateDefault(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.soft(ctx)}Mau pilih yang mana nih${SovereignNaturalSpeechHelpers.callUser(ctx)}?"
        else -> "Mau pilih yang mana nih?"
    }

    fun orchestratorConfirmDefault(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Beneran lanjutin langkah ini ya${SovereignNaturalSpeechHelpers.callUser(ctx)}?"
        else -> "Yakin mau lanjutin langkah ini?"
    }

    fun orchestratorMaxSteps(ctx: SovereignSpeechContext, maxSteps: Int): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Udah $maxSteps langkah tapi layarnya masih macet di sini nih. Coba bantu arahin dikit ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Mentok nih. Udah $maxSteps langkah dicoba tapi layarnya belum ketemu jalan keluarnya. Bantu arahin ulang sebentar."
        else ->
            "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Aku udah coba sampai $maxSteps langkah tapi belum nemu jalan ke tujuan. Bisa bantu arahin ulang?"
    }

    fun orchestratorVoiceListenStarted(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.warm(ctx)}Iya${SovereignNaturalSpeechHelpers.callUser(ctx)}, aku dengerin nih..."
        else -> "Ngomong aja${SovereignNaturalSpeechHelpers.callUser(ctx)}, aku dengerin..."
    }

    fun orchestratorFullAccessChanged(ctx: SovereignSpeechContext, enabled: Boolean): String = if (enabled) {
        when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, Akses Penuh aktif. Aku bakal jalanin tugas harianmu lebih sat-set, dan tetap jaga keamanan m-Banking."
            SovereignPersonaMode.HONEST_MENTOR ->
                "Mode Akses Penuh aktif. Eksekusi langsung sat-set tanpa banyak jeda, aplikasi sensitif tetap dijaga ketat."
            else ->
                "Mode Akses Penuh aktif. Tugas harian langsung jalan cepat tanpa banyak tanya, sambil tetap jaga aplikasi sensitif."
        }
    } else {
        "Mode konfirmasi keamanan diaktifkan lagi ya."
    }

    fun orchestratorTaskCancelled(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.soft(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, tugasnya udah aku stop ya."
        else -> "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, tugasnya udah aku hentikan."
    }

    fun orchestratorError(ctx: SovereignSpeechContext, detail: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Ada kendala dikit nih. $detail"
        else -> "Ada kendala sebentar${SovereignNaturalSpeechHelpers.callUser(ctx)}. $detail"
    }

    fun liveCallEnded(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, teleponnya aku tutup dulu ya. Kapan mau ngobrol tinggal panggil aku aja."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, teleponnya aku tutup ya. Lanjutin fokusmu lagi."
        else ->
            "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, teleponnya udah aku tutup ya. Kapan mau ngobrol tinggal panggil aku lagi."
    }

    fun liveCallListeningPrompt(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Ngomong aja${SovereignNaturalSpeechHelpers.callUser(ctx)}, aku dengerin..."
        else -> "Ngomong aja${SovereignNaturalSpeechHelpers.callUser(ctx)}, aku dengerin..."
    }

    fun liveCallConnecting(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Sebentar ya${SovereignNaturalSpeechHelpers.callUser(ctx)}, lagi nyambungin..."
        else -> "Sebentar${SovereignNaturalSpeechHelpers.callUser(ctx)}, lagi nyambungin..."
    }

    fun liveCallConnected(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Udah nyambung! Ngomong aja, aku dengerin"
        else -> "Udah nyambung • Ngomong aja langsung"
    }

    fun liveCallReconnecting(ctx: SovereignSpeechContext, attempt: Int, maxAttempts: Int): String =
        "Sinyal agak goyang. Nyambungin ulang percobaan ke-$attempt dari $maxAttempts..."

    fun liveCallFailed(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Yah${SovereignNaturalSpeechHelpers.callUser(ctx)}, koneksinya lagi putus nih..."
        else -> "Duh, koneksinya lagi putus nih..."
    }

    fun voicePreviewSample(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Hai${SovereignNaturalSpeechHelpers.callUser(ctx)}, ini sampel suara baruku. Kedengerannya udah enak dan pas belum di telingamu?"
        SovereignPersonaMode.HONEST_MENTOR ->
            "Halo${SovereignNaturalSpeechHelpers.callUser(ctx)}, ini tes suara baruku buat nemenin kamu tetap fokus. Udah pas belum kedengerannya?"
        else ->
            "Halo${SovereignNaturalSpeechHelpers.callUser(ctx)}! Ini sampel suara baruku. Menurutmu udah terdengar natural dan enak didenger belum?"
    }

    fun csiOwnerGreeting(ctx: SovereignSpeechContext, fallbackText: String): String {
        val nameClause = ctx.resolvedOwnerName.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.warm(ctx)}Eh${nameClause.ifEmpty { " kamu" }} baru dateng ya? Aku siap nemenin kalau ada yang mau dikerjain."
            SovereignPersonaMode.HONEST_MENTOR ->
                "Udah balik$nameClause? Yuk langsung lanjutin target yang masih kepegang."
            else ->
                if (fallbackText.isNotBlank()) fallbackText else "Selamat datang balik$nameClause, ada yang mau aku bantuin sekarang?"
        }
    }

}
