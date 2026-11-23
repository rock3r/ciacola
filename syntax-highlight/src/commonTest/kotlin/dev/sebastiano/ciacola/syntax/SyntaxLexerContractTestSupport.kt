package dev.sebastiano.ciacola.syntax

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Verifies that [lexer] produces lossless, strictly contiguous, non-overlapping [SyntaxToken]
 * spans covering `[0, code.length)` whose concatenated slices equal [code].
 */
fun assertLosslessInvariants(
    lexer: SyntaxLexer,
    code: String,
    label: String = "",
): List<SyntaxToken> {
    val prefix = if (label.isNotEmpty()) "[$label] " else ""
    val tokens = lexer.lex(code)
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
        assertLosslessInvariants(lexer, input, label = "${lexer.language.id}:perf:$label")
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
