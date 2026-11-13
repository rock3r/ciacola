import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.compose)
}

group = "dev.sebastiano.ciacola"
version = "0.1-SNAPSHOT"

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

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
                implementation(libs.spectre.core)
                implementation(libs.spectre.testing)
                implementation(libs.spectre.recording)
                runtimeOnly(libs.junit.jupiter.api)
                runtimeOnly(libs.spectre.recording.macos)
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

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "25"
    targetCompatibility = "25"
}

tasks.named<Test>("desktopTest") {
    javaLauncher.set(jbr25)
    exclude("**/*SpectreTest.class")
    jvmArgs("-Dapple.awt.UIElement=true")
    systemProperty("apple.awt.UIElement", "true")
}

val sckRecording = providers.gradleProperty("ciacola.sckRecording")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
val desktopTestTask = tasks.named<Test>("desktopTest")
tasks.register<Test>("spectreTest") {
    description = "Runs real-window Spectre 0.9.0 E2E tests on JetBrains Runtime 25."
    group = "verification"
    javaLauncher.set(jbr25)
    testClassesDirs = desktopTestTask.get().testClassesDirs
    classpath = desktopTestTask.get().classpath
    include("**/*SpectreTest.class")
    systemProperty("java.awt.headless", "false")
    if (!sckRecording) {
        jvmArgs("-Dapple.awt.UIElement=true")
        systemProperty("apple.awt.UIElement", "true")
    } else {
        systemProperty("apple.awt.UIElement", "false")
    }
    listOf(
        "CIACOLA_QA_WORKER",
        "CIACOLA_QA_ROOT",
        "CIACOLA_SPECTRE_CAPTURE_DIR",
        "FFMPEG_BIN",
    ).forEach { key ->
        providers.environmentVariable(key).orNull?.let { environment(key, it) }
    }
    listOf("ciacola.qa.worker", "ciacola.qa.root").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    outputs.upToDateWhen { false }
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

afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        workingDir = rootProject.projectDir
    }
}
