$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
python -m venv .venv
& .venv\Scripts\python.exe -m pip install -r bridge\requirements.txt
Write-Host "GeneCraft bridge installed. Start it with: $Root\scripts\start-bridge.ps1"
