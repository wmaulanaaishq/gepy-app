package com.teknologigawai.gepy.core.iot

import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.laptop.AntigravityRpcResponse
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.wellness.WellnessAndGuardianEngine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Representasi terurai satu paket telemetri dari node **ESP32-S3 Wi-Fi CSI**. */
data class EspCsiEventPayload(
    val event: String,
    val room: String = "kamar",
    val durationMinutes: Int = 0,
    val biometricOwnerMatch: Boolean = true,
    val confidence: Double = 1.0,
    val respirationBpm: Int? = null
)

/**
 * Jembatan Lokal **ESP32-S3 Wi-Fi CSI & RF Biometric Bridge** untuk **Gepy v5.0**:
 *
 * - Menjalankan peladen HTTP JSON ringan (`ServerSocket` pada `Dispatchers.IO`, `< 1 MB` RAM, 0 cloud)
 *   yang hanya dibuka ketika sakelar `espCsiBridgeEnabled` aktif di Pengaturan.
 * - **Zero-Trust LAN Isolation**: Hanya menerima paket dari alamat IP privat lokal (`192.168.x.x`,
 *   `10.x.x.x`, `172.16-31.x.x`, atau `127.0.0.1`). Menolak koneksi publik (`403 Forbidden`).
 * - Mendukung pemicu *touchless* `AIR_GESTURE_WAKE`, verifikasi gaya berjalan `GAIT_BIOMETRIC`
 *   (*Privacy Shield* otomatis saat ada orang lain di ruangan), dan pemantauan durasi rebahan `BED_OCCUPIED`.
 */
class EspCsiAmbientBridge(
    private val wellnessEngine: WellnessAndGuardianEngine,
    private val onAirGestureWake: suspend () -> Unit = {},
    private val onOwnerPresenceGreeting: suspend (room: String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val proactivityLevelProvider: () -> ProactivityLevel = { ProactivityLevel.CONTEXT_COPILOT }
) {
    companion object {
        private const val TAG = "EspCsiBridge"
        private const val WELCOME_GREETING_COOLDOWN_MS = 4 * 60 * 60 * 1000L

        /** Memastikan alamat pengirim berasal dari jaringan lokal privat (LAN), loopback, atau VPN Tailscale. */
        fun isPrivateOrLoopbackAddress(address: InetAddress?): Boolean {
            if (address == null) return false
            val host = address.hostAddress ?: ""
            return address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                host.startsWith("100.")
        }

        /** Mendeteksi alamat IPv4 Tailscale (100.x.x.x) jika aktif, atau fallback ke IPv4 Wi-Fi LAN. */
        fun resolveDeviceBestIpv4(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                var lanFallback = "127.0.0.1"
                for (intf in interfaces) {
                    val isLoopback = try { intf.isLoopback } catch (_: Throwable) { false }
                    if (isLoopback) continue
                    val isUp = try { intf.isUp } catch (_: Throwable) { true }

                    val addrs = try { intf.inetAddresses } catch (_: Throwable) { null } ?: continue
                    for (addr in addrs) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            // Prioritaskan alamat IP VPN Tailscale CGNAT (100.64.0.0/10)
                            if (host.startsWith("100.")) {
                                return host
                            }
                            if (isUp && addr.isSiteLocalAddress && lanFallback == "127.0.0.1") {
                                lanFallback = host
                            }
                        }
                    }
                }
                lanFallback
            } catch (_: Throwable) {
                "127.0.0.1"
            }
        }

        /** Mengurai string JSON mentah dari firmware ESP32-S3 `esp-csi` menjadi [EspCsiEventPayload]. */
        fun parsePayloadJson(rawJson: String): EspCsiEventPayload {
            val obj = JSONObject(rawJson.trim())
            val event = obj.optString("event", "").trim().uppercase()
            if (event.isEmpty()) {
                throw IllegalArgumentException("Field 'event' wajib diisi pada payload CSI.")
            }
            val bpmRaw = if (obj.has("respiration_bpm") && !obj.isNull("respiration_bpm")) {
                obj.optInt("respiration_bpm", 0).takeIf { it > 0 }
            } else {
                null
            }
            return EspCsiEventPayload(
                event = event,
                room = obj.optString("room", "kamar").trim().ifEmpty { "kamar" },
                durationMinutes = obj.optInt("duration_minutes", 0).coerceAtLeast(0),
                biometricOwnerMatch = obj.optBoolean("biometric_owner_match", true),
                confidence = obj.optDouble("confidence", 1.0).coerceIn(0.0, 1.0),
                respirationBpm = bpmRaw
            )
        }

        /** Mendeteksi alamat IPv4 Wi-Fi lokal HP Android untuk ditampilkan di Pengaturan ESP32. */
        fun resolveDeviceLocalIpv4(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
                "127.0.0.1"
            } catch (_: Throwable) {
                "127.0.0.1"
            }
        }
    }

    var onAntigravityCallbackListener: ((AntigravityRpcResponse) -> Unit)? = null

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var lastWelcomeGreetingWallMs: Long? = null

    /** Menyalakan peladen webhook lokal ESP32-S3 CSI pada [port] (gunakan `0` untuk port acak pada unit test). */
    @Synchronized
    fun startServer(port: Int = 8765): Boolean {
        if (_isRunningFlow.value && serverSocket != null && serverSocket?.isClosed == false) {
            return true
        }
        stopServer()
        return try {
            val ss = ServerSocket(port)
            serverSocket = ss
            _isRunningFlow.value = true
            acceptJob = scope.launch {
                GepyLog.i(TAG, "ESP32-S3 CSI LAN Bridge aktif pada port ${ss.localPort}")
                while (isActive && !ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    launch {
                        handleClientSocket(client)
                    }
                }
            }
            true
        } catch (e: Throwable) {
            GepyLog.w(TAG, "Gagal membuka port ESP32 CSI ($port): ${e.message}")
            _isRunningFlow.value = false
            false
        }
    }

    /** Menghentikan peladen webhook lokal dan membebaskan soket segera. */
    @Synchronized
    fun stopServer() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        _isRunningFlow.value = false
    }

    /** Memproses event CSI baik dari jaringan ESP32-S3 maupun dari tombol simulator di Dashboard. */
    suspend fun processIncomingEventJson(
        rawJson: String,
        nowWallMs: Long = System.currentTimeMillis()
    ): EspCsiEventPayload {
        val parsed = parsePayloadJson(rawJson)
        wellnessEngine.ingestCsiTelemetry(
            eventType = parsed.event,
            room = parsed.room,
            durationMinutes = parsed.durationMinutes,
            biometricOwnerMatch = parsed.biometricOwnerMatch,
            respirationBpm = parsed.respirationBpm,
            nowWallMs = nowWallMs
        )

        when (parsed.event) {
            "AIR_GESTURE_WAKE" -> {
                // Keamanan Biometrik: hanya izinkan Air-Gesture jika terverifikasi pemilik sah!
                if (parsed.biometricOwnerMatch) {
                    onAirGestureWake()
                } else {
                    GepyLog.w(TAG, "AIR_GESTURE_WAKE ditolak karena biometric_owner_match=false.")
                }
            }
            "PRESENCE_ENTER" -> {
                if (proactivityLevelProvider() == ProactivityLevel.ZEN_PASSIVE) {
                    GepyLog.i(TAG, "Sapaan datang PRESENCE_ENTER ditahan karena ProactivityLevel=ZEN_PASSIVE.")
                } else {
                    val lastGreetingMs = lastWelcomeGreetingWallMs
                    val cooldownPassed = lastGreetingMs == null ||
                        (nowWallMs - lastGreetingMs) >= WELCOME_GREETING_COOLDOWN_MS
                    if (parsed.biometricOwnerMatch && cooldownPassed) {
                        lastWelcomeGreetingWallMs = nowWallMs
                        onOwnerPresenceGreeting(parsed.room)
                    }
                }
            }
        }
        return parsed
    }

    private suspend fun handleClientSocket(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 3_000
            if (!isPrivateOrLoopbackAddress(client.inetAddress)) {
                writeHttpResponse(client, 403, """{"status":"FORBIDDEN","reason":"LAN_ONLY"}""")
                return
            }

            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase().orEmpty()
            val path = parts.getOrNull(1).orEmpty()

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                }
            }

            if (method == "GET" && path.startsWith("/health")) {
                writeHttpResponse(client, 200, """{"status":"OK","bridge":"gepy_v5_esp32_csi"}""")
                return
            }

            if (method == "POST" && path.startsWith("/api/antigravity/callback")) {
                val bodyChars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val n = reader.read(bodyChars, readTotal, contentLength - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                val bodyStr = String(bodyChars, 0, readTotal)
                try {
                    val resp = AntigravityRpcResponse.fromJsonString(bodyStr)
                    GepyLog.i(TAG, "Menerima async callback Antigravity: action=${resp.action}, status=${resp.status}")
                    scope.launch {
                        onAntigravityCallbackListener?.invoke(resp)
                    }
                    writeHttpResponse(
                        client,
                        200,
                        """{"status":"DELIVERED","request_id":"${resp.requestId}"}"""
                    )
                } catch (e: Exception) {
                    GepyLog.w(TAG, "Gagal mengurai async callback: ${e.message}")
                    writeHttpResponse(client, 400, """{"status":"BAD_REQUEST","error":"${e.message?.take(80)}"}""")
                }
                return
            }

            if (method == "POST" && path.startsWith("/csi-event")) {
                val bodyChars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val n = reader.read(bodyChars, readTotal, contentLength - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                val bodyStr = String(bodyChars, 0, readTotal)
                try {
                    val processed = processIncomingEventJson(bodyStr)
                    writeHttpResponse(
                        client,
                        200,
                        """{"status":"OK","event":"${processed.event}","privacy_shield":${!processed.biometricOwnerMatch}}"""
                    )
                } catch (e: Exception) {
                    writeHttpResponse(client, 400, """{"status":"BAD_REQUEST","error":"${e.message?.take(80)}"}""")
                }
                return
            }

            writeHttpResponse(client, 404, """{"status":"NOT_FOUND"}""")
        }
    }

    private fun writeHttpResponse(socket: Socket, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            else -> "Not Found"
        }
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }
}
