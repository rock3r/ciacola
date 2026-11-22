package dev.bnorm.storyboard.text.magic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagicTextDiffTest {

    private fun assertValidPartition(
        from: AnnotatedString,
        to: AnnotatedString,
        by: MorphBy,
        matches: List<TokenMatch>,
    ) {
        val expectedBefore = MagicTextDiff.tokenize(from, by)
        val expectedAfter = MagicTextDiff.tokenize(to, by)

        val actualBefore = matches.mapNotNull { it.before }.sortedBy { it.start }
        val actualAfter = matches.mapNotNull { it.after }.sortedBy { it.start }

        assertEquals(expectedBefore, actualBefore, "Every source token must appear exactly once")
        assertEquals(expectedAfter, actualAfter, "Every target token must appear exactly once")

        for (m in matches) {
            assertTrue(m.before != null || m.after != null, "Match must have at least one side")
            m.before?.let { r ->
                assertTrue(r.start in 0..from.length && r.end in r.start..from.length && r.length > 0)
            }
            m.after?.let { r ->
                assertTrue(r.start in 0..to.length && r.end in r.start..to.length && r.length > 0)
            }
            if (m.before != null && m.after != null) {
                assertEquals(
                    from.text.substring(m.before.start, m.before.end),
                    to.text.substring(m.after.start, m.after.end),
                    "Matched ranges must have equal text",
                )
            }
        }
    }

    @Test
    fun seamFixtureRepeatedTokenWithContext() {
        val before = AnnotatedString("item left item right")
        val after = AnnotatedString("item right item left")

        val plan = MagicTextDiff.plan(before, after, MorphBy.Word)
        assertValidPartition(before, after, MorphBy.Word, plan)

        assertEquals(
            listOf(
                TokenMatch(TextRange(10, 14), TextRange(0, 4), styleChanged = false),
                TokenMatch(TextRange(15, 20), TextRange(5, 10), styleChanged = false),
                TokenMatch(TextRange(0, 4), TextRange(11, 15), styleChanged = false),
                TokenMatch(TextRange(5, 9), TextRange(16, 20), styleChanged = false),
            ),
            plan,
        )
    }

    @Test
    fun seamFixtureCombiningAndZwjEmoji() {
        val before = AnnotatedString("Cafe\u0301 \uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB")
        val after = AnnotatedString("\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB \u2014 Cafe\u0301")

        val plan = MagicTextDiff.plan(before, after, MorphBy.Word)
        assertValidPartition(before, after, MorphBy.Word, plan)

        assertEquals(
            listOf(
                TokenMatch(TextRange(6, 13), TextRange(0, 7), styleChanged = false),
                TokenMatch(TextRange(0, 5), TextRange(10, 15), styleChanged = false),
                TokenMatch(null, TextRange(8, 9), styleChanged = false),
            ),
            plan,
        )
    }

    @Test
    fun seamFixtureMixedDirectionality() {
        val before = AnnotatedString("status שלום 42")
        val after = AnnotatedString("42 — שלום status")

        val plan = MagicTextDiff.plan(before, after, MorphBy.Word)
        assertValidPartition(before, after, MorphBy.Word, plan)

        assertEquals(
            listOf(
                TokenMatch(TextRange(12, 14), TextRange(0, 2), styleChanged = false),
                TokenMatch(TextRange(7, 11), TextRange(5, 9), styleChanged = false),
                TokenMatch(TextRange(0, 6), TextRange(10, 16), styleChanged = false),
                TokenMatch(null, TextRange(3, 4), styleChanged = false),
            ),
            plan,
        )
    }

    @Test
    fun seamFixtureLineMovementAndStyleOnlyChange() {
        val before = AnnotatedString("val answer = 42\nprintln(answer)")
        val after = buildAnnotatedString {
            append("println(")
            withStyle(SpanStyle(color = Color.Magenta, fontWeight = FontWeight.Bold)) {
                append("answer")
            }
            append(")\nval ")
            withStyle(SpanStyle(color = Color.Cyan)) {
                append("answer")
            }
            append(" = 42")
        }

        val plan = MagicTextDiff.plan(before, after, MorphBy.Word)
        assertValidPartition(before, after, MorphBy.Word, plan)

        val matchedAnswers = plan.filter { m ->
            m.before != null && m.after != null &&
                before.text.substring(m.before.start, m.before.end) == "answer"
        }
        assertEquals(2, matchedAnswers.size)
        // First "answer" in target is inside println(...) -> came from line 2 [24, 30) in before.
        assertEquals(TextRange(24, 30), matchedAnswers[0].before)
        assertTrue(matchedAnswers[0].styleChanged)
        // Second "answer" in target is inside val answer = 42 -> came from line 1 [4, 10) in before.
        assertEquals(TextRange(4, 10), matchedAnswers[1].before)
        assertTrue(matchedAnswers[1].styleChanged)

        // Unchanged tokens have styleChanged = false.
        val valMatch = plan.first { m ->
            m.before != null && before.text.substring(m.before.start, m.before.end) == "val"
        }
        assertFalse(valMatch.styleChanged)
    }

    @Test
    fun switchableStopwordPruning() {
        val before = AnnotatedString("Measuring baseline latency in the rendering pipeline every single frame")
        val after = AnnotatedString("Inspecting shader uniforms in the graphics debugger across complex scenes")

        val pruned = MagicTextDiff.plan(before, after, MorphBy.Word, pruneStopwords = true)
        assertValidPartition(before, after, MorphBy.Word, pruned)
        val matchedPruned = pruned.filter { it.before != null && it.after != null }
        assertTrue(matchedPruned.isEmpty(), "Expected low-signal 'in the' to be pruned, got $matchedPruned")

        val unpruned = MagicTextDiff.plan(before, after, MorphBy.Word, pruneStopwords = false)
        assertValidPartition(before, after, MorphBy.Word, unpruned)
        val matchedUnpruned = unpruned.filter { it.before != null && it.after != null }
            .map { before.text.substring(it.before!!.start, it.before!!.end) }
        assertEquals(listOf("in", "the"), matchedUnpruned)
    }

    @Test
    fun characterModeUsesGraphemeClustersAndHandlesHighMultiplicity() {
        val before = AnnotatedString("Cafe\u0301 \uD83D\uDC68\u200D\uD83C\uDF73 \uD83C\uDDEE\uD83C\uDDF9 eeeeeeeeee")
        val after = AnnotatedString("\uD83C\uDDEE\uD83C\uDDF9 \uD83D\uDC68\u200D\uD83C\uDF73 Cafe\u0301 eeeeeeeeee")

        val clusters = MagicTextDiff.splitGraphemeClusters(before.text)
        assertTrue(clusters.any { before.text.substring(it.start, it.end) == "e\u0301" })
        assertTrue(clusters.any { before.text.substring(it.start, it.end) == "\uD83D\uDC68\u200D\uD83C\uDF73" })
        assertTrue(clusters.any { before.text.substring(it.start, it.end) == "\uD83C\uDDEE\uD83C\uDDF9" })

        val plan = MagicTextDiff.plan(before, after, MorphBy.Character, pruneStopwords = true)
        assertValidPartition(before, after, MorphBy.Character, plan)
        assertTrue(plan.all { it.before != null && it.after != null }, "All clusters should match cleanly")
        val matchedE = plan.count { after.text.substring(it.after!!.start, it.after!!.end) == "e" }
        assertEquals(10, matchedE)
    }

    @Test
    fun lineModeSplitsOnNewlinesAndPreservesStyleChanges() {
        val before = AnnotatedString("alpha\nbeta\r\n\ngamma")
        val after = buildAnnotatedString {
            append("gamma\n")
            withStyle(SpanStyle(color = Color.Yellow)) { append("alpha") }
            append("\ndelta")
        }

        val plan = MagicTextDiff.plan(before, after, MorphBy.Line)
        assertValidPartition(before, after, MorphBy.Line, plan)

        val betaExit = plan.firstOrNull { it.after == null }
        assertNotNull(betaExit)
        assertEquals("beta", before.text.substring(betaExit.before!!.start, betaExit.before!!.end))

        val alphaMatch = plan.first {
            it.before != null && it.after != null && before.text.substring(it.before.start, it.before.end) == "alpha"
        }
        assertTrue(alphaMatch.styleChanged)

        val gammaMatch = plan.first {
            it.before != null && it.after != null && before.text.substring(it.before.start, it.before.end) == "gamma"
        }
        assertFalse(gammaMatch.styleChanged)

        val deltaEnter = plan.firstOrNull { it.before == null }
        assertNotNull(deltaEnter)
        assertEquals("delta", after.text.substring(deltaEnter.after!!.start, deltaEnter.after!!.end))
    }

    @Test
    fun emptyAndWhitespaceEdgesAndFragmentAddresses() {
        assertEquals(emptyList(), MagicTextDiff.plan(AnnotatedString(""), AnnotatedString(""), MorphBy.Word))
        assertEquals(emptyList(), MagicTextDiff.plan(AnnotatedString("   \n\t "), AnnotatedString("  "), MorphBy.Character))

        val enterOnly = MagicTextDiff.plan(AnnotatedString(""), AnnotatedString("hi hi"), MorphBy.Word)
        assertEquals(2, enterOnly.size)
        assertTrue(enterOnly.all { it.before == null && it.after != null })
        assertEquals(listOf("hi#0", "hi#1"), MagicTextDiff.fragmentAddresses(AnnotatedString(""), AnnotatedString("hi hi"), enterOnly))

        val exitOnly = MagicTextDiff.plan(AnnotatedString("bye bye"), AnnotatedString(""), MorphBy.Word)
        assertEquals(2, exitOnly.size)
        assertTrue(exitOnly.all { it.before != null && it.after == null })
        assertEquals(listOf("-bye#0", "-bye#1"), MagicTextDiff.fragmentAddresses(AnnotatedString("bye bye"), AnnotatedString(""), exitOnly))

        val identicalStyleSplit = buildAnnotatedString {
            withStyle(SpanStyle(color = Color.Red)) { append("he") }
            withStyle(SpanStyle(color = Color.Red)) { append("llo") }
        }
        val singleStyle = buildAnnotatedString {
            withStyle(SpanStyle(color = Color.Red)) { append("hello") }
        }
        val sameStylePlan = MagicTextDiff.plan(identicalStyleSplit, singleStyle, MorphBy.Word)
        assertEquals(1, sameStylePlan.size)
        assertNull(sameStylePlan.firstOrNull { it.styleChanged })
    }

    @Test
    fun exitFragmentAddressesUseSourceOccurrenceIndexNotExitingOnlyCounter() {
        // Second "shader" exits while first "shader" stays -> exiting fragment MUST be "-shader#1", NOT "-shader#0".
        val from = AnnotatedString("a shader is a shader")
        val to = AnnotatedString("a shader is great")
        val plan = MagicTextDiff.plan(from, to, MorphBy.Word, pruneStopwords = false)
        assertValidPartition(from, to, MorphBy.Word, plan)
        val addresses = MagicTextDiff.fragmentAddresses(from, to, plan)

        val exitingIndices = plan.indices.filter { plan[it].before != null && plan[it].after == null }
        val exitingAddresses = exitingIndices.map { addresses[it] }.toSet()
        assertTrue("-shader#1" in exitingAddresses, "Second 'shader' exiting must be '-shader#1', got $exitingAddresses")
        assertFalse("-shader#0" in exitingAddresses, "Kept first 'shader' must not shift exit address to '-shader#0'")
        assertTrue("-a#1" in exitingAddresses, "Second 'a' exiting must be '-a#1', got $exitingAddresses")

        // Reverse: first "shader" exits while second "shader" stays -> exiting fragment MUST be "-shader#0".
        val fromRev = AnnotatedString("old shader vs cool shader end")
        val toRev = AnnotatedString("new thing vs cool shader end")
        val planRev = MagicTextDiff.plan(fromRev, toRev, MorphBy.Word, pruneStopwords = false)
        assertValidPartition(fromRev, toRev, MorphBy.Word, planRev)
        val addressesRev = MagicTextDiff.fragmentAddresses(fromRev, toRev, planRev)
        val exitingRev = planRev.indices
            .filter { planRev[it].before != null && planRev[it].after == null }
            .map { addressesRev[it] }
            .toSet()
        assertTrue("-shader#0" in exitingRev, "First 'shader' exiting must be '-shader#0', got $exitingRev")
        assertFalse("-shader#1" in exitingRev, "Second 'shader' stayed so '-shader#1' must not exist")
    }
}
