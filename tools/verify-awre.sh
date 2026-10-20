#!/usr/bin/env bash
# Sandbox verification of the pure-Java AWRE protocol module (no Gradle needed). Usage: tools/verify-awre.sh
# Compiles awre-protocol with -Xlint:all and runs its real test files against the tiny JUnit shim.
# NOT covered here: awre-paper (needs Paper API) and awre-fabric (needs Loom + Minecraft); build those with Gradle.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
OUT=$(mktemp -d)
$JAVAC -Xlint:all -d "$OUT/main" $(find awre-protocol/src/main -name '*.java')
$JAVAC -cp "$OUT/main" -d "$OUT/test" $(find tools/junit-shim awre-protocol/src/test -name '*.java')
java -cp "$OUT/main:$OUT/test" Runner "$OUT/test"
