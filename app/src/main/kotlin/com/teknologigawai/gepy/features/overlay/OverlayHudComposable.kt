package com.teknologigawai.gepy.features.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import com.teknologigawai.gepy.features.voice.OverlayHudActivityKind
import com.teknologigawai.gepy.features.voice.OverlayHudState
import com.teknologigawai.gepy.features.voice.OverlayVisualMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Palet Kaca Bening Transparan (Clear Frosted Liquid Glass ala Dynamic Island)
private val ClearFrostedGlass = Color(0x3D111522)      // ~24% translucent tinted clear glass
private val ClearFrostedCard = Color(0x4D151A2B)       // ~30% translucent clear card panel
private val GlassSpecularTop = Color(0x99FFFFFF)       // Pantulan bevel kristal 60% putih
private val GlassSpecularMid = Color(0x35FFFFFF)       // Pembiasan cahaya tengah
private val GlassSpecularBottom = Color(0x0EFFFFFF)    // Refleksi bawah halus
private val GlassSpecularRim = Color(0x2EFFFFFF)       // Garis tepi kaca 1.5dp

private val EmeraldOnline = Color(0xFF10B981)
private val HudAmberWarning = Color(0xFFF59E0B)
private val HudRedDanger = Color(0xFFEF4444)
private val HudTextSecondary = Color(0xFFA1A1AA)

/**
 * Modifier kustom untuk menghasilkan efek Kaca Bening Cair (Frosted Liquid Glass)
 * ala iPhone Dynamic Island dengan kilau refleksi bevel atas halus (1.5dp specular highlight).
 */
private fun Modifier.dynamicIslandGlass(
    shape: Shape = RoundedCornerShape(26.dp),
    borderWidth: Dp = 1.5.dp
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(
                Color(0x4A182030),
                ClearFrostedCard,
                Color(0x330F131E)
            )
        )
    )
    .border(
        width = borderWidth,
        brush = Brush.verticalGradient(
            colors = listOf(
                GlassSpecularTop,
                GlassSpecularMid,
                GlassSpecularBottom,
                GlassSpecularRim
            )
        ),
        shape = shape
    )

/**
 * Garis Lengkung Pembiasan Cahaya Optik (Optical Caustic Arc)
 * Menghasilkan refleksi prisma internal kaca cembung halus di bagian atas kapsul (merujuk foto referensi 1 & 3).
 */
@Composable
private fun OpticalCausticArc(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.9f)
            quadraticTo(w * 0.5f, 0f, w * 0.78f, h * 0.9f)
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(
                    Color.Transparent,
                    Color(0x25FFFFFF),
                    Color(0x75FFFFFF),
                    Color(0x25FFFFFF),
                    Color.Transparent
                )
            ),
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Antarmuka Jetpack Compose untuk Jendela Mengambang (*Floating Overlay HUD*) Gepy Native.
 * Didesain ulang 100% sebagai **iPhone Dynamic Island Clear Liquid Glass**:
 * - Kaca bening transparan dengan pantulan specular bevel kristal.
 * - Refleksi cahaya bevel kaca realistis tanpa efek pelangi AI slop.
 * - Gestur ganda: Ketuk 1x untuk STT Cepat, Tekan Tahan (Long-Press) untuk Gepy Live Call.
 */
@Composable
fun OverlayHudComposable(
    hudState: OverlayHudState,
    onMicTap: () -> Unit,
    onMicLongPress: () -> Unit = {},
    onCancelListen: () -> Unit,
    onDisambiguationSelect: (String) -> Unit,
    onHitlDecision: (Boolean) -> Unit,
    onWhisperAction: (String) -> Unit = {},
    onWhisperDismiss: () -> Unit = {},
    onDragDelta: (Float, Float) -> Unit = { _, _ -> }
) {
    when (hudState.mode) {
        OverlayVisualMode.IDLE -> IdleCircularBubble(
            onMicTap = onMicTap,
            onMicLongPress = onMicLongPress,
            onDragDelta = onDragDelta
        )

        OverlayVisualMode.LISTENING,
        OverlayVisualMode.THINKING -> ActiveGlassProcessBubble(
            hudState = hudState,
            onCancelListen = onCancelListen,
            onDragDelta = onDragDelta
        )

        OverlayVisualMode.DISAMBIGUATION -> DisambiguationCard(
            questionHeadline = hudState.headline,
            options = hudState.options,
            onSelect = onDisambiguationSelect,
            onCancel = onCancelListen
        )

        OverlayVisualMode.HITL_CONFIRM -> HitlConfirmationCard(
            headline = hudState.headline,
            summaryPrompt = hudState.subtitle,
            onDecision = onHitlDecision,
            onDragDelta = onDragDelta
        )

        OverlayVisualMode.PROACTIVE_WHISPER -> ProactiveWhisperCard(
            headline = hudState.headline,
            subtitle = hudState.subtitle,
            actionLabel = hudState.whisperActionLabel,
            onActionTap = { onWhisperAction(hudState.whisperGoalPayload) },
            onDismiss = onWhisperDismiss
        )
    }
}

/**
 * Komponen Animasi Orb Kaca Murni (Pure Compose Liquid Glow Orb).
 * Dibuat 100% menggunakan native Jetpack Compose tanpa memuat file asset JSON eksternal,
 * sehingga bebas tabrakan visual (zero collision) dengan gelembung mengambang (*bubble up*).
 */
@Composable
fun SiriLottieOrb(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    speed: Float = 1.0f
) {
    val transition = rememberInfiniteTransition(label = "pureComposeOrb")
    val scale by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween((1200 / speed).toInt().coerceAtLeast(400), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbScale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween((1200 / speed).toInt().coerceAtLeast(400), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbAlpha"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                if (isPlaying) {
                    scaleX = scale
                    scaleY = scale
                }
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38BDF8).copy(alpha = alpha),
                        Color(0xFF818CF8).copy(alpha = alpha * 0.6f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
        )
    }
}

/**
 * Gelembung Kaca Bening Siaga (Clear Frosted Liquid Glass Bubble):
 * Berupa orb bulat kaca bening 60dp x 60dp dengan kilau bevel kristal,
 * pantulan optik caustic glare, mikrofon monokrom putih bersih dengan aura bernapas halus,
 * dan titik hijau status siaga di sudut kanan bawah.
 *
 * Murni 100% Jetpack Compose tanpa memuat asset eksternal (menghindari tabrakan visual).
 * Mendukung gestur ganda: Ketuk 1x untuk STT Cepat, Tekan Tahan (Long-Press ~600ms) untuk Gepy Live Call.
 */
@Composable
private fun IdleCircularBubble(
    onMicTap: () -> Unit,
    onMicLongPress: () -> Unit,
    onDragDelta: (Float, Float) -> Unit
) {
    val currentOnMicTap by rememberUpdatedState(onMicTap)
    val currentOnMicLongPress by rememberUpdatedState(onMicLongPress)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)

    // Animasi denyut aura kaca bernapas halus (subtle breathing pulse)
    val breathTransition = rememberInfiniteTransition(label = "bubbleBreath")
    val breathAlpha by breathTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathAlpha"
    )
    val breathScale by breathTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )

    Box(
        modifier = Modifier
            .size(60.dp)
            .semantics {
                contentDescription = "Gepy Voice Assistant. Ketuk satu kali untuk perintah suara cepat, tekan tahan untuk panggilan Gepy Live."
                role = Role.Button
                onClick {
                    currentOnMicTap()
                    true
                }
                onLongClick {
                    currentOnMicLongPress()
                    true
                }
            }
            .pointerInput(Unit) {
                val dragSlopPx = 18.dp.toPx()
                val longPressTimeoutMs = 550L
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        var isDragging = false
                        var longPressTriggered = false
                        var accumulatedOffset = Offset.Zero

                        val longPressJob = launch {
                            delay(longPressTimeoutMs)
                            if (!isDragging) {
                                longPressTriggered = true
                                currentOnMicLongPress()
                            }
                        }

                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break
                            pointerId = change.id

                            if (!change.pressed) {
                                change.consume()
                                break
                            }

                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                if (!isDragging) {
                                    accumulatedOffset += delta
                                    if (accumulatedOffset.getDistance() > dragSlopPx) {
                                        isDragging = true
                                        longPressJob.cancel()
                                        currentOnDragDelta(accumulatedOffset.x, accumulatedOffset.y)
                                    }
                                } else {
                                    currentOnDragDelta(delta.x, delta.y)
                                }
                                change.consume()
                            }
                        }

                        longPressJob.cancel()
                        if (!isDragging && !longPressTriggered) {
                            currentOnMicTap()
                        }
                    }
                }
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0x52222D42),
                        ClearFrostedGlass,
                        Color(0x2E0D111A)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassSpecularTop,
                        GlassSpecularMid,
                        GlassSpecularBottom,
                        GlassSpecularRim
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Pantulan lengkung optik kaca atas (Optical Caustic Glare)
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 18.dp)
                .align(Alignment.TopCenter)
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x55FFFFFF),
                            Color(0x15FFFFFF),
                            Color.Transparent
                        )
                    )
                )
        )

        // Radial glow sphere di dalam kaca (murni Compose, tanpa tabrakan batas luar)
        Box(
            modifier = Modifier
                .size(38.dp)
                .graphicsLayer {
                    scaleX = breathScale
                    scaleY = breathScale
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF38BDF8).copy(alpha = breathAlpha * 0.45f), // Soft cyan glow
                            Color(0xFF818CF8).copy(alpha = breathAlpha * 0.25f), // Soft indigo glow
                            Color.Transparent
                        )
                    )
                )
        )

        // Ikon Mikrofon Kaca Bersih (Pure White Mic Icon)
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Gepy Voice Assistant",
            tint = Color.White.copy(alpha = 0.95f),
            modifier = Modifier.size(24.dp)
        )

        // Titik Indikator Hijau Siaga Halus (Standby Status Dot)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 7.dp, end = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(EmeraldOnline)
                .border(0.75.dp, Color(0x99FFFFFF), CircleShape)
        )
    }
}

/**
 * Kartu Bubble-Up Terpadu Bergaya Dark Smoked Frosted Glass + Pil Status Cerah (Referensi Screenshot 4):
 * - Kartu kaca gelap asap dengan pantulan bezel specular halus di atas.
 * - Sisi kiri: Orb bercahaya Pure Compose cyan/mint + garis pemisah vertikal halus.
 * - Sisi kanan: Teks streaming 2-nada (*putih cerah* + *putih redup*) untuk:
 *   1. Proses berpikir Gepy (`THINKING`),
 *   2. Aksi yang sedang dijalankan (`EXECUTING`),
 *   3. Transkrip suara pengguna saat mendengarkan (`LISTENING` / `LIVE_LISTENING`),
 *   4. Teks output suara Gepy secara real-time saat Live Call (`LIVE_SPEAKING`).
 * - Di bawah kartu: Pil status putih bersih berisi label aktivitas dan tombol Stop (`■`) saat interaktif.
 */
/**
 * Permukaan Kaca Gelap Asap (*Dark Smoked Frosted Glass*) bersama untuk kartu Bubble-Up aktif dan HITL.
 */
private fun Modifier.smokedGlassProcessCard(
    onDragDelta: ((Float, Float) -> Unit)? = null
): Modifier = this
    .widthIn(min = 256.dp, max = 336.dp)
    .shadow(
        elevation = 16.dp,
        shape = RoundedCornerShape(22.dp),
        ambientColor = Color(0x55000000),
        spotColor = Color(0x66000000)
    )
    .clip(RoundedCornerShape(22.dp))
    .background(
        Brush.verticalGradient(
            colors = listOf(
                Color(0xEB1A1D24),
                Color(0xE014161D),
                Color(0xD60F1116)
            )
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0x58FFFFFF),
                Color(0x1CFFFFFF),
                Color(0x28FFFFFF)
            )
        ),
        shape = RoundedCornerShape(22.dp)
    )
    .let { base ->
        if (onDragDelta != null) {
            base.pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
        } else {
            base
        }
    }

@Composable
private fun ActiveGlassProcessBubble(
    hudState: OverlayHudState,
    onCancelListen: () -> Unit,
    onDragDelta: (Float, Float) -> Unit
) {
    val pillLabel = hudState.statusPillLabel.ifBlank { hudState.activityKind.defaultPillLabel }
    val semanticText = listOf(pillLabel, hudState.headline, hudState.subtitle)
        .filter { it.isNotBlank() }
        .joinToString(". ")
    val canDragOrClick = !(hudState.mode == OverlayVisualMode.THINKING && !hudState.isLiveCallActive)
    val cardModifier = Modifier.smokedGlassProcessCard(
        onDragDelta = if (canDragOrClick) onDragDelta else null
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .semantics {
                contentDescription = semanticText
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Box(modifier = cardModifier) {
            OpticalCausticArc(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 28.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlowingGlassSphereOrb(activityKind = hudState.activityKind)

                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(42.dp)
                        .background(Color(0x2EFFFFFF))
                )

                Text(
                    text = buildTwoToneBubbleText(hudState),
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(9.dp))

        Box(
            modifier = Modifier
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(50),
                    ambientColor = Color(0x30000000),
                    spotColor = Color(0x40000000)
                )
                .clip(RoundedCornerShape(50))
                .background(Color(0xF7FFFFFF))
                .border(0.8.dp, Color(0x33D4D4D8), RoundedCornerShape(50))
                .padding(
                    start = 15.dp,
                    end = if (hudState.showStopButton) 7.dp else 15.dp,
                    top = 6.dp,
                    bottom = 6.dp
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPillPulseDot(activityKind = hudState.activityKind)

                Text(
                    text = pillLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF52525B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (hudState.showStopButton) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE4E4E7))
                            .semantics {
                                role = Role.Button
                                contentDescription = if (hudState.activityKind == OverlayHudActivityKind.LIVE_SPEAKING) {
                                    "Hentikan giliran bicara Gepy"
                                } else {
                                    "Hentikan"
                                }
                            }
                            .clickable { onCancelListen() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF71717A))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlowingGlassSphereOrb(activityKind: OverlayHudActivityKind) {
    val speedMs = when (activityKind) {
        OverlayHudActivityKind.THINKING,
        OverlayHudActivityKind.EXECUTING -> 900
        OverlayHudActivityKind.LIVE_SPEAKING -> 750
        else -> 1500
    }
    val transition = rememberInfiniteTransition(label = "glassSphereOrb")
    val pulseScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(speedMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulseScale"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.36f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(speedMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbGlowAlpha"
    )

    val (outerColor, coreTopColor, coreBottomColor) = when (activityKind) {
        OverlayHudActivityKind.LIVE_SPEAKING -> Triple(
            Color(0xFF38BDF8),
            Color(0xFFA5F3FC),
            Color(0xFF0284C7)
        )
        OverlayHudActivityKind.THINKING,
        OverlayHudActivityKind.EXECUTING -> Triple(
            Color(0xFF60A5FA),
            Color(0xFFE0F2FE),
            Color(0xFF2563EB)
        )
        OverlayHudActivityKind.CONNECTING -> Triple(
            HudAmberWarning,
            Color(0xFFFEF3C7),
            Color(0xFFD97706)
        )
        else -> Triple(
            Color(0xFF2DD4BF),
            Color(0xFFCCFBF1),
            Color(0xFF0D9488)
        )
    }

    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            outerColor.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(21.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            coreTopColor,
                            outerColor,
                            coreBottomColor
                        )
                    )
                )
                .border(0.6.dp, Color(0x88FFFFFF), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp, start = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.75f))
            )
        }
    }
}

@Composable
private fun StatusPillPulseDot(activityKind: OverlayHudActivityKind) {
    val transition = rememberInfiniteTransition(label = "pillDot")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pillDotAlpha"
    )
    val dotColor = when (activityKind) {
        OverlayHudActivityKind.LIVE_SPEAKING -> Color(0xFF0EA5E9)
        OverlayHudActivityKind.THINKING,
        OverlayHudActivityKind.EXECUTING -> Color(0xFF2563EB)
        OverlayHudActivityKind.CONNECTING -> HudAmberWarning
        else -> EmeraldOnline
    }
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(dotColor.copy(alpha = alpha))
    )
}

internal fun tailSliceForOverlayBubble(raw: String, maxChars: Int = 145): String {
    val clean = raw.replace(Regex("""[ \t\r\n]+"""), " ").trim()
    if (clean.length <= maxChars) return clean
    val slice = clean.takeLast(maxChars - 3)
    val firstSpace = slice.indexOf(' ')
    val aligned = if (firstSpace in 1..20) slice.substring(firstSpace + 1) else slice
    return "...$aligned"
}

internal fun buildTwoToneBubbleText(hudState: OverlayHudState): AnnotatedString {
    val headline = hudState.headline.replace(Regex("""\s+"""), " ").trim()
    val rawSubtitle = hudState.subtitle.replace(Regex("""\s+"""), " ").trim()

    val primaryStyle = SpanStyle(
        color = Color.White,
        fontWeight = FontWeight.Medium
    )
    val secondaryStyle = SpanStyle(
        color = Color.White.copy(alpha = 0.56f),
        fontWeight = FontWeight.Normal
    )

    return buildAnnotatedString {
        when (hudState.activityKind) {
            OverlayHudActivityKind.THINKING,
            OverlayHudActivityKind.EXECUTING -> {
                val lead = headline.ifBlank { hudState.activityKind.defaultPillLabel.trimEnd('.') }
                val detail = tailSliceForOverlayBubble(rawSubtitle, 130)
                withStyle(primaryStyle) { append(lead) }
                if (detail.isNotBlank() && !detail.equals(lead, ignoreCase = true)) {
                    withStyle(secondaryStyle) { append(" — $detail") }
                }
            }
            OverlayHudActivityKind.CONNECTING -> {
                val lead = headline.ifBlank { "Menyambung Gepy Live" }
                withStyle(primaryStyle) { append(lead) }
                if (rawSubtitle.isNotBlank()) {
                    withStyle(secondaryStyle) { append(" — ${tailSliceForOverlayBubble(rawSubtitle, 115)}") }
                }
            }
            OverlayHudActivityKind.LIVE_SPEAKING,
            OverlayHudActivityKind.LIVE_LISTENING,
            OverlayHudActivityKind.LISTENING,
            OverlayHudActivityKind.IDLE -> {
                val content = tailSliceForOverlayBubble(
                    rawSubtitle.ifBlank { headline },
                    145
                )
                if (content.isBlank()) {
                    withStyle(primaryStyle) { append("Siap mendengarkan") }
                    withStyle(secondaryStyle) { append(" — Silakan bicara...") }
                } else {
                    val splitIdx = findNaturalTwoToneSplitIndex(content)
                    if (splitIdx in 1 until content.length) {
                        withStyle(primaryStyle) { append(content.substring(0, splitIdx)) }
                        withStyle(secondaryStyle) { append(content.substring(splitIdx)) }
                    } else {
                        withStyle(primaryStyle) { append(content) }
                    }
                }
            }
        }
    }
}

private fun findNaturalTwoToneSplitIndex(text: String): Int {
    if (text.length <= 26) return -1
    val dashIdx = text.indexOf(" — ")
    if (dashIdx in 4 until (text.length - 4)) return dashIdx
    val hyphenIdx = text.indexOf(" - ")
    if (hyphenIdx in 4 until (text.length - 4)) return hyphenIdx
    val targetIdx = (text.length * 0.52f).toInt().coerceIn(14, text.length - 8)
    val spaceAfter = text.indexOf(' ', targetIdx)
    return if (spaceAfter in 8 until text.length - 2) spaceAfter else text.lastIndexOf(' ', targetIdx)
}

/**
 * Kartu Pertanyaan Klarifikasi / Disambiguasi Gaya Kaca Obsidian.
 */
@Composable
private fun DisambiguationCard(
    questionHeadline: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(340.dp)
            .heightIn(max = 280.dp),
        color = ClearFrostedCard,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(GlassSpecularTop, GlassSpecularBottom))
        ),
        shadowElevation = 14.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = null,
                    tint = HudAmberWarning,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = questionHeadline,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (opt in options) {
                    OutlinedButton(
                        onClick = { onSelect(opt) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF27272E)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF13141B),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = opt,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = HudTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text(
                        text = "Batalkan",
                        color = HudTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Kartu Konfirmasi Tindakan Berisiko (Human-in-the-Loop Gatekeeper).
 * Mengikuti desain terpadu Bubble-Up *Dark Smoked Frosted Glass* di atas
 * dan kapsul aksi putih bersih (*Floating Capsule Pill Dock*) di bawahnya.
 */
@Composable
private fun HitlConfirmationCard(
    headline: String,
    summaryPrompt: String,
    onDecision: (Boolean) -> Unit,
    onDragDelta: (Float, Float) -> Unit = { _, _ -> }
) {
    val cleanHeadline = headline.trim().ifBlank { "Konfirmasi Aksi" }
    val cleanSummary = summaryPrompt.trim().ifBlank { "Periksa tindakan ini sebelum dilanjutkan." }
    val semanticText = "$cleanHeadline. $cleanSummary. Ketuk Batalkan atau Eksekusi."

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .semantics {
                contentDescription = semanticText
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Box(modifier = Modifier.smokedGlassProcessCard(onDragDelta = onDragDelta)) {
            OpticalCausticArc(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 28.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlowingGlassSphereOrb(activityKind = OverlayHudActivityKind.EXECUTING)

                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(42.dp)
                        .background(Color(0x2EFFFFFF))
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = cleanHeadline,
                        color = Color.White,
                        fontSize = 14.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = cleanSummary,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 13.5.sp,
                        lineHeight = 18.5.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(9.dp))

        Box(
            modifier = Modifier
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(50),
                    ambientColor = Color(0x30000000),
                    spotColor = Color(0x40000000)
                )
                .clip(RoundedCornerShape(50))
                .background(Color(0xF7FFFFFF))
                .border(0.8.dp, Color(0x33D4D4D8), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    onClick = { onDecision(false) },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFF4F4F5)
                ) {
                    Text(
                        text = "Batalkan",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF52525B),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }

                Surface(
                    onClick = { onDecision(true) },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF14161D)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Eksekusi",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * Kartu Bisikan Proaktif (*Dynamic Island Frosted Liquid Glass Whisper Card*).
 * Menampilkan saran relevan (resi pengiriman, ringkasan artikel web, peringatan baterai malam)
 * secara elegan tanpa suara yang mengagetkan pengguna.
 */
@Composable
private fun ProactiveWhisperCard(
    headline: String,
    subtitle: String,
    actionLabel: String,
    onActionTap: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(330.dp)
            .heightIn(min = 60.dp, max = 84.dp)
            .semantics {
                contentDescription = listOf(headline, subtitle, actionLabel).filter { it.isNotBlank() }.joinToString(". ")
                liveRegion = LiveRegionMode.Polite
            }
            .dynamicIslandGlass(
                shape = RoundedCornerShape(22.dp),
                borderWidth = 1.2.dp
            )
    ) {
        OpticalCausticArc(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SiriLottieOrb(
                modifier = Modifier.size(24.dp),
                isPlaying = true,
                speed = 0.8f
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = headline,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = HudTextSecondary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (actionLabel.isNotBlank()) {
                Surface(
                    onClick = onActionTap,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x3310B981),
                    border = BorderStroke(1.dp, Color(0xFF10B981))
                ) {
                    Text(
                        text = actionLabel,
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Tutup bisikan",
                    tint = HudTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1E1E1E)
@Composable
private fun OverlayHudPreviewIdle() {
    IdleCircularBubble(
        onMicTap = {},
        onMicLongPress = {},
        onDragDelta = { _, _ -> }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1E1E1E)
@Composable
private fun OverlayHudPreviewListening() {
    ActiveGlassProcessBubble(
        hudState = OverlayHudState(
            isVisible = true,
            mode = OverlayVisualMode.LISTENING,
            headline = "Kamu",
            subtitle = "Tolong carikan project github trending hari ini satu saja",
            activityKind = OverlayHudActivityKind.LISTENING,
            statusPillLabel = "Mendengarkan...",
            showStopButton = true
        ),
        onCancelListen = {},
        onDragDelta = { _, _ -> }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1E1E1E)
@Composable
private fun OverlayHudPreviewThinking() {
    ActiveGlassProcessBubble(
        hudState = OverlayHudState(
            isVisible = true,
            mode = OverlayVisualMode.THINKING,
            headline = "Membuka aplikasi",
            subtitle = "Membuka WhatsApp untuk mencari kontak Dika",
            activityKind = OverlayHudActivityKind.EXECUTING,
            statusPillLabel = "Membuka aplikasi...",
            showStopButton = false
        ),
        onCancelListen = {},
        onDragDelta = { _, _ -> }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1E1E1E)
@Composable
private fun OverlayHudPreviewLiveSpeaking() {
    ActiveGlassProcessBubble(
        hudState = OverlayHudState(
            isVisible = true,
            mode = OverlayVisualMode.LISTENING,
            headline = "Gepy Berbicara",
            subtitle = "Oke, aku sudah cek trending GitHub hari ini dan nemuin satu repo teratas untukmu.",
            isLiveCallActive = true,
            activityKind = OverlayHudActivityKind.LIVE_SPEAKING,
            statusPillLabel = "Gepy Berbicara...",
            showStopButton = true
        ),
        onCancelListen = {},
        onDragDelta = { _, _ -> }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFE5E7EB)
@Composable
private fun OverlayHudPreviewHitl() {
    HitlConfirmationCard(
        headline = "Konfirmasi Aksi (Book)",
        summaryPrompt = "Tarif GoRide Rp12.500 ke Universitas Alma Ata. Yakin mau lanjutin?",
        onDecision = {}
    )
}
