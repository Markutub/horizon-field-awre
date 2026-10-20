# Audit report (Session 7) — first report per Vikingplan_Optimized §19

Labels: VERIFIED / MEASURED / INFERENCE / HYPOTHESIS / UNKNOWN. "REPORTED" = stated by earlier docs, not reproduced here.

## A. Executive conclusion
- Present: pure-Java core (tiles, height pyramid, visibility sweep, shell mesher, bounded queue, worker pool, TileLog v1, TileCodec, TileLogReader), Paper capture plugin, probe commands, offline analysis tools, 4 stream simulations. No code delivers terrain to any client yet.
- MEASURED (this session, JDK 21 sandbox, no Gradle): `tools/verify-core.sh` → 27/27 core tests pass. Not reproduced: RealStreamSim/RealTileReport numbers (the real .hfl was not supplied to this session).
- REPORTED (Session 6 docs): Java-21 build of the Paper module compiled and ran for the user; a real 4761-chunk capture worked; on that one water-heavy world the sweep had 0% false occlusion but was ~12–31x loose at ground level, so server-side culling saved ~0%. The earlier synthetic 9–39% / 21% culling figures did NOT carry over to real data. Treat server-side culling as unproven-to-negative.
- Shortest credible path to distant terrain: the optional **Fabric client renderer** (Gates 1→2). Vanilla-only cannot exceed the client render distance (VERIFIED earlier: MC-145813) and may be fog-limited (HYPOTHESIS, E2).

## B. Repository/build status
- Modules: `horizon-core` (tested), `horizon-paper` (REPORTED compiled by user on Java 21; not compiled by author), `docs/`, `tools/`.
- No Fabric module exists yet. No Gradle run in this sandbox (no network/Gradle). Java 25 build: UNKNOWN.
- Likely version-sensitive APIs: see BUILD_AND_CAPTURE.md §1.

## C. Version matrix for the Fabric branch (from web search this session)
| Item | Finding | Label | Source |
|---|---|---|---|
| 1.21.11 is the last obfuscated MC; 26.1 is unobfuscated | yes | VERIFIED | https://fabricmc.net/2025/12/05/12111.html |
| Loom for 1.21.11 | Loom 1.14 "at time of writing" | VERIFIED (blog) | same |
| Fabric API for 1.21.11 | 0.138.3+1.21.11 exists; a third party cites 0.141.3+1.21.11 and Loader 0.19.2+ | VERIFIED(0.138.3) / REPORTED(others) | https://github.com/RakambdaOrg/FallingTree/pull/992 ; spectacam release notes |
| World render events | removed in 1.21.9, reintroduced as "New World Render Events" in Fabric API 0.137.0 (1.21.10/11); documented in Fabric Docs; renamed LevelRenderEvents in 26.1 | VERIFIED | https://fabricmc.net/2025/09/23/1219.html , https://github.com/RakambdaOrg/FallingTree/pull/992 , https://docs.fabricmc.net/de_de/1.21.11/develop/porting/26.1/fabric-api |
| Rendering model in 1.21.9+ | extraction separated from rendering; queue-based | VERIFIED | fabricmc.net posts above |
| Mods built for 1.21.11 do not work on 26.1 (even as compile deps); 26.1 needs Java 25 | | VERIFIED | https://docs.fabricmc.net/ru_ru/develop/porting/index |
| Paper API `1.21.11-R0.1-SNAPSHOT`, Java 21 baseline | | VERIFIED (earlier) | Paper README / issue 13611 |
| Paper plugin-message channel ↔ Fabric `CustomPayload` interop and lifecycle | not checked | UNKNOWN — first task of Gate 1 |

Consequence: target MC 1.21.11 + Loom 1.14.x + Fabric API ≥0.138.3 + Java 21 for the client mod; a 26.x port is a separate later task (different mappings, `LevelRenderEvents`, Java 25).

## D. Critical unknowns, ordered by uncertainty removed
1. Gate 1: Paper↔Fabric payload handshake + vanilla fallback (unknown channel semantics; cheap; unblocks everything).
2. Gate 2: draw a server-sent test surface outside the chunk range using the 1.21.11 world render events (unknown hook details/fog interaction; decides project viability).
3. E1/E2 (vanilla display entities vs fog): bounded side experiment; may be skipped if Gate 2 passes.
4. Real-terrain data: capture a mountain/coast world; re-run RealStreamSim; decide whether server-side culling is dropped (likely).
