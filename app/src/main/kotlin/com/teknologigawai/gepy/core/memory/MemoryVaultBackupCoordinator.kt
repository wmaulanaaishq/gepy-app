package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.core.auth.GoogleAuthManager
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.memory.backup.GepyVaultBackup
import com.teknologigawai.gepy.core.memory.backup.SovereignBackupManager
import com.teknologigawai.gepy.core.memory.backup.VaultImportResult
import com.teknologigawai.gepy.core.network.CloudflareGatewayClient
import com.teknologigawai.gepy.data.database.GepyDao
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Koordinator ekspor/impor terenkripsi Sovereign Vault (JSON/Stream) & sinkronisasi cloud Supabase pgvector opsional.
 */
internal class MemoryVaultBackupCoordinator(
    private val authManager: GoogleAuthManager?,
    private val gatewayClient: CloudflareGatewayClient?,
    private val gepyDao: GepyDao?,
    private val clockMsProvider: () -> Long,
    private val scope: CoroutineScope,
    private val temporalLock: Any,
    private val temporalFactsCache: MutableList<TemporalMemoryFactEntity>,
    private val allocateInMemoryFactId: () -> Long,
    private val vectorEngine: HybridVectorMemoryEngine,
    private val getUserDisplayName: () -> String,
    private val setUserDisplayName: (String, Boolean) -> Unit,
    private val getAllTemporalFactsSnapshot: () -> List<TemporalMemoryFactEntity>,
    private val getActiveTemporalFactsSync: (Long) -> List<TemporalMemoryFactEntity>,
    private val persistEntries: (List<UserMemoryEntry>) -> Unit
) {
    private val backupManager: SovereignBackupManager = SovereignBackupManager()

    /**
     * Membangun dokumen cadangan memori personal lengkap ([GepyVaultBackup])
     * dari fakta-fakta graf temporal Room v5 dan preferensi profil pengguna.
     */
    fun exportVaultBackup(appVersion: String = EnvConfig.appVersionName): GepyVaultBackup {
        val facts = getAllTemporalFactsSnapshot()
        val displayName = getUserDisplayName()
        return backupManager.createVaultBackup(
            facts = facts,
            userDisplayName = displayName,
            appVersion = appVersion,
            exportedAtMs = clockMsProvider()
        )
    }

    /**
     * Mengekspor dokumen cadangan ke string format JSON (untuk sinkronisasi Google Drive AppData).
     */
    fun exportVaultJson(appVersion: String = EnvConfig.appVersionName): String {
        return backupManager.exportToJson(exportVaultBackup(appVersion))
    }

    /**
     * Mengekspor dokumen cadangan secara streaming langsung ke [OutputStream]
     * (digunakan oleh Android Storage Access Framework / CreateDocument).
     */
    fun exportVaultToStream(outputStream: OutputStream, appVersion: String = EnvConfig.appVersionName) {
        backupManager.exportToStream(exportVaultBackup(appVersion), outputStream)
    }

    /**
     * Mengimpor dan menggabungkan fakta dari dokumen [GepyVaultBackup] ke dalam
     * basis data Room v5 dan cache in-memory secara cerdas (*Smart Merge*).
     */
    suspend fun importVaultBackup(vaultBackup: GepyVaultBackup): VaultImportResult {
        val nowMs = clockMsProvider()
        val currentFacts = getAllTemporalFactsSnapshot()
        val plan = backupManager.planSmartMerge(
            existingFacts = currentFacts,
            vaultBackup = vaultBackup,
            nowMs = nowMs
        )

        val dao = gepyDao
        var importedCount = 0
        var updatedCount = 0

        // 1. Eksekusi pembaruan entri yang sudah ada
        if (plan.toUpdate.isNotEmpty()) {
            if (dao != null) {
                try {
                    dao.insertTemporalFacts(plan.toUpdate)
                } catch (t: Throwable) {
                    GepyLog.w(UserMemoryManager.TAG, "Gagal mengupdate fakta import di Room: ${t.message}")
                }
            }
            synchronized(temporalLock) {
                for (updatedEntity in plan.toUpdate) {
                    val idx = temporalFactsCache.indexOfFirst { it.id == updatedEntity.id }
                    if (idx >= 0) {
                        temporalFactsCache[idx] = updatedEntity
                    }
                }
            }
            updatedCount = plan.toUpdate.size
        }

        // 2. Eksekusi penyisipan fakta baru
        if (plan.toInsert.isNotEmpty()) {
            val insertedEntities = if (dao != null) {
                try {
                    val ids = dao.insertTemporalFacts(plan.toInsert)
                    plan.toInsert.mapIndexed { idx, entity ->
                        val newId = ids.getOrNull(idx) ?: allocateInMemoryFactId()
                        entity.copy(id = newId)
                    }
                } catch (t: Throwable) {
                    GepyLog.w(UserMemoryManager.TAG, "Gagal insert batch fakta import di Room: ${t.message}")
                    plan.toInsert.map { entity ->
                        val inMemId = allocateInMemoryFactId()
                        entity.copy(id = inMemId)
                    }
                }
            } else {
                plan.toInsert.map { entity ->
                    val inMemId = allocateInMemoryFactId()
                    entity.copy(id = inMemId)
                }
            }

            synchronized(temporalLock) {
                temporalFactsCache.addAll(0, insertedEntities)
                if (temporalFactsCache.size > UserMemoryManager.MAX_TEMPORAL_RAM_FACTS) {
                    temporalFactsCache.subList(UserMemoryManager.MAX_TEMPORAL_RAM_FACTS, temporalFactsCache.size).clear()
                }
            }
            importedCount = insertedEntities.size

            if (vectorEngine.isBackfillReady(nowMs)) {
                val dbHashes = if (dao != null) {
                    runCatching { dao.getAllVectorContentHashes() }.getOrNull()?.toHashSet() ?: emptySet()
                } else {
                    emptySet()
                }
                vectorEngine.triggerBackfillIfEligible(insertedEntities, nowMs, dbHashes)
            }
        }

        // 3. Pulihkan nama panggilan pengguna jika di lokal masih kosong
        if (vaultBackup.userDisplayName.isNotBlank() && getUserDisplayName().isBlank()) {
            setUserDisplayName(vaultBackup.userDisplayName, true)
        }

        // 4. Sinkronkan ke SharedPreferences legacy agar format lama tetap konsisten
        val refreshedActive = getActiveTemporalFactsSync(nowMs)
        val legacyList = refreshedActive.map { entity ->
            UserMemoryEntry(
                id = UUID.randomUUID().toString(),
                category = entity.category,
                subject = entity.subject,
                fact = entity.factText,
                updatedAt = entity.updatedAtMs,
                validUntilMs = entity.validUntilMs
            )
        }
        persistEntries(legacyList)

        GepyLog.i(
            UserMemoryManager.TAG,
            "Pemulihan Sovereign Vault selesai: disisipkan $importedCount, diperbarui $updatedCount, dilewati ${plan.skippedCount}"
        )

        return VaultImportResult.Success(
            importedCount = importedCount,
            updatedCount = updatedCount,
            skippedCount = plan.skippedCount,
            totalFactsInVault = vaultBackup.facts.size,
            userDisplayName = vaultBackup.userDisplayName.ifBlank { null }
        )
    }

    /**
     * Mengurai string JSON lalu mengimpornya ke memori Room v5.
     */
    suspend fun importVaultJson(jsonString: String): VaultImportResult {
        val parseResult = backupManager.parseVaultJson(jsonString)
        val backup = parseResult.getOrElse { throwable ->
            return VaultImportResult.Failure(
                reason = throwable.message ?: "Format berkas cadangan JSON tidak valid.",
                throwable = throwable
            )
        }
        return importVaultBackup(backup)
    }

    /**
     * Membaca dari [InputStream] (SAF) lalu mengimpornya ke memori Room v5.
     */
    suspend fun importVaultFromStream(inputStream: InputStream): VaultImportResult {
        val parseResult = backupManager.parseVaultStream(inputStream)
        val backup = parseResult.getOrElse { throwable ->
            return VaultImportResult.Failure(
                reason = throwable.message ?: "Gagal membaca berkas cadangan dari penyimpanan.",
                throwable = throwable
            )
        }
        return importVaultBackup(backup)
    }

    internal fun syncToCloudIfAuthenticated(category: String, fact: String) {
        val sessionToken = authManager?.currentUser?.value?.sessionToken
        if (!sessionToken.isNullOrBlank() &&
            sessionToken != "offline_session_token" &&
            gatewayClient != null
        ) {
            if (gatewayClient.isCircuitOpen) {
                GepyLog.w(UserMemoryManager.TAG, "Sinkronisasi cloud dilewati sementara karena circuit breaker gateway aktif.")
                return
            }

            scope.launch {
                try {
                    val res = gatewayClient.storeMemory(
                        sessionToken = sessionToken,
                        content = fact,
                        category = category
                    )
                    if (res.isSuccess) {
                        GepyLog.i(UserMemoryManager.TAG, "Memori berhasil disinkronkan ke Supabase pgvector")
                    } else {
                        GepyLog.w(UserMemoryManager.TAG, "Gagal sinkronisasi ke Supabase: ${res.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    GepyLog.w(UserMemoryManager.TAG, "Kesalahan jaringan sinkronisasi memori: ${e.message}")
                }
            }
        }
    }
}
