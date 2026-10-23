#!/usr/bin/env bash
# Tests for log-scan.sh (no Minecraft, no network). Usage: bash tools/ci/log-scan-test.sh
# Fixtures named real-*.log are verbatim excerpts of the integration-logs artifact of the 2026-10-22 CI run (commit 5517d55).
# Fixtures built in this script are SYNTHETIC and say so: the real logs contain no AWRE exception, so the failing paths need made-up input.
set -uo pipefail
cd "$(dirname "$0")"
S="bash ./log-scan.sh"; F=fixtures; T=$(mktemp -d); FAIL=0
expect() { # expect <name> <want-exit> <stdout-regex> <args...>
  local name=$1 want=$2 re=$3; shift 3
  out=$($S "$@" 2>&1); rc=$?
  if [ "$rc" -eq "$want" ] && grep -Eq "$re" <<<"$out"; then echo "PASS $name"; else echo "FAIL $name (rc=$rc want $want, want /$re/): $out"; FAIL=$((FAIL+1)); fi
}
view() { printf '[00:00:00] [Render thread/INFO]: [AWRE] horizon view: %s\n' "$1"; }

# ---- converged ----
# REAL: the supplied run's last client-A line. The harness stopped the client at ready=852 of 1626 candidates, so the old "ready>=1" check passed
# while the stream had not finished meshing. This is a statement about the supplied run, not about the current code.
expect "real clientA end: ready 852 of 1626 is NOT converged" 1 'not converged: candidates=1626 ready=852' converged $F/real-clientA-view-end.log
# REAL: client C holds 182 tiles, all inside the vanilla square: candidates=0 (which is why the job never asserts convergence for client C).
expect "real clientC end: candidates=0 is reported, not passed" 1 'candidates=0' converged $F/real-clientC-view-end.log
view 'held=2127 candidates=1626 ready=1626 drawn=1500 hidden_vanilla=501 hidden_finer=126 too_far=0 tris=1 pending=0 failures=0' > $T/ok.log      # SYNTHETIC
expect "synthetic converged line passes (old format without over_cap)" 0 'LOG-SCAN PASS: converged' converged $T/ok.log
view 'held=60 candidates=30 ready=10 drawn=10 hidden_vanilla=30 hidden_finer=0 too_far=0 tris=1 pending=0 failures=0 over_cap=20 vanilla_radius=192' > $T/cap.log  # SYNTHETIC
expect "tiles above the mesh cap are counted, not mistaken for stalls" 0 'over_cap=20' converged $T/cap.log
view 'held=2127 candidates=1626 ready=1626 drawn=1 hidden_vanilla=501 hidden_finer=0 too_far=0 tris=1 pending=3 failures=0' > $T/p.log
expect "pending work is not converged" 1 'pending=3' converged $T/p.log
view 'held=2127 candidates=1626 ready=1620 drawn=1 hidden_vanilla=501 hidden_finer=0 too_far=0 tris=1 pending=0 failures=6' > $T/f.log
expect "mesh failures fail" 1 'mesh failures=6' converged $T/f.log
view 'held=2200 candidates=1626 ready=1626 drawn=1 hidden_vanilla=501 hidden_finer=0 too_far=0 tris=1 pending=0 failures=0' > $T/a.log
expect "held != candidates+hidden+too_far fails (lost tiles)" 1 'tile accounting' converged $T/a.log
view 'held=1 candidates=1' > $T/m.log
expect "missing field fails" 1 "field 'ready' missing" converged $T/m.log
: > $T/none.log;  expect "no view line" 2 "no '\[AWRE\] horizon view:' line" converged $T/none.log
view 'held=1 candidates=1 ready=1 drawn=1 hidden_vanilla=0 too_far=0 pending=0 failures=0' > $T/old.log
{ cat $T/old.log; view 'held=9 candidates=9 ready=2 drawn=2 hidden_vanilla=0 too_far=0 pending=0 failures=0'; } > $T/last.log
expect "only the LAST view line counts" 1 'ready=2' converged $T/last.log

# ---- awre-exceptions ----
expect "real OpenAL exception (no AWRE frame) is not an AWRE failure" 0 'LOG-SCAN PASS' awre-exceptions client $F/real-clientA-openal.log
expect "real flat-world MapLike errors on the server are not an AWRE failure" 0 'LOG-SCAN PASS' awre-exceptions server $F/real-server-maplike.log
# SYNTHETIC: a stack trace whose message line never mentions AWRE, only the frames do (the shape a renderer crash really has).
printf '[00:00:01] [Render thread/ERROR]: Unhandled\njava.lang.IllegalStateException: boom\n\tat knot//net.minecraft.class_1.m(c.java:1)\n\tat knot//dev.awre.fabric.HorizonRenderer.render(HorizonRenderer.java:9)\n' > $T/trace.log
if grep -En 'AWRE.*(Exception|rejected)' $T/trace.log >/dev/null; then echo "FAIL premise: old single-line check was expected NOT to see this trace"; FAIL=$((FAIL+1)); else echo "PASS premise: the previous single-line grep does not see a stack trace with only AWRE frames"; fi
expect "stack trace with an AWRE frame fails (client)" 1 'AWRE frame at line 4' awre-exceptions client $T/trace.log
printf '[00:00:02] [Render thread/WARN]: [AWRE] draw failed at stage x after 0 of 3 draws\njava.lang.RuntimeException: y\n\tat dev.horizonfield.core.terrain.TerrainMesher.mesh(T.java:1)\n' > $T/trace2.log
expect "stack trace with a core frame fails" 1 'AWRE frame' awre-exceptions client $T/trace2.log
printf '[00:00:03] [Render thread/WARN]: [AWRE] payload rejected: oversize\n' > $T/rej.log
expect "tagged rejection fails on a client" 1 'rejected' awre-exceptions client $T/rej.log
printf '[00:00:04 WARN]: [AWRE] session abc REJECTED INCOMPATIBLE_VERSION\n' > $T/srvrej.log
expect "expected server-side INCOMPATIBLE_VERSION rejection is not an exception" 0 'LOG-SCAN PASS' awre-exceptions server $T/srvrej.log
printf '[00:00:05 ERROR]: [AWRE] Could not pass event X to AWRE\n[00:00:05 WARN]: at dev.awre.paper.A\n' > $T/ev.log
expect "server: Could not pass event fails" 1 'LOG-SCAN FAIL' awre-exceptions server $T/ev.log
expect "unreadable log -> exit 2" 2 'cannot read' awre-exceptions client $T/nope.log

# ---- classify ----
out=$($S classify $F/real-clientA-env-errors.log 2>&1); rc=$?
if [ $rc -eq 0 ] && grep -q 'no unclassified ERROR-level lines' <<<"$out" && grep -q 'OpenAL' <<<"$out" && grep -q 'narrator' <<<"$out" && grep -q 'Mojang services' <<<"$out"; then echo "PASS real client environmental errors are all labelled"; else echo "FAIL real client environmental errors: $out"; FAIL=$((FAIL+1)); fi
out=$($S classify $F/real-server-maplike.log 2>&1)
if grep -q 'flat level-type with empty generator-settings' <<<"$out" && grep -q 'no unclassified' <<<"$out"; then echo "PASS real server MapLike errors are labelled"; else echo "FAIL real server MapLike: $out"; FAIL=$((FAIL+1)); fi
{ cat $F/real-clientA-env-errors.log; printf '[00:00:09] [Render thread/ERROR]: Something new and unexpected\n'; } > $T/unk.log
out=$($S classify $T/unk.log 2>&1)
if grep -q 'UNCLASSIFIED' <<<"$out" && grep -q 'Something new and unexpected' <<<"$out"; then echo "PASS an unknown ERROR line is surfaced, never hidden"; else echo "FAIL unknown error surfacing: $out"; FAIL=$((FAIL+1)); fi

rm -rf "$T"; [ "$FAIL" -eq 0 ] && echo "log-scan tests: all passed" || { echo "$FAIL log-scan test(s) failed"; exit 1; }
