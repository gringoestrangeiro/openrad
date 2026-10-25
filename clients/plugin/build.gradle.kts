import java.util.jar.JarFile
import java.io.DataInputStream

plugins {
    java
}

group = "io.github.gringoestrangeiro"
version = "2.1.5"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
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
                        check(input.readUnsignedShort() == 65) { "Class is not Java 21 bytecode" }
                    }
                    classes++
                }
            }
            check(classes > 0)
            println("Verified $classes Java 21 classes; plugin metadata/license; no native/runtime dependencies.")
        }
    }
}

tasks.check { dependsOn("verifyJar") }
