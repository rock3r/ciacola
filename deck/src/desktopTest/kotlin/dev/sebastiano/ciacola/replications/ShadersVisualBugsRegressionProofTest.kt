package dev.sebastiano.ciacola.replications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkiaRect

@OptIn(ExperimentalComposeUiApi::class)
class ShadersVisualBugsRegressionProofTest {

    private data class RenderedFrame(
        val width: Int,
        val height: Int,
        val rgba: ByteArray,
    )

    private data class InkMetrics(
        val pixelCount: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val centroidX: Double,
        val centroidY: Double,
    ) {
        val heightPx: Int get() = if (pixelCount == 0) 0 else (maxY - minY + 1)
    }

    private fun proofDir(): File {
        val prop = System.getProperty("ciacola.proof.dir")?.takeIf { it.isNotBlank() }
        val dir = prop?.let(::File) ?: File("build/reports/visual-fixes-proof")
        dir.mkdirs()
        return dir
    }

    private inner class DeckSliceProofHarness(
        doc: DeckDocument,
        specimens: DeckSpecimenRegistry,
        initialIndex: Storyboard.Index = Storyboard.Index(0, 0),
    ) : AutoCloseable {
        val width: Int = doc.stage.width
        val height: Int = doc.stage.height
        private var clockNanos: Long = 0L
        private lateinit var _animatic: Animatic
        private lateinit var _scope: CoroutineScope

        val animatic: Animatic get() = _animatic

        val scene: ImageComposeScene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f, 1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            _scope = rememberCoroutineScope()
            val session = RenderSession.forVideo(doc)
            val storyboard = buildDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    baseDensity = 1f,
                    specimens = specimens,
                ),
            )
            CiacolaJewelTheme(isDark = false) {
                DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                    val a = rememberAnimatic(initialIndex = initialIndex, storyboard = { storyboard })
                    _animatic = a
                    Box(Modifier.fillMaxSize()) {
                        Easel(a, mode = SceneMode.Preview)
                    }
                }
            }
        }

        init {
            settle(4)
        }

        fun renderStep(stepNanos: Long = 10_000_000L): RenderedFrame {
            clockNanos += stepNanos
            val img = scene.render(clockNanos)
            try {
                val bmp = Bitmap()
                val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
                bmp.allocPixels(info)
                assertTrue(img.readPixels(bmp, 0, 0), "Failed to read Skia Image pixels")
                val bytes = bmp.readPixels(info, width * 4, 0, 0) ?: ByteArray(width * height * 4)
                bmp.close()
                return RenderedFrame(width, height, bytes)
            } finally {
                img.close()
            }
        }

        fun settle(frames: Int = 4): RenderedFrame {
            var last: RenderedFrame? = null
            repeat(frames.coerceAtLeast(1)) { last = renderStep() }
            return checkNotNull(last)
        }

        private fun drainJob(job: Job) {
            var steps = 0
            while (!job.isCompleted && steps++ < 400) {
                renderStep()
            }
            assertTrue(job.isCompleted, "Animatic job did not complete within 400 steps")
        }

        fun jumpAndSettle(index: Storyboard.Index): RenderedFrame {
            val job = _scope.launch { _animatic.jumpTo(index) }
            drainJob(job)
            return settle(4)
        }

        fun seekSegment(fromStateIndex: Int, fraction: Float): RenderedFrame {
            val job = _scope.launch {
                _animatic.seekToSegment(fromStateIndex, fraction, rebuildScene = true)
            }
            drainJob(job)
            return renderStep(1_000_000L)
        }

        fun valueStateIndex(sceneIdx: Int, beatIdx: Int): Int =
            _animatic.stateDescriptors.first {
                it.sceneIndex == sceneIdx &&
                    it.frameIndex == beatIdx &&
                    !it.isBoundaryStart &&
                    !it.isBoundaryEnd
            }.stateIndex

        fun crossFromStateIndex(fromSceneIdx: Int): Int =
            _animatic.stateDescriptors.first {
                it.sceneIndex == fromSceneIdx && it.isBoundaryEnd
            }.stateIndex

        fun entranceFromStateIndex(sceneIdx: Int): Int =
            _animatic.stateDescriptors.first {
                it.sceneIndex == sceneIdx && it.isBoundaryStart
            }.stateIndex

        override fun close() {
            scene.close()
        }
    }

    private fun measureInk(
        frame: RenderedFrame,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        r: Int,
        g: Int,
        b: Int,
        tolerance: Int = 24,
    ): InkMetrics {
        val sx = x0.coerceIn(0, frame.width - 1)
        val sy = y0.coerceIn(0, frame.height - 1)
        val ex = x1.coerceIn(0, frame.width - 1)
        val ey = y1.coerceIn(0, frame.height - 1)
        var count = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var sumX = 0.0
        var sumY = 0.0
        val rgba = frame.rgba
        val w = frame.width
        for (y in sy..ey) {
            val rowOff = y * w * 4
            for (x in sx..ex) {
                val i = rowOff + x * 4
                val pr = rgba[i].toInt() and 0xFF
                val pg = rgba[i + 1].toInt() and 0xFF
                val pb = rgba[i + 2].toInt() and 0xFF
                if (abs(pr - r) <= tolerance && abs(pg - g) <= tolerance && abs(pb - b) <= tolerance) {
                    count++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    sumX += x
                    sumY += y
                }
            }
        }
        return if (count == 0) {
            InkMetrics(0, 0, 0, 0, 0, 0.0, 0.0)
        } else {
            InkMetrics(count, minX, minY, maxX, maxY, sumX / count, sumY / count)
        }
    }

    private fun countDiffPixels(a: RenderedFrame, b: RenderedFrame, tolerance: Int = 2): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var diffs = 0
        val ra = a.rgba
        val rb = b.rgba
        for (i in ra.indices step 4) {
            val dr = abs((ra[i].toInt() and 0xFF) - (rb[i].toInt() and 0xFF))
            val dg = abs((ra[i + 1].toInt() and 0xFF) - (rb[i + 1].toInt() and 0xFF))
            val db = abs((ra[i + 2].toInt() and 0xFF) - (rb[i + 2].toInt() and 0xFF))
            if (dr > tolerance || dg > tolerance || db > tolerance) diffs++
        }
        return diffs
    }

    private fun cropFrame(frame: RenderedFrame, x0: Int, y0: Int, x1: Int, y1: Int): RenderedFrame {
        val sx = x0.coerceIn(0, frame.width - 1)
        val sy = y0.coerceIn(0, frame.height - 1)
        val ex = x1.coerceIn(sx + 1, frame.width)
        val ey = y1.coerceIn(sy + 1, frame.height)
        val cw = ex - sx
        val ch = ey - sy
        val out = ByteArray(cw * ch * 4)
        for (y in 0 until ch) {
            System.arraycopy(frame.rgba, ((sy + y) * frame.width + sx) * 4, out, y * cw * 4, cw * 4)
        }
        return RenderedFrame(cw, ch, out)
    }

    private fun saveContactSheet(
        file: File,
        title: String,
        frames: List<Pair<String, RenderedFrame>>,
        columns: Int = frames.size.coerceAtMost(4),
        cellWidth: Int = 640,
    ) {
        if (frames.isEmpty()) return
        val cols = columns.coerceIn(1, frames.size)
        val rows = ceil(frames.size / cols.toDouble()).toInt()
        val first = frames.first().second
        val cellHeight = (cellWidth * (first.height.toDouble() / first.width)).toInt().coerceAtLeast(120)
        val pad = 16
        val labelH = 26
        val headerH = 44
        val totalW = cols * cellWidth + (cols + 1) * pad
        val totalH = headerH + rows * (cellHeight + labelH + pad) + pad

        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(totalW, totalH)
        val canvas: Canvas = surface.canvas
        canvas.clear(0xFF151821.toInt())

        val titlePaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
        val labelPaint = Paint().apply { color = 0xFFD0D5DD.toInt() }
        val borderPaint = Paint().apply {
            color = 0xFF334155.toInt()
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = 2f
        }
        val titleFont = Font(null, 20f)
        val labelFont = Font(null, 15f)
        canvas.drawString(title, pad.toFloat(), 28f, titleFont, titlePaint)

        frames.forEachIndexed { idx, (label, rf) ->
            val col = idx % cols
            val row = idx / cols
            val x = pad + col * (cellWidth + pad)
            val y = headerH + row * (cellHeight + labelH + pad)
            val bmp = Bitmap()
            val info = ImageInfo(rf.width, rf.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            bmp.allocPixels(info)
            bmp.installPixels(info, rf.rgba, rf.width * 4)
            val img = SkiaImage.makeFromBitmap(bmp)
            val dst = SkiaRect.makeXYWH(x.toFloat(), y.toFloat(), cellWidth.toFloat(), cellHeight.toFloat())
            canvas.drawImageRect(img, dst)
            canvas.drawRect(dst, borderPaint)
            canvas.drawString(label, x.toFloat(), (y + cellHeight + 19).toFloat(), labelFont, labelPaint)
            img.close()
            bmp.close()
        }

        val outImg = surface.makeImageSnapshot()
        val encoded = outImg.encodeToData(EncodedImageFormat.PNG)!!
        file.parentFile?.mkdirs()
        file.writeBytes(encoded.bytes)
        outImg.close()
        surface.close()
    }

    @Test
    fun ac1_shadersS16S14AndNjptS050LineDrawArrowheadFollowsTipWithCleanDottedCaps() {
        val shEntry = DeckCatalog.shadersQuickie2025
        val shDoc = loadClasspathDeckDocument(shEntry)

        // 1. Shaders s16 line-7 (#50BFA0 dotted arrow from x~746 -> x~641, y~351, beat 2)
        val s15 = shDoc.slides.first { it.id == "s15" }
        val s16 = shDoc.slides.first { it.id == "s16" }
        val s16Slice = shDoc.copy(slides = listOf(s15, s16))
        val proofFrames = mutableListOf<Pair<String, RenderedFrame>>()

        DeckSliceProofHarness(s16Slice, shEntry.specimens, Storyboard.Index(1, 1)).use { h ->
            val beat1State = h.valueStateIndex(1, 1) // beat 1 -> beat 2: vectors (0..500ms) then line-7 (500..1200ms)
            val atBeat1 = h.jumpAndSettle(Storyboard.Index(1, 1))
            val atP55 = h.seekSegment(beat1State, 0.55f)
            val atP72 = h.seekSegment(beat1State, 0.72f)
            val atP88 = h.seekSegment(beat1State, 0.88f)
            val atBeat2 = h.jumpAndSettle(Storyboard.Index(1, 2))

            // Beat 1: line-7 not drawn yet
            val beforeInk = measureInk(atBeat1, 635, 336, 755, 366, 0x50, 0xBF, 0xA0, tolerance = 28)
            assertEquals(0, beforeInk.pixelCount, "s16 line-7 should not appear at beat 1")

            // At p=0.55 (~23% into line-7): arrowhead must NOT be pinned at final left destination (x=636..660)!
            val destTipAt55 = measureInk(atP55, 636, 340, 660, 362, 0x50, 0xBF, 0xA0, tolerance = 28)
            val growingInkAt55 = measureInk(atP55, 635, 336, 755, 366, 0x50, 0xBF, 0xA0, tolerance = 28)
            assertEquals(0, destTipAt55.pixelCount, "s16 line-7 arrowhead must NOT sit at destination x=636..660 at p=0.55")
            assertTrue(growingInkAt55.pixelCount >= 20, "s16 line-7 must be actively drawing at p=0.55 (got ${growingInkAt55.pixelCount})")

            // Tip moves leftward from p=0.55 to p=0.72, still before destination x=636..654
            val growingInkAt72 = measureInk(atP72, 635, 336, 755, 366, 0x50, 0xBF, 0xA0, tolerance = 28)
            assertTrue(
                growingInkAt72.minX < growingInkAt55.minX,
                "s16 line-7 tip minX must advance left from p=0.55 (${growingInkAt55.minX}) to p=0.72 (${growingInkAt72.minX})",
            )

            // Beat 2 rest: tip reaches destination x=638..658, and dot height >= 4px (round dots, not 0.5px slivers)
            val destTipRest = measureInk(atBeat2, 636, 340, 660, 362, 0x50, 0xBF, 0xA0, tolerance = 28)
            assertTrue(destTipRest.pixelCount >= 60, "s16 line-7 filled arrowhead must appear at destination at beat 2")
            val shaftDotsRest = measureInk(atBeat2, 690, 341, 745, 361, 0x50, 0xBF, 0xA0, tolerance = 28)
            assertTrue(shaftDotsRest.heightPx in 4..6, "s16 line-7 dotted stroke (w=4) must render circular 4px dots, got h=${shaftDotsRest.heightPx}")
            val atP100 = h.seekSegment(beat1State, 1.0f)
            assertEquals(0, countDiffPixels(atBeat2, atP100, tolerance = 2), "s16 line-7 p=1.0 must match Rest")

            proofFrames += "Shaders s16 line-7 p=0.55" to cropFrame(atP55, 580, 310, 780, 410)
            proofFrames += "Shaders s16 line-7 p=0.72" to cropFrame(atP72, 580, 310, 780, 410)
            proofFrames += "Shaders s16 line-7 p=0.88" to cropFrame(atP88, 580, 310, 780, 410)
            proofFrames += "Shaders s16 line-7 Rest" to cropFrame(atBeat2, 580, 310, 780, 410)
        }

        // 2. Shaders s14 line-7/line-8 (#62EDCB dotted axes, beat 2 Enter + beat 3 Exit dot stationarity)
        val s13 = shDoc.slides.first { it.id == "s13" }
        val s14 = shDoc.slides.first { it.id == "s14" }
        DeckSliceProofHarness(shDoc.copy(slides = listOf(s13, s14)), shEntry.specimens, Storyboard.Index(1, 1)).use { h ->
            val beat1State = h.valueStateIndex(1, 1)
            val beat2State = h.valueStateIndex(1, 2)
            val atP52 = h.seekSegment(beat1State, 0.52f)
            val atP100 = h.seekSegment(beat1State, 1.0f)
            val atBeat2 = h.jumpAndSettle(Storyboard.Index(1, 2))
            assertEquals(0, countDiffPixels(atBeat2, atP100, tolerance = 2), "s14 line-7/8 p=1.0 must match Rest")
            // line-7 vertical destination bottom tip is at y=800..830, x=1270..1296
            val vTipAt52 = measureInk(atP52, 1270, 800, 1296, 830, 0x62, 0xED, 0xCB, tolerance = 28)
            assertEquals(0, vTipAt52.pixelCount, "s14 line-7 arrowhead must NOT be pinned at y=800..830 at p=0.52")
            val vGrowingAt52 = measureInk(atP52, 1270, 332, 1296, 800, 0x62, 0xED, 0xCB, tolerance = 28)
            assertTrue(vGrowingAt52.pixelCount >= 40, "s14 line-7 must be actively drawing at p=0.52")
            val vTipRest = measureInk(atBeat2, 1270, 800, 1296, 830, 0x62, 0xED, 0xCB, tolerance = 28)
            assertTrue(vTipRest.pixelCount >= 80, "s14 line-7 arrowhead must arrive at y=800..830 at rest")

            // Beat 3 Exit (`startToEnd`, 100ms..500ms inside 1200ms Beat 3): at t=300ms (p=0.25f),
            // top shaft dots (below group-9 circle y=325..346) are erased while bottom dots (y=680..826) stay 100% stationary!
            val atExitMid = h.seekSegment(beat2State, 0.25f)
            val erasedTopDots = measureInk(atExitMid, 1274, 355, 1292, 520, 0x62, 0xED, 0xCB, tolerance = 28)
            assertEquals(0, erasedTopDots.pixelCount, "s14 line-7 Beat 3 Exit at p=0.25 must have erased top dots")
            var dotPositionMismatches = 0
            for (yy in 680..826) {
                for (xx in 1274..1292) {
                    val idx = (yy * atBeat2.width + xx) * 4
                    fun isMint(buf: ByteArray): Boolean {
                        val pr = buf[idx].toInt() and 0xFF
                        val pg = buf[idx + 1].toInt() and 0xFF
                        val pb = buf[idx + 2].toInt() and 0xFF
                        return abs(pr - 0x62) <= 35 && abs(pg - 0xED) <= 35 && abs(pb - 0xCB) <= 35
                    }
                    if (isMint(atBeat2.rgba) != isMint(atExitMid.rgba)) dotPositionMismatches++
                }
            }
            assertEquals(
                0,
                dotPositionMismatches,
                "s14 line-7 Beat 3 Exit must keep remaining mint dots at identical pixel coordinates (zero phase swimming)",
            )

            proofFrames += "Shaders s14 line-7/8 p=0.52" to cropFrame(atP52, 1240, 300, 1800, 850)
            proofFrames += "Shaders s14 line-7/8 Rest" to cropFrame(atBeat2, 1240, 300, 1800, 850)
        }

        // 3. NJPT s050 line-2 (#626972 endToStart arrow with start: filled head at top-left x=1065..1090, y=501..526)
        val njEntry = DeckCatalog.notJustPlainText2024
        val njDoc = loadClasspathDeckDocument(njEntry)
        val s049 = njDoc.slides.first { it.id == "s049" }
        val s050 = njDoc.slides.first { it.id == "s050" }
        DeckSliceProofHarness(njDoc.copy(slides = listOf(s049, s050)), njEntry.specimens, Storyboard.Index(1, 0)).use { h ->
            val beat0State = h.valueStateIndex(1, 0)
            val atP25 = h.seekSegment(beat0State, 0.25f)
            val atP100 = h.seekSegment(beat0State, 1.0f)
            val atBeat1 = h.jumpAndSettle(Storyboard.Index(1, 1))
            assertEquals(0, countDiffPixels(atBeat1, atP100, tolerance = 2), "NJPT s050 line-2 p=1.0 must match Rest")
            val startTipAt25 = measureInk(atP25, 1064, 500, 1090, 526, 0x62, 0x69, 0x72, tolerance = 24)
            assertEquals(0, startTipAt25.pixelCount, "NJPT s050 line-2 endToStart head must NOT appear at final destination at p=0.25")
            val startTipRest = measureInk(atBeat1, 1064, 500, 1090, 526, 0x62, 0x69, 0x72, tolerance = 24)
            assertTrue(startTipRest.pixelCount >= 40, "NJPT s050 line-2 start head must arrive at top-left at rest")

            proofFrames += "NJPT s050 line-2 p=0.25" to cropFrame(atP25, 1020, 460, 1220, 660)
            proofFrames += "NJPT s050 line-2 Rest" to cropFrame(atBeat1, 1020, 460, 1220, 660)
        }

        saveContactSheet(
            file = File(proofDir(), "ac1_linedraw_arrows_proof.png"),
            title = "AC-1 Proof: lineDraw Arrowheads Follow Growing Tip & Crisp Circular Dotted Caps",
            frames = proofFrames,
            columns = 4,
            cellWidth = 420,
        )
    }

    @Test
    fun ac2_shadersS16HalfFloatMasksAlignOnSingleLineWithoutGhostBleed() {
        val shEntry = DeckCatalog.shadersQuickie2025
        val shDoc = loadClasspathDeckDocument(shEntry)
        val s15 = shDoc.slides.first { it.id == "s15" }
        val s16 = shDoc.slides.first { it.id == "s16" }

        DeckSliceProofHarness(shDoc.copy(slides = listOf(s15, s16)), shEntry.specimens, Storyboard.Index(1, 1)).use { h ->
            val b1 = h.jumpAndSettle(Storyboard.Index(1, 1))
            val b3 = h.jumpAndSettle(Storyboard.Index(1, 3))
            val b4 = h.jumpAndSettle(Storyboard.Index(1, 4))
            val b5 = h.jumpAndSettle(Storyboard.Index(1, 5))

            // Beat 1: brown half4 and float2 present
            val b1BrownHalf4 = measureInk(b1, 232, 322, 366, 385, 0x59, 0x45, 0x48, tolerance = 24)
            assertTrue(b1BrownHalf4.pixelCount >= 200, "Beat 1 must render brown half4")

            // Beat 3: orange half & float on ONE line (no pixels below y=382), zero brown inside masked 'half' & 'float'
            val b3OrangeHalf = measureInk(b3, 230, 320, 338, 415, 0xD9, 0x6E, 0x30, tolerance = 32)
            assertTrue(b3OrangeHalf.pixelCount >= 150, "Beat 3 must render orange 'half'")
            assertTrue(b3OrangeHalf.maxY <= 382, "Beat 3 'half' must NOT wrap onto 2nd line (maxY=${b3OrangeHalf.maxY})")

            val b3BrownUnderHalf = measureInk(b3, 232, 322, 336, 382, 0x59, 0x45, 0x48, tolerance = 24)
            assertEquals(0, b3BrownUnderHalf.pixelCount, "Beat 3 opaque #EBEDEC 'half' mask must leave zero brown bleed")

            val b3BrownUnderFloat = measureInk(b3, 432, 322, 563, 382, 0x59, 0x45, 0x48, tolerance = 24)
            assertEquals(0, b3BrownUnderFloat.pixelCount, "Beat 3 opaque #EBEDEC 'float' mask must leave zero brown bleed")

            // Beat 4: orange '4' and '2' cover brown '4' and '2' cleanly on exact same baseline (half & float exited at beat 4)
            val b4BrownUnder4 = measureInk(b4, 338, 322, 365, 382, 0x59, 0x45, 0x48, tolerance = 24)
            val b4BrownUnder2 = measureInk(b4, 564, 322, 591, 382, 0x59, 0x45, 0x48, tolerance = 24)
            assertEquals(0, b4BrownUnder4.pixelCount, "Beat 4 mask '4' must leave ZERO brown ghost pixels under '4'")
            assertEquals(0, b4BrownUnder2.pixelCount, "Beat 4 mask '2' must leave ZERO brown ghost pixels under '2'")
            val b4Orange4 = measureInk(b4, 337, 320, 366, 382, 0xD9, 0x6E, 0x30, tolerance = 32)
            val b4Orange2 = measureInk(b4, 564, 320, 592, 382, 0xD9, 0x6E, 0x30, tolerance = 32)
            assertTrue(b4Orange4.pixelCount >= 40 && b4Orange2.pixelCount >= 40, "Beat 4 must display orange '4' and '2'")
            assertTrue(b4Orange4.minY in 326..345 && b4Orange4.maxY <= 380, "'4' must sit on half's baseline")
            assertTrue(b4Orange2.minY in 326..345 && b4Orange2.maxY <= 380, "'2' must sit on float's baseline")

            saveContactSheet(
                file = File(proofDir(), "ac2_s16_masks_proof.png"),
                title = "AC-2 Proof: Shaders s16 half/4 & float/2 Exact Baseline Alignment + Zero Wrap + Zero Ghost Bleed",
                frames = listOf(
                    "Beat 1: half4 / float2" to cropFrame(b1, 200, 295, 680, 415),
                    "Beat 3: half / float masks" to cropFrame(b3, 200, 295, 680, 415),
                    "Beat 4: 4 / 2 masks" to cropFrame(b4, 200, 295, 680, 415),
                    "Beat 5: Masks dissolved" to cropFrame(b5, 200, 295, 680, 415),
                ),
                columns = 2,
                cellWidth = 560,
            )
        }
    }

    @Test
    fun ac3_shadersS22ToS23CardInterpolatesMonotonicallyAndJewelS18PillsStayRounded() {
        val shEntry = DeckCatalog.shadersQuickie2025
        val shDoc = loadClasspathDeckDocument(shEntry)
        val s22 = shDoc.slides.first { it.id == "s22" }
        val s23 = shDoc.slides.first { it.id == "s23" }

        val frames = mutableListOf<Pair<String, RenderedFrame>>()
        DeckSliceProofHarness(shDoc.copy(slides = listOf(s22, s23)), shEntry.specimens).use { h ->
            val restABeat0 = h.jumpAndSettle(Storyboard.Index(0, 0))
            val restA = h.jumpAndSettle(Storyboard.Index(0, s22.beats.lastIndex.coerceAtLeast(0)))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            val crossIdx = h.crossFromStateIndex(0)

            // Verify rounded card height interpolates smoothly (zero Frame-1 snap) and code text is crisp at rests
            val restACode = measureInk(restABeat0, 245, 470, 950, 600, 0xCF, 0x8E, 0x6D, tolerance = 45)
            val restBCode = measureInk(restB, 245, 415, 950, 660, 0xCF, 0x8E, 0x6D, tolerance = 45)
            assertTrue(restACode.pixelCount >= 120 && restBCode.pixelCount >= 120, "Resting code cards must show crisp code text")

            val fracs = listOf(0.02f, 0.25f, 0.50f, 0.75f, 1.0f)
            val heights = fracs.map { f ->
                val frame = h.seekSegment(crossIdx, f)
                frames += "s22->s23 p=$f" to cropFrame(frame, 180, 340, 1200, 740)
                val card = measureInk(frame, 220, 380, 1135, 700, 0x41, 0x33, 0x35, tolerance = 12)
                card.heightPx
            }
            val restAHeight = measureInk(restA, 220, 380, 1135, 700, 0x41, 0x33, 0x35, tolerance = 12).heightPx
            val restBHeight = measureInk(restB, 220, 380, 1135, 700, 0x41, 0x33, 0x35, tolerance = 12).heightPx
            assertTrue(heights[0] <= restAHeight + 10, "p=0.02 card must NOT snap to Rest B height ($restBHeight), got ${heights[0]}")
            for (i in 0 until heights.lastIndex) {
                assertTrue(
                    heights[i] < heights[i + 1],
                    "Card height must grow monotonically across p=${fracs[i]} (${heights[i]}) -> p=${fracs[i + 1]} (${heights[i + 1]})",
                )
            }
            assertTrue(abs(heights.last() - restBHeight) <= 2, "p=1.0 card height (${heights.last()}) must match Rest B ($restBHeight)")
        }

        // Also check Shaders s26 -> s27 mid-Cross live code ink stays crisp inside the growing card
        val s26 = shDoc.slides.first { it.id == "s26" }
        val s27 = shDoc.slides.first { it.id == "s27" }
        DeckSliceProofHarness(shDoc.copy(slides = listOf(s26, s27)), shEntry.specimens).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            for (f in listOf(0.25f, 0.50f, 0.75f)) {
                val mid = h.seekSegment(crossIdx, f)
                val cardCode = measureInk(mid, 115, 500, 1100, 665, 0xCF, 0x8E, 0x6D, tolerance = 45)
                assertTrue(cardCode.pixelCount >= 120, "Shaders s26->s27 live code inside growing card must stay visible at p=$f (got ${cardCode.pixelCount})")
            }
        }

        // Also check Jewel s18 -> s19 pill morphing smoothly with zero olive cross-bleed
        val jwEntry = DeckCatalog.jewelTalk2024
        val jwDoc = loadClasspathDeckDocument(jwEntry)
        val s18 = jwDoc.slides.first { it.id == "s18" }
        val s19 = jwDoc.slides.first { it.id == "s19" }
        DeckSliceProofHarness(jwDoc.copy(slides = listOf(s18, s19)), jwEntry.specimens).use { h ->
            val restS19 = h.jumpAndSettle(Storyboard.Index(1, 0))
            h.jumpAndSettle(Storyboard.Index(0, 0))
            val crossIdx = h.crossFromStateIndex(0)
            val mid = h.seekSegment(crossIdx, 0.50f)
            // Pure pill interior regions (avoiding rounded-corner AA & "Ladybug.2" glyph AA) + mask must have ZERO olive (#868A56) bleed
            val oliveLeftCap = measureInk(mid, 1607, 970, 1614, 1002, 0x86, 0x8A, 0x56, tolerance = 30)
            val oliveTopStrip = measureInk(mid, 1690, 960, 1820, 963, 0x86, 0x8A, 0x56, tolerance = 30)
            val oliveMaskInterior = measureInk(mid, 1862, 945, 1920, 1025, 0x86, 0x8A, 0x56, tolerance = 30)
            assertEquals(0, oliveLeftCap.pixelCount, "Jewel s18->s19 p=0.50 pill left cap must have ZERO olive bleed")
            assertEquals(0, oliveTopStrip.pixelCount, "Jewel s18->s19 p=0.50 pill top strip must have ZERO olive bleed")
            assertEquals(0, oliveMaskInterior.pixelCount, "Jewel s18->s19 p=0.50 mask interior must have ZERO olive bleed")
            val pill = measureInk(mid, 1590, 950, 1865, 1025, 0xE3, 0xF2, 0x72, tolerance = 24)
            val restPill = measureInk(restS19, 1590, 950, 1865, 1025, 0xE3, 0xF2, 0x72, tolerance = 24)
            assertTrue(
                pill.maxX - pill.minX + 1 in 252..266 && pill.heightPx in 54..64 &&
                    abs(pill.pixelCount - restPill.pixelCount) <= 150,
                "Jewel s18->s19 pill must stay solid 260x60 rounded (got ${pill.maxX - pill.minX + 1}x${pill.heightPx}, px=${pill.pixelCount} vs rest=${restPill.pixelCount})",
            )
            frames += "Jewel s18->s19 p=0.50" to cropFrame(mid, 1500, 880, 1920, 1080)
        }

        saveContactSheet(
            file = File(proofDir(), "ac3_card_resize_proof.png"),
            title = "AC-3 Proof: Smooth Monotonic Code Card Interpolation (Shaders s22->s23) & Rounded Pills",
            frames = frames,
            columns = 3,
            cellWidth = 520,
        )
    }

    @Test
    fun ac4_shadersS28ToS29CrossSlidePreservesAnchorInterleavedZOrderAndHandover() {
        val shEntry = DeckCatalog.shadersQuickie2025
        val shDoc = loadClasspathDeckDocument(shEntry)
        val s28 = shDoc.slides.first { it.id == "s28" }
        val s29 = shDoc.slides.first { it.id == "s29" }

        DeckSliceProofHarness(shDoc.copy(slides = listOf(s28, s29)), shEntry.specimens).use { h ->
            val restA = h.jumpAndSettle(Storyboard.Index(0, s28.beats.lastIndex.coerceAtLeast(0)))
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            val crossIdx = h.crossFromStateIndex(0)
            // s29 has a beat-0 entrance (`compiledshader-setfloatunifo-2`), so Cross(s28, s29) ends at Entrance(s29, p=0)
            val startB = h.seekSegment(h.entranceFromStateIndex(1), 0f)

            val pStart = h.seekSegment(crossIdx, 1e-5f)
            val p25 = h.seekSegment(crossIdx, 0.25f)
            val p50 = h.seekSegment(crossIdx, 0.50f)
            val p75 = h.seekSegment(crossIdx, 0.75f)
            val pEnd = h.seekSegment(crossIdx, 1f - 1e-5f)

            assertEquals(0, countDiffPixels(restA, pStart, tolerance = 2), "Rest A must match Cross(s28,s29) at p=1e-5")
            assertEquals(0, countDiffPixels(startB, pEnd, tolerance = 2), "Entrance(s29, p=0) must match Cross(s28,s29) at p=1-1e-5")

            // Unmatched code tokens sitting AFTER path-1 (#413335) must remain visible ABOVE path-1 mid-Cross,
            // and "//" (s28/node) must NEVER fly diagonally toward "(" at (595, 460)!
            for ((label, frame) in listOf("p=0.25" to p25, "p=0.50" to p50, "p=0.75" to p75)) {
                val tealInk = measureInk(frame, 330, 440, 1550, 700, 0x0E, 0xA1, 0x9E, tolerance = 45)
                val greenInk = measureInk(frame, 330, 360, 1550, 620, 0x6C, 0xB3, 0x8A, tolerance = 45)
                assertTrue(
                    tealInk.pixelCount + greenInk.pixelCount >= 180,
                    "s28->s29 unmatched code lines over path-1 card must NOT be occluded at $label (teal=${tealInk.pixelCount}, green=${greenInk.pixelCount})",
                )
                val bogusDiagonalComment = measureInk(frame, 460, 412, 520, 445, 0x7A, 0x7E, 0x85, tolerance = 25)
                assertEquals(
                    0,
                    bogusDiagonalComment.pixelCount,
                    "s28 '//' must NOT fly diagonally through (460..520, 412..445) at $label",
                )
            }

            saveContactSheet(
                file = File(proofDir(), "ac4_s28_s29_zorder_proof.png"),
                title = "AC-4 Proof: Shaders s28->s29 Anchor-Interleaved Z-Order Over #413335 Code Card",
                frames = listOf(
                    "Rest s28" to cropFrame(restA, 260, 270, 1660, 810),
                    "p=0.25" to cropFrame(p25, 260, 270, 1660, 810),
                    "p=0.50" to cropFrame(p50, 260, 270, 1660, 810),
                    "p=0.75" to cropFrame(p75, 260, 270, 1660, 810),
                    "Rest s29" to cropFrame(restB, 260, 270, 1660, 810),
                ),
                columns = 3,
                cellWidth = 520,
            )
        }
    }

    @Test
    fun shadersS26AndS38OversizedSpotlightHandoverStaysAligned() {
        val shEntry = DeckCatalog.shadersQuickie2025
        val shDoc = loadClasspathDeckDocument(shEntry)
        for ((fromId, toId) in listOf("s26" to "s27", "s38" to "s39")) {
            val slideA = shDoc.slides.first { it.id == fromId }
            val slideB = shDoc.slides.first { it.id == toId }
            DeckSliceProofHarness(shDoc.copy(slides = listOf(slideA, slideB)), shEntry.specimens).use { h ->
                val restA = h.jumpAndSettle(Storyboard.Index(0, slideA.beats.lastIndex.coerceAtLeast(0)))
                val crossIdx = h.crossFromStateIndex(0)
                val pStart = h.seekSegment(crossIdx, 1e-5f)
                assertEquals(
                    0,
                    countDiffPixels(restA, pStart, tolerance = 2),
                    "Real Shaders $fromId->$toId with 3375/3376x3061 oversized spotlight must NOT shift between Rest(A) and Cross(1e-5f)",
                )
            }
        }
    }

    @Test
    fun jewelS39FirstRestoresBoldPurpleStyling() {
        val jwEntry = DeckCatalog.jewelTalk2024
        val jwDoc = loadClasspathDeckDocument(jwEntry)
        val s38 = jwDoc.slides.first { it.id == "s38" }
        val s39 = jwDoc.slides.first { it.id == "s39" }

        val firstS39 = s39.nodes.first { it.id == "first" }
        assertEquals(600, firstS39.text?.weight, "Jewel s39 'first' must restore weight=600")
        assertEquals("#A74EBF", firstS39.text?.color, "Jewel s39 'first' must restore color=#A74EBF")

        DeckSliceProofHarness(jwDoc.copy(slides = listOf(s38, s39)), jwEntry.specimens).use { h ->
            val restS38 = h.jumpAndSettle(Storyboard.Index(0, s38.beats.lastIndex.coerceAtLeast(0)))
            val restS39 = h.jumpAndSettle(Storyboard.Index(1, 0))
            // "First" sits at x=1110..1398, y=465..649. On s38 it is #C6B2F0; on s39 it turns #A74EBF.
            val purpleOnS38 = measureInk(restS38, 1110, 465, 1398, 649, 0xA7, 0x4E, 0xBF, tolerance = 24)
            val purpleOnS39 = measureInk(restS39, 1110, 465, 1398, 649, 0xA7, 0x4E, 0xBF, tolerance = 24)
            assertEquals(0, purpleOnS38.pixelCount, "Jewel s38 'First' must not yet be #A74EBF purple")
            assertTrue(
                purpleOnS39.pixelCount >= 500,
                "Jewel s39 'First' must render #A74EBF bold purple ink (got ${purpleOnS39.pixelCount} px)",
            )
        }
    }
}
