package com.teknologigawai.gepy.core.wellness

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class BriefingKind {
    MORNING,
    DAYTIME,
    EVENING
}

/** Hasil perakitan ringkasan harian terverifikasi beserta metadata pemicu. */
data class DailyBriefingResult(
    val kind: BriefingKind,
    val dateKey: String,
    val spokenText: String,
    val scheduleCount: Int,
    val yesterdaySteps: Int?,
    val todaySteps: Int,
    val batteryPercent: Int
)

/**
 * Mesin **Proactive Truthful Daily Briefing** untuk **Gepy v5.1 (100% On-Device)**:
 *
 * Prinsip Kejujuran Mutlak:
 * - Seluruh kalimat ringkasan hanya merujuk pada fakta terverifikasi dari OS & sensor nyata HP:
 *   1. Jadwal & catatan berbatas waktu dari **Temporal Knowledge Graph (Room `v4`)**
 *   2. Langkah kaki riil dari pedometer perangkat keras (`Sensor.TYPE_STEP_COUNTER`)
 *   3. Durasi layar medsos riil (`GepyAccessibilityService.foregroundPackageFlow`)
 *   4. Status baterai, pengisian daya, dan jam lokal OS Android
 * - Dilarang keras mengarang atau mengklaim mengetahui postur tubuh pengguna (duduk, berdiri, tidur).
 */
class DailyBriefingEngine(
    private val context: Context? = null,
    private val userMemoryManager: UserMemoryManager,
    private val wellnessEngine: WellnessAndGuardianEngine? = null,
    private val systemToolsBridge: AndroidSystemToolsBridge? = null,
    private val clockProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val speechContextProvider: (() -> SovereignSpeechContext)? = null,
    private val proactivityLevelProvider: () -> ProactivityLevel = { ProactivityLevel.CONTEXT_COPILOT }
) {
    companion object {
        private const val TAG = "DailyBriefingEngine"
        private const val PREFS_NAME = "gepy_daily_briefing_prefs"
        private const val KEY_LAST_MORNING_DATE = "last_morning_briefing_date"
        private const val KEY_LAST_EVENING_DATE = "last_evening_briefing_date"

        private val DATE_KEY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        private val DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("id-ID"))
    }

    private val inMemoryDates = mutableMapOf<String, String>()

    private val prefs: SharedPreferences? = try {
        context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    private fun getSavedDate(key: String): String {
        return prefs?.getString(key, "").orEmpty().ifEmpty { inMemoryDates[key].orEmpty() }
    }

    private fun markSavedDate(key: String, dateKey: String) {
        inMemoryDates[key] = dateKey
        prefs?.edit { putString(key, dateKey) }
    }

    private fun resolveSpeechContext(ownerName: String): SovereignSpeechContext {
        val base = speechContextProvider?.invoke()
            ?: systemToolsBridge?.speechContextProvider?.invoke()
            ?: SovereignSpeechContext(
                ownerName = ownerName,
                wellness = PersonalWellnessContext(
                    ownerName = ownerName,
                    isGuestPrivacyShieldActive = wellnessEngine?.currentSnapshot?.isGuestPrivacyShieldActive == true
                )
            )
        return if (base.resolvedOwnerName.isBlank() && ownerName.isNotBlank()) {
            base.copy(ownerName = ownerName)
        } else {
            base
        }
    }

    /**
     * Merakit **Morning Briefing** yang 100% berdasarkan data nyata (agenda hari ini, langkah kemarin, baterai HP).
     */
    suspend fun buildMorningBriefing(): DailyBriefingResult {
        val now = clockProvider()
        val nowMs = now.toInstant().toEpochMilli()
        val dateKey = DATE_KEY_FORMATTER.format(now)
        val dayLabel = DAY_LABEL_FORMATTER.format(now)

        val ownerName = userMemoryManager.getUserDisplayName().trim()
        val speechCtx = resolveSpeechContext(ownerName)

        val todaySchedules = userMemoryManager.getTodaySchedules(nowMs)
        val yesterdaySteps = wellnessEngine?.getYesterdaySteps()
        val todaySteps = wellnessEngine?.currentSnapshot?.todaySteps ?: 0
        val deviceStatus = systemToolsBridge?.getDeviceAndTimeStatus()
        val battery = deviceStatus?.batteryPercent ?: -1

        val agendaSummary = if (todaySchedules.isNotEmpty()) {
            todaySchedules.take(3).joinToString("; ") { it.factText.trimEnd('.') }
        } else {
            null
        }
        val text = SovereignNaturalSpeech.dailyBriefingMorning(
            ctx = speechCtx,
            dayLabel = dayLabel,
            hhmm = now.format(DateTimeFormatter.ofPattern("HH:mm")),
            todaySchedulesText = agendaSummary,
            yesterdaySteps = yesterdaySteps,
            batteryPercent = battery,
            isCharging = deviceStatus?.isCharging == true
        )

        return DailyBriefingResult(
            kind = BriefingKind.MORNING,
            dateKey = dateKey,
            spokenText = text,
            scheduleCount = todaySchedules.size,
            yesterdaySteps = yesterdaySteps,
            todaySteps = todaySteps,
            batteryPercent = battery
        )
    }

    /**
     * Merakit **Evening Reflection** berdasarkan data terukur hari ini (langkah pedometer, waktu layar medsos, baterai).
     */
    suspend fun buildEveningReflection(): DailyBriefingResult {
        val now = clockProvider()
        val nowMs = now.toInstant().toEpochMilli()
        val dateKey = DATE_KEY_FORMATTER.format(now)

        val ownerName = userMemoryManager.getUserDisplayName().trim()
        val speechCtx = resolveSpeechContext(ownerName)

        val snap = wellnessEngine?.currentSnapshot
        val todaySteps = snap?.todaySteps ?: 0
        val socialMins = snap?.totalSocialScrollMinutes ?: 0
        val todaySchedules = userMemoryManager.getTodaySchedules(nowMs)
        val deviceStatus = systemToolsBridge?.getDeviceAndTimeStatus()
        val battery = deviceStatus?.batteryPercent ?: -1

        val text = SovereignNaturalSpeech.dailyBriefingEvening(
            ctx = speechCtx,
            todaySteps = todaySteps,
            socialMins = socialMins,
            scheduleCount = todaySchedules.size,
            batteryPercent = battery,
            isCharging = deviceStatus?.isCharging == true
        )

        return DailyBriefingResult(
            kind = BriefingKind.EVENING,
            dateKey = dateKey,
            spokenText = text,
            scheduleCount = todaySchedules.size,
            yesterdaySteps = null,
            todaySteps = todaySteps,
            batteryPercent = battery
        )
    }

    /**
     * Merakit ringkasan sesuai jam berjalan saat pengguna menekan tombol *"Briefing Hari Ini"* atau memintanya secara lisan.
     */
    suspend fun buildOnDemandBriefing(): DailyBriefingResult {
        val now = clockProvider()
        val hour = now.hour
        return when {
            hour in 5..11 -> buildMorningBriefing()
            hour >= 19 || hour < 5 -> buildEveningReflection()
            else -> {
                val nowMs = now.toInstant().toEpochMilli()
                val dateKey = DATE_KEY_FORMATTER.format(now)
                val ownerName = userMemoryManager.getUserDisplayName().trim()
                val speechCtx = resolveSpeechContext(ownerName)
                val todaySchedules = userMemoryManager.getTodaySchedules(nowMs)
                val snap = wellnessEngine?.currentSnapshot
                val todaySteps = snap?.todaySteps ?: 0
                val socialMins = snap?.totalSocialScrollMinutes ?: 0
                val deviceStatus = systemToolsBridge?.getDeviceAndTimeStatus()
                val battery = deviceStatus?.batteryPercent ?: -1

                val agendaSummary = if (todaySchedules.isNotEmpty()) {
                    todaySchedules.take(3).joinToString("; ") { it.factText.trimEnd('.') }
                } else {
                    null
                }
                val text = SovereignNaturalSpeech.dailyBriefingDaytime(
                    ctx = speechCtx,
                    hhmm = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    todaySchedulesText = agendaSummary,
                    todaySteps = todaySteps,
                    socialMins = socialMins,
                    batteryPercent = battery
                )

                DailyBriefingResult(
                    kind = BriefingKind.DAYTIME,
                    dateKey = dateKey,
                    spokenText = text,
                    scheduleCount = todaySchedules.size,
                    yesterdaySteps = null,
                    todaySteps = todaySteps,
                    batteryPercent = battery
                )
            }
        }
    }

    /**
     * Mengevaluasi apakah interaksi pertama di pagi hari (`05:00 - 10:30`, misal membuka kunci layar)
     * layak memicu **Morning Briefing** secara otomatis (maksimal 1x per tanggal, ditahan saat
     * `ProactivityLevel.ZEN_PASSIVE` atau Privacy Shield aktif).
     */
    suspend fun tryTriggerMorningWakeupBriefing(): DailyBriefingResult? {
        if (proactivityLevelProvider() == ProactivityLevel.ZEN_PASSIVE) {
            GepyLog.i(TAG, "Morning Briefing otomatis ditahan karena ProactivityLevel=ZEN_PASSIVE.")
            return null
        }
        if (wellnessEngine?.currentSnapshot?.isGuestPrivacyShieldActive == true) {
            GepyLog.i(TAG, "Morning Briefing ditahan karena Privacy Shield aktif.")
            return null
        }
        val now = clockProvider()
        if (now.hour !in 5..10 || (now.hour == 10 && now.minute > 30)) {
            return null
        }
        val dateKey = DATE_KEY_FORMATTER.format(now)
        if (getSavedDate(KEY_LAST_MORNING_DATE) == dateKey) {
            return null
        }
        markSavedDate(KEY_LAST_MORNING_DATE, dateKey)
        return buildMorningBriefing()
    }

    /**
     * Mengevaluasi pemicu **Evening Reflection** otomatis pada rentang malam (`20:30 - 23:30`)
     * khusus mode [ProactivityLevel.AUTONOMOUS] dengan proteksi anti-spam maksimal 1x per malam.
     */
    suspend fun tryTriggerEveningReflection(): DailyBriefingResult? {
        val level = proactivityLevelProvider()
        if (level != ProactivityLevel.AUTONOMOUS) {
            GepyLog.i(TAG, "Evening Reflection otomatis ditahan karena ProactivityLevel=$level (hanya aktif di AUTONOMOUS).")
            return null
        }
        if (wellnessEngine?.currentSnapshot?.isGuestPrivacyShieldActive == true) {
            GepyLog.i(TAG, "Evening Reflection ditahan karena Privacy Shield aktif.")
            return null
        }
        val now = clockProvider()
        val minuteOfDay = now.hour * 60 + now.minute
        if (minuteOfDay !in (20 * 60 + 30)..(23 * 60 + 30)) {
            return null
        }
        val dateKey = DATE_KEY_FORMATTER.format(now)
        if (getSavedDate(KEY_LAST_EVENING_DATE) == dateKey) {
            return null
        }
        markSavedDate(KEY_LAST_EVENING_DATE, dateKey)
        return buildEveningReflection()
    }
}
