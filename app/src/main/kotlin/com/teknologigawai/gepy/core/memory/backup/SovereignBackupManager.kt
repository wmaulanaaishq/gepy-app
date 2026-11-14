package com.teknologigawai.gepy.core.memory.backup

import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Model pembungkus dokumen cadangan memori personal Gepy (Sovereign Memory Vault).
 *
 * Dirancang agar 100% portabel, independen terhadap perubahan skema ID lokal,
 * dan aman dari manipulasi berkas melalui pemeriksaan SHA-256.
 */
@Serializable
data class GepyVaultBackup(
    val formatVersion: Int = 1,
    val exportedAtMs: Long,
    val appVersion: String,
    val userDisplayName: String = "",
    val facts: List<TemporalFactDto>,
    val metadata: Map<String, String> = emptyMap(),
    val checksum: String = ""
)

/**
 * Data Transfer Object (DTO) untuk fakta temporal independen tanpa ID basis data internal.
 */
@Serializable
data class TemporalFactDto(
    val category: String,
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val factText: String,
    val confidence: Float = 1.0f,
    val validFromMs: Long,
    val validUntilMs: Long? = null,
    val isActive: Boolean = true,
    val source: String = "backup",
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Hasil dari proses pemulihan (impor) berkas cadangan ke dalam basis data Room v4.
 */
sealed interface VaultImportResult {
    data class Success(
        val importedCount: Int,
        val updatedCount: Int,
        val skippedCount: Int,
        val totalFactsInVault: Int,
        val userDisplayName: String? = null
    ) : VaultImportResult

    data class Failure(
        val reason: String,
        val throwable: Throwable? = null
    ) : VaultImportResult
}

/**
 * Rencana aksi penggabungan cerdas (Smart Merge Plan) yang memisahkan
 * data yang harus disisipkan baru vs data yang harus diperbarui di Room.
 */
data class SmartMergePlan(
    val toInsert: List<TemporalMemoryFactEntity>,
    val toUpdate: List<TemporalMemoryFactEntity>,
    val skippedCount: Int
)

/**
 * Manajer Pemulihan & Pencadangan Kedaulatan Memori (Sovereign Backup Manager).
 *
 * Bertanggung jawab atas:
 * 1. Serialisasi graf memori temporal menjadi JSON portabel (`GepyVaultBackup`).
 * 2. Streaming I/O hemat memori (beban rendah di Galaxy A06 RAM 4 GB) untuk Android Storage Access Framework (SAF).
 * 3. Validasi integritas berkas via SHA-256 checksum.
 * 4. Penggabungan cerdas (*Smart Merge*) idempoten: mencegah duplikasi fakta dan
 *    memperbarui masa berlaku agenda tanpa merusak data yang sudah ada.
 */
class SovereignBackupManager(
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
) {
    companion object {
        private const val TAG = "SovereignBackupManager"
        const val CURRENT_FORMAT_VERSION = 1
        const val DEFAULT_FILE_PREFIX = "gepy_memory_vault"

        /**
         * Menghitung hash SHA-256 hex string dari data byte.
         */
        fun calculateSha256(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Membangun objek [GepyVaultBackup] dari kumpulan entri fakta lokal Room.
     */
    fun createVaultBackup(
        facts: List<TemporalMemoryFactEntity>,
        userDisplayName: String,
        appVersion: String,
        exportedAtMs: Long = System.currentTimeMillis()
    ): GepyVaultBackup {
        val dtos = facts.map { entity ->
            TemporalFactDto(
                category = entity.category,
                subject = entity.subject,
                predicate = entity.predicate,
                objectValue = entity.objectValue,
                factText = entity.factText,
                confidence = entity.confidence,
                validFromMs = entity.validFromMs,
                validUntilMs = entity.validUntilMs,
                isActive = entity.isActive,
                source = entity.source,
                createdAtMs = entity.createdAtMs,
                updatedAtMs = entity.updatedAtMs
            )
        }

        // Hitung checksum integritas berdasarkan representasi kanonikal fakta
        val canonicalPayload = buildString {
            append("version=").append(CURRENT_FORMAT_VERSION).append(";")
            append("exported=").append(exportedAtMs).append(";")
            append("count=").append(dtos.size).append(";")
            dtos.forEach { dto ->
                append("${dto.subject}|${dto.predicate}|${dto.objectValue}|${dto.factText}|${dto.validUntilMs};")
            }
        }
        val checksum = calculateSha256(canonicalPayload.toByteArray(Charsets.UTF_8))

        return GepyVaultBackup(
            formatVersion = CURRENT_FORMAT_VERSION,
            exportedAtMs = exportedAtMs,
            appVersion = appVersion,
            userDisplayName = userDisplayName,
            facts = dtos,
            metadata = mapOf(
                "creator" to "Gepy Native Mobile AI",
                "fact_count" to dtos.size.toString()
            ),
            checksum = checksum
        )
    }

    /**
     * Mengekspor dokumen cadangan ke format JSON String.
     */
    fun exportToJson(backup: GepyVaultBackup): String {
        return json.encodeToString(backup)
    }

    /**
     * Mengekspor dokumen cadangan secara streaming langsung ke [OutputStream] (Storage Access Framework).
     */
    fun exportToStream(backup: GepyVaultBackup, outputStream: OutputStream) {
        val jsonString = exportToJson(backup)
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(jsonString)
            writer.flush()
        }
    }

    /**
     * Memvalidasi integritas berkas cadangan berdasarkan checksum SHA-256 jika tersedia.
     */
    fun verifyChecksum(backup: GepyVaultBackup): Boolean {
        if (backup.checksum.isBlank()) return true // Berkas warisan tanpa checksum tetap diterima

        val canonicalPayload = buildString {
            append("version=").append(backup.formatVersion).append(";")
            append("exported=").append(backup.exportedAtMs).append(";")
            append("count=").append(backup.facts.size).append(";")
            backup.facts.forEach { dto ->
                append("${dto.subject}|${dto.predicate}|${dto.objectValue}|${dto.factText}|${dto.validUntilMs};")
            }
        }
        val expected = calculateSha256(canonicalPayload.toByteArray(Charsets.UTF_8))
        return expected.equals(backup.checksum, ignoreCase = true)
    }

    /**
     * Mengurai string JSON menjadi [GepyVaultBackup] dengan pengecekan kesalahan lengkap.
     */
    fun parseVaultJson(jsonString: String): Result<GepyVaultBackup> {
        val trimmed = jsonString.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Berkas cadangan kosong."))
        }
        return try {
            val backup = json.decodeFromString<GepyVaultBackup>(trimmed)
            if (!verifyChecksum(backup)) {
                return Result.failure(IllegalStateException("Integritas data rusak: Checksum SHA-256 tidak cocok."))
            }
            Result.success(backup)
        } catch (t: Throwable) {
            GepyLog.e(TAG, "Gagal mem-parse JSON berkas cadangan: ${t.message}", t)
            Result.failure(t)
        }
    }

    /**
     * Mengurai dari [InputStream] secara streaming (Storage Access Framework).
     */
    fun parseVaultStream(inputStream: InputStream): Result<GepyVaultBackup> {
        return try {
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseVaultJson(content)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Menghasilkan rencana penggabungan cerdas (*Smart Merge Plan*) antara data lokal yang sudah ada
     * dengan fakta-fakta dari berkas cadangan.
     *
     * Aturan:
     * 1. Fakta yang identik tripletnya & masa berlakunya sama -> Lewati (Skipped).
     * 2. Fakta yang tripletnya cocok namun berkas cadangan memiliki masa berlaku / pembaruan lebih baru -> Update.
     * 3. Fakta baru yang belum ada di lokal -> Insert baru.
     */
    fun planSmartMerge(
        existingFacts: List<TemporalMemoryFactEntity>,
        vaultBackup: GepyVaultBackup,
        nowMs: Long = System.currentTimeMillis()
    ): SmartMergePlan {
        val toInsert = mutableListOf<TemporalMemoryFactEntity>()
        val toUpdate = mutableListOf<TemporalMemoryFactEntity>()
        var skipped = 0

        // Buat indeks pencarian cepat fakta lokal
        val existingMap = existingFacts.associateBy { buildFactKey(it.subject, it.predicate, it.objectValue) }
        val existingTextMap = existingFacts.associateBy { it.factText.trim().lowercase() }

        for (dto in vaultBackup.facts) {
            val cleanText = dto.factText.trim()
            if (cleanText.isBlank()) {
                skipped++
                continue
            }

            val key = buildFactKey(dto.subject, dto.predicate, dto.objectValue)
            val matchedByKey = existingMap[key]
            val matchedByText = existingTextMap[cleanText.lowercase()]
            val match = matchedByKey ?: matchedByText

            if (match != null) {
                // Periksa apakah berkas cadangan membawa informasi yang lebih baru / diperpanjang
                val backupIsNewer = dto.updatedAtMs > match.updatedAtMs ||
                    (dto.validUntilMs != null && (match.validUntilMs == null || dto.validUntilMs > match.validUntilMs)) ||
                    (dto.isActive && !match.isActive)

                if (backupIsNewer) {
                    val updatedEntity = match.copy(
                        category = dto.category,
                        subject = dto.subject,
                        predicate = dto.predicate,
                        objectValue = dto.objectValue,
                        factText = cleanText,
                        confidence = dto.confidence,
                        validUntilMs = dto.validUntilMs,
                        isActive = dto.isActive,
                        source = dto.source,
                        updatedAtMs = maxOf(dto.updatedAtMs, nowMs)
                    )
                    toUpdate.add(updatedEntity)
                } else {
                    skipped++
                }
            } else {
                // Fakta baru yang belum pernah tercatat di HP lokal
                val newEntity = TemporalMemoryFactEntity(
                    id = 0L, // Room akan memberikan auto-generated ID baru
                    category = dto.category,
                    subject = dto.subject,
                    predicate = dto.predicate,
                    objectValue = dto.objectValue,
                    factText = cleanText,
                    confidence = dto.confidence,
                    validFromMs = dto.validFromMs,
                    validUntilMs = dto.validUntilMs,
                    isActive = dto.isActive,
                    source = dto.source,
                    createdAtMs = dto.createdAtMs,
                    updatedAtMs = maxOf(dto.updatedAtMs, nowMs)
                )
                toInsert.add(newEntity)
            }
        }

        return SmartMergePlan(
            toInsert = toInsert,
            toUpdate = toUpdate,
            skippedCount = skipped
        )
    }

    private fun buildFactKey(subject: String, predicate: String, objectValue: String): String {
        return "${subject.trim().lowercase()}:${predicate.trim().lowercase()}:${objectValue.trim().lowercase()}"
    }
}
