package com.teknologigawai.gepy.core.persona

import com.teknologigawai.gepy.core.config.SovereignPersonaMode

/**
 * Template ucapan deterministik domain perangkat, media, navigasi deep-link, dan notifikasi sistem.
 */
internal object SovereignSpeechSystemTemplates {
    // =========================================================================
    // SPOTIFY, YOUTUBE, MEDIA & PELUNCURAN APLIKASI (0B)
    // =========================================================================

    fun spotifyPlay(ctx: SovereignSpeechContext, query: String, targetDevice: String): String {
        val cleanQuery = query.trim()
        val isLaptop = targetDevice.equals("laptop", ignoreCase = true) ||
            targetDevice.equals("pc", ignoreCase = true) ||
            targetDevice.equals("komputer", ignoreCase = true)
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> when {
                isLaptop && cleanQuery.isNotEmpty() ->
                    "${SovereignNaturalSpeechHelpers.openOk(ctx)} Lagu '$cleanQuery' udah aku puterin di Spotify laptop ya."
                isLaptop ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, Spotify di laptop udah aku bukain terus musiknya jalan ya."
                cleanQuery.isNotEmpty() ->
                    "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aku puterin '$cleanQuery' di Spotify ya."
                else ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, aku bukain Spotify terus play musiknya ya."
            }
            SovereignPersonaMode.HONEST_MENTOR -> when {
                isLaptop && cleanQuery.isNotEmpty() ->
                    "${SovereignNaturalSpeechHelpers.openOk(ctx)} Lagu '$cleanQuery' udah jalan di Spotify laptop. Tetap fokus kerjanya ya."
                isLaptop ->
                    "Sip${SovereignNaturalSpeechHelpers.callUser(ctx)}, Spotify di laptop udah kebuka dan musiknya jalan."
                cleanQuery.isNotEmpty() ->
                    "${SovereignNaturalSpeechHelpers.openOk(ctx)} Lagu '$cleanQuery' udah diputar di Spotify. Tetap fokus ya."
                else ->
                    "Sip${SovereignNaturalSpeechHelpers.callUser(ctx)}, Spotify udah kebuka dan musiknya jalan. Tetap fokus ya."
            }
            else -> when {
                isLaptop && cleanQuery.isNotEmpty() ->
                    "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aku puterin '$cleanQuery' di Spotify laptop ya."
                isLaptop ->
                    "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, Spotify di laptop udah aku buka dan musiknya aku puter ya."
                cleanQuery.isNotEmpty() ->
                    "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aku puterin '$cleanQuery' di Spotify."
                else ->
                    "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, Spotify-nya udah aku buka dan musiknya langsung jalan ya."
            }
        }
    }

    fun spotifyError(ctx: SovereignSpeechContext, detail: String?): String {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Spotify-nya belum mau kebuka nih$suffix. Coba kita cek bentar ya."
            SovereignPersonaMode.HONEST_MENTOR ->
                "Spotify belum bisa dibuka nih$suffix. Cek lagi aplikasinya udah terpasang dan aktif belum."
            else ->
                "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Spotify-nya belum berhasil kebuka sekarang$suffix."
        }
    }

    fun youtubePlay(ctx: SovereignSpeechContext, query: String): String {
        val cleanQuery = query.trim()
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> if (cleanQuery.isNotEmpty()) {
                "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aku bukain '$cleanQuery' di YouTube ya."
            } else {
                "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, YouTube-nya udah aku buka ya."
            }
            SovereignPersonaMode.HONEST_MENTOR -> if (cleanQuery.isNotEmpty()) {
                "${SovereignNaturalSpeechHelpers.openOk(ctx)} '$cleanQuery' udah aku bukain di YouTube. Awas jangan kebablasan nonton yang lain ya."
            } else {
                "Sip${SovereignNaturalSpeechHelpers.callUser(ctx)}, YouTube udah kebuka. Inget batasi waktunya ya."
            }
            else -> if (cleanQuery.isNotEmpty()) {
                "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aku bukain '$cleanQuery' di YouTube."
            } else {
                "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, YouTube-nya udah aku buka."
            }
        }
    }

    fun mediaControl(ctx: SovereignSpeechContext, action: String, targetDevice: String): String {
        val act = action.trim().lowercase()
        val isLaptop = targetDevice.equals("laptop", ignoreCase = true) ||
            targetDevice.equals("pc", ignoreCase = true) ||
            targetDevice.equals("komputer", ignoreCase = true)
        if (isLaptop) {
            return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, kontrol musik di laptop udah aku kirim ya."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sip${SovereignNaturalSpeechHelpers.callUser(ctx)}, kontrol media di laptop udah beres."
                else ->
                    "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, musik di laptop udah aku atur ya."
            }
        }
        return when (act) {
            "pause" -> when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, musiknya dijeda dulu ya."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sip, musiknya udah dijeda."
                else ->
                    "Oke, musiknya udah dijeda ya."
            }
            "play", "resume" -> when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, musiknya dilanjutkan lagi ya."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sip, musiknya udah dilanjutkan lagi."
                else ->
                    "Oke, musiknya udah dilanjutkan lagi ya."
            }
            "next", "skip" -> when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, aku ganti ke lagu berikutnya ya."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sip, lanjut ke lagu berikutnya."
                else ->
                    "Oke, lanjut ke lagu berikutnya ya."
            }
            "prev", "previous" -> when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, aku balikin ke lagu sebelumnya ya."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sip, balik ke lagu sebelumnya."
                else ->
                    "Oke, balik ke lagu sebelumnya ya."
            }
            else -> when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                    "${SovereignNaturalSpeechHelpers.warm(ctx)}Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}, pemutaran musiknya udah aku atur ya."
                SovereignPersonaMode.HONEST_MENTOR ->
                    "Sip, pemutaran musiknya udah disesuaikan."
                else ->
                    "Oke, pemutaran musiknya udah aku atur ya."
            }
        }
    }

    fun mediaControlError(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Duh${SovereignNaturalSpeechHelpers.callUser(ctx)}, tombol medianya lagi nggak merespons nih."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Tombol medianya lagi nggak merespons nih, coba cek pemutar musiknya bentar."
        else ->
            "Duh, tombol medianya lagi nggak merespons nih."
    }

    fun appOpened(ctx: SovereignSpeechContext, appName: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Aku bukain $appName ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Oke${SovereignNaturalSpeechHelpers.callUser(ctx)}. $appName udah kebuka. Jangan kelamaan ya."
        else ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} $appName udah aku buka ya."
    }

    fun appNotFound(ctx: SovereignSpeechContext, appName: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku cari-cari aplikasi $appName belum ketemu nih di HP."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Aplikasi $appName nggak ketemu di HP kamu. Cek lagi namanya atau pastiin udah keinstal."
        else ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku cari aplikasi $appName kok belum ketemu di HP kamu ya."
    }

    fun appNotInstalled(ctx: SovereignSpeechContext, appName: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Kayaknya aplikasi $appName belum keinstal di HP deh."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Aplikasi $appName belum keinstal di HP ini."
        else ->
            "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Kayaknya aplikasi $appName belum keinstal di HP kamu nih."
    }

    fun appLaunchSimulated(ctx: SovereignSpeechContext, resolvedPackage: String): String =
        "Oke, simulasi buka $resolvedPackage udah beres."

    // =========================================================================
    // INFORMASI SISTEM ANDROID & TELEMETRI HARIAN
    // =========================================================================

    fun deviceStatusSummary(
        ctx: SovereignSpeechContext,
        formattedDateTimeId: String,
        batteryPercent: Int,
        isCharging: Boolean,
        networkType: String,
        mediaVolumePercent: Int,
        ringerMode: String
    ): String {
        val chargeText = if (isCharging) "lagi diisi daya" else "belum dicolok charger"
        val battText = if (batteryPercent >= 0) "$batteryPercent% ($chargeText)" else "belum kebaca"
        val lowBatteryTip = if (batteryPercent in 1..20 && !isCharging) {
            when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> " Udah agak tipis nih, siapin charger ya."
                SovereignPersonaMode.HONEST_MENTOR -> " Baterainya menipis, segera colok charger."
                else -> " Sebentar lagi butuh dicas ya."
            }
        } else {
            ""
        }
        val cleanOwner = ctx.resolvedOwnerName
        val timedLead = if (cleanOwner.isNotEmpty()) "$cleanOwner, sekarang" else "Sekarang"
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.warm(ctx)}Sekarang $formattedDateTimeId ya${SovereignNaturalSpeechHelpers.callUser(ctx)}. Baterai $battText, nyambung ke $networkType, mode dering $ringerMode, terus volume medianya $mediaVolumePercent%.$lowBatteryTip"
            SovereignPersonaMode.HONEST_MENTOR ->
                "$timedLead $formattedDateTimeId. Baterai $battText, jaringan $networkType, mode $ringerMode, volume $mediaVolumePercent%.$lowBatteryTip"
            else ->
                "$timedLead $formattedDateTimeId ya. Baterai $battText, nyambung ke $networkType, mode dering $ringerMode, volume media $mediaVolumePercent%.$lowBatteryTip"
        }
    }

    fun browserOpened(ctx: SovereignSpeechContext, urlOrQuery: String, success: Boolean): String {
        if (!success) {
            return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Halamannya gagal kebuka di browser nih."
                SovereignPersonaMode.HONEST_MENTOR -> "Browser gagal buka alamat itu. Coba cek koneksi atau link-nya."
                else -> "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Halamannya belum berhasil kebuka di browser."
            }
        }
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Udah aku bukain di browser untuk '$urlOrQuery' ya."
            SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Udah kebuka di browser untuk '$urlOrQuery'."
            else -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Udah aku bukain '$urlOrQuery' di browser."
        }
    }

    fun contactsNotFound(ctx: SovereignSpeechContext, query: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Aku belum nemu kontak bernama '$query' nih. Pastiin izin kontaknya udah nyala ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Kontak '$query' nggak ketemu. Pastiin izin kontaknya udah dinyalain ya."
        else ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku belum nemu kontak bernama '$query' nih. Pastiin izin kontaknya udah aktif ya."
    }

    fun notificationsPrivacyBlocked(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "Privacy Shield lagi aktif soalnya sensor ngedeteksi ada orang lain di ruangan. Isi notifikasimu aku jaga dulu sampai kamu sendirian lagi ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Privacy Shield lagi aktif karena sensor ngedeteksi ada orang lain di sekitar. Notifikasi pribadi aku tahan dulu sampai aman."
        else ->
            "Privacy Shield lagi aktif soalnya sensor ngedeteksi ada orang lain di ruangan. Isi notifikasimu aku tahan dulu sampai kamu sendirian ya."
    }

    fun notificationsEmpty(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.warm(ctx)}Belum ada notifikasi baru yang masuk nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Belum ada notifikasi baru yang masuk. Bagus, jadi bisa tetap fokus."
        else ->
            "Belum ada notifikasi baru yang masuk sejauh ini."
    }

    fun locationFound(
        ctx: SovereignSpeechContext,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        provider: String
    ): String {
        val coords = String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude)
        val acc = String.format(java.util.Locale.US, "%.0f", accuracyMeters)
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.soft(ctx)}Posisimu terakhir kebaca di koordinat $coords${SovereignNaturalSpeechHelpers.callUser(ctx)}, akurasinya sekitar $acc meter lewat $provider."
            SovereignPersonaMode.HONEST_MENTOR ->
                "Lokasi GPS terakhir ada di koordinat $coords, akurasi $acc meter lewat $provider."
            else ->
                "Lokasi GPS terakhir kebaca di koordinat $coords, akurasinya sekitar $acc meter lewat $provider."
        }
    }

    fun locationUnavailable(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Lokasi GPS-nya belum kebaca nih${SovereignNaturalSpeechHelpers.callUser(ctx)}. Cek izin lokasi sama GPS-nya udah nyala belum ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Lokasi GPS belum kebaca. Pastiin izin lokasi sama GPS HP nyala."
        else ->
            "Lokasi GPS belum kebaca nih. Pastiin izin lokasi sama GPS-nya udah nyala ya."
    }

    fun alarmSet(ctx: SovereignSpeechContext, hour: Int, minute: Int, label: String, success: Boolean): String {
        if (!success) {
            return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Alarmnya gagal aku pasang nih."
                SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Alarm gagal dipasang. Cek izin aplikasi Jam di HP."
                else -> "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Belum berhasil masang alarmnya sekarang."
            }
        }
        val formattedTime = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        val cleanLabel = label.trim()
        val detailClause = if (cleanLabel.isNotEmpty() && !cleanLabel.equals("Alarm Gepy", ignoreCase = true)) {
            "Jam $formattedTime, $cleanLabel."
        } else {
            "Jam $formattedTime."
        }
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
                "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Alarm udah aku pasang biar nggak telat. $detailClause"
            SovereignPersonaMode.HONEST_MENTOR ->
                "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Alarm udah terpasang, nanti pas bunyi langsung bangun. $detailClause"
            else ->
                "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Alarm udah aku pasang ya. $detailClause"
        }
    }

    fun wellnessEmptyFallback(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Data langkah kaki sama durasi layar hari ini belum ada catatannya nih${SovereignNaturalSpeechHelpers.callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Catatan langkah kaki sama durasi layar hari ini masih kosong."
        else ->
            "Catatan langkah kaki sama durasi layar hari ini belum kerekam nih."
    }

    // =========================================================================
    // DEEP-LINK ROUTER (SETTINGS, MAPS, WHATSAPP DRAFT)
    // =========================================================================

    fun deepLinkWhatsAppDraft(ctx: SovereignSpeechContext, contactName: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Ruang chat WhatsApp $contactName udah aku bukain sekalian draf pesannya ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Ruang chat WhatsApp $contactName udah kebuka sama draf pesannya. Cek dulu sebelum dikirim."
        else ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Ruang chat WhatsApp $contactName udah aku buka sekalian draf pesannya."
    }

    fun deepLinkSettingsOpened(ctx: SovereignSpeechContext, panelTarget: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pengaturan $panelTarget udah aku bukain ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pengaturan $panelTarget udah kebuka."
        else ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pengaturan $panelTarget udah aku buka ya."
    }

    fun deepLinkSettingsUnknown(ctx: SovereignSpeechContext, panelTarget: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Halaman pengaturan '$panelTarget' kayaknya nggak ada atau namanya beda nih."
        SovereignPersonaMode.HONEST_MENTOR ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Menu pengaturan '$panelTarget' nggak ketemu di HP ini."
        else ->
            "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Halaman pengaturan '$panelTarget' belum ketemu nih."
    }

    fun deepLinkMapsNavigation(ctx: SovereignSpeechContext, destination: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Rute ke $destination udah aku siapin di Google Maps ya. Hati-hati di jalan!"
        SovereignPersonaMode.HONEST_MENTOR ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Navigasi ke $destination udah jalan di Google Maps. Fokus di jalan ya."
        else ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Rute Google Maps ke $destination udah aku mulai."
    }

    fun deepLinkMapsSearch(ctx: SovereignSpeechContext, place: String): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Lokasi $place udah aku carikan di Google Maps ya."
        SovereignPersonaMode.HONEST_MENTOR ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pencarian $place udah kebuka di Google Maps."
        else ->
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Lokasi $place udah aku buka di Google Maps."
    }

    fun mapsOpenError(ctx: SovereignSpeechContext): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "${SovereignNaturalSpeechHelpers.soft(ctx)}Duh${SovereignNaturalSpeechHelpers.callUser(ctx)}, Google Maps lagi nggak mau kebuka nih."
        SovereignPersonaMode.HONEST_MENTOR ->
            "Google Maps gagal dibuka. Pastiin aplikasinya masih aktif."
        else ->
            "Duh, Google Maps belum berhasil kebuka sekarang."
    }


    // =========================================================================
    // BATCH 1 & BATCH 2: API-FIRST OS ACTIONS, MEDIA SESSION & REMOTE INPUT
    // =========================================================================

    fun torchToggled(ctx: SovereignSpeechContext, enabled: Boolean, success: Boolean): String {
        if (!success) {
            return "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Lampu senternya lagi nggak bisa diakses di HP ini."
        }
        return if (enabled) {
            when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Senternya udah aku nyalain ya."
                SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Senter udah nyala."
                else -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Senter udah aku nyalain."
            }
        } else {
            when (SovereignNaturalSpeechHelpers.eff(ctx)) {
                SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Senternya udah aku matiin ya."
                SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Senter udah mati."
                else -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Senter udah aku matiin."
            }
        }
    }

    fun timerSet(
        ctx: SovereignSpeechContext,
        durationLabel: String,
        label: String,
        success: Boolean
    ): String {
        if (!success) {
            return "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Timernya gagal aku pasang nih. Cek aplikasi Jam bawaan HP ya."
        }
        val cleanLabel = label.trim()
        val labelSuffix = if (cleanLabel.isNotEmpty() &&
            !cleanLabel.equals("Timer Gepy", ignoreCase = true) &&
            !cleanLabel.equals("Pengingat Gepy", ignoreCase = true)
        ) {
            ", buat $cleanLabel"
        } else {
            ""
        }
        val detail = "Selama $durationLabel$labelSuffix."
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Timernya udah aku pasang ya. $detail Nanti pas waktunya habis, HP kamu bakal bunyi buat ngingetin."
            SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Timer udah jalan. $detail Nanti bakal bunyi pas waktunya habis."
            else -> "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Timer udah aku pasang. $detail Nanti bakal bunyi saat waktunya habis."
        }
    }

    fun alarmsDisplayed(
        ctx: SovereignSpeechContext,
        nextAlarmFormatted: String?,
        openedUi: Boolean
    ): String {
        if (!openedUi && nextAlarmFormatted == null) {
            return "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Daftar alarmnya lagi belum kebuka nih."
        }
        val detail = if (!nextAlarmFormatted.isNullOrBlank()) {
            " Alarm terdekat jam $nextAlarmFormatted."
        } else {
            " Belum ada alarm terdekat yang aktif."
        }
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Daftar alarmnya udah aku bukain ya.$detail"
            SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Daftar alarm udah kebuka.$detail"
            else -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Daftar alarm udah aku buka.$detail"
        }
    }

    fun volumeAdjusted(
        ctx: SovereignSpeechContext,
        streamLabelId: String,
        percent: Int?,
        directionLabel: String?
    ): String {
        val detail = when {
            percent != null -> "Volume $streamLabelId sekarang $percent persen."
            directionLabel == "mute" -> "Volume $streamLabelId udah aku bisukan."
            directionLabel == "unmute" -> "Volume $streamLabelId udah dinyalain lagi."
            directionLabel == "up" -> "Volume $streamLabelId udah aku naikin."
            directionLabel == "down" -> "Volume $streamLabelId udah aku turunin."
            else -> "Volume $streamLabelId udah disesuaikan."
        }
        return "${SovereignNaturalSpeechHelpers.openOk(ctx)} $detail"
    }

    fun volumeError(ctx: SovereignSpeechContext): String =
        "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Volume HP lagi ketahan sama aturan sistem nih."

    fun ringerModeChanged(
        ctx: SovereignSpeechContext,
        modeLabel: String,
        needsDndPermission: Boolean
    ): String {
        if (needsDndPermission) {
            return "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Mode senyap butuh izin Jangan Ganggu dulu di Android. Aku turunin ke Getar dan bukain pengaturannya ya."
        }
        return "${SovereignNaturalSpeechHelpers.openOk(ctx)} Mode dering udah aku ubah ke $modeLabel."
    }

    fun dialerOpened(ctx: SovereignSpeechContext, contactLabel: String, success: Boolean): String {
        if (!success) {
            return "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Layar teleponnya lagi gagal kebuka nih."
        }
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Layar teleponnya udah siap. Tinggal ketuk panggil ke $contactLabel."
            else -> "${SovereignNaturalSpeechHelpers.openOk(ctx)} Layar telepon ke $contactLabel udah siap, tinggal pencet panggil."
        }
    }

    fun emailComposerOpened(ctx: SovereignSpeechContext, recipientLabel: String, success: Boolean): String {
        if (!success) {
            return "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Aplikasi emailnya belum nemu atau gagal kebuka nih."
        }
        val detail = if (recipientLabel.isNotBlank()) " Tujuan ke $recipientLabel." else ""
        return "${SovereignNaturalSpeechHelpers.openOk(ctx)} Draf emailnya udah aku bukain di layar.$detail"
    }

    fun floatingPanelOpened(ctx: SovereignSpeechContext, panelLabel: String, success: Boolean): String {
        if (!success) {
            return "${SovereignNaturalSpeechHelpers.openSoftDuh(ctx)} Panel cepat $panelLabel belum tersedia di perangkat ini."
        }
        return "${SovereignNaturalSpeechHelpers.openOk(ctx)} Panel cepat $panelLabel udah aku tampilin ya."
    }

    fun nowPlayingInfo(
        ctx: SovereignSpeechContext,
        title: String,
        artist: String,
        appLabel: String,
        isPlaying: Boolean
    ): String {
        val cleanTitle = title.trim().ifEmpty { "media aktif" }
        val artistPart = artist.trim().takeIf { it.isNotEmpty() }?.let { " dari $it" }.orEmpty()
        val appPart = appLabel.trim().takeIf { it.isNotEmpty() }?.let { " di $it" }.orEmpty()
        return if (isPlaying) {
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Sekarang lagi muter '$cleanTitle'$artistPart$appPart."
        } else {
            "${SovereignNaturalSpeechHelpers.openOk(ctx)} Pemutaran terakhir lagi dijeda di '$cleanTitle'$artistPart$appPart."
        }
    }

    fun nowPlayingEmpty(ctx: SovereignSpeechContext, hasPermission: Boolean): String = if (!hasPermission) {
        "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aktifin dulu Akses Notifikasi buat Gepy biar aku bisa baca judul lagu yang lagi diputar."
    } else {
        "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Sekarang lagi nggak ada lagu atau media yang aktif diputar."
    }

    fun notificationReplied(ctx: SovereignSpeechContext, senderName: String): String {
        val target = senderName.trim().ifEmpty { "pengirim" }
        return when (SovereignNaturalSpeechHelpers.eff(ctx)) {
            SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Balasan ke $target udah terkirim lewat notifikasi ya."
            SovereignPersonaMode.HONEST_MENTOR -> "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Balasan ke $target udah dikirim di latar belakang."
            else -> "${SovereignNaturalSpeechHelpers.openBeres(ctx)} Balasan ke $target udah terkirim."
        }
    }

    fun notificationReplyConfirmPrompt(
        ctx: SovereignSpeechContext,
        senderName: String,
        replyMessage: String
    ): String = when (SovereignNaturalSpeechHelpers.eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND ->
            "Mau langsung aku bales notif dari '$senderName' dengan isi '$replyMessage' sekarang?"
        else ->
            "Kirim balasan notifikasi ke '$senderName' dengan isi '$replyMessage' sekarang?"
    }

    fun notificationDismissed(
        ctx: SovereignSpeechContext,
        dismissedCount: Int,
        dismissedLabels: List<String>
    ): String {
        val detail = if (dismissedLabels.size == 1 && dismissedCount == 1) {
            "Notifikasi ${dismissedLabels.first()} udah aku bersihin."
        } else {
            "$dismissedCount notifikasi udah aku bersihin."
        }
        return "${SovereignNaturalSpeechHelpers.openOk(ctx)} $detail"
    }

    fun notificationAccessRequired(ctx: SovereignSpeechContext): String =
        "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aktifin izin Akses Notifikasi buat Gepy dulu ya biar aku bisa kelola langsung."

    fun notificationTargetNotFound(ctx: SovereignSpeechContext, targetQuery: String): String =
        "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Aku belum nemu notifikasi aktif buat '$targetQuery'."

    fun notificationsSafetyBlocked(ctx: SovereignSpeechContext): String =
        "${SovereignNaturalSpeechHelpers.openSoftHmm(ctx)} Notifikasi sensitif atau perbankan aku blokir otomatis demi keamanan."
}
