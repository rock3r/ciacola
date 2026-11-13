plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":storyboard"))
            api(project(":storyboard-easel"))
            api(project(":storyboard-text"))
            api(compose.material)
            api(compose.components.resources)
            api(libs.kotlinx.serialization.json)
        }
        getByName("desktopMain").dependencies {
            api(compose.desktop.currentOs)
            api(libs.jewel.int.ui.standalone)
            api(libs.intellij.icons)
        }
        getByName("desktopTest").dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "ciacola.deck.generated.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = provider {
            rootProject.layout.projectDirectory.dir("examples/shared/src/commonMain/composeResources")
        },
    )
}
