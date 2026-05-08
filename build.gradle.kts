import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

plugins {
    eclipse
    idea
    `java-library`
    `maven-publish`
    id("net.neoforged.moddev.legacyforge") version "2.0.91"
}

group = providers.gradleProperty("mod_group_id").get()
version = providers.gradleProperty("mod_version").get()

// JNI native platform → source zip mapping (four-platform fat jar)
val jniNativePlatforms = mapOf(
    "windows-x64"   to "ffmpeg-jni-mc-1.20.1-windows-x64.zip",
    "linux-x64"     to "ffmpeg-jni-mc-1.20.1-linux-x64.zip",
    "macos-arm64"   to "ffmpeg-jni-mc-1.20.1-macos-arm64.zip",
    "android-arm64" to "ffmpeg-jni-mc-1.20.1-android-arm64.zip",
)

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
                "--mod", modId, "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath,
            )
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        val modId = providers.gradleProperty("mod_id").get()
        create(modId) { sourceSet(sourceSets.main.get()) }
    }
}

// ---------------------------------------------------------------
//  Sources — include generated JNI resources
// ---------------------------------------------------------------
sourceSets {
    named("main") {
        resources.srcDir("src/generated/resources")
        resources.srcDir(layout.buildDirectory.dir("generated/jni-resources"))
    }
}

// ---------------------------------------------------------------
//  Copy native zips into resources (renamed to <platform>.zip)
// ---------------------------------------------------------------
val processJniNatives by tasks.registering(Copy::class) {
    val outputDir = layout.buildDirectory.dir("generated/jni-resources/META-INF/apricitymedia/natives")
    from(rootProject.layout.projectDirectory.dir("jni")) {
        jniNativePlatforms.forEach { (platform, zipName) ->
            include(zipName)
            rename(zipName, "${platform}.zip")
        }
    }
    into(outputDir)
}

tasks.named("processResources") { dependsOn(processJniNatives) }

// ---------------------------------------------------------------
//  Dependencies
// ---------------------------------------------------------------
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
}

// ---------------------------------------------------------------
//  Mixin
// ---------------------------------------------------------------
mixin {
    val modId = providers.gradleProperty("mod_id").get()
    add(sourceSets.main.get(), "$modId.refmap.json")
    config("$modId.mixins.json")
}

// ---------------------------------------------------------------
//  Jar — includes native zips, writes dist marker to "jni"
// ---------------------------------------------------------------
val distMarkerSalt = "AUIVideo-Dist-v1"

fun sha256Hex(value: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    val builder = StringBuilder(hash.size * 2)
    for (item in hash) {
        builder.append(Character.forDigit((item.toInt() shr 4) and 0xF, 16))
        builder.append(Character.forDigit(item.toInt() and 0xF, 16))
    }
    return builder.toString()
}

val writeDistMarker by tasks.registering {
    val outputFile = layout.buildDirectory.file("generated/jni-resources/META-INF/apricitymedia/dist.properties")
    outputs.file(outputFile)
    doLast {
        val normalized = "jni"
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val checksum = sha256Hex("$normalized:$nonce:$distMarkerSalt")
        outputFile.get().asFile.parentFile.mkdirs()
        outputFile.get().asFile.writeText(
            "distribution=$normalized\nnonce=$nonce\nchecksum=$checksum\n",
            Charsets.UTF_8,
        )
    }
}

tasks.named("processResources") { dependsOn(writeDistMarker) }

tasks.named<Jar>("jar") {
    val modId = providers.gradleProperty("mod_id").get()
    manifest.attributes(mapOf("MixinConfigs" to "$modId.mixins.json"))
}

// sourcesJar also needs the generated JNI resources
tasks.named<Jar>("sourcesJar") {
    dependsOn(processJniNatives, writeDistMarker)
    exclude("META-INF/apricitymedia/natives/**")
    exclude("META-INF/apricitymedia/dist.properties")
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
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

// ---------------------------------------------------------------
//  Publishing
// ---------------------------------------------------------------
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
        val publishUrl = if (project.version.toString().lowercase(Locale.ROOT).contains("snapshot")) {
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
