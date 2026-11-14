# Skrip Otomatisasi Rilis & Pemangkasan Artefak (dist/) - Gepy Native
# Membaca versionName dari app/build.gradle.kts, menjalankan verify.ps1 (opsional),
# merakit APK/AAB Debug & Release, memindahkan rilis sebelumnya ke dist/archive/,
# dan memangkas arsip lama agar ruang disk tidak membengkak.

[CmdletBinding()]
param(
    [switch]$SkipVerify,
    [int]$KeepArchiveVersions = 1
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not $env:JAVA_HOME) {
    $jbrPath = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbrPath) {
        $env:JAVA_HOME = $jbrPath
        $env:Path = "$jbrPath\bin;$env:Path"
    }
}

$gradleContent = Get-Content "app\build.gradle.kts" -Raw
if ($gradleContent -match 'versionName\s*=\s*"([^"]+)"') {
    $version = $Matches[1]
} else {
    Write-Error "Gagal membaca versionName dari app\build.gradle.kts"
    exit 1
}

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "  GEPY NATIVE RELEASE BUILDER - v$version" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not $SkipVerify) {
    & "$PSScriptRoot\verify.ps1"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
    Write-Host "`n[1/2] Merakit APK Debug..." -ForegroundColor Yellow
    & .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "`n[Release] Merakit APK Release & AAB Bundle..." -ForegroundColor Yellow
& .\gradlew.bat assembleRelease bundleRelease --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$distDir = Join-Path $repoRoot "dist"
$archiveDir = Join-Path $distDir "archive"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null

# Pindahkan versi lama selain v$version ke dist/archive/
Get-ChildItem $distDir -File -Filter "Gepy-Native-v*" | Where-Object {
    $_.Name -notlike "Gepy-Native-v$version-*"
} | ForEach-Object {
    Move-Item -Force $_.FullName (Join-Path $archiveDir $_.Name)
}

# Salin artefak terbaru v$version
Copy-Item -Force "app\build\outputs\apk\debug\app-debug.apk" (Join-Path $distDir "Gepy-Native-v$version-with-API-debug.apk")
Copy-Item -Force "app\build\outputs\apk\release\app-release.apk" (Join-Path $distDir "Gepy-Native-v$version-with-API-release.apk")
Copy-Item -Force "app\build\outputs\bundle\release\app-release.aab" (Join-Path $distDir "Gepy-Native-v$version-with-API-release.aab")

Copy-Item -Force "app\build\outputs\apk\debug\app-debug.apk" (Join-Path $distDir "app-debug.apk")
Copy-Item -Force "app\build\outputs\apk\release\app-release.apk" (Join-Path $distDir "app-release.apk")
Copy-Item -Force "app\build\outputs\bundle\release\app-release.aab" (Join-Path $distDir "app-release.aab")

# Pangkas dist/archive/ agar hanya menyimpan $KeepArchiveVersions versi mundur terbaru
$archivedGroups = Get-ChildItem $archiveDir -File -Filter "Gepy-Native-v*" |
    Group-Object {
        if ($_.Name -match '^Gepy-Native-(v[0-9]+\.[0-9]+\.[0-9]+)') { $Matches[1] } else { "other" }
    } |
    Sort-Object { ($_.Group | Measure-Object LastWriteTime -Maximum).Maximum } -Descending

if ($archivedGroups.Count -gt $KeepArchiveVersions) {
    $archivedGroups | Select-Object -Skip $KeepArchiveVersions | ForEach-Object {
        $_.Group | Remove-Item -Force
    }
}

$totalDistMb = [math]::Round(((Get-ChildItem $distDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Host "`nv Paket rilis v$version siap di dist/ (Total ukuran dist/ + archive/: $totalDistMb MB)" -ForegroundColor Green
Get-ChildItem $distDir -File | Select-Object Name, @{N="SizeMB";E={[math]::Round($_.Length/1MB, 2)}} | Format-Table -AutoSize
