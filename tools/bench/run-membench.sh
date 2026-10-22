#!/usr/bin/env bash
# Compiles and runs MemBench against horizon-core + awre-stream sources (no Gradle needed). Usage: tools/bench/run-membench.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/bench/MemBench.java
java -Xmx3g -cp "$OUT" MemBench
