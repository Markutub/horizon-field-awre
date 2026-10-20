// Root build: shared Java configuration for subprojects. Requires Gradle 9.1+ if toolchain/release 25 is used.
subprojects {
    apply(plugin = "java")
    group = "dev.horizonfield"
    version = "0.1.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of((findProperty("javaRelease") as String).toInt()))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set((findProperty("javaRelease") as String).toInt())
        options.compilerArgs.add("-Xlint:all")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
