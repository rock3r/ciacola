package dev.sebastiano.ciacola.effects.v2

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import dev.bnorm.storyboard.Storyboard

/**
 * All 9 whole-slide transitions, parameterized on canonical transition progress
 * `p ∈ [0, 1]` from slide `A` (`p = 0`) to slide `B` (`p = 1`).
 */
@Immutable
sealed interface SlideTransitionV2 {
    val canonicalName: String

    data object None : SlideTransitionV2 {
        override val canonicalName: String = "none"
    }

    @Immutable
    data class Dissolve(
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.EASE_IN_OUT),
    ) : SlideTransitionV2 {
        override val canonicalName: String = "dissolve"
    }

    @Immutable
    data class Push(
        val direction: EffectDirection8 = EffectDirection8.RightToLeft,
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.PUSH),
    ) : SlideTransitionV2 {
        override val canonicalName: String = "push"
    }

    @Immutable
    data class Slide(
        val direction: EffectDirection8 = EffectDirection8.RightToLeft,
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.SLIDE),
    ) : SlideTransitionV2 {
        override val canonicalName: String = "slide"
    }

    @Immutable
    data class Reveal(
        val direction: EffectDirection8 = EffectDirection8.RightToLeft,
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.REVEAL),
    ) : SlideTransitionV2 {
        override val canonicalName: String = "reveal"
    }

    @Immutable
    data class Wipe(
        val direction: EffectDirection8 = EffectDirection8.LeftToRight,
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.EASE_IN_OUT),
    ) : SlideTransitionV2 {
        override val canonicalName: String = "wipe"
    }

    @Immutable
    data class Iris(
        val direction: IrisDirection = IrisDirection.In,
        val center: Offset = Offset(0.5f, 0.5f),
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.EASE_IN_OUT),
    ) : SlideTransitionV2 {
        override val canonicalName: String = "iris"
    }

    /**
     * Fades `A` out to a solid color veil at `p = 0.5`, then fades `B` in from that solid color.
     *
     * Guarded to enforce opaque `alpha = 1f` on the veil color regardless of what was supplied so
     * transparent colors can never break the midpoint occlusion guarantee.
     */
    @Immutable
    data class FadeThroughColor(
        val color: Color = Color.Black,
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.EASE_IN_OUT),
    ) : SlideTransitionV2 {
        val opaqueColor: Color = color.copy(alpha = 1f)
        override val canonicalName: String = "fadeThroughColor"
    }

    @Immutable
    data class MagicMove(
        val curve: CurveV2 = CurveV2.Preset(KeynoteCurves.EASE_IN_OUT),
    ) : SlideTransitionV2 {
        override val canonicalName: String = "magicMove"
    }
}

/**
 * Identifies which slide layer is drawn when ordering layers in [SlideTransitionComposition].
 */
enum class SlideSlot {
    OutgoingA,
    IncomingB,
}

/**
 * Exact compositor state for one slide (`A` or `B`) at canonical progress `p`.
 */
@Immutable
data class SlideLayerComposition(
    val slot: SlideSlot,
    val zIndex: Float,
    val visible: Boolean,
    val layer: LayerState,
)

/**
 * Optional full-stage solid color veil drawn above both slide layers (used by `FadeThroughColor`).
 */
@Immutable
data class SlideColorVeil(
    val color: Color,
    val alpha: Float,
)

/**
 * Optional Magic Move background & travelling overlay state (non-null only for [SlideTransitionV2.MagicMove]).
 */
@Immutable
data class MagicMoveHostState(
    val backgroundAAlpha: Float,
    val backgroundBAlpha: Float,
    val overlayProgress: Float,
    val overlayZIndex: Float = 2f,
)

/**
 * Complete canonical compositor plan for `(A, B, p)`.
 *
 * Depends strictly on `(transition, canonicalProgress, stageSize, reducedMotion)`, guaranteeing
 * pixel-identical results for forward travel, backward travel, mid-flight reversal, and cold seek
 * at equal canonical progress `p`.
 */
@Immutable
data class SlideTransitionComposition(
    val canonicalProgress: Float,
    val slideA: SlideLayerComposition,
    val slideB: SlideLayerComposition,
    val veil: SlideColorVeil? = null,
    val magicMove: MagicMoveHostState? = null,
) {
    /** Slide layers ordered strictly bottom-to-top by `zIndex`. */
    val drawOrder: List<SlideSlot>
        get() = if (slideA.zIndex <= slideB.zIndex) {
            listOf(SlideSlot.OutgoingA, SlideSlot.IncomingB)
        } else {
            listOf(SlideSlot.IncomingB, SlideSlot.OutgoingA)
        }

    /** `true` iff slide `A` is stacked strictly above slide `B` (e.g. `Reveal`, `Iris(Out)`). */
    val outgoingAboveIncoming: Boolean
        get() = slideA.zIndex > slideB.zIndex
}

/**
 * Returns whether [transition] requires outgoing slide `A` to be stacked above incoming slide `B`.
 */
fun SlideTransitionV2.stacksOutgoingAboveIncoming(): Boolean = when (this) {
    is SlideTransitionV2.Reveal -> true
    is SlideTransitionV2.Iris -> direction == IrisDirection.Out
    else -> false
}

/**
 * Produces a `Storyboard.sceneZIndex` lambda implementing the exact stacking rule of [transition]
 * between [fromSceneIndex] (`A`) and [toSceneIndex] (`B`) regardless of navigation direction.
 */
fun sceneZIndexForTransition(
    transition: SlideTransitionV2,
    fromSceneIndex: Int,
    toSceneIndex: Int,
    reducedMotion: Boolean = false,
): (Storyboard.Index) -> Float {
    val outgoingOnTop = !reducedMotion && transition.stacksOutgoingAboveIncoming()
    return { index ->
        when (index.sceneIndex) {
            fromSceneIndex -> if (outgoingOnTop) 1f else 0f
            toSceneIndex -> if (outgoingOnTop) 0f else 1f
            else -> if (index.sceneIndex < toSceneIndex) 0f else 1f
        }
    }
}

/**
 * Pure canonical compositor evaluating all 9 [SlideTransitionV2] modes on `(A, B, p)`.
 */
fun composeSlideTransition(
    transition: SlideTransitionV2,
    canonicalProgress: Float,
    stageSize: Size = Size(1920f, 1080f),
    reducedMotion: Boolean = false,
): SlideTransitionComposition {
    val p = canonicalProgress.coerceIn(0f, 1f)
    if (reducedMotion && transition !is SlideTransitionV2.None && transition !is SlideTransitionV2.FadeThroughColor) {
        return composeDissolve(p, CurveV2.EaseInOut)
    }

    return when (transition) {
        SlideTransitionV2.None -> {
            val showB = p >= 0.5f
            SlideTransitionComposition(
                canonicalProgress = p,
                slideA = SlideLayerComposition(SlideSlot.OutgoingA, zIndex = 0f, visible = !showB, layer = if (!showB) LayerState.Identity else LayerState.Hidden),
                slideB = SlideLayerComposition(SlideSlot.IncomingB, zIndex = 1f, visible = showB, layer = if (showB) LayerState.Identity else LayerState.Hidden),
            )
        }

        is SlideTransitionV2.Dissolve -> composeDissolve(p, transition.curve)

        is SlideTransitionV2.Push -> {
            val e = transition.curve.sample(p)
            val shift = fullStageShift(transition.direction, stageSize)
            SlideTransitionComposition(
                canonicalProgress = p,
                slideA = SlideLayerComposition(
                    slot = SlideSlot.OutgoingA,
                    zIndex = 0f,
                    visible = p < 1f,
                    layer = LayerState(
                        alpha = if (p < 1f) 1f else 0f,
                        translationX = shift.x * e,
                        translationY = shift.y * e,
                    ),
                ),
                slideB = SlideLayerComposition(
                    slot = SlideSlot.IncomingB,
                    zIndex = 1f,
                    visible = p > 0f,
                    layer = LayerState(
                        alpha = if (p > 0f) 1f else 0f,
                        translationX = -shift.x * (1f - e),
                        translationY = -shift.y * (1f - e),
                    ),
                ),
            )
        }

        is SlideTransitionV2.Slide -> {
            val e = transition.curve.sample(p)
            val shift = fullStageShift(transition.direction, stageSize)
            SlideTransitionComposition(
                canonicalProgress = p,
                slideA = SlideLayerComposition(
                    slot = SlideSlot.OutgoingA,
                    zIndex = 0f,
                    visible = p < 1f,
                    layer = if (p < 1f) LayerState.Identity else LayerState.Hidden,
                ),
                slideB = SlideLayerComposition(
                    slot = SlideSlot.IncomingB,
                    zIndex = 1f,
                    visible = p > 0f,
                    layer = LayerState(
                        alpha = if (p > 0f) 1f else 0f,
                        translationX = -shift.x * (1f - e),
                        translationY = -shift.y * (1f - e),
                    ),
                ),
            )
        }

        is SlideTransitionV2.Reveal -> {
            val e = transition.curve.sample(p)
            val shift = fullStageShift(transition.direction, stageSize)
            // B sits statically below A; A slides off in `direction` revealing B underneath.
            SlideTransitionComposition(
                canonicalProgress = p,
                slideA = SlideLayerComposition(
                    slot = SlideSlot.OutgoingA,
                    zIndex = 1f,
                    visible = p < 1f,
                    layer = LayerState(
                        alpha = if (p < 1f) 1f else 0f,
                        translationX = shift.x * e,
                        translationY = shift.y * e,
                    ),
                ),
                slideB = SlideLayerComposition(
                    slot = SlideSlot.IncomingB,
                    zIndex = 0f,
                    visible = p > 0f,
                    layer = if (p > 0f) LayerState.Identity else LayerState.Hidden,
                ),
            )
        }

        is SlideTransitionV2.Wipe -> {
            val e = transition.curve.sample(p).coerceIn(0f, 1f)
            SlideTransitionComposition(
                canonicalProgress = p,
                slideA = SlideLayerComposition(
                    slot = SlideSlot.OutgoingA,
                    zIndex = 0f,
                    visible = p < 1f,
                    layer = if (p < 1f) LayerState.Identity else LayerState.Hidden,
                ),
                slideB = SlideLayerComposition(
                    slot = SlideSlot.IncomingB,
                    zIndex = 1f,
                    visible = p > 0f,
                    layer = when {
                        p <= 0f -> LayerState.Hidden
                        p >= 1f -> LayerState.Identity
                        else -> LayerState(
                            alpha = 1f,
                            clip = EffectClip.Wipe(direction = transition.direction, revealFraction = e, isExit = false),
                        )
                    },
                ),
            )
        }

        is SlideTransitionV2.Iris -> {
            val e = transition.curve.sample(p).coerceIn(0f, 1f)
            when (transition.direction) {
                IrisDirection.In -> SlideTransitionComposition(
                    canonicalProgress = p,
                    slideA = SlideLayerComposition(
                        slot = SlideSlot.OutgoingA,
                        zIndex = 0f,
                        visible = p < 1f,
                        layer = if (p < 1f) LayerState.Identity else LayerState.Hidden,
                    ),
                    slideB = SlideLayerComposition(
                        slot = SlideSlot.IncomingB,
                        zIndex = 1f,
                        visible = p > 0f,
                        layer = when {
                            p <= 0f -> LayerState.Hidden
                            p >= 1f -> LayerState.Identity
                            else -> LayerState(
                                alpha = 1f,
                                clip = EffectClip.Iris(center = transition.center, radiusFraction = e, keepInside = true),
                            )
                        },
                    ),
                )

                IrisDirection.Out -> SlideTransitionComposition(
                    canonicalProgress = p,
                    // Iris Out: B sits below A; A shrinks inside a closing circular iris revealing B.
                    slideA = SlideLayerComposition(
                        slot = SlideSlot.OutgoingA,
                        zIndex = 1f,
                        visible = p < 1f,
                        layer = when {
                            p <= 0f -> LayerState.Identity
                            p >= 1f -> LayerState.Hidden
                            else -> LayerState(
                                alpha = 1f,
                                clip = EffectClip.Iris(center = transition.center, radiusFraction = 1f - e, keepInside = true),
                            )
                        },
                    ),
                    slideB = SlideLayerComposition(
                        slot = SlideSlot.IncomingB,
                        zIndex = 0f,
                        visible = p > 0f,
                        layer = if (p > 0f) LayerState.Identity else LayerState.Hidden,
                    ),
                )
            }
        }

        is SlideTransitionV2.FadeThroughColor -> {
            val veilColor = transition.opaqueColor
            val (showA, showB, veilAlpha) = when {
                p < 0.5f -> {
                    val halfE = transition.curve.sample((p * 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    Triple(true, false, halfE)
                }
                p == 0.5f -> Triple(false, false, 1f)
                else -> {
                    val halfE = transition.curve.sample(((p - 0.5f) * 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    Triple(false, true, 1f - halfE)
                }
            }
            SlideTransitionComposition(
                canonicalProgress = p,
                slideA = SlideLayerComposition(
                    slot = SlideSlot.OutgoingA,
                    zIndex = 0f,
                    visible = showA,
                    layer = if (showA) LayerState.Identity else LayerState.Hidden,
                ),
                slideB = SlideLayerComposition(
                    slot = SlideSlot.IncomingB,
                    zIndex = 1f,
                    visible = showB,
                    layer = if (showB) LayerState.Identity else LayerState.Hidden,
                ),
                veil = if (veilAlpha > 0f) SlideColorVeil(color = veilColor, alpha = veilAlpha) else null,
            )
        }

        is SlideTransitionV2.MagicMove -> {
            val e = transition.curve.sample(p).coerceIn(0f, 1f)
            val exitAlpha = if (p <= 0f) {
                1f
            } else if (p >= 0.45f) {
                0f
            } else {
                1f - transition.curve.sample((p / 0.45f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            }
            val enterAlpha = if (p <= 0.35f) {
                0f
            } else if (p >= 1f) {
                1f
            } else {
                transition.curve.sample(((p - 0.35f) / 0.65f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            }
            SlideTransitionComposition(
                canonicalProgress = p,
                slideA = SlideLayerComposition(
                    slot = SlideSlot.OutgoingA,
                    zIndex = 0f,
                    visible = exitAlpha > 0f,
                    layer = LayerState(alpha = exitAlpha),
                ),
                slideB = SlideLayerComposition(
                    slot = SlideSlot.IncomingB,
                    zIndex = 1f,
                    visible = enterAlpha > 0f,
                    layer = LayerState(alpha = enterAlpha),
                ),
                magicMove = MagicMoveHostState(
                    backgroundAAlpha = if (p < 1f) 1f else 0f,
                    backgroundBAlpha = e,
                    overlayProgress = e,
                ),
            )
        }
    }
}

private fun composeDissolve(p: Float, curve: CurveV2): SlideTransitionComposition {
    val e = curve.sample(p).coerceIn(0f, 1f)
    return SlideTransitionComposition(
        canonicalProgress = p,
        slideA = SlideLayerComposition(
            slot = SlideSlot.OutgoingA,
            zIndex = 0f,
            visible = p < 1f,
            // Hold A opaque beneath B while B fades in so identical backgrounds never dip in luminance.
            layer = if (p < 1f) LayerState.Identity else LayerState.Hidden,
        ),
        slideB = SlideLayerComposition(
            slot = SlideSlot.IncomingB,
            zIndex = 1f,
            visible = p > 0f,
            layer = when {
                p <= 0f -> LayerState.Hidden
                p >= 1f -> LayerState.Identity
                else -> LayerState(alpha = e)
            },
        ),
    )
}

private fun fullStageShift(direction: EffectDirection8, stageSize: Size): Offset {
    val signs = direction.axisSigns()
    return Offset(signs.x * stageSize.width, signs.y * stageSize.height)
}

/**
 * Applies a [SlideLayerComposition]'s transform, alpha, and clip (`Wipe`/`Iris`) to a slide box.
 */
fun Modifier.slideLayerTransform(layer: LayerState): Modifier {
    val clampedAlpha = layer.alpha.coerceIn(0f, 1f)
    val layerBlock: GraphicsLayerScope.() -> Unit = {
        alpha = clampedAlpha
        translationX = layer.translationX
        translationY = layer.translationY
        scaleX = layer.scaleX
        scaleY = layer.scaleY
        transformOrigin = layer.transformOrigin
        rotationZ = layer.rotationZ
        compositingStrategy = if (clampedAlpha < 1f || layer.clip != EffectClip.None) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
    }
    val transformed = this.graphicsLayer(layerBlock)
    return when (val clip = layer.clip) {
        EffectClip.None -> transformed
        is EffectClip.Wipe -> transformed.drawWithContent {
            val path = LayerEffects.buildWipeClipPath(size, clip)
            clipPath(path) {
                this@drawWithContent.drawContent()
            }
        }
        is EffectClip.Iris -> transformed.drawWithContent {
            val path = LayerEffects.buildIrisClipPath(size, clip)
            clipPath(path) {
                this@drawWithContent.drawContent()
            }
        }
        is EffectClip.RestBounds -> this.restBoundsClip(clip).graphicsLayer(layerBlock)
    }
}

/** Clips to the untransformed layout box grown by [clip]'s padding (applied outside the layer). */
fun Modifier.restBoundsClip(clip: EffectClip.RestBounds): Modifier = this.drawWithContent {
    clipRect(
        left = -clip.padLeft,
        top = -clip.padTop,
        right = size.width + clip.padRight,
        bottom = size.height + clip.padBottom,
    ) {
        this@drawWithContent.drawContent()
    }
}
