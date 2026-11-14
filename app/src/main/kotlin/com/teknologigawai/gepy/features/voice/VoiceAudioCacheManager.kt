package com.teknologigawai.gepy.features.voice

import android.content.Context
import com.teknologigawai.gepy.core.logging.GepyLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pemecah teks respon menjadi klausa/kalimat alami untuk caching audio per-kalimat
 * serta pembersih tag ekspresi ElevenLabs (`[softly]`, `[laughs]`, dll.) saat fallback ke Android TTS.
 */
object VoiceClauseChunker {
    private val INLINE_TAG_REGEX = Regex("""\[[^\[\]]{1,48}]""")
    private val DISPLAY_AUDIO_TAG_REGEX = Regex("""\[([a-z][a-z\s_-]{1,31})]""")

    /**
     * Memecah [rawText] secara deterministik pada batas kalimat (`.`, `!`, `?`, `;`, baris baru)
     * tanpa memotong di dalam tanda kurung siku tag audio `[...]`. Jika satu kalimat melebihi
     * [maxChunkChars], kalimat dipecah secara halus pada tanda koma di luar tag `[...]`.
     */
    fun splitIntoClauses(rawText: String, maxChunkChars: Int = 140): List<String> {
        val cleaned = rawText.replace(Regex("""[ \t]+"""), " ").trim()
        if (cleaned.isEmpty()) return emptyList()

        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0

        for (i in cleaned.indices) {
            val ch = cleaned[i]
            if (ch == '[') bracketDepth++
            else if (ch == ']' && bracketDepth > 0) bracketDepth--

            if (ch == '\n' || ch == '\r') {
                if (bracketDepth == 0) {
                    val part = current.toString().trim()
                    if (part.isNotEmpty()) sentences.add(part)
                    current.setLength(0)
                    continue
                }
            }

            current.append(ch)

            if (bracketDepth == 0 && (ch == '.' || ch == '!' || ch == '?' || ch == ';')) {
                val nextChar = cleaned.getOrNull(i + 1)
                val isDecimalDot = ch == '.' &&
                    cleaned.getOrNull(i - 1)?.isDigit() == true &&
                    nextChar?.isDigit() == true
                if (!isDecimalDot && (nextChar == null || nextChar.isWhitespace() || nextChar == '[')) {
                    val part = current.toString().trim()
                    if (part.isNotEmpty()) sentences.add(part)
                    current.setLength(0)
                }
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) sentences.add(tail)

        val result = mutableListOf<String>()
        for (sentence in sentences) {
            if (sentence.length <= maxChunkChars) {
                result.add(sentence)
            } else {
                result.addAll(splitLongClauseByComma(sentence, maxChunkChars))
            }
        }
        return result.filter { it.isNotBlank() }
    }

    private fun splitLongClauseByComma(sentence: String, maxChunkChars: Int): List<String> {
        val pieces = mutableListOf<String>()
        val buf = StringBuilder()
        var bracketDepth = 0

        for (i in sentence.indices) {
            val ch = sentence[i]
            if (ch == '[') bracketDepth++
            else if (ch == ']' && bracketDepth > 0) bracketDepth--

            buf.append(ch)
            if (bracketDepth == 0 && ch == ',' && buf.length >= (maxChunkChars / 2)) {
                val part = buf.toString().trim()
                if (part.isNotEmpty()) pieces.add(part)
                buf.setLength(0)
            }
        }
        val rem = buf.toString().trim()
        if (rem.isNotEmpty()) pieces.add(rem)
        return pieces
    }

    /** Menghapus tag ekspresi ElevenLabs (`[laughs]`, `[softly]`, dll.) sebelum dibacakan Android TTS. */
    fun stripAudioTagsForSystemTts(text: String): String {
        return INLINE_TAG_REGEX.replace(text, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Menghapus tag ekspresi suara ElevenLabs bertuliskan huruf kecil (`[softly]`, `[laughs]`, `[clears throat]`)
     * dari tampilan teks Chat/memori sembari tetap mempertahankan sitasi `[1]` atau `[BMKG]`.
     */
    fun stripAudioTagsForDisplay(text: String): String {
        return DISPLAY_AUDIO_TAG_REGEX.replace(text, " ")
            .replace(Regex(""" +"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .trim()
    }

    /** Menormalisasi teks agar perbedaan spasi/huruf kapital tetap mengenai klip cache yang sama. */
    fun normalizeCacheKeyText(text: String): String {
        return text.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)
    }
}

@Serializable
data class VoiceCacheClipEntry(
    val hash: String,
    val voiceId: String,
    val text: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val lastAccessedAtMs: Long,
    val modelId: String = VoiceAudioCacheManager.DEFAULT_MODEL_ID,
    val stability: Float = VoiceAudioCacheManager.DEFAULT_STABILITY,
    val similarityBoost: Float = VoiceAudioCacheManager.DEFAULT_SIMILARITY_BOOST,
    val languageCode: String = VoiceAudioCacheManager.DEFAULT_LANGUAGE_CODE,
    val outputFormat: String = VoiceAudioCacheManager.DEFAULT_OUTPUT_FORMAT
)

@Serializable
data class VoiceCacheIndex(
    val version: Int = 2,
    val clips: MutableList<VoiceCacheClipEntry> = mutableListOf()
)

@Serializable
data class VoiceVaultManifest(
    val formatVersion: Int = 2,
    val exportedAtMs: Long,
    val clipCount: Int,
    val clips: List<VoiceCacheClipEntry>
)

data class VoiceCacheStats(
    val clipCount: Int,
    val totalBytes: Long
) {
    val totalMegabytes: Double
        get() = totalBytes / (1024.0 * 1024.0)
}

data class VoiceVaultImportResult(
    val importedCount: Int,
    val skippedCount: Int,
    val totalClipsInArchive: Int
)

/**
 * Manajer Auto-Cache Audio Lokal & Brankas Cadangan ZIP (`SAF` Google Drive) untuk suara `gepy`.
 *
 * - Menyimpan setiap klip MP3 per-kalimat di `filesDir/voice_cache/{voiceId}/{sha256}.mp3`
 *   disertai indeks metadata `index.json`. Kunci hash mengikutsertakan `voiceId`, `modelId`,
 *   slider `stability` & `similarityBoost`, `languageCode`, `outputFormat`, serta teks ter-normalisasi.
 * - Menerapkan pemangkasan **LRU (Least Recently Used)** otomatis agar total cache tidak
 *   pernah melampaui anggaran penyimpanan (`maxCacheMb`, default `200 MB`, dapat diatur hingga `250 MB` tanpa beban RAM).
 * - Menyediakan `statsFlow` reaktif untuk UI Dashboard serta ekspor streaming `ZipOutputStream`
 *   dan impor *Smart Merge* `ZipInputStream` dengan proteksi anti **ZIP Slip path traversal**.
 */
class VoiceAudioCacheManager(
    private val rootDir: File,
    private val maxCacheMbProvider: () -> Int = { DEFAULT_MAX_CACHE_MB },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    constructor(
        context: Context,
        maxCacheMbProvider: () -> Int = { DEFAULT_MAX_CACHE_MB }
    ) : this(
        rootDir = File(context.applicationContext.filesDir, "voice_cache"),
        maxCacheMbProvider = maxCacheMbProvider
    )

    companion object {
        private const val TAG = "VoiceAudioCacheManager"
        private const val INDEX_FILE_NAME = "index.json"
        private const val MANIFEST_ENTRY_NAME = "manifest.json"
        private const val CLIPS_DIR_PREFIX = "clips/"

        const val DEFAULT_MAX_CACHE_MB: Int = 200
        const val DEFAULT_MODEL_ID: String = "eleven_v4_turbo"
        const val DEFAULT_STABILITY: Float = 0.45f
        const val DEFAULT_SIMILARITY_BOOST: Float = 0.75f
        const val DEFAULT_LANGUAGE_CODE: String = "id"
        const val DEFAULT_OUTPUT_FORMAT: String = ElevenLabsTtsClient.DEFAULT_OUTPUT_FORMAT

        fun normalizeSliderValue(value: Float): String {
            val clamped = if (value.isNaN()) 0.5f else value.coerceIn(0f, 1f)
            return String.format(Locale.US, "%.2f", clamped)
        }

        fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return buildString(bytes.size * 2) {
                for (b in bytes) {
                    append(String.format(Locale.US, "%02x", b))
                }
            }
        }

        private fun sanitizePathSegment(segment: String): String {
            val cleaned = segment.trim().replace(Regex("""[^a-zA-Z0-9_-]"""), "_")
            return cleaned.ifEmpty { "default_voice" }
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val indexFile: File get() = File(rootDir, INDEX_FILE_NAME)

    private val _statsFlow = MutableStateFlow(VoiceCacheStats(clipCount = 0, totalBytes = 0L))
    val statsFlow: StateFlow<VoiceCacheStats> = _statsFlow.asStateFlow()

    init {
        _statsFlow.value = getStats()
    }

    /**
     * Menghitung hash deterministik SHA-256 untuk kombinasi [voiceId], [modelId], slider suara
     * ([stability], [similarityBoost]), [languageCode], [outputFormat], dan teks klausa [text].
     */
    fun computeClipHash(
        voiceId: String,
        text: String,
        modelId: String = DEFAULT_MODEL_ID,
        stability: Float = DEFAULT_STABILITY,
        similarityBoost: Float = DEFAULT_SIMILARITY_BOOST,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
        outputFormat: String = DEFAULT_OUTPUT_FORMAT
    ): String {
        val safeVoice = sanitizePathSegment(voiceId)
        val normModel = modelId.trim().ifEmpty { DEFAULT_MODEL_ID }.lowercase(Locale.ROOT)
        val stabStr = normalizeSliderValue(stability)
        val simStr = normalizeSliderValue(similarityBoost)
        val normLang = languageCode.trim().ifEmpty { DEFAULT_LANGUAGE_CODE }.lowercase(Locale.ROOT)
        val normFormat = outputFormat.trim().ifEmpty { DEFAULT_OUTPUT_FORMAT }.lowercase(Locale.ROOT)
        val normText = VoiceClauseChunker.normalizeCacheKeyText(text)
        return sha256Hex("$safeVoice|$normModel|$stabStr|$simStr|$normLang|$normFormat|$normText")
    }

    /**
     * Memeriksa secara *read-only* apakah berkas `.mp3` fisik valid untuk klausa [text]
     * sudah tersedia di cache lokal tanpa mengubah metadata akses maupun `index.json`.
     */
    fun hasCachedClip(
        voiceId: String,
        text: String,
        modelId: String = DEFAULT_MODEL_ID,
        stability: Float = DEFAULT_STABILITY,
        similarityBoost: Float = DEFAULT_SIMILARITY_BOOST,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
        outputFormat: String = DEFAULT_OUTPUT_FORMAT,
        allowVoiceOnlyFallback: Boolean = false
    ): Boolean {
        return synchronized(lock) {
            val safeVoice = sanitizePathSegment(voiceId)
            val exactHash = computeClipHash(
                voiceId = safeVoice,
                text = text,
                modelId = modelId,
                stability = stability,
                similarityBoost = similarityBoost,
                languageCode = languageCode,
                outputFormat = outputFormat
            )
            val exactFile = File(File(rootDir, safeVoice), "$exactHash.mp3")
            if (exactFile.exists() && exactFile.length() > 0L) {
                return@synchronized true
            }
            if (!allowVoiceOnlyFallback) {
                return@synchronized false
            }
            val normText = VoiceClauseChunker.normalizeCacheKeyText(text)
            if (normText.isEmpty()) {
                return@synchronized false
            }
            val index = loadIndexLocked()
            index.clips.any { candidate ->
                sanitizePathSegment(candidate.voiceId) == safeVoice &&
                    VoiceClauseChunker.normalizeCacheKeyText(candidate.text) == normText &&
                    resolveValidClipFileLocked(safeVoice, candidate.fileName) != null
            }
        }
    }

    /**
     * Mengembalikan berkas `.mp3` lokal jika sudah tersedia di cache (`0 ms`, `0 credit`).
     * Saat [allowVoiceOnlyFallback] aktif (mode offline tanpa API Key / circuit terbuka),
     * apabila hash parameter eksak belum ditemukan, pencarian tetap dapat mencocokkan klip valid
     * terakhir berdasarkan `(voiceId + teks ter-normalisasi)`.
     */
    fun lookupCachedClip(
        voiceId: String,
        text: String,
        modelId: String = DEFAULT_MODEL_ID,
        stability: Float = DEFAULT_STABILITY,
        similarityBoost: Float = DEFAULT_SIMILARITY_BOOST,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
        outputFormat: String = DEFAULT_OUTPUT_FORMAT,
        allowVoiceOnlyFallback: Boolean = false
    ): File? {
        return synchronized(lock) {
            val safeVoice = sanitizePathSegment(voiceId)
            val hash = computeClipHash(
                voiceId = safeVoice,
                text = text,
                modelId = modelId,
                stability = stability,
                similarityBoost = similarityBoost,
                languageCode = languageCode,
                outputFormat = outputFormat
            )
            val target = File(File(rootDir, safeVoice), "$hash.mp3")
            val index = loadIndexLocked()
            val now = clockMs()

            if (target.exists() && target.length() > 0L) {
                val existingIdx = index.clips.indexOfFirst { it.hash == hash && sanitizePathSegment(it.voiceId) == safeVoice }
                var statsChanged = false
                if (existingIdx >= 0) {
                    val old = index.clips[existingIdx]
                    index.clips[existingIdx] = old.copy(lastAccessedAtMs = now, sizeBytes = target.length())
                } else {
                    index.clips.add(
                        VoiceCacheClipEntry(
                            hash = hash,
                            voiceId = safeVoice,
                            text = text.trim(),
                            fileName = "$hash.mp3",
                            sizeBytes = target.length(),
                            createdAtMs = now,
                            lastAccessedAtMs = now,
                            modelId = modelId.trim().ifEmpty { DEFAULT_MODEL_ID },
                            stability = stability.coerceIn(0f, 1f),
                            similarityBoost = similarityBoost.coerceIn(0f, 1f),
                            languageCode = languageCode.trim().ifEmpty { DEFAULT_LANGUAGE_CODE },
                            outputFormat = outputFormat.trim().ifEmpty { DEFAULT_OUTPUT_FORMAT }
                        )
                    )
                    statsChanged = true
                }
                saveIndexLocked(index)
                if (statsChanged) {
                    _statsFlow.value = computeStatsFromIndexLocked(index)
                }
                return@synchronized target
            }

            if (!allowVoiceOnlyFallback) {
                return@synchronized null
            }

            val normText = VoiceClauseChunker.normalizeCacheKeyText(text)
            if (normText.isEmpty()) {
                return@synchronized null
            }
            var bestIdx = -1
            var bestAccessedAtMs = Long.MIN_VALUE
            var bestFile: File? = null
            for (idx in index.clips.indices) {
                val candidate = index.clips[idx]
                if (sanitizePathSegment(candidate.voiceId) == safeVoice &&
                    VoiceClauseChunker.normalizeCacheKeyText(candidate.text) == normText
                ) {
                    val resolvedFile = resolveValidClipFileLocked(safeVoice, candidate.fileName)
                    if (resolvedFile != null && candidate.lastAccessedAtMs >= bestAccessedAtMs) {
                        bestAccessedAtMs = candidate.lastAccessedAtMs
                        bestIdx = idx
                        bestFile = resolvedFile
                    }
                }
            }
            if (bestIdx >= 0 && bestFile != null) {
                val old = index.clips[bestIdx]
                index.clips[bestIdx] = old.copy(lastAccessedAtMs = now, sizeBytes = bestFile.length())
                saveIndexLocked(index)
                return@synchronized bestFile
            }
            null
        }
    }

    /** Memastikan total ukuran berkas cache di disk tidak melampaui batas [maxCacheMbProvider] saat ini. */
    fun enforceCacheBudget(): Int {
        return synchronized(lock) {
            val index = loadIndexLocked()
            val beforeCount = index.clips.size
            trimToMaxBytesLocked(index)
            val removedCount = (beforeCount - index.clips.size).coerceAtLeast(0)
            if (removedCount > 0) {
                saveIndexLocked(index)
                _statsFlow.value = computeStatsFromIndexLocked(index)
            }
            removedCount
        }
    }

    private fun resolveValidClipFileLocked(safeVoice: String, fileName: String): File? {
        val safeName = File(fileName).name
        val target = File(File(rootDir, safeVoice), safeName)
        return try {
            if (target.canonicalPath.startsWith(rootDir.canonicalPath) && target.exists() && target.length() > 0L) {
                target
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Menyiapkan path berkas `.mp3` tujuan sebelum diunduh dari ElevenLabs. */
    fun prepareTargetFile(
        voiceId: String,
        text: String,
        modelId: String = DEFAULT_MODEL_ID,
        stability: Float = DEFAULT_STABILITY,
        similarityBoost: Float = DEFAULT_SIMILARITY_BOOST,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
        outputFormat: String = DEFAULT_OUTPUT_FORMAT
    ): File {
        val safeVoice = sanitizePathSegment(voiceId)
        val hash = computeClipHash(
            voiceId = safeVoice,
            text = text,
            modelId = modelId,
            stability = stability,
            similarityBoost = similarityBoost,
            languageCode = languageCode,
            outputFormat = outputFormat
        )
        val dir = File(rootDir, safeVoice)
        dir.mkdirs()
        return File(dir, "$hash.mp3")
    }

    /** Mendaftarkan klip MP3 yang baru diunduh ke `index.json` serta menjalankan LRU eviction bila penuh. */
    fun registerCachedClip(
        voiceId: String,
        text: String,
        mp3File: File,
        modelId: String = DEFAULT_MODEL_ID,
        stability: Float = DEFAULT_STABILITY,
        similarityBoost: Float = DEFAULT_SIMILARITY_BOOST,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
        outputFormat: String = DEFAULT_OUTPUT_FORMAT
    ) {
        synchronized(lock) {
            if (!mp3File.exists() || mp3File.length() <= 0L) return@synchronized
            val safeVoice = sanitizePathSegment(voiceId)
            val hash = computeClipHash(
                voiceId = safeVoice,
                text = text,
                modelId = modelId,
                stability = stability,
                similarityBoost = similarityBoost,
                languageCode = languageCode,
                outputFormat = outputFormat
            )
            val now = clockMs()
            val index = loadIndexLocked()

            index.clips.removeAll { it.hash == hash && it.voiceId == safeVoice }
            index.clips.add(
                VoiceCacheClipEntry(
                    hash = hash,
                    voiceId = safeVoice,
                    text = text.trim(),
                    fileName = mp3File.name,
                    sizeBytes = mp3File.length(),
                    createdAtMs = now,
                    lastAccessedAtMs = now,
                    modelId = modelId.trim().ifEmpty { DEFAULT_MODEL_ID },
                    stability = stability.coerceIn(0f, 1f),
                    similarityBoost = similarityBoost.coerceIn(0f, 1f),
                    languageCode = languageCode.trim().ifEmpty { DEFAULT_LANGUAGE_CODE },
                    outputFormat = outputFormat.trim().ifEmpty { DEFAULT_OUTPUT_FORMAT }
                )
            )
            trimToMaxBytesLocked(index)
            saveIndexLocked(index)
            _statsFlow.value = computeStatsFromIndexLocked(index)
        }
    }

    /** Mengembalikan jumlah klip dan total ukuran penyimpanan cache suara saat ini. */
    fun getStats(): VoiceCacheStats {
        return synchronized(lock) {
            val index = loadIndexLocked()
            var validCount = 0
            var totalBytes = 0L
            val validEntries = mutableListOf<VoiceCacheClipEntry>()

            for (entry in index.clips) {
                val f = File(File(rootDir, sanitizePathSegment(entry.voiceId)), entry.fileName)
                if (f.exists() && f.length() > 0L) {
                    val len = f.length()
                    validCount++
                    totalBytes += len
                    validEntries.add(if (entry.sizeBytes != len) entry.copy(sizeBytes = len) else entry)
                }
            }
            if (validEntries.size != index.clips.size) {
                saveIndexLocked(VoiceCacheIndex(clips = validEntries))
            }
            val stats = VoiceCacheStats(clipCount = validCount, totalBytes = totalBytes)
            _statsFlow.value = stats
            stats
        }
    }

    private fun computeStatsFromIndexLocked(index: VoiceCacheIndex): VoiceCacheStats {
        var validCount = 0
        var totalBytes = 0L
        for (entry in index.clips) {
            val f = File(File(rootDir, sanitizePathSegment(entry.voiceId)), entry.fileName)
            if (f.exists() && f.length() > 0L) {
                validCount++
                totalBytes += f.length()
            }
        }
        return VoiceCacheStats(clipCount = validCount, totalBytes = totalBytes)
    }

    /** Mengembalikan daftar seluruh klip audio valid untuk inspeksi & kurasi dataset suara. */
    fun getAllCachedClipsSnapshot(): List<VoiceCacheClipEntry> {
        return synchronized(lock) {
            val index = loadIndexLocked()
            index.clips.mapNotNull { entry ->
                val f = File(File(rootDir, sanitizePathSegment(entry.voiceId)), entry.fileName)
                if (f.exists() && f.length() > 0L) {
                    val len = f.length()
                    if (entry.sizeBytes != len) entry.copy(sizeBytes = len) else entry
                } else {
                    null
                }
            }.sortedByDescending { it.lastAccessedAtMs }
        }
    }

    /** Mengembalikan berkas `.mp3` fisik dari entri metadata jika tersedia dan aman di dalam `rootDir`. */
    fun resolveClipFile(entry: VoiceCacheClipEntry): File? {
        val safeVoice = sanitizePathSegment(entry.voiceId)
        val safeName = File(entry.fileName).name
        val target = File(File(rootDir, safeVoice), safeName)
        return try {
            if (target.canonicalPath.startsWith(rootDir.canonicalPath) && target.exists() && target.length() > 0L) {
                target
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Menghapus satu klip audio spesifik dari disk dan `index.json` untuk kurasi dataset. */
    fun deleteClip(voiceId: String, hash: String): Boolean {
        return synchronized(lock) {
            val safeVoice = sanitizePathSegment(voiceId)
            val index = loadIndexLocked()
            val targetEntry = index.clips.firstOrNull {
                sanitizePathSegment(it.voiceId) == safeVoice && it.hash == hash
            }
            val fileName = targetEntry?.fileName ?: "$hash.mp3"
            val file = File(File(rootDir, safeVoice), File(fileName).name)
            val fileDeleted = if (file.exists()) file.delete() else false
            val removedFromIndex = index.clips.removeAll {
                sanitizePathSegment(it.voiceId) == safeVoice && it.hash == hash
            }
            if (removedFromIndex) {
                saveIndexLocked(index)
            }
            if (fileDeleted || removedFromIndex) {
                _statsFlow.value = computeStatsFromIndexLocked(index)
            }
            fileDeleted || removedFromIndex
        }
    }

    /** Menghapus seluruh berkas audio cache lokal dan mengosongkan `index.json`. */
    fun clearAllCache(): Int {
        return synchronized(lock) {
            val index = loadIndexLocked()
            val removed = index.clips.size
            if (rootDir.exists()) {
                rootDir.listFiles()?.forEach { child ->
                    if (child.isDirectory) {
                        child.deleteRecursively()
                    } else {
                        child.delete()
                    }
                }
            }
            saveIndexLocked(VoiceCacheIndex())
            _statsFlow.value = VoiceCacheStats(clipCount = 0, totalBytes = 0L)
            removed
        }
    }

    /**
     * Mengekspor seluruh klip MP3 lokal dan `manifest.json` ke [outputStream] dalam format `.zip`
     * secara streaming (`8 KB` buffer) agar aman bagi anggaran RAM Galaxy A06 (ceiling LMK `< 240 MB`).
     */
    fun exportVaultToZipStream(outputStream: OutputStream): Int {
        return synchronized(lock) {
            val index = loadIndexLocked()
            val exportable = index.clips.filter { entry ->
                val f = File(File(rootDir, sanitizePathSegment(entry.voiceId)), entry.fileName)
                f.exists() && f.length() > 0L
            }

            val manifest = VoiceVaultManifest(
                exportedAtMs = clockMs(),
                clipCount = exportable.size,
                clips = exportable
            )
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                val manifestEntry = ZipEntry(MANIFEST_ENTRY_NAME)
                zos.putNextEntry(manifestEntry)
                zos.write(manifestBytes)
                zos.closeEntry()

                val buffer = ByteArray(8 * 1024)
                for (clip in exportable) {
                    val safeVoice = sanitizePathSegment(clip.voiceId)
                    val file = File(File(rootDir, safeVoice), clip.fileName)
                    if (!file.exists()) continue

                    val entryPath = "$CLIPS_DIR_PREFIX$safeVoice/${clip.fileName}"
                    zos.putNextEntry(ZipEntry(entryPath))
                    FileInputStream(file).use { fis ->
                        while (true) {
                            val read = fis.read(buffer)
                            if (read == -1) break
                            if (read > 0) zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
                zos.finish()
            }
            exportable.size
        }
    }

    /**
     * Memulihkan klip audio dari arsip `.zip` ke cache lokal menggunakan strategi *Smart Merge*
     * (melewati klip yang hash-nya sudah ada di HP) serta menolak entri berbahaya (*ZIP Slip*).
     */
    fun importVaultFromZipStream(inputStream: InputStream): VoiceVaultImportResult {
        return synchronized(lock) {
            rootDir.mkdirs()
            val index = loadIndexLocked()
            val existingKeys = index.clips.mapTo(mutableSetOf()) { "${sanitizePathSegment(it.voiceId)}|${it.hash}" }

            var parsedManifest: VoiceVaultManifest? = null
            val extractedClipFiles = mutableListOf<Triple<String, String, File>>() // voiceId, fileName, file
            var skippedCount = 0

            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val rawName = entry.name.replace('\\', '/')
                    if (rawName.contains("..") || rawName.startsWith("/") || rawName.startsWith("\\")) {
                        GepyLog.w(TAG, "Menolak entri ZIP berbahaya (path traversal): $rawName")
                        zis.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }

                    if (rawName == MANIFEST_ENTRY_NAME) {
                        val baos = ByteArrayOutputStream()
                        while (true) {
                            val read = zis.read(buffer)
                            if (read == -1) break
                            if (read > 0) baos.write(buffer, 0, read)
                        }
                        parsedManifest = try {
                            json.decodeFromString<VoiceVaultManifest>(baos.toString(Charsets.UTF_8.name()))
                        } catch (e: Throwable) {
                            GepyLog.w(TAG, "Gagal mem-parse manifest.json di arsip suara: ${e.message}")
                            null
                        }
                    } else if (rawName.startsWith(CLIPS_DIR_PREFIX) && rawName.endsWith(".mp3", ignoreCase = true)) {
                        val rel = rawName.removePrefix(CLIPS_DIR_PREFIX)
                        val parts = rel.split('/')
                        if (parts.size == 2) {
                            val safeVoice = sanitizePathSegment(parts[0])
                            val safeFileName = File(parts[1]).name
                            val hash = safeFileName.removeSuffix(".mp3")
                            val key = "$safeVoice|$hash"

                            val voiceDir = File(rootDir, safeVoice)
                            voiceDir.mkdirs()
                            val targetFile = File(voiceDir, safeFileName)
                            val canonicalRoot = rootDir.canonicalPath
                            if (!targetFile.canonicalPath.startsWith(canonicalRoot)) {
                                zis.closeEntry()
                                continue
                            }

                            if (existingKeys.contains(key) && targetFile.exists() && targetFile.length() > 0L) {
                                skippedCount++
                            } else {
                                val tempFile = File(voiceDir, "$safeFileName.tmp")
                                var written = 0L
                                FileOutputStream(tempFile).use { fos ->
                                    while (true) {
                                        val read = zis.read(buffer)
                                        if (read == -1) break
                                        if (read > 0) {
                                            fos.write(buffer, 0, read)
                                            written += read
                                        }
                                    }
                                    fos.flush()
                                }
                                if (written > 0L) {
                                    if (targetFile.exists()) targetFile.delete()
                                    if (!tempFile.renameTo(targetFile)) {
                                        tempFile.copyTo(targetFile, overwrite = true)
                                        tempFile.delete()
                                    }
                                    extractedClipFiles.add(Triple(safeVoice, safeFileName, targetFile))
                                } else {
                                    tempFile.delete()
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }

            val manifestByKey = parsedManifest?.clips?.associateBy {
                "${sanitizePathSegment(it.voiceId)}|${it.hash}"
            }.orEmpty()

            val now = clockMs()
            var importedCount = 0
            for ((safeVoice, fileName, file) in extractedClipFiles) {
                val hash = fileName.removeSuffix(".mp3")
                val key = "$safeVoice|$hash"
                val meta = manifestByKey[key]
                index.clips.removeAll { sanitizePathSegment(it.voiceId) == safeVoice && it.hash == hash }
                index.clips.add(
                    VoiceCacheClipEntry(
                        hash = hash,
                        voiceId = safeVoice,
                        text = meta?.text.orEmpty(),
                        fileName = fileName,
                        sizeBytes = file.length(),
                        createdAtMs = meta?.createdAtMs ?: now,
                        lastAccessedAtMs = now,
                        modelId = meta?.modelId?.ifBlank { DEFAULT_MODEL_ID } ?: DEFAULT_MODEL_ID,
                        stability = (meta?.stability ?: DEFAULT_STABILITY).coerceIn(0f, 1f),
                        similarityBoost = (meta?.similarityBoost ?: DEFAULT_SIMILARITY_BOOST).coerceIn(0f, 1f),
                        languageCode = meta?.languageCode?.ifBlank { DEFAULT_LANGUAGE_CODE } ?: DEFAULT_LANGUAGE_CODE,
                        outputFormat = meta?.outputFormat?.ifBlank { DEFAULT_OUTPUT_FORMAT } ?: DEFAULT_OUTPUT_FORMAT
                    )
                )
                importedCount++
            }

            trimToMaxBytesLocked(index)
            saveIndexLocked(index)
            _statsFlow.value = computeStatsFromIndexLocked(index)

            val totalArchive = maxOf(parsedManifest?.clipCount ?: 0, importedCount + skippedCount)
            VoiceVaultImportResult(
                importedCount = importedCount,
                skippedCount = skippedCount,
                totalClipsInArchive = totalArchive
            )
        }
    }

    private fun trimToMaxBytesLocked(index: VoiceCacheIndex) {
        val maxBytes = maxCacheMbProvider().coerceIn(5, 500) * 1024L * 1024L
        var total = index.clips.sumOf { it.sizeBytes }
        if (total <= maxBytes) return

        val sortedOldestFirst = index.clips.sortedBy { it.lastAccessedAtMs }.toMutableList()
        while (total > maxBytes && sortedOldestFirst.isNotEmpty()) {
            val victim = sortedOldestFirst.removeAt(0)
            val file = File(File(rootDir, sanitizePathSegment(victim.voiceId)), victim.fileName)
            val freed = if (file.exists()) {
                val len = file.length()
                file.delete()
                len
            } else {
                victim.sizeBytes
            }
            total -= freed
            index.clips.removeAll { it.hash == victim.hash && it.voiceId == victim.voiceId }
        }
    }

    private fun loadIndexLocked(): VoiceCacheIndex {
        if (!indexFile.exists()) return VoiceCacheIndex()
        return try {
            val raw = indexFile.readText(Charsets.UTF_8)
            json.decodeFromString<VoiceCacheIndex>(raw)
        } catch (e: Throwable) {
            GepyLog.w(TAG, "Gagal membaca index.json cache suara, membuat ulang: ${e.message}")
            VoiceCacheIndex()
        }
    }

    private fun saveIndexLocked(index: VoiceCacheIndex) {
        try {
            rootDir.mkdirs()
            val tmp = File(rootDir, "$INDEX_FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(index), Charsets.UTF_8)
            if (indexFile.exists()) indexFile.delete()
            if (!tmp.renameTo(indexFile)) {
                tmp.copyTo(indexFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Throwable) {
            GepyLog.w(TAG, "Gagal menyimpan index.json cache suara: ${e.message}")
        }
    }
}
