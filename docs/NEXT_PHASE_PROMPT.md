# AWRE G1 - Next phase: prove it, measure it, then fix what the measurements show

## Role
Principal engineer on the existing `horizon-field-awre-g1` repository (Java 21, Paper 1.21.11 plugin, Fabric 1.21.11 client mod, pure-Java engine modules). Work directly in the repo. Treat the source and the evidence files as the truth; read `docs/REVIEW_HONEST.md` first, then `docs/HANDOFF.md` (top section only) and `AI_CONTEXT.md`.

## Hard rules
- Never claim a build, test, benchmark, or in-game result you did not execute and observe. Label every statement VERIFIED (you ran it), USER-SUPPLIED (from logs the user gave you), SOURCE (read in code), or NOT VERIFIED. Never invent a number; a missing measurement is written as "NOT MEASURED" with the exact command that would produce it.
- Do not disable or weaken tests, suppress errors, or change thresholds to get green. Do not discard the user's uncommitted changes (`.gitignore`, `gradlew.bat`). Keep protocol/persistence compatibility unless a migration is documented and tested.
- Keep every queue, cache, buffer and background job bounded. Respect Paper thread rules (live world only from the main thread; async work on immutable snapshots).
- Diff against the NEWEST tree before claiming a defect; a previous snapshot may already have fixed it.
- No new features (shader-pack drawing, other Minecraft versions, Folia, new LOD formats) until tasks 1-3 are done and documented.
- You cannot run Gradle, Minecraft or a GPU in the sandbox: for every Paper/Fabric change, say it is syntax-checked only, and give the user the exact command to build and the exact log line to send back.

## Evidence you start from (do not re-derive; verify only what you change)
- Tree `e43858b` + the review's test additions: core 118, protocol 73, stream 112 tests pass in the sandbox. User CI on an earlier commit (`1f03353`): 41 PASS / 0 FAIL; client builds 154 tiles/s; full horizon ~5 s after the stream ends; GPU 32.2 MiB for 2064 tiles; `render_smoke` was off.
- Server capture, the only sample: 187 chunks, snapshot p99 4.45 ms (max 5.49), capture tick p99 2.06 ms (max 5.81) on a 2-vCPU runner.
- Gaps: no automated proof that terrain is visible on screen; no screenshot capability; no MSPT A/B; the 4,100-chunk acceptance test never run; `TerrainStore` refuses chunks at new coordinates when full (no eviction); no Nether/ceiling handling; floating Loom/Paper snapshots; `javaRelease=25` default never built.

## Task 1 (highest value): prove terrain is visible, with pixels
Goal: an automated, machine-checkable frame from the headless CI client that shows AWRE terrain beyond the vanilla radius.
1. Investigate first: can the CI client (HeadlessMC under xvfb, software GL) take a real framebuffer capture? Find the actual 1.21.11 API in `mc-api-dump` / the game jar (do not port from memory). If a capture is impossible, document why with evidence and design the next-best check; do not fake it.
2. Add a debug capture path in the mod (config/system-property gated, off by default) that writes a PNG after the horizon converged (use the existing `horizon view:` convergence line), bounded and thread-correct (render thread).
3. Add a CI check on that PNG with a CONTROL: run the same scene with `-Dawre.render=off` and compare. PASS only if the AWRE run has terrain-coloured pixels in the far band where the control has sky/void, with explicit thresholds you justify. Report the false-positive and false-negative risks.
4. Upload the PNGs as CI artifacts. Record the result in `docs/COMPATIBILITY.md` only for what was actually run (date, commit, loader mix).
Acceptance: a green run that fails if AWRE draws nothing (prove it by running with render off and showing the check fails).

## Task 2: measure server cost at scale, then bound it
1. Build a repeatable harness (script + documented command) that runs Paper with at least 4,100 loaded chunks (e.g. flat world, view-distance 32, one fixed-position player, then a walking path), with and without the plugin (A/B), recording MSPT p50/p95/p99/max, `/awre stats` capture and store lines, heap, and one revalidation sweep. State hardware, JVM flags, world, path, cache state (cold/warm).
2. Compare with the project's own targets (capture tick p99 < 2 ms). If exceeded, implement the smallest bounded fix with a test: for example stop draining for the rest of the tick after one snapshot exceeds the budget, adapt `MAX_PER_TICK` to the measured snapshot cost, skip when the previous tick was already slow, and spread the sweep. Re-measure; keep the A/B numbers in a doc.
3. Bound the per-player streaming concurrency (currently one async job per player on Bukkit's unbounded pool). Measure N simulated clients with the real `PlayerStreamer` before deciding; do not add a limit without data.
Acceptance: numbers (or "NOT MEASURED + command") for MSPT A/B, capture p99, sweep cost, and N-player CPU; any added limit has a test.

## Task 3: fix the store-cap priority inversion
When `TerrainStore` is full it refuses new coordinates and the cache replays oldest-first, so a long-running server shows whatever was captured first. Design and implement an eviction or priority policy that keeps the chunks nearest to current players (and recent edits), never drops a chunk that a client still needs without re-streaming it, keeps tile revisions monotonic, and surfaces refusals/evictions in `/awre stats`. Preserve the guarantees tested in `TerrainStoreCapTest` or replace them with equally strict tests. Prove with a deterministic test that after the cap is reached, exploring a new area near the player gets captured while far-away old chunks are evicted, and with a restart test that the on-disk cache is not corrupted.

## Task 4: small correctness guards (each with a regression test)
- Skip or gate ceilinged dimensions (Nether-like) so their roof is never streamed as terrain; make the behaviour a documented config key. Do not claim Nether support.
- Reset a blacklisted tile when its revision changes.

## Task 5: reproducibility and feedback loop
- Read the resolved Loom and Paper API versions from a CI log (ask the user for the `build` job output if you cannot see them) and pin them; decide the Java matrix (21 only, or add a non-blocking Java 25 job) and make `gradle.properties` say what CI builds.
- Document a Windows pre-push command the user runs locally (`gradlew.bat build -PjavaRelease=21` plus the Fabric build) so compile errors are found before CI.

## Task 6: documentation hygiene
One "LATEST" marker only; keep `AI_CONTEXT.md` short; update `STATUS.md`, `HANDOFF.md` and the relevant phase doc only with verified facts; keep the honest-review style (ranked weaknesses, evidence labels). Do not create a new long report per session.

## Process
Work in this order: baseline (run all `tools/verify-*.sh`, `tools/ci/render-gate-test.sh`, `ParseOnly`), then tasks 1-3, then 4-6. For every fix: root cause with source reference, smallest change, a test that FAILS without the fix (show that by temporarily reverting it), full suite rerun, diff review. Do not stop after documentation; continue until each task is done or blocked with the exact command the user must run.

## Deliverables
1. A `git apply`-able patch against the user's repo (list of files, no unrelated changes) and a complete ZIP.
2. Updated docs (see task 6) and the new harness scripts with usage lines.
3. A short result table: task, status (DONE / PARTIAL / BLOCKED), evidence label, what the user must run next.
4. Exactly one final status: `READY FOR EXPERIMENTAL TESTING`, `NOT READY - CORE CORRECTNESS ISSUES`, `NOT READY - PERFORMANCE OR SECURITY ISSUES`, or `READY FOR A LIMITED PRODUCTION PILOT`, justified only by executed evidence.

Start by reading `docs/REVIEW_HONEST.md`, confirming the baseline, and diffing the repo against `e43858b`.
