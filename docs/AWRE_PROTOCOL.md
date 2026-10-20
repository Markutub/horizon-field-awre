# AWRE protocol v1 (G0)

Status: implemented and unit-tested in `awre-protocol` (39 tests, `tools/verify-awre.sh`). The Paper and Fabric adapters are **written but not yet compiled or run** (see `AWRE_G0_RUNBOOK.md`).

## Transport
- One custom-payload channel, `awre:main`, both directions. On the wire a plugin message is the channel id followed by raw bytes (no length prefix); the Fabric codec (`AwrePayload`) reads all remaining bytes, the Paper side uses `Player#sendPluginMessage` / `PluginMessageListener`.
- **Frame ceiling `HARD_MAX_FRAME = 32000` bytes**, deliberately below Bukkit's `Messenger.MAX_MESSAGE_SIZE` and the vanilla client-to-server custom payload limit (32767), so one frame = one plugin message in both directions. Tiles that do not fit one frame use `FRAGMENT` frames (type 6, capability bit 2; added in session 10, see `docs/AWRE_G1_FEATURES.md` section 1). Building a frame that is too large still throws, it is never truncated.
- Session = one per player connection; there is no state across reconnects (the client simply negotiates again).

## Frame (big-endian)
```
u16 magic 0xA3E1 | u8 frameVersion (=1) | u8 type | u32 seq | u16 bodyLen | body[bodyLen] | u32 crc32(all preceding bytes)
```
Decode order (`Frame.decode`): total length vs limit **first** (before any field is read) -> magic -> frame version -> type -> `bodyLen == length - 14` -> CRC. `frameVersion` is the framing format and never changes inside protocol major 1, so a HELLO is always readable.

## Messages
| type | id | direction | body |
|---|---|---|---|
| HELLO | 1 | C->S | u16 minVer, u16 maxVer, u32 caps, u16 clientMaxFrame, string modVersion(<=32) |
| HELLO_ACK | 2 | S->C | u8 status(0 OK,1 INCOMPATIBLE,2 DISABLED), u16 selectedVer, u16 serverMin, u16 serverMax, u32 caps, u16 maxFrame, u32 maxDecodedTile, i64 sessionId |
| TILE | 3 | S->C | i64 worldMsb, i64 worldLsb, string dimension(<=48), i32 tileX, i32 tileZ, u8 lod, i64 revision, u8 provenance(0 PREDICTED,1 AUTHORITATIVE,2 DIRTY), u8 encoding(0 RAW,1 DEFLATE), u32 maxDecodedLen, u32 crc32(decoded), u32 compressedLen, payload |
| TILE_ACK | 4 | C->S | u32 ackSeq (the TILE frame's seq), u8 status(0 OK,1 INVALID,2 STALE,3 DUPLICATE), i32 tileX, i32 tileZ, u8 lod, i64 revision |
| ERROR | 5 | S->C | u16 code, string detail(<=64); informational only |

`string` = u8 byte length + UTF-8. Capability bit 1 = `TILE_V1`.

## Negotiation
The **client** speaks first (HELLO) so the server never has to guess whether a client has the mod. Server picks `min(clientMax, serverMax)` if it is `>= max(clientMin, serverMin)` and `TILE_V1` is offered; otherwise INCOMPATIBLE (session REJECTED, nothing else is ever sent). `maxFrame = min(server limit, clientMaxFrame)`: a client can ask for less, never more. A duplicate HELLO on an ACTIVE session gets the same ACK again (idempotent; the first ACK may have been lost). Client retries HELLO every 40 ticks, at most 5 tries, then gives up silently (**vanilla fallback**).

## Validation and bounds (what "never trust the other side" means here)
- Every length is checked against the remaining bytes before any allocation; every enum is strict; coordinates, lod, revision, sizes have explicit ranges (`Limits`).
- Inflate is hard-bounded: output memory never exceeds `maxDecodedLen + 1`; a 10 MB zero-bomb of ~10 KB on the wire is stopped with `INFLATE_LIMIT` (unit test). Trailing bytes after the deflate stream, truncated streams and wrong CRCs are rejected.
- Server: malformed frames are **dropped without a reply** (no amplification) and count as strikes; 5 strikes = BLOCKED until reconnect. HELLO and tile sends are token-bucket rate limited; at most 4 unacknowledged tiles per player (entries expire after 30 s); an ACK must echo the exact fields of a pending tile or it is ignored.
- Client: a tile is stored only after bounded inflate + CRC + content check. **Newer revision wins**; an older or equal revision never overwrites (answered STALE / DUPLICATE so the server window never leaks). Store capacity is bounded (64 tiles).
- Codec level (Fabric): payloads above 32000 bytes are skipped without being copied.

## Transport facts: verified vs not yet verified
- Verified in docs: Fabric 1.21.11 uses `CustomPacketPayload`, `StreamCodec`, `Identifier`, `PayloadTypeRegistry.playS2C()/playC2S()`, `ClientPlayNetworking.registerGlobalReceiver/send`; `ResourceLocation` was renamed `Identifier` in 1.21.11; Paper has `Player#sendPluginMessage(Plugin, String, byte[])` and `getListeningPluginChannels()`, and requires the outgoing channel to be registered.
- **Not verified (hypotheses G0 must settle):** (1) whether Paper silently drops `sendPluginMessage` to a client that never announced the channel via `minecraft:register` (the client-first HELLO and the `clientListens` line in `/awre status` are there to expose this); (2) that Fabric API sends `minecraft:register` for our channel soon enough after JOIN (the client waits 20 ticks and retries); (3) behaviour through Velocity/BungeeCord; (4) that Paper delivers plugin messages on the main thread (assumed; the handler time is measured and shown by `/awre stats`).
