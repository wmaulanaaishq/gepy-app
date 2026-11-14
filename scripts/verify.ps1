# Skrip Verifikasi Kualitas Kode & Pengujian Pre-Commit Gepy Native (Kotlin + Jetpack Compose)
# Memastikan unit test JVM, Android Lint, dan kompilasi APK Debug lulus 100%.

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    $jbrPath = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbrPath) {
        $env:JAVA_HOME = $jbrPath
        $env:Path = "$jbrPath\bin;$env:Path"
    }
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  GEPY NATIVE PRE-COMMIT VERIFICATION   " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Pengujian Unit JVM (testDebugUnitTest)
Write-Host "`n[1/3] Menjalankan pengujian unit JVM (./gradlew testDebugUnitTest)..." -ForegroundColor Yellow
& .\gradlew.bat testDebugUnitTest --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "X Satu atau lebih pengujian unit gagal." -ForegroundColor Red
    exit 1
}
Write-Host "v Seluruh pengujian unit JVM lulus 100%!" -ForegroundColor Green

# 2. Analisis Statis Android Lint (lintDebug)
Write-Host "`n[2/3] Menjalankan analisis statis Android Lint (./gradlew lintDebug)..." -ForegroundColor Yellow
& .\gradlew.bat lintDebug --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "X Analisis statis Android Lint menemukan error." -ForegroundColor Red
    exit 1
}
Write-Host "v Android Lint bersih (0 Error)." -ForegroundColor Green

# 3. Kompilasi APK Debug (assembleDebug)
Write-Host "`n[3/3] Merakit APK Debug (./gradlew assembleDebug)..." -ForegroundColor Yellow
& .\gradlew.bat assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "X Kompilasi APK Debug gagal." -ForegroundColor Red
    exit 1
}
Write-Host "v Kompilasi APK Debug sukses!" -ForegroundColor Green

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  SEMUA VERIFIKASI LULUS (SIAP COMMIT)  " -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
