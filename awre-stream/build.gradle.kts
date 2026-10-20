plugins { `java-library` }

// Pure Java: glues the AWRE protocol sessions to the horizon-core LOD planner, scheduler and terrain store. No Bukkit, no Minecraft.
dependencies {
    api(project(":awre-protocol"))
    api(project(":horizon-core"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
