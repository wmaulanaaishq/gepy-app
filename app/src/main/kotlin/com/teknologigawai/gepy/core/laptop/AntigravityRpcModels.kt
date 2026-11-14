package com.teknologigawai.gepy.core.laptop

import org.json.JSONObject

/**
 * Permintaan terstruktur Agent-to-Agent (A2A) M2M dari Gepy Android ke Antigravity di Laptop.
 */
data class AntigravityRpcRequest(
    val action: String,
    val target: String = "",
    val prompt: String = "",
    val parameters: Map<String, Any?> = emptyMap(),
    val requestId: String = "a2a_${System.currentTimeMillis()}",
    val protocol: String = "GEPY_A2A_v1",
    val clientDevice: String = "Samsung-SM-A065F",
    val isAsync: Boolean = false,
    val callbackUrl: String = ""
)

/**
 * Balasan terstruktur Agent-to-Agent (A2A) M2M dari Antigravity di Laptop ke Gepy Android.
 */
data class AntigravityRpcResponse(
    val status: String,
    val action: String,
    val summaryTts: String,
    val markdownDetails: String = "",
    val executionTimeMs: Long = 0L,
    val resultData: Map<String, Any?> = emptyMap(),
    val requestId: String = "",
    val protocol: String = "GEPY_A2A_v1"
) {
    companion object {
        fun fromJson(
            json: JSONObject,
            defaultAction: String = "",
            defaultRequestId: String = ""
        ): AntigravityRpcResponse {
            val resultDataMap = mutableMapOf<String, Any?>()
            val resultDataObj = json.optJSONObject("result_data")
            if (resultDataObj != null) {
                val keys = resultDataObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    resultDataMap[k] = resultDataObj.get(k)
                }
            }
            return AntigravityRpcResponse(
                status = json.optString("status", "SUCCESS"),
                action = json.optString("action", defaultAction),
                summaryTts = json.optString("summary_tts", "Perintah Antigravity selesai diproses."),
                markdownDetails = json.optString("markdown_details", ""),
                executionTimeMs = json.optLong("execution_time_ms", 0L),
                resultData = resultDataMap,
                requestId = json.optString("request_id", defaultRequestId),
                protocol = json.optString("protocol", "GEPY_A2A_v1")
            )
        }

        fun fromJsonString(
            rawJson: String,
            defaultAction: String = "",
            defaultRequestId: String = ""
        ): AntigravityRpcResponse {
            return fromJson(JSONObject(rawJson.trim()), defaultAction, defaultRequestId)
        }
    }
}
