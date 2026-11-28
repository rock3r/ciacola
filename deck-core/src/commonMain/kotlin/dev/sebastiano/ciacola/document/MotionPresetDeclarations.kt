package dev.sebastiano.ciacola.document

/**
 * Metadata for the motion presets: typographic build effects built on per-piece motion channels
 * (see [MotionChannelSpec]).
 *
 * Every visual default is a declared argument, and any channel can be overridden further through a
 * build's `channels` map. Lengths are in em. The build-level `curve` remaps each piece's progress
 * before the channels apply their own curves, so presets default to a linear build curve.
 */
object MotionPresetDeclarations {
    const val RISE: String = "rise"
    const val FOCUS_PULL: String = "focusPull"
    const val WAVE: String = "wave"
    const val TRACKING_BREATHE: String = "trackingBreathe"
    const val AXIS_WAVE: String = "axisWave"
    const val AXIS_RISE: String = "axisRise"

    val WAVE_SHAPES: List<String> = listOf("sine", "triangle", "square", "saw", "noise")

    private val RISE_DIRECTIONS: Set<String> = setOf("up", "down", "left", "right")

    val all: List<BuildEffectDeclaration> = listOf(
        BuildEffectDeclaration(
            id = RISE,
            tier = EffectTier.Layer,
            supportsPieceDelivery = true,
            supportedDirections = RISE_DIRECTIONS,
            args = listOf(
                ParamSpec.FloatParam("distance", label = "Distance (em)", defaultValue = 0.9f, range = 0f..5f, step = 0.05f),
                ParamSpec.BooleanParam("reveal", label = "Mask to line", defaultValue = true),
                ParamSpec.FloatParam("revealPadding", label = "Mask padding (em)", defaultValue = 0.12f, range = 0f..2f, step = 0.01f),
                ParamSpec.FloatParam("fadeWindow", label = "Fade-in share", defaultValue = 0.35f, range = 0f..1f, step = 0.05f),
            ),
            defaultDurationMs = 750,
            defaultCurve = Curve.Linear,
        ),
        BuildEffectDeclaration(
            id = FOCUS_PULL,
            tier = EffectTier.Layer,
            supportsPieceDelivery = true,
            args = listOf(
                ParamSpec.FloatParam("radius", label = "Blur radius (em)", defaultValue = 0.3f, range = 0f..3f, step = 0.01f),
                ParamSpec.FloatParam("restOpacity", label = "Out-of-focus opacity", defaultValue = 0.45f, range = 0f..1f, step = 0.05f),
                ParamSpec.FloatParam("fromScale", label = "Start scale", defaultValue = 1.06f, range = 0.2f..3f, step = 0.01f),
                ParamSpec.FloatParam("focusFrom", label = "Focus start (0..1 of width)", defaultValue = 0f, range = -0.5f..1.5f, step = 0.01f),
                ParamSpec.FloatParam("focusTo", label = "Focus end (0..1 of width)", defaultValue = 1f, range = -0.5f..1.5f, step = 0.01f),
                ParamSpec.FloatParam("focusWidth", label = "Depth of field (em)", defaultValue = 1.2f, range = 0.05f..20f, step = 0.05f),
                ParamSpec.BooleanParam("settle", label = "Settle sharp at the end", defaultValue = true),
            ),
            defaultDurationMs = 900,
            defaultCurve = Curve.Linear,
        ),
        BuildEffectDeclaration(
            id = WAVE,
            tier = EffectTier.Layer,
            supportsPieceDelivery = true,
            isEmphasis = true,
            args = listOf(
                ParamSpec.FloatParam("amplitude", label = "Lift (em)", defaultValue = 0.18f, range = -3f..3f, step = 0.01f),
                ParamSpec.FloatParam("rotation", label = "Tilt (deg)", defaultValue = 0f, range = -90f..90f, step = 0.5f),
                ParamSpec.FloatParam("scale", label = "Swell", defaultValue = 0f, range = -1f..2f, step = 0.01f),
                ParamSpec.FloatParam("cycles", label = "Cycles", defaultValue = 2f, range = 0f..50f, step = 0.25f),
                ParamSpec.FloatParam("phasePerPiece", label = "Phase per piece", defaultValue = 0.08f, range = -1f..1f, step = 0.01f),
                ParamSpec.ChoiceParam("shape", label = "Shape", defaultValue = "sine", choices = WAVE_SHAPES),
                ParamSpec.FloatParam("fade", label = "Fade in/out share", defaultValue = 0.15f, range = 0f..0.5f, step = 0.01f),
            ),
            defaultDurationMs = 2000,
            defaultCurve = Curve.Linear,
        ),
        BuildEffectDeclaration(
            id = TRACKING_BREATHE,
            tier = EffectTier.Layer,
            supportsPieceDelivery = true,
            isEmphasis = true,
            args = listOf(
                ParamSpec.FloatParam("amplitude", label = "Tracking (em per glyph)", defaultValue = 0.12f, range = -1f..2f, step = 0.01f),
                ParamSpec.FloatParam("cycles", label = "Breaths", defaultValue = 1f, range = 0f..50f, step = 0.25f),
                ParamSpec.FloatParam("phasePerLine", label = "Phase per line", defaultValue = 0.15f, range = -1f..1f, step = 0.01f),
                ParamSpec.ChoiceParam("shape", label = "Shape", defaultValue = "sine", choices = WAVE_SHAPES),
                ParamSpec.FloatParam("fade", label = "Fade in/out share", defaultValue = 0.12f, range = 0f..0.5f, step = 0.01f),
            ),
            defaultDurationMs = 3000,
            defaultCurve = Curve.Linear,
        ),
        BuildEffectDeclaration(
            id = AXIS_WAVE,
            tier = EffectTier.Layer,
            supportsPieceDelivery = true,
            isEmphasis = true,
            args = listOf(
                ParamSpec.StringParam("axis", label = "Axis tag", defaultValue = "wght"),
                ParamSpec.FloatParam("amplitude", label = "Swing (axis units)", defaultValue = 200f, range = -2000f..2000f, step = 1f),
                ParamSpec.FloatParam("offset", label = "Bias (axis units)", defaultValue = 0f, range = -2000f..2000f, step = 1f),
                ParamSpec.FloatParam("cycles", label = "Cycles", defaultValue = 2f, range = 0f..50f, step = 0.25f),
                ParamSpec.FloatParam("phasePerPiece", label = "Phase per piece", defaultValue = 0.08f, range = -1f..1f, step = 0.01f),
                ParamSpec.FloatParam("phasePerLine", label = "Phase per line", defaultValue = 0f, range = -1f..1f, step = 0.01f),
                ParamSpec.ChoiceParam("shape", label = "Shape", defaultValue = "sine", choices = WAVE_SHAPES),
                ParamSpec.FloatParam("fade", label = "Fade in/out share", defaultValue = 0.15f, range = 0f..0.5f, step = 0.01f),
            ),
            defaultDurationMs = 2400,
            defaultCurve = Curve.Linear,
        ),
        BuildEffectDeclaration(
            id = AXIS_RISE,
            tier = EffectTier.Layer,
            supportsPieceDelivery = true,
            supportedDirections = RISE_DIRECTIONS,
            args = listOf(
                ParamSpec.StringParam("axis", label = "Axis tag", defaultValue = "wght"),
                ParamSpec.FloatParam("axisFrom", label = "Axis offset at the start (axis units)", defaultValue = -300f, range = -2000f..2000f, step = 1f),
                ParamSpec.FloatParam("distance", label = "Distance (em)", defaultValue = 0.5f, range = 0f..5f, step = 0.05f),
                ParamSpec.BooleanParam("reveal", label = "Mask to line", defaultValue = true),
                ParamSpec.FloatParam("revealPadding", label = "Mask padding (em)", defaultValue = 0.12f, range = 0f..2f, step = 0.01f),
                ParamSpec.FloatParam("fadeWindow", label = "Fade-in share", defaultValue = 0.35f, range = 0f..1f, step = 0.05f),
            ),
            defaultDurationMs = 900,
            defaultCurve = Curve.Linear,
        ),
    )

    val ids: Set<String> = all.mapTo(linkedSetOf()) { it.id }
}
