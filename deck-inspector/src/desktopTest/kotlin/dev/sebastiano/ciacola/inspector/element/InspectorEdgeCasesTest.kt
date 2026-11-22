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
}
