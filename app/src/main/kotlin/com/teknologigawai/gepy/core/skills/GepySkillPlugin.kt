package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.launcher.AppIntentLauncher
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Kontrak ekstensi kemampuan vertikal baru (*Skill Plugin*) Gepy sesuai `skill.md`.
 *
 * Pengembang dapat menambahkan domain baru (misal WhatsApp, Google Calendar,
 * Google Maps, Spotify) cukup dengan mengimplementasikan antarmuka ini dan
 * mendaftarkannya ke [SkillRegistry] tanpa mengubah orkestrator inti.
 */
interface GepySkillPlugin {
    /** Identitas unik skill (contoh: `whatsapp_messaging`, `calendar_scheduler`). */
    val skillId: String

    /** Daftar nama paket aplikasi Android yang didukung oleh skill ini. */
    val supportedPackages: List<String>

    /**
     * Nama aplikasi atau paket utama yang diluncurkan terlebih dahulu ketika
     * perintah suara cocok dengan skill ini (opsional).
     */
    val preferredInitialPackage: String?
        get() = supportedPackages.firstOrNull()

    /** Memeriksa apakah transkripsi perintah suara [transcript] relevan dengan skill ini. */
    fun canHandleVoiceIntent(transcript: String): Boolean

    /**
     * Pengetahuan domain tambahan (*Knowledge Injection*) yang disuntikkan ke dalam
     * *System Prompt* LLM ketika skill ini aktif.
     */
    val domainKnowledgePrompt: String

    /**
     * Hook pra-eksekusi untuk memvalidasi atau meningkatkan keamanan aksi
     * [proposedAction] berdasarkan elemen layar [screenElements] sebelum dieksekusi.
     */
    suspend fun validateBeforeAction(
        proposedAction: ReActActionModel,
        screenElements: List<PrunedUiElement>
    ): ReActActionModel = proposedAction

    /**
     * *Fast Path* deterministik: mengusulkan aksi berikutnya TANPA memanggil LLM
     * apabila skill mengenali layar saat ini dengan keyakinan tinggi.
     *
     * Kembalikan `null` bila layar tidak dikenali atau ambigu agar orkestrator
     * menyerahkan keputusan ke `BrainPort` (LLM). Aksi yang diusulkan tetap
     * melewati `validateBeforeAction`, *Hardcoded Safety Gatekeeper*, dan HITL.
     */
    fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? = null
}

/**
 * Registri terpusat untuk mencocokkan perintah suara dan paket aktif dengan
 * kumpulan [GepySkillPlugin] yang terdaftar.
 */
class SkillRegistry(
    val skills: List<GepySkillPlugin> = listOf(
        AndroidSystemToolSkill(),
        LaptopCompanionSkill(),
        SpotifyFastPathSkill(),
        ScreenReaderSummarySkill(),
        WhatsAppMessagingSkill(),
        InstagramSocialSkill(),
        CalendarSchedulerSkill(),
        UniversalAppNavigationSkill()
    )
) {
    /** Mencari seluruh skill yang relevan berdasarkan [voiceTranscript] atau [activePackage]. */
    fun matchSkills(
        voiceTranscript: String,
        activePackage: String
    ): List<GepySkillPlugin> {
        val cleanPkg = activePackage.trim().lowercase()
        val explicitTargetPkg = AppIntentLauncher.detectTargetAppPackageInGoal(voiceTranscript)
        return skills.filter { skill ->
            val byIntent = skill.canHandleVoiceIntent(voiceTranscript)
            // Jangan menyuntikkan skill aplikasi lama (misal WhatsApp) apabila pengguna meminta operasi di aplikasi lain (misal Instagram/YouTube)
            val isSwitchingAway = explicitTargetPkg != null &&
                skill.supportedPackages.isNotEmpty() &&
                skill.supportedPackages.none { pkg -> pkg.equals(explicitTargetPkg, ignoreCase = true) }
            val byPackage = !isSwitchingAway &&
                cleanPkg.isNotEmpty() &&
                skill.supportedPackages.any { pkg -> pkg.lowercase() == cleanPkg }
            byIntent || byPackage
        }
    }

    /** Menggabungkan `domainKnowledgePrompt` dari seluruh skill yang cocok. */
    fun buildCombinedDomainKnowledge(
        voiceTranscript: String,
        activePackage: String
    ): String? {
        val matched = matchSkills(
            voiceTranscript = voiceTranscript,
            activePackage = activePackage
        )
        if (matched.isEmpty()) {
            return null
        }
        return matched.joinToString("\n\n") { it.domainKnowledgePrompt }
    }
}
