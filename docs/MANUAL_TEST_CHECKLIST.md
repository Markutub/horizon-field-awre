# Manual test checklist (what to run, in order)

Do 1-3 first: everything else depends on them. "Send me" means paste/attach to the next session.

## Phase 1: does it build?
1. `bash tools/check.sh` (JDK 21). Expect `passed: 14 failed: 0` (12 with `--quick`).
2. `bash tools/feedback.sh` (Git Bash on Windows; `GRADLE_CMD=gradlew.bat` if `bash gradlew` fails). Send the `feedback-*.txt` it writes.
3. Push; the `verify` and `build` CI jobs must be green.

## Phase 2: server (Paper 1.21.11)
4. Starts clean: `AWRE enabled`, no stack traces; `plugins/AWRE/config.yml` created. The log line `[AWRE] config file <path> -> stream.enabled=... source=...` shows what was read.
5. Real world: in that file set under `stream:` `enabled: true` and `source: captured`; set `diagnostics.report-seconds: 30` and `mspt-seconds: 30`; restart. No `stream.enabled is false` warning may appear.
6. `/awre status` (shows a `stream:` line and no WARNING) and `/awre stream` after a minute: `terrain chunks in memory` > 0 and `sent` far above 1.
7. `/awre report`, then `bash tools/ci/report-check.sh plugins/AWRE/reports/awre-report.jsonl --require-activity` -> `REPORT-CHECK PASS`.
8. Stop the server normally: the last report line has `"reason":"shutdown"`.

## Phase 3: client (Fabric)
9. First launch creates `config/awre-client.txt` (all commented); `[AWRE] options:` shows every value as `default`.
10. Options work: `render = off` removes the terrain (`render=off(file)`); `maxDistance = 99999` is rejected with a line-numbered warning; `-Dawre.render=on` overrides the file (`(jvm)`).
11. Visual proof: client render distance 4-6 chunks, stand where vanilla terrain ends, F3 open, one screenshot with `render = on` and one with `render = off` from the SAME spot. Send both and the `[AWRE] horizon view:` lines.
12. Compatibility: repeat 11 with Sodium, then Iris + a shader pack; note flicker/double-draw. With Distant Horizons or Voxy and `render = auto`, AWRE must stay off.

## Phase 4: behavior and load
13. Vanilla client on your server, and the Fabric client on a server without the plugin: both must work without errors.
14. Leave and rejoin ~10 times: strikes not climbing, memory not growing.
15. 2-5 players (or bots) at once: check `players` in the report file.
16. Server cost: same server, `stream.enabled: false` then `true`, each with `report-seconds: 60` and `mspt-seconds: 60`; compare `mspt.p99Ms` and `heapUsedMiB`. Send both report files.
17. Optional CI: run the workflow manually with `pixel_check` ticked and send `ci-out/pixel/`. The first run may well be inconclusive.
