# Technique map — which advanced rendering/streaming ideas fit Viking Engine

Labels: VERIFIED / MEASURED / INFERENCE / HYPOTHESIS / UNKNOWN (per Vikingplan §5).
**No web research was done in this session**; prior-art statements below are general knowledge = INFERENCE, with no
source links yet. Add citations before relying on any of them.

## 0. The key split
A vanilla client cannot run custom GPU code (ARCHITECTURE §1: vanilla = chunks/entities/maps only). So:
- **Server-side / protocol-side techniques** can be built and tested now (Paper plugin, `horizon-core`).
- **GPU/client techniques** only matter on the optional Fabric branch (Module H, not started). They are useless
  until Gate 2 (a client renders ANY server-sent surface beyond vanilla range) passes.

## 1. The 13 techniques, classified
| Technique | Where it lives | Fit | Verdict |
|---|---|---|---|
| Data-oriented design | server + client | Flat primitive arrays, no per-column objects, batch processing | **Do now.** `ColumnTile` already uses `short[]`; next: pool/slab storage for tiles instead of one object each (HYPOTHESIS H5 measures it) |
| Palette compression + bit packing | wire + disk | Tile = 256 heights + 256 materials; few distinct materials per tile | **Done as experiment**: `TileCodec` (MEASURED below) |
| Hierarchical cache + adaptive eviction | server RAM→disk, client RAM→VRAM | `HeightPyramid` is already a hierarchy; eviction policy not implemented | **Do in Phase 3** (H5) |
| Disk-backed cache + memory mapping | server | `TileLog` is append-only, replayed fully on first use (README limitation) | **Do in Phase 3**: index + mmap random access, avoids synchronous replay. Risk: mmap'd file + crash recovery + Windows file locking (INFERENCE) |
| Geometry clipmaps | client + stream scheduler | Nested square rings, resolution halves per ring, toroidal update when the viewer moves | **Strong fit** as the *streaming layout* even if rendering is a plain mesh: ring k = pyramid level k (H1) |
| Sparse voxel hierarchy / SVO | data format | Only needed where heightfield fails (overhangs, caves, floating islands) | **Later** (H4). Do not start before the heightfield path works end to end |
| Ray-guided streaming | server priority | `VisibilitySweep` is already a conservative horizon sweep: its output can rank which tiles the player needs first | **Strong fit** (H6); server-side analogue, no GPU feedback needed. GPU-feedback variant needs client telemetry (untrusted, optional) |
| GPU-driven rendering + indirect draw | client | Few draw calls for thousands of tiles | **Fabric branch only.** Need to verify what 1.21.11's renderer lets a mod do (UNKNOWN) |
| Meshlet compression / clusters | client + wire | Tile = natural cluster (e.g. 16x16 column patch ≈ 512 tris) | **Fabric branch**, after a plain mesh works. Quantized positions are free here: x,z are grid-aligned, only Y varies |
| Nanite-style virtualized geometry | client | Cluster DAG with error-bounded LOD selection | **Reject as a full system** (needs mesh shaders/compute pipeline Minecraft mods don't have by default, INFERENCE). Keep one idea: error-bounded LOD switching per tile (H2) |
| GPU geometry compression + buffer pooling | client | Big pooled VBO/SSBO, slab-allocated per tile, free-list | **Fabric branch.** Pooling idea also applies to server byte[] buffers (reduce GC) |
| GPU-side decompression | client | Upload packed bits, unpack in vertex shader | **Fabric branch, late.** Only worth it if CPU decode shows up in client profiling (MEASURE first) |
| GPU terrain synthesis (clipmap + procedural) | client | Needs a client-side noise that matches vanilla worldgen exactly | **Reject for now**: matching vanilla generation on the client is infeasible without server data (worldgen depends on datapacks/settings/structures, Vikingplan §9) |
| Neural rendering / radiance fields | client | Needs training per world, heavy GPU, stale after edits | **Reject for this project** (cost vs. a heightfield; no way to invalidate on block edits). Revisit only as a pure research branch |

## 2. Research hypotheses (≥5, each falsifiable)
**H1 Clipmap-ring streaming.** Represent = pyramid levels as concentric rings; discard = fine detail beyond ring k.
Update = toroidal: when the player moves one tile, only the new edge row of each ring is requested.
Cost/benefit metric: bytes sent per block moved vs. distance-sorted baseline.
Smallest disproof: simulate a flight path on synthetic terrain; if ring-streaming bytes/move are not lower than
nearest-first streaming at equal coverage, drop it. Prior art: Losasso & Hoppe clipmaps (INFERENCE, uncited here).

**H2 Error-bounded LOD per tile.** Pick the coarsest pyramid level whose max vertical error projected on screen < 1 px
(needs eye position and FOV). Disproof: on captured real terrain, if visible silhouette error at the chosen levels exceeds
the bound in a render comparison, the bound is wrong. Requires the Fabric renderer to measure; synthetic height-error only until then.

**H3 Packed wire format.** `TileCodec` + batch deflate. MEASURED below (synthetic). Disproof: already partly done —
see numbers; on rough terrain the gain over "raw shorts + batch deflate" is ~35%, not 10x.

**H4 Surface-voxel summary for overhangs.** Per tile, keep a sparse list of (y-range, material) spans per column
instead of one top value. Disproof: on a captured cave/overhang-rich area, if spans per column stay ≈1 the heightfield was enough.

**H5 Slab storage + mmap index.** One `short[]`/`byte[]` slab per pyramid level instead of a `ColumnTile` object per
chunk; mmap'd disk index for cold tiles. Disproof: compare heap bytes and replay time for 100k tiles; if slab is
not ≥2x smaller or restart is not faster, keep objects.

**H6 Visibility-ranked streaming.** Order requests by `VisibilitySweep` result (visible first, then by distance).
Disproof: in a simulated flight, if time-to-visible-coverage is not shorter than distance-only ordering, drop it.
Important: this saves nothing on a vanilla client that never receives culled terrain anyway (Vikingplan §14).

## 3. MEASURED this session (synthetic noise terrain, JDK 21 sandbox)
Reproduced: `tools/verify-core.sh` 21/21 original tests pass (24/24 with the new codec tests).
Reproduced: `SweepBench` shows 9%/30%/39% of far-ring chunks culled, 2.8–3.7 ms best-of-30 per sweep (matches the
handoff's "9–39%, 3–6 ms"). Still synthetic, single machine, single viewpoint: it says nothing about real Minecraft terrain.

`tools/bench/CodecBench.java`, 1600 tiles per row, bytes per tile:
| roughness | v1 (per-tile Deflate) | codec raw | codec + batch deflate | baseline: raw shorts + batch deflate |
|---|---|---|---|---|
| 10  | 48.7  | 38.1  | 8.0  | 19.0  |
| 40  | 119.0 | 108.5 | 42.1 | 77.0  |
| 120 | 155.3 | 151.7 | 70.1 | 114.4 |
| 220 | 163.0 | 167.5 | 82.2 | 126.4 |
Reading: per-tile Deflate wastes its dictionary on 512 values, so batching matters more than bit-packing; the codec adds
~35–58% on top of batching. On very rough tiles raw codec is slightly larger than v1 (0.97x) — a fixed-width pack cannot
beat entropy coding there. Encode-time figures were not reported as a conclusion (cold JIT, no warm-up control).
Not tested: real terrain, real material distributions, decode cost, a real network.

## 4. H1 / H6 streaming simulation (SYNTHETIC) — `tools/bench/StreamSim.java`, raw output in `docs/streamsim-output.txt`
Setup: fractal-noise terrain (roughness 120), straight flight along +X, 120 steps (assume 1 step = 1 s of movement; NOT calibrated),
ring [128,512] blocks beyond a vanilla range of 8 chunks. Ground truth visibility = independent dense horizon ray march
(2048 rays, 2-block steps), NOT the sweep under test. Tile cost = real `TileCodec.encode` bytes (raw, no batch deflate).
Clipmap rings: L0 [128,256) 1-chunk tiles, L1 [256,384) 2-chunk, L2 [384,512] 4-chunk, 2-chunk hysteresis eviction.
Ranking only reorders; it never drops tiles. Runtime of the full run: 24 s. Single seed, single path, single roughness.

| Scenario (budget/step) | Strategy | tiles | KB | t90 (step) | late coverage | mean abs height err (blocks) |
|---|---|---|---|---|---|---|
| walk, 4 KB | S1 L0 nearest | 3093 | 460 | 45 | 81.1% | 0 |
| | S2 L0 sweep-ranked | 3098 | 461 | 45 | 91.6% | 0 |
| | S3 clipmap nearest | 2762 | 439 | 45 | 95.8% | 0.31 |
| | S4 clipmap ranked | 2792 | 445 | 45 | 98.3% | 0.33 |
| walk, 16 KB | S1 | 5141 | 765 | 20 | 100% | 0 |
| | S2 | 5180 | 771 | 17 | 100% | 0 |
| | S3 | 3304 | 533 | 10 | 99.3% | 0.34 |
| | S4 | 3306 | 533 | 9 | 99.3% | 0.34 |
| elytra, 16 KB | S1 / S2 | 12620 | 1866 | >120 | 29.9% | 0 |
| | S3 / S4 | 12620 | 1866 | >120 | 17.9% | 0 |
| elytra, 64 KB | S1 / S2 | 28247 | 4146 | 6 | 99.9% | 0 |
| | S3 / S4 | 24970 | 4012 | 2 | 99.1% | 0.32 |

Sweep vs truth (same centre-distance filter on both): false occlusion **0.00%** in all scenarios and for 1024/2048/4096 azimuth rays;
but precision is low at ground level (sweep set = **6.3x** the truth set) and tight at altitude (1.2x).

### What this supports / does not support
- **H6 (visibility-ranked order): NOT disproved, modest.** When bandwidth-starved at ground level, late coverage rises 81.1% → 91.6% (L0) and
  95.8% → 98.3% (clipmap). No effect at altitude (almost everything is visible, ranking ≈ distance order). It saves no bytes by itself.
  ~75% of bytes at ground level go to tiles with no truth-visible chunk at send time (`useful%` 17–29%): that is the *potential* of culling,
  not a result. Culling by the sweep is untested (sweep is 6.3x loose at ground level, so the saving would be much smaller than 75%).
- **H1 (clipmap rings): SUPPORTED on synthetic data when bandwidth is adequate.** Walk 16 KB: -36% tiles, -30% bytes, t90 20 → 10 steps.
  Elytra 64 KB: -3% bytes, t90 6 → 2. **Contradicted when bandwidth-starved at high speed** (elytra 16 KB: 17.9% vs 29.9% coverage).
  The first guess (re-sending tiles at a finer level) was REFUTED in section 4b: churn is 0% in that case.
- **Confidence limits:** the synthetic terrain is smooth (finest octave wavelength ~16 blocks), so the 0.3-block LOD error is NOT transferable;
  real terrain with block-scale cliffs/trees/water will give larger error. Coverage counts a chunk as covered by a coarse tile.
  Coarse tiles cost slightly more bytes each (fixed-width pack of a wider height range). No real network, no prediction, no multi-player.
- **Measurement bugs found and fixed during this session (kept for the record):** (1) first error metric sampled exactly at the
  tile sample points → always 0; (2) a ray-count study used positions outside the terrain bounds → meaningless 42% miss;
  (3) truth set and sweep set used different distance filters → fake 3.5–4% "false occlusion". All three corrected before the numbers above.
- Smallest next tests: run on captured real chunks; try a clipmap with hysteresis that avoids level churn; wall-clock cost of per-player sweeps.

## 5. Notes from the uploaded Distant Horizons zip (ideas only; no code copied; GPL-3/LGPL-3)
The zip has `common/` + loader modules only. The `coreSubProjects` submodule (LOD tree, data sources, scheduling) is **not in the archive**, so
DH's LOD tree and streaming policy could NOT be studied. What was read (VERIFIED by reading the files; behaviour not run):
- `DhRoughSurfaceGenerator`: predicts surface from sparse samples — evaluates heights on every 2nd column in x and z (1 in 4 columns), interpolates the
  rest, marches down in steps with "floating island" sanity checks, and smooths underwater spikes. Direction for our PREDICTED tiles; it relies on
  Minecraft's `RandomState`/`DensityFunction` (server internals), so on Paper it means an isolated NMS module and per-version risk. Uncertainty must stay labelled PREDICTED.
- Detail levels = sections of 2^detailLevel blocks per data point; same idea as our pyramid levels.
- Pooled arrays (`PhantomArrayListPool`) to cut GC churn; matches H5 buffer pooling.
- `RegionFileStorageExternalCache` / `ChunkFileReader`: reads persisted chunks off-thread; comments warn about thread-safety and a corruption risk with the
  C2ME mod. Confirms Vikingplan §9: raw region reads can race with the server; treat as a high-risk source.
- Networking (`AbstractPluginPacketSender`, `CommonPacketPayload`): a custom payload that starts with a protocol version number and rejects mismatches on decode.
  Same pattern as our Gate 1 requirement; the exact Paper-side transport on 1.21.11 is still UNVERIFIED for us.
- Their code supports many MC versions through a preprocessor (`#if MC_VER ...`): a hint that version-sensitive code must stay thin.

## 4b. Follow-up: clipmap re-send churn and sweep culling (same synthetic setup; raw output in `docs/streamsim-output.txt`)
**Churn** = a tile is "re-leveled" if any chunk in its footprint was previously sent at a different level.
| Scenario | clipmap bytes vs L0 | re-leveled share of clipmap bytes |
|---|---|---|
| walk 4 KB | -4.5% | 48.5% |
| walk 16 KB | -30.4% | 54.4% |
| elytra 16 KB | 0% (budget-bound) | 0.0% |
| elytra 64 KB | -3.2% | 74.5% |
- Half to three quarters of the bytes a clipmap sends are re-sends at another level; at elytra speed that cancels almost all of its saving (-3%).
  Walking still nets -30% because rings change slowly. A level-aware hysteresis (not tested) is the obvious next lever.
- **My earlier explanation for the starved elytra loss was wrong.** Churn is 0% there. The actual cause was eviction: S3 drops a fine tile when it leaves
  its ring even though no coarse replacement has arrived, and with the budget exhausted it never does. Variant **S5 (keep a tile until its replacement is
  cached)** restores coverage to exactly the L0 result in that case (29.9% vs 17.9%), and changes nothing in the other scenarios. Verdict: confirmed by the
  S5 experiment (synthetic).

**Sweep culling** (L0 nearest-first; only tiles inside the last sweep's visible set are candidates; baseline = no culling at the same budget):
| Scenario | Variant | KB saved | late coverage (min) | holes/step |
|---|---|---|---|---|
| walk 16 KB | sweep every step, slack 8 | 21.1% | 100% (99.8%) | 0.0 (same as baseline) |
| walk 16 KB | sweep every step, slack 32 | 3.4% | 100% (99.8%) | 0.0 |
| walk 16 KB | every 4 / 10 steps, slack 8, unpadded | 22.1% / 24.1% | 98.9% (96.1%) / 96.2% (90.8%) | 5.8 / 19.8 |
| walk 16 KB | every 4 / 10 steps, slack 8, **radius padded by distance moved** | 21.2% / 21.4% | 100% (99.8%) | 0.0 |
| elytra 64 KB | sweep every step, slack 8 | 0.5% | 99.9% | 2.0 (same as baseline) |
| elytra 64 KB | every 4 / 10 steps, unpadded | 3.6% / 21.7% | 86.7% (76.0%) / 60.2% (34.4%) | 326 / 962 |
| elytra 64 KB | every 4 / 10 steps, padded | 0.7% / 2.0% | 99.7% (97.2%) / 97.4% (89.0%) | 6.7 / 64.8 |
- On the synthetic ground-level walk, culling with a fresh sweep saves ~21% of bytes with no extra holes; a looser slack (32) gives back almost all of it.
- The holes with a stale sweep were caused by the sweep's ring [128,512] no longer matching the moved player (new frontier chunks were outside the stale set),
  not by viewpoint change: padding the sweep radius by the distance moved removes them completely when walking. At elytra speed padding is not enough
  (viewpoint drift of 48 blocks/step matters) and the saving is only 0.7–2%, so culling is not worthwhile at altitude (nearly all terrain is visible).
- The sweep covers all azimuths, so culling does not depend on which way the player faces; turning the head was not the risk. Culling by field of view was not tested.
- Not included in the savings: sweep CPU cost (about 3–4 ms per sweep per player on this sandbox, single run), and everything in the "confidence limits" list above
  (smooth synthetic terrain, one path, one seed). The 21% figure may be very different on real terrain with trees, caves and water.

## 4c. REAL-terrain re-run of 4/4b (MEASURED on one real capture) — `tools/bench/RealStreamSim.java`, raw output `docs/realstreamsim-output.txt`
Setup: one real `.hfl` (4761 chunks, x[-33..35] z[-32..36], 41.5% water / 25% grass / ~16% leaves / ice 5.7%, mean |dY| 0.51), JDK 21 sandbox.
Sim radius SCALED to MAXR=256 (MINR=64, rings 128/192; budgets x0.25) because the capture is only ~1100 blocks wide; viewpoints keep their whole disc inside the capture.
Truth = independent dense ray march over the real 2-block grid; unknown chunks neither occlude, count as truth, nor are sent. Path row = roughest of 5 rows (z=-256, at the capture edge). "Steps" are abstract, not seconds.
- **Sweep safety (49 grid viewpoints, ground+2 and ground+60 eyes, 1024/2048/4096 rays): mean and worst false occlusion 0.00%.** Path runs: 0.00% / 0.01%. More rays changed nothing.
- **Sweep tightness at ground level: sweep set = 11.8x truth on the path, 31x on the grid** — i.e. it keeps essentially the whole ring. At altitude it is 1.04–1.05x (nothing to cull).
- **Culling savings on real terrain: ~0.2% bytes (ground, fresh sweep), 0% (elevated).** The ~21% seen on synthetic terrain did NOT reproduce here. An ideal culler could in principle drop ~90% of the ring at ground level (truth is ~1/12 of it) but the conservative sweep cannot. Stale sweeps still create holes (elevated, every 4 steps: 122 holes/step, 83% coverage) unless radius-padded (then 0.0–2.8 holes/step, ~0% saving).
- **Clipmap vs level-0 (adequate budget): ~14% fewer bytes (256 vs 299 KB), t90 at step 11 vs 25, late coverage 96–99% vs 100%, mean height error ~1.1–1.25 blocks.** 64% of clipmap tiles/67% of its bytes are re-sends at another level (synthetic: 48–75%). Keep-until-replaced (S5) is again the best clipmap variant (coverage 98.7%/96.9% vs 98.0%/96.1%).
- **Starved budget:** plain clipmap (S3/S4) loses coverage to L0 (22.9% vs 31.0% walk; 10.6% vs 21.5% elevated); S5 matches L0 exactly. Visibility ranking (S2/S4) was identical to distance ranking everywhere here (sweep too loose to reorder).
LIMITS: one world, one location, mostly water/flat/forest, no mountains; radius scaled; top-surface heights include leaves; eye = top+2 (canopy top); single path; no real client/network. Treat as one data point, not a conclusion about Minecraft in general.

## 4d. Level hysteresis (S5h / S6) on the same real capture — `tools/bench/RealStreamSim.java` mode `hyst`, raw output `docs/realstreamsim-output.txt`
Same setup as 4c (MAXR=256, rings 64/128/192/257, row z=-256, scaled budgets, abstract steps). S6 = keep-until-replaced + a tile is NOT re-requested at another level while a cached tile covering the same
area is still inside ITS OWN ring band (ring +/- HB, HB=32 blocks). A first S6 version only checked "a covering tile is cached" and so never replaced tiles kept by keep-until-replaced (tiles -60%, error x2: meaningless, fixed).
S5h = S5 with the same band HB, so S5h vs S6 isolates the hysteresis rule (HB also changes eviction, so S5 vs S5h differs slightly on its own).
| path, budget | S1 L0 KB | S5 KB | S5h KB | S6 KB | S6 vs S5h | err S5h -> S6 (blocks) | late coverage S5h -> S6 |
|---|---|---|---|---|---|---|---|
| straight, 4 KB/step | 299 | 256 | 244 | 202 | -17% | 1.06 -> 1.49 | 99.1% -> 99.1% |
| back-and-forth, 4 KB/step | 216 | 175 | 148 | 93 | -37% | 0.98 -> 1.15 | 99.5% -> 99.5% |
| back-and-forth, starved (1 KB/step) | 89 | 85 | 86 | 70 | -19% | 0.53 -> 1.02 | 64.8% -> 92.5% (L0: 62.7%) |
(straight, starved 1 KB/step: S6 == S5h; the budget is exhausted before any re-level is possible.)
Reading: a straight walk crosses each ring once, so most re-sends are intrinsic (S6 still saves ~17%); back-and-forth movement is where hysteresis earns its keep. Under starvation fewer re-sends leave budget for new areas, so coverage rises a lot,
at the price of keeping coarse tiles longer (error roughly doubles in that case). HB was swept only in an earlier independent run on a calmer row (z=8, kept as `docs/realstreamsim-hysteresis-calm-row-output.txt`; that run used an earlier
code path that is NOT reproducible with the merged tool): S6 saved 11–28% (straight) and 33–38% (back-and-forth) as HB went 32 -> 64, error +0.2–0.7 block. Same direction, so HB is a quality-vs-bytes dial, not a free win.
LIMITS: one world (41% water), one rough row at the capture edge, one speed, abstract steps, no network model, top-surface heights include leaves. Not a statement about Minecraft in general.
