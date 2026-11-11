$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
mvn --batch-mode clean -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Plugin build failed (exit $LASTEXITCODE)." }
Write-Host "Built plugin JAR in $Root/target/"
