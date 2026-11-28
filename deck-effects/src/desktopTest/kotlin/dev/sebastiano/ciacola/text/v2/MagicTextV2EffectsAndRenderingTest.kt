package dev.sebastiano.ciacola.text.v2

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.sun.management.ThreadMXBean
import dev.bnorm.storyboard.text.magic.MorphBy as MagicMorphBy
import dev.sebastiano.ciacola.EffectCall
import dev.sebastiano.ciacola.StyledText
import dev.sebastiano.ciacola.TextBuildKind
import dev.sebastiano.ciacola.TextContent
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.FontResourceLoader
import dev.sebastiano.ciacola.core.fonts.SystemFontLookup
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PieceSchedule
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.effects.v2.BuildEffects
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.text.v2.MagicTextV2Renderer.Companion.drawBuildFrame
import dev.sebastiano.ciacola.text.v2.MagicTextV2Renderer.Companion.drawMorphFrame
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Graphics
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.Stats
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.graphicapi.DirectXOffscreenContext
import org.jetbrains.skiko.graphicapi.MetalOffscreenContext
import org.jetbrains.skiko.graphicapi.OpenGLOffscreenContext

class MagicTextV2EffectsAndRenderingTest {

    // -------------------------------------------------------------------------
    // Production Composable Path Benchmark at 1920×1080 (CPU + the host's GPU backend)
    //   - 300-fragment moving morph (layer-free: 0 managed B/frame)
    //   - 300 pieces fading at once (layered: bounded constant B/frame, non-growing with count)
    //   - Overlapping additive cross-fade groups (BlendMode.PLUS)
    //   - Native-paragraph fallback with ink overflowing its box (no ink clipped during fade)
    //   - Native/GPU resource-cache bytes & live offscreen Surface count non-growing post-warmup
    // -------------------------------------------------------------------------

    private data class ProductionRunResult(
        val fixtureName: String,
        val backend: String,
        val recompositionCount: Int,
        val p50Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val maxMs: Double,
        val drawBytesPerFrame: Long,
        val resourceCacheStartBytes: Int,
        val resourceCacheEndBytes: Int,
        val fontCacheStartBytes: Int,
        val fontCacheEndBytes: Int,
        val liveSurfacesStart: Int,
        val liveSurfacesEnd: Int,
        val midFrameRgba: ByteArray? = null,
    )

    /**
     * Offscreen GPU backend Skiko offers on the current OS. Each run gets a fresh context, and
     * [withDirectContext] keeps it current on the calling thread for the whole [block].
     */
    @OptIn(ExperimentalSkikoApi::class)
    private enum class GpuBackend(val label: String) {
        Metal("Metal") {
            override fun <T> withDirectContext(block: (DirectContext) -> T): T =
                MetalOffscreenContext().use { ctx -> block(ctx.directContext) }
        },
        DirectX("Direct3D") {
            override fun <T> withDirectContext(block: (DirectContext) -> T): T =
                DirectXOffscreenContext().use { ctx -> block(ctx.directContext) }
        },
        OpenGL("OpenGL") {
            // The GL context is only current (and its DirectContext only exists) inside withTexture.
            override fun <T> withDirectContext(block: (DirectContext) -> T): T =
                OpenGLOffscreenContext().use { ctx -> ctx.withTexture(1, 1) { block(ctx.directContext) } }
        },
        ;

        abstract fun <T> withDirectContext(block: (DirectContext) -> T): T

        companion object {
            fun forHost(osName: String): GpuBackend {
                val os = osName.lowercase()
                return when {
                    os.contains("mac") -> Metal
                    os.contains("windows") -> DirectX
                    else -> OpenGL
                }
            }
        }
    }

    /** Returns the GPU's renderer description, or null when the host has no usable hardware GPU backend. */
    private fun probeHardwareGpu(backend: GpuBackend): String? = try {
        backend.withDirectContext {
            if (backend == GpuBackend.OpenGL) currentGlRenderer() ?: backend.label else backend.label
        }.takeUnless { description ->
            // Mesa's software rasterizers are CPU rendering behind a GL API: not a GPU measurement.
            SOFTWARE_GL_RENDERERS.any { description.contains(it, ignoreCase = true) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun runProductionScene(
        fixtureName: String,
        backend: String,
        gpu: GpuBackend?,
        frames: Array<TextContent>,
        config: MagicTextV2Config,
        density: Density,
        captureMidRgba: Boolean = false,
    ): ProductionRunResult = if (gpu == null) {
        runProductionSceneOn(fixtureName, backend, null, frames, config, density, captureMidRgba)
    } else {
        gpu.withDirectContext { ctx ->
            runProductionSceneOn(fixtureName, backend, ctx, frames, config, density, captureMidRgba)
        }
    }

    /**
     * Reads `GL_VENDOR` / `GL_RENDERER` from the current GL context through Skiko's OpenGLApi, which
     * is Kotlin-internal, so it is reached reflectively. Returns null if that API is not available.
     */
    private fun currentGlRenderer(): String? = try {
        val api = Class.forName("org.jetbrains.skiko.OpenGLApi")
        val companion = api.getField("Companion").get(null)
        val gl = companion.javaClass.getMethod("getInstance").apply { isAccessible = true }.invoke(companion)
        val glGetString = api.getMethod("glGetString", Int::class.javaPrimitiveType)
        val vendor = glGetString.invoke(gl, api.getMethod("getGL_VENDOR").invoke(gl))
        val renderer = glGetString.invoke(gl, api.getMethod("getGL_RENDERER").invoke(gl))
        "OpenGL: $vendor $renderer"
    } catch (_: ReflectiveOperationException) {
        null
    }

    @OptIn(InternalComposeUiApi::class)
    private fun runProductionSceneOn(
        fixtureName: String,
        backend: String,
        gpuContext: DirectContext?,
        frames: Array<TextContent>,
        config: MagicTextV2Config,
        density: Density,
        captureMidRgba: Boolean,
    ): ProductionRunResult {
        val renderer = MagicTextV2Renderer()
        val frameIdx = mutableIntStateOf(0)
        var recompositionCount = 0

        val threadBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        val allocSupported = threadBean?.isThreadAllocatedMemorySupported == true
        if (allocSupported) {
            threadBean?.isThreadAllocatedMemoryEnabled = true
        }

        var measuring = false
        var frameAllocStart = 0L
        var totalDrawAllocBytes = 0L
        var measuredDrawCalls = 0

        renderer.onBeforeDrawPhaseForTest = {
            if (measuring && allocSupported) {
                frameAllocStart = threadBean!!.currentThreadAllocatedBytes
            }
        }
        renderer.onAfterDrawPhaseForTest = {
            if (measuring && allocSupported) {
                val endBytes = threadBean!!.currentThreadAllocatedBytes
                val delta = if (endBytes >= frameAllocStart) endBytes - frameAllocStart else 0L
                totalDrawAllocBytes += delta
                measuredDrawCalls++
            }
        }

        val surface = if (gpuContext != null) {
            Surface.makeRenderTarget(
                context = gpuContext,
                budgeted = false,
                imageInfo = ImageInfo.makeN32Premul(1920, 1080),
            ) ?: error("Failed to create 1920x1080 $backend render target Surface")
        } else {
            Surface.makeRasterN32Premul(1920, 1080)
        }
        val composeCanvas = surface.canvas.asComposeCanvas()
        val frameRecomposer = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            size = IntSize(1920, 1080),
        )

        fun renderOneFrame(index: Int, frameSeq: Long) {
            frameIdx.intValue = index
            Snapshot.sendApplyNotifications()
            frameRecomposer.performFrame(frameSeq * 16_666_667L)
            scene.measureAndLayout()
            surface.canvas.clear(0xFF0E1117.toInt())
            scene.draw(composeCanvas)
            gpuContext?.flushAndSubmit(surface, syncCpu = true)
        }

        val warmupCount = 48
        val measureCount = 90
        val durationsNs = LongArray(measureCount)
        var resCacheStart = 0
        var resCacheEnd = 0
        var fontCacheStart = 0
        var fontCacheEnd = 0
        var liveSurfacesStart = 0
        var liveSurfacesEnd = 0
        var midRgba: ByteArray? = null

        try {
            scene.setContent {
                recompositionCount++
                CompositionLocalProvider(LocalMagicTextV2Config provides config) {
                    renderer.Text(content = { frames[frameIdx.intValue] })
                }
            }

            // Warmup phase (populates TextMeasurer, Partition, Morph/Build plans, JIT, and GPU pipelines).
            for (w in 0 until warmupCount) {
                renderOneFrame((w * (frames.size - 1)) / (warmupCount - 1).coerceAtLeast(1), w.toLong())
            }
            val planMissesBefore = renderer.caches.morphPlanCacheMisses
            val partMissesBefore = renderer.caches.partitionCacheMisses

            // Measured timing + managed heap allocation pass (Stats disabled to avoid Skiko map tracking allocs).
            Stats.enabled = false
            resCacheStart = Graphics.resourceCacheTotalUsed
            fontCacheStart = Graphics.fontCacheUsed

            measuring = true
            for (m in 0 until measureCount) {
                val idx = (m * (frames.size - 1)) / (measureCount - 1).coerceAtLeast(1)
                val t0 = System.nanoTime()
                renderOneFrame(idx, (warmupCount + m).toLong())
                durationsNs[m] = System.nanoTime() - t0
            }
            measuring = false

            resCacheEnd = Graphics.resourceCacheTotalUsed
            fontCacheEnd = Graphics.fontCacheUsed

            assertEquals(planMissesBefore, renderer.caches.morphPlanCacheMisses, "$fixtureName ($backend) had cache misses mid-sweep")
            assertEquals(partMissesBefore, renderer.caches.partitionCacheMisses, "$fixtureName ($backend) had partition misses mid-sweep")

            // Dedicated live offscreen Surface leak pass (asserts zero Surface growth across 20 warm frames).
            Stats.enabled = true
            try {
                liveSurfacesStart = Stats.allocated["Surface"] ?: 0
                for (k in 0 until 20) {
                    val idx = (k * (frames.size - 1)) / 19
                    renderOneFrame(idx, (warmupCount + measureCount + k).toLong())
                }
                liveSurfacesEnd = Stats.allocated["Surface"] ?: 0
            } finally {
                Stats.enabled = false
            }

            if (captureMidRgba) {
                val midIdx = frames.size / 2
                renderOneFrame(midIdx, (warmupCount + measureCount + 25).toLong())
                val snap = surface.makeImageSnapshot()
                try {
                    midRgba = readImageRgba(snap)
                } finally {
                    snap.close()
                }
            }
        } finally {
            renderer.onBeforeDrawPhaseForTest = null
            renderer.onAfterDrawPhaseForTest = null
            scene.close()
            frameRecomposer.close()
            surface.close()
        }

        val sortedMs = durationsNs.map { it / 1_000_000.0 }.sorted()
        val p50 = sortedMs[(sortedMs.size * 0.50).toInt()]
        val p95 = sortedMs[(sortedMs.size * 0.95).toInt().coerceAtMost(sortedMs.lastIndex)]
        val p99 = sortedMs[(sortedMs.size * 0.99).toInt().coerceAtMost(sortedMs.lastIndex)]
        val maxMs = sortedMs.last()
        val bytesPerFrame = if (allocSupported && measuredDrawCalls > 0) totalDrawAllocBytes / measuredDrawCalls else -1L

        return ProductionRunResult(
            fixtureName = fixtureName,
            backend = backend,
            recompositionCount = recompositionCount,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            maxMs = maxMs,
            drawBytesPerFrame = bytesPerFrame,
            resourceCacheStartBytes = resCacheStart,
            resourceCacheEndBytes = resCacheEnd,
            fontCacheStartBytes = fontCacheStart,
            fontCacheEndBytes = fontCacheEnd,
            liveSurfacesStart = liveSurfacesStart,
            liveSurfacesEnd = liveSurfacesEnd,
            midFrameRgba = midRgba,
        )
    }

    @Test
    fun productionComposablePathCpuAndGpuAll4FixturesAllocAndNativeResources() {
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val density = Density(2f)
        val config = MagicTextV2Config(
            fontResolver = resolver,
            stageSizeForEffects = Size(1920f, 1080f),
        )
        val baseStyle = resolver.resolveTextStyle(
            "Inter",
            TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 22.sp),
        )
        val palette = listOf(Color(0xFFFFFFFF), Color(0xFFE0ECFF), Color(0xFFA8D8FF), Color(0xFFFFD8A8))

        fun buildStyledWords(words: List<String>, flipStylePrefix: Int = 0): AnnotatedString = buildAnnotatedString {
            for ((idx, w) in words.withIndex()) {
                if (idx > 0) append(' ')
                val id = w.substring(3).toInt()
                val flip = flipStylePrefix > 0 && w.startsWith("tok") && id < flipStylePrefix
                val color = if (flip) Color(0xFFFFB74D) else palette[id % palette.size]
                val weight = when {
                    flip -> FontWeight.Bold
                    id % 5 == 0 -> FontWeight.Bold
                    id % 5 == 2 -> FontWeight.Medium
                    else -> FontWeight.Normal
                }
                withStyle(SpanStyle(color = color, fontWeight = weight)) { append(w) }
            }
        }

        // Fixture 1: 300-fragment moving morph (ALL kept, same style -> layer-free draws -> 0 managed B/frame!)
        val vocab300 = List(300) { idx -> "tok%03d".format(idx) }
        val rotated300 = List(300) { idx -> vocab300[(idx * 7 + 13) % 300] }
        val moveFromStyled = StyledText(buildStyledWords(vocab300), baseStyle, maxWidth = 1820f)
        val moveToStyled = StyledText(buildStyledWords(rotated300), baseStyle, maxWidth = 1820f)
        val moveSched = PieceSchedule(
            listOf(
                dev.sebastiano.ciacola.document.Slot(
                    address = dev.sebastiano.ciacola.document.Slot.SIZE_ADDRESS,
                    startMs = 0,
                    durationMs = 600,
                    curve = Curve.Linear,
                    phase = dev.sebastiano.ciacola.document.SlotPhase.Move,
                ),
            ),
        )
        val fixture1Move300 = Array<TextContent>(90) { f ->
            TextContent.Morph(
                from = moveFromStyled,
                to = moveToStyled,
                by = MorphBy.Word,
                schedule = moveSched,
                localMs = 60f + (f / 89f) * 480f,
                pruneStopwords = false,
                interpolateLayoutSize = false,
            )
        }

        // Fixture 2: 300 pieces fading simultaneously (layered fade) + 50-piece baseline to verify constant alloc.
        fun makeSimultaneousFadeFrames(wordCount: Int): Array<TextContent> {
            val words = List(wordCount) { idx -> "tok%03d".format(idx) }
            val text = buildStyledWords(words)
            val styled = StyledText(text, baseStyle, maxWidth = 1820f)
            val tokens = dev.sebastiano.ciacola.document.TextTokenizer.tokenizeForDelivery(text.text, DeliveryBy.Word)
            val slots = tokens.map { tok ->
                dev.sebastiano.ciacola.document.Slot(
                    address = tok.address,
                    startMs = 0,
                    durationMs = 600,
                    curve = Curve.Linear,
                    phase = dev.sebastiano.ciacola.document.SlotPhase.Enter,
                )
            }
            val sched = PieceSchedule(slots)
            val eff = EffectCall(effectId = BuildEffects.DISSOLVE)
            return Array(90) { f ->
                TextContent.Build(
                    text = styled,
                    by = DeliveryBy.Word,
                    effect = eff,
                    kind = TextBuildKind.In,
                    schedule = sched,
                    localMs = 60f + (f / 89f) * 480f,
                )
            }
        }
        val fixture2Fade50 = makeSimultaneousFadeFrames(50)
        val fixture2Fade300 = makeSimultaneousFadeFrames(300)

        // Fixture 3: 300-word morph with overlapping additive BlendMode.PLUS cross-fade groups + exits/enters.
        val mixedRearranged = buildList {
            for (i in 0 until 260) add(vocab300[(i * 7 + 13) % 260])
            for (i in 0 until 40) add("new%03d".format(i))
        }
        val addFromStyled = StyledText(buildStyledWords(vocab300, flipStylePrefix = 0), baseStyle, maxWidth = 1820f)
        val addToStyled = StyledText(buildStyledWords(mixedRearranged, flipStylePrefix = 32), baseStyle, maxWidth = 1820f)
        val addSched = PieceSchedule(
            listOf(
                dev.sebastiano.ciacola.document.Slot(
                    address = dev.sebastiano.ciacola.document.Slot.SIZE_ADDRESS,
                    startMs = 0,
                    durationMs = 600,
                    curve = Curve.Linear,
                    phase = dev.sebastiano.ciacola.document.SlotPhase.Move,
                ),
            ),
        )
        val fixture3Additive = Array<TextContent>(90) { f ->
            TextContent.Morph(
                from = addFromStyled,
                to = addToStyled,
                by = MorphBy.Word,
                schedule = addSched,
                localMs = 60f + (f / 89f) * 480f,
                pruneStopwords = false,
                interpolateLayoutSize = false,
            )
        }

        // Fixture 4: Native-paragraph fallback with ink overflowing its layout box (huge shadow > 2 * lineHeight).
        val overflowShadow = Shadow(
            color = Color(0xFFFF6B35),
            offset = Offset(190f, 195f),
            blurRadius = 24f,
        )
        val overflowStyle = resolver.resolveTextStyle(
            "Inter",
            TextStyle(color = Color.White, fontSize = 36.sp, lineHeight = 44.sp, shadow = overflowShadow),
        )
        val overflowFrom = StyledText(AnnotatedString("Overflowing shadow paragraph"), overflowStyle, maxWidth = 900f)
        val overflowTo = StyledText(AnnotatedString("Different target paragraph"), overflowStyle, maxWidth = 900f)
        val overflowPlan = MagicTextV2Renderer().planMorph(
            TextContentV2.Morph(
                from = StyledTextV2(overflowFrom.text, overflowFrom.style, maxWidthPx = 900),
                to = StyledTextV2(overflowTo.text, overflowTo.style, maxWidthPx = 900),
                by = MagicMorphBy.Word,
                schedule = PieceScheduleV2(totalDurationMs = 600, slots = emptyMap()),
                elapsedMs = 300f,
            ),
            MagicTextV2TestSupport.createMeasurer(density),
            density,
            config = config,
        )
        assertTrue(overflowPlan.wholeParagraphCrossFade, "Shadow > 2*lineHeight must refuse partition into wholeParagraphCrossFade")
        val overflowLayoutSize = overflowPlan.fromPartition.wholeLayout.size
        // Verify Fixtures 1, 2, and 3 genuinely admit into partitioned pieces (never silently falling back to whole-paragraph cross-fade!).
        run {
            val verifyRenderer = MagicTextV2Renderer()
            val verifyMeasurer = MagicTextV2TestSupport.createMeasurer(density)
            val movePlan = verifyRenderer.planMorph(
                TextContentV2.Morph(
                    from = StyledTextV2(moveFromStyled.text, moveFromStyled.style, maxWidthPx = 1820),
                    to = StyledTextV2(moveToStyled.text, moveToStyled.style, maxWidthPx = 1820),
                    by = MagicMorphBy.Word,
                    schedule = PieceScheduleV2(totalDurationMs = 600, slots = emptyMap()),
                    elapsedMs = 300f,
                    pruneStopwords = false,
                ),
                verifyMeasurer,
                density,
                config = config,
            )
            assertFalse(
                movePlan.wholeParagraphCrossFade,
                "Fixture 1 (300-move) must admit cleanly without falling back (from=${movePlan.fromPartition.refusalReason}, to=${movePlan.toPartition.refusalReason})",
            )
            assertEquals(
                300,
                movePlan.groups.size,
                "Fixture 1 must have 300 admitted moving word groups " +
                    "(fromPieces=${movePlan.fromPartition.pieces.size}, fromRounds=${movePlan.fromPartition.coarseningRounds}, " +
                    "toPieces=${movePlan.toPartition.pieces.size}, toRounds=${movePlan.toPartition.coarseningRounds})",
            )
            assertTrue(
                movePlan.groups.all { it.kind == DrawableGroupKind.Move && !it.styleChanged },
                "Fixture 1 must be 100% layer-free Move groups",
            )

            val fade300Text = (fixture2Fade300[0] as TextContent.Build).text
            val fade300Plan = verifyRenderer.planBuild(
                TextContentV2.Build(
                    content = StyledTextV2(fade300Text.text, fade300Text.style, maxWidthPx = 1820),
                    granularity = PartitionGranularity.Word,
                    effect = EffectCallV2(BuildEffects.DISSOLVE),
                    schedule = PieceScheduleV2(totalDurationMs = 600, slots = emptyMap()),
                    elapsedMs = 300f,
                ),
                verifyMeasurer,
                density,
                config = config,
            )
            assertTrue(fade300Plan.partition.admitted, "Fixture 2 (300-fade) must admit cleanly")
            assertEquals(300, fade300Plan.pieceTimings.size, "Fixture 2 (300-fade) must have 300 admitted word pieces")

            val addPlan = verifyRenderer.planMorph(
                TextContentV2.Morph(
                    from = StyledTextV2(addFromStyled.text, addFromStyled.style, maxWidthPx = 1820),
                    to = StyledTextV2(addToStyled.text, addToStyled.style, maxWidthPx = 1820),
                    by = MagicMorphBy.Word,
                    schedule = PieceScheduleV2(totalDurationMs = 600, slots = emptyMap()),
                    elapsedMs = 300f,
                    pruneStopwords = false,
                ),
                verifyMeasurer,
                density,
                config = config,
            )
            assertFalse(addPlan.wholeParagraphCrossFade, "Fixture 3 (300-morph + 32 additive) must admit cleanly")
            val additiveCount = addPlan.groups.count { it.kind == DrawableGroupKind.Move && it.styleChanged }
            assertTrue(
                additiveCount >= 28,
                "Fixture 3 must exercise >= 28 real additive BlendMode.PLUS cross-fade groups (got $additiveCount)",
            )
        }
        val fixture4Overflow = Array<TextContent>(90) { f ->
            TextContent.Morph(
                from = overflowFrom,
                to = overflowTo,
                by = MorphBy.Word,
                schedule = addSched,
                localMs = 60f + (f / 89f) * 480f,
                pruneStopwords = false,
                interpolateLayoutSize = false,
            )
        }

        // Benchmark the GPU backend the app renders with on this OS (Metal on macOS, Direct3D on
        // Windows, OpenGL on Linux) whenever the host has a hardware GPU for it.
        val osName = System.getProperty("os.name").orEmpty()
        val gpuBackend = GpuBackend.forHost(osName)
        val gpuDescription = probeHardwareGpu(gpuBackend)
        if (osName.lowercase().contains("mac")) {
            assertNotNull(gpuDescription, "Production Metal benchmark requires real Metal offscreen context on macOS")
        }

        val backends = buildList<Pair<String, GpuBackend?>> {
            add("CPU" to null)
            if (gpuDescription != null) add(gpuBackend.label to gpuBackend)
        }

        val report = StringBuilder("\n=== Production Composable Path Benchmark (1920x1080, Density=2f, Warm Caches) ===\n")
        report.append(
            "  Host : ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), " +
                "${Runtime.getRuntime().availableProcessors()} cores, JDK ${System.getProperty("java.version")}\n",
        )
        report.append("  GPU  : ${gpuDescription ?: "none (${gpuBackend.label} offscreen context unavailable or software)"}\n")

        val isCi = System.getenv("CI").equals("true", ignoreCase = true) ||
            System.getenv("GITHUB_ACTIONS").equals("true", ignoreCase = true)

        for ((backendName, gpu) in backends) {
            // The app draws through the GPU on every OS, so GPU runs keep the strict 60 fps (16.67 ms)
            // budget. The CPU raster run is a software reference (32 additive saveLayers / 1920x1080
            // 24 px blur fallback): 30 fps (33.33 ms) on local machines, and 100 ms on shared
            // virtualized CI runners, which run ~8x slower than Apple Silicon.
            val p95BudgetMs = when {
                gpu != null -> 16.67
                isCi -> 100.0
                else -> 33.33
            }
            val rMove = runProductionScene(
                "300-move (layer-free)", backendName, gpu, fixture1Move300, config, density,
            )
            val rFade50 = runProductionScene(
                "50-fade (layered)", backendName, gpu, fixture2Fade50, config, density,
            )
            val rFade300 = runProductionScene(
                "300-fade (layered)", backendName, gpu, fixture2Fade300, config, density,
            )
            val rAdditive = runProductionScene(
                "300-morph + 32 additive", backendName, gpu, fixture3Additive, config, density,
            )
            val rOverflow = runProductionScene(
                "overflow-fallback", backendName, gpu, fixture4Overflow, config, density, captureMidRgba = true,
            )

            for (r in listOf(rMove, rFade50, rFade300, rAdditive, rOverflow)) {
                report.append(
                    String.format(
                        java.util.Locale.US,
                        "  [%-5s] %-24s | recomps=%d | p50=%6.3f ms p95=%6.3f ms p99=%6.3f ms max=%6.3f ms | " +
                            "alloc=%4d B/f | resCache=%d->%d B | fontCache=%d->%d B | surfaces=%d->%d\n",
                        r.backend,
                        r.fixtureName,
                        r.recompositionCount,
                        r.p50Ms,
                        r.p95Ms,
                        r.p99Ms,
                        r.maxMs,
                        r.drawBytesPerFrame,
                        r.resourceCacheStartBytes,
                        r.resourceCacheEndBytes,
                        r.fontCacheStartBytes,
                        r.fontCacheEndBytes,
                        r.liveSurfacesStart,
                        r.liveSurfacesEnd,
                    ),
                )

                assertEquals(1, r.recompositionCount, "${r.fixtureName} (${r.backend}) must NEVER recompose on frame updates")
                assertTrue(
                    r.p95Ms < p95BudgetMs,
                    "${r.fixtureName} (${r.backend}) p95=%.3f ms exceeded %.2f ms budget".format(r.p95Ms, p95BudgetMs) +
                        report,
                )
                assertTrue(
                    r.liveSurfacesEnd <= r.liveSurfacesStart,
                    "${r.fixtureName} (${r.backend}) leaked live offscreen Surfaces: ${r.liveSurfacesStart} -> ${r.liveSurfacesEnd}",
                )
                assertTrue(
                    r.resourceCacheEndBytes <= r.resourceCacheStartBytes + 256 * 1024,
                    "${r.fixtureName} (${r.backend}) native resource cache grew after warmup: ${r.resourceCacheStartBytes} -> ${r.resourceCacheEndBytes}",
                )
                assertTrue(
                    r.fontCacheEndBytes <= r.fontCacheStartBytes + 64 * 1024,
                    "${r.fixtureName} (${r.backend}) font cache grew after warmup: ${r.fontCacheStartBytes} -> ${r.fontCacheEndBytes}",
                )
            }

            // Layer-free managed heap allocation MUST be strictly 0 bytes/frame!
            if (rMove.drawBytesPerFrame >= 0L) {
                assertEquals(
                    0L,
                    rMove.drawBytesPerFrame,
                    "(${backendName}) layer-free 300-piece move MUST allocate 0 managed bytes/frame inside drawBehind (measured ${rMove.drawBytesPerFrame} B/frame)",
                )
            }
            // Layered fade managed allocation MUST stay within a small constant independent of piece count (50 vs 300)!
            if (rFade300.drawBytesPerFrame >= 0L && rFade50.drawBytesPerFrame >= 0L) {
                assertTrue(
                    rFade300.drawBytesPerFrame <= 512L,
                    "($backendName) 300-piece layered fade managed alloc must stay <= 512 B/frame (got ${rFade300.drawBytesPerFrame})",
                )
                assertTrue(
                    rFade300.drawBytesPerFrame <= rFade50.drawBytesPerFrame + 256L,
                    "($backendName) layered fade alloc must not grow with piece count (50-piece=${rFade50.drawBytesPerFrame}, 300-piece=${rFade300.drawBytesPerFrame})",
                )
            }
            if (rAdditive.drawBytesPerFrame >= 0L) {
                assertTrue(
                    rAdditive.drawBytesPerFrame <= 512L,
                    "($backendName) additive cross-fade managed alloc must stay <= 512 B/frame (got ${rAdditive.drawBytesPerFrame})",
                )
            }

            // Overflow fallback mid-fade MUST preserve overflowing shadow ink outside the layout box!
            val midRgba = assertNotNull(rOverflow.midFrameRgba)
            val overflowRegionInk = countNonBackgroundPixelsInRect(
                rgba = midRgba,
                w = 1920,
                h = 1080,
                x0 = 0,
                y0 = overflowLayoutSize.height + 100,
                x1 = (overflowLayoutSize.width + 240).coerceAtMost(1920),
                y1 = (overflowLayoutSize.height + 245).coerceAtMost(1080),
                bgR = 0x0E,
                bgG = 0x11,
                bgB = 0x17,
            )
            assertTrue(
                overflowRegionInk > 100,
                "($backendName) overflowing native-paragraph fallback lost shadow ink below layout box (y > ${overflowLayoutSize.height + 10}, ink=$overflowRegionInk)",
            )
        }

        println(report.toString())
    }

    // -------------------------------------------------------------------------
    // Character Builds with REAL Planner-Generated PieceSchedules
    //   (`dissolve`, `fadeMove`, `keyboard` Enter & Exit + Coarsened UTF-16 Union)
    // -------------------------------------------------------------------------

    private fun planRealTextBuildSchedule(
        text: String,
        effectId: String,
        kind: EffectBuildKind,
        by: DeliveryBy = DeliveryBy.Character,
        stagger: Stagger? = null,
        durationMs: Int? = null,
        curve: Curve? = Curve.Linear,
        args: Map<String, JsonElement> = emptyMap(),
        reducedMotion: Boolean = false,
    ): Pair<PieceScheduleV2, Int> {
        val enterBuild = Build.Enter(
            beat = 0,
            id = "enter",
            effect = effectId,
            args = args,
            by = by,
            stagger = stagger,
            durationMs = durationMs,
            curve = curve,
        )
        val exitBuild = Build.Exit(
            beat = 1,
            id = "exit",
            effect = effectId,
            args = args,
            by = by,
            stagger = stagger,
            durationMs = durationMs,
            curve = curve,
        )
        val builds = if (kind == EffectBuildKind.Enter) listOf(enterBuild) else listOf(enterBuild, exitBuild)
        val doc = DeckDocument(
            id = "char-builds-test",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "txt",
                            text = TextPayload(value = RichTextValue.Plain(text)),
                            builds = builds,
                        ),
                    ),
                ),
            ),
        )
        val targetBeat = if (kind == EffectBuildKind.Enter) 0 else 1
        val segPlan = SegmentPlanner.planSegment(doc, slideId = "s1", beat = targetBeat, reducedMotion = reducedMotion)
        val track = assertNotNull(
            segPlan.tracks.firstOrNull { it.nodeId == "txt" },
            "SegmentPlanner must emit a TrackPlan for $effectId $kind",
        )
        val scheduleV2 = track.pieces?.let {
            MagicTextV2Renderer.convertPieceSchedule(it, minTotalDurationMs = track.durationMs)
        } ?: PieceScheduleV2(totalDurationMs = track.durationMs.coerceAtLeast(1), slots = emptyMap())
        return scheduleV2 to track.durationMs
    }

    @Test
    fun characterBuildsDissolveFadeMoveAndKeyboardWithRealPlannerSlots() {
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val density = Density(2f)
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val renderer = MagicTextV2Renderer()
        val config = MagicTextV2Config(fontResolver = resolver)

        val textStr = "Compose Magic"
        val style = resolver.resolveTextStyle(
            "HankenGrotesk",
            TextStyle(color = Color.White, fontSize = 36.sp, lineHeight = 46.sp),
        )
        val styled = StyledTextV2(AnnotatedString(textStr), style, maxWidthPx = 800)
        val partition = renderer.admitPartition(
            styled = styled,
            requestedGranularity = PartitionGranularity.Character,
            characterDeliveryNode = true,
            measurer = measurer,
            density = density,
            config = config,
        )
        assertTrue(partition.admitted, "Character build partition must be admitted")
        assertEquals(12, partition.pieces.size, "Expected 12 character pieces ('Compose' + 'Magic')")

        val w = 700
        val h = 160
        val restPixels = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawText(partition.wholeLayout)
        }
        val fullInk = MagicTextV2TestSupport.countInkPixels(restPixels)
        assertTrue(fullInk > 1_000)

        // 1. Dissolve Enter & Exit using REAL planner-generated slots (stagger=30ms, dur=240ms)
        for (kind in listOf(EffectBuildKind.Enter, EffectBuildKind.Exit)) {
            val (schedule, trackDurationMs) = planRealTextBuildSchedule(
                text = textStr,
                effectId = BuildEffects.DISSOLVE,
                kind = kind,
                stagger = Stagger(ms = 30),
                durationMs = 240,
            )
            assertTrue(schedule.slots.size >= 12, "Planner must emit per-character slots, got ${schedule.slots.size}")
            val eff = EffectCallV2(BuildEffects.DISSOLVE, kind, EffectArgs.Empty, CurveV2.Linear)
            val buildBase = TextContentV2.Build(styled, PartitionGranularity.Character, eff, schedule, 0f)
            val buildPlan = renderer.planBuild(buildBase, measurer, density, config = config)
            assertEquals(12, buildPlan.pieceTimings.size)
            assertEquals(trackDurationMs, buildPlan.totalDurationMs)
            // Verify slots joined strictly by UTF-16 range intersection (distinct staggered start times!).
            assertTrue(
                buildPlan.pieceTimings.first().startMs < buildPlan.pieceTimings.last().startMs,
                "Planner stagger must propagate through UTF-16 slot joining",
            )

            val startPx = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
                drawBuildFrame(buildPlan, buildBase.copy(elapsedMs = 0f), config)
            }
            val midPx = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
                drawBuildFrame(buildPlan, buildBase.copy(elapsedMs = trackDurationMs * 0.5f), config)
            }
            val endPx = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
                drawBuildFrame(buildPlan, buildBase.copy(elapsedMs = trackDurationMs.toFloat()), config)
            }

            if (kind == EffectBuildKind.Enter) {
                assertEquals(0, MagicTextV2TestSupport.countInkPixels(startPx), "dissolve Enter t=0 must have 0 ink")
                assertTrue(MagicTextV2TestSupport.countInkPixels(midPx) in 1 until fullInk)
                assertTrue(MagicTextV2TestSupport.compareRgba(restPixels, endPx, tolerance = 2).maxDelta <= 2)
            } else {
                assertTrue(MagicTextV2TestSupport.compareRgba(restPixels, startPx, tolerance = 2).maxDelta <= 2)
                assertTrue(MagicTextV2TestSupport.countInkPixels(midPx) in 1 until fullInk)
                assertEquals(0, MagicTextV2TestSupport.countInkPixels(endPx), "dissolve Exit at trackEnd must have 0 ink")
            }
        }

        // 2. FadeMove Enter & Exit using REAL planner slots
        val (fmEnterSched, fmEnterMs) = planRealTextBuildSchedule(
            text = textStr,
            effectId = BuildEffects.FADE_MOVE,
            kind = EffectBuildKind.Enter,
            stagger = Stagger(ms = 30),
            durationMs = 240,
        )
        val dissolveEff = EffectCallV2(BuildEffects.DISSOLVE, EffectBuildKind.Enter, EffectArgs.Empty, CurveV2.Linear)
        val fmEnterEff = EffectCallV2(
            BuildEffects.FADE_MOVE,
            EffectBuildKind.Enter,
            EffectArgs(mapOf("direction" to "up", "distance" to 36f)),
            CurveV2.Linear,
        )
        val fmEnterBase = TextContentV2.Build(styled, PartitionGranularity.Character, fmEnterEff, fmEnterSched, 0f)
        val fmEnterPlan = renderer.planBuild(fmEnterBase, measurer, density, config = config)
        val dissolveMidPx = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(fmEnterPlan, fmEnterBase.copy(effect = dissolveEff, elapsedMs = fmEnterMs * 0.45f), config)
        }
        val fmMidPx = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(fmEnterPlan, fmEnterBase.copy(elapsedMs = fmEnterMs * 0.45f), config)
        }
        val fmEndPx = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(fmEnterPlan, fmEnterBase.copy(elapsedMs = fmEnterMs.toFloat()), config)
        }
        assertTrue(MagicTextV2TestSupport.compareRgba(dissolveMidPx, fmMidPx, tolerance = 2).differingPixelsOverTol > 100)
        assertTrue(MagicTextV2TestSupport.compareRgba(restPixels, fmEndPx, tolerance = 2).maxDelta <= 2)

        val (fmExitSched, fmExitMs) = planRealTextBuildSchedule(
            text = textStr,
            effectId = BuildEffects.FADE_MOVE,
            kind = EffectBuildKind.Exit,
            stagger = Stagger(ms = 30),
            durationMs = 240,
        )
        val fmExitEff = EffectCallV2(
            BuildEffects.FADE_MOVE,
            EffectBuildKind.Exit,
            EffectArgs(mapOf("direction" to "down", "distance" to 36f)),
            CurveV2.Linear,
        )
        val fmExitBase = TextContentV2.Build(styled, PartitionGranularity.Character, fmExitEff, fmExitSched, fmExitMs.toFloat())
        val fmExitPlan = renderer.planBuild(fmExitBase, measurer, density, config = config)
        val fmExitEndPx = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(fmExitPlan, fmExitBase, config)
        }
        assertEquals(0, MagicTextV2TestSupport.countInkPixels(fmExitEndPx), "fadeMove Exit at trackEnd must have 0 ink")

        // 3. Keyboard Enter using REAL SegmentPlanner schedules (zero renderer-invented jitter!)
        val (kbSchedA, kbTotalMsA) = planRealTextBuildSchedule(
            text = textStr,
            effectId = BuildEffects.KEYBOARD,
            kind = EffectBuildKind.Enter,
            args = mapOf("seed" to JsonPrimitive(42), "charsPerSec" to JsonPrimitive(16.0), "caret" to JsonPrimitive(true)),
        )
        val (kbSchedB, _) = planRealTextBuildSchedule(
            text = textStr,
            effectId = BuildEffects.KEYBOARD,
            kind = EffectBuildKind.Enter,
            args = mapOf("seed" to JsonPrimitive(999), "charsPerSec" to JsonPrimitive(16.0), "caret" to JsonPrimitive(true)),
        )
        assertNotEquals(
            kbSchedA.slots.values.map { it.startMs },
            kbSchedB.slots.values.map { it.startMs },
            "Different keyboard seeds in SegmentPlanner must produce distinct slot schedules",
        )

        val kbEnterEffCaret = EffectCallV2(BuildEffects.KEYBOARD, EffectBuildKind.Enter, EffectArgs(mapOf("caret" to true)))
        val kbEnterEffNoCaret = EffectCallV2(BuildEffects.KEYBOARD, EffectBuildKind.Enter, EffectArgs(mapOf("caret" to false)))
        val kbEnterBase = TextContentV2.Build(styled, PartitionGranularity.Character, kbEnterEffCaret, kbSchedA, 0f)
        val kbEnterPlan = renderer.planBuild(kbEnterBase, measurer, density, config = config)
        assertEquals(kbTotalMsA, kbEnterPlan.totalDurationMs)

        // Start (t=0, caret=false) is completely empty.
        val kbStartNoCaret = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbEnterPlan, kbEnterBase.copy(effect = kbEnterEffNoCaret, elapsedMs = 0f), config)
        }
        assertEquals(0, MagicTextV2TestSupport.countInkPixels(kbStartNoCaret), "keyboard Enter at t=0 must have 0 ink")

        // Mid-typing: deterministic 0/255 across repeated renders + caret adds ink pixels.
        val midKeyMs = kbEnterPlan.pieceTimings[kbEnterPlan.pieceTimings.size / 2].endMs.toFloat() + 10f
        val kbRun1 = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbEnterPlan, kbEnterBase.copy(elapsedMs = midKeyMs), config)
        }
        val kbRun2 = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbEnterPlan, kbEnterBase.copy(elapsedMs = midKeyMs), config)
        }
        assertEquals(0, MagicTextV2TestSupport.compareRgba(kbRun1, kbRun2, tolerance = 0).maxDelta, "Same keyboard seed must be bit-identical (0/255)")
        val kbNoCaretMid = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbEnterPlan, kbEnterBase.copy(effect = kbEnterEffNoCaret, elapsedMs = midKeyMs), config)
        }
        assertTrue(
            MagicTextV2TestSupport.countInkPixels(kbRun1) > MagicTextV2TestSupport.countInkPixels(kbNoCaretMid),
            "Typing caret must add visible pixels mid-build",
        )

        // Completion coincides strictly with track end (last char absent at trackEnd-1, full Rest match at trackEnd).
        val kbJustBeforeEnd = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbEnterPlan, kbEnterBase.copy(effect = kbEnterEffNoCaret, elapsedMs = (kbTotalMsA - 1).toFloat()), config)
        }
        assertTrue(
            MagicTextV2TestSupport.countInkPixels(kbJustBeforeEnd) < fullInk,
            "Last character must not appear before track end",
        )
        val kbAtEnd = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbEnterPlan, kbEnterBase.copy(elapsedMs = kbTotalMsA.toFloat()), config)
        }
        assertTrue(
            MagicTextV2TestSupport.compareRgba(restPixels, kbAtEnd, tolerance = 2).maxDelta <= 2,
            "keyboard Enter at trackEnd must match Rest within <= 2/255",
        )

        // 4. Keyboard Exit using REAL SegmentPlanner reverse-backspace schedule!
        val (kbExitSched, kbExitTotalMs) = planRealTextBuildSchedule(
            text = textStr,
            effectId = BuildEffects.KEYBOARD,
            kind = EffectBuildKind.Exit,
            args = mapOf("seed" to JsonPrimitive(42), "charsPerSec" to JsonPrimitive(16.0), "caret" to JsonPrimitive(false)),
        )
        val kbExitEff = EffectCallV2(BuildEffects.KEYBOARD, EffectBuildKind.Exit, EffectArgs(mapOf("caret" to false)))
        val kbExitBase = TextContentV2.Build(styled, PartitionGranularity.Character, kbExitEff, kbExitSched, 0f)
        val kbExitPlan = renderer.planBuild(kbExitBase, measurer, density, config = config)
        assertTrue(
            kbExitPlan.pieceTimings.last().endMs < kbExitPlan.pieceTimings.first().endMs,
            "Planner keyboard Exit must delete rightmost char before leftmost char",
        )

        val kbExitStart = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbExitPlan, kbExitBase.copy(elapsedMs = 0f), config)
        }
        assertTrue(MagicTextV2TestSupport.compareRgba(restPixels, kbExitStart, tolerance = 2).maxDelta <= 2)

        val exitMidMs = kbExitPlan.pieceTimings[kbExitPlan.pieceTimings.size / 2].endMs.toFloat() + 5f
        val kbExitMid = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbExitPlan, kbExitBase.copy(elapsedMs = exitMidMs), config)
        }
        val midSplitX = (partition.wholeLayout.size.width / 2).coerceIn(1, w - 1)
        assertTrue(countInkInXRange(kbExitMid, w, h, 0, midSplitX) > 300, "Left half must remain mid-backspace exit")
        assertEquals(0, countInkInXRange(kbExitMid, w, h, midSplitX + 40, w), "Right half must be deleted mid-backspace exit")

        val kbExitBeforeEnd = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbExitPlan, kbExitBase.copy(elapsedMs = (kbExitTotalMs - 1).toFloat()), config)
        }
        assertTrue(MagicTextV2TestSupport.countInkPixels(kbExitBeforeEnd) > 0, "Leftmost char must still be visible 1 ms before trackEnd")

        val kbExitEnd = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(kbExitPlan, kbExitBase.copy(elapsedMs = kbExitTotalMs.toFloat()), config)
        }
        assertEquals(0, MagicTextV2TestSupport.countInkPixels(kbExitEnd), "keyboard Exit at trackEnd must have 0 ink")

        // 5. Coarsened character build joins member planner slots by UTF-16 range union [minStart..maxEnd]!
        val interTypeface = org.jetbrains.skia.FontMgr.default.makeFromData(
            org.jetbrains.skia.Data.makeFromBytes(SyntheticTtfBuilder.baseInterBytes),
        )!!
        val gF = try {
            org.jetbrains.skia.Font(interTypeface, 16f).use { it.getUTF32Glyph('f'.code).toInt() }
        } finally {
            interTypeface.close()
        }
        val interTypeface2 = org.jetbrains.skia.FontMgr.default.makeFromData(
            org.jetbrains.skia.Data.makeFromBytes(SyntheticTtfBuilder.baseInterBytes),
        )!!
        val (gI, gX) = try {
            org.jetbrains.skia.Font(interTypeface2, 16f).use {
                it.getUTF32Glyph('i'.code).toInt() to it.getUTF32Glyph('x'.code).toInt()
            }
        } finally {
            interTypeface2.close()
        }
        val ccmpBytes = SyntheticTtfBuilder.repackBaseInter(
            lookups = listOf(
                SyntheticTtfBuilder.LookupSpec(
                    type = 4,
                    rawSubtables = listOf(
                        SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                            listOf(listOf(gF, gI) to gX),
                        ),
                    ),
                ),
            ),
            features = listOf(SyntheticTtfBuilder.FeatureSpec("ccmp", listOf(0))),
        )
        val ccmpResolver = DeckFontResolver(
            declarations = listOf(DeckFontDeclaration(family = "SynthCcmp", src = "synthetic/SynthCcmp.ttf", weight = 400)),
            resourceLoader = FontResourceLoader { path ->
                if (path == "synthetic/SynthCcmp.ttf") ccmpBytes else null
            },
            systemFontLookup = SystemFontLookup { null },
        )
        val ccmpConfig = MagicTextV2Config(fontResolver = ccmpResolver)
        val ccmpStyle = ccmpResolver.resolveTextStyle("SynthCcmp", TextStyle(color = Color.White, fontSize = 32.sp, lineHeight = 42.sp))
        val coarsenedText = "fig box"
        val (coarsenSched, coarsenTrackMs) = planRealTextBuildSchedule(
            text = coarsenedText,
            effectId = BuildEffects.DISSOLVE,
            kind = EffectBuildKind.Enter,
            stagger = Stagger(ms = 40),
            durationMs = 200,
        )
        val coarsenBuild = TextContentV2.Build(
            content = StyledTextV2(AnnotatedString(coarsenedText), ccmpStyle, maxWidthPx = 600),
            granularity = PartitionGranularity.Character,
            effect = EffectCallV2(BuildEffects.DISSOLVE, EffectBuildKind.Enter, EffectArgs.Empty, CurveV2.Linear),
            schedule = coarsenSched,
            elapsedMs = coarsenTrackMs.toFloat(),
            characterDeliveryNode = true,
        )
        val coarsenPlan = renderer.planBuild(coarsenBuild, measurer, density, config = ccmpConfig)
        assertTrue(coarsenPlan.partition.admitted)
        // "fig" coarsens to 1 Word piece; "box" remains 3 Character pieces ('b', 'o', 'x') -> 4 total pieces!
        assertEquals(4, coarsenPlan.pieceTimings.size, "Expected 1 coarsened 'fig' Word + 3 'box' Character pieces")
        val figTiming = coarsenPlan.pieceTimings[0]
        assertEquals(PartitionGranularity.Word, figTiming.piece.level)
        val fSlot = assertNotNull(coarsenSched.slotFor("f#0"))
        val gSlot = assertNotNull(coarsenSched.slotFor("g#0"))
        assertEquals(fSlot.startMs, figTiming.startMs, "Coarsened word piece startMs must equal minStart of member char slots")
        assertEquals(gSlot.endMs, figTiming.endMs, "Coarsened word piece endMs must equal maxEnd of member char slots")

        // 6. Reduced motion via real SegmentPlanner -> whole-paragraph fade across both halves
        val (rmSched, rmTotalMs) = planRealTextBuildSchedule(
            text = textStr,
            effectId = BuildEffects.KEYBOARD,
            kind = EffectBuildKind.Enter,
            reducedMotion = true,
        )
        val reducedConfig = MagicTextV2Config(fontResolver = resolver, reducedMotion = true)
        val rmBuild = TextContentV2.Build(styled, PartitionGranularity.Character, kbEnterEffCaret, rmSched, rmTotalMs * 0.5f)
        val rmPlan = renderer.planBuild(rmBuild, measurer, density, config = reducedConfig)
        val reducedMid = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
            drawBuildFrame(rmPlan, rmBuild, reducedConfig)
        }
        assertTrue(countInkInXRange(reducedMid, w, h, 0, midSplitX) > 200)
        assertTrue(countInkInXRange(reducedMid, w, h, midSplitX, w) > 200)
    }

    // -------------------------------------------------------------------------
    // 60-Sample 22.sp <-> 56.sp Size Morph (`<= 0.5 px`, Single-Line + Wrapped Mixed-Style)
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun sizeMorph60SamplesSingleLineAndWrappedMixedStyle22To56SubpixelAccuracy() {
        val resolver = MagicTextV2TestSupport.createBundledFontResolver()
        val density = Density(2f)
        val measurer = MagicTextV2TestSupport.createMeasurer(density)
        val renderer = MagicTextV2Renderer()
        val config = MagicTextV2Config(fontResolver = resolver)

        val report = StringBuilder("\n=== 22.sp <-> 56.sp Size Morph Subpixel Report (Density=2f) ===\n")

        // (A) Single-line 60-sample trajectory + direct handover on Inter AND HankenGrotesk
        for (familyName in listOf("Inter", "HankenGrotesk")) {
            val smallStyle = resolver.resolveTextStyle(familyName, TextStyle(color = Color.White, fontSize = 22.sp, lineHeight = 30.sp))
            val largeStyle = resolver.resolveTextStyle(familyName, TextStyle(color = Color.White, fontSize = 56.sp, lineHeight = 68.sp))
            val fromStyled = StyledTextV2(AnnotatedString("Typography"), smallStyle, maxWidthPx = 1200)
            val toStyled = StyledTextV2(AnnotatedString("Typography"), largeStyle, maxWidthPx = 1200)
            val schedule = PieceScheduleV2(totalDurationMs = 1000, slots = emptyMap())

            val fwdTemplate = TextContentV2.Morph(fromStyled, toStyled, MagicMorphBy.Word, schedule = schedule, elapsedMs = 0f, moveCurve = CurveV2.Linear)
            val revTemplate = TextContentV2.Morph(toStyled, fromStyled, MagicMorphBy.Word, schedule = schedule, elapsedMs = 1000f, moveCurve = CurveV2.Linear)
            val fwdPlan = renderer.planMorph(fwdTemplate, measurer, density, config = config)
            val revPlan = renderer.planMorph(revTemplate, measurer, density, config = config)

            assertEquals(1, fwdPlan.groups.size)
            assertTrue(fwdPlan.groups[0].sizeChanged, "$familyName must detect sizeChanged=true")
            assertNotNull(fwdPlan.groups[0].sizeMorphAsset, "$familyName must create SizeMorphAsset")

            val canvasW = 840
            val canvasH = 260
            val padPx = 24f

            val restSmallRgba = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
                drawContext.transform.translate(padPx, padPx)
                drawText(fwdPlan.fromPartition.wholeLayout)
            }
            val morphStartRgba = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
                drawContext.transform.translate(padPx, padPx)
                drawMorphFrame(fwdPlan, fwdTemplate.copy(elapsedMs = 0f), config)
            }
            val revEndRgba = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
                drawContext.transform.translate(padPx, padPx)
                drawMorphFrame(revPlan, revTemplate.copy(elapsedMs = 1000f), config)
            }

            val restSmallEdges = assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(restSmallRgba, canvasW, canvasH))
            val morphStartEdges = assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(morphStartRgba, canvasW, canvasH))
            val revEndEdges = assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(revEndRgba, canvasW, canvasH))

            val handoverErrFwd = maxEdgeDiff(restSmallEdges, morphStartEdges)
            val handoverErrRev = maxEdgeDiff(restSmallEdges, revEndEdges)

            assertTrue(
                handoverErrFwd <= 0.5,
                "$familyName forward direct handover at 22.sp exceeded 0.5 px: %.3f px".format(handoverErrFwd),
            )
            assertTrue(
                handoverErrRev <= 0.5,
                "$familyName reverse direct handover at 22.sp exceeded 0.5 px: %.3f px".format(handoverErrRev),
            )

            val samples = List(60) { k ->
                val p = k / 59f
                val rgba = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
                    drawContext.transform.translate(padPx, padPx)
                    drawMorphFrame(fwdPlan, fwdTemplate.copy(elapsedMs = p * 1000f), config)
                }
                assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(rgba, canvasW, canvasH))
            }
            val first = samples.first()
            val last = samples.last()

            var maxTrajDev = 0.0
            var worstDetail = ""
            var minPeakGrad = Double.POSITIVE_INFINITY
            var maxBackwardJump = 0.0

            for (k in samples.indices) {
                val p = k / 59.0
                val cur = samples[k]
                val dev = maxOf(
                    abs(cur.left - (first.left + (last.left - first.left) * p)),
                    abs(cur.right - (first.right + (last.right - first.right) * p)),
                    abs(cur.top - (first.top + (last.top - first.top) * p)),
                    abs(cur.bottom - (first.bottom + (last.bottom - first.bottom) * p)),
                )
                if (dev > maxTrajDev) {
                    maxTrajDev = dev
                    worstDetail = "k=$k p=%.3f cur=$cur".format(p)
                }
                if (cur.peakGradient < minPeakGrad) minPeakGrad = cur.peakGradient
                if (k > 0) {
                    val prev = samples[k - 1]
                    val back = max((prev.right - prev.left) - (cur.right - cur.left), (prev.bottom - prev.top) - (cur.bottom - cur.top))
                    if (back > maxBackwardJump) maxBackwardJump = back
                }
            }

            assertTrue(maxTrajDev <= 0.5, "$familyName single-line 60-sample maxDev=%.3f px > 0.5 px ($worstDetail)".format(maxTrajDev))
            assertTrue(maxBackwardJump <= 0.15, "$familyName single-line backward jump=%.3f px".format(maxBackwardJump))
            assertTrue(minPeakGrad >= 0.85, "$familyName single-line minPeakGrad=%.3f".format(minPeakGrad))

            val fwd35 = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
                drawMorphFrame(fwdPlan, fwdTemplate.copy(elapsedMs = 350f), config)
            }
            val rev65 = MagicTextV2TestSupport.renderCanvasToRgba(canvasW, canvasH, density) {
                drawMorphFrame(revPlan, revTemplate.copy(elapsedMs = 650f), config)
            }
            val symDiff = MagicTextV2TestSupport.compareRgba(fwd35, rev65, tolerance = 2)
            assertTrue(symDiff.maxDelta <= 2, "$familyName bidirectional symmetry maxDelta=${symDiff.maxDelta}")

            report.append(
                "  %-14s single-line : handoverFwd=%.3f px, handoverRev=%.3f px, 60-sample maxDev=%.3f px, minPeakGrad=%.3f, symMaxΔ=%d\n"
                    .format(familyName, handoverErrFwd, handoverErrRev, maxTrajDev, minPeakGrad, symDiff.maxDelta),
            )
        }

        // (B) Wrapped, mixed-style multi-line 22.sp <-> 56.sp morph: 60-sample per-line edge trajectory <= 0.5 px + direct handover!
        for (familyName in listOf("Inter", "HankenGrotesk")) {
            val useItalic = familyName == "HankenGrotesk"
            val mixedText = buildAnnotatedString {
                append("Design ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("crisp ") }
                append("wrapped ")
                if (useItalic) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic ") }
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("strong ") }
                }
                append("typography across lines cleanly")
            }

            val smallStyle = resolver.resolveTextStyle(familyName, TextStyle(color = Color.White, fontSize = 22.sp, lineHeight = 38.sp))
            val largeStyle = resolver.resolveTextStyle(familyName, TextStyle(color = Color.White, fontSize = 56.sp, lineHeight = 96.sp))
            // Scale maxWidthPx proportionally (420 -> 1070) so the paragraph wraps onto the same multi-line bands
            // across all 60 samples, allowing exact per-line subpixel edge tracking on EVERY wrapped line!
            val fromWrapped = StyledTextV2(mixedText, smallStyle, maxWidthPx = 420)
            val toWrapped = StyledTextV2(mixedText, largeStyle, maxWidthPx = 1070)
            val schedule = PieceScheduleV2(
                totalDurationMs = 800,
                slots = mapOf(
                    SIZE_SLOT_ADDRESS to PieceSlotV2(SIZE_SLOT_ADDRESS, 0, 800, CurveV2.Linear),
                ),
            )

            val wrappedMorph = TextContentV2.Morph(
                from = fromWrapped,
                to = toWrapped,
                by = MagicMorphBy.Word,
                schedule = schedule,
                elapsedMs = 0f,
                moveCurve = CurveV2.Linear,
                interpolateLayoutSize = true,
            )
            val plan = renderer.planMorph(wrappedMorph, measurer, density, config = config)
            assertTrue(plan.fromPartition.admitted && plan.toPartition.admitted, "$familyName wrapped mixed-style endpoints must admit")
            val fromWL = plan.fromPartition.wholeLayout
            val toWL = plan.toPartition.wholeLayout
            assertTrue(fromWL.lineCount >= 3, "$familyName wrapped small text must span >= 3 lines (got ${fromWL.lineCount})")
            assertEquals(fromWL.lineCount, toWL.lineCount, "$familyName proportionally scaled wrapped text must preserve line count for per-line tracking")
            assertTrue(plan.groups.all { it.kind == DrawableGroupKind.Move && it.sizeChanged && it.sizeMorphAsset != null })

            val w = 1160
            val h = 760
            val wrapPad = 24f

            val restSmall = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
                drawContext.transform.translate(wrapPad, wrapPad)
                drawText(fromWL)
            }
            val morphAtStart = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
                drawContext.transform.translate(wrapPad, wrapPad)
                drawMorphFrame(plan, wrappedMorph.copy(elapsedMs = 0f), config)
            }

            // 1. Direct Handover at 22.sp endpoint on EVERY wrapped line (<= 0.5 px).
            var worstLineHandover = 0.0
            var worstLineDetail = ""
            for (line in 0 until fromWL.lineCount) {
                val yTop = (wrapPad + fromWL.getLineTop(line)).toInt().coerceAtLeast(0)
                val yBot = (wrapPad + fromWL.getLineBottom(line)).toInt().coerceAtMost(h)
                val restLineEdges = assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(restSmall, w, h, yTop, yBot))
                val morphLineEdges = assertNotNull(MagicTextV2TestSupport.measureSubpixelEdges(morphAtStart, w, h, yTop, yBot))
                val err = maxEdgeDiff(restLineEdges, morphLineEdges)
                if (err > worstLineHandover) {
                    worstLineHandover = err
                    worstLineDetail = "line=$line rest=$restLineEdges morph=$morphLineEdges"
                }
            }
            assertTrue(
                worstLineHandover <= 0.5,
                "$familyName wrapped mixed-style direct handover exceeded 0.5 px: ${"%.3f".format(worstLineHandover)} px ($worstLineDetail)",
            )

            // 2. 60-sample per-line edge trajectory check: EVERY wrapped line's left/right/top/bottom edge <= 0.5 px!
            val lineCount = fromWL.lineCount
            val lineSamples = Array(lineCount) { ArrayList<MagicTextV2TestSupport.SubpixelInkEdges>(60) }
            var minWrappedPeakGrad = Double.POSITIVE_INFINITY

            for (k in 0 until 60) {
                val p = k / 59f
                val rgba = MagicTextV2TestSupport.renderCanvasToRgba(w, h, density) {
                    drawContext.transform.translate(wrapPad, wrapPad)
                    drawMorphFrame(plan, wrappedMorph.copy(elapsedMs = p * 800f), config)
                }
                for (line in 0 until lineCount) {
                    val interpTop = fromWL.getLineTop(line) + (toWL.getLineTop(line) - fromWL.getLineTop(line)) * p
                    val interpBot = fromWL.getLineBottom(line) + (toWL.getLineBottom(line) - fromWL.getLineBottom(line)) * p
                    val yTop = (wrapPad + interpTop).toInt().coerceIn(0, h - 1)
                    val yBot = (wrapPad + interpBot).toInt().coerceIn(yTop + 1, h)
                    val edges = assertNotNull(
                        MagicTextV2TestSupport.measureSubpixelEdges(rgba, w, h, yTop, yBot),
                        "$familyName line=$line sample=$k missing ink in band [$yTop..$yBot]",
                    )
                    lineSamples[line].add(edges)
                    if (edges.peakGradient < minWrappedPeakGrad) minWrappedPeakGrad = edges.peakGradient
                }
            }

            var maxWrappedTrajDev = 0.0
            var worstWrappedDetail = ""
            for (line in 0 until lineCount) {
                val ls = lineSamples[line]
                val first = ls.first()
                val last = ls.last()
                for (k in ls.indices) {
                    val p = k / 59.0
                    val cur = ls[k]
                    val dev = maxOf(
                        abs(cur.left - (first.left + (last.left - first.left) * p)),
                        abs(cur.right - (first.right + (last.right - first.right) * p)),
                        abs(cur.top - (first.top + (last.top - first.top) * p)),
                        abs(cur.bottom - (first.bottom + (last.bottom - first.bottom) * p)),
                    )
                    if (dev > maxWrappedTrajDev) {
                        maxWrappedTrajDev = dev
                        worstWrappedDetail = "line=$line k=$k p=%.3f cur=$cur first=$first last=$last".format(p)
                    }
                }
            }
            assertTrue(
                maxWrappedTrajDev <= 0.5,
                "$familyName wrapped mixed-style 60-sample max edge deviation exceeded 0.5 px: %.3f px ($worstWrappedDetail)".format(maxWrappedTrajDev),
            )
            assertTrue(minWrappedPeakGrad >= 0.85, "$familyName wrapped 60-sample sharpness dropped: %.3f".format(minWrappedPeakGrad))

            // 3. Verify @size slot drives deferred TextNode(content = { ... }) measured layout size smoothly!
            val sizes = mutableListOf<IntSize>()
            for (elapsed in listOf(0f, 400f, 800f)) {
                var measured = IntSize.Zero
                val sampleMorph = wrappedMorph.copy(elapsedMs = elapsed)
                val scene = ImageComposeScene(width = 1280, height = 720, density = density) {
                    renderer.TextNode(
                        content = { sampleMorph },
                        modifier = Modifier.onSizeChanged { measured = it },
                        config = config,
                    )
                }
                try {
                    scene.render(0L)
                    sizes += measured
                } finally {
                    scene.close()
                }
            }
            assertTrue(
                sizes[0].width < sizes[1].width && sizes[1].width < sizes[2].width,
                "@size slot must interpolate measured node width strictly between endpoints: $sizes",
            )

            report.append(
                "  %-14s wrapped     : lines=%d, worstLineHandover=%.3f px, 60-sample maxDev=%.3f px, minPeakGrad=%.3f, @size widths=%s\n"
                    .format(familyName, lineCount, worstLineHandover, maxWrappedTrajDev, minWrappedPeakGrad, sizes.map { it.width }),
            )
        }

        println(report.toString())
    }

    private fun readImageRgba(image: SkiaImage): ByteArray {
        val w = image.width
        val h = image.height
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap()
        try {
            bmp.allocPixels(info)
            image.readPixels(bmp, 0, 0)
            return bmp.readPixels(info, w * 4, 0, 0) ?: ByteArray(w * h * 4)
        } finally {
            bmp.close()
        }
    }

    private fun maxEdgeDiff(
        a: MagicTextV2TestSupport.SubpixelInkEdges,
        b: MagicTextV2TestSupport.SubpixelInkEdges,
    ): Double = max(
        max(abs(a.left - b.left), abs(a.right - b.right)),
        max(abs(a.top - b.top), abs(a.bottom - b.bottom)),
    )

    private fun countInkInXRange(rgba: ByteArray, w: Int, h: Int, xStart: Int, xEnd: Int): Int {
        val x0 = xStart.coerceIn(0, w)
        val x1 = xEnd.coerceIn(x0, w)
        var count = 0
        for (y in 0 until h) {
            val rowOff = y * w * 4
            for (x in x0 until x1) {
                if ((rgba[rowOff + x * 4 + 3].toInt() and 0xFF) > 8) count++
            }
        }
        return count
    }

    private fun countNonBackgroundPixelsInRect(
        rgba: ByteArray,
        w: Int,
        h: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        bgR: Int,
        bgG: Int,
        bgB: Int,
    ): Int {
        val cx0 = x0.coerceIn(0, w)
        val cy0 = y0.coerceIn(0, h)
        val cx1 = x1.coerceIn(cx0, w)
        val cy1 = y1.coerceIn(cy0, h)
        var count = 0
        for (y in cy0 until cy1) {
            val rowOff = y * w * 4
            for (x in cx0 until cx1) {
                val p = rowOff + x * 4
                val dr = abs((rgba[p].toInt() and 0xFF) - bgR)
                val dg = abs((rgba[p + 1].toInt() and 0xFF) - bgG)
                val db = abs((rgba[p + 2].toInt() and 0xFF) - bgB)
                if (dr > 6 || dg > 6 || db > 6) count++
            }
        }
        return count
    }
}

/** Mesa / ANGLE software GL renderers: CPU rasterization behind a GL API, not a GPU measurement. */
private val SOFTWARE_GL_RENDERERS = listOf("llvmpipe", "softpipe", "swrast", "SwiftShader", "Software Rasterizer")
