package com.teknologigawai.gepy.core.persona

import com.teknologigawai.gepy.core.config.SovereignPersonaMode

/**
 * Pembantu pembentuk sapaan & pembuka kalimat deterministik yang digunakan di seluruh template [SovereignNaturalSpeech].
 */
internal object SovereignNaturalSpeechHelpers {
    fun eff(ctx: SovereignSpeechContext): SovereignPersonaMode =
        SovereignPersonaEngine.resolveEffectivePersona(ctx)

    fun isRomanticPrivate(ctx: SovereignSpeechContext): Boolean =
        eff(ctx) == SovereignPersonaMode.ROMANTIC_GIRLFRIEND && !ctx.wellness.isGuestPrivacyShieldActive

    fun warm(ctx: SovereignSpeechContext): String =
        if (isRomanticPrivate(ctx)) "[warmly] " else ""

    fun soft(ctx: SovereignSpeechContext): String =
        if (isRomanticPrivate(ctx)) "[softly] " else ""

    /** Memanggil pengguna dengan namanya (`" Maulana"`), atau kosong bila nama belum diketahui. */
    fun callUser(ctx: SovereignSpeechContext): String =
        ctx.resolvedOwnerName.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()

    /** Pembuka afirmatif terisolasi kalimat (`.`) agar `VoiceClauseChunker` meng-cache sebagai klip `0 credit`. */
    fun openOk(ctx: SovereignSpeechContext): String = when (eff(ctx)) {
        SovereignPersonaMode.CUSTOM -> "Siap${callUser(ctx)}."
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${warm(ctx)}Oke${callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR -> "Sip${callUser(ctx)}."
        else -> "Oke${callUser(ctx)}."
    }

    fun openBeres(ctx: SovereignSpeechContext): String = when (eff(ctx)) {
        SovereignPersonaMode.CUSTOM -> "Beres${callUser(ctx)}."
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${warm(ctx)}Beres${callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR -> "Sip${callUser(ctx)}."
        else -> "Oke${callUser(ctx)}."
    }

    fun openSoftDuh(ctx: SovereignSpeechContext): String = when (eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${soft(ctx)}Duh${callUser(ctx)}."
        SovereignPersonaMode.HONEST_MENTOR -> "Hmm."
        else -> "Duh${callUser(ctx)}."
    }

    fun openSoftHmm(ctx: SovereignSpeechContext): String = when (eff(ctx)) {
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "${soft(ctx)}Hmm${callUser(ctx)}."
        else -> "Hmm."
    }
}
