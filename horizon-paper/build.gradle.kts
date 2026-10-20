plugins {
    `java-library`
    id("xyz.jpenilla.run-paper") version "2.3.1"   // dev server: ./gradlew :horizon-paper:runServer
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    implementation(project(":horizon-core"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

// Bundle core classes into the plugin jar (no shadow plugin needed: core has zero dependencies).
tasks.jar {
    from(project(":horizon-core").sourceSets.main.get().output)
    archiveBaseName.set("HorizonField")
}

tasks.runServer {
    minecraftVersion("1.21.11")
}
