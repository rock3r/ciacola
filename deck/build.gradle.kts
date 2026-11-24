import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":deck-core"))
            implementation(project(":deck-effects"))
            implementation(project(":deck-presenter"))
            implementation(project(":deck-review"))
            implementation(project(":deck-timeline"))
            implementation(project(":deck-inspector"))
            implementation(project(":storyboard"))
            implementation(project(":storyboard-easel"))
            implementation(project(":storyboard-layout"))
            implementation(project(":storyboard-text"))
            implementation(project(":storyboard-warp"))
            implementation(compose.material)
            implementation(libs.compose.materialIcons)
            implementation(libs.compose.hotReload.runtimeApi)
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("desktopTest").dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.spectre.core)
            implementation(libs.spectre.testing)
            implementation(libs.spectre.recording)
            runtimeOnly(libs.spectre.recording.macos)
        }
    }
}

val jbr25 = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.JETBRAINS
}

val requestedDeckProp = providers.systemProperty("ciacola.deck")
    .orElse(providers.gradleProperty("ciacola.deck"))

tasks.named<Test>("desktopTest") {
    maxHeapSize = "3g"
    exclude("**/*SpectreTest.class")
    listOf("CIACOLA_RENDER_WS3_PNGS", "CIACOLA_VIDEO_EXPORT_JOB", "CIACOLA_VIDEO_EXPORT_OUT_DIR", "CIACOLA_VIDEO_EXPORT_SPANS_DIR").forEach { key ->
        providers.environmentVariable(key).orNull?.let { environment(key, it) }
    }
    listOf(
        "ciacola.renderWs3Pngs",
        "ciacola.spotRenderDir",
        "ciacola.deck",
        "ciacola.foundationPreview.dir",
        "ciacola.videoExportJob",
        "ciacola.videoExportOutDir",
        "ciacola.videoExportSpansDir",
    ).forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    if (providers.systemProperty("ciacola.videoExportJob").isPresent ||
        providers.environmentVariable("CIACOLA_VIDEO_EXPORT_JOB").isPresent
    ) {
        jvmArgs("-Dapple.awt.UIElement=true")
        systemProperty("apple.awt.UIElement", "true")
    }
}

val sckRecording = providers.gradleProperty("ciacola.sckRecording")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
val desktopTestTask = tasks.named<Test>("desktopTest")
tasks.register<Test>("spectreTest") {
    description = "Runs real-window Spectre 0.9.0 E2E tests on JetBrains Runtime 25."
    group = "verification"
    maxHeapSize = "3g"
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
        "CIACOLA_DOGFOOD_OUT_DIR",
        "FFMPEG_BIN",
    ).forEach { key ->
        providers.environmentVariable(key).orNull?.let { environment(key, it) }
    }
    listOf("ciacola.qa.worker", "ciacola.qa.root", "ciacola.dogfoodOutDir", "ciacola.dogfoodCuesDir").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    requestedDeckProp.orNull?.let { systemProperty("ciacola.deck", it) }
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

val decksSourceDir = project.file("src/desktopMain/resources/decks")
val decksDirOverrideProp = providers.systemProperty("ciacola.decks.dir")
    .orElse(providers.gradleProperty("ciacola.decks.dir"))

val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")

tasks.withType<Copy>().configureEach {
    exclude("**/.*.sync/**")
}

val formatDecks = tasks.register<JavaExec>("formatDecks") {
    description = "Formats all tracked <deckId>.deck.json files in canonical DeckDocument key order."
    group = "formatting"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckCanonicalizerCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    args("--format", "--dir=${decksSourceDir.absolutePath}")
}

val verifyDecksCanonical = tasks.register<JavaExec>("verifyDecksCanonical") {
    description = "Verifies all tracked <deckId>.deck.json files decode cleanly and match CanonicalJson.print byte-for-byte."
    group = "verification"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckCanonicalizerCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    args("--verify", "--dir=${decksSourceDir.absolutePath}")
    inputs.files(fileTree(decksSourceDir) { exclude("**/.*.sync/**") })
}

val importLegacyOverrides = tasks.register<JavaExec>("importLegacyOverrides") {
    description = "Imports legacy .storyboard/ element, timeline, and design-system overrides into <deckId>.deck.json and archives originals."
    group = "migration"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckCanonicalizerCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    args(
        "--import-legacy-overrides",
        "--dir=${decksSourceDir.absolutePath}",
        "--storyboard-dir=${rootProject.file(".storyboard").absolutePath}",
    )
    requestedDeckProp.orNull?.let { args("--deck=$it") }
}

fun ciacolaArgProp(name: String): Provider<String> =
    providers.systemProperty(name).orElse(providers.gradleProperty(name))

val requestedSlidesProp = ciacolaArgProp("ciacola.slides")
val requestedBeatsProp = ciacolaArgProp("ciacola.beats")
val requestedOutProp = ciacolaArgProp("ciacola.out")
val requestedFpsProp = ciacolaArgProp("ciacola.fps")
val requestedSizeProp = ciacolaArgProp("ciacola.size")
val requestedThemeProp = ciacolaArgProp("ciacola.theme")
val requestedFailOnProp = ciacolaArgProp("ciacola.failOn")
val requestedJsonProp = ciacolaArgProp("ciacola.json")

tasks.register<JavaExec>("lintDeck") {
    description = "Lints a .deck.json deck using DocumentDeckLintEngine (use -Pciacola.deck=<id|path>)."
    group = "verification"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckAgentCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    jvmArgs("-Dapple.awt.UIElement=true")
    systemProperty("apple.awt.UIElement", "true")
    args("--lint", "--dir=${decksDirOverrideProp.getOrElse(decksSourceDir.absolutePath)}")
    requestedDeckProp.orNull?.let { args("--deck=$it") }
    requestedSlidesProp.orNull?.let { args("--slides=$it") }
    requestedThemeProp.orNull?.let { args("--theme=$it") }
    requestedFailOnProp.orNull?.let { args("--fail-on=$it") }
    if (requestedJsonProp.orNull.equals("true", ignoreCase = true)) {
        args("--json")
    }
}

tasks.register<JavaExec>("renderSlides") {
    description = "Headlessly renders deterministic PNG slide stills and manifest.json (use -Pciacola.deck=<id|path>)."
    group = "verification"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckAgentCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    jvmArgs("-Dapple.awt.UIElement=true")
    systemProperty("apple.awt.UIElement", "true")
    args("--render-slides", "--dir=${decksDirOverrideProp.getOrElse(decksSourceDir.absolutePath)}")
    requestedDeckProp.orNull?.let { args("--deck=$it") }
    requestedSlidesProp.orNull?.let { args("--slides=$it") }
    requestedBeatsProp.orNull?.let { args("--beats=$it") }
    requestedOutProp.orNull?.let { args("--out=$it") }
    requestedSizeProp.orNull?.let { args("--size=$it") }
    requestedThemeProp.orNull?.let { args("--theme=$it") }
}

tasks.register<JavaExec>("exportVideo") {
    description = "Headlessly exports a deck (or slide subset) to CFR H.264 MP4 and .spans.tsv (use -Pciacola.deck=<id|path>)."
    group = "distribution"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckAgentCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    jvmArgs("-Dapple.awt.UIElement=true")
    systemProperty("apple.awt.UIElement", "true")
    args("--export-video", "--dir=${decksDirOverrideProp.getOrElse(decksSourceDir.absolutePath)}")
    requestedDeckProp.orNull?.let { args("--deck=$it") }
    requestedSlidesProp.orNull?.let { args("--slides=$it") }
    requestedOutProp.orNull?.let { args("--out=$it") }
    requestedFpsProp.orNull?.let { args("--fps=$it") }
    requestedSizeProp.orNull?.let { args("--size=$it") }
    requestedThemeProp.orNull?.let { args("--theme=$it") }
}

val requestedTimeoutProp = ciacolaArgProp("ciacola.timeout")
val requestedStoryboardDirProp = ciacolaArgProp("ciacola.storyboardDir")

tasks.register<JavaExec>("reviewWait") {
    description =
        "Blocks until the author queues a review comment, prints it, and records it taken " +
            "(exit 3 on timeout; use -Pciacola.deck=<id> -Pciacola.timeout=30m)."
    group = "verification"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckAgentCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    jvmArgs("-Dapple.awt.UIElement=true")
    systemProperty("apple.awt.UIElement", "true")
    // Waiting for a person is not a cacheable, up-to-date-able thing.
    outputs.upToDateWhen { false }
    args("--review-wait")
    requestedDeckProp.orNull?.let { args("--deck=$it") }
    requestedTimeoutProp.orNull?.let { args("--timeout=$it") }
    requestedStoryboardDirProp.orNull?.let { args("--storyboard-dir=$it") }
    // A timeout is an ordinary outcome, not a crash: report it as one sentence the caller can act
    // on rather than letting Gradle print a process-exited-3 stack trace over it. The build still
    // fails, so `until ./gradlew :deck:reviewWait; do :; done` keeps working.
    isIgnoreExitValue = true
    doLast {
        val exit = executionResult.get().exitValue
        if (exit == 3) {
            throw GradleException("No review comment was queued before the timeout. Run this task again to keep waiting.")
        }
        if (exit != 0) {
            throw GradleException("reviewWait failed with exit code $exit.")
        }
    }
}

tasks.register<JavaExec>("reviewExport") {
    description =
        "Prints the review comments the author has queued, without blocking " +
            "(use -Pciacola.deck=<id> -Pciacola.json=true)."
    group = "verification"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckAgentCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    jvmArgs("-Dapple.awt.UIElement=true")
    systemProperty("apple.awt.UIElement", "true")
    outputs.upToDateWhen { false }
    args("--review-export")
    requestedDeckProp.orNull?.let { args("--deck=$it") }
    requestedStoryboardDirProp.orNull?.let { args("--storyboard-dir=$it") }
    if (requestedJsonProp.orNull.equals("true", ignoreCase = true)) {
        args("--json")
    }
}

val requestedCommentProp = ciacolaArgProp("ciacola.comment")
val requestedResolutionProp = ciacolaArgProp("ciacola.resolution")
val requestedReviewStatusProp = ciacolaArgProp("ciacola.reviewStatus")

tasks.register<JavaExec>("reviewResolve") {
    description =
        "Writes a verdict back onto a review comment, which the running deck picks up within " +
            "250ms (use -Pciacola.comment=<id> -Pciacola.resolution=<text>)."
    group = "verification"
    javaLauncher.set(jbr25)
    mainClass.set("dev.sebastiano.ciacola.decks.DeckAgentCli")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    jvmArgs("-Dapple.awt.UIElement=true")
    systemProperty("apple.awt.UIElement", "true")
    outputs.upToDateWhen { false }
    requestedCommentProp.orNull?.let { args("--review-resolve=$it") }
    requestedDeckProp.orNull?.let { args("--deck=$it") }
    requestedResolutionProp.orNull?.let { args("--resolution=$it") }
    requestedReviewStatusProp.orNull?.let { args("--review-status=$it") }
    requestedStoryboardDirProp.orNull?.let { args("--storyboard-dir=$it") }
}

tasks.named("check") {
    dependsOn(verifyDecksCanonical)
}

afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        if (name != "formatDecks" && name != "verifyDecksCanonical" && name != "importLegacyOverrides") {
            workingDir = rootProject.projectDir
            requestedDeckProp.orNull?.let { systemProperty("ciacola.deck", it) }
            systemProperty("ciacola.decks.dir", decksDirOverrideProp.getOrElse(decksSourceDir.absolutePath))
        }
    }
}
