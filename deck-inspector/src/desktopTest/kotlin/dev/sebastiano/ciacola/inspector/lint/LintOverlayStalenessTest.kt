package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class LintOverlayStalenessTest {

    private val stageFormat = SceneFormat(IntSize(1920, 1080), Density(2f))

    @Test
    fun scrubbingFromSlide2ToSlide6NeverLeavesStaleSlide2OverlayBoxesOnSlide6() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        val controller = DeckLintController()
        lateinit var animatic: Animatic
        lateinit var uiScope: CoroutineScope

        val storyboard = Storyboard.build(title = "Staleness Probe", format = stageFormat) {
            repeat(6) { idx ->
                scene(frames = listOf(0)) {
                    Box(Modifier.fillMaxSize()) {
                        Text("Slide ${idx + 1}", style = TextStyle(fontSize = 32.sp, color = Color.White))
                    }
                }
            }
        }

        fun seedSlide2Issue() {
            registry.clear()
            registry.record(
                StageNodeSnapshot(
                    elementId = "s2-tiny-caption",
                    kind = "Text",
                    label = "Slide 2 Tiny Caption",
                    sceneIndex = 1,
                    beatIndex = 0,
                    stageBounds = Rect(120f, 180f, 520f, 220f),
                    resolvedText = "Slide 2 Tiny Caption",
                    effectiveForeground = Color.White,
                    effectiveBackground = Color.Black,
                    effectiveFontSizeSp = 10f,
                ),
            )
        }

        setContent {
            CiacolaJewelTheme(isDark = true) {
                animatic = rememberAnimatic(storyboard = { storyboard })
                uiScope = rememberCoroutineScope()
                CompositionLocalProvider(
                    LocalPresentingFullscreen provides false,
                    LocalSceneMode provides SceneMode.Story,
                ) {
                    Box(Modifier.size(640.dp, 360.dp)) {
                        Surface(Modifier.fillMaxSize(), color = Color.Black) {}
                        StageLintHighlightOverlay(
                            controller = controller,
                            isInspectionVisible = true,
                        )
                        DeckLintPanel(
                            controller = controller,
                            animatic = animatic,
                            stageInspectionRegistry = registry,
                            isActive = false,
                        )
                    }
                }
            }
        }
        waitForIdle()

        // 1. Move to Slide 2 (sceneIndex = 1) and evaluate its issue -> overlay renders S2 box.
        runOnIdle {
            uiScope.launch { animatic.jumpTo(Storyboard.Index(sceneIndex = 1, frameIndex = 0)) }
        }
        waitForIdle()
        runOnIdle {
            seedSlide2Issue()
            controller.evaluateActiveBeatInternal(
                registry = registry,
                sceneIndex = 1,
                beatIndex = 0,
                semanticsTextNodes = emptyList(),
            )
        }
        waitForIdle()
        assertEquals(1, controller.currentSceneIndex)
        assertEquals(1, controller.activeBeatOverlayIssues.size)
        assertEquals(1, controller.activeBeatOverlayIssues.single().sceneIndex)
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(1)
        val slide2StateIndex = animatic.activeFromStateIndex

        // 2. Mid-transition scrub leaving Slide 2 (currentIndex != targetIndex) -> overlay hides immediately!
        runOnIdle {
            uiScope.launch { animatic.seekToSegment(fromStateIndex = slide2StateIndex, fraction = 0.5f) }
        }
        waitForIdle()
        assertFalse(controller.isStageSettled, "Expected controller.isStageSettled = false mid-scrub")
        assertTrue(controller.activeBeatOverlayIssues.isEmpty(), "Overlay issues must hide while unsettled")
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(0)

        // 3. Scrub across intermediate segment towards Slide 6 (sceneIndex = 4 -> 5, 3 states per single-frame scene):
        //    updateActiveLocation must run BEFORE the settled guard and drop S2's stale beat issues!
        val slide5StateIndex = slide2StateIndex + 9
        runOnIdle {
            registry.clear()
            uiScope.launch { animatic.seekToSegment(fromStateIndex = slide5StateIndex, fraction = 0.5f) }
        }
        waitForIdle()
        assertFalse(controller.isStageSettled)
        assertTrue(
            controller.currentSceneIndex >= 4,
            "currentSceneIndex must update even while unsettled (got ${controller.currentSceneIndex})",
        )
        assertTrue(controller.activeBeatOverlayIssues.isEmpty())
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(0)

        // 4. Settle on Slide 6 (sceneIndex = 5, with no S6 issues) -> ZERO S2 boxes may reappear!
        runOnIdle {
            uiScope.launch { animatic.jumpTo(Storyboard.Index(sceneIndex = 5, frameIndex = 0)) }
        }
        waitForIdle()
        assertEquals(5, controller.currentSceneIndex)
        assertTrue(
            controller.activeBeatOverlayIssues.none { it.sceneIndex == 1 },
            "No Slide 2 issues may survive onto Slide 6, found: ${controller.activeBeatOverlayIssues}",
        )
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(0)
    }
}
