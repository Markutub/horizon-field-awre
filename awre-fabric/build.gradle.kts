// Standalone Gradle build on purpose: Fabric Loom must not share a build with the Paper modules.
plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
}

version = property("mod_version") as String

base { archivesName.set("awre-fabric") }

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // 1.21.11 is the last obfuscated release and Yarn ends there; Fabric's own 1.21.11 docs use Mojang names.
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
}

// The pure-Java protocol is compiled straight into the mod (same sources the Paper plugin bundles; no jar dependency).
// horizon-core (terrain codec) and awre-stream (TerrainPayloads) are pure Java too; they are compiled in the same way.
sourceSets { main { java { srcDir("../awre-protocol/src/main/java"); srcDir("../horizon-core/src/main/java"); srcDir("../awre-stream/src/main/java") } } }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to project.version)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
