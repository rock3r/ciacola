package dev.sebastiano.ciacola.replications.njpt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.replications.jewel.DeterministicIdeSpinner
import dev.sebastiano.ciacola.replications.jewel.GeminiComposerCard
import dev.sebastiano.ciacola.replications.jewel.GeminiPromptPill
import dev.sebastiano.ciacola.replications.jewel.GeminiSparkleMark
import dev.sebastiano.ciacola.replications.jewel.GeminiToolWindowHeader
import dev.sebastiano.ciacola.replications.jewel.IsolatedJewelSurface
import dev.sebastiano.ciacola.replications.jewel.JewelMarkdownSplitWindow
import dev.sebastiano.ciacola.replications.jewel.LiveJewelMarkdownView
import dev.sebastiano.ciacola.replications.jewel.caretVisible
import dev.sebastiano.ciacola.replications.jewel.deterministicTypewriter
import dev.sebastiano.ciacola.replications.jewel.easeInOutSine
import dev.sebastiano.ciacola.replications.jewel.easeOutCubic
import dev.sebastiano.ciacola.replications.jewel.loopProgress
import dev.sebastiano.ciacola.replications.jewel.segmentProgress
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

// ============================================================================
// #21 — njpt.jewelMarkdownSplitDemo (Not Just Plain Text S125, periodMs = 14_000)
// ============================================================================

private const val NJPT_MARKDOWN_MARKER = "{{LIVE_EDIT}}"

private val NJPT_MARKDOWN_BASE = """
# Jewel: a Compose for Desktop theme

Jewel aims to recreate the **IntelliJ Platform New UI** look and feel in Compose for Desktop, including first-class CommonMark + GFM Markdown rendering.

$NJPT_MARKDOWN_MARKER

> [!WARNING]
> Inline HTML tags are sanitized by default unless you explicitly opt into a custom `MarkdownBlockRenderer`.

## Supported AST Blocks

| Element | Engine Layer | Live Status |
| --- | --- | --- |
| Headings & Lists | `MarkdownProcessor` | Stable |
| GFM Tables & Callouts | `ExtensionSpec` | Stable |
| Fenced Kotlin Code | `SyntaxHighlighter` | Stable |

```kotlin
@Composable
fun DocsPanel(rawMarkdown: String) {
    Markdown(
        markdown = rawMarkdown,
        markdownStyling = JewelTheme.markdownStyling,
    )
}
```

- [x] Parse CommonMark + GFM AST cleanly
- [x] Render inline `code`, **bold**, _italic_, and ~~strike~~
- [ ] Ship custom inline emoji extension
""".trimIndent()

private const val NJPT_TYPED_LINE =
    "This works just like **magic** — live AST updates with `parseMarkdownDocument()` and _zero_ WebViews!"

/**
 * Specimen #21 (`njpt.jewelMarkdownSplitDemo`, NJPT S125, `periodMs = 14_000`):
 * Live Jewel Markdown split-pane editor + live AST-to-Compose rendered preview showcasing
 * headings, bold/italic/strikethrough, inline code pills, GFM warning callout, fenced Kotlin
 * code block, GFM table, and task list — driven deterministically by [loopMillis] and also
 * interactively editable!
 */
@Composable
fun NjptJewelMarkdownSplitDemoSpecimen(
    loopMillis: Long = 3_500L,
    periodMs: Long = 14_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    JewelMarkdownSplitWindow(
        loopMillis = loopMillis,
        periodMs = periodMs,
        presetName = "Jewel readme",
        baseMarkdown = NJPT_MARKDOWN_BASE,
        typedAddition = NJPT_TYPED_LINE,
        insertionMarker = NJPT_MARKDOWN_MARKER,
        interactiveId = "specimen-njpt-jewel-markdown-split",
        isInteractive = isInteractive,
        initialDarkTheme = false,
        modifier = modifier,
    )
}

// ============================================================================
// #22 — njpt.geminiDarkMarkdownChat (Not Just Plain Text S128, periodMs = 12_000)
// ============================================================================

private val NJPT_GEMINI_PROMPTS = listOf(
    "What's the equivalent of RecyclerView in Compose?",
    "How do I style inline code in Jewel Markdown?",
    "Show a Compose LazyColumn with sticky headers",
    "How do I test a Composable on desktop?",
)

private val NJPT_GEMINI_STREAMING_REPLY = """
In Jetpack Compose, the direct equivalent of `RecyclerView` is **`LazyColumn`** (for vertical scrolling lists) or **`LazyRow`** (for horizontal lists).

Unlike `RecyclerView`, you don't need an `Adapter`, `ViewHolder`, or XML layout files:

```kotlin
@Composable
fun MessageList(messages: List<String>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = messages, key = { it.hashCode() }) { text ->
            Text(text = text, modifier = Modifier.padding(12.dp))
        }
    }
}
```

### Why teams switch to LazyColumn
- **Declarative DSL:** Describe item types inline with `item {}` and `items(...)`.
- **Smart recomposition:** Stable `key` lambdas avoid unnecessary row redraws.
- **Built-in spacing:** Use `Arrangement.spacedBy(...)` instead of `ItemDecoration`.
""".trimIndent()

/**
 * Specimen #22 (`njpt.geminiDarkMarkdownChat`, NJPT S128, `periodMs = 12_000`):
 * Dark-theme (`IntUiTheme(isDark = true)`) Gemini in Android Studio chat window transitioning
 * from the dark `"Hello, S. Poggi"` welcome state into a live streaming Markdown + Kotlin
 * syntax-highlighted response rendered by [LiveJewelMarkdownView] with Copy/Insert actions.
 */
@Composable
fun NjptGeminiDarkMarkdownChatSpecimen(
    loopMillis: Long = 3_000L,
    periodMs: Long = 12_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var userSelectedPrompt by remember { mutableStateOf<String?>(null) }

    val t = loopProgress(loopMillis, periodMs)
    // 0.00..0.16: Dark welcome screen (prompt 0 highlights at 0.10..0.16)
    // 0.16..0.88: Live streaming Markdown + Kotlin code block (so Still mode at 25% = rich streaming reply!)
    // 0.88..1.00: Complete reply hold before clean loop restart
    val showConversation = userSelectedPrompt != null || t >= 0.16f
    val activeQuestion = userSelectedPrompt ?: NJPT_GEMINI_PROMPTS.first()
    val streamProgress = if (userSelectedPrompt != null) {
        1f
    } else {
        easeOutCubic(segmentProgress(t, 0.17f, 0.84f))
    }
    val streamedMarkdown = remember(streamProgress) {
        deterministicTypewriter(NJPT_GEMINI_STREAMING_REPLY, streamProgress)
    }
    val isStreaming = userSelectedPrompt == null && t in 0.17f..0.83f
    val scrollOffsetPx = if (userSelectedPrompt != null) {
        0f
    } else {
        easeInOutSine(segmentProgress(t, 0.68f, 0.92f)) * 78f
    }

    val darkSurface = Color(0xFF1E1F22)
    val darkBubbleBg = Color(0xFF2B2D30)
    val darkBubbleBorder = Color(0xFF3E4148)

    IsolatedJewelSurface(
        isDark = true,
        surfaceColor = darkSurface,
        borderColor = Color(0xFF393B40),
        interactiveId = "specimen-njpt-gemini-dark-chat",
        interactiveLabel = "Gemini Dark Markdown Chat",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GeminiToolWindowHeader(isDark = true)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            if (!showConversation) {
                // Dark welcome screen ("Hello, S. Poggi" / "How can I help?")
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val brush = Brush.linearGradient(
                            listOf(Color(0xFF6EA8FE), Color(0xFFB388FF), Color(0xFFFF80AB)),
                        )
                        BasicText(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(brush = brush, fontWeight = FontWeight.Medium)) {
                                    append("Hello, S. Poggi")
                                }
                            },
                            style = TextStyle(fontSize = 30.sp, lineHeight = 36.sp),
                        )
                        Text(
                            text = "How can I help?",
                            fontSize = 26.sp,
                            color = Color(0xFF7A7E85),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Here are some example prompts to try out:",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFCED0D6),
                        )
                        NJPT_GEMINI_PROMPTS.forEachIndexed { idx, prompt ->
                            GeminiPromptPill(
                                text = prompt,
                                isDark = true,
                                highlighted = idx == 0 && t >= 0.09f,
                                onClick = { if (isInteractive) userSelectedPrompt = prompt },
                            )
                        }
                    }

                    GeminiComposerCard(
                        text = "",
                        isDark = true,
                        showCaret = caretVisible(loopMillis),
                        showImageAttach = true,
                    )
                }
            } else {
                // Active dark conversation streaming rich Markdown + Kotlin code block
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .graphicsLayer { translationY = -scrollOffsetPx },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // User prompt bubble
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            CompositionLocalProvider(LocalEffectiveBackgroundColor provides Color(0xFF283A5C)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF283A5C))
                                        .border(1.dp, Color(0xFF4267B2), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 11.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = activeQuestion,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                    )
                                }
                            }
                        }

                        // Assistant streaming Markdown bubble
                        CompositionLocalProvider(LocalEffectiveBackgroundColor provides darkBubbleBg) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(darkBubbleBg)
                                    .border(1.dp, darkBubbleBorder, RoundedCornerShape(10.dp))
                                    .padding(11.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    GeminiSparkleMark(
                                        size = 16.dp,
                                        rotationDeg = if (isStreaming) t * 360f else 0f,
                                    )
                                    Text(
                                        text = "Gemini",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFDFE1E5),
                                    )
                                    if (isStreaming) {
                                        DeterministicIdeSpinner(
                                            loopMillis = loopMillis,
                                            size = 12.dp,
                                            color = Color(0xFF6EA8FE),
                                        )
                                        Text(
                                            text = "Streaming Markdown…",
                                            fontSize = 10.sp,
                                            color = Color(0xFF8AB4F8),
                                        )
                                    }
                                }

                                LiveJewelMarkdownView(
                                    markdown = if (streamedMarkdown.isNotEmpty()) streamedMarkdown else "Thinking…",
                                    isDark = true,
                                    baseFontSizeSp = 11f,
                                    showCodeActionBar = true,
                                    blockSpacing = 6.dp,
                                )
                            }
                        }
                    }
                }

                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GeminiComposerCard(
                        text = "",
                        isDark = true,
                        showCaret = !isStreaming && caretVisible(loopMillis),
                        showImageAttach = true,
                    )
                    Text(
                        text = "Rendered with Jewel Markdown AST • Dark IntUiTheme",
                        fontSize = 9.5.sp,
                        color = Color(0xFF7A7E85),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
