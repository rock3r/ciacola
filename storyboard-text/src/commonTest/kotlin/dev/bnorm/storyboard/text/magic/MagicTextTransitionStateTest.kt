package dev.bnorm.storyboard.text.magic

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MagicTextTransitionStateTest {
    @Test
    fun planIsAPureFunctionOfTheFromToPairRegardlessOfHistory() {
        val a = tokens("A", " ", "scene", " ", "begins")
        val b = tokens("A", " ", "scene", " ", "advances")
        val c = tokens("The", " ", "same", " ", "path")

        val first = MagicTextPlanner.plan(a, b)
        // Plan unrelated pairs in between (forward, backward, and repeated targets).
        MagicTextPlanner.plan(b, c)
        MagicTextPlanner.plan(c, b)
        MagicTextPlanner.plan(b, a)
        val again = MagicTextPlanner.plan(a, b)

        assertEquals(first.source.map { it.key to it.crossFade }, again.source.map { it.key to it.crossFade })
        assertEquals(first.target.map { it.key to it.crossFade }, again.target.map { it.key to it.crossFade })
    }

    @Test
    fun matchedTokensShareKeysAcrossSourceAndTargetAndUnmatchedTokensDoNot() {
        val plan = MagicTextPlanner.plan(tokens("alpha", " ", "beta"), tokens("beta", " ", "alpha", " ", "new"))
        val sourceKeys = plan.source.associate { it.value.text to it.key }
        val targetByText = plan.target.groupBy { it.value.text }

        assertEquals(sourceKeys["alpha"], targetByText.getValue("alpha").single().key)
        assertEquals(sourceKeys["beta"], targetByText.getValue("beta").single().key)
        assertTrue(targetByText.getValue("new").single().key !in sourceKeys.values)
    }

    @Test
    fun settledStateKeepsTheSameKeysItStartsItsNextTransitionWith() {
        val settled = tokens("origin", " ", "text")
        val settledPlan = MagicTextPlanner.plan(settled, settled)
        val nextPlan = MagicTextPlanner.plan(settled, tokens("origin", " ", "changed"))

        assertEquals(settledPlan.source.map { it.key }, nextPlan.source.map { it.key })
        assertEquals(settledPlan.source, settledPlan.target)
    }

    @Test
    fun planningOnePairNeverMutatesAnotherPairsSourceFlags() {
        val from = tokens("keep", " ", "styled")
        val styledTarget = listOf(AnnotatedString("keep"), AnnotatedString(" "), AnnotatedString.Builder().apply {
            append("styled")
            addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), 0, 6)
        }.toAnnotatedString())

        val plain = MagicTextPlanner.plan(from, tokens("keep", " ", "plain"))
        val flagsBefore = plain.source.map { it.crossFade }
        MagicTextPlanner.plan(from, styledTarget)
        assertEquals(flagsBefore, plain.source.map { it.crossFade })
    }

    private fun tokens(vararg values: String): List<AnnotatedString> = values.map(::AnnotatedString)
}
