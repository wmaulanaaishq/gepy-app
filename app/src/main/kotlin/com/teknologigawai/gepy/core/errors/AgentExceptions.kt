package com.teknologigawai.gepy.core.errors

/**
 * Kelas dasar untuk seluruh pengecualian operasional agen Gepy (`com.teknologigawai.gepy`).
 */
sealed class AgentException(
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Pengecualian ketika layar dilindungi atribut `FLAG_SECURE` sehingga pohon
 * elemen kosong dan tangkapan layar diblokir oleh OS Android.
 *
 * Saat pengecualian ini dilempar, orkestrator wajib menghentikan otomatisasi
 * dan melakukan *User Handover* (meminta pengguna menyelesaikan layar manual).
 */
class FlagSecureException(
    val packageName: String = "",
    message: String = "Layar dilindungi keamanan sistem (FLAG_SECURE). Silakan selesaikan langkah ini secara manual."
) : AgentException(message)

/**
 * Pengecualian ketika agen terdeteksi mengulangi aksi identik tanpa perubahan
 * layar melebihi ambang batas keamanan (*Infinite Loop*).
 */
class LoopDetectedException(
    val repeatedAction: String,
    message: String = "Terdeteksi perulangan aksi tanpa perubahan layar. Menghentikan langkah berulang."
) : AgentException(message)

/**
 * Pengecualian ketika perintah mencoba mengotomatiskan paket aplikasi terlarang
 * seperti *mobile banking* atau menyentuh kolom kredensial rahasia.
 */
class ForbiddenPackageException(
    val targetIdentifier: String,
    message: String = "Demi keamanan, Gepy tidak diizinkan mengotomatiskan aplikasi perbankan atau kolom kredensial rahasia."
) : AgentException(message)

/**
 * Pengecualian ketika penyedia LLM utama maupun cadangan gagal mengembalikan
 * keputusan JSON yang valid.
 */
class LlmInferenceException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : AgentException(message, cause)
