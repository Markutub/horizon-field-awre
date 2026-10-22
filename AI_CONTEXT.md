# AI_CONTEXT — Horizon Field / AWRE

Last verified: 2026-10-22 (Session 18) on the uploaded session-17 docs ZIP; core 71 / protocol 67 / stream 32 pure-Java tests pass; Gradle/Fabric/CI/in-game not run; pure-Java verification scripts run locally in this session. No Git commit SHA or current remote CI result was available in the snapshot.

## Purpose and current phase

AWRE (Adaptive World Representation Engine) experiments with server-captured terrain summaries, versioned LOD tiles, bounded streaming, persistence, and an optional Fabric client renderer. The older `horizon-paper` / `horizon-core` Horizon Field prototype separately explores distant terrain for vanilla clients. These are related research paths but are **not the same delivery architecture**.

Current phase: **PHASE_01 — Fabric terrain rendering baseline** (Session 18: render path written; pure-Java part tested; `HorizonRenderer` NOT compiled/run — first CI `build` job decides). The pure-Java client view builds terrain meshes from streamed tiles and selects drawable tiles, but `awre-fabric` explicitly draws nothing. Implement and verify the smallest real render hook for Minecraft 1.21.11 before expanding the renderer.

## Target versions and builds

- Minecraft / Paper: 1.21.11.
- Java: root build defaults to release 25; CI sets `-PjavaRelease=21`. Fabric module targets Java 21.
- Gradle: root build requires Gradle 9.1+ for release 25; CI uses Gradle 9.2.1. Fabric is a separate Loom build (`awre-fabric/`, Loom 1.14-SNAPSHOT).
- Root Gradle modules: `horizon-core`, `horizon-paper`, `awre-protocol`, `awre-stream`, `awre-paper`.
- Separate Fabric module: `awre-fabric`; it compiles protocol/core/stream sources directly and is built with a separate Gradle invocation.

## Actual high-level data flow

Paper (`awre-paper`) captures eligible already-loaded chunk snapshots → `ChunkIngest` / `TerrainStore` creates revisioned `LodTile`s and palettes → `TerrainPersistence` writes versioned `.hfl` logs → `PlayerStreamer` / `StreamScheduler` sends bounded, prioritized tile payloads over `awre:main` → `ClientSession` validates protocol/payload/revision → `ClientHorizonView` asynchronously meshes selected tiles (tile-local meshes) → `HorizonDrawPlanner` (uploads via `MeshResidency`, draw selection, camera offsets) → `HorizonRenderer` (only Minecraft GPU code, unverified).

The separate legacy path (`horizon-paper`) captures loaded chunks into `ColumnTile` summaries, stores a `TileLog`, builds a `HeightPyramid`, and runs `VisibilitySweep`; `DeliveryChannel` is an interface and does not prove distant rendering.

## Critical invariants

- Never force-load or generate chunks to fill a distant cache.
- Do not access Bukkit/Paper world state asynchronously unless the API explicitly permits it; analyze immutable snapshots off-thread.
- Server state is authoritative; unknown or predicted terrain must not be presented as confirmed capture.
- Reject malformed/oversized payloads and stale revisions; bound queues, assemblies, caches, and in-flight work.
- Keep Paper logic independent of Fabric rendering.
- Vanilla-client compatibility is a separate, experimental path; AWRE's custom tile protocol requires the optional mod for custom geometry.
- Compilation, pure-Java tests, CI integration, and in-game rendering are distinct verification levels.

## Read next

1. `docs/STATUS.md`
2. `docs/phases/PHASE_01.md`
3. `docs/architecture/OVERVIEW.md`
4. `docs/architecture/DECISIONS.md`
5. `docs/DEVELOPMENT.md`
6. Protocol details: `docs/PROTOCOL.md`, `docs/AWRE_PROTOCOL.md`; existing implementation notes: `docs/AWRE_G1_FEATURES.md` and `docs/HANDOFF.md`.
