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

## ADR-005 — Capture requests are never dropped; only in-flight work is bounded

- **Date/status:** 2026-10-22 / Accepted
- **Context:** `AwreCapture` offered each chunk to a bounded priority queue and ignored `REJECTED`/`EVICTED_OTHER`; a chunk whose request was dropped is never observed again (no event fires for an already-loaded chunk) and the persistent cache keeps the hole. Measured premise: 665 of 4761 chunks lost with capacity 4096.
- **Decision:** Requests are remembered in `CaptureBacklog` (keys only, bounded by loaded chunks) until handled; priorities are computed when work is queued; the bounded queue only caps the memory of queued payloads.
- **Alternatives:** raise the queue capacity (moves the cliff, does not remove it); drop and rely on a later reload (loses the horizon edge, the most visible part).
- **Consequences:** backpressure is now retry, not loss; a handler must return false to retry. Periodic re-observation (`stream.revalidate-seconds`) is the safety net for changes no event reports.

## ADR-006 — The server stores observed chunks in packed form

- **Date/status:** 2026-10-22 / Accepted
- **Context:** `TerrainStore` keeps one `ColumnTile` per observed chunk for the process lifetime; two `short[256]` cost ~1.3 KB per chunk with the version index (measured), ~11x the disk record.
- **Decision:** `ColumnTile` packs heights (delta from minimum) and materials (per-chunk palette) with canonical bit packing; the public API and `contentHash64` values are unchanged.
- **Alternatives:** an LRU of decoded tiles over a disk store (larger change, needed if packing is not enough); a hard cap with eviction (loses data).
- **Consequences:** ~3x smaller on synthetic terrain, bounded worst case; accessors do a few bit operations. The store is still unbounded: a disk-backed store is the next step if a real server's figure demands it.

