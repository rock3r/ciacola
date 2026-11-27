package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.inspector.design.InspectorSegmented
import dev.sebastiano.ciacola.inspector.design.InspectorTokens
import dev.sebastiano.ciacola.inspector.design.InspectorTooltip
import dev.sebastiano.ciacola.inspector.design.humanizeTokenKey
import dev.sebastiano.ciacola.inspector.design.inspectorAccent
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.inspector.design.inspectorCaptionStyle
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.CiacolaPrimaryButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Human-readable severity filter label (never raw enum names like `ErrorsOnly`). */
fun humanLintFilterLabel(
    filter: LintSeverityFilter,
    totalCount: Int,
    errorCount: Int,
    warningCount: Int,
): String = when (filter) {
    LintSeverityFilter.All -> "All ($totalCount)"
    LintSeverityFilter.ErrorsOnly -> "Errors Only ($errorCount)"
    LintSeverityFilter.WarningsOnly -> "Warnings Only ($warningCount)"
}

/** Human-readable diagnostic source badge label. */
fun humanLintSourceLabel(source: DocumentLintSource): String = when (source) {
    DocumentLintSource.StaticValidation -> "Static"
    DocumentLintSource.StaticContrast -> "Contrast"
    DocumentLintSource.PixelContrast480x270 -> "Pixel Contrast"
    DocumentLintSource.Layout -> "Layout"
    DocumentLintSource.CrossSlide -> "Cross-Slide"
    DocumentLintSource.Provenance -> "External Edit"
    DocumentLintSource.TodoVideo -> "Video TODO"
}

/** Compact filter label for the Issues segmented control (`All 5`, `Errors 2`, `Warnings 3`). */
fun compactLintFilterLabel(filter: LintSeverityFilter, totalCount: Int, errorCount: Int, warningCount: Int): String = when (filter) {
    LintSeverityFilter.All -> "All $totalCount"
    LintSeverityFilter.ErrorsOnly -> "Errors $errorCount"
    LintSeverityFilter.WarningsOnly -> "Warnings $warningCount"
}

/**
 * Grouped **Issues** panel bound to [DocumentDeckLintController].
 *
 * Neutral rows with a 3dp severity stripe + icon, grouped by slide under collapsible
 * `Slide N · "Title" · K issues` headers, one plain sentence per issue, a theme tag, and `Show` /
 * `Fix` actions. Before any check it says `Not checked yet` with a `Check deck` button.
 *
 * [compactHorizontal] lays rows out on a single line for a wide bottom dock; pass `false` for a
 * narrow sidecar, where actions wrap under the sentence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentDeckLintPanel(
    controller: DocumentDeckLintController,
    currentSlideIndex: Int = 0,
    themeNamesProvider: () -> List<String> = { controller.store.document.themes.keys.toList() },
    onJumpToIssue: ((DocumentLintIssue) -> Unit)? = null,
    autoAuditCurrentSlide: Boolean = true,
    modifier: Modifier = Modifier,
    compactHorizontal: Boolean = true,
    auditScope: CoroutineScope = rememberCoroutineScope(),
) {
    val storeRev = controller.store.revision
    val colors = JewelTheme.globalColors
    val listState = rememberLazyListState()
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(autoAuditCurrentSlide, currentSlideIndex, storeRev, controller.isPaused) {
        if (autoAuditCurrentSlide && !controller.isPaused && !controller.isAuditing) {
            controller.lintSlideNow(slideIndex = currentSlideIndex, themeNames = themeNamesProvider())
        }
    }

    fun checkDeck() {
        when {
            controller.isAuditing || controller.hasPausedAudit -> controller.cancelAudit()
            !controller.isPaused -> controller.startFullDeckAudit(scope = auditScope, themeNames = themeNamesProvider())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.panelBackground)
            .testTag("ciacola-doc-lint-panel"),
    ) {
        LintToolbar(
            controller = controller,
            compactHorizontal = compactHorizontal,
            onCheckDeck = ::checkDeck,
            onCheckSlide = {
                if (!controller.isPaused) {
                    auditScope.launch {
                        controller.lintSlideNow(slideIndex = currentSlideIndex, themeNames = themeNamesProvider())
                    }
                }
            },
        )
        if (controller.isAuditing) {
            val fraction = controller.progress.coerceIn(0.02f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(colors.borders.normal.copy(alpha = 0.45f))
                    .testTag("ciacola-doc-lint-progress-bar"),
            ) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(inspectorAccent()))
            }
        }
        if (controller.isPaused) {
            val pausedMessage = if (controller.hasPausedAudit) {
                "Deck check paused while presenting — resumes automatically on exit."
            } else {
                "Deck checks paused while presenting."
            }
            Text(
                pausedMessage,
                style = inspectorBodyStyle(),
                color = colors.text.info,
                modifier = Modifier
                    .padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 4.dp)
                    .testTag("ciacola-doc-lint-paused-banner"),
            )
        }
        controller.lastError?.let { err ->
            Text(
                "The check stopped: $err",
                style = inspectorBodyStyle(),
                color = colors.outlines.error,
                modifier = Modifier.padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 4.dp),
            )
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        val filtered = controller.filteredIssues
        val document = controller.store.document
        val groups = remember(filtered, storeRev) { groupLintIssuesBySlide(filtered, document) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                LintEmptyState(controller, ::checkDeck)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 10.dp).testTag("ciacola-doc-lint-list"),
                ) {
                    for (group in groups) {
                        val isCollapsed = collapsed[group.slideId] == true
                        item(key = "group-${group.slideId}") {
                            LintGroupHeader(group, isCollapsed) { collapsed[group.slideId] = !isCollapsed }
                        }
                        if (!isCollapsed) {
                            items(group.issues, key = { it.id }) { issue ->
                                LintIssueRow(
                                    issue = issue,
                                    sentence = plainLintSentence(issue, document),
                                    selected = controller.selectedIssueId == issue.id,
                                    compactHorizontal = compactHorizontal,
                                    onShow = {
                                        controller.selectedIssueId = issue.id
                                        onJumpToIssue?.invoke(issue)
                                    },
                                    onFix = lintFixDescription(issue)?.let { desc -> desc to { controller.fixIssue(issue); Unit } },
                                )
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    scrollState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun LintToolbar(
    controller: DocumentDeckLintController,
    compactHorizontal: Boolean,
    onCheckDeck: () -> Unit,
    onCheckSlide: () -> Unit,
) {
    val colors = JewelTheme.globalColors
    val filters: @Composable () -> Unit = {
        InspectorSegmented(
            options = LintSeverityFilter.entries,
            selected = controller.severityFilter,
            label = { compactLintFilterLabel(it, controller.issues.size, controller.errorCount, controller.warningCount) },
            testTag = { "ciacola-doc-lint-filter-${it.name.lowercase()}" },
            onSelect = { controller.severityFilter = it },
            equalWidths = false,
        )
    }
    val buttons: @Composable () -> Unit = {
        val deckLabel = when {
            controller.isAuditing -> {
                val total = controller.totalSlidesToAudit.coerceAtLeast(1)
                "Cancel (${controller.auditedSlides}/$total)"
            }
            controller.isPaused && controller.hasPausedAudit -> "Cancel paused"
            else -> "Check deck"
        }
        // The empty state carries the one `Check deck` call to action until the deck is checked.
        if (!lintEmptyStateOwnsCheckDeck(controller)) {
            OutlinedButton(
                onClick = onCheckDeck,
                enabled = !controller.isPaused || controller.isAuditing || controller.hasPausedAudit,
                modifier = Modifier.testTag("ciacola-doc-lint-audit-all"),
            ) { Text(deckLabel, style = inspectorBodyStyle()) }
        }
        OutlinedButton(
            onClick = onCheckSlide,
            enabled = !controller.isAuditing && !controller.isPaused,
            modifier = Modifier.testTag("ciacola-doc-lint-audit-slide"),
        ) { Text("Check slide", style = inspectorBodyStyle()) }
    }
    // Counts live in the filter pills only; the toolbar carries no second summary.
    if (compactHorizontal) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buttons()
            filters()
            Box(Modifier.weight(1f))
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                buttons()
                Box(Modifier.weight(1f))
            }
            filters()
        }
    }
}

/** `true` while the empty state shows its own `Check deck` button (the toolbar then hides its copy). */
fun lintEmptyStateOwnsCheckDeck(controller: DocumentDeckLintController): Boolean =
    !controller.isAuditing && !controller.isPaused && controller.auditScope != LintAuditScope.Deck && controller.issues.isEmpty()

@Composable
private fun LintEmptyState(controller: DocumentDeckLintController, onCheckDeck: () -> Unit) {
    val colors = JewelTheme.globalColors
    val (title, detail) = when {
        controller.isAuditing -> "Checking slide ${controller.auditedSlides} of ${controller.totalSlidesToAudit.coerceAtLeast(1)}…" to null
        controller.isPaused && controller.hasPausedAudit ->
            "Deck check paused" to "Will resume automatically when you leave Present mode."
        controller.isPaused ->
            "Checks paused while presenting" to "Leave Present mode to run slide or deck checks."
        controller.issues.isNotEmpty() -> when (controller.severityFilter) {
            LintSeverityFilter.ErrorsOnly -> "No errors" to "Only warnings remain."
            LintSeverityFilter.WarningsOnly -> "No warnings" to "Only errors remain."
            LintSeverityFilter.All -> "No issues" to null
        }
        controller.auditScope == LintAuditScope.None -> "Not checked yet" to "Check the deck for contrast, overflow, and layout problems."
        // The slide was checked cleanly; only the deck is still unchecked (`Not checked yet` is
        // reserved for when nothing has run).
        controller.auditScope == LintAuditScope.Slide -> "No issues on this slide" to "Check the whole deck to find problems on other slides."
        else -> "No issues found" to "Every slide passed contrast, overflow, and layout checks."
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(InspectorTokens.SectionHorizontalPadding, 16.dp).testTag("ciacola-doc-lint-empty"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = inspectorBodyStyle().copy(fontSize = InspectorTokens.Title), fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("ciacola-doc-lint-empty-title"))
        detail?.let { Text(it, style = inspectorBodyStyle(), color = colors.text.info) }
        if (lintEmptyStateOwnsCheckDeck(controller)) {
            CiacolaPrimaryButton(
                text = "Check deck",
                onClick = onCheckDeck,
                modifier = Modifier.testTag("ciacola-doc-lint-check-deck"),
            )
        }
    }
}

@Composable
private fun LintGroupHeader(group: LintSlideGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = JewelTheme.globalColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(onClick = onToggle)
            .semantics { role = Role.Button; stateDescription = if (collapsed) "Collapsed" else "Expanded" }
            .padding(horizontal = 8.dp)
            .testTag("ciacola-doc-lint-group-${group.slideId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (collapsed) AllIconsKeys.General.ChevronRight else AllIconsKeys.General.ChevronDown, contentDescription = null, modifier = Modifier.size(12.dp))
        Text(
            group.headerText,
            style = inspectorBodyStyle(),
            fontWeight = FontWeight.SemiBold,
            color = colors.text.normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("ciacola-doc-lint-group-title-${group.slideId}"),
        )
    }
}

@Composable
private fun LintIssueRow(
    issue: DocumentLintIssue,
    sentence: String,
    selected: Boolean,
    compactHorizontal: Boolean,
    onShow: () -> Unit,
    onFix: Pair<String, () -> Unit>?,
) {
    val colors = JewelTheme.globalColors
    val isError = issue.severity == LintSeverity.Error
    val severityColor = if (isError) colors.outlines.error else colors.outlines.warning
    val accent = inspectorAccent()
    // Fixed-width action column: theme tag, `Show` and a reserved `Fix` slot, so `Show` lines up on
    // every row whether or not the issue has a fix.
    val actions: @Composable () -> Unit = {
        Box(Modifier.width(LintThemeTagWidth), contentAlignment = Alignment.CenterEnd) {
            issue.themeName?.let { theme ->
                Text(
                    humanizeTokenKey(theme),
                    style = inspectorCaptionStyle(),
                    color = colors.text.info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(InspectorTokens.Corner))
                        .border(1.dp, colors.borders.normal, RoundedCornerShape(InspectorTokens.Corner))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                        .testTag("ciacola-doc-lint-theme-${issue.id}"),
                )
            }
        }
        LintRowButton("Show", "ciacola-doc-lint-show-${issue.id}", onShow)
        Box(Modifier.width(LintFixSlotWidth)) {
            if (onFix != null) {
                InspectorTooltip(onFix.first) {
                    LintRowButton("Fix", "ciacola-doc-lint-fix-${issue.id}", onFix.second)
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(InspectorTokens.Corner))
            .then(if (selected) Modifier.background(accent.copy(alpha = 0.12f)) else Modifier)
            .clickable(onClick = onShow)
            .then(if (compactHorizontal) Modifier.height(LintRowHeight) else Modifier.height(IntrinsicSize.Min))
            .testTag("ciacola-doc-lint-issue-${issue.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(severityColor).testTag("ciacola-doc-lint-stripe-${issue.id}"))
        Row(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = if (compactHorizontal) 0.dp else 4.dp),
            verticalAlignment = if (compactHorizontal) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (isError) AllIconsKeys.General.Error else AllIconsKeys.General.Warning,
                contentDescription = if (isError) "Error" else "Warning",
                modifier = Modifier.size(13.dp),
            )
            if (compactHorizontal) {
                Text(
                    sentence,
                    style = inspectorBodyStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("ciacola-doc-lint-sentence-${issue.id}"),
                )
                actions()
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sentence, style = inspectorBodyStyle(), modifier = Modifier.testTag("ciacola-doc-lint-sentence-${issue.id}"))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
                }
            }
        }
    }
}

private val LintRowHeight = 30.dp
private val LintRowButtonHeight = 24.dp
private val LintThemeTagWidth = 64.dp
private val LintFixSlotWidth = 40.dp

/** Compact 24dp row action (`Show`, `Fix`). */
@Composable
private fun LintRowButton(label: String, testTag: String, onClick: () -> Unit) {
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(InspectorTokens.Corner)
    Box(
        modifier = Modifier
            .height(LintRowButtonHeight)
            .widthIn(min = LintFixSlotWidth)
            .clip(shape)
            .border(1.dp, colors.borders.normal, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = inspectorBodyStyle(), maxLines = 1)
    }
}

/**
 * Standalone [EaselWindow] hosting [DocumentDeckLintPanel] (`F8`) and the `Linter` menu bar.
 */
class DocumentDeckLintWindow(
    var controller: DocumentDeckLintController,
    var isDarkProvider: () -> Boolean = { true },
    var currentSlideIndexProvider: () -> Int = { 0 },
    var themeNamesProvider: () -> List<String> = { controller.store.document.themes.keys.toList() },
    var onJumpToIssue: ((DocumentLintIssue) -> Unit)? = null,
    var isLinterShownProvider: () -> Boolean = { false },
    var onToggleLinter: () -> Unit = {},
    var onPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    var auditScope: CoroutineScope? = null,
) : EaselWindow {
    override val name: String = "Linter"
    override val initialSize: DpSize = DpSize(440.dp, 640.dp)
    override val minimumSize: DpSize = DpSize(360.dp, 420.dp)
    override var visible: Boolean by mutableStateOf(false)

    @Composable
    override fun MenuScope.Menu() {
        val menuScope = rememberCoroutineScope()
        val effectiveScope = auditScope ?: menuScope
        CheckboxItem(
            text = "Show Deck Linter (F8)",
            checked = isLinterShownProvider(),
            onCheckedChange = { onToggleLinter() },
        )
        Separator()
        Item(
            text = "Lint Current Slide",
            enabled = !controller.isAuditing && !controller.isPaused,
            onClick = {
                if (!controller.isPaused) {
                    effectiveScope.launch {
                        controller.lintSlideNow(
                            slideIndex = currentSlideIndexProvider(),
                            themeNames = themeNamesProvider(),
                        )
                    }
                }
            },
        )
        Item(
            text = when {
                controller.isAuditing -> "Cancel Full-Deck Audit"
                controller.isPaused && controller.hasPausedAudit -> "Cancel Paused Full-Deck Audit"
                controller.isPaused -> "Audit Full Deck (Paused while presenting)"
                else -> "Audit Full Deck"
            },
            enabled = !controller.isPaused || controller.isAuditing || controller.hasPausedAudit,
            onClick = {
                when {
                    controller.isAuditing || controller.hasPausedAudit -> controller.cancelAudit()
                    !controller.isPaused -> controller.startFullDeckAudit(
                        scope = effectiveScope,
                        themeNames = themeNamesProvider(),
                    )
                }
            },
        )
    }

    @Composable
    override fun Content() {
        val panelScope = auditScope ?: rememberCoroutineScope()
        CiacolaJewelTheme(isDark = isDarkProvider()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { ev -> onPreviewKeyHandler?.invoke(ev) == true },
            ) {
                DocumentDeckLintPanel(
                    controller = controller,
                    currentSlideIndex = currentSlideIndexProvider(),
                    themeNamesProvider = themeNamesProvider,
                    onJumpToIssue = onJumpToIssue,
                    compactHorizontal = false,
                    auditScope = panelScope,
                )
            }
        }
    }
}

@Composable
fun rememberDocumentDeckLintWindow(
    controller: DocumentDeckLintController,
    isDarkProvider: () -> Boolean = { true },
    currentSlideIndexProvider: () -> Int = { 0 },
    themeNamesProvider: () -> List<String> = { controller.store.document.themes.keys.toList() },
    onJumpToIssue: ((DocumentLintIssue) -> Unit)? = null,
    isLinterShownProvider: () -> Boolean = { false },
    onToggleLinter: () -> Unit = {},
    onPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    auditScope: CoroutineScope? = null,
): DocumentDeckLintWindow = remember {
    DocumentDeckLintWindow(
        controller = controller,
        isDarkProvider = isDarkProvider,
        currentSlideIndexProvider = currentSlideIndexProvider,
        themeNamesProvider = themeNamesProvider,
        onJumpToIssue = onJumpToIssue,
        isLinterShownProvider = isLinterShownProvider,
        onToggleLinter = onToggleLinter,
        onPreviewKeyHandler = onPreviewKeyHandler,
        auditScope = auditScope,
    )
}.also {
    it.controller = controller
    it.isDarkProvider = isDarkProvider
    it.currentSlideIndexProvider = currentSlideIndexProvider
    it.themeNamesProvider = themeNamesProvider
    it.onJumpToIssue = onJumpToIssue
    it.isLinterShownProvider = isLinterShownProvider
    it.onToggleLinter = onToggleLinter
    it.onPreviewKeyHandler = onPreviewKeyHandler
    it.auditScope = auditScope
}
