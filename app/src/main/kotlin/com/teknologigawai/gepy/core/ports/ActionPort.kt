package com.teknologigawai.gepy.core.ports

import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/** Status verifikasi pasca-eksekusi dari komponen *Closed-Loop Checker*. */
enum class ActionOutcome(val wireName: String) {
    /** Layar berubah sesuai ekspektasi setelah aksi dieksekusi. */
    CORRECT("CORRECT"),

    /** Layar tidak berubah sama sekali baik setelah aksi logis maupun fallback gestur. */
    INEFFECTIVE("INEFFECTIVE"),

    /** Aksi ditahan atau dibatalkan oleh pengguna pada gerbang *Human-in-the-Loop*. */
    BLOCKED_BY_HITL("BLOCKED_BY_HITL")
}

/** Port Hexagonal untuk eksekusi aksi UI dan verifikasi transisi layar (*Tangan Gepy*). */
interface ActionPort {
    /**
     * Menahan eksekusi hingga tanda tangan layar stabil selama [settleCount] siklus
     * berturut-turut tanpa menggunakan jeda statis arbitrer.
     */
    suspend fun waitStable(
        timeoutMs: Long = EnvConfig.defaultWaitStableTimeoutMs,
        pollIntervalMs: Long = EnvConfig.defaultPollIntervalMs,
        settleCount: Int = EnvConfig.defaultSettleCount,
        changedFrom: String? = null
    ): List<PrunedUiElement>

    /**
     * Mengetuk elemen [target] menggunakan jalur ganda (*NodeAction -> Gesture Fallback*)
     * disertai pemeriksaan *Safety Gatekeeper* dan *Closed-Loop Checker*.
     */
    suspend fun tapElement(
        target: PrunedUiElement,
        preSignature: String,
        hitlApproved: Boolean = false
    ): ActionOutcome

    /** Menyuntikkan ketukan fisik satu titik pada koordinat layar ([x], [y]). */
    suspend fun tapCoordinates(x: Float, y: Float): Boolean

    /** Menekan dan menahan titik koordinat ([x], [y]) selama [durationMs]. */
    suspend fun longPressCoordinates(
        x: Float,
        y: Float,
        durationMs: Long = 800L
    ): Boolean

    /**
     * Memfokuskan elemen `EDITABLE` [target] terlebih dahulu (*Focus-Before-Type*)
     * kemudian menyuntikkan [text] melalui `ACTION_SET_TEXT` (+ fallback Clipboard).
     */
    suspend fun setTextElement(
        target: PrunedUiElement,
        text: String,
        preSignature: String,
        clearExisting: Boolean = false
    ): ActionOutcome

    /**
     * Aksi komposit *AutoDroid* (`SET_TEXT_AND_SUBMIT`): memfokuskan kolom input pencarian,
     * mengetik [text], lalu langsung mengeksekusi aksi Enter/Search dalam satu langkah.
     */
    suspend fun setTextAndSubmitElement(
        target: PrunedUiElement,
        text: String,
        preSignature: String,
        clearExisting: Boolean = false
    ): ActionOutcome = setTextElement(
        target = target,
        text = text,
        preSignature = preSignature,
        clearExisting = clearExisting
    )

    /**
     * Menggulir konten layar ke arah [direction] (`DOWN` untuk melihat konten bawah,
     * `UP` untuk melihat konten atas) dan memverifikasi perubahan layar.
     */
    suspend fun scrollScreen(
        direction: String,
        preSignature: String,
        amount: Double = 0.6
    ): ActionOutcome

    /** Menyuntikkan sapuan jari ke arah [fingerDirection] (`UP`, `DOWN`, `LEFT`, `RIGHT`). */
    suspend fun swipeScreen(
        fingerDirection: String,
        distance: Double = 0.4
    ): Boolean

    /** Menggulir layar secara bertahap hingga [predicate] terpenuhi atau mencapai batas daftar. */
    suspend fun scrollUntil(
        direction: String = "DOWN",
        maxScrolls: Int = 15,
        predicate: (List<PrunedUiElement>) -> Boolean
    ): Boolean

    /** Mengumpulkan dan mendeduplikasi item dari daftar panjang sambil menggulir layar. */
    suspend fun <T : Any> scrollCollect(
        maxScrolls: Int = 20,
        endAfterUnchanged: Int = 2,
        extractor: (PrunedUiElement) -> T?
    ): List<T>

    /** Menjalankan aksi sistem global Kembali (`GLOBAL_ACTION_BACK`). */
    suspend fun navigateBack(): Boolean

    /** Menjalankan aksi sistem global Beranda (`GLOBAL_ACTION_HOME`). */
    suspend fun navigateHome(): Boolean

    /** Menjalankan aksi sistem global Aplikasi Terbaru (`GLOBAL_ACTION_RECENTS`). */
    suspend fun navigateRecents(): Boolean
}
