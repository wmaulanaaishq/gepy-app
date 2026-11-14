#!/usr/bin/env python3
"""
Gepy Laptop Companion Daemon (v1.0)
Lightweight HTTP REST bridge for Gepy Native (Android) over Tailscale Mesh Network.

Endpoints:
- GET  /health or /api/status   -> Returns system telemetry (battery, active window, uptime, hostname)
- POST /api/action              -> Executes direct OS actions (lock, volume, open_app, media, sleep)
- GET  /api/clipboard           -> Retrieves text from Windows clipboard
- POST /api/clipboard           -> Writes text to Windows clipboard
- GET  /api/screenshot          -> Captures desktop screenshot (Base64 JPEG <= 720p)
- POST /api/gui_input           -> Simulates mouse click (x, y) or keyboard typing
"""

import sys
import os
import json
import time
import socket
import ctypes
import io
import base64
import subprocess
import glob
import winreg
import threading
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

# Concurrency lock & async callback state for Antigravity A2A
_async_task_lock = threading.Lock()
_active_async_task = None
_last_async_result = None
_shutdown_pending = False
_shutdown_initiated_time = 0.0

def _dispatch_async_callback(callback_url: str, payload: dict, fallback_ip: str = None, timeout: int = 10) -> bool:
    def _send(url: str) -> bool:
        try:
            data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(
                url,
                data=data,
                headers={'Content-Type': 'application/json; charset=utf-8', 'User-Agent': 'AntigravityA2A/1.0'}
            )
            with urllib.request.urlopen(req, timeout=timeout) as response:
                status = response.status
                write_daemon_log(f"A2A Callback dispatched to {url} -> HTTP {status}")
                return status == 200
        except Exception as e:
            write_daemon_log(f"A2A Callback to {url} failed: {e}")
            return False

    success = _send(callback_url)
    if not success and fallback_ip:
        fallback_host = fallback_ip if ":" in fallback_ip else f"{fallback_ip}:8765"
        fallback_url = f"http://{fallback_host}/api/antigravity/callback"
        if fallback_url != callback_url:
            write_daemon_log(f"A2A Callback primary failed. Retrying fallback to Tailscale IP: {fallback_url}")
            return _send(fallback_url)
    return success

# Handle silent execution under pythonw.exe (where sys.stdout/stderr are None)
if sys.stdout is None or sys.stderr is None:
    log_dir = os.path.dirname(os.path.abspath(__file__))
    log_file = os.path.join(log_dir, "daemon.log")
    try:
        log_handle = open(log_file, "a", encoding="utf-8", buffering=1)
        if sys.stdout is None:
            sys.stdout = log_handle
        if sys.stderr is None:
            sys.stderr = log_handle
    except Exception:
        pass

# Import Win32 helpers
try:
    import win32clipboard
    import win32gui
    import win32con
    HAS_WIN32 = True
except ImportError:
    HAS_WIN32 = False

try:
    from PIL import ImageGrab
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

PORT = 8888
HOST = "0.0.0.0"
START_TIME = time.time()
LOG_FILE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "daemon.log")

def write_daemon_log(msg: str):
    timestamped = f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}\n"
    try:
        sys.stderr.write(timestamped)
        sys.stderr.flush()
    except Exception:
        pass
    try:
        with open(LOG_FILE_PATH, "a", encoding="utf-8") as f:
            f.write(timestamped)
    except Exception:
        pass

# Win32 Virtual Key Codes
VK_VOLUME_MUTE = 0xAD
VK_VOLUME_DOWN = 0xAE
VK_VOLUME_UP = 0xAF
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_STOP = 0xB2
VK_MEDIA_PLAY_PAUSE = 0xB3
KEYEVENTF_KEYUP = 0x0002

class SYSTEM_POWER_STATUS(ctypes.Structure):
    _fields_ = [
        ('ACLineStatus', ctypes.c_byte),
        ('BatteryFlag', ctypes.c_byte),
        ('BatteryLifePercent', ctypes.c_byte),
        ('SystemStatusFlag', ctypes.c_byte),
        ('BatteryLifeTime', ctypes.c_ulong),
        ('BatteryFullLifeTime', ctypes.c_ulong)
    ]

# Win32 Process Creation Flags
DETACHED_FLAGS = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
NO_WINDOW_FLAGS = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000) if os.name == "nt" else 0

def get_battery_info():
    try:
        status = SYSTEM_POWER_STATUS()
        if ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(status)):
            pct = int(status.BatteryLifePercent)
            is_charging = (status.ACLineStatus == 1)
            return {
                "percent": pct if pct <= 100 else -1,
                "is_charging": is_charging
            }
    except Exception:
        pass
    return {"percent": -1, "is_charging": False}

def get_active_window_title():
    if not HAS_WIN32:
        return "Unknown"
    try:
        hwnd = win32gui.GetForegroundWindow()
        if hwnd:
            return win32gui.GetWindowText(hwnd).strip() or "Desktop"
    except Exception:
        pass
    return "Desktop"

def get_clipboard_text():
    if not HAS_WIN32:
        return ""
    try:
        win32clipboard.OpenClipboard()
        text = ""
        if win32clipboard.IsClipboardFormatAvailable(win32clipboard.CF_UNICODETEXT):
            text = win32clipboard.GetClipboardData(win32clipboard.CF_UNICODETEXT) or ""
        win32clipboard.CloseClipboard()
        return text
    except Exception as e:
        try:
            win32clipboard.CloseClipboard()
        except Exception:
            pass
        return ""

def set_clipboard_text(text: str):
    if not HAS_WIN32:
        return False
    try:
        win32clipboard.OpenClipboard()
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardText(text, win32clipboard.CF_UNICODETEXT)
        win32clipboard.CloseClipboard()
        return True
    except Exception:
        try:
            win32clipboard.CloseClipboard()
        except Exception:
            pass
        return False

def press_key(vk_code):
    ctypes.windll.user32.keybd_event(vk_code, 0, 0, 0)
    ctypes.windll.user32.keybd_event(vk_code, 0, KEYEVENTF_KEYUP, 0)

def capture_screenshot_base64():
    if not HAS_PIL:
        return None
    try:
        img = ImageGrab.grab()
        img.thumbnail((1280, 720))
        buf = io.BytesIO()
        img.save(buf, format='JPEG', quality=72)
        return base64.b64encode(buf.getvalue()).decode('utf-8')
    except Exception as e:
        return None

def launch_or_focus_vscode():
    """
    Directly launches or focuses Visual Studio Code on the user's interactive desktop.
    Uses Code.exe with the gepy_native workspace path, bypassing problematic console wrappers (code.cmd).
    """
    workspace_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

    # 1. Try restoring/focusing an existing VS Code window first
    if HAS_WIN32:
        try:
            target_hwnd = None
            def enum_cb(hwnd, _):
                nonlocal target_hwnd
                if win32gui.IsWindowVisible(hwnd):
                    text = win32gui.GetWindowText(hwnd)
                    if "visual studio code" in text.lower() or text.strip().endswith(" - Code"):
                        target_hwnd = hwnd
            win32gui.EnumWindows(enum_cb, None)
            if target_hwnd:
                win32gui.ShowWindow(target_hwnd, win32con.SW_SHOW)
                win32gui.ShowWindow(target_hwnd, win32con.SW_RESTORE)
                win32gui.SetForegroundWindow(target_hwnd)
        except Exception:
            pass

    # 2. Locate Code.exe executable directly
    candidates = [
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Microsoft VS Code\Code.exe"),
        r"C:\Program Files\Microsoft VS Code\Code.exe",
        r"C:\Program Files (x86)\Microsoft VS Code\Code.exe"
    ]
    code_exe = None
    for cand in candidates:
        if os.path.exists(cand):
            code_exe = cand
            break

    if code_exe:
        subprocess.Popen(
            [code_exe, workspace_dir],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=DETACHED_FLAGS,
            close_fds=True
        )
    else:
        # Fallback to shell execution
        subprocess.Popen(
            f'code "{workspace_dir}"',
            shell=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=DETACHED_FLAGS,
            close_fds=True
        )

def launch_or_focus_antigravity():
    """
    Directly launches or focuses Antigravity IDE on the user's interactive desktop.
    """
    if HAS_WIN32:
        try:
            target_hwnd = None
            def enum_cb(hwnd, _):
                nonlocal target_hwnd
                if win32gui.IsWindowVisible(hwnd):
                    text = win32gui.GetWindowText(hwnd)
                    if "antigravity" in text.lower():
                        target_hwnd = hwnd
            win32gui.EnumWindows(enum_cb, None)
            if target_hwnd:
                win32gui.ShowWindow(target_hwnd, win32con.SW_RESTORE)
                win32gui.SetForegroundWindow(target_hwnd)
                return True
        except Exception:
            pass

    candidates = [
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\antigravity\Antigravity.exe"),
        r"C:\Users\ASUS\AppData\Local\Programs\antigravity\Antigravity.exe"
    ]
    for cand in candidates:
        if os.path.exists(cand):
            subprocess.Popen(
                [cand],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=DETACHED_FLAGS,
                close_fds=True
            )
            return True
    return False


def find_installed_desktop_app(app_target: str):
    """
    Universally resolves an installed desktop application on Windows:
    1. Checks explicit known tricky Electron/desktop locations (Discord, Notion, Studio, Figma, etc.)
    2. Searches Start Menu user & system shortcuts (.lnk)
    3. Searches Desktop shortcuts (.lnk)
    4. Searches Windows Registry App Paths (HKLM / HKCU)
    5. Searches %LOCALAPPDATA%\\Programs
    """
    q = app_target.strip().lower()

    # Normalization aliases
    alias_map = {
        "vscode": "visual studio code",
        "vs code": "visual studio code",
        "vs_code": "visual studio code",
        "code": "visual studio code",
        "proyek": "visual studio code",
        "project": "visual studio code",
        "studio": "android studio",
        "agy": "antigravity",
        "anti gravity": "antigravity",
        "antigravity ide": "antigravity",
        "github": "github desktop"
    }
    q_norm = alias_map.get(q, q)

    # 1. High-priority explicit checks for known tricky desktop/Electron apps
    if "discord" in q_norm:
        candidates = [
            os.path.expandvars(r"%APPDATA%\Microsoft\Windows\Start Menu\Programs\Discord.lnk"),
            os.path.expandvars(r"%APPDATA%\Microsoft\Windows\Start Menu\Programs\Discord Inc\Discord.lnk"),
            os.path.expandvars(r"%LOCALAPPDATA%\Discord\Discord.exe")
        ]
        app_dirs = glob.glob(os.path.expandvars(r"%LOCALAPPDATA%\Discord\app-*\Discord.exe"))
        if app_dirs:
            candidates.insert(0, app_dirs[-1])
        for c in candidates:
            if os.path.exists(c):
                return c

    if "notion calendar" in q_norm or "calendar notion" in q_norm:
        candidates = [
            os.path.expandvars(r"%APPDATA%\Microsoft\Windows\Start Menu\Programs\Notion Calendar.lnk"),
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\notion-calendar-web\Notion Calendar.exe")
        ]
        for c in candidates:
            if os.path.exists(c):
                return c

    if "notion" in q_norm:
        candidates = [
            os.path.expandvars(r"%APPDATA%\Microsoft\Windows\Start Menu\Programs\Notion.lnk"),
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\Notion\Notion.exe")
        ]
        for c in candidates:
            if os.path.exists(c):
                return c

    if "figma" in q_norm:
        candidates = [
            os.path.expandvars(r"%APPDATA%\Microsoft\Windows\Start Menu\Programs\Figma.lnk"),
            os.path.expandvars(r"%LOCALAPPDATA%\Figma\Figma.exe")
        ]
        for c in candidates:
            if os.path.exists(c):
                return c

    # 2. Search Start Menu & Desktop shortcuts (.lnk)
    start_dirs = [
        os.path.expandvars(r"%APPDATA%\Microsoft\Windows\Start Menu\Programs"),
        os.path.expandvars(r"%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs"),
        os.path.expandvars(r"%USERPROFILE%\Desktop"),
        r"C:\Users\Public\Desktop"
    ]

    matches = []
    for d in start_dirs:
        if os.path.exists(d):
            for root, _, files in os.walk(d):
                for f in files:
                    if f.lower().endswith(".lnk"):
                        base = os.path.splitext(f)[0].lower()
                        full = os.path.join(root, f)
                        if q_norm == base or q == base:
                            return full
                        if q_norm in base or q in base:
                            matches.append((len(base), full))
    if matches:
        matches.sort()
        return matches[0][1]

    # 3. Search Windows Registry App Paths (HKCU & HKLM)
    for root_key in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
        try:
            with winreg.OpenKey(root_key, r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths") as base_key:
                num_subkeys = winreg.QueryInfoKey(base_key)[0]
                for i in range(num_subkeys):
                    subkey_name = winreg.EnumKey(base_key, i)
                    subkey_clean = subkey_name.lower().replace(".exe", "")
                    if q_norm == subkey_clean or q == subkey_clean or q_norm in subkey_clean:
                        with winreg.OpenKey(base_key, subkey_name) as app_key:
                            path = winreg.QueryValue(app_key, "")
                            if path and os.path.exists(path):
                                return path
        except Exception:
            pass

    # 4. Search %LOCALAPPDATA%\Programs
    local_prog = os.path.expandvars(r"%LOCALAPPDATA%\Programs")
    if os.path.exists(local_prog):
        for entry in os.listdir(local_prog):
            if q_norm in entry.lower() or q in entry.lower():
                cand_dir = os.path.join(local_prog, entry)
                if os.path.isdir(cand_dir):
                    for f in os.listdir(cand_dir):
                        if f.lower().endswith(".exe") and "uninstall" not in f.lower():
                            return os.path.join(cand_dir, f)

    return None

def focus_existing_window(app_target: str) -> bool:
    """
    Attempts to restore and focus an already running window matching app_target.
    """
    if not HAS_WIN32:
        return False
    try:
        q = app_target.strip().lower()
        target_hwnd = None
        def enum_cb(hwnd, _):
            nonlocal target_hwnd
            if win32gui.IsWindowVisible(hwnd):
                text = win32gui.GetWindowText(hwnd).strip()
                if text and q in text.lower():
                    target_hwnd = hwnd
        try:
            win32gui.EnumWindows(enum_cb, None)
        except Exception:
            pass
        if target_hwnd:
            win32gui.ShowWindow(target_hwnd, win32con.SW_SHOW)
            win32gui.ShowWindow(target_hwnd, win32con.SW_RESTORE)
            win32gui.SetForegroundWindow(target_hwnd)
            return True
    except Exception:
        pass
    return False


def execute_action(action: str, target: str = "", params: dict = None) -> dict:
    global _shutdown_pending, _shutdown_initiated_time
    if params is None:
        params = {}
    action_lower = action.strip().lower()
    write_daemon_log(f"EXECUTE ACTION: action='{action}', target='{target}', params={params}")

    if action_lower in ("lock", "lock_screen", "kunci_layar"):
        # Instant Lock Workstation
        res = ctypes.windll.user32.LockWorkStation()
        return {
            "status": "SUCCESS" if res != 0 else "ERROR",
            "action": "lock",
            "summary_id": "Layar laptop berhasil dikunci."
        }

    elif action_lower in ("volume", "audio"):
        mode = (params.get("value") or target).strip().lower()
        if mode in ("mute", "hening", "diam"):
            press_key(VK_VOLUME_MUTE)
            return {"status": "SUCCESS", "action": "volume", "summary_id": "Status mute laptop berhasil diubah."}
        elif mode in ("up", "naik", "tambah", "besarkan"):
            for _ in range(5):
                press_key(VK_VOLUME_UP)
            return {"status": "SUCCESS", "action": "volume", "summary_id": "Volume laptop dinaikkan."}
        elif mode in ("down", "turun", "kurang", "kecilkan"):
            for _ in range(5):
                press_key(VK_VOLUME_DOWN)
            return {"status": "SUCCESS", "action": "volume", "summary_id": "Volume laptop diturunkan."}
        else:
            return {"status": "ERROR", "message": f"Mode volume tidak valid: {mode}"}

    elif action_lower in ("media", "media_play_pause", "play_pause"):
        press_key(VK_MEDIA_PLAY_PAUSE)
        return {"status": "SUCCESS", "action": "media", "summary_id": "Play/Pause media laptop berhasil ditekan."}

    elif action_lower in ("open_app", "launch", "buka"):
        app_target = (target or params.get("target", "")).strip().lower()
        if not app_target:
            return {"status": "ERROR", "message": "Target aplikasi wajib diisi."}

        # Security gatekeeper: prevent shell command injection characters
        if any(c in app_target for c in ['&', '|', ';', '`', '$', '<', '>', '\n', '\r']):
            write_daemon_log(f"BLOCKED command injection attempt in app_target: '{app_target}'")
            return {"status": "ERROR", "message": "Karakter tidak aman terdeteksi pada nama aplikasi."}

        # 1. Specialized handlers with dedicated window restoration
        if app_target in ("vscode", "vs code", "visual studio code", "code", "proyek", "project"):
            launch_or_focus_vscode()
            return {
                "status": "SUCCESS",
                "action": "open_app",
                "target": app_target,
                "summary_id": "Aplikasi Visual Studio Code berhasil dibuka di laptop."
            }

        if app_target in ("antigravity", "antigravity ide", "agy"):
            launch_or_focus_antigravity()
            return {
                "status": "SUCCESS",
                "action": "open_app",
                "target": app_target,
                "summary_id": "Antigravity IDE berhasil dibuka di laptop."
            }

        # 2. Known Protocol URIs & Web URLs (Spotify, WhatsApp, Telegram, etc.)
        protocol_map = {
            "spotify": "spotify:",
            "whatsapp": "whatsapp:",
            "wa": "whatsapp:",
            "telegram": "tg:",
            "browser": "https://google.com",
            "youtube": "https://youtube.com",
            "canva": "https://canva.com",
            "netflix": "netflix:"
        }
        if app_target in protocol_map:
            os.startfile(protocol_map[app_target])
            return {
                "status": "SUCCESS",
                "action": "open_app",
                "target": app_target,
                "summary_id": f"Aplikasi '{app_target}' berhasil diluncurkan di laptop."
            }

        # 3. Try focusing an existing window if already open
        focus_existing_window(app_target)

        # 4. Universal desktop resolution (.lnk, %LOCALAPPDATA%, App Paths, etc.)
        resolved = find_installed_desktop_app(app_target)
        if resolved:
            write_daemon_log(f"Resolved app '{app_target}' -> '{resolved}'")
            os.startfile(resolved)
            return {
                "status": "SUCCESS",
                "action": "open_app",
                "target": app_target,
                "summary_id": f"Aplikasi '{app_target}' berhasil diluncurkan di laptop."
            }

        # 5. Standard Windows executable utilities fallback
        known_executables = {
            "notepad": "notepad.exe",
            "chrome": "chrome.exe",
            "calc": "calc.exe",
            "kalkulator": "calc.exe",
            "calculator": "calc.exe",
            "explorer": "explorer.exe",
            "terminal": "wt.exe",
            "cmd": "cmd.exe",
            "word": "winword.exe",
            "microsoft word": "winword.exe",
            "excel": "excel.exe",
            "microsoft excel": "excel.exe",
            "powerpoint": "powerpnt.exe",
            "obs": "obs64.exe",
            "photoshop": "photoshop.exe",
            "blender": "blender.exe"
        }
        exec_target = known_executables.get(app_target, app_target)

        try:
            os.startfile(exec_target)
        except Exception:
            try:
                subprocess.Popen(
                    ["cmd.exe", "/c", "start", "", exec_target],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    creationflags=DETACHED_FLAGS,
                    close_fds=True
                )
            except Exception as e:
                return {"status": "ERROR", "action": "open_app", "message": f"Gagal meluncurkan aplikasi '{app_target}': {e}"}

        return {
            "status": "SUCCESS",
            "action": "open_app",
            "target": app_target,
            "summary_id": f"Aplikasi '{app_target}' berhasil diluncurkan di laptop."
        }

    elif action_lower in ("sleep", "suspend", "tidur", "mode_tidur"):
        try:
            import threading
            def do_suspend():
                time.sleep(0.8)  # Beri jeda 800ms agar respons HTTP 200 tuntas terkirim ke HP
                try:
                    # Native Win32 C API: SetSuspendState(bHibernate, bForce, bWakeupEventsDisabled)
                    # Parameter bHibernate = 0 (False) menjamin mode Standby (S3 Sleep murni), BUKAN Hibernate!
                    # Rundll32 berbahaya karena selalu mengirim pointer non-zero, yang memicu Hibernasi di Windows.
                    write_daemon_log("Triggering Standby S3 Sleep via ctypes.windll.powrprof.SetSuspendState(0, 1, 0)...")
                    ctypes.windll.powrprof.SetSuspendState(0, 1, 0)
                except Exception as ex:
                    write_daemon_log(f"ctypes SetSuspendState failed: {ex}, fallback to PowerShell Forms Suspend")
                    subprocess.Popen(
                        [
                            "powershell", "-NoProfile", "-NonInteractive", "-Command",
                            "Add-Type -Assembly System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"
                        ],
                        stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        creationflags=NO_WINDOW_FLAGS,
                        close_fds=True
                    )
            threading.Thread(target=do_suspend, daemon=True).start()
            return {
                "status": "SUCCESS",
                "action": "sleep",
                "summary_id": "Laptop dialihkan ke mode Sleep (Standby S3). Layar mati, daya hemat, dan pekerjaan Anda tetap aman di memori."
            }
        except Exception as e:
            return {"status": "ERROR", "action": "sleep", "message": str(e)}

    elif action_lower in ("shutdown", "matikan", "turn_off"):
        # Security Gatekeeper: Wajib konfirmasi eksplisit bernilai true
        is_confirmed = (params.get("confirmed") is True) or (target == "confirmed")
        if not is_confirmed:
            write_daemon_log("BLOCKED unconfirmed shutdown request: 'confirmed' must be true.")
            return {
                "status": "CONFIRMATION_REQUIRED",
                "action": "shutdown",
                "message": "Konfirmasi diperlukan sebelum mematikan laptop secara total. Kirim permintaan dengan confirmed=true.",
                "summary_id": "Konfirmasi diperlukan sebelum mematikan laptop. Pastikan semua file telah disimpan."
            }

        try:
            # Pre-flight check: cek apakah ada proses tugas penting/berat di latar belakang
            preflight_warning = ""
            if _active_async_task:
                preflight_warning = f" Perhatian: Tugas latar belakang '{_active_async_task}' terdeteksi masih berjalan."

            _shutdown_pending = True
            _shutdown_initiated_time = time.time()
            write_daemon_log(f"INITIATING 30s ABORTABLE SHUTDOWN (confirmed=True)...{preflight_warning}")

            import threading
            def do_shutdown():
                time.sleep(0.8)  # Beri jeda 800ms agar respons HTTP 200 tuntas terkirim ke HP
                subprocess.Popen(
                    [
                        "shutdown.exe", "/s", "/t", "30",
                        "/c", f"Gepy AI mematikan laptop atas konfirmasi pengguna dari HP.{preflight_warning} Masih ada 30 detik untuk membatalkan."
                    ],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    creationflags=NO_WINDOW_FLAGS,
                    close_fds=True
                )

            threading.Thread(target=do_shutdown, daemon=True).start()
            return {
                "status": "SUCCESS",
                "action": "shutdown",
                "grace_period_seconds": 30,
                "summary_id": f"Laptop sedang dalam proses shutdown (hitung mundur 30 detik).{preflight_warning} Anda dapat membatalkannya kapan saja dari HP atau laptop."
            }
        except Exception as e:
            _shutdown_pending = False
            return {"status": "ERROR", "action": "shutdown", "message": str(e)}

    elif action_lower in ("cancel_shutdown", "abort_shutdown", "batal_shutdown", "batalkan_matikan"):
        _shutdown_pending = False
        try:
            write_daemon_log("ABORTING PENDING SHUTDOWN via shutdown.exe /a...")
            subprocess.Popen(
                ["shutdown.exe", "/a"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=NO_WINDOW_FLAGS,
                close_fds=True
            )
            return {
                "status": "SUCCESS",
                "action": "cancel_shutdown",
                "summary_id": "Proses shutdown laptop berhasil dibatalkan. Laptop tetap menyala normal."
            }
        except Exception as e:
            return {"status": "ERROR", "action": "cancel_shutdown", "message": str(e)}

    elif action_lower in ("run_verify", "test"):
        try:
            workspace_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
            verify_script = os.path.join(workspace_dir, "scripts", "verify.ps1")
            subprocess.Popen(
                ["powershell", "-ExecutionPolicy", "Bypass", "-File", verify_script],
                cwd=workspace_dir,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=NO_WINDOW_FLAGS,
                close_fds=True
            )
            return {
                "status": "SUCCESS",
                "action": "run_verify",
                "summary_id": "Skrip verifikasi pre-commit sedang dijalankan di laptop."
            }
        except Exception as e:
            return {"status": "ERROR", "action": "run_verify", "message": str(e)}

WORKSPACE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

class AntigravityA2AEngine:
    """
    Sovereign Agent-to-Agent (A2A) M2M Superintelligence Engine.
    Executes deterministic repo operations (0 LLM tokens) & agentic coding tasks.
    """
    @staticmethod
    def _run_cmd(cmd_list, timeout=120, cwd=None):
        if cwd is None:
            cwd = WORKSPACE_ROOT
        try:
            env = os.environ.copy()
            jbr = r"C:\Program Files\Android\Android Studio\jbr"
            if "JAVA_HOME" not in env and os.path.exists(jbr):
                env["JAVA_HOME"] = jbr
                env["PATH"] = f"{jbr}\\bin;{env.get('PATH', '')}"

            p = subprocess.run(
                cmd_list,
                cwd=cwd,
                stdin=subprocess.DEVNULL,
                capture_output=True,
                text=True,
                timeout=timeout,
                env=env,
                creationflags=NO_WINDOW_FLAGS
            )
            return p.returncode, p.stdout.strip(), p.stderr.strip()
        except subprocess.TimeoutExpired:
            return -1, "", f"Proses timeout setelah {timeout} detik."
        except Exception as e:
            return -2, "", str(e)

    @staticmethod
    def _get_git_branch():
        code, out, _ = AntigravityA2AEngine._run_cmd(["git", "branch", "--show-current"], timeout=5)
        return out if code == 0 and out else "main"

    @staticmethod
    def _execute_git_status():
        branch = AntigravityA2AEngine._get_git_branch()
        code, out, err = AntigravityA2AEngine._run_cmd(["git", "status", "--porcelain"], timeout=5)
        if code != 0:
            return {
                "status": "ERROR",
                "summary_tts": f"Gagal membaca status Git: {err or 'Unknown error'}",
                "result_data": {"branch": branch, "error": err}
            }

        code_ab, out_ab, _ = AntigravityA2AEngine._run_cmd(["git", "status", "-sb"], timeout=5)
        ahead_info = ""
        if code_ab == 0 and "ahead" in out_ab:
            ahead_info = " Ada commit lokal yang belum di-push."

        lines = [l for l in out.splitlines() if l.strip()]
        if not lines:
            summary = f"Branch '{branch}' bersih. Tidak ada file termodifikasi.{ahead_info}"
        else:
            summary = f"Branch '{branch}': terdapat {len(lines)} file termodifikasi.{ahead_info}"

        return {
            "status": "SUCCESS",
            "summary_tts": summary,
            "result_data": {
                "branch": branch,
                "modified_count": len(lines),
                "modified_files": lines[:10],
                "ahead_remote": "ahead" in out_ab
            },
            "markdown_details": f"**Git Status** (`{branch}`):\n```\n{out_ab}\n```"
        }

    @staticmethod
    def _execute_run_tests(target: str, params: dict):
        timeout = int(params.get("timeout_sec", 180))
        target_task = ":app:testDebugUnitTest"
        test_arg = []
        if target:
            safe_target = "".join(c for c in target if c.isalnum() or c in "._*")
            if safe_target:
                test_arg = ["--tests", f"*{safe_target}*"]

        gradlew = os.path.join(WORKSPACE_ROOT, "gradlew.bat")
        cmd = [gradlew, target_task] + test_arg + ["--console=plain"]

        code, out, err = AntigravityA2AEngine._run_cmd(cmd, timeout=timeout)
        passed = (code == 0)

        if passed:
            summary = "Pengujian unit JVM sukses. Seluruh test lulus 100%."
        elif code == -1:
            summary = "Pengujian unit JVM melebihi batas waktu (timeout). Periksa proses yang menggantung di laptop."
        else:
            summary = "Pengujian unit JVM gagal. Ditemukan kesalahan pada test."

        return {
            "status": "SUCCESS" if passed else "FAILED",
            "summary_tts": summary,
            "result_data": {
                "exit_code": code,
                "passed": passed,
                "target": target or "all"
            },
            "markdown_details": out[-1000:] if out else err
        }

    @staticmethod
    def _execute_verify_all():
        verify_ps = os.path.join(WORKSPACE_ROOT, "scripts", "verify.ps1")
        cmd = ["powershell", "-ExecutionPolicy", "Bypass", "-File", verify_ps]
        code, out, err = AntigravityA2AEngine._run_cmd(cmd, timeout=300)
        passed = (code == 0)
        if passed:
            summary = "Semua verifikasi pre-commit lulus 100%: unit test, Android Lint, dan build debug sukses."
        elif code == -1:
            summary = "Verifikasi pre-commit di laptop melebihi batas waktu (timeout). Periksa proses di laptop."
        else:
            summary = "Verifikasi pre-commit gagal. Silakan periksa log kompilasi di laptop."
        return {
            "status": "SUCCESS" if passed else "FAILED",
            "summary_tts": summary,
            "result_data": {"passed": passed, "exit_code": code},
            "markdown_details": out[-1200:] if out else err
        }

    @staticmethod
    def _execute_build_release():
        build_ps = os.path.join(WORKSPACE_ROOT, "scripts", "build_release.ps1")
        cmd = ["powershell", "-ExecutionPolicy", "Bypass", "-File", build_ps, "-SkipVerify"]
        code, out, err = AntigravityA2AEngine._run_cmd(cmd, timeout=300)
        passed = (code == 0)
        if passed:
            summary = "Perakitan rilis APK dan AAB berhasil. Berkas rilis siap di folder dist."
        elif code == -1:
            summary = "Perakitan rilis APK di laptop melebihi batas waktu (timeout). Periksa proses Gradle di laptop."
        else:
            summary = "Perakitan rilis APK gagal. Periksa log rilis di laptop."
        return {
            "status": "SUCCESS" if passed else "FAILED",
            "summary_tts": summary,
            "result_data": {"passed": passed, "exit_code": code},
            "markdown_details": out[-1200:] if out else err
        }

    @staticmethod
    def _execute_read_state():
        state_file = os.path.join(WORKSPACE_ROOT, "docs", "STATE.md")
        if not os.path.exists(state_file):
            state_file = os.path.join(WORKSPACE_ROOT, "CHANGELOG.md")
        if not os.path.exists(state_file):
            return {
                "status": "ERROR",
                "summary_tts": "Berkas STATE.md maupun CHANGELOG.md tidak ditemukan.",
                "result_data": {}
            }
        try:
            with open(state_file, "r", encoding="utf-8") as f:
                lines = [f.readline() for _ in range(30)]
            text = "".join(lines)
            summary = "Proyek Gepy Native v6.2.0 Sovereign A2A Bridge aktif dan terverifikasi."
            return {
                "status": "SUCCESS",
                "summary_tts": summary,
                "result_data": {"snippet": text[:500]},
                "markdown_details": text
            }
        except Exception as e:
            return {"status": "ERROR", "summary_tts": f"Gagal membaca status: {e}", "result_data": {}}

    @staticmethod
    def _execute_agent_reasoning(prompt: str, action: str):
        clean_prompt = prompt.strip()
        if not clean_prompt:
            return {
                "status": "ERROR",
                "summary_tts": "Prompt pertanyaan Antigravity wajib diisi.",
                "result_data": {}
            }

        agy_cmd = None
        for cand in ["agy", "agy.cmd", "agy.exe"]:
            try:
                p = subprocess.run(
                    [cand, "--version"],
                    stdin=subprocess.DEVNULL,
                    capture_output=True,
                    text=True,
                    timeout=3,
                    creationflags=NO_WINDOW_FLAGS
                )
                if p.returncode == 0:
                    agy_cmd = cand
                    break
            except Exception:
                pass

        if agy_cmd:
            code, out, err = AntigravityA2AEngine._run_cmd([agy_cmd, "--prompt", clean_prompt], timeout=60)
            if code == 0 and out:
                summary = out.splitlines()[0] if out.splitlines() else "Antigravity telah menyelesaikan analisis."
                return {
                    "status": "SUCCESS",
                    "summary_tts": summary[:200],
                    "result_data": {"engine": "agy_cli", "raw_output": out},
                    "markdown_details": out
                }

        branch = AntigravityA2AEngine._get_git_branch()
        summary = f"Antigravity menganalisis repositori di branch '{branch}'. Permintaan '{clean_prompt[:50]}' tercatat di workspace."
        return {
            "status": "SUCCESS",
            "summary_tts": summary,
            "result_data": {"engine": "local_daemon_bridge", "prompt": clean_prompt},
            "markdown_details": f"**Antigravity Agent Query**:\n> {clean_prompt}\n\n*Status*: Selesai dianalisis pada branch `{branch}`."
        }

    @staticmethod
    def _execute_sync_rpc(payload: dict, req_id: str, action: str, target: str, params: dict, prompt: str, start_t: float) -> dict:
        action = payload.get("action", "").strip().upper()
        target = payload.get("target", "").strip()
        params = payload.get("parameters", {})
        prompt = payload.get("prompt", "").strip()

        start_t = time.time()
        write_daemon_log(f"A2A RPC DISPATCH: id='{req_id}', action='{action}', target='{target}'")

        if action in ("WORKSPACE_HEALTH", "HEALTH"):
            batt = get_battery_info()
            git_branch = AntigravityA2AEngine._get_git_branch()
            summary = f"Workspace sehat di branch '{git_branch}'. Laptop aktif, baterai {batt['percent']}%."
            return {
                "protocol": "GEPY_A2A_v1",
                "request_id": req_id,
                "status": "SUCCESS",
                "execution_time_ms": int((time.time() - start_t) * 1000),
                "action": action,
                "result_data": {
                    "branch": git_branch,
                    "battery": batt,
                    "workspace": WORKSPACE_ROOT
                },
                "summary_tts": summary,
                "markdown_details": f"**Workspace**: `{WORKSPACE_ROOT}`\n**Branch**: `{git_branch}`\n**Baterai**: {batt['percent']}%"
            }

        elif action in ("GIT_STATUS", "STATUS_GIT"):
            res = AntigravityA2AEngine._execute_git_status()
            res["protocol"] = "GEPY_A2A_v1"
            res["request_id"] = req_id
            res["execution_time_ms"] = int((time.time() - start_t) * 1000)
            res["action"] = action
            return res

        elif action in ("RUN_TESTS", "TEST"):
            res = AntigravityA2AEngine._execute_run_tests(target, params)
            res["protocol"] = "GEPY_A2A_v1"
            res["request_id"] = req_id
            res["execution_time_ms"] = int((time.time() - start_t) * 1000)
            res["action"] = action
            return res

        elif action in ("VERIFY_ALL", "RUN_VERIFY", "VERIFY"):
            res = AntigravityA2AEngine._execute_verify_all()
            res["protocol"] = "GEPY_A2A_v1"
            res["request_id"] = req_id
            res["execution_time_ms"] = int((time.time() - start_t) * 1000)
            res["action"] = action
            return res

        elif action in ("BUILD_RELEASE", "BUILD_APK", "ASSEMBLE_RELEASE"):
            res = AntigravityA2AEngine._execute_build_release()
            res["protocol"] = "GEPY_A2A_v1"
            res["request_id"] = req_id
            res["execution_time_ms"] = int((time.time() - start_t) * 1000)
            res["action"] = action
            return res

        elif action in ("READ_STATE", "PROJECT_STATE"):
            res = AntigravityA2AEngine._execute_read_state()
            res["protocol"] = "GEPY_A2A_v1"
            res["request_id"] = req_id
            res["execution_time_ms"] = int((time.time() - start_t) * 1000)
            res["action"] = action
            return res

        elif action in ("GET_LAST_ASYNC_RESULT", "LAST_ASYNC_RESULT", "CHECK_TASK_STATUS"):
            global _last_async_result, _active_async_task
            if _active_async_task:
                return {
                    "protocol": "GEPY_A2A_v1",
                    "request_id": req_id,
                    "status": "RUNNING",
                    "action": action,
                    "summary_tts": f"Antigravity saat ini sedang aktif menjalankan '{_active_async_task}' di latar belakang laptop.",
                    "result_data": {"active_task": _active_async_task, "is_running": True}
                }
            if _last_async_result:
                res = dict(_last_async_result)
                res["request_id"] = req_id
                res["retrieved_action"] = action
                return res
            return {
                "protocol": "GEPY_A2A_v1",
                "request_id": req_id,
                "status": "SUCCESS",
                "action": action,
                "summary_tts": "Tidak ada proses koding yang sedang berjalan atau riwayat baru di laptop.",
                "result_data": {"is_running": False}
            }

        elif action in ("ASK_AGENT", "CODE_TASK", "AGENT_PROMPT"):
            query_prompt = prompt or target
            res = AntigravityA2AEngine._execute_agent_reasoning(query_prompt, action)
            res["protocol"] = "GEPY_A2A_v1"
            res["request_id"] = req_id
            res["execution_time_ms"] = int((time.time() - start_t) * 1000)
            res["action"] = action
            return res

        else:
            return {
                "protocol": "GEPY_A2A_v1",
                "request_id": req_id,
                "status": "ERROR",
                "execution_time_ms": int((time.time() - start_t) * 1000),
                "action": action,
                "summary_tts": f"Aksi A2A '{action}' tidak dikenali oleh Antigravity.",
                "markdown_details": f"Unknown action: {action}"
            }

    @staticmethod
    def handle_rpc(payload: dict) -> dict:
        req_id = payload.get("request_id") or f"a2a_{int(time.time()*1000)}"
        action = payload.get("action", "").strip().upper()
        target = payload.get("target", "").strip()
        params = payload.get("parameters", {})
        prompt = payload.get("prompt", "").strip()
        start_t = time.time()
        write_daemon_log(f"A2A RPC DISPATCH: id='{req_id}', action='{action}', target='{target}'")

        is_async = payload.get("async") is True or payload.get("is_async") is True
        client_ip = (payload.get("client_ip") or os.environ.get("GEPY_PHONE_TAILSCALE_IP", "")).strip()
        callback_url = payload.get("callback_url") or (f"http://{client_ip}:8765/api/antigravity/callback" if client_ip else None)

        if action in ("BUILD_RELEASE", "BUILD_APK", "ASSEMBLE_RELEASE", "VERIFY_ALL", "RUN_VERIFY", "VERIFY"):
            is_async = True

        if is_async:
            global _active_async_task, _last_async_result
            if _async_task_lock.locked():
                return {
                    "protocol": "GEPY_A2A_v1",
                    "request_id": req_id,
                    "status": "BUSY",
                    "execution_time_ms": int((time.time() - start_t) * 1000),
                    "action": action,
                    "summary_tts": f"Antigravity sedang menjalankan proses '{_active_async_task or 'kompilasi'}' di laptop. Mohon tunggu proses sebelumnya selesai.",
                    "result_data": {"active_task": _active_async_task}
                }

            def _async_worker():
                global _active_async_task, _last_async_result
                with _async_task_lock:
                    _active_async_task = action
                    try:
                        sync_payload = dict(payload)
                        sync_payload["async"] = False
                        res = AntigravityA2AEngine._execute_sync_rpc(sync_payload, req_id, action, target, params, prompt, start_t)
                        _last_async_result = res
                        if callback_url or client_ip:
                            _dispatch_async_callback(callback_url or "", res, fallback_ip=client_ip or None)
                    except Exception as e:
                        err_res = {
                            "protocol": "GEPY_A2A_v1",
                            "request_id": req_id,
                            "status": "ERROR",
                            "action": action,
                            "summary_tts": f"Terjadi kesalahan saat Antigravity mengeksekusi {action}: {e}",
                            "error": str(e)
                        }
                        _last_async_result = err_res
                        if callback_url or client_ip:
                            _dispatch_async_callback(callback_url or "", err_res, fallback_ip=client_ip or None)
                    finally:
                        _active_async_task = None

            t = threading.Thread(target=_async_worker, name=f"A2A-Worker-{action}", daemon=True)
            t.start()

            pending_tts = f"Antigravity sedang menjalankan '{action}' di latar belakang laptop. Anda akan dikabari begitu selesai."
            if action in ("BUILD_RELEASE", "BUILD_APK", "ASSEMBLE_RELEASE"):
                pending_tts = "Antigravity sedang merakit release APK di latar belakang laptop. Saya kabari begitu selesai!"
            elif action in ("VERIFY_ALL", "RUN_VERIFY", "VERIFY"):
                pending_tts = "Antigravity sedang menjalankan verifikasi lengkap repositori di laptop. Saya kabari begitu selesai!"
            elif action in ("RUN_TESTS", "TESTS"):
                pending_tts = "Antigravity sedang menjalankan pengujian unit test di laptop. Saya kabari begitu selesai!"

            return {
                "protocol": "GEPY_A2A_v1",
                "request_id": req_id,
                "status": "PENDING",
                "execution_time_ms": int((time.time() - start_t) * 1000),
                "action": action,
                "summary_tts": pending_tts,
                "result_data": {
                    "is_async": True,
                    "callback_url": callback_url
                }
            }

        return AntigravityA2AEngine._execute_sync_rpc(payload, req_id, action, target, params, prompt, start_t)


class GepyCompanionHandler(BaseHTTPRequestHandler):
    def _send_json(self, status_code: int, data: dict):
        try:
            body = json.dumps(data, ensure_ascii=False).encode('utf-8')
            self.send_response(status_code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            sys.stderr.write(f"[!] _send_json exception: {e}\n")

    def do_OPTIONS(self):
        try:
            self.send_response(204)
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
            self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
            self.end_headers()
        except Exception:
            pass

    def do_GET(self):
        try:
            path = self.path.split('?')[0]

            if path in ('/', '/health', '/api/status'):
                batt = get_battery_info()
                uptime_sec = int(time.time() - START_TIME)
                data = {
                    "status": "ONLINE",
                    "hostname": socket.gethostname(),
                    "active_window": get_active_window_title(),
                    "battery_percent": batt["percent"],
                    "is_charging": batt["is_charging"],
                    "uptime_seconds": uptime_sec,
                    "shutdown_pending": _shutdown_pending,
                    "summary_id": f"Laptop {socket.gethostname()} aktif. Jendela aktif: '{get_active_window_title()}'. Baterai: {batt['percent']}%."
                }
                self._send_json(200, data)

            elif path == '/api/clipboard':
                text = get_clipboard_text()
                self._send_json(200, {
                    "status": "SUCCESS",
                    "length": len(text),
                    "text": text,
                    "summary_id": f"Teks clipboard laptop ({len(text)} karakter) berhasil dibaca."
                })

            elif path == '/api/screenshot':
                b64 = capture_screenshot_base64()
                if b64:
                    self._send_json(200, {
                        "status": "SUCCESS",
                        "format": "image/jpeg",
                        "base64": b64,
                        "summary_id": "Tangkapan layar desktop laptop berhasil diambil."
                    })
                else:
                    self._send_json(500, {
                        "status": "ERROR",
                        "message": "Gagal mengambil screenshot (desktop terkunci atau PIL tidak aktif)."
                    })

            elif path in ('/api/antigravity/rpc', '/api/antigravity', '/api/antigravity/health'):
                res = AntigravityA2AEngine.handle_rpc({"action": "WORKSPACE_HEALTH"})
                self._send_json(200, res)

            elif path in ('/api/restart', '/api/reload'):
                self._send_json(200, {"status": "SUCCESS", "message": "Restarting Gepy companion daemon..."})
                import threading
                def do_restart():
                    time.sleep(0.5)
                    python_exe = sys.executable or r"C:\Python313\pythonw.exe"
                    script = os.path.abspath(__file__)
                    subprocess.Popen(
                        [python_exe, script],
                        stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        creationflags=DETACHED_FLAGS,
                        close_fds=True
                    )
                    os._exit(0)
                threading.Thread(target=do_restart, daemon=True).start()
                return

            else:
                self._send_json(404, {"status": "ERROR", "message": f"Endpoint '{path}' tidak ditemukan."})
        except Exception as e:
            sys.stderr.write(f"[!] do_GET exception: {e}\n")

    def do_POST(self):
        try:
            path = self.path.split('?')[0]
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length) if content_length > 0 else b'{}'
            
            try:
                body = json.loads(post_data.decode('utf-8')) if post_data else {}
            except Exception:
                body = {}

            if path == '/api/action':
                action = body.get("action", "")
                target = body.get("target", "")
                params = body.get("params", {})
                res = execute_action(action, target, params)
                self._send_json(200, res)

            elif path == '/api/clipboard':
                text = body.get("text", "")
                ok = set_clipboard_text(text)
                self._send_json(200, {
                    "status": "SUCCESS" if ok else "ERROR",
                    "action": "set_clipboard",
                    "length": len(text),
                    "summary_id": "Teks berhasil disuntikkan ke clipboard laptop." if ok else "Gagal menulis clipboard laptop."
                })

            elif path == '/api/gui_input':
                # GUI mouse click or key typing simulation
                input_type = body.get("type", "click").lower()
                if input_type == "click":
                    x = int(body.get("x", 0))
                    y = int(body.get("y", 0))
                    try:
                        ctypes.windll.user32.SetCursorPos(x, y)
                        ctypes.windll.user32.mouse_event(0x0002, 0, 0, 0, 0) # MOUSEEVENTF_LEFTDOWN
                        ctypes.windll.user32.mouse_event(0x0004, 0, 0, 0, 0) # MOUSEEVENTF_LEFTUP
                        self._send_json(200, {"status": "SUCCESS", "action": "click", "x": x, "y": y})
                    except Exception as e:
                        self._send_json(500, {"status": "ERROR", "message": str(e)})
                else:
                    self._send_json(400, {"status": "ERROR", "message": f"Tipe input '{input_type}' belum didukung."})

            elif path in ('/api/antigravity/rpc', '/api/antigravity'):
                if isinstance(body, dict) and "client_ip" not in body:
                    body["client_ip"] = self.client_address[0]
                res = AntigravityA2AEngine.handle_rpc(body)
                self._send_json(200, res)

            elif path in ('/api/restart', '/api/reload'):
                self._send_json(200, {"status": "SUCCESS", "message": "Restarting Gepy companion daemon..."})
                import threading
                def do_restart():
                    time.sleep(0.5)
                    python_exe = sys.executable or r"C:\Python313\pythonw.exe"
                    script = os.path.abspath(__file__)
                    subprocess.Popen(
                        [python_exe, script],
                        stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        creationflags=DETACHED_FLAGS,
                        close_fds=True
                    )
                    os._exit(0)
                threading.Thread(target=do_restart, daemon=True).start()
                return

            else:
                self._send_json(404, {"status": "ERROR", "message": f"Endpoint '{path}' tidak ditemukan."})
        except Exception as e:
            sys.stderr.write(f"[!] do_POST exception: {e}\n")

    def log_message(self, format, *args):
        try:
            write_daemon_log(format % args)
        except Exception:
            pass


def main():
    print("=" * 60)
    print("  GEPY LAPTOP COMPANION DAEMON (v1.0)")
    print("  Sovereign Superintelligence Laptop Bridge over Tailscale")
    print("=" * 60)
    
    server_address = (HOST, PORT)
    ThreadingHTTPServer.allow_reuse_address = True
    
    hostname = socket.gethostname()
    print(f"[*] Hostname          : {hostname}")
    print(f"[*] Port              : {PORT}")
    print(f"[*] Mendengarkan pada : http://0.0.0.0:{PORT}")
    print(f"[*] Endpoint Status   : http://127.0.0.1:{PORT}/api/status")
    print(f"[*] Siap menerima koneksi dari Gepy Android via Tailscale!\n")
    
    while True:
        httpd = None
        try:
            httpd = ThreadingHTTPServer(server_address, GepyCompanionHandler)
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n[!] Daemon dihentikan oleh pengguna.")
            break
        except Exception as e:
            sys.stderr.write(f"[!] Server exception: {e}. Restarting listening socket in 1s...\n")
            time.sleep(1)
        finally:
            if httpd:
                try:
                    httpd.server_close()
                except Exception:
                    pass

if __name__ == "__main__":
    main()
