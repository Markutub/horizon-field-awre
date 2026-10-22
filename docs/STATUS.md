# Project Status

Verified against the uploaded Session 16 source snapshot on 2026-10-21. This is a local source snapshot, not a claim about the current GitHub branch or latest CI run.

## Current objective

**PHASE_01 — render streamed AWRE terrain in the Fabric client.** The immediate missing link is between `ClientHorizonView.Drawable` meshes and Minecraft's 1.21.11 world-render lifecycle/GPU resources.

## Feature and test map

| Feature | Implementation | Test / evidence | Status |
|---|---|---|---|
| Height summaries and visibility sweep (legacy vanilla research path) | `horizon-core/src/main/java/dev/horizonfield/core/{TerrainAnalyzer,HeightPyramid,VisibilitySweep}.java` | `tools/verify-core.sh`; 69 tests passed in this session | Implemented; pure-Java verified |
| LOD tile model, codec, terrain store and mesher | `horizon-core/src/main/java/dev/horizonfield/core/terrain/` | `TerrainStoreTest`, `LodTileCodecV2Test`, `TerrainMesherTest`; via `tools/verify-core.sh` | Implemented; pure-Java verified |
| Protocol negotiation, framing, fragmentation, palette, DROP and revision checks | `awre-protocol/src/main/java/dev/awre/protocol/` | `tools/verify-awre.sh`; 67 tests passed in this session | Implemented; pure-Java verified |
| Streaming planner / ingest / client view / persistence glue | `awre-stream/src/main/java/dev/awre/stream/` | `tools/verify-stream.sh`; 23 tests passed in this session | Implemented; pure-Java verified |
| Paper capture and terrain persistence | `awre-paper/src/main/java/dev/awre/paper/` | `.github/workflows/ci.yml`, `tools/ci/integration.sh` contain integration checks; no CI run executed in this session | Implemented in source; runtime status not re-verified here |
| Fabric networking and tile meshing selection | `awre-fabric/src/main/java/dev/awre/fabric/AwreClientMod.java`; `ClientHorizonView` in `awre-stream` | Session 16 handoff describes G2 log checks; local script does not compile against Minecraft/Fabric APIs | Partial; source-only in this session |
| Actual AWRE GPU/world rendering | Session 18: `HorizonRenderer` (blaze3d calls) + pure-Java `HorizonDrawPlanner`/`MeshResidency`/`VertexPacker`/`CameraRelative` | Planner/residency/precision/LOD-swap: `RenderCoreTest` (9) + 2 mesher tests, pass. `HorizonRenderer`: syntax-parsed only | Written, **not compiled, not run in-game** |
| Legacy vanilla delivery / ghost chunks / display entities | `horizon-paper/.../delivery/DeliveryChannel.java`, `docs/PROTOCOL.md`, `docs/EXPERIMENTS.md` | Experiments are documented as hypotheses/gates, not proven by the pure-Java suite | Experimental / unverified; do not claim beyond-render-distance delivery |
| Gradle build and Paper/Fabric API compatibility | Root and separate Fabric Gradle builds; CI workflow | Not run here (network/dependency resolution and actual CI unavailable) | Unverified in this session |

## Session 26 (G1 audit) summary

Full report: `docs/AUDIT_G1.md`. Three confirmed defects fixed in source with regression tests: (D1) capture requests beyond the 4096-queue were silently lost (`CaptureBacklog`; premise test shows 665 of 4761 chunks lost); (D2) terrain changed by anything but player place/break was never re-observed (new event handlers + `stream.revalidate-seconds` sweep; pipeline test `RevalidationSweepTest`); (D3) server heap 1.32 KB/chunk unbounded growth, measured, now 0.43 KB/chunk via packed `ColumnTile`. Also E1: snapshots were taken without biome data. Pure-Java: core 93/93, protocol 67/67, stream 49/49. Paper/Fabric changes are syntax-checked only (CI is the compile check). Main-thread cost of capture/sweep UNMEASURED. Next: run CI, then measure on a real server (see AUDIT_G1 section 7).

## Session 21 (PHASE_03 start) summary

Measured tile-border cracks on synthetic terrain (`tools/bench/CrackBench.java`, `docs/crackbench-output.txt`): fixed-depth skirts leave thousands of open border samples on mountainous terrain; fixed by skirts to the floor (`meshLocalToFloor`). core 73/73, protocol 67/67, stream 39/39. Not real-terrain data; not verified in game. See `docs/phases/PHASE_03.md`.

## Session 19 (PHASE_02) summary

User reported "all green" (not independently visible). Added `DimensionGate` (fixes old-dimension meshes being re-uploaded after a dimension change), `WorldCorrectnessTest` (6 tests incl. a 400-iteration randomized, mutation-checked coverage property). Pure-Java: core 71/71, protocol 67/67, stream 38/38. Details and open items: `docs/phases/PHASE_02.md`.

## Session 18 (PHASE_01) summary

Render path implemented in source; see `docs/phases/PHASE_01.md` for the PASS/FAIL/NOT VERIFIED table and the list of API assumptions. Pure-Java: core 71/71, protocol 67/67, stream 32/32. Fabric/Gradle build, CI integration and in-game rendering: NOT RUN (no network in the sandbox). Next: push, read the `build` job, fix `HorizonRenderer` compile errors, then run the manual procedure and check the far-plane clipping limitation.

## Completed in the earlier documentation session (Session 17)

- Added this status map and the concise AI context entry point.
- Added architecture overview/decisions, development commands, roadmap, and phase handoff documents.
- No application code changed.

## Tests executed in this session

- `bash tools/verify-core.sh` — **PASS**, 69 passed, 0 failed.
- `bash tools/verify-awre.sh` — **PASS**, 67 passed, 0 failed.
- `bash tools/verify-stream.sh` — **PASS**, 23 passed, 0 failed.
- Root Gradle build — **NOT RUN**.
- Separate Fabric Gradle build — **NOT RUN**.
- Paper + Fabric CI/in-game rendering — **NOT RUN**.

## Known defects, risks, and open decisions

- `AwreClientMod` creates and updates `ClientHorizonView` but has no actual GPU draw path.
- The Session 16 handoff states Minecraft/Fabric API calls were not compiled against the exact 1.21.11 APIs. The first Gradle/CI build may expose mapping or lifecycle errors.
- Root Gradle `settings.gradle.kts` does not include `awre-fabric`; CI intentionally builds it separately. Preserve that boundary unless a tested reason requires changing it.
- `horizon-paper` is a separate legacy/vanilla research prototype. Do not merge its `ColumnTile` / `VisibilitySweep` delivery assumptions into AWRE's `LodTile` modded-client path without an explicit decision.
- Existing `docs/HANDOFF.md` reports a user-provided green CI run but notes uncertainty about whether the latest compaction/codec changes were included. No remote run was independently checked in this session.
- Client render thread ownership, GPU resource disposal, camera-relative coordinates, dimension changes, and render-pipeline compatibility need implementation and validation.

## Next task

Implement the smallest real Fabric render adapter that consumes `ClientHorizonView.update(...)` results using Minecraft 1.21.11's supported render lifecycle. First run the separate Fabric build to validate current APIs; then add the draw path and resource lifecycle, focused tests where possible, and a reproducible in-game smoke procedure. Update `docs/phases/PHASE_01.md`, this file, and `AI_CONTEXT.md` only with verified outcomes.


## Session 22 (CI log from session 21 phase 03)

- G2 failure cause: the server put the Bukkit world name ("world") on the wire; the client filters on the level's dimension key
  ("minecraft:overworld"), so every tile was filtered out (received=2120, candidates=0, mesh_built=0). Server now sends
  `World#getKey()` (`AwreChannel.wireDimension`). **Not yet confirmed by CI.**
- `MeshResidency` has optional deferred free (`freeDelayFrames`, `endFrame()`, `flush()`); the mod uses 3 frames. Default 0 keeps old behaviour.
- Client logic moved out of `AwreClientMod` into pure `ClientRuntime` (tested); the mod is now a thin adapter.
- New `-Dawre.render=auto|on|off`; auto disables drawing when iris/distanthorizons/voxy/bobby/vulkanmod is loaded.
- New client warning when tiles are held but none match the current dimension.
- Pure-Java suites: core 73/73, protocol 67/67, stream 43/43. Fabric and Paper modules were only syntax-checked (no network): CI is the compile check.
- New `docs/COMPATIBILITY.md` (every cell NOT TESTED unless stated).
- Compatibility scaffolding (session 22b): `docs/COMPAT_PLAN.md`; `RenderPolicy` (Sodium/Iris/VulkanMod/Bobby never disable drawing; Distant Horizons/Voxy do in auto); `ShaderPackBridge` + reflective `IrisBridge`; `ColorOverrides` + `config/awre-colors.txt`. Suites: core 76/76, protocol 67/67, stream 46/46. Fabric side only syntax-checked.
- Run 22 (CI): G2 PASS, GPU upload PASS (hundreds of buffers resident), draw FAILED at stage `writeTransform`: "Close the existing render pass before performing additional commands". CI-confirmed: dynamic-uniform writes are encoder commands and must happen BEFORE the render pass is created. `HorizonRenderer.render` now writes every per-draw uniform first, then opens the pass and only binds. Not yet re-run. Possible next hurdles: size limit of the dynamic-uniform buffer with hundreds of per-draw writes; far-plane clipping; seam with vanilla.
- Biome tint (session 25, while waiting for CI): grass/foliage/water palette keys carry the biome; `BiomeTint`, `awre-biomes.txt`, server tagging in `TerrainAnalyzer`/`AwreChunkView`. No protocol or tile-format change. Suites: core 82, protocol 67, stream 48 (after BiomeTagProtocolTest). Paper `ChunkSnapshot#getBiome(...).getKey()` is an API-ASSUMPTION: CI is the compile check.
