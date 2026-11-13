#requires -Version 5.1
<#
.SYNOPSIS
Copies local test audio into the debug app's INTERNAL files/lab (no DocumentsUI needed).
.EXAMPLE
.\talkrec\tools\push-lab-audio.ps1 -Path C:\audio -Serial emulator-5554 -User 10
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Path,
    [string] $Serial,
    [ValidateRange(0, 2147483647)]
    [int] $User = 10
)

$ErrorActionPreference = 'Stop'
$adbCommand = Get-Command adb -CommandType Application -ErrorAction Stop
$adbExecutable = $adbCommand.Source
$adbPrefix = @()
if ($Serial) { $adbPrefix = @('-s', $Serial) }

# adb shell joins its arguments for a remote POSIX shell. Quote every variable token,
# including spaces, apostrophes, dollar signs and metacharacters in user-chosen names.
function ConvertTo-ShellToken([string] $Value) {
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Invoke-LabAdb([string[]] $AdbArguments, [switch] $ShowOutput) {
    if ($ShowOutput) {
        & $adbExecutable @adbPrefix @AdbArguments
    } else {
        & $adbExecutable @adbPrefix @AdbArguments *> $null
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'adb failed. Check the device/serial, installed DEBUG build (run-as), and Android user.'
    }
}

$inputItem = Get-Item -LiteralPath $Path
$extensions = @('.wav', '.m4a', '.mp3', '.ogg')
if ($inputItem.PSIsContainer) {
    # Only this folder, not subfolders. Preserve each explicit filename in files/lab.
    $audioFiles = @(Get-ChildItem -LiteralPath $inputItem.FullName -File |
        Where-Object { $_.Extension.ToLowerInvariant() -in $extensions } | Sort-Object Name)
} elseif ($inputItem.Extension.ToLowerInvariant() -in $extensions) {
    $audioFiles = @($inputItem)
} else {
    throw 'Supported audio extensions: wav, m4a, mp3, ogg.'
}
if ($audioFiles.Count -eq 0) { throw 'No supported audio files in the selected folder.' }

$runAs = 'run-as com.dongbantest.app --user ' + $User.ToString([Globalization.CultureInfo]::InvariantCulture)
foreach ($audioFile in $audioFiles) {
    $temporaryPath = '/data/local/tmp/' + $audioFile.Name
    $temporaryToken = ConvertTo-ShellToken $temporaryPath
    $destinationToken = ConvertTo-ShellToken ('files/lab/' + $audioFile.Name)
    try {
        Invoke-LabAdb -AdbArguments @('push', $audioFile.FullName, $temporaryPath)
        Invoke-LabAdb -AdbArguments @('shell', ('chmod 644 ' + $temporaryToken))
        Invoke-LabAdb -AdbArguments @('shell', ($runAs + ' mkdir -p files/lab'))
        # No glob: run-as cannot expand /data/local/tmp/* on the Pleos AVD.
        Invoke-LabAdb -AdbArguments @('shell', ($runAs + ' cp ' + $temporaryToken + ' ' + $destinationToken))
    } finally {
        Invoke-LabAdb -AdbArguments @('shell', ('rm -f ' + $temporaryToken))
    }
}
Write-Output ('Copied {0} audio file(s). Internal files/lab:' -f $audioFiles.Count)
Invoke-LabAdb -AdbArguments @('shell', ($runAs + ' ls -lh files/lab')) -ShowOutput
