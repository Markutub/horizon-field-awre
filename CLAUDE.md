# CLAUDE.md — Horizon Field / AWRE

Read this first. It is a working brief, not a replacement for `AI_CONTEXT.md` and `docs/STATUS.md`
(those are long and partly stale; see "Known doc drift" below).

## What this is
Minecraft 1.21.11 research project on **distant terrain beyond vanilla render distance**.
Two related but separate delivery paths (ADR-002):
- **AWRE** (`awre-*`): Paper plugin captures already-loaded chunks -> revisioned LOD tiles -> bounded streaming over
  plugin channel `awre:main` -> optional **Fabric client mod** meshes and draws them. Needs the mod.
- **Horizon Field legacy** (`horizon-paper` + `horizon-core`): server-only observation/diagnostics for vanilla clients.
  It does **not** render distant terrain.

## Modules
| Module | Contents | Deps |
|---|---|---|
| `horizon-core` | tiles, LOD planner, stream scheduler, mesher, `TerrainStore`, `TileLog`, `CaptureBacklog` | pure Java |
| `awre-protocol` | wire format, sessions, fragmentation, validation, fault injection | pure Java |
| `awre-stream` | `PlayerStreamer`, `ClientHorizonView`, `HorizonDrawPlanner`, `ClientRuntime`, compat/Iris | pure Java |
| `awre-paper` / `horizon-paper` | Paper plugins | Paper API |
| `awre-fabric` | Fabric client mod, **separate Loom build**; compiles core/protocol/stream sources directly | Minecraft, Fabric |

Never import Minecraft/Fabric/Paper types into `horizon-core`, `awre-protocol` or `awre-stream` (Fabric compiles them as raw source).

## Commands
```bash
bash tools/check.sh            # everything runnable with only a JDK (~3 min). RUN THIS BEFORE AND AFTER EVERY CHANGE.
bash tools/check.sh --quick    # skip CPU preview renders (~2 min)
bash tools/verify-core.sh      # or verify-awre.sh / verify-stream.sh for one module
bash tools/bench/run-playerscalebench.sh   # N-player streaming cost (synthetic); other benches: tools/bench/run-*.sh
java tools/ParseOnly.java awre-paper/src awre-fabric/src horizon-paper/src   # syntax-only check of Paper/Fabric code

# On YOUR machine (network + Gradle): ONE command that builds both and writes one file to send back (errors, Loom/Paper API lines)
bash tools/feedback.sh                   # NOT yet run against real Gradle; tested only against a mock (tools/feedback-test.sh)

# Needs network + Gradle (CI does exactly these two):
./gradlew build -PjavaRelease=21
./gradlew -p awre-fabric build
```
Baseline at checkout (verified in a JDK-21-only sandbox): **core 134 / protocol 73 / stream 114, 0 failures; all 4 CI script tests and parse-only pass.**
If a count drops or a test fails after your change, that is the signal; do not edit tests to fit.

## Verification levels — never blur them in claims or docs
1. Pure-Java tests (`tools/check.sh`) — runnable anywhere with a JDK.
2. Gradle builds of Paper + Fabric — compile against real APIs; **only on a machine with network/Gradle or CI**.
3. Headless integration (`tools/ci/integration.sh`, CI only).
4. Human-visible in-game rendering — never verified by any automated step.
5. Performance at scale.
Paper/Fabric code edited without level 2 is **compile-unverified**: say so, and mark guessed API names `API-ASSUMPTION`.

## Critical invariants (from `AI_CONTEXT.md`, ADRs)
- Never force-load or generate chunks to fill the cache. Server state is authoritative; predicted terrain is never shown as captured.
- Paper/Bukkit world state only on the main thread unless the API says otherwise; analyze immutable snapshots off-thread.
- Capture requests are never dropped (`CaptureBacklog`, ADR-005). Everything else is bounded (queues, assemblies, caches, in-flight work).
- Reject malformed/oversized payloads and stale revisions. Wire, tile and cache formats are versioned: don't change them casually.
- Keep Paper logic independent of Fabric rendering. Don't claim visual correctness, perf wins or mod compatibility without the matching test.

## Environment
- Java: JDK 21 is enough (CI and Fabric use 21). Root default is `javaRelease=25` (deliberate); **always pass `-PjavaRelease=21`** unless you have JDK 25.
- Gradle: root wrapper is pinned to **9.6.0**; CI uses **9.2.1**; `awre-fabric` wrapper properties say 9.2.1 but it has no wrapper jar/script of its own. Whether 9.6.0 resolves is unverified.
- `fabric-loom` (`1.14-SNAPSHOT`) and Paper API (`1.21.11-R0.1-SNAPSHOT`) are floating, not pinned.
- Line endings: LF (`.gitattributes` enforces it). A Windows `autocrlf` checkout breaks the `.sh` scripts (`pipefail\r`).
- `gradlew` is committed non-executable: use `bash gradlew ...` or `git update-index --chmod=+x gradlew`.
- No network in the Claude sandbox => Gradle, Paper, Fabric builds and CI cannot run there. Say so rather than implying they passed.

## Options and results
- **Client options:** `config/awre-client.txt` (created on first run with every option commented and explained): `render`, `maxDistance`, `far`, `vanillaRadius`, `iris`. Precedence: `-D` JVM flag > file > default. Code: `awre-stream/.../ClientOptions.java` (validation lives there, not in the renderer). What the player sees is limited by THREE things: the server's `stream.radius` / `max-held-tiles`, the client's `maxDistance`, and the projection far plane. There is no in-game screen or client command yet.
- **Server results:** `diagnostics.report-seconds` (0 = off) and `/awre report` append one JSON line per snapshot to `plugins/AWRE/reports/awre-report.jsonl` (schema 1; `AwreReport` + `ReportJson` + `JsonlLog`). `tools/ci/report-check.sh <file> [--min-lines N] [--last-reason R] [--require-activity]` validates it. Clients cannot report back (no client-statistics message in the wire protocol: adding one is a versioned protocol change, so do not do it casually).

## Pixel proof (opt-in CI, never yet run)
`workflow_dispatch` input `pixel_check` (or `PIXEL_CHECK=1 bash tools/ci/integration.sh jars`) runs ONE non-headless client under Xvfb + Mesa software GL and captures the X display three times from outside the game: AWRE on, AWRE paused via `-Dawre.test.pauseFile` (`RenderPause`), and a second control. `tools/ci/FrameCheck.java` judges. Exit 0 PASS / 1 FAIL / 2 INCONCLUSIVE (infrastructure; never evidence about AWRE). First thing to read after a run: `ci-out/pixel/pixel-verdict.txt`, `display-info.txt`, the PNGs. Do NOT use reflection on Mojang names to reach Minecraft classes (runtime is obfuscated; only compile-time references are remapped).

## Conventions
- Tests are JUnit 5 sources; `tools/junit-shim` lets them run without Gradle, so keep to the shim's `@Test` + `Assertions` subset in pure-Java modules.
- A bug fix gets a test that fails without the fix (the repo's own history does this; mutation-check it).
- Update `docs/STATUS.md` with what was actually verified at which level. Keep it honest: "NOT RUN" is an acceptable entry.
- Benchmarks and sims live in `tools/bench`, outputs in `docs/*-output.txt`.

## Known doc drift (trust code and this file over these)
- `docs/DEVELOPMENT.md` says no root wrapper exists and CI-only Gradle 9.2.1; the repo has a 9.6.0 root wrapper.
- `AI_CONTEXT.md` header says Session 33 / HEAD `e43858b`; `docs/STATUS.md` goes to Session 37 and session numbers overlap. Git HEAD at checkout: `a0285f1`.
- `README.md` describes the original scaffold (`horizon-paper` only) and says no wrapper is bundled.

## Open items to know about (from `docs/STATUS.md`, `docs/HANDOFF.md`)
- First real CI run of the Session 37 plain-shader pipeline (`awre_terrain_plain`) and compat clients (`compat_mods`) is pending.
- Far-plane extension (`-Dawre.far`), seam depth, Iris/Sodium/Bobby/VulkanMod behavior: unverified. Bobby double-draw suspected (check 35).
- Server cost at scale (capture budget, `TerrainStore` eviction scans) not measured on a real server; MSPT A/B procedure in `docs/MEASUREMENT_PLAN.md`.
- Pinning Loom and Paper API versions needs the resolved version strings from a real build log.

## Where to read more
`docs/TROUBLESHOOTING.md` (symptom -> meaning -> fix, from a real test), `docs/MANUAL_TEST_CHECKLIST.md`, `docs/STATUS.md` (newest first) -> `docs/architecture/OVERVIEW.md`, `DECISIONS.md` -> `docs/PROTOCOL.md`/`AWRE_PROTOCOL.md` ->
`docs/MANUAL_TEST_PLAN.md` -> `docs/COMPAT_PLAN.md`, `docs/REVEAL_EFFECT.md`, `docs/REVIEW_HONEST.md`.
