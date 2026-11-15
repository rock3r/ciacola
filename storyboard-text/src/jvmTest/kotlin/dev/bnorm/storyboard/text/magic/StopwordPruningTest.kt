package dev.bnorm.storyboard.text.magic

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopwordPruningTest {

    private fun matchedVisibleTokens(before: String, after: String): List<String> {
        val plan = MagicTextPlanner.plan(AnnotatedString(before).toWords(), AnnotatedString(after).toWords())
        val sourceKeys = plan.source.mapNotNullTo(HashSet()) { it.key }
        return plan.target
            .filter { it.value.text.isNotBlank() && it.key in sourceKeys }
            .map { it.value.text }
    }

    @Test
    fun njptSlide9NoteDropsOrphanWithAndPeriod() {
        val before = "SpanStyle(background) clips tightly with zero padding and razor-sharp 90° corners."
        val after = "Custom drawBehind with TextLayoutResult paints rounded pills + breathing room."

        val plan = MagicTextPlanner.plan(AnnotatedString(before).toWords(), AnnotatedString(after).toWords())
        val matched = matchedVisibleTokens(before, after)

        assertTrue(
            matched.isEmpty(),
            "Expected orphan 'with' and '.' to be pruned across unrelated captions, got $matched",
        )
        assertFalse(plan.hasMovement)
    }

    @Test
    fun njptSlide9RawLineDropsStrayBackticks() {
        val before = "This PR adds animations to the `Onboarding` flow."
        val after = "I reckon you could improve it by using `animateFloatAsState(target, tween(1000, 1500))`"

        val plan = MagicTextPlanner.plan(AnnotatedString(before).toWords(), AnnotatedString(after).toWords())
        val matched = matchedVisibleTokens(before, after)

        assertTrue(
            matched.isEmpty(),
            "Expected stray backtick punctuation below 20% coverage to be pruned, got $matched",
        )
        assertFalse(plan.hasMovement)
    }

    @Test
    fun shadersSlide2KeepsMeaningfulContentWordShadersMatched() {
        val before = "There are many kinds of shaders"
        val after = "Today we focus on fragment shaders"

        val matched = matchedVisibleTokens(before, after)

        assertTrue(
            "shaders" in matched,
            "Expected content word 'shaders' to remain matched, got $matched",
        )
    }

    @Test
    fun unchangedLeadingArticlesInCommonPrefixStayMatched() {
        val aMatched = matchedVisibleTokens(
            "A crimson rectangle renders shadows smoothly",
            "A cobalt polygon draws outlines sharply",
        )
        assertEquals(
            listOf("A"),
            aMatched,
            "Unchanged leading 'A' in common prefix must stay matched so it never blinks",
        )

        val theMatched = matchedVisibleTokens(
            "The quick brown fox leaps cleanly",
            "The calm silver owl glides quietly",
        )
        assertEquals(
            listOf("The"),
            theMatched,
            "Unchanged leading 'The' in common prefix must stay matched so it never blinks",
        )
    }

    @Test
    fun unchangedTrailingStopwordInCommonSuffixStaysMatched() {
        val matched = matchedVisibleTokens(
            "First caption completely replaced on",
            "Second headline written freshly on",
        )
        assertEquals(
            listOf("on"),
            matched,
            "Unchanged trailing token in common suffix must stay matched so it never blinks",
        )
    }

    @Test
    fun adjacentLowSignalStopwordsBelowTwentyPercentCoverageOutsideEdgesArePruned() {
        val before = "Measuring baseline latency in the rendering pipeline every single frame"
        val after = "Inspecting shader uniforms in the graphics debugger across complex scenes"

        val matched = matchedVisibleTokens(before, after)
        assertTrue(
            matched.isEmpty(),
            "Expected adjacent low-signal pair 'in the' (<20% non-ws chars) outside prefix/suffix to be pruned, got $matched",
        )
    }
}
