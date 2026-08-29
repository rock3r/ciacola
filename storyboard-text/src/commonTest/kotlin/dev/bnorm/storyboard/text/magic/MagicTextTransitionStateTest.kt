package dev.bnorm.storyboard.text.magic

import androidx.compose.ui.text.AnnotatedString
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

@OptIn(ExperimentalAtomicApi::class)
class MagicTextTransitionStateTest {
    @Test
    fun forwardAndBackwardTransitionsReuseSettledKeys() {
        val initial = keyed("alpha", "beta")
        val state = MagicTextTransitionState(AtomicLong(0), initial)

        val forward = state.plan(tokens("beta", "alpha", "new"))
        state.settle(forward)
        val backward = state.plan(tokens("alpha", "beta"))

        assertEquals(listOf("initial:0", "initial:1"), backward.map { it.key })
    }

    @Test
    fun interruptedTargetReusesTheExactPreviouslyPlannedState() {
        val state = MagicTextTransitionState(AtomicLong(0), keyed("stable"))

        val firstAttempt = state.plan(tokens("stable", "inserted"))
        state.plan(tokens("stable", "other"))
        val resumedAttempt = state.plan(tokens("stable", "inserted"))

        assertSame(firstAttempt, resumedAttempt)
        assertEquals(firstAttempt.map { it.key }, resumedAttempt.map { it.key })
    }

    @Test
    fun onlyACompletedTransitionBecomesTheNextCorrespondenceBaseline() {
        val state = MagicTextTransitionState(AtomicLong(0), keyed("origin"))
        val interrupted = state.plan(tokens("origin", "temporary"))

        val withoutSettlement = state.plan(tokens("temporary"))
        assertNotEquals(interrupted.last().key, withoutSettlement.single().key)

        state.settle(interrupted)
        val afterSettlement = state.plan(tokens("temporary"))
        assertEquals(interrupted.last().key, afterSettlement.single().key)
    }

    private fun keyed(vararg values: String): List<SharedText> =
        values.mapIndexed { index, value -> SharedText(AnnotatedString(value), "initial:$index") }

    private fun tokens(vararg values: String): List<AnnotatedString> = values.map(::AnnotatedString)
}
