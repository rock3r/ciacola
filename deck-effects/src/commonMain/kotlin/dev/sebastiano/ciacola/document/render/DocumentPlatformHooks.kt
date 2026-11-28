package dev.sebastiano.ciacola.document.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ConnectorPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.DocSegment
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.PieceSchedule
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.effects.v2.LayerState
import dev.sebastiano.ciacola.effects.v2.PathTrimState
import dev.sebastiano.ciacola.theme.DeckTheme

/**
 * Platform-independent description of how a document `text` node should be rendered on the current
 * frame (bridging `commonMain` motion evaluation to `desktopMain` `MagicTextV2Renderer`).
 */
@Immutable
sealed interface DocumentTextRenderState {
    /** Resting paragraph: single `drawText(wholeLayout)`. */
    @Immutable
    data class Rest(
        val payload: TextPayload,
        val characterDeliveryNode: Boolean = false,
    ) : DocumentTextRenderState

    /** Active morph between [from] and [to] at [elapsedMs] using [schedule]. */
    @Immutable
    data class Morph(
        val from: TextPayload,
        val to: TextPayload,
        val by: MorphBy,
        val pruneStopwords: Boolean,
        val schedule: PieceSchedule?,
        val totalDurationMs: Int,
        val elapsedMs: Float,
        val moveCurve: CurveV2,
        val interpolateLayoutSize: Boolean,
        val fromMinWidthPx: Int = 0,
        val toMinWidthPx: Int = 0,
        val fromMaxWidthPx: Int = Int.MAX_VALUE,
        val toMaxWidthPx: Int = Int.MAX_VALUE,
        val fromMaxWidthIsInner: Boolean = false,
        val toMaxWidthIsInner: Boolean = false,
        val characterDeliveryNode: Boolean = false,
        val clockSegment: DocSegment? = null,
        val trackStartMs: Int = 0,
    ) : DocumentTextRenderState

    /** Piece-level build (enter/exit/emphasis) on unchanged text (`word`, `character`, `paragraph`). */
    @Immutable
    data class Build(
        val payload: TextPayload,
        val by: DeliveryBy,
        val effectId: String,
        val kind: EffectBuildKind,
        val args: EffectArgs,
        val curve: CurveV2?,
        val schedule: PieceSchedule?,
        val totalDurationMs: Int,
        val elapsedMs: Float,
        val characterDeliveryNode: Boolean = false,
        val clockSegment: DocSegment? = null,
        val trackStartMs: Int = 0,
    ) : DocumentTextRenderState
}

/**
 * Samples an SVG path data string at arc-length fraction [fraction] in `[0, 1]` and returns the
 * displacement relative to the path's starting point (`pos(fraction * len) - pos(0)`), in
 * document coordinates (used by `Build.Change.path`).
 */
expect fun sampleSvgPathOffset(pathData: String, fraction: Float): Offset

/**
 * Remembers an opaque platform font resolver handle (on desktop: `DeckFontResolver`) keyed on
 * `document.fonts` and `fontsDir`.
 */
@Composable
expect fun rememberDocumentFontResolverHandle(
    document: DeckDocument,
    fontsDir: String?,
): Any?

/**
 * Resolves [familyName] against [fontResolverHandle] onto [style], clamping weights to loaded
 * faces when available.
 */
expect fun resolveDocumentFontStyle(
    fontResolverHandle: Any?,
    familyName: String?,
    style: TextStyle,
): TextStyle

/** Applies a full [LayerState] (including blur, colorMatrix, Wipe/Iris clip). */
expect fun Modifier.applyDocumentLayerState(state: LayerState): Modifier

/** Applies a Content-tier SkSL shader (`trace`, `sparkle`, `shimmer`) on platforms that support it. */
expect fun Modifier.applyDocumentContentEffect(
    effectId: String,
    kind: EffectBuildKind,
    progress: Float,
    args: EffectArgs,
    reducedMotion: Boolean,
): Modifier

/** Renders a document `text` node via `DeckTextRenderer` (`MagicTextV2Renderer`) with deferred content and optional `surface` framing. */
@Composable
expect fun DocumentTextPayloadView(
    renderState: () -> DocumentTextRenderState,
    surface: SurfaceSpec?,
    document: DeckDocument,
    theme: DeckTheme,
    colorResolver: DocumentColorResolver,
    fontResolverHandle: Any?,
    density: Density,
    reducedMotion: Boolean,
    deckId: String,
    assetsRootDir: String?,
    maxWidthPx: Int,
    modifier: Modifier,
)

/** Renders a document `shape` node (`rect`, `ellipse`, `line`, `path`) with optional Path-tier `lineDraw`. */
@Composable
expect fun DocumentShapePayloadView(
    payload: ShapePayload,
    colorResolver: DocumentColorResolver,
    density: Density,
    deckId: String,
    assetsRootDir: String?,
    pathTrim: PathTrimState?,
    modifier: Modifier,
    shapeReferenceSize: Size? = null,
)

/**
 * Clip playback binding of an `image` node whose source may be an animated GIF/WebP.
 *
 * @property payload the node's image payload resolved at the current beat.
 * @property builds the node's raw builds (its `playback` builds drive the playhead).
 * @property retainKey session-unique key of the node's playhead.
 * @property beat the beat whose playback state is shown, or `null` when the node's slide is not
 *   the one the clock is on.
 * @property live the clip plays (an animated `Present` mount); otherwise only the poster frame shows.
 * @property present the node is shown at [beat]; a hidden node holds its frame and takes no clicks.
 * @property scrubbable hovering shows the progress bar and clicking or dragging seeks (show mode).
 * @property mirrorOf session whose playhead for [retainKey] a non-live mount shows instead of the
 *   poster (the presenter's `Current` preview, magicMove overlays carrying a playing clip).
 */
@Immutable
data class DocumentImagePlayback(
    val payload: ImagePayload,
    val builds: List<Build>,
    val retainKey: String,
    val session: RenderSession,
    val beat: Int?,
    val live: Boolean,
    val present: Boolean,
    val scrubbable: Boolean,
    val mirrorOf: RenderSession? = null,
)

/**
 * Renders a document `image` node (raster PNG/JPG/WebP or vector `.svg`) with `fit` and `tint`.
 * Animated GIF/WebP sources play when [playback] is live and otherwise show their poster frame.
 */
@Composable
expect fun DocumentImagePayloadView(
    deckId: String,
    src: String,
    fit: String,
    tint: Color?,
    assetsRootDir: String?,
    modifier: Modifier,
    playback: DocumentImagePlayback? = null,
)

/** Renders a `Fill.Image` background fill inside a slide, container, or shape. */
@Composable
expect fun DocumentImageFillView(
    deckId: String,
    src: String,
    fit: String,
    tint: Color?,
    assetsRootDir: String?,
    modifier: Modifier,
)

/** Renders a document `connector` node between registered anchors with optional Path-tier `lineDraw`. */
@Composable
expect fun DocumentConnectorPayloadView(
    payload: ConnectorPayload,
    colorResolver: DocumentColorResolver,
    density: Density,
    pathTrim: PathTrimState?,
    modifier: Modifier,
)
