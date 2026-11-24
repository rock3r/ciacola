package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.DockerfileSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.DockerfileOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerfileSyntaxLexerTest {

    @Test
    fun classifiesParserDirectivesCaseInsensitiveInstructionsAndFlags() {
        val code = """
            # syntax=docker/dockerfile:1
            # check=skip=JSONArgsRecommended
            # regular comment ends directive parsing
            # escape=`
            from --platform=linux/amd64 ghcr.io/org/app:1.2.3@sha256:abcd as builder
            arg BASE_TAG=latest
            label org.opencontainers.image.title="ciacola" version="1.0"
            env APP_HOME=/app PORT=8080
            env LEGACY_KEY legacy_val
            workdir /app
            copy --from=builder --chown=1000:1000 --chmod=0755 --link --parents --exclude=*.tmp ./out /app/
            add --checksum=sha256:1234 --keep-git-dir=true https://example.com/src.tar.gz /src/
            run --mount=type=cache,target=/root/.cache --network=none --security=sandbox echo "hi"
            expose 8080/tcp 53/udp
            volume ["/data"]
            user 1000:1000
            stopsignal SIGTERM
            healthcheck --interval=30s --timeout=3s --start-period=5s --start-interval=1s --retries=3 cmd curl -f http://localhost/ || exit 1
            onbuild run echo "child"
            shell ["/bin/bash", "-c"]
            entrypoint ["/app/bin"]
            cmd ["--serve"]
            maintainer Example <dev@example.com>
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, code, "dockerfile-features")

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("# syntax=docker/dockerfile:1"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("# check=skip=JSONArgsRecommended"))
        // `# escape=` after a non-directive comment is just a comment and does not change escape char.
        assertEquals(SyntaxTokenKind.Comment, firstKind("# escape=`"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("from"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--platform"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("as"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("arg"))
        assertEquals(SyntaxTokenKind.Property, firstKind("BASE_TAG"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("label"))
        assertEquals(SyntaxTokenKind.Property, firstKind("org.opencontainers.image.title"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("env"))
        assertEquals(SyntaxTokenKind.Property, firstKind("APP_HOME"))
        assertEquals(SyntaxTokenKind.Property, firstKind("LEGACY_KEY"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("copy"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--from"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--chown"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--chmod"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--link"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--exclude"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("run"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--mount"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("expose"))
        assertEquals(SyntaxTokenKind.Number, firstKind("8080"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("tcp"))
        assertEquals(SyntaxTokenKind.Number, firstKind("53"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("udp"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("healthcheck"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("--start-interval"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("cmd"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("onbuild"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("run", after = code.indexOf("onbuild")))
    }

    @Test
    fun honorsCustomBacktickEscapeForContinuationsAndStringsAndInterleavedComments() {
        val code = """
            # escape=`
            FROM mcr.microsoft.com/windows/servercore:ltsc2022 AS win
            RUN powershell -Command "Write-Host `"hello`"" `
                # comment inside backtick continuation
                && echo "backslash does not escape quote\" && echo after_quote
            HEALTHCHECK NONE
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, code, "dockerfile-backtick-escape")

        // The `"backslash does not escape quote\"` string closes at the `"` right after `\`,
        // so `&&` and `after_quote` are outside the string.
        val afterQuoteTok = tokens.first { it.slice(code) == "after_quote" }
        assertEquals(SyntaxTokenKind.Plain, afterQuoteTok.kind)

        val interleavedComment = tokens.first { it.slice(code) == "# comment inside backtick continuation" }
        assertEquals(SyntaxTokenKind.Comment, interleavedComment.kind)

        val noneTok = tokens.first { it.slice(code) == "NONE" }
        assertEquals(SyntaxTokenKind.Keyword, noneTok.kind)

        // Backslash-newline is NOT a line continuation when escape=` is active;
        // thus `FROM` on the line after `RUN echo \` is still recognized as a Keyword.
        val nonContCode = "# escape=`\nRUN echo \\\nFROM alpine\n"
        val nonContTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, nonContCode, "dockerfile-non-cont")
        assertEquals(SyntaxTokenKind.Keyword, nonContTokens.first { it.slice(nonContCode) == "FROM" }.kind)
    }

    @Test
    fun handlesBuildKitHeredocsMultiHeredocsExpansionsAndUnclosedHeredoc() {
        val code = """
            FROM alpine:3.19
            RUN <<EOF
            echo "${'$'}{VAR:-default} ${'$'}{ALT:+yes} ${'$'}{ERR:?missing} ${'$'}SIMPLE"
            EOF
            COPY <<'LIT' <<-IND /dst/
            literal ${'$'}VAR ${'$'}{NOT_EXPANDED}
            LIT
            	indented ${'$'}EXPANDED
            	IND
            RUN echo as from run
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, code, "dockerfile-heredocs")

        val varExp = tokens.first { it.slice(code) == "\$SIMPLE" }
        assertEquals(SyntaxTokenKind.Property, varExp.kind)

        // Literal quoted heredoc keeps `$VAR` inside String instead of expanding as Property.
        val litIdx = code.indexOf("literal \$VAR")
        val litTok = tokens.first { it.startIndex <= litIdx && it.endIndex > litIdx }
        assertEquals(SyntaxTokenKind.String, litTok.kind)

        // Tab-indented closing delimiter splits leading tabs into Whitespace.
        val indLineIdx = code.indexOf("\tIND")
        val tabTok = tokens.first { it.startIndex == indLineIdx }
        assertEquals(SyntaxTokenKind.Whitespace, tabTok.kind)
        assertEquals("\t", tabTok.slice(code))
        val indTok = tokens.first { it.startIndex == indLineIdx + 1 }
        assertEquals(SyntaxTokenKind.Keyword, indTok.kind)
        assertEquals("IND", indTok.slice(code))

        // `as`, `from`, `run` as shell arguments are NOT classified as Keywords.
        val tailStart = code.indexOf("echo as from run")
        val tailWords = tokens.filter { it.startIndex >= tailStart && it.slice(code) in setOf("as", "from", "run") }
        assertEquals(3, tailWords.size)
        assertTrue(tailWords.all { it.kind == SyntaxTokenKind.Plain })

        // Unclosed heredoc at EOF remains lossless.
        val unclosed = "RUN <<EOF\nline1\nline2 without terminator"
        assertLosslessInvariants(DockerfileSyntaxLexer, unclosed, "dockerfile-unclosed-heredoc")
    }

    @Test
    fun handlesBomCrlfUnicodeAndLoneSurrogatesLosslessly() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val samples = listOf(
            "\uFEFF# syntax=docker/dockerfile:1\r\nFROM alpine:3.19\r\nRUN echo \"café 🎉 𠮷\"\r\n",
            "RUN echo \"emoji 𠮷\" # not comment\r",
            "FROM alpine$loneHigh\nRUN echo ${loneLow}ok\n",
            "COPY <<EOF /app/\r\n$loneHigh\r\nEOF\r\n",
        )
        for ((idx, sample) in samples.withIndex()) {
            assertLosslessInvariants(DockerfileSyntaxLexer, sample, "dockerfile-encoding#$idx")
        }
        assertFalse(DockerfileSyntaxLexer.lex("").isNotEmpty())
    }

    @Test
    fun satisfiesPrefixTruncationAndFuzzSafety() {
        val sample = DockerfileOracleFixtures.multistageFlagsAndHeredoc.code
        assertPrefixTruncationSafety(DockerfileSyntaxLexer, sample, maxStep = 4)
        assertPrefixTruncationSafety(
            DockerfileSyntaxLexer,
            DockerfileOracleFixtures.customEscapeAndContinuations.code,
            maxStep = 3,
        )
        assertMalformedAndFuzzSafety(DockerfileSyntaxLexer, seed = 0xD0C4E12L, iterations = 40)
    }

    @Test
    fun lexesUnclosedHeredocsAndQuotesInLinearTime() {
        assertLinearPerformance(
            lexer = DockerfileSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-heredoc-4k" to ("RUN <<EOF\n" + "echo \${VAR:-a/b/c}\n".repeat(2_000)),
                "unclosed-dquote-4k" to ("LABEL a=\"" + "hello \${X} ".repeat(2_000)),
                "unclosed-param-exp-4k" to ("RUN echo \${VAR:-" + "{a:".repeat(2_000)),
                "continuations-4k" to ("RUN echo 1 \\\n" + "    # comment\n    && echo 2 \\\n".repeat(1_000) + "    && true\n"),
            ),
            maxMillisPerInput = 300L,
        )
    }
}
