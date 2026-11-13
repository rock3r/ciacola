package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.effects.DeckSurfaceCard
import dev.sebastiano.ciacola.effects.editableBox
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.contrastRatio
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DirectElementEditorUiTest {

    @Test
    fun editablePrimitivesRecordSnapshotsPropagateBackgroundAndApplyOverrides() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        var overrides by mutableStateOf(DeckElementOverridesFile())
        var showChild by mutableStateOf(true)
        val cardFill = Color(0xFF1E293B)

        setContent {
            DeckThemeProvider(DeckThemes.keynoteLight()) {
                CompositionLocalProvider(
                    LocalStageInspectionRegistry provides registry,
                    LocalElementOverrides provides overrides,
                ) {
                    Box(Modifier.size(800.dp, 450.dp).padding(20.dp)) {
                        DeckSurfaceCard(
                            elementId = "card-1",
                            label = "Dark Card",
                            fillColor = cardFill,
                            borderColor = Color.White,
                            borderWidth = 2.dp,
                            cornerRadius = 12.dp,
                            contentPadding = 16.dp,
                        ) {
                            Column {
                                DeckEditableText(
                                    elementId = "title-1",
                                    text = "Original Headline",
                                    color = Color.White,
                                )
                                if (showChild) {
                                    DeckEditableText(
                                        elementId = "overflow-1",
                                        text = "Very long text that should overflow a narrow fixed width box cleanly",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(120.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        waitForIdle()
        val titleSnap = registry.nodes["title-1"]
        assertNotNull(titleSnap)
        assertEquals("Original Headline", titleSnap.resolvedText)
        assertEquals(Color.White, titleSnap.effectiveForeground)
        assertEquals(cardFill, titleSnap.effectiveBackground)
        assertTrue(titleSnap.stageBounds.width > 0f && titleSnap.stageBounds.height > 0f)

        val overflowSnap = registry.nodes["overflow-1"]
        assertNotNull(overflowSnap)
        assertTrue(overflowSnap.hasVisualOverflow, "Expected overflow=true on narrow 1-line text")
        assertTrue(overflowSnap.hasEllipsis, "Expected ellipsis=true on truncated 1-line text")

        // Unmount child -> snapshot must be removed cleanly.
        showChild = false
        waitForIdle()
        assertNull(registry.nodes["overflow-1"])

        // Live override updates text, color, font size, and offsets.
        val beforeBounds = titleSnap.stageBounds
        overrides = DeckElementOverridesFile(
            elements = mapOf(
                "title-1" to ElementPatch(
                    elementId = "title-1",
                    text = "Edited Title",
                    textColorArgb = 0xFF38BDF8L,
                    fontSizeSp = 40f,
                    offsetXDp = 24f,
                    offsetYDp = 12f,
                ),
            ),
        )
        waitForIdle()
        onNodeWithText("Edited Title").assertIsDisplayed()
        val updatedSnap = registry.nodes["title-1"]
        assertNotNull(updatedSnap)
        assertEquals("Edited Title", updatedSnap.resolvedText)
        assertEquals(Color(0xFF38BDF8L), updatedSnap.effectiveForeground)
        assertEquals(40f, updatedSnap.effectiveFontSizeSp)
        assertTrue(updatedSnap.stageBounds.left > beforeBounds.left)
        assertTrue(updatedSnap.stageBounds.top > beforeBounds.top)
    }

    @Test
    fun stageDirectEditIsShowModeSafeAndSupportsDoubleClickInlineEditAndNudge() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-stage-edit")
        val store = ElementOverridesStore(dir)
        val controller = ElementEditorController(store)

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(LocalElementOverrides provides store.data().also { store.revision }) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        controller.stageDecorator.decorate {
                            Box(Modifier.fillMaxSize().padding(40.dp)) {
                                DeckEditableText(
                                    elementId = "s1:headline",
                                    text = "Click Me",
                                    modifier = Modifier.editableBox(elementId = "s1:box", defaultPadding = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. Show Mode (editorActive = false): zero overlays, clicks do not select.
        waitForIdle()
        onNodeWithTag("ciacola-direct-edit-overlay").assertDoesNotExist()
        onNodeWithText("Click Me").performClick()
        waitForIdle()
        assertNull(controller.selectedElementId)

        // 2. Editor Mode (editorActive = true): single-click selects, arrows nudge, Cmd+Backspace resets.
        controller.editorActive = true
        waitForIdle()
        onNodeWithTag("ciacola-direct-edit-overlay").assertIsDisplayed()
        val targetBounds = controller.inspectionRegistry.nodes["s1:headline"]!!.stageBounds

        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            click(Offset(targetBounds.center.x, targetBounds.center.y))
        }
        waitForIdle()
        assertEquals("s1:headline", controller.selectedElementId)

        // Plain ArrowRight nudges by +1 dp; Shift+ArrowRight nudges by +10 dp.
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput {
            pressKey(Key.DirectionRight)
            withKeyDown(Key.ShiftLeft) {
                pressKey(Key.DirectionRight)
            }
        }
        waitForIdle()
        assertEquals(11f, store.patchFor("s1:headline")?.offsetXDp)

        // Plain Backspace does NOT delete or reset element.
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput {
            pressKey(Key.Backspace)
        }
        waitForIdle()
        assertEquals(11f, store.patchFor("s1:headline")?.offsetXDp)

        // Double-click opens Inline Stage Text Editor; Enter commits.
        val movedBounds = controller.inspectionRegistry.nodes["s1:headline"]!!.stageBounds
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            doubleClick(Offset(movedBounds.center.x, movedBounds.center.y))
        }
        waitForIdle()
        onNodeWithTag("ciacola-inline-stage-editor").assertIsDisplayed()
        onNodeWithTag("ciacola-inline-stage-editor-input").performTextReplacement("Inline Updated")
        onNodeWithTag("ciacola-inline-stage-editor-input").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertNull(controller.inlineEditingElementId)
        assertEquals("Inline Updated", store.patchFor("s1:headline")?.text)

        // Esc cancels inline edit without overwriting.
        controller.startInlineEdit("s1:headline")
        waitForIdle()
        onNodeWithTag("ciacola-inline-stage-editor-input").performTextReplacement("Discarded")
        onNodeWithTag("ciacola-inline-stage-editor-input").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        assertNull(controller.inlineEditingElementId)
        assertEquals("Inline Updated", store.patchFor("s1:headline")?.text)

        // Cmd+Backspace resets selected element cleanly.
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                pressKey(Key.Backspace)
            }
        }
        waitForIdle()
        assertNull(store.patchFor("s1:headline"))
    }

    @Test
    fun elementEditorPanelEditsCopyContrastFixFillBorderAndSupportsUndoRedo() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-el-panel")
        val store = ElementOverridesStore(dir)
        val controller = ElementEditorController(store).apply { editorActive = true }

        setContent {
            DeckThemeProvider(DeckThemes.keynoteLight()) {
                CompositionLocalProvider(LocalElementOverrides provides store.data().also { store.revision }) {
                    Box(Modifier.size(960.dp, 640.dp)) {
                        DeckEditableText(
                            elementId = "s1:headline",
                            text = "Low Contrast",
                            color = Color(0xFFB8B8B8), // ~1.9:1 on KeynoteLight #EBEBEB
                        )
                    }
                }
            }
            // Populate inspection registry for s1:headline via controller directly to test Panel cleanly.
        }

        // Render both stage node and ElementEditorPanel in one test tree.
        setContent {
            DeckThemeProvider(DeckThemes.keynoteLight()) {
                CompositionLocalProvider(
                    LocalElementOverrides provides store.data().also { store.revision },
                    LocalStageInspectionRegistry provides controller.inspectionRegistry,
                ) {
                    Box(Modifier.size(1200.dp, 760.dp)) {
                        Box(Modifier.size(800.dp, 600.dp)) {
                            DeckEditableText(
                                elementId = "s1:headline",
                                text = "Low Contrast",
                                color = Color(0xFFB8B8B8),
                            )
                        }
                        Box(Modifier.width(340.dp)) {
                            CiacolaJewelTheme(isDark = false) {
                                ElementEditorPanel(controller = controller)
                            }
                        }
                    }
                }
            }
        }

        waitForIdle()
        onNodeWithTag("ciacola-element-picker-s1:headline").performClick()
        waitForIdle()
        assertEquals("s1:headline", controller.selectedElementId)

        // 1. Live text copy edit
        onNodeWithTag("ciacola-element-text-input").performTextReplacement("Crisp Copy")
        waitForIdle()
        assertEquals("Crisp Copy", store.patchFor("s1:headline")?.text)

        // 2. 1-click Fix AA raises contrast ratio >= 4.5:1
        onNodeWithTag("ciacola-element-textcolor-fix-aa").performClick()
        waitForIdle()
        val fixedArgb = store.patchFor("s1:headline")?.textColorArgb
        assertNotNull(fixedArgb)
        assertTrue(
            contrastRatio(Color(fixedArgb), DeckThemes.keynoteLight().colors.background) >= 4.5f,
            "Fix AA must lift contrast ratio to >= 4.5:1",
        )

        // 3. Weight pill 700, Clear Fill, Border Width +, Corner Radius +
        onNodeWithTag("ciacola-element-weight-700").performClick()
        onNodeWithTag("ciacola-element-fill-clear").performClick()
        onNodeWithTag("ciacola-element-borderwidth-inc").performClick()
        onNodeWithTag("ciacola-element-cornerradius-inc").performClick()
        onNodeWithTag("ciacola-element-padding-inc").performClick()
        onNodeWithTag("ciacola-element-offsetx-inc").performClick()
        waitForIdle()

        val patch = store.patchFor("s1:headline")!!
        assertEquals(700, patch.fontWeight)
        assertEquals(0L, patch.fillColorArgb)
        assertTrue((patch.borderWidthDp ?: 0f) > 0f)
        assertTrue((patch.cornerRadiusDp ?: 0f) > 0f)
        assertTrue((patch.paddingDp ?: 0f) > 0f)
        assertTrue((patch.offsetXDp ?: 0f) > 0f)

        // 4. Undo + Redo + Reset Element
        onNodeWithTag("ciacola-element-undo").performClick()
        waitForIdle()
        assertNull(store.patchFor("s1:headline")?.offsetXDp)
        onNodeWithTag("ciacola-element-redo").performClick()
        waitForIdle()
        assertTrue((store.patchFor("s1:headline")?.offsetXDp ?: 0f) > 0f)

        onNodeWithTag("ciacola-element-reset").performClick()
        waitForIdle()
        assertNull(store.patchFor("s1:headline"))
    }

    @Test
    fun transparentFillPromotesToOpaqueOnPickRangeEndsDisableWeightResetsAndOffBeatHidesEditors() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-el-pass-b")
        val store = ElementOverridesStore(dir)
        var activeScene by mutableStateOf(0)
        val controller = ElementEditorController(store).apply {
            editorActive = true
            currentSceneIndexProvider = { activeScene }
        }

        setContent {
            DeckThemeProvider(DeckThemes.keynoteLight()) {
                CompositionLocalProvider(
                    LocalElementOverrides provides store.data().also { store.revision },
                    LocalStageInspectionRegistry provides controller.inspectionRegistry,
                ) {
                    Box(Modifier.size(1200.dp, 760.dp)) {
                        Box(Modifier.size(800.dp, 600.dp)) {
                            DeckEditableText(
                                elementId = "s1:headline",
                                text = "Headline",
                            )
                        }
                        Box(Modifier.width(320.dp)) {
                            CiacolaJewelTheme(isDark = false) {
                                ElementEditorPanel(controller = controller)
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-element-picker-s1:headline").performClick()
        waitForIdle()

        // 1. Default transparent fill promotes to alpha = 1f when picking on SV canvas or Hue bar.
        onNodeWithTag("ciacola-element-fill-swatch", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("ciacola-element-fill-sv-canvas", useUnmergedTree = true).performScrollTo().performMouseInput {
            click(Offset(width * 0.7f, height * 0.3f))
        }
        waitForIdle()
        val pickedSvFill = Color(checkNotNull(store.patchFor("s1:headline")?.fillColorArgb))
        assertEquals(1f, pickedSvFill.alpha, 0.01f, "Picking on SV canvas from transparent fill must yield alpha = 1f")

        // Clear back to transparent via compact icon button, then pick on Hue bar -> alpha = 1f again.
        onNodeWithTag("ciacola-element-fill-clear", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        assertEquals(0L, store.patchFor("s1:headline")?.fillColorArgb)
        onNodeWithTag("ciacola-element-fill-hue-bar", useUnmergedTree = true).performScrollTo().performMouseInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        waitForIdle()
        val pickedHueFill = Color(checkNotNull(store.patchFor("s1:headline")?.fillColorArgb))
        assertEquals(1f, pickedHueFill.alpha, 0.01f, "Picking on Hue bar after Clear must yield alpha = 1f")

        // 2. Per-row Weight reset works cleanly.
        onNodeWithTag("ciacola-element-weight-reset", useUnmergedTree = true).performScrollTo().assertIsNotEnabled()
        onNodeWithTag("ciacola-element-weight-700", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        assertEquals(700, store.patchFor("s1:headline")?.fontWeight)
        onNodeWithTag("ciacola-element-weight-reset", useUnmergedTree = true).assertIsEnabled().performClick()
        waitForIdle()
        assertNull(store.patchFor("s1:headline")?.fontWeight)

        // 3. Steppers disable at range ends and Offset X/Y share ±480dp range with nudgeSelected.
        onNodeWithTag("ciacola-element-borderwidth-dec", useUnmergedTree = true).performScrollTo().assertIsNotEnabled()
        runOnIdle { assertTrue(controller.nudgeSelected(300f, 0f)) }
        waitForIdle()
        onNodeWithTag("ciacola-element-offsetx-value", useUnmergedTree = true).performScrollTo().assertTextEquals("300 dp")
        runOnIdle { assertTrue(controller.nudgeSelected(250f, 0f)) }
        waitForIdle()
        onNodeWithTag("ciacola-element-offsetx-value", useUnmergedTree = true).assertTextEquals("480 dp")
        onNodeWithTag("ciacola-element-offsetx-inc", useUnmergedTree = true).assertIsNotEnabled()

        // 4. Leaving the slide hides stale property editors, blocks off-beat nudge, and offers Clear Selection.
        runOnIdle { activeScene = 1 }
        waitForIdle()
        onNodeWithTag("ciacola-element-missing-on-beat", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-element-fill-swatch", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-element-fontsize-inc", useUnmergedTree = true).assertDoesNotExist()
        runOnIdle { assertFalse(controller.nudgeSelected(-20f, 0f)) }
        assertEquals(480f, store.patchFor("s1:headline")?.offsetXDp)

        onNodeWithTag("ciacola-element-clear-selection", useUnmergedTree = true).performClick()
        waitForIdle()
        assertNull(controller.selectedElementId)
    }
}
