package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.authoring.ciacolaDeck
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.presenter.PresenterDashboard
import dev.sebastiano.ciacola.presenter.rememberPresenterWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.LocalDeckLoopClock
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Verifies §4.2 / D-18 / A-10:
 * - Default [DeckCatalog.entries] registers [DeckCatalog.foundation].
 * - Per-deck `.storyboard/` directory scoping (Foundation uses root `.storyboard/`; additional
 *   decks use `.storyboard/decks/<deckId>/`).
 * - `-Dciacola.deck=<id>` precedence over `.storyboard/active-deck`.
 * - Live switching across 3 catalog decks via [DeckSwitcherDropdown] (and [DeckMenuWindow]) resets
 *   each deck to `(0, 0)`, isolates per-deck stores cleanly, persists `.storyboard/active-deck`,
 *   and updates the Presenter top bar deck badge.
 */
@OptIn(ExperimentalTestApi::class)
class DeckSwitcherTest {

    private companion object {
        const val SYNTHETIC_ALPHA_ID = "synthetic-alpha"
        const val SYNTHETIC_BETA_ID = "synthetic-beta"

        val syntheticAlphaEntry = DeckEntry(
            id = SYNTHETIC_ALPHA_ID,
            title = "Synthetic Alpha",
            create = { host ->
                ciacolaDeck(id = SYNTHETIC_ALPHA_ID, title = "Alpha Talk", host = host) {
                    slide(key = "alpha-1", title = "Alpha Intro", beats = 2) {
                        val theme = LocalDeckTheme.current
                        DeckEditableText(
                            elementId = "alpha:s1:title",
                            text = "Alpha Headline",
                            style = theme.typography.title,
                            color = theme.colors.foreground,
                        )
                    }
                }
            },
        )

        val syntheticBetaEntry = DeckEntry(
            id = SYNTHETIC_BETA_ID,
            title = "Synthetic Beta",
            create = { host ->
                ciacolaDeck(id = SYNTHETIC_BETA_ID, title = "Beta Talk", host = host) {
                    slide(key = "beta-1", title = "Beta Intro", beats = 2) {
                        val theme = LocalDeckTheme.current
                        DeckEditableText(
                            elementId = "beta:s1:title",
                            text = "Beta Headline",
                            style = theme.typography.title,
                            color = theme.colors.foreground,
                        )
                    }
                }
            },
        )

        val threeDeckCatalog: List<DeckEntry> = listOf(
            DeckCatalog.foundation,
            syntheticAlphaEntry,
            syntheticBetaEntry,
        )
    }

    @Test
    fun deckStoreScopingAndInitialDeckResolution() {
        assertEquals(
            listOf(DeckCatalog.foundation),
            DeckCatalog.entries,
            "Built-in DeckCatalog must ship only Foundation Deck by default",
        )

        val rootDir = Files.createTempDirectory("ciacola-deck-switch-unit-")
        try {
            assertEquals(
                rootDir,
                storyboardDirForDeck(rootDir, DeckCatalog.FOUNDATION_ID),
                "Foundation deck must keep the root .storyboard/ directory",
            )
            assertEquals(
                rootDir.resolve("decks").resolve(SYNTHETIC_ALPHA_ID),
                storyboardDirForDeck(rootDir, SYNTHETIC_ALPHA_ID),
            )
            assertEquals(
                rootDir.resolve("decks").resolve(SYNTHETIC_BETA_ID),
                storyboardDirForDeck(rootDir, SYNTHETIC_BETA_ID),
            )

            // Default falls back to Foundation.
            assertEquals(
                DeckCatalog.FOUNDATION_ID,
                resolveInitialDeckId(rootDir, systemPropertyOverride = null, entries = threeDeckCatalog),
            )

            // Persisted active-deck is honored when no system property is supplied.
            persistActiveDeckId(rootDir, SYNTHETIC_ALPHA_ID)
            assertEquals(
                SYNTHETIC_ALPHA_ID,
                resolveInitialDeckId(rootDir, systemPropertyOverride = null, entries = threeDeckCatalog),
            )

            // Explicit -Dciacola.deck=<id> takes precedence over persisted active-deck.
            assertEquals(
                SYNTHETIC_BETA_ID,
                resolveInitialDeckId(
                    rootDir,
                    systemPropertyOverride = SYNTHETIC_BETA_ID,
                    entries = threeDeckCatalog,
                ),
            )

            // Unknown system property falls back to persisted active-deck rather than crashing.
            assertEquals(
                SYNTHETIC_ALPHA_ID,
                resolveInitialDeckId(
                    rootDir,
                    systemPropertyOverride = "unknown-deck",
                    entries = threeDeckCatalog,
                ),
            )
        } finally {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun switcherCyclesAllThreeDecksWithIsolatedStoresAndPresenterBadge() = runComposeUiTest {
        val rootDir = Files.createTempDirectory("ciacola-deck-switch-ui-")
        lateinit var currentAnimatic: Animatic
        lateinit var currentElementStore: ElementOverridesStore
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
                            entries = threeDeckCatalog,
                        ),
                    )
                }
                val activeDeck = remember(activeDeckId) { DeckCatalog.byId(activeDeckId, threeDeckCatalog) }
                currentDeckEntry = activeDeck

                val deckStoryboardDir = remember(activeDeck.id) {
                    storyboardDirForDeck(rootDir, activeDeck.id)
                }
                val elementStore = remember(activeDeck.id) {
                    ElementOverridesStore(storyboardDir = deckStoryboardDir)
                }
                DisposableEffect(elementStore) {
                    onDispose { elementStore.close() }
                }
                currentElementStore = elementStore

                val storyboard = remember(activeDeck.id) {
                    activeDeck.create(
                        DeckHostParams(
                            theme = DeckThemes.stageDark(),
                            bundledPreset = DeckThemePreset.StageDark,
                            elementOverridesProvider = {
                                elementStore.revision
                                elementStore.data()
                            },
                        ),
                    )
                }
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
                    entries = threeDeckCatalog,
                )

                val presenterWindow = rememberPresenterWindow(animatic = animatic)

                val frozenLoopClock = remember { DeckLoopClock().apply { masterTimeMillis = 0L } }
                CompositionLocalProvider(
                    LocalAmbientLoopsPaused provides true,
                    LocalDeckLoopClock provides frozenLoopClock,
                ) {
                    CiacolaJewelTheme(isDark = true) {
                        Column(Modifier.size(1024.dp, 680.dp)) {
                            DeckSwitcherDropdown(
                                activeDeck = activeDeck,
                                onSelectDeck = selectDeck,
                                entries = threeDeckCatalog,
                            )
                            PresenterDashboard(
                                controller = presenterWindow.controller,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 1. Starts on Foundation Deck with Keynote Effects Lab included.
            assertEquals(3, menuWindow.entries.size)
            assertEquals(DeckCatalog.FOUNDATION_ID, currentDeckEntry.id)
            assertEquals("Ciacola", currentAnimatic.storyboard.title)
            val baseFoundationScenes = dev.sebastiano.ciacola.createFoundationDeck(
                includeKeynoteEffectsLab = false,
            ).scenes.size
            assertTrue(
                currentAnimatic.storyboard.scenes.size > baseFoundationScenes,
                "Foundation catalog entry must include Keynote Effects Lab scenes (${currentAnimatic.storyboard.scenes.size} vs base $baseFoundationScenes)",
            )
            onNodeWithTag("ciacola-presenter-deck-title").assertTextEquals("Ciacola")

            // Move off (0, 0) and write a Foundation-scoped element override.
            runOnIdle {
                scope.launch { currentAnimatic.jumpTo(Storyboard.Index(1, 0)) }
                currentElementStore.updateElement("s1:eyebrow") {
                    it.copy(text = "FOUNDATION OVERRIDE")
                }
            }
            waitForIdle()
            assertEquals(Storyboard.Index(1, 0), currentAnimatic.currentIndex)
            assertTrue(Files.isRegularFile(rootDir.resolve("element-overrides.json")))

            // 2. Switch to Synthetic Alpha via toolbar dropdown.
            onNodeWithTag("ciacola-deck-switcher").performClick()
            waitForIdle()
            onNodeWithTag("ciacola-deck-option-$SYNTHETIC_ALPHA_ID", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(SYNTHETIC_ALPHA_ID, currentDeckEntry.id)
            assertEquals("Alpha Talk", currentAnimatic.storyboard.title)
            assertEquals(
                Storyboard.Index(0, 0),
                currentAnimatic.currentIndex,
                "Switching decks must land cleanly on (0, 0)",
            )
            onNodeWithTag("ciacola-presenter-deck-title").assertTextEquals("Alpha Talk")
            assertNull(
                currentElementStore.data().elements["s1:eyebrow"],
                "Foundation overrides must not leak into Synthetic Alpha store",
            )

            // Persist an Alpha-only override and verify it lands under decks/synthetic-alpha/.
            runOnIdle {
                currentElementStore.updateElement("alpha:s1:title") {
                    it.copy(text = "ALPHA OVERRIDE")
                }
            }
            waitForIdle()
            val alphaOverrideFile = rootDir
                .resolve("decks")
                .resolve(SYNTHETIC_ALPHA_ID)
                .resolve("element-overrides.json")
            assertTrue(Files.isRegularFile(alphaOverrideFile))

            // 3. Switch to Synthetic Beta.
            onNodeWithTag("ciacola-deck-switcher").performClick()
            waitForIdle()
            onNodeWithTag("ciacola-deck-option-$SYNTHETIC_BETA_ID", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(SYNTHETIC_BETA_ID, currentDeckEntry.id)
            assertEquals("Beta Talk", currentAnimatic.storyboard.title)
            assertEquals(Storyboard.Index(0, 0), currentAnimatic.currentIndex)
            onNodeWithTag("ciacola-presenter-deck-title").assertTextEquals("Beta Talk")
            assertFalse(currentElementStore.data().hasOverrides)

            // 4. Switch back to Foundation Deck — lands on (0, 0) with Foundation store intact.
            onNodeWithTag("ciacola-deck-switcher").performClick()
            waitForIdle()
            onNodeWithTag("ciacola-deck-option-${DeckCatalog.FOUNDATION_ID}", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(DeckCatalog.FOUNDATION_ID, currentDeckEntry.id)
            assertEquals("Ciacola", currentAnimatic.storyboard.title)
            assertEquals(Storyboard.Index(0, 0), currentAnimatic.currentIndex)
            onNodeWithTag("ciacola-presenter-deck-title").assertTextEquals("Ciacola")
            assertEquals(
                "FOUNDATION OVERRIDE",
                currentElementStore.data().elements["s1:eyebrow"]?.text,
                "Foundation override must still exist in root .storyboard/ after round-trip deck switches",
            )
            assertNull(currentElementStore.data().elements["alpha:s1:title"])
            assertEquals(
                DeckCatalog.FOUNDATION_ID,
                resolveInitialDeckId(rootDir, systemPropertyOverride = null, entries = threeDeckCatalog),
                "active-deck persistence must record the latest deck id",
            )
        } finally {
            rootDir.toFile().deleteRecursively()
        }
    }
}
