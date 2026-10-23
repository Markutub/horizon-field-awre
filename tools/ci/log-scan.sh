#!/usr/bin/env bash
# Log assertions for the integration job, kept out of integration.sh so they can be unit-tested against real log excerpts
# (tools/ci/log-scan-test.sh). No Minecraft, no network.
#
#   log-scan.sh awre-exceptions <client|server> <log>
#       Exit 1 when the log holds an AWRE-attributable failure: (a) a line tagged AWRE with an exception/rejection, OR (b) any exception
#       whose stack (next 40 lines) contains a dev.awre / dev.horizonfield frame, even when the message line itself never says "AWRE".
#       Case (b) is what the previous single-line grep could not see. Unrelated exceptions (OpenAL, narrator, ...) do not fail this.
#   log-scan.sh converged <log>
#       Judges the LAST "[AWRE] horizon view:" line. Exit 0 only when every candidate within the mesh budget has a mesh:
#         candidates > 0, ready + over_cap == candidates, pending == 0, failures == 0, and held == candidates + hidden_vanilla + too_far.
#       "ready >= 1" proves one tile was meshed; this proves the EXPECTED tiles were. Exit 1 names the first violated condition, 2 = no line.
#   log-scan.sh classify <log>
#       Prints known environmental noise by category and lists ERROR-level lines that match no known category (never fails).
#       Nothing is suppressed: unknown errors are shown, known ones are labelled.
set -uo pipefail
cmd=${1:?usage: log-scan.sh awre-exceptions <client|server> <log> | converged <log> | classify <log>}
shift

case "$cmd" in
  awre-exceptions)
    mode=${1:?mode client|server}; log=${2:?log}
    [ -r "$log" ] || { echo "LOG-SCAN ERROR: cannot read $log"; exit 2; }
    if [ "$mode" = client ]; then tagged='AWRE.*(Exception|rejected)'; else tagged='AWRE.*(Exception|SEVERE)|Could not pass event'; fi
    hit=$(grep -En "$tagged" "$log" | head -5)
    # (b) exception header followed by an AWRE frame within 40 lines
    stack=$(awk '
      /(Exception|Error)(:|$)/ && !/^[[:space:]]+at / { hdr=$0; hn=NR }
      /^[[:space:]]+at (knot\/\/)?dev\.(awre|horizonfield)\./ { if (hn && NR-hn <= 40) { print "line " hn ": " hdr " (AWRE frame at line " NR ")"; hn=0 } }
    ' "$log" | head -5)
    if [ -n "$hit$stack" ]; then
      echo "LOG-SCAN FAIL: AWRE-attributable failure in $(basename "$log")"; [ -n "$hit" ] && echo "$hit"; [ -n "$stack" ] && echo "$stack"; exit 1
    fi
    echo "LOG-SCAN PASS: no AWRE-attributable exception in $(basename "$log") (unrelated exceptions, if any, are listed by 'classify')"; exit 0 ;;

  converged)
    log=${1:?log}
    [ -r "$log" ] || { echo "LOG-SCAN ERROR: cannot read $log"; exit 2; }
    line=$(grep -E '\[AWRE\] horizon view: ' "$log" | tail -1)
    [ -n "$line" ] || { echo "LOG-SCAN FAIL: no '[AWRE] horizon view:' line"; exit 2; }
    v() { sed -nE "s/.* $1=([0-9]+).*/\1/p" <<<"$line" | head -1; }
    for k in held candidates ready hidden_vanilla too_far pending failures; do
      x=$(v $k); [ -n "$x" ] || { echo "LOG-SCAN FAIL: field '$k' missing in: $line"; exit 1; }; eval "c_$k=$x"
    done
    c_over=$(v over_cap); c_over=${c_over:-0}              # older logs have no over_cap
    if   [ "$c_candidates" -eq 0 ]; then echo "LOG-SCAN FAIL: not converged: candidates=0 (nothing was selected for meshing): $line"; exit 1
    elif [ "$c_failures" -ne 0 ]; then echo "LOG-SCAN FAIL: mesh failures=$c_failures: $line"; exit 1
    elif [ $((c_candidates + 0)) -ne $((c_ready + c_over)) ] || [ "$c_pending" -ne 0 ]; then
      echo "LOG-SCAN FAIL: not converged: candidates=$c_candidates ready=$c_ready over_cap=$c_over pending=$c_pending (expected ready+over_cap == candidates and pending == 0)"; exit 1
    elif [ "$c_held" -ne $((c_candidates + c_hidden_vanilla + c_too_far)) ]; then
      echo "LOG-SCAN FAIL: tile accounting: held=$c_held but candidates+hidden_vanilla+too_far=$((c_candidates + c_hidden_vanilla + c_too_far))"; exit 1
    fi
    echo "LOG-SCAN PASS: converged: held=$c_held candidates=$c_candidates ready=$c_ready over_cap=$c_over hidden_vanilla=$c_hidden_vanilla too_far=$c_too_far pending=0 failures=0"; exit 0 ;;

  classify)
    log=${1:?log}
    [ -r "$log" ] || { echo "LOG-SCAN ERROR: cannot read $log"; exit 2; }
    # label|ERE  (environmental: the runner has no audio device, no Mojang services, no narrator, an offline account, a bare flat-world config)
    cats='OpenAL / SoundSystem (no audio device)|Error starting SoundSystem|OpenAL
narrator library missing|Error while loading the narrator
Mojang services unreachable or offline account|Failed to (fetch user properties|retrieve profile key pair|fetch Realms feature flags)
unsupported shader uniform (vanilla pipelines, headless GL)|Found unknown and unsupported uniform
dynamic UBO resize (normal, first frames)|Resizing .* UBO
offline/insecure mode (test server runs online-mode=false)|OFFLINE/INSECURE MODE|will make no attempt to authenticate|online-mode
flat level-type with empty generator-settings|No key layers in MapLike
window icon on this platform|Not setting icon for unrecognized platform
Paper outdated-build notice|You are running the latest build|release\(s\) behind'
    echo "known environmental noise in $(basename "$log"):"
    while IFS='|' read -r label re; do
      [ -n "$label" ] || continue
      n=$(grep -Ec "$re" "$log"); [ "$n" -gt 0 ] && printf '  %5d  %s\n' "$n" "$label"
    done <<<"$cats"
    all_re=$(while IFS='|' read -r label re; do [ -n "$label" ] && printf '%s|' "$re"; done <<<"$cats"); all_re=${all_re%|}
    unk=$(grep -E '(/ERROR\]| ERROR\]:)' "$log" | grep -Ev "$all_re" | head -10)
    if [ -n "$unk" ]; then echo "UNCLASSIFIED ERROR-level lines (not known environmental noise; read these):"; echo "$unk" | cut -c1-220; else echo "no unclassified ERROR-level lines"; fi
    exit 0 ;;

  *) echo "unknown command: $cmd"; exit 2 ;;
esac
