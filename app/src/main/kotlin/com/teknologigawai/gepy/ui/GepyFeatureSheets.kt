package com.teknologigawai.gepy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge
import com.teknologigawai.gepy.core.skills.GepySkillPlugin
import com.teknologigawai.gepy.core.wellness.WellnessGuardianSnapshot
import com.teknologigawai.gepy.ui.theme.CyberpunkTheme
import com.teknologigawai.gepy.ui.theme.GepyAmberWarning
import com.teknologigawai.gepy.ui.theme.GepyBlueAccent
import com.teknologigawai.gepy.ui.theme.GepyGreenAccent
import com.teknologigawai.gepy.ui.theme.GepyRedDanger

internal const val QUICK_ACTION_LIVE_CALL_ID = "live_call"

/** Opsi tugas cepat untuk Hero Chips dan ModalBottomSheet "Pilih tugas". */
data class QuickActionOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val promptText: String,
    val isImmediateExecute: Boolean,
    val icon: ImageVector
)

internal val HERO_QUICK_PILLS = listOf(
    QuickActionOption(
        id = "gojek",
        title = "Pesan Gojek",
        subtitle = "Otomasi aplikasi dengan konfirmasi aman",
        promptText = "Pesankan gojek ke ",
        isImmediateExecute = false,
        icon = Icons.Rounded.TwoWheeler
    ),
    QuickActionOption(
        id = "whatsapp",
        title = "Kirim WhatsApp",
        subtitle = "Isi nama kontak dan pesan",
        promptText = "Kirim pesan WhatsApp ke ",
        isImmediateExecute = false,
        icon = Icons.AutoMirrored.Rounded.Chat
    ),
    QuickActionOption(
        id = QUICK_ACTION_LIVE_CALL_ID,
        title = "Panggilan Live",
        subtitle = "Bicara dua arah real-time bersama Gepy",
        promptText = "",
        isImmediateExecute = true,
        icon = Icons.Rounded.GraphicEq
    ),
    QuickActionOption(
        id = "web_search",
        title = "Riset web",
        subtitle = "Cari info terkini dan ringkas lintas situs",
        promptText = "Carikan info terbaru tentang ",
        isImmediateExecute = false,
        icon = Icons.Rounded.TravelExplore
    )
)

internal val SHEET_QUICK_ACTIONS = listOf(
    QuickActionOption(
        id = "wa",
        title = "Kirim pesan WhatsApp ke kontak",
        subtitle = "Siapkan perintah agar Anda melengkapi kontak dan isi pesan",
        promptText = "Kirim pesan WhatsApp ke ",
        isImmediateExecute = false,
        icon = Icons.AutoMirrored.Rounded.Chat
    ),
    QuickActionOption(
        id = "calendar",
        title = "Jadwalkan agenda di Kalender",
        subtitle = "Siapkan pengingat atau jadwal rapat",
        promptText = "Jadwalkan agenda besok jam ",
        isImmediateExecute = false,
        icon = Icons.Rounded.CalendarMonth
    ),
    QuickActionOption(
        id = "screen_read",
        title = "Baca dan rangkum teks di layar",
        subtitle = "Langsung memindai dan membacakan konten layar aktif",
        promptText = "Baca dan rangkum isi layar sekarang",
        isImmediateExecute = true,
        icon = Icons.Rounded.Visibility
    ),
    QuickActionOption(
        id = "flashlight",
        title = "Nyalakan senter HP",
        subtitle = "Zero-UI < 5 ms",
        promptText = "Nyalakan senter",
        isImmediateExecute = true,
        icon = Icons.Rounded.FlashlightOn
    ),
    QuickActionOption(
        id = "battery_status",
        title = "Cek status baterai dan waktu",
        subtitle = "Alat Sistem Android",
        promptText = "Cek status baterai dan waktu sekarang",
        isImmediateExecute = true,
        icon = Icons.Rounded.Bolt
    ),
    QuickActionOption(
        id = "wifi",
        title = "Buka panel Wi-Fi dan jaringan",
        subtitle = "Menampilkan panel pengaturan Wi-Fi cepat",
        promptText = "Buka pengaturan wifi",
        isImmediateExecute = true,
        icon = Icons.Rounded.Wifi
    ),
    QuickActionOption(
        id = "spotify",
        title = "Putar musik di Spotify",
        subtitle = "Siapkan judul lagu atau artis yang ingin diputar",
        promptText = "Putar lagu ",
        isImmediateExecute = false,
        icon = Icons.Rounded.MusicNote
    ),
    QuickActionOption(
        id = "laptop_status",
        title = "Cek status laptop jarak jauh",
        subtitle = "Memeriksa baterai dan jendela aktif laptop via Tailscale",
        promptText = "Cek status laptop",
        isImmediateExecute = true,
        icon = Icons.Rounded.Devices
    )
)

/** Mengubah ID skill internal (`android_system_tools`) menjadi judul rapi untuk UI. */
internal fun formatSkillDisplayTitle(skillId: String): String {
    return when (skillId.trim().lowercase()) {
        "android_system_tools" -> "Alat Sistem OS Android"
        "laptop_companion" -> "Pendamping Laptop (Tailscale dan A2A)"
        "spotify_fast_path" -> "Kontrol Musik Spotify"
        "screen_reader_summary" -> "Pembaca dan Perangkum Layar"
        "whatsapp_messaging" -> "Pesan Cepat WhatsApp"
        "instagram_social" -> "Navigasi Sosial Instagram"
        "calendar_scheduler" -> "Penjadwal Kalender dan Agenda"
        "universal_app_navigation" -> "Navigasi Universal Lintas Aplikasi"
        else -> skillId.replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }
}

internal fun skillUsageExamplePrompt(skillId: String): String {
    return when (skillId.trim().lowercase()) {
        "android_system_tools" -> "Cek status baterai dan waktu sekarang"
        "laptop_companion" -> "Cek status laptop"
        "spotify_fast_path" -> "Putar lagu "
        "screen_reader_summary" -> "Baca dan rangkum isi layar sekarang"
        "whatsapp_messaging" -> "Kirim pesan WhatsApp ke "
        "instagram_social" -> "Buka Instagram"
        "calendar_scheduler" -> "Jadwalkan agenda besok jam "
        else -> "Bantu saya membuka "
    }
}

/**
 * Sheet Material 3 bersih untuk tombol "Pilih tugas" dan tombol (+) pada komposer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickActionsModalSheet(
    onDismiss: () -> Unit,
    onSelectOption: (QuickActionOption) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Pilih Tugas Cepat",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Aksi tanpa parameter berjalan seketika; tugas berparameter akan mengisi kolom perintah untuk Anda lengkapi.",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            for (option in SHEET_QUICK_ACTIONS) {
                Surface(
                    onClick = { onSelectOption(option) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = option.subtitle,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (option.isImmediateExecute) {
                                GepyGreenAccent.copy(alpha = 0.14f)
                            } else {
                                GepyBlueAccent.copy(alpha = 0.12f)
                            }
                        ) {
                            Text(
                                text = if (option.isImmediateExecute) "Langsung" else "Isi",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (option.isImmediateExecute) GepyGreenAccent else GepyBlueAccent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sheet Kendali Jarak Jauh & IoT untuk Laptop Companion Bridge & ESP32-S3 CSI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteAndIotSheet(
    byok: ByokSettings,
    wellnessSnapshot: WellnessGuardianSnapshot,
    isCsiServerRunning: Boolean,
    onUpdateByokSettings: (ByokSettings) -> Unit,
    onExecuteQuickGoal: (String) -> Unit,
    onSimulateCsiEvent: (jsonEvent: String, forceMentorNudge: Boolean) -> Unit,
    onOpenFullSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val localLanIp = EspCsiAmbientBridge.resolveDeviceBestIpv4()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Devices,
                    contentDescription = null,
                    tint = GepyBlueAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Kendali jarak jauh dan IoT",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Bagian 1: Laptop Windows Companion (Tailscale)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Laptop Companion (Tailscale)",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Host: ${byok.laptopTailscaleHost.ifBlank { "Belum diatur (isi di Pengaturan)" }}",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = byok.laptopCompanionEnabled,
                            onCheckedChange = {
                                onUpdateByokSettings(byok.copy(laptopCompanionEnabled = it))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onExecuteQuickGoal("Cek status laptop")
                            },
                            enabled = byok.laptopCompanionEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cek Status", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onExecuteQuickGoal("Kunci layar laptop")
                            },
                            enabled = byok.laptopCompanionEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kunci PC", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Bagian 2: Jembatan LAN ESP32-S3 CSI
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Jembatan LAN ESP32-S3 CSI (:${byok.espCsiPort})",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "http://$localLanIp:${byok.espCsiPort}/csi-event",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = byok.espCsiBridgeEnabled,
                            onCheckedChange = {
                                onUpdateByokSettings(byok.copy(espCsiBridgeEnabled = it))
                            }
                        )
                    }

                    val shieldText = if (wellnessSnapshot.isGuestPrivacyShieldActive) {
                        "Privacy Shield AKTIF (Tamu)"
                    } else {
                        "Pemilik Sah Terverifikasi"
                    }
                    Text(
                        text = "Server: ${if (isCsiServerRunning) "Mendengarkan" else "Siaga"} • $shieldText",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (wellnessSnapshot.isGuestPrivacyShieldActive) GepyRedDanger else GepyGreenAccent
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val nextOwnerMatch = wellnessSnapshot.isGuestPrivacyShieldActive
                                onSimulateCsiEvent(
                                    """{"event":"GAIT_BIOMETRIC","room":"kamar","biometric_owner_match":$nextOwnerMatch}""",
                                    false
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (wellnessSnapshot.isGuestPrivacyShieldActive) "Reset Pemilik" else "Sim. Tamu",
                                fontSize = 11.5.sp
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenFullSettings()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Detail di Pengaturan", fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sheet Terjadwal, Tingkat Proaktif, Guardian Kebiasaan, dan Daily Briefing.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProactiveGuardianSheet(
    byok: ByokSettings,
    wellnessSnapshot: WellnessGuardianSnapshot,
    onUpdateByokSettings: (ByokSettings) -> Unit,
    onPlayDailyBriefing: () -> Unit,
    onPrefillPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = GepyAmberWarning,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Terjadwal dan Guardian",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Metrik aktivitas & medsos hari ini
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Pemantauan Hari Ini (On-Device)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Langkah: ${wellnessSnapshot.todaySteps} • Medsos: ${wellnessSnapshot.continuousSocialScrollMinutes}/${byok.doomscrollLimitMinutes} mnt kontinu",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pengingat Anti-Doomscrolling",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = byok.antiDoomscrollEnabled,
                            onCheckedChange = {
                                onUpdateByokSettings(byok.copy(antiDoomscrollEnabled = it))
                            }
                        )
                    }
                }
            }

            // Tingkat Proaktif
            Text(
                text = "Tingkat Inisiatif Proaktif",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (level in ProactivityLevel.entries) {
                    FilterChip(
                        selected = byok.proactivityLevel == level,
                        onClick = { onUpdateByokSettings(byok.copy(proactivityLevel = level)) },
                        label = { Text(level.label, fontSize = 12.sp) }
                    )
                }
            }
            Text(
                text = byok.proactivityLevel.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onDismiss()
                        onPlayDailyBriefing()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GepyBlueAccent),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Putar Briefing", fontSize = 12.5.sp)
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onPrefillPrompt("Jadwalkan pengingat ")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Buat Pengingat", fontSize = 12.5.sp)
                }
            }
        }
    }
}

/**
 * Sheet Katalog Skill & Alat OS yang dibaca langsung dari [SkillRegistry].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsCatalogSheet(
    skills: List<GepySkillPlugin>,
    onPrefillSkillPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = GepyGreenAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Skill dan alat (${skills.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Plugin deterministik aktif di dalam Gepy. Ketuk salah satu skill untuk menyiapkan contoh perintah di kolom input.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            for (skill in skills) {
                val pkgInfo = if (skill.supportedPackages.isEmpty()) {
                    "Zero-UI / Lintas Sistem"
                } else {
                    "${skill.supportedPackages.size} paket didukung"
                }
                Surface(
                    onClick = {
                        onDismiss()
                        onPrefillSkillPrompt(skillUsageExamplePrompt(skill.skillId))
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatSkillDisplayTitle(skill.skillId),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${skill.skillId} • $pkgInfo",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pakai",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GepyBlueAccent
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickActionsSheetPreview() {
    CyberpunkTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in HERO_QUICK_PILLS) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(option.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(option.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
