package dev.bnorm.storyboard.easel.overview

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview

/**
 * Overview chrome bar: slide/build status on the left; `Strip | Grid` segmented control and the
 * `⌘K` Quick Jump on the right for large decks. Styled with the host's [OverviewChromeStyle].
 */
@Composable
internal fun OverviewChromeBar(
    slideBadgeText: String,
    isLargeDeck: Boolean,
    isGridMode: Boolean,
    onSelectGridMode: (Boolean) -> Unit,
    quickJumpOpen: Boolean,
    quickJumpQuery: String,
    onOpenQuickJump: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCloseQuickJump: () -> Unit,
    onSubmitQuickJump: () -> Unit,
    matchCount: Int,
    totalSlides: Int,
    quickJumpFocusRequester: FocusRequester,
    stripMatches: List<OverviewState.SceneColumn<*>>,
    slideTitle: ((Int) -> String?)?,
    onPickMatch: (OverviewState.SceneColumn<*>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = currentOverviewChromeStyle()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(style.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = slideBadgeText,
                style = style.body(weight = FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.testTag("easel-overview-slide-badge"),
            )

            if (isLargeDeck) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, style.border, RoundedCornerShape(6.dp))
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        OverviewModeSegment(
                            label = "Strip",
                            selected = !isGridMode,
                            onClick = { onSelectGridMode(false) },
                            modifier = Modifier.testTag("easel-overview-strip-toggle"),
                        )
                        OverviewModeSegment(
                            label = "Grid",
                            selected = isGridMode,
                            onClick = { onSelectGridMode(true) },
                            modifier = Modifier.testTag("easel-overview-grid-toggle"),
                        )
                    }

                    if (quickJumpOpen) {
                        QuickJumpField(
                            query = quickJumpQuery,
                            onQueryChange = onQueryChange,
                            onSubmit = onSubmitQuickJump,
                            onClose = onCloseQuickJump,
                            matchCount = matchCount,
                            totalSlides = totalSlides,
                            focusRequester = quickJumpFocusRequester,
                            style = style,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, style.border, RoundedCornerShape(6.dp))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = onOpenQuickJump)
                                .padding(horizontal = 10.dp)
                                .testTag("easel-overview-quick-jump-trigger"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "Jump to slide…  ${style.quickJumpShortcutLabel}", style = style.body(color = style.mutedOnSurface))
                        }
                    }
                }
            }
        }

        if (stripMatches.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (col in stripMatches) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, style.border, RoundedCornerShape(4.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onPickMatch(col) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("easel-overview-quick-jump-result-${col.index}"),
                    ) {
                        Text(
                            text = overviewCardCaption(col.index, overviewSlideLabel(col, slideTitle)),
                            style = style.caption(color = style.onSurface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(style.border))
    }
}

@Composable
private fun QuickJumpField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    matchCount: Int,
    totalSlides: Int,
    focusRequester: FocusRequester,
    style: OverviewChromeStyle,
) {
    Row(
        modifier = Modifier
            .height(26.dp)
            .widthIn(min = 240.dp, max = 340.dp)
            .border(1.dp, style.accent, RoundedCornerShape(6.dp))
            .background(style.background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = "Slide number or title…", style = style.body(color = style.mutedOnSurface), maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = style.body(),
                cursorBrush = SolidColor(style.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("easel-overview-quick-jump-input"),
            )
        }
        Text(
            text = "$matchCount/$totalSlides",
            style = style.caption(),
            modifier = Modifier.testTag("easel-overview-quick-jump-count"),
        )
        Text(
            text = "✕",
            style = style.caption(),
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onClose)
                .padding(horizontal = 2.dp)
                .testTag("easel-overview-quick-jump-close"),
        )
    }
}

@Composable
private fun OverviewModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = currentOverviewChromeStyle()
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .width(56.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) style.accent.copy(alpha = 0.18f) else Color.Transparent, shape)
            .border(1.dp, if (selected) style.accent else Color.Transparent, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = style.body(
                weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) style.onSurface else style.mutedOnSurface,
            ),
        )
    }
}

@Composable
internal fun BoxScope.OverviewGridView(
    storyboard: Storyboard,
    overviewState: OverviewState,
    highlightedColumnIndex: Int?,
    quickJumpOpen: Boolean,
    quickJumpQuery: String,
    columns: List<OverviewState.SceneColumn<*>>,
    gridColumnCount: Int,
    slideTitle: ((Int) -> String?)?,
    thumbnailFrameIndex: ((sceneIndex: Int, currentFrameIndex: Int) -> Int)? = null,
    onSelectOrExit: (OverviewState.SceneColumn<*>) -> Unit,
) {
    val style = currentOverviewChromeStyle()
    val selectedPositionInGrid = columns.indexOfFirst {
        it.index == (highlightedColumnIndex ?: overviewState.currentColumnIndex)
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = selectedPositionInGrid.coerceAtLeast(0),
    )
    val aspectRatio = (storyboard.format.size.width.toFloat() / storyboard.format.size.height.coerceAtLeast(1))
        .coerceIn(0.5f, 3f)

    // Instant scroll on query change / clear so filtering or clearing never lags across 80+ slides.
    LaunchedEffect(quickJumpQuery, columns.size) {
        if (columns.isEmpty()) return@LaunchedEffect
        val targetPos = if (quickJumpQuery.isNotBlank()) 0 else selectedPositionInGrid.coerceAtLeast(0)
        gridState.scrollToItem(targetPos)
    }

    // Keep keyboard-selected slide in view instantaneously without slow long-distance crawl.
    LaunchedEffect(selectedPositionInGrid) {
        if (selectedPositionInGrid < 0 || quickJumpQuery.isNotBlank()) return@LaunchedEffect
        val visible = gridState.layoutInfo.visibleItemsInfo
        val alreadyVisible = visible.any { it.index == selectedPositionInGrid }
        if (!alreadyVisible) {
            gridState.scrollToItem(selectedPositionInGrid)
        }
    }

    if (columns.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No matching slides",
                style = style.body(color = style.mutedOnSurface),
                modifier = Modifier.testTag("easel-overview-grid-empty"),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumnCount),
        state = gridState,
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 28.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("easel-overview-grid"),
    ) {
        items(columns, key = { it.index }) { column ->
            val isSelected = column.index == (highlightedColumnIndex ?: overviewState.currentColumnIndex)
            // When Quick Jump's input owns focus, soften the card selection border so there is only
            // one active focus ring on screen (the search field).
            val targetBorder = when {
                isSelected && quickJumpOpen -> style.onSurface.copy(alpha = 0.55f)
                isSelected -> style.accent
                else -> style.border
            }
            val borderColor by animateColorAsState(targetBorder)
            val borderWidth = if (isSelected && !quickJumpOpen) 2.dp else 1.dp
            val previewFrameIdx = if (column.items.isEmpty()) {
                0
            } else {
                (thumbnailFrameIndex?.invoke(column.index, column.currentItemIndex) ?: column.currentItemIndex)
                    .coerceIn(0, column.items.lastIndex)
            }
            val selectedItem = column.items[previewFrameIdx]
            val captionText = overviewCardCaption(column.index, overviewSlideLabel(column, slideTitle))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
                    .background(style.surface, RoundedCornerShape(6.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { onSelectOrExit(column) },
                    )
                    .padding(4.dp)
                    .testTag("easel-overview-grid-card-${column.index}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clip(RoundedCornerShape(3.dp)),
                ) {
                    ScenePreview(
                        storyboard = storyboard,
                        index = selectedItem.index,
                        sceneMode = SceneMode.Preview,
                    )
                    Box(modifier = Modifier.fillMaxSize())
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = captionText,
                        style = style.body(weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("easel-overview-grid-caption-${column.index}"),
                    )
                    if (column.items.size > 1) {
                        Text(
                            text = formatOverviewBuildCount(column.items.size),
                            style = style.caption(),
                            maxLines = 1,
                            modifier = Modifier.testTag("easel-overview-grid-builds-${column.index}"),
                        )
                    }
                }
            }
        }
    }

    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(gridState),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(top = 12.dp, bottom = 12.dp, end = 6.dp),
    )
}
