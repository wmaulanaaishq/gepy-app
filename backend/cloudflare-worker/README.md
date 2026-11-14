# Gepy Native — Serverless AI Gateway & Memory Hub ($0/Bulan Cloudflare Workers)

Gateway API serverless berkecepatan tinggi yang menghubungkan aplikasi Android **Gepy Native v3.8+** ke **Google Gemini 3.1 Flash Lite** (fallback `gemini-flash-lite-latest` -> `gemini-2.5-flash`) via header `x-goog-api-key` dan basis data memori jangka panjang Supabase PostgreSQL (`pgvector`).

---

## 1. Fitur Utama & Kepatuhan Toko Aplikasi

- **Zero-Leakage API Keys**: Kunci Google Gemini dan Supabase Service Role tersimpan secara aman di enkripsi edge Cloudflare (bukan di dalam file APK Android).
- **Google OAuth Verification**: Memvalidasi token Google Credential Manager Android secara langsung ke Google OAuth2 tokeninfo.
- **Persistent Semantic Memory**: Menggunakan Gemini `gemini-embedding-2` (768 dimensi MRL) dan indeks HNSW di Supabase `pgvector` untuk menyimpan dan mencari preferensi dan konteks personal secara instan (< 10 ms).
- **Store Compliance Data Deletion**: Menyediakan endpoint `DELETE /api/memory` untuk mematuhi aturan Google Play & Samsung Galaxy Store mengenai hak penghapusan data pengguna.
- **Biaya $0/Bulan**: Berjalan sepenuhnya di Free Tier Cloudflare Workers (100.000 permintaan gratis/hari) dan Free Tier Supabase (500 MB database).

---

## 2. Persiapan Database Supabase

1. Buka [database.new](https://database.new) untuk membuat proyek Supabase baru gratis (misal: `gepy-ai-memory`).
2. Masuk ke menu **SQL Editor** di dashboard Supabase Anda.
3. Buka file [`schema.sql`](./schema.sql), salin seluruh isinya, tempel ke SQL Editor Supabase, lalu klik **Run**.
4. Ambil 2 kredensial dari menu **Project Settings** > **API**:
   - **Project URL** (misal: `https://xyzcompany.supabase.co`)
   - **service_role secret key** (kunci rahasia untuk akses database dari server)

---

## 3. Deployment Cloudflare Worker (3 Langkah Mudah)

Masuk ke folder worker:
```powershell
cd backend/cloudflare-worker
```

### Langkah A: Daftarkan Secret Kunci Lingkungan
Jalankan perintah berikut satu per satu:

```powershell
# 1. Kunci Google AI Studio (Gemini)
npx wrangler secret put GEMINI_API_KEY

# 2. Secret acak untuk enkripsi JWT Sesi
npx wrangler secret put JWT_SECRET

# 3. URL Proyek Supabase
npx wrangler secret put SUPABASE_URL

# 4. Kunci Service Role Supabase
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
```

### Langkah B: Deploy ke Cloudflare Edge
```powershell
npx wrangler deploy
```

Worker akan terpasang di jaringan global Cloudflare dengan URL seperti:
`https://gepy-api-gateway.<username>.workers.dev`

---

## 4. Endpoint API Referensi

| Metode | Jalur | Deskripsi | Autentikasi |
| :--- | :--- | :--- | :--- |
| `GET` | `/health` | Status gateway & koneksi backend | Publik |
| `POST` | `/api/auth/google` | Verifikasi Google ID Token & terbitkan sesi JWT | Publik (kirim `id_token`) |
| `POST` | `/api/ai/chat` | Proxy Gemini (`gemini-3.1-flash-lite` / fallback `gemini-2.5-flash`) + injeksi memori otomatis | Bearer JWT / API Key |
| `POST` | `/api/memory/store` | Simpan fakta/preferensi personal ke `pgvector` | Bearer JWT |
| `POST` | `/api/memory/search` | Cari memori relevan berdasarkan kemiripan arti | Bearer JWT |
| `DELETE` | `/api/memory` | Hapus seluruh data memori pengguna | Bearer JWT |
| `POST` | `/api/web/search` / `/api/web/fast-search` | Pencarian web real-time paralel + sintesis | Bearer JWT / API Key |
| `POST` | `/api/web/read` | Ekstraksi teks bersih laman web via Jina Reader | Bearer JWT / API Key |
