# Build the Paper plugin and capture REAL terrain (do this on a machine with internet)

**Status (updated session 6):** the user built the plugin on their own machine with `-PjavaRelease=21` (jar: class major 65, 11 paper + core classes, SHA-256 `84e53a928447e7c24e9e5b790b41b2e270663bbf07b33bd8251b7ee37bc80cb0`) and ran it on a real Paper server, producing a valid 4761-chunk `.hfl`
(offline report + `docs/TECHNIQUE_MAP.md` §4c). So `horizon-paper` compiles and runs for that build. NOT verified by the author: which exact Paper build/Java ran it, whether the source was edited to compile (if so, the zip no longer matches the jar), Java 25 build, multi-player behaviour.

## 1. Build
Prereqs: JDK 25 (default) or JDK 21 (add `-PjavaRelease=21`), Gradle 9.1+ installed once to create the wrapper, internet.
```bash
cd horizon-field
gradle wrapper --gradle-version 9.1.0
./gradlew build                      # JDK 25
./gradlew build -PjavaRelease=21     # JDK 21
```
Expected output: `horizon-paper/build/libs/HorizonField-0.1.0.jar`; core tests pass (27 in the authoring sandbox via `tools/verify-core.sh`).
Most likely compile errors (from memory of the Paper API, UNVERIFIED): `Player#getClientViewDistance/getSendViewDistance/getSimulationDistance`,
`World#getGenerator`, `Display#setDisplayWidth/Height`, `Transformation` constructor, `ChunkSnapshot#getHighestBlockYAt` for empty columns.
If it fails: send the full Gradle output; fixes should be small.

## 2. Test server (DISPOSABLE world only)
Paper 1.21.11 on a throwaway server (or `./gradlew :horizon-paper:runServer`). Put the jar in `plugins/`.
Plugin config `plugins/HorizonField/config.yml` for a one-off capture (values stay inside the plugin's clamps):
```yaml
capture:
  queue-capacity: 16384         # default 4096 would drop part of ~4200 chunks arriving at join
  max-snapshots-per-tick: 8     # default 2 => ~100 s to drain 4200 chunks
  budget-millis-per-tick: 2.0   # disposable server: allow more main-thread time
```
`server.properties`: `view-distance=32` (vanilla maximum). Join with client render distance 32. Ordinary world generation, a seed with varied terrain.
(The plugin only observes chunks that are loaded at the moment it drains its queue; it never loads or generates chunks itself, so
pre-generators such as Chunky that unload chunks quickly may lose most of them. A player standing still with view-distance 32 is the reliable way.)

## 3. Capture checklist
1. Stand still ~2 minutes. `/hf status`: `capture.snapshots` should approach ~4200 (65x65 chunks) with few `capture.rejected` / `capture.skipped-unloaded`.
   If the numbers are far lower, the load distance is smaller than expected: raise client render distance / wait / report it.
2. `/hf sweep 512` and copy the line it prints (cells, visible chunks, probes, ms, unknownProbes). First real server-side sweep cost.
3. `/hf flush`, then stop the server cleanly (the cache is also flushed on disable).
4. Teleport to a different biome (mountains, ocean/coast, forest, a player-built area) and repeat 1-3 if you can; one .hfl per world.
5. Copy `plugins/HorizonField/cache/<world-uuid>.hfl` (about 0.7 MB per 4200 chunks).

## 4. Analyse (needs only a JDK/JRE 21+, no Gradle)
```bash
bash tools/run-report.sh /path/to/<world-uuid>.hfl
```
Read-only on the file (covered by `TileLogReaderTest`). Never open a real cache with `TileLog.open`: it truncates the file on a fingerprint mismatch.
Send back the printed report, ideally the .hfl too.

## 5. Sanity checks on the report (catches capture bugs before any conclusion)
- distinct chunks about 4200 and known ~100% of the bounding box; confidence all OBSERVED.
- heights in a plausible range for the world (e.g. -64..320) and materials that look like real surface blocks.
  Heights stuck at the minimum, or air as the dominant material, would mean the `getHighestBlockYAt` semantics are wrong for this API (UNVERIFIED in ARCHITECTURE).
- mean |dY| between adjacent columns: the synthetic terrain gave 0.16 blocks; real terrain is expected to be much higher. That number plus the LOD-error lines
  (L1/L2/L3 mean, p95, max) are what decides whether coarse clipmap levels are acceptable on real terrain.

## 6. What happens next (agreed order)
1. With a real .hfl: generalise `tools/bench/StreamSim.java` to a real heightfield (unknown chunks neither occlude nor are sent) and re-run H1/H6/culling.
2. Then clipmap with level hysteresis to cut re-sends (TECHNIQUE_MAP section 4b).
3. Then Phase 1 experiments E1/E2 (docs/EXPERIMENTS.md).
