# Phase-1 experiments (run these BEFORE building more)

Record: server build string, client version, client render distance, time of day, results (screenshots + yes/no). Do not skip: later design depends on them.

## E1 — Display entity view range and server tracking (public API, scaffold command)
1. Server `view-distance=10`, client render distance 4 (so the server has chunks the client does not render).
2. `/hf probe info`, then `/hf probe ring 60 1` and `/hf probe ring 60 8` (distance 60 blocks = inside/edge of client distance; viewRange 1 vs 8).
3. Pass if the pillars appear/disappear consistent with `viewRange`. Also repeat at 100, 140 blocks. Note whether entity **tracking range** (spigot/paper entity-tracking settings) cuts them off before the client does.

## E2 — Does fog hide entities beyond client render distance? (go/no-go for the shell)
1. Client render distance 4 (64 blocks). `/hf probe ring 120 16` in clear daytime weather, then in a Nether-like fog biome.
2. Look at pillars from the ground and from altitude. **Pass** = pillars are clearly visible well past 64 blocks. **Fail** = they vanish into fog colour (expected hypothesis, see ARCHITECTURE §1.3).
3. If fail: try 1.21.11 data-driven `fog_start/end_distance` attributes via a datapack timeline/biome override and re-test; record if the render-distance fog still clamps.

## E3 — Advertised radius decoupling (NMS, separate module)
Send `ClientboundSetChunkCacheRadiusPacket(32)` while Paper's real send distance is 8; then send a hand-built chunk at distance 20. **Pass** = client renders it. Repeat after relog, respawn, dimension change, and after Paper changes the player's view distance.

## E4 — Ghost chunk look
Synthesize a chunk from a `ColumnTile` (top block per column, full sky light above surface). Compare against the real chunk: colour (biome tint), darkness, edge seams, performance on client (FPS).

## E5 — Packet-only Display entities beyond server tracking/in unloaded chunks
Send `AddEntity` + metadata for a pillar 300 blocks away in an unloaded chunk. Does the client render it (given E2 outcome)?

## E6 — Update consistency
Break/place blocks inside the observed area; measure latency until `ColumnTile` and the pyramid reflect it (`/hf status` counters) and what happens to ghosts.

## Result table (fill in)
| E | Server build | Client | Setting | Result | Notes |
|---|---|---|---|---|---|
