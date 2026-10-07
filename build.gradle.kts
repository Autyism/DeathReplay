plugins {
    // Applies fabric-loom-remap up to 1.21.11 and fabric-loom on 26.1+ (unobfuscated)
    id("dev.kikugie.loom-back-compat")
}

fun prop(name: String): String = project.property(name).toString()

val mc = sc.current.version
val requiredJava = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

version = "${prop("mod.version")}+$mc"
group = prop("mod.group")
base { archivesName.set(prop("mod.archives_name")) }

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net") { name = "FabricMC" }
    maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
    // Other mods, for compatibility testing in the dev client only (see with_sodium below).
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")

    // Mod Menu is optional for players: compiled against, and present in the dev client only.
    modCompileOnly("com.terraformersmc:modmenu:${prop("deps.modmenu")}")
    modLocalRuntime("com.terraformersmc:modmenu:${prop("deps.modmenu")}")

    // I play with Sodium, which replaces the chunk renderer. Add it to the dev client
    // with -Pwith_sodium=true; never a dependency of the mod itself.
    if (providers.gradleProperty("with_sodium").isPresent) {
        modLocalRuntime("maven.modrinth:sodium:${prop("deps.sodium")}")
    }
}

java {
    // Loom attaches the sources jar to the build.
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain { languageVersion.set(JavaLanguageVersion.of(requiredJava.majorVersion)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(requiredJava.majorVersion.toInt())
}

tasks.processResources {
    val props = mapOf(
        "version" to prop("mod.version"),
        "minecraft_compat" to prop("mod.mc_compat"),
        "loader_compat" to prop("mod.loader_compat"),
        "java" to requiredJava.majorVersion,
        "mixin_java" to "JAVA_${requiredJava.majorVersion}",
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "*.mixins.json")) { expand(props) }
}

tasks.named<Jar>("jar") {
    val baseName = prop("mod.archives_name")
    inputs.property("archivesName", baseName)
    from(rootProject.file("LICENSE")) { rename { "${it}_$baseName" } }
}

loom {
    runs {
        named("client") {
            // Forward -Ddr.* system properties (e.g. -Ddr.selftest=true) from the Gradle command line to the game.
            System.getProperties().forEach { key, value ->
                if (key.toString().startsWith("dr.")) {
                    vmArg("-D$key=$value")
                }
            }
            // 1.21.11 keeps the dev client folder it always had (run/); the other versions get
            // their own (versions/<version>/run/), since worlds and saved replays are per version.
            if (mc == "1.21.11") {
                runDir("../../run")
            }
        }
    }
}

// Collects the release jars of all versions in build/libs/<mod version>/
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/${prop("mod.version")}"))
}
