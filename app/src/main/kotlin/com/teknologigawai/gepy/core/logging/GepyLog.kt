package com.teknologigawai.gepy.core.logging

import android.util.Log

/**
 * Logger terstruktur untuk sistem Gepy (`com.teknologigawai.gepy`).
 *
 * Mengalirkan seluruh pesan log dan penanganan kesalahan ke Android Logcat
 * dengan tag utama `Gepy` dan prefix `[<modul>]`, memungkinkan pemfilteran
 * langsung melalui `adb logcat -s Gepy` tanpa menelan kegagalan sistem secara diam-diam.
 */
object GepyLog {
    const val ROOT_TAG: String = "Gepy"

    fun formatMessage(tag: String, message: String): String {
        val cleanTag = tag.trim().ifEmpty { "Core" }
        return "[$cleanTag] $message"
    }

    /** Tingkat log untuk debugging internal & tracing. */
    fun d(tag: String, message: String) {
        val formatted = formatMessage(tag, message)
        try {
            Log.d(ROOT_TAG, formatted)
        } catch (_: Throwable) {
            println("D/$ROOT_TAG: $formatted")
        }
    }

    /** Tingkat log untuk informasi transisi state penting & tonggak eksekusi. */
    fun i(tag: String, message: String) {
        val formatted = formatMessage(tag, message)
        try {
            Log.i(ROOT_TAG, formatted)
        } catch (_: Throwable) {
            println("I/$ROOT_TAG: $formatted")
        }
    }

    /** Tingkat log untuk kondisi peringatan (failover, retry, deteksi degradasi). */
    fun w(tag: String, message: String, error: Throwable? = null) {
        val formatted = formatMessage(tag, message)
        try {
            if (error != null) {
                Log.w(ROOT_TAG, formatted, error)
            } else {
                Log.w(ROOT_TAG, formatted)
            }
        } catch (_: Throwable) {
            println("W/$ROOT_TAG: $formatted ${error?.message ?: ""}".trim())
        }
        try {
            if (error != null) {
                io.sentry.Sentry.captureException(error)
            }
        } catch (_: Throwable) {
            // Sentry tidak aktif atau berjalan di JVM unit test
        }
    }

    /** Tingkat log untuk kesalahan kritis sistem yang membatalkan alur eksekusi. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        val formatted = formatMessage(tag, message)
        try {
            if (error != null) {
                Log.e(ROOT_TAG, formatted, error)
            } else {
                Log.e(ROOT_TAG, formatted)
            }
        } catch (_: Throwable) {
            System.err.println("E/$ROOT_TAG: $formatted ${error?.message ?: ""}".trim())
        }
        try {
            if (error != null) {
                io.sentry.Sentry.captureException(error)
            } else {
                io.sentry.Sentry.captureMessage(formatted)
            }
        } catch (_: Throwable) {
            // Sentry tidak aktif atau berjalan di JVM unit test
        }
    }
}
