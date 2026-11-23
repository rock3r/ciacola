package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.oracle.OracleFixture
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Verifies that [lexer] produces lossless, strictly contiguous, non-overlapping [SyntaxToken]
 * spans covering `[0, code.length)` whose concatenated slices equal [code].
 */
fun assertLosslessInvariants(
    lexer: SyntaxLexer,
    code: String,
    label: String = "",
): List<SyntaxToken> = assertLosslessInvariants(
    code = code,
    tokens = lexer.lex(code),
    label = label,
)

/**
 * Verifies that pre-lexed [tokens] form lossless, strictly contiguous, non-overlapping
 * [SyntaxToken] spans covering `[0, code.length)` whose concatenated slices equal [code].
 */
fun assertLosslessInvariants(
    code: String,
    tokens: List<SyntaxToken>,
    label: String = "",
): List<SyntaxToken> {
    val prefix = if (label.isNotEmpty()) "[$label] " else ""
    if (code.isEmpty()) {
        assertTrue(tokens.isEmpty(), "${prefix}Empty input must produce zero tokens, got $tokens")
        return tokens
    }
    assertTrue(tokens.isNotEmpty(), "${prefix}Non-empty input (${code.length} chars) must produce >= 1 token")
    var expectedStart = 0
    for ((idx, token) in tokens.withIndex()) {
        assertTrue(
            token.length > 0,
            "${prefix}Token #$idx has non-positive length (${token.startIndex}..${token.endIndex}): $token",
        )
        assertEquals(
            expectedStart,
            token.startIndex,
            "${prefix}Token #$idx startIndex gap/overlap (expected $expectedStart, got ${token.startIndex}): $token",
        )
        assertTrue(
            token.endIndex <= code.length,
            "${prefix}Token #$idx endIndex (${token.endIndex}) exceeds input length (${code.length}): $token",
        )
        expectedStart = token.endIndex
    }
    assertEquals(
        code.length,
        expectedStart,
        "${prefix}Final token endIndex ($expectedStart) != input length (${code.length})",
    )
    val reconstructed = buildString(code.length) {
        for (token in tokens) {
            append(token.slice(code))
        }
    }
    assertEquals(code, reconstructed, "${prefix}Lossless slice reconstruction mismatch")
    return tokens
}

/**
 * Lexes every prefix `code.substring(0, k)` (stepping by [maxStep], always including `0` and
 * `code.length`) and verifies [assertLosslessInvariants] on each truncated slice.
 */
fun assertPrefixTruncationSafety(
    lexer: SyntaxLexer,
    code: String,
    maxStep: Int = 1,
) {
    val step = maxStep.coerceAtLeast(1)
    var k = 0
    while (k <= code.length) {
        assertLosslessInvariants(
            lexer = lexer,
            code = code.substring(0, k),
            label = "${lexer.language.id}:prefix($k/${code.length})",
        )
        if (k == code.length) break
        k = (k + step).coerceAtMost(code.length)
    }
}

/**
 * Exercises [lexer] against curated malformed inputs (unclosed quotes/comments/brackets, CRLF/CR,
 * surrogate pairs, emoji, control characters) plus [iterations] deterministic PRNG inputs.
 */
fun assertMalformedAndFuzzSafety(
    lexer: SyntaxLexer,
    seed: Long = 0xC1AC01AL,
    iterations: Int = 60,
) {
    val curatedMalformed = listOf(
        "",
        " ",
        "\r",
        "\n",
        "\r\n\r\n",
        "\"",
        "'",
        "\"\"\"",
        "\"unclosed\\",
        "'unclosed\\",
        "/*",
        "/* /* nested",
        "<!-- unclosed",
        "<![CDATA[ unclosed",
        "<?xml unclosed",
        "{{{((([[[",
        "}}}})))]]]",
        "// trailing without newline",
        "# preprocessor\r\n/* unclosed",
        "\"emoji 🎉🚀 café 日本語 𐐷\"",
        "\u0000\u0001\u000B\u001F\u007F",
        "\\ / < > & @ # $ % ^ * ~ ` ! ?",
    )
    for ((idx, sample) in curatedMalformed.withIndex()) {
        assertLosslessInvariants(lexer, sample, label = "${lexer.language.id}:malformed#$idx")
    }

    val alphabet = charArrayOf(
        'a', 'z', 'A', 'Z', '0', '9', '_', ' ', '\t', '\n', '\r',
        '"', '\'', '`', '\\', '/', '*', '#', ';', ':', ',', '.',
        '(', ')', '{', '}', '[', ']', '<', '>', '=', '+', '-',
        '!', '?', '&', '|', '@', '$', '%', '^', '~',
    )
    val unicodeExtras = listOf("é", "π", "中", "🎉", "𐐷", "\u0000", "\u001B")
    val rng = Random(seed)

    for (iter in 0 until iterations) {
        val len = rng.nextInt(1, 240)
        val generated = buildString(len + 8) {
            repeat(len) {
                if (rng.nextInt(25) == 0) {
                    append(unicodeExtras[rng.nextInt(unicodeExtras.size)])
                } else {
                    append(alphabet[rng.nextInt(alphabet.size)])
                }
            }
        }
        assertLosslessInvariants(lexer, generated, label = "${lexer.language.id}:fuzz#$iter")
    }
}

/**
 * Verifies that [lexer] processes every `(label, input)` pair in [pathologicalInputs] within
 * [maxMillisPerInput] milliseconds and satisfies [assertLosslessInvariants].
 */
fun assertLinearPerformance(
    lexer: SyntaxLexer,
    pathologicalInputs: List<Pair<String, String>>,
    maxMillisPerInput: Long = 2_000L,
) {
    for ((label, input) in pathologicalInputs) {
        val mark = TimeSource.Monotonic.markNow()
        val tokens = lexer.lex(input)
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertLosslessInvariants(code = input, tokens = tokens, label = "${lexer.language.id}:perf:$label")
        assertTrue(
            tokens.isNotEmpty() || input.isEmpty(),
            "[${lexer.language.id}:perf:$label] Expected tokens for non-empty input",
        )
        assertTrue(
            elapsedMs <= maxMillisPerInput,
            "[${lexer.language.id}:perf:$label] Lexing ${input.length} chars took ${elapsedMs}ms (limit: ${maxMillisPerInput}ms)",
        )
    }
}

/**
 * Runs [assertMatchesOracleFixture] on every fixture in [fixtures] and reports all failures together.
 */
fun assertAllOracleFixtures(fixtures: List<OracleFixture>) {
    val failures = mutableListOf<String>()
    for (fixture in fixtures) {
        try {
            assertMatchesOracleFixture(fixture)
        } catch (e: AssertionError) {
            failures += (e.message ?: "Fixture ${fixture.name} failed")
        }
    }
    if (failures.isNotEmpty()) {
        fail(failures.joinToString("\n\n"))
    }
}

/**
 * Verifies that lexing a valid [code] snippet satisfies [assertLosslessInvariants] and emits zero
 * [SyntaxTokenKind.Invalid] tokens.
 */
fun assertValidSnippetHasNoInvalidTokens(
    lexer: SyntaxLexer,
    code: String,
    label: String = lexer.language.id,
): List<SyntaxToken> {
    val tokens = assertLosslessInvariants(lexer, code, label = "valid:$label")
    val invalidTokens = tokens.filter { it.kind == SyntaxTokenKind.Invalid }
    assertTrue(
        invalidTokens.isEmpty(),
        "[valid:$label] Expected zero Invalid tokens for valid ${lexer.language.id} snippet, got: " +
            invalidTokens.joinToString(", ") { "${it.startIndex}..${it.endIndex}='${it.slice(code)}'" },
    )
    return tokens
}

/**
 * Verifies the concatenation property `lex(container + tail) == lex(container) + shift(lex(tail))`
 * for self-delimited container tokens (comments, strings, raw strings, CDATA, PIs, DTDs, embedded
 * blocks, etc.) followed by delimiter-bearing [tails]. Catches longest-match hazards where a
 * container rule accidentally consumes across a closing delimiter into following tokens.
 */
fun assertContainerTokenConcatenation(
    lexer: SyntaxLexer,
    containers: List<Pair<String, SyntaxTokenKind?>>,
    tails: List<String>,
    label: String = lexer.language.id,
) {
    for ((cIdx, entry) in containers.withIndex()) {
        val (container, expectedKind) = entry
        val baseTokens = assertLosslessInvariants(
            lexer = lexer,
            code = container,
            label = "concat:$label:container#$cIdx",
        )
        if (expectedKind != null) {
            assertEquals(
                1,
                baseTokens.size,
                "[concat:$label:container#$cIdx] Expected single container token for '$container', got $baseTokens",
            )
            assertEquals(
                expectedKind,
                baseTokens.single().kind,
                "[concat:$label:container#$cIdx] Expected kind $expectedKind for '$container'",
            )
        }
        for ((tIdx, tail) in tails.withIndex()) {
            val tailTokens = assertLosslessInvariants(
                lexer = lexer,
                code = tail,
                label = "concat:$label:tail#$tIdx",
            )
            val combined = container + tail
            val combinedTokens = assertLosslessInvariants(
                lexer = lexer,
                code = combined,
                label = "concat:$label:c$cIdx+t$tIdx",
            )
            val expected = baseTokens + tailTokens.map { tok ->
                tok.copy(
                    startIndex = tok.startIndex + container.length,
                    endIndex = tok.endIndex + container.length,
                )
            }
            assertEquals(
                expected,
                combinedTokens,
                "[concat:$label] Concatenation mismatch for container='$container' + tail='$tail'",
            )
        }
    }
}
