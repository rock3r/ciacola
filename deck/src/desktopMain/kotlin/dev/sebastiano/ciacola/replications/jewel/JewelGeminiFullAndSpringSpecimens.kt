package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

private val REFERENCE_LINKS_V1 = listOf(
    "https://developer.android.com/training/permissions/requesting",
    "https://stackoverflow.com/questions/63484094/how-to-get-permission-result-from-activity-in-fragment",
    "https://stackoverflow.com/questions/50546299/runtime-permission-requesting-permission-for-already-granted-permission",
    "https://stackoverflow.com/questions/53503355/how-should-i-share-activity-related-code-between-multiple-activities",
    "https://developer.android.com/jetpack/compose/layouts/basics",
)

private val REFERENCE_LINKS_V2 = listOf(
    "https://developer.android.com/studio/debug",
    "https://stackoverflow.com/questions/16929391/android-how-to-debug-apps-start-up-process",
    "https://stackoverflow.com/questions/2812442/debugging-an-android-app-to-the-phone",
    "https://developer.mozilla.org/en-US/blog/debug-mobile-apps-across-devices/",
    "https://kotlinlang.org/docs/kotlin-doc.html",
)

// ============================================================================
// #7 — jewel.referencesBouncyExpand (Jewel Talk S29–S30, periodMs = 3_500)
// ============================================================================

/**
 * Specimen #7 (`jewel.referencesBouncyExpand`, Jewel Talk S29–S30, `periodMs = 3_500`):
 * "References" disclosure accordion expanding & collapsing with a playful underdamped
 * spring curve (`dampingRatio = 0.45f`) revealing cited Android Developers & docs links.
 */
@Composable
fun ReferencesBouncyExpandSpecimen(
    loopMillis: Long = 875L,
    periodMs: Long = 3_500L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    ReferencesAccordionCore(
        loopMillis = loopMillis,
        periodMs = periodMs,
        dampingRatio = 0.45f,
        stiffness = 280f,
        leadBullets = listOf(
            "Request Code: Use a unique code per permission request.",
            "Error Handling: Handle denied permissions gracefully.",
            "User Experience: Explain why each permission is needed.",
        ),
        links = REFERENCE_LINKS_V1,
        headerCountLabel = "10",
        showSpringBadge = false,
        interactiveId = "specimen-references-bouncy-expand",
        isInteractive = isInteractive,
        modifier = modifier,
    )
}

// ============================================================================
// #19 — jewel.referencesBouncySpringV2 (Jewel Talk S57, periodMs = 2_200)
// ============================================================================

/**
 * Specimen #19 (`jewel.referencesBouncySpringV2`, Jewel Talk S57, `periodMs = 2_200`):
 * Tuned Material Expressive spring v2 (`dampingRatio = 0.60f`, `stiffness = 800f`)
 * comparison of the References disclosure accordion — snappier desktop settle vs v1!
 */
@Composable
fun ReferencesBouncySpringV2Specimen(
    loopMillis: Long = 550L,
    periodMs: Long = 2_200L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    ReferencesAccordionCore(
        loopMillis = loopMillis,
        periodMs = periodMs,
        dampingRatio = 0.60f,
        stiffness = 800f,
        leadBullets = listOf(
            "Debug your app | Android Developers",
            "IntelliJ IDEA debugging docs",
        ),
        links = REFERENCE_LINKS_V2,
        headerCountLabel = "5",
        showSpringBadge = true,
        interactiveId = "specimen-references-bouncy-spring-v2",
        isInteractive = isInteractive,
        modifier = modifier,
    )
}

@Composable
private fun ReferencesAccordionCore(
    loopMillis: Long,
    periodMs: Long,
    dampingRatio: Float,
    stiffness: Float,
    leadBullets: List<String>,
    links: List<String>,
    headerCountLabel: String,
    showSpringBadge: Boolean,
    interactiveId: String,
    isInteractive: Boolean,
    modifier: Modifier = Modifier,
) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    var useStandardSpring by remember { mutableStateOf(false) }

    val activeDamping = if (useStandardSpring) 1.0f else dampingRatio
    val activeStiffness = if (useStandardSpring) 700f else stiffness

    val t = loopProgress(loopMillis, periodMs)
    val periodSec = periodMs / 1000f
    // Open smoothly from t = 0.08..0.60, close from t = 0.60..0.95 (so t=0.25 is richly mid/post-overshoot!)
    val scriptedExpandFactor = when {
        t < 0.08f -> 0f
        t < 0.60f -> analyticalSpring((t - 0.08f) * periodSec, activeDamping, activeStiffness)
        else -> (1f - analyticalSpring((t - 0.60f) * periodSec, activeDamping, activeStiffness))
    }
    val expandFactor = userExpanded?.let { if (it) 1f else 0f } ?: scriptedExpandFactor
    val clampedVisible = expandFactor.coerceIn(0f, 1.24f)
    val isExpanded = expandFactor > 0.35f

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color.White,
        borderColor = Color(0xFFD5D8DF),
        interactiveId = interactiveId,
        interactiveLabel = "References Bouncy Spring",
        isInteractive = isInteractive,
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxHeight < 420.dp
            val titleSp = if (compact) 10.5.sp else 11.5.sp
            val bulletSp = if (compact) 9.5.sp else 10.5.sp
            val bulletLineSp = if (compact) 12.5.sp else 14.5.sp
            val linkSp = if (compact) 9.5.sp else 10.5.sp
            val linkRowStep = if (compact) 19f else 25f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (compact) 12.dp else 16.dp,
                        vertical = if (compact) 10.dp else 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
            ) {
                Text(
                    text = if (showSpringBadge) {
                        "For more advanced debugging scenarios and detailed information, refer to the official documentation:"
                    } else {
                        "Important Considerations:"
                    },
                    fontSize = titleSp,
                    fontWeight = if (showSpringBadge) FontWeight.Normal else FontWeight.SemiBold,
                    color = Color(0xFF1F2328),
                )
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
                    leadBullets.forEach { bullet ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("•", fontSize = bulletSp, color = Color(0xFF4C5058))
                            Text(
                                text = bullet,
                                fontSize = bulletSp,
                                lineHeight = bulletLineSp,
                                color = Color(0xFF27282E),
                                maxLines = if (compact) 2 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Bouncy References Card
                val cardBg = Color(0xFFF3F5F8)
                val cardShape = RoundedCornerShape(8.dp)
                CompositionLocalProvider(LocalEffectiveBackgroundColor provides cardBg) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(cardBg, cardShape)
                            .border(1.dp, Color(0xFFE1E4EB), cardShape)
                            .padding(
                                horizontal = if (compact) 10.dp else 11.dp,
                                vertical = if (compact) 7.dp else 11.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ReferenceLinkGlyph(color = Color(0xFF4C5058))
                                Text("References", fontSize = titleSp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2328))
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (showSpringBadge) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (useStandardSpring) Color(0xFFE0E2E7) else Color(0xFFE8DEF8))
                                            .clickable(enabled = isInteractive) { useStandardSpring = !useStandardSpring }
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (useStandardSpring) "Standard (1.0, 700)" else "Expressive (0.6, 800)",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (useStandardSpring) Color(0xFF3C4048) else Color(0xFF5B35B5),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable(enabled = isInteractive) {
                                            userExpanded = !(userExpanded ?: isExpanded)
                                        }
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = if (isExpanded) "Show less" else "Show all ($headerCountLabel)",
                                        fontSize = linkSp,
                                        color = Color(0xFF2470B3),
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = if (isExpanded) "⌃" else "⌄",
                                        fontSize = titleSp,
                                        color = Color(0xFF2470B3),
                                    )
                                }
                            }
                        }

                        // Always show first link; spring-expand remaining links with real overshoot height!
                        ReferenceLinkItem(links.first(), compact = compact)
                        val extraLinks = links.drop(1)
                        val extraHeightDp = (extraLinks.size * linkRowStep * clampedVisible).coerceAtLeast(0f).dp
                        if (extraHeightDp > 1.dp) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(extraHeightDp)
                                    .clipToBounds(),
                                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
                            ) {
                                extraLinks.forEachIndexed { idx, link ->
                                    val itemAlpha = ((clampedVisible * 1.3f) - idx * 0.14f).coerceIn(0f, 1f)
                                    Box(modifier = Modifier.alpha(itemAlpha)) {
                                        ReferenceLinkItem(link, compact = compact)
                                    }
                                }
                            }
                        }
                    }
                }

                // Feedback row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Helpful", fontSize = 9.5.sp, color = Color(0xFF6E737D))
                    Text("▲", fontSize = 9.sp, color = Color(0xFF6E737D))
                    Text("▼", fontSize = 9.sp, color = Color(0xFF6E737D))
                }
            }
        }
    }
}

@Composable
private fun ReferenceLinkGlyph(color: Color) {
    Canvas(modifier = Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round)
        rotate(degrees = -45f, pivot = Offset(cx, cy)) {
            val lh = h * 0.34f
            val lw = w * 0.44f
            val cr = CornerRadius(lh / 2f, lh / 2f)
            drawRoundRect(color, Offset(cx - w * 0.45f, cy - lh / 2f), Size(lw, lh), cr, style = stroke)
            drawRoundRect(color, Offset(cx + w * 0.01f, cy - lh / 2f), Size(lw, lh), cr, style = stroke)
            drawLine(color, Offset(cx - w * 0.15f, cy), Offset(cx + w * 0.15f, cy), strokeWidth = 1.4f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ReferenceLinkItem(url: String, compact: Boolean = false) {
    val sz = if (compact) 9.5.sp else 10.5.sp
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("•", fontSize = sz, color = Color(0xFF5A5E66))
        Text(
            text = url,
            fontSize = sz,
            color = Color(0xFF2470B3),
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ============================================================================
// #8 — jewel.geminiToolWindowFull (Jewel Talk S30, periodMs = 6_000)
// ============================================================================

private val TOOL_WINDOW_PROMPTS = listOf(
    "What is a composable?",
    "How do I handle the results of the permission request?",
    "Can you remind me what a KDoc for documenting Kotlin code looks like?",
    "How do I test an Android app?",
)

/**
 * Specimen #8 (`jewel.geminiToolWindowFull`, Jewel Talk S30, `periodMs = 6_000`):
 * Complete docked light-theme IntelliJ "Gemini [Preview]" tool window assembling the
 * header bar, gradient `"Hello, sebp"` / `"How can I help?"`, 4 interactive prompt pills,
 * expandable mini-snippet/references preview, bottom composer, and Google AI disclaimer!
 */
@Composable
fun GeminiToolWindowFullSpecimen(
    loopMillis: Long = 1_500L,
    periodMs: Long = 6_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var selectedPromptIdx by remember { mutableStateOf<Int?>(null) }
    val t = loopProgress(loopMillis, periodMs)
    val gradientShift = easeInOutSine(t)
    val activeChip = selectedPromptIdx ?: if (t in 0.42f..0.72f) 0 else -1
    val showMiniReply = selectedPromptIdx != null || t >= 0.62f

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF7F8FA),
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-gemini-tool-window-full",
        interactiveLabel = "Gemini Tool Window",
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Hero Greeting
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val greetBrush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF4285F4),
                            (0.45f + 0.15f * gradientShift) to Color(0xFF9B72CB),
                            1.0f to Color(0xFFD96570),
                        ),
                    )
                    BasicText(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(brush = greetBrush, fontWeight = FontWeight.Medium)) {
                                append("Hello, sebp")
                            }
                        },
                        style = TextStyle(fontSize = 26.sp, lineHeight = 31.sp),
                    )
                    Text(
                        text = "How can I help?",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF9AA0A6),
                    )
                }

                // Example prompts or compact live reply card
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = "Here are some example prompts to try out:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF27282E),
                    )
                    TOOL_WINDOW_PROMPTS.forEachIndexed { idx, prompt ->
                        GeminiPromptPill(
                            text = prompt,
                            isDark = false,
                            highlighted = idx == activeChip,
                            onClick = { if (isInteractive) selectedPromptIdx = if (selectedPromptIdx == idx) null else idx },
                        )
                    }

                    if (showMiniReply) {
                        val replyAlpha = if (selectedPromptIdx != null) 1f else easeOutCubic(segmentProgress(t, 0.62f, 0.74f))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(replyAlpha)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFDFE2E8), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        ) {
                            LiveJewelMarkdownView(
                                markdown = """
                                    A **Composable** function describes UI declaratively with `@Composable`:
                                    ```kotlin
                                    @Composable fun Greeting(name: String) = Text("Hello, ${'$'}name!")
                                    ```
                                """.trimIndent(),
                                isDark = false,
                                baseFontSizeSp = 10.5f,
                                blockSpacing = 5.dp,
                                showCodeActionBar = true,
                            )
                        }
                    }
                }

                // Bottom composer + disclaimer
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    GeminiComposerCard(
                        text = if (activeChip >= 0) TOOL_WINDOW_PROMPTS[activeChip] else "",
                        isDark = false,
                        showCaret = activeChip >= 0 && caretVisible(loopMillis),
                        showImageAttach = true,
                    )
                    BasicText(
                        text = buildAnnotatedString {
                            append("Responses may contain inaccurate or offensive information that don't represent Google's views. Please use code with caution. ")
                            withStyle(SpanStyle(color = Color(0xFF2470B3))) { append("Learn more") }
                        },
                        style = TextStyle(
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            color = Color(0xFF6E737D),
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ============================================================================
// #18 — jewel.geminiLoadingChoreography (Jewel Talk S57, periodMs = 2_400)
// ============================================================================

private val LOADING_PROMPTS = listOf(
    "What's the equivalent of RecyclerView in Compose?",
    "What is the latest version of WorkManager?",
    "Change to dark mode in Android Studio",
    "How do I test an Android app?",
)

/**
 * Specimen #18 (`jewel.geminiLoadingChoreography`, Jewel Talk S57, `periodMs = 2_400`):
 * Gemini expressive staggered entrance choreography — shimmering 4-point sparkle +
 * sweeping gradient reveal of `"Hello, csinco"` / `"How can I help?"` followed by
 * cascading prompt suggestion pills and bottom composer rise.
 */
@Composable
fun GeminiLoadingChoreographySpecimen(
    loopMillis: Long = 600L,
    periodMs: Long = 2_400L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val t = loopProgress(loopMillis, periodMs)
    val helloP = easeOutCubic(segmentProgress(t, 0.04f, 0.34f))
    val subP = easeOutCubic(segmentProgress(t, 0.14f, 0.42f))
    val promptHeaderP = easeOutCubic(segmentProgress(t, 0.22f, 0.44f))
    val composerP = easeOutCubic(segmentProgress(t, 0.16f, 0.48f))
    val shimmerSweep = segmentProgress(t, 0.0f, 0.90f)

    IsolatedJewelSurface(
        isDark = false,
        surfaceColor = Color(0xFFF3F5F8),
        borderColor = Color(0xFFD5D8DF),
        interactiveId = "specimen-gemini-loading-choreography",
        interactiveLabel = "Gemini Loading Choreography",
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
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Animated sparkle + sweeping gradient Hello, csinco / How can I help?
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GeminiSparkleMark(
                            size = 22.dp,
                            rotationDeg = shimmerSweep * 180f,
                            modifier = Modifier.graphicsLayer {
                                val pulse = 0.85f + 0.2f * easeInOutSine(t)
                                scaleX = pulse
                                scaleY = pulse
                            },
                        )
                        val revealBrush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0xFFD96570).copy(alpha = helloP),
                                (0.45f * helloP + 0.1f) to Color(0xFF9B72CB).copy(alpha = helloP),
                                (0.85f * helloP + 0.15f).coerceAtMost(1f) to Color(0xFF4285F4).copy(alpha = (helloP * 1.15f - 0.15f).coerceIn(0f, 1f)),
                            ),
                        )
                        BasicText(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(brush = revealBrush, fontWeight = FontWeight.Medium)) {
                                    append("Hello, csinco")
                                }
                            },
                            style = TextStyle(fontSize = 30.sp, lineHeight = 36.sp),
                            modifier = Modifier.graphicsLayer {
                                translationY = (1f - helloP) * 14f
                            },
                        )
                    }
                    Text(
                        text = "How can I help?",
                        fontSize = 28.sp,
                        color = Color(0xFF9AA0A6),
                        modifier = Modifier.graphicsLayer {
                            alpha = subP
                            translationY = (1f - subP) * 16f
                        },
                    )
                }

                // Shimmer skeleton morphing into staggered cascading prompt pills
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Here are some example prompts to try out:",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1F2328),
                        modifier = Modifier.alpha(promptHeaderP),
                    )
                    LOADING_PROMPTS.forEachIndexed { idx, prompt ->
                        val start = 0.24f + idx * 0.10f
                        val p = easeOutCubic(segmentProgress(t, start, start + 0.22f))
                        if (p < 0.22f) {
                            // Thinking shimmer skeleton bar before pill materializes
                            val shimmerColor = Color(0xFFDDE3EE).copy(alpha = 0.5f + 0.4f * easeInOutSine(t + idx * 0.2f))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.65f + (idx % 2) * 0.2f)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(shimmerColor),
                            )
                        } else {
                            GeminiPromptPill(
                                text = prompt,
                                isDark = false,
                                modifier = Modifier.graphicsLayer {
                                    alpha = p
                                    translationY = (1f - p) * 14f
                                },
                            )
                        }
                    }
                }

                // Bottom composer rising cleanly
                Column(
                    modifier = Modifier.graphicsLayer {
                        alpha = composerP
                        translationY = (1f - composerP) * 18f
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GeminiComposerCard(text = "", isDark = false, showImageAttach = true)
                    Text(
                        text = "Responses may contain inaccurate information. Please use code with caution.",
                        fontSize = 9.5.sp,
                        color = Color(0xFF6E737D),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
