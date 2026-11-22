package dev.sebastiano.ciacola.document

import androidx.compose.ui.text.AnnotatedString
import dev.bnorm.storyboard.text.magic.MagicTextDiff
import dev.bnorm.storyboard.text.magic.MorphBy as MagicMorphBy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Regression tests for [DeckDocumentStore] transactions/validation, [DeckFileSync] conflict and
 * recovery invariants, codec error handling, and text-diff fragment addressing.
 */
@OptIn(ExperimentalPathApi::class)
class DocumentStoreAndSyncRegressionTest {

    private fun makeDoc(title: String, id: String = "sync-test"): DeckDocument = DeckDocument(
        id = id,
        title = title,
        slides = listOf(
            SlideSpec(
                id = "s0",
                beats = listOf(BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "h",
                        text = TextPayload(value = RichTextValue.Plain(title)),
                    ),
                ),
            ),
        ),
    )

    private fun writeCanonicalAtomic(target: Path, doc: DeckDocument) {
        val tmp = target.parent.resolve(".ext-${System.nanoTime()}.tmp")
        Files.writeString(tmp, CanonicalJson.print(doc))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Test
    fun pollAfterAfterExchangeFailureStaysWriteFailedAndDoesNotReportFalseConflict(): Unit = runBlocking {
        val dir = Files.createTempDirectory("after-exchange-poll-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
            var failMove = true
            val ops = object : AtomicFileOps by NativeAtomicFileOps {
                override fun atomicMove(from: Path, to: Path) {
                    if (failMove) throw IOException("quarantine move failed")
                    NativeAtomicFileOps.atomicMove(from, to)
                }
            }
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store, fileOps = ops)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.edit { makeDoc("V2") })
                val afterFlush = assertIs<SyncState.WriteFailed>(sync.flushNow())
                assertIs<WriteFailure.AfterExchange>(afterFlush.failure)

                val afterPoll = sync.pollExternalChangesNow()
                val stillFailed = assertIs<SyncState.WriteFailed>(
                    afterPoll,
                    "Polling after AfterExchange failure must NOT misreport Studio's own exchanged bytes as Conflict(Unwritten); got $afterPoll",
                )
                assertIs<WriteFailure.AfterExchange>(stillFailed.failure)

                failMove = false
                sync.retryUnresolvedTransaction()
                assertEquals(SyncState.Saved, store.sync)
                assertFalse(store.isDirty)
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun flushAndCloseDuringOpenTransactionNeverPersistPreview(): Unit = runBlocking {
        val dir = Files.createTempDirectory("open-tx-flush-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.edit { makeDoc("V2") })

                val tx = assertNotNull(store.begin("drag"))
                tx.update { makeDoc("PREVIEW") }
                assertEquals("PREVIEW", store.document.title, "Stage preview must still reflect live transaction")

                val closeResult = sync.flushOnClose()
                assertEquals(CloseFlushResult.Clean, closeResult)

                val diskText = Files.readString(target)
                assertFalse(diskText.contains("PREVIEW"), "Uncommitted transaction preview must NEVER be written to disk")
                assertTrue(diskText.contains("V2"), "Committed V2 edits must be persisted")

                tx.cancel()
                assertEquals("V2", store.document.title)
                assertFalse(store.isDirty)
                assertEquals(SyncState.Saved, store.sync)
                assertEquals(CanonicalJson.print(store.document), Files.readString(target))
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun closeWhileDirtyAndReadOnlyIsHeldNotClean(): Unit = runBlocking {
        val dir = Files.createTempDirectory("dirty-readonly-close-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
            var unsupported = false
            val ops = object : AtomicFileOps by NativeAtomicFileOps {
                override fun exchange(pathA: Path, pathB: Path): AtomicExchangeResult =
                    if (unsupported && !pathA.fileName.toString().startsWith(".exchange-probe")) {
                        AtomicExchangeResult.Unsupported("filesystem remounted read-only")
                    } else {
                        NativeAtomicFileOps.exchange(pathA, pathB)
                    }
            }
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store, fileOps = ops)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                unsupported = true
                assertTrue(store.edit { makeDoc("V2-unsaved") })

                val closeResult = sync.flushOnClose()
                assertIs<SyncState.ReadOnly>(store.sync)
                assertTrue(store.isDirty)
                assertNotEquals<CloseFlushResult>(
                    CloseFlushResult.Clean,
                    closeResult,
                    "flushOnClose must NEVER return Clean when unsaved edits remain in memory",
                )
                assertIs<CloseFlushResult.HeldWriteFailed>(closeResult)
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun editWithLoadErrorIsRejectedAndNeverBricksFile(): Unit = runBlocking {
        val dir = Files.createTempDirectory("load-error-edit-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())

                val ok = store.edit("Duplicate slide") { d -> d.copy(slides = d.slides + d.slides) }
                assertFalse(ok, "Edit introducing a LoadError (duplicate slide ID) must be rejected")
                assertFalse(store.isDirty, "Rejected edit must not mark store dirty")
                assertEquals(1, store.document.slides.size)

                assertEquals(SyncState.Saved, sync.flushNow())
            } finally {
                sync.close()
            }

            val store2 = DeckDocumentStore()
            val sync2 = DeckFileSync(target, dir.resolve("rec"), store2)
            try {
                assertEquals(DeckLoadState.Loaded, sync2.open(), "Deck on disk must remain cleanly loadable")
            } finally {
                sync2.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepMineDetectsFreshExternalReplacementWhileUnwrittenBannerIsOpen(): Unit = runBlocking {
        val dir = Files.createTempDirectory("keep-mine-unseen-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1-base")))
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.edit { makeDoc("V2-mine") })

                // External tool writes V2-theirs -> Unwritten conflict banner opens with theirsHash = H(V2-theirs).
                writeCanonicalAtomic(target, makeDoc("V2-theirs"))
                val conflict1 = assertIs<SyncState.Conflict>(sync.flushNow())
                assertEquals(ConflictKind.Unwritten, conflict1.info.kind)
                val hashTheirs = DeckFileSync.sha256Hex(CanonicalJson.print(makeDoc("V2-theirs")).toByteArray())
                assertEquals(hashTheirs, conflict1.info.theirsHash)

                // While the Unwritten conflict banner is open, external tool writes V3-unseen!
                val unseenBytes = CanonicalJson.print(makeDoc("V3-unseen"))
                writeCanonicalAtomic(target, makeDoc("V3-unseen"))
                val hashUnseen = DeckFileSync.sha256Hex(unseenBytes.toByteArray())

                // User clicks Keep mine thinking they are overwriting V2-theirs.
                // Step 2 MUST catch V3-unseen as a fresh Unwritten conflict and NEVER overwrite it!
                val afterKeep = assertIs<SyncState.Conflict>(
                    sync.keepMine(),
                    "keepMine() must catch a newer external replacement as a fresh Unwritten conflict",
                )
                assertEquals(ConflictKind.Unwritten, afterKeep.info.kind)
                assertEquals(hashUnseen, afterKeep.info.theirsHash)
                assertEquals(unseenBytes, Files.readString(target), "Unseen V3 must remain untouched on disk")
                assertTrue(
                    sync.listQuarantineEntries(includeDismissed = true).none { it.kind == QuarantineKind.Base },
                    "Unseen V3 must NEVER be filed as .base",
                )
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inSessionRecoveryDoesNotAdoptUnseenExternalReplacementAsDiskHash(): Unit = runBlocking {
        val dir = Files.createTempDirectory("recover-insession-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1-base")))
            var failQuarantineMove = true
            val ops = object : AtomicFileOps by NativeAtomicFileOps {
                override fun atomicMove(from: Path, to: Path) {
                    if (failQuarantineMove) throw IOException("quarantine move failed")
                    NativeAtomicFileOps.atomicMove(from, to)
                }
            }
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store, fileOps = ops)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.edit { makeDoc("V2-studio") })
                assertIs<SyncState.WriteFailed>(sync.flushNow())
                assertTrue(store.edit { makeDoc("V3-studio-dirty") })

                // External tool replaces target with V3-external BEFORE user clicks Retry!
                val externalText = CanonicalJson.print(makeDoc("V3-external"))
                writeCanonicalAtomic(target, makeDoc("V3-external"))

                failQuarantineMove = false
                sync.retryUnresolvedTransaction()

                // A subsequent flush MUST NOT treat V3-external as expected base and overwrite it!
                val afterFlush = sync.flushNow()
                val conflict = assertIs<SyncState.Conflict>(
                    afterFlush,
                    "In-session recovery must NOT adopt unseen external target as diskHash; got $afterFlush",
                )
                assertEquals(ConflictKind.Unwritten, conflict.info.kind)
                assertEquals(externalText, Files.readString(target), "V3-external must remain untouched on disk")
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backgroundPollDoesNotClearOpenOverwroteConflict(): Unit = runBlocking {
        val dir = Files.createTempDirectory("overwrote-poll-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("Base")))
            var injectRace = true
            val store = DeckDocumentStore()
            val sync = DeckFileSync(
                targetFile = target,
                recoveryRootDir = dir.resolve("rec"),
                store = store,
                stepHook = { step ->
                    if (injectRace && step == SyncStep.WriteAfterCheck) {
                        injectRace = false
                        writeCanonicalAtomic(target, makeDoc("Theirs-1"))
                    }
                },
            )
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.edit { makeDoc("Mine") })
                val conflict = assertIs<SyncState.Conflict>(sync.flushNow())
                assertEquals(ConflictKind.Overwrote, conflict.info.kind)
                assertFalse(store.isDirty)

                // Another external edit happens while Overwrote banner is still open.
                writeCanonicalAtomic(target, makeDoc("Theirs-2"))
                val afterPoll = sync.pollExternalChangesNow()
                val stillConflict = assertIs<SyncState.Conflict>(
                    afterPoll,
                    "Polling must NEVER silently clear an open Conflict banner; got $afterPoll",
                )
                assertEquals(ConflictKind.Overwrote, stillConflict.info.kind)
                assertEquals(conflict.info.theirsPath, stillConflict.info.theirsPath)
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidToValidReloadAcquiresLockProbesExchangeAndRunsRecovery(): Unit = runBlocking {
        val dir = Files.createTempDirectory("invalid-to-valid-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, "{ broken json")

            var probeCalls = 0
            val countingOps = object : AtomicFileOps by NativeAtomicFileOps {
                override fun probeExchangeSupport(syncDir: Path): AtomicExchangeResult {
                    probeCalls++
                    return NativeAtomicFileOps.probeExchangeSupport(syncDir)
                }
            }
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store, fileOps = countingOps)
            try {
                assertIs<DeckLoadState.Invalid>(sync.open())
                assertEquals(0, probeCalls)

                // Fix the file externally -> poll must acquire lock, probe exchange, and create sync dirs.
                writeCanonicalAtomic(target, makeDoc("Fixed"))
                sync.pollExternalChangesNow()

                assertEquals(DeckLoadState.Loaded, store.load)
                assertEquals(SyncState.Saved, store.sync)
                assertTrue(probeCalls >= 1, "Invalid -> Valid reload must run probeExchangeSupport")
                assertTrue(Files.isDirectory(sync.syncDir), "Invalid -> Valid reload must create sync dir")
                assertTrue(Files.isDirectory(sync.quarantineDir), "Invalid -> Valid reload must create quarantine dir")

                // Verify a second Studio instance sees the lock and opens ReadOnly!
                val store2 = DeckDocumentStore()
                val sync2 = DeckFileSync(target, dir.resolve("rec2"), store2)
                try {
                    assertEquals(DeckLoadState.Loaded, sync2.open())
                    assertIs<SyncState.ReadOnly>(
                        store2.sync,
                        "First instance must hold S/lock after Invalid -> Valid transition",
                    )
                } finally {
                    sync2.close()
                }
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedCustomSerializerPayloadReturnsFailureInsteadOfThrowing() {
        val badBezier = """
            {
              "format": 1,
              "id": "bad-curve",
              "slides": [
                {
                  "id": "s0",
                  "beats": [{}],
                  "nodes": [
                    {
                      "id": "n0",
                      "text": { "value": "hi" },
                      "builds": [
                        { "kind": "enter", "beat": 0, "effect": "fade", "curve": { "bezier": 5 } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val decoded = DeckDocumentCodec.decode(badBezier)
        val failure = assertIs<DeckDecodeResult.Failure>(
            decoded,
            "Malformed custom-serializer payload must return DeckDecodeResult.Failure, not throw",
        )
        assertTrue(failure.errors.isNotEmpty())
        assertTrue(failure.errors.all { it.severity == IssueSeverity.LoadError })
    }

    @Test
    fun deckFileSyncRunsDiskIoOffCallerThread(): Unit = runBlocking {
        val dir = Files.createTempDirectory("sync-io-thread-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))

            val callerThread = Thread.currentThread()
            val probeThread = AtomicReference<Thread?>()
            val writeThread = AtomicReference<Thread?>()
            val ops = object : AtomicFileOps by NativeAtomicFileOps {
                override fun probeExchangeSupport(syncDir: Path): AtomicExchangeResult {
                    probeThread.set(Thread.currentThread())
                    return NativeAtomicFileOps.probeExchangeSupport(syncDir)
                }

                override fun writeFileAndFsync(path: Path, bytes: ByteArray) {
                    writeThread.set(Thread.currentThread())
                    NativeAtomicFileOps.writeFileAndFsync(path, bytes)
                }
            }
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store, fileOps = ops)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertNotNull(probeThread.get())
                assertNotEquals(callerThread, probeThread.get(), "open() I/O must not run on caller/UI thread")

                assertTrue(store.edit { makeDoc("V2") })
                assertEquals(SyncState.Saved, sync.flushNow())
                assertNotNull(writeThread.get())
                assertNotEquals(callerThread, writeThread.get(), "flushNow() I/O must not run on caller/UI thread")
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storeValidationUsesRealSpecimenRegistryAfterEdit(): Unit = runBlocking {
        val dir = Files.createTempDirectory("specimen-registry-regression")
        try {
            val specimens = deckSpecimens {
                specimen("custom-widget", "Custom Widget") {
                    float("gain", default = 0.5f, range = 0f..1f)
                    content {}
                }
            }
            val doc = DeckDocument(
                id = "spec-doc",
                title = "Spec",
                slides = listOf(
                    SlideSpec(
                        id = "s0",
                        beats = listOf(BeatSpec()),
                        nodes = listOf(
                            NodeSpec(
                                id = "w0",
                                frame = FrameSpec(x = 0.0, y = 0.0, w = 200.0, h = 100.0),
                                specimen = SpecimenPayload(ref = "custom-widget"),
                            ),
                        ),
                    ),
                ),
            )
            val target = dir.resolve("spec.deck.json")
            Files.writeString(target, CanonicalJson.print(doc))

            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store, specimenRegistry = specimens)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.lintIssues.isEmpty(), "Initial load must have no UNKNOWN_SPECIMEN_REF")

                assertTrue(store.edit { it.copy(title = "Spec Edited") })
                assertTrue(
                    store.lintIssues.none { it.code == "UNKNOWN_SPECIMEN_REF" },
                    "Post-edit validation must use the deck's real specimen registry; got ${store.lintIssues}",
                )
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun exitFragmentAddressUsesSourceOccurrenceIndexForRepeatedTokens() {
        val from = AnnotatedString("a shader is a shader")
        val to = AnnotatedString("a shader is great")
        val plan = MagicTextDiff.plan(from, to, MagicMorphBy.Word)
        val addresses = MagicTextDiff.fragmentAddresses(from, to, plan)

        val exitingShaderIdx = plan.indexOfFirst { m ->
            m.before != null && m.after == null &&
                from.text.substring(m.before!!.start, m.before!!.end) == "shader"
        }
        assertTrue(exitingShaderIdx >= 0, "Expected one exiting 'shader' token in $plan")
        val exitingRange = plan[exitingShaderIdx].before!!
        val expectedOcc = TextTokenizer.tokenize(from.text, MorphBy.Word)
            .first { it.start == exitingRange.start && it.endExclusive == exitingRange.end }
            .occurrence
        assertEquals(
            "-shader#$expectedOcc",
            addresses[exitingShaderIdx],
            "Exiting repeated token address must use source occurrence index across ALL source tokens, not just exiting tokens",
        )
        assertEquals("-shader#1", addresses[exitingShaderIdx])
    }

    @Test
    fun committedSnapshotCancelOpenTransactionAndTakeTheirsInvalidatePreview(): Unit = runBlocking {
        val dir = Files.createTempDirectory("committed-snapshot-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
            val store = DeckDocumentStore()
            val sync = DeckFileSync(target, dir.resolve("rec"), store)
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertTrue(store.edit { makeDoc("V2-committed") })

                val tx = assertNotNull(store.begin("drag"))
                tx.update { makeDoc("V2-preview") }
                assertEquals("V2-preview", store.document.title)
                val (committedDoc, committedRev) = store.committedSnapshot()
                assertEquals("V2-committed", committedDoc.title)
                assertEquals(store.revision, committedRev)

                // cancelOpenTransaction reverts live preview cleanly
                store.cancelOpenTransaction()
                assertFalse(store.hasOpenTransaction)
                assertEquals("V2-committed", store.document.title)

                // Open a new transaction while an Unwritten conflict triggers -> takeTheirs must
                // save recovery copy of V2-committed (NOT preview) AND invalidate stale tx.cancel()!
                val tx2 = assertNotNull(store.begin("drag-2"))
                tx2.update { makeDoc("STALE-PREVIEW") }
                writeCanonicalAtomic(target, makeDoc("V3-theirs"))
                val conflict = assertIs<SyncState.Conflict>(sync.flushNow())
                assertEquals(ConflictKind.Unwritten, conflict.info.kind)

                val recPath = sync.takeTheirs().getOrThrow()
                val recText = Files.readString(recPath)
                assertTrue(recText.contains("V2-committed"), "Recovery copy must contain committed doc, not preview")
                assertFalse(recText.contains("STALE-PREVIEW"))
                assertEquals("V3-theirs", store.document.title)
                assertFalse(store.hasOpenTransaction)

                // Late stale tx2.cancel() MUST NOT revert V3-theirs back to V2-committed!
                tx2.cancel()
                assertEquals("V3-theirs", store.document.title)
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inSessionRecoveryCleanStoreReloadsExternalTargetImmediatelyAndOpenTransactionConflicts(): Unit = runBlocking {
        val dir = Files.createTempDirectory("recover-clean-and-tx-regression")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1-base")))
            val store = DeckDocumentStore()
            val sync = DeckFileSync(
                targetFile = target,
                recoveryRootDir = dir.resolve("rec"),
                store = store,
            )
            try {
                assertEquals(DeckLoadState.Loaded, sync.open())
                assertFalse(store.isDirty)
                assertFalse(store.hasOpenTransaction)

                // Simulate an unresolved WriteFailed state while store is clean (!store.isDirty && !store.hasOpenTransaction),
                // and an external tool replaces target with V3-external BEFORE user clicks Retry!
                store.setSyncState(SyncState.WriteFailed(WriteFailure.BeforeExchange("transient failure")))
                writeCanonicalAtomic(target, makeDoc("V3-external"))

                // Retry runs recoverLocked(isOnOpen = false) on a clean store -> MUST immediately reload V3-external!
                val afterRetry = sync.retryUnresolvedTransaction()
                assertEquals(SyncState.Saved, afterRetry)
                assertEquals(
                    "V3-external",
                    store.document.title,
                    "Clean in-session recovery must immediately reload external target instead of marking stale V1-base as Saved",
                )
                assertFalse(store.isDirty)

                // Now test open transaction (!store.isDirty && store.hasOpenTransaction) during in-session recovery:
                val openTx = assertNotNull(store.begin("live-drag"))
                openTx.update { makeDoc("V4-dragging") }
                assertFalse(store.isDirty)
                assertTrue(store.hasOpenTransaction)
                store.setSyncState(SyncState.WriteFailed(WriteFailure.BeforeExchange("transient failure during drag")))

                val v5ExternalText = CanonicalJson.print(makeDoc("V5-external"))
                writeCanonicalAtomic(target, makeDoc("V5-external"))

                val conflictState = assertIs<SyncState.Conflict>(
                    sync.retryUnresolvedTransaction(),
                    "In-session recovery with an open transaction must surface Unwritten conflict and never adopt or overwrite V5-external",
                )
                assertEquals(ConflictKind.Unwritten, conflictState.info.kind)
                assertEquals(v5ExternalText, Files.readString(target))
                store.cancelOpenTransaction()
            } finally {
                sync.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepMineOnOverwroteConflictPersistsResolutionAcrossReopenWhileRetainingQuarantine(): Unit = runBlocking {
        val dir = Files.createTempDirectory("keep-mine-overwrote-persist")
        try {
            val target = dir.resolve("deck.deck.json")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1-base")))
            val store1 = DeckDocumentStore()
            var injectedExternal = false
            val sync1 = DeckFileSync(
                targetFile = target,
                recoveryRootDir = dir.resolve("rec"),
                store = store1,
                stepHook = { step ->
                    // Replace target right after the pre-exchange check so atomic exchange displaces it -> Overwrote.
                    if (step == SyncStep.WriteAfterCheck && !injectedExternal) {
                        injectedExternal = true
                        writeCanonicalAtomic(target, makeDoc("V2-external-race"))
                    }
                },
            )
            val quarantinedTheirsPath: Path
            try {
                assertEquals(DeckLoadState.Loaded, sync1.open())
                assertTrue(store1.edit { makeDoc("V2-mine") })

                val conflict = assertIs<SyncState.Conflict>(sync1.flushNow())
                assertEquals(ConflictKind.Overwrote, conflict.info.kind)
                quarantinedTheirsPath = Path.of(conflict.info.theirsPath)
                assertTrue(Files.isRegularFile(quarantinedTheirsPath))

                // User explicitly resolves by choosing Keep mine, then closes without further edits.
                assertEquals(SyncState.Saved, sync1.keepMine())
                assertEquals(CloseFlushResult.Clean, sync1.flushOnClose())

                // Quarantined .theirs bytes remain preserved and undismissed for recovery.
                assertTrue(Files.isRegularFile(quarantinedTheirsPath))
                val entries1 = sync1.listQuarantineEntries()
                assertTrue(
                    entries1.any { it.path == quarantinedTheirsPath && !it.dismissed },
                    "keepMine must retain quarantined .theirs bytes for recovery: $entries1",
                )
            } finally {
                sync1.close()
            }

            // Reopening must NOT recreate the already-resolved Overwrote conflict or block closing.
            val store2 = DeckDocumentStore()
            val sync2 = DeckFileSync(target, dir.resolve("rec"), store2)
            try {
                assertEquals(DeckLoadState.Loaded, sync2.open())
                assertEquals(SyncState.Saved, store2.sync, "Resolved Overwrote conflict must not return on reopen")
                assertEquals("V2-mine", store2.document.title)
                assertEquals(CloseFlushResult.Clean, sync2.flushOnClose(), "Reopened clean deck must close cleanly")
                assertTrue(Files.isRegularFile(quarantinedTheirsPath), "Quarantined .theirs file must remain on disk")
                assertTrue(
                    sync2.listQuarantineEntries().any { it.path == quarantinedTheirsPath && !it.dismissed },
                    "Quarantined .theirs entry must remain available for recovery after reopen",
                )
            } finally {
                sync2.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun externalReloadAtomicallyConflictsWhenEditOrTransactionIntervenes(): Unit = runBlocking {
        val dir = Files.createTempDirectory("external-reload-race-guard")
        try {
            // 1. Intervening committed edit right before external apply must NOT be overwritten; surfaces Unwritten conflict.
            val target1 = dir.resolve("deck1.deck.json")
            Files.writeString(target1, CanonicalJson.print(makeDoc("V1-base")))
            val store1 = DeckDocumentStore()
            var injectedEdit = false
            val sync1 = DeckFileSync(
                targetFile = target1,
                recoveryRootDir = dir.resolve("rec1"),
                store = store1,
                stepHook = { step ->
                    if (step == SyncStep.PollBeforeApplyExternal && !injectedEdit) {
                        injectedEdit = true
                        assertTrue(store1.edit("Intervening edit") { makeDoc("V2-local-edit") })
                    }
                },
            )
            try {
                assertEquals(DeckLoadState.Loaded, sync1.open())
                writeCanonicalAtomic(target1, makeDoc("V2-external"))
                sync1.pollExternalChangesNow()

                assertEquals("V2-local-edit", store1.document.title, "Intervening local edit must survive")
                assertTrue(store1.isDirty)
                val conflict1 = assertIs<SyncState.Conflict>(store1.sync)
                assertEquals(ConflictKind.Unwritten, conflict1.info.kind)
                assertEquals(
                    DeckFileSync.sha256Hex(Files.readAllBytes(target1)),
                    conflict1.info.observedTheirsHash,
                )
            } finally {
                sync1.close()
            }

            // 2. Intervening active gesture transaction right before external apply must stay active and intact.
            val target2 = dir.resolve("deck2.deck.json")
            Files.writeString(target2, CanonicalJson.print(makeDoc("V1-base")))
            val store2 = DeckDocumentStore()
            var openedTx: EditTransaction? = null
            val sync2 = DeckFileSync(
                targetFile = target2,
                recoveryRootDir = dir.resolve("rec2"),
                store = store2,
                stepHook = { step ->
                    if (step == SyncStep.PollBeforeApplyExternal && openedTx == null) {
                        val tx = assertNotNull(store2.begin("Live drag"))
                        tx.update { makeDoc("V2-dragging-preview") }
                        openedTx = tx
                    }
                },
            )
            try {
                assertEquals(DeckLoadState.Loaded, sync2.open())
                writeCanonicalAtomic(target2, makeDoc("V2-external"))
                sync2.pollExternalChangesNow()

                val tx = assertNotNull(openedTx)
                assertTrue(tx.isActive, "In-progress gesture transaction must NOT be cancelled by external poll")
                assertTrue(store2.hasOpenTransaction)
                assertEquals("V2-dragging-preview", store2.document.title)
                val conflict2 = assertIs<SyncState.Conflict>(store2.sync)
                assertEquals(ConflictKind.Unwritten, conflict2.info.kind)

                // Committing the gesture afterwards preserves the user's edit while keeping the conflict banner active.
                assertTrue(tx.commit())
                assertEquals("V2-dragging-preview", store2.document.title)
                assertIs<SyncState.Conflict>(store2.sync)
            } finally {
                sync2.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
