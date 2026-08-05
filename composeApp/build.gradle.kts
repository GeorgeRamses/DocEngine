import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

// ─────────────────────────────────────────────────────────────────────────────
// VERSION — read from version.properties
// ─────────────────────────────────────────────────────────────────────────────

val versionPropsFile = rootProject.file("version.properties")
val versionProps     = Properties().apply { versionPropsFile.inputStream().use { load(it) } }

val vMajor = versionProps["VERSION_MAJOR"].toString().trim()
val vMinor = versionProps["VERSION_MINOR"].toString().trim()
val vPatch = versionProps["VERSION_PATCH"].toString().trim()
val vBuild = versionProps["VERSION_BUILD"].toString().trim()

val fullVersion          = "$vMajor.$vMinor.$vPatch"
val fullVersionWithBuild = "$vMajor.$vMinor.$vPatch+$vBuild"

// ─────────────────────────────────────────────────────────────────────────────

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by sourceSets.getting
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.tess4j)
            implementation(libs.pdfbox)
            implementation(libs.slf4j)
            implementation(libs.javacv)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BUILDCONFIG GENERATION
// ─────────────────────────────────────────────────────────────────────────────

afterEvaluate {
    kotlin.sourceSets.getByName("jvmMain").kotlin
        .srcDir(layout.buildDirectory.dir("generated/buildconfig"))
}

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig")
    val propsFile = layout.projectDirectory.file("../version.properties")

    inputs.file(propsFile)           // proper input declaration — config cache safe
    outputs.dir(outputDir)

    doLast {
        val dir   = outputDir.get().asFile
        dir.mkdirs()
        val props = Properties().apply { propsFile.asFile.inputStream().use { load(it) } }
        val maj   = props["VERSION_MAJOR"].toString().trim()
        val min   = props["VERSION_MINOR"].toString().trim()
        val pat   = props["VERSION_PATCH"].toString().trim()
        val bld   = props["VERSION_BUILD"].toString().trim()

        val version     = "$maj.$min.$pat"
        val versionFull = "$maj.$min.$pat+$bld"

        File(dir, "BuildConfig.kt").writeText(
            "package nsf.georgeramsis.docengine\n\n" +
                    "object BuildConfig {\n" +
                    "    const val VERSION       = \"$version\"\n" +
                    "    const val VERSION_FULL  = \"$versionFull\"\n" +
                    "    const val VERSION_BUILD = $bld\n" +
                    "}\n"
        )
        println("  ✅ BuildConfig.kt generated → $version")
    }
}

tasks.matching { it.name == "compileKotlinJvm" || it.name == "jvmProcessResources" }
    .configureEach { dependsOn(generateBuildConfig) }

// ─────────────────────────────────────────────────────────────────────────────

compose.desktop {
    application {
        mainClass = "nsf.georgeramsis.docengine.MainKt"

        // Pin JNA native-lib extraction to a stable directory so that
        // clearing %TEMP% never breaks Tess4J / JNA at runtime.
        // Must be a JVM arg (not System.setProperty) to take effect
        // before any class-level static initialiser runs.
        val jnaDir = File(System.getProperty("user.home"), ".myapp/jna")
            .absolutePath.replace("\\", "/")
        jvmArgs += listOf(
            "-Djna.tmpdir=$jnaDir",
            "-Djna.nosys=false"
        )

        // Ensure java.io.tmpdir points to a directory that always exists.
        // Tess4j's LoadLibs extracts tesseract/leptonica native DLLs via
        // Files.createTempDirectory("tess4j") which uses java.io.tmpdir.
        // When the user empties %TEMP%, that extraction fails.  Pointing
        // java.io.tmpdir at our own persistent folder prevents this.
        val appTmpDir = File(System.getProperty("user.home"), ".myapp/tmp")
            .absolutePath.replace("\\", "/")
        jvmArgs += "-Djava.io.tmpdir=$appTmpDir"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb)
            packageName    = "DocEngine"
            packageVersion = fullVersion   // default; overridden at execution time by release tasks
            description    = "Auto-organize scanned PDFs using OCR"
            copyright      = "© 2025 George Ramsis. All rights reserved."
            vendor         = "George Ramsis"
//            appResourcesRootDir.set(project.layout.projectDirectory.dir("extra-resources"))
            windows {
                menu     = true
                iconFile.set(project.rootProject.file("./composeApp/src/jvmMain/composeResources/drawable/Icon.ico"))
                shortcut = true
            }
            buildTypes.release.proguard {
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RELEASE TASKS
// ─────────────────────────────────────────────────────────────────────────────
//
//   ./gradlew release        → bumps BUILD counter  (1.0.0+1 → 1.0.0+2)
//   ./gradlew releasePatch   → bumps PATCH version  (1.0.0   → 1.0.1  )
//   ./gradlew releaseMinor   → bumps MINOR version  (1.0.x   → 1.1.0  )
// ─────────────────────────────────────────────────────────────────────────────

// FIX 1 — Config cache: bumpVersion receives the File directly so no script
//          object is captured inside the task closure.
// FIX 2 — Installer version: after bumping we immediately update packageVersion
//          on the nativeDistributions object (it is mutable at execution time),
//          so the packager picks up the NEW version, not the config-time value.

fun bumpVersion(
    propsFile  : File,
    bumpMajor  : Boolean = false,
    bumpMinor  : Boolean = false,
    bumpPatch  : Boolean = false
): String {
    val props = Properties().apply { propsFile.inputStream().use { load(it) } }
    var major = props["VERSION_MAJOR"].toString().trim().toInt()
    var minor = props["VERSION_MINOR"].toString().trim().toInt()
    var patch = props["VERSION_PATCH"].toString().trim().toInt()
    val build = props["VERSION_BUILD"].toString().trim().toInt() + 1
    when {
        bumpMajor -> { major++; minor = 0; patch = 0 }
        bumpMinor -> { minor++; patch = 0 }
        bumpPatch -> { patch++ }
    }
    props["VERSION_MAJOR"] = major.toString()
    props["VERSION_MINOR"] = minor.toString()
    props["VERSION_PATCH"] = patch.toString()
    props["VERSION_BUILD"] = build.toString()
    propsFile.outputStream().use { props.store(it, "Auto-managed by Gradle — do not edit manually") }
    val newVersion = "$major.$minor.$patch"
    println("▶  DocEngine version → $newVersion+$build")
    return newVersion
}

// Inject updated packageVersion into the packaging tasks at execution time.
// This runs AFTER bumpVersion() writes the new number, so the installer
// gets the correct version string.
fun applyVersionToPackager(newVersion: String) {
    compose.desktop.application.nativeDistributions.packageVersion = newVersion
}

tasks.register("release") {
    group       = "distribution"
    description = "Bump build counter and package a release (x.y.z+N)"
    val propsFile = layout.projectDirectory.file("../version.properties").asFile
    doFirst {
        val v = bumpVersion(propsFile)
        applyVersionToPackager(v)
    }
    finalizedBy("runReleaseDistributable")
}

tasks.register("releasePatch") {
    group       = "distribution"
    description = "Bump PATCH version and package a release (x.y.Z)"
    val propsFile = layout.projectDirectory.file("../version.properties").asFile
    doFirst {
        val v = bumpVersion(propsFile, bumpPatch = true)
        applyVersionToPackager(v)
    }
    finalizedBy("runReleaseDistributable")
}

tasks.register("releaseMinor") {
    group       = "distribution"
    description = "Bump MINOR version and package a release (x.Y.0)"
    val propsFile = layout.projectDirectory.file("../version.properties").asFile
    doFirst {
        val v = bumpVersion(propsFile, bumpMinor = true)
        applyVersionToPackager(v)
    }
    finalizedBy("runReleaseDistributable")
}