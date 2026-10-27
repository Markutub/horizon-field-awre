#!/usr/bin/env bash
# Runs the two real Gradle builds on YOUR machine and writes ONE text file to send back (compile errors, resolved versions, log tails).
# This is the feedback loop for everything the offline sandbox cannot do: real Paper/Fabric compile, Loom/Paper API pinning.
# Usage:   bash tools/feedback.sh [outfile]          (default: feedback-<timestamp>.txt in the repo root)
# Env:     GRADLE_CMD   command used to run Gradle (default "bash gradlew"; e.g. "gradle" or "gradlew.bat" on Windows/Git Bash)
#          SKIP_CHECK=1 skip the offline `tools/check.sh --quick` section
# Exit:    0 if both builds succeeded, 1 otherwise. The file is written either way.
set -uo pipefail
cd "$(dirname "$0")/.."

GRADLE_CMD="${GRADLE_CMD:-bash gradlew}"
OUT="${1:-feedback-$(date +%Y%m%d-%H%M%S).txt}"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

sec()  { printf '\n===== %s =====\n' "$1"; }
errs() {  # first compile-error blocks and Gradle's own failure explanation from a log
  grep -n -m3 -A6 'error:' "$1" || true
  grep -n -m1 -A8 'What went wrong' "$1" || true
}
tails() { tail -n 30 "$1"; }

# shellcheck disable=SC2086
$GRADLE_CMD build -PjavaRelease=21 --stacktrace >"$W/root.log" 2>&1;            ROOT_RC=$?
# shellcheck disable=SC2086
$GRADLE_CMD -p awre-fabric build --stacktrace >"$W/fabric.log" 2>&1;            FABRIC_RC=$?
# shellcheck disable=SC2086
$GRADLE_CMD :awre-paper:dependencies --configuration compileClasspath -PjavaRelease=21 >"$W/deps.log" 2>&1; DEPS_RC=$?

{
  echo "AWRE feedback bundle  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  sec "RESULT"
  echo "root build   (build -PjavaRelease=21)  : $([ $ROOT_RC -eq 0 ] && echo OK || echo "FAILED rc=$ROOT_RC")"
  echo "fabric build (-p awre-fabric build)     : $([ $FABRIC_RC -eq 0 ] && echo OK || echo "FAILED rc=$FABRIC_RC")"

  sec "ENVIRONMENT"
  echo "git HEAD: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "uncommitted files: $(git status --short 2>/dev/null | wc -l | tr -d ' ')"
  java -version 2>&1 | head -3
  echo "gradle command: $GRADLE_CMD"

  sec "VERSIONS TO PIN (raw lines, nothing guessed)"
  echo "-- Loom line from the fabric build log:"
  grep -i -m3 'loom' "$W/fabric.log" || echo "(no line containing 'loom' found)"
  echo "-- Paper API as resolved by :awre-paper compileClasspath (rc=$DEPS_RC):"
  grep -i 'paper-api' "$W/deps.log" | sort -u | head -5 || true
  grep -qi 'paper-api' "$W/deps.log" || echo "(no paper-api line found)"

  sec "ROOT BUILD: FIRST ERRORS"
  errs "$W/root.log"
  sec "FABRIC BUILD: FIRST ERRORS"
  errs "$W/fabric.log"

  sec "ROOT BUILD: LAST 30 LINES"
  tails "$W/root.log"
  sec "FABRIC BUILD: LAST 30 LINES"
  tails "$W/fabric.log"

  if [ "${SKIP_CHECK:-0}" != "1" ]; then
    sec "OFFLINE CHECKS (tools/check.sh --quick)"
    bash tools/check.sh --quick 2>&1 | tail -n 15
  fi
} >"$OUT" 2>&1

echo "wrote $OUT"
grep -A2 '^===== RESULT' "$OUT" | tail -n 2
[ $ROOT_RC -eq 0 ] && [ $FABRIC_RC -eq 0 ]
