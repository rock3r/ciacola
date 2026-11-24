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
    fun subHighlightsFencedCodeBlocksJvmAndDataLanguages() {
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
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, code, "md-fenced-subhighlight-jvm-data")
        fun hasToken(text: String, kind: SyntaxTokenKind): Boolean =
            tokens.any { it.slice(code) == text && it.kind == kind }

        assertTrue(hasToken("val", SyntaxTokenKind.Keyword), "Expected Kotlin 'val' Keyword")
        assertTrue(hasToken("42", SyntaxTokenKind.Number), "Expected Kotlin '42' Number")
        assertTrue(hasToken("def", SyntaxTokenKind.Keyword), "Expected Python 'def' Keyword")
        assertTrue(hasToken("\"enabled\"", SyntaxTokenKind.Property), "Expected JSON property")
        assertTrue(hasToken("true", SyntaxTokenKind.Constant), "Expected JSON constant")
        assertTrue(hasToken("SELECT", SyntaxTokenKind.Keyword), "Expected SQL SELECT Keyword")
    }

    @Test
    fun subHighlightsFencedCodeBlocksWebAndScriptingLanguages() {
        val code = """
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
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, code, "md-fenced-subhighlight-web-script")
        fun hasToken(text: String, kind: SyntaxTokenKind): Boolean =
            tokens.any { it.slice(code) == text && it.kind == kind }

        assertTrue(hasToken("def", SyntaxTokenKind.Keyword), "Expected Ruby 'def' Keyword")
        assertTrue(hasToken("export", SyntaxTokenKind.Keyword), "Expected Shell export Keyword")
        assertTrue(hasToken("div", SyntaxTokenKind.Tag), "Expected HTML div Tag")
        assertTrue(hasToken("color", SyntaxTokenKind.Property), "Expected CSS color Property")
    }

    @Test
    fun subHighlightsFencedCodeBlocksSystemsLanguages() {
        val code = """
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

        val tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, code, "md-fenced-subhighlight-systems")
        fun hasToken(text: String, kind: SyntaxTokenKind): Boolean =
            tokens.any { it.slice(code) == text && it.kind == kind }

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
            # Heading After
            **bold**

            ~~~kotlin `backtick` ok
            val isFence = 2
            ~~~
        """.trimIndent() + "\n"
        val infoTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, infoStringRule, "md-info-backtick-rule")
        assertFalse(
            infoTokens.any { it.slice(infoStringRule).contains("foo`") && it.kind == SyntaxTokenKind.String },
            "Backtick run with backtick in info string and mismatched closing count must not open a fence or inline span",
        )
        assertTrue(
            infoTokens.any { it.slice(infoStringRule) == "Heading" && it.kind == SyntaxTokenKind.Keyword } &&
                infoTokens.any { it.slice(infoStringRule) == "After" && it.kind == SyntaxTokenKind.Keyword } &&
                infoTokens.any { it.slice(infoStringRule) == "bold" && it.kind == SyntaxTokenKind.Constant },
            "Lines after rejected backtick fence opener must remain normal Markdown",
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
        assertTrue(tokens.any { it.slice(indentedAndBq) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "3" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "After" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "quote" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(indentedAndBq) == "fence" && it.kind == SyntaxTokenKind.Plain })

        val noRecurseBq = "> ```kotlin\n> val q = 3\n> ```\n"
        val noRecurseTokens = assertLosslessInvariants(
            code = noRecurseBq,
            tokens = MarkdownSyntaxLexer.lex(noRecurseBq, allowNestedSubHighlighting = false),
            label = "md-bq-no-recurse",
        )
        assertFalse(noRecurseTokens.any { it.kind == SyntaxTokenKind.Keyword })
        assertTrue(noRecurseTokens.any { it.slice(noRecurseBq) == "val q = 3" && it.kind == SyntaxTokenKind.String })

        val tabCloseBq = "> ```kotlin\n> val q = 3\n>${'\t'}```\n# Heading\n"
        val tabCloseTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, tabCloseBq, "md-bq-tab-close")
        assertTrue(tabCloseTokens.any { it.slice(tabCloseBq) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tabCloseTokens.any { it.slice(tabCloseBq) == "3" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tabCloseTokens.any { it.slice(tabCloseBq) == "Heading" && it.kind == SyntaxTokenKind.Keyword })

        val blankMarkerMultiLine = "> ```kotlin\n> /* multi-line\n>\n> comment */\n> val b = 2\n> ```\n"
        val blankMarkerTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            blankMarkerMultiLine,
            "md-bq-blank-marker-multiline",
        )
        assertTrue(blankMarkerTokens.any { it.slice(blankMarkerMultiLine).contains("comment") && it.kind == SyntaxTokenKind.Comment })
        assertTrue(blankMarkerTokens.any { it.slice(blankMarkerMultiLine) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(blankMarkerTokens.any { it.slice(blankMarkerMultiLine) == "2" && it.kind == SyntaxTokenKind.Number })

        val blankSpaceMarkerFence = "> ```kotlin\n> val a = 1\n> \n> val b = 2\n> ```\n"
        val blankSpaceTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            blankSpaceMarkerFence,
            "md-bq-blank-space-marker",
        )
        assertEquals(2, blankSpaceTokens.count { it.slice(blankSpaceMarkerFence) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(blankSpaceTokens.any { it.slice(blankSpaceMarkerFence) == "2" && it.kind == SyntaxTokenKind.Number })

        val overIndentedTabCloseBq = "> ```kotlin\n> // `\n>   ${'\t'}```\n> val y = 2\n> ```\n`ok`\n"
        val overIndentedTokens = assertLosslessInvariants(
            lexer = MarkdownSyntaxLexer,
            code = overIndentedTabCloseBq,
            label = "md-bq-overindented-tab-close",
        )
        assertTrue(overIndentedTokens.any { it.slice(overIndentedTabCloseBq) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(overIndentedTokens.any { it.slice(overIndentedTabCloseBq) == "`ok`" && it.kind == SyntaxTokenKind.String })

        // 2-level nested blockquote fence + UTF-16 surrogate pair offset check
        val nestedBqSurrogate = "> > ```kotlin\n> > val rocket = \"🚀\"\n> > val n = 42\n> > ```\n"
        val bqSurrogateTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, nestedBqSurrogate, "md-bq2-surrogate")
        val rocketTok = bqSurrogateTokens.firstOrNull { it.slice(nestedBqSurrogate) == "🚀" }
        assertTrue(rocketTok != null && rocketTok.kind == SyntaxTokenKind.String)
        assertEquals(nestedBqSurrogate.indexOf("🚀"), rocketTok.startIndex)
        assertEquals(nestedBqSurrogate.indexOf("🚀") + "🚀".length, rocketTok.endIndex)
        val numTok = bqSurrogateTokens.firstOrNull { it.slice(nestedBqSurrogate) == "42" }
        assertTrue(numTok != null && numTok.kind == SyntaxTokenKind.Number)
        assertEquals(nestedBqSurrogate.indexOf("42"), numTok.startIndex)
        assertEquals(nestedBqSurrogate.indexOf("42") + 2, numTok.endIndex)

        // List-marker fenced block followed by inline code
        val listFence = "- ```kotlin\nval x = 1\n  ```\nAfter `inline`.\n"
        val listFenceTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, listFence, "md-list-fence")
        assertTrue(listFenceTokens.any { it.slice(listFence) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(listFenceTokens.any { it.slice(listFence) == "`inline`" && it.kind == SyntaxTokenKind.String })

        // Diff fenced block enforces 1-level-only non-recursion (Diff markers highlighted, Kotlin inside Diff NOT highlighted)
        val diffFence = """
            ```diff
            --- a/Main.kt
            +++ b/Main.kt
            @@ -1,1 +1,1 @@
            -val oldVal = 1
            +val newVal = 2
            ```
        """.trimIndent() + "\n"
        val diffTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, diffFence, "md-diff-fence-no-recurse")
        assertTrue(diffTokens.any { it.slice(diffFence) == "@@" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(diffTokens.any { it.slice(diffFence) == "-1,1 +1,1" && it.kind == SyntaxTokenKind.Number })
        assertTrue(diffTokens.any { it.slice(diffFence) == "val oldVal = 1" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(diffTokens.any { it.slice(diffFence) == "val newVal = 2" && it.kind == SyntaxTokenKind.String })
        assertFalse(
            diffTokens.any { it.slice(diffFence) == "val" && it.kind == SyntaxTokenKind.Keyword },
            "Diff inside Markdown must NOT recurse into Kotlin highlighting",
        )
        assertFalse(
            diffTokens.any { it.slice(diffFence) in setOf("1", "2") && it.kind == SyntaxTokenKind.Number },
            "Diff inside Markdown must NOT highlight Kotlin Number literals",
        )

        // Indented code after heading, thematic break, fence close, and blank blockquote line
        val afterHeading = "# Heading\n    codeAfterHeading\n"
        assertTrue(assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, afterHeading, "md-ic-heading")
            .any { it.slice(afterHeading) == "codeAfterHeading" && it.kind == SyntaxTokenKind.String })
        val afterRule = "---\n    codeAfterRule\n"
        assertTrue(assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, afterRule, "md-ic-rule")
            .any { it.slice(afterRule) == "codeAfterRule" && it.kind == SyntaxTokenKind.String })
        val afterFence = "```plain\nx\n```\n    codeAfterFence\n"
        assertTrue(assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, afterFence, "md-ic-fence")
            .any { it.slice(afterFence) == "codeAfterFence" && it.kind == SyntaxTokenKind.String })
        val afterBlankBq = "> text\n>\n>     codeAfterBlankBq\n"
        assertTrue(assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, afterBlankBq, "md-ic-bq")
            .any { it.slice(afterBlankBq) == "codeAfterBlankBq" && it.kind == SyntaxTokenKind.String })
    }

    @Test
    fun unmatchedInlineBacktickDoesNotCrossBlockBoundaries() {
        val listCase = "Literal ` backtick in item 1\n- Item 2 with `code` here\n"
        val listTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, listCase, "md-backtick-list-boundary")
        assertTrue(listTokens.any { it.slice(listCase) == "`" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(listTokens.any { it.slice(listCase) == "-" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(listTokens.any { it.slice(listCase) == "`code`" && it.kind == SyntaxTokenKind.String })

        val bqFenceCase = "> Literal ` backtick\n> ```js\n> const x = `tpl`;\n> ```\n"
        val bqTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, bqFenceCase, "md-backtick-bq-fence-boundary")
        assertTrue(bqTokens.any { it.slice(bqFenceCase) == "`" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(bqTokens.any { it.slice(bqFenceCase) == "const" && it.kind == SyntaxTokenKind.Keyword })
    }

    @Test
    fun setextUnderlinesOnlyApplyToImmediateParagraphLines() {
        val listThenDash = "- List item\n---\n"
        val listDashTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, listThenDash, "md-setext-after-list")
        assertTrue(listDashTokens.any { it.slice(listThenDash) == "List" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(listDashTokens.any { it.slice(listThenDash) == "item" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(listDashTokens.any { it.slice(listThenDash) == "---" && it.kind == SyntaxTokenKind.Punctuation })

        val listThenParaSetext = "- Item 1\nSetext Title\n============\n"
        val mixedTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, listThenParaSetext, "md-setext-after-list-item")
        assertTrue(mixedTokens.any { it.slice(listThenParaSetext) == "Item" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(mixedTokens.any { it.slice(listThenParaSetext) == "1" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(mixedTokens.any { it.slice(listThenParaSetext) == "Setext" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(mixedTokens.any { it.slice(listThenParaSetext) == "Title" && it.kind == SyntaxTokenKind.Keyword })

        val refDefThenDash = "[ref]: https://example.com\n---\n"
        val refTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, refDefThenDash, "md-setext-after-refdef")
        assertTrue(refTokens.any { it.slice(refDefThenDash) == "ref" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(refTokens.any { it.slice(refDefThenDash) == "https://example.com" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(refTokens.any { it.slice(refDefThenDash) == "---" && it.kind == SyntaxTokenKind.Punctuation })
    }

    @Test
    fun handlesParensInsideLinkQuotedTitlesAndUnicodePunctuationEmphasis() {
        val smileyTitle = "Click [here](https://example.com \"Say hi :)\") and (more)!\n"
        val smileyTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, smileyTitle, "md-link-title-smiley")
        assertTrue(smileyTokens.any { it.slice(smileyTitle) == "here" && it.kind == SyntaxTokenKind.Tag })
        assertTrue(smileyTokens.any { it.slice(smileyTitle) == "https://example.com" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(smileyTokens.any { it.slice(smileyTitle).contains("Say") && it.kind == SyntaxTokenKind.String })
        assertTrue(smileyTokens.any { it.slice(smileyTitle) == ")" && it.kind == SyntaxTokenKind.String })
        assertTrue(smileyTokens.any { it.slice(smileyTitle) == ")" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(smileyTokens.any { it.slice(smileyTitle).contains("more") && it.kind == SyntaxTokenKind.Plain })

        val openParenTitle = "Click [here](https://example.com \"part (1\") and **bold** (later)!\n"
        val openParenTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, openParenTitle, "md-link-title-open-paren")
        assertTrue(openParenTokens.any { it.slice(openParenTitle) == "here" && it.kind == SyntaxTokenKind.Tag })
        assertTrue(openParenTokens.any { it.slice(openParenTitle) == "bold" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(openParenTokens.any { it.slice(openParenTitle).contains("later") && it.kind == SyntaxTokenKind.Plain })

        val unicodePunct = "「_強調_」と¡_hola_!\n"
        val unicodeTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, unicodePunct, "md-unicode-punct-emphasis")
        assertTrue(unicodeTokens.any { it.slice(unicodePunct) == "強調" && it.kind == SyntaxTokenKind.Property })
        assertTrue(unicodeTokens.any { it.slice(unicodePunct) == "hola" && it.kind == SyntaxTokenKind.Property })

        val brokenThenValidPointy = "See [broken](<unclosed\nand [valid](<https://example.com/my page>)!\n"
        val brokenThenValidTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            brokenThenValidPointy,
            "md-broken-then-valid-pointy",
        )
        assertTrue(brokenThenValidTokens.any { it.slice(brokenThenValidPointy) == "valid" && it.kind == SyntaxTokenKind.Tag })
        assertTrue(brokenThenValidTokens.any { it.slice(brokenThenValidPointy) == "page" && it.kind == SyntaxTokenKind.Attribute })

        val quoteInsidePointy = "See [link](<https://example.com/a \"unclosed> \"real title\")!\n"
        val quotePointyTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            quoteInsidePointy,
            "md-quote-inside-pointy",
        )
        assertTrue(quotePointyTokens.any { it.slice(quoteInsidePointy) == "link" && it.kind == SyntaxTokenKind.Tag })
        assertTrue(quotePointyTokens.any { it.slice(quoteInsidePointy) == "\"unclosed" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(quotePointyTokens.any { it.slice(quoteInsidePointy) == "\"real" && it.kind == SyntaxTokenKind.String })
        assertTrue(quotePointyTokens.any { it.slice(quoteInsidePointy) == "title\"" && it.kind == SyntaxTokenKind.String })
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
    fun linearPerformanceOnUnclosedStars() {
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("unclosed-stars" to "*a ".repeat(8_000)),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnUnclosedUnderscores() {
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("unclosed-underscores" to "_a ".repeat(8_000)),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnUnclosedTildes() {
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("unclosed-tildes" to "~~a ".repeat(6_000)),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnDeadEmphasisStackDeactivation() {
        val mixedDelim = "*a ".repeat(4_000) + "_b ".repeat(4_000) + " c*".repeat(4_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("dead-emphasis-stack" to mixedDelim),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnActiveLinkStackDeactivation() {
        val nestedOpenBrackets = "[".repeat(8_000) + "[a](b)".repeat(8_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("active-link-stack" to nestedOpenBrackets),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnNestedImageLabels() {
        val nestedImages = "![x ".repeat(4_000) + "](u)".repeat(4_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("nested-image-labels" to nestedImages),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnNestedFootnotes() {
        val nestedFootnotes = "[^".repeat(8_000) + "]".repeat(8_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("nested-footnotes" to nestedFootnotes),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnNestedFootnotesWithSpace() {
        val nestedFootnotesWithSpace = "[^".repeat(8_000) + " " + "]".repeat(8_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("nested-footnotes-with-space" to nestedFootnotesWithSpace),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnUnclosedQuotedLinkTitles() {
        val unclosedQuotedTitle = "[a](u \"" + "x:y ".repeat(8_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("unclosed-quoted-link-titles" to unclosedQuotedTitle),
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
    fun handlesGfmExtendedAutolinksTildesEmphasisOpenersAndHtmlBlocks() {
        // 1. GFM extended autolinks + intraword '_' inside URLs
        val autolinks = "See www.example.com/a_b, http://example.com, (https://sub.example.org/p_(ok)), and user.name+tag@example.com.\n"
        val autoTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, autolinks, "md-gfm-autolinks")
        assertTrue(autoTokens.any { it.slice(autolinks) == "www.example.com/a_b" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(autoTokens.any { it.slice(autolinks) == "http://example.com" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(autoTokens.any { it.slice(autolinks) == "https://sub.example.org/p_(ok)" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(autoTokens.any { it.slice(autolinks) == "user.name+tag@example.com" && it.kind == SyntaxTokenKind.Attribute })
        assertFalse(autoTokens.any { it.slice(autolinks) == "a" && it.kind == SyntaxTokenKind.Property })

        // 2. GFM single-tilde ~x~, double-tilde ~~x~~, non-matching ~~~x~~~, and CommonMark openersBottom closer length
        val tildeAndOpeners = "~one~ ~~two~~ ~~~three~~~ and a*b c** d **e f****\n"
        val empTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, tildeAndOpeners, "md-tildes-and-openers")
        assertTrue(empTokens.any { it.slice(tildeAndOpeners) == "one" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(empTokens.any { it.slice(tildeAndOpeners) == "two" && it.kind == SyntaxTokenKind.Comment })
        assertFalse(empTokens.any { it.slice(tildeAndOpeners) == "three" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(empTokens.any { it.slice(tildeAndOpeners) == "b" && it.kind == SyntaxTokenKind.Property })
        assertTrue(empTokens.any { it.slice(tildeAndOpeners) == "c" && it.kind == SyntaxTokenKind.Property })
        assertTrue(empTokens.any { it.slice(tildeAndOpeners) == "d" && it.kind == SyntaxTokenKind.Property })
        assertTrue(empTokens.any { it.slice(tildeAndOpeners) == "e" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(empTokens.any { it.slice(tildeAndOpeners) == "f" && it.kind == SyntaxTokenKind.Constant })

        // 3. Unclosed inline <!-- must NOT swallow following heading
        val unclosedInlineComment = "type <!-- here\n# Next Heading\n"
        val commentTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, unclosedInlineComment, "md-unclosed-inline-comment")
        assertTrue(commentTokens.any { it.slice(unclosedInlineComment) == "Next" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(commentTokens.any { it.slice(unclosedInlineComment) == "Heading" && it.kind == SyntaxTokenKind.Keyword })

        // 4. CommonMark HTML blocks types 1-7 suppress Markdown emphasis/links/code inside them
        val htmlBlocks = """
            <pre>
            *not1* [n1](x) `c1`
            </pre>

            <script>
            const s = "*not2*";

            [n2](x)
            </script>

            <!-- line comment
            *not3* -->

            <?xml *not4* ?>
            <!DOCTYPE *not5*>
            <![CDATA[*not6*]]>

            <div>
            *not7* [n7](x) `c7`
            </div>

            <custom-widget>
            *not8* [n8](x)
            </custom-widget>

            *afterHtml*
        """.trimIndent() + "\n"
        val htmlTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, htmlBlocks, "md-html-blocks-1-7")
        for (idx in 1..8) {
            assertFalse(
                htmlTokens.any { it.slice(htmlBlocks).contains("not$idx") && it.kind == SyntaxTokenKind.Property },
                "HTML block content not$idx must not lex as Markdown emphasis",
            )
        }
        assertFalse(htmlTokens.any { it.slice(htmlBlocks) == "n7" && it.kind == SyntaxTokenKind.Tag })
        assertFalse(htmlTokens.any { it.slice(htmlBlocks) == "`c7`" && it.kind == SyntaxTokenKind.String })
        assertTrue(htmlTokens.any { it.slice(htmlBlocks) == "afterHtml" && it.kind == SyntaxTokenKind.Property })

        // Type 7 cannot interrupt a paragraph
        val type7Paragraph = "Paragraph line\n<custom-inline>\n*isEmphasis*\n"
        val type7ParaTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, type7Paragraph, "md-type7-para-interrupt")
        assertTrue(type7ParaTokens.any { it.slice(type7Paragraph) == "isEmphasis" && it.kind == SyntaxTokenKind.Property })
    }

    @Test
    fun linearPerformanceOnNestedDashLists() {
        val nestedDashLists = "- ".repeat(8_000) + "`x`"
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("nested-dash-lists" to nestedDashLists),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnNestedStarLists() {
        val nestedStarLists = "* ".repeat(8_000) + "x"
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("nested-star-lists" to nestedStarLists),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnUnclosedInlineHtmlComments() {
        val unclosedInlineComments = "text <!-- ".repeat(5_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("unclosed-inline-comments" to unclosedInlineComments),
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

    @Test
    fun handlesListMarkerTabAndMultiSpaceFencesBlockquoteHtmlTerminationAndSetextParity() {
        // N2 & P2-5: List-marker fences with tab stay Kotlin fences, while 5 spaces after '-' is indented code per CommonMark §5.2
        val tabListFence = "-${'\t'}```kotlin\nval x = 1\n```\nAfter `inline`.\n"
        val tabTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, tabListFence, "md-tab-list-fence")
        assertTrue(tabTokens.any { it.slice(tabListFence) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tabTokens.any { it.slice(tabListFence) == "After" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tabTokens.any { it.slice(tabListFence) == "`inline`" && it.kind == SyntaxTokenKind.String })

        val fiveSpaceListIndented = "-     ```kotlin\n\nAfter `inline`.\n"
        val fiveSpaceTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, fiveSpaceListIndented, "md-5space-list-indented")
        assertTrue(fiveSpaceTokens.any { it.slice(fiveSpaceListIndented) == "```kotlin" && it.kind == SyntaxTokenKind.String })
        assertFalse(fiveSpaceTokens.any { it.kind == SyntaxTokenKind.Type })
        assertTrue(fiveSpaceTokens.any { it.slice(fiveSpaceListIndented) == "After" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(fiveSpaceTokens.any { it.slice(fiveSpaceListIndented) == "`inline`" && it.kind == SyntaxTokenKind.String })

        // N3: Line-start <!-- and Type 4 <!DOCTYPE inside blockquote terminate when blockquote exits
        val bqType2Comment = "> <!-- note\n\nOutside `code`\n"
        val bqType2Tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, bqType2Comment, "md-bq-type2-exit")
        assertTrue(bqType2Tokens.any { it.slice(bqType2Comment).contains("note") && it.kind == SyntaxTokenKind.Comment })
        assertTrue(bqType2Tokens.any { it.slice(bqType2Comment) == "Outside" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(bqType2Tokens.any { it.slice(bqType2Comment) == "`code`" && it.kind == SyntaxTokenKind.String })

        val bqType4Doctype = "> <!DOCTYPE html\n> body\n\nOutside `code`\n"
        val bqType4Tokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, bqType4Doctype, "md-bq-type4-exit")
        assertTrue(bqType4Tokens.any { it.slice(bqType4Doctype) == "Outside" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(bqType4Tokens.any { it.slice(bqType4Doctype) == "`code`" && it.kind == SyntaxTokenKind.String })

        // N5: 1-2 char Setext underlines (--), no internal-space Setext (= =), and Type 7 paragraph sync
        val shortSetext = "`a\n--\nb`\n"
        val shortSetextTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, shortSetext, "md-short-setext-backtick")
        assertTrue(shortSetextTokens.any { it.slice(shortSetext) == "`a" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(shortSetextTokens.any { it.slice(shortSetext) == "--" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(shortSetextTokens.any { it.slice(shortSetext) == "b`" && it.kind == SyntaxTokenKind.Plain })

        val equalsAfterBlankThenType7 = "\n===\n<custom>\n`x`\n"
        val equalsTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, equalsAfterBlankThenType7, "md-equals-type7-sync")
        assertTrue(equalsTokens.any { it.slice(equalsAfterBlankThenType7) == "`x`" && it.kind == SyntaxTokenKind.String })

        val spacedEquals = "a\n= =\n"
        val spacedEqualsTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, spacedEquals, "md-spaced-equals")
        assertTrue(spacedEqualsTokens.any { it.slice(spacedEquals) == "a" && it.kind == SyntaxTokenKind.Plain })
    }

    @Test
    fun enforcesBlockBoundariesForEmphasisStrikethroughAndLinks() {
        val crossComment = "*a\n<!-- c -->\nb*\n"
        val commentTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, crossComment, "md-cross-comment")
        assertFalse(commentTokens.any { it.kind == SyntaxTokenKind.Property })

        val crossRawHtml = "*a\n<?x?>\nb*\n"
        val rawHtmlTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, crossRawHtml, "md-cross-raw-html")
        assertFalse(rawHtmlTokens.any { it.kind == SyntaxTokenKind.Property })

        val crossAtx = "*a\n# H\nb*\n"
        val atxTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, crossAtx, "md-cross-atx")
        assertFalse(atxTokens.any { it.kind == SyntaxTokenKind.Property })

        val crossList = "*a\n- item b*\n"
        val listTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, crossList, "md-cross-list")
        assertFalse(listTokens.any { it.kind == SyntaxTokenKind.Property })

        val crossLinkAtx = "[a\n# H\nb](https://example.com)\n"
        val linkAtxTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, crossLinkAtx, "md-cross-link-atx")
        assertFalse(linkAtxTokens.any { it.kind == SyntaxTokenKind.Tag })

        val multiLinePara = "*foo\nbar* and [link\ntext](https://example.com)\n"
        val paraTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, multiLinePara, "md-multiline-para")
        assertTrue(paraTokens.any { it.slice(multiLinePara) == "foo" && it.kind == SyntaxTokenKind.Property })
        assertTrue(paraTokens.any { it.slice(multiLinePara) == "bar" && it.kind == SyntaxTokenKind.Property })
        assertTrue(paraTokens.any { it.slice(multiLinePara) == "link" && it.kind == SyntaxTokenKind.Tag })
        assertTrue(paraTokens.any { it.slice(multiLinePara) == "text" && it.kind == SyntaxTokenKind.Tag })
    }

    @Test
    fun linearPerformanceOnRepeatedWwwUnderscoreDomains() {
        val repeatedWww = "www._".repeat(10_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("repeated-www-underscore" to repeatedWww),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun linearPerformanceOnRepeatedHttpsUnderscoreDomains() {
        val repeatedHttps = "https://a_".repeat(5_000)
        assertLinearPerformance(
            lexer = MarkdownSyntaxLexer,
            pathologicalInputs = listOf("repeated-https-underscore" to repeatedHttps),
            maxMillisPerInput = 300L,
        )
    }

    @Test
    fun handlesHttpsBarrierInlineHtmlBlockInterruptorsListSetextGuardAndTabBlockquotes() {
        // P2-1: Second https:// URL after '_' still autolinks cleanly
        val chainedHttp = "https://a_https://example.com\n"
        val chainedHttpTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, chainedHttp, "md-http-barrier")
        assertTrue(chainedHttpTokens.any { it.slice(chainedHttp) == "https://example.com" && it.kind == SyntaxTokenKind.Attribute })

        // P2-2: Multi-line inline HTML comments and inline HTML tags stop at block-interrupting lines (such as fences)
        val inlineCommentBeforeFence = "a <!--\n```\ncode -->\n```\ntail\n"
        val commentFenceTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, inlineCommentBeforeFence, "md-inline-comment-fence")
        assertFalse(commentFenceTokens.any { it.kind == SyntaxTokenKind.Comment })
        assertTrue(commentFenceTokens.any { it.slice(inlineCommentBeforeFence) == "tail" && it.kind == SyntaxTokenKind.Plain })

        val inlineTagBeforeFence = "a <div x\n```\ncode>\n```\ntail\n"
        val tagFenceTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, inlineTagBeforeFence, "md-inline-tag-fence")
        assertFalse(tagFenceTokens.any { it.kind == SyntaxTokenKind.Tag })
        assertTrue(tagFenceTokens.any { it.slice(inlineTagBeforeFence) == "tail" && it.kind == SyntaxTokenKind.Plain })

        // P2-3: Setext underlines must NOT trigger after a list marker
        val listEquals = "Foo\n- ===\n    bar\n"
        val listEqualsTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, listEquals, "md-list-equals-no-setext")
        assertFalse(listEqualsTokens.any { it.slice(listEquals) == "===" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(listEqualsTokens.any { it.slice(listEquals) == "bar" && it.kind == SyntaxTokenKind.Plain })

        val listDashes = "Foo\n* --\n    bar\n"
        val listDashTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, listDashes, "md-list-dash-no-setext")
        assertFalse(listDashTokens.any { it.slice(listDashes) == "--" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(listDashTokens.any { it.slice(listDashes) == "bar" && it.kind == SyntaxTokenKind.Plain })

        val nestedDashSetext = "Foo\n- -\n    bar\n"
        val nestedDashTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, nestedDashSetext, "md-nested-dash-no-setext")
        assertTrue(nestedDashTokens.any { it.slice(nestedDashSetext) == "Foo" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(nestedDashTokens.any { it.slice(nestedDashSetext) == "bar" && it.kind == SyntaxTokenKind.Plain })

        // P2-4: Tab-separated blockquote markers match between backtick index and lexer
        val mismatchedTabBq = "> `a\n>${'\t'}> b`\n"
        val mismatchedTabTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, mismatchedTabBq, "md-tab-bq-mismatch")
        assertFalse(mismatchedTabTokens.any { it.kind == SyntaxTokenKind.String })

        val matchingTabBq = ">${'\t'}> `a\n> > b`\n"
        val matchingTabTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, matchingTabBq, "md-tab-bq-match")
        assertTrue(matchingTabTokens.any { it.slice(matchingTabBq) == "`a\n> > b`" && it.kind == SyntaxTokenKind.String })
    }

    @Test
    fun handlesTabBlockquoteFencesNonColZeroMarkerTabsAndMultilineBlockquoteHtmlTags() {
        // N-1: fenced code block inside tab-separated blockquote must not let inner backticks leak into paragraph backtick pairing
        val tabBqFence = ">${'\t'}> ```\n>${'\t'}> a\n>${'\t'}> ```\n`x`\n"
        val tabBqFenceTokens = assertValidSnippetHasNoInvalidTokens(MarkdownSyntaxLexer, tabBqFence, "md-n1-tab-bq-fence")
        assertTrue(tabBqFenceTokens.any { it.slice(tabBqFence) == "`x`" && it.kind == SyntaxTokenKind.String })

        // N-2: tab column expansion after non-col-0 '>' markers
        val leadingTwoSpacesThenTab = "  >${'\t'}  code\n"
        val twoSpaceTabTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            leadingTwoSpacesThenTab,
            "md-n2-two-space-tab",
        )
        assertTrue(twoSpaceTabTokens.any { it.slice(leadingTwoSpacesThenTab) == "code" && it.kind == SyntaxTokenKind.Plain })

        val nestedMarkerTab = "> >${'\t'}  code\n"
        val nestedMarkerTabTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            nestedMarkerTab,
            "md-n2-nested-marker-tab",
        )
        assertTrue(nestedMarkerTabTokens.any { it.slice(nestedMarkerTab) == "code" && it.kind == SyntaxTokenKind.Plain })

        val threeSpaceTab = "   >${'\t'} code\n"
        val threeSpaceTabTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            threeSpaceTab,
            "md-n2-three-space-tab",
        )
        assertTrue(threeSpaceTabTokens.any { it.slice(threeSpaceTab) == "code" && it.kind == SyntaxTokenKind.String })

        val nestedFenceClosingTab = "> > ```kotlin\n> > val x = 1\n> >${'\t'}  ```\nAfter `ok`.\n"
        val nestedFenceClosingTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            nestedFenceClosingTab,
            "md-n2-nested-fence-close-tab",
        )
        assertTrue(nestedFenceClosingTokens.any { it.slice(nestedFenceClosingTab) == "val" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(nestedFenceClosingTokens.any { it.slice(nestedFenceClosingTab) == "After" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(nestedFenceClosingTokens.any { it.slice(nestedFenceClosingTab) == "`ok`" && it.kind == SyntaxTokenKind.String })

        // N-3: multiline HTML tags & unclosed '<' inside blockquotes
        val unclosedLtInBq = "> if a <b\n> then\n"
        val unclosedLtTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            unclosedLtInBq,
            "md-n3-unclosed-lt-bq",
        )
        assertFalse(unclosedLtTokens.any { it.kind == SyntaxTokenKind.Tag })
        assertTrue(unclosedLtTokens.any { it.slice(unclosedLtInBq) == ">" && it.startIndex == 0 && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(
            unclosedLtTokens.any {
                it.slice(unclosedLtInBq) == ">" &&
                    it.startIndex == unclosedLtInBq.indexOf("> then") &&
                    it.kind == SyntaxTokenKind.Punctuation
            },
        )
        assertTrue(unclosedLtTokens.any { it.slice(unclosedLtInBq).contains("then") && it.kind == SyntaxTokenKind.Plain })

        val multilineInlineTagInBq = "> <a\n> href=\"x\">hi</a>\n"
        val multilineInlineTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            multilineInlineTagInBq,
            "md-n3-multiline-inline-tag-bq",
        )
        val secondLineMarkerIdx = multilineInlineTagInBq.indexOf("> href")
        assertTrue(
            multilineInlineTokens.any {
                it.slice(multilineInlineTagInBq) == ">" &&
                    it.startIndex == secondLineMarkerIdx &&
                    it.kind == SyntaxTokenKind.Punctuation
            },
        )
        assertTrue(multilineInlineTokens.any { it.slice(multilineInlineTagInBq) == "href" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(multilineInlineTokens.any { it.slice(multilineInlineTagInBq) == "\"x\"" && it.kind == SyntaxTokenKind.String })
        assertTrue(multilineInlineTokens.any { it.slice(multilineInlineTagInBq) == "hi" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(multilineInlineTokens.any { it.slice(multilineInlineTagInBq) == "a" && it.kind == SyntaxTokenKind.Tag })

        val multilineHtmlBlockTagInBq = "> <div\n> class=\"x\">\n> *not em*\n"
        val multilineHtmlBlockTokens = assertValidSnippetHasNoInvalidTokens(
            MarkdownSyntaxLexer,
            multilineHtmlBlockTagInBq,
            "md-n3-multiline-block-tag-bq",
        )
        assertTrue(multilineHtmlBlockTokens.any { it.slice(multilineHtmlBlockTagInBq) == "div" && it.kind == SyntaxTokenKind.Tag })
        assertTrue(multilineHtmlBlockTokens.any { it.slice(multilineHtmlBlockTagInBq) == "class" && it.kind == SyntaxTokenKind.Attribute })
        assertTrue(multilineHtmlBlockTokens.any { it.slice(multilineHtmlBlockTagInBq) == "\"x\"" && it.kind == SyntaxTokenKind.String })
        assertTrue(multilineHtmlBlockTokens.any { it.slice(multilineHtmlBlockTagInBq) == "*not" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(multilineHtmlBlockTokens.any { it.slice(multilineHtmlBlockTagInBq) == "em*" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(
            multilineHtmlBlockTokens.any {
                it.slice(multilineHtmlBlockTagInBq) == ">" &&
                    it.startIndex == multilineHtmlBlockTagInBq.indexOf("> class") &&
                    it.kind == SyntaxTokenKind.Punctuation
            },
        )
        assertFalse(multilineHtmlBlockTokens.any { it.kind == SyntaxTokenKind.Property })
    }
}
