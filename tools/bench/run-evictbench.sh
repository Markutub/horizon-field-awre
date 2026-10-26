#!/usr/bin/env bash
# Compiles and runs EvictBench (no Gradle). Usage: tools/bench/run-evictbench.sh [cap]
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/bench/EvictBench.java
timeout 250 java -Xmx3g -cp "$OUT" EvictBench "${1:-300000}"
