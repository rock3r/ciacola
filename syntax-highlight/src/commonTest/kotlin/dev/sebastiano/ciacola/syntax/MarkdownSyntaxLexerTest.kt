package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.MarkdownSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.MarkdownOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownSyntaxLexerTest {

    @Test
    fun matchesDualOracleFixtures() {
        assertAllOracleFixtures(MarkdownOracleFixtures.all)
    }

    @Test
    fun classifiesHeadingsThematicBreaksListsBlockquotesTablesHtmlAndInlineSyntax() {
        val code = """
            # H1 Title
            ### H3 Closed ###
            ###### H6

            Setext Heading
            --------------

            ***
            ___
            - - -

            - Bullet
            * Star item
            + Plus item
            - [ ] Unchecked
            - [x] Checked
            - [X] Upper checked
            1. Ordered dot
            2) Ordered paren

            > Outer quote
            > > Nested **bold** and *em*

            Inline `code` and ``code ` inside`` plus ~~deleted~~ and foo_bar_baz and \*esc\*.
            Link [label](https://en.wikipedia.org/wiki/Foo_(bar) "wiki") and ![pic](<my image.png>).
            Ref [click][myref] and [collapsed][] and footnote[^1].
            [myref]: https://example.com "Ref"
            [^1]: Footnote body

            | Left | Center | Right |
            | :--- | :---: | ---: |
            | A\|B | `x` | <https://example.com> |

            <!-- comment -->
            <a href="https://example.com" data-id='1'>hi</a><br/> &amp; &#123; &#x1F600;
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, code, "md-comprehensive")

        fun kindOf(snippet: String, occurrence: Int = 0): SyntaxTokenKind {
            var count = 0
            for (tok in tokens) {
                if (tok.slice(code) == snippet) {
                    if (count == occurrence) return tok.kind
                    count++
                }
            }
            error("Token '$snippet' (occurrence #$occurrence) not found in tokens: $tokens")
        }

        assertEquals(SyntaxTokenKind.Keyword, kindOf("H1"))
        assertEquals(SyntaxTokenKind.Keyword, kindOf("Title"))
        assertEquals(SyntaxTokenKind.Keyword, kindOf("H3"))
        assertEquals(SyntaxTokenKind.Keyword, kindOf("Closed"))
        assertEquals(SyntaxTokenKind.Punctuation, kindOf("###", occurrence = 1))
        assertEquals(SyntaxTokenKind.Keyword, kindOf("H6"))
        assertEquals(SyntaxTokenKind.Keyword, kindOf("Setext"))
        assertEquals(SyntaxTokenKind.Keyword, kindOf("Heading"))
        assertEquals(SyntaxTokenKind.Punctuation, kindOf("--------------"))
        assertEquals(SyntaxTokenKind.Punctuation, kindOf("***"))
        assertEquals(SyntaxTokenKind.Punctuation, kindOf("___"))
        assertEquals(SyntaxTokenKind.Punctuation, kindOf("- - -"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("x"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("X"))
        assertEquals(SyntaxTokenKind.Punctuation, kindOf("1."))
        assertEquals(SyntaxTokenKind.Punctuation, kindOf("2)"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("bold"))
        assertEquals(SyntaxTokenKind.Property, kindOf("em"))
        assertEquals(SyntaxTokenKind.String, kindOf("`code`"))
        assertEquals(SyntaxTokenKind.String, kindOf("``code ` inside``"))
        assertEquals(SyntaxTokenKind.Comment, kindOf("deleted"))
        assertEquals(SyntaxTokenKind.Plain, kindOf("foo_bar_baz"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("\\*"))
        assertEquals(SyntaxTokenKind.Tag, kindOf("label"))
        assertEquals(SyntaxTokenKind.Attribute, kindOf("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals(SyntaxTokenKind.String, kindOf("\"wiki\""))
        assertEquals(SyntaxTokenKind.Tag, kindOf("pic"))
        assertEquals(SyntaxTokenKind.Attribute, kindOf("my"))
        assertEquals(SyntaxTokenKind.Attribute, kindOf("image.png"))
        assertEquals(SyntaxTokenKind.Tag, kindOf("click"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("myref"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("^1"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("\\|"))
        assertEquals(SyntaxTokenKind.Comment, kindOf("<!-- comment -->"))
        assertEquals(SyntaxTokenKind.Tag, kindOf("a"))
        assertEquals(SyntaxTokenKind.Attribute, kindOf("href"))
        assertEquals(SyntaxTokenKind.String, kindOf("\"https://example.com\""))
        assertEquals(SyntaxTokenKind.Constant, kindOf("&amp;"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("&#123;"))
        assertEquals(SyntaxTokenKind.Constant, kindOf("&#x1F600;"))
    }

    @Test
    fun subHighlightsFencedCodeBlocksOneLevelDeepAcrossMultipleLanguages() {
        val code = """
            ```kotlin title="App.kt"
            val answer = 42
            ```

            ```python
            def greet(name: str) -> str:
                return f"hi {name}"
            ```

            ```json
            {"enabled": true, "count": 3}
            ```

            ```sql
            SELECT id FROM users WHERE active = TRUE;
            ```

            ```ruby
            def hello(x) = puts x
            ```

            ```shell-script
            export APP="demo"
            ```

            ```html
            <div class="box">ok</div>
            ```

            ```css
            .box { color: #ff0; }
            ```

            ```rust
            fn main() { let x = 1; }
            ```

            ```go
            func main() { return }
            ```

            ~~~c++
            auto v = 1;
            ~~~

            ```c#
            var x = "hi";
            ```
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, code, "md-fenced-subhighlight")

        fun hasToken(text: String, kind: SyntaxTokenKind): Boolean =
            tokens.any { it.slice(code) == text && it.kind == kind }

        assertTrue(hasToken("val", SyntaxTokenKind.Keyword), "Expected Kotlin 'val' Keyword")
        assertTrue(hasToken("42", SyntaxTokenKind.Number), "Expected Kotlin '42' Number")
        assertTrue(hasToken("def", SyntaxTokenKind.Keyword), "Expected Python/Ruby 'def' Keyword")
        assertTrue(hasToken("\"enabled\"", SyntaxTokenKind.Property), "Expected JSON property")
        assertTrue(hasToken("true", SyntaxTokenKind.Constant), "Expected JSON constant")
        assertTrue(hasToken("SELECT", SyntaxTokenKind.Keyword), "Expected SQL SELECT Keyword")
        assertTrue(hasToken("export", SyntaxTokenKind.Keyword), "Expected Shell export Keyword")
        assertTrue(hasToken("div", SyntaxTokenKind.Tag), "Expected HTML div Tag")
        assertTrue(hasToken("color", SyntaxTokenKind.Property), "Expected CSS color Property")
        assertTrue(hasToken("fn", SyntaxTokenKind.Keyword), "Expected Rust fn Keyword")
        assertTrue(hasToken("func", SyntaxTokenKind.Keyword), "Expected Go func Keyword")
        assertTrue(hasToken("auto", SyntaxTokenKind.Keyword), "Expected C++ auto Keyword")
        assertTrue(hasToken("var", SyntaxTokenKind.Keyword), "Expected C# var Keyword")
    }

    @Test
    fun enforcesFenceClosingRulesInfoStringRulesAndNoNestedRecursion() {
        // 1. 4-backtick fence wrapping 3-backtick inner markdown & kotlin does NOT close early or recurse
        val nestedMd = """
            ````markdown
            ```kotlin
            val insideMarkdown = 1
            ```
            ````
        """.trimIndent() + "\n"
        val nestedTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, nestedMd, "md-self-nested")
        assertFalse(
            nestedTokens.any { it.slice(nestedMd) == "val" && it.kind == SyntaxTokenKind.Keyword },
            "Nested markdown fence must NOT recurse into inner Kotlin fence: $nestedTokens",
        )
        assertTrue(
            nestedTokens.any { it.kind == SyntaxTokenKind.String && it.slice(nestedMd).contains("val insideMarkdown = 1") },
            "Nested markdown fence body should remain String tokens",
        )

        // 2. 4-backtick Kotlin fence containing inner triple backticks in comment
        val fourTickKotlin = """
            ````kotlin {1,2}
            // ``` not closing
            val ok = true
            ````
        """.trimIndent() + "\n"
        val fourTickTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, fourTickKotlin, "md-4tick-kt")
        assertTrue(fourTickTokens.any { it.slice(fourTickKotlin) == "// ``` not closing" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(fourTickTokens.any { it.slice(fourTickKotlin) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(fourTickTokens.any { it.slice(fourTickKotlin) == "true" && it.kind == SyntaxTokenKind.Constant })

        // 3. CommonMark rule: backtick fence info string cannot contain backticks; tilde fence CAN
        val infoStringRule = """
            ```foo`bar
            val notFence = 1

            ~~~kotlin `backtick` ok
            val isFence = 2
            ~~~
        """.trimIndent() + "\n"
        val infoTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, infoStringRule, "md-info-backtick-rule")
        assertFalse(
            infoTokens.any { it.slice(infoStringRule).contains("notFence") && it.kind == SyntaxTokenKind.Keyword },
        )
        assertTrue(
            infoTokens.any { it.slice(infoStringRule) == "val" && it.kind == SyntaxTokenKind.Keyword },
            "Tilde fence with backticks in info string must still sub-highlight Kotlin",
        )

        // 4. Unknown fence language falls back losslessly to String tokens
        val unknownLang = """
            ```unknownlang
            some code here
            ```
        """.trimIndent() + "\n"
        val unknownTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, unknownLang, "md-unknown-lang")
        assertTrue(unknownTokens.any { it.slice(unknownLang) == "some code here" && it.kind == SyntaxTokenKind.String })

        // 5. Unclosed fence extends cleanly to EOF
        val unclosedFence = "```kotlin\nval unclosed = 99"
        val unclosedTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, unclosedFence, "md-unclosed-fence")
        assertTrue(unclosedTokens.any { it.slice(unclosedFence) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(unclosedTokens.any { it.slice(unclosedFence) == "99" && it.kind == SyntaxTokenKind.Number })

        // 6. allowNestedSubHighlighting = false suppresses sub-highlighting for fenced blocks & frontmatter
        val noRecurseDoc = "---\ntitle: Demo\n---\n\n```kotlin\nval x = 1\n```\n"
        val noRecurseTokens = assertLosslessInvariants(
            code = noRecurseDoc,
            tokens = MarkdownSyntaxLexer.lex(noRecurseDoc, allowNestedSubHighlighting = false),
            label = "md-no-recurse-flag",
        )
        assertFalse(noRecurseTokens.any { it.kind == SyntaxTokenKind.Keyword })
        assertFalse(noRecurseTokens.any { it.kind == SyntaxTokenKind.Property })
        assertTrue(noRecurseTokens.any { it.slice(noRecurseDoc) == "val x = 1" && it.kind == SyntaxTokenKind.String })
    }

    @Test
    fun handlesYamlAndTomlFrontmatterAndUnclosedDashAtOffsetZero() {
        val yamlFm = "\uFEFF---\ntitle: Ciacola\ncount: 3\n---\n# Body\n"
        val yamlTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, yamlFm, "md-yaml-fm")
        assertEquals(SyntaxTokenKind.Whitespace, yamlTokens.first().kind)
        assertTrue(yamlTokens.any { it.slice(yamlFm) == "title" && it.kind == SyntaxTokenKind.Property })
        assertTrue(yamlTokens.any { it.slice(yamlFm) == "3" && it.kind == SyntaxTokenKind.Number })
        assertTrue(yamlTokens.any { it.slice(yamlFm) == "Body" && it.kind == SyntaxTokenKind.Keyword })

        val yamlDotClose = "---\nkey: true\n...\nText\n"
        val dotTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, yamlDotClose, "md-yaml-fm-dots")
        assertTrue(dotTokens.any { it.slice(yamlDotClose) == "key" && it.kind == SyntaxTokenKind.Property })
        assertTrue(dotTokens.any { it.slice(yamlDotClose) == "true" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(dotTokens.any { it.slice(yamlDotClose) == "Text" && it.kind == SyntaxTokenKind.Plain })

        val tomlFm = "+++\ntitle = \"Deck\"\nport = 8080\n+++\n# Heading\n"
        val tomlTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, tomlFm, "md-toml-fm")
        assertTrue(tomlTokens.any { it.slice(tomlFm) == "title" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tomlTokens.any { it.slice(tomlFm) == "\"Deck\"" && it.kind == SyntaxTokenKind.String })
        assertTrue(tomlTokens.any { it.slice(tomlFm) == "8080" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tomlTokens.any { it.slice(tomlFm) == "Heading" && it.kind == SyntaxTokenKind.Keyword })

        // Unclosed '---' at offset 0 must be treated as thematic break, NOT swallow whole document
        val unclosedDash = "---\n# Still A Heading\n*em*\n"
        val unclosedTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, unclosedDash, "md-unclosed-fm")
        assertEquals(SyntaxTokenKind.Punctuation, unclosedTokens.first().kind)
        assertTrue(unclosedTokens.any { it.slice(unclosedDash) == "Still" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(unclosedTokens.any { it.slice(unclosedDash) == "Heading" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(unclosedTokens.any { it.slice(unclosedDash) == "em" && it.kind == SyntaxTokenKind.Property })
    }

    @Test
    fun handlesIndentedCodeBlocksAndBlockquoteFences() {
        val indentedAndBq = """
            Paragraph line
                not indented code

                val a = 1
                val b = 2

            > ```kotlin
            > val q = 3
            > ```
            > After quote fence
        """.trimIndent() + "\n"
        val tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, indentedAndBq, "md-indented-and-bq")
        assertTrue(tokens.any { it.slice(indentedAndBq) == "not" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "indented" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "code" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "val a = 1" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "val b = 2" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "val q = 3" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "After" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "quote" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "fence" && it.kind == SyntaxTokenKind.Plain })
    }

    @Test
    fun handlesContainerConcatenationBomCrlfUnicodeAndLoneSurrogates() {
        assertContainerTokenConcatenation(
            lexer = MarkdownSyntaxLexer,
            containers = listOf(
                "`inline`" to SyntaxTokenKind.String,
                "``with ` tick``" to SyntaxTokenKind.String,
                "<!-- c -->" to SyntaxTokenKind.Comment,
                "```plain\ncode\n```\n" to null,
                "---\na: 1\n---\n" to null,
            ),
            tails = listOf(" after", " `next`", "<!-- b -->", "\n# H1\n"),
            label = "markdown",
        )

        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val tricky = listOf(
            "\uFEFF# Título 🎉\r\nTexto con **negrita** y `código 𠮷`\r\n",
            "emoji 🎉 $loneHigh end",
            "𠮷$loneLow",
            "# Heading $loneHigh",
        )
        for ((idx, sample) in tricky.withIndex()) {
            assertLosslessInvariants(MarkdownSyntaxLexer, sample, "md-unicode#$idx")
        }
    }

    @Test
    fun prefixTruncationMalformedAndFuzzAreLossless() {
        val sample = """
            ---
            title: Demo
            ---

            # Title *em* `code`

            - [x] Task [link](https://example.com "t")
            > ```kotlin
            > val x = 1
            > ```
            | A | B |
            | --- | --- |
            | 1 | 2 |
        """.trimIndent()
        assertPrefixTruncationSafety(MarkdownSyntaxLexer, sample, maxStep = 2)
        assertMalformedAndFuzzSafety(MarkdownSyntaxLexer, seed = 0x4D444B57L, iterations = 60)
    }

    @Test
    fun linearPerformanceOnUnclosedBackticksAndAlternatingRuns() {
        val unclosedSingle = "`" + "a".repeat(25_000)
        val repeatedDouble = "``".repeat(5_000)
        val spacedDouble = "`` ".repeat(5_000)
        val alternatingRuns = buildString {
            for (k in 1..400) {
                repeat(k % 17 + 1) { append('`') }
                append("x ")
            }
        }
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-single-backtick" to unclosedSingle,
                "repeated-double-backticks" to repeatedDouble,
                "spaced-double-backticks" to spacedDouble,
                "alternating-backtick-runs" to alternatingRuns,
            ),
            maxMillisPerInput = 350L,
        )
    }

    @Test
    fun linearPerformanceOnUnclosedEmphasisAndTildes() {
        val unclosedStars = "*a ".repeat(8_000)
        val unclosedUnderscores = "_a ".repeat(8_000)
        val unclosedTildes = "~~a ".repeat(6_000)

        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-stars" to unclosedStars,
                "unclosed-underscores" to unclosedUnderscores,
                "unclosed-tildes" to unclosedTildes,
            ),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnUnclosedLinksAndHtml() {
        val unclosedBrackets = "[a](b ".repeat(6_000)
        val unclosedImages = "![a".repeat(6_000)
        val unclosedHtmlTags = "<a \"".repeat(5_000)
        val unclosedHtmlComments = "<!-- ".repeat(5_000)

        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-brackets" to unclosedBrackets,
                "unclosed-images" to unclosedImages,
                "unclosed-html-tags" to unclosedHtmlTags,
                "unclosed-html-comments" to unclosedHtmlComments,
            ),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnNestedBlockquotesHeadingsAndGiantTables() {
        val deepBlockquotes = "> ".repeat(2_000) + "deep"
        val repeatedHeadings = "# Heading\n".repeat(4_000)
        val giantTable = "| A | B | C |\n| --- | :---: | ---: |\n" + "| 1 | `x` | **y** |\n".repeat(2_000)

        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf(
                "deep-blockquotes" to deepBlockquotes,
                "repeated-headings" to repeatedHeadings,
                "giant-table" to giantTable,
            ),
            maxMillisPerInput = 300L,
        )
    }
}
