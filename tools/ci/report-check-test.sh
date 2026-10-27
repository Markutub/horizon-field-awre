#!/usr/bin/env bash
# Tests tools/ci/report-check.sh on hand-made report files, including every case that must FAIL. Usage: bash tools/ci/report-check-test.sh
set -u
cd "$(dirname "$0")/../.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
GOOD='{"schema":1,"time":"2026-10-26T01:02:03.456Z","reason":"timer","pluginVersion":null,"server":"Paper 1.21.11","java":"21.0.5+11","config":{"streamEnabled":true,"source":"synthetic","radiusBlocks":1000.0},"heapUsedMiB":120,"heapMaxMiB":1024,"mspt":null,"counters":{"hello":1,"tiles":40},"capture":null,"playersOnline":1,"awreSessions":1,"players":[{"name":"Bot","state":"ACTIVE","tilesSent":40,"stream":{"held":40}}]}'
mk() { printf '%s\n' "$2" > "$T/$1"; }
w() { sed "s/$1/$2/" <<<"$GOOD"; }
mk good "$GOOD"
mk idle "$(w '"tilesSent":40' '"tilesSent":0')"
mk noplayers "$(w '"players":\[{[^]]*}\]' '"players":[]')"
printf '%s\n%s\n' "$GOOD" "$(w '"reason":"timer"' '"reason":"shutdown"')" > "$T/two"
: > "$T/empty"
mk notjson 'this is not json'
mk pretty '{
  "schema": 1 }'
mk schema2 "$(w '"schema":1' '"schema":2')"
mk nocounters "$(sed 's/"counters":{[^}]*},//' <<<"$GOOD")"
mk badcounter "$(w '"tiles":40' '"tiles":"forty"')"
mk badreason "$(w '"reason":"timer"' '"reason":"whenever"')"
mk badtime "$(w '2026-10-26T01:02:03.456Z' 'yesterday')"
mk boolsession "$(w '"awreSessions":1' '"awreSessions":true')"
mk noplayername "$(w '"name":"Bot",' '')"
mk array '[1,2,3]'
printf '\xff\xfe{"schema":1}\n' > "$T/binary"
fails=0
expect() { local name=$1 want=$2; shift 2; out=$(bash tools/ci/report-check.sh "$@" 2>&1); got=$?
  if [ "$got" = "$want" ]; then echo "ok   $name (exit $got)"; else echo "FAIL $name: wanted $want got $got: $out"; fails=$((fails+1)); fi; }
expect "valid single report"                          0 "$T/good"
expect "valid report, last reason shutdown"           0 "$T/two" --min-lines 2 --last-reason shutdown
expect "activity required and present"                  0 "$T/good" --require-activity
expect "activity required but every player has 0 tiles"  1 "$T/idle" --require-activity
expect "activity required but no players at all"         1 "$T/noplayers" --require-activity
expect "too few lines"                                1 "$T/good" --min-lines 2
expect "last line is not the shutdown report"         1 "$T/good" --last-reason shutdown
expect "empty file"                                   1 "$T/empty"
expect "not JSON"                                     1 "$T/notjson"
expect "pretty-printed JSON is not JSON-lines"        1 "$T/pretty"
expect "unknown schema version"                       1 "$T/schema2"
expect "missing counters section"                     1 "$T/nocounters"
expect "counter value not an integer"                 1 "$T/badcounter"
expect "reason outside the promised set"             1 "$T/badreason"
expect "time not ISO-8601"                            1 "$T/badtime"
expect "boolean where an integer belongs"             1 "$T/boolsession"
expect "player entry without a name"                  1 "$T/noplayername"
expect "line is an array, not an object"              1 "$T/array"
expect "binary garbage"                               1 "$T/binary"
expect "missing file"                                 2 "$T/nope"
expect "no arguments"                                 2
expect "unknown option"                               2 "$T/good" --bogus
if [ "$fails" = 0 ]; then echo "report-check tests: all passed"; else echo "report-check tests: $fails FAILED"; exit 1; fi
