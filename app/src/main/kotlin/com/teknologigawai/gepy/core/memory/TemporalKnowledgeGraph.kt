package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Representasi triplet pengetahuan terstruktur `(subject, predicate, objectValue)`
 * beserta jendela masa berlaku temporal.
 */
data class TemporalTriple(
    val category: String,
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val validUntilMs: Long?,
    val isSingleValued: Boolean
)

/**
 * Mesin **Sovereign Temporal Knowledge Graph** (terinspirasi arsitektur Mem0 / Graphiti,
 * 100% lokal di perangkat tanpa server cloud):
 * 1. Mengekstrak triplet semantik `(subject, predicate, object_value)` dari fakta lisan Bahasa Indonesia.
 * 2. Mendeteksi batas kedaluwarsa alami (*"hari ini"*, *"besok"*, *"lusa"*, *"minggu ini"*, *"sementara"*).
 * 3. Mengelola resolusi kontradiksi lunak (*soft contradiction*): fakta baru menandai fakta lama
 *    sebagai riwayat historis (`isActive = false`, `supersededById = newId`) tanpa menghapus jejak masa lalu.
 * 4. Menyusun peringkat konteks berdasar relevansi token + *exponential time-decay* + prioritas agenda aktif.
 */
object TemporalKnowledgeGraph {

    private val INDONESIAN_LOCALE = Locale.forLanguageTag("id-ID")
    private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", INDONESIAN_LOCALE)
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", INDONESIAN_LOCALE)

    private val SINGLE_VALUED_PREDICATES = setOf(
        "is_named",
        "works_at",
        "lives_in",
        "father_named",
        "mother_named",
        "spouse_named",
        "birthday_on",
        "plate_number",
        "vehicle_model",
        "wakes_at",
        "boss_named",
        "favorite_drink",
        "favorite_food",
        "studies_at",
        "majoring_in",
        "researches_in",
        "favorite_tech",
        "specializes_in",
        "builds_with",
        "pet_named",
        "device_model",
        "laptop_model",
        "phone_model",
        "working_on_project",
        "has_goal",
        "dietary_preference"
    )

    private val SINGLE_VALUED_SUBJECTS = setOf(
        "user_name",
        "user_work",
        "profile_work",
        "location_home",
        "family_father",
        "family_mother",
        "family_spouse",
        "birthday_user",
        "vehicle_motorcycle",
        "vehicle_car",
        "routine_wake",
        "social_boss",
        "academic_major",
        "academic_campus",
        "tech_interest",
        "tech_stack",
        "coding_focus",
        "rag_stack",
        "pet_cat",
        "pet_dog",
        "device_laptop",
        "device_phone",
        "user_project",
        "user_goal",
        "user_dietary"
    )

    /**
     * Mengekstrak triplet semantik dan batas masa berlaku temporal dari fakta lisan [factText].
     */
    fun extractTriple(
        factText: String,
        rawCategory: String = "",
        rawSubject: String = "",
        rawPredicate: String = "",
        rawObjectValue: String = "",
        validHours: Int? = null,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TemporalTriple {
        val cleanFact = factText.trim()
        val lower = cleanFact.lowercase()

        val inferredCategory = normalizeCategory(rawCategory, lower)
        val inferredSubject = rawSubject.trim().lowercase().ifBlank {
            inferSubject(lower, inferredCategory)
        }
        val inferredPredicate = rawPredicate.trim().lowercase().replace(' ', '_').ifBlank {
            inferPredicate(lower, inferredSubject, inferredCategory)
        }
        val inferredObject = rawObjectValue.trim().ifBlank {
            extractObjectValue(cleanFact, inferredPredicate)
        }
        val validUntil = resolveValidUntilMs(
            lowerFact = lower,
            validHours = validHours,
            nowMs = nowMs,
            zoneId = zoneId
        )
        // Multi-valued integrity: Hanya jadikan isSingleValued jika subject DAN predicate benar-benar entitas tunggal.
        // Untuk family_siblings, social_friends, preferences, dll -> isSingleValued = false agar tidak saling menimpa!
        val isSingle = (inferredPredicate in SINGLE_VALUED_PREDICATES && inferredSubject in SINGLE_VALUED_SUBJECTS)

        return TemporalTriple(
            category = inferredCategory,
            subject = inferredSubject,
            predicate = inferredPredicate,
            objectValue = inferredObject,
            validUntilMs = validUntil,
            isSingleValued = isSingle
        )
    }

    /** Memeriksa apakah sebuah entitas fakta masih aktif dan belum melewati masa berlakunya. */
    fun isFactCurrentlyValid(
        entity: TemporalMemoryFactEntity,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!entity.isActive) return false
        val expiry = entity.validUntilMs
        return expiry == null || expiry > nowMs
    }

    /**
     * Memperingkatkan fakta aktif paling relevan terhadap [query] (atau daftar umum jika query kosong)
     * menggunakan pembobotan kecocokan kata kunci, dorongan fakta sementara/agenda dekat, dan peluruhan waktu.
     */
    fun rankRelevantFacts(
        facts: List<TemporalMemoryFactEntity>,
        query: String,
        nowMs: Long = System.currentTimeMillis(),
        maxFacts: Int = 10
    ): List<TemporalMemoryFactEntity> {
        val validFacts = facts.filter { isFactCurrentlyValid(it, nowMs) }
        if (validFacts.isEmpty()) return emptyList()

        val queryTokens = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

        return validFacts
            .map { entity ->
                val totalScore = EbbinghausDecayEngine.computeStanfordRetrievalScore(
                    entity = entity,
                    queryTokens = queryTokens,
                    nowMs = nowMs
                )
                entity to totalScore
            }
            .sortedWith(
                compareByDescending<Pair<TemporalMemoryFactEntity, Double>> { it.second }
                    .thenByDescending { it.first.updatedAtMs }
            )
            .take(maxFacts.coerceIn(1, 50))
            .map { it.first }
    }

    /** Mengambil agenda / catatan berbatas waktu yang berlaku pada hari ini (untuk Morning Briefing). */
    fun getTodaySchedules(
        facts: List<TemporalMemoryFactEntity>,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<TemporalMemoryFactEntity> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val endOfTodayMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        return facts.filter { entity ->
            if (!isFactCurrentlyValid(entity, nowMs)) return@filter false
            val isScheduleCategory = entity.category.equals("schedule", ignoreCase = true) ||
                entity.predicate == "has_schedule"
            val expiresSoon = entity.validUntilMs != null && entity.validUntilMs <= endOfTodayMs
            val createdToday = Instant.ofEpochMilli(entity.validFromMs).atZone(zoneId).toLocalDate() == today

            (isScheduleCategory && (entity.validUntilMs == null || entity.validUntilMs <= endOfTodayMs || createdToday)) ||
                expiresSoon
        }.sortedBy { it.validFromMs }
    }

    /**
     * Memformat riwayat perubahan (timeline) untuk menjawab pertanyaan historis seperti:
     * *"Dulu saya kerja di mana?"* atau *"Apa riwayat alamat rumah saya?"*
     */
    fun formatTimelineResponse(
        topic: String,
        matchingFacts: List<TemporalMemoryFactEntity>,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        if (matchingFacts.isEmpty()) {
            return "Belum ada catatan memori historis maupun aktif untuk topik '$topic'."
        }

        val currentActive = matchingFacts.filter { isFactCurrentlyValid(it, nowMs) }
            .sortedByDescending { it.updatedAtMs }
        val historical = matchingFacts.filter { !isFactCurrentlyValid(it, nowMs) }
            .sortedByDescending { it.createdAtMs }

        return buildString {
            if (currentActive.isNotEmpty()) {
                append("Fakta aktif saat ini: ")
                append(
                    currentActive.joinToString("; ") { entity ->
                        val dateStr = formatDate(entity.validFromMs, zoneId)
                        if (entity.validUntilMs != null) {
                            "${entity.factText} (berlaku s.d. ${formatDate(entity.validUntilMs, zoneId)} pukul ${formatTime(entity.validUntilMs, zoneId)})"
                        } else {
                            "${entity.factText} (dicatat $dateStr)"
                        }
                    }
                )
                append(". ")
            }
            if (historical.isNotEmpty()) {
                append("Riwayat sebelumnya: ")
                append(
                    historical.take(5).joinToString("; ") { entity ->
                        val endMs = entity.validUntilMs ?: entity.updatedAtMs
                        val statusTag = if (entity.supersededById != null) "digantikan fakta baru pada" else "berakhir pada"
                        "${entity.factText} ($statusTag ${formatDate(endMs, zoneId)})"
                    }
                )
                append(".")
            }
        }.trim()
    }

    fun formatDate(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        return SHORT_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(zoneId))
    }

    fun formatTime(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(zoneId))
    }

    private fun normalizeCategory(rawCategory: String, lowerFact: String): String {
        val raw = rawCategory.trim().lowercase()
        if (raw in setOf("profile", "preference", "family", "friends", "social", "location", "habits", "schedule", "work", "insight", "health", "birthday", "vehicle", "routine", "contact", "education", "tech_profile", "pet", "device", "project", "goal", "dietary")) {
            return if (raw == "social") "friends" else raw
        }
        return when {
            lowerFact.contains("kucing") || lowerFact.contains("anjing") ||
                lowerFact.contains("peliharaan") || lowerFact.contains("hamster") ||
                lowerFact.contains("kelinci") -> "pet"
            lowerFact.contains("laptop") || lowerFact.contains("macbook") ||
                lowerFact.contains("gadget") || lowerFact.contains("komputer") ||
                lowerFact.contains("pc gaming") -> "device"
            lowerFact.contains("proyek") || lowerFact.contains("project") ||
                lowerFact.contains("startup") || lowerFact.contains("skripsi") -> "project"
            lowerFact.contains("cita-cita") || lowerFact.contains("impian") ||
                lowerFact.contains("target hidup") || lowerFact.contains("resolusi") -> "goal"
            lowerFact.contains("vegetarian") || lowerFact.contains("vegan") ||
                lowerFact.contains("pantangan makan") || lowerFact.contains("tidak makan") -> "dietary"
            lowerFact.contains("rapat") || lowerFact.contains("meeting") ||
                lowerFact.contains("jadwal") || lowerFact.contains("agenda") ||
                lowerFact.contains("janji") || lowerFact.contains("ujian") ||
                lowerFact.contains("besok") || lowerFact.contains("hari ini") ||
                lowerFact.contains("lusa") -> "schedule"
            lowerFact.contains("ulang tahun") || lowerFact.contains("ultah") -> "birthday"
            lowerFact.contains("plat") || lowerFact.contains("motor") ||
                lowerFact.contains("mobil") || lowerFact.contains("kendaraan") -> "vehicle"
            lowerFact.contains("bangun jam") || lowerFact.contains("rutinitas") ||
                lowerFact.contains("kebiasaan") -> "routine"
            lowerFact.contains("atasan") || lowerFact.contains("bos") ||
                lowerFact.contains("manajer") || lowerFact.contains("dokter") -> "contact"
            lowerFact.contains("mahasiswa") || lowerFact.contains("kuliah") ||
                lowerFact.contains("jurusan") || lowerFact.contains("kampus") ||
                lowerFact.contains("universitas") -> "education"
            lowerFact.contains("riset") || lowerFact.contains("ai/ml") ||
                lowerFact.contains("agentic") || lowerFact.contains("skripsi") ||
                lowerFact.contains("tech stack") || lowerFact.contains("koding") ||
                lowerFact.contains("pemrograman") -> "tech_profile"
            lowerFact.contains("adik") || lowerFact.contains("kakak") ||
                lowerFact.contains("ayah") || lowerFact.contains("bapak") ||
                lowerFact.contains("ibu") || lowerFact.contains("orang tua") ||
                lowerFact.contains("keluarga") || lowerFact.contains("anak") ||
                lowerFact.contains("suami") || lowerFact.contains("istri") ||
                lowerFact.contains("sepupu") || lowerFact.contains("keponakan") ||
                lowerFact.contains("paman") || lowerFact.contains("bibi") ||
                lowerFact.contains("mertua") || lowerFact.contains("ipar") -> "family"
            lowerFact.contains("teman") || lowerFact.contains("sahabat") ||
                lowerFact.contains("kawan") || lowerFact.contains("sobat") ||
                lowerFact.contains("pacar") || lowerFact.contains("tunangan") ||
                lowerFact.contains("rekan") || lowerFact.contains("atasan") ||
                lowerFact.contains("bos") -> "friends"
            lowerFact.contains("alergi") || lowerFact.contains("sakit") ||
                lowerFact.contains("penyakit") || lowerFact.contains("obat") ||
                lowerFact.contains("pantangan") || lowerFact.contains("kesehatan") -> "health"
            lowerFact.contains("bekerja") || lowerFact.contains("kerja di") ||
                lowerFact.contains("kantor") || lowerFact.contains("perusahaan") ||
                lowerFact.contains("pekerjaan") || lowerFact.contains("profesi") -> "work"
            lowerFact.contains("tinggal di") || lowerFact.contains("domisili") ||
                lowerFact.contains("alamat") || lowerFact.contains("rumah") ||
                lowerFact.contains("pindah ke") -> "location"
            lowerFact.contains("suka") || lowerFact.contains("hobi") ||
                lowerFact.contains("gemar") || lowerFact.contains("favorit") -> "preference"
            lowerFact.contains("nama") || lowerFact.contains("umur") ||
                lowerFact.contains("usia") -> "profile"
            else -> if (raw.isNotBlank()) raw else "general"
        }
    }

    private fun inferSubject(lowerFact: String, category: String): String {
        return when {
            lowerFact.contains("nama pengguna") || lowerFact.contains("namaku") ||
                lowerFact.contains("nama saya") || lowerFact.contains("panggil aku") || lowerFact.contains("panggil saya") -> "user_name"
            lowerFact.contains("bekerja") || lowerFact.contains("kerja di") ||
                lowerFact.contains("kantor") || lowerFact.contains("pekerjaan") ||
                lowerFact.contains("profesi") -> "user_work"
            lowerFact.contains("tinggal di") || lowerFact.contains("alamat") ||
                lowerFact.contains("rumah") || lowerFact.contains("domisili") ||
                lowerFact.contains("pindah ke") -> "location_home"
            lowerFact.contains("ayah") || lowerFact.contains("bapak") -> "family_father"
            lowerFact.contains("ibu") || lowerFact.contains("mama") || lowerFact.contains("bunda") -> "family_mother"
            lowerFact.contains("adik") -> "family_siblings"
            lowerFact.contains("kakak") -> "family_older_siblings"
            lowerFact.contains("anak") -> "family_children"
            lowerFact.contains("suami") || lowerFact.contains("istri") || lowerFact.contains("pasangan") -> "family_spouse"
            lowerFact.contains("orang tua") -> "family_parents"
            lowerFact.contains("teman") || lowerFact.contains("sahabat") ||
                lowerFact.contains("kawan") || lowerFact.contains("sobat") ||
                lowerFact.contains("pacar") || lowerFact.contains("rekan") ||
                category == "friends" || category == "social" -> "social_friends"
            category == "schedule" -> "schedule_agenda"
            category == "preference" -> "user_preference"
            category == "health" -> "user_health"
            lowerFact.contains("ulang tahun") || lowerFact.contains("ultah") || category == "birthday" -> "birthday_user"
            lowerFact.contains("plat motor") || (category == "vehicle" && lowerFact.contains("motor")) -> "vehicle_motorcycle"
            lowerFact.contains("plat mobil") || (category == "vehicle" && lowerFact.contains("mobil")) -> "vehicle_car"
            lowerFact.contains("bangun jam") || lowerFact.contains("bangun pagi") || category == "routine" -> "routine_wake"
            lowerFact.contains("bos") || lowerFact.contains("atasan") || category == "contact" -> "social_boss"
            lowerFact.contains("kopi") || lowerFact.contains("minuman") -> "user_preference"
            lowerFact.contains("makanan") -> "user_preference"
            lowerFact.contains("jurusan") || lowerFact.contains("mahasiswa") -> "academic_major"
            lowerFact.contains("kampus") || lowerFact.contains("kuliah di") || lowerFact.contains("universitas") -> "academic_campus"
            lowerFact.contains("riset") || lowerFact.contains("minat") || lowerFact.contains("skripsi") -> "tech_interest"
            lowerFact.contains("koding") || lowerFact.contains("coding") || lowerFact.contains("langgraph") || lowerFact.contains("fastapi") || lowerFact.contains("vector") || lowerFact.contains("rag") -> "coding_focus"
            lowerFact.contains("bahasa") || lowerFact.contains("framework") || lowerFact.contains("tech stack") -> "tech_stack"
            lowerFact.contains("kucing") -> "pet_cat"
            lowerFact.contains("anjing") -> "pet_dog"
            category == "pet" -> "pet_animal"
            lowerFact.contains("laptop") || lowerFact.contains("macbook") -> "device_laptop"
            lowerFact.contains("hp") || lowerFact.contains("ponsel") || lowerFact.contains("smartphone") -> "device_phone"
            category == "device" -> "device_gadget"
            category == "project" || lowerFact.contains("proyek") || lowerFact.contains("startup") || lowerFact.contains("project") -> "user_project"
            category == "goal" || lowerFact.contains("cita-cita") || lowerFact.contains("impian") -> "user_goal"
            category == "dietary" || lowerFact.contains("vegetarian") || lowerFact.contains("vegan") -> "user_dietary"
            else -> category
        }
    }

    private fun inferPredicate(lowerFact: String, subject: String, category: String): String {
        return when {
            subject == "user_name" -> "is_named"
            subject == "user_work" || subject == "profile_work" ||
                lowerFact.contains("kerja di") || lowerFact.contains("bekerja di") -> "works_at"
            subject == "location_home" || lowerFact.contains("tinggal di") ||
                lowerFact.contains("pindah ke") -> "lives_in"
            subject == "family_father" -> "father_named"
            subject == "family_mother" -> "mother_named"
            subject == "family_spouse" -> "spouse_named"
            subject == "family_siblings" || subject == "family_older_siblings" || subject == "family_children" -> "has_family_member"
            subject == "social_friends" || category == "friends" || category == "social" -> "has_friend"
            category == "schedule" || lowerFact.contains("rapat") ||
                lowerFact.contains("jadwal") || lowerFact.contains("agenda") -> "has_schedule"
            lowerFact.contains("alergi") -> "allergic_to"
            lowerFact.contains("tidak suka") || lowerFact.contains("benci") -> "dislikes"
            lowerFact.contains("suka") || lowerFact.contains("gemar") || lowerFact.contains("favorit") -> "likes"
            lowerFact.contains("ulang tahun") || lowerFact.contains("ultah") || category == "birthday" -> "birthday_on"
            lowerFact.contains("plat") -> "plate_number"
            lowerFact.contains("bangun") -> "wakes_at"
            lowerFact.contains("bos") || lowerFact.contains("atasan") -> "boss_named"
            lowerFact.contains("kopi") || lowerFact.contains("minuman") -> "favorite_drink"
            lowerFact.contains("makanan") -> "favorite_food"
            lowerFact.contains("kuliah di") || lowerFact.contains("kampus") -> "studies_at"
            lowerFact.contains("jurusan") || lowerFact.contains("mahasiswa") -> "majoring_in"
            lowerFact.contains("riset") || lowerFact.contains("skripsi") || lowerFact.contains("minat") -> "researches_in"
            lowerFact.contains("koding") || lowerFact.contains("coding") || lowerFact.contains("ngoding") -> "specializes_in"
            lowerFact.contains("bahasa") || lowerFact.contains("framework") || lowerFact.contains("tech stack") -> "favorite_tech"
            category == "pet" || lowerFact.contains("kucing") || lowerFact.contains("anjing") || lowerFact.contains("peliharaan") -> "pet_named"
            lowerFact.contains("laptop") || lowerFact.contains("macbook") -> "laptop_model"
            lowerFact.contains("hp") || lowerFact.contains("ponsel") -> "phone_model"
            category == "device" -> "device_model"
            category == "project" -> "working_on_project"
            category == "goal" -> "has_goal"
            category == "dietary" -> "dietary_preference"
            else -> "noted_fact"
        }
    }

    private fun extractObjectValue(cleanFact: String, predicate: String): String {
        val markerRegex = when (predicate) {
            "is_named", "father_named", "mother_named", "spouse_named" ->
                Regex("""(?:adalah|bernama|namaku|nama saya|nama ayah|nama ibu|nama istri|nama suami|namanya)\s+(.+)$""", RegexOption.IGNORE_CASE)
            "has_family_member" ->
                Regex("""(?:adik|kakak|anak|saudara|keluarga)(?:ku| saya)?\s*(?:bernama|namanya|adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "has_friend" ->
                Regex("""(?:teman|sahabat|kawan|sobat|pacar)(?:ku| saya)?\s*(?:bernama|namanya|adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "works_at" -> Regex("""(?:kerja di|bekerja di|kantor di|bekerja sebagai|di)\s+(.+)$""", RegexOption.IGNORE_CASE)
            "lives_in" -> Regex("""(?:tinggal di|domisili di|pindah ke|alamat di|di)\s+(.+)$""", RegexOption.IGNORE_CASE)
            "allergic_to" -> Regex("""alergi\s+(.+)$""", RegexOption.IGNORE_CASE)
            "dislikes" -> Regex("""(?:tidak suka|benci)\s+(.+)$""", RegexOption.IGNORE_CASE)
            "likes" -> Regex("""(?:suka|gemar|menyukai|favorit(?:nya)?(?: adalah)?)\s+(.+)$""", RegexOption.IGNORE_CASE)
            "birthday_on" -> Regex("""(?:ulang tahun|ultah)(?:ku| saya)?\s*(?:tanggal|pada|adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "plate_number" -> Regex("""(?:plat|nomor plat)(?: motor| mobil)?(?:ku| saya)?\s*(?:adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "wakes_at" -> Regex("""(?:bangun|bangun pagi|bangun jam)\s*(?:pada|jam|pukul)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "boss_named" -> Regex("""(?:bos|atasan|manajer)(?:ku| saya)?\s*(?:namanya|bernama|adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "favorite_drink", "favorite_food" -> Regex("""(?:favorit|kesukaan)(?:ku| saya)?\s*(?:adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "studies_at" -> Regex("""(?:kuliah di|kampus di|universitas)\s+(.+)$""", RegexOption.IGNORE_CASE)
            "majoring_in" -> Regex("""(?:mahasiswa|jurusan|prodi|program studi)\s+(.+)$""", RegexOption.IGNORE_CASE)
            "researches_in" -> Regex("""(?:riset|minat|fokus|skripsi|meneliti)(?: tentang| pada| di| adalah)?\s+(.+)$""", RegexOption.IGNORE_CASE)
            "favorite_tech", "specializes_in", "builds_with" ->
                Regex("""(?:favorit|tech stack|bahasa|koding|coding|ngoding|fokus|spesialisasi)(?: di| pada| adalah)?\s+(.+)$""", RegexOption.IGNORE_CASE)
            "pet_named" -> Regex("""(?:kucing|anjing|peliharaan|kelinci|hamster)(?:ku| saya)?\s*(?:namanya|bernama|adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "laptop_model", "phone_model", "device_model" -> Regex("""(?:laptop|hp|ponsel|komputer|gadget|macbook)(?:ku| saya)?\s*(?:merk(?:nya)?|adalah|yaitu)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "working_on_project" -> Regex("""(?:proyek|project|startup|aplikasi)(?:ku| saya)?\s*(?:namanya|bernama|adalah|tentang)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "has_goal" -> Regex("""(?:cita-cita|target|impian|resolusi)(?:ku| saya)?\s*(?:adalah|yaitu)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            "dietary_preference" -> Regex("""(?:pantangan|diet|tidak makan|alergi)(?:ku| saya)?\s*(?:adalah)?\s*(.+)$""", RegexOption.IGNORE_CASE)
            else -> null
        }
        val matched = markerRegex?.find(cleanFact)?.groupValues?.getOrNull(1)?.trim()
        return if (!matched.isNullOrBlank()) matched.trimEnd('.', '!') else cleanFact
    }

    /**
     * Menghitung stempel waktu kedaluwarsa (`validUntilMs`) berdasarkan parameter eksplisit `validHours`
     * atau kata kunci penanda waktu alami Bahasa Indonesia.
     */
    fun resolveValidUntilMs(
        lowerFact: String,
        validHours: Int?,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (validHours != null && validHours > 0) {
            return nowMs + (validHours.toLong() * 3_600_000L)
        }
        val currentZoned = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        val today = currentZoned.toLocalDate()

        return when {
            lowerFact.contains("hari ini") || lowerFact.contains("pagi ini") ||
                lowerFact.contains("siang ini") || lowerFact.contains("sore ini") ||
                lowerFact.contains("nanti malam") || lowerFact.contains("malam ini") -> {
                val endToday = today.atTime(23, 59, 59).atZone(zoneId).toInstant().toEpochMilli()
                if (endToday > nowMs + 3_600_000L) endToday else nowMs + 6 * 3_600_000L
            }
            lowerFact.contains("besok") -> {
                today.plusDays(1).atTime(23, 59, 59).atZone(zoneId).toInstant().toEpochMilli()
            }
            lowerFact.contains("lusa") -> {
                today.plusDays(2).atTime(23, 59, 59).atZone(zoneId).toInstant().toEpochMilli()
            }
            lowerFact.contains("minggu ini") -> {
                nowMs + 7L * 86_400_000L
            }
            lowerFact.contains("minggu depan") -> {
                nowMs + 14L * 86_400_000L
            }
            lowerFact.contains("bulan ini") -> {
                nowMs + 30L * 86_400_000L
            }
            lowerFact.contains("sementara") || lowerFact.contains("lagi lembur") ||
                lowerFact.contains("sedang sakit") || lowerFact.contains("lagi sakit") -> {
                nowMs + 24L * 3_600_000L
            }
            else -> null
        }
    }
}
