#!/usr/bin/env bash
# Software reference frames of the real LOD plan + meshes (geometry check only; NOT an in-game render). No Gradle, no GPU.
# Usage: bash tools/preview/run-reference-frame.sh [outDir]   Exit 1 when a view has see-through holes in its near-ground band.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java') tools/preview/ReferenceFrame.java
java -cp "$OUT" ReferenceFrame "${1:-reference-frames}"
