#!/usr/bin/env bash
# Render-smoke gate: parses the LAST "[AWRE] render:" counter line of a client log and reports the first stalled stage.
#   received -> mesh built -> GPU uploaded -> draw submitted
# Usage: tools/ci/render-gate.sh <client.log>      Exit: 0 pass, 1 fail (stage named on stdout), 2 usage/unreadable log
# A passing gate proves the renderer EXECUTED (meshed, uploaded, issued draws). It does NOT prove the pixels are right.
set -uo pipefail
LOG=${1:?usage: render-gate.sh <client.log>}
[ -r "$LOG" ] || { echo "RENDER-GATE ERROR: cannot read $LOG"; exit 2; }

LINE=$(grep -E '\[AWRE\] render: ' "$LOG" | tail -1)
if [ -z "$LINE" ]; then
  echo "RENDER-GATE FAIL: stalled before 'received': no '[AWRE] render:' line (world-render event never reached or renderer disabled)"
  exit 1
fi
val() { sed -nE "s/.* $1=([0-9]+).*/\1/p" <<<"$LINE" | head -1; }
for k in received mesh_built mesh_failed gpu_uploaded upload_failed submitted_draws; do
  v=$(val $k); [ -n "$v" ] || { echo "RENDER-GATE FAIL: counter '$k' missing in: $LINE"; exit 1; }
  eval "c_$k=$v"
done

# AWRE renderer exceptions: an Exception/Error line with a dev.awre frame in the next 15 lines, or an AWRE-tagged exception/rejection.
EXC=$(awk '/Exception|Error:/ {n=NR; line=$0} /\tat dev\.awre\.|^\s+at dev\.awre\./ { if (n && NR-n<=15) { print line; n=0 } } /AWRE.*(Exception|rejected)/ {print}' "$LOG" | head -3)

stage=""
if   [ "$c_received" -eq 0 ];        then stage="received (no tiles reached the client)"
elif [ "$c_mesh_built" -eq 0 ];      then stage="mesh built (tiles received=$c_received but none meshed)"
elif [ "$c_mesh_failed" -ne 0 ];     then stage="mesh built (mesh_failed=$c_mesh_failed)"
elif [ "$c_gpu_uploaded" -eq 0 ];    then stage="GPU uploaded (mesh_built=$c_mesh_built but no upload)"
elif [ "$c_upload_failed" -ne 0 ];   then stage="GPU uploaded (upload_failed=$c_upload_failed)"
elif [ "$c_submitted_draws" -eq 0 ]; then stage="draw submitted (gpu_uploaded=$c_gpu_uploaded but no draws)"
fi
if [ -n "$stage" ]; then echo "RENDER-GATE FAIL: first stalled stage: $stage"; exit 1; fi
if [ -n "$EXC" ]; then echo "RENDER-GATE FAIL: AWRE renderer exception in log:"; echo "$EXC"; exit 1; fi
echo "RENDER-GATE PASS: received=$c_received mesh_built=$c_mesh_built gpu_uploaded=$c_gpu_uploaded submitted_draws=$c_submitted_draws (counters prove execution, not visual correctness)"
exit 0
