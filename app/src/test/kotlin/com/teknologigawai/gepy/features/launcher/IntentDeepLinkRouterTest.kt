package com.teknologigawai.gepy.features.launcher

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentDeepLinkRouterTest {

    @Test
    fun `normalizeIndonesianPhoneNumber converts local 08 and +62 formats to 628 international format`() {
        assertEquals(
            "6281234567890",
            IntentDeepLinkRouter.normalizeIndonesianPhoneNumber("0812-3456-7890")
        )
        assertEquals(
            "6281234567890",
            IntentDeepLinkRouter.normalizeIndonesianPhoneNumber("+62 812 3456 7890")
        )
        assertEquals(
            "6281234567890",
            IntentDeepLinkRouter.normalizeIndonesianPhoneNumber("81234567890")
        )
    }

    @Test
    fun `buildWhatsAppDeepLinkUri encodes phone and message accurately`() {
        val uri = IntentDeepLinkRouter.buildWhatsAppDeepLinkUri(
            phone = "0812-9988-7766",
            message = "Halo Budi, saya sudah di jalan"
        )
        assertEquals(
            "whatsapp://send?phone=6281299887766&text=Halo%20Budi%2C%20saya%20sudah%20di%20jalan",
            uri
        )
    }

    @Test
    fun `buildMapsDeepLinkUri builds search and direct navigation URIs`() {
        assertEquals(
            "google.navigation:q=Stasiun%20Gambir%20Jakarta",
            IntentDeepLinkRouter.buildMapsDeepLinkUri("Stasiun Gambir Jakarta", navigateDirectly = true)
        )
        assertEquals(
            "geo:0,0?q=Rumah%20Sakit%20Terdekat",
            IntentDeepLinkRouter.buildMapsDeepLinkUri("Rumah Sakit Terdekat", navigateDirectly = false)
        )
    }

    @Test
    fun `buildTelDialUri builds tel scheme and sanitizes formatting symbols`() {
        assertEquals("tel:081234567890", IntentDeepLinkRouter.buildTelDialUri("0812-3456-7890"))
        assertEquals("tel:+6281234567890", IntentDeepLinkRouter.buildTelDialUri("+62 812-3456-7890"))
        assertEquals("tel:*123#", IntentDeepLinkRouter.buildTelDialUri("*123#"))
    }

    @Test
    fun `buildMailtoUri builds RFC mailto URI with encoded recipient subject and body`() {
        val uri = IntentDeepLinkRouter.buildMailtoUri(
            recipient = "budi@contoh.com",
            subject = "Laporan Mingguan",
            body = "Halo Budi, tugas sudah selesai."
        )
        assertEquals(
            "mailto:budi@contoh.com?subject=Laporan%20Mingguan&body=Halo%20Budi%2C%20tugas%20sudah%20selesai.",
            uri
        )

        val uriNoSubject = IntentDeepLinkRouter.buildMailtoUri(
            recipient = "andi@contoh.com",
            subject = "",
            body = "Cek lampiran"
        )
        assertEquals("mailto:andi@contoh.com?body=Cek%20lampiran", uriNoSubject)
    }

    @Test
    fun `extractFloatingPanelKeyword and resolveFloatingSettingsPanelAction map settings panel actions`() {
        assertEquals("wifi", IntentDeepLinkRouter.extractFloatingPanelKeyword("buka panel wifi"))
        assertEquals("internet", IntentDeepLinkRouter.extractFloatingPanelKeyword("tampilkan panel internet mengambang"))
        assertEquals("volume", IntentDeepLinkRouter.extractFloatingPanelKeyword("panel volume cepat"))
        assertEquals("nfc", IntentDeepLinkRouter.extractFloatingPanelKeyword("panel nfc"))
        assertNull(IntentDeepLinkRouter.extractFloatingPanelKeyword("nyalain senter"))

        assertEquals(Settings.Panel.ACTION_WIFI, IntentDeepLinkRouter.resolveFloatingSettingsPanelAction("wifi"))
        assertEquals(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, IntentDeepLinkRouter.resolveFloatingSettingsPanelAction("internet"))
        assertEquals(Settings.Panel.ACTION_VOLUME, IntentDeepLinkRouter.resolveFloatingSettingsPanelAction("volume"))
        assertEquals(Settings.Panel.ACTION_NFC, IntentDeepLinkRouter.resolveFloatingSettingsPanelAction("nfc"))
        assertNull(IntentDeepLinkRouter.resolveFloatingSettingsPanelAction("unknown_panel"))
    }

    @Test
    fun `resolveSystemSettingsAction maps Indonesian and English settings keywords`() {
        assertEquals(
            Settings.ACTION_WIFI_SETTINGS,
            IntentDeepLinkRouter.resolveSystemSettingsAction("Wi-Fi dan Internet")
        )
        assertEquals(
            Settings.ACTION_BLUETOOTH_SETTINGS,
            IntentDeepLinkRouter.resolveSystemSettingsAction("Bluetooth")
        )
        assertEquals(
            Settings.ACTION_SOUND_SETTINGS,
            IntentDeepLinkRouter.resolveSystemSettingsAction("Suara dan Volume")
        )
        assertEquals(
            Settings.ACTION_DISPLAY_SETTINGS,
            IntentDeepLinkRouter.resolveSystemSettingsAction("Kecerahan Layar")
        )
    }

    @Test
    fun `tryExecuteImmediateDeepLinkGoal handles settings panel maps and rejects informational questions`() {
        val router = IntentDeepLinkRouter(context = null)

        val panelRes = router.tryExecuteImmediateDeepLinkGoal("Buka panel Wi-Fi sekarang")
        assertTrue(panelRes.handled)
        assertEquals("FLOATING_SETTINGS_PANEL", panelRes.routeType)
        assertEquals(Settings.Panel.ACTION_WIFI, panelRes.targetUriOrAction)

        val wifiRes = router.tryExecuteImmediateDeepLinkGoal("Buka pengaturan Wi-Fi sekarang")
        assertTrue(wifiRes.handled)
        assertEquals("SYSTEM_SETTINGS_DEEPLINK", wifiRes.routeType)

        val navRes = router.tryExecuteImmediateDeepLinkGoal("Navigasi ke Bandara Soekarno Hatta")
        assertTrue(navRes.handled)
        assertEquals("MAPS_NAVIGATION_DEEPLINK", navRes.routeType)
        assertEquals("google.navigation:q=Bandara%20Soekarno%20Hatta", navRes.targetUriOrAction)

        // Kueri tutorial / pertanyaan informasi tidak boleh dibajak
        val infoRes = router.tryExecuteImmediateDeepLinkGoal("Cara buka panel Wi-Fi di HP Samsung")
        assertFalse(infoRes.handled)
    }
}
