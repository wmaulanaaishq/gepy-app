package com.teknologigawai.gepy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.teknologigawai.gepy.core.memory.ChatTurnItem
import com.teknologigawai.gepy.core.memory.ConversationChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Calendar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teknologigawai.gepy.R
import com.teknologigawai.gepy.core.config.SovereignPersonaMode
import com.teknologigawai.gepy.core.persona.SovereignPersonaEngine
import com.teknologigawai.gepy.core.wellness.WellnessGuardianSnapshot
import com.teknologigawai.gepy.features.orchestrator.AgentProcessStep
import com.teknologigawai.gepy.features.orchestrator.GepyFsmState
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestratorState
import com.teknologigawai.gepy.features.orchestrator.ProcessStepState
import com.teknologigawai.gepy.ui.theme.GepyAmberWarning
import com.teknologigawai.gepy.ui.theme.GepyBlueAccent
import com.teknologigawai.gepy.ui.theme.GepyBluePillBg
import com.teknologigawai.gepy.ui.theme.GepyGreenAccent
import com.teknologigawai.gepy.ui.theme.GepyRedDanger
import java.util.Locale

@Composable
internal fun GepyBrandEmblem(
    size: Dp = 88.dp,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "Logo Resmi Gepy",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .graphicsLayer {
                    scaleX = 1.2f
                    scaleY = 1.2f
                }
        )
    }
}

@Composable
internal fun TopHeaderBar(
    state: GepyOrchestratorState,
    activeSection: GepyMainSection,
    permissionStatus: PermissionChecklistStatus,
    onOpenDrawer: () -> Unit,
    onNavigateBack: () -> Unit,
    onToggleOverlay: () -> Unit,
    onOpenPermissionWizard: () -> Unit,
    onStartNewChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Kiri: Tombol Bulat Buka Drawer (=) atau Kembali (<-)
        Surface(
            onClick = if (activeSection == GepyMainSection.ASSISTANT) onOpenDrawer else onNavigateBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (activeSection == GepyMainSection.ASSISTANT) {
                        Icons.Rounded.Menu
                    } else {
                        Icons.AutoMirrored.Rounded.ArrowBack
                    },
                    contentDescription = if (activeSection == GepyMainSection.ASSISTANT) {
                        "Buka menu navigasi"
                    } else {
                        "Kembali ke Asisten"
                    },
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (activeSection == GepyMainSection.ASSISTANT) {
            // Pil Status / Aksi Cepat di sebelah tombol Menu (mirip pil "+ Tingkatkan" di referensi)
            when {
                state.isLiveCallActive -> {
                    Surface(
                        color = GepyGreenAccent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, GepyGreenAccent.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Panggilan Langsung Aktif",
                                tint = GepyGreenAccent,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Live Aktif",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = GepyGreenAccent
                            )
                        }
                    }
                }

                !permissionStatus.allRequiredReady -> {
                    Surface(
                        onClick = onOpenPermissionWizard,
                        color = GepyAmberWarning.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, GepyAmberWarning.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccessibilityNew,
                                contentDescription = null,
                                tint = GepyAmberWarning,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Lengkapi Izin",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                else -> {
                    val isHudOn = state.overlayActive
                    Surface(
                        onClick = onToggleOverlay,
                        color = if (isHudOn) MaterialTheme.colorScheme.primary else GepyBluePillBg,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isHudOn) MaterialTheme.colorScheme.primary else GepyBlueAccent.copy(alpha = 0.25f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isHudOn) "Overlay Aktif" else "Aktifkan HUD",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isHudOn) MaterialTheme.colorScheme.onPrimary else GepyBlueAccent
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Kanan: Tombol Bulat Obrolan Baru / Refresh Sesi
            Surface(
                onClick = onStartNewChat,
                enabled = !state.isExecutingGoal,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = "Obrolan Baru",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            // Judul bersih untuk sub-layar Telemetri & Pengaturan
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeSection.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onOpenDrawer,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "Buka menu navigasi",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun AssistantTabContent(
    state: GepyOrchestratorState,
    wellnessSnapshot: WellnessGuardianSnapshot,
    permissionStatus: PermissionChecklistStatus,
    ownerName: String = "",
    promptText: String,
    onPromptTextChange: (String) -> Unit,
    onPrefillPrompt: (String) -> Unit,
    onSelectQuickAction: (QuickActionOption) -> Unit,
    onSubmitPrompt: () -> Unit,
    onMicTrigger: () -> Unit,
    onQuickGoal: (String) -> Unit,
    onCancelGoal: () -> Unit,
    onClearSession: () -> Unit,
    onRollbackSession: (String) -> Unit,
    onToggleLiveCall: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    DeveloperAssistantContent(
        state = state,
        wellnessSnapshot = wellnessSnapshot,
        permissionStatus = permissionStatus,
        ownerName = ownerName,
        promptText = promptText,
        onPromptTextChange = onPromptTextChange,
        onPrefillPrompt = onPrefillPrompt,
        onSelectQuickAction = onSelectQuickAction,
        onSubmitPrompt = onSubmitPrompt,
        onMicTrigger = onMicTrigger,
        onQuickGoal = onQuickGoal,
        onCancelGoal = onCancelGoal,
        onClearSession = onClearSession,
        onRollbackSession = onRollbackSession,
        onToggleLiveCall = onToggleLiveCall,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings
    )
}





/**
 * Antarmuka Asisten Utama Gepy Native — Clean White Assistant Canvas
 * Didesain ulang merujuk ChatGPT Android UI (Screenshot 2) & panduan Material Design 3:
 * - Kanvas lapang putih bersih (#FFFFFF) bebas distraksi.
 * - Progressive disclosure: Rincian teknis disembunyikan dalam akordeon dan tidak membanjiri layar.
 * - Aksi cepat 2 baris minimal di atas komposer.
 * - Floating Pill Composer terpadu dengan tombol aksi (+), input teks, mikrofon, dan tombol lingkaran Gepy Live.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeveloperAssistantContent(
    state: GepyOrchestratorState,
    wellnessSnapshot: WellnessGuardianSnapshot,
    permissionStatus: PermissionChecklistStatus,
    ownerName: String = "",
    promptText: String,
    onPromptTextChange: (String) -> Unit,
    onPrefillPrompt: (String) -> Unit,
    onSelectQuickAction: (QuickActionOption) -> Unit,
    onSubmitPrompt: () -> Unit,
    onMicTrigger: () -> Unit,
    onQuickGoal: (String) -> Unit,
    onCancelGoal: () -> Unit,
    onClearSession: () -> Unit,
    onRollbackSession: (String) -> Unit,
    onToggleLiveCall: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    var showQuickMenu by rememberSaveable { mutableStateOf(false) }

    val isBusyOrHasGoal = state.isExecutingGoal ||
        state.isLiveCallActive ||
        state.fsmState == GepyFsmState.LISTENING ||
        state.currentGoal.isNotBlank() ||
        !state.errorMessage.isNullOrBlank() ||
        state.chatHistory.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        if (wellnessSnapshot.isGuestPrivacyShieldActive || state.isLiveCallActive) {
            SovereignGuardianStatusStrip(
                state = state,
                wellness = wellnessSnapshot
            )
        }

        // Banner peringatan aksesibilitas (tetap bersih & halus)
        if (!permissionStatus.accessibilityEnabled) {
            Surface(
                onClick = onOpenAccessibilitySettings,
                color = GepyAmberWarning.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, GepyAmberWarning.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccessibilityNew,
                        contentDescription = null,
                        tint = GepyAmberWarning,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Layanan Aksesibilitas Belum Aktif",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D0D0D)
                        )
                        Text(
                            text = "Ketuk untuk mengaktifkan izin agar Gepy dapat beroperasi.",
                            fontSize = 11.5.sp,
                            color = Color(0xFF6E6E73)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = GepyAmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Area Kanvas Utama (Breathable Clean Canvas)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            if (isBusyOrHasGoal) {
                val chatScrollState = rememberScrollState()
                LaunchedEffect(state.chatHistory.size, state.fsmState, state.processTimeline.size, state.lastThought) {
                    chatScrollState.animateScrollTo(chatScrollState.maxValue)
                }

                val lastTurn = state.chatHistory.lastOrNull()
                val showProcessTimeline = state.isExecutingGoal ||
                    state.isLiveCallActive ||
                    state.fsmState == GepyFsmState.LISTENING ||
                    !state.errorMessage.isNullOrBlank() ||
                    state.chatHistory.isEmpty() ||
                    state.processTimeline.isNotEmpty()
                val trailingAssistantTurn = lastTurn?.takeIf {
                    showProcessTimeline && !it.isUser && state.processTimeline.isNotEmpty()
                }

                // Tampilan Percakapan Terpadu & Linimasa Proses Berpikir Gepy (Referensi Mika Working)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(chatScrollState)
                        .padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val turnsBeforeProcess = if (trailingAssistantTurn != null) {
                        state.chatHistory.dropLast(1)
                    } else {
                        state.chatHistory
                    }
                    turnsBeforeProcess.forEach { turn ->
                        CrossModalChatBubble(turn = turn)
                    }

                    if (showProcessTimeline) {
                        GepyProcessTimeline(
                            state = state,
                            onCancelGoal = onCancelGoal,
                            onRollbackSession = onRollbackSession
                        )
                    }

                    if (trailingAssistantTurn != null) {
                        CrossModalChatBubble(turn = trailingAssistantTurn)
                    }
                }
            } else {
                // Hero Idle Canvas Bersih (Merujuk Referensi 1: Judul Terpusat + Pill Chips + "Pilih tugas")
                val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
                val timeGreeting = remember(currentHour) {
                    when (currentHour) {
                        in 4..10 -> "Selamat pagi"
                        in 11..14 -> "Selamat siang"
                        in 15..18 -> "Selamat sore"
                        else -> "Selamat malam"
                    }
                }
                val ownerDisplayName = remember(ownerName) {
                    ownerName.trim().ifEmpty { "Kawan" }
                }

                val personaHint = when (state.byokSettings.sovereignPersonaMode) {
                    SovereignPersonaMode.CUSTOM -> "Persona Kustom"
                    SovereignPersonaMode.HONEST_MENTOR -> "Mentor Tegas"
                    SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Girlfriend Manja"
                    else -> "Gepy Taktis"
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    GepyBrandEmblem(size = 88.dp)

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "$timeGreeting, $ownerDisplayName",
                        fontSize = 23.sp,
                        lineHeight = 29.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Biarkan Gepy bekerja untuk Anda • $personaHint",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (pill in HERO_QUICK_PILLS) {
                            Surface(
                                onClick = { onSelectQuickAction(pill) },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = pill.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = pill.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // CTA Pill Gelap Utama ("Pilih tugas" — Referensi 1)
                    Surface(
                        onClick = { showQuickMenu = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Pilih tugas",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Floating Pill Composer (Screenshot 1 Bottom Bar)
        CleanFloatingComposer(
            state = state,
            promptText = promptText,
            onPromptTextChange = onPromptTextChange,
            onSubmitPrompt = onSubmitPrompt,
            onMicTrigger = onMicTrigger,
            onToggleLiveCall = onToggleLiveCall,
            onOpenQuickMenu = { showQuickMenu = true }
        )
    }

    if (showQuickMenu) {
        QuickActionsModalSheet(
            onDismiss = { showQuickMenu = false },
            onSelectOption = { option ->
                showQuickMenu = false
                if (option.isImmediateExecute) {
                    onQuickGoal(option.promptText)
                } else {
                    onPrefillPrompt(option.promptText)
                }
            }
        )
    }
}

@Composable
private fun SovereignGuardianStatusStrip(
    state: GepyOrchestratorState,
    wellness: WellnessGuardianSnapshot
) {
    val byok = state.byokSettings
    val effectivePersona = SovereignPersonaEngine.resolveEffectivePersona(
        configuredMode = byok.sovereignPersonaMode,
        wellness = wellness.toPersonalWellnessContext(
            ownerName = "",
            doomscrollLimitMinutes = byok.doomscrollLimitMinutes
        )
    )
    val personaBadgeText = when (effectivePersona) {
        SovereignPersonaMode.CUSTOM -> "Persona Kustom"
        SovereignPersonaMode.HONEST_MENTOR -> "Mentor Tegas"
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> "Girlfriend Manja"
        else -> "Gepy Taktis"
    }
    val personaColor = when (effectivePersona) {
        SovereignPersonaMode.CUSTOM -> Color(0xFF0EA5E9)
        SovereignPersonaMode.HONEST_MENTOR -> GepyAmberWarning
        SovereignPersonaMode.ROMANTIC_GIRLFRIEND -> Color(0xFFE11D48)
        else -> GepyBlueAccent
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(personaColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = personaBadgeText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (state.isLiveCallActive) {
                Text(
                    text = "Panggilan Suara Gepy Live",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = GepyGreenAccent
                )
            }

            if (wellness.isGuestPrivacyShieldActive) {
                Surface(
                    color = GepyRedDanger.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Shield Tamu",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = GepyRedDanger,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanFloatingComposer(
    state: GepyOrchestratorState,
    promptText: String,
    onPromptTextChange: (String) -> Unit,
    onSubmitPrompt: () -> Unit,
    onMicTrigger: () -> Unit,
    onToggleLiveCall: () -> Unit,
    onOpenQuickMenu: () -> Unit
) {
    val hasText = promptText.trim().isNotEmpty()

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pill Container Utama: [+] [Tanya Gepy apa saja...] [Mic / Kirim]
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenQuickMenu,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Aksi Cepat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    BasicTextField(
                        value = promptText,
                        onValueChange = onPromptTextChange,
                        enabled = !state.isExecutingGoal,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (promptText.isEmpty()) {
                                    Text(
                                        text = if (state.isExecutingGoal) "Sedang memproses..." else "Tanya Gepy apa saja...",
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (hasText) {
                        IconButton(
                            onClick = { onPromptTextChange("") },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Hapus Teks",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    val isListening = state.fsmState == GepyFsmState.LISTENING
                    IconButton(
                        onClick = {
                            if (hasText) onSubmitPrompt() else onMicTrigger()
                        },
                        enabled = !state.isExecutingGoal,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (hasText) Icons.Rounded.ArrowUpward else Icons.Rounded.Mic,
                            contentDescription = if (hasText) "Kirim Perintah" else "Bicara",
                            tint = when {
                                hasText -> MaterialTheme.colorScheme.onSurface
                                isListening -> GepyAmberWarning
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Tombol Melingkar Suara / Gepy Live (Referensi 1: Tombol Gelombang Biru)
            Surface(
                onClick = onToggleLiveCall,
                shape = CircleShape,
                color = if (state.isLiveCallActive) GepyRedDanger else GepyBlueAccent,
                modifier = Modifier.size(48.dp),
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.isLiveCallActive) Icons.Rounded.CallEnd else Icons.Rounded.GraphicEq,
                        contentDescription = "Panggilan Dua Arah Gemini Live",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * Meringkas pesan error teknis/JSON agar ramah pengguna pada kartu utama (maksimal 220 karakter),
 * sementara rincian diagnostik mentah tetap dapat dibuka melalui akordeon rincian teknis.
 */
internal fun summarizeUserFacingError(rawError: String?): String {
    val clean = rawError?.trim().orEmpty()
    if (clean.isEmpty()) return ""
    val strippedJsonDump = clean
        .substringBefore(" of {")
        .substringBefore("{\"")
        .trim()
    val base = strippedJsonDump.ifEmpty {
        "Terjadi kendala saat memproses respons AI. Silakan coba ulang perintah."
    }
    return if (base.length > 220) base.take(217).trimEnd() + "..." else base
}

private fun formatChatTurnDisplayText(raw: String): String {
    return raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        .replace(Regex("""\*([^*]+)\*"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""#{1,6}\s*"""), "")
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex(""" *\n *"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

@Composable
private fun CrossModalChatBubble(turn: ChatTurnItem) {
    val isUser = turn.isUser
    val isLiveCall = turn.channel == ConversationChannel.LIVE_CALL

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) Color(0xFF18181B) else Color(0xFFF7F7F8),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            border = if (isUser) null else BorderStroke(1.dp, Color(0xFFE5E5EA)),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isLiveCall) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Live Call",
                            tint = if (isUser) Color(0xFF93C5FD) else Color(0xFF1B64F2),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isUser) "Kamu • Live Call" else "Gepy • Live Call",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isUser) Color(0xFF93C5FD) else Color(0xFF1B64F2)
                        )
                    }
                }
                Text(
                    text = formatChatTurnDisplayText(turn.text),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (isUser) Color.White else Color(0xFF0D0D0D)
                )
            }
        }
    }
}

private fun formatElapsedDuration(ms: Long): String {
    val totalSecs = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}

@Composable
private fun GepyProcessTimeline(
    state: GepyOrchestratorState,
    onCancelGoal: () -> Unit,
    onRollbackSession: (String) -> Unit
) {
    var expandedSteps by rememberSaveable(state.goalStartedAtMs) { mutableStateOf(true) }
    var lastRolledBackSession by rememberSaveable { mutableStateOf<String?>(null) }
    val currentSessionId = state.lastSessionId

    var liveElapsedMs by remember(state.goalStartedAtMs, state.isExecutingGoal) {
        mutableLongStateOf(
            if (state.isExecutingGoal && state.goalStartedAtMs > 0L) {
                (System.currentTimeMillis() - state.goalStartedAtMs).coerceAtLeast(0L)
            } else {
                state.goalElapsedMs
            }
        )
    }
    LaunchedEffect(state.isExecutingGoal, state.goalStartedAtMs) {
        if (state.isExecutingGoal && state.goalStartedAtMs > 0L) {
            while (isActive) {
                liveElapsedMs = (System.currentTimeMillis() - state.goalStartedAtMs).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            liveElapsedMs = state.goalElapsedMs
        }
    }

    val statusTitle = when {
        state.fsmState == GepyFsmState.WAITING_HITL -> "Menunggu persetujuan..."
        state.isExecutingGoal -> "Sedang bekerja..."
        state.fsmState == GepyFsmState.LISTENING && state.isLiveCallActive -> "Gepy Live aktif"
        state.fsmState == GepyFsmState.LISTENING -> "Mendengarkan suara..."
        state.fsmState == GepyFsmState.ERROR -> "Terhenti karena kendala"
        state.lastActionLabel == "ABORTED" -> "Dihentikan"
        else -> "Selesai"
    }

    val canShowStopButton = state.isExecutingGoal ||
        (state.fsmState == GepyFsmState.LISTENING && !state.isLiveCallActive)

    val stepsToShow = remember(state.processTimeline, state.lastThought, state.fsmState, state.errorMessage) {
        if (state.processTimeline.isNotEmpty()) {
            state.processTimeline
        } else if (!state.errorMessage.isNullOrBlank()) {
            listOf(AgentProcessStep(summarizeUserFacingError(state.errorMessage), ProcessStepState.ERROR))
        } else if (state.isExecutingGoal && state.lastThought.isNotBlank()) {
            listOf(AgentProcessStep(state.lastThought, ProcessStepState.ACTIVE))
        } else {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        // Header Row (Mika-style: indikator status kiri + durasi / tombol hentikan kanan)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !state.isExecutingGoal && stepsToShow.isNotEmpty()) {
                    expandedSteps = !expandedSteps
                }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                when {
                    state.fsmState == GepyFsmState.WAITING_HITL -> {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = "Menunggu konfirmasi keamanan",
                            tint = GepyBlueAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    state.isExecutingGoal -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = GepyBlueAccent
                        )
                    }
                    state.fsmState == GepyFsmState.LISTENING && !state.isLiveCallActive -> {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(GepyAmberWarning)
                        )
                    }
                    state.isLiveCallActive -> {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(GepyGreenAccent)
                        )
                    }
                    state.fsmState == GepyFsmState.ERROR || state.lastActionLabel == "ABORTED" -> {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (state.fsmState == GepyFsmState.ERROR) GepyRedDanger else GepyAmberWarning,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = statusTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.fsmState == GepyFsmState.ERROR) GepyRedDanger else Color(0xFF18181B)
                )

                if (!state.isExecutingGoal && stepsToShow.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expandedSteps) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (liveElapsedMs > 0L || state.isExecutingGoal) {
                    Text(
                        text = formatElapsedDuration(liveElapsedMs),
                        fontSize = 12.5.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
                if (canShowStopButton) {
                    Surface(
                        onClick = onCancelGoal,
                        shape = RoundedCornerShape(50),
                        color = GepyRedDanger.copy(alpha = 0.10f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.StopCircle,
                                contentDescription = null,
                                tint = GepyRedDanger,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Hentikan",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GepyRedDanger
                            )
                        }
                    }
                }
            }
        }

        // Linimasa Vertikal Proses Berpikir & Aksi Gepy (Referensi Mika Working)
        if (expandedSteps && (stepsToShow.isNotEmpty() || state.isExecutingGoal)) {
            Spacer(modifier = Modifier.height(6.dp))
            val isWaitingHitl = state.fsmState == GepyFsmState.WAITING_HITL
            val showBgNotice = state.isExecutingGoal && !isWaitingHitl
            Column(modifier = Modifier.fillMaxWidth()) {
                stepsToShow.forEachIndexed { index, step ->
                    val isLastStep = index == stepsToShow.lastIndex
                    val hasNextLine = !isLastStep || showBgNotice
                    val isActiveNode = (step.state == ProcessStepState.ACTIVE && state.isExecutingGoal) ||
                        (isLastStep && isWaitingHitl)
                    val dotColor = when {
                        step.state == ProcessStepState.ERROR -> GepyRedDanger
                        isActiveNode -> GepyBlueAccent
                        else -> Color(0xFFA1A1AA)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(20.dp)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(if (isActiveNode) 8.dp else 7.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            if (hasNextLine) {
                                Box(
                                    modifier = Modifier
                                        .width(1.5.dp)
                                        .weight(1f)
                                        .padding(top = 3.dp)
                                        .background(Color(0xFFE4E4E7))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = step.text,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            fontWeight = if (isActiveNode) FontWeight.Medium else FontWeight.Normal,
                            color = when {
                                step.state == ProcessStepState.ERROR -> GepyRedDanger
                                isActiveNode -> Color(0xFF18181B)
                                else -> Color(0xFF71717A)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = if (hasNextLine) 10.dp else 2.dp)
                        )
                    }
                }

                if (showBgNotice) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .padding(top = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, Color(0xFFA1A1AA), CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Berjalan di latar belakang — kamu tetap bisa pakai HP seperti biasa.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = Color(0xFF9CA3AF),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (isWaitingHitl) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(GepyBlueAccent.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = null,
                                    tint = GepyBlueAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Konfirmasi Keamanan Diperlukan",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = state.lastThought.ifBlank {
                                        "Periksa ringkasan pada Overlay di layar sebelum melanjutkan eksekusi."
                                    },
                                    fontSize = 12.sp,
                                    lineHeight = 17.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!currentSessionId.isNullOrBlank() &&
            state.fsmState == GepyFsmState.COMPLETED &&
            lastRolledBackSession != currentSessionId &&
            state.lastActionLabel !in listOf("CHAT", "WEB_SEARCH", "WEB_READ", "-")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Urungkan memori aksi ini",
                    fontSize = 11.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            lastRolledBackSession = currentSessionId
                            onRollbackSession(currentSessionId)
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
