package com.teknologigawai.gepy.features.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashEngineTest {

    @Test
    fun `Hamming distance bernilai 0 untuk hash identik dan menghitung selisih bit dengan tepat`() {
        val hashA = 0x55AA_F0F0_55AA_F0F0L
        val hashB = hashA
        // Hash C berbeda 2 bit dari hash A
        val hashC = hashA xor 0x0000_0000_0000_0300L
        // Hash D kebalikan total (64 bit berbeda)
        val hashD = hashA.inv()

        assertEquals("Jarak hash identik harus 0", 0, PerceptualHashEngine.hammingDistance(hashA, hashB))
        assertEquals("Jarak dengan beda 2 bit harus 2", 2, PerceptualHashEngine.hammingDistance(hashA, hashC))
        assertEquals("Jarak bit terbalik harus 64", 64, PerceptualHashEngine.hammingDistance(hashA, hashD))

        assertTrue("Hash A dan Hash C harus dianggap serupa (dist <= 3)", PerceptualHashEngine.areVisuallySimilar(hashA, hashC))
        assertFalse("Hash A dan Hash D tidak boleh dianggap serupa", PerceptualHashEngine.areVisuallySimilar(hashA, hashD))
    }

    @Test
    fun `computeDHash fallback deterministik menghasilkan hash konsisten untuk byte yang sama`() {
        val sampleBytesA = ByteArray(256) { (it * 7 % 255).toByte() }
        val sampleBytesB = sampleBytesA.clone()
        val sampleBytesDifferent = ByteArray(256) { (255 - (it * 13 % 255)).toByte() }

        val hashA = PerceptualHashEngine.computeDHash(sampleBytesA)
        val hashB = PerceptualHashEngine.computeDHash(sampleBytesB)
        val hashDiff = PerceptualHashEngine.computeDHash(sampleBytesDifferent)

        assertEquals("Hash dari bytes identik harus persis sama", hashA, hashB)
        assertTrue("Jarak visual sampel identik harus 0", PerceptualHashEngine.areVisuallySimilar(hashA, hashB))
        assertFalse("Bytes berbeda tidak boleh menghasilkan hash identik", hashA == hashDiff)
    }
}
