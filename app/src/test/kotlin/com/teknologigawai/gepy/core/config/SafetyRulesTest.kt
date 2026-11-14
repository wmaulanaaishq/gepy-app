package com.teknologigawai.gepy.core.config

import com.teknologigawai.gepy.core.errors.ForbiddenPackageException
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.features.action.AccessibilityActionAdapter
import com.teknologigawai.gepy.features.action.NativeAccessibilityDriver
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.perception.AccessibilityTreePruner
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SafetyRulesTest {

    @Test
    fun `memblokir paket aplikasi m-Banking dan dompet digital populer`() {
        assertTrue(SafetyRules.isPackageForbidden("com.bca"))
        assertTrue(SafetyRules.isPackageForbidden("com.bca.mybca.omni"))
        assertTrue(SafetyRules.isPackageForbidden("id.co.bri.brimo"))
        assertTrue(SafetyRules.isPackageForbidden("id.bmri.livin"))
        assertTrue(SafetyRules.isPackageForbidden("id.dana"))
        assertTrue(SafetyRules.isPackageForbidden("ovo.id"))
        assertTrue(SafetyRules.isPackageForbidden("com.jago.digitalBanking"))
    }

    @Test
    fun `memblokir dialog izin sistem Android dan toko aplikasi`() {
        assertTrue(SafetyRules.isPackageForbidden("com.google.android.permissioncontroller"))
        assertTrue(SafetyRules.isPackageForbidden("com.android.permissioncontroller"))
        assertTrue(SafetyRules.isPackageForbidden("com.samsung.android.permissioncontroller"))
        assertTrue(SafetyRules.isPackageForbidden("com.android.vending"))
        assertTrue(SafetyRules.isPackageForbidden("com.sec.android.app.samsungapps"))
        assertTrue(SafetyRules.isSystemSecurityPackage("com.android.permissioncontroller"))
    }

    @Test
    fun `mengizinkan aplikasi komunikasi dan produktivitas non-finansial`() {
        assertFalse(SafetyRules.isPackageForbidden("com.whatsapp"))
        assertFalse(SafetyRules.isPackageForbidden("com.google.android.calendar"))
        assertFalse(SafetyRules.isPackageForbidden("com.google.android.apps.maps"))
    }

    @Test
    fun `mewajibkan konfirmasi HITL pada kata kunci dan resource-id sensitif`() {
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Kirim Pesan"))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Send"))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Hapus Chat"))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Simpan"))
        assertTrue(SafetyRules.requiresHitlConfirmation(label = "Log out"))
        assertTrue(
            SafetyRules.requiresHitlConfirmation(
                label = "",
                resourceId = "com.whatsapp:id/send"
            )
        )

        // Elemen navigasi biasa tidak boleh tertahan HITL
        assertFalse(
            SafetyRules.requiresHitlConfirmation(
                label = "Cari kontak",
                resourceId = "com.whatsapp:id/menuitem_search"
            )
        )
    }

    @Test
    fun `mendeteksi kolom input kredensial rahasia PIN OTP Password`() {
        assertTrue(SafetyRules.isCredentialField(label = "Masukkan PIN Anda"))
        assertTrue(SafetyRules.isCredentialField(label = "Kode OTP 6 Digit"))
        assertTrue(SafetyRules.isCredentialField(label = "Kata Sandi"))
        assertTrue(
            SafetyRules.isCredentialField(
                label = "",
                resourceId = "com.app:id/et_password"
            )
        )

        assertFalse(
            SafetyRules.isCredentialField(
                label = "Ketik pesan",
                resourceId = "com.whatsapp:id/entry"
            )
        )
    }

    @Test
    fun `AppIntentLauncher meresolusi alias O(1) dan menolak aplikasi m-Banking`() = runTest {
        val launcher = AppIntentLauncher()
        assertEquals("com.whatsapp", launcher.resolvePackageName("WA"))
        assertEquals("com.google.android.calendar", launcher.resolvePackageName("kalender"))

        val waResult = launcher.openApp("WhatsApp")
        assertTrue(waResult.success)
        assertEquals("com.whatsapp", waResult.resolvedPackage)

        try {
            launcher.openApp("com.bca.mybca")
            fail("Seharusnya melempar ForbiddenPackageException")
        } catch (e: ForbiddenPackageException) {
            assertEquals("com.bca.mybca", e.targetIdentifier)
        }
    }

    @Test
    fun `AccessibilityActionAdapter menahan tombol Kirim saat hitlApproved false`() = runTest {
        val pruner = AccessibilityTreePruner()
        val noopDriver = object : NativeAccessibilityDriver {
            override val screenWidthPx: Float = 1080f
            override val screenHeightPx: Float = 2400f
            override suspend fun clickNode(
                mapId: String?,
                viewResourceId: String?,
                centerX: Int,
                centerY: Int
            ): Boolean = true

            override suspend fun typeText(
                mapId: String?,
                viewResourceId: String?,
                centerX: Int,
                centerY: Int,
                text: String,
                clearExisting: Boolean
            ): Boolean = true

            override suspend fun dispatchGesture(
                startX: Float,
                startY: Float,
                endX: Float,
                endY: Float,
                durationMs: Long
            ): Boolean = true

            override suspend fun performGlobalAction(action: String): Boolean = true
        }
        val adapter = AccessibilityActionAdapter(perceptionPort = pruner, driver = noopDriver)

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

        val outcome = adapter.tapElement(sendBtn, preSignature = "sig_before", hitlApproved = false)
        assertEquals(ActionOutcome.BLOCKED_BY_HITL, outcome)
    }
}
