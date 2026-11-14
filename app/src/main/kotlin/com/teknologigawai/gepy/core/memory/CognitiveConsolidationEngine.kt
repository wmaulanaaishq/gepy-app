package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.GeminiKeyPool
import com.teknologigawai.gepy.core.config.GeminiKeyPurpose
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.wellness.WellnessGuardianSnapshot
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
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
 * Laporan hasil eksekusi **Konsolidasi Memori Tidur & Refleksi Generatif (v5.3)**.
 */
data class ConsolidationReport(
    val prunedCount: Int,
    val activeFactCount: Int,
    val insightsGenerated: List<String>,
    val executedAtMs: Long,
    val summaryMessage: String
)

/**
 * Mesin **Konsolidasi Memori Kognitif & Refleksi Generatif Tingkat Tinggi (Gepy v5.3)**.
 *
 * Mengimplementasikan dua mekanisme neurosains & AI mutakhir:
 * 1. **Complementary Learning Systems (CLS Theory — McClelland & Hassabis)**:
 *    Memutar ulang (*replay*) rekaman kejadian harian (Hipokampus) menjadi wawasan
 *    jangka panjang terstruktur (Neokorteks) sambil memangkas sinapsis catatan sepele
 *    yang telah meluruh menurut kurva Ebbinghaus.
 * 2. **Stanford Generative Agents Reflection (*Park et al., 2023 — arXiv:2304.03442*)**:
 *    Menyintesis observasi mentah harian menjadi kesimpulan tingkat tinggi (`category = "insight"`,
 *    `subject = "user_insight"`, `importance = 9`).
 */
class CognitiveConsolidationEngine(
    private val userMemoryManager: UserMemoryManager,
    private val apiKeyProvider: () -> String = { EnvConfig.defaultGeminiApiKey },
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val secondaryApiKeyProvider: () -> String = { "" }
) {
    companion object {
        private const val TAG = "CognitiveConsolidation"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Menjalankan satu siklus penuh konsolidasi memori:
     * 1. Pemangkasan sinapsis Ebbinghaus & masa berlaku (`Synaptic Pruning`).
     * 2. Sintesis wawasan tingkat tinggi (`Generative Reflection`) baik melalui Gemini 2.5 Flash
     *    (bila diizinkan & kunci API tersedia) maupun sintesis deterministik lokal 0-token.
     */
    suspend fun runConsolidationPass(
        wellnessSnapshot: WellnessGuardianSnapshot? = null,
        nowMs: Long = System.currentTimeMillis(),
        allowCloudLlm: Boolean = true
    ): ConsolidationReport = withContext(Dispatchers.IO) {
        GepyLog.i(TAG, "Memulai siklus Konsolidasi Memori Kognitif (CLS + Stanford Reflection)...")

        // 1. Pemangkasan Sinapsis (Ebbinghaus Decay + Time Expiry)
        val prunedCount = userMemoryManager.pruneDecayedAndExpiredFacts(nowMs)

        // 2. Kumpulkan fakta aktif terbaru (48 jam terakhir) untuk bahan refleksi
        val activeFacts = userMemoryManager.getActiveTemporalFactsSync(nowMs)
        val recentWindowMs = 48 * 3_600_000L
        val recentFacts = activeFacts.filter { (nowMs - it.updatedAtMs) <= recentWindowMs }

        // 3. Sintesis Wawasan Refleksi Tingkat Tinggi (Generative Insights)
        val generatedInsights = mutableListOf<String>()

        val cloudInsight = if (allowCloudLlm && recentFacts.isNotEmpty()) {
            requestCloudGenerativeInsight(recentFacts, wellnessSnapshot)
        } else {
            null
        }

        if (!cloudInsight.isNullOrBlank()) {
            generatedInsights.add(cloudInsight)
        } else {
            // Sintesis Kognitif Deterministik Lokal (0 ms, 0 token, 100% jujur & terukur)
            val localInsights = synthesizeDeterministicInsights(activeFacts, recentFacts, wellnessSnapshot)
            generatedInsights.addAll(localInsights)
        }

        // 4. Simpan wawasan baru ke dalam Graf Memori Temporal sebagai kategori 'insight'
        for (insight in generatedInsights) {
            userMemoryManager.recordMemory(
                category = "insight",
                fact = insight,
                subject = "user_insight",
                predicate = "cognitive_reflection",
                objectValue = insight,
                source = "nightly_reflection"
            )
        }

        val finalActiveCount = userMemoryManager.getActiveTemporalFactsSync(nowMs).size
        val summary = buildString {
            append("Konsolidasi selesai: ")
            append("$prunedCount catatan usang dipangkas, ")
            append("${generatedInsights.size} wawasan mendalam disintesis, ")
            append("$finalActiveCount memori aktif terjaga.")
        }

        GepyLog.i(TAG, summary)
        ConsolidationReport(
            prunedCount = prunedCount,
            activeFactCount = finalActiveCount,
            insightsGenerated = generatedInsights,
            executedAtMs = nowMs,
            summaryMessage = summary
        )
    }

    /**
     * Menyintesis wawasan psikologis/kebiasaan tingkat tinggi secara deterministik di perangkat (`0 token`)
     * hanya berdasarkan fakta nyata dan metrik sensor yang terverifikasi (tanpa halusinasi postur).
     */
    internal fun synthesizeDeterministicInsights(
        allActiveFacts: List<TemporalMemoryFactEntity>,
        recentFacts: List<TemporalMemoryFactEntity>,
        wellness: WellnessGuardianSnapshot?
    ): List<String> {
        val insights = mutableListOf<String>()
        val existingInsightTexts = allActiveFacts
            .filter { it.category.equals("insight", ignoreCase = true) }
            .map { it.factText.lowercase() }

        // Wawasan 1: Pola keseimbangan kerja/agenda dan keluarga
        val hasWorkOrSchedule = allActiveFacts.any {
            it.category in setOf("work", "schedule")
        }
        val hasFamily = allActiveFacts.any {
            it.category == "family"
        }
        if (hasWorkOrSchedule && hasFamily) {
            val candidate = "Wawasan: Pengguna aktif menjaga keseimbangan antara tanggung jawab pekerjaan/agenda dan perhatian pada keluarga."
            if (existingInsightTexts.none { it.contains("keseimbangan antara tanggung jawab") }) {
                insights.add(candidate)
            }
        }

        // Wawasan 2: Pola aktivitas fisik & layar terukur dari sensor nyata
        if (wellness != null) {
            if (wellness.stepSensorActive && wellness.todaySteps >= 5000) {
                val candidate = "Wawasan: Pengguna memiliki disiplin gerak fisik harian yang baik (${wellness.todaySteps} langkah terukur)."
                if (existingInsightTexts.none { it.contains("disiplin gerak fisik") }) {
                    insights.add(candidate)
                }
            } else if (wellness.totalSocialScrollMinutes >= 45) {
                val candidate = "Wawasan: Pengguna cenderung membuka media sosial cukup lama (${wellness.totalSocialScrollMinutes} menit) sehingga terbantu oleh pengingat fokus."
                if (existingInsightTexts.none { it.contains("membuka media sosial cukup lama") }) {
                    insights.add(candidate)
                }
            }
        }

        // Wawasan 3: Konsistensi preferensi & topik yang paling sering diulang (Spaced Repetition)
        val mostReinforced = allActiveFacts
            .filter { it.category != "insight" && it.hitCount >= 1 }
            .maxByOrNull { it.hitCount }

        if (mostReinforced != null && insights.isEmpty()) {
            val candidate = "Wawasan: Topik '${mostReinforced.subject}' (${mostReinforced.objectValue}) merupakan hal yang sering ditekankan oleh pengguna."
            if (existingInsightTexts.none { it.contains(mostReinforced.objectValue.lowercase()) }) {
                insights.add(candidate)
            }
        }

        // Wawasan 4: Jika ada fakta baru hari ini namun belum ada insight spesifik
        if (insights.isEmpty() && recentFacts.isNotEmpty()) {
            val nonInsightRecent = recentFacts.firstOrNull { !it.category.equals("insight", ignoreCase = true) }
            if (nonInsightRecent != null) {
                val candidate = "Wawasan: Fokus utama pengguna saat ini mencakup ${nonInsightRecent.factText.trimEnd('.')}."
                if (existingInsightTexts.none { it == candidate.lowercase() }) {
                    insights.add(candidate)
                }
            }
        }

        return insights.take(2)
    }

    /**
     * Memanggil Gemini 2.5 Flash dengan prompt mikro (`<= 350` token) untuk menyarikan 1 kalimat
     * refleksi tingkat tinggi ala *Stanford Generative Agents*.
     */
    private fun requestCloudGenerativeInsight(
        recentFacts: List<TemporalMemoryFactEntity>,
        wellness: WellnessGuardianSnapshot?
    ): String? {
        val rawPrimary = GeminiKeyPool.sanitizeKey(apiKeyProvider())
        val rawSecondary = GeminiKeyPool.sanitizeKey(secondaryApiKeyProvider())
        if (rawPrimary.equals("test_key", ignoreCase = true) ||
            rawPrimary.equals("dummy_key", ignoreCase = true)
        ) {
            return null
        }
        val orderedKeys = GeminiKeyPool.orderedKeys(
            purpose = GeminiKeyPurpose.MEMORY_SYNTHESIS,
            primaryKey = rawPrimary,
            secondaryKey = rawSecondary
        )
        if (orderedKeys.isEmpty()) return null

        val factsBullet = recentFacts.take(8).joinToString("\n") { "- [${it.category}] ${it.factText}" }
        val wellnessLine = if (wellness != null) {
            "Langkah pedometer hari ini: ${wellness.todaySteps}, durasi layar medsos: ${wellness.totalSocialScrollMinutes} menit."
        } else {
            ""
        }

        val prompt = """
            Anda adalah mesin refleksi kognitif (Stanford Generative Agents) untuk asisten pribadi Gepy.
            Berdasarkan fakta terbaru pengguna berikut:
            $factsBullet
            $wellnessLine

            Tarik tepat SATU kalimat kesimpulan tingkat tinggi (maksimal 22 kata) dalam Bahasa Indonesia yang merangkum prioritas, kebiasaan, atau kondisi pengguna secara jujur tanpa mengarang fakta di luar data di atas. Awali dengan kata "Wawasan: ".
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
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 90)
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
                        ?.optString("text", "")
                        ?.trim()
                        .orEmpty()
                    if (text.length in 12..220) {
                        return if (text.startsWith("Wawasan:", ignoreCase = true)) text else "Wawasan: $text"
                    }
                    return null
                }
            } catch (t: Throwable) {
                GepyLog.w(TAG, "Refleksi cloud dilewati (menggunakan sintesis lokal): ${t.message}")
            }
        }
        return null
    }
}
