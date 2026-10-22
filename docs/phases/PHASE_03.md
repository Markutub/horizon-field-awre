# PHASE_03 — Real-terrain calibration and visual fidelity

Status: **STARTED (Session 21). No real capture was available, so only a SYNTHETIC measurement and one fix exist. Nothing here is calibrated on real terrain.**

## Finding 1 (measured, synthetic): tile-border cracks

`tools/bench/CrackBench.java` -> `docs/crackbench-output.txt`. A tile skirt hung a fixed `max(4, 2<<lod)` blocks below its border. A neighbour that is lower by more than that leaves an open, see-through strip.

| Synthetic terrain | Border samples with a crack (current depth) | Largest border step |
|---|---|---|
| gentle (x1) | 0 at every LOD | 2 (lod0) .. 18 (lod4) blocks |
| mountainous (x3) | lod0 9,595 / lod1 13,253 / lod2 3,457 / lod3 927 / lod4 175 | 6 (lod0) .. 53 (lod4) blocks |

Real mountains/cliffs are expected to be worse than this made-up x3 terrain, but that is unmeasured.

Fix (implemented): `TerrainMesher.meshLocalToFloor` hangs every skirt to an absolute floor (`ClientHorizonView.SKIRT_FLOOR_Y = -64`). Same triangle count, no neighbour knowledge needed, 0 cracks in the bench for every policy that reaches far enough. Tests: `TerrainMesherTest.skirtToTheFloorClosesCracksThatAFixedDepthLeavesOpen`, `floorSkirtNeverHangsUpwardOnTilesLowerThanTheFloor`, `WorldCorrectnessTest.meshesBuiltByTheViewHaveSkirtsDownToTheFloor`.

Trade-offs not measured: more overdraw (hidden under terrain), and at the edge of captured data a skirt is now a tall curtain instead of a 4-block lip. Dimensions with a build floor below -64 would still show a thin gap below it.

## Not done (needs real data or a running client)
- Capture a mountain/coast world and re-run CrackBench on real tiles (`RealTileReport` / `RealStreamSim` already read `.hfl`); record real border-step distribution.
- Canopy-vs-ground height for distant tiles (HANDOFF session 6: leaves are ~16% of tops and most of the large LOD errors).
- Colours are rounded map colours (`BlockColors`), no biome tint, water is opaque, no fog/lighting.
- Visual verification in-game of the skirt change.

## Verification
core 73/73, protocol 67/67, stream 39/39 (pure Java). Fabric build / in-game: NOT VERIFIED.
