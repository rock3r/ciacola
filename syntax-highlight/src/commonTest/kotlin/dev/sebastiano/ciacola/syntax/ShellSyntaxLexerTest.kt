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
                "deep-double-paren" to ("(".repeat(1_000) + "echo a) && (echo b" + ")".repeat(1_000)),
                "long-heredoc-expand-line" to ("cat <<\$EOF\n" + "\$a\\x ".repeat(4_000) + "\n\$EOF\necho done\n"),
                "long-heredoc-expand-plain-line" to ("cat <<EOF\n" + "\$a\\x ".repeat(4_000) + "\nEOF\necho done\n"),
                "unterminated-heredoc" to ("cat <<EOF\n" + "line \$x \${y:-1}\n".repeat(1_000)),
            ),
        )
    }

    @Test
    fun braceExpansionDoesNotCorruptCommandOrCaseState() {
        val code = """
            mkdir -p src/{main,test}/kotlin
            echo {case,for}/dir
            export FOO=1
            if true; then echo hi; fi
            { echo grouped; }
            foo() { echo body; }
        """.trimIndent()

        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-brace-expansion")

        assertTrue(tokens.any { it.slice(code) == "main" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code) == "test" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code) == "case" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code) == "for" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code) == "export" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "FOO" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "if" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "true" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "then" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokens.any { it.slice(code) == "grouped" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code) == "foo" && it.kind == SyntaxTokenKind.Function })
        assertTrue(tokens.any { it.slice(code) == "body" && it.kind == SyntaxTokenKind.Plain })
        val echoKinds = tokens.filter { it.slice(code) == "echo" }.map { it.kind }
        assertEquals(listOf(SyntaxTokenKind.Function, SyntaxTokenKind.Function, SyntaxTokenKind.Function, SyntaxTokenKind.Function), echoKinds)
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun nestedSubshellsStartingWithDoubleParenDoNotLeakArithMode() {
        val code = """
            ((echo a) && (echo b))
            echo after_cmd
            x=$((echo a) && (echo b))
            echo "${'$'}x"
            y=$(( (1 + 2) * 3 ))
        """.trimIndent()

        val tokens = assertLosslessInvariants(ShellSyntaxLexer, code, "shell-nested-subshells")

        val echoKinds = tokens.filter { it.slice(code) == "echo" }.map { it.kind }
        assertEquals(6, echoKinds.size)
        assertTrue(echoKinds.all { it == SyntaxTokenKind.Function }, "Expected all echo commands to be Function, got $echoKinds")
        assertTrue(tokens.any { it.slice(code) == "after_cmd" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code) == "\$x" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "1" && it.kind == SyntaxTokenKind.Number })
        assertTrue(tokens.any { it.slice(code) == "3" && it.kind == SyntaxTokenKind.Number })
        assertFalse(tokens.any { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun heredocClosingResetsCommandPositionOnImmediatelyFollowingLine() {
        val code1 = "cat <<'EOF'\nhello\nEOF\necho ok\n"
        val t1 = assertLosslessInvariants(ShellSyntaxLexer, code1, "shell-heredoc-nextline-1")
        assertTrue(t1.any { it.slice(code1) == "echo" && it.kind == SyntaxTokenKind.Function })
        assertTrue(t1.any { it.slice(code1) == "ok" && it.kind == SyntaxTokenKind.Plain })

        val code2 = "if cat <<EOF\nhi\nEOF\nthen\n  echo yes\nfi\n"
        val t2 = assertLosslessInvariants(ShellSyntaxLexer, code2, "shell-heredoc-nextline-2")
        assertTrue(t2.any { it.slice(code2) == "then" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(t2.any { it.slice(code2) == "echo" && it.kind == SyntaxTokenKind.Function })
        assertTrue(t2.any { it.slice(code2) == "fi" && it.kind == SyntaxTokenKind.Keyword })

        val code3 = "cat <<EOF\nhi\nEOF\nexport X=1\n"
        val t3 = assertLosslessInvariants(ShellSyntaxLexer, code3, "shell-heredoc-nextline-3")
        assertTrue(t3.any { it.slice(code3) == "export" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(t3.any { it.slice(code3) == "X" && it.kind == SyntaxTokenKind.Property })
        assertTrue(t3.any { it.slice(code3) == "1" && it.kind == SyntaxTokenKind.Number })
    }

    @Test
    fun unquotedHeredocDelimitersContainingDollarOrBacktickCloseCleanly() {
        val code1 = "cat <<\$EOF\nhello \$USER\n\$EOF\necho after\n"
        val t1 = assertLosslessInvariants(ShellSyntaxLexer, code1, "shell-heredoc-dollar-delim")
        assertTrue(t1.any { it.slice(code1) == "\$USER" && it.kind == SyntaxTokenKind.Property })
        assertTrue(t1.any { it.slice(code1) == "\$EOF" && it.kind == SyntaxTokenKind.String })
        assertTrue(t1.any { it.slice(code1) == "echo" && it.kind == SyntaxTokenKind.Function })
        assertTrue(t1.any { it.slice(code1) == "after" && it.kind == SyntaxTokenKind.Plain })

        val code2 = "cat <<a\$b\nval=\$HOME\na\$b\nexport OK=1\n"
        val t2 = assertLosslessInvariants(ShellSyntaxLexer, code2, "shell-heredoc-mixed-dollar-delim")
        assertTrue(t2.any { it.slice(code2) == "\$HOME" && it.kind == SyntaxTokenKind.Property })
        assertTrue(t2.any { it.slice(code2) == "a\$b" && it.kind == SyntaxTokenKind.String })
        assertTrue(t2.any { it.slice(code2) == "export" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(t2.any { it.slice(code2) == "OK" && it.kind == SyntaxTokenKind.Property })
    }

    @Test
    fun scopesPendingHeredocsPerCommandSubstitutionAndResetsCommandPositionAfterNestedCmdSub() {
        val code1 = ": <<OUT \"\$(\nprintf inner\n)\"\nouter\nOUT\necho done\n"
        val t1 = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, code1, "shell-heredoc-multiline-cmdsub")
        assertEquals(SyntaxTokenKind.Function, t1.first { it.slice(code1) == "printf" }.kind)
        assertEquals(SyntaxTokenKind.Plain, t1.first { it.slice(code1) == "inner" }.kind)
        assertEquals(SyntaxTokenKind.String, t1.first { it.slice(code1) == "outer" }.kind)
        assertEquals(SyntaxTokenKind.String, t1.last { it.slice(code1) == "OUT" }.kind)
        assertEquals(SyntaxTokenKind.Function, t1.first { it.slice(code1) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, t1.first { it.slice(code1) == "done" }.kind)

        val code2 = "cat <<OUT \"\$(cat <<IN\ninner body\nIN\n)\"\nouter body\nOUT\nexport OK=1\n"
        val t2 = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, code2, "shell-nested-heredocs-in-cmdsub")
        assertEquals(SyntaxTokenKind.String, t2.first { it.slice(code2) == "inner body" }.kind)
        assertEquals(SyntaxTokenKind.String, t2.first { it.slice(code2) == "outer body" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, t2.first { it.slice(code2) == "export" }.kind)
        assertEquals(SyntaxTokenKind.Property, t2.first { it.slice(code2) == "OK" }.kind)
        assertEquals(SyntaxTokenKind.Number, t2.first { it.slice(code2) == "1" }.kind)

        val code3 = ": <<OUT1 <<OUT2 \"\$(\nprintf mid\n)\"\nbody1\nOUT1\nbody2\nOUT2\necho fin\n"
        val t3 = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, code3, "shell-multi-heredoc-multiline-cmdsub")
        assertEquals(SyntaxTokenKind.Function, t3.first { it.slice(code3) == "printf" }.kind)
        assertEquals(SyntaxTokenKind.String, t3.first { it.slice(code3) == "body1" }.kind)
        assertEquals(SyntaxTokenKind.String, t3.first { it.slice(code3) == "body2" }.kind)
        assertEquals(SyntaxTokenKind.Function, t3.first { it.slice(code3) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, t3.first { it.slice(code3) == "fin" }.kind)

        val code4 = "cat <<OUT\n\$(cat <<IN\ninner\nIN\n)\nOUT\necho after\n"
        val t4 = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, code4, "shell-cmdsub-heredoc-inside-heredoc")
        assertEquals(SyntaxTokenKind.String, t4.first { it.slice(code4) == "inner" }.kind)
        assertEquals(SyntaxTokenKind.Function, t4.first { it.slice(code4) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, t4.first { it.slice(code4) == "after" }.kind)

        val code5 = ": <<OUT `printf\ninner`\nouter\nOUT\necho bt_done\n"
        val t5 = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, code5, "shell-heredoc-multiline-backtick")
        assertEquals(SyntaxTokenKind.Function, t5.first { it.slice(code5) == "printf" }.kind)
        assertEquals(SyntaxTokenKind.String, t5.first { it.slice(code5) == "outer" }.kind)
        assertEquals(SyntaxTokenKind.Function, t5.first { it.slice(code5) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, t5.first { it.slice(code5) == "bt_done" }.kind)
    }

    @Test
    fun handlesInternalHashAndAnsiCOrLocaleQuotedHeredocDelimiters() {
        val hashDelim = "cat <<EOF#tag\nbody \$VAR\nEOF#tag\necho after_hash\n"
        val tHash = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, hashDelim, "shell-heredoc-internal-hash")
        assertEquals(SyntaxTokenKind.Property, tHash.first { it.slice(hashDelim) == "\$VAR" }.kind)
        assertEquals(SyntaxTokenKind.String, tHash.last { it.slice(hashDelim) == "EOF#tag" }.kind)
        assertEquals(SyntaxTokenKind.Function, tHash.first { it.slice(hashDelim) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tHash.first { it.slice(hashDelim) == "after_hash" }.kind)

        val leadingHashComment = "cat << # not a delimiter\necho next\n"
        val tComment = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, leadingHashComment, "shell-heredoc-leading-hash")
        assertEquals(SyntaxTokenKind.Comment, tComment.first { it.slice(leadingHashComment) == "# not a delimiter" }.kind)
        assertEquals(SyntaxTokenKind.Function, tComment.first { it.slice(leadingHashComment) == "echo" }.kind)

        val ansiCSimple = "cat <<\$'EOF'\nbody \$USER\nEOF\necho after_ansic\n"
        val tAnsiC = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, ansiCSimple, "shell-heredoc-ansic-simple")
        assertTrue(tAnsiC.none { it.slice(ansiCSimple) == "\$USER" && it.kind == SyntaxTokenKind.Property })
        assertEquals(SyntaxTokenKind.String, tAnsiC.last { it.slice(ansiCSimple) == "EOF" }.kind)
        assertEquals(SyntaxTokenKind.Function, tAnsiC.first { it.slice(ansiCSimple) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tAnsiC.first { it.slice(ansiCSimple) == "after_ansic" }.kind)

        val ansiCEscaped = "cat <<\$'EO\\'\\x46'\nhello\nEO'F\necho after_esc\n"
        val tAnsiCEsc = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, ansiCEscaped, "shell-heredoc-ansic-escaped")
        assertEquals(SyntaxTokenKind.String, tAnsiCEsc.last { it.slice(ansiCEscaped) == "EO'F" }.kind)
        assertEquals(SyntaxTokenKind.Function, tAnsiCEsc.first { it.slice(ansiCEscaped) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tAnsiCEsc.first { it.slice(ansiCEscaped) == "after_esc" }.kind)

        val localeDelim = "cat <<\$\"EOF\"\nbody \$USER\nEOF\necho after_locale\n"
        val tLocale = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, localeDelim, "shell-heredoc-locale")
        assertTrue(tLocale.none { it.slice(localeDelim) == "\$USER" && it.kind == SyntaxTokenKind.Property })
        assertEquals(SyntaxTokenKind.String, tLocale.last { it.slice(localeDelim) == "EOF" }.kind)
        assertEquals(SyntaxTokenKind.Function, tLocale.first { it.slice(localeDelim) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tLocale.first { it.slice(localeDelim) == "after_locale" }.kind)
    }

    @Test
    fun handlesNumericAndNamedFdHeredocsAndLineContinuationsBeforeDelimiter() {
        val numHeredoc = "cat 0<<EOF\nhello\nEOF\necho after_num\n"
        val tNum = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, numHeredoc, "shell-num-fd-heredoc")
        assertEquals(SyntaxTokenKind.Number, tNum.first { it.slice(numHeredoc) == "0" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tNum.first { it.slice(numHeredoc) == "<<" }.kind)
        assertEquals(SyntaxTokenKind.String, tNum.first { it.slice(numHeredoc) == "hello" }.kind)
        assertEquals(SyntaxTokenKind.Function, tNum.first { it.slice(numHeredoc) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tNum.first { it.slice(numHeredoc) == "after_num" }.kind)

        val numStripHeredoc = "cat 0<<-EOF\n\tbody\n\tEOF\necho after_num_strip\n"
        val tNumStrip = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, numStripHeredoc, "shell-num-fd-strip-heredoc")
        assertEquals(SyntaxTokenKind.Number, tNumStrip.first { it.slice(numStripHeredoc) == "0" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tNumStrip.first { it.slice(numStripHeredoc) == "<<-" }.kind)
        assertEquals(SyntaxTokenKind.String, tNumStrip.first { it.slice(numStripHeredoc) == "\tbody" }.kind)
        assertEquals(SyntaxTokenKind.Function, tNumStrip.first { it.slice(numStripHeredoc) == "echo" }.kind)

        val namedFdAndHereStr = "cat {fd}<<EOF\nbody\nEOF\ncat 3<<<\"hi\"\n0<<EOF2 echo prefixed\nbody2\nEOF2\n"
        val tNamed = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, namedFdAndHereStr, "shell-named-fd-heredoc")
        assertEquals(SyntaxTokenKind.Property, tNamed.first { it.slice(namedFdAndHereStr) == "fd" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tNamed.first { it.slice(namedFdAndHereStr) == "<<" }.kind)
        assertEquals(SyntaxTokenKind.String, tNamed.first { it.slice(namedFdAndHereStr) == "body" }.kind)
        assertEquals(SyntaxTokenKind.Number, tNamed.first { it.slice(namedFdAndHereStr) == "3" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tNamed.first { it.slice(namedFdAndHereStr) == "<<<" }.kind)
        assertEquals(SyntaxTokenKind.Function, tNamed.first { it.slice(namedFdAndHereStr) == "echo" }.kind)

        val contBeforeClose = "cat <<EOF\n\\\nEOF\necho after_cont\n"
        val tCont = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, contBeforeClose, "shell-heredoc-cont-close")
        assertEquals(SyntaxTokenKind.String, tCont.last { it.slice(contBeforeClose) == "EOF" }.kind)
        assertEquals(SyntaxTokenKind.Function, tCont.first { it.slice(contBeforeClose) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tCont.first { it.slice(contBeforeClose) == "after_cont" }.kind)

        val doubleContStrip = "cat <<-EOF\n\t\\\n\t\\\n\tEOF\necho after_strip_cont\n"
        val tStripCont = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, doubleContStrip, "shell-heredoc-strip-cont")
        assertEquals(SyntaxTokenKind.Function, tStripCont.first { it.slice(doubleContStrip) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tStripCont.first { it.slice(doubleContStrip) == "after_strip_cont" }.kind)

        val contAfterBody = "cat <<EOF\nfoo\\\nEOF\nstill_body\nEOF\necho after_body_cont\n"
        val tBodyCont = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, contAfterBody, "shell-heredoc-body-cont")
        assertEquals(SyntaxTokenKind.String, tBodyCont.first { it.slice(contAfterBody) == "still_body" }.kind)
        assertEquals(SyntaxTokenKind.Function, tBodyCont.first { it.slice(contAfterBody) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tBodyCont.first { it.slice(contAfterBody) == "after_body_cont" }.kind)

        val tabWithoutStrip = "cat <<EOF\n\t\\\nEOF\nstill_body2\nEOF\necho after_tab_nostrip\n"
        val tTabNoStrip = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, tabWithoutStrip, "shell-heredoc-tab-nostrip")
        assertEquals(SyntaxTokenKind.String, tTabNoStrip.first { it.slice(tabWithoutStrip) == "still_body2" }.kind)
        assertEquals(SyntaxTokenKind.Function, tTabNoStrip.first { it.slice(tabWithoutStrip) == "echo" }.kind)
    }

    @Test
    fun handlesSubstitutionShapedAndLineContinuedHeredocDelimiters() {
        val cmdSubDelim = "cat <<\$(echo EOF)\nbody \$USER\n\$(echo EOF)\necho after_cmdsub_delim\n"
        val tCmdSub = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, cmdSubDelim, "shell-heredoc-cmdsub-delim")
        assertEquals(SyntaxTokenKind.Property, tCmdSub.first { it.slice(cmdSubDelim) == "\$USER" }.kind)
        assertEquals(SyntaxTokenKind.String, tCmdSub.last { it.slice(cmdSubDelim) == "\$(echo EOF)" }.kind)
        assertEquals(SyntaxTokenKind.Function, tCmdSub.first { it.slice(cmdSubDelim) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tCmdSub.first { it.slice(cmdSubDelim) == "after_cmdsub_delim" }.kind)

        val cmdSubSemi = "cat <<\$(echo a; echo b); echo same_line\nbody\n\$(echo a; echo b)\necho after_semi\n"
        val tSemi = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, cmdSubSemi, "shell-heredoc-cmdsub-semi")
        assertEquals(SyntaxTokenKind.Function, tSemi.first { it.slice(cmdSubSemi) == "echo" && it.startIndex > cmdSubSemi.indexOf(";") }.kind)
        assertEquals(SyntaxTokenKind.Plain, tSemi.first { it.slice(cmdSubSemi) == "same_line" }.kind)
        assertEquals(SyntaxTokenKind.String, tSemi.last { it.slice(cmdSubSemi) == "\$(echo a; echo b)" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tSemi.first { it.slice(cmdSubSemi) == "after_semi" }.kind)

        val arithDelim = "cat <<\$((1 + 2))\nbody\n\$((1 + 2))\necho after_arith_delim\n"
        val tArith = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, arithDelim, "shell-heredoc-arith-delim")
        assertEquals(SyntaxTokenKind.String, tArith.last { it.slice(arithDelim) == "\$((1 + 2))" }.kind)
        assertEquals(SyntaxTokenKind.Function, tArith.first { it.slice(arithDelim) == "echo" }.kind)

        val paramDelim = "cat <<\${foo:-a b}\nbody\n\${foo:-a b}\necho after_param_delim\n"
        val tParam = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, paramDelim, "shell-heredoc-param-delim")
        assertEquals(SyntaxTokenKind.String, tParam.last { it.slice(paramDelim) == "\${foo:-a b}" }.kind)
        assertEquals(SyntaxTokenKind.Function, tParam.first { it.slice(paramDelim) == "echo" }.kind)

        val backtickDelim = "cat <<`echo EOF`\nbody \$USER\n`echo EOF`\necho after_bt_delim\n"
        val tBt = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, backtickDelim, "shell-heredoc-bt-delim")
        assertEquals(SyntaxTokenKind.Property, tBt.first { it.slice(backtickDelim) == "\$USER" }.kind)
        assertEquals(SyntaxTokenKind.String, tBt.last { it.slice(backtickDelim) == "`echo EOF`" }.kind)
        assertEquals(SyntaxTokenKind.Function, tBt.first { it.slice(backtickDelim) == "echo" }.kind)

        val heredocInsideBacktick = "x=`cat <<EOF`; echo hi\ny=`cat <<`; echo ok\n"
        val tInsideBt = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, heredocInsideBacktick, "shell-heredoc-in-bt")
        assertEquals(SyntaxTokenKind.Function, tInsideBt.first { it.slice(heredocInsideBacktick) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tInsideBt.first { it.slice(heredocInsideBacktick) == "hi" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tInsideBt.first { it.slice(heredocInsideBacktick) == "ok" }.kind)

        val unclosedCmdSubDelim = "cat <<\$(echo"
        assertLosslessInvariants(ShellSyntaxLexer, unclosedCmdSubDelim, "shell-unclosed-cmdsub-delim")

        val contDelim = "cat <<E\\\nOF\nbody\nEOF\necho after_cont_delim\n"
        val tContDelim = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, contDelim, "shell-heredoc-cont-delim")
        assertEquals(SyntaxTokenKind.String, tContDelim.last { it.slice(contDelim) == "EOF" }.kind)
        assertEquals(SyntaxTokenKind.Function, tContDelim.first { it.slice(contDelim) == "echo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tContDelim.first { it.slice(contDelim) == "after_cont_delim" }.kind)

        val crContDelim = "cat <<E\\\rOF\rbody\rEOF\recho after_cr_cont\r"
        val tCrCont = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, crContDelim, "shell-heredoc-cr-cont-delim")
        assertEquals(SyntaxTokenKind.Function, tCrCont.first { it.slice(crContDelim) == "echo" }.kind)

        val emojiDelim = "cat <<🎉EOF\nbody\n🎉EOF\necho after_emoji\n"
        val tEmoji = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, emojiDelim, "shell-heredoc-emoji-delim")
        assertEquals(SyntaxTokenKind.String, tEmoji.last { it.slice(emojiDelim) == "🎉EOF" }.kind)
        assertEquals(SyntaxTokenKind.Function, tEmoji.first { it.slice(emojiDelim) == "echo" }.kind)
    }

    @Test
    fun respectsEscapedBoundariesAndContinuationsForComments() {
        val escapedSpace = "echo foo\\ #bar; echo after_space\n"
        val tEscSpace = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, escapedSpace, "shell-comment-escaped-space")
        assertTrue(tEscSpace.none { it.kind == SyntaxTokenKind.Comment })
        assertEquals(2, tEscSpace.count { it.slice(escapedSpace) == "echo" && it.kind == SyntaxTokenKind.Function })
        assertEquals(SyntaxTokenKind.Plain, tEscSpace.first { it.slice(escapedSpace) == "after_space" }.kind)

        val escapedSemi = "echo foo\\;#bar; echo after_semi\n"
        val tEscSemi = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, escapedSemi, "shell-comment-escaped-semi")
        assertTrue(tEscSemi.none { it.kind == SyntaxTokenKind.Comment })
        assertEquals(2, tEscSemi.count { it.slice(escapedSemi) == "echo" && it.kind == SyntaxTokenKind.Function })

        val wordLineCont = "echo foo\\\n#bar; echo after_cont\n"
        val tWordCont = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, wordLineCont, "shell-comment-word-line-cont")
        assertTrue(tWordCont.none { it.kind == SyntaxTokenKind.Comment })
        assertEquals(2, tWordCont.count { it.slice(wordLineCont) == "echo" && it.kind == SyntaxTokenKind.Function })

        val wsLineCont = "echo \\\n#real comment; echo skipped\n"
        val tWsCont = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, wsLineCont, "shell-comment-ws-line-cont")
        assertEquals(SyntaxTokenKind.Comment, tWsCont.first { it.slice(wsLineCont) == "#real comment; echo skipped" }.kind)

        val evenBackslashSpace = "echo foo\\\\ #real comment; echo skipped\n"
        val tEvenBs = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, evenBackslashSpace, "shell-comment-even-bs")
        assertEquals(SyntaxTokenKind.Comment, tEvenBs.first { it.slice(evenBackslashSpace) == "#real comment; echo skipped" }.kind)

        val afterSubstitutions = "echo \$(echo hi)#not_c; echo `echo hi`#not_c2; (echo sub)#real_c\n(( 1 ))#arith_c\n"
        val tSubst = assertValidSnippetHasNoInvalidTokens(ShellSyntaxLexer, afterSubstitutions, "shell-comment-after-subst")
        assertTrue(tSubst.none { it.slice(afterSubstitutions).startsWith("#not_c") && it.kind == SyntaxTokenKind.Comment })
        assertEquals(SyntaxTokenKind.Comment, tSubst.first { it.slice(afterSubstitutions) == "#real_c" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tSubst.first { it.slice(afterSubstitutions) == "#arith_c" }.kind)
    }

    @Test
    fun matchesDualOracleFixtures() {
        assertAllOracleFixtures(shellOracleFixtures)
    }
}
