package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.ShellSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import dev.sebastiano.ciacola.syntax.oracle.shellOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellSyntaxLexerTest {

    @Test
    fun classifiesShebangCommentsAndNonCommentHashes() {
        val code = """
            #!/usr/bin/env bash
            # Line comment
            echo foo#bar # trailing comment
            (#paren comment
            echo ${'$'}{#arr[@]} ${'$'}{var#pre} ${'$'}{var##pre} ${'$'}((16#FF))
            #!/not-shebang-midfile
        """.trimIndent()

        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-comments-hashes")

        assertEquals(SyntaxTokenKind.Comment, tokens.first().kind)
        assertEquals("#!/usr/bin/env bash", tokens.first().slice(code))
        assertTrue(tokens.any { it.slice(code) == "# Line comment" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(tokens.any { it.slice(code) == "foo#bar" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code) == "# trailing comment" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(tokens.any { it.slice(code) == "#paren comment" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(tokens.any { it.slice(code) == "16#FF" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tokens.any { it.slice(code) == "#!/not-shebang-midfile" && it.kind == SyntaxTokenKind.Comment })
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun classifiesQuotesEscapesInterpolationsAndNestedSubstitutions() {
        val code = """
            s1='no \escape \'
            s2="hello \"world\" \${'$'}lit ${'$'}USER ${'$'}{BAR:-"nested"} ${'$'}(echo "hi ${'$'}(date)") `uname -s`"
            s3=${'$'}'line1\n\t\x41\u00E9'
            s4=${'$'}"localized ${'$'}HOME"
            bt=`echo \`hostname\``
        """.trimIndent()

        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-quotes")

        assertTrue(tokens.any { it.slice(code) == "'no \\escape \\'" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "\$USER" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "BAR" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "echo" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "date" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "uname" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "-s" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(tokens.any { it.slice(code) == "\$'line1\\n\\t\\x41\\u00E9'" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "\$\"" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "\$HOME" && it.kind == SyntaxTokenKind.Property })
        val quoteDebug = tokens.joinToString(", ") { "${it.slice(code).replace("\n", "\\n")}:${it.kind}" }
        assertTrue(tokens.any { it.slice(code) == "hostname" && it.kind == SyntaxTokenKind.Function }, quoteDebug)
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun handlesHeredocsQuotedUnquotedTabStrippedSameLinePipelineAndFifoQueue() {
        val code = """
            cat <<'EOF' | grep -E "pat"
            body ${'$'}not_var ${'$'}(not_cmd)
            EOF
            cat <<EXPAND
            hello ${'$'}USER ${'$'}{NAME:-anon} ${'$'}(whoami)
            EXPAND
            cat <<-TAB_END
            ${"\t"}tabbed line
            ${"\t"}TAB_END
            diff <(cat <<A) <(cat <<B)
            alpha
            A
            beta
            B
            cat <<FIRST <<SECOND
            one
            FIRST
            two
            SECOND
            cat <<\ESC
            ${'$'}literal
            ESC
        """.trimIndent()

        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-heredocs")

        // Same-line pipeline after <<'EOF' must lex as command tokens before the heredoc body starts.
        assertTrue(tokens.any { it.slice(code) == "grep" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "-E" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(tokens.any { it.slice(code) == "pat" && it.kind == SyntaxTokenKind.String })

        // Quoted heredocs (<<'EOF', <<\ESC) must NOT interpolate $not_var / $literal.
        assertTrue(tokens.any { it.slice(code) == "body \$not_var \$(not_cmd)" && it.kind == SyntaxTokenKind.String })
        assertFalse(tokens.any { it.slice(code) == "\$not_var" })
        assertTrue(tokens.any { it.slice(code) == "\$literal" && it.kind == SyntaxTokenKind.String })
        assertFalse(tokens.any { it.slice(code) == "\$literal" && it.kind == SyntaxTokenKind.Property })

        // Unquoted heredoc (<<EXPAND) MUST interpolate $USER, ${NAME:-anon}, $(whoami).
        assertTrue(tokens.any { it.slice(code) == "\$USER" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "NAME" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "whoami" && it.kind == SyntaxTokenKind.Function })

        // Tab-stripped <<-TAB_END must close on `\tTAB_END`.
        assertTrue(tokens.any { it.slice(code) == "TAB_END" && it.kind == SyntaxTokenKind.String })

        // Multiple FIFO heredocs on one line (diff <(cat <<A) <(cat <<B) and <<FIRST <<SECOND) must close in order.
        val alphaIdx = tokens.indexOfFirst { it.slice(code) == "alpha" }
        val betaIdx = tokens.indexOfFirst { it.slice(code) == "beta" }
        assertTrue(alphaIdx >= 0 && betaIdx > alphaIdx)
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun handlesNestedCaseInsideCommandSubstitutionAndArithmeticLeftShift() {
        val code = """
            res=${'$'}(
                case "${'$'}mode" in
                    (a|b) echo "ab" ;;
                    *) echo "other" ;;
                esac
            )
            shift_val=${'$'}(( (1 << 4) + 0xFF + 0755 + 16#2A ))
            (( count += 1 ))
        """.trimIndent()

        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-case-cmdsub-arith")

        assertTrue(tokens.any { it.slice(code) == "case" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "esac" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "<<" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "0xFF" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tokens.any { it.slice(code) == "0755" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tokens.any { it.slice(code) == "16#2A" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tokens.any { it.slice(code) == "count" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "+=" && it.kind == SyntaxTokenKind.Operator })
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun handlesKeywordsBuiltinsAssignmentsRedirectionsTestBracketsAndZshForms() {
        val code = """
            FOO=1 BAR="two" env --ignore-environment printenv
            export A=1 B=2
            local -r x=2
            declare -A map=([k]=v)
            typeset -i n=0
            autoload -Uz compinit
            setopt err_exit
            unsetopt nomatch
            zstyle ':completion:*' menu select
            emulate -L zsh
            . ./env.sh
            {fd}>out.txt
            cmd 2>&1 &>>all.log >|clobber.txt
            if [[ -f "${'$'}x" && "${'$'}x" =~ ^[0-9]+${'$'} ]]; then
                echo if then else done
            fi
            while [ "${'$'}n" -lt 10 -o "${'$'}n" -eq 0 ]; do
                break
            done
            coproc worker { cat; }
            trap 'rm -f tmp' EXIT
        """.trimIndent()

        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-builtins-redirs")

        assertTrue(tokens.any { it.slice(code) == "FOO" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "BAR" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "env" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "--ignore-environment" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(tokens.any { it.slice(code) == "export" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "local" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "declare" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "typeset" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "autoload" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "setopt" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "." && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "fd" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "&>>" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == ">|" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "-f" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "=~" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "-lt" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "-eq" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "coproc" && it.kind == SyntaxTokenKind.Keyword })

        // `if then else done` as arguments to `echo` must NOT be classified as Keyword.
        val echoIdx = tokens.indexOfFirst { it.slice(code) == "echo" }
        val fiIdx = tokens.indexOfFirst { it.slice(code) == "fi" }
        val echoArgs = tokens.subList(echoIdx + 1, fiIdx).filter { it.kind != SyntaxTokenKind.Whitespace }
        assertTrue(echoArgs.all { it.kind == SyntaxTokenKind.Plain }, "Expected echo args to be Plain, got $echoArgs")
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun handlesBomCrlfLineContinuationsAndSupplementaryUnicode() {
        val code = "\uFEFFecho \\\r\n  \"𠮷 🎉\" # trailing 🎉\r\n"
        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-unicode-crlf")
        assertTrue(tokens.any { it.slice(code) == "echo" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "𠮷 🎉" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "# trailing 🎉" && it.kind == SyntaxTokenKind.Comment })
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun recoversGracefullyFromUnterminatedConstructsAndMatchesPrefixFuzzAndLinearScaling() {
        val unterminated = listOf(
            "echo 'unclosed",
            "echo \"unclosed \${foo:-bar",
            "echo \$(unclosed",
            "echo \$(( 1 + 2",
            "echo `unclosed",
            "echo \$'unclosed\\",
            "cat <<EOF\nbody without close",
            "cat <<'EOF'\nbody without close",
            "cat <<",
            "if [[ -f foo",
        )
        for ((idx, sample) in unterminated.withIndex()) {
            assertLosslessInvariants(ShellSyntaxLexer, sample, "shell-unterminated#$idx")
        }

        val sample = """
            #!/usr/bin/env bash
            # comment
            export X="hi ${'$'}{Y:-0} ${'$'}(echo "ok") ${'$'}((1 + 2))"
            cat <<'EOF' | grep -E "pat"
            body ${'$'}X
            EOF
        """.trimIndent()
        assertPrefixTruncationSafety(ShellSyntaxLexer, sample, maxStep = 1)
        assertMalformedAndFuzzSafety(ShellSyntaxLexer, seed = 0x54E11L, iterations = 80)

        assertLinearPerformance(
            ShellSyntaxLexer,
            listOf(
                "repeated-commands" to buildString {
                    append("#!/usr/bin/env bash\n")
                    repeat(1_000) { idx ->
                        append("echo \"item_${'$'}{idx:-$idx}\" && (( n += 1 ))\n")
                    }
                },
                "deep-cmdsub" to ("\$(".repeat(150) + "echo hi" + ")".repeat(150)),
                "unterminated-heredoc" to ("cat <<EOF\n" + "line \$x \${y:-1}\n".repeat(1_000)),
            ),
        )
    }

    @Test
    fun matchesDualOracleFixtures() {
        assertAllOracleFixtures(shellOracleFixtures)
    }
}
