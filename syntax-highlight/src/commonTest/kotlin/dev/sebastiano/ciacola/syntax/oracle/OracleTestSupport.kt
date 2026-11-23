package dev.sebastiano.ciacola.syntax.oracle

import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.assertLosslessInvariants
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

data class OracleTokenExpectation(
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val shikiKind: SyntaxTokenKind,
    val pygmentsKind: SyntaxTokenKind,
    val allowedKinds: Set<SyntaxTokenKind> = setOf(shikiKind, pygmentsKind),
    val note: String = "",
)

data class OracleFixture(
    val name: String,
    val language: SyntaxLanguage,
    val code: String,
    val expectations: List<OracleTokenExpectation>,
    val minAgreementRatio: Double = 0.92,
    val maxOverrideShare: Double = 0.25,
)

private data class OracleMismatch(
    val index: Int,
    val expectation: OracleTokenExpectation,
    val actualKind: SyntaxTokenKind?,
    val overlapping: List<SyntaxToken>,
    val isOverride: Boolean,
)

/**
 * Verifies [fixture] against [SyntaxHighlighter.lex]:
 * 1. Confirms lossless invariants on [OracleFixture.code] and non-empty [OracleFixture.expectations].
 * 2. Enforces non-empty `allowedKinds`, single-kind replacing-only overrides (zero overlap with
 *    `{shikiKind, pygmentsKind}`) with explanatory notes, non-zero raw-oracle coverage, and
 *    `overrideShare <= maxOverrideShare`.
 * 3. Matches each [OracleTokenExpectation] span against the dominant overlapping non-whitespace
 *    emitted token kind.
 * 4. Fails with a detailed diff if any `STRICT:` or override expectation mismatches, or if either
 *    raw-oracle agreement or overall agreement falls below [OracleFixture.minAgreementRatio].
 */
fun assertMatchesOracleFixture(fixture: OracleFixture) {
    val lexer = SyntaxHighlighter.lexer(fixture.language)
    val tokens = assertLosslessInvariants(lexer, fixture.code, label = "oracle:${fixture.name}")
    assertTrue(
        fixture.expectations.isNotEmpty(),
        "Oracle fixture '${fixture.name}' (${fixture.language.id}) must have non-empty expectations",
    )

    val mismatches = mutableListOf<OracleMismatch>()
    val strictMismatches = mutableListOf<OracleMismatch>()
    val overrideMismatches = mutableListOf<OracleMismatch>()
    var matchedCount = 0
    var rawTotal = 0
    var rawMatched = 0
    var overrideTotal = 0

    for ((idx, exp) in fixture.expectations.withIndex()) {
        assertTrue(
            exp.allowedKinds.isNotEmpty(),
            "Oracle fixture '${fixture.name}' expectation #$idx ('${exp.text}') must have non-empty allowedKinds",
        )
        val extendsBeyondOracles = !exp.allowedKinds.all { it == exp.shikiKind || it == exp.pygmentsKind }
        if (extendsBeyondOracles) {
            overrideTotal++
            assertTrue(
                exp.note.isNotBlank(),
                "Oracle fixture '${fixture.name}' expectation #$idx ('${exp.text}') extends allowedKinds beyond {shiki=${exp.shikiKind}, pygments=${exp.pygmentsKind}} to ${exp.allowedKinds} without an explanatory note",
            )
            assertTrue(
                exp.allowedKinds.none { it == exp.shikiKind || it == exp.pygmentsKind },
                "Oracle fixture '${fixture.name}' expectation #$idx ('${exp.text}') uses a widening override ${exp.allowedKinds} that retains oracle kind(s) {shiki=${exp.shikiKind}, pygments=${exp.pygmentsKind}}; overrides must replace oracle kinds",
            )
            assertTrue(
                exp.allowedKinds.size == 1,
                "Oracle fixture '${fixture.name}' expectation #$idx ('${exp.text}') extends beyond oracle kinds with multiple allowedKinds ${exp.allowedKinds}; overrides must specify a single intended kind",
            )
        } else {
            rawTotal++
        }
        assertTrue(
            exp.startIndex >= 0 && exp.endIndex > exp.startIndex && exp.endIndex <= fixture.code.length,
            "Oracle fixture '${fixture.name}' expectation #$idx has invalid span ${exp.startIndex}..${exp.endIndex} (len=${fixture.code.length})",
        )
        assertEquals(
            exp.text,
            fixture.code.substring(exp.startIndex, exp.endIndex),
            "Oracle fixture '${fixture.name}' expectation #$idx text mismatch at ${exp.startIndex}..${exp.endIndex}",
        )

        val overlapping = tokens.filter { it.endIndex > exp.startIndex && it.startIndex < exp.endIndex }
        val dominant = dominantKindForSpan(overlapping, exp.startIndex, exp.endIndex)
        if (dominant != null && dominant in exp.allowedKinds) {
            matchedCount++
            if (!extendsBeyondOracles) {
                rawMatched++
            }
        } else {
            val mismatch = OracleMismatch(
                index = idx,
                expectation = exp,
                actualKind = dominant,
                overlapping = overlapping,
                isOverride = extendsBeyondOracles,
            )
            mismatches += mismatch
            if (extendsBeyondOracles) {
                overrideMismatches += mismatch
            }
            if (exp.note.startsWith("STRICT:")) {
                strictMismatches += mismatch
            }
        }
    }

    assertTrue(
        rawTotal > 0,
        "Oracle fixture '${fixture.name}' (${fixture.language.id}) has zero raw-oracle expectations",
    )
    val overrideShare = overrideTotal.toDouble() / fixture.expectations.size.toDouble()
    assertTrue(
        overrideShare <= fixture.maxOverrideShare,
        "Oracle fixture '${fixture.name}' (${fixture.language.id}) override share " +
            "$overrideTotal/${fixture.expectations.size} (${formatRatio(overrideShare)}) " +
            "exceeds maxOverrideShare (${formatRatio(fixture.maxOverrideShare)})",
    )

    val rawAgreementRatio = rawMatched.toDouble() / rawTotal.toDouble()
    val overallAgreementRatio = matchedCount.toDouble() / fixture.expectations.size.toDouble()
    if (strictMismatches.isNotEmpty() ||
        overrideMismatches.isNotEmpty() ||
        rawAgreementRatio < fixture.minAgreementRatio ||
        overallAgreementRatio < fixture.minAgreementRatio
    ) {
        val report = buildString {
            appendLine(
                "Oracle fixture '${fixture.name}' (${fixture.language.id}) failed: " +
                    "raw=$rawMatched/$rawTotal (${formatRatio(rawAgreementRatio)}), " +
                    "overall=$matchedCount/${fixture.expectations.size} (${formatRatio(overallAgreementRatio)}), " +
                    "required=${formatRatio(fixture.minAgreementRatio)}, " +
                    "strictMismatches=${strictMismatches.size}, " +
                    "overrideMismatches=${overrideMismatches.size}",
            )
            for (m in mismatches) {
                val exp = m.expectation
                val overlapDesc = m.overlapping.joinToString(", ") { tok ->
                    "${tok.kind}@${tok.startIndex}..${tok.endIndex}(${tok.slice(fixture.code).replace("\n", "\\n")})"
                }
                val tags = buildString {
                    if (exp.note.startsWith("STRICT:")) append(" [STRICT]")
                    if (m.isOverride) append(" [OVERRIDE]")
                }
                appendLine(
                    "  #${m.index}$tags ${exp.startIndex}..${exp.endIndex} '${exp.text}': " +
                        "expected=${exp.allowedKinds} (shiki=${exp.shikiKind}, pygments=${exp.pygmentsKind}), " +
                        "actual=${m.actualKind}, tokens=[$overlapDesc]" +
                        if (exp.note.isNotEmpty()) " note='${exp.note}'" else "",
                )
            }
        }
        fail(report)
    }
}

private fun dominantKindForSpan(
    overlapping: List<SyntaxToken>,
    spanStart: Int,
    spanEnd: Int,
): SyntaxTokenKind? {
    if (overlapping.isEmpty()) return null
    val nonWs = overlapping.filter { it.kind != SyntaxTokenKind.Whitespace }
    val candidates = nonWs.ifEmpty { overlapping }
    val coverageByKind = LinkedHashMap<SyntaxTokenKind, Int>()
    for (tok in candidates) {
        val overlap = minOf(tok.endIndex, spanEnd) - maxOf(tok.startIndex, spanStart)
        if (overlap > 0) {
            coverageByKind[tok.kind] = (coverageByKind[tok.kind] ?: 0) + overlap
        }
    }
    return coverageByKind.maxByOrNull { it.value }?.key
}

private fun formatRatio(value: Double): String {
    val pct = (value * 1000.0).toInt() / 10.0
    return "$pct%"
}
