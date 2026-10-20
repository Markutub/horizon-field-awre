#!/usr/bin/env bash
# Sandbox verification of the pure-Java streaming glue (awre-stream) against awre-protocol + horizon-core. Usage: tools/verify-stream.sh
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
OUT=$(mktemp -d)
$JAVAC -Xlint:all -d "$OUT/main" $(find horizon-core/src/main awre-protocol/src/main awre-stream/src/main -name '*.java')
$JAVAC -cp "$OUT/main" -d "$OUT/test" $(find tools/junit-shim awre-stream/src/test -name '*.java')
java -cp "$OUT/main:$OUT/test" Runner "$OUT/test"
