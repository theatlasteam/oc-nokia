# OpenCode x Nokia 6300 — one-shot setup for Windows.
# Installs: bun, ffmpeg, JDK 8 (optional, for building the MIDlet),
# ProGuard (preverify), JS dependencies. Idempotent.
#
# Run:  powershell -ExecutionPolicy Bypass -File setup.ps1
$ErrorActionPreference = "Continue"

function Say($msg) { Write-Host "== $msg" -ForegroundColor Cyan }
function Warn($msg) { Write-Host "warn: $msg" -ForegroundColor Yellow }

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------- 1. bun ----------
if (Get-Command bun -ErrorAction SilentlyContinue) {
    Say "bun: $(bun --version)"
} elseif (Get-Command winget -ErrorAction SilentlyContinue) {
    Say "installing bun (winget)"
    winget install --id Oven-sh.Bun -e --accept-source-agreements --accept-package-agreements
    # refresh PATH for this session
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [System.Environment]::GetEnvironmentVariable("Path", "User")
} else {
    Warn "winget not found. Install bun manually: https://bun.sh/docs/installation"
    exit 1
}
if (-not (Get-Command bun -ErrorAction SilentlyContinue)) {
    Warn "bun not on PATH yet. Open a NEW terminal and re-run setup.ps1."
    exit 1
}

# ---------- 2. ffmpeg ----------
if (Get-Command ffmpeg -ErrorAction SilentlyContinue) {
    Say "ffmpeg: ok"
} elseif (Get-Command winget -ErrorAction SilentlyContinue) {
    Say "installing ffmpeg (winget)"
    winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements
    $env:Path += ";$env:LOCALAPPDATA\Microsoft\WinGet\Links"
} else {
    Warn "ffmpeg not found. Install manually: https://ffmpeg.org/download.html"
}

# ---------- 3. JDK 8 (optional, only to BUILD the phone app) ----------
$Jdk8 = $null
foreach ($c in @(
    $env:JAVA_HOME_8_X64,
    "C:\Program Files\Java\jdk1.8*",
    "C:\Program Files\Eclipse Adoptium\jdk-8*",
    "C:\Program Files\Microsoft\jdk-8*"
)) {
    if ($c -and (Test-Path "$c\bin\javac.exe")) { $Jdk8 = (Get-Item $c).FullName; break }
}
if ($Jdk8) {
    Say "JDK 8: $Jdk8"
} else {
    Warn "JDK 8 not found — only needed to rebuild the MIDlet (prebuilt jar included)."
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Say "installing Temurin 8 (winget)"
        winget install --id EclipseAdoptium.Temurin.8.JDK -e --accept-source-agreements --accept-package-agreements
    }
}

if ($Jdk8 -or (Get-Command javac -ErrorAction SilentlyContinue)) {
    $PgDir = "$env:USERPROFILE\.cache\oc-nokia\proguard-7.4.2"
    if (Test-Path "$PgDir\lib\proguard.jar") {
        Say "ProGuard: $PgDir"
    } else {
        Say "downloading ProGuard 7.4.2 (preverify)"
        New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.cache\oc-nokia" | Out-Null
        $zip = "$env:TEMP\oc-nokia-proguard.zip"
        Invoke-WebRequest -Uri "https://github.com/Guardsquare/proguard/releases/download/v7.4.2/proguard-7.4.2.zip" -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath "$env:USERPROFILE\.cache\oc-nokia" -Force
        Remove-Item $zip
    }
}

# ---------- 4. JS deps ----------
Say "installing plugin JS deps (bun)"
Push-Location "$Here\opencode-nokia-plugin"
bun install
# Native BT module: failure is non-fatal (TCP :19741 fallback).
npm install bluetooth-serial-port --no-save
if ($LASTEXITCODE -ne 0) {
    Warn "bluetooth-serial-port native build failed - bridge will use TCP :19741 fallback."
    Warn "To retry later: install VS Build Tools (C++ workload), then npm install bluetooth-serial-port"
}
Pop-Location

# ---------- 5. hints ----------
Say "setup complete"
Write-Host @'
Next steps:
  1. setx GROQ_API_KEY gsk_...        (console.groq.com; open a NEW terminal after)
  2. run:  bun opencode-nokia-plugin/standalone-bridge.mjs
     then: node scripts/bt-proxy.js         (bridge on TCP :19741)
     dashboard: http://127.0.0.1:4097/nokia
  3. On the 6300: install NokiaOpenCode\bin\NokiaOpenCode.jad,
     then OpenCode Remote -> Connect.
Windows note: the phone dials the PC over Bluetooth SPP if the native
module built; otherwise use the TCP fallback (set the TcpHost JAD
property to this PC's IP, phone + PC on the same network/PAN).
'@
