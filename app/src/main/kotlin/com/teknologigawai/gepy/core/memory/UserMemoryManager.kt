package com.teknologigawai.gepy.core.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.teknologigawai.gepy.core.auth.GoogleAuthManager
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.memory.backup.GepyVaultBackup
import com.teknologigawai.gepy.core.memory.backup.VaultImportResult
import com.teknologigawai.gepy.core.network.CloudflareGatewayClient
import com.teknologigawai.gepy.data.database.GepyDao
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Manajer Memori Profil Pengguna Gepy yang menggabungkan:
 * - *Instant Zero-Latency Local Cache* (`SharedPreferences` di perangkat HP)
 * - **Sovereign Temporal Knowledge Graph (Room `v5`)**: penyimpanan triplet terstruktur
 *   `(subject, predicate, object_value)` dengan resolusi kontradiksi historis & batas waktu otomatis
 * - **Layer 2 On-Device Semantic Vector Index (`memory_vectors`)**: vektor `gemini-embedding-2` (768-dim MRL)
 *   berkapasitas terikat `<= 1.000` vektor di RAM (`~3 MB`) / `<= 2.500` di SQLite untuk pencarian makna lintas bahasa via RRF (`k = 60`)
 * - Sinkronisasi opsional ke *Cloudflare Gateway* & *Supabase pgvector* hanya jika pengguna login
 * - Deteksi heuristik fakta dari transkrip ucapan pengguna (*"nama saya ...", "panggil aku ..."*)
 * - Generator konteks memori terverifikasi untuk `systemInstruction` Gemini Live 3.8 & ReAct
 */
class UserMemoryManager(
    private val context: Context? = null,
    private val authManager: GoogleAuthManager? = null,
    private val gatewayClient: CloudflareGatewayClient? = null,
    private val gepyDao: GepyDao? = null,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val embeddingClient: GeminiEmbeddingClient? = null,
    private val embeddingKeyPoolProvider: () -> List<String> = { emptyList() },
    private val chatKeyPoolProvider: () -> List<String> = { emptyList() },
    private val embeddingModelProvider: () -> String = { EnvConfig.geminiEmbeddingModel }
) {
    companion object {
        internal const val TAG = "UserMemoryManager"
        private const val PREFS_NAME = "gepy_user_profile_memory_prefs"
        private const val KEY_CUSTOM_NAME = "key_custom_display_name"
        private const val KEY_NAME_CONFIRMED = "key_user_name_confirmed"
        private const val KEY_LOCAL_FACTS = "key_local_facts_json"
        private const val KEY_HAS_SKIPPED_AUTH = "key_has_skipped_opening_auth"
        private const val MAX_LEGACY_PREFS_ENTRIES = 200
        const val MAX_TEMPORAL_RAM_FACTS = 1000
        private const val MAX_CONTEXT_FACTS = 30
        const val MAX_VECTOR_RAM_CACHE = 1000
        const val MAX_VECTOR_DB_ROWS = 2500
        const val MIN_SEMANTIC_SIMILARITY = 0.32f
        const val RRF_K = 60

        // Pola pengenal nama lisan alami dalam Bahasa Indonesia (mengecualikan kata negasi 'bukan', 'salah', dll.)
        val NAME_EXTRACTION_REGEX = Regex(
            """\b(?:(?:nama\s+saya|namaku)(?:\s+(?:adalah|yaitu))?\s+|panggil\s+(?:aku|saya)\s+|(?:per)?kenal(?:kan)?(?:,\s*|\s+)(?:nama\s+saya\s+|namaku\s+|aku\s+|saya\s+))([A-Za-z]{3,20})\b""",
            RegexOption.IGNORE_CASE
        )

        // Pola koreksi nama eksplisit yang sangat ketat: "namaku bukan X tapi Y", "bukan X tapi namaku Y", "ralat namaku jadi Y".
        // WAJIB mencantumkan indikator identitas ("nama saya/namaku") agar topik obrolan kasual ("salah satu film") TIDAK PERNAH terbajak!
        val NAME_CORRECTION_REGEX = Regex(
            """(?:(?:nama\s+saya|namaku)\s+(?:bukan|bukanlah|tidak|salah|keliru)\s+[A-Za-z]{2,20}\s+(?:tapi|melainkan|melainkanlah|ganti)?\s*(?:namaku\s+|nama\s+saya\s+|panggil\s+(?:aku|saya)\s+)?([A-Za-z]{3,20})|(?:ralat|koreksi|ganti)\s+(?:nama(?:ku|\s+saya)?\s+(?:jadi|menjadi|ke)\s+)([A-Za-z]{3,20})|\b(?:bukan|bukanlah|keliru)\s+[A-Za-z]{2,20},?\s+(?:tapi|melainkan)\s+(?:namaku|nama\s+saya|panggil\s+(?:aku|saya))\s+([A-Za-z]{3,20}))""",
            RegexOption.IGNORE_CASE
        )

        internal val BLACKLISTED_NAME_WORDS = setOf(
            // Nama asisten & sapaan dasar
            "gepy", "halo", "hai", "bisa", "mau", "tahu", "siapa", "apa", "dong", "deh", "sih", "kan", "kok",
            // Negasi & koreksi
            "bukan", "bukanlah", "salah", "tidak", "belum", "keliru", "jangan",
            // Sebutan relasi & gelar
            "orang", "teman", "temanku", "kawan", "kawanku", "sahabat", "sohib", "sobat",
            "saudara", "adik", "adikku", "kakak", "kakakku", "ayah", "ayahku", "ibu", "ibuku",
            "mama", "mamaku", "bapak", "bapakku", "suami", "suamiku", "istri", "istriku", "anak", "anakku",
            "om", "tante", "mas", "mbak", "bang", "pak", "bu", "bung", "dek", "bro", "sis", "bos",
            // Kata benda / waktu / partikel percakapan yang rawan salah dengar
            "namaku", "nama", "panggil", "nanti", "sekarang", "besok", "lusa", "kemarin", "tadi",
            "kalau", "jika", "lalu", "terus", "lagi", "sudah", "baru", "siap", "oke", "iya",
            "ini", "itu", "sini", "sana", "ada", "boleh", "tolong", "makan", "minum", "tidur", "pulang",
            "belanja", "kerja", "kantor", "rumah",
            // Media, hiburan, percakapan film/musik/konten (Topik obrolan yang DILARANG MUTLAK jadi nama)
            "film", "fim", "movie", "bioskop", "cinema", "sinema", "layar", "video", "lagu", "musik",
            "game", "buku", "novel", "komik", "cerita", "status", "reels", "story", "feed", "postingan",
            "aktor", "aktris", "sutradara", "genre", "horor", "komedi", "drama", "action", "romantis",
            "animasi", "anime", "kartun", "soundtrack", "artis", "penyanyi", "band", "lagunya", "filmnya",
            "tonton", "nonton", "dengar", "putar", "baca", "main", "mainkan",
            // Kata kuantitas / determiners
            "satu", "cuma", "hanya", "semua", "banyak", "sedikit", "benda", "hal", "topik", "masalah",
            "halusinasi", "konten", "informasi", "berita", "kabar"
        )
    }

    /**
     * Callback yang dipanggil saat ada fakta baru tersimpan, diperbarui, atau dihapus
     * agar komponen aktif (seperti Gemini Live WebSocket) dapat langsung menyematkan anchor pin.
     */
    var onMemoryChangedListener: ((String) -> Unit)? = null

    fun notifyMemoryChanged() {
        runCatching {
            val ctx = getSystemInstructionContext()
            onMemoryChangedListener?.invoke(ctx)
        }
    }

    private val inMemoryFallbackMap = mutableMapOf<String, Any?>()
    private val postCallSynthesizer = PostCallMemorySynthesizer()
    private val subWordBM25: SubWordBM25RetrievalEngine = SubWordBM25RetrievalEngine()

    private val prefs: SharedPreferences? = try {
        context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    private val factExtractor = ConversationalFactExtractor(
        clockMsProvider = clockMsProvider,
        recorder = object : MemoryFactRecorder {
            override fun record(
                category: String,
                fact: String,
                subject: String,
                predicate: String,
                objectValue: String,
                validHours: Int?,
                source: String
            ) = recordMemoryAsync(category, fact, subject, predicate, objectValue, validHours, source)
        }
    )

    private val vectorEngine: HybridVectorMemoryEngine by lazy {
        HybridVectorMemoryEngine(
            gepyDao = gepyDao,
            clockMsProvider = clockMsProvider,
            scope = scope,
            embeddingClient = embeddingClient,
            embeddingKeyPoolProvider = embeddingKeyPoolProvider,
            embeddingModelProvider = embeddingModelProvider,
            subWordBM25 = subWordBM25,
            isTemporalInitializedProvider = { mutationStore.isTemporalInitialized },
            isClearJobActiveProvider = { mutationStore.isClearJobActive() },
            awaitPendingClearJobProvider = { mutationStore.awaitPendingClearJob() },
            getUserDisplayName = { getUserDisplayName() },
            getActiveTemporalFactsSync = { now -> getActiveTemporalFactsSync(now) },
            getAllTemporalFactsSnapshot = { getAllTemporalFactsSnapshot() }
        )
    }

    private val mutationStore: TemporalFactMutationStore by lazy {
        TemporalFactMutationStore(
            gepyDao = gepyDao,
            clockMsProvider = clockMsProvider,
            scope = scope,
            vectorEngine = vectorEngine,
            factExtractor = factExtractor,
            readRawLocalEntriesUnsafe = { fallbackNowMs -> readRawLocalEntriesUnsafe(fallbackNowMs) },
            getLocalEntries = { getLocalEntries() },
            persistEntries = { entries -> persistEntries(entries) },
            onProfileNameUpdated = { clean ->
                setPrefString(KEY_CUSTOM_NAME, clean)
                setPrefBoolean(KEY_NAME_CONFIRMED, true)
            },
            clearPrefs = { clearPrefs() },
            syncToCloudIfAuthenticated = { cat, fact -> vaultCoordinator.syncToCloudIfAuthenticated(cat, fact) },
            notifyMemoryChanged = { notifyMemoryChanged() }
        )
    }

    private val temporalQueryAndDecay: TemporalMemoryQueryAndDecay by lazy {
        TemporalMemoryQueryAndDecay(
            gepyDao = gepyDao,
            clockMsProvider = clockMsProvider,
            scope = scope,
            temporalLock = mutationStore.temporalLock,
            temporalFactsCache = mutationStore.temporalFactsCache,
            subWordBM25 = subWordBM25,
            vectorEngine = vectorEngine,
            getUserDisplayName = { getUserDisplayName() },
            getAllTemporalFactsSnapshot = { getAllTemporalFactsSnapshot() },
            getActiveTemporalFactsSync = { now -> getActiveTemporalFactsSync(now) }
        )
    }

    private val vaultCoordinator: MemoryVaultBackupCoordinator by lazy {
        MemoryVaultBackupCoordinator(
            authManager = authManager,
            gatewayClient = gatewayClient,
            gepyDao = gepyDao,
            clockMsProvider = clockMsProvider,
            scope = scope,
            temporalLock = mutationStore.temporalLock,
            temporalFactsCache = mutationStore.temporalFactsCache,
            allocateInMemoryFactId = { mutationStore.allocateInMemoryFactId() },
            vectorEngine = vectorEngine,
            getUserDisplayName = { getUserDisplayName() },
            setUserDisplayName = { name, isConfirmed -> setUserDisplayName(name, isConfirmed) },
            getAllTemporalFactsSnapshot = { getAllTemporalFactsSnapshot() },
            getActiveTemporalFactsSync = { now -> getActiveTemporalFactsSync(now) },
            persistEntries = { entries -> persistEntries(entries) }
        )
    }

    // --- Delegasi API Publik Terjaga ke Komponen Kohesif (Tahap 5) ---

    suspend fun initializeTemporalStore() = mutationStore.initializeTemporalStore()

    fun recordMemoryAsync(
        category: String,
        fact: String,
        subject: String = "",
        predicate: String = "",
        objectValue: String = "",
        validHours: Int? = null,
        source: String = "voice"
    ) = mutationStore.recordMemoryAsync(category, fact, subject, predicate, objectValue, validHours, source)

    fun recordMemory(
        category: String,
        fact: String,
        subject: String = "",
        predicate: String = "",
        objectValue: String = "",
        validHours: Int? = null,
        source: String = "voice"
    ) = mutationStore.recordMemory(category, fact, subject, predicate, objectValue, validHours, source)

    suspend fun updateOrCorrectMemory(
        targetToReplace: String,
        correctedFact: String,
        category: String = "general",
        subject: String = ""
    ) = mutationStore.updateOrCorrectMemory(targetToReplace, correctedFact, category, subject)

    fun deleteMemory(targetOrKeyword: String): Boolean = mutationStore.deleteMemory(targetOrKeyword)

    suspend fun deleteTemporalFactById(factId: Long) = mutationStore.deleteTemporalFactById(factId)

    fun getAllTemporalFactsSnapshot(): List<TemporalMemoryFactEntity> =
        mutationStore.getAllTemporalFactsSnapshot()

    fun getActiveTemporalFactsSync(nowMs: Long = clockMsProvider()): List<TemporalMemoryFactEntity> =
        mutationStore.getActiveTemporalFactsSync(nowMs)

    fun getTodaySchedules(nowMs: Long = clockMsProvider()): List<TemporalMemoryFactEntity> =
        mutationStore.getTodaySchedules(nowMs)

    suspend fun flushPendingVectorIndexing() = vectorEngine.flushPendingVectorIndexing()

    fun cachedVectorCount(): Int = vectorEngine.cachedVectorCount()

    fun peekVectorRamStats(): VectorMemoryStats = vectorEngine.peekVectorRamStats()

    suspend fun getVectorMemoryStats(): VectorMemoryStats = vectorEngine.getVectorMemoryStats()

    fun recordEpisodicVectorMemory(
        category: String,
        subject: String,
        contentText: String,
        importance: Int = 6,
        factId: Long? = null
    ) = vectorEngine.recordEpisodicVectorMemory(category, subject, contentText, importance, factId)

    suspend fun searchHybridMemories(
        query: String,
        nowMs: Long = clockMsProvider(),
        maxResults: Int = 6
    ): List<HybridMemoryMatch> = vectorEngine.searchHybridMemories(query, nowMs, maxResults)

    suspend fun buildHybridReActContext(userGoal: String, maxFacts: Int = 8): String? =
        vectorEngine.buildHybridReActContext(userGoal, maxFacts)

    suspend fun queryMemoryTimeline(topic: String): String =
        temporalQueryAndDecay.queryMemoryTimeline(topic)

    fun queryMemoryTimelineSync(topic: String): String =
        temporalQueryAndDecay.queryMemoryTimelineSync(topic)

    fun getSystemInstructionContext(): String =
        temporalQueryAndDecay.getSystemInstructionContext()

    fun extractAndRecordConversationalFacts(utterance: String): Int =
        factExtractor.extractAndRecordConversationalFacts(utterance)

    fun reinforceMatchingFacts(utteranceOrQuery: String, nowMs: Long = clockMsProvider()): Int =
        temporalQueryAndDecay.reinforceMatchingFacts(utteranceOrQuery, nowMs)

    suspend fun pruneDecayedAndExpiredFacts(nowMs: Long = clockMsProvider()): Int =
        temporalQueryAndDecay.pruneDecayedAndExpiredFacts(nowMs)

    fun isValidName(name: String): Boolean = factExtractor.isValidName(name)

    fun buildCompactReActContext(userGoal: String, maxFacts: Int = 8): String? =
        temporalQueryAndDecay.buildCompactReActContext(userGoal, maxFacts)

    fun clearLocalMemory() = mutationStore.clearLocalMemory()

    suspend fun clearLocalMemorySuspend() = mutationStore.clearLocalMemorySuspend()

    fun exportVaultBackup(appVersion: String = EnvConfig.appVersionName): GepyVaultBackup =
        vaultCoordinator.exportVaultBackup(appVersion)

    fun exportVaultJson(appVersion: String = EnvConfig.appVersionName): String =
        vaultCoordinator.exportVaultJson(appVersion)

    fun exportVaultToStream(outputStream: OutputStream, appVersion: String = EnvConfig.appVersionName) =
        vaultCoordinator.exportVaultToStream(outputStream, appVersion)

    suspend fun importVaultBackup(vaultBackup: GepyVaultBackup): VaultImportResult =
        vaultCoordinator.importVaultBackup(vaultBackup)

    suspend fun importVaultJson(jsonString: String): VaultImportResult =
        vaultCoordinator.importVaultJson(jsonString)

    suspend fun importVaultFromStream(inputStream: InputStream): VaultImportResult =
        vaultCoordinator.importVaultFromStream(inputStream)

    private fun getPrefString(key: String, def: String): String {
        return prefs?.getString(key, def) ?: (inMemoryFallbackMap[key] as? String ?: def)
    }

    private fun setPrefString(key: String, value: String) {
        prefs?.edit { putString(key, value) }
            ?: run { inMemoryFallbackMap[key] = value }
    }

    private fun getPrefBoolean(key: String, def: Boolean): Boolean {
        return prefs?.getBoolean(key, def) ?: (inMemoryFallbackMap[key] as? Boolean ?: def)
    }

    private fun setPrefBoolean(key: String, value: Boolean) {
        prefs?.edit { putBoolean(key, value) }
            ?: run { inMemoryFallbackMap[key] = value }
    }

    private fun clearPrefs() {
        prefs?.edit { clear() }
        inMemoryFallbackMap.clear()
    }

    /** Menandakan apakah pengguna memilih "Lewati" pada layar pembuka login. */
    var hasSkippedOpeningAuth: Boolean
        get() = getPrefBoolean(KEY_HAS_SKIPPED_AUTH, false)
        set(value) = setPrefBoolean(KEY_HAS_SKIPPED_AUTH, value)

    /**
     * Mengambil nama panggilan resmi pengguna:
     * 1. Nama kustom yang diingat dari percakapan lisan
     * 2. Nama dari Akun Google jika sudah login
     * 3. String kosong jika belum diketahui
     */
    fun getUserDisplayName(): String {
        val custom = getPrefString(KEY_CUSTOM_NAME, "").trim()
        if (custom.isNotBlank()) {
            if (isValidName(custom)) {
                return custom
            } else {
                GepyLog.w(TAG, "Mendeteksi nama kustom korup/terlarang: '$custom'. Menghapus nama (Auto-Heal)...")
                setPrefString(KEY_CUSTOM_NAME, "")
                setPrefBoolean(KEY_NAME_CONFIRMED, false)
            }
        }

        val googleUser = authManager?.currentUser?.value
        if (googleUser != null && googleUser.displayName.isNotBlank()) {
            val candidate = googleUser.displayName.split(" ").firstOrNull()?.trim() ?: googleUser.displayName
            if (isValidName(candidate)) {
                return candidate
            }
        }
        return ""
    }

    /** Memeriksa apakah nama pengguna telah terverifikasi/terkunci (Identity Shield). */
    fun isNameConfirmed(): Boolean {
        val custom = getPrefString(KEY_CUSTOM_NAME, "").trim()
        if (custom.isNotBlank() && !isValidName(custom)) {
            setPrefString(KEY_CUSTOM_NAME, "")
            setPrefBoolean(KEY_NAME_CONFIRMED, false)
            return false
        }
        return getPrefBoolean(KEY_NAME_CONFIRMED, false) || custom.isNotBlank()
    }

    /** Menandai konfirmasi penguncian nama secara eksplisit. */
    fun setNameConfirmed(confirmed: Boolean) {
        setPrefBoolean(KEY_NAME_CONFIRMED, confirmed)
    }

    /** Menyimpan atau memperbarui nama panggilan pengguna dengan perlindungan Identity Shield. */
    fun setUserDisplayName(name: String, isConfirmed: Boolean = true) {
        val clean = name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        if (clean.isBlank() || !isValidName(clean)) return
        setPrefString(KEY_CUSTOM_NAME, clean)
        setPrefBoolean(KEY_NAME_CONFIRMED, isConfirmed)
        GepyLog.i(TAG, "Nama pengguna berhasil disimpan secara persisten: $clean (confirmed=$isConfirmed)")
        notifyMemoryChanged()

        // Sinkronisasi fakta nama ke cloud jika login
        scope.launch {
            recordMemory(
                category = "profile",
                fact = "Nama panggilan pengguna adalah $clean",
                subject = "user_name"
            )
        }
    }

    /** Membaca entri mentah dari SharedPreferences termasuk yang memiliki stempel batas waktu. */
    private fun readRawLocalEntriesUnsafe(fallbackNowMs: Long): List<UserMemoryEntry> {
        val raw = getPrefString(KEY_LOCAL_FACTS, "[]")
        return try {
            val jsonArr = JSONArray(raw)
            val list = mutableListOf<UserMemoryEntry>()
            for (i in 0 until jsonArr.length()) {
                val item = jsonArr.opt(i)
                if (item is JSONObject) {
                    val validUntil = if (item.has("validUntilMs") && !item.isNull("validUntilMs")) {
                        item.optLong("validUntilMs")
                    } else {
                        null
                    }
                    list.add(
                        UserMemoryEntry(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            category = item.optString("category", "general"),
                            subject = item.optString("subject", ""),
                            fact = item.optString("fact", "").trim(),
                            updatedAt = item.optLong("updatedAt", fallbackNowMs),
                            validUntilMs = validUntil
                        )
                    )
                } else if (item is String && item.isNotBlank()) {
                    // Backward-compatible untuk format legacy v3.8.1 (array string)
                    val cat = factExtractor.guessCategoryFromFact(item)
                    list.add(
                        UserMemoryEntry(
                            category = cat,
                            fact = item.trim(),
                            updatedAt = fallbackNowMs
                        )
                    )
                }
            }
            list.filter { it.fact.isNotBlank() }
        } catch (e: Exception) {
            GepyLog.w(TAG, "Gagal mengurai memori lokal: ${e.message}")
            emptyList()
        }
    }

    /** Mengambil daftar entri memori lokal yang masih aktif & belum kedaluwarsa. */
    fun getLocalEntries(): List<UserMemoryEntry> {
        val nowMs = clockMsProvider()
        return readRawLocalEntriesUnsafe(nowMs)
            .filter { it.validUntilMs == null || it.validUntilMs > nowMs }
    }

    /** Mengambil daftar string fakta mentah yang aktif (backward compatibility). */
    fun getLocalFacts(): List<String> {
        return getLocalEntries().map { it.fact }
    }

    private fun persistEntries(entries: List<UserMemoryEntry>) {
        val jsonArr = JSONArray()
        val pruned = if (entries.size > MAX_LEGACY_PREFS_ENTRIES) entries.take(MAX_LEGACY_PREFS_ENTRIES) else entries
        pruned.forEach { entry ->
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("category", entry.category)
                put("subject", entry.subject)
                put("fact", entry.fact)
                put("updatedAt", entry.updatedAt)
                if (entry.validUntilMs != null) {
                    put("validUntilMs", entry.validUntilMs)
                }
            }
            jsonArr.put(obj)
        }
        setPrefString(KEY_LOCAL_FACTS, jsonArr.toString())
    }

    /**
     * Versi asinkron non-suspend dari [recordMemory] untuk dipanggil dari *Fast-Path Skill*
     * atau inspeksi transkrip percakapan lisan (< 1 ms tanpa memblokir thread pemanggil).
     */

    /**
     * Memeriksa transkrip audio pengguna secara real-time dengan perlindungan **Identity Shield**:
     * 1. Selalu memperkuat fakta yang topiknya disebut pengguna (*Spaced Repetition*).
     * 2. Koreksi nama eksplisit (*"namaku bukan X tapi Y"* atau *"ralat namaku Y"*) diizinkan memperbarui nama.
     * 3. Jika nama pengguna sudah tersimpan/terkonfirmasi, nama tersebut **DIKUNCI MUTLAK** dan
     *    DILARANG KERAS ditimpa oleh pola heuristik kasual (*"panggil mas"*, *"kenalkan kawan saya"*, dll).
     * 4. Pengenalan nama umum hanya berjalan jika nama pengguna saat ini masih kosong.
     */
    fun inspectTranscriptForProfile(transcript: String) {
        val trimmed = transcript.trim()
        if (trimmed.length < 4) return

        // Perkuat memori yang topiknya disebut dalam obrolan (Spaced Repetition Reinforcement)
        reinforceMatchingFacts(trimmed)

        // Cek apakah ada koreksi/ralat nama eksplisit terlebih dahulu
        val correctionMatch = NAME_CORRECTION_REGEX.find(trimmed)
        if (correctionMatch != null) {
            val correctedName = (correctionMatch.groups[1]?.value ?: correctionMatch.groups[2]?.value ?: correctionMatch.groups[3]?.value)?.trim().orEmpty()
            if (isValidName(correctedName)) {
                GepyLog.i(TAG, "Heuristic transcript mendeteksi koreksi nama eksplisit: $correctedName")
                setUserDisplayName(correctedName, isConfirmed = true)
                return
            }
        }

        val currentName = getUserDisplayName()
        // IDENTITY SHIELD: Jika nama pengguna belum ada/belum dikonfirmasi, periksa perkenalan nama awal
        if (currentName.isBlank() || !isNameConfirmed()) {
            val match = NAME_EXTRACTION_REGEX.find(trimmed)
            if (match != null && match.groupValues.size >= 2) {
                val potentialName = match.groupValues[1].trim()
                if (isValidName(potentialName)) {
                    if (currentName.isBlank()) {
                        GepyLog.i(TAG, "Heuristic transcript mendeteksi nama awal: $potentialName")
                        setUserDisplayName(potentialName, isConfirmed = true)
                        return
                    }
                }
            }
        }

        // Ekstraksi otomatis fakta personal lisan dari percakapan alami pengguna
        extractAndRecordConversationalFacts(trimmed)
    }

    /**
     * Menjalankan siklus **Konsolidasi Memori Kognitif & Refleksi Generatif (Gepy v5.3)**:
     * memangkas sinapsis Ebbinghaus yang usang dan menyintesis wawasan psikologis tingkat tinggi.
     */
    suspend fun runNightlyCognitiveConsolidation(
        wellnessSnapshot: com.teknologigawai.gepy.core.wellness.WellnessGuardianSnapshot? = null,
        nowMs: Long = clockMsProvider(),
        allowCloudLlm: Boolean = true
    ): ConsolidationReport {
        val chatKeys = chatKeyPoolProvider()
        val engine = CognitiveConsolidationEngine(
            userMemoryManager = this,
            apiKeyProvider = { chatKeys.firstOrNull() ?: EnvConfig.defaultGeminiApiKey },
            secondaryApiKeyProvider = { if (chatKeys.size > 1) chatKeys[1] else "" }
        )
        return engine.runConsolidationPass(
            wellnessSnapshot = wellnessSnapshot,
            nowMs = nowMs,
            allowCloudLlm = allowCloudLlm
        )
    }

    /**
     * Menjalankan evaluasi & sintesis memori pasca-panggilan suara (Live Voice Call):
     * Memangkas derau transkrip sesaat dan menyintesis fakta penting yang memiliki relasi jelas.
     */
    suspend fun synthesizePostCallMemories(
        turns: List<CallTranscriptTurn>,
        apiKeyProvider: () -> String = {
            chatKeyPoolProvider().firstOrNull() ?: EnvConfig.defaultGeminiApiKey
        },
        nowMs: Long = clockMsProvider(),
        secondaryApiKeyProvider: () -> String = {
            val pool = chatKeyPoolProvider()
            if (pool.size > 1) pool[1] else ""
        }
    ): List<String> {
        return postCallSynthesizer.synthesizeSession(
            turns = turns,
            memoryManager = this,
            apiKeyProvider = apiKeyProvider,
            nowMs = nowMs,
            secondaryApiKeyProvider = secondaryApiKeyProvider
        )
    }
}
