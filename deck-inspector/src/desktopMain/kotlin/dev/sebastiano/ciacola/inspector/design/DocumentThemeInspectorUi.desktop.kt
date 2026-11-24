package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.transitionDisplayName
import dev.sebastiano.ciacola.inspector.document.DOCUMENT_THEME_COLOR_KEYS
import dev.sebastiano.ciacola.inspector.document.DOCUMENT_TYPOGRAPHY_ROLES
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.inspector.element.ColorSwatch
import dev.sebastiano.ciacola.inspector.element.CompactSliderRow
import dev.sebastiano.ciacola.inspector.element.ModifiedDot
import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import dev.sebastiano.ciacola.theme.ReducedMotionMode
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import java.nio.file.Path
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.separator

private enum class ThemeInspectorSectionTab(val label: String) {
    Colors("Colors"),
    Typography("Type"),
    Motion("Motion defaults"),
    Palettes("Palettes"),
}

/** Readable labels for theme presets. */
internal fun themePresetLabel(preset: String): String = when (preset.lowercase()) {
    "stagedark" -> "Dark"
    "keynotelight" -> "Light"
    else -> humanizeTokenKey(preset)
}

/** Default-curve choices offered by the Motion defaults section (readable names only). */
internal val THEME_CURVE_CHOICES: List<Curve> = listOf(
    Curve.Preset("fastOutSlowIn"),
    Curve.Preset("easeInOut"),
    Curve.Preset("easeOut"),
    Curve.Preset("linear"),
    Curve.Spring(damping = 0.8, stiffness = 380.0),
)

/** `true` for a theme curve that is set but is not one of [THEME_CURVE_CHOICES] (exact identity). */
internal fun isCustomThemeCurve(curve: Curve?): Boolean = curve != null && curve !in THEME_CURVE_CHOICES

/**
 * Label for the theme's default curve: the renderer default reads `Standard (default)` when
 * unset, presets and standard shapes use their readable name, and anything else reads `Custom`
 * / `Custom spring`.
 */
internal fun themeCurveLabel(curve: Curve?): String = when {
    curve == null -> "${curveDisplayName(THEME_CURVE_CHOICES.first())} (default)"
    !isCustomThemeCurve(curve) -> curveDisplayName(curve)
    curve is Curve.Spring -> "Custom spring"
    // A non-preset curve that still matches a standard shape keeps its readable name (`Ease in-out`).
    else -> curveDisplayName(curve).takeUnless { it == "Custom" } ?: "Custom"
}

/**
 * Deck-level Theme editor bound to `document.themes[activeTheme]` and `document.palettes` on the
 * unified [dev.sebastiano.ciacola.document.DeckDocumentStore] undo stack.
 *
 * Flat collapsible sections, readable token names (`Elevated Surface`) and curve names
 * (`Standard`, `Ease in-out`). Undo/Redo live in the App Bar; pass [showHeaderUndoRedo] only for
 * standalone hosting. The `Dark | Light` base row only appears when the theme selector cannot
 * already switch between several themes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentThemeInspectorUi(
    controller: DocumentThemeInspectorController,
    onPickDeckFileToCopyThemes: (() -> Path?)? = null,
    onPickCodeSchemeFile: (() -> Path?)? = null,
    modifier: Modifier = Modifier,
    showHeaderUndoRedo: Boolean = false,
) {
    controller.store.revision
    val doc = controller.store.document
    val themeSpec = controller.currentThemeSpec()
    val resolvedColors = remember(themeSpec) { DocumentThemeResolver.resolveThemeColorMap(themeSpec) }
    val colors = JewelTheme.globalColors
    val scroll = rememberScrollState()
    var activeTab by remember { mutableStateOf(ThemeInspectorSectionTab.Colors) }
    val themeNames = controller.availableThemeNames()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.panelBackground)
            .testTag("ciacola-doc-theme-inspector"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val visibility = themeHeaderVisibility(themeNames, controller.activeThemeName)
            if (visibility.showSelector || showHeaderUndoRedo) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (visibility.showSelector) {
                        Text("Theme", style = inspectorBodyStyle(), color = colors.text.info)
                        Dropdown(
                            modifier = Modifier.weight(1f).height(InspectorTokens.RowHeight).testTag("ciacola-doc-theme-selector"),
                            menuContent = {
                                for (name in themeNames) {
                                    selectableItem(
                                        selected = controller.activeThemeName == name,
                                        onClick = { controller.selectTheme(name) },
                                    ) { Text(humanizeTokenKey(name), style = inspectorBodyStyle()) }
                                }
                            },
                        ) { Text(humanizeTokenKey(controller.activeThemeName), style = inspectorBodyStyle()) }
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    if (showHeaderUndoRedo) {
                        OutlinedButton(
                            onClick = { controller.performUndo() },
                            enabled = controller.store.canUndo || controller.hasActiveTransaction,
                            modifier = Modifier.testTag("ciacola-doc-theme-undo"),
                        ) { Text("Undo", style = inspectorBodyStyle()) }
                        OutlinedButton(
                            onClick = { controller.performRedo() },
                            enabled = controller.store.canRedo,
                            modifier = Modifier.testTag("ciacola-doc-theme-redo"),
                        ) { Text("Redo", style = inspectorBodyStyle()) }
                    }
                }
            }

            if (visibility.showBasePreset) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Base", style = inspectorBodyStyle(), color = colors.text.info)
                    InspectorSegmented(
                        options = listOf("stageDark", "keynoteLight"),
                        selected = listOf("stageDark", "keynoteLight").firstOrNull { themeSpec.preset.equals(it, ignoreCase = true) },
                        label = ::themePresetLabel,
                        testTag = { "ciacola-doc-theme-preset-$it" },
                        onSelect = { controller.setThemePreset(it) },
                        modifier = Modifier.width(140.dp),
                    )
                }
            }

            if (onPickDeckFileToCopyThemes != null) {
                OutlinedButton(
                    onClick = {
                        onPickDeckFileToCopyThemes()?.let { controller.copyThemesFromDeckFile(it, includePalettes = true) }
                    },
                    enabled = !controller.store.isReadOnly,
                    modifier = Modifier.testTag("ciacola-doc-theme-copy-from-deck"),
                ) { Text("Copy themes from another deck…", style = inspectorBodyStyle()) }
            }
            controller.lastCopyStatus?.let { status ->
                Text(status, style = inspectorCaptionStyle(), color = colors.text.info, modifier = Modifier.testTag("ciacola-doc-theme-copy-status"))
            }

            InspectorSegmented(
                options = ThemeInspectorSectionTab.entries,
                selected = activeTab,
                label = { it.label },
                testTag = { "ciacola-doc-theme-tab-${it.name.lowercase()}" },
                onSelect = { activeTab = it },
                equalWidths = false,
            )
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(bottom = 24.dp, end = 8.dp),
            ) {
                when (activeTab) {
                    ThemeInspectorSectionTab.Colors -> {
                        CodeSchemeEditorSection(controller, onPickCodeSchemeFile)
                        ThemeColorsEditorSection(controller, themeSpec.colors, resolvedColors)
                    }
                    ThemeInspectorSectionTab.Typography -> ThemeTypographyEditorSection(controller)
                    ThemeInspectorSectionTab.Motion -> ThemeMotionEditorSection(controller)
                    ThemeInspectorSectionTab.Palettes -> ThemePalettesEditorSection(controller, doc.palettes.keys.toList())
                }
            }
            VerticalScrollbar(
                scrollState = scroll,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
    }
}

/** Compact hex text field committing on focus loss. */
@Composable
private fun HexField(value: String, tag: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    val tfState = rememberTextFieldState(initialText = value)
    LaunchedEffect(value) {
        if (tfState.text.toString() != value) tfState.setTextAndPlaceCursorAtEnd(value)
    }
    TextField(
        state = tfState,
        modifier = modifier
            .height(InspectorTokens.RowHeight)
            .onFocusChanged { st ->
                if (!st.isFocused) {
                    val candidate = tfState.text.toString().trim()
                    if (candidate != value) onCommit(candidate)
                }
            }
            .trackAuthoringTextInputFocus()
            .testTag(tag),
    )
}

private const val CODE_SCHEME_PREVIEW_SNIPPET =
    "@Composable\nfun greet(name: String = \"hi\") {\n    // preview\n    val n = 42\n}"

private fun highlightCodeSchemePreview(scheme: SyntaxColorScheme): AnnotatedString {
    val baseColor = Color(scheme.foreground.argb)
    val tokens = SyntaxHighlighter.lex(CODE_SCHEME_PREVIEW_SNIPPET, SyntaxLanguage.Kotlin)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = baseColor)) {
            append(CODE_SCHEME_PREVIEW_SNIPPET)
        }
        for (token in tokens) {
            if (token.length <= 0) continue
            if (token.kind == SyntaxTokenKind.Plain || token.kind == SyntaxTokenKind.Whitespace) continue
            val tokenStyle = scheme.styleFor(token.kind)
            val decoration = when {
                tokenStyle.underline && tokenStyle.strikethrough ->
                    TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                tokenStyle.underline -> TextDecoration.Underline
                tokenStyle.strikethrough -> TextDecoration.LineThrough
                else -> null
            }
            addStyle(
                style = SpanStyle(
                    color = tokenStyle.foreground?.let { Color(it.argb) } ?: baseColor,
                    background = tokenStyle.background?.let { Color(it.argb) } ?: Color.Unspecified,
                    fontWeight = if (tokenStyle.bold) FontWeight.Bold else null,
                    fontStyle = if (tokenStyle.italic) FontStyle.Italic else null,
                    textDecoration = decoration,
                ),
                start = token.startIndex,
                end = token.endIndex,
            )
        }
    }
}

@Composable
private fun CodeSchemeEditorSection(
    controller: DocumentThemeInspectorController,
    onPickCodeSchemeFile: (() -> Path?)?,
) {
    val colors = JewelTheme.globalColors
    val themeSpec = controller.currentThemeSpec()
    val activeScheme = remember(themeSpec) { controller.activeCodeScheme() }
    val isImported = themeSpec.customCodeScheme != null
    val selectedLabel = if (isImported) "${activeScheme.name} (Imported)" else activeScheme.name
    val isModified = controller.isCodeSchemeModified()

    FlatCollapsibleSection(title = "Code highlighting", testTag = "ciacola-doc-theme-code-scheme-section") {
        Row(
            modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModifiedDot(
                modified = isModified,
                label = "Code scheme",
                tag = "ciacola-doc-theme-code-scheme-reset",
                onReset = if (controller.store.isReadOnly) null else ({ controller.resetCodeScheme() }),
                resolvedTestTag = "ciacola-doc-theme-code-scheme-reset",
            )
            Text("Scheme", style = inspectorBodyStyle(), color = colors.text.info)
            Dropdown(
                enabled = !controller.store.isReadOnly,
                modifier = Modifier
                    .weight(1f)
                    .height(InspectorTokens.RowHeight)
                    .testTag("ciacola-doc-theme-code-scheme-selector"),
                menuContent = {
                    if (isImported) {
                        selectableItem(selected = true, onClick = {}) {
                            Text("${activeScheme.name} (Imported)", style = inspectorBodyStyle())
                        }
                        separator()
                    }
                    passiveItem {
                        Text(
                            text = "IntelliJ",
                            style = inspectorCaptionStyle(),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.text.info,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    for (scheme in SyntaxColorSchemes.intelliJ) {
                        selectableItem(
                            selected = !isImported && activeScheme.id == scheme.id,
                            onClick = { controller.selectBundledCodeScheme(scheme.id) },
                        ) {
                            Text(scheme.name, style = inspectorBodyStyle())
                        }
                    }
                    separator()
                    passiveItem {
                        Text(
                            text = "GitHub",
                            style = inspectorCaptionStyle(),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.text.info,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    for (scheme in SyntaxColorSchemes.gitHub) {
                        selectableItem(
                            selected = !isImported && activeScheme.id == scheme.id,
                            onClick = { controller.selectBundledCodeScheme(scheme.id) },
                        ) {
                            Text(scheme.name, style = inspectorBodyStyle())
                        }
                    }
                },
            ) {
                Text(
                    text = selectedLabel,
                    style = inspectorBodyStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (onPickCodeSchemeFile != null) {
            OutlinedButton(
                onClick = { onPickCodeSchemeFile()?.let { controller.importCodeSchemeFromFile(it) } },
                enabled = !controller.store.isReadOnly,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ciacola-doc-theme-code-scheme-import"),
            ) {
                Text("Import IJ / VS Code\u2026", style = inspectorBodyStyle())
            }
        }

        controller.lastCodeSchemeStatus?.let { status ->
            val isSuccess = status.startsWith("Imported ")
            Text(
                text = status,
                style = inspectorCaptionStyle(),
                color = if (isSuccess) colors.text.info else colors.text.error,
                modifier = Modifier.testTag("ciacola-doc-theme-code-scheme-status"),
            )
        }

        val previewShape = RoundedCornerShape(6.dp)
        val schemeBg = Color(activeScheme.background.argb)
        val schemeFg = Color(activeScheme.foreground.argb)
        val previewAnnotated = remember(activeScheme) { highlightCodeSchemePreview(activeScheme) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(previewShape)
                .background(schemeBg, previewShape)
                .border(1.dp, schemeFg.copy(alpha = 0.18f), previewShape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("ciacola-doc-theme-code-scheme-preview"),
        ) {
            Text(
                text = previewAnnotated,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = schemeFg,
                ),
            )
        }
    }
}

@Composable
private fun ThemeColorsEditorSection(
    controller: DocumentThemeInspectorController,
    overriddenMap: Map<String, String>,
    resolvedColors: Map<String, Color>,
) {
    FlatCollapsibleSection(title = "Theme colors", testTag = "ciacola-doc-theme-colors-section") {
        for (token in DOCUMENT_THEME_COLOR_KEYS) {
            val color = resolvedColors[token] ?: Color.Gray
            val hexText = overriddenMap[token] ?: color.toHexString()
            Row(
                modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModifiedDot(
                    modified = token in overriddenMap,
                    label = humanizeTokenKey(token),
                    tag = "ciacola-doc-theme-color-reset-$token",
                    onReset = { controller.resetThemeColor(token) },
                )
                ColorSwatch(color, 16.dp)
                Text(
                    text = humanizeTokenKey(token),
                    style = inspectorBodyStyle(),
                    modifier = Modifier.weight(1f).testTag("ciacola-doc-theme-color-label-$token"),
                    maxLines = 1,
                )
                HexField(
                    value = hexText,
                    tag = "ciacola-doc-theme-color-$token",
                    onCommit = { candidate -> if (parseHexColorOrNull(candidate) != null) controller.setThemeColor(token, candidate) },
                    modifier = Modifier.width(96.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeTypographyEditorSection(controller: DocumentThemeInspectorController) {
    val themeSpec = controller.currentThemeSpec()
    val role = controller.selectedTypographyRole
    val resolved = DocumentThemeResolver.resolveTypographyRole(role, themeSpec)
    val colors = JewelTheme.globalColors

    FlatCollapsibleSection(
        title = "Type role",
        testTag = "ciacola-doc-theme-typography-section",
        headerTrailing = {
            if (role in themeSpec.typography) {
                OutlinedButton(
                    onClick = { controller.resetTypographyRole(role) },
                    modifier = Modifier.testTag("ciacola-doc-theme-typo-reset"),
                ) { Text("Reset role", style = inspectorBodyStyle()) }
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().height(InspectorTokens.RowHeight), verticalAlignment = Alignment.CenterVertically) {
            Text("Role", style = inspectorBodyStyle(), color = colors.text.info, modifier = Modifier.width(81.dp))
            Dropdown(
                modifier = Modifier.weight(1f).height(InspectorTokens.RowHeight).testTag("ciacola-doc-theme-typo-role-dropdown"),
                menuContent = {
                    for (r in DOCUMENT_TYPOGRAPHY_ROLES) {
                        selectableItem(selected = role == r, onClick = { controller.selectedTypographyRole = r }) {
                            Text(humanizeTokenKey(r), style = inspectorBodyStyle())
                        }
                    }
                },
            ) { Text(humanizeTokenKey(role), style = inspectorBodyStyle()) }
        }
        // Compare against the CURRENT resolved size (no stale dedupe state across undo or theme switches).
        val latestSize by rememberUpdatedState(resolved.size)
        CompactSliderRow(
            label = "Size",
            value = resolved.size.toFloat().coerceIn(10f, 140f),
            valueRange = 10f..140f,
            valueText = String.format(java.util.Locale.US, "%.1f", resolved.size),
            tagPrefix = "doc-theme-typo-size",
            modified = themeSpec.typography[role]?.size != null,
            onReset = null,
            onGestureStart = { controller.beginGesture("Adjust typography $role size") },
            onChange = { raw ->
                val snapped = ((raw * 2f).roundToInt() / 2.0).coerceIn(10.0, 140.0)
                if (snapped != latestSize) {
                    controller.updateTypographyRole(role, useActiveGesture = true) { it.copy(size = snapped) }
                }
            },
            onGestureEnd = { controller.commitGesture() },
        )
        val weights = remember { listOf(300, 400, 500, 600, 700, 800, 900) }
        Row(Modifier.fillMaxWidth().height(InspectorTokens.RowHeight), verticalAlignment = Alignment.CenterVertically) {
            Text("Weight", style = inspectorBodyStyle(), color = colors.text.info, modifier = Modifier.width(81.dp))
            Dropdown(
                modifier = Modifier.weight(1f).height(InspectorTokens.RowHeight).testTag("ciacola-doc-theme-typo-weight"),
                menuContent = {
                    for (w in weights) {
                        selectableItem(selected = resolved.weight == w, onClick = { controller.updateTypographyRole(role) { it.copy(weight = w) } }) {
                            Text("$w", style = inspectorBodyStyle())
                        }
                    }
                },
            ) { Text("${resolved.weight}", style = inspectorBodyStyle()) }
        }
        Row(
            modifier = Modifier.testTag("ciacola-doc-theme-typo-italic"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Checkbox(
                checked = resolved.italic,
                onCheckedChange = { checked -> controller.updateTypographyRole(role) { it.copy(italic = checked) } },
            )
            Text("Italic", style = inspectorBodyStyle())
        }
    }
}

@Composable
private fun ThemeMotionEditorSection(controller: DocumentThemeInspectorController) {
    val motion = controller.currentThemeSpec().motion
    val durationMs = motion?.durationMs ?: 500
    val colors = JewelTheme.globalColors

    FlatCollapsibleSection(title = "Motion defaults", testTag = "ciacola-doc-theme-motion-section") {
        CompactSliderRow(
            label = "Duration",
            value = durationMs.toFloat().coerceIn(50f, 2000f),
            valueRange = 50f..2000f,
            valueText = "$durationMs ms",
            tagPrefix = "doc-theme-motion-duration",
            modified = motion?.durationMs != null,
            onReset = null,
            onGestureStart = { controller.beginGesture("Adjust theme default duration") },
            onChange = { raw ->
                val snapped = ((raw / 10f).roundToInt() * 10).coerceIn(50, 2000)
                // Compare against the CURRENT document value, never a stale local copy.
                if (snapped != (controller.currentThemeSpec().motion?.durationMs ?: 500)) {
                    controller.setMotionDefaultDurationMs(snapped, useActiveGesture = true)
                }
            },
            onGestureEnd = { controller.commitGesture() },
            labelWidth = 72.dp,
        )
        Row(Modifier.fillMaxWidth().height(InspectorTokens.RowHeight), verticalAlignment = Alignment.CenterVertically) {
            Text("Curve", style = inspectorBodyStyle(), color = colors.text.info, modifier = Modifier.width(81.dp))
            Dropdown(
                modifier = Modifier.weight(1f).height(InspectorTokens.RowHeight).testTag("ciacola-doc-theme-motion-curve"),
                menuContent = {
                    val current = motion?.curve
                    if (isCustomThemeCurve(current)) {
                        // A non-preset curve stays listed (and selected) so it is never silently replaced.
                        selectableItem(selected = true, onClick = {}) {
                            Text(themeCurveLabel(current), style = inspectorBodyStyle())
                        }
                    }
                    for (curve in THEME_CURVE_CHOICES) {
                        selectableItem(
                            // Exact curve identity, never display-name equality.
                            selected = current == curve,
                            onClick = {
                                // Picking `Spring` keeps an existing custom spring's parameters.
                                if (!(curve is Curve.Spring && current is Curve.Spring)) controller.setMotionDefaultCurve(curve)
                            },
                        ) { Text(curveDisplayName(curve), style = inspectorBodyStyle()) }
                    }
                },
            ) { Text(themeCurveLabel(motion?.curve), style = inspectorBodyStyle(), modifier = Modifier.testTag("ciacola-doc-theme-motion-curve-label")) }
        }
        val currentTransType = motion?.transition?.type ?: TransitionType.Dissolve
        Row(Modifier.fillMaxWidth().height(InspectorTokens.RowHeight), verticalAlignment = Alignment.CenterVertically) {
            Text("Transition", style = inspectorBodyStyle(), color = colors.text.info, modifier = Modifier.width(81.dp))
            Dropdown(
                modifier = Modifier.weight(1f).height(InspectorTokens.RowHeight).testTag("ciacola-doc-theme-motion-transition"),
                menuContent = {
                    for (t in TransitionType.entries) {
                        selectableItem(selected = currentTransType == t, onClick = { controller.setMotionDefaultTransition(type = t) }) {
                            Text(transitionDisplayName(TransitionSpec(type = t)), style = inspectorBodyStyle())
                        }
                    }
                },
            ) { Text(transitionDisplayName(TransitionSpec(type = currentTransType)), style = inspectorBodyStyle()) }
        }
    }
}

@Composable
private fun ThemePalettesEditorSection(
    controller: DocumentThemeInspectorController,
    paletteNames: List<String>,
) {
    val activePaletteName = controller.selectedPaletteName?.takeIf { it in paletteNames } ?: paletteNames.firstOrNull()
    val paletteSpec = activePaletteName?.let { controller.store.document.palettes[it] }
    val colors = JewelTheme.globalColors

    FlatCollapsibleSection(
        title = "Slide palettes",
        testTag = "ciacola-doc-theme-palettes-section",
        headerTrailing = {
            OutlinedButton(
                onClick = { controller.addPalette("palette-${paletteNames.size + 1}") },
                modifier = Modifier.testTag("ciacola-doc-palette-add"),
            ) { Text("Add", style = inspectorBodyStyle()) }
        },
    ) {
        if (activePaletteName == null || paletteSpec == null) {
            Text("This deck has no slide palettes yet.", style = inspectorBodyStyle(), color = colors.text.info)
            return@FlatCollapsibleSection
        }
        Row(Modifier.fillMaxWidth().height(InspectorTokens.RowHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dropdown(
                modifier = Modifier.weight(1f).height(InspectorTokens.RowHeight).testTag("ciacola-doc-palette-selector"),
                menuContent = {
                    for (p in paletteNames) {
                        selectableItem(selected = p == activePaletteName, onClick = { controller.selectedPaletteName = p }) {
                            Text(humanizeTokenKey(p), style = inspectorBodyStyle())
                        }
                    }
                },
            ) { Text(humanizeTokenKey(activePaletteName), style = inspectorBodyStyle()) }
            OutlinedButton(
                onClick = { controller.removePalette(activePaletteName) },
                modifier = Modifier.testTag("ciacola-doc-palette-remove"),
            ) { Text("Remove", style = inspectorBodyStyle()) }
        }
        val slots = listOf(
            "canvas" to paletteSpec.canvas,
            "ink" to paletteSpec.ink,
            "mutedInk" to paletteSpec.mutedInk,
            "accent" to paletteSpec.accent,
            "surface" to paletteSpec.surface,
            "border" to paletteSpec.border,
        )
        for ((slotName, value) in slots) {
            Row(
                modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorSwatch(value?.let { parseHexColorOrNull(it) }, 16.dp)
                Text(humanizeTokenKey(slotName), style = inspectorBodyStyle(), modifier = Modifier.weight(1f), maxLines = 1)
                HexField(
                    value = value.orEmpty(),
                    tag = "ciacola-doc-palette-$slotName",
                    onCommit = { raw ->
                        val nextVal = raw.ifEmpty { null }
                        controller.updatePalette(activePaletteName) { spec ->
                            when (slotName) {
                                "canvas" -> spec.copy(canvas = nextVal)
                                "ink" -> spec.copy(ink = nextVal)
                                "mutedInk" -> spec.copy(mutedInk = nextVal)
                                "accent" -> spec.copy(accent = nextVal)
                                "surface" -> spec.copy(surface = nextVal)
                                "border" -> spec.copy(border = nextVal)
                                else -> spec
                            }
                        }
                    },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

/**
 * Standalone [EaselWindow] hosting [DocumentThemeInspectorUi] (`F7`) and the `Theme` menu bar
 * (document theme selector + reduced motion & ambient loop controls).
 */
class DocumentThemeInspectorWindow(
    var controller: DocumentThemeInspectorController,
    var isDarkProvider: () -> Boolean = { true },
    var currentMotionModeProvider: () -> ReducedMotionMode = { ReducedMotionMode.System },
    var systemReducedMotionProvider: () -> Boolean = { false },
    var ambientLoopsPausedProvider: () -> Boolean = { false },
    var onSelectMotionMode: (ReducedMotionMode) -> Unit = {},
    var onToggleAmbientLoopsPaused: (Boolean) -> Unit = {},
    var isEditorShownProvider: () -> Boolean = { false },
    var onToggleEditor: () -> Unit = {},
    var onPickDeckFileToCopyThemes: (() -> Path?)? = null,
    var onPickCodeSchemeFile: (() -> Path?)? = null,
) : EaselWindow {
    override val name: String = "Theme"
    override val initialSize: DpSize = DpSize(440.dp, 680.dp)
    override val minimumSize: DpSize = DpSize(360.dp, 460.dp)
    override var visible: Boolean by mutableStateOf(false)

    @Composable
    override fun MenuScope.Menu() {
        val themes = controller.availableThemeNames()
        for (themeName in themes) {
            RadioButtonItem(
                text = themeName,
                selected = controller.activeThemeName == themeName,
                onClick = { controller.selectTheme(themeName) },
            )
        }
        Separator()
        Menu("Motion") {
            val motionMode = currentMotionModeProvider()
            val sysLabel = if (systemReducedMotionProvider()) "System (Reduced)" else "System (Full)"
            RadioButtonItem(
                text = sysLabel,
                selected = motionMode == ReducedMotionMode.System,
                onClick = { onSelectMotionMode(ReducedMotionMode.System) },
            )
            RadioButtonItem(
                text = "Full Motion",
                selected = motionMode == ReducedMotionMode.Full,
                onClick = { onSelectMotionMode(ReducedMotionMode.Full) },
            )
            RadioButtonItem(
                text = "Reduced Motion",
                selected = motionMode == ReducedMotionMode.Reduced,
                onClick = { onSelectMotionMode(ReducedMotionMode.Reduced) },
            )
            Separator()
            val paused = ambientLoopsPausedProvider()
            CheckboxItem(
                text = "Pause Ambient Loops",
                checked = paused,
                onCheckedChange = { onToggleAmbientLoopsPaused(it) },
            )
        }
        Separator()
        CheckboxItem(
            text = "Show Theme & Palette Inspector (F7)",
            checked = isEditorShownProvider(),
            onCheckedChange = { onToggleEditor() },
        )
    }

    @Composable
    override fun Content() {
        CiacolaJewelTheme(isDark = isDarkProvider()) {
            DocumentThemeInspectorUi(
                controller = controller,
                onPickDeckFileToCopyThemes = onPickDeckFileToCopyThemes,
                onPickCodeSchemeFile = onPickCodeSchemeFile,
            )
        }
    }
}

@Composable
fun rememberDocumentThemeInspectorWindow(
    controller: DocumentThemeInspectorController,
    isDarkProvider: () -> Boolean = { true },
    currentMotionModeProvider: () -> ReducedMotionMode = { ReducedMotionMode.System },
    systemReducedMotionProvider: () -> Boolean = { false },
    ambientLoopsPausedProvider: () -> Boolean = { false },
    onSelectMotionMode: (ReducedMotionMode) -> Unit = {},
    onToggleAmbientLoopsPaused: (Boolean) -> Unit = {},
    isEditorShownProvider: () -> Boolean = { false },
    onToggleEditor: () -> Unit = {},
    onPickDeckFileToCopyThemes: (() -> Path?)? = null,
    onPickCodeSchemeFile: (() -> Path?)? = null,
): DocumentThemeInspectorWindow = remember {
    DocumentThemeInspectorWindow(
        controller = controller,
        isDarkProvider = isDarkProvider,
        currentMotionModeProvider = currentMotionModeProvider,
        systemReducedMotionProvider = systemReducedMotionProvider,
        ambientLoopsPausedProvider = ambientLoopsPausedProvider,
        onSelectMotionMode = onSelectMotionMode,
        onToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused,
        isEditorShownProvider = isEditorShownProvider,
        onToggleEditor = onToggleEditor,
        onPickDeckFileToCopyThemes = onPickDeckFileToCopyThemes,
        onPickCodeSchemeFile = onPickCodeSchemeFile,
    )
}.also {
    it.controller = controller
    it.isDarkProvider = isDarkProvider
    it.currentMotionModeProvider = currentMotionModeProvider
    it.systemReducedMotionProvider = systemReducedMotionProvider
    it.ambientLoopsPausedProvider = ambientLoopsPausedProvider
    it.onSelectMotionMode = onSelectMotionMode
    it.onToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused
    it.isEditorShownProvider = isEditorShownProvider
    it.onToggleEditor = onToggleEditor
    it.onPickDeckFileToCopyThemes = onPickDeckFileToCopyThemes
    it.onPickCodeSchemeFile = onPickCodeSchemeFile
}

/** Which header controls the Theme sheet shows for the deck's theme set. */
internal data class ThemeHeaderVisibility(val showSelector: Boolean, val showBasePreset: Boolean)

/**
 * A one-option `Theme` dropdown is noise, so it only appears with 2+ themes. The `Base [Dark | Light]`
 * preset row appears for single-theme decks and for custom-named themes (the `dark` / `light` pair
 * already encodes its base in the selector).
 */
internal fun themeHeaderVisibility(themeNames: List<String>, activeThemeName: String): ThemeHeaderVisibility =
    ThemeHeaderVisibility(
        showSelector = themeNames.size > 1,
        showBasePreset = themeNames.size <= 1 || activeThemeName.lowercase() !in setOf("dark", "light"),
    )
