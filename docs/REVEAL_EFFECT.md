# Terrain reveal effect (distant AWRE tiles)

**Status (2026-10-23, Session 33): implemented, NOT VERIFIED in game.** Pure-Java logic and a CPU preview are tested; the shader and the Fabric wiring have
never run on a GPU. The first CI build/integration run on this change is the first real test of the shader.

## What it looks like
When AWRE terrain first appears, it spreads out from the player like chunks loading: each part of the terrain **emerges from below its true height, rises,
turns ghost-grey while it is up, then settles back down and regains its real colour**, with a small damped ripple. The ring expands at `speed` blocks/s
(capped by `maxDelay` so far terrain does not wait forever). Only the first reveal of an area animates: tile updates (new revisions) and LOD swaps over already
revealed ground do not replay it. Dimension change, disconnect and shutdown reset the session.

**Scope:** AWRE's own distant tiles only. Terrain inside the vanilla render square is drawn by Minecraft and is not animated (that would need mixins into
vanilla chunk rendering and would conflict with Sodium/Iris; not attempted).

## Pieces
| File | Role |
|---|---|
| `awre-stream/.../RevealAnimator.java` | Single source of truth: parameters, formulas (`tau`, `lift`, `grey`, `greyMix`), uniform packing, session state, "already revealed" bookkeeping (bounded) |
| `awre-fabric/src/main/resources/assets/minecraft/shaders/core/awre_terrain.vsh/.fsh` | GLSL mirror of the formulas. In the `minecraft` namespace so the existing `String` overloads of the pipeline builder resolve it |
| `HorizonRenderer` | Reveal pipeline (lazy holder class), per-draw uniforms, **automatic fallback** to the plain pipeline if the shader cannot be built or a draw fails |
| `RevealAnimatorTest`, `RevealShaderContractTest` | Behaviour tests; contract test that fails when Java packing/constants and the GLSL drift apart |
| `tools/preview/RevealPreview.java` + `run-reveal-preview.sh` | CPU render of the effect with the real animator and real LOD meshes + self-checks |

## Uniform packing (contract)
`ModelOffset` = tile origin minus camera (applied by the shader itself; see "Open question" below). `ColorModulator` = (wave origin x, z relative to camera, `revealAge` s, `tileAge` s). `TextureMat` column-major = parameters: col 0 (speed, maxDelay, duration, peak), col 1 (depth, emerge, greyPeak, rippleAmp), col 2 (rippleCycles). `revealAge`/`tileAge` = 10000 means finished (no offset, no grey, nothing hidden).

## Tuning (no rebuild)
Easiest: create `config/awre-reveal.txt` (one `key = value` per line, `#` comments; keys: enabled, speed, maxDelay, duration, peak, depth, emerge, greyPeak, rippleAmp, rippleCycles; the commented example is `RevealSettings.EXAMPLE`). Bad lines are skipped with a warning; JVM properties override the file.
System properties on the client JVM: `-Dawre.reveal=off` disables the effect; `-Dawre.reveal.<speed|maxDelay|duration|peak|depth|emerge|greyPeak|rippleAmp|rippleCycles>=N` override defaults
(defaults: speed 120, maxDelay 6, duration 1.6, peak 6, depth 10, emerge 0.3, greyPeak 0.85, rippleAmp 1.2, rippleCycles 2). An out-of-range set is rejected as a whole and the defaults are used (warning logged).
Preview a setting offline: `JAVA_TOOL_OPTIONS="-Dawre.reveal.speed=200 -Dawre.reveal.peak=10" bash tools/preview/run-reveal-preview.sh out/` then open `out/reveal-overhead.gif` and `out/reveal-sheet-overhead.png`.
`-Dawre.preview.vanilla=N` changes the half-width of the vanilla square the preview assumes (default 96).

## What the preview checks (and does not)
Per view: END (final frame pixel-identical to the no-effect frame, also at x,z around 1,000,000 in float), START (first frame shows almost nothing), SPREAD (elevated views: revealed area grows), GREY (ghost-grey pixels appear mid-sequence and are gone at the end).
It cannot show real GPU output, fog, lighting, biome tint, the real shader compiling, or Sodium/Iris behaviour.
Bug found by it while building: 64-block bookkeeping cells froze the neighbours of the first 16-block tile (fixed: 16-block cells + rectangles for coarse tiles; regression tests added).

## Known limitations / risks
- **Shader/API unverified.** Assumptions: `#moj_import <minecraft:dynamictransforms.glsl>` / `<minecraft:projection.glsl>` exist in 1.21.11 and declare `ModelViewMat`, `ColorModulator`, `ModelOffset`, `TextureMat`, `ProjMat`. `tools/ci/api-dump.sh` now also dumps vanilla `core/position_color.*` and `include/*` into the `mc-api-dump` artifact to settle this.
- **Open question (important):** the original pipeline uses vanilla `core/position_color`; I believe that shader does not apply `ModelOffset`, which would put every tile at the camera instead of its place. The reveal shader applies it itself, so it is correct either way, but if the belief is right the plain fallback pipeline is mispositioned. The `mc-api-dump` artifact (`position_color.vsh uses ModelOffset: N occurrence(s)` note) answers it.
- Vertices are displaced per vertex; very large coarse triangles (high LODs) move as flat planes and the wave front across them is coarse (the preview shows jagged fronts at the very first frames).
- Hidden-before-arrival is a per-fragment `discard` on an interpolated value; it is not exact at tile borders.
- Under Iris with a shader pack the AWRE pipeline's behaviour is unverified, with or without this effect.
- Far tiles (beyond `speed * maxDelay` ≈ 720 blocks) all start at `maxDelay`, i.e. they pop in together rather than ripple.

## Acceptance criteria for "verified"
1. CI `build` job green and the client log shows `[AWRE] reveal effect active`; no `reveal pipeline draw failed` / `could not be created` warning.
2. `render-gate.sh` still passes on the integration log (draws submitted with the reveal pipeline).
3. Manual checks 12-15 in `docs/MANUAL_RENDER_TEST.md` done on a real display.
