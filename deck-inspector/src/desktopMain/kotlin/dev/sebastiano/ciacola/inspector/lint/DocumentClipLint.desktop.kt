package dev.sebastiano.ciacola.inspector.lint

import dev.sebastiano.ciacola.animation.AnimationBudget
import dev.sebastiano.ciacola.animation.probeAnimation
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import java.util.Locale

/**
 * Animated image checks that need the asset bytes: playback builds or clip fields on a missing or
 * non-animated source, and animations whose decode budget would leave only the first frame.
 */
internal object DocumentClipLint {
    fun collect(
        slide: SlideSpec,
        slideIndex: Int,
        out: MutableMap<String, DocumentLintIssue>,
        readAsset: (String) -> ByteArray?,
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
            val bytes = readAsset(image.src)
            if (bytes == null) {
                if (hasPlaybackBuilds) {
                    warn(
                        "CLIP_SOURCE_MISSING",
                        "Playback builds target a missing image",
                        "Node '${node.id}' has playback builds but '${image.src}' was not found.",
                    )
                }
                continue
            }
            val info = probeAnimation(bytes)
            if (info == null) {
                if (usesClipFields) {
                    warn(
                        "CLIP_SOURCE_NOT_ANIMATED",
                        "Playback settings on a still image",
                        "Node '${node.id}' sets playback builds or clip fields, but '${image.src}' is not an animated GIF/WebP.",
                    )
                }
                continue
            }
            val budget = AnimationBudget(maxPixels = image.maxPixels?.takeIf { it > 0 } ?: AnimationBudget().maxPixels)
            if (info.totalPixels > budget.maxPixels || info.width > budget.maxSidePx || info.height > budget.maxSidePx) {
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
