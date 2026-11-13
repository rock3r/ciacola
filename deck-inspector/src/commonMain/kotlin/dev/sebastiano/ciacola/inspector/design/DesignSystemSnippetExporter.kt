package dev.sebastiano.ciacola.inspector.design

import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.theme.CodePaletteOverrides
import dev.sebastiano.ciacola.theme.ColorTokenOverrides
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.MotionTokenOverrides
import dev.sebastiano.ciacola.theme.StyleTokenOverrides
import dev.sebastiano.ciacola.theme.TextStyleTokenOverride
import dev.sebastiano.ciacola.theme.TypographyTokenOverrides
import dev.sebastiano.ciacola.theme.withOverrides

/**
 * Formats a deterministic, paste-ready Kotlin snippet using [DeckThemes] and
 * [DesignSystemOverrides] for the given [preset], [overrides], and [baseTheme].
 */
fun exportDesignSystemKotlinSnippet(
    preset: DeckThemePreset,
    overrides: DesignSystemOverrides,
    baseTheme: DeckTheme = DeckThemes.forPreset(preset),
): String {
    val presetFactory = when (preset) {
        DeckThemePreset.StageDark -> "DeckThemes.stageDark()"
        DeckThemePreset.KeynoteLight -> "DeckThemes.keynoteLight()"
    }
    val valName = when (preset) {
        DeckThemePreset.StageDark -> "stageDarkOverrides"
        DeckThemePreset.KeynoteLight -> "keynoteLightOverrides"
    }
    val resolved = baseTheme.withOverrides(overrides)

    if (!overrides.hasOverrides) {
        return buildString {
            appendLine("// Ciacola Design System (${preset.name}) — using preset defaults")
            appendLine("val $valName = DesignSystemOverrides()")
            append("val theme = $presetFactory")
        }
    }

    return buildString {
        appendLine("// Ciacola Design System overrides for ${preset.name}")
        appendLine("val $valName = DesignSystemOverrides(")
        if (overrides.colors != ColorTokenOverrides()) {
            appendColorOverrides(overrides.colors, resolved)
        }
        if (overrides.typography != TypographyTokenOverrides()) {
            appendTypographyOverrides(overrides.typography)
        }
        if (overrides.styles != StyleTokenOverrides()) {
            appendStyleOverrides(overrides.styles)
        }
        if (overrides.motion != MotionTokenOverrides()) {
            appendMotionOverrides(overrides.motion)
        }
        appendLine(")")
        append("val theme = $presetFactory.withOverrides($valName)")
    }
}

private fun StringBuilder.appendColorOverrides(
    c: ColorTokenOverrides,
    resolved: DeckTheme,
) {
    appendLine("    colors = ColorTokenOverrides(")
    c.backgroundArgb?.let { appendLine("        backgroundArgb = ${Color(it).toKotlinHexLiteral()}, // ${resolved.colors.background.toHexString()}") }
    c.surfaceArgb?.let { appendLine("        surfaceArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.elevatedSurfaceArgb?.let { appendLine("        elevatedSurfaceArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.foregroundArgb?.let { appendLine("        foregroundArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.mutedArgb?.let { appendLine("        mutedArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.subtleArgb?.let { appendLine("        subtleArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.accentArgb?.let { appendLine("        accentArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.onAccentArgb?.let { appendLine("        onAccentArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.secondaryArgb?.let { appendLine("        secondaryArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.positiveArgb?.let { appendLine("        positiveArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.warningArgb?.let { appendLine("        warningArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.dangerArgb?.let { appendLine("        dangerArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.borderArgb?.let { appendLine("        borderArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.chipBackgroundArgb?.let { appendLine("        chipBackgroundArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.motionPathPreviewArgb?.let { appendLine("        motionPathPreviewArgb = ${Color(it).toKotlinHexLiteral()},") }
    c.spotlightDimAlpha?.let { appendLine("        spotlightDimAlpha = ${formatFloatLiteral(it)},") }
    c.code?.takeIf { it != CodePaletteOverrides() }?.let { code ->
        appendLine("        code = CodePaletteOverrides(")
        code.backgroundArgb?.let { appendLine("            backgroundArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.foregroundArgb?.let { appendLine("            foregroundArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.keywordArgb?.let { appendLine("            keywordArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.stringArgb?.let { appendLine("            stringArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.commentArgb?.let { appendLine("            commentArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.numberArgb?.let { appendLine("            numberArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.typeArgb?.let { appendLine("            typeArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.functionDeclarationArgb?.let { appendLine("            functionDeclarationArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.functionCallArgb?.let { appendLine("            functionCallArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.propertyArgb?.let { appendLine("            propertyArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.annotationArgb?.let { appendLine("            annotationArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.punctuationArgb?.let { appendLine("            punctuationArgb = ${Color(it).toKotlinHexLiteral()},") }
        code.invalidArgb?.let { appendLine("            invalidArgb = ${Color(it).toKotlinHexLiteral()},") }
        appendLine("        ),")
    }
    appendLine("    ),")
}

private fun StringBuilder.appendTypographyOverrides(t: TypographyTokenOverrides) {
    appendLine("    typography = TypographyTokenOverrides(")
    t.globalFontScale?.let { appendLine("        globalFontScale = ${formatFloatLiteral(it)},") }
    appendTextStyleRole("hero", t.hero)
    appendTextStyleRole("title", t.title)
    appendTextStyleRole("heading", t.heading)
    appendTextStyleRole("subheading", t.subheading)
    appendTextStyleRole("body", t.body)
    appendTextStyleRole("bodyEmphasis", t.bodyEmphasis)
    appendTextStyleRole("callout", t.callout)
    appendTextStyleRole("eyebrow", t.eyebrow)
    appendTextStyleRole("code", t.code)
    appendTextStyleRole("codeEmphasis", t.codeEmphasis)
    appendTextStyleRole("stamp", t.stamp)
    appendLine("    ),")
}

private fun StringBuilder.appendTextStyleRole(name: String, o: TextStyleTokenOverride?) {
    if (o == null || o == TextStyleTokenOverride()) return
    val args = buildList {
        o.fontSizeSp?.let { add("fontSizeSp = ${formatFloatLiteral(it)}") }
        o.lineHeightSp?.let { add("lineHeightSp = ${formatFloatLiteral(it)}") }
        o.fontWeight?.let { add("fontWeight = $it") }
        o.letterSpacingSp?.let { add("letterSpacingSp = ${formatFloatLiteral(it)}") }
    }
    if (args.isNotEmpty()) {
        appendLine("        $name = TextStyleTokenOverride(${args.joinToString(", ")}),")
    }
}

private fun StringBuilder.appendStyleOverrides(s: StyleTokenOverrides) {
    appendLine("    styles = StyleTokenOverrides(")
    s.slidePaddingDp?.let { appendLine("        slidePaddingDp = ${formatFloatLiteral(it)},") }
    s.codeCardCornerRadiusDp?.let { appendLine("        codeCardCornerRadiusDp = ${formatFloatLiteral(it)},") }
    s.codeCardPaddingDp?.let { appendLine("        codeCardPaddingDp = ${formatFloatLiteral(it)},") }
    s.codeCardBorderWidthDp?.let { appendLine("        codeCardBorderWidthDp = ${formatFloatLiteral(it)},") }
    s.calloutCornerRadiusDp?.let { appendLine("        calloutCornerRadiusDp = ${formatFloatLiteral(it)},") }
    s.calloutStrokeWidthDp?.let { appendLine("        calloutStrokeWidthDp = ${formatFloatLiteral(it)},") }
    s.calloutDashOnDp?.let { appendLine("        calloutDashOnDp = ${formatFloatLiteral(it)},") }
    s.calloutDashOffDp?.let { appendLine("        calloutDashOffDp = ${formatFloatLiteral(it)},") }
    s.calloutInsetDp?.let { appendLine("        calloutInsetDp = ${formatFloatLiteral(it)},") }
    s.leaderArrowStrokeWidthDp?.let { appendLine("        leaderArrowStrokeWidthDp = ${formatFloatLiteral(it)},") }
    s.leaderArrowHeadLengthDp?.let { appendLine("        leaderArrowHeadLengthDp = ${formatFloatLiteral(it)},") }
    s.leaderArrowHeadAngleDegrees?.let { appendLine("        leaderArrowHeadAngleDegrees = ${formatFloatLiteral(it)},") }
    s.leaderArrowGapDp?.let { appendLine("        leaderArrowGapDp = ${formatFloatLiteral(it)},") }
    s.strikeoutStrokeWidthDp?.let { appendLine("        strikeoutStrokeWidthDp = ${formatFloatLiteral(it)},") }
    s.strikeoutHorizontalOverhangDp?.let { appendLine("        strikeoutHorizontalOverhangDp = ${formatFloatLiteral(it)},") }
    s.strikeoutVerticalTiltFraction?.let { appendLine("        strikeoutVerticalTiltFraction = ${formatFloatLiteral(it)},") }
    s.stampCornerRadiusDp?.let { appendLine("        stampCornerRadiusDp = ${formatFloatLiteral(it)},") }
    s.stampHorizontalPaddingDp?.let { appendLine("        stampHorizontalPaddingDp = ${formatFloatLiteral(it)},") }
    s.stampVerticalPaddingDp?.let { appendLine("        stampVerticalPaddingDp = ${formatFloatLiteral(it)},") }
    s.stampBorderWidthDp?.let { appendLine("        stampBorderWidthDp = ${formatFloatLiteral(it)},") }
    s.detourDiamondSizeDp?.let { appendLine("        detourDiamondSizeDp = ${formatFloatLiteral(it)},") }
    s.detourCornerRadiusDp?.let { appendLine("        detourCornerRadiusDp = ${formatFloatLiteral(it)},") }
    s.detourStrokeWidthDp?.let { appendLine("        detourStrokeWidthDp = ${formatFloatLiteral(it)},") }
    s.loupeDiameterDp?.let { appendLine("        loupeDiameterDp = ${formatFloatLiteral(it)},") }
    s.loupeRingWidthDp?.let { appendLine("        loupeRingWidthDp = ${formatFloatLiteral(it)},") }
    s.loupeDefaultZoom?.let { appendLine("        loupeDefaultZoom = ${formatFloatLiteral(it)},") }
    s.loupeConnectorDashOnDp?.let { appendLine("        loupeConnectorDashOnDp = ${formatFloatLiteral(it)},") }
    s.loupeConnectorDashOffDp?.let { appendLine("        loupeConnectorDashOffDp = ${formatFloatLiteral(it)},") }
    appendLine("    ),")
}

private fun StringBuilder.appendMotionOverrides(m: MotionTokenOverrides) {
    appendLine("    motion = MotionTokenOverrides(")
    m.fadeMoveDurationMs?.let { appendLine("        fadeMoveDurationMs = $it,") }
    m.fadeMoveDistanceDp?.let { appendLine("        fadeMoveDistanceDp = ${formatFloatLiteral(it)},") }
    m.dissolveDurationMs?.let { appendLine("        dissolveDurationMs = $it,") }
    m.fromDarknessDurationMs?.let { appendLine("        fromDarknessDurationMs = $it,") }
    m.fromDarknessInitialScale?.let { appendLine("        fromDarknessInitialScale = ${formatFloatLiteral(it)},") }
    m.lineDrawDurationMs?.let { appendLine("        lineDrawDurationMs = $it,") }
    m.staggerStepMs?.let { appendLine("        staggerStepMs = $it,") }
    m.spotlightDurationMs?.let { appendLine("        spotlightDurationMs = $it,") }
    m.loupeDurationMs?.let { appendLine("        loupeDurationMs = $it,") }
    m.sharedBoundsDurationMs?.let { appendLine("        sharedBoundsDurationMs = $it,") }
    m.warpTokenDurationMs?.let { appendLine("        warpTokenDurationMs = $it,") }
    m.popDampingRatio?.let { appendLine("        popDampingRatio = ${formatFloatLiteral(it)},") }
    m.popStiffness?.let { appendLine("        popStiffness = ${formatFloatLiteral(it)},") }
    m.popInitialScale?.let { appendLine("        popInitialScale = ${formatFloatLiteral(it)},") }
    m.popOvershootScale?.let { appendLine("        popOvershootScale = ${formatFloatLiteral(it)},") }
    appendLine("    ),")
}

private fun formatFloatLiteral(value: Float): String {
    val roundedInt = value.toInt()
    val formatted = if (value == roundedInt.toFloat()) {
        "$roundedInt.0"
    } else {
        val scaled = kotlin.math.round(value * 1000f) / 1000f
        scaled.toString()
    }
    return "${formatted}f"
}
