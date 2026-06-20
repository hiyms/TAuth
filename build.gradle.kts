plugins {
    idea
    `maven-publish`
    kotlin("jvm") version "2.0.0"
    id("net.minecraftforge.gradle") version "[6.0,6.2)"
    id("org.parchmentmc.librarian.forgegradle") version "1.+"
    id("org.spongepowered.mixin") version "0.7.+"
}

// Properties from gradle.properties
val modId: String get() = project.property("modId") as String
val modVersion: String get() = project.property("modVersion") as String
val modGroupId: String get() = project.property("modGroupId") as String
val modName: String get() = project.property("modName") as String
val modAuthors: String get() = project.property("modAuthors") as String
val modDescription: String get() = project.property("modDescription") as String
val modLicense: String get() = project.property("modLicense") as String
val mcVer: String get() = project.property("mcVersion") as String
val mcVerRange: String get() = project.property("mcVersionRange") as String
val forgeVer: String get() = project.property("forgeVer") as String
val forgeVerRange: String get() = project.property("forgeVerRange") as String
val kffVer: String get() = project.property("kffVer") as String
val parchmentChannel: String get() = project.property("parchmentChannel") as String
val parchmentVer: String get() = project.property("parchmentVer") as String

version = modVersion
group = modGroupId

base { archivesName.set(modId) }

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

println("Java: ${System.getProperty("java.version")}, JVM: ${System.getProperty("java.vm.version")} (${System.getProperty("java.vendor")}), Arch: ${System.getProperty("os.arch")}")

minecraft {
    mappings(parchmentChannel, parchmentVer)

    runs {
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("forge.enabledGameTestNamespaces", modId)
            mods { create(modId) { source(sourceSets.main.get()) } }
        }
        create("server") {
            workingDirectory(project.file("run/server"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("forge.enabledGameTestNamespaces", modId)
            mods { create(modId) { source(sourceSets.main.get()) } }
        }
        create("gameTestServer") {
            workingDirectory(project.file("run/server"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("forge.enabledGameTestNamespaces", modId)
            mods { create(modId) { source(sourceSets.main.get()) } }
        }
        create("data") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            args("--mod", modId, "--all", "--output", file("src/generated/resources/"), "--existing", file("src/main/resources"))
            mods { create(modId) { source(sourceSets.main.get()) } }
        }
    }
}

sourceSets.main.get().resources { srcDir("src/generated/resources/") }

repositories {
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
        content { includeGroup("thedarkcolour") }
    }
    maven {
        name = "LDLib"
        url = uri("https://maven.lowdragmc.com/")
        content { includeGroup("com.lowdragmc.ldlib") }
    }
}

dependencies {
    minecraft("net.minecraftforge:forge:$mcVer-$forgeVer")
    implementation("thedarkcolour:kotlinforforge:$kffVer")

    // Nitrite v4
    implementation(platform("org.dizitart:nitrite-bom:4.4.1"))
    implementation("org.dizitart:nitrite")
    implementation("org.dizitart:nitrite-mvstore-adapter")
    implementation("org.dizitart:potassium-nitrite")

    // LDLib client UI
    compileOnly("com.lowdragmc.ldlib:ldlib-forge-1.20.1:1.0.38")

    // TrueUUID premium detection API
    compileOnly(files("libs/TrueUUID.jar"))
}

mixin {
    add(sourceSets.main.get(), "tauth.mixins.refmap.json")
    config("tauth.mixins.json")
}

val resourceTargets = listOf("META-INF/mods.toml", "pack.mcmeta")
val replaceProperties = mapOf(
    "modId" to modId, "modVersion" to modVersion, "modName" to modName,
    "modAuthors" to modAuthors, "modDescription" to modDescription, "modLicense" to modLicense,
    "minecraftVersion" to mcVer, "minecraftVersionRange" to mcVerRange,
    "forgeVersion" to forgeVer, "forgeVersionRange" to forgeVerRange
)

tasks.processResources {
    inputs.properties(replaceProperties)
    filesMatching(resourceTargets) { expand(replaceProperties) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.jar {
    manifest {
        attributes["Specification-Title"] = modId
        attributes["Specification-Vendor"] = modAuthors
        attributes["Specification-Version"] = "1"
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = project.version
        attributes["Implementation-Vendor"] = modAuthors
    }
    finalizedBy("reobfJar")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

publishing {
    publications { create<MavenPublication>("mavenJava") { artifact(tasks.jar) } }
    repositories { maven { url = uri("file://${project.projectDir}/mcmodsrepo") } }
}
