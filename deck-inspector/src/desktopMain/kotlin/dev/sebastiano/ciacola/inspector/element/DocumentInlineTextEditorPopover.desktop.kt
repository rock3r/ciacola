package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.TextRangeEdit
import dev.sebastiano.ciacola.inspector.design.inspectorAccent
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.inspector.design.inspectorCaptionStyle
import dev.sebastiano.ciacola.ui.CiacolaPrimaryButton
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

/**
 * Inline stage text editor popover with real per-input UTF-16 range provenance and local `⌘Z`.
 * Placement is stable: below the node when it fits, otherwise above, then beside it, and always
 * clamped inside the stage so it never clips off the top edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentInlineTextEditorPopover(
    controller: DocumentElementInspectorController,
    editingNodeId: String,
    displayName: String,
    targetRectPx: Rect,
    siblingTextRectsPx: List<Rect>,
    overlaySizePx: IntSize,
) {
    val density = LocalDensity.current
    val colors = JewelTheme.globalColors
    var popoverMeasuredSize by remember(editingNodeId) { mutableStateOf(Size(340f, 160f)) }
    val gapPx = with(density) { 12.dp.toPx() }
    val clamped = computeClampedInlineEditorOffset(
        targetBounds = targetRectPx,
        popoverSizePx = popoverMeasuredSize,
        stageWidthPx = overlaySizePx.width.toFloat().coerceAtLeast(240f),
        stageHeightPx = overlaySizePx.height.toFloat().coerceAtLeast(160f),
        marginPx = INLINE_EDITOR_MARGIN_PX,
        gapPx = gapPx,
        siblingTextBounds = siblingTextRectsPx,
    )
    val textFieldState = rememberTextFieldState(initialText = controller.inlineOriginalText)
    val recordedEdits = remember(editingNodeId) { mutableStateListOf<TextRangeEdit>() }
    val focusRequester = remember(editingNodeId) { FocusRequester() }

    // Capture real UTF-16 edit ranges straight from Compose's TextFieldBuffer.changes.
    val provenanceRecorder = remember(editingNodeId) {
        InputTransformation {
            val ch = changes
            for (i in 0 until ch.changeCount) {
                val origRange = ch.getOriginalRange(i)
                val newRange = ch.getRange(i)
                val inserted = asCharSequence().subSequence(newRange.min, newRange.max).toString()
                recordedEdits += TextRangeEdit(start = origRange.min, removedLength = origRange.length, insertedText = inserted)
            }
        }
    }

    LaunchedEffect(editingNodeId) {
        textFieldState.setTextAndPlaceCursorAtEnd(controller.inlineOriginalText)
        recordedEdits.clear()
        runCatching { focusRequester.requestFocus() }
    }

    fun commit() {
        controller.commitInlineTextEdits(capturedEdits = recordedEdits.toList(), finalText = textFieldState.text.toString())
    }

    Column(
        modifier = Modifier
            .onSizeChanged { popoverMeasuredSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .offset { IntOffset(clamped.x.roundToInt(), clamped.y.roundToInt()) }
            .width(340.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.panelBackground)
            .border(1.dp, inspectorAccent(), RoundedCornerShape(6.dp))
            .padding(8.dp)
            .testTag("ciacola-doc-inline-text-popover"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Edit \u201C$displayName\u201D",
                style = inspectorBodyStyle(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("ciacola-doc-inline-text-header"),
            )
            Text(text = "⌘↩ Commit · Esc Cancel", style = inspectorCaptionStyle(), color = colors.text.info, maxLines = 1)
        }

        TextArea(
            state = textFieldState,
            inputTransformation = provenanceRecorder,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 140.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { st -> controller.textInputHasFocus = st.isFocused }
                .trackAuthoringTextInputFocus()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        ev.key == Key.Escape -> {
                            controller.cancelInlineTextEdit()
                            true
                        }
                        (ev.isMetaPressed || ev.isCtrlPressed) && ev.key == Key.Enter -> {
                            commit()
                            true
                        }
                        // Explicitly do NOT intercept ⌘Z / ⇧⌘Z: leave local undo inside TextArea!
                        else -> false
                    }
                }
                .onKeyEvent { ev ->
                    // Consume bubble-phase Spacebar/navigation keys so typing never advances slides.
                    ev.key == Key.Spacebar ||
                        ev.key == Key.DirectionLeft ||
                        ev.key == Key.DirectionRight ||
                        ev.key == Key.DirectionUp ||
                        ev.key == Key.DirectionDown ||
                        ev.key == Key.Backspace
                }
                .testTag("ciacola-doc-inline-text-field"),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = { controller.cancelInlineTextEdit() },
                modifier = Modifier.testTag("ciacola-doc-inline-text-cancel"),
            ) { Text("Cancel", style = inspectorBodyStyle()) }
            Box(Modifier.width(6.dp))
            CiacolaPrimaryButton(
                text = "Commit",
                onClick = { commit() },
                modifier = Modifier.testTag("ciacola-doc-inline-text-commit"),
            )
        }
    }
}
