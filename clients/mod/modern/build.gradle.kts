plugins {
    id("net.fabricmc.fabric-loom") version "1.17.20"
    java
}
group = "io.github.gringoestrangeiro"
version = "2.1.5"
val target = providers.gradleProperty("openradMinecraftVersion").orElse("26.3").get()
val apis = mapOf(
    "26.1" to "0.145.1+26.1",
    "26.1.1" to "0.145.4+26.1.1",
    "26.1.2" to "0.155.3+26.1.2",
    "26.2" to "0.161.0+26.2",
    "26.3" to "0.164.0+26.3"
)
val fabricApi = apis[target] ?: error("Modern Fabric requires 26.1 through 26.3: $target")
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
sourceSets.main {
    java.srcDirs("../core/src/main/java", "src/main/java")
}
dependencies {
    minecraft("com.mojang:minecraft:$target")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}
tasks.processResources {
    inputs.property("openradMinecraftVersion", target)
    inputs.property("openradModVersion", version.toString())
    filesMatching("fabric.mod.json") {
        expand("version" to project.version.toString(), "minecraftVersion" to target)
    }
    filesMatching("openrad-client.mixins.json") {
        expand("connectionMixin" to "ConnectionRoutingMixin")
    }
}
tasks.jar {
    archiveFileName.set("OpenRadClientMod-2.1.5-modern-$target.jar")
    from("../LICENSE") { into("META-INF") }
}
