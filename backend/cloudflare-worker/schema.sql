-- ==============================================================================
-- Gepy Native — Skema Database Supabase (PostgreSQL + pgvector)
-- Memori Jangka Panjang AI untuk Pengguna Tunanetra
-- ==============================================================================

-- 1. Aktifkan Ekstensi pgvector untuk Semantic Embedding
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Tabel Pengguna (Tersinkronisasi dengan Google OAuth sub ID)
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,                 -- Google Subject ID (unik per akun Google)
    email TEXT NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_login_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 3. Tabel Memori Semantik Pengguna
CREATE TABLE IF NOT EXISTS user_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category TEXT DEFAULT 'general',    -- 'address', 'preference', 'emergency_contact', 'medical', 'habit'
    content TEXT NOT NULL,              -- Fakta/preferensi: misal "Titik jemput rumah di pagar hitam sebelah warung"
    embedding vector(768),              -- Vector 768 dimensi dari Google Gemini gemini-embedding-2 (MRL)
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indeks B-Tree untuk pencarian cepat berdasarkan user_id
CREATE INDEX IF NOT EXISTS idx_user_memories_user_id ON user_memories(user_id);

-- Indeks HNSW (Hierarchical Navigable Small World) untuk pencarian vektor super cepat (< 5ms)
CREATE INDEX IF NOT EXISTS idx_user_memories_hnsw 
ON user_memories USING hnsw (embedding vector_cosine_ops);

-- 4. Fungsi Prosedur Tersimpan (RPC) untuk Semantic Search
-- Menghitung kemiripan kosinus (Cosine Similarity) antara query perintah dan memori tersimpan
CREATE OR REPLACE FUNCTION match_user_memories (
    query_embedding vector(768),
    match_user_id TEXT,
    match_threshold FLOAT DEFAULT 0.4,
    match_count INT DEFAULT 5
)
RETURNS TABLE (
    id UUID,
    category TEXT,
    content TEXT,
    similarity FLOAT
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        um.id,
        um.category,
        um.content,
        (1 - (um.embedding <=> query_embedding))::FLOAT AS similarity
    FROM user_memories um
    WHERE um.user_id = match_user_id
      AND (1 - (um.embedding <=> query_embedding)) > match_threshold
    ORDER BY um.embedding <=> query_embedding
    LIMIT match_count;
END;
$$;
