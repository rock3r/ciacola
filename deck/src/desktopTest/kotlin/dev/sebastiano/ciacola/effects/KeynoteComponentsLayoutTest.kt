package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import dev.bnorm.storyboard.Agsl
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckMagicText
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.authoring.Specimen
import dev.sebastiano.ciacola.authoring.ciacolaDeck
import dev.sebastiano.ciacola.createEditableTextTransition
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.LocalDeckLoopClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class KeynoteComponentsLayoutTest {

    @Test
    fun dottedLeaderRowReservesLeadingWidthAndNeverWrapsIntoVerticalColumns() = runComposeUiTest {
        val rowWidthDp = 380f
        setContent {
            DeckThemeProvider(DeckThemes.keynoteLight()) {
                Box(Modifier.width(rowWidthDp.dp)) {
                    DottedLeaderRow(
                        leading = "Execution",
                        trailing = "VeryLongGpuFragmentPipelineStageDescriptor()",
                        leadingStyle = DeckThemes.keynoteLight().typography.cardBody,
                        trailingStyle = DeckThemes.keynoteLight().typography.codeCompact.copy(fontSize = 15.sp),
                        modifier = Modifier.testTag("narrow-leader"),
                    )
                }
            }
        }
        waitForIdle()

        val rowBounds = onNodeWithTag("narrow-leader").getUnclippedBoundsInRoot()
        val leadingBounds = onNodeWithText("Execution").getUnclippedBoundsInRoot()
        assertTrue(
            rowBounds.height.value <= 42f,
            "Expected single-line DottedLeaderRow without vertical wrap (height=${rowBounds.height})",
        )
        assertTrue(
            leadingBounds.width.value >= 60f && leadingBounds.height.value <= 34f,
            "Expected leading label 'Execution' to stay uncrushed on 1 line (bounds=$leadingBounds)",
        )
    }

    @Test
    fun magnifierLoupeUsesLocalEffectiveBackgroundColorInsteadOfThemeBackground() = runComposeUiTest {
        val customCanvas = Color(0xFF5BB397)
        val darkTheme = DeckThemes.stageDark()

        setContent {
            DeckThemeProvider(darkTheme) {
                CompositionLocalProvider(LocalEffectiveBackgroundColor provides customCanvas) {
                    Box(Modifier.size(520.dp, 280.dp).background(customCanvas)) {
                        MagnifierLoupeStage(
                            targetKey = "mint-target",
                            progress = { 1f },
                            modifier = Modifier.fillMaxSize(),
                            loupeAlignment = Alignment.CenterEnd,
                            loupeDiameter = 180.dp,
                            loupePadding = PaddingValues(end = 24.dp),
                        ) {
                            Box(Modifier.fillMaxSize().padding(start = 32.dp), contentAlignment = Alignment.CenterStart) {
                                Text(
                                    text = "SDF",
                                    color = Color(0xFF162520),
                                    modifier = Modifier.decorate("mint-target"),
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()

        val loupePixels = onNodeWithTag("ciacola-magnifier-loupe").captureToImage().toPixelMap()
        // Sample inside the lens near the top-center (well inside the ring, above the centered text).
        val sample = loupePixels[loupePixels.width / 2, loupePixels.height / 5]
        assertTrue(
            abs(sample.red - customCanvas.red) < 0.08f &&
                abs(sample.green - customCanvas.green) < 0.08f &&
                abs(sample.blue - customCanvas.blue) < 0.08f,
            "Expected loupe fill to match LocalEffectiveBackgroundColor ($customCanvas), got $sample",
        )
    }

    @Test
    fun outerTangentsArePerpendicularToLensRadiusAcrossAllDirections() {
        val pill = Rect(left = 100f, top = 200f, right = 280f, bottom = 240f)
        val pillRadius = 14f
        val lensRadius = 80f
        val lensPositions = listOf(
            Offset(520f, 220f), // right
            Offset(-120f, 220f), // left
            Offset(190f, 440f), // below
            Offset(190f, 10f), // above
            Offset(460f, 380f), // diagonal bottom-right
        )
        for (lensCenter in lensPositions) {
            val pair = computeLoupeOuterTangents(pill, pillRadius, lensCenter, lensRadius)
            assertNotNull(pair, "Expected outer tangent pair for lensCenter=$lensCenter")
            for (seg in listOf(pair.first, pair.second)) {
                val radiusVec = seg.lensPoint - lensCenter
                val rayVec = seg.lensPoint - seg.pillPoint
                val radiusLen = hypot(radiusVec.x, radiusVec.y)
                assertTrue(
                    abs(radiusLen - lensRadius) <= 0.5f,
                    "Tangent point must lie on lens circle (expected r=$lensRadius, got $radiusLen)",
                )
                val dot = radiusVec.x * rayVec.x + radiusVec.y * rayVec.y
                val rayLen = hypot(rayVec.x, rayVec.y).coerceAtLeast(1f)
                assertTrue(
                    abs(dot / (radiusLen * rayLen)) <= 0.02f,
                    "Tangent ray must be perpendicular to lens radius (cos=${dot / (radiusLen * rayLen)})",
                )
            }
        }
    }

    @Test
    fun fittedLoupeZoomPreventsTextClippingForWideTargets() {
        val target = Size(width = 240f, height = 24f)
        val usableRadius = 90f
        val padX = 8f
        val padY = 4f
        val effectiveZoom = computeFittedLoupeZoom(
            targetSize = target,
            requestedZoom = 3.2f,
            usableLensRadiusPx = usableRadius,
            padXPx = padX,
            padYPx = padY,
        )
        val fittedHalfDiag = hypot((target.width / 2f + padX) * effectiveZoom, (target.height / 2f + padY) * effectiveZoom)
        assertTrue(
            fittedHalfDiag <= usableRadius + 0.5f,
            "Fitted half-diagonal ($fittedHalfDiag) must stay within usable lens radius ($usableRadius)",
        )
    }

    @Test
    fun loupeLensBackgroundMatchesTargetSurfaceAndRaysClearAllStageAndSemanticsText() = runComposeUiTest {
        mainClock.autoAdvance = false
        val lightTheme = DeckThemes.keynoteLight()

        fun segmentIntersectsRect(a: Offset, b: Offset, rect: Rect): Boolean {
            // Shrink by 0.5px to ignore grazing floating-point touches outside glyph ink.
            val r = Rect(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f)
            if (r.width <= 0f || r.height <= 0f) return false
            var tMin = 0f
            var tMax = 1f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val p = floatArrayOf(-dx, dx, -dy, dy)
            val q = floatArrayOf(a.x - r.left, r.right - a.x, a.y - r.top, r.bottom - a.y)
            for (i in 0..3) {
                if (abs(p[i]) < 1e-6f) {
                    if (q[i] < 0f) return false
                } else {
                    val t = q[i] / p[i]
                    if (p[i] < 0f) {
                        if (t > tMax) return false
                        if (t > tMin) tMin = t
                    } else {
                        if (t < tMin) return false
                        if (t < tMax) tMax = t
                    }
                }
            }
            return tMin <= tMax
        }

        fun verifyDeckLoupeClearance(
            deckLabel: String,
            deck: Storyboard,
            expectedLensFill: Color,
            checks: List<suspend (Animatic) -> String>,
        ) {
            val registry = StageInspectionRegistry()
            val frozenClock = DeckLoopClock().apply { scrubbedMillis = 0L }
            var latestGeometry: LoupeStageGeometrySnapshot? = null
            lateinit var animatic: Animatic
            lateinit var scope: CoroutineScope

            setContent {
                scope = rememberCoroutineScope()
                CompositionLocalProvider(
                    LocalAmbientLoopsPaused provides true,
                    LocalDeckLoopClock provides frozenClock,
                    LocalStageInspectionRegistry provides registry,
                    LocalLoupeGeometryObserver provides { snap -> latestGeometry = snap },
                ) {
                    Box(Modifier.size(960.dp, 540.dp).testTag("loupe-stage")) {
                        animatic = rememberAnimatic(storyboard = { deck })
                        Easel(animatic)
                    }
                }
            }
            repeat(5) { mainClock.advanceTimeByFrame() }

            for (step in checks) {
                latestGeometry = null
                lateinit var job: Job
                lateinit var stepLabel: String
                runOnIdle {
                    job = scope.launch { stepLabel = step(animatic) }
                }
                var guard = 0
                while (!job.isCompleted && guard++ < 600) mainClock.advanceTimeByFrame()
                assertTrue(job.isCompleted, "$deckLabel: navigation step did not complete")
                repeat(4) { mainClock.advanceTimeByFrame() }
                onNodeWithTag("loupe-stage").captureToImage()

                val geom = assertNotNull(latestGeometry, "$deckLabel ($stepLabel): expected active LoupeStageGeometrySnapshot")
                assertEquals(
                    expectedLensFill,
                    geom.resolvedLensBackground,
                    "$deckLabel ($stepLabel): lens fill must match target's enclosing card surface",
                )
                val tangents = assertNotNull(
                    computeLoupeOuterTangents(
                        pillRect = geom.pillRectInStage,
                        pillRadius = geom.pillRadius,
                        lensCenter = geom.lensCenterInStage,
                        lensRadius = geom.lensRadius,
                    ),
                    "$deckLabel ($stepLabel): expected outer tangent pair",
                )
                val rays = listOf("upper/neg" to tangents.first, "lower/pos" to tangents.second)

                // 1. Check all StageInspectionRegistry text nodes on Slide 2 (excluding target pill text inside pillRect).
                val inspectedTextNodes = registry.nodes.values.filter { snap ->
                    snap.sceneIndex == 1 &&
                        snap.resolvedText.isNotBlank() &&
                        !geom.pillRectInStage.inflate(4f).contains(snap.stageBounds.center)
                }
                assertTrue(inspectedTextNodes.isNotEmpty(), "$deckLabel ($stepLabel): expected registered text nodes on Slide 2")
                for (node in inspectedTextNodes) {
                    for ((rayName, seg) in rays) {
                        assertTrue(
                            !segmentIntersectsRect(seg.pillPoint, seg.lensPoint, node.stageBounds),
                            "$deckLabel ($stepLabel): $rayName ray (${seg.pillPoint} -> ${seg.lensPoint}) intersected " +
                                "registered text '${node.elementId}' ('${node.resolvedText}') at ${node.stageBounds}",
                        )
                    }
                }

                // 2. Also check all unmerged semantics Text nodes (catches specimen window titlebar & body lines).
                val hasAnyText = SemanticsMatcher("hasText") { SemanticsProperties.Text in it.config }
                val semanticsTextNodes = onAllNodes(hasAnyText, useUnmergedTree = true).fetchSemanticsNodes()
                for (semNode in semanticsTextNodes) {
                    val txt = semNode.config[SemanticsProperties.Text].joinToString("") { it.text }
                    val b = semNode.boundsInRoot
                    if (txt.isBlank() || b.width <= 1f || b.height <= 1f) continue
                    if (geom.pillRectInStage.inflate(6f).contains(b.center)) continue
                    for ((rayName, seg) in rays) {
                        assertTrue(
                            !segmentIntersectsRect(seg.pillPoint, seg.lensPoint, b),
                            "$deckLabel ($stepLabel): $rayName ray (${seg.pillPoint} -> ${seg.lensPoint}) intersected " +
                                "semantics text '$txt' at $b",
                        )
                    }
                }
            }
        }

        val inlineCodeLoupeDeck = ciacolaDeck(
            id = "inline-code-loupe-fixture",
            title = "Inline Code Loupe Fixture",
            host = DeckHostParams(
                theme = lightTheme,
                bundledPreset = DeckThemePreset.KeynoteLight,
            ),
        ) {
            slide(key = "intro", title = "Intro", beats = 1) {
                val theme = LocalDeckTheme.current
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    DeckEditableText(
                        elementId = "loupe:white:intro",
                        text = "Opening Slide",
                        style = theme.typography.title,
                        color = theme.colors.foreground,
                    )
                }
            }
            slide(key = "inline-code-loupe", title = "Inline Code Rendering", beats = 3) {
                val theme = LocalDeckTheme.current
                val loupeProgress by animateByBeat("loupe-white-open", theme.motion.loupeDurationMs) {
                    if (it <= 1) 1f else 0f
                }
                val expandFraction by animateByBeat("loupe-white-expand") { if (it >= 2) 1f else 0f }
                val rawTransition = transition.createEditableTextTransition("loupe:white:raw", "loupe-white-raw") { step ->
                    when (step) {
                        0 -> AnnotatedString("Use `appendInlineContent` for rounded pills")
                        else -> AnnotatedString("Inline `Placeholder` boxes do not soft-wrap across lines")
                    }
                }
                val noteTransition = transition.createEditableTextTransition("loupe:white:note", "loupe-white-note") { step ->
                    when (step) {
                        0 -> AnnotatedString("Custom inline spans reserve fixed glyph boxes in paragraph layout.")
                        else -> AnnotatedString("Long inline code spans stay atomic inside the specimen card.")
                    }
                }

                Box(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
                    MagnifierLoupeStage(
                        targetKey = "loupe-white-target",
                        progress = { loupeProgress },
                        loupeAlignment = Alignment.BottomEnd,
                        loupeDiameter = 216.dp,
                        loupePadding = PaddingValues(end = 10.dp, bottom = 0.dp),
                        zoom = 1.75f,
                        lensBackgroundColor = Color.White,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DeckSurfaceCard(
                                elementId = "loupe:white:raw-card",
                                label = "Raw Snippet Card",
                                cornerRadius = 10.dp,
                                contentPadding = 12.dp,
                                modifier = Modifier.fillMaxWidth(0.96f),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("RAW INPUT", style = theme.typography.eyebrow, color = theme.colors.muted)
                                    DeckMagicText(
                                        transition = rawTransition,
                                        style = theme.typography.code.copy(
                                            fontSize = 17.sp,
                                            lineHeight = 23.sp,
                                            color = theme.colors.foreground,
                                        ),
                                        elementId = "loupe:white:raw",
                                        label = "Raw Inline Code",
                                        kind = "Code",
                                    )
                                }
                            }
                            DeckMagicText(
                                transition = noteTransition,
                                style = theme.typography.callout.copy(
                                    fontSize = 17.sp,
                                    lineHeight = 22.sp,
                                    color = theme.colors.muted,
                                ),
                                elementId = "loupe:white:note",
                                label = "Inline Code Takeaway",
                                modifier = Modifier.fillMaxWidth(0.96f),
                            )
                            Specimen(
                                id = "loupe:white:specimen",
                                label = "Inline Code Specimen",
                                modifier = Modifier.fillMaxWidth(0.60f + 0.36f * expandFraction),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Inline Code Preview",
                                        style = theme.typography.caption,
                                        color = Color(0xFF4B5563),
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = "Render pill via",
                                            style = theme.typography.cardBody,
                                            color = Color(0xFF111827),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFEEF2FF))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                                .decorate("loupe-white-target"),
                                        ) {
                                            Text(
                                                text = "appendInline",
                                                style = theme.typography.codeCompact.copy(
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                                color = Color(0xFF3730A3),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        verifyDeckLoupeClearance(
            deckLabel = "White Card Loupe",
            deck = inlineCodeLoupeDeck,
            expectedLensFill = Color.White,
            checks = listOf(
                { a -> a.jumpTo(Storyboard.Index(1, 0)); "S2:B1" },
                { a -> a.jumpTo(Storyboard.Index(1, 1)); "S2:B2" },
            ),
        )
    }

    @Test
    fun loupeRaysDoNotIntersectAnyTextBoundsAcrossMidOpenAndSettledBeat() = runComposeUiTest {
        mainClock.autoAdvance = false
        val lightTheme = DeckThemes.keynoteLight()
        val sdfPreviewSksl = """
            uniform float2 iResolution;
            uniform float uStage;
            uniform float radius;

            half4 main(float2 fragCoord) {
                vec2 uv = (fragCoord - 0.5 * iResolution) / max(min(iResolution.x, iResolution.y), 1.0);
                float aa = 1.5 / max(min(iResolution.x, iResolution.y), 1.0);
                float d = length(uv) - clamp(radius, 0.05, 0.8);
                float mask = mix(clamp(d * 2.2, 0.0, 1.0), smoothstep(aa, -aa, d), clamp(uStage, 0.0, 1.0));
                return half4(vec3(mask), 1.0);
            }
        """.trimIndent()

        val loupeShaderDeck = ciacolaDeck(
            id = "sdf-loupe-fixture",
            title = "SDF Loupe Fixture",
            host = DeckHostParams(
                theme = lightTheme,
                bundledPreset = DeckThemePreset.KeynoteLight,
            ),
        ) {
            slide(key = "sdf-loupe", title = "Signed Distance Fields", beats = 3) {
                val activeTheme = LocalDeckTheme.current
                val warpStep = rememberBeatTransition("loupe-sdf-warp")
                val sdfStage by animateByBeat("loupe-sdf-stage") { it.toFloat() }
                val loupeProgress by animateByBeat("loupe-sdf-open", activeTheme.motion.loupeDurationMs) {
                    if (it == 1) 1f else 0f
                }
                val titleTransition = transition.createEditableTextTransition("loupe:sdf:title", "loupe-sdf-title") { step ->
                    when (step) {
                        0 -> AnnotatedString("Distance from center creates a signed field")
                        1 -> AnnotatedString("SDF — threshold distance for crisp circles")
                        else -> AnnotatedString("Boolean CSG: subtract with max, union with min")
                    }
                }
                val snippets = remember {
                    listOf(
                        "float d = length(uv) - radius;\nvec3 col = vec3(d);\nreturn half4(col, 1.0);",
                        "float d = length(uv) - radius;\nfloat mask = smoothstep(aa, -aa, d);\nreturn half4(vec3(mask), 1.0);",
                        "float d1 = length(uv) - radius * 1.18;\nfloat d2 = length(uv) - radius * 0.82;\nreturn half4(vec3(step(max(d1, -d2), 0.0)), 1.0);",
                    )
                }

                Box(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
                    MagnifierLoupeStage(
                        targetKey = "loupe-sdf-formula",
                        progress = { loupeProgress },
                        modifier = Modifier.fillMaxSize(),
                        loupeAlignment = Alignment.BottomEnd,
                        loupeDiameter = 228.dp,
                        loupePadding = PaddingValues(end = 278.dp, bottom = 0.dp),
                        zoom = 1.65f,
                        lensBackgroundColor = activeTheme.colors.surface,
                    ) {
                        Column(Modifier.fillMaxSize(), Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth().height(40.dp)) {
                                DeckMagicText(
                                    transition = titleTransition,
                                    elementId = "loupe:sdf:title",
                                    label = "SDF Headline",
                                    style = activeTheme.typography.heading.copy(
                                        fontSize = 31.sp,
                                        lineHeight = 36.sp,
                                        color = activeTheme.colors.foreground,
                                    ),
                                )
                            }
                            Row(Modifier.weight(1f).fillMaxWidth(), Arrangement.spacedBy(26.dp), Alignment.Top) {
                                Column(Modifier.weight(1.42f), Arrangement.spacedBy(12.dp)) {
                                    DeckWarpCodeCard(
                                        codeStates = snippets,
                                        stateTransition = warpStep,
                                        language = Agsl,
                                        textStyle = activeTheme.typography.codeCompact.copy(fontSize = 16.sp, lineHeight = 23.sp),
                                        elementId = "loupe:sdf:code",
                                        highlightedLines = { step -> if (step < 2) setOf(0) else setOf(1) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        DeckEditableText(
                                            elementId = "loupe:sdf:swizzle-note",
                                            text = "Swizzles: xyzw · rgba · stuv",
                                            style = activeTheme.typography.caption,
                                            color = activeTheme.colors.muted,
                                        )
                                        DeckSurfaceCard(
                                            elementId = "loupe:sdf:formula-pill",
                                            label = "SDF Circle Equation Pill",
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                        ) {
                                            DeckEditableText(
                                                elementId = "loupe:sdf:formula-text",
                                                text = "float d = length(uv) - radius;",
                                                style = activeTheme.typography.codeCompact.copy(
                                                    fontSize = 14.sp,
                                                    lineHeight = 18.sp,
                                                    letterSpacing = (-0.5).sp,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                                color = activeTheme.colors.accent,
                                                modifier = Modifier.decorate("loupe-sdf-formula"),
                                            )
                                        }
                                    }
                                }
                                Column(Modifier.width(256.dp), Arrangement.spacedBy(10.dp), Alignment.CenterHorizontally) {
                                    DeckSkslShader(
                                        id = "loupe:sdf:shader",
                                        sksl = sdfPreviewSksl,
                                        label = "Live SDF Shader",
                                        modifier = Modifier.size(252.dp),
                                    ) {
                                        float("uStage", sdfStage)
                                        uniformFloat("radius", default = 0.35f, range = 0.05f..0.8f)
                                    }
                                    DeckEditableText(
                                        "loupe:sdf:preview-caption",
                                        byBeat("Raw distance gradient", "Anti-aliased SDF circle", "CSG boolean ring"),
                                        style = activeTheme.typography.caption,
                                        color = activeTheme.colors.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val registry = StageInspectionRegistry()
        val frozenClock = DeckLoopClock().apply { scrubbedMillis = 0L }
        var latestGeometry: LoupeStageGeometrySnapshot? = null
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope

        fun segmentIntersectsRect(a: Offset, b: Offset, rect: Rect): Boolean {
            val r = Rect(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f)
            if (r.width <= 0f || r.height <= 0f) return false
            var tMin = 0f
            var tMax = 1f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val p = floatArrayOf(-dx, dx, -dy, dy)
            val q = floatArrayOf(a.x - r.left, r.right - a.x, a.y - r.top, r.bottom - a.y)
            for (i in 0..3) {
                if (abs(p[i]) < 1e-6f) {
                    if (q[i] < 0f) return false
                } else {
                    val t = q[i] / p[i]
                    if (p[i] < 0f) {
                        if (t > tMax) return false
                        if (t > tMin) tMin = t
                    } else {
                        if (t < tMin) return false
                        if (t < tMax) tMax = t
                    }
                }
            }
            return tMin <= tMax
        }

        setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalAmbientLoopsPaused provides true,
                LocalDeckLoopClock provides frozenClock,
                LocalStageInspectionRegistry provides registry,
                LocalLoupeGeometryObserver provides { snap -> latestGeometry = snap },
            ) {
                Box(Modifier.size(960.dp, 540.dp).testTag("loupe-sdf-stage")) {
                    animatic = rememberAnimatic(storyboard = { loupeShaderDeck })
                    Easel(animatic)
                }
            }
        }
        repeat(5) { mainClock.advanceTimeByFrame() }

        val s1Beat0State = animatic.stateDescriptors.first {
            it.sceneIndex == 0 && it.frameIndex == 0 && !it.isBoundaryStart && !it.isBoundaryEnd
        }.stateIndex

        val checkpoints: List<Pair<String, suspend () -> Unit>> = listOf(
            "S1:B1->B2@50%" to { animatic.seekToSegment(s1Beat0State, 0.5f, rebuildScene = true) },
            "S1:B2-settled" to { animatic.jumpTo(Storyboard.Index(0, 1)) },
        )

        for ((label, action) in checkpoints) {
            latestGeometry = null
            lateinit var job: Job
            runOnIdle { job = scope.launch { action() } }
            var guard = 0
            while (!job.isCompleted && guard++ < 600) mainClock.advanceTimeByFrame()
            assertTrue(job.isCompleted)
            repeat(4) { mainClock.advanceTimeByFrame() }
            onNodeWithTag("loupe-sdf-stage").captureToImage()

            val geom = assertNotNull(latestGeometry, "SDF Loupe ($label): expected active LoupeStageGeometrySnapshot")
            assertEquals(
                lightTheme.colors.surface,
                geom.resolvedLensBackground,
                "SDF Loupe ($label): lens fill must match formula pill card surface",
            )
            val tangents = assertNotNull(
                computeLoupeOuterTangents(
                    pillRect = geom.pillRectInStage,
                    pillRadius = geom.pillRadius,
                    lensCenter = geom.lensCenterInStage,
                    lensRadius = geom.lensRadius,
                ),
            )
            val rays = listOf("upper/neg" to tangents.first, "lower/pos" to tangents.second)
            val textNodes = registry.nodes.values.filter { snap ->
                snap.sceneIndex == 0 &&
                    snap.resolvedText.isNotBlank() &&
                    snap.elementId != "loupe:sdf:formula-text"
            }
            assertTrue(textNodes.any { it.elementId == "loupe:sdf:swizzle-note" }, "Expected loupe:sdf:swizzle-note registered")
            for (node in textNodes) {
                for ((rayName, seg) in rays) {
                    assertTrue(
                        !segmentIntersectsRect(seg.pillPoint, seg.lensPoint, node.stageBounds),
                        "SDF Loupe ($label): $rayName ray (${seg.pillPoint} -> ${seg.lensPoint}) intersected " +
                            "'${node.elementId}' ('${node.resolvedText}') at ${node.stageBounds}",
                    )
                }
            }
        }
    }
}
