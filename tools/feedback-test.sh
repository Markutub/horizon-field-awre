#!/usr/bin/env bash
# Tests tools/feedback.sh against a MOCK Gradle (no real build, no network). Version strings below are fake placeholders.
set -uo pipefail
cd "$(dirname "$0")/.."
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
cat >"$T/mockgradle" <<'MOCK'
#!/usr/bin/env bash
args="$*"
case "$args" in
  *dependencies*) echo "+--- io.papermc.paper:paper-api:MOCK-API -> MOCK-RESOLVED-API"; exit 0 ;;
  *"-p awre-fabric"*)
     echo "Fabric Loom: MOCK-LOOM"
     if [ "${MOCK_FAIL:-}" = fabric ]; then echo "awre-fabric/src/X.java:7: error: cannot find symbol"; echo "    foo();"; echo "    ^"; echo "FAILURE: Build failed"; echo "* What went wrong:"; echo "Execution failed for task ':compileJava'."; exit 1; fi
     echo "BUILD SUCCESSFUL"; exit 0 ;;
  *)  if [ "${MOCK_FAIL:-}" = root ]; then echo "awre-paper/src/Y.java:12: error: cannot find symbol"; echo "    getTickTimes();"; echo "    ^"; echo "* What went wrong:"; echo "Execution failed for task ':awre-paper:compileJava'."; exit 1; fi
      echo "BUILD SUCCESSFUL"; exit 0 ;;
esac
MOCK
chmod +x "$T/mockgradle"
fails=0
ck() { if grep -q -- "$2" "$1"; then echo "  ok   $3"; else echo "  FAIL $3 (missing: $2)"; fails=$((fails+1)); fi; }
sk() { # sk file "section title prefix" pattern description : pattern must appear INSIDE that section only
  if awk -v t="===== $2" 'index($0,t)==1{on=1;next} /^===== /{on=0} on' "$1" | grep -q -- "$3"; then echo "  ok   $4"; else echo "  FAIL $4 (not in section '$2': $3)"; fails=$((fails+1)); fi; }
ex() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 (got $1, want $2)"; fails=$((fails+1)); fi; }

echo "case: both builds succeed"
GRADLE_CMD="$T/mockgradle" SKIP_CHECK=1 bash tools/feedback.sh "$T/ok.txt" >/dev/null; ex $? 0 "exit 0"
ck "$T/ok.txt" "root build .*: OK" "root OK reported"; ck "$T/ok.txt" "fabric build .*: OK" "fabric OK reported"
sk "$T/ok.txt" "VERSIONS TO PIN" "Fabric Loom: MOCK-LOOM" "loom line in VERSIONS section"; sk "$T/ok.txt" "VERSIONS TO PIN" "MOCK-RESOLVED-API" "paper-api line in VERSIONS section"

echo "case: root build fails with a compile error"
MOCK_FAIL=root GRADLE_CMD="$T/mockgradle" SKIP_CHECK=1 bash tools/feedback.sh "$T/r.txt" >/dev/null; ex $? 1 "exit 1"
ck "$T/r.txt" "root build .*FAILED" "root FAILED reported"; sk "$T/r.txt" "ROOT BUILD: FIRST ERRORS" "Y.java:12: error: cannot find symbol" "error block captured in FIRST ERRORS"
sk "$T/r.txt" "ROOT BUILD: FIRST ERRORS" "What went wrong" "gradle explanation captured in FIRST ERRORS"; ck "$T/r.txt" "fabric build .*: OK" "fabric still ran"

echo "case: fabric build fails"
MOCK_FAIL=fabric GRADLE_CMD="$T/mockgradle" SKIP_CHECK=1 bash tools/feedback.sh "$T/f.txt" >/dev/null; ex $? 1 "exit 1"
ck "$T/f.txt" "fabric build .*FAILED" "fabric FAILED reported"; sk "$T/f.txt" "FABRIC BUILD: FIRST ERRORS" "X.java:7: error: cannot find symbol" "error block captured in FIRST ERRORS"
echo; [ "$fails" -eq 0 ] && echo "feedback-test: all passed" || { echo "feedback-test: $fails FAILED"; exit 1; }
