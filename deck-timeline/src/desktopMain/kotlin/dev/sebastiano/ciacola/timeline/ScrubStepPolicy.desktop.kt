package dev.sebastiano.ciacola.timeline

import dev.bnorm.storyboard.easel.Animatic

/**
 * Snapshot of a scrub state endpoint passed into [scrubStepPolicy].
 */
data class ScrubStepEndpoint(
    val stateIndex: Int,
    val toStateIndex: Int = stateIndex,
    val fraction: Float = 0f,
    val sceneIndex: Int = 0,
    val isBoundaryStart: Boolean = false,
    val isBoundaryEnd: Boolean = false,
) {
    companion object {
        fun fromActiveAnimatic(animatic: Animatic, lastFraction: Float = animatic.activeFraction): ScrubStepEndpoint {
            val desc = animatic.stateDescriptors.getOrNull(animatic.activeFromStateIndex)
            return ScrubStepEndpoint(
                stateIndex = animatic.activeFromStateIndex,
                toStateIndex = animatic.activeToStateIndex,
                fraction = lastFraction,
                sceneIndex = desc?.sceneIndex ?: -1,
                isBoundaryStart = desc?.isBoundaryStart == true,
                isBoundaryEnd = desc?.isBoundaryEnd == true,
            )
        }

        fun fromStateIndex(
            animatic: Animatic,
            stateIndex: Int,
            fraction: Float,
        ): ScrubStepEndpoint {
            val desc = animatic.stateDescriptors.getOrNull(stateIndex)
            val toIdx = if (fraction > 0f && stateIndex < animatic.stateDescriptors.lastIndex) {
                stateIndex + 1
            } else {
                stateIndex
            }
            return ScrubStepEndpoint(
                stateIndex = stateIndex,
                toStateIndex = toIdx,
                fraction = fraction,
                sceneIndex = desc?.sceneIndex ?: -1,
                isBoundaryStart = desc?.isBoundaryStart == true,
                isBoundaryEnd = desc?.isBoundaryEnd == true,
            )
        }
    }
}

/**
 * Outcome of [scrubStepPolicy] governing whether a scrub step seeks in place or rebuilds the scene,
 * and whether the subsequent drag-release settle must perform a clean rebuild.
 */
data class ScrubStepDecision(
    val inPlace: Boolean,
    val sameSegmentInPlace: Boolean,
    val sameSlideDrag: Boolean,
    val crossedIntraSlideBeat: Boolean,
    val reversedWithinSegment: Boolean,
)

/**
 * Pure scrub-step policy shared by [TimelineStudioController.runScrubWorker] and
 * `ScrubPathIndependenceTest`.
 *
 * While dragging:
 * - Staying strictly inside the active segment (`sameSegmentInPlace`) seeks in place.
 * - Stepping across intra-slide beats or back to a beat start within the same non-boundary slide
 *   (`sameSlideDrag`) also seeks in place for 60 fps responsiveness, while flagging
 *   [ScrubStepDecision.crossedIntraSlideBeat] so drag release settles with a clean rebuild.
 * - Crossing slide boundaries or stepping while not dragging (`!dragging`) rebuilds the scene so
 *   frames remain path-independent.
 */
fun scrubStepPolicy(
    prev: ScrubStepEndpoint,
    next: ScrubStepEndpoint,
    dragging: Boolean,
    forceRebuild: Boolean = false,
): ScrubStepDecision {
    val sameSegmentInPlace = scrubStepIsInPlace(
        activeFrom = prev.stateIndex,
        activeTo = prev.toStateIndex,
        stateIndex = next.stateIndex,
        fraction = next.fraction,
    )
    val sameSlideDrag = !sameSegmentInPlace &&
        dragging &&
        prev.sceneIndex >= 0 &&
        prev.sceneIndex == next.sceneIndex &&
        !prev.isBoundaryStart &&
        !prev.isBoundaryEnd &&
        !next.isBoundaryStart &&
        !next.isBoundaryEnd
    val inPlace = !forceRebuild && (sameSegmentInPlace || sameSlideDrag)
    val enteringFromSettledStart = prev.stateIndex == next.stateIndex &&
        prev.toStateIndex == next.stateIndex &&
        next.fraction > 0f
    val crossedIntraSlideBeat = sameSlideDrag && !enteringFromSettledStart
    val reversedWithinSegment = sameSegmentInPlace &&
        prev.stateIndex == next.stateIndex &&
        next.fraction < prev.fraction - 1e-4f
    return ScrubStepDecision(
        inPlace = inPlace,
        sameSegmentInPlace = sameSegmentInPlace,
        sameSlideDrag = sameSlideDrag,
        crossedIntraSlideBeat = crossedIntraSlideBeat,
        reversedWithinSegment = reversedWithinSegment,
    )
}
