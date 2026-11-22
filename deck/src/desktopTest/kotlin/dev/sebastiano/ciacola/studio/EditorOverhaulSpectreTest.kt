package dev.sebastiano.ciacola.studio

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.junit.Rule

/**
 * Real-window Spectre E2E test exercising the unified Studio Inspector (`Motion | Element | Theme | Lint`),
 * live Document Theme edit + Undo, live Direct Element stage picking + text edit + Undo, and
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
            )

            with(studio) {
                settle()

                // 1. Verify the selection-scoped Design | Animate tabs mount inside docked Studio.
                assertNotNull(automator.findOneByTestTag("ciacola-studio-tab-animate"))
                val elementTab = checkNotNull(automator.findOneByTestTag("ciacola-studio-tab-design"))
                assertEquals(StudioInspectorTab.Design, studioWorkspace.activeTab)

                val baselineFrame = screenshot(settleDelayMs = 60L)

                // 2. Switch to Theme tab via real synthetic click, mutate stage background live,
                //    verify visible Skia pixels update, then Undo via the Theme Undo button.
                onUi { studioWorkspace.toggleDesignSystemEditor() }
                settle()
                assertTrue(studioWorkspace.themeSheetOpen)
                assertEquals(StudioInspectorTab.Design, studioWorkspace.activeTab)

                onUi {
                    themeController.setThemeColor("background", "#184E68")
                }
                settle()
                val themedFrame = screenshot(settleDelayMs = 60L)
                assertTrue(
                    differingFraction(baselineFrame, themedFrame) > 0.05,
                    "Expected live Theme background token edit to visibly repaint the stage window",
                )

                onUi { studioWorkspace.undo() }
                settle()
                assertFalse(store.canUndo)

                // 3. Switch to Element tab, click Slide 1's eyebrow directly on the live stage to pick it,
                //    apply live text copy + Undo via the Element Undo button.
                automator.click(elementTab)
                settle()
                assertEquals(StudioInspectorTab.Element, studioWorkspace.activeTab)

                val eyebrowNode = stageText("FOUNDATION / PROVISIONAL", exact = true)
                automator.click(eyebrowNode)
                settle()
                assertEquals("s1:eyebrow", elementController.selectedNodeId)

                onUi {
                    elementController.applyWholeTextReplacement("LIVE STUDIO STAGE EDIT", nodeId = "s1:eyebrow")
                }
                settle()
                assertNotNull(stageText("LIVE STUDIO STAGE EDIT", exact = true))

                val elementUndoBtn = checkNotNull(automator.findOneByTestTag("ciacola-app-bar-undo"))
                automator.click(elementUndoBtn)
                settle()
                assertNotNull(stageText("FOUNDATION / PROVISIONAL", exact = true))
                assertFalse(store.canUndo)

                // 4. Switch to Lint tab, click 'Audit Full Deck', wait for completion across dark + light,
                //    and assert 0 lint errors out of the box.
                onUi { studioWorkspace.toggleDeckLinter() }
                settle()
                assertTrue(studioWorkspace.issuesDockOpen)
                onUi { lintController.startFullDeckAudit(CoroutineScope(Dispatchers.Main), listOf("dark", "light")) }

                val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
                while (System.nanoTime() < deadline) {
                    automator.waitForIdle()
                    if (!lintController.isAuditing && lintController.lastStats.slidesAudited > 0) break
                    delay(80)
                }

                assertFalse(lintController.isAuditing, "Expected Full-Deck Lint audit to finish")
                assertTrue(lintController.lastStats.slidesAudited > 0, "Expected Full-Deck Lint audit to record results")
                assertEquals(
                    0,
                    lintController.errorCount,
                    "Expected 0 lint errors across foundation.deck.json, found: ${lintController.issues}",
                )
            }
        }
}
