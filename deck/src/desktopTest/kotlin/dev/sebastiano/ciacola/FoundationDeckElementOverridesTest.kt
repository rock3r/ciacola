package dev.sebastiano.ciacola

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

@OptIn(ExperimentalTestApi::class)
class FoundationDeckElementOverridesTest {

    @Test
    fun foundationDeckCapturesStageSnapshotsAndAppliesPerBeatAndStampEdits() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        val store = createLoadedTestDeckStore()
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope

        setContent {
            val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
            val storyboard = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    themeName = "dark",
                    specimens = AllDocumentDecks.foundation.specimens,
                ),
            )
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalStageInspectionRegistry provides registry) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    animatic = rememberAnimatic(storyboard = { storyboard })
                    Easel(animatic)
                }
            }
        }

        waitForIdle()
        onNodeWithText("FOUNDATION / PROVISIONAL").assertIsDisplayed()
        assertNotNull(registry.nodes["s1:eyebrow"])
        assertNotNull(registry.nodes["s1:prose"])
        assertEquals("A scene begins with one deterministic state.", registry.nodes["s1:prose"]?.resolvedText)

        // Apply per-beat edit on Beat 1 of s1:prose and baseline edit on s5:stamp inside DeckDocumentStore:
        runOnIdle {
            store.edit("Edit Beat 1 Prose and Stamp") { doc ->
                doc.copy(
                    slides = doc.slides.map { slide ->
                        when (slide.id) {
                            "beats" -> slide.copy(
                                nodes = slide.nodes.map { node ->
                                    if (node.id == "s1:prose") {
                                        JsonMergePatch.applyEditAtBeat(
                                            node,
                                            1,
                                            buildJsonObject {
                                                putJsonObject("text") {
                                                    put("value", JsonPrimitive("Custom Beat One Copy"))
                                                }
                                            },
                                        )
                                    } else {
                                        node
                                    }
                                },
                            )
                            "keynote-effects-lab" -> slide.copy(
                                nodes = slide.nodes.map { node ->
                                    if (node.id == "s5:loupe-stage") {
                                        node.copy(
                                            loupe = node.loupe!!.copy(
                                                children = node.loupe!!.children.map { child ->
                                                    if (child.id == "s5:stamp") {
                                                        child.copy(
                                                            text = child.text!!.copy(
                                                                value = RichTextValue.Plain("CUSTOMIZED"),
                                                            ),
                                                        )
                                                    } else {
                                                        child
                                                    }
                                                },
                                            ),
                                        )
                                    } else {
                                        node
                                    }
                                },
                            )
                            else -> slide
                        }
                    },
                )
            }
        }
        waitForIdle()
        assertEquals("A scene begins with one deterministic state.", registry.nodes["s1:prose"]?.resolvedText)

        // Advance to Beat 1 -> MagicText v2 displays custom beat-1 copy and records s1:prose.
        runOnIdle { scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        waitForIdle()
        assertEquals("Custom Beat One Copy", registry.nodes["s1:prose"]?.resolvedText)

        // Jump to Slide 5 Beat 2 (Keynote Effects Lab with stamp).
        runOnIdle { scope.launch { animatic.jumpTo(Storyboard.Index(4, 2)) } }
        waitForIdle()
        onNodeWithText("CUSTOMIZED").assertIsDisplayed()
        val stampSnap = registry.nodes["s5:stamp"]
        assertNotNull(stampSnap)
        assertEquals("CUSTOMIZED", stampSnap.resolvedText)
        assertTrue(registry.nodes.containsKey("s5:headline"))
        assertTrue(registry.nodes.containsKey("s5:row-web"))
    }

    @Test
    fun reactiveStoreUpdatesElementsAndThemeInPlaceWithoutRebuildingStoryboard() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        val store = createLoadedTestDeckStore()
        lateinit var builtStoryboard: Storyboard
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope

        setContent {
            val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
            val storyboard = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    themeName = "dark",
                    specimens = AllDocumentDecks.foundation.specimens,
                ),
            )
            builtStoryboard = storyboard
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalStageInspectionRegistry provides registry) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    animatic = rememberAnimatic(storyboard = { storyboard })
                    Easel(animatic)
                }
            }
        }

        waitForIdle()
        val initialStoryboard = builtStoryboard

        // Edit node text in DeckDocumentStore -> stage reflects change without rebuilding Storyboard.
        runOnIdle {
            store.edit("Edit Beat 0 Prose") { doc ->
                doc.copy(
                    slides = doc.slides.mapIndexed { idx, slide ->
                        if (idx == 0) {
                            slide.copy(
                                nodes = slide.nodes.map { node ->
                                    if (node.id == "s1:prose") {
                                        node.copy(text = node.text!!.copy(value = RichTextValue.Plain("Reactive Document Prose")))
                                    } else {
                                        node
                                    }
                                },
                            )
                        } else {
                            slide
                        }
                    },
                )
            }
        }
        waitForIdle()
        assertSame(initialStoryboard, builtStoryboard)
        assertEquals("Reactive Document Prose", registry.nodes["s1:prose"]?.resolvedText)

        // Jump to Slide 4 (Highlighted Code) and edit dark theme colors -> Storyboard identity stays identical!
        runOnIdle { scope.launch { animatic.jumpTo(Storyboard.Index(3, 0)) } }
        waitForIdle()
        runOnIdle {
            store.edit("Edit Dark Theme Code Colors") { doc ->
                val dark = doc.themes.getValue("dark")
                doc.copy(
                    themes = doc.themes + ("dark" to dark.copy(
                        colors = dark.colors + ("codeBg" to "#102030") + ("codeText" to "#E2E8F0"),
                    )),
                )
            }
        }
        waitForIdle()
        assertSame(initialStoryboard, builtStoryboard)
        assertNotNull(registry.nodes["s4:code"])
    }
}
