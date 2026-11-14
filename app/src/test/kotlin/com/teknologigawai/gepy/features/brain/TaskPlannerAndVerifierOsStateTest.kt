package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.BrainPort
import com.teknologigawai.gepy.core.ports.GoalVerificationVerdict
import com.teknologigawai.gepy.core.ports.OsQueryStatus
import com.teknologigawai.gepy.core.ports.OsStateCheckResult
import com.teknologigawai.gepy.core.ports.OsStateVerifierPort
import com.teknologigawai.gepy.core.ports.OsVerificationBaseline
import com.teknologigawai.gepy.core.ports.OsVerifyDomain
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.core.ports.StepRecord
import com.teknologigawai.gepy.core.system.AndroidOsStateVerifier
import com.teknologigawai.gepy.core.system.CalendarEventRowSnapshot
import com.teknologigawai.gepy.core.system.ContactRowSnapshot
import com.teknologigawai.gepy.core.system.DownloadRowSnapshot
import com.teknologigawai.gepy.core.system.OsGoalMatchSpec
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import java.util.Calendar
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlannerAndVerifierOsStateTest {

    private class CountingSpyBrain(
        private val criticVerdict: GoalVerificationVerdict = GoalVerificationVerdict(achieved = true)
    ) : BrainPort {
        var criticVerifyCallCount: Int = 0

        override suspend fun decideNextAction(
            userGoal: String,
            currentPackage: String,
            screenElements: List<PrunedUiElement>,
            memory: ShortTermMemorySnapshot,
            injectedDomainKnowledge: String?
        ): ReActActionModel = throw UnsupportedOperationException()

        override suspend fun verifyGoalCompletion(
            userGoal: String,
            currentPackage: String,
            screenElements: List<PrunedUiElement>,
            memory: ShortTermMemorySnapshot
        ): GoalVerificationVerdict {
            criticVerifyCallCount++
            return criticVerdict
        }
    }

    private class FakeOsVerifier(
        private val resultToReturn: OsStateCheckResult?
    ) : OsStateVerifierPort {
        var verifyCalls: Int = 0

        override fun captureBaseline(): OsVerificationBaseline = OsVerificationBaseline(capturedAtMs = 1_000L)

        override fun verifyGoalOsState(
            userGoal: String,
            baseline: OsVerificationBaseline?
        ): OsStateCheckResult? {
            verifyCalls++
            return resultToReturn
        }
    }

    @Test
    fun `parseGoalSpec mengidentifikasi perintah tulis Kalender Alarm Kontak Download dan mengabaikan perintah baca atau hapus`() {
        val calSpec = AndroidOsStateVerifier.parseGoalSpec("Buatkan acara rapat proyek besok jam 10 pagi di Kalender")
        assertNotNull(calSpec)
        assertEquals(OsVerifyDomain.CALENDAR_EVENT, calSpec!!.domain)
        assertEquals(10, calSpec.targetHour)
        assertEquals(0, calSpec.targetMinute)
        assertTrue(calSpec.keywords.contains("rapat"))
        assertTrue(calSpec.keywords.contains("proyek"))

        val alarmSpec = AndroidOsStateVerifier.parseGoalSpec("Pasang alarm jam 5 sore untuk sholat")
        assertNotNull(alarmSpec)
        assertEquals(OsVerifyDomain.ALARM, alarmSpec!!.domain)
        assertEquals(17, alarmSpec.targetHour)
        assertEquals(0, alarmSpec.targetMinute)

        val contactSpec = AndroidOsStateVerifier.parseGoalSpec("Simpan kontak Budi Santoso nomor baru 08123456789")
        assertNotNull(contactSpec)
        assertEquals(OsVerifyDomain.CONTACT, contactSpec!!.domain)
        assertTrue(contactSpec.keywords.contains("budi"))
        assertTrue(contactSpec.keywords.contains("santoso"))

        val dlSpec = AndroidOsStateVerifier.parseGoalSpec("Unduh laporan_keuangan.pdf dari portal")
        assertNotNull(dlSpec)
        assertEquals(OsVerifyDomain.DOWNLOAD, dlSpec!!.domain)
        assertTrue(dlSpec.keywords.contains("laporan_keuangan.pdf"))

        assertNull(AndroidOsStateVerifier.parseGoalSpec("Cek jadwal rapat hari ini"))
        assertNull(AndroidOsStateVerifier.parseGoalSpec("Lihat daftar kontak keluarga"))
        assertNull(AndroidOsStateVerifier.parseGoalSpec("Hapus alarm jam 6 pagi"))
        assertNull(AndroidOsStateVerifier.parseGoalSpec("Matikan alarm sekarang"))
        assertNull(AndroidOsStateVerifier.parseGoalSpec("Kirim pesan WA ke Budi bilang halo"))
    }

    @Test
    fun `evaluateCalendarRows mendeteksi acara baru atau menolak bila masih kosong setelah baseline`() {
        val spec = OsGoalMatchSpec(
            domain = OsVerifyDomain.CALENDAR_EVENT,
            keywords = listOf("rapat", "tim")
        )
        val baseline = OsVerificationBaseline(maxCalendarEventId = 40L, calendarQueryPermitted = true)

        val emptyResult = AndroidOsStateVerifier.evaluateCalendarRows(
            spec = spec,
            baseline = baseline,
            newRows = listOf(CalendarEventRowSnapshot(eventId = 40L, title = "Acara Lama", dtStartMs = 1_000L))
        )
        assertEquals(OsQueryStatus.NOT_FOUND, emptyResult.status)
        assertTrue(emptyResult.missingReason.contains("CalendarContract.Events"))

        val matchedResult = AndroidOsStateVerifier.evaluateCalendarRows(
            spec = spec,
            baseline = baseline,
            newRows = listOf(CalendarEventRowSnapshot(eventId = 41L, title = "Rapat Tim Produk", dtStartMs = 2_000L))
        )
        assertEquals(OsQueryStatus.MATCH_CONFIRMED, matchedResult.status)
        assertTrue(matchedResult.evidenceSummary.contains("Rapat Tim Produk"))
    }

    @Test
    fun `evaluateAlarmClock memverifikasi alarm baru aktif dan aman fallback saat ada alarm lebih pagi`() {
        val nowMs = 1_700_000_000_000L
        val calEarly = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        val calTarget = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            add(Calendar.DAY_OF_YEAR, 1)
        }

        val spec = OsGoalMatchSpec(domain = OsVerifyDomain.ALARM, targetHour = 7, targetMinute = 30)

        val noAlarm = AndroidOsStateVerifier.evaluateAlarmClock(
            spec = spec,
            baseline = OsVerificationBaseline(baselineNextAlarmTriggerMs = null),
            currentNextAlarmTriggerMs = null,
            nowMs = nowMs
        )
        assertEquals(OsQueryStatus.NOT_FOUND, noAlarm.status)

        val newlyScheduled = AndroidOsStateVerifier.evaluateAlarmClock(
            spec = spec,
            baseline = OsVerificationBaseline(baselineNextAlarmTriggerMs = null),
            currentNextAlarmTriggerMs = calTarget.timeInMillis,
            nowMs = nowMs
        )
        assertEquals(OsQueryStatus.MATCH_CONFIRMED, newlyScheduled.status)
        assertTrue(newlyScheduled.evidenceSummary.contains("07:30"))

        val earlierAlarmExists = AndroidOsStateVerifier.evaluateAlarmClock(
            spec = spec,
            baseline = OsVerificationBaseline(baselineNextAlarmTriggerMs = calEarly.timeInMillis),
            currentNextAlarmTriggerMs = calEarly.timeInMillis,
            nowMs = nowMs
        )
        assertEquals(OsQueryStatus.UNAVAILABLE_OR_DENIED, earlierAlarmExists.status)
    }

    @Test
    fun `evaluateContactRows membedakan kontak belum tersimpan vs kontak baru tercatat`() {
        val spec = OsGoalMatchSpec(domain = OsVerifyDomain.CONTACT, keywords = listOf("andi"))
        val baseline = OsVerificationBaseline(latestContactUpdatedMs = 5_000L, contactQueryPermitted = true)

        val missing = AndroidOsStateVerifier.evaluateContactRows(
            spec = spec,
            baseline = baseline,
            updatedRows = listOf(ContactRowSnapshot(contactId = 1L, displayName = "Budi", lastUpdatedMs = 5_000L))
        )
        assertEquals(OsQueryStatus.NOT_FOUND, missing.status)

        val saved = AndroidOsStateVerifier.evaluateContactRows(
            spec = spec,
            baseline = baseline,
            updatedRows = listOf(ContactRowSnapshot(contactId = 9L, displayName = "Andi Wijaya", lastUpdatedMs = 8_500L))
        )
        assertEquals(OsQueryStatus.MATCH_CONFIRMED, saved.status)
        assertTrue(saved.evidenceSummary.contains("Andi Wijaya"))
    }

    @Test
    fun `evaluateDownloadRows mendeteksi IS_PENDING selesai dan aman pada Scoped Storage 0-rows`() {
        val spec = OsGoalMatchSpec(domain = OsVerifyDomain.DOWNLOAD, keywords = listOf("invoice.pdf"))
        val baseline = OsVerificationBaseline(
            capturedAtMs = 1_700_000_000_000L,
            latestDownloadId = 10L,
            visibleDownloadsCountAtStart = 3
        )

        val pending = AndroidOsStateVerifier.evaluateDownloadRows(
            spec = spec,
            baseline = baseline,
            recentRows = listOf(
                DownloadRowSnapshot(
                    downloadId = 11L,
                    displayName = "invoice.pdf",
                    dateAddedSec = 1_700_000_005L,
                    isPending = 1,
                    sizeBytes = 12_000L
                )
            ),
            totalVisibleCountNow = 4
        )
        assertEquals(OsQueryStatus.PENDING_IN_PROGRESS, pending.status)
        assertTrue(pending.missingReason.contains("IS_PENDING"))

        val completed = AndroidOsStateVerifier.evaluateDownloadRows(
            spec = spec,
            baseline = baseline,
            recentRows = listOf(
                DownloadRowSnapshot(
                    downloadId = 11L,
                    displayName = "invoice.pdf",
                    dateAddedSec = 1_700_000_010L,
                    isPending = 0,
                    sizeBytes = 42_500L
                )
            ),
            totalVisibleCountNow = 4
        )
        assertEquals(OsQueryStatus.MATCH_CONFIRMED, completed.status)
        assertTrue(completed.evidenceSummary.contains("IS_PENDING=0"))

        val scopedStorageEmpty = AndroidOsStateVerifier.evaluateDownloadRows(
            spec = spec,
            baseline = baseline.copy(visibleDownloadsCountAtStart = 0),
            recentRows = emptyList(),
            totalVisibleCountNow = 0
        )
        assertEquals(OsQueryStatus.UNAVAILABLE_OR_DENIED, scopedStorageEmpty.status)
    }

    @Test
    fun `verifyBeforeDone menerima DONE dengan 0 token LLM Critic saat OS-State terbukti dan menolak klaim palsu meski baru 1 langkah`() = runTest {
        val spyBrain = CountingSpyBrain(
            criticVerdict = GoalVerificationVerdict(achieved = false, missingReason = "LLM tidak seharusnya dipanggil")
        )
        val multiStepMemory = ShortTermMemorySnapshot(
            recentSteps = listOf(
                StepRecord(1, AgentActionType.CLICK, "Tambah Acara", "s1", "s2", ActionOutcome.CORRECT),
                StepRecord(2, AgentActionType.SET_TEXT, "Judul", "s2", "s3", ActionOutcome.CORRECT, textValue = "Rapat"),
                StepRecord(3, AgentActionType.CLICK, "Simpan", "s3", "s4", ActionOutcome.CORRECT)
            ),
            scratchpad = emptyMap()
        )
        val confirmedVerifier = FakeOsVerifier(
            OsStateCheckResult(
                domain = OsVerifyDomain.CALENDAR_EVENT,
                status = OsQueryStatus.MATCH_CONFIRMED,
                evidenceSummary = "Terbukti di CalendarContract.Events: acara \"Rapat\" (id=99) sudah tersimpan."
            )
        )

        val confirmedVerdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "Buatkan acara Rapat besok di Kalender",
            currentPackage = "com.google.android.calendar",
            screenElements = listOf(PrunedUiElement(index = 1, nodeId = "calendar_list", text = "Hari ini")),
            memory = multiStepMemory,
            brainPort = spyBrain,
            osStateVerifier = confirmedVerifier,
            osBaseline = confirmedVerifier.captureBaseline()
        )

        assertTrue(confirmedVerdict.achieved)
        assertTrue(confirmedVerdict.proofSummary.orEmpty().contains("CalendarContract.Events"))
        assertEquals(1, confirmedVerifier.verifyCalls)
        assertEquals(0, spyBrain.criticVerifyCallCount)

        // Sebaliknya, pada steps.size == 1 jika OS-State NOT_FOUND -> wajib TOLAK DONE sebelum pintasan 1-langkah
        val notFoundVerifier = FakeOsVerifier(
            OsStateCheckResult(
                domain = OsVerifyDomain.CALENDAR_EVENT,
                status = OsQueryStatus.NOT_FOUND,
                missingReason = "Bukti OS (CalendarContract.Events): belum ada acara baru yang tersimpan."
            )
        )
        val singleStepMemory = ShortTermMemorySnapshot(
            recentSteps = listOf(
                StepRecord(1, AgentActionType.CLICK, "Tambah", "s1", "s2", ActionOutcome.CORRECT)
            ),
            scratchpad = emptyMap()
        )

        val rejectedVerdict = TaskPlannerAndVerifier.verifyBeforeDone(
            userGoal = "Buatkan acara Rapat besok di Kalender",
            currentPackage = "com.google.android.calendar",
            screenElements = listOf(PrunedUiElement(index = 1, nodeId = "save", text = "Simpan")),
            memory = singleStepMemory,
            brainPort = spyBrain,
            osStateVerifier = notFoundVerifier,
            osBaseline = notFoundVerifier.captureBaseline()
        )

        assertFalse(rejectedVerdict.achieved)
        assertTrue(rejectedVerdict.missingReason.contains("CalendarContract.Events"))
        assertEquals(0, spyBrain.criticVerifyCallCount)
    }
}
