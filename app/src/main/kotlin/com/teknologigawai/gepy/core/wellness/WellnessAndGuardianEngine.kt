package com.teknologigawai.gepy.core.wellness

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.GuardianTriggerReason
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignPersonaEngine
import com.teknologigawai.gepy.data.database.GepyDao
import com.teknologigawai.gepy.data.database.PersonalWellnessLogEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Cuplikan reaktif status kebugaran fisik, fokus medsos, dan radar ruangan CSI milik pengguna. */
data class WellnessGuardianSnapshot(
    val dateKey: String = "",
    val todaySteps: Int = 0,
    val totalSocialScrollSeconds: Int = 0,
    val continuousSocialScrollSeconds: Int = 0,
    val activeSocialPackage: String = "",
    val sedentaryBedMinutes: Int = 0,
    val respirationBpm: Int? = null,
    val interventionsCount: Int = 0,
    val lastCsiEvent: String = "SIAGA",
    val csiRoomLabel: String = "-",
    val isOwnerVerifiedInRoom: Boolean = true,
    val isGuestPrivacyShieldActive: Boolean = false,
    val stepSensorActive: Boolean = false
) {
    val totalSocialScrollMinutes: Int
        get() = totalSocialScrollSeconds / 60

    val continuousSocialScrollMinutes: Int
        get() = continuousSocialScrollSeconds / 60

    /** Menunjukkan apakah benar-benar ada sinyal perangkat keras eksternal ESP32 CSI (bukan deteksi imajiner HP). */
    val hasRealCsiTelemetry: Boolean
        get() = sedentaryBedMinutes > 0 ||
            (respirationBpm != null && respirationBpm > 0) ||
            (lastCsiEvent.isNotBlank() && lastCsiEvent != "SIAGA") ||
            isGuestPrivacyShieldActive

    fun toPersonalWellnessContext(
        ownerName: String,
        doomscrollLimitMinutes: Int
    ): PersonalWellnessContext = PersonalWellnessContext(
        ownerName = ownerName,
        todaySteps = todaySteps,
        activeSocialScrollMinutes = continuousSocialScrollMinutes,
        doomscrollLimitMinutes = doomscrollLimitMinutes,
        sedentaryBedMinutes = sedentaryBedMinutes,
        respirationBpm = respirationBpm,
        isOwnerVerifiedInRoom = isOwnerVerifiedInRoom,
        isGuestPrivacyShieldActive = isGuestPrivacyShieldActive
    )

    fun toToolSummaryMap(): Map<String, Any?> {
        val summary = if (hasRealCsiTelemetry) {
            val shieldLabel = if (isGuestPrivacyShieldActive) "Aktif (Ada Tamu)" else "Aman (Pemilik Sah)"
            val bpmText = if (respirationBpm != null && respirationBpm > 0) ", napas $respirationBpm BPM" else ""
            "Hari ini tercatat $todaySteps langkah kaki, durasi layar media sosial $totalSocialScrollMinutes menit " +
                "(sesi kontinu $continuousSocialScrollMinutes menit), durasi diam/kamar $sedentaryBedMinutes menit$bpmText. " +
                "Status radar CSI: $lastCsiEvent ($shieldLabel)."
        } else {
            val pedometerNote = if (stepSensorActive) "sensor pedometer hardware aktif" else "pedometer menunggu langkah pertama"
            "Hari ini tercatat $todaySteps langkah kaki ($pedometerNote) dan durasi layar media sosial " +
                "$totalSocialScrollMinutes menit (sesi kontinu $continuousSocialScrollMinutes menit). " +
                "Catatan jujur: Gepy berjalan mandiri di HP tanpa kamera atau radar eksternal, sehingga tidak mendeteksi postur duduk, berdiri, atau tidur."
        }
        return mapOf(
            "status" to "SUCCESS",
            "date" to dateKey,
            "step_count" to todaySteps,
            "social_scroll_minutes" to totalSocialScrollMinutes,
            "continuous_scroll_minutes" to continuousSocialScrollMinutes,
            "has_external_csi_telemetry" to hasRealCsiTelemetry,
            "sedentary_bed_minutes" to sedentaryBedMinutes,
            "respiration_bpm" to respirationBpm,
            "last_csi_event" to if (hasRealCsiTelemetry) lastCsiEvent else "NONE_ON_DEVICE_ONLY",
            "privacy_shield_active" to isGuestPrivacyShieldActive,
            "summary_id" to summary
        )
    }
}

/**
 * Mesin **Anti-Doomscrolling Guardian & Personal Wellness Tracker** untuk **Gepy v5.0**:
 * 1. Memantau pedometer perangkat keras `Sensor.TYPE_STEP_COUNTER` (`0%` beban CPU latar belakang).
 * 2. Menghitung sesi *doomscrolling* kontinu di Instagram, TikTok, dan YouTube (hanya saat pengguna
 *    melakukan scroll manual, diabaikan ketika `isAgentExecutingProvider() == true`).
 * 3. Menggabungkan sinyal durasi rebahan dan biometrik dari `EspCsiAmbientBridge`.
 * 4. Menyimpan log kesehatan harian 100% lokal di Room SQLite (`personal_wellness_logs`).
 */
class WellnessAndGuardianEngine(
    private val context: Context? = null,
    private val dao: GepyDao? = null,
    private val byokPreferencesManager: ByokPreferencesManager = ByokPreferencesManager(),
    private val ownerNameProvider: () -> String = { "" },
    private val isAgentExecutingProvider: () -> Boolean = { false },
    private val onGuardianIntervention: suspend (speechText: String, triggerAutoHome: Boolean) -> Unit = { _, _ -> },
    private val clockProvider: () -> LocalDateTime = { LocalDateTime.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onProactiveLifecycleTick: (suspend () -> Unit)? = null
) {
    companion object {
        private const val TAG = "WellnessGuardian"
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** Daftar paket aplikasi jebakan atensi (*Attention-Economy Short Video*). */
        val ATTENTION_TRAP_PACKAGES: Set<String> = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.google.android.youtube",
            "com.twitter.android"
        )

        private const val MIN_INTERVENTION_COOLDOWN_MS = 10 * 60 * 1000L
    }

    private val _stateFlow = MutableStateFlow(
        WellnessGuardianSnapshot(dateKey = DATE_FORMATTER.format(clockProvider()))
    )
    val stateFlow: StateFlow<WellnessGuardianSnapshot> = _stateFlow.asStateFlow()

    val currentSnapshot: WellnessGuardianSnapshot
        get() = _stateFlow.value

    private var hardwareStepBaseline: Int? = null
    private var lastInterventionWallMs: Long = 0L
    private var lastProactiveTickWallMs: Long = 0L
    private var sensorManager: SensorManager? = null
    private var stepListener: SensorEventListener? = null
    private var watchdogJob: Job? = null

    init {
        scope.launch {
            restoreTodayFromDatabase()
        }
    }

    /** Memulai pemantauan sensor langkah kaki perangkat keras Android dan pengawas foreground medsos. */
    fun startMonitoring(foregroundPackageFlow: StateFlow<String>) {
        registerHardwareStepSensorIfPermitted()
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                val nowMs = System.currentTimeMillis()
                val fgPkg = foregroundPackageFlow.value
                tickForegroundObservation(
                    foregroundPackage = fgPkg,
                    elapsedSeconds = 5,
                    nowWallMs = nowMs
                )
                if (onProactiveLifecycleTick != null && (nowMs - lastProactiveTickWallMs) >= 300_000L) {
                    lastProactiveTickWallMs = nowMs
                    try {
                        onProactiveLifecycleTick.invoke()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        GepyLog.w(TAG, "Evaluasi proaktif berkala gagal: ${t.message}")
                    }
                }
            }
        }
    }

    /** Dapat dipanggil ulang sesudah pengguna memberikan izin `ACTIVITY_RECOGNITION` di runtime. */
    fun registerHardwareStepSensorIfPermitted() {
        val ctx = context ?: return
        if (stepListener != null) return
        val hasPerm = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val rawTotal = event?.values?.firstOrNull()?.toInt() ?: return
                    onHardwareStepCounterUpdate(rawTotal)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val registered = sm.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (registered) {
                sensorManager = sm
                stepListener = listener
                _stateFlow.update { it.copy(stepSensorActive = true) }
                GepyLog.i(TAG, "Hardware Step Counter (TYPE_STEP_COUNTER) berhasil diaktifkan.")
            }
        } catch (e: Throwable) {
            GepyLog.w(TAG, "Sensor langkah perangkat keras tidak tersedia: ${e.message}")
        }
    }

    /** Memperbarui langkah harian berdasarkan nilai kumulatif sensor perangkat keras. */
    fun onHardwareStepCounterUpdate(cumulativeHardwareSteps: Int) {
        if (cumulativeHardwareSteps < 0) return
        ensureDateRollover()
        val baseline = hardwareStepBaseline
        if (baseline == null || cumulativeHardwareSteps < baseline) {
            hardwareStepBaseline = cumulativeHardwareSteps - _stateFlow.value.todaySteps.coerceAtLeast(0)
            return
        }
        val computedToday = (cumulativeHardwareSteps - baseline).coerceAtLeast(0)
        if (computedToday != _stateFlow.value.todaySteps) {
            _stateFlow.update { it.copy(todaySteps = computedToday) }
            persistCurrentToDatabaseAsync()
        }
    }

    /**
     * Satu detak observasi foreground aplikasi (mudah dipanggil secara deterministik di unit test JVM
     * tanpa perlu menunggu waktu nyata 15 menit).
     */
    suspend fun tickForegroundObservation(
        foregroundPackage: String,
        elapsedSeconds: Int,
        nowWallMs: Long = System.currentTimeMillis()
    ) {
        if (elapsedSeconds <= 0) return
        ensureDateRollover()

        val cleanPkg = foregroundPackage.trim()
        val isAttentionTrap = cleanPkg in ATTENTION_TRAP_PACKAGES

        // Abaikan jika Gepy sendiri yang sedang menjalankan otomatisasi tugas atas perintah pemilik!
        if (!isAttentionTrap || isAgentExecutingProvider()) {
            if (_stateFlow.value.continuousSocialScrollSeconds > 0 && !isAttentionTrap && cleanPkg.isNotEmpty()) {
                _stateFlow.update {
                    it.copy(
                        continuousSocialScrollSeconds = 0,
                        activeSocialPackage = ""
                    )
                }
            }
            return
        }

        var updatedTotal = 0
        var updatedCont = 0
        _stateFlow.update { cur ->
            updatedTotal = cur.totalSocialScrollSeconds + elapsedSeconds
            updatedCont = cur.continuousSocialScrollSeconds + elapsedSeconds
            cur.copy(
                totalSocialScrollSeconds = updatedTotal,
                continuousSocialScrollSeconds = updatedCont,
                activeSocialPackage = cleanPkg
            )
        }

        val settings = byokPreferencesManager.currentSettings
        val limitSeconds = settings.doomscrollLimitMinutes.coerceAtLeast(1) * 60
        if (settings.antiDoomscrollEnabled &&
            settings.proactivityLevel != ProactivityLevel.ZEN_PASSIVE &&
            updatedCont >= limitSeconds &&
            (nowWallMs - lastInterventionWallMs) >= MIN_INTERVENTION_COOLDOWN_MS
        ) {
            lastInterventionWallMs = nowWallMs
            val autoHome = settings.antiDoomscrollAutoHome
            val ctx = _stateFlow.value.toPersonalWellnessContext(
                ownerName = ownerNameProvider(),
                doomscrollLimitMinutes = settings.doomscrollLimitMinutes
            )
            val speech = SovereignPersonaEngine.buildGuardianInterventionSpeech(
                configuredMode = settings.sovereignPersonaMode,
                reason = GuardianTriggerReason.DOOMSCROLL_LIMIT,
                wellness = ctx,
                autoHomeTriggered = autoHome
            )
            _stateFlow.update {
                it.copy(
                    interventionsCount = it.interventionsCount + 1,
                    continuousSocialScrollSeconds = if (autoHome) 0 else it.continuousSocialScrollSeconds
                )
            }
            persistCurrentToDatabaseAsync()
            onGuardianIntervention(speech, autoHome)
        } else if (updatedTotal % 30 < elapsedSeconds) {
            persistCurrentToDatabaseAsync()
        }
    }

    /**
     * Menerima laporan telemetri ruangan dari `EspCsiAmbientBridge` (atau tombol simulasi di Dashboard).
     */
    suspend fun ingestCsiTelemetry(
        eventType: String,
        room: String,
        durationMinutes: Int = 0,
        biometricOwnerMatch: Boolean = true,
        respirationBpm: Int? = null,
        nowWallMs: Long = System.currentTimeMillis()
    ) {
        ensureDateRollover()
        val normEvent = eventType.trim().uppercase()
        val guestShield = !biometricOwnerMatch

        _stateFlow.update { cur ->
            val updatedBedMins = when (normEvent) {
                "BED_OCCUPIED", "SEDENTARY_ALERT" -> maxOf(cur.sedentaryBedMinutes, durationMinutes.coerceAtLeast(0))
                "PRESENCE_EXIT" -> cur.sedentaryBedMinutes
                else -> cur.sedentaryBedMinutes
            }
            cur.copy(
                sedentaryBedMinutes = updatedBedMins,
                respirationBpm = respirationBpm ?: cur.respirationBpm,
                lastCsiEvent = normEvent,
                csiRoomLabel = room.ifBlank { cur.csiRoomLabel },
                isOwnerVerifiedInRoom = biometricOwnerMatch,
                isGuestPrivacyShieldActive = guestShield
            )
        }
        persistCurrentToDatabaseAsync()

        // Evaluasi intervensi rebahan/kurang gerak pada jam aktif (07:00 - 21:00) hanya bila pemilik sah di kamar
        val hour = clockProvider().hour
        val isDaytime = hour in 7..21
        val snap = _stateFlow.value
        val settings = byokPreferencesManager.currentSettings
        if (settings.proactivityLevel != ProactivityLevel.ZEN_PASSIVE &&
            biometricOwnerMatch &&
            isDaytime &&
            (normEvent == "SEDENTARY_ALERT" || normEvent == "BED_OCCUPIED") &&
            snap.sedentaryBedMinutes >= 180 &&
            snap.todaySteps < 300 &&
            (nowWallMs - lastInterventionWallMs) >= MIN_INTERVENTION_COOLDOWN_MS
        ) {
            lastInterventionWallMs = nowWallMs
            val ctx = snap.toPersonalWellnessContext(
                ownerName = ownerNameProvider(),
                doomscrollLimitMinutes = settings.doomscrollLimitMinutes
            )
            val speech = SovereignPersonaEngine.buildGuardianInterventionSpeech(
                configuredMode = settings.sovereignPersonaMode,
                reason = GuardianTriggerReason.SEDENTARY_BED_ALERT,
                wellness = ctx,
                autoHomeTriggered = false
            )
            _stateFlow.update { it.copy(interventionsCount = it.interventionsCount + 1) }
            persistCurrentToDatabaseAsync()
            onGuardianIntervention(speech, false)
        }
    }

    private fun ensureDateRollover() {
        val today = DATE_FORMATTER.format(clockProvider())
        if (_stateFlow.value.dateKey != today) {
            hardwareStepBaseline = null
            _stateFlow.update {
                WellnessGuardianSnapshot(
                    dateKey = today,
                    stepSensorActive = it.stepSensorActive,
                    isOwnerVerifiedInRoom = it.isOwnerVerifiedInRoom,
                    isGuestPrivacyShieldActive = it.isGuestPrivacyShieldActive
                )
            }
        }
    }

    private suspend fun restoreTodayFromDatabase() {
        val d = dao ?: return
        val today = DATE_FORMATTER.format(clockProvider())
        try {
            val saved = d.getWellnessLogForDate(today) ?: return
            _stateFlow.update { cur ->
                cur.copy(
                    dateKey = today,
                    todaySteps = saved.stepCount,
                    totalSocialScrollSeconds = saved.socialScrollSeconds,
                    sedentaryBedMinutes = saved.sedentaryBedMinutes,
                    interventionsCount = saved.interventionsCount,
                    lastCsiEvent = saved.lastCsiEvent.ifEmpty { "SIAGA" }
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun persistCurrentToDatabaseAsync() {
        val d = dao ?: return
        val snap = _stateFlow.value
        if (snap.dateKey.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                d.upsertWellnessLog(
                    PersonalWellnessLogEntity(
                        dateKey = snap.dateKey,
                        stepCount = snap.todaySteps,
                        socialScrollSeconds = snap.totalSocialScrollSeconds,
                        sedentaryBedMinutes = snap.sedentaryBedMinutes,
                        interventionsCount = snap.interventionsCount,
                        lastCsiEvent = snap.lastCsiEvent,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    /** Mengambil jumlah langkah kaki kemarin dari Room database lokal (null jika belum ada catatan). */
    suspend fun getYesterdaySteps(): Int? {
        val d = dao ?: return null
        return try {
            val yesterdayKey = DATE_FORMATTER.format(clockProvider().minusDays(1))
            d.getWellnessLogForDate(yesterdayKey)?.stepCount
        } catch (_: Throwable) {
            null
        }
    }

    fun stopMonitoring() {
        watchdogJob?.cancel()
        watchdogJob = null
        stepListener?.let { listener ->
            try {
                sensorManager?.unregisterListener(listener)
            } catch (_: Throwable) {
            }
        }
        stepListener = null
    }
}
