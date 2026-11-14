# Skrip Otomasi Evaluasi AI SOTA & Quality Gate Gepy Native (Kotlin + Python)
# Menguji Otonomi, Integritas Tool, Groundedness Memori, dan Persona Samantha + F.R.I.D.A.Y.

param (
    [ValidateSet("Quick", "Full", "Persona", "Safety")]
    [string]$Mode = "Quick"
)

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    $jbrPath = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbrPath) {
        $env:JAVA_HOME = $jbrPath
        $env:Path = "$jbrPath\bin;$env:Path"
    }
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GEPY SOTA AI EVALUATION SUITE ($Mode)   " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Jalankan Deterministic JVM Quality Gates
Write-Host "`n[1/2] Menjalankan Unit Test Evaluasi JVM (Gradle)..." -ForegroundColor Yellow
& .\gradlew.bat testDebugUnitTest --tests "com.teknologigawai.gepy.evals.*" --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "X Evaluasi JVM gagal." -ForegroundColor Red
    exit 1
}
Write-Host "v Seluruh evaluasi JVM lulus 100%!" -ForegroundColor Green

# 2. Jalankan Python Evaluation Runner
Write-Host "`n[2/2] Menjalankan Mesin Evaluasi Python (scripts/evals/runner.py --mode $($Mode.ToLower()))..." -ForegroundColor Yellow
$pyMode = $Mode.ToLower()
python .\scripts\evals\runner.py --mode $pyMode
if ($LASTEXITCODE -ne 0) {
    Write-Host "X Evaluasi AI Runner mendeteksi kegagalan." -ForegroundColor Red
    exit 1
}

Write-Host "`n==========================================" -ForegroundColor Cyan
Write-Host "  EVALUASI SUKSES (SUPERINTELLIGENCE READY)" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
