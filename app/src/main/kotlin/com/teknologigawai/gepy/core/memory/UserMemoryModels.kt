package com.teknologigawai.gepy.core.memory

import java.util.UUID

/**
 * Entri memori terstruktur untuk profil, relasi keluarga, dan preferensi pengguna.
 */
data class UserMemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val category: String = "general",
    val subject: String = "",
    val fact: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val validUntilMs: Long? = null
)

/**
 * Hasil pencarian memori hibrida Layer 1 (BM25+ & Temporal KG) + Layer 2 (Vektor Semantik 768-dim)
 * yang digabungkan menggunakan *Reciprocal Rank Fusion* (RRF, $k = 60$).
 */
data class HybridMemoryMatch(
    val factText: String,
    val category: String,
    val subject: String,
    val rrfScore: Double,
    val bm25Score: Double,
    val semanticCosine: Float,
    val matchedLayer: String,
    val factId: Long? = null,
    val updatedAtMs: Long = 0L
)

/**
 * Ringkasan kapasitas dan pemakaian nyata memori jangka panjang (Layer 1 Temporal KG & Layer 2 Vektor Semantik).
 */
data class VectorMemoryStats(
    val ramVectorCount: Int,
    val maxRamVectors: Int,
    val dbVectorCount: Int,
    val maxDbVectors: Int,
    val activeTemporalFacts: Int,
    val estimatedVectorRamKb: Int
)

internal data class CachedVectorEntry(
    val factId: Long?,
    val category: String,
    val subject: String,
    val contentText: String,
    val contentHash: String,
    val embedding: FloatArray,
    val importance: Int,
    val updatedAtMs: Long
)
