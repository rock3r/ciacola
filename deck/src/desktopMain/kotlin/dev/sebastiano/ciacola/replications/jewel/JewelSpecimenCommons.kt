package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.stageInteractive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.default
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.Text

/**
 * Isolated JetBrains Jewel IntUi sub-theme container for live slide specimens.
 *
 * Wraps the specimen in its own [IntUiTheme] (`lightThemeDefinition` or `darkThemeDefinition`)
 * and paints an explicit surface container while updating [LocalEffectiveBackgroundColor] so
 * light IntelliJ dialogs render crisply over Jewel Talk's dark `#29223A` canvas and never
 * inherit or leak Studio's outer chrome theme.
 */
@Composable
fun IsolatedJewelSurface(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    surfaceColor: Color? = null,
    borderColor: Color? = null,
    cornerRadius: Dp = 10.dp,
    interactiveId: String? = null,
    interactiveLabel: String? = null,
    isInteractive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val themeDefinition = if (isDark) {
        JewelTheme.darkThemeDefinition()
    } else {
        JewelTheme.lightThemeDefinition()
    }
    val resolvedSurface = surfaceColor ?: if (isDark) Color(0xFF1E1F22) else Color(0xFFF7F8FA)
    val resolvedBorder = borderColor ?: if (isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val shape = RoundedCornerShape(cornerRadius)
    val interactiveModifier = if (isInteractive && interactiveId != null) {
        Modifier.stageInteractive(id = interactiveId, label = interactiveLabel ?: interactiveId)
    } else {
        Modifier
    }

    IntUiTheme(
        theme = themeDefinition,
        styling = ComponentStyling.default(),
        swingCompatMode = false,
    ) {
        CompositionLocalProvider(LocalEffectiveBackgroundColor provides resolvedSurface) {
            Box(
                modifier = modifier
                    .then(interactiveModifier)
                    .clip(shape)
                    .background(resolvedSurface, shape)
                    .border(1.dp, resolvedBorder, shape)
                    .clipToBounds(),
            ) {
                content()
            }
        }
    }
}

/**
 * macOS-styled Jewel / IntelliJ window frame with traffic-light dots and customizable title bar.
 */
@Composable
fun JewelWindowFrame(
    title: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    darkTitleBar: Boolean = isDark,
    surfaceColor: Color? = null,
    interactiveId: String? = null,
    interactiveLabel: String? = null,
    isInteractive: Boolean = true,
    leadingTitleContent: @Composable RowScope.() -> Unit = {},
    trailingTitleContent: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val barBg = if (darkTitleBar) Color(0xFF2B2D30) else Color(0xFFF2F3F5)
    val barText = if (darkTitleBar) Color(0xFFDFE1E5) else Color(0xFF27282E)
    val barDivider = if (darkTitleBar) Color(0xFF1E1F22) else Color(0xFFDCDDE2)

    IsolatedJewelSurface(
        isDark = isDark,
        modifier = modifier,
        surfaceColor = surfaceColor ?: if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF),
        interactiveId = interactiveId,
        interactiveLabel = interactiveLabel,
        isInteractive = isInteractive,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalEffectiveBackgroundColor provides barBg) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(barBg)
                        .padding(horizontal = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        MacTrafficLights()
                        Spacer(Modifier.width(4.dp))
                        leadingTitleContent()
                    }
                    Text(
                        text = title,
                        color = barText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        trailingTitleContent()
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(barDivider),
            )
            content()
        }
    }
}

@Composable
internal fun MacTrafficLights(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5F57)))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFEBC2E)))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF28C840)))
    }
}

// ---------------------------------------------------------------------------
// Deterministic Animation & Analytical Spring Helpers
// ---------------------------------------------------------------------------

/** Normalizes [loopMillis] into `[0f, 1f)` over [periodMs]. */
internal fun loopProgress(loopMillis: Long, periodMs: Long): Float {
    if (periodMs <= 0L) return 0f
    val wrapped = ((loopMillis % periodMs) + periodMs) % periodMs
    return wrapped.toFloat() / periodMs.toFloat()
}

/** Maps normalized progress [t] into `[0f, 1f]` across `[start, end]`. */
internal fun segmentProgress(t: Float, start: Float, end: Float): Float {
    if (end <= start) return if (t >= start) 1f else 0f
    return ((t - start) / (end - start)).coerceIn(0f, 1f)
}

internal fun easeInOutCubic(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f).let { it * it * it }) / 2f
}

internal fun easeOutCubic(t: Float): Float {
    val x = 1f - t.coerceIn(0f, 1f)
    return 1f - x * x * x
}

internal fun easeInOutSine(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return (-(cos(PI * x) - 1.0) / 2.0).toFloat()
}

/**
 * Analytical damped harmonic oscillator response `y(tSeconds)` starting at `0` with target `1`.
 *
 * Matches Compose's `spring(dampingRatio, stiffness)` physics deterministically from elapsed
 * time without requiring wall-clock `Animatable` coroutines.
 */
internal fun analyticalSpring(
    elapsedSeconds: Float,
    dampingRatio: Float = 0.6f,
    stiffness: Float = 400f,
): Float {
    if (elapsedSeconds <= 0f) return 0f
    val omega = sqrt(stiffness.coerceAtLeast(1f))
    val zeta = dampingRatio.coerceAtLeast(0.05f)
    val t = elapsedSeconds.toDouble()
    val result = when {
        zeta < 0.999f -> {
            val omegaD = omega * sqrt(1.0 - zeta * zeta)
            val decay = exp(-zeta * omega * t)
            1.0 - decay * (cos(omegaD * t) + (zeta / sqrt(1.0 - zeta * zeta)) * sin(omegaD * t))
        }
        zeta <= 1.001f -> {
            val decay = exp(-omega * t)
            1.0 - decay * (1.0 + omega * t)
        }
        else -> {
            val s = sqrt((zeta * zeta - 1.0))
            val r1 = -omega * (zeta - s)
            val r2 = -omega * (zeta + s)
            val c2 = r1 / (r1 - r2)
            val c1 = 1.0 - c2
            1.0 - (c1 * exp(r1 * t) + c2 * exp(r2 * t))
        }
    }
    return result.toFloat()
}

/** Smoothly types [fullText] proportionally to [progress] in `[0f, 1f]`. */
internal fun deterministicTypewriter(fullText: String, progress: Float): String {
    val count = (fullText.length * progress.coerceIn(0f, 1f)).toInt().coerceIn(0, fullText.length)
    return fullText.substring(0, count)
}

internal fun caretVisible(loopMillis: Long, blinkHalfPeriodMs: Long = 450L): Boolean {
    if (blinkHalfPeriodMs <= 0L) return true
    val slot = ((loopMillis / blinkHalfPeriodMs) % 2L + 2L) % 2L
    return slot == 0L
}

// ---------------------------------------------------------------------------
// Shared Vector Marks & Syntax Highlighting
// ---------------------------------------------------------------------------

/** Crisp 4-point Gemini sparkle emblem rendered via Compose Canvas. */
@Composable
internal fun GeminiSparkleMark(
    size: Dp,
    modifier: Modifier = Modifier,
    rotationDeg: Float = 0f,
    brush: Brush = Brush.linearGradient(
        listOf(Color(0xFF4285F4), Color(0xFF7B61FF), Color(0xFFD96570)),
    ),
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val path = Path().apply {
            moveTo(cx, 0f)
            cubicTo(cx, cy * 0.62f, cx + (w - cx) * 0.38f, cy, w, cy)
            cubicTo(cx + (w - cx) * 0.38f, cy, cx, cy + (h - cy) * 0.38f, cx, h)
            cubicTo(cx, cy + (h - cy) * 0.38f, cx * 0.62f, cy, 0f, cy)
            cubicTo(cx * 0.62f, cy, cx, cy * 0.62f, cx, 0f)
            close()
        }
        rotate(degrees = rotationDeg, pivot = Offset(cx, cy)) {
            drawPath(path = path, brush = brush)
        }
    }
}

/** Deterministic IntelliJ 8-petal spinner driven purely by [loopMillis]. */
@Composable
internal fun DeterministicIdeSpinner(
    loopMillis: Long,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val stepIndex = (((loopMillis / 100L) % 8L + 8L) % 8L).toInt()
    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outerR = this.size.minDimension * 0.44f
        val innerR = this.size.minDimension * 0.22f
        val stroke = (this.size.minDimension * 0.11f).coerceAtLeast(1.4f)
        for (i in 0 until 8) {
            val angleRad = (i * 45.0) * PI / 180.0
            val rel = (i - stepIndex + 8) % 8
            val alpha = 0.20f + 0.80f * (1f - rel / 8f)
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(cx + cosA * innerR, cy + sinA * innerR),
                end = Offset(cx + cosA * outerR, cy + sinA * outerR),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Lightweight deterministic Kotlin / Gradle / JSON syntax highlighter for IntUi Light & Dark. */
internal object IdeCodeHighlighter {
    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "data", "object", "interface", "package", "import",
        "return", "if", "else", "when", "for", "while", "true", "false", "null", "override",
        "private", "internal", "public", "by", "in", "is", "as", "this", "super",
    )

    private val tokenRegex = Regex(
        pattern = """(//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|@[A-Za-z_][A-Za-z0-9_]*|\b[0-9]+(?:\.[0-9]+)?[fFdDlL]?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)""",
    )

    fun highlight(code: String, isDark: Boolean): AnnotatedString {
        val keywordColor = if (isDark) Color(0xFFCF8E6D) else Color(0xFF0033B3)
        val stringColor = if (isDark) Color(0xFF6AAB73) else Color(0xFF067D17)
        val commentColor = if (isDark) Color(0xFF7A7E85) else Color(0xFF8C8C8C)
        val annotationColor = if (isDark) Color(0xFFB3AE60) else Color(0xFF9E880D)
        val numberColor = if (isDark) Color(0xFF2AACB8) else Color(0xFF1750EB)
        val typeColor = if (isDark) Color(0xFF56A8F5) else Color(0xFF00627A)
        val plainColor = if (isDark) Color(0xFFBCBEC4) else Color(0xFF080808)

        return buildAnnotatedString {
            var cursor = 0
            for (match in tokenRegex.findAll(code)) {
                if (match.range.first > cursor) {
                    withStyle(SpanStyle(color = plainColor, fontFamily = FontFamily.Monospace)) {
                        append(code.substring(cursor, match.range.first))
                    }
                }
                val token = match.value
                val style = when {
                    token.startsWith("//") || token.startsWith("/*") -> SpanStyle(
                        color = commentColor,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Monospace,
                    )
                    token.startsWith("\"") -> SpanStyle(
                        color = stringColor,
                        fontFamily = FontFamily.Monospace,
                    )
                    token.startsWith("@") -> SpanStyle(
                        color = annotationColor,
                        fontFamily = FontFamily.Monospace,
                    )
                    token.first().isDigit() -> SpanStyle(
                        color = numberColor,
                        fontFamily = FontFamily.Monospace,
                    )
                    token in kotlinKeywords -> SpanStyle(
                        color = keywordColor,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    token.first().isUpperCase() -> SpanStyle(
                        color = typeColor,
                        fontFamily = FontFamily.Monospace,
                    )
                    else -> SpanStyle(
                        color = plainColor,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                withStyle(style) { append(token) }
                cursor = match.range.last + 1
            }
            if (cursor < code.length) {
                withStyle(SpanStyle(color = plainColor, fontFamily = FontFamily.Monospace)) {
                    append(code.substring(cursor))
                }
            }
        }
    }
}
