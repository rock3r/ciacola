package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class IssuesPanelTest {

    private fun doc(): DeckDocument = DeckDocument(
        id = "issues-doc",
        title = "Issues",
        defaultTheme = "dark",
        themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark", colors = mapOf("canvas" to "#111827", "ink" to "#F8FAFC"))),
        slides = listOf(
            SlideSpec(id = "intro", title = "Welcome", background = Fill.Solid("#111827")),
            SlideSpec(
                id = "s2",
                title = "Contrast trouble",
                background = Fill.Solid("#111827"),
                nodes = listOf(
                    NodeSpec(
                        id = "dim",
                        frame = FrameSpec(x = 200.0, y = 400.0, w = 500.0, h = 80.0),
                        text = TextPayload(value = RichTextValue.Plain("Barely visible"), color = "#111827", size = 40.0),
                    ),
                    NodeSpec(
                        id = "edge",
                        frame = FrameSpec(x = 1800.0, y = 900.0, w = 400.0, h = 300.0),
                        text = TextPayload(value = RichTextValue.Plain("Off the edge"), size = 40.0),
                    ),
                ),
            ),
        ),
    )

    private fun issue(id: String, code: String, slideIndex: Int, slideId: String, nodeId: String?, title: String, detail: String, severity: LintSeverity = LintSeverity.Warning) =
        DocumentLintIssue(
            id = id, code = code, severity = severity, source = DocumentLintSource.StaticContrast,
            slideIndex = slideIndex, slideId = slideId, nodeId = nodeId, themeName = "dark", title = title, detail = detail,
        )

    @Test
    fun groupsBySlideWithReadableHeadersAndPlainSentences() {
        val d = doc()
        val issues = listOf(
            issue("i1", "LOW_CONTRAST", 1, "s2", "dim", "Low text contrast (1.00:1)", "[dark] 'dim' contrast 1.00:1 < WCAG 4.5:1 (static).", LintSeverity.Error),
            issue("i2", "STAGE_OUT_OF_BOUNDS", 1, "s2", "edge", "Node extends outside stage", "'edge' rect [..] exceeds 1920×1080."),
            issue("i3", "TEXT_OVERLAP", 1, "s2", "dim", "Overlapping text nodes", "'dim' and 'edge' overlap by 42%."),
            issue("i0", "SCHEMA_X", 0, "intro", null, "SCHEMA_X", "slides[0].nodes: something is wrong"),
        )
        val groups = groupLintIssuesBySlide(issues, d)
        assertEquals(listOf("intro", "s2"), groups.map { it.slideId })
        assertEquals("Slide 2 · \u201CContrast trouble\u201D · 3 issues", groups[1].headerText)
        assertEquals("i1", groups[1].issues.first().id, "Errors sort first")
        assertEquals("Slide 1 · \u201CWelcome\u201D · 1 issue", groups[0].headerText)

        assertEquals("\u201CBarely visible\u201D is hard to read: contrast 1:1, needs 4.5:1.", plainLintSentence(issues[0], d))
        assertEquals("\u201COff the edge\u201D extends past the edge of the slide.", plainLintSentence(issues[1], d))
        assertEquals("\u201CBarely visible\u201D and \u201COff the edge\u201D overlap by 42%.", plainLintSentence(issues[2], d))
        assertEquals("Something is wrong.", plainLintSentence(issues[3], d))
        assertTrue(issues.map { plainLintSentence(it, d) }.none { "LOW_CONTRAST" in it || "'" in it })
        assertEquals("52 errors · 270 warnings", humanLintSummary(52, 270))
        assertEquals("1 error · 1 warning", humanLintSummary(1, 1))
    }

    @Test
    fun notCheckedYetEmptyStateThenGroupedRowsWithShowAndFix() = runComposeUiTest {
        val store = DeckDocumentStore(doc(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        val jumped = mutableListOf<String>()
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(1100.dp, 320.dp)) {
                    DocumentDeckLintPanel(
                        controller = controller,
                        currentSlideIndex = 1,
                        autoAuditCurrentSlide = false,
                        onJumpToIssue = { jumped += it.id },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Not checked yet", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-doc-lint-check-deck", useUnmergedTree = true).assertExists()
        onAllNodesWithText("No lint issues reported.", useUnmergedTree = true).assertCountEquals(0)
        // One `Check deck` only: the toolbar hides its copy while the empty state offers it.
        onNodeWithTag("ciacola-doc-lint-audit-all", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-doc-lint-summary", useUnmergedTree = true).assertDoesNotExist()

        runBlocking { controller.lintSlideNow(slideIndex = 1, themeNames = listOf("dark")) }
        waitForIdle()
        assertEquals(LintAuditScope.Slide, controller.auditScope)
        onNodeWithTag("ciacola-doc-lint-audit-all", useUnmergedTree = true).assertExists()
        val contrast = controller.issues.first { it.code == "LOW_CONTRAST" }
        onNodeWithTag("ciacola-doc-lint-group-s2", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-doc-lint-stripe-${contrast.id}", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-doc-lint-show-${contrast.id}", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf(contrast.id), jumped)

        onNodeWithTag("ciacola-doc-lint-fix-${contrast.id}", useUnmergedTree = true).performClick()
        waitForIdle()
        val dim = store.document.slides[1].nodes.first { it.id == "dim" }
        assertEquals("foreground", dim.text?.color)
        assertTrue(controller.issues.none { it.id == contrast.id })
        store.undo()
        assertEquals("#111827", store.document.slides[1].nodes.first { it.id == "dim" }.text?.color, "Fix is one undo step")

        // Collapsing the slide group hides its rows.
        onNodeWithTag("ciacola-doc-lint-group-s2", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-doc-lint-show-${contrast.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun narrowSidecarLayoutAndOutOfBoundsFix() = runComposeUiTest {
        val store = DeckDocumentStore(doc(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        setContent {
            CiacolaJewelTheme(isDark = false) {
                Box(Modifier.size(360.dp, 600.dp)) {
                    DocumentDeckLintPanel(controller = controller, currentSlideIndex = 1, autoAuditCurrentSlide = true, compactHorizontal = false)
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { controller.auditScope == LintAuditScope.Slide }
        waitForIdle()
        val oob = controller.issues.firstOrNull { it.code == "STAGE_OUT_OF_BOUNDS" }
        if (oob != null) {
            onNodeWithTag("ciacola-doc-lint-sentence-${oob.id}", useUnmergedTree = true).assertExists()
            runOnIdle { assertTrue(controller.fixIssue(oob)) }
            val edge = store.document.slides[1].nodes.first { it.id == "edge" }.frame!!
            assertEquals(1520.0, edge.x)
            assertEquals(780.0, edge.y)
        }
    }
}
