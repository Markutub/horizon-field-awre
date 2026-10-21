#!/usr/bin/env bash
# Compiles and runs LodCodecBench against horizon-core + awre-stream sources (no Gradle needed). Usage: tools/bench/run-lodcodec.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
OUT=$(mktemp -d)
$JAVAC -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/bench/LodCodecBench.java
java -cp "$OUT" LodCodecBench
