#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT"
mvn -B clean -DskipTests package
echo "Built $ROOT/target/genecraft-paper-0.2.0.jar"
