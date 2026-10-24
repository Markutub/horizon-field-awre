# Compatibility matrix

Every cell is **NOT TESTED** until someone runs it and writes the date + result. Nothing here is a promise.
Legend: NOT TESTED / PASS (date, build) / FAIL (date, symptom) / N/A.

## Architecture rule that protects compatibility

| Layer | Modules | Minecraft-specific? | On a version/loader change |
|---|---|---|---|
| Protocol + tile format | `awre-protocol`, `horizon-core` | No | Versioned; unchanged |
| Client/server logic | `awre-stream` (`ClientRuntime`, `ClientHorizonView`, `HorizonDrawPlanner`, `MeshResidency`) | No | Unchanged |
| Thin adapter | `awre-fabric` (`AwreClientMod`, `HorizonRenderer`), `awre-paper` | Yes | **Only this layer is rewritten** |

`HorizonRenderer` must make no raw OpenGL calls (it only uses the game's own GPU layer), so it is not tied to one backend.

## Minecraft versions

| Target | Server (Paper) | Client (Fabric) | Notes |
|---|---|---|---|
| 1.21.11 | G0-G1 PASS in CI (session 21); G2 fixed, NOT TESTED | G0-G1 PASS; G2 NOT TESTED; G3 (GPU draw) NOT TESTED | Renderer still has open `API-ASSUMPTION`s |
| 26.x | NOT TESTED | NOT TESTED | Read the real jar with `javap` before porting; do not guess class names. Java version requirements differ: check before building |

## Rendering backends (client)

| Backend | Status |
|---|---|
| OpenGL (1.21.11 default) | NOT TESTED |
| Vulkan (experimental / later default in 26.x, unverified) | NOT TESTED |
| VulkanMod | TARGET, NOT TESTED. AWRE keeps rendering with it loaded and logs it. Open question: does VulkanMod implement the game's GPU layer (`GpuDevice`, `RenderPass`) that `HorizonRenderer` uses? |

Deferred GPU free (`MeshResidency` freeDelayFrames=3, unit-tested with a fake GPU) exists so a buffer is not closed while earlier frames may still read it. Whether 3 frames is enough on a real Vulkan backend: NOT TESTED.

## Mods / shaders (client)

`-Dawre.render=auto|on|off`. `auto` turns terrain drawing off (protocol and tile storage still run) ONLY when Distant Horizons or Voxy is loaded (they draw the same terrain). Sodium, Iris, VulkanMod and Bobby are targets: drawing stays on and the mod is logged. `on` forces drawing, `off` disables it.

| Mod | Expected | Status |
|---|---|---|
| Sodium | TARGET. We draw our own pass from a Fabric API world-render event, not through Sodium's chunk renderer | NOT TESTED |
| Iris / shader packs | TARGET. Default: AWRE terrain keeps its own shaders (correctly placed, NOT shaded by the pack); Iris is detected and logged. `-Dawre.iris=assign` (experimental) asks Iris to shade the plain pipeline via its public `assignPipeline`; only fakes tested, placement under a pack unknown | NOT TESTED; run `compat_mods=sodium+iris`, read the `Iris detected` NOTE, then look with a pack
| Bobby | TARGET. Bobby makes cached chunks real chunks inside the render-distance setting; AWRE skips tiles inside that setting, so they should meet without overlap | NOT TESTED |
| Distant Horizons, Voxy | Overlap with what AWRE draws | NOT TESTED (disabled in `auto`) |
| Other Fabric mods | No known interaction | NOT TESTED |

## Server

| Item | Expected | Status |
|---|---|---|
| Vanilla client | Never receives AWRE data, unaffected | NOT TESTED outside CI |
| Plugins (general) | Plugin uses Paper API + snapshots, no NMS | NOT TESTED |
| Proxies (Velocity / BungeeCord) | Must forward the `awre:main` plugin channel | NOT TESTED |
| Multiworld / custom dimensions | Tiles carry the namespaced world key (`World#getKey()`), same string the client sees | NOT TESTED (default worlds PASS in CI) |
| Datapack worldgen | Captured from loaded chunks, independent of generator | NOT TESTED |
| Dimensions with build floor below -64 | Client skirt floor is a constant (`SKIRT_FLOOR_Y = -64`) | KNOWN LIMITATION |
| ItemsAdder / custom blocks | Chunk data shows carrier blocks (e.g. note blocks), so distant colours would be wrong | NOT TESTED; needs a colour-override list |
| Resource/data pack | AWRE ships none (avoids `pack_format` drift); keep it that way | N/A |


## How to fill this table (CI)

Run the `ci` workflow manually (workflow_dispatch) with `compat_mods` set, e.g. `sodium sodium+iris bobby vulkanmod`; read "Compat clients" in the job summary and `ci-out/compat_<group>.log`. A PASS means: the client started, AWRE negotiated, meshed, uploaded and issued draws beside that mod. It does NOT mean the terrain is visible or shaded correctly. Copy the result here with the date and run id; only then change NOT TESTED. Also download the `mc-api-dump` artifact from the `build` job: it is the source of truth for the projection/far-plane and Vulkan questions.
