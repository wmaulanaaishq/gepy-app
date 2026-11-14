package com.teknologigawai.gepy.core.system

import android.location.LocationManager
import android.media.session.MediaSessionManager
import android.provider.ContactsContract
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext

/** Cuplikan status sistem perangkat & waktu OS Android. */
data class DeviceSystemStatus(
    val formattedDateTimeId: String,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String,
    val mediaVolumePercent: Int,
    val ringerMode: String
) {
    fun toNaturalSummaryId(speechContext: SovereignSpeechContext = SovereignSpeechContext()): String =
        SovereignNaturalSpeech.deviceStatusSummary(
            ctx = speechContext,
            formattedDateTimeId = formattedDateTimeId,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            networkType = networkType,
            mediaVolumePercent = mediaVolumePercent,
            ringerMode = ringerMode
        )

    fun toMap(speechContext: SovereignSpeechContext = SovereignSpeechContext()): Map<String, Any?> = mapOf(
        "status" to "SUCCESS",
        "datetime_id" to formattedDateTimeId,
        "battery_percent" to batteryPercent,
        "is_charging" to isCharging,
        "network_type" to networkType,
        "media_volume_percent" to mediaVolumePercent,
        "ringer_mode" to ringerMode,
        "summary_id" to toNaturalSummaryId(speechContext)
    )
}

/** Representasi kontak buku telepon Android (`ContactsContract`). */
data class PhoneContactItem(
    val displayName: String,
    val phoneNumber: String
)

/** Representasi lokasi GPS terakhir perangkat (`LocationManager`). */
data class DeviceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val provider: String
) {
    fun toNaturalSummaryId(speechContext: SovereignSpeechContext = SovereignSpeechContext()): String =
        SovereignNaturalSpeech.locationFound(
            ctx = speechContext,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            provider = provider
        )
}

/** Representasi sesi pemutaran media aktif yang terbaca dari `MediaSessionManager`. */
data class ActiveMediaSessionInfo(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String = "",
    val isPlaying: Boolean = true
)

