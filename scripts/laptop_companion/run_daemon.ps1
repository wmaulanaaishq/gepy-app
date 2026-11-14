param(
    [switch]$Foreground
)

# Skrip Peluncur Gepy Laptop Companion Daemon
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  GEPY LAPTOP COMPANION DAEMON LAUNCHER " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$daemonScript = Join-Path $scriptDir "gepy_laptop_daemon.py"

# Cek IP Tailscale
try {
    $tsIp = (tailscale ip -4).Trim()
    Write-Host "[v] Tailscale IPv4: $tsIp" -ForegroundColor Green
    Write-Host "[v] Gepy Android dapat menghubungi: http://$($tsIp):8888" -ForegroundColor Green
} catch {
    Write-Host "[!] Peringatan: Tailscale belum terdeteksi aktif." -ForegroundColor Yellow
}

# Cek apakah daemon sudah berjalan di port 8888
$conn = Get-NetTCPConnection -LocalPort 8888 -State Listen -ErrorAction SilentlyContinue
if ($conn) {
    Write-Host "[v] Daemon sudah aktif berjalan di port 8888." -ForegroundColor Green
    exit 0
}

if ($Foreground) {
    Write-Host "`n[*] Menjalankan Daemon di foreground (Ctrl+C untuk berhenti)..." -ForegroundColor Yellow
    python $daemonScript
} else {
    Write-Host "`n[*] Menjalankan Daemon di latar belakang (pythonw)..." -ForegroundColor Yellow
    Start-Process -FilePath "pythonw.exe" -ArgumentList "`"$daemonScript`"" -WorkingDirectory $scriptDir
    Start-Sleep -Seconds 1
    $status = curl.exe -s http://127.0.0.1:8888/api/status
    if ($status -like "*ONLINE*") {
        Write-Host "[v] Daemon berhasil berjalan di latar belakang (ONLINE)." -ForegroundColor Green
    } else {
        Write-Host "[!] Gagal memeriksa status daemon di http://127.0.0.1:8888/api/status" -ForegroundColor Yellow
    }
}
