#!/usr/bin/env bash
# Synthetic DNG colour checks through the real shader pipeline (needs the golden binary: tools/golden/run-golden.sh or build.sh builds /tmp/golden/golden).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
GOLDEN_BIN="${GOLDEN_BIN:-/tmp/golden/golden}" python3 "$ROOT/tools/colour/run_fixtures.py" "$@"
