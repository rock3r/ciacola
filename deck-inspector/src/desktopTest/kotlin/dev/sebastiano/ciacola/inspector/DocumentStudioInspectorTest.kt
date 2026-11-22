package dev.sebastiano.ciacola.inspector

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.CloseFlushResult
import dev.sebastiano.ciacola.document.ConflictKind
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckFileSync
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeSpec
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.document.ParamSpec
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.document.SyncStep
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextRangeEdit
import dev.sebastiano.ciacola.document.TextStyleOverride
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.TextMorphGranularity
import dev.sebastiano.ciacola.inspector.sync.CloseGuardState
import dev.sebastiano.ciacola.inspector.sync.DocumentLoadStateGate
import dev.sebastiano.ciacola.inspector.sync.DocumentSyncController
import dev.sebastiano.ciacola.inspector.sync.RecoverySource
import dev.sebastiano.ciacola.inspector.sync.SyncUiTestTags
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

/**
 * Studio UI and controller verification:
 * - Sync & conflict resolution: conflict UI (`Unwritten` & `Overwrote`), *Keep mine*, *Take theirs*,
 *   `ExternalInvalid`, recovery picker listing both `quarantine/` and app-data recovery copies, and
 *   close-hold dialog (*Save a recovery copy and close* / *Cancel*).
 * - Undo & editing semantics: slider drag with a 2-second pause = 1 undo step, two quick drags =
 *   2 undo steps, `Esc` mid-drag restores exact prior document, focused text-field local `⌘Z`,
 *   inline text edit range provenance + pruned manual delay notice, interleaved Element + Motion +
 *   Theme edits on one unified undo stack, beat-editing contract, specimen params, and copy themes.
 */
class DocumentStudioInspectorTest {

    private fun testSpecimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("studio.plasma", "Plasma Shader") {
            uniformFloat(name = "uSpeed", default = 1.0f, range = 0.0f..5.0f, step = 0.25f)
            int(name = "octaves", default = 4, range = 1..8, step = 1)
            boolean(name = "invert", default = false)
            color(name = "tint", default = Color(0xFFFF6B35))
            choice(name = "mode", default = "smooth", choices = listOf("smooth", "ridged", "cellular"))
            string(name = "label", default = "Plasma")
            content {}
        }
    }

    private fun sampleDeck(titleText: String = "A shader is a function"): DeckDocument = DeckDocument(
        id = "studio-inspector-deck",
        title = "Studio Inspector Test Deck",
        themes = mapOf(
            "dark" to DeckThemeSpec(
                preset = "stageDark",
                colors = mapOf("accent" to "#38BDF8"),
                typography = mapOf("title" to TextStyleOverride(size = 88.0, weight = 700)),
            ),
            "light" to DeckThemeSpec(
                preset = "keynoteLight",
                colors = mapOf("accent" to "#0284C7"),
            ),
        ),
        defaultTheme = "dark",
        slides = listOf(
            SlideSpec(
                id = "s1",
                title = "Slide 1",
                background = Fill.Solid("#0B0D12"),
                beats = listOf(
                    BeatSpec(notes = "Beat 0"),
                    BeatSpec(notes = "Beat 1", stagger = Stagger(ms = 40)),
                    BeatSpec(notes = "Beat 2"),
                ),
                nodes = listOf(
                    NodeSpec(
                        id = "headline",
                        frame = FrameSpec(x = 120.0, y = 100.0, w = 960.0, h = 140.0),
                        text = TextPayload(
                            value = RichTextValue.Plain(titleText),
                            style = "title",
                            morph = TextMorphSpec(
                                by = MorphBy.Word,
                                stagger = Stagger(
                                    ms = 15,
                                    manual = mapOf("function#0" to 240, "shader#0" to 120),
                                ),
                            ),
                        ),
                        builds = listOf(
                            Build.Change(
                                beat = 1,
                                set = buildJsonObject {
                                    putJsonObject("frame") { put("y", 60.0) }
                                },
                            ),
                        ),
                    ),
                    NodeSpec(
                        id = "plasma-node",
                        frame = FrameSpec(x = 120.0, y = 320.0, w = 720.0, h = 480.0),
                        specimen = SpecimenPayload(
                            ref = "studio.plasma",
                            params = mapOf("uSpeed" to kotlinx.serialization.json.JsonPrimitive(2.0)),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun writeDeckToFile(path: Path, doc: DeckDocument) {
        Files.writeString(path, CanonicalJson.print(doc), StandardCharsets.UTF_8)
    }

    // ── Conflict Flows (Unwritten & Overwrote), ExternalInvalid, Recovery Picker, Close Guard ──

    @Test
    fun unwrittenConflictKeepMineAndTakeTheirsAndCloseHoldDialog() = runBlocking {
        val tempDir = Files.createTempDirectory("ciacola-sync-unwritten")
        val deckFile = tempDir.resolve("sync-test.deck.json")
        val recoveryDir = tempDir.resolve("app-recovery")
        writeDeckToFile(deckFile, sampleDeck("Initial"))

        val store = DeckDocumentStore()
        val sync = DeckFileSync(
            targetFile = deckFile,
            recoveryRootDir = recoveryDir,
            store = store,
            specimenRegistry = testSpecimens(),
        )
        try {
            assertEquals(DeckLoadState.Loaded, sync.open())
            val syncController = DocumentSyncController(store, sync)

            // Make Studio dirty.
            store.edit("Studio edit") { d -> d.copy(title = "Studio Local Title") }
            assertTrue(store.isDirty)

            // External tool replaces file on disk before Studio writes -> Unwritten conflict!
            writeDeckToFile(deckFile, sampleDeck("External Edit").copy(title = "External Title"))
            sync.flushNow()
            val conflict = assertIs<SyncState.Conflict>(store.sync)
            assertEquals(ConflictKind.Unwritten, conflict.info.kind)
            assertTrue(syncController.shouldShowStickyBanner)

            // Closing with an unresolved conflict must hold close.
            var closed = false
            val flushResult = syncController.requestClose(onAllowClose = { closed = true })
            assertIs<CloseFlushResult.HeldConflict>(flushResult)
            assertFalse(closed, "Close must be held while conflict is unresolved")
            assertIs<CloseGuardState.Confirming>(syncController.closeGuard)

            // Cancel keeps window open & conflict active.
            syncController.cancelCloseGuard()
            assertEquals(CloseGuardState.Idle, syncController.closeGuard)
            assertIs<SyncState.Conflict>(store.sync)

            // Take theirs -> writes Studio's document to app-data recovery first, loads External Title!
            val takeResult = syncController.takeTheirs()
            assertTrue(takeResult.isSuccess, "Take theirs failed: ${takeResult.exceptionOrNull()}")
            assertEquals("External Title", store.document.title)
            assertEquals(SyncState.Saved, store.sync)
            assertTrue(Files.isRegularFile(takeResult.getOrThrow()), "Studio copy must be saved in app-data recovery")

            // Undo restores Studio's version!
            store.undo()
            assertEquals("Studio Local Title", store.document.title)

            // Now trigger Unwritten conflict again and test Keep mine -> quarantines external copy!
            writeDeckToFile(deckFile, sampleDeck("External Edit 2").copy(title = "External Title 2"))
            sync.flushNow()
            assertEquals(ConflictKind.Unwritten, assertIs<SyncState.Conflict>(store.sync).info.kind)

            val afterKeep = syncController.keepMine()
            assertEquals(SyncState.Saved, afterKeep)
            assertTrue(Files.readString(deckFile).contains("Studio Local Title"))

            // Recovery picker MUST list BOTH quarantine/ AND app-data recovery copies!
            val entries = syncController.refreshRecoveryEntries()
            assertTrue(entries.any { it.source == RecoverySource.Quarantine }, "Expected quarantine entry in $entries")
            assertTrue(entries.any { it.source == RecoverySource.AppDataRecovery }, "Expected app-data recovery entry in $entries")

            // Test Close-Hold "Save a recovery copy and close" flow!
            store.edit("Unsaved edit before close") { d -> d.copy(title = "Before Close Title") }
            writeDeckToFile(deckFile, sampleDeck("External 3").copy(title = "External 3"))
            val closeRes2 = syncController.requestClose(onAllowClose = { closed = true })
            assertIs<CloseFlushResult.HeldConflict>(closeRes2)
            assertFalse(closed)

            val savedCopy = syncController.saveRecoveryCopyAndClose(onAllowClose = { closed = true })
            assertTrue(savedCopy.isSuccess)
            assertTrue(closed, "Save a recovery copy and close must allow closing after writing recovery copy")
            assertTrue(Files.readString(savedCopy.getOrThrow()).contains("Before Close Title"))
        } finally {
            sync.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun overwroteConflictAndExternalInvalidFlows() = runBlocking {
        val tempDir = Files.createTempDirectory("ciacola-sync-overwrote")
        val deckFile = tempDir.resolve("overwrote.deck.json")
        val recoveryDir = tempDir.resolve("app-recovery")
        writeDeckToFile(deckFile, sampleDeck("Base"))

        val injectRace = AtomicBoolean(false)
        val store = DeckDocumentStore()
        val sync = DeckFileSync(
            targetFile = deckFile,
            recoveryRootDir = recoveryDir,
            store = store,
            specimenRegistry = testSpecimens(),
            stepHook = { step ->
                if (step == SyncStep.WriteAfterCheck && injectRace.compareAndSet(true, false)) {
                    // External writer sneaks in between step 2 hash check and step 4 atomic exchange!
                    writeDeckToFile(deckFile, sampleDeck("Raced External").copy(title = "Raced External"))
                }
            },
        )
        try {
            assertEquals(DeckLoadState.Loaded, sync.open())
            val syncController = DocumentSyncController(store, sync)

            // 1. Trigger Overwrote conflict: Studio wins target, displaced external file goes to .theirs!
            store.edit("Studio race edit") { d -> d.copy(title = "Studio Race Title") }
            injectRace.set(true)
            sync.flushNow()
            val conflict = assertIs<SyncState.Conflict>(store.sync)
            assertEquals(ConflictKind.Overwrote, conflict.info.kind)
            assertTrue(Files.readString(deckFile).contains("Studio Race Title"))
            assertTrue(Files.isRegularFile(Path.of(conflict.info.theirsPath)))

            // 2. If another tool modifies disk BEFORE "Take theirs", detect a fresh
            // Unwritten conflict instead of blindly overwriting!
            writeDeckToFile(deckFile, sampleDeck("Third Party").copy(title = "Third Party"))
            val takeResult = syncController.takeTheirs()
            assertTrue(takeResult.isSuccess)
            val freshConflict = assertIs<SyncState.Conflict>(store.sync)
            assertEquals(
                ConflictKind.Unwritten,
                freshConflict.info.kind,
                "Take theirs after Overwrote uses normal write protocol and must catch concurrent disk changes as Unwritten",
            )
            assertTrue(Files.readString(deckFile).contains("Third Party"), "Third party file must NOT be overwritten")

            // Resolve with Keep mine, then verify ExternalInvalid banner + Overwrite with Studio's version.
            assertEquals(SyncState.Saved, syncController.keepMine())
            Files.writeString(deckFile, "{ malformed json !!!", StandardCharsets.UTF_8)
            sync.pollExternalChangesNow()
            assertIs<SyncState.ExternalInvalid>(store.sync)
            assertTrue(syncController.shouldShowStickyBanner)

            val overwriteRes = syncController.overwriteExternalInvalid()
            assertTrue(overwriteRes.isSuccess)
            assertEquals(SyncState.Saved, store.sync)
            assertEquals("{ malformed json !!!", Files.readString(overwriteRes.getOrThrow()))
        } finally {
            sync.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun stickyConflictBannerRecoveryPickerAndCloseGuardComposeUi() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-sync-ui")
        val deckFile = tempDir.resolve("ui.deck.json")
        val recoveryDir = tempDir.resolve("app-recovery")
        writeDeckToFile(deckFile, sampleDeck("UI Base"))

        val store = DeckDocumentStore()
        val sync = DeckFileSync(deckFile, recoveryDir, store, specimenRegistry = testSpecimens())
        runBlocking {
            sync.open()
            store.edit("Edit") { it.copy(title = "Mine UI") }
            writeDeckToFile(deckFile, sampleDeck("Theirs UI").copy(title = "Theirs UI"))
            sync.flushNow()
        }
        val controller = DocumentSyncController(store, sync)
        var closeAllowed = false

        try {
            setContent {
                IntUiTheme {
                    DocumentLoadStateGate(
                        store = store,
                        controller = controller,
                        onAllowClose = { closeAllowed = true },
                    ) {
                        org.jetbrains.jewel.ui.component.Text("Stage Canvas")
                    }
                }
            }

            waitForIdle()
            onNodeWithTag(SyncUiTestTags.STICKY_BANNER).assertExists()
            onNodeWithTag(SyncUiTestTags.BTN_KEEP_MINE).assertExists()
            onNodeWithTag(SyncUiTestTags.BTN_TAKE_THEIRS).assertExists()
            onNodeWithTag(SyncUiTestTags.BTN_OPEN_RECOVERY).assertExists()

            // Trigger close-hold dialog and verify Cancel + Save a recovery copy and close buttons render.
            runBlocking { controller.requestClose(onAllowClose = { closeAllowed = true }) }
            waitForIdle()
            onNodeWithTag(SyncUiTestTags.CLOSE_GUARD_DIALOG).assertExists()
            onNodeWithTag(SyncUiTestTags.BTN_SAVE_RECOVERY_AND_CLOSE).assertExists()
            onNodeWithTag(SyncUiTestTags.BTN_CANCEL_CLOSE).performClick()
            waitForIdle()
            assertFalse(closeAllowed)
            assertEquals(CloseGuardState.Idle, controller.closeGuard)

            // Open recovery picker from sticky banner.
            onNodeWithTag(SyncUiTestTags.BTN_OPEN_RECOVERY).performClick()
            waitForIdle()
            onNodeWithTag(SyncUiTestTags.RECOVERY_PICKER).assertExists()
            onNodeWithText("Open recovery copy", substring = false).assertExists()
        } finally {
            sync.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    // ── Undo Semantics: 2s Pause Drag, Quick Drags, Esc Cancel, Focused Text, Unified Stack ──

    @Test
    fun sliderDrag2sPauseOneStepTwoQuickDragsTwoStepsEscCancelAndFocusedTextLocalUndo() {
        val store = DeckDocumentStore()
        store.loadInitial(sampleDeck())
        val elementController = DocumentElementInspectorController(
            store = store,
            specimens = testSpecimens(),
            initialSlideId = "s1",
            initialBeat = 0,
        )
        elementController.selectNode("headline")
        val initialDoc = store.document

        // 1. Slider drag with a real 2-second pause = EXACTLY 1 undo step!
        assertTrue(elementController.beginGesture("Adjust opacity"))
        assertTrue(elementController.setOpacity(0.8, useActiveGesture = true))
        Thread.sleep(2_050L)
        assertTrue(elementController.setOpacity(0.45, useActiveGesture = true))
        elementController.commitGesture()
        assertEquals(0.45, elementController.selectedResolvedNode()?.opacity)

        // Single undo restores 1.0 completely!
        store.undo()
        assertEquals(initialDoc, store.document)
        assertFalse(store.canUndo)

        // 2. Two quick drags = 2 separate undo steps!
        assertTrue(elementController.beginGesture("Drag 1"))
        assertTrue(elementController.setOpacity(0.75, useActiveGesture = true))
        elementController.commitGesture()

        assertTrue(elementController.beginGesture("Drag 2"))
        assertTrue(elementController.setOpacity(0.35, useActiveGesture = true))
        elementController.commitGesture()

        store.undo()
        assertEquals(0.75, elementController.selectedResolvedNode()?.opacity)
        store.undo()
        assertEquals(initialDoc, store.document)

        // 3. Esc mid-drag restores exact prior document with zero history entries!
        assertTrue(elementController.beginGesture("Drag to cancel"))
        assertTrue(elementController.setOpacity(0.2, useActiveGesture = true))
        assertEquals(0.2, elementController.selectedResolvedNode()?.opacity)
        assertTrue(elementController.handleShortcut(Key.Escape))
        assertEquals(initialDoc, store.document)
        assertFalse(store.canUndo)

        // 4. Focused inline text field keeps local ⌘Z (does NOT pop DeckDocumentStore undo stack)!
        assertTrue(elementController.setOpacity(0.85))
        assertTrue(store.canUndo)
        assertTrue(elementController.startInlineTextEdit("headline"))
        assertTrue(elementController.textInputHasFocus)
        // Pressing ⌘Z while text field has focus MUST return false and leave opacity = 0.85!
        assertFalse(
            elementController.handleShortcut(Key.Z, isCommandDown = true),
            "⌘Z must stay local while text input is focused",
        )
        assertEquals(0.85, elementController.selectedResolvedNode()?.opacity)

        // 5. Commit inline text edit via UTF-16 range provenance -> remaps/prunes manual delays with notice!
        // Original: "A shader is a function" (has manual delays for "function#0" and "shader#0").
        // Replace "function" (index 14..22) with "pure lambda" -> "function#0" is pruned and triggers notice!
        assertTrue(
            elementController.commitInlineTextEdit(
                newText = "A shader is a pure lambda",
                explicitRangeEdit = TextRangeEdit(start = 14, removedLength = 8, insertedText = "pure lambda"),
            ),
        )
        assertFalse(elementController.textInputHasFocus)
        val updatedHeadline = elementController.selectedResolvedNode()
        assertEquals("A shader is a pure lambda", updatedHeadline?.text?.value?.plainText)
        // "shader#0" survived untouched, "function#0" was pruned with a user notice!
        assertEquals(120, updatedHeadline?.text?.morph?.stagger?.manual?.get("shader#0"))
        assertNull(updatedHeadline?.text?.morph?.stagger?.manual?.get("function#0"))
        assertTrue(
            elementController.remapNotices.any { it.oldAddress == "function#0" },
            "Pruning manual fragment delay must surface a user notice banner/toast",
        )

        // Now that focus left the text field, ⌘Z DOES undo the committed inline text edit!
        assertTrue(elementController.handleShortcut(Key.Z, isCommandDown = true))
        assertEquals("A shader is a function", elementController.selectedResolvedNode()?.text?.value?.plainText)
    }

    @Test
    fun interleavedElementMotionThemeEditsBeatContractSpecimenParamsAndCopyThemes() {
        val store = DeckDocumentStore()
        store.loadInitial(sampleDeck())
        val elementController = DocumentElementInspectorController(
            store = store,
            specimens = testSpecimens(),
            initialSlideId = "s1",
            initialBeat = 0,
        )
        val themeController = DocumentThemeInspectorController(store = store)

        // ── 1. Interleaved Element + Motion + Theme edits on ONE unified undo stack ──
        elementController.selectNode("headline")
        // Element edit: stage drag snapped to 0.5 units!
        assertTrue(elementController.commitStageDrag("headline", deltaX = 10.3, deltaY = -5.7))
        assertEquals(130.5, elementController.selectedResolvedNode()?.frame?.x)
        assertEquals(94.5, elementController.selectedResolvedNode()?.frame?.y)

        // Motion edit directly on store (same unified stack as Timeline):
        assertTrue(
            store.edit("Motion stagger edit") { doc ->
                val s = doc.slides[0]
                val beats = s.beats.toMutableList()
                beats[1] = beats[1].copy(stagger = Stagger(ms = 115))
                doc.copy(slides = listOf(s.copy(beats = beats)))
            },
        )

        // Theme edit via DocumentThemeInspectorController:
        assertTrue(themeController.setThemeColor("accent", "#F97316"))
        assertEquals("#F97316", store.document.themes["dark"]?.colors?.get("accent"))

        // Unwind all 3 interleaved edits in reverse order on the ONE undo stack!
        store.undo() // undoes Theme
        assertEquals("#38BDF8", store.document.themes["dark"]?.colors?.get("accent"))
        assertEquals(115, store.document.slides[0].beats[1].stagger?.ms)

        store.undo() // undoes Motion
        assertEquals(40, store.document.slides[0].beats[1].stagger?.ms)
        assertEquals(130.5, elementController.selectedResolvedNode()?.frame?.x)

        store.undo() // undoes Element
        assertEquals(120.0, elementController.selectedResolvedNode()?.frame?.x)
        assertFalse(store.canUndo)

        // Redo replays Element -> Motion -> Theme!
        store.redo()
        store.redo()
        store.redo()
        assertEquals("#F97316", store.document.themes["dark"]?.colors?.get("accent"))

        // ── 2. Beat-Editing Contract + Declared Specimen Params ──
        elementController.selectLocation("s1", beat = 1)
        elementController.selectNode("plasma-node")

        // Edit all 6 declared specimen ParamSpec types on beat 1 (quantized to step & clamped to range):
        val plasmaDecl = assertNotNull(elementController.selectedSpecimenDeclaration())
        val uSpeedSpec = assertIs<ParamSpec.FloatParam>(plasmaDecl.param("uSpeed"))
        val octavesSpec = assertIs<ParamSpec.IntParam>(plasmaDecl.param("octaves"))
        val invertSpec = assertIs<ParamSpec.BooleanParam>(plasmaDecl.param("invert"))
        val tintSpec = assertIs<ParamSpec.ColorParam>(plasmaDecl.param("tint"))
        val modeSpec = assertIs<ParamSpec.ChoiceParam>(plasmaDecl.param("mode"))
        val labelSpec = assertIs<ParamSpec.StringParam>(plasmaDecl.param("label"))

        assertTrue(elementController.setSpecimenFloatParam(uSpeedSpec, 3.33f))
        assertTrue(elementController.setSpecimenIntParam(octavesSpec, 6))
        assertTrue(elementController.setSpecimenBooleanParam(invertSpec, true))
        assertTrue(elementController.setSpecimenColorParam(tintSpec, "#22C55E"))
        assertTrue(elementController.setSpecimenChoiceParam(modeSpec, "ridged"))
        assertTrue(elementController.setSpecimenStringParam(labelSpec, "Custom"))

        val resolvedB1 = elementController.selectedResolvedNode()!!
        assertEquals(3.25, resolvedB1.specimen?.params?.get("uSpeed")?.jsonPrimitive?.doubleOrNull)
        assertEquals("ridged", resolvedB1.specimen?.params?.get("mode")?.jsonPrimitive?.content)

        // Reset on beat 1 writes JSON `null` into beat 1's `change.set`!
        assertTrue(elementController.resetSpecimenParam("uSpeed"))
        val basePlasma = elementController.selectedBaseNode()!!
        val beat1Change = basePlasma.builds.filterIsInstance<Build.Change>().last { it.beat == 1 }
        val setSpecimenParams = (beat1Change.set["specimen"] as? kotlinx.serialization.json.JsonObject)
            ?.get("params") as? kotlinx.serialization.json.JsonObject
        assertEquals(JsonNull, setSpecimenParams?.get("uSpeed"), "Reset on beat > 0 must write JSON null in set")
        assertNull(
            JsonMergePatch.resolveNodeAtBeat(basePlasma, 1).specimen?.params?.get("uSpeed"),
            "Resolved node at beat 1 must omit reset param (falls back to Kotlin default, NOT base)",
        )

        // Apply to all beats updates base AND strips key from later beats' sets!
        elementController.selectNode("headline")
        val yPatch = buildJsonObject { putJsonObject("frame") { put("y", 48.0) } }
        assertTrue(elementController.applyPatchToAllBeats("Apply y to all beats", yPatch))
        val headlineBase = elementController.selectedBaseNode()!!
        assertEquals(48.0, headlineBase.frame?.y)
        assertEquals(48.0, JsonMergePatch.resolveNodeAtBeat(headlineBase, 1).frame?.y)
        assertEquals(48.0, JsonMergePatch.resolveNodeAtBeat(headlineBase, 2).frame?.y)

        // Morph granularity edit works cleanly:
        assertTrue(elementController.setTextMorphGranularity(TextMorphGranularity.Character))
        assertEquals(MorphBy.Character, elementController.selectedResolvedNode()?.text?.morph?.by)

        // ── 3. Copy themes from deck ──
        val sourceDeck = DeckDocument(
            id = "other-deck",
            themes = mapOf(
                "editorial" to DeckThemeSpec(
                    preset = "keynoteLight",
                    colors = mapOf("accent" to "#BE123C"),
                    motion = dev.sebastiano.ciacola.document.ThemeMotionDefaults(
                        durationMs = 420,
                        curve = Curve.EaseOut,
                    ),
                ),
            ),
            defaultTheme = "editorial",
        )
        assertTrue(themeController.copyThemesFromDocument(sourceDeck, replaceExisting = false))
        assertNotNull(store.document.themes["editorial"])
        assertEquals("#BE123C", store.document.themes["editorial"]?.colors?.get("accent"))
        // Single undo removes copied themes cleanly!
        store.undo()
        assertNull(store.document.themes["editorial"])
    }
}
