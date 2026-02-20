import org.gradle.kotlin.dsl.libs
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    id("com.github.gmazzo.buildconfig") version "6.0.7"
}

kotlin {
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
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
            implementation(libs.sqlite.jdbc)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.jnativehook)
            val exposedVersion = libs.versions.exposed.get()
            implementation(libs.exposed.jdbc)
            implementation(libs.exposed.core)
            implementation(libs.exposed.dao)

            implementation(libs.kotlin.logging)
            implementation(libs.slf4j.simple)
        }
    }
}


compose.desktop {
    application {
        mainClass = "org.benesv.history.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "History"
            packageVersion = "1.0.0"

            modules("java.sql")

            macOS {
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSSystemExtensionsWhitelist</key>
                        <array>
                            <string>com.apple.security.system-extension</string>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}

buildConfig {
    packageName("org.benesv.history")
    className("BuildConfig")

    val developFlag = providers
        .gradleProperty("develop")
        .map { it.toBoolean() }
        .orElse(true)

    buildConfigField("DEVELOP", developFlag.get())
}
