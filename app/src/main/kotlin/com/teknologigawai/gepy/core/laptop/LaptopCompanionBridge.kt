package com.teknologigawai.gepy.core.laptop

import com.teknologigawai.gepy.core.logging.GepyLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Representasi status laptop Windows yang diterima melalui jaringan privat Tailscale.
 */
data class LaptopStatusSnapshot(
    val status: String,
    val hostname: String,
    val activeWindow: String,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val uptimeSeconds: Long,
    val summaryId: String
)

/**
 * Jembatan Komunikasi Jaringan antara **Gepy Native (Android)** dan **Laptop Windows Companion**
 * melalui jaringan privat **Tailscale Mesh (WireGuard)**.
 *
 * Menggunakan `OkHttpClient` dengan *timeout* ketat (3 detik connect, 5 detik read) agar tidak
 * membekukan alur suara ReAct / Gemini Live saat laptop sleep atau Tailscale terputus.
 */
class LaptopCompanionBridge(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "LaptopBridge"
        private const val UNCONFIGURED_HOST_MESSAGE =
            "Host Tailscale laptop belum diisi. Atur di Pengaturan atau local_secrets.json."
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun sanitizeHost(rawHost: String): String {
            var h = rawHost.trim()
            if (h.isEmpty()) return ""
            if (!h.startsWith("http://") && !h.startsWith("https://")) {
                h = "http://$h"
            }
            return h.trimEnd('/')
        }

        private fun unconfiguredMap(): Map<String, Any?> = mapOf(
            "status" to "UNCONFIGURED",
            "summary_id" to UNCONFIGURED_HOST_MESSAGE
        )
    }

    /**
     * Membaca status kesehatan, jendela aktif, dan daya baterai laptop.
     */
    fun getLaptopStatus(host: String = ""): Map<String, Any?> {
        val baseUrl = sanitizeHost(host)
        if (baseUrl.isEmpty()) return unconfiguredMap()
        val request = Request.Builder()
            .url("$baseUrl/api/status")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val json = JSONObject(bodyStr)
                    val explicitSummary = json.optString("summary_id", "").trim()
                    buildMap<String, Any?> {
                        put("status", "SUCCESS")
                        put("hostname", json.optString("hostname", "Laptop"))
                        put("active_window", json.optString("active_window", "Desktop"))
                        put("battery_percent", json.optInt("battery_percent", -1))
                        put("is_charging", json.optBoolean("is_charging", false))
                        put("uptime_seconds", json.optLong("uptime_seconds", 0L))
                        if (explicitSummary.isNotEmpty()) {
                            put("summary_id", explicitSummary)
                        }
                    }
                } else {
                    mapOf(
                        "status" to "ERROR",
                        "code" to response.code,
                        "summary_id" to "Gagal membaca status laptop (HTTP ${response.code})."
                    )
                }
            }
        } catch (e: Exception) {
            GepyLog.w(TAG, "Gagal menghubungi laptop di $baseUrl: ${e.message}")
            mapOf(
                "status" to "UNREACHABLE",
                "error" to (e.message ?: "Koneksi terputus"),
                "summary_id" to "Laptop sedang tidak terjangkau. Pastikan Tailscale dan daemon laptop aktif."
            )
        }
    }

    /**
     * Menjalankan aksi sistem langsung di laptop Windows (lock, volume, open_app, media, sleep).
     */
    fun executeAction(
        action: String,
        target: String = "",
        params: Map<String, Any?> = emptyMap(),
        host: String = ""
    ): Map<String, Any?> {
        val baseUrl = sanitizeHost(host)
        if (baseUrl.isEmpty()) return unconfiguredMap()
        val payload = JSONObject().apply {
            put("action", action.trim())
            if (target.isNotEmpty()) put("target", target.trim())
            if (params.isNotEmpty()) {
                val paramsObj = JSONObject()
                params.forEach { (k, v) -> paramsObj.put(k, v) }
                put("params", paramsObj)
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/api/action")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val json = JSONObject(bodyStr)
                    val status = json.optString("status", "SUCCESS")
                    val explicitSummary = json.optString("summary_id", "").trim()
                    val errorMsg = json.optString("message", "").trim()
                    buildMap<String, Any?> {
                        put("status", status)
                        put("action", json.optString("action", action))
                        when {
                            explicitSummary.isNotEmpty() -> put("summary_id", explicitSummary)
                            status != "SUCCESS" && errorMsg.isNotEmpty() -> put("summary_id", errorMsg)
                        }
                    }
                } else {
                    mapOf(
                        "status" to "ERROR",
                        "summary_id" to "Laptop mengembalikan error (HTTP ${response.code})."
                    )
                }
            }
        } catch (e: Exception) {
            GepyLog.w(TAG, "Gagal eksekusi aksi '$action' ke laptop di $baseUrl: ${e.message}")
            mapOf(
                "status" to "UNREACHABLE",
                "summary_id" to "Tidak dapat menjalankan '$action' di laptop: laptop sedang tidak terjangkau."
            )
        }
    }

    /**
     * Mematikan laptop Windows secara total dengan jeda hitung mundur 30 detik (abortable).
     * Memerlukan [confirmed] = true agar daemon mengeksekusi shutdown.
     */
    fun shutdownLaptop(confirmed: Boolean = true, host: String = ""): Map<String, Any?> {
        return executeAction(
            action = "shutdown",
            params = mapOf("confirmed" to confirmed),
            host = host
        )
    }

    /**
     * Membatalkan proses hitung mundur shutdown pada laptop Windows.
     */
    fun cancelShutdownLaptop(host: String = ""): Map<String, Any?> {
        return executeAction(
            action = "cancel_shutdown",
            host = host
        )
    }

    /**
     * Mengambil teks dari clipboard Windows laptop.
     */
    fun getClipboardText(host: String = ""): Map<String, Any?> {
        val baseUrl = sanitizeHost(host)
        if (baseUrl.isEmpty()) return unconfiguredMap()
        val request = Request.Builder()
            .url("$baseUrl/api/clipboard")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val json = JSONObject(bodyStr)
                    val text = json.optString("text", "")
                    mapOf(
                        "status" to "SUCCESS",
                        "text" to text,
                        "length" to text.length,
                        "summary_id" to if (text.isNotEmpty()) {
                            "Teks clipboard laptop (${text.length} karakter): \"${text.take(80)}\""
                        } else {
                            "Clipboard laptop saat ini kosong."
                        }
                    )
                } else {
                    mapOf("status" to "ERROR", "summary_id" to "Gagal membaca clipboard laptop.")
                }
            }
        } catch (e: Exception) {
            mapOf(
                "status" to "UNREACHABLE",
                "summary_id" to "Tidak dapat mengakses clipboard: laptop sedang tidak terjangkau."
            )
        }
    }

    /**
     * Menyuntikkan teks ke clipboard Windows laptop.
     */
    fun setClipboardText(text: String, host: String = ""): Map<String, Any?> {
        val baseUrl = sanitizeHost(host)
        if (baseUrl.isEmpty()) return unconfiguredMap()
        val payload = JSONObject().put("text", text)
        val request = Request.Builder()
            .url("$baseUrl/api/clipboard")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val json = JSONObject(bodyStr)
                    mapOf(
                        "status" to json.optString("status", "SUCCESS"),
                        "summary_id" to json.optString("summary_id", "Teks berhasil disalin ke laptop.")
                    )
                } else {
                    mapOf("status" to "ERROR", "summary_id" to "Gagal menyalin teks ke laptop.")
                }
            }
        } catch (e: Exception) {
            mapOf(
                "status" to "UNREACHABLE",
                "summary_id" to "Tidak dapat menyalin teks: laptop sedang tidak terjangkau."
            )
        }
    }

    /**
     * Menangkap tangkapan layar desktop Windows (Base64 JPEG <= 720p).
     */
    fun captureScreenshotBase64(host: String = ""): String? {
        val baseUrl = sanitizeHost(host)
        if (baseUrl.isEmpty()) return null
        val request = Request.Builder()
            .url("$baseUrl/api/screenshot")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    json.optString("base64", "").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Mengirim simulasi klik mouse pada koordinat (X, Y) di layar desktop laptop.
     */
    fun sendGuiClick(x: Int, y: Int, host: String = ""): Boolean {
        val baseUrl = sanitizeHost(host)
        if (baseUrl.isEmpty()) return false
        val payload = JSONObject().apply {
            put("type", "click")
            put("x", x)
            put("y", y)
        }
        val request = Request.Builder()
            .url("$baseUrl/api/gui_input")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Mengeksekusi permintaan terstruktur Agent-to-Agent (A2A) M2M ke Antigravity di laptop.
     */
    fun executeAntigravityRpc(
        request: AntigravityRpcRequest,
        host: String = ""
    ): AntigravityRpcResponse {
        val baseUrl = sanitizeHost(host)
        if (baseUrl.isEmpty()) {
            return AntigravityRpcResponse(
                status = "UNCONFIGURED",
                action = request.action,
                summaryTts = UNCONFIGURED_HOST_MESSAGE,
                requestId = request.requestId
            )
        }
        val payload = JSONObject().apply {
            put("protocol", request.protocol)
            put("request_id", request.requestId)
            put("action", request.action)
            if (request.target.isNotEmpty()) put("target", request.target)
            if (request.prompt.isNotEmpty()) put("prompt", request.prompt)
            put("client_device", request.clientDevice)
            if (request.isAsync) {
                put("async", true)
            }
            if (request.callbackUrl.isNotEmpty()) {
                put("callback_url", request.callbackUrl)
            }
            if (request.parameters.isNotEmpty()) {
                val paramsObj = JSONObject()
                request.parameters.forEach { (k, v) -> paramsObj.put(k, v) }
                put("parameters", paramsObj)
            }
        }

        val rpcClient = if (request.isAsync) {
            client
        } else if (request.action in listOf("RUN_TESTS", "VERIFY_ALL", "BUILD_RELEASE")) {
            client.newBuilder()
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
        } else {
            client
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/api/antigravity/rpc")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            rpcClient.newCall(httpRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val json = JSONObject(bodyStr)
                    AntigravityRpcResponse.fromJson(json, defaultAction = request.action, defaultRequestId = request.requestId)
                } else {
                    AntigravityRpcResponse(
                        status = "ERROR",
                        action = request.action,
                        summaryTts = "Antigravity mengembalikan error (HTTP ${response.code}).",
                        requestId = request.requestId
                    )
                }
            }
        } catch (e: Exception) {
            GepyLog.w(TAG, "Gagal RPC Antigravity '${request.action}': ${e.message}")
            AntigravityRpcResponse(
                status = "UNREACHABLE",
                action = request.action,
                summaryTts = "Tidak dapat terhubung ke Antigravity di laptop. Pastikan Tailscale dan daemon aktif.",
                requestId = request.requestId
            )
        }
    }
}
