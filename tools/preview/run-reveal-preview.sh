#!/usr/bin/env bash
# CPU preview + self-check of the terrain reveal effect (see tools/preview/RevealPreview.java). No Gradle, no GPU.
# Tune with e.g.:  JAVA_TOOL_OPTIONS="-Dawre.reveal.speed=200 -Dawre.reveal.peak=10" bash tools/preview/run-reveal-preview.sh
# Usage: bash tools/preview/run-reveal-preview.sh [outDir]   Exit 1 when a check fails.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/preview/RevealPreview.java
java -Djava.awt.headless=true -cp "$OUT" RevealPreview "${1:-reveal-frames}"
