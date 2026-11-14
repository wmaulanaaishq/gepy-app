package com.teknologigawai.gepy.data.database

import com.teknologigawai.gepy.core.ports.PredictedEdgeOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteMemoryRepositoryTest {

    @Test
    fun `Two-Phase Commit menampung kandidat di staging dan baru menulis permanen saat commitStagedTransitions dipanggil`() = runTest {
        val repo = SqliteMemoryRepository(dao = null)

        // 1. Stage transition
        repo.stageCandidateTransition(
            packageName = "com.instagram.android",
            intentGoal = "Cari IG Budi lalu kirim DM halo",
            resourceId = "com.instagram.android:id/row_thread_composer_button_send",
            screenSignatureHash = "hash_chat_screen",
            actionType = "CLICK"
        )

        // Di buffer in-memory/DB belum aktif sebelum di-commit
        val beforeCommit = repo.findPlaybookTransition(
            packageName = "com.instagram.android",
            screenSignatureHash = "hash_chat_screen",
            intentGoal = "Cari IG Budi lalu kirim DM halo",
            minSuccessCount = 1
        )
        assertNull(beforeCommit)

        // 2. Commit session
        repo.commitStagedTransitions("ses_12345")

        // Setelah commit, transition tersimpan dan queryable
        val afterCommit = repo.findPlaybookTransition(
            packageName = "com.instagram.android",
            screenSignatureHash = "hash_chat_screen",
            intentGoal = "Cari IG Budi lalu kirim DM halo",
            minSuccessCount = 1
        )
        assertNotNull(afterCommit)
        assertEquals("com.instagram.android:id/row_thread_composer_button_send", afterCommit?.targetResourceId)
        assertEquals(1, afterCommit?.successCount)
        assertEquals(0, afterCommit?.failureCount)
    }

    @Test
    fun `discardStagedTransitions membuang seluruh buffer staging saat tugas gagal atau dibatalkan`() = runTest {
        val repo = SqliteMemoryRepository(dao = null)

        repo.stageCandidateTransition(
            packageName = "com.whatsapp",
            intentGoal = "Kirim pesan WA ke Budi",
            resourceId = "com.whatsapp:id/send",
            screenSignatureHash = "hash_wa_chat",
            actionType = "CLICK"
        )

        repo.discardStagedTransitions()
        repo.commitStagedTransitions("ses_failed")

        val hit = repo.findPlaybookTransition(
            packageName = "com.whatsapp",
            screenSignatureHash = "hash_wa_chat",
            intentGoal = "Kirim pesan WA ke Budi",
            minSuccessCount = 1
        )
        assertNull(hit)
    }

    @Test
    fun `penalizePlaybookTransition menaikkan failureCount dan menonaktifkan transition bermasalah`() = runTest {
        val repo = SqliteMemoryRepository(dao = null)

        // Commit sukses pertama
        repo.stageCandidateTransition(
            packageName = "com.sec.android.app.launcher",
            intentGoal = "Buka galeri",
            resourceId = "com.sec.android.app.launcher:id/icon_gallery",
            screenSignatureHash = "hash_home",
            actionType = "CLICK"
        )
        repo.commitStagedTransitions("ses_home")

        // Berikan penalti kegagalan
        repo.penalizePlaybookTransition(
            packageName = "com.sec.android.app.launcher",
            screenSignatureHash = "hash_home",
            intentGoal = "Buka galeri",
            targetResourceId = "com.sec.android.app.launcher:id/icon_gallery",
            actionType = "CLICK"
        )

        // Harus tidak lagi di-hit oleh playbook finder
        val hit = repo.findPlaybookTransition(
            packageName = "com.sec.android.app.launcher",
            screenSignatureHash = "hash_home",
            intentGoal = "Buka galeri",
            minSuccessCount = 1
        )
        assertNull(hit)

        val all = repo.getAllPlaybookEntries()
        assertEquals(1, all.size)
        assertTrue(all[0].failureCount > 0)
        assertTrue(all[0].isDisabled)
    }

    @Test
    fun `rollbackSessionMemory menonaktifkan seluruh memori yang berasal dari sesi tertentu`() = runTest {
        val repo = SqliteMemoryRepository(dao = null)

        repo.stageCandidateTransition(
            packageName = "com.whatsapp",
            intentGoal = "Kirim pesan",
            resourceId = "com.whatsapp:id/send",
            screenSignatureHash = "sig_1",
            actionType = "CLICK"
        )
        repo.commitStagedTransitions("ses_bad_feedback")

        // Pengguna menekan tombol 👎 Salah
        repo.rollbackSessionMemory("ses_bad_feedback")

        val entries = repo.getAllPlaybookEntries()
        assertEquals(1, entries.size)
        assertTrue(entries[0].isDisabled)
    }

    @Test
    fun `SEWM observeEdgeTransition mengakumulasi skor risiko memulihkan edge saat aplikasi membaik dan mendukung clearAll`() = runTest {
        val repo = SqliteMemoryRepository(dao = null)

        // 1. Dua klik berturut-turut memicu PAYWALL -> riskScore tinggi & dominant = PAYWALL
        repeat(2) {
            repo.observeEdgeTransition(
                packageName = "com.spotify.music",
                screenSignatureHash = "scr_home",
                edgeKey = "com.spotify.music:id/upsell_cta",
                actionType = "CLICK",
                targetLabelSample = "Coba Gratis",
                outcome = PredictedEdgeOutcome.PAYWALL
            )
        }
        val riskyRules = repo.predictEdgeRisksForScreenFast("com.spotify.music", "scr_home")
        assertEquals(1, riskyRules.size)
        assertEquals(PredictedEdgeOutcome.PAYWALL, riskyRules[0].dominantOutcome)
        assertEquals(2, riskyRules[0].paywallCount)
        assertTrue(riskyRules[0].riskScore >= 0.70f)

        // 2. Setelah update/perubahan perilaku menjadi aman berulang, skor pulih di bawah threshold warning
        repeat(4) {
            repo.observeEdgeTransition(
                packageName = "com.spotify.music",
                screenSignatureHash = "scr_home",
                edgeKey = "com.spotify.music:id/upsell_cta",
                actionType = "CLICK",
                targetLabelSample = "Coba Gratis",
                outcome = PredictedEdgeOutcome.SAFE_PROGRESS
            )
        }
        val recoveredRules = repo.predictEdgeRisksForScreenFast("com.spotify.music", "scr_home")
        assertEquals(4, recoveredRules[0].safeCount)
        assertEquals(PredictedEdgeOutcome.SAFE_PROGRESS, recoveredRules[0].dominantOutcome)
        assertTrue(recoveredRules[0].riskScore < 0.45f)

        // 3. Pembersihan menyeluruh menghapus aturan di RAM
        repo.clearAllEdgeWorldModelRules()
        assertTrue(repo.predictEdgeRisksForScreenFast("com.spotify.music", "scr_home").isEmpty())
        assertEquals(0, repo.getEdgeWorldModelStats().totalRulesInRam)
    }
}
