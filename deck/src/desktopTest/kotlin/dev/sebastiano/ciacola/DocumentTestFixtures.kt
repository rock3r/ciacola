package dev.sebastiano.ciacola

import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.decks.DocumentDeckRegistration
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.timeline.DeckTimelineSnapshot
import dev.sebastiano.ciacola.timeline.DocumentTimelinePlannerCache
import dev.sebastiano.ciacola.timeline.DocumentTimelineSnapshot

fun loadTestDeckDocument(entry: DeckEntry = DeckCatalog.foundation): DeckDocument =
    loadClasspathDeckDocument(entry)

fun loadFoundationTestDocument(): DeckDocument =
    loadClasspathDeckDocument(DeckCatalog.foundation)

fun createLoadedTestDeckStore(
    document: DeckDocument = loadFoundationTestDocument(),
): DeckDocumentStore = DeckDocumentStore(
    initialDocument = document,
    initialLoad = DeckLoadState.Loaded,
)

fun buildFoundationTestStoryboard(
    document: DeckDocument = loadFoundationTestDocument(),
    store: DeckDocumentStore = createLoadedTestDeckStore(document),
    themeName: String = "dark",
    reducedMotion: Boolean = false,
    fontScale: Float = 1f,
    stageDecorator: ContentDecorator = ContentDecorator.None,
    freeClockMillis: () -> Long = { 0L },
    masterPlayheadMillis: () -> Long? = { null },
    specimens: DeckSpecimenRegistry = AllDocumentDecks.foundation.specimens,
    session: RenderSession = RenderSession.forPresent(
        store = store,
        freeClockMillis = freeClockMillis,
        masterPlayheadMillis = masterPlayheadMillis,
    ),
): Storyboard = buildDocumentStoryboard(
    session = session,
    config = DocumentRenderConfig(
        themeName = themeName,
        specimens = specimens,
        reducedMotion = reducedMotion,
        fontScale = fontScale,
        stageDecorator = stageDecorator,
    ),
    title = document.title.ifBlank { "Ciacola" },
)

fun indexFoundationDocumentTimeline(
    document: DeckDocument = loadFoundationTestDocument(),
    themeName: String = "dark",
    reducedMotion: Boolean = false,
    specimens: DeckSpecimenRegistry = AllDocumentDecks.foundation.specimens,
): DocumentTimelineSnapshot = DocumentTimelinePlannerCache().buildSnapshot(
    document = document,
    themeName = themeName,
    reducedMotion = reducedMotion,
    specimens = specimens,
)

fun indexFoundationTimelineSnapshot(
    document: DeckDocument = loadFoundationTestDocument(),
    themeName: String = "dark",
    reducedMotion: Boolean = false,
): DeckTimelineSnapshot = indexFoundationDocumentTimeline(
    document = document,
    themeName = themeName,
    reducedMotion = reducedMotion,
).snapshot

fun loadAllTestDeckRegistrations(): List<Pair<DocumentDeckRegistration, DeckDocument>> =
    AllDocumentDecks.entries.map { reg ->
        reg to loadClasspathDeckDocument(DeckCatalog.byId(reg.id))
    }
