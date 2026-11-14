package com.teknologigawai.gepy.features.brain

import androidx.sqlite.db.SupportSQLiteDatabase
import com.teknologigawai.gepy.core.ports.ActionOutcome
import com.teknologigawai.gepy.core.ports.EdgeRiskRule
import com.teknologigawai.gepy.core.ports.PredictedEdgeOutcome
import com.teknologigawai.gepy.data.database.GepyDatabase
import com.teknologigawai.gepy.data.database.SqliteMemoryRepository
import com.teknologigawai.gepy.features.brain.models.AgentActionType
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.brain.models.ReflectionStatus
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolicEdgeWorldModelTest {

    @Test
    fun `buildEdgeKey menormalisasi resourceId spesifik generic android id dan elemen Compose tanpa id`() {
        val specificIdEl = PrunedUiElement(
            index = 1,
            nodeId = "n1",
            viewResourceId = " com.app:id/btn_upgrade_pro ",
            text = "Upgrade Pro"
        )
        assertEquals("com.app:id/btn_upgrade_pro", SymbolicEdgeWorldModel.buildEdgeKey(specificIdEl))

        val genericAndroidEl = PrunedUiElement(
            index = 2,
            nodeId = "n2",
            viewResourceId = "android:id/title",
            text = "  Berlangganan   Premium "
        )
        assertEquals("android:id/title|berlangganan premium", SymbolicEdgeWorldModel.buildEdgeKey(genericAndroidEl))

        val composeNoIdEl = PrunedUiElement(
            index = 3,
            nodeId = "n3",
            viewResourceId = null,
            text = "Coba Gratis 7 Hari"
        )
        assertEquals("label:coba gratis 7 hari", SymbolicEdgeWorldModel.buildEdgeKey(composeNoIdEl))
    }

    @Test
    fun `classifyTransition mendeteksi PAYWALL AD_POPUP PERMISSION_MODAL ERROR_DIALOG INEFFECTIVE_DEADEND dan pengecualian tujuan`() {
        val preScreen = listOf(PrunedUiElement(index = 1, nodeId = "play", text = "Putar Lagu", isClickable = true))

        val paywallOutcome = SymbolicEdgeWorldModel.classifyTransition(
            userGoal = "Putar lagu favorit saya",
            prePackage = "com.spotify.music",
            preElements = preScreen,
            postPackage = "com.spotify.music",
            postElements = listOf(
                PrunedUiElement(index = 1, nodeId = "promo", text = "Mulai uji coba gratis 1 bulan", isClickable = true)
            ),
            rawOutcome = ActionOutcome.CORRECT
        )
        assertEquals(PredictedEdgeOutcome.PAYWALL, paywallOutcome)

        // Transisi ke Google Play Store dari aplikasi musik ditandai PAYWALL bila tujuan tidak minta berlangganan
        val unintentionalStoreRedirect = SymbolicEdgeWorldModel.classifyTransition(
            userGoal = "Putar lagu favorit saya",
            prePackage = "com.spotify.music",
            preElements = preScreen,
            postPackage = "com.android.vending",
            postElements = listOf(PrunedUiElement(index = 1, nodeId = "sub", text = "Pilih paket bulanan")),
            rawOutcome = ActionOutcome.CORRECT
        )
        assertEquals(PredictedEdgeOutcome.PAYWALL, unintentionalStoreRedirect)

        // Jika pengguna justru meminta berlangganan, transisi ke paywall tidak ditandai sebagai jebakan
        val intentionalSub = SymbolicEdgeWorldModel.classifyTransition(
            userGoal = "Berlangganan Spotify Premium sekarang",
            prePackage = "com.spotify.music",
            preElements = preScreen,
            postPackage = "com.android.vending",
            postElements = listOf(PrunedUiElement(index = 1, nodeId = "sub", text = "Pilih paket bulanan")),
            rawOutcome = ActionOutcome.CORRECT
        )
        assertEquals(PredictedEdgeOutcome.SAFE_PROGRESS, intentionalSub)

        val adOutcome = SymbolicEdgeWorldModel.classifyTransition(
            userGoal = "Buka berita terbaru",
            prePackage = "com.news.app",
            preElements = preScreen,
            postPackage = "com.news.app",
            postElements = listOf(PrunedUiElement(index = 1, nodeId = "ad", text = "Lewati iklan")),
            rawOutcome = ActionOutcome.CORRECT
        )
        assertEquals(PredictedEdgeOutcome.AD_POPUP, adOutcome)

        val permOutcome = SymbolicEdgeWorldModel.classifyTransition(
            userGoal = "Putar musik",
            prePackage = "com.music.app",
            preElements = preScreen,
            postPackage = "com.google.android.permissioncontroller",
            postElements = listOf(PrunedUiElement(index = 1, nodeId = "allow", text = "Saat aplikasi digunakan akses lokasi")),
            rawOutcome = ActionOutcome.CORRECT
        )
        assertEquals(PredictedEdgeOutcome.PERMISSION_MODAL, permOutcome)

        val errOutcome = SymbolicEdgeWorldModel.classifyTransition(
            userGoal = "Kirim pesan",
            prePackage = "com.chat.app",
            preElements = preScreen,
            postPackage = "com.chat.app",
            postElements = listOf(PrunedUiElement(index = 1, nodeId = "err", text = "Tidak ada koneksi internet")),
            rawOutcome = ActionOutcome.CORRECT
        )
        assertEquals(PredictedEdgeOutcome.ERROR_DIALOG, errOutcome)

        val deadendOutcome = SymbolicEdgeWorldModel.classifyTransition(
            userGoal = "Kirim pesan",
            prePackage = "com.chat.app",
            preElements = preScreen,
            postPackage = "com.chat.app",
            postElements = preScreen,
            rawOutcome = ActionOutcome.INEFFECTIVE
        )
        assertEquals(PredictedEdgeOutcome.INEFFECTIVE_DEADEND, deadendOutcome)
    }

    @Test
    fun `evaluatePreActionEdgeGuard dan isPlaybookTargetRisky mencegat klik berisiko tinggi serta mengizinkan jika diminta user`() {
        val rules = listOf(
            EdgeRiskRule(
                packageName = "com.app.example",
                screenSignatureHash = "h_main",
                edgeKey = "com.app.example:id/btn_pro",
                actionType = "CLICK",
                targetLabelSample = "coba pro",
                dominantOutcome = PredictedEdgeOutcome.PAYWALL,
                paywallCount = 3,
                riskScore = 0.89f,
                mitigationHint = "Hindari 'coba pro': memicu paywall."
            )
        )
        val elements = listOf(
            PrunedUiElement(
                index = 1,
                nodeId = "n1",
                viewResourceId = "com.app.example:id/btn_pro",
                text = "Coba Pro",
                isClickable = true
            ),
            PrunedUiElement(
                index = 2,
                nodeId = "n2",
                viewResourceId = "com.app.example:id/btn_skip",
                text = "Lewati",
                isClickable = true
            )
        )

        assertTrue(
            SymbolicEdgeWorldModel.isPlaybookTargetRisky(
                targetResourceId = "com.app.example:id/btn_pro",
                actionType = "CLICK",
                rules = rules,
                userGoal = "Putar lagu santai"
            )
        )
        assertFalse(
            SymbolicEdgeWorldModel.isPlaybookTargetRisky(
                targetResourceId = "com.app.example:id/btn_pro",
                actionType = "CLICK",
                rules = rules,
                userGoal = "Beli langganan Coba Pro"
            )
        )

        val advice = SymbolicEdgeWorldModel.buildPreActionPromptAdvice(elements, rules, "Putar lagu santai")
        assertNotNull(advice)
        assertTrue(advice!!.contains("idx=1") && advice.contains("PAYWALL"))

        val riskyClick = ReActActionModel(
            thought = "Klik Coba Pro",
            reflectionOnLastStep = ReflectionStatus.INITIAL,
            action = AgentActionType.CLICK,
            targetIndex = 1
        )
        val veto = SymbolicEdgeWorldModel.evaluatePreActionEdgeGuard(
            decision = riskyClick,
            elements = elements,
            rules = rules,
            userGoal = "Putar lagu santai",
            alreadyVetoedEdgeKeys = emptySet()
        )
        assertNotNull(veto)
        assertTrue(veto!!.feedbackForBrain.contains("PAYWALL"))

        // Jika edge sudah pernah di-veto pada sesi ini, jangan mengunci retry selamanya
        val vetoRepeated = SymbolicEdgeWorldModel.evaluatePreActionEdgeGuard(
            decision = riskyClick,
            elements = elements,
            rules = rules,
            userGoal = "Putar lagu santai",
            alreadyVetoedEdgeKeys = setOf("com.app.example:id/btn_pro")
        )
        assertNull(vetoRepeated)
    }

    @Test
    fun `skema Room v6 memiliki MIGRATION_5_6 non-destruktif serta kapasitas 2000 rule di bawah 1,5 MB dan kueri RAM di bawah 0,1 ms`() = runTest {
        assertEquals(5, GepyDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, GepyDatabase.MIGRATION_5_6.endVersion)

        val executedSqls = mutableListOf<String>()
        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty() && args[0] is String) {
                executedSqls.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase
        GepyDatabase.MIGRATION_5_6.migrate(proxyDb)

        val joinedSql = executedSqls.joinToString("\n")
        assertTrue(joinedSql.contains("CREATE TABLE IF NOT EXISTS `ui_transition_rules`"))
        assertFalse(joinedSql.contains("DROP TABLE"))
        assertTrue(joinedSql.contains("idx_sewm_unique_edge"))
        assertTrue(joinedSql.contains("idx_sewm_pkg_screen"))
        assertTrue(joinedSql.contains("idx_sewm_updated"))

        val repo = SqliteMemoryRepository(dao = null)
        // Isi hingga melampaui batas keras 2.000 baris untuk menguji pemangkasan LRU & anggaran < 1,5 MB
        for (i in 1..2_050) {
            val screenIdx = i / 4
            repo.observeEdgeTransition(
                packageName = "com.example.app",
                screenSignatureHash = "scr_$screenIdx",
                edgeKey = "com.example.app:id/btn_$i",
                actionType = "CLICK",
                targetLabelSample = "tombol_$i",
                outcome = if (i % 10 == 0) PredictedEdgeOutcome.PAYWALL else PredictedEdgeOutcome.SAFE_PROGRESS
            )
        }

        val stats = repo.getEdgeWorldModelStats()
        assertTrue("RAM rules (${stats.totalRulesInRam}) tidak boleh melebihi cap 2000", stats.totalRulesInRam <= 2_000)
        assertEquals(2_000, stats.maxDbRulesCap)
        assertTrue(
            "Estimasi SQLite (${stats.estimatedSqliteBytes} B) harus < 1,5 MB (1_500_000 B)",
            stats.estimatedSqliteBytes < 1_500_000L
        )

        // Warm-up JIT sebelum benchmark mikro < 0,1 ms per kueri
        repeat(2_000) {
            repo.predictEdgeRisksForScreenFast("com.example.app", "scr_500")
        }
        val iterations = 10_000
        val startNs = System.nanoTime()
        var totalHits = 0
        repeat(iterations) { iter ->
            totalHits += repo.predictEdgeRisksForScreenFast("com.example.app", "scr_${450 + (iter % 20)}").size
        }
        val elapsedNs = System.nanoTime() - startNs
        val avgNs = elapsedNs.toDouble() / iterations.toDouble()
        assertTrue(totalHits > 0)
        assertTrue(
            "Rata-rata kueri RAM SEWM harus < 0,1 ms (100.000 ns), terukur ${String.format(java.util.Locale.US, "%.1f", avgNs)} ns",
            avgNs < 100_000.0
        )
    }
}
