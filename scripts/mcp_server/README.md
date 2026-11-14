# Gepy Satellite Worker — MCP Server (HTTP / SSE)

Worker satelit berbasis **Model Context Protocol (MCP)** dengan transport **HTTP / SSE (Server-Sent Events)** yang dirancang untuk dieksekusi oleh **Gepy Native (Android Client)** melalui jaringan privat **Tailscale / LAN**.

---

## 🛠️ Tools yang Disediakan
1. `search_and_extract_web(query, max_results)`: Riset web mendalam dan ekstraksi teks artikel bersih (Trafilatura + BeautifulSoup4).
2. `analyze_document(file_content, task_type)`: Ekstraksi dan analisa dokumen panjang (PDF base64 dengan `pypdf`, CSV profil statistik, TF-scored key sentences).
3. `execute_python_calc(script_code)`: Komputasi data dan kalkulasi Python di dalam sandbox terisolasi dengan verifikasi AST.
4. `ping_worker()`: Health check, uptime, dan daftar network IP aktif.

---

## 🚀 Cara Menjalankan

### Opsi A: Di Google Cloud Shell / Linux VM (Rekomendasi 24/7)
1. Buka [Google Cloud Shell](https://shell.cloud.google.com) atau terminal Linux VM Anda.
2. Salin folder `mcp_server/` atau clone repositori.
3. Jalankan:
   ```bash
   cd scripts/mcp_server
   python3 -m venv .venv
   source .venv/bin/activate
   pip install -r requirements.txt
   python3 server.py
   ```

### Opsi B: Di Laptop Windows (Lokal / Testing Cepat)
```powershell
cd scripts\mcp_server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python server.py
```

---

## 🌐 Endpoints
- **Health Check**: `GET http://<HOST>:8000/health`
- **MCP SSE Connection**: `GET http://<HOST>:8000/sse`
- **MCP Messages (JSON-RPC)**: `POST http://<HOST>:8000/messages/`
