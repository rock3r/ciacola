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
    explicitApi()

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
        // AntlrWorkerManager sets the worker workingDir to layout.projectDirectory, so a relative
        // path resolves cleanly while keeping Gradle's build cache relocatable across machines.
        arguments = listOf("-lib", "src/commonMain/antlr/shared")
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

tasks.withType(org.gradle.jvm.tasks.Jar::class.java).configureEach {
    from(rootProject.file("LICENSE.txt")) {
        into("META-INF")
    }
    from(layout.projectDirectory.file("NOTICE.txt")) {
        into("META-INF")
    }
}

val verifySyntaxHighlightSourcesJar = tasks.register("verifySyntaxHighlightSourcesJar") {
    group = "verification"
    description = "Verifies published JAR notices, UnicodeIdent.g4 notice header, sources layout, hygiene, and internal visibility."
    val jvmJarTask = tasks.named("jvmJar", org.gradle.jvm.tasks.Jar::class.java)
    val sourcesJarTask = tasks.named("jvmSourcesJar", org.gradle.jvm.tasks.Jar::class.java)
    val jsSourcesJarTask = tasks.named("jsSourcesJar", org.gradle.jvm.tasks.Jar::class.java)
    val wasmJsSourcesJarTask = tasks.named("wasmJsSourcesJar", org.gradle.jvm.tasks.Jar::class.java)
    val allMetadataJarTask = tasks.named("allMetadataJar", org.gradle.jvm.tasks.Jar::class.java)
    dependsOn(jvmJarTask, sourcesJarTask, jsSourcesJarTask, wasmJsSourcesJarTask, allMetadataJarTask)

    val jarProviders = listOf(
        jvmJarTask.flatMap { it.archiveFile },
        sourcesJarTask.flatMap { it.archiveFile },
        jsSourcesJarTask.flatMap { it.archiveFile },
        wasmJsSourcesJarTask.flatMap { it.archiveFile },
        allMetadataJarTask.flatMap { it.archiveFile },
    )
    val sourcesJarProvider = sourcesJarTask.flatMap { it.archiveFile }
    val sharedUnicodeDir = layout.projectDirectory.dir("src/commonMain/antlr/shared")
    val sharedUnicodeFiles = fileTree(sharedUnicodeDir) { include("Unicode*.g4") }
    jarProviders.forEach { inputs.file(it) }
    inputs.files(sharedUnicodeFiles).withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val violations = mutableListOf<String>()
        val requiredNoticeSnippets = listOf(
            "Apache License, Version 2.0",
            "Copyright (c) 2013 Terence Parr",
            "Redistribution and use in source and binary forms",
            "UNICODE LICENSE V3",
            "Permission is hereby granted, free of charge, to any person obtaining",
        )

        val unicodeGrammars = sharedUnicodeDir.asFile.listFiles { file ->
            file.isFile && file.name.startsWith("Unicode") && file.extension == "g4"
        }?.sortedBy { it.name }.orEmpty()
        if (unicodeGrammars.size < 4) {
            violations += "Expected at least 4 generated Unicode*.g4 grammars in ${sharedUnicodeDir.asFile.name}, found ${unicodeGrammars.map { it.name }}"
        }
        for (grammarFile in unicodeGrammars) {
            val grammarText = grammarFile.readText()
            if (!grammarText.contains("UNICODE LICENSE V3") ||
                !grammarText.contains("Permission is hereby granted, free of charge, to any person obtaining")
            ) {
                violations += "${grammarFile.name} is missing the Unicode License v3 copyright and permission notice header"
            }
        }

        for (jarProvider in jarProviders) {
            val jarFile = jarProvider.get().asFile
            ZipFile(jarFile).use { zip ->
                val licenseEntry = zip.getEntry("META-INF/LICENSE.txt")
                if (licenseEntry == null) {
                    violations += "Missing META-INF/LICENSE.txt in ${jarFile.name}"
                }
                val noticeEntry = zip.getEntry("META-INF/NOTICE.txt")
                if (noticeEntry == null) {
                    violations += "Missing META-INF/NOTICE.txt in ${jarFile.name}"
                } else {
                    val noticeText = zip.getInputStream(noticeEntry).bufferedReader().use { it.readText() }
                    for (snippet in requiredNoticeSnippets) {
                        if (!noticeText.contains(snippet)) {
                            violations += "META-INF/NOTICE.txt in ${jarFile.name} is missing required notice text: '$snippet'"
                        }
                    }
                }
            }
        }

        val sourcesJarFile = sourcesJarProvider.get().asFile
        ZipFile(sourcesJarFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val ktEntries = entries.filter { !it.isDirectory && it.name.endsWith(".kt") }
            if (ktEntries.none { it.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/antlr/") }) {
                violations += "Expected ANTLR sources under commonMain/dev/sebastiano/ciacola/syntax/antlr/ in ${sourcesJarFile.name}"
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
                    violations += "Forbidden ANTLR sidecar file in ${sourcesJarFile.name}: ${entry.name}"
                }
                if (entry.name.startsWith("commonMain/") && entry.name.endsWith(".kt") &&
                    !entry.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/")
                ) {
                    violations += "Improperly rooted Kotlin source in ${sourcesJarFile.name}: ${entry.name}"
                }
                if (entry.name.endsWith(".kt")) {
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    val firstLine = text.lineSequence().firstOrNull().orEmpty()
                    if (firstLine.startsWith("// Generated from /") || windowsDriveHeader.containsMatchIn(firstLine)) {
                        violations += "Absolute local path leaked in ${entry.name}: $firstLine"
                    }
                    val isInternalPackage =
                        entry.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/antlr/") ||
                            entry.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/lexer/") ||
                            entry.name.startsWith("commonMain/dev/sebastiano/ciacola/syntax/internal/")
                    if (isInternalPackage && topLevelNonInternal.containsMatchIn(text)) {
                        violations += "Public top-level declaration in ${entry.name}"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Packaging verification failed:\n" + violations.joinToString("\n") { " - $it" })
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
            license {
                name = "BSD 3-Clause License (adapted XML grammar)"
                url = "https://opensource.org/licenses/BSD-3-Clause"
                distribution = "repo"
            }
            license {
                name = "Unicode License v3 (UCD-derived identifier tables)"
                url = "https://www.unicode.org/license.txt"
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
            matchingRegex.set("""dev\.sebastiano\.ciacola\.syntax\.(antlr|lexer|internal)(\..*)?""")
            suppress.set(true)
        }
    }
}
