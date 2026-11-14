package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.data.database.GepyDao
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Orkestrator kueri linimasa graf temporal, konstruksi `systemInstruction` Two-Tier Working Memory,
 * penguatan jejak memori Ebbinghaus (Spaced Repetition), dan pemangkasan fakta kedaluwarsa.
 */
internal class TemporalMemoryQueryAndDecay(
    private val gepyDao: GepyDao?,
    private val clockMsProvider: () -> Long,
    private val scope: CoroutineScope,
    private val temporalLock: Any,
    private val temporalFactsCache: MutableList<TemporalMemoryFactEntity>,
    private val subWordBM25: SubWordBM25RetrievalEngine,
    private val vectorEngine: HybridVectorMemoryEngine,
    private val getUserDisplayName: () -> String,
    private val getAllTemporalFactsSnapshot: () -> List<TemporalMemoryFactEntity>,
    private val getActiveTemporalFactsSync: (Long) -> List<TemporalMemoryFactEntity>
) {
    /**
     * Menelusuri riwayat memori temporal untuk suatu [topic] (baik fakta aktif maupun riwayat masa lalu
     * yang telah digantikan atau kedaluwarsa).
     */
    suspend fun queryMemoryTimeline(topic: String): String {
        val cleanTopic = topic.trim()
        val nowMs = clockMsProvider()
        if (cleanTopic.isBlank()) {
            return "Mohon sebutkan topik atau kata kunci yang ingin ditelusuri riwayatnya."
        }

        val matches = if (gepyDao != null) {
            try {
                gepyDao.searchTemporalTimeline(cleanTopic, limit = 30)
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }.ifEmpty {
            synchronized(temporalLock) {
                temporalFactsCache.filter { entity ->
                    entity.factText.contains(cleanTopic, ignoreCase = true) ||
                        entity.subject.contains(cleanTopic, ignoreCase = true) ||
                        entity.predicate.contains(cleanTopic, ignoreCase = true) ||
                        entity.objectValue.contains(cleanTopic, ignoreCase = true) ||
                        entity.category.contains(cleanTopic, ignoreCase = true)
                }.sortedByDescending { it.createdAtMs }
            }
        }

        return TemporalKnowledgeGraph.formatTimelineResponse(
            topic = cleanTopic,
            matchingFacts = matches,
            nowMs = nowMs
        )
    }

    /**
     * Versi sinkron dari penelusuran riwayat memori temporal untuk *Fast-Path Skill* (< 5 ms, 0 I/O blokir).
     */
    fun queryMemoryTimelineSync(topic: String): String {
        val cleanTopic = topic.trim()
        val nowMs = clockMsProvider()
        if (cleanTopic.isBlank()) {
            return "Mohon sebutkan topik atau kata kunci yang ingin ditelusuri riwayatnya."
        }

        val allFacts = getAllTemporalFactsSnapshot()
        val matches = allFacts.filter { entity ->
            entity.factText.contains(cleanTopic, ignoreCase = true) ||
                entity.subject.contains(cleanTopic, ignoreCase = true) ||
                entity.predicate.contains(cleanTopic, ignoreCase = true) ||
                entity.objectValue.contains(cleanTopic, ignoreCase = true) ||
                entity.category.contains(cleanTopic, ignoreCase = true)
        }.sortedByDescending { it.createdAtMs }

        return TemporalKnowledgeGraph.formatTimelineResponse(
            topic = cleanTopic,
            matchingFacts = matches,
            nowMs = nowMs
        )
    }

    /**
     * Menghasilkan teks konteks memori terverifikasi yang dikelompokkan rapi per kategori & agenda temporal
     * untuk disuntikkan ke dalam `systemInstruction` Gemini Live 3.8.
     *
     * Arsitektur **Two-Tier Working Memory (Gepy v5.6)**:
     * - **Tier 1 (Core Anchor Facts - UNCONDITIONAL)**: Profil pengguna, nama terkunci, seluruh keluarga,
     *   seluruh teman dekat, dan alergi kritis SELALU disertakan utuh tanpa risiko tersingkir oleh kuota batasan.
     * - **Tier 2 (Dynamic Temporal Context)**: Agenda aktif, refleksi, preferensi, dan catatan temporal
     *   yang diperingkatkan berdasar skor relevansi Stanford + Ebbinghaus.
     */
    fun getSystemInstructionContext(): String {
        val displayName = getUserDisplayName()
        val nowMs = clockMsProvider()
        val allActiveFacts = getActiveTemporalFactsSync(nowMs)

        // TIER 1: Sovereign Core Anchor Facts (UNCONDITIONAL - SELALU DIINJEKSI TANPA KECUALI)
        val familyFacts = allActiveFacts.filter {
            it.category.equals("family", ignoreCase = true) || it.subject.startsWith("family_")
        }
        val friendFacts = allActiveFacts.filter {
            it.category.equals("friends", ignoreCase = true) ||
                it.category.equals("social", ignoreCase = true) ||
                it.category.equals("relationship", ignoreCase = true) ||
                it.subject.contains("friend")
        }
        val healthFacts = allActiveFacts.filter {
            it.category.equals("health", ignoreCase = true) ||
                it.category.equals("dietary", ignoreCase = true) ||
                it.predicate == "allergic_to" ||
                it.factText.lowercase().contains("alergi")
        }
        val profileFacts = allActiveFacts.filter {
            (it.category.equals("profile", ignoreCase = true) ||
                it.category.equals("work", ignoreCase = true) ||
                it.subject == "user_work" ||
                it.subject == "location_home") &&
                !familyFacts.contains(it) && !friendFacts.contains(it) && !healthFacts.contains(it)
        }
        val educationFacts = allActiveFacts.filter {
            it.category.equals("education", ignoreCase = true) || it.subject.startsWith("academic_")
        }
        val techFacts = allActiveFacts.filter {
            it.category.equals("tech_profile", ignoreCase = true) ||
                it.category.equals("device", ignoreCase = true) ||
                it.subject.startsWith("tech_") ||
                it.subject.startsWith("coding_") ||
                it.subject.startsWith("device_")
        }
        val petFacts = allActiveFacts.filter {
            it.category.equals("pet", ignoreCase = true) || it.subject.startsWith("pet_") || it.predicate.contains("pet")
        }
        val projectFacts = allActiveFacts.filter {
            it.category.equals("project", ignoreCase = true) ||
                it.category.equals("goal", ignoreCase = true) ||
                it.subject == "user_project" ||
                it.subject == "user_goal"
        }
        val vehicleFacts = allActiveFacts.filter {
            it.category.equals("vehicle", ignoreCase = true) || it.subject.startsWith("vehicle_")
        }
        val prefFacts = allActiveFacts.filter {
            (it.category.equals("preference", ignoreCase = true) ||
                it.category.equals("habits", ignoreCase = true) ||
                it.category.equals("routine", ignoreCase = true) ||
                it.category.equals("birthday", ignoreCase = true)) &&
                it.validUntilMs == null
        }

        val coreAnchorSet = (familyFacts + friendFacts + healthFacts + profileFacts +
            educationFacts + techFacts + petFacts + projectFacts + vehicleFacts + prefFacts).toSet()

        // TIER 2: Dynamic Temporal Facts (Ranked by recency + relevance)
        val nonCoreFacts = allActiveFacts.filter { !coreAnchorSet.contains(it) }
        val rankedTemporalFacts = TemporalKnowledgeGraph.rankRelevantFacts(
            facts = nonCoreFacts,
            query = "",
            nowMs = nowMs,
            maxFacts = 50
        )

        val scheduleEntries = rankedTemporalFacts.filter { it.category.equals("schedule", ignoreCase = true) || it.validUntilMs != null }
        val insightEntries = rankedTemporalFacts.filter { it.category.equals("insight", ignoreCase = true) && !scheduleEntries.contains(it) }
        val locEntries = rankedTemporalFacts.filter { it.category.equals("location", ignoreCase = true) && !scheduleEntries.contains(it) && !profileFacts.contains(it) }
        val otherEntries = rankedTemporalFacts.filter {
            !scheduleEntries.contains(it) && !insightEntries.contains(it) && !locEntries.contains(it)
        }

        val sb = StringBuilder()
        if (displayName.isNotBlank()) {
            sb.append("- Nama Pengguna: ").append(displayName).append("\n")
        }

        if (familyFacts.isNotEmpty()) {
            sb.append("- Informasi Keluarga:\n")
            familyFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (friendFacts.isNotEmpty()) {
            sb.append("- Teman Dekat & Relasi Sosial:\n")
            friendFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (healthFacts.isNotEmpty()) {
            sb.append("- Kesehatan, Alergi & Pantangan Makan:\n")
            healthFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (profileFacts.isNotEmpty()) {
            sb.append("- Data Diri, Pekerjaan & Domisili:\n")
            profileFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (educationFacts.isNotEmpty()) {
            sb.append("- Pendidikan & Kampus:\n")
            educationFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (techFacts.isNotEmpty()) {
            sb.append("- Minat Riset, Tech Stack & Gadget:\n")
            techFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (petFacts.isNotEmpty()) {
            sb.append("- Hewan Peliharaan:\n")
            petFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (projectFacts.isNotEmpty()) {
            sb.append("- Proyek, Startup & Impian/Target Hidup:\n")
            projectFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (vehicleFacts.isNotEmpty()) {
            sb.append("- Kendaraan & Nomor Plat:\n")
            vehicleFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (prefFacts.isNotEmpty()) {
            sb.append("- Preferensi, Kebiasaan, Rutinitas & Tanggal Spesial:\n")
            prefFacts.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (scheduleEntries.isNotEmpty()) {
            sb.append("- Agenda & Catatan Berbatas Waktu Aktif:\n")
            scheduleEntries.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (insightEntries.isNotEmpty()) {
            sb.append("- Wawasan & Refleksi Psikologis Pengguna:\n")
            insightEntries.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (locEntries.isNotEmpty()) {
            sb.append("- Lokasi & Alamat Penting:\n")
            locEntries.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        if (otherEntries.isNotEmpty()) {
            sb.append("- Catatan Penting Lainnya:\n")
            otherEntries.forEach { e -> sb.append("  * ").append(e.factText).append("\n") }
        }

        return sb.toString().trim()
    }

    /**
     * Memperkuat jejak memori (**Ebbinghaus Spaced Repetition**) untuk fakta-fakta aktif yang
     * disebut kembali oleh pengguna dalam percakapan atau instruksi tujuan.
     *
     * Diperkaya dengan **Sub-Word Tri-Gram BM25+ Engine (Gepy v5.4)** untuk mengenali variasi
     * imbuhan bahasa Indonesia (misal: *ngopi* -> *kopi*, *berlari* -> *lari*) dan typo lisan.
     */
    fun reinforceMatchingFacts(utteranceOrQuery: String, nowMs: Long = clockMsProvider()): Int {
        val cleanUtterance = utteranceOrQuery.trim()
        if (cleanUtterance.length < 3) return 0

        val tokens = cleanUtterance.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && !UserMemoryManager.BLACKLISTED_NAME_WORDS.contains(it) }
            .toSet()

        val reinforcedIds = mutableListOf<Long>()

        synchronized(temporalLock) {
            for (idx in temporalFactsCache.indices) {
                val existing = temporalFactsCache[idx]
                if (!existing.isActive) continue
                val objLower = existing.objectValue.lowercase()
                val factLower = existing.factText.lowercase()
                val exactTokenMatch = tokens.isNotEmpty() && tokens.any { token ->
                    objLower.contains(token) || factLower.contains(token)
                }

                val bm25Score = subWordBM25.computeSimilarity(cleanUtterance, existing)
                val isMatched = exactTokenMatch || bm25Score >= 1.5

                if (isMatched) {
                    temporalFactsCache[idx] = existing.copy(
                        hitCount = existing.hitCount + 1,
                        updatedAtMs = nowMs
                    )
                    reinforcedIds.add(existing.id)
                }
            }
        }

        if (reinforcedIds.isNotEmpty() && gepyDao != null) {
            scope.launch {
                for (id in reinforcedIds) {
                    try {
                        gepyDao.incrementTemporalFactHitCount(id, nowMs)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return reinforcedIds.size
    }

    /**
     * Memangkas fakta-fakta yang telah kedaluwarsa (`validUntilMs <= nowMs`) maupun observasi kasual
     * bernilai rendah ($I \le 3$, `hitCount == 0`) yang daya ingat Ebbinghaus-nya telah meluruh
     * di bawah ambang retensi (`< 20%`). Fakta penting ($I \ge 5$) tetap terjaga abadi.
     */
    suspend fun pruneDecayedAndExpiredFacts(nowMs: Long = clockMsProvider()): Int {
        val allSnapshot = synchronized(temporalLock) { temporalFactsCache.toList() }
        val toPrune = EbbinghausDecayEngine.identifyFactsToPrune(allSnapshot, nowMs)
        if (toPrune.isEmpty()) {
            val dbExpired = try {
                gepyDao?.expireOutdatedTemporalFacts(nowMs) ?: 0
            } catch (_: Throwable) {
                0
            }
            return dbExpired
        }

        val pruneIds = toPrune.map { it.id }.toSet()
        val deactivatedEntities = mutableListOf<TemporalMemoryFactEntity>()

        synchronized(temporalLock) {
            for (idx in temporalFactsCache.indices) {
                val existing = temporalFactsCache[idx]
                if (existing.id in pruneIds && existing.isActive) {
                    val updated = existing.copy(isActive = false, updatedAtMs = nowMs)
                    temporalFactsCache[idx] = updated
                    deactivatedEntities.add(updated)
                }
            }
        }

        if (deactivatedEntities.isNotEmpty()) {
            vectorEngine.removeFactIds(pruneIds)
        }

        if (gepyDao != null) {
            try {
                gepyDao.expireOutdatedTemporalFacts(nowMs)
                if (deactivatedEntities.isNotEmpty()) {
                    gepyDao.insertTemporalFacts(deactivatedEntities)
                    deactivatedEntities.filter { it.id > 0L }.forEach {
                        gepyDao.deleteVectorByFactId(it.id)
                    }
                }
            } catch (t: Throwable) {
                GepyLog.w(UserMemoryManager.TAG, "Gagal menyinkronkan pemangkasan Ebbinghaus ke Room: ${t.message}")
            }
        }

        return deactivatedEntities.size
    }


    /**
     * Membangun ringkasan memori personal yang sangat ringkas (`<= 120` token) dan relevan
     * terhadap [userGoal] menggunakan peringkat graf temporal untuk `SingleAgentReActAdapter`.
     */
    fun buildCompactReActContext(userGoal: String, maxFacts: Int = 8): String? {
        val name = getUserDisplayName()
        val nowMs = clockMsProvider()
        val activeTemporal = getActiveTemporalFactsSync(nowMs)
        if (name.isBlank() && activeTemporal.isEmpty()) return null

        val rankedFacts = TemporalKnowledgeGraph.rankRelevantFacts(
            facts = activeTemporal,
            query = userGoal,
            nowMs = nowMs,
            maxFacts = maxFacts.coerceIn(1, 15)
        ).map { it.factText.take(100) }

        return buildString {
            if (name.isNotBlank()) {
                append("Pemilik: $name. ")
            }
            if (rankedFacts.isNotEmpty()) {
                append("Fakta Pribadi: ")
                append(rankedFacts.joinToString(" | "))
            }
        }.trim().ifEmpty { null }
    }
}
