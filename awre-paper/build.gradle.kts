plugins {
    `java-library`
    id("xyz.jpenilla.run-paper") version "2.3.1"   // dev server: ./gradlew :awre-paper:runServer
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    implementation(project(":awre-protocol"))
    implementation(project(":horizon-core"))     // LodPlanner for /awre lodplan
    implementation(project(":awre-stream"))      // PlayerStreamer, SyntheticTerrain
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

// Bundle the (dependency-free) protocol classes into the plugin jar; no shadow plugin needed.
tasks.jar {
    from(project(":awre-protocol").sourceSets.main.get().output)
    from(project(":horizon-core").sourceSets.main.get().output)
    from(project(":awre-stream").sourceSets.main.get().output)
    archiveBaseName.set("AWRE-Paper")
}

tasks.runServer {
    minecraftVersion("1.21.11")
}
