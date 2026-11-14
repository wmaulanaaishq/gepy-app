# Changelog

All notable releases and verified engineering milestones of **Gepy — Mobile-Use Personal AI Agent** (`com.teknologigawai.gepy`) are documented in this file.

## [6.6.0] - 2026-11-15

### Public Source-Available Release & Configuration Hardening
- **Zero-Hardcoded-Secret Clean-Room Build:** Externalized optional Sentry DSN (`SENTRY_DSN` manifest placeholder defaulting to `""`), Cloudflare Edge Gateway URL (`GATEWAY_BASE_URL`), Google OAuth Web Client ID (`GOOGLE_WEB_CLIENT_ID`), and Windows Laptop Companion Tailscale host (`LAPTOP_TAILSCALE_HOST`) into `local_secrets.json` / environment overrides, allowing external contributors to compile and run unit tests without personal infrastructure keys.
- **Defensive Unconfigured Guards:** Added graceful unconfigured checks in `CloudflareGatewayClient` (`isConfigured`), `GoogleAuthManager`, `LaptopCompanionBridge`, and `backend/cloudflare-worker/worker.js` (`env.GOOGLE_WEB_CLIENT_ID` required with `HTTP 503` guard when unset).
- **Restricted License & Sanitized Templates:** Added PolyForm Noncommercial License 1.0.0 (`LICENSE` + `NOTICE`), `backend/cloudflare-worker/wrangler.example.toml`, and updated `local_secrets.example.json`; removed unused legacy Lottie assets (`~833 KB`).

### Core Agent, UI, Voice & Safety Capabilities
- **AI/ML API 1-Tap BYOK Preset & Latency Optimization:** Added `ByokProviderPreset.AIML_API` (`google/gemini-3.1-flash-lite`), fast HTTP receive timeout (`14s`), provider-level circuit breakers (`HTTP 401/402/403/404` & `429`), and `thinkingBudget = 0` JSON turn optimization (reducing Samsung Galaxy A06 end-to-end Gojek booking flow to `45.32s` up to the HITL safety gate).
- **Dark Smoked Glass Overlay HUD & HITL Safety Hardening:** Unified floating overlay HUD (`ActiveGlassProcessBubble`, `HitlConfirmationCard`) with floating white capsule controls (`Cancel` / `Execute`), strengthened transactional HITL keywords (`Book`, `Order Now`, `Pay`, `Send`, `Delete`), and added `GepyStateMirrorServer` (`BuildConfig.DEBUG` loopback state mirror).
- **Modularized Native Kotlin Hexagonal Architecture:** Pure single-process Android architecture (Jetpack Compose Material 3, Room SQLite `v6`, Symbolic Edge World Model `SEWM`, Tri-Gram BM25+ + `gemini-embedding-2` Hybrid Vector Memory, `Gemini 3.8 Live` bidirectional audio, ElevenLabs TTS + SHA-256 disk cache, native `SpeechRecognizer`, Windows Laptop Companion A2A JSON-RPC, and ESP32-S3 Wi-Fi CSI ambient bridge).

### Verified Quality Gates (Clean-Room & Local)
- **JVM Unit Tests:** `468 / 468` passing across `68` suites (`0` failures, `0` skipped) via `./gradlew testDebugUnitTest`.
- **Android Lint:** `0` errors via `./gradlew lintDebug`.
- **Deterministic AI Evals (`gepy-eval`):** `36 / 36` checks passing via `python scripts/evals/runner.py --mode quick`.
- **APK Assembly:** Clean `./gradlew assembleDebug` build verified with no `local_secrets.json`.

---

For hardware constraints and wireless ADB setup, see [`docs/DEVICE_PROFILE.md`](docs/DEVICE_PROFILE.md).  
For supported model IDs, see [`docs/MODELS.md`](docs/MODELS.md).  
For architectural details, see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
