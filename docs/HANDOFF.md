# HANDOFF — read this first (for the next AI / developer)

## Session 13 addendum (LATEST)
User: "continue building" (twice). Delivered (docs/AWRE_G1_FEATURES.md section 7): `TerrainPersistence` (TileLog reuse, restart + torn-tail + fingerprint tests) wired into `ChunkIngest` and Paper (`stream.persist`, `stream.import-horizon-cache`, async replay, 30 s flush); `TerrainMesher` + `BlockColors` (property-tested greedy mesher with skirts); `TileMeshCache`; `tools/preview/TerrainPreview.java` with images in docs/img. Sandbox: core 61/61, protocol 67/67, stream 13/13.
Still source-only: awre-paper (incl. async persistence wiring), awre-fabric. Not started: the actual draw call in the Fabric client (needs 1.21.11 render APIs that cannot be checked offline).
Next: user runs CI/builds and returns logs; then Fabric render hook using TileMeshCache, calibration on real data, log compaction.

## Session 12 addendum
User: "continue building". Delivered (docs/AWRE_G1_FEATURES.md section 6): `PALETTE` message (cap 8) so material ids are meaningful on the client; `ChunkIngest` (observed chunks -> TerrainStore, pure Java, tested); Paper `AwreCapture` + `AwreChunkView` + `AwreWorldTerrain`, multi-world `AwreStreaming` with world-change re-attach and `stream.source: synthetic|captured`; Fabric logs `palette_entries`; CI checks caps 15 and the palette. Sandbox tests: core 57/57, protocol 67/67, stream 12/12.
Still source-only: all of awre-paper and awre-fabric. Still unverified: CI, Gradle, Loom, Paper API usage (ChunkSnapshot calls, scheduler, events) in the new Paper classes.
Next: user builds / runs CI and returns logs; then persistence of captured terrain, client-side rendering of tiles + palette, calibration of kappa / penalties / budget on real data.

## Session 11 addendum
User said "do that step now" = G1 tile data model + wiring the scheduler. Delivered (details: `docs/AWRE_G1_FEATURES.md` sections 5-6):
- `horizon-core/terrain`: `LodTile`, `LodTileCodec`, `TerrainStore` (version-stamped pyramid, reduction); `stream/PyramidTileSource`; `StreamScheduler` skips no-data areas; `ByteBudget.refund`. Core tests 57/57.
- `awre-protocol`: `DROP` message + `CAP_DROP_V1`, `ServerSession.TileListener/sendDrop/expireStale/maxInFlight`, `ClientSession.setPayloadValidator/tileKeys`, `Limits.withClientTileCache/withStreaming`. Protocol tests 60/60.
- NEW module `awre-stream` (`PlayerStreamer`, `StreamConfig`, `SyntheticTerrain`, `TerrainPayloads`), tests 8/8 via `bash tools/verify-stream.sh`.
- SOURCE-ONLY: `awre-paper` (`AwreStreaming`, `/awre stream`, config `stream.*`), `awre-fabric` (validator, 8192 client cache, `store tiles=` log, compiles horizon-core + awre-stream sources), CI script checks for the stream.
- Bugs found by the new tests and fixed: G0 client rejected every non-test tile; in-flight purge never ran when the window was full.
Next: user runs CI / builds and returns logs; then real chunk feed (TerrainStore.put from captured chunks), client-side rendering, calibration on a real .hfl, world-change handling.

## Session 10 addendum
User asked to "add a bunch of new features and build the whole thing; numbers and errors later, based on math and theory". Delivered, all documented in `docs/AWRE_G1_FEATURES.md`:
- `awre-protocol`: tile fragmentation (`FRAGMENT`, `CAP_FRAGMENT_V1`, `Fragment`, `Fragmenter`, `Reassembler`, `ServerSession.sendTile/sendTestTile`, `TestTile.generateNoise`, client reassembly + ack by msgId). Tests: 54/54 (was 39), run with `bash tools/verify-awre.sh`.
- `horizon-core`: `lod/` (LodModel, LodKey, LodPlan, LodPlanner: error-model quadtree + hysteresis + 2:1 balance) and `stream/` (StreamScheduler benefit/cost greedy + keep-until-replaced eviction, ByteBudget, InFlightSizer). Tests: 49/49 (was 31). Bench: `tools/bench/LodPlanBench.java`, output in `docs/lodplanbench-output.txt`.
- `awre-paper` (SOURCE-ONLY): `/awre fragtest [side]`, `/awre lodplan [radius]`; now depends on and bundles horizon-core. `awre-fabric` (SOURCE-ONLY): logs negotiated caps.
- `tools/junit-shim`: added `assertArrayEquals(byte[],byte[][,String])`.
Honest limits: nothing compiled with Gradle/Paper/Loom; kappa, tolerance, hole/stale penalties are assumptions; planner is slow-ish (30-120 ms/plan, plan async); scheduler is not wired to real tiles or to ServerSession; G0 is still NOT passed (needs the user's builds and the 7 runbook checks).
Next: user builds both modules and runs runbook checks 1-7 plus fragtest/lodplan and sends back logs; then G1 tile data model (ColumnTile -> TileMessage), wire scheduler + InFlightSizer + ByteBudget into the server session, calibrate kappa on a real .hfl (RealTileReport LOD-error lines).

## Session 9 addendum (LATEST — single log: this section is the newest; older sections are history)
User supplied `AWRE_Master_Prompt_Direction_C.txt` (Paper plugin + Fabric client mod + custom protocol, "build everything in-house") and asked to critique, complete and code. Done this session:
- Critique + refined prompt: `docs/AWRE_CRITIQUE.md`, `docs/AWRE_Master_Prompt_Direction_C_v2.txt` (limits of plugin messages, client-first negotiation, measurable G0, exposure policy, prediction scope, verification labels).
- G0 code (the only gate implemented): `awre-protocol` (pure Java; frames, messages, strict bounded decode, inflate-bomb guard, server/client session state machines, rate limits, strikes, newer-revision-wins client store, fault injector) — **TESTED in the sandbox, 39/39** via `bash tools/verify-awre.sh`; `awre-paper` (plugin: channel bridge + `/awre status|stats|sendtest|sendbad|debug|reload`) — **SOURCE-ONLY**; `awre-fabric` (separate Gradle build, Mojang mappings, client handshake wiring) — **SOURCE-ONLY**. Core tests unchanged: 31/31.
- Docs: `docs/AWRE_PROTOCOL.md` (wire format, bounds, verified vs unverified transport facts), `docs/AWRE_G0_RUNBOOK.md` (build + 7 acceptance checks; the user must run them and send back logs).
- Verified from docs this session: Fabric 1.21.11 networking API names (Mojang mappings: `CustomPacketPayload`, `StreamCodec`, `Identifier`; `PayloadTypeRegistry.playS2C/playC2S`), Paper `sendPluginMessage`/`getListeningPluginChannels`. Fabric versions (Loader 0.18.4, API 0.141.3+1.21.11, Loom 1.14-SNAPSHOT, Gradle 9.2.1) come from third-party projects building 1.21.11, to be confirmed by the first Gradle build.
- Reproduced this session: the Session 8 finding D1 (shared palette) is real at core level (old code throws `palette mismatch at id 1` when worlds are opened in a different order after a restart).
- G0 is NOT passed: nothing of awre-paper/awre-fabric has been compiled or run. Open hypotheses: Paper may drop `sendPluginMessage` to clients that never sent `minecraft:register` for the channel; Fabric registers early enough; handler time on the main thread is small; proxies.
- Next: user builds both modules and runs the 7 checks in the runbook; fix compile errors first. Only after G0 passes: G1 (tile data model on top of horizon-core), then fragmentation (needed as soon as a tile exceeds ~32 KB).

## Session 8 addendum — review & hardening
Full review in `docs/REVIEW.md`. Fixed: per-world palette (shared-palette cache corruption), log-growth dedup (`TileDeduper`), robust highest-Y in `ChunkSnapshotView`, drain/open error handling, shutdown warning. Core tests 31/31 reproduced (shim runner); Paper layer syntax-parsed only. NOT reproduced: any real-capture benchmark. Direction critique: stop StreamSim tuning; Gate 1 -> Gate 2 next; confirm the Fabric-mod pivot with the user. `docs/AUDIT_REPORT.md` has the Fabric 1.21.11 version matrix (Loom 1.14.x, Fabric API >=0.138.3+1.21.11, Java 21, world render events reintroduced in 0.137.0).

## Session 6 addendum
User supplied the built jar (Java 21 target, SHA-256 84e53a92…7bc80cb0) and a REAL capture (4761 chunks). Gate 0 (build) passed for the Java 21 build; the capture pipeline works at runtime.
Added `tools/bench/RealStreamSim.java` (StreamSim on real heights; usage `java dev.horizonfield.core.RealStreamSim file.hfl [maxR=256] [all|cull|study|sweep]`, needs core classes on the classpath; compile like `tools/run-report.sh`) and `docs/realstreamsim-output.txt`. Results in TECHNIQUE_MAP §4c.
Headline: sweep has 0% false occlusion on real data but is ~12-31x loose at ground level, so culling saves ~0% (synthetic 21% did NOT reproduce); clipmap saves ~14% bytes with 64% re-sends; keep-until-replaced still the best clipmap variant. One water-heavy world, radius scaled to 256 — capture a mountain/coast world next.
Session 6b (merged with a parallel branch of this work): `RealStreamSim.java` now also has S5h (S5 with its own band HB) and S6 (S5h + LEVEL HYSTERESIS: do not re-request an area at another level while a cached tile covering it is still inside ITS OWN ring band), a back-and-forth path (`PATHKIND=1`), and mode `hyst` (usage: `... RealStreamSim file.hfl [maxR=256] [all|cull|study|sweep|hyst] [bandHB=32]`). S1–S5 output is byte-identical to the pre-merge run. Results in TECHNIQUE_MAP §4d: hysteresis cuts clipmap bytes 17% (straight walk) to 37% (back-and-forth) at 4 KB/step vs S5h, and under a starved budget lifts late coverage 64.8% -> 92.5%, but mean height error rises ~0.15–0.5 block (coarse tiles live longer). One world, one rough row, abstract steps.
Capture facts learned from the user's runs: view=32/send=32/sim=10 on a clean Paper server gives ONE contiguous 69x69-chunk square (4761 chunks, 100% filled); simulation distance does NOT limit capture; the client render distance must also be 32; a `/hf flush` while `capture queue>0` writes a partial file; an earlier capture on a server with other plugins contained a stray 1360-chunk patch ~10,000 blocks away (cause unknown, not from this plugin).
Next: (1) capture a rougher biome (mountains/coast) and rerun `all` + `hyst`; (2) decide canopy-vs-ground height for distant tiles (leaves ~16% of tops; most of the large LOD errors, max 75–80 blocks) ; (3) tighten the sweep (per-ray occluder tests, canopy-aware) or drop server-side culling from the plan; (4) E1/E2 and Gate 1/2.


## Session 2 addendum (read before the oldest notes below)
Done: reproduced 21/21 core tests and the SweepBench numbers (JDK 21 sandbox, still no Gradle/network, Paper module still NOT compiled).
Added (additive only, nothing existing was changed): `core/TileCodec.java` (local palette + bit packing + base/delta heights),
`core/.../TileCodecTest.java` (3 tests; suite now 24/24), `tools/bench/CodecBench.java`, `docs/TECHNIQUE_MAP.md`.
`TileCodec` is NOT wired into `TileLog` (log format v1 unchanged). If wiring it in: bump FORMAT_VERSION, keep a v1 reader, add a migration test.
Measured (synthetic): see TECHNIQUE_MAP §3. Honest summary: batching the deflate matters most; bit packing adds ~35–58% on top.
No web research was done. All prior-art statements in TECHNIQUE_MAP are uncited INFERENCE.
Still the next gate: Gate 0 real build on a networked machine, then Phase 1 experiments E1/E2 (and Gate 1/2 for the Fabric branch).
Rejected for now: Nanite-style virtualized geometry, neural rendering/radiance fields, client-side GPU terrain synthesis (reasons in TECHNIQUE_MAP §1).
Session 3: H1/H6 simulation DONE (tools/bench/StreamSim.java, results in TECHNIQUE_MAP §4, raw output docs/streamsim-output.txt). Summary: clipmap saves ~30% bytes
when bandwidth is adequate but loses when starved at high speed; visibility ranking helps ground-level starved cases, no bytes saved; sweep had 0% false occlusion
but is 6.3x loose at ground level. All synthetic and smooth-terrain; three measurement bugs were found and fixed (listed in §4). DH zip lacks its core submodule (§5).
Session 4: churn + culling follow-ups DONE (TECHNIQUE_MAP §4b). Clipmap: 48-75% of its bytes are re-sends at another level; the starved-elytra loss was due to evicting fine tiles
before a replacement existed (fixed by S5 keep-until-replaced), NOT churn. Culling with a fresh sweep saved ~21% on a ground-level synthetic walk with no extra holes; stale sweeps need
their radius padded by the distance moved; at altitude culling is useless. Still synthetic only.
Session 5: user chose order = (1) real Paper build + REAL chunks first, (2) then clipmap with level hysteresis. The sandbox still has no network/Gradle/Paper jar, so the Paper
module is STILL uncompiled. Added instead: core/TileLogReader.java (read-only reader; TileLog.open TRUNCATES on fingerprint mismatch, never use it on a real cache), its 3 tests (suite 27/27),
tools/bench/RealTileReport.java + tools/run-report.sh (offline report on a real .hfl: extent, heights, materials, LOD error L1-L3, codec bytes), tools/bench/MakeSyntheticHfl.java
(pipeline test only), docs/BUILD_AND_CAPTURE.md (exact build + capture + analysis steps). Waiting on: build output / a captured .hfl from the user. Then generalise StreamSim to real heights.
Older suggestion list (superseded by the agreed order above): (a) clipmap with level hysteresis to cut re-sends, (b) feed real captured tiles (needs the Paper build + a server) instead of noise,
(c) measure sweep CPU per player under a realistic tick budget, (d) then Gate 0 real build and E1/E2.

## Where work stopped
Phase 0 done (research + architecture). Phase 2 baseline scaffold written. **Phase 1 (protocol experiments E1–E6) is NOT done** and is the next gate — nothing delivers terrain to a client yet.

## Verified vs unverified
- VERIFIED (ran in sandbox): `horizon-core` compiles; 21 tests pass (`tools/verify-core.sh`); synthetic bench ran (`tools/bench/SweepBench.java`). Sandbox JDK was 21 (no `--release 25`).
- NOT VERIFIED: `horizon-paper` compile (only syntax-parsed with `tools/ParseOnly.java`); Gradle files never executed; Java 25 build; any runtime behaviour on a real Paper server; all [T] items in ARCHITECTURE.md.
- Research was done via a handful of web searches + the uploaded DH zip's license files; DH source was NOT studied in depth.

## Next steps, in order
1. Build on a networked machine: `gradle wrapper`, `./gradlew build`. Fix any Paper API compile errors (most likely spots: `Player#getClientViewDistance/getSendViewDistance/getSimulationDistance`, `World#getGenerator`, `Display#setDisplayWidth/Height`, `Transformation` ctor, `ChunkSnapshot#getHighestBlockYAt` semantics for empty columns). Gradle 9.1+ is needed for JDK 25 toolchains; `-PjavaRelease=21` otherwise. Check `run-paper` plugin version is current.
2. Run **E1 then E2** with `/hf probe ...` and fill `docs/EXPERIMENTS.md`. E2 decides if the "skyline shell" (C2) is viable at all.
3. Create module `horizon-nms` (paperweight-userdev, 1.21.11 dev bundle) for E3/E4: send `ClientboundSetChunkCacheRadiusPacket` and a synthetic `ClientboundLevelChunkWithLightPacket`. Keep NMS isolated behind `paper/delivery/DeliveryChannel`.
4. If E3+E4 pass: implement `GhostChunkBuilder` (ColumnTile → chunk sections: single top block per column, air above, sky light), per-player state machine (see PROTOCOL.md), driven by `VisibilitySweep.toChunkSet()` with slack for movement; budget via `BoundedTaskQueue` per player.
5. Only if E2 passes: `DisplayShellChannel` using packet-only entities + `ShellMesher` output; handle diffing and entity budget.
6. Hardening: async cache replay, log compaction, explosion/piston/fluid invalidation (`BlockExplodeEvent`, `EntityExplodeEvent`, `BlockFromToEvent`, piston events), per-player cancellation when players leave (`BoundedTaskQueue.cancelIf`), PREDICTED tiles, biome tint data, tests with MockBukkit or integration server.

## Design decisions to keep
- Never load/generate chunks from the plugin; observe only.
- Unknown terrain neither occludes nor is shown. Predicted ≠ observed (`Confidence`).
- Conservative occlusion: MAX@near for visibility, MIN@far − slack for occluders, complete nodes only.
- All queues bounded; main thread only enqueues or runs time-budgeted `drain`.

## File map
core: `ColumnTile`, `TerrainAnalyzer`, `HeightPyramid`, `VisibilitySweep` (algorithm), `ShellMesher`, `BoundedTaskQueue`, `WorkerPool`, `TileLog`, `Metrics`, `MaterialPalette`.
paper: `HorizonFieldPlugin`, `CaptureService`, `HorizonListener`, `HorizonFieldCommand`, `ProbeService`, `WorldState`, `ChunkSnapshotView`, `HorizonConfig`, `delivery/DeliveryChannel`.

## Licensing note
Distant Horizons (uploaded zip) is GPL-3/LGPL-3. This project contains no DH code; do not copy DH source into it unless you accept GPL obligations.
