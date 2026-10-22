# Phase Roadmap

Statuses refer to the Session 18 source and local checks run 2026-10-22, not to the current remote branch.

| Phase | Name | Status | Objective / exit gate |
|---|---|---|---|
| PHASE_00 | Verified documentation baseline | COMPLETE (docs only) | Create a stable AI context, verified status map, architecture decisions, build instructions, and actionable next-phase handoff. Pure-Java checks run and recorded. |
| PHASE_01 | Fabric rendering baseline | IN PROGRESS: source written (Session 18), pure-Java parts tested; Fabric compile and in-game NOT VERIFIED | Draw actual AWRE terrain meshes in Minecraft 1.21.11 using `ClientHorizonView`; exact Fabric build succeeds; resource lifecycle is safe; a reproducible in-game/CI verification path demonstrates visible output. |
| PHASE_02 | Correctness across world and LOD transitions | IN PROGRESS: pure-Java invariants + dimension bug fixed (Session 19, tests pass); in-game NOT VERIFIED | Validate camera-relative transforms, dimension filtering, tile coverage, LOD transitions, edits/revisions, reconnects and resource disposal with deterministic tests plus in-game checks. |
| PHASE_03 | Real-terrain calibration and visual fidelity | PLANNED | Capture representative real terrain, record visual defects and mesh/payload costs, then improve the highest-value fidelity gap with regression fixtures. |
| PHASE_04 | Performance instrumentation and budgets | PLANNED | Measure client render cost, GPU uploads, memory, server tick cost, bytes/s, cache hit rate and convergence latency before optimizing. |
| PHASE_05 | Reliability and compatibility hardening | PLANNED | Verify persistence recovery, malformed input, multiple players, shutdown/restart, supported Minecraft/Paper/Fabric combinations, and honest compatibility claims. |
| PHASE_V | Vanilla-client delivery experiments | SEPARATE / GATED | Continue only via `docs/EXPERIMENTS.md`; record outcomes for each experiment. Do not assume a vanilla client can draw custom AWRE geometry. |

## PHASE_01 acceptance criteria

- Fabric build succeeds against the repository's declared Minecraft 1.21.11, Loader 0.18.4, Fabric API 0.141.3, and Java 21 configuration, or exact blockers are documented.
- A real render hook consumes `ClientHorizonView.Drawable`; no mock or screenshot-only placeholder.
- GPU resources are created/updated/freed on appropriate lifecycle/thread boundaries.
- Camera-relative positioning is correct at negative and large world coordinates.
- Dimension change, disconnect, and shutdown clear/release owned resources.
- Client logs and a reproducible test show actual draw activity; compilation or mesh-ready counters alone are insufficient.
- Pure-Java verification remains green.
- `docs/phases/PHASE_01.md`, `docs/STATUS.md`, and `AI_CONTEXT.md` are updated with exact tests and the next handoff.
