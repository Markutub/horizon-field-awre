# PHASE_01 — Fabric Rendering Baseline

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
