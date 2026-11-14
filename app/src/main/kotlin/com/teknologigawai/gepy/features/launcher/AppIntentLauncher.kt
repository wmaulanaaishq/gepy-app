package com.teknologigawai.gepy.features.launcher

import android.content.Context
import android.content.Intent
import com.teknologigawai.gepy.core.config.EnvConfig
import com.teknologigawai.gepy.core.config.SafetyRules
import com.teknologigawai.gepy.core.errors.ForbiddenPackageException
import com.teknologigawai.gepy.core.logging.GepyLog
import com.teknologigawai.gepy.core.persona.SovereignNaturalSpeech
import com.teknologigawai.gepy.core.persona.SovereignSpeechContext
import com.teknologigawai.gepy.core.ports.PerceptionPort
import com.teknologigawai.gepy.service.GepyAccessibilityService
import kotlinx.coroutines.delay

/** Hasil resolusi dan peluncuran aplikasi melalui `PackageManager` / `Intent`. */
data class AppLaunchResult(
    val success: Boolean,
    val resolvedPackage: String,
    val message: String
)

/** Hasil ekstraksi perintah suara untuk membuka aplikasi (murni maupun diikuti sub-tugas). */
data class ParsedAppLaunchIntent(
    val appName: String,
    val isPureLaunchOnly: Boolean,
    val remainingSubGoal: String = ""
)

/**
 * Antarmuka cache alias paket aplikasi (akan dihubungkan ke Room `app_package_cache` pada Fase 3).
 */
interface PackageAliasCache {
    suspend fun lookupAlias(alias: String): String?
    suspend fun saveAlias(alias: String, packageName: String)
}

/**
 * Peluncur aplikasi langsung berbasis kamus alias `O(1)`, indeks native `PackageManager`,
 * dan cache `app_package_cache` sesuai aturan `skill.md` (*App Launch First*).
 */
class AppIntentLauncher(
    private val context: Context? = null,
    private val aliasCache: PackageAliasCache? = null,
    @Volatile var speechContextProvider: () -> SovereignSpeechContext = { SovereignSpeechContext() }
) {
    companion object {
        /** Kamus alias populer Bahasa Indonesia -> `packageName` berkecepatan `O(1)`. */
        val builtinAliasMap: Map<String, String> = mapOf(
            "wa" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "whats app" to "com.whatsapp",
            "wa bisnis" to "com.whatsapp.w4b",
            "whatsapp business" to "com.whatsapp.w4b",
            "kalender" to "com.google.android.calendar",
            "calendar" to "com.google.android.calendar",
            "google calendar" to "com.google.android.calendar",
            "maps" to "com.google.android.apps.maps",
            "peta" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "ig" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "instagram lite" to "com.instagram.lite",
            "ig lite" to "com.instagram.lite",
            "tiktok" to "com.ss.android.ugc.trill",
            "tik tok" to "com.ss.android.ugc.trill",
            "facebook" to "com.facebook.katana",
            "fb" to "com.facebook.katana",
            "facebook lite" to "com.facebook.lite",
            "threads" to "com.instagram.barcelona",
            "x" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "shopee" to "com.shopee.id",
            "tokopedia" to "com.tokopedia.tkpd",
            "tokped" to "com.tokopedia.tkpd",
            "gojek" to "com.gojek.app",
            "goride" to "com.gojek.app",
            "gofood" to "com.gojek.app",
            "gocar" to "com.gojek.app",
            "grab" to "com.grabtaxi.passenger",
            "lazada" to "com.lazada.android",
            "traveloka" to "com.traveloka.android",
            "netflix" to "com.netflix.mediaclient",
            "capcut" to "com.lemon.lvoverseas",
            "canva" to "com.canva.editor",
            "kamera" to "com.sec.android.app.camera",
            "camera" to "com.sec.android.app.camera",
            "galeri" to "com.sec.android.gallery3d",
            "gallery" to "com.sec.android.gallery3d",
            "kalkulator" to "com.sec.android.app.popupcalculator",
            "calculator" to "com.sec.android.app.popupcalculator",
            "jam" to "com.sec.android.app.clockpackage",
            "clock" to "com.sec.android.app.clockpackage",
            "telepon" to "com.samsung.android.dialer",
            "phone" to "com.samsung.android.dialer",
            "play store" to "com.android.vending",
            "google play" to "com.android.vending",
            "pengaturan" to "com.android.settings",
            "settings" to "com.android.settings",
            "setelan" to "com.android.settings"
        )

        /** Paket alternatif apabila varian aplikasi berbeda di perangkat pengguna (misal TikTok / Instagram Lite / OEM). */
        val fallbackPackageCandidates: Map<String, List<String>> = mapOf(
            "com.instagram.android" to listOf("com.instagram.lite"),
            "com.ss.android.ugc.trill" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.aweme"),
            "com.facebook.katana" to listOf("com.facebook.lite"),
            "com.sec.android.app.camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            "com.sec.android.gallery3d" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
            "com.sec.android.app.popupcalculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
            "com.sec.android.app.clockpackage" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "com.samsung.android.dialer" to listOf("com.google.android.dialer", "com.android.dialer")
        )

        private val pureOpenAppRegex = Regex(
            """^(?:tolong\s+|coba\s+|gepy\s+|hey\s+gepy\s+|halo\s+gepy\s+)?(?:buka|bukain|bukakan|jalankan|aktifkan|masuk\s+ke|pindah\s+ke)\s+(?:aplikasi\s+|app\s+|apk\s+)?([a-zA-Z0-9 ._-]{2,36}?)(?:\s+sekarang|\s+dong|\s+ya|\s+please|\s+deh)?[.!?]*$""",
            RegexOption.IGNORE_CASE
        )

        private val compoundOpenAppRegex = Regex(
            """^(?:tolong\s+|coba\s+|gepy\s+)?(?:buka|bukain|bukakan|jalankan|masuk\s+ke|pindah\s+ke)\s+(?:aplikasi\s+|app\s+)?([a-zA-Z0-9._-]+(?:\s+(?:bisnis|business|lite|maps|calendar|play|store))?)\s+(?:lalu|kemudian|terus|dan|untuk|buat|cari|kirim|chat|lihat|cek|putar|bikin|tulis)\b\s*(.*)$""",
            RegexOption.IGNORE_CASE
        )

        private val nonAppTargetKeywords = setOf(
            "pengaturan wifi", "pengaturan bluetooth", "pengaturan suara", "pengaturan layar",
            "pengaturan lokasi", "pengaturan aksesibilitas", "pengaturan nfc",
            "panel wifi", "panel wi-fi", "panel internet", "panel volume", "panel suara", "panel nfc",
            "panel cepat", "kontrol cepat",
            "mode getar", "mode normal", "mode senyap", "mode hening", "mode silent", "mode diam",
            "senter", "lampu senter", "flashlight", "torch",
            "alarm", "timer", "notifikasi", "notif",
            "chat", "pesan", "obrolan", "rute", "navigasi"
        )

        /**
         * Mengurai instruksi suara seperti `"Buka Instagram"`, `"Tolong buka WA"`, atau
         * `"Buka Instagram lalu cari akun teknologi gawai"` menjadi [ParsedAppLaunchIntent].
         */
        fun parseVoiceAppLaunchIntent(voiceGoal: String): ParsedAppLaunchIntent? {
            val clean = voiceGoal.trim()
            if (clean.isEmpty()) return null

            val compoundMatch = compoundOpenAppRegex.find(clean)
            if (compoundMatch != null) {
                val appCandidate = compoundMatch.groupValues[1].trim()
                val remainder = compoundMatch.groupValues[2].trim()
                if (appCandidate.isNotEmpty() && !nonAppTargetKeywords.contains(appCandidate.lowercase())) {
                    return ParsedAppLaunchIntent(
                        appName = appCandidate,
                        isPureLaunchOnly = false,
                        remainingSubGoal = remainder
                    )
                }
            }

            val pureMatch = pureOpenAppRegex.find(clean) ?: return null
            val rawTarget = pureMatch.groupValues[1].trim()
            val lowerTarget = rawTarget.lowercase()
            if (rawTarget.isEmpty() || nonAppTargetKeywords.any { lowerTarget.startsWith(it) }) {
                return null
            }
            // Jika mengandung kata penghubung tugas lanjutan, jangan anggap sebagai pure launch
            val subTaskConnectors = listOf(" lalu ", " kemudian ", " terus ", " untuk ", " cari ", " kirim ", " ke ")
            if (subTaskConnectors.any { lowerTarget.contains(it) }) {
                return null
            }
            return ParsedAppLaunchIntent(
                appName = rawTarget,
                isPureLaunchOnly = true
            )
        }

        private val goalPlatformRegexList: List<Pair<Regex, String>> = listOf(
            Regex("""\b(instagram\s+lite|ig\s+lite)\b""", RegexOption.IGNORE_CASE) to "com.instagram.lite",
            Regex("""\b(instagram|ig|insta|dm|direct\s+message)\b""", RegexOption.IGNORE_CASE) to "com.instagram.android",
            Regex("""\b(whatsapp\s+business|wa\s+bisnis)\b""", RegexOption.IGNORE_CASE) to "com.whatsapp.w4b",
            Regex("""\b(whatsapp|whats\s+app|wa)\b""", RegexOption.IGNORE_CASE) to "com.whatsapp",
            Regex("""\b(youtube|yt)\b""", RegexOption.IGNORE_CASE) to "com.google.android.youtube",
            Regex("""\b(tiktok|tik\s+tok)\b""", RegexOption.IGNORE_CASE) to "com.ss.android.ugc.trill",
            Regex("""\b(telegram|tele)\b""", RegexOption.IGNORE_CASE) to "org.telegram.messenger",
            Regex("""\b(shopee)\b""", RegexOption.IGNORE_CASE) to "com.shopee.id",
            Regex("""\b(tokopedia|tokped)\b""", RegexOption.IGNORE_CASE) to "com.tokopedia.tkpd",
            Regex("""\b(spotify)\b""", RegexOption.IGNORE_CASE) to "com.spotify.music",
            Regex("""\b(chrome|browser)\b""", RegexOption.IGNORE_CASE) to "com.android.chrome",
            Regex("""\b(gmail)\b""", RegexOption.IGNORE_CASE) to "com.google.android.gm",
            Regex("""\b(facebook\s+lite|fb\s+lite)\b""", RegexOption.IGNORE_CASE) to "com.facebook.lite",
            Regex("""\b(facebook|fb)\b""", RegexOption.IGNORE_CASE) to "com.facebook.katana",
            Regex("""\b(threads)\b""", RegexOption.IGNORE_CASE) to "com.instagram.barcelona",
            Regex("""\b(twitter)\b""", RegexOption.IGNORE_CASE) to "com.twitter.android",
            Regex("""\b(gojek|goride|gofood|gocar)\b""", RegexOption.IGNORE_CASE) to "com.gojek.app",
            Regex("""\b(grab)\b""", RegexOption.IGNORE_CASE) to "com.grabtaxi.passenger",
            Regex("""\b(google\s+calendar|kalender|jadwal\s+rapat)\b""", RegexOption.IGNORE_CASE) to "com.google.android.calendar",
            Regex("""\b(play\s+store|google\s+play)\b""", RegexOption.IGNORE_CASE) to "com.android.vending"
        )

        /**
         * Mendeteksi paket aplikasi target yang disebutkan di mana saja di dalam instruksi suara
         * (contoh: `"Cari IG Budi lalu kirim DM halo"` -> `"com.instagram.android"`,
         * `"Cari video gadget di YouTube"` -> `"com.google.android.youtube"`).
         */
        fun detectTargetAppPackageInGoal(voiceGoal: String): String? {
            val clean = voiceGoal.trim()
            if (clean.isEmpty()) return null
            val parsedLaunch = parseVoiceAppLaunchIntent(clean)
            if (parsedLaunch != null) {
                val aliasPkg = builtinAliasMap[parsedLaunch.appName.trim().lowercase()]
                if (aliasPkg != null) return aliasPkg
            }
            var bestMatchPos = Int.MAX_VALUE
            var bestPackage: String? = null
            for ((regex, pkg) in goalPlatformRegexList) {
                val match = regex.find(clean)
                if (match != null && match.range.first < bestMatchPos) {
                    bestMatchPos = match.range.first
                    bestPackage = pkg
                }
            }
            return bestPackage
        }
    }

    private val installedAppIndex = LinkedHashMap<String, String>()
    private var hasIndexedApps = false

    /**
     * Memuat dan mengindeks daftar aplikasi terpasang melalui `PackageManager` Android
     * (termasuk aplikasi pra-instal yang memiliki `ACTION_MAIN` + `CATEGORY_LAUNCHER`).
     */
    fun refreshInstalledAppsIndex() {
        val ctx = context ?: run {
            hasIndexedApps = true
            return
        }
        try {
            val pm = ctx.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            installedAppIndex.clear()
            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == EnvConfig.SELF_PACKAGE_NAME) continue
                val label = info.loadLabel(pm).toString().trim().lowercase()
                if (label.isNotEmpty()) {
                    installedAppIndex[label] = pkg
                }
            }
        } catch (_: Throwable) {
            // Aman pada lingkungan unit test JVM.
        }
        hasIndexedApps = true
    }

    /** Meresolusi nama aplikasi atau nama paket [nameOrPackage] menjadi `packageName` Android. */
    suspend fun resolvePackageName(nameOrPackage: String): String? {
        val normalized = nameOrPackage.trim().lowercase()
        if (normalized.isEmpty()) {
            return null
        }

        // 1. Jika input sudah berbentuk package name (mengandung titik tanpa spasi)
        if (normalized.contains('.') && !normalized.contains(' ')) {
            return nameOrPackage.trim()
        }

        // 2. Cek kamus alias O(1) bawaan
        val directAlias = builtinAliasMap[normalized]
        if (directAlias != null) {
            return directAlias
        }

        // 3. Cek cache SQLite/Room app_package_cache
        val cachedPkg = aliasCache?.lookupAlias(normalized)
        if (!cachedPkg.isNullOrEmpty()) {
            return cachedPkg
        }

        // 4. Cek indeks aplikasi terpasang dari PackageManager
        if (!hasIndexedApps) {
            refreshInstalledAppsIndex()
        }

        val exactInstalled = installedAppIndex[normalized]
        if (exactInstalled != null) {
            aliasCache?.saveAlias(normalized, exactInstalled)
            return exactInstalled
        }

        for ((appName, pkgName) in installedAppIndex) {
            if (appName.contains(normalized) || normalized.contains(appName)) {
                aliasCache?.saveAlias(normalized, pkgName)
                return pkgName
            }
        }

        return null
    }

    /**
     * Membuka aplikasi berdasarkan nama ramah pengguna atau nama paket [nameOrPackage].
     *
     * Melempar [ForbiddenPackageException] apabila paket tujuan termasuk dalam
     * daftar hitam aplikasi perbankan/dompet digital (`SafetyRules`).
     */
    suspend fun openApp(nameOrPackage: String): AppLaunchResult {
        val speechCtx = speechContextProvider()
        val resolved = resolvePackageName(nameOrPackage)
        if (resolved.isNullOrEmpty()) {
            return AppLaunchResult(
                success = false,
                resolvedPackage = "",
                message = SovereignNaturalSpeech.appNotFound(speechCtx, nameOrPackage)
            )
        }

        if (SafetyRules.isPackageForbidden(resolved)) {
            throw ForbiddenPackageException(targetIdentifier = resolved)
        }

        val baseCtx = context
            ?: return AppLaunchResult(
                success = true,
                resolvedPackage = resolved,
                message = SovereignNaturalSpeech.appLaunchSimulated(speechCtx, resolved)
            )

        // Utamakan konteks GepyAccessibilityService aktif agar bebas blokir Background Activity Launch (BAL) Android 10-15
        // ketika Gepy berada di latar belakang (misal berpindah dari WhatsApp ke Instagram saat Live Call).
        val launchCtx = GepyAccessibilityService.instance ?: baseCtx

        return try {
            val pm = launchCtx.packageManager
            var finalPkg: String = resolved
            var launchIntent = pm.getLaunchIntentForPackage(finalPkg)

            // Fallback 1: Coba kandidat paket alternatif (misal Instagram Lite / TikTok varian global)
            if (launchIntent == null) {
                val altList = fallbackPackageCandidates[finalPkg].orEmpty()
                for (altPkg in altList) {
                    val altIntent = pm.getLaunchIntentForPackage(altPkg)
                    if (altIntent != null) {
                        finalPkg = altPkg
                        launchIntent = altIntent
                        break
                    }
                }
            }

            // Fallback 2: Segarkan indeks aplikasi terpasang dan cocokkan berdasarkan label aplikasi di perangkat
            if (launchIntent == null) {
                refreshInstalledAppsIndex()
                val normQuery = nameOrPackage.trim().lowercase()
                val byLabelPkg = installedAppIndex[normQuery]
                    ?: installedAppIndex.entries.firstOrNull { (label, _) ->
                        label.contains(normQuery) || normQuery.contains(label)
                    }?.value
                if (!byLabelPkg.isNullOrEmpty()) {
                    val labelIntent = pm.getLaunchIntentForPackage(byLabelPkg)
                    if (labelIntent != null) {
                        finalPkg = byLabelPkg
                        launchIntent = labelIntent
                    }
                }
            }

            val finalIntent = launchIntent
                ?: return AppLaunchResult(
                    success = false,
                    resolvedPackage = finalPkg,
                    message = SovereignNaturalSpeech.appNotInstalled(speechCtx, nameOrPackage)
                )

            finalIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            launchCtx.startActivity(finalIntent)
            aliasCache?.saveAlias(nameOrPackage.trim().lowercase(), finalPkg)
            GepyLog.i("AppLauncher", "Berhasil meluncurkan aplikasi $finalPkg (dari query '$nameOrPackage')")
            AppLaunchResult(
                success = true,
                resolvedPackage = finalPkg,
                message = SovereignNaturalSpeech.appOpened(speechCtx, nameOrPackage)
            )
        } catch (e: Throwable) {
            GepyLog.w("AppLauncher", "Gagal menjalankan Intent peluncuran untuk $resolved: ${e.message}")
            AppLaunchResult(
                success = false,
                resolvedPackage = resolved,
                message = "Gagal menjalankan Intent peluncuran untuk $resolved: ${e.message}"
            )
        }
    }

    /**
     * Menunggu secara reaktif hingga [packageName] benar-benar menjadi aplikasi
     * latar depan (*foreground*) dan pohon tampilannya stabil.
     */
    suspend fun waitForApp(
        packageName: String,
        perceptionPort: PerceptionPort,
        timeoutMs: Long = 10_000L,
        pollIntervalMs: Long = EnvConfig.defaultPollIntervalMs
    ): Boolean {
        val targetLower = packageName.trim().lowercase()
        if (targetLower.isEmpty()) {
            return false
        }
        if (context == null) {
            return true
        }

        val startMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startMs < timeoutMs) {
            val elements = perceptionPort.captureAndPruneScreen()
            if (perceptionPort.currentForegroundPackage.lowercase() == targetLower &&
                elements.isNotEmpty()
            ) {
                return true
            }
            delay(pollIntervalMs)
        }
        return perceptionPort.currentForegroundPackage.lowercase() == targetLower
    }
}
