# Development and Verification

## Prerequisites

- JDK 21 is used by CI and is sufficient for the CI override `-PjavaRelease=21` and the Fabric module.
- The root build defaults to Java release 25 (`gradle.properties`); use a compatible JDK/Gradle or explicitly set `-PjavaRelease=21`.
- Internet access is required for Gradle dependency resolution and Minecraft/Paper/Fabric artifacts.
- The repository snapshot does not include a root Gradle wrapper. CI uses Gradle 9.2.1; the root build comment requires Gradle 9.1+ for release 25.

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

## Before you push (the cheapest way to avoid a red CI)

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
