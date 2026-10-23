# AWRE G1 audit (Session 26): defects, fixes, evidence

## 2026-10-22 (Session 27) evidence addendum (LATEST)

The statements above remain as written at audit time ("Gradle/Fabric/CI/in-game NOT RUN" was true then). Evidence since:

| Level | Status for source `dbbaae9` + Session 27 edits |
|---|---|
| 1. Pure-Java tests | **VERIFIED, run this session**: core 98 (93 + 5 new `TimingWindowTest`), protocol 67, stream 57 (49 + 3 retry/backoff + 3 regression + 2 corrupt-tile fuzz), render-gate parser tests 13/13 (`tools/ci/render-gate-test.sh`); 0 failures |
| 2. Fresh root + Fabric Gradle builds | **NOT RUN**: no Gradle, no wrapper at root, no network in the session sandbox. Indirect only: the supplied integration logs show a built plugin and mod loading in CI, so *some* build of an unknown commit succeeded; that is not proof for this snapshot. Session 27 Paper edits (`AwreCapture`, `AwreStreaming`) are syntax-checked only (`tools/ParseOnly.java`: 8 files, 0 errors) and **compile-unverified** |
| 3. Headless integration | **Supplied logs (not re-run)**: 32 PASS / 0 FAIL; clientA render line `mesh_built=978 gpu_uploaded=974 upload_failed=0 submitted_draws=1255796`. `render-gate.sh` applied offline to the supplied `clientA.log`: PASS. Applied to `clientC.log`: FAIL at "mesh built" as expected (169 tiles inside the vanilla square, `candidates=0`; client C must never be gated) |
| 4. Human-visible in-game | **NOT VERIFIED**: no display. Procedure: `docs/MANUAL_RENDER_TEST.md` |
| 5. Performance | **NOT RUN**: instrumentation added (`AwreCapture#timingStats`); procedure: `docs/MEASUREMENT_PLAN.md` |

The original audit's "baseline: 82/82, 48/48" before-numbers are historical; current counts are 93 (+5 Session 27 = 98) / 67 / 49 (+8 Sessions 27-28 = 57).

Scope: the G1 repository as received (git HEAD `1e03f0c`, "ci:biome tint"), audited from source. Everything below is labelled
**VERIFIED** (executed here), **SOURCE** (read in the code, not executed against Paper/Minecraft), or **UNVERIFIED**. Nothing
about Paper, Fabric, Gradle, CI or in-game rendering was run in this session (no network, no Minecraft): CI is still the compile check
for every Paper/Fabric change here.

## 1. Baseline (commands run in this session)

| Command | Result |
|---|---|
| `bash tools/verify-core.sh` (before changes) | PASS 82/82 |
| `bash tools/verify-awre.sh` (before changes) | PASS 67/67 |
| `bash tools/verify-stream.sh` (before changes) | PASS 48/48 |
| `java tools/ParseOnly.java awre-paper/src awre-fabric/src horizon-paper/src` | PASS (8 files, syntax only) |
| `bash tools/verify-core.sh` (after) | PASS 93/93 (+6 `CaptureBacklogTest`, +5 `ColumnTilePackingTest`) |
| `bash tools/verify-awre.sh` (after) | PASS 67/67 |
| `bash tools/verify-stream.sh` (after) | PASS 49/49 (+1 `RevalidationSweepTest`) |
| `bash tools/bench/run-membench.sh` | PASS, output in `docs/membench-output.txt` |
| Root Gradle build, separate Fabric build, CI `tools/ci/integration.sh`, in-game | NOT RUN |

## 2. Data path as read (stage -> file -> thread -> failure behaviour)

| Stage | Source | Thread | Notes |
|---|---|---|---|
| Event / load -> request | `AwreCapture` | main | was: bounded queue, result ignored (defect 1) |
| Snapshot | `AwreCapture.snapshot` (`ChunkSnapshot`) | main, 0.5 ms/tick, 2 per tick | only already-loaded chunks; never loads or generates (invariant kept) |
| Analysis | `ChunkIngest` / `TerrainAnalyzer` | 1 worker | immutable snapshot only; bounded queue 4096, `submit` returns false when full |
| Store + revisions | `TerrainStore` | worker | `put` ignores identical content; version per (lod, tx, tz); consistent (tile, version) pair via `versioned` |
| Persist | `TerrainPersistence` / `TileLog` | worker, flush + compaction on async timer | CRC per record, torn tail cut on open, atomic compaction |
| Plan + schedule | `PlayerStreamer` / `StreamScheduler` | one async job per player, `busy` CAS | pending set is recomputed from the plan every round, so it cannot grow without bound; failures blacklist a tile after `retryLimit` |
| Wire | `ServerSession` (all methods `synchronized`), `Codec.inflateBounded`, `Limits` | main (send), async (build) | frame size capped before parsing; inflate hard-bounded; rate limits; strike limit |
| Client store | `ClientSession` | client | newer revision wins, older -> STALE, equal -> DUPLICATE; capacity by COUNT only |
| Mesh + select | `ClientHorizonView` | 1 mesher thread | camera-relative placement in double (`CameraRelative`), tile-local float vertices; dimension gate |
| GPU | `HorizonRenderer` | render thread | NOT compiled/run here; CI run 22 reported a draw-pass ordering bug, fix not yet confirmed |

Checked and found sound (SOURCE + existing tests): revision monotonicity on the client, bounded inflate, camera-relative precision
at +-30M, per-session locking, async work only on immutable snapshots, cache fingerprint (UID, seed, height range, generator
class), permission default `op`, world-key on the wire.

## 3. The three most consequential confirmed defects

### D1 - Loaded chunks silently never captured when more than 4096 are requested (SOURCE + VERIFIED premise)
- Evidence: `AwreCapture.request` called `queue.offer(...)` and ignored the result; `BoundedTaskQueue.offer` returns `REJECTED` (or
  `EVICTED_OTHER`, which forgets another entry) when full. `enqueueLoaded` offers every loaded chunk once when a world becomes ready.
- Mechanism: a chunk whose request is dropped is never observed again (it stays loaded, no event fires). The drop hits the farthest
  chunks, i.e. exactly the horizon edge, and the persistent cache remembers the hole across restarts.
- Reproduction: `CaptureBacklogTest.aBoundedQueueAloneLosesRequestsBeyondItsCapacity`: 4761 chunks (69x69, the size of the
  project's own earlier captures) into a 4096 queue lose 665.
- Fix: `horizon-core/.../CaptureBacklog` (requests are remembered until handled; the bounded queue only caps in-flight payloads;
  priorities are recomputed when work is queued, so a teleporting player is served nearest-first; a handler that cannot finish, or
  throws, gets the key back). `AwreCapture` now uses it; `INGEST_HIGH_WATER` avoids wasting snapshots while analysis is behind.
- Tests: `CaptureBacklogTest` (6): no request lost beyond capacity and handled exactly once; nearest-first with fresh priorities;
  retry and exception safety; coalescing of event bursts; budgets.
- Remaining: main-thread cost of a snapshot is UNMEASURED (needs a real server); the backlog holds one key per loaded chunk at most.

### D2 - Terrain changed by anything except a player placing/breaking a block is never re-observed; stale data is served as AUTHORITATIVE and persisted (SOURCE)
- Evidence: `AwreCapture` had four handlers (`WorldLoad`, `ChunkLoad`, `BlockPlace`, `BlockBreak`). `PlayerStreamer` maps
  `Confidence.OBSERVED` to `Provenance.AUTHORITATIVE`. `Provenance.DIRTY` exists but nothing ever produces it.
- Mechanism: explosions, pistons, fire, leaf decay, tree growth, falling blocks, ice/snow/grass changes, fluids, WorldEdit and other
  plugins change loaded chunks without a place/break event; the chunk is not re-observed until it is unloaded and loaded again, and the
  old tile (already persisted) keeps being streamed as authoritative.
- Fix: handlers for entity/block explosions, piston extend/retract (moved blocks and destination cells), burn, leaves decay, fade, grow
  (covers form/spread), entity-change-block, structure growth (one request per distinct chunk), plus a periodic sweep
  (`stream.revalidate-seconds`, default 600, 0 = off) that re-requests every loaded chunk for what no event reports.
- Tests: `RevalidationSweepTest` (pipeline backlog -> ingest -> store -> persistence, with a backlog smaller than the world): an
  unchanged sweep creates no revision and no disk record; 10 edited chunks create exactly 10 revisions and 10 records; the edit
  survives a restart. The Paper event handlers themselves are NOT testable here (SOURCE only; CI compiles them).
- Remaining: not every possible change fires an event and the sweep period is a latency bound, not instant. Sweep cost on the main
  thread is UNMEASURED; the default is a guess to be tuned from `/awre stats` (`capture: requested/handled/retried/waiting`).

### D3 - Server heap grows without bound, 1.3 KB per observed chunk (VERIFIED)
- Evidence: `TerrainStore.chunks` and `versions` have no cap; `ColumnTile` kept two `short[256]` plus a boxed `HashMap` was built in
  every constructor. Measured with `tools/bench/MemBench.java` on the real classes (synthetic smooth terrain):

| | B per chunk (whole store, incl. version index) | 1,000,000 chunks |
|---|---|---|
| G1 as received | 1290 / 1318 / 1322 (16k / 65k / 147k chunks) | 1.23 GiB |
| after `ColumnTile` packing | 398 / 427 / 430 | 0.40 GiB |

- The on-disk record is about 120 B/chunk, so the heap form was ~11x the disk form.
- Fix: `ColumnTile` stores heights as bit-packed deltas from the chunk minimum and materials as bit-packed indices into a per-chunk
  palette. Canonical, so `sameContent` is an array comparison. Public API, `contentHash64` values and the dominant-material
  tie-break are unchanged (oracle tests). The constructor no longer allocates a boxed map. `TerrainStore.approxChunkBytes()` and
  `/awre stats` expose the figure.
- Tests: `ColumnTilePackingTest` (5): every column round-trips for random tiles including 16-bit height ranges and 256-entry palettes;
  hash equals the pre-packing definition; size bounds (flat < 1/5, hilly < 1/2, worst case <= old size + 600 B).
- Remaining: the store is still unbounded (no eviction, no disk-backed tiles); real terrain is rougher than the synthetic data, so
  expect a larger figure than 430 B; `TileLog.open` still reads the whole file with `Files.readAllBytes` (peak = file size, an
  `OutOfMemoryError` above 2 GiB would escape the `catch (IOException | RuntimeException)` in `AwreStreaming`).

## 4. Other confirmed findings (not fixed unless stated)

| # | Severity | Finding | Status |
|---|---|---|---|
| E1 | Medium | `AwreCapture` took snapshots with `includeBiome=false` while `AwreChunkView.biomeKey` needs biome data and swallows the exception, so the session-25 biome tint is most likely inert on a real server (Paper's behaviour for a snapshot without biomes is from memory: UNVERIFIED). | FIXED (`getChunkSnapshot(true, true, false)`); biome copy cost unmeasured |
| E2 | Medium | Ceilinged worlds (Nether): no environment handling anywhere (`grep getEnvironment` is empty); a top-surface heightfield of the Nether is its bedrock roof. | NOT FIXED, runtime unobserved |
| E3 | Low-Med | Client tile store is bounded by tile COUNT (8192) not bytes; `maxDecodedTileBytes` is 65536, so a hostile server could make a client hold up to 512 MiB. Real tiles are ~1 KB. | NOT FIXED |
| E4 | Low | A tile that fails `retryLimit` times is blacklisted until reconnect, even after the world edit that changed its revision. | NOT FIXED |
| E5 | Info | Heightfield cannot represent caves, overhangs, arches or interiors; player builds appear only as column tops. Intentional approximation (documented), not a defect. | - |
| E6 | Info | `LodModel.kappa = 0.5` and the error model are assumptions, not per-tile measured error. | - |

## 5. Performance evidence

Measured in this session (1 vCPU sandbox, JDK 21, synthetic terrain): only the memory figures above.
Measured earlier on the Session-16 source (planner, mesher, codec, `TileLog` classes were not changed by this session; NOT re-run on
G1; 1 vCPU, cold-JIT single runs for the log numbers): LOD plan R=4000 4638 leaves in ~13 ms cold; mesher 2400-10000 tiles/s/core
depending on tile shape; deflate-9 of a v1 tile 33-141 B; `TileLog` append ~164 us/record and replay ~121 us/record on this
sandbox disk (100k records, 11.4 MiB). No Paper tick, GPU, network, GC or frame-time measurement exists. A server-side procedure is
unchanged in `docs/EXPERIMENTS.md`; new counters for it: `/awre stats` (chunk bytes, capture backlog).

## 6. Remaining risks / unverified

- Every Paper and Fabric change (including this session's) is syntax-checked only. API assumptions in the new code:
  `BlockExplodeEvent#blockList`, `EntityExplodeEvent#blockList` (first CI build: `getBlockList` did not exist, fixed), `BlockPistonEvent#getBlocks/getDirection`,
  `StructureGrowEvent#getBlocks` (BlockState x/z), `ChunkSnapshot` factory signature `(boolean, boolean, boolean)`.
- Main-thread cost of snapshots and of the sweep; the 0.5 ms/2-per-tick budget is an assumption.
- Defects E2-E4 above; unbounded `TerrainStore`; no per-player sharing of encoded tiles (deflate-9 per tile per player is ~10-20 us, not a
  measured bottleneck).
- Folia, other versions, other servers, Sodium/Iris/Vulkan: not claimed (see `docs/COMPAT_PLAN.md`; every cell is UNVERIFIED).

## 7. Next task

Run CI on this tree and read the `build` job (compile check of the new Paper handlers), then on a real Paper server with at least
4,100 loaded chunks confirm `/awre stats` shows `capture: waiting=0` after startup and record the tick cost of the capture drain and of
one sweep; tune `stream.revalidate-seconds` from that number.

## 2026-10-23 audit record (Session 33, Phase 0 on `ceeaa68` + first fix)

Inspected: repo `ceeaa68` (clean, 3 commits after `5517d55`), the previous artifact set, `HorizonRenderer`, `ClientHorizonView`, `ClientRuntime`, `AwreClientMod`, `integration.sh`, supplied logs and jars. Sandbox: Java 21, no network, no Gradle/Fabric/Minecraft, so **in-game rendering: BLOCKED / UNVERIFIED**.

**Provenance (PASS):** every class of core/protocol/stream in `AWRE-Paper-0.1.0.jar` is byte-identical to a fresh `javac -g` of `5517d55` (150/150); `HorizonField-0.1.0.jar` core 65/65; `awre-fabric-0.1.0.jar` is instruction-identical for the 150 shared classes (8 differ only by `ldc`->`ldc_w` and branch offsets from the Loom remapper); its 7 Fabric-only classes are unchecked.

**Defect 1, FIXED: mesh-cap rebuild loop.** `ClientHorizonView.update` step 1 scheduled every candidate without a mesh, step 2 evicted the farthest beyond `maxMeshes`. With candidates > cap the same tiles were rebuilt on every update and never drawn. Reproduced: 30 candidates / cap 10 -> +20 builds per update; 6000 candidates / cap 4096 -> +1904 per update with a synchronous executor (in the async client it is bounded by `maxInFlight`, now 64, so <= ~640 wasted builds/s). Fix: only the nearest `maxMeshes` candidates are scheduled; `Stats.overCap` and the log tokens ` over_cap=N vanilla_radius=R` make the cut visible. Regression tests: `ClientHorizonViewTest.candidatesAboveTheMeshCapAreNotRebuiltInAnEndlessLoop`, `theMeshCapFollowsTheCameraAndSettlesAgain`. CI never reached it (1626 candidates < 4096). Limitation: beyond the cap the farthest (coarsest) tiles are not drawn, so the horizon is cut; a smarter policy is a Phase 3 decision.

**Defect 2, OPEN (confirmed in source, impact not seen in a frame): vanilla radius.** `AwreClientMod.java:69` passes `options.renderDistance().get() * 16`, the client setting, not what the server sends. In the 2026-10-22 CI run `hidden_vanilla=501`; the project's own `LodPlanner` at the CI spawn gives 497-540 leaves fully inside a 192-block square (render distance 12) and only 49-64 inside 64 blocks (server `view-distance=4`): the client hid ~450 tiles vanilla was not drawing. Not fixed: the replacement (believed `Options#getEffectiveRenderDistance`) must be read from the `mc-api-dump` artifact first. Gate prepared: `ASSERT_EFFECTIVE_RADIUS=1 CLIENT_RENDER_DISTANCE=12` makes `integration.sh` fail until it is fixed.

**Harness (changed):** `tools/ci/log-scan.sh` (+ `log-scan-test.sh`, 22 tests on real log excerpts, mutation-checked): stack-aware AWRE exception scan (the old single-line grep could not see a trace whose only AWRE evidence is its frames), convergence (`ready + over_cap == candidates`, `pending=0`, `held == candidates + hidden_vanilla + too_far`), labelled noise classification. `integration.sh`: explicit client `renderDistance` (default 4), run metadata, convergence wait (<= 240 s), server-side quit cleanup assertion (needs the new `[AWRE] <name> quit: session dropped, stream detached` line in `AwreChannel`; `AwreStreaming.detach` now returns boolean). **None of the `integration.sh`/Paper edits has been executed in CI.**

**Log observations:** `No key layers in MapLike[{}]` = before AWRE enables, fresh world only, harness sets `level-type=flat` with no `generator-settings` (probable cause, not reproduced; unrelated to AWRE). Exit 143 = the harness's own `kill` (expected; it also means client disconnect/shutdown cleanup, `gpu_freed=0`, is never exercised). Only client exception: `OpenAL 1.1 not supported`. In CI, captured terrain (client C) was entirely inside the vanilla square (`candidates=0`): real captured terrain never reached the renderer.

**Commands (sandbox, shim runner not Gradle):** `verify-core` 98, `verify-awre` 67, `verify-stream` 66 (64 + 2 new), `render-gate-test`, `compat-lib-test`, `log-scan-test` (22), `run-reference-frame.sh`: all exit 0. `ParseOnly` on awre-paper: 0 errors. NOT RUN: Gradle, `awre-paper`/`awre-fabric` compilation, all four CI jobs, any in-game frame.

**Measured (1 vCPU sandbox, informational):** ScaleBench 16,384 chunks: ingest 954 ms, 2,152 tiles sent, 0.36 MiB wire, 0 holes, 0 strikes. Mesh build 1,712 tiles: 0.07 ms/tile mean (5 runs after 2 warm-ups, 73-229 ms per pass), 520k triangles, 21.8 MiB if all resident. Your 0.14 ms/tile figure is on different hardware.

### Phase 1 (geometry), 2026-10-23: no new geometry defect found; positional oracle added
Inspected `TerrainMesher`, `TerrainStore` (LOD reduction: `floorDiv` rounding, `NO_DATA` skipped, `& 7` correct for negative tile offsets), `VertexPacker` (16 B POSITION_COLOR, tile-local floats, baked per-face shade UP 1.0 / N,S 0.8 / W,E 0.6 / skirt 0.7).
The existing `TerrainMesherTest` asserts AREAS and winding. An area sum cannot see a quad at the wrong height or plane, so `TerrainGeometryOracleTest` (10 tests, core 98 -> 108) ray-samples the mesh against the height field: exactly one top at `top+1` per valid cell, none over `NO_DATA`; every wall/skirt covers its span exactly once with the right face tag, nothing above or below; two tiles of different LOD meshed independently (LOD 0|1, 1|3 in negative space, 0|3) leave no uncovered point on their seam and draw the visible cliff once. Fixtures: flat, ramp, cliff, ridge, valley with negative heights, checkerboard, holes/stripes, pillar+pit, random at LOD 0-5, tile coordinates negative and positive.
**Mutation check (scratch copy):** top one block too low -> 9 oracle failures, 0 old failures; SOUTH wall on the wrong plane -> 10 / 0; wall one block too deep -> 8 / 1; floor skirt 3 blocks short -> 10 / 1. So two of four plausible bugs were invisible before.
**Measured, synthetic terrain only (not representative of mountains):** 1,712 leaves; borders between same-LOD tiles p95 = 1 block (max 2), between mixed-LOD tiles p95 = 1 (max 2, n = 6,144); visible seam wall area 0.17% of top area. Natural relief is at most 2 blocks, so the terraces and dark rectangles in the reference frames are runs of 1-2 block steps (walls are merged along an edge) rather than seam artifacts: a hypothesis, not yet checked in a real frame.
Limits: skirts go to the fixed floor `ClientHorizonView.SKIRT_FLOOR_Y = -64`, correct for vanilla dimensions, not for a datapack dimension with a lower `min_y` (crack possible); a heightfield cannot represent caves/overhangs; no real mountain data exists in the repo to measure seams on. Next geometry work belongs with Phase 3 (stitching is only worth building if real-terrain seam steps turn out large).
Commands: `bash tools/verify-core.sh` -> passed=108 failed=0 (shim runner, not Gradle).
