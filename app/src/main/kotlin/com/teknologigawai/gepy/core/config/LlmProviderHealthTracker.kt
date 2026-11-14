package com.teknologigawai.gepy.core.config

import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot status penangguhan sementara (*cooldown / circuit breaker*) penyedia LLM
 * akibat error billing (`HTTP 402`), otorisasi (`401`/`403`), atau rate limit (`429`).
 */
data class ProviderCooldownStatus(
    val provider: PreferredLlmProvider,
    val httpStatus: Int,
    val reason: String,
    val remainingMs: Long
)

/**
 * Pelacak kesehatan penyedia LLM non-statis (*In-Memory Circuit Breaker*).
 *
 * Mencegah pemborosan latensi (`1-2s` per langkah ReAct) ketika suatu penyedia seperti
 * OpenRouter (`CUSTOM_OPENAI`) mengembalikan `HTTP 402` (kredit habis) secara berulang.
 * Cooldown otomatis diikat ke *credential signature* (endpoint + model + hash API key),
 * sehingga perubahan kredensial atau pengaturan BYOK oleh pengguna langsung memulihkan penyedia.
 */
object LlmProviderHealthTracker {
    const val BILLING_OR_AUTH_COOLDOWN_MS: Long = 30 * 60_000L
    const val RATE_LIMIT_COOLDOWN_MS: Long = 60_000L

    private data class CooldownEntry(
        val provider: PreferredLlmProvider,
        val signature: String,
        val httpStatus: Int,
        val reason: String,
        val untilMs: Long
    )

    private val entries = ConcurrentHashMap<PreferredLlmProvider, CooldownEntry>()

    private val AFFORDABLE_TOKENS_REGEX = Regex(
        """can\s+only\s+afford\s+(\d+)""",
        RegexOption.IGNORE_CASE
    )

    /** Menghasilkan tanda tangan non-rahasia kredensial penyedia agar cooldown otomatis batal saat key/model berubah. */
    fun credentialSignature(
        provider: PreferredLlmProvider,
        endpointUrl: String,
        apiKey: String,
        modelName: String
    ): String {
        return "${provider.wireName}|${endpointUrl.trim()}|${modelName.trim()}|${apiKey.trim().hashCode()}"
    }

    /** Menentukan durasi cooldown berdasarkan kode HTTP error. Mengembalikan `0` jika bukan error yang perlu cooldown. */
    fun cooldownDurationForStatus(statusCode: Int): Long = when (statusCode) {
        401, 402, 403, 404 -> BILLING_OR_AUTH_COOLDOWN_MS
        408, 429, 500, 502, 503, 504 -> RATE_LIMIT_COOLDOWN_MS
        else -> 0L
    }

    /** Mengekstrak batas `max_tokens` terjangkau bila OpenRouter mengembalikan pesan `can only afford <N>`. */
    fun parseAffordableMaxTokensFrom402(errorText: String?): Int? {
        if (errorText.isNullOrBlank()) return null
        val match = AFFORDABLE_TOKENS_REGEX.find(errorText) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    fun markFailure(
        provider: PreferredLlmProvider,
        signature: String,
        statusCode: Int,
        rawReason: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (provider == PreferredLlmProvider.AUTO) return
        val durationMs = cooldownDurationForStatus(statusCode)
        if (durationMs <= 0L) return

        val friendlyReason = when (statusCode) {
            402 -> {
                val affordable = parseAffordableMaxTokensFrom402(rawReason)
                if (affordable != null) {
                    "Kredit tidak cukup (HTTP 402 • maks $affordable token)"
                } else {
                    "Kredit penyedia habis (HTTP 402)"
                }
            }
            401, 403 -> "API Key ditolak (HTTP $statusCode)"
            429 -> "Batas permintaan tercapai (HTTP 429)"
            else -> "Gangguan sementara (HTTP $statusCode)"
        }

        entries[provider] = CooldownEntry(
            provider = provider,
            signature = signature,
            httpStatus = statusCode,
            reason = friendlyReason,
            untilMs = nowMs + durationMs
        )
    }

    fun getActiveCooldown(
        provider: PreferredLlmProvider,
        signature: String,
        nowMs: Long = System.currentTimeMillis()
    ): ProviderCooldownStatus? {
        val entry = entries[provider] ?: return null
        if (entry.signature != signature || entry.untilMs <= nowMs) {
            entries.remove(provider, entry)
            return null
        }
        return ProviderCooldownStatus(
            provider = entry.provider,
            httpStatus = entry.httpStatus,
            reason = entry.reason,
            remainingMs = entry.untilMs - nowMs
        )
    }

    fun activeCooldownsSnapshot(nowMs: Long = System.currentTimeMillis()): List<ProviderCooldownStatus> {
        val active = mutableListOf<ProviderCooldownStatus>()
        for ((provider, entry) in entries) {
            if (entry.untilMs <= nowMs) {
                entries.remove(provider, entry)
            } else {
                active.add(
                    ProviderCooldownStatus(
                        provider = entry.provider,
                        httpStatus = entry.httpStatus,
                        reason = entry.reason,
                        remainingMs = entry.untilMs - nowMs
                    )
                )
            }
        }
        return active.sortedBy { it.provider.ordinal }
    }

    fun clearProvider(provider: PreferredLlmProvider) {
        entries.remove(provider)
    }

    fun resetAll() {
        entries.clear()
    }
}
