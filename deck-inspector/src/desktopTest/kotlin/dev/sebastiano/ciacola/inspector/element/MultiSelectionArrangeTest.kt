package dev.sebastiano.ciacola.inspector.element

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun arrangeTestDocument(): DeckDocument = DeckDocument(
    id = "arrange-doc",
    title = "Arrange",
    defaultTheme = "dark",
    themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
    slides = listOf(
        SlideSpec(
            id = "s1",
            background = Fill.Solid("#111827"),
            beats = listOf(BeatSpec(), BeatSpec()),
            nodes = listOf(
                NodeSpec(
                    id = "a",
                    frame = FrameSpec(x = 100.0, y = 100.0, w = 200.0, h = 100.0),
                    text = TextPayload(value = RichTextValue.Plain("Hello stage world"), size = 40.0),
                ),
                NodeSpec(
                    id = "b",
                    frame = FrameSpec(x = 400.0, y = 300.0, w = 100.0, h = 50.0),
                    shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FF0000")),
                ),
                NodeSpec(
                    id = "c",
                    frame = FrameSpec(x = 900.0, y = 600.0, w = 300.0, h = 120.0),
                    shape = ShapePayload(geometry = ShapeGeometry.Ellipse),
                ),
                NodeSpec(
                    id = "late",
                    frame = FrameSpec(x = 50.0, y = 800.0, w = 100.0, h = 100.0),
                    shape = ShapePayload(geometry = ShapeGeometry.Rect),
                    builds = listOf(Build.Enter(beat = 1, effect = "dissolve")),
                ),
            ),
        ),
    ),
)

private fun frameOf(store: DeckDocumentStore, id: String, beat: Int = 0): FrameSpec {
    val node = store.document.slides.first().nodes.first { it.id == id }
    return dev.sebastiano.ciacola.document.JsonMergePatch.resolveNodeAtBeat(node, beat).frame!!
}

@OptIn(InternalComposeUiApi::class)
private fun key(k: Key, type: KeyEventType = KeyEventType.KeyDown, alt: Boolean = false, shift: Boolean = false, meta: Boolean = false): KeyEvent =
    KeyEvent(key = k, type = type, isAltPressed = alt, isShiftPressed = shift, isMetaPressed = meta)

class MultiSelectionArrangeTest {

    private fun controller(): Pair<DeckDocumentStore, DocumentElementInspectorController> {
        val store = DeckDocumentStore(arrangeTestDocument(), initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        return store to c
    }

    @Test
    fun shiftClickTogglesMultiSelectionAndReportsPrimaryChanges() {
        val (_, c) = controller()
        val changes = mutableListOf<String?>()
        c.onSelectionChanged = { changes += it }

        c.selectNode("a")
        c.selectNode("b", additive = true)
        assertEquals(setOf("a", "b"), c.selectedNodeIds)
        assertEquals("b", c.selectedNodeId)

        c.selectNode("b", additive = true)
        assertEquals(setOf("a"), c.selectedNodeIds)
        assertEquals("a", c.selectedNodeId)

        c.selectNodes(setOf("b", "c"), primaryId = "c")
        assertEquals("c", c.selectedNodeId)
        c.selectNode(null)
        assertTrue(c.selectedNodeIds.isEmpty())
        assertEquals(listOf("a", "b", "a", "c", null), changes)

        // Legacy setter replaces the whole multi-selection.
        c.selectNodes(setOf("a", "b"))
        c.selectedNodeId = "c"
        assertEquals(setOf("c"), c.selectedNodeIds)
    }

    @Test
    fun cmdASelectsTopLevelNodesPresentOnTheCurrentBeat() {
        val (_, c) = controller()
        assertTrue(c.handleKeyEvent(key(Key.A, meta = true)))
        assertEquals(setOf("a", "b", "c"), c.selectedNodeIds)

        c.selectLocation("s1", 1)
        assertTrue(c.selectAllOnCurrentSlide())
        assertEquals(setOf("a", "b", "c", "late"), c.selectedNodeIds)

        c.textInputHasFocus = true
        c.selectNode(null)
        assertFalse(c.handleKeyEvent(key(Key.A, meta = true)), "Cmd+A must stay with a focused text field")
    }

    @Test
    fun marqueeSelectsIntersectingTopLevelNodesOnly() {
        fun snap(id: String, r: Rect, parent: String? = null) = StageNodeSnapshot(
            elementId = id, kind = "Shape", label = id, sceneIndex = 0, beatIndex = 0, stageBounds = r, parentElementId = parent,
        )
        val nodes = listOf(
            snap("a", Rect(100f, 100f, 300f, 200f)),
            snap("b", Rect(400f, 300f, 500f, 350f)),
            snap("c", Rect(900f, 600f, 1200f, 720f)),
            snap("child", Rect(120f, 120f, 160f, 160f), parent = "a"),
        )
        assertEquals(listOf("a", "b"), StageSelectionModel.resolveMarquee(Rect(450f, 320f, 50f, 50f), nodes))
        assertTrue(StageSelectionModel.resolveMarquee(Rect(600f, 400f, 700f, 500f), nodes).isEmpty())

        val ctx = StageSelectionModel.toggleAdditive(StageSelectionContext(selectedElementId = "a"), "b")
        assertEquals(setOf("a", "b"), ctx.selectedElementIds)
        assertEquals("b", ctx.selectedElementId)
        val back = StageSelectionModel.toggleAdditive(ctx, "b")
        assertEquals("a", back.selectedElementId)
        assertEquals(setOf("a"), StageSelectionContext(selectedElementId = "a").selectedElementIds)
    }

    @Test
    fun arrowNudgesAreOneTenAndTwentyFivePixelsAndEachIsOneUndoStep() {
        val (store, c) = controller()
        c.selectNodes(setOf("a", "b"))

        assertTrue(c.handleKeyEvent(key(Key.DirectionRight)))
        assertEquals(101.0, frameOf(store, "a").x)
        assertEquals(401.0, frameOf(store, "b").x)

        assertTrue(c.handleKeyEvent(key(Key.DirectionRight, alt = true)))
        assertEquals(111.0, frameOf(store, "a").x)

        assertTrue(c.handleKeyEvent(key(Key.DirectionDown, shift = true)))
        assertEquals(110.0, frameOf(store, "a").y)
        assertEquals(310.0, frameOf(store, "b").y)

        assertTrue(c.handleKeyEvent(key(Key.DirectionLeft, alt = true, shift = true)))
        assertEquals(86.0, frameOf(store, "a").x)
        assertEquals(386.0, frameOf(store, "b").x)

        assertTrue(c.handleKeyEvent(key(Key.DirectionLeft, type = KeyEventType.KeyUp)), "Key-up is consumed so slides never advance")

        store.undo()
        assertEquals(111.0, frameOf(store, "a").x)
        assertEquals(411.0, frameOf(store, "b").x, "Alt+Shift nudge of both nodes reverts in ONE undo step")
        assertEquals(StageArrangeMath.NUDGE_SMALL, StageArrangeMath.nudgeStep(isAltPressed = false, isShiftPressed = false))
        assertEquals(StageArrangeMath.NUDGE_BIG, StageArrangeMath.nudgeStep(isAltPressed = true, isShiftPressed = false))
        assertEquals(StageArrangeMath.NUDGE_BIG, StageArrangeMath.nudgeStep(isAltPressed = false, isShiftPressed = true))
        assertEquals(StageArrangeMath.NUDGE_HUGE, StageArrangeMath.nudgeStep(isAltPressed = true, isShiftPressed = true))

        c.textInputHasFocus = true
        assertFalse(c.handleKeyEvent(key(Key.DirectionRight)), "Arrows belong to the focused text field")
        c.textInputHasFocus = false
        c.selectNode(null)
        assertFalse(c.handleKeyEvent(key(Key.DirectionRight)), "Without a selection arrows stay with slide navigation")
    }

    @Test
    fun nudgeOnBuildBeatWritesChangePatchAndLeavesBaseFrame() {
        val (store, c) = controller()
        c.selectLocation("s1", 1)
        c.selectNodes(setOf("a", "late"))
        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        val a = store.document.slides.first().nodes.first { it.id == "a" }
        assertEquals(100.0, a.frame?.x, "Base frame untouched on beat 1")
        val change = a.builds.filterIsInstance<Build.Change>().single { it.beat == 1 }
        assertEquals(110.0, change.set["frame"]!!.jsonObject["x"]!!.jsonPrimitive.content.toDouble())
        assertEquals(60.0, frameOf(store, "late", 1).x)
        store.undo()
        assertTrue(store.document.slides.first().nodes.first { it.id == "a" }.builds.none { it is Build.Change })
    }

    @Test
    fun allSixAlignmentsUseSelectionBoundsOrStageAndAreSingleUndoSteps() {
        val (store, c) = controller()
        c.selectNodes(setOf("a", "b"))
        val expectations = mapOf(
            NodeAlignment.Left to listOf(100.0 to null, 100.0 to null),
            NodeAlignment.Right to listOf(300.0 to null, 400.0 to null),
            NodeAlignment.CenterH to listOf(200.0 to null, 250.0 to null),
            NodeAlignment.Top to listOf(null to 100.0, null to 100.0),
            NodeAlignment.Bottom to listOf(null to 250.0, null to 300.0),
            NodeAlignment.CenterV to listOf(null to 175.0, null to 200.0),
        )
        for ((alignment, exp) in expectations) {
            assertTrue(c.alignSelectedNodes(alignment), "$alignment should move something")
            val fa = frameOf(store, "a")
            val fb = frameOf(store, "b")
            exp[0].first?.let { assertEquals(it, fa.x, "$alignment a.x") }
            exp[0].second?.let { assertEquals(it, fa.y, "$alignment a.y") }
            exp[1].first?.let { assertEquals(it, fb.x, "$alignment b.x") }
            exp[1].second?.let { assertEquals(it, fb.y, "$alignment b.y") }
            store.undo()
            assertEquals(100.0, frameOf(store, "a").x)
            assertEquals(400.0, frameOf(store, "b").x)
            assertEquals(300.0, frameOf(store, "b").y, "$alignment reverts in one undo step")
        }

        c.selectNode("b")
        assertTrue(c.alignSelectedNodes(NodeAlignment.Right))
        assertEquals(1820.0, frameOf(store, "b").x, "Single node aligns to the 1920-wide stage")
        assertTrue(c.alignSelectedNodes(NodeAlignment.CenterV))
        assertEquals(515.0, frameOf(store, "b").y)
    }

    @Test
    fun distributeEvenlySpacesThreeNodesPinningOutermostOnes() {
        val (store, c) = controller()
        c.selectNodes(setOf("a", "b"))
        assertFalse(c.distributeSelectedNodes(DistributeAxis.Horizontal), "Needs three or more nodes")

        c.selectNodes(setOf("a", "b", "c"))
        assertTrue(c.distributeSelectedNodes(DistributeAxis.Horizontal))
        // Span 100..1200 = 1100, widths 600 → gap 250: a@100, b@550, c@900.
        assertEquals(100.0, frameOf(store, "a").x)
        assertEquals(550.0, frameOf(store, "b").x)
        assertEquals(900.0, frameOf(store, "c").x)
        store.undo()
        assertEquals(400.0, frameOf(store, "b").x)

        assertTrue(c.distributeSelectedNodes(DistributeAxis.Vertical))
        // Span 100..720 = 620, heights 270 → gap 175: a@100, b@375, c@600.
        assertEquals(375.0, frameOf(store, "b").y)
        assertEquals(600.0, frameOf(store, "c").y)

        val deltas = StageArrangeMath.distributionDeltas(
            mapOf("x" to Rect(0f, 0f, 10f, 10f), "y" to Rect(15f, 0f, 25f, 10f), "z" to Rect(90f, 0f, 100f, 10f)),
            DistributeAxis.Horizontal,
        )
        assertEquals(Offset.Zero, deltas["x"])
        assertEquals(Offset(30f, 0f), deltas["y"])
        assertEquals(Offset.Zero, deltas["z"])
    }

    @Test
    fun sharedDragMovesEverySelectedNodeInOneUndoStep() {
        val (store, c) = controller()
        c.selectNodes(setOf("a", "b"))
        c.beginMultiDrag("b")
        assertEquals(setOf("a", "b"), c.dragPreviewNodeIds)
        c.updateMultiDrag(20.0, -10.0)
        assertEquals(100.0, frameOf(store, "a").x, "Preview never writes the document")
        assertTrue(c.commitMultiDrag(20.0, -10.0))
        assertEquals(120.0, frameOf(store, "a").x)
        assertEquals(290.0, frameOf(store, "b").y)
        assertTrue(c.dragPreviewNodeIds.isEmpty())
        store.undo()
        assertEquals(100.0, frameOf(store, "a").x)
        assertEquals(300.0, frameOf(store, "b").y)

        c.beginMultiDrag("c")
        assertEquals(setOf("c"), c.dragPreviewNodeIds, "Grabbing an unselected node drags only that node")
        c.cancelStageDragPreview()
    }

    @Test
    fun mixedValueGridCommitsAcrossSelectionAndGuardsPayloadKinds() {
        val (store, c) = controller()
        c.selectNodes(setOf("a", "b"), primaryId = "a")
        val targets = c.editTargets()
        val xEditor = GridPropertyEditor(c, LayoutXProperty, targets)
        assertNull(xEditor.sharedValue(), "Different X values read as Mixed")
        xEditor.commit(640.0)
        assertEquals(640.0, frameOf(store, "a").x)
        assertEquals(640.0, frameOf(store, "b").x)
        store.undo()
        assertEquals(100.0, frameOf(store, "a").x)
        assertEquals(400.0, frameOf(store, "b").x, "Mixed commit is ONE undo step")

        val stepEditor = GridPropertyEditor(c, LayoutYProperty, c.editTargets())
        stepEditor.step(5.0)
        assertEquals(105.0, frameOf(store, "a").y)
        assertEquals(305.0, frameOf(store, "b").y, "Arrow step keeps relative offsets")

        val scrub = GridPropertyEditor(c, LayoutWProperty, c.editTargets())
        scrub.scrubStart()
        scrub.scrub(4.0)
        scrub.scrub(12.0)
        scrub.scrubEnd()
        assertEquals(212.0, frameOf(store, "a").w)
        assertEquals(112.0, frameOf(store, "b").w)
        store.undo()
        assertEquals(200.0, frameOf(store, "a").w, "Scrub gesture reverts in one step")

        // Text edits on a mixed selection only touch the text node; the shape stays valid.
        assertTrue(c.setTextProperty("size", JsonPrimitive(48.0)))
        val nodes = store.document.slides.first().nodes
        assertEquals(48.0, nodes.first { it.id == "a" }.text?.size)
        assertNull(nodes.first { it.id == "b" }.text)
        assertNotNull(nodes.first { it.id == "b" }.shape)
    }

    @Test
    fun allBuildsToggleRoutesEditsToEveryBuild() {
        val (store, c) = controller()
        c.selectLocation("s1", 1)
        c.selectNode("b")
        c.editAllBuilds = true
        assertTrue(c.setOpacity(0.5))
        val b = store.document.slides.first().nodes.first { it.id == "b" }
        assertEquals(0.5, b.opacity, "All builds writes the base value")
        assertTrue(b.builds.none { it is Build.Change })
    }

    @Test
    fun nudgeOnBuildBeatWithAllBuildsOffsetsBaseAndBuildChangesRelatively() {
        val animatedDoc = DeckDocument(
            id = "rel-nudge",
            title = "Relative Nudge",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    background = Fill.Solid("#111827"),
                    beats = listOf(BeatSpec(), BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "mover",
                            frame = FrameSpec(x = 100.0, y = 200.0, w = 200.0, h = 100.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FF0000")),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    set = buildJsonObject {
                                        put("frame", buildJsonObject { put("x", 500.0) })
                                    },
                                ),
                                Build.Change(
                                    beat = 2,
                                    set = buildJsonObject {
                                        put("frame", buildJsonObject { put("y", 450.0) })
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(animatedDoc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 1)
        c.selectNode("mover")
        c.editAllBuilds = true

        assertTrue(c.nudgeSelectedNodes(10.0, 20.0))

        assertEquals(110.0, frameOf(store, "mover", 0).x, "Base x must move relatively from 100 to 110")
        assertEquals(220.0, frameOf(store, "mover", 0).y, "Base y must move relatively from 200 to 220")
        assertEquals(510.0, frameOf(store, "mover", 1).x, "Build 1 x must move relatively from 500 to 510")
        assertEquals(220.0, frameOf(store, "mover", 1).y, "Build 1 inherits y=220")
        assertEquals(510.0, frameOf(store, "mover", 2).x, "Build 2 inherits x=510")
        assertEquals(470.0, frameOf(store, "mover", 2).y, "Build 2 y must move relatively from 450 to 470")

        store.undo()
        assertEquals(100.0, frameOf(store, "mover", 0).x)
        assertEquals(500.0, frameOf(store, "mover", 1).x)
        assertEquals(450.0, frameOf(store, "mover", 2).y)
    }

    @Test
    fun dimensionPillPlacementAvoidsOtherNodes() {
        val stage = Size(1000f, 600f)
        val sel = Rect(400f, 200f, 600f, 300f)
        val pill = Size(100f, 20f)
        val below = computeDimensionPillPlacement(sel, pill, stage, obstacles = emptyList(), gapPx = 8f)
        assertEquals(308f, below.y)
        val blockedBelow = computeDimensionPillPlacement(sel, pill, stage, listOf(Rect(0f, 305f, 1000f, 340f)), 8f)
        assertEquals(172f, blockedBelow.y, "Falls back to above")
        val blockedBoth = computeDimensionPillPlacement(sel, pill, stage, listOf(Rect(0f, 305f, 1000f, 340f), Rect(0f, 160f, 1000f, 195f)), 8f)
        assertEquals(Offset(408f, 208f), blockedBoth, "Falls back to inside top-left")
        val nearBottom = computeDimensionPillPlacement(Rect(400f, 560f, 600f, 590f), pill, stage, emptyList(), 8f)
        assertTrue(nearBottom.y < 560f, "Never clips below the stage")

        val box = Rect(100f, 100f, 300f, 200f)
        val nw = stageHandleCenter(StageResizeHandle.NW, box, outsetPx = 4f, handlePx = 8f)
        assertEquals(Offset(92f, 92f), nw, "Handle sits fully outside the frame")
        val e = stageHandleCenter(StageResizeHandle.E, box, outsetPx = 4f, handlePx = 8f)
        assertEquals(Offset(308f, 150f), e)
    }

    @Test
    fun displayNamesAndGoToBuildBeat() {
        val doc = arrangeTestDocument()
        val nodes = doc.slides.first().nodes
        assertEquals("Hello stage world", InspectorDisplayNames.nodeDisplayName(nodes[0]))
        assertEquals("Rectangle", InspectorDisplayNames.nodeDisplayName(nodes[1]))
        assertEquals("Ellipse", InspectorDisplayNames.nodeDisplayName(nodes[2]))
        assertEquals(1, InspectorDisplayNames.firstPresentBeat(nodes[3]))
        assertEquals("Elevated Surface", InspectorDisplayNames.humanizeIdentifier("elevatedSurface"))
        assertEquals("On Accent", InspectorDisplayNames.humanizeIdentifier("onAccent"))
        assertEquals("A long sentence that is cut…", InspectorDisplayNames.excerpt("A long sentence that is cut off somewhere"))
        assertEquals("s1/a", InspectorDisplayNames.rawReference("s1", "a"))
        val absentEmptyGroup = NodeSpec(
            id = "s1:shared-card",
            stack = ContainerPayload(),
            builds = listOf(Build.Enter(beat = 1, effect = "zoom")),
        )
        assertEquals("Group", InspectorDisplayNames.nodeTypeLabel(absentEmptyGroup))
        assertEquals("Group", InspectorDisplayNames.nodeDisplayName(absentEmptyGroup))
        val populatedRow = NodeSpec(
            id = "s1:row",
            row = ContainerPayload(children = listOf(nodes[0], nodes[1])),
        )
        assertEquals("Row", InspectorDisplayNames.nodeTypeLabel(populatedRow))
        assertEquals("Row · 2 items", InspectorDisplayNames.nodeDisplayName(populatedRow))
        assertEquals(null, sharedValueOrMixed(listOf(1.0, 2.0)))
        assertEquals(3.0, sharedValueOrMixed(listOf(3.0, 3.0)))
        assertEquals(12.5, parseNumericInput("12,5°"))
        assertEquals("12.5", formatCompactNumber(12.5))
        assertEquals("12", formatCompactNumber(12.0))
        assertEquals(10.0, scrubMultiplier(isShiftPressed = true, isAltPressed = false))
        assertEquals(0.1, scrubMultiplier(isShiftPressed = false, isAltPressed = true))
    }

    @Test
    fun editingSxOnAMixedSelectionPreservesEachNodesOwnSy() {
        val (store, c) = controller()
        c.applyPatchesToNodes(
            "Seed scales",
            mapOf(
                "a" to buildJsonObject { put("scale", buildJsonObject { put("x", 1.0); put("y", 0.5) }) },
                "b" to buildJsonObject { put("scale", buildJsonObject { put("x", 2.0); put("y", 1.5) }) },
            ),
        )
        c.selectNodes(setOf("a", "b"), primaryId = "a")
        fun scaleOf(id: String) = store.document.slides.first().nodes.first { it.id == id }.scale!!
        GridPropertyEditor(c, NonUniformScaleXProperty, c.editTargets()).commit(150.0)
        assertEquals(1.5, scaleOf("a").scaleX)
        assertEquals(0.5, scaleOf("a").scaleY, "a keeps its own SY")
        assertEquals(1.5, scaleOf("b").scaleX)
        assertEquals(1.5, scaleOf("b").scaleY, "b keeps its own SY")

        // SX/SY show Mixed when the selection disagrees.
        assertNull(GridPropertyEditor(c, NonUniformScaleYProperty, c.editTargets()).sharedValue())

        // Turning `Scale X/Y separately` off converts each node from its OWN scale.
        c.applyPatchesToNodes("Uniform", c.editTargets().associate { it.id to scaleModePatch(it, separate = false) })
        assertEquals(dev.sebastiano.ciacola.document.ScaleValue.Uniform(1.5), scaleOf("a"))
        assertEquals(dev.sebastiano.ciacola.document.ScaleValue.Uniform(1.5), scaleOf("b"))
    }
}
