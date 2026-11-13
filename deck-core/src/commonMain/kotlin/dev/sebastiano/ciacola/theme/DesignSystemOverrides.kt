package dev.sebastiano.ciacola.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlinx.serialization.Serializable

/**
 * Serializable overrides for [DeckCodePalette] syntax highlighting tokens.
 */
@Serializable
data class CodePaletteOverrides(
    val backgroundArgb: Long? = null,
    val foregroundArgb: Long? = null,
    val keywordArgb: Long? = null,
    val stringArgb: Long? = null,
    val commentArgb: Long? = null,
    val numberArgb: Long? = null,
    val typeArgb: Long? = null,
    val functionDeclarationArgb: Long? = null,
    val functionCallArgb: Long? = null,
    val propertyArgb: Long? = null,
    val annotationArgb: Long? = null,
    val punctuationArgb: Long? = null,
    val invalidArgb: Long? = null,
)

/**
 * Serializable overrides for [DeckColors] tokens. Color values are stored as 32-bit ARGB longs.
 */
@Serializable
data class ColorTokenOverrides(
    val backgroundArgb: Long? = null,
    val surfaceArgb: Long? = null,
    val elevatedSurfaceArgb: Long? = null,
    val foregroundArgb: Long? = null,
    val mutedArgb: Long? = null,
    val subtleArgb: Long? = null,
    val accentArgb: Long? = null,
    val secondaryArgb: Long? = null,
    val positiveArgb: Long? = null,
    val warningArgb: Long? = null,
    val dangerArgb: Long? = null,
    val borderArgb: Long? = null,
    val chipBackgroundArgb: Long? = null,
    val spotlightDimAlpha: Float? = null,
    val onAccentArgb: Long? = null,
    val motionPathPreviewArgb: Long? = null,
    val code: CodePaletteOverrides? = null,
)

/**
 * Serializable per-role typography token override.
 */
@Serializable
data class TextStyleTokenOverride(
    val fontSizeSp: Float? = null,
    val lineHeightSp: Float? = null,
    val fontWeight: Int? = null,
    val letterSpacingSp: Float? = null,
)

/**
 * Serializable overrides for [DeckTypography] roles.
 */
@Serializable
data class TypographyTokenOverrides(
    val hero: TextStyleTokenOverride? = null,
    val title: TextStyleTokenOverride? = null,
    val heading: TextStyleTokenOverride? = null,
    val subheading: TextStyleTokenOverride? = null,
    val body: TextStyleTokenOverride? = null,
    val bodyEmphasis: TextStyleTokenOverride? = null,
    val callout: TextStyleTokenOverride? = null,
    val eyebrow: TextStyleTokenOverride? = null,
    val code: TextStyleTokenOverride? = null,
    val codeEmphasis: TextStyleTokenOverride? = null,
    val stamp: TextStyleTokenOverride? = null,
    val globalFontScale: Float? = null,
)

/**
 * Serializable overrides for [DeckStyles] geometry and decoration tokens.
 */
@Serializable
data class StyleTokenOverrides(
    val slidePaddingDp: Float? = null,
    val codeCardCornerRadiusDp: Float? = null,
    val codeCardPaddingDp: Float? = null,
    val codeCardBorderWidthDp: Float? = null,
    val calloutCornerRadiusDp: Float? = null,
    val calloutStrokeWidthDp: Float? = null,
    val calloutDashOnDp: Float? = null,
    val calloutDashOffDp: Float? = null,
    val calloutInsetDp: Float? = null,
    val leaderArrowStrokeWidthDp: Float? = null,
    val leaderArrowHeadLengthDp: Float? = null,
    val leaderArrowHeadAngleDegrees: Float? = null,
    val leaderArrowGapDp: Float? = null,
    val strikeoutStrokeWidthDp: Float? = null,
    val strikeoutHorizontalOverhangDp: Float? = null,
    val strikeoutVerticalTiltFraction: Float? = null,
    val stampCornerRadiusDp: Float? = null,
    val stampHorizontalPaddingDp: Float? = null,
    val stampVerticalPaddingDp: Float? = null,
    val stampBorderWidthDp: Float? = null,
    val detourDiamondSizeDp: Float? = null,
    val detourCornerRadiusDp: Float? = null,
    val detourStrokeWidthDp: Float? = null,
    val loupeDiameterDp: Float? = null,
    val loupeRingWidthDp: Float? = null,
    val loupeDefaultZoom: Float? = null,
    val loupeConnectorDashOnDp: Float? = null,
    val loupeConnectorDashOffDp: Float? = null,
)

/**
 * Serializable overrides for [DeckMotion] durations, distances, and spring parameters.
 */
@Serializable
data class MotionTokenOverrides(
    val fadeMoveDurationMs: Int? = null,
    val fadeMoveDistanceDp: Float? = null,
    val dissolveDurationMs: Int? = null,
    val fromDarknessDurationMs: Int? = null,
    val fromDarknessInitialScale: Float? = null,
    val lineDrawDurationMs: Int? = null,
    val staggerStepMs: Int? = null,
    val spotlightDurationMs: Int? = null,
    val loupeDurationMs: Int? = null,
    val sharedBoundsDurationMs: Int? = null,
    val warpTokenDurationMs: Int? = null,
    val popDampingRatio: Float? = null,
    val popStiffness: Float? = null,
    val popInitialScale: Float? = null,
    val popOvershootScale: Float? = null,
)

/**
 * Complete design system override payload persisted and edited live by `:deck-inspector`.
 */
@Serializable
data class DesignSystemOverrides(
    val colors: ColorTokenOverrides = ColorTokenOverrides(),
    val typography: TypographyTokenOverrides = TypographyTokenOverrides(),
    val styles: StyleTokenOverrides = StyleTokenOverrides(),
    val motion: MotionTokenOverrides = MotionTokenOverrides(),
) {
    val hasOverrides: Boolean
        get() = this != DesignSystemOverrides()
}

/**
 * Computes WCAG 2.1 relative luminance for an sRGB [Color] in `[0f, 1f]`.
 */
fun relativeLuminance(color: Color): Float {
    fun linearize(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * linearize(color.red) +
        0.7152f * linearize(color.green) +
        0.0722f * linearize(color.blue)
}

/**
 * Computes the WCAG 2.1 contrast ratio (`1f..21f`) between [foreground] and [background].
 */
fun contrastRatio(foreground: Color, background: Color): Float {
    val l1 = relativeLuminance(foreground)
    val l2 = relativeLuminance(background)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Returns a copy of this [DeckTheme] with every non-null token in [overrides] applied.
 */
fun DeckTheme.withOverrides(overrides: DesignSystemOverrides): DeckTheme {
    if (!overrides.hasOverrides) return this
    return copy(
        colors = colors.withOverrides(overrides.colors),
        typography = typography.withOverrides(overrides.typography),
        styles = styles.withOverrides(overrides.styles),
        motion = motion.withOverrides(overrides.motion),
    )
}

private fun Long?.orColor(fallback: Color): Color = if (this != null) Color(this) else fallback

private fun DeckCodePalette.withOverrides(o: CodePaletteOverrides?): DeckCodePalette {
    if (o == null) return this
    return copy(
        background = o.backgroundArgb.orColor(background),
        foreground = o.foregroundArgb.orColor(foreground),
        keyword = o.keywordArgb.orColor(keyword),
        string = o.stringArgb.orColor(string),
        comment = o.commentArgb.orColor(comment),
        number = o.numberArgb.orColor(number),
        type = o.typeArgb.orColor(type),
        functionDeclaration = o.functionDeclarationArgb.orColor(functionDeclaration),
        functionCall = o.functionCallArgb.orColor(functionCall),
        property = o.propertyArgb.orColor(property),
        annotation = o.annotationArgb.orColor(annotation),
        punctuation = o.punctuationArgb.orColor(punctuation),
        invalid = o.invalidArgb.orColor(invalid),
    )
}

private fun DeckColors.withOverrides(o: ColorTokenOverrides): DeckColors {
    if (o == ColorTokenOverrides()) return this
    return copy(
        background = o.backgroundArgb.orColor(background),
        surface = o.surfaceArgb.orColor(surface),
        elevatedSurface = o.elevatedSurfaceArgb.orColor(elevatedSurface),
        foreground = o.foregroundArgb.orColor(foreground),
        muted = o.mutedArgb.orColor(muted),
        subtle = o.subtleArgb.orColor(subtle),
        accent = o.accentArgb.orColor(accent),
        secondary = o.secondaryArgb.orColor(secondary),
        positive = o.positiveArgb.orColor(positive),
        warning = o.warningArgb.orColor(warning),
        danger = o.dangerArgb.orColor(danger),
        border = o.borderArgb.orColor(border),
        chipBackground = o.chipBackgroundArgb.orColor(chipBackground),
        spotlightDimAlpha = o.spotlightDimAlpha?.coerceIn(0.05f, 1f) ?: spotlightDimAlpha,
        code = code.withOverrides(o.code),
        onAccent = o.onAccentArgb.orColor(onAccent),
        motionPathPreview = o.motionPathPreviewArgb.orColor(motionPathPreview),
    )
}

private fun TextStyle.withOverride(
    o: TextStyleTokenOverride?,
    scale: Float,
): TextStyle {
    val baseSize = (o?.fontSizeSp ?: fontSize.value) * scale
    val baseLine = (o?.lineHeightSp ?: lineHeight.value) * scale
    val weight = o?.fontWeight?.let { FontWeight(it.coerceIn(100, 900)) } ?: fontWeight
    val letter = o?.letterSpacingSp?.sp ?: letterSpacing
    return copy(
        fontSize = baseSize.coerceAtLeast(6f).sp,
        lineHeight = baseLine.coerceAtLeast(8f).sp,
        fontWeight = weight,
        letterSpacing = letter,
    )
}

private fun DeckTypography.withOverrides(o: TypographyTokenOverrides): DeckTypography {
    if (o == TypographyTokenOverrides()) return this
    val scale = (o.globalFontScale ?: 1f).coerceIn(0.5f, 2.5f)
    return copy(
        hero = hero.withOverride(o.hero, scale),
        title = title.withOverride(o.title, scale),
        heading = heading.withOverride(o.heading, scale),
        subheading = subheading.withOverride(o.subheading, scale),
        body = body.withOverride(o.body, scale),
        bodyEmphasis = bodyEmphasis.withOverride(o.bodyEmphasis, scale),
        callout = callout.withOverride(o.callout, scale),
        eyebrow = eyebrow.withOverride(o.eyebrow, scale),
        code = code.withOverride(o.code, scale),
        codeEmphasis = codeEmphasis.withOverride(o.codeEmphasis, scale),
        stamp = stamp.withOverride(o.stamp, scale),
    )
}

private fun DeckStyles.withOverrides(o: StyleTokenOverrides): DeckStyles {
    if (o == StyleTokenOverrides()) return this
    return copy(
        slidePadding = o.slidePaddingDp?.dp ?: slidePadding,
        codeCard = codeCard.copy(
            cornerRadius = o.codeCardCornerRadiusDp?.dp ?: codeCard.cornerRadius,
            padding = o.codeCardPaddingDp?.dp ?: codeCard.padding,
            borderWidth = o.codeCardBorderWidthDp?.dp ?: codeCard.borderWidth,
        ),
        calloutBox = calloutBox.copy(
            cornerRadius = o.calloutCornerRadiusDp?.dp ?: calloutBox.cornerRadius,
            strokeWidth = o.calloutStrokeWidthDp?.dp ?: calloutBox.strokeWidth,
            dashOn = o.calloutDashOnDp?.dp ?: calloutBox.dashOn,
            dashOff = o.calloutDashOffDp?.dp ?: calloutBox.dashOff,
            inset = o.calloutInsetDp?.dp ?: calloutBox.inset,
        ),
        leaderArrow = leaderArrow.copy(
            strokeWidth = o.leaderArrowStrokeWidthDp?.dp ?: leaderArrow.strokeWidth,
            headLength = o.leaderArrowHeadLengthDp?.dp ?: leaderArrow.headLength,
            headAngleDegrees = o.leaderArrowHeadAngleDegrees ?: leaderArrow.headAngleDegrees,
            gap = o.leaderArrowGapDp?.dp ?: leaderArrow.gap,
        ),
        strikeout = strikeout.copy(
            strokeWidth = o.strikeoutStrokeWidthDp?.dp ?: strikeout.strokeWidth,
            horizontalOverhang = o.strikeoutHorizontalOverhangDp?.dp ?: strikeout.horizontalOverhang,
            verticalTiltFraction = o.strikeoutVerticalTiltFraction ?: strikeout.verticalTiltFraction,
        ),
        stamp = stamp.copy(
            cornerRadius = o.stampCornerRadiusDp?.dp ?: stamp.cornerRadius,
            horizontalPadding = o.stampHorizontalPaddingDp?.dp ?: stamp.horizontalPadding,
            verticalPadding = o.stampVerticalPaddingDp?.dp ?: stamp.verticalPadding,
            borderWidth = o.stampBorderWidthDp?.dp ?: stamp.borderWidth,
        ),
        detourBadge = detourBadge.copy(
            diamondSize = o.detourDiamondSizeDp?.dp ?: detourBadge.diamondSize,
            cornerRadius = o.detourCornerRadiusDp?.dp ?: detourBadge.cornerRadius,
            strokeWidth = o.detourStrokeWidthDp?.dp ?: detourBadge.strokeWidth,
        ),
        loupe = loupe.copy(
            diameter = o.loupeDiameterDp?.dp ?: loupe.diameter,
            ringWidth = o.loupeRingWidthDp?.dp ?: loupe.ringWidth,
            defaultZoom = o.loupeDefaultZoom ?: loupe.defaultZoom,
            connectorDashOn = o.loupeConnectorDashOnDp?.dp ?: loupe.connectorDashOn,
            connectorDashOff = o.loupeConnectorDashOffDp?.dp ?: loupe.connectorDashOff,
        ),
    )
}

private fun DeckMotion.withOverrides(o: MotionTokenOverrides): DeckMotion {
    if (o == MotionTokenOverrides()) return this
    return copy(
        fadeMoveDurationMs = o.fadeMoveDurationMs?.coerceAtLeast(16) ?: fadeMoveDurationMs,
        fadeMoveDistance = o.fadeMoveDistanceDp?.dp ?: fadeMoveDistance,
        dissolveDurationMs = o.dissolveDurationMs?.coerceAtLeast(16) ?: dissolveDurationMs,
        fromDarknessDurationMs = o.fromDarknessDurationMs?.coerceAtLeast(16) ?: fromDarknessDurationMs,
        fromDarknessInitialScale = o.fromDarknessInitialScale?.coerceIn(0.1f, 1.5f) ?: fromDarknessInitialScale,
        lineDrawDurationMs = o.lineDrawDurationMs?.coerceAtLeast(16) ?: lineDrawDurationMs,
        staggerStepMs = o.staggerStepMs?.coerceAtLeast(0) ?: staggerStepMs,
        spotlightDurationMs = o.spotlightDurationMs?.coerceAtLeast(16) ?: spotlightDurationMs,
        loupeDurationMs = o.loupeDurationMs?.coerceAtLeast(16) ?: loupeDurationMs,
        sharedBoundsDurationMs = o.sharedBoundsDurationMs?.coerceAtLeast(16) ?: sharedBoundsDurationMs,
        warpTokenDurationMs = o.warpTokenDurationMs?.coerceAtLeast(16) ?: warpTokenDurationMs,
        popDampingRatio = o.popDampingRatio?.coerceIn(0.2f, 1.5f) ?: popDampingRatio,
        popStiffness = o.popStiffness?.coerceIn(50f, 4_000f) ?: popStiffness,
        popInitialScale = o.popInitialScale?.coerceIn(0.1f, 1.5f) ?: popInitialScale,
        popOvershootScale = o.popOvershootScale?.coerceIn(0.8f, 1.5f) ?: popOvershootScale,
    )
}
