package dev.sebastiano.ciacola.document

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Tests for [DeckDocumentStore] transactions/undo, structure edits, fragment provenance remapping,
 * [LegacyOverridesImporter], and [StudioSessionStore].
 */
@OptIn(ExperimentalPathApi::class)
class DeckDocumentStoreAndProvenanceTest {

    private fun baseDoc(): DeckDocument = DeckDocument(
        id = "store-test",
        title = "Initial",
        defaultTheme = "main",
        themes = mapOf(
            "main" to DeckThemeSpec(
                colors = mapOf("fg" to "#FFFFFF", "accent" to "#FF6B5E"),
                typography = mapOf("title" to TextStyleOverride(size = 72.0)),
            ),
        ),
        slides = listOf(
            SlideSpec(
                id = "s0",
                beats = listOf(
                    BeatSpec(notes = "Beat 0 notes"),
                    BeatSpec(notes = "Beat 1 notes"),
                    BeatSpec(notes = "Beat 2 notes"),
                ),
                nodes = listOf(
                    NodeSpec(
                        id = "n0",
                        frame = FrameSpec(x = 100.0, y = 100.0, w = 400.0, h = 100.0),
                        text = TextPayload(value = RichTextValue.Plain("alpha")),
                        builds = listOf(
                            Build.Enter(id = "e0", beat = 1, effect = "fade", durationMs = 300),
                            Build.Change(id = "c1", beat = 2, durationMs = 400, set = buildJsonObject { put("opacity", 0.5) }),
                        ),
                    ),
                    NodeSpec(
                        id = "n1",
                        frame = FrameSpec(x = 600.0, y = 100.0, w = 300.0, h = 100.0),
                        shape = ShapePayload(geometry = ShapeGeometry.Rect),
                        builds = listOf(
                            Build.Enter(id = "e1", beat = 1, effect = "fade", after = "n0#e0", durationMs = 250),
                            Build.Emphasis(id = "em1", beat = 1, effect = "pulse", after = "n1#e1", durationMs = 200),
                        ),
                    ),
                    NodeSpec(
                        id = "conn",
                        connector = ConnectorPayload(from = "n0", to = "n1"),
                    ),
                    NodeSpec(
                        id = "spec",
                        frame = FrameSpec(x = 100.0, y = 400.0, w = 300.0, h = 200.0),
                        specimen = SpecimenPayload(
                            ref = "demo",
                            tracks = mapOf("intro" to SpecimenTrackOverride(beat = 1, delayMs = 20, durationMs = 300)),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun transactionsApplyToSnapshotAtBeginUndoMidGestureAndUnified200StepStack() {
        val store = DeckDocumentStore(maxHistorySize = 200)
        val initial = baseDoc()
        store.loadInitial(initial)
        assertFalse(store.canUndo)
        assertFalse(store.canRedo)

        // 1. Slider drag with multiple updates (including a simulated pause) = ONE undo step,
        //    and every update applies to snapshotAtBegin (no cumulative drift!).
        val tx1 = assertNotNull(store.begin("Drag x"))
        assertTrue(store.hasOpenTransaction)
        assertNull(store.begin("Nested not allowed"))
        tx1.update { snap ->
            // Always receives snapshotAtBegin (x == 100.0), never intermediate values
            assertEquals(100.0, snap.slides[0].nodes[0].frame?.x)
            snap.copy(
                slides = snap.slides.map { s ->
                    s.copy(nodes = s.nodes.map { if (it.id == "n0") it.copy(frame = it.frame!!.copy(x = 125.0)) else it })
                },
            )
        }
        tx1.update { snap ->
            assertEquals(100.0, snap.slides[0].nodes[0].frame?.x, "update must always receive snapshotAtBegin")
            snap.copy(
                slides = snap.slides.map { s ->
                    s.copy(nodes = s.nodes.map { if (it.id == "n0") it.copy(frame = it.frame!!.copy(x = 240.0)) else it })
                },
            )
        }
        tx1.commit()
        assertFalse(store.hasOpenTransaction)
        assertEquals(240.0, store.document.slides[0].nodes[0].frame?.x)
        assertEquals(listOf("Drag x"), store.undoHistoryLabels())

        // 2. Two quick separate drags = TWO undo steps
        val tx2 = assertNotNull(store.begin("Drag x #2"))
        tx2.update { snap ->
            snap.copy(
                slides = snap.slides.map { s ->
                    s.copy(nodes = s.nodes.map { if (it.id == "n0") it.copy(frame = it.frame!!.copy(x = 300.0)) else it })
                },
            )
        }
        tx2.commit()
        assertEquals(listOf("Drag x", "Drag x #2"), store.undoHistoryLabels())

        // 3. Cancel mid-gesture (`Esc`) restores exact snapshotAtBegin without touching undo stack
        val beforeCancel = store.document
        val tx3 = assertNotNull(store.begin("Cancelled drag"))
        tx3.update { it.copy(title = "Transient") }
        assertEquals("Transient", store.document.title)
        store.cancelActiveTransaction()
        assertFalse(store.hasOpenTransaction)
        assertEquals(beforeCancel, store.document)
        assertEquals(listOf("Drag x", "Drag x #2"), store.undoHistoryLabels())

        // 4. Undo mid-gesture cancels the active transaction restoring exact snapshotAtBegin
        val tx4 = assertNotNull(store.begin("Undo mid-gesture"))
        tx4.update { it.copy(title = "Transient 2") }
        store.undo()
        assertFalse(store.hasOpenTransaction)
        assertEquals(beforeCancel, store.document)
        assertEquals(listOf("Drag x", "Drag x #2"), store.undoHistoryLabels())

        // 5. Interleaved Element, Motion, and Theme edits share ONE unified undo/redo stack
        assertTrue(store.edit("Element opacity") { doc ->
            doc.copy(slides = doc.slides.map { s -> s.copy(nodes = s.nodes.map { if (it.id == "n0") it.copy(opacity = 0.7) else it }) })
        })
        assertTrue(store.edit("Motion duration") { doc ->
            doc.copy(
                slides = doc.slides.map { s ->
                    s.copy(
                        nodes = s.nodes.map { n ->
                            if (n.id != "n0") n else n.copy(
                                builds = n.builds.map { if (it is Build.Enter) it.copy(durationMs = 600) else it },
                            )
                        },
                    )
                },
            )
        })
        assertTrue(store.edit("Theme accent") { doc ->
            val t = doc.themes.getValue("main")
            doc.copy(themes = doc.themes + ("main" to t.copy(colors = t.colors + ("accent" to "#00E5FF"))))
        })

        assertEquals(listOf("Drag x", "Drag x #2", "Element opacity", "Motion duration", "Theme accent"), store.undoHistoryLabels())
        // Undo Theme -> Motion -> Element -> Drag #2 -> Drag #1
        store.undo()
        assertEquals("#FF6B5E", store.document.themes.getValue("main").colors["accent"])
        store.undo()
        assertEquals(300, (store.document.slides[0].nodes[0].builds[0] as Build.Enter).durationMs)
        store.undo()
        assertEquals(1.0, store.document.slides[0].nodes[0].opacity)
        store.undo()
        assertEquals(240.0, store.document.slides[0].nodes[0].frame?.x)
        store.undo()
        assertEquals(initial, store.document)
        assertFalse(store.canUndo)
        assertTrue(store.canRedo)

        // Redo restores step by step
        store.redo()
        assertEquals(240.0, store.document.slides[0].nodes[0].frame?.x)

        // 6. 200-step cap
        val cappedStore = DeckDocumentStore(maxHistorySize = 200)
        cappedStore.loadInitial(initial)
        for (i in 1..225) {
            assertTrue(cappedStore.edit("Step $i") { it.copy(title = "Title $i") })
        }
        assertEquals(200, cappedStore.undoHistoryLabels().size)
        assertEquals("Step 26", cappedStore.undoHistoryLabels().first())
        assertEquals("Step 225", cappedStore.undoHistoryLabels().last())

        // 7. Read-only store rejects edit and begin
        val roStore = DeckDocumentStore()
        roStore.loadInitial(initial, readOnlyReason = "Locked")
        assertFalse(roStore.edit("Blocked") { it.copy(title = "No") })
        assertNull(roStore.begin("Blocked"))
    }

    @Test
    fun beatInsertDeleteBuildDeleteAndNodeDeleteWithReferences() {
        val doc = baseDoc()

        // insertBeat at 1 shifts builds & specimen tracks with beat >= 1 by +1
        val inserted = DeckStructureEdits.insertBeat(doc, "s0", atBeat = 1)
        assertEquals(4, inserted.slides[0].beats.size)
        val n0Ins = inserted.slides[0].nodes.first { it.id == "n0" }
        assertEquals(listOf(2, 3), n0Ins.builds.map { it.beat })
        assertEquals(2, inserted.slides[0].nodes.first { it.id == "spec" }.specimen?.tracks?.get("intro")?.beat)

        // deleteBeat at 2 moves beat 2 builds to beat 1 AFTER beat 1's existing builds, merges notes, nothing dropped
        val deletedBeat = DeckStructureEdits.deleteBeat(doc, "s0", beatIndex = 2)
        assertEquals(2, deletedBeat.slides[0].beats.size)
        assertEquals("Beat 1 notes\nBeat 2 notes", deletedBeat.slides[0].beats[1].notes)
        val n0Del = deletedBeat.slides[0].nodes.first { it.id == "n0" }
        assertEquals(listOf("e0", "c1"), n0Del.builds.map { it.id })
        assertEquals(listOf(1, 1), n0Del.builds.map { it.beat })

        // deleteBuild on n1#e1 (which has after="n0#e0") rewires dependent n1#em1 to inherit "n0#e0"
        val deletedBuild = DeckStructureEdits.deleteBuild(doc, "s0", nodeId = "n1", buildIndex = 0)
        val n1After = deletedBuild.slides[0].nodes.first { it.id == "n1" }
        assertEquals(1, n1After.builds.size)
        assertEquals("em1", n1After.builds[0].id)
        assertEquals("n0#e0", n1After.builds[0].after)

        // findNodeReferences + deleteNode(removeReferences = true)
        val refs = DeckStructureEdits.findNodeReferences(doc.slides[0], "n0")
        assertEquals(listOf("conn"), refs.connectorIds)
        assertEquals(listOf("n1"), refs.afterBuildOwners)
        assertFalse(refs.isEmpty)

        val cleaned = DeckStructureEdits.deleteNode(doc, "s0", nodeId = "n0", removeReferences = true)
        assertTrue(cleaned.slides[0].nodes.none { it.id == "n0" || it.id == "conn" })
        val n1Cleaned = cleaned.slides[0].nodes.first { it.id == "n1" }
        assertNull(n1Cleaned.builds.first { it.id == "e1" }.after)
        assertEquals("n1#e1", n1Cleaned.builds.first { it.id == "em1" }.after)
    }

    @Test
    fun fragmentManualDelayEditProvenanceCoversEveryRepeatedTokenCase() {
        // 1. Typing "item " before `item#0` rewrites to `item#1`
        val remapBefore = FragmentProvenance.remapForRangeEdit(
            oldText = "item",
            edit = TextRangeEdit(start = 0, removedLength = 0, insertedText = "item "),
            manual = mapOf("item#0" to 250),
            by = MorphBy.Word,
        )
        assertEquals(mapOf("item#1" to 250), remapBefore.updatedManual)
        assertTrue(remapBefore.notices.isEmpty())

        // 2. Typing " item" after `item#0` keeps `item#0`
        val remapAfter = FragmentProvenance.remapForRangeEdit(
            oldText = "item",
            edit = TextRangeEdit(start = 4, removedLength = 0, insertedText = " item"),
            manual = mapOf("item#0" to 250),
            by = MorphBy.Word,
        )
        assertEquals(mapOf("item#0" to 250), remapAfter.updatedManual)
        assertTrue(remapAfter.notices.isEmpty())

        // 3. "item item" -> "itemx item" (typing 'x' at end of first `item`): removed with notice, NEVER slides to second `item`!
        val remapExtend = FragmentProvenance.remapForRangeEdit(
            oldText = "item item",
            edit = TextRangeEdit(start = 4, removedLength = 0, insertedText = "x"),
            manual = mapOf("item#0" to 250),
            by = MorphBy.Word,
        )
        assertTrue(remapExtend.updatedManual.isEmpty(), "Must NOT slide onto second occurrence 'item#0' in 'itemx item'")
        assertEquals(1, remapExtend.notices.size)
        assertEquals("item#0", remapExtend.notices.single().oldAddress)

        // 4. Deleting space between "foo bar" -> "foobar" removes manual delay on `foo#0` and `bar#0` with notice
        val remapMerge = FragmentProvenance.remapForRangeEdit(
            oldText = "foo bar",
            edit = TextRangeEdit(start = 3, removedLength = 1, insertedText = ""),
            manual = mapOf("foo#0" to 100, "bar#0" to 200),
            by = MorphBy.Word,
        )
        assertTrue(remapMerge.updatedManual.isEmpty())
        assertEquals(2, remapMerge.notices.size)

        // 5. Adding combining mark `\u0301` to addressed token removes entry with notice (Word AND Character modes!)
        val remapMarkWord = FragmentProvenance.remapForRangeEdit(
            oldText = "cafe cafe",
            edit = TextRangeEdit(start = 4, removedLength = 0, insertedText = "\u0301"),
            manual = mapOf("cafe#0" to 300),
            by = MorphBy.Word,
        )
        assertTrue(remapMarkWord.updatedManual.isEmpty(), "Combining mark extends word cluster -> must drop, never slide")
        assertEquals(1, remapMarkWord.notices.size)

        val remapMarkChar = FragmentProvenance.remapForRangeEdit(
            oldText = "ee",
            edit = TextRangeEdit(start = 1, removedLength = 0, insertedText = "\u0301"),
            manual = mapOf("e#0" to 300),
            by = MorphBy.Character,
        )
        assertTrue(remapMarkChar.updatedManual.isEmpty(), "Combining mark extends grapheme cluster -> must drop, never slide to second 'e'")
        assertEquals(1, remapMarkChar.notices.size)

        // 6. Whole-text replacement: repeated token removed with notice, unique token present once in both survives!
        val remapWhole = FragmentProvenance.remapForWholeReplacement(
            oldText = "alpha item item omega",
            newText = "omega item alpha item",
            manual = mapOf("item#0" to 120, "alpha#0" to 400, "omega#0" to 500),
            by = MorphBy.Word,
        )
        assertEquals(mapOf("alpha#0" to 400, "omega#0" to 500), remapWhole.updatedManual)
        assertEquals(listOf("item#0"), remapWhole.notices.map { it.oldAddress })

        // 7. DeckStructureEdits.applyTextRangeEdit + external edit lint warning (unit + end-to-end DeckFileSync!)
        val textDoc = DeckDocument(
            id = "prov",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "headline",
                            text = TextPayload(
                                value = RichTextValue.Plain("item unique"),
                                morph = TextMorphSpec(
                                    by = MorphBy.Word,
                                    stagger = Stagger(manual = mapOf("item#0" to 150, "unique#0" to 300)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val (shiftedDoc, shiftNotices) = DeckStructureEdits.applyTextRangeEdit(
            document = textDoc,
            slideId = "s0",
            nodeId = "headline",
            beat = 0,
            edit = TextRangeEdit(start = 0, removedLength = 0, insertedText = "item "),
        )
        assertTrue(shiftNotices.isEmpty())
        assertEquals(
            mapOf("item#1" to 150, "unique#0" to 300),
            shiftedDoc.slides[0].nodes[0].text?.morph?.stagger?.manual,
        )

        // External file edit changing text while repeated-token manual delay exists -> MANUAL_DELAY_EXTERNAL_EDIT_AMBIGUOUS
        val externallyEdited = shiftedDoc.copy(
            slides = shiftedDoc.slides.map { s ->
                s.copy(
                    nodes = s.nodes.map { n ->
                        n.copy(text = n.text!!.copy(value = RichTextValue.Plain("item item item unique")))
                    },
                )
            },
        )
        val warnings = FragmentProvenance.lintExternalDocumentEdit(shiftedDoc, externallyEdited)
        assertTrue(
            warnings.any { it.code == "MANUAL_DELAY_EXTERNAL_EDIT_AMBIGUOUS" && it.severity == IssueSeverity.LintWarning },
            "Expected MANUAL_DELAY_EXTERNAL_EDIT_AMBIGUOUS in $warnings",
        )

        // End-to-end through DeckFileSync watcher
        runBlocking {
            val dir = Files.createTempDirectory("provenance-sync-lint")
            try {
                val target = dir.resolve("prov.deck.json")
                val recovery = dir.resolve("recovery")
                Files.writeString(target, CanonicalJson.print(shiftedDoc), StandardCharsets.UTF_8)
                val store = DeckDocumentStore()
                val sync = DeckFileSync(targetFile = target, recoveryRootDir = recovery, store = store)
                sync.open()

                val tmp = dir.resolve(".ext.tmp")
                Files.writeString(tmp, CanonicalJson.print(externallyEdited), StandardCharsets.UTF_8)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

                sync.pollExternalChangesNow()
                assertTrue(
                    sync.externalEditLintWarnings.any { it.code == "MANUAL_DELAY_EXTERNAL_EDIT_AMBIGUOUS" },
                    "DeckFileSync watcher must surface MANUAL_DELAY_EXTERNAL_EDIT_AMBIGUOUS",
                )
                sync.close()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun studioSessionStoreAndLegacyOverridesImporter() {
        val dir = Files.createTempDirectory("session-legacy-import")
        try {
            // 1. StudioSessionStore: strictly session preferences, zero authored content
            val sessionFile = dir.resolve(".storyboard/session.json")
            val sessionStore = StudioSessionStore(sessionFile)
            assertEquals(StudioSessionState(), sessionStore.load())

            val saved = sessionStore.update {
                it.copy(
                    activeDeckId = "ciacola-talk",
                    lastSlideIdByDeck = mapOf("ciacola-talk" to "s3-demo"),
                    lastBeatByDeck = mapOf("ciacola-talk" to 2),
                    selectedThemeByDeck = mapOf("ciacola-talk" to "obsidian"),
                    workspaceLayout = WorkspaceLayoutState(inspectorOpen = true, timelineOpen = true, activeInspectorTab = "motion"),
                )
            }
            assertEquals(saved, sessionStore.load())
            val rawSession = Files.readString(sessionFile, StandardCharsets.UTF_8)
            assertFalse(rawSession.contains("\"slides\""), "Session file must never store deck content")

            // 2. LegacyOverridesImporter: element + timeline + design-system + archive
            val elemFile = dir.resolve("elements.json")
            Files.writeString(
                elemFile,
                """
                {
                  "elements": {
                    "n0": { "elementId": "n0", "text": "Imported Title", "offsetXDp": 12.0 },
                    "n0:b1": { "elementId": "n0:b1", "text": "Beat 1 Title" },
                    "missing-node": { "elementId": "missing-node", "text": "Orphan" }
                  }
                }
                """.trimIndent(),
            )

            val timelineFile = dir.resolve("timeline.json")
            Files.writeString(
                timelineFile,
                """
                {
                  "tracks": {
                    "s0/n0": {
                      "trackId": "s0/n0",
                      "delayMs": 80,
                      "durationMs": 520,
                      "curve": { "isSpring": true, "dampingRatio": 0.7, "stiffness": 380.0 }
                    },
                    "ghost/track": { "trackId": "ghost/track", "delayMs": 10 }
                  },
                  "sceneBeats": {
                    "0": { "sceneIndex": 0, "injectedAfterBeats": [1] }
                  },
                  "loopPeriodsMs": { "ambient/pulse": 2200 }
                }
                """.trimIndent(),
            )

            val dsFile = dir.resolve("design-main.json")
            Files.writeString(
                dsFile,
                """
                {
                  "colors": { "accentArgb": 4281597849 },
                  "typography": { "title": { "fontSizeSp": 80.0 } }
                }
                """.trimIndent(),
            )

            val archiveDir = dir.resolve(".storyboard/archived-legacy")
            val report = LegacyOverridesImporter.importFromFiles(
                document = baseDoc(),
                elementOverridesFile = elemFile,
                timelineOverridesFile = timelineFile,
                designSystemOverridesByTheme = mapOf("main" to dsFile),
                archiveDir = archiveDir,
            )

            assertEquals(3, report.archivedFiles.size, "All 3 legacy files must be archived, not deleted")
            assertTrue(report.archivedFiles.all { Files.exists(it) })
            assertFalse(Files.exists(elemFile))
            assertTrue(report.unplacedItems.any { it.contains("missing-node") })
            assertTrue(report.unplacedItems.any { it.contains("ghost/track") })

            val imported = report.document
            assertEquals(2200L, imported.loopPeriodsMs["ambient/pulse"])
            assertEquals("#33FF99", imported.themes.getValue("main").colors["accent"])
            assertEquals(80.0, imported.themes.getValue("main").typography["title"]?.size)
            assertEquals(4, imported.slides[0].beats.size, "INSERT beat edit must have added a beat")
            val n0Imp = imported.slides[0].nodes.first { it.id == "n0" }
            assertEquals("Imported Title", n0Imp.text?.value?.plainText)
            assertEquals("Beat 1 Title", JsonMergePatch.resolveNodeAtBeat(n0Imp, 1).text?.value?.plainText)
            assertEquals(80, n0Imp.builds.first { it.id == "e0" }.delayMs)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun exitFragmentAddressUsesSourceOccurrenceIndexAndMatchesProvenance() {
        val from = "a shader is a shader"
        val to = "a shader is great"
        val diff = TextTokenizer.diff(from, to, by = MorphBy.Word, pruneStopwords = false)

        val exitingAddresses = diff.exiting.map { it.address }.toSet()
        assertTrue("-shader#1" in exitingAddresses, "Expected '-shader#1' for second exiting 'shader', got $exitingAddresses")
        assertFalse("-shader#0" in exitingAddresses)
        assertTrue("-a#1" in exitingAddresses)

        // Remapping a manual delay on '-shader#1' when prefixing another 'shader ' to the source text
        // shifts occurrence 1 -> 2, matching the new TextTokenizer.diff exit address '-shader#2'!
        val manual = mapOf("-shader#1" to 240, "great#0" to 80)
        val edit = TextRangeEdit(start = 0, removedLength = 0, insertedText = "shader ")
        val remapped = FragmentProvenance.remapForRangeEdit(
            oldText = from,
            edit = edit,
            manual = manual,
            by = MorphBy.Word,
            editingExitSource = true,
        )
        assertEquals(mapOf("-shader#2" to 240, "great#0" to 80), remapped.updatedManual)
        assertTrue(remapped.notices.isEmpty())

        val newFrom = edit.applyTo(from) // "shader a shader is a shader"
        val newDiff = TextTokenizer.diff(newFrom, to, by = MorphBy.Word, pruneStopwords = false)
        assertTrue(
            newDiff.exiting.any { it.address == "-shader#2" },
            "Remapped exit address '-shader#2' must match TextTokenizer.diff exit address; got ${newDiff.exiting.map { it.address }}",
        )
    }

    @Test
    fun jsonMergePatch_memoizesSlideAndNodeResolutionWithZeroPerFrameRoundTrips() {
        JsonMergePatch.clearCachesForTesting()
        val staticNode = NodeSpec(
            id = "static",
            text = TextPayload(value = RichTextValue.Plain("Unchanged")),
        )
        val changingNode = NodeSpec(
            id = "mover",
            opacity = 1.0,
            text = TextPayload(value = RichTextValue.Plain("Beat 0")),
            builds = listOf(
                Build.Change(
                    id = "c1",
                    beat = 1,
                    set = buildJsonObject {
                        put("opacity", 0.5)
                        putJsonObject("text") { put("value", "Beat 1") }
                    },
                ),
            ),
        )
        val slide = SlideSpec(
            id = "s0",
            beats = listOf(BeatSpec(), BeatSpec()),
            nodes = listOf(staticNode, changingNode),
        )

        // Fast-path: node with no applicable change at beat 0 does 0 JSON round-trips and returns same instance.
        val atBeat0 = JsonMergePatch.resolveNodeAtBeat(changingNode, 0)
        assertTrue(atBeat0 === changingNode)
        assertTrue(JsonMergePatch.resolveNodeAtBeat(staticNode, 1) === staticNode)
        assertEquals(0L, JsonMergePatch.cacheStatsForTesting().jsonRoundTrips)

        // First resolve at beat 1 performs exactly 1 JSON round-trip; next 499 frames hit cache with 0 extra round-trips!
        val firstSlideNodes = JsonMergePatch.resolveSlideNodesAtBeat(slide, 1)
        assertEquals("Beat 1", firstSlideNodes[1].text?.value?.plainText)
        assertEquals(0.5, firstSlideNodes[1].opacity)
        assertEquals(1L, JsonMergePatch.cacheStatsForTesting().jsonRoundTrips)

        repeat(499) {
            val cachedSlideNodes = JsonMergePatch.resolveSlideNodesAtBeat(slide, 1)
            assertTrue(cachedSlideNodes === firstSlideNodes)
            val cachedNode = JsonMergePatch.resolveNodeAtBeat(changingNode, 1)
            assertTrue(cachedNode === firstSlideNodes[1])
        }
        val stats = JsonMergePatch.cacheStatsForTesting()
        assertEquals(1L, stats.jsonRoundTrips, "60fps repeated resolution must NEVER re-run JSON encode/decode")
        assertTrue(stats.hits >= 998L, "Expected >= 998 cache hits, got ${stats.hits}")

        // Structural fallback: equal copied SlideSpec / NodeSpec also reuses cached resolution with 0 new round-trips!
        val copiedSlide = slide.copy()
        val fromCopied = JsonMergePatch.resolveSlideNodesAtBeat(copiedSlide, 1)
        assertEquals(firstSlideNodes, fromCopied)
        assertEquals(1L, JsonMergePatch.cacheStatsForTesting().jsonRoundTrips)
    }

    @Test
    fun legacyOverridesImporter_archiveCollisionPreservesBothFilesWithTimestamp() {
        val dir = Files.createTempDirectory("legacy-archive-collision")
        try {
            val archiveDir = dir.resolve("archived-legacy")
            val elem1 = dir.resolve("element-overrides.json")
            Files.writeString(elem1, """{"overrides": {}}""")

            val report1 = LegacyOverridesImporter.importFromFiles(
                document = baseDoc(),
                elementOverridesFile = elem1,
                archiveDir = archiveDir,
            )
            assertEquals(1, report1.archivedFiles.size)
            val firstArchived = report1.archivedFiles.single()
            assertEquals("element-overrides.json", firstArchived.fileName.toString())
            assertEquals("""{"overrides": {}}""", Files.readString(firstArchived))

            // Second import with the same filename MUST NOT overwrite the first archive file!
            val elem2 = dir.resolve("element-overrides.json")
            Files.writeString(elem2, """{"overrides": {"s0/n0": {"text": "Second"}}}""")
            val report2 = LegacyOverridesImporter.importFromFiles(
                document = baseDoc(),
                elementOverridesFile = elem2,
                archiveDir = archiveDir,
            )
            assertEquals(1, report2.archivedFiles.size)
            val secondArchived = report2.archivedFiles.single()
            assertNotEquals(firstArchived, secondArchived, "Collision archive must get unique timestamped filename")
            assertEquals("""{"overrides": {}}""", Files.readString(firstArchived), "First archived file must remain intact")
            assertEquals("""{"overrides": {"s0/n0": {"text": "Second"}}}""", Files.readString(secondArchived))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun textRangeAndWholeTextEditsPreserveRichTextRunsAndStyling() {
        val richNode = NodeSpec(
            id = "rich-title",
            frame = FrameSpec(x = 100.0, y = 100.0, w = 800.0, h = 120.0),
            text = TextPayload(
                value = RichTextValue.Runs(
                    listOf(
                        TextRun(text = "Hello ", weight = 400, color = "#AAAAAA"),
                        TextRun(text = "Compose", weight = 700, italic = true, color = "#FF6B35"),
                    ),
                ),
            ),
        )
        val doc = DeckDocument(
            format = 1,
            id = "rich-edits",
            title = "Rich Edits",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(SlideSpec(id = "s0", beats = listOf(BeatSpec(), BeatSpec()), nodes = listOf(richNode))),
        )

        // Inline range edit on beat 0 must preserve runs and per-run styling.
        val (afterRange, _) = DeckStructureEdits.applyTextRangeEdit(
            document = doc,
            slideId = "s0",
            nodeId = "rich-title",
            beat = 0,
            edit = TextRangeEdit(start = 6, removedLength = 7, insertedText = "Desktop"),
        )
        val rangeValue = assertIs<RichTextValue.Runs>(afterRange.slides.single().nodes.single().text?.value)
        assertEquals("Hello Desktop", rangeValue.plainText)
        assertEquals(2, rangeValue.runs.size)
        assertEquals("#AAAAAA", rangeValue.runs[0].color)
        assertEquals(400, rangeValue.runs[0].weight)
        assertEquals("#FF6B35", rangeValue.runs[1].color)
        assertEquals(700, rangeValue.runs[1].weight)
        assertEquals(true, rangeValue.runs[1].italic)

        // Whole-text replacement on beat 0 and beat 1 must also preserve runs and styling.
        val (afterWholeBeat0, _) = DeckStructureEdits.applyWholeTextReplacement(
            document = doc,
            slideId = "s0",
            nodeId = "rich-title",
            beat = 0,
            newText = "Hello World",
        )
        val wholeValue0 = assertIs<RichTextValue.Runs>(afterWholeBeat0.slides.single().nodes.single().text?.value)
        assertEquals("Hello World", wholeValue0.plainText)
        assertEquals(2, wholeValue0.runs.size)
        assertEquals("#FF6B35", wholeValue0.runs[1].color)
        assertEquals(true, wholeValue0.runs[1].italic)

        val (afterWholeBeat1, _) = DeckStructureEdits.applyWholeTextReplacement(
            document = doc,
            slideId = "s0",
            nodeId = "rich-title",
            beat = 1,
            newText = "Hi Compose",
        )
        val resolvedBeat1 = JsonMergePatch.resolveNodeAtBeat(afterWholeBeat1.slides.single().nodes.single(), 1)
        val wholeValue1 = assertIs<RichTextValue.Runs>(resolvedBeat1.text?.value)
        assertEquals("Hi Compose", wholeValue1.plainText)
        assertEquals(2, wholeValue1.runs.size)
        assertEquals("#AAAAAA", wholeValue1.runs[0].color)
        assertEquals("#FF6B35", wholeValue1.runs[1].color)

        val (afterAppendBeat1, _) = DeckStructureEdits.applyWholeTextReplacement(
            document = afterWholeBeat1,
            slideId = "s0",
            nodeId = "rich-title",
            beat = 1,
            newText = "Hi Compose!",
        )
        val resolvedAppendBeat1 = JsonMergePatch.resolveNodeAtBeat(afterAppendBeat1.slides.single().nodes.single(), 1)
        val appendValue1 = assertIs<RichTextValue.Runs>(resolvedAppendBeat1.text?.value)
        assertEquals("Hi Compose!", appendValue1.plainText)
        assertEquals(2, appendValue1.runs.size)
        assertEquals("#AAAAAA", appendValue1.runs[0].color)
        assertEquals("#FF6B35", appendValue1.runs[1].color)
    }

    @Test
    fun editAtEarlierBeatInsertsBeforeLaterBeatChangesAndResolvesChronologically() {
        val nodeWithBeat2 = NodeSpec(
            id = "mover",
            frame = FrameSpec(x = 0.0, y = 100.0, w = 200.0, h = 80.0),
            text = TextPayload(value = RichTextValue.Plain("Move")),
            builds = listOf(
                Build.Change(
                    beat = 2,
                    set = buildJsonObject { putJsonObject("frame") { put("x", 200.0) } },
                ),
            ),
        )

        val edited = JsonMergePatch.applyEditAtBeat(
            node = nodeWithBeat2,
            beat = 1,
            patch = buildJsonObject { putJsonObject("frame") { put("x", 100.0) } },
        )
        val changeBeats = edited.builds.filterIsInstance<Build.Change>().map { it.beat }
        assertEquals(listOf(1, 2), changeBeats, "New beat-1 change must be inserted before beat-2 change")
        assertEquals(0.0, JsonMergePatch.resolveNodeAtBeat(edited, 0).frame?.x)
        assertEquals(100.0, JsonMergePatch.resolveNodeAtBeat(edited, 1).frame?.x)
        assertEquals(200.0, JsonMergePatch.resolveNodeAtBeat(edited, 2).frame?.x)

        // Hand-authored out-of-order builds [beat 2, beat 1] must also resolve chronologically.
        val outOfOrder = nodeWithBeat2.copy(
            builds = nodeWithBeat2.builds + Build.Change(
                beat = 1,
                set = buildJsonObject { putJsonObject("frame") { put("x", 100.0) } },
            ),
        )
        assertEquals(100.0, JsonMergePatch.resolveNodeAtBeat(outOfOrder, 1).frame?.x)
        assertEquals(200.0, JsonMergePatch.resolveNodeAtBeat(outOfOrder, 2).frame?.x)
        assertEquals(100.0, JsonMergePatch.resolveNodeBeforeBuild(outOfOrder, 0).frame?.x)
        assertEquals(200.0, JsonMergePatch.resolveNodeAfterBuild(outOfOrder, 0).frame?.x)
    }

    @Test
    fun beatZeroEditsReconcileBeatZeroChangeOverridesAfterDeletingBeatOne() {
        val node = NodeSpec(
            id = "n1",
            frame = FrameSpec(x = 100.0, y = 200.0, w = 300.0, h = 80.0),
            text = TextPayload(value = RichTextValue.Plain("Base"), color = "foreground"),
            builds = listOf(
                Build.Change(
                    beat = 1,
                    set = buildJsonObject {
                        putJsonObject("frame") { put("x", 300.0) }
                        putJsonObject("text") {
                            put("value", "Beat 1 copy")
                            put("color", "accent")
                        }
                        put("opacity", 0.5)
                    },
                ),
            ),
        )
        val doc = DeckDocument(
            format = 1,
            id = "beat0-reconcile",
            title = "Beat 0 Reconcile",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(node),
                ),
            ),
        )
        // Deleting beat 1 moves its Build.Change onto beat 0.
        val afterDelete = DeckStructureEdits.deleteBeat(doc, "s1", beatIndex = 1)
        val collapsed = afterDelete.slides.single().nodes.single()
        assertEquals(300.0, JsonMergePatch.resolveNodeAtBeat(collapsed, 0).frame?.x)
        assertEquals("Beat 1 copy", JsonMergePatch.resolveNodeAtBeat(collapsed, 0).text?.value?.plainText)

        // Editing frame.x on beat 0 must update the resolved node while keeping untouched beat-0 keys.
        val nudged = JsonMergePatch.applyEditAtBeat(
            collapsed,
            beat = 0,
            patch = buildJsonObject { putJsonObject("frame") { put("x", 310.0) } },
        )
        val resolvedNudged = JsonMergePatch.resolveNodeAtBeat(nudged, 0)
        assertEquals(310.0, resolvedNudged.frame?.x, "Beat-0 edit must not be shadowed by beat-0 Build.Change")
        assertEquals(0.5, resolvedNudged.opacity, "Untouched keys in the beat-0 Build.Change must survive")

        // Editing text on beat 0 via applyTextRangeEdit must also take effect on the resolved node.
        val (textEditedDoc, _) = DeckStructureEdits.applyTextRangeEdit(
            document = afterDelete,
            slideId = "s1",
            nodeId = "n1",
            beat = 0,
            edit = TextRangeEdit(start = 11, removedLength = 0, insertedText = "!"),
        )
        val resolvedText = JsonMergePatch.resolveNodeAtBeat(textEditedDoc.slides.single().nodes.single(), 0)
        assertEquals("Beat 1 copy!", resolvedText.text?.value?.plainText)

        // Resetting text.color on beat 0 must clear both base and beat-0 Build.Change.
        val resetColor = JsonMergePatch.resetAtBeat(nudged, beat = 0, keyPath = listOf("text", "color"))
        assertNull(JsonMergePatch.resolveNodeAtBeat(resetColor, 0).text?.color)
    }

    @Test
    fun textEditOnLaterBeatPreservesUnaffectedBuildManualFragmentDelays() {
        val node = NodeSpec(
            id = "copy",
            frame = FrameSpec(x = 100.0, y = 100.0, w = 600.0, h = 100.0),
            text = TextPayload(
                value = RichTextValue.Plain("alpha keep"),
                morph = TextMorphSpec(
                    by = MorphBy.Word,
                    stagger = Stagger(manual = mapOf("alpha#0" to 50)),
                ),
            ),
            builds = listOf(
                Build.Enter(
                    beat = 0,
                    effect = "fade",
                    by = DeliveryBy.Word,
                    stagger = Stagger(manual = mapOf("alpha#0" to 100)),
                ),
                Build.Emphasis(
                    beat = 0,
                    effect = "pulse",
                    by = DeliveryBy.Word,
                    stagger = Stagger(manual = mapOf("alpha#0" to 110)),
                ),
                Build.Change(
                    beat = 1,
                    set = buildJsonObject { putJsonObject("text") { put("value", "beta keep") } },
                    morph = TextMorphSpec(
                        by = MorphBy.Word,
                        stagger = Stagger(manual = mapOf("-alpha#0" to 80, "beta#0" to 120, "keep#0" to 130)),
                    ),
                ),
                Build.Change(
                    beat = 2,
                    set = buildJsonObject { putJsonObject("text") { put("value", "delta keep") } },
                    morph = TextMorphSpec(
                        by = MorphBy.Word,
                        stagger = Stagger(manual = mapOf("-beta#0" to 90, "delta#0" to 140)),
                    ),
                ),
                Build.Exit(
                    beat = 3,
                    effect = "fade",
                    by = DeliveryBy.Word,
                    stagger = Stagger(manual = mapOf("delta#0" to 150)),
                ),
            ),
        )
        val doc = DeckDocument(
            format = 1,
            id = "scoped-fragment-remap",
            title = "Scoped Fragment Remap",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec(), BeatSpec(), BeatSpec()),
                    nodes = listOf(node),
                ),
            ),
        )

        // Edit beat 1 text from "beta keep" -> "gamma keep" (replaces "beta" at 0..4 with "gamma").
        val (updatedDoc, notices) = DeckStructureEdits.applyTextRangeEdit(
            document = doc,
            slideId = "s1",
            nodeId = "copy",
            beat = 1,
            edit = TextRangeEdit(start = 0, removedLength = 4, insertedText = "gamma"),
        )
        val updated = updatedDoc.slides.single().nodes.single()

        // Beat-0 text ("alpha keep") and Beat-2/3 text ("delta keep") did NOT change -> delays preserved!
        assertEquals(mapOf("alpha#0" to 50), updated.text?.morph?.stagger?.manual)
        assertEquals(mapOf("alpha#0" to 100), (updated.builds[0] as Build.Enter).stagger?.manual)
        assertEquals(mapOf("alpha#0" to 110), (updated.builds[1] as Build.Emphasis).stagger?.manual)
        assertEquals(mapOf("delta#0" to 150), (updated.builds[4] as Build.Exit).stagger?.manual)

        // Beat-1 Change: exit side ("alpha keep") unchanged -> "-alpha#0" preserved; target side ("beta" -> "gamma")
        // prunes "beta#0" and keeps "keep#0".
        assertEquals(
            mapOf("-alpha#0" to 80, "keep#0" to 130),
            (updated.builds[2] as Build.Change).morph?.stagger?.manual,
        )
        // Beat-2 Change: exit side ("beta keep" -> "gamma keep") prunes "-beta#0"; target side ("delta keep") untouched!
        assertEquals(
            mapOf("delta#0" to 140),
            (updated.builds[3] as Build.Change).morph?.stagger?.manual,
        )
        assertEquals(setOf("beta#0", "-beta#0"), notices.mapTo(HashSet()) { it.oldAddress })
    }

    @Test
    fun deleteMiddleBuildRewiresExternalDependentsToImplicitSameNodePredecessor() {
        fun makeDoc(firstId: String?): DeckDocument = DeckDocument(
            format = 1,
            id = "delete-middle-build",
            title = "Delete Middle Build",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "a",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 200.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("A")),
                            builds = listOf(
                                Build.Enter(id = firstId, beat = 1, effect = "fade", durationMs = 300),
                                Build.Change(
                                    id = "middle",
                                    beat = 1,
                                    durationMs = 200,
                                    set = buildJsonObject { put("opacity", 0.7) },
                                ),
                                Build.Emphasis(id = "last", beat = 1, effect = "pulse", after = "a#middle", durationMs = 250),
                            ),
                        ),
                        NodeSpec(
                            id = "b",
                            frame = FrameSpec(x = 400.0, y = 100.0, w = 200.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("B")),
                            builds = listOf(
                                Build.Enter(id = "follow", beat = 1, effect = "fade", after = "a#middle", durationMs = 200),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // 1. Named predecessor: deleting a#middle redirects b#follow -> a#first and clears redundant same-node after on a#last.
        val afterNamed = DeckStructureEdits.deleteBuild(makeDoc(firstId = "first"), "s1", nodeId = "a", buildIndex = 1)
        val nodeBNamed = afterNamed.slides.single().nodes.first { it.id == "b" }
        val nodeANamed = afterNamed.slides.single().nodes.first { it.id == "a" }
        assertEquals("a#first", nodeBNamed.builds.single().after)
        assertNull(nodeANamed.builds.last().after, "Same-node later build sequences implicitly after a#first")
        val planNamed = SegmentPlanner.planSegment(afterNamed, "s1", beat = 1)
        assertEquals(300, planNamed.tracks.first { it.nodeId == "b" }.startMs, "B must follow a#first (300ms)")
        assertTrue(DeckDocumentCodec.validate(afterNamed).none { it.code.startsWith("AFTER_") })

        // 2. Anonymous predecessor with a later build still present: assigns synthetic id to predecessor so B
        // follows the 300ms Enter rather than jumping forward to a#last (550ms).
        val afterAnon = DeckStructureEdits.deleteBuild(makeDoc(firstId = null), "s1", nodeId = "a", buildIndex = 1)
        val nodeAAnon = afterAnon.slides.single().nodes.first { it.id == "a" }
        val nodeBAnon = afterAnon.slides.single().nodes.first { it.id == "b" }
        val assignedId = nodeAAnon.builds.first().id
        assertNotNull(assignedId, "Anonymous predecessor needs an id when followed by a later build")
        assertEquals("a#$assignedId", nodeBAnon.builds.single().after)
        val planAnon = SegmentPlanner.planSegment(afterAnon, "s1", beat = 1)
        assertEquals(300, planAnon.tracks.first { it.nodeId == "b" }.startMs, "B must follow the 300ms anonymous Enter, not a#last")
        assertTrue(DeckDocumentCodec.validate(afterAnon).none { it.code.startsWith("AFTER_") })
    }

    @Test
    fun deleteBeatPinsNodeOnlyDependenciesToPreventCyclesAndPreserveOrder() {
        fun makeCrossOrderDoc(
            targetBeat: Int,
            deletedBeat: Int,
            aFirstId: String?,
            bFirstId: String?,
            extraExistingIdOnA: String? = null,
            nestedInStack: Boolean = false,
        ): DeckDocument {
            val aBuilds = buildList {
                add(Build.Enter(id = aFirstId, beat = targetBeat, effect = "fade", durationMs = 200))
                add(Build.Emphasis(id = "second", beat = deletedBeat, effect = "pulse", after = "b", durationMs = 150))
                if (extraExistingIdOnA != null) {
                    add(Build.Emphasis(id = extraExistingIdOnA, beat = deletedBeat + 1, effect = "pulse", durationMs = 100))
                }
            }
            val bBuilds = listOf(
                Build.Enter(id = bFirstId, beat = targetBeat, effect = "fade", after = "a", durationMs = 150),
                Build.Emphasis(id = "second", beat = deletedBeat, effect = "pulse", durationMs = 250),
            )
            val nodeA = NodeSpec(
                id = "a",
                frame = FrameSpec(x = 100.0, y = 100.0, w = 200.0, h = 80.0),
                text = TextPayload(RichTextValue.Plain("A")),
                builds = aBuilds,
            )
            val nodeB = NodeSpec(
                id = "b",
                frame = FrameSpec(x = 400.0, y = 100.0, w = 200.0, h = 80.0),
                text = TextPayload(RichTextValue.Plain("B")),
                builds = bBuilds,
            )
            val nodeC = NodeSpec(
                id = "c",
                frame = FrameSpec(x = 700.0, y = 100.0, w = 200.0, h = 80.0),
                text = TextPayload(RichTextValue.Plain("C")),
                builds = listOf(Build.Enter(id = null, beat = targetBeat, effect = "fade", durationMs = 100)),
            )
            val nodeD = NodeSpec(
                id = "d",
                frame = FrameSpec(x = 1000.0, y = 100.0, w = 200.0, h = 80.0),
                text = TextPayload(RichTextValue.Plain("D")),
                builds = listOf(Build.Enter(id = null, beat = targetBeat, effect = "fade", after = "c", durationMs = 100)),
            )
            val rootNodes = if (nestedInStack) {
                listOf(
                    NodeSpec(
                        id = "group",
                        frame = FrameSpec(x = 0.0, y = 0.0, w = 1200.0, h = 300.0),
                        stack = ContainerPayload(children = listOf(nodeA, nodeB)),
                    ),
                    nodeC,
                    nodeD,
                )
            } else {
                listOf(nodeA, nodeB, nodeC, nodeD)
            }
            val beatCount = maxOf(deletedBeat + 1, if (extraExistingIdOnA != null) deletedBeat + 2 else 0)
            return DeckDocument(
                format = 1,
                id = "delete-beat-pin-after",
                title = "Delete Beat Pin After",
                defaultTheme = "dark",
                themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
                slides = listOf(
                    SlideSpec(id = "s0", beats = listOf(BeatSpec())),
                    SlideSpec(
                        id = "s1",
                        beats = List(beatCount) { BeatSpec() },
                        nodes = rootNodes,
                    ),
                ),
            )
        }

        // 1. Named builds merging beat 1 into beat 0: B#first(after="a") must pin to a#first so no cycle forms.
        val mergedBeat0 = DeckStructureEdits.deleteBeat(
            makeCrossOrderDoc(targetBeat = 0, deletedBeat = 1, aFirstId = "first", bFirstId = "first"),
            slideId = "s1",
            beatIndex = 1,
        )
        val mergedBeat0Issues = DeckDocumentCodec.validate(mergedBeat0)
        assertTrue(mergedBeat0Issues.none { it.code.startsWith("AFTER_") }, "Unexpected after issues: $mergedBeat0Issues")
        val mergedB0 = mergedBeat0.slides[1].nodes.first { it.id == "b" }
        assertEquals("a#first", mergedB0.builds.first().after)
        val mergedD0 = mergedBeat0.slides[1].nodes.first { it.id == "d" }
        assertEquals("c", mergedD0.builds.single().after, "Unmerged target node 'c' should remain node-only")
        val plan0 = SegmentPlanner.planSegment(mergedBeat0, "s1", beat = 0)
        assertEquals(200, plan0.tracks.first { it.nodeId == "b" && it.buildId == "first" }.startMs)

        // 2. Named builds merging beat 2 into beat 1: full execution order A#first -> B#first -> B#second -> A#second.
        val mergedBeat1 = DeckStructureEdits.deleteBeat(
            makeCrossOrderDoc(targetBeat = 1, deletedBeat = 2, aFirstId = "first", bFirstId = "first"),
            slideId = "s1",
            beatIndex = 2,
        )
        val mergedBeat1Issues = DeckDocumentCodec.validate(mergedBeat1)
        assertTrue(mergedBeat1Issues.none { it.code.startsWith("AFTER_") }, "Unexpected after issues: $mergedBeat1Issues")
        val plan1 = SegmentPlanner.planSegment(mergedBeat1, "s1", beat = 1)
        assertEquals(0, plan1.tracks.first { it.nodeId == "a" && it.buildId == "first" }.startMs)
        assertEquals(200, plan1.tracks.first { it.nodeId == "b" && it.buildId == "first" }.startMs)
        assertEquals(350, plan1.tracks.first { it.nodeId == "b" && it.buildId == "second" }.startMs)
        assertEquals(600, plan1.tracks.first { it.nodeId == "a" && it.buildId == "second" }.startMs)

        // 3. Anonymous builds inside a nested stack with an existing 'b1' id on another beat:
        // assigns non-colliding synthetic id ('b2'), pins B's target-beat after, and preserves acyclic timing.
        val mergedAnonNested = DeckStructureEdits.deleteBeat(
            makeCrossOrderDoc(
                targetBeat = 1,
                deletedBeat = 2,
                aFirstId = null,
                bFirstId = null,
                extraExistingIdOnA = "b1",
                nestedInStack = true,
            ),
            slideId = "s1",
            beatIndex = 2,
        )
        val nestedIssues = DeckDocumentCodec.validate(mergedAnonNested)
        assertTrue(
            nestedIssues.none { it.code.startsWith("AFTER_") || it.code == "DUPLICATE_BUILD_ID" },
            "Unexpected validation issues: $nestedIssues",
        )
        val groupChildren = mergedAnonNested.slides[1].nodes.first { it.id == "group" }.children
        val nestedA = groupChildren.first { it.id == "a" }
        val nestedB = groupChildren.first { it.id == "b" }
        assertEquals("b2", nestedA.builds.first().id)
        assertEquals("a#b2", nestedB.builds.first().after)
        val planNested = SegmentPlanner.planSegment(mergedAnonNested, "s1", beat = 1)
        assertEquals(0, planNested.tracks.first { it.nodeId == "a" && it.buildId == "b2" }.startMs)
        assertEquals(200, planNested.tracks.first { it.nodeId == "b" && it.buildIndex == 0 }.startMs)
        assertEquals(350, planNested.tracks.first { it.nodeId == "b" && it.buildId == "second" }.startMs)
        assertEquals(600, planNested.tracks.first { it.nodeId == "a" && it.buildId == "second" }.startMs)
    }

    @Test
    fun editingTextRemapsCrossSlideTransitionFragmentDelaysForIncomingAndOutgoingSides() {
        val doc = DeckDocument(
            format = 1,
            id = "cross-slide-remap",
            title = "Cross-Slide Remap",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec()),
                    transition = TransitionSpec(
                        type = TransitionType.MagicMove,
                        durationMs = 500,
                        text = mapOf(
                            "headline" to TextMorphSpec(
                                by = MorphBy.Word,
                                stagger = Stagger(
                                    manual = mapOf(
                                        "alpha#1" to 140,
                                        "-old#0" to 80,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "headline",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 600.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("old tail")),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "headline",
                            frame = FrameSpec(x = 100.0, y = 200.0, w = 600.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("alpha alpha")),
                        ),
                    ),
                ),
            ),
        )

        // 1. Incoming range edit on s1 beat 0: inserting "alpha " at offset 0 shifts alpha#1 -> alpha#2
        //    while leaving outgoing -old#0 untouched.
        val (afterIncomingRange, inNotices) = DeckStructureEdits.applyTextRangeEdit(
            document = doc,
            slideId = "s1",
            nodeId = "headline",
            beat = 0,
            edit = TextRangeEdit(start = 0, removedLength = 0, insertedText = "alpha "),
        )
        assertTrue(inNotices.isEmpty(), "Exact range shift should emit no removal notices: $inNotices")
        assertEquals(
            mapOf("alpha#2" to 140, "-old#0" to 80),
            afterIncomingRange.slides[0].transition?.text?.get("headline")?.stagger?.manual,
        )
        val planAfterIncoming = SegmentPlanner.planTransition(afterIncomingRange, "s0", "s1")
        val morphTrack = planAfterIncoming.tracks.single { it.nodeId == "headline" }
        assertEquals(140, morphTrack.pieces?.slot("alpha#2")?.startMs)

        // 2. Outgoing range edit on s0 last beat: inserting "old " at offset 0 shifts -old#0 -> -old#1
        //    while leaving incoming alpha#2 untouched.
        val (afterOutgoingRange, outNotices) = DeckStructureEdits.applyTextRangeEdit(
            document = afterIncomingRange,
            slideId = "s0",
            nodeId = "headline",
            beat = 0,
            edit = TextRangeEdit(start = 0, removedLength = 0, insertedText = "old "),
        )
        assertTrue(outNotices.isEmpty(), "Exact outgoing range shift should emit no notices: $outNotices")
        assertEquals(
            mapOf("alpha#2" to 140, "-old#1" to 80),
            afterOutgoingRange.slides[0].transition?.text?.get("headline")?.stagger?.manual,
        )

        // 3. Editing s1 beat 1 (not beat 0) does NOT affect the incoming s0->s1 transition.
        val (afterBeat1Edit, beat1Notices) = DeckStructureEdits.applyWholeTextReplacement(
            document = afterOutgoingRange,
            slideId = "s1",
            nodeId = "headline",
            beat = 1,
            newText = "unrelated beat1",
        )
        assertTrue(beat1Notices.isEmpty())
        assertEquals(
            mapOf("alpha#2" to 140, "-old#1" to 80),
            afterBeat1Edit.slides[0].transition?.text?.get("headline")?.stagger?.manual,
        )

        // 4. Whole-text replacement on s1 (allBeats = true) removes stale alpha#2 with a notice while keeping -old#1.
        val (afterWholeReplace, replaceNotices) = DeckStructureEdits.applyWholeTextReplacement(
            document = afterBeat1Edit,
            slideId = "s1",
            nodeId = "headline",
            beat = 0,
            newText = "gamma",
            allBeats = true,
        )
        assertEquals(
            mapOf("-old#1" to 80),
            afterWholeReplace.slides[0].transition?.text?.get("headline")?.stagger?.manual,
        )
        assertTrue(replaceNotices.any { it.oldAddress == "alpha#2" }, "Expected notice for removed alpha#2: $replaceNotices")

        // 5. External-edit lint also flags stale cross-slide transition delays when text is edited externally.
        val externallyEdited = afterOutgoingRange.copy(
            slides = afterOutgoingRange.slides.map { s ->
                if (s.id != "s1") {
                    s
                } else {
                    s.copy(
                        nodes = s.nodes.map { n ->
                            if (n.id == "headline") n.copy(text = n.text!!.copy(value = RichTextValue.Plain("gamma"))) else n
                        },
                    )
                }
            },
        )
        val extIssues = FragmentProvenance.lintExternalDocumentEdit(afterOutgoingRange, externallyEdited)
        assertTrue(
            extIssues.any { it.address == "s0/transition/text/headline" },
            "Expected external-edit lint warning on s0/transition/text/headline: $extIssues",
        )
    }
}
