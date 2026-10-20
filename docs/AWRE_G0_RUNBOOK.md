# AWRE G0 runbook (toolchain + protocol feasibility)

## What exists, and what has been run
| part | state |
|---|---|
| `awre-protocol` (pure Java, framing, negotiation, bounded decode, sessions) | **built and tested in the sandbox: 39/39** (`bash tools/verify-awre.sh`) |
| `awre-paper` (plugin: channel bridge, `/awre`) | written, **not compiled** (needs Paper API) |
| `awre-fabric` (client mod: payload, handshake wiring) | written, **not compiled** (needs Loom + Minecraft 1.21.11) |
| anything on a real server/client | **not run** |

## 1. Paper plugin (root Gradle build)
```powershell
cd horizon-field
.\gradlew build -PjavaRelease=21          # builds horizon-core, horizon-paper, awre-protocol, awre-paper
```
Jar: `awre-paper\build\libs\AWRE-Paper-0.1.0.jar` -> copy to the server's `plugins\`. (Plugin and HorizonField can coexist; different names and commands.)
Dev server alternative: `.\gradlew :awre-paper:runServer`.

## 2. Fabric client mod (separate build, own wrapper)
```powershell
cd awre-fabric
gradle wrapper --gradle-version 9.2.1     # the wrapper jar is not shipped; properties file is
.\gradlew build
```
Jar: `awre-fabric\build\libs\awre-fabric-0.1.0.jar` -> client `mods\` together with Fabric API 0.141.x for 1.21.11 and Loader >= 0.18.1. If Gradle reports a missing artifact, bump `fabric_version` / `loader_version` in `awre-fabric\gradle.properties` from https://fabricmc.net/develop/ and tell me.

## 3. Acceptance checks (G0 passes only if all are true; copy the log lines back to me)
1. **Server start**: log has `AWRE G0 enabled: channel awre:main`. No exceptions.
2. **Mod client joins**: client log (`latest.log`) shows `[AWRE] handshake OK: protocol v1` within ~3 s and then `test tile (x,z) lod 0 rev N ... verified OK (512 decoded bytes)`. Server log shows `session NEW -> ACTIVE`. `/awre status` shows `acksOk=1`, `inFlight=0`, `strikes=0`, and the line `client announced channel awre:main via minecraft:register: true|false` (**tell me which**; `false` plus a working handshake means the register hypothesis is irrelevant, `false` plus a missing test tile means it is real).
3. **Vanilla client joins** (no mod): no errors on either side, no AWRE traffic, `/awre status` says `your session: none`. Normal play.
4. **Incompatible client**: start the client with JVM arg `-Dawre.test.minVersion=2 -Dawre.test.maxVersion=3`. Client logs `incompatible server, continuing as a normal vanilla-style client`; server shows `REJECTED (INCOMPATIBLE_VERSION)`; you stay connected and can play.
5. **Bounded decode on the real stack** (as op, with the mod client): `/awre sendbad crc`, `truncated`, `bomb`, `oversize`, `huge`. The client must stay connected, log a rejection per frame, and `huge` must log `skipped an oversize payload of 32500 bytes without allocating it`. After 5 bad frames the client stops listening (session FAILED) but the game continues.
6. **Server tick impact**: `/awre stats` -> `handler ... avg=..us max=..us`. Report the numbers. (Pass: well under 1 ms; this is a measurement, not an assumption.)
7. **Chunks untouched**: the plugin registers no chunk/world listeners; confirm `/awre` never changes anything in the world (nothing to test beyond normal play).

## 3b. Session 10 extras (not part of the G0 gate)
- `/awre fragtest [side]`: sends a fragmented noise tile; client log must show `verified OK`, `/awre status` must return to `inFlight=0`.
- `/awre lodplan [radius]`: async LOD plan around you; report the printed tile counts and ms.
- Details and expected values: `docs/AWRE_G1_FEATURES.md` section 4.

## 3c. Automated run (GitHub Actions)
`.github/workflows/ci.yml` builds everything, runs the pure-Java tests, the MC-Runtime-Test client smoke test, and `tools/ci/integration.sh`
(Paper server + Fabric client via HeadlessMc, asserts runbook checks 1, 2, 4 and the fragment test; checks 3, 5, 6 still need a human or a vanilla client).
UNVERIFIED: never executed. Read `ci-out/summary.md` (job summary) and the uploaded `integration-logs` artifact first.

## 4. If something fails
Send: the exact Gradle error, or the first ~40 lines of the failing log, plus the output of `/awre status` and `/awre stats`. Likely first problems, in order: Gradle artifact versions (Fabric), `World#getName` / Bukkit API usage (Paper), Mojang-mapping names in `AwrePayload` (`StreamCodec`, `Identifier`), the Paper register hypothesis (check 2).

## 5. Not part of G0 (deliberately)
Fragmentation, edit journal, caches, hierarchy, rendering, prediction, benchmarks against DH/Voxy. Those start only after G0 passes.
