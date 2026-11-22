package dev.sebastiano.ciacola.inspector.element

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(InternalComposeUiApi::class)
private fun keyDown(k: Key, meta: Boolean = false): KeyEvent =
    KeyEvent(key = k, type = KeyEventType.KeyDown, isMetaPressed = meta)

/** Edge-case tests for inspector interactions: single-axis nudges, absent marquee picks, key yielding, and all-builds resets. */
class InspectorEdgeCasesTest {

    private fun controller(): Pair<DeckDocumentStore, DocumentElementInspectorController> {
        val store = DeckDocumentStore(arrangeTestDocument(), initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        return store to c
    }

    private fun node(store: DeckDocumentStore, id: String) = store.document.slides.first().nodes.first { it.id == id }

    @Test
    fun horizontalNudgeOnABuildWritesOnlyXAndNeverResnapsAnOffGridY() {
        val (store, c) = controller()
        c.applyPatchesToNodes("Off-grid y", mapOf("b" to buildJsonObject { put("frame", buildJsonObject { put("y", 300.3) }) }))
        c.selectLocation("s1", 1)
        c.selectNode("b")
        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        val b = node(store, "b")
        val change = b.builds.filterIsInstance<Build.Change>().last { it.beat == 1 }
        val frame = change.set["frame"] as JsonObject
        assertTrue("x" in frame, "The moved axis is written")
        assertFalse("y" in frame, "The untouched axis must not be pinned into Build 1: $frame")
        assertEquals(300.3, JsonMergePatch.resolveNodeAtBeat(b, 1).frame?.y, "Off-grid y is never re-snapped")
        assertEquals(410.0, JsonMergePatch.resolveNodeAtBeat(b, 1).frame?.x)
    }

    @Test
    fun marqueeNeverPicksNodesAbsentOnTheCurrentBuild() {
        assertEquals(listOf("a"), filterMarqueePicks(listOf("a", "late", "child"), topLevelIds = setOf("a", "late"), presentIds = setOf("a")))
        // Zero-width or zero-height marquees short-circuit.
        val snap = StageNodeSnapshot(elementId = "a", kind = "Shape", label = "a", sceneIndex = 0, beatIndex = 0, stageBounds = Rect(0f, 0f, 100f, 100f))
        assertTrue(StageSelectionModel.resolveMarquee(Rect(10f, 10f, 10f, 90f), listOf(snap)).isEmpty())
        assertTrue(StageSelectionModel.resolveMarquee(Rect(10f, 10f, 90f, 10f), listOf(snap)).isEmpty())
    }

    @Test
    fun stageKeysYieldArrowsAndCmdAToFocusedTextAndKeyboardCapturedSpecimens() {
        val (store, c) = controller()
        c.selectNode("a")
        val before = node(store, "a").frame
        assertFalse(c.handleKeyEvent(keyDown(Key.DirectionRight), externalInputActive = true))
        assertFalse(c.handleKeyEvent(keyDown(Key.A, meta = true), externalInputActive = true))
        assertEquals(before, node(store, "a").frame, "Arrows must reach the specimen, not nudge the node")
        assertEquals(setOf("a"), c.selectedNodeIds, "Cmd+A must reach the specimen, not select all")
        // Without external input the stage keeps its shortcuts.
        assertTrue(c.handleKeyEvent(keyDown(Key.DirectionRight)))
    }

    @Test
    fun resetWithAllBuildsClearsThePropertyOnEveryBuild() {
        val (store, c) = controller()
        c.selectNode("a")
        c.applyPatchesToNodes("Base rotation", mapOf("a" to buildJsonObject { put("rotation", 15.0) }))
        c.selectLocation("s1", 1)
        c.applyPatchesToNodes("Build rotation", mapOf("a" to buildJsonObject { put("rotation", 30.0) }))
        c.editAllBuilds = true
        assertTrue(c.resetPropertyAtCurrentBeat("Reset rotation", listOf("rotation")))
        val a = node(store, "a")
        assertEquals(0.0, a.rotation, "Base rotation is reset")
        assertEquals(0.0, JsonMergePatch.resolveNodeAtBeat(a, 1).rotation, "Build 1 rotation is reset too")
        assertNull(a.builds.filterIsInstance<Build.Change>().firstOrNull { (it.set["rotation"]) != null })
    }

    @Test
    fun wholeTextReplacementWithAllBuildsAppliesToEveryBuild() {
        val (store, c) = controller()
        c.selectLocation("s1", 1)
        c.selectNode("a")
        c.editAllBuilds = true
        assertTrue(c.applyWholeTextReplacement("Everywhere"))
        val a = node(store, "a")
        assertEquals("Everywhere", a.text?.value?.plainText, "Base text updated")
        assertEquals("Everywhere", JsonMergePatch.resolveNodeAtBeat(a, 1).text?.value?.plainText)
    }

    @Test
    fun allBuildsTextEditKeepsRichRunsAndStyling() {
        val (store, c) = controller()
        c.applyPatchesToNodes(
            "Rich text",
            mapOf(
                "a" to buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put(
                                "value",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(buildJsonObject { put("text", "Hello "); put("weight", 700) })
                                    add(buildJsonObject { put("text", "world"); put("italic", true) })
                                },
                            )
                        },
                    )
                },
            ),
        )
        c.selectLocation("s1", 1)
        c.selectNode("a")
        c.editAllBuilds = true
        assertTrue(c.applyWholeTextReplacement("Hello there world"))
        val value = node(store, "a").text?.value
        assertTrue(value is dev.sebastiano.ciacola.document.RichTextValue.Runs, "Rich runs must survive: $value")
        assertEquals("Hello there world", value.plainText)
        assertEquals(listOf(700, null), value.runs.map { it.weight })
        assertEquals("world", value.runs.last().text)
        assertEquals(true, value.runs.last().italic)
        assertEquals("Hello there world", JsonMergePatch.resolveNodeAtBeat(node(store, "a"), 1).text?.value?.plainText)
    }

    @Test
    fun rangeEditsOnRunsTouchOnlyTheEditedSpan() {
        val runs = listOf(
            dev.sebastiano.ciacola.document.TextRun(text = "Bold ", weight = 700),
            dev.sebastiano.ciacola.document.TextRun(text = "plain", italic = true),
        )
        // Delete across the boundary ("d p" -> "") keeps both styles.
        val joined = applyRangeEditToRuns(runs, dev.sebastiano.ciacola.document.TextRangeEdit(start = 3, removedLength = 3, insertedText = ""))
        assertEquals(listOf("Bol", "lain"), joined.map { it.text })
        assertEquals(listOf(700, null), joined.map { it.weight })
        // Replacing everything keeps the first run's style.
        val replaced = richValueWithText(dev.sebastiano.ciacola.document.RichTextValue.Runs(runs), "New")
        assertEquals("New", replaced.plainText)
    }

    @Test
    fun arrangeOnlyMovesSelectedNodesPresentOnTheCurrentBuild() {
        val (store, c) = controller()
        c.selectLocation("s1", 1)
        c.selectNodes(setOf("a", "late"), primaryId = "a")
        c.selectLocation("s1", 0) // `late` enters on Build 1: absent on Base.
        val lateBefore = node(store, "late").frame
        assertEquals(listOf("a"), DocumentElementArrangeOps.presentTargetIds(c))
        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        assertEquals(110.0, node(store, "a").frame?.x)
        assertEquals(lateBefore, node(store, "late").frame, "The absent node must stay untouched")
    }

    @Test
    fun allBuildsUnionResizeOffsetsEveryBuildRelatively() {
        val (store, c) = controller()
        c.selectLocation("s1", 1)
        // Build 1 moves `b` 400 units right of its base position.
        c.applyPatchesToNodes("Build 1 motion", mapOf("b" to buildJsonObject { put("frame", buildJsonObject { put("x", 800.0) }) }))
        c.selectNodes(setOf("a", "b"), primaryId = "a")
        c.editAllBuilds = true
        val startRects = mapOf("a" to Rect(100f, 100f, 300f, 200f), "b" to Rect(800f, 300f, 900f, 350f))
        val startUnion = Rect(100f, 100f, 900f, 350f)
        assertTrue(c.beginGroupResizeGesture(2))
        assertTrue(c.updateGroupResizeGesture(startUnion, Rect(100f, 100f, 1700f, 350f), startRects))
        c.commitGesture()
        val b = node(store, "b")
        // Base x shifts by the same delta as Build 1 (+700): 400 -> 1100; Build 1 stays 400 ahead.
        assertEquals(1100.0, b.frame?.x)
        assertEquals(1500.0, JsonMergePatch.resolveNodeAtBeat(b, 1).frame?.x)
        assertEquals(200.0, b.frame?.w, "Width doubles relatively (100 -> 200)")
    }

    @Test
    fun themeCurveLabelsUseExactIdentityAndNameCustomCurves() {
        assertEquals("Standard (default)", dev.sebastiano.ciacola.inspector.design.themeCurveLabel(null))
        assertEquals("Linear", dev.sebastiano.ciacola.inspector.design.themeCurveLabel(dev.sebastiano.ciacola.document.Curve.Preset("linear")))
        assertEquals("Custom spring", dev.sebastiano.ciacola.inspector.design.themeCurveLabel(dev.sebastiano.ciacola.document.Curve.Spring(0.3, 120.0)))
        assertEquals("Custom", dev.sebastiano.ciacola.inspector.design.themeCurveLabel(dev.sebastiano.ciacola.document.Curve.Bezier(0.11, 0.22, 0.33, 0.44)))
        assertTrue(dev.sebastiano.ciacola.inspector.design.isCustomThemeCurve(dev.sebastiano.ciacola.document.Curve.Spring(0.3, 120.0)))
        assertFalse(dev.sebastiano.ciacola.inspector.design.isCustomThemeCurve(null))
    }

    @Test
    fun selectionContextKeepsPrimaryInsideTheSet() {
        val ctx = StageSelectionContext(selectedElementId = "a").withSingleSelection("b")
        assertEquals("b", ctx.selectedElementId)
        assertEquals(setOf("b"), ctx.selectedElementIds)
        assertTrue(runCatching { StageSelectionContext(selectedElementId = "a", selectedElementIds = setOf("b")) }.isFailure)
        assertEquals("Hello…", InspectorDisplayNames.excerpt("Hello   world", max = 7))
    }

    @Test
    fun singleNodeResizeWithAllBuildsKeepsTheBuildOffsetRelativeToBase() {
        val (store, c) = controller()
        c.selectLocation("s1", 1)
        c.applyPatchesToNodes("Build 1 motion", mapOf("b" to buildJsonObject { put("frame", buildJsonObject { put("x", 800.0) }) }))
        c.selectNode("b")
        c.editAllBuilds = true
        assertTrue(c.beginResizeGesture("b", dev.sebastiano.ciacola.document.FrameSpec(x = 800.0, y = 300.0, w = 100.0, h = 50.0)))
        assertTrue(c.updateResizeGesture("b", dev.sebastiano.ciacola.document.FrameSpec(x = 850.0, y = 300.0, w = 150.0, h = 50.0)))
        c.commitResizeGesture()
        val b = node(store, "b")
        assertEquals(450.0, b.frame?.x, "Base x moves by the same +50")
        assertEquals(150.0, b.frame?.w, "Base width grows by the same +50")
        assertEquals(850.0, JsonMergePatch.resolveNodeAtBeat(b, 1).frame?.x, "Build 1 keeps its 400-unit offset")
        assertEquals("Rectangle", c.displayNameOf("b"), "Undo labels use display names, not raw ids")
    }

    @Test
    fun specimenParameterEditsOnlyReachSpecimensOfTheSameRef() {
        val (store, c) = controller()
        store.edit("Add specimens") { doc ->
            val slide = doc.slides.first()
            fun spec(id: String, ref: String) = dev.sebastiano.ciacola.document.NodeSpec(
                id = id,
                frame = dev.sebastiano.ciacola.document.FrameSpec(x = 10.0, y = 10.0, w = 100.0, h = 100.0),
                specimen = dev.sebastiano.ciacola.document.SpecimenPayload(ref = ref),
            )
            doc.copy(slides = listOf(slide.copy(nodes = slide.nodes + spec("sa", "shaders.a") + spec("sb", "shaders.b") + spec("sa2", "shaders.a"))))
        }
        c.selectNodes(setOf("sa", "sb", "sa2"), primaryId = "sa")
        assertEquals(listOf("sa", "sa2"), c.specimenPeerIds())
        assertTrue(c.setSpecimenParamRaw("speed", kotlinx.serialization.json.JsonPrimitive(2.0)))
        assertTrue("speed" in node(store, "sa").specimen!!.params)
        assertTrue("speed" in node(store, "sa2").specimen!!.params)
        assertFalse("speed" in node(store, "sb").specimen!!.params, "A different specimen ref never gains the parameter")
    }

    @Test
    fun allBuildsTextEditLeavesNoStaleManualDelaysOnBuildsWithTheirOwnText() {
        val (store, c) = controller()
        store.edit("Seed manual delays") { doc ->
            val slide = doc.slides.first()
            val nodes = slide.nodes.map { n ->
                if (n.id != "a") return@map n
                n.copy(
                    text = n.text!!.copy(
                        morph = dev.sebastiano.ciacola.document.TextMorphSpec(
                            stagger = dev.sebastiano.ciacola.document.Stagger(manual = mapOf("Hello#0" to 100)),
                        ),
                    ),
                    builds = listOf(
                        Build.Change(
                            beat = 1,
                            set = buildJsonObject { put("text", buildJsonObject { put("value", "Other words") }) },
                            morph = dev.sebastiano.ciacola.document.TextMorphSpec(
                                stagger = dev.sebastiano.ciacola.document.Stagger(manual = mapOf("Other#0" to 50)),
                            ),
                        ),
                    ),
                )
            }
            doc.copy(slides = listOf(slide.copy(nodes = nodes)))
        }
        c.selectLocation("s1", 1)
        c.selectNode("a")
        c.editAllBuilds = true
        assertTrue(c.applyWholeTextReplacement("Hello stage world again"))
        val a = node(store, "a")
        assertEquals("Hello stage world again", JsonMergePatch.resolveNodeAtBeat(a, 1).text?.value?.plainText)
        assertEquals("Hello stage world again", a.text?.value?.plainText)
        val change = a.builds.filterIsInstance<Build.Change>().firstOrNull { it.beat == 1 }
        assertTrue(change?.morph?.stagger?.manual.orEmpty().isEmpty(), "No stale fragment keys on Build 1: ${change?.morph}")
        assertEquals(mapOf("Hello#0" to 100), a.text?.morph?.stagger?.manual, "Base manual delays are remapped, not lost")
    }

    @Test
    fun arrowsAreNotConsumedWhenNoSelectedNodeIsOnThisBuild() {
        val (_, c) = controller()
        c.selectLocation("s1", 1)
        c.selectNode("late")
        c.selectLocation("s1", 0) // `late` is absent on Base.
        assertFalse(c.handleKeyEvent(keyDown(Key.DirectionRight)), "Arrows must fall through to slide navigation")
    }

    @Test
    fun firstNudgeOnImplicitlyAlignedChildPreservesOrthogonalCoordinateInParentLocalSpace() {
        val alignedChild = dev.sebastiano.ciacola.document.NodeSpec(
            id = "centered-child",
            align = "center",
            frame = dev.sebastiano.ciacola.document.FrameSpec(w = 200.0, h = 80.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Centered"),
            ),
        )
        val parent = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-stack",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 200.0, w = 600.0, h = 400.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(alignedChild)),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "aligned-nudge",
            title = "Aligned Nudge",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec(), dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(parent),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        // Parent sits at stage (100, 200); centered child sits at local (200, 160) -> stage (300, 360).
        c.stageBoundsProvider = { id ->
            when (id) {
                "parent-stack" -> Rect(100f, 200f, 700f, 600f)
                "centered-child" -> Rect(300f, 360f, 500f, 440f)
                else -> null
            }
        }
        c.selectLocation("s1", 0)
        c.selectNode("centered-child")

        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        val updated = dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver.findNode(
            store.document.slides.first().nodes,
            "centered-child",
        )!!
        assertEquals(210.0, updated.frame?.x, "Horizontal nudge must move parent-local x from 200 to 210")
        assertEquals(160.0, updated.frame?.y, "First nudge on aligned child must preserve parent-local y (160), not jump to 0")
    }

    @Test
    fun alignAndDistributeCompareNestedChildrenInStageCoordinatesAndWriteParentLocalPatches() {
        val child1 = dev.sebastiano.ciacola.document.NodeSpec(
            id = "child-1",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 10.0, y = 20.0, w = 100.0, h = 40.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("One"),
            ),
        )
        val child2 = dev.sebastiano.ciacola.document.NodeSpec(
            id = "child-2",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 10.0, y = 20.0, w = 100.0, h = 40.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Two"),
            ),
        )
        val parent1 = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-1",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 50.0, w = 300.0, h = 200.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(child1)),
        )
        val parent2 = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-2",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 200.0, y = 300.0, w = 300.0, h = 200.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(child2)),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "nested-align",
            title = "Nested Align",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(parent1, parent2),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        c.selectNodes(setOf("child-1", "child-2"), primaryId = "child-1")

        // Stage positions: child-1 is at x = 110, child-2 is at x = 210 (100 units apart despite equal local x = 10).
        assertEquals(110f, DocumentElementArrangeOps.nodeStageBounds(c, "child-1")?.left)
        assertEquals(210f, DocumentElementArrangeOps.nodeStageBounds(c, "child-2")?.left)

        // Left-align brings child-2 to stage x = 110 -> parent-local x = -90 inside parent-2 (at x = 200).
        assertTrue(c.alignSelectedNodes(NodeAlignment.Left))
        val after1 = dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver.findNode(
            store.document.slides.first().nodes,
            "child-1",
        )!!
        val after2 = dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver.findNode(
            store.document.slides.first().nodes,
            "child-2",
        )!!
        assertEquals(10.0, after1.frame?.x, "Leftmost child-1 stays at local x = 10 (stage x = 110)")
        assertEquals(-90.0, after2.frame?.x, "child-2 must shift by -100 to local x = -90 (stage x = 110)")
        assertEquals(110f, DocumentElementArrangeOps.nodeStageBounds(c, "child-2")?.left)
    }

    @Test
    fun inspectorPositionFieldsConvertStageCoordinatesBackToParentLocal() {
        val child = dev.sebastiano.ciacola.document.NodeSpec(
            id = "nested-child",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 10.0, y = 20.0, w = 100.0, h = 40.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Nested"),
            ),
        )
        val alignedChild = dev.sebastiano.ciacola.document.NodeSpec(
            id = "aligned-child",
            frame = dev.sebastiano.ciacola.document.FrameSpec(w = 120.0, h = 40.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Aligned"),
            ),
        )
        val parent = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-stack",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 200.0, w = 500.0, h = 300.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(
                padding = dev.sebastiano.ciacola.document.PaddingSpec(left = 12.0, top = 8.0, right = 12.0, bottom = 8.0),
                children = listOf(child, alignedChild),
            ),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "inspector-nested-xy",
            title = "Inspector Nested XY",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(parent),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        // Parent origin is (100, 200) + padding (12, 8) = (112, 208).
        // alignedChild sits at local (88, 92) -> stage (200, 300).
        c.stageBoundsProvider = { id ->
            when (id) {
                "parent-stack" -> Rect(100f, 200f, 600f, 500f)
                "aligned-child" -> Rect(200f, 300f, 320f, 340f)
                else -> null
            }
        }
        c.selectLocation("s1", 0)
        c.selectNode("nested-child")

        val xEditor = GridPropertyEditor(c, LayoutXProperty, c.editTargets())
        val yEditor = GridPropertyEditor(c, LayoutYProperty, c.editTargets())
        // Displayed stage coordinates include parent origin + padding: X = 112 + 10 = 122, Y = 208 + 20 = 228.
        assertEquals(122.0, xEditor.sharedValue())
        assertEquals(228.0, yEditor.sharedValue())

        // Step by +1 moves stage X from 122 to 123 (parent-local 10 -> 11), never 123 local.
        xEditor.step(1.0)
        xEditor.targets = c.editTargets()
        assertEquals(11.0, findNode(store, "nested-child").frame?.x)
        assertEquals(123.0, xEditor.sharedValue())

        // Commit absolute stage Y = 258 writes parent-local 50 (258 - 208).
        yEditor.commit(258.0)
        yEditor.targets = c.editTargets()
        assertEquals(50.0, findNode(store, "nested-child").frame?.y)
        assertEquals(258.0, yEditor.sharedValue())

        // Scrub X by +15 moves stage X from 123 to 138 (parent-local 26) in one undo step.
        xEditor.scrubStart()
        xEditor.scrub(5.0)
        xEditor.scrub(15.0)
        xEditor.scrubEnd()
        assertEquals(26.0, findNode(store, "nested-child").frame?.x)
        assertEquals(138f, DocumentElementArrangeOps.nodeStageBounds(c, "nested-child")?.left)

        // Scrubbing X on an implicitly aligned nested child preserves local Y across every scrub update.
        c.selectNode("aligned-child")
        val alignedX = GridPropertyEditor(c, LayoutXProperty, c.editTargets())
        assertEquals(200.0, alignedX.sharedValue())
        alignedX.scrubStart()
        alignedX.scrub(5.0)
        alignedX.targets = c.editTargets()
        alignedX.scrub(15.0)
        alignedX.scrubEnd()
        val updatedAligned = findNode(store, "aligned-child")
        assertEquals(103.0, updatedAligned.frame?.x, "local X moves from 88 to 103 (stage 215)")
        assertEquals(92.0, updatedAligned.frame?.y, "local Y stays pinned at 92 across multi-step scrub")
    }

    @Test
    fun singleNodeResizeGestureConvertsStageCoordinatesToParentLocal() {
        val child = dev.sebastiano.ciacola.document.NodeSpec(
            id = "nested-card",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 20.0, y = 30.0, w = 100.0, h = 60.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Card"),
            ),
        )
        val parent = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-stack",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 200.0, w = 500.0, h = 300.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(child)),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "nested-resize",
            title = "Nested Resize",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(parent),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        c.selectNode("nested-card")

        // Stage handle starts at (120, 230, 100, 60) and drags NW by (-10, -15) -> stage (110, 215, 110, 75).
        assertTrue(
            c.beginResizeGesture(
                "nested-card",
                dev.sebastiano.ciacola.document.FrameSpec(x = 120.0, y = 230.0, w = 100.0, h = 60.0),
            ),
        )
        assertTrue(
            c.updateResizeGesture(
                "nested-card",
                dev.sebastiano.ciacola.document.FrameSpec(x = 110.0, y = 215.0, w = 110.0, h = 75.0),
            ),
        )
        assertEquals(110.0, c.resizePreviewFrame?.x, "Live preview stays in stage coordinates")
        c.commitResizeGesture()

        val updated = findNode(store, "nested-card").frame!!
        assertEquals(10.0, updated.x, "Parent-local x is stage 110 - parent 100 = 10")
        assertEquals(15.0, updated.y, "Parent-local y is stage 215 - parent 200 = 15")
        assertEquals(110.0, updated.w)
        assertEquals(75.0, updated.h)
    }

    @Test
    fun lintOutOfBoundsFixConvertsClampedStagePositionToParentLocal() {
        val child = dev.sebastiano.ciacola.document.NodeSpec(
            id = "oob-child",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 220.0, y = 40.0, w = 200.0, h = 60.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Overflow"),
            ),
        )
        val parent = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-stack",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 1600.0, y = 100.0, w = 300.0, h = 200.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(child)),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "nested-lint-fix",
            title = "Nested Lint Fix",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(parent),
                ),
            ),
        )
        val issue = dev.sebastiano.ciacola.inspector.lint.DocumentLintIssue(
            id = "oob-1",
            code = "STAGE_OUT_OF_BOUNDS",
            severity = dev.sebastiano.ciacola.inspector.lint.LintSeverity.Error,
            source = dev.sebastiano.ciacola.inspector.lint.DocumentLintSource.Layout,
            slideIndex = 0,
            slideId = "s1",
            beatIndex = 0,
            nodeId = "oob-child",
            title = "Out of bounds",
            detail = "Extends past right edge",
            stageBounds = Rect(1820f, 140f, 2020f, 200f),
        )
        val patch = dev.sebastiano.ciacola.inspector.lint.lintFixPatch(issue, doc)!!
        val frame = patch["frame"]!!.jsonObject
        // Stage left clamps from 1820 to 1720 (1920 - 200), so local x shifts by -100 from 220 to 120.
        assertEquals(120.0, frame["x"]!!.jsonPrimitive.content.toDouble())
        assertEquals(40.0, frame["y"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun selectedParentAndChildMoveOnceAcrossNudgeDragAlignAndAllBuilds() {
        val child = dev.sebastiano.ciacola.document.NodeSpec(
            id = "child",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 10.0, y = 20.0, w = 80.0, h = 40.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Child"),
            ),
            builds = listOf(
                Build.Change(
                    beat = 1,
                    set = buildJsonObject { put("frame", buildJsonObject { put("x", 40.0) }) },
                ),
            ),
        )
        val parent = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 200.0, w = 300.0, h = 200.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(child)),
            builds = listOf(
                Build.Change(
                    beat = 1,
                    set = buildJsonObject { put("frame", buildJsonObject { put("x", 300.0) }) },
                ),
            ),
        )
        val anchor = dev.sebastiano.ciacola.document.NodeSpec(
            id = "anchor",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 50.0, y = 200.0, w = 40.0, h = 40.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Anchor"),
            ),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "ancestor-descendant-move",
            title = "Ancestor Descendant Move",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec(), dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(anchor, parent),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        c.selectNodes(setOf("parent", "child"), primaryId = "parent")

        // Nudge +10 at beat 0: parent moves 100 -> 110, child stays at local 10 (stage 120, not 130).
        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        assertEquals(110.0, findNode(store, "parent").frame?.x)
        assertEquals(10.0, findNode(store, "child").frame?.x)
        assertEquals(120f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.left)

        // Multi-drag by (+15, -5): parent moves to (125, 195), child keeps local (10, 20) -> stage (135, 215).
        c.beginMultiDrag("parent")
        assertTrue(c.commitMultiDrag(15.0, -5.0))
        assertEquals(125.0, findNode(store, "parent").frame?.x)
        assertEquals(195.0, findNode(store, "parent").frame?.y)
        assertEquals(10.0, findNode(store, "child").frame?.x)
        assertEquals(20.0, findNode(store, "child").frame?.y)
        assertEquals(135f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.left)
        assertEquals(215f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.top)

        // All builds nudge at beat 1 by +20: parent shifts on beat 0 & beat 1; child local stays untouched on both.
        c.selectLocation("s1", 1)
        c.editAllBuilds = true
        assertTrue(c.nudgeSelectedNodes(20.0, 0.0))
        val parentAfterAll = findNode(store, "parent")
        val childAfterAll = findNode(store, "child")
        assertEquals(145.0, parentAfterAll.frame?.x, "Beat 0 parent shifts +20")
        assertEquals(320.0, JsonMergePatch.resolveNodeAtBeat(parentAfterAll, 1).frame?.x, "Beat 1 parent shifts +20")
        assertEquals(10.0, childAfterAll.frame?.x, "Beat 0 child local x must not shift twice")
        assertEquals(40.0, JsonMergePatch.resolveNodeAtBeat(childAfterAll, 1).frame?.x, "Beat 1 child local x must not shift twice")

        // Align Left with anchor at x = 50 on beat 0: parent moves to 50 and child moves to local 0 (stage 50).
        c.editAllBuilds = false
        c.selectLocation("s1", 0)
        c.selectNodes(setOf("anchor", "parent", "child"), primaryId = "anchor")
        assertTrue(c.alignSelectedNodes(NodeAlignment.Left))
        assertEquals(50.0, findNode(store, "parent").frame?.x)
        assertEquals(0.0, findNode(store, "child").frame?.x, "Child aligns to stage 50 via local 0 inside parent at 50")
        assertEquals(50f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.left)
    }

    @Test
    fun selectedParentAndChildInspectorFieldsAndGroupResizeCompensateForAncestorShift() {
        val child = dev.sebastiano.ciacola.document.NodeSpec(
            id = "child",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 10.0, y = 20.0, w = 50.0, h = 40.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Child"),
            ),
        )
        val parent = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 200.0, w = 200.0, h = 100.0),
            stack = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(child)),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "ancestor-descendant-inspector-resize",
            title = "Ancestor Descendant Inspector And Resize",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec(), dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(parent),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        c.selectNodes(setOf("parent", "child"), primaryId = "parent")

        // Step X by +10: parent moves 100 -> 110, child keeps local 10 (stage 120, not 130).
        val xEditor = GridPropertyEditor(c, LayoutXProperty, c.editTargets())
        xEditor.step(10.0)
        xEditor.targets = c.editTargets()
        assertEquals(110.0, findNode(store, "parent").frame?.x)
        assertEquals(10.0, findNode(store, "child").frame?.x)
        assertEquals(120f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.left)

        // Scrub X by +15: parent moves 110 -> 125, child keeps local 10 (stage 135).
        xEditor.scrubStart()
        xEditor.scrub(5.0)
        xEditor.scrub(15.0)
        xEditor.scrubEnd()
        xEditor.targets = c.editTargets()
        assertEquals(125.0, findNode(store, "parent").frame?.x)
        assertEquals(10.0, findNode(store, "child").frame?.x)
        assertEquals(135f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.left)

        // Commit absolute stage X = 150: parent moves to 150, child moves to local 0 (stage 150, not 200).
        xEditor.commit(150.0)
        assertEquals(150.0, findNode(store, "parent").frame?.x)
        assertEquals(0.0, findNode(store, "child").frame?.x)
        assertEquals(150f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.left)

        // Reset to known positions and test multi-selection union resize with parent + child selected.
        store.undo() // back to parent 125, child local 10 (stage 135)
        val startRects = mapOf(
            "parent" to Rect(125f, 200f, 325f, 300f),
            "child" to Rect(135f, 220f, 185f, 260f),
        )
        val startUnion = Rect(125f, 200f, 325f, 300f)
        // Double size and move union top-left by (+50, +50) -> parent becomes (175, 250, 400, 200),
        // child becomes stage (175 + 20 = 195, 250 + 40 = 290, 100, 80) -> local (20, 40).
        val nextUnion = Rect(175f, 250f, 575f, 450f)
        assertTrue(c.beginGroupResizeGesture(2))
        assertTrue(c.updateGroupResizeGesture(startUnion, nextUnion, startRects))
        c.commitGesture()
        assertEquals(175.0, findNode(store, "parent").frame?.x)
        assertEquals(250.0, findNode(store, "parent").frame?.y)
        assertEquals(20.0, findNode(store, "child").frame?.x, "Child local x reflects post-resize parent origin")
        assertEquals(40.0, findNode(store, "child").frame?.y, "Child local y reflects post-resize parent origin")
        assertEquals(195f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.left)
        assertEquals(290f, DocumentElementArrangeOps.nodeStageBounds(c, "child")?.top)

        // Undo and verify the exact same union resize in All builds mode on beat 1.
        store.undo()
        c.selectLocation("s1", 1)
        c.editAllBuilds = true
        assertTrue(c.beginGroupResizeGesture(2))
        assertTrue(c.updateGroupResizeGesture(startUnion, nextUnion, startRects))
        c.commitGesture()
        assertEquals(175.0, findNode(store, "parent").frame?.x)
        assertEquals(20.0, findNode(store, "child").frame?.x, "All-builds group resize must not double-shift child")
        assertEquals(40.0, findNode(store, "child").frame?.y)
    }

    @Test
    fun allBuildsNudgeOffsetsImplicitBaseCoordinatesEvenWhenLaterBuildOverrides() {
        val implicitXNode = dev.sebastiano.ciacola.document.NodeSpec(
            id = "implicit-x",
            frame = dev.sebastiano.ciacola.document.FrameSpec(y = 100.0, w = 200.0, h = 60.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Slide"),
            ),
            builds = listOf(
                Build.Change(
                    beat = 2,
                    set = buildJsonObject { put("frame", buildJsonObject { put("x", 200.0) }) },
                ),
            ),
        )
        val alignedNode = dev.sebastiano.ciacola.document.NodeSpec(
            id = "aligned-later",
            frame = dev.sebastiano.ciacola.document.FrameSpec(w = 200.0, h = 80.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Centered"),
            ),
            builds = listOf(
                Build.Change(
                    beat = 2,
                    set = buildJsonObject { put("frame", buildJsonObject { put("x", 500.0); put("y", 160.0) }) },
                ),
            ),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "all-builds-implicit-base",
            title = "All Builds Implicit Base",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(
                        dev.sebastiano.ciacola.document.BeatSpec(),
                        dev.sebastiano.ciacola.document.BeatSpec(),
                        dev.sebastiano.ciacola.document.BeatSpec(),
                    ),
                    nodes = listOf(implicitXNode, alignedNode),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.stageBoundsProvider = { id ->
            when (id) {
                "aligned-later" -> Rect(200f, 160f, 400f, 240f)
                else -> null
            }
        }
        // Parked on beat 1 with All builds: implicit base X = 0 -> 10, beat 2 X = 200 -> 210.
        c.selectLocation("s1", 1)
        c.editAllBuilds = true
        c.selectNode("implicit-x")
        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        val afterBeat1 = findNode(store, "implicit-x")
        assertEquals(10.0, JsonMergePatch.resolveNodeAtBeat(afterBeat1, 0).frame?.x)
        assertEquals(10.0, JsonMergePatch.resolveNodeAtBeat(afterBeat1, 1).frame?.x)
        assertEquals(210.0, JsonMergePatch.resolveNodeAtBeat(afterBeat1, 2).frame?.x)

        // Undo and repeat while parked on beat 2 (where x = 200 is active): base must still shift 0 -> 10, not stamp 210.
        store.undo()
        c.selectLocation("s1", 2)
        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        val afterBeat2 = findNode(store, "implicit-x")
        assertEquals(10.0, JsonMergePatch.resolveNodeAtBeat(afterBeat2, 0).frame?.x)
        assertEquals(10.0, JsonMergePatch.resolveNodeAtBeat(afterBeat2, 1).frame?.x)
        assertEquals(210.0, JsonMergePatch.resolveNodeAtBeat(afterBeat2, 2).frame?.x)

        // Implicitly aligned centered node nudged from beat 1 with All builds offsets both base (200 -> 210)
        // and beat 2 (500 -> 510) while pinning base Y at 160 so alignment collapse does not jump Y to 0.
        c.selectLocation("s1", 1)
        c.selectNode("aligned-later")
        assertTrue(c.nudgeSelectedNodes(10.0, 0.0))
        val afterAligned = findNode(store, "aligned-later")
        assertEquals(210.0, JsonMergePatch.resolveNodeAtBeat(afterAligned, 0).frame?.x)
        assertEquals(160.0, JsonMergePatch.resolveNodeAtBeat(afterAligned, 0).frame?.y)
        assertEquals(510.0, JsonMergePatch.resolveNodeAtBeat(afterAligned, 2).frame?.x)
        assertEquals(160.0, JsonMergePatch.resolveNodeAtBeat(afterAligned, 2).frame?.y)
    }

    @Test
    fun flowChildrenIgnoreIneffectiveFramePositionEditsWhileAllowingSizeEdits() {
        val rowItem = dev.sebastiano.ciacola.document.NodeSpec(
            id = "row-item",
            frame = dev.sebastiano.ciacola.document.FrameSpec(w = 140.0, h = 60.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Flow Item"),
            ),
        )
        val colItem = dev.sebastiano.ciacola.document.NodeSpec(
            id = "col-item",
            frame = dev.sebastiano.ciacola.document.FrameSpec(w = 180.0, h = 50.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Col Item"),
            ),
        )
        val row = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-row",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 200.0, w = 500.0, h = 100.0),
            row = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(rowItem)),
        )
        val col = dev.sebastiano.ciacola.document.NodeSpec(
            id = "parent-col",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 400.0, w = 400.0, h = 200.0),
            column = dev.sebastiano.ciacola.document.ContainerPayload(children = listOf(colItem)),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "flow-child-positioning",
            title = "Flow Child Positioning",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(row, col),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.stageBoundsProvider = { id ->
            when (id) {
                "parent-row" -> Rect(100f, 200f, 600f, 300f)
                "row-item" -> Rect(240f, 220f, 380f, 280f)
                "parent-col" -> Rect(100f, 400f, 500f, 600f)
                "col-item" -> Rect(120f, 460f, 300f, 510f)
                else -> null
            }
        }
        c.selectLocation("s1", 0)
        c.selectNode("row-item")

        // Stage bounds come from flow layout, and position operations are disabled / no-ops.
        assertEquals(240f, c.nodeStageBounds("row-item")?.left)
        assertFalse(c.nudgeSelectedNodes(10.0, 0.0), "Nudge must not write unused frame.x on flow child")
        assertFalse(c.handleKeyEvent(keyDown(Key.DirectionRight)), "Arrows on flow child must not be swallowed")
        assertFalse(c.alignSelectedNodes(NodeAlignment.Left), "Align must ignore flow child")
        c.beginMultiDrag("row-item")
        assertTrue(c.dragPreviewNodeIds.isEmpty(), "Dragging a lone flow child must not start a fake preview")
        assertFalse(c.commitMultiDrag(25.0, 15.0))
        val xEditor = GridPropertyEditor(c, LayoutXProperty, c.editTargets())
        xEditor.commit(300.0)
        xEditor.step(5.0)
        assertNull(findNode(store, "row-item").frame?.x)
        assertNull(findNode(store, "row-item").frame?.y)
        assertFalse(store.canUndo, "Ineffective flow-child position edits must not create undo entries")

        // Lint out-of-bounds auto-fix also declines flow children rather than writing unused coordinates.
        val oobIssue = dev.sebastiano.ciacola.inspector.lint.DocumentLintIssue(
            id = "flow-oob",
            code = "STAGE_OUT_OF_BOUNDS",
            severity = dev.sebastiano.ciacola.inspector.lint.LintSeverity.Error,
            source = dev.sebastiano.ciacola.inspector.lint.DocumentLintSource.Layout,
            slideIndex = 0,
            slideId = "s1",
            beatIndex = 0,
            nodeId = "row-item",
            title = "Out of bounds",
            detail = "Extends past stage",
            stageBounds = Rect(1850f, 220f, 1990f, 280f),
        )
        assertNull(dev.sebastiano.ciacola.inspector.lint.lintFixPatch(oobIssue, store.document))

        // Resizing a flow child updates w/h (rendered by DocumentNodeItem) without writing x/y.
        assertTrue(c.beginResizeGesture("row-item", dev.sebastiano.ciacola.document.FrameSpec(x = 240.0, y = 220.0, w = 140.0, h = 60.0)))
        assertTrue(c.updateResizeGesture("row-item", dev.sebastiano.ciacola.document.FrameSpec(x = 220.0, y = 210.0, w = 160.0, h = 70.0)))
        c.commitResizeGesture()
        val resizedFlow = findNode(store, "row-item").frame!!
        assertNull(resizedFlow.x, "Resizing a flow child must not write unused frame.x")
        assertNull(resizedFlow.y, "Resizing a flow child must not write unused frame.y")
        assertEquals(160.0, resizedFlow.w)
        assertEquals(70.0, resizedFlow.h)

        // Selecting parent-row + row-item moves the row cleanly without writing coordinates onto row-item.
        c.selectNodes(setOf("parent-row", "row-item"), primaryId = "parent-row")
        assertTrue(c.nudgeSelectedNodes(15.0, 0.0))
        assertEquals(115.0, findNode(store, "parent-row").frame?.x)
        assertNull(findNode(store, "row-item").frame?.x)
    }

    @Test
    fun allBuildsEditPreservesReferencedAnonymousChangeBuildAsTimingAnchor() {
        val nodeA = dev.sebastiano.ciacola.document.NodeSpec(
            id = "node-a",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 100.0, y = 100.0, w = 200.0, h = 80.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("First"),
            ),
            builds = listOf(
                Build.Change(
                    beat = 1,
                    durationMs = 500,
                    set = buildJsonObject { put("opacity", 0.5) },
                ),
            ),
        )
        val nodeB = dev.sebastiano.ciacola.document.NodeSpec(
            id = "node-b",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 400.0, y = 100.0, w = 200.0, h = 80.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Second"),
            ),
            builds = listOf(
                Build.Enter(
                    beat = 1,
                    effect = "fade",
                    after = "node-a",
                    durationMs = 300,
                ),
            ),
        )
        val unreferencedC = dev.sebastiano.ciacola.document.NodeSpec(
            id = "node-c",
            frame = dev.sebastiano.ciacola.document.FrameSpec(x = 700.0, y = 100.0, w = 200.0, h = 80.0),
            text = dev.sebastiano.ciacola.document.TextPayload(
                value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Unreferenced"),
            ),
            builds = listOf(
                Build.Change(
                    beat = 1,
                    durationMs = 500,
                    set = buildJsonObject { put("opacity", 0.5) },
                ),
            ),
        )
        val doc = dev.sebastiano.ciacola.document.DeckDocument(
            format = 1,
            id = "timing-anchor-preservation",
            title = "Timing Anchor Preservation",
            defaultTheme = "dark",
            themes = mapOf("dark" to dev.sebastiano.ciacola.document.DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                dev.sebastiano.ciacola.document.SlideSpec(
                    id = "s1",
                    beats = listOf(dev.sebastiano.ciacola.document.BeatSpec(), dev.sebastiano.ciacola.document.BeatSpec()),
                    nodes = listOf(nodeA, nodeB, unreferencedC),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 1)
        c.editAllBuilds = true
        c.selectNodes(setOf("node-a", "node-c"), primaryId = "node-a")

        // Applying opacity to all builds empties both beat-1 Change sets: node-a's build is referenced
        // by node-b's `after = "node-a"` so it stays as a timing anchor, while unreferenced node-c prunes cleanly.
        assertTrue(c.applyPatchAtCurrentBeat("Edit opacity", buildJsonObject { put("opacity", 0.8) }))
        val updatedA = findNode(store, "node-a")
        val updatedC = findNode(store, "node-c")
        assertEquals(0.8, JsonMergePatch.resolveNodeAtBeat(updatedA, 0).opacity)
        assertEquals(0.8, JsonMergePatch.resolveNodeAtBeat(updatedA, 1).opacity)
        assertEquals(1, updatedA.builds.size, "Referenced anonymous beat-1 change must be preserved as timing anchor")
        assertTrue((updatedA.builds.single() as Build.Change).set.isEmpty())
        assertTrue(updatedC.builds.isEmpty(), "Unreferenced empty anonymous change build is still pruned")

        val plan = dev.sebastiano.ciacola.document.SegmentPlanner.planSegment(
            document = store.document,
            slideId = "s1",
            beat = 1,
        )
        val trackB = plan.tracks.first { it.nodeId == "node-b" }
        assertEquals(500, trackB.startMs, "node-b must still start after node-a's 500ms beat-1 build")
        val issues = dev.sebastiano.ciacola.document.DeckDocumentCodec.validate(store.document)
        assertTrue(issues.none { it.code.startsWith("AFTER_") }, "No dangling after-reference lint warnings: $issues")
    }

    private fun findNode(store: DeckDocumentStore, id: String) =
        dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver.findNode(
            store.document.slides.first().nodes,
            id,
        )!!
}
