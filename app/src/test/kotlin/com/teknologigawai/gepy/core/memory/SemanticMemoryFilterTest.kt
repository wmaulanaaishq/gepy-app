package com.teknologigawai.gepy.core.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMemoryFilterTest {

    @Test
    fun `menolak idiom emosional dan keluhan sesaat yang diawali suka`() {
        assertFalse(
            "Harus menolak 'aku suka bingung'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("preference", "Pengguna suka bingung", "bingung")
        )
        assertFalse(
            "Harus menolak 'suka pusing'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("preference", "Pengguna suka pusing kalau ngoding", "pusing kalau ngoding")
        )
        assertFalse(
            "Harus menolak 'suka heran'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("preference", "Pengguna suka heran sama responnya", "heran")
        )
        assertFalse(
            "Harus menolak 'suka lupa'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("preference", "Pengguna suka lupa naruh kunci", "lupa")
        )
        assertTrue(
            "isTransientIdiomOrNoise harus mendeteksi 'aku suka bingung deh'",
            SemanticMemoryFilter.isTransientIdiomOrNoise("aku suka bingung deh kalau kamu ngomong gitu")
        )
    }

    @Test
    fun `menolak instruksi navigasi atau komentar layar ponsel`() {
        assertFalse(
            "Harus menolak 'fokus ke layar dulu'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("tech_profile", "Fokus riset AI pengguna: ke layar dulu", "ke layar dulu")
        )
        assertFalse(
            "Harus menolak 'fokus ke tombol'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("tech_profile", "Fokus koding pengguna: ke tombol merah", "ke tombol merah")
        )
        assertFalse(
            "Harus menolak 'benerin bug'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("tech_profile", "Fokus koding pengguna: benerin bug ini gep", "benerin bug ini gep")
        )
        assertTrue(
            "isTransientIdiomOrNoise harus mendeteksi perintah layar",
            SemanticMemoryFilter.isTransientIdiomOrNoise("fokus ke tombol ini dulu ya gep")
        )
    }

    @Test
    fun `menolak lokasi umum atau kondisi situasional sesaat`() {
        assertFalse(
            "Harus menolak 'kantor pos'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("work", "Pengguna bekerja di kantor pos mana ya", "kantor pos mana ya")
        )
        assertFalse(
            "Harus menolak 'mati lampu'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("work", "Pengguna bekerja di mati lampu", "mati lampu")
        )
        assertFalse(
            "Harus menolak 'rumah sakit'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("location", "Pengguna tinggal di rumah sakit", "rumah sakit")
        )
    }

    @Test
    fun `menolak keluhan rutinitas terkait aplikasi atau ponsel`() {
        assertFalse(
            "Harus menolak keluhan aplikasi lemot",
            SemanticMemoryFilter.isMeaningfulPersonalFact("routine", "Rutinitas pengguna: nih aplikasinya lemot banget", "nih aplikasinya lemot banget")
        )
    }

    @Test
    fun `menerima fakta personal sejati yang bermakna dan abadi`() {
        assertTrue(
            "Harus menerima preferensi kuliner riil",
            SemanticMemoryFilter.isMeaningfulPersonalFact("preference", "Minuman favorit pengguna: Americano dingin tanpa gula", "Americano dingin tanpa gula")
        )
        assertTrue(
            "Harus menerima fakta keluarga",
            SemanticMemoryFilter.isMeaningfulPersonalFact("family", "Adik pengguna bernama Farhan", "Farhan")
        )
        assertTrue(
            "Harus menerima tempat kerja valid",
            SemanticMemoryFilter.isMeaningfulPersonalFact("work", "Pengguna bekerja di Bank Mandiri", "Bank Mandiri")
        )
        assertTrue(
            "Harus menerima domisili sah",
            SemanticMemoryFilter.isMeaningfulPersonalFact("location", "Pengguna tinggal di Sleman Yogyakarta", "Sleman Yogyakarta")
        )
        assertTrue(
            "Harus menerima alergi medis",
            SemanticMemoryFilter.isMeaningfulPersonalFact("health", "Pengguna alergi seafood dan udang", "seafood dan udang")
        )
        assertTrue(
            "Harus menerima profil koding & tech stack sejati",
            SemanticMemoryFilter.isMeaningfulPersonalFact("tech_profile", "Fokus koding & tech stack pengguna: Python, LangGraph, FastAPI, Vector DB", "Python, LangGraph, FastAPI, Vector DB")
        )
        assertTrue(
            "Harus menerima spesifikasi laptop pribadi",
            SemanticMemoryFilter.isMeaningfulPersonalFact("device", "Laptop pengguna: ASUS ROG Zephyrus", "ASUS ROG Zephyrus")
        )
        assertTrue(
            "Harus menerima proyek pribadi deklaratif",
            SemanticMemoryFilter.isMeaningfulPersonalFact("project", "Proyek pengguna: Gepy Native", "Gepy Native")
        )
    }

    @Test
    fun `menolak kalimat perintah aksi dan pertanyaan seperti cek kondisi laptop`() {
        assertFalse(
            "Harus menolak perintah 'cek kondisi laptop'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("device", "cek kondisi laptop", "device_laptop")
        )
        assertFalse(
            "Harus menolak perintah berawalan sapaan 'Gep tolong kunci laptop saya'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("device", "Gep tolong kunci laptop saya", "device_laptop")
        )
        assertFalse(
            "Harus menolak pertanyaan 'Apa laptop saya?'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("device", "Apa laptop saya?", "device_laptop")
        )
        assertFalse(
            "Harus menolak perintah 'buka vscode di laptop'",
            SemanticMemoryFilter.isMeaningfulPersonalFact("project", "buka vscode di laptop", "vscode")
        )
        assertTrue(
            "isTransientIdiomOrNoise harus mendeteksi perintah cek kondisi laptop",
            SemanticMemoryFilter.isTransientIdiomOrNoise("cek kondisi laptop")
        )
        assertTrue(
            "isTransientIdiomOrNoise harus mendeteksi perintah kunci laptop",
            SemanticMemoryFilter.isTransientIdiomOrNoise("Gep tolong kunci laptop saya")
        )
    }
}
