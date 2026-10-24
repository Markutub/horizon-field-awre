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

### 1.1 Far plane (WRITTEN from the real API; first CI run pending)

Facts from the CI API dump of the 1.21.11 jar (`mc-api-dump`): `GameRenderer#getDepthFar()`, `getProjectionMatrix(float)`, `PROJECTION_Z_NEAR`; `PerspectiveProjectionMatrixBuffer#getBuffer(Matrix4f)`; `RenderSystem#setProjectionMatrix(GpuBufferSlice, ProjectionType)`, `getProjectionMatrixBuffer()`, `getProjectionType()`; Fabric `START_MAIN`, `BEFORE_TRANSLUCENT`, `END_MAIN`. The jar has **0 Vulkan/backend-named classes**: 1.21.11 is OpenGL-only behind the GPU layer, so "Vulkan" on this version can only mean VulkanMod. A Vulkan-capable game needs its own dump from a 26.x build.

Still NOT verified: that vanilla's far plane really clips AWRE terrain (the dump shows the API, not the clipping), that `getProjectionMatrix` takes degrees, and that `getBuffer` is an encoder command (done before the pass opens, like the uniforms).

What the code does (`HorizonRenderer#extendedProjection`, pure math in `ProjectionExtender`, 9 unit tests): take vanilla's own level projection, replace only its two depth entries (m22, m32) for a larger far plane (auto = `viewMaxDistance*1.1 + 512`, never below vanilla's, capped at 200000), upload it with `PerspectiveProjectionMatrixBuffer`, set it with `RenderSystem.setProjectionMatrix` for the AWRE pass only, and ALWAYS restore vanilla's buffer/type afterwards (finally). x/y scale (FOV, aspect) is untouched so AWRE terrain lines up with vanilla. The matrix is VERIFIED, not assumed, to be a standard OpenGL perspective with the expected near plane (`impliedNear = m32/(m22-1)`); anything else (zero-to-one, reversed-Z, other near) is refused with a one-time warning and vanilla's projection is kept. `-Dawre.far=auto|off|<blocks>`; `off` = previous behaviour. CI logs `[AWRE] far plane: vanilla=.. awre=..` or `far plane NOT extended` (informational line in `integration.sh`).

Known costs, by construction (not bugs):
- FOV: only the base FOV option is used. While sprint/potion FOV effects are active, AWRE terrain uses a slightly different FOV than vanilla. Proper fix needs the live FOV (private `GameRenderer#getFov`): an accessor mixin.
- Depth: two far planes give slightly different depth values, so near the seam AWRE can wrongly draw over vanilla geometry that is only a little nearer (condition `1/dV - 1/dA < 1/f1 - 1/f2`; about a 33% band beyond the vanilla edge when f1 = 4x render distance). Mitigations if it shows: `RenderPipeline.Builder#withDepthBias`, or drawing AWRE at `START_MAIN` with its own depth.
- Depth precision at thousands of blocks with near 0.05 is poor (surfaces closer than ~15 blocks at 5000 blocks can z-fight). LOD tiles do not overlap, so this should only touch skirts.
- No fog is applied to AWRE terrain (`position_color`), so there is a visible edge where vanilla fog ends. Separate task.

## 2. Targets

### 2.1 Sodium (client)
- Design: AWRE draws its own pass from a Fabric API world-render event, independent of Sodium's chunk renderer. Expected to work.
- Risks: event timing relative to Sodium's passes; depth buffer state. UNKNOWN until run.
- Work (WRITTEN): run the `integration` job via workflow_dispatch with `compat_mods` = `sodium` (see 3.1). Expect the same render counters as plain 1.21.11.
- Done when: G2 and G3 pass with Sodium loaded.

### 2.2 Bobby (client)
- Design: Bobby turns cached chunks into real chunks inside the render-distance setting. AWRE already skips tiles fully inside `renderDistance * 16`, so the two meet at that border with no overlap. No code needed if that holds.
- Risks: Bobby chunks beyond the *server* view distance but inside the client setting are not in AWRE's "vanilla" assumption in any special way; confirm no gap or double layer at the border. Seam shading differences (vanilla lighting vs AWRE flat colour) will be visible.
- Work: CI run with Bobby; in-game check at the border. Done when no gap/double layer is observed.

### 2.3 Iris / shader packs (client) - the hard one
- Before: AWRE drew after the scene with a vanilla-style shader, so packs did not shade it (no shadows, mismatched lighting/fog).
- Found (secondary source, NOT checked against an Iris build for 1.21.11): Iris's public API v0 (revision 3) has `IrisApi.assignPipeline(RenderPipeline, IrisProgram)`, "which allows mods to associate a Mojang RenderPipeline with specific Iris shader keys", with an `IrisProgram` enum (TERRAIN, ENTITIES, HAND, CLOUDS...) and `IrisShadowProgram`; plus `isShaderPackInUse()`, `isRenderingShadowPass()`. Source: DeepWiki summary of IrisShaders/Iris (commit 37c02037) and a Sodium-options issue pointing at the same `IrisApi`. Iris is LGPL-3 and calling its public API by reflection needs no Iris code in AWRE.
- WRITTEN (tested only against fakes with Iris's package/names, `IrisReflectionTest`): `IrisReflection` (pure, by reflection) + `ShaderPackBridge#assignTerrainPipeline`; at startup, when Iris is loaded, `HorizonRenderer#setShaderBridge` asks Iris to render the PLAIN terrain pipeline with the pack's TERRAIN program and logs `[AWRE] Iris assignPipeline(...): ACCEPTED | NOT available`. While a pack is active the plain pipeline is used (the reveal shader cannot run inside a pack). `-Dawre.iris=off` skips the assignment (rollback). The compat CI run for `sodium+iris` prints that log line as a COMPAT NOTE, which answers whether the installed Iris has the method.
- UNKNOWN until run with a real pack: (1) whether Iris 1.21.11 builds have `assignPipeline` at all (API may postdate the build); (2) whether Iris can map a `POSITION_COLOR` pipeline to a TERRAIN program (that program normally expects the terrain vertex format with UV/light/normals; Iris chooses shaders by `ShaderKey.findBestMatch`, so a colour-only format may map to a basic program instead); (3) whether the AWRE pass lands in the right pack stage (G-buffer vs composite) since it is drawn at END_MAIN; (4) shadows: AWRE terrain is not drawn into the shadow map; (5) the vertex colours are baked block colours, not textures, so pack effects that rely on normals/PBR have nothing to work with. Treat Iris as "loads and does not crash" until a human looks at it with a pack.
- Fallback if (2) fails: tint/fog-match AWRE terrain to the pack's sky colour, or leave unshaded and say so.
- Done when: with 2 popular packs terrain is lit consistently, no flicker, no z-fight. Until then the documented limitation stands.

### 2.4 Vulkan / VulkanMod / Minecraft Vulkan backend (client)
- Design: `HorizonRenderer` uses only the game's GPU layer (no raw GL), so it should follow whichever backend is active. (API dump: 1.21.11 has no Vulkan classes at all; only VulkanMod could provide one.) 26.x changes to class names are an adapter rewrite, not an engine change.
- WRITTEN, tested with a fake GPU: deferred buffer free (`MeshResidency` `freeDelayFrames`, mod uses 3), because explicit APIs may still read a buffer drawn in earlier frames. Whether 3 is enough on a real Vulkan backend: UNKNOWN.
- UNKNOWN: does VulkanMod implement the GPU classes `HorizonRenderer` calls? Answer by running it (CI client "client D" with VulkanMod, or manually).
- UNKNOWN: 26.x API names and which backend is default. Method: read the real jar with `javap` / mappings; do not port from memory. Port once to the latest stable release after 1.21.11 is green.
- Done when: G3 passes under VulkanMod and under the game's own Vulkan backend (when it exists for the target version).

### 2.5 Worldgen mods / datapacks / plugins (server + data)
What already works: the server reads chunks that are loaded, through Paper snapshots, so it does not care which generator made them. Namespaced world key on the wire (`World#getKey()`) supports custom dimensions.

Gaps, in priority order:
1. **Biome tint (WRITTEN, no protocol change).** Grass, foliage and water carry their biome inside the palette key (`minecraft:grass_block/biome/minecraft:desert`, `BiomeTint.tag`), so tile codecs, persistence and the palette message are unchanged (verified against the real `PaletteUpdate.validKey` and a PALETTE round trip). `TerrainAnalyzer` tags on the server (`ChunkView.biomeKey`, implemented in `AwreChunkView` through `ChunkSnapshot#getBiome`, API-ASSUMPTION); the client `BlockColors` splits the key and asks `BiomeTint`. Built-in colours are APPROXIMATE (from memory); unknown biomes from worldgen datapacks/plugins get a keyword heuristic ("desert", "swamp", "frozen"...), then plains. Exact colours: `config/awre-biomes.txt`, `biome_id = grass:#RRGGBB foliage:#RRGGBB water:#RRGGBB` (any subset). Caveats: the palette grows with (tintable blocks x biomes) (limit 32768 entries, fine); an existing on-disk terrain cache was written without tags and keeps plain colours until its chunks are re-observed; only the top block of each column is tinted, only block-level (no biome blending at borders); a Paper snapshot that cannot give the biome simply leaves the plain key.
2. **Block colours for unknown blocks (WRITTEN).** `ColorOverrides` + `config/awre-colors.txt` (`namespace:block = #RRGGBB`, prefix rules with `*`). Limitation: id only, no block-state properties, so plugin custom blocks that share one carrier block (e.g. ItemsAdder on note blocks) cannot be told apart.
3. **Height range (TODO).** `ClientHorizonView.SKIRT_FLOOR_Y = -64` is a constant. Datapack dimensions with a lower `min_y` show a thin gap under skirts. Plan: send min/max Y per world in the handshake or palette update; use it for the skirt floor. Also confirm tile height encoding holds the tallest worldgen mod (short fits +/-32767; verify the LOD codec does not clamp).
4. **Coverage (docs only).** Terrain appears only where chunks were generated. For far terrain, pregenerate (Chunky or similar); AWRE does not generate chunks. State this in the README.
5. **Slow/odd generators.** Capture is per loaded chunk, so a generator that fills chunks late (async worldgen) can send a tile before the chunk is final. Plan: the existing revision mechanism re-sends on change; test with a plugin that edits chunks after load.

Server-side compatibility otherwise: Paper API only, no NMS. TODO: proxy test (Velocity/BungeeCord must forward the `awre:main` plugin channel). Java version requirements differ between Minecraft generations; check before building for 26.x.

## 3.1 Compat clients in CI (WRITTEN, opt-in, informational)

`workflow_dispatch` input `compat_mods`, e.g. `sodium sodium+iris bobby vulkanmod` (groups split on space/comma, `+` joins mods that load together; Iris needs Sodium, so use `sodium+iris`). `integration.sh` adds each group's mods through HeadlessMc's Modrinth support (`mod add mod <slug> fabric <mc>`), starts one client per group against the synthetic server, and records per group: no crash, handshake OK, "compat target loaded: <slug>" (RenderPolicy detection), G2 meshes, the render gate, no AWRE exceptions. A mod that cannot be installed for the version is reported as SKIPPED, not failed. Results appear in the summary under "Compat clients" as `COMPAT PASS/FAIL/NOTE`; they fail the job only with `COMPAT_STRICT=1`. Limits: headless counters prove the renderer RAN beside the mod, not that pixels are correct; Iris is loaded WITHOUT a shader pack, so this does not test shaded rendering. Logic tested offline with a mock HeadlessMc (success, skip, crash paths) and `tools/ci/compat-lib-test.sh`.

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
| Biome tint | `horizon-core/.../terrain/BiomeTint.java`, `BlockColors`, `ChunkView#biomeKey`, `TerrainAnalyzer`, `AwreChunkView`, `awre-biomes.txt` loader (+ `BiomeTintTest`, `BiomeTagProtocolTest`) | DONE in core/protocol tests; Paper/Fabric parts WRITTEN |
| Height range | - | TODO |
