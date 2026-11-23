import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
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
    val outDir = layout.buildDirectory.dir("generatedAntlr/commonMain/kotlin/dev/sebastiano/ciacola/syntax/antlr/$packageSuffix")

    val task = tasks.register("generate${taskSuffix}GrammarSource", AntlrKotlinTask::class.java) {
        group = "antlr"
        description = "Generates ANTLR4 Kotlin lexer sources for $dirName."
        inputs.files(fileTree(antlrDir) { include("**/*.g4", "**/*.tokens") })
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(outDir)
        doFirst { delete(outDir) }

        source = fileTree(antlrDir) {
            include("**/*.g4")
        }
        packageName = pkgName
        outputDirectory = outDir.get().asFile
    }

    generateAntlrGrammars.configure {
        dependsOn(task)
    }

    kotlin.sourceSets.named("commonMain") {
        kotlin.srcDir(task)
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

mavenPublishing {
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
