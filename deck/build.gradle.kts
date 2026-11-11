import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.compose)
}

group = "dev.sebastiano.ciacola"
version = "0.1-SNAPSHOT"

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":storyboard"))
                implementation(project(":storyboard-easel"))
                implementation(project(":storyboard-layout"))
                implementation(project(":storyboard-text"))
                implementation(project(":storyboard-warp"))
                implementation(compose.material)
                implementation(libs.compose.materialIcons)
                implementation(compose.components.resources)
                implementation(libs.compose.hotReload.runtimeApi)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jewel.int.ui.standalone)
                implementation(libs.intellij.icons)
            }
        }
        getByName("desktopTest") {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
    }
}

compose.resources {
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = provider {
            rootProject.layout.projectDirectory.dir("examples/shared/src/commonMain/composeResources")
        },
    )
}

val jbr25 = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.JETBRAINS
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(jbr25)
}

compose.desktop {
    application {
        mainClass = "dev.sebastiano.ciacola.MainKt"
        javaHome = jbr25.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Ciacola"
            packageVersion = "1.0.0"
        }
    }
}
