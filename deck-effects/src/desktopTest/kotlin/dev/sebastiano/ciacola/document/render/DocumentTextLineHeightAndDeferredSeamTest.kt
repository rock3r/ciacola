package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.text.v2.DeckTextRendererV2
import dev.sebastiano.ciacola.text.v2.MagicTextV2Config
import dev.sebastiano.ciacola.text.v2.MagicTextV2Renderer
import dev.sebastiano.ciacola.text.v2.TextContentV2
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DocumentTextLineHeightAndDeferredSeamTest {

    @Test
    fun relativeMultiplierLineHeightProducesNonOverlappingMultiLineBounds() = runComposeUiTest {
        val theme = DeckThemes.stageDark()
        val doc = DeckDocument(id = "lh-multiplier", title = "LineHeight Multiplier")
        val colorResolver = DocumentColorResolver(themeSpec = null, baseTheme = theme, slidePaletteSpec = null)
        val payload = TextPayload(
            value = RichTextValue.Plain("First large headline line\nSecond large headline line"),
            size = 120.0,
            lineHeight = 1.25,
        )

        setContent {
            Box(Modifier.size(1920.dp, 1080.dp)) {
                DocumentTextPayloadView(
                    renderState = { DocumentTextRenderState.Rest(payload = payload) },
                    surface = null,
                    document = doc,
                    theme = theme,
                    colorResolver = colorResolver,
                    fontResolverHandle = null,
                    density = Density(1f),
                    reducedMotion = false,
                    deckId = doc.id,
                    assetsRootDir = null,
                    maxWidthPx = 1800,
                    modifier = Modifier.testTag("lh-multiplier-node"),
                )
            }
        }
        waitForIdle()

        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("lh-multiplier-node")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        val tlr = layouts.single()
        assertEquals(2, tlr.lineCount, "Expected two explicit lines")
        val line0Bottom = tlr.getLineBottom(0)
        val line1Top = tlr.getLineTop(1)
        val baselineDelta = tlr.getLineBaseline(1) - tlr.getLineBaseline(0)
        assertTrue(
            line1Top >= line0Bottom - 1f,
            "Relative lineHeight=1.25 at size=120 must not overlap lines (line0Bottom=$line0Bottom, line1Top=$line1Top)",
        )
        assertTrue(
            baselineDelta >= 135f,
            "Expected baseline separation ~150px for 120 * 1.25, got $baselineDelta",
        )
    }

    @Test
    fun explicitLargeSizeWithNullLineHeightClearsInheritedBodyLineHeightAndPreventsOverlap() = runComposeUiTest {
        val theme = DeckThemes.stageDark() // body.lineHeight == 40.sp!
        val doc = DeckDocument(id = "lh-unspecified", title = "LineHeight Unspecified")
        val colorResolver = DocumentColorResolver(themeSpec = null, baseTheme = theme, slidePaletteSpec = null)
        val payload = TextPayload(
            value = RichTextValue.Plain("HERO ONE\nHERO TWO"),
            size = 180.0,
            lineHeight = null,
        )

        val styled = buildStyledTextV2(
            payload = payload,
            theme = theme,
            themeTypography = emptyMap(),
            colorResolver = colorResolver,
            fontResolver = null,
            density = Density(1f),
            maxWidthPx = 1850,
            disableLigatures = false,
        )
        assertEquals(
            TextUnit.Unspecified,
            styled.style.lineHeight,
            "When payload.size is set and payload.lineHeight is null, lineHeight must reset to TextUnit.Unspecified",
        )

        setContent {
            Box(Modifier.size(1920.dp, 1080.dp)) {
                DocumentTextPayloadView(
                    renderState = { DocumentTextRenderState.Rest(payload = payload) },
                    surface = null,
                    document = doc,
                    theme = theme,
                    colorResolver = colorResolver,
                    fontResolverHandle = null,
                    density = Density(1f),
                    reducedMotion = false,
                    deckId = doc.id,
                    assetsRootDir = null,
                    maxWidthPx = 1850,
                    modifier = Modifier.testTag("lh-unspecified-node"),
                )
            }
        }
        waitForIdle()

        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("lh-unspecified-node")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        val tlr = layouts.single()
        assertEquals(2, tlr.lineCount, "Expected two explicit lines")
        val line0Bottom = tlr.getLineBottom(0)
        val line1Top = tlr.getLineTop(1)
        val baselineDelta = tlr.getLineBaseline(1) - tlr.getLineBaseline(0)
        assertTrue(
            line1Top >= line0Bottom - 1f,
            "Large size=180 headline with null lineHeight must never crush lines onto inherited 40.sp (line0Bottom=$line0Bottom, line1Top=$line1Top)",
        )
        assertTrue(
            baselineDelta >= 170f,
            "Expected baseline separation >= 170px for size=180 headline, got $baselineDelta",
        )
    }

    @Test
    fun deferredTextPayloadSeamAnimatesMorphFramesWithoutRecomposingTextNode() = runComposeUiTest {
        val theme = DeckThemes.stageDark()
        val doc = DeckDocument(id = "deferred-seam", title = "Deferred Seam")
        val colorResolver = DocumentColorResolver(themeSpec = null, baseTheme = theme, slidePaletteSpec = null)
        val fromPayload = TextPayload(value = RichTextValue.Plain("Alpha Beta Gamma"), size = 60.0)
        val toPayload = TextPayload(value = RichTextValue.Plain("Alpha Delta Gamma"), size = 60.0)

        var textNodeCompositions = 0
        val delegate = MagicTextV2Renderer()
        val countingRenderer = object : DeckTextRendererV2 by delegate {
            @Composable
            override fun TextNode(
                content: () -> TextContentV2,
                modifier: Modifier,
                config: MagicTextV2Config,
            ) {
                textNodeCompositions++
                delegate.TextNode(content = content, modifier = modifier, config = config)
            }
        }

        val elapsed = mutableFloatStateOf(0f)
        setContent {
            DeckTextRendererProvider(countingRenderer) {
                // Re-evaluate DocumentTextPayloadView across frames while keeping stationary modifier:
                val ms = elapsed.floatValue
                val state = DocumentTextRenderState.Morph(
                    from = fromPayload,
                    to = toPayload,
                    by = MorphBy.Word,
                    pruneStopwords = true,
                    schedule = null,
                    totalDurationMs = 500,
                    elapsedMs = ms,
                    moveCurve = CurveV2.EaseInOut,
                    interpolateLayoutSize = false,
                )
                Box(Modifier.size(1920.dp, 1080.dp)) {
                    DocumentTextPayloadView(
                        renderState = { state },
                        surface = null,
                        document = doc,
                        theme = theme,
                        colorResolver = colorResolver,
                        fontResolverHandle = null,
                        density = Density(1f),
                        reducedMotion = false,
                        deckId = doc.id,
                        assetsRootDir = null,
                        maxWidthPx = 1600,
                        modifier = Modifier.testTag("deferred-morph-node"),
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(1, textNodeCompositions, "Initial mount must compose TextNode once")

        for (step in listOf(50f, 150f, 250f, 350f, 450f, 500f)) {
            runOnIdle { elapsed.floatValue = step }
            waitForIdle()
        }
        assertEquals(
            1,
            textNodeCompositions,
            "Animated morph frames must pass through the deferred () -> TextContentV2 seam without recomposing TextNode",
        )
    }

    @Test
    fun fontResolverHandleIsSharedAcrossMultipleSlideCompositionsOfSameDeck() = runComposeUiTest {
        val docA = DeckDocument(
            id = "shared-fonts-a",
            fonts = listOf(
                DeckFontDeclaration(
                    family = "Inter",
                    src = "fonts/InterVariable.ttf",
                    variable = true,
                ),
            ),
        )
        val docB = docA.copy(id = "shared-fonts-b")

        var handle1: Any? = null
        var handle2: Any? = null
        setContent {
            Box {
                handle1 = rememberDocumentFontResolverHandle(docA, fontsDir = null)
            }
            Box {
                handle2 = rememberDocumentFontResolverHandle(docB, fontsDir = null)
            }
        }
        waitForIdle()
        assertNotNull(handle1)
        assertSame(
            handle1,
            handle2,
            "Multiple slide scenes (Overview 2D Grid / Presenter / Linter) with identical font declarations must share one DeckFontResolver instance",
        )
    }
}
