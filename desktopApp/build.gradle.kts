import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {}
    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }
        jvmMain.dependencies {
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.vlcj)
            implementation("de.femtopedia.dex2jar:dex-translator:2.4.38") // converts Android .cs3 plugins (dex) to jars
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            implementation(project(":shared"))
            implementation(project(":library"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val appVersion = providers.gradleProperty("app.version").getOrElse("1.0.0")
// Installed builds look here for updates (a small patch when one exists), so one click is enough
val updateUrl = providers.gradleProperty("update.url").getOrElse("https://github.com/RDK456/1-stream/releases/latest/download/update.json")

tasks.withType<Test> {
    maxHeapSize = "2g"
    systemProperty("onestream.version", appVersion)
    dependsOn(":plugins:blender-demo:jar")
    systemProperty("blender.jar", project(":plugins:blender-demo").layout.buildDirectory.file("libs/blender-demo.jar").get().asFile.absolutePath)
    systemProperty("onestream.vlc", project.layout.projectDirectory.dir("appResources/windows-x64/vlc").asFile.absolutePath)
    systemProperty("shots.dir", rootProject.layout.projectDirectory.dir("build/ui-shots").asFile.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote")
}

// java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote")
}

compose.desktop {
    application {
        mainClass = "app.onestream.MainKt"
        // plugin jars converted from Android dex are not verifiable (no stack map frames, stub classes); plugins are user-approved and pinned
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote", "-Donestream.version=$appVersion")
        if (updateUrl.isNotBlank()) jvmArgs("-Donestream.update.url=$updateUrl")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "1-stream"
            packageVersion = appVersion
            description = "1-stream desktop media player"
            vendor = "1-stream"
            // Bundles VLC: appResources/windows-x64/vlc (see scripts/fetch-vlc.ps1)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            modules("java.net.http", "jdk.unsupported", "java.naming", "java.sql", "jdk.crypto.ec", "jdk.zipfs")

            val iconsRoot = project.file("src/desktop-icons")
            windows {
                iconFile.set(iconsRoot.resolve("icon-windows.ico"))
                menuGroup = "1-stream"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Stable id so newer MSIs upgrade older installs
                upgradeUuid = "6f1d3a52-8c0e-4b7a-9a41-2d5e7c9b1f34"
            }
        }
    }
}
