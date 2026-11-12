$ErrorActionPreference = 'Stop'

$localTools = Join-Path $PSScriptRoot '.local'
$python = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
$uv = Join-Path $PSScriptRoot '../dev-tools/.local/uv/uv.exe'
[void](New-Item -ItemType Directory -Force -Path $localTools)

if (-not (Test-Path -LiteralPath $uv)) {
    $destination = Join-Path $localTools 'uv'
    $uv = Join-Path $destination 'uv.exe'
    if (-not (Test-Path -LiteralPath $uv)) {
        $archive = Join-Path $localTools 'uv.zip'
        & curl.exe --fail --silent --show-error --location --retry 2 -o $archive `
            'https://github.com/astral-sh/uv/releases/download/0.13.7/uv-x86_64-pc-windows-msvc.zip'
        if ($LASTEXITCODE -ne 0) { throw 'uv download failed' }
        Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
        Remove-Item -LiteralPath $archive
        if (-not (Test-Path -LiteralPath $uv)) { throw 'uv.exe not found' }
    }
}

# Keep Python, caches, and the environment inside this project, even in a fresh shell.
$settings = @{
    UV_PYTHON_INSTALL_DIR = (Join-Path $localTools 'python')
    UV_CACHE_DIR = (Join-Path $localTools 'uv-cache')
    UV_PYTHON_PREFERENCE = 'only-managed'
    UV_PROJECT_ENVIRONMENT = (Join-Path $PSScriptRoot '.venv')
    UV_LINK_MODE = 'copy'
}
$previous = @{}
try {
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
    & $uv sync --project $PSScriptRoot --python 3.12 --all-groups --locked
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
    & $uv pip check --python $python
    if ($LASTEXITCODE -ne 0) { throw 'Dependency compatibility check failed' }
    & $python -W error (Join-Path $PSScriptRoot 'tools/check_env.py')
    if ($LASTEXITCODE -ne 0) { throw 'GPU environment check failed' }
}
finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
