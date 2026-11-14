package com.teknologigawai.gepy.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.teknologigawai.gepy.core.system.SamsungBatteryOptimizationGuard
import com.teknologigawai.gepy.service.GepyAccessibilityService
import com.teknologigawai.gepy.service.GepyNotificationListenerService
import com.teknologigawai.gepy.ui.theme.GepyAmberWarning
import com.teknologigawai.gepy.ui.theme.GepyGreenAccent

/** Model status 4 izin utama OS Android (+ 1 akses opsional notifikasi/media) yang dibutuhkan Gepy Native. */
data class PermissionChecklistStatus(
    val microphoneGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val notificationListenerGranted: Boolean = false
) {
    val completedCount: Int
        get() = listOf(
            microphoneGranted,
            overlayGranted,
            accessibilityEnabled,
            batteryUnrestricted
        ).count { it }

    val allRequiredReady: Boolean
        get() = microphoneGranted && overlayGranted && accessibilityEnabled
}

/** Memeriksa status ke-4 izin sistem utama & 1 akses notifikasi opsional Android secara langsung. */
fun checkPermissionChecklist(context: Context): PermissionChecklistStatus {
    val micOk = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val overlayOk = Settings.canDrawOverlays(context)
    val a11yOk = GepyAccessibilityService.isServiceEnabled(context)
    val batteryOk = SamsungBatteryOptimizationGuard.isIgnoringBatteryOptimizations(context)
    val notifListenerOk = GepyNotificationListenerService.isServiceEnabled(context)

    return PermissionChecklistStatus(
        microphoneGranted = micOk,
        overlayGranted = overlayOk,
        accessibilityEnabled = a11yOk,
        batteryUnrestricted = batteryOk,
        notificationListenerGranted = notifListenerOk
    )
}

fun openAppInfoSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}

fun openOverlayPermissionSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}

fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}

fun openNotificationListenerSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        openAppInfoSettings(context)
    }
}

@SuppressLint("BatteryLife")
fun openBatteryOptimizationSettings(context: Context) {
    SamsungBatteryOptimizationGuard.requestUnrestrictedBattery(context)
}

/**
 * Layar **Wizard Izin Bertahap (4 Langkah)** untuk memandu pengguna mengaktifkan:
 * 1. Mikrofon, Notifikasi & Akses Konteks OS (`RECORD_AUDIO`, `READ_CONTACTS`, `LOCATION`)
 * 2. Jendela Mengambang (`SYSTEM_ALERT_WINDOW` / Overlay HUD)
 * 3. Layanan Aksesibilitas (`GepyAccessibilityService` + panduan *Allow Restricted Settings* Android 13–15)
 * 4. Pengecualian Optimasi Baterai (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
 */
@Composable
fun PermissionWizardScreen(
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(checkPermissionChecklist(context)) }

    fun refreshStatus() {
        val updated = checkPermissionChecklist(context)
        status = updated
        if (updated.allRequiredReady) {
            onCompleted()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStatus()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                text = "PENYIAPAN AWAL • ${status.completedCount} DARI 4 AKTIF",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Siapkan akses Gepy",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Aktifkan izin berikut satu per satu biar Gepy bisa baca dan bantu operasikan layarmu dengan aman.",
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { status.completedCount / 4f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PermissionStepCard(
                    stepNumber = 1,
                    title = "Mikrofon, Kontak, dan Konteks OS",
                    subtitle = "Diperlukan untuk perintah suara Bahasa Indonesia (id-ID), pencarian kontak cepat, dan lokasi.",
                    icon = Icons.Rounded.Mic,
                    isGranted = status.microphoneGranted,
                    actionLabel = "Izinkan Mikrofon",
                    onAction = {
                        val perms = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            add(Manifest.permission.READ_CONTACTS)
                            add(Manifest.permission.READ_CALENDAR)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        runtimePermissionLauncher.launch(perms.toTypedArray())
                    }
                )

                PermissionStepCard(
                    stepNumber = 2,
                    title = "Jendela Mengambang (Overlay HUD)",
                    subtitle = "Menampilkan kapsul mikrofon dan modal persetujuan di atas aplikasi lain.",
                    icon = Icons.Rounded.Layers,
                    isGranted = status.overlayGranted,
                    actionLabel = "Buka Pengaturan Overlay",
                    onAction = { openOverlayPermissionSettings(context) }
                )

                PermissionStepCard(
                    stepNumber = 3,
                    title = "Layanan Aksesibilitas Gepy",
                    subtitle = "Mata dan tangan Gepy untuk membaca pohon UI dan mengetuk tombol secara deterministik.",
                    icon = Icons.Rounded.AccessibilityNew,
                    isGranted = status.accessibilityEnabled,
                    actionLabel = "Buka Menu Aksesibilitas",
                    onAction = { openAccessibilitySettings(context) },
                    helperNote = "Pada Android 13–15: jika menu Aksesibilitas terkunci ('Restricted Setting'), buka Info Aplikasi -> ketuk ikon (⋮) di kanan atas -> pilih 'Izinkan pengaturan terbatas'.",
                    helperActionLabel = "Buka Info Aplikasi (Allow Restricted Settings)",
                    onHelperAction = { openAppInfoSettings(context) }
                )

                PermissionStepCard(
                    stepNumber = 4,
                    title = "Baterai Tanpa Batasan (Anti-Mati Latar Belakang)",
                    subtitle = SamsungBatteryOptimizationGuard.getBatterySurvivalGuideText(),
                    icon = Icons.Rounded.BatteryChargingFull,
                    isGranted = status.batteryUnrestricted,
                    actionLabel = "Aktifkan Mode Tidak Dibatasi",
                    onAction = { openBatteryOptimizationSettings(context) },
                    helperNote = if (SamsungBatteryOptimizationGuard.isSamsungDevice()) {
                        "Khusus Samsung One UI: pastikan pengaturan Baterai Gepy berstatus 'Tidak dibatasi' (Unrestricted) agar tidak dibekukan oleh Samsung Device Care saat layar mati."
                    } else null,
                    helperActionLabel = if (SamsungBatteryOptimizationGuard.isSamsungDevice()) {
                        "Buka Pengaturan Baterai Samsung"
                    } else null,
                    onHelperAction = if (SamsungBatteryOptimizationGuard.isSamsungDevice()) {
                        { SamsungBatteryOptimizationGuard.openSamsungDeviceCareBatterySettings(context) }
                    } else null
                )

                PermissionStepCard(
                    stepNumber = 5,
                    isOptional = true,
                    title = "Akses Notifikasi dan Media (Opsional)",
                    subtitle = "Agar Gepy bisa baca dan balas chat langsung di latar belakang (RemoteInput), hapus notifikasi, serta cek judul lagu yang diputar tanpa buka aplikasi.",
                    icon = Icons.Rounded.Notifications,
                    isGranted = status.notificationListenerGranted,
                    actionLabel = "Buka Akses Notifikasi",
                    onAction = { openNotificationListenerSettings(context) },
                    helperNote = "Pada Android 13–15: jika sakelar Akses Notifikasi terkunci ('Restricted Setting'), buka Info Aplikasi -> ketuk ikon (⋮) di kanan atas -> pilih 'Izinkan pengaturan terbatas'.",
                    helperActionLabel = "Buka Info Aplikasi (Allow Restricted Settings)",
                    onHelperAction = { openAppInfoSettings(context) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { refreshStatus() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Periksa Status", fontSize = 13.sp)
                }
                Button(
                    onClick = onCompleted,
                    modifier = Modifier.weight(1.3f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (status.allRequiredReady) "Mulai Gepy" else "Lewati Dulu",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStepCard(
    stepNumber: Int,
    isOptional: Boolean = false,
    title: String,
    subtitle: String,
    icon: ImageVector,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    helperNote: String? = null,
    helperActionLabel: String? = null,
    onHelperAction: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isGranted) GepyGreenAccent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGranted) {
                                GepyGreenAccent.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isGranted) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = GepyGreenAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isOptional) "Opsional. $title" else "$stepNumber. $title",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = when {
                                isGranted -> GepyGreenAccent.copy(alpha = 0.14f)
                                isOptional -> MaterialTheme.colorScheme.surfaceVariant
                                else -> GepyAmberWarning.copy(alpha = 0.14f)
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = when {
                                    isGranted -> "Aktif"
                                    isOptional -> "Opsional"
                                    else -> "Perlu Izin"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isGranted -> GepyGreenAccent
                                    isOptional -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> GepyAmberWarning
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }

            if (!isGranted && !helperNote.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = helperNote,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                        if (helperActionLabel != null && onHelperAction != null) {
                            TextButton(
                                onClick = onHelperAction,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = helperActionLabel,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            if (!isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onAction,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
