package dev.sebastiano.ciacola.syntax

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxHighlightPackagingTest {

    @Test
    fun publicApiDoesNotExposeAntlrRuntimeTypes() {
        val publicApiClasses = listOf(
            SyntaxToken::class.java,
            SyntaxTokenKind::class.java,
            SyntaxLanguage::class.java,
            SyntaxLexer::class.java,
            SyntaxHighlighter::class.java,
        )

        assertTrue(publicApiClasses.isNotEmpty(), "Expected public API classes to inspect")
        for (clazz in publicApiClasses) {
            assertTrue(Modifier.isPublic(clazz.modifiers), "Expected public API class ${clazz.name} to be public")

            clazz.genericSuperclass?.let { superType ->
                assertFalse(
                    superType.typeName.contains("org.antlr."),
                    "Public class ${clazz.name} exposes ANTLR supertype: $superType",
                )
            }
            for (iface in clazz.genericInterfaces) {
                assertFalse(
                    iface.typeName.contains("org.antlr."),
                    "Public class ${clazz.name} exposes ANTLR interface: $iface",
                )
            }
            for (ctor in clazz.constructors) {
                for (param in ctor.genericParameterTypes) {
                    assertFalse(
                        param.typeName.contains("org.antlr."),
                        "Public constructor on ${clazz.name} exposes ANTLR parameter: $param",
                    )
                }
            }
            for (field in clazz.fields) {
                assertFalse(
                    field.genericType.typeName.contains("org.antlr."),
                    "Public field ${clazz.name}.${field.name} exposes ANTLR type: ${field.genericType}",
                )
            }
            for (method in clazz.methods) {
                if (method.declaringClass == Any::class.java) continue
                assertFalse(
                    method.genericReturnType.typeName.contains("org.antlr."),
                    "Public method ${clazz.name}.${method.name} exposes ANTLR return type: ${method.genericReturnType}",
                )
                for (param in method.genericParameterTypes) {
                    assertFalse(
                        param.typeName.contains("org.antlr."),
                        "Public method ${clazz.name}.${method.name} exposes ANTLR parameter: $param",
                    )
                }
            }
        }
    }

    @Test
    fun antlrAndLexerSourcesAreInternalCleanAndProperlyRooted() {
        val moduleDir = resolveModuleDir()
        val handWrittenAntlrDir = File(moduleDir, "src/commonMain/kotlin/dev/sebastiano/ciacola/syntax/antlr")
        val lexerImplDir = File(moduleDir, "src/commonMain/kotlin/dev/sebastiano/ciacola/syntax/lexer")
        val internalDir = File(moduleDir, "src/commonMain/kotlin/dev/sebastiano/ciacola/syntax/internal")
        val generatedAntlrRoot = File(moduleDir, "build/generatedAntlr")

        val modifierWord =
            """(?:public|open|abstract|final|sealed|data|enum|value|annotation|inline|const|expect|actual|suspend|tailrec|operator|infix|external|lateinit|override|companion)"""
        val topLevelNonInternal = Regex(
            """(?m)^(?!internal\b|private\b)(?:$modifierWord\s+)*(?:class|object|interface|fun|val|var|typealias)\b""",
        )
        val windowsDriveHeader = Regex("""^// Generated from [A-Za-z]:""")

        val activeGeneratedRoots = if (generatedAntlrRoot.isDirectory) {
            generatedAntlrRoot.listFiles()
                ?.filter { it.isDirectory && it.name != "commonMain" }
                .orEmpty()
        } else {
            emptyList()
        }

        val kotlinFiles = buildList {
            for (dir in listOf(handWrittenAntlrDir, lexerImplDir, internalDir)) {
                if (dir.isDirectory) {
                    dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { add(it) }
                }
            }
            for (root in activeGeneratedRoots) {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { add(it) }
            }
        }
        assertTrue(kotlinFiles.isNotEmpty(), "Expected Kotlin files under internal implementation directories in $moduleDir")

        for (file in kotlinFiles) {
            val content = file.readText()
            val firstLine = content.lineSequence().firstOrNull().orEmpty()
            assertFalse(
                firstLine.startsWith("// Generated from /") || windowsDriveHeader.containsMatchIn(firstLine),
                "Generated file ${file.name} leaked local machine path: $firstLine",
            )
            assertFalse(
                topLevelNonInternal.containsMatchIn(content),
                "Internal implementation file ${file.path} contains a non-internal top-level declaration",
            )
        }

        val noticeFile = File(moduleDir, "NOTICE.txt")
        assertTrue(noticeFile.isFile, "Expected module NOTICE.txt at ${noticeFile.path}")
        val noticeText = noticeFile.readText()
        assertTrue(noticeText.contains("Apache License, Version 2.0"), "NOTICE.txt must cite Apache-2.0")
        assertTrue(noticeText.contains("Copyright (c) 2013 Terence Parr"), "NOTICE.txt must retain XML BSD-3-Clause notice")
        assertTrue(noticeText.contains("DerivedCoreProperties-16.0.0.txt"), "NOTICE.txt must cite Unicode UCD")

        for (root in activeGeneratedRoots) {
            val sidecars = root.walkTopDown()
                .filter { it.isFile && (it.extension == "tokens" || it.extension == "interp") }
                .toList()
            assertTrue(sidecars.isEmpty(), "Expected zero .tokens/.interp sidecar files in $root, found $sidecars")

            val misrooted = root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { !it.invariantSeparatorsPath.contains("/kotlin/dev/sebastiano/ciacola/syntax/antlr/") }
                .toList()
            assertTrue(misrooted.isEmpty(), "Generated ANTLR sources must sit under .../kotlin/dev/sebastiano/ciacola/syntax/antlr/, found $misrooted")
        }
    }

    private fun resolveModuleDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "syntax-highlight").isDirectory) File(cwd, "syntax-highlight") else cwd
    }
}
