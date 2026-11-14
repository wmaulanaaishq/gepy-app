package com.teknologigawai.gepy.core.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.teknologigawai.gepy.service.CapturedNotificationEntry
import com.teknologigawai.gepy.service.GepyAccessibilityService
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Delegasi telemetri sistem OS, clipboard, kontak telepon/email, notifikasi terbaru, dan lokasi GPS terakhir.
 */
internal class DeviceTelemetryAndContactsBridge(
    private val bridge: AndroidSystemToolsBridge,
    private val context: Context?,
    private val clockProvider: () -> ZonedDateTime
) {
    @Volatile
    private var inMemoryClipboardFallback: String = ""

    /** Menyalin [text] ke Clipboard OS Android (atau fallback in-memory pada unit test JVM). */
    fun copyToClipboard(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        inMemoryClipboardFallback = clean
        val ctx = context ?: return true
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return true
            cm.setPrimaryClip(ClipData.newPlainText("gepy_clip", clean))
            true
        } catch (_: Throwable) {
            true
        }
    }

    /** Membaca teks dari Clipboard OS Android (atau fallback in-memory pada unit test JVM). */
    fun readFromClipboard(): String {
        val ctx = context ?: return inMemoryClipboardFallback
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return inMemoryClipboardFallback
            val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val txt = item?.coerceToText(ctx)?.toString()?.trim().orEmpty()
            txt.ifEmpty { inMemoryClipboardFallback }
        } catch (_: Throwable) {
            inMemoryClipboardFallback
        }
    }

    /** Membaca waktu lokal Bahasa Indonesia, persentase baterai, jaringan, dan volume. */
    fun getDeviceAndTimeStatus(): DeviceSystemStatus {
        val now = clockProvider()
        val formattedTime = AndroidSystemToolsBridge.DATE_TIME_FORMATTER.format(now)
        val ctx = context ?: return DeviceSystemStatus(
            formattedDateTimeId = formattedTime,
            batteryPercent = 85,
            isCharging = false,
            networkType = "Wi-Fi",
            mediaVolumePercent = 70,
            ringerMode = "Normal"
        )

        var batteryPct = -1
        var charging = false
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                charging = bm.isCharging
            }
            if (batteryPct < 0) {
                val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (sticky != null) {
                    val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPct = (level * 100) / scale
                    }
                    val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        } catch (_: Throwable) {
        }

        val networkType = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data Seluler"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Terhubung"
            }
        } catch (_: Throwable) {
            "Tidak Diketahui"
        }

        var mediaVolPct = 0
        var ringerLabel = "Normal"
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                mediaVolPct = ((cur * 100) / max).coerceIn(0, 100)
                ringerLabel = when (am.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "Senyap"
                    AudioManager.RINGER_MODE_VIBRATE -> "Getar"
                    else -> "Normal"
                }
            }
        } catch (_: Throwable) {
        }

        return DeviceSystemStatus(
            formattedDateTimeId = formattedTime,
            batteryPercent = batteryPct,
            isCharging = charging,
            networkType = networkType,
            mediaVolumePercent = mediaVolPct,
            ringerMode = ringerLabel
        )
    }

    /** Mencari kontak di buku telepon Android (`ContactsContract`) berdasarkan [query]. */
    fun searchPhoneContacts(query: String, limit: Int = 5): List<PhoneContactItem> {
        val cleanQuery = query.trim()
        bridge.phoneContactsOverrideProvider?.let { override ->
            val raw = override(cleanQuery, limit)
            if (cleanQuery.isEmpty()) return raw.take(limit)
            val lowerQ = cleanQuery.lowercase(Locale.ROOT)
            return raw.filter {
                it.displayName.lowercase(Locale.ROOT).contains(lowerQ) ||
                    it.phoneNumber.contains(cleanQuery)
            }.take(limit)
        }
        val ctx = context ?: return emptyList()
        val hasPerm = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            return emptyList()
        }

        val results = mutableListOf<PhoneContactItem>()
        val seen = mutableSetOf<String>()
        val selection = if (cleanQuery.isNotEmpty()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        } else {
            null
        }
        val selectionArgs = if (cleanQuery.isNotEmpty()) {
            arrayOf("%$cleanQuery%")
        } else {
            null
        }

        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && results.size < limit) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val num = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        val key = "${name.lowercase()}|$num"
                        if (seen.add(key)) {
                            results.add(PhoneContactItem(displayName = name, phoneNumber = num))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return results
    }

    /** Mencari alamat email kontak dari buku telepon Android (`ContactsContract.CommonDataKinds.Email`). */
    fun searchEmailContacts(query: String, limit: Int = 5): List<Pair<String, String>> {
        val cleanQuery = query.trim()
        bridge.emailContactsOverrideProvider?.let { override ->
            val raw = override(cleanQuery)
            if (cleanQuery.isEmpty()) return raw.take(limit)
            val lowerQ = cleanQuery.lowercase(Locale.ROOT)
            return raw.filter { (name, email) ->
                name.lowercase(Locale.ROOT).contains(lowerQ) ||
                    email.lowercase(Locale.ROOT).contains(lowerQ)
            }.take(limit)
        }
        val ctx = context ?: return emptyList()
        val hasPerm = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return emptyList()

        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val selection = if (cleanQuery.isNotEmpty()) {
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ?"
        } else {
            null
        }
        val selectionArgs = if (cleanQuery.isNotEmpty()) {
            arrayOf("%$cleanQuery%", "%$cleanQuery%")
        } else {
            null
        }
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val emailIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext() && results.size < limit) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val email = if (emailIdx >= 0) cursor.getString(emailIdx)?.trim().orEmpty() else ""
                    if (email.contains('@') && seen.add(email.lowercase(Locale.ROOT))) {
                        results.add(name.ifEmpty { email } to email)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return results
    }

    /** Mengambil daftar notifikasi sistem terbaru yang telah disaring dari aplikasi perbankan & OTP. */
    fun getRecentNotifications(limit: Int = 6): List<CapturedNotificationEntry> {
        val liveSnapshots = try {
            bridge.activeNotificationsProvider(limit)
        } catch (_: Throwable) {
            emptyList()
        }
        if (liveSnapshots.isNotEmpty()) {
            return liveSnapshots.map { snap ->
                CapturedNotificationEntry(
                    packageName = snap.packageName,
                    summaryText = snap.displaySummary,
                    timestampMs = snap.timestampMs
                )
            }
        }
        return GepyAccessibilityService.getRecentNotifications(limit)
    }

    /** Membaca lokasi GPS terakhir yang diketahui perangkat (`LocationManager`) tanpa menguras baterai. */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): DeviceLocationSnapshot? {
        val ctx = context ?: return null
        val fineGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            return null
        }

        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var bestLocation: Location? = null
            for (provider in providers) {
                val loc = try {
                    lm.getLastKnownLocation(provider)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (bestLocation == null || loc.time > bestLocation.time) {
                    bestLocation = loc
                }
            }
            bestLocation?.let {
                DeviceLocationSnapshot(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracyMeters = it.accuracy,
                    provider = it.provider ?: "GPS"
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

}
