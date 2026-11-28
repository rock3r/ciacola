package dev.sebastiano.ciacola.document.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.sebastiano.ciacola.animation.AnimationBudget
import dev.sebastiano.ciacola.animation.AnimationFrame
import dev.sebastiano.ciacola.animation.AnimationInfo
import dev.sebastiano.ciacola.animation.AnimationTooLargeException
import dev.sebastiano.ciacola.animation.decodeAnimatedFrames
import dev.sebastiano.ciacola.animation.probeAnimation as probeAnimationBytes
import java.io.File
import java.util.Collections
import java.util.Optional
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext

/**
 * Loads and caches raster (PNG/JPG/WebP) and vector (`.svg`) image assets for `<deckId>.assets/`
 * from an explicit [assetsRootDir], `-Dciacola.decks.dir`, or JVM classpath resources, plus the
 * decoded frames of animated GIF/WebP assets.
 */
internal object DocumentAssetLoader {
    private const val MAX_CACHE_ENTRIES = 128

    private val painterCache: MutableMap<String, Painter> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Painter>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Painter>?): Boolean =
                size > MAX_CACHE_ENTRIES
        },
    )

    private val bitmapCache: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                size > MAX_CACHE_ENTRIES
        },
    )

    private const val MAX_ANIMATION_CACHE_ENTRIES = 4

    private val animationCache: MutableMap<String, Optional<List<AnimationFrame>>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Optional<List<AnimationFrame>>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Optional<List<AnimationFrame>>>?): Boolean =
                size > MAX_ANIMATION_CACHE_ENTRIES
        },
    )

    /** One lock per animation cache key, so two mounts asking for the same clip decode it once. */
    private val animationDecodeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private val animationInfoCache: MutableMap<String, Optional<AnimationInfo>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Optional<AnimationInfo>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Optional<AnimationInfo>>?): Boolean =
                size > MAX_CACHE_ENTRIES
        },
    )

    fun loadPainter(
        deckId: String,
        src: String,
        assetsRootDir: String? = null,
    ): Painter? {
        val clean = src.trim().removePrefix("/")
        if (clean.isEmpty()) return null
        val cacheKey = "${assetsRootDir.orEmpty()}|$deckId|$clean"
        painterCache[cacheKey]?.let { return it }

        val bytes = readAssetBytes(deckId, clean, assetsRootDir) ?: return null
        val painter: Painter = if (clean.endsWith(".svg", ignoreCase = true)) {
            createSvgPainter(bytes) ?: return null
        } else {
            val bitmap = decodeRasterBitmap(bytes) ?: return null
            bitmapCache[cacheKey] = bitmap
            BitmapPainter(bitmap)
        }
        painterCache[cacheKey] = painter
        return painter
    }

    fun loadImageBitmap(
        deckId: String,
        src: String,
        assetsRootDir: String? = null,
    ): ImageBitmap? {
        val clean = src.trim().removePrefix("/")
        if (clean.isEmpty() || clean.endsWith(".svg", ignoreCase = true)) return null
        val cacheKey = "${assetsRootDir.orEmpty()}|$deckId|$clean"
        bitmapCache[cacheKey]?.let { return it }

        val bytes = readAssetBytes(deckId, clean, assetsRootDir) ?: return null
        val bitmap = decodeRasterBitmap(bytes) ?: return null
        bitmapCache[cacheKey] = bitmap
        return bitmap
    }

    /**
     * Header-only probe of an animated GIF/WebP asset (`null` for stills, other formats, and
     * missing assets). Only `.gif` and `.webp` sources are read, so other images stay untouched.
     */
    fun probeAnimation(deckId: String, src: String, assetsRootDir: String? = null): AnimationInfo? {
        val clean = src.trim().removePrefix("/")
        if (!isAnimatableSource(clean)) return null
        val cacheKey = "${assetsRootDir.orEmpty()}|$deckId|$clean"
        return animationInfoCache.getOrPut(cacheKey) {
            Optional.ofNullable(readAssetBytes(deckId, clean, assetsRootDir)?.let(::probeAnimationBytes))
        }.orElse(null)
    }

    /**
     * Decodes every frame of an animated asset within [maxPixels] (the library default when
     * `null`). Returns `null` for stills, missing assets, and animations over the budget, which then
     * render as their first frame. Synchronous: call it off the UI thread for large clips.
     */
    fun loadAnimation(
        deckId: String,
        src: String,
        assetsRootDir: String? = null,
        maxPixels: Long? = null,
    ): List<AnimationFrame>? {
        val cacheKey = animationCacheKey(deckId, src, assetsRootDir, maxPixels) ?: return null
        animationCache[cacheKey]?.let { return it.orElse(null) }
        synchronized(animationDecodeLocks.computeIfAbsent(cacheKey) { Any() }) {
            animationCache[cacheKey]?.let { return it.orElse(null) }
            return decodeAnimation(deckId, src.trim().removePrefix("/"), assetsRootDir, cacheKey, maxPixels)
        }
    }

    /** Frames [loadAnimation] already decoded, without reading or decoding anything. */
    fun cachedAnimation(
        deckId: String,
        src: String,
        assetsRootDir: String? = null,
        maxPixels: Long? = null,
    ): List<AnimationFrame>? =
        animationCacheKey(deckId, src, assetsRootDir, maxPixels)?.let { animationCache[it]?.orElse(null) }

    private fun animationCacheKey(deckId: String, src: String, assetsRootDir: String?, maxPixels: Long?): String? {
        val clean = src.trim().removePrefix("/")
        if (!isAnimatableSource(clean)) return null
        return "${assetsRootDir.orEmpty()}|$deckId|$clean|${budgetFor(maxPixels).maxPixels}"
    }

    private fun budgetFor(maxPixels: Long?): AnimationBudget =
        maxPixels?.takeIf { it > 0 }?.let { AnimationBudget(maxPixels = it) } ?: AnimationBudget()

    private fun decodeAnimation(
        deckId: String,
        clean: String,
        assetsRootDir: String?,
        cacheKey: String,
        maxPixels: Long?,
    ): List<AnimationFrame>? {
        val budget = budgetFor(maxPixels)
        val bytes = readAssetBytes(deckId, clean, assetsRootDir) ?: return null
        val frames = try {
            decodeAnimatedFrames(bytes, budget)
        } catch (e: InterruptedException) {
            throw e
        } catch (_: AnimationTooLargeException) {
            null
        } catch (_: java.io.IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        animationCache[cacheKey] = Optional.ofNullable(frames)
        return frames
    }

    private fun isAnimatableSource(src: String): Boolean =
        src.endsWith(".gif", ignoreCase = true) || src.endsWith(".webp", ignoreCase = true)

    fun parseContentScale(fit: String?): ContentScale = when (fit?.lowercase()) {
        "cover" -> ContentScale.Crop
        "fill" -> ContentScale.FillBounds
        "fitwidth" -> ContentScale.FillWidth
        "fitheight" -> ContentScale.FillHeight
        "none" -> ContentScale.None
        else -> ContentScale.Fit
    }

    private fun readAssetBytes(deckId: String, src: String, assetsRootDir: String?): ByteArray? {
        val candidates = buildList {
            if (!assetsRootDir.isNullOrBlank()) {
                val root = File(assetsRootDir)
                add(File(root, "$deckId.assets/$src"))
                add(File(root, src))
                if (root.name.endsWith(".assets")) {
                    root.parentFile?.let { parent ->
                        add(File(parent, "$deckId.assets/$src"))
                        add(File(parent, src))
                    }
                }
            }
            val decksProp = System.getProperty("ciacola.decks.dir")
            if (!decksProp.isNullOrBlank()) {
                val decksRoot = File(decksProp)
                add(File(decksRoot, "$deckId.assets/$src"))
                add(File(decksRoot, src))
            }
            val directFile = File(src)
            if (directFile.isAbsolute) {
                add(directFile)
            }
        }
        for (file in candidates) {
            if (file.isFile) {
                return runCatching { file.readBytes() }.getOrNull()
            }
        }

        val cpCandidates = listOf(
            "decks/$deckId.assets/$src",
            "$deckId.assets/$src",
            src,
        )
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            DocumentAssetLoader::class.java.classLoader,
        ).distinct()
        for (cp in cpCandidates) {
            for (loader in loaders) {
                val bytes = loader.getResourceAsStream(cp)?.use { it.readBytes() }
                if (bytes != null) return bytes
            }
        }
        return null
    }

    private fun decodeRasterBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
        SkiaImage.makeFromEncoded(bytes).use { img -> img.toComposeImageBitmap() }
    }.getOrNull()

    private fun createSvgPainter(bytes: ByteArray): Painter? = runCatching {
        val data = Data.makeFromBytes(bytes)
        val dom = SVGDOM(data)
        val root = dom.root
        val intrinsic = if (root != null) {
            val viewBox = root.viewBox
            if (viewBox != null && viewBox.width > 0f && viewBox.height > 0f) {
                Size(viewBox.width, viewBox.height)
            } else {
                val resolved = root.getIntrinsicSize(SVGLengthContext(1920f, 1080f))
                if (resolved.x > 0f && resolved.y > 0f) {
                    Size(resolved.x, resolved.y)
                } else {
                    Size(256f, 256f)
                }
            }
        } else {
            Size(256f, 256f)
        }
        SkiaSvgPainter(dom, intrinsic)
    }.getOrNull()
}

/**
 * Compose [Painter] backed by Skia's [SVGDOM] supporting arbitrary target sizing and tint filters.
 */
private class SkiaSvgPainter(
    private val dom: SVGDOM,
    override val intrinsicSize: Size,
) : Painter() {
    private var currentAlpha: Float = 1f
    private var currentColorFilter: ColorFilter? = null

    override fun applyAlpha(alpha: Float): Boolean {
        currentAlpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        currentColorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val needsLayer = currentAlpha < 0.999f || currentColorFilter != null
            if (needsLayer) {
                val paint = SkiaPaint().apply {
                    setAlphaf(currentAlpha.coerceIn(0f, 1f))
                    currentColorFilter?.let { cf ->
                        colorFilter = cf.asSkiaColorFilter()
                    }
                }
                try {
                    native.saveLayer(SkiaRect.makeWH(w, h), paint)
                    try {
                        renderDom(native, w, h)
                    } finally {
                        native.restore()
                    }
                } finally {
                    paint.close()
                }
            } else {
                native.save()
                try {
                    renderDom(native, w, h)
                } finally {
                    native.restore()
                }
            }
        }
    }

    private fun renderDom(native: org.jetbrains.skia.Canvas, w: Float, h: Float) {
        synchronized(dom) {
            val iw = intrinsicSize.width
            val ih = intrinsicSize.height
            if (iw > 0f && ih > 0f) {
                dom.setContainerSize(iw, ih)
                native.scale(w / iw, h / ih)
            } else {
                dom.setContainerSize(w, h)
            }
            dom.render(native)
        }
    }
}

private fun ColorFilter.asSkiaColorFilter(): org.jetbrains.skia.ColorFilter? = runCatching {
    // Compose Desktop ColorFilter exposes nativeColorFilter via Skiko bridge.
    val field = this::class.java.declaredFields.firstOrNull {
        org.jetbrains.skia.ColorFilter::class.java.isAssignableFrom(it.type)
    } ?: return null
    field.isAccessible = true
    field.get(this) as? org.jetbrains.skia.ColorFilter
}.getOrNull()
