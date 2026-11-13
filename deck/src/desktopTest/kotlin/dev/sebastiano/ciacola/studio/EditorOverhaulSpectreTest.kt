package dev.sebastiano.ciacola.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.ciacola.qa.differingFraction
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.spectre.testing.runSpectreTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Rule

/**
 * Real-window Spectre E2E test exercising the unified Studio Inspector (`Motion | Element | Theme | Lint`),
 * live Design System override + Undo, live Direct Element stage picking + text override + Undo, and
 * Full-Deck Lint audit in a real JBR 25 `ComposeWindow` (`-Dapple.awt.UIElement=true`).
 */
class EditorOverhaulSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun unifiedStudioTabsDriveLiveDesignElementAndFullDeckLinterEndToEnd(): Unit =
        runSpectreTest(timeout = 90.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1360.dp, 880.dp),
                timelineDocked = true,
                includeKeynoteEffectsLab = true,
            )

            with(studio) {
                settle()

                // 1. Verify unified Motion | Element | Theme | Lint tabs mount inside docked Studio.
                assertNotNull(automator.findOneByTestTag("ciacola-studio-tab-motion"))
                val elementTab = checkNotNull(automator.findOneByTestTag("ciacola-studio-tab-element"))
                val themeTab = checkNotNull(automator.findOneByTestTag("ciacola-studio-tab-theme"))
                val lintTab = checkNotNull(automator.findOneByTestTag("ciacola-studio-tab-lint"))
                assertEquals(StudioInspectorTab.Motion, studioWorkspace.activeTab)

                val baselineFrame = screenshot(settleDelayMs = 60L)

                // 2. Switch to Theme tab via real synthetic click, mutate stage background live,
                //    verify visible Skia pixels update, then Undo via the Theme Undo button.
                automator.click(themeTab)
                settle()
                assertEquals(StudioInspectorTab.Theme, studioWorkspace.activeTab)
                assertNotNull(automator.findOneByTestTag("design-system-editor-panel"))

                val tealBgArgb = Color(0xFF184E68).toArgb().toLong() and 0xFFFFFFFFL
                onUi {
                    designStore.updateColors(DeckThemePreset.StageDark) {
                        it.copy(backgroundArgb = tealBgArgb)
                    }
                }
                settle()
                val themedFrame = screenshot(settleDelayMs = 60L)
                assertTrue(
                    differingFraction(baselineFrame, themedFrame) > 0.05,
                    "Expected live Design System background token edit to visibly repaint the stage window",
                )

                val themeUndoBtn = checkNotNull(automator.findOneByTestTag("design-editor-undo"))
                automator.click(themeUndoBtn)
                settle()
                assertFalse(designStore.overridesFor(DeckThemePreset.StageDark).hasOverrides)

                // 3. Switch to Element tab, click Slide 1's eyebrow directly on the live stage to pick it,
                //    apply live text copy + Undo via the Element Undo button.
                automator.click(elementTab)
                settle()
                assertEquals(StudioInspectorTab.Element, studioWorkspace.activeTab)

                val eyebrowNode = stageText("FOUNDATION / PROVISIONAL", exact = true)
                automator.click(eyebrowNode)
                settle()
                assertEquals("s1:eyebrow", elementController.selectedElementId)

                onUi {
                    elementStore.updateElement("s1:eyebrow") {
                        it.copy(text = "LIVE STUDIO STAGE EDIT")
                    }
                }
                settle()
                assertNotNull(stageText("LIVE STUDIO STAGE EDIT", exact = true))

                val elementUndoBtn = checkNotNull(automator.findOneByTestTag("ciacola-element-undo"))
                automator.click(elementUndoBtn)
                settle()
                assertNotNull(stageText("FOUNDATION / PROVISIONAL", exact = true))
                assertFalse(elementStore.data().hasOverrides)

                // 4. Switch to Lint tab, click 'Audit Deck', wait for completion across StageDark + KeynoteLight,
                //    and assert 0 lint errors out of the box.
                automator.click(lintTab)
                settle()
                assertEquals(StudioInspectorTab.Lint, studioWorkspace.activeTab)

                val auditBtn = checkNotNull(automator.findOneByTestTag("ciacola-lint-audit-button"))
                automator.click(auditBtn)

                val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
                while (System.nanoTime() < deadline) {
                    automator.waitForIdle()
                    if (!lintController.isAuditing && lintController.hasFullDeckAudit) break
                    delay(80)
                }

                assertFalse(lintController.isAuditing, "Expected Full-Deck Lint audit to finish")
                assertTrue(lintController.hasFullDeckAudit, "Expected Full-Deck Lint audit to record results")
                assertEquals(
                    0,
                    lintController.errorCount,
                    "Expected 0 lint errors across FoundationDeck, found: ${lintController.activeScopeIssues}",
                )
            }
        }
}
