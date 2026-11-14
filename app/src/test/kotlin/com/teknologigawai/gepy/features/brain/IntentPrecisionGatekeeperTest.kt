package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.system.PhoneContactItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentPrecisionGatekeeperTest {

    private val sampleContacts = listOf(
        PhoneContactItem("Budi Santoso", "081234567890"),
        PhoneContactItem("Budi Kantor", "081987654321"),
        PhoneContactItem("Siti Rahma", "082112233445"),
        PhoneContactItem("Andi Pratama", "085611223344")
    )

    @Test
    fun `menolak suara partikel desah napas dan noise mik`() {
        val gatekeeper = IntentPrecisionGatekeeper()

        val noise1 = gatekeeper.evaluateIntent("ah")
        assertTrue(noise1 is IntentEvaluationResult.IgnoredNoise)

        val noise2 = gatekeeper.evaluateIntent("tes")
        assertTrue(noise2 is IntentEvaluationResult.IgnoredNoise)

        val noise3 = gatekeeper.evaluateIntent("hm")
        assertTrue(noise3 is IntentEvaluationResult.IgnoredNoise)
    }

    @Test
    fun `menangani sapaan santai ramah tanpa memicu eksekusi UI acak`() {
        val gatekeeper = IntentPrecisionGatekeeper()

        val greeting = gatekeeper.evaluateIntent("halo gepy")
        assertTrue(greeting is IntentEvaluationResult.IgnoredNoise)
        val reply = (greeting as IntentEvaluationResult.IgnoredNoise).friendlyReply
        assertTrue(reply?.contains("Halo") == true)
    }

    @Test
    fun `mendeteksi ambiguitas kontak ganda dan meminta klarifikasi di HUD`() {
        val gatekeeper = IntentPrecisionGatekeeper(
            phoneContactsProvider = { sampleContacts }
        )

        val eval = gatekeeper.evaluateIntent("Kirim pesan ke Budi isi pesan lagi di jalan ya")
        assertTrue("Harus meminta disambiguasi karena ada 2 kontak bernama Budi", eval is IntentEvaluationResult.RequiresDisambiguation)
        val disambig = eval as IntentEvaluationResult.RequiresDisambiguation
        assertEquals(2, disambig.candidates.size)
        assertTrue(disambig.questionPrompt.contains("Budi"))
    }

    @Test
    fun `meloloskan kontak tunggal yang jelas secara instan`() {
        val gatekeeper = IntentPrecisionGatekeeper(
            phoneContactsProvider = { sampleContacts }
        )

        val eval = gatekeeper.evaluateIntent("Kirim wa ke Siti Rahma besok jadi ketemu")
        assertTrue("Kontak tunggal harus langsung approved", eval is IntentEvaluationResult.Approved)
        assertEquals("Kirim wa ke Siti Rahma besok jadi ketemu", (eval as IntentEvaluationResult.Approved).cleanGoal)
    }

    @Test
    fun `mencegat tindakan berisiko tinggi dan destruktif untuk konfirmasi`() {
        val gatekeeper = IntentPrecisionGatekeeper()

        val evalDelete = gatekeeper.evaluateIntent("Hapus semua chat sekarang")
        assertTrue(evalDelete is IntentEvaluationResult.RequiresConfirmation)
        val confirm = evalDelete as IntentEvaluationResult.RequiresConfirmation
        assertTrue(confirm.confirmationPrompt.contains("Hapus semua chat"))

        val evalReset = gatekeeper.evaluateIntent("Tolong lakukan factory reset HP")
        assertTrue(evalReset is IntentEvaluationResult.RequiresConfirmation)
    }

    @Test
    fun `meloloskan tugas rutin harian pemilik secara bebas tanpa hambatan birokrasi`() {
        val gatekeeper = IntentPrecisionGatekeeper()

        val evalNav = gatekeeper.evaluateIntent("Buka YouTube dan cari video tutorial Jetpack Compose", sovereignPersonalMode = true)
        assertTrue(evalNav is IntentEvaluationResult.Approved)
        assertTrue((evalNav as IntentEvaluationResult.Approved).isDirectSovereignFastPass)

        val evalAlarm = gatekeeper.evaluateIntent("Pasang alarm jam 05:30 pagi besok", sovereignPersonalMode = true)
        assertTrue(evalAlarm is IntentEvaluationResult.Approved)

        val evalSearch = gatekeeper.evaluateIntent("Cari kafe dengan wifi cepat di Tebet", sovereignPersonalMode = true)
        assertTrue(evalSearch is IntentEvaluationResult.Approved)
    }
}
