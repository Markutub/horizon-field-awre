# PHASE_02 — Correctness across world and LOD transitions

Status: **IN PROGRESS. Pure-Java part done and tested (Session 19). In-game checks NOT VERIFIED.**

Input state: the user reported "all green" after PHASE_01, but the build log they supplied afterwards shows the Fabric build FAILING on the session 18 code (see PHASE_01.md), so "green" cannot have covered the Fabric job (user-reported only; no log, run id or in-game result was provided, so it is not recorded as verified evidence here, and it says nothing about whether terrain is actually visible).

## Done (pure Java, `tools/verify-stream.sh`: 38/38)

| Item | Result | Evidence |
|---|---|---|
| Dimension filtering after a dimension change | **FIXED + PASS (logic)** | Bug: the filter used the dimension of the last received tile, so right after a dimension change old-dimension tiles were drawn and re-uploaded until a new tile arrived. Now `DimensionGate`: the level's dimension wins, last tile is only a fallback. Tests: `afterADimensionChangeOnlyTheNewDimensionIsUploaded`, `theLevelsDimensionBeatsTheLastReceivedTile`, `resourceKeyTextIsParsedAndGarbageIsRejected`. |
| LOD coverage: no double layer, no hole | **PASS (randomized)** | `randomizedCoverageNeverDoubleLayersAndNeverHolesWhereSomethingIsReady`: 400 random pyramids (5 LOD levels, random held subsets, random camera, vanilla radius, max distance, random 70% GPU-resident subset). Invariants: only resident candidates drawn; no two drawn tiles overlap; every lod0 cell outside vanilla that any resident tile covers is covered by a drawn tile. Mutation-checked: disabling the ancestor rule or the readiness rule makes it (and the older tests) fail. |
| Revisions superseded mid-build | **PASS** | `rapidRevisionsAlwaysEndOnTheNewestAndNeverLeakHandles`: older valid mesh keeps drawing, final = newest, one live handle. |
| Reconnects / disposal | **PASS (logic)** | `reconnectingManyTimesLeaksNothing`: 5 sessions, with and without a prior `release()`, uploads == frees, no double free. |

Fabric side: `AwreClientMod` now takes the dimension from `String.valueOf(client.level.dimension())` (text parse, avoids the `location()`/`identifier()` accessor rename). Not compiled in this sandbox (no network) — same status as PHASE_01's Fabric code.

## Not done / NOT VERIFIED

- Any in-game behaviour (positions at -5,000,000 / +29,000,000, visible LOD swaps, no flicker, level change releases GPU meshes).
- Far-plane clipping of distant tiles (PHASE_01 risk, still open).
- Edits (block changes) producing new revisions end-to-end from Paper to GPU: only the client-side revision handling is tested.
- Skirt/crack appearance between neighbouring tiles of different LOD.
- Multiple dimensions held at once under the memory cap (CPU meshes of other dimensions stay until DROP or the mesh cap).

## Manual checks to run in-game
1. Nether portal and `/execute in` dimension change: log shows `level changed: releasing GPU meshes`, `gpu_resident` drops to 0 then refills with the new dimension only.
2. Disconnect and rejoin 3 times: `gpu_resident=0` after each disconnect.
3. Edit terrain on the server (so a tile revision changes): the tile updates without a gap; `gpu_uploaded` increases by 1, `gpu_freed` by 1.
4. Fly toward/away from a LOD boundary: no flicker, no z-fighting band.

## Session 26 (audit) additions

Capture side correctness, details and evidence in `docs/AUDIT_G1.md`: `CaptureBacklog` (no lost capture requests), more edit events plus `stream.revalidate-seconds`, biome-aware snapshots, packed `ColumnTile`. Pure-Java: core 93/93, protocol 67/67, stream 49/49. Not verified: Paper compile of the new handlers, main-thread cost, in-game behaviour. Open: ceilinged worlds (Nether), client store byte cap, tile blacklist after an edit.

