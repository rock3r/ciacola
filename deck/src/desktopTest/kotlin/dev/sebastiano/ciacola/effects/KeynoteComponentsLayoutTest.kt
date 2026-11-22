package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
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
            Offset(520f, 220f),
            Offset(-120f, 220f),
            Offset(190f, 440f),
            Offset(190f, 10f),
            Offset(460f, 380f),
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
    fun documentLoupeStagePublishesValidTangentGeometryAcrossMidOpenAndSettledBeats() = runComposeUiTest {
        mainClock.autoAdvance = false
        val lightTheme = DeckThemes.keynoteLight()
        val registry = StageInspectionRegistry()
        var latestGeometry: LoupeStageGeometrySnapshot? = null
        val deck = buildFoundationTestStoryboard(themeName = "light")
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope

        setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalStageInspectionRegistry provides registry,
                LocalLoupeGeometryObserver provides { snap -> latestGeometry = snap },
            ) {
                Box(Modifier.size(960.dp, 540.dp).testTag("loupe-doc-stage")) {
                    animatic = rememberAnimatic(
                        initialIndex = Storyboard.Index(4, 3),
                        storyboard = { deck },
                    )
                    Easel(animatic)
                }
            }
        }
        repeat(5) { mainClock.advanceTimeByFrame() }

        val s5Beat3State = animatic.stateDescriptors.first {
            it.sceneIndex == 4 && it.frameIndex == 3 && !it.isBoundaryStart && !it.isBoundaryEnd
        }.stateIndex

        val checkpoints: List<Pair<String, suspend () -> Unit>> = listOf(
            "S5:B3->B4@50%" to { animatic.seekToSegment(s5Beat3State, 0.5f, rebuildScene = true) },
            "S5:B4-settled" to { animatic.jumpTo(Storyboard.Index(4, 4)) },
        )

        for ((label, action) in checkpoints) {
            latestGeometry = null
            lateinit var job: Job
            runOnIdle { job = scope.launch { action() } }
            var guard = 0
            while (!job.isCompleted && guard++ < 600) mainClock.advanceTimeByFrame()
            assertTrue(job.isCompleted)
            repeat(4) { mainClock.advanceTimeByFrame() }
            onNodeWithTag("loupe-doc-stage").captureToImage()

            val geom = assertNotNull(latestGeometry, "Foundation Loupe ($label): expected active LoupeStageGeometrySnapshot")
            assertEquals(
                lightTheme.colors.background,
                geom.resolvedLensBackground,
                "Foundation Loupe ($label): lens fill must match slide background",
            )
            val tangents = assertNotNull(
                computeLoupeOuterTangents(
                    pillRect = geom.pillRectInStage,
                    pillRadius = geom.pillRadius,
                    lensCenter = geom.lensCenterInStage,
                    lensRadius = geom.lensRadius,
                ),
                "Foundation Loupe ($label): expected outer tangent pair",
            )
            assertTrue(tangents.first.lensPoint != tangents.second.lensPoint)
        }
    }
}
