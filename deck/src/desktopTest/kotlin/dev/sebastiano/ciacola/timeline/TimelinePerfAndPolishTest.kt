package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.effects.DottedLeaderRow
import dev.sebastiano.ciacola.indexFoundationTimelineSnapshot
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class TimelinePerfAndPolishTest {

    @Test
    fun seekingSettledOrSameSegmentWithScrubStepPolicyNeverBumpsScrubEpoch() = runComposeUiTest {
        val deck = buildFoundationTestStoryboard()
        val snapshot = indexFoundationTimelineSnapshot()
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope

        setContent {
            scope = rememberCoroutineScope()
            animatic = rememberAnimatic(storyboard = { deck })
            Box(Modifier.size(480.dp, 270.dp)) {
                Easel(animatic)
            }
        }
        waitForIdle()

        val initialEpoch = animatic.scrubEpoch
        val firstAnimated = snapshot.stateSpans.first { !it.isHold && it.durationMs > 100 }

        // Seeking to already-settled state 0 at fraction 0 with inPlace policy must not bump scrubEpoch.
        runOnIdle {
            val (s, f) = snapshot.stateFractionForTimeMs(100)
            val inPlace = scrubStepIsInPlace(animatic.activeFromStateIndex, animatic.activeToStateIndex, s, f)
            scope.launch { animatic.seekToSegment(s, f, rebuildScene = !inPlace) }
        }
        waitForIdle()
        assertEquals(initialEpoch, animatic.scrubEpoch, "Settled in-place seek must not bump scrubEpoch")

        // Dragging inside the same active (from, to) segment must stay at the same scrubEpoch!
        val (firstS, firstF) = snapshot.stateFractionForTimeMs(firstAnimated.startMs + (firstAnimated.durationMs * 0.15f).toInt())
        runOnIdle {
            val inPlace = scrubStepIsInPlace(animatic.activeFromStateIndex, animatic.activeToStateIndex, firstS, firstF)
            scope.launch { animatic.seekToSegment(firstS, firstF, rebuildScene = !inPlace) }
        }
        waitForIdle()
        val epochAtSegmentStart = animatic.scrubEpoch

        for (frac in listOf(0.35f, 0.55f, 0.72f)) {
            val t = firstAnimated.startMs + (firstAnimated.durationMs * frac).toInt()
            val (s, f) = snapshot.stateFractionForTimeMs(t)
            runOnIdle {
                val inPlace = scrubStepIsInPlace(animatic.activeFromStateIndex, animatic.activeToStateIndex, s, f)
                assertTrue(inPlace, "Intra-segment scrub step at frac=$frac must be in-place")
                scope.launch { animatic.seekToSegment(s, f, rebuildScene = !inPlace) }
            }
            waitForIdle()
        }
        assertEquals(epochAtSegmentStart, animatic.scrubEpoch, "In-segment scrub steps must not bump scrubEpoch")
    }

    @Test
    fun formatTimelineLocationBadgeReflectsRestingMidBeatAndCrossSlideTransitions() {
        val snapshot = indexFoundationTimelineSnapshot()
        val firstHold = snapshot.stateSpans.first { it.isHold }
        val firstAnim = snapshot.stateSpans.first { !it.isHold && !it.isBoundary && it.durationMs > 0 }
        val boundaryAnim = snapshot.stateSpans.first { it.isBoundary && it.durationMs > 0 }

        val restBadge = formatTimelineLocationBadge(
            snapshot = snapshot,
            timeMs = firstHold.startMs + 50,
            activeSubLoopId = null,
            fallbackIndex = Storyboard.Index(0, 0),
            sceneBeatsCountProvider = { s -> snapshot.slides[s].beatCount },
        )
        assertTrue(restBadge.endsWith("S1:B1/${snapshot.slides[0].beatCount}"), "Got $restBadge")

        val midBadge = formatTimelineLocationBadge(
            snapshot = snapshot,
            timeMs = firstAnim.startMs + firstAnim.durationMs / 2,
            activeSubLoopId = null,
            fallbackIndex = Storyboard.Index(0, 0),
            sceneBeatsCountProvider = { s -> snapshot.slides[s].beatCount },
        )
        assertTrue(midBadge.endsWith("S1:B1 → B2"), "Got $midBadge")

        val crossBadge = formatTimelineLocationBadge(
            snapshot = snapshot,
            timeMs = boundaryAnim.startMs + boundaryAnim.durationMs / 2,
            activeSubLoopId = null,
            fallbackIndex = Storyboard.Index(0, 0),
            sceneBeatsCountProvider = { s -> snapshot.slides[s].beatCount },
        )
        assertTrue(crossBadge.contains("→ S2:B1"), "Expected cross-slide arrow badge, got $crossBadge")
    }

    @Test
    fun dottedLeaderRowKeepsMinimumDotsWidthInNarrowContainer() = runComposeUiTest {
        var densityVal = 1f
        setContent {
            densityVal = LocalDensity.current.density
            val theme = DeckThemes.forPreset(DeckThemePreset.StageDark)
            CompositionLocalProvider(LocalDeckTheme provides theme) {
                Box(Modifier.size(480.dp, 120.dp)) {
                    DottedLeaderRow(
                        leading = "Compose Declarative UI",
                        trailing = "BasicText(AnnotatedString)",
                        trailingStyle = theme.typography.code.copy(fontSize = 14.sp),
                    )
                }
            }
        }
        waitForIdle()
        val trailingBounds = onNodeWithText("BasicText(AnnotatedString)").fetchSemanticsNode().boundsInRoot
        val leadingBounds = onNodeWithText("Compose Declarative UI").fetchSemanticsNode().boundsInRoot
        val gapBetweenTextsDp = (trailingBounds.left - leadingBounds.right) / densityVal
        assertTrue(
            gapBetweenTextsDp >= 72f,
            "DottedLeaderRow must preserve >=36dp dots gap (got ${gapBetweenTextsDp}dp)",
        )
    }

    @Test
    fun incrementalPlannerCacheRePlansOnlyEditedSlide() {
        val store = createLoadedTestDeckStore()
        val controller = rememberlessTimelineController(store)
        assertEquals(5, controller.snapshot.diagnostics.replannedSlideIds.size)

        // Mutating Slide 1 ("beats") must re-plan ONLY "beats" while keeping slides 2..5 cached!
        store.edit("Edit Slide 1 Title") { doc ->
            doc.copy(
                slides = doc.slides.mapIndexed { idx, s ->
                    if (idx == 0) s.copy(title = "Beats Updated") else s
                },
            )
        }
        val updated = controller.refreshSnapshot()
        assertEquals(setOf("beats"), updated.diagnostics.replannedSlideIds)
        assertFalse("keynote-effects-lab" in updated.diagnostics.replannedSlideIds)
    }

    private fun rememberlessTimelineController(store: dev.sebastiano.ciacola.document.DeckDocumentStore) =
        DocumentTimelineController(
            store = store,
            specimens = AllDocumentDecks.foundation.specimens,
        )
}
