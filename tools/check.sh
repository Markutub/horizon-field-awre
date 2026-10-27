#!/usr/bin/env bash
# One-command offline check: everything that can run with only a JDK (no Gradle, no network).
# Mirrors the `verify` job in .github/workflows/ci.yml, plus a syntax pass over Paper/Fabric sources.
# It does NOT compile Paper/Fabric against their real APIs and proves nothing about in-game rendering.
# Usage: bash tools/check.sh [--quick]   (--quick skips the reference-frame preview renders)
set -uo pipefail
cd "$(dirname "$0")/.."

QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

command -v java >/dev/null || { echo "java not found (JDK 21+ required)"; exit 2; }

PASS=(); FAIL=()
run() {
  local name="$1"; shift
  local log; log="$(mktemp)"
  if "$@" >"$log" 2>&1; then
    PASS+=("$name"); printf '  PASS  %s  %s\n' "$name" "$(grep -E '^passed=' "$log" | tail -1)"
  else
    FAIL+=("$name"); printf '  FAIL  %s\n' "$name"; tail -15 "$log" | sed 's/^/        /'
  fi
  rm -f "$log"
}

echo "== unit tests (real test sources against tools/junit-shim)"
run verify-core    bash tools/verify-core.sh
run verify-awre    bash tools/verify-awre.sh
run verify-stream  bash tools/verify-stream.sh

echo "== CI script tests"
run render-gate    bash tools/ci/render-gate-test.sh
run frame-check    bash tools/ci/frame-check-test.sh
run compat-lib     bash tools/ci/compat-lib-test.sh
run log-scan       bash tools/ci/log-scan-test.sh
run feedback       bash tools/feedback-test.sh
run report-check   bash tools/ci/report-check-test.sh
run pixel-check    bash tools/ci/pixel-check-test.sh
run api-dump-regex bash tools/ci/api-dump-regex-test.sh

echo "== syntax only (NOT a compile against Paper/Fabric APIs)"
run parse-only     bash -c 'java tools/ParseOnly.java awre-paper/src awre-fabric/src horizon-paper/src | tee /dev/stderr | grep -q "syntax errors=0"'

if [ "$QUICK" -eq 0 ]; then
  echo "== CPU previews (geometry/reveal self-checks; not in-game frames)"
  OUT="$(mktemp -d)"
  run reference-frame bash tools/preview/run-reference-frame.sh "$OUT/ref"
  run reveal-preview  bash tools/preview/run-reveal-preview.sh "$OUT/reveal"
  rm -rf "$OUT"
fi

echo
echo "passed: ${#PASS[@]}   failed: ${#FAIL[@]}"
[ "${#FAIL[@]}" -eq 0 ] || { echo "failed: ${FAIL[*]}"; exit 1; }
