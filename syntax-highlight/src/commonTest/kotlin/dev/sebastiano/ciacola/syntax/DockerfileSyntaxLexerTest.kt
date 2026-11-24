package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.DockerfileSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.DockerfileOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerfileSyntaxLexerTest {

    @Test
    fun matchesDualOracleFixtures() {
        assertAllOracleFixtures(DockerfileOracleFixtures.all)
    }

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
        assertEquals(SyntaxTokenKind.Number, firstKind("8080", after = code.indexOf("expose")))
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

        // Late `# escape=` after FROM must NOT switch the escape character away from `\`.
        val lateEscapeCode = "FROM alpine\n# escape=`\nRUN echo ` \nFROM busybox\n"
        val lateEscapeTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, lateEscapeCode, "dockerfile-late-escape")
        assertEquals(
            SyntaxTokenKind.Keyword,
            lateEscapeTokens.first { it.slice(lateEscapeCode) == "FROM" && it.startIndex > 0 }.kind,
        )
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
            ${'\t'}indented ${'$'}EXPANDED
            ${'\t'}IND
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
    fun recoversFromUnclosedQuotesHandlesTrailingSpacesAfterEscapeAndNestedVarsMultiArgLegacyEnv() {
        val unclosedQuoteCode = "ENV BROKEN=\"oops\nFROM alpine:3.19\nRUN echo \"ok\"\n"
        val unclosedTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            unclosedQuoteCode,
            "dockerfile-unclosed-quote-recovery",
        )
        assertEquals(SyntaxTokenKind.Keyword, unclosedTokens.first { it.slice(unclosedQuoteCode) == "FROM" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, unclosedTokens.first { it.slice(unclosedQuoteCode) == "RUN" }.kind)
        assertEquals(SyntaxTokenKind.String, unclosedTokens.first { it.slice(unclosedQuoteCode) == "ok" }.kind)

        val unclosedSingleCode = "RUN echo 'oops\nFROM busybox:latest\n"
        val unclosedSingleTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            unclosedSingleCode,
            "dockerfile-unclosed-single-recovery",
        )
        assertEquals(SyntaxTokenKind.Keyword, unclosedSingleTokens.first { it.slice(unclosedSingleCode) == "FROM" }.kind)

        val trailingSpacesContCode = "RUN echo hello \\   \n    && echo world\nFROM alpine\n"
        val trailingContTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            trailingSpacesContCode,
            "dockerfile-trailing-space-cont",
        )
        val echoTokens = trailingContTokens.filter { it.slice(trailingSpacesContCode) == "echo" }
        assertEquals(2, echoTokens.size)
        assertTrue(echoTokens.all { it.kind == SyntaxTokenKind.Function })

        val nestedVarCode = "RUN echo \"${'$'}{OUTER:-${'$'}{INNER}}\"\n"
        val nestedVarTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            nestedVarCode,
            "dockerfile-nested-param-exp",
        )
        assertEquals(SyntaxTokenKind.Property, nestedVarTokens.first { it.slice(nestedVarCode) == "OUTER" }.kind)
        assertEquals(SyntaxTokenKind.Property, nestedVarTokens.first { it.slice(nestedVarCode) == "INNER" }.kind)

        val multiArgCode = "ARG DEBIAN_FRONTEND TARGETARCH TARGETOS\nARG FOO=bar BAZ QUX=1 LAST\nARG A=\"hello world\" B C=2 D\n"
        val multiArgTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            multiArgCode,
            "dockerfile-multi-arg",
        )
        for (argName in listOf("DEBIAN_FRONTEND", "TARGETARCH", "TARGETOS", "FOO", "BAZ", "QUX", "LAST", "A", "B", "C", "D")) {
            assertEquals(
                SyntaxTokenKind.Property,
                multiArgTokens.first { it.slice(multiArgCode) == argName }.kind,
                "Expected ARG name '$argName' to be Property",
            )
        }
        assertEquals(SyntaxTokenKind.Plain, multiArgTokens.first { it.slice(multiArgCode) == "bar" }.kind)
        assertEquals(SyntaxTokenKind.Number, multiArgTokens.first { it.slice(multiArgCode) == "1" }.kind)
        assertEquals(SyntaxTokenKind.Number, multiArgTokens.first { it.slice(multiArgCode) == "2" }.kind)

        val legacyEnvCode = "ENV JAVA_OPTS -Xmx512m -Dfoo=bar\n"
        val legacyEnvTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            legacyEnvCode,
            "dockerfile-legacy-env",
        )
        assertEquals(SyntaxTokenKind.Property, legacyEnvTokens.first { it.slice(legacyEnvCode) == "JAVA_OPTS" }.kind)
        assertEquals(SyntaxTokenKind.Plain, legacyEnvTokens.first { it.slice(legacyEnvCode) == "-Dfoo" }.kind)
    }

    @Test
    fun handlesParamExpBracesUnclosedExpansionsSingleQuotedContinuationsAndDQuoteComments() {
        val paramBracesCode = "ENV CFG=${'$'}{CFG:-{}}\nFROM alpine\n"
        val paramBracesTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            paramBracesCode,
            "dockerfile-param-exp-braces",
        )
        assertEquals(SyntaxTokenKind.Keyword, paramBracesTokens.first { it.slice(paramBracesCode) == "FROM" }.kind)

        val unclosedParamCode = "ENV A=${'$'}{UNCLOSED\nFROM alpine\n"
        val unclosedParamTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            unclosedParamCode,
            "dockerfile-unclosed-param",
        )
        assertEquals(SyntaxTokenKind.Keyword, unclosedParamTokens.first { it.slice(unclosedParamCode) == "FROM" }.kind)

        val unclosedParamInHeredoc = "RUN <<EOF\necho ${'$'}{UNCLOSED\nEOF\nFROM alpine\n"
        val unclosedParamHeredocTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            unclosedParamInHeredoc,
            "dockerfile-unclosed-param-heredoc",
        )
        assertEquals(SyntaxTokenKind.Keyword, unclosedParamHeredocTokens.first { it.slice(unclosedParamInHeredoc) == "EOF" && it.startIndex > unclosedParamInHeredoc.indexOf("UNCLOSED") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, unclosedParamHeredocTokens.first { it.slice(unclosedParamInHeredoc) == "FROM" }.kind)

        val heredocInterpCommentCode = "RUN <<EOF\necho \"${'$'}{HOME}\"\nEOF\n# see <<NOTE\n# don't\nFROM alpine\n"
        val heredocInterpCommentTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            heredocInterpCommentCode,
            "dockerfile-heredoc-interp-comment",
        )
        assertEquals(SyntaxTokenKind.Comment, heredocInterpCommentTokens.first { it.slice(heredocInterpCommentCode) == "# see <<NOTE" }.kind)
        assertEquals(SyntaxTokenKind.Comment, heredocInterpCommentTokens.first { it.slice(heredocInterpCommentCode) == "# don't" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, heredocInterpCommentTokens.first { it.slice(heredocInterpCommentCode) == "FROM" }.kind)

        val heredocTrailingBackslash = "RUN <<EOF\nline ending with \\\nEOF\nFROM alpine\n"
        val heredocTrailingBsTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            heredocTrailingBackslash,
            "dockerfile-heredoc-trailing-bs",
        )
        assertEquals(SyntaxTokenKind.Keyword, heredocTrailingBsTokens.last { it.slice(heredocTrailingBackslash) == "EOF" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, heredocTrailingBsTokens.first { it.slice(heredocTrailingBackslash) == "FROM" }.kind)

        val singleContBs = "RUN echo 'deb http://x \\\n    stable main' > f\nFROM alpine\n"
        val singleContBsTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, singleContBs, "dockerfile-single-cont-bs")
        assertEquals(SyntaxTokenKind.String, singleContBsTokens.first { it.slice(singleContBs).startsWith("'deb") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, singleContBsTokens.first { it.slice(singleContBs) == "FROM" }.kind)

        val singleContBt = "# escape=`\nRUN echo 'deb http://x `\n    stable main' > f\nFROM alpine\n"
        val singleContBtTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, singleContBt, "dockerfile-single-cont-bt")
        assertEquals(SyntaxTokenKind.String, singleContBtTokens.first { it.slice(singleContBt).startsWith("'deb") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, singleContBtTokens.first { it.slice(singleContBt) == "FROM" }.kind)

        val blankLineCont = "RUN echo a \\\n\n    \n    && echo b\nFROM alpine\n"
        val blankContTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, blankLineCont, "dockerfile-blank-cont")
        val echoToks = blankContTokens.filter { it.slice(blankLineCont) == "echo" }
        assertEquals(2, echoToks.size)
        assertTrue(echoToks.all { it.kind == SyntaxTokenKind.Function })
        assertEquals(SyntaxTokenKind.Keyword, blankContTokens.first { it.slice(blankLineCont) == "FROM" }.kind)

        val dquoteCommentCont = "RUN echo \"hello \\\n    # dquote comment\n    world\"\nFROM alpine\n"
        val dqCommentTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, dquoteCommentCont, "dockerfile-dq-comment-cont")
        assertEquals(SyntaxTokenKind.Comment, dqCommentTokens.first { it.slice(dquoteCommentCont) == "# dquote comment" }.kind)
        assertEquals(SyntaxTokenKind.String, dqCommentTokens.first { it.slice(dquoteCommentCont).contains("world") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, dqCommentTokens.first { it.slice(dquoteCommentCont) == "FROM" }.kind)
    }

    @Test
    fun restrictsHeredocsAndFlagsAndKeyValueAssignmentsByPosition() {
        val falseHeredocCode = "RUN echo ${'$'}((x<<y))\nENV A=<<EOF\nFROM alpine\n"
        val falseHeredocTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            falseHeredocCode,
            "dockerfile-no-false-heredoc",
        )
        assertEquals(SyntaxTokenKind.Keyword, falseHeredocTokens.first { it.slice(falseHeredocCode) == "ENV" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, falseHeredocTokens.first { it.slice(falseHeredocCode) == "FROM" }.kind)

        val shellEqAndFlagsCode = """
            RUN --mount=type=cache,target=/cache apt-get install --no-install-recommends curl
            RUN [ x = y ]
            COPY --from=builder /a /b
        """.trimIndent() + "\n"
        val shellEqTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            shellEqAndFlagsCode,
            "dockerfile-position-flags-eq",
        )
        assertEquals(SyntaxTokenKind.Attribute, shellEqTokens.first { it.slice(shellEqAndFlagsCode) == "--mount" }.kind)
        assertEquals(SyntaxTokenKind.Property, shellEqTokens.first { it.slice(shellEqAndFlagsCode) == "type" }.kind)
        assertEquals(SyntaxTokenKind.Property, shellEqTokens.first { it.slice(shellEqAndFlagsCode) == "target" }.kind)
        assertEquals(SyntaxTokenKind.Plain, shellEqTokens.first { it.slice(shellEqAndFlagsCode) == "--no-install-recommends" }.kind)
        assertEquals(SyntaxTokenKind.Plain, shellEqTokens.first { it.slice(shellEqAndFlagsCode) == "x" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, shellEqTokens.first { it.slice(shellEqAndFlagsCode) == "--from" }.kind)

        val kvValuesWithEqualsCode = """
            ENV JAVA_OPTS=-Dfile.encoding=UTF-8
            ENV DB_URL=postgres://h/db?sslmode=disable
            ENV A=1 B=2
            RUN ${'$'}{CC} --version
            ENTRYPOINT ${'$'}{APP} --serve
            COPY --from=${'$'}{STAGE} --link /src /dst
        """.trimIndent() + "\n"
        val kvValTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            kvValuesWithEqualsCode,
            "dockerfile-kv-eq-and-param-flags",
        )
        assertEquals(SyntaxTokenKind.Property, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "JAVA_OPTS" }.kind)
        assertEquals(SyntaxTokenKind.Plain, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "-Dfile.encoding" }.kind)
        assertEquals(SyntaxTokenKind.Property, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "DB_URL" }.kind)
        assertEquals(SyntaxTokenKind.Plain, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "postgres://h/db?sslmode" }.kind)
        assertEquals(SyntaxTokenKind.Property, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "A" }.kind)
        assertEquals(SyntaxTokenKind.Property, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "B" }.kind)
        assertEquals(SyntaxTokenKind.Plain, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "--version" }.kind)
        assertEquals(SyntaxTokenKind.Plain, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "--serve" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "--from" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, kvValTokens.first { it.slice(kvValuesWithEqualsCode) == "--link" }.kind)
    }

    @Test
    fun handlesNestedUnclosedDQuotesAndBlankOrCommentedContinuationsInStringsAndParamExp() {
        // P2-1: Unclosed '"' inside '${...}' outside and inside a heredoc unwinds to line/heredoc start.
        val unclosedDqOutside = "ENV A=${'$'}{B:-\"oops\nFROM alpine\n"
        val unclosedDqOutsideTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            unclosedDqOutside,
            "dockerfile-unclosed-dq-in-pe-outside",
        )
        assertEquals(SyntaxTokenKind.Keyword, unclosedDqOutsideTokens.first { it.slice(unclosedDqOutside) == "FROM" }.kind)

        val unclosedDqHeredoc = "RUN <<EOF\necho ${'$'}{A:-\"x\nEOF\nFROM alpine\n"
        val unclosedDqHeredocTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            unclosedDqHeredoc,
            "dockerfile-unclosed-dq-in-pe-heredoc",
        )
        assertEquals(
            SyntaxTokenKind.Keyword,
            unclosedDqHeredocTokens.last { it.slice(unclosedDqHeredoc) == "EOF" }.kind,
        )
        assertEquals(SyntaxTokenKind.Keyword, unclosedDqHeredocTokens.first { it.slice(unclosedDqHeredoc) == "FROM" }.kind)

        // P2-2: Blank/whitespace-only and '#'-comment lines inside '"..."', "'...'", and '${...}' continuations.
        val dqBlankCont = "RUN echo \"a \\\n\n  \n  # dq note\n  \n b\"\nFROM alpine\n"
        val dqBlankTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, dqBlankCont, "dockerfile-dq-blank-cont")
        assertEquals(SyntaxTokenKind.Comment, dqBlankTokens.first { it.slice(dqBlankCont) == "# dq note" }.kind)
        assertEquals(SyntaxTokenKind.String, dqBlankTokens.first { it.slice(dqBlankCont).contains("b") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, dqBlankTokens.first { it.slice(dqBlankCont) == "FROM" }.kind)

        val sqBlankCommentCont = "RUN echo 'a \\\n  \n  # sq note\n\n b'\nFROM alpine\n"
        val sqBlankTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, sqBlankCommentCont, "dockerfile-sq-blank-comment-cont")
        assertEquals(SyntaxTokenKind.Comment, sqBlankTokens.first { it.slice(sqBlankCommentCont) == "# sq note" }.kind)
        assertEquals(SyntaxTokenKind.String, sqBlankTokens.first { it.slice(sqBlankCommentCont) == " b'" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, sqBlankTokens.first { it.slice(sqBlankCommentCont) == "FROM" }.kind)

        val peBlankCommentCont = "RUN echo ${'$'}{A:-\\\n\n  \n  # pe note\n\n  fallback}\nFROM alpine\n"
        val peBlankTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, peBlankCommentCont, "dockerfile-pe-blank-comment-cont")
        assertEquals(SyntaxTokenKind.Comment, peBlankTokens.first { it.slice(peBlankCommentCont) == "# pe note" }.kind)
        assertEquals(SyntaxTokenKind.Plain, peBlankTokens.first { it.slice(peBlankCommentCont) == "fallback" }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, peBlankTokens.first { it.slice(peBlankCommentCont) == "}" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, peBlankTokens.first { it.slice(peBlankCommentCont) == "FROM" }.kind)

        // Ensure unclosed quotes/param-exp WITHOUT continuation still recover on newline.
        val unclosedNoCont = "RUN echo 'unclosed\nFROM scratch\nRUN echo ${'$'}{UNCLOSED\nFROM busybox\n"
        val unclosedNoContTokens = assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, unclosedNoCont, "dockerfile-unclosed-no-cont")
        assertEquals(2, unclosedNoContTokens.count { it.slice(unclosedNoCont) == "FROM" && it.kind == SyntaxTokenKind.Keyword })

        // P2-3: Embedded comment inside '"..."' must not stick outer continuation state onto closing newline.
        val nestedContWithDqComment =
            "RUN a \\\n" +
                "    && echo \"x \\\n" +
                "    # c\n" +
                "    y\"\n" +
                "FROM alpine\n"
        val nestedContTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            nestedContWithDqComment,
            "dockerfile-outer-cont-dq-comment",
        )
        assertEquals(SyntaxTokenKind.Comment, nestedContTokens.first { it.slice(nestedContWithDqComment) == "# c" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, nestedContTokens.first { it.slice(nestedContWithDqComment) == "FROM" }.kind)
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
        val unclosedParam = "RUN echo \${VAR:-" + "{a:".repeat(2_000)
        assertValidSnippetHasNoInvalidTokens(DockerfileSyntaxLexer, unclosedParam, "dockerfile-unclosed-param-valid")
        assertLinearPerformance(
            lexer = DockerfileSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-heredoc-4k" to ("RUN <<EOF\n" + "echo \${VAR:-a/b/c}\n".repeat(2_000)),
                "unclosed-dquote-4k" to ("LABEL a=\"" + "hello \${X} ".repeat(2_000)),
                "unclosed-param-exp-4k" to unclosedParam,
                "continuations-4k" to ("RUN echo 1 \\\n" + "    # comment\n    && echo 2 \\\n".repeat(1_000) + "    && true\n"),
            ),
            maxMillisPerInput = 300L,
        )
    }
}
