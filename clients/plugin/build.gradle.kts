import java.util.jar.JarFile
import java.io.DataInputStream

plugins {
    java
}

group = "io.github.gringoestrangeiro"
version = "2.2.0"

// Shared runtime includes the 2.1.5 CPU/RAM and peer-presence fixes.
// Only the Paper API and Java target vary by build.
val paperApi = providers.gradleProperty("openradPaperApi").orElse("1.20.6-R0.1-SNAPSHOT").get()
val bytecodeJava = if (paperApi.startsWith("26.")) 25 else if ((paperApi.startsWith("1.17-") || paperApi.startsWith("1.17.1-"))) 16 else if ((paperApi.startsWith("1.18-") || paperApi.startsWith("1.18.1-")) || paperApi.startsWith("1.18.2-") || paperApi.startsWith("1.19-") || paperApi.startsWith("1.19.1-") || paperApi.startsWith("1.19.2-") || paperApi.startsWith("1.19.3-") || paperApi.startsWith("1.19.4-") || paperApi.startsWith("1.20.1-") || paperApi.startsWith("1.20.2-") || paperApi.startsWith("1.20.3-") || paperApi.startsWith("1.20.4-")) 17 else 21

repositories {
    // Paper 1.20.5 imports a historical Adventure BOM snapshot that disappeared
    // from the default repositories. Mirror only that metadata-only module.
    if (paperApi.startsWith("1.20.5-")) {
        maven("https://repo.stellardrift.ca/maven/snapshots/") {
            content { includeModule("net.kyori", "adventure-bom") }
        }
    }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// The April 2024 Paper API references Adventure 4.17.0 snapshots that are
// no longer hosted. The BOM comes from its archived POM; substitute the May
// 2024 final 4.17.0 artifacts for non-BOM runtime API dependencies only.
if (paperApi.startsWith("1.20.5-")) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "net.kyori" && requested.name != "adventure-bom"
                    && requested.version == "4.17.0-SNAPSHOT") {
                useVersion("4.17.0")
                because("Paper 1.20.5 snapshot modules no longer published")
            }
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    testImplementation("io.papermc.paper:paper-api:$paperApi")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    toolchain.languageVersion.set(JavaLanguageVersion.of(if (bytecodeJava <= 17) 25 else bytecodeJava))
}

// Paper 1.19.2 predates JavaPlugin#getPluginMeta. Generate a legacy-only
// source copy so the current versions keep using the newer Paper API.
if ((paperApi.startsWith("1.17-") || paperApi.startsWith("1.17.1-")) || (paperApi.startsWith("1.18-") || paperApi.startsWith("1.18.1-")) || paperApi.startsWith("1.18.2-") || paperApi.startsWith("1.19-") || paperApi.startsWith("1.19.1-") || paperApi.startsWith("1.19.2-")) {
    val legacySources = layout.buildDirectory.dir("generated/java1192")
    val generateLegacy1192 by tasks.registering(Copy::class) {
        inputs.property("openradLegacyAdapterVersion", if ((paperApi.startsWith("1.17-") || paperApi.startsWith("1.17.1-"))) "java16-v4" else if (paperApi.startsWith("1.18-")) "java17-118-v1" else "java17-v1")
        from("src/main/java")
        into(legacySources)
        filteringCharset = "UTF-8"
        if ((paperApi.startsWith("1.17-") || paperApi.startsWith("1.17.1-"))) {
            filesMatching("**/*.java") {
                filter { line: String ->
                    line.replace("import java.util.HexFormat;", "import io.github.gringoestrangeiro.openradplugin.util.HexFormatCompat;")
                        .replace("java.util.HexFormat.of()", "io.github.gringoestrangeiro.openradplugin.util.HexFormatCompat.of()")
                        .replace("HexFormat.of()", "HexFormatCompat.of()")
                        .replace("public sealed interface Packet permits Frames, Keepalive, Other {}", "public interface Packet {}")
                        .replace("public sealed interface Result permits Rejected, ArpResponse, AdmittedTcp {}", "public interface Result {}")
                        .replace("import java.util.random.RandomGenerator;", "import java.util.Random;")
                        .replace("RandomGenerator random", "Random random")
                        .replace("yaml.options().parseComments(true);", "")
                        .replace("replace(configPath(), yaml.saveToString(), false);", "replace(configPath(), io.github.gringoestrangeiro.openradplugin.config.LegacyYamlComments.preserve(configPath(), yaml.saveToString()), false);")
                }
            }
        }
        if (paperApi.startsWith("1.18-")) {
            filesMatching("**/ConfigEditor.java") {
                filter { line: String ->
                    line.replace("yaml.options().parseComments(true);", "")
                        .replace("replace(configPath(), yaml.saveToString(), false);",
                            "replace(configPath(), io.github.gringoestrangeiro.openradplugin.config.LegacyYamlComments.preserve(configPath(), yaml.saveToString()), false);")
                }
            }
        }
        filesMatching("**/OpenRadPlugin.java") {
            filter { line: String ->
                line.replace("getPluginMeta().getVersion()", "getDescription().getVersion()")
            }
        }
    }
    sourceSets.main {
        java.setSrcDirs(listOf(legacySources.get().asFile))
        if ((paperApi.startsWith("1.17-") || paperApi.startsWith("1.17.1-")) || paperApi.startsWith("1.18-")) java.srcDir("src/compat16/java")
    }
    tasks.named<JavaCompile>("compileJava") { dependsOn(generateLegacy1192) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(bytecodeJava)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

// Keep the existing JUnit harness (which intentionally uses Java 21 test APIs)
// while compiling the legacy plugin classes for Java 16 or 17.
tasks.named<JavaCompile>("compileTestJava") {
    if (bytecodeJava <= 17) options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    minHeapSize = "32m"
    maxHeapSize = "256m"
    maxParallelForks = 1
    jvmArgs("-XX:ActiveProcessorCount=2")
}

// Version-specific Bukkit metadata, not a broad compatibility claim.
// Paper 1.20.3-1.20.4 do not recognize the newer 1.20.6 API-version string.
tasks.processResources {
    inputs.property("openradTargetPaperApi", paperApi)
    if ((paperApi.startsWith("1.17-") || paperApi.startsWith("1.17.1-")) || (paperApi.startsWith("1.18-") || paperApi.startsWith("1.18.1-")) || paperApi.startsWith("1.18.2-") || paperApi.startsWith("1.19-") || paperApi.startsWith("1.19.1-") || paperApi.startsWith("1.19.2-") || paperApi.startsWith("1.19.3-") || paperApi.startsWith("1.19.4-") || paperApi.startsWith("1.20.1-") || paperApi.startsWith("1.20.2-") || paperApi.startsWith("1.20.3-") || paperApi.startsWith("1.20.4-") || paperApi.startsWith("1.20.5-")) {
        filesMatching("plugin.yml") {
            filter { line: String -> line.replace("api-version: '1.20.6'", if ((paperApi.startsWith("1.17-") || paperApi.startsWith("1.17.1-"))) "api-version: '1.17'" else if ((paperApi.startsWith("1.18-") || paperApi.startsWith("1.18.1-")) || paperApi.startsWith("1.18.2-")) "api-version: '1.18'" else if (paperApi.startsWith("1.19-") || paperApi.startsWith("1.19.1-") || paperApi.startsWith("1.19.2-") || paperApi.startsWith("1.19.3-") || paperApi.startsWith("1.19.4-")) "api-version: '1.19'" else if (paperApi.startsWith("1.20.5-")) "api-version: '1.20.5'" else "api-version: '1.20'") }
        }
    }
}

tasks.jar {
    archiveFileName.set("OpenRadPlugin.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("LICENSE") { into("META-INF") }
    manifest {
        attributes("Implementation-Title" to "OpenRad Plugin", "Implementation-Version" to (project.version.toString() + " (alpha)"))
    }
}

tasks.register("verifyJar") {
    dependsOn(tasks.jar)
    doLast {
        JarFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            check(jar.getEntry("plugin.yml") != null && jar.getEntry("META-INF/LICENSE") != null)
            var classes = 0
            for (entry in jar.entries()) {
                check(!Regex("(?i).*[.](exe|dll|so|dylib|rs)$").matches(entry.name)) { "Native artifact in JAR" }
                if (entry.name.endsWith(".class")) {
                    check(entry.name.startsWith("io/github/gringoestrangeiro/openradplugin/")) { "Unexpected runtime dependency" }
                    DataInputStream(jar.getInputStream(entry)).use { input ->
                        check(input.readInt() == 0xCAFEBABE.toInt())
                        input.readUnsignedShort()
                        check(input.readUnsignedShort() == bytecodeJava + 44) { "Class version differs from Java $bytecodeJava target" }
                    }
                    classes++
                }
            }
            check(classes > 0)
            println("Verified $classes Java $bytecodeJava classes; plugin metadata/license; no native/runtime dependencies.")
        }
    }
}

tasks.check { dependsOn("verifyJar") }
