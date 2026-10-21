# PHASE_00 — Verified Documentation Baseline

## Goal and scope

Create the minimal persistent Markdown context required by the supplied AWRE development prompt. This phase is documentation-only; application code and build configuration are not changed.

## Relevant constraints

- Use actual source/build/test evidence; distinguish implementation from verification.
- Preserve separate root Gradle and Fabric Loom build boundaries.
- Keep AWRE's modded-client path separate from the legacy vanilla-client research path.

## Files changed

- `AI_CONTEXT.md` — concise entry point, verified date, target versions, architecture summary, invariants and links.
- `docs/STATUS.md` — feature/test map, executed test results, blockers and next task.
- `docs/architecture/OVERVIEW.md` — actual modules and data flow, including the missing renderer link.
- `docs/architecture/DECISIONS.md` — existing build boundary and explicit project invariants.
- `docs/DEVELOPMENT.md` — exact local verification/build commands and next renderer validation procedure.
- `docs/PHASES.md` — phase roadmap and PHASE_01 acceptance criteria.
- `docs/phases/PHASE_00.md` — this record.
- `docs/phases/PHASE_01.md` — actionable implementation handoff.

## Verification performed

- `bash tools/verify-core.sh`: PASS, 69 passed, 0 failed.
- `bash tools/verify-awre.sh`: PASS, 67 passed, 0 failed.
- `bash tools/verify-stream.sh`: PASS, 23 passed, 0 failed.
- Root Gradle build: NOT RUN.
- Fabric Loom build: NOT RUN.
- Remote CI / in-game rendering: NOT RUN.

## Decisions and limitations

- `AwreClientMod` and `ClientHorizonView` have a client-side mesh-selection/build path but no actual draw adapter in this snapshot.
- A source zip cannot establish whether the remote repository has newer commits or whether the latest GitHub Actions run is green.
- No application code was modified in this documentation setup phase.

## Acceptance checklist

- [x] Stable `AI_CONTEXT.md` added.
- [x] Status map distinguishes pure-Java test evidence from unverified platform integration.
- [x] Architecture and decisions documented from source paths.
- [x] Development commands documented.
- [x] Roadmap and next-phase acceptance criteria added.
- [x] Next phase can start from Markdown without relying on chat history.

## Handoff

Begin PHASE_01 by running `gradle -p awre-fabric build --stacktrace` with Java 21 and the declared dependencies. Fix only confirmed target-version compile blockers, then implement a minimal real renderer that consumes the existing `ClientHorizonView.Drawable` meshes. Do not expand representation formats or claim rendering success before visible output is verified.
