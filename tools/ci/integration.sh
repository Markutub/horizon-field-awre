#!/usr/bin/env bash
# End-to-end AWRE check with HeadlessMc: Paper server + AWRE plugin, Fabric client + AWRE mod, then assert on the logs.
# Usage: tools/ci/integration.sh <dir-with-jars>      Env: MC_VERSION (default 1.21.11), HMC (default: headlessmc), OUT (default ./ci-out)
# Needs: JDK 21 on PATH, HeadlessMc installed, network. Runs the client with an OFFLINE account: this is the CI-only use HeadlessMc allows.
# UNVERIFIED: HeadlessMc paths/flags were taken from its skill docs, not run. Every step dumps diagnostics to $OUT.
#
# By running this you also accept the Minecraft EULA (https://aka.ms/MinecraftEULA) for the throwaway CI server.
set -uo pipefail

JARS=$(realpath "${1:?usage: integration.sh <jars-dir>}")
MC=${MC_VERSION:-1.21.11}
HMC=${HMC:-headlessmc}
OUT=${OUT:-$PWD/ci-out}
mkdir -p "$OUT"
H() { "$HMC" -Dhmc.jline.enabled=false "$@"; }

FAILS=0; : > "$OUT/summary.md"
pass() { echo "PASS  $1"; echo "- PASS: $1" >> "$OUT/summary.md"; }
fail() { echo "FAIL  $1"; echo "- **FAIL**: $1" >> "$OUT/summary.md"; FAILS=$((FAILS+1)); }
check() { # check <description> <file> <extended-regex>
  if grep -Eq "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1   (pattern: $3 in $(basename "$2"))"; fi
}
die() { echo "FATAL: $*"; echo "- **FATAL**: $*" >> "$OUT/summary.md"; H debug > "$OUT/hmc-debug.txt" 2>&1 || true; exit 1; }
wait_for() { # wait_for <file> <regex> <seconds>
  local t=0; while [ $t -lt "$3" ]; do grep -Eq "$2" "$1" 2>/dev/null && return 0; sleep 5; t=$((t+5)); done; return 1
}
SERVER_PID=""; CLIENT_PID=""
cleanup() { for p in $CLIENT_PID $SERVER_PID; do [ -n "$p" ] && kill "$p" 2>/dev/null; done; pkill -f "net.fabricmc" 2>/dev/null; [ -n "${SERVER_DIR:-}" ] && pkill -f "$SERVER_DIR" 2>/dev/null; true; }
trap cleanup EXIT

ls "$JARS"/AWRE-Paper-*.jar "$JARS"/awre-fabric-*.jar >/dev/null || die "jars missing in $JARS"
H debug > "$OUT/hmc-debug-start.txt" 2>&1 || true

# ---------------------------------------------------------------- server
echo "== Paper $MC server"
H server add paper "$MC" > "$OUT/server-add.log" 2>&1 || die "server add failed (see server-add.log)"
SERVER_DIR=$(find "$HOME" -maxdepth 7 -type d -name "server-paper-$MC*" 2>/dev/null | head -1)
[ -n "$SERVER_DIR" ] || die "cannot locate server dir (server-paper-$MC*)"
echo "server dir: $SERVER_DIR"; ls -la "$SERVER_DIR" > "$OUT/server-dir.txt" 2>&1
echo "eula=true" > "$SERVER_DIR/eula.txt"
mkdir -p "$SERVER_DIR/plugins/AWRE"
cp "$JARS"/AWRE-Paper-*.jar "$SERVER_DIR/plugins/"
cat > "$SERVER_DIR/plugins/AWRE/config.yml" <<'YML'
enabled: true
auto-send-test-tile: true
auto-fragment-test-side: 128
debug: false
stream:
  enabled: true
  synthetic-chunk-radius: 80
  radius: 1000
limits:
  max-in-flight-tiles: 16
  tile-burst: 64
  tile-refill-per-sec: 500
YML
cat > "$SERVER_DIR/server.properties" <<'PROPS'
online-mode=false
server-port=25565
view-distance=4
simulation-distance=4
spawn-protection=0
level-type=minecraft\:flat
motd=awre-ci
PROPS
H server launch "server-paper-$MC" --jvm "-Xms512M -Xmx1G" > "$OUT/server.log" 2>&1 &
SERVER_PID=$!
wait_for "$OUT/server.log" 'Done \(' 300 || { tail -50 "$OUT/server.log"; die "server did not finish starting"; }
check "G0.1 server start: AWRE enabled" "$OUT/server.log" 'AWRE G0 enabled: channel awre:main'

# ---------------------------------------------------------------- client
echo "== Fabric client $MC"
H version install fabric "$MC" > "$OUT/client-install.log" 2>&1 || echo "version install returned non-zero (launch may still fetch it)"
H mod add mod fabric-api fabric "$MC" > "$OUT/mod-add.log" 2>&1 || die "could not add fabric-api via Modrinth (see mod-add.log)"
FAPI=$(find "$HOME" -maxdepth 8 -name 'fabric-api-*.jar' -not -path '*/.gradle/*' 2>/dev/null | head -1)
[ -n "$FAPI" ] || die "cannot locate the downloaded fabric-api jar"
MODS_DIR=$(dirname "$FAPI"); echo "mods dir: $MODS_DIR"
cp "$JARS"/awre-fabric-*.jar "$MODS_DIR/"
ls -la "$MODS_DIR" > "$OUT/mods-dir.txt"
printf 'pauseOnLostFocus:false\nonboardAccessibility:false\nnarrator:0\n' > "$MODS_DIR/../options.txt"

run_client() { # run_client <name> <extra jvm args>
  H launch --offline --headless --server localhost:25565 --jvm "-Xmx2G $2" fabric "$MC" > "$OUT/$1.log" 2>&1 &
  CLIENT_PID=$!
}
stop_client() { kill "$CLIENT_PID" 2>/dev/null; sleep 5; pkill -f "net.fabricmc" 2>/dev/null; CLIENT_PID=""; sleep 10; }

start_server() { # start_server <log>
  H server launch "server-paper-$MC" --jvm "-Xms512M -Xmx1G" > "$1" 2>&1 &
  SERVER_PID=$!
  wait_for "$1" 'Done \(' 300 || { tail -50 "$1"; die "server did not finish starting ($(basename "$1"))"; }
}
stop_server() { # stop_server <log>
  pkill -TERM -f "$SERVER_DIR" 2>/dev/null
  wait_for "$1" 'AWRE disabled' 90 || echo "server did not log plugin disable within 90 s ($(basename "$1"))"
  kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; SERVER_PID=""; sleep 5
}

echo "== client A: compatible mod client"
run_client clientA ""
wait_for "$OUT/clientA.log" 'verified OK \(32768 decoded bytes\)' 420 || echo "client A: fragment marker not seen within timeout"
wait_for "$OUT/clientA.log" 'store tiles=[0-9]{3,}' 240 || echo "client A: streamed tiles not seen within timeout"
wait_for "$OUT/server.log" 'stream [^:]+: leaves=[0-9]+ held=[0-9]{3,} .*holes=0' 180 || echo "server: stream did not report holes=0 within timeout"
check "G0.2 handshake OK on the client"            "$OUT/clientA.log" '\[AWRE\] handshake OK: protocol v1'
check "G0.2 caps negotiated = 15 (tile+fragment+drop+palette)" "$OUT/clientA.log" 'caps 15 '
check "G0.2 G0 test tile verified (512 bytes)"      "$OUT/clientA.log" 'verified OK \(512 decoded bytes\)'
check "G1 fragmented tile verified (32768 bytes)"   "$OUT/clientA.log" 'verified OK \(32768 decoded bytes\)'
check "G0.2 server session NEW -> ACTIVE"           "$OUT/server.log"  'session NEW -> ACTIVE'
check "G1 server sent the noise tile as 2+ frames"    "$OUT/server.log"  'noise tile side=128 SENT, frames=[2-9]'
check "G1 terrain stream attached on the server"      "$OUT/server.log"  'stream attached: '
check "G1 client received 100+ streamed tiles"        "$OUT/clientA.log" 'store tiles=[0-9]{3,}'
check "G1 server reports a hole-free stream"          "$OUT/server.log"  'stream [^:]+: leaves=[0-9]+ held=[0-9]{3,} .*holes=0'
check "G1 no tile failures reported by the server"    "$OUT/server.log"  'stream [^:]+: .*failures=0'
check "G1 client received the 5-entry synthetic palette" "$OUT/clientA.log" 'palette_entries=5 '
check "G1 client strikes stayed 0"                    "$OUT/clientA.log" 'store tiles=[0-9]+ .*strikes=0'
if grep -Eq 'Exception|Caused by' "$OUT/clientA.log"; then
  grep -En 'AWRE.*(Exception|rejected)' "$OUT/clientA.log" > /dev/null && fail "AWRE-related exception/rejection in client log" || pass "no AWRE exceptions in client log (unrelated exceptions may exist)"
else pass "no exceptions in client log"; fi
stop_client

echo "== client B: incompatible client (G0.4)"
run_client clientB "-Dawre.test.minVersion=2 -Dawre.test.maxVersion=3"
wait_for "$OUT/clientB.log" 'incompatible server' 420 || echo "client B: marker not seen within timeout"
check "G0.4 client reports incompatible and continues" "$OUT/clientB.log" 'incompatible server, continuing as a normal vanilla-style client'
check "G0.4 server rejects with INCOMPATIBLE_VERSION"  "$OUT/server.log"  'REJECTED.*INCOMPATIBLE_VERSION'
stop_client

# ---------------------------------------------------------------- shutdown + exceptions
pkill -TERM -f "$SERVER_DIR" 2>/dev/null
wait_for "$OUT/server.log" 'AWRE disabled' 90 || echo "server did not log plugin disable within 90 s"
kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null
if grep -Eq 'AWRE.*(Exception|SEVERE)|Could not pass event' "$OUT/server.log"; then fail "AWRE-related exception in server log"; else pass "no AWRE exceptions in server log"; fi
check "server stopped cleanly (plugin disabled)" "$OUT/server.log" 'AWRE disabled'

# ---------------------------------------------------------------- phase 2: captured (real chunks) + persistence
echo "== phase 2: captured terrain, fresh disk cache"
rm -rf "$SERVER_DIR/plugins/AWRE/terrain"
cat > "$SERVER_DIR/plugins/AWRE/config.yml" <<'YML'
enabled: true
auto-send-test-tile: false
auto-fragment-test-side: 0
debug: false
stream:
  enabled: true
  source: captured
  persist: true
  import-horizon-cache: false
  radius: 1000
limits:
  max-in-flight-tiles: 16
  tile-burst: 64
  tile-refill-per-sec: 500
YML
start_server "$OUT/server2.log"
run_client clientC ""
wait_for "$OUT/clientC.log" 'store tiles=[1-9][0-9]* ' 420 || echo "client C: captured tiles not seen within timeout"
wait_for "$OUT/server2.log" 'stream [^:]+: leaves=[0-9]+ held=[1-9][0-9]* ' 120 || echo "server2: no stream line with held tiles"
check "G1c capture started with persistence"         "$OUT/server2.log" "capturing loaded chunks of '.*' \\(OBSERVED, persisted\\)"
check "G1c first start: fresh disk cache"            "$OUT/server2.log" "terrain cache for '.*': fresh"
check "G1c client received captured tiles"           "$OUT/clientC.log" 'store tiles=[1-9][0-9]* '
check "G1c client received a palette from real blocks" "$OUT/clientC.log" 'palette_entries=[1-9]'
check "G1c unobserved areas send nothing (noData>0)" "$OUT/server2.log" 'stream [^:]+: .*noData=[1-9]'
check "G1c no tile failures reported by the server"  "$OUT/server2.log" 'stream [^:]+: .*failures=0'
check "G1c client strikes stayed 0"                  "$OUT/clientC.log" 'store tiles=[0-9]+ .*strikes=0'
stop_client
stop_server "$OUT/server2.log"
if grep -Eq 'AWRE.*(Exception|SEVERE)|Could not pass event' "$OUT/server2.log"; then fail "AWRE-related exception in server2 log"; else pass "no AWRE exceptions in server2 log"; fi
TERRAIN_FILE=$(find "$SERVER_DIR/plugins/AWRE/terrain" -name '*.hfl' 2>/dev/null | head -1)
ls -la "$SERVER_DIR/plugins/AWRE/terrain" > "$OUT/terrain-dir.txt" 2>&1
if [ -n "$TERRAIN_FILE" ] && [ "$(stat -c %s "$TERRAIN_FILE")" -gt 200 ]; then pass "G1c terrain cache file written ($(stat -c %s "$TERRAIN_FILE") bytes)"; else fail "G1c terrain cache file missing or empty"; fi

echo "== phase 3: restart, cache must be replayed"
start_server "$OUT/server3.log"
wait_for "$OUT/server3.log" "terrain cache for '.*': restored" 120 || echo "server3: cache line not seen (it appears when the first chunk or player touches the world)"
check "G1c restart restores chunks from disk"        "$OUT/server3.log" "terrain cache for '.*': restored [1-9][0-9]* chunks"
stop_server "$OUT/server3.log"

H debug > "$OUT/hmc-debug-end.txt" 2>&1 || true
echo; cat "$OUT/summary.md"
[ "$FAILS" -eq 0 ] || { echo "$FAILS check(s) failed"; exit 1; }
