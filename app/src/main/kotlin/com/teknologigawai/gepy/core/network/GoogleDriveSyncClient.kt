package com.teknologigawai.gepy.core.network

import com.teknologigawai.gepy.core.logging.GepyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Metadata berkas cadangan di Google Drive AppData Folder.
 */
data class GoogleDriveFileMetadata(
    val id: String,
    val name: String,
    val modifiedTime: String = "",
    val size: Long = 0L
)

/**
 * Klien REST mandiri untuk Google Drive API v3.
 *
 * Bertanggung jawab atas:
 * 1. Menemukan snapshot cadangan di folder terisolasi `appDataFolder`.
 * 2. Mengunggah snapshot baru (`multipart/related`) atau memperbarui berkas yang sudah ada.
 * 3. Mengunduh isi JSON snapshot saat pengguna memulihkan aplikasi setelah instal ulang.
 * 4. Berjalan dengan batas memori hemat (tanpa pustaka SDK Google Drive raksasa yang berat).
 */
class GoogleDriveSyncClient(
    val apiBaseUrl: String = "https://www.googleapis.com/drive/v3",
    val uploadBaseUrl: String = "https://www.googleapis.com/upload/drive/v3",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    companion object {
        private const val TAG = "GoogleDriveSyncClient"
        const val VAULT_FILE_NAME = "gepy_temporal_vault.json"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Mengambil daftar berkas cadangan yang berada di `appDataFolder` milik pengguna.
     */
    suspend fun listAppDataFiles(accessToken: String): Result<List<GoogleDriveFileMetadata>> = withContext(Dispatchers.IO) {
        val cleanToken = accessToken.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Token akses Google Drive kosong."))
        }

        val url = "$apiBaseUrl/files?spaces=appDataFolder&fields=files(id,name,modifiedTime,size)&pageSize=20"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    GepyLog.w(TAG, "Gagal membaca berkas Google Drive appData: HTTP ${response.code} - $bodyString")
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: Gagal mengambil data Drive ($bodyString)")
                    )
                }

                val jsonRoot = JSONObject(bodyString)
                val filesArr = jsonRoot.optJSONArray("files") ?: org.json.JSONArray()
                val list = mutableListOf<GoogleDriveFileMetadata>()
                for (i in 0 until filesArr.length()) {
                    val obj = filesArr.optJSONObject(i) ?: continue
                    list.add(
                        GoogleDriveFileMetadata(
                            id = obj.optString("id", ""),
                            name = obj.optString("name", ""),
                            modifiedTime = obj.optString("modifiedTime", ""),
                            size = obj.optLong("size", 0L)
                        )
                    )
                }
                Result.success(list)
            }
        } catch (t: Throwable) {
            GepyLog.e(TAG, "Kesalahan jaringan saat memuat daftar berkas Drive: ${t.message}", t)
            Result.failure(t)
        }
    }

    /**
     * Menemukan berkas cadangan vault memori [fileName] di `appDataFolder`.
     */
    suspend fun findVaultFile(
        accessToken: String,
        fileName: String = VAULT_FILE_NAME
    ): Result<GoogleDriveFileMetadata?> {
        val listResult = listAppDataFiles(accessToken)
        return listResult.map { files ->
            files.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
        }
    }

    /**
     * Mengunggah snapshot baru atau memperbarui snapshot yang sudah ada di `appDataFolder`.
     */
    suspend fun uploadVaultFile(
        accessToken: String,
        vaultJson: String,
        existingFileId: String? = null,
        fileName: String = VAULT_FILE_NAME
    ): Result<GoogleDriveFileMetadata> = withContext(Dispatchers.IO) {
        val cleanToken = accessToken.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Token akses Google Drive kosong."))
        }

        if (existingFileId != null && existingFileId.isNotBlank()) {
            // Perbarui isi berkas yang sudah ada (PATCH uploadType=media)
            val patchUrl = "$uploadBaseUrl/files/$existingFileId?uploadType=media"
            val requestBody = vaultJson.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(patchUrl)
                .header("Authorization", "Bearer $cleanToken")
                .patch(requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("HTTP ${response.code}: Gagal memperbarui cadangan Drive ($bodyString)")
                        )
                    }
                    val obj = JSONObject(bodyString)
                    Result.success(
                        GoogleDriveFileMetadata(
                            id = obj.optString("id", existingFileId),
                            name = obj.optString("name", fileName),
                            modifiedTime = obj.optString("modifiedTime", ""),
                            size = vaultJson.toByteArray(Charsets.UTF_8).size.toLong()
                        )
                    )
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        } else {
            // Unggah berkas baru ke appDataFolder (POST multipart/related)
            val postUrl = "$uploadBaseUrl/files?uploadType=multipart"
            val metadataJson = JSONObject().apply {
                put("name", fileName)
                put("parents", org.json.JSONArray().put("appDataFolder"))
            }.toString()

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", null, metadataJson.toRequestBody(JSON_MEDIA_TYPE))
                .addFormDataPart("file", fileName, vaultJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val request = Request.Builder()
                .url(postUrl)
                .header("Authorization", "Bearer $cleanToken")
                .post(multipartBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("HTTP ${response.code}: Gagal mengunggah cadangan baru ke Drive ($bodyString)")
                        )
                    }
                    val obj = JSONObject(bodyString)
                    Result.success(
                        GoogleDriveFileMetadata(
                            id = obj.optString("id", ""),
                            name = obj.optString("name", fileName),
                            modifiedTime = obj.optString("modifiedTime", ""),
                            size = vaultJson.toByteArray(Charsets.UTF_8).size.toLong()
                        )
                    )
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Mengunduh konten mentah JSON dari berkas cadangan di Google Drive.
     */
    suspend fun downloadVaultFile(
        accessToken: String,
        fileId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanToken = accessToken.trim()
        val cleanId = fileId.trim()
        if (cleanToken.isBlank() || cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Parameter token atau fileId kosong."))
        }

        val url = "$apiBaseUrl/files/$cleanId?alt=media"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: Gagal mengunduh berkas cadangan ($bodyString)")
                    )
                }
                Result.success(bodyString)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Menghapus berkas cadangan di Google Drive jika diinginkan oleh pengguna.
     */
    suspend fun deleteVaultFile(
        accessToken: String,
        fileId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanToken = accessToken.trim()
        val cleanId = fileId.trim()
        if (cleanToken.isBlank() || cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Parameter token atau fileId kosong."))
        }

        val url = "$apiBaseUrl/files/$cleanId"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanToken")
            .delete()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    val bodyString = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: Gagal menghapus berkas cadangan Drive ($bodyString)")
                    )
                }
                Result.success(true)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
