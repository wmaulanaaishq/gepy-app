package com.teknologigawai.gepy.ui

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
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teknologigawai.gepy.ui.theme.CyberpunkTheme
import com.teknologigawai.gepy.ui.theme.GepyGreenAccent

/**
 * Layar Pengungkapan Terbuka (*Prominent Disclosure Screen*) Layanan Aksesibilitas Android
 * yang diwajibkan secara tegas oleh Kebijakan Google Play Store (*Google Play Policy - Accessibility API*).
 *
 * Layar ini ditampilkan kepada pengguna SEBELUM meminta atau mengarahkan ke pengaturan
 * `AccessibilityService`, dengan rincian:
 * 1. Data apa yang diakses (Elemen UI & teks layar).
 * 2. Mengapa diakses (Asisten navigasi suara & otomasi UI Personal AI Agent hands-free).
 * 3. Bagaimana privasi dilindungi (Pemblokiran total m-Banking/PIN & zero penjualan data).
 */
@Composable
fun ProminentDisclosureScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Header Ikon & Judul
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(GepyGreenAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccessibilityNew,
                            contentDescription = null,
                            tint = GepyGreenAccent,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Keterbukaan Aksesibilitas",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Prominent Disclosure • Google Play Policy",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Gepy dirancang sebagai Personal AI Agent otonom untuk membantu pengguna mengoperasikan smartphone secara hands-free melalui perintah suara maupun teks, tanpa perlu mengetuk layar berulang kali.",
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(18.dp))

                DisclosureDetailCard(
                    icon = Icons.Rounded.Visibility,
                    iconTint = Color(0xFF06B6D4),
                    title = "1. Data yang Diakses Melalui Accessibility API",
                    description = "Gepy membaca elemen pohon tampilan layar (AccessibilityNodeInfo) yang sedang aktif untuk mengidentifikasi teks tombol, label kolom input, dan konteks aplikasi."
                )

                Spacer(modifier = Modifier.height(12.dp))

                DisclosureDetailCard(
                    icon = Icons.Rounded.AccessibilityNew,
                    iconTint = GepyGreenAccent,
                    title = "2. Tujuan dan Cara Penggunaan Data",
                    description = "Data tampilan layar digunakan secara instan untuk: (a) Merangkum atau membacakan isi layar secara hands-free, dan (b) Mengeksekusi ketukan/pengetikan teks sesuai perintahmu (contoh: 'Kirim pesan WhatsApp ke Budi')."
                )

                Spacer(modifier = Modifier.height(12.dp))

                DisclosureDetailCard(
                    icon = Icons.Rounded.Security,
                    iconTint = Color(0xFF10B981),
                    title = "3. Pemblokiran Ketat Aplikasi Keuangan dan PIN",
                    description = "Melalui sistem SafetyRules permanen, Gepy secara otomatis dan mutlak memblokir akses ke aplikasi m-Banking (BCA, Mandiri, BRI, BNI), e-Wallet (Dana, OVO, GoPay), serta kolom Kata Sandi/PIN/OTP."
                )

                Spacer(modifier = Modifier.height(12.dp))

                DisclosureDetailCard(
                    icon = Icons.Rounded.Lock,
                    iconTint = Color(0xFF8B5CF6),
                    title = "4. Privasi dan Kedaulatan Pengguna",
                    description = "Data UI layar hanya diproses sementara di memori perangkat (RAM) selama sesi perintah berlangsung. Data TIDAK PERNAH dijual, TIDAK dikumpulkan untuk profil iklan, dan TIDAK dibagikan kepada pihak ketiga."
                )

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Tombol Aksi Persetujuan Eksplisit
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GepyGreenAccent,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Saya Mengerti dan Izinkan",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        text = "Tolak dan Keluar",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclosureDetailCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProminentDisclosurePreview() {
    CyberpunkTheme {
        ProminentDisclosureScreen(
            onAccept = {},
            onDecline = {}
        )
    }
}
