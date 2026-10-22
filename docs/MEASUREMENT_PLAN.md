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
