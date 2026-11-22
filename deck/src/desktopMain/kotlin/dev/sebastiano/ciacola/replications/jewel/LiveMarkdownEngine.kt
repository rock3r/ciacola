package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ---------------------------------------------------------------------------
// Live Jewel Markdown Block Renderer
// ---------------------------------------------------------------------------

@Composable
fun LiveJewelMarkdownView(
    markdown: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    baseFontSizeSp: Float = 12.5f,
    blockSpacing: Dp = 8.dp,
    showCodeActionBar: Boolean = false,
) {
    val blocks = remember(markdown) { parseMarkdownDocument(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(blockSpacing),
    ) {
        blocks.forEach { block ->
            RenderMarkdownBlock(
                block = block,
                isDark = isDark,
                baseFontSizeSp = baseFontSizeSp,
                showCodeActionBar = showCodeActionBar,
            )
        }
    }
}

@Composable
private fun RenderMarkdownBlock(
    block: MarkdownBlock,
    isDark: Boolean,
    baseFontSizeSp: Float,
    showCodeActionBar: Boolean,
) {
    val bodyColor = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2328)
    val mutedColor = if (isDark) Color(0xFF9DA0A8) else Color(0xFF57606A)
    val dividerColor = if (isDark) Color(0xFF393B40) else Color(0xFFD8DEE4)

    when (block) {
        is MarkdownBlock.Heading -> {
            val size = when (block.level) {
                1 -> (baseFontSizeSp * 1.72f).sp
                2 -> (baseFontSizeSp * 1.36f).sp
                else -> (baseFontSizeSp * 1.12f).sp
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    text = renderInlines(block.inlines, isDark, bodyColor),
                    style = TextStyle(
                        color = bodyColor,
                        fontSize = size,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (size.value * 1.28f).sp,
                    ),
                )
                if (block.level <= 2) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(dividerColor))
                }
            }
        }

        is MarkdownBlock.Paragraph -> {
            BasicText(
                text = renderInlines(block.inlines, isDark, bodyColor),
                style = TextStyle(
                    color = bodyColor,
                    fontSize = baseFontSizeSp.sp,
                    lineHeight = (baseFontSizeSp * 1.48f).sp,
                ),
            )
        }

        is MarkdownBlock.Callout -> {
            val accent = when (block.kind) {
                MarkdownCalloutKind.Tip -> if (isDark) Color(0xFF57AB5A) else Color(0xFF1A7F37)
                MarkdownCalloutKind.Warning -> if (isDark) Color(0xFFD29922) else Color(0xFF9A6700)
                MarkdownCalloutKind.Important -> if (isDark) Color(0xFFA371F7) else Color(0xFF8250DF)
                MarkdownCalloutKind.Note -> if (isDark) Color(0xFF58A6FF) else Color(0xFF0969DA)
            }
            val bg = when (block.kind) {
                MarkdownCalloutKind.Tip -> if (isDark) Color(0xFF1C2B20) else Color(0xFFF3FBF5)
                MarkdownCalloutKind.Warning -> if (isDark) Color(0xFF2D2618) else Color(0xFFFFF8E6)
                MarkdownCalloutKind.Important -> if (isDark) Color(0xFF271F35) else Color(0xFFF8F4FF)
                MarkdownCalloutKind.Note -> if (isDark) Color(0xFF1A2535) else Color(0xFFF0F6FF)
            }
            val shape = RoundedCornerShape(6.dp)
            CompositionLocalProvider(LocalEffectiveBackgroundColor provides bg) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(shape)
                        .background(bg, shape)
                        .border(1.dp, accent.copy(alpha = 0.25f), shape),
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val iconKey = when (block.kind) {
                                MarkdownCalloutKind.Warning -> AllIconsKeys.General.Warning
                                MarkdownCalloutKind.Tip -> AllIconsKeys.General.Information
                                MarkdownCalloutKind.Important -> AllIconsKeys.General.ExclMark
                                MarkdownCalloutKind.Note -> AllIconsKeys.General.Information
                            }
                            Icon(iconKey, contentDescription = block.kind.title, modifier = Modifier.size(13.dp), tint = accent)
                            Text(
                                text = block.kind.title,
                                color = accent,
                                fontSize = (baseFontSizeSp - 0.5f).sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        block.blocks.forEach { inner ->
                            RenderMarkdownBlock(inner, isDark, baseFontSizeSp - 0.5f, showCodeActionBar = false)
                        }
                    }
                }
            }
        }

        is MarkdownBlock.BlockQuote -> {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(dividerColor, RoundedCornerShape(2.dp)))
                Column(
                    modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.blocks.forEach { inner ->
                        RenderMarkdownBlock(inner, isDark, baseFontSizeSp, showCodeActionBar = false)
                    }
                }
            }
        }

        is MarkdownBlock.CodeBlock -> {
            val codeBg = if (isDark) Color(0xFF1A1B1E) else Color(0xFFF3F4F6)
            val codeBorder = if (isDark) Color(0xFF33363C) else Color(0xFFDCDFE5)
            val shape = RoundedCornerShape(6.dp)
            CompositionLocalProvider(LocalEffectiveBackgroundColor provides codeBg) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(codeBg, shape)
                        .border(1.dp, codeBorder, shape),
                ) {
                    if (showCodeActionBar || block.language.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isDark) Color(0xFF23252A) else Color(0xFFEAEDF1))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = block.language.ifEmpty { "code" },
                                color = mutedColor,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (showCodeActionBar) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CodeActionBadge(AllIconsKeys.Actions.Copy, "Copy", isDark)
                                    CodeActionBadge(AllIconsKeys.General.Add, "Insert", isDark)
                                }
                            }
                        }
                    }
                    BasicText(
                        text = remember(block.code, isDark) { IdeCodeHighlighter.highlight(block.code, isDark) },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseFontSizeSp - 1.2f).sp,
                            lineHeight = (baseFontSizeSp * 1.35f).sp,
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }

        is MarkdownBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", color = mutedColor, fontSize = baseFontSizeSp.sp)
                            BasicText(
                                text = renderInlines(item.inlines, isDark, bodyColor),
                                style = TextStyle(
                                    color = bodyColor,
                                    fontSize = baseFontSizeSp.sp,
                                    lineHeight = (baseFontSizeSp * 1.44f).sp,
                                ),
                            )
                        }
                        if (item.children.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item.children.forEach { child ->
                                    RenderMarkdownBlock(child, isDark, baseFontSizeSp, showCodeActionBar = false)
                                }
                            }
                        }
                    }
                }
            }
        }

        is MarkdownBlock.OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { idx, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${block.start + idx}.",
                                color = mutedColor,
                                fontSize = baseFontSizeSp.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            BasicText(
                                text = renderInlines(item.inlines, isDark, bodyColor),
                                style = TextStyle(
                                    color = bodyColor,
                                    fontSize = baseFontSizeSp.sp,
                                    lineHeight = (baseFontSizeSp * 1.44f).sp,
                                ),
                            )
                        }
                        if (item.children.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item.children.forEach { child ->
                                    RenderMarkdownBlock(child, isDark, baseFontSizeSp, showCodeActionBar = false)
                                }
                            }
                        }
                    }
                }
            }
        }

        is MarkdownBlock.TaskList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Checkbox(checked = item.checked, onCheckedChange = {}, enabled = true)
                            BasicText(
                                text = renderInlines(item.content, isDark, bodyColor),
                                style = TextStyle(
                                    color = if (item.checked) mutedColor else bodyColor,
                                    fontSize = baseFontSizeSp.sp,
                                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                            )
                        }
                        if (item.children.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item.children.forEach { child ->
                                    RenderMarkdownBlock(child, isDark, baseFontSizeSp, showCodeActionBar = false)
                                }
                            }
                        }
                    }
                }
            }
        }

        is MarkdownBlock.Table -> {
            val headerBg = if (isDark) Color(0xFF26282E) else Color(0xFFF3F5F8)
            val altBg = if (isDark) Color(0xFF212328) else Color(0xFFFAFBFD)
            val shape = RoundedCornerShape(6.dp)
            val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
            val colWeights = remember(block.headers, block.rows, colCount) {
                List(colCount) { colIdx ->
                    val headerLen = block.headers.getOrNull(colIdx)?.let(::inlineVisualWeight) ?: 0f
                    val maxRowLen = block.rows.maxOfOrNull { row ->
                        row.getOrNull(colIdx)?.let(::inlineVisualWeight) ?: 0f
                    } ?: 0f
                    maxOf(headerLen, maxRowLen).coerceIn(5.5f, 30f)
                }
            }
            val tableFontSize = (baseFontSizeSp - 1.5f).coerceAtLeast(9.4f).sp
            val tableCodeFontSize = (tableFontSize.value - 0.3f).coerceAtLeast(9.0f).sp
            fun textAlignFor(colIdx: Int): TextAlign = when (block.alignments.getOrNull(colIdx)) {
                MarkdownTableAlignment.Center -> TextAlign.Center
                MarkdownTableAlignment.Right -> TextAlign.End
                else -> TextAlign.Start
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, dividerColor, shape),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    block.headers.forEachIndexed { colIdx, hCell ->
                        BasicText(
                            text = renderInlines(hCell, isDark, bodyColor, codeFontSize = tableCodeFontSize),
                            style = TextStyle(
                                color = bodyColor,
                                fontSize = tableFontSize,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = textAlignFor(colIdx),
                            ),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(colWeights.getOrElse(colIdx) { 10f }),
                        )
                    }
                }
                block.rows.forEachIndexed { rowIdx, row ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(dividerColor))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (rowIdx % 2 == 1) altBg else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEachIndexed { colIdx, cell ->
                            val singleToken = isSingleUnbreakableToken(cell)
                            BasicText(
                                text = renderInlines(cell, isDark, bodyColor, codeFontSize = tableCodeFontSize),
                                style = TextStyle(
                                    color = bodyColor,
                                    fontSize = tableFontSize,
                                    textAlign = textAlignFor(colIdx),
                                ),
                                maxLines = if (singleToken) 1 else Int.MAX_VALUE,
                                softWrap = !singleToken,
                                modifier = Modifier.weight(colWeights.getOrElse(colIdx) { 10f }),
                            )
                        }
                    }
                }
            }
        }

        MarkdownBlock.ThematicBreak -> {
            Box(Modifier.fillMaxWidth().height(1.dp).background(dividerColor))
        }
    }
}

@Composable
private fun CodeActionBadge(
    iconKey: org.jetbrains.jewel.ui.icon.IconKey,
    label: String,
    isDark: Boolean,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isDark) Color(0xFF2E3138) else Color(0xFFE0E4EA))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(iconKey, contentDescription = label, modifier = Modifier.size(11.dp))
        Text(label, fontSize = 10.sp, color = if (isDark) Color(0xFFCED0D6) else Color(0xFF343A40))
    }
}

private fun inlineVisualWeight(inlines: List<MarkdownInline>): Float =
    inlines.sumOf { node ->
        when (node) {
            is MarkdownInline.Plain -> node.text.length.toDouble()
            is MarkdownInline.Code -> (node.text.length + 2) * 1.42
            is MarkdownInline.Bold -> inlineVisualWeight(node.children) * 1.06
            is MarkdownInline.Italic -> inlineVisualWeight(node.children).toDouble()
            is MarkdownInline.Strike -> inlineVisualWeight(node.children).toDouble()
            is MarkdownInline.Link -> inlineVisualWeight(node.children).toDouble()
        }
    }.toFloat()

private fun isSingleUnbreakableToken(inlines: List<MarkdownInline>): Boolean {
    if (inlines.size == 1) {
        return when (val only = inlines[0]) {
            is MarkdownInline.Code -> !only.text.trim().contains(' ')
            is MarkdownInline.Plain -> !only.text.trim().contains(' ')
            else -> false
        }
    }
    return false
}

private fun renderInlines(
    inlines: List<MarkdownInline>,
    isDark: Boolean,
    baseColor: Color,
    codeFontSize: androidx.compose.ui.unit.TextUnit = 10.5.sp,
): AnnotatedString = buildAnnotatedString {
    val linkColor = if (isDark) Color(0xFF589DF6) else Color(0xFF2470B3)
    val inlineCodeBg = if (isDark) Color(0xFF2F323A) else Color(0xFFEAECEF)
    val inlineCodeFg = if (isDark) Color(0xFFE2B97B) else Color(0xFF24292F)

    fun appendNodes(nodes: List<MarkdownInline>, textColor: Color = baseColor) {
        for (node in nodes) {
            when (node) {
                is MarkdownInline.Plain -> withStyle(SpanStyle(color = textColor)) { append(node.text) }
                is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) {
                    appendNodes(node.children, textColor)
                }
                is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = textColor)) {
                    appendNodes(node.children, textColor)
                }
                is MarkdownInline.Strike -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = textColor.copy(alpha = 0.72f)),
                ) {
                    appendNodes(node.children, textColor)
                }
                is MarkdownInline.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineCodeBg,
                        color = inlineCodeFg,
                        fontSize = codeFontSize,
                    ),
                ) {
                    append("\u200A${node.text}\u200A")
                }
                is MarkdownInline.Link -> withStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ) {
                    appendNodes(node.children, linkColor)
                }
            }
        }
    }
    appendNodes(inlines)
}

// ---------------------------------------------------------------------------
// Shared Live Split-Pane Jewel Standalone Sample Window (Jewel S36 & NJPT S125)
// ---------------------------------------------------------------------------

/**
 * Shared live Jewel Standalone Sample Markdown split editor + real AST renderer powering both
 * `jewel.markdownStandaloneSample` (Jewel Talk S36) and `njpt.jewelMarkdownSplitDemo` (NJPT S125).
 */
@Composable
fun JewelMarkdownSplitWindow(
    loopMillis: Long,
    periodMs: Long,
    presetName: String,
    baseMarkdown: String,
    typedAddition: String,
    insertionMarker: String,
    interactiveId: String,
    isInteractive: Boolean = true,
    initialDarkTheme: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var userEditedText by remember { mutableStateOf<String?>(null) }
    var darkThemeOverride by remember { mutableStateOf<Boolean?>(null) }
    var selectedPreset by remember { mutableStateOf(presetName) }

    val isDark = darkThemeOverride ?: initialDarkTheme
    val t = loopProgress(loopMillis, periodMs)
    val typePhase = segmentProgress(t, 0.08f, 0.58f)
    val scrollPhase = easeInOutSine(segmentProgress(t, 0.62f, 0.94f))

    val scriptedMarkdown = remember(baseMarkdown, typedAddition, insertionMarker, typePhase) {
        val typed = deterministicTypewriter(typedAddition, easeOutCubic(typePhase))
        if (insertionMarker.isNotEmpty() && baseMarkdown.contains(insertionMarker)) {
            baseMarkdown.replace(insertionMarker, typed)
        } else {
            baseMarkdown + typed
        }
    }
    val activeMarkdown = userEditedText ?: scriptedMarkdown

    val panelBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val toolbarBg = if (isDark) Color(0xFF2B2D30) else Color(0xFFF7F8FA)
    val editorBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val editorFg = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2328)
    val dividerColor = if (isDark) Color(0xFF393B40) else Color(0xFFDCDDE2)

    JewelWindowFrame(
        title = "Jewel standalone sample",
        isDark = isDark,
        darkTitleBar = true,
        surfaceColor = panelBg,
        interactiveId = interactiveId,
        interactiveLabel = "Jewel Markdown Split Editor",
        isInteractive = isInteractive,
        modifier = modifier,
        leadingTitleContent = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF3B3E45))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("M↓", color = Color(0xFFE0E2E7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Markdown ▾", color = Color(0xFFE0E2E7), fontSize = 11.sp)
            }
        },
        trailingTitleContent = {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3B3E45)),
                contentAlignment = Alignment.Center,
            ) {
                Text("GH", color = Color(0xFFDFE1E5), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF3B3E45))
                    .clickable(enabled = isInteractive) { darkThemeOverride = !isDark }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (isDark) "☀" else "☾",
                    color = Color(0xFFFFD66B),
                    fontSize = 11.sp,
                )
            }
        },
    ) {
        // Split-pane content
        Row(modifier = Modifier.fillMaxSize()) {
            // Left pane: Toolbar + raw Markdown editor
            Column(
                modifier = Modifier
                    .weight(0.49f)
                    .fillMaxHeight()
                    .background(editorBg),
            ) {
                CompositionLocalProvider(LocalEffectiveBackgroundColor provides toolbarBg) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(toolbarBg)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { if (isInteractive) userEditedText = null },
                        ) {
                            Text("Load file…", fontSize = 11.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { if (isInteractive) userEditedText = "# Cleared\n\nType live Markdown here!" },
                        ) {
                            Text("Clear", fontSize = 11.sp, maxLines = 1)
                        }
                        Dropdown(
                            menuContent = {
                                selectableItem(
                                    selected = selectedPreset == presetName,
                                    onClick = {
                                        selectedPreset = presetName
                                        userEditedText = null
                                    },
                                ) {
                                    Text(presetName)
                                }
                                selectableItem(
                                    selected = selectedPreset == "GFM Showcase",
                                    onClick = {
                                        selectedPreset = "GFM Showcase"
                                        userEditedText = GFM_SHOWCASE_PRESET
                                    },
                                ) {
                                    Text("GFM Showcase")
                                }
                            },
                        ) {
                            Text(selectedPreset, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                val leftScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clipToBounds()
                        .verticalScroll(leftScroll),
                ) {
                    if (isInteractive && userEditedText != null) {
                        BasicTextField(
                            value = activeMarkdown,
                            onValueChange = { userEditedText = it },
                            textStyle = TextStyle(
                                color = editorFg,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.5.sp,
                            ),
                            cursorBrush = SolidColor(Color(0xFF3574F0)),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val caretSuffix = if (typePhase in 0.01f..0.99f && caretVisible(loopMillis)) "│" else ""
                        BasicText(
                            text = activeMarkdown + caretSuffix,
                            style = TextStyle(
                                color = editorFg,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.5.sp,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { translationY = -scrollPhase * 96f }
                                .clickable(enabled = isInteractive) { userEditedText = activeMarkdown },
                        )
                    }
                }
            }

            // Center splitter with thin vertical handle
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(dividerColor),
            )

            // Right pane: Live rendered Jewel Markdown AST preview
            val rightScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .weight(0.51f)
                    .fillMaxHeight()
                    .background(panelBg)
                    .clipToBounds(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .verticalScroll(rightScroll)
                        .graphicsLayer {
                            translationY = if (userEditedText == null) -scrollPhase * 110f else 0f
                        },
                ) {
                    LiveJewelMarkdownView(
                        markdown = activeMarkdown,
                        isDark = isDark,
                        baseFontSizeSp = 11.5f,
                        blockSpacing = 6.dp,
                        showCodeActionBar = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Subtle deterministic scrollbar indicator on right edge
                Canvas(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(6.dp)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp, horizontal = 1.dp),
                ) {
                    val thumbH = (size.height * 0.32f).coerceAtLeast(20f)
                    val thumbY = (size.height - thumbH) * scrollPhase
                    drawLine(
                        color = (if (isDark) Color(0xFF5A5D66) else Color(0xFFC4C8D0)),
                        start = Offset(size.width / 2f, thumbY),
                        end = Offset(size.width / 2f, thumbY + thumbH),
                        strokeWidth = 3.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private val GFM_SHOWCASE_PRESET = """
# Jewel Markdown Showcase

Live CommonMark + **GFM** parsed into immutable `MarkdownBlock` AST nodes and rendered with *Compose for Desktop*.

> [!TIP]
> Edit the source pane directly or click the moon/sun toggle to switch `IntUiTheme` live!

| Feature | Status | Notes |
|---|---|---|
| Headings & inline code | Done | `AnnotatedString` spans |
| GFM Callouts | Done | Tip, Warning, Note |
| Task Lists | Done | Real Jewel `Checkbox` |

- [x] Parse CommonMark AST outside composition
- [x] Render nested blocks recursively
- [ ] Enjoy ~~HTML hacks~~ clean Compose UI
""".trimIndent()
