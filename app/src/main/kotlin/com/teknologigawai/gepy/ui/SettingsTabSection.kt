package com.teknologigawai.gepy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teknologigawai.gepy.R
import com.teknologigawai.gepy.core.auth.GepyUser
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.LlmProviderHealthTracker
import com.teknologigawai.gepy.core.config.PreferredLlmProvider
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.iot.EspCsiAmbientBridge
import com.teknologigawai.gepy.core.wellness.WellnessGuardianSnapshot
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestratorState
import com.teknologigawai.gepy.features.voice.ElevenLabsTtsClient
import com.teknologigawai.gepy.features.voice.VoiceCacheStats
import com.teknologigawai.gepy.ui.theme.GepyAmberWarning
import com.teknologigawai.gepy.ui.theme.GepyGreenAccent
import com.teknologigawai.gepy.ui.theme.GepyRedDanger
import java.util.Locale
import kotlinx.coroutines.launch

internal fun matchesSettingsSearchQuery(query: String, vararg keywords: String): Boolean {
    val clean = query.trim().lowercase(Locale.ROOT)
    if (clean.isEmpty()) return true
    val tokens = clean.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val searchable = keywords.joinToString(" ").lowercase(Locale.ROOT)
    return tokens.all { token -> searchable.contains(token) }
}

private fun shortProviderLabel(provider: PreferredLlmProvider): String = when (provider) {
    PreferredLlmProvider.AUTO -> "Otomatis"
    PreferredLlmProvider.FIREWORKS -> "Fireworks AI"
    PreferredLlmProvider.GEMINI -> "Gemini Flash"
    PreferredLlmProvider.CUSTOM_OPENAI -> "Custom / AI/ML API"
    PreferredLlmProvider.AZURE -> "Azure OpenAI"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTabContent(
    state: GepyOrchestratorState,
    wellnessSnapshot: WellnessGuardianSnapshot,
    isCsiServerRunning: Boolean,
    permissionStatus: PermissionChecklistStatus,
    currentUser: GepyUser?,
    isSigningIn: Boolean,
    voiceCacheStats: VoiceCacheStats,
    elevenLabsClient: ElevenLabsTtsClient,
    onOpenVoiceDatasetDialog: () -> Unit,
    onClearVoiceCache: () -> Unit,
    onExportVoiceVault: () -> Unit,
    onImportVoiceVault: () -> Unit,
    onSignInGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onClearCloudMemories: () -> Unit,
    onOpenByokDialog: () -> Unit,
    onOpenPlaybookDialog: () -> Unit,
    onOpenTemporalMemoryDialog: () -> Unit,
    onPlayDailyBriefing: () -> Unit,
    onUpdateByokSettings: (ByokSettings) -> Unit,
    onRequestStepPermission: () -> Unit,
    onSimulateCsiEvent: (jsonEvent: String, forceMentorNudge: Boolean) -> Unit,
    onToggleZeroHitl: (Boolean) -> Unit,
    onSelectVoicePersona: (String) -> Unit,
    onSelectNativeStt: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onRequestMicAndContext: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit = {},
    onOpenPermissionWizard: () -> Unit
) {
    var showVoicePicker by rememberSaveable { mutableStateOf(false) }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var expandVoiceCacheDetails by rememberSaveable { mutableStateOf(false) }
    var expandEspDevTools by rememberSaveable { mutableStateOf(false) }
    var healthRefreshTick by remember { mutableIntStateOf(0) }
    val settingsScope = rememberCoroutineScope()
    var elevenLabsQuotaText by remember { mutableStateOf<String?>(null) }
    var isCheckingQuota by remember { mutableStateOf(false) }

    val voiceOptions = listOf(
        "Aoede" to "Aoede (Cerah dan Luwes)",
        "Kore" to "Kore (Tenang dan Jelas)",
        "Puck" to "Puck (Cepat dan Responsif)",
        "Charon" to "Charon (Berat dan Tegas)",
        "Fenrir" to "Fenrir (Hangat dan Natural)"
    )

    val byok = state.byokSettings
    val activeCooldowns = remember(byok, healthRefreshTick, state.lastThought) {
        LlmProviderHealthTracker.activeCooldownsSnapshot()
    }
    val localLanIp = remember { EspCsiAmbientBridge.resolveDeviceLocalIpv4() }

    val showGoogleCard = matchesSettingsSearchQuery(
        settingsSearchQuery,
        "google", "akun", "login", "masuk", "keluar", "sign out", "cloud", "supabase", "pgvector", "hapus riwayat"
    )
    val showByokCard = matchesSettingsSearchQuery(
        settingsSearchQuery,
        "otak", "penyedia", "provider", "model", "ai", "kunci", "api", "byok",
        "gemini", "fireworks", "deepseek", "openrouter", "groq", "together", "402", "cooldown"
    )
    val showPersonaCard = matchesSettingsSearchQuery(
        settingsSearchQuery,
        "karakter", "persona", "gepy", "taktis", "romantis", "mentor", "standar", "nada bicara",
        "kustom", "custom", "patuh", "proaktif", "inisiatif", "otonom", "zen", "copilot", "briefing",
        byok.sovereignPersonaMode.label, byok.proactivityLevel.label
    )
    val showVoiceCard = matchesSettingsSearchQuery(
        settingsSearchQuery,
        "suara", "gepy live", "live", "elevenlabs", "eleven", "tts", "stt", "speech",
        "mikrofon", "cache", "brankas", "zip", "credit", "kuota", "dataset", "aoede", "kloning", "earcon", "haptic"
    )
    val showSafetyCard = matchesSettingsSearchQuery(
        settingsSearchQuery,
        "keselamatan", "safety", "gatekeeper", "mbanking", "m-banking", "zero-hitl",
        "hitl", "akses penuh", "izin", "sistem", "panduan", "overlay", "baterai",
        "notifikasi", "akses notifikasi", "pintasan", "tombol", "volume"
    )
    val showEcosystemCard = matchesSettingsSearchQuery(
        settingsSearchQuery,
        "anti", "doomscroll", "doomscrolling", "langkah", "pedometer", "sensor",
        "memori", "personal", "temporal", "room", "playbook", "aturan", "briefing",
        "iot", "esp32", "csi", "wifi", "lan", "privacy shield"
    )
    val anySettingsCardVisible = showGoogleCard || showByokCard || showPersonaCard ||
        showVoiceCard || showSafetyCard || showEcosystemCard

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Bilah Pencarian Pengaturan
        OutlinedTextField(
            value = settingsSearchQuery,
            onValueChange = { settingsSearchQuery = it },
            placeholder = {
                Text(
                    text = "Cari pengaturan",
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Cari pengaturan",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            },
            trailingIcon = if (settingsSearchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { settingsSearchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Hapus pencarian",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // =========================================================================
        // 1. AKUN
        // =========================================================================
        if (showGoogleCard) {
            SettingsSectionHeader("Akun")
            InsetGroupCard {
                if (currentUser == null) {
                    InsetGroupItem(
                        leadingContent = {
                            Surface(
                                shape = CircleShape,
                                color = Color.White,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_google_logo),
                                    contentDescription = "Google",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                        },
                        title = if (isSigningIn) "Menghubungkan Akun Google..." else "Masuk dengan Google",
                        subtitle = "Sinkronkan ingatan dan preferensi personal ke Cloud aman",
                        trailingContent = if (isSigningIn) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = { if (!isSigningIn) onSignInGoogle() }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(GepyGreenAccent.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = GepyGreenAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentUser.displayName,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentUser.email,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Memori Cloud Aktif (Supabase pgvector)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = GepyGreenAccent,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    InsetHairlineDivider()
                    InsetGroupItem(
                        icon = Icons.Rounded.Delete,
                        title = "Hapus Riwayat Memori Cloud",
                        subtitle = "Bersihkan fakta tersimpan di server",
                        onClick = onClearCloudMemories
                    )
                    InsetHairlineDivider()
                    InsetGroupItem(
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        title = "Keluar",
                        subtitle = "Keluar dari akun Google dan kembali ke layar masuk",
                        onClick = onSignOutGoogle
                    )
                }
            }
        }

        // =========================================================================
        // 2. MODEL AI DAN KUNCI API (1-KETUK GANTI PROVIDER)
        // =========================================================================
        if (showByokCard) {
            SettingsSectionHeader("Model AI dan kunci API")
            InsetGroupCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Penyedia AI Utama (1-Ketuk)",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pilih penyedia utama. Gepy otomatis beralih bila terjadi error/saldo habis.",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickProviders = buildList {
                            add(PreferredLlmProvider.AUTO)
                            add(PreferredLlmProvider.FIREWORKS)
                            add(PreferredLlmProvider.GEMINI)
                            add(PreferredLlmProvider.CUSTOM_OPENAI)
                            if (EnvConfig.defaultAzureEndpoint.isNotBlank() ||
                                byok.preferredProvider == PreferredLlmProvider.AZURE
                            ) {
                                add(PreferredLlmProvider.AZURE)
                            }
                        }
                        for (provider in quickProviders) {
                            val isSelected = byok.preferredProvider == provider
                            val isSuspended = activeCooldowns.any { it.provider == provider }
                            val needsCustomSetup = provider == PreferredLlmProvider.CUSTOM_OPENAI &&
                                !byok.hasCustomOpenAiCredentials
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (needsCustomSetup) {
                                        onOpenByokDialog()
                                    } else {
                                        val updated = if (provider == PreferredLlmProvider.CUSTOM_OPENAI) {
                                            byok.copy(preferredProvider = provider, customOpenAiEnabled = true)
                                        } else {
                                            byok.copy(preferredProvider = provider)
                                        }
                                        onUpdateByokSettings(updated)
                                        healthRefreshTick++
                                    }
                                },
                                label = {
                                    val suffix = when {
                                        isSuspended -> " (Jeda)"
                                        needsCustomSetup -> " (Atur)"
                                        provider == PreferredLlmProvider.CUSTOM_OPENAI && !byok.customOpenAiEnabled -> " (Nonaktif)"
                                        else -> ""
                                    }
                                    Text(
                                        text = shortProviderLabel(provider) + suffix,
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }

                    if (activeCooldowns.isNotEmpty()) {
                        Surface(
                            color = GepyAmberWarning.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, GepyAmberWarning.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = GepyAmberWarning,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    for (cd in activeCooldowns) {
                                        val mins = ((cd.remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
                                        Text(
                                            text = "${shortProviderLabel(cd.provider)}: ${cd.reason} (~${mins} mnt dilewati)",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        LlmProviderHealthTracker.resetAll()
                                        healthRefreshTick++
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Pulihkan", fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                }

                if (byok.hasCustomOpenAiCredentials) {
                    InsetHairlineDivider()
                    InsetGroupItem(
                        icon = Icons.Rounded.Bolt,
                        title = "Pakai Custom OpenAI (${byok.customOpenAiModel.ifBlank { "Custom" }})",
                        subtitle = "Matikan sementara tanpa menghapus API Key",
                        showChevron = false,
                        trailingContent = {
                            Switch(
                                checked = byok.customOpenAiEnabled,
                                onCheckedChange = { enabled ->
                                    val fallbackProvider = if (!enabled && byok.preferredProvider == PreferredLlmProvider.CUSTOM_OPENAI) {
                                        PreferredLlmProvider.AUTO
                                    } else {
                                        byok.preferredProvider
                                    }
                                    onUpdateByokSettings(
                                        byok.copy(
                                            customOpenAiEnabled = enabled,
                                            preferredProvider = fallbackProvider
                                        )
                                    )
                                }
                            )
                        },
                        onClick = {
                            val nextEnabled = !byok.customOpenAiEnabled
                            val fallbackProvider = if (!nextEnabled && byok.preferredProvider == PreferredLlmProvider.CUSTOM_OPENAI) {
                                PreferredLlmProvider.AUTO
                            } else {
                                byok.preferredProvider
                            }
                            onUpdateByokSettings(
                                byok.copy(
                                    customOpenAiEnabled = nextEnabled,
                                    preferredProvider = fallbackProvider
                                )
                            )
                        }
                    )
                }

                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Key,
                    title = "Kelola kunci API dan model",
                    subtitle = "Gemini, Fireworks, dan AI/ML API / OpenRouter",
                    valueBadge = when {
                        byok.hasGeminiApiKey && byok.hasGeminiSecondaryApiKey -> "Dual-Key"
                        byok.hasGeminiApiKey -> "Aktif"
                        else -> "Atur"
                    },
                    onClick = onOpenByokDialog
                )
            }
        }

        // =========================================================================
        // 3. KEPRIBADIAN
        // =========================================================================
        if (showPersonaCard) {
            SettingsSectionHeader("Kepribadian")
            InsetGroupCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = "Karakter Persona Gepy",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = byok.sovereignPersonaMode.shortDescription,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (mode in SovereignPersonaMode.entries) {
                            FilterChip(
                                selected = byok.sovereignPersonaMode == mode,
                                onClick = {
                                    onUpdateByokSettings(byok.copy(sovereignPersonaMode = mode))
                                },
                                label = { Text(mode.label, fontSize = 12.sp) }
                            )
                        }
                    }

                    if (byok.sovereignPersonaMode == SovereignPersonaMode.CUSTOM) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Instruksi Kepribadian Kustom:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = byok.customPersonaPrompt,
                            onValueChange = { newPrompt ->
                                onUpdateByokSettings(byok.copy(customPersonaPrompt = newPrompt))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "Misal: Panggil aku Bos, bicara santai, dan langsung eksekusi permintaanku...",
                                    fontSize = 12.sp
                                )
                            },
                            minLines = 3,
                            maxLines = 5
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateByokSettings(
                                        byok.copy(
                                            customPersonaPrompt = "Patuhi dan laksanakan semua perintah pengguna dengan setia, cekatan, dan tanpa penolakan. Gunakan nada bicara akrab, bersahabat, panggil Bos, dan selalu prioritaskan kehendak pengguna."
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Template Santai", fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    onUpdateByokSettings(
                                        byok.copy(
                                            customPersonaPrompt = "Laksanakan setiap instruksi pengguna dengan presisi tinggi, patuh mutlak, efisien, dan tanpa basa-basi atau bantahan."
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Template Presisi", fontSize = 11.5.sp)
                            }
                        }
                    }
                }

                InsetHairlineDivider()

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = "Tingkat proaktif dan inisiatif",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = byok.proactivityLevel.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (level in ProactivityLevel.entries) {
                            FilterChip(
                                selected = byok.proactivityLevel == level,
                                onClick = {
                                    onUpdateByokSettings(byok.copy(proactivityLevel = level))
                                },
                                label = { Text(level.label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 4. SUARA
        // =========================================================================
        if (showVoiceCard) {
            SettingsSectionHeader("Suara")
            InsetGroupCard {
                InsetGroupItem(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Karakter Suara Live Call",
                    subtitle = "Suara native Gemini Live dua arah",
                    valueBadge = state.liveVoicePersona,
                    onClick = { showVoicePicker = true }
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.SpatialAudioOff,
                    title = "Suara Respon (ElevenLabs)",
                    subtitle = "Untuk balasan chat, briefing, dan notifikasi",
                    valueBadge = when {
                        byok.hasElevenLabsConfigured -> "Aktif"
                        byok.hasElevenLabsVoiceProfile && voiceCacheStats.clipCount > 0 -> "Offline"
                        else -> "Android TTS"
                    },
                    onClick = onOpenByokDialog
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Mic,
                    title = "Pengenalan suara dan earcon",
                    subtitle = "STT Android (id-ID), haptic, dan earcon aktif",
                    showChevron = false,
                    onClick = {}
                )
                InsetHairlineDivider()

                val sizeMbFormatted = String.format(Locale.US, "%.1f", voiceCacheStats.totalMegabytes)
                InsetGroupItem(
                    icon = Icons.Rounded.Layers,
                    title = "Brankas Cache Suara Offline",
                    subtitle = "${voiceCacheStats.clipCount} klip • $sizeMbFormatted / ${byok.elevenLabsCacheMaxMb} MB",
                    showChevron = false,
                    trailingContent = {
                        Icon(
                            imageVector = if (expandVoiceCacheDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Kelola Cache Suara",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = { expandVoiceCacheDetails = !expandVoiceCacheDetails }
                )

                AnimatedVisibility(visible = expandVoiceCacheDetails) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val maxCacheMb = byok.elevenLabsCacheMaxMb.toFloat().coerceAtLeast(1f)
                        val cacheUsageFraction = (voiceCacheStats.totalMegabytes.toFloat() / maxCacheMb).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { cacheUsageFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = GepyGreenAccent,
                            trackColor = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${String.format(Locale.US, "%.1f", cacheUsageFraction * 100f)}% terpakai",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Status: ${elevenLabsClient.lastStatusSummary}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (elevenLabsClient.lastStatusSummary.startsWith("Fallback")) {
                                    GepyAmberWarning
                                } else {
                                    GepyGreenAccent
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenVoiceDatasetDialog,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Putar dan kelola data suara (${voiceCacheStats.clipCount} klip)", fontSize = 12.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onExportVoiceVault,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cadangkan (.zip)", fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = onImportVoiceVault,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pulihkan (.zip)", fontSize = 11.5.sp)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (!isCheckingQuota) {
                                        isCheckingQuota = true
                                        settingsScope.launch {
                                            val res = elevenLabsClient.fetchSubscriptionQuota(
                                                byok.effectiveElevenLabsApiKey
                                            )
                                            elevenLabsQuotaText = res.fold(
                                                onSuccess = { info ->
                                                    "Sisa Credit: ${info.remainingCredits} / ${info.characterLimit} karakter (${info.tier})"
                                                },
                                                onFailure = { err ->
                                                    "Gagal cek credit: ${err.message ?: "Periksa API Key / internet"}"
                                                }
                                            )
                                            isCheckingQuota = false
                                        }
                                    }
                                },
                                enabled = !isCheckingQuota && byok.effectiveElevenLabsApiKey.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isCheckingQuota) "Mengecek..." else "Cek Sisa Credit",
                                    fontSize = 11.5.sp
                                )
                            }
                            TextButton(
                                onClick = onClearVoiceCache,
                                enabled = voiceCacheStats.clipCount > 0,
                                colors = ButtonDefaults.textButtonColors(contentColor = GepyRedDanger)
                            ) {
                                Text("Bersihkan Cache", fontSize = 11.5.sp)
                            }
                        }
                        elevenLabsQuotaText?.let { quotaStatus ->
                            Text(
                                text = quotaStatus,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (quotaStatus.startsWith("Sisa Credit")) GepyGreenAccent else GepyRedDanger
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 5. KESELAMATAN DAN IZIN
        // =========================================================================
        if (showSafetyCard) {
            SettingsSectionHeader("Keselamatan dan izin")
            InsetGroupCard {
                InsetGroupItem(
                    icon = Icons.Rounded.VerifiedUser,
                    title = "Pelindung Finansial (Gatekeeper)",
                    subtitle = "Aplikasi perbankan, PIN, dan OTP selalu terisolasi",
                    valueBadge = "Aktif",
                    showChevron = false,
                    onClick = {}
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Bolt,
                    title = "Mode Akses Penuh (Zero-HITL)",
                    subtitle = "Lewati konfirmasi untuk aksi non-finansial",
                    showChevron = false,
                    trailingContent = {
                        Switch(
                            checked = state.fullAccessZeroHitl,
                            onCheckedChange = onToggleZeroHitl
                        )
                    },
                    onClick = { onToggleZeroHitl(!state.fullAccessZeroHitl) }
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Tune,
                    title = "Izin Sistem Android",
                    subtitle = "Aksesibilitas, Overlay HUD, Mikrofon, dan Baterai",
                    valueBadge = if (permissionStatus.allRequiredReady) "Lengkap" else "Perlu Izin",
                    onClick = onOpenPermissionWizard
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Notifications,
                    title = "Akses notifikasi dan media",
                    subtitle = "Baca notifikasi dan balas pesan otomatis",
                    valueBadge = if (permissionStatus.notificationListenerGranted) "Aktif" else "Belum Aktif",
                    onClick = onOpenNotificationListenerSettings
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.AccessibilityNew,
                    title = "Pintasan Tombol Fisik",
                    subtitle = "Tahan tombol Volume +/- atau pintasan Aksesibilitas",
                    showChevron = false,
                    onClick = {}
                )
            }
        }

        // =========================================================================
        // 6. MEMORI, JADWAL, DAN PERANGKAT
        // =========================================================================
        if (showEcosystemCard) {
            SettingsSectionHeader("Memori, jadwal, dan perangkat")
            InsetGroupCard {
                InsetGroupItem(
                    icon = Icons.Rounded.Psychology,
                    title = "Memori Personal Temporal",
                    subtitle = "Fakta diri, relasi, dan pencadangan JSON",
                    onClick = onOpenTemporalMemoryDialog
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Layers,
                    title = "Aturan dan playbook",
                    subtitle = "Kelola alur otomatisasi lintas aplikasi",
                    onClick = onOpenPlaybookDialog
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.CalendarMonth,
                    title = "Putar Briefing Hari Ini",
                    subtitle = "Rangkuman agenda, langkah, dan baterai nyata",
                    onClick = onPlayDailyBriefing
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Visibility,
                    title = "Penjaga Anti-Doomscrolling",
                    subtitle = if (byok.antiDoomscrollEnabled) {
                        "Aktif • Batas ${byok.doomscrollLimitMinutes} mnt IG/TikTok/YouTube"
                    } else {
                        "Nonaktif"
                    },
                    showChevron = false,
                    trailingContent = {
                        Switch(
                            checked = byok.antiDoomscrollEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateByokSettings(byok.copy(antiDoomscrollEnabled = enabled))
                            }
                        )
                    },
                    onClick = {
                        onUpdateByokSettings(byok.copy(antiDoomscrollEnabled = !byok.antiDoomscrollEnabled))
                    }
                )
                if (byok.antiDoomscrollEnabled) {
                    InsetHairlineDivider()
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (mins in listOf(10, 15, 30, 45)) {
                                FilterChip(
                                    selected = byok.doomscrollLimitMinutes == mins,
                                    onClick = {
                                        onUpdateByokSettings(byok.copy(doomscrollLimitMinutes = mins))
                                    },
                                    label = { Text("$mins mnt", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    InsetHairlineDivider()
                    InsetGroupItem(
                        icon = Icons.Rounded.Bolt,
                        title = "Auto-Home Hard Lock",
                        subtitle = "Otomatis kembali ke Beranda saat batas terlewati",
                        showChevron = false,
                        trailingContent = {
                            Switch(
                                checked = byok.antiDoomscrollAutoHome,
                                onCheckedChange = { hardLock ->
                                    onUpdateByokSettings(byok.copy(antiDoomscrollAutoHome = hardLock))
                                }
                            )
                        },
                        onClick = {
                            onUpdateByokSettings(byok.copy(antiDoomscrollAutoHome = !byok.antiDoomscrollAutoHome))
                        }
                    )
                }
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.Analytics,
                    title = "Sensor Langkah Kaki Harian",
                    subtitle = if (wellnessSnapshot.stepSensorActive) "Pedometer perangkat keras aktif" else "Ketuk untuk mengaktifkan izin aktivitas fisik",
                    valueBadge = if (wellnessSnapshot.stepSensorActive) "${wellnessSnapshot.todaySteps} langkah" else "Aktifkan",
                    showChevron = !wellnessSnapshot.stepSensorActive,
                    onClick = {
                        if (!wellnessSnapshot.stepSensorActive) {
                            onRequestStepPermission()
                        }
                    }
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    icon = Icons.Rounded.FlashlightOn,
                    title = "Jembatan LAN ESP32-S3 CSI (:${byok.espCsiPort})",
                    subtitle = if (isCsiServerRunning) "Server aktif di http://$localLanIp:${byok.espCsiPort}" else "Opsional (butuh modul ESP32-S3)",
                    showChevron = false,
                    trailingContent = {
                        Switch(
                            checked = byok.espCsiBridgeEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateByokSettings(byok.copy(espCsiBridgeEnabled = enabled))
                            }
                        )
                    },
                    onClick = {
                        onUpdateByokSettings(byok.copy(espCsiBridgeEnabled = !byok.espCsiBridgeEnabled))
                    }
                )
                InsetHairlineDivider()
                InsetGroupItem(
                    title = "Alat Pengembang ESP32",
                    subtitle = if (wellnessSnapshot.isGuestPrivacyShieldActive) "Privacy Shield AKTIF (Mode Tamu)" else "Simulasi event payload LAN",
                    showChevron = false,
                    trailingContent = {
                        Icon(
                            imageVector = if (expandEspDevTools) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Alat Pengembang",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = { expandEspDevTools = !expandEspDevTools }
                )
                AnimatedVisibility(visible = expandEspDevTools) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Endpoint: http://$localLanIp:${byok.espCsiPort}/csi-event",
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onSimulateCsiEvent(
                                        """{"event":"AIR_GESTURE_WAKE","room":"kamar","biometric_owner_match":true}""",
                                        false
                                    )
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Air-Gesture Mic", fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    onSimulateCsiEvent(
                                        """{"event":"SEDENTARY_ALERT","room":"kamar","duration_minutes":185,"biometric_owner_match":true,"respiration_bpm":15}""",
                                        true
                                    )
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Sim. Diam 3 Jam", fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val nextOwnerMatch = wellnessSnapshot.isGuestPrivacyShieldActive
                                    onSimulateCsiEvent(
                                        """{"event":"GAIT_BIOMETRIC","room":"kamar","biometric_owner_match":$nextOwnerMatch}""",
                                        false
                                    )
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    if (wellnessSnapshot.isGuestPrivacyShieldActive) "Reset Pemilik" else "Mode Tamu",
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!anySettingsCardVisible) {
            InsetGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Pengaturan \"$settingsSearchQuery\" tidak ditemukan.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Coba kata kunci lain seperti provider, BYOK, suara, memori, atau izin.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { settingsSearchQuery = "" }) {
                        Text("Reset Pencarian", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }

    // Modal Pilihan Karakter Suara Live
    if (showVoicePicker) {
        AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = {
                Text(
                    text = "Pilih Karakter Suara Live",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((voiceId, label) in voiceOptions) {
                        val isSelected = state.liveVoicePersona == voiceId
                        Surface(
                            onClick = {
                                onSelectVoicePersona(voiceId)
                                showVoicePicker = false
                            },
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = label,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoicePicker = false }) {
                    Text("Tutup", fontWeight = FontWeight.SemiBold)
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.ROOT),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp)
    )
}

@Composable
private fun InsetGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
private fun InsetGroupItem(
    icon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    title: String,
    subtitle: String? = null,
    valueBadge: String? = null,
    showChevron: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(modifier = Modifier.width(12.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
        }
        if (!valueBadge.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = valueBadge,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        } else if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun InsetHairlineDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        thickness = 0.8.dp,
        modifier = Modifier.padding(start = 16.dp)
    )
}
