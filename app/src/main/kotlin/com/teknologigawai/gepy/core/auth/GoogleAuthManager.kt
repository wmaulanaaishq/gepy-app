package com.teknologigawai.gepy.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.logging.GepyLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Model profil pengguna Gepy yang berhasil diautentikasi melalui Google OAuth. */
data class GepyUser(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String = "",
    val sessionToken: String = ""
)

/** Hasil operasi login Google. */
sealed interface GoogleAuthResult {
    data class Success(val user: GepyUser, val idToken: String) : GoogleAuthResult
    data class Cancelled(val message: String = "Pengguna membatalkan login.") : GoogleAuthResult
    data class Failure(val message: String, val throwable: Throwable? = null) : GoogleAuthResult
}

/**
 * Manajer Autentikasi Google modern menggunakan Android Credential Manager API (`androidx.credentials`).
 *
 * Mengusung prinsip *Accessibility-First*:
 * - Menggunakan One-Tap Sign-In tanpa perlu mengetik kata sandi di layar.
 * - Sesuai standar TalkBack Google Play Services.
 * - Menyimpan sesi aman di `SharedPreferences` terisolasi.
 */
class GoogleAuthManager(
    private val context: Context,
    private val credentialManager: CredentialManager = CredentialManager.create(context),
    private val webClientId: String = EnvConfig.googleWebClientId,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val PREFS_NAME = "gepy_auth_session_prefs"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_USER_EMAIL = "key_user_email"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_USER_AVATAR = "key_user_avatar"
        private const val KEY_SESSION_TOKEN = "key_session_token"
    }

    private val _currentUser = MutableStateFlow<GepyUser?>(loadSavedSession())
    val currentUser: StateFlow<GepyUser?> = _currentUser.asStateFlow()

    val isSignedIn: Boolean
        get() = _currentUser.value != null

    /** Memuat sesi pengguna yang tersimpan saat aplikasi dibuka kembali. */
    private fun loadSavedSession(): GepyUser? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_USER_EMAIL, null) ?: return null
        val name = prefs.getString(KEY_USER_NAME, "") ?: ""
        val avatar = prefs.getString(KEY_USER_AVATAR, "") ?: ""
        val token = prefs.getString(KEY_SESSION_TOKEN, "") ?: ""
        return GepyUser(
            id = userId,
            email = email,
            displayName = name,
            avatarUrl = avatar,
            sessionToken = token
        )
    }

    /**
     * Memulai alur masuk One-Tap Google Credential Manager.
     * Wajib dipanggil dari konteks Activity agar dialog One-Tap dapat ditampilkan.
     */
    suspend fun signIn(activityContext: Context): GoogleAuthResult {
        if (webClientId.isBlank()) {
            GepyLog.w(TAG, "Google Web Client ID belum dikonfigurasi; arahkan ke mode BYOK.")
            return GoogleAuthResult.Failure(
                "Google Web Client ID belum dikonfigurasi. Gunakan mode BYOK atau isi GOOGLE_WEB_CLIENT_ID di local_secrets.json."
            )
        }
        GepyLog.i(TAG, "Memulai alur Google One-Tap Sign-In via Credential Manager")
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                context = activityContext,
                request = request
            )

            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                val user = GepyUser(
                    id = googleIdTokenCredential.id,
                    email = googleIdTokenCredential.id,
                    displayName = googleIdTokenCredential.displayName ?: googleIdTokenCredential.id.substringBefore("@"),
                    avatarUrl = googleIdTokenCredential.profilePictureUri?.toString().orEmpty()
                )

                GepyLog.i(TAG, "Google One-Tap berhasil: ${user.email} (Token len: ${idToken.length})")
                GoogleAuthResult.Success(user = user, idToken = idToken)
            } else {
                val msg = "Tipe kredensial tidak dikenali: ${credential.javaClass.simpleName}"
                GepyLog.w(TAG, msg)
                GoogleAuthResult.Failure(msg)
            }
        } catch (e: GetCredentialCancellationException) {
            GepyLog.i(TAG, "Login dibatalkan oleh pengguna.")
            GoogleAuthResult.Cancelled()
        } catch (e: NoCredentialException) {
            GepyLog.w(TAG, "Tidak ada akun kredensial yang tersedia di perangkat: ${e.message}")
            GoogleAuthResult.Failure("Tidak ada akun Google yang tersedia di perangkat ini.", e)
        } catch (e: GetCredentialException) {
            GepyLog.e(TAG, "Kesalahan Credential Manager: ${e.message}", e)
            GoogleAuthResult.Failure("Gagal masuk dengan Google: ${e.message}", e)
        } catch (t: Throwable) {
            GepyLog.e(TAG, "Kesalahan tak terduga saat autentikasi Google", t)
            GoogleAuthResult.Failure("Terjadi kesalahan sistem: ${t.localizedMessage ?: t.message}", t)
        }
    }

    /** Menyimpan sesi pengguna yang telah diverifikasi oleh backend Cloudflare Gateway. */
    fun saveSession(user: GepyUser, sessionToken: String) {
        val updatedUser = user.copy(sessionToken = sessionToken)
        prefs.edit {
            putString(KEY_USER_ID, updatedUser.id)
            putString(KEY_USER_EMAIL, updatedUser.email)
            putString(KEY_USER_NAME, updatedUser.displayName)
            putString(KEY_USER_AVATAR, updatedUser.avatarUrl)
            putString(KEY_SESSION_TOKEN, sessionToken)
        }
        _currentUser.value = updatedUser
        GepyLog.i(TAG, "Sesi login disimpan untuk pengguna: ${updatedUser.email}")
    }

    /** Menghapus sesi login di lokal dan mereset status kredensial Google Play Services. */
    suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            GepyLog.w(TAG, "Peringatan saat clearCredentialState: ${e.message}")
        }
        prefs.edit { clear() }
        _currentUser.value = null
        GepyLog.i(TAG, "Pengguna berhasil keluar (Sign Out).")
    }
}
