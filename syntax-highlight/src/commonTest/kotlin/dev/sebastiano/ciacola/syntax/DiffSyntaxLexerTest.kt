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
        val svnDiff = "--- src/Main.kt (revision 42)\n" +
            "+++ src/Main.kt (working copy)\n" +
            "@@ -1 +1 @@\n" +
            "-val x = 1\n" +
            "+val x = 2\n"
        val svnTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, svnDiff, "svn-annotations")
        assertTrue(svnTokens.any { it.slice(svnDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(svnTokens.any { it.slice(svnDiff) == "2" && it.kind == SyntaxTokenKind.Number })

        val isoDiff = "--- src/Main.kt 2026-01-01 12:00:00.000000000 +0000\n" +
            "+++ src/Main.kt 2026-01-01 12:05:00.000000000 +0000\n" +
            "@@ -1 +1 @@\n" +
            "-val iso = 1\n" +
            "+val iso = 2\n"
        val isoTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, isoDiff, "iso-space-ts")
        assertTrue(isoTokens.any { it.slice(isoDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(isoTokens.any { it.slice(isoDiff) == "2" && it.kind == SyntaxTokenKind.Number })

        val ctimeDiff = "--- src/Main.kt Thu Jan  1 00:00:00 1970\n" +
            "+++ src/Main.kt Thu Jan  1 00:01:00 1970\n" +
            "@@ -1 +1 @@\n" +
            "-val ct = 3\n" +
            "+val ct = 4\n"
        val ctimeTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, ctimeDiff, "ctime-space-ts")
        assertTrue(ctimeTokens.any { it.slice(ctimeDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(ctimeTokens.any { it.slice(ctimeDiff) == "4" && it.kind == SyntaxTokenKind.Number })

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
    fun preventsDefaultModeSectionLeakageHandlesLongDashSeparatorsAndContextToUnifiedReset() {
        val logAndSignature = "diff --git a/Main.kt b/Main.kt\n" +
            "--- a/Main.kt\n" +
            "+++ b/Main.kt\n" +
            "@@ -1 +1 @@\n" +
            "-val a = 1\n" +
            "+val a = 2\n" +
            "-- \n" +
            "2.45.0\n" +
            "----------\n" +
            "commit 1234567890abcdef\n" +
            "\n" +
            "    val indentedProse = 99\n" +
            "+   bulletedCommitNote\n" +
            "-   removedCommitNote\n"
        val logTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, logAndSignature, "log-p-no-leak")

        // `-- ` format-patch signature must NOT be sub-highlighted or attached to the Kotlin hunk
        val sigStart = logAndSignature.indexOf("\n-- \n") + 1
        val sigTokens = logTokens.filter { it.startIndex >= sigStart && it.endIndex <= sigStart + 3 }
        assertEquals("-", sigTokens[0].slice(logAndSignature))
        assertEquals(SyntaxTokenKind.Punctuation, sigTokens[0].kind)
        assertEquals("- ", sigTokens[1].slice(logAndSignature))
        assertEquals(SyntaxTokenKind.Keyword, sigTokens[1].kind)

        // `----------` horizontal rule is a single Punctuation token
        assertTrue(logTokens.any { it.slice(logAndSignature) == "----------" && it.kind == SyntaxTokenKind.Punctuation })

        // Indented commit message and `+`/`-` prose lines outside a hunk stay pure Diff (no Kotlin sub-highlighting)
        val proseStart = logAndSignature.indexOf("    val indentedProse = 99")
        val proseEnd = proseStart + "    val indentedProse = 99".length
        val proseTokens = logTokens.filter { it.startIndex >= proseStart && it.endIndex <= proseEnd }
        assertEquals(2, proseTokens.size)
        assertEquals(SyntaxTokenKind.Whitespace, proseTokens[0].kind)
        assertEquals(SyntaxTokenKind.Plain, proseTokens[1].kind)
        assertEquals("   val indentedProse = 99", proseTokens[1].slice(logAndSignature))

        val bulletStart = logAndSignature.indexOf("+   bulletedCommitNote")
        val bulletEnd = bulletStart + "+   bulletedCommitNote".length
        val bulletTokens = logTokens.filter { it.startIndex >= bulletStart && it.endIndex <= bulletEnd }
        assertEquals(2, bulletTokens.size)
        assertEquals(SyntaxTokenKind.Punctuation, bulletTokens[0].kind)
        assertEquals(SyntaxTokenKind.String, bulletTokens[1].kind)

        // Context hunk for .kt followed immediately by unified --- unknown.xyz resets language
        val ctxThenUnified = "*** a/Ctx.kt\t2026-11-23\n" +
            "--- b/Ctx.kt\t2026-11-23\n" +
            "***************\n" +
            "*** 1,1 ****\n" +
            "- val ktOld = 1\n" +
            "--- c/unknown.unknownext\n" +
            "+++ c/unknown.unknownext\n" +
            "@@ -1 +1 @@\n" +
            "-val stayPlain = 1\n" +
            "+val stayString = 2\n"
        val resetTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, ctxThenUnified, "ctx-to-unified-reset")
        assertTrue(resetTokens.any { it.slice(ctxThenUnified) == "c/unknown.unknownext" && it.kind == SyntaxTokenKind.Keyword })
        val unkAddStart = ctxThenUnified.indexOf("+val stayString = 2")
        val unkAddEnd = unkAddStart + "+val stayString = 2".length
        val unkAddTokens = resetTokens.filter { it.startIndex >= unkAddStart && it.endIndex <= unkAddEnd }
        assertEquals(2, unkAddTokens.size)
        assertEquals(SyntaxTokenKind.Punctuation, unkAddTokens[0].kind)
        assertEquals(SyntaxTokenKind.String, unkAddTokens[1].kind)
    }

    @Test
    fun handlesCopyModeHeadersOctopusCombinedHunksSurrogateOffsetsAndMarkdownDiff() {
        val richDiff = "diff --git a/src/A.kt b/src/B.kt\n" +
            "old mode 100644\n" +
            "new mode 100755\n" +
            "copy from src/A.kt\n" +
            "copy to src/B.kt\n" +
            "diff --git a/New.kt b/New.kt\n" +
            "new file mode 100644\n" +
            "--- /dev/null\n" +
            "+++ b/New.kt\n" +
            "@@ -0,0 +1,1 @@\n" +
            "+val rocket = \"🚀\" + 42\n" +
            "diff --git a/Gone.kt b/Gone.kt\n" +
            "deleted file mode 100644\n" +
            "--- a/Gone.kt\n" +
            "+++ /dev/null\n" +
            "@@ -1,1 +0,0 @@\n" +
            "-val gone = 99\n" +
            "diff --combined Octo.kt\n" +
            "@@@@ -1,1 -1,1 -1,1 +1,1 @@@@\n" +
            "---val octoOld = 1\n" +
            "+++val octoNew = 7\n"
        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, richDiff, "copy-mode-octopus-surrogate")

        for (header in listOf("old mode 100644", "new mode 100755", "copy from src/A.kt", "copy to src/B.kt", "new file mode 100644", "deleted file mode 100644")) {
            assertTrue(tokens.any { it.slice(richDiff) == header && it.kind == SyntaxTokenKind.Keyword }, "Missing header token: $header")
        }

        // Exact UTF-16 offset after surrogate-pair emoji `🚀`
        val numOffset = richDiff.indexOf("42")
        val numToken = tokens.first { it.startIndex == numOffset }
        assertEquals(SyntaxTokenKind.Number, numToken.kind)
        assertEquals("42", numToken.slice(richDiff))
        assertEquals(numOffset + 2, numToken.endIndex)

        // Octopus @@@@ combined hunk sub-highlighted as Kotlin
        val octoStart = richDiff.indexOf("+++val octoNew = 7")
        val octoEnd = octoStart + "+++val octoNew = 7".length
        val octoTokens = tokens.filter { it.startIndex >= octoStart && it.endIndex <= octoEnd }
        assertEquals("+++", octoTokens[0].slice(richDiff))
        assertEquals(SyntaxTokenKind.Punctuation, octoTokens[0].kind)
        assertTrue(octoTokens.any { it.slice(richDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(octoTokens.any { it.slice(richDiff) == "7" && it.kind == SyntaxTokenKind.Number })

        // Markdown diff: 1-level Markdown sub-highlighting without 2nd-level Kotlin fence recursion
        val mdDiff = "diff --git a/README.md b/README.md\n" +
            "--- a/README.md\n" +
            "+++ b/README.md\n" +
            "@@ -1,4 +1,4 @@\n" +
            " # Title\n" +
            " ```kotlin\n" +
            "-val old = 1\n" +
            "+val new = 2\n" +
            " ```\n"
        val mdTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, mdDiff, "diff-of-markdown")
        assertTrue(mdTokens.any { it.slice(mdDiff) == "Title" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(mdTokens.none { it.slice(mdDiff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(mdTokens.none { it.slice(mdDiff) == "2" && it.kind == SyntaxTokenKind.Number })
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

    @Test
    fun linearPerformanceOnRepeatedPrefixGitHeader() {
        val repeatedGitHeader = "diff --git a/" + " b/".repeat(12_000) + "\n"
        assertLinearPerformance(
            lexer = DiffSyntaxLexer,
            pathologicalInputs = listOf("12k-repeated-git-prefixes" to repeatedGitHeader),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun contextHunkExitsOnNonHunkLinesAndLongDashSeparatorsWithoutLeakingSubHighlighting() {
        val diff = "*** a/Main.kt\t2026-11-23\n" +
            "--- b/Main.kt\t2026-11-23\n" +
            "***************\n" +
            "*** 1,1 ****\n" +
            "  val a = 1\n" +
            "--- 1,1 ----\n" +
            "! val a = 2\n" +
            "-- \n" +
            "2.45.0\n" +
            "----------\n" +
            " val notHunk = 99\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diff, "context-exit-non-hunk")
        val sigStart = diff.indexOf("\n-- \n") + 1
        val sigTokens = tokens.filter { it.startIndex >= sigStart && it.endIndex <= sigStart + 3 }
        assertEquals(listOf("-", "- "), sigTokens.map { it.slice(diff) })
        assertEquals(listOf(SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword), sigTokens.map { it.kind })

        assertTrue(tokens.any { it.slice(diff) == "----------" && it.kind == SyntaxTokenKind.Punctuation })

        val proseStart = diff.indexOf(" val notHunk = 99")
        val proseEnd = proseStart + " val notHunk = 99".length
        val proseTokens = tokens.filter { it.startIndex >= proseStart && it.endIndex <= proseEnd }
        assertEquals(2, proseTokens.size)
        assertEquals(SyntaxTokenKind.Whitespace, proseTokens[0].kind)
        assertEquals(SyntaxTokenKind.Plain, proseTokens[1].kind)
        assertEquals("val notHunk = 99", proseTokens[1].slice(diff))

        // Repeat with ---------- directly after context hunk before 1-space prose
        val sepFirstDiff = "*** a/Main.kt\n" +
            "--- b/Main.kt\n" +
            "***************\n" +
            "*** 1,1 ****\n" +
            "  val a = 1\n" +
            "----------\n" +
            " val afterDash = 42\n"
        val sepTokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, sepFirstDiff, "context-dash-sep-exit")
        assertTrue(sepTokens.any { it.slice(sepFirstDiff) == "----------" && it.kind == SyntaxTokenKind.Punctuation })
        val afterDashStart = sepFirstDiff.indexOf(" val afterDash = 42")
        val afterDashTokens = sepTokens.filter { it.startIndex >= afterDashStart && it.endIndex <= afterDashStart + " val afterDash = 42".length }
        assertEquals(2, afterDashTokens.size)
        assertEquals(SyntaxTokenKind.Plain, afterDashTokens[1].kind)
    }

    @Test
    fun normalHunkTransitionsCleanlyOnLongDashSeparatorsAndContextHeaders() {
        val diff = "diff a/Old.py b/Old.py\n" +
            "1c1\n" +
            "< def old(): return 1\n" +
            "---\n" +
            "> def new(): return 2\n" +
            "----------\n" +
            "*** src/Next.kt\t2026-11-23\n" +
            "--- src/Next.kt\t2026-11-23\n" +
            "***************\n" +
            "*** 1,1 ****\n" +
            "! val ktOld = 1\n" +
            "--- 1,1 ----\n" +
            "! val ktNew = 2\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diff, "normal-dash-and-context-next")
        assertTrue(tokens.any { it.slice(diff) == "----------" && it.kind == SyntaxTokenKind.Punctuation })
        val nextStart = diff.indexOf("! val ktNew = 2")
        val nextEnd = nextStart + "! val ktNew = 2".length
        val nextTokens = tokens.filter { it.startIndex >= nextStart && it.endIndex <= nextEnd }
        assertTrue(nextTokens.any { it.slice(diff) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(nextTokens.any { it.slice(diff) == "2" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun linearPerformanceOnHugeCombinedHunkHeaderAndManyParents() {
        val hugeAtHeader = "@".repeat(50_000) + " -1,999999 +1,999999 " + "@".repeat(50_000) + "\n" + " \n".repeat(10_000)
        val manyParentsHeader = "@@@" + "@".repeat(120) + " " + "-1,999999 ".repeat(120) + "+1,999999 " + "@".repeat(123) + "\n" + " \n".repeat(10_000)
        assertLinearPerformance(
            lexer = DiffSyntaxLexer,
            pathologicalInputs = listOf(
                "100k-combined-at-header" to hugeAtHeader,
                "120-parents-combined-header" to manyParentsHeader,
            ),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun exitsCombinedModeCleanlyWhenHeaderHasExcessParentRanges() {
        val diff = "--- a/Main.kt\n" +
            "+++ b/Main.kt\n" +
            "@@@ -1,1 -1,1 -1,1 +1,2 @@@\n" +
            "  val keep = 1\n" +
            "++val added = 2\n" +
            "-- \n" +
            "2.45.0\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diff, "excess-combined-parents")
        val sigStart = diff.indexOf("\n-- \n") + 1
        val sigTokens = tokens.filter { it.startIndex >= sigStart && it.endIndex <= sigStart + 3 }
        assertEquals(listOf("-", "- "), sigTokens.map { it.slice(diff) })
        assertEquals(listOf(SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword), sigTokens.map { it.kind })
    }

    @Test
    fun supportsInitialTabContextDiffLinesWhileKeepingDashDashSignatureOutside() {
        val diff = "*** a/Main.kt\t2026-11-23\n" +
            "--- b/Main.kt\t2026-11-23\n" +
            "***************\n" +
            "*** 1,3 ****\n" +
            " \tval ctx = 4\n" +
            "-\tval old = 1\n" +
            "!\tval chg = 0\n" +
            "--- 1,3 ----\n" +
            " \tval ctx = 4\n" +
            "+\tval new = 2\n" +
            "!\tval chg = 3\n" +
            "-- \n" +
            "2.45.0\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DiffSyntaxLexer, diff, "context-initial-tab")

        for (line in listOf(" \tval ctx = 4", "-\tval old = 1", "+\tval new = 2", "!\tval chg = 3")) {
            val lineStart = diff.indexOf(line)
            val lineEnd = lineStart + line.length
            val lineTokens = tokens.filter { it.startIndex >= lineStart && it.endIndex <= lineEnd }
            assertTrue(lineTokens.any { it.slice(diff) == "val" && it.kind == SyntaxTokenKind.Keyword }, "Missing Kotlin Keyword in '$line'")
            assertTrue(lineTokens.any { it.kind == SyntaxTokenKind.Number }, "Missing Kotlin Number in '$line'")
            if (!line.startsWith(" ")) {
                assertEquals(line.substring(0, 1), lineTokens[0].slice(diff))
                assertEquals(SyntaxTokenKind.Punctuation, lineTokens[0].kind)
                assertEquals("\t", lineTokens[1].slice(diff))
                assertEquals(SyntaxTokenKind.Whitespace, lineTokens[1].kind)
            }
        }

        val sigStart = diff.indexOf("\n-- \n") + 1
        val sigTokens = tokens.filter { it.startIndex >= sigStart && it.endIndex <= sigStart + 3 }
        assertEquals(listOf("-", "- "), sigTokens.map { it.slice(diff) })
        assertEquals(listOf(SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword), sigTokens.map { it.kind })
    }

    @Test
    fun linearPerformanceOnExcessParentsWithSmallWidthAndLongPathManyHunks() {
        val narrowExcessParents = "@@@ " + "-1,999999 ".repeat(80) + "+1,999999 @@@\n" + " \n".repeat(10_000)
        val longPath = "x".repeat(50_000)
        val longPathManyHunks = "--- a/$longPath.kt\n+++ b/$longPath.kt\n" + "@@ -0,0 +0,0 @@\n".repeat(10_000)
        assertLinearPerformance(
            lexer = DiffSyntaxLexer,
            pathologicalInputs = listOf(
                "width2-80-parents" to narrowExcessParents,
                "50kb-path-10k-hunks" to longPathManyHunks,
            ),
            maxMillisPerInput = 300L,
        )
    }
}
