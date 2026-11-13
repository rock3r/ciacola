package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Embeddable Jewel Lint panel ready to mount inside the Studio `Lint` tab.
 *
 * Provides live active-beat linting, full-deck offscreen audit (`Audit Deck`), scope/severity
 * filters, click-to-jump navigation, and deterministic `ElementPatch` quick-fix actions.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DeckLintPanel(
    controller: DeckLintController,
    animatic: Animatic,
    storyboardProvider: (() -> Storyboard)? = null,
    presetsToCheck: List<DeckThemePreset> = emptyList(),
    storyboardForPreset: ((DeckThemePreset) -> Storyboard)? = null,
    stageInspectionRegistry: StageInspectionRegistry? = LocalStageInspectionRegistry.current,
    onApplyElementPatch: ((ElementPatch) -> Unit)? = null,
    onEditElement: ((elementId: String, sceneIndex: Int, beatIndex: Int) -> Unit)? = null,
    onJumpToIssue: ((DeckLintIssue) -> Unit)? = null,
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val palette = rememberLintPalette()
    val focusRingColor = rememberAccessibleFocusRingColor()
    val theme = LocalDeckTheme.current
    val elementOverrides = LocalElementOverrides.current

    val currentIndex = animatic.currentIndex
    val targetIndex = animatic.targetIndex
    val isSettled = currentIndex == targetIndex && !animatic.scrubSettling
    val registrySnapshotKey = stageInspectionRegistry?.nodes?.entries?.hashCode() ?: 0

    // Live reactive evaluation of the settled beat when Lint/Authoring is active.
    LaunchedEffect(
        isActive,
        currentIndex,
        isSettled,
        registrySnapshotKey,
        theme,
        elementOverrides,
    ) {
        if (!isActive || !isSettled) return@LaunchedEffect
        controller.updateActiveLocation(currentIndex.sceneIndex, currentIndex.frameIndex)
        delay(120L)
        controller.refreshActiveBeat(
            registry = stageInspectionRegistry,
            sceneIndex = currentIndex.sceneIndex,
            beatIndex = currentIndex.frameIndex,
            fallbackBackground = theme.colors.background,
        )
    }

    val runFullAudit: () -> Unit = {
        scope.launch {
            val sb = storyboardProvider?.invoke() ?: animatic.storyboard
            controller.runFullDeckAudit(
                storyboard = sb,
                presetsToCheck = presetsToCheck,
                storyboardForPreset = storyboardForPreset,
            )
        }
    }

    val jumpToIssueAction: (DeckLintIssue) -> Unit = { issue ->
        if (onJumpToIssue != null) {
            controller.selectedIssueId = issue.id
            onJumpToIssue(issue)
        } else {
            scope.launch { controller.jumpToIssue(issue, animatic) }
        }
    }

    val applyQuickFixAction: (DeckLintIssue, LintQuickFix) -> Unit = { issue, fix ->
        when {
            fix.patch != null && onApplyElementPatch != null -> {
                onApplyElementPatch(fix.patch)
                scope.launch {
                    delay(80L)
                    if (controller.scope == LintScope.AllSlides && controller.hasFullDeckAudit) {
                        val sb = storyboardProvider?.invoke() ?: animatic.storyboard
                        controller.runFullDeckAudit(
                            storyboard = sb,
                            presetsToCheck = presetsToCheck,
                            storyboardForPreset = storyboardForPreset,
                        )
                    } else {
                        controller.refreshActiveBeat(
                            registry = stageInspectionRegistry,
                            sceneIndex = controller.currentSceneIndex,
                            beatIndex = controller.currentBeatIndex,
                            fallbackBackground = theme.colors.background,
                        )
                    }
                }
            }
            fix.opensElementEditor && issue.elementId != null -> {
                onEditElement?.invoke(issue.elementId, issue.sceneIndex, issue.beatIndex)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(globalColors.panelBackground)
            .testTag("ciacola-lint-panel"),
    ) {
        LintTopToolbar(
            controller = controller,
            palette = palette,
            focusRingColor = focusRingColor,
            onRunAudit = runFullAudit,
            onSwitchScope = { nextScope ->
                controller.scope = nextScope
                if (nextScope == LintScope.AllSlides && !controller.hasFullDeckAudit && !controller.isAuditing) {
                    runFullAudit()
                }
            },
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        if (controller.lastAuditError != null) {
            LintErrorBanner(
                message = controller.lastAuditError.orEmpty(),
                palette = palette,
            )
        }

        val issues = controller.filteredIssues
        if (issues.isEmpty()) {
            LintEmptyState(
                controller = controller,
                palette = palette,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            val listState = rememberLazyListState()
            VerticallyScrollableContainer(
                scrollState = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("ciacola-lint-issue-list"),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = issues,
                        key = { it.id },
                        contentType = { it.ruleId },
                    ) { issue ->
                        LintIssueCard(
                            issue = issue,
                            isSelected = controller.selectedIssueId == issue.id,
                            palette = palette,
                            focusRingColor = focusRingColor,
                            canApplyPatch = onApplyElementPatch != null,
                            canOpenEditor = onEditElement != null,
                            onSelectAndJump = { jumpToIssueAction(issue) },
                            onHoverChanged = { hovered ->
                                controller.hoveredIssueId = if (hovered) issue.id else {
                                    if (controller.hoveredIssueId == issue.id) null else controller.hoveredIssueId
                                }
                            },
                            onQuickFix = { fix -> applyQuickFixAction(issue, fix) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun LintTopToolbar(
    controller: DeckLintController,
    palette: LintUiPalette,
    focusRingColor: Color,
    onRunAudit: () -> Unit,
    onSwitchScope: (LintScope) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            LintSummaryPill(
                errorCount = controller.errorCount,
                warningCount = controller.warningCount,
                palette = palette,
            )
            Spacer(Modifier.weight(1f, fill = false))

            Tooltip(tooltip = { Text("Toggle stage diagnostic boxes overlay (F8 toggles Lint tab)") }) {
                val eyeInteraction = remember { MutableInteractionSource() }
                val eyeFocused by eyeInteraction.collectIsFocusedAsState()
                IconButton(
                    onClick = { controller.showStageOverlay = !controller.showStageOverlay },
                    interactionSource = eyeInteraction,
                    modifier = Modifier
                        .size(26.dp)
                        .accessibleFocusRing(eyeFocused, focusRingColor)
                        .testTag("ciacola-lint-overlay-toggle"),
                ) {
                    Icon(
                        key = AllIconsKeys.General.InspectionsEye,
                        contentDescription = if (controller.showStageOverlay) {
                            "Hide stage lint overlay"
                        } else {
                            "Show stage lint overlay"
                        },
                        tint = if (controller.showStageOverlay) {
                            globalColors.text.normal
                        } else {
                            globalColors.text.disabled
                        },
                    )
                }
            }

            Tooltip(tooltip = { Text("Sweep every slide and beat offscreen (preserves 60 fps UI)") }) {
                val auditInteraction = remember { MutableInteractionSource() }
                val auditFocused by auditInteraction.collectIsFocusedAsState()
                DefaultButton(
                    onClick = onRunAudit,
                    enabled = !controller.isAuditing,
                    interactionSource = auditInteraction,
                    modifier = Modifier
                        .accessibleFocusRing(auditFocused, focusRingColor)
                        .testTag("ciacola-lint-audit-button"),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (controller.isAuditing) {
                            CircularProgressIndicator(Modifier.size(12.dp))
                            Text("Auditing…")
                        } else {
                            Icon(
                                key = AllIconsKeys.Actions.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                            )
                            Text("Audit Deck")
                        }
                    }
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            LintSegmentedPill(
                label = "Current Beat",
                selected = controller.scope == LintScope.CurrentBeat,
                tooltip = "Show lint diagnostics for S${controller.currentSceneIndex + 1}:B${controller.currentBeatIndex + 1}",
                focusRingColor = focusRingColor,
                testTag = "ciacola-lint-scope-beat",
                onClick = { onSwitchScope(LintScope.CurrentBeat) },
            )
            LintSegmentedPill(
                label = "All Slides",
                selected = controller.scope == LintScope.AllSlides,
                tooltip = "Show lint diagnostics across the entire deck",
                focusRingColor = focusRingColor,
                testTag = "ciacola-lint-scope-all",
                onClick = { onSwitchScope(LintScope.AllSlides) },
            )

            val errorsCount = controller.errorCount
            val warningsCount = controller.warningCount
            val totalCount = controller.activeScopeIssues.size

            LintSegmentedPill(
                label = "All ($totalCount)",
                selected = controller.severityFilter == LintSeverityFilter.All,
                tooltip = "Show both errors and warnings",
                focusRingColor = focusRingColor,
                testTag = "ciacola-lint-filter-all",
                onClick = { controller.severityFilter = LintSeverityFilter.All },
            )
            LintSegmentedPill(
                label = "Errors ($errorsCount)",
                selected = controller.severityFilter == LintSeverityFilter.ErrorsOnly,
                tooltip = "Filter to blocking lint errors only",
                focusRingColor = focusRingColor,
                testTag = "ciacola-lint-filter-errors",
                onClick = { controller.severityFilter = LintSeverityFilter.ErrorsOnly },
            )
            LintSegmentedPill(
                label = "Warnings ($warningsCount)",
                selected = controller.severityFilter == LintSeverityFilter.WarningsOnly,
                tooltip = "Filter to lint warnings only",
                focusRingColor = focusRingColor,
                testTag = "ciacola-lint-filter-warnings",
                onClick = { controller.severityFilter = LintSeverityFilter.WarningsOnly },
            )

            if (controller.hasFullDeckAudit && controller.lastAuditDurationMs > 0L) {
                Text(
                    text = "${controller.lastAuditDurationMs} ms",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                )
            }
        }
    }
}

@Composable
private fun LintSummaryPill(
    errorCount: Int,
    warningCount: Int,
    palette: LintUiPalette,
) {
    val globalColors = JewelTheme.globalColors
    val (bg, border, icon, text) = when {
        errorCount == 0 && warningCount == 0 -> SummaryBadgeVisual(
            bg = palette.ok.copy(alpha = 0.14f),
            border = palette.ok.copy(alpha = 0.55f),
            icon = AllIconsKeys.General.GreenCheckmark,
            label = "0 Issues",
        )
        errorCount > 0 && warningCount > 0 -> SummaryBadgeVisual(
            bg = palette.error.copy(alpha = 0.14f),
            border = palette.error.copy(alpha = 0.55f),
            icon = AllIconsKeys.General.Error,
            label = "$errorCount ${pluralize(errorCount, "Error")} · $warningCount ${pluralize(warningCount, "Warning")}",
        )
        errorCount > 0 -> SummaryBadgeVisual(
            bg = palette.error.copy(alpha = 0.14f),
            border = palette.error.copy(alpha = 0.55f),
            icon = AllIconsKeys.General.Error,
            label = "$errorCount ${pluralize(errorCount, "Error")}",
        )
        else -> SummaryBadgeVisual(
            bg = palette.warning.copy(alpha = 0.14f),
            border = palette.warning.copy(alpha = 0.55f),
            icon = AllIconsKeys.General.Warning,
            label = "$warningCount ${pluralize(warningCount, "Warning")}",
        )
    }

    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Deck lint summary: $text"
            }
            .testTag("ciacola-lint-summary-pill"),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(key = icon, contentDescription = null, modifier = Modifier.size(13.dp))
        Text(
            text = text,
            style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
            color = globalColors.text.normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LintSegmentedPill(
    label: String,
    selected: Boolean,
    tooltip: String,
    focusRingColor: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(5.dp)
    val bg = if (selected) globalColors.outlines.focused.copy(alpha = 0.18f) else Color.Transparent
    val borderColor = if (selected) globalColors.outlines.focused else globalColors.borders.normal

    Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(bg, shape)
                .border(1.dp, borderColor, shape)
                .accessibleFocusRing(focused, focusRingColor, cornerRadius = 5.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                )
                .semantics {
                    this.selected = selected
                }
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag(testTag),
        ) {
            Text(
                text = label,
                style = JewelTheme.typography.small.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = globalColors.text.normal,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun LintIssueCard(
    issue: DeckLintIssue,
    isSelected: Boolean,
    palette: LintUiPalette,
    focusRingColor: Color,
    canApplyPatch: Boolean,
    canOpenEditor: Boolean,
    onSelectAndJump: () -> Unit,
    onHoverChanged: (Boolean) -> Unit,
    onQuickFix: (LintQuickFix) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val severityColor = if (issue.severity == LintSeverity.Error) palette.error else palette.warning
    val severityIcon = if (issue.severity == LintSeverity.Error) {
        AllIconsKeys.General.Error
    } else {
        AllIconsKeys.General.Warning
    }

    val cardBorder = if (isSelected) severityColor else globalColors.borders.normal
    val cardBg = if (isSelected) {
        severityColor.copy(alpha = 0.10f)
    } else {
        globalColors.panelBackground
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBg, shape)
            .border(if (isSelected) 1.5.dp else 1.dp, cardBorder, shape)
            .accessibleFocusRing(focused, focusRingColor, cornerRadius = 6.dp)
            .hoverable(interactionSource)
            .pointerInput(issue.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> onHoverChanged(true)
                            PointerEventType.Exit -> onHoverChanged(false)
                        }
                    }
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = "Jump to ${issue.locationBadge} and highlight element",
                onClick = onSelectAndJump,
            )
            .padding(10.dp)
            .testTag("ciacola-lint-issue-${issue.id}"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = severityIcon,
                contentDescription = if (issue.severity == LintSeverity.Error) "Error" else "Warning",
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = issue.ruleId.shortLabel,
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = severityColor,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .background(globalColors.borders.normal.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = issue.locationBadge,
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Medium),
                    color = globalColors.text.normal,
                    maxLines = 1,
                )
            }
            Text(
                text = issue.elementLabel,
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = globalColors.text.normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = issue.detail,
            style = JewelTheme.typography.small,
            color = globalColors.text.normal,
        )

        if (issue.textSnippet.isNotBlank()) {
            Text(
                text = "\"${issue.textSnippet}\"",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val actionableFixes = issue.quickFixes.filter { fix ->
            (fix.patch != null && canApplyPatch) || (fix.opensElementEditor && canOpenEditor)
        }
        if (actionableFixes.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (fix in actionableFixes) {
                    val fixInteraction = remember { MutableInteractionSource() }
                    val fixFocused by fixInteraction.collectIsFocusedAsState()
                    Tooltip(tooltip = { Text(fix.tooltip) }) {
                        OutlinedButton(
                            onClick = { onQuickFix(fix) },
                            interactionSource = fixInteraction,
                            modifier = Modifier
                                .accessibleFocusRing(fixFocused, focusRingColor)
                                .testTag("ciacola-lint-fix-${issue.id}-${fix.label}"),
                        ) {
                            Text(
                                text = fix.label,
                                style = JewelTheme.typography.small,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LintErrorBanner(
    message: String,
    palette: LintUiPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.error.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.General.Error, contentDescription = "Audit error", modifier = Modifier.size(14.dp))
        Text(
            text = message,
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.normal,
        )
    }
}

@Composable
private fun LintEmptyState(
    controller: DeckLintController,
    palette: LintUiPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            key = AllIconsKeys.General.GreenCheckmark,
            contentDescription = "No lint issues",
            modifier = Modifier.size(22.dp),
            tint = palette.ok,
        )
        Spacer(Modifier.size(8.dp))
        val headline = when {
            controller.severityFilter != LintSeverityFilter.All && controller.activeScopeIssues.isNotEmpty() ->
                "No matching ${if (controller.severityFilter == LintSeverityFilter.ErrorsOnly) "errors" else "warnings"}."
            controller.scope == LintScope.AllSlides && controller.hasFullDeckAudit ->
                "All slides passed with 0 lint issues."
            else ->
                "Current beat S${controller.currentSceneIndex + 1}:B${controller.currentBeatIndex + 1} passed with 0 issues."
        }
        InfoText(headline)
    }
}

@Immutable
private data class SummaryBadgeVisual(
    val bg: Color,
    val border: Color,
    val icon: IconKey,
    val label: String,
)

private fun pluralize(count: Int, singular: String): String =
    if (count == 1) singular else "${singular}s"
