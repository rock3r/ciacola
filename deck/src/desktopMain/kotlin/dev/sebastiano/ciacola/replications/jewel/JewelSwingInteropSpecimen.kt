package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font as AwtFont
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
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
// #11 — jewel.swingComposeInterop (Jewel Talk S42–S43)
// ============================================================================

private val THEME_JSON_SAMPLE = """
{
  "name": "Studio Themes",
  "author": "Chris Sinco",
  "dark": true,
  "nameKey": "studio.theme.name",
  "editorScheme": "Dark",
  "colors": {
    "primaryRed": "#821010"
  },
  "ui": {
    "Button.background": "primaryRed"
  }
}
""".trimIndent()

/**
 * Specimen #11 (`jewel.swingComposeInterop`, Jewel Talk S42–S43):
 * Full IntelliJ IDEA `StudioThemes` window showing Swing Project tree + JSON Editor
 * seamlessly hosting a bottom docked `Jewel Demo` `JewelComposePanel` with matching
 * IntelliJ New UI typography, borders, and interactive Jewel components!
 */
@Composable
fun SwingComposeInteropSpecimen(
    loopMillis: Long = 0L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var outlinedCount by remember { mutableIntStateOf(0) }
    var defaultCount by remember { mutableIntStateOf(0) }
    var themedChecked by remember { mutableStateOf(false) }
    var validationChecked by remember { mutableStateOf(false) }
    var enabledChecked by remember { mutableStateOf(true) }
    var selectedRadio by remember { mutableIntStateOf(0) }
    var selectedToolTab by remember { mutableIntStateOf(0) }
    var selectedTreeRow by remember { mutableIntStateOf(5) }

    JewelWindowFrame(
        title = "StudioThemes — StudioThemes.theme.json",
        isDark = false,
        darkTitleBar = true,
        surfaceColor = Color.White,
        interactiveId = "specimen-swing-compose-interop",
        interactiveLabel = "Swing + JewelComposePanel Interop",
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
                        .background(Color(0xFF7B9E2E))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text("ST", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text("StudioThemes ▾", fontSize = 11.sp, color = Color(0xFFDFE1E5))
                Text("⎇ main ▾", fontSize = 10.5.sp, color = Color(0xFF9DA0A8))
            }
        },
        trailingTitleContent = {
            Text("Current File ▾", fontSize = 10.5.sp, color = Color(0xFFCED0D6))
            Icon(AllIconsKeys.Actions.Execute, contentDescription = "Run", modifier = Modifier.size(13.dp))
        },
    ) {
        // Upper Half: Real javax.swing JSplitPane hosting a javax.swing.JTree + javax.swing.JTextArea
        // painted offscreen onto a deterministic BufferedImage (headless / Still / Video / Lint safe).
        BoxWithConstraints(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxWidth()
                .background(Color.White)
                .clickable(enabled = isInteractive) {
                    selectedTreeRow = (selectedTreeRow + 1) % SWING_TREE_ROW_COUNT
                },
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.roundToPx() }.coerceIn(0, 2048)
            val heightPx = with(density) { maxHeight.roundToPx() }.coerceIn(0, 1024)
            if (widthPx > 0 && heightPx > 0) {
                val swingBitmap = remember(widthPx, heightPx, selectedTreeRow) {
                    renderRealSwingIdePaneBitmap(
                        widthPx = widthPx,
                        heightPx = heightPx,
                        selectedTreeRow = selectedTreeRow,
                    ).toComposeImageBitmap()
                }
                Image(
                    bitmap = swingBitmap,
                    contentDescription = "IntelliJ Swing JTree and JTextArea editor",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        // Lower Half: Docked JewelComposePanel ("Jewel Demo" tool window)
        Column(modifier = Modifier.weight(0.58f).fillMaxWidth().background(Color(0xFFF7F8FA))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(Color(0xFFF2F3F6))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Jewel Demo", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2328))
                listOf("Components", "Releases Demo", "Swing Comparison", "Scrollbars Sample").forEachIndexed { i, tab ->
                    ProfilerTabChip(tab, selected = selectedToolTab == i) {
                        if (isInteractive) selectedToolTab = i
                    }
                }
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            Row(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Left column: Real Jewel IntUi widgets
                Column(
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Here is a selection of our finest components (activated: false):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1F2328),
                    )
                    Dropdown(menuContent = {}) {
                        Text("Selected item -1", fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { if (isInteractive) outlinedCount++ },
                            enabled = enabledChecked,
                        ) { Text("Outlined: $outlinedCount", fontSize = 10.5.sp) }
                        OutlinedButton(onClick = {}, enabled = false) { Text("Outlined", fontSize = 10.5.sp) }
                        DefaultButton(
                            onClick = { if (isInteractive) defaultCount++ },
                            enabled = enabledChecked,
                        ) { Text("Default: $defaultCount", fontSize = 10.5.sp) }
                        DefaultButton(onClick = {}, enabled = false) { Text("Default", fontSize = 10.5.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckboxRow(
                            text = "Hello, I am a themed checkbox",
                            checked = themedChecked,
                            onCheckedChange = { if (isInteractive) themedChecked = it },
                            enabled = enabledChecked,
                        )
                        CheckboxRow(
                            text = "Show validation",
                            checked = validationChecked,
                            onCheckedChange = { if (isInteractive) validationChecked = it },
                            enabled = enabledChecked,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButtonRow(
                            text = "I am number one",
                            selected = selectedRadio == 0,
                            onClick = { if (isInteractive) selectedRadio = 0 },
                            enabled = enabledChecked,
                        )
                        RadioButtonRow(
                            text = "Sad second",
                            selected = selectedRadio == 1,
                            onClick = { if (isInteractive) selectedRadio = 1 },
                            enabled = enabledChecked,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Circular progress small:", fontSize = 10.5.sp, color = Color(0xFF4C5058))
                        DeterministicIdeSpinner(loopMillis, size = 14.dp, color = Color(0xFF6C707E))
                        Text("big:", fontSize = 10.5.sp, color = Color(0xFF4C5058))
                        DeterministicIdeSpinner(loopMillis + 200L, size = 20.dp, color = Color(0xFF6C707E))
                        OutlinedButton(onClick = {}, enabled = enabledChecked) { Text("Hover Me!", fontSize = 10.5.sp) }
                    }
                }

                // Right column: Enabled checkbox + embedded Markdown box + mini tree
                Column(
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CheckboxRow(
                        text = "Enabled",
                        checked = enabledChecked,
                        onCheckedChange = { if (isInteractive) enabledChecked = it },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFF3574F0).copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                    ) {
                        LiveJewelMarkdownView(
                            markdown = """
                                Hi! This is an example of **Markdown** rendering. We support the [CommonMark](https://commonmark.org) specs out of the box.

                                - Images & Tables
                                - `fun hello() = "World"`
                            """.trimIndent(),
                            isDark = false,
                            baseFontSizeSp = 10.5f,
                        )
                    }
                    Text("activated: false", fontSize = 10.sp, color = Color(0xFF6E737D))
                    Text("▸ root 1    ▸ root 2    ▸ root 3", fontSize = 10.5.sp, color = Color(0xFF27282E))
                }
            }

            // Bottom IDE Status Bar
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(Color(0xFFF2F3F6))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("StudioThemes › theme › StudioThemes.theme.json", fontSize = 9.5.sp, color = Color(0xFF5A5E66))
                Text("5:34   LF   UTF-8   2 spaces   Swing JTree + JewelComposePanel", fontSize = 9.5.sp, color = Color(0xFF5A5E66))
            }
        }
    }
}

private const val SWING_TREE_ROW_COUNT: Int = 6

/**
 * Instantiates genuine `javax.swing` components ([JSplitPane], [JTree], [JTextArea], [JPanel],
 * [JLabel]) and paints them deterministically into an offscreen [BufferedImage] without realizing
 * any native window peer — safe across live presentation, `ImageComposeScene`, PDF/Video export,
 * and `480x270` lint rendering.
 */
internal fun renderRealSwingIdePaneBitmap(
    widthPx: Int,
    heightPx: Int,
    selectedTreeRow: Int = 5,
): BufferedImage {
    val safeW = widthPx.coerceAtLeast(1)
    val safeH = heightPx.coerceAtLeast(1)
    val surfaceBg = java.awt.Color(0xF7, 0xF8, 0xFA)
    val borderColor = java.awt.Color(0xDC, 0xE0, 0xE6)
    val textColor = java.awt.Color(0x1F, 0x23, 0x28)
    val mutedColor = java.awt.Color(0x8C, 0x91, 0x9B)
    val selectionBg = java.awt.Color(0xD4, 0xE2, 0xFF)
    val accentBlue = java.awt.Color(0x35, 0x74, 0xF0)

    val uiFont = AwtFont(AwtFont.SANS_SERIF, AwtFont.PLAIN, 12)
    val uiBoldFont = AwtFont(AwtFont.SANS_SERIF, AwtFont.BOLD, 12)
    val monoFont = AwtFont(AwtFont.MONOSPACED, AwtFont.PLAIN, 12)

    // Real javax.swing.JTree with DefaultMutableTreeNode hierarchy
    val root = DefaultMutableTreeNode("StudioThemes")
    val ideaNode = DefaultMutableTreeNode(".idea")
    val outNode = DefaultMutableTreeNode("out / production")
    val metaInfNode = DefaultMutableTreeNode("META-INF")
    val themeNode = DefaultMutableTreeNode("theme")
    val themeFileNode = DefaultMutableTreeNode("StudioThemes.theme.json")
    themeNode.add(themeFileNode)
    outNode.add(metaInfNode)
    outNode.add(themeNode)
    root.add(ideaNode)
    root.add(outNode)

    val tree = JTree(root).apply {
        background = surfaceBg
        foreground = textColor
        font = uiFont
        isOpaque = true
        rowHeight = 20
        showsRootHandles = true
        border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        cellRenderer = DefaultTreeCellRenderer().apply {
            backgroundNonSelectionColor = surfaceBg
            backgroundSelectionColor = selectionBg
            borderSelectionColor = selectionBg
            textNonSelectionColor = textColor
            textSelectionColor = textColor
            font = uiFont
            openIcon = null
            closedIcon = null
            leafIcon = null
        }
    }
    var r = 0
    while (r < tree.rowCount) {
        tree.expandRow(r)
        r++
    }
    if (tree.rowCount > 0) {
        tree.setSelectionRow(selectedTreeRow.coerceIn(0, tree.rowCount - 1))
    }

    val leftHeader = JLabel("Project  (javax.swing.JTree)").apply {
        font = uiBoldFont
        foreground = textColor
        background = surfaceBg
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
            BorderFactory.createEmptyBorder(4, 10, 4, 8),
        )
        preferredSize = Dimension(220, 26)
    }
    val leftPane = JPanel(BorderLayout()).apply {
        background = surfaceBg
        isOpaque = true
        add(leftHeader, BorderLayout.NORTH)
        add(tree, BorderLayout.CENTER)
    }

    // Real javax.swing.JTextArea editor with line-number gutter
    val tabLabel = JLabel("{} StudioThemes.theme.json").apply {
        font = uiBoldFont
        foreground = textColor
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 2, 0, accentBlue),
            BorderFactory.createEmptyBorder(4, 8, 2, 8),
        )
    }
    val rightHeader = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        background = surfaceBg
        isOpaque = true
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor)
        preferredSize = Dimension(400, 26)
        add(tabLabel)
    }
    val lineNumbers = (1..THEME_JSON_SAMPLE.lines().size).joinToString("\n") { it.toString().padStart(2, ' ') }
    val gutterArea = JTextArea(lineNumbers).apply {
        font = monoFont
        foreground = mutedColor
        background = java.awt.Color(0xFA, 0xFA, 0xFC)
        isOpaque = true
        isEditable = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, borderColor),
            BorderFactory.createEmptyBorder(6, 8, 6, 8),
        )
    }
    val editorArea = JTextArea(THEME_JSON_SAMPLE).apply {
        font = monoFont
        foreground = textColor
        background = java.awt.Color.WHITE
        isOpaque = true
        isEditable = false
        border = BorderFactory.createEmptyBorder(6, 10, 6, 8)
    }
    val editorBody = JPanel(BorderLayout()).apply {
        background = java.awt.Color.WHITE
        isOpaque = true
        add(gutterArea, BorderLayout.WEST)
        add(editorArea, BorderLayout.CENTER)
    }
    val rightPane = JPanel(BorderLayout()).apply {
        background = java.awt.Color.WHITE
        isOpaque = true
        add(rightHeader, BorderLayout.NORTH)
        add(editorBody, BorderLayout.CENTER)
    }

    val dividerPx = (safeW * 0.28).toInt().coerceIn(minOf(safeW / 2, 160), minOf(safeW - 1, 260))
    val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPane).apply {
        border = BorderFactory.createEmptyBorder()
        dividerSize = 1
        background = borderColor
        isOpaque = true
        setBounds(0, 0, safeW, safeH)
        setDividerLocation(dividerPx)
    }

    disableDoubleBufferingRecursively(splitPane)
    layoutSwingHierarchy(splitPane)
    // Guarantee deterministic split geometry even when Swing UI delegates defer layout without a native peer.
    if (leftPane.width <= 0 || rightPane.width <= 0) {
        leftPane.setBounds(0, 0, dividerPx, safeH)
        rightPane.setBounds(dividerPx + 1, 0, (safeW - dividerPx - 1).coerceAtLeast(1), safeH)
        layoutSwingHierarchy(leftPane)
        layoutSwingHierarchy(rightPane)
    }

    val image = BufferedImage(safeW, safeH, BufferedImage.TYPE_INT_ARGB)
    val g2d = image.createGraphics()
    try {
        configureSwingGraphics(g2d)
        g2d.color = java.awt.Color.WHITE
        g2d.fillRect(0, 0, safeW, safeH)
        splitPane.paint(g2d)
    } finally {
        g2d.dispose()
    }
    return image
}

private fun configureSwingGraphics(g2d: Graphics2D) {
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
}

private fun disableDoubleBufferingRecursively(component: Component) {
    if (component is JComponent) {
        component.isDoubleBuffered = false
    }
    if (component is Container) {
        for (i in 0 until component.componentCount) {
            disableDoubleBufferingRecursively(component.getComponent(i))
        }
    }
}

private fun layoutSwingHierarchy(container: Container) {
    container.doLayout()
    for (i in 0 until container.componentCount) {
        val child = container.getComponent(i)
        if (child is Container) {
            layoutSwingHierarchy(child)
        }
    }
}
