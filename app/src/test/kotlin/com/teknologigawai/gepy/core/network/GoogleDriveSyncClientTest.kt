package com.teknologigawai.gepy.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveSyncClientTest {

    private fun createMockClient(
        statusCode: Int = 200,
        responseBody: String = "{}"
    ): GoogleDriveSyncClient {
        val mockInterceptor = Interceptor { chain ->
            val request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(mockInterceptor)
            .build()

        return GoogleDriveSyncClient(
            apiBaseUrl = "https://fake.googleapis.com/drive/v3",
            uploadBaseUrl = "https://fake.googleapis.com/upload/drive/v3",
            client = okHttpClient
        )
    }

    @Test
    fun testListAppDataFiles_ParsesFilesCorrectly() = runBlocking {
        val jsonPayload = """
            {
                "files": [
                    {
                        "id": "vault_id_101",
                        "name": "gepy_temporal_vault.json",
                        "modifiedTime": "2026-11-08T06:00:00Z",
                        "size": "2048"
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient(statusCode = 200, responseBody = jsonPayload)
        val result = client.listAppDataFiles("valid_mock_token")

        assertTrue(result.isSuccess)
        val files = result.getOrThrow()
        assertEquals(1, files.size)
        assertEquals("vault_id_101", files[0].id)
        assertEquals("gepy_temporal_vault.json", files[0].name)
        assertEquals(2048L, files[0].size)
    }

    @Test
    fun testFindVaultFile_ReturnsMatchingMetadata() = runBlocking {
        val jsonPayload = """
            {
                "files": [
                    {
                        "id": "file_other",
                        "name": "unrelated.txt",
                        "size": "100"
                    },
                    {
                        "id": "vault_found_999",
                        "name": "gepy_temporal_vault.json",
                        "size": "4096"
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient(statusCode = 200, responseBody = jsonPayload)
        val foundResult = client.findVaultFile("valid_mock_token")

        assertTrue(foundResult.isSuccess)
        val metadata = foundResult.getOrThrow()
        assertNotNull(metadata)
        assertEquals("vault_found_999", metadata?.id)
        assertEquals("gepy_temporal_vault.json", metadata?.name)
    }

    @Test
    fun testUploadVaultFile_MultipartUploadSuccess() = runBlocking {
        val responsePayload = """
            {
                "id": "new_uploaded_file_888",
                "name": "gepy_temporal_vault.json",
                "modifiedTime": "2026-11-08T06:30:00Z"
            }
        """.trimIndent()

        val client = createMockClient(statusCode = 200, responseBody = responsePayload)
        val uploadResult = client.uploadVaultFile(
            accessToken = "valid_mock_token",
            vaultJson = "{\"formatVersion\":1,\"facts\":[]}",
            existingFileId = null
        )

        assertTrue(uploadResult.isSuccess)
        val meta = uploadResult.getOrThrow()
        assertEquals("new_uploaded_file_888", meta.id)
        assertEquals("gepy_temporal_vault.json", meta.name)
    }

    @Test
    fun testUploadVaultFile_PatchExistingSuccess() = runBlocking {
        val responsePayload = """
            {
                "id": "existing_file_777",
                "name": "gepy_temporal_vault.json",
                "modifiedTime": "2026-11-08T06:45:00Z"
            }
        """.trimIndent()

        val client = createMockClient(statusCode = 200, responseBody = responsePayload)
        val patchResult = client.uploadVaultFile(
            accessToken = "valid_mock_token",
            vaultJson = "{\"formatVersion\":1,\"facts\":[]}",
            existingFileId = "existing_file_777"
        )

        assertTrue(patchResult.isSuccess)
        val meta = patchResult.getOrThrow()
        assertEquals("existing_file_777", meta.id)
    }

    @Test
    fun testDownloadVaultFile_ReturnsRawContent() = runBlocking {
        val expectedVaultJson = "{\"formatVersion\":1,\"userDisplayName\":\"Maulana\",\"facts\":[]}"
        val client = createMockClient(statusCode = 200, responseBody = expectedVaultJson)

        val downloadResult = client.downloadVaultFile("valid_mock_token", "file_to_download")
        assertTrue(downloadResult.isSuccess)
        assertEquals(expectedVaultJson, downloadResult.getOrThrow())
    }

    @Test
    fun testListAppDataFiles_HandlesHttpErrorGracefully() = runBlocking {
        val errorBody = "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}"
        val client = createMockClient(statusCode = 401, responseBody = errorBody)

        val result = client.listAppDataFiles("expired_token")
        assertTrue("HTTP 401 harus menghasilkan Result.failure", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
    }

    @Test
    fun testDeleteVaultFile_Success() = runBlocking {
        val client = createMockClient(statusCode = 204, responseBody = "")
        val result = client.deleteVaultFile("valid_mock_token", "file_to_delete")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }
}
