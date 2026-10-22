#!/usr/bin/env bash
# Tests for render-gate.sh (no Minecraft, no network). Usage: bash tools/ci/render-gate-test.sh
set -uo pipefail
cd "$(dirname "$0")"
G="bash ./render-gate.sh"; T=$(mktemp -d); FAIL=0
mk() { printf '[00:00:00] [Render thread/INFO]: [AWRE] render: received=%s mesh_built=%s mesh_failed=%s gpu_uploaded=%s upload_failed=%s gpu_freed=0 gpu_resident=1 gpu_bytes=1 planned_draws=1 planned_tris=1 submitted_draws=%s submitted_frames=1\n' "$@"; }
expect() { # expect <name> <exit> <stdout-regex> <logfile>
  out=$($G "$4" 2>&1); rc=$?
  if [ "$rc" -eq "$2" ] && grep -Eq "$3" <<<"$out"; then echo "PASS $1"; else echo "FAIL $1 (rc=$rc want $2): $out"; FAIL=$((FAIL+1)); fi; }
mk 10 5 0 5 0 99 > $T/ok.log;                expect "all stages pass"            0 'RENDER-GATE PASS'                          $T/ok.log
expect "real clientA fixture passes"         0 'RENDER-GATE PASS' fixtures/clientA-render.log
: > $T/empty.log;                            expect "no render line"             1 "before 'received'"                          $T/empty.log
mk 0 0 0 0 0 0 > $T/a.log;                   expect "stall at received"          1 'stalled stage: received'                    $T/a.log
mk 9 0 0 0 0 0 > $T/b.log;                   expect "stall at mesh"              1 'stalled stage: mesh built'                  $T/b.log
mk 9 4 2 4 0 7 > $T/c.log;                   expect "mesh_failed>0"              1 'mesh_failed=2'                              $T/c.log
mk 9 4 0 0 0 0 > $T/d.log;                   expect "stall at GPU upload"        1 'stalled stage: GPU uploaded'                $T/d.log
mk 9 4 0 3 1 7 > $T/e.log;                   expect "upload_failed>0"            1 'upload_failed=1'                            $T/e.log
mk 9 4 0 4 0 0 > $T/f.log;                   expect "stall at draw"              1 'stalled stage: draw submitted'              $T/f.log
{ mk 1 1 0 1 0 0; mk 9 4 0 4 0 5; } > $T/g.log; expect "only the LAST line counts" 0 'RENDER-GATE PASS'                          $T/g.log
{ mk 9 4 0 4 0 5; printf 'java.lang.IllegalStateException: boom\n\tat dev.awre.fabric.HorizonRenderer.render(HorizonRenderer.java:1)\n'; } > $T/h.log
expect "AWRE renderer exception fails"       1 'renderer exception'                         $T/h.log
{ mk 9 4 0 4 0 5; printf 'java.lang.RuntimeException: other\n\tat net.minecraft.Foo.bar(Foo.java:1)\n'; } > $T/i.log
expect "unrelated exception ignored"         0 'RENDER-GATE PASS'                           $T/i.log
{ mk 9 4 0 4 0 5 | sed 's/$/ built_not_resident=2 sample=[0\/1\/2 0\/3\/4]/'; } > $T/j.log; expect "extended line with built_not_resident suffix" 0 'RENDER-GATE PASS' $T/j.log
expect "unreadable log -> exit 2"            2 'cannot read'                                $T/nope.log
rm -rf "$T"; [ "$FAIL" -eq 0 ] && echo "render-gate tests: all passed" || { echo "$FAIL render-gate test(s) failed"; exit 1; }
