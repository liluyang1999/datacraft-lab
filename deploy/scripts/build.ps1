# Builds the datacraft-cli jar on Windows (no Docker required).
# Usage:  pwsh -File deploy/scripts/build.ps1   (or run from PowerShell)
$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) {
    Write-Host '[datacraft] Tip: set $env:JAVA_HOME to a JDK 25+ install for a clean build.'
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Push-Location $repoRoot
try {
    # Same order as build-jar.sh: Maven on PATH, else the bundled wrapper, else a clear error.
    $mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' } else { Join-Path $repoRoot 'mvnw.cmd' }
    if (-not (Get-Command $mvn -ErrorAction SilentlyContinue)) {
        throw 'Maven not found. Install Maven 3.9+ or use the bundled mvnw.cmd.'
    }
    Write-Host "[datacraft] Building CLI jar with $mvn..."
    & $mvn -B -ntp -pl datacraft-cli -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
    Write-Host '[datacraft] Built: datacraft-cli/target/datacraft-cli.jar'
}
finally {
    Pop-Location
}
