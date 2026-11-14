package com.teknologigawai.gepy.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.teknologigawai.gepy.core.memory.EbbinghausDecayEngine
import com.teknologigawai.gepy.core.memory.TemporalKnowledgeGraph
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.data.database.TemporalMemoryFactEntity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.teknologigawai.gepy.core.config.ByokProviderPreset
import com.teknologigawai.gepy.core.config.ByokSettings
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.LlmProviderHealthTracker
import com.teknologigawai.gepy.core.config.PreferredLlmProvider
import com.teknologigawai.gepy.core.ports.PlaybookTransition
import com.teknologigawai.gepy.features.orchestrator.GepyOrchestrator
import com.teknologigawai.gepy.features.voice.AndroidVoiceAudioFocusCoordinator
import com.teknologigawai.gepy.features.voice.ElevenLabsRuntimeConfig
import com.teknologigawai.gepy.features.voice.VoiceAudioCacheManager
import com.teknologigawai.gepy.features.voice.VoiceAudioFocusEvent
import com.teknologigawai.gepy.ui.theme.GepyAmberWarning
import com.teknologigawai.gepy.ui.theme.GepyGreenAccent
import com.teknologigawai.gepy.ui.theme.GepyRedDanger
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class ByokDialogTab(val label: String) {
    GEMINI("Gemini"),
    CUSTOM("Custom"),
    FIREWORKS("Fireworks"),
    VOICE("Suara")
}

private fun PreferredLlmProvider.byokShortLabel(): String = when (this) {
    PreferredLlmProvider.AUTO -> "Otomatis"
    PreferredLlmProvider.FIREWORKS -> "Fireworks AI"
    PreferredLlmProvider.GEMINI -> "Gemini Flash"
    PreferredLlmProvider.CUSTOM_OPENAI -> "Custom / AI/ML API"
    PreferredLlmProvider.AZURE -> "Azure OpenAI"
}

/**
 * Dialog Konfigurasi **Bring Your Own Key (BYOK) & Multi-Provider Failover**.
 *
 * Memungkinkan pengguna:
 * - Memilih penyedia utama (`AUTO`, `FIREWORKS`, `GEMINI`, `CUSTOM_OPENAI`, `AZURE`) dalam 1 ketukan
 * - Mengaktifkan/menonaktifkan Custom OpenAI secara non-destruktif (`customOpenAiEnabled`)
 * - Memantau & memulihkan cooldown HTTP 402/429 melalui [LlmProviderHealthTracker]
 * - Mengelola API Key per tab (`Gemini`, `Custom`, `Fireworks`, `Suara`) tanpa formulir panjang menumpuk.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ByokConfigurationDialog(
    currentSettings: ByokSettings,
    onDismiss: () -> Unit,
    onSave: (ByokSettings, Boolean) -> Unit,
    onResetDefault: () -> Unit,
    onTestElevenLabsVoice: suspend (ElevenLabsRuntimeConfig) -> Result<String> = {
        Result.failure(IllegalStateException("Pratinjau suara belum tersedia."))
    }
) {
    val dialogScope = rememberCoroutineScope()
    var preferredProvider by remember { mutableStateOf(currentSettings.preferredProvider) }
    var customEnabled by remember { mutableStateOf(currentSettings.customOpenAiEnabled) }
    var customUrl by remember { mutableStateOf(currentSettings.customOpenAiUrl) }
    var customKey by remember { mutableStateOf(currentSettings.customOpenAiApiKey) }
    var customModel by remember { mutableStateOf(currentSettings.customOpenAiModel) }
    var fireworksKey by remember { mutableStateOf(currentSettings.fireworksApiKeyOverride) }
    var fireworksModel by remember {
        mutableStateOf(currentSettings.fireworksModelOverride.ifEmpty { EnvConfig.fireworksModel })
    }
    var geminiKey by remember { mutableStateOf(currentSettings.geminiApiKeyOverride) }
    var geminiSecondaryKey by remember { mutableStateOf(currentSettings.geminiSecondaryApiKeyOverride) }
    var geminiModel by remember {
        mutableStateOf(currentSettings.geminiModelOverride.ifEmpty { EnvConfig.geminiModel })
    }
    var geminiEmbeddingModel by remember {
        mutableStateOf(currentSettings.geminiEmbeddingModelOverride.ifEmpty { EnvConfig.geminiEmbeddingModel })
    }
    var elevenLabsEnabled by remember { mutableStateOf(currentSettings.elevenLabsEnabled) }
    var elevenLabsKey by remember { mutableStateOf(currentSettings.elevenLabsApiKeyOverride) }
    var elevenLabsVoiceId by remember {
        mutableStateOf(currentSettings.elevenLabsVoiceIdOverride.ifEmpty { EnvConfig.defaultElevenLabsVoiceId })
    }
    var elevenLabsModel by remember {
        mutableStateOf(currentSettings.elevenLabsModelOverride.ifEmpty { EnvConfig.elevenLabsModel })
    }
    var elevenLabsStability by remember { mutableFloatStateOf(currentSettings.elevenLabsStability) }
    var elevenLabsSimilarity by remember { mutableFloatStateOf(currentSettings.elevenLabsSimilarityBoost) }
    var elevenLabsCacheMaxMb by remember { mutableIntStateOf(currentSettings.elevenLabsCacheMaxMb.coerceIn(50, 250)) }
    var showKeysPlainText by remember { mutableStateOf(false) }
    var isTestingVoice by remember { mutableStateOf(false) }
    var voicePreviewStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var cooldownRefreshTick by remember { mutableIntStateOf(0) }
    var activeTab by remember {
        mutableStateOf(
            when (currentSettings.preferredProvider) {
                PreferredLlmProvider.CUSTOM_OPENAI -> ByokDialogTab.CUSTOM
                PreferredLlmProvider.FIREWORKS -> ByokDialogTab.FIREWORKS
                else -> ByokDialogTab.GEMINI
            }
        )
    }

    val cooldowns = remember(cooldownRefreshTick) {
        LlmProviderHealthTracker.activeCooldownsSnapshot()
    }

    fun buildUpdatedByokSettings(): ByokSettings {
        val cleanCustomKey = customKey.trim()
        val resolvedGeminiKey = when {
            geminiKey.trim().isNotEmpty() -> geminiKey.trim()
            EnvConfig.isGeminiApiKeyFormat(cleanCustomKey) -> cleanCustomKey
            else -> ""
        }
        return currentSettings.copy(
            preferredProvider = preferredProvider,
            fireworksApiKeyOverride = fireworksKey.trim(),
            fireworksModelOverride = fireworksModel.trim(),
            geminiApiKeyOverride = resolvedGeminiKey,
            geminiSecondaryApiKeyOverride = geminiSecondaryKey.trim(),
            geminiModelOverride = geminiModel.trim(),
            geminiEmbeddingModelOverride = geminiEmbeddingModel.trim(),
            customOpenAiEnabled = customEnabled,
            customOpenAiUrl = customUrl.trim(),
            customOpenAiApiKey = cleanCustomKey,
            customOpenAiModel = customModel.trim(),
            elevenLabsEnabled = elevenLabsEnabled,
            elevenLabsApiKeyOverride = elevenLabsKey.trim(),
            elevenLabsVoiceIdOverride = elevenLabsVoiceId.trim(),
            elevenLabsModelOverride = elevenLabsModel.trim(),
            elevenLabsStability = elevenLabsStability.coerceIn(0f, 1f),
            elevenLabsSimilarityBoost = elevenLabsSimilarity.coerceIn(0f, 1f),
            elevenLabsCacheMaxMb = elevenLabsCacheMaxMb.coerceIn(10, 250)
        )
    }

    val keyVisualTransform = if (showKeysPlainText) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header Tetap
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model AI dan kunci API",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Aktif: ${preferredProvider.byokShortLabel()}",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { showKeysPlainText = !showKeysPlainText }) {
                        Icon(
                            imageVector = if (showKeysPlainText) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (showKeysPlainText) "Sembunyikan Key" else "Lihat Key",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Tutup",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Konten Dapat Digulir
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Kartu Pilihan Otak Utama 1-Ketuk
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Otak Utama ReAct (1-Ketuk)",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pindah penyedia utama langsung tanpa menghapus key tersimpan.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (provider in PreferredLlmProvider.entries) {
                                    FilterChip(
                                        selected = preferredProvider == provider,
                                        onClick = { preferredProvider = provider },
                                        label = {
                                            Text(
                                                text = provider.byokShortLabel(),
                                                fontSize = 11.5.sp
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Peringatan Cooldown HTTP 402/429 (jika aktif)
                    if (cooldowns.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = GepyAmberWarning.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, GepyAmberWarning.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = GepyAmberWarning,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Penyedia Terjeda Sementara",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            LlmProviderHealthTracker.resetAll()
                                            cooldownRefreshTick++
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pulihkan", fontSize = 11.5.sp)
                                    }
                                }
                                cooldowns.forEach { status ->
                                    val minsLeft = ceil(status.remainingMs / 60_000.0).toInt().coerceAtLeast(1)
                                    Text(
                                        text = "• ${status.provider.byokShortLabel()}: ${status.reason} (~$minsLeft mnt)",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Pemilih 4 Tab Segmented
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ByokDialogTab.entries.forEach { tab ->
                                val selected = activeTab == tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.surface
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            }
                                        )
                                        .clickable { activeTab = tab }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab.label,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Formulir Fokus Sesuai Tab Aktif
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (activeTab) {
                                ByokDialogTab.GEMINI -> {
                                    Text(
                                        text = "Google Gemini Dual-Key (Live Call, Chat, dan Vector)",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GepyGreenAccent
                                    )
                                    Text(
                                        text = "Key 1: ${currentSettings.maskedGeminiApiKey} • Key 2: ${currentSettings.maskedGeminiSecondaryApiKey}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = geminiKey,
                                        onValueChange = { geminiKey = it },
                                        label = { Text("Gemini API Key 1 (Live Call dan Chat)") },
                                        placeholder = { Text("Kosongkan = default (AIza... / AQ....)") },
                                        visualTransformation = keyVisualTransform,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = geminiSecondaryKey,
                                        onValueChange = { geminiSecondaryKey = it },
                                        label = { Text("Gemini API Key 2 (Vector dan Failover 429)") },
                                        placeholder = { Text("Kosongkan = default (AIza... / AQ....)") },
                                        visualTransformation = keyVisualTransform,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = geminiModel,
                                        onValueChange = { geminiModel = it },
                                        label = { Text("Gemini Flash Model ID (VLM/Brain)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = geminiEmbeddingModel,
                                        onValueChange = { geminiEmbeddingModel = it },
                                        label = { Text("Gemini Embedding Model (Vector Layer 2)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                ByokDialogTab.CUSTOM -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Aktifkan Custom OpenAI",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Nonaktifkan untuk melewati endpoint kustom tanpa menghapus key.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = customEnabled,
                                            onCheckedChange = { customEnabled = it }
                                        )
                                    }
                                    Text(
                                        text = "Preset Cepat OpenAI-Compatible (1-Ketuk):",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        for (preset in ByokProviderPreset.entries) {
                                            FilterChip(
                                                selected = customUrl == preset.chatCompletionsUrl &&
                                                    customModel == preset.recommendedModel,
                                                onClick = {
                                                    customEnabled = true
                                                    customUrl = preset.chatCompletionsUrl
                                                    customModel = preset.recommendedModel
                                                },
                                                label = { Text(preset.label, fontSize = 11.5.sp) }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = customUrl,
                                        onValueChange = { customUrl = it },
                                        label = { Text("Endpoint URL") },
                                        placeholder = { Text("https://api.aimlapi.com/v1/chat/completions") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = customModel,
                                        onValueChange = { customModel = it },
                                        label = { Text("Model ID (OpenRouter / Groq / DeepSeek)") },
                                        placeholder = { Text("deepseek/deepseek-chat-v3-0324") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = customKey,
                                        onValueChange = { customKey = it },
                                        label = { Text("Custom OpenAI API Key") },
                                        placeholder = { Text("sk-...") },
                                        visualTransformation = keyVisualTransform,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                ByokDialogTab.FIREWORKS -> {
                                    Text(
                                        text = "Fireworks AI (DeepSeek V4.1 Flash Override)",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Kosongkan API Key untuk memakai kunci bawaan aplikasi.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = fireworksKey,
                                        onValueChange = { fireworksKey = it },
                                        label = { Text("Fireworks API Key Override") },
                                        placeholder = { Text("fw_...") },
                                        visualTransformation = keyVisualTransform,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = fireworksModel,
                                        onValueChange = { fireworksModel = it },
                                        label = { Text("Fireworks Model ID") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                ByokDialogTab.VOICE -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Suara Kustom ElevenLabs (gepy)",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GepyGreenAccent,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = elevenLabsEnabled,
                                            onCheckedChange = { elevenLabsEnabled = it }
                                        )
                                    }
                                    Text(
                                        text = "Status Kunci Bawaan: ${currentSettings.maskedElevenLabsApiKey}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = elevenLabsKey,
                                        onValueChange = { elevenLabsKey = it },
                                        label = { Text("ElevenLabs API Key Override (sk_...)") },
                                        placeholder = { Text("Kosongkan = kunci bawaan / Cache Offline") },
                                        visualTransformation = keyVisualTransform,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = elevenLabsVoiceId,
                                        onValueChange = { elevenLabsVoiceId = it },
                                        label = { Text("ElevenLabs Voice ID (gepy)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        for (modelCandidate in listOf("eleven_v4_turbo", "eleven_flash_v2_5", "eleven_v4")) {
                                            FilterChip(
                                                selected = elevenLabsModel == modelCandidate,
                                                onClick = { elevenLabsModel = modelCandidate },
                                                label = { Text(modelCandidate, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Stability: ${String.format(Locale.US, "%.2f", elevenLabsStability)} (Kreatif <-> Stabil)",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Slider(
                                            value = elevenLabsStability,
                                            onValueChange = { elevenLabsStability = it },
                                            valueRange = 0f..1f
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Similarity Boost: ${String.format(Locale.US, "%.2f", elevenLabsSimilarity)} (Kemiripan Klona)",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Slider(
                                            value = elevenLabsSimilarity,
                                            onValueChange = { elevenLabsSimilarity = it },
                                            valueRange = 0f..1f
                                        )
                                    }
                                    val estCacheHours = (elevenLabsCacheMaxMb * 1_048_576.0) / (8_000.0 * 3_600.0)
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Batas Cache Suara: $elevenLabsCacheMaxMb MB (~${String.format(Locale.US, "%.1f", estCacheHours)} jam 64 kbps • 0 MB RAM)",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Slider(
                                            value = elevenLabsCacheMaxMb.toFloat(),
                                            onValueChange = { raw ->
                                                elevenLabsCacheMaxMb = ((raw / 25f).roundToInt() * 25).coerceIn(50, 250)
                                            },
                                            valueRange = 50f..250f,
                                            steps = 7
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (!isTestingVoice) {
                                                isTestingVoice = true
                                                voicePreviewStatus = null
                                                val draft = buildUpdatedByokSettings()
                                                val draftRuntime = ElevenLabsRuntimeConfig(
                                                    enabled = true,
                                                    apiKey = draft.effectiveElevenLabsApiKey,
                                                    voiceId = draft.effectiveElevenLabsVoiceId,
                                                    modelId = draft.effectiveElevenLabsModel,
                                                    stability = draft.elevenLabsStability,
                                                    similarityBoost = draft.elevenLabsSimilarityBoost
                                                )
                                                dialogScope.launch {
                                                    val res = onTestElevenLabsVoice(draftRuntime)
                                                    voicePreviewStatus = res.fold(
                                                        onSuccess = { msg -> true to msg },
                                                        onFailure = { err ->
                                                            false to (err.localizedMessage ?: err.message ?: "Gagal mencoba suara draf.")
                                                        }
                                                    )
                                                    isTestingVoice = false
                                                }
                                            }
                                        },
                                        enabled = !isTestingVoice,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (isTestingVoice) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Memutar pratinjau suara...", fontSize = 12.sp)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Tes Suara Gepy", fontSize = 12.sp)
                                        }
                                    }
                                    voicePreviewStatus?.let { (ok, statusText) ->
                                        Text(
                                            text = statusText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (ok) GepyGreenAccent else GepyRedDanger
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Footer Aksi 1-Baris Rapi
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(
                        onClick = onResetDefault,
                        colors = ButtonDefaults.textButtonColors(contentColor = GepyRedDanger),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("Reset", fontSize = 12.sp, maxLines = 1)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Batal", fontSize = 12.sp, maxLines = 1)
                    }
                    if (geminiKey.trim().isNotEmpty() ||
                        geminiSecondaryKey.trim().isNotEmpty() ||
                        EnvConfig.isGeminiApiKeyFormat(customKey)
                    ) {
                        OutlinedButton(
                            onClick = { onSave(buildUpdatedByokSettings(), true) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Live", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    Button(
                        onClick = { onSave(buildUpdatedByokSettings(), false) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Simpan", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaybookMemoryDialog(
    orchestrator: GepyOrchestrator,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<PlaybookTransition>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            isLoading = true
            entries = orchestrator.memoryPort.getAllPlaybookEntries()
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        reload()
        onDispose {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Layers,
                    contentDescription = null,
                    tint = GepyGreenAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Memori Playbook (${entries.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Jalur deterministik yang dipelajari Gepy dari tugas yang berhasil diverifikasi. Memori yang salah atau tidak diinginkan dapat dihapus sewaktu-waktu.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isLoading) {
                    Text("Memuat data playbook...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (entries.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Belum ada pola Playbook yang tersimpan. Gepy akan mempelajari jalur baru saat tugas diverifikasi sukses.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    for (entry in entries) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (entry.failureCount > 0 || entry.isDisabled) GepyRedDanger.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${entry.packageName} • ${entry.subgoalIntent}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Elemen: ${entry.targetResourceId} (${entry.actionType})",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Sukses: ${entry.successCount}x • Gagal: ${entry.failureCount}x" +
                                            if (entry.isDisabled) " • [DINONAKTIFKAN]" else "",
                                        fontSize = 10.5.sp,
                                        color = if (entry.isDisabled) GepyRedDanger else GepyGreenAccent
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            orchestrator.memoryPort.deletePlaybookEntry(entry.id)
                                            reload()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Hapus",
                                        tint = GepyRedDanger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        },
        dismissButton = {
            if (entries.isNotEmpty()) {
                TextButton(
                    onClick = {
                        scope.launch {
                            orchestrator.memoryPort.clearAllPlaybookMemory()
                            reload()
                        }
                    }
                ) {
                    Text("Hapus Semua", color = GepyRedDanger)
                }
            }
        }
    )
}

private enum class TemporalMemoryFilter(val label: String) {
    ACTIVE("Aktif"),
    INSIGHT("Wawasan"),
    TIME_BOUND("Berbatas Waktu"),
    HISTORY("Riwayat")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemporalMemoryDialog(
    userMemoryManager: UserMemoryManager,
    onDismiss: () -> Unit,
    onExportVault: () -> Unit = {},
    onImportVault: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var allFacts by remember { mutableStateOf<List<TemporalMemoryFactEntity>>(emptyList()) }
    var filter by remember { mutableStateOf(TemporalMemoryFilter.ACTIVE) }
    var consolidationStatus by remember { mutableStateOf<String?>(null) }
    var isConsolidating by remember { mutableStateOf(false) }

    fun reload() {
        allFacts = userMemoryManager.getAllTemporalFactsSnapshot()
    }

    DisposableEffect(Unit) {
        reload()
        onDispose {}
    }

    val nowMs = System.currentTimeMillis()
    val visibleFacts = when (filter) {
        TemporalMemoryFilter.ACTIVE -> allFacts.filter { TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) }
        TemporalMemoryFilter.INSIGHT -> allFacts.filter {
            TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) &&
                (it.category.equals("insight", ignoreCase = true) || it.source == "nightly_reflection")
        }
        TemporalMemoryFilter.TIME_BOUND -> allFacts.filter {
            TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) && it.validUntilMs != null
        }
        TemporalMemoryFilter.HISTORY -> allFacts.filter { !TemporalKnowledgeGraph.isFactCurrentlyValid(it, nowMs) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Layers,
                    contentDescription = null,
                    tint = GepyGreenAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Memori Temporal (${allFacts.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Card Cadangan Google Drive & File Mandiri
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CloudUpload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Cadangan Cloud (Google Drive / Berkas)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Ekspor seluruh memorimu ke berkas JSON mandiri di Google Drive. Memori tetap aman dan gampang dipulihkan kalau aplikasi dipasang ulang.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onExportVault,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cadangkan", fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = onImportVault,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pulihkan", fontSize = 11.5.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (!isConsolidating) {
                                    isConsolidating = true
                                    scope.launch {
                                        try {
                                            val report = userMemoryManager.runNightlyCognitiveConsolidation(
                                                nowMs = System.currentTimeMillis(),
                                                allowCloudLlm = true
                                            )
                                            consolidationStatus = report.summaryMessage
                                            reload()
                                        } finally {
                                            isConsolidating = false
                                        }
                                    }
                                }
                            },
                            enabled = !isConsolidating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConsolidating) "Mengkonsolidasi Memori..." else "Konsolidasi dan Refleksi Otak",
                                fontSize = 11.5.sp
                            )
                        }
                        consolidationStatus?.let { statusMsg ->
                            Text(
                                text = statusMsg,
                                fontSize = 11.sp,
                                color = GepyGreenAccent,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Text(
                    text = "Disimpan lokal di HP (Room v6) dengan Kurva Lupa Ebbinghaus, Spaced Repetition, dan konsolidasi otomatis saat cas malam.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in TemporalMemoryFilter.entries) {
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label, fontSize = 12.sp) }
                        )
                    }
                }

                if (visibleFacts.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Belum ada memori pada kategori ini.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    for (fact in visibleFacts) {
                        val isValid = TemporalKnowledgeGraph.isFactCurrentlyValid(fact, nowMs)
                        val retentionPct = EbbinghausDecayEngine.computeRetentionPercent(fact, nowMs)
                        val importance = EbbinghausDecayEngine.computeImportanceScore(fact)
                        val statusLine = when {
                            !isValid && fact.supersededById != null ->
                                "Digantikan • ${TemporalKnowledgeGraph.formatDate(fact.validUntilMs ?: fact.updatedAtMs)}"
                            !isValid ->
                                "Kedaluwarsa • ${TemporalKnowledgeGraph.formatDate(fact.validUntilMs ?: fact.updatedAtMs)}"
                            fact.validUntilMs != null ->
                                "Berlaku s.d. ${TemporalKnowledgeGraph.formatDate(fact.validUntilMs)} ${TemporalKnowledgeGraph.formatTime(fact.validUntilMs)} • Daya Ingat $retentionPct% (★$importance/10)"
                            else -> "Sejak ${TemporalKnowledgeGraph.formatDate(fact.validFromMs)} • Daya Ingat $retentionPct% (★$importance/10 • ${fact.hitCount}x)"
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isValid) MaterialTheme.colorScheme.outline else GepyAmberWarning.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fact.factText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${fact.subject}.${fact.predicate} = ${fact.objectValue}",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = statusLine,
                                        fontSize = 10.5.sp,
                                        color = if (isValid) GepyGreenAccent else GepyAmberWarning
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            userMemoryManager.deleteTemporalFactById(fact.id)
                                            reload()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Hapus",
                                        tint = GepyRedDanger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

@Composable
internal fun VoiceDatasetInspectorDialog(
    voiceCacheManager: VoiceAudioCacheManager,
    onStatsChanged: () -> Unit,
    onExportVoiceVault: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var clips by remember { mutableStateOf(voiceCacheManager.getAllCachedClipsSnapshot()) }
    var searchQuery by remember { mutableStateOf("") }
    var playingHash by remember { mutableStateOf<String?>(null) }
    var activePlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    val previewAudioFocus = remember(context) { AndroidVoiceAudioFocusCoordinator(context) }

    fun stopPreviewPlayer() {
        try {
            activePlayer?.release()
        } catch (_: Throwable) {
        }
        activePlayer = null
        playingHash = null
        previewAudioFocus.abandonSpeechFocus()
    }

    fun reloadClips() {
        clips = voiceCacheManager.getAllCachedClipsSnapshot()
        onStatsChanged()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPreviewPlayer()
        }
    }

    val filteredClips = remember(clips, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) clips
        else clips.filter {
            it.text.contains(q, ignoreCase = true) ||
                it.voiceId.contains(q, ignoreCase = true) ||
                it.modelId.contains(q, ignoreCase = true) ||
                it.fileName.contains(q, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = {
            stopPreviewPlayer()
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = GepyGreenAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Data suara dan dataset (${clips.size} klip)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Daftar pasangan transkrip dan berkas audio MP3 (44.1 kHz) tersimpan lokal. Ekspor (.zip) menyertakan manifest.json serta berkas .mp3.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                FilledTonalButton(
                    onClick = onExportVoiceVault,
                    enabled = clips.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ekspor Dataset Suara (.zip) ke Drive", fontSize = 12.sp)
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    placeholder = { Text("Cari kalimat, model, atau voice ID...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )

                if (filteredClips.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (clips.isEmpty()) {
                                "Belum ada klip suara tersimpan. Ucapkan perintah atau tes suara kloningan agar klip otomatis terekam di sini."
                            } else {
                                "Tidak ada klip suara yang cocok dengan pencarian."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    for (clip in filteredClips) {
                        val isPlayingThis = playingHash == clip.hash
                        val kbSize = String.format(Locale.US, "%.1f KB", clip.sizeBytes / 1024.0)
                        val dateLabel = TemporalKnowledgeGraph.formatDate(clip.createdAtMs)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isPlayingThis) GepyGreenAccent else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (isPlayingThis) {
                                            stopPreviewPlayer()
                                        } else {
                                            stopPreviewPlayer()
                                            val file = voiceCacheManager.resolveClipFile(clip)
                                            if (file != null) {
                                                val granted = previewAudioFocus.acquireSpeechFocus { focusEv ->
                                                    when (focusEv) {
                                                        VoiceAudioFocusEvent.LOSS,
                                                        VoiceAudioFocusEvent.LOSS_TRANSIENT -> stopPreviewPlayer()
                                                        VoiceAudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK -> {
                                                            try {
                                                                activePlayer?.setVolume(0.25f, 0.25f)
                                                            } catch (_: Throwable) {
                                                            }
                                                        }
                                                        VoiceAudioFocusEvent.GAIN -> {
                                                            try {
                                                                activePlayer?.setVolume(1.0f, 1.0f)
                                                            } catch (_: Throwable) {
                                                            }
                                                        }
                                                    }
                                                }
                                                if (!granted) {
                                                    Toast.makeText(
                                                        context,
                                                        "Fokus audio sedang dipakai aplikasi/panggilan lain.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else try {
                                                    val mp = android.media.MediaPlayer().apply {
                                                        setAudioAttributes(
                                                            android.media.AudioAttributes.Builder()
                                                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                                                .build()
                                                        )
                                                        setDataSource(file.absolutePath)
                                                        setOnCompletionListener {
                                                            stopPreviewPlayer()
                                                        }
                                                        setOnErrorListener { _, _, _ ->
                                                            stopPreviewPlayer()
                                                            true
                                                        }
                                                        prepare()
                                                        start()
                                                    }
                                                    activePlayer = mp
                                                    playingHash = clip.hash
                                                } catch (e: Throwable) {
                                                    stopPreviewPlayer()
                                                    Toast.makeText(
                                                        context,
                                                        "Gagal memutar klip: ${e.message}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isPlayingThis) Icons.Rounded.StopCircle else Icons.Rounded.GraphicEq,
                                        contentDescription = if (isPlayingThis) "Hentikan" else "Putar",
                                        tint = if (isPlayingThis) GepyAmberWarning else GepyGreenAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = clip.text.ifBlank { "(Tanpa transkrip)" },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${clip.fileName.take(14)}...mp3 • $kbSize • $dateLabel",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val stabFmt = VoiceAudioCacheManager.normalizeSliderValue(clip.stability)
                                    val simFmt = VoiceAudioCacheManager.normalizeSliderValue(clip.similarityBoost)
                                    Text(
                                        text = "Voice: ${clip.voiceId} • ${clip.modelId} • S:$stabFmt / Sim:$simFmt",
                                        fontSize = 10.sp,
                                        color = GepyGreenAccent
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (isPlayingThis) stopPreviewPlayer()
                                        voiceCacheManager.deleteClip(clip.voiceId, clip.hash)
                                        reloadClips()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Hapus klip",
                                        tint = GepyRedDanger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    stopPreviewPlayer()
                    onDismiss()
                }
            ) {
                Text("Tutup")
            }
        }
    )
}
