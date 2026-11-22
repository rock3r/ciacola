package dev.sebastiano.ciacola.replications.shaders

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Registry helper exposing all 14 *Shaders Quickie 2025* live SkSL sources and a standalone
 * `@Composable` dispatcher so deck integrators can bind every `shaders.*` specimen ref in
 * `deckSpecimens { ... }` without circular dependencies.
 */
object ShadersSpecimensCatalog {
    /**
     * All 14 SkSL sources keyed by canonical specimen ID, covering direct full-bleed shaders and
     * `ImageFilter.makeRuntimeShader` `RenderEffect` pipelines.
     */
    val allSkslSources: Map<String, String> = linkedMapOf(
        ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE to ShadersShowcaseSkslSources.TrippyKaleidoscope,
        ShadersSpecimenIds.JOURNEY_DUNES to ShadersShowcaseSkslSources.JourneyDunes,
        ShadersSpecimenIds.HAZE_SCROLL_HEADER to HazeRenderEffectSksl.FrostedGlass,
        ShadersSpecimenIds.LIQUID_GLASS_LENS to LiquidGlassRenderEffectSksl.RefractivePill,
        ShadersSpecimenIds.RAINDROPS_WINDOW to ShadersShowcaseSkslSources.RaindropsWindow,
        ShadersSpecimenIds.COLOR_EVOLUTION to ShadersTeachingSkslSources.ColorEvolution,
        ShadersSpecimenIds.LIVE_UV_STATIC to ShadersTeachingSkslSources.LiveUvStatic,
        ShadersSpecimenIds.LIVE_UV_ANIMATED to ShadersTeachingSkslSources.LiveUvAnimated,
        ShadersSpecimenIds.SDF_GOAL_CIRCLE to ShadersTeachingSkslSources.SdfGoalCircle,
        ShadersSpecimenIds.SDF_RAW_DISTANCE to ShadersTeachingSkslSources.SdfRawDistance,
        ShadersSpecimenIds.SDF_STEP_CIRCLE to ShadersTeachingSkslSources.SdfStepCircle,
        ShadersSpecimenIds.SDF_CSG_SUBTRACT to ShadersTeachingSkslSources.SdfCsgSubtract,
        ShadersSpecimenIds.SDF_CSG_UNION_SUBTRACT to ShadersTeachingSkslSources.SdfCsgUnionSubtract,
        ShadersSpecimenIds.PLAID_HYPERSPACE_STARFIELD to ShadersShowcaseSkslSources.PlaidHyperspaceStarfield,
    )

    /** Returns the canonical deterministic loop period in milliseconds, or `null` for static specimens. */
    fun periodMsOrNull(ref: String): Long? = ShadersSpecimenIds.animatedPeriodsMs[ref]
}

/**
 * Standalone dispatcher rendering any of the 14 `shaders.*` specimen refs emitted by
 * `kpf_to_deck_json.py`, forwarding typed float parameters from [params] with talk-accurate
 * defaults. Throws [IllegalArgumentException] if [ref] is not a registered `shaders.*` ID.
 */
@Composable
fun RenderShadersSpecimen(
    ref: String,
    loopMillis: Long = 0L,
    params: Map<String, Float> = emptyMap(),
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (ref) {
        ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE -> TrippyKaleidoscopeSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
            folds = params["folds"] ?: 6f,
        )

        ShadersSpecimenIds.JOURNEY_DUNES -> JourneyDunesSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
            shimmer = params["shimmer"] ?: 1f,
        )

        ShadersSpecimenIds.HAZE_SCROLL_HEADER -> HazeScrollHeaderSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.LIQUID_GLASS_LENS -> LiquidGlassLensSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.RAINDROPS_WINDOW -> RaindropsWindowSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
            intensity = params["intensity"] ?: 1f,
        )

        ShadersSpecimenIds.COLOR_EVOLUTION -> ColorEvolutionSpecimen(
            loopMillis = loopMillis,
            r = params["r"] ?: 0f,
            g = params["g"] ?: 0f,
            b = params["b"] ?: 0f,
            a = params["a"] ?: 1f,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.LIVE_UV_STATIC -> LiveUvStaticSpecimen(
            loopMillis = loopMillis,
            blue = params["blue"] ?: 1f,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.LIVE_UV_ANIMATED -> LiveUvAnimatedSpecimen(
            loopMillis = loopMillis,
            speed = params["speed"] ?: 1f,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.SDF_GOAL_CIRCLE -> SdfGoalCircleSpecimen(
            loopMillis = loopMillis,
            radius = params["radius"] ?: 0.5f,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.SDF_RAW_DISTANCE -> SdfRawDistanceSpecimen(
            loopMillis = loopMillis,
            radius = params["radius"] ?: 0.5f,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.SDF_STEP_CIRCLE -> SdfStepCircleSpecimen(
            loopMillis = loopMillis,
            radius = params["radius"] ?: 0.5f,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.SDF_CSG_SUBTRACT -> SdfCsgSubtractSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.SDF_CSG_UNION_SUBTRACT -> SdfCsgUnionSubtractSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        ShadersSpecimenIds.PLAID_HYPERSPACE_STARFIELD -> PlaidHyperspaceStarfieldSpecimen(
            loopMillis = loopMillis,
            uPlaidMix = params["uPlaidMix"] ?: 0.65f,
            isInteractive = isInteractive,
            modifier = modifier,
        )

        else -> throw IllegalArgumentException("Unknown shaders specimen ref: '$ref'")
    }
}
