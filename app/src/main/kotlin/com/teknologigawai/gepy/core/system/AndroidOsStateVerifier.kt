package com.teknologigawai.gepy.core.system

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.teknologigawai.gepy.core.ports.OsQueryStatus
import com.teknologigawai.gepy.core.ports.OsStateCheckResult
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.OsVerificationBaseline
import com.teknologigawai.gepy.core.ports.OsVerifyDomain
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Spesifikasi niat tujuan yang dapat diverifikasi langsung pada state OS Android. */
data class OsGoalMatchSpec(
    val domain: OsVerifyDomain,
    val keywords: List<String> = emptyList(),
    val targetHour: Int? = null,
    val targetMinute: Int? = null
)

/** Cuplikan baris hasil kueri `CalendarContract.Events` yang aman dari ketergantungan Android framework. */
data class CalendarEventRowSnapshot(
    val eventId: Long,
    val title: String,
    val dtStartMs: Long
)

/** Cuplikan baris hasil kueri `ContactsContract.Contacts`. */
data class ContactRowSnapshot(
    val contactId: Long,
    val displayName: String,
    val lastUpdatedMs: Long
)

/** Cuplikan baris hasil kueri `MediaStore.Downloads`. */
data class DownloadRowSnapshot(
    val downloadId: Long,
    val displayName: String,
    val dateAddedSec: Long,
    val isPending: Int,
    val sizeBytes: Long
)

/**
 * Implementasi **Programmatic OS-State Verifiers (DeepMind AndroidWorld)** berbasis
 * `ContentResolver` & `AlarmManager` (`0` token LLM):
 * - [`CalendarContract.Events.CONTENT_URI`](https://developer.android.com/reference/android/provider/CalendarContract.Events)
 * - [`AlarmManager.getNextAlarmClock`](https://developer.android.com/reference/android/app/AlarmManager#getNextAlarmClock())
 * - [`ContactsContract.Contacts.CONTENT_URI`](https://developer.android.com/reference/android/provider/ContactsContract.Contacts)
 * - [`MediaStore.Downloads.EXTERNAL_CONTENT_URI`](https://developer.android.com/reference/android/provider/MediaStore.Downloads)
 *
 * Aman saat izin ditolak atau Scoped Storage membatasi visibilitas (degradasi bersih ke `UNAVAILABLE_OR_DENIED`),
 * dan memisahkan kalkulus evaluasi murni agar 100% dapat diuji deterministik di JVM.
 */
class AndroidOsStateVerifier(
    private val context: Context? = null,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) : OsStateVerifierPort {

    override fun captureBaseline(): OsVerificationBaseline {
        val nowMs = nowMsProvider()
        val ctx = context ?: return OsVerificationBaseline(capturedAtMs = nowMs)

        val calGranted = hasPermission(ctx, Manifest.permission.READ_CALENDAR)
        val maxEventId = if (calGranted) queryMaxCalendarEventId(ctx) else 0L

        val alarmTrigger = queryNextAlarmTriggerMs(ctx)

        val contactGranted = hasPermission(ctx, Manifest.permission.READ_CONTACTS)
        val maxContactUpdatedMs = if (contactGranted) queryLatestContactUpdatedMs(ctx) else 0L

        val (maxDlId, maxDlDateSec, visibleDlCount) = queryDownloadBaseline(ctx)

        return OsVerificationBaseline(
            capturedAtMs = nowMs,
            maxCalendarEventId = maxEventId,
            calendarQueryPermitted = calGranted,
            baselineNextAlarmTriggerMs = alarmTrigger,
            latestContactUpdatedMs = maxContactUpdatedMs,
            contactQueryPermitted = contactGranted,
            latestDownloadId = maxDlId,
            latestDownloadDateSec = maxDlDateSec,
            visibleDownloadsCountAtStart = visibleDlCount
        )
    }

    override fun verifyGoalOsState(
        userGoal: String,
        baseline: OsVerificationBaseline?
    ): OsStateCheckResult? {
        val spec = parseGoalSpec(userGoal) ?: return null
        val ctx = context ?: return OsStateCheckResult(
            domain = spec.domain,
            status = OsQueryStatus.UNAVAILABLE_OR_DENIED
        )
        val effectiveBaseline = baseline ?: OsVerificationBaseline(capturedAtMs = nowMsProvider())

        return when (spec.domain) {
            OsVerifyDomain.CALENDAR_EVENT -> verifyCalendarState(ctx, spec, effectiveBaseline)
            OsVerifyDomain.ALARM -> verifyAlarmState(ctx, spec, effectiveBaseline)
            OsVerifyDomain.CONTACT -> verifyContactState(ctx, spec, effectiveBaseline)
            OsVerifyDomain.DOWNLOAD -> verifyDownloadState(ctx, spec, effectiveBaseline)
        }
    }

    private fun verifyCalendarState(
        ctx: Context,
        spec: OsGoalMatchSpec,
        baseline: OsVerificationBaseline
    ): OsStateCheckResult {
        if (!hasPermission(ctx, Manifest.permission.READ_CALENDAR)) {
            return OsStateCheckResult(
                domain = OsVerifyDomain.CALENDAR_EVENT,
                status = OsQueryStatus.UNAVAILABLE_OR_DENIED
            )
        }
        val rows = queryRecentCalendarEvents(ctx, baseline.maxCalendarEventId)
            ?: return OsStateCheckResult(
                domain = OsVerifyDomain.CALENDAR_EVENT,
                status = OsQueryStatus.UNAVAILABLE_OR_DENIED
            )
        return evaluateCalendarRows(spec, baseline, rows)
    }

    private fun verifyAlarmState(
        ctx: Context,
        spec: OsGoalMatchSpec,
        baseline: OsVerificationBaseline
    ): OsStateCheckResult {
        val nextTrigger = queryNextAlarmTriggerMs(ctx)
        return evaluateAlarmClock(
            spec = spec,
            baseline = baseline,
            currentNextAlarmTriggerMs = nextTrigger,
            nowMs = nowMsProvider()
        )
    }

    private fun verifyContactState(
        ctx: Context,
        spec: OsGoalMatchSpec,
        baseline: OsVerificationBaseline
    ): OsStateCheckResult {
        if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
            return OsStateCheckResult(
                domain = OsVerifyDomain.CONTACT,
                status = OsQueryStatus.UNAVAILABLE_OR_DENIED
            )
        }
        val rows = queryRecentUpdatedContacts(ctx, baseline.latestContactUpdatedMs)
            ?: return OsStateCheckResult(
                domain = OsVerifyDomain.CONTACT,
                status = OsQueryStatus.UNAVAILABLE_OR_DENIED
            )
        return evaluateContactRows(spec, baseline, rows)
    }

    private fun verifyDownloadState(
        ctx: Context,
        spec: OsGoalMatchSpec,
        baseline: OsVerificationBaseline
    ): OsStateCheckResult {
        val queryResult = queryRecentDownloads(ctx, baseline)
            ?: return OsStateCheckResult(
                domain = OsVerifyDomain.DOWNLOAD,
                status = OsQueryStatus.UNAVAILABLE_OR_DENIED
            )
        return evaluateDownloadRows(
            spec = spec,
            baseline = baseline,
            recentRows = queryResult.first,
            totalVisibleCountNow = queryResult.second
        )
    }

    private fun hasPermission(ctx: Context, permission: String): Boolean {
        return runCatching {
            ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun queryMaxCalendarEventId(ctx: Context): Long {
        return try {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.DELETED} = 0",
                null,
                "${CalendarContract.Events._ID} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                if (idIdx >= 0 && cursor.moveToFirst()) cursor.getLong(idIdx) else 0L
            } ?: 0L
        } catch (_: SecurityException) {
            0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun queryRecentCalendarEvents(
        ctx: Context,
        minExclusiveEventId: Long
    ): List<CalendarEventRowSnapshot>? {
        return try {
            val rows = mutableListOf<CalendarEventRowSnapshot>()
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART
                ),
                "${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events._ID} > ?",
                arrayOf(minExclusiveEventId.toString()),
                "${CalendarContract.Events._ID} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext() && rows.size < 25) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty().trim() else ""
                    val dtStart = if (startIdx >= 0) cursor.getLong(startIdx) else 0L
                    rows.add(CalendarEventRowSnapshot(eventId = id, title = title, dtStartMs = dtStart))
                }
            }
            rows
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryNextAlarmTriggerMs(ctx: Context): Long? {
        return try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return null
            am.nextAlarmClock?.triggerTime
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryLatestContactUpdatedMs(ctx: Context): Long {
        return try {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                null,
                null,
                "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC"
            )?.use { cursor ->
                val tsIdx = cursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                if (tsIdx >= 0 && cursor.moveToFirst()) cursor.getLong(tsIdx) else 0L
            } ?: 0L
        } catch (_: SecurityException) {
            0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun queryRecentUpdatedContacts(
        ctx: Context,
        minExclusiveUpdatedMs: Long
    ): List<ContactRowSnapshot>? {
        return try {
            val rows = mutableListOf<ContactRowSnapshot>()
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
                ),
                "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?",
                arrayOf(minExclusiveUpdatedMs.toString()),
                "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val tsIdx = cursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                while (cursor.moveToNext() && rows.size < 25) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val updatedMs = if (tsIdx >= 0) cursor.getLong(tsIdx) else 0L
                    rows.add(ContactRowSnapshot(contactId = id, displayName = name, lastUpdatedMs = updatedMs))
                }
            }
            rows
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryDownloadBaseline(ctx: Context): Triple<Long, Long, Int> {
        return try {
            var maxId = 0L
            var maxDateSec = 0L
            var totalVisible = 0
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATE_ADDED
                ),
                null,
                null,
                "${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                totalVisible = cursor.count
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                if (cursor.moveToFirst()) {
                    if (idIdx >= 0) maxId = cursor.getLong(idIdx)
                    if (dateIdx >= 0) maxDateSec = cursor.getLong(dateIdx)
                }
            }
            Triple(maxId, maxDateSec, totalVisible)
        } catch (_: SecurityException) {
            Triple(0L, 0L, 0)
        } catch (_: Throwable) {
            Triple(0L, 0L, 0)
        }
    }

    private fun queryRecentDownloads(
        ctx: Context,
        baseline: OsVerificationBaseline
    ): Pair<List<DownloadRowSnapshot>, Int>? {
        return try {
            val minDateSec = (baseline.capturedAtMs / 1000L) - 5L
            val selection = "${MediaStore.MediaColumns._ID} > ? OR ${MediaStore.MediaColumns.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf(baseline.latestDownloadId.toString(), minDateSec.toString())
            val rows = mutableListOf<DownloadRowSnapshot>()
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.SIZE
                ),
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val pendingIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext() && rows.size < 20) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val dateSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val pending = if (pendingIdx >= 0) cursor.getInt(pendingIdx) else 0
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    rows.add(
                        DownloadRowSnapshot(
                            downloadId = id,
                            displayName = name,
                            dateAddedSec = dateSec,
                            isPending = pending,
                            sizeBytes = size
                        )
                    )
                }
            }

            // Cek total baris yang terlihat oleh paket ini untuk membedakan Scoped Storage isolation
            val totalVisibleNow = if (rows.isNotEmpty()) {
                baseline.visibleDownloadsCountAtStart + rows.size
            } else {
                queryDownloadBaseline(ctx).third
            }
            rows to totalVisibleNow
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private val readOnlyRegex = Regex(
            """^\s*(?:cek|lihat|tampilkan|baca|bacakan|daftar|list|apa|kapan|show|check)\b""",
            RegexOption.IGNORE_CASE
        )
        private val destructiveOrDisableRegex = Regex(
            """^\s*(?:hapus|delete|matikan|nonaktifkan|batalkan|cancel|turn\s+off|remove)\b""",
            RegexOption.IGNORE_CASE
        )

        private val calendarWriteVerbRegex = Regex(
            """\b(?:buat|buatkan|tambah|tambahkan|bikin|bikinkan|simpan|jadwalkan|atur|setel|masukkan|catat|schedule|create|add|save)\b""",
            RegexOption.IGNORE_CASE
        )
        private val calendarNounRegex = Regex(
            """\b(?:acara|jadwal|agenda|rapat|meeting|event|kalender|calendar|janji|pengingat\s+kalender)\b""",
            RegexOption.IGNORE_CASE
        )

        private val alarmWriteVerbRegex = Regex(
            """\b(?:pasang|pasangkan|set|setel|atur|buat|buatkan|bikin|bikinkan|aktifkan|nyalakan|tambah|tambahin|bangunin|bangunkan)\b""",
            RegexOption.IGNORE_CASE
        )
        private val alarmNounRegex = Regex("""\balarm\b""", RegexOption.IGNORE_CASE)

        private val contactWriteVerbRegex = Regex(
            """\b(?:simpan|simpankan|tambah|tambahkan|buat|buatkan|bikin|catat|masukkan|save|add|create)\b""",
            RegexOption.IGNORE_CASE
        )
        private val contactNounRegex = Regex(
            """\b(?:kontak|contact|nomor\s+baru|nomer\s+baru)\b""",
            RegexOption.IGNORE_CASE
        )
        private val savePhoneNumberRegex = Regex(
            """\b(?:simpan|tambah|catat)\s+(?:nomor|nomer)\b""",
            RegexOption.IGNORE_CASE
        )

        private val downloadWriteVerbRegex = Regex(
            """\b(?:unduh|unduhkan|download|downloadkan|simpan\s+(?:file|berkas|dokumen|pdf|zip|apk|laporan|materi)|save\s+(?:file|pdf|document|attachment))\b""",
            RegexOption.IGNORE_CASE
        )

        private val hourMinuteRegex = Regex(
            """\b(?:jam|pukul)?\s*(\d{1,2})(?:[:.](\d{2}))?\s*(pagi|siang|sore|malam|am|pm)?\b""",
            RegexOption.IGNORE_CASE
        )

        private val stopWords = setOf(
            "tolong", "gep", "gepy", "buka", "aplikasi", "app", "di", "ke", "pada", "untuk", "yang",
            "dan", "atau", "dengan", "jam", "pukul", "besok", "hari", "ini", "lusa", "pagi", "siang",
            "sore", "malam", "buat", "buatkan", "tambah", "tambahkan", "bikin", "bikinkan", "simpan",
            "simpankan", "jadwalkan", "atur", "setel", "masukkan", "catat", "pasang", "pasangkan",
            "kalender", "calendar", "jadwal", "acara", "agenda", "event", "kontak", "contact", "baru",
            "nomor", "nomer", "alarm", "unduh", "unduhkan", "download", "downloadkan", "file", "berkas",
            "save", "create", "add", "schedule", "set", "the", "a", "an", "to", "in", "on", "for"
        )

        private val nonWordSplitRegex = Regex("""[^a-z0-9._-]+""")

        /** Mem-parsing tujuan pengguna menjadi spesifikasi domain OS yang perlu diaudit pasca-eksekusi. */
        fun parseGoalSpec(userGoal: String): OsGoalMatchSpec? {
            val clean = userGoal.trim()
            if (clean.isEmpty()) return null
            val lower = clean.lowercase(Locale.ROOT)

            // Jangan ganggu instruksi baca-saja atau hapus/matikan
            if (readOnlyRegex.containsMatchIn(lower) || destructiveOrDisableRegex.containsMatchIn(lower)) {
                return null
            }

            // 1. Kontak (prioritaskan bila ada "simpan kontak" / "simpan nomor")
            if (contactWriteVerbRegex.containsMatchIn(lower) &&
                (contactNounRegex.containsMatchIn(lower) || savePhoneNumberRegex.containsMatchIn(lower))
            ) {
                return OsGoalMatchSpec(
                    domain = OsVerifyDomain.CONTACT,
                    keywords = extractMeaningfulKeywords(lower, includeNumbers = false)
                )
            }

            // 2. Kalender / Acara
            if (calendarWriteVerbRegex.containsMatchIn(lower) && calendarNounRegex.containsMatchIn(lower)) {
                val parsedTime = extractTargetHourMinute(lower)
                return OsGoalMatchSpec(
                    domain = OsVerifyDomain.CALENDAR_EVENT,
                    keywords = extractMeaningfulKeywords(lower, includeNumbers = false),
                    targetHour = parsedTime?.first,
                    targetMinute = parsedTime?.second
                )
            }

            // 3. Alarm
            if (alarmNounRegex.containsMatchIn(lower) &&
                (alarmWriteVerbRegex.containsMatchIn(lower) || lower.startsWith("alarm "))
            ) {
                val parsedTime = extractTargetHourMinute(lower)
                return OsGoalMatchSpec(
                    domain = OsVerifyDomain.ALARM,
                    keywords = extractMeaningfulKeywords(lower, includeNumbers = false),
                    targetHour = parsedTime?.first,
                    targetMinute = parsedTime?.second
                )
            }

            // 4. Unduhan / MediaStore.Downloads
            if (downloadWriteVerbRegex.containsMatchIn(lower)) {
                return OsGoalMatchSpec(
                    domain = OsVerifyDomain.DOWNLOAD,
                    keywords = extractMeaningfulKeywords(lower, includeNumbers = true)
                )
            }

            return null
        }

        internal fun extractTargetHourMinute(lowerGoal: String): Pair<Int, Int>? {
            val matches = hourMinuteRegex.findAll(lowerGoal)
            for (m in matches) {
                val full = m.value
                val rawHour = m.groupValues[1].toIntOrNull() ?: continue
                val rawMin = m.groupValues[2].toIntOrNull() ?: 0
                val meridiem = m.groupValues[3].lowercase(Locale.ROOT)
                val hasTimeCue = full.contains("jam") ||
                    full.contains("pukul") ||
                    full.contains(":") ||
                    full.contains(".") ||
                    meridiem.isNotEmpty()
                if (!hasTimeCue) continue
                if (rawHour !in 0..24 || rawMin !in 0..59) continue

                var normalizedHour = rawHour
                if (meridiem == "pm" || meridiem == "sore" || (meridiem == "malam" && rawHour in 1..11)) {
                    if (normalizedHour in 1..11) normalizedHour += 12
                } else if (meridiem == "siang" && rawHour in 1..4) {
                    normalizedHour += 12
                } else if ((meridiem == "am" || meridiem == "pagi") && normalizedHour == 12) {
                    normalizedHour = 0
                }
                if (normalizedHour == 24) normalizedHour = 0
                return normalizedHour to rawMin
            }
            return null
        }

        internal fun extractMeaningfulKeywords(lowerGoal: String, includeNumbers: Boolean): List<String> {
            return lowerGoal
                .split(nonWordSplitRegex)
                .map { it.trim('.', '-', '_') }
                .filter { tok ->
                    tok.length >= 3 &&
                        tok !in stopWords &&
                        (includeNumbers || tok.any { it.isLetter() })
                }
                .distinct()
                .take(5)
        }

        /** Mengevaluasi baris baru dari `CalendarContract.Events` secara murni (`0` token). */
        fun evaluateCalendarRows(
            spec: OsGoalMatchSpec,
            baseline: OsVerificationBaseline,
            newRows: List<CalendarEventRowSnapshot>
        ): OsStateCheckResult {
            val freshRows = newRows.filter { it.eventId > baseline.maxCalendarEventId }
            if (freshRows.isEmpty()) {
                return OsStateCheckResult(
                    domain = OsVerifyDomain.CALENDAR_EVENT,
                    status = OsQueryStatus.NOT_FOUND,
                    missingReason = "Bukti OS (CalendarContract.Events): belum ada acara baru yang tersimpan setelah langkah dimulai. Pastikan semua kolom diisi dan tombol Simpan/Save diketuk dengan sukses."
                )
            }

            val keywordMatch = if (spec.keywords.isEmpty()) {
                freshRows.first()
            } else {
                freshRows.firstOrNull { row ->
                    val titleLower = row.title.lowercase(Locale.ROOT)
                    spec.keywords.any { kw -> titleLower.contains(kw) }
                } ?: freshRows.firstOrNull { it.title.isNotBlank() } ?: freshRows.first()
            }

            val labelDesc = keywordMatch.title.ifBlank { "id=${keywordMatch.eventId}" }
            return OsStateCheckResult(
                domain = OsVerifyDomain.CALENDAR_EVENT,
                status = OsQueryStatus.MATCH_CONFIRMED,
                evidenceSummary = "Terbukti di CalendarContract.Events: acara \"$labelDesc\" (id=${keywordMatch.eventId}) sudah tersimpan."
            )
        }

        /** Mengevaluasi state `AlarmManager.getNextAlarmClock()` secara murni (`0` token). */
        fun evaluateAlarmClock(
            spec: OsGoalMatchSpec,
            baseline: OsVerificationBaseline,
            currentNextAlarmTriggerMs: Long?,
            nowMs: Long = System.currentTimeMillis()
        ): OsStateCheckResult {
            if (currentNextAlarmTriggerMs == null) {
                return OsStateCheckResult(
                    domain = OsVerifyDomain.ALARM,
                    status = OsQueryStatus.NOT_FOUND,
                    missingReason = "Bukti OS (AlarmManager): belum ada alarm aktif yang terjadwal di sistem. Pastikan alarm telah disimpan dan sakelarnya aktif."
                )
            }

            val triggerChanged = currentNextAlarmTriggerMs != baseline.baselineNextAlarmTriggerMs
            if (triggerChanged && currentNextAlarmTriggerMs > nowMs - 60_000L) {
                val cal = Calendar.getInstance().apply { timeInMillis = currentNextAlarmTriggerMs }
                val actualHour = cal.get(Calendar.HOUR_OF_DAY)
                val actualMin = cal.get(Calendar.MINUTE)
                val timeStr = String.format(Locale.US, "%02d:%02d", actualHour, actualMin)
                return OsStateCheckResult(
                    domain = OsVerifyDomain.ALARM,
                    status = OsQueryStatus.MATCH_CONFIRMED,
                    evidenceSummary = "Terbukti di AlarmManager OS: alarm berikutnya aktif terjadwal pukul $timeStr."
                )
            }

            // Jika waktu target disebutkan dan cocok dengan nextAlarmClock yang sama (alarm diperbarui ulang)
            if (spec.targetHour != null && spec.targetMinute != null) {
                val cal = Calendar.getInstance().apply { timeInMillis = currentNextAlarmTriggerMs }
                val actualHour = cal.get(Calendar.HOUR_OF_DAY)
                val actualMin = cal.get(Calendar.MINUTE)
                val diffMin = abs((actualHour * 60 + actualMin) - (spec.targetHour * 60 + spec.targetMinute))
                if (diffMin <= 2) {
                    val timeStr = String.format(Locale.US, "%02d:%02d", actualHour, actualMin)
                    return OsStateCheckResult(
                        domain = OsVerifyDomain.ALARM,
                        status = OsQueryStatus.MATCH_CONFIRMED,
                        evidenceSummary = "Terbukti di AlarmManager OS: alarm pukul $timeStr aktif terjadwal."
                    )
                }

                // Kasus batas: pengguna sudah punya alarm lebih pagi (mis. 05:00) dan baru menambah alarm 07:30.
                // AlarmManager.getNextAlarmClock() hanya mengembalikan 1 alarm terdekat -> serahkan ke verifikasi UI/LLM.
                return OsStateCheckResult(
                    domain = OsVerifyDomain.ALARM,
                    status = OsQueryStatus.UNAVAILABLE_OR_DENIED
                )
            }

            // Tidak berubah dan tidak ada target jam spesifik -> tidak bisa memastikan hanya dari 1 nextAlarmClock
            return OsStateCheckResult(
                domain = OsVerifyDomain.ALARM,
                status = OsQueryStatus.UNAVAILABLE_OR_DENIED
            )
        }

        /** Mengevaluasi baris baru/diperbarui pada `ContactsContract.Contacts`. */
        fun evaluateContactRows(
            spec: OsGoalMatchSpec,
            baseline: OsVerificationBaseline,
            updatedRows: List<ContactRowSnapshot>
        ): OsStateCheckResult {
            val freshRows = updatedRows.filter { it.lastUpdatedMs > baseline.latestContactUpdatedMs }
            if (freshRows.isEmpty()) {
                return OsStateCheckResult(
                    domain = OsVerifyDomain.CONTACT,
                    status = OsQueryStatus.NOT_FOUND,
                    missingReason = "Bukti OS (ContactsContract): belum ada kontak baru atau pembaruan kontak yang tercatat setelah langkah dimulai. Pastikan tombol Simpan kontak sudah diketuk."
                )
            }

            val matched = if (spec.keywords.isEmpty()) {
                freshRows.first()
            } else {
                freshRows.firstOrNull { row ->
                    val nameLower = row.displayName.lowercase(Locale.ROOT)
                    spec.keywords.any { kw -> nameLower.contains(kw) }
                }
            }

            if (matched != null) {
                val label = matched.displayName.ifBlank { "id=${matched.contactId}" }
                return OsStateCheckResult(
                    domain = OsVerifyDomain.CONTACT,
                    status = OsQueryStatus.MATCH_CONFIRMED,
                    evidenceSummary = "Terbukti di ContactsContract: kontak \"$label\" sudah tersimpan di buku telepon."
                )
            }

            // Ada kontak baru tersimpan di jendela waktu ini walaupun ejaan nama sedikit berbeda
            val fallbackRow = freshRows.first()
            return OsStateCheckResult(
                domain = OsVerifyDomain.CONTACT,
                status = OsQueryStatus.MATCH_CONFIRMED,
                evidenceSummary = "Terbukti di ContactsContract: pembaruan kontak \"${fallbackRow.displayName}\" baru saja tercatat."
            )
        }

        /**
         * Mengevaluasi hasil kueri `MediaStore.Downloads`.
         * Menangani deteksi unduhan yang masih berjalan (`IS_PENDING == 1`) maupun keterbatasan Scoped Storage.
         */
        fun evaluateDownloadRows(
            spec: OsGoalMatchSpec,
            baseline: OsVerificationBaseline,
            recentRows: List<DownloadRowSnapshot>,
            totalVisibleCountNow: Int
        ): OsStateCheckResult {
            val minSec = (baseline.capturedAtMs / 1000L) - 5L
            val candidates = recentRows.filter {
                it.downloadId > baseline.latestDownloadId || it.dateAddedSec >= minSec
            }

            val stillPending = candidates.firstOrNull { it.isPending == 1 }
            if (stillPending != null) {
                val fileLabel = stillPending.displayName.ifBlank { "berkas #${stillPending.downloadId}" }
                return OsStateCheckResult(
                    domain = OsVerifyDomain.DOWNLOAD,
                    status = OsQueryStatus.PENDING_IN_PROGRESS,
                    missingReason = "Bukti OS (MediaStore.Downloads): file \"$fileLabel\" masih berstatus IS_PENDING (unduhan belum rampung). Tunggu atau selesaikan pengunduhan sebelum DONE."
                )
            }

            val completed = candidates.filter { it.isPending == 0 && it.sizeBytes > 0L }
            if (completed.isNotEmpty()) {
                val bestMatch = if (spec.keywords.isEmpty()) {
                    completed.first()
                } else {
                    completed.firstOrNull { row ->
                        val nameLower = row.displayName.lowercase(Locale.ROOT)
                        spec.keywords.any { kw -> nameLower.contains(kw) }
                    } ?: completed.first()
                }
                val fileLabel = bestMatch.displayName.ifBlank { "id=${bestMatch.downloadId}" }
                return OsStateCheckResult(
                    domain = OsVerifyDomain.DOWNLOAD,
                    status = OsQueryStatus.MATCH_CONFIRMED,
                    evidenceSummary = "Terbukti di MediaStore.Downloads: \"$fileLabel\" (${bestMatch.sizeBytes} byte) selesai diunduh (`IS_PENDING=0`)."
                )
            }

            // Pada Android 10–16 Scoped Storage, unduhan milik aplikasi pihak ketiga (mis. Chrome)
            // tidak selalu diekspos ke MediaStore aplikasi lain. Jangan memblokir palsu bila visibilitas 0.
            if (totalVisibleCountNow == 0 && baseline.visibleDownloadsCountAtStart == 0) {
                return OsStateCheckResult(
                    domain = OsVerifyDomain.DOWNLOAD,
                    status = OsQueryStatus.UNAVAILABLE_OR_DENIED
                )
            }

            return OsStateCheckResult(
                domain = OsVerifyDomain.DOWNLOAD,
                status = OsQueryStatus.NOT_FOUND,
                missingReason = "Bukti OS (MediaStore.Downloads): belum terdeteksi berkas baru yang selesai diunduh. Pastikan tombol Download/Unduh sudah diketuk dan proses pengunduhan selesai."
            )
        }
    }
}
