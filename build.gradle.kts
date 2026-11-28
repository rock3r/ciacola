import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.process.CommandLineArgumentProvider
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
    dokka(project(":syntax-highlight"))
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

class CiacolaWatchdogJvmArgProvider(
    @get:Internal val token: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dciacola.watchdogToken=$token")
}

/**
 * Dumps stacks and kills hung test worker JVMs matching [marker].
 * Ciacola's test tasks run with default `maxParallelForks = 1` (single worker JVM per token).
 */
fun dumpAndKillWorkerProcesses(marker: String, toolchainHome: File?) {
    val selfPid = ProcessHandle.current().pid()
    val handles = LinkedHashMap<Long, ProcessHandle>()
    runCatching {
        ProcessHandle.allProcesses().forEach { ph ->
            if (ph.pid() == selfPid || !ph.isAlive) return@forEach
            val info = ph.info()
            val cmdLineMatches = info.commandLine().map { it.contains(marker) }.orElse(false)
            val argsMatch = info.arguments().map { args -> args.any { it.contains(marker) } }.orElse(false)
            if (cmdLineMatches || argsMatch) {
                handles[ph.pid()] = ph
            }
        }
    }
    if (handles.isEmpty()) {
        runCatching {
            val psOut = File.createTempFile("ciacola-watchdog-ps-", ".txt")
            try {
                val psProc = ProcessBuilder("ps", "-axo", "pid=,command=")
                    .redirectErrorStream(true)
                    .redirectOutput(psOut)
                    .start()
                if (psProc.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                    psOut.forEachLine { line ->
                        if (line.contains(marker)) {
                            val pid = line.trim().substringBefore(' ').trim().toLongOrNull()
                            if (pid != null && pid != selfPid) {
                                ProcessHandle.of(pid).ifPresent { ph ->
                                    if (ph.isAlive) handles[pid] = ph
                                }
                            }
                        }
                    }
                } else {
                    psProc.destroyForcibly()
                }
            } finally {
                psOut.delete()
            }
        }
    }

    val jstackBin = toolchainHome?.resolve("bin/jstack")?.takeIf { it.canExecute() }?.absolutePath
        ?: File(System.getProperty("java.home", ""), "bin/jstack").takeIf { it.canExecute() }?.absolutePath
        ?: "jstack"

    for ((pid, ph) in handles) {
        runCatching {
            val stackFile = File.createTempFile("ciacola-watchdog-jstack-$pid-", ".txt")
            try {
                val jstackProc = ProcessBuilder(jstackBin, pid.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(stackFile)
                    .start()
                if (!jstackProc.waitFor(5, TimeUnit.SECONDS)) {
                    jstackProc.destroyForcibly()
                }
                val dump = stackFile.takeIf { it.exists() }?.readText().orEmpty().trim()
                if (dump.isNotEmpty()) {
                    System.err.println("[TEST-TIMEOUT] jstack for worker PID $pid:\n$dump")
                }
            } finally {
                stackFile.delete()
            }
        }
        runCatching { ph.destroyForcibly() }
    }
}

// Compose 1.13 pins Skiko 0.156.1, whose Linux OpenGLOffscreenContext fails on Mesa (glXCreateContext
// with a visual-less pbuffer config). Skiko 0.157.0 switched to glXCreateNewContext.
val skikoVersion = libs.versions.skiko.get()

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.skiko") {
                useVersion(skikoVersion)
                because("Skiko 0.157.0 fixes OpenGLOffscreenContext creation on Mesa")
            }
        }
    }

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
                jvmArgs("--enable-native-access=ALL-UNNAMED")
            }
            tasks.withType(JavaExec::class.java).configureEach {
                jvmArgs("--enable-native-access=ALL-UNNAMED")
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
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        if (!sckRecording) {
            // Prevent macOS AWT/Skiko test JVMs from registering a Dock icon or stealing focus.
            jvmArgs("-Dapple.awt.UIElement=true")
            systemProperty("apple.awt.UIElement", "true")
        }

        val isManualVideoExport = providers.systemProperty("ciacola.videoExportJob").isPresent ||
            providers.environmentVariable("CIACOLA_VIDEO_EXPORT_JOB").isPresent
        val rawTestTimeout = providers.gradleProperty("ciacola.testTimeoutSeconds")
            .orElse(providers.systemProperty("ciacola.testTimeoutSeconds"))
            .orNull
        val rawTaskTimeout = providers.gradleProperty("ciacola.taskTimeoutMinutes")
            .orElse(providers.systemProperty("ciacola.taskTimeoutMinutes"))
            .orNull
        val parsedTestTimeout = rawTestTimeout?.let { raw ->
            raw.trim().toLongOrNull()
                ?: throw GradleException("Invalid ciacola.testTimeoutSeconds='$raw': expected an integer >= 0")
        }
        val parsedTaskTimeout = rawTaskTimeout?.let { raw ->
            raw.trim().toLongOrNull()
                ?: throw GradleException("Invalid ciacola.taskTimeoutMinutes='$raw': expected an integer >= 0")
        }
        val hasExplicitTestTimeout = parsedTestTimeout != null
        if (!sckRecording && !isManualVideoExport && (isDeckModule || hasExplicitTestTimeout)) {
            val defaultTimeoutSeconds = if (name == "spectreTest") 120L else 60L
            val timeoutSeconds = parsedTestTimeout ?: defaultTimeoutSeconds
            val defaultTaskTimeoutMinutes = if (timeoutSeconds <= 0L) {
                0L
            } else if (name == "spectreTest") {
                maxOf(12L, (timeoutSeconds * 12L) / 60L)
            } else {
                maxOf(10L, (timeoutSeconds * 10L) / 60L)
            }
            val taskTimeoutMinutes = parsedTaskTimeout ?: defaultTaskTimeoutMinutes
            if (taskTimeoutMinutes > 0L) {
                timeout.set(
                    provider {
                        if (debug || debugOptions.enabled.get()) null else Duration.ofMinutes(taskTimeoutMinutes)
                    },
                )
            }
            if (timeoutSeconds > 0L) {
                val watchdogToken = UUID.randomUUID().toString()
                val watchdogMarker = "-Dciacola.watchdogToken=$watchdogToken"
                jvmArgumentProviders.add(CiacolaWatchdogJvmArgProvider(watchdogToken))
                val taskPath = path
                val launcherProvider = javaLauncher

                addTestListener(object : TestListener {
                    @Volatile
                    private var scheduler: ScheduledExecutorService? = null
                    private val activeTests = ConcurrentHashMap<TestDescriptor, ScheduledFuture<*>>()

                    override fun beforeSuite(suite: TestDescriptor) {
                        if (suite.parent == null) {
                            scheduler?.shutdownNow()
                            activeTests.clear()
                            scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                                Executors.defaultThreadFactory().newThread(runnable).apply {
                                    isDaemon = true
                                    name = "ciacola-test-watchdog-$taskPath"
                                }
                            }
                        }
                    }

                    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                        if (suite.parent == null) {
                            activeTests.values.forEach { it.cancel(false) }
                            activeTests.clear()
                            scheduler?.shutdownNow()
                            scheduler = null
                        }
                    }

                    override fun beforeTest(testDescriptor: TestDescriptor) {
                        if (testDescriptor.isComposite || debug || debugOptions.enabled.get()) return
                        val exec = scheduler ?: return
                        val future = exec.schedule({
                            activeTests.remove(testDescriptor)
                            val fqName = "${testDescriptor.className ?: "<unknown>"}.${testDescriptor.name}"
                            System.err.println(
                                "[TEST-TIMEOUT] $fqName exceeded ${timeoutSeconds}s — dumping stack & killing hung worker"
                            )
                            dumpAndKillWorkerProcesses(
                                marker = watchdogMarker,
                                toolchainHome = launcherProvider.orNull?.metadata?.installationPath?.asFile,
                            )
                        }, timeoutSeconds, TimeUnit.SECONDS)
                        activeTests.put(testDescriptor, future)?.cancel(false)
                    }

                    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                        activeTests.remove(testDescriptor)?.cancel(false)
                    }
                })
            }
        }
    }

    plugins.withId("com.vanniktech.maven.publish") {
        apply(plugin = "org.jetbrains.dokka")
        if (project.path != ":syntax-highlight") {
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
    }

    plugins.withId("org.jetbrains.dokka") {
        configure<DokkaExtension> {
            dokkaSourceSets.configureEach {
                documentedVisibilities.add(VisibilityModifier.Public)
                if (project.path != ":syntax-highlight") {
                    sourceLink {
                        localDirectory = rootProject.projectDir
                        remoteUrl("https://github.com/bnorm/storyboard/tree/main/")
                        remoteLineSuffix = "#L"
                    }
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
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.(effects|inspector\.(element|design|lint|sync))\b"""),
                Regex("""\b(DeckTextRenderer|AnimatedMeshBackdrop|PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|DocumentTimelineController|DocumentTimelineWindow|DocumentElementInspectorController|DocumentThemeInspectorController|DocumentDeckLintController)\b"""),
            ),
            "deck-effects" to listOf(
                Regex("""\b(PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|DocumentTimelineController|DocumentTimelineWindow|TimelineViewportState|DocumentElementInspectorController|DocumentThemeInspectorController|DocumentDeckLintController)\b"""),
            ),
            "deck-presenter" to listOf(
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.(effects|inspector\.(element|design|lint|sync))\b"""),
                Regex("""\b(DeckTextRenderer|AnimatedMeshBackdrop|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|DocumentTimelineController|DocumentTimelineWindow|TimelineViewportState)\b"""),
            ),
            "deck-review" to listOf(
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.(effects|inspector\.(element|design|lint|sync))\b"""),
                Regex("""\b(DeckTextRenderer|AnimatedMeshBackdrop|PresenterWindow|PresenterController|DocumentTimelineController|DocumentTimelineWindow|TimelineViewportState)\b"""),
            ),
            "deck-timeline" to listOf(
                Regex("""\bimport\s+dev\.sebastiano\.ciacola\.(effects|inspector\.(element|design|lint|sync))\b"""),
                Regex("""\b(DeckTextRenderer|AnimatedMeshBackdrop|PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator)\b"""),
            ),
            "deck-inspector" to listOf(
                Regex("""\b(PresenterWindow|PresenterController|SlideReviewWindow|ReviewCommentStore|ReviewStageCoordinator|DocumentTimelineController|DocumentTimelineWindow)\b"""),
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
        val scannableExtensions = setOf("kt", "kts", "md", "toml", "properties", "txt", "g4", "py")
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
