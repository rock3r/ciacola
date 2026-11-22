package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.theme.contrastRatio
import java.nio.file.Path
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

private const val TRANSPARENT_HEX_COLOR: String = "#00000000"

internal data class RealSlideRenderResult(
    val buffer: StagePixelBuffer,
    val glyphBoundsByNodeId: Map<String, Rect>,
)

/**
 * Reused `480x270` (`Density(0.5f)`) real-slide renderer for pixel-fallback contrast audits.
 *
 * Renders the real slide through [buildDocumentStoryboard] in an isolated [RenderSession.forStill]
 * with text glyphs masked to `#00000000`, capturing BOTH genuine rendered backdrop pixels (over real
 * images, gradients, shapes, and live SkSL/Jewel specimens) AND laid-out glyph bounds from Compose
 * `SemanticsActions.GetTextLayoutResult`.
 */
internal class DocumentDeckLintBackdropRenderer(
    private val specimens: DeckSpecimenRegistry,
) : AutoCloseable {

    private data class RealSlideRenderRequest(
        val backdropDocument: DeckDocument,
        val beat: Int,
        val config: DocumentRenderConfig,
    )

    private var pixelScene: ImageComposeScene? = null
    private var pixelBitmap: Bitmap? = null
    private var pixelBuffer: SkiaBitmapPixelBuffer? = null
    private val renderRequestState = mutableStateOf<RealSlideRenderRequest?>(null)
    private val renderEpochState = mutableIntStateOf(0)

    val hasAllocatedBitmap: Boolean
        get() = pixelBitmap != null

    @OptIn(ExperimentalComposeUiApi::class)
    fun renderRealSlideBackdropBuffer(
        document: DeckDocument,
        slide: SlideSpec,
        beat: Int,
        themeName: String,
        deckDir: Path?,
        assetsDir: Path?,
        excludedOverlayNodeIds: Set<String> = emptySet(),
    ): RealSlideRenderResult? {
        val backdropSlide = hideTextGlyphsInSlide(slide, excludedOverlayNodeIds).copy(skipped = false)
        val backdropDoc = document.copy(slides = listOf(backdropSlide))
        val assetsRootDir = (assetsDir ?: deckDir)?.toAbsolutePath()?.toString()
        val fontsDir = deckDir?.toAbsolutePath()?.toString()
        val renderConfig = DocumentRenderConfig(
            themeName = themeName,
            specimens = specimens,
            reducedMotion = true,
            assetsRootDir = assetsRootDir,
            fontsDir = fontsDir,
            baseDensity = 2f,
            isEditMode = false,
        )

        return runCatching {
            val scene = pixelScene ?: ImageComposeScene(
                width = LINT_AUDIT_WIDTH,
                height = LINT_AUDIT_HEIGHT,
                density = Density(0.5f),
                coroutineContext = Dispatchers.Unconfined,
            ) {
                val req = renderRequestState.value
                val epoch = renderEpochState.intValue
                if (req != null) {
                    key(epoch) {
                        val storyboard = remember(req) {
                            buildDocumentStoryboard(
                                session = RenderSession.forStill(req.backdropDocument),
                                config = req.config,
                            )
                        }
                        val animatic = rememberAnimatic(
                            initialIndex = Storyboard.Index(0, req.beat),
                            storyboard = { storyboard },
                        )
                        Box(Modifier.fillMaxSize()) {
                            Easel(animatic, mode = SceneMode.Preview)
                        }
                    }
                }
            }.also { pixelScene = it }

            val bitmap = pixelBitmap ?: Bitmap().apply {
                allocPixels(ImageInfo(LINT_AUDIT_WIDTH, LINT_AUDIT_HEIGHT, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
            }.also { pixelBitmap = it }
            val buffer = pixelBuffer ?: SkiaBitmapPixelBuffer(bitmap).also { pixelBuffer = it }

            renderRequestState.value = RealSlideRenderRequest(
                backdropDocument = backdropDoc,
                beat = beat.coerceIn(0, (backdropSlide.beats.size - 1).coerceAtLeast(0)),
                config = renderConfig,
            )
            renderEpochState.intValue++
            Snapshot.sendApplyNotifications()

            val image = scene.render(0L)
            val ok = try {
                image.readPixels(bitmap)
            } finally {
                image.close()
            }
            if (!ok) return null

            val glyphBounds = extractNodeGlyphBoundsFromSemantics(
                scene = scene,
                stageWidth = document.stage.width.toFloat().coerceAtLeast(1f),
                stageHeight = document.stage.height.toFloat().coerceAtLeast(1f),
            )
            RealSlideRenderResult(buffer = buffer, glyphBoundsByNodeId = glyphBounds)
        }.onFailure {
            runCatching { pixelScene?.close() }
            pixelScene = null
        }.getOrNull()
    }

    /**
     * Samples genuine backdrop pixels strictly inside [sampleRect] from the `480x270` framebuffer
     * and returns the 10th-percentile (worst-case robust) WCAG contrast ratio against [foreground].
     */
    fun sampleWorstBackdropContrastRatio(
        buffer: StagePixelBuffer,
        sampleRect: Rect,
        stageWidth: Float,
        stageHeight: Float,
        foreground: Color,
    ): Float? {
        val bw = buffer.width
        val bh = buffer.height
        if (bw <= 0 || bh <= 0) return null

        val fitScale = min(bw / stageWidth.coerceAtLeast(1f), bh / stageHeight.coerceAtLeast(1f))
        val offsetX = (bw - stageWidth * fitScale) * 0.5f
        val offsetY = (bh - stageHeight * fitScale) * 0.5f

        val rawLeft = (offsetX + sampleRect.left * fitScale).roundToInt()
        val rawTop = (offsetY + sampleRect.top * fitScale).roundToInt()
        val rawRight = (offsetX + sampleRect.right * fitScale).roundToInt()
        val rawBottom = (offsetY + sampleRect.bottom * fitScale).roundToInt()
        if (rawRight < 0 || rawBottom < 0 || rawLeft >= bw || rawTop >= bh) return null

        val insetX = if (rawRight - rawLeft >= 4) 1 else 0
        val insetY = if (rawBottom - rawTop >= 4) 1 else 0
        val minX = (rawLeft + insetX).coerceIn(0, bw - 1)
        val maxX = (rawRight - insetX).coerceIn(minX, bw - 1)
        val minY = (rawTop + insetY).coerceIn(0, bh - 1)
        val maxY = (rawBottom - insetY).coerceIn(minY, bh - 1)

        val cols = 9
        val rows = 5
        val ratios = FloatArray(cols * rows)
        var idx = 0
        for (iy in 0 until rows) {
            val y = if (rows == 1) minY else minY + (maxY - minY) * iy / (rows - 1)
            for (ix in 0 until cols) {
                val x = if (cols == 1) minX else minX + (maxX - minX) * ix / (cols - 1)
                val argb = buffer.getArgb(x, y)
                val bg = Color(
                    red = ((argb ushr 16) and 0xFF) / 255f,
                    green = ((argb ushr 8) and 0xFF) / 255f,
                    blue = (argb and 0xFF) / 255f,
                    alpha = 1f,
                )
                val effFg = blendOver(foreground, bg)
                ratios[idx++] = contrastRatio(effFg, bg)
            }
        }
        ratios.sort()
        return ratios[(ratios.size / 10).coerceIn(0, ratios.lastIndex)]
    }

    override fun close() {
        runCatching { pixelScene?.close() }
        pixelScene = null
        runCatching { pixelBitmap?.close() }
        pixelBitmap = null
        pixelBuffer = null
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun extractNodeGlyphBoundsFromSemantics(
        scene: ImageComposeScene,
        stageWidth: Float,
        stageHeight: Float,
    ): Map<String, Rect> {
        val fitScale = min(LINT_AUDIT_WIDTH / stageWidth, LINT_AUDIT_HEIGHT / stageHeight)
            .coerceAtLeast(0.0001f)
        val offsetX = (LINT_AUDIT_WIDTH - stageWidth * fitScale) * 0.5f
        val offsetY = (LINT_AUDIT_HEIGHT - stageHeight * fitScale) * 0.5f

        fun toStageRect(rootPx: Rect): Rect = Rect(
            left = ((rootPx.left - offsetX) / fitScale).coerceIn(0f, stageWidth),
            top = ((rootPx.top - offsetY) / fitScale).coerceIn(0f, stageHeight),
            right = ((rootPx.right - offsetX) / fitScale).coerceIn(0f, stageWidth),
            bottom = ((rootPx.bottom - offsetY) / fitScale).coerceIn(0f, stageHeight),
        )

        val result = HashMap<String, Rect>()
        val layouts = ArrayList<TextLayoutResult>(2)

        fun walk(node: SemanticsNode, currentDocNodeId: String?) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            val activeNodeId = if (tag != null && tag.startsWith("doc-node-")) {
                tag.removePrefix("doc-node-")
            } else {
                currentDocNodeId
            }

            if (activeNodeId != null) {
                val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
                if (action != null) {
                    layouts.clear()
                    action.action?.invoke(layouts)
                    val nodeBox = node.boundsInRoot
                    val layout = layouts.firstOrNull()
                    if (layout != null && layout.lineCount > 0 && nodeBox.width > 0.5f && nodeBox.height > 0.5f) {
                        var minL = Float.POSITIVE_INFINITY
                        var minT = Float.POSITIVE_INFINITY
                        var maxR = Float.NEGATIVE_INFINITY
                        var maxB = Float.NEGATIVE_INFINITY
                        for (line in 0 until layout.lineCount) {
                            minL = min(minL, layout.getLineLeft(line))
                            minT = min(minT, layout.getLineTop(line))
                            maxR = kotlin.math.max(maxR, layout.getLineRight(line))
                            maxB = kotlin.math.max(maxB, layout.getLineBottom(line))
                        }
                        if (maxR > minL && maxB > minT) {
                            val layoutW = layout.size.width.toFloat().coerceAtLeast(1f)
                            val layoutH = layout.size.height.toFloat().coerceAtLeast(1f)
                            val sx = nodeBox.width / layoutW
                            val sy = nodeBox.height / layoutH
                            val glyphRootRect = Rect(
                                left = nodeBox.left + minL * sx,
                                top = nodeBox.top + minT * sy,
                                right = nodeBox.left + maxR * sx,
                                bottom = nodeBox.top + maxB * sy,
                            )
                            val stageRect = toStageRect(glyphRootRect)
                            if (stageRect.width > 1f && stageRect.height > 1f) {
                                val prev = result[activeNodeId]
                                result[activeNodeId] = if (prev == null) stageRect else Rect(
                                    left = min(prev.left, stageRect.left),
                                    top = min(prev.top, stageRect.top),
                                    right = kotlin.math.max(prev.right, stageRect.right),
                                    bottom = kotlin.math.max(prev.bottom, stageRect.bottom),
                                )
                            }
                        }
                    }
                }
            }

            for (child in node.children) {
                walk(child, activeNodeId)
            }
        }

        for (owner in scene.semanticsOwners) {
            walk(owner.unmergedRootSemanticsNode, null)
        }
        return result
    }

    private fun hideTextGlyphsInSlide(
        slide: SlideSpec,
        excludedOverlayNodeIds: Set<String>,
    ): SlideSpec =
        slide.copy(nodes = slide.nodes.map { hideTextGlyphsInNode(it, excludedOverlayNodeIds) })

    private fun hideTextGlyphsInNode(
        node: NodeSpec,
        excludedOverlayNodeIds: Set<String>,
    ): NodeSpec {
        val hideWholeNode = node.id in excludedOverlayNodeIds
        val maskedText = node.text?.let { tp ->
            val maskedVal = when (val v = tp.value) {
                is RichTextValue.Plain -> v
                is RichTextValue.Runs -> RichTextValue.Runs(
                    v.runs.map { run -> run.copy(color = TRANSPARENT_HEX_COLOR) },
                )
            }
            tp.copy(value = maskedVal, color = TRANSPARENT_HEX_COLOR)
        }
        val maskedBuilds = if (node.builds.isEmpty()) {
            node.builds
        } else {
            node.builds.map { build ->
                if (build is Build.Change) {
                    val patched = LinkedHashMap(build.set)
                    var modified = false
                    if (hideWholeNode && "opacity" in patched) {
                        patched["opacity"] = JsonPrimitive(0.0)
                        modified = true
                    }
                    val textObj = build.set["text"] as? JsonObject
                    if (textObj != null) {
                        val patchedTextObj = JsonObject(
                            LinkedHashMap(textObj).apply {
                                put("color", JsonPrimitive(TRANSPARENT_HEX_COLOR))
                                val valEl = get("value")
                                if (valEl is JsonArray) {
                                    put(
                                        "value",
                                        JsonArray(
                                            valEl.map { elem ->
                                                if (elem is JsonObject) {
                                                    JsonObject(
                                                        LinkedHashMap(elem).apply {
                                                            put("color", JsonPrimitive(TRANSPARENT_HEX_COLOR))
                                                        },
                                                    )
                                                } else {
                                                    elem
                                                }
                                            },
                                        ),
                                    )
                                }
                            },
                        )
                        patched["text"] = patchedTextObj
                        modified = true
                    }
                    if (modified) build.copy(set = JsonObject(patched)) else build
                } else {
                    build
                }
            }
        }
        return node.copy(
            opacity = if (hideWholeNode) 0.0 else node.opacity,
            text = maskedText,
            builds = maskedBuilds,
            stack = node.stack?.let { c -> c.copy(children = c.children.map { hideTextGlyphsInNode(it, excludedOverlayNodeIds) }) },
            row = node.row?.let { c -> c.copy(children = c.children.map { hideTextGlyphsInNode(it, excludedOverlayNodeIds) }) },
            column = node.column?.let { c -> c.copy(children = c.children.map { hideTextGlyphsInNode(it, excludedOverlayNodeIds) }) },
            loupe = node.loupe?.let { l -> l.copy(children = l.children.map { hideTextGlyphsInNode(it, excludedOverlayNodeIds) }) },
        )
    }

    private fun blendOver(fg: Color, bg: Color): Color {
        val a = fg.alpha.coerceIn(0f, 1f)
        if (a >= OPAQUE_ALPHA_EPSILON) return fg.copy(alpha = 1f)
        val inv = 1f - a
        return Color(
            red = fg.red * a + bg.red * inv,
            green = fg.green * a + bg.green * inv,
            blue = fg.blue * a + bg.blue * inv,
            alpha = 1f,
        )
    }
}
