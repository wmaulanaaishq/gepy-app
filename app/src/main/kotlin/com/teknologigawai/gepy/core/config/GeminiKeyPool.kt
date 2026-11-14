package com.teknologigawai.gepy.core.config

import java.util.concurrent.ConcurrentHashMap

/**
 * Peran penggunaan Gemini API Key untuk pemisahan kuota (Role Separation) & Failover `HTTP 429`:
 * - [CHAT_REASONING], [LIVE_VOICE], [WEB_GROUNDING], [MEMORY_SYNTHESIS]: mengutamakan Key 1 lalu failover ke Key 2.
 * - [EMBEDDING]: mengutamakan Key 2 terlebih dahulu lalu failover ke Key 1 agar tidak memakan RPM/TPM Live Call.
 */
enum class GeminiKeyPurpose {
    CHAT_REASONING,
    LIVE_VOICE,
    WEB_GROUNDING,
    MEMORY_SYNTHESIS,
    EMBEDDING
}

/**
 * Pengelola kolam kunci Gemini ganda (**Dual-Key Pool & Auto-Failover**):
 * - Memisahkan beban embedding semantik (`gemini-embedding-2`) ke Key 2 dan interaksi real-time (`gemini-3.8-live` / `gemini-2.5-flash`) ke Key 1.
 * - Secara otomatis mendeteksi kehabisan kuota (`HTTP 429` / `RESOURCE_EXHAUSTED`) dan memindahkan permintaan
 *   ke kunci cadangan sambil memberi masa jeda (*cooldown*) 60 detik pada kunci yang sedang penuh.
 */
object GeminiKeyPool {
    const val DEFAULT_QUOTA_COOLDOWN_MS: Long = 60_000L
    const val INVALID_KEY_COOLDOWN_MS: Long = 10 * 60_000L

    private val cooldownUntilMap = ConcurrentHashMap<String, Long>()

    fun sanitizeKey(raw: String): String {
        var cleaned = raw.trim().trim('"', '\'', '`')
        if (cleaned.startsWith("Bearer ", ignoreCase = true)) {
            cleaned = cleaned.substring(7).trim()
        }
        if (cleaned.contains('=')) {
            val afterEq = cleaned.substringAfter('=').trim().trim('"', '\'')
            if (afterEq.isNotEmpty()) {
                cleaned = afterEq
            }
        }
        return cleaned.replace("\\s+".toRegex(), "")
    }

    /**
     * Mengembalikan daftar kandidat kunci terurut berdasarkan [purpose] serta menyaring kunci yang
     * sedang berada dalam masa jeda (*cooldown*), selama masih ada kunci sehat tersisa.
     */
    fun orderedKeys(
        purpose: GeminiKeyPurpose,
        primaryKey: String,
        secondaryKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        val p = sanitizeKey(primaryKey)
        val s = sanitizeKey(secondaryKey)
        val preferredOrder = when (purpose) {
            GeminiKeyPurpose.EMBEDDING -> listOf(s, p)
            else -> listOf(p, s)
        }.filter { it.isNotEmpty() }.distinct()

        if (preferredOrder.isEmpty()) return emptyList()

        val healthy = preferredOrder.filter { key ->
            val until = cooldownUntilMap[key] ?: 0L
            until <= nowMs
        }
        return healthy.ifEmpty { preferredOrder }
    }

    /** Menandai suatu kunci sedang berada di masa jeda (*cooldown*) akibat limitasi kuota atau penolakan. */
    fun markQuotaExhausted(
        apiKey: String,
        cooldownMs: Long = DEFAULT_QUOTA_COOLDOWN_MS,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val clean = sanitizeKey(apiKey)
        if (clean.isEmpty()) return
        cooldownUntilMap[clean] = nowMs + cooldownMs.coerceAtLeast(1_000L)
    }

    fun isInCooldown(apiKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val clean = sanitizeKey(apiKey)
        if (clean.isEmpty()) return false
        return (cooldownUntilMap[clean] ?: 0L) > nowMs
    }

    fun isQuotaOrRateLimitResponse(statusCode: Int, responseBody: String): Boolean {
        if (statusCode == 429) return true
        return responseBody.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            responseBody.contains("quota", ignoreCase = true) ||
            responseBody.contains("rate limit", ignoreCase = true)
    }

    fun isKeyRejectedResponse(statusCode: Int): Boolean {
        return statusCode == 401 || statusCode == 403
    }

    fun resetCooldownsForTest() {
        cooldownUntilMap.clear()
    }
}
