# Protocol analysis (vanilla client, 1.21.11)

Packet names are Mojang-mapped names; **verify them against the exact 1.21.11 Paper build** before coding the NMS module. Nothing here is implemented yet.

## Relevant clientbound packets
| Packet | Purpose | Use |
|---|---|---|
| `ClientboundSetChunkCacheRadiusPacket` | tells the client the server view radius; client render distance = min(its option, this) **[H]** | advertise a large radius while the server only really loads a small one **[T E3]** |
| `ClientboundSetChunkCacheCenterPacket` | recenters the client chunk cache | sent by Paper itself as the player moves |
| `ClientboundLevelChunkWithLightPacket` | one chunk + light | ghost chunk payload **[T E4]** |
| `ClientboundForgetLevelChunkPacket` | drop a chunk | we must send it for ghosts we created |
| `ClientboundAddEntityPacket`, `ClientboundSetEntityDataPacket`, `ClientboundTeleportEntityPacket`, `ClientboundRemoveEntitiesPacket` | virtual Display entities | shell delivery **[T E5]** |

## Flow (Ghost Ring — hypothesis)
```
Paper: real chunks r ≤ R_real  ──────────────► client (normal)
Plugin: advertise radius R_adv (≤ client option) ─ SetChunkCacheRadius ─► client storage radius grows
Plugin: for chunks R_real < r ≤ R_adv that the VisibilitySweep marks visible:
        GhostChunk(columns from ColumnTile) ─ LevelChunkWithLight ─► client renders it
Player moves: Paper recenters; plugin diffs visible set → send new ghosts / Forget stale ones
Real chunk becomes loaded by Paper: its real packet replaces the ghost (promotion)
```
Ghost chunk state per player & chunk: `UNSENT → GHOST_SENT → PROMOTED(real) → FORGOTTEN`. We only ever send Forget for `GHOST_SENT` chunks; Paper owns the rest.

## Known hazards (must be tested, not assumed)
1. Paper's chunk sender may also advertise/adjust the radius (e.g. via `setSendViewDistance`) and could overwrite our advertised value → E3 must test across join, respawn, dimension change.
2. Light: ghost chunks need sky light for air above the surface, or terrain renders dark. Plan: sections wholly above max surface = full sky light; surface section = per-column cut. **[T E4]**
3. Block entities, heightmaps, biomes: send empty block entities; heightmaps recomputed; biome from a summary (grass/foliage colour) **[T]**.
4. Ghosts must not be interactable/solid for gameplay: the server world has no such blocks, so clients walking into a ghost chunk area would collide client-side until the real chunk replaces it → keep ghosts beyond the real-chunk radius and promote before the player arrives (prefetch margin).
5. Block edits in ghost area are not visible until re-synthesized (invalidate via `ColumnTile` re-capture; only chunks the server loaded are observed).
6. Anti-cheat/anti-xray plugins that also hook chunk packets may conflict.

## Test matrix (fill during Phase 1)
Render distance {2, 4, 8, 16, 32} × server real radius {4, 8} × movement {stand, walk, elytra, teleport} × events {relog, respawn, dimension change, `/tp` across 1000 blocks, block edit in ghost area} × clients {vanilla 1.21.11, vanilla with Sodium-less defaults}.
