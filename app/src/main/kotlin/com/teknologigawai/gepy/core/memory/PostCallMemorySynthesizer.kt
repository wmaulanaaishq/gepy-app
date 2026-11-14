package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.config.GeminiKeyPurpose
import com.teknologigawai.gepy.core.logging.GepyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Data giliran ujaran dalam sesi panggilan suara (Live Voice Call).
 */
data class CallTranscriptTurn(
    val speaker: String, // "user" atau "assistant"
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Mesin Sintesis Memori Pasca-Panggilan (Post-Call Memory Synthesizer):
 * Mengevaluasi seluruh transkrip percakapan setelah sesi telepon selesai,
 * memangkas serpihan salah dengar/idiom sesaat, dan memetakan intisari
 * memori penting beserta relasinya ke dalam Graf Memori Temporal (Room v4).
 */
class PostCallMemorySynthesizer(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "PostCallSynthesizer"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Menjalankan evaluasi pasca-panggilan:
     * 1. Membersihkan fakta sementara atau derau yang sempat masuk selama sesi.
     * 2. Menyintesis fakta penting yang benar-benar bermakna dan memiliki relasi jelas.
     */
    suspend fun synthesizeSession(
        turns: List<CallTranscriptTurn>,
        memoryManager: UserMemoryManager,
        apiKeyProvider: () -> String = { EnvConfig.defaultGeminiApiKey },
        nowMs: Long = System.currentTimeMillis(),
        secondaryApiKeyProvider: () -> String = { "" }
    ): List<String> = withContext(Dispatchers.IO) {
        if (turns.isEmpty()) return@withContext emptyList()

        val userTurns = turns.filter { it.speaker.equals("user", ignoreCase = true) && it.text.trim().length >= 4 }
        if (userTurns.isEmpty()) return@withContext emptyList()

        GepyLog.i(TAG, "Memulai evaluasi pasca-panggilan untuk ${userTurns.size} ujaran pengguna...")

        // 1. Pembersihan proaktif: identifikasi dan hapus serpihan derau/idiom yang sempat terekam
        purgeTransientSessionMemories(memoryManager, turns)

        // 2. Kumpulkan dialog lengkap untuk sintesis
        val dialogTranscript = turns.joinToString("\n") { turn ->
            val who = if (turn.speaker.equals("user", ignoreCase = true)) "Pengguna" else "Gepy"
            "$who: ${turn.text.trim()}"
        }

        // 3. Sintesis Memori Bermakna (Key 1 -> Failover Key 2)
        val rawPrimary = GeminiKeyPool.sanitizeKey(apiKeyProvider())
        val rawSecondary = GeminiKeyPool.sanitizeKey(secondaryApiKeyProvider())
        val isMockTestKey = rawPrimary.equals("test_key", ignoreCase = true) ||
            rawPrimary.equals("dummy_key", ignoreCase = true)
        val orderedKeys = if (isMockTestKey) {
            emptyList()
        } else {
            GeminiKeyPool.orderedKeys(
                purpose = GeminiKeyPurpose.MEMORY_SYNTHESIS,
                primaryKey = rawPrimary,
                secondaryKey = rawSecondary
            )
        }

        val synthesizedFacts = if (orderedKeys.isNotEmpty() && dialogTranscript.length >= 25) {
            requestCloudPostCallSynthesis(dialogTranscript, orderedKeys)
                ?: synthesizeDeterministicFromTurns(userTurns)
        } else {
            synthesizeDeterministicFromTurns(userTurns)
        }

        // 4. Rekam fakta hasil sintesis ke Graf Memori Temporal
        val recordedFacts = mutableListOf<String>()
        for (item in synthesizedFacts) {
            val fact = item.fact.trim()
            if (fact.length >= 6 && SemanticMemoryFilter.isMeaningfulPersonalFact(item.category, fact, item.objectValue)) {
                val triple = TemporalKnowledgeGraph.extractTriple(
                    factText = fact,
                    rawCategory = item.category,
                    rawSubject = item.subject,
                    rawPredicate = item.predicate,
                    rawObjectValue = item.objectValue,
                    validHours = item.validHours,
                    nowMs = nowMs
                )
                memoryManager.recordMemory(
                    category = triple.category,
                    fact = fact,
                    subject = triple.subject,
                    predicate = triple.predicate,
                    objectValue = triple.objectValue,
                    validHours = item.validHours,
                    source = "post_call_synthesis"
                )
                recordedFacts.add(fact)
                GepyLog.i(TAG, "Fakta pasca-panggilan berhasil disintesis: $fact (kat=${triple.category}, rel=${triple.predicate})")
            }
        }

        recordedFacts
    }

    /**
     * Menghapus fakta sementara yang sempat masuk ke database selama panggilan berlangsung
     * jika terbukti hanya berupa idiom percakapan atau navigasi layar.
     */
    internal fun purgeTransientSessionMemories(
        memoryManager: UserMemoryManager,
        turns: List<CallTranscriptTurn>
    ) {
        val userTexts = turns.filter { it.speaker.equals("user", ignoreCase = true) }.map { it.text }
        val currentFacts = memoryManager.getLocalEntries()

        for (entry in currentFacts) {
            val factText = entry.fact
            if (!SemanticMemoryFilter.isMeaningfulPersonalFact(entry.category, factText, entry.subject)) {
                GepyLog.w(TAG, "Memangkas serpihan derau pasca-panggilan: $factText")
                memoryManager.deleteMemory(factText)
                continue
            }

            // Jika fakta terbukti cocok persis dengan salah satu ocehan idiom kasual
            val isFromTransientUtterance = userTexts.any { speech ->
                SemanticMemoryFilter.isTransientIdiomOrNoise(speech) &&
                    (factText.contains(speech, ignoreCase = true) || speech.contains(factText, ignoreCase = true))
            }
            if (isFromTransientUtterance) {
                GepyLog.w(TAG, "Memangkas memori dari ujaran idiom sesaat: $factText")
                memoryManager.deleteMemory(factText)
            }
        }
    }

    /**
     * Sintesis deterministik murni di perangkat (0 token, offline) berbasis aturan semantik terarah.
     */
    internal fun synthesizeDeterministicFromTurns(
        userTurns: List<CallTranscriptTurn>
    ): List<SynthesizedFactItem> {
        val results = mutableListOf<SynthesizedFactItem>()
        for (turn in userTurns) {
            val text = turn.text.trim()
            if (SemanticMemoryFilter.isTransientIdiomOrNoise(text)) continue

            // Ekstraksi eksplisit fakta direktif yang benar-benar bermakna
            val lower = text.lowercase()
            when {
                lower.startsWith("ingat ya") || lower.startsWith("catat bahwa") || lower.startsWith("tolong ingat") -> {
                    val content = text.replace(Regex("""^(?:tolong\s+)?(?:ingat\s+ya|catat\s+bahwa|simpan)(?:\s+bahwa)?\s*[:,]?\s*""", RegexOption.IGNORE_CASE), "").trim()
                    if (SemanticMemoryFilter.isMeaningfulPersonalFact("general", content)) {
                        results.add(SynthesizedFactItem(category = "profile", fact = content))
                    }
                }
                (lower.contains("kopi favorit") || lower.contains("makanan kesukaan") || lower.contains("hobi saya")) &&
                    !SemanticMemoryFilter.isTransientIdiomOrNoise(text) -> {
                    results.add(SynthesizedFactItem(category = "preference", fact = text))
                }
                (lower.contains("adikku namanya") || lower.contains("istri saya") || lower.contains("anak saya")) -> {
                    results.add(SynthesizedFactItem(category = "family", fact = text))
                }
                (lower.contains("saya bekerja di") || lower.contains("kantor saya di")) &&
                    !lower.contains("mati lampu") && !lower.contains("kantor pos") -> {
                    results.add(SynthesizedFactItem(category = "work", fact = text))
                }
                (lower.contains("minat risetku") || lower.contains("fokus koding")) &&
                    !lower.contains("ke layar") && !lower.contains("ke tombol") -> {
                    results.add(SynthesizedFactItem(category = "tech_profile", fact = text))
                }
                (lower.contains("kucing") || lower.contains("anjing") || lower.contains("peliharaan")) &&
                    (lower.contains("namanya") || lower.contains("bernama") || lower.contains("punya")) -> {
                    results.add(SynthesizedFactItem(category = "pet", fact = text))
                }
                (lower.contains("laptop saya") || lower.contains("laptopku") ||
                    lower.contains("macbook saya") || lower.contains("macbookku") ||
                    lower.contains("punya laptop") || lower.contains("pakai laptop") ||
                    lower.contains("menggunakan laptop") || lower.contains("hp saya") ||
                    lower.contains("hpku")) -> {
                    results.add(SynthesizedFactItem(category = "device", fact = text))
                }
                (lower.contains("proyekku") || lower.contains("proyek saya") ||
                    lower.contains("project saya") || lower.contains("projectku") ||
                    lower.contains("startup saya") || lower.contains("startupku") ||
                    lower.contains("lagi bikin") || lower.contains("lagi mengembangkan") ||
                    lower.contains("sedang mengerjakan")) -> {
                    results.add(SynthesizedFactItem(category = "project", fact = text))
                }
                (lower.contains("cita-citaku") || lower.contains("cita-cita saya") ||
                    lower.contains("impianku") || lower.contains("impian saya")) -> {
                    results.add(SynthesizedFactItem(category = "goal", fact = text))
                }
                (lower.contains("vegetarian") || lower.contains("pantangan makan")) -> {
                    results.add(SynthesizedFactItem(category = "dietary", fact = text))
                }
            }
        }
        return results.distinctBy { it.fact }
    }

    /**
     * Memanggil Gemini 2.5 Flash dengan mikro-payload untuk menyarikan fakta penting
     * dan relasi ontologis secara akurat dari percakapan yang baru selesai.
     */
    private fun requestCloudPostCallSynthesis(
        transcript: String,
        orderedKeys: List<String>
    ): List<SynthesizedFactItem>? {
        val prompt = """
            Anda adalah Cognitive Semantic Memory Analyzer untuk asisten pribadi Gepy.
            Berikut adalah transkrip lengkap percakapan suara yang baru saja selesai:
            ${transcript.take(2000)}

            Tugas Anda:
            1. Analisis apakah ada FAKTA PERSONAL BARU yang PENTING & ABADI tentang pengguna (keluarga, pekerjaan, domisili, alergi/kesehatan, preferensi kuliner/hobi nyata, tech stack/riset koding, atau jadwal masa depan).
            2. DILARANG KERAS mencatat:
               - Topik diskusi umum (judul film, sinopsis bioskop, genre, lagu, video, media, buku, game) sebagai identitas pengguna ('user_name') atau relasi keluarga/teman.
               - Idiom emosional atau keluhan kasual ("suka bingung", "suka pusing", "suka heran", "suka capek").
               - Komentar layar ponsel ("fokus ke layar", "klik tombol", "lihat dulu").
               - Perintah aksi asisten atau pertanyaan ("cek kondisi laptop", "kunci laptop", "buka youtube", "cari berita", "putar lagu").
               - Halusinasi pengalaman ngoding bersama di masa lalu.
               - Percakapan basa-basi atau sapaan.
            3. Jika TIDAK ada fakta personal baru yang substansial, kembalikan array JSON kosong: [].
            4. Jika ada fakta penting, kembalikan JSON array berisi objek dengan skema:
               [
                 {
                   "category": "family|work|location|health|preference|tech_profile|education|schedule",
                   "fact": "Kalimat fakta lengkap dalam Bahasa Indonesia (contoh: 'Adik pengguna bernama Farhan', 'Pengguna bekerja di Bank Mandiri')",
                   "subject": "user_work|family_siblings|tech_stack|location_home|dll",
                   "predicate": "works_at|has_family_member|allergic_to|specializes_in|dll",
                   "object_value": "Nilai target fakta",
                   "valid_hours": null
                 }
               ]
            Hanya kembalikan JSON array valid.
        """.trimIndent()

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("maxOutputTokens", 400)
                    .put("responseMimeType", "application/json")
            )
        }

        val url = "${EnvConfig.geminiBaseUrl}/models/${EnvConfig.geminiModel}:generateContent"
        for (activeKey in orderedKeys) {
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", activeKey)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (GeminiKeyPool.isQuotaOrRateLimitResponse(response.code, body)) {
                        GepyLog.w(TAG, "Sintesis cloud 429/kuota penuh pada key aktif, failover ke key cadangan...")
                        GeminiKeyPool.markQuotaExhausted(activeKey)
                        return@use
                    }
                    if (GeminiKeyPool.isKeyRejectedResponse(response.code)) {
                        GeminiKeyPool.markQuotaExhausted(activeKey, GeminiKeyPool.INVALID_KEY_COOLDOWN_MS)
                        return@use
                    }
                    if (!response.isSuccessful) return null

                    val json = JSONObject(body)
                    val text = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "[]")
                        ?.trim()
                        .orEmpty()

                    val items = mutableListOf<SynthesizedFactItem>()
                    val arr = if (text.startsWith("[")) JSONArray(text) else JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val fact = obj.optString("fact", "").trim()
                        val cat = obj.optString("category", "profile").trim()
                        val sub = obj.optString("subject", "").trim()
                        val pred = obj.optString("predicate", "").trim()
                        val objVal = obj.optString("object_value", "").trim()
                        val validHours = if (obj.has("valid_hours") && !obj.isNull("valid_hours")) {
                            obj.optInt("valid_hours").takeIf { it > 0 }
                        } else null

                        if (fact.isNotBlank()) {
                            items.add(
                                SynthesizedFactItem(
                                    category = cat,
                                    fact = fact,
                                    subject = sub,
                                    predicate = pred,
                                    objectValue = objVal,
                                    validHours = validHours
                                )
                            )
                        }
                    }
                    return items
                }
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Sintesis cloud dilewati: ${t.message}")
            }
        }
        return null
    }
}

/**
 * Item fakta hasil sintesis pasca-panggilan.
 */
data class SynthesizedFactItem(
    val category: String,
    val fact: String,
    val subject: String = "",
    val predicate: String = "",
    val objectValue: String = "",
    val validHours: Int? = null
)
