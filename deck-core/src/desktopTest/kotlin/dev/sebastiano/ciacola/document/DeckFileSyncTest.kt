package dev.sebastiano.ciacola.document

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Exhaustive crash-safety, conflict-resolution, and atomic-exchange tests for [DeckFileSync] and [NativeAtomicFileOps].
 */
@OptIn(ExperimentalPathApi::class)
class DeckFileSyncTest {

    private class FakeSyncClock(
        var wallMs: Long = 1_700_000_000_000L,
        var monoMs: Long = 10_000L,
    ) : SyncClock {
        override fun wallTimeMillis(): Long = wallMs
        override fun monotonicTimeMillis(): Long = monoMs

        fun advanceBoth(deltaMs: Long) {
            wallMs += deltaMs
            monoMs += deltaMs
        }
    }

    private class SimulatedCrash(val step: SyncStep) : RuntimeException("Simulated crash at $step")

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

    private fun fileKeyOf(path: Path): Any? =
        Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()

    @Test
    fun nativeAtomicFileOpsSwapsInodesAndRetainedChannelFollowsDisplacedFile() {
        val dir = Files.createTempDirectory("sync-native-ffm")
        try {
            val probe = NativeAtomicFileOps.probeExchangeSupport(dir)
            assertIs<AtomicExchangeResult.Success>(probe, "macOS/APFS or Linux must support atomic exchange")

            val fileA = dir.resolve("a.deck.json")
            val fileB = dir.resolve("b.deck.json")
            Files.writeString(fileA, "VERSION_A")
            Files.writeString(fileB, "VERSION_B")

            val inodeA = fileKeyOf(fileA)
            val inodeB = fileKeyOf(fileB)
            assertNotNull(inodeA)
            assertNotNull(inodeB)
            assertNotEquals(inodeA, inodeB)

            // Open a FileChannel to fileA BEFORE the atomic exchange
            FileChannel.open(fileA, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
                val result = NativeAtomicFileOps.exchange(fileA, fileB)
                assertIs<AtomicExchangeResult.Success>(result)

                // Inodes swapped atomically
                assertEquals(inodeB, fileKeyOf(fileA))
                assertEquals(inodeA, fileKeyOf(fileB))
                assertEquals("VERSION_B", Files.readString(fileA))
                assertEquals("VERSION_A", Files.readString(fileB))

                // Writing through the retained channel writes into fileB (the displaced inode), NOT fileA!
                ch.position(0)
                ch.truncate(0)
                ch.write(ByteBuffer.wrap("LATE_WRITE_FROM_TOOL".toByteArray(StandardCharsets.UTF_8)))
                ch.force(true)

                assertEquals("VERSION_B", Files.readString(fileA), "Target must remain untouched by retained FD")
                assertEquals("LATE_WRITE_FROM_TOOL", Files.readString(fileB), "Retained FD must write into displaced file")
            }

            NativeAtomicFileOps.fsyncDirectory(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pauseAndCrashAtEveryWriteRecoveryAndSweepStepRecoversWithoutDataLoss() = runBlocking {
        val writeSteps = listOf(
            SyncStep.WriteAfterTmp,
            SyncStep.WriteAfterMeta,
            SyncStep.WriteAfterJournal,
            SyncStep.WriteAfterExchange,
            SyncStep.WriteAfterHashDisplaced,
            SyncStep.WriteAfterQuarantine,
            SyncStep.WriteAfterDeleteJournal,
        )

        val docA = makeDoc("Version A")
        val docM = makeDoc("Version M")
        val canonicalA = CanonicalJson.print(docA)
        val canonicalM = CanonicalJson.print(docM)

        for (crashStep in writeSteps) {
            val dir = Files.createTempDirectory("sync-crash-$crashStep")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, canonicalA)

                val clock = FakeSyncClock()
                val store1 = DeckDocumentStore()
                val sync1 = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store1,
                    clock = clock,
                    stepHook = { step -> if (step == crashStep) throw SimulatedCrash(step) },
                )
                sync1.open()
                store1.edit { docM }
                assertFailsWith<SimulatedCrash> { sync1.flushNow() }
                // Simulate hard crash (release lock without flushing)
                sync1.close()

                // Reopen fresh instance and verify recovery
                val store2 = DeckDocumentStore()
                val sync2 = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store2,
                    clock = clock,
                )
                sync2.open()

                assertFalse(Files.exists(sync2.journalFile), "Journal must be cleared after recovery for $crashStep")
                assertTrue(
                    SyncFilesHelper.listStrayTmpFiles(sync2.syncDir).isEmpty(),
                    "No stray .tmp may remain after recovery for $crashStep",
                )

                val targetText = Files.readString(target)
                val qEntries = sync2.listQuarantineEntries(includeDismissed = true)
                val qContents = qEntries.map { Files.readString(it.path) }

                when (crashStep) {
                    SyncStep.WriteAfterTmp, SyncStep.WriteAfterMeta -> {
                        // Crashed before journal: target is still A; stray tmp moved to .unknown
                        assertEquals(canonicalA, targetText)
                        assertEquals("Version A", store2.document.title)
                        assertTrue(qContents.contains(canonicalM), "Unsaved M bytes must be preserved in .unknown at $crashStep")
                    }

                    SyncStep.WriteAfterJournal -> {
                        // Journal exists, exchange did not happen -> tmp moved to .mine, target stays A, notice reported
                        assertEquals(canonicalA, targetText)
                        assertEquals("Version A", store2.document.title)
                        assertTrue(qEntries.any { it.kind == QuarantineKind.Mine && Files.readString(it.path) == canonicalM })
                        assertTrue(sync2.recoveryNotices.any { it.kind == "unsaved_at_crash" })
                    }

                    SyncStep.WriteAfterExchange,
                    SyncStep.WriteAfterHashDisplaced,
                    SyncStep.WriteAfterQuarantine,
                    SyncStep.WriteAfterDeleteJournal,
                    -> {
                        // Exchange completed: target holds M, displaced A is quarantined as .base
                        assertEquals(canonicalM, targetText)
                        assertEquals("Version M", store2.document.title)
                        assertEquals(SyncState.Saved, store2.sync)
                        assertTrue(qEntries.any { it.kind == QuarantineKind.Base && Files.readString(it.path) == canonicalA })
                    }

                    else -> error("Unexpected step $crashStep")
                }

                sync2.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        // Also test crashing inside Recovery steps & Sweep steps
        val recoveryAndSweepSteps = listOf(
            SyncStep.RecoveryBeforeRenameTmp,
            SyncStep.RecoveryAfterRenameTmp,
            SyncStep.RecoveryAfterDeleteJournal,
            SyncStep.SweepBeforeHash,
            SyncStep.SweepAfterHashBeforeDelete,
            SyncStep.SweepAfterDeleteEntryBeforeMeta,
        )
        for (crashStep in recoveryAndSweepSteps) {
            val dir = Files.createTempDirectory("sync-crash-$crashStep")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, canonicalA)
                val clock = FakeSyncClock()

                // First produce either an interrupted exchange (for Recovery*) or a normal save (for Sweep*)
                val store1 = DeckDocumentStore()
                val sync1 = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store1,
                    clock = clock,
                    stepHook = { step ->
                        if (crashStep.name.startsWith("Recovery") && step == SyncStep.WriteAfterExchange) {
                            throw SimulatedCrash(step)
                        }
                    },
                )
                sync1.open()
                store1.edit { docM }
                if (crashStep.name.startsWith("Recovery")) {
                    assertFailsWith<SimulatedCrash> { sync1.flushNow() }
                } else {
                    sync1.flushNow()
                }
                sync1.close()

                // Second instance crashes at the exact recovery or sweep checkpoint
                val store2 = DeckDocumentStore()
                val sync2 = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store2,
                    clock = clock,
                    stepHook = { step -> if (step == crashStep) throw SimulatedCrash(step) },
                )
                if (crashStep.name.startsWith("Recovery")) {
                    assertFailsWith<SimulatedCrash> { sync2.open() }
                } else {
                    sync2.open()
                    clock.advanceBoth(DeckFileSync.DEFAULT_RETENTION_MS + 1_000L)
                    assertFailsWith<SimulatedCrash> { sync2.sweepNow() }
                }
                sync2.close()

                // Third instance finishes cleanly
                val store3 = DeckDocumentStore()
                val sync3 = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store3,
                    clock = clock,
                )
                sync3.open()
                clock.advanceBoth(DeckFileSync.DEFAULT_RETENTION_MS + 1_000L)
                sync3.sweepNow()

                assertEquals(canonicalM, Files.readString(target))
                assertFalse(Files.exists(sync3.journalFile))
                assertTrue(SyncFilesHelper.listStrayTmpFiles(sync3.syncDir).isEmpty())
                if (crashStep.name.startsWith("Sweep")) {
                    // After full sweep of clean .base, both entry and orphan .meta are cleaned up
                    assertTrue(sync3.listQuarantineEntries(includeDismissed = true).isEmpty())
                    val metas = Files.list(sync3.quarantineDir).use { it.toList() }
                    assertTrue(metas.isEmpty(), "Orphan .meta must be deleted after sweep recovery at $crashStep")
                }
                sync3.close()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun recoveryWithTmpAbsentAfterQuarantineAndAfterJournalDeleteRebuildsOverwroteConflict() = runBlocking {
        val docA = makeDoc("Base A")
        val docB = makeDoc("External B")
        val docM = makeDoc("Studio M")

        for (crashAt in listOf(SyncStep.WriteAfterQuarantine, SyncStep.WriteAfterDeleteJournal)) {
            val dir = Files.createTempDirectory("sync-tmp-absent-$crashAt")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(docA))

                val store1 = DeckDocumentStore()
                val sync1 = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store1,
                    stepHook = { step ->
                        if (step == SyncStep.WriteAfterCheck) {
                            // External tool replaces target between check and exchange!
                            writeCanonicalAtomic(target, docB)
                        }
                        if (step == crashAt) throw SimulatedCrash(step)
                    },
                )
                sync1.open()
                store1.edit { docM }
                assertFailsWith<SimulatedCrash> { sync1.flushNow() }
                sync1.close()

                // Reopen: tmp is already absent; journal may even be gone (WriteAfterDeleteJournal),
                // yet rebuildStatusForTxid MUST reconstruct SyncState.Conflict(Overwrote)!
                val store2 = DeckDocumentStore()
                val sync2 = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store2)
                sync2.open()

                val conflict = assertIs<SyncState.Conflict>(store2.sync, "Must rebuild Overwrote after crash at $crashAt")
                assertEquals(ConflictKind.Overwrote, conflict.info.kind)
                assertEquals(CanonicalJson.print(docM), Files.readString(target))
                assertEquals(CanonicalJson.print(docB), Files.readString(Path.of(conflict.info.theirsPath)))
                sync2.close()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun abaRetainedFileDescriptorRenamesPreservingInodeAndCapturesLateWrites() = runBlocking {
        val dir = Files.createTempDirectory("sync-aba-fd")
        try {
            val target = dir.resolve("deck.deck.json")
            val recovery = dir.resolve("recovery")
            val docA = makeDoc("A original")
            val docB = makeDoc("B external")
            val docM = makeDoc("M studio")
            val canonicalA = CanonicalJson.print(docA)
            val canonicalB = CanonicalJson.print(docB)
            Files.writeString(target, canonicalA)

            var externalChannel: FileChannel? = null
            var externalInode: Any? = null

            val store1 = DeckDocumentStore()
            val sync1 = DeckFileSync(
                targetFile = target,
                recoveryRootDir = recovery,
                store = store1,
                stepHook = { step ->
                    when (step) {
                        SyncStep.WriteAfterCheck -> {
                            // Tool 1 installs B and holds open FD to B
                            writeCanonicalAtomic(target, docB)
                            externalInode = fileKeyOf(target)
                            externalChannel = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE)
                        }

                        SyncStep.WriteAfterExchange -> {
                            // Tool 2 restores A at target (ABA!) before Studio quarantines B, then Studio crashes!
                            writeCanonicalAtomic(target, docA)
                            throw SimulatedCrash(step)
                        }

                        else -> Unit
                    }
                },
            )
            sync1.open()
            store1.edit { docM }
            assertFailsWith<SimulatedCrash> { sync1.flushNow() }
            sync1.close()

            try {
                // Reopen: Studio must RENAME tmp (preserving inode, NOT copy!) into Q/<txid>-<ms>.theirs
                val store2 = DeckDocumentStore()
                val sync2 = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store2)
                sync2.open()

                assertEquals(canonicalA, Files.readString(target))
                val theirsEntry = sync2.listQuarantineEntries(includeDismissed = true)
                    .single { it.kind == QuarantineKind.Theirs }
                assertEquals(canonicalB, Files.readString(theirsEntry.path))
                assertEquals(
                    externalInode,
                    fileKeyOf(theirsEntry.path),
                    "Recovery MUST rename (same inode), never copy+delete, so retained FDs stay attached!",
                )
                assertTrue(sync2.recoveryNotices.any { it.kind == "theirs" })

                // Tool 1 now writes through its retained FD -> write lands directly in quarantined .theirs!
                val lateBytes = CanonicalJson.print(makeDoc("B late write")).toByteArray(StandardCharsets.UTF_8)
                externalChannel!!.apply {
                    position(0)
                    truncate(0)
                    write(ByteBuffer.wrap(lateBytes))
                    force(true)
                }
                assertEquals(String(lateBytes, StandardCharsets.UTF_8), Files.readString(theirsEntry.path))
                sync2.close()
            } finally {
                runCatching { externalChannel?.close() }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun retainedFdWritesBeforeAndAfterStep5HashAndAfterJournalIsGone() = runBlocking {
        // Part A: Retained FD writes AFTER exchange, BEFORE step 5 hash -> caught immediately as Overwrote
        run {
            val dir = Files.createTempDirectory("sync-fd-before-hash")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(makeDoc("Initial")))
                val lateText = CanonicalJson.print(makeDoc("Written Before Hash"))

                FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
                    val store = DeckDocumentStore()
                    val sync = DeckFileSync(
                        targetFile = target,
                        recoveryRootDir = recovery,
                        store = store,
                        stepHook = { step ->
                            if (step == SyncStep.WriteAfterExchange) {
                                ch.position(0)
                                ch.truncate(0)
                                ch.write(ByteBuffer.wrap(lateText.toByteArray(StandardCharsets.UTF_8)))
                                ch.force(true)
                            }
                        },
                    )
                    sync.open()
                    store.edit { makeDoc("Studio Edit") }
                    val state = sync.flushNow()
                    val conflict = assertIs<SyncState.Conflict>(state)
                    assertEquals(ConflictKind.Overwrote, conflict.info.kind)
                    assertEquals(lateText, Files.readString(Path.of(conflict.info.theirsPath)))
                    sync.close()
                }
            } finally {
                dir.deleteRecursively()
            }
        }

        // Part B: Retained FD writes AFTER journal is gone (into quarantined .base) -> detected on sweep via .meta!
        run {
            val dir = Files.createTempDirectory("sync-fd-after-journal-gone")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(makeDoc("Initial")))
                val lateText = CanonicalJson.print(makeDoc("Written After Journal Gone"))
                val clock = FakeSyncClock()

                FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
                    val store1 = DeckDocumentStore()
                    val sync1 = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store1, clock = clock)
                    sync1.open()
                    store1.edit { makeDoc("Studio Saved") }
                    assertEquals(SyncState.Saved, sync1.flushNow())
                    assertFalse(Files.exists(sync1.journalFile), "Journal must be deleted after save")
                    assertTrue(sync1.listQuarantineEntries(includeDismissed = true).any { it.kind == QuarantineKind.Base })
                    sync1.close()

                    // Now write through the retained FD while Studio is closed and journal is gone!
                    ch.position(0)
                    ch.truncate(0)
                    ch.write(ByteBuffer.wrap(lateText.toByteArray(StandardCharsets.UTF_8)))
                    ch.force(true)
                }

                // Reopen, advance dual clock past retention, run sweep -> .base re-hashed against Q/<txid>.meta -> Overwrote!
                val store2 = DeckDocumentStore()
                val sync2 = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store2, clock = clock)
                sync2.open()
                clock.advanceBoth(DeckFileSync.DEFAULT_RETENTION_MS + 1_000L)
                sync2.sweepNow()

                val conflict = assertIs<SyncState.Conflict>(store2.sync)
                assertEquals(ConflictKind.Overwrote, conflict.info.kind)
                assertTrue(conflict.info.theirsPath.endsWith(".theirs"))
                assertEquals(lateText, Files.readString(Path.of(conflict.info.theirsPath)))
                sync2.close()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun dualClockRetentionRestartDismissalFutureMillisAndMissingOrCorruptMeta() = runBlocking {
        val dir = Files.createTempDirectory("sync-retention")
        try {
            val target = dir.resolve("deck.deck.json")
            val recovery = dir.resolve("recovery")
            Files.writeString(target, CanonicalJson.print(makeDoc("V1")))

            val clock = FakeSyncClock(wallMs = 1_700_000_000_000L, monoMs = 10_000L)
            val retention = 600_000L // 10 minutes
            val store1 = DeckDocumentStore()
            val sync1 = DeckFileSync(
                targetFile = target,
                recoveryRootDir = recovery,
                store = store1,
                clock = clock,
                retentionMs = retention,
            )
            sync1.open()
            store1.edit { makeDoc("V2") }
            sync1.flushNow()
            val baseEntry = sync1.listQuarantineEntries(includeDismissed = true).single { it.kind == QuarantineKind.Base }

            // 1. Jumping wall clock forward +1 hour while mono only advances +1 minute MUST NOT delete!
            clock.wallMs += 3_600_000L
            clock.monoMs += 60_000L
            sync1.sweepNow()
            assertTrue(Files.exists(baseEntry.path), "Wall-clock jump alone must not shorten retention")

            // 2. Restart resets monotonic observation -> even after +9 min more (total wall >> 10m),
            //    monotonic age since restart is 0, so it MUST survive for a further full 10 minutes!
            sync1.close()
            val store2 = DeckDocumentStore()
            val sync2 = DeckFileSync(
                targetFile = target,
                recoveryRootDir = recovery,
                store = store2,
                clock = clock,
                retentionMs = retention,
            )
            sync2.open()
            clock.advanceBoth(retention - 5_000L)
            sync2.sweepNow()
            assertTrue(Files.exists(baseEntry.path), "Restart must retain entry for a further full retention period")

            // 3. Once full retention elapses on BOTH clocks since restart -> clean .base AND .meta deleted!
            clock.advanceBoth(10_000L)
            sync2.sweepNow()
            assertFalse(Files.exists(baseEntry.path), "Clean .base must be deleted once both wall and mono retention elapse")
            assertFalse(Files.exists(sync2.quarantineDir.resolve("${baseEntry.txid}.meta")), ".meta must be deleted with last entry")

            // 4. Future `<millis>` timestamp counts as age 0; missing/corrupt .meta is NEVER deleted or reclassified
            val futureWall = clock.wallMs + 500_000L
            val futureTxid = "future-tx"
            val futureFile = sync2.quarantineDir.resolve("$futureTxid-$futureWall.base")
            val futureBytes = "future-content".toByteArray(StandardCharsets.UTF_8)
            Files.write(futureFile, futureBytes)
            Files.writeString(
                sync2.quarantineDir.resolve("$futureTxid.meta"),
                SyncFilesHelper.syncJson.encodeToString(
                    SyncMeta.serializer(),
                    SyncMeta(futureTxid, SyncFilesHelper.sha256Hex(futureBytes), "other"),
                ),
            )

            val missingMetaFile = sync2.quarantineDir.resolve("nometa-1000.base")
            Files.writeString(missingMetaFile, "keep-me-no-meta")

            val corruptMetaTx = "corruptmeta"
            val corruptMetaFile = sync2.quarantineDir.resolve("$corruptMetaTx-1000.base")
            Files.writeString(corruptMetaFile, "keep-me-corrupt-meta")
            Files.writeString(sync2.quarantineDir.resolve("$corruptMetaTx.meta"), "{not-valid-json")

            // Dismissed .theirs entry: hidden immediately, NOT deleted before retention, deleted AFTER retention!
            val theirsTx = "dismiss-tx"
            val theirsName = "$theirsTx-${clock.wallMs}.theirs"
            val theirsFile = sync2.quarantineDir.resolve(theirsName)
            Files.writeString(theirsFile, "external-v")
            Files.writeString(
                sync2.quarantineDir.resolve("$theirsTx.meta"),
                SyncFilesHelper.syncJson.encodeToString(SyncMeta.serializer(), SyncMeta(theirsTx, "b", "n")),
            )

            sync2.sweepNow() // records firstObservedMonoMs
            assertTrue(sync2.dismissQuarantineEntry(theirsName))
            assertTrue(
                sync2.listQuarantineEntries(includeDismissed = false).none { it.fileName == theirsName },
                "Dismissed entry must be hidden from default listing",
            )
            assertTrue(Files.exists(theirsFile), "Dismissal must NOT delete before retention completes")

            // Advance by retention: futureFile's wall age is still < retention (it was +500s in future!),
            // whereas theirsFile is now past retention -> theirsFile deleted, futureFile kept, missing/corrupt meta kept!
            clock.advanceBoth(retention + 1_000L)
            sync2.sweepNow()
            assertFalse(Files.exists(theirsFile), "Dismissed entry must be deleted after retention completes")
            assertFalse(Files.exists(sync2.quarantineDir.resolve("$theirsTx.meta")))
            assertTrue(Files.exists(futureFile), "Future <millis> entry must count as age 0 and survive until wall >= future+retention")
            assertTrue(Files.exists(missingMetaFile), "Entry with missing .meta must NEVER be deleted or reclassified")
            assertTrue(Files.exists(corruptMetaFile), "Entry with corrupt .meta must NEVER be deleted or reclassified")

            // Advance another 500s so futureFile finally ages out
            clock.advanceBoth(500_000L)
            sync2.sweepNow()
            assertFalse(Files.exists(futureFile))
            assertTrue(Files.exists(missingMetaFile))
            assertTrue(Files.exists(corruptMetaFile))
            sync2.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inFlightEditExternalInvalidJournalGuardIoFailuresUnsupportedAndLock() = runBlocking {
        // 1. Edit committed while write is in flight stays Dirty and is written next
        run {
            val dir = Files.createTempDirectory("sync-inflight")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(makeDoc("V1")))

                val store = DeckDocumentStore()
                val injectedEdit = AtomicBoolean(false)
                val sync = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store,
                    stepHook = { step ->
                        if (step == SyncStep.WriteAfterJournal && injectedEdit.compareAndSet(false, true)) {
                            store.edit { makeDoc("V3 InFlight") }
                        }
                    },
                )
                sync.open()
                store.edit { makeDoc("V2 First") }
                sync.flushNow()
                assertTrue(store.isDirty, "Edit arriving while write is in flight must keep store Dirty")
                assertEquals(SyncState.Dirty, store.sync)
                assertEquals(CanonicalJson.print(makeDoc("V2 First")), Files.readString(target))

                // Next flush writes V3
                assertEquals(SyncState.Saved, sync.flushNow())
                assertEquals(CanonicalJson.print(makeDoc("V3 InFlight")), Files.readString(target))
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        // 2. External invalid JSON -> ExternalInvalid, suspends writing, overwriteExternalInvalid saves recovery copy first,
        //    AND fixing externally recovers cleanly
        run {
            val dir = Files.createTempDirectory("sync-ext-invalid")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(makeDoc("Valid V1")))

                val store = DeckDocumentStore()
                val sync = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store)
                sync.open()

                // External tool writes broken JSON
                val broken = "{ broken json !!!"
                Files.writeString(target, broken)
                val st = sync.pollExternalChangesNow()
                assertIs<SyncState.ExternalInvalid>(st)
                assertEquals("Valid V1", store.document.title, "Last valid document must stay loaded")

                // Editing & flushing while ExternalInvalid must NOT overwrite target!
                store.edit { makeDoc("Studio Pending") }
                assertIs<SyncState.ExternalInvalid>(sync.flushNow())
                assertEquals(broken, Files.readString(target))
                assertIs<CloseFlushResult.HeldExternalInvalid>(sync.flushOnClose())

                // Overwrite action copies invalid bytes to recovery first, then writes Studio's doc
                val copyRes = sync.overwriteExternalInvalid()
                assertTrue(copyRes.isSuccess)
                assertEquals(broken, Files.readString(copyRes.getOrThrow()))
                assertEquals(CanonicalJson.print(makeDoc("Studio Pending")), Files.readString(target))
                assertEquals(SyncState.Saved, store.sync)

                // Break again, then fix externally -> watcher recovers automatically!
                Files.writeString(target, "{ broken again")
                assertIs<SyncState.ExternalInvalid>(sync.pollExternalChangesNow())
                writeCanonicalAtomic(target, makeDoc("Fixed Externally"))
                assertEquals(SyncState.Saved, sync.pollExternalChangesNow())
                assertEquals("Fixed Externally", store.document.title)
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        // 3. I/O failure before exchange vs after exchange, plus journal blocks second save
        run {
            val dir = Files.createTempDirectory("sync-io-failures")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                val canonical1 = CanonicalJson.print(makeDoc("V1"))
                Files.writeString(target, canonical1)

                var failExchange = true
                var failQuarantineMove = false
                val flakyOps = object : AtomicFileOps by NativeAtomicFileOps {
                    override fun exchange(pathA: Path, pathB: Path): AtomicExchangeResult {
                        if (failExchange) throw IOException("Disk full before exchange")
                        return NativeAtomicFileOps.exchange(pathA, pathB)
                    }

                    override fun atomicMove(from: Path, to: Path) {
                        if (failQuarantineMove) throw IOException("Quarantine move I/O failure")
                        NativeAtomicFileOps.atomicMove(from, to)
                    }
                }

                val store = DeckDocumentStore()
                val sync = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store, fileOps = flakyOps)
                sync.open()
                store.edit { makeDoc("V2") }

                // Before exchange failure: target untouched, tmp/journal cleaned up
                val beforeFail = assertIs<SyncState.WriteFailed>(sync.flushNow())
                assertIs<WriteFailure.BeforeExchange>(beforeFail.failure)
                assertEquals(canonical1, Files.readString(target))
                assertFalse(Files.exists(sync.journalFile))

                // Clear state via retry, now fail AFTER exchange (on quarantine move)
                failExchange = false
                failQuarantineMove = true
                sync.retryUnresolvedTransaction()
                val afterFail = assertIs<SyncState.WriteFailed>(sync.flushNow())
                val afterInfo = assertIs<WriteFailure.AfterExchange>(afterFail.failure)
                assertTrue(afterInfo.displacedAt.endsWith(".tmp"))
                // Studio's V2 IS installed on disk, journal still exists, flushNow refuses to start a second write!
                assertEquals(CanonicalJson.print(makeDoc("V2")), Files.readString(target))
                assertTrue(Files.exists(sync.journalFile))
                store.edit { makeDoc("V3 Blocked") }
                assertIs<SyncState.WriteFailed>(sync.flushNow(), "Must not start a second save while journal/AfterExchange exists")
                assertIs<CloseFlushResult.HeldWriteFailed>(sync.flushOnClose())

                // Allow quarantine move to succeed, retry resolves the transaction, then V3 writes cleanly
                failQuarantineMove = false
                sync.retryUnresolvedTransaction()
                assertFalse(Files.exists(sync.journalFile))
                assertEquals(SyncState.Saved, sync.flushNow())
                assertEquals(CanonicalJson.print(makeDoc("V3 Blocked")), Files.readString(target))
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        // 4. Exchange unavailable -> ReadOnly and target NEVER written
        run {
            val dir = Files.createTempDirectory("sync-unsupported")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                val initial = CanonicalJson.print(makeDoc("ReadOnly Deck"))
                Files.writeString(target, initial)

                val unsupportedOps = object : AtomicFileOps by NativeAtomicFileOps {
                    override fun probeExchangeSupport(syncDir: Path): AtomicExchangeResult =
                        AtomicExchangeResult.Unsupported("Filesystem does not support RENAME_EXCHANGE")
                }
                val store = DeckDocumentStore()
                val sync = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store, fileOps = unsupportedOps)
                sync.open()
                assertIs<SyncState.ReadOnly>(store.sync)
                assertTrue(store.isReadOnly)
                assertFalse(store.edit { makeDoc("Mutated") })
                assertEquals(initial, Files.readString(target))
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        // 5. Second Studio instance on same file sees S/lock -> ReadOnly
        run {
            val dir = Files.createTempDirectory("sync-lock")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(makeDoc("Locked")))

                val store1 = DeckDocumentStore()
                val sync1 = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store1)
                sync1.open()
                assertFalse(store1.isReadOnly)

                val store2 = DeckDocumentStore()
                val sync2 = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store2)
                sync2.open()
                assertTrue(store2.isReadOnly)
                assertIs<SyncState.ReadOnly>(store2.sync)

                sync2.close()
                sync1.close()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun conflictsKeepMineTakeTheirsRecoveryCopyFailureAndCloseHold() = runBlocking {
        val docBase = makeDoc("Base")
        val docMine = makeDoc("Mine")
        val docTheirs = makeDoc("Theirs")
        val docThird = makeDoc("Third External")

        // A. Unwritten -> Keep mine (writes mine, puts theirs in quarantine)
        run {
            val dir = Files.createTempDirectory("sync-unwritten-keep")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(docBase))

                val store = DeckDocumentStore()
                val sync = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store)
                sync.open()
                store.edit { docMine }
                writeCanonicalAtomic(target, docTheirs)

                val conflict = assertIs<SyncState.Conflict>(sync.flushNow())
                assertEquals(ConflictKind.Unwritten, conflict.info.kind)
                assertIs<CloseFlushResult.HeldConflict>(sync.flushOnClose())

                assertEquals(SyncState.Saved, sync.keepMine())
                assertEquals(CanonicalJson.print(docMine), Files.readString(target))
                val q = sync.listQuarantineEntries(includeDismissed = true)
                assertTrue(q.any { Files.readString(it.path) == CanonicalJson.print(docTheirs) })
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        // B. Unwritten -> Take theirs (recovery copy write failure refuses action; success leaves mine in recovery & undo)
        run {
            val dir = Files.createTempDirectory("sync-unwritten-take")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(docBase))

                var failRecoveryWrite = true
                val guardedOps = object : AtomicFileOps by NativeAtomicFileOps {
                    override fun writeFileAndFsync(path: Path, bytes: ByteArray) {
                        if (failRecoveryWrite && path.toAbsolutePath().startsWith(recovery.toAbsolutePath())) {
                            throw IOException("Recovery dir read-only")
                        }
                        NativeAtomicFileOps.writeFileAndFsync(path, bytes)
                    }
                }

                val store = DeckDocumentStore()
                val sync = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store, fileOps = guardedOps)
                sync.open()
                store.edit { docMine }
                writeCanonicalAtomic(target, docTheirs)
                assertIs<SyncState.Conflict>(sync.pollExternalChangesNow())

                // Recovery-copy failure MUST refuse Take theirs and keep conflict open
                val refused = sync.takeTheirs()
                assertTrue(refused.isFailure)
                assertIs<SyncState.Conflict>(store.sync)
                assertEquals("Mine", store.document.title)

                // Now succeed: recovery copy holds Mine, store holds Theirs, undo restores Mine!
                failRecoveryWrite = false
                val taken = sync.takeTheirs()
                assertTrue(taken.isSuccess)
                assertEquals(CanonicalJson.print(docMine), Files.readString(taken.getOrThrow()))
                assertEquals("Theirs", store.document.title)
                assertEquals(SyncState.Saved, store.sync)
                assertTrue(store.canUndo)
                store.undo()
                assertEquals("Mine", store.document.title, "Mine must remain reachable by undo after Take theirs")
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        // C. Overwrote -> Keep mine vs Take theirs (including third-party replace before Take theirs!)
        run {
            val dir = Files.createTempDirectory("sync-overwrote")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(docBase))

                val store = DeckDocumentStore()
                var injectRace = true
                val sync = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store,
                    stepHook = { step ->
                        if (injectRace && step == SyncStep.WriteAfterCheck) {
                            injectRace = false
                            writeCanonicalAtomic(target, docTheirs)
                        }
                    },
                )
                sync.open()
                store.edit { docMine }
                val conflict = assertIs<SyncState.Conflict>(sync.flushNow())
                assertEquals(ConflictKind.Overwrote, conflict.info.kind)
                assertEquals(CanonicalJson.print(docMine), Files.readString(target))
                assertEquals(CanonicalJson.print(docTheirs), Files.readString(Path.of(conflict.info.theirsPath)))
                assertIs<CloseFlushResult.HeldConflict>(sync.flushOnClose())

                // Another tool replaces target with docThird BEFORE user clicks Take theirs!
                writeCanonicalAtomic(target, docThird)

                val takeRes = sync.takeTheirs()
                assertTrue(takeRes.isSuccess, "Recovery copy of Mine succeeded before attempting save")
                assertEquals(CanonicalJson.print(docMine), Files.readString(takeRes.getOrThrow()))
                // MUST NOT overwrite docThird! Normal write protocol detects fresh Unwritten conflict!
                assertEquals(CanonicalJson.print(docThird), Files.readString(target), "Take theirs after Overwrote must NEVER overwrite a newer target")
                val freshConflict = assertIs<SyncState.Conflict>(store.sync)
                assertEquals(ConflictKind.Unwritten, freshConflict.info.kind)

                // Now test Overwrote -> Keep mine cleanly on a fresh file
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }

        run {
            val dir = Files.createTempDirectory("sync-overwrote-keep")
            try {
                val target = dir.resolve("deck.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(docBase))

                val store = DeckDocumentStore()
                val sync = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = recovery,
                    store = store,
                    stepHook = { step ->
                        if (step == SyncStep.WriteAfterCheck) writeCanonicalAtomic(target, docTheirs)
                    },
                )
                sync.open()
                store.edit { docMine }
                val conflict = assertIs<SyncState.Conflict>(sync.flushNow())
                assertEquals(ConflictKind.Overwrote, conflict.info.kind)

                assertEquals(SyncState.Saved, sync.keepMine())
                assertEquals(CanonicalJson.print(docMine), Files.readString(target))
                assertTrue(Files.exists(Path.of(conflict.info.theirsPath)), ".theirs must remain in quarantine after Keep mine")
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun startBackgroundSyncDebounceExternalConflictAndSweepPreservation(): Unit = runBlocking {
        // 1. Real startBackgroundSync debounces edits, suppresses writes during open tx, and writes after commit.
        run {
            val dir = Files.createTempDirectory("bg-sync-debounce")
            val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val target = dir.resolve("deck.deck.json")
                Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
                val store = DeckDocumentStore()
                val sync = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = dir.resolve("rec"),
                    store = store,
                    debounceMs = 60L,
                    pollIntervalMs = 20L,
                    sweepIntervalMs = 200L,
                )
                try {
                    assertEquals(DeckLoadState.Loaded, sync.open())
                    sync.startBackgroundSync(bgScope)

                    // Open gesture transaction -> background debounce must NOT persist preview!
                    val tx = assertNotNull(store.begin("drag"))
                    tx.update { makeDoc("PREVIEW_DURING_DEBOUNCE") }
                    delay(180L)
                    assertEquals(CanonicalJson.print(makeDoc("V1")), Files.readString(target))

                    // Commit transaction -> background debounce persists V2 automatically!
                    assertTrue(tx.commit())
                    val deadline = System.currentTimeMillis() + 3_000L
                    while (store.isDirty && System.currentTimeMillis() < deadline) {
                        delay(20L)
                    }
                    assertFalse(store.isDirty, "Background debounce should persist committed edit")
                    assertEquals(SyncState.Saved, store.sync)
                    assertEquals(CanonicalJson.print(makeDoc("V2").copy(title = "PREVIEW_DURING_DEBOUNCE", slides = makeDoc("PREVIEW_DURING_DEBOUNCE").slides)), Files.readString(target))
                } finally {
                    sync.close()
                }
            } finally {
                bgScope.cancel()
                dir.deleteRecursively()
            }
        }

        // 2. External replacement during debounce window triggers Conflict(Unwritten) without overwriting external bytes.
        run {
            val dir = Files.createTempDirectory("bg-sync-ext-conflict")
            val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val target = dir.resolve("deck.deck.json")
                Files.writeString(target, CanonicalJson.print(makeDoc("Base")))
                val store = DeckDocumentStore()
                val sync = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = dir.resolve("rec"),
                    store = store,
                    debounceMs = 250L,
                    pollIntervalMs = 20L,
                    sweepIntervalMs = 5_000L,
                )
                try {
                    assertEquals(DeckLoadState.Loaded, sync.open())
                    sync.startBackgroundSync(bgScope)

                    assertTrue(store.edit { makeDoc("Studio-Dirty") })
                    val externalDoc = makeDoc("External-Winner")
                    writeCanonicalAtomic(target, externalDoc)

                    val deadline = System.currentTimeMillis() + 3_000L
                    while (store.sync !is SyncState.Conflict && System.currentTimeMillis() < deadline) {
                        delay(20L)
                    }
                    val conflict = assertIs<SyncState.Conflict>(store.sync)
                    assertEquals(ConflictKind.Unwritten, conflict.info.kind)
                    assertEquals(CanonicalJson.print(externalDoc), Files.readString(target))
                } finally {
                    sync.close()
                }
            } finally {
                bgScope.cancel()
                dir.deleteRecursively()
            }
        }

        // 3. Sweep promoting a modified .base -> .theirs while an Unwritten conflict is open preserves the open conflict banner.
        run {
            val dir = Files.createTempDirectory("sweep-preserves-open-conflict")
            try {
                val target = dir.resolve("deck.deck.json")
                val clock = FakeSyncClock()
                Files.writeString(target, CanonicalJson.print(makeDoc("V1")))
                val store = DeckDocumentStore()
                val sync = DeckFileSync(
                    targetFile = target,
                    recoveryRootDir = dir.resolve("rec"),
                    store = store,
                    clock = clock,
                )
                try {
                    assertEquals(DeckLoadState.Loaded, sync.open())
                    assertTrue(store.edit { makeDoc("V2") })
                    assertEquals(SyncState.Saved, sync.flushNow())

                    // Modify the quarantined .base entry (simulating a retained FD late write)
                    val baseEntry = sync.listQuarantineEntries(includeDismissed = true).single { it.kind == QuarantineKind.Base }
                    Files.writeString(baseEntry.path, CanonicalJson.print(makeDoc("Late-FD-Write")))

                    // Now open an Unwritten conflict banner via dirty edit + external replace
                    assertTrue(store.edit { makeDoc("V3-Mine") })
                    writeCanonicalAtomic(target, makeDoc("V3-External"))
                    val unwritten = assertIs<SyncState.Conflict>(sync.pollExternalChangesNow())
                    assertEquals(ConflictKind.Unwritten, unwritten.info.kind)

                    // Advance past retention and run sweep -> .base promoted to .theirs + notice emitted,
                    // while the already-open Unwritten conflict banner stays intact!
                    clock.advanceBoth(DeckFileSync.DEFAULT_RETENTION_MS + 1_000L)
                    sync.sweepNow()

                    val stillConflict = assertIs<SyncState.Conflict>(store.sync)
                    assertEquals(ConflictKind.Unwritten, stillConflict.info.kind)
                    assertEquals(unwritten.info.theirsHash, stillConflict.info.theirsHash)
                    assertTrue(
                        sync.listQuarantineEntries(includeDismissed = true).any { it.kind == QuarantineKind.Theirs },
                        "Modified .base must be promoted to .theirs",
                    )
                    assertTrue(
                        sync.recoveryNotices.any { it.kind == "theirs_from_base" },
                        "Promoting modified .base while another conflict is open must record a RecoveryNotice",
                    )
                } finally {
                    sync.close()
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
