#!/usr/bin/env bash
# Tests FrameCheck.java on synthetic frames, including the cases that must FAIL. Usage: bash tools/ci/frame-check-test.sh
set -u
cd "$(dirname "$0")/../.."
T=$(mktemp -d)
java tools/ci/MakeTestFrames.java "$T" || { echo "could not generate test frames"; exit 2; }
fails=0
expect() { # expect <name> <exit-code> <args...>
  local name=$1 want=$2; shift 2
  out=$(java tools/ci/FrameCheck.java "$@" 2>&1); got=$?
  if [ "$got" = "$want" ]; then echo "ok   $name (exit $got)"; else echo "FAIL $name: wanted exit $want, got $got: $out"; fails=$((fails+1)); fi
}
expect "terrain visible vs control"                          0 --awre "$T/awre.png" --control "$T/control.png"
expect "terrain visible, stable control confirmed by noise run" 0 --awre "$T/awre.png" --control "$T/control.png" --noise "$T/control2.png"
expect "AWRE draws nothing (identical to control)"           1 --awre "$T/control.png" --control "$T/control.png"
expect "only noise differs (below min-diff)"                 1 --awre "$T/control2.png" --control "$T/control.png"
expect "difference only outside the horizon band (HUD)"      1 --awre "$T/hud-only.png" --control "$T/control.png"
expect "unstable control scene makes a difference meaningless" 1 --awre "$T/awre.png" --control "$T/control.png" --noise "$T/control-unstable.png"
expect "flat black AWRE frame (no GL output)"                1 --awre "$T/black.png" --control "$T/control.png"
expect "size mismatch"                                       2 --awre "$T/small.png" --control "$T/control.png"
expect "not an image"                                        2 --awre "$T/notimage.png" --control "$T/control.png"
expect "missing file"                                        2 --awre "$T/nope.png" --control "$T/control.png"
expect "bad option"                                          2 --awre "$T/awre.png" --control "$T/control.png" --bogus
expect "band can be widened to include a change elsewhere"   0 --awre "$T/hud-only.png" --control "$T/control.png" --band 0.0,1.0
if [ "$fails" = 0 ]; then echo "frame-check tests: all passed"; else echo "frame-check tests: $fails FAILED"; exit 1; fi
