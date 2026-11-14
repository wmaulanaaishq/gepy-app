package com.teknologigawai.gepy.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.teknologigawai.gepy.core.diagnostics.MemoryHealthLevel
import com.teknologigawai.gepy.core.diagnostics.RuntimeMemoryDiagnostics
import com.teknologigawai.gepy.core.diagnostics.RuntimeMemorySnapshot
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.ports.MemoryPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teknologigawai.gepy.core.ports.TelemetryEntry
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestratorState
import com.teknologigawai.gepy.ui.theme.GepyAmberWarning
import com.teknologigawai.gepy.ui.theme.GepyGreenAccent
import com.teknologigawai.gepy.ui.theme.GepyRedDanger
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun TelemetryTabContent(
    state: GepyOrchestratorState,
    userMemoryManager: UserMemoryManager? = null,
    memoryPort: MemoryPort? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var memorySnapshot by remember { mutableStateOf<RuntimeMemorySnapshot?>(null) }
    var isMeasuringMemory by remember { mutableStateOf(false) }

    LaunchedEffect(userMemoryManager, memoryPort) {
        while (isActive) {
            isMeasuringMemory = true
            try {
                memorySnapshot = RuntimeMemoryDiagnostics.capture(
                    context = context,
                    userMemoryManager = userMemoryManager,
                    memoryPort = memoryPort
                )
            } finally {
                isMeasuringMemory = false
            }
            delay(2000L)
        }
    }

    val pruningPercent = String.format(Locale.US, "%.1f%%", state.lastPruningRatio * 100.0)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RuntimeMemoryDiagnosticsCard(
            snapshot = memorySnapshot,
            isMeasuring = isMeasuringMemory,
            onMeasureNow = {
                if (!isMeasuringMemory) {
                    scope.launch {
                        isMeasuringMemory = true
                        try {
                            memorySnapshot = RuntimeMemoryDiagnostics.capture(
                                context = context,
                                userMemoryManager = userMemoryManager,
                                memoryPort = memoryPort
                            )
                        } finally {
                            isMeasuringMemory = false
                        }
                    }
                }
            },
            onTrimGc = {
                if (!isMeasuringMemory) {
                    scope.launch {
                        isMeasuringMemory = true
                        try {
                            withContext(Dispatchers.Default) {
                                RuntimeMemoryDiagnostics.requestLightGcTrim()
                            }
                            delay(250L)
                            memorySnapshot = RuntimeMemoryDiagnostics.capture(
                                context = context,
                                userMemoryManager = userMemoryManager,
                                memoryPort = memoryPort
                            )
                            Toast.makeText(
                                context,
                                "Trim GC selesai dan PSS diperbarui.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            isMeasuringMemory = false
                        }
                    }
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricKpiCard(
                modifier = Modifier.weight(1f),
                label = "Kompresi Pohon XML",
                value = pruningPercent,
                caption = "Target > 80%"
            )
            MetricKpiCard(
                modifier = Modifier.weight(1f),
                label = "Latensi Penalaran",
                value = if (state.lastLlmLatencyMs == 0) "0 ms (Fast-Path)" else "${state.lastLlmLatencyMs} ms",
                caption = "Target < 800 ms"
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = GepyGreenAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Single-Process In-Memory + Playbook dan Alat OS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "0 ms IPC overhead • WhatsApp/Calendar Fast-Path • Android System Tools < 5 ms",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = "Riwayat Langkah Sesi (${state.telemetryHistory.size})",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.telemetryHistory.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = "Belum ada riwayat eksekusi. Jalankan perintah dari tab Asisten untuk melihat metrik per langkah.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            for (entry in state.telemetryHistory) {
                TelemetryRowItem(entry = entry)
            }
        }
    }
}

@Composable
private fun RuntimeMemoryDiagnosticsCard(
    snapshot: RuntimeMemorySnapshot?,
    isMeasuring: Boolean,
    onMeasureNow: () -> Unit,
    onTrimGc: () -> Unit
) {
    val healthLevel = snapshot?.healthLevel ?: MemoryHealthLevel.OPTIMAL
    val healthColor = when (healthLevel) {
        MemoryHealthLevel.OPTIMAL -> GepyGreenAccent
        MemoryHealthLevel.NORMAL -> MaterialTheme.colorScheme.primary
        MemoryHealthLevel.ELEVATED -> GepyAmberWarning
        MemoryHealthLevel.HIGH -> GepyRedDanger
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Diagnostik Memori Real-Time (PSS)",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Aktif 2s hanya saat tab Telemetri dibuka",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = healthColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (snapshot == null) "Mengukur..." else healthLevel.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = healthColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (snapshot == null) {
                Text(
                    text = "Mengambil profil memori proses ART dan Room v6...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val totalPssStr = String.format(Locale.US, "%.1f MB", snapshot.totalPssMb)
                val ceilingStr = String.format(Locale.US, "%.0f MB", RuntimeMemoryDiagnostics.SAFE_LMK_PSS_CEILING_MB)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = totalPssStr,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "/ plafon aman $ceilingStr",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                LinearProgressIndicator(
                    progress = { snapshot.ceilingUtilizationFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = healthColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Text(
                    text = String.format(
                        Locale.US,
                        "ART/Java: %.1f MB • Native: %.1f MB • Graphics/UI: %.1f MB • Code+Stack: %.1f MB",
                        snapshot.javaHeapMb,
                        snapshot.nativeHeapMb,
                        snapshot.graphicsMb,
                        snapshot.codeAndStackMb
                    ),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = String.format(
                        Locale.US,
                        "RAM Bebas HP: %.0f / %.0f MB • Batas Heap App: %.0f MB%s",
                        snapshot.systemAvailRamMb,
                        snapshot.systemTotalRamMb,
                        snapshot.maxAppHeapMb,
                        if (snapshot.isLowRamDeviceWarning) " • PERINGATAN LOW-RAM" else ""
                    ),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (snapshot.isLowRamDeviceWarning) GepyRedDanger else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (snapshot.vectorStats != null || snapshot.sewmStats != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Memori Jangka Panjang Lokal",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            snapshot.vectorStats?.let { vs ->
                                val ramVecMb = vs.estimatedVectorRamKb / 1024.0
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "RAM Vektor: %d / %d (~%.1f MB) • SQLite Room v6: %d / %d • Fakta Aktif: %d",
                                        vs.ramVectorCount,
                                        vs.maxRamVectors,
                                        ramVecMb,
                                        vs.dbVectorCount,
                                        vs.maxDbVectors,
                                        vs.activeTemporalFacts
                                    ),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            snapshot.sewmStats?.let { sewm ->
                                val sewmKb = sewm.estimatedSqliteBytes / 1024.0
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "SEWM Risiko UI: %d / %d aturan • Risiko Tinggi: %d (~%.0f KB)",
                                        sewm.totalRulesInDb,
                                        sewm.maxDbRulesCap,
                                        sewm.highRiskRuleCount,
                                        sewmKb
                                    ),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onMeasureNow,
                    enabled = !isMeasuring,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ukur Ulang", fontSize = 12.sp)
                }
                FilledTonalButton(
                    onClick = onTrimGc,
                    enabled = !isMeasuring,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Trim GC", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MetricKpiCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    caption: String
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = caption,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TelemetryRowItem(entry: TelemetryEntry) {
    val isOk = entry.verificationOutcome == "CORRECT"
    val badgeColor = if (isOk) GepyGreenAccent else GepyAmberWarning

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${entry.stepNumber} ${entry.actionType}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = badgeColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = entry.verificationOutcome,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.userGoal,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${entry.packageName} • Node: ${entry.rawNodeCount}->${entry.prunedNodeCount} • LLM: ${entry.llmLatencyMs}ms • Total: ${entry.totalStepLatencyMs}ms",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
