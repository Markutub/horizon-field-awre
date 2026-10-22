# Manual human-visible render test (Minecraft 1.21.11, Fabric, Paper 1.21.11)

**Result status: NOT VERIFIED.** Written 2026-10-22 (Session 27). No display was available; nothing below has been executed, and no
screenshots exist. Headless counters (`tools/ci/render-gate.sh`) show the renderer *executed*; only this procedure can show the terrain *looks right*.

## Setup (baseline renderer only)
1. Paper 1.21.11 with `AWRE-Paper-*.jar`; config `stream.enabled: true`, `stream.source: captured`, `stream.persist: true`, `debug: true`; `server.properties` `view-distance=4`, `simulation-distance=4`.
2. Client: Minecraft 1.21.11 + Fabric Loader + Fabric API + `awre-fabric-*.jar`. **No** Sodium, Iris, VulkanMod or other render mods. Video settings: render distance 4, fog on, default FOV.
3. Pre-generate a varied area (mountains + ocean + forest) by flying/elytra with a second account, so there is *observed* terrain beyond the client's vanilla square. Never use `/chunky`-style forced generation as the AWRE capture source: AWRE only captures already-loaded chunks.
4. Record: client log, server log, screenshots (F2) per check, GPU/driver, resolution.

## Checks (record PASS / FAIL / NOT RUN with a screenshot reference)
| # | Check | How | Pass criterion |
|---|---|---|---|
| 1 | Terrain beyond the vanilla edge | Stand still at a high point, look at the horizon | Coloured terrain visible past the vanilla fog/render edge |
| 2 | Max-distance / far plane | Look toward the configured `stream.radius` edge; fly toward it | Terrain not sliced by the far plane inside the configured distance; ends where config says |
| 3 | LOD transitions | Fly slowly across a LOD boundary | No holes/gaps, no flicker, no z-fighting band against vanilla chunks |
| 4 | Seams / skirts on real terrain | Look along steep ridgelines and coastlines | No see-through cracks between tiles |
| 5 | Negative coordinates | Teleport to x,z around -50 000 | Same appearance as positive; no jitter |
| 6 | Large coordinates | Teleport to x,z around 1 000 000 and 29 000 000 | No vertex jitter while moving (camera-relative precision) |
| 7 | Block edits / revisions | Break/place a large block structure inside the observed area; wait for `stream.revalidate-seconds` or edit via block events | Distant terrain updates; no stale duplicate geometry |
| 8 | Dimension change | Nether portal, then back | Old-dimension terrain gone immediately; no wrong-dimension geometry |
| 9 | Disconnect / reconnect | `/disconnect`, rejoin 3 times | Terrain returns; `gpu_resident` returns to 0 on disconnect (render counter line) and no exception |
| 10 | Shutdown | Quit client normally | Clean exit; no "leaked buffer" or exception lines |
| 11 | Nether ceiling | Fly under and above the bedrock roof | Record behaviour (roof is not special-cased; known limitation) |

## Compatibility (each separately, after the baseline passes; none claimed today)
Sodium, Iris (with a shader pack), VulkanMod: repeat checks 1, 3, 4, 8. Folia, other Minecraft versions, vanilla clients: not tested, not claimed.

## Report template
`date | commit | GPU/driver | checks passed/failed/not-run | screenshots | log excerpts | first failing check`

## Related automated check (Session 28)
`bash tools/preview/run-reference-frame.sh` renders CPU reference frames of the same LOD plan/meshes at origin, negative and large coordinates (CI uploads them as the `reference-frames` artifact). Compare your in-game screenshots with those frames: shape and LOD seams should agree. It does not replace any check above (no GPU, fog, lighting, biome tint or mod behaviour).
