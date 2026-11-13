package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.LocalDeckLoopClock
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen
import dev.sebastiano.ciacola.timeline.TimelineHarvestCollector
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

private const val MAX_SETTLE_RENDER_PASSES = 2

/**
 * Coordinates live active-beat linting, full-deck offscreen audits, diagnostic filtering, and
 * stage highlight overlays for `:deck-inspector`.
 */
@Stable
class DeckLintController {
    var scope by mutableStateOf(LintScope.CurrentBeat)
    var severityFilter by mutableStateOf(LintSeverityFilter.All)
    var isAuditing by mutableStateOf(false)
        private set
    var auditProgress by mutableFloatStateOf(0f)
        private set
    var currentBeatIssues by mutableStateOf<List<DeckLintIssue>>(emptyList())
        private set
    var fullDeckIssues by mutableStateOf<List<DeckLintIssue>>(emptyList())
        private set
    var hasFullDeckAudit by mutableStateOf(false)
        private set
    var lastAuditDurationMs by mutableLongStateOf(0L)
        private set
    var lastAuditError by mutableStateOf<String?>(null)
        private set
    var selectedIssueId by mutableStateOf<String?>(null)
    var hoveredIssueId by mutableStateOf<String?>(null)
    var showStageOverlay by mutableStateOf(true)

    var currentSceneIndex by mutableIntStateOf(0)
        private set
    var currentBeatIndex by mutableIntStateOf(0)
        private set
    var currentSceneTitle by mutableStateOf("")
        private set

    internal var stageRootCoordinates: LayoutCoordinates? = null
    internal var stageBoundsInRoot by mutableStateOf<Rect?>(null)
    internal var cleanStageGraphicsLayer: GraphicsLayer? = null

    val activeScopeIssues: List<DeckLintIssue>
        get() = when (scope) {
            LintScope.CurrentBeat -> currentBeatIssues
            LintScope.AllSlides -> if (hasFullDeckAudit) fullDeckIssues else currentBeatIssues
        }

    val filteredIssues: List<DeckLintIssue>
        get() = when (severityFilter) {
            LintSeverityFilter.All -> activeScopeIssues
            LintSeverityFilter.ErrorsOnly -> activeScopeIssues.filter { it.severity == LintSeverity.Error }
            LintSeverityFilter.WarningsOnly -> activeScopeIssues.filter { it.severity == LintSeverity.Warning }
        }

    val errorCount: Int
        get() = activeScopeIssues.count { it.severity == LintSeverity.Error }

    val warningCount: Int
        get() = activeScopeIssues.count { it.severity == LintSeverity.Warning }

    val activeBeatOverlayIssues: List<DeckLintIssue>
        get() = activeScopeIssues.filter {
            it.sceneIndex == currentSceneIndex && it.beatIndex == currentBeatIndex
        }

    val highlightedIssueId: String?
        get() = hoveredIssueId ?: selectedIssueId

    fun updateActiveLocation(sceneIndex: Int, beatIndex: Int, sceneTitle: String = currentSceneTitle) {
        currentSceneIndex = sceneIndex
        currentBeatIndex = beatIndex
        if (sceneTitle.isNotBlank()) {
            currentSceneTitle = sceneTitle
        }
    }

    /**
     * Evaluates the active settled `(sceneIndex, beatIndex)` using [registry] snapshots and either
     * provided [semanticsTextNodes] or live Compose window semantics + optional [pixelBuffer].
     */
    internal fun evaluateActiveBeatInternal(
        registry: StageInspectionRegistry?,
        sceneIndex: Int,
        beatIndex: Int,
        sceneTitle: String = "",
        semanticsTextNodes: List<LintTextNodeCandidate>? = null,
        pixelBuffer: StagePixelBuffer? = null,
        fallbackBackground: Color? = null,
        stageWidth: Float = STAGE_WIDTH_PX,
        stageHeight: Float = STAGE_HEIGHT_PX,
    ): List<DeckLintIssue> {
        currentSceneIndex = sceneIndex
        currentBeatIndex = beatIndex
        if (sceneTitle.isNotBlank()) {
            currentSceneTitle = sceneTitle
        }
        val stageSnapshots = registry?.nodesForSlide(sceneIndex, beatIndex).orEmpty()
        val semantics = semanticsTextNodes ?: SemanticsSlideScanner.scanLiveWindows(
            stageBoundsInRoot = stageBoundsInRoot,
            pixelBuffer = pixelBuffer,
            stageWidth = stageWidth,
            stageHeight = stageHeight,
            fallbackBackground = fallbackBackground,
        )
        val input = DeckLintRules.buildSlideInput(
            sceneIndex = sceneIndex,
            beatIndex = beatIndex,
            sceneTitle = sceneTitle.ifBlank { currentSceneTitle },
            stageSnapshots = stageSnapshots,
            semanticsTextNodes = semantics,
            stageWidth = stageWidth,
            stageHeight = stageHeight,
        )
        val evaluated = DeckLintRules.evaluateSlide(input)
        currentBeatIssues = evaluated
        if (selectedIssueId != null && activeScopeIssues.none { it.id == selectedIssueId }) {
            selectedIssueId = null
        }
        return evaluated
    }

    /**
     * Public entry point for live evaluation of the current beat using [registry] and active
     * desktop window semantics (plus a captured stage [GraphicsLayer] when available).
     */
    suspend fun refreshActiveBeat(
        registry: StageInspectionRegistry?,
        sceneIndex: Int,
        beatIndex: Int,
        sceneTitle: String = "",
        fallbackBackground: Color? = null,
    ): List<DeckLintIssue> {
        val layerBitmap = cleanStageGraphicsLayer?.let { layer ->
            runCatching { layer.toImageBitmap() }.getOrNull()
        }
        val pixelBuffer = layerBitmap?.toStagePixelBuffer()
        return evaluateActiveBeatInternal(
            registry = registry,
            sceneIndex = sceneIndex,
            beatIndex = beatIndex,
            sceneTitle = sceneTitle,
            semanticsTextNodes = null,
            pixelBuffer = pixelBuffer,
            fallbackBackground = fallbackBackground,
        )
    }

    /**
     * Synchronous evaluation helper for callers/tests supplying [StageInspectionRegistry] and
     * optional explicit semantics candidates.
     */
    fun evaluateActiveBeat(
        registry: StageInspectionRegistry?,
        sceneIndex: Int,
        beatIndex: Int,
        sceneTitle: String = "",
        fallbackBackground: Color? = null,
    ): List<DeckLintIssue> = evaluateActiveBeatInternal(
        registry = registry,
        sceneIndex = sceneIndex,
        beatIndex = beatIndex,
        sceneTitle = sceneTitle,
        semanticsTextNodes = null,
        pixelBuffer = null,
        fallbackBackground = fallbackBackground,
    )

    /**
     * Sweeps every settled `(sceneIndex, frameIndex)` across [storyboard] (and optional
     * [presetsToCheck] when [storyboardForPreset] is supplied) using ONE reused offscreen
     * [ImageComposeScene] on the caller/UI thread with `yield()` between beats.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    suspend fun runFullDeckAudit(
        storyboard: Storyboard,
        presetsToCheck: List<DeckThemePreset> = emptyList(),
        storyboardForPreset: ((DeckThemePreset) -> Storyboard)? = null,
        extraDecorator: ContentDecorator = ContentDecorator.None,
    ): List<DeckLintIssue> {
        if (isAuditing) return fullDeckIssues
        isAuditing = true
        auditProgress = 0f
        lastAuditError = null
        val startNanos = System.nanoTime()

        try {
            val targets: List<Pair<DeckThemePreset?, Storyboard>> =
                if (presetsToCheck.isNotEmpty() && storyboardForPreset != null) {
                    presetsToCheck.distinct().map { preset -> preset to storyboardForPreset(preset) }
                } else {
                    listOf(null to storyboard)
                }

            val totalSteps = targets.sumOf { it.second.indices.size }.coerceAtLeast(1)
            val width = storyboard.format.size.width.coerceAtLeast(1)
            val height = storyboard.format.size.height.coerceAtLeast(1)
            val stageW = width.toFloat()
            val stageH = height.toFloat()

            val offscreenRegistry = StageInspectionRegistry()
            val harvestCollector = TimelineHarvestCollector()
            val frozenClock = DeckLoopClock().apply { masterTimeMillis = 0L }

            val activeStoryboardState = mutableStateOf<Storyboard?>(null)
            val activeIndexState = mutableStateOf<Storyboard.Index?>(null)
            var renderEpoch by mutableIntStateOf(0)

            // Single reused offscreen ImageComposeScene on the caller (UI) thread to avoid
            // SnapshotStateObserver lock contention while keeping frame render costs minimal.
            val scene = ImageComposeScene(
                width = width,
                height = height,
                density = storyboard.format.density,
                coroutineContext = Dispatchers.Unconfined,
            ) {
                CompositionLocalProvider(
                    LocalTimelineOffscreen provides true,
                    LocalStageInspectionRegistry provides offscreenRegistry,
                    LocalTimelineHarvestCollector provides harvestCollector,
                    LocalDeckLoopClock provides frozenClock,
                ) {
                    extraDecorator.decorate {
                        val sb = activeStoryboardState.value
                        val index = activeIndexState.value
                        if (sb != null && index != null) {
                            key(sb, index, renderEpoch) {
                                ScenePreview(sb, index, sceneMode = SceneMode.Preview)
                            }
                        }
                    }
                }
            }

            val bitmap = Bitmap().apply {
                allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
            }
            val pixelBuffer = SkiaBitmapPixelBuffer(bitmap)
            val collectedIssues = LinkedHashMap<String, DeckLintIssue>()
            var completedSteps = 0

            try {
                for ((preset, targetStoryboard) in targets) {
                    activeStoryboardState.value = targetStoryboard
                    for (index in targetStoryboard.indices) {
                        offscreenRegistry.clear()
                        activeIndexState.value = index
                        renderEpoch++
                        Snapshot.sendApplyNotifications()

                        var rendered = scene.render(0L)
                        var settlePass = 0
                        while (scene.hasInvalidations() && settlePass < MAX_SETTLE_RENDER_PASSES) {
                            rendered.close()
                            rendered = scene.render(0L)
                            settlePass++
                        }

                        val hasPixels = try {
                            rendered.readPixels(bitmap)
                        } finally {
                            rendered.close()
                        }

                        val semantics = SemanticsSlideScanner.scanScene(
                            scene = scene,
                            pixelBuffer = if (hasPixels) pixelBuffer else null,
                            stageWidth = stageW,
                            stageHeight = stageH,
                        )
                        val title = harvestCollector.sceneTitles[index.sceneIndex].orEmpty()
                        val input = DeckLintRules.buildSlideInput(
                            sceneIndex = index.sceneIndex,
                            beatIndex = index.frameIndex,
                            sceneTitle = title,
                            stageSnapshots = offscreenRegistry.nodesForSlide(index.sceneIndex, index.frameIndex),
                            semanticsTextNodes = semantics,
                            stageWidth = stageW,
                            stageHeight = stageH,
                        )
                        val beatIssues = DeckLintRules.evaluateSlide(input)
                        for (issue in beatIssues) {
                            val isThemeSensitive = issue.ruleId == LintRuleId.LOW_CONTRAST
                            val dedupeKey = if (preset != null && targets.size > 1 && isThemeSensitive) {
                                "${issue.id}@${preset.name}"
                            } else {
                                issue.id
                            }
                            val annotated = if (preset != null && targets.size > 1 && isThemeSensitive) {
                                issue.copy(
                                    id = dedupeKey,
                                    detail = "[${presetLabel(preset)}] ${issue.detail}",
                                )
                            } else {
                                issue
                            }
                            collectedIssues.putIfAbsent(dedupeKey, annotated)
                        }

                        completedSteps++
                        auditProgress = completedSteps.toFloat() / totalSteps.toFloat()
                        yield()
                    }
                }
            } finally {
                bitmap.close()
                scene.close()
            }

            val result = collectedIssues.values.sortedWith(
                compareBy<DeckLintIssue> { it.severity != LintSeverity.Error }
                    .thenBy { it.sceneIndex }
                    .thenBy { it.beatIndex }
                    .thenBy { it.stageBounds.top }
                    .thenBy { it.stageBounds.left },
            )
            fullDeckIssues = result
            hasFullDeckAudit = true
            val currentSlice = result.filter {
                it.sceneIndex == currentSceneIndex && it.beatIndex == currentBeatIndex
            }
            if (currentBeatIssues.isEmpty() || scope == LintScope.AllSlides) {
                currentBeatIssues = currentSlice
            }
            return result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            lastAuditError = t.message ?: t::class.simpleName ?: "Audit failed"
            return fullDeckIssues
        } finally {
            lastAuditDurationMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            isAuditing = false
        }
    }

    /**
     * Selects [issue] for stage highlight and jumps [animatic] to `(issue.sceneIndex, issue.beatIndex)`.
     */
    suspend fun jumpToIssue(issue: DeckLintIssue, animatic: Animatic) {
        selectedIssueId = issue.id
        currentSceneIndex = issue.sceneIndex
        currentBeatIndex = issue.beatIndex
        if (issue.sceneTitle.isNotBlank()) {
            currentSceneTitle = issue.sceneTitle
        }
        animatic.jumpTo(Storyboard.Index(sceneIndex = issue.sceneIndex, frameIndex = issue.beatIndex))
    }

    private fun presetLabel(preset: DeckThemePreset): String = when (preset) {
        DeckThemePreset.StageDark -> "Stage Dark"
        DeckThemePreset.KeynoteLight -> "Keynote Light"
    }
}

@Composable
fun rememberDeckLintController(): DeckLintController = remember { DeckLintController() }
