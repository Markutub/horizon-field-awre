#!/usr/bin/env bash
# Offline report on a HorizonField cache file. Needs only a JDK/JRE 21+ (no Gradle, no network). Read-only on the .hfl.
# Usage: tools/run-report.sh /path/to/<world-uuid>.hfl
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -ge 1 ] || { echo "usage: $0 file.hfl" >&2; exit 2; }
F="$(realpath "$1")"
OUT=$(mktemp -d)
JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
$JAVAC -d "$OUT" $(find horizon-core/src/main -name '*.java') tools/bench/CodecBench.java tools/bench/RealTileReport.java
java -Xmx2g -cp "$OUT" dev.horizonfield.core.RealTileReport "$F"
