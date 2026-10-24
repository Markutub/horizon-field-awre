# Manual test plan (everything that needs a person, a GPU and a screen)

**Status: NOT RUN. Written 2026-10-24 (Session 36).** Nothing in this file has been executed. CI (headless) proves the code *ran*: handshake, streaming,
meshing, GPU upload, draw calls, far plane extended (run 40060623947). It cannot show that terrain *looks right*, so every PASS below is a human
judgement backed by a screenshot or a log excerpt.

This file **extends** `docs/MANUAL_RENDER_TEST.md`. That file keeps checks **1-15** (baseline terrain, seams, coordinates, dimensions, reconnect, reveal).
This file adds checks **16+** and the setup, switches, log lines and report format for all of them. Run the baseline first; if it fails, stop and
report it before anything below (every later check assumes terrain is drawn at all).

Related: `docs/COMPAT_PLAN.md` (why each check exists), `docs/COMPATIBILITY.md` (the table you fill in), `docs/REVEAL_EFFECT.md`, `docs/MEASUREMENT_PLAN.md`.

---

## 0. How to use this file
1. Do section 1 once (environment). 2. Do section 2 (baseline setup). 3. Run checks in the order of the tables; record PASS / FAIL / NOT RUN + screenshot name.
4. For every FAIL or surprise, grab the logs (section 6) and use the triage table (section 8) to see which switch to flip.
5. Fill the report template (section 7) and copy results into `docs/COMPATIBILITY.md` with the date and commit.

Rule: **one variable at a time.** Change one mod, one JVM flag or one config value, re-run only the checks that depend on it, write down what changed.

---

## 1. Environment (record before starting)
| Item | Value |
|---|---|
| Date / commit hash of the AWRE build | |
| AWRE jars (`AWRE-Paper-*.jar`, `awre-fabric-*.jar`) file names + sizes | |
| Minecraft 1.21.11, Fabric Loader version, Fabric API version | |
| Paper build (1.21.11) and Java version (server, client) | |
| GPU model, driver version, OS | |
| Resolution, FOV (default 70), render distance, "FOV Effects" slider | |
| Other plugins / datapacks / mods present | |

---

## 2. Baseline setup
**Server** (`plugins/AWRE/config.yml`, then `/awre reload`; `server.properties`: `view-distance=4`, `simulation-distance=4`):
```yaml
stream:
  enabled: true
  source: captured        # real chunks; use "synthetic" only where a check says so
  persist: true
  radius: 1000
debug: true
```
**Client:** Minecraft 1.21.11 + Fabric Loader + Fabric API + `awre-fabric-*.jar` only. Video: render distance 4, fog on, default FOV.
Add JVM flags in the launcher profile (JVM Arguments); they are listed in section 3.

**Terrain to look at:** AWRE (captured mode) only streams chunks the server has *loaded*. To have observed terrain beyond the client's vanilla square, fly
over a varied area (mountains, ocean, forest, desert/snow if you can) with a second account or elytra first. Forced pre-generation alone does not feed
AWRE (it only helps because the world already exists when you fly over it).

**Fresh cache rule:** if you ran an older AWRE build on this world, delete `plugins/AWRE/terrain/<world>.hfl` before the biome checks (old caches hold
untinted colours until chunks are observed again).

---

## 3. Switches (rollback and test controls)
**Client JVM flags**
| Flag | Values | Meaning |
|---|---|---|
| `-Dawre.far=` | `auto` (default) / `off` / blocks | Far plane of the AWRE pass. `off` = vanilla's projection (old behaviour). |
| `-Dawre.render=` | `auto` / `on` / `off` | `off`: protocol and tile storage run, nothing is drawn. `auto`: off only if Distant Horizons or Voxy is loaded. `on`: force. |
| `-Dawre.reveal=off` | | Disables the reveal effect; terrain draws through the plain pipeline (own shader, places tiles with ModelOffset). |
| `-Dawre.reveal.<name>=N` | speed, maxDelay, duration, peak, depth, emerge, greyPeak, rippleAmp, rippleCycles | Tune the effect. |
| `-Dawre.iris=` | `auto` (default) / `assign` | `auto`: detect Iris only. `assign`: EXPERIMENTAL, ask Iris to shade AWRE terrain with the pack's TERRAIN program. |
| `-Dawre.vanilla.radius=` | `effective` (default) / `client` | Which render distance counts as vanilla's. `effective` = min(client option, server view distance); `client` = the raw client option, for mods like Bobby that draw real chunks out to it (check 35). |
| `-Dawre.view.maxDistance=` | blocks (default 16384) | Planner max distance on the client. |
| `-Dawre.test.minVersion=2 -Dawre.test.maxVersion=2` | | Fakes an incompatible client (check 47). |

**Client config files** (`.minecraft/config/`, read at startup; restart the client after editing):
- `awre-colors.txt`: `namespace:block = #RRGGBB`, prefix rules with `*` (`mymod:* = #808080`). Block id only, no block-state properties.
- `awre-biomes.txt`: `biome_id = grass:#RRGGBB foliage:#RRGGBB water:#RRGGBB` (any subset). Bad lines are logged as `awre-biomes.txt line N: ...`.

**Server commands** (op): `/awre status`, `/awre stats`, `/awre stream [on|off|status]`, `/awre lodplan [radius]`, `/awre debug [on|off]`, `/awre reload`.
**Vanilla helpers:** `/tp`, `/locate biome minecraft:<id>`, `/gamemode spectator`, `/time set day`, `/weather clear`.

---

## 4. Log lines to look for (client `latest.log`, grep `AWRE`)
| Line (prefix `[AWRE]`) | Meaning |
|---|---|
| `handshake OK: protocol v1 ... caps 15` | Connected to an AWRE server. |
| `projection far plane 2048.0 blocks, effective render distance N chunks` | Vanilla far plane and the radius AWRE treats as "vanilla's". |
| `far plane: vanilla=2048.0 awre=... (near=0.05, implied near ...)` | Far plane extended. |
| `far plane NOT extended: ...` | The level projection was not a standard matrix; terrain clips at vanilla's far plane. Send the whole line. |
| `reveal effect active (params ...)` | Reveal pipeline in use. |
| `plain terrain pipeline in use (reveal effect off or unavailable)` | Plain own-shader pipeline in use. |
| `plain terrain pipeline could not be created; AWRE terrain is NOT drawn` | Plain shader failed: nothing is drawn (by design). Send the stack trace. |
| `horizon view: held=.. candidates=.. ready=.. drawn=.. hidden_vanilla=.. too_far=.. over_cap=..` | What the planner decided. `over_cap>0` = tiles beyond the client's resident cap (not drawn). `held>0, candidates=0` also logs a dimension-mismatch warning. |
| `render: received=.. mesh_built=.. gpu_resident=.. gpu_bytes=.. submitted_draws=..` | Renderer counters (every 5 s). `gpu_resident` must return to 0 on disconnect. |
| `compat target loaded: sodium|iris|vulkanmod|bobby` | Detection of a target mod (drawing stays on). |
| `LOD mod 'distanthorizons'|'voxy' ... drawing disabled|ENABLED (forced)` | Overlapping-LOD-mod policy. |
| `shader pack ACTIVE|inactive` | Iris reports a pack in use. |
| `Iris detected: detect-only ...` / `Iris assignPipeline(...): ACCEPTED|NOT available` | Iris integration outcome (default / `assign`). |
| `colour overrides: N rules` / `biome overrides: N biomes` | Config files loaded. |
| `draw failed at stage '...'` | A GPU call threw; the stage name says which. Send the stack trace. |

---

## 5. Checks (continue numbering from `MANUAL_RENDER_TEST.md`)

### 5.A Far plane (needs terrain beyond 2048 blocks)
Why: CI shows the far plane was extended (2048 -> 18534) but headless counters cannot show tiles beyond 2048 appearing. Vanilla's far plane here is 2048 blocks,
not a multiple of the render distance, so only tiles beyond ~2048 were ever clipped. Note `stream.radius` max is 8000.

Setup for 16-18: `stream.source: synthetic`, `synthetic-chunk-radius: 99` (terrain spans roughly +-1584 blocks around 0,0), `stream.radius: 4000`, `/awre reload`.
Teleport to `-1500 100 0`, face east (+x): the far edge of the terrain is ~3084 blocks away.
| # | Check | How | Pass |
|---|---|---|---|
| 16 | Terrain beyond 2048 shows (extension on) | Default flags. Look east, wait until `ready` stops growing. Check the `horizon view:` line: `over_cap=0`. | Terrain continues past 2048 blocks to its true end (~3084 away); no hard cut at a constant distance. If `over_cap>0` the client hit its tile cap (`max-held-tiles`, max 4096) and some far tiles are held but not drawn: that is a cap effect, not a far-plane failure; lower `stream.radius` (e.g. 3200) and retry. |
| 17 | Same view with extension off | Restart with `-Dawre.far=off`, same spot. | Expected to cut off around 2048 (this confirms what 16 fixes). If 16 and 17 look identical: the extension has no visible effect, report with both screenshots. |
| 18 | Depth precision far away | In 16, look at ridgelines 2500-3000 blocks away, slowly turn. | No shimmering/z-fight stripes on terrain faces; skirts may flicker slightly (known, see section 9). |
| 19 | Vanilla not broken by the projection swap | In 16, look at the near vanilla world, water, entities, particles, clouds, hand, GUI. | Everything vanilla looks and behaves as without AWRE (the projection is restored after the AWRE pass). Compare with `-Dawre.render=off`. |

### 5.B Placement and plain pipeline
Why: vanilla `position_color` ignores `ModelOffset`; the plain pipeline uses our own shader. CI only ever exercised the reveal pipeline.
| # | Check | How | Pass |
|---|---|---|---|
| 20 | Plain pipeline places tiles correctly (CI client D already proves it builds and draws headlessly; only placement needs your eyes) | `-Dawre.reveal=off`, repeat check 1 (terrain beyond the vanilla edge). | Log has `plain terrain pipeline in use`; terrain is where it should be (matches the reveal-on picture once the effect has settled); NOT collapsed around the camera. |
| 21 | Plain vs reveal agree | Screenshot the same view with reveal on (after ~10 s) and `-Dawre.reveal=off`. | Same shapes and colours. |

### 5.C Seam, depth, FOV, fog (expected imperfections; record how bad)
| # | Check | How | Pass / record |
|---|---|---|---|
| 22 | Hill occlusion at the seam | Stand ~60 blocks behind a 15+ block hill at the vanilla edge, look over/through it toward far terrain. | Far AWRE terrain must not draw over the hill. Known risk: a band just beyond the vanilla edge can wrongly draw over slightly nearer vanilla geometry (two projections give different depth). Record screenshot + whether it is distracting. |
| 23 | FOV effects | Hold sprint while looking at the horizon, then set Accessibility "FOV Effects" to 0% and repeat. | With effects on, AWRE terrain may shift/zoom relative to vanilla at the edge (only the base FOV is used). With 0% it should line up. Confirms the cause if the shift vanishes. |
| 24 | FOV setting | Change FOV 30 / 70 / 110. | AWRE edge lines up with vanilla at each. A misfit that grows with FOV points at the degrees/radians assumption; report values. |
| 25 | Fog edge | Render distance 4, fog on, look at the vanilla edge. | Record the visible edge (AWRE has no fog yet: known limitation). Note colour mismatch with sky/fog. |
| 26 | Weather / time | Rain, thunder, night, sunrise. | Record how AWRE terrain looks (no lighting or weather tint is applied: expected). |

### 5.D Biome tint and worldgen mods
Fresh cache first (section 2). Use `/locate biome` and `/tp` to get near each biome, then look at terrain just beyond the vanilla edge.
| # | Check | How | Pass |
|---|---|---|---|
| 27 | Vanilla biome colours | Visit plains, desert, swamp, jungle, snowy plains, a warm ocean and a cold ocean. | Distinct, plausible grass/foliage/water tints (built-in colours are approximate, from memory). Record any that look wrong with the biome id. |
| 28 | Override works | Create `config/awre-biomes.txt` with `minecraft:plains = grass:#FF00FF`, restart client. | Log `biome overrides: 1 biomes`; plains grass in the distance is bright magenta. Remove the file afterwards. |
| 29 | Colour override works | `config/awre-colors.txt` with `minecraft:stone = #FF0000`, restart. | Log `colour overrides: 1 rules`; distant stone-topped ground is red. Remove afterwards. |
| 30 | Bad config lines | Add a line with a typo. | Warning `awre-biomes.txt line N: ...` / `awre-colors.txt line N: ...`; client still starts. |
| 31 | Worldgen mod/datapack | With your worldgen setup (name + version): fly 1000+ blocks, look at distant terrain. | Terrain is captured and drawn; custom biomes get plausible colours via the name heuristic (desert/snow/jungle/swamp/ocean...) or plains. List biome ids that look wrong, they can be fixed in `awre-biomes.txt`. |
| 32 | Tall / deep terrain | Mountains above y=320 or a custom dimension with min_y below -64 (if your setup has them). | No missing tops. A thin gap under skirts at a low min_y is a KNOWN limitation (skirt floor is a constant -64). |
| 33 | Plugin custom blocks | If you use ItemsAdder or similar: look at areas with custom blocks. | Expected wrong colours (carrier blocks); record, fix with `awre-colors.txt` where the carrier id is unique. |

### 5.E Mods and shaders (one at a time; each: checks 1, 3, 4, 8, 9, 10 from the baseline + the specifics)
Install the mod(s) next to AWRE, change nothing else, start the client, look for `compat target loaded: <id>`.
| # | Mod | Specifics | Pass |
|---|---|---|---|
| 34 | **Sodium** | Terrain beyond the edge; vanilla chunks look normal; fly across the seam. | AWRE terrain visible and placed correctly; no flicker or missing vanilla chunks. |
| 35 | **Bobby** | Join, explore, leave and rejoin so Bobby has cached chunks, set client render distance (e.g. 12) above the server view distance (4), look at the band between 4 and 12 chunks. | **Predicted problem:** AWRE treats the *effective* render distance (min of client and server, here 4 chunks) as vanilla's, but Bobby draws real cached chunks out to the client's 12. Expect AWRE tiles overlapping/z-fighting with Bobby chunks in that band. Record a screenshot. If confirmed, re-test with `-Dawre.vanilla.radius=client`: the overlap band should disappear (and with Bobby absent that flag would instead leave a ring with no terrain). Report which setting looked right; making it automatic when Bobby is loaded is the follow-up. |
| 36 | **VulkanMod** | Start the game, join, look at terrain. 1.21.11 itself has no Vulkan classes; only VulkanMod could provide one. | Starts without crash; AWRE draws. If it crashes or draws nothing, send `latest.log` and the crash report. |
| 37 | **Iris, no shader pack** | Install Sodium + Iris, shader packs off. | Behaves like check 34. |
| 38 | **Iris + shader pack A** (name the pack) | Pack on, default flags. | Log `shader pack ACTIVE` and `Iris detected: detect-only`. Expected: AWRE terrain drawn but NOT shaded by the pack (flat, maybe wrong lighting/fog). Must still be correctly placed and not crash. Screenshot. |
| 39 | **Iris + shader pack B** | Same with a second pack. | Same expectations; record differences. |
| 40 | **Iris `assign` (experimental)** | `-Dawre.iris=assign`, pack on. | Read the log line: `ACCEPTED` or `NOT available`. If ACCEPTED: is terrain **placed correctly**? shaded by the pack? If tiles are misplaced or garbage, FAIL and report; the default must stay detect-only. |
| 41 | **Distant Horizons or Voxy** | Install one. | Log `LOD mod ... drawing disabled`; no AWRE terrain drawn (no double layer). Then `-Dawre.render=on`: expect two overlapping layers (record). |
| 42 | Combination | Sodium + Bobby + your worldgen mod; then Sodium + Iris + a pack. | Record; report only the first failing combination. |

### 5.F Server, plugins, proxy
| # | Check | How | Pass |
|---|---|---|---|
| 43 | Vanilla client | Join with an unmodded client. | Joins normally, no errors in server log, `/awre status` shows no AWRE session for it. |
| 44 | Client mod, server without AWRE | Join a plain Paper server with the mod. | Game unaffected. The client should end up logging `AWRE unavailable, vanilla fallback` after its handshake retries; if it never reports a final state, record that (not verified). |
| 45 | Two AWRE clients | Join with two clients, move both. | Each gets its own session/stream (`/awre status`), no cross-talk, TPS fine. |
| 46 | Restart replay | Stop and start the server (`persist: true`). | Terrain replays from `plugins/AWRE/terrain/<world>.hfl` without re-flying. |
| 47 | Incompatible client | Client with `-Dawre.test.minVersion=2 -Dawre.test.maxVersion=2`. | Client reports incompatible and continues; server rejects with INCOMPATIBLE_VERSION; no crash. |
| 48 | Block edits by other plugins | WorldEdit (or similar) edit inside the observed area; wait `revalidate-seconds` (default 600; set 30 for the test). | Distant terrain updates; no stale duplicate geometry. |
| 49 | Proxy | If you run Velocity/BungeeCord: join through it. | Handshake OK through the proxy (`awre:main` must be forwarded). Otherwise NOT RUN. |
| 50 | Multiple worlds / custom dimensions | Move between worlds (Multiverse or datapack dimension). | Terrain of the old world is gone immediately; tiles carry the namespaced key, no wrong-dimension geometry. |

### 5.G Performance and limits
| # | Check | How | Record |
|---|---|---|---|
| 51 | FPS cost | Same view, F3 FPS: `-Dawre.render=off` vs default vs `-Dawre.reveal=off`. | FPS for each; GPU model. |
| 52 | GPU memory | Read `gpu_bytes` / `gpu_resident` in the `render:` line after streaming settles. | Values; CI synthetic reached ~34 MB for 2064 tiles. `max-held-tiles` caps at 4096. |
| 53 | Join hitch | Time from join to first AWRE terrain; watch for freezes while meshing. | Seconds, any stutter. |
| 54 | Server cost | Profile the server main thread for 5 minutes with 1 and 5 clients and `revalidate-seconds` default (spark/Timings, whichever you have). | % of tick spent in AWRE; `/awre stats`; TPS. |
| 55 | Bandwidth | `bytes-per-second: 250000` per player is an ASSUMED budget. Watch network usage during first join. | KB/s per player. |
| 56 | Long session | Play 30+ minutes, fly 5000+ blocks, change dimension twice. | `gpu_resident` stays bounded; no growth of `gpu_bytes` over time; no exceptions. |

---

## 6. Evidence to collect
- Client `logs/latest.log` and server `logs/latest.log` after each section (copy, do not rely on the live file; it rotates on restart). Search: `AWRE`, `Exception`, `Caused by`.
- Screenshots (F2) named `<check#>-<short-name>-<flags>.png`, e.g. `16-far-extension-default.png`, `17-far-extension-off.png`. Always two shots for A/B checks.
- For crashes: `crash-reports/*.txt`.
- The exact JVM args and mod list used for the run (copy from the launcher).
- CI side: the `integration-logs` and `mc-api-dump` artifacts from the commit you tested, if available.

## 7. Report template (one row per check, plus a header)
```
Date | commit | GPU/driver | MC/Fabric/Paper versions | mods + versions | JVM flags | server config diff
#  | PASS/FAIL/NOT RUN | screenshot | log excerpt (3-10 lines) | note
```
Then copy the verdicts to `docs/COMPATIBILITY.md` (target mods rows) with date and commit. Only a human PASS here changes "NOT TESTED" there.

## 8. Triage: what to flip when something is wrong
| Symptom | First suspect | Try | Send |
|---|---|---|---|
| No AWRE terrain at all, handshake OK | draw failed / dimension filter | read `draw failed at stage` and `held>0 candidates=0` warning | the stack trace and the `horizon view:` line |
| Terrain cut off at a constant distance | far plane | `-Dawre.far=auto` vs `off`; look for `far plane NOT extended` | both screenshots + the far-plane log line |
| Terrain collapsed around the camera / stacked | placement (ModelOffset) | `-Dawre.reveal=off` vs default | screenshots + which pipeline log line appeared |
| Terrain pops over nearby hills at the edge | seam depth mismatch | none (known) | screenshot, distance of the hill |
| Terrain shifted/zoomed when sprinting | FOV effects | Accessibility "FOV Effects" = 0% | before/after screenshot |
| Everything vanilla looks wrong after the AWRE pass | projection not restored | `-Dawre.far=off` | screenshot, log |
| Double terrain layer with a LOD mod | overlapping mods | `-Dawre.render=off` | mod names + versions |
| Overlap/z-fight band with Bobby | effective vs client render distance | `-Dawre.vanilla.radius=client` (check 35) | screenshot, both render-distance values |
| Garbage under shader pack with `assign` | pack program does not place our tiles | remove `-Dawre.iris=assign` | log line + screenshot |
| Wrong ground colours | biome/block colour tables | `awre-biomes.txt`, `awre-colors.txt` | biome ids / block ids and screenshot |
| Crash on start with a mod | the mod pair | remove AWRE, then the mod, to see which is needed to crash | `latest.log`, crash report |
| FPS drop | draw volume | `-Dawre.render=off` | FPS numbers, `render:` line |

## 9. Known limitations you should see (not bugs; record how bad they look)
- AWRE has **no fog** and no lighting/weather tint: a visible edge and flat colours are expected (check 25, 26).
- Only the **base FOV** is used for the AWRE projection (check 23); depth at the seam can be wrong in a band just outside the vanilla edge (check 22).
- **Shader packs do not shade** AWRE terrain by default (checks 38, 39).
- Colours come from block ids, top block per column only; biome tint has no blending across biome borders.
- Captured mode shows only chunks that were loaded; unobserved areas send nothing.
- Nether roof and caves/overhangs are not special-cased; skirt floor is a constant -64.
- The first run on real hardware may expose compile- or driver-specific problems; that is the point of this file.
