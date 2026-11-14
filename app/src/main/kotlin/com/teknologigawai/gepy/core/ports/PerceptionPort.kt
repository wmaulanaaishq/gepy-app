package com.teknologigawai.gepy.core.ports

import com.teknologigawai.gepy.features.perception.models.PruneMetricsSnapshot
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import com.teknologigawai.gepy.features.perception.models.TextBox

/**
 * Port Hexagonal untuk lapisan persepsi layar (*Mata Gepy*).
 *
 * Mengabstraksikan pembacaan *Accessibility Tree* (`XML-First`) dan
 * *fallback* pengenalan teks optik (*OCR*) ke dalam kosakata primitif
 * *On-Device Poor Man's DOM* sesuai `ARCHITECTURE.md` dan `skill.md`.
 */
interface PerceptionPort {
    /** Nama paket aplikasi latar depan (*foreground*) yang terakhir teramati. */
    val currentForegroundPackage: String

    /**
     * Menangkap dan memangkas pohon UI aktif menjadi daftar elemen berindeks `1..N`.
     *
     * Jika [forceOcrFallback] bernilai `true` atau pohon XML mengembalikan `< 2`
     * elemen interaktif pada kanvas kustom, metode otomatis mengeksekusi
     * tangkapan layar senyap dan OCR lokal Google ML Kit.
     */
    suspend fun captureAndPruneScreen(
        forceOcrFallback: Boolean = false,
        includeNonInteractive: Boolean = true
    ): List<PrunedUiElement>

    /**
     * Mengambil cuplikan pemangkasan lengkap beserta metrik kompresi token
     * untuk keperluan pencatatan telemetri.
     */
    suspend fun capturePruneSnapshot(
        forceOcrFallback: Boolean = false
    ): PruneMetricsSnapshot

    /** Mengembalikan daftar kotak teks terlihat di layar (`screen()` primitive). */
    suspend fun readScreenTextBoxes(minConfidence: Double = 0.3): List<TextBox>

    /** Mencari elemen terlihat di layar yang teksnya cocok dengan [query]. */
    suspend fun findText(
        query: String,
        exact: Boolean = false
    ): List<PrunedUiElement>

    /** Melakukan polling hingga teks [query] muncul di layar atau melewati [timeoutMs]. */
    suspend fun waitForText(
        query: String,
        timeoutMs: Long = 10_000L,
        intervalMs: Long = 400L
    ): PrunedUiElement?

    /** Mengambil tangkapan layar senyap di memori menggunakan API aksesibilitas. */
    suspend fun captureScreenshot(): ByteArray?

    /** Menghitung tanda tangan (*screen signature*) deterministik dari daftar [elements]. */
    fun computeSignature(elements: List<PrunedUiElement>): String

    /** Membebaskan langganan dan sumber daya pengenal teks. */
    fun dispose()
}
