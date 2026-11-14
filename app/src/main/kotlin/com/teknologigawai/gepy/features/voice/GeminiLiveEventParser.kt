package com.teknologigawai.gepy.features.voice

import java.util.Base64
import org.json.JSONObject

/** Status siklus hidup koneksi panggilan dua arah **Gemini Live Session Supervisor**. */
enum class LiveConnectionState {
    CLOSED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/** Representasi permintaan pemanggilan fungsi asinkron dari server Gemini Live. */
data class GeminiLiveToolCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>
)

/** Hasil penguraian satu pesan JSON `BidiGenerateContentServerMessage` dari Gemini Live API. */
data class GeminiLiveParsedEvent(
    val isSetupComplete: Boolean = false,
    val audioChunksPcm24k: List<ByteArray> = emptyList(),
    val modelTextParts: List<String> = emptyList(),
    val interimInputTranscript: String? = null,
    val finalInputTranscript: String? = null,
    val outputTranscript: String? = null,
    val interrupted: Boolean = false,
    val turnComplete: Boolean = false,
    val interactionStatus: String? = null,
    val toolCalls: List<GeminiLiveToolCall> = emptyList(),
    val cancelledToolCallIds: List<String> = emptyList(),
    val resumptionHandle: String? = null,
    val goAway: Boolean = false
)

/** Parser murni tanpa ketergantungan I/O untuk protokol WebSocket `BidiGenerateContent`. */
object GeminiLiveEventParser {

    /** Mengurai string JSON pesan server menjadi [GeminiLiveParsedEvent]. */
    fun parseServerMessage(rawJson: String): GeminiLiveParsedEvent {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return GeminiLiveParsedEvent()
        }
        val decoded = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return GeminiLiveParsedEvent()
        }

        val isSetupComplete = decoded.has("setupComplete")
        val goAway = decoded.has("goAway")

        var resumptionHandle: String? = null
        val resumption = decoded.optJSONObject("sessionResumptionUpdate")
        if (resumption != null) {
            val resumable = resumption.optBoolean("resumable", true)
            val handle = resumption.optString("newHandle", "").trim()
            if (resumable && handle.isNotEmpty() && !handle.equals("null", ignoreCase = true)) {
                resumptionHandle = handle
            }
        }

        val audioChunks = mutableListOf<ByteArray>()
        val textParts = mutableListOf<String>()
        var interimIn: String? = null
        var finalIn: String? = null
        var outTranscript: String? = null
        var interrupted = false
        var turnComplete = false
        var interactionStatus: String? = null

        val serverContent = decoded.optJSONObject("serverContent")
        if (serverContent != null) {
            interrupted = serverContent.optBoolean("interrupted", false)
            turnComplete = serverContent.optBoolean("turnComplete", false)
            if (serverContent.has("interactionStatus") && !serverContent.isNull("interactionStatus")) {
                interactionStatus = serverContent.optString("interactionStatus")
            }

            // Proses SELURUH part dalam satu event secara simultan sesuai spesifikasi Live API
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val text = part.optString("text", "")
                    if (text.isNotEmpty() && !part.isNull("text")) {
                        textParts.add(text)
                    }
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val b64 = inlineData.optString("data", "").trim()
                        if (b64.isNotEmpty()) {
                            try {
                                audioChunks.add(Base64.getDecoder().decode(b64))
                            } catch (_: IllegalArgumentException) {
                                // Abaikan fragmen base64 korup
                            }
                        }
                    }
                }
            }

            interimIn = readTranscriptText(serverContent.optJSONObject("interimInputTranscription"))
            finalIn = readTranscriptText(serverContent.optJSONObject("inputTranscription"))
            outTranscript = readTranscriptText(serverContent.optJSONObject("outputTranscription"))
        }

        val toolCalls = mutableListOf<GeminiLiveToolCall>()
        val rawToolCall = decoded.optJSONObject("toolCall")
        val calls = rawToolCall?.optJSONArray("functionCalls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val item = calls.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val name = item.optString("name", "").trim()
                val rawArgs = item.optJSONObject("args")
                val argsMap = mutableMapOf<String, Any?>()
                if (rawArgs != null) {
                    val keys = rawArgs.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val v = rawArgs.opt(key)
                        argsMap[key] = if (v === JSONObject.NULL) null else v
                    }
                }
                if (name.isNotEmpty()) {
                    toolCalls.add(GeminiLiveToolCall(id = id, name = name, args = argsMap))
                }
            }
        }

        val cancelledIds = mutableListOf<String>()
        val cancellation = decoded.optJSONObject("toolCallCancellation")
        val ids = cancellation?.optJSONArray("ids")
        if (ids != null) {
            for (i in 0 until ids.length()) {
                val idStr = ids.optString(i, "").trim()
                if (idStr.isNotEmpty()) {
                    cancelledIds.add(idStr)
                }
            }
        }

        return GeminiLiveParsedEvent(
            isSetupComplete = isSetupComplete,
            audioChunksPcm24k = audioChunks,
            modelTextParts = textParts,
            interimInputTranscript = interimIn,
            finalInputTranscript = finalIn,
            outputTranscript = outTranscript,
            interrupted = interrupted,
            turnComplete = turnComplete,
            interactionStatus = interactionStatus,
            toolCalls = toolCalls,
            cancelledToolCallIds = cancelledIds,
            resumptionHandle = resumptionHandle,
            goAway = goAway
        )
    }

    private fun readTranscriptText(node: JSONObject?): String? {
        if (node == null || node.isNull("text")) return null
        val text = node.optString("text", "")
        return text.ifEmpty { null }
    }
}
