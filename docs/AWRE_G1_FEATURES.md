# G1 features: fragmentation, LOD planning, stream scheduling (session 10)

**Status label for everything below:** implemented in pure Java and unit-tested in the sandbox (`bash tools/verify-core.sh` = 49/49,
`bash tools/verify-awre.sh` = 54/54). The Paper commands and the Fabric log line that expose them are SOURCE-ONLY (syntax-parsed, never compiled).
Nothing here was run on a real server or client. Numbers in this file are either derived from formulas or printed by `tools/bench/LodPlanBench.java`
with ASSUMED inputs (marked A). Calibrate the assumptions on real terrain before trusting any absolute number.

## 1. Tile fragmentation (awre-protocol)
Problem: one frame <= 32000 B (below Bukkit's 32766 / vanilla's 32767 plugin-message limit), negotiated default 16384. A deflated tile can exceed that.

Wire: new `FRAGMENT` (type 6), capability `CAP_FRAGMENT_V1 = 2` (negotiated in HELLO/HELLO_ACK; a client without it never receives one).
```
FRAGMENT body = u32 msgId | u16 index | u16 count | u32 totalLen | u32 crc32(whole TILE body) | u16 chunkSize | data
msgId = seq of the first FRAGMENT frame; the client acks the whole tile with TILE_ACK(ackSeq = msgId)
```
Math:
- payload per slice `c = F - 14 (frame overhead) - 18 (fragment header)`; with F = 16384, c = 16352.
- slices `n = ceil(L / c)`; overhead fraction `n * 32 / L` (about 0.2 % for large tiles). Bound `n <= 64` => max body about 1 MB, above the largest allowed decoded tile (1 MiB hard limit).
- Reliable ordered TCP underneath, so loss is not modelled; reordering and duplicates are still handled because nothing in the plugin-message path is guaranteed.

Safety bounds (all enforced before allocation; tests in `FragmentTest`):
- header cross-checks: `2 <= count <= 64`, `index < count`, `(count-1)*chunk < total <= count*chunk`, slice length exactly `chunk` (last: remainder), `total <= negotiatedDecoded * 9/8 + 1024`.
- at most 2 concurrent partial tiles per connection (oldest evicted), 10 s (200 ticks) timeout, header change mid-tile drops the assembly, whole-body CRC32 at the end, then the existing bounded inflate + payload CRC.
- server never fragments unless the client negotiated the capability (`TOO_LARGE` instead); fragment frames before handshake are dropped without allocation.
Known gap: the G0 path `buildTestTile` still frames with the server limit instead of the negotiated size; the new `sendTile` uses the negotiated size. Do not use the old path for real tiles.

## 2. LOD planning (horizon-core `lod` package)
Model (CDLOD-style geometric error vs pixel tolerance):
```
eps_L = kappa * (2^L - 1)                    height error of a level-L tile in blocks          (kappa = roughness, A: 0.5)
p     = eps * H / (2 d tan(fov/2))           projected error in pixels
need p <= tau  =>  d >= eps * K,  K = H / (2 tau tan(fov/2))    (A: fov 70 deg, H 1080 px, tau 2 px  =>  K = 385.6)
d_L   = max(kappa (2^L - 1) K, S * 2^L)      distance beyond which a level-L tile is acceptable; closer => split into 4 children
```
Results with the assumed inputs: d_1..d_6 = 193, 578, 1350, 2892, 5977, 12146 blocks. A tile of level L is `16 * 2^L` blocks wide (A: 16 samples per tile).
Properties proven by tests: each point in the disc lies in exactly one leaf; neighbours differ by at most 1 level (post-pass splits the coarser leaf); every leaf has projected error <= tau (without hysteresis); plan is deterministic; leaf count per level follows the annulus-area prediction `pi (d_{L+1}^2 - d_L^2) / (16 * 2^L)^2` within 0.4x..2.5x.

Hysteresis (band h = 0.15): a node that was a leaf splits only when `dist < d_L (1 - h)`; a node that was split merges only when `dist >= d_L (1 + h)`. A viewer jittering less than `h * d_L` never flips it. Cost: a coarse tile may be kept up to `tau / (1 - h)` = 2.35 px error.

Printed by `LodPlanBench` (docs/lodplanbench-output.txt; A inputs; one cold-JVM run, not a benchmark):
| R (blocks) | leaves | uniform LOD0 tiles | ratio |
|---|---|---|---|
| 2000 | 3304 | 49087 | 15x |
| 4000 | 4648 | 196350 | 42x |
| 8000 | 6076 | 785398 | 129x |
| 12000 | 7080 | 1767146 | 250x |
Churn (new leaves needed over 30 s, R = 4000): walking 5.6 b/s: 860 -> 358 with hysteresis (-58 %); elytra 30 b/s: 4688 -> 3380 (-28 %).
Planning took 30..120 ms per plan in the sandbox (the 2:1 balance pass dominates). Therefore: plan OFF the main thread and only when the viewer crossed a level-0 tile boundary; `/awre lodplan` already does this. An incremental planner is a follow-up.

## 3. Stream scheduling (horizon-core `stream` package)
`StreamScheduler.decide(plan, position, heading, byteBudget, maxOrders, source)` returns what to send and what the client may drop.
- Priority = benefit / cost (greedy knapsack, 1/2-approximation): `benefit = (currentErrPx - newErrPx) * area * viewWeight`, `area = min(4, (size/dist)^2)`, `viewWeight = max(0.05, (1 + cos(angle to heading)) / 2)`, an uncovered area counts as a hole of `H/4` px (A). Stale revisions get a fixed 4 px (A). Finer tiles that already cover a coarser desired tile only earn a 1e-3 consolidation bonus (memory, not quality).
- Eviction is keep-until-replaced: a held tile is dropped only if it is outside the plan, or its whole footprint is covered by acknowledged, current planned leaves. Test `evictionNeverCreatesAHoleEvenWhenStarved` walks 60 steps with a budget of one tile per step and checks 400 random points per step: eviction never uncovered a previously covered point.
- Convergence test: after the viewer stops, a finite budget reaches exactly the planned leaf set with no holes and nothing left to evict.
- `ByteBudget` (token bucket in bytes) and `InFlightSizer` (Little's law `N = ceil(BW / avgTile * RTT) + 1`; 1 MB/s, 100 ms, 16 KiB => 8) are provided and tested; NOT yet wired into `ServerSession` (its window is still the fixed `maxInFlightTiles`).

## 4. How to try it (needs the Gradle builds from docs/AWRE_G0_RUNBOOK.md)
1. Same acceptance checks 1-3 as G0.
2. `/awre fragtest` (default side 128 = 32768 raw bytes of noise, about 25 KB deflated): reply says `frames=2`; client log shows `verified OK (32768 decoded bytes)`; `/awre status` shows `inFlight=0`, `strikes=0`. Try `/awre fragtest 256` (131072 raw bytes, above the default 65536 decoded limit: expect `TOO_LARGE`, which is the correct answer) and `/awre fragtest 180`.
3. `/awre lodplan 8000`: expect about 6000 tiles and a single-digit-to-tens-of-ms async plan time; report the printed numbers.
4. `/awre stats` should now also list `server.tile.fragmented`, `server.fragments.sent`, and the client log counters `reasm.*`.

## 5. Real tile model and the streaming loop (session 11)
Status: pure-Java parts tested in the sandbox (core 57/57, protocol 60/60, stream 8/8). Paper commands, `AwreStreaming` and the Fabric changes are SOURCE-ONLY.

**Tile model** (`horizon-core/terrain`). A level-L tile is 16x16 samples (top height, top material), each sample summarising 2^L x 2^L blocks. Level 0 is exactly one `ColumnTile` (one chunk).
- Reduction per sample from its 2x2 children: height = rounded mean (default; minimises RMS error, which is what the LOD error model assumes) or max (keeps peaks); material = mode, ties to the lowest id; confidence = weakest contributor; no valid child => `NO_DATA`.
- `TerrainStore` keeps chunk summaries, a lazy bounded LRU of built tiles, and versions. A content change takes the next value of a global monotone clock and stamps the chunk and all ancestors, so a tile's version = newest change below it = the AWRE tile revision; 0 = no data; identical re-puts change nothing. Test `everyLevelMatchesTheDefinitionIncludingNegativeCoordsAndHoles` checks levels 1..4 sample by sample against an independent recursive reference (both reduce modes, negative coordinates, 10 % unobserved chunks).
- Wire payload `LodTileCodec`: `u8 format | u8 confidence | 256 x i16 top | 256 x u16 material` = 1026 bytes before deflate; strict decode (length, format, confidence, no material on `NO_DATA`).

**Protocol additions.** `DROP` (type 7, `CAP_DROP_V1 = 4`): server tells the client to forget up to 256 tiles per message, so the server's shadow of the client store stays exact (the client LRU alone would silently desynchronise it). `ServerSession.TileListener` reports acks and expiries; `ClientSession.setPayloadValidator` replaces the hard-wired G0 test-tile check (it made every real tile INVALID, found by the first end-to-end test); `expireStale()` fixes a deadlock where a window full of lost tiles was never purged because purging only ran on a send.

**`awre-stream` module** (`PlayerStreamer`): replan only after the viewer moved >= 16 blocks; window = min(server limit, Little's law); budget = `ByteBudget`; orders by benefit/cost; tiles built from the store with the version they were built at; DROP for what the scheduler says is covered; retry limit 3 per tile (INVALID or expired acks), `TOO_LARGE` never retried; held + pending never exceed `maxHeldTiles`. `SyntheticTerrain` is deterministic made-up terrain labelled PREDICTED.

**Sandbox numbers** (fake clock 50 ms/tick, 400 kB/s, window 16, R = 700 or 400, synthetic smooth terrain, so tile sizes are NOT representative of real terrain):
- cold start R = 700: 1712 leaves, all delivered in 108 ticks (5.4 s simulated), 282 917 wire bytes (165 B per tile), 0 holes, 0 failures, 0 strikes; wire bytes stayed under `burst + rate x time`; in-flight never above the limit.
- walking 20 blocks/s for 400 ticks, R = 400: 1856 tiles sent, 916 dropped, 25 replans, and no previously covered point ever became uncovered (200 random points per tick); the server shadow equalled the client store size on every tick.
- edit of one chunk: exactly one tile re-sent, with the new revision and content; lost acks: recovered after the 30 s timeout; cap of 100 tiles never exceeded; unobserved areas send nothing; a client that never acks is bounded by the retry limit.

**Try it on a server** (`config.yml`): `stream.enabled: true`, `stream.synthetic-chunk-radius: 80`, plus larger `limits.max-in-flight-tiles` (16), `tile-burst` (64), `tile-refill-per-sec` (500) than the G0 defaults (4, 4, 2.0: those throttle streaming to 2 tiles/s). Join with the Fabric client: client log `[AWRE] store tiles=N ...`, server log `[AWRE] stream NAME: ... holes=0`. `/awre stream status` shows the same numbers.

## 6. Palette and real chunk capture (session 12)
Status: protocol, ingest and streaming logic tested in the sandbox (protocol 67/67, stream 12/12). `AwreCapture`, `AwreChunkView`, `AwreWorldTerrain`, the multi-world `AwreStreaming` and `stream.source` are SOURCE-ONLY.

**Why a palette.** Material ids inside tiles come from the server's `MaterialPalette` (ids are assigned in first-seen order), so a client cannot know what id 7 means. New message `PALETTE` (type 8, `CAP_PALETTE_V1 = 8`): `i64 world | i64 world | dimension | u16 startId | u16 count (1..128) | count x key`. Append-only; identical re-sends are fine, a different key for a known id or a gap is a strike. Keys are restricted to lowercase namespaced ids (`[a-z0-9_.-]` plus `:` or `/`), at most 96 chars; at most 32768 entries; a client keeps at most 16 world palettes (LRU). `PlayerStreamer` sends new entries BEFORE the tiles of the same round: it snapshots the palette after building the round's tiles, and because the palette is append-only that snapshot covers every id those tiles use. `LiveCaptureTest` checks this with real worker threads: chunks arrive while streaming and a new block type (gold) appears mid-stream; every 25 ticks every id in every client tile must already be inside the client palette. Result: passes; 1648 tiles sent for 1252 leaves (the extra sends are legitimate revision updates as chunks arrived).

**Real feed.** `ChunkIngest` (pure Java, awre-stream) = bounded de-duplicating distance-prioritised queue -> worker threads -> `TerrainAnalyzer` -> `TerrainStore.put`, labelled OBSERVED. Tests: 400 chunks stored with correct heights and palette ids, identical re-observation keeps the version while a change bumps it, 20 000 submissions against a queue of 64 stay bounded. `AwreCapture` (Paper, source-only) mirrors horizon-paper's CaptureService: ChunkLoad/BlockPlace/BlockBreak enqueue coordinates, a 0.5 ms/tick main-thread drain takes `ChunkSnapshot`s of ALREADY LOADED chunks only, workers analyse. In-memory only (rebuilt as chunks load after a restart); it does not read horizon-paper's `.hfl`. `config.yml`: `stream.source: captured`. Streaming now keeps one terrain per world and re-attaches when a player changes world.

What to expect on a real server: only chunks the server has loaded exist, so the far field stays empty (`noData` in the stream log) until players explore; `/awre stream status` prints `terrain chunks in memory`.

## 7. Persistence, mesher and preview (session 13)
Status: pure-Java parts tested in the sandbox (core 61/61, stream 13/13); Paper wiring source-only.

**Persistence.** `TerrainPersistence` wraps the existing append-only CRC `TileLog` (same file format and the same fingerprint function as horizon-paper, so a file is interchangeable). `ChunkIngest` appends each stored tile once (unchanged re-observations are skipped). Test `capturedTerrainSurvivesARestartAndATornTail`: 144 chunks written, replayed into a fresh store and palette with identical content and palette ids; a 100-byte torn tail loses only the damaged records and never throws; a different world fingerprint starts fresh instead of mixing data. Paper: `stream.persist` (default true) keeps `plugins/AWRE/terrain/<world>.hfl`, replayed asynchronously at first use (the world streams once ready), flushed every 30 s; `stream.import-horizon-cache` copies `plugins/HorizonField/cache/<world>.hfl` the first time. No compaction exists: the log grows with every real change.

**Mesher** (`horizon-core/terrain`: `TerrainMesher`, `BlockColors`). A tile becomes stepped columns: tops at height + 1 merged greedily into rectangles, walls between different heights, and a skirt on every border towards another tile or missing data (so tiles are meshed independently and cracks between levels are hidden). Property tests over random tiles at levels 0..5 with holes: total top area = valid samples x cell^2, wall area = sum of height steps x cell, skirt area = border edges x cell x depth, every triangle non-degenerate and correctly wound (tops +y, walls outward), vertices inside the tile. `TileMeshCache` (client side, awre-stream) meshes held tiles on demand, rebuilds when the revision changes, LRU-bounded. Sandbox number (synthetic terrain, not representative): 1712 tiles -> 517 320 triangles (302 per tile). Colours are APPROXIMATE map colours from memory, not read from game assets.

**Preview.** `tools/preview/TerrainPreview.java` renders the plan over synthetic terrain: `docs/img/lod-levels.png` (tinted by level with tile outlines: level 0 in the middle, rings of growing tiles outwards, no holes in the disc) and `docs/img/lod-terrain.png` (same terrain without tint; it is continuous across level boundaries, which is a visual check that tile addressing and reduction line up).

## 7b. CI phases for captured mode (session 14)
`tools/ci/integration.sh` now has three phases on the same Paper + Fabric setup: (1) synthetic terrain, fragmentation, palette, incompatible client (all green in the first real run); (2) `stream.source: captured` with a fresh disk cache: the client must receive tiles built from REAL chunks the server loaded (flat world around spawn), a palette from real block keys, `noData>0` (unobserved areas send nothing), zero failures and strikes, and `plugins/AWRE/terrain/<world>.hfl` must exist after shutdown; (3) restart: the log must say `terrain cache ...: restored N chunks`. UNVERIFIED until the first CI run with these phases; the first failure will most likely be in log wording or timing, not in the logic.

## 7c. Terrain log compaction (session 15)
The captured-terrain cache (`plugins/AWRE/terrain/<world>.hfl`, `TileLog` format v1) is append-only, so every real block change adds a record and replay keeps only the last one per chunk. `TileLog.compact()` removes the superseded records:
1. Scan the valid prefix (length + CRC; a torn tail is dropped), remembering the offset of the last tile record per chunk and of every palette record.
2. Write header + all palette records + live tile records (original order) to `<file>.compact`, `force`.
3. Close the channel, atomically move the temp file over the log (non-atomic fallback if the filesystem refuses), reopen for append. If the swap fails the original is reopened untouched.
Crash safety: before the move the old file is complete; after it the new file is complete; a leftover `.compact` is deleted on the next `open`. Memory: one boxed long per chunk plus one 2-long entry per live chunk during the scan (records are re-read for the copy, not held).
Trigger: `TerrainPersistence.compactIfWorthwhile(ratio, minBytes)` from the 30 s async task (`stream.compact-garbage-ratio`, `stream.compact-min-bytes`). Appends for that world wait during the rewrite. Verified in the sandbox by `TileLogTest` (3 tests) and `PersistenceCompactionTest`; NOT measured on a real multi-MB cache.

## 7d. Compact tile payload, format 2 (session 15) — built, measured, NOT adopted
`LodTileCodec.encodeV2` (layout in the class javadoc): heights as `top - minTop` bit-packed, local material palette, validity bitmap for no-data samples, canonical (one tile = one byte string; padding bits must be zero), strict decoder (truncation, trailing bytes, bad palette/bit widths all give IOException; 20 000 random corruptions never throw anything else). `decode` accepts v1 and v2; the encoder default stays v1 because a v1-only client rejects format 2 and there is no capability bit for it yet.
Measured on SYNTHETIC smooth terrain (`tools/bench/run-lodcodec.sh`, output `docs/lodcodecbench-output.txt`): raw 1026 B -> 105..261 B, but AFTER deflate (AWRE uses BEST_COMPRESSION) v2/v1 is 1.04-1.06 at levels 0-2 (v2 slightly LARGER) and 0.84-0.94 at levels 3-4; with BEST_SPEED v2 is ~20% smaller at level 0. Reading: deflate already finds the redundancy v2 removes by hand. v2 only pays if (a) the sender wants to skip or lighten deflate (CPU), or (b) real terrain behaves differently. Real terrain is rougher than this synthetic data, so re-run the bench on a real `.hfl` before deciding; if adopting, add a capability bit first (like `CAP_FRAGMENT_V1`).

## 7e. Client horizon view (session 16)
`ClientHorizonView` sits between the client's tile store and a future GPU renderer (pure Java, no Minecraft classes). Per `update` it (1) drops meshes of tiles the server DROPped, (2) classifies every held tile of the current dimension: fully inside vanilla's square -> not drawn; nearest point beyond `maxDistance` -> not drawn; else candidate, (3) schedules decode+mesh jobs for candidates nearest first (one worker thread, at most `maxInFlight` pending, a revision that failed to decode is not retried until the revision changes), (4) caps memory at `maxMeshes` by evicting the farthest meshes, (5) hides a coarse tile when each of its four children is drawn, inside vanilla's square, or itself covered by its children (recursive; memoised per update); a missing child keeps the parent drawn, so transitions never leave a hole and never draw two layers. Output: nearest-first `Drawable(key, revision, mesh)` plus `Stats`. The renderer is expected to re-upload when `revision` changes and free GPU buffers of keys that left the list.
Assumption to verify with real streams: the server may send a parent and its children at the same time during a transition (the rule above handles it) or only leaves (then rule (5) never triggers, which is harmless).
Verified in the sandbox by `ClientHorizonViewTest` (8 tests). Not verified: behaviour with the real headless client (CI check G2), the 1.21.11 client API calls in `AwreClientMod`, any GPU drawing.

## 8. Not done (deliberately)
Drawing the meshes (the mesher and cache exist; the Minecraft 1.21.11 render hook does not, it needs a Fabric build to develop against); incremental planner; delta/edit coding of tiles; calibrating kappa, hole/stale penalties, the byte budget and the real compression ratio on a real `.hfl`; Gradle/Loom/Paper builds.
