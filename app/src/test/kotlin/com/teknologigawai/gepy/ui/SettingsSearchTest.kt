package com.teknologigawai.gepy.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    @Test
    fun `matchesSettingsSearchQuery handles empty multi-token and non-matching filters accurately`() {
        assertTrue(matchesSettingsSearchQuery("", "suara", "elevenlabs"))
        assertTrue(matchesSettingsSearchQuery("   ", "memori", "room"))

        assertTrue(matchesSettingsSearchQuery("suara elevenlabs", "Suara Respon (ElevenLabs)", "Voice", "TTS"))
        assertTrue(matchesSettingsSearchQuery("API BYOK", "Kunci API (BYOK)", "Gemini", "Fireworks"))
        assertTrue(matchesSettingsSearchQuery("esp32 csi", "Jembatan LAN ESP32-S3 CSI", "IoT", "Wi-Fi"))

        assertFalse(matchesSettingsSearchQuery("elevenlabs", "Penjaga Anti-Doomscrolling", "Langkah Kaki"))
        assertFalse(matchesSettingsSearchQuery("suara whatsapp", "Suara Respon", "ElevenLabs"))

        assertTrue(
            matchesSettingsSearchQuery(
                "zen refleksi malam",
                "proaktif", "zen", "pasif", "copilot", "briefing", "refleksi", "malam"
            )
        )

        // Verifikasi pencarian fitur OS API-First & Akses Notifikasi di Kartu Keselamatan
        val safetyTokens = arrayOf(
            "keselamatan", "safety", "gatekeeper", "mbanking", "m-banking", "zero-hitl",
            "hitl", "akses penuh", "izin", "sistem", "panduan", "overlay", "baterai",
            "notifikasi", "akses notifikasi", "balas", "remoteinput", "media", "lagu",
            "senter", "timer", "dialer", "panel", "dnd", "jangan ganggu"
        )
        assertTrue(matchesSettingsSearchQuery("akses notifikasi", *safetyTokens))
        assertTrue(matchesSettingsSearchQuery("remoteinput", *safetyTokens))
        assertTrue(matchesSettingsSearchQuery("senter timer", *safetyTokens))
        assertTrue(matchesSettingsSearchQuery("dnd jangan ganggu", *safetyTokens))
        assertTrue(matchesSettingsSearchQuery("dialer panel", *safetyTokens))
        assertFalse(matchesSettingsSearchQuery("kopi hitam", *safetyTokens))
    }
}
