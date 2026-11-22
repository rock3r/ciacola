package dev.sebastiano.ciacola.studio

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.ciacola.qa.SyntheticInput
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule

/**
 * Verifies Show-mode focus-proof navigation & global `F2` Presenter toggle:
 * 1. Clicking an interactive live Compose `Specimen` button (`specimen-focus-play-button`) in
 *    Show Mode must never trap `→` / `←` slide navigation.
 * 2. Pressing `F2` opens Presenter View from the Story window, navigation keys work while
 *    Presenter owns focus, and pressing `F2` again while Presenter owns focus closes it.
 */
class ShowModeNavigationFocusSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun clickingLiveSpecimenButtonInShowModeDoesNotBreakArrowNavigation(): Unit = runSpectreTest(timeout = 120.seconds) {
        val specimens = deckSpecimens {
            specimen("test.springButton") {
                content {
                    var triggered by remember { mutableStateOf(false) }
                    Button(
                        onClick = { triggered = !triggered },
                        modifier = Modifier
                            .stageHitRegion()
                            .testTag("specimen-focus-play-button"),
                    ) {
                        Text(if (triggered) "← Reset Springs" else "Trigger Springs →")
                    }
                }
            }
        }

        val doc = DeckDocument(
            id = "specimen-focus-fixture",
            title = "Specimen Focus Fixture",
            slides = listOf(
                SlideSpec(
                    id = "interactive-specimen",
                    title = "Interactive Specimen",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "focus:s1:col",
                            column = ContainerPayload(
                                gap = 24.0,
                                padding = PaddingSpec(all = 96.0),
                                children = listOf(
                                    NodeSpec(
                                        id = "focus:s1:title",
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Beat One — Interactive Specimen"),
                                            style = "title",
                                        ),
                                        builds = listOf(
                                            Build.Change(
                                                beat = 1,
                                                set = buildJsonObject {
                                                    put(
                                                        "text",
                                                        buildJsonObject {
                                                            put("value", "Beat Two — Advanced Cleanly")
                                                        },
                                                    )
                                                },
                                            ),
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "focus:s1:specimen",
                                        interactive = "pointer",
                                        specimen = SpecimenPayload(ref = "test.springButton"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val studio = qa.launchStudio(
            timelineDocked = false,
            worker = "show-nav-focus",
            documentFactory = { doc to specimens },
        )
        with(studio) {
            assertEquals(Storyboard.Index(0, 0), animatic.presentedIndex)
            settle()

            val playButton = automator.waitForNode(tag = "specimen-focus-play-button", timeout = 5.seconds)
            automator.click(playButton)
            automator.waitUntil(description = "live spring specimen toggled after click", timeout = 5.seconds) {
                automator.findByText("← Reset Springs", true).isNotEmpty()
            }
            settle()

            // With focus now sitting on the interactive specimen button, → must still advance!
            automator.pressKey(KeyEvent.VK_RIGHT)
            automator.waitUntil(
                description = "→ advances from (0, 0) to (0, 1) after clicking live specimen (at ${animatic.presentedIndex})",
                timeout = 5.seconds,
            ) {
                animatic.presentedIndex == Storyboard.Index(0, 1)
            }
            assertEquals(Storyboard.Index(0, 1), animatic.presentedIndex)

            // ← must also step back cleanly to beat 0.
            automator.pressKey(KeyEvent.VK_LEFT)
            automator.waitUntil(
                description = "← returns from (0, 1) to (0, 0) (at ${animatic.presentedIndex})",
                timeout = 5.seconds,
            ) {
                animatic.presentedIndex == Storyboard.Index(0, 0)
            }
            assertEquals(Storyboard.Index(0, 0), animatic.presentedIndex)
        }
    }

    @Test
    fun f2OpensAndClosesPresenterViewEvenWhenPresenterOwnsFocus(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(
            deckId = DeckCatalog.FOUNDATION_ID,
            timelineDocked = false,
            worker = "show-f2-toggle",
        )
        with(studio) {
            assertFalse(presenterWindow.visible, "Presenter View must start closed")
            settle()

            // Press F2 from Story window -> opens Presenter View.
            automator.pressKey(KeyEvent.VK_F2)
            automator.waitUntil(description = "F2 opens Presenter View window", timeout = 10.seconds) {
                presenterWindow.visible && presenterComposeWindow?.isShowing == true
            }
            settle()
            val presenterWin = assertNotNull(presenterComposeWindow, "Presenter ComposeWindow must be mounted")
            assertTrue(presenterWindow.visible)

            // Dispatch → directly to the focused Presenter window -> advances slide/beat.
            val presenterInput = SyntheticInput(presenterWin)
            val before = animatic.presentedIndex
            presenterInput.keyPress(KeyEvent.VK_RIGHT)
            delay(30)
            presenterInput.keyRelease(KeyEvent.VK_RIGHT)
            automator.waitUntil(
                description = "→ in Presenter window advances from $before (at ${animatic.presentedIndex})",
                timeout = 5.seconds,
            ) {
                animatic.presentedIndex != before
            }

            // Dispatch F2 directly to the focused Presenter window -> closes Presenter View.
            presenterInput.keyPress(KeyEvent.VK_F2)
            delay(30)
            presenterInput.keyRelease(KeyEvent.VK_F2)
            automator.waitUntil(description = "F2 in Presenter window closes Presenter View", timeout = 5.seconds) {
                !presenterWindow.visible && presenterComposeWindow == null
            }
            assertFalse(presenterWindow.visible)
        }
    }
}
