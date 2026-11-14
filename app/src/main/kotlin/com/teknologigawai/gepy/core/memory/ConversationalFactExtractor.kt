package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.logging.GepyLog

/**
 * Ekstraktor fakta percakapan bahasa Indonesia & pengklasifikasi entitas/subjek untuk [UserMemoryManager].
 */
internal interface MemoryFactRecorder {
    fun record(
        category: String,
        fact: String,
        subject: String = "",
        predicate: String = "",
        objectValue: String = "",
        validHours: Int? = null,
        source: String = "voice"
    )
}

internal class ConversationalFactExtractor(
    private val clockMsProvider: () -> Long,
    private val recorder: MemoryFactRecorder
) {
    private companion object {
        private const val TAG = "UserMemoryManager"

        val DIRECTIVE_SAVE_REGEX = Regex(
            """^(?:tolong\s+)?(?:ingat(?:kan)?|catat|simpan)(?:\s+ya)?(?:\s+bahwa|\s+info)?\s*[:,]?\s*(.+)""",
            RegexOption.IGNORE_CASE
        )

        val FAMILY_PATTERNS = listOf(
            Regex("""\b(adik|kakak|istri|suami|anak|ayah|bapak|ibu|mama)(?:ku| saya)?\s+(?:namanya|bernama|adalah)\s+([A-Za-z]{3,20}(?:\s+[A-Za-z]{3,20})?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bnama\s+(adik|kakak|istri|suami|anak|ayah|bapak|ibu|mama)(?:ku| saya)\s+(?:adalah\s+)?([A-Za-z]{3,20}(?:\s+[A-Za-z]{3,20})?)\b""", RegexOption.IGNORE_CASE)
        )

        val FRIEND_PATTERNS = listOf(
            Regex("""\b(teman|sahabat|kawan|sobat)(?:ku| saya)?\s+(?:namanya|bernama|adalah)\s+([A-Za-z]{3,20}(?:\s+[A-Za-z]{3,20})?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bnama\s+(teman|sahabat|kawan|sobat)(?:ku| saya)\s+(?:adalah\s+)?([A-Za-z]{3,20}(?:\s+[A-Za-z]{3,20})?)\b""", RegexOption.IGNORE_CASE)
        )

        val WORK_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+(?:bekerja|kerja)\s+di\s+([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bkantor(?:ku| saya)?\s+(?:ada\s+)?di\s+([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:pekerjaan|profesi)(?:ku| saya)?\s+(?:adalah|sebagai)?\s*([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val LOCATION_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+(?:tinggal|domisili)\s+di\s+([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:alamat|rumah)(?:ku| saya)?\s+(?:di|ada di|berada di)\s+([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val HEALTH_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+alergi\s+([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bpantangan\s+(?:makanan\s+)?(?:ku|saya)?\s+(?:adalah\s+)?([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val PREF_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+(?:suka|gemar|hobi)\s+([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:hobi|kesukaan)(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val DISLIKE_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+(?:tidak suka|benci)\s+([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val SCHEDULE_PATTERN = Regex(
            """\b(besok|lusa|hari ini|nanti sore|nanti malam)\s+(?:ada\s+)?(?:jadwal|rapat|meeting|agenda|janji)\s+([A-Za-z0-9\s.,-]{3,50})\b""",
            RegexOption.IGNORE_CASE
        )

        val BIRTHDAY_PATTERNS = listOf(
            Regex("""\bulang\s+tahun(?:ku| saya)?\s+(?:tanggal\s+|pada\s+|adalah\s+)?([0-9]{1,2}\s+[A-Za-z]+(?:\s+[0-9]{4})?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bhari\s+lahir(?:ku| saya)?\s+(?:tanggal\s+|pada\s+|adalah\s+)?([0-9]{1,2}\s+[A-Za-z]+(?:\s+[0-9]{4})?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bulang\s+tahun\s+(adik|kakak|istri|suami|anak|ibu|ayah|mama|bapak)(?:ku| saya)?\s+(?:tanggal\s+|pada\s+|adalah\s+)?([0-9]{1,2}\s+[A-Za-z]+(?:\s+[0-9]{4})?)\b""", RegexOption.IGNORE_CASE)
        )

        val VEHICLE_PATTERNS = listOf(
            Regex("""\bplat\s+(?:nomor\s+)?(?:motor|mobil|kendaraan)?(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z]{1,2}\s+[0-9]{1,4}\s+[A-Za-z]{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:nomor\s+)?plat(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z]{1,2}\s+[0-9]{1,4}\s+[A-Za-z]{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:motor|mobil)(?:ku| saya)\s+(?:adalah\s+)?([A-Za-z0-9\s-]{3,30})\b""", RegexOption.IGNORE_CASE)
        )

        val ROUTINE_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+(?:biasa|kebiasaan|rutin)\s+bangun\s+(?:jam|pukul)?\s*([0-9]{1,2}(?::[0-9]{2})?(?:\s+(?:pagi|subuh))?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:kebiasaan|rutinitas)(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z0-9\s.,-]{3,50})\b""", RegexOption.IGNORE_CASE)
        )

        val FOOD_DRINK_PATTERNS = listOf(
            Regex("""\b(?:kopi|minuman|makanan)\s+favorit(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:makanan|minuman)\s+kesukaan(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:saya|aku)\s+paling\s+suka\s+(?:makan|minum|ngopi)\s+([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val PROFESSIONAL_CONTACT_PATTERNS = listOf(
            Regex("""\b(bos|atasan|manajer|supervisor|dokter\s+gigi|dokter)(?:ku| saya)?\s+(?:namanya|bernama|adalah)\s+([A-Za-z.\s]{3,30})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bnama\s+(bos|atasan|manajer|supervisor|dokter\s+gigi|dokter)(?:ku| saya)\s+(?:adalah\s+)?([A-Za-z.\s]{3,30})\b""", RegexOption.IGNORE_CASE)
        )

        val EDUCATION_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+(?:adalah\s+)?(?:mahasiswa|kuliah\s+di|kuliah\s+jurusan)\s+([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:jurusan|program studi)(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:kampus(?:ku| saya)|universitas(?:ku| saya)|(?:kampus|universitas)\s+(?:di|adalah))\s*([A-Za-z0-9\s.,-]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val TECH_PROFILE_PATTERNS = listOf(
            Regex("""\b(?:fokus|minat)\s+(?:riset|penelitian|belajar|saya|ku)?\s*(?:di|tentang|pada|adalah)?\s*([A-Za-z0-9\s/.,-]{3,80})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:skripsi|tugas akhir|proyek)(?:ku| saya)?\s+(?:tentang|adalah)?\s*([A-Za-z0-9\s/.,-]{3,80})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:bahasa koding|bahasa pemrograman|framework|tech stack)\s+favorit(?:ku| saya)?\s+(?:adalah\s+)?([A-Za-z0-9\s,+-]{3,80})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:fokus|spesialisasi|minat|proyek)\s+(?:koding|coding|ngoding)?\s*(?:saya|ku)?\s*(?:di|pada|adalah)?\s*([A-Za-z0-9\s/.,+-]{3,100})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:kalau|jika)?\s*(?:coding|koding|ngoding)(?: sih)?\s+(?:saya|aku)?\s*(?:fokusnya|fokus|biasanya|seringnya)?\s*(?:di|pada|adalah)?\s*([A-Za-z0-9\s/.,+-]{3,100})\b""", RegexOption.IGNORE_CASE)
        )

        val PET_PATTERNS = listOf(
            Regex("""\b(?:kucing|anjing|peliharaan|kelinci|hamster)(?:ku| saya)?\s+(?:namanya|bernama|adalah)\s+([A-Za-z0-9\s]{2,30})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bnama\s+(?:kucing|anjing|peliharaan|kelinci|hamster)(?:ku| saya)\s+(?:adalah\s+)?([A-Za-z0-9\s]{2,30})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:saya|aku)\s+punya\s+(?:seekor\s+)?(kucing|anjing|kelinci|hamster)\s+(?:namanya|bernama)\s+([A-Za-z0-9\s]{2,30})\b""", RegexOption.IGNORE_CASE)
        )

        val DEVICE_PATTERNS = listOf(
            Regex("""\b(?:laptop|komputer|pc|macbook)(?:ku| saya)\s+(?:merk(?:nya)?|adalah|yaitu)?\s*([A-Za-z0-9\s._-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:hp|handphone|ponsel|smartphone)(?:ku| saya)\s+(?:merk(?:nya)?|adalah|yaitu)?\s*([A-Za-z0-9\s._-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:saya|aku)\s+(?:pakai|menggunakan)\s+(?:laptop|hp|ponsel)\s+([A-Za-z0-9\s._-]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val PROJECT_PATTERNS = listOf(
            Regex("""\b(?:proyek|project|startup|aplikasi)(?:ku| saya)?\s+(?:namanya|bernama|adalah)\s+([A-Za-z0-9\s._-]{3,40})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:saya|aku)\s+(?:lagi|sedang)?\s*(?:mengerjakan|bikin|buat|mengembangkan)\s+(?:proyek|project|aplikasi|startup)\s+([A-Za-z0-9\s._-]{3,40})\b""", RegexOption.IGNORE_CASE)
        )

        val GOAL_PATTERNS = listOf(
            Regex("""\b(?:cita-cita|target|impian|resolusi)(?:ku| saya)?\s+(?:adalah|yaitu)?\s*([A-Za-z0-9\s.,-]{3,60})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:saya|aku)\s+(?:ingin|bercita-cita|pengen)\s+(?:jadi|memiliki|punya)\s+([A-Za-z0-9\s.,-]{3,60})\b""", RegexOption.IGNORE_CASE)
        )

        val DIETARY_PATTERNS = listOf(
            Regex("""\b(?:saya|aku)\s+(?:seorang\s+)?(vegetarian|vegan)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:saya|aku)\s+(?:tidak|gak)\s+(?:bisa\s+)?makan\s+([A-Za-z0-9\s,]{3,40})\b""", RegexOption.IGNORE_CASE)
        )
    }

    /**
     * Mengekstrak fakta personal lisan dari percakapan alami pengguna (bahasa Indonesia)
     * dan menyimpannya secara otomatis ke Room v4 & memori temporal lokal tanpa harus menunggu
     * pemanggilan tool eksplisit dari cloud LLM.
     */
    fun extractAndRecordConversationalFacts(utterance: String): Int {
        val trimmed = utterance.trim()
        if (trimmed.length < 5) return 0

        // Jangan ekstrak fakta dari kalimat tanya / interogatif lisan
        if (isInterrogative(trimmed)) return 0

        // Tolak jika ujaran murni idiom sesaat atau derau navigasi layar
        if (SemanticMemoryFilter.isTransientIdiomOrNoise(trimmed)) return 0

        var extractedCount = 0

        // 1. Direktif simpan eksplisit ("ingat ya...", "catat bahwa...", "simpan info...")
        val directiveMatch = DIRECTIVE_SAVE_REGEX.find(trimmed)
        if (directiveMatch != null && directiveMatch.groupValues.size >= 2) {
            val content = directiveMatch.groupValues[1].trim()
            if (content.length >= 3) {
                val triple = TemporalKnowledgeGraph.extractTriple(factText = content, nowMs = clockMsProvider())
                recorder.record(
                    category = triple.category,
                    fact = content,
                    subject = triple.subject,
                    predicate = triple.predicate,
                    objectValue = triple.objectValue,
                    validHours = null,
                    source = "transcript_directive"
                )
                GepyLog.i(TAG, "Fakta tersimpan dari direktif: $content")
                return 1
            }
        }

        // 2. Hubungan Keluarga ("adikku namanya Farhan", "istri saya Siti", "nama anak saya Budi")
        for (pattern in FAMILY_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 3) {
                val relation = match.groupValues[1].lowercase()
                val rawName = match.groupValues[2].trim()
                if (isValidPersonName(rawName)) {
                    val capRelation = relation.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val capName = titleCase(rawName)
                    val subject = when (relation) {
                        "adik" -> "family_siblings"
                        "kakak" -> "family_older_siblings"
                        "istri", "suami" -> "family_spouse"
                        "anak" -> "family_children"
                        "ayah", "bapak" -> "family_father"
                        "ibu", "mama" -> "family_mother"
                        else -> "family"
                    }
                    val predicate = when (relation) {
                        "ayah", "bapak" -> "father_named"
                        "ibu", "mama" -> "mother_named"
                        "istri", "suami" -> "spouse_named"
                        else -> "has_family_member"
                    }
                    val fact = "$capRelation pengguna bernama $capName"
                    recorder.record(
                        category = "family",
                        fact = fact,
                        subject = subject,
                        predicate = predicate,
                        objectValue = capName,
                        source = "transcript_family"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta keluarga tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 3. Relasi Teman / Sahabat ("temanku namanya Joko", "sahabat saya Budi")
        for (pattern in FRIEND_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 3) {
                val rawName = match.groupValues[2].trim()
                if (isValidPersonName(rawName)) {
                    val capName = titleCase(rawName)
                    val fact = "Teman pengguna bernama $capName"
                    recorder.record(
                        category = "friends",
                        fact = fact,
                        subject = "social_friends",
                        predicate = "has_friend",
                        objectValue = capName,
                        source = "transcript_friends"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta pertemanan tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 4. Pekerjaan & Kantor ("saya bekerja di Bank BCA", "kantor saya di Sudirman")
        for (pattern in WORK_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val rawPlace = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(rawPlace)) {
                    val fact = "Pengguna bekerja di $rawPlace"
                    recorder.record(
                        category = "work",
                        fact = fact,
                        subject = "user_work",
                        predicate = "works_at",
                        objectValue = rawPlace,
                        source = "transcript_work"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta pekerjaan tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 5. Tempat Tinggal / Lokasi Rumah ("saya tinggal di Sleman", "rumah saya di Kaliurang")
        for (pattern in LOCATION_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val rawLoc = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(rawLoc)) {
                    val fact = "Pengguna tinggal di $rawLoc"
                    recorder.record(
                        category = "location",
                        fact = fact,
                        subject = "location_home",
                        predicate = "lives_in",
                        objectValue = rawLoc,
                        source = "transcript_location"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta lokasi tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 6. Kesehatan & Alergi ("saya alergi seafood", "pantangan makanan saya udang")
        for (pattern in HEALTH_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val rawAllergy = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(rawAllergy)) {
                    val fact = "Pengguna alergi $rawAllergy"
                    recorder.record(
                        category = "health",
                        fact = fact,
                        subject = "user_health",
                        predicate = "allergic_to",
                        objectValue = rawAllergy,
                        source = "transcript_health"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta kesehatan/alergi tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 7. Preferensi & Hobi ("saya suka kopi tanpa gula", "hobi saya fotografi")
        for (pattern in PREF_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val rawPref = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(rawPref)) {
                    val fact = "Pengguna suka $rawPref"
                    recorder.record(
                        category = "preference",
                        fact = fact,
                        subject = "user_preference",
                        predicate = "likes",
                        objectValue = rawPref,
                        source = "transcript_preference"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta preferensi tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        for (pattern in DISLIKE_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val rawDislike = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(rawDislike)) {
                    val fact = "Pengguna tidak suka $rawDislike"
                    recorder.record(
                        category = "preference",
                        fact = fact,
                        subject = "user_preference",
                        predicate = "dislikes",
                        objectValue = rawDislike,
                        source = "transcript_preference"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta antipati tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 8. Ulang Tahun & Tanggal Spesial ("ulang tahunku 15 Mei", "ulang tahun istriku 20 Oktober")
        for (pattern in BIRTHDAY_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val isFamily = match.groupValues.size >= 3 && match.groupValues[1].isNotBlank()
                val target = if (isFamily) match.groupValues[1].trim() else "pengguna"
                val dateStr = if (isFamily) match.groupValues[2].trim() else match.groupValues[1].trim()
                if (isValidFactValue(dateStr)) {
                    val fact = if (isFamily) "Ulang tahun $target tanggal $dateStr" else "Ulang tahun pengguna tanggal $dateStr"
                    val subject = if (isFamily) "birthday_$target" else "birthday_user"
                    recorder.record(
                        category = "birthday",
                        fact = fact,
                        subject = subject,
                        predicate = "birthday_on",
                        objectValue = dateStr,
                        source = "transcript_birthday"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta ulang tahun tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 9. Kendaraan & Nomor Plat ("plat motorku AB 1234 CD", "mobilku Honda Jazz")
        for (pattern in VEHICLE_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val value = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(value)) {
                    val isPlate = value.matches(Regex("""[A-Za-z]{1,2}\s+[0-9]{1,4}\s+[A-Za-z]{1,3}"""))
                    val isMotor = trimmed.contains("motor", ignoreCase = true)
                    val fact = if (isPlate) {
                        if (isMotor) "Plat motor pengguna $value" else "Plat nomor kendaraan pengguna $value"
                    } else {
                        "Kendaraan pengguna $value"
                    }
                    val subject = if (isMotor) "vehicle_motorcycle" else "vehicle_car"
                    val predicate = if (isPlate) "plate_number" else "vehicle_model"
                    recorder.record(
                        category = "vehicle",
                        fact = fact,
                        subject = subject,
                        predicate = predicate,
                        objectValue = value,
                        source = "transcript_vehicle"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta kendaraan tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 10. Rutinitas & Waktu Bangun ("saya biasa bangun jam 5 pagi", "kebiasaan saya olahraga tiap pagi")
        for (pattern in ROUTINE_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val value = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(value)) {
                    val isWake = trimmed.contains("bangun", ignoreCase = true)
                    val fact = if (isWake) "Pengguna biasa bangun jam $value" else "Rutinitas pengguna: $value"
                    val subject = if (isWake) "routine_wake" else "routine_daily"
                    val predicate = if (isWake) "wakes_at" else "has_habit"
                    recorder.record(
                        category = "routine",
                        fact = fact,
                        subject = subject,
                        predicate = predicate,
                        objectValue = value,
                        source = "transcript_routine"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta rutinitas tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 11. Kuliner & Minuman Favorit ("kopi favoritku Americano dingin", "makanan kesukaan saya nasi goreng")
        for (pattern in FOOD_DRINK_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val value = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(value)) {
                    val isDrink = trimmed.contains("kopi", ignoreCase = true) || trimmed.contains("minum", ignoreCase = true)
                    val fact = if (isDrink) "Minuman favorit pengguna: $value" else "Makanan favorit pengguna: $value"
                    recorder.record(
                        category = "preference",
                        fact = fact,
                        subject = "user_preference",
                        predicate = if (isDrink) "favorite_drink" else "favorite_food",
                        objectValue = value,
                        source = "transcript_preference"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta kuliner favorit tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 12. Relasi Kontak Profesional ("bosku namanya Pak Bambang", "atasan saya Bu Maya")
        for (pattern in PROFESSIONAL_CONTACT_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 3) {
                val role = match.groupValues[1].trim()
                val name = match.groupValues[2].trim()
                if (name.length >= 2) {
                    val capRole = role.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val fact = "$capRole pengguna bernama $name"
                    recorder.record(
                        category = "contact",
                        fact = fact,
                        subject = "social_boss",
                        predicate = "boss_named",
                        objectValue = name,
                        source = "transcript_contact"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta relasi profesional tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 13. Pendidikan & Status Mahasiswa ("aku mahasiswa informatika", "kampus saya di ITB")
        for (pattern in EDUCATION_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val value = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(value)) {
                    val isCampus = trimmed.contains("kampus", ignoreCase = true) ||
                        trimmed.contains("universitas", ignoreCase = true) ||
                        trimmed.contains("kuliah di", ignoreCase = true)
                    val fact = if (isCampus) "Pengguna kuliah di $value" else "Pengguna mahasiswa $value"
                    val subject = if (isCampus) "academic_campus" else "academic_major"
                    val predicate = if (isCampus) "studies_at" else "majoring_in"
                    recorder.record(
                        category = "education",
                        fact = fact,
                        subject = subject,
                        predicate = predicate,
                        objectValue = value,
                        source = "transcript_education"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta akademik tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 14. Minat Riset AI & Profil Teknologi ("minat risetku AI/ML agentic system", "bahasa favoritku Kotlin")
        for (pattern in TECH_PROFILE_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val value = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(value)) {
                    val isCoding = trimmed.contains("coding", ignoreCase = true) ||
                        trimmed.contains("koding", ignoreCase = true) ||
                        trimmed.contains("ngoding", ignoreCase = true) ||
                        trimmed.contains("python", ignoreCase = true) ||
                        trimmed.contains("langchain", ignoreCase = true) ||
                        trimmed.contains("langgraph", ignoreCase = true) ||
                        trimmed.contains("fastapi", ignoreCase = true) ||
                        trimmed.contains("vector", ignoreCase = true) ||
                        trimmed.contains("rag", ignoreCase = true)
                    val isTech = trimmed.contains("bahasa", ignoreCase = true) ||
                        trimmed.contains("framework", ignoreCase = true) ||
                        trimmed.contains("tech stack", ignoreCase = true)
                    val fact = when {
                        isCoding -> "Fokus koding & tech stack pengguna: $value"
                        isTech -> "Tech stack favorit pengguna: $value"
                        else -> "Fokus riset AI pengguna: $value"
                    }
                    val subject = when {
                        isCoding -> "coding_focus"
                        isTech -> "tech_stack"
                        else -> "tech_interest"
                    }
                    val predicate = when {
                        isCoding -> "specializes_in"
                        isTech -> "favorite_tech"
                        else -> "researches_in"
                    }
                    recorder.record(
                        category = "tech_profile",
                        fact = fact,
                        subject = subject,
                        predicate = predicate,
                        objectValue = value,
                        source = "transcript_tech_profile"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta riset & profil teknologi tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 15. Jadwal & Agenda Masa Depan ("besok ada rapat evaluasi jam 10")
        val schedMatch = SCHEDULE_PATTERN.find(trimmed)
        if (schedMatch != null && schedMatch.groupValues.size >= 3) {
            val time = schedMatch.groupValues[1].trim()
            val agenda = schedMatch.groupValues[2].trim().trimEnd('.', '!')
            if (isValidFactValue(agenda)) {
                val fact = "$time ada agenda $agenda"
                recorder.record(
                    category = "schedule",
                    fact = fact,
                    subject = "schedule_agenda",
                    predicate = "has_schedule",
                    objectValue = agenda,
                    source = "transcript_schedule"
                )
                extractedCount++
                GepyLog.i(TAG, "Fakta jadwal tersimpan otomatis: $fact")
            }
        }
        if (extractedCount > 0) return extractedCount

        // 16. Hewan Peliharaan ("kucing saya namanya Miko", "aku punya anjing namanya Milo")
        for (pattern in PET_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val isThreeGroup = match.groupValues.size >= 3
                val animalType = if (isThreeGroup) match.groupValues[1].lowercase() else "peliharaan"
                val petName = (if (isThreeGroup) match.groupValues[2] else match.groupValues[1]).trim().trimEnd('.', '!')
                if (petName.length >= 2 && isValidFactValue(petName)) {
                    val capPetName = titleCase(petName)
                    val capType = animalType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val fact = "$capType pengguna bernama $capPetName"
                    val subject = if (animalType.contains("kucing")) "pet_cat" else if (animalType.contains("anjing")) "pet_dog" else "pet_animal"
                    recorder.record(
                        category = "pet",
                        fact = fact,
                        subject = subject,
                        predicate = "pet_named",
                        objectValue = capPetName,
                        source = "transcript_pet"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta peliharaan tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 17. Perangkat / Gadget / Laptop / HP ("laptop saya ASUS ROG", "hp saya Samsung A06")
        for (pattern in DEVICE_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val devModel = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(devModel)) {
                    val isLaptop = trimmed.contains("laptop", ignoreCase = true) || trimmed.contains("macbook", ignoreCase = true) || trimmed.contains("komputer", ignoreCase = true)
                    val fact = if (isLaptop) "Laptop pengguna: $devModel" else "HP pengguna: $devModel"
                    val subject = if (isLaptop) "device_laptop" else "device_phone"
                    val predicate = if (isLaptop) "laptop_model" else "phone_model"
                    recorder.record(
                        category = "device",
                        fact = fact,
                        subject = subject,
                        predicate = predicate,
                        objectValue = devModel,
                        source = "transcript_device"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta perangkat tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 18. Proyek / Startup / Aplikasi ("proyek saya namanya Gepy", "saya lagi bikin startup fintech")
        for (pattern in PROJECT_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val projName = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(projName)) {
                    val fact = "Proyek pengguna: $projName"
                    recorder.record(
                        category = "project",
                        fact = fact,
                        subject = "user_project",
                        predicate = "working_on_project",
                        objectValue = projName,
                        source = "transcript_project"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta proyek tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 19. Cita-cita / Impian / Target ("cita-cita saya ingin jadi AI architect")
        for (pattern in GOAL_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val goalVal = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(goalVal)) {
                    val fact = "Cita-cita pengguna: $goalVal"
                    recorder.record(
                        category = "goal",
                        fact = fact,
                        subject = "user_goal",
                        predicate = "has_goal",
                        objectValue = goalVal,
                        source = "transcript_goal"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta impian/cita-cita tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 20. Pantangan Makanan & Diet ("saya vegetarian", "saya gak bisa makan daging")
        for (pattern in DIETARY_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val dietVal = match.groupValues[1].trim().trimEnd('.', '!')
                if (isValidFactValue(dietVal)) {
                    val fact = if (dietVal.contains("vegetarian") || dietVal.contains("vegan")) "Pengguna seorang $dietVal" else "Pantangan makan pengguna: $dietVal"
                    recorder.record(
                        category = "dietary",
                        fact = fact,
                        subject = "user_dietary",
                        predicate = "dietary_preference",
                        objectValue = dietVal,
                        source = "transcript_dietary"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta pantangan makan tersimpan otomatis: $fact")
                    break
                }
            }
        }
        if (extractedCount > 0) return extractedCount

        // 21. Universal First-Person Personal Statement Catcher ("aku/saya ...")
        if (trimmed.startsWith("saya ", ignoreCase = true) || trimmed.startsWith("aku ", ignoreCase = true)) {
            val statement = trimmed
            if (statement.length in 8..150 && !SemanticMemoryFilter.isTransientIdiomOrNoise(statement)) {
                val triple = TemporalKnowledgeGraph.extractTriple(factText = statement, nowMs = clockMsProvider())
                if (triple.category in EbbinghausDecayEngine.CORE_CATEGORIES) {
                    recorder.record(
                        category = triple.category,
                        fact = statement,
                        subject = triple.subject,
                        predicate = triple.predicate,
                        objectValue = triple.objectValue,
                        source = "transcript_personal_statement"
                    )
                    extractedCount++
                    GepyLog.i(TAG, "Fakta personal statement universal tersimpan otomatis: $statement")
                }
            }
        }

        return extractedCount
    }

    private fun isInterrogative(text: String): Boolean {
        val clean = text.trim()
        if (clean.endsWith("?")) return true
        val lower = clean.lowercase()
        return lower.startsWith("siapa") ||
            lower.startsWith("apa") ||
            lower.startsWith("apakah") ||
            lower.startsWith("dimana") ||
            lower.startsWith("di mana") ||
            lower.startsWith("kapan") ||
            lower.startsWith("bagaimana") ||
            lower.startsWith("berapa") ||
            lower.startsWith("jam berapa") ||
            lower.startsWith("kenapa") ||
            lower.startsWith("mengapa") ||
            lower.contains("kamu tahu") ||
            lower.contains("kamu ingat") ||
            lower.contains("tahu gak") ||
            lower.contains("ingat gak") ||
            lower.contains("apakah kamu")
    }

    private fun isValidPersonName(raw: String): Boolean {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts.size > 3) return false
        return parts.all { isValidName(it) }
    }

    private fun titleCase(raw: String): String {
        return raw.trim().split(Regex("\\s+")).joinToString(" ") { p ->
            p.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun isValidFactValue(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        if (lower.length !in 3..120) return false
        val invalidTokens = setOf("apa", "siapa", "dimana", "kapan", "bagaimana", "tolong", "bisa", "dong", "saja")
        return !invalidTokens.contains(lower)
    }

    fun isValidName(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower.length !in 3..20) return false
        if (UserMemoryManager.BLACKLISTED_NAME_WORDS.contains(lower)) return false
        if (lower.endsWith("ku") && UserMemoryManager.BLACKLISTED_NAME_WORDS.contains(lower.removeSuffix("ku"))) return false
        if (lower.endsWith("mu") && UserMemoryManager.BLACKLISTED_NAME_WORDS.contains(lower.removeSuffix("mu"))) return false
        if (lower.endsWith("nya") && UserMemoryManager.BLACKLISTED_NAME_WORDS.contains(lower.removeSuffix("nya"))) return false
        return true
    }

    internal fun guessCategoryFromFact(fact: String): String {
        val lower = fact.lowercase()
        return when {
            lower.contains("adik") || lower.contains("kakak") || lower.contains("ayah") ||
                lower.contains("ibu") || lower.contains("orang tua") || lower.contains("keluarga") ||
                lower.contains("anak") || lower.contains("suami") || lower.contains("istri") -> "family"
            lower.contains("suka") || lower.contains("alergi") || lower.contains("hobi") ||
                lower.contains("gemar") || lower.contains("kebiasaan") || lower.contains("minum") ||
                lower.contains("makan") -> "preference"
            lower.contains("tinggal di") || lower.contains("alamat") || lower.contains("rumah") ||
                lower.contains("kantor") || lower.contains("kota") -> "location"
            lower.contains("nama") || lower.contains("umur") || lower.contains("usia") ||
                lower.contains("bekerja") || lower.contains("pekerjaan") -> "profile"
            else -> "general"
        }
    }

    internal fun deriveSubject(fact: String, category: String): String {
        val lower = fact.lowercase()
        return when {
            lower.contains("adik") -> "family_siblings"
            lower.contains("kakak") -> "family_older_siblings"
            lower.contains("ayah") || lower.contains("bapak") -> "family_father"
            lower.contains("ibu") || lower.contains("mama") -> "family_mother"
            lower.contains("orang tua") -> "family_parents"
            lower.contains("alamat") || lower.contains("rumah") -> "location_home"
            lower.contains("nama pengguna") || lower.contains("namaku") -> "user_name"
            else -> category
        }
    }

    internal fun isSingleEntitySubject(subject: String): Boolean {
        return subject == "family_father" ||
            subject == "family_mother" ||
            subject == "family_spouse" ||
            subject == "user_name" ||
            subject == "user_work" ||
            subject == "location_home" ||
            subject == "profile_work" ||
            subject == "device_laptop" ||
            subject == "device_phone" ||
            subject == "user_project" ||
            subject == "user_goal" ||
            subject == "user_dietary" ||
            subject == "academic_major" ||
            subject == "academic_campus"
    }
}
