import java.util.jar.JarFile
import java.io.DataInputStream

plugins {
    java
}

group = "io.github.gringoestrangeiro"
version = "2.1.5"

// Shared runtime includes the 2.1.5 CPU/RAM and peer-presence fixes.
// Only the Paper API and Java target vary by build.
val paperApi = providers.gradleProperty("openradPaperApi").orElse("1.20.6-R0.1-SNAPSHOT").get()
val bytecodeJava = if (paperApi.startsWith("26.")) 25 else 21

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
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
    toolchain.languageVersion.set(JavaLanguageVersion.of(bytecodeJava))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(bytecodeJava)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
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
    if (paperApi.startsWith("1.20.3-") || paperApi.startsWith("1.20.4-")) {
        filesMatching("plugin.yml") {
            filter { line: String -> line.replace("api-version: '1.20.6'", "api-version: '1.20'") }
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
