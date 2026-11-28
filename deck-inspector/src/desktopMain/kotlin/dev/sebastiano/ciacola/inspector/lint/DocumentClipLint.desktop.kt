package dev.sebastiano.ciacola.inspector.lint

import dev.sebastiano.ciacola.animation.AnimationBudget
import dev.sebastiano.ciacola.animation.AnimationInfo
import dev.sebastiano.ciacola.animation.probeAnimation
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Animated image checks that need the asset bytes: playback builds or clip fields on a missing or
 * non-animated source, and animations whose decode budget would leave only the first frame.
 */
internal object DocumentClipLint {
    /** What lint knows about an image source. */
    sealed interface ClipSource {
        data object Missing : ClipSource
        data object Still : ClipSource
        data class Animated(val info: AnimationInfo) : ClipSource
    }

    /** Probes keyed by file path, size and modification time (or classpath entry), so a lint pass reads each clip once. */
    private val probeCache = ConcurrentHashMap<String, ClipSource>()

    /**
     * Probes a deck's image sources: the file [onDisk] resolves, else the classpath copy that
     * packaged decks ship with. Missing sources are not cached, so a file added later is found.
     */
    fun assetProbe(deckId: String, onDisk: (String) -> Path?): (String) -> ClipSource = { src ->
        val path = onDisk(src)
        val key = if (path != null) {
            runCatching { "$path|${Files.size(path)}|${Files.getLastModifiedTime(path).toMillis()}" }.getOrNull()
        } else {
            "classpath:$deckId|$src"
        }
        key?.let(probeCache::get) ?: run {
            val bytes = path?.let { runCatching { Files.readAllBytes(it) }.getOrNull() } ?: readClasspathAsset(deckId, src)
            val source = when {
                bytes == null -> ClipSource.Missing
                else -> probeAnimation(bytes)?.let(ClipSource::Animated) ?: ClipSource.Still
            }
            if (key != null && source != ClipSource.Missing) probeCache[key] = source
            source
        }
    }

    fun collect(
        slide: SlideSpec,
        slideIndex: Int,
        out: MutableMap<String, DocumentLintIssue>,
        probe: (String) -> ClipSource,
    ) {
        for (node in DocumentThemeResolver.flattenNodes(slide.nodes)) {
            val image = node.image ?: continue
            val hasPlaybackBuilds = node.builds.any { it is Build.Playback }
            val usesClipFields = hasPlaybackBuilds || image.copy(fit = null, tint = null) != ImagePayload(image.src)
            fun warn(code: String, title: String, detail: String) {
                val issue = DocumentLintIssue(
                    id = "clip:${code.lowercase(Locale.US)}:${slide.id}:${node.id}",
                    code = code,
                    severity = LintSeverity.Warning,
                    source = DocumentLintSource.StaticValidation,
                    slideIndex = slideIndex,
                    slideId = slide.id,
                    nodeId = node.id,
                    title = title,
                    detail = detail,
                )
                out.putIfAbsent(issue.id, issue)
            }
            // The deck only animates .gif/.webp sources, so nothing else is read.
            val animatable = image.src.endsWith(".gif", ignoreCase = true) || image.src.endsWith(".webp", ignoreCase = true)
            val source = if (animatable) probe(image.src) else ClipSource.Still
            if (source == ClipSource.Missing) {
                if (hasPlaybackBuilds) {
                    warn(
                        "CLIP_SOURCE_MISSING",
                        "Playback builds target a missing image",
                        "Node '${node.id}' has playback builds but '${image.src}' was not found.",
                    )
                }
                continue
            }
            if (source !is ClipSource.Animated) {
                if (usesClipFields) {
                    warn(
                        "CLIP_SOURCE_NOT_ANIMATED",
                        "Playback settings on a still image",
                        "Node '${node.id}' sets playback builds or clip fields, but '${image.src}' is not an animated GIF/WebP.",
                    )
                }
                continue
            }
            val info = source.info
            val budget = AnimationBudget(maxPixels = image.maxPixels?.takeIf { it > 0 } ?: AnimationBudget().maxPixels)
            if (info.width > budget.maxSidePx || info.height > budget.maxSidePx) {
                warn(
                    "CLIP_OVER_BUDGET",
                    "Animated image exceeds its decode budget",
                    "'${image.src}' is ${info.width}x${info.height}, over the ${budget.maxSidePx} px side limit for animation; " +
                        "it will show only its first frame. Scale the clip down.",
                )
            } else if (info.totalPixels > budget.maxPixels) {
                warn(
                    "CLIP_OVER_BUDGET",
                    "Animated image exceeds its decode budget",
                    "'${image.src}' is ${info.width}x${info.height} with ${info.frameCount} frames (${info.totalPixels} pixels, " +
                        "budget ${budget.maxPixels}); it will show only its first frame. Set image.maxPixels to at least ${info.totalPixels}.",
                )
            }
        }
    }

    fun readClasspathAsset(deckId: String, src: String): ByteArray? {
        val clean = src.trim().removePrefix("/")
        if (clean.isEmpty()) return null
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            DocumentClipLint::class.java.classLoader,
        ).distinct()
        for (cp in listOf("decks/$deckId.assets/$clean", "$deckId.assets/$clean", clean)) {
            for (loader in loaders) {
                loader.getResourceAsStream(cp)?.use { return it.readBytes() }
            }
        }
        return null
    }
}
