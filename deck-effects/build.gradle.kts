plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":deck-core"))
            api(project(":storyboard"))
            api(project(":storyboard-easel"))
            api(project(":storyboard-layout"))
            api(project(":storyboard-text"))
            api(project(":storyboard-warp"))
            implementation(compose.material)
        }
        getByName("desktopTest").dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.pdfbox)
        }
    }
}

tasks.named<Test>("desktopTest") {
    systemProperty(
        "ciacola.fonts.dir",
        rootProject.file("deck/src/desktopMain/resources/fonts").absolutePath,
    )
}

// Skiko 0.156.1 (pinned by Compose) cannot create its offscreen OpenGL context on Mesa, because
// glXCreateContext gets a pbuffer config with no X visual; 0.157.0 switched to glXCreateNewContext.
// Tests only: the GPU benchmark needs that offscreen context on Linux; the app keeps Compose's Skiko.
val testSkikoVersion = "0.157.0"
configurations
    .matching { it.name == "desktopTestCompileClasspath" || it.name == "desktopTestRuntimeClasspath" }
    .configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.skiko") {
                useVersion(testSkikoVersion)
                because("Skiko 0.157.0 fixes OpenGLOffscreenContext creation on Mesa")
            }
        }
    }
