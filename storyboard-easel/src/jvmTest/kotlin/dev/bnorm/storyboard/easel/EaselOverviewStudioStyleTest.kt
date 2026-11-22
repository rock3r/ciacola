package dev.bnorm.storyboard.easel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.LocalOverviewChromeStyle
import dev.bnorm.storyboard.easel.overview.OverviewChromeStyle
import dev.bnorm.storyboard.easel.overview.calculateOverviewStripLayout
import dev.bnorm.storyboard.easel.overview.formatOverviewBuildCount
import dev.bnorm.storyboard.easel.overview.formatOverviewStatus
import dev.bnorm.storyboard.easel.overview.overviewCardCaption
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.overview.stripFirstVisibleIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EaselOverviewStudioStyleTest {

    private fun buildDeck(slideCount: Int, multiBeatEvery: Int = 3, beats: Int = 2): Storyboard =
        Storyboard.build(title = "StudioStyle$slideCount") {
            repeat(slideCount) { s ->
                val frames = if (s % multiBeatEvery == 0) List(beats) { it } else listOf(0)
                scene(frames = frames) {
                    Box(Modifier.fillMaxSize()) { Text("Slide ${s + 1}") }
                }
            }
        }

    @Test
    fun buildCountsAreSpelledOutAndCaptionsUseBadgeAndTitle() {
        assertEquals("1 build", formatOverviewBuildCount(1))
        assertEquals("2 builds", formatOverviewBuildCount(2))
        assertEquals("12 builds", formatOverviewBuildCount(12))
        assertEquals("#03 · Rich text", overviewCardCaption(2, "Rich text"))
        assertEquals("#03", overviewCardCaption(2, "Slide 3"), "Untitled slides show the badge alone")
        assertEquals("Slide 3 of 136 · Build 1 of 2", formatOverviewStatus(2, 136, 0, 2))
        assertEquals("Slide 4 of 136", formatOverviewStatus(3, 136, 0, 1))
    }

    @Test
    fun stripLayoutFillsTheHeightWithoutADeadBandOrBottomClipping() {
        val view = DpSize(1440.dp, 820.dp)
        val layout = calculateOverviewStripLayout(view, IntSize(1920, 1080), maxItemsInColumn = 6)
        assertEquals(3, layout.rowsVisible)
        val used = layout.itemSize.height * layout.rowsVisible + layout.verticalPadding * 2
        assertTrue(used.value <= view.height.value + 0.5f, "Rows must never be clipped at the bottom edge ($used)")
        assertTrue(layout.verticalPadding.value <= 16.5f, "Top band must be a 16dp edge, not a dead band (${layout.verticalPadding})")

        // A short stage slot shrinks the row count instead of clipping the second row.
        val short = calculateOverviewStripLayout(DpSize(1100.dp, 300.dp), IntSize(1920, 1080), maxItemsInColumn = 4)
        assertEquals(2, short.rowsVisible)
        assertTrue(short.itemSize.height * short.rowsVisible + short.verticalPadding * 2 <= 300.5.dp)

        // Single-build decks keep one large, vertically centered row.
        val single = calculateOverviewStripLayout(view, IntSize(1920, 1080), maxItemsInColumn = 1)
        assertEquals(1, single.rowsVisible)
        assertTrue(single.itemSize.width.value <= view.width.value * 0.42f + 0.5f)
    }

    @Test
    fun stripScrollKeepsTheCurrentBuildFullyVisible() {
        assertEquals(0, stripFirstVisibleIndex(currentIndex = 0, rowsVisible = 3))
        assertEquals(0, stripFirstVisibleIndex(currentIndex = 2, rowsVisible = 3))
        assertEquals(2, stripFirstVisibleIndex(currentIndex = 4, rowsVisible = 3))
    }

    @Test
    fun gridCardsUseOneLineCaptionsAndSpelledOutBuildCountsWithHostChrome() = runComposeUiTest {
        val deck = buildDeck(20)
        val style = OverviewChromeStyle.NeutralDark.copy(accent = Color(0xFF00AAFF), fontFamily = FontFamily.SansSerif)
        val titles: (Int) -> String? = { idx -> if (idx == 0) "A deliberately long opening slide title that must ellipsize" else null }

        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            val visibility = rememberOverviewVisibility(initiallyVisible = true)
            CompositionLocalProvider(LocalOverviewChromeStyle provides style) {
                Box(Modifier.size(1_440.dp, 900.dp)) {
                    EaselOverview(animatic = animatic, visibility = visibility, slideTitle = titles) {
                        Easel(animatic)
                    }
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        onNodeWithTag("easel-overview-slide-badge", useUnmergedTree = true).assertTextEquals("Slide 1 of 20 · Build 1 of 2")
        onNodeWithTag("easel-overview-grid-builds-0", useUnmergedTree = true).assertTextEquals("2 builds")
        assertEquals(0, onAllNodesWithText("2b").fetchSemanticsNodes().size, "Build counts must never read '2b'")

        val caption = onNodeWithTag("easel-overview-grid-caption-0", useUnmergedTree = true).getBoundsInRoot()
        val card = onNodeWithTag("easel-overview-grid-card-0", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(caption.right <= card.right, "Caption must ellipsize inside its card")
        assertTrue((caption.bottom - caption.top).value < 24f, "Caption must stay on one line (${caption.bottom - caption.top})")
    }

    @Test
    fun stripModeShowsRowsWithinTheViewportWithoutClipping() = runComposeUiTest {
        val deck = buildDeck(20, multiBeatEvery = 2, beats = 3)
        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            val visibility = rememberOverviewVisibility(initiallyVisible = true)
            Box(Modifier.size(1_440.dp, 860.dp)) {
                EaselOverview(animatic = animatic, visibility = visibility) {
                    Easel(animatic)
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        onNodeWithTag("easel-overview-strip-toggle", useUnmergedTree = true).performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()

        val strip = onNodeWithTag("easel-overview-strip", useUnmergedTree = true).getBoundsInRoot()
        val badge = onNodeWithTag("easel-overview-slide-badge", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(strip.top >= badge.bottom, "Strip must start below the chrome bar")
        // Three builds fit in three exact rows: the strip height is fully used with only edge padding.
        assertTrue((strip.bottom - strip.top).value > 700f, "Strip must take the remaining window height")
    }
}
