# PHASE_01 — Fabric Rendering Baseline

## Session 18 result (read this first)

**State: IMPLEMENTED IN SOURCE, PURE-JAVA PART VERIFIED, MINECRAFT PART NOT COMPILED AND NOT RUN.** The sandbox had no network (Maven/Fabric/Gradle unreachable, no Gradle installed, no Minecraft), so `gradle -p awre-fabric build` could not be attempted to completion. Nothing below claims terrain is visible in-game.

### Definition of done

| Criterion | Result | Evidence |
|---|---|---|
| Fabric build against Minecraft 1.21.11 | **FAILED once in real CI (user-supplied log, session 18 code): 1 compile error, `ctx.positionMatrix()`; fixed in session 20, re-run NOT VERIFIED**. Original note: | No network and no `gradle` in the sandbox. `HorizonRenderer.java`, `AwreClientMod.java` only syntax-parse (`tools/ParseOnly.java`: 3 files, 0 errors). First CI `build` job is the real test. |
| Terrain geometry submitted through the Minecraft renderer | **NOT VERIFIED** | `HorizonRenderer.render` issues the draw calls; never compiled/run. `submitted_draws` is only incremented after a pass completes without exception. |
| Placement at positive, negative, large coordinates | **PASS (math only)** | `RenderCoreTest.cameraRelativePlacementIsPreciseAtNegativeAndHugeCoordinates` (cameras at +-29,999,984, 16,777,217), `TerrainMesherTest.meshLocal*` (tile at x=29,999,984 exact). Not verified on screen. |
| Update when a tile revision changes | **PASS (planner level)** | `RenderCoreTest.aNewRevisionIsUploadedBeforeTheOldHandleIsFreed...` (new handle exists before old freed; failed upload keeps old mesh; failed revision not retried per frame). |
| GPU resources released on tile drop / dimension change / disconnect / shutdown | **PASS (logic) / NOT VERIFIED (real GPU)** | `...dropOutOfRangeAndReleaseAllFreeEverythingExactlyOnce` (fake GPU throws on double free; uploads == frees). Wiring: DROP/out-of-range -> `MeshResidency.sync`; level identity change -> `planner.release()`; `DISCONNECT`/`JOIN` -> `renderer.releaseAll()`; `CLIENT_STOPPING` -> release + close. Real `GpuBuffer.close()` unverified. |
| No LOD holes / double layers | **PASS (planner level)** | `...lodSwapNeverOpensAHoleOrDrawsTwoLayers...` with GPU uploads lagging 1 mesh/frame. This test found a real bug (ready child drawn on top of still-visible parent) which is fixed in `ClientHorizonView.select`. Skirts/crack hiding between neighbouring tiles of different LOD is as before and not visually checked. |
| Existing pure-Java suites pass | **PASS** | core 71/71 (was 69), protocol 67/67, stream 32/32 (was 23). |
| CI integration | **NOT RUN** | Needs a push. `tools/ci/integration.sh` gained informational G3 lines (gpu_uploaded / submitted_draws), not hard checks. |
| In-game visual verification | **NOT VERIFIED** | See manual procedure below. |
| Documentation | PASS | This file, STATUS, PHASES, AI_CONTEXT, HANDOFF updated. |

### What was built

- `TerrainMesher.meshLocal` + `Mesh.originX/originZ`: tile-local vertices (exact floats at any world coordinate). `ClientHorizonView` now builds these. `TerrainMesher.mesh` (absolute) unchanged.
- `ClientHorizonView.candidateOrder()` / `select(ready)`: draw selection by GPU readiness, with parent-hides-only-when-4-children-ready and no-child-over-visible-ancestor rules.
- `VertexPacker` (16-byte POSITION_COLOR vertices + int indices, baked directional shade), `CameraRelative` (double offset, float result), `MeshResidency` (upload/free/caps/revision swap), `HorizonDrawPlanner` (tick/frame/diagnostics). All pure Java in `awre-stream`; no Minecraft imports.
- `awre-fabric`: `HorizonRenderer` (only class touching blaze3d) and wiring in `AwreClientMod`.
- Bounds: 4096 resident meshes, 192 MiB, 8 uploads and 2 MiB per frame (`MeshResidency.Limits.defaults()`); farthest tiles are evicted first.
- Log line `[AWRE] render: received= mesh_built= mesh_failed= gpu_uploaded= upload_failed= gpu_freed= gpu_resident= gpu_bytes= planned_draws= planned_tris= submitted_draws= submitted_frames=` every 5 s.

### API-ASSUMPTIONs the first build will confirm or break (all in `HorizonRenderer` + one block in `AwreClientMod`)

1. `RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)`, `.withLocation(String)`, `RenderPipelines.register` and shader name `core/position_color`.
2. `RenderSystem.getDevice().createBuffer(Supplier<String>, int usage, ByteBuffer)`, `GpuBuffer.USAGE_VERTEX/USAGE_INDEX`.
3. `getMainRenderTarget().getColorTextureView()/getDepthTextureView()`, `createCommandEncoder().createRenderPass(label, color, OptionalInt, depth, OptionalDouble)`.
4. `RenderSystem.bindDefaultUniforms`, `getDynamicUniforms().writeTransform(Matrix4f, Vector4f, Vector3f, Matrix4f)`, uniform name `DynamicTransforms`.
5. `RenderPass.setVertexBuffer/setIndexBuffer(buf, VertexFormat.IndexType.INT)/drawIndexed(baseVertex, firstIndex, count, instances)`.
6. `WorldRenderEvents.END_MAIN` and `gameRenderer.getMainCamera().position()`: **CONFIRMED by the first real build (javac accepted them)**. `ctx.positionMatrix()`: **WRONG** (`cannot find symbol` in `WorldRenderContext`, the only compile error reported). Replaced in session 20 by a view matrix from `Camera#rotation()` (conjugate quaternion); `Camera#rotation()` is the new open assumption.
7. `RenderSystem.isOnRenderThread()`.
Fix compile errors one by one; if only the event/matrix names are wrong, change only the lambda in `AwreClientMod.onInitializeClient`.

### Known limitations / risks (not hidden)

- **Far plane**: the vanilla projection's far clip is likely much closer than the 16,384-block default view distance, so distant tiles may be clipped until a larger-far-plane projection is used. Next step after the build works.
- Draws go in at `END_MAIN` (after translucents), no fog, no lighting, culling off, colours are approximate map colours (`BlockColors`). Water/leaf tops are opaque.
- Dimension filter still uses the dimension of the last received tile; GPU meshes are released on `Minecraft.level` identity change.
- Sodium/Iris/VulkanMod: not tested, no claim.
- GPU uploads are synchronous on the render thread (bounded per frame).

### Manual in-game test procedure

1. Build: `gradle -p awre-fabric build --stacktrace`; Paper server with the AWRE plugin (`stream.source: synthetic` is enough), client with the jar, Fabric API 0.141.3+1.21.11.
2. Client video settings: render distance 8. Join; wait for `handshake OK` and `store tiles=`.
3. Expect `[AWRE] render:` with `gpu_uploaded>=1`, then `submitted_draws>0`. If `mesh_built>0` but `gpu_uploaded=0` the upload path failed; if `gpu_uploaded>0` but `submitted_draws=0` the pass failed (look for `draw failed`).
4. Look beyond the vanilla fog edge: flat coloured terrain should be visible. Fly in all directions; check no flicker when tiles refine.
5. Teleport to x=-5,000,000 and x=29,000,000 (needs the server to have stream data there; use `stream.source: synthetic`); terrain must not jitter.
6. Change dimension / disconnect / quit: log must show release, `gpu_resident=0`.

### Change log (session 18)
Added: `VertexPacker`, `CameraRelative`, `MeshResidency`, `HorizonDrawPlanner`, `RenderCoreTest` (9 tests), `HorizonRenderer`, 2 mesher tests. Changed: `TerrainMesher` (+meshLocal), `ClientHorizonView` (local meshes, `select`, `candidateOrder`), `AwreClientMod` (renderer wiring), `tools/ci/integration.sh` (info lines), `fabric.mod.json` text. Wire protocol, tile format and persistence: unchanged.

---

## Original phase brief

## Goal

Connect the existing pure-Java terrain mesh pipeline to Minecraft 1.21.11's actual Fabric world-render lifecycle so streamed AWRE terrain is visible in-game.

## Scope

- Start with the exact current source and build configuration.
- Inspect `awre-fabric/src/main/java/dev/awre/fabric/AwreClientMod.java`, `awre-stream/src/main/java/dev/awre/stream/ClientHorizonView.java`, `TileMeshCache.java`, and `horizon-core/src/main/java/dev/horizonfield/core/terrain/TerrainMesher.java`.
- Inspect the declared Minecraft/Fabric versions and renderer APIs available to this target before choosing an event or pipeline.
- Add the minimum render adapter and GPU-resource lifecycle code necessary to draw the existing mesh output.
- Add tests for pure-Java transforms/mesh conversion if those concerns can be isolated without Minecraft classes.
- Update documentation based on observed build/test results.

## Constraints

- Minecraft 1.21.11; Fabric Loom is a standalone build; Java 21 target.
- Do not move Minecraft/Fabric dependencies into pure-Java modules.
- Do not silently change the wire protocol or tile/cache formats.
- Do not claim Sodium/VulkanMod compatibility without testing.
- Render-thread and GPU-resource lifecycle must follow the target API.
- Preserve existing drawable coverage rules and revisions; a newer mesh must not replace a valid old mesh until ready.
- Keep all existing pure-Java tests intact; do not replace real behavior with mocks.

## Planned implementation

1. Run `gradle -p awre-fabric build --stacktrace`; save exact compiler diagnostics.
2. Confirm the 1.21.11 render callback and buffer/pipeline APIs from the resolved dependency/source signatures.
3. Implement one baseline backend for existing CPU-generated mesh data. Avoid a generalized multi-backend abstraction until a second backend has a concrete use case.
4. Upload/update meshes when a drawable revision changes; remove GPU allocations for keys no longer held or drawable; release everything on dimension change, disconnect and client shutdown.
5. Apply camera-relative transforms using world coordinates with large/negative-coordinate validation.
6. Add draw counters/logging that distinguish `mesh ready` from `draw submitted` and, where feasible, visible render verification.
7. Run the three pure-Java verification scripts, separate Fabric build, and CI integration; manually validate in-game output.

## Acceptance criteria

- [ ] Fabric build succeeds against the pinned target or exact external blocker is documented.
- [ ] Actual terrain geometry is submitted through the Minecraft renderer; no placeholder or synthetic-only success claim.
- [ ] Correct placement at positive, negative and large coordinates.
- [ ] No GPU resource leak across tile revision, DROP, dimension change, disconnect or shutdown.
- [ ] No obvious holes/double layers when coarse tiles are replaced by four finer children.
- [ ] Pure-Java verification passes.
- [ ] CI/in-game evidence is recorded and differentiated from compilation.
- [ ] `docs/STATUS.md`, `docs/PHASES.md`, and `AI_CONTEXT.md` are updated at phase end.

## Files likely to inspect first

- `awre-fabric/src/main/java/dev/awre/fabric/AwreClientMod.java`
- `awre-fabric/src/main/java/dev/awre/fabric/AwrePayload.java`
- `awre-fabric/build.gradle.kts`, `awre-fabric/gradle.properties`
- `awre-stream/src/main/java/dev/awre/stream/ClientHorizonView.java`
- `awre-stream/src/main/java/dev/awre/stream/TileMeshCache.java`
- `horizon-core/src/main/java/dev/horizonfield/core/terrain/TerrainMesher.java`
- `.github/workflows/ci.yml`, `tools/ci/integration.sh`

## Initial known blockers

- The uploaded snapshot has not been compiled against actual Minecraft/Fabric APIs in this session.
- `AwreClientMod` explicitly says “Renders nothing”; there is no established rendering lifecycle/resource implementation to preserve.
- This phase cannot be marked complete from pure-Java test success or a Fabric compile alone; visible output needs in-game or equivalent renderer-level verification.
