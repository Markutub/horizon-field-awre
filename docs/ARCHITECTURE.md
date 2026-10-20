# Project Horizon Field — Architecture & Feasibility (Phase 0)

Status legend: **[V]** verified from a cited source · **[H]** reasoned hypothesis · **[T]** needs an experiment · **[M]** measured here (synthetic only).

## 1. Hard constraints and what a server can actually do

| Capability | Server-only, vanilla client? |
|---|---|
| A. Server-side data, caching, scheduling, choosing which packets to send | Yes |
| B. Influence what the client shows via ordinary chunks / entities / maps | Yes, but bounded (below) |
| C. New geometry/texture rendering primitives | **No** — needs a client mod |

Verified/derived facts:

1. **[V]** Paper API coordinate: `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` (Paper README snippet: https://github.com/Intybyte/Paper-PR ; a 1.21.11 server log shows "Implementing API version 1.21.11-R0.1-SNAPSHOT": https://github.com/PaperMC/Paper/issues/13611). Paper's README lists Java 21 as the build JDK; a third-party plugin reports OpenJDK 25 working (weak evidence). **[T]** Confirm Java 25 on your exact Paper build.
2. **[V]** A vanilla client **ignores chunk packets outside its view range** ("Ignoring chunk since it's not in the view range", Mojang bug MC-145813: https://bugs-legacy.mojang.com/browse/MC-145813). The mod "I See Your Chunks" has to patch `ClientChunkCache` on the client to accept out-of-range chunks (https://www.modpackindex.com/mod/164956/i-see-your-chunks). Conclusion: **chunk packets cannot show terrain beyond the client's own render-distance setting.** [H] That setting is capped by the vanilla options UI at 32 chunks (512 blocks) — verify on your client.
3. **[V]** The 1.21.11 client has fog data with *separate* environmental and render-distance fields (`FogData.environmentalStart/End`, `renderDistanceStart/End`: https://mappings.dev/1.21.11/net/minecraft/client/renderer/fog/FogData.html) and data-driven `fog_start_distance` / `fog_end_distance` environment attributes (https://mappings.dev/1.21.11/net/minecraft/world/attribute/EnvironmentAttributes.html). **[H]** The render-distance fog is not server-controllable, so anything farther than the client's render distance is fogged out *even if it is an entity*. **[T] = experiment E2. This is the project's go/no-go gate for "beyond render distance".**
4. **[V]** Distant Horizons in the uploaded zip is GPL-3.0 (`LICENSE.txt`) with LGPL-3.0 (`LICENSE.LESSER.txt`) parts. It needs a client mod. **No DH code is used here**; only the general idea (summaries instead of full chunks) informed the design.

## 2. Candidate approaches

| # | Approach | Vanilla client can show it? | Reaches beyond client render distance? | Verdict |
|---|---|---|---|---|
| C1 | **Ghost chunks**: synthetic chunk packets from cached column summaries (no real load/generation) | Yes (ordinary chunk packets) **[T E3/E4]** | No (≤ client render distance [V] above) | Build. Makes the 8→32 chunk band cheap |
| C2 | **Skyline shell**: merged prisms as packet-only Display entities | Entities yes **[T E1/E5]** | Only if fog does not hide entities **[T E2]** | Gated experiment |
| C3 | Map items / item-frame panoramas | Yes | Same entity/fog limits, flat, low-res | Parked |
| C4 | Resource-pack sky/fog tricks | Partial, unverified | Not for terrain | Parked |
| C5 | Client mod (DH/Voxy-class) | No (violates constraint) | Yes | Separate branch only (Module H) |

Honest bottom line for vanilla-only: **perceived range ≈ the client's render distance.** The server-side value is making that range cheap (little generation, little simulation, little bandwidth) and smart (don't send what can't be seen). Going beyond requires E2 to pass, otherwise a client mod (C5).

## 3. What is novel here (and what is not)

- **Not novel:** ghost/fake chunks, heightmap LODs, max-mipmaps (Tevs et al., I3D 2008), viewshed/horizon analysis (GIS), impostor/prism meshes.
- **Candidate novelty (algorithmic, modest):** a *conservative, visibility-first horizon sweep over a max/min height pyramid* used to decide **what to stream to a vanilla client** (`VisibilitySweep`):
  - visible test uses node MAX at NEAR edge; occlusion uses node MIN at FAR edge, lowered by `slackBlocks`, and only for **complete** nodes;
  - unknown terrain neither occludes nor is reported (no silent guessing);
  - confidence (OBSERVED/PREDICTED/UNKNOWN) is carried end-to-end.
- **[M] Synthetic benchmark** (`tools/bench/SweepBench.java`, fractal noise, 1024 rays, radius 128–512 blocks, slack 8): 9–39% of far-ring chunks culled, ~3–6 ms per sweep after JIT on the sandbox CPU. This is **not** a Minecraft benchmark. Real gain depends on terrain; flat worlds gain ~0. It is a bandwidth/CPU optimization, not a new rendering capability.

## 4. Modules → code

| Module | Code |
|---|---|
| A World Adapter | `paper/CaptureService`, `HorizonListener`, `ChunkSnapshotView` (only already-loaded chunks; no gen) |
| B Terrain Analysis | `core/TerrainAnalyzer`, `ColumnTile` (top Y + top material per column) |
| C Representation/Prediction | `core/HeightPyramid`, `VisibilitySweep`, `ShellMesher`; prediction not implemented (`Confidence.PREDICTED` reserved) |
| D Scheduler | `core/BoundedTaskQueue` (dedup, priority, eviction, cancel), `WorkerPool`; main-thread `drain(budget)` |
| E Cache/Storage | `core/TileLog` (CRC records, truncated-tail recovery, fingerprint invalidation) |
| F Delivery | `paper/delivery/DeliveryChannel` (interface only — gated by E1–E5) |
| G Metrics | `core/Metrics`, `/horizon status` |
| H Experimental renderer | not started |

## 5. Paper API used

| API | Thread | May load/generate chunks? | Notes |
|---|---|---|---|
| `ChunkLoadEvent#getChunk` | main | no (already loaded) | enqueue coordinates only |
| `BlockPlaceEvent` / `BlockBreakEvent` (MONITOR) | main | no | dirty marking; explosions/pistons/fluids **not** covered yet |
| `World#isChunkLoaded(x,z)`, `World#getChunkAt(x,z)` | main | `getChunkAt` would load if unloaded, so guarded by `isChunkLoaded` first | |
| `Chunk#getChunkSnapshot(true,false,false)` | main | no | snapshot is immutable → analysis off-thread |
| `ChunkSnapshot#getHighestBlockYAt`, `#getBlockType` | any (snapshot) | no | |
| `Material#getKey` | any | no | |
| `Player#getClientViewDistance/getViewDistance/getSendViewDistance/getSimulationDistance` | main | no | probe info **[T: confirm exist on 1.21.11 build]** |
| `World#spawn(Location, Class, Consumer)`, `BlockDisplay`, `Display#setViewRange/setDisplayWidth/Height/setBrightness/setTransformation`, `Entity#setVisibleByDefault`, `Player#showEntity` | main | no (spawn requires loaded chunk, checked) | experiment E1/E2 only |

The **Paper layer has not been compiled** (no network in the authoring sandbox); signatures are from memory of the Paper API → treat compile errors as expected first-build chores. Internal NMS is deliberately not used yet; ghost chunks will need it (see PROTOCOL.md) and must live in a separate version-pinned module.

## 6. Evaluation framework (define before Phase 3 ends; no invented numbers)

Baseline: stock Paper 1.21.11, documented `server.properties`/`paper-world-defaults.yml`, same seed and flight path.

Metrics: tick time (mean/p99, via Spark or `/tps` + `mspt`), main-thread time attributable to the plugin (our own `Metrics`), RSS/heap, disk (cache bytes per km²), per-player bandwidth (packet counters / `tcpdump`), chunk generation count, warm-up time, N-player scaling (1, 5, 20 viewers looking at different areas), silhouette error vs real terrain (sample 100 headings; compare skyline angle from client screenshots/synthetic raycast), post-edit staleness (time until a change is reflected).

Suggested starting acceptance criteria (tune after E-results): plugin main-thread ≤ 0.5 ms/tick mean and no tick > 5 ms attributable to us; zero chunk generations caused by the plugin; per-player extra bandwidth bounded by config; cache survives `kill -9` (TileLog test covers truncated tail).

## 7. Roadmap with evidence gates

- **Phase 0** (this doc): constraints, prior art, unknowns.
- **Phase 1 — protocol PoC** (next): E1–E5. *Gate:* a recorded result per experiment in `docs/EXPERIMENTS.md`. If E3 fails → C1 is dead on vanilla; if E2 fails → C2 is dead.
- **Phase 2 — baseline analyzer** (scaffold done): capture → pyramid → log with budgets. *Gate:* stable on a 20-player test, 0 plugin-caused generations.
- **Phase 3 — representations:** (a) ghost-column synthesis + per-chunk visibility culling, (b) shell prisms; plus a PREDICTED source (generator-based) only if the world uses vanilla generation and a safe API exists. *Gate:* storage/processing/accuracy numbers for both.
- **Phase 4 — integration:** only through verified delivery.
- **Phase 5/6:** movement, edits, restarts, memory pressure; docs and hardening.
