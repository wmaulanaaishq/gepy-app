package com.teknologigawai.gepy.core.memory.backup

import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SovereignBackupManagerTest {

    private val backupManager = SovereignBackupManager()

    @Test
    fun testCreateAndExportVaultJson_ProducesValidChecksumAndMetadata() {
        val nowMs = 1731000000000L
        val facts = listOf(
            TemporalMemoryFactEntity(
                id = 1L,
                category = "family",
                subject = "adik",
                predicate = "bernama",
                objectValue = "Aisyah",
                factText = "Adik saya bernama Aisyah",
                validFromMs = nowMs - 50000L,
                validUntilMs = null,
                isActive = true,
                createdAtMs = nowMs - 50000L,
                updatedAtMs = nowMs - 1000L
            ),
            TemporalMemoryFactEntity(
                id = 2L,
                category = "schedule",
                subject = "rapat",
                predicate = "pada",
                objectValue = "14:00",
                factText = "Ada rapat proyek jam 2 siang",
                validFromMs = nowMs,
                validUntilMs = nowMs + 3600000L,
                isActive = true,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )

        val vault = backupManager.createVaultBackup(
            facts = facts,
            userDisplayName = "Budi Santoso",
            appVersion = "5.2.0",
            exportedAtMs = nowMs
        )

        assertEquals(1, vault.formatVersion)
        assertEquals("Budi Santoso", vault.userDisplayName)
        assertEquals(2, vault.facts.size)
        assertTrue("Checksum SHA-256 tidak boleh kosong", vault.checksum.isNotBlank())
        assertTrue(backupManager.verifyChecksum(vault))

        val jsonString = backupManager.exportToJson(vault)
        assertTrue(jsonString.contains("Budi Santoso"))
        assertTrue(jsonString.contains("Aisyah"))
        assertTrue(jsonString.contains("rapat proyek"))

        val parsedResult = backupManager.parseVaultJson(jsonString)
        assertTrue(parsedResult.isSuccess)
        val parsed = parsedResult.getOrThrow()
        assertEquals(2, parsed.facts.size)
        assertEquals(vault.checksum, parsed.checksum)
    }

    @Test
    fun testVerifyChecksum_DetectsTamperedPayload() {
        val nowMs = 1731000000000L
        val facts = listOf(
            TemporalMemoryFactEntity(
                id = 1L,
                category = "profile",
                subject = "pekerjaan",
                predicate = "sebagai",
                objectValue = "Dosen",
                factText = "Saya bekerja sebagai Dosen",
                validFromMs = nowMs,
                isActive = true,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )
        val vault = backupManager.createVaultBackup(
            facts = facts,
            userDisplayName = "Dosen Ali",
            appVersion = "5.2.0",
            exportedAtMs = nowMs
        )
        assertTrue(backupManager.verifyChecksum(vault))

        // Ubah isi fakta tanpa menghitung ulang checksum (simulasi manipulasi berkas)
        val tamperedFacts = vault.facts.map { it.copy(objectValue = "Hacker") }
        val tamperedVault = vault.copy(facts = tamperedFacts)

        assertFalse(
            "Checksum harus mendeteksi perubahan ilegal pada berkas",
            backupManager.verifyChecksum(tamperedVault)
        )
    }

    @Test
    fun testStreamingExportAndParse_WorksWithInputOutputStreams() {
        val nowMs = 1731000000000L
        val facts = listOf(
            TemporalMemoryFactEntity(
                id = 10L,
                category = "preference",
                subject = "kopi",
                predicate = "suka",
                objectValue = "tanpa gula",
                factText = "Saya suka kopi hitam tanpa gula",
                validFromMs = nowMs,
                isActive = true,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )
        val vault = backupManager.createVaultBackup(
            facts = facts,
            userDisplayName = "Rian",
            appVersion = "5.2.0",
            exportedAtMs = nowMs
        )

        val outStream = ByteArrayOutputStream()
        backupManager.exportToStream(vault, outStream)
        val bytes = outStream.toByteArray()
        assertTrue("Output stream harus terisi", bytes.isNotEmpty())

        val inStream = ByteArrayInputStream(bytes)
        val parseResult = backupManager.parseVaultStream(inStream)
        assertTrue(parseResult.isSuccess)
        val restoredVault = parseResult.getOrThrow()
        assertEquals("Rian", restoredVault.userDisplayName)
        assertEquals(1, restoredVault.facts.size)
        assertEquals("tanpa gula", restoredVault.facts[0].objectValue)
    }

    @Test
    fun testPlanSmartMerge_CorrectlySegmentsInsertUpdateAndSkip() {
        val nowMs = 1731000000000L

        // Basis data lokal yang sedang ada di HP
        val existingFacts = listOf(
            TemporalMemoryFactEntity(
                id = 1L,
                category = "family",
                subject = "adik",
                predicate = "bernama",
                objectValue = "Aisyah",
                factText = "Adik saya bernama Aisyah",
                validFromMs = nowMs - 100000L,
                validUntilMs = null,
                isActive = true,
                createdAtMs = nowMs - 100000L,
                updatedAtMs = nowMs - 100000L
            ),
            TemporalMemoryFactEntity(
                id = 2L,
                category = "schedule",
                subject = "seminar",
                predicate = "pada",
                objectValue = "Ruang 301",
                factText = "Seminar AI di Ruang 301",
                validFromMs = nowMs - 50000L,
                validUntilMs = nowMs + 10000L, // Waktu lama
                isActive = true,
                createdAtMs = nowMs - 50000L,
                updatedAtMs = nowMs - 50000L
            )
        )

        // Berkas cadangan yang diimpor
        val backupFacts = listOf(
            // 1. Identik -> Harus dilewati (Skipped)
            TemporalFactDto(
                category = "family",
                subject = "adik",
                predicate = "bernama",
                objectValue = "Aisyah",
                factText = "Adik saya bernama Aisyah",
                validFromMs = nowMs - 100000L,
                validUntilMs = null,
                isActive = true,
                createdAtMs = nowMs - 100000L,
                updatedAtMs = nowMs - 100000L
            ),
            // 2. Diperpanjang masa berlakunya -> Harus diupdate
            TemporalFactDto(
                category = "schedule",
                subject = "seminar",
                predicate = "pada",
                objectValue = "Ruang 301",
                factText = "Seminar AI di Ruang 301",
                validFromMs = nowMs - 50000L,
                validUntilMs = nowMs + 7200000L, // Diperpanjang 2 jam
                isActive = true,
                createdAtMs = nowMs - 50000L,
                updatedAtMs = nowMs + 1000L
            ),
            // 3. Fakta baru yang belum pernah tercatat di HP -> Harus diinsert
            TemporalFactDto(
                category = "location",
                subject = "rumah",
                predicate = "alamat",
                objectValue = "Jalan Malioboro No. 12",
                factText = "Rumah saya di Jalan Malioboro No. 12",
                validFromMs = nowMs,
                validUntilMs = null,
                isActive = true,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )

        val vault = GepyVaultBackup(
            formatVersion = 1,
            exportedAtMs = nowMs,
            appVersion = "5.2.0",
            userDisplayName = "Budi",
            facts = backupFacts,
            checksum = ""
        )

        val plan = backupManager.planSmartMerge(existingFacts, vault, nowMs)

        assertEquals("Harus ada 1 fakta baru yang disisipkan", 1, plan.toInsert.size)
        assertEquals("Rumah saya di Jalan Malioboro No. 12", plan.toInsert[0].factText)
        assertEquals(0L, plan.toInsert[0].id) // ID baru 0L untuk auto-generate Room

        assertEquals("Harus ada 1 fakta yang diperbarui masa berlakunya", 1, plan.toUpdate.size)
        assertEquals(2L, plan.toUpdate[0].id) // Tetap menggunakan ID lokal 2L
        assertEquals(nowMs + 7200000L, plan.toUpdate[0].validUntilMs)

        assertEquals("Harus ada 1 fakta identik yang dilewati", 1, plan.skippedCount)
    }

    @Test
    fun testParseCorruptedJson_FailsGracefullyWithoutThrowingCrash() {
        val result = backupManager.parseVaultJson("{ invalid_json: true, unterminated ")
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }
}
