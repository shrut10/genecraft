$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
mvn --batch-mode clean -DskipTests package
Write-Host "Built $Root/target/genecraft-paper-0.2.0.jar"
