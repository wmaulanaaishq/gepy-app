package com.teknologigawai.gepy.core.diagnostics

import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestratorState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Peladen cermin state *read-only* **khusus build Debug (`127.0.0.1` loopback-only)**
 * untuk memancarkan snapshot `GepyOrchestratorState` secara real-time melalui `adb forward`.
 *
 * Keamanan:
 * - Hanya mengikat `127.0.0.1` sehingga tidak dapat dijangkau dari Wi-Fi LAN maupun Tailscale.
 * - Tidak pernah mengirim kredensial, API key, atau memori privat.
 */
class GepyStateMirrorServer(
    private val stateProvider: () -> GepyOrchestratorState,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "StateMirror"
        const val DEFAULT_PORT = 8766
        const val MAX_MIRROR_CHAT_ITEMS = 12

        /** Memserialisasi snapshot [GepyOrchestratorState] aman untuk penampil Dashboard Chat. */
        fun serializeStateSnapshotJson(
            state: GepyOrchestratorState,
            nowMs: Long = System.currentTimeMillis(),
            maxChatItems: Int = MAX_MIRROR_CHAT_ITEMS
        ): String {
            val root = JSONObject().apply {
                put("fsmState", state.fsmState.name)
                put("currentGoal", state.currentGoal)
                put("currentPackage", state.currentPackage)
                put("currentStep", state.currentStep)
                put("isExecutingGoal", state.isExecutingGoal)
                put("isLiveCallActive", state.isLiveCallActive)
                put("overlayActive", state.overlayActive)
                put("lastThought", state.lastThought)
                put("lastActionLabel", state.lastActionLabel)
                put("goalStartedAtMs", state.goalStartedAtMs)
                put("goalElapsedMs", state.goalElapsedMs)
                put("errorMessage", state.errorMessage ?: JSONObject.NULL)
                put("serverTimeMs", nowMs)
            }

            val timelineArr = JSONArray()
            for (step in state.processTimeline) {
                timelineArr.put(
                    JSONObject().apply {
                        put("text", step.text)
                        put("state", step.state.name)
                        put("timestampMs", step.timestampMs)
                    }
                )
            }
            root.put("processTimeline", timelineArr)

            val recentChat = if (maxChatItems > 0 && state.chatHistory.size > maxChatItems) {
                state.chatHistory.takeLast(maxChatItems)
            } else if (maxChatItems <= 0) {
                emptyList()
            } else {
                state.chatHistory
            }
            val chatArr = JSONArray()
            for (turn in recentChat) {
                chatArr.put(
                    JSONObject().apply {
                        put("id", turn.id)
                        put("isUser", turn.isUser)
                        put("text", turn.text)
                        put("channel", turn.channel.name)
                        put("timestampMs", turn.timestampMs)
                    }
                )
            }
            root.put("chatHistory", chatArr)
            return root.toString()
        }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    @Volatile
    var boundPort: Int = -1
        private set

    fun start(port: Int = DEFAULT_PORT) {
        if (serverJob?.isActive == true && boundPort > 0) return
        stop()
        serverJob = scope.launch {
            try {
                val loopback = InetAddress.getByName("127.0.0.1")
                val ss = ServerSocket(port, 8, loopback)
                serverSocket = ss
                boundPort = ss.localPort
                GepyLog.i(TAG, "Debug Live State Mirror aktif di 127.0.0.1:$boundPort")
                while (isActive && !ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    launch {
                        try {
                            handleClient(client)
                        } catch (_: Throwable) {
                            // Abaikan soket klien yang timeout atau memutus koneksi secara sepihak
                        }
                    }
                }
            } catch (e: Throwable) {
                GepyLog.w(TAG, "Gagal menjalankan Live State Mirror: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        boundPort = -1
        serverJob?.cancel()
        serverJob = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 2000
            if (client.inetAddress?.isLoopbackAddress != true) {
                writeHttpResponse(client, 403, """{"error":"Loopback only"}""")
                return
            }
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)?.uppercase().orEmpty()
            val path = parts.getOrNull(1)?.substringBefore('?').orEmpty()
            if (method == "GET" && (path == "/state" || path == "/" || path == "/health")) {
                val body = serializeStateSnapshotJson(stateProvider())
                writeHttpResponse(client, 200, body)
            } else {
                writeHttpResponse(client, 404, """{"error":"Not found"}""")
            }
        }
    }

    private fun writeHttpResponse(client: Socket, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            403 -> "Forbidden"
            else -> "Not Found"
        }
        val bodyBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        client.getOutputStream().apply {
            write(headers)
            write(bodyBytes)
            flush()
        }
    }
}
