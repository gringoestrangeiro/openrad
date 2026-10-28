plugins {
    id("fabric-loom") version "1.13.6"
    java
}
val legacyJava17 = providers.gradleProperty("openradMinecraftVersion").orElse("1.21.1").get() in setOf("1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20.1", "1.20.2", "1.20.3", "1.20.4")
java { toolchain.languageVersion.set(JavaLanguageVersion.of(if (legacyJava17) 25 else 21)) }
// Each Minecraft version gets its own remapped JAR. Never claim cross-version
// compatibility simply by widening fabric.mod.json's Minecraft constraint.
val minecraftTarget = providers.gradleProperty("openradMinecraftVersion").orElse("1.21.1").get()
val supportedTargets = mapOf(
    "1.18.2" to "0.59.1+1.18.2",
    "1.19" to "0.58.0+1.19",
    "1.19.1" to "0.58.5+1.19.1",
    "1.19.2" to "0.77.0+1.19.2",
    "1.19.3" to "0.76.1+1.19.3",
    "1.19.4" to "0.87.2+1.19.4",
    "1.20.1" to "0.92.12+1.20.1",
    "1.20.2" to "0.91.6+1.20.2",
    "1.20.3" to "0.91.1+1.20.3",
    "1.20.4" to "0.97.3+1.20.4",
    "1.20.5" to "0.97.8+1.20.5",
    "1.20.6" to "0.100.8+1.20.6",
    "1.21" to "0.102.0+1.21",
    "1.21.1" to "0.116.17+1.21.1",
    "1.21.2" to "0.106.1+1.21.2",
    "1.21.3" to "0.114.1+1.21.3",
    "1.21.4" to "0.119.4+1.21.4",
    "1.21.5" to "0.128.2+1.21.5",
    "1.21.6" to "0.128.2+1.21.6",
    "1.21.7" to "0.129.0+1.21.7",
    "1.21.8" to "0.136.1+1.21.8",
    "1.21.9" to "0.134.1+1.21.9",
    "1.21.10" to "0.138.4+1.21.10",
    "1.21.11" to "0.141.3+1.21.11"
)
val fabricApi = supportedTargets[minecraftTarget]
    ?: error("Unsupported target: $minecraftTarget. Allowed: ${supportedTargets.keys}")
dependencies {
    minecraft("com.mojang:minecraft:$minecraftTarget")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.17.3")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
sourceSets.main {
    java.srcDir("../core/src/main/java")
    // Exclude bytecode injections for APIs that do not exist in this target.
    if (minecraftTarget == "1.21.11")
        java.exclude("**/ConnectionMixin.java")
    else
        java.exclude("**/ConnectionRoutingMixin.java")
}
// Minecraft 1.19.4 uses PoseStack in Screen.render rather than GuiGraphics.
// Generate a source-only compatibility copy; never modify new-version UI sources.
if (minecraftTarget in setOf("1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4")) {
    val generated1194 = layout.buildDirectory.dir("generated/java1194")
    val generate1194 by tasks.registering(Sync::class) {
        inputs.property("compatibilityAdapterVersion", "1182-v3")
        from("src/main/java")
        into(generated1194)
        filteringCharset = "UTF-8"
        if (minecraftTarget in setOf("1.18.2", "1.19", "1.19.1", "1.19.2")) {
            exclude("**/mixin/MultiplayerScreenMixin.java")
            filesMatching("**/*.java") {
                filter { line: String ->
                    line.replace("Button.builder(", "LegacyButtonFactory.builder(")
                        .replace("import net.minecraft.client.gui.components.Tooltip;", "// Tooltip was introduced after Minecraft 1.19.2")
                        .replace("save.setTooltip(active ? Tooltip.create(OpenRadI18n.tr(\"openrad.menu.reconnect_note\")) : null);", ";")
                        .replace("option.setTooltip(Tooltip.create(Component.literal(item.entry().name())));", ";")
                        .replace("ScreenEvents.afterTick(screen).register(ignored -> button.setTooltip(Tooltip.create(", "/* Legacy 1.19.2 has no dynamic Tooltip API")
                        .replace("OpenRadI18n.tr(\"openrad.menu.status\", phase(controller.status().phase())))));", "*/")
                        .replace("box.getX()", "box.x")
                        .replace("box.getY()", "box.y")
                }
            }
        }
        if (minecraftTarget == "1.18.2") {
            filesMatching("**/*.java") {
                filter { source: String ->
                    source.replace("Component.literal(", "new net.minecraft.network.chat.TextComponent(")
                        .replace("Component.translatable(", "new net.minecraft.network.chat.TranslatableComponent(")
                        .replace("Component.empty()", "new net.minecraft.network.chat.TextComponent(\"\")")
                        .replace("rebuildWidgets();", "clearWidgets(); init();")
                        .replace("net.fabricmc.fabric.api.client.command.v2.", "net.fabricmc.fabric.api.client.command.v1.")
                        .replace("import net.fabricmc.fabric.api.client.command.v1.ClientCommandRegistrationCallback;", "")
                        .replace("ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(", "net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.DISPATCHER.register(")
                        .replace("}))))", "})))")
                }
            }
        }
        // Mixin uses a different static connection entrypoint before MC 1.20.
        filesMatching("**/ConnectionMixin.java") {
            filter { line: String ->
                line.replace("connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
                    "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;")
            }
        }
        // 1.19.3 returns a LanguageInfo rather than a locale String.
        if (minecraftTarget in setOf("1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3")) {
            filesMatching("**/OpenRad*Screen.java") {
                filter { line: String ->
                    line.replace("minecraft.getLanguageManager().getSelected()",
                        "minecraft.getLanguageManager().getSelected().getCode()")
                }
            }
        }
        filesMatching("**/OpenRad*Screen.java") {
            filter { sourceLine: String ->
                sourceLine.replace("net.minecraft.client.gui.GuiGraphics", "com.mojang.blaze3d.vertex.PoseStack")
                    .replace("GuiGraphics ", "PoseStack ")
                    .replace("graphics.drawCenteredString(font,", "drawCenteredString(graphics, font,")
                    .replace("graphics.drawString(font,", "drawString(graphics, font,")
            }
        }
    }
    sourceSets.main {
        val legacyRoots = mutableListOf(generated1194.get().asFile, file("../core/src/main/java"))
        if (minecraftTarget in setOf("1.18.2", "1.19", "1.19.1", "1.19.2")) legacyRoots.add(file("src/compat1192/java"))
        java.setSrcDirs(legacyRoots)
    }
    tasks.named<JavaCompile>("compileJava") { dependsOn(generate1194) }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(if (legacyJava17) 17 else 21)
    options.encoding = "UTF-8"
}
tasks.named<JavaCompile>("compileTestJava") {
    if (legacyJava17) options.release.set(21)
}
tasks.processResources {
    // Track the version as a task input so Gradle refreshes fabric.mod.json on updates.
    inputs.property("openradModVersion", project.version.toString())
    inputs.property("openradMinecraftVersion", minecraftTarget)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version.toString(), "minecraftVersion" to minecraftTarget, "javaVersion" to (if (legacyJava17) 17 else 21))
    }
    val newerRouting = minecraftTarget == "1.21.11"
    inputs.property("openradRouteMixin", newerRouting)
    filesMatching("openrad-client.mixins.json") {
        expand("connectionMixin" to (if (newerRouting) "ConnectionRoutingMixin" else "ConnectionMixin"))
    }
}
tasks.jar { from("../LICENSE") { into("META-INF") } }

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "256m"
    maxParallelForks = 1
}

// An explicitly opted-in integration test mod. It is never part of jar/remapJar.
val smoke by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}
if (providers.gradleProperty("openradSmoke").orNull == "true") {
    val prepareSmokeRun = tasks.register("prepareSmokeRun") {
        doLast {
            val config = file("run-smoke/config/openrad-client/openrad-client.properties")
            config.parentFile.mkdirs()
            config.writeText("auto-connect=false\nnetwork-names=\n", Charsets.UTF_8)
            file("run-smoke/openrad-smoke-result.txt").writeText("NOT_STARTED\n", Charsets.UTF_8)
        }
    }
    loom {
        mods {
            create("openrad-client") { sourceSet(sourceSets.main.get()) }
            create("openrad-client-smoke") { sourceSet(smoke) }
        }
        runs {
            create("smokeClient") {
                client()
                name("OpenRad isolated runtime smoke")
                source(smoke)
                runDir("run-smoke")
                vmArg("-Dopenrad.smoke=true")
                vmArg("-Xmx768m")
                programArgs("--username", "OpenRadSmoke", "--width", "854", "--height", "480")
            }
        }
    }
    tasks.matching { it.name == "runSmokeClient" }.configureEach { dependsOn(prepareSmokeRun) }
}

// Interactive UI preview uses a separate game directory and an opt-in VPN profile.
if (providers.gradleProperty("openradPreview").orNull == "true") {
    loom { runs { getByName("client").runDir("run-preview-v022") } }
}
