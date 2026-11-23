import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
import java.util.zip.ZipFile
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.antlr.kotlin)
}

group = "dev.sebastiano.ciacola"
version = "0.1.0-SNAPSHOT"

val generateAntlrGrammars = tasks.register("generateAntlrGrammars") {
    group = "antlr"
    description = "Generates all ANTLR4 Kotlin lexers for :syntax-highlight."
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    js {
        nodejs()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.antlr.kotlin.runtime)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

fun registerAntlrLexer(dirName: String, packageSuffix: String = dirName) {
    val taskSuffix = dirName.replaceFirstChar { it.uppercase() }
    val pkgName = "dev.sebastiano.ciacola.syntax.antlr.$packageSuffix"
    val antlrDir = layout.projectDirectory.dir("src/commonMain/antlr/$dirName")
    val sharedAntlrDir = layout.projectDirectory.dir("src/commonMain/antlr/shared")
    val genRoot = layout.buildDirectory.dir("generatedAntlr/$dirName/kotlin")
    val outDir = genRoot.map { it.dir("dev/sebastiano/ciacola/syntax/antlr/$packageSuffix") }

    val task = tasks.register("generate${taskSuffix}GrammarSource", AntlrKotlinTask::class.java) {
        group = "antlr"
        description = "Generates ANTLR4 Kotlin lexer sources for $dirName."
        inputs.files(
            fileTree(antlrDir) { include("**/*.g4", "**/*.tokens") },
            fileTree(sharedAntlrDir) { include("**/*.g4", "**/*.tokens") },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(outDir)
        doFirst { outDir.get().asFile.deleteRecursively() }

        source = fileTree(antlrDir) {
            include("**/*.g4")
        }
        packageName = pkgName
        arguments = listOf("-lib", sharedAntlrDir.asFile.absolutePath)
        outputDirectory = outDir.get().asFile

        doLast {
            val outDirFile = outDir.get().asFile
            outDirFile.walkTopDown()
                .filter { it.isFile && (it.extension == "interp" || it.extension == "tokens") }
                .forEach { it.delete() }

            val generatedFromRegex = Regex("""^// Generated from .*""")
            val nonVisibilityModifier =
                """(?:open|abstract|final|sealed|data|enum|value|annotation|inline|const|expect|actual|suspend|tailrec|operator|infix|external|lateinit|override|companion)"""
            val topLevelPublicRegex = Regex(
                """(?m)^public\s+((?:$nonVisibilityModifier\s+)*)(class|object|interface|fun|val|var|typealias)\b""",
            )
            outDirFile.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { ktFile ->
                    val original = ktFile.readText()
                    var rewritten = generatedFromRegex.replaceFirst(original, "// Generated from $dirName by ANTLR")
                    rewritten = topLevelPublicRegex.replace(rewritten) { match ->
                        val modifiers = match.groupValues[1]
                        val kind = match.groupValues[2]
                        "internal $modifiers$kind"
                    }
                    if (rewritten != original) {
                        ktFile.writeText(rewritten)
                    }
                }
        }
    }

    generateAntlrGrammars.configure {
        dependsOn(task)
    }

    kotlin.sourceSets.named("commonMain") {
        kotlin.srcDir(task.map { genRoot.get() })
    }
}

registerAntlrLexer("kotlin")
registerAntlrLexer("xml")
registerAntlrLexer("json")
registerAntlrLexer("css")
registerAntlrLexer("javascript")
registerAntlrLexer("html")
registerAntlrLexer("rust")
registerAntlrLexer("go")
registerAntlrLexer("python")
registerAntlrLexer("toml")
registerAntlrLexer("java")
registerAntlrLexer("cpp")
registerAntlrLexer("glsl")

tasks.withType(KotlinCompilationTask::class.java).configureEach {
    dependsOn(generateAntlrGrammars)
}

tasks.matching {
    it.name.endsWith("SourcesJar", ignoreCase = true) ||
        it.name.contains("dokka", ignoreCase = true)
}.configureEach {
    dependsOn(generateAntlrGrammars)
}

val verifySyntaxHighlightSourcesJar = tasks.register("verifySyntaxHighlightSourcesJar") {
    group = "verification"
    description = "Verifies jvmSourcesJar layout, hygiene, and internal visibility of ANTLR lexers."
    val sourcesJarTask = tasks.named("jvmSourcesJar", org.gradle.jvm.tasks.Jar::class.java)
    dependsOn(sourcesJarTask)
    val jarFileProvider = sourcesJarTask.flatMap { it.archiveFile }
    inputs.file(jarFileProvider)

    doLast {
        val jarFile = jarFileProvider.get().asFile
        val violations = mutableListOf<String>()
        ZipFile(jarFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val ktEntries = entries.filter { !it.isDirectory && it.name.endsWith(".kt") }
            if (ktEntries.none { it.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/antlr/") }) {
                violations += "Expected ANTLR sources under commonMain/dev/sebastiano/ciacola/syntax/antlr/ in ${jarFile.name}"
            }
            val modifierWord =
                """(?:public|open|abstract|final|sealed|data|enum|value|annotation|inline|const|expect|actual|suspend|tailrec|operator|infix|external|lateinit|override|companion)"""
            val topLevelNonInternal = Regex(
                """(?m)^(?!internal\b|private\b)(?:$modifierWord\s+)*(?:class|object|interface|fun|val|var|typealias)\b""",
            )
            val windowsDriveHeader = Regex("""^// Generated from [A-Za-z]:""")
            for (entry in entries) {
                if (entry.isDirectory) continue
                if (entry.name.endsWith(".tokens") || entry.name.endsWith(".interp")) {
                    violations += "Forbidden ANTLR sidecar file in ${jarFile.name}: ${entry.name}"
                }
                if (entry.name.startsWith("commonMain/") && entry.name.endsWith(".kt") &&
                    !entry.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/")
                ) {
                    violations += "Improperly rooted Kotlin source in ${jarFile.name}: ${entry.name}"
                }
                if (entry.name.endsWith(".kt")) {
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    val firstLine = text.lineSequence().firstOrNull().orEmpty()
                    if (firstLine.startsWith("// Generated from /") || windowsDriveHeader.containsMatchIn(firstLine)) {
                        violations += "Absolute local path leaked in ${entry.name}: $firstLine"
                    }
                    if (entry.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/antlr/") &&
                        topLevelNonInternal.containsMatchIn(text)
                    ) {
                        violations += "Public top-level declaration in ${entry.name}"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("SourcesJar verification failed:\n" + violations.joinToString("\n") { " - $it" })
        }
    }
}

tasks.named("check") {
    dependsOn(verifySyntaxHighlightSourcesJar)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        name = "Ciacola Syntax Highlight"
        description = "Standalone Kotlin Multiplatform ANTLR4 syntax highlighting library."
        url = "https://github.com/rock3r/ciacola"
        licenses {
            license {
                name = "The Apache Software License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "rock3r"
                name = "Sebastiano Poggi"
            }
        }
        scm {
            url = "https://github.com/rock3r/ciacola"
            connection = "scm:git:https://github.com/rock3r/ciacola.git"
            developerConnection = "scm:git:ssh://git@github.com/rock3r/ciacola.git"
        }
    }
}

configure<DokkaExtension> {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory = rootProject.projectDir
            remoteUrl("https://github.com/rock3r/ciacola/tree/main/")
            remoteLineSuffix = "#L"
        }
        perPackageOption {
            matchingRegex.set("""dev\.sebastiano\.ciacola\.syntax\.antlr(\..*)?""")
            suppress.set(true)
        }
    }
}
