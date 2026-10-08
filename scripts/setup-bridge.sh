#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT"
python3 -m venv .venv
.venv/bin/python -m pip install -r bridge/requirements.txt
echo "GeneCraft bridge installed. Start it with: $ROOT/scripts/start-bridge.sh"
