package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ---------------------------------------------------------------------------
// Shared Gemini in Android Studio Subcomponents (Light & Dark aware)
// ---------------------------------------------------------------------------

internal val GEMINI_EXAMPLE_PROMPTS = listOf(
    "How do I handle the results of the permission request?",
    "How to choose minSdkVersion?",
    "Create a Map in Kotlin",
    "What is the latest version of WorkManager?",
)

@Composable
internal fun GeminiToolWindowHeader(
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (isDark) Color(0xFF1E1F22) else Color(0xFFF7F8FA)
    val titleColor = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2328)
    val badgeBg = if (isDark) Color(0xFF3E2D6B) else Color(0xFFE9DEFF)
    val badgeFg = if (isDark) Color(0xFFCBB6FF) else Color(0xFF5B35B5)
    val iconColor = if (isDark) Color(0xFF9DA0A8) else Color(0xFF6E737D)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Gemini", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = titleColor)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeBg)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text("Preview", fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = badgeFg)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AllIconsKeys.General.Delete, contentDescription = "Clear", modifier = Modifier.size(13.dp), tint = iconColor)
            Icon(AllIconsKeys.General.Ellipsis, contentDescription = "More", modifier = Modifier.size(13.dp), tint = iconColor)
            Icon(AllIconsKeys.General.Remove, contentDescription = "Minimize", modifier = Modifier.size(13.dp), tint = iconColor)
        }
    }
}

@Composable
internal fun GeminiPromptPill(
    text: String,
    isDark: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val bg = when {
        highlighted && isDark -> Color(0xFF283A5C)
        highlighted -> Color(0xFFE7F0FF)
        isDark -> Color(0xFF23252A)
        else -> Color(0xFFFFFFFF)
    }
    val border = when {
        highlighted -> Color(0xFF3574F0)
        isDark -> Color(0xFF3E68B8)
        else -> Color(0xFF6B9CF5)
    }
    val fg = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2328)
    val arrowColor = if (isDark) Color(0xFF8AB4F8) else Color(0xFF3574F0)
    val shape = RoundedCornerShape(16.dp)

    CompositionLocalProvider(LocalEffectiveBackgroundColor provides bg) {
        Row(
            modifier = modifier
                .clip(shape)
                .background(bg, shape)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                color = fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text("→", fontSize = 11.sp, color = arrowColor, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun GeminiComposerCard(
    text: String,
    isDark: Boolean,
    showCaret: Boolean = false,
    showImageAttach: Boolean = true,
    attachHighlighted: Boolean = false,
    contextPill: String? = null,
    tokenCounterText: String? = null,
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val boxBg = if (isDark) Color(0xFF1E1F22) else Color.White
    val border = if (text.isNotEmpty()) Color(0xFF3574F0) else if (isDark) Color(0xFF43454A) else Color(0xFFC9CCD6)
    val placeholderFg = if (isDark) Color(0xFF6F737A) else Color(0xFF7B808A)
    val bodyFg = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2328)
    val iconFg = if (isDark) Color(0xFF8AB4F8) else Color(0xFF3574F0)
    val mutedIcon = if (isDark) Color(0xFF868A91) else Color(0xFF7B808A)
    val shape = RoundedCornerShape(8.dp)

    CompositionLocalProvider(LocalEffectiveBackgroundColor provides boxBg) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(boxBg, shape)
                .border(1.dp, border, shape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (contextPill != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isDark) Color(0xFF283A5C) else Color(0xFFE7F0FF))
                            .border(1.dp, Color(0xFF3574F0).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            contextPill,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isDark) Color(0xFFA8C7FA) else Color(0xFF1D4ED8),
                        )
                        Text("×", fontSize = 10.sp, color = mutedIcon)
                    }
                    if (tokenCounterText != null) {
                        Text(tokenCounterText, fontSize = 9.5.sp, color = mutedIcon, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            val caret = if (showCaret) "│" else ""
            BasicText(
                text = if (text.isEmpty()) "What would you like to do?" else text + caret,
                style = TextStyle(
                    color = if (text.isEmpty()) placeholderFg else bodyFg,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = if (text.contains("val ")) FontFamily.Monospace else FontFamily.Default,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showImageAttach) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (attachHighlighted) {
                                        if (isDark) Color(0xFF2B3E63) else Color(0xFFE4EEFF)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .padding(4.dp),
                        ) {
                            Icon(
                                AllIconsKeys.General.Add,
                                contentDescription = "Attach image or file",
                                modifier = Modifier.size(13.dp),
                                tint = iconFg,
                            )
                        }
                    }
                    Icon(
                        AllIconsKeys.General.History,
                        contentDescription = "Prompt history",
                        modifier = Modifier.size(13.dp),
                        tint = mutedIcon,
                    )
                }

                if (text.isNotEmpty()) {
                    DefaultButton(onClick = onSubmit) { Text("Submit", fontSize = 11.sp) }
                } else {
                    OutlinedButton(onClick = {}, enabled = false) { Text("Submit", fontSize = 11.sp) }
                }
            }
        }
    }
}

// ============================================================================
// #3 — jewel.geminiAuthCard (Jewel Talk S25–S30, periodMs = 4_000)
// ============================================================================

/**
 * Specimen #3 (`jewel.geminiAuthCard`, Jewel Talk S25–S30, `periodMs = 4_000`):
 * Gemini in Android Studio welcome/authorization onboarding card with 4-point Gemini sparkle,
 * blue-outlined Jewel card, spring-animated `Authorize Gemini` -> `✓ Authorized!` transition,
 * and purple preview disclaimer banner.
 */
@Composable
fun GeminiAuthCardSpecimen(
    loopMillis: Long = 1_000L,
    periodMs: Long = 4_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var manualAuthorized by remember { mutableStateOf<Boolean?>(null) }
    val t = loopProgress(loopMillis, periodMs)
    val isAuthorized = manualAuthorized ?: (t >= 0.36f)
    val checkAnim = if (manualAuthorized != null) {
        if (manualAuthorized == true) 1f else 0f
    } else {
        val elapsed = ((t - 0.36f).coerceAtLeast(0f) * (periodMs / 1000f))
        if (t >= 0.36f) analyticalSpring(elapsed, dampingRatio = 0.58f, stiffness = 520f).coerceIn(0f, 1.15f) else 0f
    }

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF7F8FA),
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-gemini-auth-card",
        interactiveLabel = "Gemini Authorization Card",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxHeight < 350.dp
            val bodySp = if (compact) 10.5.sp else 12.5.sp
            val bodyLineSp = if (compact) 14.sp else 17.5.sp
            val btnSp = if (compact) 10.5.sp else 12.sp
            val bannerSp = if (compact) 9.5.sp else 11.5.sp
            val bannerLineSp = if (compact) 13.sp else 15.5.sp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (compact) 14.dp else 24.dp,
                        vertical = if (compact) 8.dp else 18.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GeminiSparkleMark(
                    size = if (compact) 24.dp else 42.dp,
                    rotationDeg = if (isAuthorized) 12f * easeOutCubic(segmentProgress(t, 0.36f, 0.52f)) else 0f,
                    brush = Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFF1A73E8))),
                )
                Spacer(Modifier.height(if (compact) 6.dp else 14.dp))

                // Main blue-outlined authorization card
                val cardShape = RoundedCornerShape(8.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(Color.White, cardShape)
                        .border(1.2.dp, Color(0xFF4D88F4), cardShape)
                        .padding(
                            horizontal = if (compact) 11.dp else 14.dp,
                            vertical = if (compact) 8.dp else 14.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp),
                ) {
                    BasicText(
                        text = buildAnnotatedString {
                            append("Gemini in Android Studio is an AI coding companion that helps you build high quality apps faster. ")
                            withStyle(SpanStyle(color = Color(0xFF2470B3))) { append("Learn more") }
                        },
                        style = TextStyle(fontSize = bodySp, lineHeight = bodyLineSp, color = Color(0xFF1F2328)),
                    )
                    Text(
                        text = "To get started, first authorize Gemini.",
                        fontSize = bodySp,
                        color = Color(0xFF1F2328),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                    ) {
                        if (!isAuthorized) {
                            val hovering = t in 0.18f..0.36f
                            if (hovering) {
                                DefaultButton(onClick = { if (isInteractive) manualAuthorized = true }) {
                                    Text("Authorize Gemini", fontSize = btnSp, maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedButton(onClick = { if (isInteractive) manualAuthorized = true }) {
                                    Text("Authorize Gemini", fontSize = btnSp, maxLines = 1, softWrap = false)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { if (isInteractive) manualAuthorized = false },
                                enabled = false,
                            ) {
                                Text("Authorize Gemini", fontSize = btnSp, maxLines = 1, softWrap = false)
                            }
                            Row(
                                modifier = Modifier
                                    .graphicsLayer {
                                        val s = 0.72f + 0.28f * checkAnim
                                        scaleX = s
                                        scaleY = s
                                        alpha = checkAnim.coerceIn(0f, 1f)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (compact) 14.dp else 16.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF34A853)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("✓", fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Text(
                                    text = "Authorized!",
                                    fontSize = bodySp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1F2328),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

                // Purple preview notice banner
                val bannerShape = RoundedCornerShape(6.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(bannerShape)
                        .background(Color(0xFFF3ECFF), bannerShape)
                        .border(1.dp, Color(0xFFA073F4), bannerShape)
                        .padding(
                            horizontal = if (compact) 10.dp else 12.dp,
                            vertical = if (compact) 6.dp else 9.dp,
                        ),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                ) {
                    Icon(
                        AllIconsKeys.General.BalloonInformation,
                        contentDescription = "Preview notice",
                        modifier = Modifier.size(if (compact) 12.dp else 14.dp).padding(top = 1.dp),
                        tint = Color(0xFF7B48E2),
                    )
                    BasicText(
                        text = buildAnnotatedString {
                            append("Gemini in Android Studio is offered at no cost while in preview and is currently available in ")
                            withStyle(SpanStyle(color = Color(0xFF2470B3))) { append("select countries") }
                            append(".")
                        },
                        style = TextStyle(fontSize = bannerSp, lineHeight = bannerLineSp, color = Color(0xFF2B2738)),
                    )
                }
            }
        }
    }
}

// ============================================================================
// #4 — jewel.geminiPromptInput (Jewel Talk S26–S30, periodMs = 12_000)
// ============================================================================

private const val GEMINI_TYPED_PROMPT = "Please explain how this code works:\n\nval name = \"Paul\""

/**
 * Specimen #4 (`jewel.geminiPromptInput`, Jewel Talk S26–S30, `periodMs = 12_000`):
 * Gemini prompt suggestions pill list + live character-by-character composer typing
 * a Kotlin snippet with Jewel `DefaultButton("Submit")`.
 */
@Composable
fun GeminiPromptInputSpecimen(
    loopMillis: Long = 3_000L,
    periodMs: Long = 12_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var clickedPrompt by remember { mutableStateOf<String?>(null) }
    val t = loopProgress(loopMillis, periodMs)
    val highlightedChipIdx = if (t in 0.05f..0.18f) 0 else -1
    val typingProgress = segmentProgress(t, 0.14f, 0.82f)
    val activeText = clickedPrompt ?: deterministicTypewriter(GEMINI_TYPED_PROMPT, typingProgress)

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF7F8FA),
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-gemini-prompt-input",
        interactiveLabel = "Gemini Prompt Input",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "Here are some example prompts to try out:",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF27282E),
                )
                GEMINI_EXAMPLE_PROMPTS.forEachIndexed { idx, prompt ->
                    GeminiPromptPill(
                        text = prompt,
                        isDark = false,
                        highlighted = idx == highlightedChipIdx || clickedPrompt == prompt,
                        onClick = { if (isInteractive) clickedPrompt = prompt },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                GeminiComposerCard(
                    text = activeText,
                    isDark = false,
                    showCaret = caretVisible(loopMillis),
                    showImageAttach = false,
                    onSubmit = { if (isInteractive) clickedPrompt = null },
                )
            }
        }
    }
}

// ============================================================================
// #5 — jewel.geminiStreamingChat (Jewel Talk S27–S30, periodMs = 10_000)
// ============================================================================

private val GEMINI_STREAMING_ANSWER = """
Okay, let's create a layout with three columns in Compose.

You can achieve this using `LazyRow` and `Column` composables along with the `weight` modifier to distribute available space evenly:

```kotlin
LazyRow {
    items(3) { columnIndex ->
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(8.dp)
        ) {
            Text("Column ${'$'}{columnIndex + 1}")
        }
    }
}
```

**How it works:**
1. `LazyRow`: Arranges its children horizontally in a lazy fashion.
2. `items(3)`: Creates three items within the `LazyRow`, one for each column.
3. `Modifier.weight(1f)`: Assigns equal weight so columns share space evenly.
""".trimIndent()

/**
 * Specimen #5 (`jewel.geminiStreamingChat`, Jewel Talk S27–S30, `periodMs = 10_000`):
 * Live streaming Gemini response bubble rendering Markdown prose + syntax-highlighted Kotlin
 * `LazyRow` code card with Copy / Insert action buttons, driven deterministically by `loopMillis`.
 */
@Composable
fun GeminiStreamingChatSpecimen(
    loopMillis: Long = 2_500L,
    periodMs: Long = 10_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val t = loopProgress(loopMillis, periodMs)
    val streamProgress = easeOutCubic(segmentProgress(t, 0.04f, 0.84f))
    val minVisibleChars = 110
    val charCount = (minVisibleChars + (GEMINI_STREAMING_ANSWER.length - minVisibleChars) * streamProgress)
        .toInt()
        .coerceIn(minVisibleChars, GEMINI_STREAMING_ANSWER.length)
    val streamedMarkdown = remember(charCount) {
        val raw = GEMINI_STREAMING_ANSWER.substring(0, charCount)
        // Close an in-progress fenced code block cleanly so AST renders live Kotlin card mid-stream
        val fenceCount = Regex("```").findAll(raw).count()
        if (fenceCount % 2 == 1) "$raw\n```" else raw
    }

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color.White,
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-gemini-streaming-chat",
        interactiveLabel = "Gemini Streaming Chat",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // User bubble row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF5E43A6)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("SP", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(
                    text = "How do I create a layout with three columns in Compose? I need the three columns to contain lists of items — thank you!",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Color(0xFF1F2328),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            // Gemini response row
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                GeminiSparkleMark(size = 18.dp, modifier = Modifier.padding(top = 2.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    LiveJewelMarkdownView(
                        markdown = streamedMarkdown,
                        isDark = false,
                        baseFontSizeSp = 10.5f,
                        showCodeActionBar = true,
                        blockSpacing = 6.dp,
                    )
                    if (streamProgress >= 0.85f) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("👍", fontSize = 11.sp, color = Color(0xFF6E737D))
                            Text("👎", fontSize = 11.sp, color = Color(0xFF6E737D))
                            Spacer(Modifier.width(4.dp))
                            Text("References (3)", fontSize = 10.5.sp, color = Color(0xFF2470B3))
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// #6 — jewel.geminiChatComposer (Jewel Talk S28–S30, periodMs = 8_000)
// ============================================================================

private const val COMPOSER_SCRIPTED_TEXT = "Explain how @MainActivity.kt wires IntUiTheme into JewelComposePanel"

/**
 * Specimen #6 (`jewel.geminiChatComposer`, Jewel Talk S28–S30, `periodMs = 8_000`):
 * Compact multi-line Jewel chat composer featuring image/file attachment micro-interaction,
 * `@file` context pill popup, token counter, and animated Submit button activation.
 */
@Composable
fun GeminiChatComposerSpecimen(
    loopMillis: Long = 2_000L,
    periodMs: Long = 8_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var manualAttach by remember { mutableStateOf<Boolean?>(null) }
    val t = loopProgress(loopMillis, periodMs)
    val attachActive = manualAttach ?: (t in 0.14f..0.88f)
    val showPopup = t in 0.14f..0.52f && manualAttach == null
    val typedText = deterministicTypewriter(COMPOSER_SCRIPTED_TEXT, segmentProgress(t, 0.16f, 0.78f))
    val tokenCount = (typedText.length / 4 + if (attachActive) 142 else 0)

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF7F8FA),
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-gemini-chat-composer",
        interactiveLabel = "Gemini Chat Composer",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GeminiToolWindowHeader(isDark = false)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Subtle recent conversation turn providing realistic context above the composer
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GeminiSparkleMark(size = 14.dp)
                        Text(
                            text = "Context-aware composer",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1F2328),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFE3E5EC), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = "Type @ to attach project Kotlin files or paste a screenshot. Gemini automatically tracks context window tokens before sending.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFF57606A),
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Context attachment popup tooltip/picker floating above composer
                    val popupAlpha = if (showPopup) easeOutCubic(segmentProgress(t, 0.14f, 0.22f)) else 0f
                    if (popupAlpha > 0.01f) {
                        Row(
                            modifier = Modifier
                                .alpha(popupAlpha)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFC9CCD6), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Attach context:", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4C5058))
                            Text("@MainActivity.kt", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF2470B3))
                            Text("@Theme.kt", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF6E737D))
                            Text("screenshot.png", fontSize = 10.5.sp, color = Color(0xFF6E737D))
                        }
                    }

                    GeminiComposerCard(
                        text = typedText,
                        isDark = false,
                        showCaret = caretVisible(loopMillis),
                        showImageAttach = true,
                        attachHighlighted = attachActive,
                        contextPill = if (attachActive) "@MainActivity.kt" else null,
                        tokenCounterText = if (attachActive) "$tokenCount / 32k tokens" else null,
                        onSubmit = { if (isInteractive) manualAttach = !(manualAttach ?: false) },
                    )
                }
            }
        }
    }
}
