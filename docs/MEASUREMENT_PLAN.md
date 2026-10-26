# Server capture / revalidation measurement plan

**Result status: NOT RUN** (Session 27, 2026-10-22: no Paper server with ~4 100 loaded chunks and no network). Instrumentation exists; no runtime numbers do.

## What is instrumented (compile-unverified until CI/local build runs)
`AwreCapture` records, in fixed-size ring buffers (2048 samples each, `TimingWindow`, tested in `TimingWindowTest`):
- `snapshot[...]`: time of one `ChunkSnapshot` + ingest submit on the main thread (p50/p95/p99/max).
- `tick[...]`: whole capture tick (sweep enqueue + backlog pump + drain), the number to compare with the 50 ms tick budget.
- `lastSweep chunks=N enqueueMs=X`: the last revalidation sweep's size and enqueue time. The snapshot work itself is spread over later ticks (<= 2/tick, 0.5 ms budget).
It is appended to the existing capture line (`capture: requested= handled= retried= waiting= queued= peakWaiting= ...`) printed by `AwreStreaming.captureStats()`.

## Procedure
1. Paper 1.21.11, Java 21, `-Xms2G -Xmx2G`, Aikar flags, AWRE with `stream.source: captured`, `persist: true`. Load about 4 100+ chunks: e.g. view-distance 32 with 2 players spread apart, or 1 player at view-distance 32 (check `/paper chunks` or the plugin's `held`).
2. Run `stream.revalidate-seconds` = 0, 600, 120, 30 for 15 minutes each (after a 5 minute warm-up), same world and players.
3. Record per run: capture line (requested/handled/retried/waiting, snapshot and tick percentiles, lastSweep), MSPT (`/mspt` or Spark), TPS, GC pauses and heap (Spark or `-Xlog:gc`), cache write time on compaction and replay time on restart (restart and read the `terrain cache ... restored` line timing).
4. Compare MSPT at interval 0 vs each interval. Only then pick a default; acceptance: capture `tick` p99 < 2 ms and no measurable p95 MSPT increase.
5. Keep running with players moving to check `waiting` drains and `peakWaiting` stays bounded.

## Do not
Tune `stream.revalidate-seconds` from synthetic benchmarks; force-load or generate chunks to reach the target count.

## Synthetic baseline (Session 28)
`docs/scalebench-output.txt` (`bash tools/bench/run-scalebench.sh`) gives a synthetic ingest/stream baseline at 1k/4k/16k chunks. It informs expectations only: it does not measure Paper main-thread cost and must not be used to tune `stream.revalidate-seconds`.

## MSPT A/B at 4,100+ loaded chunks (Session 33; NOT RUN yet - this is what to run)

What it answers: how much main-thread time does AWRE add, and is the capture drain (now governed by `stream.capture-budget-micros`) within budget at a realistic chunk count? Nothing here has been measured yet; the only server data so far is 187 chunks on a CI runner.

Setup (write it down with every result): hardware (CPU model, cores, RAM), Java version and JVM flags, Paper build, `server.properties` (`level-type=flat`, `view-distance=32`, `simulation-distance=10`, `online-mode=false` for a local test), the plugin jar commit, cold or warm terrain cache (`plugins/AWRE/terrain/` present or deleted).

`plugins/AWRE/config.yml` for both runs: `diagnostics.mspt-seconds: 30`, `stream.source: captured`, `stream.persist: true`, `stream.revalidate-seconds: 600` (run C below: `60`).

| Run | `stream.enabled` | Player |
|---|---|---|
| A baseline | `false` | stands still 5 minutes at one spot (view-distance 32 loads about 4,225 chunks) |
| B AWRE | `true` | same spot, 5 minutes, cold cache |
| C sweep | `true`, `revalidate-seconds: 60` | same spot, 5 minutes, warm cache (restart after B) |
| D movement | `true` | after 2 minutes still: sprint in a straight line for 2 minutes, then stop |

Send back, per run: all `[AWRE] mspt:` lines, the last `/awre stats` output (it contains `capture: ... snapshot[...] tick[...] ... storeRejected= storeEvicted=`), and the `[AWRE] terrain store cap` and `compacted` lines. Compare `mspt` p50/p95/p99/max of B, C, D against A; the difference is AWRE's cost (the tick time includes everything on the main thread, not only this plugin).

Reading the result: the capture budget (average 0.5 ms per tick = 1% of a 50 ms tick) is bounded by `DrainGovernor` in source and tests; what the A/B shows is the real cost on top of it (snapshots are heavier than their measured p50, the sweep's `getLoadedChunks()` listing, event handlers, the 100 ms planner replan on the main thread, `channel.send`). If B-A at p99 exceeds about 2 ms, lower `stream.capture-budget-micros` (100..5000) first and re-run; do not raise it.
