# Daftar ID Model Terverifikasi (Anti-Halusinasi)

Dokumen ini mencatat seluruh model AI dan API yang **resmi diverifikasi keberadaannya** di dokumentasi upstream Google Gemini API dan Fireworks AI. 

**Aturan Mutlak**: Dilarang menggunakan nama model di kode sebelum dicatat dan diverifikasi di dokumen ini.

---

## 1. Google Gemini API (Upstream Official)

Diverifikasi via `gemini-api_gemini-api-docs` MCP Server (2026-11-10):

| Model ID | Tipe Endpoint | Deskripsi & Penggunaan di Gepy |
|---|---|---|
| `gemini-3.8-live` | `BidiGenerateContent` WebSocket | Panggilan suara full-duplex dua arah dengan dukungan native audio PCM 24kHz. Default di Gepy. |
| `gemini-3.8-live-extended-thinking` | `BidiGenerateContent` WebSocket | Model Live dengan penalaran mendalam (*extended thinking* via `thinkingLevel`). |
| `gemini-2.5-flash` | REST `v1beta/models/...:generateContent` | Model teks/multimodal cepat berbiaya sangat rendah. Digunakan sebagai failover penalaran ReAct (termasuk fallback multimodal Set-of-Marks), Google Search Grounding, Konsolidasi Memori, & Sintesis Pasca-Panggilan. |
| `gemini-3.1-flash-lite` | REST `v1beta/models/...:generateContent` | Model ringan ultra-cepat & hemat kuota (*verified Stable* di dokumentasi Gemini API). Digunakan sebagai prioritas awal jalur teks ReAct (`SingleAgentReActAdapter`), ringkasan web (`WebSearchAndReaderBridge`), dan Cloudflare Worker `/api/ai/chat` + `/api/web/search`. |
| `gemini-flash-lite-latest` | REST `v1beta/models/...:generateContent` | Alias resmi seri Flash Lite terbaru (`@google/genai` SDK) sebagai lapisan fallback berlatensi rendah sebelum beralih ke `gemini-2.5-flash`. |
| `gemini-3.8-flash` | REST `v1beta/models/...:generateContent` | Model generasi terbaru untuk transkripsi audio dan inferensi multimodal/VLM cepat. |
| `gemini-embedding-2` | REST `v1beta/models/...:embedContent` | Model embedding semantik multimodal & teks multibahasa (100+ bahasa). Dikonfigurasi via `embedContentConfig.outputDimensionality = 768` (MRL, L2-normalized Float32) untuk **Layer 2 On-Device Semantic Vector Memory (`memory_vectors`)** dan Supabase `pgvector`. |

> **Kebijakan Failover `PREFERRED_LIVE_MODELS` (`GeminiLiveClient`)**: Daftar prioritas statis hanya memuat model GA resmi yang bebas jadwal shutdown (`models/gemini-3.8-live` → `models/gemini-3.8-live-extended-thinking`). Model warisan yang telah ditutup (`gemini-live-2.5-flash-preview`, `gemini-2.0-flash-live-001`, `gemini-2.0-flash-exp`) serta model preview lama dihapus dari daftar statis agar handshake WebSocket tidak tertunda penolakan server, sementara `buildCandidateModelOrder()` tetap mendukung model aktif hasil penemuan dinamis `/v1beta/models`.

### Arsitektur Pemisahan Peran Kunci Ganda (*Dual-Key Role Separation & Auto-Failover*)

Untuk mengoptimalkan kuota RPM/TPM agar pembaruan indeks vektor tidak pernah menghabiskan kuota panggilan suara real-time maupun chat, **`GeminiKeyPool`** memisahkan dua Gemini API Key secara deterministik beserta *failover* otomatis (`HTTP 429` / `RESOURCE_EXHAUSTED` / `401` / `403`, dengan masa *cooldown* 60 detik):

| Peran / Tujuan (`GeminiKeyPurpose`) | Kunci Utama | Failover Otomatis | Modul Pengguna |
|---|---|---|---|
| `CHAT_REASONING`, `LIVE_VOICE`, `WEB_GROUNDING`, `MEMORY_SYNTHESIS` | **Key 1** (`GEMINI_API_KEY`) | **Key 2** (`GEMINI_API_KEY_2`) | `SingleAgentReActAdapter`, `GeminiLiveClient`, `WebSearchAndReaderBridge`, `PostCallMemorySynthesizer`, `CognitiveConsolidationEngine` |
| `EMBEDDING` | **Key 2** (`GEMINI_API_KEY_2`) | **Key 1** (`GEMINI_API_KEY`) | `GeminiEmbeddingClient`, `UserMemoryManager` (Layer 2 Vector Index & Hybrid Search) |

---

## 2. Speech-to-Text (STT) Engine

Gepy v3.5.0 mengadopsi arsitektur STT murni berbasis perangkat (*on-device / edge*):

| Mesin STT | Bahasa | Deskripsi & Penggunaan di Gepy |
|---|---|---|
| **Android Native `SpeechRecognizer`** | `id-ID` (Bahasa Indonesia) | Mesin Google Speech Services bawaan Android OS. Berjalan cepat di perangkat tanpa kuota eksternal, tanpa latensi jaringan, zero API key, dan tahan gangguan sinyal seluler. |
| *(Deprecated v3.5.0) Deepgram Nova-3* | `id` | Dihapus sepenuhnya pada v3.5.0 demi kesederhanaan arsitektur dan keandalan di perangkat entry-level (Galaxy A06). |

---

## 3. Fireworks AI Serverless (OpenAI-Compatible)

| Model ID | Tipe | Deskripsi & Penggunaan di Gepy |
|---|---|---|
| `accounts/fireworks/models/deepseek-v4p1-flash` | Chat Completions | Model penalaran ReAct ultra-cepat default Gepy via Serverless JSON Mode. |
| `accounts/fireworks/models/glm-5p3-flash` | Chat Completions | Model alternatif berlatensi rendah untuk pemrosesan teks cepat. |
| `accounts/fireworks/models/gpt-oss-120b` | Chat Completions | Model berkapasitas besar untuk perencanaan kompleks. |

---

## 4. ElevenLabs Text-to-Speech API (`v1`)

Digunakan untuk sintesis suara personal hasil *voice cloning* (`gepy`) pada respon harian (`VoiceAudioEngine.speakSafe`) dengan dukungan Auto-Cache MP3 lokal per-kalimat/klausa:

| Model ID | Latensi & Biaya Credit | Deskripsi & Penggunaan di Gepy |
|---|---|---|
| `eleven_v4_turbo` | ~100 ms TTFA • 0.5 credit/karakter | **Default di Gepy**. Mendukung Bahasa Indonesia (`language_code = "id"`) secara natural serta *inline audio tags* (`[laughs]`, `[softly]`, `[sighs]`). |
| `eleven_flash_v2_5` | ~75 ms TTFA • 0.5 credit/karakter | Model ultra-rendah latensi dengan dukungan Bahasa Indonesia (`id`). |
| `eleven_v4` | ~150 ms TTFA • 1.0 credit/karakter | Model flagship dengan ekspresi emosional paling kaya. |

---

## 5. AI/ML API & Penyedia BYOK OpenAI-Compatible (`ByokProviderPreset`)

Dukungan **1-ketuk BYOK Preset** di `ByokPreferencesManager` (`PreferredLlmProvider.CUSTOM_OPENAI`, endpoint `https://api.aimlapi.com/v1/chat/completions`):

| Preset / Model ID | Latensi Terukur (5-Langkah ReAct JSON) | Catatan Verifikasi |
|---|---|---|
| `google/gemini-3.1-flash-lite` | ~1,16 dtk rata-rata (5/5 akurat) | **Default Preset `AIML_API`**. Tercepat & konsisten mematuhi aturan `CLICK`, `SET_TEXT_AND_SUBMIT`, dan `CONFIRM_AND_CLICK`. |
| `google/gemini-3.5-flash-lite` | ~1,30 dtk rata-rata (5/5 akurat) | Alternatif berlatensi rendah untuk JSON ReAct terstruktur. |
| `deepseek/deepseek-v4.1-flash` | ~2,40 dtk rata-rata (5/5 akurat) | Alternatif penalaran multi-langkah melalui OpenAI-compatible gateway. |
