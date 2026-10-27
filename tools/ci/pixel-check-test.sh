#!/usr/bin/env bash
# Tests tools/ci/pixel-check.sh with a MOCK display capture and a MOCK client that reacts to the pause file (no Minecraft, no X server).
# Frames come from tools/ci/MakeTestFrames.java. Usage: bash tools/ci/pixel-check-test.sh
set -u
cd "$(dirname "$0")/../.."
T="$(mktemp -d)"; trap 'rm -rf "$T"; jobs -p | xargs -r kill 2>/dev/null' EXIT
java tools/ci/MakeTestFrames.java "$T/f" || { echo "cannot generate frames"; exit 2; }
CONVERGED='[00:00:01] [Render thread/INFO]: [AWRE] horizon view: held=150 candidates=100 ready=100 over_cap=0 hidden_vanilla=50 too_far=0 pending=0 failures=0'
# mock capture: the Nth call copies the Nth path listed in $SEQ (so a case chooses what the three captures "look like")
cat >"$T/capture" <<'MOCK'
#!/usr/bin/env bash
n=$(( $(cat "$SEQ.n" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$SEQ.n"
src=$(sed -n "${n}p" "$SEQ"); [ -n "$src" ] && [ "$src" != FAIL ] || exit 1
cp "$src" "$1"
MOCK
chmod +x "$T/capture"
fails=0; case_no=0
run_case() { # run_case <name> <want-exit> <frames: a b c | FAIL...> -- env...  ; client log pre-seeded; responder optional via RESPOND=0/1
  local name=$1 want=$2; shift 2
  case_no=$((case_no+1)); local d="$T/c$case_no"; mkdir -p "$d"
  printf '%s\n%s\n%s\n' "$1" "$2" "$3" > "$d/seq"; shift 3
  local log="$d/client.log"; : > "$log"; [ "${NO_CONVERGE:-0}" = 1 ] || echo "$CONVERGED" >> "$log"
  local pf="$d/pause.flag"
  if [ "${RESPOND:-1}" = 1 ]; then ( while [ ! -e "$pf" ]; do sleep 0.05; done; echo '[AWRE] test render pause ON (AWRE terrain not drawn)' >> "$log" ) & fi
  out=$(SEQ="$d/seq" CAPTURE_CMD="$T/capture" PAUSE_FILE="$pf" SETTLE=0 PAUSE_SETTLE=0 POLL=0.1 CONVERGE_TIMEOUT=2 PAUSE_TIMEOUT=2 \
        bash tools/ci/pixel-check.sh "$log" "$d/out" 2>&1); got=$?
  jobs -p | xargs -r kill 2>/dev/null; wait 2>/dev/null
  if [ "$got" = "$want" ]; then echo "ok   $name (exit $got)"; else echo "FAIL $name: wanted exit $want, got $got"; echo "$out" | sed 's/^/       /'; fails=$((fails+1)); fi
}
F="$T/f"
run_case "AWRE visible, stable scene"                                   0 "$F/awre.png"          "$F/control.png" "$F/control2.png"
run_case "AWRE draws nothing (awre frame == control)"                   1 "$F/control.png"       "$F/control.png" "$F/control2.png"
run_case "unstable scene (controls differ a lot)"                       1 "$F/awre.png"          "$F/control.png" "$F/control-unstable.png"
run_case "flat black AWRE frame (no GL output)"                         1 "$F/black.png"         "$F/control.png" "$F/control2.png"
run_case "check cannot fail: 'control2' already differs like AWRE"      1 "$F/awre.png"          "$F/control.png" "$F/awre.png"
FRAMECHECK_ARGS="--max-noise 1.0" run_case "loosened noise guard: the negative control still refuses an unstable scene" 1 "$F/awre.png" "$F/control.png" "$F/control-unstable.png"
run_case "frame size mismatch is infrastructure, not a verdict"         2 "$F/small.png"         "$F/control.png" "$F/control2.png"
run_case "capture command fails"                                        2 FAIL                   "$F/control.png" "$F/control2.png"
RESPOND=0 run_case "client never honours the pause hook"               2 "$F/awre.png"          "$F/control.png" "$F/control2.png"
NO_CONVERGE=1 run_case "horizon never converges"                       2 "$F/awre.png"          "$F/control.png" "$F/control2.png"
echo; [ "$fails" = 0 ] && echo "pixel-check tests: all passed" || { echo "pixel-check tests: $fails FAILED"; exit 1; }
