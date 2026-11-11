#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT"
mvn -B clean -DskipTests package
echo "Built plugin JAR in $ROOT/target/"
