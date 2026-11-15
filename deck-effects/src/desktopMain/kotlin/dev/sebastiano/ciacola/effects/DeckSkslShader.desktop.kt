package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.inspector.LocalActiveBeatIndex
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageParentElementId
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.ModifierLocalStageAlpha
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import java.util.Collections
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader as SkiaShader

const val MAX_COMPILED_SKSL_CACHE_ENTRIES = 64

internal object CompiledDeckSkslCache {
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Result<RuntimeEffect>>(MAX_COMPILED_SKSL_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<RuntimeEffect>>): Boolean =
            size > MAX_COMPILED_SKSL_CACHE_ENTRIES
    }

    fun getOrCompile(sksl: String): Result<RuntimeEffect> = synchronized(lock) {
        cache[sksl] ?: runCatching { RuntimeEffect.makeForShader(sksl) }.also { compiled ->
            cache[sksl] = compiled
        }
    }

    fun sizeForTesting(): Int = synchronized(lock) { cache.size }
}

private val loggedUnknownShaderUniforms: MutableSet<String> = Collections.synchronizedSet(HashSet())

private class CachedShaderHolder(val effect: RuntimeEffect) {
    val builder = RuntimeShaderBuilder(effect)
    var lastSize: Size = Size.Unspecified
    var lastUniforms: Map<String, DeckShaderUniformValue>? = null
    var skiaShader: SkiaShader? = null
    var brush: ShaderBrush? = null

    fun obtainBrush(
        hasResolution: Boolean,
        drawSize: Size,
        uniforms: Map<String, DeckShaderUniformValue>,
        shaderId: String,
        onUniformError: (String) -> Unit,
    ): ShaderBrush {
        val existingBrush = brush
        if (existingBrush != null && lastSize == drawSize && lastUniforms == uniforms) {
            return existingBrush
        }
        if (hasResolution) {
            builder.uniform("iResolution", drawSize.width, drawSize.height)
        }
        for ((name, value) in uniforms) {
            try {
                when (value) {
                    is DeckShaderUniformValue.FloatValue -> builder.uniform(name, value.value)
                    is DeckShaderUniformValue.Float2Value -> builder.uniform(name, value.x, value.y)
                    is DeckShaderUniformValue.Float3Value -> builder.uniform(name, value.x, value.y, value.z)
                    is DeckShaderUniformValue.FloatArrayValue -> builder.uniform(name, value.values.toFloatArray())
                }
            } catch (err: Throwable) {
                val msg = "Uniform '$name' failed on shader '$shaderId': ${err.message ?: err::class.simpleName}"
                if (loggedUnknownShaderUniforms.add("$shaderId::$name")) {
                    System.err.println(msg)
                }
                onUniformError(msg)
            }
        }
        val previous = skiaShader
        val nextSkiaShader = builder.makeShader()
        val nextBrush = ShaderBrush(nextSkiaShader.asComposeShader())
        skiaShader = nextSkiaShader
        brush = nextBrush
        lastSize = drawSize
        lastUniforms = uniforms
        previous?.close()
        return nextBrush
    }

    fun close() {
        skiaShader?.close()
        skiaShader = null
        brush = null
        lastUniforms = null
        builder.close()
    }
}

/**
 * Compiles [sksl] via Skia [RuntimeEffect.makeForShader] and caches the resulting [Result] keyed
 * by source string (bounded to [MAX_COMPILED_SKSL_CACHE_ENTRIES] LRU entries) so repeated
 * compositions reuse the same compiled [RuntimeEffect] instance.
 */
fun compileOrGetSkslEffect(sksl: String): Result<RuntimeEffect> =
    CompiledDeckSkslCache.getOrCompile(sksl)

private val SKSL_BLOCK_COMMENT_REGEX = Regex("""/\*[\s\S]*?\*/""")
private val SKSL_LINE_COMMENT_REGEX = Regex("""//[^\r\n]*""")
private val SKSL_UNIFORM_DECLARATION_REGEX = Regex("""\buniform\s+(?:(?:highp|mediump|lowp)\s+)?[A-Za-z_]\w*\s+([^;]+);""")
private val SKSL_UNIFORM_DECLARATOR_REGEX = Regex("""^([A-Za-z_]\w*)(?:\s*\[[^\]]*\])*$""")

/**
 * Extracts all declared SkSL `uniform` variable names from [sksl], supporting layout/precision
 * qualifiers (`layout(color) uniform ...`), fixed-size arrays (`uniform float a[4];`), and
 * comma-separated declarator lists (`uniform float a, b[2];`) while ignoring comments.
 */
fun parseDeclaredSkslUniformNames(sksl: String): Set<String> {
    val stripped = sksl
        .replace(SKSL_BLOCK_COMMENT_REGEX, " ")
        .replace(SKSL_LINE_COMMENT_REGEX, " ")
    val names = HashSet<String>()
    for (match in SKSL_UNIFORM_DECLARATION_REGEX.findAll(stripped)) {
        for (rawPart in match.groupValues[1].split(',')) {
            val ident = SKSL_UNIFORM_DECLARATOR_REGEX.find(rawPart.trim())?.groupValues?.get(1)
            if (!ident.isNullOrEmpty()) {
                names.add(ident)
            }
        }
    }
    return names
}

/** Current number of compiled [RuntimeEffect] entries retained in the bounded LRU shader cache. */
fun compiledSkslCacheSize(): Int = CompiledDeckSkslCache.sizeForTesting()

actual fun validateSkslShader(sksl: String): String? =
    compileOrGetSkslEffect(sksl).exceptionOrNull()?.let { err ->
        err.message ?: err::class.simpleName ?: "Unknown SkSL compilation failure"
    }

@Composable
actual fun DeckSkslShader(
    id: String,
    sksl: String,
    modifier: Modifier,
    label: String,
    fallbackColor: Color,
    overlay: @Composable BoxScope.() -> Unit,
    uniforms: @Composable DeckShaderUniformScope.() -> Unit,
) {
    val overrides = LocalElementOverrides.current
    val patch = overrides.patchFor(id)
    val dragPreview = LocalStageDragPreview.current
    val parentElementId = LocalStageParentElementId.current
    val registry = LocalStageInspectionRegistry.current
    val anchorRegistry = LocalStageAnchorRegistry.current
    val sceneCtx = LocalActiveSceneTimeline.current
    val beatIndex = LocalActiveBeatIndex.current
    val overviewOpen = LocalOverviewOpen.current
    val rootHolder = LocalStageRootCoordinates.current
    val effectiveBg = effectiveBackgroundColor()

    val uniformScope = remember(id) { DeckShaderUniformScope(id, overrides) }
    uniformScope.overrides = overrides
    uniformScope.uniforms()
    val snapshotUniforms = LinkedHashMap(uniformScope.boundValues)
    val shaderUniformSpecs = uniformScope.declaredSpecs
    LocalShaderUniformObserver.current?.invoke(id, snapshotUniforms)

    val compileResult = remember(sksl) { compileOrGetSkslEffect(sksl) }
    val effect = compileResult.getOrNull()
    val compileError = compileResult.exceptionOrNull()?.let { err ->
        err.message ?: err::class.simpleName ?: "Unknown SkSL compilation failure"
    }
    val declaredUniformNames = remember(sksl) { parseDeclaredSkslUniformNames(sksl) }
    val undeclaredUniformError = remember(id, effect, snapshotUniforms.keys) {
        if (effect == null) {
            null
        } else {
            val undeclared = snapshotUniforms.keys.filter { it !in declaredUniformNames }
            if (undeclared.isEmpty()) {
                null
            } else {
                val msg = "Unknown SkSL uniform(s) on '$id': ${undeclared.joinToString(", ")}"
                for (name in undeclared) {
                    if (loggedUnknownShaderUniforms.add("$id::$name")) {
                        System.err.println(msg)
                    }
                }
                msg
            }
        }
    }
    var uniformError by remember(id, sksl) { mutableStateOf<String?>(null) }
    val hasResolution = "iResolution" in declaredUniformNames

    val shaderHolder = remember(effect) { effect?.let(::CachedShaderHolder) }
    DisposableEffect(shaderHolder) {
        onDispose { shaderHolder?.close() }
    }

    val shouldInspect = registry != null && !overviewOpen
    val ownerToken = remember { Any() }
    var lastCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stageAlphaProvider by remember { mutableStateOf<() -> Float>({ 1f }) }

    if (anchorRegistry != null) {
        DisposableEffect(anchorRegistry, id) {
            onDispose { anchorRegistry.remove(id, ownerToken) }
        }
    }

    if (shouldInspect) {
        DisposableEffect(registry, id, shouldInspect) {
            onDispose { registry.remove(id, ownerToken) }
        }
    }

    fun publishSnapshot(coords: LayoutCoordinates?) {
        if (!shouldInspect) return
        val stageBounds = resolveStageBounds(coords, rootHolder) ?: return
        registry.recordMetadata(
            meta = StageElementMetadata(
                elementId = id,
                supportsText = false,
                supportsBoxStyle = false,
            ),
            owner = ownerToken,
        )
        registry.record(
            snapshot = StageNodeSnapshot(
                elementId = id,
                kind = "Shader",
                label = label,
                sceneIndex = sceneCtx?.sceneIndex ?: 0,
                beatIndex = beatIndex,
                stageBounds = stageBounds,
                effectiveBackground = effectiveBg,
                isEditable = true,
                parentElementId = parentElementId,
                shaderUniforms = shaderUniformSpecs,
            ),
            owner = ownerToken,
            alphaProvider = { stageAlphaProvider() },
            coordinatesProvider = { lastCoords },
            stageRootProvider = { rootHolder?.coordinates },
        )
    }

    if (shouldInspect) {
        val coordsNow = lastCoords
        observeStageRootCoordinates(rootHolder)
        SideEffect {
            publishSnapshot(coordsNow)
        }
    }

    val offsetModifier = Modifier.offset {
        val drag = dragPreview?.offsetFor(id) ?: DpOffset.Zero
        IntOffset(
            x = ((patch?.offsetXDp ?: 0f).dp + drag.x).roundToPx(),
            y = ((patch?.offsetYDp ?: 0f).dp + drag.y).roundToPx(),
        )
    }

    val activeError = compileError ?: undeclaredUniformError ?: uniformError

    Box(
        modifier = modifier
            .then(offsetModifier)
            .modifierLocalConsumer { stageAlphaProvider = ModifierLocalStageAlpha.current }
            .onPlaced { coords ->
                lastCoords = coords
                anchorRegistry?.record(id, coords, ownerToken)
                publishSnapshot(coords)
            }
            .drawBehind {
                val activeUniforms = LinkedHashMap(uniformScope.boundValues)
                val holder = shaderHolder
                if (holder == null || size.width <= 0.5f || size.height <= 0.5f) {
                    drawRect(fallbackColor)
                    return@drawBehind
                }
                val brush = holder.obtainBrush(
                    hasResolution = hasResolution,
                    drawSize = size,
                    uniforms = activeUniforms,
                    shaderId = id,
                    onUniformError = { msg ->
                        if (uniformError == null) uniformError = msg
                    },
                )
                drawRect(brush = brush)
            },
    ) {
        CompositionLocalProvider(LocalStageParentElementId provides id) {
            overlay()
        }
        if (activeError != null) {
            val errorShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE85A0E16), errorShape)
                    .border(2.dp, Color(0xFFFF5252), errorShape)
                    .padding(12.dp)
                    .testTag("deck-sksl-error-$id"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "SkSL error ($id):\n$activeError",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFFECEC),
                    ),
                )
            }
        }
    }
}
