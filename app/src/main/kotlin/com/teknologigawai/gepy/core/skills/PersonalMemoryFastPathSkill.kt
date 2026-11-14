package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.memory.TemporalKnowledgeGraph
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * *Fast-Path Skill* untuk pengelolaan dan penelusuran memori personal pengguna
 * (< 15 ms, 0 token LLM Cloud, 100% On-Device).
 *
 * Menangani:
 * 1. Simpan fakta lisan eksplisit: "ingat ya...", "catat bahwa...", "tolong ingat..."
 * 2. Penelusuran silsilah keluarga & rekan: "siapa nama adikku/istriku/anakku/temanku"
 * 3. Informasi profil, pekerjaan, & domisili: "saya kerja di mana", "alamat rumah saya di mana"
 * 4. Catatan kesehatan & alergi: "apa alergi saya", "pantangan kesehatan saya"
 * 5. Preferensi & hobi: "apa hobi saya", "makanan kesukaan saya"
 * 6. Agenda temporal hari ini: "ada jadwal apa hari ini", "agenda saya besok"
 * 7. Riwayat timeline perubahan: "dulu saya kerja di mana", "riwayat alamat saya"
 * 8. Rangkuman profil menyeluruh: "apa saja yang kamu ingat tentang saya", "rangkum profil saya"
 */
class PersonalMemoryFastPathSkill(
    private val userMemoryManager: UserMemoryManager,
    private val speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) : GepySkillPlugin {

    companion object {
        private val SAVE_DIRECTIVE_REGEX = Regex(
            """\b(ingat ya|ingat bahwa|tolong ingat|catat ya|catat bahwa|tolong catat|simpan info|simpan catatan)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_PERSON_REGEX = Regex(
            """\b(siapa nama|siapa adik|siapa kakak|siapa istri|siapa suami|siapa anak|siapa ayah|siapa ibu|siapa teman|siapa sahabat)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_WORK_REGEX = Regex(
            """\b(saya kerja di mana|kantor saya di mana|apa pekerjaan saya|pekerjaan saya apa|profesi saya|tempat kerja saya)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_LOCATION_REGEX = Regex(
            """\b(alamat rumah|saya tinggal di mana|domisili saya|rumah saya di mana|dimana rumah saya|dimana tempat tinggal saya)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_SCHEDULE_REGEX = Regex(
            """\b(apa jadwal saya|jadwal saya hari ini|agenda saya|jadwal besok|jadwal hari ini|ada agenda apa|ada rapat apa|ada jadwal apa)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_HEALTH_REGEX = Regex(
            """\b(apa alergi saya|saya alergi apa|pantangan saya apa|alergi apa|catatan kesehatan saya)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_PREFERENCE_REGEX = Regex(
            """\b(apa hobi saya|apa kesukaan saya|saya suka apa|makanan favorit saya|minuman favorit saya)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_BIRTHDAY_REGEX = Regex(
            """\b(kapan ulang tahun|ulang tahun(?:ku| saya)|kapan ultah|hari lahir(?:ku| saya)|tanggal ulang tahun)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_VEHICLE_REGEX = Regex(
            """\b(plat nomor|nomor plat|plat motor|plat mobil|kendaraan saya|motor saya|mobil saya|apa plat)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_ROUTINE_REGEX = Regex(
            """\b(jam berapa saya (?:biasa )?bangun|rutinitas saya|kebiasaan saya|jadwal olahraga)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_FOOD_DRINK_REGEX = Regex(
            """\b(kopi favorit|minuman favorit|makanan favorit|makanan kesukaan|minuman kesukaan|saya paling suka (?:makan|minum|ngopi))\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_PROFESSIONAL_REGEX = Regex(
            """\b(siapa (?:nama )?(?:bos|atasan|manajer|dokter gigi)(?:ku| saya)?|bos saya|atasan saya)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_EDUCATION_REGEX = Regex(
            """\b(apa jurusan saya|jurusan saya apa|saya kuliah di mana|dimana saya kuliah|kampus saya|program studi saya|saya mahasiswa apa)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_TECH_PROFILE_REGEX = Regex(
            """\b(apa fokus riset saya|fokus riset saya|minat riset saya|topik skripsi saya|skripsi saya|tech stack favorit|bahasa koding favorit|bahasa pemrograman favorit|fokus koding saya|fokus coding saya|apa fokus koding saya|apa fokus coding saya|stack koding saya|stack coding saya|apa stack koding saya|bahasa koding saya|framework koding saya|teknologi yang sering kupakai|teknologi favoritku)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_PROFILE_ALL_REGEX = Regex(
            """\b(apa (?:saja )?yang kamu (?:ingat|tahu)|ceritakan tentang saya|profil saya|rangkum tentang saya|kamu ingat apa saja)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_TIMELINE_REGEX = Regex(
            """\b(dulu saya|riwayat kerja|riwayat alamat|riwayat tinggal|riwayat memori|sebelumnya saya)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_GENERAL_RECALL_REGEX = Regex(
            """\b(kamu ingat (?:gak|tidak|kah)|kamu tahu (?:gak|tidak|kah))\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_PET_REGEX = Regex(
            """\b(siapa nama (?:kucing|anjing|peliharaan)|nama (?:kucing|anjing|peliharaan)(?:ku| saya)|hewan peliharaan(?:ku| saya)|kucing(?:ku| saya)|anjing(?:ku| saya))\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_DEVICE_REGEX = Regex(
            """\b(apa laptop(?:ku| saya)|laptop(?:ku| saya) apa|merk hp(?:ku| saya)|apa hp(?:ku| saya)|gadget(?:ku| saya)|komputer(?:ku| saya)|laptop yang kupakai)\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_PROJECT_REGEX = Regex(
            """\b(apa proyek(?:ku| saya)|proyek(?:ku| saya) apa|apa project(?:ku| saya)|startup(?:ku| saya)|aplikasi yang (?:saya|ku) (?:buat|bikin))\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_GOAL_REGEX = Regex(
            """\b(apa cita-cita(?:ku| saya)|cita-cita(?:ku| saya) apa|apa impian(?:ku| saya)|target(?:ku| saya) apa|impian(?:ku| saya))\b""",
            RegexOption.IGNORE_CASE
        )

        private val QUERY_DIETARY_REGEX = Regex(
            """\b(pantangan makan(?:an)?(?:ku| saya)|apakah saya vegetarian|apakah saya vegan|makanan yang tidak (?:bisa|boleh) (?:saya|kumakan))\b""",
            RegexOption.IGNORE_CASE
        )
    }

    override val skillId: String = "personal_memory_vault"

    override val supportedPackages: List<String> = emptyList()

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        val clean = transcript.trim()
        return SAVE_DIRECTIVE_REGEX.containsMatchIn(clean) ||
            QUERY_PERSON_REGEX.containsMatchIn(clean) ||
            QUERY_WORK_REGEX.containsMatchIn(clean) ||
            QUERY_LOCATION_REGEX.containsMatchIn(clean) ||
            QUERY_SCHEDULE_REGEX.containsMatchIn(clean) ||
            QUERY_HEALTH_REGEX.containsMatchIn(clean) ||
            QUERY_PREFERENCE_REGEX.containsMatchIn(clean) ||
            QUERY_BIRTHDAY_REGEX.containsMatchIn(clean) ||
            QUERY_VEHICLE_REGEX.containsMatchIn(clean) ||
            QUERY_ROUTINE_REGEX.containsMatchIn(clean) ||
            QUERY_FOOD_DRINK_REGEX.containsMatchIn(clean) ||
            QUERY_PROFESSIONAL_REGEX.containsMatchIn(clean) ||
            QUERY_EDUCATION_REGEX.containsMatchIn(clean) ||
            QUERY_TECH_PROFILE_REGEX.containsMatchIn(clean) ||
            QUERY_PROFILE_ALL_REGEX.containsMatchIn(clean) ||
            QUERY_TIMELINE_REGEX.containsMatchIn(clean) ||
            QUERY_GENERAL_RECALL_REGEX.containsMatchIn(clean) ||
            QUERY_PET_REGEX.containsMatchIn(clean) ||
            QUERY_DEVICE_REGEX.containsMatchIn(clean) ||
            QUERY_PROJECT_REGEX.containsMatchIn(clean) ||
            QUERY_GOAL_REGEX.containsMatchIn(clean) ||
            QUERY_DIETARY_REGEX.containsMatchIn(clean)
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: PERSONAL MEMORY VAULT & TEMPORAL KNOWLEDGE GRAPH]
Ketika pengguna meminta mengingat/mencatat fakta personal atau menanyakan hal-hal yang pernah dicatat (nama keluarga, pekerjaan, tempat tinggal, alergi, preferensi, jadwal, dll), jawab langsung pada completion_tts dengan action "DONE" dalam < 15 ms.
        """.trimIndent()

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        val cleanGoal = userGoal.trim()
        if (!canHandleVoiceIntent(cleanGoal)) return null

        val lower = cleanGoal.lowercase()
        val speechCtx = speechContextProvider()

        // 1. Save Directive Intent
        if (SAVE_DIRECTIVE_REGEX.containsMatchIn(cleanGoal)) {
            val content = cleanGoal.replace(
                Regex("""^(?:tolong\s+)?(?:ingat(?:kan)?|catat|simpan)(?:\s+ya)?(?:\s+bahwa|\s+info)?\s*[:,]?\s*""", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (content.isNotBlank()) {
                val triple = TemporalKnowledgeGraph.extractTriple(factText = content, nowMs = System.currentTimeMillis())
                userMemoryManager.recordMemory(
                    category = triple.category,
                    fact = content,
                    subject = triple.subject,
                    predicate = triple.predicate,
                    objectValue = triple.objectValue,
                    source = "fast_path_skill"
                )
                userMemoryManager.inspectTranscriptForProfile(content)
                return ReActActionModel(
                    thought = "[Fast Path Memory] Menyimpan fakta personal ke graf memori lokal.",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE,
                    completionTts = SovereignNaturalSpeech.memorySaved(speechCtx, content)
                )
            }
        }

        // 2. Timeline / History Query Intent
        if (QUERY_TIMELINE_REGEX.containsMatchIn(cleanGoal)) {
            val effectiveTopic = when {
                lower.contains("kerja") || lower.contains("kantor") || lower.contains("profesi") -> "kerja"
                lower.contains("tinggal") || lower.contains("alamat") || lower.contains("rumah") || lower.contains("domisili") -> "tinggal"
                lower.contains("sekolah") || lower.contains("kuliah") || lower.contains("kampus") -> "sekolah"
                lower.contains("kesehatan") || lower.contains("alergi") || lower.contains("sakit") -> "kesehatan"
                else -> cleanGoal.replace(QUERY_TIMELINE_REGEX, "")
                    .replace(Regex("""\b(di mana|dimana|apa|siapa|kapan|berapa|ya|sih|kah)\b""", RegexOption.IGNORE_CASE), "")
                    .trim('?', ' ')
                    .ifBlank { "kerja" }
            }
            val timelineResponse = userMemoryManager.queryMemoryTimelineSync(effectiveTopic)
            return ReActActionModel(
                thought = "[Fast Path Memory] Mengambil riwayat memori temporal untuk topik '$effectiveTopic'.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = timelineResponse
            )
        }

        // 3. Today's Schedules / Agenda Query Intent
        if (QUERY_SCHEDULE_REGEX.containsMatchIn(cleanGoal)) {
            val schedules = userMemoryManager.getTodaySchedules()
            val speech = if (schedules.isEmpty()) {
                SovereignNaturalSpeech.memoryNoScheduleToday(speechCtx)
            } else {
                SovereignNaturalSpeech.memoryScheduleToday(
                    speechCtx,
                    schedules.joinToString("; ") { it.factText }
                )
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Mengambil agenda temporal hari ini.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        val activeFacts = userMemoryManager.getActiveTemporalFactsSync()
        val displayName = userMemoryManager.getUserDisplayName()

        // 4. Person Query Intent (siapa nama adik/istri/kakak/anak/teman/ayah/ibu/bos)
        if (QUERY_PERSON_REGEX.containsMatchIn(cleanGoal) && !QUERY_PET_REGEX.containsMatchIn(cleanGoal)) {
            val relation = when {
                lower.contains("adik") -> "adik"
                lower.contains("kakak") -> "kakak"
                lower.contains("istri") -> "istri"
                lower.contains("suami") -> "suami"
                lower.contains("anak") -> "anak"
                lower.contains("ayah") || lower.contains("bapak") -> "ayah"
                lower.contains("ibu") || lower.contains("mama") -> "ibu"
                lower.contains("teman") || lower.contains("sahabat") -> "teman"
                lower.contains("bos") || lower.contains("atasan") -> "bos"
                else -> ""
            }

            if (relation.isNotBlank()) {
                val matchingFacts = activeFacts.filter {
                    it.factText.contains(relation, ignoreCase = true) ||
                        it.subject.contains(relation, ignoreCase = true) ||
                        (it.category.equals("family", ignoreCase = true) && it.factText.contains(relation, ignoreCase = true)) ||
                        (it.category.equals("contact", ignoreCase = true) && it.factText.contains(relation, ignoreCase = true))
                }

                val speech = if (matchingFacts.isNotEmpty()) {
                    SovereignNaturalSpeech.memoryRememberedFact(
                        speechCtx,
                        formatFactForWarmSpeech(matchingFacts.first().factText)
                    )
                } else {
                    SovereignNaturalSpeech.memoryMissingRelation(speechCtx, relation)
                }

                return ReActActionModel(
                    thought = "[Fast Path Memory] Menjawab pertanyaan relasi personal ($relation).",
                    reflectionOnLastStep = ReflectionStatus.INITIAL,
                    action = AgentActionType.DONE,
                    completionTts = speech
                )
            }
        }

        // 5. Work Query Intent
        if (QUERY_WORK_REGEX.containsMatchIn(cleanGoal)) {
            val workFacts = activeFacts.filter {
                it.category.equals("work", ignoreCase = true) ||
                    it.subject.contains("work", ignoreCase = true) ||
                    it.predicate == "works_at"
            }
            val speech = if (workFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(workFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingWork(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan pekerjaan pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 6. Location Query Intent
        if (QUERY_LOCATION_REGEX.containsMatchIn(cleanGoal)) {
            val locFacts = activeFacts.filter {
                it.category.equals("location", ignoreCase = true) ||
                    it.subject.contains("location", ignoreCase = true) ||
                    it.predicate == "lives_in"
            }
            val speech = if (locFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(locFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingLocation(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan lokasi tempat tinggal.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 7. Health / Allergy Query Intent
        if (QUERY_HEALTH_REGEX.containsMatchIn(cleanGoal)) {
            val healthFacts = activeFacts.filter {
                it.category.equals("health", ignoreCase = true) ||
                    it.subject.contains("health", ignoreCase = true) ||
                    it.predicate == "allergic_to"
            }
            val speech = if (healthFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryHealthList(
                    speechCtx,
                    healthFacts.joinToString("; ") { formatFactForWarmSpeech(it.factText) }
                )
            } else {
                SovereignNaturalSpeech.memoryMissingHealth(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan kesehatan & alergi.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 8. Birthday Query Intent
        if (QUERY_BIRTHDAY_REGEX.containsMatchIn(cleanGoal)) {
            val bdayFacts = activeFacts.filter {
                it.category.equals("birthday", ignoreCase = true) ||
                    it.predicate == "birthday_on" ||
                    it.factText.contains("ulang tahun", ignoreCase = true)
            }
            val speech = if (bdayFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryBirthday(
                    speechCtx,
                    formatFactForWarmSpeech(bdayFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingBirthday(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan ulang tahun pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 9. Vehicle & Plate Number Query Intent
        if (QUERY_VEHICLE_REGEX.containsMatchIn(cleanGoal)) {
            val vehFacts = activeFacts.filter {
                it.category.equals("vehicle", ignoreCase = true) ||
                    it.predicate in setOf("plate_number", "vehicle_model") ||
                    it.factText.contains("plat", ignoreCase = true) ||
                    it.factText.contains("kendaraan", ignoreCase = true)
            }
            val speech = if (vehFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(vehFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingVehicle(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan plat atau kendaraan pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 10. Routine & Daily Schedule Query Intent
        if (QUERY_ROUTINE_REGEX.containsMatchIn(cleanGoal)) {
            val routineFacts = activeFacts.filter {
                it.category.equals("routine", ignoreCase = true) ||
                    it.predicate in setOf("wakes_at", "has_habit") ||
                    it.factText.contains("bangun", ignoreCase = true)
            }
            val speech = if (routineFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(routineFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingRoutine(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan rutinitas harian pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 11. Food & Drink Query Intent
        if (QUERY_FOOD_DRINK_REGEX.containsMatchIn(cleanGoal)) {
            val foodFacts = activeFacts.filter {
                it.predicate in setOf("favorite_drink", "favorite_food") ||
                    it.factText.contains("kopi", ignoreCase = true) ||
                    it.factText.contains("favorit", ignoreCase = true)
            }
            val speech = if (foodFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(foodFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingFoodDrink(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan kuliner & minuman favorit.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 12. Professional & Boss Query Intent
        if (QUERY_PROFESSIONAL_REGEX.containsMatchIn(cleanGoal)) {
            val bossFacts = activeFacts.filter {
                it.category.equals("contact", ignoreCase = true) ||
                    it.subject == "social_boss" ||
                    it.predicate == "boss_named" ||
                    it.factText.contains("bos", ignoreCase = true) ||
                    it.factText.contains("atasan", ignoreCase = true)
            }
            val speech = if (bossFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(bossFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingBoss(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan kontak profesional/atasan.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 13. Preference Query Intent
        if (QUERY_PREFERENCE_REGEX.containsMatchIn(cleanGoal)) {
            val prefFacts = activeFacts.filter {
                it.category.equals("preference", ignoreCase = true) ||
                    it.subject.contains("pref", ignoreCase = true) ||
                    it.predicate in setOf("likes", "dislikes", "favorite_drink", "favorite_food")
            }
            val speech = if (prefFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryPrefList(
                    speechCtx,
                    prefFacts.joinToString("; ") { formatFactForWarmSpeech(it.factText) }
                )
            } else {
                SovereignNaturalSpeech.memoryMissingPref(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan preferensi & hobi.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 14. Education & Campus Intent
        if (QUERY_EDUCATION_REGEX.containsMatchIn(cleanGoal)) {
            val eduFacts = activeFacts.filter {
                it.category.equals("education", ignoreCase = true) ||
                    it.predicate in setOf("majoring_in", "studies_at") ||
                    it.subject in setOf("academic_major", "academic_campus") ||
                    it.factText.contains("mahasiswa", ignoreCase = true) ||
                    it.factText.contains("kuliah", ignoreCase = true)
            }
            val speech = if (eduFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryEduFact(
                    speechCtx,
                    formatFactForWarmSpeech(eduFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingEdu(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan pendidikan & jurusan.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 15. Tech Profile & AI Research Intent
        if (QUERY_TECH_PROFILE_REGEX.containsMatchIn(cleanGoal)) {
            val techFacts = activeFacts.filter {
                it.category.equals("tech_profile", ignoreCase = true) ||
                    it.predicate in setOf("researches_in", "favorite_tech", "specializes_in", "builds_with") ||
                    it.subject in setOf("tech_interest", "tech_stack", "coding_focus", "rag_stack") ||
                    it.factText.contains("riset", ignoreCase = true) ||
                    it.factText.contains("skripsi", ignoreCase = true) ||
                    it.factText.contains("koding", ignoreCase = true) ||
                    it.factText.contains("coding", ignoreCase = true) ||
                    it.factText.contains("tech stack", ignoreCase = true)
            }
            val speech = if (techFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(techFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingTechProfile(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan minat riset AI/ML dan stack koding.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 16. Full Profile / Rangkum Tentang Saya Intent
        if (QUERY_PROFILE_ALL_REGEX.containsMatchIn(cleanGoal)) {
            val factsJoined = if (activeFacts.isNotEmpty()) {
                activeFacts.take(8).joinToString("; ") { formatFactForWarmSpeech(it.factText) }
            } else {
                null
            }
            val summary = SovereignNaturalSpeech.memoryProfileSummary(
                ctx = speechCtx,
                displayName = displayName,
                factsJoined = factsJoined
            )
            return ReActActionModel(
                thought = "[Fast Path Memory] Memberikan rangkuman memori profil pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = summary
            )
        }

        // 16b. Pet Query Intent
        if (QUERY_PET_REGEX.containsMatchIn(cleanGoal)) {
            val petFacts = activeFacts.filter {
                it.category.equals("pet", ignoreCase = true) ||
                    it.predicate.contains("pet") ||
                    it.subject.startsWith("pet_") ||
                    it.factText.contains("kucing", ignoreCase = true) ||
                    it.factText.contains("anjing", ignoreCase = true)
            }
            val speech = if (petFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(petFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingPet(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan hewan peliharaan pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 16c. Device / Laptop / HP Query Intent
        if (QUERY_DEVICE_REGEX.containsMatchIn(cleanGoal)) {
            val devFacts = activeFacts.filter {
                it.category.equals("device", ignoreCase = true) ||
                    it.predicate in setOf("laptop_model", "phone_model", "device_model") ||
                    it.subject.startsWith("device_") ||
                    it.factText.contains("laptop", ignoreCase = true) ||
                    it.factText.contains("hp", ignoreCase = true)
            }
            val speech = if (devFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(devFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingDevice(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan perangkat/gadget pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 16d. Project / Startup Intent
        if (QUERY_PROJECT_REGEX.containsMatchIn(cleanGoal)) {
            val projFacts = activeFacts.filter {
                it.category.equals("project", ignoreCase = true) ||
                    it.predicate == "working_on_project" ||
                    it.subject == "user_project" ||
                    it.factText.contains("proyek", ignoreCase = true) ||
                    it.factText.contains("startup", ignoreCase = true)
            }
            val speech = if (projFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(projFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingProject(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan proyek/startup pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 16e. Goal / Cita-cita Intent
        if (QUERY_GOAL_REGEX.containsMatchIn(cleanGoal)) {
            val goalFacts = activeFacts.filter {
                it.category.equals("goal", ignoreCase = true) ||
                    it.predicate == "has_goal" ||
                    it.subject == "user_goal" ||
                    it.factText.contains("cita-cita", ignoreCase = true) ||
                    it.factText.contains("impian", ignoreCase = true)
            }
            val speech = if (goalFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryGoalWithSpirit(
                    speechCtx,
                    formatFactForWarmSpeech(goalFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingGoal(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan cita-cita/impian pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 16f. Dietary Restrictions Intent
        if (QUERY_DIETARY_REGEX.containsMatchIn(cleanGoal)) {
            val dietFacts = activeFacts.filter {
                it.category.equals("dietary", ignoreCase = true) ||
                    it.predicate == "dietary_preference" ||
                    it.subject == "user_dietary" ||
                    it.factText.contains("vegetarian", ignoreCase = true) ||
                    it.factText.contains("pantangan makan", ignoreCase = true)
            }
            val speech = if (dietFacts.isNotEmpty()) {
                SovereignNaturalSpeech.memoryRememberedFact(
                    speechCtx,
                    formatFactForWarmSpeech(dietFacts.first().factText)
                )
            } else {
                SovereignNaturalSpeech.memoryMissingDietary(speechCtx)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan pantangan diet pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        // 17. General Recall ("kamu ingat gak X")
        if (QUERY_GENERAL_RECALL_REGEX.containsMatchIn(cleanGoal)) {
            val queryCore = cleanGoal.replace(QUERY_GENERAL_RECALL_REGEX, "").trim('?', ' ')
            val ranked = TemporalKnowledgeGraph.rankRelevantFacts(
                facts = activeFacts,
                query = queryCore,
                maxFacts = 3
            )
            val speech = if (ranked.isNotEmpty()) {
                SovereignNaturalSpeech.memoryGeneralRecall(
                    speechCtx,
                    ranked.joinToString("; ") { formatFactForWarmSpeech(it.factText) }
                )
            } else {
                SovereignNaturalSpeech.memoryGeneralMissing(speechCtx, queryCore)
            }
            return ReActActionModel(
                thought = "[Fast Path Memory] Menjawab pertanyaan ingatan umum pengguna.",
                reflectionOnLastStep = ReflectionStatus.INITIAL,
                action = AgentActionType.DONE,
                completionTts = speech
            )
        }

        return null
    }

    private fun formatFactForWarmSpeech(factText: String): String {
        return factText
            .replace("Pengguna bekerja di ", "Kamu bekerja di ")
            .replace("pengguna bekerja di ", "kamu bekerja di ")
            .replace("Pengguna tinggal di ", "Kamu tinggal di ")
            .replace("pengguna tinggal di ", "kamu tinggal di ")
            .replace("Pengguna alergi ", "Kamu alergi ")
            .replace("pengguna alergi ", "kamu alergi ")
            .replace("Pengguna suka ", "Kamu suka ")
            .replace("pengguna suka ", "kamu suka ")
            .replace("Pengguna tidak suka ", "Kamu tidak suka ")
            .replace("pengguna tidak suka ", "kamu tidak suka ")
            .replace("Pengguna biasa bangun jam ", "Kamu biasa bangun jam ")
            .replace("pengguna biasa bangun jam ", "kamu biasa bangun jam ")
            .replace("Ulang tahun pengguna tanggal ", "Ulang tahunmu tanggal ")
            .replace("ulang tahun pengguna tanggal ", "ulang tahunmu tanggal ")
            .replace("Plat motor pengguna ", "Plat motor kamu ")
            .replace("plat motor pengguna ", "plat motor kamu ")
            .replace("Plat nomor kendaraan pengguna ", "Plat nomor kendaraan kamu ")
            .replace("Kendaraan pengguna ", "Kendaraan kamu ")
            .replace("Minuman favorit pengguna: ", "Minuman favoritmu itu ")
            .replace("Makanan favorit pengguna: ", "Makanan kesukaanmu itu ")
            .replace("Pengguna mahasiswa ", "Kamu mahasiswa ")
            .replace("pengguna mahasiswa ", "kamu mahasiswa ")
            .replace("Pengguna kuliah di ", "Kamu kuliah di ")
            .replace("pengguna kuliah di ", "kamu kuliah di ")
            .replace("Fokus riset AI pengguna: ", "Fokus riset AI kamu di ")
            .replace("fokus riset AI pengguna: ", "fokus riset AI kamu di ")
            .replace("Tech stack favorit pengguna: ", "Tech stack favoritmu itu ")
            .replace("tech stack favorit pengguna: ", "tech stack favoritmu itu ")
            .replace("Fokus koding & tech stack pengguna: ", "Fokus koding kamu di ")
            .replace("fokus koding & tech stack pengguna: ", "fokus koding kamu di ")
            .replace("Adik pengguna bernama ", "Adik kamu bernama ")
            .replace("Kakak pengguna bernama ", "Kakak kamu bernama ")
            .replace("Istri pengguna bernama ", "Istri kamu bernama ")
            .replace("Suami pengguna bernama ", "Suami kamu bernama ")
            .replace("Anak pengguna bernama ", "Anak kamu bernama ")
            .replace("Ayah pengguna bernama ", "Ayah kamu bernama ")
            .replace("Ibu pengguna bernama ", "Ibu kamu bernama ")
            .replace("Teman pengguna bernama ", "Teman kamu bernama ")
            .replace("Bos pengguna bernama ", "Bos kamu bernama ")
            .replace("Atasan pengguna bernama ", "Atasan kamu bernama ")
            .replace("Kucing pengguna bernama ", "Kucing kamu bernama ")
            .replace("Anjing pengguna bernama ", "Anjing kamu bernama ")
            .replace("Peliharaan pengguna bernama ", "Peliharaan kamu bernama ")
            .replace("Laptop pengguna: ", "Laptop kamu itu ")
            .replace("HP pengguna: ", "HP kamu itu ")
            .replace("Proyek pengguna: ", "Proyek yang kamu bangun itu ")
            .replace("Cita-cita pengguna: ", "Cita-cita kamu itu ")
            .replace("Pantangan makan pengguna: ", "Pantangan makan kamu itu ")
            .replace("pengguna", "kamu")
            .replace("Pengguna", "Kamu")
            .trimEnd('.', ' ')
    }
}
