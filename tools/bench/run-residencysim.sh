#!/usr/bin/env bash
# Compiles and runs ResidencySim for a few camera/render-distance scenarios. No Gradle needed. Usage: tools/bench/run-residencysim.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/bench/ResidencySim.java
run() { echo "== scenario: final radius $1 blocks, $2 ticks at camera ($3,$4) radius $5"; java -cp "$OUT" ResidencySim "$1" "$2" "$3" "$4" "$5" | grep -E "mesh_built|built_not_resident=|lifetime" | sed 's/ received=[0-9]*//; s/ gpu_bytes=[0-9]*//; s/ planned_[a-z]*=[0-9]*//g; s/ submitted_[a-z]*=[0-9]*//g'; }
run 192 0 0 0 192        # stationary camera, render distance 12
run 192 40 0 0 32        # render distance 2 for the first 2 s, then 12 (a setting applied after joining)
run 192 5 700 700 192    # camera starts far away for 0.25 s, then at the spawn
