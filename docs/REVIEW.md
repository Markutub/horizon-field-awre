# Review & critique (Session 8)

Scope: full read of TileLog/TileLogReader/TileCodec/Capture path/Paper layer; docs and bench claims re-read. Tests re-run: 31/31 (JDK 21 sandbox, shim runner). Paper layer still NOT compiled here (syntax-parse only). Real-capture benchmarks NOT reproduced (no .hfl in this session).

## 1. Defects found and fixed (code)
| # | Defect | Evidence | Fix | Test |
|---|---|---|---|---|
| D1 | **One MaterialPalette shared by all worlds** (CaptureService). Palette ids are persisted per log; a 2nd world (nether/end) replaying its log hits `restore()` mismatch (IllegalStateException, not caught) or later writes tiles whose ids are not in that world's log → corrupt cache. | code reading (palette field + `TileLog.open` restore/`written=palette.size()`) | per-world palette/analyzer in `WorldState`; `TileLog.open` now throws IllegalArgumentException when replaying with a non-fresh palette | `TileLogTest.sharedPaletteAcrossWorldsIsRejectedOnReplay` (pins the invariant; the original failure was NOT executed on a server) |
| D2 | **Unbounded cache growth**: every ChunkLoadEvent re-captured and appended an identical tile; no compaction. | code reading | `TileDeduper` (64-bit content hash, seeded from replay); capture skips identical tiles (`storage.skipped-unchanged`) | `TileDeduperTest` (3) |
| D3 | `getHighestBlockYAt` semantics (top block vs first free) were UNVERIFIED and silently feed the whole pipeline. | docs flagged it; real capture only "looked plausible" | `ChunkSnapshotView` normalises against actual blocks (down through air, up through solids) | none possible without Paper API |
| D4 | Exception in the main-thread drain would abort the tick's remaining work and spam logs; `computeIfAbsent` returning null re-tried opening the log on every chunk. | code reading | try/catch + counter in drain; negative cache `failedWorlds` | none (Paper layer) |
| D5 | Pending analyses silently dropped at shutdown. | code reading | warning with count (chunks are re-observed on next load) | none |

D3–D5 are Paper-layer edits: syntax-parsed only. Treat as UNVERIFIED until `./gradlew build` on a networked machine.

## 2. Known weaknesses NOT fixed
- Log is still append-only without compaction (D2 only removes the main source of growth; edits/re-captures with changes still append). Replay and `TileLogReader` read the whole file into memory.
- `VisibilitySweep` allocates a `double[2]` per probe (GC churn at tens of thousands of probes per sweep × players).
- Heights include leaves (~16% of tops, max LOD error 75–80 blocks per TECHNIQUE_MAP); canopy-vs-ground is undecided.
- Invalidation covers only place/break; no explosion/piston/fluid/WorldEdit; no chunk revision numbers.
- Cache replay is synchronous on the main thread at first use of a world.

## 3. Critique of the research direction
1. **Effort is mis-ordered.** Seven sessions of simulation (clipmap, hysteresis) optimise a delivery path that does not exist. Gate 1 (handshake) and Gate 2 (a client actually drawing something) are the only results that can kill or confirm the project, and are still untouched. Until Gate 2 passes, further stream-simulation work should stop.
2. **Simulated savings are not product savings.** StreamSim counts abstract steps and codec bytes with no network model, no client mesh/GPU cost, no real chunk-sender interaction. "−37% bytes" is a statement about the simulator.
3. **Evidence base is thin.** One water-heavy world (41% water), one row at the capture edge, one speed. Hysteresis trades bytes for error (coarse tiles live longer; error up to ~2x in the starved case) — a dial, not a win. The "calm row" hysteresis result is explicitly not reproducible with the merged tool: keep it out of any decision.
4. **Server-side culling should leave the critical path.** Real data: 0% false occlusion but 3–12x too loose, ~0% saved; the earlier synthetic 9–39% (including this project's own first benchmark) did not generalise. Keep `VisibilitySweep` only as a diagnostic/ranking tool unless a canopy-aware, per-ray variant demonstrably saves a real resource.
5. **Requirement drift.** The original brief was server-only/vanilla clients; the optimized plan makes a Fabric mod the primary path (vanilla stays a fallback). That is a different product. Confirm it is intended before investing in the client branch; if not, the honest result for vanilla-only is "≤ client render distance, fog-limited (E2)".
6. **Unverified premises still driving design:** client render-distance cap of 32 (HYPOTHESIS), render-distance fog applies to entities (HYPOTHESIS), advertised-radius trick (E3), Paper plugin channel ↔ Fabric payload interop (UNKNOWN).
7. **Process risk:** parallel sessions produced merge divergence (HANDOFF has two "latest" sections; merged tool differs from older outputs). Keep one dated log, and tag each benchmark output with the git commit/tool hash that produced it.

## 4. Revised next steps
1. Build on a networked machine (`./gradlew build -PjavaRelease=21`), fix any compile errors from D3–D5.
2. Gate 1 then Gate 2 (see AUDIT_REPORT.md §D). Freeze StreamSim work.
3. Capture a mountain/coast world only if the client branch proceeds, to size LOD error and canopy handling.
4. Add log compaction + async replay before any long-running deployment.
