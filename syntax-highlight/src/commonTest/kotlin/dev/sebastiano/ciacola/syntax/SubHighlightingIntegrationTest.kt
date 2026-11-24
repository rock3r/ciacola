package dev.sebastiano.ciacola.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubHighlightingIntegrationTest {

    private fun tokensWithin(
        code: String,
        tokens: List<SyntaxToken>,
        lineSnippet: String,
    ): List<SyntaxToken> {
        val start = code.indexOf(lineSnippet)
        assertTrue(start >= 0, "Expected snippet '$lineSnippet' in:\n$code")
        val end = start + lineSnippet.length
        return tokens.filter { it.startIndex >= start && it.endIndex <= end }
    }

    @Test
    fun diffOfMarkdownWithKotlinFenceHighlightsMarkdownOnlyWithoutKotlinRecursion() {
        val diff = """
            diff --git a/docs/guide.md b/docs/guide.md
            index 1111111..2222222 100644
            --- a/docs/guide.md
            +++ b/docs/guide.md
            @@ -1,6 +1,6 @@
            -# Old Guide
            +# New Guide
             Paragraph with *emphasis* and **bold**.
             ```kotlin
            -val oldAnswer = 41
            +val newAnswer = 42
             ```
        """.trimIndent() + "\n"

        val lexer = SyntaxHighlighter.lexer(SyntaxLanguage.Diff)
        val tokens = assertValidSnippetHasNoInvalidTokens(lexer, diff, "diff-of-md-with-kt-fence")

        // 1st-level Markdown highlighting inside the diff MUST be active:
        val addedHeading = tokensWithin(diff, tokens, "+# New Guide")
        assertEquals("+", addedHeading.first().slice(diff))
        assertEquals(SyntaxTokenKind.Punctuation, addedHeading.first().kind)
        assertTrue(addedHeading.any { it.slice(diff) == "New" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(addedHeading.any { it.slice(diff) == "Guide" && it.kind == SyntaxTokenKind.Keyword })

        val contextEm = tokensWithin(diff, tokens, " Paragraph with *emphasis* and **bold**.")
        assertTrue(contextEm.any { it.slice(diff) == "emphasis" && it.kind == SyntaxTokenKind.Property })
        assertTrue(contextEm.any { it.slice(diff) == "bold" && it.kind == SyntaxTokenKind.Constant })

        val fenceOpen = tokensWithin(diff, tokens, " ```kotlin")
        assertTrue(fenceOpen.any { it.slice(diff) == "```" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(fenceOpen.any { it.slice(diff) == "kotlin" && it.kind == SyntaxTokenKind.Type })

        // 2nd-level Kotlin sub-highlighting MUST NOT run inside the fenced body:
        assertFalse(
            tokens.any { it.slice(diff) == "val" && it.kind == SyntaxTokenKind.Keyword },
            "Diff -> Markdown -> Kotlin must NOT highlight 'val' as Kotlin Keyword: $tokens",
        )
        assertFalse(
            tokens.any { (it.slice(diff) == "41" || it.slice(diff) == "42") && it.kind == SyntaxTokenKind.Number },
            "Diff -> Markdown -> Kotlin must NOT highlight numbers inside fenced body: $tokens",
        )
        val deletedBody = tokensWithin(diff, tokens, "-val oldAnswer = 41")
        assertTrue(
            deletedBody.any { it.slice(diff) == "val oldAnswer = 41" && it.kind == SyntaxTokenKind.String },
            "Fenced body inside Diff of Markdown must remain Markdown code String, got: $deletedBody",
        )
        val addedBody = tokensWithin(diff, tokens, "+val newAnswer = 42")
        assertTrue(
            addedBody.any { it.slice(diff) == "val newAnswer = 42" && it.kind == SyntaxTokenKind.String },
            "Fenced body inside Diff of Markdown must remain Markdown code String, got: $addedBody",
        )
    }

    @Test
    fun diffOfMarkdownWithYamlFrontmatterDoesNotTriggerSecondLevelYamlSubHighlighting() {
        val diff = """
            diff --git a/post.md b/post.md
            --- a/post.md
            +++ b/post.md
            @@ -1,5 +1,5 @@
             ---
            -title: Old Post
            +title: New Post
             count: 3
             ---
            +# Body Heading
        """.trimIndent() + "\n"

        val lexer = SyntaxHighlighter.lexer(SyntaxLanguage.Diff)
        val tokens = assertValidSnippetHasNoInvalidTokens(lexer, diff, "diff-of-md-frontmatter")

        // Frontmatter delimiters inside the hunk do NOT break hunk line tracking, and
        // 2nd-level YAML sub-highlighting MUST NOT run on `title` or `3`:
        assertFalse(
            tokens.any { (it.slice(diff) == "title" || it.slice(diff) == "count") && it.kind == SyntaxTokenKind.Property },
            "Diff -> Markdown frontmatter must NOT trigger 2nd-level YAML Property highlighting: $tokens",
        )
        assertFalse(
            tokens.any { it.slice(diff) == "3" && it.kind == SyntaxTokenKind.Number },
            "Diff -> Markdown frontmatter must NOT trigger 2nd-level YAML Number highlighting: $tokens",
        )

        // Markdown heading after frontmatter IS highlighted at level 1:
        val heading = tokensWithin(diff, tokens, "+# Body Heading")
        assertTrue(heading.any { it.slice(diff) == "Body" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(heading.any { it.slice(diff) == "Heading" && it.kind == SyntaxTokenKind.Keyword })
    }

    @Test
    fun markdownWithDiffFenceOfKotlinFileHighlightsDiffOnlyWithoutKotlinRecursion() {
        val md = """
            # Patch Notes

            ```diff
            diff --git a/src/Main.kt b/src/Main.kt
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -1,3 +1,3 @@
             val keep = 0
            -val oldVal = 1
            +val newVal = 2
            ```
        """.trimIndent() + "\n"

        val lexer = SyntaxHighlighter.lexer(SyntaxLanguage.Markdown)
        val tokens = assertValidSnippetHasNoInvalidTokens(lexer, md, "md-with-diff-of-kt")

        // Markdown outer heading works:
        assertTrue(tokens.any { it.slice(md) == "Patch" && it.kind == SyntaxTokenKind.Keyword })

        // 1st-level Diff tokens inside the fence MUST be highlighted:
        val gitHeader = tokensWithin(md, tokens, "diff --git a/src/Main.kt b/src/Main.kt")
        assertTrue(gitHeader.any { it.slice(md).startsWith("diff") && it.kind == SyntaxTokenKind.Keyword })

        val hunkHeader = tokensWithin(md, tokens, "@@ -1,3 +1,3 @@")
        assertTrue(hunkHeader.any { it.kind != SyntaxTokenKind.String || it.slice(md) != "@@ -1,3 +1,3 @@" })

        val deletedLine = tokensWithin(md, tokens, "-val oldVal = 1")
        assertEquals("-", deletedLine.first().slice(md))
        assertEquals(SyntaxTokenKind.Punctuation, deletedLine.first().kind)
        assertEquals("val oldVal = 1", deletedLine[1].slice(md))
        assertEquals(SyntaxTokenKind.Keyword, deletedLine[1].kind)

        val addedLine = tokensWithin(md, tokens, "+val newVal = 2")
        assertEquals("+", addedLine.first().slice(md))
        assertEquals(SyntaxTokenKind.Punctuation, addedLine.first().kind)
        assertEquals("val newVal = 2", addedLine[1].slice(md))
        assertEquals(SyntaxTokenKind.String, addedLine[1].kind)

        // 2nd-level Kotlin sub-highlighting MUST NOT run inside the diff:
        assertFalse(
            tokens.any { it.slice(md) == "val" && it.kind == SyntaxTokenKind.Keyword },
            "Markdown -> Diff -> Kotlin must NOT highlight standalone 'val' as Kotlin Keyword: $tokens",
        )
        assertFalse(
            tokens.any { it.slice(md) in setOf("0", "1", "2") && it.kind == SyntaxTokenKind.Number },
            "Markdown -> Diff -> Kotlin must NOT highlight Kotlin Number literals: $tokens",
        )
    }

    @Test
    fun diffSubHighlightsDockerfileProtobufAndHclOneLevelDeep() {
        val diff = """
            diff --git a/Dockerfile b/Dockerfile
            --- a/Dockerfile
            +++ b/Dockerfile
            @@ -1,2 +1,2 @@
            -FROM alpine:3.18 AS base
            +FROM alpine:3.19 AS base
             RUN echo "ready"
            diff --git a/api/service.proto b/api/service.proto
            --- a/api/service.proto
            +++ b/api/service.proto
            @@ -1,3 +1,3 @@
             syntax = "proto3";
             message GreetRequest {
            -  string name = 1;
            +  string user_name = 2;
             }
            diff --git a/infra/main.tf b/infra/main.tf
            --- a/infra/main.tf
            +++ b/infra/main.tf
            @@ -1,3 +1,3 @@
             resource "aws_s3_bucket" "assets" {
            -  count = 1
            +  count = 2
             }
        """.trimIndent() + "\n"

        val lexer = SyntaxHighlighter.lexer(SyntaxLanguage.Diff)
        val tokens = assertValidSnippetHasNoInvalidTokens(lexer, diff, "diff-of-infra-languages")

        // Dockerfile
        val dockerFrom = tokensWithin(diff, tokens, "+FROM alpine:3.19 AS base")
        assertTrue(dockerFrom.any { it.slice(diff) == "FROM" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(dockerFrom.any { it.slice(diff) == "AS" && it.kind == SyntaxTokenKind.Keyword })
        val dockerRun = tokensWithin(diff, tokens, " RUN echo \"ready\"")
        assertTrue(dockerRun.any { it.slice(diff) == "RUN" && it.kind == SyntaxTokenKind.Keyword })

        // Protobuf
        val protoSyntax = tokensWithin(diff, tokens, " syntax = \"proto3\";")
        assertTrue(protoSyntax.any { it.slice(diff) == "syntax" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(protoSyntax.any { it.slice(diff) == "\"proto3\"" && it.kind == SyntaxTokenKind.String })
        val protoField = tokensWithin(diff, tokens, "+  string user_name = 2;")
        assertTrue(protoField.any { it.slice(diff) == "string" && it.kind == SyntaxTokenKind.Type })
        assertTrue(protoField.any { it.slice(diff) == "2" && it.kind == SyntaxTokenKind.Number })

        // HCL / Terraform
        val hclRes = tokensWithin(diff, tokens, " resource \"aws_s3_bucket\" \"assets\" {")
        assertTrue(hclRes.any { it.slice(diff) == "resource" && it.kind == SyntaxTokenKind.Keyword })
        val hclAttr = tokensWithin(diff, tokens, "+  count = 2")
        assertTrue(hclAttr.any { it.slice(diff) == "count" && it.kind == SyntaxTokenKind.Property })
        assertTrue(hclAttr.any { it.slice(diff) == "2" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun markdownSubHighlightsDockerfileProtobufHclAndTerraformFencesOneLevelDeep() {
        val md = """
            ```dockerfile
            FROM alpine:3.19 AS base
            RUN echo "ok"
            ```

            ```proto
            syntax = "proto3";
            message Reply {
              int32 status = 200;
            }
            ```

            ```hcl
            variable "region" {
              type = string
            }
            ```

            ```terraform
            resource "aws_s3_bucket" "logs" {
              count = 3
            }
            ```
        """.trimIndent() + "\n"

        val lexer = SyntaxHighlighter.lexer(SyntaxLanguage.Markdown)
        val tokens = assertValidSnippetHasNoInvalidTokens(lexer, md, "md-with-infra-fences")

        // Dockerfile
        assertTrue(tokens.any { it.slice(md) == "FROM" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(md) == "RUN" && it.kind == SyntaxTokenKind.Keyword })

        // Protobuf
        assertTrue(tokens.any { it.slice(md) == "syntax" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(md) == "message" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(md) == "int32" && it.kind == SyntaxTokenKind.Type })
        assertTrue(tokens.any { it.slice(md) == "200" && it.kind == SyntaxTokenKind.Number })

        // HCL + Terraform aliases
        assertTrue(tokens.any { it.slice(md) == "variable" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(md) == "string" && it.kind == SyntaxTokenKind.Type })
        assertTrue(tokens.any { it.slice(md) == "resource" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(md) == "count" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(md) == "3" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun selfNestingInDiffAndMarkdownDoesNotRecurse() {
        // 1. Diff of a .patch file that itself patches a .kt file:
        // Outer Diff highlights the .patch hunk lines at most 1 level (pure Diff, zero Kotlin recursion).
        val diffOfPatch = """
            diff --git a/changes.patch b/changes.patch
            --- a/changes.patch
            +++ b/changes.patch
            @@ -1,5 +1,5 @@
             --- a/App.kt
             +++ b/App.kt
             @@ -1,1 +1,1 @@
            --val before = 10
            ++val after = 20
        """.trimIndent() + "\n"

        val diffTokens = assertValidSnippetHasNoInvalidTokens(
            SyntaxHighlighter.lexer(SyntaxLanguage.Diff),
            diffOfPatch,
            "diff-of-patch-self-nesting",
        )
        assertFalse(
            diffTokens.any { it.slice(diffOfPatch) == "val" && it.kind == SyntaxTokenKind.Keyword },
            "Diff of .patch must NOT recurse into inner App.kt Kotlin tokens: $diffTokens",
        )
        assertFalse(
            diffTokens.any { it.slice(diffOfPatch) in setOf("10", "20") && it.kind == SyntaxTokenKind.Number },
            "Diff of .patch must NOT emit inner Kotlin Number tokens: $diffTokens",
        )

        // 2. Markdown 4-backtick markdown fence wrapping 3-backtick kotlin fence:
        // Stays Markdown code block (no recursion into inner Markdown/Kotlin).
        val mdOfMd = """
            ````markdown
            # Inner Heading
            ```kotlin
            val nested = 99
            ```
            ````
        """.trimIndent() + "\n"

        val mdTokens = assertValidSnippetHasNoInvalidTokens(
            SyntaxHighlighter.lexer(SyntaxLanguage.Markdown),
            mdOfMd,
            "md-of-md-self-nesting",
        )
        assertFalse(
            mdTokens.any { it.slice(mdOfMd) == "val" && it.kind == SyntaxTokenKind.Keyword },
            "Markdown fence of markdown must NOT recurse into inner Kotlin fence: $mdTokens",
        )
        assertFalse(
            mdTokens.any { it.slice(mdOfMd) == "99" && it.kind == SyntaxTokenKind.Number },
            "Markdown fence of markdown must NOT highlight inner Kotlin Number: $mdTokens",
        )
        assertFalse(
            mdTokens.any { it.slice(mdOfMd) == "Inner" && it.kind == SyntaxTokenKind.Keyword },
            "Markdown fence of markdown must NOT highlight inner Markdown heading when self-nesting is suppressed or code-blocked: $mdTokens",
        )
        assertTrue(
            mdTokens.any { it.kind == SyntaxTokenKind.String && it.slice(mdOfMd).contains("val nested = 99") },
            "Expected self-nested markdown code block body to remain String, got: $mdTokens",
        )
    }
}
