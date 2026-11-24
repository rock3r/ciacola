package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.DiffSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.lexEmbeddedNoRecursion
import dev.sebastiano.ciacola.syntax.lexer.normalizeDiffPath
import dev.sebastiano.ciacola.syntax.lexer.resolveLanguageForNormalizedPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffSyntaxLexerTest {

    @Test
    fun resolvesAll32LanguagesAndSpecialBasenamesFromPaths() {
        fun resolve(raw: String): SyntaxLanguage? =
            normalizeDiffPath(raw)?.let { resolveLanguageForNormalizedPath(it) }

        // Special basenames (case-insensitive)
        assertEquals(SyntaxLanguage.Dockerfile, resolve("a/Dockerfile"))
        assertEquals(SyntaxLanguage.Dockerfile, resolve("b/deploy/Dockerfile.prod"))
        assertEquals(SyntaxLanguage.Dockerfile, resolve("a/app.dockerfile"))
        assertEquals(SyntaxLanguage.Dockerfile, resolve("b/Containerfile"))
        assertEquals(SyntaxLanguage.Dockerfile, resolve("b/Containerfile.alpine"))
        assertEquals(SyntaxLanguage.Dockerfile, resolve("a/dev.containerfile"))

        assertEquals(SyntaxLanguage.Ruby, resolve("a/Rakefile"))
        assertEquals(SyntaxLanguage.Ruby, resolve("b/Gemfile"))
        assertEquals(SyntaxLanguage.Ruby, resolve("a/ios/Podfile"))
        assertEquals(SyntaxLanguage.Ruby, resolve("b/Vagrantfile"))
        assertEquals(SyntaxLanguage.Ruby, resolve("a/fastlane/Fastfile"))
        assertEquals(SyntaxLanguage.Ruby, resolve("b/Brewfile"))

        assertEquals(SyntaxLanguage.Groovy, resolve("a/Jenkinsfile"))
        assertEquals(SyntaxLanguage.Groovy, resolve("b/build.gradle"))
        assertEquals(SyntaxLanguage.Groovy, resolve("a/settings.gradle"))

        assertEquals(SyntaxLanguage.Kotlin, resolve("a/build.gradle.kts"))
        assertEquals(SyntaxLanguage.Kotlin, resolve("b/app/settings.gradle.kts"))

        assertEquals(SyntaxLanguage.Shell, resolve("a/.bashrc"))
        assertEquals(SyntaxLanguage.Shell, resolve("b/.zshrc"))
        assertEquals(SyntaxLanguage.Shell, resolve("a/.bash_profile"))
        assertEquals(SyntaxLanguage.Shell, resolve("b/.profile"))
        assertEquals(SyntaxLanguage.Shell, resolve("a/.zprofile"))
        assertEquals(SyntaxLanguage.Shell, resolve("b/.shrc"))

        // Mnemonic prefixes & slashes
        assertEquals(SyntaxLanguage.Kotlin, resolve("i/src/Main.kt"))
        assertEquals(SyntaxLanguage.Kotlin, resolve("w/src/Main.kt"))
        assertEquals(SyntaxLanguage.Kotlin, resolve("c/src/Main.kt"))
        assertEquals(SyntaxLanguage.Kotlin, resolve("o/src/Main.kt"))
        assertNull(resolve("/dev/null"))
        assertNull(resolve(""))

        // All 32 languages by extension
        val samples = mapOf(
            "a/Main.kt" to SyntaxLanguage.Kotlin,
            "a/pom.xml" to SyntaxLanguage.Xml,
            "a/icon.svg" to SyntaxLanguage.Xml,
            "a/data.jsonc" to SyntaxLanguage.Json,
            "a/index.html" to SyntaxLanguage.Html,
            "a/styles.css" to SyntaxLanguage.Css,
            "a/app.jsx" to SyntaxLanguage.JavaScript,
            "a/view.tsx" to SyntaxLanguage.TypeScript,
            "a/lib.rs" to SyntaxLanguage.Rust,
            "a/main.go" to SyntaxLanguage.Go,
            "a/script.pyi" to SyntaxLanguage.Python,
            "a/Cargo.toml" to SyntaxLanguage.Toml,
            "a/App.java" to SyntaxLanguage.Java,
            "a/header.h" to SyntaxLanguage.C,
            "a/impl.cpp" to SyntaxLanguage.Cpp,
            "a/extra.hxx" to SyntaxLanguage.Cpp,
            "a/shader.frag" to SyntaxLanguage.Glsl,
            "a/effect.sksl" to SyntaxLanguage.Sksl,
            "a/shader.agsl" to SyntaxLanguage.Agsl,
            "a/ci.yaml" to SyntaxLanguage.Yaml,
            "a/schema.sql" to SyntaxLanguage.Sql,
            "a/run.zsh" to SyntaxLanguage.Shell,
            "a/Program.cs" to SyntaxLanguage.CSharp,
            "a/index.php" to SyntaxLanguage.Php,
            "a/App.swift" to SyntaxLanguage.Swift,
            "a/app.rb" to SyntaxLanguage.Ruby,
            "a/main.dart" to SyntaxLanguage.Dart,
            "a/script.groovy" to SyntaxLanguage.Groovy,
            "a/deploy.ps1" to SyntaxLanguage.PowerShell,
            "a/app.dockerfile" to SyntaxLanguage.Dockerfile,
            "a/fix.patch" to SyntaxLanguage.Diff,
            "a/README.markdown" to SyntaxLanguage.Markdown,
            "a/api.proto" to SyntaxLanguage.Protobuf,
            "a/main.tf" to SyntaxLanguage.Hcl,
        )
        assertEquals(SyntaxLanguage.entries.toSet(), samples.values.toSet())
        for ((path, expected) in samples) {
            assertEquals(expected, resolve(path), "Failed to resolve $path")
        }

        // Unknown files
        assertNull(resolve("a/Makefile"))
        assertNull(resolve("b/notes.txt"))
        assertNull(resolve("b/README"))
    }

    @Test
    fun subHighlightsMultiFileDiffAcrossLanguagesAndResetsOnUnknownFiles() {
        val diff = """
            diff --git a/Main.kt b/Main.kt
            index 1111111..2222222 100644
            --- a/Main.kt	2026-11-23 10:00:00.000 +0100
            +++ b/Main.kt	2026-11-23 10:01:00.000 +0100
            @@ -1,4 +1,4 @@ fun demo()
             /* outer
            - * old */
            + * new */
             val answer = 42
            diff --git a/app.py b/app.py
            --- a/app.py
            +++ b/app.py
            @@ -1,2 +1,2 @@
            -def greet(): return "old"
            +def greet(): return "new"
            diff --git a/config.json b/config.json
            --- a/config.json
            +++ b/config.json
            @@ -1,2 +1,2 @@
            -{ "ok": false }
            +{ "ok": true }
            diff --git a/config.yaml b/config.yaml
            --- a/config.yaml
            +++ b/config.yaml
            @@ -1,2 +1,2 @@
            -enabled: false
            +enabled: true
            diff --git a/script.rb b/script.rb
            --- a/script.rb
            +++ b/script.rb
            @@ -1,2 +1,2 @@
            -puts "bye"
            +puts "hi"
            diff --git a/run.sh b/run.sh
            --- a/run.sh
            +++ b/run.sh
            @@ -1,2 +1,2 @@
            -if true; then echo 0; fi
            +if true; then echo 1; fi
            diff --git a/index.html b/index.html
            --- a/index.html
            +++ b/index.html
            @@ -1,2 +1,2 @@
            -<div class="old">Hi</div>
            +<div class="new">Hi</div>
            diff --git a/main.cpp b/main.cpp
            --- a/main.cpp
            +++ b/main.cpp
            @@ -1,2 +1,2 @@
            -constexpr int n = 1;
            +constexpr int n = 2;
            diff --git a/notes.txt b/notes.txt
            --- a/notes.txt
            +++ b/notes.txt
            @@ -1,2 +1,2 @@
            -val shouldNotBeKotlin = 1
            +val shouldStayDiffString = 2
            diff --git a/Makefile b/Makefile
            --- a/Makefile
            +++ b/Makefile
            @@ -1,1 +1,1 @@
            -all: old
            +all: new
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diff, "multi-file-subhighlight")

        fun tokensInLine(lineText: String): List<SyntaxToken> {
            val lineStart = diff.indexOf(lineText)
            assertTrue(lineStart >= 0, "Line not found: $lineText")
            val lineEnd = lineStart + lineText.length
            return tokens.filter { it.startIndex >= lineStart && it.endIndex <= lineEnd }
        }

        // Kotlin multi-line comment spanning context + deleted / added lines
        val ktDeletedComment = tokensInLine("- * old */")
        assertEquals(SyntaxTokenKind.Punctuation, ktDeletedComment[0].kind)
        assertEquals(SyntaxTokenKind.Comment, ktDeletedComment.last().kind)

        val ktAddedComment = tokensInLine("+ * new */")
        assertEquals(SyntaxTokenKind.Punctuation, ktAddedComment[0].kind)
        assertEquals(SyntaxTokenKind.Comment, ktAddedComment.last().kind)

        val ktCtx = tokensInLine(" val answer = 42")
        assertTrue(ktCtx.any { it.slice(diff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(ktCtx.any { it.slice(diff) == "42" && it.kind == SyntaxTokenKind.Number })

        // Python
        val pyAdded = tokensInLine("+def greet(): return \"new\"")
        assertTrue(pyAdded.any { it.slice(diff) == "def" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(pyAdded.any { it.slice(diff) == "\"new\"" && it.kind == SyntaxTokenKind.String })

        // JSON
        val jsonAdded = tokensInLine("+{ \"ok\": true }")
        assertTrue(jsonAdded.any { it.slice(diff) == "\"ok\"" && it.kind == SyntaxTokenKind.Property })
        assertTrue(jsonAdded.any { it.slice(diff) == "true" && it.kind == SyntaxTokenKind.Constant })

        // YAML
        val yamlAdded = tokensInLine("+enabled: true")
        assertTrue(yamlAdded.any { it.slice(diff) == "enabled" && it.kind == SyntaxTokenKind.Property })
        assertTrue(yamlAdded.any { it.slice(diff) == "true" && it.kind == SyntaxTokenKind.Constant })

        // Ruby
        val rbAdded = tokensInLine("+puts \"hi\"")
        assertTrue(rbAdded.any { it.slice(diff) == "puts" && it.kind == SyntaxTokenKind.Function })

        // Shell
        val shAdded = tokensInLine("+if true; then echo 1; fi")
        assertTrue(shAdded.any { it.slice(diff) == "if" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(shAdded.any { it.slice(diff) == "1" && it.kind == SyntaxTokenKind.Number })

        // HTML
        val htmlAdded = tokensInLine("+<div class=\"new\">Hi</div>")
        assertTrue(htmlAdded.any { it.slice(diff) == "div" && it.kind == SyntaxTokenKind.Tag })
        assertTrue(htmlAdded.any { it.slice(diff) == "class" && it.kind == SyntaxTokenKind.Attribute })

        // C++
        val cppAdded = tokensInLine("+constexpr int n = 2;")
        assertTrue(cppAdded.any { it.slice(diff) == "constexpr" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(cppAdded.any { it.slice(diff) == "2" && it.kind == SyntaxTokenKind.Number })

        // Reset to unknown extension notes.txt -> pure Diff (no Kotlin sub-highlighting!)
        val txtDel = tokensInLine("-val shouldNotBeKotlin = 1")
        assertEquals(SyntaxTokenKind.Punctuation, txtDel[0].kind)
        assertEquals(SyntaxTokenKind.Keyword, txtDel[1].kind)
        assertEquals("val shouldNotBeKotlin = 1", txtDel[1].slice(diff))

        val txtAdd = tokensInLine("+val shouldStayDiffString = 2")
        assertEquals(SyntaxTokenKind.Punctuation, txtAdd[0].kind)
        assertEquals(SyntaxTokenKind.String, txtAdd[1].kind)
        assertEquals("val shouldStayDiffString = 2", txtAdd[1].slice(diff))

        // Unknown Makefile -> pure Diff
        val mkAdd = tokensInLine("+all: new")
        assertEquals(SyntaxTokenKind.String, mkAdd[1].kind)
        assertEquals("all: new", mkAdd[1].slice(diff))
    }

    @Test
    fun handlesDeletedFilesQuotedPathsCombinedContextNormalAndBinaryPatches() {
        val diff = """
            diff --git "a/dir with spaces/My \"File\".kt" "b/dir with spaces/My \"File\".kt"
            --- "a/dir with spaces/My \"File\".kt"
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -val deleted = 123
            diff --git a/Merge.kt b/Merge.kt
            index aaa..bbb 100644
            --- a/Merge.kt
            +++ b/Merge.kt
            @@@ -1,2 -1,2 +1,3 @@@ fun merge()
              val keep = 1
            --val removed = 2
            ++val added = 3
            + val fromLeft = 4
            diff --git a/bin.dat b/bin.dat
            GIT binary patch
            literal 12
            RcmZQzU|<5P00000000000

            delta 4
            KcmZQzU|<5P00000
            *** a/Ctx.kt	2026-11-23 10:00:00
            --- b/Ctx.kt	2026-11-23 10:01:00
            ***************
            *** 1,2 ****
              val same = 1
            ! val x = "old"
            --- 1,2 ----
              val same = 1
            ! val x = "new"
            Index: Norm.kt
            ===================================================================
            1,1c1,1
            < val oldNorm = 1
            ---
            > val newNorm = 2
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diff, "complex-formats")

        // Deleted file (/dev/null in +++ falls back to --- Kotlin path)
        val delStart = diff.indexOf("-val deleted = 123")
        val delEnd = delStart + "-val deleted = 123".length
        val delTokens = tokens.filter { it.startIndex >= delStart && it.endIndex <= delEnd }
        assertTrue(delTokens.any { it.slice(diff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(delTokens.any { it.slice(diff) == "123" && it.kind == SyntaxTokenKind.Number })

        // Combined diff (@@@) sub-highlighted as Kotlin
        val combAddStart = diff.indexOf("++val added = 3")
        val combAddEnd = combAddStart + "++val added = 3".length
        val combTokens = tokens.filter { it.startIndex >= combAddStart && it.endIndex <= combAddEnd }
        assertEquals("++", combTokens[0].slice(diff))
        assertEquals(SyntaxTokenKind.Punctuation, combTokens[0].kind)
        assertTrue(combTokens.any { it.slice(diff) == "3" && it.kind == SyntaxTokenKind.Number })

        // Context diff sub-highlighted as Kotlin
        val ctxStart = diff.indexOf("! val x = \"new\"")
        val ctxEnd = ctxStart + "! val x = \"new\"".length
        val ctxTokens = tokens.filter { it.startIndex >= ctxStart && it.endIndex <= ctxEnd }
        assertEquals("! ", ctxTokens[0].slice(diff))
        assertEquals(SyntaxTokenKind.Punctuation, ctxTokens[0].kind)
        assertTrue(ctxTokens.any { it.slice(diff) == "new" && it.kind == SyntaxTokenKind.String })

        // Normal diff sub-highlighted as Kotlin
        val normStart = diff.indexOf("> val newNorm = 2")
        val normEnd = normStart + "> val newNorm = 2".length
        val normTokens = tokens.filter { it.startIndex >= normStart && it.endIndex <= normEnd }
        assertEquals("> ", normTokens[0].slice(diff))
        assertEquals(SyntaxTokenKind.Punctuation, normTokens[0].kind)
        assertTrue(normTokens.any { it.slice(diff) == "2" && it.kind == SyntaxTokenKind.Number })

        // Binary patch tokens preserved cleanly
        assertTrue(tokens.any { it.slice(diff) == "GIT binary patch" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(diff) == "RcmZQzU|<5P00000000000" && it.kind == SyntaxTokenKind.Constant })
    }

    @Test
    fun enforcesOneLevelNonRecursiveSubHighlightingAndHandlesDiffOfDiff() {
        val ktDiff = """
            diff --git a/Main.kt b/Main.kt
            --- a/Main.kt
            +++ b/Main.kt
            @@ -1,1 +1,1 @@
            -val x = 1
            +val x = 2
        """.trimIndent() + "\n"

        // Direct call with allowNestedSubHighlighting = false disables Kotlin sub-highlighting
        val nonNestedTokens = assertLosslessInvariants(
            code = ktDiff,
            tokens = DiffSyntaxLexer.lex(ktDiff, allowNestedSubHighlighting = false),
            label = "diff-no-nested",
        )
        assertTrue(
            nonNestedTokens.none { it.slice(ktDiff) == "2" && it.kind == SyntaxTokenKind.Number },
            "Expected no Kotlin Number sub-token when allowNestedSubHighlighting=false",
        )
        assertTrue(nonNestedTokens.any { it.slice(ktDiff) == "val x = 2" && it.kind == SyntaxTokenKind.String })

        // lexEmbeddedNoRecursion also disables nested sub-highlighting on Diff
        val embeddedTokens = lexEmbeddedNoRecursion(ktDiff, SyntaxLanguage.Diff)
        assertEquals(nonNestedTokens, embeddedTokens)

        // Diff of a .diff / .patch file NEVER recurses
        val diffOfDiff = """
            diff --git a/changes.patch b/changes.patch
            --- a/changes.patch
            +++ b/changes.patch
            @@ -1,2 +1,2 @@
            -@@ -1 +1 @@
            +-val x = 1
            +@@ -1 +1 @@
            ++val x = 2
        """.trimIndent() + "\n"

        val patchTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diffOfDiff, "diff-of-diff")
        assertTrue(patchTokens.any { it.slice(diffOfDiff) == "+val x = 2" && it.kind == SyntaxTokenKind.String })
        assertTrue(patchTokens.none { it.slice(diffOfDiff) == "2" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun handlesYamlTripleDashInsideUnifiedHunkWithoutBreakingHunkState() {
        val yamlDiff = """
            diff --git a/doc.yaml b/doc.yaml
            --- a/doc.yaml
            +++ b/doc.yaml
            @@ -1,3 +1,3 @@
            ----
            +---
             key: 42
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, yamlDiff, "yaml-triple-dash")
        // Line "----" inside hunk is '-' marker + '---' YAML doc marker (Keyword), NOT FROM_FILE header
        assertTrue(tokens.any { it.slice(yamlDiff) == "---" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(yamlDiff) == "key" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(yamlDiff) == "42" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun handlesBomCrlfBareCrEmptyLinesMissingTrailingNewlineUnicodeAndLoneSurrogates() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val crlfDiff = "\uFEFFdiff --git a/Main.kt b/Main.kt\r\n" +
            "--- a/Main.kt\r\n" +
            "+++ b/Main.kt\r\n" +
            "@@ -1,3 +1,3 @@\r\n" +
            " \r\n" +
            "-\r\n" +
            "+\r\n" +
            "+val msg = \"🎉 $loneHigh $loneLow\"\r" +
            "+val tail = 1"

        val tokens = assertLosslessInvariants(DiffSyntaxLexer, crlfDiff, "diff-crlf-bom-surrogate")
        assertTrue(tokens.any { it.slice(crlfDiff) == "tail" })
        assertTrue(tokens.any { it.slice(crlfDiff) == "1" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            diff --git a/Main.kt b/Main.kt
            index 1234567..abcdef0 100644
            --- a/Main.kt
            +++ b/Main.kt
            @@ -1,3 +1,3 @@ fun demo()
             val a = 1
            -val b = "old"
            +val b = "new"
            \ No newline at end of file
        """.trimIndent() + "\n"

        assertPrefixTruncationSafety(DiffSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(DiffSyntaxLexer, seed = 0xD1FF2026L, iterations = 60)
    }

    @Test
    fun handlesStrippedBlankContextLinesAcrossMultiFileUnifiedAndCombinedHunks() {
        val diff = "--- a/Main.kt\n" +
            "+++ b/Main.kt\n" +
            "@@ -1,3 +1,3 @@\n" +
            " val a = 1\n" +
            "\n" +
            "-val b = 2\n" +
            "+val b = 3\n" +
            "--- a/app.py\n" +
            "+++ b/app.py\n" +
            "@@ -1 +1 @@\n" +
            "-def f(): return 1\n" +
            "+def f(): return 2\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diff, "stripped-blank-ctx")
        val pyLineStart = diff.indexOf("+def f(): return 2")
        val pyLineEnd = pyLineStart + "+def f(): return 2".length
        val pyTokens = tokens.filter { it.startIndex >= pyLineStart && it.endIndex <= pyLineEnd }
        assertTrue(pyTokens.any { it.slice(diff) == "def" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(pyTokens.any { it.slice(diff) == "2" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tokens.any { it.slice(diff) == "a/app.py" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(diff) == "b/app.py" && it.kind == SyntaxTokenKind.String })

        // Combined diff with stripped blank context line followed by another file
        val combinedDiff = "--- a/Main.kt\n" +
            "+++ b/Main.kt\n" +
            "@@@ -1,3 -1,3 +1,3 @@@\n" +
            "  val a = 1\n" +
            "\n" +
            "--val b = 2\n" +
            "++val b = 3\n" +
            "--- a/app.py\n" +
            "+++ b/app.py\n" +
            "@@ -1 +1 @@\n" +
            "-def g(): return 1\n" +
            "+def g(): return 2\n"
        val combTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, combinedDiff, "combined-stripped-blank-ctx")
        val pyCombStart = combinedDiff.indexOf("+def g(): return 2")
        val pyCombEnd = pyCombStart + "+def g(): return 2".length
        val pyCombTokens = combTokens.filter { it.startIndex >= pyCombStart && it.endIndex <= pyCombEnd }
        assertTrue(pyCombTokens.any { it.slice(combinedDiff) == "def" && it.kind == SyntaxTokenKind.Keyword })
    }

    @Test
    fun fallsBackToFromPathOnUnknownToExtensionAndResetsGitSectionStateAcrossPlainHeaders() {
        val backupDiff = "--- Main.kt\n" +
            "+++ Main.kt.bak\n" +
            "@@ -1 +1 @@\n" +
            "-val x = 1\n" +
            "+val x = 2\n"
        val backupTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, backupDiff, "backup-fallback")
        assertTrue(backupTokens.any { it.slice(backupDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(backupTokens.any { it.slice(backupDiff) == "2" && it.kind == SyntaxTokenKind.Number })

        val mixedDiff = "diff --git a/Main.kt b/Main.kt\n" +
            "--- a/Main.kt\n" +
            "+++ b/Main.kt\n" +
            "@@ -1 +1 @@\n" +
            "-val a = 1\n" +
            "+val a = 2\n" +
            "--- notes.txt\n" +
            "+++ notes.txt\n" +
            "@@ -1 +1 @@\n" +
            "-val x = 1\n" +
            "+val x = 2\n"
        val mixedTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, mixedDiff, "plain-reset-after-git")
        val notesLineStart = mixedDiff.indexOf("+val x = 2")
        val notesLineEnd = notesLineStart + "+val x = 2".length
        val notesTokens = mixedTokens.filter { it.startIndex >= notesLineStart && it.endIndex <= notesLineEnd }
        assertEquals(2, notesTokens.size)
        assertEquals(SyntaxTokenKind.Punctuation, notesTokens[0].kind)
        assertEquals(SyntaxTokenKind.String, notesTokens[1].kind)
        assertEquals("val x = 2", notesTokens[1].slice(mixedDiff))
    }

    @Test
    fun handlesSvnSpaceAnnotationsUnquotedGitSpacesAndFlaglessDiffHeaders() {
        val svnDiff = "Index: src/Main.kt\n" +
            "===================================================================\n" +
            "--- src/Main.kt (revision 42)\n" +
            "+++ src/Main.kt (working copy)\n" +
            "@@ -1 +1 @@\n" +
            "-val x = 1\n" +
            "+val x = 2\n"
        val svnTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, svnDiff, "svn-annotations")
        assertTrue(svnTokens.any { it.slice(svnDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(svnTokens.any { it.slice(svnDiff) == "2" && it.kind == SyntaxTokenKind.Number })

        val ccDiff = "diff --cc dir with spaces/Main.kt\n" +
            "index 111,222..333\n" +
            "@@@ -1,1 -1,1 +1,1 @@@\n" +
            "- val a = 1\n" +
            "++val a = 2\n"
        val ccTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, ccDiff, "cc-unquoted-spaces")
        assertTrue(ccTokens.any { it.slice(ccDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(ccTokens.any { it.slice(ccDiff) == "2" && it.kind == SyntaxTokenKind.Number })

        val gitSpaceDiff = "diff --git a/dir with spaces/Main.kt b/dir with spaces/Main.kt\n" +
            "@@ -1 +1 @@\n" +
            "-val s = 1\n" +
            "+val s = 2\n"
        val gitSpaceTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, gitSpaceDiff, "git-unquoted-spaces")
        assertTrue(gitSpaceTokens.any { it.slice(gitSpaceDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(gitSpaceTokens.any { it.slice(gitSpaceDiff) == "2" && it.kind == SyntaxTokenKind.Number })

        val flaglessDiff = "diff dir1/Main.kt dir2/Main.kt\n" +
            "1c1\n" +
            "< val old = 1\n" +
            "---\n" +
            "> val new = 2\n"
        val flaglessTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, flaglessDiff, "flagless-diff")
        assertEquals(SyntaxTokenKind.Keyword, flaglessTokens.first { it.slice(flaglessDiff).startsWith("diff dir1") }.kind)
        assertTrue(flaglessTokens.any { it.slice(flaglessDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(flaglessTokens.any { it.slice(flaglessDiff) == "2" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun isolatesContextDiffOldAndNewHalvesAndParsesSeparatorFunctionLabels() {
        val ctxDiff = "*** a/Main.kt\t2026-11-23\n" +
            "--- b/Main.kt\t2026-11-23\n" +
            "*************** fun greet()\n" +
            "*** 1,2 ****\n" +
            "  val s = \"\"\"\n" +
            "- old\"\"\"\n" +
            "--- 1,2 ----\n" +
            "  val s = \"\"\"\n" +
            "+ new\"\"\"\n" +
            "*************** fun tail()\n" +
            "*** 5,5 ****\n" +
            "  val ok = true\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, ctxDiff, "context-halves-and-sep-label")
        assertTrue(tokens.any { it.slice(ctxDiff) == "***************" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(tokens.any { it.slice(ctxDiff) == "fun greet()" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(ctxDiff) == "fun tail()" && it.kind == SyntaxTokenKind.Plain })

        val newHalfStart = ctxDiff.indexOf("--- 1,2 ----")
        val newCtxStart = ctxDiff.indexOf("  val s = \"\"\"", startIndex = newHalfStart)
        val newCtxEnd = newCtxStart + "  val s = \"\"\"".length
        val newCtxTokens = tokens.filter { it.startIndex >= newCtxStart && it.endIndex <= newCtxEnd }
        assertTrue(newCtxTokens.any { it.slice(ctxDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(newCtxTokens.any { it.slice(ctxDiff) == "=" && it.kind == SyntaxTokenKind.Operator })

        val newAddStart = ctxDiff.indexOf("+ new\"\"\"")
        val newAddEnd = newAddStart + "+ new\"\"\"".length
        val newAddTokens = tokens.filter { it.startIndex >= newAddStart && it.endIndex <= newAddEnd }
        assertTrue(newAddTokens.any { it.slice(ctxDiff) == "new" && it.kind == SyntaxTokenKind.String })
        assertTrue(newAddTokens.any { it.slice(ctxDiff) == "\"\"\"" && it.kind == SyntaxTokenKind.String })
    }

    @Test
    fun linearPerformanceOnLargeUnifiedDiffWithSubHighlighting() {
        val largeKtDiff = buildString {
            append("diff --git a/Large.kt b/Large.kt\n")
            append("--- a/Large.kt\n")
            append("+++ b/Large.kt\n")
            append("@@ -1,1000 +1,1000 @@\n")
            repeat(1_000) { idx ->
                append("-val old_$idx = \"unclosed\n")
                append("+val new_$idx = $idx\n")
            }
        }
        assertLinearPerformance(
            lexer = DiffSyntaxLexer,
            pathologicalInputs = listOf("1k-hunk-kt-lines" to largeKtDiff),
            maxMillisPerInput = 1_500L,
        )
    }

    @Test
    fun linearPerformanceOnMalformedAndSelfDiffInputs() {
        val giantHeaderTail = "@@ -1,1 +1,1 @@ " + "x".repeat(25_000) + "\n-a\n+b\n"
        val giantPatchSelfDiff = buildString {
            append("diff --git a/huge.patch b/huge.patch\n")
            append("--- a/huge.patch\n")
            append("+++ b/huge.patch\n")
            append("@@ -1,2000 +1,2000 @@\n")
            repeat(2_000) { idx ->
                append("-@@ -$idx,1 +$idx,1 @@\n")
                append("++val x$idx = $idx\n")
            }
        }
        assertLinearPerformance(
            lexer = DiffSyntaxLexer,
            pathologicalInputs = listOf(
                "25k-hunk-tail" to giantHeaderTail,
                "4k-self-patch-lines" to giantPatchSelfDiff,
            ),
            maxMillisPerInput = 1_500L,
        )
    }
}
