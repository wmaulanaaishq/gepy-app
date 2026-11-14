package com.teknologigawai.gepy.core.wellness

import com.teknologigawai.gepy.core.config.ByokPreferencesManager
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.system.AndroidSystemToolsBridge
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBriefingEngineTest {

    private val zone = ZoneId.of("Asia/Jakarta")

    @Test
    fun `Morning Briefing merangkum agenda jadwal terverifikasi langkah kemarin dan baterai tanpa halusinasi postur`() = runBlocking {
        val morningClock = ZonedDateTime.of(2026, 11, 8, 7, 30, 0, 0, zone)
        val morningMs = morningClock.toInstant().toEpochMilli()

        val memoryManager = UserMemoryManager(clockMsProvider = { morningMs })
        memoryManager.clearLocalMemory()
        memoryManager.setUserDisplayName("Maulana")
        memoryManager.recordMemory(
            category = "schedule",
            fact = "Hari ini jam 9 ada rapat evaluasi tim",
            subject = "schedule_agenda"
        )

        val byok = ByokPreferencesManager(context = null)
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            clockProvider = { LocalDateTime.of(2026, 11, 8, 7, 30) }
        )

        val systemBridge = AndroidSystemToolsBridge(
            context = null,
            clockProvider = { morningClock }
        )

        val briefingEngine = DailyBriefingEngine(
            context = null,
            userMemoryManager = memoryManager,
            wellnessEngine = wellnessEngine,
            systemToolsBridge = systemBridge,
            clockProvider = { morningClock }
        )

        val morning = briefingEngine.buildMorningBriefing()
        assertEquals(BriefingKind.MORNING, morning.kind)
        assertEquals(1, morning.scheduleCount)
        assertEquals(85, morning.batteryPercent)

        val text = morning.spokenText
        assertTrue("Harus menyapa nama pemilik", text.contains("Maulana"))
        assertTrue("Harus menyebut agenda hari ini", text.contains("rapat evaluasi tim"))
        assertTrue("Harus menyebut baterai HP", text.contains("85%"))

        // Verifikasi kepatuhan anti-halusinasi mutlak (user instruction)
        assertFalse("DILARANG mengarang deteksi tidur kasur", text.contains("kasur") || text.contains("rebahan"))
        assertFalse("DILARANG mengarang deteksi postur duduk", text.contains("duduk"))
        assertFalse("DILARANG mengarang deteksi postur berdiri", text.contains("berdiri"))
    }

    @Test
    fun `Evening Reflection merangkum langkah harian dan durasi layar medsos secara terukur`() = runBlocking {
        val eveningClock = ZonedDateTime.of(2026, 11, 8, 21, 15, 0, 0, zone)
        val eveningMs = eveningClock.toInstant().toEpochMilli()

        val memoryManager = UserMemoryManager(clockMsProvider = { eveningMs })
        memoryManager.clearLocalMemory()
        memoryManager.setUserDisplayName("Maulana")

        val byok = ByokPreferencesManager(context = null)
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            clockProvider = { LocalDateTime.of(2026, 11, 8, 21, 15) }
        )
        wellnessEngine.onHardwareStepCounterUpdate(5_000)
        wellnessEngine.onHardwareStepCounterUpdate(9_450)
        wellnessEngine.tickForegroundObservation("com.instagram.android", elapsedSeconds = 720, nowWallMs = 1_000_000L)

        val systemBridge = AndroidSystemToolsBridge(
            context = null,
            clockProvider = { eveningClock }
        )

        val briefingEngine = DailyBriefingEngine(
            context = null,
            userMemoryManager = memoryManager,
            wellnessEngine = wellnessEngine,
            systemToolsBridge = systemBridge,
            clockProvider = { eveningClock }
        )

        val evening = briefingEngine.buildEveningReflection()
        assertEquals(BriefingKind.EVENING, evening.kind)
        assertEquals(4_450, evening.todaySteps)

        val text = evening.spokenText
        assertTrue("Harus memuat langkah kaki pedometer", text.contains("4450 langkah"))
        assertTrue("Harus memuat durasi scroll medsos", text.contains("12 menit"))
        assertFalse("DILARANG mengarang deteksi posisi tubuh tidur/kasur", text.contains("kasur") || text.contains("rebahan"))
    }

    @Test
    fun `tryTriggerMorningWakeupBriefing mematuhi jendela pagi batas 1x per hari dan proteksi Privacy Shield`() = runBlocking {
        var currentClock = ZonedDateTime.of(2026, 11, 8, 7, 0, 0, 0, zone)

        val memoryManager = UserMemoryManager(clockMsProvider = { currentClock.toInstant().toEpochMilli() })
        memoryManager.clearLocalMemory()

        val byok = ByokPreferencesManager(context = null)
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            clockProvider = { currentClock.toLocalDateTime() }
        )

        val briefingEngine = DailyBriefingEngine(
            context = null,
            userMemoryManager = memoryManager,
            wellnessEngine = wellnessEngine,
            systemToolsBridge = AndroidSystemToolsBridge(context = null, clockProvider = { currentClock }),
            clockProvider = { currentClock }
        )

        // 1. Pemicu pagi pertama berhasil
        val first = briefingEngine.tryTriggerMorningWakeupBriefing()
        assertNotNull("Pemicu pertama harus menghasilkan briefing", first)

        // 2. Pemicu kedua pada tanggal yang sama DITOLAK (anti-spam 1x/hari)
        val second = briefingEngine.tryTriggerMorningWakeupBriefing()
        assertNull("Pemicu kedua pada hari yang sama harus diblokir", second)

        // 3. Pada jam siang (14:00) harus ditolak
        currentClock = ZonedDateTime.of(2026, 11, 8, 14, 0, 0, 0, zone)
        val afternoon = briefingEngine.tryTriggerMorningWakeupBriefing()
        assertNull("Di luar jam pagi harus null", afternoon)

        // 4. Hari baru (tanggal 9) dengan Privacy Shield aktif (ada tamu) harus dibatalkan
        currentClock = ZonedDateTime.of(2026, 11, 9, 6, 30, 0, 0, zone)
        wellnessEngine.ingestCsiTelemetry("GAIT_BIOMETRIC", room = "kamar", biometricOwnerMatch = false)
        assertTrue(wellnessEngine.currentSnapshot.isGuestPrivacyShieldActive)

        val guestBlocked = briefingEngine.tryTriggerMorningWakeupBriefing()
        assertNull("Briefing harus ditahan saat Privacy Shield aktif", guestBlocked)
    }

    @Test
    fun `Morning dan Evening Briefing menyesuaikan persona Girlfriend Manja dan Mentor Jujur tanpa halusinasi postur`() = runBlocking {
        val clock = ZonedDateTime.of(2026, 11, 10, 7, 15, 0, 0, zone)
        val memoryManager = UserMemoryManager(clockMsProvider = { clock.toInstant().toEpochMilli() })
        memoryManager.clearLocalMemory()
        memoryManager.setUserDisplayName("Maulana")

        val byok = ByokPreferencesManager(context = null)
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            clockProvider = { clock.toLocalDateTime() }
        )
        wellnessEngine.onHardwareStepCounterUpdate(1000)
        wellnessEngine.onHardwareStepCounterUpdate(3200)

        val systemBridge = AndroidSystemToolsBridge(context = null, clockProvider = { clock })

        val girlEngine = DailyBriefingEngine(
            context = null,
            userMemoryManager = memoryManager,
            wellnessEngine = wellnessEngine,
            systemToolsBridge = systemBridge,
            clockProvider = { clock },
            speechContextProvider = {
                SovereignSpeechContext(
                    configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
                    ownerName = "Maulana",
                    wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = false)
                )
            }
        )
        val girlMorning = girlEngine.buildMorningBriefing().spokenText
        assertTrue(girlMorning.startsWith("[warmly] "))
        assertFalse(girlMorning.contains("sayang", ignoreCase = true))
        assertTrue(girlMorning.contains("Maulana"))
        assertFalse(girlMorning.contains("kasur") || girlMorning.contains("duduk"))

        val mentorEngine = DailyBriefingEngine(
            context = null,
            userMemoryManager = memoryManager,
            wellnessEngine = wellnessEngine,
            systemToolsBridge = systemBridge,
            clockProvider = { clock },
            speechContextProvider = {
                SovereignSpeechContext(
                    configuredMode = SovereignPersonaMode.HONEST_MENTOR,
                    ownerName = "Maulana"
                )
            }
        )
        val mentorEvening = mentorEngine.buildEveningReflection().spokenText
        assertTrue(mentorEvening.contains("Evaluasi jujur"))
        assertTrue(mentorEvening.contains("2200 langkah"))
        assertFalse(mentorEvening.contains("kasur") || mentorEvening.contains("rebahan"))
    }

    @Test
    fun `ProactivityLevel ZEN_PASSIVE CONTEXT_COPILOT dan AUTONOMOUS mengatur pemicu briefing otomatis secara konsisten`() = runBlocking {
        var currentClock = ZonedDateTime.of(2026, 11, 11, 7, 15, 0, 0, zone)
        var currentLevel = ProactivityLevel.ZEN_PASSIVE

        val memoryManager = UserMemoryManager(clockMsProvider = { currentClock.toInstant().toEpochMilli() })
        memoryManager.clearLocalMemory()
        memoryManager.setUserDisplayName("Maulana")

        val byok = ByokPreferencesManager(context = null)
        val wellnessEngine = WellnessAndGuardianEngine(
            context = null,
            dao = null,
            byokPreferencesManager = byok,
            ownerNameProvider = { "Maulana" },
            clockProvider = { currentClock.toLocalDateTime() }
        )
        val briefingEngine = DailyBriefingEngine(
            context = null,
            userMemoryManager = memoryManager,
            wellnessEngine = wellnessEngine,
            systemToolsBridge = AndroidSystemToolsBridge(context = null, clockProvider = { currentClock }),
            clockProvider = { currentClock },
            proactivityLevelProvider = { currentLevel }
        )

        // 1. ZEN_PASSIVE menahan briefing pagi otomatis, namun permintaan manual on-demand TETAP jalan
        assertNull("ZEN_PASSIVE wajib menahan morning briefing otomatis", briefingEngine.tryTriggerMorningWakeupBriefing())
        val manualBriefing = briefingEngine.buildOnDemandBriefing()
        assertEquals(BriefingKind.MORNING, manualBriefing.kind)

        // 2. CONTEXT_COPILOT meloloskan morning briefing otomatis 1x/hari, tetapi menahan evening reflection otomatis
        currentLevel = ProactivityLevel.CONTEXT_COPILOT
        assertNotNull("CONTEXT_COPILOT harus memicu morning briefing", briefingEngine.tryTriggerMorningWakeupBriefing())

        currentClock = ZonedDateTime.of(2026, 11, 11, 21, 0, 0, 0, zone)
        assertNull("CONTEXT_COPILOT tidak boleh otomatis memicu evening reflection", briefingEngine.tryTriggerEveningReflection())

        // 3. AUTONOMOUS membuka evening reflection otomatis pada 20:30-23:30 maksimal 1x/malam
        currentLevel = ProactivityLevel.AUTONOMOUS
        val autoEvening = briefingEngine.tryTriggerEveningReflection()
        assertNotNull("AUTONOMOUS wajib memicu evening reflection di rentang 20:30-23:30", autoEvening)
        assertEquals(BriefingKind.EVENING, autoEvening?.kind)
        assertNull("Evening reflection kedua di tanggal yang sama wajib ditahan", briefingEngine.tryTriggerEveningReflection())

        // 4. Privacy Shield tetap menahan evening reflection AUTONOMOUS di malam baru
        currentClock = ZonedDateTime.of(2026, 11, 12, 21, 0, 0, 0, zone)
        wellnessEngine.ingestCsiTelemetry("GAIT_BIOMETRIC", room = "kamar", biometricOwnerMatch = false)
        assertNull("Privacy Shield aktif wajib menahan evening reflection walau AUTONOMOUS", briefingEngine.tryTriggerEveningReflection())
    }
}
