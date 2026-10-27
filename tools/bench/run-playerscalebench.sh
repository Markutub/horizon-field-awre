#!/usr/bin/env bash
# Compiles and runs PlayerScaleBench (N-player streaming cost, synthetic, no Gradle). Usage: bash tools/bench/run-playerscalebench.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/bench/PlayerScaleBench.java
java -Xmx3g -cp "$OUT" PlayerScaleBench
