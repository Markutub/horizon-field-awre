# Architecture Decisions

Append decisions here. Mark superseded decisions explicitly rather than silently rewriting history.

## ADR-001 — Keep Paper and Fabric builds separate

- **Date/status:** 2026-10-21 / Accepted (existing repository arrangement)
- **Context:** Paper API and Fabric Loom have different build/plugin requirements. The root build includes server and pure-Java modules; `awre-fabric` has a standalone Gradle build.
- **Decision:** Keep `awre-fabric` outside root `settings.gradle.kts`; CI invokes a separate Fabric build.
- **Alternatives:** One composite build; publishing pure-Java modules and consuming their artifacts.
- **Consequences:** CI and development instructions must always build both entry points. Fabric currently compiles shared pure-Java sources directly, so source compatibility and duplicate class packaging must be watched.

## ADR-002 — Treat AWRE and the vanilla prototype as distinct delivery paths

- **Date/status:** 2026-10-21 / Accepted
- **Context:** The repository contains both `awre-paper` + `awre-fabric` + `awre-protocol`/`awre-stream`, and the legacy `horizon-paper` path with `ColumnTile`/`VisibilitySweep` and a `DeliveryChannel` interface.
- **Decision:** Do not describe legacy visibility analysis or delivery experiments as an implemented AWRE renderer. Document each capability against its actual path.
- **Alternatives:** Merge both models before an end-to-end milestone.
- **Consequences:** Avoid accidental protocol/model coupling. Reconsider only with an explicit migration plan and tests.

## ADR-003 — Server remains authoritative; no forced chunk generation

- **Date/status:** 2026-10-21 / Accepted (project invariant)
- **Context:** Distant data must not create hidden server load or silently replace real edits with guesses.
- **Decision:** Capture only eligible already-loaded chunks; unknown data stays unknown. Prediction requires provenance and a separate verified adapter.
- **Consequences:** Cold or unexplored regions can remain unavailable until captured or a future prediction implementation is explicitly added.

## ADR-004 — Client rendering is a required end-to-end link, not implied by meshing

- **Date/status:** 2026-10-21 / Accepted
- **Context:** `ClientHorizonView` can produce meshes and drawable tile selections, but `AwreClientMod` explicitly documents that nothing is drawn.
- **Decision:** The next implementation milestone is a minimal real Fabric render adapter and resource lifecycle, not further abstract renderer architecture or performance claims.
- **Alternatives:** Continue adding representation formats or optimize the mesher before a visible baseline exists.
- **Consequences:** Prioritize exact 1.21.11 API compatibility and in-game proof. Do not mark rendering complete based on compilation or mesh counts alone.
