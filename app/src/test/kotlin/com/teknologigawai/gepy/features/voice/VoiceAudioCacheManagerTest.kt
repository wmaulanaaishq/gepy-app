package com.teknologigawai.gepy.features.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceAudioCacheManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `VoiceClauseChunker splits sentences cleanly without breaking audio tags and strips tags for system TTS`() {
        val input = "[softly] Halo sayang, aku udah beresin pesan WhatsApp kamu ya! [light chuckle] Mau aku lanjutin apa lagi sekarang?"
        val clauses = VoiceClauseChunker.splitIntoClauses(input, maxChunkChars = 140)
        assertEquals(2, clauses.size)
        assertEquals("[softly] Halo sayang, aku udah beresin pesan WhatsApp kamu ya!", clauses[0])
        assertEquals("[light chuckle] Mau aku lanjutin apa lagi sekarang?", clauses[1])

        val stripped = VoiceClauseChunker.stripAudioTagsForSystemTts(input)
        assertEquals("Halo sayang, aku udah beresin pesan WhatsApp kamu ya! Mau aku lanjutin apa lagi sekarang?", stripped)
    }

    @Test
    fun `cache stores lookup hits and enforces LRU eviction when exceeding max MB`() {
        var nowMs = 1_000L
        val cacheDir = File(tempFolder.root, "voice_cache_lru")
        // Batas 5 MB
        val manager = VoiceAudioCacheManager(
            rootDir = cacheDir,
            maxCacheMbProvider = { 5 },
            clockMs = { nowMs }
        )

        val clip1Payload = ByteArray(3 * 1024 * 1024) { 0x11 } // 3 MB
        val clip2Payload = ByteArray(3 * 1024 * 1024) { 0x22 } // 3 MB -> total 6 MB > 5 MB -> clip 1 terhapus LRU

        val f1 = manager.prepareTargetFile("gepy", "Kalimat pertama")
        f1.writeBytes(clip1Payload)
        manager.registerCachedClip("gepy", "Kalimat pertama", f1)
        assertNotNull(manager.lookupCachedClip("gepy", "  kalimat PERTAMA  "))

        nowMs += 5_000L
        val f2 = manager.prepareTargetFile("gepy", "Kalimat kedua")
        f2.writeBytes(clip2Payload)
        manager.registerCachedClip("gepy", "Kalimat kedua", f2)

        // Klip lama (Kalimat pertama) harus terpangkas oleh LRU
        assertNull(manager.lookupCachedClip("gepy", "Kalimat pertama"))
        assertNotNull(manager.lookupCachedClip("gepy", "Kalimat kedua"))
        assertEquals(1, manager.getStats().clipCount)
    }

    @Test
    fun `SAF ZIP export and Smart-Merge import roundtrip works and blocks ZIP Slip traversal`() {
        val sourceDir = File(tempFolder.root, "cache_src")
        val sourceMgr = VoiceAudioCacheManager(rootDir = sourceDir)

        val mp3A = byteArrayOf(0x49, 0x44, 0x33, 0x01, 0x0A)
        val mp3B = byteArrayOf(0x49, 0x44, 0x33, 0x02, 0x0B)
        val fileA = sourceMgr.prepareTargetFile("gepy", "Halo sayang")
        fileA.writeBytes(mp3A)
        sourceMgr.registerCachedClip("gepy", "Halo sayang", fileA)

        val fileB = sourceMgr.prepareTargetFile("gepy", "Baterai penuh")
        fileB.writeBytes(mp3B)
        sourceMgr.registerCachedClip("gepy", "Baterai penuh", fileB)

        val zipOut = ByteArrayOutputStream()
        val exported = sourceMgr.exportVaultToZipStream(zipOut)
        assertEquals(2, exported)

        // Impor ke perangkat tujuan yang sudah punya 1 klip ("Halo sayang") -> Smart Merge melewati 1 & mengimpor 1 baru
        val destDir = File(tempFolder.root, "cache_dest")
        val destMgr = VoiceAudioCacheManager(rootDir = destDir)
        val existingA = destMgr.prepareTargetFile("gepy", "Halo sayang")
        existingA.writeBytes(mp3A)
        destMgr.registerCachedClip("gepy", "Halo sayang", existingA)

        val importResult = destMgr.importVaultFromZipStream(ByteArrayInputStream(zipOut.toByteArray()))
        assertEquals(1, importResult.importedCount)
        assertEquals(1, importResult.skippedCount)
        assertEquals(2, importResult.totalClipsInArchive)

        val restoredB = destMgr.lookupCachedClip("gepy", "Baterai penuh")
        assertNotNull(restoredB)
        assertArrayEquals(mp3B, restoredB!!.readBytes())

        // Uji proteksi ZIP Slip path traversal (../evil.mp3 harus ditolak mutlak)
        val maliciousZip = ByteArrayOutputStream()
        ZipOutputStream(maliciousZip).use { zos ->
            zos.putNextEntry(ZipEntry("../escaped_evil.mp3"))
            zos.write(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
            zos.closeEntry()
        }
        val malRes = destMgr.importVaultFromZipStream(ByteArrayInputStream(maliciousZip.toByteArray()))
        assertEquals(0, malRes.importedCount)
        assertFalse(File(tempFolder.root, "escaped_evil.mp3").exists())

        // Uji snapshot daftar klip & penghapusan per-klip untuk kurasi dataset suara
        val snapshot = destMgr.getAllCachedClipsSnapshot()
        assertEquals(2, snapshot.size)
        val firstClip = snapshot.first()
        assertNotNull(destMgr.resolveClipFile(firstClip))
        assertTrue(destMgr.deleteClip(firstClip.voiceId, firstClip.hash))
        assertEquals(1, destMgr.getAllCachedClipsSnapshot().size)
    }

    @Test
    fun `computeClipHash isolates audio across modelId, stability, similarityBoost, languageCode, and normalizes slider precision`() {
        val manager = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "voice_hash_params"))

        val baseHash = manager.computeClipHash(
            voiceId = "gepy",
            text = "Halo sayang.",
            modelId = "eleven_v4_turbo",
            stability = 0.45f,
            similarityBoost = 0.75f,
            languageCode = "id"
        )
        val modelChangedHash = manager.computeClipHash(
            voiceId = "gepy",
            text = "Halo sayang.",
            modelId = "eleven_multilingual_v2",
            stability = 0.45f,
            similarityBoost = 0.75f,
            languageCode = "id"
        )
        val stabilityChangedHash = manager.computeClipHash(
            voiceId = "gepy",
            text = "Halo sayang.",
            modelId = "eleven_v4_turbo",
            stability = 0.72f,
            similarityBoost = 0.75f,
            languageCode = "id"
        )
        val similarityChangedHash = manager.computeClipHash(
            voiceId = "gepy",
            text = "Halo sayang.",
            modelId = "eleven_v4_turbo",
            stability = 0.45f,
            similarityBoost = 0.90f,
            languageCode = "id"
        )
        val languageChangedHash = manager.computeClipHash(
            voiceId = "gepy",
            text = "Halo sayang.",
            modelId = "eleven_v4_turbo",
            stability = 0.45f,
            similarityBoost = 0.75f,
            languageCode = "en"
        )
        val floatJitterHash = manager.computeClipHash(
            voiceId = "gepy",
            text = "  halo SAYANG. ",
            modelId = "eleven_v4_turbo",
            stability = 0.4500012f,
            similarityBoost = 0.749998f,
            languageCode = "ID"
        )

        assertNotEquals(baseHash, modelChangedHash)
        assertNotEquals(baseHash, stabilityChangedHash)
        assertNotEquals(baseHash, similarityChangedHash)
        assertNotEquals(baseHash, languageChangedHash)
        assertEquals(baseHash, floatJitterHash)

        // Registrasi dengan parameter spesifik tidak boleh tertabrak lookup parameter lain
        val f1 = manager.prepareTargetFile("gepy", "Halo sayang.", modelId = "eleven_v4_turbo", stability = 0.45f)
        f1.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x01))
        manager.registerCachedClip("gepy", "Halo sayang.", f1, modelId = "eleven_v4_turbo", stability = 0.45f)

        assertNotNull(manager.lookupCachedClip("gepy", "Halo sayang.", modelId = "eleven_v4_turbo", stability = 0.45f))
        assertNull(manager.lookupCachedClip("gepy", "Halo sayang.", modelId = "eleven_v4_turbo", stability = 0.72f))
        assertNull(manager.lookupCachedClip("gepy", "Halo sayang.", modelId = "eleven_multilingual_v2", stability = 0.45f))
    }

    @Test
    fun `statsFlow updates live on mutations and SAF zip preserves model and slider metadata`() {
        val mgr = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "stats_src"))
        assertEquals(0, mgr.statsFlow.value.clipCount)

        val file1 = mgr.prepareTargetFile(
            voiceId = "gepy",
            text = "Tes model kustom",
            modelId = "eleven_flash_v2_5",
            stability = 0.62f,
            similarityBoost = 0.88f
        )
        file1.writeBytes(ByteArray(128) { 0x33 })
        mgr.registerCachedClip(
            voiceId = "gepy",
            text = "Tes model kustom",
            mp3File = file1,
            modelId = "eleven_flash_v2_5",
            stability = 0.62f,
            similarityBoost = 0.88f
        )
        assertEquals(1, mgr.statsFlow.value.clipCount)
        assertEquals(128L, mgr.statsFlow.value.totalBytes)

        val zipBytes = ByteArrayOutputStream()
        assertEquals(1, mgr.exportVaultToZipStream(zipBytes))

        val restoredMgr = VoiceAudioCacheManager(rootDir = File(tempFolder.root, "stats_dest"))
        val importRes = restoredMgr.importVaultFromZipStream(ByteArrayInputStream(zipBytes.toByteArray()))
        assertEquals(1, importRes.importedCount)
        assertEquals(1, restoredMgr.statsFlow.value.clipCount)

        val restoredEntry = restoredMgr.getAllCachedClipsSnapshot().single()
        assertEquals("eleven_flash_v2_5", restoredEntry.modelId)
        assertEquals(0.62f, restoredEntry.stability, 0.01f)
        assertEquals(0.88f, restoredEntry.similarityBoost, 0.01f)
        assertNotNull(
            restoredMgr.lookupCachedClip(
                voiceId = "gepy",
                text = "Tes model kustom",
                modelId = "eleven_flash_v2_5",
                stability = 0.62f,
                similarityBoost = 0.88f
            )
        )

        assertTrue(restoredMgr.deleteClip(restoredEntry.voiceId, restoredEntry.hash))
        assertEquals(0, restoredMgr.statsFlow.value.clipCount)

        mgr.clearAllCache()
        assertEquals(0, mgr.statsFlow.value.clipCount)
    }

    @Test
    fun `default max cache is 200 MB, offline voice-only fallback preserves online strictness, and enforceCacheBudget trims excess immediately`() {
        assertEquals(200, VoiceAudioCacheManager.DEFAULT_MAX_CACHE_MB)

        var nowMs = 10_000L
        var dynamicMaxMb = 10
        val mgr = VoiceAudioCacheManager(
            rootDir = File(tempFolder.root, "voice_budget_and_fallback"),
            maxCacheMbProvider = { dynamicMaxMb },
            clockMs = { nowMs }
        )

        val fOld = mgr.prepareTargetFile(
            voiceId = "gepy",
            text = "Oke Maulana.",
            modelId = "eleven_v4_turbo",
            stability = 0.45f,
            similarityBoost = 0.75f
        )
        fOld.writeBytes(ByteArray(3 * 1024 * 1024) { 0x1A })
        mgr.registerCachedClip(
            voiceId = "gepy",
            text = "Oke Maulana.",
            mp3File = fOld,
            modelId = "eleven_v4_turbo",
            stability = 0.45f,
            similarityBoost = 0.75f
        )

        // Saat online (allowVoiceOnlyFallback = false), perubahan slider tetap dianggap cache miss
        assertFalse(
            mgr.hasCachedClip(
                voiceId = "gepy",
                text = "Oke Maulana.",
                modelId = "eleven_flash_v2_5",
                stability = 0.85f,
                allowVoiceOnlyFallback = false
            )
        )
        assertNull(
            mgr.lookupCachedClip(
                voiceId = "gepy",
                text = "Oke Maulana.",
                modelId = "eleven_flash_v2_5",
                stability = 0.85f,
                allowVoiceOnlyFallback = false
            )
        )

        // Saat offline (allowVoiceOnlyFallback = true), voiceId + teks ter-normalisasi tetap cocok
        assertTrue(
            mgr.hasCachedClip(
                voiceId = "gepy",
                text = "  oke   maulana. ",
                modelId = "eleven_flash_v2_5",
                stability = 0.85f,
                allowVoiceOnlyFallback = true
            )
        )
        assertNotNull(
            mgr.lookupCachedClip(
                voiceId = "gepy",
                text = "  oke   maulana. ",
                modelId = "eleven_flash_v2_5",
                stability = 0.85f,
                allowVoiceOnlyFallback = true
            )
        )

        // Tambah klip 3 MB kedua (total 6 MB <= 10 MB) -> lalu turunkan budget ke 4 MB dan panggil enforceCacheBudget()
        nowMs += 2_000L
        val fNew = mgr.prepareTargetFile("gepy", "Sip Maulana.")
        fNew.writeBytes(ByteArray(3 * 1024 * 1024) { 0x2B })
        mgr.registerCachedClip("gepy", "Sip Maulana.", fNew)
        assertEquals(2, mgr.statsFlow.value.clipCount)

        dynamicMaxMb = 4
        val evicted = mgr.enforceCacheBudget()
        assertEquals(1, evicted)
        assertEquals(1, mgr.statsFlow.value.clipCount)
        assertFalse(mgr.hasCachedClip("gepy", "Oke Maulana.", allowVoiceOnlyFallback = true))
        assertTrue(mgr.hasCachedClip("gepy", "Sip Maulana.", allowVoiceOnlyFallback = false))
    }
}
