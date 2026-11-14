package com.teknologigawai.gepy.features.brain.models

import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ByokProviderPreset
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.LlmCredentialsConfig
import com.teknologigawai.gepy.features.brain.LlmHttpResponse
import com.teknologigawai.gepy.features.brain.MultiAgentMinitapAdapter
import com.teknologigawai.gepy.features.brain.SingleAgentReActAdapter
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReActActionModelTest {

    private class StubCortexBrain : BrainPort {
        var capturedKnowledge: String? = null

        override suspend fun decideNextAction(
            userGoal: String,
            currentPackage: String,
            screenElements: List<PrunedUiElement>,
            memory: ShortTermMemorySnapshot,
            injectedDomainKnowledge: String?
        ): ReActActionModel {
            capturedKnowledge = injectedDomainKnowledge
            return ReActActionModel(
                thought = "Sub-tujuan pertama adalah membuka kalender.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.LAUNCH_APP,
                appName = "Google Calendar"
            )
        }
    }

    @Test
    fun `mengurai JSON murni blok kode markdown maupun tag think DeepSeek dengan akurat`() {
        val fencedJson = """
            <think>
            Menganalisis daftar kontak yang muncul di layar WhatsApp...
            </think>
            ```json
            {
              "thought": "Ada dua kontak bernama Alif, wajib minta disambiguasi.",
              "reflection_on_last_step": "CORRECT",
              "save_to_scratchpad": "Rapat Skripsi pukul 13:00",
              "action": "ASK_USER_DISAMBIGUATION",
              "target_index": null,
              "question_tts": "Ada dua Alif di WhatsApp, pilih yang mana?",
              "options": ["Alif Praktikum", "Alif Ketua Kelas"]
            }
            ```
        """.trimIndent()

        val parsed = ReActActionModel.fromRawJsonString(fencedJson)
        assertEquals(AgentActionType.ASK_USER_DISAMBIGUATION, parsed.action)
        assertEquals(ReflectionStatus.CORRECT, parsed.reflectionOnLastStep)
        assertEquals("Rapat Skripsi pukul 13:00", parsed.saveToScratchpad)
        assertEquals(listOf("Alif Praktikum", "Alif Ketua Kelas"), parsed.options)
        assertEquals("Ada dua Alif di WhatsApp, pilih yang mana?", parsed.questionTts)
    }

    @Test
    fun `mendukung peningkatan CLICK menjadi CONFIRM_AND_CLICK via copy`() {
        val original = ReActActionModel(
            thought = "Menekan tombol kirim.",
            reflectionOnLastStep = ReflectionStatus.CORRECT,
            action = AgentActionType.CLICK,
            targetIndex = 4
        )

        val upgraded = original.copy(
            action = AgentActionType.CONFIRM_AND_CLICK,
            confirmationPrompt = "Kirim pesan ke Alif?"
        )

        assertEquals(AgentActionType.CONFIRM_AND_CLICK, upgraded.action)
        assertEquals(4, upgraded.targetIndex)
        assertEquals("Kirim pesan ke Alif?", upgraded.confirmationPrompt)
    }

    @Test
    fun `MultiAgentMinitapAdapter memecah sub-tujuan majemuk lintas aplikasi`() = runBlocking {
        val cortex = StubCortexBrain()
        val multiAgent = MultiAgentMinitapAdapter(cortexDelegate = cortex)

        val goal = "Cek jadwal rapat di kalender hari ini lalu kirim ke Alif lewat WhatsApp"
        val subgoals = multiAgent.decomposeSubgoals(goal)

        assertEquals(2, subgoals.size)
        assertEquals("Cek jadwal rapat di kalender hari ini", subgoals.first())
        assertEquals("kirim ke Alif lewat WhatsApp", subgoals.last())

        val action = multiAgent.decideNextAction(
            userGoal = goal,
            currentPackage = "com.google.android.apps.nexuslauncher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(
                recentSteps = emptyList(),
                scratchpad = emptyMap()
            )
        )

        assertEquals(AgentActionType.LAUNCH_APP, action.action)
        assertNotNull(cortex.capturedKnowledge)
        assertTrue(cortex.capturedKnowledge!!.contains("[MULTI-AGENT PLANNER SUBGOALS]"))
    }

    @Test
    fun `SingleAgentReActAdapter menggunakan Fireworks AI Serverless JSON Mode sebagai otak utama`() = runBlocking {
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        var capturedBody: String? = null

        val adapter = SingleAgentReActAdapter(
            credentials = LlmCredentialsConfig(
                fireworksApiKey = "fw_test_secret_key",
                fireworksModel = "accounts/fireworks/models/deepseek-v4p1-flash"
            ),
            httpTransport = { url, headers, jsonBody ->
                capturedUrl = url
                capturedHeaders = headers
                capturedBody = jsonBody
                val fireworksResponse = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("thought", "Buka WhatsApp melalui intent aplikasi.")
                                        .put("reflection_on_last_step", "INITIAL")
                                        .put("action", "LAUNCH_APP")
                                        .put("app_name", "WhatsApp")
                                        .toString()
                                )
                        )
                    )
                )
                LlmHttpResponse(statusCode = 200, body = fireworksResponse.toString())
            }
        )

        val result = adapter.decideNextAction(
            userGoal = "Kirim pesan WhatsApp ke Alif",
            currentPackage = "com.google.android.apps.nexuslauncher",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(
                recentSteps = emptyList(),
                scratchpad = emptyMap()
            )
        )

        assertEquals(EnvConfig.fireworksChatCompletionsUrl, capturedUrl)
        assertEquals("Bearer fw_test_secret_key", capturedHeaders?.get("Authorization"))
        val reqObj = JSONObject(capturedBody!!)
        assertEquals("accounts/fireworks/models/deepseek-v4p1-flash", reqObj.getString("model"))
        assertEquals("json_object", reqObj.getJSONObject("response_format").getString("type"))
        assertEquals(AgentActionType.LAUNCH_APP, result.action)
        assertEquals("WhatsApp", result.appName)
    }

    @Test
    fun `SingleAgentReActAdapter melakukan failover otomatis ke Gemini Flash saat Fireworks mengalami HTTP 503 atau 402`() = runBlocking {
        com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        try {
            val visitedUrls = mutableListOf<String>()

            val adapter = SingleAgentReActAdapter(
                credentials = LlmCredentialsConfig(
                    fireworksApiKey = "fw_primary_key",
                    geminiApiKey = "gemini_failover_key"
                ),
                httpTransport = { url, _, _ ->
                    visitedUrls.add(url)
                    if (url == EnvConfig.fireworksChatCompletionsUrl) {
                        LlmHttpResponse(statusCode = 503, body = """{"error":"service_unavailable"}""")
                    } else {
                        val geminiResponse = JSONObject().put(
                            "candidates",
                            JSONArray().put(
                                JSONObject().put(
                                    "content",
                                    JSONObject().put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "text",
                                                JSONObject()
                                                    .put("thought", "Failover Gemini mengeksekusi klik kontak.")
                                                    .put("reflection_on_last_step", "CORRECT")
                                                    .put("action", "CLICK")
                                                    .put("target_index", 2)
                                                    .toString()
                                            )
                                        )
                                    )
                                )
                            )
                        )
                        LlmHttpResponse(statusCode = 200, body = geminiResponse.toString())
                    }
                }
            )

            val result = adapter.decideNextAction(
                userGoal = "Klik kontak Alif",
                currentPackage = "com.whatsapp",
                screenElements = emptyList(),
                memory = ShortTermMemorySnapshot(
                    recentSteps = emptyList(),
                    scratchpad = emptyMap()
                )
            )

            assertEquals(2, visitedUrls.size)
            assertEquals(EnvConfig.fireworksChatCompletionsUrl, visitedUrls.first())
            assertTrue(visitedUrls.last().contains("generativelanguage.googleapis.com"))
            assertEquals(AgentActionType.CLICK, result.action)
            assertEquals(2, result.targetIndex)
        } finally {
            com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        }
    }

    @Test
    fun `ByokPreferencesManager memungkinkan beralih ke penyedia OpenAI-Compatible kustom seperti OpenRouter saat kuota habis`() = runBlocking {
        val byokManager = ByokPreferencesManager(context = null)
        byokManager.applyCustomOpenAiPreset(
            preset = ByokProviderPreset.OPENROUTER,
            apiKey = "sk-or-v1-byok-custom-key"
        )

        var capturedUrl: String? = null
        var capturedAuth: String? = null
        var capturedModel: String? = null

        val adapter = SingleAgentReActAdapter(
            byokPreferencesManager = byokManager,
            httpTransport = { url, headers, body ->
                capturedUrl = url
                capturedAuth = headers["Authorization"]
                capturedModel = JSONObject(body).optString("model")
                val openAiResponse = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("thought", "Eksekusi via BYOK OpenRouter.")
                                        .put("reflection_on_last_step", "CORRECT")
                                        .put("action", "DONE")
                                        .put("completion_tts", "Tugas selesai melalui OpenRouter.")
                                        .toString()
                                )
                        )
                    )
                )
                LlmHttpResponse(statusCode = 200, body = openAiResponse.toString())
            }
        )

        val result = adapter.decideNextAction(
            userGoal = "Selesaikan tugas",
            currentPackage = "com.whatsapp",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(
                recentSteps = emptyList(),
                scratchpad = emptyMap()
            )
        )

        assertEquals(ByokProviderPreset.OPENROUTER.chatCompletionsUrl, capturedUrl)
        assertEquals("Bearer sk-or-v1-byok-custom-key", capturedAuth)
        assertEquals(ByokProviderPreset.OPENROUTER.recommendedModel, capturedModel)
        assertEquals(AgentActionType.DONE, result.action)
        assertEquals("Tugas selesai melalui OpenRouter.", result.completionTts)
    }
}
