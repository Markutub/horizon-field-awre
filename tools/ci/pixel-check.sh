#!/usr/bin/env bash
# Pixel proof that AWRE terrain is visible, from REAL framebuffer captures of ONE running client (docs/NEXT_PHASE_PROMPT.md task 1).
#
# Method: the client runs non-headless under Xvfb + Mesa software GL (tools/ci/integration.sh sets that up). After the horizon has
# converged and the reveal effect has settled, this script captures the display three times from outside the game (no Minecraft screenshot API):
#   awre.png     AWRE terrain drawn
#   control.png  after creating $PAUSE_FILE: the mod (-Dawre.test.pauseFile, see RenderPause) stops drawing AWRE terrain
#   control2.png a second control a moment later (measures scene noise)
# Same session, seconds apart: time of day, camera and weather cannot differ. tools/ci/FrameCheck.java then decides, and a NEGATIVE CONTROL
# (control2 vs control must NOT look like "AWRE visible") shows that the check can fail. NOTE: with FrameCheck's default thresholds this is
# implied by the noise guard (a stable scene cannot differ by >= min-diff); it is kept as the literal "AWRE off => the check fails" evidence in
# the verdict file, and it becomes a real gate if someone loosens --max-noise above --min-diff.
#
# Usage: tools/ci/pixel-check.sh <client.log> <out-dir>
# Env:   PAUSE_FILE (required)   CAPTURE_CMD (default "import -window root"; called as: $CAPTURE_CMD <png>, uses $DISPLAY)
#        SETTLE=45 (s after convergence)  PAUSE_SETTLE=8  CONVERGE_TIMEOUT=900  PAUSE_TIMEOUT=90  POLL=5
#        FRAMECHECK_ARGS (extra FrameCheck options, e.g. "--band 0.35,0.70 --min-diff 0.05")
# Exit:  0 PASS (AWRE visible)   1 FAIL (AWRE not visible, scene unstable, or the check cannot fail)   2 INCONCLUSIVE (infrastructure: never
#        converged, capture failed, the pause hook never fired). 2 is never evidence about AWRE.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="${1:?usage: pixel-check.sh <client.log> <out-dir>}"; OUT="${2:?usage: pixel-check.sh <client.log> <out-dir>}"
: "${PAUSE_FILE:?PAUSE_FILE must be set (the same path as -Dawre.test.pauseFile)}"
CAPTURE_CMD="${CAPTURE_CMD:-import -window root}"
SETTLE="${SETTLE:-45}"; PAUSE_SETTLE="${PAUSE_SETTLE:-8}"; CONVERGE_TIMEOUT="${CONVERGE_TIMEOUT:-900}"; PAUSE_TIMEOUT="${PAUSE_TIMEOUT:-90}"; POLL="${POLL:-5}"
mkdir -p "$OUT"
say() { echo "PIXEL $*" | tee -a "$OUT/pixel-verdict.txt"; }
: > "$OUT/pixel-verdict.txt"
inconclusive() { say "INCONCLUSIVE $*"; rm -f "$PAUSE_FILE"; exit 2; }

elapsed_wait() { # elapsed_wait <timeout-seconds> <command...> : poll until the command succeeds
  local limit=$1; shift; local start=$SECONDS
  while ! "$@" >/dev/null 2>&1; do [ $((SECONDS - start)) -ge "$limit" ] && return 1; sleep "$POLL"; done
}
capture() { # capture <name>
  # shellcheck disable=SC2086
  $CAPTURE_CMD "$OUT/$1.png" >"$OUT/capture-$1.log" 2>&1 || inconclusive "capture command failed for $1 (see capture-$1.log): $CAPTURE_CMD"
  [ -s "$OUT/$1.png" ] || inconclusive "capture command produced no file for $1"
}

rm -f "$PAUSE_FILE"
elapsed_wait "$CONVERGE_TIMEOUT" bash "$HERE/log-scan.sh" converged "$LOG" || inconclusive "the horizon never converged within ${CONVERGE_TIMEOUT}s: $(bash "$HERE/log-scan.sh" converged "$LOG" 2>&1 | head -1)"
say "converged; settling ${SETTLE}s for the reveal effect"
sleep "$SETTLE"
capture awre

: > "$PAUSE_FILE"
elapsed_wait "$PAUSE_TIMEOUT" grep -q '\[AWRE\] test render pause ON' "$LOG" || inconclusive "no '[AWRE] test render pause ON' within ${PAUSE_TIMEOUT}s: the client did not honour -Dawre.test.pauseFile (wrong path, or the render event never ran)"
sleep "$PAUSE_SETTLE"
capture control
sleep 2
capture control2
rm -f "$PAUSE_FILE"

# shellcheck disable=SC2086
POS=$(java "$HERE/FrameCheck.java" --awre "$OUT/awre.png" --control "$OUT/control.png" --noise "$OUT/control2.png" ${FRAMECHECK_ARGS:-} 2>&1); RC=$?
say "$POS"
[ "$RC" -eq 2 ] && inconclusive "FrameCheck could not compare the captures (unreadable image or size mismatch)"
# shellcheck disable=SC2086
NEG=$(java "$HERE/FrameCheck.java" --awre "$OUT/control2.png" --control "$OUT/control.png" ${FRAMECHECK_ARGS:-} 2>&1); NRC=$?
say "negative control (control2 vs control; must FAIL): $NEG"
if [ "$NRC" -eq 0 ]; then say "FAIL the negative control passed: two frames WITHOUT AWRE look 'different enough', so this check cannot be trusted in this scene"; exit 1; fi
if [ "$RC" -eq 0 ]; then say "PASS AWRE terrain is visible: the frame with AWRE differs from the same-session control, and the negative control fails as it should"; exit 0; fi
say "FAIL AWRE terrain is not visible in the horizon band (see the FrameCheck line above for the reason)"; exit 1
