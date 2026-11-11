$ErrorActionPreference = 'Stop'

$localTools = Join-Path $PSScriptRoot '.local'
$python = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
[void](New-Item -ItemType Directory -Force -Path $localTools)

function Install-PortableTool($Name, $Url) {
    $destination = Join-Path $localTools $Name
    $existing = Get-ChildItem -LiteralPath $destination -Filter "$Name.exe" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($existing) { return $existing.FullName }

    $archive = Join-Path $localTools "$Name.zip"
    & curl.exe --fail --silent --show-error --location --retry 2 -o $archive $Url
    if ($LASTEXITCODE -ne 0) { throw "$Name download failed" }
    Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
    Remove-Item -LiteralPath $archive
    $executable = Get-ChildItem -LiteralPath $destination -Filter "$Name.exe" -Recurse | Select-Object -First 1
    if (-not $executable) { throw "$Name.exe not found in $destination" }
    return $executable.FullName
}

$uv = Install-PortableTool 'uv' 'https://github.com/astral-sh/uv/releases/download/0.13.7/uv-x86_64-pc-windows-msvc.zip'
$deno = Install-PortableTool 'deno' 'https://github.com/denoland/deno/releases/download/v2.9.8/deno-x86_64-pc-windows-msvc.zip'
$env:UV_PYTHON_INSTALL_DIR = Join-Path $localTools 'python'
$env:UV_CACHE_DIR = Join-Path $localTools 'uv-cache'
$env:UV_PYTHON_PREFERENCE = 'only-managed'

if (-not (Test-Path -LiteralPath $python)) {
    & $uv venv --python 3.12 (Join-Path $PSScriptRoot '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Python environment setup failed' }
}

& $uv pip install --python $python -r (Join-Path $PSScriptRoot 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
& $python -m yt_dlp --version
if ($LASTEXITCODE -ne 0) { throw 'yt-dlp startup failed' }
& $deno --version
if ($LASTEXITCODE -ne 0) { throw 'Deno startup failed' }
