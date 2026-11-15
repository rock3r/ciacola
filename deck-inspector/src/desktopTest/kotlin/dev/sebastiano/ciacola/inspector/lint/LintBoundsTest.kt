package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class LintBoundsTest {

    @Test
    fun centeredSubtitleOnScaledFixedSizeStageMapsAccuratelyInside1920x1080() {
        var stageRootCoords: LayoutCoordinates? = null
        // Viewport is 768x432 (0.4x of 1920x1080), mirroring a live Studio preview pane where
        // FixedSize scales a 1920x1080 Density(2f) stage down to fit the viewport.
        val scene = ImageComposeScene(
            width = 768,
            height = 432,
            density = Density(1f),
        ) {
            Box(Modifier.fillMaxSize()) {
                TestScaledStage(size = IntSize(1920, 1080), density = Density(2f)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { stageRootCoords = it },
                        color = Color(0xFF11131A),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Writing fast, expressive shaders directly in Compose",
                                style = TextStyle(
                                    fontSize = 33.sp,
                                    color = Color(0xFFF5F7FA),
                                    textAlign = TextAlign.Center,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        try {
            scene.render(0L)
            val rootCoords = requireNotNull(stageRootCoords) { "Expected stage root coordinates to be captured" }
            val candidates = SemanticsSlideScanner.scanOwners(
                owners = scene.semanticsOwners,
                pixelBuffer = null,
                explicitStageRootCoordinates = rootCoords,
                stageWidth = STAGE_WIDTH_PX,
                stageHeight = STAGE_HEIGHT_PX,
                fallbackBackground = Color(0xFF11131A),
            )
            val subtitle = candidates.firstOrNull { it.text.contains("expressive shaders") }
            requireNotNull(subtitle) { "Expected centered subtitle semantics node, found: $candidates" }

            // Must lie cleanly inside 1920x1080 and be centered around x = 960 (never x = 1929!).
            assertTrue(
                subtitle.stageBounds.left >= 0f && subtitle.stageBounds.right <= STAGE_WIDTH_PX,
                "Expected subtitle bounds inside [0, 1920], got ${subtitle.stageBounds}",
            )
            assertTrue(
                abs(subtitle.stageBounds.center.x - STAGE_WIDTH_PX / 2f) < 40f,
                "Expected subtitle horizontally centered near 960, got center=${subtitle.stageBounds.center}",
            )

            val issues = DeckLintRules.evaluateSlide(
                LintSlideInput(
                    sceneIndex = 0,
                    beatIndex = 0,
                    textNodes = candidates,
                ),
            )
            assertTrue(
                issues.none { it.ruleId == LintRuleId.STAGE_OUT_OF_BOUNDS },
                "Centered 33sp subtitle must not trigger STAGE_OUT_OF_BOUNDS, got: $issues",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun magicTextSnapshotCollapsesChildWordSemanticsIntoOneCandidate() {
        val headlineSnapshot = StageNodeSnapshot(
            elementId = "s1:headline",
            kind = "MagicText",
            label = "Headline",
            sceneIndex = 0,
            beatIndex = 0,
            stageBounds = Rect(120f, 180f, 1320f, 280f),
            resolvedText = "Compose makes UI motion effortless",
            effectiveForeground = Color.White,
            effectiveBackground = Color(0xFF11131A),
            effectiveFontSizeSp = 48f,
        )
        val wordSemantics = listOf(
            "Compose" to Rect(120f, 184f, 340f, 272f),
            "makes" to Rect(356f, 184f, 520f, 272f),
            "UI" to Rect(536f, 184f, 600f, 272f),
            "motion" to Rect(616f, 184f, 820f, 272f),
            "effortless" to Rect(836f, 184f, 1120f, 272f),
        ).map { (word, rect) ->
            LintTextNodeCandidate(
                elementId = null,
                label = word,
                text = word,
                stageBounds = rect,
                unclippedStageBounds = rect,
                effectiveForeground = Color.White,
                opaqueForeground = Color.White,
                effectiveBackground = Color(0xFF11131A),
                fontSizeSp = 48f,
            )
        }

        val slideInput = DeckLintRules.buildSlideInput(
            sceneIndex = 0,
            beatIndex = 0,
            stageSnapshots = listOf(headlineSnapshot),
            semanticsTextNodes = wordSemantics,
        )
        assertEquals(
            1,
            slideInput.textNodes.size,
            "MagicText headline snapshot + 5 child word semantics must merge into 1 candidate, got: ${slideInput.textNodes}",
        )
        assertEquals("s1:headline", slideInput.textNodes.single().elementId)
    }

    @Test
    fun largeTextContrastThresholdAccountsForGlobalFontScale() {
        // #636B78 on #11131A has WCAG contrast ~3.5:1 (fails 4.5:1 normal text, passes 3.0:1 large text).
        val mutedText = Color(0xFF636B78)
        val darkBg = Color(0xFF11131A)
        val baseCandidate = LintTextNodeCandidate(
            elementId = "s1:subhead",
            label = "Scaled Subhead",
            text = "Scaled Subhead",
            stageBounds = Rect(120f, 200f, 700f, 260f),
            unclippedStageBounds = Rect(120f, 200f, 700f, 260f),
            effectiveForeground = mutedText,
            opaqueForeground = mutedText,
            effectiveBackground = darkBg,
            fontSizeSp = 18f, // 18sp normal (< 24sp), but 18 * 1.4 = 25.2sp (>= 24sp large text!)
            isBold = false,
        )

        val unscaledIssues = DeckLintRules.evaluateSlide(
            LintSlideInput(
                sceneIndex = 0,
                beatIndex = 0,
                fontScale = 1.0f,
                textNodes = listOf(baseCandidate.copy(fontScale = 1.0f)),
            ),
        )
        assertTrue(
            unscaledIssues.any { it.ruleId == LintRuleId.LOW_CONTRAST },
            "At 1.0x scale, 18sp text with ~3.5:1 contrast must fail 4.5:1 normal-text AA, got: $unscaledIssues",
        )

        val scaledIssues = DeckLintRules.evaluateSlide(
            LintSlideInput(
                sceneIndex = 0,
                beatIndex = 0,
                fontScale = 1.4f,
                textNodes = listOf(baseCandidate.copy(fontScale = 1.4f)),
            ),
        )
        assertTrue(
            scaledIssues.none { it.ruleId == LintRuleId.LOW_CONTRAST },
            "At 1.4x global font scale, 18sp (25.2sp effective) text with ~3.5:1 contrast must pass 3.0:1 large-text AA, got: $scaledIssues",
        )
    }
}

@Composable
private fun TestScaledStage(
    size: IntSize,
    density: Density,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            CompositionLocalProvider(LocalDensity provides density) {
                Box { content() }
            }
        },
    ) { measurables, constraints ->
        val scale = minOf(
            constraints.maxWidth.toFloat() / size.width,
            constraints.maxHeight.toFloat() / size.height,
        )
        val layoutW = (size.width * scale).toInt()
        val layoutH = (size.height * scale).toInt()
        val placeable = measurables[0].measure(Constraints.fixed(size.width, size.height))
        layout(layoutW, layoutH) {
            placeable.placeWithLayer((layoutW - size.width) / 2, (layoutH - size.height) / 2) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}
