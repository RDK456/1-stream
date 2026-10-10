import com.codingfeline.buildkonfig.compiler.FieldSpec
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("maven-publish") // Gradle core plugin
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.kotlin.serialization)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

kotlin {
    version = "1.0.1"

    applyDefaultHierarchyTemplate()

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
            implementation(libs.annotation) // Annotations
            implementation(libs.jackson.module.kotlin) // JSON Parser
            implementation(libs.jsoup) // HTML Parser
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json) // JSON Parser
            implementation(libs.ksoup) // HTML Parser
            implementation(libs.ktor.http)
            implementation(libs.nicehttp) // HTTP Library
            implementation(libs.rhino) // Run JavaScript
            implementation(libs.bundles.cryptography) // Cryptography
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlin.reflect)
                implementation(libs.newpipeextractor)
            }
        }

        jvmMain { dependsOn(jvmCommonMain) }
    }
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(javaTarget)
    }
}

buildkonfig {
    packageName = "com.lagradost.api"
    exposeObjectWithName = "BuildConfig"

    defaultConfigs {
        val localProperties = Properties().apply {
            rootDir.resolve("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
        }
        buildConfigField(
            FieldSpec.Type.STRING,
            "MDL_API_KEY",
            (System.getenv("MDL_API_KEY") ?: localProperties["mdl.key"] ?: "").toString()
        )

        buildConfigField(
            FieldSpec.Type.STRING,
            "TRAKT_CLIENT_ID", (System.getenv("TRAKT_CLIENT_ID") ?: localProperties["trakt.id"] ?: "").toString()
        )
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            groupId = "com.lagradost.api"
        }
    }
}
