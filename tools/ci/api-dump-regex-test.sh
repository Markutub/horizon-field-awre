#!/usr/bin/env bash
# Regression test for tools/ci/api-dump.sh: every `dump <name> '<regex>'` line must match at least the class it exists to dump.
# Background: the `screenshot` regex once had a doubled backslash (`\\.class`) and matched nothing, so screenshot.javap.txt would have been
# empty on every CI run. This test extracts the regexes from the script itself, so it cannot drift from it.
# Usage: bash tools/ci/api-dump-regex-test.sh
set -u
cd "$(dirname "$0")/../.."
declare -A MUST=(
  [blaze3d]='com/mojang/blaze3d/systems/RenderSystem.class'
  [projection]='net/minecraft/client/renderer/GameRenderer.class'
  [camera]='net/minecraft/client/Camera.class'
  [rendertypes]='net/minecraft/client/renderer/RenderType.class'
  [screenshot]='net/minecraft/client/Screenshot.class'
  [worldheight]='net/minecraft/client/multiplayer/ClientLevel.class'
)
# must NOT match: a different class and a non-class entry (guards against a regex that matches everything)
NOT='net/minecraft/world/item/Items.class
net/minecraft/client/Screenshot.txt'
fails=0; seen=0
while IFS= read -r line; do
  [[ "$line" =~ ^dump[[:space:]]+([a-z0-9]+)[[:space:]]+\'([^\']*)\' ]] || continue
  name=${BASH_REMATCH[1]}; re=${BASH_REMATCH[2]}; seen=$((seen+1))
  want=${MUST[$name]:-}
  if [ -z "$want" ]; then echo "FAIL $name: no expected class registered in this test (add one)"; fails=$((fails+1)); continue; fi
  if printf '%s\n' "$want" | grep -Eq -- "$re"; then echo "ok   $name matches $want"; else echo "FAIL $name: regex /$re/ does not match $want"; fails=$((fails+1)); fi
  if printf '%s\n' "$NOT" | grep -Eq -- "$re"; then echo "FAIL $name: regex matches an unrelated entry"; fails=$((fails+1)); fi
done < tools/ci/api-dump.sh
[ "$seen" -ge 6 ] || { echo "FAIL only $seen dump lines found (expected >= 6): parser out of date?"; fails=$((fails+1)); }
if [ "$fails" = 0 ]; then echo "api-dump-regex tests: all passed ($seen regexes)"; else echo "api-dump-regex tests: $fails FAILED"; exit 1; fi
