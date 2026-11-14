package com.teknologigawai.gepy.core.wellness

import com.teknologigawai.gepy.core.config.ProactivityLevel
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoPilotContextSentinelTest {

    private fun fixedClock(hour: Int, minute: Int = 0): () -> ZonedDateTime = {
        ZonedDateTime.of(2026, 11, 9, hour, minute, 0, 0, ZoneId.of("Asia/Jakarta"))
    }

    @Test
    fun `mendeteksi resi Shopee Xpress dan JNE dari clipboard secara akurat`() {
        val emitted = mutableListOf<ProactiveWhisper>()
        val sentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.CONTEXT_COPILOT },
            onWhisperAvailable = { emitted.add(it) }
        )

        val spxWhisper = sentinel.evaluateClipboardText("Paketmu dikirim via no resi SPXID02938172635 ya")
        assertNotNull(spxWhisper)
        assertEquals("Resi Shopee Xpress Terdeteksi", spxWhisper?.title)
        assertTrue(spxWhisper?.subtitle?.contains("SPXID02938172635") == true)
        assertEquals("Lacak Paket", spxWhisper?.actionLabel)

        val jneWhisper = sentinel.evaluateClipboardText("Resi: CGK1234567890123")
        assertNotNull(jneWhisper)
        assertEquals("Resi JNE Terdeteksi", jneWhisper?.title)
    }

    @Test
    fun `mendeteksi resi JNT SiCepat dan Pos Indonesia`() {
        val sentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.CONTEXT_COPILOT }
        )

        val jntEntity = sentinel.parseClipEntity("JP987654321012")
        assertTrue(jntEntity is DetectedClipEntity.CourierWaybill)
        assertEquals("J&T Express", (jntEntity as DetectedClipEntity.CourierWaybill).courierName)

        val sicepatEntity = sentinel.parseClipEntity("002819283746")
        assertTrue(sicepatEntity is DetectedClipEntity.CourierWaybill)
        assertEquals("SiCepat", (sicepatEntity as DetectedClipEntity.CourierWaybill).courierName)

        val posEntity = sentinel.parseClipEntity("POS1234567890")
        assertTrue(posEntity is DetectedClipEntity.CourierWaybill)
        assertEquals("Pos Indonesia", (posEntity as DetectedClipEntity.CourierWaybill).courierName)
    }

    @Test
    fun `mendeteksi URL artikel web untuk diringkas`() {
        val sentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.CONTEXT_COPILOT }
        )

        val urlWhisper = sentinel.evaluateClipboardText("https://kompas.com/tekno/read/2026/11/09/ai-native-architecture")
        assertNotNull(urlWhisper)
        assertEquals("Tautan Artikel Disalin", urlWhisper?.title)
        assertEquals("Ringkas Artikel", urlWhisper?.actionLabel)
    }

    @Test
    fun `mendeteksi nomor WhatsApp untuk percakapan baru`() {
        val sentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.CONTEXT_COPILOT }
        )

        val phoneWhisper = sentinel.evaluateClipboardText("081234567890")
        assertNotNull(phoneWhisper)
        assertEquals("Nomor Telepon Disalin", phoneWhisper?.title)
        assertEquals("Kirim WA", phoneWhisper?.actionLabel)
        assertEquals("Kirim pesan WA ke 6281234567890", phoneWhisper?.goalPayload)
    }

    @Test
    fun `menahan bisikan saat pengguna sedang di aplikasi video game atau mengetik`() {
        val sentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.CONTEXT_COPILOT }
        )

        // YouTube
        val ytSuppressed = sentinel.evaluateClipboardText(
            rawText = "https://example.com/tech-article",
            currentForegroundPackage = "com.google.android.youtube"
        )
        assertNull("Wajib ditahan saat YouTube aktif", ytSuppressed)

        // Mobile Legends
        val mlSuppressed = sentinel.evaluateClipboardText(
            rawText = "081234567890",
            currentForegroundPackage = "com.mobile.legends"
        )
        assertNull("Wajib ditahan saat game aktif", mlSuppressed)

        // Keyboard IME Samsung
        val imeSuppressed = sentinel.evaluateClipboardText(
            rawText = "081234567890",
            currentForegroundPackage = "com.samsung.android.honeyboard"
        )
        assertNull("Wajib ditahan saat keyboard aktif", imeSuppressed)
    }

    @Test
    fun `memberikan peringatan baterai lemah malam hari jika tidak dicas`() {
        val emitted = mutableListOf<ProactiveWhisper>()
        val sentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.CONTEXT_COPILOT },
            clockProvider = fixedClock(hour = 22, minute = 30),
            onWhisperAvailable = { emitted.add(it) }
        )

        // Baterai 14% jam 22:30 malam tidak dicas -> memicu bisikan
        val battWhisper = sentinel.evaluateBatteryState(
            batteryPercent = 14,
            isCharging = false
        )
        assertNotNull(battWhisper)
        assertEquals("Baterai Lemah (14%)", battWhisper?.title)
        assertEquals(1, emitted.size)

        // Siang hari jam 14:00 baterai 14% -> tidak memicu peringatan malam
        val daytimeSentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.CONTEXT_COPILOT },
            clockProvider = fixedClock(hour = 14, minute = 0)
        )
        val daytimeWhisper = daytimeSentinel.evaluateBatteryState(
            batteryPercent = 14,
            isCharging = false
        )
        assertNull("Siang hari tidak boleh memicu peringatan malam", daytimeWhisper)
    }

    @Test
    fun `mode ZEN_PASSIVE menonaktifkan seluruh inisiatif bisikan proaktif`() {
        val sentinel = CoPilotContextSentinel(
            proactivityLevelProvider = { ProactivityLevel.ZEN_PASSIVE },
            clockProvider = fixedClock(hour = 23, minute = 0)
        )

        assertNull(sentinel.evaluateClipboardText("SPXID1234567890"))
        assertNull(sentinel.evaluateBatteryState(batteryPercent = 10, isCharging = false))
    }
}
