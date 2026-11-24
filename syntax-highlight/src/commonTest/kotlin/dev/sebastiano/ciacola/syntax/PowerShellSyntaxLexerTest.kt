package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.PowerShellSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.PowerShellOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerShellSyntaxLexerTest {

    @Test
    fun classifiesCaseInsensitiveKeywordsOperatorsCommentsAndNumbers() {
        val code = """
            #Requires -Version 7.0
            <#
            .SYNOPSIS
            Case-insensitive PowerShell test.
            #>
            FUNCTION Get-SampleData {
                [CmdletBinding()]
                PARAM(
                    [Parameter(Mandatory = ${'$'}TRUE)]
                    [ValidateNotNullOrEmpty()]
                    [OutputType([string])]
                    [string]${'$'}Path = 'default',
                    [System.Collections.Generic.List[int]]${'$'}Ids
                )
                BEGIN {
                    ${'$'}hex = 0xFF_00ul
                    ${'$'}bin = 0b1010_0101n
                    ${'$'}size = 1kb + 2MB + 3gb + 4Tb + 5pb
                    ${'$'}flt = 1.5e-2d
                    ${'$'}typed = 10y + 20s + 30uy + 40us + 50lu
                    ${'$'}rng = 1..10
                }
                PROCESS {
                    IF (${'$'}Path -CEQ 'none' -OR ${'$'}Ids.Count -gt 0 -AND ${'$'}Path -is [string] -and -not (${'$'}Path -inotlike '*.tmp')) {
                        Write-Host -Object "Path=${'$'}Path" -ForegroundColor Green
                    } ELSEIF (${'$'}FALSE -ne ${'$'}NULL) {
                        ${'$'}val = ${'$'}Path -replace 'a', 'b' -split ',' -join ';'
                        ${'$'}bits = (1 -shl 4) -band 0xFF -bor 2 -bxor 1 -shr 1
                        ${'$'}fmt = '{0}' -f 42
                    } ELSE {
                        RETURN
                    }
                }
                END {}
            }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(PowerShellSyntaxLexer, code, "powershell-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("#Requires -Version 7.0"))
        assertTrue(tokens.any { it.slice(code).contains(".SYNOPSIS") && it.kind == SyntaxTokenKind.Comment })

        for (kw in listOf("FUNCTION", "PARAM", "BEGIN", "PROCESS", "IF", "ELSEIF", "ELSE", "RETURN", "END")) {
            assertEquals(SyntaxTokenKind.Keyword, firstKind(kw), "Expected keyword '$kw' to be Keyword")
        }

        assertEquals(SyntaxTokenKind.Function, firstKind("Get-SampleData"))
        assertEquals(SyntaxTokenKind.Function, firstKind("Write-Host"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("CmdletBinding"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Parameter"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("ValidateNotNullOrEmpty"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("OutputType"))
        assertEquals(SyntaxTokenKind.Property, firstKind("Mandatory"))

        assertEquals(SyntaxTokenKind.Type, firstKind("string") { it.startIndex > code.indexOf("[OutputType([") })
        assertEquals(SyntaxTokenKind.Type, firstKind("System"))
        assertEquals(SyntaxTokenKind.Type, firstKind("List"))
        assertEquals(SyntaxTokenKind.Type, firstKind("int"))

        assertEquals(SyntaxTokenKind.Constant, firstKind("\$TRUE"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("\$FALSE"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("\$NULL"))
        assertEquals(SyntaxTokenKind.Property, firstKind("\$Path"))

        for (num in listOf("0xFF_00ul", "0b1010_0101n", "1kb", "2MB", "3gb", "4Tb", "5pb", "1.5e-2d", "10y", "20s", "30uy", "40us", "50lu")) {
            assertEquals(SyntaxTokenKind.Number, firstKind(num), "Expected number '$num' to be Number")
        }
        assertEquals(SyntaxTokenKind.Number, firstKind("1"))
        assertEquals(SyntaxTokenKind.Operator, firstKind(".."))

        for (op in listOf("-CEQ", "-OR", "-gt", "-AND", "-is", "-and", "-not", "-inotlike", "-ne", "-replace", "-split", "-join", "-shl", "-band", "-bor", "-bxor", "-shr", "-f")) {
            assertEquals(SyntaxTokenKind.Operator, firstKind(op), "Expected operator '$op' to be Operator")
        }
        assertEquals(SyntaxTokenKind.Attribute, firstKind("-Object"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("-ForegroundColor"))
    }

    @Test
    fun handlesStringsHereStringsColumn0CloseAndSubexpressions() {
        val code = """
            ${'$'}lit = 'it''s literal ${'$'}var `n
            multiline'
            ${'$'}exp = "hello ""quoted"" ``backtick `n `u{1F600} `${'$'}escaped ${'$'}env:PATH ${'$'}{my var} ${'$'}(Get-Date (Get-Item '.'))"
            ${'$'}nested = "outer ${'$'}("inner ${'$'}(${'$'}x + 1)") end"
            ${'$'}hereExp = @"
            Line 1 ${'$'}exp
              "@ indented does not close
            Nested: ${'$'}(1 + 2)
            "@
            ${'$'}hereLit = @'
            Literal ${'$'}exp and `n
              '@ indented does not close
            '@
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(PowerShellSyntaxLexer, code, "powershell-strings")

        assertTrue(tokens.any { it.slice(code).contains("it''s literal \$var `n") && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "\"\"" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "`u{1F600}" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "`$" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "\$env:PATH" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "\${my var}" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "Get-Date" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "Get-Item" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == " end" && it.kind == SyntaxTokenKind.String })

        // Indented "@ and '@ must NOT close here-strings; column-0 "@ and '@ must close them.
        assertEquals(2, tokens.count { it.slice(code) == "\"@" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == " indented does not close" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "Nested: " && it.kind == SyntaxTokenKind.String })
        assertEquals(2, tokens.count { it.slice(code) == "'@" && it.kind == SyntaxTokenKind.String })
        assertEquals(SyntaxTokenKind.Property, tokens.last { it.slice(code) == "\$hereLit" }.kind)
    }

    @Test
    fun handlesVariablesSplattingModuleCmdletsOperatorsAndAllKeywords() {
        val code = """
            ${'$'}script:x = ${'$'}global:y + ${'$'}local:z + ${'$'}private:p + ${'$'}using:u
            ${'$'}auto = @(${'$'}_, ${'$'}PSItem, ${'$'}?, ${'$'}${'$'}, ${'$'}^, ${'$'}true, ${'$'}false, ${'$'}null, ${'$'}args, ${'$'}input, ${'$'}this, ${'$'}PSVersionTable)
            ${'$'}hash = @{ Key = 'val'; Nested = @(1, 2) }
            ${'$'}arr = @(10, 20)
            ${'$'}item = ${'$'}arr[0]
            Invoke-RestMethod @hash -ErrorAction Stop 2>&1 *>&1
            Microsoft.PowerShell.Utility\Write-Output "ok"
            ${'$'}v = ${'$'}obj?.Prop ?? 'x'
            ${'$'}v ??= 'y'
            ${'$'}v++
            ${'$'}v--
            ${'$'}ok = ${'$'}a && ${'$'}b || !${'$'}c
            echo foo#not_comment
            class MyItem { static [void] Run() {} }
            enum Mode { Fast = 1 }
            filter Select-Pos { process { ${'$'}_ } }
            workflow Run-Flow { parallel {} sequence {} inlinescript {} }
            try { throw "err" } catch {} finally {} trap { continue }
            switch (1) { 1 { break } default { exit 0 } }
            for (${'$'}i = 0; ${'$'}i -lt 2; ${'$'}i++) {}
            foreach (${'$'}k in 1..2) {}
            do {} while (${'$'}false)
            do {} until (${'$'}true)
            data {}
            dynamicparam {}
            clean {}
            using namespace System
            hidden [int]${'$'}h = 0
            define var from
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(PowerShellSyntaxLexer, code, "powershell-vars-ops-kws")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        for (v in listOf("\$script:x", "\$global:y", "\$local:z", "\$private:p", "\$using:u", "@hash")) {
            assertEquals(SyntaxTokenKind.Property, firstKind(v), "Expected var '$v' to be Property")
        }
        for (c in listOf("\$_", "\$PSItem", "\$?", "$$", "\$^", "\$true", "\$false", "\$null", "\$args", "\$input", "\$this", "\$PSVersionTable")) {
            assertEquals(SyntaxTokenKind.Constant, firstKind(c), "Expected automatic var '$c' to be Constant")
        }
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("@("))
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("@{"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0") { it.startIndex > code.indexOf("\$arr[") })
        assertEquals(SyntaxTokenKind.Function, firstKind("Invoke-RestMethod"))
        assertEquals(SyntaxTokenKind.Function, firstKind("Write-Output"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("2>&1"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("*>&1"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("?."))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("&&"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("||"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("#") { it.startIndex > code.indexOf("foo") })

        for (kw in listOf(
            "class", "static", "enum", "filter", "process", "workflow", "parallel", "sequence",
            "inlinescript", "try", "throw", "catch", "finally", "trap", "continue", "switch",
            "break", "exit", "for", "foreach", "in", "do", "while", "until", "data",
            "dynamicparam", "clean", "using", "hidden", "define", "var", "from",
        )) {
            assertEquals(SyntaxTokenKind.Keyword, firstKind(kw), "Expected keyword '$kw' to be Keyword")
        }
    }

    @Test
    fun handlesBomCrlfCrUnicodeAndSurrogatesLosslessly() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val samples = listOf(
            "\uFEFF\$α = \"𠮷 🎉\"\r\n# note 🎉\r\$β = 'café'",
            "\$x = \"$loneHigh\" + '${loneLow}a'",
            "<# block 🎉 #>\r\nWrite-Host 'hi'",
        )
        for ((idx, sample) in samples.withIndex()) {
            assertLosslessInvariants(PowerShellSyntaxLexer, sample, "powershell-unicode#$idx")
        }
        val firstTokens = assertValidSnippetHasNoInvalidTokens(PowerShellSyntaxLexer, samples.first(), "powershell-bom-crlf")
        assertTrue(firstTokens.any { it.slice(samples.first()) == "\$α" && it.kind == SyntaxTokenKind.Property })
        assertTrue(firstTokens.any { it.slice(samples.first()) == "𠮷 🎉" && it.kind == SyntaxTokenKind.String })
        assertTrue(firstTokens.any { it.slice(samples.first()) == "# note 🎉" && it.kind == SyntaxTokenKind.Comment })
    }

    @Test
    fun recoversFromUnterminatedConstructsAndMatchesPrefixFuzzAndLinearPerformance() {
        val unterminated = listOf(
            "<# unclosed block comment",
            "\$s = \"unclosed \$(Get-Date",
            "\$s = 'unclosed",
            "\$h = @\"\nunclosed here-string",
            "\$h = @'\nunclosed literal here-string",
            "\$v = \${unclosed braced",
            "\$x = `",
            "[string",
        )
        for ((idx, sample) in unterminated.withIndex()) {
            assertLosslessInvariants(PowerShellSyntaxLexer, sample, "powershell-unterminated#$idx")
        }

        val prefixSample = """
            <# help #>
            [CmdletBinding()]
            param([string]${'$'}Name = "hi ${'$'}(Get-Date)")
            ${'$'}h = @"
            line ${'$'}Name
            "@
            Get-ChildItem -Path ${'$'}Name | Where-Object { ${'$'}_ -ne ${'$'}null }
        """.trimIndent()
        assertPrefixTruncationSafety(PowerShellSyntaxLexer, prefixSample, maxStep = 1)
        assertMalformedAndFuzzSafety(PowerShellSyntaxLexer, seed = 0x9053E11L, iterations = 80)

        assertLinearPerformance(
            PowerShellSyntaxLexer,
            listOf(
                "unclosed-block-comment-10k" to ("<# " + "#".repeat(12_000)),
                "unclosed-dquote-10k" to ("\$s = \"" + "a".repeat(12_000)),
                "unclosed-squote-10k" to ("\$s = '" + "a".repeat(12_000)),
                "unclosed-heredoc-expand-10k" to ("\$h = @\"\n" + "line\n".repeat(2_500)),
                "unclosed-heredoc-literal-10k" to ("\$h = @'\n" + "line\n".repeat(2_500)),
                "deep-subexpr-nesting" to ("\"\$(".repeat(150) + "'x'" + ")\"".repeat(150)),
                "escaped-quotes-10k" to ("\"" + "\"\"".repeat(6_000) + "\""),
            ),
        )
    }

    @Test
    fun handlesArraySubexprInSubexprAndAdjacentInlineComments() {
        val code = """
            ${'$'}msg1 = "Items: ${'$'}(@(1, 2, 3) -join ', ') tail1"
            ${'$'}msg2 = "Items: ${'$'}(@(1, 2) -join ", ") tail2"
            ${'$'}x = ${'$'}(@(1, 2))
            ${'$'}here = @"
            Body ${'$'}(@(1, 2) -join ', ') after
            "@# after-heredoc
            function Foo { return 1 }# end-brace
            ${'$'}y = (1 + 2)# sum-paren
            ${'$'}z = ${'$'}arr[0]# first-brack
            ${'$'}s1 = "hi"# dquote-comment don't break
            ${'$'}s2 = 'hi'# squote-comment
            ${'$'}lit = @'
            raw
            '@# after-lit-heredoc
            echo foo#not-comment
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(PowerShellSyntaxLexer, code, "powershell-regressions")

        assertTrue(tokens.any { it.slice(code) == " tail1" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == " tail2" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == " after" && it.kind == SyntaxTokenKind.String })
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "-join" }.kind)

        for (comment in listOf(
            "# after-heredoc",
            "# end-brace",
            "# sum-paren",
            "# first-brack",
            "# dquote-comment don't break",
            "# squote-comment",
            "# after-lit-heredoc",
        )) {
            assertTrue(
                tokens.any { it.slice(code) == comment && it.kind == SyntaxTokenKind.Comment },
                "Expected '$comment' to lex as Comment, got $tokens",
            )
        }

        // Mid-word '#' must remain Plain, not Comment.
        assertFalse(tokens.any { it.slice(code).contains("#not-comment") && it.kind == SyntaxTokenKind.Comment })
        assertTrue(tokens.any { it.slice(code) == "#" && it.startIndex > code.indexOf("echo foo") && it.kind == SyntaxTokenKind.Plain })
    }

    @Test
    fun matchesDualOracleFixtures() {
        for (fixture in PowerShellOracleFixtures.all) {
            assertMatchesOracleFixture(fixture)
        }
    }
}
