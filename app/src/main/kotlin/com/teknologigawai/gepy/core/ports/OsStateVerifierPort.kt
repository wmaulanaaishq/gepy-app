package com.teknologigawai.gepy.core.ports

/** Domain status OS yang dapat diverifikasi secara programatik (`0` token LLM, konsep AndroidWorld). */
enum class OsVerifyDomain(val labelId: String) {
    CALENDAR_EVENT("CalendarContract.Events"),
    ALARM("AlarmManager"),
    CONTACT("ContactsContract"),
    DOWNLOAD("MediaStore.Downloads")
}

/** Hasil kueri objektif terhadap penyedia OS Android. */
enum class OsQueryStatus {
    /** Perubahan status OS terbukti nyata cocok dengan tujuan pengguna. */
    MATCH_CONFIRMED,

    /** Objek terdeteksi ada di OS namun masih berstatus belum selesai (mis. `IS_PENDING == 1`). */
    PENDING_IN_PROGRESS,

    /** Izin kueri tersedia & kueri berhasil, namun objek target belum tercatat di OS. */
    NOT_FOUND,

    /** Izin tidak diberikan, Scoped Storage membatasi visibilitas, atau konteks OS ambigu -> serahkan ke UI/LLM Critic. */
    UNAVAILABLE_OR_DENIED
}

/** Snapshot baseline OS sebelum loop eksekusi aksi dimulai untuk mencegah klaim sukses palsu dari data lama. */
data class OsVerificationBaseline(
    val capturedAtMs: Long = System.currentTimeMillis(),
    val maxCalendarEventId: Long = 0L,
    val calendarQueryPermitted: Boolean = false,
    val baselineNextAlarmTriggerMs: Long? = null,
    val latestContactUpdatedMs: Long = 0L,
    val contactQueryPermitted: Boolean = false,
    val latestDownloadId: Long = 0L,
    val latestDownloadDateSec: Long = 0L,
    val visibleDownloadsCountAtStart: Int = 0
)

/** Ringkasan hasil evaluasi verifikator OS terhadap tujuan pengguna. */
data class OsStateCheckResult(
    val domain: OsVerifyDomain,
    val status: OsQueryStatus,
    val evidenceSummary: String = "",
    val missingReason: String = ""
)

/**
 * Port Hexagonal untuk **Programmatic OS-State Verifiers** (konsep DeepMind AndroidWorld):
 * memberi Gepy bukti objektif langsung dari `ContentResolver` & `AlarmManager` Android (`0` token LLM)
 * sebelum melaporkan keberhasilan ke pengguna.
 */
interface OsStateVerifierPort {
    /** Menangkap snapshot state OS sebelum aksi UI dijalankan. */
    fun captureBaseline(): OsVerificationBaseline

    /**
     * Memverifikasi apakah tujuan [userGoal] termasuk domain OS yang dapat diaudit secara programatik.
     * Mengembalikan `null` apabila tujuan tidak menargetkan penambahan/penyimpanan state OS
     * (atau merupakan perintah baca-saja / hapus).
     */
    fun verifyGoalOsState(
        userGoal: String,
        baseline: OsVerificationBaseline? = null
    ): OsStateCheckResult?
}
