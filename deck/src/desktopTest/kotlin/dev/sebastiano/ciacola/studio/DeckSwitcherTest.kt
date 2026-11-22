package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.presenter.PresenterDashboard
import dev.sebastiano.ciacola.presenter.rememberPresenterWindow
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Verifies document-deck catalog registration, [StudioSessionStore] persistence, and live switching
 * across decks via [DeckSwitcherDropdown] + [PresenterDashboard]:
 * - [DeckCatalog.entries] registers all 4 canonical document decks (`foundation`, `shaders-quickie-2025`,
 *   `jewel-talk-2024`, `not-just-plain-text-2024`).
 * - `-Dciacola.deck=<id>` takes precedence over `.storyboard/session.json`.
 * - Switching decks resets to `(0, 0)`, isolates per-deck [DeckDocumentStore]s, persists `session.json`,
 *   and updates the Presenter top bar deck title badge.
 */
@OptIn(ExperimentalTestApi::class)
class DeckSwitcherTest {

    @Test
    fun deckCatalogAndSessionStoreResolution() {
        assertEquals(
            listOf(
                DeckCatalog.FOUNDATION_ID,
                DeckCatalog.SHADERS_QUICKIE_ID,
                DeckCatalog.JEWEL_TALK_ID,
                DeckCatalog.NOT_JUST_PLAIN_TEXT_ID,
            ),
            DeckCatalog.entries.map { it.id },
            "Built-in DeckCatalog must register all 4 canonical document decks",
        )

        val rootDir = Files.createTempDirectory("ciacola-deck-switch-unit-")
        try {
            assertEquals(
                rootDir,
                storyboardDirForDeck(rootDir, DeckCatalog.FOUNDATION_ID),
                "Foundation deck keeps the root .storyboard/ directory for review comments",
            )
            assertEquals(
                rootDir.resolve("decks").resolve(DeckCatalog.SHADERS_QUICKIE_ID),
                storyboardDirForDeck(rootDir, DeckCatalog.SHADERS_QUICKIE_ID),
            )

            assertEquals(
                DeckCatalog.FOUNDATION_ID,
                resolveInitialDeckId(rootDir, systemPropertyOverride = null),
            )

            persistActiveDeckId(rootDir, DeckCatalog.SHADERS_QUICKIE_ID)
            assertTrue(Files.isRegularFile(rootDir.resolve(SESSION_FILE_NAME)))
            assertEquals(
                DeckCatalog.SHADERS_QUICKIE_ID,
                resolveInitialDeckId(rootDir, systemPropertyOverride = null),
            )

            assertEquals(
                DeckCatalog.JEWEL_TALK_ID,
                resolveInitialDeckId(
                    rootDir,
                    systemPropertyOverride = DeckCatalog.JEWEL_TALK_ID,
                ),
            )

            assertEquals(
                DeckCatalog.SHADERS_QUICKIE_ID,
                resolveInitialDeckId(
                    rootDir,
                    systemPropertyOverride = "unknown-deck",
                ),
            )
        } finally {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun switcherCyclesDocumentDecksWithIsolatedStoresAndPresenterBadge() = runComposeUiTest {
        val rootDir = Files.createTempDirectory("ciacola-deck-switch-ui-")
        val twoDeckSubset = listOf(DeckCatalog.foundation, DeckCatalog.shadersQuickie2025)
        val storesById = mutableMapOf<String, DeckDocumentStore>()
        lateinit var currentAnimatic: Animatic
        lateinit var currentStore: DeckDocumentStore
        lateinit var currentDeckEntry: DeckEntry
        lateinit var menuWindow: DeckMenuWindow
        lateinit var scope: CoroutineScope

        try {
            setContent {
                scope = rememberCoroutineScope()
                var activeDeckId by remember {
                    mutableStateOf(
                        resolveInitialDeckId(
                            rootDir,
                            systemPropertyOverride = null,
                            entries = twoDeckSubset,
                        ),
                    )
                }
                val activeDeck = remember(activeDeckId) { DeckCatalog.byId(activeDeckId, twoDeckSubset) }
                currentDeckEntry = activeDeck

                val store = remember(activeDeck.id) {
                    storesById.getOrPut(activeDeck.id) {
                        createLoadedTestDeckStore(loadClasspathDeckDocument(activeDeck))
                    }
                }
                currentStore = store

                val session = remember(store) { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
                val storyboard = rememberDocumentStoryboard(
                    session = session,
                    config = DocumentRenderConfig(specimens = activeDeck.specimens),
                )
                val animatic = key(activeDeck.id) {
                    rememberAnimatic(
                        initialIndex = Storyboard.Index(0, 0),
                        storyboard = { storyboard },
                    )
                }
                currentAnimatic = animatic

                val selectDeck: (DeckEntry) -> Unit = { next ->
                    if (next.id != activeDeck.id) {
                        persistActiveDeckId(rootDir, next.id)
                        activeDeckId = next.id
                    }
                }
                menuWindow = rememberDeckMenuWindow(
                    activeDeckProvider = { activeDeck },
                    onSelectDeck = selectDeck,
                    animaticProvider = { animatic },
                    isTextInputActiveProvider = { false },
                    isStudioOpenProvider = { true },
                    entries = twoDeckSubset,
                )

                val presenterWindow = rememberPresenterWindow(animatic = animatic)

                CiacolaJewelTheme(isDark = true) {
                    Column(Modifier.size(1024.dp, 680.dp)) {
                        DeckSwitcherDropdown(
                            activeDeck = activeDeck,
                            onSelectDeck = selectDeck,
                            entries = twoDeckSubset,
                        )
                        PresenterDashboard(
                            controller = presenterWindow.controller,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
            waitForIdle()

            // 1. Starts on Foundation Deck (5 slides).
            assertEquals(2, menuWindow.entries.size)
            assertEquals(DeckCatalog.FOUNDATION_ID, currentDeckEntry.id)
            assertEquals(5, currentAnimatic.storyboard.scenes.size)
            onNodeWithTag("ciacola-presenter-deck-title").assertTextEquals(currentAnimatic.storyboard.title)

            // Jump to Slide 2 and edit Foundation's s1:eyebrow in its isolated store.
            runOnIdle {
                scope.launch { currentAnimatic.jumpTo(Storyboard.Index(1, 0)) }
                currentStore.edit("Edit Foundation Eyebrow") { doc ->
                    doc.copy(
                        slides = doc.slides.mapIndexed { idx, s ->
                            if (idx == 0) {
                                s.copy(
                                    nodes = s.nodes.map { n ->
                                        if (n.id == "s1:eyebrow") {
                                            n.copy(text = n.text!!.copy(value = RichTextValue.Plain("FOUNDATION EDITED")))
                                        } else {
                                            n
                                        }
                                    },
                                )
                            } else {
                                s
                            }
                        },
                    )
                }
            }
            waitForIdle()
            assertEquals(Storyboard.Index(1, 0), currentAnimatic.currentIndex)

            // 2. Switch to Shaders Quickie via toolbar dropdown -> lands on (0, 0) with clean isolated store!
            onNodeWithTag("ciacola-deck-switcher").performClick()
            waitForIdle()
            onNodeWithTag("ciacola-deck-option-${DeckCatalog.SHADERS_QUICKIE_ID}", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(DeckCatalog.SHADERS_QUICKIE_ID, currentDeckEntry.id)
            assertEquals(Storyboard.Index(0, 0), currentAnimatic.currentIndex, "Switching decks must land on (0, 0)")
            assertEquals(48, currentAnimatic.storyboard.scenes.size)
            assertTrue(!currentStore.isDirty, "Shaders store must remain clean and isolated from Foundation edits")
            onNodeWithTag("ciacola-presenter-deck-title").assertTextEquals(currentAnimatic.storyboard.title)
            assertEquals(
                DeckCatalog.SHADERS_QUICKIE_ID,
                studioSessionStore(rootDir).load().activeDeckId,
            )
        } finally {
            rootDir.toFile().deleteRecursively()
        }
    }
}
