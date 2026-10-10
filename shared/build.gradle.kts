plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }

        commonMain.dependencies {
            implementation(libs.coil.network.ktor3)
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(project(":library"))
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.lagradost.cloudstream4.generated.resources"
    generateResClass = auto
}
