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
