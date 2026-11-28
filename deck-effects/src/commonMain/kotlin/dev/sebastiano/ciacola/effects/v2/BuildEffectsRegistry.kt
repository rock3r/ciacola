package dev.sebastiano.ciacola.effects.v2

import dev.sebastiano.ciacola.document.MotionPresetDeclarations

/**
 * Central 3-tier (+ Text-tier) build effects registry for Ciacola v2, plus the complete 52-entry
 * Keynote `(type, name)` mapping table covering every effect observed in the reference KPF decks.
 */
object BuildEffects {
    const val APPEAR = "appear"
    const val DISSOLVE = "dissolve"
    const val FADE_MOVE = "fadeMove"
    const val MOVE_IN = "moveIn"
    const val WIPE = "wipe"
    const val IRIS = "iris"
    const val ZOOM = "zoom"
    const val ZOOM_BIG = "zoomBig"
    const val POP = "pop"
    const val BLUR = "blur"
    const val FROM_DARKNESS = "fromDarkness"
    const val JIGGLE = "jiggle"
    const val LINE_DRAW = "lineDraw"
    const val TRACE = "trace"
    const val SPARKLE = "sparkle"
    const val SHIMMER = "shimmer"
    const val KEYBOARD = "keyboard"

    private val allNodes: Set<EffectNodeKind> = EffectNodeKind.entries.toSet()
    private val enterExit: Set<EffectBuildKind> = setOf(EffectBuildKind.Enter, EffectBuildKind.Exit)

    val declarations: Map<String, EffectDeclaration> = listOf(
        EffectDeclaration(
            id = APPEAR,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
        ),
        EffectDeclaration(
            id = DISSOLVE,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
        ),
        EffectDeclaration(
            id = FADE_MOVE,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
                EffectParamSpec("distance", EffectParamType.Float, minValue = 0f),
                EffectParamSpec("posCurve", EffectParamType.Curve),
                EffectParamSpec("alphaCurve", EffectParamType.Curve),
                EffectParamSpec("exitCurve", EffectParamType.Curve),
            ),
        ),
        EffectDeclaration(
            id = MOVE_IN,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
                EffectParamSpec("distance", EffectParamType.Float, minValue = 0f),
                EffectParamSpec("bounce", EffectParamType.Boolean),
            ),
        ),
        EffectDeclaration(
            id = WIPE,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
            ),
        ),
        EffectDeclaration(
            id = IRIS,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.IrisDirection),
                EffectParamSpec("center", EffectParamType.Offset),
                EffectParamSpec("centerX", EffectParamType.Float, minValue = 0f, maxValue = 1f),
                EffectParamSpec("centerY", EffectParamType.Float, minValue = 0f, maxValue = 1f),
            ),
        ),
        EffectDeclaration(
            id = ZOOM,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("fromScale", EffectParamType.Float, minValue = 0.01f, maxValue = 20f),
                EffectParamSpec("toScale", EffectParamType.Float, minValue = 0.01f, maxValue = 20f),
                EffectParamSpec("scaleDelay", EffectParamType.Float, minValue = 0f, maxValue = 0.85f),
                EffectParamSpec("fade", EffectParamType.Boolean),
                EffectParamSpec("originX", EffectParamType.Float, minValue = 0f, maxValue = 1f),
                EffectParamSpec("originY", EffectParamType.Float, minValue = 0f, maxValue = 1f),
            ),
        ),
        EffectDeclaration(
            id = ZOOM_BIG,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("fromScale", EffectParamType.Float, minValue = 0.01f, maxValue = 20f),
                EffectParamSpec("toScale", EffectParamType.Float, minValue = 0.01f, maxValue = 20f),
                EffectParamSpec("scaleDelay", EffectParamType.Float, minValue = 0f, maxValue = 0.85f),
                EffectParamSpec("fade", EffectParamType.Boolean),
            ),
        ),
        EffectDeclaration(
            id = POP,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("initialScale", EffectParamType.Float, minValue = 0.01f, maxValue = 0.99f),
                EffectParamSpec("overshootScale", EffectParamType.Float, minValue = 1f, maxValue = 2.5f),
            ),
        ),
        EffectDeclaration(
            id = BLUR,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("radius", EffectParamType.Float, minValue = 0f, maxValue = 256f),
            ),
        ),
        EffectDeclaration(
            id = FROM_DARKNESS,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("initialScale", EffectParamType.Float, minValue = 0.5f, maxValue = 1.5f),
            ),
        ),
        EffectDeclaration(
            id = JIGGLE,
            tier = EffectTier.Layer,
            supportedKinds = setOf(EffectBuildKind.Emphasis),
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("intensity", EffectParamType.Float, minValue = 0f, maxValue = 5f),
                EffectParamSpec("cycles", EffectParamType.Float, minValue = 1f, maxValue = 20f),
                EffectParamSpec("amplitude", EffectParamType.Float, minValue = 0f, maxValue = 90f),
                EffectParamSpec("maxAngleDeg", EffectParamType.Float, minValue = 0f, maxValue = 45f),
                EffectParamSpec("maxShiftPx", EffectParamType.Float, minValue = 0f, maxValue = 100f),
            ),
        ),
        EffectDeclaration(
            id = LINE_DRAW,
            tier = EffectTier.Path,
            supportedKinds = enterExit,
            supportedNodeKinds = setOf(EffectNodeKind.Path, EffectNodeKind.Shape, EffectNodeKind.Connector),
            unsupportedNodeFallbackId = DISSOLVE,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.LineDrawDirection),
                EffectParamSpec("includeEndpoints", EffectParamType.Boolean),
            ),
        ),
        EffectDeclaration(
            id = TRACE,
            tier = EffectTier.Content,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
                EffectParamSpec("accentColor", EffectParamType.Color),
                EffectParamSpec("edgeThreshold", EffectParamType.Float, minValue = 0.01f, maxValue = 1f),
            ),
        ),
        EffectDeclaration(
            id = SPARKLE,
            tier = EffectTier.Content,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
                EffectParamSpec("sparkleColor", EffectParamType.Color),
                EffectParamSpec("seed", EffectParamType.Long),
                EffectParamSpec("density", EffectParamType.Float, minValue = 0.1f, maxValue = 5f),
            ),
        ),
        EffectDeclaration(
            id = SHIMMER,
            tier = EffectTier.Content,
            supportedKinds = setOf(EffectBuildKind.Enter, EffectBuildKind.Emphasis),
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
                EffectParamSpec("highlightColor", EffectParamType.Color),
                EffectParamSpec("intensity", EffectParamType.Float, minValue = 0f, maxValue = 3f),
            ),
        ),
        EffectDeclaration(
            id = KEYBOARD,
            tier = EffectTier.Text,
            supportedKinds = enterExit,
            supportedNodeKinds = setOf(EffectNodeKind.Text),
            unsupportedNodeFallbackId = DISSOLVE,
            params = listOf(
                EffectParamSpec("seed", EffectParamType.Long),
                EffectParamSpec("charsPerSec", EffectParamType.Float, minValue = 1f, maxValue = 200f),
                EffectParamSpec("initialPauseMs", EffectParamType.Int, minValue = 0f, maxValue = 5000f),
                EffectParamSpec("caret", EffectParamType.Boolean),
                EffectParamSpec("showCaret", EffectParamType.Boolean),
                EffectParamSpec("caretColor", EffectParamType.Color),
            ),
        ),
        EffectDeclaration(
            id = MotionPresetDeclarations.RISE,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
                EffectParamSpec("distance", EffectParamType.Float, minValue = 0f, maxValue = 5f),
                EffectParamSpec("reveal", EffectParamType.Boolean),
                EffectParamSpec("revealPadding", EffectParamType.Float, minValue = 0f, maxValue = 2f),
                EffectParamSpec("fadeWindow", EffectParamType.Float, minValue = 0f, maxValue = 1f),
                EffectParamSpec(MotionPresets.CHANNELS_ARG, EffectParamType.Channels),
            ),
        ),
        EffectDeclaration(
            id = MotionPresetDeclarations.FOCUS_PULL,
            tier = EffectTier.Layer,
            supportedKinds = setOf(EffectBuildKind.Enter, EffectBuildKind.Exit, EffectBuildKind.Emphasis),
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("radius", EffectParamType.Float, minValue = 0f, maxValue = 3f),
                EffectParamSpec("restOpacity", EffectParamType.Float, minValue = 0f, maxValue = 1f),
                EffectParamSpec("fromScale", EffectParamType.Float, minValue = 0.2f, maxValue = 3f),
                EffectParamSpec("focusFrom", EffectParamType.Float, minValue = -0.5f, maxValue = 1.5f),
                EffectParamSpec("focusTo", EffectParamType.Float, minValue = -0.5f, maxValue = 1.5f),
                EffectParamSpec("focusWidth", EffectParamType.Float, minValue = 0.05f, maxValue = 20f),
                EffectParamSpec("settle", EffectParamType.Boolean),
                EffectParamSpec(MotionPresets.CHANNELS_ARG, EffectParamType.Channels),
            ),
        ),
        EffectDeclaration(
            id = MotionPresetDeclarations.WAVE,
            tier = EffectTier.Layer,
            supportedKinds = setOf(EffectBuildKind.Emphasis),
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("amplitude", EffectParamType.Float, minValue = -3f, maxValue = 3f),
                EffectParamSpec("rotation", EffectParamType.Float, minValue = -90f, maxValue = 90f),
                EffectParamSpec("scale", EffectParamType.Float, minValue = -1f, maxValue = 2f),
                EffectParamSpec("cycles", EffectParamType.Float, minValue = 0f, maxValue = 50f),
                EffectParamSpec("phasePerPiece", EffectParamType.Float, minValue = -1f, maxValue = 1f),
                EffectParamSpec("shape", EffectParamType.String),
                EffectParamSpec("fade", EffectParamType.Float, minValue = 0f, maxValue = 0.5f),
                EffectParamSpec(MotionPresets.CHANNELS_ARG, EffectParamType.Channels),
            ),
        ),
        EffectDeclaration(
            id = MotionPresetDeclarations.TRACKING_BREATHE,
            tier = EffectTier.Layer,
            supportedKinds = setOf(EffectBuildKind.Emphasis),
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("amplitude", EffectParamType.Float, minValue = -1f, maxValue = 2f),
                EffectParamSpec("cycles", EffectParamType.Float, minValue = 0f, maxValue = 50f),
                EffectParamSpec("phasePerLine", EffectParamType.Float, minValue = -1f, maxValue = 1f),
                EffectParamSpec("shape", EffectParamType.String),
                EffectParamSpec("fade", EffectParamType.Float, minValue = 0f, maxValue = 0.5f),
                EffectParamSpec(MotionPresets.CHANNELS_ARG, EffectParamType.Channels),
            ),
        ),
        EffectDeclaration(
            id = MotionPresetDeclarations.AXIS_WAVE,
            tier = EffectTier.Layer,
            supportedKinds = setOf(EffectBuildKind.Emphasis),
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("axis", EffectParamType.String),
                EffectParamSpec("amplitude", EffectParamType.Float, minValue = -2000f, maxValue = 2000f),
                EffectParamSpec("offset", EffectParamType.Float, minValue = -2000f, maxValue = 2000f),
                EffectParamSpec("cycles", EffectParamType.Float, minValue = 0f, maxValue = 50f),
                EffectParamSpec("phasePerPiece", EffectParamType.Float, minValue = -1f, maxValue = 1f),
                EffectParamSpec("phasePerLine", EffectParamType.Float, minValue = -1f, maxValue = 1f),
                EffectParamSpec("shape", EffectParamType.String),
                EffectParamSpec("fade", EffectParamType.Float, minValue = 0f, maxValue = 0.5f),
                EffectParamSpec(MotionPresets.CHANNELS_ARG, EffectParamType.Channels),
            ),
        ),
        EffectDeclaration(
            id = MotionPresetDeclarations.AXIS_RISE,
            tier = EffectTier.Layer,
            supportedKinds = enterExit,
            supportedNodeKinds = allNodes,
            params = listOf(
                EffectParamSpec("direction", EffectParamType.Direction8),
                EffectParamSpec("axis", EffectParamType.String),
                EffectParamSpec("axisFrom", EffectParamType.Float, minValue = -2000f, maxValue = 2000f),
                EffectParamSpec("distance", EffectParamType.Float, minValue = 0f, maxValue = 5f),
                EffectParamSpec("reveal", EffectParamType.Boolean),
                EffectParamSpec("revealPadding", EffectParamType.Float, minValue = 0f, maxValue = 2f),
                EffectParamSpec("fadeWindow", EffectParamType.Float, minValue = 0f, maxValue = 1f),
                EffectParamSpec(MotionPresets.CHANNELS_ARG, EffectParamType.Channels),
            ),
        ),
    ).associateBy { it.id }

    fun get(id: String): EffectDeclaration? = declarations[id]

    /**
     * Resolves the effective effect id for [nodeKind], falling back to `dissolve`
     * whenever [effectId] is unknown or unsupported on [nodeKind].
     */
    fun resolveEffectiveEffectId(effectId: String, nodeKind: EffectNodeKind): String {
        val decl = declarations[effectId] ?: return DISSOLVE
        return if (nodeKind in decl.supportedNodeKinds) {
            effectId
        } else {
            decl.unsupportedNodeFallbackId ?: DISSOLVE
        }
    }

    /**
     * Validates an effect call against its [EffectDeclaration], checking existence, [EffectBuildKind],
     * [EffectNodeKind] (with documented `dissolve` fallback), and argument names/types/ranges.
     */
    fun validate(
        effectId: String,
        kind: EffectBuildKind,
        nodeKind: EffectNodeKind,
        args: EffectArgs = EffectArgs.Empty,
    ): List<EffectValidationIssue> {
        val decl = declarations[effectId] ?: return listOf(
            EffectValidationIssue(
                severity = EffectValidationSeverity.Error,
                code = "EFFECT_UNKNOWN",
                message = "Unknown build effect '$effectId'.",
                fallbackEffectId = DISSOLVE,
            ),
        )
        val issues = mutableListOf<EffectValidationIssue>()
        if (kind !in decl.supportedKinds) {
            issues += EffectValidationIssue(
                severity = EffectValidationSeverity.Error,
                code = "EFFECT_UNSUPPORTED_KIND",
                message = "Effect '$effectId' does not support build kind $kind (supported: ${decl.supportedKinds}).",
            )
        }
        if (nodeKind !in decl.supportedNodeKinds) {
            val fallback = decl.unsupportedNodeFallbackId ?: DISSOLVE
            issues += EffectValidationIssue(
                severity = EffectValidationSeverity.Error,
                code = "EFFECT_UNSUPPORTED_NODE_KIND",
                message = "Effect '$effectId' does not support node kind $nodeKind; falling back to '$fallback'.",
                fallbackEffectId = fallback,
            )
        }

        val paramsByName = decl.params.associateBy { it.name }
        for ((argName, rawVal) in args.values) {
            if (rawVal == null) continue
            val spec = paramsByName[argName]
            if (spec == null) {
                issues += EffectValidationIssue(
                    severity = EffectValidationSeverity.Warning,
                    code = "EFFECT_UNKNOWN_ARG",
                    message = "Unknown argument '$argName' for effect '$effectId'.",
                )
                continue
            }
            validateArgValue(effectId, spec, rawVal)?.let { issues += it }
        }
        return issues
    }

    /**
     * Evaluates any Layer-tier effect (or degrades unsupported node kinds / non-Layer effects
     * cleanly to `dissolve` when invoked through the layer path).
     */
    fun evaluateLayer(
        effectId: String,
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext = EffectEvalContext(),
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
        nodeKind: EffectNodeKind? = null,
    ): LayerState {
        val effectiveId = if (nodeKind != null) resolveEffectiveEffectId(effectId, nodeKind) else effectId
        return when (effectiveId) {
            APPEAR -> LayerEffects.evaluateAppear(kind, progress)
            DISSOLVE -> LayerEffects.evaluateDissolve(kind, progress, curve)
            FADE_MOVE -> LayerEffects.evaluateFadeMove(kind, progress, context, args, curve)
            MOVE_IN -> LayerEffects.evaluateMoveIn(kind, progress, context, args, curve)
            WIPE -> LayerEffects.evaluateWipe(kind, progress, context, args, curve)
            IRIS -> LayerEffects.evaluateIris(kind, progress, context, args, curve)
            ZOOM -> LayerEffects.evaluateZoom(kind, progress, context, args, curve, isBig = false)
            ZOOM_BIG -> LayerEffects.evaluateZoom(kind, progress, context, args, curve, isBig = true)
            POP -> LayerEffects.evaluatePop(kind, progress, context, args, curve)
            BLUR -> LayerEffects.evaluateBlur(kind, progress, context, args, curve)
            FROM_DARKNESS -> LayerEffects.evaluateFromDarkness(kind, progress, context, args, curve)
            JIGGLE -> LayerEffects.evaluateJiggle(kind, progress, context, args)
            else -> if (MotionPresets.isPreset(effectiveId)) {
                MotionPresets.evaluateLayer(effectiveId, kind, progress, context, args, curve)
            } else {
                LayerEffects.evaluateDissolve(kind, progress, curve)
            }
        }
    }

    /**
     * Evaluates Path-tier trim state (`lineDraw`).
     */
    fun evaluatePath(
        effectId: String = LINE_DRAW,
        kind: EffectBuildKind,
        progress: Float,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): PathTrimState = when (effectId) {
        LINE_DRAW -> LayerEffects.evaluateLineDrawTrim(kind, progress, args, curve)
        else -> PathTrimState.Full
    }

    /**
     * Complete 52-entry Keynote `(keynoteType, keynoteName)` mapping catalog covering all 5
     * `actionBuild`, 24 `buildIn`, 14 `buildOut`, and 9 `transition` effects observed across the
     * reference KPF presentations.
     */
    val keynoteEffectMappings: List<KeynoteEffectMapping> = listOf(
        // --- 5 actionBuild ---
        KeynoteEffectMapping("actionBuild", "apple:action-jiggle", KeynoteTargetCategory.BuildEffect, JIGGLE, EffectTier.Layer, EffectBuildKind.Emphasis, KeynoteDeliveryMode.Object),
        KeynoteEffectMapping("actionBuild", "apple:action-motion-path", KeynoteTargetCategory.ActionTrackProperty, "motionPath", null, null, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("actionBuild", "apple:action-opacity", KeynoteTargetCategory.ActionTrackProperty, "opacity", null, null, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("actionBuild", "apple:action-rotation", KeynoteTargetCategory.ActionTrackProperty, "rotation", null, null, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("actionBuild", "apple:action-scale", KeynoteTargetCategory.ActionTrackProperty, "scale", null, null, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),

        // --- 24 buildIn ---
        KeynoteEffectMapping("buildIn", "apple:appear", KeynoteTargetCategory.BuildEffect, APPEAR, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.LINEAR),
        KeynoteEffectMapping("buildIn", "apple:bc-appear", KeynoteTargetCategory.BuildEffect, APPEAR, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.LINEAR),
        KeynoteEffectMapping("buildIn", "apple:bc-pop", KeynoteTargetCategory.BuildEffect, POP, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object),
        KeynoteEffectMapping("buildIn", "apple:dissolve", KeynoteTargetCategory.BuildEffect, DISSOLVE, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "apple:dissolve character", KeynoteTargetCategory.BuildEffect, DISSOLVE, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Character, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "apple:fade and move", KeynoteTargetCategory.BuildEffect, FADE_MOVE, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.FADE_MOVE_POS),
        KeynoteEffectMapping("buildIn", "apple:fade and move character", KeynoteTargetCategory.BuildEffect, FADE_MOVE, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Character, KeynoteCurves.FADE_MOVE_POS),
        KeynoteEffectMapping("buildIn", "apple:keyboard", KeynoteTargetCategory.BuildEffect, KEYBOARD, EffectTier.Text, EffectBuildKind.Enter, KeynoteDeliveryMode.Character),
        KeynoteEffectMapping("buildIn", "apple:move in", KeynoteTargetCategory.BuildEffect, MOVE_IN, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.MOVE_IN),
        KeynoteEffectMapping("buildIn", "apple:move in character", KeynoteTargetCategory.BuildEffect, MOVE_IN, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Character, KeynoteCurves.MOVE_IN),
        KeynoteEffectMapping("buildIn", "apple:movie-start", KeynoteTargetCategory.MediaPlayback, "movieStart", null, EffectBuildKind.Enter, KeynoteDeliveryMode.Media),
        KeynoteEffectMapping("buildIn", "apple:pop", KeynoteTargetCategory.BuildEffect, POP, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object),
        KeynoteEffectMapping("buildIn", "apple:wipe", KeynoteTargetCategory.BuildEffect, WIPE, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "apple:wipe-iris", KeynoteTargetCategory.BuildEffect, IRIS, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "apple:zoom", KeynoteTargetCategory.BuildEffect, ZOOM, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_OUT),
        KeynoteEffectMapping("buildIn", "apple:zoom character", KeynoteTargetCategory.BuildEffect, ZOOM, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Character, KeynoteCurves.EASE_OUT),
        KeynoteEffectMapping("buildIn", "com.apple.iWork.Keynote.Blur", KeynoteTargetCategory.BuildEffect, BLUR, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_OUT),
        KeynoteEffectMapping("buildIn", "com.apple.iWork.Keynote.FromDarkness", KeynoteTargetCategory.BuildEffect, FROM_DARKNESS, EffectTier.Layer, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "com.apple.iWork.Keynote.KLNShimmer", KeynoteTargetCategory.BuildEffect, SHIMMER, EffectTier.Content, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "com.apple.iWork.Keynote.KLNSparkle", KeynoteTargetCategory.BuildEffect, SPARKLE, EffectTier.Content, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "com.apple.iWork.Keynote.LineDraw", KeynoteTargetCategory.BuildEffect, LINE_DRAW, EffectTier.Path, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "com.apple.iWork.Keynote.LineDrawForLine", KeynoteTargetCategory.BuildEffect, LINE_DRAW, EffectTier.Path, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT, EffectArgs.of("includeEndpoints" to true)),
        KeynoteEffectMapping("buildIn", "com.apple.iWork.Keynote.Trace", KeynoteTargetCategory.BuildEffect, TRACE, EffectTier.Content, EffectBuildKind.Enter, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildIn", "renderMovie", KeynoteTargetCategory.MediaPlayback, "renderMovie", null, EffectBuildKind.Enter, KeynoteDeliveryMode.Media),

        // --- 14 buildOut ---
        KeynoteEffectMapping("buildOut", "apple:bc-appear", KeynoteTargetCategory.BuildEffect, APPEAR, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.LINEAR),
        KeynoteEffectMapping("buildOut", "apple:bc-pop", KeynoteTargetCategory.BuildEffect, POP, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Object),
        KeynoteEffectMapping("buildOut", "apple:bc-zoom-big character", KeynoteTargetCategory.BuildEffect, ZOOM_BIG, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Character, KeynoteCurves.ZOOM_BIG_EXIT),
        KeynoteEffectMapping("buildOut", "apple:dissolve", KeynoteTargetCategory.BuildEffect, DISSOLVE, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildOut", "apple:dissolve character", KeynoteTargetCategory.BuildEffect, DISSOLVE, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Character, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildOut", "apple:fade and move", KeynoteTargetCategory.BuildEffect, FADE_MOVE, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.FADE_MOVE_EXIT),
        KeynoteEffectMapping("buildOut", "apple:fade and move character", KeynoteTargetCategory.BuildEffect, FADE_MOVE, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Character, KeynoteCurves.FADE_MOVE_EXIT),
        KeynoteEffectMapping("buildOut", "apple:keyboard", KeynoteTargetCategory.BuildEffect, KEYBOARD, EffectTier.Text, EffectBuildKind.Exit, KeynoteDeliveryMode.Character),
        KeynoteEffectMapping("buildOut", "apple:move in", KeynoteTargetCategory.BuildEffect, MOVE_IN, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.MOVE_OUT),
        KeynoteEffectMapping("buildOut", "apple:movie-start", KeynoteTargetCategory.MediaPlayback, "movieStop", null, EffectBuildKind.Exit, KeynoteDeliveryMode.Media),
        KeynoteEffectMapping("buildOut", "apple:wipe", KeynoteTargetCategory.BuildEffect, WIPE, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildOut", "com.apple.iWork.Keynote.FromDarkness", KeynoteTargetCategory.BuildEffect, FROM_DARKNESS, EffectTier.Layer, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildOut", "com.apple.iWork.Keynote.LineDraw", KeynoteTargetCategory.BuildEffect, LINE_DRAW, EffectTier.Path, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("buildOut", "com.apple.iWork.Keynote.LineDrawForLine", KeynoteTargetCategory.BuildEffect, LINE_DRAW, EffectTier.Path, EffectBuildKind.Exit, KeynoteDeliveryMode.Object, KeynoteCurves.EASE_IN_OUT, EffectArgs.of("includeEndpoints" to true)),

        // --- 9 transition ---
        KeynoteEffectMapping("transition", "apple:dissolve", KeynoteTargetCategory.SlideTransition, "dissolve", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("transition", "apple:magic-move-implied-motion-path", KeynoteTargetCategory.SlideTransition, "magicMove", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("transition", "apple:push", KeynoteTargetCategory.SlideTransition, "push", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.PUSH),
        KeynoteEffectMapping("transition", "apple:reveal", KeynoteTargetCategory.SlideTransition, "reveal", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.REVEAL),
        KeynoteEffectMapping("transition", "apple:slide", KeynoteTargetCategory.SlideTransition, "slide", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.SLIDE),
        KeynoteEffectMapping("transition", "apple:wipe", KeynoteTargetCategory.SlideTransition, "wipe", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("transition", "apple:wipe-iris", KeynoteTargetCategory.SlideTransition, "iris", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("transition", "com.apple.iWork.Keynote.BLTFadeThruColor", KeynoteTargetCategory.SlideTransition, "fadeThroughColor", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.EASE_IN_OUT),
        KeynoteEffectMapping("transition", "none", KeynoteTargetCategory.SlideTransition, "none", null, null, KeynoteDeliveryMode.Slide, KeynoteCurves.LINEAR),
    )

    private val keynoteIndex: Map<Pair<String, String>, KeynoteEffectMapping> =
        keynoteEffectMappings.associateBy { it.keynoteType to it.keynoteName }

    /** Looks up the exact Ciacola mapping for a Keynote `(keynoteType, keynoteName)` pair. */
    fun resolveKeynoteEffect(keynoteType: String, keynoteName: String): KeynoteEffectMapping? =
        keynoteIndex[keynoteType to keynoteName]

    private fun validateArgValue(
        effectId: String,
        spec: EffectParamSpec,
        rawVal: Any,
    ): EffectValidationIssue? {
        fun typeError(expected: String) = EffectValidationIssue(
            severity = EffectValidationSeverity.Error,
            code = "EFFECT_ARG_TYPE",
            message = "Argument '${spec.name}' on '$effectId' expects $expected, got ${rawVal::class.simpleName} ($rawVal).",
        )

        fun rangeError(value: Float): EffectValidationIssue? {
            if (spec.minValue != null && value < spec.minValue) {
                return EffectValidationIssue(
                    severity = EffectValidationSeverity.Error,
                    code = "EFFECT_ARG_RANGE",
                    message = "Argument '${spec.name}' on '$effectId' must be >= ${spec.minValue} (got $value).",
                )
            }
            if (spec.maxValue != null && value > spec.maxValue) {
                return EffectValidationIssue(
                    severity = EffectValidationSeverity.Error,
                    code = "EFFECT_ARG_RANGE",
                    message = "Argument '${spec.name}' on '$effectId' must be <= ${spec.maxValue} (got $value).",
                )
            }
            return null
        }

        return when (spec.type) {
            EffectParamType.Float -> {
                val f = (rawVal as? Number)?.toFloat() ?: (rawVal as? String)?.toFloatOrNull()
                    ?: return typeError("Float")
                if (!f.isFinite()) return typeError("finite Float")
                rangeError(f)
            }
            EffectParamType.Int -> {
                val n = when (rawVal) {
                    is Int -> rawVal
                    is Long -> rawVal.toInt()
                    is String -> rawVal.toIntOrNull()
                    else -> null
                } ?: return typeError("Int")
                rangeError(n.toFloat())
            }
            EffectParamType.Long -> {
                val ok = rawVal is Long || rawVal is Int || (rawVal is String && rawVal.toLongOrNull() != null)
                if (!ok) typeError("Long") else null
            }
            EffectParamType.Boolean -> {
                val ok = rawVal is Boolean || (rawVal is String && rawVal.toBooleanStrictOrNull() != null)
                if (!ok) typeError("Boolean") else null
            }
            EffectParamType.String -> if (rawVal is String) null else typeError("String")
            EffectParamType.Color -> {
                val ok = rawVal is androidx.compose.ui.graphics.Color || rawVal is Int || rawVal is Long
                if (!ok) typeError("Color") else null
            }
            EffectParamType.Direction8 -> {
                val ok = rawVal is EffectDirection8 ||
                    (rawVal is Int && EffectDirection8.fromKeynoteCode(rawVal) != null) ||
                    (rawVal is String && EffectDirection8.parse(rawVal) != null)
                if (!ok) typeError("EffectDirection8") else null
            }
            EffectParamType.IrisDirection -> {
                val ok = rawVal is IrisDirection || (rawVal is String && IrisDirection.parse(rawVal) != null)
                if (!ok) typeError("IrisDirection") else null
            }
            EffectParamType.LineDrawDirection -> {
                val ok = rawVal is LineDrawDirection || (rawVal is String && LineDrawDirection.parse(rawVal) != null)
                if (!ok) typeError("LineDrawDirection") else null
            }
            EffectParamType.Offset -> {
                if (rawVal is androidx.compose.ui.geometry.Offset) null else typeError("Offset")
            }
            EffectParamType.Curve -> {
                val ok = rawVal is CurveV2 || (rawVal is String && KeynoteCurves.byName(rawVal) != null)
                if (!ok) typeError("CurveV2") else null
            }
            EffectParamType.Channels -> if (rawVal is Map<*, *>) null else typeError("channels map")
        }
    }
}
