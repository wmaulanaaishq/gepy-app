package com.teknologigawai.gepy.core.diagnostics

import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.memory.VectorMemoryStats
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMemoryDiagnosticsTest {

    @Test
    fun `konversi kilobyte dan byte ke megabyte presisi`() {
        assertEquals(1.0, RuntimeMemoryDiagnostics.kbToMb(1024), 0.0001)
        assertEquals(120.0, RuntimeMemoryDiagnostics.kbToMb(122_880), 0.0001)
        assertEquals(0.0, RuntimeMemoryDiagnostics.kbToMb(-50), 0.0001)
        assertEquals(4096.0, RuntimeMemoryDiagnostics.bytesToMb(4L * 1024L * 1024L * 1024L), 0.001)
    }

    @Test
    fun `evaluateMemoryHealth mengklasifikasikan rentang PSS Galaxy A06 dengan benar`() {
        assertEquals(MemoryHealthLevel.OPTIMAL, RuntimeMemoryDiagnostics.evaluateMemoryHealth(95.4, false))
        assertEquals(MemoryHealthLevel.OPTIMAL, RuntimeMemoryDiagnostics.evaluateMemoryHealth(120.0, false))
        assertEquals(MemoryHealthLevel.NORMAL, RuntimeMemoryDiagnostics.evaluateMemoryHealth(145.0, false))
        assertEquals(MemoryHealthLevel.NORMAL, RuntimeMemoryDiagnostics.evaluateMemoryHealth(180.0, false))
        assertEquals(MemoryHealthLevel.ELEVATED, RuntimeMemoryDiagnostics.evaluateMemoryHealth(210.0, false))
        assertEquals(MemoryHealthLevel.HIGH, RuntimeMemoryDiagnostics.evaluateMemoryHealth(241.0, false))
        assertEquals(MemoryHealthLevel.HIGH, RuntimeMemoryDiagnostics.evaluateMemoryHealth(85.0, true))
    }

    @Test
    fun `ceilingUtilizationFraction terikat aman 0f hingga 1f`() {
        val midSnapshot = RuntimeMemorySnapshot(
            capturedAtMs = 1000L,
            totalPssMb = 120.0,
            javaHeapMb = 28.0,
            nativeHeapMb = 32.0,
            graphicsMb = 24.0,
            codeAndStackMb = 18.0,
            privateOtherMb = 18.0,
            maxAppHeapMb = 256.0,
            systemAvailRamMb = 1400.0,
            systemTotalRamMb = 4096.0,
            isLowRamDeviceWarning = false,
            healthLevel = MemoryHealthLevel.OPTIMAL,
            vectorStats = VectorMemoryStats(
                ramVectorCount = 420,
                maxRamVectors = 1000,
                dbVectorCount = 1250,
                maxDbVectors = 2500,
                activeTemporalFacts = 180,
                estimatedVectorRamKb = 1260
            )
        )
        assertEquals(0.5f, midSnapshot.ceilingUtilizationFraction, 0.01f)

        val overCeiling = midSnapshot.copy(totalPssMb = 360.0, healthLevel = MemoryHealthLevel.HIGH)
        assertEquals(1.0f, overCeiling.ceilingUtilizationFraction, 0.001f)
    }

    @Test
    fun `UserMemoryManager getVectorMemoryStats membaca kapasitas 1000 RAM dan 2500 SQLite`() = runTest {
        val manager = UserMemoryManager()
        manager.recordMemory("profile", "Pengguna menyukai kopi tubruk tanpa gula", subject = "coffee_pref")
        val stats = manager.getVectorMemoryStats()

        assertEquals(UserMemoryManager.MAX_VECTOR_RAM_CACHE, stats.maxRamVectors)
        assertEquals(UserMemoryManager.MAX_VECTOR_DB_ROWS, stats.maxDbVectors)
        assertTrue(stats.activeTemporalFacts >= 1)
    }
}
