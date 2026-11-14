package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.persona.PersonalWellnessContext
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryFastPathSkillTest {

    private val dummyMemory = ShortTermMemorySnapshot(
        recentSteps = emptyList(),
        scratchpad = emptyMap()
    )

    @Test
    fun `PersonalMemoryFastPathSkill canHandleVoiceIntent mengenali ragam perintah memori personal`() {
        val manager = UserMemoryManager()
        val skill = PersonalMemoryFastPathSkill(userMemoryManager = manager)

        val supportedIntents = listOf(
            "Ingat ya nama adikku Farhan",
            "Catat bahwa saya kerja di Bank BCA",
            "Tolong catat besok jam 10 ada rapat evaluasi",
            "Siapa nama adikku?",
            "Saya kerja di mana?",
            "Alamat rumah saya di mana?",
            "Apa jadwal saya hari ini?",
            "Apa alergi saya?",
            "Apa hobi saya?",
            "Apa yang kamu ingat tentang saya?",
            "Kamu ingat gak siapa temanku?",
            "Dulu saya kerja di mana?",
            "Kapan ulang tahunku?",
            "Berapa nomor plat motorku?",
            "Jam berapa saya biasa bangun?",
            "Apa kopi favorit saya?",
            "Siapa nama bos saya?",
            "Apa jurusan saya?",
            "Apa fokus riset saya?"
        )

        supportedIntents.forEach { intent ->
            assertTrue("Skill harus mengenali intent: '$intent'", skill.canHandleVoiceIntent(intent))
        }
    }

    @Test
    fun `proposeFastPathAction menyimpan fakta personal lisan secara langsung tanpa memanggil LLM Cloud`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        val skill = PersonalMemoryFastPathSkill(userMemoryManager = manager)

        val action = skill.proposeFastPathAction(
            userGoal = "Ingat ya nama adikku Farhan",
            foregroundPackage = "com.android.launcher3",
            screenElements = emptyList(),
            memory = dummyMemory
        )

        assertNotNull(action)
        assertEquals(AgentActionType.DONE, action?.action)
        assertTrue(action?.completionTts?.contains("Farhan") == true)

        val activeFacts = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Fakta adik Farhan harus tersimpan di UserMemoryManager", activeFacts.any { it.contains("Farhan") })
    }

    @Test
    fun `proposeFastPathAction menjawab pertanyaan silsilah keluarga, pekerjaan, domisili, dan kesehatan seketika`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        // Simpan fakta awal
        manager.recordMemory(category = "family", fact = "Adik pengguna bernama Farhan", subject = "family_siblings")
        manager.recordMemory(category = "work", fact = "Pengguna bekerja di Bank Mandiri", subject = "user_work")
        manager.recordMemory(category = "location", fact = "Pengguna tinggal di Sleman Yogyakarta", subject = "location_home")
        manager.recordMemory(category = "health", fact = "Pengguna alergi seafood", subject = "user_health")

        val skill = PersonalMemoryFastPathSkill(userMemoryManager = manager)

        // 1. Tanya adik
        val actionAdik = skill.proposeFastPathAction("Siapa nama adikku?", "", emptyList(), dummyMemory)
        assertNotNull(actionAdik)
        assertEquals(AgentActionType.DONE, actionAdik?.action)
        assertTrue(actionAdik?.completionTts?.contains("Farhan") == true)

        // 2. Tanya pekerjaan
        val actionWork = skill.proposeFastPathAction("Saya kerja di mana?", "", emptyList(), dummyMemory)
        assertNotNull(actionWork)
        assertEquals(AgentActionType.DONE, actionWork?.action)
        assertTrue(actionWork?.completionTts?.contains("Bank Mandiri") == true)

        // 3. Tanya lokasi rumah
        val actionLoc = skill.proposeFastPathAction("Alamat rumah saya di mana?", "", emptyList(), dummyMemory)
        assertNotNull(actionLoc)
        assertEquals(AgentActionType.DONE, actionLoc?.action)
        assertTrue(actionLoc?.completionTts?.contains("Sleman") == true)

        // 4. Tanya alergi
        val actionHealth = skill.proposeFastPathAction("Apa alergi saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionHealth)
        assertEquals(AgentActionType.DONE, actionHealth?.action)
        assertTrue(actionHealth?.completionTts?.contains("seafood") == true)

        // 5. Tanya rangkuman profil
        val actionProfile = skill.proposeFastPathAction("Apa saja yang kamu ingat tentang saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionProfile)
        assertEquals(AgentActionType.DONE, actionProfile?.action)
        assertTrue(actionProfile?.completionTts?.contains("Maulana") == true)
        assertTrue(actionProfile?.completionTts?.contains("Farhan") == true)
    }

    @Test
    fun `proposeFastPathAction menjawab riwayat timeline masa lalu secara konsisten`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()

        manager.recordMemory(category = "work", fact = "Pengguna bekerja di Tokopedia", subject = "user_work")
        manager.recordMemory(category = "work", fact = "Pengguna bekerja di Bank Mandiri", subject = "user_work")

        val skill = PersonalMemoryFastPathSkill(userMemoryManager = manager)

        val actionTimeline = skill.proposeFastPathAction("Dulu saya kerja di mana?", "", emptyList(), dummyMemory)
        assertNotNull(actionTimeline)
        assertEquals(AgentActionType.DONE, actionTimeline?.action)
        val tts = actionTimeline?.completionTts.orEmpty()
        assertTrue(tts.contains("Bank Mandiri") || tts.contains("Tokopedia"))
    }

    @Test
    fun `proposeFastPathAction menjawab pertanyaan ulang tahun, kendaraan, rutinitas, kuliner, dan atasan dengan bahasa hangat`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        manager.recordMemory(category = "birthday", fact = "Ulang tahun pengguna tanggal 15 Mei", subject = "birthday_user", predicate = "birthday_on")
        manager.recordMemory(category = "vehicle", fact = "Plat motor pengguna AB 1234 CD", subject = "vehicle_motorcycle", predicate = "plate_number")
        manager.recordMemory(category = "routine", fact = "Pengguna biasa bangun jam 5 pagi", subject = "routine_wake", predicate = "wakes_at")
        manager.recordMemory(category = "preference", fact = "Minuman favorit pengguna: Americano dingin tanpa gula", subject = "user_preference", predicate = "favorite_drink")
        manager.recordMemory(category = "contact", fact = "Bos pengguna bernama Pak Bambang", subject = "social_boss", predicate = "boss_named")

        val skill = PersonalMemoryFastPathSkill(userMemoryManager = manager)

        // 1. Ulang tahun
        val actionBday = skill.proposeFastPathAction("Kapan ulang tahunku?", "", emptyList(), dummyMemory)
        assertNotNull(actionBday)
        assertEquals(AgentActionType.DONE, actionBday?.action)
        assertTrue("Harus memuat tanggal 15 Mei", actionBday?.completionTts?.contains("15 Mei") == true)
        assertTrue("Harus terdengar hangat (ulang tahunmu)", actionBday?.completionTts?.contains("Ulang tahunmu") == true)

        // 2. Plat motor
        val actionVeh = skill.proposeFastPathAction("Berapa nomor plat motorku?", "", emptyList(), dummyMemory)
        assertNotNull(actionVeh)
        assertEquals(AgentActionType.DONE, actionVeh?.action)
        assertTrue("Harus memuat plat AB 1234 CD", actionVeh?.completionTts?.contains("AB 1234 CD") == true)

        // 3. Rutinitas jam bangun
        val actionRoutine = skill.proposeFastPathAction("Jam berapa saya biasa bangun?", "", emptyList(), dummyMemory)
        assertNotNull(actionRoutine)
        assertEquals(AgentActionType.DONE, actionRoutine?.action)
        assertTrue("Harus memuat jam 5 pagi", actionRoutine?.completionTts?.contains("5 pagi") == true)

        // 4. Kopi favorit
        val actionDrink = skill.proposeFastPathAction("Apa kopi favorit saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionDrink)
        assertEquals(AgentActionType.DONE, actionDrink?.action)
        assertTrue("Harus memuat Americano dingin", actionDrink?.completionTts?.contains("Americano dingin") == true)

        // 5. Atasan / Bos
        val actionBoss = skill.proposeFastPathAction("Siapa nama bos saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionBoss)
        assertEquals(AgentActionType.DONE, actionBoss?.action)
        assertTrue("Harus memuat Pak Bambang", actionBoss?.completionTts?.contains("Pak Bambang") == true)

        // 6. Jurusan & Mahasiswa Informatika
        manager.recordMemory(category = "education", fact = "Pengguna mahasiswa informatika", subject = "academic_major", predicate = "majoring_in")
        val actionEdu = skill.proposeFastPathAction("Apa jurusan saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionEdu)
        assertEquals(AgentActionType.DONE, actionEdu?.action)
        assertTrue("Harus memuat informatika", actionEdu?.completionTts?.contains("informatika") == true)

        // 7. Minat Riset AI & Agentic Systems
        manager.recordMemory(category = "tech_profile", fact = "Fokus riset AI pengguna: agentic systems", subject = "tech_interest", predicate = "researches_in")
        val actionTech = skill.proposeFastPathAction("Apa fokus riset saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionTech)
        assertEquals(AgentActionType.DONE, actionTech?.action)
        assertTrue("Harus memuat agentic systems", actionTech?.completionTts?.contains("agentic systems") == true)

        // 8. Spesialisasi Fokus Koding Python LangChain LangGraph FastAPI RAG
        manager.recordMemory(category = "tech_profile", fact = "Fokus koding & tech stack pengguna: Python, LangGraph, FastAPI, Vector DB, RAG", subject = "coding_focus", predicate = "specializes_in")
        val actionCoding = skill.proposeFastPathAction("Apa fokus koding saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionCoding)
        assertEquals(AgentActionType.DONE, actionCoding?.action)
        val tts = actionCoding?.completionTts.orEmpty()
        assertTrue("Harus memuat Python dan LangGraph", tts.contains("Python") && tts.contains("LangGraph"))
        assertTrue("Harus memuat fakta fokus koding tanpa halusinasi", tts.startsWith("Fokus koding kamu di"))
    }

    @Test
    fun `proposeFastPathAction menjawab pertanyaan hewan peliharaan, laptop, proyek, cita-cita, dan diet dengan hangat`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        manager.recordMemory(category = "pet", fact = "Kucing pengguna bernama Miko", subject = "pet_cat", predicate = "pet_named")
        manager.recordMemory(category = "device", fact = "Laptop pengguna: ASUS ROG Zephyrus", subject = "device_laptop", predicate = "laptop_model")
        manager.recordMemory(category = "project", fact = "Proyek pengguna: Gepy Superintelligence", subject = "user_project", predicate = "working_on_project")
        manager.recordMemory(category = "goal", fact = "Cita-cita pengguna: Menjadi AI architect", subject = "user_goal", predicate = "has_goal")
        manager.recordMemory(category = "dietary", fact = "Pengguna seorang vegetarian", subject = "user_dietary", predicate = "dietary_preference")

        val skill = PersonalMemoryFastPathSkill(userMemoryManager = manager)

        // 1. Hewan peliharaan
        val actionPet = skill.proposeFastPathAction("Siapa nama kucingku?", "", emptyList(), dummyMemory)
        assertNotNull(actionPet)
        assertEquals(AgentActionType.DONE, actionPet?.action)
        assertTrue("Harus memuat Miko", actionPet?.completionTts?.contains("Miko") == true)
        assertTrue("Harus hangat (Kucing kamu)", actionPet?.completionTts?.contains("Kucing kamu") == true)

        // 2. Laptop
        val actionDev = skill.proposeFastPathAction("Apa laptop saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionDev)
        assertEquals(AgentActionType.DONE, actionDev?.action)
        assertTrue("Harus memuat ASUS ROG", actionDev?.completionTts?.contains("ASUS ROG") == true)

        // 3. Proyek
        val actionProj = skill.proposeFastPathAction("Apa proyek saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionProj)
        assertEquals(AgentActionType.DONE, actionProj?.action)
        assertTrue("Harus memuat Gepy Superintelligence", actionProj?.completionTts?.contains("Gepy Superintelligence") == true)

        // 4. Cita-cita
        val actionGoal = skill.proposeFastPathAction("Apa cita-cita saya?", "", emptyList(), dummyMemory)
        assertNotNull(actionGoal)
        assertEquals(AgentActionType.DONE, actionGoal?.action)
        assertTrue("Harus memuat AI architect", actionGoal?.completionTts?.contains("AI architect") == true)

        // 5. Diet
        val actionDiet = skill.proposeFastPathAction("Apakah saya vegetarian?", "", emptyList(), dummyMemory)
        assertNotNull(actionDiet)
        assertEquals(AgentActionType.DONE, actionDiet?.action)
        assertTrue("Harus memuat vegetarian", actionDiet?.completionTts?.contains("vegetarian") == true)
    }

    @Test
    fun `PersonalMemoryFastPathSkill menyesuaikan gaya persona Girlfriend Manja dan Mentor Jujur tanpa bahasa kaku`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        val girlSkill = PersonalMemoryFastPathSkill(
            userMemoryManager = manager,
            speechContextProvider = {
                SovereignSpeechContext(
                    configuredMode = SovereignPersonaMode.ROMANTIC_GIRLFRIEND,
                    ownerName = "Maulana",
                    wellness = PersonalWellnessContext(ownerName = "Maulana", isGuestPrivacyShieldActive = false)
                )
            }
        )
        val mentorSkill = PersonalMemoryFastPathSkill(
            userMemoryManager = manager,
            speechContextProvider = {
                SovereignSpeechContext(
                    configuredMode = SovereignPersonaMode.HONEST_MENTOR,
                    ownerName = "Maulana"
                )
            }
        )

        val girlNoSchedule = girlSkill.proposeFastPathAction("Apa jadwal saya hari ini?", "", emptyList(), dummyMemory)
        assertNotNull(girlNoSchedule)
        val girlTts = girlNoSchedule?.completionTts.orEmpty()
        assertTrue(girlTts.startsWith("[warmly] "))
        assertTrue(girlTts.contains("Maulana"))
        assertFalse(girlTts.contains("sayang", ignoreCase = true))
        assertFalse(girlTts.contains("Saya belum memiliki"))

        val mentorNoSchedule = mentorSkill.proposeFastPathAction("Apa jadwal saya hari ini?", "", emptyList(), dummyMemory)
        assertNotNull(mentorNoSchedule)
        val mentorTts = mentorNoSchedule?.completionTts.orEmpty()
        assertTrue(mentorTts.contains("prioritas utama"))
        assertFalse(mentorTts.contains("Anda"))
    }
}