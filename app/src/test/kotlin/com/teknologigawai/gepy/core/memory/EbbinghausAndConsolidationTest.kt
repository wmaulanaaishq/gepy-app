package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian unit untuk arsitektur **Gepy v5.3 — Cognitive Human-Like Memory**:
 * - Kurva Peluruhan Ebbinghaus ($R = e^{-\Delta t / S}$)
 * - Skor Kepentingan Amigdala ($I \in [1, 10]$)
 * - Penguatan Spaced Repetition (`hitCount`)
 * - Rumus Retrieval Stanford Generative Agents (Recency + Importance + Relevance)
 * - Konsolidasi Tidur Malam & Pemangkasan Sinaptik (`CognitiveConsolidationEngine`)
 */
class EbbinghausAndConsolidationTest {

    private fun buildFact(
        id: Long = 1L,
        category: String = "fact",
        subject: String = "user",
        predicate: String = "catatan",
        objectValue: String = "Cek cuaca sore",
        factText: String = "Cek cuaca sore",
        confidence: Float = 0.90f,
        hitCount: Int = 1,
        source: String = "voice",
        validFromMs: Long = 1_000_000L,
        validUntilMs: Long? = null,
        updatedAtMs: Long = 1_000_000L
    ): TemporalMemoryFactEntity {
        return TemporalMemoryFactEntity(
            id = id,
            category = category,
            subject = subject,
            predicate = predicate,
            objectValue = objectValue,
            factText = factText,
            confidence = confidence,
            hitCount = hitCount,
            source = source,
            validFromMs = validFromMs,
            validUntilMs = validUntilMs,
            supersededById = null,
            createdAtMs = validFromMs,
            updatedAtMs = updatedAtMs
        )
    }

    @Test
    fun `Amigdala Importance Score memberikan bobot tertinggi pada keluarga, profil, alergi, dan insight`() {
        val familyFact = buildFact(
            category = "family",
            subject = "contact:ibu",
            predicate = "phone_hint",
            objectValue = "Ibu Kandung",
            factText = "Ibu Kandung bernama Siti"
        )
        val allergyFact = buildFact(
            category = "fact",
            subject = "user",
            predicate = "kesehatan",
            objectValue = "Alergi kacang tanah parah",
            factText = "Saya alergi kacang tanah parah"
        )
        val insightFact = buildFact(
            category = "insight",
            subject = "user_insight",
            predicate = "kebiasaan",
            objectValue = "Sering olahraga pagi",
            factText = "Pengguna disiplin berolahraga setiap pagi",
            source = "nightly_reflection"
        )
        val friendFact = buildFact(
            category = "friends",
            subject = "social_friends",
            predicate = "has_friend",
            objectValue = "Budi",
            factText = "Teman dekat saya bernama Budi"
        )
        val trivialNote = buildFact(
            category = "fact",
            subject = "user",
            predicate = "trivia",
            objectValue = "Tadi lihat kucing lewat",
            factText = "Tadi lihat kucing lewat",
            confidence = 0.55f,
            hitCount = 0
        )

        assertTrue("Fakta keluarga harus memiliki skor >= 9", EbbinghausDecayEngine.computeImportanceScore(familyFact) >= 9)
        assertTrue("Fakta teman harus memiliki skor >= 9", EbbinghausDecayEngine.computeImportanceScore(friendFact) >= 9)
        assertTrue("Fakta alergi/kesehatan harus memiliki skor >= 9", EbbinghausDecayEngine.computeImportanceScore(allergyFact) >= 9)
        assertTrue("Insight konsolidasi malam harus memiliki skor >= 9", EbbinghausDecayEngine.computeImportanceScore(insightFact) >= 9)
        assertEquals("Trivia berkeyakinan rendah tanpa pengulangan harus memiliki skor 3", 3, EbbinghausDecayEngine.computeImportanceScore(trivialNote))
    }

    @Test
    fun `Kurva Lupa Ebbinghaus menjaga memori inti di atas 85 persen walau 1 tahun berlalu`() {
        val baseTimeMs = 1_700_000_000_000L
        val oneYearLaterMs = baseTimeMs + 365L * 24L * 3_600_000L

        val coreFamilyFact = buildFact(
            category = "family",
            subject = "contact:istri",
            predicate = "nama",
            objectValue = "Nadia",
            factText = "Nama istri saya adalah Nadia",
            updatedAtMs = baseTimeMs
        )

        val retentionAfterYear = EbbinghausDecayEngine.computeRetention(coreFamilyFact, oneYearLaterMs)
        assertTrue(
            "Memori inti (I >= 8) tidak boleh luntur di bawah 85% (aktual: $retentionAfterYear)",
            retentionAfterYear >= EbbinghausDecayEngine.CORE_MEMORY_RETENTION_FLOOR
        )
    }

    @Test
    fun `Spaced Repetition memperlambat peluruhan memori secara eksponensial`() {
        val baseTimeMs = 1_700_000_000_000L
        val fiveDaysLaterMs = baseTimeMs + 5L * 24L * 3_600_000L

        val unreinforcedTrivia = buildFact(
            id = 10L,
            category = "fact",
            factText = "Parkir motor di blok C2",
            confidence = 0.55f,
            hitCount = 0,
            updatedAtMs = baseTimeMs
        )

        val reinforcedFact = unreinforcedTrivia.copy(
            id = 11L,
            hitCount = 4 // Disebut ulang 4 kali (Spaced Repetition)
        )

        val retentionUnreinforced = EbbinghausDecayEngine.computeRetention(unreinforcedTrivia, fiveDaysLaterMs)
        val retentionReinforced = EbbinghausDecayEngine.computeRetention(reinforcedFact, fiveDaysLaterMs)

        assertTrue(
            "Trivia tanpa pengulangan harus meluruh di bawah ambang 20% setelah 5 hari (aktual: $retentionUnreinforced)",
            retentionUnreinforced < EbbinghausDecayEngine.PRUNING_RETENTION_THRESHOLD
        )
        assertTrue(
            "Fakta yang diperkuat Spaced Repetition (hitCount=4) harus bertahan jauh lebih kuat (aktual: $retentionReinforced)",
            retentionReinforced > 0.45
        )
    }

    @Test
    fun `Stanford Generative Agents Retrieval mengutamakan relevansi, kepentingan, dan kesegaran`() {
        val nowMs = 1_700_000_000_000L
        val coffeePreference = buildFact(
            id = 1L,
            category = "preference",
            subject = "user_preference",
            predicate = "minuman_favorit",
            objectValue = "kopi hitam tanpa gula",
            factText = "Minuman favorit pengguna adalah kopi hitam tanpa gula",
            hitCount = 3,
            updatedAtMs = nowMs - 2 * 3_600_000L
        )
        val unrelatedFact = buildFact(
            id = 2L,
            category = "fact",
            subject = "user",
            predicate = "hobi",
            objectValue = "bermain catur",
            factText = "Hobi bermain catur saat akhir pekan",
            hitCount = 1,
            updatedAtMs = nowMs - 48 * 3_600_000L
        )

        val queryTokens = setOf("pesan", "kopi", "hitam")
        val scoreCoffee = EbbinghausDecayEngine.computeStanfordRetrievalScore(coffeePreference, queryTokens, nowMs)
        val scoreUnrelated = EbbinghausDecayEngine.computeStanfordRetrievalScore(unrelatedFact, queryTokens, nowMs)

        assertTrue(
            "Skor Stanford untuk fakta relevan harus lebih tinggi ($scoreCoffee vs $scoreUnrelated)",
            scoreCoffee > scoreUnrelated
        )
    }

    @Test
    fun `UserMemoryManager melakukan Spaced Repetition dan Konsolidasi Malam tanpa menghapus memori inti`() = runBlocking {
        var currentClock = 1_700_000_000_000L
        val manager = UserMemoryManager(clockMsProvider = { currentClock })
        manager.clearLocalMemory()

        // 1. Simpan memori inti & preferensi
        manager.recordMemory(category = "family", fact = "Nama istri saya adalah Nadia")
        manager.recordMemory(category = "preference", fact = "Saya suka minum kopi americano dingin")
        manager.recordMemory(category = "habit", fact = "Saya rutin lari pagi setiap Sabtu")
        manager.recordMemory(category = "fact", fact = "Saya sedang alergi udang laut")

        // 2. Simpan catatan sementara yang akan kedaluwarsa besok
        manager.recordMemory(category = "temporary_note", fact = "Besok pagi rapat proyek jam 9")

        // 3. Uji Spaced Repetition: pengguna menyebut ulang kopi americano 6 jam & 12 jam kemudian
        currentClock += 6 * 3_600_000L
        val reinforcedCount = manager.reinforceMatchingFacts(
            utteranceOrQuery = "Tolong pesankan kopi americano dingin favoritku",
            nowMs = currentClock
        )
        assertTrue("Harus memperkuat minimal 1 fakta yang cocok", reinforcedCount >= 1)

        currentClock += 6 * 3_600_000L
        manager.reinforceMatchingFacts(
            utteranceOrQuery = "Hari ini beli kopi americano dingin lagi",
            nowMs = currentClock
        )

        val factsAfterReinforce = manager.getActiveTemporalFactsSync(nowMs = currentClock)
        val coffeeFact = factsAfterReinforce.first { it.factText.contains("americano", ignoreCase = true) }
        assertTrue("hitCount fakta kopi harus bertambah (>= 2)", coffeeFact.hitCount >= 2)

        // 4. Majukan waktu 4 hari ke depan (catatan sementara "besok" sudah kedaluwarsa)
        currentClock += 4L * 24L * 3_600_000L
        val report = manager.runNightlyCognitiveConsolidation(
            nowMs = currentClock,
            allowCloudLlm = false // gunakan sintesis deterministik lokal 0-token untuk pengujian deterministik
        )

        assertTrue("Catatan sementara kedaluwarsa harus dipangkas (>= 1)", report.prunedCount >= 1)
        assertTrue("Harus menghasilkan minimal 1 Wawasan/Insight baru", report.insightsGenerated.isNotEmpty())

        val activeAfterConsolidation = manager.getActiveTemporalFactsSync(nowMs = currentClock)
        // Memori keluarga & preferensi tetap utuh
        assertTrue(
            "Memori keluarga inti wajib tetap utuh",
            activeAfterConsolidation.any { it.factText.contains("Nadia", ignoreCase = true) }
        )
        assertTrue(
            "Memori kopi yang diperkuat wajib tetap utuh",
            activeAfterConsolidation.any { it.factText.contains("americano", ignoreCase = true) }
        )
        // Wawasan (insight) hasil refleksi malam tersimpan di kategori "insight"
        assertTrue(
            "Wawasan/insight baru wajib tersimpan sebagai kategori insight",
            activeAfterConsolidation.any { it.category == "insight" }
        )

        // Konteks instruksi sistem juga memuat bagian Wawasan & Refleksi Psikologis Pengguna
        val sysContext = manager.getSystemInstructionContext()
        assertTrue(
            "System instruction harus menyertakan bagian Wawasan & Refleksi Psikologis Pengguna",
            sysContext.contains("Wawasan & Refleksi Psikologis Pengguna")
        )
    }
}
