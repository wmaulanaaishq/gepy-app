package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubWordBM25RetrievalEngineTest {

    private val engine = SubWordBM25RetrievalEngine()

    private fun buildFact(
        id: Long = 1L,
        category: String = "preference",
        subject: String = "minuman",
        predicate: String = "favorit",
        objectValue: String = "kopi americano dingin",
        factText: String = "Minuman favorit saya adalah kopi americano dingin"
    ): TemporalMemoryFactEntity {
        return TemporalMemoryFactEntity(
            id = id,
            category = category,
            subject = subject,
            predicate = predicate,
            objectValue = objectValue,
            factText = factText,
            confidence = 1.0f,
            validFromMs = 1_000_000L,
            validUntilMs = null,
            supersededById = null,
            hitCount = 1,
            source = "voice",
            createdAtMs = 1_000_000L,
            updatedAtMs = 1_000_000L
        )
    }

    @Test
    fun `SubWordBM25 mengenali variasi imbuhan bahasa Indonesia tanpa stemmer berat`() {
        val coffeeFact = buildFact(
            id = 1L,
            category = "preference",
            subject = "kopi",
            objectValue = "americano",
            factText = "Suka minum kopi americano"
        )
        val runFact = buildFact(
            id = 2L,
            category = "habit",
            subject = "olahraga",
            objectValue = "lari pagi",
            factText = "Rutin lari pagi setiap akhir pekan"
        )

        // Variasi lisan dengan imbuhan: "ngopi" (akar kata kopi), "berlari" (akar kata lari)
        val scoreNgopi = engine.computeSimilarity("aku mau ngopi santai hari ini", coffeeFact)
        val scoreUnrelated = engine.computeSimilarity("aku mau ngopi santai hari ini", runFact)
        assertTrue("Sub-word trigram harus menangkap hubungan ngopi dan kopi", scoreNgopi > 1.0)
        assertTrue("Skor fakta kopi harus jauh lebih tinggi dibanding lari", scoreNgopi > scoreUnrelated)

        val scoreBerlari = engine.computeSimilarity("saya sedang gemar berlari di taman", runFact)
        val scoreBerlariUnrelated = engine.computeSimilarity("saya sedang gemar berlari di taman", coffeeFact)
        assertTrue("Sub-word trigram harus menangkap hubungan berlari dan lari", scoreBerlari > 1.0)
        assertTrue("Skor fakta lari harus lebih tinggi dibanding kopi saat membahas berlari", scoreBerlari > scoreBerlariUnrelated)
    }

    @Test
    fun `SubWordBM25 toleran terhadap typo dan ejaan fonetik lisan`() {
        val coffeeFact = buildFact(
            id = 1L,
            objectValue = "americano",
            factText = "Saya suka kopi americano"
        )

        // Typo: "amerikano" (k alih-alih c)
        val scoreTypo = engine.computeSimilarity("pesankan kopi amerikano", coffeeFact)
        assertTrue("Typo huruf k/c harus tetap menghasilkan skor relevan yang tinggi", scoreTypo > 2.0)
    }

    @Test
    fun `SubWordBM25 memeringkat fakta paling relevan di posisi puncak`() {
        val facts = listOf(
            buildFact(id = 1L, category = "family", subject = "istri", objectValue = "Nadia", factText = "Istri saya bernama Nadia"),
            buildFact(id = 2L, category = "preference", subject = "kopi", objectValue = "americano", factText = "Suka minum kopi americano"),
            buildFact(id = 3L, category = "habit", subject = "catur", objectValue = "main catur", factText = "Hobi bermain catur"),
            buildFact(id = 4L, category = "location", subject = "kantor", objectValue = "Sudirman", factText = "Bekerja di kantor Sudirman")
        )

        val results = engine.rankFacts("ulang tahun istriku Nadia", facts, topK = 2)
        assertTrue("Hasil pencarian tidak boleh kosong", results.isNotEmpty())
        assertEquals("Fakta istri Nadia harus berada di peringkat 1", 1L, results[0].first.id)
    }
}
