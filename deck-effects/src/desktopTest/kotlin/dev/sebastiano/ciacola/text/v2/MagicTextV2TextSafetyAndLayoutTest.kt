package dev.sebastiano.ciacola.text.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.export.StoryboardPdfExporter
import dev.bnorm.storyboard.text.magic.MorphBy
import dev.sebastiano.ciacola.core.fonts.CharacterSafetyEngine
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.EffectiveFontFeatureConfig
import dev.sebastiano.ciacola.core.fonts.FontResourceLoader
import dev.sebastiano.ciacola.core.fonts.GsubParserLimits
import dev.sebastiano.ciacola.core.fonts.SystemFontLookup
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.text.v2.MagicTextV2Renderer.Companion.drawMorphFrame
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

class MagicTextV2TextSafetyAndLayoutTest {

    // -------------------------------------------------------------------------
    // Rest = BasicText pixel identity (0/255), near-endpoint tolerance
    // at BOTH ends, consistent character-delivery feature plumbing across Rest/Morph,
    // and additive BlendMode.PLUS color cross-fade 0% luminance dip.
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun restIsPixelIdenticalToBasicTextAndColorCrossFadeHasZeroLuminanceDip() {
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val baseStyle = resolver.resolveTextStyle(
            familyName = "Inter",
            style = TextStyle(
                color = Color(0xFFF5F7FA),
                fontSize = 28.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Normal,
            ),
        )
        val sampleText = buildAnnotatedString {
            append("A ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF7DD3FC))) {
                append("shader")
            }
            append(" is a pure function mapping coordinates to color.")
        }

        val w = 720
        val h = 200
        val density = Density(2f)

        val basicScene = ImageComposeScene(width = w, height = h, density = density) {
            Box(Modifier.fillMaxSize()) {
                BasicText(text = sampleText, style = baseStyle)
            }
        }
        val magicRenderer = MagicTextV2Renderer()
        val restContent = TextContentV2.Rest(StyledTextV2(sampleText, baseStyle))
        val magicScene = ImageComposeScene(width = w, height = h, density = density) {
            Box(Modifier.fillMaxSize()) {
                magicRenderer.TextNode(
                    content = { restContent },
                    config = MagicTextV2Config(fontResolver = resolver),
                )
            }
        }
        try {
            basicScene.render(0L)
            val basicPx = MagicTextV2TestSupport.readImageRgba(basicScene.render(16_000_000L))
            magicScene.render(0L)
            val magicPx = MagicTextV2TestSupport.readImageRgba(magicScene.render(16_000_000L))

            val diff = MagicTextV2TestSupport.compareRgba(basicPx, magicPx, tolerance = 0)
            assertEquals(0, diff.maxDelta, "Rest must be 0/255 pixel-identical to BasicText")
            assertEquals(0, diff.differingPixelsOverTol)
            assertTrue(MagicTextV2TestSupport.countInkPixels(magicPx) > 1000)
        } finally {
            basicScene.close()
            magicScene.close()
        }

        // Frames just inside BOTH morph endpoints agree within <= 2/255 with source & target Rest.
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val fromStyled = StyledTextV2(AnnotatedString("Hello Compose World"), baseStyle, maxWidthPx = 680)
        val toStyled = StyledTextV2(AnnotatedString("Hello MagicText World"), baseStyle, maxWidthPx = 680)
        val schedule = PieceScheduleV2(totalDurationMs = 600, slots = emptyMap())
        val config = MagicTextV2Config(fontResolver = resolver)
        val morphNearStart = TextContentV2.Morph(
            from = fromStyled,
            to = toStyled,
            by = MorphBy.Word,
            schedule = schedule,
            elapsedMs = 0.01f,
            moveCurve = CurveV2.Linear,
        )
        val morphNearEnd = morphNearStart.copy(elapsedMs = 599.99f)
        val plan = magicRenderer.planMorph(morphNearStart, measurer, density, config = config)

        val restFromImg = MagicTextV2TestSupport.renderCanvasToRgba(700, 140, density) {
            drawText(plan.fromPartition.wholeLayout)
        }
        val morphStartImg = MagicTextV2TestSupport.renderCanvasToRgba(700, 140, density) {
            drawMorphFrame(plan, morphNearStart, config)
        }
        val startDiff = MagicTextV2TestSupport.compareRgba(restFromImg, morphStartImg, tolerance = 2)
        assertTrue(startDiff.maxDelta <= 2, "near-start diff exceeded 2/255: maxDelta=${startDiff.maxDelta}")

        val restToImg = MagicTextV2TestSupport.renderCanvasToRgba(700, 140, density) {
            drawText(plan.toPartition.wholeLayout)
        }
        val morphEndImg = MagicTextV2TestSupport.renderCanvasToRgba(700, 140, density) {
            drawMorphFrame(plan, morphNearEnd, config)
        }
        val endDiff = MagicTextV2TestSupport.compareRgba(restToImg, morphEndImg, tolerance = 2)
        if (endDiff.maxDelta > 2) {
            var wx = -1; var wy = -1; var best = -1; var rPx = ""; var mPx = ""
            for (py in 0 until 140) {
                for (px in 0 until 700) {
                    val o = (py * 700 + px) * 4
                    val d = maxOf(
                        kotlin.math.abs((restToImg[o].toInt() and 0xFF) - (morphEndImg[o].toInt() and 0xFF)),
                        kotlin.math.abs((restToImg[o + 1].toInt() and 0xFF) - (morphEndImg[o + 1].toInt() and 0xFF)),
                        kotlin.math.abs((restToImg[o + 2].toInt() and 0xFF) - (morphEndImg[o + 2].toInt() and 0xFF)),
                        kotlin.math.abs((restToImg[o + 3].toInt() and 0xFF) - (morphEndImg[o + 3].toInt() and 0xFF)),
                    )
                    if (d > best) {
                        best = d; wx = px; wy = py
                        rPx = "(${restToImg[o].toInt() and 0xFF},${restToImg[o + 3].toInt() and 0xFF})"
                        mPx = "(${morphEndImg[o].toInt() and 0xFF},${morphEndImg[o + 3].toInt() and 0xFF})"
                    }
                }
            }
            assertTrue(
                false,
                "near-end diff exceeded 2/255: maxDelta=${endDiff.maxDelta} at ($wx,$wy) rest=$rPx morph=$mPx " +
                    "fromLines=${plan.fromPartition.wholeLayout.lineCount} toLines=${plan.toPartition.wholeLayout.lineCount} " +
                    "groups=${plan.groups.map { "${it.kind}[${it.startMs}..${it.endMs}]src=${it.sourcePieces.map { p -> "${p.line}@${p.originX},${p.originY}(pb=${p.paintedBaselineY})" }}dst=${it.targetPieces.map { p -> "${p.line}@${p.originX},${p.originY}(pb=${p.paintedBaselineY})" }}" }}",
            )
        }

        // Character-delivery node keeps ligatures forced off even on a Word-track morph (zero ligature pop!).
        val hankenStyle = resolver.resolveTextStyle("HankenGrotesk", TextStyle(color = Color.White, fontSize = 32.sp))
        val ligFrom = StyledTextV2(AnnotatedString("fi fl office"), hankenStyle, maxWidthPx = 600)
        val ligTo = StyledTextV2(AnnotatedString("office fi fl"), hankenStyle, maxWidthPx = 600)
        val wordTrackOnCharNode = TextContentV2.Morph(
            from = ligFrom,
            to = ligTo,
            by = MorphBy.Word,
            schedule = schedule,
            elapsedMs = 0.01f,
            moveCurve = CurveV2.Linear,
            characterDeliveryNode = true,
        )
        val charNodePlan = magicRenderer.planMorph(wordTrackOnCharNode, measurer, density, config = config)
        val restCharNodeLayout = TwoConditionPartitioner.measureWholeParagraph(
            ligFrom.withEffectiveFeatureConfig(EffectiveFontFeatureConfig.CharacterForcedOff),
            measurer,
        )
        val restCharPx = MagicTextV2TestSupport.renderCanvasToRgba(620, 120, density) { drawText(restCharNodeLayout) }
        val wordTrackStartPx = MagicTextV2TestSupport.renderCanvasToRgba(620, 120, density) {
            drawMorphFrame(charNodePlan, wordTrackOnCharNode, config)
        }
        assertTrue(
            MagicTextV2TestSupport.compareRgba(restCharPx, wordTrackStartPx, tolerance = 2).maxDelta <= 2,
            "Word track on character-delivery node must keep forced-off ligature config identically to Rest",
        )

        // Additive BlendMode.PLUS color cross-fade on kept piece: 0% luminance dip at t = 0.5!
        val redStyle = baseStyle.copy(color = Color(0xFFFF4040))
        val blueStyle = baseStyle.copy(color = Color(0xFF4080FF))
        val colorFrom = StyledTextV2(AnnotatedString("Constant"), redStyle, maxWidthPx = 600)
        val colorTo = StyledTextV2(AnnotatedString("Constant"), blueStyle, maxWidthPx = 600)
        val colorMorph = TextContentV2.Morph(
            from = colorFrom,
            to = colorTo,
            by = MorphBy.Word,
            schedule = PieceScheduleV2(totalDurationMs = 500, slots = emptyMap()),
            elapsedMs = 250f,
            moveCurve = CurveV2.Linear,
        )
        val colorPlan = magicRenderer.planMorph(colorMorph, measurer, density, config = config)
        assertEquals(DrawableGroupKind.Move, colorPlan.groups.single().kind)
        assertTrue(colorPlan.groups.single().styleChanged)

        val restAlphaPixels = MagicTextV2TestSupport.renderCanvasToRgba(500, 140, density) {
            drawText(colorPlan.fromPartition.wholeLayout)
        }
        val midAlphaPixels = MagicTextV2TestSupport.renderCanvasToRgba(500, 140, density) {
            drawMorphFrame(colorPlan, colorMorph, config)
        }
        val restToPixels = MagicTextV2TestSupport.renderCanvasToRgba(500, 140, density) {
            drawText(colorPlan.toPartition.wholeLayout)
        }
        var maxAlphaDip = 0
        var i = 3
        while (i < restAlphaPixels.size) {
            val ra = restAlphaPixels[i].toInt() and 0xFF
            val ta = restToPixels[i].toInt() and 0xFF
            val ma = midAlphaPixels[i].toInt() and 0xFF
            val dip = abs((ra + ta) / 2 - ma)
            if (dip > maxAlphaDip) maxAlphaDip = dip
            i += 4
        }
        assertTrue(
            maxAlphaDip <= 2,
            "additive BlendMode.PLUS cross-fade must have 0% luminance/alpha dip (maxAlphaDip=$maxAlphaDip)",
        )
    }

    // -------------------------------------------------------------------------
    // 24 Difficult Fixtures (both directions + independent pixel verification +
    // real mid-morph frames p = 0.35, 0.5, 0.7), Asymmetric Coarsening (ab -> ba & ccmp),
    // Character-Safety Predicate Matrix, All 9 Bundled Latin Families,
    // Unclipped Fades, Fail-Closed Readback, Affine Transforms & Forced C2 Failure.
    // -------------------------------------------------------------------------

    @Test
    fun difficultFixturesAdmissionEquivalenceAndBidirectionalMorphs() {
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val density = Density(2f)
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val renderer = MagicTextV2Renderer()
        val config = MagicTextV2Config(fontResolver = resolver)

        val inter = resolver.resolveTextStyle("Inter", TextStyle(color = Color.White, fontSize = 28.sp, lineHeight = 38.sp))
        val mono = resolver.resolveTextStyle("JetBrainsMono", TextStyle(color = Color.White, fontSize = 26.sp, lineHeight = 36.sp))
        val hanken = resolver.resolveTextStyle("HankenGrotesk", TextStyle(color = Color.White, fontSize = 28.sp, lineHeight = 38.sp))
        val hankenItalic = resolver.resolveTextStyle("HankenGrotesk", TextStyle(color = Color.White, fontSize = 28.sp, lineHeight = 38.sp, fontStyle = FontStyle.Italic))

        data class Fixture(
            val name: String,
            val from: StyledTextV2,
            val to: StyledTextV2,
            val by: MorphBy,
            val expectCharacterSplits: Boolean? = null,
            val expectAdmittedBoth: Boolean? = null,
        )

        val fixtures = listOf(
            // 1-4: All 4 Seam Doc Fixture Families
            Fixture(
                name = "seam-code-refactor",
                from = StyledTextV2(AnnotatedString("val sum = items.map { it.price }.sum()"), mono, maxWidthPx = 880),
                to = StyledTextV2(AnnotatedString("val total = items.sumOf { it.price }"), mono, maxWidthPx = 880),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            Fixture(
                name = "seam-bullet-reorder",
                from = StyledTextV2(AnnotatedString("• Measure once\n• Verify pixels\n• Animate fast"), inter, maxWidthPx = 700),
                to = StyledTextV2(AnnotatedString("• Verify pixels\n• Animate fast\n• Measure once"), inter, maxWidthPx = 700),
                by = MorphBy.Line,
                expectAdmittedBoth = true,
            ),
            Fixture(
                name = "seam-mixed-style-span",
                from = StyledTextV2(
                    buildAnnotatedString {
                        append("Keep ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))) { append("pixel-exact") }
                        append(" typography")
                    },
                    inter,
                    maxWidthPx = 800,
                ),
                to = StyledTextV2(
                    buildAnnotatedString {
                        append("Keep ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFF59E0B))) { append("pixel-exact") }
                        append(" slides")
                    },
                    inter,
                    maxWidthPx = 800,
                ),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            Fixture(
                name = "seam-prose-stopword-rewrite",
                from = StyledTextV2(AnnotatedString("The quick fox jumps over the lazy dog"), inter, maxWidthPx = 760),
                to = StyledTextV2(AnnotatedString("A swift fox leaps past the sleeping hound"), inter, maxWidthPx = 760),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 5: Proportional ligatures (HankenGrotesk fi/fl/ffi/ffl in Character mode)
            Fixture(
                name = "proportional-ligature-char",
                from = StyledTextV2(AnnotatedString("fi fl ffi ffl waffle office"), hanken, maxWidthPx = 800),
                to = StyledTextV2(AnnotatedString("office waffle ffl ffi fl fi"), hanken, maxWidthPx = 800),
                by = MorphBy.Character,
                expectCharacterSplits = true,
                expectAdmittedBoth = true,
            ),
            // 6: Coding ligatures (JetBrainsMono -> => != === <= >= ::)
            Fixture(
                name = "coding-ligature-char",
                from = StyledTextV2(AnnotatedString("a -> b => c != d === e <= f >= g :: h"), mono, maxWidthPx = 900),
                to = StyledTextV2(AnnotatedString("x => y -> z === w != v >= u <= t :: s"), mono, maxWidthPx = 900),
                by = MorphBy.Character,
                expectCharacterSplits = true,
                expectAdmittedBoth = true,
            ),
            // 7: Low-alpha ligature (opacity-normalized Condition 2!)
            Fixture(
                name = "low-alpha-ligature",
                from = StyledTextV2(AnnotatedString("fi fl -> =>"), hanken.copy(color = Color(0x18FFFFFF)), maxWidthPx = 700),
                to = StyledTextV2(AnnotatedString("=> -> fl fi"), hanken.copy(color = Color(0x18FFFFFF)), maxWidthPx = 700),
                by = MorphBy.Character,
                expectCharacterSplits = true,
                expectAdmittedBoth = true,
            ),
            // 8: Arabic (must NOT split below word!)
            Fixture(
                name = "arabic-rtl",
                from = StyledTextV2(AnnotatedString("مرحبا بالعالم الجميل"), inter, maxWidthPx = 700),
                to = StyledTextV2(AnnotatedString("اهلا بالعالم الجديد"), inter, maxWidthPx = 700),
                by = MorphBy.Character,
                expectCharacterSplits = false,
            ),
            // 9: Hebrew (must NOT split below word!)
            Fixture(
                name = "hebrew-rtl",
                from = StyledTextV2(AnnotatedString("שלום עולם יפה"), inter, maxWidthPx = 700),
                to = StyledTextV2(AnnotatedString("שלום עולם חדש"), inter, maxWidthPx = 700),
                by = MorphBy.Character,
                expectCharacterSplits = false,
            ),
            // 10: Devanagari (must NOT split below word!)
            Fixture(
                name = "devanagari-indic",
                from = StyledTextV2(AnnotatedString("नमस्ते दुनिया सुंदर"), inter, maxWidthPx = 700),
                to = StyledTextV2(AnnotatedString("नमस्ते भारत सुंदर"), inter, maxWidthPx = 700),
                by = MorphBy.Character,
                expectCharacterSplits = false,
            ),
            // 11: Thai (must NOT split below word!)
            Fixture(
                name = "thai-script",
                from = StyledTextV2(AnnotatedString("สวัสดีชาวโลก"), inter, maxWidthPx = 700),
                to = StyledTextV2(AnnotatedString("สวัสดีทุกคน"), inter, maxWidthPx = 700),
                by = MorphBy.Character,
                expectCharacterSplits = false,
            ),
            // 12: Emoji + ZWJ sequence routed via NotoEmoji monochrome fallback — refuses char split!
            Fixture(
                name = "emoji-zwj-monochrome",
                from = StyledTextV2(AnnotatedString("⏩\uFE0F Velocity 🔨 craft 👨\u200D🍳 chef"), hanken, maxWidthPx = 900),
                to = StyledTextV2(AnnotatedString("💖 Devs 😎 ship 👨\u200D🍳 chef"), hanken, maxWidthPx = 900),
                by = MorphBy.Character,
                expectCharacterSplits = false,
                expectAdmittedBoth = true,
            ),
            // 13: Combining marks (non-NFC + M* refuses char split!)
            Fixture(
                name = "combining-marks",
                from = StyledTextV2(AnnotatedString("cafe\u0301 re\u0301sume\u0301 na\u0308ive"), inter, maxWidthPx = 760),
                to = StyledTextV2(AnnotatedString("na\u0308ive cafe\u0301 re\u0301sume\u0301"), inter, maxWidthPx = 760),
                by = MorphBy.Character,
                expectCharacterSplits = false,
            ),
            // 14: Italic overhang
            Fixture(
                name = "italic-overhang",
                from = StyledTextV2(AnnotatedString("fffff WAVY italic overhang"), hankenItalic, maxWidthPx = 760),
                to = StyledTextV2(AnnotatedString("WAVY fffff overhang italic"), hankenItalic, maxWidthPx = 760),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 15: Negative tracking
            Fixture(
                name = "negative-tracking",
                from = StyledTextV2(AnnotatedString("AVATAR WAVE TYPOGRAPHY"), inter.copy(letterSpacing = (-1.5).sp), maxWidthPx = 800),
                to = StyledTextV2(AnnotatedString("TYPOGRAPHY WAVE AVATAR"), inter.copy(letterSpacing = (-1.5).sp), maxWidthPx = 800),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 16: Baseline shift (within 2*lineHeight)
            Fixture(
                name = "baseline-shift",
                from = StyledTextV2(
                    buildAnnotatedString {
                        append("E = mc")
                        withStyle(SpanStyle(baselineShift = BaselineShift(0.35f), fontSize = 18.sp)) { append("2") }
                        append(" energy")
                    },
                    inter,
                    maxWidthPx = 700,
                ),
                to = StyledTextV2(
                    buildAnnotatedString {
                        append("H")
                        withStyle(SpanStyle(baselineShift = BaselineShift(-0.25f), fontSize = 18.sp)) { append("2") }
                        append("O water")
                    },
                    inter,
                    maxWidthPx = 700,
                ),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 17: Tight line height
            Fixture(
                name = "tight-line-height",
                from = StyledTextV2(AnnotatedString("Tight stacked lines\noverlapping ascenders\nand descenders gypq"), inter.copy(lineHeight = 22.sp), maxWidthPx = 600),
                to = StyledTextV2(AnnotatedString("Tight stacked lines\nreordered descenders\nand ascenders gypq"), inter.copy(lineHeight = 22.sp), maxWidthPx = 600),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 18: Continuous underline across spaces (space-decoration merge!)
            Fixture(
                name = "continuous-underline-across-space",
                from = StyledTextV2(
                    buildAnnotatedString {
                        append("Plain ")
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("continuous underline run") }
                        append(" tail")
                    },
                    inter,
                    maxWidthPx = 900,
                ),
                to = StyledTextV2(
                    buildAnnotatedString {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("continuous underline run") }
                        append(" Plain tail")
                    },
                    inter,
                    maxWidthPx = 900,
                ),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 19: Low-alpha underline & low-alpha run background
            Fixture(
                name = "low-alpha-underline-and-run-bg",
                from = StyledTextV2(
                    buildAnnotatedString {
                        append("Muted ")
                        withStyle(SpanStyle(color = Color(0x28FFFFFF), textDecoration = TextDecoration.Underline, background = Color(0x28FF8800))) {
                            append("highlighted span")
                        }
                        append(" end")
                    },
                    inter,
                    maxWidthPx = 800,
                ),
                to = StyledTextV2(
                    buildAnnotatedString {
                        append("End ")
                        withStyle(SpanStyle(color = Color(0x28FFFFFF), textDecoration = TextDecoration.Underline, background = Color(0x28FF8800))) {
                            append("highlighted span")
                        }
                        append(" muted")
                    },
                    inter,
                    maxWidthPx = 800,
                ),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 20: Token wrapping differently across endpoints & differing endpoint widths
            Fixture(
                name = "different-wrap-and-widths",
                from = StyledTextV2(AnnotatedString("Compose Desktop renders typography cleanly across narrow and wide slides"), inter, maxWidthPx = 620),
                to = StyledTextV2(AnnotatedString("Compose Desktop renders typography cleanly across narrow and wide slides"), inter, maxWidthPx = 340),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 21: Ink overflowing paragraph box (soft shadow within 2*lineHeight)
            Fixture(
                name = "shadow-overflow-within-margin",
                from = StyledTextV2(
                    AnnotatedString("Glowing Shadow Headline"),
                    inter.copy(shadow = Shadow(Color(0x9000D4FF), offset = Offset(4f, 6f), blurRadius = 10f)),
                    maxWidthPx = 800,
                ),
                to = StyledTextV2(
                    AnnotatedString("Headline Glowing Shadow"),
                    inter.copy(shadow = Shadow(Color(0x9000D4FF), offset = Offset(4f, 6f), blurRadius = 10f)),
                    maxWidthPx = 800,
                ),
                by = MorphBy.Word,
                expectAdmittedBoth = true,
            ),
            // 22: Ink overflowing beyond 2*lineHeight (outer ring ink check refuses partition!)
            Fixture(
                name = "overflow-beyond-2-lineheight-refused",
                from = StyledTextV2(
                    AnnotatedString("Normal Detached"),
                    inter.copy(shadow = Shadow(Color(0xFF00D4FF), offset = Offset(0f, 165f), blurRadius = 32f)),
                    maxWidthPx = 800,
                ),
                to = StyledTextV2(AnnotatedString("Normal"), inter, maxWidthPx = 800),
                by = MorphBy.Word,
            ),
            // 23: Uncovered codepoint in paragraph -> refuses ENTIRE paragraph for Character!
            Fixture(
                name = "fallback-uncovered-codepoint-paragraph",
                from = StyledTextV2(AnnotatedString("Hello ✦world today"), hanken, maxWidthPx = 800),
                to = StyledTextV2(AnnotatedString("today ✦world Hello"), hanken, maxWidthPx = 800),
                by = MorphBy.Character,
                expectCharacterSplits = false,
                expectAdmittedBoth = true,
            ),
            // 24: Plain monochrome emoji (no joiner/selector) routed to NotoEmoji IS judged normally!
            Fixture(
                name = "plain-emoji-char-eligible",
                from = StyledTextV2(AnnotatedString("Hello 🔨 ship"), hanken, maxWidthPx = 800),
                to = StyledTextV2(AnnotatedString("ship 🔨 Hello"), hanken, maxWidthPx = 800),
                by = MorphBy.Character,
                expectCharacterSplits = true,
                expectAdmittedBoth = true,
            ),
        )

        val summary = StringBuilder("\n=== Difficult Fixture Matrix (${fixtures.size} fixtures, both directions + mid-morphs) ===\n")
        for (fx in fixtures) {
            val schedule = PieceScheduleV2(totalDurationMs = 500, slots = emptyMap())
            val fwdMorph = TextContentV2.Morph(fx.from, fx.to, fx.by, schedule = schedule, elapsedMs = 250f, moveCurve = CurveV2.Linear)
            val revMorph = TextContentV2.Morph(fx.to, fx.from, fx.by, schedule = schedule, elapsedMs = 250f, moveCurve = CurveV2.Linear)

            val fwdPlan = renderer.planMorph(fwdMorph, measurer, density, config = config)
            val revPlan = renderer.planMorph(revMorph, measurer, density, config = config)

            // Every admitted partition MUST satisfy independent whole-vs-pieces pixel delta <= 2/255!
            for ((side, part) in listOf("from" to fwdPlan.fromPartition, "to" to fwdPlan.toPartition)) {
                if (part.admitted) {
                    assertTrue(part.maxChannelDelta <= 2, "${fx.name} $side maxDelta=${part.maxChannelDelta}")
                    assertFalse(part.outerRingInkDetected, "${fx.name} $side outerRingInk")
                    val pad = 80
                    val cw = (part.wholeLayout.size.width + pad * 2).coerceIn(120, 1280)
                    val ch = (part.wholeLayout.size.height + pad * 2).coerceIn(120, 520)
                    val wholeImg = MagicTextV2TestSupport.renderCanvasToRgba(cw, ch, density) {
                        withTransform({ translate(pad.toFloat(), pad.toFloat()) }) {
                            drawText(part.wholeLayout)
                        }
                    }
                    val piecesImg = MagicTextV2TestSupport.renderCanvasToRgba(cw, ch, density) {
                        withTransform({ translate(pad.toFloat(), pad.toFloat()) }) {
                            for (p in part.pieces) {
                                withTransform({ translate(p.originX, p.originY) }) {
                                    drawText(p.layout)
                                }
                            }
                        }
                    }
                    val indepDiff = MagicTextV2TestSupport.compareRgba(wholeImg, piecesImg, tolerance = 2)
                    assertTrue(
                        indepDiff.maxDelta <= 2,
                        "${fx.name} $side independent whole-vs-pieces pixel delta=${indepDiff.maxDelta} (> 2/255, diffPx=${indepDiff.differingPixelsOverTol})",
                    )
                }
            }

            // Render real mid-morph frames (p = 0.35, 0.5, 0.7) and verify bidirectional symmetry!
            val morphW = maxOf(fwdPlan.fromPartition.wholeLayout.size.width, fwdPlan.toPartition.wholeLayout.size.width) + 120
            val morphH = maxOf(fwdPlan.fromPartition.wholeLayout.size.height, fwdPlan.toPartition.wholeLayout.size.height) + 120
            val clampedW = morphW.coerceIn(160, 1280)
            val clampedH = morphH.coerceIn(120, 560)
            val fwd35 = MagicTextV2TestSupport.renderCanvasToRgba(clampedW, clampedH, density) {
                withTransform({ translate(40f, 40f) }) { drawMorphFrame(fwdPlan, fwdMorph.copy(elapsedMs = 175f), config) }
            }
            val fwd50 = MagicTextV2TestSupport.renderCanvasToRgba(clampedW, clampedH, density) {
                withTransform({ translate(40f, 40f) }) { drawMorphFrame(fwdPlan, fwdMorph.copy(elapsedMs = 250f), config) }
            }
            val fwd70 = MagicTextV2TestSupport.renderCanvasToRgba(clampedW, clampedH, density) {
                withTransform({ translate(40f, 40f) }) { drawMorphFrame(fwdPlan, fwdMorph.copy(elapsedMs = 350f), config) }
            }
            assertTrue(MagicTextV2TestSupport.countInkPixels(fwd35) > 0, "${fx.name} p=0.35 drew no ink")
            assertTrue(MagicTextV2TestSupport.countInkPixels(fwd50) > 0, "${fx.name} p=0.50 drew no ink")
            assertTrue(MagicTextV2TestSupport.countInkPixels(fwd70) > 0, "${fx.name} p=0.70 drew no ink")

            if (fx.expectAdmittedBoth == true) {
                assertTrue(fwdPlan.fromPartition.admitted, "Expected from admitted for ${fx.name} (reason=${fwdPlan.fromPartition.refusalReason})")
                assertTrue(fwdPlan.toPartition.admitted, "Expected to admitted for ${fx.name} (reason=${fwdPlan.toPartition.refusalReason})")
            }
            if (fx.expectCharacterSplits == true) {
                assertTrue(fwdPlan.fromPartition.pieces.any { it.level == PartitionGranularity.Character }, "Expected character pieces for ${fx.name}")
            } else if (fx.expectCharacterSplits == false) {
                assertFalse(fwdPlan.fromPartition.pieces.any { it.level == PartitionGranularity.Character }, "${fx.name} must NEVER split below word!")
            }

            // Every piece owned by strictly 1 group; symmetric Move & CrossFadeInPlace counts!
            if (!fwdPlan.wholeParagraphCrossFade) {
                val srcOwned = fwdPlan.groups.flatMap { it.sourcePieces.map { p -> p.index } }
                val dstOwned = fwdPlan.groups.flatMap { it.targetPieces.map { p -> p.index } }
                assertEquals(fwdPlan.fromPartition.pieces.size, srcOwned.size, "${fx.name} source piece ownership count")
                assertEquals(srcOwned.size, srcOwned.toSet().size, "${fx.name} duplicate source piece ownership")
                assertEquals(fwdPlan.toPartition.pieces.size, dstOwned.size, "${fx.name} target piece ownership count")
                assertEquals(dstOwned.size, dstOwned.toSet().size, "${fx.name} duplicate target piece ownership")
            }
            assertEquals(fwdPlan.groups.count { it.kind == DrawableGroupKind.Move }, revPlan.groups.count { it.kind == DrawableGroupKind.Move })
            assertEquals(fwdPlan.groups.count { it.kind == DrawableGroupKind.CrossFadeInPlace }, revPlan.groups.count { it.kind == DrawableGroupKind.CrossFadeInPlace })

            if (fx.name == "overflow-beyond-2-lineheight-refused") {
                assertTrue(!fwdPlan.fromPartition.admitted && fwdPlan.fromPartition.outerRingInkDetected)
            }
            if (fx.name == "emoji-zwj-monochrome" || fx.name == "plain-emoji-char-eligible") {
                val rendered = MagicTextV2TestSupport.renderCanvasToRgba(920, 140, density) {
                    drawText(fwdPlan.fromPartition.wholeLayout)
                }
                assertEquals(0, MagicTextV2TestSupport.countColoredNonGrayscalePixels(rendered), "NotoEmoji must have 0 colored pixels")
            }

            summary.append(
                "  %-40s | from: %-5s %-9s r=%d Δ=%d | to: %-5s %-9s r=%d Δ=%d | grp=%d\n".format(
                    fx.name,
                    fwdPlan.fromPartition.admitted,
                    fwdPlan.fromPartition.effectiveGranularity,
                    fwdPlan.fromPartition.coarseningRounds,
                    fwdPlan.fromPartition.maxChannelDelta,
                    fwdPlan.toPartition.admitted,
                    fwdPlan.toPartition.effectiveGranularity,
                    fwdPlan.toPartition.coarseningRounds,
                    fwdPlan.toPartition.maxChannelDelta,
                    fwdPlan.groups.size,
                ),
            )
        }
        println(summary.toString())
    }

    @Test
    fun asymmetricAbToBaAndRealCcmpFiToFxCoarsenSymmetrically() {
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val density = Density(2f)
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val style = resolver.resolveTextStyle("HankenGrotesk", TextStyle(color = Color.White, fontSize = 32.sp))
        val renderer = MagicTextV2Renderer()

        // 1. `ab` -> `ba` by character with endpoints coarsened differently via Condition 2 hook:
        //    source `ab` fails Character -> coarsens to Word (`ab`), target `ba` admits as Character (`b`, `a`).
        val asymmetricConfig = MagicTextV2Config(
            fontResolver = resolver,
            forceCondition2FailureForEndpoint = { styled, level ->
                styled.text.text == "ab" && level == PartitionGranularity.Character
            },
        )
        val ab = StyledTextV2(AnnotatedString("ab"), style, maxWidthPx = 400)
        val ba = StyledTextV2(AnnotatedString("ba"), style, maxWidthPx = 400)
        val schedule = PieceScheduleV2(totalDurationMs = 400, slots = emptyMap())

        val fwd = renderer.planMorph(TextContentV2.Morph(ab, ba, MorphBy.Character, schedule = schedule, elapsedMs = 200f), measurer, density, config = asymmetricConfig)
        val rev = renderer.planMorph(TextContentV2.Morph(ba, ab, MorphBy.Character, schedule = schedule, elapsedMs = 200f), measurer, density, config = asymmetricConfig)

        assertEquals(1, fwd.fromPartition.pieces.size, "Source 'ab' must coarsen to 1 Word piece")
        assertEquals(PartitionGranularity.Word, fwd.fromPartition.effectiveGranularity)
        assertEquals(2, fwd.toPartition.pieces.size, "Target 'ba' must split into 2 Character pieces")
        assertEquals(1, fwd.groups.size, "ab -> ba must coalesce into 1 CrossFadeInPlace connected component")
        assertEquals(DrawableGroupKind.CrossFadeInPlace, fwd.groups.single().kind)
        assertEquals(1, rev.groups.size, "ba -> ab must symmetrically coalesce into 1 CrossFadeInPlace component")
        assertEquals(DrawableGroupKind.CrossFadeInPlace, rev.groups.single().kind)

        // 2. Real synthetic `ccmp` ligature (f + i) via production Tier B predicate:
        //    "fi" stays 1 Word piece while "fz" splits into 2 Character pieces ("f", "z")!
        val interBase = resolver.resolveFace("Inter", FontWeight.Normal, FontStyle.Normal)!!
        val interTf = resolver.renderingTypeface(interBase)!!
        val gF = interTf.getUTF32Glyph('f'.code).toInt() and 0xFFFF
        val gI = interTf.getUTF32Glyph('i'.code).toInt() and 0xFFFF
        val gLig = interTf.getUTF32Glyph('Z'.code).toInt() and 0xFFFF
        val ccmpBytes = SyntheticTtfBuilder.repackBaseInter(
            lookups = listOf(
                SyntheticTtfBuilder.LookupSpec(
                    type = 4,
                    rawSubtables = listOf(SyntheticTtfBuilder.buildLigatureFormat1Subtable(listOf(listOf(gF, gI) to gLig))),
                ),
            ),
            features = listOf(SyntheticTtfBuilder.FeatureSpec("ccmp", listOf(0))),
        )
        val ccmpResolver = DeckFontResolver(
            declarations = listOf(DeckFontDeclaration(family = "CcmpFi", src = "ccmp.ttf", weight = 400)),
            resourceLoader = FontResourceLoader { if (it == "ccmp.ttf") ccmpBytes else null },
            systemFontLookup = SystemFontLookup { null },
        )
        val ccmpStyle = ccmpResolver.resolveTextStyle("CcmpFi", TextStyle(color = Color.White, fontSize = 32.sp))
        val ccmpCfg = MagicTextV2Config(fontResolver = ccmpResolver)
        val ccmpPlan = renderer.planMorph(
            TextContentV2.Morph(
                from = StyledTextV2(AnnotatedString("fi"), ccmpStyle, maxWidthPx = 400),
                to = StyledTextV2(AnnotatedString("fz"), ccmpStyle, maxWidthPx = 400),
                by = MorphBy.Character,
                schedule = schedule,
                elapsedMs = 200f,
            ),
            measurer,
            density,
            config = ccmpCfg,
        )
        assertEquals(1, ccmpPlan.fromPartition.pieces.size, "Tier B ccmp f+i must keep 'fi' as 1 Word piece")
        assertEquals(PartitionGranularity.Word, ccmpPlan.fromPartition.pieces.single().level)
        assertEquals(2, ccmpPlan.toPartition.pieces.size, "Tier B must split 'fz' into 2 Character pieces")
        assertTrue(ccmpPlan.toPartition.pieces.all { it.level == PartitionGranularity.Character })
        assertTrue(ccmpPlan.groups.any { it.kind == DrawableGroupKind.CrossFadeInPlace })
    }

    @Test
    fun productionPredicateMatrixAll9LatinFamiliesUnclippedFadesAndFailClosed() {
        val density = Density(2f)
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val renderer = MagicTextV2Renderer()

        // 0. Guard against any second character-safety predicate creeping back into MagicText v2!
        val v2Methods = Class.forName("dev.sebastiano.ciacola.text.v2.CharacterSafetyAndEmoji_desktopKt").declaredMethods.map { it.name }
        assertFalse("isFaceCharacterSafeV2" in v2Methods, "Second face character-safety predicate must not exist!")
        assertFalse("isWordCharacterSafeV2" in v2Methods, "Second word character-safety predicate must not exist!")

        val bundled = MagicTextV2TestSupport.createBundledFontResolver()
        val interFace = assertNotNull(bundled.resolveFace("Inter", FontWeight.Normal, FontStyle.Normal))
        val interTf = assertNotNull(bundled.renderingTypeface(interFace))
        val interNumGlyphs = assertNotNull(interFace.inspection).numGlyphs
        fun gid(ch: Char): Int = interTf.getUTF32Glyph(ch.code).toInt() and 0xFFFF
        val gA = gid('a')
        val gC = gid('c')
        val gF = gid('f')
        val gI = gid('i')
        val gT = gid('t')
        val gZ = gid('Z')

        fun admitCatThroughProduction(
            ttfBytes: ByteArray,
            text: String = "cat",
            color: Color = Color.White,
            characterDeliveryNode: Boolean = true,
            limits: GsubParserLimits = GsubParserLimits.Default,
        ): AdmittedPartition {
            val res = DeckFontResolver(
                declarations = listOf(DeckFontDeclaration(family = "Synth", src = "synth.ttf", weight = 400)),
                resourceLoader = FontResourceLoader { if (it == "synth.ttf") ttfBytes else null },
                systemFontLookup = SystemFontLookup { null },
                gsubParserLimits = limits,
            )
            val style = res.resolveTextStyle("Synth", TextStyle(color = color, fontSize = 28.sp))
            val cfg = MagicTextV2Config(fontResolver = res, condition1Limits = limits)
            return MagicTextV2Renderer().admitPartition(
                styled = StyledTextV2(AnnotatedString(text), style, maxWidthPx = 600),
                requestedGranularity = if (characterDeliveryNode) PartitionGranularity.Character else PartitionGranularity.Word,
                characterDeliveryNode = characterDeliveryNode,
                measurer = measurer,
                density = density,
                config = cfg,
            )
        }

        val dummyLig = SyntheticTtfBuilder.buildLigatureFormat1Subtable(listOf(listOf(gF, gI) to gZ))
        // Visually-identical self-mapping contextual rule (empty seqLookups -> draws IDENTICALLY to letters!).
        val visuallyIdenticalCtx1 = SyntheticTtfBuilder.buildContextFormat1Subtable(gC, listOf(gA), emptyList())

        // 1. Record-based classification through production renderer!
        val plainLigaBytes = SyntheticTtfBuilder.repackBaseInter(
            lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
            features = listOf(SyntheticTtfBuilder.FeatureSpec("liga", listOf(0))),
        )
        assertTrue(
            admitCatThroughProduction(plainLigaBytes).pieces.all { it.level == PartitionGranularity.Character },
            "Plain liga under CharacterForcedOff must split 'cat' to characters",
        )
        val plainSs01Bytes = SyntheticTtfBuilder.repackBaseInter(
            lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
            features = listOf(SyntheticTtfBuilder.FeatureSpec("ss01", listOf(0))),
        )
        assertTrue(
            admitCatThroughProduction(plainSs01Bytes).pieces.all { it.level == PartitionGranularity.Character },
            "Plain ss01 under CharacterForcedOff must split 'cat' to characters",
        )

        // Required liga, ccmp, ss01 + FeatureVariations on liga, ccmp, ss01 + unregistered tag -> ALL refuse!
        val recordRejectFixtures = listOf(
            "required-liga" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("liga", listOf(0))),
                requiredFeatureIndex = 0,
            ),
            "required-ccmp" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("ccmp", listOf(0))),
                requiredFeatureIndex = 0,
            ),
            "required-ss01" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("ss01", listOf(0))),
                requiredFeatureIndex = 0,
            ),
            "featvar-liga" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("liga", emptyList())),
                featureVariations = listOf(listOf(SyntheticTtfBuilder.VariationSub(0, listOf(0)))),
            ),
            "featvar-ccmp" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("ccmp", emptyList())),
                featureVariations = listOf(listOf(SyntheticTtfBuilder.VariationSub(0, listOf(0)))),
            ),
            "featvar-ss01" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("ss01", emptyList())),
                featureVariations = listOf(listOf(SyntheticTtfBuilder.VariationSub(0, listOf(0)))),
            ),
            "unregistered-tag" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("xxxx", listOf(0))),
            ),
        )
        for ((label, bytes) in recordRejectFixtures) {
            val part = admitCatThroughProduction(bytes)
            assertFalse(
                part.pieces.any { it.level == PartitionGranularity.Character },
                "Record classification '$label' must reject character split on 'cat'",
            )
            // Low-alpha variant must ALSO reject structurally!
            val lowAlphaPart = admitCatThroughProduction(bytes, color = Color(0x0AFFFFFF))
            assertFalse(
                lowAlphaPart.pieces.any { it.level == PartitionGranularity.Character },
                "Low-alpha record classification '$label' must reject character split on 'cat'",
            )
        }

        // 2. Alternating configuration guard on the SAME liga font (ShaperDefaults never inspects font!).
        CharacterSafetyEngine.clearTierACache()
        val altRes = DeckFontResolver(
            declarations = listOf(DeckFontDeclaration(family = "AltLiga", src = "alt.ttf", weight = 400)),
            resourceLoader = FontResourceLoader { if (it == "alt.ttf") plainLigaBytes else null },
            systemFontLookup = SystemFontLookup { null },
        )
        val altStyle = altRes.resolveTextStyle("AltLiga", TextStyle(color = Color.White, fontSize = 28.sp))
        val altCatLayout = measurer.measure(AnnotatedString("cat"), altStyle)
        val altCatLtr: (Int) -> Boolean = { off ->
            val c = off.coerceIn(0, 2)
            altCatLayout.getParagraphDirection(c) == androidx.compose.ui.text.style.ResolvedTextDirection.Ltr &&
                altCatLayout.getBidiRunDirection(c) == androidx.compose.ui.text.style.ResolvedTextDirection.Ltr
        }
        val beforeDef = CharacterSafetyEngine.tierAEvaluationCount.get()
        assertFalse(
            altRes.certifyParagraphCharacterSafety(
                AnnotatedString("cat"),
                altStyle,
                EffectiveFontFeatureConfig.ShaperDefaults,
                altCatLtr,
            ).isWordRangeSafe(0, 3),
        )
        assertEquals(beforeDef, CharacterSafetyEngine.tierAEvaluationCount.get(), "ShaperDefaults must not inspect font")
        assertTrue(
            altRes.certifyParagraphCharacterSafety(
                AnnotatedString("cat"),
                altStyle,
                EffectiveFontFeatureConfig.CharacterForcedOff,
                altCatLtr,
            ).isWordRangeSafe(0, 3),
        )
        assertTrue(CharacterSafetyEngine.tierAEvaluationCount.get() > beforeDef)

        // 3. Tier A synthetic TTFs (direct + Extension 7 wrapped + deleting Type 2 + morx/Silf + corrupt +
        //    visually identical + low alpha + boundary numGlyphs - 1 vs numGlyphs + lookupFlags skip + benign Type 1/2).
        val ctx2 = SyntheticTtfBuilder.buildContextFormat2Subtable(
            SyntheticTtfBuilder.buildCoverageFormat1(listOf(gC)),
            SyntheticTtfBuilder.buildClassDefFormat1(gC, listOf(1)),
            firstClass = 1,
            remainingClasses = listOf(0),
        )
        val ctx3 = SyntheticTtfBuilder.buildContextFormat3Subtable(
            listOf(SyntheticTtfBuilder.buildCoverageFormat1(listOf(gC)), SyntheticTtfBuilder.buildCoverageFormat1(listOf(gA))),
        )
        val chain1 = SyntheticTtfBuilder.buildChainedFormat1Subtable(listOf(gF), gC, listOf(gA), listOf(gT))
        val chain2 = SyntheticTtfBuilder.buildChainedFormat2Subtable(
            SyntheticTtfBuilder.buildCoverageFormat1(listOf(gC)),
            SyntheticTtfBuilder.buildClassDefFormat1(gF, listOf(1)),
            SyntheticTtfBuilder.buildClassDefFormat1(gC, listOf(1)),
            SyntheticTtfBuilder.buildClassDefFormat1(gT, listOf(1)),
            listOf(1),
            1,
            emptyList(),
            listOf(1),
        )
        val chain3 = SyntheticTtfBuilder.buildChainedFormat3Subtable(
            listOf(SyntheticTtfBuilder.buildCoverageFormat1(listOf(gF))),
            listOf(SyntheticTtfBuilder.buildCoverageFormat1(listOf(gC))),
            listOf(SyntheticTtfBuilder.buildCoverageFormat1(listOf(gT))),
        )
        val rev8 = SyntheticTtfBuilder.buildReverseChainedFormat1Subtable(
            inputPairs = listOf(gC to gC), // visually identical!
            backCoverages = listOf(SyntheticTtfBuilder.buildCoverageFormat1(listOf(gF))),
        )
        val deletingType2 = SyntheticTtfBuilder.buildMultipleFormat1Subtable(listOf(gF to emptyList()))
        val nonEmptyType2 = SyntheticTtfBuilder.buildMultipleFormat1Subtable(listOf(gF to listOf(gF, gI)))
        val benignType1 = SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(gF to gF))

        val tierARejects = listOf(
            "rlig-type4" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "rclt-ctx1-visually-identical" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(5, rawSubtables = listOf(visuallyIdenticalCtx1))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rclt", listOf(0))),
            ),
            "rvrn-ctx2" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(5, rawSubtables = listOf(ctx2))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rvrn", listOf(0))),
            ),
            "rlig-ctx3" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(5, rawSubtables = listOf(ctx3))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "rlig-chain1" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(6, rawSubtables = listOf(chain1))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "rlig-chain2" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(6, rawSubtables = listOf(chain2))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "rlig-chain3" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(6, rawSubtables = listOf(chain3))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "rlig-rev8" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(8, rawSubtables = listOf(rev8))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "ext7-type4" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(7, extensionInnerType = 4, extensionInnerSubtable = dummyLig)),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "ext7-type5" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(7, extensionInnerType = 5, extensionInnerSubtable = visuallyIdenticalCtx1)),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "ext7-type6" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(7, extensionInnerType = 6, extensionInnerSubtable = chain1)),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "ext7-type8" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(7, extensionInnerType = 8, extensionInnerSubtable = rev8)),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
            "deleting-type2" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(2, rawSubtables = listOf(deletingType2))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rvrn", listOf(0))),
            ),
            "morx-table" to SyntheticTtfBuilder.repackBaseInter(extraTables = mapOf("morx" to ByteArray(16))),
            "silf-table" to SyntheticTtfBuilder.repackBaseInter(extraTables = mapOf("Silf" to ByteArray(16))),
            "truncated-gsub" to SyntheticTtfBuilder.repackBaseInter(rawGsubOverride = ByteArray(6)),
            "bad-lookup-index" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(1, rawSubtables = listOf(benignType1))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rvrn", listOf(99))),
            ),
            "nested-cycle" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(
                    SyntheticTtfBuilder.LookupSpec(
                        5,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildContextFormat1Subtable(gF, emptyList(), listOf(SyntheticTtfBuilder.SeqLookupRec(0, 0))),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("ccmp", listOf(0))),
            ),
            "missing-maxp" to SyntheticTtfBuilder.repackBaseInter(removeTables = setOf("maxp")),
            "out-of-domain-numGlyphs" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(
                    SyntheticTtfBuilder.LookupSpec(
                        1,
                        rawSubtables = listOf(SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(interNumGlyphs to gA))),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("ccmp", listOf(0))),
            ),
            "lookup-flag-ignore-marks-still-rejects" to SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(4, lookupFlag = 0x0008, rawSubtables = listOf(dummyLig))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("rlig", listOf(0))),
            ),
        )
        for ((label, bytes) in tierARejects) {
            val part = admitCatThroughProduction(bytes)
            assertFalse(part.pieces.any { it.level == PartitionGranularity.Character }, "Tier A '$label' must reject 'cat'")
            val lowAlpha = admitCatThroughProduction(bytes, color = Color(0x0AFFFFFF))
            assertFalse(lowAlpha.pieces.any { it.level == PartitionGranularity.Character }, "Tier A '$label' at low alpha must reject 'cat'")
        }

        // Parse budget exceeded rejects!
        val budgetExceededPart = admitCatThroughProduction(
            SyntheticTtfBuilder.repackBaseInter(
                lookups = listOf(SyntheticTtfBuilder.LookupSpec(1, rawSubtables = listOf(benignType1))),
                features = listOf(SyntheticTtfBuilder.FeatureSpec("ccmp", listOf(0))),
            ),
            limits = GsubParserLimits(maxParsedRules = 0),
        )
        assertFalse(budgetExceededPart.pieces.any { it.level == PartitionGranularity.Character }, "Exceeded parse budget must fail closed")

        // Positive Tier A boundaries: numGlyphs - 1, non-empty Type 2, and Type 1 under Always-On do NOT reject!
        val boundaryValid = SyntheticTtfBuilder.repackBaseInter(
            lookups = listOf(
                SyntheticTtfBuilder.LookupSpec(1, rawSubtables = listOf(SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf((interNumGlyphs - 1) to gA)))),
                SyntheticTtfBuilder.LookupSpec(2, rawSubtables = listOf(nonEmptyType2)),
            ),
            features = listOf(SyntheticTtfBuilder.FeatureSpec("rvrn", listOf(0, 1))),
        )
        assertTrue(
            admitCatThroughProduction(boundaryValid).pieces.all { it.level == PartitionGranularity.Character },
            "numGlyphs-1, non-empty Type 2, and Type 1 under rvrn must NOT reject 'cat'",
        )

        // 4. Real bundled Latin families (ALL 9!): "awesome", "function", "Shaders" split to Character!
        val allBundled = MagicTextV2TestSupport.createAllBundledFontResolver()
        val allBundledConfig = MagicTextV2Config(fontResolver = allBundled)
        val nineLatinFamilies = listOf(
            "Inter" to FontStyle.Normal,
            "JetBrains Mono" to FontStyle.Normal,
            "Hanken Grotesk" to FontStyle.Normal,
            "Hanken Grotesk" to FontStyle.Italic,
            "Fredoka" to FontStyle.Normal,
            "Assistant" to FontStyle.Normal,
            "Plus Jakarta Sans" to FontStyle.Normal,
            "Montserrat" to FontStyle.Normal,
            "Roboto" to FontStyle.Normal,
            "Nanum Pen Script" to FontStyle.Normal,
        )
        for ((family, fontStyle) in nineLatinFamilies) {
            val style = allBundled.resolveTextStyle(family, TextStyle(color = Color.White, fontSize = 28.sp, fontStyle = fontStyle))
            val styled = StyledTextV2(AnnotatedString("awesome function Shaders"), style, maxWidthPx = 900)
            val part = renderer.admitPartition(styled, PartitionGranularity.Character, true, measurer, density, config = allBundledConfig)
            assertTrue(part.admitted, "Expected admitted Character partition in $family ($fontStyle): ${part.refusalReason}")
            assertTrue(
                part.pieces.all { it.level == PartitionGranularity.Character },
                "Expected 'awesome function Shaders' to split wholly to Character in $family ($fontStyle), got ${part.pieces.map { it.level }.toSet()}",
            )
            assertEquals(22, part.pieces.size, "Expected 22 characters in 'awesome function Shaders' for $family ($fontStyle)")
        }

        // 5. Feature plumbing proof including mixed weight + italic spans!
        val hankenOn = bundled.resolveTextStyle("HankenGrotesk", TextStyle(color = Color.White, fontSize = 36.sp))
        val hankenOff = hankenOn.copy(fontFeatureSettings = CHARACTER_MODE_DISABLE_LIGATURES)
        val mixedLigText = buildAnnotatedString {
            append("fi ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("fl ") }
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("ffi ffl") }
        }
        val layoutOn = TwoConditionPartitioner.measureWholeParagraph(StyledTextV2(mixedLigText, hankenOn), measurer)
        val layoutOff = TwoConditionPartitioner.measureWholeParagraph(StyledTextV2(mixedLigText, hankenOff), measurer)
        val bmpOn = MagicTextV2TestSupport.renderCanvasToRgba(680, 140, density) { drawText(layoutOn) }
        val bmpOff = MagicTextV2TestSupport.renderCanvasToRgba(680, 140, density) { drawText(layoutOff) }
        assertTrue(
            MagicTextV2TestSupport.compareRgba(bmpOn, bmpOff).maxDelta > 100,
            "Feature plumbing: unquoted ligature disable MUST change mixed weight/italic HankenGrotesk ligatures!",
        )

        // 6. Unclipped fades: overhanging italic + shadow fading at p = 0.5 MUST NOT clip overhanging ink!
        val shadowItalicStyle = bundled.resolveTextStyle(
            "HankenGrotesk",
            TextStyle(
                color = Color.White,
                fontSize = 36.sp,
                fontStyle = FontStyle.Italic,
                shadow = Shadow(Color(0xFF00D4FF), offset = Offset(24f, 22f), blurRadius = 12f),
            ),
        )
        val fadeFrom = StyledTextV2(AnnotatedString("fffff"), shadowItalicStyle, maxWidthPx = 400)
        val fadeTo = StyledTextV2(AnnotatedString("ggggg"), shadowItalicStyle, maxWidthPx = 400)
        val fadePlan = renderer.planMorph(
            TextContentV2.Morph(fadeFrom, fadeTo, MorphBy.Word, schedule = PieceScheduleV2(400, emptyMap()), elapsedMs = 200f, moveCurve = CurveV2.Linear),
            measurer,
            density,
            config = MagicTextV2Config(fontResolver = bundled),
        )
        val boxW = fadePlan.fromPartition.wholeLayout.size.width
        val boxH = fadePlan.fromPartition.wholeLayout.size.height
        val fadedRgba = MagicTextV2TestSupport.renderCanvasToRgba(boxW + 140, boxH + 140, density) {
            withTransform({ translate(20f, 20f) }) {
                drawMorphFrame(
                    fadePlan,
                    TextContentV2.Morph(fadeFrom, fadeTo, MorphBy.Word, schedule = PieceScheduleV2(400, emptyMap()), elapsedMs = 150f, moveCurve = CurveV2.Linear),
                    MagicTextV2Config(fontResolver = bundled),
                )
            }
        }
        // Check pixels strictly OUTSIDE the layout box (below boxH + 20 + 8) where the 22px offset shadow lives!
        var shadowInkOutsideBox = 0
        val wStride = boxW + 140
        for (y in (boxH + 26) until (boxH + 60)) {
            for (x in 20 until (boxW + 60)) {
                if ((fadedRgba[(y * wStride + x) * 4 + 3].toInt() and 0xFF) > 10) shadowInkOutsideBox++
            }
        }
        assertTrue(
            shadowInkOutsideBox > 80,
            "Unclipped saveLayer(null) fade must preserve shadow ink outside layout box (found $shadowInkOutsideBox px)",
        )

        // 7. Fail-closed Condition 2 readback!
        val readbackFailConfig = MagicTextV2Config(
            fontResolver = bundled,
            condition2ReadbackOverrideForTest = { null },
        )
        val readbackFailPart = renderer.admitPartition(
            StyledTextV2(AnnotatedString("Hello World"), bundled.resolveTextStyle("Inter", TextStyle(color = Color.White, fontSize = 26.sp)), maxWidthPx = 600),
            PartitionGranularity.Word,
            false,
            measurer,
            density,
            config = readbackFailConfig,
        )
        assertFalse(readbackFailPart.admitted, "Null offscreen pixel readback MUST fail closed (admitted = false)")

        // 8. Affine transforms (rotations, scales, fractional offsets) + forced Condition 2 failure.
        val transformStyled = StyledTextV2(
            AnnotatedString("Transformed typography stays crisp"),
            bundled.resolveTextStyle("Inter", TextStyle(color = Color.White, fontSize = 26.sp, lineHeight = 36.sp)),
            maxWidthPx = 640,
        )
        val admitted = renderer.admitPartition(transformStyled, PartitionGranularity.Word, false, measurer, density, config = MagicTextV2Config(fontResolver = bundled))
        assertTrue(admitted.admitted)

        val transforms = listOf("offset-0.35x0.65", "rot-12deg", "rot-minus12deg", "scale-0.75", "scale-1.25", "combined")
        for (label in transforms) {
            fun applyT(scope: androidx.compose.ui.graphics.drawscope.DrawTransform) {
                scope.translate(120f, 90f)
                when (label) {
                    "offset-0.35x0.65" -> scope.translate(0.35f, 0.65f)
                    "rot-12deg" -> scope.rotate(12f, Offset(200f, 40f))
                    "rot-minus12deg" -> scope.rotate(-12f, Offset(200f, 40f))
                    "scale-0.75" -> scope.scale(0.75f, 0.75f, Offset(200f, 40f))
                    "scale-1.25" -> scope.scale(1.25f, 1.25f, Offset(200f, 40f))
                    "combined" -> {
                        scope.translate(0.35f, 0.65f)
                        scope.rotate(12f, Offset(200f, 40f))
                        scope.scale(1.25f, 1.25f, Offset(200f, 40f))
                    }
                }
            }
            val wholePx = MagicTextV2TestSupport.renderCanvasToRgba(900, 300, density) {
                withTransform({ applyT(this) }) { drawText(admitted.wholeLayout) }
            }
            val piecesPx = MagicTextV2TestSupport.renderCanvasToRgba(900, 300, density) {
                withTransform({ applyT(this) }) {
                    for (p in admitted.pieces) {
                        withTransform({ translate(p.originX, p.originY) }) { drawText(p.layout) }
                    }
                }
            }
            val diff = MagicTextV2TestSupport.compareRgba(wholePx, piecesPx, tolerance = 2)
            assertTrue(diff.maxDelta <= 2, "affine transform '$label' exceeded 2/255: maxDelta=${diff.maxDelta}")
        }

        val forcedConfig = MagicTextV2Config(fontResolver = bundled, forceCondition2Failure = true)
        val forcedPart = renderer.admitPartition(transformStyled, PartitionGranularity.Word, false, measurer, density, config = forcedConfig)
        assertFalse(forcedPart.admitted, "forceCondition2Failure must refuse partition")
        val forcedPlan = renderer.planMorph(
            TextContentV2.Morph(transformStyled, transformStyled, MorphBy.Word, schedule = PieceScheduleV2(400, emptyMap()), elapsedMs = 200f),
            measurer,
            density,
            config = forcedConfig,
        )
        assertTrue(forcedPlan.wholeParagraphCrossFade)
    }

    // -------------------------------------------------------------------------
    // Single semantics node (0 child token nodes) + real StoryboardPdfExporter
    // selectable text verification for BOTH unrotated and 15°-rotated text nodes.
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun singleSemanticsNodeAndPdfExportSelectableTextIncludingRotatedNode() {
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val style = resolver.resolveTextStyle("Inter", TextStyle(color = Color.White, fontSize = 24.sp, lineHeight = 32.sp))
        val renderer = MagicTextV2Renderer()
        val config = MagicTextV2Config(fontResolver = resolver)

        val unrotatedText = "MagicText v2 single semantics node across twenty distinct words one two three four five"
        val rotatedText = "Rotated headline fifteen degrees selectable"

        val checkScene = ImageComposeScene(width = 960, height = 540, density = Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                renderer.TextNode(
                    content = { TextContentV2.Rest(StyledTextV2(AnnotatedString(unrotatedText), style, maxWidthPx = 800)) },
                    modifier = Modifier.offset(40.dp, 40.dp),
                    config = config,
                )
                renderer.TextNode(
                    content = {
                        TextContentV2.Morph(
                            from = StyledTextV2(AnnotatedString("Alpha Beta Gamma"), style, maxWidthPx = 400),
                            to = StyledTextV2(AnnotatedString("Gamma Delta Alpha"), style, maxWidthPx = 400),
                            by = MorphBy.Word,
                            schedule = PieceScheduleV2(500, emptyMap()),
                            elapsedMs = 250f,
                        )
                    },
                    modifier = Modifier.offset(40.dp, 200.dp),
                    config = config,
                )
            }
        }
        try {
            checkScene.render(0L)
            checkScene.render(16_000_000L)
            val textNodes = checkScene.semanticsOwners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }

            assertEquals(2, textNodes.size, "Expected strictly 2 semantics nodes (1 per TextNode, 0 per token)")
            for (node in textNodes) {
                assertNotNull(node.config.getOrNull(SemanticsProperties.Text))
                val out = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(out)
                assertEquals(1, out.size, "GetTextLayoutResult must return target whole-paragraph layout")
            }
        } finally {
            checkScene.close()
        }

        val board = Storyboard.build(
            title = "PdfSelectabilityCheck",
            format = SceneFormat(IntSize(960, 540), Density(1f)),
        ) {
            scene(frameCount = 1) {
                Box(Modifier.fillMaxSize()) {
                    renderer.TextNode(
                        content = { TextContentV2.Rest(StyledTextV2(AnnotatedString(unrotatedText), style, maxWidthPx = 800)) },
                        modifier = Modifier.offset(40.dp, 60.dp),
                        config = config,
                    )
                    renderer.TextNode(
                        content = { TextContentV2.Rest(StyledTextV2(AnnotatedString(rotatedText), style, maxWidthPx = 600)) },
                        modifier = Modifier
                            .offset(120.dp, 260.dp)
                            .graphicsLayer { rotationZ = 15f },
                        config = config,
                    )
                }
            }
        }

        val tmpPdf = Files.createTempFile("ciacola-pdf-check-", ".pdf")
        try {
            val exporter = StoryboardPdfExporter()
            invokeInternalExportAsPdf(exporter, board, tmpPdf)
            assertTrue(Files.size(tmpPdf) > 500, "Generated PDF must be non-empty")

            Loader.loadPDF(tmpPdf.toFile()).use { doc ->
                assertEquals(1, doc.numberOfPages)
                val extracted = PDFTextStripper().getText(doc).replace(Regex("\\s+"), " ").trim()
                assertTrue(extracted.contains("MagicText v2 single semantics node"), "PDF missing unrotated text: '$extracted'")
                assertTrue(extracted.contains("Rotated headline fifteen degrees selectable"), "PDF missing 15-deg rotated text: '$extracted'")
            }
        } finally {
            Files.deleteIfExists(tmpPdf)
        }
    }

    private fun invokeInternalExportAsPdf(
        exporter: StoryboardPdfExporter,
        storyboard: Storyboard,
        path: java.nio.file.Path,
    ) = runBlocking {
        val method = StoryboardPdfExporter::class.java.declaredMethods
            .first { it.name.startsWith("exportAsPdf") && it.parameterCount == 3 }
        method.isAccessible = true
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont: Continuation<Any?> ->
            method.invoke(exporter, storyboard, path, cont)
        }
    }
}
