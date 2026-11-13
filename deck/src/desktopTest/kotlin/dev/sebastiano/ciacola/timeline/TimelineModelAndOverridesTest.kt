package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TimelineModelAndOverridesTest {

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun harvestingInOneReusedSceneFindsTheSameTracksAsAFreshScenePerIndex() {
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        val overrides = DeckTimelineOverridesFile()
        // Oracle: the original harvest, one fresh offscreen scene per storyboard index.
        val fresh = TimelineHarvestCollector()
        val freshClock = DeckLoopClock()
        for (index in deck.indices) {
            androidx.compose.ui.renderComposeScene(width = 64, height = 36, density = deck.format.density) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalTimelineOffscreen provides true,
                    LocalTimelineHarvestCollector provides fresh,
                    LocalTimelineOverrides provides overrides,
                    LocalDeckLoopClock provides freshClock,
                ) { dev.bnorm.storyboard.easel.ScenePreview(deck, index) }
            }
        }
        val reused = TimelineHarvestCollector()
        harvestIntoCollector(deck, overrides, reused, DeckLoopClock())

        assertTrue(fresh.records.isNotEmpty())
        assertEquals(fresh.records, reused.records)
        assertEquals(fresh.sharedSightings.toSet(), reused.sharedSightings.toSet())
        assertEquals(fresh.sharedLabels, reused.sharedLabels)
        assertEquals(fresh.loopRecords.keys, reused.loopRecords.keys)
    }

    @Test
    fun indexDeckTimelineProducesContiguousOverlappingSlidesEventBeatLinesStaggersSharedSpansAndLoops() {
        val tempDir = Files.createTempDirectory("ciacola-timeline-idx")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)

        val snapshot = indexDeckTimeline(deck, store.data())
        assertEquals(5, snapshot.slides.size, "Expected 5 contiguous slide sections")

        // Adjacent slides must overlap contiguously across their slide transition!
        for (i in 0 until snapshot.slides.lastIndex) {
            val current = snapshot.slides[i]
            val next = snapshot.slides[i + 1]
            assertTrue(current.overlapOutMs > 0, "Expected positive overlap leaving slide $i")
            assertEquals(
                current.endMs - current.overlapOutMs,
                next.startMs,
                "Slide ${i + 1} must start where Slide $i's exit transition begins",
            )
        }

        // One click beat per resting state that is followed by an animation (all but the last).
        val restingStates = deck.scenes.sumOf { it.frames.size }
        assertEquals(restingStates - 1, snapshot.eventBeats.size)
        // Segments that animate nothing measure 0 ms, so pick a beat whose segment really plays.
        val midBeat = snapshot.eventBeats.filter { it.segmentDurationMs > 0 }[3]
        val midTime = midBeat.timeMs + midBeat.segmentDurationMs / 2
        val (stateIdx, frac) = snapshot.stateFractionForTimeMs(midTime)
        assertEquals(midBeat.stateIndex, stateIdx)
        assertEquals(0.5f, frac, absoluteTolerance = 0.05f)
        assertEquals(midTime, snapshot.timeMsForStateFraction(stateIdx, frac), "Time round-trip mismatch")

        // A click hold maps its whole time range onto the settled beat; the beat line sits at its end.
        val hold = snapshot.stateSpans.first { it.isHold && it.stateIndex == midBeat.stateIndex }
        assertEquals(midBeat.stateIndex to 0f, snapshot.stateFractionForTimeMs(hold.startMs + hold.durationMs / 2))
        assertEquals(midBeat.timeMs, snapshot.timeMsForStateFraction(midBeat.stateIndex, 0f))
        assertEquals(hold.startMs + hold.durationMs, midBeat.timeMs)

        // Categories harvested across the whole deck (even scenes not currently mounted!)
        val categories = snapshot.tracks.map { it.category }.toSet()
        assertTrue(TrackCategory.SharedElement in categories, "Missing SharedElement track")
        assertTrue(TrackCategory.MagicText in categories, "Missing MagicText track")
        assertTrue(TrackCategory.WarpCode in categories, "Missing WarpCode track")
        assertTrue(TrackCategory.StaggerGroup in categories, "Missing StaggerGroup track")
        assertTrue(TrackCategory.StaggerItem in categories, "Missing StaggerItem staircase tracks")
        assertTrue(TrackCategory.LineDraw in categories, "Missing LineDraw tracks")
        assertTrue(TrackCategory.LoopSubComp in categories, "Missing LoopSubComp track")
    }

    @Test
    fun timelineOverridesStorePersistsZeroLatencyBeatInsertionsTrackDragsCurvesAndArcMotionPaths() {
        val tempDir = Files.createTempDirectory("ciacola-timeline-ovr")
        val store = TimelineOverridesStore(tempDir)

        store.insertBeatAfter(sceneIndex = 4, beatIndex = 1)
        assertEquals(1, store.structuralRevision)

        store.updateTrack("lab-immutable-stamp") { prev ->
            prev.copy(
                enterBeat = 3,
                delayMs = 80,
                durationMs = 520,
                curve = TimelineCurveSpec(preset = "Custom", x1 = 0.1f, y1 = 0.9f, x2 = 0.2f, y2 = 1.0f),
                spatial = TimelineSpatialSpec(offsetXDp = -16f, offsetYDp = 40f, arcBend = 0.65f),
            )
        }
        assertEquals(1, store.parameterRevision)

        // Reload in a fresh store instance to prove atomic JSON persistence
        val reloaded = TimelineOverridesStore(tempDir)
        assertEquals(listOf(1), reloaded.data().sceneBeats[4]?.injectedAfterBeats)
        assertEquals(520, reloaded.data().tracks["lab-immutable-stamp"]?.durationMs)
        assertEquals(0.65f, reloaded.data().tracks["lab-immutable-stamp"]?.spatial?.arcBend)

        // Verify curved spatial arc interpolation bends away from straight linear midpoint
        val start = Rect(100f, 100f, 200f, 200f)
        val end = Rect(700f, 100f, 800f, 200f)
        val linearMid = computeArcInterpolatedRect(start, end, fraction = 0.5f, arcBend = 0f)
        val curvedMid = computeArcInterpolatedRect(start, end, fraction = 0.5f, arcBend = 0.6f)
        assertEquals(450f, linearMid.center.x, absoluteTolerance = 1f)
        assertEquals(150f, linearMid.center.y, absoluteTolerance = 1f)
        assertNotEquals(linearMid.center.y, curvedMid.center.y, "Arc bend must curve Y midpoint off the line")
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun draggingBlockEdgeContinuesAcrossSnapshotRefreshInsteadOfDyingAfterFirstStep() =
        androidx.compose.ui.test.v2.runComposeUiTest {
            val tempDir = Files.createTempDirectory("ciacola-block-drag")
            val store = TimelineOverridesStore(tempDir)
            val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
            lateinit var controller: TimelineStudioController
            var densityPxPerDp = 1f

            setContent {
                densityPxPerDp = androidx.compose.ui.platform.LocalDensity.current.density
                val loopClock = androidx.compose.runtime.remember { DeckLoopClock() }
                val animatic = dev.bnorm.storyboard.easel.rememberAnimatic(storyboard = { deck })
                controller = rememberTimelineStudioController(
                    animatic = animatic,
                    overridesStore = store,
                    loopClock = loopClock,
                )
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.size(
                        androidx.compose.ui.unit.Dp(1200f),
                        androidx.compose.ui.unit.Dp(600f),
                    ),
                ) {
                    TimelineStudioPanel(controller = controller, isDark = true)
                }
            }
            waitForIdle()

            val trackIdx = controller.snapshot.tracks.indexOfFirst { it.trackId == "lab-immutable-stamp" }
            assertTrue(trackIdx >= 0, "Expected lab-immutable-stamp track")
            val block = controller.snapshot.tracks[trackIdx].blocks.first()
            val headerTotalPx = 52f * densityPxPerDp
            val rowHeightPx = 28f * densityPxPerDp
            val y = headerTotalPx + (trackIdx + 0.5f) * rowHeightPx
            val bx0 = controller.viewport.xForTime(block.startMs.toFloat())
            val bx1 = bx0 + kotlin.math.max(18f, block.durationMs * controller.viewport.pxPerMs)

            val canvas = onNodeWithTag("ciacola-timeline-canvas", useUnmergedTree = true)
            canvas.performMouseInput {
                moveTo(androidx.compose.ui.geometry.Offset(bx1, y))
                press()
                moveBy(androidx.compose.ui.geometry.Offset(6f, 0f))
            }
            waitForIdle()
            val afterFirstStep = store.data().tracks["lab-immutable-stamp"]?.durationMs
            assertTrue(afterFirstStep != null && afterFirstStep > block.durationMs, "First drag step must increase duration")

            // Refreshing the snapshot mid-drag (either immediately or after debounce) must NOT cancel the gesture!
            runOnIdle { controller.refreshSnapshot(deck) }
            waitForIdle()

            canvas.performMouseInput {
                moveBy(androidx.compose.ui.geometry.Offset(20f, 0f))
                release()
            }
            waitForIdle()
            val afterSecondStep = store.data().tracks["lab-immutable-stamp"]?.durationMs ?: 0
            assertTrue(
                afterSecondStep > afterFirstStep + 100,
                "Drag must continue updating duration across snapshot refresh (first=$afterFirstStep, second=$afterSecondStep)",
            )
        }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun inspectorExposesVerticalScrollbarAndBezierHandleDragContinuesAcrossSteps() =
        androidx.compose.ui.test.v2.runComposeUiTest {
            val tempDir = Files.createTempDirectory("ciacola-bezier-drag")
            val store = TimelineOverridesStore(tempDir)
            val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
            lateinit var controller: TimelineStudioController

            setContent {
                val loopClock = androidx.compose.runtime.remember { DeckLoopClock() }
                val animatic = dev.bnorm.storyboard.easel.rememberAnimatic(storyboard = { deck })
                controller = rememberTimelineStudioController(
                    animatic = animatic,
                    overridesStore = store,
                    loopClock = loopClock,
                )
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.size(
                        androidx.compose.ui.unit.Dp(1200f),
                        androidx.compose.ui.unit.Dp(480f),
                    ),
                ) {
                    TimelineStudioPanel(controller = controller, isDark = true)
                }
            }
            waitForIdle()
            runOnIdle { controller.selectTrack("lab-immutable-stamp") }
            waitForIdle()

            onNodeWithTag("ciacola-inspector-vscrollbar", useUnmergedTree = true).assertExists()

            val bezier = onNodeWithTag("ciacola-bezier-editor", useUnmergedTree = true)
            val bounds = bezier.fetchSemanticsNode().boundsInRoot
            val w = bounds.width
            val h = bounds.height
            val startP1 = androidx.compose.ui.geometry.Offset(0.25f * w, 0.9f * h)

            bezier.performMouseInput {
                moveTo(startP1)
                press()
                moveBy(androidx.compose.ui.geometry.Offset(0.15f * w, -0.2f * h))
            }
            waitForIdle()
            val afterFirstMove = store.data().tracks["lab-immutable-stamp"]?.curve
            assertTrue(afterFirstMove != null && afterFirstMove.preset == "Custom" && afterFirstMove.x1 > 0.30f)

            bezier.performMouseInput {
                moveBy(androidx.compose.ui.geometry.Offset(0.35f * w, -0.3f * h))
                release()
            }
            waitForIdle()
            val afterSecondMove = store.data().tracks["lab-immutable-stamp"]?.curve
            assertTrue(
                afterSecondMove != null && afterSecondMove.x1 > afterFirstMove.x1 + 0.20f,
                "Bezier drag must continue across multiple steps without restarting pointerInput(curve): first=${afterFirstMove.x1}, second=${afterSecondMove?.x1}",
            )
        }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun inspectorClampsTriggerBeatAndPreservesStaggerAndSharedDurationMath() =
        androidx.compose.ui.test.v2.runComposeUiTest {
            val tempDir = Files.createTempDirectory("ciacola-inspector-math")
            val store = TimelineOverridesStore(tempDir)
            val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
            lateinit var controller: TimelineStudioController

            setContent {
                val loopClock = androidx.compose.runtime.remember { DeckLoopClock() }
                val animatic = dev.bnorm.storyboard.easel.rememberAnimatic(storyboard = { deck })
                controller = rememberTimelineStudioController(
                    animatic = animatic,
                    overridesStore = store,
                    loopClock = loopClock,
                )
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.size(
                        androidx.compose.ui.unit.Dp(1200f),
                        androidx.compose.ui.unit.Dp(600f),
                    ),
                ) {
                    TimelineStudioPanel(controller = controller, isDark = true)
                }
            }
            waitForIdle()

            // 1. Trigger Beat '+' must clamp to the last beat index of the slide (4 on S5, 5 beats total).
            runOnIdle { controller.selectTrack("lab-immutable-stamp") }
            waitForIdle()
            repeat(6) {
                onNodeWithTag("ciacola-inspector-beat-inc", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    release()
                }
                waitForIdle()
            }
            assertEquals(
                4,
                store.data().tracks["lab-immutable-stamp"]?.enterBeat,
                "Trigger Beat '+' must clamp to last beat index (4) of S5",
            )

            // 2. Stagger group Duration '+50ms' must grow total group bar by +50ms (405 -> 455ms)
            // and store per-item duration 400ms (350 + 50ms), keeping Inspector and bar in sync.
            runOnIdle { controller.selectTrack("lab-leader-stagger") }
            waitForIdle()
            val origGroupBar = controller.snapshot.tracks.first { it.trackId == "lab-leader-stagger" }.blocks.first().durationMs
            assertEquals(405, origGroupBar)
            onNodeWithTag("ciacola-inspector-dur-inc", useUnmergedTree = true).performMouseInput {
                moveTo(center)
                press()
                release()
            }
            waitForIdle()
            assertEquals(400, store.data().tracks["lab-leader-stagger"]?.durationMs, "Stored override must be per-item duration (350 + 50 = 400ms)")
            runOnIdle { controller.refreshSnapshot(deck) }
            waitForIdle()
            val newGroupBar = controller.snapshot.tracks.first { it.trackId == "lab-leader-stagger" }.blocks.first().durationMs
            assertEquals(455, newGroupBar, "Stagger group bar must grow by +50ms (405 -> 455ms), not +105ms")

            // 3. Shared element Duration must edit the morph duration (550ms), not the 2800ms cross-slide span.
            runOnIdle { controller.selectTrack("shared:foundation-shared-card") }
            waitForIdle()
            onNodeWithTag("ciacola-inspector-dur-dec", useUnmergedTree = true).performMouseInput {
                moveTo(center)
                press()
                release()
            }
            waitForIdle()
            assertEquals(
                500,
                store.data().tracks["shared:foundation-shared-card"]?.durationMs,
                "Shared element Duration '-' must step from 550ms morph duration to 500ms, not 2750ms",
            )
        }

    @Test
    fun virtualBeatInsertionsAndRemovalsResolveThroughBaseBeatIndices() {
        val tempDir = Files.createTempDirectory("ciacola-virtual-beats")
        val store = TimelineOverridesStore(tempDir)
        val base3 = listOf("B0", "B1", "B2")

        // 1. S1 (3 base beats): '+' on B1 (v0 -> b0), then '+' on last beat B4/4 (v3 -> b2), then '+' on B3/5 (v2 -> b1).
        store.insertBeatAfter(sceneIndex = 0, beatIndex = 0)
        assertEquals(listOf(0), store.data().sceneBeats[0]?.injectedAfterBeats)
        assertEquals(4, buildVirtualizedFrames(base3, store.data().sceneBeats[0]).size)

        store.insertBeatAfter(sceneIndex = 0, beatIndex = 3)
        assertEquals(
            listOf(0, 2),
            store.data().sceneBeats[0]?.injectedAfterBeats,
            "Adding a beat on the last virtual beat (v3) must map to base beat 2, not out-of-range base 3",
        )
        assertEquals(5, buildVirtualizedFrames(base3, store.data().sceneBeats[0]).size)

        store.insertBeatAfter(sceneIndex = 0, beatIndex = 2)
        assertEquals(
            listOf(0, 2, 1),
            store.data().sceneBeats[0]?.injectedAfterBeats,
            "Adding a beat on virtual beat 2 (after an earlier injection) must map to base beat 1",
        )
        assertEquals(6, buildVirtualizedFrames(base3, store.data().sceneBeats[0]).size)

        // 2. S5: inject at last beat (b4), then '-' at B1 (v0) must delete b0, not remove the injection at b4.
        store.insertBeatAfter(sceneIndex = 4, beatIndex = 4)
        assertEquals(listOf(4), store.data().sceneBeats[4]?.injectedAfterBeats)
        store.removeInjectedBeat(sceneIndex = 4, beatIndex = 0)
        assertEquals(
            listOf(4),
            store.data().sceneBeats[4]?.injectedAfterBeats,
            "Removing at B1 must not remove an unrelated injected beat at the end of the slide",
        )
        assertEquals(listOf(0), store.data().sceneBeats[4]?.deletedBeats)

        // 3. S4 (no injections): '-' twice at B1 (v0) must delete base 0 then base 1.
        store.removeInjectedBeat(sceneIndex = 3, beatIndex = 0)
        assertEquals(listOf(0), store.data().sceneBeats[3]?.deletedBeats)
        store.removeInjectedBeat(sceneIndex = 3, beatIndex = 0)
        assertEquals(
            listOf(0, 1),
            store.data().sceneBeats[3]?.deletedBeats,
            "Second '-' at virtual beat 0 must delete the new first beat (base 1), not repeat base 0",
        )
    }

    @Test
    fun overridesAndReviewStorePreserveExternalEditsAndBackupCorruptJson() {
        val tempDir = Files.createTempDirectory("ciacola-json-guards")
        val store = TimelineOverridesStore(tempDir)
        store.updateTrack("track-a") { it.copy(durationMs = 450) }

        // 1. External edit to timeline-overrides.json on disk must be picked up before the next UI mutation
        // instead of being silently clobbered (F-qa-stage-007).
        Files.writeString(
            store.overridesFile,
            """
            {
              "tracks": {
                "track-a": { "trackId": "track-a", "durationMs": 450 },
                "track-external": { "trackId": "track-external", "durationMs": 900 }
              }
            }
            """.trimIndent(),
        )
        store.updateTrack("track-b") { it.copy(durationMs = 600) }
        assertEquals(
            900,
            store.data().tracks["track-external"]?.durationMs,
            "External track edit on disk must be merged before updateTrack, not clobbered",
        )
        assertEquals(600, store.data().tracks["track-b"]?.durationMs)

        // 2. Corrupt timeline-overrides.json on startup/reload must be backed up to timeline-overrides.corrupt.json
        // before any subsequent write overwrites it (F-qa-code-020, F-qa-stage-010).
        val corruptDir = Files.createTempDirectory("ciacola-corrupt-overrides")
        val corruptOverridesFile = corruptDir.resolve("timeline-overrides.json")
        Files.writeString(corruptOverridesFile, "{ invalid json")
        val storeFromCorrupt = TimelineOverridesStore(corruptDir)
        storeFromCorrupt.updateTrack("track-c") { it.copy(durationMs = 500) }
        val backupOverridesFile = corruptDir.resolve("timeline-overrides.corrupt.json")
        assertTrue(
            Files.exists(backupOverridesFile),
            "Corrupt timeline-overrides.json must be backed up to timeline-overrides.corrupt.json before overwrite",
        )
        assertEquals("{ invalid json", Files.readString(backupOverridesFile))

        // 3. Corrupt review-comments.json must be backed up to review-comments.corrupt.json and not re-parsed on
        // every reloadFromDisk() poll when unchanged (F-qa-stage-009, F-qa-code-031).
        val reviewDir = Files.createTempDirectory("ciacola-corrupt-review")
        val commentsFile = reviewDir.resolve("review-comments.json")
        Files.writeString(commentsFile, "[ broken review json")
        val reviewStore = dev.sebastiano.ciacola.review.ReviewCommentStore(reviewDir, reviewDir)
        val backupCommentsFile = reviewDir.resolve("review-comments.corrupt.json")
        assertTrue(
            Files.exists(backupCommentsFile),
            "Corrupt review-comments.json must be backed up to review-comments.corrupt.json",
        )
        assertEquals("[ broken review json", Files.readString(backupCommentsFile))
    }
}
