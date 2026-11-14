#!/usr/bin/env python3
"""
MCP (Model Context Protocol) Server via HTTP / SSE (Server-Sent Events)
Dirancang untuk diakses oleh klien Android Native (Gepy) melalui jaringan privat (Tailscale / LAN).

Binding default: 0.0.0.0:8000
Endpoints:
  - GET  /sse        : Endpoint koneksi SSE untuk MCP Client
  - POST /messages/  : Endpoint pengiriman pesan JSON-RPC MCP Client
  - GET  /health     : Endpoint HTTP cek kesehatan server (REST)
"""

import ast
import base64
import collections
import csv
import datetime
import io
import json
import logging
import math
import os
import platform
import re
import socket
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List

import httpx
import uvicorn
from bs4 import BeautifulSoup
from mcp.server.fastmcp import FastMCP
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route

# Konfigurasi logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("mcp-android-server")

SERVER_START_TIME = time.time()
HOST = os.getenv("MCP_HOST", "0.0.0.0")
PORT = int(os.getenv("MCP_PORT", "8000"))

# Inisialisasi FastMCP Server
mcp = FastMCP(
    "AndroidResearchWorker",
    host=HOST,
    port=PORT,
)


# =====================================================================
# Helper Functions
# =====================================================================

def _get_network_Addresses() -> List[str]:
    """Mendapatkan daftar alamat IP lokal / Tailscale pada host."""
    ips: List[str] = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, family=socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass

    # Cek socket UDP dummy untuk mendeteksi IP interface utama
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("100.100.100.100", 80))  # Tailscale MagicDNS IP
            primary_ip = s.getsockname()[0]
            if primary_ip not in ips:
                ips.insert(0, primary_ip)
    except Exception:
        pass

    return ips or ["127.0.0.1"]


def _clean_html_to_text(html_content: str) -> str:
    """Mengekstrak teks bersih dari dokumen HTML."""
    try:
        import trafilatura  # type: ignore

        extracted = trafilatura.extract(
            html_content,
            include_comments=False,
            include_tables=True,
            no_fallback=False,
        )
        if extracted and len(extracted.strip()) > 100:
            return extracted.strip()
    except ImportError:
        pass
    except Exception as exc:
        logger.debug("Trafilatura gagal mengekstrak HTML: %s", exc)

    # Fallback menggunakan BeautifulSoup4
    soup = BeautifulSoup(html_content, "html.parser")
    for tag in soup(["script", "style", "nav", "footer", "header", "noscript", "svg", "aside", "form"]):
        tag.decompose()

    main_node = soup.find("article") or soup.find("main") or soup.body or soup
    lines = [line.strip() for line in main_node.get_text(separator="\n").splitlines()]
    cleaned_lines = [line for line in lines if line]
    return "\n".join(cleaned_lines)


def _search_web_urls(query: str, max_results: int) -> List[Dict[str, str]]:
    """Mencari daftar artikel web menggunakan duckduckgo-search atau fallback HTML."""
    results: List[Dict[str, str]] = []

    # Prioritas 1: Library duckduckgo_search
    try:
        from duckduckgo_search import DDGS  # type: ignore

        with DDGS() as ddgs:
            for item in ddgs.text(query, max_results=max_results):
                results.append(
                    {
                        "title": item.get("title", ""),
                        "url": item.get("href") or item.get("url", ""),
                        "snippet": item.get("body") or item.get("snippet", ""),
                    }
                )
        if results:
            return results[:max_results]
    except Exception as exc:
        logger.warning("Pencarian DDGS gagal, beralih ke fallback HTTP: %s", exc)

    # Prioritas 2: Fallback scraping ke DuckDuckGo HTML
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
    }
    try:
        with httpx.Client(timeout=12.0, follow_redirects=True, headers=headers) as client:
            resp = client.post("https://html.duckduckgo.com/html/", data={"q": query})
            resp.raise_for_status()
            soup = BeautifulSoup(resp.text, "html.parser")
            for res in soup.select(".result"):
                a_tag = res.select_one(".result__a")
                snippet_tag = res.select_one(".result__snippet")
                if not a_tag:
                    continue
                href = a_tag.get("href", "")
                if href and href.startswith("http"):
                    results.append(
                        {
                            "title": a_tag.get_text(strip=True),
                            "url": href,
                            "snippet": snippet_tag.get_text(strip=True) if snippet_tag else "",
                        }
                    )
                if len(results) >= max_results:
                    break
    except Exception as exc:
        logger.error("Fallback pencarian web gagal: %s", exc)

    return results[:max_results]


def _decode_document_if_pdf(raw_input: str) -> str:
    """Mendeteksi apakah input berupa string Base64 dari file PDF."""
    stripped = raw_input.strip()
    if stripped.startswith("data:application/pdf;base64,"):
        stripped = stripped.split(",", 1)[1].strip()

    if stripped.startswith("JVBERi0"):
        try:
            from pypdf import PdfReader  # type: ignore

            pdf_bytes = base64.b64decode(stripped)
            reader = PdfReader(io.BytesIO(pdf_bytes))
            pages_text = []
            for idx, page in enumerate(reader.pages, start=1):
                page_txt = page.extract_text() or ""
                if page_txt.strip():
                    pages_text.append(f"[Halaman {idx}]\n{page_txt.strip()}")
            return "\n\n".join(pages_text) if pages_text else ""
        except Exception as exc:
            logger.warning("Gagal mengekstrak PDF dari base64: %s", exc)

    return raw_input


def _score_sentences(text: str, top_n: int = 7) -> List[str]:
    """Ekstraksi kalimat terpenting berbasis frekuensi kata (TF scoring)."""
    sentences = [
        s.strip()
        for s in re.split(r"(?<=[.!?])\s+|\n+", text)
        if len(s.strip()) > 25
    ]
    if not sentences:
        return [text[:500]] if text else []

    if len(sentences) <= top_n:
        return sentences

    stopwords = {
        "yang", "dan", "di", "ke", "dari", "ini", "itu", "dengan", "untuk",
        "pada", "adalah", "sebagai", "dalam", "tidak", "akan", "atau", "juga",
        "oleh", "karena", "the", "and", "of", "to", "in", "is", "that", "for",
        "on", "with", "as", "are", "this", "by", "be", "at", "or", "from", "an",
    }

    words = re.findall(r"\b[a-zA-Z0-9_]{3,}\b", text.lower())
    freq = collections.Counter(w for w in words if w not in stopwords)
    if not freq:
        return sentences[:top_n]

    max_freq = max(freq.values())
    norm_freq = {w: count / max_freq for w, count in freq.items()}

    scored = []
    for idx, sent in enumerate(sentences):
        sent_words = re.findall(r"\b[a-zA-Z0-9_]{3,}\b", sent.lower())
        if not sent_words:
            continue
        score = sum(norm_freq.get(w, 0.0) for w in sent_words) / (len(sent_words) ** 0.5)
        if idx < 3:
            score *= 1.15
        scored.append((idx, score, sent))

    top_selected = sorted(scored, key=lambda x: x[1], reverse=True)[:top_n]
    top_ordered = sorted(top_selected, key=lambda x: x[0])
    return [item[2] for item in top_ordered]


def _validate_sandbox_ast(script_code: str) -> None:
    """Memeriksa AST script Python untuk mencegah pemanggilan modul sistem berbahaya."""
    forbidden_modules = {
        "os", "subprocess", "socket", "shutil", "sys", "pathlib",
        "http", "urllib", "requests", "httpx", "ftplib", "telnetlib",
        "ctypes", "multiprocessing", "threading", "signal", "pty", "resource",
    }
    forbidden_builtins = {"eval", "exec", "compile", "open", "input", "__import__"}

    tree = ast.parse(script_code, mode="exec")
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                root_mod = alias.name.split(".")[0]
                if root_mod in forbidden_modules:
                    raise ValueError(f"Import modul '{root_mod}' diblokir di dalam sandbox.")
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                root_mod = node.module.split(".")[0]
                if root_mod in forbidden_modules:
                    raise ValueError(f"Import dari modul '{root_mod}' diblokir di dalam sandbox.")
        elif isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name) and node.func.id in forbidden_builtins:
                raise ValueError(f"Pemanggilan fungsi '{node.func.id}()' diblokir di dalam sandbox.")
        elif isinstance(node, ast.Attribute):
            if node.attr.startswith("__") and node.attr.endswith("__"):
                raise ValueError(f"Akses atribut dunder '{node.attr}' diblokir di dalam sandbox.")


# =====================================================================
# Definisi Tools MCP
# =====================================================================

@mcp.tool()
def search_and_extract_web(query: str, max_results: int = 5) -> str:
    """Mencari artikel web berdasarkan kueri dan mengekstrak isi teks bersihnya untuk riset mendalam."""
    if not query or not query.strip():
        return json.dumps({"error": "Parameter 'query' tidak boleh kosong."}, ensure_ascii=False)

    max_results = max(1, min(int(max_results), 10))
    logger.info("Menjalankan search_and_extract_web: query='%s', max_results=%d", query, max_results)

    search_hits = _search_web_urls(query.strip(), max_results=max_results)
    if not search_hits:
        return json.dumps(
            {
                "query": query,
                "total_results": 0,
                "articles": [],
                "message": "Tidak ditemukan hasil pencarian untuk kueri tersebut.",
            },
            ensure_ascii=False,
            indent=2,
        )

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
    }

    extracted_articles: List[Dict[str, Any]] = []
    with httpx.Client(timeout=12.0, follow_redirects=True, headers=headers) as client:
        for idx, hit in enumerate(search_hits, start=1):
            url = hit.get("url", "")
            article_data: Dict[str, Any] = {
                "rank": idx,
                "title": hit.get("title", ""),
                "url": url,
                "snippet": hit.get("snippet", ""),
                "content": "",
                "word_count": 0,
                "status": "pending",
            }
            if not url:
                article_data["status"] = "skipped_empty_url"
                extracted_articles.append(article_data)
                continue

            try:
                resp = client.get(url)
                resp.raise_for_status()
                clean_text = _clean_html_to_text(resp.text)
                truncated_text = clean_text[:12000]
                article_data["content"] = truncated_text
                article_data["word_count"] = len(clean_text.split())
                article_data["truncated"] = len(clean_text) > 12000
                article_data["status"] = "ok"
            except Exception as exc:
                article_data["status"] = f"fetch_error: {type(exc).__name__}"
                article_data["content"] = hit.get("snippet", "")

            extracted_articles.append(article_data)

    return json.dumps(
        {
            "query": query,
            "total_results": len(extracted_articles),
            "articles": extracted_articles,
        },
        ensure_ascii=False,
        indent=2,
    )


@mcp.tool()
def analyze_document(file_content: str, task_type: str = "summary") -> str:
    """Merangkum dan mengekstrak poin penting dari teks dokumen panjang (PDF base64 / TXT / CSV)."""
    if not file_content or not file_content.strip():
        return json.dumps({"error": "Parameter 'file_content' tidak boleh kosong."}, ensure_ascii=False)

    task_normalized = (task_type or "summary").strip().lower()
    logger.info("Menjalankan analyze_document: task_type='%s', ukuran=%d karakter", task_normalized, len(file_content))

    decoded_text = _decode_document_if_pdf(file_content)
    lines = [line for line in decoded_text.splitlines() if line.strip()]
    words = decoded_text.split()

    doc_metrics = {
        "char_count": len(decoded_text),
        "word_count": len(words),
        "line_count": len(lines),
        "estimated_reading_minutes": round(len(words) / 200.0, 2),
    }

    is_csv_like = False
    dialect = csv.excel
    if task_normalized == "csv_stats" or (len(lines) >= 2 and lines[0].count(",") >= 2):
        try:
            sample_csv = "\n".join(lines[:50])
            dialect = csv.Sniffer().sniff(sample_csv, delimiters=",;\t")
            is_csv_like = True
        except Exception:
            dialect = csv.excel

    result_payload: Dict[str, Any] = {
        "task_type": task_normalized,
        "metrics": doc_metrics,
    }

    if is_csv_like and task_normalized in ("csv_stats", "summary", "full"):
        try:
            reader = list(csv.reader(io.StringIO(decoded_text), dialect=dialect))
            if reader:
                headers = reader[0]
                data_rows = reader[1:]
                col_summary: Dict[str, Any] = {}
                for col_idx, col_name in enumerate(headers):
                    col_vals = [
                        row[col_idx].strip()
                        for row in data_rows
                        if col_idx < len(row) and row[col_idx].strip() != ""
                    ]
                    numeric_vals: List[float] = []
                    for val in col_vals:
                        try:
                            numeric_vals.append(float(val.replace(",", "")))
                        except ValueError:
                            pass

                    if numeric_vals and len(numeric_vals) >= len(col_vals) * 0.6:
                        col_summary[col_name or f"col_{col_idx}"] = {
                            "type": "numeric",
                            "non_null_count": len(numeric_vals),
                            "min": min(numeric_vals),
                            "max": max(numeric_vals),
                            "mean": round(sum(numeric_vals) / len(numeric_vals), 4),
                        }
                    else:
                        top_common = collections.Counter(col_vals).most_common(3)
                        col_summary[col_name or f"col_{col_idx}"] = {
                            "type": "categorical/text",
                            "non_null_count": len(col_vals),
                            "unique_values": len(set(col_vals)),
                            "top_values": top_common,
                        }

                result_payload["csv_analysis"] = {
                    "columns": headers,
                    "row_count": len(data_rows),
                    "column_profiles": col_summary,
                }
        except Exception as exc:
            logger.warning("Analisis CSV gagal: %s", exc)

    top_sentences = _score_sentences(decoded_text, top_n=8)
    numeric_highlights = [
        s for s in top_sentences if re.search(r"\b\d+(?:[.,]\d+)?(?:\s*%|\s*Rp|\s*USD|\b)", s)
    ][:5]

    if task_normalized in ("summary", "full"):
        result_payload["summary"] = " ".join(top_sentences[:5])

    if task_normalized in ("key_points", "summary", "full"):
        result_payload["key_points"] = top_sentences
        result_payload["numeric_highlights"] = numeric_highlights

    return json.dumps(result_payload, ensure_ascii=False, indent=2)


@mcp.tool()
def execute_python_calc(script_code: str) -> str:
    """Menjalankan komputasi atau analisis data Python di dalam sandbox terisolasi."""
    if not script_code or not script_code.strip():
        return json.dumps({"status": "error", "error": "Parameter 'script_code' kosong."}, ensure_ascii=False)

    logger.info("Menjalankan execute_python_calc (%d karakter)", len(script_code))

    try:
        _validate_sandbox_ast(script_code)
    except SyntaxError as exc:
        return json.dumps(
            {
                "status": "syntax_error",
                "error": f"Baris {exc.lineno}: {exc.msg}",
            },
            ensure_ascii=False,
            indent=2,
        )
    except ValueError as exc:
        return json.dumps(
            {
                "status": "security_blocked",
                "error": str(exc),
            },
            ensure_ascii=False,
            indent=2,
        )

    runner_template = """
import collections
import datetime
import decimal
import functools
import itertools
import json
import math
import random
import re
import statistics

try:
    import numpy as np
except ImportError:
    pass

try:
    import pandas as pd
except ImportError:
    pass

_user_ns = {}
_code = __USER_CODE__
exec(compile(_code, "<sandbox>", "exec"), _user_ns, _user_ns)
if "result" in _user_ns:
    try:
        print("\\n__MCP_RESULT_JSON__:" + json.dumps(_user_ns["result"], default=str, ensure_ascii=False))
    except Exception as _e:
        print("\\n__MCP_RESULT_JSON__:" + json.dumps(str(_user_ns["result"]), ensure_ascii=False))
"""
    wrapped_code = runner_template.replace("__USER_CODE__", repr(script_code))

    start_ts = time.perf_counter()
    try:
        with tempfile.TemporaryDirectory(prefix="mcp_calc_") as tmp_dir:
            proc = subprocess.run(
                [sys.executable, "-I", "-c", wrapped_code],
                cwd=tmp_dir,
                capture_output=True,
                text=True,
                timeout=15.0,
                env={"PATH": os.environ.get("PATH", ""), "PYTHONIOENCODING": "utf-8"},
            )
        elapsed_ms = round((time.perf_counter() - start_ts) * 1000, 2)

        raw_stdout = proc.stdout or ""
        captured_result: Any = None
        if "__MCP_RESULT_JSON__:" in raw_stdout:
            stdout_Part, result_part = raw_stdout.rsplit("__MCP_RESULT_JSON__:", 1)
            raw_stdout = stdout_Part.rstrip()
            try:
                captured_result = json.loads(result_part.strip())
            except Exception:
                captured_result = result_part.strip()

        return json.dumps(
            {
                "status": "success" if proc.returncode == 0 else "runtime_error",
                "exit_code": proc.returncode,
                "execution_time_ms": elapsed_ms,
                "stdout": raw_stdout.strip(),
                "stderr": (proc.stderr or "").strip(),
                "result": captured_result,
            },
            ensure_ascii=False,
            indent=2,
        )
    except subprocess.TimeoutExpired:
        return json.dumps(
            {
                "status": "timeout",
                "error": "Eksekusi komputasi melebihi batas waktu 15 detik.",
            },
            ensure_ascii=False,
            indent=2,
        )
    except Exception as exc:
        return json.dumps(
            {
                "status": "error",
                "error": str(exc),
            },
            ensure_ascii=False,
            indent=2,
        )


@mcp.tool()
def ping_worker() -> str:
    """Mengecek status kesehatan server (health check), uptime, dan informasi jaringan worker."""
    uptime_seconds = round(time.time() - SERVER_START_TIME, 2)
    payload = {
        "status": "healthy",
        "server_name": "AndroidResearchWorker",
        "transport": "HTTP/SSE",
        "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "uptime_seconds": uptime_seconds,
        "hostname": socket.gethostname(),
        "network_ips": _get_network_Addresses(),
        "bound_address": f"{HOST}:{PORT}",
        "python_version": platform.python_version(),
        "platform": platform.platform(),
        "available_tools": [
            "search_and_extract_web",
            "analyze_document",
            "execute_python_calc",
            "ping_worker",
        ],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


# =====================================================================
# Konfigurasi Aplikasi ASGI (Starlette + MCP SSE + CORS untuk Android)
# =====================================================================

async def health_endpoint(request: Request) -> JSONResponse:
    """Endpoint HTTP GET /health standar untuk pengecekan cepat dari Android."""
    return JSONResponse(json.loads(ping_worker()))


def create_app() -> Starlette:
    """Membangun aplikasi ASGI Starlette dengan dukungan MCP SSE dan CORS."""
    sse_sub_app = mcp.sse_app()

    middleware = [
        Middleware(
            CORSMiddleware,
            allow_origins=["*"],
            allow_methods=["GET", "POST", "OPTIONS"],
            allow_headers=["*"],
        )
    ]

    routes = [
        Route("/health", endpoint=health_endpoint, methods=["GET"]),
        Mount("/", app=sse_sub_app),
    ]

    return Starlette(routes=routes, middleware=middleware)


app = create_app()


if __name__ == "__main__":
    ips = _get_network_Addresses()
    logger.info("==========================================================")
    logger.info("Memulai MCP SSE Server pada http://%s:%d", HOST, PORT)
    for ip in ips:
        logger.info("  -> SSE Endpoint (LAN/Tailscale): http://%s:%d/sse", ip, PORT)
        logger.info("  -> Health Check Endpoint       : http://%s:%d/health", ip, PORT)
    logger.info("==========================================================")

    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
        log_level="info",
        timeout_keep_alive=65,
    )
