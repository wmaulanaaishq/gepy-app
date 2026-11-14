package com.teknologigawai.gepy.ui

import com.teknologigawai.gepy.core.memory.ChatTurnItem
import com.teknologigawai.gepy.core.memory.ConversationChannel
import com.teknologigawai.gepy.core.ports.TelemetryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNavigationAndDrawerTest {

    @Test
    fun `extractOwnerInitials handles multi-word single-word and blank names`() {
        assertEquals("WI", extractOwnerInitials("Wahyu Irwan"))
        assertEquals("BU", extractOwnerInitials("Budi"))
        assertEquals("GP", extractOwnerInitials("   "))
        assertEquals("GP", extractOwnerInitials(null))
    }

    @Test
    fun `filterDrawerPinned matches label or prompt case-insensitively`() {
        val all = DEFAULT_PINNED_WORKFLOWS
        assertEquals(all.size, filterDrawerPinned(all, "").size)

        val byLabel = filterDrawerPinned(all, "agenda")
        assertEquals(1, byLabel.size)
        assertEquals("pin_briefing", byLabel.first().id)

        val byPrompt = filterDrawerPinned(all, "layar")
        assertEquals(1, byPrompt.size)
        assertEquals("pin_screen", byPrompt.first().id)
    }

    @Test
    fun `buildRecentSessionEntries puts active session first and deduplicates telemetry goals`() {
        val turns = listOf(
            ChatTurnItem(role = "user", text = "Tolong cek cuaca hari ini", channel = ConversationChannel.TEXT_CHAT),
            ChatTurnItem(role = "assistant", text = "Cerah berawan.", channel = ConversationChannel.TEXT_CHAT)
        )
        val telemetry = listOf(
            TelemetryEntry(
                sessionId = "s1",
                stepNumber = 1,
                userGoal = "Nyalakan senter",
                packageName = "com.android.settings",
                rawNodeCount = 12,
                prunedNodeCount = 4,
                pruningRatio = 0.66,
                llmLatencyMs = 0,
                totalStepLatencyMs = 12,
                actionType = "SYSTEM_TOOL",
                verificationOutcome = "VERIFIED",
                createdAt = 1000L
            ),
            TelemetryEntry(
                sessionId = "s2",
                stepNumber = 2,
                userGoal = "Nyalakan senter",
                packageName = "com.android.settings",
                rawNodeCount = 12,
                prunedNodeCount = 4,
                pruningRatio = 0.66,
                llmLatencyMs = 0,
                totalStepLatencyMs = 15,
                actionType = "SYSTEM_TOOL",
                verificationOutcome = "VERIFIED",
                createdAt = 2000L
            ),
            TelemetryEntry(
                sessionId = "s3",
                stepNumber = 1,
                userGoal = "Cek status laptop",
                packageName = "com.teknologigawai.gepy",
                rawNodeCount = 8,
                prunedNodeCount = 8,
                pruningRatio = 0.0,
                llmLatencyMs = 0,
                totalStepLatencyMs = 45,
                actionType = "LAPTOP_RPC",
                verificationOutcome = "VERIFIED",
                createdAt = 3000L
            )
        )

        val entries = buildRecentSessionEntries(chatHistory = turns, telemetryHistory = telemetry)
        assertEquals(3, entries.size)
        assertTrue(entries[0].isActiveSession)
        assertEquals("Tolong cek cuaca hari ini", entries[0].title)
        assertFalse(entries[1].isActiveSession)
        assertEquals("Nyalakan senter", entries[1].title)
        assertEquals("Cek status laptop", entries[2].title)
    }

    @Test
    fun `filterDrawerChats narrows session list by title or subtitle`() {
        val sample = listOf(
            DrawerChatEntry("1", "Riset Agen AI Mobile", "Sesi aktif • 4 giliran", ""),
            DrawerChatEntry("2", "Cek status laptop", "gepy • 40 ms", "Cek status laptop")
        )
        val matches = filterDrawerChats(sample, "laptop")
        assertEquals(1, matches.size)
        assertEquals("2", matches.first().id)
    }

    @Test
    fun `formatSkillDisplayTitle formats known and fallback skill IDs cleanly`() {
        assertEquals("Alat Sistem OS Android", formatSkillDisplayTitle("android_system_tools"))
        assertEquals("Pendamping Laptop (Tailscale dan A2A)", formatSkillDisplayTitle("laptop_companion"))
        assertEquals("Custom Maps Helper", formatSkillDisplayTitle("custom_maps_helper"))
    }

    @Test
    fun `shouldShowOpeningAuthScreen returns user to login screen after signout and hides after sign-in or skip`() {
        assertTrue(shouldShowOpeningAuthScreen(isSignedIn = false, hasSkippedOpeningAuth = false))
        assertFalse(shouldShowOpeningAuthScreen(isSignedIn = true, hasSkippedOpeningAuth = false))
        assertFalse(shouldShowOpeningAuthScreen(isSignedIn = false, hasSkippedOpeningAuth = true))
        assertFalse(shouldShowOpeningAuthScreen(isSignedIn = true, hasSkippedOpeningAuth = true))
    }

    @Test
    fun `navigation drawer titles and pinned labels stay plain without ampersand`() {
        assertTrue(GepyMainSection.entries.none { it.title.contains("&") })
        assertTrue(DEFAULT_PINNED_WORKFLOWS.none { it.label.contains("&") })
    }

    @Test
    fun `hero quick pills showcase four concise flagship capabilities`() {
        assertEquals(4, HERO_QUICK_PILLS.size)
        assertEquals(
            listOf("gojek", "whatsapp", QUICK_ACTION_LIVE_CALL_ID, "web_search"),
            HERO_QUICK_PILLS.map { it.id }
        )
        assertTrue(HERO_QUICK_PILLS.none { it.id == "alarm" || it.id == "flashlight" })
        assertTrue(HERO_QUICK_PILLS.all { it.title.isNotBlank() && it.subtitle.isNotBlank() })
        assertTrue(
            HERO_QUICK_PILLS
                .filterNot { it.id == QUICK_ACTION_LIVE_CALL_ID }
                .all { it.promptText.isNotBlank() }
        )
    }
}
