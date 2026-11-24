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

        // Heredoc bodies are taken verbatim line-by-line: disable Dockerfile continuation/comment skipping inside them.
        val heredocParamContCode =
            "RUN <<EOF\n" +
                "echo \"\${MSG:-line1 \\\n" +
                "# not a comment}\"\n" +
                "EOF\n" +
                "FROM alpine\n"
        val heredocParamContTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            heredocParamContCode,
            "dockerfile-heredoc-no-pe-cont",
        )
        assertEquals(
            SyntaxTokenKind.Keyword,
            heredocParamContTokens.last { it.slice(heredocParamContCode) == "EOF" }.kind,
        )
        assertEquals(
            SyntaxTokenKind.Keyword,
            heredocParamContTokens.first { it.slice(heredocParamContCode) == "FROM" }.kind,
        )
        assertFalse(heredocParamContTokens.any { it.kind == SyntaxTokenKind.Comment })

        val heredocSingleQuoteContCode =
            "RUN <<EOF\n" +
                "echo \${MSG:-'line1 \\\n" +
                "# not a comment'}\n" +
                "EOF\n" +
                "FROM alpine\n"
        val heredocSingleQuoteTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            heredocSingleQuoteContCode,
            "dockerfile-heredoc-no-sq-cont",
        )
        assertEquals(
            SyntaxTokenKind.Keyword,
            heredocSingleQuoteTokens.last { it.slice(heredocSingleQuoteContCode) == "EOF" }.kind,
        )
        assertEquals(
            SyntaxTokenKind.Keyword,
            heredocSingleQuoteTokens.first { it.slice(heredocSingleQuoteContCode) == "FROM" }.kind,
        )
        assertFalse(heredocSingleQuoteTokens.any { it.kind == SyntaxTokenKind.Comment })
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
    fun preservesJsonStringEscapesUnderBacktickEscapeDirective() {
        val code =
            "# escape=`\n" +
                "RUN [\"echo\", \"a\\\"b\", \"c`\", \"d\\\\\"]\n" +
                "RUN --mount=type=cache,target=/cache `\n" +
                "    [ `\n" +
                "        # array-level comment\n" +
                "\n" +
                "        \"echo\", `\n" +
                "        \"hello `\n" +
                "        # cont comment\n" +
                "        x\\\"y\" `\n" +
                "    ]\n" +
                "RUN [\"echo\", \"odd-bs\\`\n" +
                "n-continued\"]\n" +
                "CMD [\"a\\`\n" +
                "\\\", \"\$HOME\"]\n" +
                "CMD [\"q\\`\n" +
                "\"b\", \"\$HOME\"]\n" +
                "COPY --from=builder [\"\${SRC}/a\\\"b\", \"dst/\"]\n" +
                "ADD [\"\$SRC/file\", \"/dst/\"]\n" +
                "VOLUME [\"\$DATA\", \"/var/log\"]\n" +
                "ENTRYPOINT [\"/bin/app\", \"a\\\"b\", \"\$1\"]\n" +
                "SHELL [\"powershell\", \"-Command\", \"Write-Host \\\"hi\\\"\"]\n" +
                "HEALTHCHECK --interval=30s --timeout=5s CMD [\"curl\", \"a\\\"b\"]\n" +
                "ONBUILD CMD [\"sh\", \"-c\", \"echo \${FOO:-\\\"bar\\\"} \$HOME \$1\"]\n" +
                "CMD [\"C:\\app\\run.exe\", \"\$PORT\"]\n" +
                "RUN [ \"\$TARGETARCH\" = \"amd64\" ] && echo ok\n" +
                "RUN [ \"C:\\dir\\\" = \"x\" ]\n" +
                "CMD [\"a\" \"b\"]\n" +
                "RUN echo \"C:\\Windows\\\" \"line1`\"quoted\"\n" +
                "RUN [ -f \"C:\\temp\\\" ]\n" +
                "RUN [\"echo\", \"unclosed\n" +
                "FROM alpine\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            code,
            "dockerfile-json-escapes-backtick-directive",
        )

        fun tokensOnLine(line: String, fromIndex: Int = 0): List<SyntaxToken> {
            val s = code.indexOf(line, fromIndex)
            assertTrue(s >= fromIndex, "Line not found: $line")
            return tokens.filter { it.startIndex >= s && it.endIndex <= s + line.length }
        }

        // 1. Exact exec-form `RUN ["echo", "a\"b", "c`", "d\\"]`: `\"` and `\\` escape; `` ` `` stays literal String; `]` is Punctuation
        val runJsonLine = "RUN [\"echo\", \"a\\\"b\", \"c`\", \"d\\\\\"]"
        val runJsonTokens = tokensOnLine(runJsonLine)
        assertTrue(runJsonTokens.any { it.slice(code) == "\\\"" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(runJsonTokens.any { it.slice(code) == "b" && it.kind == SyntaxTokenKind.String })
        assertTrue(runJsonTokens.any { it.slice(code) == "`" && it.kind == SyntaxTokenKind.String })
        assertTrue(runJsonTokens.any { it.slice(code) == "\\\\" && it.kind == SyntaxTokenKind.Constant })
        assertEquals("]", runJsonTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, runJsonTokens.last().kind)

        // 2. Multiline JSON array (with array-level comment & blank line) + JSON string continuation + `\"`
        assertTrue(tokensOnLine("        # array-level comment").any { it.kind == SyntaxTokenKind.Comment })
        val contLine = "        x\\\"y\" `"
        val contTokens = tokensOnLine(contLine)
        assertTrue(contTokens.any { it.slice(code) == "\\\"" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(contTokens.any { it.slice(code) == "y" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokensOnLine("        # cont comment").any { it.kind == SyntaxTokenKind.Comment })
        val closeBracketTokens = tokensOnLine("    ]", code.indexOf(contLine))
        assertEquals("]", closeBracketTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, closeBracketTokens.last().kind)

        // 3. Split JSON escapes (`\n`, `\\`, `\"`) across backtick line continuations stay in sync with JSON boundaries
        val oddBsLine = "n-continued\"]"
        val oddBsTokens = tokensOnLine(oddBsLine)
        assertTrue(oddBsTokens.any { it.slice(code) == "n" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(oddBsTokens.any { it.slice(code) == "-continued" && it.kind == SyntaxTokenKind.String })
        assertEquals("]", oddBsTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, oddBsTokens.last().kind)

        val splitDoubleBsLine = "\\\", \"\$HOME\"]"
        val splitDoubleBsTokens = tokensOnLine(splitDoubleBsLine)
        assertEquals("\\", splitDoubleBsTokens.first().slice(code))
        assertEquals(SyntaxTokenKind.Constant, splitDoubleBsTokens.first().kind)
        assertTrue(splitDoubleBsTokens.any { it.slice(code) == "," && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(splitDoubleBsTokens.none { it.kind == SyntaxTokenKind.Property })
        assertEquals("]", splitDoubleBsTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, splitDoubleBsTokens.last().kind)

        val splitQuoteLine = "\"b\", \"\$HOME\"]"
        val splitQuoteTokens = tokensOnLine(splitQuoteLine)
        assertEquals("\"", splitQuoteTokens.first().slice(code))
        assertEquals(SyntaxTokenKind.Constant, splitQuoteTokens.first().kind)
        assertTrue(splitQuoteTokens.any { it.slice(code) == "b" && it.kind == SyntaxTokenKind.String })
        assertTrue(splitQuoteTokens.none { it.kind == SyntaxTokenKind.Property })
        assertEquals("]", splitQuoteTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, splitQuoteTokens.last().kind)

        // 4. COPY/ADD/VOLUME preserve BOTH JSON `\"` escapes AND variable expansion highlighting (`Property`)
        val copyLine = "COPY --from=builder [\"\${SRC}/a\\\"b\", \"dst/\"]"
        val copyTokens = tokensOnLine(copyLine)
        assertTrue(copyTokens.any { it.slice(code) == "SRC" && it.kind == SyntaxTokenKind.Property })
        assertTrue(copyTokens.any { it.slice(code) == "\\\"" && it.kind == SyntaxTokenKind.Constant })
        assertEquals("]", copyTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, copyTokens.last().kind)

        assertTrue(tokensOnLine("ADD [\"\$SRC/file\", \"/dst/\"]").any { it.slice(code) == "\$SRC" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokensOnLine("VOLUME [\"\$DATA\", \"/var/log\"]").any { it.slice(code) == "\$DATA" && it.kind == SyntaxTokenKind.Property })

        // 5. ENTRYPOINT, SHELL, HEALTHCHECK (multiple flags) CMD, ONBUILD CMD keep JSON escapes & suppress `$` / `$1` expansion
        for (line in listOf(
            "ENTRYPOINT [\"/bin/app\", \"a\\\"b\", \"\$1\"]",
            "SHELL [\"powershell\", \"-Command\", \"Write-Host \\\"hi\\\"\"]",
            "HEALTHCHECK --interval=30s --timeout=5s CMD [\"curl\", \"a\\\"b\"]",
            "ONBUILD CMD [\"sh\", \"-c\", \"echo \${FOO:-\\\"bar\\\"} \$HOME \$1\"]",
        )) {
            val lt = tokensOnLine(line)
            assertTrue(lt.any { it.slice(code) == "\\\"" && it.kind == SyntaxTokenKind.Constant }, "Missing \\\" escape in: $line")
            assertEquals("]", lt.last().slice(code), "Expected closing ] Punctuation in: $line")
            assertEquals(SyntaxTokenKind.Punctuation, lt.last().kind, "Expected closing ] Punctuation in: $line")
            assertTrue(lt.none { it.kind == SyntaxTokenKind.Property }, "Unexpected variable expansion in exec form: $line")
            assertTrue(lt.none { it.slice(code) == "\$1" && it.kind == SyntaxTokenKind.Constant }, "Unexpected \$1 expansion in: $line")
        }

        // 6. Invalid JSON escape (`\a` in Windows path) falls back to shell form: `\` stays String and `$PORT` expands as Property
        val invalidJsonEscTokens = tokensOnLine("CMD [\"C:\\app\\run.exe\", \"\$PORT\"]")
        assertTrue(invalidJsonEscTokens.any { it.slice(code) == "\\" && it.kind == SyntaxTokenKind.String })
        assertTrue(invalidJsonEscTokens.any { it.slice(code) == "\$PORT" && it.kind == SyntaxTokenKind.Property })

        // 7. Default backslash escape mode also falls back to shell form on invalid JSON escapes
        val defaultEscInvalidJson = "CMD [\"C:\\app\\run.exe\", \"\$PORT\"]\n"
        val defaultEscTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            defaultEscInvalidJson,
            "dockerfile-default-esc-invalid-json-fallback",
        )
        assertTrue(defaultEscTokens.any { it.slice(defaultEscInvalidJson) == "\$PORT" && it.kind == SyntaxTokenKind.Property })

        // 8. POSIX `[ "$TARGETARCH" = "amd64" ]`, `[ "C:\dir\" = "x" ]`, and invalid `CMD ["a" "b"]` stay in shell form
        val posixVarTokens = tokensOnLine("RUN [ \"\$TARGETARCH\" = \"amd64\" ] && echo ok")
        assertTrue(posixVarTokens.any { it.slice(code) == "\$TARGETARCH" && it.kind == SyntaxTokenKind.Property })
        assertTrue(posixVarTokens.any { it.slice(code) == "=" && it.kind == SyntaxTokenKind.Operator })

        val posixWinTokens = tokensOnLine("RUN [ \"C:\\dir\\\" = \"x\" ]")
        assertTrue(posixWinTokens.any { it.slice(code) == "=" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(posixWinTokens.any { it.slice(code) == "x" && it.kind == SyntaxTokenKind.String })
        assertEquals("]", posixWinTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, posixWinTokens.last().kind)

        val missingCommaTokens = tokensOnLine("CMD [\"a\" \"b\"]")
        assertEquals("]", missingCommaTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, missingCommaTokens.last().kind)

        // 9. Non-JSON shell form & shell `[ -f ... ]` still respect `# escape=\``
        val shellTokens = tokensOnLine("RUN echo \"C:\\Windows\\\" \"line1`\"quoted\"")
        assertTrue(shellTokens.any { it.slice(code) == "\\" && it.kind == SyntaxTokenKind.String })
        assertTrue(shellTokens.any { it.slice(code) == "`\"" && it.kind == SyntaxTokenKind.Constant })

        val testBracketTokens = tokensOnLine("RUN [ -f \"C:\\temp\\\" ]")
        assertTrue(testBracketTokens.any { it.slice(code) == "\\" && it.kind == SyntaxTokenKind.String })
        assertEquals("]", testBracketTokens.last().slice(code))
        assertEquals(SyntaxTokenKind.Punctuation, testBracketTokens.last().kind)

        // 10. Unclosed JSON string unwinds cleanly on newline
        val fromTokens = tokensOnLine("FROM alpine")
        assertEquals("FROM", fromTokens.first().slice(code))
        assertEquals(SyntaxTokenKind.Keyword, fromTokens.first().kind)
    }

    @Test
    fun consumesCompleteShellWordHeredocDelimitersWithDotsQuotesAndEscapes() {
        val code =
            "RUN <<EOF.sh\n" +
                "echo \$VAR\n" +
                "EOF.sh\n" +
                "FROM alpine AS s1\n" +
                "COPY <<-requirements.txt /app/requirements.txt\n" +
                "\tflask==3.0\n" +
                "\trequirements.txt\n" +
                "RUN <<E'O'F.sh\n" +
                "echo \$LITERAL1\n" +
                "EOF.sh\n" +
                "RUN <<\\EOF.txt\n" +
                "echo \$LITERAL2\n" +
                "EOF.txt\n" +
                "RUN <<\"E\\\"O\\\"F\"\n" +
                "echo \$LITERAL3\n" +
                "E\"O\"F\n" +
                "RUN cat <<< \"here-string\"\n" +
                "FROM busybox AS final\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            code,
            "dockerfile-shell-word-heredoc-delims",
        )

        fun tokensOnLine(line: String, fromIndex: Int = 0): List<SyntaxToken> {
            val s = code.indexOf(line, fromIndex)
            assertTrue(s >= fromIndex, "Line not found: $line")
            return tokens.filter { it.startIndex >= s && it.endIndex <= s + line.length }
        }

        // 1. Unquoted `<<EOF.sh` keeps expansion active and closes at `EOF.sh`, resuming `FROM alpine AS s1`
        val open1 = tokensOnLine("RUN <<EOF.sh")
        assertTrue(open1.any { it.slice(code) == "EOF.sh" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(tokensOnLine("echo \$VAR").any { it.slice(code) == "\$VAR" && it.kind == SyntaxTokenKind.Property })
        val close1 = tokensOnLine("EOF.sh", code.indexOf("echo \$VAR"))
        assertEquals(listOf("EOF.sh"), close1.map { it.slice(code) })
        assertEquals(SyntaxTokenKind.Keyword, close1.single().kind)
        assertEquals(SyntaxTokenKind.Keyword, tokensOnLine("FROM alpine AS s1").first().kind)

        // 2. `COPY <<-requirements.txt /app/requirements.txt` strips leading tab on closing `requirements.txt`
        val copyOpen = tokensOnLine("COPY <<-requirements.txt /app/requirements.txt")
        assertTrue(copyOpen.any { it.slice(code) == "requirements.txt" && it.kind == SyntaxTokenKind.Keyword })
        val copyClose = tokensOnLine("\trequirements.txt")
        assertTrue(copyClose.any { it.slice(code) == "requirements.txt" && it.kind == SyntaxTokenKind.Keyword })

        // 3. Partially quoted `<<E'O'F.sh`, backslash-escaped `<<\EOF.txt`, and escaped double-quoted `<<"E\"O\"F"` suppress expansion
        assertTrue(tokensOnLine("echo \$LITERAL1").none { it.kind == SyntaxTokenKind.Property })
        val close3 = tokensOnLine("EOF.sh", code.indexOf("echo \$LITERAL1"))
        assertEquals(SyntaxTokenKind.Keyword, close3.single().kind)

        assertTrue(tokensOnLine("echo \$LITERAL2").none { it.kind == SyntaxTokenKind.Property })
        val close4 = tokensOnLine("EOF.txt", code.indexOf("echo \$LITERAL2"))
        assertEquals(SyntaxTokenKind.Keyword, close4.single().kind)

        assertTrue(tokensOnLine("echo \$LITERAL3").none { it.kind == SyntaxTokenKind.Property })
        val close5 = tokensOnLine("E\"O\"F", code.indexOf("echo \$LITERAL3"))
        assertEquals(SyntaxTokenKind.Keyword, close5.single().kind)

        // 4. Bash here-string `<<<` is not mistaken for a heredoc, and final `FROM` resumes as Keyword
        val hereString = tokensOnLine("RUN cat <<< \"here-string\"")
        assertTrue(hereString.any { it.slice(code) == "<<" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(hereString.any { it.slice(code) == "<" && it.kind == SyntaxTokenKind.Operator })
        val finalFrom = tokensOnLine("FROM busybox AS final")
        assertEquals("FROM", finalFrom.first().slice(code))
        assertEquals(SyntaxTokenKind.Keyword, finalFrom.first().kind)

        // 5. Bare `<<-` followed by space, operator, or EOF must stay OP_MULTI (never queue an empty heredoc delimiter)
        val bareDashHeredoc =
            "RUN cat <<- EOF\n" +
                "\tbody line\n" +
                "\tEOF\n" +
                "FROM alpine AS after_bare_dash\n"
        val bareDashTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            bareDashHeredoc,
            "dockerfile-bare-heredoc-dash-space",
        )
        assertTrue(bareDashTokens.any { it.slice(bareDashHeredoc) == "<<-" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(bareDashTokens.any { it.slice(bareDashHeredoc) == "FROM" && it.kind == SyntaxTokenKind.Keyword })

        val bareDashBeforeOp =
            "RUN cat <<-; echo ok\n" +
                "FROM alpine AS after_bare_dash_op\n"
        val bareDashOpTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            bareDashBeforeOp,
            "dockerfile-bare-heredoc-dash-before-op",
        )
        assertTrue(bareDashOpTokens.any { it.slice(bareDashBeforeOp) == "<<-" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(bareDashOpTokens.any { it.slice(bareDashBeforeOp) == ";" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(bareDashOpTokens.any { it.slice(bareDashBeforeOp) == "FROM" && it.kind == SyntaxTokenKind.Keyword })

        val bareDashAtEof = "RUN cat <<-"
        val bareDashEofTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            bareDashAtEof,
            "dockerfile-bare-heredoc-dash-eof",
        )
        assertTrue(bareDashEofTokens.any { it.slice(bareDashAtEof) == "<<-" && it.kind == SyntaxTokenKind.Operator })

        // 6. Leading-dash delimiters: `<<--EOF` strips tabs and expands; `<<"-EOF"` and `<<\-EOF` do not strip tabs and stay literal
        val leadingDashHeredoc =
            "RUN cat <<--EOF\n" +
                "\techo \$EXPANDED\n" +
                "\t-EOF\n" +
                "FROM alpine AS after_double_dash\n" +
                "RUN cat <<\"-EOF\"\n" +
                "\t-EOF\n" +
                "echo \$NO_EXPAND_DQ\n" +
                "-EOF\n" +
                "FROM alpine AS after_quoted_dash\n" +
                "RUN cat <<\\-EOF\n" +
                "\t-EOF\n" +
                "echo \$NO_EXPAND_ESC\n" +
                "-EOF\n" +
                "FROM scratch AS after_escaped_dash\n"
        val leadingDashTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            leadingDashHeredoc,
            "dockerfile-leading-dash-heredoc-delimiters",
        )
        fun leadingDashLineTokens(prefix: String): List<SyntaxToken> {
            val start = leadingDashHeredoc.indexOf(prefix)
            val end = leadingDashHeredoc.indexOf('\n', start).let { if (it < 0) leadingDashHeredoc.length else it }
            return leadingDashTokens.filter { it.startIndex >= start && it.endIndex <= end }
        }
        val doubleDashOpen = leadingDashLineTokens("RUN cat <<--EOF")
        assertTrue(doubleDashOpen.any { it.slice(leadingDashHeredoc) == "<<-" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(doubleDashOpen.any { it.slice(leadingDashHeredoc) == "-EOF" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(leadingDashTokens.any { it.slice(leadingDashHeredoc) == "\$EXPANDED" && it.kind == SyntaxTokenKind.Property })
        val doubleDashCloseIdx = leadingDashHeredoc.indexOf("\t-EOF\n") + 1
        assertTrue(
            leadingDashTokens.any {
                it.startIndex == doubleDashCloseIdx &&
                    it.slice(leadingDashHeredoc) == "-EOF" &&
                    it.kind == SyntaxTokenKind.Keyword
            },
        )
        val quotedDashOpen = leadingDashLineTokens("RUN cat <<\"-EOF\"")
        assertTrue(quotedDashOpen.any { it.slice(leadingDashHeredoc) == "<<" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(quotedDashOpen.any { it.slice(leadingDashHeredoc) == "\"-EOF\"" && it.kind == SyntaxTokenKind.Keyword })
        val quotedFakeCloseIdx = leadingDashHeredoc.indexOf("\t-EOF\n", startIndex = leadingDashHeredoc.indexOf("RUN cat <<\"-EOF\""))
        assertTrue(
            leadingDashTokens.any {
                it.startIndex == quotedFakeCloseIdx &&
                    it.slice(leadingDashHeredoc) == "\t-EOF" &&
                    it.kind == SyntaxTokenKind.String
            },
        )
        assertTrue(leadingDashTokens.none { it.slice(leadingDashHeredoc) == "\$NO_EXPAND_DQ" && it.kind == SyntaxTokenKind.Property })
        val escapedDashOpen = leadingDashLineTokens("RUN cat <<\\-EOF")
        assertTrue(escapedDashOpen.any { it.slice(leadingDashHeredoc) == "<<" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(escapedDashOpen.any { it.slice(leadingDashHeredoc) == "\\-EOF" && it.kind == SyntaxTokenKind.Keyword })
        val escapedFakeCloseIdx = leadingDashHeredoc.indexOf("\t-EOF\n", startIndex = leadingDashHeredoc.indexOf("RUN cat <<\\-EOF"))
        assertTrue(
            leadingDashTokens.any {
                it.startIndex == escapedFakeCloseIdx &&
                    it.slice(leadingDashHeredoc) == "\t-EOF" &&
                    it.kind == SyntaxTokenKind.String
            },
        )
        assertTrue(leadingDashTokens.none { it.slice(leadingDashHeredoc) == "\$NO_EXPAND_ESC" && it.kind == SyntaxTokenKind.Property })
        assertEquals(
            3,
            leadingDashTokens.count { it.slice(leadingDashHeredoc) == "FROM" && it.kind == SyntaxTokenKind.Keyword },
        )

        // 7. Backslash + trailing spaces + LF/CRLF/CR right after unquoted or quoted heredoc delimiter stays a line continuation
        val trailingContHeredoc =
            "RUN cat <<EOF\\   \n" +
                "    && echo chained\n" +
                "body1\n" +
                "EOF\n" +
                "FROM alpine AS after_unquoted_cont\n" +
                "RUN cat <<\"QUOTED\"\\ \t\n" +
                "    && echo chained2\n" +
                "body2\n" +
                "QUOTED\n" +
                "FROM scratch AS after_quoted_cont\n" +
                "RUN cat <<EOF_CRLF\\  \r\n" +
                "    && echo chained_crlf\r\n" +
                "body_crlf\r\n" +
                "EOF_CRLF\r\n" +
                "FROM alpine AS after_crlf_cont\r\n" +
                "RUN cat <<EOF_CR\\ \t\r" +
                "    && echo chained_cr\r" +
                "body_cr\r" +
                "EOF_CR\r" +
                "FROM scratch AS after_cr_cont\r"
        val trailingContTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            trailingContHeredoc,
            "dockerfile-heredoc-trailing-ws-backslash-cont",
        )
        val eofOpenLineStart = trailingContHeredoc.indexOf("RUN cat <<EOF\\")
        val eofOpenLineEnd = trailingContHeredoc.indexOf('\n', eofOpenLineStart)
        val eofOpenLineTokens = trailingContTokens.filter { it.startIndex >= eofOpenLineStart && it.endIndex <= eofOpenLineEnd + 1 }
        assertTrue(eofOpenLineTokens.any { it.slice(trailingContHeredoc) == "EOF" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(eofOpenLineTokens.any { it.slice(trailingContHeredoc) == "\\   \n" && it.kind == SyntaxTokenKind.Whitespace })
        val quotedOpenLineStart = trailingContHeredoc.indexOf("RUN cat <<\"QUOTED\"")
        val quotedOpenLineEnd = trailingContHeredoc.indexOf('\n', quotedOpenLineStart)
        val quotedOpenLineTokens = trailingContTokens.filter { it.startIndex >= quotedOpenLineStart && it.endIndex <= quotedOpenLineEnd + 1 }
        assertTrue(quotedOpenLineTokens.any { it.slice(trailingContHeredoc) == "\"QUOTED\"" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(quotedOpenLineTokens.any { it.slice(trailingContHeredoc) == "\\ \t\n" && it.kind == SyntaxTokenKind.Whitespace })
        val crlfOpenLineStart = trailingContHeredoc.indexOf("RUN cat <<EOF_CRLF\\")
        val crlfOpenLineEnd = trailingContHeredoc.indexOf("\r\n", crlfOpenLineStart) + 2
        val crlfOpenLineTokens = trailingContTokens.filter { it.startIndex >= crlfOpenLineStart && it.endIndex <= crlfOpenLineEnd }
        assertTrue(crlfOpenLineTokens.any { it.slice(trailingContHeredoc) == "EOF_CRLF" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(crlfOpenLineTokens.any { it.slice(trailingContHeredoc) == "\\  \r\n" && it.kind == SyntaxTokenKind.Whitespace })
        val crOpenLineStart = trailingContHeredoc.indexOf("RUN cat <<EOF_CR\\")
        val crOpenLineEnd = trailingContHeredoc.indexOf('\r', crOpenLineStart) + 1
        val crOpenLineTokens = trailingContTokens.filter { it.startIndex >= crOpenLineStart && it.endIndex <= crOpenLineEnd }
        assertTrue(crOpenLineTokens.any { it.slice(trailingContHeredoc) == "EOF_CR" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(crOpenLineTokens.any { it.slice(trailingContHeredoc) == "\\ \t\r" && it.kind == SyntaxTokenKind.Whitespace })
        assertEquals(
            4,
            trailingContTokens.count { it.slice(trailingContHeredoc) == "FROM" && it.kind == SyntaxTokenKind.Keyword },
        )
        assertTrue(
            trailingContTokens.any {
                it.slice(trailingContHeredoc) == "QUOTED" &&
                    it.startIndex == trailingContHeredoc.indexOf("\nQUOTED\n") + 1 &&
                    it.kind == SyntaxTokenKind.Keyword
            },
        )

        // 8. Under `# escape=\``, backtick+ws+newline stays BACKTICK_LINE_CONT while `\ ` is part of the shell-word delimiter
        val backtickEscapeHeredoc =
            "# escape=`\n" +
                "RUN cat <<EOF`   \n" +
                "    && echo chained_bt\n" +
                "body_bt\n" +
                "EOF\n" +
                "FROM alpine AS after_bt_cont\n" +
                "RUN cat <<EOF\\ \n" +
                "EOF\n" +
                "EOF \n" +
                "FROM scratch AS after_bt_esc_space\n"
        val btTokens = assertValidSnippetHasNoInvalidTokens(
            DockerfileSyntaxLexer,
            backtickEscapeHeredoc,
            "dockerfile-heredoc-backtick-escape",
        )
        val btContLineStart = backtickEscapeHeredoc.indexOf("RUN cat <<EOF`")
        val btContLineEnd = backtickEscapeHeredoc.indexOf('\n', btContLineStart) + 1
        val btContLineTokens = btTokens.filter { it.startIndex >= btContLineStart && it.endIndex <= btContLineEnd }
        assertTrue(btContLineTokens.any { it.slice(backtickEscapeHeredoc) == "EOF" && it.kind == SyntaxTokenKind.Keyword })
        assertTrue(btContLineTokens.any { it.slice(backtickEscapeHeredoc) == "`   \n" && it.kind == SyntaxTokenKind.Whitespace })
        val btEscSpaceOpenStart = backtickEscapeHeredoc.indexOf("RUN cat <<EOF\\ ")
        val btEscSpaceOpenEnd = backtickEscapeHeredoc.indexOf('\n', btEscSpaceOpenStart) + 1
        val btEscSpaceOpenTokens = btTokens.filter { it.startIndex >= btEscSpaceOpenStart && it.endIndex <= btEscSpaceOpenEnd }
        assertTrue(btEscSpaceOpenTokens.any { it.slice(backtickEscapeHeredoc) == "EOF\\ " && it.kind == SyntaxTokenKind.Keyword })
        val innerNonClosingEofIdx = backtickEscapeHeredoc.indexOf("\nEOF\n", startIndex = btEscSpaceOpenStart) + 1
        assertTrue(
            btTokens.any {
                it.startIndex == innerNonClosingEofIdx &&
                    it.slice(backtickEscapeHeredoc) == "EOF" &&
                    it.kind == SyntaxTokenKind.String
            },
        )
        val closingEofSpaceIdx = backtickEscapeHeredoc.indexOf("\nEOF \n") + 1
        assertTrue(
            btTokens.any {
                it.startIndex == closingEofSpaceIdx &&
                    it.slice(backtickEscapeHeredoc) == "EOF " &&
                    it.kind == SyntaxTokenKind.Keyword
            },
        )
        assertEquals(
            2,
            btTokens.count { it.slice(backtickEscapeHeredoc) == "FROM" && it.kind == SyntaxTokenKind.Keyword },
        )
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
                "unclosed-heredoc-squote-4k" to ("RUN cat <<'" + "a".repeat(4_000) + "\nFROM alpine\n"),
                "unclosed-heredoc-dquote-4k" to ("RUN cat <<\"" + "a".repeat(4_000) + "\nFROM alpine\n"),
                "continuations-4k" to ("RUN echo 1 \\\n" + "    # comment\n    && echo 2 \\\n".repeat(1_000) + "    && true\n"),
            ),
            maxMillisPerInput = 300L,
        )
    }
}
