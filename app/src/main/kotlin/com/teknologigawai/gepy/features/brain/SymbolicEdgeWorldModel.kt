package com.teknologigawai.gepy.features.brain

import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.EdgeRiskRule
import com.teknologigawai.gepy.core.ports.PredictedEdgeOutcome
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import java.util.Locale

/** Keputusan intersep pra-eksekusi SEWM ketika suatu aksi diprediksi kuat memicu risiko tinggi. */
data class PreActionEdgeVeto(
    val matchedRule: EdgeRiskRule,
    val targetElement: PrunedUiElement,
    val feedbackForBrain: String
)

/**
 * **Symbolic Edge World Model (SEWM — Room v6)**:
 * Model dunia simbolik ringan tanpa NPU (`< 1,5 MB` SQLite, `< 0,1 ms` query RAM cache)
 * yang mempelajari hubungan `(package, structural_screen_hash, edge_key, action) -> outcome`:
 * - `PAYWALL` (layar berlangganan / upgrade pro / checkout store)
 * - `AD_POPUP` (pop-up iklan / sponsored / modal rating)
 * - `PERMISSION_MODAL` (dialog izin OS yang mengganggu alur)
 * - `ERROR_DIALOG` (dialog gagal muat / putus koneksi)
 * - `INEFFECTIVE_DEADEND` (tombol buntu yang berulang tidak mengubah layar)
 *
 * Dengan memprediksi efek sebelum menyentuh tombol, Gepy menyaring Playbook berbahaya,
 * menyuntikkan peringatan tepat sasaran ke Brain, dan mencegat klik berisiko tinggi sebelum terjadi.
 */
object SymbolicEdgeWorldModel {

    /** Kapasitas keras DB SQLite agar ukuran total tabel + 3 indeks tetap jauh di bawah `1,5 MB`. */
    const val MAX_SEWM_DB_RULES: Int = 2_000

    /** Perkiraan ukuran rata-rata 1 baris `ui_transition_rules` + 3 indeks B-tree SQLite (~450 B). */
    const val ESTIMATED_BYTES_PER_ROW: Long = 450L

    const val MAX_EDGE_KEY_LEN: Int = 96
    const val MAX_LABEL_SAMPLE_LEN: Int = 48
    const val MAX_MITIGATION_HINT_LEN: Int = 120

    const val PROMPT_WARNING_THRESHOLD: Float = 0.45f
    const val PLAYBOOK_BLOCK_THRESHOLD: Float = 0.60f
    const val PRE_CLICK_VETO_THRESHOLD: Float = 0.70f

    private val whitespaceRegex = Regex("""\s+""")
    private val genericAndroidIdTails = setOf("content", "text1", "text2", "title", "icon", "container")

    private val paywallStrongPhrases = listOf(
        "coba gratis",
        "free trial",
        "mulai uji coba",
        "start free trial",
        "berlangganan",
        "langganan",
        "upgrade pro",
        "upgrade ke pro",
        "upgrade premium",
        "upgrade to pro",
        "paket bulanan",
        "paket tahunan",
        "pilih paket",
        "per bulan",
        "/bulan",
        "/month",
        "subscribe now",
        "paywall"
    )

    private val adPopupStrongPhrases = listOf(
        "tutup iklan",
        "lewati iklan",
        "abaikan iklan",
        "skip ad",
        "close ad",
        "sponsored",
        "beri nilai",
        "beri rating",
        "rate this app",
        "enjoying "
    )

    private val permissionModalPhrases = listOf(
        "saat aplikasi digunakan",
        "while using the app",
        "hanya kali ini",
        "only this time",
        "izinkan ",
        "allow "
    )

    private val permissionContextSecondary = listOf(
        "mengakses",
        "lokasi",
        "kamera",
        "mikrofon",
        "penyimpanan",
        "kontak",
        "notifikasi",
        "access ",
        "location",
        "notifications"
    )

    private val errorDialogPhrases = listOf(
        "tidak ada koneksi internet",
        "koneksi terputus",
        "terjadi kesalahan",
        "something went wrong",
        "coba lagi nanti",
        "try again",
        "coba lagi",
        "gagal memuat",
        "failed to load",
        "isn't responding",
        "tidak menanggapi"
    )

    private val storePackages = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps"
    )

    /** Membangun kunci simbolik kanonik untuk suatu elemen UI target (`<= 96` karakter). */
    fun buildEdgeKey(element: PrunedUiElement): String =
        buildEdgeKey(resourceId = element.viewResourceId, label = element.text)

    /** Membangun kunci simbolik kanonik dari pasangan `resourceId` dan label teks tombol. */
    fun buildEdgeKey(resourceId: String?, label: String): String {
        val cleanId = resourceId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normLabel = normalizeLabel(label)
        if (cleanId.isNotEmpty()) {
            val tail = cleanId.substringAfterLast('/')
            return if (tail in genericAndroidIdTails && normLabel.isNotEmpty()) {
                "$cleanId|$normLabel".take(MAX_EDGE_KEY_LEN)
            } else {
                cleanId.take(MAX_EDGE_KEY_LEN)
            }
        }
        return if (normLabel.isNotEmpty()) {
            "label:$normLabel".take(MAX_EDGE_KEY_LEN)
        } else {
            "label:unnamed"
        }
    }

    fun normalizeLabel(rawLabel: String): String {
        return rawLabel
            .trim()
            .lowercase(Locale.ROOT)
            .replace(whitespaceRegex, " ")
            .take(MAX_LABEL_SAMPLE_LEN)
    }

    /** Menghitung skor risiko `[0.0f..1.0f]` dan kelas hasil dominan dari seluruh penghitung observasi. */
    fun computeRiskScoreAndDominantOutcome(
        safeCount: Int,
        paywallCount: Int,
        adPopupCount: Int,
        permissionModalCount: Int,
        errorCount: Int,
        ineffectiveCount: Int
    ): Pair<PredictedEdgeOutcome, Float> {
        val s = safeCount.coerceAtLeast(0)
        val pw = paywallCount.coerceAtLeast(0)
        val ad = adPopupCount.coerceAtLeast(0)
        val pm = permissionModalCount.coerceAtLeast(0)
        val er = errorCount.coerceAtLeast(0)
        val inef = ineffectiveCount.coerceAtLeast(0)

        val total = s + pw + ad + pm + er + inef
        if (total <= 0) return PredictedEdgeOutcome.SAFE_PROGRESS to 0f

        val adverseWeighted = (pw * PredictedEdgeOutcome.PAYWALL.baseSeverity) +
            (ad * PredictedEdgeOutcome.AD_POPUP.baseSeverity) +
            (pm * PredictedEdgeOutcome.PERMISSION_MODAL.baseSeverity) +
            (er * PredictedEdgeOutcome.ERROR_DIALOG.baseSeverity) +
            (inef * PredictedEdgeOutcome.INEFFECTIVE_DEADEND.baseSeverity)

        if (adverseWeighted <= 0f) {
            return PredictedEdgeOutcome.SAFE_PROGRESS to 0f
        }

        val riskScore = (adverseWeighted / (total.toFloat() + 0.35f)).coerceIn(0f, 1f)
        val adverseCandidates = listOf(
            PredictedEdgeOutcome.PAYWALL to pw,
            PredictedEdgeOutcome.AD_POPUP to ad,
            PredictedEdgeOutcome.PERMISSION_MODAL to pm,
            PredictedEdgeOutcome.ERROR_DIALOG to er,
            PredictedEdgeOutcome.INEFFECTIVE_DEADEND to inef
        ).filter { it.second > 0 }

        val topAdverse = adverseCandidates.maxWithOrNull(
            compareBy<Pair<PredictedEdgeOutcome, Int>> { it.second }
                .thenBy { it.first.baseSeverity }
        )?.first ?: PredictedEdgeOutcome.SAFE_PROGRESS

        val maxAdverseCount = adverseCandidates.maxOfOrNull { it.second } ?: 0
        val dominant = if (s > maxAdverseCount && riskScore < PROMPT_WARNING_THRESHOLD) {
            PredictedEdgeOutcome.SAFE_PROGRESS
        } else {
            topAdverse
        }
        return dominant to riskScore
    }

    /**
     * Mengklasifikasikan efek transisi layar pasca-aksi secara deterministik `0 ms` (tanpa NPU)
     * dengan membandingkan elemen & paket *yang baru muncul* setelah aksi dijalankan.
     */
    fun classifyTransition(
        userGoal: String,
        prePackage: String,
        preElements: List<PrunedUiElement>,
        postPackage: String,
        postElements: List<PrunedUiElement>,
        rawOutcome: ActionOutcome
    ): PredictedEdgeOutcome {
        val goalLower = userGoal.trim().lowercase(Locale.ROOT)
        val prePkgLower = prePackage.trim().lowercase(Locale.ROOT)
        val postPkgLower = postPackage.trim().lowercase(Locale.ROOT)

        val preTextSet = preElements.mapNotNull { el ->
            el.text.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
        }.toSet()
        val newlyAppearedLines = postElements
            .mapNotNull { el -> el.text.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() } }
            .filter { it !in preTextSet }
        val newlyJoined = newlyAppearedLines.joinToString(" | ")

        // 1. Paywall / Subscription Upsell / Store Checkout (cek sebelum Permission Controller
        //    karena SafetyRules.systemSecurityPackages juga mencakup paket checkout Play/Galaxy Store)
        val enteredStoreFromOtherApp = postPkgLower in storePackages && prePkgLower.isNotEmpty() && prePkgLower !in storePackages
        val paywallSignalCount = paywallStrongPhrases.count { ph -> newlyJoined.contains(ph) }
        if ((enteredStoreFromOtherApp || paywallSignalCount >= 1) &&
            !isRiskyOutcomeExemptedByGoal(goalLower, PredictedEdgeOutcome.PAYWALL, "")
        ) {
            return PredictedEdgeOutcome.PAYWALL
        }

        // 2. Permission Modal OS (hindari menyalahkan paket store checkout sebagai dialog izin)
        val enteredPermPkg = postPkgLower.isNotEmpty() &&
            postPkgLower != prePkgLower &&
            postPkgLower !in storePackages &&
            SafetyRules.isSystemSecurityPackage(postPkgLower)
        val hasPermText = permissionModalPhrases.any { ph -> newlyJoined.contains(ph) } &&
            permissionContextSecondary.any { sec -> newlyJoined.contains(sec) }
        if ((enteredPermPkg || hasPermText) && !isRiskyOutcomeExemptedByGoal(goalLower, PredictedEdgeOutcome.PERMISSION_MODAL, "")) {
            return PredictedEdgeOutcome.PERMISSION_MODAL
        }

        // 3. Ad Pop-up / Promo / Rating Modal
        val adSignal = adPopupStrongPhrases.any { ph -> newlyJoined.contains(ph) }
        if (adSignal && !isRiskyOutcomeExemptedByGoal(goalLower, PredictedEdgeOutcome.AD_POPUP, "")) {
            return PredictedEdgeOutcome.AD_POPUP
        }

        // 4. Error Dialog / Network Failure
        if (errorDialogPhrases.any { ph -> newlyJoined.contains(ph) }) {
            return PredictedEdgeOutcome.ERROR_DIALOG
        }

        // 5. Tombol buntu / tidak mengubah layar
        if (rawOutcome == ActionOutcome.INEFFECTIVE) {
            return PredictedEdgeOutcome.INEFFECTIVE_DEADEND
        }

        return PredictedEdgeOutcome.SAFE_PROGRESS
    }

    /** Membangun saran mitigasi ringkas (`<= 120` char) agar masuk anggaran `< 1,5 MB` SQLite. */
    fun defaultMitigationHint(outcome: PredictedEdgeOutcome, targetLabel: String): String {
        val cleanTarget = targetLabel.trim().ifEmpty { "elemen ini" }.take(24)
        val raw = when (outcome) {
            PredictedEdgeOutcome.PAYWALL -> "Hindari '$cleanTarget': memicu paywall/upgrade. Pilih tombol lewati atau jalur gratis."
            PredictedEdgeOutcome.AD_POPUP -> "Hindari '$cleanTarget': memicu pop-up iklan/rating. Pilih elemen tugas utama."
            PredictedEdgeOutcome.PERMISSION_MODAL -> "Waspada '$cleanTarget': memicu dialog izin OS. Gunakan fitur lain bila tidak wajib."
            PredictedEdgeOutcome.ERROR_DIALOG -> "Waspada '$cleanTarget': sering memicu dialog error. Coba muat ulang atau jalur lain."
            PredictedEdgeOutcome.INEFFECTIVE_DEADEND -> "Hindari '$cleanTarget': berulang kali tidak merespons (dead-end). Gunakan SCROLL atau BACK."
            PredictedEdgeOutcome.SAFE_PROGRESS -> ""
        }
        return raw.take(MAX_MITIGATION_HINT_LEN)
    }

    /** Memeriksa apakah tujuan pengguna justru *meminta* aksi yang berisiko (mis. beli langganan/izinkan). */
    fun isRiskyOutcomeExemptedByGoal(
        userGoal: String,
        outcome: PredictedEdgeOutcome,
        targetLabel: String
    ): Boolean {
        val goalLower = userGoal.trim().lowercase(Locale.ROOT)
        val targetLower = targetLabel.trim().lowercase(Locale.ROOT)
        if (targetLower.isNotEmpty() && targetLower.length >= 4 && goalLower.contains(targetLower)) {
            return true
        }
        return when (outcome) {
            PredictedEdgeOutcome.PAYWALL ->
                goalLower.contains("langganan") ||
                    goalLower.contains("berlangganan") ||
                    goalLower.contains("subscribe") ||
                    goalLower.contains("premium") ||
                    goalLower.contains("upgrade") ||
                    goalLower.contains("beli paket") ||
                    goalLower.contains("bayar")
            PredictedEdgeOutcome.AD_POPUP ->
                goalLower.contains("rating") ||
                    goalLower.contains("beri nilai") ||
                    goalLower.contains("bintang") ||
                    goalLower.contains("iklan")
            PredictedEdgeOutcome.PERMISSION_MODAL ->
                goalLower.contains("izinkan") ||
                    goalLower.contains("izin ") ||
                    goalLower.contains("allow") ||
                    goalLower.contains("permission")
            PredictedEdgeOutcome.ERROR_DIALOG,
            PredictedEdgeOutcome.INEFFECTIVE_DEADEND,
            PredictedEdgeOutcome.SAFE_PROGRESS -> false
        }
    }

    /** Mencocokkan elemen UI aktual di layar dengan daftar aturan SEWM yang sudah dimuat (`O(N)` kecil di RAM). */
    fun matchElementToRules(
        element: PrunedUiElement,
        rules: List<EdgeRiskRule>,
        actionType: String? = null
    ): EdgeRiskRule? {
        if (rules.isEmpty()) return null
        val elementEdgeKey = buildEdgeKey(element)
        val elResIdLower = element.viewResourceId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val elLabelNorm = normalizeLabel(element.text)

        return rules
            .filter { rule ->
                if (actionType != null && !rule.actionType.equals(actionType, ignoreCase = true)) {
                    return@filter false
                }
                rule.edgeKey == elementEdgeKey ||
                    (elResIdLower.isNotEmpty() && rule.edgeKey == elResIdLower) ||
                    (elLabelNorm.isNotEmpty() && rule.targetLabelSample == elLabelNorm)
            }
            .maxByOrNull { it.riskScore }
    }

    /** Memeriksa apakah kandidat Playbook menunjuk ke edge berisiko tinggi menurut SEWM. */
    fun isPlaybookTargetRisky(
        targetResourceId: String,
        actionType: String,
        rules: List<EdgeRiskRule>,
        userGoal: String
    ): Boolean {
        if (rules.isEmpty()) return false
        val cleanId = targetResourceId.trim().lowercase(Locale.ROOT)
        if (cleanId.isEmpty()) return false
        val matchedRule = rules.firstOrNull { rule ->
            rule.actionType.equals(actionType, ignoreCase = true) &&
                (rule.edgeKey == cleanId || rule.edgeKey.startsWith("$cleanId|"))
        } ?: return false
        if (!matchedRule.dominantOutcome.isAdverse || matchedRule.riskScore < PLAYBOOK_BLOCK_THRESHOLD) {
            return false
        }
        return !isRiskyOutcomeExemptedByGoal(userGoal, matchedRule.dominantOutcome, matchedRule.targetLabelSample)
    }

    /**
     * Membangun peringatan padat pra-aksi untuk disuntikkan ke `domain_hint` BrainPort,
     * memetakan aturan risiko SQLite langsung ke nomor `[idx]` elemen di layar aktif.
     */
    fun buildPreActionPromptAdvice(
        elements: List<PrunedUiElement>,
        rules: List<EdgeRiskRule>,
        userGoal: String
    ): String? {
        if (elements.isEmpty() || rules.isEmpty()) return null
        val riskyRules = rules.filter { it.dominantOutcome.isAdverse && it.riskScore >= PROMPT_WARNING_THRESHOLD }
        if (riskyRules.isEmpty()) return null

        val warnings = mutableListOf<String>()
        for (el in elements) {
            if (!el.isClickable && !el.isEditable) continue
            val matched = matchElementToRules(el, riskyRules) ?: continue
            if (isRiskyOutcomeExemptedByGoal(userGoal, matched.dominantOutcome, el.text)) continue

            val label = el.text.trim().ifEmpty { el.viewResourceId?.substringAfterLast('/') ?: el.nodeId }.take(28)
            val scoreStr = String.format(Locale.US, "%.2f", matched.riskScore)
            warnings.add(
                "- HINDARI idx=${el.index} (\"$label\"): diprediksi memicu ${matched.dominantOutcome.wireName} (skor $scoreStr). ${matched.mitigationHint}"
            )
            if (warnings.size >= 4) break
        }
        if (warnings.isEmpty()) return null
        return "[SEWM PRE-ACTION RISK MODEL]\n" + warnings.joinToString("\n")
    }

    /**
     * Gerbang penjaga pra-eksekusi SEWM (`0 ms` di RAM):
     * Mencegat aksi klik pada tombol berisiko tinggi (`riskScore >= 0.70`) **sebelum menyentuh OS**,
     * kecuali bila memang diminta pengguna atau sudah pernah di-veto sebelumnya pada sesi ini.
     */
    fun evaluatePreActionEdgeGuard(
        decision: ReActActionModel,
        elements: List<PrunedUiElement>,
        rules: List<EdgeRiskRule>,
        userGoal: String,
        alreadyVetoedEdgeKeys: Set<String>
    ): PreActionEdgeVeto? {
        if (decision.action != AgentActionType.CLICK &&
            decision.action != AgentActionType.CONFIRM_AND_CLICK &&
            decision.action != AgentActionType.LONG_PRESS
        ) {
            return null
        }
        val targetIdx = decision.targetIndex ?: return null
        val targetEl = elements.firstOrNull { it.index == targetIdx } ?: return null
        val matched = matchElementToRules(targetEl, rules, actionType = decision.action.wireName)
            ?: matchElementToRules(targetEl, rules, actionType = AgentActionType.CLICK.wireName)
            ?: return null

        if (!matched.dominantOutcome.isAdverse || matched.riskScore < PRE_CLICK_VETO_THRESHOLD) {
            return null
        }
        if (matched.edgeKey in alreadyVetoedEdgeKeys) {
            return null
        }
        if (isRiskyOutcomeExemptedByGoal(userGoal, matched.dominantOutcome, targetEl.text)) {
            return null
        }

        val label = targetEl.text.trim().ifEmpty { matched.targetLabelSample }.ifEmpty { "idx=$targetIdx" }
        val scoreStr = String.format(Locale.US, "%.2f", matched.riskScore)
        val feedback = "SEWM Guard memprediksi aksi pada idx=$targetIdx (\"$label\") akan memicu " +
            "${matched.dominantOutcome.wireName} (skor risiko $scoreStr). " +
            "Klik dibatalkan sebelum menyentuh layar! Pilih elemen alternatif atau BACK. " +
            matched.mitigationHint
        return PreActionEdgeVeto(
            matchedRule = matched,
            targetElement = targetEl,
            feedbackForBrain = feedback.trim()
        )
    }
}
