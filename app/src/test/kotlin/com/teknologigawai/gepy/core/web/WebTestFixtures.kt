package com.teknologigawai.gepy.core.web



internal class FakeWebHttpTransport(
        private val postHandler: suspend (url: String, body: String) -> WebHttpResponse = { _, _ ->
            WebHttpResponse(200, "{}")
        },
        private val getHandler: suspend (url: String) -> WebHttpResponse = { _ ->
            WebHttpResponse(200, "{}")
        }
    ) : WebHttpTransport {
        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            jsonBody: String
        ): WebHttpResponse {
            return postHandler(url, jsonBody)
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>
        ): WebHttpResponse {
            return getHandler(url)
        }
    }
