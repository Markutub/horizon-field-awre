#!/usr/bin/env bash
# Compiles and runs BuildRateSim (no Gradle). Usage: tools/bench/run-buildratesim.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/bench/BuildRateSim.java
timeout 200 java -cp "$OUT" BuildRateSim
