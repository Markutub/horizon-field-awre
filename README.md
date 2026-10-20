# Horizon Field (scaffold v0.1.0)

Server-only Paper plugin research project for **distant terrain on vanilla Minecraft 1.21.11 clients**.

> **Honest status:** this scaffold does **not** render distant terrain. It observes already-loaded chunks, builds a compact
> height pyramid + persistent cache, runs a conservative visibility sweep, and ships experiment commands that decide which
> delivery method is even possible. Read `docs/ARCHITECTURE.md` first (it explains why the vanilla client limits this).

## Layout
- `horizon-core/lod`, `horizon-core/stream` (session 10): quadtree LOD planner with hysteresis, stream scheduler, byte budget. `awre-protocol` now fragments large tiles. See `docs/AWRE_G1_FEATURES.md` (sandbox: core 61/61, protocol 67/67, stream 13/13 via tools/verify-*.sh; Paper/Fabric glue source-only). Session 11 added `terrain/` (tile model), `awre-stream/` (PlayerStreamer) and the DROP message..
- `horizon-core/` pure Java, zero dependencies (model, pyramid, sweep, mesher, queue, storage) — **built and tested** (27 tests (21 original + 3 TileCodec + 3 TileLogReader), run via `tools/verify-core.sh` in the authoring sandbox)
- `horizon-paper/` the plugin (listeners, capture pipeline, command, probes) — compiled and exercised by CI (`.github/workflows/ci.yml`); the end-to-end run is `tools/ci/integration.sh`
- `docs/` ARCHITECTURE, PROTOCOL, EXPERIMENTS, HANDOFF
- `tools/` bench, sandbox test shim

## Build (needs internet + Gradle 9.1+ for Java 25)
```bash
gradle wrapper --gradle-version 9.1.0     # once; no wrapper is bundled
./gradlew build                            # compiles both modules, runs core tests
./gradlew :horizon-paper:runServer         # dev Paper 1.21.11 server (run-paper plugin)
# Java 21 server instead of 25:
./gradlew build -PjavaRelease=21
```
Output: `horizon-paper/build/libs/HorizonField-0.1.0.jar` (core classes are bundled inside).

## Install
Copy the jar to `plugins/`, start Paper 1.21.11 on Java 25 (or build with `-PjavaRelease=21` for Java 21).

Expected log lines (unverified until you run it):
```
[HorizonField] World 'world': cache fresh (no cache file), discarded 0 bytes
[HorizonField] HorizonField enabled (scaffold: observation + diagnostics, no distant rendering). Java 25, workers=1
...
[HorizonField] HorizonField disabled; workers stopped and caches flushed.
```

## Basic functional test
1. Join, walk around so chunks load. `/hf status` → `capture.snapshots` and `storage.appended` rise; pyramid node counts > 0.
2. `/hf sweep 512` → prints cells / visible chunks / probes / ms. Expect `unknownProbes > 0` on a fresh cache.
3. Restart the server → log says `loaded N tiles`.
4. Experiments: `/hf probe info`, `/hf probe ring 120 16` (see `docs/EXPERIMENTS.md`).
5. `kill -9` the server mid-run and restart → cache still loads (truncated tail is discarded).

## Known limitations
- No rendering of distant terrain; delivery layer is an interface only.
- Observes only chunks the server has loaded; no prediction from the world generator yet.
- Surface heightfield only (no caves/overhangs); water/leaves treated as surface.
- Block edits via place/break only (not explosions, pistons, fluids, WorldEdit).
- Cache replay is synchronous on first use of a world; no log compaction.
- `Player#getClientViewDistance` etc. and all Paper API calls are from memory of the API — expect to fix compile errors.


## Capturing real terrain
See `docs/BUILD_AND_CAPTURE.md` (build, capture settings, `tools/run-report.sh` for offline analysis of a cache file).
