package com.teknologigawai.gepy.core.wellness

import android.content.ClipboardManager
import android.content.Context
import com.teknologigawai.gepy.core.config.ProactivityLevel
import com.teknologigawai.gepy.core.logging.GepyLog
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Data model untuk bisikan kontekstual proaktif (*Proactive Whisper*) yang
 * akan ditampilkan pada Dynamic Island Liquid Glass HUD Gepy.
 */
data class ProactiveWhisper(
    val title: String,
    val subtitle: String,
    val actionLabel: String,
    val goalPayload: String,
    val priority: Int = 1
)

/**
 * Jenis entitas berharga yang terdeteksi secara cerdas dari teks Clipboard pengguna.
 */
sealed class DetectedClipEntity {
    data class CourierWaybill(val courierName: String, val waybillNumber: String) : DetectedClipEntity()
    data class WebArticle(val url: String) : DetectedClipEntity()
    data class PhoneNumber(val rawPhone: String, val normalizedWa: String) : DetectedClipEntity()
    data object None : DetectedClipEntity()
}

/**
 * Pengawas Konteks Proaktif Cerdas (**Co-Pilot Context Sentinel**) untuk **Gepy v5.9.0**:
 *
 * 1. **Clipboard Sentinel**: Mengobservasi `ClipboardManager` secara instan (`0 ms` overhead, 0 panggilan LLM).
 *    Mendeteksi nomor resi kurir (J&T, JNE, SiCepat, SPX, AnterAja, Pos, dll.), tautan artikel web,
 *    dan nomor kontak telepon untuk menawarkan bantuan tepat waktu.
 * 2. **Device State & Night Sentinel**: Memantau baterai lemah (< 18%) saat tidak diisi daya pada malam hari
 *    (>= 21:00) agar alarm pengguna tidak mati mendadak saat tidur.
 * 3. **Anti-Distraction Guard**: Secara ketat MENAHAN bisikan jika pengguna sedang memutar video layar penuh
 *    (YouTube/Netflix) atau bermain game, sehingga pengguna bebas dari rasa terganggu.
 * 4. **Proactivity Level Respect**: Hanya aktif ketika `ProactivityLevel != ZEN_PASSIVE`.
 */
class CoPilotContextSentinel(
    private var context: Context? = null,
    private val proactivityLevelProvider: () -> ProactivityLevel = { ProactivityLevel.CONTEXT_COPILOT },
    private val clockProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    var onWhisperAvailable: ((ProactiveWhisper) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CoPilotSentinel"

        /** Paket aplikasi media/game layar penuh yang dilarang diinterupsi oleh bisikan proaktif. */
        val IMMERSIVE_MEDIA_OR_GAMES: Set<String> = setOf(
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "com.mobile.legends",
            "com.miHoYo.GenshinImpact",
            "com.dts.freefireth",
            "com.pubg.imobile",
            "com.tencent.ig"
        )

        /** Paket keyboard/IME yang menandakan pengguna sedang sibuk mengetik pesan. */
        val KEYBOARD_IME_PACKAGES: Set<String> = setOf(
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey"
        )

        private val URL_REGEX = Regex(
            """^(https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?:/[^\s]*)?)$""",
            RegexOption.IGNORE_CASE
        )

        private val PHONE_NUMBER_REGEX = Regex(
            """^(?:\+62|62|08)[1-9][0-9]{7,11}$"""
        )

        private val RESI_KEYWORD_REGEX = Regex(
            """\b(?:resi|awb|waybill|no\.?\s*resi|nomor\s*resi)\s*[:#-]?\s*([A-Za-z0-9]{8,24})\b""",
            RegexOption.IGNORE_CASE
        )

        // Pola prefiks kode kurir populer di Indonesia
        private val SPX_REGEX = Regex("""\b(SPXID\d{9,14})\b""", RegexOption.IGNORE_CASE)
        private val SICEPAT_REGEX = Regex("""\b(00\d{10}|TKP01[A-Z0-9]{10}|SI[A-Z0-9]{12})\b""", RegexOption.IGNORE_CASE)
        private val JNT_REGEX = Regex("""\b(JP\d{10,12}|JX\d{10,12}|JZ\d{10,12}|JNA\d{10}|[0-9]{12})\b""", RegexOption.IGNORE_CASE)
        private val JNE_REGEX = Regex("""\b(JT\d{10}|CGK[A-Z0-9]{10,13}|SUB[A-Z0-9]{10,13}|SOC[A-Z0-9]{10,13}|JNE[A-Z0-9]{9,12})\b""", RegexOption.IGNORE_CASE)
        private val ANTERAJA_REGEX = Regex("""\b(1000\d{10})\b""")
        private val POS_INDO_REGEX = Regex("""\b(P[0-9]{11}|POS[0-9]{9,11})\b""", RegexOption.IGNORE_CASE)
    }

    private var lastEvaluatedClipHash: Int = 0
    private var lastBatteryWarningDateKey: String = ""
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** Menghubungkan context Android runtime dan memulai pengamatan clipboard. */
    fun attachContext(ctx: Context) {
        if (context == null) {
            context = ctx.applicationContext
            startListening()
        }
    }

    /** Memulai pengamatan clipboard di Android jika tersedia. */
    fun startListening() {
        val ctx = context ?: return
        if (clipboardListener != null) return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                onPrimaryClipChanged()
            }
            cm.addPrimaryClipChangedListener(listener)
            clipboardListener = listener
            GepyLog.i(TAG, "CoPilotContextSentinel clipboard watcher aktif.")
        } catch (e: Throwable) {
            GepyLog.w(TAG, "Gagal mengaktifkan clipboard listener: ${e.message}")
        }
    }

    /** Menghentikan pengamatan clipboard. */
    fun stopListening() {
        val ctx = context ?: return
        val listener = clipboardListener ?: return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.removePrimaryClipChangedListener(listener)
            clipboardListener = null
        } catch (_: Throwable) {
        }
    }

    /** Menangani event saat isi clipboard OS berubah. */
    fun onPrimaryClipChanged() {
        val ctx = context ?: return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val text = clip?.coerceToText(ctx)?.toString().orEmpty()
            evaluateClipboardText(text)
        } catch (e: Throwable) {
            GepyLog.w(TAG, "Gagal mengevaluasi clipboard change: ${e.message}")
        }
    }

    /**
     * Memeriksa teks masukan clipboard secara murni (deterministik dan dapat diuji di JVM).
     *
     * @param rawText Teks yang disalin oleh pengguna
     * @param currentForegroundPackage Paket aplikasi yang sedang aktif di depan
     * @return `ProactiveWhisper` jika konteks layak ditawarkan, atau `null` jika diabaikan/suppressed.
     */
    fun evaluateClipboardText(
        rawText: String,
        currentForegroundPackage: String = ""
    ): ProactiveWhisper? {
        val currentLevel = proactivityLevelProvider()
        if (currentLevel == ProactivityLevel.ZEN_PASSIVE) {
            return null
        }

        val clean = rawText.trim()
        if (clean.length < 5 || clean.length > 1000) return null

        val currentHash = clean.hashCode()
        if (currentHash == lastEvaluatedClipHash) {
            return null
        }
        lastEvaluatedClipHash = currentHash

        // Cek anti-distraksi
        if (isDistractionActive(currentForegroundPackage)) {
            GepyLog.d(TAG, "Whisper clipboard ditahan karena user sedang di immersive app: $currentForegroundPackage")
            return null
        }

        val entity = parseClipEntity(clean)
        val whisper = when (entity) {
            is DetectedClipEntity.CourierWaybill -> {
                ProactiveWhisper(
                    title = "Resi ${entity.courierName} Terdeteksi",
                    subtitle = "Nomor: ${entity.waybillNumber}. Mau lacak status pengiriman sekarang?",
                    actionLabel = "Lacak Paket",
                    goalPayload = "Cek resi ${entity.courierName} ${entity.waybillNumber}",
                    priority = 2
                )
            }
            is DetectedClipEntity.WebArticle -> {
                // Abaikan jika tautan adalah gambar mentah atau localhost
                if (entity.url.endsWith(".png") || entity.url.endsWith(".jpg") || entity.url.contains("localhost")) {
                    null
                } else {
                    ProactiveWhisper(
                        title = "Tautan Artikel Disalin",
                        subtitle = "Buka dan minta Gepy merangkum inti pembahasannya?",
                        actionLabel = "Ringkas Artikel",
                        goalPayload = "Buka tautan ini dan ringkas artikelnya: ${entity.url}",
                        priority = 1
                    )
                }
            }
            is DetectedClipEntity.PhoneNumber -> {
                ProactiveWhisper(
                    title = "Nomor Telepon Disalin",
                    subtitle = "${entity.rawPhone}. Buka percakapan WhatsApp baru?",
                    actionLabel = "Kirim WA",
                    goalPayload = "Kirim pesan WA ke ${entity.normalizedWa}",
                    priority = 1
                )
            }
            is DetectedClipEntity.None -> null
        }

        if (whisper != null) {
            GepyLog.i(TAG, "Proactive Whisper dihasilkan: ${whisper.title}")
            onWhisperAvailable?.invoke(whisper)
        }
        return whisper
    }

    /**
     * Memeriksa kondisi baterai dan waktu untuk menghasilkan pengingat tidur (Nightly Battery Guard).
     *
     * @param batteryPercent Persentase daya baterai saat ini
     * @param isCharging Status pengisian daya perangkat
     * @param currentForegroundPackage Paket aplikasi aktif
     */
    fun evaluateBatteryState(
        batteryPercent: Int,
        isCharging: Boolean,
        currentForegroundPackage: String = ""
    ): ProactiveWhisper? {
        val currentLevel = proactivityLevelProvider()
        if (currentLevel == ProactivityLevel.ZEN_PASSIVE) return null

        val now = clockProvider()
        val hour = now.hour
        val isLateNight = hour >= 21 || hour < 5
        if (!isLateNight) return null

        if (batteryPercent in 1..18 && !isCharging) {
            val dateKey = "${now.year}-${now.monthValue}-${now.dayOfMonth}"
            if (dateKey == lastBatteryWarningDateKey) {
                return null
            }
            if (isDistractionActive(currentForegroundPackage)) {
                return null
            }
            lastBatteryWarningDateKey = dateKey

            val whisper = ProactiveWhisper(
                title = "Baterai Lemah ($batteryPercent%)",
                subtitle = "Sambungkan pengisi daya sebelum beristirahat agar alarm besok pagi tetap aman.",
                actionLabel = "Siap",
                goalPayload = "Buka pengaturan baterai",
                priority = 3
            )
            onWhisperAvailable?.invoke(whisper)
            return whisper
        }
        return null
    }

    /** Memeriksa apakah aplikasi yang sedang berada di layar termasuk kategori yang dilarang diinterupsi. */
    fun isDistractionActive(foregroundPackage: String): Boolean {
        val cleanPkg = foregroundPackage.trim()
        if (cleanPkg.isEmpty()) return false
        return cleanPkg in IMMERSIVE_MEDIA_OR_GAMES || cleanPkg in KEYBOARD_IME_PACKAGES
    }

    /** Mengklasifikasikan teks clipboard ke dalam entitas data konkret. */
    fun parseClipEntity(text: String): DetectedClipEntity {
        val clean = text.trim()

        // 1. Cek Pola Resi Kurir
        val keywordMatch = RESI_KEYWORD_REGEX.find(clean)
        if (keywordMatch != null) {
            val waybill = keywordMatch.groupValues[1]
            val courier = guessCourier(waybill)
            return DetectedClipEntity.CourierWaybill(courierName = courier, waybillNumber = waybill)
        }

        val spxMatch = SPX_REGEX.find(clean)
        if (spxMatch != null) {
            return DetectedClipEntity.CourierWaybill("Shopee Xpress", spxMatch.groupValues[1])
        }

        val sicepatMatch = SICEPAT_REGEX.find(clean)
        if (sicepatMatch != null) {
            return DetectedClipEntity.CourierWaybill("SiCepat", sicepatMatch.groupValues[1])
        }

        val jntMatch = JNT_REGEX.find(clean)
        if (jntMatch != null && (clean.startsWith("JP") || clean.startsWith("JX") || clean.startsWith("JZ") || clean.startsWith("JNA"))) {
            return DetectedClipEntity.CourierWaybill("J&T Express", jntMatch.groupValues[1])
        }

        val jneMatch = JNE_REGEX.find(clean)
        if (jneMatch != null) {
            return DetectedClipEntity.CourierWaybill("JNE", jneMatch.groupValues[1])
        }

        val anterAjaMatch = ANTERAJA_REGEX.find(clean)
        if (anterAjaMatch != null) {
            return DetectedClipEntity.CourierWaybill("AnterAja", anterAjaMatch.groupValues[1])
        }

        val posMatch = POS_INDO_REGEX.find(clean)
        if (posMatch != null && clean.length in 10..14) {
            return DetectedClipEntity.CourierWaybill("Pos Indonesia", posMatch.groupValues[1])
        }

        // 2. Cek Pola URL Artikel Web
        if (URL_REGEX.matches(clean)) {
            return DetectedClipEntity.WebArticle(clean)
        }

        // 3. Cek Pola Nomor Telepon / WA
        val normalizedDigits = clean.replace(Regex("""[\s\-_]"""), "")
        if (PHONE_NUMBER_REGEX.matches(normalizedDigits)) {
            val normalizedWa = when {
                normalizedDigits.startsWith("+62") -> normalizedDigits.substring(1)
                normalizedDigits.startsWith("08") -> "628" + normalizedDigits.substring(2)
                else -> normalizedDigits
            }
            return DetectedClipEntity.PhoneNumber(rawPhone = clean, normalizedWa = normalizedWa)
        }

        return DetectedClipEntity.None
    }

    private fun guessCourier(waybill: String): String {
        val upper = waybill.uppercase(Locale.ROOT)
        return when {
            upper.startsWith("SPX") -> "Shopee Xpress"
            upper.startsWith("JP") || upper.startsWith("JX") || upper.startsWith("JZ") -> "J&T Express"
            upper.startsWith("CGK") || upper.startsWith("SUB") || upper.startsWith("SOC") || upper.startsWith("JT") || upper.startsWith("JNE") -> "JNE"
            upper.startsWith("TKP") || upper.startsWith("00") || upper.startsWith("SI") -> "SiCepat"
            upper.startsWith("1000") -> "AnterAja"
            upper.startsWith("P") || upper.startsWith("POS") -> "Pos Indonesia"
            else -> "Ekspedisi"
        }
    }
}
