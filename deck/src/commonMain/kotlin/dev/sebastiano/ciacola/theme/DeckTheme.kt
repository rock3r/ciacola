package dev.sebastiano.ciacola.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.text.highlight.CodeStyle
import dev.sebastiano.ciacola.deck.generated.resources.Inter_Black
import dev.sebastiano.ciacola.deck.generated.resources.Inter_Bold
import dev.sebastiano.ciacola.deck.generated.resources.Inter_ExtraBold
import dev.sebastiano.ciacola.deck.generated.resources.Inter_Light
import dev.sebastiano.ciacola.deck.generated.resources.Inter_Medium
import dev.sebastiano.ciacola.deck.generated.resources.Inter_Regular
import dev.sebastiano.ciacola.deck.generated.resources.Inter_SemiBold
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_Bold
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_BoldItalic
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_Italic
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_Medium
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_MediumItalic
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_Regular
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_SemiBold
import dev.sebastiano.ciacola.deck.generated.resources.JetBrainsMono_SemiBoldItalic
import dev.sebastiano.ciacola.deck.generated.resources.Res
import org.jetbrains.compose.resources.Font

enum class DeckThemePreset {
    StageDark,
    KeynoteLight,
}

@Immutable
data class DeckCodePalette(
    val background: Color,
    val foreground: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val functionDeclaration: Color,
    val functionCall: Color,
    val property: Color,
    val annotation: Color,
    val punctuation: Color,
    val invalid: Color,
) {
    fun toCodeStyle(): CodeStyle = CodeStyle.build(SpanStyle(color = foreground)) {
        keyword = simple + SpanStyle(color = this@DeckCodePalette.keyword, fontWeight = FontWeight.SemiBold)
        string = simple + SpanStyle(color = this@DeckCodePalette.string)
        comment = simple + SpanStyle(color = this@DeckCodePalette.comment, fontStyle = FontStyle.Italic)
        number = simple + SpanStyle(color = this@DeckCodePalette.number)
        typeParameters = simple + SpanStyle(color = this@DeckCodePalette.type)
        functionDeclaration = simple + SpanStyle(color = this@DeckCodePalette.functionDeclaration, fontWeight = FontWeight.Bold)
        staticFunctionCall = simple + SpanStyle(color = this@DeckCodePalette.functionCall)
        extensionFunctionCall = simple + SpanStyle(color = this@DeckCodePalette.functionCall)
        property = simple + SpanStyle(color = this@DeckCodePalette.property)
        staticProperty = simple + SpanStyle(color = this@DeckCodePalette.property)
        annotation = simple + SpanStyle(color = this@DeckCodePalette.annotation)
        punctuation = simple + SpanStyle(color = this@DeckCodePalette.punctuation)
        namedArgument = simple + SpanStyle(color = this@DeckCodePalette.property)
        label = simple + SpanStyle(color = this@DeckCodePalette.annotation)
    }

    fun colorForWarpScope(scope: String): Color = when {
        scope.contains("annotation") || scope.contains("attribute") -> annotation
        scope.contains("keyword") || scope.contains("storage") -> keyword
        scope.contains("string") -> string
        scope.contains("comment") -> comment
        scope.contains("constant.numeric") || scope.contains("constants.numeric") || scope.contains("constant") -> number
        scope.contains("function") -> functionCall
        scope.contains("entity.name.type") || scope.contains("class") -> type
        scope.contains("property") || scope.contains("variable.other.property") -> property
        scope.contains("punctuation") -> punctuation
        scope.contains("invalid") -> invalid
        else -> foreground
    }
}

@Immutable
data class DeckColors(
    val background: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val foreground: Color,
    val muted: Color,
    val subtle: Color,
    val accent: Color,
    val secondary: Color,
    val positive: Color,
    val warning: Color,
    val danger: Color,
    val border: Color,
    val chipBackground: Color,
    val spotlightDimAlpha: Float,
    val code: DeckCodePalette,
    /** Text/icons placed on [accent] fills (e.g. the Shared Card label). */
    val onAccent: Color = background,
    /** Stage overlay colour for the motion-path (arc) preview drawn by the timeline studio. */
    val motionPathPreview: Color = Color(0xFFD59BF6),
)

@Immutable
data class DeckTypography(
    val displayFontFamily: FontFamily,
    val bodyFontFamily: FontFamily,
    val monoFontFamily: FontFamily,
    val hero: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val body: TextStyle,
    val bodyEmphasis: TextStyle,
    val callout: TextStyle,
    val eyebrow: TextStyle,
    val code: TextStyle,
    val codeEmphasis: TextStyle,
    val stamp: TextStyle,
)

@Immutable
data class CodeCardStyle(
    val cornerRadius: Dp = 20.dp,
    val padding: Dp = 36.dp,
    val borderWidth: Dp = 1.dp,
)

@Immutable
data class CalloutBoxStyle(
    val cornerRadius: Dp = 12.dp,
    val strokeWidth: Dp = 3.dp,
    val dashOn: Dp = 10.dp,
    val dashOff: Dp = 7.dp,
    val inset: Dp = 8.dp,
)

@Immutable
data class LeaderArrowStyle(
    val strokeWidth: Dp = 3.dp,
    val headLength: Dp = 14.dp,
    val headAngleDegrees: Float = 28f,
    val gap: Dp = 8.dp,
)

@Immutable
data class StrikeoutStyle(
    val strokeWidth: Dp = 4.dp,
    val horizontalOverhang: Dp = 6.dp,
    val verticalTiltFraction: Float = -0.06f,
)

@Immutable
data class StampStyle(
    val cornerRadius: Dp = 12.dp,
    val horizontalPadding: Dp = 24.dp,
    val verticalPadding: Dp = 10.dp,
    val borderWidth: Dp = 3.dp,
)

@Immutable
data class DetourBadgeStyle(
    val diamondSize: Dp = 84.dp,
    val cornerRadius: Dp = 14.dp,
    val strokeWidth: Dp = 3.dp,
)

@Immutable
data class LoupeStyle(
    val diameter: Dp = 260.dp,
    val ringWidth: Dp = 3.dp,
    val defaultZoom: Float = 3.2f,
    val connectorDashOn: Dp = 4.dp,
    val connectorDashOff: Dp = 5.dp,
)

@Immutable
data class DeckStyles(
    val slidePadding: Dp = 64.dp,
    val codeCard: CodeCardStyle = CodeCardStyle(),
    val calloutBox: CalloutBoxStyle = CalloutBoxStyle(),
    val leaderArrow: LeaderArrowStyle = LeaderArrowStyle(),
    val strikeout: StrikeoutStyle = StrikeoutStyle(),
    val stamp: StampStyle = StampStyle(),
    val detourBadge: DetourBadgeStyle = DetourBadgeStyle(),
    val loupe: LoupeStyle = LoupeStyle(),
)

@Immutable
data class DeckMotion(
    val standardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
    val fadeMoveDurationMs: Int = 350,
    val fadeMoveDistance: Dp = 24.dp,
    val dissolveDurationMs: Int = 300,
    val fromDarknessDurationMs: Int = 380,
    val fromDarknessInitialScale: Float = 0.88f,
    val lineDrawDurationMs: Int = 340,
    val staggerStepMs: Int = 55,
    val spotlightDurationMs: Int = 260,
    val loupeDurationMs: Int = 380,
    val sharedBoundsDurationMs: Int = 550,
    val warpTokenDurationMs: Int = 280,
    val popDampingRatio: Float = 0.65f,
    val popStiffness: Float = 520f,
    val popInitialScale: Float = 0.72f,
    val popOvershootScale: Float = 1.06f,
) {
    fun <T> fadeMoveSpec(): FiniteAnimationSpec<T> = tween(fadeMoveDurationMs, easing = standardEasing)
    fun <T> lineDrawSpec(): FiniteAnimationSpec<T> = tween(lineDrawDurationMs, easing = standardEasing)
    fun <T> popSpringSpec(): SpringSpec<T> = spring(dampingRatio = popDampingRatio, stiffness = popStiffness)
}

@Immutable
data class DeckTheme(
    val name: String,
    val isLight: Boolean,
    val colors: DeckColors,
    val typography: DeckTypography,
    val styles: DeckStyles = DeckStyles(),
    val motion: DeckMotion = DeckMotion(),
) {
    fun toMaterialColors(): Colors = if (isLight) {
        lightColors(
            primary = colors.accent,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = colors.onAccent,
            onSecondary = Color(0xFF101318),
            onBackground = colors.foreground,
            onSurface = colors.foreground,
            error = colors.danger,
        )
    } else {
        darkColors(
            primary = colors.accent,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = colors.onAccent,
            onSecondary = colors.background,
            onBackground = colors.foreground,
            onSurface = colors.foreground,
            error = colors.danger,
        )
    }

    fun toMaterialTypography(): Typography = Typography(
        defaultFontFamily = typography.bodyFontFamily,
        h1 = typography.title,
        h2 = typography.heading,
        h3 = typography.subheading,
        body1 = typography.body,
        body2 = typography.callout,
        caption = typography.eyebrow,
    )
}

object DeckThemes {
    private val DarkCodeCardPalette = DeckCodePalette(
        background = Color(0xFF171B24),
        foreground = Color(0xFFF4F1EA),
        keyword = Color(0xFFFF6B5E),
        string = Color(0xFF71D7C5),
        comment = Color(0xFF838C9E),
        number = Color(0xFFF5B864),
        type = Color(0xFFD59BF6),
        functionDeclaration = Color(0xFFFFD166),
        functionCall = Color(0xFF8CB4FF),
        property = Color(0xFFC7B8FA),
        annotation = Color(0xFFFFD166),
        punctuation = Color(0xFFA9B0C0),
        invalid = Color(0xFFFA6675),
    )

    // Inspired by Shaders Quickie & Not Just Plain Text: warm light stage + dark plum/charcoal code card & teal callouts.
    private val KeynoteCodeCardPalette = DeckCodePalette(
        background = Color(0xFF2E282C),
        foreground = Color(0xFFEDE8E6),
        keyword = Color(0xFFE57C58),
        string = Color(0xFF63D9B6),
        comment = Color(0xFF9C9398),
        number = Color(0xFF53D6C8),
        type = Color(0xFFF2B866),
        functionDeclaration = Color(0xFFFFCF70),
        functionCall = Color(0xFF7EC4FA),
        property = Color(0xFFD9A6F5),
        annotation = Color(0xFFF2B866),
        punctuation = Color(0xFFB5ACB0),
        invalid = Color(0xFFFF6E7A),
    )

    val StageDarkColors = DeckColors(
        background = Color(0xFF0B0D12),
        surface = Color(0xFF171B24),
        elevatedSurface = Color(0xFF202634),
        foreground = Color(0xFFF4F1EA),
        muted = Color(0xFFA9B0C0),
        subtle = Color(0xFF6E7687),
        accent = Color(0xFFFF6B5E),
        secondary = Color(0xFF71D7C5),
        positive = Color(0xFF66D19E),
        warning = Color(0xFFFFC857),
        danger = Color(0xFFFF6B6B),
        border = Color(0xFF2B3345),
        chipBackground = Color(0xFF232A3A),
        // Dimmed comparison text keeps >= 3:1 (large text) against the stage.
        spotlightDimAlpha = 0.4f,
        code = DarkCodeCardPalette,
        onAccent = Color(0xFF0B0D12),
        motionPathPreview = Color(0xFFD59BF6),
    )

    val KeynoteLightColors = DeckColors(
        background = Color(0xFFEBEBEB),
        surface = Color(0xFFF8F8F8),
        elevatedSurface = Color(0xFFFFFFFF),
        foreground = Color(0xFF242124),
        muted = Color(0xFF6B666B),
        subtle = Color(0xFF9A949A),
        // Slightly deeper terracotta than #D15322: white on accent reaches 4.9:1 (was 4.2:1) and the
        // stamp's accent-on-14%-tint text 3.4:1 (was 3.0:1).
        accent = Color(0xFFC24A1C),
        secondary = Color(0xFF1BA890),
        positive = Color(0xFF267D4D),
        warning = Color(0xFFD99B16),
        danger = Color(0xFFC93838),
        border = Color(0xFFD2CED2),
        chipBackground = Color(0xFFDEDADE),
        // Dimmed comparison text keeps >= 3:1 (large text) instead of washing out to ~1.4:1.
        spotlightDimAlpha = 0.55f,
        code = KeynoteCodeCardPalette,
        onAccent = Color.White,
        // A deep violet instead of pastel #D59BF6 (1.8:1), legible on the light stage (5.9:1).
        motionPathPreview = Color(0xFF7B2FBE),
    )

    fun buildTypography(
        displayFont: FontFamily = FontFamily.Default,
        bodyFont: FontFamily = displayFont,
        monoFont: FontFamily = FontFamily.Monospace,
    ): DeckTypography = DeckTypography(
        displayFontFamily = displayFont,
        bodyFontFamily = bodyFont,
        monoFontFamily = monoFont,
        hero = TextStyle(
            fontFamily = displayFont,
            fontSize = 90.sp,
            lineHeight = 96.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-2).sp,
        ),
        title = TextStyle(
            fontFamily = displayFont,
            fontSize = 72.sp,
            lineHeight = 78.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        ),
        heading = TextStyle(
            fontFamily = displayFont,
            fontSize = 46.sp,
            lineHeight = 54.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        subheading = TextStyle(
            fontFamily = displayFont,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Medium,
        ),
        body = TextStyle(
            fontFamily = bodyFont,
            fontSize = 30.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodyEmphasis = TextStyle(
            fontFamily = bodyFont,
            fontSize = 30.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        callout = TextStyle(
            fontFamily = bodyFont,
            fontSize = 25.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.Medium,
        ),
        eyebrow = TextStyle(
            fontFamily = displayFont,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold,
        ),
        code = TextStyle(
            fontFamily = monoFont,
            fontSize = 27.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Normal,
        ),
        codeEmphasis = TextStyle(
            fontFamily = monoFont,
            fontSize = 27.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
        ),
        stamp = TextStyle(
            fontFamily = displayFont,
            fontSize = 52.sp,
            lineHeight = 58.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
        ),
    )

    fun stageDark(
        displayFont: FontFamily = FontFamily.Default,
        bodyFont: FontFamily = displayFont,
        monoFont: FontFamily = FontFamily.Monospace,
    ): DeckTheme = DeckTheme(
        name = "Stage Dark",
        isLight = false,
        colors = StageDarkColors,
        typography = buildTypography(displayFont, bodyFont, monoFont),
    )

    fun keynoteLight(
        displayFont: FontFamily = FontFamily.Default,
        bodyFont: FontFamily = displayFont,
        monoFont: FontFamily = FontFamily.Monospace,
    ): DeckTheme = DeckTheme(
        name = "Keynote Light",
        isLight = true,
        colors = KeynoteLightColors,
        typography = buildTypography(displayFont, bodyFont, monoFont),
    )

    fun forPreset(
        preset: DeckThemePreset,
        displayFont: FontFamily = FontFamily.Default,
        bodyFont: FontFamily = displayFont,
        monoFont: FontFamily = FontFamily.Monospace,
    ): DeckTheme = when (preset) {
        DeckThemePreset.StageDark -> stageDark(displayFont, bodyFont, monoFont)
        DeckThemePreset.KeynoteLight -> keynoteLight(displayFont, bodyFont, monoFont)
    }

    @Composable
    fun rememberBundledDeckTheme(
        preset: DeckThemePreset = DeckThemePreset.StageDark,
    ): DeckTheme {
        val interLight = Font(Res.font.Inter_Light, FontWeight.Light, FontStyle.Normal)
        val interRegular = Font(Res.font.Inter_Regular, FontWeight.Normal, FontStyle.Normal)
        val interMedium = Font(Res.font.Inter_Medium, FontWeight.Medium, FontStyle.Normal)
        val interSemiBold = Font(Res.font.Inter_SemiBold, FontWeight.SemiBold, FontStyle.Normal)
        val interBold = Font(Res.font.Inter_Bold, FontWeight.Bold, FontStyle.Normal)
        val interExtraBold = Font(Res.font.Inter_ExtraBold, FontWeight.ExtraBold, FontStyle.Normal)
        val interBlack = Font(Res.font.Inter_Black, FontWeight.Black, FontStyle.Normal)

        val monoRegular = Font(Res.font.JetBrainsMono_Regular, FontWeight.Normal, FontStyle.Normal)
        val monoItalic = Font(Res.font.JetBrainsMono_Italic, FontWeight.Normal, FontStyle.Italic)
        val monoMedium = Font(Res.font.JetBrainsMono_Medium, FontWeight.Medium, FontStyle.Normal)
        val monoMediumItalic = Font(Res.font.JetBrainsMono_MediumItalic, FontWeight.Medium, FontStyle.Italic)
        val monoSemiBold = Font(Res.font.JetBrainsMono_SemiBold, FontWeight.SemiBold, FontStyle.Normal)
        val monoSemiBoldItalic = Font(Res.font.JetBrainsMono_SemiBoldItalic, FontWeight.SemiBold, FontStyle.Italic)
        val monoBold = Font(Res.font.JetBrainsMono_Bold, FontWeight.Bold, FontStyle.Normal)
        val monoBoldItalic = Font(Res.font.JetBrainsMono_BoldItalic, FontWeight.Bold, FontStyle.Italic)

        val sansFamily = remember(
            interLight,
            interRegular,
            interMedium,
            interSemiBold,
            interBold,
            interExtraBold,
            interBlack,
        ) {
            FontFamily(
                interLight,
                interRegular,
                interMedium,
                interSemiBold,
                interBold,
                interExtraBold,
                interBlack,
            )
        }

        val monoFamily = remember(
            monoRegular,
            monoItalic,
            monoMedium,
            monoMediumItalic,
            monoSemiBold,
            monoSemiBoldItalic,
            monoBold,
            monoBoldItalic,
        ) {
            FontFamily(
                monoRegular,
                monoItalic,
                monoMedium,
                monoMediumItalic,
                monoSemiBold,
                monoSemiBoldItalic,
                monoBold,
                monoBoldItalic,
            )
        }

        return remember(preset, sansFamily, monoFamily) {
            when (preset) {
                DeckThemePreset.StageDark -> stageDark(
                    displayFont = sansFamily,
                    bodyFont = sansFamily,
                    monoFont = monoFamily,
                )
                DeckThemePreset.KeynoteLight -> keynoteLight(
                    displayFont = sansFamily,
                    bodyFont = sansFamily,
                    monoFont = monoFamily,
                )
            }
        }
    }
}

val LocalDeckTheme = staticCompositionLocalOf<DeckTheme> {
    DeckThemes.stageDark()
}

@Composable
fun DeckThemeProvider(
    theme: DeckTheme,
    content: @Composable () -> Unit,
) {
    val materialColors = remember(theme) { theme.toMaterialColors() }
    val materialTypography = remember(theme) { theme.toMaterialTypography() }
    CompositionLocalProvider(LocalDeckTheme provides theme) {
        MaterialTheme(
            colors = materialColors,
            typography = materialTypography,
            content = content,
        )
    }
}
