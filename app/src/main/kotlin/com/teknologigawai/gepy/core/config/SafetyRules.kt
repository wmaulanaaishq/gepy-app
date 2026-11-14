package com.teknologigawai.gepy.core.config

/**
 * Aturan keselamatan deterministik (*Hardcoded Safety Gatekeeper*) Gepy (`com.teknologigawai.gepy`).
 *
 * Menegakkan tiga kontrak keselamatan utama:
 * 1. Larangan otomatisasi pada aplikasi perbankan dan dompet digital (*Zero-Credential Rule*).
 * 2. Pencegatan wajib *Human-in-the-Loop* (`CONFIRM_AND_CLICK`) pada elemen
 *    berlabel aksi ireversibel atau eksternal.
 * 3. Larangan menyentuh kolom kredensial sensitif (PIN, kata sandi, OTP).
 */
object SafetyRules {
    /**
     * Daftar awalan atau nama paket aplikasi perbankan dan finansial yang
     * dilarang keras diotomatisasi oleh Gepy (*Zero-Credential Rule*).
     */
    val blacklistedPackagePatterns: List<String> = listOf(
        "com.bca",
        "id.co.bri.brimo",
        "src.com.bni",
        "id.bmri.livin",
        "com.bankmandiri",
        "com.cimbniaga",
        "com.btpn.dc",
        "id.dana",
        "ovo.id",
        "com.gojek.gopay",
        "com.shopee.id.wallet",
        "com.SeaBank",
        "com.jago.digitalBanking"
    )

    /**
     * Daftar paket dialog keamanan sistem Android & Samsung yang dilarang keras disentuh
     * secara otomatis oleh AI (mencegah tuduhan Device Hijacking & Auto-Granting Permissions).
     */
    val systemSecurityPackages: Set<String> = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.android.settings.intelligence",
        "com.android.vending", // Google Play checkout / install dialog
        "com.sec.android.app.samsungapps" // Samsung Galaxy Store purchase / checkout dialog
    )

    /**
     * Daftar kata kunci label atau resource-id tombol ireversibel yang wajib
     * melewati persetujuan *Human-in-the-Loop* (`CONFIRM_AND_CLICK`).
     */
    val sensitiveHitlKeywords: List<String> = listOf(
        "kirim",
        "send",
        "post",
        "posting",
        "reply",
        "balas",
        "simpan",
        "save",
        "update",
        "perbarui",
        "hapus",
        "delete",
        "remove",
        "log out",
        "logout",
        "keluar akun",
        "bayar",
        "pay",
        "transfer",
        "checkout",
        "beli sekarang"
    )

    /**
     * Daftar kata kunci aksi katastrofik finansial atau penghapusan permanen fatal yang
     * tetap wajib memerlukan konfirmasi bahkan saat mode personal otonom berdaulat aktif.
     */
    val catastrophicHitlKeywords: List<String> = listOf(
        "bayar",
        "pay",
        "transfer",
        "checkout",
        "beli sekarang",
        "book",
        "book now",
        "book goride",
        "book gofood",
        "book gocar",
        "pesan goride",
        "pesan gofood",
        "pesan gojek",
        "pesan sekarang",
        "buat pesanan",
        "konfirmasi pesanan",
        "order now",
        "place order",
        "hapus akun",
        "delete account",
        "format data",
        "factory reset"
    )

    /**
     * Daftar resource-id spesifik aplikasi populer yang wajib memicu HITL
     * meskipun label teksnya kosong (misal tombol ikon pesawat kertas WhatsApp).
     */
    val sensitiveResourceIds: List<String> = listOf(
        "com.whatsapp:id/send",
        "com.whatsapp.w4b:id/send",
        "com.google.android.calendar:id/save",
        "com.google.android.gm:id/send"
    )

    /** Daftar kata kunci kolom input kredensial yang dilarang diisi otomatis. */
    val credentialFieldKeywords: List<String> = listOf(
        "password",
        "kata sandi",
        "sandi",
        "pin",
        "mpin",
        "otp",
        "kode verifikasi",
        "verification code",
        "cvv",
        "cvc",
        "passcode"
    )

    private val nonAlphaNumericRegex = Regex("[^a-z0-9]+")

    /**
     * Memeriksa apakah [packageName] termasuk aplikasi finansial/perbankan
     * atau dialog keamanan sistem yang diblokir dari otomatisasi UI.
     */
    fun isPackageForbidden(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) {
            return false
        }
        for (pattern in blacklistedPackagePatterns) {
            val lowerPattern = pattern.lowercase()
            if (normalized == lowerPattern || normalized.startsWith("$lowerPattern.")) {
                return true
            }
        }
        for (secPkg in systemSecurityPackages) {
            if (normalized == secPkg || normalized.startsWith("$secPkg.")) {
                return true
            }
        }
        return false
    }

    /**
     * Memeriksa apakah [packageName] merupakan dialog izin sistem runtime atau checkout toko.
     */
    fun isSystemSecurityPackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        return systemSecurityPackages.any { normalized == it || normalized.startsWith("$it.") }
    }

    /**
     * Memeriksa apakah elemen dengan [label] atau [resourceId] merupakan aksi
     * ireversibel yang wajib meminta konfirmasi pengguna (`CONFIRM_AND_CLICK`).
     *
     * Saat [sovereignDirectExecution] bernilai `true` (mode proyek pribadi pemilik),
     * aksi rutin seperti kirim pesan, balas chat, simpan kontak, atau posting cerita
     * dieksekusi langsung tanpa cegatan modal HITL. Hanya aksi katastrofik finansial
     * dan penghapusan akun permanen [catastrophicHitlKeywords] yang tetap meminta konfirmasi.
     */
    private fun containsKeywordMatch(
        textLower: String,
        tokens: Set<String>,
        keywords: List<String>
    ): Boolean {
        for (keyword in keywords) {
            if (keyword.contains(' ')) {
                if (textLower.contains(keyword)) {
                    return true
                }
            } else if (tokens.contains(keyword)) {
                return true
            }
        }
        return false
    }

    fun requiresHitlConfirmation(
        label: String,
        resourceId: String? = null,
        sovereignDirectExecution: Boolean = false
    ): Boolean {
        val keywordsToCheck = if (sovereignDirectExecution) {
            catastrophicHitlKeywords
        } else {
            sensitiveHitlKeywords
        }

        if (!resourceId.isNullOrBlank()) {
            val cleanId = resourceId.trim().lowercase()
            for (sensitiveId in sensitiveResourceIds) {
                if (cleanId == sensitiveId.lowercase() ||
                    cleanId.endsWith(":id/send") ||
                    cleanId.endsWith(":id/delete")
                ) {
                    if (sovereignDirectExecution && (cleanId.endsWith(":id/send") || cleanId.contains("save") || cleanId.contains("simpan"))) {
                        continue
                    }
                    return true
                }
            }
            val resEntryName = cleanId.substringAfter(":id/", cleanId)
            val normalizedEntry = resEntryName.replace('_', ' ').replace('-', ' ')
            val idTokens = resEntryName
                .split(nonAlphaNumericRegex)
                .filter { it.isNotEmpty() }
                .toSet()
            if (containsKeywordMatch(normalizedEntry, idTokens, keywordsToCheck)) {
                return true
            }
        }

        val cleanLabel = label.trim().lowercase()
        if (cleanLabel.isEmpty()) {
            return false
        }

        val words = cleanLabel
            .split(nonAlphaNumericRegex)
            .filter { it.isNotEmpty() }
            .toSet()

        return containsKeywordMatch(cleanLabel, words, keywordsToCheck)
    }

    /**
     * Memeriksa apakah suatu kolom input [label] atau [resourceId] adalah
     * kolom kredensial rahasia (PIN / Password / OTP) yang dilarang disentuh.
     */
    fun isCredentialField(
        label: String,
        resourceId: String? = null
    ): Boolean {
        val combined = "${label.lowercase()} ${(resourceId ?: "").lowercase()}"
        val tokens = combined
            .split(nonAlphaNumericRegex)
            .filter { it.isNotEmpty() }
            .toSet()

        for (keyword in credentialFieldKeywords) {
            if (keyword.contains(' ')) {
                if (combined.contains(keyword)) {
                    return true
                }
            } else if (tokens.contains(keyword)) {
                return true
            }
        }
        return false
    }
}
