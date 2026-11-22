package dev.sebastiano.ciacola.authoring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.ConnectorAnchor
import dev.sebastiano.ciacola.effects.ConnectorStyle
import dev.sebastiano.ciacola.effects.EditableBox
import dev.sebastiano.ciacola.effects.ElementConnector
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.StageAnchorRegistry
import dev.sebastiano.ciacola.effects.resolveConnectorEndpoints
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ElementConnectorTest {

    private val connectorColor = Color(0xFFFF00FF)

    @Test
    fun resolveConnectorEndpointsSelectsFacingEdgesAndRespectsGapAndRtl() {
        val left = Rect(10f, 50f, 110f, 100f)
        val right = Rect(210f, 50f, 310f, 100f)

        val autoHoriz = resolveConnectorEndpoints(left, right)
        assertEquals(110f, autoHoriz.start.x, 0.01f)
        assertEquals(75f, autoHoriz.start.y, 0.01f)
        assertEquals(210f, autoHoriz.end.x, 0.01f)
        assertEquals(75f, autoHoriz.end.y, 0.01f)

        val withGap = resolveConnectorEndpoints(left, right, gapPx = 8f)
        assertEquals(118f, withGap.start.x, 0.01f)
        assertEquals(202f, withGap.end.x, 0.01f)

        val top = Rect(50f, 20f, 150f, 70f)
        val bottom = Rect(50f, 170f, 150f, 220f)
        val autoVert = resolveConnectorEndpoints(top, bottom)
        assertEquals(100f, autoVert.start.x, 0.01f)
        assertEquals(70f, autoVert.start.y, 0.01f)
        assertEquals(100f, autoVert.end.x, 0.01f)
        assertEquals(170f, autoVert.end.y, 0.01f)

        val rtlExplicit = resolveConnectorEndpoints(
            fromRect = left,
            toRect = right,
            fromAnchor = ConnectorAnchor.Start,
            toAnchor = ConnectorAnchor.End,
            layoutDirection = LayoutDirection.Rtl,
        )
        assertEquals(110f, rtlExplicit.start.x, 0.01f)
        assertEquals(210f, rtlExplicit.end.x, 0.01f)
    }

    @Test
    fun renderedEndpointsTrackElementBoundsShiftAndProgressZero() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        val anchorRegistry = StageAnchorRegistry()
        val rootHolder = StageRootCoordinatesHolder()
        var shiftXDp by mutableFloatStateOf(0f)
        var progress by mutableFloatStateOf(1f)
        var densityPxPerDp = 1f

        setContent {
            densityPxPerDp = LocalDensity.current.density
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    LocalStageInspectionRegistry provides registry,
                    LocalStageAnchorRegistry provides anchorRegistry,
                    LocalStageRootCoordinates provides rootHolder,
                ) {
                    Box(
                        modifier = Modifier
                            .size(400.dp, 200.dp)
                            .background(Color(0xFF0A0C12))
                            .testTag("connector-stage")
                            .onPlaced { rootHolder.coordinates = it },
                    ) {
                        EditableBox(
                            elementId = "node-a",
                            defaultFill = Color(0xFF1F2A40),
                            modifier = Modifier.offset(x = 20.dp, y = 70.dp).size(80.dp, 40.dp),
                        ) {}
                        EditableBox(
                            elementId = "node-b",
                            defaultFill = Color(0xFF1F2A40),
                            modifier = Modifier.offset(x = (200f + shiftXDp).dp, y = 70.dp).size(80.dp, 40.dp),
                        ) {}
                        ElementConnector(
                            fromId = "node-a",
                            toId = "node-b",
                            style = ConnectorStyle.Line,
                            color = connectorColor,
                            strokeWidth = 3.dp,
                            progress = { progress },
                        )
                    }
                }
            }
        }

        waitForIdle()
        val rectA = assertNotNull(registry.liveStageBoundsOf("node-a"), "Expected node-a bounds in registry")
        val rectB = assertNotNull(registry.liveStageBoundsOf("node-b"), "Expected node-b bounds in registry")

        val initialExtent = findInkHorizontalBounds(onNodeWithTag("connector-stage").captureToImage().toPixelMap())
        assertNotNull(initialExtent, "Expected connector pixels at progress = 1f")
        assertTrue(
            abs(initialExtent.first - rectA.right) <= 2f,
            "Start x=${initialExtent.first} should be within 2px of node-a right=${rectA.right}",
        )
        assertTrue(
            abs(initialExtent.second - rectB.left) <= 2f,
            "End x=${initialExtent.second} should be within 2px of node-b left=${rectB.left}",
        )

        shiftXDp = 40f
        waitForIdle()
        val expectedShiftPx = 40f * densityPxPerDp
        val shiftedExtent = findInkHorizontalBounds(onNodeWithTag("connector-stage").captureToImage().toPixelMap())
        assertNotNull(shiftedExtent, "Expected connector pixels after shifting node-b")
        val actualShiftPx = shiftedExtent.second - initialExtent.second
        assertTrue(
            abs(actualShiftPx - expectedShiftPx) <= 2f,
            "Expected endpoint shift of ${expectedShiftPx}px (+40dp), got ${actualShiftPx}px",
        )

        progress = 0f
        waitForIdle()
        val hiddenExtent = findInkHorizontalBounds(onNodeWithTag("connector-stage").captureToImage().toPixelMap())
        assertEquals(null, hiddenExtent, "Expected zero connector pixels at progress = 0f")
    }

    @Test
    fun allConnectorStylesRenderCleanly() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        val anchorRegistry = StageAnchorRegistry()
        val rootHolder = StageRootCoordinatesHolder()
        var style by mutableStateOf(ConnectorStyle.Arrow)

        setContent {
            val activeStyle = style
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    LocalStageInspectionRegistry provides registry,
                    LocalStageAnchorRegistry provides anchorRegistry,
                    LocalStageRootCoordinates provides rootHolder,
                ) {
                    Box(
                        modifier = Modifier
                            .size(360.dp, 220.dp)
                            .background(Color(0xFF0A0C12))
                            .testTag("style-stage")
                            .onPlaced { rootHolder.coordinates = it },
                    ) {
                        EditableBox(
                            elementId = "src",
                            defaultFill = Color(0xFF1F2A40),
                            modifier = Modifier.offset(x = 20.dp, y = 30.dp).size(80.dp, 40.dp),
                        ) {}
                        EditableBox(
                            elementId = "dst",
                            defaultFill = Color(0xFF1F2A40),
                            modifier = Modifier.offset(x = 220.dp, y = 130.dp).size(80.dp, 40.dp),
                        ) {}
                        ElementConnector(
                            fromId = "src",
                            toId = "dst",
                            fromAnchor = ConnectorAnchor.End,
                            toAnchor = ConnectorAnchor.Start,
                            style = activeStyle,
                            color = connectorColor,
                            strokeWidth = 3.dp,
                            progress = { 1f },
                        )
                    }
                }
            }
        }

        for (candidate in ConnectorStyle.entries) {
            style = candidate
            waitForIdle()
            val extent = findInkHorizontalBounds(onNodeWithTag("style-stage").captureToImage().toPixelMap())
            assertNotNull(extent, "Expected rendered connector pixels for style=$candidate")
        }
    }

    @Test
    fun connectorRendersUnderOverviewOpenWithoutStageInspectionRegistry() = runComposeUiTest {
        val anchorRegistry = StageAnchorRegistry()
        var densityPxPerDp = 1f

        setContent {
            densityPxPerDp = LocalDensity.current.density
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    dev.bnorm.storyboard.easel.LocalOverviewOpen provides true,
                    LocalStageInspectionRegistry provides null,
                    LocalStageAnchorRegistry provides anchorRegistry,
                ) {
                    Box(
                        modifier = Modifier
                            .size(360.dp, 200.dp)
                            .background(Color(0xFF0A0C12))
                            .testTag("overview-connector-stage"),
                    ) {
                        EditableBox(
                            elementId = "ov-a",
                            defaultFill = Color(0xFF1F2A40),
                            modifier = Modifier.offset(x = 24.dp, y = 70.dp).size(80.dp, 40.dp),
                        ) {}
                        EditableBox(
                            elementId = "ov-b",
                            defaultFill = Color(0xFF1F2A40),
                            modifier = Modifier.offset(x = 216.dp, y = 70.dp).size(80.dp, 40.dp),
                        ) {}
                        ElementConnector(
                            fromId = "ov-a",
                            toId = "ov-b",
                            style = ConnectorStyle.Arrow,
                            color = connectorColor,
                            strokeWidth = 3.dp,
                            progress = { 1f },
                        )
                    }
                }
            }
        }

        waitForIdle()
        val extent = findInkHorizontalBounds(onNodeWithTag("overview-connector-stage").captureToImage().toPixelMap())
        assertNotNull(extent, "Expected connector pixels under Overview without StageInspectionRegistry")
        assertTrue(
            abs(extent.first - 104f * densityPxPerDp) <= 3f,
            "Expected start near 104dp (${104f * densityPxPerDp}px), got ${extent.first}",
        )
        assertTrue(
            abs(extent.second - 216f * densityPxPerDp) <= 3f,
            "Expected end near 216dp (${216f * densityPxPerDp}px), got ${extent.second}",
        )
    }

    private fun findInkHorizontalBounds(pixelMap: PixelMap): Pair<Int, Int>? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for (y in 0 until pixelMap.height) {
            for (x in 0 until pixelMap.width) {
                val c = pixelMap[x, y]
                if (c.red > 0.7f && c.blue > 0.7f && c.green < 0.25f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        return if (minX <= maxX) minX to maxX else null
    }
}
