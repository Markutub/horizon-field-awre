# Development and Verification

## Prerequisites

- JDK 21 is used by CI and is sufficient for the CI override `-PjavaRelease=21` and the Fabric module.
- The root build defaults to Java release 25 (`gradle.properties`); use a compatible JDK/Gradle or explicitly set `-PjavaRelease=21`.
- Internet access is required for Gradle dependency resolution and Minecraft/Paper/Fabric artifacts.
- The repository has a root Gradle wrapper (`gradlew`, pinned to Gradle 9.6.0 in `gradle/wrapper/gradle-wrapper.properties`; committed non-executable, so use `bash gradlew ...`). CI installs Gradle 9.2.1 instead and runs `gradle`; the root build requires Gradle 9.1+ for release 25. Whether the wrapper's 9.6.0 resolves has NOT been verified. `awre-fabric` has only a wrapper properties file (9.2.1), no script or jar. Do not run `gradle wrapper ...`: it would overwrite the committed wrapper.

## Fast pure-Java verification

Run from repository root:

```bash
bash tools/verify-core.sh
bash tools/verify-awre.sh
bash tools/verify-stream.sh
```

These scripts compile real pure-Java test sources against the repository's tiny JUnit shim. They do not compile Paper/Fabric integration against platform APIs and do not prove in-game rendering.

## Gradle builds

```bash
gradle build -PjavaRelease=21 --stacktrace
gradle -p awre-fabric build --stacktrace
```

The first command builds root modules (`horizon-core`, `horizon-paper`, `awre-protocol`, `awre-stream`, `awre-paper`). The second builds the standalone Loom project. Use the same versions as `.github/workflows/ci.yml` when comparing results.

Optional dev servers, if dependencies resolve:

```bash
gradle :awre-paper:runServer -PjavaRelease=21
gradle :horizon-paper:runServer -PjavaRelease=21
```

## Seeing your real world (server config)
The defaults stream NOTHING useful: `stream.enabled` is false, and `source: synthetic` with `synthetic-chunk-radius: 0` means no terrain at all (only the handshake test tile). For a real world set, in `plugins/AWRE/config.yml`:
```yaml
stream:
  enabled: true
  source: captured     # real chunks the server has loaded; never generates anything
  radius: 1000         # blocks; above ~2000 tiles are clipped by the projection far plane (measured)
```
then restart, join, and move around so the server loads the area (a pregeneration plugin may also feed it; not verified). Check with `/awre stream` (chunks in memory, `sent`, `held`) and `/awre status` (prints a WARNING when the setup streams nothing). `tilesSent=1` means only the test tile was sent.

## Client options and result reports
- Client: edit `config/awre-client.txt` in the Minecraft instance (created on first launch). Keys: `render` (auto|on|off), `maxDistance` (64..16384 blocks), `far` (auto|off|blocks), `vanillaRadius` (effective|client), `iris` (auto|assign). A JVM flag such as `-Dawre.render=off` overrides the file. Bad lines are rejected with a warning in the game log (`[AWRE] awre-client.txt line N: ...`) and the default is used. The startup log line `[AWRE] options: ...` shows each value and whether it came from `jvm`, `file` or `default`.
- Server: set `diagnostics.report-seconds: 60` (and `mspt-seconds: 60` to include MSPT) in `plugins/AWRE/config.yml`, or run `/awre report`. Result lines go to `plugins/AWRE/reports/awre-report.jsonl` (rotated at 8 MiB, one backup `.1`). Validate with `bash tools/ci/report-check.sh <file>`. Offline tests: `bash tools/ci/report-check-test.sh`.

## Pixel check (opt-in, experimental)
Run the CI workflow manually with `pixel_check` ticked. It installs `xvfb imagemagick mesa-utils x11-utils libgl1-mesa-dri`, starts one non-headless client under software GL and uploads `ci-out/pixel/` (PNG captures, verdict, display info) in the `integration-logs` artifact. Offline tests: `bash tools/ci/pixel-check-test.sh`.

## Before you push (the cheapest way to avoid a red CI)

Shortcut: `bash tools/feedback.sh` (Git Bash on Windows; `GRADLE_CMD=gradlew.bat` if `bash gradlew` does not work) runs both builds below plus the dependency query, and writes one `feedback-<timestamp>.txt` with the first compile errors, the Loom and Paper API lines needed for pinning, and log tails. Send that file back. Tested only against a mock Gradle; NOT yet run against a real build.

CI compiles with Java 21 (`-PjavaRelease=21`); the repository default is `javaRelease=25` (a deliberate choice, see `gradle.properties`), so a plain `gradlew build` needs a JDK 25 toolchain and produces bytecode that does not run on a Java 21 server. Run the same two commands CI runs (PowerShell or cmd; use `./gradlew` on Linux/macOS):

```
gradlew.bat build -PjavaRelease=21
gradlew.bat -p awre-fabric build
```

The sandbox used by the AI sessions can only syntax-check Paper and Fabric code (`java tools/ParseOnly.java awre-paper/src awre-fabric/src horizon-paper/src`), so real compile errors (wrong Paper/Minecraft method names) only appear here or in CI. If either command fails, send the first `error:` block.

**Pinning (open item):** `fabric-loom` is `1.14-SNAPSHOT` and the Paper API is `1.21.11-R0.1-SNAPSHOT`, both floating. To pin them, send the resolved versions: the first lines of the Fabric build log print `Fabric Loom: <version>`, and `gradlew.bat dependencies --configuration compileClasspath` shows the resolved Paper API build. Nothing is pinned until those exact strings are known (do not guess a version).

## Integration and client smoke

The CI workflow `.github/workflows/ci.yml` runs pure-Java tests, the root Gradle build, the separate Fabric build, a Fabric client smoke test, and `bash tools/ci/integration.sh jars` with Paper plus a headless Fabric client. It uploads logs and reports. A source snapshot or successful local pure-Java tests are not substitutes for a completed CI run.

## Next renderer validation procedure

1. Run the separate Fabric build and capture the exact compiler errors before editing render API usage.
2. Implement only the minimal 1.21.11 render hook and GPU resource ownership needed to draw `ClientHorizonView.Drawable` meshes.
3. Rebuild `awre-fabric`; run root pure-Java scripts to guard the shared model/protocol.
4. Run CI integration and inspect client/server logs for AWRE exceptions, nonzero mesh-ready/drawn counts, and clean shutdown.
5. Perform manual in-game validation: compare terrain tile placement to camera/world coordinates; move/rotate, cross tile and LOD boundaries, change dimension, disconnect/reconnect, and shut down. Verify no stale GPU resources or holes/overdraw at coverage transitions.
6. Do not claim visual correctness, performance wins, Sodium compatibility, or beyond-vanilla-range behavior without the corresponding test.

## Troubleshooting / constraints

- If Fabric API names fail to compile, inspect the exact 1.21.11 mappings and installed Fabric API version; do not guess newer APIs into this target.
- The Fabric module intentionally compiles shared Java source directories directly. Avoid adding Minecraft/Fabric imports to `horizon-core`, `awre-protocol`, or `awre-stream`.
- Keep world access on the Paper main thread unless a specific API guarantees otherwise; pass immutable snapshots to background workers.
- Never force-load chunks to populate the distant cache.
