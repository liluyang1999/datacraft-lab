# Builds the datacraft-cli jar on Windows (no Docker required).
# Usage:  pwsh -File deploy/scripts/build.ps1   (or run from PowerShell)
$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) {
    Write-Host '[datacraft] Tip: set $env:JAVA_HOME to a JDK 25+ install for a clean build.'
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Push-Location $repoRoot
try {
    # Same order as build-jar.sh: the pinned, checksummed wrapper, else Maven on PATH.
    $wrapper = Join-Path $repoRoot 'mvnw.cmd'
    $mvn = if (Test-Path $wrapper) { $wrapper } elseif (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' } else { $null }
    if (-not $mvn) {
        throw 'Maven not found: restore mvnw.cmd or install Maven 3.9+.'
    }
    Write-Host "[datacraft] Building CLI jar with $mvn..."
    & $mvn -B -ntp -pl :datacraft-cli -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
    Write-Host '[datacraft] Built: modules/interfaces/datacraft-cli/target/datacraft-cli.jar'
}
finally {
    Pop-Location
}
