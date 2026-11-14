package com.teknologigawai.gepy.core.memory

import com.teknologigawai.gepy.features.voice.GeminiLiveClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMemoryManagerTest {

    @Test
    fun `Heuristic name extractor mendeteksi pola lisan Bahasa Indonesia secara akurat`() {
        val testCases = mapOf(
            "Halo Gepy, kenalkan namaku Maulana" to "Maulana",
            "Nama saya Budi Santoso" to "Budi",
            "Panggil aku Siti ya" to "Siti",
            "Perkenalkan saya Ishaq" to "Ishaq",
            "Kenalkan, aku Doni" to "Doni"
        )

        testCases.forEach { (input, expectedName) ->
            val match = UserMemoryManager.NAME_EXTRACTION_REGEX.find(input)
            assertNotNull("Regex harus cocok dengan kalimat: $input", match)
            val extracted = match!!.groupValues[1].replaceFirstChar { it.uppercase() }
            assertEquals(expectedName, extracted)
        }
    }

    @Test
    fun `GeminiLiveClient mendaftarkan tool save_user_memory dengan parameter fact`() {
        val client = GeminiLiveClient(apiKey = "test_key")
        val setup = client.buildSetupMessageJson().getJSONObject("setup")
        val declarations = setup
            .getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")

        var foundSaveMemory = false
        for (i in 0 until declarations.length()) {
            val decl = declarations.getJSONObject(i)
            if (decl.getString("name") == "save_user_memory") {
                foundSaveMemory = true
                val params = decl.getJSONObject("parameters")
                assertTrue(params.has("properties"))
                val props = params.getJSONObject("properties")
                assertTrue("Harus memiliki parameter fact", props.has("fact"))
                assertTrue("Harus memiliki parameter category", props.has("category"))
                val required = params.getJSONArray("required")
                assertEquals("fact", required.getString(0))
            }
        }
        assertTrue("Tool save_user_memory wajib terdaftar di Gemini Live tools", foundSaveMemory)
    }

    @Test
    fun `GeminiLiveClient menginjeksi konteks memori pengguna ke dalam systemInstruction`() {
        val client = GeminiLiveClient(apiKey = "test_key")
        val memoryContext = "- Nama Pengguna: Maulana\n- Fakta: Pengguna menyukai kopi hitam"
        val setup = client.buildSetupMessageJson(userContext = memoryContext).getJSONObject("setup")

        val systemInstruction = setup.getJSONObject("systemInstruction")
        val instructionText = systemInstruction.getJSONArray("parts").getJSONObject(0).getString("text")

        assertTrue("System instruction harus memuat header memori", instructionText.contains("[MEMORI PERSONAL PENGGUNA DARI SESI SEBELUMNYA]"))
        assertTrue("System instruction harus memuat nama pengguna", instructionText.contains("Nama Pengguna: Maulana"))
        assertTrue("System instruction harus memuat instruksi panggil save_user_memory", instructionText.contains("save_user_memory"))
        assertTrue("System instruction harus memuat aturan end_live_call", instructionText.contains("end_live_call"))
        assertTrue("System instruction harus memuat aturan koreksi memori", instructionText.contains("update_or_correct_memory"))
    }

    @Test
    fun `GeminiLiveClient mendaftarkan tool update_or_correct_memory dan delete_user_memory`() {
        val client = GeminiLiveClient(apiKey = "test_key")
        val setup = client.buildSetupMessageJson().getJSONObject("setup")
        val declarations = setup
            .getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")

        var foundUpdate = false
        var foundDelete = false
        for (i in 0 until declarations.length()) {
            val decl = declarations.getJSONObject(i)
            if (decl.getString("name") == "update_or_correct_memory") {
                foundUpdate = true
                val params = decl.getJSONObject("parameters")
                assertTrue(params.has("properties"))
                val props = params.getJSONObject("properties")
                assertTrue("Harus memiliki parameter target_to_replace", props.has("target_to_replace"))
                assertTrue("Harus memiliki parameter corrected_fact", props.has("corrected_fact"))
            }
            if (decl.getString("name") == "delete_user_memory") {
                foundDelete = true
                val params = decl.getJSONObject("parameters")
                assertTrue(params.has("properties"))
                val props = params.getJSONObject("properties")
                assertTrue("Harus memiliki parameter target", props.has("target"))
            }
        }
        assertTrue("Tool update_or_correct_memory wajib terdaftar", foundUpdate)
        assertTrue("Tool delete_user_memory wajib terdaftar", foundDelete)
    }

    @Test
    fun `Heuristic name extractor mengenali pola koreksi nama eksplisit dan mengabaikan negasi`() {
        val manager = UserMemoryManager()
        
        // Kasus 1: Koreksi eksplisit "namaku bukan Ricky tapi Rizky"
        manager.inspectTranscriptForProfile("namaku bukan Ricky tapi Rizky")
        assertEquals("Rizky", manager.getUserDisplayName())

        // Kasus 2: Frasa "bukan ricky tapi namaku Anton"
        manager.inspectTranscriptForProfile("bukan ricky tapi namaku Anton")
        assertEquals("Anton", manager.getUserDisplayName())
    }

    @Test
    fun `UserMemoryManager dapat menyimpan, mengoreksi salah dengar dan menghapus fakta`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()

        // 1. Simpan fakta awal (salah dengar: Ricky)
        manager.recordMemory(
            category = "family",
            fact = "Adik pengguna bernama Ricky",
            subject = "family_siblings"
        )
        val initialFacts = manager.getLocalFacts()
        assertTrue("Fakta awal Ricky harus tersimpan", initialFacts.contains("Adik pengguna bernama Ricky"))

        // 2. Koreksi memori: ganti Ricky dengan Rizky
        manager.updateOrCorrectMemory(
            targetToReplace = "Ricky",
            correctedFact = "Adik pengguna bernama Rizky",
            category = "family",
            subject = "family_siblings"
        )
        val updatedFacts = manager.getLocalFacts()
        assertTrue("Fakta baru Rizky harus tersimpan", updatedFacts.contains("Adik pengguna bernama Rizky"))
        assertTrue("Fakta lama Ricky TIDAK boleh ada lagi", updatedFacts.none { it.contains("Ricky", ignoreCase = true) })

        // 3. Hapus fakta
        val deleted = manager.deleteMemory("Rizky")
        assertTrue("Penghapusan harus berhasil", deleted)
        assertTrue("Fakta Rizky harus terhapus", manager.getLocalFacts().isEmpty())
    }

    @Test
    fun `UserMemoryManager mengelompokkan kategori informasi keluarga majemuk tanpa pemotongan prematur take 5`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana")

        // Tambah 7 fakta: 3 adik, 2 orang tua, alamat, dan kopi
        manager.recordMemory("family", "Adik pengguna ada 3 orang: Doni, Farah, dan Galih", "family_siblings")
        manager.recordMemory("family", "Ayah pengguna bernama Herman", "family_father")
        manager.recordMemory("family", "Ibu pengguna bernama Siti", "family_mother")
        manager.recordMemory("location", "Pengguna tinggal di Bandung", "location_home")
        manager.recordMemory("preference", "Pengguna suka kopi hitam tanpa gula", "pref_coffee")
        manager.recordMemory("preference", "Pengguna alergi udang", "pref_allergy")
        manager.recordMemory("profile", "Pengguna bekerja sebagai pengembang perangkat lunak", "profile_work")

        val contextString = manager.getSystemInstructionContext()

        // Pastikan seluruh 7 fakta TIDAK ada yang hilang / terpotong oleh limit lama (take 5)
        assertTrue("Harus memuat header keluarga", contextString.contains("Informasi Keluarga:"))
        assertTrue("Harus memuat adik Doni, Farah, dan Galih", contextString.contains("Doni, Farah, dan Galih"))
        assertTrue("Harus memuat Ayah Herman", contextString.contains("Ayah pengguna bernama Herman"))
        assertTrue("Harus memuat Ibu Siti", contextString.contains("Ibu pengguna bernama Siti"))
        assertTrue("Harus memuat Bandung", contextString.contains("Pengguna tinggal di Bandung"))
        assertTrue("Harus memuat kopi hitam", contextString.contains("Pengguna suka kopi hitam"))
        assertTrue("Harus memuat alergi udang", contextString.contains("Pengguna alergi udang"))
        assertTrue("Harus memuat profil pekerjaan", contextString.contains("pengembang perangkat lunak"))
    }

    @Test
    fun `GeminiLiveClient mendaftarkan tool end_live_call dengan parameter farewell`() {
        val client = GeminiLiveClient(apiKey = "test_key")
        val setup = client.buildSetupMessageJson().getJSONObject("setup")
        val declarations = setup
            .getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")

        var foundEndCall = false
        for (i in 0 until declarations.length()) {
            val decl = declarations.getJSONObject(i)
            if (decl.getString("name") == "end_live_call") {
                foundEndCall = true
                val params = decl.getJSONObject("parameters")
                assertTrue(params.has("properties"))
                val props = params.getJSONObject("properties")
                assertTrue("Harus memiliki parameter farewell", props.has("farewell"))
            }
        }
        assertTrue("Tool end_live_call wajib terdaftar di Gemini Live tools", foundEndCall)
    }

    @Test
    fun `VoiceAudioEngine HANGUP_KEYWORDS mengenali perintah suara pamit tutup telepon`() {
        val testPhrases = listOf(
            "gepy tutup telepon ya",
            "tolong matikan telepon",
            "akhiri panggilan sekarang",
            "sudah dulu ya gepy terima kasih",
            "sampai jumpa gepy",
            "bye gepy"
        )

        testPhrases.forEach { phrase ->
            val matches = com.teknologigawai.gepy.features.voice.VoiceAudioEngine.HANGUP_KEYWORDS.any {
                phrase.contains(it)
            }
            assertTrue("Frasa '$phrase' harus dikenali sebagai perintah tutup telepon", matches)
        }
    }

    @Test
    fun `UserMemoryManager export and import vault snapshot preserves memory across uninstalls`() = kotlinx.coroutines.runBlocking {
        var simulatedTime = 1731000000000L
        val manager = UserMemoryManager(
            clockMsProvider = { simulatedTime }
        )

        // Simpan fakta awal
        manager.recordMemory(
            category = "profile",
            fact = "Nama panggilan saya adalah Budi Santoso",
            subject = "user_name"
        )
        manager.recordMemory(
            category = "preference",
            fact = "Saya suka kopi tubruk tanpa gula",
            subject = "kopi"
        )

        val exportedJson = manager.exportVaultJson(appVersion = "5.2.0")
        assertTrue("JSON cadangan harus memuat nama pengguna", exportedJson.contains("Budi Santoso"))
        assertTrue("JSON cadangan harus memuat preferensi kopi", exportedJson.contains("kopi tubruk"))

        // Simulasikan HP baru atau setelah uninstall bersih
        val freshManager = UserMemoryManager(
            clockMsProvider = { simulatedTime }
        )
        assertEquals(0, freshManager.getAllTemporalFactsSnapshot().size)

        // Pulihkan dari JSON cadangan
        val importResult = freshManager.importVaultJson(exportedJson)
        assertTrue(importResult is com.teknologigawai.gepy.core.memory.backup.VaultImportResult.Success)
        val success = importResult as com.teknologigawai.gepy.core.memory.backup.VaultImportResult.Success
        assertEquals(2, success.importedCount)
        assertEquals(0, success.skippedCount)

        // Verifikasi fakta terpulihkan di manager baru
        val restoredFacts = freshManager.getAllTemporalFactsSnapshot()
        assertEquals(2, restoredFacts.size)
        assertTrue(restoredFacts.any { it.factText.contains("kopi tubruk") })
    }

    @Test
    fun `Identity Shield melindungi nama dari pembajakan oleh percakapan kasual dan salah dengar`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)
        assertEquals("Maulana", manager.getUserDisplayName())
        assertTrue(manager.isNameConfirmed())

        // 1. Frasa sapaan: "Gep, panggil temanku mas Budi"
        manager.inspectTranscriptForProfile("Gep, panggil temanku mas Budi")
        assertEquals("Nama harus tetap Maulana, tidak boleh terbajak", "Maulana", manager.getUserDisplayName())

        // 2. Frasa "panggil aku mas"
        manager.inspectTranscriptForProfile("panggil aku mas ya gep")
        assertEquals("Nama harus tetap Maulana, mas adalah gelar", "Maulana", manager.getUserDisplayName())

        // 3. Frasa "kenalkan temanku Joko"
        manager.inspectTranscriptForProfile("kenalkan temanku Joko")
        assertEquals("Nama harus tetap Maulana", "Maulana", manager.getUserDisplayName())

        // 4. Frasa "kenalkan ini kawan saya"
        manager.inspectTranscriptForProfile("kenalkan ini kawan saya")
        assertEquals("Nama harus tetap Maulana", "Maulana", manager.getUserDisplayName())

        // 5. Salah dengar / waktu: "namaku nanti sore mau belanja"
        manager.inspectTranscriptForProfile("namaku nanti sore mau belanja")
        assertEquals("Nama harus tetap Maulana, 'nanti' adalah waktu", "Maulana", manager.getUserDisplayName())

        // 6. Topik diskusi film / bioskop (Penyebab bug 'Fim') DILARANG membajak nama pengguna
        manager.inspectTranscriptForProfile("salah satu film favoritku yang baru kutonton itu bagus banget")
        assertEquals("Nama harus tetap Maulana, 'film' dilarang membajak nama", "Maulana", manager.getUserDisplayName())

        manager.inspectTranscriptForProfile("bukan cuma film horor yang saya suka")
        assertEquals("Nama harus tetap Maulana", "Maulana", manager.getUserDisplayName())

        manager.inspectTranscriptForProfile("ada adegan panggil polisi di film itu")
        assertEquals("Nama harus tetap Maulana, 'polisi' dilarang jadi nama", "Maulana", manager.getUserDisplayName())

        // 7. Koreksi eksplisit branch 1: "namaku bukan Maulana tapi Rizky"
        manager.inspectTranscriptForProfile("namaku bukan Maulana tapi Rizky")
        assertEquals("Koreksi eksplisit harus berhasil mengubah nama", "Rizky", manager.getUserDisplayName())

        // 8. Koreksi eksplisit branch 2: "ralat namaku jadi Anton"
        manager.inspectTranscriptForProfile("ralat namaku jadi Anton")
        assertEquals("Ralat nama harus berhasil mengubah nama", "Anton", manager.getUserDisplayName())

        // 9. Koreksi eksplisit branch 3: "bukan Anton, tapi namaku Bambang"
        manager.inspectTranscriptForProfile("bukan Anton, tapi namaku Bambang")
        assertEquals("Koreksi negasi awal harus berhasil mengubah nama", "Bambang", manager.getUserDisplayName())
    }

    @Test
    fun `Diskusi film dan media pada kondisi awal tanpa nama tidak pernah menghasilkan nama korup Fim atau Film`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        assertEquals("", manager.getUserDisplayName())
        assertFalse(manager.isNameConfirmed())

        // Percakapan tentang film saat nama pengguna masih kosong
        manager.inspectTranscriptForProfile("kemarin aku nonton salah satu film bioskop seru")
        assertEquals("Nama tidak boleh menjadi Film atau Fim", "", manager.getUserDisplayName())

        manager.inspectTranscriptForProfile("salah satu film horor paling menegangkan")
        assertEquals("Nama tidak boleh menjadi Film", "", manager.getUserDisplayName())

        manager.inspectTranscriptForProfile("bukan cuma film komedi yang kusukai")
        assertEquals("Nama tidak boleh berubah", "", manager.getUserDisplayName())

        manager.inspectTranscriptForProfile("tidak ada bioskop di dekat rumahku")
        assertEquals("Nama tidak boleh berubah", "", manager.getUserDisplayName())

        // Namun perkenalan resmi harus tetap berhasil
        manager.inspectTranscriptForProfile("Halo gep, nama saya Rizky")
        assertEquals("Rizky", manager.getUserDisplayName())
        assertTrue(manager.isNameConfirmed())
    }

    @Test
    fun `Auto-Heal membersihkan nama korup Fim atau Film di SharedPreferences dan mengembalikan identitas bersih`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()

        // Simulasikan kondisi bug lama di mana "Fim" atau "Film" tersimpan di penyimpanan lokal
        manager.setUserDisplayName("Rizky", isConfirmed = true)
        assertEquals("Rizky", manager.getUserDisplayName())

        // Paksa injeksi nama korup ke prefs untuk menguji Auto-Heal
        // Menggunakan refleksi atau helper internal
        val method = UserMemoryManager::class.java.getDeclaredMethod("setPrefString", String::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(manager, "key_custom_display_name", "Fim")

        // Panggil getUserDisplayName(): Auto-Heal harus mendeteksi 'Fim' sebagai blacklisted, menghapusnya, dan mengembalikan ""
        val healedName = manager.getUserDisplayName()
        assertEquals("Auto-Heal harus menghapus nama 'Fim' dan mengembalikan string kosong", "", healedName)
        assertFalse("isNameConfirmed harus false setelah auto-heal", manager.isNameConfirmed())

        // Simulasikan nama korup 'Film'
        method.invoke(manager, "key_custom_display_name", "Film")
        assertEquals("", manager.getUserDisplayName())
        assertFalse(manager.isNameConfirmed())
    }

    @Test
    fun `Multi-valued family siblings mempertahankan seluruh adik aktif tanpa saling mematikan`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()

        manager.recordMemory(category = "family", fact = "Adik pengguna bernama Doni")
        manager.recordMemory(category = "family", fact = "Adik pengguna bernama Farah")
        manager.recordMemory(category = "family", fact = "Adik pengguna bernama Galih")

        val activeFacts = manager.getActiveTemporalFactsSync().map { it.factText }
        assertEquals(3, activeFacts.size)
        assertTrue(activeFacts.any { it.contains("Doni") })
        assertTrue(activeFacts.any { it.contains("Farah") })
        assertTrue(activeFacts.any { it.contains("Galih") })

        val context = manager.getSystemInstructionContext()
        assertTrue(context.contains("Doni"))
        assertTrue(context.contains("Farah"))
        assertTrue(context.contains("Galih"))
    }

    @Test
    fun `Teman dan relasi sosial masuk kategori abadi dan muncul di konteks instruksi sistem`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()

        manager.recordMemory(category = "friends", fact = "Teman dekat pengguna bernama Budi")
        manager.recordMemory(category = "friends", fact = "Sahabat pengguna bernama Joko")

        val active = manager.getActiveTemporalFactsSync()
        assertEquals(2, active.size)
        assertTrue(active.all { it.category == "friends" })

        val context = manager.getSystemInstructionContext()
        assertTrue("Harus memuat bagian Teman Dekat & Relasi Sosial", context.contains("Teman Dekat & Relasi Sosial:"))
        assertTrue(context.contains("Budi"))
        assertTrue(context.contains("Joko"))
    }

    @Test
    fun `Two-Tier Working Memory menjamin keluarga dan teman tidak tersingkir oleh puluhan catatan baru`() = kotlinx.coroutines.runBlocking {
        var clock = 1731000000000L
        val manager = UserMemoryManager(clockMsProvider = { clock })
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        // Simpan fakta inti keluarga & teman di masa lalu (clock awal)
        manager.recordMemory(category = "family", fact = "Ibu pengguna bernama Siti")
        manager.recordMemory(category = "family", fact = "Adik pengguna bernama Doni")
        manager.recordMemory(category = "friends", fact = "Teman dekat pengguna bernama Budi")
        manager.recordMemory(category = "health", fact = "Pengguna alergi udang dan kepiting")

        // Majukan waktu 10 hari dan tambahkan 35 catatan & jadwal harian baru (melebihi limit 30)
        clock += 10 * 86_400_000L
        for (i in 1..35) {
            manager.recordMemory(category = "schedule", fact = "Catatan agenda rapat ke-$i")
        }

        val context = manager.getSystemInstructionContext()

        // Verifikasi mutlak: Fakta inti keluarga, teman, alergi, dan nama TIDAK PERNAH terbuang
        assertTrue("Nama harus tetap ada di context", context.contains("Nama Pengguna: Maulana"))
        assertTrue("Ibu Siti harus tetap ada", context.contains("Ibu pengguna bernama Siti"))
        assertTrue("Adik Doni harus tetap ada", context.contains("Adik pengguna bernama Doni"))
        assertTrue("Teman Budi harus tetap ada", context.contains("Teman dekat pengguna bernama Budi"))
        assertTrue("Alergi udang harus tetap ada", context.contains("Pengguna alergi udang"))
    }

    @Test
    fun `Ekstraksi fakta percakapan personal menyimpan data keluarga, pekerjaan, domisili, dan kesehatan otomatis`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        // 1. Fakta keluarga dari transkrip alami
        manager.inspectTranscriptForProfile("Adikku namanya Farhan")
        val factsAfterAdik = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Adik Farhan harus tersimpan otomatis", factsAfterAdik.any { it.contains("Farhan") && it.contains("Adik") })

        // 2. Fakta pekerjaan dari transkrip alami
        manager.inspectTranscriptForProfile("Saya bekerja di Bank Mandiri")
        val factsAfterWork = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Pekerjaan Bank Mandiri harus tersimpan", factsAfterWork.any { it.contains("Bank Mandiri") })

        // 3. Fakta lokasi domisili dari transkrip alami
        manager.inspectTranscriptForProfile("Saya tinggal di Sleman Yogyakarta")
        val factsAfterLoc = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Domisili Sleman harus tersimpan", factsAfterLoc.any { it.contains("Sleman") })

        // 4. Fakta alergi dari transkrip alami
        manager.inspectTranscriptForProfile("Saya alergi seafood dan kacang")
        val factsAfterHealth = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Alergi seafood harus tersimpan", factsAfterHealth.any { it.contains("seafood") })

        // 5. Direktif simpan eksplisit
        manager.inspectTranscriptForProfile("Ingat ya besok jam 10 ada rapat evaluasi")
        val factsAfterDirective = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Direktif rapat evaluasi harus tersimpan", factsAfterDirective.any { it.contains("rapat evaluasi") })
    }

    @Test
    fun `Kalimat interogatif atau pertanyaan pengguna tidak pernah diekstrak menjadi fakta memori baru`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        val countBefore = manager.getActiveTemporalFactsSync().size

        // Pertanyaan tidak boleh menjadi fakta memori!
        manager.inspectTranscriptForProfile("Siapa nama adikku?")
        manager.inspectTranscriptForProfile("Saya kerja di mana ya?")
        manager.inspectTranscriptForProfile("Apa jadwal saya hari ini?")
        manager.inspectTranscriptForProfile("Kamu ingat gak siapa temanku?")

        val countAfter = manager.getActiveTemporalFactsSync().size
        assertEquals("Pertanyaan tidak boleh menambah fakta baru ke memori", countBefore, countAfter)
    }

    @Test
    fun `onMemoryChangedListener dipanggil saat ada fakta tersimpan`() {
        val manager = UserMemoryManager()
        var callbackTriggered = false
        var lastContext = ""
        manager.onMemoryChangedListener = { ctx ->
            callbackTriggered = true
            lastContext = ctx
        }

        manager.recordMemory(category = "work", fact = "Pengguna bekerja di Google")
        assertTrue("Listener harus terpanggil", callbackTriggered)
        assertTrue("Konteks harus memuat pekerjaan baru", lastContext.contains("Google"))
    }

    @Test
    fun `extractAndRecordConversationalFacts mengekstrak fakta ulang tahun, kendaraan plat nomor, rutinitas, kuliner favorit, dan atasan`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        // 1. Ulang tahun
        manager.inspectTranscriptForProfile("Ulang tahunku 15 Mei")
        val factsBday = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Ulang tahun harus tersimpan", factsBday.any { it.contains("15 Mei") })

        // 2. Kendaraan & plat nomor
        manager.inspectTranscriptForProfile("Plat motorku AB 1234 CD")
        val factsVeh = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Plat motor harus tersimpan", factsVeh.any { it.contains("AB 1234 CD") })

        // 3. Rutinitas bangun pagi
        manager.inspectTranscriptForProfile("Saya biasa bangun jam 5 pagi")
        val factsRoutine = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Rutinitas bangun jam 5 pagi harus tersimpan", factsRoutine.any { it.contains("5 pagi") })

        // 4. Kopi & kuliner favorit
        manager.inspectTranscriptForProfile("Kopi favoritku Americano dingin tanpa gula")
        val factsFood = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Kopi favorit harus tersimpan", factsFood.any { it.contains("Americano dingin") })

        // 5. Atasan / Bos
        manager.inspectTranscriptForProfile("Bosku namanya Pak Bambang")
        val factsBoss = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Nama bos harus tersimpan", factsBoss.any { it.contains("Pak Bambang") })

        // 6. Pertanyaan seputar entitas baru tidak boleh diekstrak
        val countBefore = manager.getActiveTemporalFactsSync().size
        manager.inspectTranscriptForProfile("Kapan ulang tahunku?")
        manager.inspectTranscriptForProfile("Berapa nomor plat motorku?")
        manager.inspectTranscriptForProfile("Jam berapa saya biasa bangun?")
        val countAfter = manager.getActiveTemporalFactsSync().size
        assertEquals("Pertanyaan tidak boleh diekstrak sebagai fakta", countBefore, countAfter)
    }

    @Test
    fun `extractAndRecordConversationalFacts mengekstrak fakta akademik mahasiswa informatika dan minat riset AI`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        // 1. Mahasiswa informatika
        manager.inspectTranscriptForProfile("Aku mahasiswa informatika")
        val factsEdu = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Status mahasiswa informatika harus tersimpan", factsEdu.any { it.contains("informatika") })

        // 2. Minat riset AI/ML agentic system
        manager.inspectTranscriptForProfile("Minat risetku AI/ML agentic systems")
        val factsTech = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Minat riset AI/ML harus tersimpan", factsTech.any { it.contains("AI/ML agentic systems") })

        // 3. Tech stack favorit
        manager.inspectTranscriptForProfile("Bahasa pemrograman favoritku Kotlin dan Python")
        val factsStack = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Tech stack favorit harus tersimpan", factsStack.any { it.contains("Kotlin dan Python") })

        // 4. Spesialisasi fokus koding Python, LangChain, LangGraph, FastAPI, Vector DB, RAG
        manager.inspectTranscriptForProfile("kalau coding sih saya fokusnya di python langchain langgraph fastapi, vector database, rag dan lainnya")
        val factsCoding = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Fokus koding Python dan LangGraph harus tersimpan", factsCoding.any { it.contains("python langchain langgraph", ignoreCase = true) })
    }

    @Test
    fun `extractAndRecordConversationalFacts mengekstrak fakta hewan peliharaan, perangkat gadget, proyek startup, cita-cita, dan pantangan diet`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        // 1. Kucing peliharaan
        manager.inspectTranscriptForProfile("Kucing saya namanya Miko")
        val factsPet = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Nama kucing Miko harus tersimpan", factsPet.any { it.contains("Miko") })

        // 2. Laptop & Gadget
        manager.inspectTranscriptForProfile("Laptop saya ASUS ROG Zephyrus")
        manager.inspectTranscriptForProfile("HP saya Samsung Galaxy A06")
        val factsDevice = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Laptop ASUS ROG harus tersimpan", factsDevice.any { it.contains("ASUS ROG Zephyrus") })
        assertTrue("HP Samsung A06 harus tersimpan", factsDevice.any { it.contains("Samsung Galaxy A06") })

        // 3. Proyek & Startup
        manager.inspectTranscriptForProfile("Proyek saya namanya Gepy")
        val factsProject = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Proyek Gepy harus tersimpan", factsProject.any { it.contains("Gepy") })

        // 4. Cita-cita & Impian
        manager.inspectTranscriptForProfile("Cita-cita saya ingin jadi AI architect")
        val factsGoal = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Cita-cita AI architect harus tersimpan", factsGoal.any { it.contains("AI architect") })

        // 5. Diet & Vegetarian
        manager.inspectTranscriptForProfile("Saya seorang vegetarian")
        val factsDiet = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Pola diet vegetarian harus tersimpan", factsDiet.any { it.contains("vegetarian") })

        // 6. Universal Personal Statement
        manager.inspectTranscriptForProfile("Saya orangnya suka belajar teknologi baru")
        val allFacts = manager.getActiveTemporalFactsSync().map { it.factText }
        assertTrue("Universal personal statement harus tersimpan", allFacts.any { it.contains("teknologi baru") })
    }

    @Test
    fun `extractAndRecordConversationalFacts tidak menangkap destinasi universitas pada perintah transportasi sebagai kampus pengguna`() = kotlinx.coroutines.runBlocking {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()

        manager.inspectTranscriptForProfile("pesankan gojek ke universitas alma ata")
        val facts = manager.getActiveTemporalFactsSync().map { it.factText }
        assertFalse(
            "Destinasi perjalanan 'universitas alma ata' tidak boleh dianggap sebagai kampus pengguna",
            facts.any { it.contains("Alma Ata", ignoreCase = true) || it.contains("kuliah", ignoreCase = true) }
        )
    }

    @Test
    fun `getSystemInstructionContext menginjeksi seluruh kategori multi-domain ke Tier 1 secara unconditional`() {
        val manager = UserMemoryManager()
        manager.clearLocalMemory()
        manager.setUserDisplayName("Maulana", isConfirmed = true)

        manager.recordMemory("family", "Adik pengguna bernama Farhan", "family_siblings")
        manager.recordMemory("pet", "Kucing pengguna bernama Miko", "pet_cat")
        manager.recordMemory("device", "Laptop pengguna: ASUS ROG", "device_laptop")
        manager.recordMemory("project", "Proyek pengguna: Gepy", "user_project")
        manager.recordMemory("goal", "Cita-cita pengguna: Membangun AGI", "user_goal")
        manager.recordMemory("dietary", "Pengguna seorang vegetarian", "user_dietary")
        manager.recordMemory("education", "Pengguna mahasiswa informatika", "academic_major")
        manager.recordMemory("tech_profile", "Tech stack favorit pengguna: Kotlin dan Python", "tech_stack")

        val ctx = manager.getSystemInstructionContext()
        assertTrue("Harus memuat header keluarga", ctx.contains("- Informasi Keluarga:"))
        assertTrue("Harus memuat header hewan peliharaan", ctx.contains("- Hewan Peliharaan:"))
        assertTrue("Harus memuat header tech stack & gadget", ctx.contains("- Minat Riset, Tech Stack & Gadget:"))
        assertTrue("Harus memuat header proyek & impian", ctx.contains("- Proyek, Startup & Impian/Target Hidup:"))
        assertTrue("Harus memuat header kesehatan & diet", ctx.contains("- Kesehatan, Alergi & Pantangan Makan:"))
        assertTrue("Harus memuat header pendidikan", ctx.contains("- Pendidikan & Kampus:"))
        assertTrue("Harus memuat Miko", ctx.contains("Miko"))
        assertTrue("Harus memuat ASUS ROG", ctx.contains("ASUS ROG"))
        assertTrue("Harus memuat Gepy", ctx.contains("Gepy"))
    }
}