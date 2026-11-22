package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.text.magic.MorphBy as MagicMorphBy
import dev.sebastiano.ciacola.core.fonts.CharacterSafetyEngine
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.effects.v2.BuildEffects
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.text.v2.MagicTextV2Drawing.drawBuildFrame
import dev.sebastiano.ciacola.text.v2.MagicTextV2Drawing.drawMorphFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.graphicapi.MetalOffscreenContext

class MagicTextV2SafetyAndSubpixelParityTest {

    @Test
    fun singleAuthoritativeParagraphPredicateAndZeroBitmapSizeMorphClasses() {
        val resolverMethods = DeckFontResolver::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("certifyParagraphCharacterSafety" in resolverMethods)
        assertFalse(
            resolverMethods.any { it.startsWith("isWordCharacterSafe") },
            "Legacy isWordCharacterSafe overloads must not exist in production DeckFontResolver: $resolverMethods",
        )

        val engineMethods = CharacterSafetyEngine::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("certifyParagraph" in engineMethods)
        assertFalse(
            engineMethods.any { it.startsWith("certifyExplicitRuns") || it.startsWith("defaultLtrPredicate") },
            "certifyExplicitRuns and defaultLtrPredicate must not exist in production CharacterSafetyEngine: $engineMethods",
        )

        // Default parameter mask ($default synthetic method) must require explicit isLayoutLtrAtOffset (bit 3 = 8 must NOT be defaulted).
        assertFailsWith<ClassNotFoundException>("WordFaceRun must be deleted from production sources") {
            Class.forName("dev.sebastiano.ciacola.core.fonts.WordFaceRun")
        }
        assertFailsWith<ClassNotFoundException>("PreRenderedGlyphImage must be deleted from production sources") {
            Class.forName("dev.sebastiano.ciacola.text.v2.PreRenderedGlyphImage")
        }
    }

    @Test
    fun pieceInkBoundsRefuses1PxBorderAlphaAndBatchedDissolvePropagatesNullWithoutClipping() {
        val w = 40
        val h = 28
        val pad = 8

        fun makeRgbaWithSingleAlphaPixel(px: Int, py: Int, alpha: Int = 1): ByteArray {
            val rgba = ByteArray(w * h * 4)
            val idx = (py * w + px) * 4
            rgba[idx + 3] = alpha.toByte()
            return rgba
        }

        // Any non-zero alpha (even alpha = 1) touching x = 0, y = 0, x = w - 1, or y = h - 1 MUST return null!
        assertNull(PieceInkBounds.scanInkBounds(makeRgbaWithSingleAlphaPixel(0, 14, alpha = 1), w, h, pad), "Left border ink must refuse bounds")
        assertNull(PieceInkBounds.scanInkBounds(makeRgbaWithSingleAlphaPixel(w - 1, 14, alpha = 1), w, h, pad), "Right border ink must refuse bounds")
        assertNull(PieceInkBounds.scanInkBounds(makeRgbaWithSingleAlphaPixel(20, 0, alpha = 1), w, h, pad), "Top border ink must refuse bounds")
        assertNull(PieceInkBounds.scanInkBounds(makeRgbaWithSingleAlphaPixel(20, h - 1, alpha = 1), w, h, pad), "Bottom border ink must refuse bounds")

        // Strictly interior alpha = 1 succeeds and includes 4 px safety halo.
        val interior = assertNotNull(PieceInkBounds.scanInkBounds(makeRgbaWithSingleAlphaPixel(12, 10, alpha = 1), w, h, pad))
        assertEquals((12 - pad) - 4f, interior.left)
        assertEquals((10 - pad) - 4f, interior.top)
        assertEquals((13 - pad) + 4f, interior.right)
        assertEquals((11 - pad) + 4f, interior.bottom)

        // Batched dissolve fade: if ANY piece in a coalesced equal-alpha run has verifiedInkBounds == null,
        // the batch MUST pass saveLayer(null, paint) and NEVER clip that piece to its neighbors' bounds!
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val density = Density(2f)
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val renderer = MagicTextV2Renderer()
        val config = MagicTextV2Config(fontResolver = resolver)
        val style = resolver.resolveTextStyle("Inter", TextStyle(color = Color.White, fontSize = 32.sp, lineHeight = 42.sp))
        val styled = StyledTextV2(AnnotatedString("Alpha Beta Gamma"), style, maxWidthPx = 900)
        val schedule = PieceScheduleV2(totalDurationMs = 400, slots = emptyMap())
        val eff = EffectCallV2(BuildEffects.DISSOLVE, EffectBuildKind.Enter, EffectArgs.Empty, CurveV2.Linear)
        val buildContent = TextContentV2.Build(styled, PartitionGranularity.Word, eff, schedule, elapsedMs = 200f)
        val basePlan = renderer.planBuild(buildContent, measurer, density, config = config)
        assertEquals(3, basePlan.pieceTimings.size)
        assertTrue(basePlan.allPiecesPairwiseDisjoint)

        // Force the rightmost piece ("Gamma") to have verifiedInkBounds = null inside a batchMarkedDisjoint plan.
        val modifiedTimings = basePlan.pieceTimings.mapIndexed { idx, bt ->
            if (idx == 2) bt.copy(piece = bt.piece.copy(verifiedInkBounds = null)) else bt
        }
        val nullInBatchPlan = basePlan.copy(pieceTimings = modifiedTimings, allPiecesPairwiseDisjoint = true)

        val canvasW = 700
        val canvasH = 140
        val refRgba = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
            drawBuildFrame(basePlan, buildContent, config)
        }
        val nullPropRgba = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
            drawBuildFrame(nullInBatchPlan, buildContent, config)
        }
        val diff = MagicTextV2TestSupport.compareRgba(refRgba, nullPropRgba, tolerance = 0)
        assertEquals(
            0,
            diff.maxDelta,
            "Batched dissolve with a verifiedInkBounds=null piece must use unbounded saveLayer(null) and preserve 100% of ink",
        )
    }

    @OptIn(ExperimentalSkikoApi::class)
    @Test
    fun pureVectorSizeMorphMetalGpuSubpixelParity() {
        MagicTextV2TestSupport.ensureComposeRegistered()
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val density = Density(2f)
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val renderer = MagicTextV2Renderer()
        val config = MagicTextV2Config(fontResolver = resolver)

        val mixedText = buildAnnotatedString {
            append("Design ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("crisp ") }
            append("wrapped ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic ") }
            append("typography across lines cleanly")
        }
        val smallStyle = resolver.resolveTextStyle("HankenGrotesk", TextStyle(color = Color.White, fontSize = 22.sp, lineHeight = 38.sp))
        val largeStyle = resolver.resolveTextStyle("HankenGrotesk", TextStyle(color = Color.White, fontSize = 56.sp, lineHeight = 96.sp))
        val fromWrapped = StyledTextV2(mixedText, smallStyle, maxWidthPx = 420)
        val toWrapped = StyledTextV2(mixedText, largeStyle, maxWidthPx = 1070)
        val schedule = PieceScheduleV2(totalDurationMs = 800, slots = emptyMap())
        val morph = TextContentV2.Morph(fromWrapped, toWrapped, MagicMorphBy.Word, schedule = schedule, elapsedMs = 0f, moveCurve = CurveV2.Linear)
        val plan = renderer.planMorph(morph, measurer, density, config = config)

        // Confirm every Move group uses pure Skia Path vector outlines (non-null fromVectorRuns).
        assertTrue(plan.groups.all { it.sizeMorphAsset?.fromVectorRuns?.isNotEmpty() == true })

        val w = 1160
        val h = 900
        val wrapPad = 24f
        val metalCtx = MetalOffscreenContext()
        val surface = Surface.makeRenderTarget(
            context = metalCtx.directContext,
            budgeted = false,
            imageInfo = ImageInfo.makeN32Premul(w, h),
        ) ?: error("Failed to create Metal render target")
        val bmp = Bitmap()
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        bmp.allocPixels(info)

        fun renderMetal(drawBlock: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ByteArray {
            val drawScope = CanvasDrawScope()
            surface.canvas.clear(0)
            drawScope.draw(density, LayoutDirection.Ltr, surface.canvas.asComposeCanvas(), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat())) {
                drawContext.transform.translate(wrapPad, wrapPad)
                drawBlock()
            }
            metalCtx.directContext.flushAndSubmit(surface, syncCpu = true)
            assertTrue(surface.readPixels(bmp, 0, 0))
            return assertNotNull(bmp.readPixels(info, w * 4, 0, 0))
        }

        try {
            val fromWL = plan.fromPartition.wholeLayout
            val toWL = plan.toPartition.wholeLayout
            val restSmall = renderMetal { drawText(fromWL) }
            val morphStart = renderMetal { drawMorphFrame(plan, morph.copy(elapsedMs = 0f), config) }

            for (line in 0 until fromWL.lineCount) {
                val yTop = (wrapPad + fromWL.getLineTop(line)).toInt().coerceAtLeast(0)
                val yBot = (wrapPad + fromWL.getLineBottom(line)).toInt().coerceAtMost(h)
                val restEdges = assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(restSmall, w, h, yTop, yBot))
                val startEdges = assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(morphStart, w, h, yTop, yBot))
                val err = max(
                    max(abs(restEdges.left - startEdges.left), abs(restEdges.right - startEdges.right)),
                    max(abs(restEdges.top - startEdges.top), abs(restEdges.bottom - startEdges.bottom)),
                )
                assertTrue(err <= 0.5, "Metal GPU wrapped line $line direct handover exceeded 0.5 px: $err")
            }

            val lineSamples = Array(fromWL.lineCount) { ArrayList<MagicTextV2TestSupport.SubpixelInkEdges>(60) }
            for (k in 0 until 60) {
                val p = k / 59f
                val rgba = renderMetal { drawMorphFrame(plan, morph.copy(elapsedMs = p * 800f), config) }
                for (line in 0 until fromWL.lineCount) {
                    val interpTop = fromWL.getLineTop(line) + (toWL.getLineTop(line) - fromWL.getLineTop(line)) * p
                    val interpBot = fromWL.getLineBottom(line) + (toWL.getLineBottom(line) - fromWL.getLineBottom(line)) * p
                    val yTop = (wrapPad + interpTop).toInt().coerceIn(0, h - 1)
                    val yBot = (wrapPad + interpBot).toInt().coerceIn(yTop + 1, h)
                    lineSamples[line].add(assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(rgba, w, h, yTop, yBot)))
                }
            }

            for (line in 0 until fromWL.lineCount) {
                val ls = lineSamples[line]
                val first = ls.first()
                val last = ls.last()
                for (k in ls.indices) {
                    val p = k / 59.0
                    val cur = ls[k]
                    val dev = maxOf(
                        abs(cur.left - (first.left + (last.left - first.left) * p)),
                        abs(cur.right - (first.right + (last.right - first.right) * p)),
                        abs(cur.top - (first.top + (last.top - first.top) * p)),
                        abs(cur.bottom - (first.bottom + (last.bottom - first.bottom) * p)),
                    )
                    assertTrue(
                        dev <= 0.5,
                        "Metal GPU wrapped line $line sample $k trajectory dev exceeded 0.5 px: $dev (cur=$cur first=$first last=$last)",
                    )
                }
            }
        } finally {
            bmp.close()
            surface.close()
            metalCtx.close()
        }
    }
}
