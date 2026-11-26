package dev.bnorm.storyboard.easel.overview

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview
import dev.bnorm.storyboard.easel.internal.requestFocus
import dev.bnorm.storyboard.easel.rememberSharedContentState
import dev.bnorm.storyboard.easel.sharedElement
import kotlin.math.abs

internal const val OVERVIEW_LARGE_DECK_THRESHOLD = 12

private val RAW_SPECIMEN_TITLE_REGEX = Regex("^[a-z][a-z0-9_-]*\\.([A-Za-z][A-Za-z0-9_]*)$")
private val WORD_SPLIT_REGEX = Regex("[\\s\\-_:/·.]+")

internal fun formatOverviewBadgeNumber(slideIndex: Int): String =
    "#${(slideIndex + 1).toString().padStart(2, '0')}"

internal fun cleanOverviewSlideTitle(
    rawTitle: String?,
    slideIndex: Int,
): String? {
    val trimmed = rawTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val oneBased = slideIndex + 1
    // Strip redundant "Slide N · " or "Slide N:" prefix since cards already carry a `#NN` badge.
    val prefixRegex = Regex("^Slide\\s+0?$oneBased\\s*(?:[·:\\-–—]+\\s*)?", RegexOption.IGNORE_CASE)
    val stripped = trimmed.replace(prefixRegex, "").trim()
    if (stripped.isEmpty()) return null
    val specimenMatch = RAW_SPECIMEN_TITLE_REGEX.matchEntire(stripped)
    if (specimenMatch != null) {
        val rawName = specimenMatch.groupValues[1]
        val spaced = rawName
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Za-z])([0-9])"), "$1 $2")
            .replace('_', ' ')
            .trim()
        return spaced.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    return stripped
}

internal fun overviewSlideLabel(
    column: OverviewState.SceneColumn<*>,
    slideTitle: ((Int) -> String?)? = null,
): String {
    val explicit = cleanOverviewSlideTitle(slideTitle?.invoke(column.index), column.index)
    if (!explicit.isNullOrEmpty()) return explicit
    val frameLabel = column.items.firstOrNull()?.state
        ?.takeIf { it is String || it is Enum<*> }
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return frameLabel ?: "Slide ${column.index + 1}"
}

internal fun filterOverviewSlideColumns(
    columns: List<OverviewState.SceneColumn<*>>,
    query: String,
    slideTitle: ((Int) -> String?)? = null,
): List<OverviewState.SceneColumn<*>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return columns
    val normalized = trimmed.lowercase()
    val strippedHash = normalized.removePrefix("#").trim()
    val numericToken = strippedHash
        .removePrefix("slide")
        .removePrefix("s")
        .trim()
    val isPureNumeric = numericToken.isNotEmpty() && numericToken.all { it.isDigit() }
    val numericCandidate = if (isPureNumeric) numericToken.toIntOrNull() else null
    val canonicalNumeric = numericCandidate?.toString()

    data class RankedColumn(
        val column: OverviewState.SceneColumn<*>,
        val tier: Int,
        val digitLength: Int,
        val oneBased: Int,
    )

    val ranked = ArrayList<RankedColumn>()
    for (col in columns) {
        val oneBased = col.index + 1
        val numStr = oneBased.toString()
        val padded = numStr.padStart(2, '0')
        val cleanTitle = cleanOverviewSlideTitle(slideTitle?.invoke(col.index), col.index).orEmpty()
        val rawTitle = slideTitle?.invoke(col.index)?.trim().orEmpty()
        val label = overviewSlideLabel(col, slideTitle)
        val searchable = "$cleanTitle $rawTitle $label".lowercase()
        val words = searchable.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }

        val tier = when {
            canonicalNumeric != null && numStr == canonicalNumeric -> 0
            canonicalNumeric != null && (numStr.startsWith(canonicalNumeric) || padded.startsWith(numericToken)) -> 1
            canonicalNumeric != null && numStr.contains(canonicalNumeric) -> 2
            words.any { it.startsWith(strippedHash) } || cleanTitle.lowercase().startsWith(strippedHash) -> 3
            searchable.contains(normalized) ||
                searchable.contains(strippedHash) ||
                "#$numStr".contains(normalized) ||
                "#$padded".contains(normalized) -> 4
            else -> -1
        }
        if (tier >= 0) {
            ranked += RankedColumn(
                column = col,
                tier = tier,
                digitLength = if (tier == 1) numStr.length else 0,
                oneBased = oneBased,
            )
        }
    }

    ranked.sortWith(compareBy<RankedColumn>({ it.tier }, { it.digitLength }, { it.oneBased }))
    return ranked.map { it.column }
}

// TODO keep assistant up-to-date?
// TODO disable assistant navigation while in overview?
@Composable
context(_: AnimatedVisibilityScope, _: SharedTransitionScope)
internal fun EaselOverviewInternal(
    animatic: Animatic,
    overviewState: OverviewState,
    isVisible: Boolean,
    onExitOverview: (Storyboard.Index) -> Unit,
    modifier: Modifier = Modifier,
    useSharedElement: Boolean = true,
    slideTitle: ((Int) -> String?)? = null,
    thumbnailStoryboard: Storyboard = animatic.storyboard,
    thumbnailFrameIndex: ((sceneIndex: Int, currentFrameIndex: Int) -> Int)? = null,
    onDismiss: () -> Unit = {},
) {
    val slideCount = overviewState.columns.size
    val isLargeDeck = slideCount > OVERVIEW_LARGE_DECK_THRESHOLD
    var isGridMode by remember(animatic.storyboard) { mutableStateOf(isLargeDeck) }
    var quickJumpOpen by remember(animatic.storyboard) { mutableStateOf(false) }
    var quickJumpQuery by remember(animatic.storyboard) { mutableStateOf("") }
    var quickJumpHighlightColumnIndex by remember(animatic.storyboard) { mutableStateOf<Int?>(null) }
    var suppressNextEscapeKeyUp by remember { mutableStateOf(false) }
    val overviewFocusRequester = remember { FocusRequester() }
    val quickJumpFocusRequester = remember { FocusRequester() }

    val filteredColumns = remember(overviewState.columns, quickJumpQuery, slideTitle) {
        if (!isLargeDeck || quickJumpQuery.isBlank()) {
            overviewState.columns
        } else {
            filterOverviewSlideColumns(overviewState.columns, quickJumpQuery, slideTitle)
        }
    }

    LaunchedEffect(quickJumpOpen) {
        if (quickJumpOpen) {
            runCatching { quickJumpFocusRequester.requestFocus() }
        } else {
            quickJumpHighlightColumnIndex = null
        }
    }

    // Track the top Quick Jump candidate purely as a preview highlight without mutating
    // overviewState or animatic.currentIndex until the user explicitly confirms (Enter / click).
    LaunchedEffect(quickJumpOpen, quickJumpQuery, filteredColumns) {
        quickJumpHighlightColumnIndex = if (quickJumpOpen && quickJumpQuery.isNotBlank()) {
            filteredColumns.firstOrNull()?.index
        } else {
            null
        }
    }

    fun closeQuickJump() {
        quickJumpOpen = false
        quickJumpQuery = ""
        quickJumpHighlightColumnIndex = null
        runCatching { overviewFocusRequester.requestFocus() }
    }

    fun confirmColumnJump(column: OverviewState.SceneColumn<*>) {
        quickJumpOpen = false
        quickJumpQuery = ""
        quickJumpHighlightColumnIndex = null
        overviewState.jumpTo(column.index)
        onExitOverview(column.items[column.currentItemIndex].index)
    }

    fun onClick(columnIndex: Int, itemIndex: Int, item: OverviewState.StateItem<*>) {
        if (item == overviewState.currentItem) {
            onExitOverview(item.index)
        }
        overviewState.jumpTo(columnIndex, itemIndex)
    }

    val currentIndex = overviewState.currentItem.index
    val beatCount = overviewState.columns[overviewState.currentColumnIndex].items.size
    LaunchedEffect(currentIndex) {
        animatic.jumpTo(currentIndex)
    }

    val chromeStyle = currentOverviewChromeStyle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(chromeStyle.background)
            .onPreviewKeyEvent { event ->
                if (!isLargeDeck) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.K &&
                    (event.isMetaPressed || event.isCtrlPressed)
                ) {
                    quickJumpOpen = true
                    runCatching { quickJumpFocusRequester.requestFocus() }
                    return@onPreviewKeyEvent true
                }
                if (quickJumpOpen && event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyDown) {
                        suppressNextEscapeKeyUp = true
                        closeQuickJump()
                    } else if (event.type == KeyEventType.KeyUp) {
                        suppressNextEscapeKeyUp = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (suppressNextEscapeKeyUp && event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                    suppressNextEscapeKeyUp = false
                    return@onPreviewKeyEvent true
                }
                false
            },
    ) {
        // Dedicated non-overlapping top chrome bar: slide/beat status on the left,
        // [ ▤ Strip | ⊞ Grid ] segmented control and ⌘K Quick Jump on the right for large decks.
        OverviewChromeBar(
            slideBadgeText = formatOverviewStatus(currentIndex.sceneIndex, slideCount, currentIndex.frameIndex, beatCount),
            isLargeDeck = isLargeDeck,
            isGridMode = isGridMode,
            onSelectGridMode = { grid ->
                isGridMode = grid
                if (!quickJumpOpen) {
                    runCatching { overviewFocusRequester.requestFocus() }
                }
            },
            quickJumpOpen = quickJumpOpen,
            quickJumpQuery = quickJumpQuery,
            onOpenQuickJump = { quickJumpOpen = true },
            onQueryChange = { quickJumpQuery = it },
            onCloseQuickJump = ::closeQuickJump,
            onSubmitQuickJump = {
                val target = filteredColumns.firstOrNull { it.index == quickJumpHighlightColumnIndex }
                    ?: filteredColumns.firstOrNull()
                if (target != null) {
                    confirmColumnJump(target)
                }
            },
            matchCount = filteredColumns.size,
            totalSlides = slideCount,
            quickJumpFocusRequester = quickJumpFocusRequester,
            stripMatches = if (isLargeDeck && !isGridMode && quickJumpOpen && quickJumpQuery.isNotBlank()) {
                filteredColumns.take(8)
            } else {
                emptyList()
            },
            slideTitle = slideTitle,
            onPickMatch = ::confirmColumnJump,
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val viewSize = DpSize(maxWidth, maxHeight)
            val gridColumnCount = ((maxWidth - 64.dp) / 220.dp).toInt().coerceIn(3, 6)
            val maxItemsInColumn = remember(overviewState.columns) {
                overviewState.columns.maxOfOrNull { stripColumnItemCount(it) } ?: 1
            }
            val stripLayout = calculateOverviewStripLayout(
                viewSize = viewSize,
                contentSize = animatic.storyboard.format.size,
                maxItemsInColumn = maxItemsInColumn,
            )
            val itemSize = stripLayout.itemSize

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(overviewFocusRequester)
                    .onOverviewNavigation(
                        overview = overviewState,
                        isGridMode = isLargeDeck && isGridMode,
                        gridColumnCount = gridColumnCount,
                        navigableColumns = if (isLargeDeck && isGridMode) filteredColumns else overviewState.columns,
                        quickJumpOpen = quickJumpOpen,
                        onExitOverview = onExitOverview,
                        onDismiss = onDismiss,
                    ),
            ) {
                if (isLargeDeck && isGridMode) {
                    val highlightedColumnIndex = if (quickJumpOpen && quickJumpQuery.isNotBlank()) {
                        quickJumpHighlightColumnIndex
                    } else {
                        overviewState.currentColumnIndex
                    }
                    OverviewGridView(
                        storyboard = thumbnailStoryboard,
                        overviewState = overviewState,
                        highlightedColumnIndex = highlightedColumnIndex,
                        quickJumpOpen = quickJumpOpen,
                        quickJumpQuery = quickJumpQuery,
                        columns = filteredColumns,
                        gridColumnCount = gridColumnCount,
                        slideTitle = slideTitle,
                        thumbnailFrameIndex = thumbnailFrameIndex,
                        onSelectOrExit = { column ->
                            if (quickJumpOpen && quickJumpQuery.isNotBlank()) {
                                confirmColumnJump(column)
                            } else {
                                val item = column.items[column.currentItemIndex]
                                if (column.index == overviewState.currentColumnIndex) {
                                    onExitOverview(item.index)
                                } else {
                                    overviewState.jumpTo(column.index)
                                }
                            }
                        },
                    )
                } else {
                    val hState = rememberOverviewSceneScroll(viewSize, itemSize, overviewState)

                    LazyRow(
                        state = hState,
                        modifier = Modifier.fillMaxSize().testTag("easel-overview-strip"),
                    ) {
                        itemsIndexed(overviewState.columns) { columnIndex, column ->
                            val isCurrentColumn = columnIndex == overviewState.currentColumnIndex
                            // Columns with fewer builds than the strip's rows sit centred, so
                            // short columns never leave a dead band at the bottom of the strip.
                            val verticalPaddingDp = stripColumnTopPadding(
                                layout = stripLayout,
                                itemCount = stripColumnItemCount(column),
                            )
                            val vState = rememberOverviewStateScroll(stripLayout.rowsVisible, column)

                            // TODO how to keep the user from scrolling to a start or end frame?
                            //  - invisible padding item with different content padding?
                            OverviewLazyColumn(
                                state = vState,
                                storyboard = thumbnailStoryboard,
                                column = column,
                                isVisible = isVisible,
                                useSharedElement = useSharedElement,
                                verticalPaddingDp = verticalPaddingDp,
                                itemSize = itemSize,
                                isCurrentColumn = isCurrentColumn,
                                onClick = { itemIndex, item ->
                                    onClick(
                                        columnIndex = columnIndex,
                                        itemIndex = itemIndex,
                                        item = item
                                    )
                                },
                            )
                        }
                    }

                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(hState),
                        modifier = Modifier.fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberOverviewSceneScroll(
    viewSize: DpSize,
    itemSize: DpSize,
    overview: OverviewState,
): LazyListState {
    val horizontalPaddingDp = (viewSize.width - itemSize.width).coerceAtLeast(0.dp) / 2
    val horizontalScrollOffset = with(LocalDensity.current) { horizontalPaddingDp.toPx() }

    val hIndex = overview.currentColumnIndex
    val hState = rememberLazyListState(hIndex, -horizontalScrollOffset.toInt())
    LaunchedEffect(hIndex, horizontalScrollOffset) {
        if (abs(hState.firstVisibleItemIndex - hIndex) > 6) {
            hState.scrollToItem(hIndex, -horizontalScrollOffset.toInt())
        } else {
            hState.animateScrollToItem(hIndex, -horizontalScrollOffset.toInt())
        }
    }

    return hState
}

/** Top padding that vertically centres a strip column of [itemCount] items within the strip rows. */
internal fun stripColumnTopPadding(layout: OverviewStripLayout, itemCount: Int): Dp {
    val emptyRows = (layout.rowsVisible - itemCount).coerceAtLeast(0)
    return layout.verticalPadding + layout.itemSize.height * emptyRows / 2
}

/** Items a strip column renders, including the faded start/end placeholder frames. */
internal fun stripColumnItemCount(column: OverviewState.SceneColumn<*>): Int =
    column.items.size + (if (column.start) 1 else 0) + (if (column.end) 1 else 0)

/** `Slide 3 of 136 · Build 1 of 2` status text for the Overview chrome bar. */
internal fun formatOverviewStatus(sceneIndex: Int, slideCount: Int, frameIndex: Int, buildCount: Int): String {
    val slides = "Slide ${sceneIndex + 1} of ${slideCount.coerceAtLeast(1)}"
    return if (buildCount > 1) "$slides · Build ${frameIndex + 1} of $buildCount" else slides
}

@Composable
private fun rememberOverviewStateScroll(
    rowsVisible: Int,
    column: OverviewState.SceneColumn<*>,
): LazyListState {
    val vIndex = column.currentItemIndex + if (column.start) 1 else 0
    val vState = rememberLazyListState(stripFirstVisibleIndex(vIndex, rowsVisible))
    LaunchedEffect(vIndex, rowsVisible) {
        val first = vState.firstVisibleItemIndex
        // Scroll only as far as needed to keep the current build fully visible (never clip it).
        if (vIndex < first) {
            vState.animateScrollToItem(vIndex)
        } else if (vIndex > first + rowsVisible - 1) {
            vState.animateScrollToItem(stripFirstVisibleIndex(vIndex, rowsVisible))
        }
    }
    return vState
}

@Composable
context(_: AnimatedVisibilityScope, _: SharedTransitionScope)
private fun OverviewLazyColumn(
    state: LazyListState,
    storyboard: Storyboard,
    column: OverviewState.SceneColumn<*>,
    isVisible: Boolean,
    useSharedElement: Boolean,
    verticalPaddingDp: Dp,
    itemSize: DpSize,
    isCurrentColumn: Boolean,
    onClick: (Int, OverviewState.StateItem<*>) -> Unit,
) {
    LazyColumn(
        state = state,
        userScrollEnabled = column.items.size > 1,
        contentPadding = PaddingValues(vertical = verticalPaddingDp),
        modifier = Modifier.fillMaxHeight()
    ) {
        if (column.start) {
            item("start") {
                Item(itemSize) {
                    ScenePreview(
                        storyboard = storyboard,
                        scene = column.scene,
                        frame = Frame.Start,
                        modifier = Modifier.alpha(0.5f),
                    )
                }
            }
        }

        itemsIndexed(column.items) { itemIndex, item ->
            val isCurrentIndex = isCurrentColumn && column.currentItemIndex == itemIndex
            val chrome = currentOverviewChromeStyle()
            val currentColor by animateColorAsState(
                when {
                    isCurrentIndex -> chrome.accent
                    else -> chrome.border
                }
            )

            val stripBadge = if (column.items.size > 1) {
                "${formatOverviewBadgeNumber(column.index)} · Build ${itemIndex + 1} of ${column.items.size}"
            } else {
                formatOverviewBadgeNumber(column.index)
            }

            Item(
                size = itemSize,
                borderColor = currentColor,
                badgeText = stripBadge,
                onClick = { onClick(itemIndex, item) }
            ) {
                ScenePreview(
                    storyboard = storyboard,
                    index = item.index,
                    modifier = when {
                        isCurrentIndex && useSharedElement -> Modifier.sharedElement(rememberSharedContentState(OverviewCurrentItemKey))
                        else -> Modifier
                    },
                    sceneMode = if (isVisible && isCurrentIndex) SceneMode.Story else SceneMode.Preview,
                )
            }
        }

        if (column.end) {
            item("end") {
                Item(itemSize) {
                    ScenePreview(
                        storyboard = storyboard,
                        scene = column.scene,
                        frame = Frame.End,
                        modifier = Modifier.alpha(0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Item(
    size: DpSize,
    borderColor: Color = Color.Transparent,
    badgeText: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            // !!! Make sure this padding is set in the default parameter of `toItemSize` !!!
            .padding(4.dp)
            .border(2.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        content()

        // Cover the scene content with a clickable modifier
        // to disable interaction while in overview.
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerHoverIcon(if (onClick != null) PointerIcon.Hand else PointerIcon.Default)
                .clickable(
                    interactionSource = null, indication = null, // disable ripple effect
                    onClick = { onClick?.invoke() }
                )
        )

        if (!badgeText.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badgeText,
                    style = currentOverviewChromeStyle().caption(weight = FontWeight.Medium, color = Color.White),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
context(animatedVisibilityScope: AnimatedVisibilityScope)
private fun Modifier.onOverviewNavigation(
    overview: OverviewState,
    isGridMode: Boolean,
    gridColumnCount: Int,
    navigableColumns: List<OverviewState.SceneColumn<*>>,
    quickJumpOpen: Boolean,
    onExitOverview: (Storyboard.Index) -> Unit,
    onDismiss: () -> Unit,
): Modifier {
    fun handle(event: KeyEvent): Boolean {
        // Disable navigation until enter/exit animation is complete.
        if (animatedVisibilityScope.transition.isRunning) return false

        // Esc toggles: it opens the overview on KeyUp, so a second Esc closes it (without jumping).
        if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
            if (quickJumpOpen) return false
            onDismiss()
            return true
        }

        if (event.type == KeyEventType.KeyDown) {
            if (quickJumpOpen) return false
            val currentColumnIndex = overview.currentColumnIndex
            if (isGridMode) {
                val pos = navigableColumns.indexOfFirst { it.index == currentColumnIndex }
                when (event.key) {
                    Key.Enter -> {
                        onExitOverview(overview.currentItem.index)
                        return true
                    }

                    Key.DirectionRight -> {
                        if (navigableColumns.isNotEmpty()) {
                            val nextPos = (if (pos < 0) 0 else pos + 1).coerceIn(navigableColumns.indices)
                            overview.jumpTo(navigableColumns[nextPos].index)
                        }
                        return true
                    }

                    Key.DirectionLeft -> {
                        if (navigableColumns.isNotEmpty()) {
                            val nextPos = (if (pos < 0) 0 else pos - 1).coerceIn(navigableColumns.indices)
                            overview.jumpTo(navigableColumns[nextPos].index)
                        }
                        return true
                    }

                    Key.DirectionDown -> {
                        if (navigableColumns.isNotEmpty()) {
                            val nextPos = (if (pos < 0) 0 else pos + gridColumnCount).coerceIn(navigableColumns.indices)
                            overview.jumpTo(navigableColumns[nextPos].index)
                        }
                        return true
                    }

                    Key.DirectionUp -> {
                        if (navigableColumns.isNotEmpty()) {
                            val nextPos = (if (pos < 0) 0 else pos - gridColumnCount).coerceIn(navigableColumns.indices)
                            overview.jumpTo(navigableColumns[nextPos].index)
                        }
                        return true
                    }

                    else -> return false
                }
            }

            when (event.key) {
                Key.Enter -> {
                    onExitOverview(overview.currentItem.index)
                    return true
                }

                Key.DirectionRight -> {
                    var index = currentColumnIndex + 1
                    while (index < overview.columns.size && overview.columns[index].items.isEmpty()) index += 1
                    overview.jumpTo(index)
                    return true
                }

                Key.DirectionLeft -> {
                    var index = currentColumnIndex - 1
                    while (index >= 0 && overview.columns[index].items.isEmpty()) index -= 1
                    overview.jumpTo(index)
                    return true
                }

                Key.DirectionDown -> {
                    val column = overview.columns[currentColumnIndex]
                    overview.jumpTo(currentColumnIndex, column.currentItemIndex + 1)
                    return true
                }

                Key.DirectionUp -> {
                    val column = overview.columns[currentColumnIndex]
                    overview.jumpTo(currentColumnIndex, column.currentItemIndex - 1)
                    return true
                }

                else -> return false
            }
        }

        return false
    }

    return requestFocus().onKeyEvent { handle(it) }
}
