package com.teknologigawai.gepy.core.memory

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostCallMemorySynthesizerTest {

    private lateinit var memoryManager: UserMemoryManager
    private lateinit var synthesizer: PostCallMemorySynthesizer

    @Before
    fun setUp() {
        memoryManager = UserMemoryManager(context = null, gepyDao = null)
        synthesizer = PostCallMemorySynthesizer()
    }

    @Test
    fun `purgeTransientSessionMemories membersihkan serpihan derau dan mempertahankan fakta sejati`() {
        // Simpan 1 fakta valid dan 1 fakta derau idiom
        memoryManager.recordMemory(
            category = "preference",
            fact = "Minuman favorit pengguna: Americano dingin tanpa gula",
            source = "voice"
        )
        // Paksa simpan fakta derau langsung (source bypass) untuk menguji purge
        memoryManager.recordMemory(
            category = "preference",
            fact = "Pengguna suka bingung deh kalau kamu ngomong gitu",
            source = "internal_test"
        )

        assertEquals(2, memoryManager.getLocalEntries().size)

        val turns = listOf(
            CallTranscriptTurn("user", "halo gep"),
            CallTranscriptTurn("assistant", "Halo! Mau dibantu apa?"),
            CallTranscriptTurn("user", "aku suka bingung deh kalau kamu ngomong gitu"),
            CallTranscriptTurn("assistant", "Oke, aku bicaranya lebih santai ya!")
        )

        synthesizer.purgeTransientSessionMemories(memoryManager, turns)

        val remaining = memoryManager.getLocalEntries()
        assertEquals(1, remaining.size)
        assertEquals("Minuman favorit pengguna: Americano dingin tanpa gula", remaining.first().fact)
        assertFalse(remaining.any { it.fact.contains("bingung") })
    }

    @Test
    fun `synthesizeDeterministicFromTurns mengekstrak fakta personal penting dari ujaran pengguna`() {
        val turns = listOf(
            CallTranscriptTurn("user", "halo gep"),
            CallTranscriptTurn("user", "aku suka bingung deh"), // harus diabaikan (idiom)
            CallTranscriptTurn("user", "fokus ke layar dulu"), // harus diabaikan (navigasi)
            CallTranscriptTurn("user", "ingat ya adikku namanya Farhan"),
            CallTranscriptTurn("user", "kantor saya di Bank Mandiri")
        )

        val distilled = synthesizer.synthesizeDeterministicFromTurns(turns)
        assertEquals(2, distilled.size)
        assertTrue(distilled.any { it.fact.contains("Farhan") })
        assertTrue(distilled.any { it.fact.contains("Bank Mandiri") })
    }

    @Test
    fun `synthesizeSession mengintegrasikan evaluasi penuh pasca-panggilan`() = runTest {
        val turns = listOf(
            CallTranscriptTurn("user", "Gep, ingat ya adikku namanya Farhan"),
            CallTranscriptTurn("assistant", "Siap, aku ingat adikmu bernama Farhan!"),
            CallTranscriptTurn("user", "kantor saya di Bank Mandiri"),
            CallTranscriptTurn("assistant", "Beres, kamu bekerja di Bank Mandiri.")
        )

        val recorded = synthesizer.synthesizeSession(
            turns = turns,
            memoryManager = memoryManager,
            apiKeyProvider = { "test_key" } // memicu fallback deterministik teruji
        )

        assertTrue(recorded.isNotEmpty())
        val entries = memoryManager.getLocalEntries()
        assertTrue(entries.any { it.fact.contains("Farhan") })
    }

    @Test
    fun `synthesizeSession melakukan failover Key 1 ke Key 2 saat Gemini mengembalikan HTTP 429`() = runTest {
        com.teknologigawai.gepy.core.config.GeminiKeyPool.resetCooldownsForTest()
        val seenKeys = mutableListOf<String>()

        val interceptorClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val key = req.header("x-goog-api-key").orEmpty()
                seenKeys.add(key)
                val (code, jsonBody) = if (key == "synth_key_1") {
                    429 to """{"error":{"code":429,"message":"RESOURCE_EXHAUSTED"}}"""
                } else {
                    val cloudArray = org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("category", "work")
                            .put("fact", "Pengguna bertugas sebagai arsitek sistem di Jakarta")
                            .put("subject", "user_work")
                            .put("predicate", "works_as")
                            .put("object_value", "arsitek sistem")
                    )
                    val envelope = org.json.JSONObject().put(
                        "candidates",
                        org.json.JSONArray().put(
                            org.json.JSONObject().put(
                                "content",
                                org.json.JSONObject().put(
                                    "parts",
                                    org.json.JSONArray().put(
                                        org.json.JSONObject().put("text", cloudArray.toString())
                                    )
                                )
                            )
                        )
                    )
                    200 to envelope.toString()
                }
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Too Many Requests")
                    .body(
                        jsonBody.toResponseBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()
            }
            .build()

        val mockSynthesizer = PostCallMemorySynthesizer(httpClient = interceptorClient)
        val turns = listOf(
            CallTranscriptTurn("user", "Aku sekarang bertugas sebagai arsitek sistem di Jakarta lho"),
            CallTranscriptTurn("assistant", "Keren banget, aku catat ya!")
        )

        val recorded = mockSynthesizer.synthesizeSession(
            turns = turns,
            memoryManager = memoryManager,
            apiKeyProvider = { "synth_key_1" },
            secondaryApiKeyProvider = { "synth_key_2" }
        )

        assertEquals(listOf("synth_key_1", "synth_key_2"), seenKeys)
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].contains("arsitek sistem"))
        assertTrue(memoryManager.getLocalFacts().any { it.contains("arsitek sistem") })
        assertTrue(com.teknologigawai.gepy.core.config.GeminiKeyPool.isInCooldown("synth_key_1"))
        com.teknologigawai.gepy.core.config.GeminiKeyPool.resetCooldownsForTest()
    }

    @Test
    fun `synthesizeSession dan synthesizeDeterministicFromTurns menolak perintah aksi seperti cek kondisi laptop`() = runTest {
        val turns = listOf(
            CallTranscriptTurn("user", "cek kondisi laptop"),
            CallTranscriptTurn("user", "Gep tolong kunci laptop saya"),
            CallTranscriptTurn("user", "cek status git di laptop"),
            CallTranscriptTurn("user", "buka project android studio"),
            CallTranscriptTurn("user", "apa laptop saya?"),
            CallTranscriptTurn("user", "Laptop saya MacBook Pro M3")
        )

        val distilled = synthesizer.synthesizeDeterministicFromTurns(turns)
        assertEquals(1, distilled.size)
        assertEquals("Laptop saya MacBook Pro M3", distilled.first().fact)

        val recorded = synthesizer.synthesizeSession(
            turns = turns,
            memoryManager = memoryManager,
            apiKeyProvider = { "test_key" }
        )
        assertEquals(1, recorded.size)
        assertTrue(recorded.first().contains("MacBook Pro M3"))
        assertFalse(memoryManager.getLocalFacts().any { it.contains("cek kondisi laptop", ignoreCase = true) })
    }

    @Test
    fun `purgeTransientSessionMemories membersihkan fakta perintah usang seperti cek kondisi laptop`() {
        memoryManager.recordMemory(
            category = "device",
            fact = "cek kondisi laptop",
            subject = "device_laptop",
            predicate = "laptop_model",
            objectValue = "cek kondisi laptop",
            source = "legacy_bypass"
        )
        memoryManager.recordMemory(
            category = "device",
            fact = "Laptop pengguna: ASUS ROG Zephyrus",
            subject = "device_laptop",
            predicate = "laptop_model",
            objectValue = "ASUS ROG Zephyrus",
            source = "voice"
        )

        assertEquals(1, memoryManager.getLocalEntries().size)
        val remaining = memoryManager.getLocalEntries()
        assertEquals("Laptop pengguna: ASUS ROG Zephyrus", remaining.first().fact)
    }
}
