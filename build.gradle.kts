plugins { java }

group = "fr.noltox.hcplugins"
version = providers.gradleProperty("version").get()

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

dependencies {
    compileOnly("fr.noltox.hcplugins:core-api")
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation("com.github.retrooper:packetevents-spigot:2.14.0")
    // PacketEvents' test fixtures need the buffers normally provided by the server.
    testRuntimeOnly("io.netty:netty-buffer:4.2.19.Final")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("paper-plugin.yml") { expand("version" to pluginVersion) }
}

tasks.jar {
    archiveFileName.set("HCAdvancementsRedirect-${project.version}.jar")
}

tasks.test { useJUnitPlatform() }
