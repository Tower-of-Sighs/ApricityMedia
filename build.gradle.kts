import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import java.util.jar.JarFile

plugins {
    eclipse
    idea
    `java-library`
    `maven-publish`
    id("net.neoforged.moddev.legacyforge") version "2.0.91"
}

group = providers.gradleProperty("mod_group_id").get()
version = providers.gradleProperty("mod_version").get()

val javacppVersion = "1.5.13"
val ffmpegVersion = "8.0.1-1.5.13"
val nativePlatforms =
    listOf(
        "linux-x86_64",
        "linux-arm64",
        "macosx-x86_64",
        "macosx-arm64",
        "windows-x86_64",
        "android-arm64",
        "android-x86_64",
    )

val runtimeNativePlatform: String? =
    run {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val osArch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        when {
            osName.contains("win") -> "windows-x86_64"
            osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "macosx-arm64" else "macosx-x86_64"
            osName.contains("linux") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "linux-arm64" else "linux-x86_64"
            else -> null
        }
    }

base {
    val modName = providers.gradleProperty("mod_name").get()
    val minecraftVersion = providers.gradleProperty("minecraft_version").get()
    archivesName.set("$modName-forge-$minecraftVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

legacyForge {
    val minecraftVersion = providers.gradleProperty("minecraft_version").get()
    val forgeVersion = providers.gradleProperty("forge_version").get()
    version = "$minecraftVersion-$forgeVersion"

    runs {
        val modId = providers.gradleProperty("mod_id").get()

        create("client") {
            client()
            systemProperty("forge.enabledGameTestNamespaces", modId)
        }

        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("forge.enabledGameTestNamespaces", modId)
        }

        create("gameTestServer") {
            type = "gameTestServer"
            systemProperty("forge.enabledGameTestNamespaces", modId)
        }

        create("data") {
            data()
            programArguments.addAll(
                "--mod",
                modId,
                "--all",
                "--output",
                file("src/generated/resources/").absolutePath,
                "--existing",
                file("src/main/resources/").absolutePath,
            )
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        val modId = providers.gradleProperty("mod_id").get()
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

sourceSets {
    named("main") {
        resources.srcDir("src/generated/resources")
    }
}

val localRuntime by configurations.creating

configurations.named("runtimeClasspath") {
    extendsFrom(localRuntime)
}

obfuscation {
    createRemappingConfiguration(localRuntime)
}

repositories {
    mavenLocal()
    maven("https://maven.sighs.cc/repository/maven-releases/")
    maven("https://maven.sighs.cc/repository/maven-snapshots/")
    maven("https://www.cursemaven.com") {
        name = "Curse Maven"
        content { includeGroup("curse.maven") }
    }
    mavenCentral()
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")

    modImplementation("curse.maven:kubejs-238086:5853326")
    modImplementation("curse.maven:rhino-416294:6186971")
    modImplementation("curse.maven:architectury-api-419699:5137938")

    modImplementation("com.sighs:ApricityUI-forge-1.20.1:1.1.2")

    fun compileOnlyAndJarJar(notation: String, strictVersion: String) {
        compileOnly(notation) {
            version {
                strictly("[$strictVersion]")
                prefer(strictVersion)
            }
        }
        add("jarJar", notation) {
            version {
                strictly("[$strictVersion]")
                prefer(strictVersion)
            }
        }
    }

    compileOnlyAndJarJar("org.bytedeco:javacpp:$javacppVersion", javacppVersion)
    compileOnlyAndJarJar("org.bytedeco:ffmpeg:$ffmpegVersion", ffmpegVersion)

    add("additionalRuntimeClasspath", "org.bytedeco:javacpp:$javacppVersion")
    add("additionalRuntimeClasspath", "org.bytedeco:ffmpeg:$ffmpegVersion")
    if (runtimeNativePlatform != null) {
        add("additionalRuntimeClasspath", "org.bytedeco:javacpp:$javacppVersion:$runtimeNativePlatform")
        add("additionalRuntimeClasspath", "org.bytedeco:ffmpeg:$ffmpegVersion:$runtimeNativePlatform")
    }

    nativePlatforms.forEach { platform ->
        compileOnlyAndJarJar("org.bytedeco:javacpp:$javacppVersion:$platform", javacppVersion)
        compileOnlyAndJarJar("org.bytedeco:ffmpeg:$ffmpegVersion:$platform", ffmpegVersion)
    }
}

mixin {
    val modId = providers.gradleProperty("mod_id").get()
    add(sourceSets.main.get(), "$modId.refmap.json")
    config("$modId.mixins.json")
}

tasks.named<Jar>("jar") {
    val modId = providers.gradleProperty("mod_id").get()
    manifest.attributes(mapOf("MixinConfigs" to "$modId.mixins.json"))

    // Exclude decoder classes — loaded via URLClassLoader at runtime
    exclude("**/video/FFmpegVideoDecoder*.class")
    exclude("**/audio/FFmpegAudioDecoder*.class")
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties =
        mapOf(
            "minecraft_version" to providers.gradleProperty("minecraft_version").get(),
            "minecraft_version_range" to providers.gradleProperty("minecraft_version_range").get(),
            "forge_version" to providers.gradleProperty("forge_version").get(),
            "forge_version_range" to providers.gradleProperty("forge_version_range").get(),
            "loader_version_range" to providers.gradleProperty("loader_version_range").get(),
            "mod_id" to providers.gradleProperty("mod_id").get(),
            "mod_name" to providers.gradleProperty("mod_name").get(),
            "mod_license" to providers.gradleProperty("mod_license").get(),
            "mod_version" to providers.gradleProperty("mod_version").get(),
            "mod_authors" to providers.gradleProperty("mod_authors").get(),
            "mod_description" to providers.gradleProperty("mod_description").get(),
        )

    inputs.properties(replaceProperties)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) { expand(replaceProperties) }
    exclude("assets/apricityui/apricity/apricityui/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val reobfJarTask = tasks.named("reobfJar")
val baseObfJar =
    layout.buildDirectory.file("libs/${base.archivesName.get()}-${project.version}.jar")
val variantOutputDir = layout.buildDirectory.dir("libs")
val distMarkerSalt = "AUIVideo-Dist-v1"

fun sha256Hex(value: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    val builder = StringBuilder(hash.size * 2)
    for (item in hash) {
        val hi = (item.toInt() shr 4) and 0xF
        val lo = item.toInt() and 0xF
        builder.append(Character.forDigit(hi, 16))
        builder.append(Character.forDigit(lo, 16))
    }
    return builder.toString()
}

fun writeDistributionMarker(distribution: String, outputFile: File) {
    val normalized = distribution.trim().lowercase(Locale.ROOT)
    val nonce = UUID.randomUUID().toString().replace("-", "")
    val checksum = sha256Hex("$normalized:$nonce:$distMarkerSalt")
    outputFile.parentFile.mkdirs()
    outputFile.writeText(
        "distribution=$normalized\nnonce=$nonce\nchecksum=$checksum\n",
        Charsets.UTF_8,
    )
}

fun filterJarJarMetadata(jarFile: File, keepEntry: (String) -> Boolean, outputFile: File) {
    val entryPath = "META-INF/jarjar/metadata.json"
    JarFile(jarFile).use { jar ->
        val entry = jar.getJarEntry(entryPath)
        if (entry == null) {
            outputFile.parentFile.mkdirs()
            outputFile.writeText("{\"jars\":[]}", Charsets.UTF_8)
            return
        }

        val text = jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = JsonSlurper().parseText(text)
        val parsedMap =
            parsed as? MutableMap<*, *>
                ?: run {
                    outputFile.parentFile.mkdirs()
                    outputFile.writeText("{\"jars\":[]}", Charsets.UTF_8)
                    return
                }

        val jars = parsedMap["jars"]
        if (jars !is List<*>) {
            outputFile.parentFile.mkdirs()
            outputFile.writeText("{\"jars\":[]}", Charsets.UTF_8)
            return
        }

        val filtered =
            jars.filter { item ->
                val itemMap = item as? Map<*, *>
                val path = itemMap?.get("path")?.toString()
                path != null && keepEntry(path)
            }

        val output = LinkedHashMap<Any?, Any?>()
        for (entryItem in parsedMap.entries) {
            output[entryItem.key] = entryItem.value
        }
        output["jars"] = filtered

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(output)),
            Charsets.UTF_8,
        )
    }
}

val DECODER_JAR_NAME = "decoder.jar"
val decoderJarArchive = layout.buildDirectory.file("tmp/decoder-jar/$DECODER_JAR_NAME")

tasks.register<Jar>("decoderJar") {
    dependsOn(tasks.named("compileJava"))
    includeEmptyDirs = false
    from(
        sourceSets.main.get().output.asFileTree.matching {
            include("**/FFmpegVideoDecoder*.class")
            include("**/FFmpegAudioDecoder*.class")
        },
    )
    archiveFileName.set(DECODER_JAR_NAME)
    destinationDirectory.set(layout.buildDirectory.dir("tmp/decoder-jar"))
}

fun registerVariantJarTask(
    taskName: String,
    classifier: String,
    distribution: String,
    keepEntry: (String) -> Boolean,
) {
    tasks.register<Jar>(taskName) {
        dependsOn(reobfJarTask, "decoderJar")
        archiveBaseName.set(base.archivesName)
        archiveVersion.set(project.version.toString())
        archiveClassifier.set(classifier)
        destinationDirectory.set(variantOutputDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from({ zipTree(baseObfJar.get().asFile) }) {
            eachFile(
                Action<FileCopyDetails> {
                    val entryPath = path.replace("\\", "/")
                    if (entryPath == "META-INF/jarjar/metadata.json") {
                        exclude()
                        return@Action
                    }

                    // Check keepEntry FIRST so platform/downloader filtering works
                    if (!keepEntry(entryPath)) {
                        exclude()
                        return@Action
                    }

                    // Move javacpp/ffmpeg JARs out of jarjar to avoid module system crash
                    if (entryPath.startsWith("META-INF/jarjar/") && (entryPath.contains("javacpp") || entryPath.contains(
                            "ffmpeg"
                        ))
                    ) {
                        path = "META-INF/apricitymedia/runtime/" + entryPath.removePrefix("META-INF/jarjar/")
                    }
                },
            )
        }

        // Include decoder.jar at runtime path (loaded by URLClassLoader at runtime)
        from(decoderJarArchive) {
            into("META-INF/apricitymedia/runtime")
            rename { DECODER_JAR_NAME }
        }

        val metadataOutput = layout.buildDirectory.file("tmp/jarjar/$taskName/metadata.json")
        val distributionMarkerOutput = layout.buildDirectory.file("tmp/dist/$taskName/dist.properties")
        inputs.file(baseObfJar)
        outputs.file(metadataOutput)
        outputs.file(distributionMarkerOutput)
        doFirst {
            val jarFile = baseObfJar.get().asFile
            val metadataFile = metadataOutput.get().asFile
            val metadataKeepEntry: (String) -> Boolean = { path ->
                if (!keepEntry(path)) {
                    false
                } else if (!path.startsWith("META-INF/jarjar/")) {
                    true
                } else {
                    // Exclude javacpp/ffmpeg from jarjar metadata (they're in runtime/)
                    !(path.contains("javacpp") || path.contains("ffmpeg"))
                }
            }
            filterJarJarMetadata(jarFile, metadataKeepEntry, metadataFile)
            val markerFile = distributionMarkerOutput.get().asFile
            writeDistributionMarker(distribution, markerFile)
        }
        from(metadataOutput) {
            into("META-INF/jarjar")
            rename { "metadata.json" }
        }
        from(distributionMarkerOutput) {
            into("META-INF/apricitymedia")
            rename { "dist.properties" }
        }
    }
}

registerVariantJarTask("jarFullVariant", "full", "full") { true }

registerVariantJarTask("jarDownloaderVariant", "downloader", "downloader") { path ->
    if (!path.startsWith("META-INF/jarjar/")) return@registerVariantJarTask true
    if ((path.contains("javacpp") || path.contains("ffmpeg")) && nativePlatforms.any { p -> path.contains(p) }) {
        return@registerVariantJarTask false
    }
    true
}

nativePlatforms.forEach { platform ->
    val taskSuffix = platform.replace("-", "_").replace(".", "_")
    registerVariantJarTask("jarPlatformVariant_$taskSuffix", "platform-$platform", "platform-$platform") { path ->
        if (!path.startsWith("META-INF/jarjar/")) return@registerVariantJarTask true
        if (path.contains("javacpp") || path.contains("ffmpeg")) {
            if (nativePlatforms.any { p -> path.contains(p) }) {
                return@registerVariantJarTask path.contains(platform)
            }
        }
        true
    }
}

tasks.register("buildDistVariants") {
    dependsOn("jarFullVariant", "jarDownloaderVariant")
    nativePlatforms.forEach { platform ->
        dependsOn("jarPlatformVariant_${platform.replace("-", "_").replace(".", "_")}")
    }
}

tasks.named("build") { dependsOn("buildDistVariants") }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = base.archivesName.get()
            version = project.version.toString()
        }
    }
    repositories {
        val publishUrl =
            if (project.version.toString().lowercase(Locale.ROOT).contains("snapshot")) {
                "https://maven.sighs.cc/repository/maven-snapshots/"
            } else {
                "https://maven.sighs.cc/repository/maven-releases/"
            }
        maven {
            url = uri(publishUrl)
            credentials {
                username = System.getenv("SIGHS_PUBLISH_USER")
                password = System.getenv("SIGHS_PUBLISH_PASSWORD")
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
