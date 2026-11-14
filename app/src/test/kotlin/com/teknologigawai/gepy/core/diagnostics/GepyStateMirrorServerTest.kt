package com.teknologigawai.gepy.core.diagnostics

import com.teknologigawai.gepy.core.memory.ChatTurnItem
import com.teknologigawai.gepy.core.memory.ConversationChannel
import com.teknologigawai.gepy.features.orchestrator.AgentProcessStep
import com.teknologigawai.gepy.features.orchestrator.GepyFsmState
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestratorState
import com.teknologigawai.gepy.features.orchestrator.ProcessStepState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GepyStateMirrorServerTest {

    @Test
    fun `serializeStateSnapshotJson exports timeline and caps chat history without leaking secrets`() {
        val chats = (1..15).map { idx ->
            ChatTurnItem(
                id = "turn_$idx",
                role = if (idx % 2 == 1) "user" else "assistant",
                text = "Pesan \"$idx\"\nBaris kedua",
                channel = if (idx == 15) ConversationChannel.LIVE_CALL else ConversationChannel.TEXT_CHAT,
                timestampMs = 1000L + idx
            )
        }
        val state = GepyOrchestratorState(
            fsmState = GepyFsmState.WAITING_HITL,
            currentGoal = "Pesan GoRide ke Universitas Alma Ata",
            currentPackage = "com.gojek.app",
            currentStep = 4,
            lastThought = "Menunggu konfirmasi pengguna sebelum mengetuk Pesan GoRide",
            lastActionLabel = "CONFIRM_AND_CLICK",
            processTimeline = listOf(
                AgentProcessStep("Membuka aplikasi Gojek", ProcessStepState.DONE, 2000L),
                AgentProcessStep("Menunggu persetujuan sebelum Pesan GoRide", ProcessStepState.ACTIVE, 3000L)
            ),
            chatHistory = chats,
            goalStartedAtMs = 1500L,
            goalElapsedMs = 4200L
        )

        val jsonText = GepyStateMirrorServer.serializeStateSnapshotJson(state, nowMs = 9999L)
        val parsed = JSONObject(jsonText)

        assertEquals("WAITING_HITL", parsed.getString("fsmState"))
        assertEquals("Pesan GoRide ke Universitas Alma Ata", parsed.getString("currentGoal"))
        assertEquals("com.gojek.app", parsed.getString("currentPackage"))
        assertTrue(parsed.getBoolean("isExecutingGoal"))
        assertEquals(9999L, parsed.getLong("serverTimeMs"))

        val timeline = parsed.getJSONArray("processTimeline")
        assertEquals(2, timeline.length())
        assertEquals("ACTIVE", timeline.getJSONObject(1).getString("state"))

        val history = parsed.getJSONArray("chatHistory")
        assertEquals(GepyStateMirrorServer.MAX_MIRROR_CHAT_ITEMS, history.length())
        //Harus menyisakan item ke-4..15 (item terbaru di indeks terakhir)
        assertEquals("turn_4", history.getJSONObject(0).getString("id"))
        assertEquals("LIVE_CALL", history.getJSONObject(11).getString("channel"))
        assertTrue(history.getJSONObject(11).getString("text").contains("Pesan \"15\"\nBaris kedua"))

        // Tidak boleh membocorkan kunci BYOK atau secret internal
        assertFalse(parsed.has("byokSettings"))
    }

    @Test
    fun `server responds with live JSON over loopback and stops cleanly`() {
        val sampleState = GepyOrchestratorState(
            fsmState = GepyFsmState.EXECUTING,
            currentGoal = "Cek rute GoRide",
            processTimeline = listOf(AgentProcessStep("Mencari tujuan Universitas Alma Ata", ProcessStepState.ACTIVE, 1234L))
        )
        val server = GepyStateMirrorServer(stateProvider = { sampleState })
        try {
            server.start(port = 0)
            var waited = 0
            while (server.boundPort <= 0 && waited < 40) {
                Thread.sleep(25)
                waited++
            }
            assertTrue("Port loopback harus terikat", server.boundPort > 0)

            val response = Socket("127.0.0.1", server.boundPort).use { client ->
                client.soTimeout = 2000
                client.getOutputStream().write("GET /state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            val body = response.substringAfter("\r\n\r\n")
            val json = JSONObject(body)
            assertEquals("EXECUTING", json.getString("fsmState"))
            assertEquals("Cek rute GoRide", json.getString("currentGoal"))
            assertEquals(1, json.getJSONArray("processTimeline").length())

            // Soket setengah-buka tanpa request tidak boleh menjatuhkan server
            Socket("127.0.0.1", server.boundPort).use { idle ->
                idle.soTimeout = 500
            }
            val afterIdle = Socket("127.0.0.1", server.boundPort).use { client ->
                client.soTimeout = 2000
                client.getOutputStream().write("GET /state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).readText()
            }
            assertTrue(afterIdle.startsWith("HTTP/1.1 200 OK"))
        } finally {
            server.stop()
        }
    }
}
