package com.teknologigawai.gepy.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teknologigawai.gepy.R
import com.teknologigawai.gepy.core.accessibility.GepyFeedbackPattern
import com.teknologigawai.gepy.core.accessibility.HapticFeedbackManager
import com.teknologigawai.gepy.core.auth.GoogleAuthManager
import com.teknologigawai.gepy.core.auth.GoogleAuthResult
import com.teknologigawai.gepy.core.memory.UserMemoryManager
import com.teknologigawai.gepy.core.network.CloudflareGatewayClient
import com.teknologigawai.gepy.core.ports.VoiceHudPort
import kotlinx.coroutines.launch

/**
 * Layar Pembuka & Autentikasi Pengguna (*Welcome & One-Tap Google Sign-In Screen*).
 *
 * Dirancang responsif dengan prioritas kendali suara (*Voice-First Personal AI Agent*):
 * - Otomatis menyapa pengguna via Text-To-Speech saat layar pertama kali dibuka.
 * - Tombol raksasa One-Tap Google Sign-In yang terintegrasi dengan Android Credential Manager.
 * - Opsi "Lewati untuk Sementara (Mode Tamu)" agar pengguna tidak dipaksa login jika ingin segera mencoba.
 * - Umpan balik haptik dan konfirmasi suara instan saat proses login berhasil.
 */
@Composable
fun WelcomeAuthScreen(
    authManager: GoogleAuthManager,
    gatewayClient: CloudflareGatewayClient,
    memoryManager: UserMemoryManager,
    voiceHudPort: VoiceHudPort,
    hapticManager: HapticFeedbackManager,
    onAuthCompleted: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Sapaan suara otomatis saat pertama kali layar pembuka ditampilkan
    LaunchedEffect(Unit) {
        voiceHudPort.finishAndSpeak(
            "Halo, selamat datang di Gepy! " +
                "Masuk pakai Akun Google biar Gepy bisa terus inget nama dan preferensimu, " +
                "atau ketuk Lewati kalau mau coba dulu sebagai tamu."
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo Resmi Gepy
            GepyBrandEmblem(size = 108.dp)

            Spacer(modifier = Modifier.height(22.dp))

            // Judul Utama
            Text(
                text = "Selamat Datang di Gepy",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Asisten AI pribadi dan otomasi Android",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Kartu Informasi Manfaat Login (Memori Persisten)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    FeatureHighlightRow(
                        icon = Icons.Rounded.Psychology,
                        title = "Memori Pintar Antar-Panggilan",
                        description = "Gepy selalu mengingat nama, preferensi, dan hal penting buatmu meski panggilan suara ditutup dan disambung lagi."
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FeatureHighlightRow(
                        icon = Icons.Rounded.Security,
                        title = "Aman dan Terlindungi",
                        description = "Datamu tersimpan aman dan terisolasi di edge cloud, dan bisa kamu hapus kapan saja lewat menu Pengaturan."
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Pesan Error jika gagal
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Tombol 1: Masuk dengan Google One-Tap
            Button(
                onClick = {
                    if (isSigningIn) return@Button
                    isSigningIn = true
                    errorMessage = null
                    scope.launch {
                        try {
                            when (val res = authManager.signIn(context)) {
                                is GoogleAuthResult.Success -> {
                                    val authRes = gatewayClient.authenticateWithGoogle(res.idToken)
                                    if (authRes.isSuccess) {
                                        val data = authRes.getOrThrow()
                                        authManager.saveSession(data.user, data.sessionToken)
                                        memoryManager.setUserDisplayName(data.user.displayName)
                                        hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                        voiceHudPort.finishAndSpeak("Berhasil masuk sebagai ${data.user.displayName}. Memori Cloud aktif.")
                                    } else {
                                        authManager.saveSession(res.user, "offline_session_token")
                                        memoryManager.setUserDisplayName(res.user.displayName)
                                        hapticManager.triggerFeedback(GepyFeedbackPattern.STEP_SUCCESS)
                                        voiceHudPort.finishAndSpeak("Berhasil masuk sebagai ${res.user.displayName}.")
                                    }
                                    onAuthCompleted()
                                }
                                is GoogleAuthResult.Cancelled -> {
                                    // Pengguna membatalkan dialog One-Tap
                                }
                                is GoogleAuthResult.Failure -> {
                                    errorMessage = res.message
                                    hapticManager.triggerFeedback(GepyFeedbackPattern.ERROR_OR_INTERRUPTED)
                                    voiceHudPort.finishAndSpeak("Gagal masuk: ${res.message}")
                                }
                            }
                        } finally {
                            isSigningIn = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .semantics {
                        contentDescription = "Tombol Masuk dengan Akun Google. Masuk untuk mengaktifkan memori jangka panjang."
                    },
                shape = RoundedCornerShape(16.dp),
                enabled = !isSigningIn
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Menghubungkan...", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_google_logo),
                            contentDescription = "Google",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Masuk dengan Akun Google",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tombol 2: Lewati untuk Sementara (Mode Tamu)
            OutlinedButton(
                onClick = {
                    memoryManager.hasSkippedOpeningAuth = true
                    scope.launch {
                        voiceHudPort.finishAndSpeak("Oke, lanjut sebagai tamu dulu ya. Kamu tetap bisa masuk kapan saja lewat menu Pengaturan.")
                    }
                    onSkip()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .semantics {
                        contentDescription = "Tombol Lewati untuk Sementara. Masuk sebagai tamu tanpa akun Google."
                    },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = "Lewati untuk Sementara (Mode Tamu)",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
private fun FeatureHighlightRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}
