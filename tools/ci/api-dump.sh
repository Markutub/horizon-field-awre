#!/usr/bin/env bash
# Dumps the REAL class signatures of the Minecraft jar the Fabric build compiled against (Mojang names), so API-ASSUMPTIONs
# (projection / far plane, render pass, fog, Fabric world-render events, any Vulkan classes) are read from the jar instead of guessed.
# Usage: tools/ci/api-dump.sh [out-dir]   (run AFTER `gradle -p awre-fabric build`, which downloads + remaps Minecraft)
# Never fails the build: it writes what it finds and a NOTES.txt saying what it could not find. Needs JDK (javap) and unzip.
set -uo pipefail
OUT=${1:-mc-api-dump}; mkdir -p "$OUT"
NOTES="$OUT/NOTES.txt"; : > "$NOTES"
note() { echo "$*" | tee -a "$NOTES"; }

ROOTS=()
for r in "$HOME/.gradle" awre-fabric/.gradle awre-fabric/build; do [ -d "$r" ] && ROOTS+=("$r"); done
[ ${#ROOTS[@]} -gt 0 ] || { note "no gradle/loom directories found; nothing to dump"; exit 0; }

# a jar "is the mapped Minecraft" if it contains Mojang-named GameRenderer (obfuscated jars do not)
has_class() { unzip -Z1 "$1" 2>/dev/null | grep -qx "$2"; }
MC_JAR=""
while IFS= read -r j; do
  if has_class "$j" 'net/minecraft/client/renderer/GameRenderer.class'; then MC_JAR=$j; break; fi
done < <(find "${ROOTS[@]}" -name '*.jar' -size +5M \( -iname '*minecraft*' -o -path '*loom*' \) 2>/dev/null | sort)
if [ -z "$MC_JAR" ]; then
  note "no jar containing net/minecraft/client/renderer/GameRenderer.class found; candidate jars:"
  find "${ROOTS[@]}" -name '*.jar' -size +5M \( -iname '*minecraft*' -o -path '*loom*' \) 2>/dev/null | head -30 | tee -a "$NOTES"
  exit 0
fi
note "minecraft jar: $MC_JAR"
unzip -Z1 "$MC_JAR" | grep '\.class$' | sort > "$OUT/class-index.txt"
note "classes in jar: $(wc -l < "$OUT/class-index.txt")"

dump() { # dump <name> <class-path-regex>   (regex matched against entries like net/minecraft/client/Camera.class)
  local name=$1 re=$2 list="$OUT/$1.classes"
  grep -E "$re" "$OUT/class-index.txt" | sed 's/\.class$//' > "$list"
  local n; n=$(wc -l < "$list")
  if [ "$n" -eq 0 ]; then note "$name: no classes matched /$re/"; rm -f "$list"; return; fi
  # shellcheck disable=SC2046
  javap -p -cp "$MC_JAR" $(tr '/' '.' < "$list" | tr '\n' ' ') > "$OUT/$name.javap.txt" 2>"$OUT/$name.javap.err" || note "$name: javap reported errors (see $name.javap.err)"
  note "$name: $n classes, $(wc -l < "$OUT/$name.javap.txt") lines"
}

dump blaze3d       '^com/mojang/blaze3d/.*\.class$'
dump projection    '^net/minecraft/client/renderer/([a-z]+/)?[A-Za-z0-9_]*(Projection|Fog|DynamicUniforms|GameRenderer|LevelRenderer|RenderPipelines|Frustum|LevelTargetBundle)[A-Za-z0-9_$]*\.class$'
dump camera        '^net/minecraft/client/(Camera|Minecraft|Options)\.class$'
dump rendertypes   '^net/minecraft/client/renderer/(rendertype/)?(RenderType|RenderStateShard|RenderSetup|RenderTypes)[A-Za-z0-9_$]*\.class$'
# world height accessors (per-dimension min/max build height, to replace the constant skirt floor -64) and the client level
dump screenshot   '^net/minecraft/client/Screenshot[A-Za-z0-9_$]*\.class$'      # needed to write the in-game frame capture from real signatures (docs/NEXT_PHASE_PROMPT.md task 1)
dump worldheight   '^net/minecraft/(world/level/(LevelHeightAccessor|Level|dimension/DimensionType)|client/multiplayer/ClientLevel)\.class$'

# Vanilla shader SOURCES (assets are usually in the same jar or in a separate client jar): needed to check what core/position_color does
# (e.g. whether it applies ModelOffset) and which names dynamictransforms.glsl / projection.glsl declare, which awre_terrain.vsh relies on.
mkdir -p "$OUT/shaders"
SHADER_JAR=""
for j in "$MC_JAR" $(find "${ROOTS[@]}" -name '*.jar' -size +5M 2>/dev/null | sort); do
  if unzip -Z1 "$j" 2>/dev/null | grep -qx 'assets/minecraft/shaders/core/position_color.vsh'; then SHADER_JAR=$j; break; fi
done
if [ -n "$SHADER_JAR" ]; then
  unzip -o -q "$SHADER_JAR" 'assets/minecraft/shaders/core/position_color.*' 'assets/minecraft/shaders/include/*' -d "$OUT/shaders" 2>/dev/null || true
  note "vanilla shader sources from $SHADER_JAR: $(find "$OUT/shaders" -type f | wc -l) files (position_color.vsh/.fsh, include/*.glsl)"
  note "position_color.vsh uses ModelOffset: $(grep -c 'ModelOffset' "$OUT/shaders/assets/minecraft/shaders/core/position_color.vsh" 2>/dev/null || echo unknown) occurrence(s)"
else
  note "no jar with assets/minecraft/shaders/core/position_color.vsh found; shader sources not dumped"
fi

# any Vulkan or alternative-backend classes in the game itself (tells whether this version has a Vulkan backend at all)
grep -iE 'vulkan|vk[A-Z]|backend' "$OUT/class-index.txt" > "$OUT/backend-classes.txt" || true
note "backend/vulkan-named classes in the game jar: $(wc -l < "$OUT/backend-classes.txt")"

# Fabric API world-render events (separate jar)
FAPI_JAR=""
while IFS= read -r j; do
  if has_class "$j" 'net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderEvents.class'; then FAPI_JAR=$j; break; fi
done < <(find "${ROOTS[@]}" -name '*.jar' \( -iname '*rendering*' -o -iname '*fabric-api*' \) 2>/dev/null | sort)
if [ -n "$FAPI_JAR" ]; then
  note "fabric rendering jar: $FAPI_JAR"
  # shellcheck disable=SC2046
  javap -p -cp "$FAPI_JAR" $(unzip -Z1 "$FAPI_JAR" | grep -E '^net/fabricmc/fabric/api/client/rendering/v1/world/.*\.class$' | sed 's/\.class$//' | tr '/' '.' | tr '\n' ' ') > "$OUT/fabric-world-render.javap.txt" 2>/dev/null || note "fabric-world-render: javap errors"
  note "fabric-world-render: $(wc -l < "$OUT/fabric-world-render.javap.txt") lines"
else
  note "fabric rendering jar with WorldRenderEvents not found"
fi
exit 0
