package com.teknologigawai.gepy.service

import com.teknologigawai.gepy.core.config.EnvConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationVaultFilterTest {

    @Test
    fun `isSensitiveCredentialContent mendeteksi OTP kata sandi PIN dan kode verifikasi`() {
        assertTrue(NotificationVaultFilter.isSensitiveCredentialContent("Bank BCA", "Kode OTP 123456 berlaku 5 menit"))
        assertTrue(NotificationVaultFilter.isSensitiveCredentialContent("Google", "G-987654 is your verification code"))
        assertTrue(NotificationVaultFilter.isSensitiveCredentialContent("Telegram", "Kode masuk Anda: 55443"))
        assertTrue(NotificationVaultFilter.isSensitiveCredentialContent("Keamanan", "Two-factor authentication code 112233"))
        assertTrue(NotificationVaultFilter.isSensitiveCredentialContent("PIN Anda", "Jangan bagikan PIN ke siapa pun"))

        assertFalse(NotificationVaultFilter.isSensitiveCredentialContent("Budi Santoso", "Halo Maulana, besok jadi ngopi jam 3 sore?"))
        assertFalse(NotificationVaultFilter.isSensitiveCredentialContent("Spotify", "Bernadya - Satu Bulan sedang diputar"))
        assertFalse(NotificationVaultFilter.isSensitiveCredentialContent("", ""))
    }

    @Test
    fun `sanitizeOrDrop memblokir m-Banking dialog izin kredensial dan self-package`() {
        // 1. Aplikasi perbankan / e-Wallet wajib ditolak mutlak
        assertNull(
            NotificationVaultFilter.sanitizeOrDrop(
                key = "k_bca",
                packageName = "com.bca",
                appLabel = "BCA mobile",
                rawTitle = "Mutasi Rekening",
                rawText = "Dana masuk Rp 1.000.000",
                timestampMs = 1000L
            )
        )
        assertNull(
            NotificationVaultFilter.sanitizeOrDrop(
                key = "k_dana",
                packageName = "id.dana",
                appLabel = "DANA",
                rawTitle = "Promo DANA",
                rawText = "Cashback 50%",
                timestampMs = 1000L
            )
        )

        // 2. Dialog izin sistem (permissioncontroller) wajib ditolak mutlak
        assertNull(
            NotificationVaultFilter.sanitizeOrDrop(
                key = "k_perm",
                packageName = "com.android.permissioncontroller",
                appLabel = "Izin",
                rawTitle = "Izin Akses",
                rawText = "Berikan izin kamera?",
                timestampMs = 1000L
            )
        )

        // 3. Self-package Gepy wajib diabaikan agar tidak self-trigger / feedback loop
        assertNull(
            NotificationVaultFilter.sanitizeOrDrop(
                key = "k_gepy",
                packageName = EnvConfig.SELF_PACKAGE_NAME,
                appLabel = "Gepy",
                rawTitle = "Gepy Siaga",
                rawText = "Mendengarkan...",
                timestampMs = 1000L
            )
        )

        // 4. Notifikasi OTP / kredensial di aplikasi apa pun wajib ditolak
        assertNull(
            NotificationVaultFilter.sanitizeOrDrop(
                key = "k_wa_otp",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                rawTitle = "WhatsApp",
                rawText = "Kode verifikasi WhatsApp Anda: 443-221",
                timestampMs = 1000L
            )
        )

        // 5. Notifikasi aman wajib diterima, disanitasi, dan label aplikasi otomatis dilengkapi
        val safe = NotificationVaultFilter.sanitizeOrDrop(
            key = "k_wa_safe",
            packageName = "com.whatsapp",
            appLabel = "",
            rawTitle = "Budi Santoso",
            rawText = "Halo Maulana, file laporan sudah kukirim ya.",
            timestampMs = 2000L,
            hasRemoteInput = true
        )
        assertNotNull(safe)
        assertEquals("com.whatsapp", safe?.packageName)
        assertEquals("WhatsApp", safe?.appLabel)
        assertEquals("Budi Santoso", safe?.title)
        assertEquals("Halo Maulana, file laporan sudah kukirim ya.", safe?.text)
        assertTrue(safe?.hasRemoteInput == true)
        assertFalse(safe?.isOngoing == true)
    }

    @Test
    fun `selectReadableNotifications menyaring ongoing group-summary dan mengurutkan teranyar`() {
        val now = 5000L
        val list = listOf(
            ActiveNotificationSnapshot(
                key = "k1",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                title = "Alif",
                text = "Pagi Maulana",
                timestampMs = now - 1000,
                hasRemoteInput = true
            ),
            ActiveNotificationSnapshot(
                key = "k_ongoing",
                packageName = "com.google.android.apps.maps",
                appLabel = "Google Maps",
                title = "Navigasi",
                text = "Belok kanan dalam 200m",
                timestampMs = now,
                isOngoing = true
            ),
            ActiveNotificationSnapshot(
                key = "k_summary",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                title = "WhatsApp",
                text = "3 pesan dari 2 obrolan",
                timestampMs = now - 500,
                isGroupSummary = true
            ),
            ActiveNotificationSnapshot(
                key = "k2",
                packageName = "org.telegram.messenger",
                appLabel = "Telegram",
                title = "Citra",
                text = "Kapan presentasi dimulai?",
                timestampMs = now - 200,
                hasRemoteInput = true
            ),
            ActiveNotificationSnapshot(
                key = "k_bca",
                packageName = "com.bca",
                appLabel = "BCA",
                title = "BCA",
                text = "Transaksi berhasil",
                timestampMs = now + 100
            )
        )

        val readable = NotificationVaultFilter.selectReadableNotifications(list, limit = 5)
        assertEquals(2, readable.size)
        // Teranyar di urutan pertama: Citra (now - 200) lalu Alif (now - 1000)
        assertEquals("Citra", readable[0].title)
        assertEquals("Alif", readable[1].title)
    }

    @Test
    fun `findMatchingReplyTarget mencocokkan target RemoteInput berbasis judul parsial dan alias aplikasi`() {
        val notifs = listOf(
            ActiveNotificationSnapshot(
                key = "k_wa_budi",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                title = "Budi Santoso",
                text = "Halo, apa kabar?",
                timestampMs = 1000L,
                hasRemoteInput = true
            ),
            ActiveNotificationSnapshot(
                key = "k_wa_andi",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                title = "Andi Wijaya",
                text = "Sudah dikirim ya.",
                timestampMs = 2000L,
                hasRemoteInput = true
            ),
            ActiveNotificationSnapshot(
                key = "k_ig_dina",
                packageName = "com.instagram.android",
                appLabel = "Instagram",
                title = "Dina",
                text = "Menyukai cerita Anda",
                timestampMs = 3000L,
                hasRemoteInput = false // Tidak ada remote input
            )
        )

        // 1. Judul persis
        val exact = NotificationVaultFilter.findMatchingReplyTarget(notifs, "Andi Wijaya")
        assertNotNull(exact)
        assertEquals("k_wa_andi", exact?.key)

        // 2. Judul parsial
        val partial = NotificationVaultFilter.findMatchingReplyTarget(notifs, "budi")
        assertNotNull(partial)
        assertEquals("k_wa_budi", partial?.key)

        // 3. Nama aplikasi lewat alias "wa" / "whatsapp" (memilih notifikasi WA ber-RemoteInput terbaru)
        val appMatch = NotificationVaultFilter.findMatchingReplyTarget(notifs, "wa")
        assertNotNull(appMatch)
        assertEquals("k_wa_andi", appMatch?.key)

        // 4. Target tanpa RemoteInput (Instagram) harus mengembalikan null
        val noRemote = NotificationVaultFilter.findMatchingReplyTarget(notifs, "dina")
        assertNull(noRemote)

        // 5. Query kosong / tidak ditemukan
        assertNull(NotificationVaultFilter.findMatchingReplyTarget(notifs, ""))
        assertNull(NotificationVaultFilter.findMatchingReplyTarget(notifs, "Zulkifli"))
    }

    @Test
    fun `findMatchingDismissTargets mendukung target spesifik dan hapus seluruh notifikasi aman`() {
        val notifs = listOf(
            ActiveNotificationSnapshot(
                key = "k1",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                title = "Budi Santoso",
                text = "Pesan 1",
                timestampMs = 1000L
            ),
            ActiveNotificationSnapshot(
                key = "k2",
                packageName = "org.telegram.messenger",
                appLabel = "Telegram",
                title = "Citra",
                text = "Pesan 2",
                timestampMs = 2000L
            ),
            ActiveNotificationSnapshot(
                key = "k_ongoing",
                packageName = "com.spotify.music",
                appLabel = "Spotify",
                title = "Memutar",
                text = "Lagu",
                timestampMs = 3000L,
                isOngoing = true
            )
        )

        // Target spesifik WhatsApp
        val waTargets = NotificationVaultFilter.findMatchingDismissTargets(notifs, "whatsapp")
        assertEquals(1, waTargets.size)
        assertEquals("k1", waTargets.first().key)

        // Target spesifik nama pengirim "Citra"
        val citraTargets = NotificationVaultFilter.findMatchingDismissTargets(notifs, "Citra")
        assertEquals(1, citraTargets.size)
        assertEquals("k2", citraTargets.first().key)

        // "all", "semua", "notifikasi" mengembalikan seluruh notifikasi NON-ONGOING
        for (q in listOf("all", "semua", "seluruh", "semuanya", "notif", "notifikasi", "")) {
            val all = NotificationVaultFilter.findMatchingDismissTargets(notifs, q)
            assertEquals("Query '$q' harus membersihkan seluruh notifikasi non-ongoing", 2, all.size)
            assertFalse(all.any { it.isOngoing })
        }
    }

    @Test
    fun `deriveFriendlyAppName menghasilkan nama ramah untuk paket populer dan fallback titlecase`() {
        assertEquals("WhatsApp", NotificationVaultFilter.deriveFriendlyAppName("com.whatsapp"))
        assertEquals("WhatsApp", NotificationVaultFilter.deriveFriendlyAppName("com.whatsapp.w4b"))
        assertEquals("Telegram", NotificationVaultFilter.deriveFriendlyAppName("org.telegram.messenger"))
        assertEquals("Instagram", NotificationVaultFilter.deriveFriendlyAppName("com.instagram.android"))
        assertEquals("Spotify", NotificationVaultFilter.deriveFriendlyAppName("com.spotify.music"))
        assertEquals("YouTube", NotificationVaultFilter.deriveFriendlyAppName("com.google.android.youtube"))
        assertEquals("Gmail", NotificationVaultFilter.deriveFriendlyAppName("com.google.android.gm"))
        assertEquals("Shopee", NotificationVaultFilter.deriveFriendlyAppName("com.shopee.id"))
        assertEquals("Tokopedia", NotificationVaultFilter.deriveFriendlyAppName("com.tokopedia.tkpd"))
        assertEquals("Gojek", NotificationVaultFilter.deriveFriendlyAppName("com.gojek.app"))
        assertEquals("Grab", NotificationVaultFilter.deriveFriendlyAppName("com.grabtaxi.passenger"))
        assertEquals("Customapp", NotificationVaultFilter.deriveFriendlyAppName("com.vendor.customapp"))
    }
}
