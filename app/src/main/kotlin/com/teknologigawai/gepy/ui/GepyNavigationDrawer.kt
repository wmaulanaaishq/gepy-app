package com.teknologigawai.gepy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teknologigawai.gepy.BuildConfig
import com.teknologigawai.gepy.core.memory.ChatTurnItem
import com.teknologigawai.gepy.core.ports.TelemetryEntry
import com.teknologigawai.gepy.ui.theme.CyberpunkTheme
import com.teknologigawai.gepy.ui.theme.GepyBlueAccent

/** Destinasi layar utama yang dirender di dalam `DashboardScreen`. */
enum class GepyMainSection(val title: String) {
    ASSISTANT("Asisten"),
    TELEMETRY("Telemetri"),
    SETTINGS("Pengaturan")
}

/** Aksi modal/sheet cepat yang dipicu dari `ModalNavigationDrawer`. */
enum class DrawerFeatureTarget {
    MEMORY_TEMPORAL,
    PLAYBOOK_GRAPH,
    REMOTE_AND_IOT,
    PROACTIVE_GUARDIAN,
    SKILLS_CATALOG
}

/** Pintasan tugas tersemat pada seksi *Disematkan* di laci navigasi. */
data class DrawerPinnedWorkflow(
    val id: String,
    val label: String,
    val goalPrompt: String,
    val isImmediateExecute: Boolean
)

/** Item riwayat obrolan / tugas terbaru yang ditampilkan di laci navigasi. */
data class DrawerChatEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val promptToPrefill: String,
    val isActiveSession: Boolean = false
)

internal val DEFAULT_PINNED_WORKFLOWS = listOf(
    DrawerPinnedWorkflow(
        id = "pin_briefing",
        label = "Ringkasan agenda hari ini",
        goalPrompt = "Bacakan ringkasan jadwal dan aktivitas hari ini",
        isImmediateExecute = true
    ),
    DrawerPinnedWorkflow(
        id = "pin_screen",
        label = "Baca dan rangkum layar",
        goalPrompt = "Baca dan rangkum isi layar sekarang",
        isImmediateExecute = true
    )
)

/** Mengambil maksimal 2 huruf inisial nama pemilik untuk avatar footer drawer. */
internal fun extractOwnerInitials(displayName: String?): String {
    val clean = displayName?.trim().orEmpty()
    if (clean.isEmpty()) return "GP"
    val parts = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "GP"
    }
}

/** Menyaring item *Disematkan* berdasarkan kueri pencarian di drawer. */
internal fun filterDrawerPinned(
    items: List<DrawerPinnedWorkflow>,
    query: String
): List<DrawerPinnedWorkflow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return items
    return items.filter {
        it.label.lowercase().contains(q) || it.goalPrompt.lowercase().contains(q)
    }
}

/** Menyaring item *Obrolan & Tugas Terbaru* berdasarkan kueri pencarian di drawer. */
internal fun filterDrawerChats(
    items: List<DrawerChatEntry>,
    query: String
): List<DrawerChatEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return items
    return items.filter {
        it.title.lowercase().contains(q) ||
            it.subtitle.lowercase().contains(q) ||
            it.promptToPrefill.lowercase().contains(q)
    }
}

/**
 * Membangun daftar entri nyata seksi *Obrolan* dari sesi aktif (`ConversationSessionBuffer`)
 * dan riwayat tujuan unik terakhir di `TelemetryEntry`.
 */
internal fun buildRecentSessionEntries(
    chatHistory: List<ChatTurnItem>,
    telemetryHistory: List<TelemetryEntry>,
    maxItems: Int = 6
): List<DrawerChatEntry> {
    val result = mutableListOf<DrawerChatEntry>()
    if (chatHistory.isNotEmpty()) {
        val firstUserTurn = chatHistory.firstOrNull { it.isUser } ?: chatHistory.last()
        val previewTitle = firstUserTurn.text.trim().replace("\n", " ").take(48)
        result.add(
            DrawerChatEntry(
                id = "active_session",
                title = previewTitle.ifEmpty { "Sesi Percakapan Aktif" },
                subtitle = "Sesi aktif • ${chatHistory.size} giliran",
                promptToPrefill = "",
                isActiveSession = true
            )
        )
    }
    val seenGoals = HashSet<String>()
    for (entry in telemetryHistory) {
        val goal = entry.userGoal.trim().replace("\n", " ")
        if (goal.isEmpty()) continue
        val key = goal.lowercase()
        if (!seenGoals.add(key)) continue
        result.add(
            DrawerChatEntry(
                id = "goal_${entry.sessionId}_${entry.stepNumber}",
                title = goal.take(48),
                subtitle = "${entry.packageName.substringAfterLast('.')} • ${entry.totalStepLatencyMs} ms",
                promptToPrefill = goal,
                isActiveSession = false
            )
        )
        if (result.size >= maxItems) break
    }
    return result
}

private data class DrawerMenuSpec(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val isSelected: Boolean,
    val onClick: () -> Unit
)

/**
 * Laci navigasi samping Material 3 (`ModalDrawerSheet`) terstruktur untuk Gepy Native.
 */
@Composable
internal fun GepyDrawerSheetContent(
    drawerState: DrawerState,
    activeSection: GepyMainSection,
    ownerDisplayName: String,
    ownerEmail: String?,
    @Suppress("UNUSED_PARAMETER") isRemoteOrCsiActive: Boolean,
    chatHistory: List<ChatTurnItem>,
    telemetryHistory: List<TelemetryEntry>,
    pinnedWorkflows: List<DrawerPinnedWorkflow> = DEFAULT_PINNED_WORKFLOWS,
    onSelectSection: (GepyMainSection) -> Unit,
    onOpenFeature: (DrawerFeatureTarget) -> Unit,
    onSelectPinnedWorkflow: (DrawerPinnedWorkflow) -> Unit,
    onSelectRecentEntry: (DrawerChatEntry) -> Unit,
    onStartNewChat: () -> Unit
) {
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val menuSpecs = listOf(
        DrawerMenuSpec(
            id = "memory",
            label = "Memori dan pengetahuan",
            icon = Icons.Rounded.Psychology,
            isSelected = false,
            onClick = { onOpenFeature(DrawerFeatureTarget.MEMORY_TEMPORAL) }
        ),
        DrawerMenuSpec(
            id = "playbook",
            label = "Aturan dan playbook",
            icon = Icons.Rounded.Layers,
            isSelected = false,
            onClick = { onOpenFeature(DrawerFeatureTarget.PLAYBOOK_GRAPH) }
        ),
        DrawerMenuSpec(
            id = "remote",
            label = "Kendali jarak jauh",
            icon = Icons.Rounded.Devices,
            isSelected = false,
            onClick = { onOpenFeature(DrawerFeatureTarget.REMOTE_AND_IOT) }
        ),
        DrawerMenuSpec(
            id = "guardian",
            label = "Terjadwal",
            icon = Icons.Rounded.Schedule,
            isSelected = false,
            onClick = { onOpenFeature(DrawerFeatureTarget.PROACTIVE_GUARDIAN) }
        ),
        DrawerMenuSpec(
            id = "skills",
            label = "Skill dan alat",
            icon = Icons.Rounded.Extension,
            isSelected = false,
            onClick = { onOpenFeature(DrawerFeatureTarget.SKILLS_CATALOG) }
        ),
        DrawerMenuSpec(
            id = "telemetry",
            label = "Telemetri",
            icon = Icons.Rounded.Analytics,
            isSelected = activeSection == GepyMainSection.TELEMETRY,
            onClick = { onSelectSection(GepyMainSection.TELEMETRY) }
        ),
        DrawerMenuSpec(
            id = "settings",
            label = "Pengaturan",
            icon = Icons.Rounded.Tune,
            isSelected = activeSection == GepyMainSection.SETTINGS,
            onClick = { onSelectSection(GepyMainSection.SETTINGS) }
        )
    )

    val filteredMenus = if (searchQuery.isBlank()) {
        menuSpecs
    } else {
        val q = searchQuery.trim().lowercase()
        menuSpecs.filter { it.label.lowercase().contains(q) }
    }
    val filteredPinned = filterDrawerPinned(pinnedWorkflows, searchQuery)
    val recentEntries = filterDrawerChats(
        buildRecentSessionEntries(chatHistory = chatHistory, telemetryHistory = telemetryHistory),
        searchQuery
    )

    ModalDrawerSheet(
        drawerState = drawerState,
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.widthIn(min = 280.dp, max = 324.dp)
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Header: Brand + Versi + Tombol Cari
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Gepy",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    onClick = {
                        isSearchExpanded = !isSearchExpanded
                        if (!isSearchExpanded) searchQuery = ""
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSearchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = if (isSearchExpanded) "Tutup pencarian drawer" else "Cari di menu dan riwayat",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (isSearchExpanded) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(GepyBlueAccent),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Cari fitur, tugas, atau riwayat...",
                                            fontSize = 13.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Bersihkan pencarian",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Konten Scrollable Tengah: Menu Fitur -> Disematkan -> Obrolan
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                for (item in filteredMenus) {
                    DrawerFeatureRow(
                        icon = item.icon,
                        label = item.label,
                        isSelected = item.isSelected,
                        onClick = item.onClick
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Disematkan",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )

                if (filteredPinned.isEmpty()) {
                    Text(
                        text = "Tidak ada pintasan cocok.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                } else {
                    for (pinned in filteredPinned) {
                        DrawerCompactItemRow(
                            icon = Icons.Rounded.PushPin,
                            title = pinned.label,
                            subtitle = null,
                            isHighlighted = false,
                            onClick = { onSelectPinnedWorkflow(pinned) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Obrolan",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )

                if (recentEntries.isEmpty()) {
                    Text(
                        text = "Belum ada riwayat percakapan pada sesi ini.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                } else {
                    for (entry in recentEntries) {
                        DrawerCompactItemRow(
                            icon = if (entry.isActiveSession) Icons.AutoMirrored.Rounded.Chat else null,
                            title = entry.title,
                            subtitle = entry.subtitle,
                            isHighlighted = entry.isActiveSession && activeSection == GepyMainSection.ASSISTANT,
                            onClick = { onSelectRecentEntry(entry) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Sticky Footer: [+ Obrolan Baru] + [Avatar Inisial Pemilik]
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    onClick = onStartNewChat,
                    shape = RoundedCornerShape(50),
                    color = GepyBlueAccent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Obrolan",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                val initials = extractOwnerInitials(ownerDisplayName)
                Surface(
                    onClick = { onSelectSection(GepyMainSection.SETTINGS) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initials,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerFeatureRow(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                fontSize = 14.5.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DrawerCompactItemRow(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isHighlighted) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GepyDrawerPreview() {
    CyberpunkTheme(darkTheme = false) {
        GepyDrawerSheetContent(
            drawerState = rememberDrawerState(DrawerValue.Open),
            activeSection = GepyMainSection.ASSISTANT,
            ownerDisplayName = "Pengguna Gepy",
            ownerEmail = "owner@example.com",
            isRemoteOrCsiActive = true,
            chatHistory = emptyList(),
            telemetryHistory = listOf(
                TelemetryEntry(
                    sessionId = "s1",
                    stepNumber = 1,
                    userGoal = "Riset Agen AI Mobile",
                    packageName = "com.teknologigawai.gepy",
                    rawNodeCount = 120,
                    prunedNodeCount = 18,
                    pruningRatio = 0.85,
                    llmLatencyMs = 210,
                    totalStepLatencyMs = 240,
                    actionType = "WEB_SEARCH",
                    verificationOutcome = "VERIFIED",
                    createdAt = 1000L
                ),
                TelemetryEntry(
                    sessionId = "s2",
                    stepNumber = 1,
                    userGoal = "Cek baterai laptop dan git status",
                    packageName = "com.teknologigawai.gepy",
                    rawNodeCount = 10,
                    prunedNodeCount = 10,
                    pruningRatio = 0.0,
                    llmLatencyMs = 0,
                    totalStepLatencyMs = 45,
                    actionType = "LAPTOP_RPC",
                    verificationOutcome = "VERIFIED",
                    createdAt = 2000L
                )
            ),
            onSelectSection = {},
            onOpenFeature = {},
            onSelectPinnedWorkflow = {},
            onSelectRecentEntry = {},
            onStartNewChat = {}
        )
    }
}
