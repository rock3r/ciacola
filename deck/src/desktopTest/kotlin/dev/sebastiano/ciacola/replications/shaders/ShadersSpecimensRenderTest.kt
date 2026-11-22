package dev.sebastiano.ciacola.replications.shaders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.RuntimeEffect

class ShadersSpecimensRenderTest {

    private companion object {
        const val WIDTH: Int = 800
        const val HEIGHT: Int = 600
        const val DUMP_DIR_PROP: String = "ciacola.dumpShadersPreviewDir"
    }

    private data class RenderedFrame(
        val width: Int,
        val height: Int,
        val rgbaBytes: ByteArray,
    ) {
        fun rgbAt(x: Int, y: Int): Triple<Int, Int, Int> {
            val idx = (y * width + x) * 4
            return Triple(
                rgbaBytes[idx].toInt() and 0xFF,
                rgbaBytes[idx + 1].toInt() and 0xFF,
                rgbaBytes[idx + 2].toInt() and 0xFF,
            )
        }

        fun countDistinctQuantizedColors(step: Int = 4): Int {
            val seen = HashSet<Int>(1024)
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val idx = (y * width + x) * 4
                    val r = (rgbaBytes[idx].toInt() and 0xFF) / 8
                    val g = (rgbaBytes[idx + 1].toInt() and 0xFF) / 8
                    val b = (rgbaBytes[idx + 2].toInt() and 0xFF) / 8
                    seen.add((r shl 10) or (g shl 5) or b)
                    x += step
                }
                y += step
            }
            return seen.size
        }

        fun hasNonZeroLuminanceVariance(step: Int = 4): Boolean {
            var minLuma = Int.MAX_VALUE
            var maxLuma = Int.MIN_VALUE
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val idx = (y * width + x) * 4
                    val r = rgbaBytes[idx].toInt() and 0xFF
                    val g = rgbaBytes[idx + 1].toInt() and 0xFF
                    val b = rgbaBytes[idx + 2].toInt() and 0xFF
                    val luma = (r * 30 + g * 59 + b * 11) / 100
                    if (luma < minLuma) minLuma = luma
                    if (luma > maxLuma) maxLuma = luma
                    x += step
                }
                y += step
            }
            return (maxLuma - minLuma) >= 24
        }

        fun diffFractionAgainst(other: RenderedFrame, channelThreshold: Int = 6): Double {
            require(rgbaBytes.size == other.rgbaBytes.size)
            val totalPixels = width * height
            var changed = 0
            var i = 0
            while (i < rgbaBytes.size) {
                val dr = abs((rgbaBytes[i].toInt() and 0xFF) - (other.rgbaBytes[i].toInt() and 0xFF))
                val dg = abs((rgbaBytes[i + 1].toInt() and 0xFF) - (other.rgbaBytes[i + 1].toInt() and 0xFF))
                val db = abs((rgbaBytes[i + 2].toInt() and 0xFF) - (other.rgbaBytes[i + 2].toInt() and 0xFF))
                if (dr >= channelThreshold || dg >= channelThreshold || db >= channelThreshold) {
                    changed++
                }
                i += 4
            }
            return changed.toDouble() / totalPixels.toDouble()
        }

        fun meanChannelDeltaAgainst(other: RenderedFrame): Double {
            require(rgbaBytes.size == other.rgbaBytes.size)
            var sum = 0L
            var i = 0
            while (i < rgbaBytes.size) {
                sum += abs((rgbaBytes[i].toInt() and 0xFF) - (other.rgbaBytes[i].toInt() and 0xFF))
                sum += abs((rgbaBytes[i + 1].toInt() and 0xFF) - (other.rgbaBytes[i + 1].toInt() and 0xFF))
                sum += abs((rgbaBytes[i + 2].toInt() and 0xFF) - (other.rgbaBytes[i + 2].toInt() and 0xFF))
                i += 4
            }
            return sum.toDouble() / (width * height * 3.0)
        }
    }

    private fun extractRgba(image: Image): ByteArray {
        val info = ImageInfo(
            width = image.width,
            height = image.height,
            colorType = ColorType.RGBA_8888,
            alphaType = ColorAlphaType.UNPREMUL,
        )
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        try {
            val readOk = image.readPixels(bitmap, 0, 0)
            assertTrue(readOk, "Expected Skia Image.readPixels to succeed")
            val bytes = bitmap.readPixels(info, info.minRowBytes, 0, 0)
            assertTrue(bytes != null && bytes.size == image.width * image.height * 4)
            return bytes
        } finally {
            bitmap.close()
        }
    }

    private fun maybeDumpPng(name: String, image: Image) {
        val dumpDirPath = (
            System.getProperty(DUMP_DIR_PROP)
                ?: System.getenv("CIACOLA_DUMP_SHADERS_PREVIEW_DIR")
            )?.takeIf { it.isNotBlank() } ?: return
        val dir = File(dumpDirPath).apply { mkdirs() }
        val encoded = image.encodeToData(EncodedImageFormat.PNG) ?: return
        try {
            File(dir, "$name.png").writeBytes(encoded.bytes)
        } finally {
            encoded.close()
        }
    }

    private fun renderInScene(
        dumpName: String? = null,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        content: @Composable () -> Unit,
    ): RenderedFrame {
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            content()
        }
        return try {
            val image = scene.render(0L)
            try {
                if (dumpName != null) {
                    maybeDumpPng(dumpName, image)
                }
                RenderedFrame(width = image.width, height = image.height, rgbaBytes = extractRgba(image))
            } finally {
                image.close()
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun allFourteenSkslShadersCompileCleanlyOnSkiko() {
        assertEquals(14, ShadersSpecimenIds.allIds.size, "Expected exactly 14 shaders.* specimen IDs")
        assertEquals(
            ShadersSpecimenIds.allIds.toSet(),
            ShadersSpecimensCatalog.allSkslSources.keys,
            "Every shaders.* specimen ID must register its SkSL source in ShadersSpecimensCatalog",
        )

        for ((id, sksl) in ShadersSpecimensCatalog.allSkslSources) {
            val effect = RuntimeEffect.makeForShader(sksl)
            effect.close()
            assertTrue(sksl.contains("half4 main("), "SkSL source for $id must declare half4 main(...)")
        }
    }

    @Test
    fun everySpecimenRendersNonBlankDiverseDeterministicPixelsInImageComposeScene() {
        for (ref in ShadersSpecimenIds.allIds) {
            // Color evolution defaults to solid black when (0,0,0,1); verify checkerboard diversity via a=0f here
            // and check all solid RGBA color evolution steps separately below.
            val params = if (ref == ShadersSpecimenIds.COLOR_EVOLUTION) {
                mapOf("r" to 1f, "g" to 0f, "b" to 0f, "a" to 0.5f)
            } else {
                emptyMap()
            }

            val dumpSlug = ref.replace('.', '_')
            val frameA = renderInScene(dumpName = "${dumpSlug}_t0") {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = 0L,
                    params = params,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frameB = renderInScene {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = 0L,
                    params = params,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            assertContentEquals(
                frameA.rgbaBytes,
                frameB.rgbaBytes,
                "Specimen '$ref' must render byte-for-byte identical pixels across repeated ImageComposeScene renders at loopMillis=0",
            )
            assertTrue(
                frameA.hasNonZeroLuminanceVariance(),
                "Specimen '$ref' rendered blank or flat luminance frame",
            )
            val distinctColors = frameA.countDistinctQuantizedColors()
            val minExpectedColors = when (ref) {
                ShadersSpecimenIds.COLOR_EVOLUTION -> 2
                ShadersSpecimenIds.SDF_GOAL_CIRCLE,
                ShadersSpecimenIds.SDF_STEP_CIRCLE,
                ShadersSpecimenIds.SDF_CSG_SUBTRACT,
                ShadersSpecimenIds.SDF_CSG_UNION_SUBTRACT,
                -> 2
                ShadersSpecimenIds.SDF_RAW_DISTANCE -> 16
                else -> 40
            }
            assertTrue(
                distinctColors >= minExpectedColors,
                "Specimen '$ref' expected >= $minExpectedColors distinct quantized colors, got $distinctColors",
            )
        }
    }

    @Test
    fun everyAnimatedSpecimenChangesPixelsBetweenZeroAndOneThirdPeriodAndLoopsSeamlessly() {
        assertEquals(7, ShadersSpecimenIds.animatedPeriodsMs.size)

        for ((ref, periodMs) in ShadersSpecimenIds.animatedPeriodsMs) {
            val oneThirdMs = periodMs / 3L
            val dumpSlug = ref.replace('.', '_')

            val frameAtZero = renderInScene {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = 0L,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frameAtOneThirdA = renderInScene(dumpName = "${dumpSlug}_tThird") {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = oneThirdMs,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frameAtOneThirdB = renderInScene {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = oneThirdMs,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frameAtPeriod = renderInScene {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = periodMs,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frameAtPostZero = renderInScene {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = 16L,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frameAtPreWrap = renderInScene {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = (periodMs - 16L).coerceAtLeast(0L),
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            assertContentEquals(
                frameAtOneThirdA.rgbaBytes,
                frameAtOneThirdB.rgbaBytes,
                "Animated specimen '$ref' must be deterministic at loopMillis=$oneThirdMs",
            )
            assertContentEquals(
                frameAtZero.rgbaBytes,
                frameAtPeriod.rgbaBytes,
                "Animated specimen '$ref' must wrap seamlessly (loopMillis=0 == loopMillis=$periodMs)",
            )

            // Mathematical wrap continuity: frame(periodMs - 16) -> frame(0) must have no seam jump
            // compared to a normal 16ms forward step frame(0) -> frame(16).
            val normalStepDelta = frameAtZero.meanChannelDeltaAgainst(frameAtPostZero)
            val wrapSeamDelta = frameAtPreWrap.meanChannelDeltaAgainst(frameAtZero)
            val maxAllowedWrapDelta = maxOf(normalStepDelta * 3.0, 4.0)
            assertTrue(
                wrapSeamDelta < 8.0 && wrapSeamDelta <= maxAllowedWrapDelta,
                "Animated specimen '$ref' has a visible loop wrap seam between t=${periodMs - 16}ms and t=0ms " +
                    "(wrapDelta=${"%.3f".format(wrapSeamDelta)}, normal16msStep=${"%.3f".format(normalStepDelta)}, maxAllowed=${"%.3f".format(maxAllowedWrapDelta)})",
            )

            val diffFraction = frameAtZero.diffFractionAgainst(frameAtOneThirdA)
            assertTrue(
                diffFraction > 0.05,
                "Animated specimen '$ref' must produce substantially distinct pixels at t=0 vs t=$oneThirdMs (changed=${"%.2f".format(diffFraction * 100)}%)",
            )
        }
    }

    @Test
    fun animatedSpecimensSurviveLiveSequentialRecompositionFramesWithoutNativeLeakOrCrash() {
        for ((ref, periodMs) in ShadersSpecimenIds.animatedPeriodsMs) {
            var currentLoopMs by mutableLongStateOf(0L)
            val scene = ImageComposeScene(
                width = WIDTH,
                height = HEIGHT,
                density = Density(1f),
                coroutineContext = Dispatchers.Unconfined,
            ) {
                RenderShadersSpecimen(
                    ref = ref,
                    loopMillis = currentLoopMs,
                    isInteractive = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            try {
                val frame0 = scene.render(0L).use { extractRgba(it) }
                currentLoopMs = periodMs / 3L
                val frame1 = scene.render(16_000_000L).use { extractRgba(it) }
                currentLoopMs = (periodMs * 2L) / 3L
                val frame2 = scene.render(32_000_000L).use { extractRgba(it) }
                currentLoopMs = periodMs
                val frame3 = scene.render(48_000_000L).use { extractRgba(it) }

                assertFalse(
                    frame0.contentEquals(frame1),
                    "Live sequential scene for '$ref' should update pixels from frame 0 to frame 1",
                )
                assertFalse(
                    frame1.contentEquals(frame2),
                    "Live sequential scene for '$ref' should update pixels from frame 1 to frame 2",
                )
                assertContentEquals(
                    frame0,
                    frame3,
                    "Live sequential scene for '$ref' should return to exact t=0 pixels at t=periodMs",
                )
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun colorEvolutionStatesAndTeachingShadersMatchExpectedPixelMath() {
        // S14..S23 color evolution: transparent checkerboard, #000000, #FFFFFF, #FF0000
        val black = renderInScene {
            ColorEvolutionSpecimen(r = 0f, g = 0f, b = 0f, a = 1f, modifier = Modifier.fillMaxSize())
        }
        val white = renderInScene {
            ColorEvolutionSpecimen(r = 1f, g = 1f, b = 1f, a = 1f, modifier = Modifier.fillMaxSize())
        }
        val red = renderInScene(dumpName = "shaders_colorEvolution_red") {
            ColorEvolutionSpecimen(r = 1f, g = 0f, b = 0f, a = 1f, modifier = Modifier.fillMaxSize())
        }
        val transparent = renderInScene {
            ColorEvolutionSpecimen(r = 0f, g = 0f, b = 0f, a = 0f, modifier = Modifier.fillMaxSize())
        }

        assertEquals(Triple(0, 0, 0), black.rgbAt(WIDTH / 2, HEIGHT / 2))
        assertEquals(Triple(255, 255, 255), white.rgbAt(WIDTH / 2, HEIGHT / 2))
        assertEquals(Triple(255, 0, 0), red.rgbAt(WIDTH / 2, HEIGHT / 2))
        assertTrue(transparent.hasNonZeroLuminanceVariance(), "Transparent colorEvolution should show checkerboard")

        // S22..S26 liveUvStatic: verify blue channel parameter and UV corners
        val uvBlue0 = renderInScene {
            LiveUvStaticSpecimen(blue = 0f, modifier = Modifier.fillMaxSize())
        }
        val uvBlue1 = renderInScene {
            LiveUvStaticSpecimen(blue = 1f, modifier = Modifier.fillMaxSize())
        }
        val topLeft0 = uvBlue0.rgbAt(4, 4)
        val topLeft1 = uvBlue1.rgbAt(4, 4)
        val bottomRight0 = uvBlue0.rgbAt(WIDTH - 5, HEIGHT - 5)
        assertTrue(topLeft0.first < 15 && topLeft0.second < 15 && topLeft0.third < 5)
        assertTrue(topLeft1.first < 15 && topLeft1.second < 15 && topLeft1.third > 250)
        assertTrue(bottomRight0.first > 240 && bottomRight0.second > 240 && bottomRight0.third < 5)

        // S33..S44 SDF progression in a square 600x600 frame so uv=(0.625, 0.625) maps cleanly to (375, 375)
        val sdfRaw = renderInScene(width = 600, height = 600) {
            SdfRawDistanceSpecimen(modifier = Modifier.fillMaxSize())
        }
        val sdfStep = renderInScene(width = 600, height = 600) {
            SdfStepCircleSpecimen(modifier = Modifier.fillMaxSize())
        }
        val sdfSub = renderInScene(width = 600, height = 600) {
            SdfCsgSubtractSpecimen(modifier = Modifier.fillMaxSize())
        }
        val sdfUnionSub = renderInScene(width = 600, height = 600) {
            SdfCsgUnionSubtractSpecimen(modifier = Modifier.fillMaxSize())
        }

        // Raw SDF: bright center -> mid-gray halfway -> darker corner
        assertTrue(sdfRaw.rgbAt(300, 300).first > 240, "SdfRawDistance center should be near-white")
        assertTrue(sdfRaw.rgbAt(300, 150).first in 150..215, "SdfRawDistance mid-radius should be smooth gray")
        assertTrue(sdfRaw.rgbAt(20, 20).first < 135, "SdfRawDistance corner should be darker than mid-radius")

        // Step circle is white at (375, 375), CSG subtract cuts it black, CSG union-subtract restores white bullseye!
        assertTrue(sdfStep.rgbAt(375, 375).first > 240, "SdfStepCircle should be white at (0.625, 0.625)")
        assertTrue(sdfSub.rgbAt(375, 375).first < 15, "SdfCsgSubtract should carve black cutout at (0.625, 0.625)")
        assertTrue(sdfUnionSub.rgbAt(375, 375).first > 240, "SdfCsgUnionSubtract should restore white bullseye at (0.625, 0.625)")
        // Ring between r=0.15 and r=0.25 from (0.625, 0.625) at uv=(0.625, 0.825) => pixel (375, 495) stays black!
        assertTrue(sdfUnionSub.rgbAt(375, 495).first < 15, "SdfCsgUnionSubtract ring outside bullseye should remain black")

        // S45..S46 plaid mix responsiveness
        val pureStars = renderInScene {
            PlaidHyperspaceStarfieldSpecimen(loopMillis = 0L, uPlaidMix = 0f, modifier = Modifier.fillMaxSize())
        }
        val fullPlaid = renderInScene {
            PlaidHyperspaceStarfieldSpecimen(loopMillis = 0L, uPlaidMix = 1f, modifier = Modifier.fillMaxSize())
        }
        assertTrue(
            pureStars.diffFractionAgainst(fullPlaid) > 0.45,
            "PlaidHyperspaceStarfield uPlaidMix=0 vs uPlaidMix=1 must differ dramatically",
        )
    }

    @Test
    fun trippyKaleidoscopeFoldsUniformChangesRadialSymmetryVisiblyAndWrapsSeamlessly() {
        val folds4 = renderInScene {
            TrippyKaleidoscopeSpecimen(loopMillis = 2_000L, folds = 4f, modifier = Modifier.fillMaxSize())
        }
        val folds6 = renderInScene {
            TrippyKaleidoscopeSpecimen(loopMillis = 2_000L, folds = 6f, modifier = Modifier.fillMaxSize())
        }
        val folds10 = renderInScene {
            TrippyKaleidoscopeSpecimen(loopMillis = 2_000L, folds = 10f, modifier = Modifier.fillMaxSize())
        }

        assertTrue(
            folds4.diffFractionAgainst(folds6) > 0.25,
            "TrippyKaleidoscope folds=4 vs folds=6 must visibly alter radial kaleidoscope symmetry",
        )
        assertTrue(
            folds6.diffFractionAgainst(folds10) > 0.25,
            "TrippyKaleidoscope folds=6 vs folds=10 must visibly alter radial kaleidoscope symmetry",
        )
        assertTrue(
            folds4.diffFractionAgainst(folds10) > 0.25,
            "TrippyKaleidoscope folds=4 vs folds=10 must visibly alter radial kaleidoscope symmetry",
        )

        val periodMs = ShadersSpecimenIds.animatedPeriodsMs.getValue(ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE)
        for (f in listOf(4f, 10f)) {
            val fZero = renderInScene {
                TrippyKaleidoscopeSpecimen(loopMillis = 0L, folds = f, modifier = Modifier.fillMaxSize())
            }
            val fPeriod = renderInScene {
                TrippyKaleidoscopeSpecimen(loopMillis = periodMs, folds = f, modifier = Modifier.fillMaxSize())
            }
            assertContentEquals(
                fZero.rgbaBytes,
                fPeriod.rgbaBytes,
                "TrippyKaleidoscope at folds=$f must wrap seamlessly at loopMillis=$periodMs",
            )
        }
    }

    @Test
    fun allFourteenSpecimensHandleZeroSizeLayoutGracefully() {
        for (ref in ShadersSpecimenIds.allIds) {
            for (interactive in listOf(false, true)) {
                renderInScene {
                    Box(modifier = Modifier.size(0.dp)) {
                        RenderShadersSpecimen(
                            ref = ref,
                            loopMillis = 1_250L,
                            isInteractive = interactive,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
