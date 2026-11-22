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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ============================================================================
// #1 — jewel.taskBasedProfilers (Jewel Talk S16–S17)
// ============================================================================

private data class ProfilerProcessRow(val name: String, val pid: String, val config: String = "Debuggable")

private enum class ProfilerIconKind { CpuSystem, MemoryChip, Waveform }

private data class ProfilerTaskTile(
    val title: String,
    val subtitle: String,
    val iconKind: ProfilerIconKind,
)

private val PROFILER_PROCESSES = listOf(
    ProfilerProcessRow("com.example.jetnews", "3647"),
    ProfilerProcessRow("android.process.acore", "2023"),
    ProfilerProcessRow("android.process.media", "2083"),
    ProfilerProcessRow("com.android.camera2", "4315"),
    ProfilerProcessRow("com.android.carrierdefaultapp", "3246"),
    ProfilerProcessRow("com.android.chrome", "2898"),
    ProfilerProcessRow("com.android.dynsystem", "4337"),
    ProfilerProcessRow("com.android.emulator.multidisplay", "1821"),
    ProfilerProcessRow("com.android.keychain", "3106"),
    ProfilerProcessRow("com.android.phone", "1039"),
    ProfilerProcessRow("com.android.settings", "1119"),
)

private val PROFILER_TASKS = listOf(
    ProfilerTaskTile("Capture System Activities", "System Trace", ProfilerIconKind.CpuSystem),
    ProfilerTaskTile("Analyze Memory Usage", "Heap Dump", ProfilerIconKind.MemoryChip),
    ProfilerTaskTile("Find CPU Hotspots", "Callstack Sample", ProfilerIconKind.CpuSystem),
    ProfilerTaskTile("Track Memory Consumption", "Java/Kotlin Allocations", ProfilerIconKind.MemoryChip),
    ProfilerTaskTile("Find CPU Hotspots", "Java/Kotlin Method Recording", ProfilerIconKind.CpuSystem),
    ProfilerTaskTile("Track Memory Consumption", "Native Allocations", ProfilerIconKind.MemoryChip),
    ProfilerTaskTile("View Live Telemetry", "Real-time CPU & RAM", ProfilerIconKind.Waveform),
)

/**
 * Specimen #1 (`jewel.taskBasedProfilers`, Jewel Talk S16–S17):
 * Android Studio Koala.2 Task-Based Profiler home view rendered in isolated Light Jewel IntUi.
 */
@Composable
fun TaskBasedProfilersSpecimen(
    @Suppress("UNUSED_PARAMETER") loopMillis: Long = 0L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var selectedProcessIdx by remember { mutableIntStateOf(0) }
    var selectedTaskIdx by remember { mutableIntStateOf(0) }
    var activeTab by remember { mutableIntStateOf(0) }
    var startMode by remember { mutableStateOf("Process start (restarts process)") }

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF7F8FA),
        borderColor = Color(0xFFD0D3DA),
        interactiveId = "specimen-task-based-profilers",
        interactiveLabel = "Task-Based Profilers",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top tool-window header strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(Color(0xFFF2F3F6))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Profiler", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2328))
                    ProfilerTabChip("Home", selected = activeTab == 0) { if (isInteractive) activeTab = 0 }
                    ProfilerTabChip("Past Recordings", selected = activeTab == 1) { if (isInteractive) activeTab = 1 }
                }
                Icon(AllIconsKeys.General.Gear, contentDescription = "Settings", modifier = Modifier.size(14.dp))
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            // Split body: left process list | right task tiles
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left process pane
                Column(
                    modifier = Modifier
                        .weight(0.36f)
                        .fillMaxHeight()
                        .background(Color.White),
                ) {
                    // Connected device selector header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF7F8FA))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF28A745)),
                        )
                        Text(
                            text = "Pixel_3a_API_33_arm64-v8a",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2328),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("API 33", fontSize = 10.sp, color = Color(0xFF6E737D))
                    }
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                    // Column headers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF7F8FA))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text("Process name", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(1.9f))
                        Text("PID", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(0.6f))
                        Text("Manifest", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(1.0f))
                    }
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                    // Process rows
                    Column(modifier = Modifier.fillMaxSize().padding(vertical = 2.dp)) {
                        PROFILER_PROCESSES.forEachIndexed { idx, proc ->
                            val isSelected = idx == selectedProcessIdx
                            val rowBg = if (isSelected) Color(0xFFD4E2FF) else Color.Transparent
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(rowBg)
                                    .clickable(enabled = isInteractive) { selectedProcessIdx = idx }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = proc.name,
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF1F2328),
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1.9f),
                                )
                                Text(proc.pid, fontSize = 10.5.sp, color = Color(0xFF5A5E66), modifier = Modifier.weight(0.6f))
                                Text(proc.config, fontSize = 10.sp, color = Color(0xFF5A5E66), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.0f))
                            }
                        }
                    }
                }

                Divider(Orientation.Vertical, Modifier.fillMaxHeight())

                // Right Tasks Grid + bottom CTA bar
                Column(
                    modifier = Modifier
                        .weight(0.64f)
                        .fillMaxHeight()
                        .background(Color(0xFFF7F8FA)),
                ) {
                    Text(
                        text = "Tasks",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF27282E),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                    // 4x2 tile grid
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (i in 0..3) {
                                ProfilerTaskCard(
                                    tile = PROFILER_TASKS[i],
                                    selected = selectedTaskIdx == i,
                                    onClick = { if (isInteractive) selectedTaskIdx = i },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (i in 4..6) {
                                ProfilerTaskCard(
                                    tile = PROFILER_TASKS[i],
                                    selected = selectedTaskIdx == i,
                                    onClick = { if (isInteractive) selectedTaskIdx = i },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }

                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                    // Bottom footer bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF2F3F6))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Start profiler task from",
                                fontSize = 11.sp,
                                color = Color(0xFF3C4048),
                                maxLines = 1,
                                softWrap = false,
                            )
                            Dropdown(
                                modifier = Modifier.weight(1f, fill = false).widthIn(max = 210.dp),
                                menuContent = {
                                    listOf(
                                        "Process start (restarts process)",
                                        "Now (attaches to process)",
                                    ).forEach { opt ->
                                        selectableItem(selected = startMode == opt, onClick = { startMode = opt }) {
                                            Text(opt, fontSize = 11.sp)
                                        }
                                    }
                                },
                            ) {
                                Text(startMode, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        DefaultButton(onClick = {}) {
                            Text("Start profiler task", fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfilerTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color(0xFF1F2328) else Color(0xFF6E737D),
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(if (selected) 36.dp else 0.dp)
                .height(2.dp)
                .background(Color(0xFF3574F0), RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun ProfilerTaskCard(
    tile: ProfilerTaskTile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) Color(0xFFD6E3FA) else Color.Transparent
    val border = if (selected) Color(0xFF99BDF6) else Color.Transparent
    val shape = RoundedCornerShape(8.dp)
    CompositionLocalProvider(LocalEffectiveBackgroundColor provides if (selected) bg else Color(0xFFF7F8FA)) {
        Column(
            modifier = modifier
                .clip(shape)
                .background(bg, shape)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ProfilerTaskIcon(tile.iconKind)
            Text(
                text = tile.title,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F2328),
                textAlign = TextAlign.Center,
                lineHeight = 13.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.subtitle,
                fontSize = 9.5.sp,
                color = Color(0xFF5A5E66),
                textAlign = TextAlign.Center,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProfilerTaskIcon(kind: ProfilerIconKind) {
    val boxBg = when (kind) {
        ProfilerIconKind.CpuSystem -> Color(0xFFEA4335)
        ProfilerIconKind.MemoryChip -> Color(0xFF34A853)
        ProfilerIconKind.Waveform -> Color(0xFFE8710A)
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(boxBg),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            when (kind) {
                ProfilerIconKind.CpuSystem -> {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(w * 0.22f, h * 0.22f),
                        size = Size(w * 0.56f, h * 0.56f),
                        cornerRadius = CornerRadius(2.5f, 2.5f),
                        style = Stroke(width = 1.8f),
                    )
                    for (p in listOf(0.35f, 0.65f)) {
                        drawLine(Color.White, Offset(w * p, 1f), Offset(w * p, h * 0.2f), 1.6f)
                        drawLine(Color.White, Offset(w * p, h * 0.8f), Offset(w * p, h - 1f), 1.6f)
                        drawLine(Color.White, Offset(1f, h * p), Offset(w * 0.2f, h * p), 1.6f)
                        drawLine(Color.White, Offset(w * 0.8f, h * p), Offset(w - 1f, h * p), 1.6f)
                    }
                }
                ProfilerIconKind.MemoryChip -> {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(w * 0.18f, h * 0.18f),
                        size = Size(w * 0.64f, h * 0.64f),
                        cornerRadius = CornerRadius(2.5f, 2.5f),
                        style = Stroke(width = 1.8f),
                    )
                    drawLine(Color.White, Offset(w * 0.34f, h * 0.42f), Offset(w * 0.66f, h * 0.42f), 1.6f)
                    drawLine(Color.White, Offset(w * 0.34f, h * 0.60f), Offset(w * 0.58f, h * 0.60f), 1.6f)
                }
                ProfilerIconKind.Waveform -> {
                    val path = Path().apply {
                        moveTo(w * 0.08f, h * 0.62f)
                        lineTo(w * 0.30f, h * 0.62f)
                        lineTo(w * 0.44f, h * 0.22f)
                        lineTo(w * 0.60f, h * 0.78f)
                        lineTo(w * 0.74f, h * 0.48f)
                        lineTo(w * 0.92f, h * 0.48f)
                    }
                    drawPath(path, Color.White, style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

// ============================================================================
// #2 — jewel.addDeviceDialog (Jewel Talk S18–S19, periodMs = 12_000)
// ============================================================================

private data class DeviceProfileRow(
    val name: String,
    val formFactor: String,
    val hasPlay: Boolean,
    val api: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: String,
    val diagonal: String,
    val oem: String = "Google",
)

private val DEVICE_PROFILES = listOf(
    DeviceProfileRow("Small Phone", "Phone", true, "24+", 720, 1280, "320 dpi", "4.7\"", "Generic"),
    DeviceProfileRow("Medium Phone", "Phone", true, "24+", 1080, 2400, "420 dpi", "6.4\"", "Generic"),
    DeviceProfileRow("Pixel 9 Pro XL", "Phone", true, "35+", 1344, 2992, "480 dpi", "6.8\""),
    DeviceProfileRow("Pixel 9 Pro Fold", "Phone", true, "35+", 2076, 2152, "390 dpi", "8.0\""),
    DeviceProfileRow("Pixel 9 Pro", "Phone", true, "35+", 1280, 2856, "480 dpi", "6.3\""),
    DeviceProfileRow("Pixel 8a", "Phone", true, "34+", 1080, 2400, "420 dpi", "6.1\""),
    DeviceProfileRow("Pixel 8 Pro", "Phone", true, "34+", 1344, 2992, "480 dpi", "6.7\""),
    DeviceProfileRow("Pixel 8", "Phone", true, "34+", 1080, 2400, "420 dpi", "6.2\""),
    DeviceProfileRow("Pixel Fold", "Phone", true, "34+", 2208, 1840, "420 dpi", "7.6\""),
    DeviceProfileRow("Pixel Tablet", "Tablet", true, "33+", 2560, 1600, "320 dpi", "10.9\""),
    DeviceProfileRow("Medium Tablet", "Tablet", true, "28+", 2560, 1600, "320 dpi", "10.1\"", "Generic"),
    DeviceProfileRow("Wear OS Large Round", "Wear OS", true, "30+", 454, 454, "320 dpi", "1.4\"", "Generic"),
    DeviceProfileRow("Medium Desktop", "Desktop", false, "33+", 1920, 1080, "160 dpi", "15.0\"", "Generic"),
)

private val FORM_FACTORS = listOf("Phone", "Tablet", "Wear OS", "Desktop", "TV", "Automotive")

/**
 * Specimen #2 (`jewel.addDeviceDialog`, Jewel Talk S18–S19, `periodMs = 12_000`):
 * Android Studio Ladybug.2 "Add Device" / Virtual Device Configuration Jewel wizard dialog.
 * Cycles deterministically across form-factor filtering, search typing, device selection,
 * and Step 2 ("Configure virtual device") while also supporting live clicks!
 */
@Composable
fun AddDeviceDialogSpecimen(
    loopMillis: Long = 3_000L,
    periodMs: Long = 12_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var userFormFactor by remember { mutableStateOf<String?>(null) }
    var userSelectedName by remember { mutableStateOf<String?>(null) }
    var userShowObsolete by remember { mutableStateOf<Boolean?>(null) }
    var userStepOverride by remember { mutableStateOf<Int?>(null) }

    val t = loopProgress(loopMillis, periodMs)
    val scriptedStep = if (t < 0.58f) 0 else 1
    val step = userStepOverride ?: scriptedStep

    val scriptedFormFactor = when {
        t in 0.10f..0.20f -> "Tablet"
        else -> "Phone"
    }
    val activeFormFactor = userFormFactor ?: scriptedFormFactor
    val showObsolete = userShowObsolete ?: false

    val typedSearch = if (userFormFactor == null && activeFormFactor == "Phone") {
        val searchP = segmentProgress(t, 0.24f, 0.42f)
        deterministicTypewriter("Pixel 8", searchP)
    } else {
        ""
    }

    val filteredDevices = remember(activeFormFactor, typedSearch) {
        DEVICE_PROFILES.filter { row ->
            row.formFactor == activeFormFactor &&
                (typedSearch.isBlank() || row.name.contains(typedSearch, ignoreCase = true))
        }.ifEmpty { DEVICE_PROFILES.filter { it.formFactor == "Phone" } }
    }

    val scriptedSelected = when {
        step == 1 -> DEVICE_PROFILES.first { it.name == "Pixel 8 Pro" }
        t < 0.08f -> null
        activeFormFactor == "Tablet" -> filteredDevices.firstOrNull()
        t >= 0.36f -> filteredDevices.firstOrNull { it.name == "Pixel 8 Pro" } ?: filteredDevices.firstOrNull()
        else -> filteredDevices.firstOrNull { it.name == "Pixel 9 Pro" } ?: filteredDevices.firstOrNull()
    }
    val selectedDevice = userSelectedName?.let { name -> DEVICE_PROFILES.firstOrNull { it.name == name } }
        ?: scriptedSelected

    JewelWindowFrame(
        title = "Add Device",
        isDark = false,
        darkTitleBar = false,
        surfaceColor = Color(0xFFF7F8FA),
        interactiveId = "specimen-add-device-dialog",
        interactiveLabel = "Add Device Wizard",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        if (step == 0) {
            // STEP 1: Device Selection
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left Form Factor Rail
                Column(
                    modifier = Modifier
                        .width(138.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFF2F3F6))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("Form Factor", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF27282E))
                    Spacer(Modifier.height(2.dp))
                    FORM_FACTORS.forEach { factor ->
                        RadioButtonRow(
                            text = factor,
                            selected = activeFormFactor == factor,
                            onClick = { if (isInteractive) userFormFactor = factor },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    CheckboxRow(
                        text = "Show obsolete",
                        checked = showObsolete,
                        onCheckedChange = { if (isInteractive) userShowObsolete = it },
                    )
                }

                Divider(Orientation.Vertical, Modifier.fillMaxHeight())

                // Center search + device table
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.White)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Search field visual
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White)
                            .border(
                                1.dp,
                                if (typedSearch.isNotEmpty()) Color(0xFF3574F0) else Color(0xFFC9CCD6),
                                RoundedCornerShape(5.dp),
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(AllIconsKeys.General.Filter, contentDescription = "Search", modifier = Modifier.size(13.dp))
                        val caret = if (typedSearch.isNotEmpty() && caretVisible(loopMillis)) "│" else ""
                        Text(
                            text = if (typedSearch.isEmpty()) "Search for a device by name" else typedSearch + caret,
                            fontSize = 11.5.sp,
                            color = if (typedSearch.isEmpty()) Color(0xFF8C9099) else Color(0xFF1F2328),
                            maxLines = 1,
                        )
                    }

                    // Table header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF5F6F9))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text("Name", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(2.2f))
                        Text("API", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(0.7f))
                        Text("Width", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(0.8f))
                        Text("Height", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(0.8f))
                        Text("Density", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A5E66), modifier = Modifier.weight(0.95f))
                    }
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                    // Rows
                    Column(modifier = Modifier.fillMaxSize()) {
                        filteredDevices.forEach { device ->
                            val isSel = selectedDevice?.name == device.name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSel) Color(0xFFD4E2FF) else Color.Transparent)
                                    .clickable(enabled = isInteractive) { userSelectedName = device.name }
                                    .padding(horizontal = 8.dp, vertical = 4.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = device.name,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                                    color = Color(0xFF1F2328),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(2.2f),
                                )
                                Text(device.api, fontSize = 10.5.sp, color = Color(0xFF4C5058), modifier = Modifier.weight(0.7f))
                                Text("${device.widthPx}", fontSize = 10.5.sp, color = Color(0xFF4C5058), modifier = Modifier.weight(0.8f))
                                Text("${device.heightPx}", fontSize = 10.5.sp, color = Color(0xFF4C5058), modifier = Modifier.weight(0.8f))
                                Text(device.density, fontSize = 10.5.sp, color = Color(0xFF4C5058), modifier = Modifier.weight(0.95f))
                            }
                        }
                    }
                }

                Divider(Orientation.Vertical, Modifier.fillMaxHeight())

                // Right device preview pane
                DevicePreviewPane(
                    device = selectedDevice,
                    modifier = Modifier.width(172.dp).fillMaxHeight(),
                )
            }
        } else {
            // STEP 2: Configure Virtual Device (S19)
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.White)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Configure virtual device", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2328))
                    Text(
                        "Select the system image you'd like to use with the device profile you selected.",
                        fontSize = 11.sp,
                        color = Color(0xFF5A5E66),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProfilerTabChip("Device", selected = false) {}
                        ProfilerTabChip("Additional settings", selected = true) {}
                    }
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                    // Amber info banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFFFF8E1))
                            .border(1.dp, Color(0xFFEBD084), RoundedCornerShape(5.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(AllIconsKeys.General.Warning, contentDescription = "Warning", modifier = Modifier.size(13.dp))
                        Text(
                            "Some device settings cannot be configured when using a Google Play Store image",
                            fontSize = 10.5.sp,
                            color = Color(0xFF4E3D14),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ConfigRow("Startup — Orientation", "Portrait")
                    ConfigRow("Default boot", "Quick")
                    ConfigRow("Internal storage", "2 GB")
                    ConfigRow("Expanded storage", "Custom  512 MB")
                    ConfigRow("CPU cores", "4")
                    ConfigRow("Graphics acceleration", "Automatic")
                }

                Divider(Orientation.Vertical, Modifier.fillMaxHeight())

                DevicePreviewPane(
                    device = selectedDevice ?: DEVICE_PROFILES.first { it.name == "Pixel 8 Pro" },
                    showDownloadBadge = true,
                    modifier = Modifier.width(172.dp).fillMaxHeight(),
                )
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        // Wizard Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF2F3F6))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (step == 0) {
                    OutlinedButton(onClick = {}) { Text("New hardware profile…", fontSize = 11.sp) }
                    OutlinedButton(onClick = {}) { Text("Import…", fontSize = 11.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { if (isInteractive) userStepOverride = 0 }) { Text("Cancel", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { if (isInteractive) userStepOverride = 0 },
                    enabled = step == 1,
                ) { Text("Previous", fontSize = 11.sp) }
                if (step == 0) {
                    DefaultButton(
                        onClick = { if (isInteractive) userStepOverride = 1 },
                        enabled = selectedDevice != null,
                    ) { Text("Next", fontSize = 11.sp) }
                    OutlinedButton(onClick = {}, enabled = false) { Text("Finish", fontSize = 11.sp) }
                } else {
                    OutlinedButton(onClick = {}, enabled = false) { Text("Next", fontSize = 11.sp) }
                    DefaultButton(onClick = { if (isInteractive) userStepOverride = 0 }) { Text("Finish", fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF3C4048), modifier = Modifier.width(140.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF7F8FA))
                .border(1.dp, Color(0xFFC9CCD6), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("$value  ▾", fontSize = 11.sp, color = Color(0xFF1F2328))
        }
    }
}

@Composable
private fun DevicePreviewPane(
    device: DeviceProfileRow?,
    showDownloadBadge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xFFF7F8FA))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (device == null) Arrangement.Center else Arrangement.spacedBy(8.dp),
    ) {
        if (device == null) {
            Text("Select a device", fontSize = 11.5.sp, color = Color(0xFF7B808A))
        } else {
            Text(
                text = device.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F2328),
                modifier = Modifier.fillMaxWidth(),
            )
            // Device outline schematic
            Box(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                contentAlignment = Alignment.Center,
            ) {
                val isWide = device.widthPx > device.heightPx
                val outlineW = if (isWide) 82.dp else 54.dp
                val outlineH = if (isWide) 56.dp else 94.dp
                Box(
                    modifier = Modifier
                        .size(outlineW, outlineH)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.5.dp, Color(0xFF4C5058), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(device.diagonal, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF3C4048))
                }
            }
            if (showDownloadBadge) {
                Text(
                    "↓ 1.7 GB system image will be downloaded",
                    fontSize = 9.5.sp,
                    color = Color(0xFF4C5058),
                    textAlign = TextAlign.Center,
                    lineHeight = 12.sp,
                )
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Device", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF27282E))
                SpecMiniRow("OEM", device.oem)
                SpecMiniRow("API Levels", device.api)
                Spacer(Modifier.height(3.dp))
                Text("Screen", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF27282E))
                SpecMiniRow("Resolution", "${device.widthPx} × ${device.heightPx}")
                SpecMiniRow("Density", device.density)
            }
        }
    }
}

@Composable
private fun SpecMiniRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, fontSize = 9.5.sp, color = Color(0xFF6E737D))
        Text(value, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2328))
    }
}
