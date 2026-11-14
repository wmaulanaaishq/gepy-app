package com.teknologigawai.gepy.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class IndonesianSpeechNormalizerTest {

    @Test
    fun `normalizes spoken relative time to digits`() {
        assertEquals(
            "coba 2 menit dari sekarang ingetin untuk istirahat",
            IndonesianSpeechNormalizer.normalize("coba dua menit dari sekarang ingetin untuk istirahat")
        )
        assertEquals(
            "ingetin aku 1 menit lagi",
            IndonesianSpeechNormalizer.normalize("ingetin aku semenit lagi")
        )
        assertEquals(
            "pasang alarm 30 menit lagi",
            IndonesianSpeechNormalizer.normalize("pasang alarm setengah jam lagi")
        )
        assertEquals(
            "setel timer 10 detik",
            IndonesianSpeechNormalizer.normalize("setel timer sepuluh detik")
        )
        assertEquals(
            "bangunin aku jam 7 pagi",
            IndonesianSpeechNormalizer.normalize("bangunin aku jam tujuh pagi")
        )
        assertEquals(
            "atur volume ke 50 persen",
            IndonesianSpeechNormalizer.normalize("atur volume ke lima puluh persen")
        )
    }

    @Test
    fun `joins spaced spoken affixes`() {
        assertEquals(
            "coba ingetin aku nanti",
            IndonesianSpeechNormalizer.normalize("coba inget in aku nanti")
        )
        assertEquals(
            "tolong matiin senter sekarang",
            IndonesianSpeechNormalizer.normalize("tolong mati in senter sekarang")
        )
        assertEquals(
            "tolong nyalain lampunya",
            IndonesianSpeechNormalizer.normalize("tolong nyala in lampunya")
        )
        assertEquals(
            "bisa bukain aplikasi wa",
            IndonesianSpeechNormalizer.normalize("bisa buka in aplikasi wa")
        )
    }

    @Test
    fun `corrects common phonetic voice homophones in context`() {
        assertEquals(
            "tolong nyalakan senter",
            IndonesianSpeechNormalizer.normalize("tolong nyalakan center")
        )
        assertEquals(
            "putar lagu tulus di spotify",
            IndonesianSpeechNormalizer.normalize("putar lagu tulus di spotipai")
        )
        assertEquals(
            "buka aplikasi youtube",
            IndonesianSpeechNormalizer.normalize("buka aplikasi yutub")
        )
        assertEquals(
            "kirim pesan whatsapp ke ibu",
            IndonesianSpeechNormalizer.normalize("kirim pesan wasap ke ibu")
        )
    }
}
