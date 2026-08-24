# Builds the datacraft-cli jar on Windows (no Docker required).
# Usage:  pwsh -File deploy/scripts/build.ps1   (or run from PowerShell)
$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) {
    Write-Host '[datacraft] Tip: set $env:JAVA_HOME to a JDK 25+ install for a clean build.'
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Push-Location $repoRoot
try {
    Write-Host '[datacraft] Building CLI jar with Maven...'
    & mvn -B -ntp -pl datacraft-cli -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
    Write-Host '[datacraft] Built: datacraft-cli/target/datacraft-cli.jar'
}
finally {
    Pop-Location
}
