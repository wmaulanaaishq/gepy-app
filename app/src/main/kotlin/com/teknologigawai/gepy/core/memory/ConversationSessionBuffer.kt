package com.teknologigawai.gepy.core.memory

import java.util.UUID

/** Kanal asal giliran percakapan pada sesi aktif lintas moda. */
enum class ConversationChannel(val badgeLabel: String) {
    TEXT_CHAT("Chat"),
    LIVE_CALL("Live Call")
}

/** Satu giliran percakapan (*turn*) terpadu antara pengguna dan Gepy (Chat Teks maupun Gemini Live Call). */
data class ChatTurnItem(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val channel: ConversationChannel,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isUser: Boolean
        get() = role.equals("user", ignoreCase = true)
}

/**
 * Penyangga konteks percakapan bergulir (*Rolling Cross-Modal Context Window*) berjejak memori ultra-ringan (`< 10 KB` RAM)
 * untuk menyinkronkan percakapan antara **Chat Teks** dan **Gemini 3.8 Live Call** secara dua arah.
 */
class ConversationSessionBuffer(
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_MAX_TURNS = 12
        const val DEFAULT_MAX_TOTAL_CHARS = 3_000
        private const val DEDUP_WINDOW_MS = 3_000L
    }

    private val lock = Any()
    private val turns = ArrayDeque<ChatTurnItem>()

    /** Menambahkan giliran baru (`user` atau `assistant`) dan memangkas entri terlama bila melampaui anggaran. */
    fun appendTurn(
        role: String,
        text: String,
        channel: ConversationChannel
    ): List<ChatTurnItem> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return snapshot()
        val normalizedRole = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
        val nowMs = clockMsProvider()

        synchronized(lock) {
            val last = turns.lastOrNull()
            if (last != null &&
                last.role == normalizedRole &&
                last.text.equals(cleanText, ignoreCase = true) &&
                (nowMs - last.timestampMs) in 0L..DEDUP_WINDOW_MS
            ) {
                return turns.toList()
            }

            turns.addLast(
                ChatTurnItem(
                    role = normalizedRole,
                    text = cleanText,
                    channel = channel,
                    timestampMs = nowMs
                )
            )
            evictOverflowLocked()
            return turns.toList()
        }
    }

    /** Mengembalikan salinan imutabel seluruh giliran pada sesi aktif saat ini. */
    fun snapshot(): List<ChatTurnItem> = synchronized(lock) { turns.toList() }

    /** Bernilai `true` bila belum ada percakapan di sesi aktif. */
    fun isEmpty(): Boolean = synchronized(lock) { turns.isEmpty() }

    /** Mengosongkan jendela konteks sesi aktif (*Sesi Baru*) tanpa menghapus memori permanen Room. */
    fun clear(): List<ChatTurnItem> = synchronized(lock) {
        turns.clear()
        emptyList()
    }

    /**
     * Merangkai blok konteks ringkas lintas moda untuk disuntikkan ke sesi `Gemini 3.8 Live`
     * agar panggilan suara langsung melanjutkan topik yang baru dibahas di Chat.
     */
    fun toPromptContextBlock(maxChars: Int = 2_200): String {
        val items = snapshot()
        if (items.isEmpty()) return ""

        val formattedLines = items.map { item ->
            val speaker = if (item.isUser) "User" else "Gepy"
            "- $speaker [${item.channel.badgeLabel}]: ${item.text}"
        }

        val selectedReversed = mutableListOf<String>()
        var usedChars = 0
        val header = "[RIWAYAT PERCAKAPAN SESI AKTIF (CHAT & LIVE CALL)]"
        val budget = (maxChars - header.length - 4).coerceAtLeast(120)

        for (line in formattedLines.asReversed()) {
            val lineCost = line.length + 1
            if (selectedReversed.isNotEmpty() && usedChars + lineCost > budget) {
                break
            }
            val clippedLine = if (line.length > budget) {
                line.take(budget - 3).trimEnd() + "..."
            } else {
                line
            }
            selectedReversed.add(clippedLine)
            usedChars += clippedLine.length + 1
        }

        if (selectedReversed.isEmpty()) return ""
        return buildString {
            appendLine(header)
            for (line in selectedReversed.asReversed()) {
                appendLine(line)
            }
        }.trim()
    }

    /**
     * Mengembalikan daftar pasangan `(role, content)` berurutan untuk disisipkan ke array
     * `messages` (OpenAI/Fireworks) atau `contents` (Gemini REST).
     */
    fun toChatHistoryMessages(excludeLatestUserText: String? = null): List<Pair<String, String>> {
        val items = snapshot()
        if (items.isEmpty()) return emptyList()
        val trimmedExclude = excludeLatestUserText?.trim().orEmpty()
        val filtered = if (trimmedExclude.isNotEmpty() &&
            items.last().isUser &&
            items.last().text.equals(trimmedExclude, ignoreCase = true)
        ) {
            items.dropLast(1)
        } else {
            items
        }
        return filtered.map { it.role to it.text }
    }

    private fun evictOverflowLocked() {
        while (turns.size > maxTurns.coerceAtLeast(2)) {
            turns.removeFirst()
        }
        var totalChars = turns.sumOf { it.text.length }
        while (turns.size > 2 && totalChars > maxTotalChars.coerceAtLeast(300)) {
            val removed = turns.removeFirst()
            totalChars -= removed.text.length
        }
    }
}
