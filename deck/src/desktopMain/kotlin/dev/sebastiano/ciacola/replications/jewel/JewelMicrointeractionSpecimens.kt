package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.stageInteractive
import kotlin.math.PI
import kotlin.math.sin
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

// ============================================================================
// #9 — jewel.confettiPartyPopper (Jewel Talk S31, periodMs = 1_400)
// ============================================================================

private data class ConfettiPiece(
    val vx: Float,
    val vy: Float,
    val color: Color,
    val sizeRatio: Float,
    val spinDeg: Float,
    val isDiamond: Boolean,
)

private val CONFETTI_PIECES = listOf(
    ConfettiPiece(0.42f, -0.58f, Color(0xFFFF4D6D), 0.032f, 220f, false),
    ConfettiPiece(0.62f, -0.44f, Color(0xFF4CC9F0), 0.030f, -190f, true),
    ConfettiPiece(0.32f, -0.66f, Color(0xFFFFD166), 0.028f, 300f, false),
    ConfettiPiece(0.74f, -0.32f, Color(0xFF06D6A0), 0.029f, -260f, false),
    ConfettiPiece(0.50f, -0.70f, Color(0xFFA66CFF), 0.034f, 160f, true),
    ConfettiPiece(0.68f, -0.56f, Color(0xFFFF6B9D), 0.026f, -310f, false),
    ConfettiPiece(0.24f, -0.52f, Color(0xFF3A86FF), 0.025f, 250f, true),
    ConfettiPiece(0.80f, -0.46f, Color(0xFFFFBE0B), 0.031f, -210f, false),
    ConfettiPiece(0.46f, -0.34f, Color(0xFFFF595E), 0.024f, 180f, false),
    ConfettiPiece(0.56f, -0.26f, Color(0xFF8AC926), 0.027f, -240f, true),
)

/**
 * Specimen #9 (`jewel.confettiPartyPopper`, Jewel Talk S31, `periodMs = 1_400`):
 * Crisp Canvas vector party-popper cannon shooting curly ribbons, streamers, and
 * parabolic confetti pieces inward (horizontally mirrored when [mirrored] is `true`).
 */
@Composable
fun ConfettiPartyPopperSpecimen(
    loopMillis: Long = 350L,
    periodMs: Long = 1_400L,
    mirrored: Boolean = false,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var burstOffsetMs by remember { mutableStateOf(0L) }
    val t = loopProgress(loopMillis + burstOffsetMs, periodMs)
    val popRecoil = 1f + 0.09f * sin(t * 2f * PI).toFloat()
    val burstProgress = easeOutCubic(segmentProgress(t, 0.02f, 0.88f))
    val tailFade = 1f - segmentProgress(t, 0.80f, 0.99f)

    val interactiveMod = if (isInteractive) {
        Modifier
            .stageInteractive(
                id = if (mirrored) "specimen-confetti-popper-right" else "specimen-confetti-popper-left",
                label = "Confetti Party Popper",
            )
            .clickable { burstOffsetMs += 280L }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.then(interactiveMod).fillMaxSize()) {
        val w = size.width
        val h = size.height
        val unit = minOf(w, h)
        withTransform({
            if (mirrored) {
                scale(scaleX = -1f, scaleY = 1f, pivot = Offset(w / 2f, h / 2f))
            }
        }) {
            val tip = Offset(w * 0.14f, h * 0.86f)
            val mouthCenter = Offset(w * 0.34f, h * 0.62f)
            val mouthDx = unit * 0.105f
            val mouthDy = unit * 0.105f

            // Parabolic confetti particles launching from the popper mouth
            val gravity = 0.52f
            CONFETTI_PIECES.forEachIndexed { idx, piece ->
                val localP = ((burstProgress + idx * 0.03f) % 1.0f).coerceIn(0f, 1f)
                val px = mouthCenter.x + piece.vx * unit * 0.82f * localP
                val py = mouthCenter.y + (piece.vy * localP + gravity * localP * localP) * unit * 0.88f
                val alpha = (tailFade * (0.35f + 0.65f * (1f - localP * 0.45f))).coerceIn(0f, 1f)
                val sz = unit * piece.sizeRatio
                rotate(degrees = piece.spinDeg * localP, pivot = Offset(px, py)) {
                    if (piece.isDiamond) {
                        val diamond = Path().apply {
                            moveTo(px, py - sz)
                            lineTo(px + sz * 0.72f, py)
                            lineTo(px, py + sz)
                            lineTo(px - sz * 0.72f, py)
                            close()
                        }
                        drawPath(diamond, color = piece.color.copy(alpha = alpha))
                    } else {
                        drawRoundRect(
                            color = piece.color.copy(alpha = alpha),
                            topLeft = Offset(px - sz * 0.6f, py - sz * 0.35f),
                            size = Size(sz * 1.2f, sz * 0.7f),
                            cornerRadius = CornerRadius(sz * 0.2f, sz * 0.2f),
                        )
                    }
                }
            }

            // Three lively curly streamers (crimson, hot-pink, sky-blue)
            val ribbons = listOf(
                Triple(Color(0xFFEF476F), Offset(0.50f, -0.50f), 1.0f),
                Triple(Color(0xFFFF70A6), Offset(0.66f, -0.26f), -0.85f),
                Triple(Color(0xFF4CC9F0), Offset(0.28f, -0.60f), 0.75f),
            )
            ribbons.forEach { (ribbonColor, dir, waveSign) ->
                val path = Path()
                val steps = 16
                val reach = 0.28f + 0.72f * burstProgress
                for (s in 0..steps) {
                    val f = (s.toFloat() / steps) * reach
                    val wave = sin(f * 14.0 + t * 8.0).toFloat() * unit * 0.026f * waveSign
                    val rx = mouthCenter.x + dir.x * unit * 0.82f * f - dir.y * wave
                    val ry = mouthCenter.y + dir.y * unit * 0.82f * f + dir.x * wave + (f * f * unit * 0.12f)
                    if (s == 0) path.moveTo(rx, ry) else path.lineTo(rx, ry)
                }
                drawPath(
                    path = path,
                    color = ribbonColor.copy(alpha = (0.35f + 0.65f * tailFade).coerceIn(0f, 1f)),
                    style = Stroke(width = (unit * 0.024f).coerceAtLeast(3f), cap = StrokeCap.Round),
                )
            }

            // Party popper cone body with scale recoil
            withTransform({
                scale(popRecoil, popRecoil, pivot = tip)
            }) {
                val mouthTopLeft = Offset(mouthCenter.x - mouthDx, mouthCenter.y - mouthDy)
                val mouthBottomRight = Offset(mouthCenter.x + mouthDx, mouthCenter.y + mouthDy)
                val conePath = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(mouthTopLeft.x, mouthTopLeft.y)
                    lineTo(mouthBottomRight.x, mouthBottomRight.y)
                    close()
                }
                drawPath(
                    path = conePath,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFD54F), Color(0xFFFFB300), Color(0xFFF57C00)),
                        start = mouthTopLeft,
                        end = tip,
                    ),
                )
                // Diagonal festive orange stripes clipped across the cone
                for (band in listOf(0.38f, 0.64f)) {
                    val a = Offset(
                        tip.x + (mouthTopLeft.x - tip.x) * band,
                        tip.y + (mouthTopLeft.y - tip.y) * band,
                    )
                    val b = Offset(
                        tip.x + (mouthBottomRight.x - tip.x) * band,
                        tip.y + (mouthBottomRight.y - tip.y) * band,
                    )
                    drawLine(
                        color = Color(0xFFE65100),
                        start = a,
                        end = b,
                        strokeWidth = (unit * 0.036f).coerceAtLeast(4f),
                        cap = StrokeCap.Round,
                    )
                }
                // Dark brown oval mouth opening
                rotate(degrees = 45f, pivot = mouthCenter) {
                    drawOval(
                        color = Color(0xFF4E342E),
                        topLeft = Offset(mouthCenter.x - mouthDx * 1.42f, mouthCenter.y - mouthDy * 0.44f),
                        size = Size(mouthDx * 2.84f, mouthDy * 0.88f),
                    )
                    drawOval(
                        color = Color(0xFFFFA000),
                        topLeft = Offset(mouthCenter.x - mouthDx * 1.42f, mouthCenter.y - mouthDy * 0.44f),
                        size = Size(mouthDx * 2.84f, mouthDy * 0.88f),
                        style = Stroke(width = (unit * 0.016f).coerceAtLeast(2f)),
                    )
                }
            }
        }
    }
}

// ============================================================================
// #12 — jewel.microinteractionScrollFab (Jewel Talk S49–S50, periodMs = 8_500)
// ============================================================================

private const val SCROLL_FAB_MARKDOWN = """
Here is an example of a KDoc comment for a Kotlin function:

```kotlin
/**
 * Calculates the sum of two integers.
 *
 * @param a The first integer.
 * @param b The second integer.
 * @return The sum of a and b.
 */
fun sum(a: Int, b: Int): Int {
    return a + b
}
```

**Key features of KDoc:**
- **Block tags:** Use `@param`, `@return`, and `@throws` to document function contracts.
- **Markdown syntax:** Supports inline formatting, code blocks, and links to Kotlin symbols.
- **IDE previews:** IntelliJ IDEA and Android Studio render KDoc directly in Quick Documentation.
"""

/**
 * Specimen #12 (`jewel.microinteractionScrollFab`, Jewel Talk S49–S50, `periodMs = 8_500`):
 * Scrollable light-theme Gemini chat view where scrolling upward springs in an elevated
 * floating "Jump to bottom ↓" Jewel pill button, which snaps back to bottom when clicked.
 */
@Composable
fun MicrointeractionScrollFabSpecimen(
    loopMillis: Long = 2_125L,
    periodMs: Long = 8_500L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var userScrolledUp by remember { mutableStateOf<Boolean?>(null) }

    val t = loopProgress(loopMillis, periodMs)
    val periodSec = periodMs / 1000f
    // Scroll up over 0.08..0.34, stay scrolled up while FAB springs in (0.18..0.72), click & snap back (0.74..0.88)
    val scriptedScrollRatio = when {
        t < 0.08f -> 0f
        t < 0.34f -> easeInOutCubic(segmentProgress(t, 0.08f, 0.34f))
        t < 0.74f -> 1f
        t < 0.88f -> 1f - easeInOutCubic(segmentProgress(t, 0.74f, 0.88f))
        else -> 0f
    }
    val scrollRatio = userScrolledUp?.let { if (it) 1f else 0f } ?: scriptedScrollRatio

    val fabVisiblePhase = when {
        userScrolledUp != null -> if (userScrolledUp == true) 1f else 0f
        t < 0.16f -> 0f
        t < 0.74f -> analyticalSpring((t - 0.16f) * periodSec, dampingRatio = 0.62f, stiffness = 480f)
        else -> (1f - easeOutCubic(segmentProgress(t, 0.74f, 0.84f)))
    }.coerceIn(0f, 1.15f)
    val fabPressed = userScrolledUp == null && t in 0.70f..0.75f

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF7F8FA),
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-microinteraction-scroll-fab",
        interactiveLabel = "Scroll to Bottom FAB",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GeminiToolWindowHeader(isDark = false)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .clickable(enabled = isInteractive) {
                        userScrolledUp = !(userScrolledUp ?: (scrollRatio > 0.4f))
                    },
            ) {
                // Start at bottom of chat (translationY = -72f) and smoothly scroll UP to top (0f)
                // when scrollRatio rises -> top message stays 100% unclipped while FAB springs in!
                val contentShiftY = -(1f - scrollRatio) * 72f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .graphicsLayer {
                            translationY = contentShiftY
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFE7F0FF))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Can you remind me what a KDoc looks like?",
                                fontSize = 11.sp,
                                color = Color(0xFF1F2328),
                            )
                        }
                    }

                    LiveJewelMarkdownView(
                        markdown = SCROLL_FAB_MARKDOWN,
                        isDark = false,
                        baseFontSizeSp = 11f,
                        showCodeActionBar = true,
                        blockSpacing = 6.dp,
                    )
                }

                // Subtle top fade scrim when scrolled down into the conversation
                if (scrollRatio < 0.92f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(18.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFF7F8FA).copy(alpha = (1f - scrollRatio) * 0.9f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }

                // Floating "Jump to bottom ↓" Jewel pill button springing in from bottom-right
                if (fabVisiblePhase > 0.03f) {
                    val scale = (0.72f + 0.28f * fabVisiblePhase) * (if (fabPressed) 0.92f else 1f)
                    val offsetY = (1f - fabVisiblePhase) * 28f
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 12.dp)
                            .graphicsLayer {
                                alpha = fabVisiblePhase.coerceIn(0f, 1f)
                                scaleX = scale
                                scaleY = scale
                                translationY = offsetY
                            }
                            .shadow(elevation = if (fabPressed) 2.dp else 6.dp, shape = RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (fabPressed) Color(0xFFEAF2FE) else Color.White)
                            .border(
                                width = 1.dp,
                                color = if (fabPressed) Color(0xFF3574F0) else Color(0xFFD5D8DF),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clickable(enabled = isInteractive) { userScrolledUp = false }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = "Jump to bottom",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF27282E),
                        )
                        Text(
                            text = "↓",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3574F0),
                        )
                    }
                }
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Box(modifier = Modifier.padding(10.dp)) {
                GeminiComposerCard(text = "", isDark = false, showImageAttach = true)
            }
        }
    }
}

// ============================================================================
// #13 — jewel.microinteractionEnabledButton (Jewel Talk S49–S50, periodMs = 2_400)
// ============================================================================

/**
 * Specimen #13 (`jewel.microinteractionEnabledButton`, Jewel Talk S49–S50, `periodMs = 2_400`):
 * Close-up of the `(Test only) Prompt Library` feature card transitioning a Jewel action button
 * cleanly across `disabled -> Enable -> hover -> pressed -> [ (✓) Enabled ]` with animated
 * pill width, focus ring, and green checkmark scale-in.
 */
@Composable
fun MicrointeractionEnabledButtonSpecimen(
    loopMillis: Long = 600L,
    periodMs: Long = 2_400L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var userEnabled by remember { mutableStateOf<Boolean?>(null) }
    val t = loopProgress(loopMillis, periodMs)
    val periodSec = periodMs / 1000f

    // Phase choreography:
    // 0.00..0.14: Disabled/idle -> 0.14..0.28: Hover/ready -> 0.28..0.34: Press -> 0.34..0.84: Enabled!
    val isDisabled = userEnabled == null && t < 0.12f
    val isHovered = userEnabled == null && t in 0.14f..0.28f
    val isPressed = userEnabled == null && t in 0.28f..0.35f
    val scriptedEnabled = t in 0.34f..0.86f
    val isEnabled = userEnabled ?: scriptedEnabled

    val checkSpring = when {
        userEnabled == true -> 1f
        userEnabled == false -> 0f
        t < 0.34f -> 0f
        t < 0.86f -> analyticalSpring((t - 0.34f) * periodSec, dampingRatio = 0.58f, stiffness = 620f)
        else -> 1f - easeOutCubic(segmentProgress(t, 0.86f, 0.96f))
    }.coerceIn(0f, 1.18f)

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF3F4F6),
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-microinteraction-enabled-button",
        interactiveLabel = "Prompt Library Enabled Button",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top dark IntelliJ Settings miniature preview ("Tools > Gemini > Prompt Library")
            val topBg = Color(0xFF1E1F22)
            CompositionLocalProvider(LocalEffectiveBackgroundColor provides topBg) {
                Row(
                    modifier = Modifier
                        .weight(0.56f)
                        .fillMaxWidth()
                        .background(topBg)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(128.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2B2D30))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("Settings", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDFE1E5))
                        Text("  Tools", fontSize = 9.5.sp, color = Color(0xFF9DA0A8))
                        Text("    Gemini [Preview]", fontSize = 9.5.sp, color = Color(0xFFCED0D6))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2E436E))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        ) {
                            Text("Prompt Library", fontSize = 9.5.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2B2D30))
                            .border(1.dp, Color(0xFF393B40), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Prompt Library", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDFE1E5))
                        listOf("Explain this code", "Summarize this file", "Summarize this code").forEachIndexed { idx, row ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (idx == 0) Color(0xFF2E436E) else Color.Transparent)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            ) {
                                Text(row, fontSize = 10.sp, color = Color(0xFFDFE1E5))
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E1F22))
                                .padding(6.dp),
                        ) {
                            BasicText(
                                text = "Explain the selected code: \$SELECTION",
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = Color(0xFF6AAB73)),
                            )
                        }
                    }
                }
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            // Bottom light feature card with animated Jewel Enable -> (✓) Enabled toggle button
            Column(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxWidth()
                    .background(Color(0xFFF3F4F6))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "(Test only) Prompt Library",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2328),
                    )
                    Text(
                        text = "Store, share, and trigger reusable Gemini prompt templates right from the editor context menu.",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFF57606A),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val stateLabel = when {
                        isEnabled -> "State: Enabled"
                        isPressed -> "State: Pressed"
                        isHovered -> "State: Hover"
                        isDisabled -> "State: Disabled"
                        else -> "State: Ready"
                    }
                    Text(
                        text = stateLabel,
                        fontSize = 10.5.sp,
                        color = Color(0xFF6E737D),
                        fontFamily = FontFamily.Monospace,
                    )

                    val btnBg = when {
                        isDisabled -> Color(0xFFE8EAEE)
                        isEnabled -> lerp(Color(0xFFFFFFFF), Color(0xFFEAF7EE), checkSpring.coerceIn(0f, 1f))
                        isPressed -> Color(0xFF285CC4)
                        isHovered -> Color(0xFF4280F5)
                        else -> Color(0xFF3574F0)
                    }
                    val btnBorder = when {
                        isDisabled -> Color(0xFFD3D5DB)
                        isEnabled -> Color(0xFF3574F0)
                        else -> Color(0xFF2E65D2)
                    }
                    val btnFg = when {
                        isDisabled -> Color(0xFF9AA0A6)
                        isEnabled -> Color(0xFF1F2328)
                        else -> Color.White
                    }
                    val focusHalo = if (isEnabled || isHovered || isPressed) 2.5.dp else 1.dp
                    val btnWidth = (92f + 28f * checkSpring.coerceIn(0f, 1.1f)).dp
                    val btnShape = RoundedCornerShape(6.dp)

                    Row(
                        modifier = Modifier
                            .width(btnWidth)
                            .height(32.dp)
                            .graphicsLayer {
                                val s = if (isPressed) 0.96f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .clip(btnShape)
                            .background(btnBg, btnShape)
                            .border(focusHalo, btnBorder, btnShape)
                            .clickable(enabled = isInteractive) {
                                userEnabled = !isEnabled
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (checkSpring > 0.08f) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .graphicsLayer {
                                        scaleX = checkSpring
                                        scaleY = checkSpring
                                    }
                                    .clip(CircleShape)
                                    .background(Color(0xFF208A3C)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = if (isEnabled) "Enabled" else "Enable",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = btnFg,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
