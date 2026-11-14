package com.teknologigawai.gepy.core.web

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Respons HTTP mentah untuk abstraksi transport jaringan [WebSearchAndReaderBridge]. */
data class WebHttpResponse(
    val statusCode: Int,
    val body: String
)

/** Antarmuka transport HTTP minimal agar [WebSearchAndReaderBridge] 100% dapat diuji di JVM Unit Test. */
interface WebHttpTransport {
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String
    ): WebHttpResponse

    suspend fun get(
        url: String,
        headers: Map<String, String>
    ): WebHttpResponse
}

/** Implementasi produksi [WebHttpTransport] menggunakan `OkHttpClient`. */
class OkHttpWebTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) : WebHttpTransport {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String
    ): WebHttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        client.newCall(builder.build()).execute().use { resp ->
            WebHttpResponse(
                statusCode = resp.code,
                body = resp.body?.string().orEmpty()
            )
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>
    ): WebHttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .get()
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        client.newCall(builder.build()).execute().use { resp ->
            WebHttpResponse(
                statusCode = resp.code,
                body = resp.body?.string().orEmpty()
            )
        }
    }
}

/** Hasil pencarian web real-time terverifikasi beserta sitasi sumber. */
data class WebSearchResult(
    val success: Boolean,
    val query: String,
    val spokenAnswer: String,
    val sources: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    fun toToolResponseMap(): Map<String, Any?> = mapOf(
        "status" to if (success) "SUCCESS" else "ERROR",
        "query" to query,
        "answer" to spokenAnswer,
        "sources" to sources.joinToString(", "),
        "summary_id" to spokenAnswer
    )
}

/** Hasil pembacaan dan ekstraksi halaman web bersih menggunakan Jina Reader (`r.jina.ai`). */
data class WebpageReadResult(
    val success: Boolean,
    val url: String,
    val title: String,
    val markdownContent: String,
    val spokenSummary: String,
    val errorMessage: String? = null
) {
    fun toToolResponseMap(): Map<String, Any?> = mapOf(
        "status" to if (success) "SUCCESS" else "ERROR",
        "url" to url,
        "title" to title,
        "content" to markdownContent,
        "summary_id" to spokenSummary
    )
}

/** Potongan informasi hasil pencarian web real-time yang telah dievaluasi tingkat kredibilitasnya. */
internal data class WebSearchSnippet(
    val title: String,
    val snippet: String,
    val source: String,
    val linkUrl: String = "",
    val sourceUrl: String = "",
    val publishedDate: String = "",
    val credibilityTier: CredibilityTier = CredibilityTier.TIER_4_GENERAL_WEB,
    val authorityScore: Int = CredibilityTier.TIER_4_GENERAL_WEB.baseScore,
    val deepLeadExcerpt: String = ""
)
