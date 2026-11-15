# Starts the Pleos emulator with the host microphone turned on, and checks the emulator audio input.
# Usage: powershell -File tools/emulator/start-pleos.ps1 [-Avd <name>] [-CheckMic]
param(
    [string]$Avd = 'Pleos_Connect_v2.1.0',
    [switch]$CheckMic
)

$ErrorActionPreference = 'Stop'

function Find-SdkRoot {
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $props = Join-Path $repoRoot 'local.properties'
    if (Test-Path $props) {
        foreach ($line in Get-Content $props) {
            if ($line -match '^\s*sdk\.dir\s*=(.*)$') {
                $v = $Matches[1].Trim()
                $v = $v.Replace('\:', ':').Replace('\\', '\')
                if ($v) { return $v }
            }
        }
    }
    return (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
}

$sdk = Find-SdkRoot
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
if (-not (Test-Path $adb)) { Write-Host "adb not found at: $adb"; exit 2 }
if (-not (Test-Path $emulator)) { Write-Host "emulator not found at: $emulator"; exit 2 }

function Test-Mic {
    $lines = & $adb logcat -d -t 2000 2>$null
    $count = 0
    foreach ($l in $lines) {
        if ($l -like '*pcm_read failed*') { $count++ }
    }
    if ($count -eq 0) {
        Write-Host 'mic input OK'
        return 0
    }
    Write-Host "pcm_read failed lines: $count"
    Write-Host "the emulator's audio input has failed - restart the emulator (cold boot)"
    return 1
}

if ($CheckMic) {
    exit (Test-Mic)
}

$devices = & $adb devices
$running = $false
foreach ($l in $devices) {
    if ($l -match '^emulator-\d+\s+device') { $running = $true }
}

if ($running) {
    Write-Host 'An emulator is already running; not starting another one.'
} else {
    Write-Host "Starting emulator: $Avd"
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $Avd) | Out-Null
}

$deadline = (Get-Date).AddMinutes(5)
$booted = $false
while ((Get-Date) -lt $deadline) {
    $v = & $adb shell getprop sys.boot_completed 2>$null
    if ($v -and ($v.ToString().Trim() -eq '1')) { $booted = $true; break }
    Start-Sleep -Seconds 3
}
if (-not $booted) {
    Write-Host 'Gave up: the emulator did not finish booting within 5 minutes.'
    exit 3
}

Write-Host 'Boot completed. Turning host microphone on...'
$r = & $adb emu avd hostmicon 2>&1
Write-Host ($r | Out-String)

Start-Sleep -Seconds 5
exit (Test-Mic)
