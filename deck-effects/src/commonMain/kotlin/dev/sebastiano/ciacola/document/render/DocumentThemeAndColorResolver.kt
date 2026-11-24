package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.GradientSpec
import dev.sebastiano.ciacola.document.GradientType
import dev.sebastiano.ciacola.document.SlidePaletteSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.ThemeMotionDefaults
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.isLightThemePreset
import dev.sebastiano.ciacola.document.resolveDeckCodeScheme
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectDirection8
import dev.sebastiano.ciacola.effects.v2.IrisDirection
import dev.sebastiano.ciacola.effects.v2.SlideTransitionV2
import dev.sebastiano.ciacola.theme.DeckCodePalette
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.SlidePalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Resolves the active [DeckThemeSpec] from [document] using [themeName] or `document.defaultTheme`. */
fun resolveActiveThemeSpec(
    document: DeckDocument,
    themeName: String? = null,
): DeckThemeSpec? {
    val effectiveName = themeName ?: document.defaultTheme
    return effectiveName?.let { document.themes[it] } ?: document.themes.values.firstOrNull()
}

/**
 * Resolves the effective [TransitionSpec] for [slide], honoring `slide.transition`, then the
 * active theme's `motion.transition`, defaulting to [TransitionSpec] (`dissolve`).
 */
fun effectiveSlideTransition(
    slide: SlideSpec,
    activeThemeSpec: DeckThemeSpec?,
): TransitionSpec = slide.transition ?: activeThemeSpec?.motion?.transition ?: TransitionSpec()

/**
 * Resolves the active [DeckTheme] from a [DeckDocument] and [themeName] (preset + code scheme +
 * color overrides).
 */
fun resolveDeckTheme(
    document: DeckDocument,
    themeName: String? = null,
): DeckTheme {
    val spec = resolveActiveThemeSpec(document, themeName)
    val base = if (isLightThemePreset(spec?.preset)) {
        DeckThemes.keynoteLight()
    } else {
        DeckThemes.stageDark()
    }
    if (spec == null) return base

    val effectiveScheme = resolveDeckCodeScheme(spec, isLight = base.isLight)
    val schemePalette = DeckCodePalette.fromScheme(effectiveScheme)
    val baseWithScheme = if (schemePalette != base.colors.code) {
        base.copy(colors = base.colors.copy(code = schemePalette))
    } else {
        base
    }
    if (spec.colors.isEmpty()) return baseWithScheme

    val resolver = DocumentColorResolver(
        themeSpec = spec,
        baseTheme = baseWithScheme,
        slidePaletteSpec = null,
    )
    val c = baseWithScheme.colors
    val overriddenCode = c.code.copy(
        background = resolver.resolveToken("codeBackground") ?: resolver.resolveToken("code.background") ?: c.code.background,
        foreground = resolver.resolveToken("codeForeground") ?: resolver.resolveToken("code.foreground") ?: c.code.foreground,
        keyword = resolver.resolveToken("code.keyword") ?: c.code.keyword,
        string = resolver.resolveToken("code.string") ?: c.code.string,
        comment = resolver.resolveToken("code.comment") ?: c.code.comment,
        number = resolver.resolveToken("code.number") ?: c.code.number,
        type = resolver.resolveToken("code.type") ?: c.code.type,
        functionDeclaration = resolver.resolveToken("code.functionDeclaration") ?: c.code.functionDeclaration,
        functionCall = resolver.resolveToken("code.functionCall") ?: resolver.resolveToken("code.function") ?: c.code.functionCall,
        property = resolver.resolveToken("code.property") ?: c.code.property,
        annotation = resolver.resolveToken("code.annotation") ?: c.code.annotation,
        punctuation = resolver.resolveToken("code.punctuation") ?: c.code.punctuation,
        invalid = resolver.resolveToken("code.invalid") ?: c.code.invalid,
        constant = resolver.resolveToken("code.constant") ?: c.code.constant,
        attribute = resolver.resolveToken("code.attribute") ?: c.code.attribute,
        tag = resolver.resolveToken("code.tag") ?: c.code.tag,
        operator = resolver.resolveToken("code.operator") ?: c.code.operator,
        preprocessor = resolver.resolveToken("code.preprocessor") ?: c.code.preprocessor,
    )
    val overridden = c.copy(
        background = resolver.resolveToken("canvas") ?: resolver.resolveToken("background") ?: c.background,
        surface = resolver.resolveToken("surface") ?: c.surface,
        elevatedSurface = resolver.resolveToken("elevatedSurface") ?: c.elevatedSurface,
        border = resolver.resolveToken("border") ?: c.border,
        foreground = resolver.resolveToken("ink") ?: resolver.resolveToken("foreground") ?: c.foreground,
        muted = resolver.resolveToken("mutedInk") ?: resolver.resolveToken("muted") ?: c.muted,
        subtle = resolver.resolveToken("subtle") ?: c.subtle,
        accent = resolver.resolveToken("accent") ?: c.accent,
        secondary = resolver.resolveToken("secondary") ?: c.secondary,
        positive = resolver.resolveToken("positive") ?: c.positive,
        warning = resolver.resolveToken("warning") ?: c.warning,
        danger = resolver.resolveToken("danger") ?: c.danger,
        onAccent = resolver.resolveToken("onAccent") ?: c.onAccent,
        code = overriddenCode,
    )
    return baseWithScheme.copy(colors = overridden)
}

/**
 * Single-step color & token resolver implementing `node -> slide palette -> active theme`.
 */
@Immutable
data class DocumentColorResolver(
    val themeSpec: DeckThemeSpec?,
    val baseTheme: DeckTheme,
    val slidePaletteSpec: SlidePaletteSpec? = null,
) {
    /** Resolves a literal color (`#RGB`, `#RRGGBB`, `#AARRGGBB`, `rgba(...)`) or semantic token. */
    fun resolve(spec: String?, fallback: Color = Color.Unspecified): Color {
        if (spec.isNullOrBlank()) return fallback
        val trimmed = spec.trim()
        parseLiteralColor(trimmed)?.let { return it }
        resolveToken(trimmed)?.let { return it }
        return fallback
    }

    /** Resolves [spec], returning `null` if unknown or blank. */
    fun resolveOrNull(spec: String?): Color? {
        val c = resolve(spec, fallback = Color.Unspecified)
        return if (c == Color.Unspecified) null else c
    }

    /** Resolves a semantic palette/theme color token (with single-hop indirection). */
    fun resolveToken(token: String, depth: Int = 0): Color? {
        if (depth > 3) return null
        val key = token.trim()

        // 1. Slide palette override
        val paletteRaw: String? = when (key) {
            "canvas", "background" -> slidePaletteSpec?.canvas
            "ink", "foreground" -> slidePaletteSpec?.ink
            "mutedInk", "muted" -> slidePaletteSpec?.mutedInk
            "accent" -> slidePaletteSpec?.accent
            "surface" -> slidePaletteSpec?.surface
            "border" -> slidePaletteSpec?.border
            else -> null
        }
        if (paletteRaw != null) {
            return parseLiteralColor(paletteRaw) ?: resolveToken(paletteRaw, depth + 1)
        }

        // 2. Document theme color map override
        val themeRaw: String? = themeSpec?.colors?.get(key) ?: when (key) {
            "background" -> themeSpec?.colors?.get("canvas")
            "canvas" -> themeSpec?.colors?.get("background")
            "foreground" -> themeSpec?.colors?.get("ink")
            "ink" -> themeSpec?.colors?.get("foreground")
            "muted" -> themeSpec?.colors?.get("mutedInk")
            "mutedInk" -> themeSpec?.colors?.get("muted")
            "codeBackground" -> themeSpec?.colors?.get("code.background")
            "code.background" -> themeSpec?.colors?.get("codeBackground")
            "codeForeground" -> themeSpec?.colors?.get("code.foreground")
            "code.foreground" -> themeSpec?.colors?.get("codeForeground")
            else -> null
        }
        if (themeRaw != null) {
            return parseLiteralColor(themeRaw) ?: resolveToken(themeRaw, depth + 1)
        }

        // 3. Base Kotlin theme preset
        val c = baseTheme.colors
        return when (key) {
            "canvas", "background" -> c.background
            "ink", "foreground" -> c.foreground
            "mutedInk", "muted" -> c.muted
            "subtle" -> c.subtle
            "accent" -> c.accent
            "secondary" -> c.secondary
            "positive" -> c.positive
            "warning" -> c.warning
            "danger" -> c.danger
            "surface" -> c.surface
            "elevatedSurface" -> c.elevatedSurface
            "border" -> c.border
            "onAccent" -> c.onAccent
            "codeBackground", "code.background" -> c.code.background
            "codeForeground", "code.foreground" -> c.code.foreground
            "code.keyword" -> c.code.keyword
            "code.string" -> c.code.string
            "code.comment" -> c.code.comment
            "code.number" -> c.code.number
            "code.type" -> c.code.type
            "code.functionDeclaration" -> c.code.functionDeclaration
            "code.functionCall", "code.function" -> c.code.functionCall
            "code.property" -> c.code.property
            "code.annotation" -> c.code.annotation
            "code.punctuation" -> c.code.punctuation
            "code.invalid" -> c.code.invalid
            "code.constant" -> c.code.constant
            "code.attribute" -> c.code.attribute
            "code.tag" -> c.code.tag
            "code.operator" -> c.code.operator
            "code.preprocessor" -> c.code.preprocessor
            "transparent" -> Color.Transparent
            "black" -> Color.Black
            "white" -> Color.White
            else -> null
        }
    }
}

/** Resolves a [SlidePalette] for [slide] if it declares a named palette. */
fun resolveSlidePalette(
    slide: SlideSpec,
    document: DeckDocument,
    colorResolver: DocumentColorResolver,
): SlidePalette? {
    val spec = slide.palette?.let { document.palettes[it] } ?: return null
    val base = colorResolver.baseTheme.colors
    return SlidePalette(
        canvas = colorResolver.resolve(spec.canvas, base.background),
        ink = spec.ink?.let { colorResolver.resolve(it, base.foreground) },
        mutedInk = spec.mutedInk?.let { colorResolver.resolve(it, base.muted) },
        accent = spec.accent?.let { colorResolver.resolve(it, base.accent) },
    )
}

/**
 * Returns the provably solid background [Color] of [slide], or `null` when the slide background is
 * a gradient or image.
 */
fun resolveEffectiveSolidBackground(
    slide: SlideSpec,
    colorResolver: DocumentColorResolver,
): Color? = when (val bg = slide.background) {
    null -> colorResolver.resolve("canvas", colorResolver.baseTheme.colors.background)
    is Fill.Solid -> colorResolver.resolveOrNull(bg.color) ?: colorResolver.baseTheme.colors.background
    is Fill.Gradient, is Fill.Image -> null
}

/** Parses `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB`, `rgb(...)`, or `rgba(...)` color literals. */
fun parseLiteralColor(raw: String): Color? {
    val s = raw.trim()
    if (s.startsWith("#")) {
        val hex = s.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                Color(red = r * 17, green = g * 17, blue = b * 17, alpha = 255)
            }
            4 -> {
                val a = hex[0].digitToIntOrNull(16) ?: return null
                val r = hex[1].digitToIntOrNull(16) ?: return null
                val g = hex[2].digitToIntOrNull(16) ?: return null
                val b = hex[3].digitToIntOrNull(16) ?: return null
                Color(red = r * 17, green = g * 17, blue = b * 17, alpha = a * 17)
            }
            6 -> {
                val rgb = hex.toLongOrNull(16) ?: return null
                Color((0xFF000000L or rgb).toInt())
            }
            8 -> {
                val argb = hex.toLongOrNull(16) ?: return null
                Color(argb.toInt())
            }
            else -> null
        }
    }
    if (s.startsWith("rgba(", ignoreCase = true) && s.endsWith(")")) {
        val parts = s.substring(5, s.length - 1).split(',').map { it.trim() }
        if (parts.size != 4) return null
        val r = parts[0].toFloatOrNull()?.coerceIn(0f, 255f) ?: return null
        val g = parts[1].toFloatOrNull()?.coerceIn(0f, 255f) ?: return null
        val b = parts[2].toFloatOrNull()?.coerceIn(0f, 255f) ?: return null
        val aRaw = parts[3].toFloatOrNull() ?: return null
        val a = if (aRaw > 1f) (aRaw / 255f).coerceIn(0f, 1f) else aRaw.coerceIn(0f, 1f)
        return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)
    }
    if (s.startsWith("rgb(", ignoreCase = true) && s.endsWith(")")) {
        val parts = s.substring(4, s.length - 1).split(',').map { it.trim() }
        if (parts.size != 3) return null
        val r = parts[0].toFloatOrNull()?.coerceIn(0f, 255f) ?: return null
        val g = parts[1].toFloatOrNull()?.coerceIn(0f, 255f) ?: return null
        val b = parts[2].toFloatOrNull()?.coerceIn(0f, 255f) ?: return null
        return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)
    }
    return null
}

/** Converts a document [Curve] into a v2 [CurveV2]. */
fun Curve.toCurveV2(): CurveV2 = when (this) {
    is Curve.Preset -> CurveV2.Preset(name)
    is Curve.Bezier -> CurveV2.Bezier(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
    is Curve.Spring -> CurveV2.Spring(damping = damping.toFloat(), stiffness = stiffness.toFloat())
}

/** Converts a document [TransitionSpec] into a v2 [SlideTransitionV2]. */
fun TransitionSpec.toSlideTransitionV2(
    colorResolver: DocumentColorResolver,
    themeMotion: ThemeMotionDefaults? = null,
): SlideTransitionV2 {
    val effectiveCurve = (curve ?: themeMotion?.curve)?.toCurveV2()
    return when (type) {
        TransitionType.None -> SlideTransitionV2.None
        TransitionType.Dissolve -> SlideTransitionV2.Dissolve(
            curve = effectiveCurve ?: CurveV2.EaseInOut,
        )
        TransitionType.Push -> SlideTransitionV2.Push(
            direction = direction?.let { EffectDirection8.parse(it) } ?: EffectDirection8.RightToLeft,
            curve = effectiveCurve ?: CurveV2.Preset("push"),
        )
        TransitionType.Slide -> SlideTransitionV2.Slide(
            direction = direction?.let { EffectDirection8.parse(it) } ?: EffectDirection8.RightToLeft,
            curve = effectiveCurve ?: CurveV2.Preset("slide"),
        )
        TransitionType.Reveal -> SlideTransitionV2.Reveal(
            direction = direction?.let { EffectDirection8.parse(it) } ?: EffectDirection8.RightToLeft,
            curve = effectiveCurve ?: CurveV2.Preset("reveal"),
        )
        TransitionType.Wipe -> SlideTransitionV2.Wipe(
            direction = direction?.let { EffectDirection8.parse(it) } ?: EffectDirection8.LeftToRight,
            curve = effectiveCurve ?: CurveV2.EaseInOut,
        )
        TransitionType.Iris -> SlideTransitionV2.Iris(
            direction = direction?.let { IrisDirection.parse(it) } ?: IrisDirection.In,
            center = center?.let { Offset(it.x.toFloat(), it.y.toFloat()) } ?: Offset(0.5f, 0.5f),
            curve = effectiveCurve ?: CurveV2.EaseInOut,
        )
        TransitionType.FadeThroughColor -> SlideTransitionV2.FadeThroughColor(
            color = colorResolver.resolve(color, Color.Black),
            curve = effectiveCurve ?: CurveV2.EaseInOut,
        )
        TransitionType.MagicMove -> SlideTransitionV2.MagicMove(
            curve = effectiveCurve ?: CurveV2.EaseInOut,
        )
    }
}

/**
 * Converts JSON `args` + optional `direction` from a document build into [EffectArgs] suitable for
 * `BuildEffects.evaluateLayer` / `evaluatePath` / `applyContentEffect`.
 */
fun buildEffectArgs(
    rawArgs: Map<String, JsonElement>,
    direction: String?,
    colorResolver: DocumentColorResolver,
): EffectArgs {
    if (rawArgs.isEmpty() && direction == null) return EffectArgs.Empty
    val out = LinkedHashMap<String, Any>(rawArgs.size + 2)
    if (direction != null) {
        out["direction"] = direction
    }
    for ((key, value) in rawArgs) {
        val converted: Any? = when (value) {
            is JsonPrimitive -> when {
                value.isString -> {
                    val str = value.content
                    if (key.contains("color", ignoreCase = true) || key == "caretColor") {
                        colorResolver.resolveOrNull(str) ?: str
                    } else {
                        str
                    }
                }
                value.booleanOrNull != null -> value.booleanOrNull
                value.intOrNull != null -> value.intOrNull
                value.longOrNull != null -> value.longOrNull
                value.floatOrNull != null -> value.floatOrNull
                value.doubleOrNull != null -> value.doubleOrNull
                else -> value.contentOrNull
            }
            is JsonObject -> {
                val x = value["x"]?.let { (it as? JsonPrimitive)?.floatOrNull }
                val y = value["y"]?.let { (it as? JsonPrimitive)?.floatOrNull }
                if (x != null && y != null) {
                    Offset(x, y)
                } else {
                    value.toString()
                }
            }
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
        }
        if (converted != null) {
            out[key] = converted
            // Document schema allows `caret: true/false` on `keyboard`; LayerEffects reads `showCaret`.
            if (key == "caret" && converted is Boolean) {
                out["showCaret"] = converted
            }
            // Document schema declares `amplitude` (deg) on `jiggle`; LayerEffects reads `maxAngleDeg`.
            if (key == "amplitude" && converted is Number && "maxAngleDeg" !in out) {
                out["maxAngleDeg"] = converted.toFloat()
            }
        }
    }
    // Document schema allows `centerX` / `centerY` on `iris`; LayerEffects reads `center: Offset`.
    if ("center" !in out && ("centerX" in out || "centerY" in out)) {
        val cx = (out["centerX"] as? Number)?.toFloat() ?: 0.5f
        val cy = (out["centerY"] as? Number)?.toFloat() ?: 0.5f
        out["center"] = Offset(cx, cy)
    }
    return EffectArgs(out)
}

/** Converts a [GradientSpec] into a Compose [Brush] sized to [size]. */
fun GradientSpec.toComposeBrush(
    size: Size,
    colorResolver: DocumentColorResolver,
): Brush {
    val colorStops = stops.sortedBy { it.at }.map { stop ->
        stop.at.toFloat().coerceIn(0f, 1f) to colorResolver.resolve(stop.color, Color.Transparent)
    }.toTypedArray()

    if (colorStops.isEmpty()) {
        return Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }

    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    return when (type) {
        GradientType.Linear -> {
            // CSS/Keynote linear gradient angle: 0° = bottom-to-top, 90° = left-to-right, 180° = top-to-bottom.
            val angleDeg = angle ?: 180.0
            val rad = angleDeg * PI / 180.0
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val halfSpan = (abs(w * dx) + abs(h * dy)) / 2f
            val cx = w / 2f
            val cy = h / 2f
            Brush.linearGradient(
                colorStops = colorStops,
                start = Offset(cx - dx * halfSpan, cy - dy * halfSpan),
                end = Offset(cx + dx * halfSpan, cy + dy * halfSpan),
            )
        }
        GradientType.Radial -> {
            val cx = ((center?.x ?: 0.5).toFloat()) * w
            val cy = ((center?.y ?: 0.5).toFloat()) * h
            val rad = radius
            val r = if (rad != null) {
                (rad.toFloat() * maxOf(w, h)).coerceAtLeast(1f)
            } else {
                (hypot(w.toDouble(), h.toDouble()).toFloat() / 2f).coerceAtLeast(1f)
            }
            Brush.radialGradient(
                colorStops = colorStops,
                center = Offset(cx, cy),
                radius = r,
            )
        }
    }
}

/** Parses a stroke cap string (`butt`, `round`, `square`), defaulting to [StrokeCap.Round]. */
fun parseStrokeCap(cap: String?): StrokeCap = when (cap?.lowercase()) {
    "butt" -> StrokeCap.Butt
    "square" -> StrokeCap.Square
    else -> StrokeCap.Round
}

/** Builds a [PathEffect.dashPathEffect], preserving crisp circular dots (`>= 0.01f`) under [StrokeCap.Round]. */
fun buildDashPathEffect(dash: List<Double>?, cap: StrokeCap, phase: Float = 0f): PathEffect? =
    dash?.takeIf { it.size >= 2 }?.let { d ->
        val intervals = FloatArray(d.size) { i ->
            val minLen = if (i % 2 == 0 && cap == StrokeCap.Round) 0.01f else 0.5f
            d[i].toFloat().coerceAtLeast(minLen)
        }
        PathEffect.dashPathEffect(intervals, phase)
    }

/** Parses a stroke join string (`miter`, `round`, `bevel`). */
fun parseStrokeJoin(join: String?): StrokeJoin = when (join?.lowercase()) {
    "miter" -> StrokeJoin.Miter
    "bevel" -> StrokeJoin.Bevel
    else -> StrokeJoin.Round
}

/** Converts a [StrokeSpec] into a Compose [Stroke] in stage pixel coordinates. */
fun StrokeSpec.toComposeStroke(density: Density): Stroke {
    val pxWidth = width.toFloat().coerceAtLeast(0f)
    val resolvedCap = parseStrokeCap(cap)
    val pathEffect = buildDashPathEffect(dash, resolvedCap)
    return Stroke(
        width = pxWidth,
        cap = resolvedCap,
        join = parseStrokeJoin(join),
        pathEffect = pathEffect,
    )
}

/**
 * Draws a [Fill] (solid or gradient) over the full [DrawScope] rect with optional corner radius.
 * Note: [Fill.Image] is rendered via [DocumentFillBackgroundBox].
 */
fun DrawScope.drawDocumentFill(
    fill: Fill,
    colorResolver: DocumentColorResolver,
    cornerRadiusPx: Float = 0f,
    alpha: Float = 1f,
) {
    when (fill) {
        is Fill.Solid -> {
            val c = colorResolver.resolve(fill.color, Color.Transparent)
            if (c.alpha <= 0f) return
            if (cornerRadiusPx > 0f) {
                drawRoundRect(
                    color = c,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    alpha = alpha,
                )
            } else {
                drawRect(color = c, alpha = alpha)
            }
        }
        is Fill.Gradient -> {
            val brush = fill.gradient.toComposeBrush(size, colorResolver)
            if (cornerRadiusPx > 0f) {
                drawRoundRect(
                    brush = brush,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    alpha = alpha,
                )
            } else {
                drawRect(brush = brush, alpha = alpha)
            }
        }
        is Fill.Image -> Unit
    }
}

/** Renders a slide or container background [Fill] (solid, linear/radial gradient, or image). */
@Composable
fun DocumentFillBackgroundBox(
    fill: Fill?,
    fallbackColor: Color,
    colorResolver: DocumentColorResolver,
    deckId: String,
    assetsRootDir: String?,
    modifier: Modifier = Modifier,
) {
    when (fill) {
        null -> Box(modifier = modifier.fillMaxSize().background(fallbackColor))
        is Fill.Solid -> {
            val c = colorResolver.resolve(fill.color, fallbackColor)
            Box(modifier = modifier.fillMaxSize().background(c))
        }
        is Fill.Gradient -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawDocumentFill(fill, colorResolver)
                    },
            )
        }
        is Fill.Image -> {
            Box(modifier = modifier.fillMaxSize().background(fallbackColor)) {
                DocumentImageFillView(
                    deckId = deckId,
                    src = fill.image.src,
                    fit = fill.image.fit ?: "cover",
                    tint = fill.image.tint?.let { colorResolver.resolveOrNull(it) },
                    assetsRootDir = assetsRootDir,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Applies a [SurfaceSpec] (clip, fill, border stroke) as a [Modifier] for solid/gradient fills.
 * Image fills on surfaces are drawn inside [DocumentSurfaceBox].
 */
fun Modifier.applyDocumentSurfaceChrome(
    surface: SurfaceSpec?,
    colorResolver: DocumentColorResolver,
    density: Density,
    clipToShape: Boolean = true,
): Modifier {
    if (surface == null) return this
    val radiusDp = (surface.cornerRadius ?: 0.0).toStageDp(density)
    val shape: Shape = RoundedCornerShape(radiusDp)
    val fill = surface.fill
    val stroke = surface.stroke

    var mod = this
    if (fill != null && fill !is Fill.Image) {
        val radiusPx = (surface.cornerRadius ?: 0.0).toFloat().coerceAtLeast(0f)
        mod = mod.drawBehind {
            drawDocumentFill(fill, colorResolver, cornerRadiusPx = radiusPx)
        }
    }
    if (stroke != null && stroke.width > 0.0) {
        val strokeColor = colorResolver.resolve(stroke.color, Color.Transparent)
        if (strokeColor.alpha > 0f) {
            if (stroke.dash.isNullOrEmpty()) {
                mod = mod.border(
                    width = stroke.width.toStageDp(density),
                    color = strokeColor,
                    shape = shape,
                )
            } else {
                val radiusPx = (surface.cornerRadius ?: 0.0).toFloat().coerceAtLeast(0f)
                val composeStroke = stroke.toComposeStroke(density)
                mod = mod.drawBehind {
                    drawRoundRect(
                        color = strokeColor,
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                        style = composeStroke,
                    )
                }
            }
        }
    }
    if (clipToShape && radiusDp > 0.dp) {
        mod = mod.clip(shape)
    }
    return mod
}
