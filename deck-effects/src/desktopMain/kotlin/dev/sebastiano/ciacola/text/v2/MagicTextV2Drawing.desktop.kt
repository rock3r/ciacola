package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.effects.v2.BuildEffects
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.effects.v2.EffectClip
import dev.sebastiano.ciacola.effects.v2.EffectEvalContext
import dev.sebastiano.ciacola.effects.v2.LayerEffects
import dev.sebastiano.ciacola.effects.v2.LayerState
import dev.sebastiano.ciacola.effects.v2.MotionPresets
import dev.sebastiano.ciacola.effects.v2.PieceContext
import kotlin.math.abs
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect

/**
 * Unclipped single-`DrawScope` drawing primitives for `MagicText v2`.
 *
 * Guarantees:
 * - Zero managed heap allocations per frame on layer-free draws.
 * - Offscreen fade and additive cross-fade layers pass empirically measured non-zero-alpha ink
 *   bounds (`verifiedInkBounds` with 4 px safety halo and 1 px scratch-border refusal) for
 *   Condition-2-admitted pieces, and strictly `null` (`saveLayer(null, paint)`, unbounded) whenever
 *   any piece/batch has unverified bounds, native-paragraph fallback, or transformed blur so
 *   overhanging italic glyphs, shadows, baseline-shifted ink, and overflowing native paragraphs
 *   are NEVER clipped.
 * - Size-changing `Move` groups render 100% pure vector glyphs from `largeLayout` (`max(fromSp, toSp)`)
 *   scaled down on the interpolated baseline with zero intermediate raster bitmaps.
 * - Piece build timing and morph curves come strictly from planner-resolved slots (`BuildPlanV2` /
 *   `MorphPlanV2`); zero timing is invented at draw time.
 */
internal object MagicTextV2Drawing {
    private val reusableLayerPaint: ThreadLocal<SkiaPaint> = ThreadLocal.withInitial { SkiaPaint() }
    private val reusablePlusPaint: ThreadLocal<SkiaPaint> = ThreadLocal.withInitial {
        SkiaPaint().apply { blendMode = SkiaBlendMode.PLUS }
    }
    private val reusableVectorPaint: ThreadLocal<SkiaPaint> = ThreadLocal.withInitial {
        SkiaPaint().apply { isAntiAlias = true }
    }

    /** `Rest` drawing: single `drawText(wholeLayout)` — 100% pixel-identical to `BasicText`. */
    fun DrawScope.drawRestParagraph(wholeLayout: TextLayoutResult) {
        drawText(wholeLayout)
    }

    /** Single-`DrawScope` morph frame renderer. */
    fun DrawScope.drawMorphFrame(
        plan: MorphPlanV2,
        content: TextContentV2.Morph,
        config: MagicTextV2Config = MagicTextV2Config(),
    ) = drawMorphFrame(
        plan = plan,
        elapsedMs = content.elapsedMs,
        totalDurationMs = content.schedule.totalDurationMs,
        exitEffect = content.exitEffect,
        enterEffect = content.enterEffect,
        moveCurve = content.moveCurve,
        config = config,
    )

    /** Zero-allocation primitive-parameter morph frame renderer used on the hot draw path. */
    fun DrawScope.drawMorphFrame(
        plan: MorphPlanV2,
        elapsedMs: Float,
        totalDurationMs: Int,
        exitEffect: EffectCallV2,
        enterEffect: EffectCallV2,
        moveCurve: dev.sebastiano.ciacola.effects.v2.CurveV2,
        config: MagicTextV2Config = MagicTextV2Config(),
    ) {
        val totalMs = totalDurationMs.coerceAtLeast(1).toFloat()
        val overallFraction = (elapsedMs / totalMs).coerceIn(0f, 1f)

        if (plan.wholeParagraphCrossFade || config.reducedMotion) {
            val t = moveCurve.sample(overallFraction, totalMs).coerceIn(0f, 1f)
            drawWholeParagraphCrossFade(
                fromLayout = plan.fromPartition.wholeLayout,
                toLayout = plan.toPartition.wholeLayout,
                t = t,
            )
            return
        }

        val stageSize = config.stageSizeForEffects
        val groups = plan.groups

        for (gIdx in 0 until groups.size) {
            val group = groups[gIdx]
            when (group.kind) {
                DrawableGroupKind.Move -> drawMoveGroup(group, elapsedMs)

                DrawableGroupKind.Exit -> {
                    val rawP = group.rawProgressAt(elapsedMs)
                    val eff = exitEffect
                    val effectiveCurve = eff.curve ?: group.curve
                    if (eff.effectId == BuildEffects.DISSOLVE && !config.reducedMotion) {
                        val eased = effectiveCurve.sample(rawP, (group.endMs - group.startMs).toFloat()).coerceIn(0f, 1f)
                        val alpha = 1f - eased
                        val pieces = group.sourcePieces
                        for (pi in 0 until pieces.size) {
                            val piece = pieces[pi]
                            drawLayoutAlphaOnly(piece.layout, piece.originX, piece.originY, alpha, piece.verifiedInkBounds)
                        }
                    } else {
                        val pieces = group.sourcePieces
                        for (pi in 0 until pieces.size) {
                            val piece = pieces[pi]
                            val ctx = EffectEvalContext(
                                nodeBounds = piece.boundingBox,
                                stageSize = stageSize,
                                density = density,
                                reducedMotion = config.reducedMotion,
                            )
                            val layerState = BuildEffects.evaluateLayer(
                                effectId = eff.effectId,
                                kind = EffectBuildKind.Exit,
                                progress = rawP,
                                context = ctx,
                                args = eff.args,
                                curve = effectiveCurve,
                            )
                            drawPieceWithLayerState(piece, layerState)
                        }
                    }
                }

                DrawableGroupKind.Enter -> {
                    val rawP = group.rawProgressAt(elapsedMs)
                    val eff = enterEffect
                    val effectiveCurve = eff.curve ?: group.curve
                    if (eff.effectId == BuildEffects.DISSOLVE && !config.reducedMotion) {
                        val alpha = effectiveCurve.sample(rawP, (group.endMs - group.startMs).toFloat()).coerceIn(0f, 1f)
                        val pieces = group.targetPieces
                        for (pi in 0 until pieces.size) {
                            val piece = pieces[pi]
                            drawLayoutAlphaOnly(piece.layout, piece.originX, piece.originY, alpha, piece.verifiedInkBounds)
                        }
                    } else {
                        val pieces = group.targetPieces
                        for (pi in 0 until pieces.size) {
                            val piece = pieces[pi]
                            val ctx = EffectEvalContext(
                                nodeBounds = piece.boundingBox,
                                stageSize = stageSize,
                                density = density,
                                reducedMotion = config.reducedMotion,
                            )
                            val layerState = BuildEffects.evaluateLayer(
                                effectId = eff.effectId,
                                kind = EffectBuildKind.Enter,
                                progress = rawP,
                                context = ctx,
                                args = eff.args,
                                curve = effectiveCurve,
                            )
                            drawPieceWithLayerState(piece, layerState)
                        }
                    }
                }

                DrawableGroupKind.CrossFadeInPlace -> drawCrossFadeInPlaceGroup(group, elapsedMs)
            }
        }
    }

    private fun DrawScope.drawMoveGroup(
        group: DrawableGroup,
        elapsedMs: Float,
    ) {
        val sp = group.sourcePieces[0]
        val dp = group.targetPieces[0]
        val t = group.easedProgressAt(elapsedMs)
        val clampedT = t.coerceIn(0f, 1f)
        val rawX = lerp(sp.originX, dp.originX, t)

        val sizeAsset = group.sizeMorphAsset
        if (group.sizeChanged && sizeAsset != null) {
            val x = lerp(sizeAsset.fromCalibratedX, sizeAsset.toCalibratedX, t)
            val baselineY = lerp(sizeAsset.fromCalibratedBaselineY, sizeAsset.toCalibratedBaselineY, t)
            val scaleX = lerp(sizeAsset.fromScaleX, sizeAsset.toScaleX, t).coerceAtLeast(1e-4f)
            val scaleY = lerp(sizeAsset.fromScaleY, sizeAsset.toScaleY, t).coerceAtLeast(1e-4f)

            if (group.styleChanged && sizeAsset.toLargeLayout != null && clampedT > 0f && clampedT < 1f) {
                val scaledBounds = sizeAsset.additiveLayerBounds?.let { b ->
                    SkiaRect.makeLTRB(
                        b.left * scaleX - 4f,
                        b.top * scaleY - 4f,
                        b.right * scaleX + 4f,
                        b.bottom * scaleY + 4f,
                    )
                }
                drawAdditiveCrossFadeLayer(
                    bounds = scaledBounds,
                    fromAlpha = 1f - clampedT,
                    toAlpha = clampedT,
                    translateX = x,
                    translateY = baselineY,
                    drawFrom = {
                        drawScaledLayoutOnBaseline(
                            layout = sizeAsset.fromLargeLayout,
                            vectorRuns = sizeAsset.fromVectorRuns,
                            x = 0f,
                            baselineY = 0f,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            largePaintedBaselinePx = sizeAsset.fromLargePaintedBaselinePx,
                        )
                    },
                    drawTo = {
                        drawScaledLayoutOnBaseline(
                            layout = sizeAsset.toLargeLayout,
                            vectorRuns = sizeAsset.toVectorRuns,
                            x = 0f,
                            baselineY = 0f,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            largePaintedBaselinePx = sizeAsset.toLargePaintedBaselinePx,
                        )
                    },
                )
            } else {
                val useTo = group.styleChanged && sizeAsset.toLargeLayout != null && clampedT >= 1f
                val activeLayout = if (useTo) sizeAsset.toLargeLayout else sizeAsset.fromLargeLayout
                val activeRuns = if (useTo) sizeAsset.toVectorRuns else sizeAsset.fromVectorRuns
                val activeBase = if (useTo) sizeAsset.toLargePaintedBaselinePx else sizeAsset.fromLargePaintedBaselinePx
                drawScaledLayoutOnBaseline(
                    layout = activeLayout,
                    vectorRuns = activeRuns,
                    x = x,
                    baselineY = baselineY,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    largePaintedBaselinePx = activeBase,
                )
            }
        } else {
            // Snap within 1e-3 px of exact endpoints so near-endpoint frames (e.g. t = 1 - eps)
            // never cross a discrete Skia 0.25 px subpixel glyph-cache bin boundary.
            val x = when {
                abs(rawX - sp.originX) < 1e-3f -> sp.originX
                abs(rawX - dp.originX) < 1e-3f -> dp.originX
                else -> rawX
            }
            // Anchor vertical interpolation on the painted baseline so a piece moving between lines
            // with different LineHeightStyle.Trim (e.g. line 0 Trim.Both -> line 1 Trim.LastLineBottom)
            // never suffers a vertical baseline pop at t -> 1 or during BlendMode.PLUS cross-fades.
            val spLocalBaseline = sp.paintedBaselineY - sp.originY
            val dpLocalBaseline = dp.paintedBaselineY - dp.originY
            val rawBaselineY = lerp(sp.paintedBaselineY, dp.paintedBaselineY, t)
            val baselineY = when {
                abs(rawBaselineY - sp.paintedBaselineY) < 1e-3f -> sp.paintedBaselineY
                abs(rawBaselineY - dp.paintedBaselineY) < 1e-3f -> dp.paintedBaselineY
                else -> rawBaselineY
            }
            val spY = baselineY - spLocalBaseline
            val dpY = baselineY - dpLocalBaseline
            if (!group.styleChanged || clampedT <= 0f) {
                drawPieceFastTranslated(sp.layout, x, spY)
            } else if (clampedT >= 1f) {
                drawPieceFastTranslated(dp.layout, x, dpY)
            } else {
                drawAdditiveCrossFadeLayer(
                    bounds = group.additiveLayerBounds,
                    fromAlpha = 1f - clampedT,
                    toAlpha = clampedT,
                    translateX = x,
                    translateY = baselineY,
                    drawFrom = { drawPieceFastTranslated(sp.layout, 0f, -spLocalBaseline) },
                    drawTo = { drawPieceFastTranslated(dp.layout, 0f, -dpLocalBaseline) },
                )
            }
        }
    }

    private fun DrawScope.drawCrossFadeInPlaceGroup(
        group: DrawableGroup,
        elapsedMs: Float,
    ) {
        val t = group.easedProgressAt(elapsedMs).coerceIn(0f, 1f)
        val srcPieces = group.sourcePieces
        val tgtPieces = group.targetPieces
        if (t <= 0f) {
            for (pi in 0 until srcPieces.size) {
                val sp = srcPieces[pi]
                drawPieceFastTranslated(sp.layout, sp.originX, sp.originY)
            }
        } else if (t >= 1f) {
            for (pi in 0 until tgtPieces.size) {
                val dp = tgtPieces[pi]
                drawPieceFastTranslated(dp.layout, dp.originX, dp.originY)
            }
        } else {
            drawAdditiveCrossFadeLayer(
                bounds = group.additiveLayerBounds,
                fromAlpha = 1f - t,
                toAlpha = t,
                drawFrom = {
                    for (pi in 0 until srcPieces.size) {
                        val sp = srcPieces[pi]
                        drawPieceFastTranslated(sp.layout, sp.originX, sp.originY)
                    }
                },
                drawTo = {
                    for (pi in 0 until tgtPieces.size) {
                        val dp = tgtPieces[pi]
                        drawPieceFastTranslated(dp.layout, dp.originX, dp.originY)
                    }
                },
            )
        }
    }

    /**
     * Single-`DrawScope` piece-level build renderer consuming strictly planner-joined
     * [BuildPlanV2] timings (`dissolve`, `fadeMove`, `keyboard`, etc.).
     */
    fun DrawScope.drawBuildFrame(
        plan: BuildPlanV2,
        content: TextContentV2.Build,
        config: MagicTextV2Config = MagicTextV2Config(),
    ) = drawBuildFrame(
        plan = plan,
        elapsedMs = content.elapsedMs,
        totalDurationMs = content.schedule.totalDurationMs,
        defaultCurve = content.schedule.defaultCurve,
        effect = content.effect,
        caretColor = content.caretColor,
        config = config,
    )

    /** Zero-allocation primitive-parameter build frame renderer used on the hot draw path. */
    fun DrawScope.drawBuildFrame(
        plan: BuildPlanV2,
        elapsedMs: Float,
        totalDurationMs: Int,
        defaultCurve: dev.sebastiano.ciacola.effects.v2.CurveV2,
        effect: EffectCallV2,
        caretColor: Color = Color.Unspecified,
        config: MagicTextV2Config = MagicTextV2Config(),
    ) {
        val partition = plan.partition
        val totalMs = totalDurationMs.coerceAtLeast(1).toFloat()
        val overallRaw = (elapsedMs / totalMs).coerceIn(0f, 1f)
        val eff = effect

        if (!partition.admitted || config.reducedMotion || plan.pieceTimings.isEmpty()) {
            val wholeBox = Rect(
                0f,
                0f,
                partition.wholeLayout.size.width.toFloat(),
                partition.wholeLayout.size.height.toFloat(),
            )
            val ctx = EffectEvalContext(
                nodeBounds = wholeBox,
                stageSize = config.stageSizeForEffects,
                density = density,
                reducedMotion = config.reducedMotion,
            )
            val fallbackId = if (eff.effectId == BuildEffects.KEYBOARD) BuildEffects.DISSOLVE else eff.effectId
            val state = BuildEffects.evaluateLayer(
                effectId = fallbackId,
                kind = eff.kind,
                progress = overallRaw,
                context = ctx,
                args = eff.args,
                curve = eff.curve ?: defaultCurve,
            )
            drawLayoutWithLayerState(
                layout = partition.wholeLayout,
                originX = 0f,
                originY = 0f,
                state = state,
            )
            return
        }

        if (eff.effectId == BuildEffects.KEYBOARD) {
            drawKeyboardBuildFrame(plan, elapsedMs, eff, caretColor)
            return
        }

        val timings = plan.pieceTimings
        val stageSize = config.stageSizeForEffects

        if (eff.effectId == BuildEffects.DISSOLVE) {
            drawDissolveBuildFrame(plan, timings, elapsedMs, eff)
            return
        }

        val motion = MotionPresets.resolve(eff.effectId, eff.kind, eff.args)
        if (motion != null) {
            drawMotionPresetBuildFrame(plan, timings, elapsedMs, eff, motion, stageSize, config)
            return
        }

        for (i in 0 until timings.size) {
            val timing = timings[i]
            val piece = timing.piece
            val rawP = timing.rawProgressAt(elapsedMs)
            val ctx = EffectEvalContext(
                nodeBounds = piece.boundingBox,
                stageSize = stageSize,
                density = density,
                reducedMotion = false,
            )
            val layerState = BuildEffects.evaluateLayer(
                effectId = eff.effectId,
                kind = eff.kind,
                progress = rawP,
                context = ctx,
                args = eff.args,
                curve = eff.curve ?: timing.curve,
            )
            drawPieceWithLayerState(piece, layerState)
        }
    }

    /**
     * Motion preset build: channels are resolved once per frame, then evaluated per piece with the
     * piece's index, line, position inside its line, font size and the whole text box.
     */
    private fun DrawScope.drawMotionPresetBuildFrame(
        plan: BuildPlanV2,
        timings: List<BuildPieceTiming>,
        elapsedMs: Float,
        eff: EffectCallV2,
        motion: MotionPresets.ResolvedMotion,
        stageSize: Size,
        config: MagicTextV2Config,
    ) {
        val count = timings.size
        var maxLine = 0
        for (i in 0 until count) maxLine = maxOf(maxLine, timings[i].piece.line)
        val lineLengths = IntArray(maxLine + 1)
        for (i in 0 until count) lineLengths[timings[i].piece.line]++
        val visualRanks = MotionPresets.visualRanksInLine(
            lines = IntArray(count) { timings[it].piece.line },
            centerX = FloatArray(count) { timings[it].piece.boundingBox.center.x },
        )
        val whole = plan.partition.wholeLayout
        val textBounds = Rect(0f, 0f, whole.size.width.toFloat(), whole.size.height.toFloat())

        for (i in 0 until count) {
            val timing = timings[i]
            val piece = timing.piece
            val line = piece.line
            val ctx = EffectEvalContext(
                nodeBounds = piece.boundingBox,
                stageSize = stageSize,
                density = density,
                reducedMotion = false,
                piece = PieceContext(
                    index = i,
                    count = count,
                    line = line,
                    indexInLine = visualRanks[i],
                    lineLength = lineLengths[line],
                    emPx = piece.dominantFontSizeSp.sp.toPx(),
                    textBounds = textBounds,
                ),
            )
            val layerState = MotionPresets.evaluate(
                resolved = motion,
                progress = timing.rawProgressAt(elapsedMs),
                context = ctx,
                curve = eff.curve ?: timing.curve,
            )
            val axisPaint = if (layerState.axisOffsets.isEmpty()) {
                null
            } else {
                AxisPiecePainter.prepare(piece, layerState.axisOffsets, this, config.fontResolver)
            }
            if (axisPaint == null) {
                drawPieceWithLayerState(piece, layerState)
            } else {
                // Varying an axis changes the ink, so the verified rest bounds no longer apply.
                drawLayoutWithLayerState(
                    layout = piece.layout,
                    originX = piece.originX,
                    originY = piece.originY,
                    state = layerState,
                    verifiedInkBounds = null,
                    paintOverride = axisPaint,
                )
            }
        }
    }

    private fun DrawScope.drawDissolveBuildFrame(
        plan: BuildPlanV2,
        timings: List<BuildPieceTiming>,
        elapsedMs: Float,
        eff: EffectCallV2,
    ) {
        val isExit = eff.kind == EffectBuildKind.Exit
        val effCurve = eff.curve
        val canBatch = plan.allPiecesPairwiseDisjoint
        val count = timings.size

        fun alphaByteAt(index: Int): Int {
            val timing = timings[index]
            val eased = if (effCurve != null) {
                val rawP = timing.rawProgressAt(elapsedMs)
                effCurve.sample(rawP, (timing.endMs - timing.startMs).toFloat()).coerceIn(0f, 1f)
            } else {
                timing.easedProgressAt(elapsedMs).coerceIn(0f, 1f)
            }
            val a = if (isExit) 1f - eased else eased
            return (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        var i = 0
        while (i < count) {
            val aByte = alphaByteAt(i)
            if (aByte <= 0) {
                i++
                continue
            }
            val firstPiece = timings[i].piece
            if (aByte >= 255) {
                drawPieceFastTranslated(firstPiece.layout, firstPiece.originX, firstPiece.originY)
                i++
                continue
            }
            if (canBatch) {
                var j = i + 1
                while (j < count && alphaByteAt(j) == aByte) {
                    j++
                }
                if (j - i > 1) {
                    var uL = Float.POSITIVE_INFINITY
                    var uT = Float.POSITIVE_INFINITY
                    var uR = Float.NEGATIVE_INFINITY
                    var uB = Float.NEGATIVE_INFINITY
                    var anyNonEmpty = false
                    var anyUnverified = false
                    for (k in i until j) {
                        val p = timings[k].piece
                        val vb = p.verifiedInkBounds
                        if (vb == null) {
                            anyUnverified = true
                            anyNonEmpty = true
                        } else if (!vb.isEmpty) {
                            anyNonEmpty = true
                            val l = p.originX + vb.left
                            val t = p.originY + vb.top
                            val r = p.originX + vb.right
                            val b = p.originY + vb.bottom
                            if (l < uL) uL = l
                            if (t < uT) uT = t
                            if (r > uR) uR = r
                            if (b > uB) uB = b
                        }
                    }
                    if (anyNonEmpty) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val paint = reusableLayerPaint.get()
                            paint.reset()
                            paint.color = aByte shl 24
                            if (anyUnverified) {
                                native.saveLayer(null, paint)
                            } else {
                                native.saveLayer(uL, uT, uR, uB, paint)
                            }
                            try {
                                for (k in i until j) {
                                    val p = timings[k].piece
                                    native.save()
                                    native.translate(p.originX, p.originY)
                                    TextPainter.paint(canvas, p.layout)
                                    native.restore()
                                }
                            } finally {
                                native.restore()
                            }
                        }
                    }
                    i = j
                    continue
                }
            }
            drawLayoutAlphaOnly(
                firstPiece.layout,
                firstPiece.originX,
                firstPiece.originY,
                aByte / 255f,
                firstPiece.verifiedInkBounds,
            )
            i++
        }
    }

    private fun DrawScope.drawKeyboardBuildFrame(
        plan: BuildPlanV2,
        elapsedMs: Float,
        effect: EffectCallV2,
        caretColor: Color,
    ) {
        val timings = plan.pieceTimings
        val isExit = effect.kind == EffectBuildKind.Exit
        val showCaret = if (effect.args.values["caret"] != null) {
            effect.args.boolean("caret", true)
        } else {
            effect.args.boolean("showCaret", true)
        }

        var lastVisiblePiece: AdmittedPiece? = null
        var lastEventMs = Float.NEGATIVE_INFINITY
        var visibleCount = 0

        for (i in 0 until timings.size) {
            val timing = timings[i]
            val piece = timing.piece
            // Trigger at slot endMs so Enter begins empty at t=0 and completes exactly at
            // track end (last slot endMs), and Exit begins full at t=0 and clears exactly at track end.
            val triggerMs = timing.endMs.toFloat()
            val visible = if (!isExit) elapsedMs >= triggerMs else elapsedMs < triggerMs
            if (visible) {
                drawPieceFastTranslated(piece.layout, piece.originX, piece.originY)
                lastVisiblePiece = piece
                visibleCount++
            }
            if (elapsedMs >= triggerMs && triggerMs > lastEventMs) {
                lastEventMs = triggerMs
            }
        }

        // Draw solid/blinking typing caret while build is in progress.
        val totalMs = plan.totalDurationMs.toFloat()
        val caretActive = showCaret && elapsedMs >= 0f && (
            (!isExit && elapsedMs < totalMs) ||
                (isExit && elapsedMs < totalMs && visibleCount > 0)
            )
        if (caretActive) {
            val sinceKeystrokeMs = if (lastEventMs.isFinite()) elapsedMs - lastEventMs else elapsedMs
            val blinkVisible = sinceKeystrokeMs < 240f || (((sinceKeystrokeMs / 530f).toInt()) % 2 == 0)
            if (blinkVisible) {
                val whole = plan.partition.wholeLayout
                val line = lastVisiblePiece?.line ?: 0
                val caretX = if (lastVisiblePiece != null) {
                    lastVisiblePiece.originX + lastVisiblePiece.layout.size.width.toFloat() + 1f
                } else {
                    if (whole.lineCount > 0) whole.getLineLeft(0) else 0f
                }
                val caretTop = if (line < whole.lineCount) whole.getLineTop(line) else 0f
                val caretBottom = if (line < whole.lineCount) whole.getLineBottom(line) else 24f * density
                val caretWidth = (2.5f * density).coerceAtLeast(2f)
                val resolvedCaretColor = when {
                    caretColor != Color.Unspecified -> caretColor
                    effect.args.values["caretColor"] != null -> effect.args.color("caretColor", Color.White)
                    plan.partition.effectiveStyle.color != Color.Unspecified -> plan.partition.effectiveStyle.color
                    else -> Color.White
                }
                drawRect(
                    color = resolvedCaretColor,
                    topLeft = Offset(caretX, caretTop + (caretBottom - caretTop) * 0.08f),
                    size = Size(caretWidth, (caretBottom - caretTop) * 0.84f),
                )
            }
        }
    }

    private fun DrawScope.drawWholeParagraphCrossFade(
        fromLayout: TextLayoutResult,
        toLayout: TextLayoutResult,
        t: Float,
    ) {
        if (t <= 0f) {
            drawPieceFastTranslated(fromLayout, 0f, 0f)
            return
        }
        if (t >= 1f) {
            drawPieceFastTranslated(toLayout, 0f, 0f)
            return
        }
        drawAdditiveCrossFadeLayer(
            bounds = null,
            fromAlpha = 1f - t,
            toAlpha = t,
            drawFrom = { drawPieceFastTranslated(fromLayout, 0f, 0f) },
            drawTo = { drawPieceFastTranslated(toLayout, 0f, 0f) },
        )
    }

    private fun DrawScope.drawPieceFastTranslated(
        layout: TextLayoutResult,
        x: Float,
        y: Float,
    ) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(x, y)
            TextPainter.paint(canvas, layout)
            native.restore()
        }
    }

    private fun DrawScope.drawLayoutAlphaOnly(
        layout: TextLayoutResult,
        originX: Float,
        originY: Float,
        alpha: Float,
        verifiedInkBounds: SkiaRect?,
    ) {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0.0005f) return
        if (a >= 0.999f) {
            drawPieceFastTranslated(layout, originX, originY)
            return
        }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            try {
                native.translate(originX, originY)
                val paint = reusableLayerPaint.get()
                paint.reset()
                setLayerAlphaZeroAlloc(paint, a)
                // Empirically verified ink bounds (or null unbounded when unverified): never clips.
                native.saveLayer(verifiedInkBounds, paint)
                try {
                    TextPainter.paint(canvas, layout)
                } finally {
                    native.restore()
                }
            } finally {
                native.restore()
            }
        }
    }

    private fun DrawScope.drawScaledLayoutOnBaseline(
        layout: TextLayoutResult,
        vectorRuns: List<VectorGlyphRun>?,
        x: Float,
        baselineY: Float,
        scaleX: Float,
        scaleY: Float,
        largePaintedBaselinePx: Float,
    ) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(x, baselineY)
            native.scale(scaleX, scaleY)
            if (vectorRuns != null) {
                val paint = reusableVectorPaint.get()
                paint.reset()
                paint.isAntiAlias = true
                for (ri in 0 until vectorRuns.size) {
                    val run = vectorRuns[ri]
                    val bgRect = run.backgroundRect
                    if (bgRect != null) {
                        paint.color = run.backgroundArgbColor
                        native.drawRect(bgRect, paint)
                    }
                    paint.color = run.argbColor
                    val glyphs = run.glyphs
                    for (gi in 0 until glyphs.size) {
                        val g = glyphs[gi]
                        native.save()
                        native.translate(g.x, g.y)
                        native.drawPath(g.path, paint)
                        native.restore()
                    }
                    run.decorationPath?.let { native.drawPath(it, paint) }
                }
            } else {
                native.translate(0f, -largePaintedBaselinePx)
                TextPainter.paint(canvas, layout)
            }
            native.restore()
        }
    }

    private fun DrawScope.drawPieceWithLayerState(
        piece: AdmittedPiece,
        state: LayerState,
    ) {
        drawLayoutWithLayerState(
            layout = piece.layout,
            originX = piece.originX,
            originY = piece.originY,
            state = state,
            verifiedInkBounds = piece.verifiedInkBounds,
        )
    }

    private fun DrawScope.drawLayoutWithLayerState(
        layout: TextLayoutResult,
        originX: Float,
        originY: Float,
        state: LayerState,
        verifiedInkBounds: SkiaRect? = null,
        paintOverride: ((org.jetbrains.skia.Canvas) -> Unit)? = null,
    ) {
        val alpha = state.alpha.coerceIn(0f, 1f)
        if (alpha <= 0.0005f) return

        if (state.isIdentity) {
            drawPieceFastTranslated(layout, originX, originY)
            return
        }

        val pieceW = layout.size.width.toFloat().coerceAtLeast(1f)
        val pieceH = layout.size.height.toFloat().coerceAtLeast(1f)
        val pivotX = pieceW * state.transformOrigin.pivotFractionX
        val pivotY = pieceH * state.transformOrigin.pivotFractionY

        val needsLayer = alpha < 0.999f || state.blurRadiusPx > 0.05f || state.colorMatrix != null
        val hasTransform = state.translationX != 0f ||
            state.translationY != 0f ||
            state.scaleX != 1f ||
            state.scaleY != 1f ||
            state.rotationZ != 0f

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            try {
                val restClip = state.clip
                if (restClip is EffectClip.RestBounds) {
                    // Fixed at the piece's resting box, so the piece slides in from behind its edge.
                    native.clipRect(
                        SkiaRect.makeLTRB(
                            originX - restClip.padLeft,
                            originY - restClip.padTop,
                            originX + pieceW + restClip.padRight,
                            originY + pieceH + restClip.padBottom,
                        ),
                        true,
                    )
                }
                native.translate(originX + state.translationX, originY + state.translationY)
                if (hasTransform && (state.scaleX != 1f || state.scaleY != 1f || state.rotationZ != 0f)) {
                    native.translate(pivotX, pivotY)
                    if (state.rotationZ != 0f) native.rotate(state.rotationZ)
                    if (state.scaleX != 1f || state.scaleY != 1f) native.scale(state.scaleX, state.scaleY)
                    native.translate(-pivotX, -pivotY)
                }

                when (val clip = state.clip) {
                    EffectClip.None -> Unit
                    is EffectClip.Wipe -> {
                        val p = LayerEffects.buildWipeClipPath(Size(pieceW, pieceH), clip)
                        native.clipPath(p.asSkiaPath(), antiAlias = true)
                    }
                    is EffectClip.Iris -> {
                        val p = LayerEffects.buildIrisClipPath(Size(pieceW, pieceH), clip)
                        native.clipPath(p.asSkiaPath(), antiAlias = true)
                    }
                    is EffectClip.RestBounds -> Unit
                }

                if (!needsLayer) {
                    if (paintOverride != null) paintOverride(native) else TextPainter.paint(canvas, layout)
                } else {
                    val paint = reusableLayerPaint.get()
                    paint.reset()
                    setLayerAlphaZeroAlloc(paint, alpha)
                    val imageFilter = buildPieceImageFilter(state)
                    paint.imageFilter = imageFilter
                    val nonTranslationTransform = state.scaleX != 1f || state.scaleY != 1f || state.rotationZ != 0f
                    val layerBounds = if (state.blurRadiusPx > 0.05f && nonTranslationTransform) {
                        null
                    } else {
                        PieceInkBounds.expandForBlur(verifiedInkBounds, state.blurRadiusPx)
                    }
                    try {
                        // Empirically verified bounds (expanded for blur) or null unbounded: never clips.
                        native.saveLayer(layerBounds, paint)
                        try {
                            if (paintOverride != null) paintOverride(native) else TextPainter.paint(canvas, layout)
                        } finally {
                            native.restore()
                        }
                    } finally {
                        paint.imageFilter = null
                        imageFilter?.close()
                    }
                }
            } finally {
                native.restore()
            }
        }
    }

    /**
     * Unclipped isolated additive cross-fade layer (`BlendMode.PLUS`) ensuring shared glyph ink
     * suffers 0.00% luminance dip and zero clipping during color/weight/mixed cross-fades.
     *
     * Uses precomputed empirically verified bounds for Condition-2-admitted pieces/groups and
     * `null` (unbounded) for native-paragraph fallback.
     */
    private inline fun DrawScope.drawAdditiveCrossFadeLayer(
        bounds: SkiaRect?,
        fromAlpha: Float,
        toAlpha: Float,
        translateX: Float = 0f,
        translateY: Float = 0f,
        drawFrom: DrawScope.() -> Unit,
        drawTo: DrawScope.() -> Unit,
    ) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val hasTranslate = translateX != 0f || translateY != 0f
            if (hasTranslate) {
                native.save()
                native.translate(translateX, translateY)
            }
            try {
                val complementary = abs((fromAlpha + toAlpha) - 1f) < 1e-4f
                val toByte = (toAlpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val fromByte = if (complementary) {
                    255 - toByte
                } else {
                    (fromAlpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
                native.saveLayer(bounds, null)
                try {
                    if (fromByte > 0) {
                        val pFrom = reusableLayerPaint.get()
                        pFrom.reset()
                        pFrom.color = fromByte shl 24
                        native.saveLayer(bounds, pFrom)
                        try {
                            drawFrom()
                        } finally {
                            native.restore()
                        }
                    }
                    if (toByte > 0) {
                        val pTo = reusablePlusPaint.get()
                        pTo.reset()
                        pTo.color = toByte shl 24
                        pTo.blendMode = SkiaBlendMode.PLUS
                        native.saveLayer(bounds, pTo)
                        try {
                            drawTo()
                        } finally {
                            native.restore()
                        }
                    }
                } finally {
                    native.restore()
                }
            } finally {
                if (hasTranslate) native.restore()
            }
        }
    }

    private fun buildPieceImageFilter(state: LayerState): ImageFilter? {
        val blur = if (state.blurRadiusPx > 0.05f) {
            val sigma = state.blurRadiusPx * 0.57735f
            ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL)
        } else {
            null
        }
        val cm = state.colorMatrix?.takeIf { it.size == 20 }?.let { matrix ->
            val cf = ColorFilter.makeMatrix(ColorMatrix(matrix))
            try {
                ImageFilter.makeColorFilter(cf, blur, null)
            } finally {
                cf.close()
                blur?.close()
            }
        }
        return cm ?: blur
    }

    /**
     * Sets layer opacity via [SkiaPaint.color] (`_nSetColor`) instead of `setAlphaf` because
     * Skiko's `Paint.setAlphaf` allocates two `Color4f` wrappers (64 B) per call.
     */
    private fun setLayerAlphaZeroAlloc(paint: SkiaPaint, alpha: Float) {
        val aByte = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        paint.color = aByte shl 24
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
