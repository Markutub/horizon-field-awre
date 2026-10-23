# Project Status

## 2026-10-22 (Session 27) evidence addendum (LATEST)

Older sections below are historical (Session 16-26 snapshots) and are kept as written. Current evidence, by verification level:

| Level | Status for source `dbbaae9` + Session 27 edits |
|---|---|
| 1. Pure-Java tests | **VERIFIED, run this session**: core 98 (93 + 5 new `TimingWindowTest`), protocol 67, stream 57 (49 + 3 retry/backoff + 3 regression + 2 corrupt-tile fuzz), render-gate parser tests 13/13 (`tools/ci/render-gate-test.sh`); 0 failures |
| 2. Fresh root + Fabric Gradle builds | **NOT RUN**: no Gradle, no wrapper at root, no network in the session sandbox. Indirect only: the supplied integration logs show a built plugin and mod loading in CI, so *some* build of an unknown commit succeeded; that is not proof for this snapshot. Session 27 Paper edits (`AwreCapture`, `AwreStreaming`) are syntax-checked only (`tools/ParseOnly.java`: 8 files, 0 errors) and **compile-unverified** |
| 3. Headless integration | **Supplied logs (not re-run)**: 32 PASS / 0 FAIL; clientA render line `mesh_built=978 gpu_uploaded=974 upload_failed=0 submitted_draws=1255796`. `render-gate.sh` applied offline to the supplied `clientA.log`: PASS. Applied to `clientC.log`: FAIL at "mesh built" as expected (169 tiles inside the vanilla square, `candidates=0`; client C must never be gated) |
| 4. Human-visible in-game | **NOT VERIFIED**: no display. Procedure: `docs/MANUAL_RENDER_TEST.md` |
| 5. Performance | **NOT RUN**: instrumentation added (`AwreCapture#timingStats`); procedure: `docs/MEASUREMENT_PLAN.md` |

Session 27 changes: `tools/ci/render-gate.sh` + `render-gate-test.sh` + `fixtures/` (opt-in render smoke; first stalled stage reported); `integration.sh` honours `RENDER_SMOKE=1`; `ci.yml` gets a `render_smoke` workflow_dispatch input (default off, so default CI is unchanged) and runs the gate tests in the `verify` job; `TimingWindow` (horizon-core) and bounded timing in `AwreCapture`, appended to the `capture:` log line; new `docs/MANUAL_RENDER_TEST.md`, `docs/MEASUREMENT_PLAN.md`. Protocol, tile and cache formats are unchanged. API read-through (source only): `e.blockList()` is used for explosions (the audit's `getBlockList()` failure is already fixed), `getChunkSnapshot(true, true, false)` is called on an already loaded chunk (no load/generation).

Session 27 (part 2) fix: **failed-tile blacklist no longer lasts until reconnect.** `PlayerStreamer` now records failures with the store revision and time; after `retryLimit` failures a tile is held back, then released when its revision changes (immediately) or after a cooldown (default 60 s, doubling per further failure up to 16x; second constructor takes a custom cooldown). `resetFailures()` still exists. Tests: `aBlacklistedTileIsRetriedAfterTheCooldownWithBackoff`, `aNewRevisionClearsTheBlacklistImmediately`, `nonPositiveCooldownIsRejected`; a mutation check (restoring the permanent blacklist) makes the first two fail. Pure Java only; wire/tile/cache formats unchanged; cooldown is a constant, not yet a config key (assumption to measure).

Session 28 (regression / scale / reference-frame phase), all pure Java and run in the sandbox:
- **Regression tests (item 5):** `StreamLoopTest` +3 (vanilla client that never handshakes receives no frames and holds no state; a reconnected client on new sessions over the same store gets the full plan again with 0 strikes; a valid frame with a corrupt tile body is rejected without ending the session and good tiles keep flowing) and `CorruptTileFuzzTest` +2 (deterministic mutation fuzz of V1/V2 tile bytes: only IOException/RuntimeException ever escape the codec, every still-decodable mutation meshes without throwing; the wire validator never throws on random bytes). Already existing and not duplicated: incompatible protocol ranges, garbage strikes, silent-server fallback, mass client reconnect, integration-job incompatible-client check.
- **Scale baseline (item 6):** `tools/bench/ScaleBench.java` / `run-scalebench.sh`, output in `docs/scalebench-output.txt`. Synthetic: 1k/4k/16k chunks ingest at about 3.8k/11k/20k chunks/s (first row includes JIT warm-up), store about 0.4 KB/chunk, full plan streamed with 0 holes and 0 strikes. No thresholds are set; real terrain and a real server are still required (NOT RUN).
- **Reference frame (item 7, partial):** `tools/preview/ReferenceFrame.java` renders the real LOD plan with the real tile-local meshes (camera-relative, near-plane clipped, z-buffered) at origin, diagonal, negative (-50 000) and large (1 000 000) coordinates; CI `verify` job runs it and uploads the PNGs. It gates on near-ground uncovered pixels, interior holes and a depth see-through test against an independent ray-marched ground truth. Mutation check: dropping 20% of tiles gives 228-1648 see-through samples per view versus 0-1 clean, so the gate can fail. A first version that only checked for sky pixels did NOT catch missing tiles (a gap shows farther terrain, not sky) and was replaced. **This is a geometry check only: no GPU, fog, lighting, real biome colours or mod compatibility; the real frame is still `docs/MANUAL_RENDER_TEST.md`.**
- **Open finding:** in the `negative` view one sample (right frame edge, true distance 261 blocks, near the LOD0/LOD1 boundary at 256) is an uncovered pixel. It may be a hairline LOD-boundary crack or an edge artifact; not yet diagnosed. The gate tolerance is 0.1% of samples, taken from this baseline.

## 2026-10-22 (Session 28, later) Gradle test evidence
Supplied `gradle-reports_1.zip` (JUnit XML, real Gradle + JUnit 5, stamped 2026-10-22 05:47Z): **222 tests, 0 failures, 0 errors, 0 skipped** = horizon-core 98 + awre-protocol 67 + awre-stream 57. This matches the sandbox-shim counts exactly, and the Session 27-28 tests are present and passing under real JUnit: `TimingWindowTest` 5, `CorruptTileFuzzTest` 2, `StreamLoopTest` 14 (including the vanilla-client, reconnect, corrupt-body and blacklist backoff/revision tests). First confirmation that the new tests behave the same outside the sandbox shim.
What this does NOT show: `awre-paper`, `awre-fabric` and `horizon-paper` have no unit tests, so these reports say nothing about whether they compile. A green CI `build` job (reported by the user, screenshot without commit SHA) would, together with these reports containing the Session 28 tests, indicate the Paper edits compile; to close that at level 2, record the commit SHA of the green `build` run and keep its log. Integration (headless) result for this commit and all in-game / performance items remain as listed above.

## 2026-10-22 (Session 29) Evidence for repo HEAD `5517d55` (CI run of the Session 27-28 code)
Supplied: `integration-logs_1`, `jars`, `reference-frames`, `gradle-reports_5474f3` (the same 05:47Z Gradle run as `gradle-reports_1`: 222 tests, 0 failures), and the repo snapshot (`git log`: `5517d55 ci: add jobs`, `6670469 ci:permission failure`, `dbcb4d1 c1:P1 scaling risks`, on top of `dbbaae9`; its source equals the Session 28 zip, only these docs differed).
- **Level 2 (builds), now evidenced:** the CI `Build Paper plugins + Fabric mod` job produced `AWRE-Paper-0.1.0.jar`, `awre-fabric-0.1.0.jar`, `HorizonField-0.1.0.jar` (05:48Z). Inspecting them: `AwreCapture.class` contains the Session 27 timing strings (`snapshot[`, `tick[`, `lastSweep chunks=`, `enqueueMs=`), and `PlayerStreamer$Fail`/`TimingWindow` are in both the Paper and the Fabric jar. So the Paper edits and the standalone Fabric Loom build compiled for `5517d55`. Not shown: the raw Gradle/Loom build logs (only their products), so exit codes are inferred from the jars, not read.
- **Level 3 (headless integration):** summary 32 PASS / 0 FAIL, same set as before (G3 render lines are still informational because `render_smoke` was off). `clientA.log` last render line: `received=2127 mesh_built=852 gpu_uploaded=848 upload_failed=0 submitted_draws=1049874`; `render-gate.sh` on this log: PASS.
- **Reference frames:** the four CI PNGs are byte-identical to the ones rendered in the sandbox (deterministic across machines). The one flagged sample in the `negative` view was inspected: it is a skyline-silhouette pixel (coarse LODs average heights, so a distant peak is slightly lower), not a crack; interior sky pixels = 0. The see-through check now ignores skyline pixels; clean views read exactly 0/N and the 20%-missing-tiles mutation still reads 222-1646 per view.
- **Open observation:** both integration runs end with `mesh_built - gpu_uploaded = 4` (852/848 now, 978/974 before) with `mesh_failed=0`, `upload_failed=0`, `pending=0`. Cause not established from the logs (not a transient in-flight backlog; the 4 tiles carry ~984 triangles, so they are not empty). Added diagnostic: the render line now ends with ` built_not_resident=N sample=[lod/tx/tz ...]` (`HorizonDrawPlanner#builtNotResident`, test `builtButNotYetResidentMeshesAreReportedAndDrainUnderTheUploadBudget`), so the next CI run names the tiles.
- **Capture timing:** the Session 27 timing line existed only behind `/awre`, so this run recorded no capture timings. Now logged once a minute and at shutdown (`[AWRE] capture: ... (final)`) and printed by `integration.sh`. Still NOT RUN on a representative server (~4100 chunks).
- **Tests after Session 29 changes:** core 98, protocol 67, stream 58 (new test above), render-gate 14. Not yet seen in CI.
- Still NOT VERIFIED: human-visible rendering, production-scale server cost, compatibility with Sodium/Iris/VulkanMod/Folia.

Next tasks (acceptance criteria):
1. **Fresh builds (P0).** On a machine with network: `gradle build -PjavaRelease=21 --stacktrace` and `gradle -p awre-fabric build --stacktrace` on this tree. Accept: both exit 0, jars produced, logs kept; any compile errors fixed minimally and re-run.
2. **Render smoke gate (P0).** Run the `integration` job via workflow_dispatch with `render_smoke=true` on the CI runner. Accept: `G3 render smoke` PASS in the summary on 3 consecutive runs before it is ever made default.
3. **Human-visible test (P0).** Execute `docs/MANUAL_RENDER_TEST.md` baseline. Accept: checks 1-10 each marked PASS/FAIL with screenshots; failures become issues.
4. **Server cost (P1).** Execute `docs/MEASUREMENT_PLAN.md` at >= 4100 chunks. Accept: capture `tick` p99 < 2 ms, no p95 MSPT regression, then choose `stream.revalidate-seconds` from the data.
5. **Scaling risks (P1), chosen from measurements:** unbounded `TerrainStore`, `TileLog.open` full read, client store bounded by count not bytes, tile Nether roof, no caves/overhangs.

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

## Session 31 summary

From the supplied CI logs: the `built - uploaded = 4` gap is a sampling artifact (render line logged right after the 10 Hz plan refresh). Real finding: client mesh throughput was capped at 40 tiles/s (`maxInFlight` 4 x 10 Hz updates; measured 40.8 / 39.4 / 40.8 / 40.0 per second in the CI log; mesher itself ~0.14 ms/tile). `ClientHorizonView.DEFAULT_MAX_IN_FLIGHT = 64` (model: 39.7 s -> 2.5 s for ~1600 tiles), `MeshThroughputTest` (3). Pure Java: core 98, protocol 67, stream 64, render-gate 15. CI confirmation of the new rate pending. Reference-frame PNGs from CI are byte-identical to the sandbox renders. Details: `docs/HANDOFF.md` (Session 31).

## Session 30 summary

The CI `mesh_built - gpu_uploaded = 4` observation was reproduced offline with the real server -> client -> view -> planner chain (`tools/bench/run-residencysim.sh`, `docs/residencysim-output.txt`): no gap for a stationary camera; a gap of the same shape appears when the camera or render distance changes early (mesh finished for a plan that changed before its upload slot), with `built_waiting=0`. Hypothesis "parent hidden by children" refuted (quadtree leaves only). Render line now splits `built_not_wanted` / `built_waiting`; 3 new tests (`ResidencyAccountingTest`), gate parser case added. Pure-Java: core 98, protocol 67, stream 61, render-gate 15. The next CI render line decides whether it is benign. Details: `docs/HANDOFF.md` (Session 30).

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
