# Critique of `AWRE_Master_Prompt_Direction_C.txt` and what changed in v2

Scope: the prompt asks for a Paper plugin + Fabric client mod + custom protocol + renderer + prediction + benchmark suite vs Distant Horizons and Voxy. Sound points kept as they were: separate modules, gates that must not be skipped, "never fabricate metrics", bounded queues, authority/provenance on tiles, screen-space error with hysteresis.

## What was wrong or missing (each fixed in `AWRE_Master_Prompt_Direction_C_v2.txt`)
1. **No transport limits.** A plugin message has hard size caps (Bukkit's `Messenger.MAX_MESSAGE_SIZE`, vanilla client-to-server payload 32767 bytes). The prompt never mentions fragmentation, yet tiles/edits will exceed one message. v2 adds the limits and a bounded-fragmentation requirement. G0 deliberately caps frames at 32000 B and refuses to build bigger ones.
2. **Capability negotiation assumed free.** Bukkit tracks the channels each client announces (`getListeningPluginChannels`, documented). My recollection of the CraftPlayer implementation, **not verified**, is that `sendPluginMessage` only sends on channels in that set, in which case a server-first handshake can silently vanish. v2: client speaks first, silence = vanilla fallback after bounded retries, and diagnostics expose which case happened (G0 check 2 settles the hypothesis).
3. **Main-thread rule contradicts how plugin messages arrive.** They are delivered on the main thread. v2: size-check and drop hostile input before parsing; measure handler time (G0 now requires it).
4. **G0 pass criteria were not testable** ("works without blocking the tick"). v2 lists RUN/MEASURED items: handshake, verified tile, incompatible client stays playable, vanilla client unaffected, five fault-injection cases on the real stack, handler timing.
5. **Prediction is mis-scoped.** Reproducing vanilla worldgen is a large, version-specific, licensing-sensitive task and conflicts with the prompt's own "do not fabricate certainty". v2 defaults to budgeted server-side pre-generation of real (authoritative) chunks; reproduction becomes a gated research track after G3 with a cost estimate and a clean-room plan, else NOT PLANNED.
6. **Information leak not addressed.** Server-authoritative far terrain, structures and the edit overlay reveal bases and coastlines beyond vanilla range. v2 adds an exposure/authorization policy (surface only, anti-xray, allow-lists, radius, excluded regions) to be defined before G3.
7. **G5 statistics under-specified.** "5% worse p95" with unspecified run counts is noise. v2 requires pre-registration, >= 10 runs, medians with bootstrap CI, raw data, and an explicit feature-parity list for what "same quality" means.
8. **No verification vocabulary.** The prompt forbids false claims but gives no way to say "written, not compiled". v2 adds the labels SOURCE-ONLY / BUILT / TESTED / RUN / MEASURED.
9. **Toolchain left open.** 1.21.11 is the last obfuscated version; Fabric's docs for it use Mojang names (`CustomPacketPayload`, `StreamCodec`, `Identifier`). v2 pins a starting toolchain to verify, requires a separate Gradle build for Loom, and asks for the porting cost to the unobfuscated line.
10. **Reuse ignored.** A working core already exists (tile pyramid, cache log, codec, 31 tests). v2 asks to reuse it and map old names to the `awre-*` modules instead of starting over.

## Judgement calls the prompt leaves implicit (not changed, flagged)
- The screen-space formula `E_px = e*H*f_p/(2d)` is right when `f_p = 1/tan(fovY/2)`; use the distance to the tile's nearest point (not its centre) to stay conservative, and treat vertical faces separately.
- "Sparse volumetric hierarchy" is unscoped; it stays deferred until a surface-only baseline demonstrably fails (the prompt already says so).
- Comparing against Voxy's server path requires its exact versions/licences; G5 may be impossible to run as written, and the prompt correctly allows "stop".

## Mapping of existing HorizonField code to the awre-* layout
| existing | role going forward |
|---|---|
| `horizon-core` (ColumnTile, HeightPyramid, TileLog, TileCodec, VisibilitySweep) | seed of `awre-core`; unchanged in G0 |
| `horizon-paper` (capture) | seed of `awre-paper` capture; unchanged in G0 |
| `awre-protocol` (new) | the shared contract part of `awre-api`: frames, messages, limits, sessions, tests |
| `awre-paper` (new) | protocol endpoint + diagnostics only |
| `awre-fabric` (new, separate build) | client protocol endpoint only (no rendering) |
