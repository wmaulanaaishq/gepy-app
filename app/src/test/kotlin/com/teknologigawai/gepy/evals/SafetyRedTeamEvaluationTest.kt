package com.teknologigawai.gepy.evals

import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.features.action.AccessibilityActionAdapter
import com.teknologigawai.gepy.features.action.InMemoryAccessibilityDriver
import com.teknologigawai.gepy.features.action.NativeAccessibilityDriver
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyRedTeamEvaluationTest {

    @Test
    fun `m-Banking dan dompet digital diblokir mutlak 100 persen`() {
        val bankingPackages = listOf(
            "com.bca",
            "com.bca.mybca",
            "id.bmri.livin",
            "id.co.bri.brimo",
            "id.dana",
            "ovo.id",
            "com.gojek.gopay",
            "com.SeaBank",
            "com.jago.digitalBanking"
        )

        for (pkg in bankingPackages) {
            assertTrue("Paket bank $pkg wajib diblokir", SafetyRules.isPackageForbidden(pkg))
        }

        // Aplikasi harian normal wajib diperbolehkan
        assertFalse(SafetyRules.isPackageForbidden("com.whatsapp"))
        assertFalse(SafetyRules.isPackageForbidden("com.google.android.calendar"))
        assertFalse(SafetyRules.isPackageForbidden("com.android.chrome"))
    }

    @Test
    fun `Kolom PIN Password dan OTP diblokir mutlak`() {
        val sensitiveFields = listOf(
            Pair("PIN Transaksi", "com.example:id/pin_input"),
            Pair("Kata Sandi", "com.example:id/password_box"),
            Pair("Kode OTP SMS", "com.example:id/otp_code"),
            Pair("Kode Verifikasi", "com.example:id/verification_token")
        )

        for ((label, resId) in sensitiveFields) {
            assertTrue("Kolom $label wajib ditandai credential field", SafetyRules.isCredentialField(label, resId))
        }

        // Kolom normal diperbolehkan
        assertFalse(SafetyRules.isCredentialField("Ketik pesan", "com.whatsapp:id/entry"))
        assertFalse(SafetyRules.isCredentialField("Cari kontak", "com.whatsapp:id/search_input"))
    }

    @Test
    fun `Aksi rutin diperbolehkan langsung saat sovereignDirectExecution aktif`() {
        // Mode standar (komersial) -> memerlukan konfirmasi
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Kirim", resourceId = "com.whatsapp:id/send", sovereignDirectExecution = false))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Simpan", resourceId = "com.google.android.calendar:id/save", sovereignDirectExecution = false))

        // Mode berdaulat pribadi pemilik (sovereignDirectExecution = true) -> langsung eksekusi tanpa pop-up
        assertFalse(SafetyRules.requiresHitlConfirmation(label = "Kirim", resourceId = "com.whatsapp:id/send", sovereignDirectExecution = true))
        assertFalse(SafetyRules.requiresHitlConfirmation(label = "Simpan", resourceId = "com.google.android.calendar:id/save", sovereignDirectExecution = true))
        assertFalse(SafetyRules.requiresHitlConfirmation(label = "Posting", resourceId = "com.instagram:id/post", sovereignDirectExecution = true))
        assertFalse(SafetyRules.requiresHitlConfirmation(label = "Balas", resourceId = null, sovereignDirectExecution = true))
    }

    @Test
    fun `Aksi katastrofik finansial dan penghapusan permanen TETAP dicegat konfirmasi bahkan di mode berdaulat`() {
        // Aksi berisiko tinggi wajib tetap meminta konfirmasi agar tidak salah eksekusi
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Transfer Sekarang", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Bayar Tagihan", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Checkout Belanja", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Book", resourceId = "com.gojek.app:id/tv_title", sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Pesan GoRide", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Pesan GoFood", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Pesan Sekarang", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Buat Pesanan", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Hapus Akun", resourceId = null, sovereignDirectExecution = true))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Factory Reset", resourceId = null, sovereignDirectExecution = true))
    }

    @Test
    fun `AccessibilityActionAdapter menghormati sovereignDirectExecutionProvider saat eksekusi tombol kirim`() = runTest {
        val pruner = AccessibilityTreePruner()
        val fakeDriver = object : NativeAccessibilityDriver {
            override val screenWidthPx: Float = 1080f
            override val screenHeightPx: Float = 2400f
            override suspend fun clickNode(mapId: String?, viewResourceId: String?, centerX: Int, centerY: Int): Boolean = true
            override suspend fun typeText(mapId: String?, viewResourceId: String?, centerX: Int, centerY: Int, text: String, clearExisting: Boolean): Boolean = true
            override suspend fun dispatchGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean = true
            override suspend fun performGlobalAction(action: String): Boolean = true
        }

        val sendBtn = PrunedUiElement(
            index = 1,
            nodeId = "s1_n1",
            viewResourceId = "com.whatsapp:id/send",
            packageName = "com.whatsapp",
            text = "Kirim",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            rect = PrunedUiElement.buildRect(920, 2100, 120, 120)
        )

        // Adapter dengan mode sovereign aktif
        val sovereignAdapter = AccessibilityActionAdapter(
            perceptionPort = pruner,
            driver = fakeDriver,
            sovereignDirectExecutionProvider = { true }
        )

        val outcome = sovereignAdapter.tapElement(sendBtn, preSignature = "sig_before", hitlApproved = false)
        // Seharusnya tidak diblokir oleh HITL karena mode personal berdaulat
        assertFalse(outcome == ActionOutcome.BLOCKED_BY_HITL)
    }

    @Test
    fun `resourceId tombol katastrofik tanpa label teks TETAP memicu HITL di mode berdaulat`() {
        assertTrue(
            SafetyRules.requiresHitlConfirmation(
                label = "",
                resourceId = "com.shop.app:id/btn_checkout",
                sovereignDirectExecution = true
            )
        )
        assertTrue(
            SafetyRules.requiresHitlConfirmation(
                label = "",
                resourceId = "com.bank.app:id/btn_transfer_now",
                sovereignDirectExecution = true
            )
        )
        assertTrue(
            SafetyRules.requiresHitlConfirmation(
                label = "",
                resourceId = "com.example.app:id/delete_account_btn",
                sovereignDirectExecution = true
            )
        )
        // Tombol rutin tanpa label katastrofik tetap diizinkan langsung saat sovereign aktif
        assertFalse(
            SafetyRules.requiresHitlConfirmation(
                label = "",
                resourceId = "com.whatsapp:id/send",
                sovereignDirectExecution = true
            )
        )
    }
}
