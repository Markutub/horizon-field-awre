# Compatibility plan: Sodium, Iris, Vulkan, Bobby, worldgen mods

Status words used here: **DONE** (code + passing pure-Java tests), **WRITTEN** (code exists, only syntax-checked, needs CI), **TODO**, **UNKNOWN** (needs a real jar/log to answer). The per-cell test results live in `COMPATIBILITY.md`; this file is the plan and design.

Goal: AWRE keeps drawing distant terrain with these installed, without rebuilding the engine per mod or per Minecraft version.

## 0. Ground rules

1. Engine code stays pure Java (`horizon-core`, `awre-protocol`, `awre-stream`). Only `awre-fabric` and `awre-paper` know Minecraft.
2. Compatibility decisions are pure and tested (`RenderPolicy`); adapters only answer "is mod X loaded?".
3. Never claim support that was not run. A target is "supported" only after its row in `COMPATIBILITY.md` says PASS with a date.
4. A failure in a compat feature must degrade to "AWRE draws nothing", never to a crash or a broken vanilla scene.

## 1. Prerequisite (blocks everything below)

G2 (meshes built) and G3 (GPU upload + draw submitted) must pass on plain 1.21.11 in CI first. G2 fix is WRITTEN (dimension id). G3 depends on `HorizonRenderer`'s open `API-ASSUMPTION`s. Also open: the far-plane clipping limitation (terrain past the projection far plane is clipped regardless of mods): needs an AWRE-owned projection matrix with an extended far plane, and a fog blend at the seam with vanilla. **TODO**, applies to every target.

## 2. Targets

### 2.1 Sodium (client)
- Design: AWRE draws its own pass from a Fabric API world-render event, independent of Sodium's chunk renderer. Expected to work.
- Risks: event timing relative to Sodium's passes; depth buffer state. UNKNOWN until run.
- Work: add `sodium` + Fabric API to the CI mods dir (second client run, "client C"); expect G3 identical to vanilla.
- Done when: G2 and G3 pass with Sodium loaded.

### 2.2 Bobby (client)
- Design: Bobby turns cached chunks into real chunks inside the render-distance setting. AWRE already skips tiles fully inside `renderDistance * 16`, so the two meet at that border with no overlap. No code needed if that holds.
- Risks: Bobby chunks beyond the *server* view distance but inside the client setting are not in AWRE's "vanilla" assumption in any special way; confirm no gap or double layer at the border. Seam shading differences (vanilla lighting vs AWRE flat colour) will be visible.
- Work: CI run with Bobby; in-game check at the border. Done when no gap/double layer is observed.

### 2.3 Iris / shader packs (client) - the hard one
- Today: AWRE draws after the scene with `core/position_color`, so packs do not shade it (no shadows, mismatched lighting and fog).
- Step 1, WRITTEN: `ShaderPackBridge` + `IrisBridge` (reflection on `net.irisshaders.iris.api.v0.IrisApi`, API-ASSUMPTION) only detect "pack active" and log it.
- Step 2, TODO/UNKNOWN: draw inside the pack's pipeline. Needs reading Iris's source for 1.21.11 to learn how it maps pipelines to pack programs and whether a mod can register a terrain-class pipeline. Candidate approaches, in order of preference: (a) use Iris's public API if it offers a hook for custom world geometry; (b) a pipeline Iris maps to a terrain program, drawn in Iris's terrain phase; (c) mixin into Iris (fragile, last resort); (d) fall back to an unshaded but fog/sky-tinted draw (never ship (d) silently: log it).
- Shadow pass: decide whether AWRE terrain is drawn into it (`renderingShadowPass()`); default no.
- Done when: with 2 popular packs, terrain is lit consistently and does not flicker or z-fight. Until then the documented limitation stands.

### 2.4 Vulkan / VulkanMod / Minecraft Vulkan backend (client)
- Design: `HorizonRenderer` uses only the game's GPU layer (no raw GL), so it should follow whichever backend is active. 26.x changes to class names are an adapter rewrite, not an engine change.
- WRITTEN, tested with a fake GPU: deferred buffer free (`MeshResidency` `freeDelayFrames`, mod uses 3), because explicit APIs may still read a buffer drawn in earlier frames. Whether 3 is enough on a real Vulkan backend: UNKNOWN.
- UNKNOWN: does VulkanMod implement the GPU classes `HorizonRenderer` calls? Answer by running it (CI client "client D" with VulkanMod, or manually).
- UNKNOWN: 26.x API names and which backend is default. Method: read the real jar with `javap` / mappings; do not port from memory. Port once to the latest stable release after 1.21.11 is green.
- Done when: G3 passes under VulkanMod and under the game's own Vulkan backend (when it exists for the target version).

### 2.5 Worldgen mods / datapacks / plugins (server + data)
What already works: the server reads chunks that are loaded, through Paper snapshots, so it does not care which generator made them. Namespaced world key on the wire (`World#getKey()`) supports custom dimensions.

Gaps, in priority order:
1. **Biome tint (TODO, tile format change).** Colours come from block id only, so grass/foliage/water in custom biomes look wrong. Plan: add an optional per-tile biome-index plane + a per-world biome palette (like the block palette, capability bit 16), and a pure `BiomeTint` table (biome id -> grass/foliage/water ARGB, with overrides). Version the tile format; old clients ignore the plane. Needs a protocol bump and tests (round trip, old-client skip).
2. **Block colours for unknown blocks (WRITTEN).** `ColorOverrides` + `config/awre-colors.txt` (`namespace:block = #RRGGBB`, prefix rules with `*`). Limitation: id only, no block-state properties, so plugin custom blocks that share one carrier block (e.g. ItemsAdder on note blocks) cannot be told apart.
3. **Height range (TODO).** `ClientHorizonView.SKIRT_FLOOR_Y = -64` is a constant. Datapack dimensions with a lower `min_y` show a thin gap under skirts. Plan: send min/max Y per world in the handshake or palette update; use it for the skirt floor. Also confirm tile height encoding holds the tallest worldgen mod (short fits +/-32767; verify the LOD codec does not clamp).
4. **Coverage (docs only).** Terrain appears only where chunks were generated. For far terrain, pregenerate (Chunky or similar); AWRE does not generate chunks. State this in the README.
5. **Slow/odd generators.** Capture is per loaded chunk, so a generator that fills chunks late (async worldgen) can send a tile before the chunk is final. Plan: the existing revision mechanism re-sends on change; test with a plugin that edits chunks after load.

Server-side compatibility otherwise: Paper API only, no NMS. TODO: proxy test (Velocity/BungeeCord must forward the `awre:main` plugin channel). Java version requirements differ between Minecraft generations; check before building for 26.x.

## 3. Policy for conflicting mods (WRITTEN, `RenderPolicy`)

`-Dawre.render=auto|on|off`. `auto`: drawing OFF only when Distant Horizons or Voxy is loaded (same terrain, two layers). Sodium, Iris, VulkanMod, Bobby: drawing stays ON, mod is logged. `on` forces, `off` disables; protocol and tile storage run in every mode.

## 4. Order of work

1. G2 green in CI (dimension fix) -> G3 (draw) on vanilla 1.21.11.
2. Own projection / far plane + fog seam.
3. CI client runs: Sodium, then Bobby, then VulkanMod. Record results in `COMPATIBILITY.md`.
4. Biome tint (protocol bump), height range in handshake.
5. Iris step 2 (needs reading Iris source).
6. 26.x port of the adapter layer from the real jar.

## 5. Files

| Item | File | Status |
|---|---|---|
| Render policy | `awre-stream/.../compat/RenderPolicy.java` (+ `RenderPolicyTest`) | DONE |
| Shader pack interface | `awre-stream/.../compat/ShaderPackBridge.java` | DONE (interface) |
| Iris detection | `awre-fabric/.../IrisBridge.java` | WRITTEN, API-ASSUMPTION |
| Colour overrides | `horizon-core/.../terrain/ColorOverrides.java`, hook in `BlockColors` (+ `ColorOverridesTest`) | DONE |
| Overrides file loading, policy wiring, shader log | `awre-fabric/.../AwreClientMod.java` | WRITTEN |
| Deferred free | `MeshResidency`, `HorizonDrawPlanner` | DONE |
| Client lifecycle | `ClientRuntime` | DONE |
| Biome tint | - | TODO |
| Height range | - | TODO |
