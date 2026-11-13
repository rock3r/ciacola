package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesignSystemEditorUiTest {
    @Test
    fun colorTabSupportsFixAaHexValidationGestureUndoAndCopyKotlin() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-design-ui-colors")
        val store = DesignSystemStore(tempDir)
        var activePreset by mutableStateOf(DeckThemePreset.StageDark)
        var copiedText = ""

        setContent {
            Box(Modifier.size(560.dp, 680.dp)) {
                DesignSystemEditorPanel(
                    store = store,
                    currentPreset = activePreset,
                    onSelectPreset = { activePreset = it },
                    clipboardWriter = { copiedText = it },
                )
            }
        }
        waitForIdle()

        // StageDark's subtle (#6E7687) is < 4.5:1 on surface (#171B24), so Fix AA is offered immediately.
        onNodeWithTag("design-color-subtle-contrast-badge", useUnmergedTree = true).performScrollTo()
        onNodeWithTag("design-color-subtle-fix-aa", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()

        val fixedSubtle = store.resolveTheme(DeckThemePreset.StageDark).colors.subtle
        assertTrue(
            worstWcagContrastRatio(
                fixedSubtle,
                DeckThemes.StageDarkColors.background,
                DeckThemes.StageDarkColors.surface,
            ) >= 4.55f,
        )

        // Expand accent swatch, validate invalid vs valid hex input, and test gesture-coalesced drag + Cmd+Z.
        onNodeWithTag("design-color-accent-swatch", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag("design-color-accent-hex", useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithTag("design-color-accent-hex", useUnmergedTree = true).performTextReplacement("bad")
        waitForIdle()
        onNodeWithTag("design-color-accent-hex-error", useUnmergedTree = true).assertExists()

        onNodeWithTag("design-color-accent-hex", useUnmergedTree = true).performTextReplacement("#33AAFF")
        waitForIdle()
        assertEquals(0xFF33AAFFL, store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)

        // Drag SV canvas continuously -> must create ONE undo step.
        val beforeDragArgb = store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb
        onNodeWithTag("design-color-accent-sv-canvas", useUnmergedTree = true).performScrollTo().performMouseInput {
            moveTo(Offset(10f, 10f))
            press()
            moveTo(Offset(80f, 40f))
            moveTo(Offset(140f, 70f))
            release()
            exit(Offset(-10f, -10f))
        }
        waitForIdle()
        val afterDragArgb = store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb
        assertNotEquals(beforeDragArgb, afterDragArgb)

        // Click Undo button once -> reverts entire SV drag back to #33AAFF.
        onNodeWithTag("design-editor-undo", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(0xFF33AAFFL, store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)

        // Redo with toolbar button, then Copy Kotlin.
        onNodeWithTag("design-editor-redo", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(afterDragArgb, store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)

        onNodeWithTag("design-editor-copy-kotlin", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(copiedText.contains("DesignSystemOverrides("))
        onNodeWithText("Copied ✓", useUnmergedTree = true).assertExists()

        // Switch preset to KeynoteLight: StageDark overrides remain untouched and KeynoteLight starts clean.
        onNodeWithTag("design-preset-keynote-light", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(DeckThemePreset.KeynoteLight, activePreset)
        onNodeWithTag("design-editor-reset-preset", useUnmergedTree = true).assertIsNotEnabled()

        // Switch back to StageDark and reset preset.
        onNodeWithTag("design-preset-stage-dark", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("design-editor-reset-preset", useUnmergedTree = true).assertIsEnabled().performClick()
        waitForIdle()
        assertNull(store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)
    }

    @Test
    fun typographyStylesAndMotionTabsEditTokensAndPreviewMotion() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-design-ui-tabs")
        val store = DesignSystemStore(tempDir)
        var activePreset by mutableStateOf(DeckThemePreset.StageDark)

        setContent {
            Box(Modifier.size(560.dp, 680.dp)) {
                DesignSystemEditorPanel(
                    store = store,
                    currentPreset = activePreset,
                    onSelectPreset = { activePreset = it },
                )
            }
        }
        waitForIdle()

        // Tab 2: Typography
        onNodeWithTag("design-tab-typography", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("design-typo-title-size-inc", useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithTag("design-typo-title-weight-800", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        assertEquals(74f, store.overridesFor(DeckThemePreset.StageDark).typography.title?.fontSizeSp)
        assertEquals(800, store.overridesFor(DeckThemePreset.StageDark).typography.title?.fontWeight)
        onNodeWithTag("design-typo-title-preview", useUnmergedTree = true).assertExists()

        // Tab 3: Surfaces & Effects
        onNodeWithTag("design-tab-styles", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("design-style-slidePadding-inc", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        assertEquals(68f, store.overridesFor(DeckThemePreset.StageDark).styles.slidePaddingDp)

        // Tab 4: Motion Tokens + Preview Motion button
        onNodeWithTag("design-tab-motion", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("design-motion-fadeMove-inc", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        assertEquals(375, store.overridesFor(DeckThemePreset.StageDark).motion.fadeMoveDurationMs)
        onNodeWithTag("design-motion-preview-play", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()

        // Keyboard Cmd+Z undoes the fadeMove edit when focus is not inside a text field.
        onNodeWithTag("design-system-editor-panel").performKeyInput {
            keyDown(Key.MetaLeft)
            keyDown(Key.Z)
            keyUp(Key.Z)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertNull(store.overridesFor(DeckThemePreset.StageDark).motion.fadeMoveDurationMs)

        // Standalone DesignSystemWindow contract check.
        val window = DesignSystemWindow(store = store)
        assertEquals(DpSize(520.dp, 560.dp), window.minimumSize)
        assertNotNull(window)
    }
}
