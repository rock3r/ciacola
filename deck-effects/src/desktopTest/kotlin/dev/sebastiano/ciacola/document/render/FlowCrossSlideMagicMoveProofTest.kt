package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.v2.PathTrimState
import dev.sebastiano.ciacola.effects.v2.drawLineDrawPath
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.export.StoryboardVideoExporter
import dev.bnorm.storyboard.easel.export.VideoFrameSink
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.CodePayload
import dev.sebastiano.ciacola.document.ConnectorPayload
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.ImageFillSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.Phases
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.ScaleValue
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.StaggerOrder
import dev.sebastiano.ciacola.document.StageSize
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.Vec2
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface

/**
 * Exhaustive empirical & visual proof suite for synchronous zero-lag `LookaheadScope` +
 * `approachLayout` cross-slide Magic Move across flow (`Row`/`Column`), `align`-based `Stack`,
 * auto-sized rotated/scaled nodes, reflowing `MagicText v2`, matched container reordering,
 * unmatched sibling stability, 2x3 flow grid stagger, live `movableContentOf` specimens,
 * light/dark themes + compact widths + `StageInspectionRegistry`, and deterministic `Video` export.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FlowCrossSlideMagicMoveProofTest {

    private val proofDir: File by lazy {
        val configured = System.getProperty("ciacola.proof.dir")
        (if (!configured.isNullOrBlank()) File(configured) else File("build/reports/flow-magic-move-proof"))
            .apply { mkdirs() }
    }

    private data class ColorCentroid(
        val x: Double,
        val y: Double,
        val pixelCount: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
    )

    private fun measureColorCentroid(
        frame: RenderedFrame,
        targetR: Int,
        targetG: Int,
        targetB: Int,
        tolerance: Int = 28,
        region: IntArray? = null, // [left, top, right, bottom]
    ): ColorCentroid {
        val x0 = region?.get(0)?.coerceIn(0, frame.width) ?: 0
        val y0 = region?.get(1)?.coerceIn(0, frame.height) ?: 0
        val x1 = region?.get(2)?.coerceIn(0, frame.width) ?: frame.width
        val y1 = region?.get(3)?.coerceIn(0, frame.height) ?: frame.height
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in y0 until y1) {
            val rowOff = y * frame.width * 4
            for (x in x0 until x1) {
                val off = rowOff + x * 4
                val r = frame.rgba[off].toInt() and 0xFF
                val g = frame.rgba[off + 1].toInt() and 0xFF
                val b = frame.rgba[off + 2].toInt() and 0xFF
                if (abs(r - targetR) <= tolerance && abs(g - targetG) <= tolerance && abs(b - targetB) <= tolerance) {
                    sumX += x
                    sumY += y
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        check(count > 0) { "Expected pixels near RGB($targetR,$targetG,$targetB)±$tolerance, found 0" }
        return ColorCentroid(sumX / count, sumY / count, count, minX, maxX, minY, maxY)
    }

    private fun measureNonBackgroundBounds(
        frame: RenderedFrame,
        bgR: Int,
        bgG: Int,
        bgB: Int,
        tolerance: Int = 10,
        region: IntArray? = null,
    ): ColorCentroid {
        val x0 = region?.get(0)?.coerceIn(0, frame.width) ?: 0
        val y0 = region?.get(1)?.coerceIn(0, frame.height) ?: 0
        val x1 = region?.get(2)?.coerceIn(0, frame.width) ?: frame.width
        val y1 = region?.get(3)?.coerceIn(0, frame.height) ?: frame.height
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in y0 until y1) {
            val rowOff = y * frame.width * 4
            for (x in x0 until x1) {
                val off = rowOff + x * 4
                val r = frame.rgba[off].toInt() and 0xFF
                val g = frame.rgba[off + 1].toInt() and 0xFF
                val b = frame.rgba[off + 2].toInt() and 0xFF
                if (abs(r - bgR) > tolerance || abs(g - bgG) > tolerance || abs(b - bgB) > tolerance) {
                    sumX += x
                    sumY += y
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        check(count > 0) { "Expected non-background pixels in region, found 0" }
        return ColorCentroid(sumX / count, sumY / count, count, minX, maxX, minY, maxY)
    }

    private fun RenderedFrame.toSkiaImage(): SkiaImage {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap()
        bmp.allocPixels(info)
        bmp.installPixels(info, rgba, width * 4)
        return SkiaImage.makeFromBitmap(bmp)
    }

    private fun saveContactSheet(
        fileName: String,
        title: String,
        frames: List<Pair<String, RenderedFrame>>,
        columns: Int = 4,
    ): File {
        val maxFrameW = frames.maxOf { it.second.width }
        val maxFrameH = frames.maxOf { it.second.height }
        val cellPad = 14
        val headerH = 38
        val labelH = 24
        val thumbW = maxFrameW.coerceIn(320, 480)
        val thumbH = frames.maxOf {
            (it.second.height.toFloat() * (thumbW.toFloat() / it.second.width.toFloat())).toInt()
        }.coerceIn(140, 320)
        val rows = (frames.size + columns - 1) / columns
        val totalW = columns * thumbW + (columns + 1) * cellPad
        val totalH = headerH + rows * (thumbH + labelH + cellPad) + cellPad

        val typeface = FontMgr.default.matchFamilyStyle("Inter", FontStyle.NORMAL)
            ?: FontMgr.default.matchFamilyStyle("Helvetica Neue", FontStyle.NORMAL)
            ?: FontMgr.default.matchFamilyStyle("Arial", FontStyle.NORMAL)

        val surface = Surface.makeRasterN32Premul(totalW, totalH)
        try {
            val canvas = surface.canvas
            canvas.clear(0xFF0B0F17.toInt())
            val titleFont = Font(typeface, 15f)
            val labelFont = Font(typeface, 12f)
            val textPaint = Paint().apply { color = 0xFFE2E8F0.toInt(); isAntiAlias = true }
            val cellBgPaint = Paint().apply { color = 0xFF020617.toInt() }
            val borderPaint = Paint().apply {
                color = 0xFF334155.toInt()
                mode = PaintMode.STROKE
                strokeWidth = 1.5f
            }
            canvas.drawString(title, cellPad.toFloat(), 24f, titleFont, textPaint)

            for ((idx, pair) in frames.withIndex()) {
                val (label, frame) = pair
                val col = idx % columns
                val row = idx / columns
                val x = cellPad + col * (thumbW + cellPad)
                val y = headerH + row * (thumbH + labelH + cellPad)
                val cellRect = SkiaRect.makeXYWH(x.toFloat(), y.toFloat(), thumbW.toFloat(), thumbH.toFloat())
                canvas.drawRect(cellRect, cellBgPaint)
                val fitScale = minOf(thumbW.toFloat() / frame.width.toFloat(), thumbH.toFloat() / frame.height.toFloat())
                val drawW = frame.width.toFloat() * fitScale
                val drawH = frame.height.toFloat() * fitScale
                val dx = x.toFloat() + (thumbW.toFloat() - drawW) * 0.5f
                val dy = y.toFloat() + (thumbH.toFloat() - drawH) * 0.5f
                val img = frame.toSkiaImage()
                try {
                    val dst = SkiaRect.makeXYWH(dx, dy, drawW, drawH)
                    canvas.drawImageRect(img, dst)
                    canvas.drawRect(cellRect, borderPaint)
                } finally {
                    img.close()
                }
                canvas.drawString(label, x.toFloat(), (y + thumbH + 16).toFloat(), labelFont, textPaint)
            }

            val outImg = surface.makeImageSnapshot()
            try {
                val data = outImg.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Failed to encode PNG $fileName")
                val outFile = File(proofDir, fileName)
                outFile.writeBytes(data.bytes)
                return outFile
            } finally {
                outImg.close()
            }
        } finally {
            surface.close()
        }
    }

    private fun assertOverlayHandover(
        h: DocumentStageHarness,
        crossIdx: Int,
        restA: RenderedFrame,
        restB: RenderedFrame,
        matchedOverlayIds: List<String>,
        scenarioLabel: String,
    ) {
        fun overlayNodesCount(id: String) = h.onUiThread {
            h.scene.semanticsOwners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .count { it.config.getOrNull(SemanticsProperties.TestTag) == "doc-overlay-node-$id" }
        }

        fun diffSummary(a: RenderedFrame, b: RenderedFrame): String {
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val p1 = a.pixelRgba(x, y); val p2 = b.pixelRgba(x, y)
                val d = maxOf(abs(p1[0] - p2[0]), abs(p1[1] - p2[1]), abs(p1[2] - p2[2]), abs(p1[3] - p2[3]))
                if (d > 12) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
            return if (minX == Int.MAX_VALUE) "none" else "[$minX..$maxX, $minY..$maxY]"
        }

        val fStart = h.seekSegment(crossIdx, 1e-6f, rebuildScene = true)
        for (id in matchedOverlayIds) {
            assertEquals(1, overlayNodesCount(id), "$scenarioLabel p=1e-6 must mount doc-overlay-node-$id")
        }
        val dStart = diffFrames(restA, fStart, tolerance = 2)
        assertTrue(
            dStart.maxChannelDelta <= 12 && dStart.differingPixels <= 150,
            "$scenarioLabel overlay handover p=1e-6 must match Rest A within subpixel AA (maxDelta=${dStart.maxChannelDelta}, differingPixels=${dStart.differingPixels}, bounds>12=${diffSummary(restA, fStart)})",
        )

        val fEnd = h.seekSegment(crossIdx, 1f - 1e-6f, rebuildScene = true)
        for (id in matchedOverlayIds) {
            assertEquals(1, overlayNodesCount(id), "$scenarioLabel p=1-1e-6 must mount doc-overlay-node-$id")
        }
        val dEnd = diffFrames(restB, fEnd, tolerance = 2)
        assertTrue(
            dEnd.maxChannelDelta <= 12 && dEnd.differingPixels <= 150,
            "$scenarioLabel overlay handover p=1-1e-6 must match Rest B within subpixel AA (maxDelta=${dEnd.maxChannelDelta}, differingPixels=${dEnd.differingPixels}, bounds>12=${diffSummary(restB, fEnd)})",
        )
    }

    private fun linearMm(durationMs: Int = 200) = TransitionSpec(
        type = TransitionType.MagicMove,
        durationMs = durationMs,
        curve = Curve.Linear,
        phases = Phases(
            exit = Timing(durationMs = durationMs, curve = Curve.Linear),
            move = Timing(durationMs = durationMs, curve = Curve.Linear),
            enter = Timing(durationMs = durationMs, curve = Curve.Linear),
        ),
    )

    private fun pillNode(
        id: String,
        label: String,
        fillHex: String,
        textColorHex: String = "#FFFFFF",
        fontSize: Double = 18.0,
        padding: PaddingSpec = PaddingSpec(16.0, 10.0, 16.0, 10.0),
    ): NodeSpec = NodeSpec(
        id = id,
        text = TextPayload(
            value = RichTextValue.Plain(label),
            size = fontSize,
            color = textColorHex,
            surface = SurfaceSpec(
                fill = Fill.Solid(fillHex),
                cornerRadius = 10.0,
                padding = padding,
            ),
        ),
    )

    @Test
    fun scenario1_rowToColumnCrossSlideFlightExactEndpointsAndMonotonicTrajectories() {
        val doc = DeckDocument(
            id = "s1-row-to-col",
            stage = compactStage(640, 360),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0F172A"),
                    transition = linearMm(200),
                    nodes = listOf(
                        NodeSpec(
                            id = "rowA",
                            frame = FrameSpec(x = 40.0, y = 140.0, w = 560.0, h = 80.0),
                            row = ContainerPayload(
                                justify = "spaceBetween",
                                alignItems = "center",
                                padding = PaddingSpec(12.0),
                                children = listOf(
                                    pillNode("pill-red", "Alpha", "#EF4444"),
                                    pillNode("pill-green", "Beta", "#22C55E"),
                                    pillNode("pill-blue", "Gamma", "#3B82F6"),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0F172A"),
                    nodes = listOf(
                        NodeSpec(
                            id = "colB",
                            frame = FrameSpec(x = 270.0, y = 40.0, w = 140.0, h = 280.0),
                            column = ContainerPayload(
                                gap = 24.0,
                                alignItems = "center",
                                justify = "center",
                                children = listOf(
                                    pillNode("pill-red", "Alpha", "#EF4444"),
                                    pillNode("pill-green", "Beta", "#22C55E"),
                                    pillNode("pill-blue", "Gamma", "#3B82F6"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val match = CrossSlideGeometry.matchCrossSlidePairs(doc.slides[0], doc.slides[1], doc.stage)
        assertEquals(setOf("pill-red", "pill-green", "pill-blue"), match.pairs.map { it.nodeId }.toSet())
        assertTrue(match.pairs.none { it.isDocumentDetermined })

        DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))

            val fracs = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
            val seekFrames = fracs.associateWith { f -> h.seekSegment(crossIdx, f, rebuildScene = true) }

            val d0 = diffFrames(restA, seekFrames.getValue(0.0f), tolerance = 2)
            val d1 = diffFrames(restB, seekFrames.getValue(1.0f), tolerance = 2)
            assertEquals(0, d0.differingPixels, "Scenario 1 p=0 must match restA (maxDelta=${d0.maxChannelDelta})")
            assertEquals(0, d1.differingPixels, "Scenario 1 p=1 must match restB (maxDelta=${d1.maxChannelDelta})")
            assertOverlayHandover(h, crossIdx, restA, restB, listOf("pill-red", "pill-green", "pill-blue"), "Scenario 1")

            val redA = measureColorCentroid(restA, 0xEF, 0x44, 0x44)
            val blueA = measureColorCentroid(restA, 0x3B, 0x82, 0xF6)
            assertTrue(redA.minX < 80, "Row(spaceBetween) Alpha must start at left padding (minX=${redA.minX})")
            assertTrue(blueA.maxX > 550, "Row(spaceBetween) Gamma must reach right padding (maxX=${blueA.maxX})")

            val redB = measureColorCentroid(restB, 0xEF, 0x44, 0x44)
            val greenB = measureColorCentroid(restB, 0x22, 0xC5, 0x5E)
            val blueB = measureColorCentroid(restB, 0x3B, 0x82, 0xF6)
            assertTrue(redB.x in 338.0..342.0, "colB Alpha center X=${redB.x} expected 340.0±2.0")
            assertTrue(greenB.x in 338.0..342.0, "colB Beta center X=${greenB.x} expected 340.0±2.0")
            assertTrue(blueB.x in 338.0..342.0, "colB Gamma center X=${blueB.x} expected 340.0±2.0")
            assertTrue(greenB.y in 178.0..182.0, "colB Beta center Y=${greenB.y} expected 180.0±2.0")

            val redTraj = fracs.map { measureColorCentroid(seekFrames.getValue(it), 0xEF, 0x44, 0x44) }
            val greenTraj = fracs.map { measureColorCentroid(seekFrames.getValue(it), 0x22, 0xC5, 0x5E) }
            val blueTraj = fracs.map { measureColorCentroid(seekFrames.getValue(it), 0x3B, 0x82, 0xF6) }

            // Red moves right (left -> center) and up (center -> top)
            for (i in 0 until fracs.lastIndex) {
                assertTrue(redTraj[i + 1].x >= redTraj[i].x - 1.0, "Red X monotonic: ${redTraj.map { it.x }}")
                assertTrue(redTraj[i + 1].y <= redTraj[i].y + 1.0, "Red Y monotonic: ${redTraj.map { it.y }}")
                // Blue moves left (right -> center) and down (center -> bottom)
                assertTrue(blueTraj[i + 1].x <= blueTraj[i].x + 1.0, "Blue X monotonic: ${blueTraj.map { it.x }}")
                assertTrue(blueTraj[i + 1].y >= blueTraj[i].y - 1.0, "Blue Y monotonic: ${blueTraj.map { it.y }}")
            }
            assertTrue(redTraj.last().x > redTraj.first().x + 100.0)
            assertTrue(blueTraj.first().x > blueTraj.last().x + 100.0)
            assertTrue(greenTraj.first().y in 160.0..200.0)

            saveContactSheet(
                fileName = "scenario1_row_to_column_dark.png",
                title = "Scenario 1: Row(spaceBetween) -> Column(center) Flow Magic Move (p=0,0.25,0.5,0.75,1.0)",
                frames = listOf("Rest A" to restA) + fracs.map { "p=$it" to seekFrames.getValue(it) } + listOf("Rest B" to restB),
            )
            println("S1 PASS: p0MaxDelta=${d0.maxChannelDelta}, p1MaxDelta=${d1.maxChannelDelta}, redX=${redTraj.map { "%.1f".format(it.x) }}, blueX=${blueTraj.map { "%.1f".format(it.x) }}")
        }
    }

    @Test
    fun scenario2_zeroOneFrameLagColdSeekMatchesWarmForwardAndWarmBackward() {
        var siblingWidthDp by mutableStateOf(80)
        val specimens = deckSpecimens {
            specimen("s2.mut") {
                content {
                    Box(Modifier.width(siblingWidthDp.dp).height(60.dp).background(Color(0xFF6366F1)))
                }
            }
        }
        val doc = DeckDocument(
            id = "s2-zero-lag",
            stage = compactStage(640, 320),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#111827"),
                    transition = linearMm(200),
                    nodes = listOf(
                        NodeSpec(
                            id = "rowA",
                            frame = FrameSpec(x = 30.0, y = 40.0, w = 580.0, h = 240.0),
                            row = ContainerPayload(
                                gap = 24.0,
                                alignItems = "center",
                                children = listOf(
                                    pillNode("c1", "First", "#EC4899"),
                                    NodeSpec(
                                        id = "sib-mut",
                                        frame = FrameSpec(h = 60.0),
                                        specimen = SpecimenPayload(ref = "s2.mut"),
                                    ),
                                    NodeSpec(
                                        id = "c2",
                                        weight = 1.0,
                                        frame = FrameSpec(h = 60.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#14B8A6")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#111827"),
                    nodes = listOf(
                        NodeSpec(
                            id = "colB",
                            frame = FrameSpec(x = 320.0, y = 40.0, w = 280.0, h = 240.0),
                            column = ContainerPayload(
                                gap = 28.0,
                                alignItems = "end",
                                children = listOf(
                                    pillNode("c1", "First", "#EC4899"),
                                    NodeSpec(
                                        id = "c2",
                                        frame = FrameSpec(w = 180.0, h = 80.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#14B8A6")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val checkFracs = listOf(0.0f, 0.35f, 0.70f, 1.0f)
        val warmFwd: Map<Float, RenderedFrame>
        val warmBwd: Map<Float, RenderedFrame>
        val restA: RenderedFrame
        val restB: RenderedFrame
        val cfg = DocumentRenderConfig(baseDensity = 1f, specimens = specimens, fontsDir = bundledFontsDirPath())

        DocumentStageHarness(doc, initialConfig = cfg).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            assertOverlayHandover(h, crossIdx, restA, restB, listOf("c1", "c2"), "Scenario 2")

            h.jumpAndSettle(Storyboard.Index(0, 0))
            warmFwd = h.advanceAndCaptureMultiple(
                direction = AdvanceDirection.Forward,
                targetCanonicalFractions = listOf(0.35f, 0.70f),
                stepMs = 10L,
                matchSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
            ).first

            h.jumpAndSettle(Storyboard.Index(1, 0))
            warmBwd = h.advanceAndCaptureMultiple(
                direction = AdvanceDirection.Backward,
                targetCanonicalFractions = listOf(0.35f, 0.70f),
                stepMs = 10L,
                matchSegment = { f, t -> t == crossIdx && f == crossIdx + 1 },
            ).first
        }

        // Fresh ImageComposeScene mounted directly at Cross(A, B): capture ACTUAL Frame 0 (zero discarded frames)
        var maxColdVsFwd = 0
        var maxColdVsBwd = 0
        for (frac in checkFracs) {
            DocumentStageHarness(doc, initialConfig = cfg).use { fresh ->
                val crossIdx = fresh.crossFromStateIndex(0)
                val coldFrame0 = fresh.seekSegmentCaptureFirstLiveFrame(crossIdx, frac, rebuildScene = true)
                when (frac) {
                    0.0f -> {
                        val d = diffFrames(restA, coldFrame0, tolerance = 2)
                        maxColdVsFwd = maxOf(maxColdVsFwd, d.maxChannelDelta)
                        assertEquals(0, d.differingPixels, "Cold Frame 0 p=0 must match restA (maxDelta=${d.maxChannelDelta})")
                    }
                    1.0f -> {
                        val d = diffFrames(restB, coldFrame0, tolerance = 2)
                        maxColdVsFwd = maxOf(maxColdVsFwd, d.maxChannelDelta)
                        assertEquals(0, d.differingPixels, "Cold Frame 0 p=1 must match restB (maxDelta=${d.maxChannelDelta})")
                    }
                    else -> {
                        val dFwd = diffFrames(warmFwd.getValue(frac), coldFrame0, tolerance = 2)
                        val dBwd = diffFrames(warmBwd.getValue(frac), coldFrame0, tolerance = 2)
                        maxColdVsFwd = maxOf(maxColdVsFwd, dFwd.maxChannelDelta)
                        maxColdVsBwd = maxOf(maxColdVsBwd, dBwd.maxChannelDelta)
                        assertEquals(0, dFwd.differingPixels, "Cold Frame 0 p=$frac != warm forward (maxDelta=${dFwd.maxChannelDelta})")
                        assertEquals(0, dBwd.differingPixels, "Cold Frame 0 p=$frac != warm backward (maxDelta=${dBwd.maxChannelDelta})")
                    }
                }
            }
        }

        // Mid-Cross (p = 0.35f, rebuildScene = false) sibling width mutation -> synchronous same-frame overlay resize
        siblingWidthDp = 80
        DocumentStageHarness(doc, initialConfig = cfg).use { mutH ->
            val crossIdx = mutH.crossFromStateIndex(0)
            val before = mutH.seekSegment(crossIdx, 0.35f, rebuildScene = false)
            val c2Before = measureColorCentroid(before, 0x14, 0xB8, 0xA6)
            val widthBefore = c2Before.maxX - c2Before.minX + 1

            siblingWidthDp = 240
            val singleFrameAfterMutation = mutH.renderStep(1_000_000L)
            val c2After = measureColorCentroid(singleFrameAfterMutation, 0x14, 0xB8, 0xA6)
            val widthAfter = c2After.maxX - c2After.minX + 1
            assertTrue(
                widthAfter <= widthBefore - 90,
                "Mid-Cross sibling growth must shrink weighted c2 immediately on frame 0 (before=$widthBefore, after=$widthAfter)",
            )

            // Must match a freshly mounted scene already at siblingWidthDp=240 with zero differing pixels!
            val expectedAfter = DocumentStageHarness(doc, initialConfig = cfg).use { expH ->
                expH.seekSegment(expH.crossFromStateIndex(0), 0.35f, rebuildScene = true)
            }
            val mutDiff = diffFrames(expectedAfter, singleFrameAfterMutation, tolerance = 2)
            assertEquals(
                0,
                mutDiff.differingPixels,
                "Mid-Cross sibling mutation single frame must match fresh settled frame (maxDelta=${mutDiff.maxChannelDelta})",
            )
        }
        println("S2 PASS: Frame0 cold vs warm Fwd=$maxColdVsFwd/255, Bwd=$maxColdVsBwd/255, mid-Cross sibling mutation 0-lag verified")
    }

    @Test
    fun scenario3_reflowingWrappedMagicTextFromNarrowRowWeightToWideColumnAndTextAlign() {
        val doc = DeckDocument(
            id = "s3-reflow-text",
            stage = compactStage(820, 380),
            fonts = listOf(
                DeckFontDeclaration(
                    family = "Inter",
                    src = "Inter-Regular.ttf",
                    weight = 400,
                ),
            ),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0B1020"),
                    transition = linearMm(200),
                    nodes = listOf(
                        NodeSpec(
                            id = "splitRow",
                            frame = FrameSpec(x = 40.0, y = 40.0, w = 740.0, h = 220.0),
                            row = ContainerPayload(
                                gap = 24.0,
                                alignItems = "top",
                                children = listOf(
                                    NodeSpec(
                                        id = "reflow-title",
                                        weight = 1.0,
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Flow layout Magic Move morphs wrapped words cleanly"),
                                            font = "Inter",
                                            size = 20.0,
                                            color = "#F8FAFC",
                                            surface = SurfaceSpec(
                                                fill = Fill.Solid("#1E293B"),
                                                cornerRadius = 14.0,
                                                padding = PaddingSpec(12.0),
                                            ),
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "right-col",
                                        weight = 1.0,
                                        frame = FrameSpec(h = 160.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#334155")),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "alignedColA",
                            frame = FrameSpec(x = 40.0, y = 270.0, w = 360.0, h = 80.0),
                            column = ContainerPayload(
                                alignItems = "end",
                                children = listOf(
                                    NodeSpec(
                                        id = "aligned-text",
                                        frame = FrameSpec(w = 320.0),
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Right Aligned"),
                                            font = "Inter",
                                            size = 22.0,
                                            color = "#38BDF8",
                                            align = "end",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0B1020"),
                    nodes = listOf(
                        NodeSpec(
                            id = "wideCol",
                            frame = FrameSpec(x = 40.0, y = 80.0, w = 740.0, h = 160.0),
                            column = ContainerPayload(
                                alignItems = "center",
                                children = listOf(
                                    NodeSpec(
                                        id = "reflow-title",
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Flow layout Magic Move morphs wrapped words cleanly"),
                                            font = "Inter",
                                            size = 26.0,
                                            color = "#F8FAFC",
                                            surface = SurfaceSpec(
                                                fill = Fill.Solid("#1E293B"),
                                                cornerRadius = 14.0,
                                                padding = PaddingSpec(12.0),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "alignedColB",
                            frame = FrameSpec(x = 220.0, y = 270.0, w = 380.0, h = 80.0),
                            column = ContainerPayload(
                                alignItems = "center",
                                children = listOf(
                                    NodeSpec(
                                        id = "aligned-text",
                                        frame = FrameSpec(w = 340.0),
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Center Aligned"),
                                            font = "Inter",
                                            size = 22.0,
                                            color = "#38BDF8",
                                            align = "center",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            val fracs = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
            val seek = fracs.associateWith { f -> h.seekSegment(crossIdx, f, rebuildScene = true) }

            val d0 = diffFrames(restA, seek.getValue(0.0f), tolerance = 2)
            val d1 = diffFrames(restB, seek.getValue(1.0f), tolerance = 2)
            assertEquals(0, d0.differingPixels, "Scenario 3 p=0 must match restA (maxDelta=${d0.maxChannelDelta})")
            assertEquals(0, d1.differingPixels, "Scenario 3 p=1 must match restB (maxDelta=${d1.maxChannelDelta})")

            // Verify wrapped multi-line surfaced Text A reflows smoothly to wider B without clipping
            val inkA = measureColorCentroid(restA, 0xF8, 0xFA, 0xFC, tolerance = 90, region = intArrayOf(35, 35, 420, 260))
            val inkMid = measureColorCentroid(seek.getValue(0.5f), 0xF8, 0xFA, 0xFC, tolerance = 90, region = intArrayOf(35, 35, 790, 260))
            val inkB = measureColorCentroid(restB, 0xF8, 0xFA, 0xFC, tolerance = 90, region = intArrayOf(35, 35, 790, 260))

            // Verify overlay handover at p = 1e-6 and p = 1 - 1e-6:
            // - Both matched overlay nodes mount.
            // - Every pixel outside `reflow-title`'s size-morphing vector glyph box (including the rounded card surface,
            //   sibling `right-col`, and all of `aligned-text`) matches Rest A / Rest B strictly within subpixel AA.
            // - Inside `reflow-title`'s size-morphing vector glyph box (`20sp -> 26sp`, rendered via pure vector SkPath
            //   outlines rather than hinted/dilated CoreText/FreeType bitmaps), the glyph ink centroid and bounds match
            //   Rest A / Rest B within <= 1.0 px centroid and <= 2 px edge tolerance.
            fun assertSizeMorphOverlayHandover(
                fraction: Float,
                rest: RenderedFrame,
                restInk: ColorCentroid,
                searchRegion: IntArray,
                label: String,
            ) {
                val frame = h.seekSegment(crossIdx, fraction, rebuildScene = true)
                for (id in listOf("reflow-title", "aligned-text")) {
                    val count = h.onUiThread {
                        h.scene.semanticsOwners
                            .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                            .count { it.config.getOrNull(SemanticsProperties.TestTag) == "doc-overlay-node-$id" }
                    }
                    assertEquals(1, count, "Scenario 3 $label must mount doc-overlay-node-$id")
                }
                val exMinX = restInk.minX - 2
                val exMaxX = restInk.maxX + 2
                val exMinY = restInk.minY - 2
                val exMaxY = restInk.maxY + 2
                var outsideMaxDelta = 0
                var outsideDiffPixels = 0
                for (y in 0 until rest.height) {
                    for (x in 0 until rest.width) {
                        if (x in exMinX..exMaxX && y in exMinY..exMaxY) continue
                        val p1 = rest.pixelRgba(x, y)
                        val p2 = frame.pixelRgba(x, y)
                        val d = maxOf(abs(p1[0] - p2[0]), abs(p1[1] - p2[1]), abs(p1[2] - p2[2]), abs(p1[3] - p2[3]))
                        if (d > outsideMaxDelta) outsideMaxDelta = d
                        if (d > 2) outsideDiffPixels++
                    }
                }
                assertTrue(
                    outsideMaxDelta <= 12 && outsideDiffPixels <= 150,
                    "Scenario 3 $label outside size-morph glyph box [$exMinX..$exMaxX, $exMinY..$exMaxY] must match rest within subpixel AA (maxDelta=$outsideMaxDelta, differingPixels=$outsideDiffPixels)",
                )
                fun weightedTextCentroid(src: RenderedFrame, region: IntArray): Pair<Double, Double> {
                    var sumW = 0.0
                    var sumX = 0.0
                    var sumY = 0.0
                    for (y in region[1].coerceAtLeast(0) until region[3].coerceAtMost(src.height)) {
                        for (x in region[0].coerceAtLeast(0) until region[2].coerceAtMost(src.width)) {
                            val rgba = src.pixelRgba(x, y)
                            val w = ((rgba[0] - 0x1E) + (rgba[1] - 0x29) + (rgba[2] - 0x3B)).coerceAtLeast(0).toDouble()
                            if (w > 24.0) {
                                sumW += w
                                sumX += x * w
                                sumY += y * w
                            }
                        }
                    }
                    assertTrue(sumW > 0.0, "Scenario 3 $label expected visible text ink in region")
                    return (sumX / sumW) to (sumY / sumW)
                }
                val (restCx, restCy) = weightedTextCentroid(rest, searchRegion)
                val (handoverCx, handoverCy) = weightedTextCentroid(frame, searchRegion)
                assertTrue(
                    abs(handoverCx - restCx) <= 1.5 && abs(handoverCy - restCy) <= 1.0,
                    "Scenario 3 $label weighted vector glyph centroid ($handoverCx, $handoverCy) must match rest ($restCx, $restCy)",
                )
                val handoverInk = measureColorCentroid(frame, 0xF8, 0xFA, 0xFC, tolerance = 90, region = searchRegion)
                assertTrue(
                    abs(handoverInk.minX - restInk.minX) <= 2 &&
                        abs(handoverInk.maxX - restInk.maxX) <= 2 &&
                        abs(handoverInk.minY - restInk.minY) <= 2 &&
                        abs(handoverInk.maxY - restInk.maxY) <= 2,
                    "Scenario 3 $label vector glyph bounds [${handoverInk.minX}..${handoverInk.maxX}, ${handoverInk.minY}..${handoverInk.maxY}] must match rest [${restInk.minX}..${restInk.maxX}, ${restInk.minY}..${restInk.maxY}] within 2 px",
                )
            }
            assertSizeMorphOverlayHandover(1e-6f, restA, inkA, intArrayOf(35, 35, 420, 260), "p=1e-6")
            assertSizeMorphOverlayHandover(1f - 1e-6f, restB, inkB, intArrayOf(35, 35, 790, 260), "p=1-1e-6")

            assertTrue(inkB.maxX > inkA.maxX + 120, "Reflowed wide line on B (${inkB.maxX}) must be wider than narrow A (${inkA.maxX})")
            assertTrue(inkB.maxY - inkB.minY < inkA.maxY - inkA.minY, "Reflowed single-line B must have smaller ink height than wrapped A")
            assertTrue(
                inkMid.pixelCount >= minOf(inkA.pixelCount, inkB.pixelCount) * 8 / 10,
                "Rounded surfaced Text reflow at p=0.5 must not clip glyphs (mid=${inkMid.pixelCount}, A=${inkA.pixelCount}, B=${inkB.pixelCount})",
            )
            assertTrue(inkMid.x in (inkA.x - 10.0)..(inkB.x + 10.0), "Mid-flight centroid X (${inkMid.x}) must interpolate smoothly between ${inkA.x} and ${inkB.x}")

            // Verify genuine TextAlign.End on Rest A and TextAlign.Center on Rest B!
            val alignA = measureColorCentroid(restA, 0x38, 0xBD, 0xF8, tolerance = 80, region = intArrayOf(35, 265, 410, 355))
            val alignB = measureColorCentroid(restB, 0x38, 0xBD, 0xF8, tolerance = 80, region = intArrayOf(215, 265, 605, 355))
            assertTrue(alignA.x > 280.0, "Rest A right-aligned text centroid X=${alignA.x} must be > 280.0")
            assertTrue(alignB.x in 406.0..414.0, "Rest B centered text centroid X=${alignB.x} expected 410.0±4.0")

            saveContactSheet(
                fileName = "scenario3_reflow_text.png",
                title = "Scenario 3: Narrow Row(weight=1) Wrapped Text -> Wide Column + TextAlign Morph",
                frames = listOf("Rest A" to restA) + fracs.map { "p=$it" to seek.getValue(it) } + listOf("Rest B" to restB),
            )
            println("S3 PASS: p0MaxDelta=${d0.maxChannelDelta}, p1MaxDelta=${d1.maxChannelDelta}, inkWidthA=${inkA.maxX - inkA.minX}, inkWidthB=${inkB.maxX - inkB.minX}")
        }
    }

    @Test
    fun scenario4_reorderingAndSiblingInsertRemoveInsideMatchedRowToolbar() {
        val doc = DeckDocument(
            id = "s4-matched-row-reorder",
            stage = compactStage(760, 220),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0F172A"),
                    transition = linearMm(200),
                    nodes = listOf(
                        NodeSpec(
                            id = "toolbar",
                            frame = FrameSpec(x = 40.0, y = 60.0, w = 680.0, h = 100.0),
                            row = ContainerPayload(
                                gap = 20.0,
                                alignItems = "center",
                                padding = PaddingSpec(16.0),
                                surface = SurfaceSpec(
                                    fill = Fill.Solid("#1E293B"),
                                    cornerRadius = 14.0,
                                ),
                                children = listOf(
                                    NodeSpec(id = "itemA", frame = FrameSpec(w = 120.0, h = 60.0), shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#EF4444"))),
                                    NodeSpec(id = "itemB", frame = FrameSpec(w = 120.0, h = 60.0), shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#22C55E"))),
                                    NodeSpec(id = "exitOnly", frame = FrameSpec(w = 120.0, h = 60.0), shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#EAB308"))),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0F172A"),
                    nodes = listOf(
                        NodeSpec(
                            id = "toolbar",
                            frame = FrameSpec(x = 40.0, y = 60.0, w = 680.0, h = 100.0),
                            row = ContainerPayload(
                                gap = 20.0,
                                alignItems = "center",
                                padding = PaddingSpec(16.0),
                                surface = SurfaceSpec(
                                    fill = Fill.Solid("#1E293B"),
                                    cornerRadius = 14.0,
                                ),
                                children = listOf(
                                    NodeSpec(id = "enterOnly", frame = FrameSpec(w = 120.0, h = 60.0), shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#A855F7"))),
                                    NodeSpec(id = "itemB", frame = FrameSpec(w = 120.0, h = 60.0), shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#22C55E"))),
                                    NodeSpec(id = "itemA", frame = FrameSpec(w = 120.0, h = 60.0), shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#EF4444"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            val fracs = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
            val seek = fracs.associateWith { f -> h.seekSegment(crossIdx, f, rebuildScene = true) }

            val d0 = diffFrames(restA, seek.getValue(0.0f), tolerance = 2)
            val d1 = diffFrames(restB, seek.getValue(1.0f), tolerance = 2)
            assertEquals(0, d0.differingPixels, "Scenario 4 p=0 must match restA (maxDelta=${d0.maxChannelDelta})")
            assertEquals(0, d1.differingPixels, "Scenario 4 p=1 must match restB (maxDelta=${d1.maxChannelDelta})")
            assertOverlayHandover(h, crossIdx, restA, restB, listOf("toolbar"), "Scenario 4")

            val aXs = fracs.map { measureColorCentroid(seek.getValue(it), 0xEF, 0x44, 0x44).x }
            // itemA glides monotonically from slot 0 (~116) to slot 2 (~396)
            for (i in 0 until fracs.lastIndex) {
                assertTrue(aXs[i + 1] > aXs[i] + 50.0, "itemA must glide right across slots: $aXs")
            }

            // At p=0.25, exitOnly is still fading out in place at slot 2 (~396) while enterOnly fades in at slot 0 (~116)
            val midExitPx = seek.getValue(0.25f).pixelRgba(430, 110)
            assertTrue(midExitPx[0] > 0x30 && midExitPx[1] > 0x30, "exitOnly visible fading at p=0.25")

            saveContactSheet(
                fileName = "scenario4_matched_row_reorder.png",
                title = "Scenario 4: Matched Row Reorder [A, B, Exit] -> [Enter, B, A]",
                frames = listOf("Rest A" to restA) + fracs.map { "p=$it" to seek.getValue(it) } + listOf("Rest B" to restB),
            )
            println("S4 PASS: p0MaxDelta=${d0.maxChannelDelta}, p1MaxDelta=${d1.maxChannelDelta}, itemA_X=$aXs")
        }
    }

    @Test
    fun scenario5_unmatchedSiblingStabilityNextToGhostedFlowNodes() {
        val doc = DeckDocument(
            id = "s5-sibling-stability",
            stage = compactStage(640, 260),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0F172A"),
                    transition = linearMm(200),
                    nodes = listOf(
                        NodeSpec(
                            id = "unmatchedRowA",
                            frame = FrameSpec(x = 40.0, y = 60.0, w = 560.0, h = 120.0),
                            row = ContainerPayload(
                                gap = 24.0,
                                alignItems = "center",
                                children = listOf(
                                    pillNode("traveller", "Traveller Pill", "#3B82F6"),
                                    NodeSpec(
                                        id = "sibA1",
                                        frame = FrameSpec(w = 100.0, h = 60.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#22C55E")),
                                    ),
                                    NodeSpec(
                                        id = "sibA2",
                                        weight = 1.0,
                                        frame = FrameSpec(h = 60.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#F97316")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0F172A"),
                    nodes = listOf(
                        NodeSpec(
                            id = "unmatchedColB",
                            frame = FrameSpec(x = 340.0, y = 20.0, w = 260.0, h = 220.0),
                            column = ContainerPayload(
                                gap = 16.0,
                                alignItems = "center",
                                children = listOf(
                                    NodeSpec(
                                        id = "sibB1",
                                        frame = FrameSpec(w = 140.0, h = 50.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#A855F7")),
                                    ),
                                    pillNode("traveller", "Traveller Pill", "#3B82F6"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            val p001 = h.seekSegment(crossIdx, 0.01f, rebuildScene = true)
            val p099 = h.seekSegment(crossIdx, 0.99f, rebuildScene = true)

            val sibA1Rest = measureColorCentroid(restA, 0x22, 0xC5, 0x5E)
            val sibA1Early = measureColorCentroid(p001, 0x22, 0xC5, 0x5E)
            val sibA2Rest = measureColorCentroid(restA, 0xF9, 0x73, 0x16)
            val sibA2Early = measureColorCentroid(p001, 0xF9, 0x73, 0x16)

            assertTrue(abs(sibA1Rest.x - sibA1Early.x) < 0.5 && abs(sibA1Rest.minX - sibA1Early.minX) <= 1, "sibA1 must NOT shift/collapse at p=0.01: rest=$sibA1Rest vs early=$sibA1Early")
            assertTrue(abs(sibA2Rest.x - sibA2Early.x) < 0.5 && abs(sibA2Rest.maxX - sibA2Early.maxX) <= 1, "sibA2 must NOT shift/collapse at p=0.01: rest=$sibA2Rest vs early=$sibA2Early")

            val sibB1Rest = measureColorCentroid(restB, 0xA8, 0x55, 0xF7)
            val sibB1Late = measureColorCentroid(p099, 0xA8, 0x55, 0xF7)
            assertTrue(abs(sibB1Rest.y - sibB1Late.y) < 0.5 && abs(sibB1Rest.minY - sibB1Late.minY) <= 1, "sibB1 must NOT shift/collapse at p=0.99: rest=$sibB1Rest vs late=$sibB1Late")
            println("S5 PASS: sibA1DeltaX=${abs(sibA1Rest.x - sibA1Early.x)}, sibA2DeltaX=${abs(sibA2Rest.x - sibA2Early.x)}, sibB1DeltaY=${abs(sibB1Rest.y - sibB1Late.y)}")
        }
    }

    @Test
    fun scenario6_nestedMixedHierarchyAncestorRotationScaleAlignAndShearedFallback() {
        val doc = DeckDocument(
            id = "s6-nested-transform-align",
            stage = compactStage(700, 380),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0F172A"),
                    transition = linearMm(200),
                    nodes = listOf(
                        NodeSpec(
                            id = "outerStackA",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 300.0, h = 320.0),
                            stack = ContainerPayload(
                                alignItems = "center",
                                children = listOf(
                                    NodeSpec(
                                        id = "innerColA",
                                        column = ContainerPayload(
                                            gap = 12.0,
                                            alignItems = "center",
                                            children = listOf(
                                                NodeSpec(
                                                    id = "innerRowA",
                                                    row = ContainerPayload(
                                                        gap = 10.0,
                                                        children = listOf(
                                                            NodeSpec(
                                                                id = "badge",
                                                                rotation = -18.0,
                                                                scale = ScaleValue.Uniform(1.1),
                                                                text = TextPayload(
                                                                    value = RichTextValue.Plain("Badge"),
                                                                    size = 18.0,
                                                                    color = "#FFFFFF",
                                                                    surface = SurfaceSpec(
                                                                        fill = Fill.Solid("#EC4899"),
                                                                        cornerRadius = 8.0,
                                                                        padding = PaddingSpec(10.0),
                                                                    ),
                                                                ),
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        // Sheared flow node -> MUST still cross-fade
                        NodeSpec(
                            id = "shearedParentA",
                            frame = FrameSpec(x = 40.0, y = 290.0, w = 120.0, h = 60.0),
                            scale = ScaleValue.NonUniform(2.0, 1.0),
                            row = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "shearedBadge",
                                        frame = FrameSpec(w = 40.0, h = 30.0),
                                        rotation = 30.0,
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#FACC15")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0F172A"),
                    nodes = listOf(
                        NodeSpec(
                            id = "outerStackB",
                            frame = FrameSpec(x = 340.0, y = 40.0, w = 320.0, h = 280.0),
                            rotation = 22.0,
                            scale = ScaleValue.Uniform(1.35),
                            stack = ContainerPayload(
                                alignItems = "bottomEnd",
                                padding = PaddingSpec(24.0),
                                children = listOf(
                                    NodeSpec(
                                        id = "innerRowB",
                                        align = "bottomEnd",
                                        row = ContainerPayload(
                                            children = listOf(
                                                NodeSpec(
                                                    id = "badge",
                                                    rotation = 0.0,
                                                    scale = ScaleValue.Uniform(1.0),
                                                    text = TextPayload(
                                                        value = RichTextValue.Plain("Badge"),
                                                        size = 18.0,
                                                        color = "#FFFFFF",
                                                        surface = SurfaceSpec(
                                                            fill = Fill.Solid("#14B8A6"),
                                                            cornerRadius = 8.0,
                                                            padding = PaddingSpec(10.0),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "shearedBadge",
                            frame = FrameSpec(x = 430.0, y = 310.0, w = 40.0, h = 30.0),
                            shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#FACC15")),
                        ),
                    ),
                ),
            ),
        )

        val match = CrossSlideGeometry.matchCrossSlidePairs(doc.slides[0], doc.slides[1], doc.stage)
        assertEquals(listOf("badge"), match.pairs.map { it.nodeId })
        assertTrue(match.lintIssues.any { it.code == "CROSS_SLIDE_SHEARED_TRANSFORM" && it.address.contains("shearedBadge") })

        DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            val fracs = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
            val seek = fracs.associateWith { f -> h.seekSegment(crossIdx, f, rebuildScene = true) }

            val d0 = diffFrames(restA, seek.getValue(0.0f), tolerance = 2)
            val d1 = diffFrames(restB, seek.getValue(1.0f), tolerance = 2)
            assertEquals(0, d0.differingPixels, "Scenario 6 p=0 must match restA (maxDelta=${d0.maxChannelDelta})")
            assertEquals(0, d1.differingPixels, "Scenario 6 p=1 must match restB (maxDelta=${d1.maxChannelDelta})")
            assertOverlayHandover(h, crossIdx, restA, restB, listOf("badge"), "Scenario 6")

            // Sheared node cross-fades (present at restA left & restB right; at p=0.5 both fade in place, NOT travelling in middle)
            val midFrame = seek.getValue(0.5f)
            val midShearedLeft = measureColorCentroid(midFrame, 132, 113, 31, tolerance = 45, region = intArrayOf(0, 250, 260, 380))
            val midShearedRight = measureColorCentroid(midFrame, 132, 113, 31, tolerance = 45, region = intArrayOf(420, 250, 700, 380))
            assertTrue(midShearedLeft.pixelCount > 200 && midShearedRight.pixelCount > 200, "Sheared node cross-fades in place on both sides at p=0.5 (left=${midShearedLeft.pixelCount}, right=${midShearedRight.pixelCount})")

            saveContactSheet(
                fileName = "scenario6_nested_transform_align.png",
                title = "Scenario 6: Nested Stack(align) -> Column -> Row + Rotated/Scaled Ancestor + Sheared Fallback",
                frames = listOf("Rest A" to restA) + fracs.map { "p=$it" to seek.getValue(it) } + listOf("Rest B" to restB),
            )
            println("S6 PASS: p0MaxDelta=${d0.maxChannelDelta}, p1MaxDelta=${d1.maxChannelDelta}")
        }
    }

    @Test
    fun scenario7_flowGrid2x3StaggerOrdersRenderedInDistinctWaves() {
        fun gridDoc(order: StaggerOrder): DeckDocument {
            fun tile(id: String, label: String) = NodeSpec(
                id = id,
                frame = FrameSpec(w = 90.0, h = 50.0),
                text = TextPayload(
                    value = RichTextValue.Plain(label),
                    size = 14.0,
                    color = "#0F172A",
                    surface = SurfaceSpec(fill = Fill.Solid("#38BDF8"), cornerRadius = 8.0, padding = PaddingSpec(6.0)),
                ),
            )
            fun makeGrid(containerId: String, offsetX: Double): NodeSpec = NodeSpec(
                id = containerId,
                frame = FrameSpec(x = offsetX, y = 40.0, w = 310.0, h = 120.0),
                column = ContainerPayload(
                    gap = 20.0,
                    children = listOf(
                        NodeSpec(
                            id = "$containerId-row0",
                            row = ContainerPayload(
                                gap = 20.0,
                                children = listOf(tile("c00", "0,0"), tile("c01", "0,1"), tile("c02", "0,2")),
                            ),
                        ),
                        NodeSpec(
                            id = "$containerId-row1",
                            row = ContainerPayload(
                                gap = 20.0,
                                children = listOf(tile("c10", "1,0"), tile("c11", "1,1"), tile("c12", "1,2")),
                            ),
                        ),
                    ),
                ),
            )
            return DeckDocument(
                id = "s7-cross-grid-$order",
                stage = compactStage(760, 200),
                slides = listOf(
                    SlideSpec(
                        id = "sA",
                        background = Fill.Solid("#0F172A"),
                        transition = TransitionSpec(
                            type = TransitionType.MagicMove,
                            durationMs = 100,
                            curve = Curve.Linear,
                            stagger = Stagger(ms = 100, order = order),
                            phases = Phases(
                                exit = Timing(durationMs = 100, curve = Curve.Linear),
                                move = Timing(durationMs = 100, curve = Curve.Linear),
                                enter = Timing(durationMs = 100, curve = Curve.Linear),
                            ),
                        ),
                        nodes = listOf(makeGrid("gridA", offsetX = 20.0)),
                    ),
                    SlideSpec(
                        id = "sB",
                        background = Fill.Solid("#0F172A"),
                        nodes = listOf(makeGrid("gridB", offsetX = 430.0)),
                    ),
                ),
            )
        }

        // Tile centers at Slide A (left) and Slide B (right): tile w=90, h=50, gap=20
        val leftCenters = mapOf(
            "c00" to (65 to 65), "c01" to (175 to 65), "c02" to (285 to 65),
            "c10" to (65 to 135), "c11" to (175 to 135), "c12" to (285 to 135),
        )
        val rightCenters = leftCenters.mapValues { (_, xy) -> (xy.first + 410) to xy.second }
        fun isTileAt(frame: RenderedFrame, pt: Pair<Int, Int>): Boolean {
            // Check 4 corners of the tile around center to avoid matching dark glyph text inside center
            val px = frame.pixelRgba(pt.first - 30, pt.second - 15)
            return px[2] > 0x90 // #38BDF8 blue channel is 0xF8 vs bg 0x2A
        }

        // TopToBottom: waves at 0ms, 100ms -> total 200ms. At f=0.5 (100ms): row0 moved to B, row1 still at A!
        val tdDoc = gridDoc(StaggerOrder.TopToBottom)
        val tdPlan = SegmentPlanner.planTransition(tdDoc, "sA", "sB")
        assertEquals(200, tdPlan.segmentMs)
        val tdMoveStarts = tdPlan.tracks.filter { it.kind == TrackKind.TransitionMove }.associate { it.nodeId to it.startMs }
        assertEquals(mapOf("c00" to 0, "c01" to 0, "c02" to 0, "c10" to 100, "c11" to 100, "c12" to 100), tdMoveStarts)
        val tdMid = DocumentStageHarness(tdDoc).use { h ->
            h.seekSegment(h.crossFromStateIndex(0), 0.5f, rebuildScene = true)
        }
        assertTrue(tdMid.countNonMatchingPixels(0x0F, 0x17, 0x2A, tolerance = 8) > 10_000, "TopToBottom mid frame must not be blank")
        assertTrue(listOf("c00", "c01", "c02").all { isTileAt(tdMid, rightCenters.getValue(it)) }, "TopToBottom row0 arrived at B by 100ms")
        assertTrue(listOf("c10", "c11", "c12").all { isTileAt(tdMid, leftCenters.getValue(it)) }, "TopToBottom row1 still waiting at A at 100ms")

        // LeftToRight: waves at 0ms, 100ms, 200ms -> total 300ms. At f=1/3 (100ms): col0 arrived at B, col1&col2 still at A!
        val ltrDoc = gridDoc(StaggerOrder.LeftToRight)
        val ltrPlan = SegmentPlanner.planTransition(ltrDoc, "sA", "sB")
        assertEquals(300, ltrPlan.segmentMs)
        val ltrMoveStarts = ltrPlan.tracks.filter { it.kind == TrackKind.TransitionMove }.associate { it.nodeId to it.startMs }
        assertEquals(mapOf("c00" to 0, "c10" to 0, "c01" to 100, "c11" to 100, "c02" to 200, "c12" to 200), ltrMoveStarts)
        val ltrMid = DocumentStageHarness(ltrDoc).use { h ->
            h.seekSegment(h.crossFromStateIndex(0), 1f / 3f, rebuildScene = true)
        }
        assertTrue(ltrMid.countNonMatchingPixels(0x0F, 0x17, 0x2A, tolerance = 8) > 10_000, "LeftToRight mid frame must not be blank")
        assertTrue(listOf("c00", "c10").all { isTileAt(ltrMid, rightCenters.getValue(it)) }, "LeftToRight col0 arrived at B by 100ms")
        assertTrue(listOf("c01", "c11", "c02", "c12").all { isTileAt(ltrMid, leftCenters.getValue(it)) }, "LeftToRight col1/2 still at A at 100ms")

        // ReadingOrder: 6 waves (0,100,200,300,400,500) -> total 600ms.
        val roDoc = gridDoc(StaggerOrder.ReadingOrder)
        val roPlan = SegmentPlanner.planTransition(roDoc, "sA", "sB")
        assertEquals(600, roPlan.segmentMs)
        val roMoveStarts = roPlan.tracks.filter { it.kind == TrackKind.TransitionMove }.associate { it.nodeId to it.startMs }
        assertEquals(mapOf("c00" to 0, "c01" to 100, "c02" to 200, "c10" to 300, "c11" to 400, "c12" to 500), roMoveStarts)
        val roFrames = DocumentStageHarness(roDoc).use { h ->
            val cIdx = h.crossFromStateIndex(0)
            listOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f).map { f ->
                val fr = h.seekSegment(cIdx, f, rebuildScene = true)
                assertTrue(fr.countNonMatchingPixels(0x0F, 0x17, 0x2A, tolerance = 8) > 10_000, "ReadingOrder f=$f must not be blank")
                "RO f=${"%.2f".format(f)}" to fr
            }
        }
        val roAt200 = roFrames[2].second // t=200ms: c00 (0..100), c01 (100..200) arrived at B; c02..c12 still at A!
        assertTrue(isTileAt(roAt200, rightCenters.getValue("c00")) && isTileAt(roAt200, rightCenters.getValue("c01")))
        assertTrue(isTileAt(roAt200, leftCenters.getValue("c02")) && isTileAt(roAt200, leftCenters.getValue("c12")))

        saveContactSheet(
            fileName = "scenario7_grid_stagger.png",
            title = "Scenario 7: Cross-Slide MagicMove 2x3 Flow Grid Stagger (TopToBottom, LeftToRight, ReadingOrder)",
            frames = listOf("TopToBottom@100ms" to tdMid, "LeftToRight@100ms" to ltrMid) + roFrames,
        )
        println("S7 PASS: cross-slide MagicMove waves verified (TopToBottom=200ms, LeftToRight=300ms, ReadingOrder=600ms, 0 blank frames)")
    }

    @Test
    fun scenario8_liveMovableSpecimenInFlowAndAlignStackPreservesSingleMountAndState() {
        val mounts = AtomicInteger(0)
        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val observedRemember = mutableListOf<Any>()
        val observedRetained = mutableListOf<Any>()

        class RememberBox
        class RetainedBox

        val registry = deckSpecimens {
            specimen("flow.counter") {
                content {
                    DisposableEffect(Unit) {
                        mounts.incrementAndGet()
                        val cur = activeCount.incrementAndGet()
                        maxConcurrent.updateAndGet { maxOf(it, cur) }
                        onDispose { activeCount.decrementAndGet() }
                    }
                    val rem = remember { RememberBox() }
                    val ret = retained("r") { RetainedBox() }
                    observedRemember += rem
                    observedRetained += ret
                    Box(Modifier.fillMaxSize().background(Color(0xFF14B8A6)))
                }
            }
        }

        val mm = linearMm(120)
        val doc = DeckDocument(
            id = "s8-flow-specimen",
            stage = compactStage(600, 320),
            slides = listOf(
                // Slide A: Row with weighted Specimen + sibling shape
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0F172A"),
                    transition = mm,
                    nodes = listOf(
                        NodeSpec(
                            id = "rowA",
                            frame = FrameSpec(x = 30.0, y = 40.0, w = 540.0, h = 140.0),
                            row = ContainerPayload(
                                gap = 20.0,
                                children = listOf(
                                    NodeSpec(
                                        id = "spec",
                                        weight = 1.0,
                                        frame = FrameSpec(h = 120.0),
                                        specimen = SpecimenPayload(ref = "flow.counter"),
                                    ),
                                    NodeSpec(
                                        id = "sibA",
                                        frame = FrameSpec(w = 140.0, h = 120.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#3B82F6")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                // Slide B: Column with weighted Specimen + explicit w
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0F172A"),
                    transition = mm,
                    nodes = listOf(
                        NodeSpec(
                            id = "colB",
                            frame = FrameSpec(x = 30.0, y = 30.0, w = 260.0, h = 260.0),
                            column = ContainerPayload(
                                gap = 16.0,
                                children = listOf(
                                    NodeSpec(
                                        id = "spec",
                                        weight = 1.0,
                                        frame = FrameSpec(w = 220.0),
                                        specimen = SpecimenPayload(ref = "flow.counter"),
                                    ),
                                    NodeSpec(
                                        id = "sibB",
                                        frame = FrameSpec(w = 220.0, h = 60.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#EC4899")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                // Slide C: Stack(align="bottomEnd") with explicit w x h
                SlideSpec(
                    id = "sC",
                    background = Fill.Solid("#0F172A"),
                    nodes = listOf(
                        NodeSpec(
                            id = "stackC",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 560.0, h = 280.0),
                            stack = ContainerPayload(
                                alignItems = "bottomEnd",
                                children = listOf(
                                    NodeSpec(
                                        id = "spec",
                                        align = "bottomEnd",
                                        frame = FrameSpec(w = 200.0, h = 110.0),
                                        specimen = SpecimenPayload(ref = "flow.counter"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cfg = DocumentRenderConfig(baseDensity = 1f, specimens = registry, fontsDir = bundledFontsDirPath())
        DocumentStageHarness(doc, initialConfig = cfg).use { h ->
            assertEquals(1, mounts.get())
            val firstRem = observedRemember.first()
            val firstRet = observedRetained.first()

            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L) // A -> B
            assertEquals(Storyboard.Index(1, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "A->B flow specimen must stay mounts == 1")
            assertEquals(1, maxConcurrent.get(), "A->B maxConcurrent must stay 1")

            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L) // B -> C
            assertEquals(Storyboard.Index(2, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "B->C flow specimen must stay mounts == 1")

            h.advanceToRest(AdvanceDirection.Backward, stepMs = 10L) // C -> B
            assertEquals(Storyboard.Index(1, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "C->B flow specimen must stay mounts == 1")

            h.advanceToRest(AdvanceDirection.Backward, stepMs = 10L) // B -> A
            assertEquals(Storyboard.Index(0, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "B->A flow specimen must stay mounts == 1")

            assertTrue(observedRemember.all { it === firstRem }, "remember state preserved A->B->C->B->A")
            assertTrue(observedRetained.all { it === firstRet }, "retained state preserved A->B->C->B->A")
            println("S8 PASS: mounts=${mounts.get()}, maxConcurrent=${maxConcurrent.get()}, samples=${observedRemember.size}")
        }
    }

    @Test
    fun scenario9_lightAndDarkThemesCompactWidthsAndStageInspectionRegistryNoGhostDuplicates() {
        fun themeDoc(themeName: String, stageW: Int, stageH: Int): DeckDocument = DeckDocument(
            id = "s9-$themeName-${stageW}x$stageH",
            stage = compactStage(stageW, stageH),
            defaultTheme = themeName,
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    colors = mapOf("canvas" to "#0B1020", "ink" to "#F8FAFC", "surface" to "#1E293B", "accent" to "#38BDF8"),
                ),
                "light" to DeckThemeSpec(
                    preset = "stageLight",
                    colors = mapOf("canvas" to "#F8FAFC", "ink" to "#0F172A", "surface" to "#E2E8F0", "accent" to "#2563EB"),
                ),
            ),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    transition = linearMm(200),
                    nodes = listOf(
                        NodeSpec(
                            id = "rowA",
                            frame = FrameSpec(x = 16.0, y = 20.0, w = (stageW - 32).toDouble(), h = (stageH - 40).toDouble()),
                            row = ContainerPayload(
                                gap = 12.0,
                                alignItems = "center",
                                children = listOf(
                                    pillNode("card-1", "Card One", "accent", textColorHex = "#FFFFFF", fontSize = 14.0, padding = PaddingSpec(10.0)),
                                    pillNode("card-2", "Card Two", "surface", textColorHex = "ink", fontSize = 14.0, padding = PaddingSpec(10.0)),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    nodes = listOf(
                        NodeSpec(
                            id = "colB",
                            frame = FrameSpec(x = 16.0, y = 20.0, w = (stageW - 32).toDouble(), h = (stageH - 40).toDouble()),
                            column = ContainerPayload(
                                gap = 12.0,
                                alignItems = "center",
                                justify = "center",
                                children = listOf(
                                    pillNode("card-1", "Card One", "accent", textColorHex = "#FFFFFF", fontSize = 14.0, padding = PaddingSpec(10.0)),
                                    pillNode("card-2", "Card Two", "surface", textColorHex = "ink", fontSize = 14.0, padding = PaddingSpec(10.0)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        for (themeName in listOf("dark", "light")) {
            val doc = themeDoc(themeName, 640, 320)
            DocumentStageHarness(
                doc,
                initialConfig = DocumentRenderConfig(baseDensity = 1f, themeName = themeName, fontsDir = bundledFontsDirPath()),
            ).use { h ->
                val crossIdx = h.crossFromStateIndex(0)
                val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
                val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
                val fracs = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
                val seek = fracs.associateWith { f -> h.seekSegment(crossIdx, f, rebuildScene = true) }
                assertEquals(0, diffFrames(restA, seek.getValue(0.0f), tolerance = 2).differingPixels)
                assertEquals(0, diffFrames(restB, seek.getValue(1.0f), tolerance = 2).differingPixels)
                assertOverlayHandover(h, crossIdx, restA, restB, listOf("card-1", "card-2"), "Scenario 9 ($themeName)")
                saveContactSheet(
                    fileName = "scenario9_theme_$themeName.png",
                    title = "Scenario 9 ($themeName theme): Row -> Column Magic Move",
                    frames = listOf("Rest A" to restA) + fracs.map { "p=$it" to seek.getValue(it) } + listOf("Rest B" to restB),
                )
            }
        }

        // Compact & wide widths 240px..1600px check (all 3 widths rendered with aspect-preserving letterboxing)
        val widthFrames = mutableListOf<Pair<String, RenderedFrame>>()
        for (w in listOf(240, 480, 1600)) {
            val doc = themeDoc("dark", w, 240)
            DocumentStageHarness(
                doc,
                initialConfig = DocumentRenderConfig(baseDensity = 1f, themeName = "dark", fontsDir = bundledFontsDirPath()),
            ).use { h ->
                val crossIdx = h.crossFromStateIndex(0)
                val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
                val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
                val p0 = h.seekSegment(crossIdx, 0.0f, rebuildScene = true)
                val pMid = h.seekSegment(crossIdx, 0.5f, rebuildScene = true)
                val p1 = h.seekSegment(crossIdx, 1.0f, rebuildScene = true)
                assertEquals(0, diffFrames(restA, p0, tolerance = 2).differingPixels, "width=$w p=0 != restA")
                assertEquals(0, diffFrames(restB, p1, tolerance = 2).differingPixels, "width=$w p=1 != restB")
                widthFrames += "w=$w p=0" to p0
                widthFrames += "w=$w p=0.5" to pMid
                widthFrames += "w=$w p=1" to p1
            }
        }
        saveContactSheet(
            fileName = "scenario9_compact_widths.png",
            title = "Scenario 9: Stage Widths (240px, 480px, 1600px) Aspect-Preserved",
            frames = widthFrames,
            columns = 3,
        )

        // StageInspectionRegistry, live connector tracking, ghost <code/> isolation, zero invalidations
        val registry = StageInspectionRegistry()
        val inspectBase = themeDoc("dark", 640, 320)
        val connectorNode = NodeSpec(
            id = "conn-12",
            connector = ConnectorPayload(
                from = "card-1",
                to = "card-2",
                stroke = StrokeSpec(color = "#FACC15", width = 4.0),
            ),
        )
        fun codeWrap(xPos: Double) = NodeSpec(
            id = "code-wrap",
            frame = FrameSpec(x = xPos, y = 240.0, w = 120.0, h = 48.0),
            stack = ContainerPayload(
                children = listOf(
                    NodeSpec(
                        id = "ghost-code",
                        frame = FrameSpec(w = 100.0, h = 40.0),
                        code = CodePayload(value = "val k = 1"),
                    ),
                ),
            ),
        )
        val inspectDoc = inspectBase.copy(
            slides = listOf(
                inspectBase.slides[0].copy(
                    nodes = inspectBase.slides[0].nodes.map { n ->
                        n.copy(row = n.row?.copy(gap = 64.0, justify = "center"))
                    } + listOf(codeWrap(20.0), connectorNode),
                ),
                inspectBase.slides[1].copy(
                    nodes = inspectBase.slides[1].nodes.map { n ->
                        n.copy(column = n.column?.copy(gap = 64.0))
                    } + listOf(codeWrap(480.0), connectorNode),
                ),
            ),
        )
        DocumentStageHarness(
            inspectDoc,
            initialConfig = DocumentRenderConfig(
                baseDensity = 1f,
                themeName = "dark",
                fontsDir = bundledFontsDirPath(),
                stageDecorator = ContentDecorator { inner ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalStageInspectionRegistry provides registry,
                    ) { inner() }
                },
            ),
        ).use { h ->
            fun nodesWithTag(tag: String) = h.onUiThread {
                h.scene.semanticsOwners
                    .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            }

            assertFalse(h.onUiThread { h.scene.hasInvalidations() }, "Rest frame must have zero pending invalidations")
            assertEquals(1, nodesWithTag("doc-node-card-1").size)
            assertEquals(0, nodesWithTag("doc-ghost-node-card-1").size)
            assertTrue("card-1" in registry.nodes)

            // Seek to Cross(A, B) at p=0.5
            val crossIdx = h.crossFromStateIndex(0)
            val midFrame = h.seekSegment(crossIdx, 0.5f, rebuildScene = true)
            assertFalse(h.onUiThread { h.scene.hasInvalidations() }, "Cross(A,B) mid-flight frame must have zero infinite invalidations")

            // Ghosts exist ONLY under doc-ghost-node-* / doc-ghost-warp-* with cleared text semantics
            assertEquals(0, nodesWithTag("doc-node-card-1").size)
            val ghosts = nodesWithTag("doc-ghost-node-card-1")
            assertEquals(2, ghosts.size, "SceneA and SceneB each host one ghost")
            for (g in ghosts) {
                assertFalse(SemanticsProperties.Text in g.config, "Ghost semantics must be cleared")
            }
            assertEquals(0, nodesWithTag("doc-node-code-wrap").size)
            assertEquals(2, nodesWithTag("doc-ghost-node-code-wrap").size, "SceneA and SceneB each host one ghost code-wrap")
            assertEquals(2, nodesWithTag("doc-ghost-warp-surface-ghost-code").size, "Ghosted <code> uses doc-ghost-warp-* tags")
            assertEquals(1, nodesWithTag("doc-warp-surface-ghost-code").size, "Only Overlay renders active doc-warp-surface-*")

            // Overlay registers travelling nodes cleanly in StageInspectionRegistry & AnchorRegistry
            assertEquals(1, nodesWithTag("doc-overlay-node-card-1").size, "Overlay hosts single travelling node")
            assertEquals(1, nodesWithTag("doc-overlay-node-card-2").size)
            assertTrue("card-1" in registry.nodes, "Overlay travelling node card-1 must register in StageInspectionRegistry mid-Cross")
            assertTrue("card-2" in registry.nodes, "Overlay travelling node card-2 must register in StageInspectionRegistry mid-Cross")

            // Live connector (#FACC15) draws visibly connecting travelling card-1 and card-2 mid-Cross!
            // Connectors stay on the SceneA/SceneB cross-fade path, so at p=0.5 under
            // linearMm(200) (exit=0.5, enter=0.5) #FACC15 composites to ~0.75 alpha over #0B1020 (~190,157,23).
            val connInk = measureColorCentroid(midFrame, 0xFA, 0xCC, 0x15, tolerance = 80)
            assertTrue(connInk.pixelCount >= 20, "Live connector must track travelling anchors mid-Cross (pixels=${connInk.pixelCount})")
        }
        println("S9 PASS: dark/light themes, 240/480/1600px widths, overlay registry/connector tracking & ghost isolation verified")
    }

    @Test
    fun scenario10_realVideoExportFrameSequenceDeterminismAndTiming() {
        val registry = deckSpecimens {
            specimen("vid.badge") {
                content {
                    Box(Modifier.fillMaxSize().background(Color(0xFF14B8A6)))
                }
            }
        }
        val doc = DeckDocument(
            id = "s10-video-export",
            stage = compactStage(640, 360),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    background = Fill.Solid("#0B1020"),
                    transition = linearMm(550),
                    nodes = listOf(
                        NodeSpec(
                            id = "row1",
                            frame = FrameSpec(x = 30.0, y = 40.0, w = 580.0, h = 160.0),
                            row = ContainerPayload(
                                gap = 20.0,
                                alignItems = "center",
                                children = listOf(
                                    pillNode("hero-title", "Flow Magic Move Video", "#2563EB"),
                                    NodeSpec(
                                        id = "vid-spec",
                                        weight = 1.0,
                                        frame = FrameSpec(h = 100.0),
                                        specimen = SpecimenPayload(ref = "vid.badge"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s2",
                    background = Fill.Solid("#0B1020"),
                    transition = linearMm(550),
                    nodes = listOf(
                        NodeSpec(
                            id = "col2",
                            frame = FrameSpec(x = 80.0, y = 40.0, w = 480.0, h = 280.0),
                            column = ContainerPayload(
                                gap = 20.0,
                                alignItems = "center",
                                justify = "center",
                                children = listOf(
                                    pillNode("hero-title", "Flow Magic Move Video", "#16A34A"),
                                    NodeSpec(
                                        id = "vid-spec",
                                        weight = 1.0,
                                        frame = FrameSpec(w = 240.0),
                                        specimen = SpecimenPayload(ref = "vid.badge"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s3",
                    background = Fill.Solid("#0B1020"),
                    nodes = listOf(
                        NodeSpec(
                            id = "row3",
                            frame = FrameSpec(x = 40.0, y = 200.0, w = 560.0, h = 120.0),
                            row = ContainerPayload(
                                justify = "spaceBetween",
                                alignItems = "center",
                                children = listOf(
                                    NodeSpec(
                                        id = "vid-spec",
                                        frame = FrameSpec(w = 180.0, h = 80.0),
                                        specimen = SpecimenPayload(ref = "vid.badge"),
                                    ),
                                    pillNode("hero-title", "Flow Magic Move Video", "#9333EA"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        class CapturingSink : VideoFrameSink {
            val frames = ArrayList<ByteArray>()
            val frameTimesNs = ArrayList<Long>()
            private var lastNs = System.nanoTime()
            var finished = false
            override suspend fun writeFrame(rgbaBytes: ByteArray) {
                val now = System.nanoTime()
                frameTimesNs += (now - lastNs)
                lastNs = now
                frames += rgbaBytes.copyOf()
            }
            override suspend fun finish() { finished = true }
            override fun abort() {}
        }

        fun runExport(): CapturingSink {
            val sink = CapturingSink()
            val session = RenderSession.forVideo(doc)
            val sb = buildDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(baseDensity = 1f, specimens = registry, fontsDir = bundledFontsDirPath()),
            )
            val exporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 50L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> sink },
            )
            runBlocking {
                exporter.exportAsMp4(sb, Path.of(proofDir.absolutePath, "dummy.mp4"))
            }
            return sink
        }

        val exp1 = runExport()
        val exp2 = runExport()
        assertTrue(exp1.finished && exp2.finished)
        assertTrue(exp1.frames.size >= 60, "Expected >= 60 video frames at 60fps across A->B->C, got ${exp1.frames.size}")
        assertEquals(exp1.frames.size, exp2.frames.size, "Two video exports must emit identical frame counts")
        for (i in exp1.frames.indices) {
            assertTrue(
                exp1.frames[i].contentEquals(exp2.frames[i]),
                "Video export frame $i must be byte-for-byte identical across runs",
            )
        }

        val frame0Ms = exp1.frameTimesNs.first() / 1_000_000.0
        val warmSortedMs = exp1.frameTimesNs.drop(1).map { it / 1_000_000.0 }.sorted()
        val warmP50 = warmSortedMs[warmSortedMs.size / 2]
        val warmP95 = warmSortedMs[(warmSortedMs.size * 95) / 100]
        val warmMax = warmSortedMs.last()
        println(
            "S10 PASS: frames=${exp1.frames.size}, deterministic=true, " +
                "frame0=${"%.2f".format(frame0Ms)}ms, warmP50=${"%.2f".format(warmP50)}ms, " +
                "warmP95=${"%.2f".format(warmP95)}ms, warmMax=${"%.2f".format(warmMax)}ms",
        )
    }

    @Test
    fun scenario11_plainPlaybackConnectorTrackingAndDelayedFlowTextLockstep() {
        val doc = DeckDocument(
            id = "s11-plain-connector-and-delay",
            stage = compactStage(640, 320),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0B1020"),
                    transition = TransitionSpec(
                        type = TransitionType.MagicMove,
                        durationMs = 200,
                        curve = Curve.Linear,
                        phases = Phases(
                            exit = Timing(delayMs = 0, durationMs = 100, curve = Curve.Linear),
                            move = Timing(delayMs = 100, durationMs = 200, curve = Curve.Linear),
                            enter = Timing(delayMs = 0, durationMs = 100, curve = Curve.Linear),
                        ),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "rowA",
                            frame = FrameSpec(x = 20.0, y = 40.0, w = 400.0, h = 80.0),
                            row = ContainerPayload(
                                gap = 100.0,
                                alignItems = "center",
                                children = listOf(
                                    pillNode("card-1", "Travel", "#EF4444"),
                                    pillNode("card-2", "Peer", "#3B82F6"),
                                    NodeSpec(
                                        id = "sib-shape",
                                        frame = FrameSpec(w = 40.0, h = 40.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#14B8A6")),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "conn-cards",
                            connector = ConnectorPayload(
                                from = "card-1",
                                to = "card-2",
                                stroke = StrokeSpec(color = "#FACC15", width = 4.0),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0B1020"),
                    nodes = listOf(
                        NodeSpec(
                            id = "colB",
                            frame = FrameSpec(x = 420.0, y = 40.0, w = 200.0, h = 260.0),
                            column = ContainerPayload(
                                gap = 70.0,
                                alignItems = "center",
                                children = listOf(
                                    pillNode("card-1", "Travel", "#EF4444"),
                                    pillNode("card-2", "Peer", "#3B82F6"),
                                    NodeSpec(
                                        id = "sib-shape",
                                        frame = FrameSpec(w = 40.0, h = 40.0),
                                        shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#14B8A6")),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "static-anchor",
                            frame = FrameSpec(x = 60.0, y = 240.0, w = 80.0, h = 40.0),
                            shape = ShapePayload(ShapeGeometry.Rect, fill = Fill.Solid("#A855F7")),
                        ),
                        NodeSpec(
                            id = "conn-cards",
                            connector = ConnectorPayload(
                                from = "card-1",
                                to = "card-2",
                                stroke = StrokeSpec(color = "#FACC15", width = 4.0),
                            ),
                        ),
                        NodeSpec(
                            id = "conn-to-static",
                            connector = ConnectorPayload(
                                from = "card-1",
                                to = "static-anchor",
                                stroke = StrokeSpec(color = "#F97316", width = 4.0),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Default DocumentRenderConfig: NO custom stageDecorator, NO injected StageInspectionRegistry!
        DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))

            val cardA = measureColorCentroid(restA, 0xEF, 0x44, 0x44)
            val cardB = measureColorCentroid(restB, 0xEF, 0x44, 0x44)
            val shapeA = measureColorCentroid(restA, 0x14, 0xB8, 0xA6)
            val shapeB = measureColorCentroid(restB, 0x14, 0xB8, 0xA6)

            // 1. During phases.move.delayMs (t=50ms of 300ms => frac=50/300): neither card-1 nor sib-shape has moved yet!
            val duringDelay = h.seekSegment(crossIdx, 50f / 300f, rebuildScene = true)
            val cardDelay = measureColorCentroid(duringDelay, 0xEF, 0x44, 0x44)
            val shapeDelay = measureColorCentroid(duringDelay, 0x14, 0xB8, 0xA6)
            assertTrue(abs(cardDelay.x - cardA.x) <= 1.5, "Unchanged flow text must wait for move.delayMs (cardDelay=${cardDelay.x}, cardA=${cardA.x})")
            assertTrue(abs(shapeDelay.x - shapeA.x) <= 1.5, "Sibling shape must wait for move.delayMs (shapeDelay=${shapeDelay.x}, shapeA=${shapeA.x})")

            // 2. Halfway through move window (t=200ms of 300ms => frac=200/300): both at exact 50% X in lockstep!
            val midMove = h.seekSegment(crossIdx, 200f / 300f, rebuildScene = true)
            assertFalse(h.onUiThread { h.scene.hasInvalidations() }, "Plain Cross(A,B) mid-flight frame must have zero infinite invalidations")
            val cardMid = measureColorCentroid(midMove, 0xEF, 0x44, 0x44)
            val shapeMid = measureColorCentroid(midMove, 0x14, 0xB8, 0xA6)
            val cardProg = (cardMid.x - cardA.x) / (cardB.x - cardA.x)
            val shapeProg = (shapeMid.x - shapeA.x) / (shapeB.x - shapeA.x)
            assertTrue(abs(cardProg - 0.5) < 0.03, "Unchanged flow text must hit 50% at t=200ms (prog=$cardProg)")
            assertTrue(abs(shapeProg - 0.5) < 0.03, "Sibling shape must hit 50% at t=200ms (prog=$shapeProg)")
            assertTrue(abs(cardProg - shapeProg) < 0.02, "Flow text & sibling shape must stay in lockstep ($cardProg vs $shapeProg)")

            // 3. Plain-playback connectors track moving Overlay endpoints without any external decorator!
            // At t=200ms with enter=[0..100ms], sB's connectors are at full 1.0 alpha.
            val yellowConnRestA = measureColorCentroid(restA, 0xFA, 0xCC, 0x15, tolerance = 50)
            val yellowConnMid = measureColorCentroid(midMove, 0xFA, 0xCC, 0x15, tolerance = 50)
            val orangeConnMid = measureColorCentroid(midMove, 0xF9, 0x73, 0x16, tolerance = 50)
            assertTrue(yellowConnMid.pixelCount >= 20, "Matched connector must remain visible in plain Cross playback")
            assertTrue(orangeConnMid.pixelCount >= 20, "Travelling->stationary connector must render in plain Cross playback")
            assertTrue(
                yellowConnMid.x > yellowConnRestA.x + 80.0,
                "Plain-playback connector must move with travelling flow anchors (restA=${yellowConnRestA.x}, mid=${yellowConnMid.x})",
            )
            println("S11 PASS: plain-playback connectors & delayed lockstep verified (cardProg=$cardProg, shapeProg=$shapeProg, connMidX=${yellowConnMid.x})")
        }
    }

    @Test
    fun p02_unmatchedSpecimensOnMagicMoveNeverRemount() {
        var exitMounts = 0
        var exitActive = 0
        var exitMaxConcurrent = 0
        var enterMounts = 0
        var enterActive = 0
        var enterMaxConcurrent = 0

        val registry = deckSpecimens {
            specimen("p02.exit") {
                content {
                    DisposableEffect(Unit) {
                        exitMounts++
                        exitActive++
                        exitMaxConcurrent = maxOf(exitMaxConcurrent, exitActive)
                        onDispose { exitActive-- }
                    }
                    Box(Modifier.fillMaxSize().background(Color(0xFF14B8A6)))
                }
            }
            specimen("p02.enter") {
                content {
                    DisposableEffect(Unit) {
                        enterMounts++
                        enterActive++
                        enterMaxConcurrent = maxOf(enterMaxConcurrent, enterActive)
                        onDispose { enterActive-- }
                    }
                    Box(Modifier.fillMaxSize().background(Color(0xFFF97316)))
                }
            }
        }

        val doc = DeckDocument(
            id = "p02-unmatched-specimen",
            stage = compactStage(640, 360),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    transition = linearMm(400),
                    nodes = listOf(
                        NodeSpec(
                            id = "shared-box",
                            frame = FrameSpec(x = 40.0, y = 40.0, w = 80.0, h = 80.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EF4444")),
                        ),
                        NodeSpec(
                            id = "only-exit-spec",
                            frame = FrameSpec(x = 200.0, y = 40.0, w = 120.0, h = 120.0),
                            specimen = SpecimenPayload(ref = "p02.exit"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s1",
                    background = Fill.Solid("#0B1020"),
                    nodes = listOf(
                        NodeSpec(
                            id = "shared-box",
                            frame = FrameSpec(x = 480.0, y = 40.0, w = 80.0, h = 80.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EF4444")),
                        ),
                        NodeSpec(
                            id = "only-enter-spec",
                            frame = FrameSpec(x = 200.0, y = 180.0, w = 120.0, h = 120.0),
                            specimen = SpecimenPayload(ref = "p02.enter"),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(
            doc,
            initialConfig = DocumentRenderConfig(baseDensity = 1f, specimens = registry, fontsDir = bundledFontsDirPath()),
        ).use { h ->
            assertEquals(1, exitMounts, "Exiting specimen must mount once on Rest(s0)")
            assertEquals(1, exitMaxConcurrent)
            h.advanceToRest(AdvanceDirection.Forward, stepMs = 15L)
            assertEquals(1, exitMounts, "Unmatched exiting specimen must NEVER remount across Rest(s0)->Overlay->Rest(s1)")
            assertEquals(1, exitMaxConcurrent)
            assertEquals(1, enterMounts, "Unmatched entering specimen must mount once across Overlay->Rest(s1)")
            assertEquals(1, enterMaxConcurrent)
        }
    }

    @Test
    fun p11_shapeInterpolationNullRadiusEllipseRectAndBuildChangePath() {
        val colorResolver = DocumentColorResolver(themeSpec = null, baseTheme = resolveDeckTheme(DeckDocument(id = "t"), null), slidePaletteSpec = null)

        // 1. Sharp rect (cornerRadius=null) -> rounded rect (28.0) interpolates from 0.0 (no jump at t=0.01)
        val nSharp = NodeSpec(id = "r", frame = FrameSpec(0.0, 0.0, 100.0, 100.0), shape = ShapePayload(geometry = ShapeGeometry.Rect, cornerRadius = null))
        val nRound = NodeSpec(id = "r", frame = FrameSpec(0.0, 0.0, 100.0, 100.0), shape = ShapePayload(geometry = ShapeGeometry.Rect, cornerRadius = 28.0))
        val midRect = interpolateDocumentNodeProperties(nSharp, nRound, 0.25f, colorResolver)
        assertEquals(7.0, midRect.shape?.cornerRadius ?: -1.0, 0.01)
        val revRect = interpolateDocumentNodeProperties(nRound, nSharp, 0.75f, colorResolver)
        assertEquals(7.0, revRect.shape?.cornerRadius ?: -1.0, 0.01)

        // 2. Near-square Ellipse (100x101.5 => r=50) <-> Rect (100x100, r=10) morphs continuously via ShapeGeometry.Rect
        val nCircle = NodeSpec(id = "c", frame = FrameSpec(0.0, 0.0, 100.0, 101.5), shape = ShapePayload(geometry = ShapeGeometry.Ellipse))
        val nCard = NodeSpec(id = "c", frame = FrameSpec(0.0, 0.0, 100.0, 100.0), shape = ShapePayload(geometry = ShapeGeometry.Rect, cornerRadius = 10.0))
        val midMorph = interpolateDocumentNodeProperties(nCircle, nCard, 0.25f, colorResolver)
        assertEquals(ShapeGeometry.Rect, midMorph.shape?.geometry)
        assertEquals(40.0, midMorph.shape?.cornerRadius ?: -1.0, 0.01)
        val revCircleMorph = interpolateDocumentNodeProperties(nCard, nCircle, 0.75f, colorResolver)
        assertEquals(ShapeGeometry.Rect, revCircleMorph.shape?.geometry)
        assertEquals(40.0, revCircleMorph.shape?.cornerRadius ?: -1.0, 0.01)

        // 2b. Integer-rounded small dot (13x14, diff=1px) stays continuous, while 100x104 (diff=4px > 2px), eccentric oval (200x80), and unsized flow Ellipse keep 0.5f swap
        val nSmallDot = NodeSpec(id = "sd", frame = FrameSpec(0.0, 0.0, 13.0, 14.0), shape = ShapePayload(geometry = ShapeGeometry.Ellipse))
        val nSmallRect = NodeSpec(id = "sd", frame = FrameSpec(0.0, 0.0, 20.0, 20.0), shape = ShapePayload(geometry = ShapeGeometry.Rect, cornerRadius = 4.0))
        assertEquals(ShapeGeometry.Rect, interpolateDocumentNodeProperties(nSmallDot, nSmallRect, 0.25f, colorResolver).shape?.geometry)
        val nSlightlyOval = NodeSpec(id = "so", frame = FrameSpec(0.0, 0.0, 100.0, 104.0), shape = ShapePayload(geometry = ShapeGeometry.Ellipse))
        assertEquals(ShapeGeometry.Ellipse, interpolateDocumentNodeProperties(nSlightlyOval, nCard, 0.25f, colorResolver).shape?.geometry)
        val nOval = NodeSpec(id = "o", frame = FrameSpec(0.0, 0.0, 200.0, 80.0), shape = ShapePayload(geometry = ShapeGeometry.Ellipse))
        val nSharpRect = NodeSpec(id = "o", frame = FrameSpec(0.0, 0.0, 200.0, 80.0), shape = ShapePayload(geometry = ShapeGeometry.Rect))
        val ovalQuarter = interpolateDocumentNodeProperties(nOval, nSharpRect, 0.25f, colorResolver)
        assertEquals(ShapeGeometry.Ellipse, ovalQuarter.shape?.geometry)
        val ovalThreeQuarter = interpolateDocumentNodeProperties(nOval, nSharpRect, 0.75f, colorResolver)
        assertEquals(ShapeGeometry.Rect, ovalThreeQuarter.shape?.geometry)

        val nFlowCircle = NodeSpec(id = "fc", frame = null, shape = ShapePayload(geometry = ShapeGeometry.Ellipse))
        val nPill = NodeSpec(id = "fc", frame = FrameSpec(0.0, 0.0, 100.0, 60.0), shape = ShapePayload(geometry = ShapeGeometry.Rect, cornerRadius = 12.0))
        val flowQuarter = interpolateDocumentNodeProperties(nFlowCircle, nPill, 0.25f, colorResolver)
        assertEquals(ShapeGeometry.Ellipse, flowQuarter.shape?.geometry)

        // 3. Build.Change resizing frame on ShapeGeometry.Path without geometry patch scales cleanly BOTH mid-change and at Rest
        val sqPath = "M 0 0 L 100 0 L 100 100 L 0 100 Z"
        val doc = DeckDocument(
            id = "p11-path-build-change",
            stage = compactStage(400, 300),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    background = Fill.Solid("#0B1020"),
                    nodes = listOf(
                        NodeSpec(
                            id = "p",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 100.0, h = 100.0),
                            shape = ShapePayload(
                                geometry = ShapeGeometry.Path(sqPath),
                                fill = Fill.Solid("#22C55E"),
                            ),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    set = kotlinx.serialization.json.buildJsonObject {
                                        put(
                                            "frame",
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("w", kotlinx.serialization.json.JsonPrimitive(240.0))
                                                put("h", kotlinx.serialization.json.JsonPrimitive(180.0))
                                            },
                                        )
                                    },
                                    durationMs = 400,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        DocumentStageHarness(doc, initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath())).use { h ->
            val b0State = h.valueStateIndex(0, 0)
            val mid = h.seekSegment(b0State, 0.5f, rebuildScene = true)
            val rest = h.jumpAndSettle(Storyboard.Index(0, 1))
            val midGreen = measureColorCentroid(mid, 0x22, 0xC5, 0x5E)
            val restGreen = measureColorCentroid(rest, 0x22, 0xC5, 0x5E)
            // Scaled path fills interpolated width (~170px at p=0.5, 240px at Rest) instead of staying 100px
            assertTrue(midGreen.maxX - midGreen.minX + 1 >= 150, "Mid Build.Change Path must fill ~170px width, got ${midGreen.maxX - midGreen.minX + 1}")
            assertTrue(restGreen.maxX - restGreen.minX + 1 >= 230, "Resting post-Build.Change Path must fill 240px width, got ${restGreen.maxX - restGreen.minX + 1}")
        }
    }

    @Test
    fun p12_unmatchedContainerChildConnectorAndCollidingAlphaHooks() {
        val registry = StageInspectionRegistry()
        val doc = DeckDocument(
            id = "p12-unmatched-child-hooks",
            stage = compactStage(640, 320),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    transition = linearMm(400),
                    nodes = listOf(
                        NodeSpec(
                            id = "shared-anchor",
                            frame = FrameSpec(x = 40.0, y = 40.0, w = 60.0, h = 60.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#3B82F6")),
                        ),
                        NodeSpec(
                            id = "unmatched-wrap-a",
                            frame = FrameSpec(x = 200.0, y = 60.0, w = 140.0, h = 100.0),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "nested-target",
                                        frame = FrameSpec(x = 20.0, y = 20.0, w = 80.0, h = 60.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EF4444")),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "conn-to-nested",
                            connector = ConnectorPayload(
                                from = "shared-anchor",
                                to = "nested-target",
                                stroke = StrokeSpec(color = "#FACC15", width = 6.0),
                            ),
                        ),
                        NodeSpec(
                            id = "kind-collide",
                            frame = FrameSpec(x = 40.0, y = 220.0, w = 180.0, h = 40.0),
                            text = TextPayload(value = RichTextValue.Plain("From Text")),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s1",
                    background = Fill.Solid("#0B1020"),
                    nodes = listOf(
                        NodeSpec(
                            id = "shared-anchor",
                            frame = FrameSpec(x = 480.0, y = 40.0, w = 60.0, h = 60.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#3B82F6")),
                        ),
                        // Incompatible kind with same ID on s1 (`Shape` vs `Text` on s0)
                        NodeSpec(
                            id = "kind-collide",
                            frame = FrameSpec(x = 40.0, y = 220.0, w = 180.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#14B8A6")),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(
            doc,
            initialConfig = DocumentRenderConfig(
                baseDensity = 1f,
                fontsDir = bundledFontsDirPath(),
                stageDecorator = ContentDecorator { inner ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalStageInspectionRegistry provides registry,
                    ) { inner() }
                },
            ),
        ).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val p25 = h.seekSegment(crossIdx, 0.25f, rebuildScene = true)
            val connInk = measureColorCentroid(p25, 0xFA, 0xCC, 0x15, tolerance = 70)
            assertTrue(connInk.pixelCount >= 25, "Connector to unmatched container child must draw mid-MagicMove (got ${connInk.pixelCount})")
            assertTrue("nested-target" in registry.nodes, "Unmatched container child must register in StageInspectionRegistry")
            // At p=0.25, Exiting kind-collide has alpha ~0.75 > Entering ~0.25, so inspector reports >= 0.70 (not 0.25!)
            val collideAlpha = registry.effectiveAlphaOf("kind-collide")
            assertTrue(collideAlpha >= 0.65f, "Higher-alpha side must own hooks for colliding unmatched ID at p=0.25 (got $collideAlpha)")
        }
    }

    @Test
    fun p13_dottedLineDrawExitKeepsRemainingDotsStationary() {
        val doc = DeckDocument(
            id = "p13-dotted-exit-phase",
            stage = compactStage(320, 480),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "dotted-line",
                            frame = FrameSpec(x = 140.0, y = 20.0, w = 40.0, h = 440.0),
                            shape = ShapePayload(
                                geometry = ShapeGeometry.Path("M 20 10 L 20 430"),
                                stroke = StrokeSpec(
                                    color = "#62EDCB",
                                    width = 6.0,
                                    dash = listOf(0.01, 18.0),
                                    cap = "round",
                                ),
                                heads = dev.sebastiano.ciacola.document.LineHeads(
                                    end = dev.sebastiano.ciacola.document.HeadStyle.Filled,
                                ),
                            ),
                            builds = listOf(
                                Build.Exit(
                                    beat = 1,
                                    effect = "lineDraw",
                                    direction = "startToEnd",
                                    durationMs = 400,
                                    curve = Curve.Linear,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(
            doc,
            initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath()),
        ).use { h ->
            val rest = h.jumpAndSettle(Storyboard.Index(0, 0))
            val beat0State = h.valueStateIndex(0, 0)
            val midExit = h.seekSegment(beat0State, 0.40f)
            fun isMint(buf: ByteArray, idx: Int): Boolean {
                val r = buf[idx].toInt() and 0xFF
                val g = buf[idx + 1].toInt() and 0xFF
                val b = buf[idx + 2].toInt() and 0xFF
                return kotlin.math.abs(r - 0x62) <= 20 &&
                    kotlin.math.abs(g - 0xED) <= 20 &&
                    kotlin.math.abs(b - 0xCB) <= 20
            }
            var mismatches = 0
            for (yy in 280 until 470) {
                for (xx in 140 until 180) {
                    val idx = (yy * rest.width + xx) * 4
                    if (isMint(rest.rgba, idx) != isMint(midExit.rgba, idx)) mismatches++
                }
            }
            assertEquals(
                0,
                mismatches,
                "Remaining dotted stroke + filled head in bottom half must stay pixel-identical during startToEnd Exit",
            )
        }
    }

    @Test
    fun crossSlideBoundaryHandoverEdgeCases() {
        // Covers 5 cross-slide boundary handover edge cases across Rest(A)->Cross(1e-5f)->Cross(0.5f)->Cross(1-1e-5f)->Rest(B):
        // (a) Unmatched Loupe with future enter build (Beat 1) keeps wrapped children visible during Cross;
        // (b) Unsized cold-visit entering Specimen renders non-zero bounds on Frame 0 before Rest(B) ever renders;
        // (c) Matched node reparented out of an unmatched container at (400, 300) to root (900, 300) moves along x=400..900 (~650 at p=0.5), NOT from (0, 0);
        // (d) Colliding unmatched Exiting (opacity=0.2) & Entering (opacity=1.0) roots with id "foo" transfer hooks cleanly (single owner, 0.18 -> 0.50 -> 0.90);
        // (e) Oversized 3376x3061 spotlight root at (-519, -876) stays pixel-aligned across Rest(A) <-> Cross(1e-5f).
        val registry = StageInspectionRegistry()
        val specimens = deckSpecimens {
            specimen("test.unsized") {
                content {
                    Box(Modifier.width(120.dp).height(60.dp).background(Color(0xFF22C55E)))
                }
            }
        }
        val oversizedPath = "M 0 0 L 3376 0 L 3376 3061 L 0 3061 Z M 619 976 L 819 976 L 819 1126 L 619 1126 Z"
        val doc = DeckDocument(
            id = "cross-slide-boundary-handover",
            stage = compactStage(1920, 1080),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0B1020"),
                    transition = linearMm(400).copy(
                        phases = Phases(
                            exit = Timing(0, 400),
                            enter = Timing(0, 400),
                        ),
                    ),
                    nodes = listOf(
                        // (e) Oversized 3376x3061 spotlight cutout at (-519, -876), hole at stage (100..300, 100..250)
                        NodeSpec(
                            id = "oversized-spotlight",
                            frame = FrameSpec(x = -519.0, y = -876.0, w = 3376.0, h = 3061.0),
                            shape = ShapePayload(
                                geometry = ShapeGeometry.Path(oversizedPath),
                                fill = Fill.Solid("#40000000"),
                            ),
                        ),
                        // (c) Matched node starting inside an unmatched container at (400, 300)
                        NodeSpec(
                            id = "unmatched-host-a",
                            frame = FrameSpec(x = 400.0, y = 300.0, w = 160.0, h = 100.0),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "reparented-pill",
                                        frame = FrameSpec(x = 0.0, y = 0.0, w = 80.0, h = 60.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#F59E0B")),
                                    ),
                                ),
                            ),
                        ),
                        // (d) Colliding unmatched Exiting root (Text, incompatible with sB's Shape) with opacity=0.2
                        NodeSpec(
                            id = "foo",
                            frame = FrameSpec(x = 1200.0, y = 700.0, w = 120.0, h = 80.0),
                            opacity = 0.2,
                            text = TextPayload(value = RichTextValue.Plain("foo")),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        // (a) Unmatched Loupe with Beat-1 enter build wrapping Beat-0 visible child
                        NodeSpec(
                            id = "unmatched-loupe",
                            frame = FrameSpec(x = 100.0, y = 500.0, w = 400.0, h = 200.0),
                            loupe = dev.sebastiano.ciacola.document.LoupePayload(
                                target = "loupe-child",
                                zoom = 1.5,
                                diameter = 120.0,
                                children = listOf(
                                    NodeSpec(
                                        id = "loupe-child",
                                        frame = FrameSpec(x = 20.0, y = 20.0, w = 180.0, h = 80.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#38BDF8")),
                                    ),
                                ),
                            ),
                            builds = listOf(
                                Build.Enter(beat = 1, effect = "dissolve", durationMs = 300),
                            ),
                        ),
                        // (b) Unsized cold-visit entering Specimen (no frame w/h)
                        NodeSpec(
                            id = "cold-unsized-specimen",
                            frame = FrameSpec(x = 600.0, y = 600.0),
                            specimen = SpecimenPayload(ref = "test.unsized"),
                        ),
                        // (c) Reparented node moved to root (900, 300)
                        NodeSpec(
                            id = "reparented-pill",
                            frame = FrameSpec(x = 900.0, y = 300.0, w = 80.0, h = 60.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#F59E0B")),
                        ),
                        // (d) Colliding unmatched Entering root with same id "foo" and opacity=1.0
                        NodeSpec(
                            id = "foo",
                            frame = FrameSpec(x = 1200.0, y = 700.0, w = 120.0, h = 80.0),
                            opacity = 1.0,
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#3B82F6")),
                        ),
                    ),
                ),
            ),
        )

        // First render Cross on a cold harness before Rest(B) is ever visited (tests cold unsized specimen measurement!).
        DocumentStageHarness(
            doc,
            initialConfig = DocumentRenderConfig(
                baseDensity = 1f,
                specimens = specimens,
                fontsDir = bundledFontsDirPath(),
                stageDecorator = ContentDecorator { inner ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalStageInspectionRegistry provides registry,
                    ) { inner() }
                },
            ),
        ).use { h ->
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            val crossIdx = h.crossFromStateIndex(0)

            val pStart = h.seekSegment(crossIdx, 1e-5f, rebuildScene = true)
            // (e) Oversized 3376x3061 spotlight cutout must NOT shift across Rest(A) <-> Cross(1e-5f)
            assertEquals(
                0,
                diffFrames(restA, pStart, tolerance = 2).differingPixels,
                "Rest(A) and Cross(1e-5f) must match pixel-for-pixel even with 3376x3061 oversized root",
            )

            // (d) At p=0.1, Exiting foo (0.9 * 0.2 = 0.18) > Entering foo (0.1 * 1.0 = 0.10): Exiting owns hooks (~0.18)
            h.seekSegment(crossIdx, 0.1f, rebuildScene = true)
            val alphaP10 = registry.effectiveAlphaOf("foo")
            assertTrue(
                alphaP10 in 0.14f..0.24f,
                "At p=0.1 colliding Exiting(0.2) must own hooks (expected ~0.18, got $alphaP10)",
            )

            // (d) At p=0.3, Entering foo (0.3 * 1.0 = 0.30) > Exiting foo (0.7 * 0.2 = 0.14); catches wrong exitEff=0.7*1.0!
            h.seekSegment(crossIdx, 0.3f, rebuildScene = true)
            val alphaP30 = registry.effectiveAlphaOf("foo")
            assertTrue(
                alphaP30 in 0.25f..0.35f,
                "At p=0.3 colliding Entering(1.0) must own hooks (expected ~0.30, got $alphaP30)",
            )

            val p50 = h.seekSegment(crossIdx, 0.50f, rebuildScene = true)
            // (a) Unmatched Loupe's Beat-0 child (#38BDF8 at alpha=0.50 over #0B1020 -> RGB(34,102,140)) must stay visible at p=0.50
            val loupeChildInk = measureColorCentroid(p50, 34, 102, 140, tolerance = 25)
            assertTrue(
                loupeChildInk.pixelCount >= 500,
                "Unmatched Loupe child must remain visible mid-MagicMove (got ${loupeChildInk.pixelCount} px)",
            )
            // (b) Cold unsized Specimen (#22C55E 120x60 at alpha=0.50 over #0B1020 -> RGB(23,106,63)) must NOT pin to 0x0 on Frame 0!
            val coldSpecInk = measureColorCentroid(p50, 23, 106, 63, tolerance = 25)
            assertTrue(
                coldSpecInk.pixelCount >= 500,
                "Cold unsized entering Specimen must measure non-zero before Rest(B) (got ${coldSpecInk.pixelCount} px)",
            )
            // (c) Reparented pill (#F59E0B) must interpolate (400,300)->(900,300), centering ~x=690 (NOT flying from 0,0!)
            val pillMid = measureColorCentroid(p50, 0xF5, 0x9E, 0x0B, tolerance = 40)
            assertTrue(
                pillMid.pixelCount >= 1000 && pillMid.x in 650f..730f,
                "Reparented pill at p=0.5 must be at x~690 (got centroid=${pillMid.x}, px=${pillMid.pixelCount})",
            )
            // (d) At p=0.5, Entering foo (0.5 * 1.0 = 0.50) > Exiting foo (0.5 * 0.2 = 0.10)
            val alphaP50 = registry.effectiveAlphaOf("foo")
            assertTrue(
                alphaP50 in 0.45f..0.55f,
                "At p=0.5 colliding Entering(1.0) must own hooks (expected ~0.50, got $alphaP50)",
            )

            // (d) At p=0.9, Entering foo (0.9 * 1.0 = 0.90) > Exiting foo (0.1 * 0.2 = 0.02)
            h.seekSegment(crossIdx, 0.90f, rebuildScene = true)
            val alphaP90 = registry.effectiveAlphaOf("foo")
            assertTrue(
                alphaP90 in 0.85f..0.95f,
                "At p=0.9 colliding Entering(1.0) must own hooks (expected ~0.90, got $alphaP90)",
            )

            val pEnd = h.seekSegment(crossIdx, 1f - 1e-5f, rebuildScene = true)
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            assertEquals(
                0,
                diffFrames(restB, pEnd, tolerance = 2).differingPixels,
                "Cross(1 - 1e-5f) must hand over pixel-identically to Rest(B) for Loupe, unsized Specimen, reparented pill, and foo",
            )
        }
    }

    @Test
    fun p13b_multiContourDottedLineDrawExitKeepsAllContourDotsStationary() {
        val doc = DeckDocument(
            id = "p13b-multi-contour-dotted-exit",
            stage = compactStage(320, 300),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "two-contour-dotted",
                            frame = FrameSpec(x = 10.0, y = 10.0, w = 300.0, h = 280.0),
                            shape = ShapePayload(
                                geometry = ShapeGeometry.Path("M 20 80 L 280 80 M 20 200 L 280 200"),
                                stroke = StrokeSpec(
                                    color = "#62EDCB",
                                    width = 6.0,
                                    dash = listOf(0.01, 20.0),
                                    cap = "round",
                                ),
                            ),
                            builds = listOf(
                                Build.Exit(
                                    beat = 1,
                                    effect = "lineDraw",
                                    direction = "startToEnd",
                                    durationMs = 400,
                                    curve = Curve.Linear,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(
            doc,
            initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath()),
        ).use { h ->
            val rest = h.jumpAndSettle(Storyboard.Index(0, 0))
            val beat0State = h.valueStateIndex(0, 0)
            // At p=0.25, half of contour 0 (first 130px) is erased while contour 0's right half and all of contour 1 remain.
            val midExit = h.seekSegment(beat0State, 0.25f)

            fun isMint(buf: ByteArray, idx: Int): Boolean {
                val r = buf[idx].toInt() and 0xFF
                val g = buf[idx + 1].toInt() and 0xFF
                val b = buf[idx + 2].toInt() and 0xFF
                return abs(r - 0x62) <= 20 && abs(g - 0xED) <= 20 && abs(b - 0xCB) <= 20
            }

            // Contour 0 erased left portion must actually be gone
            var erasedContour0Count = 0
            for (yy in 75..105) {
                for (xx in 25..135) {
                    val idx = (yy * midExit.width + xx) * 4
                    if (isMint(midExit.rgba, idx)) erasedContour0Count++
                }
            }
            assertEquals(0, erasedContour0Count, "Left half of contour 0 must be erased at p=0.25")

            // Contour 0 remaining right dots (x=175..295, y=75..105) stay stationary
            var c0Mismatches = 0
            for (yy in 75..105) {
                for (xx in 175..295) {
                    val idx = (yy * rest.width + xx) * 4
                    if (isMint(rest.rgba, idx) != isMint(midExit.rgba, idx)) c0Mismatches++
                }
            }
            assertEquals(0, c0Mismatches, "Remaining dots on contour 0 must stay world-space stationary")

            // Contour 1 ALL dots (x=20..295, y=195..225) must stay pixel-identical (per-contour dash phase = 0, not contour 0's start)
            var c1Mismatches = 0
            var c1MintCount = 0
            for (yy in 195..225) {
                for (xx in 20..295) {
                    val idx = (yy * rest.width + xx) * 4
                    val restMint = isMint(rest.rgba, idx)
                    if (restMint) c1MintCount++
                    if (restMint != isMint(midExit.rgba, idx)) c1Mismatches++
                }
            }
            assertTrue(c1MintCount >= 200, "Contour 1 must render dotted ink (got $c1MintCount)")
            assertEquals(0, c1Mismatches, "All dots on contour 1 must stay world-space stationary while contour 0 trims")
        }

        // 2. Two crossing dotted contours with translucent stroke color (#80FFFFFF) must composite via single saveLayer
        val crossingDoc = DeckDocument(
            id = "p13b-crossing-translucent-dots",
            stage = compactStage(200, 200),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "cross-dots",
                            frame = FrameSpec(x = 0.0, y = 0.0, w = 200.0, h = 200.0),
                            shape = ShapePayload(
                                geometry = ShapeGeometry.Path("M 100 20 L 100 180 M 20 100 L 180 100"),
                                stroke = StrokeSpec(
                                    color = "#80FFFFFF",
                                    width = 10.0,
                                    dash = listOf(0.01, 20.0),
                                    cap = "round",
                                ),
                            ),
                            builds = listOf(
                                Build.Exit(
                                    beat = 1,
                                    effect = "lineDraw",
                                    direction = "startToEnd",
                                    durationMs = 400,
                                    curve = Curve.Linear,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        DocumentStageHarness(
            crossingDoc,
            initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath()),
        ).use { h ->
            val beat0State = h.valueStateIndex(0, 0)
            val atP02 = h.seekSegment(beat0State, 0.02f)
            val nonOverlapDot = atP02.pixelRgba(100, 40)
            val intersectionDot = atP02.pixelRgba(100, 100)
            // Verify dot at (100, 40) actually rendered at ~50% white over #0B1020 (~133, 136, 144)
            assertTrue(nonOverlapDot[0] in 120..150, "Expected ~50% translucent white dot at (100, 40), got R=${nonOverlapDot[0]}")
            assertTrue(
                abs(intersectionDot[0] - nonOverlapDot[0]) <= 3 &&
                    abs(intersectionDot[1] - nonOverlapDot[1]) <= 3 &&
                    abs(intersectionDot[2] - nonOverlapDot[2]) <= 3,
                "Overlapping crossing dotted contours with #80FFFFFF stroke must NOT double-blend: " +
                    "intersection=${intersectionDot.toList()} vs single=${nonOverlapDot.toList()}",
            )
        }

        // 3. Direct CanvasDrawScope raster proof of PathTrimState.alpha < 1f + Color.White inside drawLineDrawPath
        val directSurface = Surface.makeRasterN32Premul(200, 200)
        try {
            directSurface.canvas.clear(0xFF0B1020.toInt())
            val crossingPath = ComposePath().apply {
                moveTo(100f, 20f)
                lineTo(100f, 180f)
                moveTo(20f, 100f)
                lineTo(180f, 100f)
            }
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = directSurface.canvas.asComposeCanvas(),
                size = Size(200f, 200f),
            ) {
                drawLineDrawPath(
                    source = crossingPath,
                    state = PathTrimState(startFraction = 0f, endFraction = 0.98f, alpha = 0.5f),
                    color = Color.White,
                    strokeWidthPx = 10f,
                    cap = StrokeCap.Round,
                    dash = listOf(0.01, 20.0),
                )
            }
            val directInfo = ImageInfo(200, 200, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            val directBmp = Bitmap()
            val directRgba = try {
                directBmp.allocPixels(directInfo)
                directSurface.readPixels(directBmp, 0, 0)
                directBmp.readPixels(directInfo, 200 * 4, 0, 0)!!
            } finally {
                directBmp.close()
            }
            fun directPixel(x: Int, y: Int): IntArray {
                val i = (y * 200 + x) * 4
                return intArrayOf(
                    directRgba[i].toInt() and 0xFF,
                    directRgba[i + 1].toInt() and 0xFF,
                    directRgba[i + 2].toInt() and 0xFF,
                    directRgba[i + 3].toInt() and 0xFF,
                )
            }
            val nonOverlapDirect = directPixel(100, 40)
            val intersectionDirect = directPixel(100, 100)
            assertTrue(
                nonOverlapDirect[0] in 120..150,
                "Expected ~50% translucent white dot at (100, 40) for PathTrimState.alpha=0.5, got R=${nonOverlapDirect[0]}",
            )
            assertTrue(
                abs(intersectionDirect[0] - nonOverlapDirect[0]) <= 3 &&
                    abs(intersectionDirect[1] - nonOverlapDirect[1]) <= 3 &&
                    abs(intersectionDirect[2] - nonOverlapDirect[2]) <= 3,
                "Overlapping crossing dotted contours with PathTrimState.alpha=0.5 must NOT double-blend: " +
                    "intersection=${intersectionDirect.toList()} vs single=${nonOverlapDirect.toList()}",
            )
        } finally {
            directSurface.close()
        }
    }

    @Test
    fun shapeReferenceSizeAcrossConsecutiveChangesAndZeroPopCrossSlideHandover() {
        val sq100 = "M 0 0 L 100 0 L 100 100 L 0 100 Z"
        val sq80 = "M 0 0 L 80 0 L 80 80 L 0 80 Z"
        fun framePatch(w: Double, h: Double) = kotlinx.serialization.json.buildJsonObject {
            put(
                "frame",
                kotlinx.serialization.json.buildJsonObject {
                    put("w", kotlinx.serialization.json.JsonPrimitive(w))
                    put("h", kotlinx.serialization.json.JsonPrimitive(h))
                },
            )
        }

        // Case 1: Consecutive Build.Change frame-only resizes (100 -> 240 on beat 1, 240 -> 300 on beat 2)
        val docConsecutive = DeckDocument(
            id = "p14-consecutive-changes",
            stage = compactStage(420, 380),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "p-consec",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 100.0, h = 100.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq100), fill = Fill.Solid("#22C55E")),
                            builds = listOf(
                                Build.Change(beat = 1, set = framePatch(240.0, 160.0), durationMs = 400, curve = Curve.Linear),
                                Build.Change(beat = 2, set = framePatch(300.0, 200.0), durationMs = 400, curve = Curve.Linear),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // Directly assert evaluator at Beat 1 rest, Beat 2 mid, and Beat 2 rest
        val colorResolver = DocumentColorResolver(themeSpec = null, baseTheme = resolveDeckTheme(docConsecutive, null), slidePaletteSpec = null)
        val consecSlide = docConsecutive.slides.single()
        val consecRaw = consecSlide.nodes.single()
        val beat1RestPlan = SegmentPlanner.planSegment(docConsecutive, "s0", 1, reducedMotion = false)
        val beat1RestEval = DocumentMotionEvaluator.evaluateNode(
            rawNode = consecRaw,
            slide = consecSlide,
            clock = dev.sebastiano.ciacola.document.DeckClock(
                segment = dev.sebastiano.ciacola.document.DocSegment.Rest("s0", 1),
                t = 0f,
                effMs = 0f,
            ),
            segmentPlan = beat1RestPlan,
            stageSize = Size(420f, 380f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        assertEquals(Size(100f, 100f), beat1RestEval.shapeReferenceSize, "Beat 1 rest must keep base authored Size(100, 100)")

        val beat2Plan = SegmentPlanner.planSegment(docConsecutive, "s0", 2, reducedMotion = false)
        val beat2MidEval = DocumentMotionEvaluator.evaluateNode(
            rawNode = consecRaw,
            slide = consecSlide,
            clock = dev.sebastiano.ciacola.document.DeckClock(
                segment = dev.sebastiano.ciacola.document.DocSegment.Beat("s0", 1),
                t = 200f,
                effMs = 400f,
            ),
            segmentPlan = beat2Plan,
            stageSize = Size(420f, 380f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        assertEquals(Size(100f, 100f), beat2MidEval.shapeReferenceSize, "Beat 2 mid-change must keep base authored Size(100, 100)")

        val beat2RestEval = DocumentMotionEvaluator.evaluateNode(
            rawNode = consecRaw,
            slide = consecSlide,
            clock = dev.sebastiano.ciacola.document.DeckClock(
                segment = dev.sebastiano.ciacola.document.DocSegment.Rest("s0", 2),
                t = 0f,
                effMs = 0f,
            ),
            segmentPlan = beat2Plan,
            stageSize = Size(420f, 380f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        assertEquals(Size(100f, 100f), beat2RestEval.shapeReferenceSize, "Beat 2 rest must keep base authored Size(100, 100)")

        // Same-beat sequential Change builds (100 -> 200, then 200 -> 300 on Beat 1) + reducedMotion=true multi-Change
        val docSameBeat = DeckDocument(
            id = "p14-same-beat-changes",
            stage = compactStage(420, 380),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "p-samebeat",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 100.0, h = 100.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq100), fill = Fill.Solid("#22C55E")),
                            builds = listOf(
                                Build.Change(beat = 1, id = "c1", set = framePatch(200.0, 140.0), delayMs = 0, durationMs = 300, curve = Curve.Linear),
                                Build.Change(beat = 1, id = "c2", set = framePatch(300.0, 200.0), delayMs = 400, durationMs = 300, curve = Curve.Linear),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val sameBeatSlide = docSameBeat.slides.single()
        val sameBeatRaw = sameBeatSlide.nodes.single()
        val sameBeatPlan = SegmentPlanner.planSegment(docSameBeat, "s0", 1, reducedMotion = false)
        for (tMs in listOf(150f, 350f, 550f)) {
            val ev = DocumentMotionEvaluator.evaluateNode(
                rawNode = sameBeatRaw,
                slide = sameBeatSlide,
                clock = dev.sebastiano.ciacola.document.DeckClock(
                    segment = dev.sebastiano.ciacola.document.DocSegment.Beat("s0", 0),
                    t = tMs,
                    effMs = 700f,
                ),
                segmentPlan = sameBeatPlan,
                stageSize = Size(420f, 380f),
                densityValue = 1f,
                colorResolver = colorResolver,
                reducedMotion = false,
            )
            assertEquals(Size(100f, 100f), ev.shapeReferenceSize, "Same-beat sequential Change at t=$tMs must keep Size(100, 100)")
        }
        val sameBeatRmPlan = SegmentPlanner.planSegment(docSameBeat, "s0", 1, reducedMotion = true)
        val sameBeatRmEval = DocumentMotionEvaluator.evaluateNode(
            rawNode = sameBeatRaw,
            slide = sameBeatSlide,
            clock = dev.sebastiano.ciacola.document.DeckClock(
                segment = dev.sebastiano.ciacola.document.DocSegment.Beat("s0", 0),
                t = 150f,
                effMs = 300f,
            ),
            segmentPlan = sameBeatRmPlan,
            stageSize = Size(420f, 380f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = true,
        )
        assertEquals(Size(100f, 100f), sameBeatRmEval.shapeReferenceSize, "reducedMotion multi-Change must keep Size(100, 100)")

        // Verify afterGeom != prevGeom & upToBuildIndex when a Build.Change updates BOTH shape.geometry AND frame
        val rect200x100 = "M 0 0 L 200 0 L 200 100 L 0 100 Z"
        val docGeomChange = DeckDocument(
            id = "p14-geom-and-frame-change",
            stage = compactStage(420, 380),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "p-geom",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 100.0, h = 100.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq100), fill = Fill.Solid("#22C55E")),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    set = kotlinx.serialization.json.buildJsonObject {
                                        put(
                                            "frame",
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("w", kotlinx.serialization.json.JsonPrimitive(200.0))
                                                put("h", kotlinx.serialization.json.JsonPrimitive(100.0))
                                            },
                                        )
                                        put(
                                            "shape",
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("geometry", kotlinx.serialization.json.buildJsonObject {
                                                    put("path", kotlinx.serialization.json.JsonPrimitive(rect200x100))
                                                })
                                            },
                                        )
                                    },
                                    durationMs = 400,
                                    curve = Curve.Linear,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val geomSlide = docGeomChange.slides.single()
        val geomRaw = geomSlide.nodes.single()
        val geomPlan = SegmentPlanner.planSegment(docGeomChange, "s0", 1, reducedMotion = false)
        val geomAt49 = DocumentMotionEvaluator.evaluateNode(
            rawNode = geomRaw,
            slide = geomSlide,
            clock = dev.sebastiano.ciacola.document.DeckClock(
                segment = dev.sebastiano.ciacola.document.DocSegment.Beat("s0", 0),
                t = 196f,
                effMs = 400f,
            ),
            segmentPlan = geomPlan,
            stageSize = Size(420f, 380f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        assertEquals(Size(100f, 100f), geomAt49.shapeReferenceSize, "Before 0.5 geometry swap, ref must be old Size(100, 100)")
        val geomAt51 = DocumentMotionEvaluator.evaluateNode(
            rawNode = geomRaw,
            slide = geomSlide,
            clock = dev.sebastiano.ciacola.document.DeckClock(
                segment = dev.sebastiano.ciacola.document.DocSegment.Beat("s0", 0),
                t = 204f,
                effMs = 400f,
            ),
            segmentPlan = geomPlan,
            stageSize = Size(420f, 380f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        assertEquals(Size(200f, 100f), geomAt51.shapeReferenceSize, "After 0.5 geometry swap, ref must be new Size(200, 100)")
        val geomAtRest = DocumentMotionEvaluator.evaluateNode(
            rawNode = geomRaw,
            slide = geomSlide,
            clock = dev.sebastiano.ciacola.document.DeckClock(
                segment = dev.sebastiano.ciacola.document.DocSegment.Rest("s0", 1),
                t = 0f,
                effMs = 0f,
            ),
            segmentPlan = geomPlan,
            stageSize = Size(420f, 380f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        assertEquals(Size(200f, 100f), geomAtRest.shapeReferenceSize, "Rest after geometry+frame Change must use new authored Size(200, 100)")

        DocumentStageHarness(docConsecutive, initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath())).use { h ->
            val restB1 = h.jumpAndSettle(Storyboard.Index(0, 1))
            val b1State = h.valueStateIndex(0, 1)
            val b2Mid = h.seekSegment(b1State, 0.5f, rebuildScene = true)
            val restB2 = h.jumpAndSettle(Storyboard.Index(0, 2))
            val b1W = measureColorCentroid(restB1, 0x22, 0xC5, 0x5E).let { it.maxX - it.minX + 1 }
            val midW = measureColorCentroid(b2Mid, 0x22, 0xC5, 0x5E).let { it.maxX - it.minX + 1 }
            val b2W = measureColorCentroid(restB2, 0x22, 0xC5, 0x5E).let { it.maxX - it.minX + 1 }
            assertTrue(b1W in 236..244, "Beat 1 rest must be ~240px wide (got $b1W)")
            assertTrue(midW in 264..276, "Beat 2 p=0.5 must be ~270px wide with zero snap back to 100px (got $midW)")
            assertTrue(b2W in 296..304, "Beat 2 rest must be ~300px wide (got $b2W)")
        }

        // Case 2 + Case 3: Cross-slide Magic Move with:
        // - Identical path string & different authored frames (`80x80` on A -> `160x160` on B)
        // - Matched path resized by Build.Change on Slide A (`100 -> 240`), then entering Cross(A -> B)
        // - Unmatched path resized by Build.Change on Slide A (`100 -> 240`), then entering Cross(A -> B) via RenderStaticOverlaySubtree
        val docCross = DeckDocument(
            id = "p14-cross-handover",
            stage = compactStage(640, 400),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    transition = linearMm(400),
                    nodes = listOf(
                        NodeSpec(
                            id = "same-path-diff-frame",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 80.0, h = 80.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq80), fill = Fill.Solid("#3B82F6")),
                        ),
                        NodeSpec(
                            id = "resized-matched",
                            frame = FrameSpec(x = 20.0, y = 120.0, w = 100.0, h = 100.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq100), fill = Fill.Solid("#22C55E")),
                            builds = listOf(Build.Change(beat = 1, set = framePatch(240.0, 100.0), durationMs = 200)),
                        ),
                        NodeSpec(
                            id = "resized-unmatched",
                            frame = FrameSpec(x = 20.0, y = 250.0, w = 100.0, h = 100.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq100), fill = Fill.Solid("#F59E0B")),
                            builds = listOf(Build.Change(beat = 1, set = framePatch(240.0, 100.0), durationMs = 200)),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0B1020"),
                    nodes = listOf(
                        NodeSpec(
                            id = "same-path-diff-frame",
                            frame = FrameSpec(x = 340.0, y = 20.0, w = 160.0, h = 160.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq80), fill = Fill.Solid("#3B82F6")),
                        ),
                        NodeSpec(
                            id = "resized-matched",
                            frame = FrameSpec(x = 340.0, y = 220.0, w = 240.0, h = 100.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Path(sq100), fill = Fill.Solid("#22C55E")),
                        ),
                    ),
                ),
            ),
        )

        DocumentStageHarness(docCross, initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath())).use { h ->
            val restA = h.jumpAndSettle(Storyboard.Index(0, 1))
            val crossIdx = h.crossFromStateIndex(0)
            val pStart = h.seekSegment(crossIdx, 1e-5f, rebuildScene = true)
            val p49 = h.seekSegment(crossIdx, 0.49f, rebuildScene = true)
            val p51 = h.seekSegment(crossIdx, 0.51f, rebuildScene = true)
            val pEnd = h.seekSegment(crossIdx, 1f - 1e-5f, rebuildScene = true)
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))

            assertEquals(
                0,
                diffFrames(restA, pStart, tolerance = 2).differingPixels,
                "Rest(A) and Cross(1e-5f) must be pixel-identical for matched and unmatched resized paths",
            )
            assertEquals(
                0,
                diffFrames(restB, pEnd, tolerance = 2).differingPixels,
                "Cross(1 - 1e-5f) and Rest(B) must be pixel-identical for identical-path different-frame pairs",
            )

            // Verify zero t=0.5 mid-flight snap across 0.49 -> 0.51 on BOTH same-path pairs
            val blueW49 = measureColorCentroid(p49, 0x3B, 0x82, 0xF6).let { it.maxX - it.minX + 1 }
            val blueW51 = measureColorCentroid(p51, 0x3B, 0x82, 0xF6).let { it.maxX - it.minX + 1 }
            assertTrue(
                abs(blueW49 - blueW51) <= 6,
                "same-path-diff-frame must NOT snap across t=0.5 (w49=$blueW49, w51=$blueW51)",
            )
            val greenW49 = measureColorCentroid(p49, 0x22, 0xC5, 0x5E).let { it.maxX - it.minX + 1 }
            val greenW51 = measureColorCentroid(p51, 0x22, 0xC5, 0x5E).let { it.maxX - it.minX + 1 }
            assertTrue(
                abs(greenW49 - greenW51) <= 6,
                "resized-matched must NOT snap across t=0.5 (w49=$greenW49, w51=$greenW51)",
            )
        }
    }

    @Test
    fun p14b_unmatchedExitingResizedChildInsideMatchedContainerWithCollidingToSlideId() {
        val sq100 = "M 0 0 L 100 0 L 100 100 L 0 100 Z"
        fun framePatch(w: Double, h: Double) = kotlinx.serialization.json.buildJsonObject {
            put(
                "frame",
                kotlinx.serialization.json.buildJsonObject {
                    put("w", kotlinx.serialization.json.JsonPrimitive(w))
                    put("h", kotlinx.serialization.json.JsonPrimitive(h))
                },
            )
        }
        val doc = DeckDocument(
            id = "p14b-unmatched-child-resized-path",
            stage = compactStage(640, 320),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    transition = linearMm(400).copy(
                        phases = Phases(exit = Timing(0, 400), enter = Timing(0, 400)),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "host",
                            frame = FrameSpec(x = 40.0, y = 40.0, w = 280.0, h = 200.0),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "colliding-child",
                                        frame = FrameSpec(x = 10.0, y = 20.0, w = 100.0, h = 100.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Path(sq100), fill = Fill.Solid("#F59E0B")),
                                        builds = listOf(Build.Change(beat = 1, set = framePatch(240.0, 100.0), durationMs = 200)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#0B1020"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "host",
                            frame = FrameSpec(x = 320.0, y = 40.0, w = 280.0, h = 200.0),
                            stack = ContainerPayload(children = emptyList()),
                        ),
                        // Incompatible kind sharing the same node ID on toSlide with its own Build.Change
                        NodeSpec(
                            id = "colliding-child",
                            frame = FrameSpec(x = 40.0, y = 260.0, w = 220.0, h = 40.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("ToSlide"),
                                size = 28.0,
                                color = "#22D3EE",
                            ),
                            builds = listOf(Build.Change(beat = 1, set = framePatch(240.0, 40.0), durationMs = 200)),
                        ),
                    ),
                ),
            ),
        )

        // Direction A: Priming sB's ("sB", "colliding-child", 0) cache first must NOT shrink sA's exiting path
        DocumentStageHarness(doc, initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath())).use { h ->
            h.seekSegment(h.valueStateIndex(1, 0), 0.5f, rebuildScene = false)
            h.jumpAndSettle(Storyboard.Index(1, 1))

            val restA = h.jumpAndSettle(Storyboard.Index(0, 1))
            val crossIdx = h.crossFromStateIndex(0)
            val pStart = h.seekSegment(crossIdx, 1e-5f, rebuildScene = false)
            assertEquals(
                0,
                diffFrames(restA, pStart, tolerance = 2).differingPixels,
                "Unmatched exiting Build.Change-resized path inside matched Stack must match Rest(A) at Cross(1e-5f)",
            )
            val p25 = h.seekSegment(crossIdx, 0.25f, rebuildScene = false)
            // Amber #F59E0B at alpha=0.75 over #0B1020 => RGB(187, 122, 16)
            val amberW = measureColorCentroid(p25, 187, 122, 16, tolerance = 30).let { it.maxX - it.minX + 1 }
            assertTrue(
                amberW in 232..246,
                "Unmatched exiting resized child must stay ~240px wide at Cross(0.25f) even after sB cache is primed (got $amberW)",
            )
        }

        // Direction B: Cold Cross(sA -> sB) must NOT poison sB's ("sB", "colliding-child", 0) cache
        DocumentStageHarness(doc, initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath())).use { h ->
            h.jumpAndSettle(Storyboard.Index(0, 1))
            val crossIdx = h.crossFromStateIndex(0)
            h.seekSegment(crossIdx, 0.25f, rebuildScene = false)

            val sBMid = h.seekSegment(h.valueStateIndex(1, 0), 0.5f, rebuildScene = false)
            var amberCount = 0
            for (y in 0 until sBMid.height) {
                for (x in 0 until sBMid.width) {
                    val px = sBMid.pixelRgba(x, y)
                    if (abs(px[0] - 0xF5) <= 24 && abs(px[1] - 0x9E) <= 24 && abs(px[2] - 0x0B) <= 24) {
                        amberCount++
                    }
                }
            }
            assertEquals(0, amberCount, "Cold Cross(sA->sB) must NOT poison sB Beat 0->1 with sA's amber shape")
            val cyanInk = measureColorCentroid(sBMid, 0x22, 0xD3, 0xEE, tolerance = 45, region = intArrayOf(40, 250, 280, 310))
            assertTrue(cyanInk.pixelCount >= 40, "sB Beat 0->1 must render sB's cyan Text node (got ${cyanInk.pixelCount} px)")
        }
    }

    @Test
    fun p15_unmatchedContainerImageFillSurfaceAndRootDescendantEffectiveAlpha() {
        val tempAssetsDir = java.nio.file.Files.createTempDirectory("ciacola-p15-assets").toFile()
        try {
            // Write a solid #EC4899 32x32 PNG into tempAssetsDir/p15-image-and-alpha.assets/bg.png
            val deckAssetDir = File(tempAssetsDir, "p15-image-and-alpha.assets").apply { mkdirs() }
            val surface = Surface.makeRasterN32Premul(32, 32)
            try {
                surface.canvas.clear(0xFFEC4899.toInt())
                val snap = surface.makeImageSnapshot()
                try {
                    val png = snap.encodeToData(EncodedImageFormat.PNG)!!.bytes
                    File(deckAssetDir, "bg.png").writeBytes(png)
                } finally {
                    snap.close()
                }
            } finally {
                surface.close()
            }

            val registry = StageInspectionRegistry()
            val doc = DeckDocument(
                id = "p15-image-and-alpha",
                stage = compactStage(640, 360),
                slides = listOf(
                    SlideSpec(
                        id = "sA",
                        background = Fill.Solid("#0B1020"),
                        transition = linearMm(400).copy(
                            phases = Phases(
                                exit = Timing(0, 400),
                                enter = Timing(0, 400),
                            ),
                        ),
                        nodes = listOf(
                            NodeSpec(
                                id = "shared-anchor",
                                frame = FrameSpec(x = 20.0, y = 20.0, w = 40.0, h = 40.0),
                                shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#3B82F6")),
                            ),
                            NodeSpec(
                                id = "unmatched-img-stack",
                                frame = FrameSpec(x = 100.0, y = 40.0, w = 140.0, h = 100.0),
                                stack = ContainerPayload(
                                    surface = SurfaceSpec(
                                        fill = Fill.Image(ImageFillSpec(src = "bg.png", fit = "cover")),
                                        cornerRadius = 16.0,
                                    ),
                                    children = emptyList(),
                                ),
                            ),
                            NodeSpec(
                                id = "unmatched-img-row",
                                frame = FrameSpec(x = 280.0, y = 40.0, w = 140.0, h = 100.0),
                                row = ContainerPayload(
                                    surface = SurfaceSpec(
                                        fill = Fill.Image(ImageFillSpec(src = "bg.png", fit = "cover")),
                                    ),
                                    children = emptyList(),
                                ),
                            ),
                            NodeSpec(
                                id = "semi-root",
                                frame = FrameSpec(x = 100.0, y = 180.0, w = 160.0, h = 120.0),
                                opacity = 0.5,
                                stack = ContainerPayload(
                                    children = listOf(
                                        NodeSpec(
                                            id = "semi-child",
                                            frame = FrameSpec(x = 10.0, y = 10.0, w = 80.0, h = 80.0),
                                            opacity = 0.8,
                                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#22C55E")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    SlideSpec(
                        id = "sB",
                        background = Fill.Solid("#0B1020"),
                        nodes = listOf(
                            NodeSpec(
                                id = "shared-anchor",
                                frame = FrameSpec(x = 560.0, y = 20.0, w = 40.0, h = 40.0),
                                shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#3B82F6")),
                            ),
                        ),
                    ),
                ),
            )

            DocumentStageHarness(
                doc,
                initialConfig = DocumentRenderConfig(
                    baseDensity = 1f,
                    assetsRootDir = tempAssetsDir.absolutePath,
                    fontsDir = bundledFontsDirPath(),
                    stageDecorator = ContentDecorator { inner ->
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalStageInspectionRegistry provides registry,
                        ) { inner() }
                    },
                ),
            ).use { h ->
                val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
                val stackInkRest = measureColorCentroid(restA, 0xEC, 0x48, 0x99, tolerance = 24, region = intArrayOf(100, 40, 240, 140))
                val rowInkRest = measureColorCentroid(restA, 0xEC, 0x48, 0x99, tolerance = 24, region = intArrayOf(280, 40, 420, 140))
                assertTrue(stackInkRest.pixelCount >= 10_000, "Rest(A) Stack Fill.Image must render (got ${stackInkRest.pixelCount})")
                assertTrue(rowInkRest.pixelCount >= 10_000, "Rest(A) Row Fill.Image must render (got ${rowInkRest.pixelCount})")

                val crossIdx = h.crossFromStateIndex(0)
                val pStart = h.seekSegment(crossIdx, 1e-5f, rebuildScene = true)
                assertEquals(
                    0,
                    diffFrames(restA, pStart, tolerance = 2).differingPixels,
                    "Unmatched Stack/Row Fill.Image containers must hand over pixel-identically from Rest(A) to Cross(1e-5f)",
                )

                // At p=0.20 (trackAlpha=0.80): semi-root (opacity 0.5) => 0.40, semi-child (opacity 0.8) => 0.32 (NOT double-multiplied 0.16!)
                h.seekSegment(crossIdx, 0.20f, rebuildScene = true)
                val rootAlpha = registry.effectiveAlphaOf("semi-root")
                val childAlpha = registry.effectiveAlphaOf("semi-child")
                assertTrue(
                    rootAlpha in 0.36f..0.44f,
                    "Unmatched semi-root at p=0.20 must report ~0.40 effectiveAlpha (got $rootAlpha)",
                )
                assertTrue(
                    childAlpha in 0.28f..0.36f,
                    "Child of semi-root (0.5 * 0.8 * 0.8) must report ~0.32 effectiveAlpha, not double-counted ~0.16 (got $childAlpha)",
                )
            }
        } finally {
            tempAssetsDir.deleteRecursively()
        }
    }
}
