package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.errors.LlmInferenceException
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleAgentReActAdapterTest {

    private val adapter = SingleAgentReActAdapter()

    @Test
    fun `extractOpenAiChatContent mengembalikan isi content jika tersedia dan valid`() {
        val rawJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "{\"thought\": \"Buka WA\", \"action\": \"LAUNCH_APP\", \"app_name\": \"WhatsApp\"}"
                  },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()

        val extracted = adapter.extractOpenAiChatContent(rawJson)
        val actionModel = ReActActionModel.fromRawJsonString(extracted)
        assertEquals("WhatsApp", actionModel.appName)
        assertEquals(AgentActionType.LAUNCH_APP, actionModel.action)
    }

    @Test
    fun `extractOpenAiChatContent berhasil mengekstrak JSON dari reasoning_content jika content kosong`() {
        // Skenario DeepSeek V4.1 / R1: reasoning menghabiskan token atau menaruh JSON di reasoning_content
        val rawJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "",
                    "reasoning_content": "Mari kita pertimbangkan langkah berikutnya. Pengguna ingin posting ke story. Maka JSON yang tepat adalah: {\"thought\": \"Ketuk cerita anda\", \"action\": \"CLICK\", \"target_index\": 4} dan selesai."
                  },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()

        val extracted = adapter.extractOpenAiChatContent(rawJson)
        val actionModel = ReActActionModel.fromRawJsonString(extracted)
        assertEquals(4, actionModel.targetIndex)
        assertEquals(AgentActionType.CLICK, actionModel.action)
    }

    @Test(expected = LlmInferenceException::class)
    fun `extractOpenAiChatContent melempar LlmInferenceException jika content dan reasoning keduanya kosong`() {
        val rawJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "reasoning_content": "Hanya teks berpikir tanpa ada format json kurung kurawal sama sekali."
                  },
                  "finish_reason": "length"
                }
              ]
            }
        """.trimIndent()

        adapter.extractOpenAiChatContent(rawJson)
    }

    @Test
    fun `buildUserPayload memadatkan langkah lama dengan earlier_steps_summary jika riwayat lebih dari 4 langkah`() {
        val steps = (1..6).map { idx ->
            StepRecord(
                stepNumber = idx,
                action = AgentActionType.CLICK,
                targetLabel = "Elemen_$idx",
                screenSignatureBefore = "sig_$idx",
                screenSignatureAfter = "sig_${idx + 1}",
                outcomeStatus = ActionOutcome.CORRECT
            )
        }
        val memory = ShortTermMemorySnapshot(recentSteps = steps, scratchpad = emptyMap())

        val payloadStr = adapter.buildUserPayload(
            userGoal = "Posting cerita instagram",
            currentPackage = "com.instagram.android",
            screenElements = emptyList(),
            memory = memory
        )

        val payload = JSONObject(payloadStr)
        assertTrue(payload.has("earlier_steps_summary"))
        val summary = payload.getString("earlier_steps_summary")
        assertTrue(summary.contains("[#1]"))
        assertTrue(summary.contains("[#2]"))
        assertTrue(summary.contains("[#3]"))

        // recent_steps hanya boleh berisi 3 langkah terakhir (langkah 4, 5, 6)
        val recentSteps = payload.getJSONArray("recent_steps")
        assertEquals(3, recentSteps.length())
        assertEquals(4, recentSteps.getJSONObject(0).getInt("step"))
        assertEquals(6, recentSteps.getJSONObject(2).getInt("step"))
    }

    @Test
    fun `buildUserPayload tidak memadatkan langkah jika riwayat 4 langkah atau kurang`() {
        val steps = (1..3).map { idx ->
            StepRecord(
                stepNumber = idx,
                action = AgentActionType.CLICK,
                targetLabel = "Tombol_$idx",
                screenSignatureBefore = "sig_$idx",
                screenSignatureAfter = "sig_${idx + 1}",
                outcomeStatus = ActionOutcome.CORRECT
            )
        }
        val memory = ShortTermMemorySnapshot(recentSteps = steps, scratchpad = emptyMap())

        val payloadStr = adapter.buildUserPayload(
            userGoal = "Kirim pesan",
            currentPackage = "com.whatsapp",
            screenElements = emptyList(),
            memory = memory
        )

        val payload = JSONObject(payloadStr)
        assertFalse(payload.has("earlier_steps_summary"))
        val recentSteps = payload.getJSONArray("recent_steps")
        assertEquals(3, recentSteps.length())
    }

    @Test
    fun `ReActActionModel memulihkan JSON Action-First yang terpotong pada bagian akhir thought`() {
        val truncatedActionFirstJson =
            """{"action": "CLICK", "target_index": 19, "reflection_on_last_step": "CORRECT", "thought": "Mengetuk postingan ke-6 pada grid profil NewJe"""

        val salvaged = ReActActionModel.fromRawJsonString(truncatedActionFirstJson)
        assertEquals(AgentActionType.CLICK, salvaged.action)
        assertEquals(19, salvaged.targetIndex)
        assertTrue(salvaged.thought.contains("Mengetuk postingan ke-6"))
    }

    @Test
    fun `decideNextAction melakukan failover otomatis ke Gemini Flash saat Fireworks mengembalikan HTTP 200 dengan JSON thought terpotong`() = kotlinx.coroutines.runBlocking {
        val visitedUrls = mutableListOf<String>()
        val babblingTruncatedContent = """{"thought": "Pengguna ingin membagikan postingan ke-6 NewJeans ke story. """ +
            "Namun kita belum tahu apakah postingan ke-6 ada di bawah. Tapi kita bisa scroll ke bawah. ".repeat(45) +
            "Tapi kita tidak tahu berapa banyak. M"

        val failoverAdapter = SingleAgentReActAdapter(
            credentials = LlmCredentialsConfig(
                fireworksApiKey = "fw_key",
                geminiApiKey = "gem_key"
            ),
            httpTransport = { url, _, _ ->
                visitedUrls.add(url)
                if (url.contains("fireworks.ai")) {
                    val fwResp = JSONObject().put(
                        "choices",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("finish_reason", "length")
                                .put(
                                    "message",
                                    JSONObject()
                                        .put("role", "assistant")
                                        .put("content", babblingTruncatedContent)
                                )
                        )
                    )
                    LlmHttpResponse(statusCode = 200, body = fwResp.toString())
                } else {
                    val gemResp = JSONObject().put(
                        "candidates",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "content",
                                JSONObject().put(
                                    "parts",
                                    org.json.JSONArray().put(
                                        JSONObject().put(
                                            "text",
                                            """{"action":"BACK","reflection_on_last_step":"ERRONEOUS","thought":"Kembali ke grid profil untuk memilih postingan ke-6."}"""
                                        )
                                    )
                                )
                            )
                        )
                    )
                    LlmHttpResponse(statusCode = 200, body = gemResp.toString())
                }
            }
        )

        val result = failoverAdapter.decideNextAction(
            userGoal = "buka ig newjeans cari postingan ke 6 lalu repost ke story dan berikan caption kalau kamu yang post story",
            currentPackage = "com.instagram.android",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(recentSteps = emptyList(), scratchpad = emptyMap())
        )

        assertEquals(2, visitedUrls.size)
        assertEquals(AgentActionType.BACK, result.action)
    }

    @Test
    fun `detectSpatialMediaGridSummary memetakan urutan Postingan 1 sampai 6 pada grid 3 kolom profil Instagram secara akurat`() {
        val elements = listOf(
            // Avatar profil tunggal di kiri atas (harus diabaikan)
            PrunedUiElement(
                index = 2,
                nodeId = "row_profile_header_imageview",
                viewResourceId = "com.instagram.android:id/row_profile_header_imageview",
                isClickable = true,
                rect = PrunedUiElement.buildRect(40, 220, 220, 220)
            ),
            // Baris 1 Grid (Postingan #1, #2, #3) - Sengaja diacak urutan list untuk menguji pengurutan geometri (y lalu x)
            PrunedUiElement(index = 12, nodeId = "post_r1_c3", isClickable = true, rect = PrunedUiElement.buildRect(722, 950, 355, 355)),
            PrunedUiElement(index = 10, nodeId = "post_r1_c1", text = "Disematkan", isClickable = true, rect = PrunedUiElement.buildRect(0, 950, 355, 355)),
            PrunedUiElement(index = 11, nodeId = "post_r1_c2", isClickable = true, rect = PrunedUiElement.buildRect(361, 950, 355, 355)),
            // Baris 2 Grid (Postingan #4, #5, #6)
            PrunedUiElement(index = 15, nodeId = "post_r2_c3", isClickable = true, rect = PrunedUiElement.buildRect(722, 1312, 355, 355)),
            PrunedUiElement(index = 13, nodeId = "post_r2_c1", isClickable = true, rect = PrunedUiElement.buildRect(0, 1312, 355, 355)),
            PrunedUiElement(index = 14, nodeId = "post_r2_c2", isClickable = true, rect = PrunedUiElement.buildRect(361, 1312, 355, 355)),
            // Tab navigasi bawah (harus diabaikan karena tinggi < 160 dan id tab_bar)
            PrunedUiElement(index = 20, nodeId = "search_tab", viewResourceId = "com.instagram.android:id/search_tab", isClickable = true, rect = PrunedUiElement.buildRect(216, 2240, 216, 140))
        )

        val gridSummary = adapter.detectSpatialMediaGridSummary(elements)
        assertNotNull(gridSummary)
        assertTrue(gridSummary!!.contains("Postingan #1 (Pinned) -> idx=10"))
        assertTrue(gridSummary.contains("Postingan #2 -> idx=11"))
        assertTrue(gridSummary.contains("Postingan #3 -> idx=12"))
        assertTrue(gridSummary.contains("Postingan #4 -> idx=13"))
        assertTrue(gridSummary.contains("Postingan #5 -> idx=14"))
        assertTrue(gridSummary.contains("Postingan #6 -> idx=15"))
        assertFalse(gridSummary.contains("idx=2,"))
        assertFalse(gridSummary.contains("idx=20"))
    }

    @Test
    fun `summarizeUserFacingError dan GepyOrchestratorState memisahkan status Live Call pasif dari eksekusi aktif`() {
        val rawBabbleError = """Respons LLM bukan objek JSON yang valid: Unterminated string at character 4119 of { "thought": "${"Namun kita perlu scroll. ".repeat(160)}"""
        val clean = com.teknologigawai.gepy.ui.summarizeUserFacingError(rawBabbleError)
        assertTrue("Pesan error UI harus diringkas <= 220 karakter", clean.length <= 220)
        assertFalse("Pesan error UI utama tidak boleh membocorkan JSON mentah", clean.contains("\"thought\""))

        val liveListeningState = com.teknologigawai.gepy.features.orchestrator.GepyOrchestratorState(
            fsmState = com.teknologigawai.gepy.features.orchestrator.GepyFsmState.LISTENING,
            isLiveCallActive = true
        )
        assertFalse("Live Call pasif tidak boleh dianggap sedang mengeksekusi langkah UI", liveListeningState.isExecutingGoal)
        assertFalse("Live Call pasif tidak boleh mengunci input isBusy", liveListeningState.isBusy)
    }

    @Test
    fun `decideNextAction melakukan failover Key 1 ke Key 2 saat Gemini mengembalikan HTTP 429`() = kotlinx.coroutines.runBlocking {
        com.teknologigawai.gepy.core.config.GeminiKeyPool.resetCooldownsForTest()
        val seenKeys = mutableListOf<String>()
        val gemSuccess = JSONObject().put(
            "candidates",
            org.json.JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "text",
                                """{"action":"WAIT","wait_ms":500,"reflection_on_last_step":"NONE","thought":"Key 2 mengambil alih setelah Key 1 429."}"""
                            )
                        )
                    )
                )
            )
        ).toString()

        val failoverAdapter = SingleAgentReActAdapter(
            credentials = LlmCredentialsConfig(
                preferredProvider = com.teknologigawai.gepy.core.config.PreferredLlmProvider.GEMINI,
                geminiApiKey = "gem_primary_429",
                geminiSecondaryApiKey = "gem_backup_200"
            ),
            httpTransport = { _, headers, _ ->
                val sentKey = headers["x-goog-api-key"].orEmpty()
                seenKeys.add(sentKey)
                if (sentKey == "gem_primary_429") {
                    LlmHttpResponse(statusCode = 429, body = """{"error":{"code":429,"message":"RESOURCE_EXHAUSTED"}}""")
                } else {
                    LlmHttpResponse(statusCode = 200, body = gemSuccess)
                }
            }
        )

        val result = failoverAdapter.decideNextAction(
            userGoal = "tunggu sebentar",
            currentPackage = "com.whatsapp",
            screenElements = emptyList(),
            memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
        )

        assertEquals(listOf("gem_primary_429", "gem_backup_200"), seenKeys)
        assertEquals(AgentActionType.WAIT, result.action)
        assertTrue(com.teknologigawai.gepy.core.config.GeminiKeyPool.isInCooldown("gem_primary_429"))
        com.teknologigawai.gepy.core.config.GeminiKeyPool.resetCooldownsForTest()
    }

    @Test
    fun `buildSystemPrompt dan buildConversationalSystemPrompt memuat aturan tegas anti-time-travel dan anti-fake-reminder`() {
        val prompt = adapter.buildSystemPrompt(currentPackage = "com.sec.android.app.launcher")
        assertTrue("Prompt ReAct wajib memuat aturan anti-time-travel", prompt.contains("ANTI-TIME-TRAVEL"))
        assertTrue("Prompt ReAct melarang klaim palsu bahwa waktu di masa depan sudah berlalu", prompt.contains("klaim palsu bahwa waktu di masa depan sudah berlalu"))

        val conversationalPrompt = adapter.buildConversationalSystemPrompt()
        assertTrue("Prompt percakapan wajib memuat aturan waktu masa depan", conversationalPrompt.contains("ZERO-TIME-TRAVEL"))
        assertTrue("Prompt percakapan melarang halusinasi waktu masa depan", conversationalPrompt.contains("DILARANG KERAS berhalusinasi atau berpura-pura bahwa waktu di masa depan sudah berlalu"))
    }

    @Test
    fun `decideNextAction memicu cooldown HTTP 402 pada CUSTOM_OPENAI sehingga langkah berikutnya langsung melewati OpenRouter`() = kotlinx.coroutines.runBlocking {
        com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        try {
            val visitedUrls = mutableListOf<String>()
            val fwSuccess = JSONObject().put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", """{"action":"WAIT","wait_ms":300,"reflection_on_last_step":"NONE","thought":"Fireworks menangani setelah OpenRouter 402."}""")
                    )
                )
            ).toString()

            val failoverAdapter = SingleAgentReActAdapter(
                credentials = LlmCredentialsConfig(
                    preferredProvider = com.teknologigawai.gepy.core.config.PreferredLlmProvider.CUSTOM_OPENAI,
                    customOpenAiEnabled = true,
                    customOpenAiUrl = "https://openrouter.ai/api/v1/chat/completions",
                    customOpenAiApiKey = "sk-or-empty-credits",
                    customOpenAiModel = "deepseek/deepseek-chat-v3-0324",
                    fireworksApiKey = "fw_healthy_backup"
                ),
                httpTransport = { url, _, _ ->
                    visitedUrls.add(url)
                    if (url.contains("openrouter.ai")) {
                        LlmHttpResponse(
                            statusCode = 402,
                            body = """{"error":{"message":"This request requires more credits. You requested up to 1024 tokens, but can only afford 475."}}"""
                        )
                    } else {
                        LlmHttpResponse(statusCode = 200, body = fwSuccess)
                    }
                }
            )

            val firstResult = failoverAdapter.decideNextAction(
                userGoal = "cek kondisi layar",
                currentPackage = "com.android.settings",
                screenElements = emptyList(),
                memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
            )
            assertEquals(AgentActionType.WAIT, firstResult.action)
            assertEquals(2, visitedUrls.size)
            assertTrue(visitedUrls[0].contains("openrouter.ai"))
            assertTrue(visitedUrls[1].contains("fireworks.ai"))
            assertTrue(
                com.teknologigawai.gepy.core.config.LlmProviderHealthTracker
                    .activeCooldownsSnapshot()
                    .any { it.provider == com.teknologigawai.gepy.core.config.PreferredLlmProvider.CUSTOM_OPENAI }
            )

            visitedUrls.clear()
            val secondResult = failoverAdapter.decideNextAction(
                userGoal = "cek kondisi layar langkah 2",
                currentPackage = "com.android.settings",
                screenElements = emptyList(),
                memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
            )
            assertEquals(AgentActionType.WAIT, secondResult.action)
            assertEquals(1, visitedUrls.size)
            assertFalse("OpenRouter tidak boleh dihubungi lagi selama cooldown 402 aktif", visitedUrls.any { it.contains("openrouter.ai") })
            assertTrue(visitedUrls.single().contains("fireworks.ai"))
        } finally {
            com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        }
    }

    @Test
    fun `customOpenAiEnabled false menonaktifkan CUSTOM_OPENAI meski API key tersimpan`() = kotlinx.coroutines.runBlocking {
        com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        try {
            val visitedUrls = mutableListOf<String>()
            val fwSuccess = JSONObject().put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", """{"action":"WAIT","wait_ms":200,"reflection_on_last_step":"NONE","thought":"Custom dinonaktifkan, pakai Fireworks."}""")
                    )
                )
            ).toString()

            val disabledCustomAdapter = SingleAgentReActAdapter(
                credentials = LlmCredentialsConfig(
                    preferredProvider = com.teknologigawai.gepy.core.config.PreferredLlmProvider.CUSTOM_OPENAI,
                    customOpenAiEnabled = false,
                    customOpenAiUrl = "https://openrouter.ai/api/v1/chat/completions",
                    customOpenAiApiKey = "sk-or-saved-but-disabled",
                    customOpenAiModel = "deepseek/deepseek-chat-v3-0324",
                    fireworksApiKey = "fw_healthy_backup"
                ),
                httpTransport = { url, _, _ ->
                    visitedUrls.add(url)
                    LlmHttpResponse(statusCode = 200, body = fwSuccess)
                }
            )

            val result = disabledCustomAdapter.decideNextAction(
                userGoal = "buka setelan",
                currentPackage = "com.android.settings",
                screenElements = emptyList(),
                memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
            )
            assertEquals(AgentActionType.WAIT, result.action)
            assertEquals(1, visitedUrls.size)
            assertTrue(visitedUrls.single().contains("fireworks.ai"))
        } finally {
            com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        }
    }

    @Test
    fun `decideNextAction menyesuaikan max_tokens adaptif saat penyedia tunggal membalas HTTP 402 can only afford N`() = kotlinx.coroutines.runBlocking {
        com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        try {
            val seenMaxTokens = mutableListOf<Int>()
            val openRouterSuccess = JSONObject().put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", """{"action":"WAIT","wait_ms":250,"reflection_on_last_step":"NONE","thought":"Berhasil dengan batas token hemat."}""")
                    )
                )
            ).toString()

            val singleCustomAdapter = SingleAgentReActAdapter(
                credentials = LlmCredentialsConfig(
                    preferredProvider = com.teknologigawai.gepy.core.config.PreferredLlmProvider.CUSTOM_OPENAI,
                    customOpenAiEnabled = true,
                    customOpenAiUrl = "https://openrouter.ai/api/v1/chat/completions",
                    customOpenAiApiKey = "sk-or-low-balance",
                    customOpenAiModel = "deepseek/deepseek-chat-v3-0324",
                    fireworksApiKey = "",
                    geminiApiKey = "",
                    geminiSecondaryApiKey = ""
                ),
                httpTransport = { _, _, body ->
                    val reqJson = JSONObject(body)
                    val maxTok = reqJson.optInt("max_tokens", -1)
                    seenMaxTokens.add(maxTok)
                    if (seenMaxTokens.size == 1) {
                        LlmHttpResponse(
                            statusCode = 402,
                            body = """{"error":{"message":"You requested up to 1024 tokens, but can only afford 475."}}"""
                        )
                    } else {
                        LlmHttpResponse(statusCode = 200, body = openRouterSuccess)
                    }
                }
            )

            val result = singleCustomAdapter.decideNextAction(
                userGoal = "tunggu sebentar",
                currentPackage = "com.android.settings",
                screenElements = emptyList(),
                memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
            )
            assertEquals(AgentActionType.WAIT, result.action)
            assertEquals(2, seenMaxTokens.size)
            assertTrue("Percobaan ulang wajib menurunkan max_tokens <= 475", seenMaxTokens[1] in 1..475)
        } finally {
            com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        }
    }

    @Test
    fun `decideNextAction memicu cooldown SocketTimeoutException dan mengirim thinkingBudget 0 ke Gemini ReAct`() = kotlinx.coroutines.runBlocking {
        com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        try {
            val visitedUrls = mutableListOf<String>()
            var capturedGeminiBody = ""
            val aimlSuccess = JSONObject().put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", """{"action":"CLICK","target_index":0,"reflection_on_last_step":"INITIAL","thought":"AI/ML API merespons cepat."}""")
                    )
                )
            ).toString()

            val failoverAdapter = SingleAgentReActAdapter(
                credentials = LlmCredentialsConfig(
                    preferredProvider = com.teknologigawai.gepy.core.config.PreferredLlmProvider.GEMINI,
                    fireworksApiKey = "",
                    geminiApiKey = "gem_timeout_key",
                    geminiSecondaryApiKey = "",
                    geminiModel = "gemini-2.5-flash",
                    customOpenAiEnabled = true,
                    customOpenAiUrl = com.teknologigawai.gepy.core.config.ByokProviderPreset.AIML_API.chatCompletionsUrl,
                    customOpenAiApiKey = "aiml_fast_key",
                    customOpenAiModel = com.teknologigawai.gepy.core.config.ByokProviderPreset.AIML_API.recommendedModel
                ),
                httpTransport = { url, _, body ->
                    visitedUrls.add(url)
                    if (url.contains("generativelanguage.googleapis.com")) {
                        capturedGeminiBody = body
                        throw java.net.SocketTimeoutException("timeout after 14000ms")
                    } else {
                        LlmHttpResponse(statusCode = 200, body = aimlSuccess)
                    }
                }
            )

            val step1 = failoverAdapter.decideNextAction(
                userGoal = "pesankan gojek",
                currentPackage = "com.sec.android.app.launcher",
                screenElements = emptyList(),
                memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
            )
            assertEquals(AgentActionType.CLICK, step1.action)
            val geminiGenConfig = JSONObject(capturedGeminiBody).getJSONObject("generationConfig")
            assertEquals(0, geminiGenConfig.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
            assertTrue(
                com.teknologigawai.gepy.core.config.LlmProviderHealthTracker
                    .activeCooldownsSnapshot()
                    .any { it.provider == com.teknologigawai.gepy.core.config.PreferredLlmProvider.GEMINI }
            )

            visitedUrls.clear()
            val step2 = failoverAdapter.decideNextAction(
                userGoal = "pesankan gojek langkah 2",
                currentPackage = "com.gojek.app",
                screenElements = emptyList(),
                memory = ShortTermMemorySnapshot(emptyList(), emptyMap())
            )
            assertEquals(AgentActionType.CLICK, step2.action)
            assertEquals(1, visitedUrls.size)
            assertEquals(com.teknologigawai.gepy.core.config.ByokProviderPreset.AIML_API.chatCompletionsUrl, visitedUrls.single())
        } finally {
            com.teknologigawai.gepy.core.config.LlmProviderHealthTracker.resetAll()
        }
    }
}
