package com.teknologigawai.gepy.core.orchestrator

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedActionSupervisorTest {

    @Test
    fun `Supervisor mengelola siklus hidup tugas dan menghasilkan snapshot status terstruktur`() {
        val supervisor = UnifiedActionSupervisor()
        assertEquals(SupervisorState.IDLE, supervisor.getCurrentSnapshot().state)

        supervisor.onGoalStarted("Kirim pesan WA ke Budi", listOf("Buka WA", "Cari Budi", "Ketik pesan"))
        var snap = supervisor.getCurrentSnapshot()
        assertEquals(SupervisorState.RUNNING, snap.state)
        assertEquals(1, snap.currentStep)
        assertEquals("Kirim pesan WA ke Budi", snap.activeGoal)

        supervisor.onStepStarted(2, "com.whatsapp")
        supervisor.onActionCompleted("CLICK", "Mengetuk kontak Budi")
        snap = supervisor.getCurrentSnapshot()
        assertEquals(2, snap.currentStep)
        assertEquals("com.whatsapp", snap.currentPackage)
        assertEquals("CLICK", snap.lastAction)

        val toolMap = snap.toToolResponseMap()
        assertEquals("RUNNING", toolMap["state"])
        assertEquals("com.whatsapp", toolMap["current_package"])
        assertTrue((toolMap["summary"] as String).contains("Kirim pesan WA ke Budi"))
    }

    @Test
    fun `Supervisor mendukung jeda kooperatif dan kelanjutan eksekusi tanpa membatalkan tugas`() = runBlocking {
        val supervisor = UnifiedActionSupervisor()
        supervisor.onGoalStarted("Buka Instagram", listOf("Buka IG"))

        assertTrue("Pause harus berhasil saat status RUNNING", supervisor.pauseForVoiceIntervention("Pengguna bertanya hal lain"))
        assertTrue(supervisor.getCurrentSnapshot().isPaused)
        assertEquals(SupervisorState.PAUSED, supervisor.getCurrentSnapshot().state)

        var barrierPassed = false
        val job = launch {
            val canProceed = supervisor.checkActionBarrier()
            if (canProceed) {
                barrierPassed = true
            }
        }

        // Sebelum resume, barrier harus menahan eksekusi
        assertFalse("Action barrier harus menahan eksekusi saat dijeda", barrierPassed)

        // Melanjutkan eksekusi
        supervisor.resumeExecution()
        job.join()

        assertTrue("Action barrier harus melepaskan eksekusi setelah resume", barrierPassed)
        assertFalse(supervisor.getCurrentSnapshot().isPaused)
        assertEquals(SupervisorState.RUNNING, supervisor.getCurrentSnapshot().state)
    }

    @Test
    fun `Supervisor membatalkan tugas secara bersih dan menghentikan barrier`() = runBlocking {
        val supervisor = UnifiedActionSupervisor()
        supervisor.onGoalStarted("Hapus postingan", listOf("Buka app"))
        supervisor.pauseForVoiceIntervention("Tanya konfirmasi")

        var barrierResult = true
        val job = launch {
            barrierResult = supervisor.checkActionBarrier()
        }

        supervisor.abortGoal("Pengguna membatalkan konfirmasi")
        job.join()

        assertFalse("checkActionBarrier harus mengembalikan false saat dibatalkan", barrierResult)
        assertEquals(SupervisorState.ABORTED, supervisor.getCurrentSnapshot().state)
    }
}
