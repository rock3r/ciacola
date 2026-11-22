package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

// ============================================================================
// #14 — jewel.adaptiveDemoWizard (Jewel Talk S52, periodMs = 5_400)
// ============================================================================

private data class WizardTemplate(
    val title: String,
    val accent: Color,
    val badge: String? = null,
)

private val WIZARD_TEMPLATES = listOf(
    WizardTemplate("Empty", Color(0xFF7B8794)),
    WizardTemplate("App", Color(0xFF3574F0)),
    WizardTemplate("Adaptive", Color(0xFF6B57FF)),
    WizardTemplate("Gemini Starter App", Color(0xFFD96570), badge = "Preview"),
    WizardTemplate("Fullscreen App", Color(0xFF2E8B57)),
    WizardTemplate("Google Maps", Color(0xFF009688)),
    WizardTemplate("Game", Color(0xFFE67E22)),
    WizardTemplate("C++", Color(0xFF5C6BC0)),
)

private val WIZARD_CATEGORIES = listOf("PhoneAndTablet", "TV", "Automotive", "Wear")

/**
 * Specimen #14 (`jewel.adaptiveDemoWizard`, Jewel Talk S52, `periodMs = 5_400`):
 * Multi-step light-theme `Jewel Demo wizard` window smoothly resizing its live viewport
 * across `loopMillis` so `BoxWithConstraints` reflows the template grid between 4, 3,
 * and 2 columns while collapsing the category rail on compact widths.
 */
@Composable
fun AdaptiveDemoWizardSpecimen(
    loopMillis: Long = 1_350L,
    periodMs: Long = 5_400L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf(0) }
    var selectedTemplate by remember { mutableStateOf(2) }
    var pinnedFraction by remember { mutableStateOf<Float?>(null) }

    val t = loopProgress(loopMillis, periodMs)
    // Smoothly breathe between 1.0 (wide) -> 0.56 (compact) -> 1.0; at t=0.25 sits at ~0.78 (medium-wide)
    val scriptedWidthFraction = 0.56f + 0.44f * (0.5f + 0.5f * cos(t * 2f * PI).toFloat())
    val widthFraction = pinnedFraction ?: scriptedWidthFraction

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        JewelWindowFrame(
            title = "Jewel Demo wizard",
            isDark = false,
            darkTitleBar = false,
            surfaceColor = Color.White,
            interactiveId = "specimen-adaptive-demo-wizard",
            interactiveLabel = "Jewel Adaptive Demo Wizard",
            isInteractive = isInteractive,
            modifier = Modifier
                .fillMaxWidth(widthFraction.coerceIn(0.52f, 1f))
                .fillMaxHeight(0.94f),
            trailingTitleContent = {
                if (isInteractive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("S" to 0.56f, "M" to 0.78f, "L" to 1.0f).forEach { (lbl, frac) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (pinnedFraction == frac) Color(0xFF3574F0) else Color(0xFFE4E6EC))
                                    .clickable { pinnedFraction = if (pinnedFraction == frac) null else frac }
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = lbl,
                                    fontSize = 9.5.sp,
                                    color = if (pinnedFraction == frac) Color.White else Color(0xFF3C4048),
                                )
                            }
                        }
                    }
                }
            },
        ) {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val availableWidth = maxWidth
                val compact = availableWidth < 460.dp
                val columns = when {
                    availableWidth >= 680.dp -> 4
                    availableWidth >= 500.dp -> 3
                    else -> 2
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if (compact) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF7F8FA))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            WIZARD_CATEGORIES.forEachIndexed { idx, cat ->
                                val active = idx == selectedCategory
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) Color(0xFFD4E2FF) else Color.Transparent)
                                        .clickable(enabled = isInteractive) { selectedCategory = idx }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = cat,
                                        fontSize = 10.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = Color(0xFF1F2328),
                                    )
                                }
                            }
                        }
                        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                    }

                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (!compact) {
                            Column(
                                modifier = Modifier
                                    .width(138.dp)
                                    .fillMaxHeight()
                                    .background(Color(0xFFF7F8FA))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Templates",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF57606A),
                                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
                                )
                                WIZARD_CATEGORIES.forEachIndexed { idx, cat ->
                                    val active = idx == selectedCategory
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(if (active) Color(0xFFD4E2FF) else Color.Transparent)
                                            .clickable(enabled = isInteractive) { selectedCategory = idx }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = cat,
                                            fontSize = 11.sp,
                                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                            color = Color(0xFF1F2328),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = WIZARD_CATEGORIES[selectedCategory],
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1F2328),
                                )
                                Text(
                                    text = "$columns cols • ${availableWidth.value.toInt()} dp",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF6E737D),
                                )
                            }

                            val rows = WIZARD_TEMPLATES.chunked(columns)
                            rows.forEachIndexed { rIdx, rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowItems.forEachIndexed { cIdx, tpl ->
                                        val globalIdx = rIdx * columns + cIdx
                                        val isSelected = globalIdx == selectedTemplate
                                        val cardShape = RoundedCornerShape(8.dp)
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(cardShape)
                                                .background(if (isSelected) Color(0xFFEEF4FF) else Color(0xFFF9FAFC), cardShape)
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) Color(0xFF3574F0) else Color(0xFFDFE2E8),
                                                    shape = cardShape,
                                                )
                                                .clickable(enabled = isInteractive) { selectedTemplate = globalIdx }
                                                .padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(tpl.accent.copy(alpha = 0.14f))
                                                    .border(1.dp, tpl.accent.copy(alpha = 0.35f), RoundedCornerShape(5.dp)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Box(
                                                        Modifier
                                                            .size(width = 16.dp, height = 28.dp)
                                                            .clip(RoundedCornerShape(3.dp))
                                                            .background(tpl.accent.copy(alpha = 0.78f)),
                                                    )
                                                    if (tpl.title == "Adaptive") {
                                                        Box(
                                                            Modifier
                                                                .size(width = 24.dp, height = 28.dp)
                                                                .clip(RoundedCornerShape(3.dp))
                                                                .background(tpl.accent.copy(alpha = 0.45f)),
                                                        )
                                                    }
                                                }
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    text = tpl.title,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                    color = Color(0xFF1F2328),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                if (tpl.badge != null) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(Color(0xFFE9DEFF))
                                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                                    ) {
                                                        Text(tpl.badge, fontSize = 8.sp, color = Color(0xFF5B35B5))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    repeat(columns - rowItems.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F8FA))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {}) { Text("Cancel", fontSize = 11.sp) }
                    OutlinedButton(onClick = {}, enabled = false) { Text("Back", fontSize = 11.sp) }
                    DefaultButton(onClick = { if (isInteractive) selectedTemplate = (selectedTemplate + 1) % WIZARD_TEMPLATES.size }) {
                        Text("Next", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = {}) { Text("Finish", fontSize = 11.sp) }
                }
            }
        }
    }
}

// ============================================================================
// #15 — jewel.adaptiveWireframePanes (Jewel Talk S53, periodMs = 6_400)
// ============================================================================

/**
 * Specimen #15 (`jewel.adaptiveWireframePanes`, Jewel Talk S53, `periodMs = 6_400`):
 * Responsive multi-pane adaptive layout wireframe on Jewel Talk's dark eggplant canvas,
 * framed by a lime-yellow `#E3F574` resizable viewport border morphing cleanly across
 * Compact (1-pane), Medium (2-pane), and Expanded (3-pane IDE) breakpoints as
 * [widthFraction] and [loopMillis] sweep!
 */
@Composable
fun AdaptiveWireframePanesSpecimen(
    loopMillis: Long = 1_600L,
    periodMs: Long = 6_400L,
    widthFraction: Float = 0.72f,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var userWidthOverride by remember { mutableStateOf<Float?>(null) }

    val t = loopProgress(loopMillis, periodMs)
    val baseCenter = widthFraction.coerceIn(0.45f, 0.90f)
    val sweep = (baseCenter + 0.28f * sin((t * 2f * PI) - PI / 4.0).toFloat()).coerceIn(0.36f, 0.96f)
    val activeFraction = userWidthOverride ?: sweep

    val breakpoint = when {
        activeFraction < 0.52f -> "Compact"
        activeFraction < 0.76f -> "Medium"
        else -> "Expanded"
    }

    val canvasBg = Color(0xFF262138)
    val limeBorder = Color(0xFFE3F574)
    val paneFill = Color(0xFF3B3452)
    val paneStroke = Color(0xFFA794D4)

    IsolatedJewelSurface(
        isDark = true,
        surfaceColor = canvasBg,
        borderColor = Color(0xFF423A5C),
        interactiveId = "specimen-adaptive-wireframe-panes",
        interactiveLabel = "Adaptive Wireframe Panes",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Compact" to 0.42f, "Medium" to 0.66f, "Expanded" to 0.92f).forEach { (name, frac) ->
                        val active = name == breakpoint
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) limeBorder else Color(0xFF352E4A))
                                .clickable(enabled = isInteractive) {
                                    userWidthOverride = if (userWidthOverride == frac) null else frac
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = name,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) Color(0xFF1F1B2E) else Color(0xFFCBBEEB),
                            )
                        }
                    }
                }
                Text(
                    text = "width ${(activeFraction * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = limeBorder,
                )
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(activeFraction).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val outerShape = RoundedCornerShape(14.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(outerShape)
                            .background(Color(0xFF2D2742), outerShape)
                            .border(3.dp, limeBorder, outerShape)
                            .padding(14.dp),
                    ) {
                        when (breakpoint) {
                            "Compact" -> {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    WireframePaneBlock("Navigation Bar", paneFill, paneStroke, Modifier.fillMaxWidth().height(34.dp))
                                    WireframePaneBlock("Primary Detail", paneFill, paneStroke, Modifier.fillMaxWidth().weight(1f))
                                    WireframePaneBlock("Secondary Sheet", paneFill, paneStroke, Modifier.fillMaxWidth().height(72.dp))
                                }
                            }
                            "Medium" -> {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        WireframePaneBlock("List Pane", paneFill, paneStroke, Modifier.fillMaxWidth().weight(0.55f))
                                        WireframePaneBlock("Inspector", paneFill, paneStroke, Modifier.fillMaxWidth().weight(0.45f))
                                    }
                                    WireframePaneBlock("Detail Workspace", paneFill, paneStroke, Modifier.weight(0.58f).fillMaxHeight())
                                }
                            }
                            else -> {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    WireframePaneBlock("Rail", paneFill, paneStroke, Modifier.width(46.dp).fillMaxHeight())
                                    WireframePaneBlock("Project Tree", paneFill, paneStroke, Modifier.weight(0.28f).fillMaxHeight())
                                    Column(
                                        modifier = Modifier.weight(0.48f).fillMaxHeight(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        WireframePaneBlock("Editor Canvas", paneFill, paneStroke, Modifier.fillMaxWidth().weight(0.68f))
                                        WireframePaneBlock("Tool Console", paneFill, paneStroke, Modifier.fillMaxWidth().weight(0.32f))
                                    }
                                    WireframePaneBlock("Side Pane", paneFill, paneStroke, Modifier.weight(0.24f).fillMaxHeight())
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(limeBorder),
                    )
                }
            }
        }
    }
}

@Composable
private fun WireframePaneBlock(
    label: String,
    fill: Color,
    stroke: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill, shape)
            .border(1.5.dp, stroke, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE7DEFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .fillMaxWidth(0.72f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(stroke.copy(alpha = 0.40f)),
        )
        Box(
            Modifier
                .fillMaxWidth(0.48f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(stroke.copy(alpha = 0.25f)),
        )
    }
}
