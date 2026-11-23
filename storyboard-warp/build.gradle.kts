import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.maven.publish)
}

group = "dev.bnorm.storyboard"

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":syntax-highlight"))
            implementation(project(":storyboard"))
            implementation(project(":storyboard-layout"))
            implementation(project(":storyboard-text"))
            api(libs.jetbrains.annotations)
            implementation(libs.androidx.collection)
            implementation(compose.material)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
