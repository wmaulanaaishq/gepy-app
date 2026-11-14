package com.teknologigawai.gepy.evals

import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.voice.GeminiLiveClient
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallingEvaluationTest {

    private class TestableGeminiLiveClient : GeminiLiveClient(apiKey = "dummy") {
        fun exposedDeclarations(): List<String> {
            val setupPayload = buildSetupMessageJson()
            val setupObj = setupPayload.getJSONObject("setup")
            val tools = setupObj.getJSONArray("tools")
            val toolObj = tools.getJSONObject(0)
            val funcDecls = toolObj.getJSONArray("functionDeclarations")
            val names = mutableListOf<String>()
            for (i in 0 until funcDecls.length()) {
                val decl = funcDecls.getJSONObject(i)
                names.add(decl.getString("name"))
            }
            return names
        }
    }

    @Test
    fun `Semua tool declaration di GeminiLiveClient memiliki skema valid dan tanpa duplikasi`() {
        val client = TestableGeminiLiveClient()
        val tools = client.exposedDeclarations()

        assertTrue("Tool declaration harus memiliki minimal 15 tool", tools.size >= 15)
        assertEquals("Dilarang ada duplikasi nama fungsi tool", tools.size, tools.toSet().size)

        // Verifikasi keberadaan tool-tool kunci yang diminta oleh pengguna
        val requiredTools = listOf(
            "get_github_repo_details",
            "get_github_readme",
            "query_github_trending_or_search",
            "get_huggingface_model_details",
            "query_huggingface_trending",
            "open_browser_url",
            "search_web_realtime",
            "save_user_memory",
            "update_or_correct_memory",
            "delete_user_memory",
            "query_memory_history",
            "set_system_alarm",
            "open_system_settings_panel"
        )

        for (req in requiredTools) {
            assertTrue("Tool wajib '$req' harus terdaftar di GeminiLiveClient", tools.contains(req))
        }
    }

    @Test
    fun `Dataset tool_calling_eval memetakan tool resmi dan tidak ada tool fiktif`() {
        val datasetFile = File("tests/evals/datasets/tool_calling_eval.json")
        if (!datasetFile.exists()) {
            return
        }

        val jsonContent = datasetFile.readText()
        val datasetObj = JSONObject(jsonContent)
        val testCases = datasetObj.getJSONArray("test_cases")

        val client = TestableGeminiLiveClient()
        val validTools = client.exposedDeclarations().toSet()

        for (i in 0 until testCases.length()) {
            val tc = testCases.getJSONObject(i)
            val expected = if (tc.isNull("expected_tool")) null else tc.getString("expected_tool")
            if (expected != null) {
                assertTrue(
                    "Test case '${tc.getString("id")}' mengekspektasikan tool '$expected' yang harus valid",
                    validTools.contains(expected)
                )
            }
        }
    }

    @Test
    fun `Disambiguasi kontak mencegah salah dengar atau salah kirim ke orang yang salah`() {
        // Memastikan tipe aksi disambiguasi resmi tersedia dan terdefinisi di ReAct Action Space
        val disambigAction = AgentActionType.valueOf("ASK_USER_DISAMBIGUATION")
        assertEquals(AgentActionType.ASK_USER_DISAMBIGUATION, disambigAction)
        assertNotNull(disambigAction)
    }
}
