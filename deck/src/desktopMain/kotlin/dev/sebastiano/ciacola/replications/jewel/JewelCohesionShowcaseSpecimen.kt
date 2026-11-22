package dev.sebastiano.ciacola.replications.jewel

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.document.LocalRenderSession
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ============================================================================
// #20 — jewel.cohesionIdeShowcase (Jewel Talk S59–S64, 5 panel modes)
// ============================================================================

val COHESION_PANEL_MODES = listOf("emulator", "settings", "idePreview", "ideFull", "comparison")

private val JETSNACK_SNIPPET = """
@Composable
fun JetsnackApp() {
    JetsnackTheme {
        val nav = rememberJetsnackNavController()
        NavHost(
            navController = nav.navController,
            startDestination = HOME_ROUTE,
        ) {
            composable(HOME_ROUTE) {
                Feed(onClick = nav::openDetail)
            }
            composable(DETAIL_ROUTE) { entry ->
                val id = entry.snackId()
                SnackDetail(snackId = id)
            }
        }
    }
}
""".trimIndent()

private val ANDROID_SETTINGS_ROWS = listOf(
    Triple("📶", "Network & internet", "Mobile, Wi-Fi, hotspot"),
    Triple("🔗", "Connected devices", "Bluetooth, pairing"),
    Triple("🧩", "Apps", "Assistant, recent apps, default apps"),
    Triple("🔔", "Notifications", "Notification history, conversations"),
    Triple("🔋", "Battery", "100% — Charging"),
    Triple("💾", "Storage", "28% used — 5.8 GB free"),
)

private val EXTENDED_CONTROLS_NAV = listOf(
    "Location",
    "Displays",
    "Cellular",
    "Battery",
    "Camera",
    "Phone",
    "Directional Pad",
    "Microphone",
    "Fingerprint",
    "Virtual Sensors",
    "Snapshots",
    "Settings",
)

/**
 * Specimen #20 (`jewel.cohesionIdeShowcase`, Jewel Talk S59–S64):
 * Cohesive IntelliJ + Android Studio visual evolution showcase supporting all 5 `panel`
 * modes emitted by `kpf_to_deck_json.py`:
 * - `"emulator"`: Standalone dark Android Emulator running Android Settings + right rail.
 * - `"settings"`: Legacy Material `Extended Controls` dialog (highlighting visual mismatch).
 * - `"idePreview"`: Android Studio `Jetsnack` Project tree + Compose editor.
 * - `"ideFull"`: Full Android Studio `Jetsnack` IDE + docked `Running Devices` tool window.
 * - `"comparison"`: Cohesive Jewel IntUi `Extended Controls - Medium Phone` dialog (`Camera`).
 */
@Composable
fun CohesionIdeShowcaseSpecimen(
    panel: String = "ideFull",
    loopMillis: Long = 0L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var activePanelOverride by remember(panel) { mutableStateOf<String?>(null) }
    val resolvedPanel = activePanelOverride ?: if (panel in COHESION_PANEL_MODES) panel else "ideFull"

    // Touch loopMillis deterministically for subtle clock indicator in phone status bar
    val minuteTick = 41 + ((loopMillis / 60_000L) % 9L).toInt()
    val phoneClock = "10:$minuteTick"

    Box(modifier = modifier.fillMaxSize()) {
        when (resolvedPanel) {
            "emulator" -> StandaloneEmulatorPanel(
                phoneClock = phoneClock,
                isInteractive = isInteractive,
                modifier = Modifier.fillMaxSize(),
            )
            "settings" -> LegacyExtendedControlsPanel(
                isInteractive = isInteractive,
                modifier = Modifier.fillMaxSize(),
            )
            "idePreview" -> AndroidStudioIdePanel(
                includeRunningDevices = false,
                phoneClock = phoneClock,
                isInteractive = isInteractive,
                modifier = Modifier.fillMaxSize(),
            )
            "comparison" -> CohesiveJewelExtendedControlsPanel(
                isInteractive = isInteractive,
                modifier = Modifier.fillMaxSize(),
            )
            else -> AndroidStudioIdePanel(
                includeRunningDevices = true,
                phoneClock = phoneClock,
                isInteractive = isInteractive,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Mode-cycle pill is strictly suppressed in Present / Still / Video render sessions
        // so live slides and exports never show debug panel-switcher chrome.
        val sessionPurpose = LocalRenderSession.current?.purpose
        val showModePill = isInteractive &&
            sessionPurpose != RenderPurpose.Present &&
            sessionPurpose != RenderPurpose.Still &&
            sessionPurpose != RenderPurpose.Video
        if (showModePill) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC1E1F22))
                    .border(1.dp, Color(0xFF43454A), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                COHESION_PANEL_MODES.forEach { mode ->
                    val selected = mode == resolvedPanel
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) Color(0xFF3574F0) else Color.Transparent)
                            .clickable { activePanelOverride = mode }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        BasicText(
                            text = mode,
                            style = TextStyle(
                                fontSize = 9.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Color.White else Color(0xFFA0A4AD),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandaloneEmulatorPanel(
    phoneClock: String,
    isInteractive: Boolean,
    modifier: Modifier = Modifier,
) {
    JewelWindowFrame(
        title = "Android Emulator - Medium_Phone_API_35:5554",
        isDark = true,
        darkTitleBar = true,
        surfaceColor = Color(0xFF1A1B1E),
        interactiveId = "specimen-cohesion-emulator",
        interactiveLabel = "Standalone Android Emulator",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Virtual phone chassis
            EmulatorPhoneScreen(
                phoneClock = phoneClock,
                modifier = Modifier
                    .width(290.dp)
                    .fillMaxHeight(0.96f),
            )
            Spacer(Modifier.width(12.dp))
            // Classic floating dark emulator control rail
            Column(
                modifier = Modifier
                    .width(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2B2D30))
                    .border(1.dp, Color(0xFF3E4046), RoundedCornerShape(10.dp))
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf("⏻", "🔊", "🔉", "↺", "↻", "📷", "◀", "●", "■", "⋯").forEach { glyph ->
                    Text(glyph, fontSize = 12.sp, color = Color(0xFFDFE1E5))
                }
            }
        }
    }
}

@Composable
private fun EmulatorPhoneScreen(
    phoneClock: String,
    modifier: Modifier = Modifier,
) {
    val bezelShape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .clip(bezelShape)
            .background(Color(0xFF101214), bezelShape)
            .border(3.dp, Color(0xFF3A3D44), bezelShape)
            .padding(10.dp),
    ) {
        // Android status bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(phoneClock, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE0E2E7))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF282B30)))
            Text("5G ▮▮▮ 100%", fontSize = 9.sp, color = Color(0xFFCED0D6))
        }
        // Android Settings body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF171A1C))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Settings", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE3E8EC))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF252A2E))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("🔍  Search settings", fontSize = 10.5.sp, color = Color(0xFF9EA6AE))
            }
            ANDROID_SETTINGS_ROWS.forEach { (icon, title, sub) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1B3A42)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(icon, fontSize = 11.sp)
                    }
                    Column {
                        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEAEEF2))
                        Text(sub, fontSize = 9.sp, color = Color(0xFF9AA3AB), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Bottom gesture pill
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(72.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF7B8088)))
        }
    }
}

@Composable
private fun LegacyExtendedControlsPanel(
    isInteractive: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedNav by remember { mutableStateOf(11) } // Settings
    var sendCrashReports by remember { mutableStateOf(true) }
    var clipboardSharing by remember { mutableStateOf(true) }
    var alwaysOnTop by remember { mutableStateOf(false) }

    val legacyTeal = Color(0xFF26A69A)
    val legacyBg = Color(0xFF2B3036)
    val legacySidebar = Color(0xFF21262B)

    JewelWindowFrame(
        title = "Extended Controls - Medium_Phone_API_35:5554",
        isDark = true,
        darkTitleBar = true,
        surfaceColor = legacyBg,
        interactiveId = "specimen-cohesion-legacy-settings",
        interactiveLabel = "Legacy Extended Controls",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Old Material-styled Qt dark-teal sidebar
            Column(
                modifier = Modifier
                    .width(172.dp)
                    .fillMaxHeight()
                    .background(legacySidebar)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EXTENDED_CONTROLS_NAV.forEachIndexed { idx, label ->
                    val active = idx == selectedNav
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (active) Color(0xFF323940) else Color.Transparent)
                            .clickable(enabled = isInteractive) { selectedNav = idx }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(14.dp)
                                .background(if (active) legacyTeal else Color.Transparent),
                        )
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = if (active) legacyTeal else Color(0xFFC5CCD4),
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            // Old Material teal settings pane
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(legacyBg)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = EXTENDED_CONTROLS_NAV[selectedNav],
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE6ECEF),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF3E464E))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("Legacy Qt / Material UI", fontSize = 9.5.sp, color = legacyTeal)
                    }
                }

                // Material tabs
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("GENERAL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = legacyTeal)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.width(64.dp).height(2.dp).background(legacyTeal))
                    }
                    Text("PROXY", fontSize = 11.sp, color = Color(0xFF9EA7B0))
                    Text("ADVANCED", fontSize = 11.sp, color = Color(0xFF9EA7B0))
                }

                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                LegacyTealToggleRow("Emulator always on top", alwaysOnTop, legacyTeal, isInteractive) { alwaysOnTop = !alwaysOnTop }
                LegacyTealToggleRow("Enable clipboard sharing", clipboardSharing, legacyTeal, isInteractive) { clipboardSharing = !clipboardSharing }
                LegacyTealToggleRow("Send crash reports automatically", sendCrashReports, legacyTeal, isInteractive) { sendCrashReports = !sendCrashReports }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Screenshot save location", fontSize = 10.sp, color = Color(0xFF9EA7B0))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF21262B), RoundedCornerShape(4.dp))
                            .border(1.dp, legacyTeal.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("/Users/dev/Desktop", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE0E5EA))
                        Text("BROWSE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = legacyTeal)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacyTealToggleRow(
    label: String,
    checked: Boolean,
    accent: Color,
    isInteractive: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isInteractive, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFFE0E5EA))
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) accent.copy(alpha = 0.45f) else Color(0xFF4A525A))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (checked) accent else Color(0xFFB0B8C0)),
            )
        }
    }
}

@Composable
private fun AndroidStudioIdePanel(
    includeRunningDevices: Boolean,
    phoneClock: String,
    isInteractive: Boolean,
    modifier: Modifier = Modifier,
) {
    val ideBg = Color(0xFF1E1F22)
    val chromeBg = Color(0xFF2B2D30)
    val divider = Color(0xFF393B40)

    JewelWindowFrame(
        title = "Jetsnack — JetsnackApp.kt",
        isDark = true,
        darkTitleBar = true,
        surfaceColor = ideBg,
        interactiveId = if (includeRunningDevices) "specimen-cohesion-ide-full" else "specimen-cohesion-ide-preview",
        interactiveLabel = if (includeRunningDevices) "Android Studio Full IDE + Running Devices" else "Android Studio IDE Preview",
        isInteractive = isInteractive,
        modifier = modifier,
        leadingTitleContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2E436E))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("JS", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text("Jetsnack", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDFE1E5))
            }
        },
        trailingTitleContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF393B40))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text("app • Medium Phone API 35", fontSize = 10.sp, color = Color(0xFFDFE1E5))
                }
                Icon(AllIconsKeys.Actions.Execute, contentDescription = "Run", modifier = Modifier.size(13.dp), tint = Color(0xFF57965C))
            }
        },
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Left Project tool window
            Column(
                modifier = Modifier
                    .width(if (includeRunningDevices) 172.dp else 210.dp)
                    .fillMaxHeight()
                    .background(chromeBg)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Project", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDFE1E5))
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                listOf(
                    0 to "▾ Jetsnack",
                    1 to "▾ app",
                    2 to "▾ java",
                    3 to "▾ com.example.jetsnack",
                    4 to "▸ model",
                    4 to "▾ ui",
                    5 to "JetsnackApp.kt",
                    5 to "JetsnackAppState.kt",
                    5 to "Feed.kt",
                    4 to "▸ theme",
                    2 to "▸ res",
                    1 to "build.gradle.kts",
                ).forEach { (indent, label) ->
                    val selected = label == "JetsnackApp.kt"
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) Color(0xFF2E436E) else Color.Transparent)
                            .padding(start = (indent * 8 + 4).dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.5.sp,
                            color = if (selected) Color.White else Color(0xFFCED0D6),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Box(Modifier.width(1.dp).fillMaxHeight().background(divider))

            // Center Compose code editor
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(ideBg),
            ) {
                // Editor tab strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(chromeBg)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        Text("JetsnackApp.kt", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.width(86.dp).height(2.dp).background(Color(0xFF3574F0)))
                    }
                    Text("Feed.kt", fontSize = 11.sp, color = Color(0xFF9DA0A8))
                    Text("Theme.kt", fontSize = 11.sp, color = Color(0xFF9DA0A8))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(divider))

                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Gutter line numbers (matching exact lineHeight of editor BasicText)
                    val lineCount = remember { JETSNACK_SNIPPET.lines().size }
                    BasicText(
                        text = remember(lineCount) { (1..lineCount).joinToString("\n") { it.toString().padStart(2, ' ') } },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFF5A5D65),
                        ),
                    )
                    BasicText(
                        text = remember { IdeCodeHighlighter.highlight(JETSNACK_SNIPPET, isDark = true) },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 16.sp,
                        ),
                    )
                }
            }

            // Optional docked Running Devices tool window (for "ideFull" mode)
            if (includeRunningDevices) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
                Column(
                    modifier = Modifier
                        .width(268.dp)
                        .fillMaxHeight()
                        .background(chromeBg),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Running Devices", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDFE1E5))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("⏻", fontSize = 11.sp, color = Color(0xFFCED0D6))
                            Text("↺", fontSize = 11.sp, color = Color(0xFFCED0D6))
                            Text("📷", fontSize = 11.sp, color = Color(0xFFCED0D6))
                            Text("⋯", fontSize = 11.sp, color = Color(0xFFCED0D6))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(ideBg)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmulatorPhoneScreen(
                            phoneClock = phoneClock,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CohesiveJewelExtendedControlsPanel(
    isInteractive: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf(4) } // Camera
    var enableTvAnimation by remember { mutableStateOf(true) }
    var wallImageName by remember { mutableStateOf("poster_jewel.png") }

    val panelBg = Color(0xFF1E1F22)
    val sidebarBg = Color(0xFF2B2D30)
    val blueSelection = Color(0xFF2E436E)

    JewelWindowFrame(
        title = "Extended Controls - Medium Phone API 35",
        isDark = true,
        darkTitleBar = true,
        surfaceColor = panelBg,
        interactiveId = "specimen-cohesion-jewel-comparison",
        interactiveLabel = "Cohesive Jewel Extended Controls",
        isInteractive = isInteractive,
        modifier = modifier,
        trailingTitleContent = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2B3F5E))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text("Jewel IntUi", fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8AB4F8))
            }
        },
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Native IntelliJ IntUi sidebar with blue selection pill
            Column(
                modifier = Modifier
                    .width(176.dp)
                    .fillMaxHeight()
                    .background(sidebarBg)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EXTENDED_CONTROLS_NAV.forEachIndexed { idx, label ->
                    val active = idx == selectedCategory
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) blueSelection else Color.Transparent)
                            .clickable(enabled = isInteractive) { selectedCategory = idx }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) Color.White else Color(0xFFCED0D6),
                        )
                    }
                }
            }

            Divider(Orientation.Vertical, Modifier.fillMaxHeight())

            // Cohesive Jewel IntUi Camera / Virtual Scene Images pane
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(panelBg)
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Virtual scene images",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFDFE1E5),
                        )
                        Text(
                            text = "Add images to display on the wall and table in the virtual camera scene.",
                            fontSize = 11.5.sp,
                            color = Color(0xFF9DA0A8),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        VirtualSceneDropCard(
                            title = "Wall",
                            fileName = wallImageName,
                            isInteractive = isInteractive,
                            onToggleSample = {
                                wallImageName = if (wallImageName == "poster_jewel.png") "studio_banner.png" else "poster_jewel.png"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        VirtualSceneDropCard(
                            title = "Table",
                            fileName = null,
                            isInteractive = isInteractive,
                            onToggleSample = {},
                            modifier = Modifier.weight(1f),
                        )
                    }

                    CompositionLocalProvider(LocalEffectiveBackgroundColor provides panelBg) {
                        CheckboxRow(
                            text = "Enable TV animation",
                            checked = enableTvAnimation,
                            onCheckedChange = { if (isInteractive) enableTvAnimation = it },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = {}) {
                        Text("Close", fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualSceneDropCard(
    title: String,
    fileName: String?,
    isInteractive: Boolean,
    onToggleSample: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .clip(cardShape)
            .background(Color(0xFF26282E), cardShape)
            .border(1.dp, Color(0xFF3E4148), cardShape)
            .clickable(enabled = isInteractive, onClick = onToggleSample)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDFE1E5))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E1F22))
                .border(1.dp, Color(0xFF43464E), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (fileName != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(AllIconsKeys.FileTypes.Any_type, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF589DF6))
                    Text(fileName, fontSize = 10.5.sp, color = Color(0xFFCED0D6), fontFamily = FontFamily.Monospace)
                }
            } else {
                Text("+ Add image", fontSize = 11.sp, color = Color(0xFF589DF6), fontWeight = FontWeight.Medium)
            }
        }
    }
}
