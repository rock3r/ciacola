package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.studio.DocumentSpeakerNotesMemo
import dev.sebastiano.ciacola.studio.StudioWorkspaceController
import dev.sebastiano.ciacola.studio.prepareToLeaveDeck
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.studio.saveSpeakerNote
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.Rectangle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage-ready Presenter View: aligned `Current`/`Next`, adaptive notes width, labelled clocks,
 * inline speaker-notes editing persisted as undoable edits, and clicker keys.
 */
@OptIn(ExperimentalTestApi::class)
class PresenterStageReadyTest {

    private val singleDisplay = object : DisplayRoutingAdapter {
        override fun screens(): List<DisplayScreenInfo> =
            listOf(DisplayScreenInfo("d1", "Display 1 (1920×1080 · Primary)", Rectangle(0, 0, 1920, 1080), true))

        override fun presentStoryOn(screen: DisplayScreenInfo): Boolean = true
        override fun swapAudienceAndPresenter(): Boolean = false
    }

    private class Mounted(val window: PresenterWindow, val animatic: Animatic, val scope: CoroutineScope)

    private fun androidx.compose.ui.test.ComposeUiTest.mountPresenter(
        store: DeckDocumentStore,
        showPresenter: () -> Boolean = { true },
        activeStore: () -> DeckDocumentStore = { store },
        notesBlocked: () -> Boolean = { false },
    ): () -> Mounted {
        lateinit var mounted: Mounted
        setContent {
            val scope = rememberCoroutineScope()
            val config = remember {
                DocumentRenderConfig(themeName = "dark", specimens = AllDocumentDecks.foundation.specimens)
            }
            val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
            val storyboard = rememberDocumentStoryboard(session = session, config = config)
            val animatic = rememberAnimatic(storyboard = { storyboard })
            val memo = remember { DocumentSpeakerNotesMemo() }
            val window = rememberPresenterWindow(
                animatic = animatic,
                speakerNotesProvider = { memo.notesFor(activeStore().document) },
                documentProvider = { activeStore().document },
                onSaveSpeakerNote = { slideId, beat, note -> saveSpeakerNote(activeStore(), slideId, beat, note) },
                notesEditingBlockedProvider = notesBlocked,
                displayRouter = singleDisplay,
            )
            mounted = Mounted(window, animatic, scope)
            Box(Modifier.size(1400.dp, 860.dp)) {
                CiacolaJewelTheme(isDark = true) {
                    if (showPresenter()) window.Content()
                }
            }
        }
        return { mounted }
    }

    private fun clearNotes(store: DeckDocumentStore, slideIndex: Int) {
        val slide = store.document.slides.filterNot { it.skipped }[slideIndex]
        slide.beats.indices.forEach { saveSpeakerNote(store, slide.id, it, null) }
    }

    @Test
    fun currentAndNextShareOneLeftEdgeAndWidthAndNotesPaneAdaptsToContent() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()

        val current = onNodeWithTag("ciacola-presenter-current-preview").getBoundsInRoot()
        val next = onNodeWithTag("ciacola-presenter-next-preview").getBoundsInRoot()
        assertTrue(abs((current.left - next.left).value) < 0.5f, "Next must sit directly under Current (${current.left} vs ${next.left})")
        assertTrue(
            abs((current.right - current.left).value - (next.right - next.left).value) < 0.5f,
            "Current and Next must share one preview width",
        )
        assertTrue(next.top >= current.bottom, "Next must be stacked below Current")

        val dashboard = onNodeWithTag("ciacola-presenter-dashboard").getBoundsInRoot()
        val dashboardWidth = (dashboard.right - dashboard.left).value
        fun notesShare(): Float {
            val pane = onNodeWithTag("ciacola-presenter-notes-pane").getBoundsInRoot()
            return (pane.right - pane.left).value / dashboardWidth
        }
        assertTrue(notesShare() >= 0.39f, "Notes pane must be at least 40% with notes (${notesShare()})")
        fun deadStripDp(): Float {
            val pane = onNodeWithTag("ciacola-presenter-notes-pane").getBoundsInRoot()
            val preview = onNodeWithTag("ciacola-presenter-current-preview").getBoundsInRoot()
            return (pane.left - preview.right).value
        }
        assertTrue(deadStripDp() <= 18f, "Preview column must hug its previews, no dead strip (${deadStripDp()}dp)")

        onNodeWithTag("ciacola-presenter-wall-clock-label", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Clock", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Elapsed", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("ciacola-presenter-deck-title", useUnmergedTree = true).assertIsDisplayed()

        runOnIdle { clearNotes(store, mounted().animatic.currentIndex.sceneIndex) }
        waitForIdle()
        assertTrue(notesShare() >= 0.27f, "Notes pane must be at least 28% when empty (${notesShare()})")
        assertTrue(deadStripDp() <= 18f, "Empty notes must not leave a dead strip either (${deadStripDp()}dp)")
        onNodeWithText("No notes for this slide", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("ciacola-presenter-notes-add").assertIsDisplayed()
        val wider = onNodeWithTag("ciacola-presenter-current-preview").getBoundsInRoot()
        assertTrue(
            (wider.right - wider.left).value >= (current.right - current.left).value,
            "Previews must never shrink when the notes pane narrows",
        )
    }

    @Test
    fun inlineNotesEditingTypesFreelyAndSavesAsOneUndoableEdit() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()
        runOnIdle { clearNotes(store, 0) }
        waitForIdle()
        val startIndex = mounted().animatic.targetIndex
        val revisionBefore = store.revision

        onNodeWithTag("ciacola-presenter-notes-add").performClick()
        waitForIdle()
        assertTrue(mounted().window.controller.isEditingNotes)
        val input = onNodeWithTag("ciacola-presenter-notes-input")
        input.performTextInput("Pause here. Breathe")
        // Navigation and curtain keys must type into the field, never drive the deck.
        input.performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
            pressKey(Key.B)
            pressKey(Key.W)
            pressKey(Key.PageDown)
        }
        waitForIdle()
        assertEquals(startIndex, mounted().animatic.targetIndex, "Typing notes must not advance the deck")
        assertEquals(StageCurtain.None, mounted().window.controller.curtain, "Typing B/W must not toggle curtains")

        onNodeWithTag("ciacola-presenter-notes-save").performClick()
        waitForIdle()
        val saved = store.document.slides.first().beats[0].notes
        assertTrue(saved != null && saved.startsWith("Pause here. Breathe"), "Notes must persist to beats[0].notes: $saved")
        assertEquals(revisionBefore + 1, store.revision, "Saving notes must be one atomic history entry")
        onNodeWithTag("ciacola-presenter-notes-content").assertIsDisplayed()

        runOnIdle { store.undo() }
        waitForIdle()
        assertNull(store.document.slides.first().beats[0].notes, "Undo must restore the previous notes")

        // Edit → Esc cancels without writing.
        runOnIdle { saveSpeakerNote(store, store.document.slides.first().id, 0, "Original") }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-edit").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput(" changed")
        onNodeWithTag("ciacola-presenter-notes-input").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals("Original", store.document.slides.first().beats[0].notes)
        assertTrue(onAllNodesWithTag("ciacola-presenter-notes-editor").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun inlineNotesEditorIsCancelledWhenTheDeckSwitchesUnderIt() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()
        runOnIdle { clearNotes(store, 0) }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-add").performClick()
        waitForIdle()
        val controller = mounted().window.controller
        assertTrue(controller.isEditingNotes)
        // Same slide/build: the editor stays bound.
        runOnIdle { assertEquals(false, controller.commitOrCancelEditingNotesIfTargetMoved()) }
        assertTrue(controller.isEditingNotes)
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput("Draft for the old slide")

        // The captured (slideId, beat) target disappears: close without writing anywhere.
        runOnIdle {
            store.edit("Simulate deck switch") { doc ->
                doc.copy(slides = doc.slides.mapIndexed { i, s -> if (i == 0) s.copy(id = "other-deck-slide") else s })
            }
        }
        val revisionAfterSwitch = store.revision
        waitForIdle()
        assertFalse(controller.isEditingNotes, "Stale target must close the editor")
        assertNull(controller.editingNoteTarget)
        assertEquals(revisionAfterSwitch, store.revision, "A vanished target must never be written")
        assertNull(store.document.slides.first().beats[0].notes)
        assertTrue(onAllNodesWithTag("ciacola-presenter-notes-editor").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun inlineNotesDraftIsNeverWrittenIntoADifferentDeck() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()
        runOnIdle { clearNotes(store, 0) }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-add").performClick()
        waitForIdle()
        val controller = mounted().window.controller
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput("Belongs to the old deck")
        // Same slide ids, different deck id: the new deck must stay untouched.
        runOnIdle { store.edit("Swap deck") { doc -> doc.copy(id = doc.id + "-other") } }
        val revisionAfterSwitch = store.revision
        waitForIdle()
        assertFalse(controller.isEditingNotes)
        assertEquals(revisionAfterSwitch, store.revision, "Drafts must never cross into another deck")
        assertNull(store.document.slides.first().beats[0].notes)
    }

    @Test
    fun navigatingAwayAutoCommitsADirtyDraftToThePreviousBuild() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()
        runOnIdle { clearNotes(store, 0) }
        waitForIdle()
        val firstSlide = store.document.slides.filterNot { it.skipped }[0]
        val start = mounted().animatic.targetIndex

        // 1. Story-side navigation (the animatic moves under the Presenter).
        onNodeWithTag("ciacola-presenter-notes-add").performClick()
        waitForIdle()
        val startTarget = mounted().window.controller.editingNoteTarget!!
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput("Story side draft")
        val before = store.revision
        runOnIdle { mounted().scope.launch { mounted().animatic.advance(AdvanceDirection.Forward) } }
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertTrue(mounted().animatic.targetIndex > start)
        assertFalse(mounted().window.controller.isEditingNotes, "Navigating away must close the editor")
        assertEquals(before + 1, store.revision, "Auto-commit must be one undoable edit")
        assertEquals(
            "Story side draft",
            store.document.slides.first { it.id == startTarget.slideId }.beats[startTarget.beatIndex].notes,
        )
        runOnIdle { store.undo() }
        waitForIdle()
        assertNull(store.document.slides.first { it.id == firstSlide.id }.beats[startTarget.beatIndex].notes)

        // 2. Presenter Next button commits synchronously before stepping.
        val target = mounted().window.controller.currentNoteTarget()!!
        val existing = store.document.slides.first { it.id == target.slideId }.beats[target.beatIndex].notes
        if (existing.isNullOrBlank()) {
            onNodeWithTag("ciacola-presenter-notes-add").performClick()
        } else {
            onNodeWithTag("ciacola-presenter-notes-edit").performClick()
        }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput("Next button draft ")
        val beforeNext = store.revision
        val indexBeforeNext = mounted().animatic.targetIndex
        onNodeWithTag("ciacola-presenter-next-beat").performClick()
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertTrue(mounted().animatic.targetIndex > indexBeforeNext)
        assertFalse(mounted().window.controller.isEditingNotes)
        assertEquals(beforeNext + 1, store.revision)
        val saved = store.document.slides.first { it.id == target.slideId }.beats[target.beatIndex].notes
        assertTrue(saved != null && saved.startsWith("Next button draft"), "Draft must land on the previous build: $saved")
    }

    @Test
    fun navigatingAwayFromAnUnchangedDraftAddsNoHistory() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()
        runOnIdle { saveSpeakerNote(store, store.document.slides.first().id, 0, "Unchanged") }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-edit").performClick()
        waitForIdle()
        assertTrue(mounted().window.controller.isEditingNotes)
        val before = store.revision
        onNodeWithTag("ciacola-presenter-next-beat").performClick()
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertFalse(mounted().window.controller.isEditingNotes)
        assertEquals(before, store.revision, "An unchanged draft must not add an undo entry")
        assertEquals("Unchanged", store.document.slides.first().beats[0].notes)
    }

    @Test
    fun cancelButtonDiscardsTheDraft() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()
        runOnIdle { saveSpeakerNote(store, store.document.slides.first().id, 0, "Original") }
        waitForIdle()
        val before = store.revision
        onNodeWithTag("ciacola-presenter-notes-edit").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput(" changed")
        onNodeWithTag("ciacola-presenter-notes-cancel").performClick()
        waitForIdle()
        assertFalse(mounted().window.controller.isEditingNotes)
        // Navigating afterwards must not resurrect the discarded draft.
        onNodeWithTag("ciacola-presenter-next-beat").performClick()
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertEquals(before, store.revision)
        assertEquals("Original", store.document.slides.first().beats[0].notes)
    }

    @Test
    fun closingThePresenterWhileEditingAutoCommitsButNotAfterEscape() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        var show by mutableStateOf(true)
        val mounted = mountPresenter(store, showPresenter = { show })
        waitForIdle()
        runOnIdle { clearNotes(store, 0) }
        waitForIdle()
        val slideId = store.document.slides.first().id

        onNodeWithTag("ciacola-presenter-notes-add").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput("Saved on close")
        val before = store.revision
        runOnIdle { show = false }
        waitForIdle()
        assertFalse(mounted().window.controller.isEditingNotes)
        assertEquals(before + 1, store.revision)
        assertEquals("Saved on close", store.document.slides.first { it.id == slideId }.beats[0].notes)

        runOnIdle { show = true }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-edit").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput(" discarded")
        onNodeWithTag("ciacola-presenter-notes-input").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        val afterEscape = store.revision
        runOnIdle { show = false }
        waitForIdle()
        assertEquals(afterEscape, store.revision, "Esc must discard; closing afterwards writes nothing")
        assertEquals("Saved on close", store.document.slides.first { it.id == slideId }.beats[0].notes)
    }

    @Test
    fun prepareToLeaveDeckCommitsTheDraftIntoTheOutgoingStoreBeforeTheFlush() = runComposeUiTest {
        val outgoingStore = createLoadedTestDeckStore()
        // Same deck file on both sides: identical slide ids make a late write maximally dangerous.
        val incomingStore = createLoadedTestDeckStore()
        var active by mutableStateOf(outgoingStore)
        val mounted = mountPresenter(outgoingStore, activeStore = { active })
        waitForIdle()
        runOnIdle { clearNotes(outgoingStore, 0) }
        waitForIdle()
        val slideId = outgoingStore.document.slides.first().id
        val incomingRevision = incomingStore.revision
        val incomingNote = incomingStore.document.slides.first().beats[0].notes
        onNodeWithTag("ciacola-presenter-notes-add").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-input").performTextInput("Flushed with the old deck")
        val outgoingRevision = outgoingStore.revision
        val controller = mounted().window.controller
        val specimens = AllDocumentDecks.foundation.specimens
        val elementController = DocumentElementInspectorController(store = outgoingStore, specimens = specimens)
        val workspace = StudioWorkspaceController(
            store = outgoingStore,
            timelineController = DocumentTimelineController(store = outgoingStore, specimens = specimens),
            elementController = elementController,
            themeController = DocumentThemeInspectorController(store = outgoingStore),
            lintController = DocumentDeckLintController(store = outgoingStore, specimens = specimens),
        )
        var flushed = false
        val onFlushAndClose = {
            assertEquals(outgoingRevision + 1, outgoingStore.revision, "The draft must be committed before the flush")
            assertEquals("Flushed with the old deck", outgoingStore.document.slides.first { it.id == slideId }.beats[0].notes)
            assertFalse(controller.isEditingNotes)
            active = incomingStore
            flushed = true
        }
        runOnIdle {
            prepareToLeaveDeck(workspace, elementController, controller)
            onFlushAndClose()
        }
        waitForIdle()
        assertTrue(flushed)
        assertFalse(controller.isEditingNotes)
        assertEquals(incomingRevision, incomingStore.revision, "The incoming deck must stay untouched")
        assertEquals(incomingNote, incomingStore.document.slides.first().beats[0].notes)
        assertEquals(outgoingRevision + 1, outgoingStore.revision)
    }

    @Test
    fun notesEditingIsUnavailableWhileADeckTransitionIsPending() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        var blocked by mutableStateOf(false)
        val mounted = mountPresenter(store, notesBlocked = { blocked })
        waitForIdle()
        runOnIdle { clearNotes(store, 0) }
        waitForIdle()
        val controller = mounted().window.controller
        assertTrue(controller.canEditNotes)
        onNodeWithTag("ciacola-presenter-notes-add").assertIsDisplayed()

        runOnIdle { blocked = true }
        waitForIdle()
        runOnIdle {
            assertFalse(controller.canEditNotes, "No note edits while a deck switch/quit is in flight")
            assertFalse(controller.isNotesReadOnly, "A pending transition is not a read-only deck")
            assertFalse(controller.beginEditingNotes())
            assertFalse(controller.isEditingNotes)
        }
        assertTrue(onAllNodesWithTag("ciacola-presenter-notes-add").fetchSemanticsNodes().isEmpty())
        assertTrue(
            onAllNodesWithText("This deck's notes are read-only.", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "A pending transition must never claim the deck is read-only",
        )

        // A cancelled close guard unblocks editing immediately.
        runOnIdle { blocked = false }
        waitForIdle()
        runOnIdle { assertTrue(controller.canEditNotes) }
        onNodeWithTag("ciacola-presenter-notes-add").assertIsDisplayed()
    }

    @Test
    fun editButtonSaysAddBuildNotesWhenOnlySlideContextShows() {
        fun notes(beat: Int, beatNote: String?, slideNotes: String) = dev.sebastiano.ciacola.presenter.ResolvedSpeakerNotes(
            sceneIndex = 0, sceneTitle = "", beatIndex = beat, baseBeatIndex = beat, isVirtualHoldBeat = false,
            slideNotes = slideNotes, beatNote = beatNote,
        )
        assertEquals("Add build 2 notes", presenterNotesEditLabel(notes(1, null, "Context for the slide")))
        assertEquals("Edit", presenterNotesEditLabel(notes(1, "Build two", "Context for the slide")))
        assertEquals("Edit", presenterNotesEditLabel(notes(0, "Opening", "Opening")))
    }

    @Test
    fun clickerKeysDriveTheDeckFromThePresenterWindow() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val mounted = mountPresenter(store)
        waitForIdle()
        val dashboard = onNodeWithTag("ciacola-presenter-dashboard")
        val start = mounted().animatic.targetIndex

        dashboard.performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        val afterSpace = mounted().animatic.targetIndex
        assertTrue(afterSpace > start, "Space must advance ($start -> $afterSpace)")

        dashboard.performKeyInput { pressKey(Key.DirectionLeft) }
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertEquals(start, mounted().animatic.targetIndex, "Left must step back")

        dashboard.performKeyInput { pressKey(Key.PageDown) }
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertTrue(mounted().animatic.targetIndex > start, "PageDown (clicker forward) must advance")

        dashboard.performKeyInput { pressKey(Key.PageUp) }
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertEquals(start, mounted().animatic.targetIndex, "PageUp (clicker back) must step back")

        dashboard.performKeyInput { pressKey(Key.DirectionRight) }
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        assertTrue(mounted().animatic.targetIndex > start, "Right must advance")

        dashboard.performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.Blackout, mounted().window.controller.curtain)
        dashboard.performKeyInput { pressKey(Key.W) }
        waitForIdle()
        assertEquals(StageCurtain.Whiteout, mounted().window.controller.curtain)

        dashboard.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.G) } }
        waitForIdle()
        assertEquals(JumpDialogHost.Presenter, mounted().window.controller.jumpDialogHost)
        assertTrue(Storyboard.Index(0, 0) <= mounted().animatic.targetIndex)
    }
}
