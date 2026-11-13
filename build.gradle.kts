import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.File
import java.security.MessageDigest
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish) apply false
}

dokka {
    dokkaPublications.html {
        includes.from(project.layout.projectDirectory.file("README.md"))
    }
}

dependencies {
    dokka(project(":storyboard"))
    dokka(project(":storyboard-easel"))
    dokka(project(":storyboard-text"))
    dokka(project(":storyboard-warp"))
}

val ciacolaDeckModules = setOf(
    ":deck-core",
    ":deck-effects",
    ":deck-presenter",
    ":deck-review",
    ":deck-timeline",
    ":deck-inspector",
    ":deck",
)

val junitJupiterApiLib = libs.junit.jupiter.api

allprojects {
    val isDeckModule = project.path in ciacolaDeckModules
    group = if (isDeckModule) "dev.sebastiano.ciacola" else "dev.bnorm.storyboard"
    version = if (isDeckModule) "0.1-SNAPSHOT" else "0.1.0-SNAPSHOT"

    val javaVersion = if (isDeckModule) JavaVersion.VERSION_25 else JavaVersion.VERSION_11
    val sckRecording = providers.gradleProperty("ciacola.sckRecording")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            if (isDeckModule) {
                jvmToolchain {
                    languageVersion.set(JavaLanguageVersion.of(25))
                    vendor.set(JvmVendorSpec.JETBRAINS)
                }
                jvm("desktop") {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_25)
                        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
                        extraWarnings.set(true)
                    }
                }
                sourceSets.getByName("commonTest").dependencies {
                    implementation(kotlin("test"))
                }
                sourceSets.getByName("desktopTest").dependencies {
                    runtimeOnly(junitJupiterApiLib)
                }
            }

            sourceSets.all {
                languageSettings {
                    optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                    optIn("kotlin.time.ExperimentalTime")
                    optIn("androidx.compose.animation.core.ExperimentalTransitionApi")
                    optIn("androidx.compose.animation.ExperimentalAnimationApi")
                    optIn("androidx.compose.animation.ExperimentalSharedTransitionApi")
                }
            }

            targets.withType(KotlinJvmTarget::class.java) {
                compilerOptions {
                    jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
                    jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
                    if (isDeckModule) {
                        extraWarnings.set(true)
                    }
                }
            }
        }

        if (isDeckModule) {
            val jbr25Launcher = extensions.getByType(JavaToolchainService::class.java).launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(JvmVendorSpec.JETBRAINS)
            }
            tasks.withType(Test::class.java).configureEach {
                javaLauncher.set(jbr25Launcher)
            }
            afterEvaluate {
                tasks.findByName("check")?.dependsOn(rootProject.tasks.named("ciacolaStaticCheck"))
            }
        }
    }

    tasks.withType(JavaCompile::class.java).configureEach {
        sourceCompatibility = javaVersion.toString()
        targetCompatibility = javaVersion.toString()
    }

    tasks.withType(Test::class.java).configureEach {
        if (!sckRecording) {
            // Prevent macOS AWT/Skiko test JVMs from registering a Dock icon or stealing focus.
            jvmArgs("-Dapple.awt.UIElement=true")
            systemProperty("apple.awt.UIElement", "true")
        }
    }

    plugins.withId("com.vanniktech.maven.publish") {
        apply(plugin = "org.jetbrains.dokka")
        configure<MavenPublishBaseExtension> {
            signAllPublications()
            pom {
                description = "Compose Multiplatform library for building presentations."
                name = project.name
                url = "https://github.com/bnorm/storyboard/"
                licenses {
                    license {
                        name = "The Apache Software License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "bnorm"
                        name = "Brian Norman"
                    }
                }
                scm {
                    url = "https://github.com/bnorm/storyboard/"
                    connection = "scm:git:https://github.com/bnorm/storyboard.git"
                    developerConnection = "scm:git:ssh://git@github.com/bnorm/storyboard.git"
                }
            }
        }
    }

    plugins.withId("org.jetbrains.dokka") {
        configure<DokkaExtension> {
            dokkaSourceSets.configureEach {
                documentedVisibilities.add(VisibilityModifier.Public)
                sourceLink {
                    localDirectory = rootProject.projectDir
                    remoteUrl("https://github.com/bnorm/storyboard/tree/main/")
                    remoteLineSuffix = "#L"
                }
            }
        }
    }
}

val ciacolaStaticCheck = tasks.register("ciacolaStaticCheck") {
    group = "verification"
    description = "Enforces Ciacola's module DAG, file size caps, structured concurrency, and confidentiality checks."
    val rootDir = project.layout.projectDirectory.asFile

    doLast {
        val violations = mutableListOf<String>()

        val allowedDeckDeps = mapOf(
            "deck-core" to emptySet(),
            "deck-effects" to setOf("deck-core"),
            "deck-presenter" to setOf("deck-core"),
            "deck-review" to setOf("deck-core"),
            "deck-timeline" to setOf("deck-core"),
            "deck-inspector" to setOf("deck-core", "deck-effects"),
            "deck" to setOf(
                "deck-core",
                "deck-effects",
                "deck-presenter",
                "deck-review",
                "deck-timeline",
                "deck-inspector",
            ),
        )

        val projectDepRegex = Regex("""project\(":(deck[^"]*)"\)""")
        for ((moduleName, allowed) in allowedDeckDeps) {
            val buildFile = File(rootDir, "$moduleName/build.gradle.kts")
            if (!buildFile.exists()) {
                violations += "Missing module build file: $moduleName/build.gradle.kts"
                continue
            }
            val text = buildFile.readText()
            val declaredDeps = projectDepRegex.findAll(text).map { it.groupValues[1] }.toSet()
            val unexpected = declaredDeps - allowed
            if (unexpected.isNotEmpty()) {
                violations += "Forbidden module dependency in :$moduleName -> $unexpected (allowed: $allowed)"
            }
        }

        val forbiddenSymbolsByModule = mapOf(
            "deck-core" to listOf(
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.effects\b"""),
                Regex("""\b(createFoundationDeck|FoundationDeck|DeckTextRenderer|DeckMagicText|AnimatedMeshBackdrop|PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|TimelineStudioController|TimelineOverridesStore|TimelineWindow)\b"""),
            ),
            "deck-effects" to listOf(
                Regex("""\b(createFoundationDeck|FoundationDeck|PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|TimelineStudioController|TimelineOverridesStore|TimelineViewportState|TimelineWindow)\b"""),
            ),
            "deck-presenter" to listOf(
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.(effects|inspector)\b"""),
                Regex("""\b(createFoundationDeck|FoundationDeck|DeckTextRenderer|DeckMagicText|AnimatedMeshBackdrop|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|TimelineStudioController|TimelineOverridesStore|TimelineViewportState|TimelineWindow)\b"""),
            ),
            "deck-review" to listOf(
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.(effects|inspector)\b"""),
                Regex("""\b(createFoundationDeck|FoundationDeck|DeckTextRenderer|DeckMagicText|AnimatedMeshBackdrop|PresenterWindow|PresenterController|TimelineStudioController|TimelineOverridesStore|TimelineViewportState|TimelineWindow)\b"""),
            ),
            "deck-timeline" to listOf(
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.(effects|inspector)\b"""),
                Regex("""\b(createFoundationDeck|FoundationDeck|DeckTextRenderer|DeckMagicText|AnimatedMeshBackdrop|PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator)\b"""),
            ),
            "deck-inspector" to listOf(
                Regex("""\b(createFoundationDeck|FoundationDeck|PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|TimelineStudioController|TimelineOverridesStore|TimelineWindow)\b"""),
            ),
        )

        val maxMainFileLines = 1_100
        val globalScopeRegex = Regex("""\bGlobalScope\b""")
        val rawThreadRegex = Regex("""(?:java\.lang\.)?\bThread\s*\(""")

        for (moduleName in allowedDeckDeps.keys) {
            for (sourceSet in listOf("commonMain", "desktopMain")) {
                val srcDir = File(rootDir, "$moduleName/src/$sourceSet")
                if (!srcDir.exists()) continue
                srcDir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val rel = file.relativeTo(rootDir).path
                        val lines = file.readLines()
                        if (lines.size > maxMainFileLines) {
                            violations += "God-file limit exceeded ($rel: ${lines.size} lines > $maxMainFileLines)"
                        }
                        val content = lines.joinToString("\n")
                        if (globalScopeRegex.containsMatchIn(content)) {
                            violations += "Unstructured concurrency (GlobalScope) in $rel"
                        }
                        if (rawThreadRegex.containsMatchIn(content)) {
                            violations += "Unstructured concurrency (raw Thread(...)) in $rel"
                        }
                        forbiddenSymbolsByModule[moduleName]?.forEach { pattern ->
                            if (pattern.containsMatchIn(content)) {
                                violations += "Forbidden cross-module/reverse-DAG reference in $rel matching ${pattern.pattern}"
                            }
                        }
                    }
            }
        }

        // SHA-256 digests of forbidden private tokens (checked via 1-gram and 2-gram lowercase scan).
        val forbiddenDigests = setOf(
            "a31357a23acceeb8cac795246b10b11980c9170e23f2d26c5f1e1a14e47e2931",
            "3becab2a2af5e603f4abb224b30c83a83f71e4088f12f2d7631c22746dc83953",
            "242d3e7855bee96743e4ce66a296f9c57c2e766f24594d6df7174b32c80a4445",
            "46b6e8616c31418379b92afdc9a8940f63658e87c421bdaeb22f1d1b7771a1ee",
            "0cc9b6a7c723f3a2678955e3c93018992aafe0dca79f0a9eea4896d798e37b01",
            "f410c234753d3b74c783297f15e66e2ca74417395d99273a620e9fa193b532cb",
        )
        val sha256 = MessageDigest.getInstance("SHA-256")
        fun digestOf(token: String): String =
            sha256.digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        val tokenRegex = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
        val scannableExtensions = setOf("kt", "kts", "md", "toml", "properties", "txt")
        rootDir.walkTopDown()
            .onEnter { dir ->
                val name = dir.name
                name != ".git" && name != "build" && name != ".gradle" && name != ".gradle-agent-home" && name != ".storyboard"
            }
            .filter { it.isFile && it.extension.lowercase() in scannableExtensions }
            .forEach { file ->
                val tokens = tokenRegex.findAll(file.readText().lowercase()).map { it.value }.toList()
                for (i in tokens.indices) {
                    if (digestOf(tokens[i]) in forbiddenDigests) {
                        violations += "Confidentiality token leak in ${file.relativeTo(rootDir).path}"
                        break
                    }
                    if (i + 1 < tokens.size && digestOf("${tokens[i]} ${tokens[i + 1]}") in forbiddenDigests) {
                        violations += "Confidentiality phrase leak in ${file.relativeTo(rootDir).path}"
                        break
                    }
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "ciacolaStaticCheck failed with ${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { " - $it" }
            )
        }
    }
}

tasks.register("ciacolaStaticAnalysis") {
    group = "verification"
    description = "Alias for ciacolaStaticCheck."
    dependsOn(ciacolaStaticCheck)
}

tasks.register<Sync>("site") {
    into(project.layout.buildDirectory.dir("_site"))

    into("docs/api/latest") {
        from(tasks.named("dokkaGeneratePublicationHtml"))
    }

    into("examples") {
        into("basic") {
            from(project(":examples:basic").tasks.named("wasmJsBrowserDistribution"))
        }
        into("diagram") {
            from(project(":examples:diagram").tasks.named("wasmJsBrowserDistribution"))
        }
        into("interactive") {
            from(project(":examples:interactive").tasks.named("wasmJsBrowserDistribution"))
        }
    }
}
