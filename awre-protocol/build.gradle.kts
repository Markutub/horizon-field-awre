plugins { `java-library` }

// Pure Java, zero runtime dependencies. Shared by awre-paper (bundled into the plugin jar) and awre-fabric (source-included).
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
