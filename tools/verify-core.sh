#!/usr/bin/env bash
# Sandbox verification without Gradle/JUnit (runs the REAL test files against a tiny JUnit shim).
# Works with a JRE that only has the jdk.compiler module. Usage: tools/verify-core.sh
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
OUT=$(mktemp -d)
$JAVAC -d "$OUT/main" $(find horizon-core/src/main -name '*.java')
$JAVAC -cp "$OUT/main" -d "$OUT/test" $(find tools/junit-shim horizon-core/src/test -name '*.java')
java -cp "$OUT/main:$OUT/test" Runner "$OUT/test"
