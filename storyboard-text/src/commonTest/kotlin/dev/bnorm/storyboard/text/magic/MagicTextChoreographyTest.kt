package dev.bnorm.storyboard.text.magic

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MagicTextChoreographyTest {
    private fun maxOverlapAlpha(c: MagicTextChoreography): Pair<Float, Int> {
        var maxOverlap = 0f
        var at = 0
        for (t in 0..c.totalDurationMillis) {
            val overlap = minOf(c.exitAlpha(t), c.enterAlpha(t))
            if (overlap > maxOverlap) {
                maxOverlap = overlap
                at = t
            }
        }
        return maxOverlap to at
    }

    @Test
    fun zeroMovementTransitionHandsOffImmediatelyWithoutGhostOverlapOrDeadGap() {
        val c = MagicTextChoreography.of(
            fadeDurationMillis = 300,
            moveDurationMillis = 300,
            delayDurationMillis = 0,
            hasMovement = false,
        )
        val (overlap, at) = maxOverlapAlpha(c)
        assertTrue(overlap <= 1e-4f, "Outgoing and incoming text must not superimpose, got overlap=$overlap at +${at}ms")
        assertEquals(
            c.exitDelayMillis + c.exitDurationMillis,
            c.enterDelayMillis,
            "No dead wait between fade-out completion and fade-in start when nothing moves",
        )
        assertEquals(900, c.totalDurationMillis, "The timeline segment length must not change")
    }

    @Test
    fun zeroMoveDurationHandsOffImmediatelyWithoutGhostOverlap() {
        val c = MagicTextChoreography.of(fadeDurationMillis = 300, moveDurationMillis = 0, delayDurationMillis = 0)
        val (overlap, at) = maxOverlapAlpha(c)
        assertTrue(overlap <= 1e-4f, "Expected no double-exposed overlap when moveDuration == 0, got $overlap at +${at}ms")
        assertEquals(c.exitDelayMillis + c.exitDurationMillis, c.enterDelayMillis)
    }

    @Test
    fun movingTransitionCompletesExitBeforeMoveAndMoveBeforeEnter() {
        val c = MagicTextChoreography.of(fadeDurationMillis = 300, moveDurationMillis = 300, delayDurationMillis = 0)
        assertTrue(
            c.exitDelayMillis + c.exitDurationMillis <= c.moveDelayMillis,
            "Outgoing words must finish fading out (${c.exitDelayMillis + c.exitDurationMillis}ms) before shared words move (${c.moveDelayMillis}ms)",
        )
        assertTrue(
            c.moveDelayMillis + c.moveDurationMillis <= c.enterDelayMillis,
            "Shared words must finish moving (${c.moveDelayMillis + c.moveDurationMillis}ms) before incoming words fade in (${c.enterDelayMillis}ms)",
        )
        val (overlap, at) = maxOverlapAlpha(c)
        assertTrue(overlap <= 1e-4f, "Outgoing and incoming words must never overlap, got $overlap at +${at}ms")
        assertEquals(900, c.totalDurationMillis)
    }

    @Test
    fun exitAcceleratesAwayAndEntranceDeceleratesIn() {
        val c = MagicTextChoreography.of(fadeDurationMillis = 300, moveDurationMillis = 300, delayDurationMillis = 0)
        val exitQuarter = c.exitDelayMillis + c.exitDurationMillis / 4
        val enterQuarter = c.enterDelayMillis + c.enterDurationMillis / 4
        assertTrue(c.exitAlpha(exitQuarter) > 0.85f, "Outgoing words should linger early in the exit, got ${c.exitAlpha(exitQuarter)}")
        assertTrue(c.enterAlpha(enterQuarter) > 0.5f, "Incoming words should be mostly visible a quarter in, got ${c.enterAlpha(enterQuarter)}")
        // Decelerating into full opacity: the final 10% of the entrance adds very little alpha (no terminal slam).
        val end = c.enterDelayMillis + c.enterDurationMillis
        val tail = c.enterAlpha(end) - c.enterAlpha(end - c.enterDurationMillis / 10)
        assertTrue(tail < 0.05f, "Expected a soft landing into full opacity, final 10% added $tail")
    }

    @Test
    fun startDelayShiftsEveryPhaseAndCustomEasingDrivesTheEntrance() {
        val base = MagicTextChoreography.of(300, 300, 0)
        val shifted = MagicTextChoreography.of(300, 300, 0, startDelayMillis = 120, easing = LinearEasing)
        assertEquals(base.exitDelayMillis + 120, shifted.exitDelayMillis)
        assertEquals(base.moveDelayMillis + 120, shifted.moveDelayMillis)
        assertEquals(base.enterDelayMillis + 120, shifted.enterDelayMillis)
        assertEquals(base.totalDurationMillis + 120, shifted.totalDurationMillis)
        assertEquals(1f, shifted.exitAlpha(119))
        assertEquals(LinearEasing, shifted.moveEasing)
        assertEquals(LinearEasing, shifted.enterEasing)
    }

    @Test
    fun planReportsMovementOnlyWhenAVisibleSharedWordCanShift() {
        fun words(s: String) = AnnotatedString(s).toWords()
        val codeB1 = words("fun provisional() = Unit")
        val codeB2 = words("fun provisional() {\n    println(\"foundation\")\n}")
        assertFalse(MagicTextPlanner.plan(codeB1, codeB2).hasMovement, "'fun provisional()' stays put on S4:B1 -> B2")
        assertFalse(MagicTextPlanner.plan(codeB2, codeB1).hasMovement)

        val proseB1 = words("A scene begins with one deterministic state.")
        val proseB2 = words("A scene advances to a deterministic next state.")
        assertTrue(MagicTextPlanner.plan(proseB1, proseB2).hasMovement, "'state.' shifts on S1:B1 -> B2")
    }
}
