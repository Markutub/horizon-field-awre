# Horizon Field / AWRE: user guide (draft)

**Status:** written from the code and the CI evidence, not from real play. Anything under "Not verified" has never been seen working on a real display or a loaded server. Read `docs/STATUS.md` for the evidence behind each statement.

## What it does
The server plugin (Paper) observes terrain players have loaded and streams a compact height map of it to players who have the client mod (Fabric). The mod draws that terrain beyond the vanilla render distance and, when it first appears, animates it spreading out from the player (rise, grey, settle). Players without the mod just play vanilla.

## First run: why you may see nothing far away
- The plugin only records chunks that are **already loaded** by someone. It never loads or generates chunks to fill the distance. A fresh world has almost nothing to show; distant terrain appears as players explore and the server keeps those chunks loaded. This is by design (no generation cost, no exploit surface).
- The cache is saved to disk (`stream.persist`) and restored after a restart, so explored terrain accumulates over time.
- Terrain inside the vanilla render square is drawn by Minecraft, not by AWRE, and is not animated.

## Server setup (Paper 1.21.11)
Put the plugin jar in `plugins/` and edit `plugins/AWRE/config.yml`. The ones you will normally touch (all under `stream:`):

| Key | Default | Meaning |
|---|---|---|
| `enabled` | false | Turn streaming on (it is off until you enable it) |
| `source` | synthetic | Use `captured` for real terrain (`synthetic` is a test pattern) |
| `radius` | 1000 | Blocks streamed around each player (256..8000). Beyond about 2048 the client's projection far plane is expected to clip it (measured on 1.21.11 at render distance 4 and 12) |
| `bytes-per-second` | 250000 | Per-player stream budget |
| `max-store-mib` | 0 (auto) | Memory budget for observed terrain per world. Auto = min(512 MiB, 1/8 of max heap), about 450 bytes per chunk. When full, chunks at new coordinates are not captured (a warning is logged once) |
| `revalidate-seconds` | 600 | How often loaded chunks are re-observed to catch changes no event reports. 0 = only on load and on block events. Not tuned on a real server yet |
| `retry-cooldown-seconds` | 60 | How long a tile a client keeps failing to accept is held back before another attempt |
| `persist` | true | Save the terrain cache to disk |

Useful log lines: `terrain store cap for '<world>'` (the resolved cap), `capture: ... storeRejected=N` once a minute, `terrain store is full` (raise `max-store-mib` or give the server more heap).

## Client setup (Minecraft 1.21.11 + Fabric Loader + Fabric API)
Put the mod jar in `mods/`. Optional files in `config/` (all optional; a missing file means defaults, bad lines are skipped with a warning in the log):
- `awre-reveal.txt`: the reveal effect (`enabled = false` turns the animation off; speed, height, grey amount and so on). Copy the commented example from `docs/REVEAL_EFFECT.md` or the `EXAMPLE` text in `RevealSettings`. JVM properties (`-Dawre.reveal=off`) override the file.
- `awre-colors.txt`: `minecraft:some_block = #RRGGBB` for blocks the built-in colour table does not know.
- `awre-biomes.txt`: tint colours for biomes from worldgen mods.

Log lines: `[AWRE] reveal effect active` means the shader pipeline is in use. If you instead see `reveal pipeline ... failed` or `could not be created`, the effect is disabled for the session and the terrain is drawn without animation (that fallback is automatic).

## Supported and tested
- **Developed against:** Minecraft 1.21.11, Paper 1.21.11, Fabric Loader + Fabric API. CI builds both and runs a headless server + client integration (handshake, streaming, persistence, incompatible-client rejection).
- **Tested without a display only:** everything the client draws. Counters show meshes built, uploaded and draw calls issued; nobody has verified the pixels yet.
- **Not verified:** Sodium, Iris (detection code exists), VulkanMod, Folia, any other Minecraft version, vanilla clients on a real server beyond simulated sessions. Do not assume they work.

## Known limits
- The terrain is a height map: no caves, arches, overhangs or interiors. The Nether roof is not handled specially.
- Server cost at thousands of loaded chunks, and real memory per chunk, are not yet measured on real terrain (450 bytes per chunk is a synthetic estimate).
- With many players, total bandwidth and CPU are not measured.

## Troubleshooting
| Symptom | Likely cause | What to do |
|---|---|---|
| Nothing far away appears | Nothing observed yet, `stream.enabled` false, `source` still synthetic, client lacks the mod | Check the config, explore with a loaded area, look for `stream attached` in the server log |
| Far terrain appears but never animates | Reveal off, shader pipeline failed, or an Iris shader pack is active | Look for `reveal effect active` or a `reveal pipeline` warning in the client log |
| Terrain stops being captured in new areas | Store full | `terrain store is full` warning: raise `max-store-mib` |
| Odd colours for modded blocks/biomes | Not in the colour table | Add rules to `awre-colors.txt` / `awre-biomes.txt` |
