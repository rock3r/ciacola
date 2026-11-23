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
)

private data class OracleMismatch(
    val index: Int,
    val expectation: OracleTokenExpectation,
    val actualKind: SyntaxTokenKind?,
    val overlapping: List<SyntaxToken>,
)

/**
 * Verifies [fixture] against [SyntaxHighlighter.lex]:
 * 1. Confirms lossless invariants on [OracleFixture.code].
 * 2. Matches each [OracleTokenExpectation] span against the dominant overlapping non-whitespace
 *    emitted token kind.
 * 3. Fails with a detailed diff if any expectation with `note.startsWith("STRICT:")` mismatches or
 *    if overall agreement falls below [OracleFixture.minAgreementRatio].
 */
fun assertMatchesOracleFixture(fixture: OracleFixture) {
    val lexer = SyntaxHighlighter.lexer(fixture.language)
    val tokens = assertLosslessInvariants(lexer, fixture.code, label = "oracle:${fixture.name}")
    if (fixture.expectations.isEmpty()) return

    val mismatches = mutableListOf<OracleMismatch>()
    val strictMismatches = mutableListOf<OracleMismatch>()
    var matchedCount = 0

    for ((idx, exp) in fixture.expectations.withIndex()) {
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
        } else {
            val mismatch = OracleMismatch(
                index = idx,
                expectation = exp,
                actualKind = dominant,
                overlapping = overlapping,
            )
            mismatches += mismatch
            if (exp.note.startsWith("STRICT:")) {
                strictMismatches += mismatch
            }
        }
    }

    val agreementRatio = matchedCount.toDouble() / fixture.expectations.size.toDouble()
    if (strictMismatches.isNotEmpty() || agreementRatio < fixture.minAgreementRatio) {
        val report = buildString {
            appendLine(
                "Oracle fixture '${fixture.name}' (${fixture.language.id}) failed: " +
                    "agreement $matchedCount/${fixture.expectations.size} (${formatRatio(agreementRatio)}) " +
                    "< required ${formatRatio(fixture.minAgreementRatio)}, " +
                    "strictMismatches=${strictMismatches.size}",
            )
            for (m in mismatches) {
                val exp = m.expectation
                val overlapDesc = m.overlapping.joinToString(", ") { tok ->
                    "${tok.kind}@${tok.startIndex}..${tok.endIndex}(${tok.slice(fixture.code).replace("\n", "\\n")})"
                }
                val strictTag = if (exp.note.startsWith("STRICT:")) " [STRICT]" else ""
                appendLine(
                    "  #${m.index}$strictTag ${exp.startIndex}..${exp.endIndex} '${exp.text}': " +
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
