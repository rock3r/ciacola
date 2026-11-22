package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DocumentIssue
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Compose-observable controller wrapping [DocumentDeckLintEngine] and [DeckDocumentStore] for
 * single-slide and cancellable time-sliced full-deck audits.
 */
@Stable
class DocumentDeckLintController(
    val store: DeckDocumentStore,
    val engine: DocumentDeckLintEngine = DocumentDeckLintEngine(),
    var deckDir: Path? = null,
    var assetsDir: Path? = null,
    private val externalEditWarningsProvider: () -> List<DocumentIssue> = { emptyList() },
) : AutoCloseable {

    constructor(
        store: DeckDocumentStore,
        specimens: DeckSpecimenRegistry,
        effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
        deckDir: Path? = null,
        assetsDir: Path? = null,
        externalEditWarningsProvider: () -> List<DocumentIssue> = { emptyList() },
    ) : this(
        store = store,
        engine = DocumentDeckLintEngine(specimens = specimens, effects = effects),
        deckDir = deckDir,
        assetsDir = assetsDir,
        externalEditWarningsProvider = externalEditWarningsProvider,
    )

    var severityFilter: LintSeverityFilter by mutableStateOf(LintSeverityFilter.All)
    var isAuditing: Boolean by mutableStateOf(false)
        private set
    var progress: Float by mutableFloatStateOf(0f)
        private set
    var auditedSlides: Int by mutableIntStateOf(0)
        private set
    var totalSlidesToAudit: Int by mutableIntStateOf(0)
        private set
    var issues: List<DocumentLintIssue> by mutableStateOf(emptyList())
        private set
    var lastStats: DocumentDeckLintStats by mutableStateOf(DocumentDeckLintStats())
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var selectedIssueId: String? by mutableStateOf(null)

    /** What has been checked so far; `None` until the first slide or deck audit completes. */
    var auditScope: LintAuditScope by mutableStateOf(LintAuditScope.None)
        private set
    var showStageOverlay: Boolean by mutableStateOf(true)

    private var activeJob: Job? = null

    val filteredIssues: List<DocumentLintIssue>
        get() = when (severityFilter) {
            LintSeverityFilter.All -> issues
            LintSeverityFilter.ErrorsOnly -> issues.filter { it.severity == LintSeverity.Error }
            LintSeverityFilter.WarningsOnly -> issues.filter { it.severity == LintSeverity.Warning }
        }

    val errorCount: Int
        get() = issues.count { it.severity == LintSeverity.Error }

    val warningCount: Int
        get() = issues.count { it.severity == LintSeverity.Warning }

    /**
     * Returns filtered issues with non-empty [DocumentLintIssue.stageBounds] matching the active
     * slide and beat for stage callout rendering ([DocumentDeckLintStageOverlay]).
     */
    fun overlayIssuesFor(slideId: String?, beatIndex: Int): List<DocumentLintIssue> {
        if (slideId == null) return emptyList()
        return filteredIssues.filter { issue ->
            issue.slideId == slideId &&
                issue.beatIndex == beatIndex &&
                issue.stageBounds.width > 0.5f &&
                issue.stageBounds.height > 0.5f
        }
    }

    suspend fun lintSlideNow(
        slideIndex: Int,
        themeNames: List<String> = emptyList(),
    ): DocumentLintReport {
        val report = engine.lintSingleSlide(
            document = store.document,
            slideIndex = slideIndex,
            themeNames = themeNames,
            deckDir = deckDir,
            assetsDir = assetsDir,
            externalEditWarnings = externalEditWarningsProvider(),
        )
        // After a full-deck audit, re-checking one slide refreshes only that slide's issues.
        issues = if (auditScope == LintAuditScope.Deck) {
            val refreshedIds = report.issues.mapTo(HashSet()) { it.id }
            issues.filterNot { it.slideIndex == slideIndex || it.id in refreshedIds } + report.issues
        } else {
            report.issues
        }
        lastStats = report.stats
        lastError = null
        if (auditScope != LintAuditScope.Deck) auditScope = LintAuditScope.Slide
        return report
    }

    fun startFullDeckAudit(
        scope: CoroutineScope,
        themeNames: List<String> = emptyList(),
        sliceBudgetMs: Double = DEFAULT_LINT_SLICE_BUDGET_MS,
    ): Job {
        cancelAudit()
        val slideTotal = store.document.slides.size.coerceAtLeast(1)
        isAuditing = true
        progress = 0f
        auditedSlides = 0
        totalSlidesToAudit = slideTotal
        lastError = null
        val job = scope.launch {
            try {
                val report = engine.lintFullDeck(
                    document = store.document,
                    themeNames = themeNames,
                    deckDir = deckDir,
                    assetsDir = assetsDir,
                    externalEditWarnings = externalEditWarningsProvider(),
                    sliceBudgetMs = sliceBudgetMs,
                    onProgress = { p ->
                        progress = p
                        auditedSlides = (p * slideTotal).roundToInt().coerceIn(0, slideTotal)
                    },
                )
                issues = report.issues
                lastStats = report.stats
                progress = 1f
                auditedSlides = slideTotal
                auditScope = LintAuditScope.Deck
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.simpleName
            } finally {
                isAuditing = false
            }
        }
        activeJob = job
        return job
    }

    /**
     * Applies the automatic fix for [issue] (see [lintFixDescription]) as ONE undo step at the
     * issue's beat, then drops the issue from the list. Returns `false` when there is no fix.
     */
    fun fixIssue(issue: DocumentLintIssue): Boolean {
        if (store.isReadOnly) return false
        val nodeId = issue.nodeId ?: return false
        val patch = lintFixPatch(issue, store.document) ?: return false
        val ok = store.edit("Fix: ${lintFixDescription(issue) ?: issue.code}") { doc ->
            val anchorBeats = doc.slides.firstOrNull { it.id == issue.slideId }
                ?.let { JsonMergePatch.referencedNodeAnchorBeats(it, nodeId) }
                .orEmpty()
            DocumentThemeResolver.updateNodeInSlide(doc, issue.slideId, nodeId) { node ->
                JsonMergePatch.applyEditAtBeat(node, issue.beatIndex, patch, anchorBeats)
            }
        }
        if (ok) issues = issues.filterNot { it.id == issue.id }
        return ok
    }

    fun cancelAudit() {
        activeJob?.cancel()
        activeJob = null
        isAuditing = false
    }

    override fun close() {
        cancelAudit()
        engine.close()
    }
}
