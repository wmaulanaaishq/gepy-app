package com.teknologigawai.gepy.features.orchestrator

import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.core.skills.SkillRegistry
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.launcher.IntentDeepLinkRouter
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Koordinator playbook terverifikasi (*self-healing functional selector*) dan pra-peluncuran aplikasi target.
 */
internal class PlaybookAndLaunchCoordinator(
    private val perceptionPort: PerceptionPort,
    private val appLauncher: AppIntentLauncher,
    private val skillRegistry: SkillRegistry,
    private val deepLinkRouter: IntentDeepLinkRouter
) {
    fun tryBuildPlaybookDecision(
        targetResourceId: String,
        actionType: String,
        successCount: Int,
        elements: List<PrunedUiElement>
    ): ReActActionModel? {
        if (actionType != "CLICK" && actionType != "CONFIRM_AND_CLICK") return null
        val matchedTarget = elements.firstOrNull { el ->
            el.isClickable && el.viewResourceId == targetResourceId
        } ?: findSelfHealingPlaybookTarget(targetResourceId, elements)
            ?: return null

        val needsHitl = actionType == "CONFIRM_AND_CLICK" ||
            SafetyRules.requiresHitlConfirmation(
                label = matchedTarget.text,
                resourceId = matchedTarget.viewResourceId
            )
        val cleanTargetDesc = matchedTarget.text.trim().takeIf { it.isNotEmpty() }
            ?.let { "tombol \"$it\"" }
            ?: "elemen tujuan"
        return ReActActionModel(
            thought = if (needsHitl) {
                "Menyiapkan konfirmasi sebelum mengetuk $cleanTargetDesc."
            } else {
                "Membuka $cleanTargetDesc sesuai pola terverifikasi."
            },
            reflectionOnLastStep = ReflectionStatus.CORRECT,
            action = if (needsHitl) AgentActionType.CONFIRM_AND_CLICK else AgentActionType.CLICK,
            targetIndex = matchedTarget.index,
            confirmationPrompt = if (needsHitl) {
                "Konfirmasi mengetuk tombol ${matchedTarget.text.ifEmpty { "Kirim" }}?"
            } else {
                null
            }
        )
    }

    /**
     * Pencocokan fungsional multi-atribut (*AutoDroid & AppAgent v2 Functional Selector*,
     * `arXiv:2308.15272v4`, `arXiv:2408.11824v4`) apabila `viewResourceId` aplikasi target
     * berubah setelah pembaruan versi atau migrasi ke Jetpack Compose.
     */
    fun findSelfHealingPlaybookTarget(
        savedResourceId: String,
        elements: List<PrunedUiElement>,
        requireEditable: Boolean = false
    ): PrunedUiElement? {
        val shortId = savedResourceId.substringAfterLast("/").trim().lowercase()
        if (shortId.isEmpty()) return null

        val semanticAliases: Set<String> = when {
            shortId.contains("search") || shortId.contains("cari") ->
                setOf("search", "cari", "pencarian", "telusuri")
            shortId.contains("send") || shortId.contains("kirim") ->
                setOf("send", "kirim")
            shortId.contains("save") || shortId.contains("simpan") ->
                setOf("save", "simpan", "selesai")
            shortId.contains("message") || shortId.contains("pesan") || shortId.contains("composer") ->
                setOf("message", "kirim pesan", "tulis pesan", "ketik pesan", "composer")
            shortId.contains("share") || shortId.contains("bagikan") ->
                setOf("share", "bagikan", "cerita anda", "your story")
            else -> setOf(shortId)
        }

        val idTokens = shortId.split('_', '-', '.').filter { it.length >= 3 }.toSet()

        var bestCandidate: PrunedUiElement? = null
        var bestScore = 0.0

        for (el in elements) {
            val interactiveMatch = if (requireEditable) el.isEditable else el.isClickable
            if (!interactiveMatch) continue
            if (SafetyRules.isPackageForbidden(el.packageName)) continue

            val elShortId = el.viewResourceId?.substringAfterLast("/")?.lowercase().orEmpty()
            val elLabel = el.text.trim().lowercase()

            // 1. Skor kemiripan ekor Resource ID (bobot maks 0.45)
            val idScore = when {
                elShortId.isEmpty() -> 0.0
                elShortId == shortId -> 0.45
                semanticAliases.any { elShortId.contains(it) } -> 0.38
                idTokens.isNotEmpty() && idTokens.any { elShortId.contains(it) } -> 0.30
                else -> 0.0
            }

            // 2. Skor kecocokan fungsi semantik pada teks/label elemen (bobot maks 0.35)
            val textScore = when {
                elLabel.isEmpty() -> 0.0
                semanticAliases.any { elLabel == it } -> 0.35
                semanticAliases.any { elLabel.contains(it) } -> 0.28
                idTokens.isNotEmpty() && idTokens.any { elLabel.contains(it) } -> 0.22
                else -> 0.0
            }

            // 3. Skor kesesuaian kapabilitas interaktif (bobot 0.20 jika ada minimal 1 sinyal ID/teks)
            val capabilityBonus = if (idScore > 0.0 || textScore > 0.0) 0.20 else 0.0
            val totalScore = idScore + textScore + capabilityBonus

            if (totalScore >= 0.48 && totalScore > bestScore) {
                bestScore = totalScore
                bestCandidate = el
            }
        }

        return bestCandidate
    }

    suspend fun ensureInitialAppLaunched(voiceGoal: String) {
        // Prioritas 1: Jika perintah adalah pesan WhatsApp dan kontak ditemukan unik di buku telepon,
        // langsung lompat ke ruang obrolan WhatsApp dengan draf pesan terisi (hemat 3 langkah UI).
        val waDirect = deepLinkRouter.tryPreLaunchWhatsAppChatWithDraft(voiceGoal)
        if (waDirect.handled) {
            appLauncher.waitForApp(
                packageName = "com.whatsapp",
                perceptionPort = perceptionPort,
                timeoutMs = 5_000L
            )
            return
        }

        // Prioritas 2: Jika perintah gabungan diawali "Buka <Aplikasi> lalu ...", luncurkan aplikasi tersebut lebih dulu
        val parsedCompoundLaunch = AppIntentLauncher.parseVoiceAppLaunchIntent(voiceGoal)
        if (parsedCompoundLaunch != null && !parsedCompoundLaunch.isPureLaunchOnly) {
            val res = appLauncher.openApp(parsedCompoundLaunch.appName)
            if (res.success) {
                appLauncher.waitForApp(
                    packageName = res.resolvedPackage,
                    perceptionPort = perceptionPort,
                    timeoutMs = 5_000L
                )
                return
            }
        }

        // Prioritas 2.5: Deteksi penyebutan nama aplikasi/platform di dalam instruksi (mis. "Cari IG Budi lalu kirim DM",
        // "Cari video di YouTube", "Beli barang di Shopee") meskipun tidak diawali kata "Buka"
        val detectedTargetPkg = AppIntentLauncher.detectTargetAppPackageInGoal(voiceGoal)
        if (!detectedTargetPkg.isNullOrEmpty() &&
            perceptionPort.currentForegroundPackage != detectedTargetPkg
        ) {
            val res = appLauncher.openApp(detectedTargetPkg)
            if (res.success) {
                appLauncher.waitForApp(
                    packageName = res.resolvedPackage,
                    perceptionPort = perceptionPort,
                    timeoutMs = 5_000L
                )
                return
            }
        }

        // Prioritas 3: Peluncuran aplikasi standar berdasarkan pencocokan Skill
        val matched = skillRegistry.matchSkills(
            voiceTranscript = voiceGoal,
            activePackage = perceptionPort.currentForegroundPackage
        )
        for (skill in matched) {
            val preferredPkg = skill.preferredInitialPackage
            if (!preferredPkg.isNullOrEmpty() &&
                perceptionPort.currentForegroundPackage != preferredPkg
            ) {
                val res = appLauncher.openApp(preferredPkg)
                if (res.success) {
                    appLauncher.waitForApp(
                        packageName = res.resolvedPackage,
                        perceptionPort = perceptionPort,
                        timeoutMs = 5_000L
                    )
                }
                break
            }
        }
    }
}
