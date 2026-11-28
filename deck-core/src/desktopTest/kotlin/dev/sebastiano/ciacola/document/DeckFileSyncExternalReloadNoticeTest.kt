package dev.sebastiano.ciacola.document

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifies [ExternalReloadNotice] surfacing, expiry on newer edits/saves, and rollback via
 * [DeckFileSync.keepStudioVersionAfterExternalReload].
 */
@OptIn(ExperimentalPathApi::class)
class DeckFileSyncExternalReloadNoticeTest {

    private fun makeDoc(title: String, headline: String = title): DeckDocument = DeckDocument(
        id = "sync-test",
        title = title,
        slides = listOf(
            SlideSpec(
                id = "s0",
                beats = listOf(BeatSpec()),
                nodes = listOf(
                    NodeSpec(id = "h", text = TextPayload(value = RichTextValue.Plain(headline))),
                ),
            ),
        ),
    )

    private fun writeCanonicalAtomic(target: Path, doc: DeckDocument) {
        val tmp = target.parent.resolve(".ext-${System.nanoTime()}.tmp")
        Files.writeString(tmp, CanonicalJson.print(doc), StandardCharsets.UTF_8)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Test
    fun externalReloadWhileHoldingUndoHistorySurfacesNoticeAndSupportsKeepStudioOrDismiss() = runBlocking {
        val dir = Files.createTempDirectory("external-reload-undo-notice")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
            val store = DeckDocumentStore()
            val sync = DeckFileSync(
                targetFile = target,
                recoveryRootDir = dir.resolve("rec"),
                store = store,
            )
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.edit { makeDoc("V2") })
                assertEquals(SyncState.Saved, sync.flushNow())
                assertTrue(store.canUndo && !store.isDirty)

                writeCanonicalAtomic(target, makeDoc("V3-Disk"))
                sync.pollExternalChangesNow()

                assertEquals("V3-Disk", store.document.title)
                val notice1 = sync.externalReloadNotice
                assertNotNull(notice1)
                assertEquals("V2", notice1.previousDocument.title)
                assertEquals(store.revision, notice1.reloadedAtRevision)
                sync.clearExternalReloadNotice()
                assertEquals(null, sync.externalReloadNotice)
                assertEquals("V3-Disk", store.document.title)

                writeCanonicalAtomic(target, makeDoc("V4-Disk"))
                sync.pollExternalChangesNow()
                assertEquals("V4-Disk", store.document.title)
                assertEquals("V3-Disk", sync.externalReloadNotice?.previousDocument?.title)

                assertEquals(SyncState.Saved, sync.keepStudioVersionAfterExternalReload())
                assertEquals(null, sync.externalReloadNotice)
                assertEquals("V3-Disk", store.document.title)
                assertEquals(CanonicalJson.print(makeDoc("V3-Disk")), Files.readString(target))

                // A subsequent local edit expires an older reload notice so Keep Studio cannot clobber it.
                writeCanonicalAtomic(target, makeDoc("V5-Disk"))
                sync.pollExternalChangesNow()
                assertNotNull(sync.externalReloadNotice)
                assertTrue(store.edit { makeDoc("V6-NewLocalEdit") })
                assertEquals(SyncState.Dirty, sync.keepStudioVersionAfterExternalReload())
                assertEquals(null, sync.externalReloadNotice)
                assertEquals("V6-NewLocalEdit", store.document.title)
                assertEquals(SyncState.Saved, sync.flushNow())
                assertEquals(null, sync.externalReloadNotice)

                // An open gesture transaction also blocks Keep Studio atomically without invalidating the drag.
                writeCanonicalAtomic(target, makeDoc("V7-Disk"))
                sync.pollExternalChangesNow()
                val noticeAtV7 = sync.externalReloadNotice
                assertNotNull(noticeAtV7)
                val dragTx = assertNotNull(store.begin("Live drag"))
                dragTx.update { makeDoc("V8-DragPreview") }
                assertEquals(
                    null,
                    store.applyExternalChangeIfClean(
                        newDocument = noticeAtV7.previousDocument,
                        conflictOnDirty = null,
                        label = "Keep Studio Version",
                        expectedRevision = noticeAtV7.reloadedAtRevision,
                        markAsSaved = false,
                    ),
                )
                assertEquals(SyncState.Saved, sync.keepStudioVersionAfterExternalReload())
                assertEquals(null, sync.externalReloadNotice)
                assertTrue(store.hasOpenTransaction)
                assertTrue(dragTx.commit())
                assertEquals("V8-DragPreview", store.document.title)
                assertEquals(CanonicalJson.print(makeDoc("V7-Disk")), Files.readString(target))
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sameSizeAtomicReplaceWithinOneTimestampTickIsStillDetected() = runBlocking {
        val dir = Files.createTempDirectory("external-reload-same-tick")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
            // Without file keys (some Windows filesystems) only size and mtime are available.
            if (Files.readAttributes(target, BasicFileAttributes::class.java).fileKey() == null) return@runBlocking
            val store = DeckDocumentStore()
            val sync = DeckFileSync(targetFile = target, recoveryRootDir = dir.resolve("rec"), store = store)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                val tick = Files.getLastModifiedTime(target)

                // Same byte length and the same modification time: only the file identity changes.
                writeCanonicalAtomic(target, makeDoc("V2"))
                Files.setLastModifiedTime(target, tick)
                assertEquals(Files.size(target), CanonicalJson.print(makeDoc("V1")).toByteArray().size.toLong())
                sync.pollExternalChangesNow()
                assertEquals("V2", store.document.title)

                writeCanonicalAtomic(target, makeDoc("V3"))
                Files.setLastModifiedTime(target, tick)
                sync.pollExternalChangesNow()
                assertEquals("V3", store.document.title)
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
