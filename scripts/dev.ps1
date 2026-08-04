$ErrorActionPreference = "Stop"

$ProjectDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$MavenWrapper = Join-Path $ProjectDir "mvnw.cmd"

if (-not (Test-Path $MavenWrapper)) {
    throw "Maven Wrapper is missing: $MavenWrapper"
}

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm is required for hot development"
}

$backend = Start-Process -FilePath $MavenWrapper `
    -ArgumentList "spring-boot:run" `
    -WorkingDirectory $ProjectDir `
    -PassThru `
    -NoNewWindow

try {
    $lockFile = Join-Path $ProjectDir "frontend/package-lock.json"
    if (Test-Path $lockFile) {
        & npm --prefix (Join-Path $ProjectDir "frontend") ci
    } else {
        & npm --prefix (Join-Path $ProjectDir "frontend") install
    }
    & npm --prefix (Join-Path $ProjectDir "frontend") run dev
} finally {
    if (-not $backend.HasExited) {
        Stop-Process -Id $backend.Id
    }
}
