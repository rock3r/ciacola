package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeckLintUiTest {

    private val stageFormat = SceneFormat(IntSize(1920, 1080), Density(2f))

    @Test
    fun lintPanelFiltersIssuesJumpsToBeatAndDispatchesQuickFix() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        val controller = DeckLintController()
        val appliedPatches = mutableListOf<ElementPatch>()
        val jumpedIssues = mutableListOf<DeckLintIssue>()

        val storyboard = Storyboard.build(title = "UI Probe", format = stageFormat) {
            scene(frames = listOf(0, 1)) {
                Box(Modifier.fillMaxSize()) {
                    Text("Beat content", style = TextStyle(fontSize = 30.sp, color = Color.White))
                }
            }
        }

        // Seed beat 0 with 1 Error (LOW_CONTRAST) + 1 Warning (TINY_STAGE_TEXT).
        registry.record(
            StageNodeSnapshot(
                elementId = "s0-low-contrast",
                kind = "Text",
                label = "Muted Label",
                sceneIndex = 0,
                beatIndex = 0,
                stageBounds = Rect(120f, 120f, 520f, 170f),
                resolvedText = "Muted Label",
                effectiveForeground = Color(0xFF9A9FA8),
                effectiveBackground = Color(0xFFEBEBEB),
                effectiveFontSizeSp = 18f,
                isEditable = true,
            ),
        )
        registry.record(
            StageNodeSnapshot(
                elementId = "s0-tiny",
                kind = "Text",
                label = "Tiny Footnote",
                sceneIndex = 0,
                beatIndex = 0,
                stageBounds = Rect(120f, 300f, 480f, 340f),
                resolvedText = "Tiny Footnote",
                effectiveForeground = Color(0xFF242124),
                effectiveBackground = Color(0xFFEBEBEB),
                effectiveFontSizeSp = 10f,
                isEditable = true,
            ),
        )

        lateinit var animatic: Animatic
        setContent {
            CiacolaJewelTheme(isDark = false) {
                animatic = rememberAnimatic(storyboard = { storyboard })
                Box(Modifier.size(340.dp, 540.dp)) {
                    DeckLintPanel(
                        controller = controller,
                        animatic = animatic,
                        stageInspectionRegistry = registry,
                        onApplyElementPatch = { appliedPatches += it },
                        onJumpToIssue = { jumpedIssues += it },
                        isActive = false,
                    )
                }
            }
        }

        runOnIdle {
            controller.evaluateActiveBeatInternal(
                registry = registry,
                sceneIndex = 0,
                beatIndex = 0,
                semanticsTextNodes = emptyList(),
            )
        }
        waitForIdle()

        onNodeWithTag("ciacola-lint-summary-pill").assertIsDisplayed()
        onNodeWithText("1 Error · 1 Warning").assertIsDisplayed()
        assertEquals(2, controller.filteredIssues.size)

        // Filter to Errors only.
        onNodeWithTag("ciacola-lint-filter-errors").performClick()
        waitForIdle()
        assertEquals(1, controller.filteredIssues.size)
        assertEquals(LintRuleId.LOW_CONTRAST, controller.filteredIssues.single().ruleId)

        // Click the issue card -> triggers jump + selection.
        val errorIssue = controller.filteredIssues.single()
        onNodeWithTag("ciacola-lint-issue-${errorIssue.id}").performClick()
        waitForIdle()
        assertEquals(listOf(errorIssue), jumpedIssues)
        assertEquals(errorIssue.id, controller.selectedIssueId)

        // Click the "Fix Contrast" quick-fix button -> emits valid ElementPatch.
        onNodeWithTag("ciacola-lint-fix-${errorIssue.id}-Fix Contrast").performClick()
        waitForIdle()
        assertEquals(1, appliedPatches.size)
        val patch = appliedPatches.single()
        assertEquals("s0-low-contrast", patch.elementId)
        assertNotNull(patch.textColorArgb)
        assertTrue(controller.errorCount == 1)

        // Filter to Warnings only -> shows Tiny Text warning.
        onNodeWithTag("ciacola-lint-filter-warnings").performClick()
        waitForIdle()
        assertEquals(1, controller.filteredIssues.size)
        assertEquals(LintRuleId.TINY_STAGE_TEXT, controller.filteredIssues.single().ruleId)
    }

    @Test
    fun stageOverlayHidesCompletelyInFullscreenShowMode() = runComposeUiTest {
        val controller = DeckLintController()
        val registry = StageInspectionRegistry()
        registry.record(
            StageNodeSnapshot(
                elementId = "s0-tiny",
                kind = "Text",
                label = "Tiny",
                sceneIndex = 0,
                beatIndex = 0,
                stageBounds = Rect(120f, 120f, 400f, 160f),
                resolvedText = "Tiny",
                effectiveForeground = Color.White,
                effectiveBackground = Color.Black,
                effectiveFontSizeSp = 10f,
            ),
        )
        controller.evaluateActiveBeatInternal(
            registry = registry,
            sceneIndex = 0,
            beatIndex = 0,
            semanticsTextNodes = emptyList(),
        )

        var fullscreen by mutableStateOf(false)
        var inspectionVisible by mutableStateOf(true)
        var sceneMode by mutableStateOf(SceneMode.Story)

        setContent {
            CiacolaJewelTheme(isDark = true) {
                CompositionLocalProvider(
                    LocalPresentingFullscreen provides fullscreen,
                    LocalSceneMode provides sceneMode,
                ) {
                    Box(Modifier.size(640.dp, 360.dp)) {
                        Surface(Modifier.fillMaxSize(), color = Color.Black) {}
                        StageLintHighlightOverlay(
                            controller = controller,
                            isInspectionVisible = inspectionVisible,
                        )
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(1)

        // Entering Fullscreen / Show Mode must hide the overlay immediately.
        fullscreen = true
        waitForIdle()
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(0)

        // Returning from fullscreen restores overlay only while inspectionVisible is true and Story mode.
        fullscreen = false
        waitForIdle()
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(1)

        sceneMode = SceneMode.Preview
        waitForIdle()
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(0)

        sceneMode = SceneMode.Story
        inspectionVisible = false
        waitForIdle()
        onAllNodesWithTag("ciacola-lint-overlay").assertCountEquals(0)
    }
}
