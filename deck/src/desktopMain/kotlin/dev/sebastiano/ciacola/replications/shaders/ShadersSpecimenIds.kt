package dev.sebastiano.ciacola.replications.shaders

/**
 * Canonical specimen identifiers and deterministic loop periods (`periodMs`) for all 14 live
 * SkSL / Skia `RenderEffect` specimens in *Shaders Quickie 2025*.
 */
object ShadersSpecimenIds {
    const val TRIPPY_KALEIDOSCOPE: String = "shaders.trippyKaleidoscope"
    const val JOURNEY_DUNES: String = "shaders.journeyDunes"
    const val HAZE_SCROLL_HEADER: String = "shaders.hazeScrollHeader"
    const val LIQUID_GLASS_LENS: String = "shaders.liquidGlassLens"
    const val RAINDROPS_WINDOW: String = "shaders.raindropsWindow"
    const val COLOR_EVOLUTION: String = "shaders.colorEvolution"
    const val LIVE_UV_STATIC: String = "shaders.liveUvStatic"
    const val LIVE_UV_ANIMATED: String = "shaders.liveUvAnimated"
    const val SDF_GOAL_CIRCLE: String = "shaders.sdfGoalCircle"
    const val SDF_RAW_DISTANCE: String = "shaders.sdfRawDistance"
    const val SDF_STEP_CIRCLE: String = "shaders.sdfStepCircle"
    const val SDF_CSG_SUBTRACT: String = "shaders.sdfCsgSubtract"
    const val SDF_CSG_UNION_SUBTRACT: String = "shaders.sdfCsgUnionSubtract"
    const val PLAID_HYPERSPACE_STARFIELD: String = "shaders.plaidHyperspaceStarfield"

    const val TRIPPY_KALEIDOSCOPE_PERIOD_MS: Long = 12_000L
    const val JOURNEY_DUNES_PERIOD_MS: Long = 14_000L
    const val HAZE_SCROLL_HEADER_PERIOD_MS: Long = 8_000L
    const val LIQUID_GLASS_LENS_PERIOD_MS: Long = 8_000L
    const val RAINDROPS_WINDOW_PERIOD_MS: Long = 10_000L
    const val LIVE_UV_ANIMATED_PERIOD_MS: Long = 6_283L
    const val PLAID_HYPERSPACE_STARFIELD_PERIOD_MS: Long = 16_000L

    /** All 14 specimen identifiers in slide presentation order. */
    val allIds: List<String> = listOf(
        TRIPPY_KALEIDOSCOPE,
        JOURNEY_DUNES,
        HAZE_SCROLL_HEADER,
        LIQUID_GLASS_LENS,
        RAINDROPS_WINDOW,
        COLOR_EVOLUTION,
        LIVE_UV_STATIC,
        LIVE_UV_ANIMATED,
        SDF_GOAL_CIRCLE,
        SDF_RAW_DISTANCE,
        SDF_STEP_CIRCLE,
        SDF_CSG_SUBTRACT,
        SDF_CSG_UNION_SUBTRACT,
        PLAID_HYPERSPACE_STARFIELD,
    )

    /** Canonical loop periods (`ms`) for all 7 animated specimens. */
    val animatedPeriodsMs: Map<String, Long> = linkedMapOf(
        TRIPPY_KALEIDOSCOPE to TRIPPY_KALEIDOSCOPE_PERIOD_MS,
        JOURNEY_DUNES to JOURNEY_DUNES_PERIOD_MS,
        HAZE_SCROLL_HEADER to HAZE_SCROLL_HEADER_PERIOD_MS,
        LIQUID_GLASS_LENS to LIQUID_GLASS_LENS_PERIOD_MS,
        RAINDROPS_WINDOW to RAINDROPS_WINDOW_PERIOD_MS,
        LIVE_UV_ANIMATED to LIVE_UV_ANIMATED_PERIOD_MS,
        PLAID_HYPERSPACE_STARFIELD to PLAID_HYPERSPACE_STARFIELD_PERIOD_MS,
    )
}
