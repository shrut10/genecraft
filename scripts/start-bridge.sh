#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT"
if [ ! -x .venv/bin/python ]; then
  echo "Run scripts/setup-bridge.sh first."
  exit 1
fi
exec .venv/bin/python bridge/genecraft_bridge.py
