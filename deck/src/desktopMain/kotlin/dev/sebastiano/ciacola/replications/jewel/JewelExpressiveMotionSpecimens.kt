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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import kotlin.math.PI
import kotlin.math.sin
import org.jetbrains.jewel.ui.component.Text

// ============================================================================
// #16 — jewel.m3ExpressiveShowcase (Jewel Talk S55, periodMs = 9_400)
// ============================================================================

private enum class M3VectorIconKind {
    Alarm,
    Clock,
    Timer,
    Stopwatch,
    Link,
    Document,
    Message,
    Folder,
    Edit,
}

private val M3_RAIL_ITEMS = listOf(
    M3VectorIconKind.Alarm to "Alarm",
    M3VectorIconKind.Clock to "Clock",
    M3VectorIconKind.Timer to "Timer",
    M3VectorIconKind.Stopwatch to "Stopwatch",
)

private val M3_FAB_MENU_ITEMS = listOf(
    M3VectorIconKind.Document to "Document",
    M3VectorIconKind.Message to "Message",
    M3VectorIconKind.Folder to "Folder",
)

@Composable
private fun M3VectorIcon(
    kind: M3VectorIconKind,
    tint: Color,
    size: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val strokeW = (w * 0.105f).coerceAtLeast(1.6f)
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            M3VectorIconKind.Alarm -> {
                val r = w * 0.34f
                drawCircle(tint, radius = r, center = Offset(cx, cy + h * 0.05f), style = stroke)
                drawLine(tint, Offset(cx, cy + h * 0.05f), Offset(cx, cy - r * 0.45f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(cx, cy + h * 0.05f), Offset(cx + r * 0.45f, cy + h * 0.14f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.14f, h * 0.24f), Offset(w * 0.30f, h * 0.12f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.86f, h * 0.24f), Offset(w * 0.70f, h * 0.12f), strokeWidth = strokeW, cap = StrokeCap.Round)
            }
            M3VectorIconKind.Clock -> {
                val r = w * 0.38f
                drawCircle(tint, radius = r, center = Offset(cx, cy), style = stroke)
                drawLine(tint, Offset(cx, cy), Offset(cx, cy - r * 0.55f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(cx, cy), Offset(cx + r * 0.48f, cy + r * 0.12f), strokeWidth = strokeW, cap = StrokeCap.Round)
            }
            M3VectorIconKind.Timer -> {
                drawLine(tint, Offset(w * 0.24f, h * 0.14f), Offset(w * 0.76f, h * 0.14f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, h * 0.86f), Offset(w * 0.76f, h * 0.86f), strokeWidth = strokeW, cap = StrokeCap.Round)
                val hourglass = Path().apply {
                    moveTo(w * 0.30f, h * 0.14f)
                    lineTo(w * 0.70f, h * 0.14f)
                    lineTo(cx, cy)
                    lineTo(w * 0.70f, h * 0.86f)
                    lineTo(w * 0.30f, h * 0.86f)
                    lineTo(cx, cy)
                    close()
                }
                drawPath(hourglass, color = tint, style = stroke)
            }
            M3VectorIconKind.Stopwatch -> {
                val r = w * 0.34f
                drawCircle(tint, radius = r, center = Offset(cx, cy + h * 0.08f), style = stroke)
                drawLine(tint, Offset(cx - w * 0.14f, h * 0.12f), Offset(cx + w * 0.14f, h * 0.12f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(cx, cy + h * 0.08f), Offset(cx + r * 0.45f, cy - r * 0.30f), strokeWidth = strokeW, cap = StrokeCap.Round)
            }
            M3VectorIconKind.Link -> {
                rotate(degrees = -45f, pivot = Offset(cx, cy)) {
                    val linkH = h * 0.30f
                    val linkW = w * 0.42f
                    val cr = CornerRadius(linkH / 2f, linkH / 2f)
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(cx - w * 0.44f, cy - linkH / 2f),
                        size = Size(linkW, linkH),
                        cornerRadius = cr,
                        style = stroke,
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(cx + w * 0.02f, cy - linkH / 2f),
                        size = Size(linkW, linkH),
                        cornerRadius = cr,
                        style = stroke,
                    )
                    drawLine(
                        color = tint,
                        start = Offset(cx - w * 0.16f, cy),
                        end = Offset(cx + w * 0.16f, cy),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                }
            }
            M3VectorIconKind.Document -> {
                val doc = Path().apply {
                    moveTo(w * 0.24f, h * 0.14f)
                    lineTo(w * 0.60f, h * 0.14f)
                    lineTo(w * 0.78f, h * 0.32f)
                    lineTo(w * 0.78f, h * 0.86f)
                    lineTo(w * 0.24f, h * 0.86f)
                    close()
                }
                drawPath(doc, color = tint, style = stroke)
                drawLine(tint, Offset(w * 0.36f, h * 0.50f), Offset(w * 0.66f, h * 0.50f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.36f, h * 0.68f), Offset(w * 0.58f, h * 0.68f), strokeWidth = strokeW, cap = StrokeCap.Round)
            }
            M3VectorIconKind.Message -> {
                val bubble = Path().apply {
                    moveTo(w * 0.16f, h * 0.22f)
                    lineTo(w * 0.84f, h * 0.22f)
                    lineTo(w * 0.84f, h * 0.68f)
                    lineTo(w * 0.42f, h * 0.68f)
                    lineTo(w * 0.22f, h * 0.84f)
                    lineTo(w * 0.24f, h * 0.68f)
                    lineTo(w * 0.16f, h * 0.68f)
                    close()
                }
                drawPath(bubble, color = tint, style = stroke)
            }
            M3VectorIconKind.Folder -> {
                val folder = Path().apply {
                    moveTo(w * 0.14f, h * 0.24f)
                    lineTo(w * 0.42f, h * 0.24f)
                    lineTo(w * 0.50f, h * 0.34f)
                    lineTo(w * 0.86f, h * 0.34f)
                    lineTo(w * 0.86f, h * 0.80f)
                    lineTo(w * 0.14f, h * 0.80f)
                    close()
                }
                drawPath(folder, color = tint, style = stroke)
            }
            M3VectorIconKind.Edit -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.78f), Offset(w * 0.74f, h * 0.26f), strokeWidth = strokeW * 1.4f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, h * 0.82f), Offset(w * 0.34f, h * 0.76f), strokeWidth = strokeW, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.62f, h * 0.20f), Offset(w * 0.80f, h * 0.38f), strokeWidth = strokeW, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * Specimen #16 (`jewel.m3ExpressiveShowcase`, Jewel Talk S55, `periodMs = 9_400`):
 * Material 3 Expressive springy motion & shape-morphing showcase — dotted lavender
 * canvas, M3 NavigationRail, shape-morphing toggle buttons (`Alarm` / `Link` / `Wifi`),
 * expanding floating pill toolbar, and bouncy FAB menu fan-out contrasting M3 expressive
 * motion against desktop UI density.
 */
@Composable
fun M3ExpressiveShowcaseSpecimen(
    loopMillis: Long = 2_350L,
    periodMs: Long = 9_400L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var userFabOpen by remember { mutableStateOf<Boolean?>(null) }
    var userNavIdx by remember { mutableStateOf<Int?>(null) }
    var userAlarmOn by remember { mutableStateOf<Boolean?>(null) }

    val t = loopProgress(loopMillis, periodMs)
    val periodSec = periodMs / 1000f

    val navIdx = userNavIdx ?: ((t * 4f).toInt() % 4)
    val alarmActive = userAlarmOn ?: (t in 0.14f..0.62f)
    val alarmSpring = when {
        userAlarmOn == true -> 1f
        userAlarmOn == false -> 0f
        t < 0.14f -> 0f
        t < 0.62f -> analyticalSpring((t - 0.14f) * periodSec, dampingRatio = 0.55f, stiffness = 420f)
        else -> 1f - easeOutCubic(segmentProgress(t, 0.62f, 0.72f))
    }.coerceIn(0f, 1.15f)

    val toolbarExpanded = when {
        t < 0.18f -> 0f
        t < 0.78f -> analyticalSpring((t - 0.18f) * periodSec, dampingRatio = 0.62f, stiffness = 360f)
        else -> 1f - easeOutCubic(segmentProgress(t, 0.78f, 0.90f))
    }.coerceIn(0f, 1.12f)

    val fabMenuSpring = when {
        userFabOpen == true -> 1f
        userFabOpen == false -> 0f
        t < 0.22f -> 0f
        t < 0.80f -> analyticalSpring((t - 0.22f) * periodSec, dampingRatio = 0.56f, stiffness = 380f)
        else -> 1f - easeOutCubic(segmentProgress(t, 0.80f, 0.92f))
    }.coerceIn(0f, 1.18f)

    val sliderFraction = 0.25f + 0.58f * (0.5f + 0.5f * sin(t * 2f * PI).toFloat())

    val m3CanvasBg = Color(0xFFFAF8FD)
    val m3Primary = Color(0xFF6750A4)
    val m3Container = Color(0xFFE8DEF8)
    val m3OnContainer = Color(0xFF21005D)

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = m3CanvasBg,
        borderColor = Color(0xFFDFD7EC),
        interactiveId = "specimen-m3-expressive-showcase",
        interactiveLabel = "Material 3 Expressive Showcase",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle purple dot-grid background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 18.dp.toPx()
                val r = 1.1.dp.toPx()
                var y = step / 2f
                while (y < size.height) {
                    var x = step / 2f
                    while (x < size.width) {
                        drawCircle(Color(0xFFD9CEE8), radius = r, center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // Left M3 Expressive NavigationRail
                Column(
                    modifier = Modifier
                        .width(86.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFF3EDF7))
                        .padding(vertical = 18.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    M3_RAIL_ITEMS.forEachIndexed { idx, (icon, label) ->
                        val active = idx == navIdx
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = isInteractive) { userNavIdx = idx }
                                .padding(vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(52.dp)
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(if (active) m3Container else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                M3VectorIcon(
                                    kind = icon,
                                    tint = if (active) m3OnContainer else Color(0xFF49454F),
                                    size = 18.dp,
                                )
                            }
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) m3OnContainer else Color(0xFF49454F),
                            )
                        }
                    }
                }

                // Right Expressive Motion Playground
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Top Expressive header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "Material 3 Expressive",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = m3OnContainer,
                                )
                                Text(
                                    text = "Shape-morphing controls & springy spatial motion",
                                    fontSize = 11.sp,
                                    color = Color(0xFF5D5768),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(m3Container)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text("Spring(0.6, 800)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = m3Primary)
                            }
                        }

                        // Row of 3 oversized shape-morphing toggle buttons + expressive progress slider
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 1. Alarm button morphing pill <-> rounded squircle + color flip
                                val alarmCorner = (34f - 18f * alarmSpring.coerceIn(0f, 1f)).dp
                                val alarmBg = lerp(m3Container, m3Primary, alarmSpring.coerceIn(0f, 1f))
                                Box(
                                    modifier = Modifier
                                        .size(width = (74f + 16f * alarmSpring).dp, height = 66.dp)
                                        .clip(RoundedCornerShape(alarmCorner))
                                        .background(alarmBg)
                                        .clickable(enabled = isInteractive) { userAlarmOn = !alarmActive },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        M3VectorIcon(
                                            kind = M3VectorIconKind.Alarm,
                                            tint = if (alarmActive) Color.White else m3OnContainer,
                                            size = 20.dp,
                                        )
                                        if (alarmActive) {
                                            Text(
                                                text = "ON",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                            )
                                        }
                                    }
                                }

                                // 2. Tall stadium toggle pill
                                val linkPressed = t in 0.34f..0.72f
                                Box(
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 74.dp)
                                        .clip(RoundedCornerShape(if (linkPressed) 16.dp else 28.dp))
                                        .background(if (linkPressed) Color(0xFF7D5260) else Color(0xFFFFD8E4)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    M3VectorIcon(
                                        kind = M3VectorIconKind.Link,
                                        tint = if (linkPressed) Color.White else Color(0xFF31111D),
                                        size = 22.dp,
                                    )
                                }

                                // 3. Wide squircle toggle group
                                Row(
                                    modifier = Modifier
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(Color(0xFFECE6F0))
                                        .padding(5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    listOf("Wi-Fi", "BT", "Focus").forEachIndexed { idx, label ->
                                        val on = idx == ((t * 3f).toInt() % 3)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(if (on) 14.dp else 24.dp))
                                                .background(if (on) m3Primary else Color.White)
                                                .padding(horizontal = 14.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (on) Color.White else m3OnContainer,
                                            )
                                        }
                                    }
                                }
                            }

                            // Expressive wavy-accent slider bar
                            Row(
                                modifier = Modifier.fillMaxWidth(0.76f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(m3Container),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(sliderFraction)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(m3Primary),
                                    )
                                }
                                Text(
                                    text = "${(sliderFraction * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = m3Primary,
                                )
                            }
                        }

                        // Bottom Floating Pill Toolbar expanding from '+' into full actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val pillWidth = (54f + 196f * toolbarExpanded.coerceIn(0f, 1.05f)).dp
                            Row(
                                modifier = Modifier
                                    .width(pillWidth)
                                    .height(48.dp)
                                    .shadow(4.dp, RoundedCornerShape(24.dp))
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color(0xFFEDE7F6))
                                    .border(1.dp, Color(0xFFD5C8EB), RoundedCornerShape(24.dp))
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                if (toolbarExpanded > 0.35f) {
                                    Text("←", fontSize = 14.sp, color = m3OnContainer)
                                    Text("→", fontSize = 14.sp, color = m3OnContainer)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(m3Primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                if (toolbarExpanded > 0.55f) {
                                    Text("PIP", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = m3OnContainer)
                                    Text("⋮", fontSize = 14.sp, color = m3OnContainer)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }

                    // Bottom-right Expressive FAB + Staggered Pill Fan-out Menu
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (fabMenuSpring > 0.05f) {
                            M3_FAB_MENU_ITEMS.forEachIndexed { idx, (icon, label) ->
                                val itemP = ((fabMenuSpring * 1.25f) - (2 - idx) * 0.14f).coerceIn(0f, 1.12f)
                                if (itemP > 0.04f) {
                                    Row(
                                        modifier = Modifier
                                            .graphicsLayer {
                                                alpha = itemP.coerceIn(0f, 1f)
                                                translationY = (1f - itemP) * 20f
                                                val s = 0.84f + 0.16f * itemP
                                                scaleX = s
                                                scaleY = s
                                            }
                                            .shadow(3.dp, RoundedCornerShape(20.dp))
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(m3Container)
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        M3VectorIcon(
                                            kind = icon,
                                            tint = m3OnContainer,
                                            size = 14.dp,
                                        )
                                        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = m3OnContainer)
                                    }
                                }
                            }
                        }

                        val fabCorner = (16f + 12f * fabMenuSpring.coerceIn(0f, 1f)).dp
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .shadow(6.dp, RoundedCornerShape(fabCorner))
                                .clip(RoundedCornerShape(fabCorner))
                                .background(m3Primary)
                                .clickable(enabled = isInteractive) {
                                    userFabOpen = !(userFabOpen ?: (fabMenuSpring > 0.4f))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (fabMenuSpring > 0.45f) {
                                Text(
                                    text = "×",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.graphicsLayer {
                                        rotationZ = fabMenuSpring.coerceIn(0f, 1f) * 90f
                                    },
                                )
                            } else {
                                M3VectorIcon(
                                    kind = M3VectorIconKind.Edit,
                                    tint = Color.White,
                                    size = 20.dp,
                                    modifier = Modifier.graphicsLayer {
                                        rotationZ = fabMenuSpring.coerceIn(0f, 1f) * 90f
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// #17 — jewel.springTokenTree (Jewel Talk S56)
// ============================================================================

private data class SpringTokenLeaf(
    val scheme: String,
    val family: String,
    val speed: String,
    val damping: Float,
    val stiffness: Float,
    val highlighted: Boolean = false,
)

private val STANDARD_SPATIAL_TOKENS = listOf(
    SpringTokenLeaf("Standard", "Spatial", "Fast", 1.0f, 1400f),
    SpringTokenLeaf("Standard", "Spatial", "Default", 1.0f, 700f),
    SpringTokenLeaf("Standard", "Spatial", "Slow", 1.0f, 300f),
)

private val STANDARD_EFFECTS_TOKENS = listOf(
    SpringTokenLeaf("Standard", "Effects", "Fast", 1.0f, 3800f),
    SpringTokenLeaf("Standard", "Effects", "Default", 1.0f, 1600f),
    SpringTokenLeaf("Standard", "Effects", "Slow", 1.0f, 800f),
)

private val EXPRESSIVE_SPATIAL_TOKENS = listOf(
    SpringTokenLeaf("Expressive", "Spatial", "Fast", 0.6f, 800f),
    SpringTokenLeaf("Expressive", "Spatial", "Default", 0.8f, 380f, highlighted = true),
    SpringTokenLeaf("Expressive", "Spatial", "Slow", 0.8f, 200f),
)

private val EXPRESSIVE_EFFECTS_TOKENS = listOf(
    SpringTokenLeaf("Expressive", "Effects", "Fast", 1.0f, 3800f),
    SpringTokenLeaf("Expressive", "Effects", "Default", 1.0f, 1600f, highlighted = true),
    SpringTokenLeaf("Expressive", "Effects", "Slow", 1.0f, 800f),
)

/**
 * Specimen #17 (`jewel.springTokenTree`, Jewel Talk S56):
 * Visual Material 3 / Jewel motion-token hierarchy comparing the `Standard` (critically
 * damped) and `Expressive` (underdamped bouncy) `Spatial` and `Effects` spring specs,
 * with crisp Canvas elbow connectors and an interactive live response-curve callout!
 */
@Composable
fun SpringTokenTreeSpecimen(
    loopMillis: Long = 0L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val allTokens = remember {
        STANDARD_SPATIAL_TOKENS + STANDARD_EFFECTS_TOKENS + EXPRESSIVE_SPATIAL_TOKENS + EXPRESSIVE_EFFECTS_TOKENS
    }
    var selectedToken by remember { mutableStateOf(EXPRESSIVE_SPATIAL_TOKENS[1]) }

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color.White,
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-spring-token-tree",
        interactiveLabel = "Spring Motion Token Tree",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top half: Standard scheme on white dotted grid
            SpringSchemeBand(
                rootLabel = "Standard",
                rootBg = Color(0xFF2B2930),
                bandBg = Color(0xFFFCFCFE),
                dotColor = Color(0xFFE3E4EA),
                accentColor = Color(0xFF49454F),
                pillBg = Color(0xFFECEEF3),
                spatialTokens = STANDARD_SPATIAL_TOKENS,
                effectsTokens = STANDARD_EFFECTS_TOKENS,
                selectedToken = selectedToken,
                isInteractive = isInteractive,
                onSelect = { selectedToken = it },
                modifier = Modifier.weight(0.42f).fillMaxWidth(),
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFD2C8E8)))

            // Bottom half: Expressive scheme on soft lavender canvas
            SpringSchemeBand(
                rootLabel = "Expressive",
                rootBg = Color(0xFF6750A4),
                bandBg = Color(0xFFEAE3F9),
                dotColor = Color(0xFFD5C8EE),
                accentColor = Color(0xFF6750A4),
                pillBg = Color(0xFFF6F2FD),
                spatialTokens = EXPRESSIVE_SPATIAL_TOKENS,
                effectsTokens = EXPRESSIVE_EFFECTS_TOKENS,
                selectedToken = selectedToken,
                isInteractive = isInteractive,
                onSelect = { selectedToken = it },
                modifier = Modifier.weight(0.42f).fillMaxWidth(),
            )

            // Live analytical spring curve inspector strip at the bottom
            val footerBg = Color(0xFF1F1C2C)
            CompositionLocalProvider(LocalEffectiveBackgroundColor provides footerBg) {
                Row(
                    modifier = Modifier
                        .weight(0.16f)
                        .fillMaxWidth()
                        .background(footerBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.width(240.dp)) {
                        Text(
                            text = "${selectedToken.scheme} → ${selectedToken.family} → ${selectedToken.speed}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            text = "dampingRatio = ${selectedToken.damping}f   stiffness = ${selectedToken.stiffness.toInt()}f",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFC8B8F2),
                        )
                    }
                    // Analytical response curve plot + moving probe dot
                    val probeT = if (loopMillis == 0L) 0.34f else loopProgress(loopMillis, 2_000L)
                    Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val w = size.width
                        val h = size.height
                        val baselineY = h * 0.82f
                        val targetY = h * 0.30f
                        val span = baselineY - targetY

                        // Target line y = 1.0
                        drawLine(
                            color = Color(0xFF524A6E),
                            start = Offset(0f, targetY),
                            end = Offset(w, targetY),
                            strokeWidth = 1.2f,
                        )
                        // Curve path over 0..0.9s
                        val path = Path()
                        val samples = 64
                        for (i in 0..samples) {
                            val u = i.toFloat() / samples
                            val yVal = analyticalSpring(u * 0.9f, selectedToken.damping, selectedToken.stiffness)
                            val px = u * w
                            val py = (baselineY - yVal * span).coerceIn(4f, h - 4f)
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFFE3F574),
                            style = Stroke(width = 2.4f, cap = StrokeCap.Round),
                        )
                        val probeY = (baselineY - analyticalSpring(probeT * 0.9f, selectedToken.damping, selectedToken.stiffness) * span)
                            .coerceIn(4f, h - 4f)
                        drawCircle(Color(0xFFE3F574), radius = 4.5f, center = Offset(probeT * w, probeY))
                    }
                    if (isInteractive) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF3A3352))
                                .clickable {
                                    val nextIdx = (allTokens.indexOf(selectedToken) + 1) % allTokens.size
                                    selectedToken = allTokens[nextIdx]
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("Next token ↻", fontSize = 10.sp, color = Color(0xFFE7DEFF))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpringSchemeBand(
    rootLabel: String,
    rootBg: Color,
    bandBg: Color,
    dotColor: Color,
    accentColor: Color,
    pillBg: Color,
    spatialTokens: List<SpringTokenLeaf>,
    effectsTokens: List<SpringTokenLeaf>,
    selectedToken: SpringTokenLeaf,
    isInteractive: Boolean,
    onSelect: (SpringTokenLeaf) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(bandBg)) {
        // Dotted grid + connector elbow lines with circular terminals
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 16.dp.toPx()
            var y = step / 2f
            while (y < size.height) {
                var x = step / 2f
                while (x < size.width) {
                    drawCircle(dotColor, radius = 0.9.dp.toPx(), center = Offset(x, y))
                    x += step
                }
                y += step
            }
            val rootRightX = size.width * 0.18f
            val midX = size.width * 0.25f
            val branchLeftX = size.width * 0.30f
            val branchRightX = size.width * 0.44f
            val leafLeftX = size.width * 0.50f
            val cy = size.height * 0.50f
            val topY = size.height * 0.28f
            val botY = size.height * 0.72f

            // Root -> Spatial / Effects elbows
            drawLine(accentColor, Offset(rootRightX, cy), Offset(midX, cy), strokeWidth = 2f)
            drawLine(accentColor, Offset(midX, topY), Offset(midX, botY), strokeWidth = 2f)
            drawLine(accentColor, Offset(midX, topY), Offset(branchLeftX, topY), strokeWidth = 2f)
            drawLine(accentColor, Offset(midX, botY), Offset(branchLeftX, botY), strokeWidth = 2f)
            drawLine(accentColor, Offset(branchRightX, topY), Offset(leafLeftX, topY), strokeWidth = 2f)
            drawLine(accentColor, Offset(branchRightX, botY), Offset(leafLeftX, botY), strokeWidth = 2f)

            listOf(Offset(rootRightX, cy), Offset(branchLeftX, topY), Offset(branchLeftX, botY)).forEach { pt ->
                drawCircle(accentColor, radius = 3.5f, center = pt)
            }
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Root node (Standard / Expressive)
            Box(
                modifier = Modifier.weight(0.22f),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(rootBg)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(rootLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // Semantic family nodes (Spatial / Effects)
            Column(
                modifier = Modifier.weight(0.24f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                listOf("Spatial", "Effects").forEach { family ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .border(1.5.dp, accentColor, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(family, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
                    }
                }
            }

            // Leaf component token pills
            Column(
                modifier = Modifier.weight(0.54f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                TokenPillRow(spatialTokens, selectedToken, pillBg, accentColor, isInteractive, onSelect)
                TokenPillRow(effectsTokens, selectedToken, pillBg, accentColor, isInteractive, onSelect)
            }
        }
    }
}

@Composable
private fun TokenPillRow(
    tokens: List<SpringTokenLeaf>,
    selectedToken: SpringTokenLeaf,
    pillBg: Color,
    accentColor: Color,
    isInteractive: Boolean,
    onSelect: (SpringTokenLeaf) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tokens.forEach { tok ->
            val isSelected = tok == selectedToken
            val fill = when {
                isSelected -> accentColor
                tok.highlighted -> accentColor.copy(alpha = 0.16f)
                else -> pillBg
            }
            val fg = if (isSelected) Color.White else Color(0xFF1F1C2C)
            val subFg = if (isSelected) Color(0xFFE7DEFF) else Color(0xFF5D5768)
            val shape = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(fill, shape)
                    .border(if (isSelected || tok.highlighted) 1.5.dp else 1.dp, accentColor.copy(alpha = 0.55f), shape)
                    .clickable(enabled = isInteractive) { onSelect(tok) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(tok.speed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
                Text(
                    text = "(${tok.damping}, ${tok.stiffness.toInt()})",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = subFg,
                )
            }
        }
    }
}
