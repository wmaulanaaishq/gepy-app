/**
 * Gepy Native — Serverless AI Gateway & Long-Term Memory Hub
 * Platform: Cloudflare Workers ($0/month Free Tier)
 *
 * Fitur:
 * 1. Google OAuth Token Verification (Android Credential Manager Web Client ID).
 * 2. Native Web Crypto JWT Session Tokens (HMAC-SHA256).
 * 3. Long-Term Semantic Memory (Supabase PostgreSQL + pgvector + Gemini gemini-embedding-2 768-dim).
 * 4. Context-Aware AI Chat & ReAct Agent Proxy (Google Gemini 2.5 Flash).
 * 5. Rate Limiting Otomatis & Anti-Abuse (60 req/menit per IP).
 */

const RATE_LIMIT_WINDOW_SECONDS = 60;
const MAX_REQUESTS_PER_WINDOW = 60;
const ipRequestCounts = new Map();

// --- Rate Limiter In-Memory ---
function isRateLimited(clientIp) {
    const now = Math.floor(Date.now() / 1000);
    const windowStart = now - (now % RATE_LIMIT_WINDOW_SECONDS);

    const record = ipRequestCounts.get(clientIp);
    if (!record || record.window !== windowStart) {
        ipRequestCounts.set(clientIp, { window: windowStart, count: 1 });
        return false;
    }

    if (record.count >= MAX_REQUESTS_PER_WINDOW) {
        return true;
    }

    record.count += 1;
    return false;
}

function cleanupOldIpRecords() {
    const now = Math.floor(Date.now() / 1000);
    for (const [ip, record] of ipRequestCounts.entries()) {
        if (now - record.window > 300) {
            ipRequestCounts.delete(ip);
        }
    }
}

// --- Web Crypto Helpers (Base64URL & JWT HMAC-SHA256) ---
function base64UrlEncode(buffer) {
    let str = "";
    const bytes = new Uint8Array(buffer);
    for (let i = 0; i < bytes.byteLength; i++) {
        str += String.fromCharCode(bytes[i]);
    }
    return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlDecode(str) {
    str = str.replace(/-/g, "+").replace(/_/g, "/");
    while (str.length % 4) {
        str += "=";
    }
    const binary = atob(str);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

async function getCryptoKey(secret) {
    if (!secret || typeof secret !== "string" || !secret.trim()) {
        throw new Error("JWT_SECRET tidak dikonfigurasi di environment Worker.");
    }
    const encoder = new TextEncoder();
    return await crypto.subtle.importKey(
        "raw",
        encoder.encode(secret.trim()),
        { name: "HMAC", hash: "SHA-256" },
        false,
        ["sign", "verify"]
    );
}

function isBlockedInternalUrl(rawUrl) {
    try {
        const parsed = new URL(rawUrl);
        if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return true;
        const host = (parsed.hostname || "").toLowerCase();
        if (
            host === "localhost" ||
            host === "127.0.0.1" ||
            host === "0.0.0.0" ||
            host === "::1" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            /^10\./.test(host) ||
            /^192\.168\./.test(host) ||
            /^169\.254\./.test(host) ||
            /^172\.(1[6-9]|2\d|3[0-1])\./.test(host)
        ) {
            return true;
        }
        return false;
    } catch (_) {
        return true;
    }
}

async function signJwt(payload, secret) {
    const header = { alg: "HS256", typ: "JWT" };
    const encoder = new TextEncoder();
    const encodedHeader = base64UrlEncode(encoder.encode(JSON.stringify(header)));
    const encodedPayload = base64UrlEncode(encoder.encode(JSON.stringify(payload)));
    const dataToSign = `${encodedHeader}.${encodedPayload}`;

    const key = await getCryptoKey(secret);
    const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(dataToSign));
    return `${dataToSign}.${base64UrlEncode(signature)}`;
}

async function verifyJwt(token, secret) {
    if (!token || typeof token !== "string") return null;
    const parts = token.split(".");
    if (parts.length !== 3) return null;

    const [encodedHeader, encodedPayload, encodedSignature] = parts;
    const dataToSign = `${encodedHeader}.${encodedPayload}`;
    const encoder = new TextEncoder();

    try {
        const key = await getCryptoKey(secret);
        const signatureBytes = base64UrlDecode(encodedSignature);
        const isValid = await crypto.subtle.verify("HMAC", key, signatureBytes, encoder.encode(dataToSign));
        if (!isValid) return null;

        const payloadJson = new TextDecoder().decode(base64UrlDecode(encodedPayload));
        const payload = JSON.parse(payloadJson);
        const now = Math.floor(Date.now() / 1000);
        if (payload.exp && payload.exp < now) {
            return null; // Token kedaluwarsa
        }
        return payload;
    } catch {
        return null;
    }
}

// --- Gemini Embeddings (gemini-embedding-2: 768 dimensi MRL) ---
async function generateGeminiEmbedding(text, apiKey, modelId = "gemini-embedding-2") {
    const cleanModel = (modelId || "gemini-embedding-2").replace(/^models\//, "");
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${cleanModel}:embedContent`;
    const res = await fetch(url, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "x-goog-api-key": apiKey
        },
        body: JSON.stringify({
            model: `models/${cleanModel}`,
            content: { parts: [{ text: text }] },
            embedContentConfig: { outputDimensionality: 768 }
        })
    });
    if (!res.ok) {
        const err = await res.text();
        throw new Error(`Gagal menghasilkan embedding: ${err}`);
    }
    const data = await res.json();
    return data.embedding?.values || [];
}

// --- Supabase REST Helpers ---
async function upsertSupabaseUser(user, env) {
    if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) return null;
    const url = `${env.SUPABASE_URL}/rest/v1/users`;
    return await fetch(url, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "apikey": env.SUPABASE_SERVICE_ROLE_KEY,
            "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
            "Prefer": "resolution=merge-duplicates"
        },
        body: JSON.stringify({
            id: user.id,
            email: user.email,
            display_name: user.name,
            avatar_url: user.picture,
            last_login_at: new Date().toISOString()
        })
    });
}

async function storeSupabaseMemory(userId, category, content, embedding, env) {
    if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) return null;
    const url = `${env.SUPABASE_URL}/rest/v1/user_memories`;
    const res = await fetch(url, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "apikey": env.SUPABASE_SERVICE_ROLE_KEY,
            "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
            "Prefer": "return=representation"
        },
        body: JSON.stringify({
            user_id: userId,
            category: category || "general",
            content: content,
            embedding: embedding
        })
    });
    return res.ok ? await res.json() : null;
}

async function searchSupabaseMemories(userId, queryEmbedding, limit, env) {
    if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) return [];
    const url = `${env.SUPABASE_URL}/rest/v1/rpc/match_user_memories`;
    const res = await fetch(url, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "apikey": env.SUPABASE_SERVICE_ROLE_KEY,
            "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
        },
        body: JSON.stringify({
            query_embedding: queryEmbedding,
            match_user_id: userId,
            match_threshold: 0.35,
            match_count: limit || 5
        })
    });
    return res.ok ? await res.json() : [];
}

async function clearSupabaseUserMemories(userId, env) {
    if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) return false;
    const url = `${env.SUPABASE_URL}/rest/v1/user_memories?user_id=eq.${encodeURIComponent(userId)}`;
    const res = await fetch(url, {
        method: "DELETE",
        headers: {
            "apikey": env.SUPABASE_SERVICE_ROLE_KEY,
            "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
        }
    });
    return res.ok;
}

// --- Handler Utama Worker ---
export default {
    async fetch(request, env, ctx) {
        cleanupOldIpRecords();

        const url = new URL(request.url);
        const origin = request.headers.get("Origin") || "*";

        const corsHeaders = {
            "Access-Control-Allow-Origin": origin,
            "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Authorization, x-goog-api-key, x-gepy-client",
            "Access-Control-Max-Age": "86400",
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY"
        };

        if (request.method === "OPTIONS") {
            return new Response(null, { status: 204, headers: corsHeaders });
        }

        // 1. Health Check
        if (url.pathname === "/health" || url.pathname === "/") {
            return new Response(
                JSON.stringify({
                    status: "ok",
                    service: "gepy-api-gateway",
                    version: "3.8.0",
                    provider: "Cloudflare Workers",
                    supabase_connected: Boolean(env.SUPABASE_URL && env.SUPABASE_SERVICE_ROLE_KEY),
                    gemini_configured: Boolean(env.GEMINI_API_KEY),
                    timestamp: new Date().toISOString()
                }),
                { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
            );
        }

        // 2. Rate Limiting
        const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";
        if (isRateLimited(clientIp)) {
            return new Response(
                JSON.stringify({ error: { code: 429, message: "Batas permintaan terlampaui. Tunggu 1 menit." } }),
                { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
            );
        }

        // 3. Autentikasi Google: POST /api/auth/google
        if (request.method === "POST" && url.pathname === "/api/auth/google") {
            try {
                const body = await request.json();
                const idToken = body.id_token;
                if (!idToken) {
                    return new Response(
                        JSON.stringify({ error: { code: 400, message: "Field 'id_token' wajib dikirim." } }),
                        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                // Verifikasi ke Google OAuth2 TokenInfo
                const verifyUrl = `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`;
                const googleRes = await fetch(verifyUrl);
                if (!googleRes.ok) {
                    const errText = await googleRes.text();
                    return new Response(
                        JSON.stringify({ error: { code: 401, message: "Google ID Token tidak valid.", detail: errText } }),
                        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                const tokenInfo = await googleRes.json();
                const expectedAudiences = (env.GOOGLE_WEB_CLIENT_ID || "")
                    .split(",")
                    .map((s) => s.trim())
                    .filter(Boolean);

                if (expectedAudiences.length === 0) {
                    return new Response(
                        JSON.stringify({ error: { code: 503, message: "GOOGLE_WEB_CLIENT_ID belum dikonfigurasi pada Cloudflare Worker." } }),
                        { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                // Pastikan token ditujukan untuk client ID kita
                if (!expectedAudiences.includes(tokenInfo.aud)) {
                    return new Response(
                        JSON.stringify({ error: { code: 403, message: "Audience token tidak cocok dengan Gepy OAuth client." } }),
                        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                const user = {
                    id: tokenInfo.sub,
                    email: tokenInfo.email,
                    name: tokenInfo.name || tokenInfo.email.split("@")[0],
                    picture: tokenInfo.picture || ""
                };

                // Sinkronisasi data user ke Supabase PostgreSQL
                await upsertSupabaseUser(user, env);

                // Terbitkan Session Token (JWT berlaku 30 hari)
                const jwtSecret = (env.JWT_SECRET || "").trim();
                if (!jwtSecret) {
                    return new Response(
                        JSON.stringify({ error: { code: 500, message: "Konfigurasi JWT_SECRET di Worker belum disetel." } }),
                        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }
                const sessionPayload = {
                    sub: user.id,
                    email: user.email,
                    name: user.name,
                    picture: user.picture,
                    iat: Math.floor(Date.now() / 1000),
                    exp: Math.floor(Date.now() / 1000) + (30 * 24 * 3600)
                };
                const sessionToken = await signJwt(sessionPayload, jwtSecret);

                return new Response(
                    JSON.stringify({
                        ok: true,
                        session_token: sessionToken,
                        user: user
                    }),
                    { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            } catch (err) {
                return new Response(
                    JSON.stringify({ error: { code: 500, message: `Autentikasi gagal: ${err.message}` } }),
                    { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }
        }

        // Helper Ekstraksi & Verifikasi Sesi JWT (fail closed bila JWT_SECRET belum disetel)
        const authHeader = request.headers.get("Authorization") || "";
        const rawToken = authHeader.replace(/^Bearer\s+/i, "").trim();
        const jwtSecret = (env.JWT_SECRET || "").trim();
        const sessionUser = jwtSecret ? await verifyJwt(rawToken, jwtSecret) : null;

        // 4. Manajemen Memori: POST /api/memory/store
        if (request.method === "POST" && url.pathname === "/api/memory/store") {
            if (!sessionUser) {
                return new Response(
                    JSON.stringify({ error: { code: 401, message: "Sesi tidak valid atau kedaluwarsa. Silakan login." } }),
                    { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            try {
                const body = await request.json();
                const content = (body.content || "").trim();
                const category = (body.category || "general").trim();
                if (!content) {
                    return new Response(
                        JSON.stringify({ error: { code: 400, message: "Field 'content' wajib diisi." } }),
                        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                const apiKey = env.GEMINI_SECONDARY_API_KEY || env.GEMINI_API_KEY;
                if (!apiKey) {
                    return new Response(
                        JSON.stringify({ error: { code: 500, message: "Kunci GEMINI_API_KEY belum dikonfigurasi di Worker." } }),
                        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                // Hitung embedding vektor 768 dimensi MRL
                const embedding = await generateGeminiEmbedding(content, apiKey, env.EMBEDDING_MODEL);

                // Simpan ke Supabase PostgreSQL
                const saved = await storeSupabaseMemory(sessionUser.sub, category, content, embedding, env);
                if (!saved) {
                    return new Response(
                        JSON.stringify({ ok: false, error: { code: 502, message: "Gagal menyimpan memori ke database cloud." } }),
                        { status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                return new Response(
                    JSON.stringify({ ok: true, message: "Memori berhasil disimpan.", memory: saved }),
                    { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            } catch (err) {
                return new Response(
                    JSON.stringify({ error: { code: 500, message: `Gagal menyimpan memori: ${err.message}` } }),
                    { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }
        }

        // 5. Pencarian Memori: POST /api/memory/search
        if (request.method === "POST" && url.pathname === "/api/memory/search") {
            if (!sessionUser) {
                return new Response(
                    JSON.stringify({ error: { code: 401, message: "Sesi tidak valid. Silakan login." } }),
                    { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            try {
                const body = await request.json();
                const query = (body.query || "").trim();
                const limit = body.limit || 5;

                const apiKey = env.GEMINI_SECONDARY_API_KEY || env.GEMINI_API_KEY;
                if (!apiKey || !query) {
                    return new Response(
                        JSON.stringify({ ok: true, memories: [] }),
                        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                const queryEmbedding = await generateGeminiEmbedding(query, apiKey, env.EMBEDDING_MODEL);
                const memories = await searchSupabaseMemories(sessionUser.sub, queryEmbedding, limit, env);

                return new Response(
                    JSON.stringify({ ok: true, memories: memories }),
                    { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            } catch (err) {
                return new Response(
                    JSON.stringify({ error: { code: 500, message: `Gagal mencari memori: ${err.message}` } }),
                    { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }
        }

        // 6. Hapus Memori Pengguna (Store Compliance Data Deletion): DELETE /api/memory
        if (request.method === "DELETE" && url.pathname === "/api/memory") {
            if (!sessionUser) {
                return new Response(
                    JSON.stringify({ error: { code: 401, message: "Sesi tidak valid." } }),
                    { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            const success = await clearSupabaseUserMemories(sessionUser.sub, env);
            return new Response(
                JSON.stringify({ ok: success, message: success ? "Semua memori berhasil dihapus." : "Gagal menghapus memori." }),
                { status: success ? 200 : 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
            );
        }

        // 7. Proxy AI Chat & ReAct Agent dengan Injeksi Memori: POST /api/ai/chat atau /generateContent
        if (request.method === "POST" && (url.pathname === "/api/ai/chat" || url.pathname.includes("/generateContent"))) {
            const clientByokKey = request.headers.get("x-goog-api-key");

            // Jaminan Keamanan Gateway: Jika pengguna tidak menyertakan API Key sendiri (BYOK),
            // pengguna WAJIB memiliki sesi login Google yang valid (sessionUser) agar tidak ada
            // pihak tak dikenal yang mengeksploitasi kuota Gemini Gateway.
            if (!clientByokKey && !sessionUser) {
                return new Response(
                    JSON.stringify({
                        error: {
                            code: 401,
                            message: "Akses ditolak. Silakan login dengan Akun Google atau sertakan API key sendiri (BYOK) melalui header x-goog-api-key."
                        }
                    }),
                    { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            const apiKey = clientByokKey || env.GEMINI_API_KEY;
            if (!apiKey) {
                return new Response(
                    JSON.stringify({ error: { code: 500, message: "GEMINI_API_KEY belum dikonfigurasi di Worker." } }),
                    { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            try {
                const requestJson = await request.json();
                let promptText = "";

                // Ekstraksi teks pertanyaan untuk pencarian memori
                if (requestJson.contents && Array.isArray(requestJson.contents)) {
                    for (const c of requestJson.contents) {
                        if (c.parts && Array.isArray(c.parts)) {
                            for (const p of c.parts) {
                                if (p.text) promptText += p.text + " ";
                            }
                        }
                    }
                }

                // Jika user login, lakukan pencarian memori semantik otomatis (dibatasi maks 400 karakter awal agar cepat & relevan)
                let memoryContext = "";
                const querySnippet = promptText.trim().slice(0, 400);
                if (sessionUser && querySnippet.length > 3) {
                    try {
                        const embKey = env.GEMINI_SECONDARY_API_KEY || apiKey;
                        const queryEmbedding = await generateGeminiEmbedding(querySnippet, embKey, env.EMBEDDING_MODEL);
                        const matched = await searchSupabaseMemories(sessionUser.sub, queryEmbedding, 3, env);
                        if (matched && matched.length > 0) {
                            memoryContext = "\n\n[MEMORI PERSONAL PENGGUNA TERVERIFIKASI]\n" +
                                matched.map(m => `- (${m.category}): ${m.content}`).join("\n");
                        }
                    } catch (e) {
                        // Abaikan error memori agar percakapan tetap berjalan
                    }
                }

                // Injeksi memori ke System Instruction jika ada
                if (memoryContext) {
                    if (!requestJson.systemInstruction) {
                        requestJson.systemInstruction = { parts: [{ text: "Anda adalah Gepy, asisten AI ramah tunanetra." }] };
                    }
                    if (requestJson.systemInstruction.parts && requestJson.systemInstruction.parts[0]) {
                        requestJson.systemInstruction.parts[0].text += memoryContext;
                    }
                }

                const candidateModels = [
                    env.DEFAULT_GEMINI_MODEL || "gemini-3.1-flash-lite",
                    "gemini-3.1-flash-lite",
                    "gemini-flash-lite-latest",
                    "gemini-2.5-flash"
                ];
                let upstreamRes = null;
                for (const modelId of [...new Set(candidateModels)]) {
                    const upstreamUrl = `https://generativelanguage.googleapis.com/v1beta/models/${modelId}:generateContent`;
                    upstreamRes = await fetch(upstreamUrl, {
                        method: "POST",
                        headers: {
                            "Content-Type": "application/json",
                            "x-goog-api-key": apiKey
                        },
                        body: JSON.stringify(requestJson)
                    });
                    if (upstreamRes.ok || (upstreamRes.status !== 404 && upstreamRes.status !== 429)) {
                        break;
                    }
                }

                const resData = await upstreamRes.text();
                return new Response(resData, {
                    status: upstreamRes.status,
                    headers: {
                        ...corsHeaders,
                        "Content-Type": upstreamRes.headers.get("Content-Type") || "application/json"
                    }
                });
            } catch (err) {
                return new Response(
                    JSON.stringify({ error: { code: 502, message: `Gagal meneruskan ke Gemini: ${err.message}` } }),
                    { status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }
        }

        // 8. Pencarian Web Real-Time dengan Multi-Engine Grounding: POST /api/web/search & POST /api/web/fast-search
        if (request.method === "POST" && (url.pathname === "/api/web/search" || url.pathname === "/api/web/fast-search")) {
            const clientByokKey = request.headers.get("x-goog-api-key");
            if (!clientByokKey && !sessionUser) {
                return new Response(
                    JSON.stringify({
                        error: {
                            code: 401,
                            message: "Akses ditolak. Silakan login dengan Akun Google atau sertakan x-goog-api-key."
                        }
                    }),
                    { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            const apiKey = clientByokKey || env.GEMINI_API_KEY;
            if (!apiKey) {
                return new Response(
                    JSON.stringify({ error: { code: 500, message: "GEMINI_API_KEY belum dikonfigurasi di Worker." } }),
                    { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            try {
                const body = await request.json();
                const query = (body.query || "").trim();
                if (!query) {
                    return new Response(
                        JSON.stringify({ error: { code: 400, message: "Field 'query' wajib diisi." } }),
                        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                const targetModel = env.DEFAULT_GEMINI_MODEL || "gemini-3.1-flash-lite";
                const upstreamUrl = `https://generativelanguage.googleapis.com/v1beta/models/${targetModel}:generateContent`;
                const todayIso = new Date().toISOString().slice(0, 10);
                const blockedSourceRegex = /(blogspot\.|wordpress\.com|kompasiana\.com|kaskus\.co\.id|quora\.com|reddit\.com|brainly\.co\.id|medium\.com)/i;

                const groundingPayload = {
                    systemInstruction: {
                        parts: [
                            {
                                text: `Anda adalah Gepy, asisten suara pintar Bahasa Indonesia. Tanggal hari ini: ${todayIso}. Jawab pertanyaan pengguna secara faktual, akurat, dan terkini berdasarkan sumber resmi (.go.id, BMKG, Bank Indonesia, OJK, BPS) atau pers nasional terverifikasi (Antara, Kompas, Tempo, CNN Indonesia, Detik, Bisnis, Kontan). WAJIB sebutkan sumber berita/lembaga resmi serta waktu rilis/terbit berita di internet (misal: dirilis hari ini, kemarin, atau 2 jam lalu) secara singkat di dalam kalimat lisan Anda. DILARANG KERAS menebak angka, kurs, atau fakta yang tidak ditemukan. Gunakan Bahasa Indonesia lisan yang alami, ringkas (maksimal 2-3 kalimat padat), tanpa format markdown/simbol bintang agar nyaman didengar oleh pengguna tunanetra.`
                            }
                        ]
                    },
                    generationConfig: {
                        temperature: 0.0,
                        maxOutputTokens: 160
                    },
                    contents: [
                        {
                            role: "user",
                            parts: [{ text: query }]
                        }
                    ],
                    tools: [
                        { google_search: {} }
                    ]
                };

                let upstreamRes = await fetch(upstreamUrl, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        "x-goog-api-key": apiKey
                    },
                    body: JSON.stringify(groundingPayload)
                });

                let answerText = "";
                const sources = [];

                if (upstreamRes.ok) {
                    const data = await upstreamRes.json();
                    const candidate = data.candidates?.[0] || {};
                    const parts = candidate.content?.parts || [];
                    answerText = parts.map(p => p.text || "").join(" ").trim();

                    const groundingChunks = candidate.groundingMetadata?.groundingChunks || [];
                    for (const chunk of groundingChunks) {
                        const chunkTitle = chunk.web?.title || "";
                        const chunkUri = chunk.web?.uri || "";
                        if ((chunkTitle || chunkUri) && !blockedSourceRegex.test(chunkTitle) && !blockedSourceRegex.test(chunkUri)) {
                            sources.push({
                                title: chunkTitle || "Sumber Web Terverifikasi",
                                uri: chunkUri
                            });
                        }
                    }
                } else {
                    // Fallback Free-Tier Berkecepatan Tinggi: Eksekusi Paralel Edge (Google News + DuckDuckGo + Wikipedia)
                    const encodedQuery = encodeURIComponent(query);
                    const gnewsUrl = `https://news.google.com/rss/search?q=${encodedQuery}&hl=id&gl=ID&ceid=ID:id`;
                    const ddgUrl = `https://api.duckduckgo.com/?q=${encodedQuery}&format=json&no_html=1&skip_disambig=1`;
                    const wikiUrl = `https://id.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodedQuery}&utf8=&format=json`;

                    const collectedSnippets = [];

                    try {
                        const [gnewsSettled, ddgSettled, wikiSettled] = await Promise.allSettled([
                            fetch(gnewsUrl),
                            fetch(ddgUrl),
                            fetch(wikiUrl)
                        ]);

                        if (gnewsSettled.status === "fulfilled" && gnewsSettled.value.ok) {
                            const xmlText = await gnewsSettled.value.text();
                            const items = [...xmlText.matchAll(/<item>([\s\S]*?)<\/item>/gi)];
                            for (const itemMatch of items.slice(0, 4)) {
                                const itemXml = itemMatch[1];
                                const titleMatch = itemXml.match(/<title>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?<\/title>/i);
                                const sourceMatch = itemXml.match(/<source[^>]*>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?<\/source>/i);
                                const rawTitle = titleMatch ? titleMatch[1].replace(/<[^>]+>/g, "").trim() : "";
                                const rawSource = sourceMatch ? sourceMatch[1].replace(/<[^>]+>/g, "").trim() : "Pers Nasional";
                                if (rawTitle && !blockedSourceRegex.test(rawTitle) && !blockedSourceRegex.test(rawSource)) {
                                    collectedSnippets.push(`[${rawSource}] ${rawTitle}`);
                                    if (!sources.some(s => s.title === rawSource)) {
                                        sources.push({ title: rawSource, uri: gnewsUrl });
                                    }
                                }
                            }
                        }

                        if (ddgSettled.status === "fulfilled" && ddgSettled.value.ok) {
                            try {
                                const ddgJson = await ddgSettled.value.json();
                                const abstract = (ddgJson.AbstractText || "").trim();
                                const answer = (ddgJson.Answer || "").trim();
                                const heading = (ddgJson.Heading || "").trim();
                                if (answer) {
                                    collectedSnippets.push(`[Jawaban Langsung] ${answer}`);
                                } else if (abstract) {
                                    collectedSnippets.push(`[${ddgJson.AbstractSource || "Referensi Web"}] ${heading}: ${abstract}`);
                                }
                            } catch (_) {}
                        }

                        if (wikiSettled.status === "fulfilled" && wikiSettled.value.ok) {
                            try {
                                const wikiJson = await wikiSettled.value.json();
                                const searchArr = wikiJson.query?.search || [];
                                for (const item of searchArr.slice(0, 2)) {
                                    const wTitle = (item.title || "").trim();
                                    const wSnippet = (item.snippet || "").replace(/<[^>]+>/g, "").trim();
                                    if (wTitle && wSnippet) {
                                        collectedSnippets.push(`[Wikipedia Indonesia] ${wTitle}: ${wSnippet}`);
                                        if (!sources.some(s => s.title === "Wikipedia Indonesia")) {
                                            sources.push({ title: "Wikipedia Indonesia", uri: `https://id.wikipedia.org/wiki/${encodeURIComponent(wTitle)}` });
                                        }
                                    }
                                }
                            } catch (_) {}
                        }
                    } catch (_) {}

                    const rssContext = collectedSnippets.slice(0, 5).map((s, idx) => `${idx + 1}. ${s}`).join("\n");

                    const synthRes = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent`, {
                        method: "POST",
                        headers: {
                            "Content-Type": "application/json",
                            "x-goog-api-key": apiKey
                        },
                        body: JSON.stringify({
                            generationConfig: {
                                temperature: 0.0,
                                maxOutputTokens: 160
                            },
                            contents: [{
                                role: "user",
                                parts: [{
                                    text: `Tanggal hari ini: ${todayIso}\nPertanyaan pengguna: ${query}\n\n[FAKTA TERVERIFIKASI DARI SUMBER KREDIBEL]:\n${rssContext || "(Gunakan hanya fakta dasar yang pasti benar; jangan mengarang angka)"}\n\nJawab pertanyaan pengguna dalam 2-3 kalimat Bahasa Indonesia lisan yang alami dan ringkas tanpa simbol markdown, serta sebutkan nama sumber beritanya secara singkat.`
                                }]
                            }]
                        })
                    });
                    if (synthRes.ok) {
                        const sData = await synthRes.json();
                        answerText = (sData.candidates?.[0]?.content?.parts || []).map(p => p.text || "").join(" ").trim();
                    } else if (rssContext) {
                        answerText = rssContext.split("\n").slice(0, 2).join(". ");
                    } else {
                        const errText = await synthRes.text();
                        return new Response(
                            JSON.stringify({ error: { code: synthRes.status, message: `Pencarian web gagal: ${errText}` } }),
                            { status: synthRes.status, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                        );
                    }
                }

                return new Response(
                    JSON.stringify({
                        ok: true,
                        query: query,
                        answer: answerText || "Maaf, informasi tersebut tidak ditemukan di hasil pencarian terkini.",
                        sources: sources.slice(0, 5)
                    }),
                    { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            } catch (err) {
                return new Response(
                    JSON.stringify({ error: { code: 500, message: `Kesalahan pencarian web: ${err.message}` } }),
                    { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }
        }

        // 9. Pembaca Halaman Web Bersih (Jina Reader Markdown Extractor): POST /api/web/read
        if (request.method === "POST" && url.pathname === "/api/web/read") {
            const clientByokKey = (request.headers.get("x-goog-api-key") || "").trim();
            const hasValidByokFormat = clientByokKey.length >= 20;
            if (!hasValidByokFormat && !sessionUser) {
                return new Response(
                    JSON.stringify({
                        error: {
                            code: 401,
                            message: "Akses ditolak. Silakan login dengan Akun Google atau sertakan x-goog-api-key yang valid."
                        }
                    }),
                    { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }

            try {
                const body = await request.json();
                const targetUrl = (body.url || "").trim();
                if (!targetUrl || isBlockedInternalUrl(targetUrl)) {
                    return new Response(
                        JSON.stringify({ error: { code: 400, message: "Field 'url' harus berupa URL http/https publik yang valid." } }),
                        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                const jinaHeaders = {
                    "Accept": "application/json",
                    "X-Return-Format": "markdown",
                    "X-Retain-Images": "none",
                    "X-With-Links-Summary": "false"
                };
                if (env.JINA_API_KEY) {
                    jinaHeaders["Authorization"] = `Bearer ${env.JINA_API_KEY}`;
                }

                const jinaRes = await fetch(`https://r.jina.ai/${targetUrl}`, {
                    method: "GET",
                    headers: jinaHeaders
                });

                if (!jinaRes.ok) {
                    return new Response(
                        JSON.stringify({ error: { code: jinaRes.status, message: `Gagal membaca halaman web (HTTP ${jinaRes.status}).` } }),
                        { status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                    );
                }

                const jinaJson = await jinaRes.json();
                const title = jinaJson.data?.title || targetUrl;
                const rawContent = jinaJson.data?.content || "";
                const truncatedContent = rawContent.length > 10000
                    ? rawContent.slice(0, 10000) + "\n...[Konten dipotong]"
                    : rawContent;

                return new Response(
                    JSON.stringify({
                        ok: true,
                        url: targetUrl,
                        title: title,
                        content: truncatedContent
                    }),
                    { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            } catch (err) {
                return new Response(
                    JSON.stringify({ error: { code: 500, message: `Gagal mengekstrak halaman web: ${err.message}` } }),
                    { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
                );
            }
        }

        return new Response(
            JSON.stringify({ error: { code: 404, message: "Endpoint tidak ditemukan. Gunakan /health, /api/auth/google, /api/memory/store, /api/ai/chat, /api/web/search, atau /api/web/read." } }),
            { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
    }
};
