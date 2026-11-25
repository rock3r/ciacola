package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.sebastiano.ciacola.inspector.design.CheckerboardBackground
import dev.sebastiano.ciacola.inspector.design.HsvChannelPickers
import dev.sebastiano.ciacola.inspector.design.InspectorTokens
import dev.sebastiano.ciacola.inspector.design.inspectorSecondaryTextColor
import dev.sebastiano.ciacola.inspector.design.humanizeTokenKey
import dev.sebastiano.ciacola.inspector.design.inspectorAccent
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.inspector.design.inspectorCaptionStyle
import dev.sebastiano.ciacola.inspector.design.parseHexColorOrNull
import dev.sebastiano.ciacola.inspector.design.toHexString
import dev.sebastiano.ciacola.inspector.design.toHsv
import dev.sebastiano.ciacola.inspector.document.DOCUMENT_THEME_COLOR_KEYS
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Readable name for a colour value: humanized token, uppercase hex, or `None` when unset. */
internal fun colorValueDisplayName(value: String?): String = when {
    value.isNullOrBlank() -> "None"
    value.trim().startsWith("#") -> value.trim().uppercase()
    else -> humanizeTokenKey(value.trim())
}

/**
 * Colour swatch. An unset colour draws a diagonal slash so it never reads as a checkbox; a
 * translucent colour sits on a checkerboard.
 */
@Composable
internal fun ColorSwatch(color: Color?, size: androidx.compose.ui.unit.Dp, tag: String? = null) {
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(InspectorTokens.Corner)
    val swatchBorder = if (JewelTheme.isDark) Color(0x4DFFFFFF) else Color(0x3D000000)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        if (color == null) {
            val slash = colors.outlines.error
            Canvas(Modifier.fillMaxSize().testTag("${tag ?: "swatch"}-unset")) {
                drawLine(slash, Offset(this.size.width * 0.15f, this.size.height * 0.85f), Offset(this.size.width * 0.85f, this.size.height * 0.15f), strokeWidth = 1.5.dp.toPx())
            }
        } else {
            if (color.alpha < 0.999f) CheckerboardBackground(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(color, shape))
        }
        Box(Modifier.fillMaxSize().border(1.dp, swatchBorder, shape))
    }
}

/**
 * One-row colour control: `[20dp swatch] Fill   TokenName ▾`. Clicking opens one popover with
 * theme tokens (real swatches beside every name), a hex field, the visual picker and `Clear`.
 *
 * [onChanged] receives the new token / hex (or `null` to clear) and whether it is part of a
 * coalesced picker drag gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InspectorColorRow(
    label: String,
    currentValue: String?,
    controller: DocumentElementInspectorController,
    tagPrefix: String,
    modified: Boolean,
    onChanged: (value: String?, inGesture: Boolean) -> Unit,
    onReset: (() -> Unit)?,
    allowClear: Boolean = true,
) {
    val colors = JewelTheme.globalColors
    val doc = controller.store.document
    val themeSpec = DocumentThemeResolver.resolveThemeSpec(doc)
    val slidePalette = controller.activeSlide()?.palette?.let { doc.palettes[it] }
    val resolved = DocumentThemeResolver.resolveColor(currentValue, slidePalette, themeSpec)
    var open by remember(tagPrefix) { mutableStateOf(false) }

    Box {
        // The whole row (swatch, label and value) opens the popover. A raw tap detector covers the
        // gaps without merging semantics, so the swatch / value test tags stay addressable.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(InspectorTokens.RowHeight)
                .pointerInput(tagPrefix) { detectTapGestures { open = !open } }
                .testTag("ciacola-$tagPrefix-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModifiedDot(modified = modified, label = label, tag = "ciacola-$tagPrefix", onReset = onReset)
            Box(
                modifier = Modifier
                    .clickable { open = !open }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Choose $label color (${colorValueDisplayName(currentValue)})"
                    },
            ) {
                ColorSwatch(resolved, 20.dp, tag = "ciacola-$tagPrefix-swatch")
            }
            Text(label, style = inspectorBodyStyle(), color = colors.text.info, modifier = Modifier.weight(1f), maxLines = 1)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(InspectorTokens.Corner))
                    .clickable { open = !open }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("ciacola-$tagPrefix-token-dropdown"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = colorValueDisplayName(currentValue),
                    style = inspectorBodyStyle(),
                    color = if (currentValue.isNullOrBlank()) colors.text.info else colors.text.normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ciacola-$tagPrefix-value-name"),
                )
                Icon(AllIconsKeys.General.ChevronDown, contentDescription = null, modifier = Modifier.size(10.dp))
            }
        }
        if (open) {
            val density = LocalDensity.current
            val positionProvider = remember(density) {
                ColorPopoverPositionProvider(
                    gapPx = with(density) { 4.dp.roundToPx() },
                    marginPx = with(density) { 8.dp.roundToPx() },
                )
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                ColorPopoverContent(
                    label = label,
                    currentValue = currentValue,
                    resolved = resolved,
                    tagPrefix = tagPrefix,
                    allowClear = allowClear,
                    tokenColor = { token -> DocumentThemeResolver.resolveColor(token, slidePalette, themeSpec) },
                    onFocusChanged = { controller.textInputHasFocus = it },
                    onGestureStart = { controller.beginGesture("Adjust $label") },
                    onGestureEnd = { controller.commitGesture() },
                    onPick = { value, inGesture -> onChanged(value, inGesture) },
                    onClose = { open = false },
                )
            }
        }
    }
}

@Composable
private fun ColorPopoverContent(
    label: String,
    currentValue: String?,
    resolved: Color?,
    tagPrefix: String,
    allowClear: Boolean,
    tokenColor: (String) -> Color?,
    onFocusChanged: (Boolean) -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onPick: (String?, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val colors = JewelTheme.globalColors
    val accent = inspectorAccent()
    val shape = RoundedCornerShape(6.dp)
    val baseColor = resolved ?: Color.White
    var hsv by remember(tagPrefix, currentValue) { mutableStateOf(baseColor.toHsv()) }
    val hexText = resolved?.toHexString(includeAlpha = resolved.alpha < 0.999f) ?: ""
    val hexState = rememberTextFieldState(if (currentValue?.trim()?.startsWith("#") == true) currentValue.trim() else hexText)
    var hexFocused by remember { mutableStateOf(false) }
    LaunchedEffect(hexText) { if (!hexFocused) hexState.setTextAndPlaceCursorAtEnd(hexText) }

    fun commitHex() {
        val typed = hexState.text.toString().trim()
        val normalized = if (typed.startsWith("#")) typed else "#$typed"
        if (parseHexColorOrNull(normalized) != null && !normalized.equals(currentValue, ignoreCase = true)) onPick(normalized.uppercase(), false)
    }

    Column(
        modifier = Modifier
            .width(268.dp)
            .clip(shape)
            .background(colors.panelBackground)
            .border(1.dp, colors.borders.normal, shape)
            .padding(10.dp)
            .testTag("ciacola-$tagPrefix-popover"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = inspectorBodyStyle(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (allowClear) {
                OutlinedButton(
                    onClick = {
                        onPick(null, false)
                        onClose()
                    },
                    modifier = Modifier.testTag("ciacola-$tagPrefix-clear"),
                ) { Text("Clear", style = inspectorBodyStyle()) }
            }
        }
        Text("Theme colors", style = inspectorCaptionStyle(), color = inspectorSecondaryTextColor())
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 216.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // One column so every token name (`Elevated Surface`, `Chip Background`) reads in full.
            for (pair in DOCUMENT_THEME_COLOR_KEYS.chunked(1)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (token in pair) {
                        val selected = token == currentValue?.trim()
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .clip(RoundedCornerShape(InspectorTokens.Corner))
                                .then(
                                    if (selected) Modifier.background(accent.copy(alpha = 0.16f)).border(1.dp, accent, RoundedCornerShape(InspectorTokens.Corner)) else Modifier,
                                )
                                .clickable {
                                    onPick(token, false)
                                    onClose()
                                }
                                .padding(horizontal = 4.dp)
                                .testTag("ciacola-$tagPrefix-token-$token"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ColorSwatch(tokenColor(token), 14.dp)
                            Text(humanizeTokenKey(token), style = inspectorBodyStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hex", style = inspectorBodyStyle(), color = inspectorSecondaryTextColor(), modifier = Modifier.width(28.dp))
            TextField(
                state = hexState,
                placeholder = { Text("#RRGGBB") },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { st ->
                        if (hexFocused && !st.isFocused) commitHex()
                        hexFocused = st.isFocused
                        onFocusChanged(st.isFocused)
                    }
                    .trackAuthoringTextInputFocus()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                            commitHex()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("ciacola-$tagPrefix-input"),
            )
        }
        HsvChannelPickers(
            tokenId = tagPrefix,
            label = label,
            color = baseColor,
            hsv = hsv,
            supportsAlpha = true,
            svTestTag = "ciacola-$tagPrefix-sv-canvas",
            hueTestTag = "ciacola-$tagPrefix-hue-bar",
            alphaTestTag = "ciacola-$tagPrefix-alpha-bar",
            onGestureStart = onGestureStart,
            onGestureEnd = onGestureEnd,
            onHsvAndColorChanged = { updated, nextColor, coalesce ->
                hsv = updated
                onPick(nextColor.toHexString(includeAlpha = nextColor.alpha < 0.999f), coalesce)
            },
        )
    }
}

/**
 * Places the colour popover right-aligned under its row, flipping above when the space below is
 * too short and always keeping an 8dp margin inside the window, so its border is never clipped.
 */
internal fun colorPopoverOffset(anchor: IntRect, windowSize: IntSize, contentSize: IntSize, gapPx: Int, marginPx: Int): IntOffset {
    val maxX = (windowSize.width - contentSize.width - marginPx).coerceAtLeast(marginPx)
    val x = (anchor.right - contentSize.width).coerceIn(marginPx, maxX)
    val below = anchor.bottom + gapPx
    val above = anchor.top - gapPx - contentSize.height
    val y = when {
        below + contentSize.height <= windowSize.height - marginPx -> below
        above >= marginPx -> above
        else -> (windowSize.height - contentSize.height - marginPx).coerceAtLeast(marginPx)
    }
    return IntOffset(x, y)
}

private class ColorPopoverPositionProvider(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = colorPopoverOffset(anchorBounds, windowSize, popupContentSize, gapPx, marginPx)
}
