package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

internal expect fun <T> withDocumentLock(lock: Any, block: () -> T): T

/**
 * First-load lifecycle state for a deck document.
 */
@Immutable
sealed interface DeckLoadState {
    data object Loading : DeckLoadState
    data object Loaded : DeckLoadState
    data class Invalid(val errors: List<DocumentIssue>) : DeckLoadState
    data class ReadOnlyNewerFormat(
        val foundVersion: Int,
        val supportedVersion: Int,
        val message: String,
    ) : DeckLoadState
}

enum class ConflictKind {
    /** Target changed before Studio wrote (step 2 or watcher while dirty). Target holds theirs. */
    Unwritten,

    /** Studio's exchange displaced an unseen version (step 5 or sweep). Target holds Studio's version. */
    Overwrote,
}

@Immutable
data class ConflictInfo(
    val kind: ConflictKind,
    val targetPath: String,
    val theirsPath: String,
    val theirsHash: String,
    val txId: String? = null,
    val reason: String,
) {
    /** Hash of the external version observed when this conflict was recorded. */
    val observedTheirsHash: String
        get() = theirsHash
}

@Immutable
sealed interface WriteFailure {
    val message: String

    @Immutable
    data class BeforeExchange(
        override val message: String,
    ) : WriteFailure

    @Immutable
    data class AfterExchange(
        val displacedAt: String,
        override val message: String,
    ) : WriteFailure
}

/**
 * Persistence and synchronization status of a [DeckDocumentStore].
 */
@Immutable
sealed interface SyncState {
    data object Saved : SyncState
    data object Dirty : SyncState
    data class Conflict(val info: ConflictInfo) : SyncState
    data class ExternalInvalid(
        val errors: List<DocumentIssue>,
        val targetPath: String,
    ) : SyncState
    data class WriteFailed(val failure: WriteFailure) : SyncState
    data class ReadOnly(val reason: String) : SyncState
}

@Immutable
data class HistoryEntry(
    val label: String,
    val document: DeckDocument,
)

/**
 * Explicit gesture transaction against [DeckDocumentStore].
 *
 * Every [update] call applies `transform` to the immutable snapshot captured at [DeckDocumentStore.begin],
 * never accumulating drift across pointer moves. [commit] records at most one history entry.
 */
interface EditTransaction {
    val label: String
    val isActive: Boolean
    fun update(transform: (DeckDocument) -> DeckDocument)
    fun commit(): Boolean
    fun cancel()
}

/**
 * Single authoritative in-memory store for a `<deckId>.deck.json` document.
 *
 * Owns:
 * - Separate [committedDocument] (at [revision]) and live [EditTransaction] preview exposed
 *   together via [document] for stage rendering, while [committedSnapshot] guarantees persistence
 *   never serializes an uncommitted transaction preview.
 * - Single-gesture [EditTransaction] lifecycle (`begin` / `update` / `commit` / `cancel`) that
 *   rejects structural `LoadError` mutations.
 * - Unified 200-step undo/redo history across Element, Motion, and Theme edits.
 */
@Stable
class DeckDocumentStore(
    initialDocument: DeckDocument = DeckDocument(id = ""),
    initialLoad: DeckLoadState = DeckLoadState.Loading,
    initialSync: SyncState = SyncState.Saved,
    validator: ((DeckDocument) -> List<DocumentIssue>)? = null,
    private val maxHistorySize: Int = DEFAULT_MAX_HISTORY,
    specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
    assetExists: ((String) -> Boolean)? = null,
) {
    private val stateLock = Any()
    private val explicitValidator: ((DeckDocument) -> List<DocumentIssue>)? = validator
    private var validationSpecimens: DeckSpecimenRegistry = specimens
    private var validationEffects: BuildEffectRegistry = effects
    private var validationAssetExists: ((String) -> Boolean)? = assetExists

    var load: DeckLoadState by mutableStateOf(initialLoad)
        private set

    private var committedDocumentState: DeckDocument by mutableStateOf(initialDocument)
    private var previewDocumentState: DeckDocument? by mutableStateOf(null)

    /** Live document for stage/UI preview: shows active transaction preview when open, else [committedDocument]. */
    val document: DeckDocument
        get() = previewDocumentState ?: committedDocumentState

    /** Latest committed document (never reflects an uncommitted transaction preview). */
    val committedDocument: DeckDocument
        get() = committedDocumentState

    var sync: SyncState by mutableStateOf(initialSync)
        private set

    var lintIssues: List<DocumentIssue> by mutableStateOf(emptyList())
        private set

    var lastRejectedEditErrors: List<DocumentIssue> by mutableStateOf(emptyList())
        private set

    var revision: Long by mutableLongStateOf(0L)
        private set

    var savedRevision: Long by mutableLongStateOf(0L)
        private set

    private var committedPair: Pair<DeckDocument, Long> = initialDocument to 0L
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    private var activeTransaction: ActiveEditTransaction? = null

    var canUndo: Boolean by mutableStateOf(false)
        private set

    var canRedo: Boolean by mutableStateOf(false)
        private set

    val isReadOnly: Boolean
        get() = sync is SyncState.ReadOnly

    val hasOpenTransaction: Boolean
        get() = withDocumentLock(stateLock) { activeTransaction != null }

    val isDirty: Boolean
        get() = revision > savedRevision

    /**
     * Atomically captures the latest committed `(document, revision)` pair, never returning an
     * uncommitted transaction preview. Safe to call from background I/O threads.
     */
    fun committedSnapshot(): Pair<DeckDocument, Long> = withDocumentLock(stateLock) {
        committedPair
    }

    /**
     * Configures the registries used for post-edit validation when no custom validator lambda was
     * supplied to the constructor.
     */
    fun configureValidation(
        specimens: DeckSpecimenRegistry = validationSpecimens,
        effects: BuildEffectRegistry = validationEffects,
        assetExists: ((String) -> Boolean)? = validationAssetExists,
    ) {
        withDocumentLock(stateLock) {
            validationSpecimens = specimens
            validationEffects = effects
            validationAssetExists = assetExists
        }
    }

    internal fun hasDefaultValidationRegistries(): Boolean = withDocumentLock(stateLock) {
        explicitValidator == null &&
            validationSpecimens === DeckSpecimenRegistry.Empty &&
            validationEffects === BuildEffectRegistry.Standard &&
            validationAssetExists == null
    }

    internal fun validateDocument(doc: DeckDocument): List<DocumentIssue> {
        val custom = explicitValidator
        if (custom != null) return custom(doc)
        val (specimens, effects, assets) = withDocumentLock(stateLock) {
            Triple(validationSpecimens, validationEffects, validationAssetExists)
        }
        return DeckDocumentCodec.validate(
            document = doc,
            specimens = specimens,
            effects = effects,
            assetExists = assets,
        )
    }

    /**
     * Atomically applies [transform] as one undoable edit (`begin` + `update` + `commit`).
     * Returns `false` if the store is read-only, not loaded, already inside an open transaction,
     * or if the resulting document introduces any structural [IssueSeverity.LoadError].
     */
    fun edit(label: String = "Edit", transform: (DeckDocument) -> DeckDocument): Boolean {
        val tx = begin(label) ?: return false
        tx.update(transform)
        return tx.commit()
    }

    /**
     * Starts an explicit gesture transaction.
     * Returns `null` when read-only, not loaded, or when another transaction is already open.
     */
    fun begin(label: String): EditTransaction? = mutateState {
        if (load != DeckLoadState.Loaded || isReadOnly || activeTransaction != null) {
            return@mutateState null
        }
        val tx = ActiveEditTransaction(label = label, snapshotAtBegin = committedDocumentState)
        activeTransaction = tx
        refreshHistoryFlags()
        tx
    }

    /**
     * Cancels any open gesture transaction, restoring the exact `snapshotAtBegin` document.
     */
    fun cancelActiveTransaction() {
        val tx = withDocumentLock(stateLock) { activeTransaction }
        tx?.cancel()
    }

    /**
     * Alias for [cancelActiveTransaction] for close/switch callers.
     */
    fun cancelOpenTransaction() {
        cancelActiveTransaction()
    }

    /**
     * Undoes the most recent committed edit, or cancels an open transaction if one is active.
     */
    fun undo() {
        val openTx = withDocumentLock(stateLock) { activeTransaction }
        if (openTx != null) {
            openTx.cancel()
            return
        }
        val previous = withDocumentLock(stateLock) {
            if (isReadOnly || undoStack.isEmpty()) null else undoStack.last()
        } ?: return
        val issues = validateDocument(previous.document)
        mutateState {
            if (activeTransaction != null || isReadOnly || undoStack.isEmpty()) return@mutateState
            val popped = undoStack.removeLast()
            pushCapped(redoStack, HistoryEntry(popped.label, committedDocumentState))
            committedDocumentState = popped.document
            previewDocumentState = null
            lintIssues = issues
            lastRejectedEditErrors = emptyList()
            revision++
            committedPair = committedDocumentState to revision
            updateDirtySyncAfterCommit()
            refreshHistoryFlags()
        }
    }

    /**
     * Re-applies the most recently undone edit.
     */
    fun redo() {
        val next = withDocumentLock(stateLock) {
            if (isReadOnly || activeTransaction != null || redoStack.isEmpty()) null else redoStack.last()
        } ?: return
        val issues = validateDocument(next.document)
        mutateState {
            if (isReadOnly || activeTransaction != null || redoStack.isEmpty()) return@mutateState
            val popped = redoStack.removeLast()
            pushCapped(undoStack, HistoryEntry(popped.label, committedDocumentState))
            committedDocumentState = popped.document
            previewDocumentState = null
            lintIssues = issues
            lastRejectedEditErrors = emptyList()
            revision++
            committedPair = committedDocumentState to revision
            updateDirtySyncAfterCommit()
            refreshHistoryFlags()
        }
    }

    /**
     * Loads a valid initial document (clears history and resets revision).
     */
    fun loadInitial(
        doc: DeckDocument,
        readOnlyReason: String? = null,
        issues: List<DocumentIssue>? = null,
    ) {
        val resolvedIssues = issues ?: validateDocument(doc)
        mutateState {
            invalidateActiveTransactionLocked()
            undoStack.clear()
            redoStack.clear()
            committedDocumentState = doc
            previewDocumentState = null
            lintIssues = resolvedIssues
            lastRejectedEditErrors = emptyList()
            load = DeckLoadState.Loaded
            revision = 0L
            savedRevision = 0L
            committedPair = doc to 0L
            sync = if (readOnlyReason != null) SyncState.ReadOnly(readOnlyReason) else SyncState.Saved
            refreshHistoryFlags()
        }
    }

    /** Marks load as in-progress. */
    fun markLoading() {
        mutateState {
            invalidateActiveTransactionLocked()
            load = DeckLoadState.Loading
            refreshHistoryFlags()
        }
    }

    /** Marks initial load failed with [errors]. */
    fun markLoadInvalid(errors: List<DocumentIssue>) {
        mutateState {
            invalidateActiveTransactionLocked()
            load = DeckLoadState.Invalid(errors)
            refreshHistoryFlags()
        }
    }

    /** Marks file as newer than supported format — read-only without entering strict decoder. */
    fun markReadOnlyNewerFormat(foundVersion: Int, supportedVersion: Int, message: String) {
        mutateState {
            invalidateActiveTransactionLocked()
            load = DeckLoadState.ReadOnlyNewerFormat(foundVersion, supportedVersion, message)
            sync = SyncState.ReadOnly(message)
            refreshHistoryFlags()
        }
    }

    /**
     * Applies an external clean file update as a new history entry labelled `"External change"`
     * while preserving undo history. Any open gesture transaction is invalidated first so a
     * stale `cancel()` can never overwrite the taken document.
     */
    fun applyExternalChange(
        newDocument: DeckDocument,
        label: String = "External change",
        markAsSaved: Boolean = true,
        issues: List<DocumentIssue>? = null,
    ) {
        val resolvedIssues = issues ?: validateDocument(newDocument)
        mutateState {
            invalidateActiveTransactionLocked()
            if (committedDocumentState != newDocument) {
                pushCapped(undoStack, HistoryEntry(label, committedDocumentState))
                redoStack.clear()
                committedDocumentState = newDocument
                lintIssues = resolvedIssues
                lastRejectedEditErrors = emptyList()
                revision++
                committedPair = committedDocumentState to revision
            } else {
                lintIssues = resolvedIssues
            }
            if (markAsSaved) {
                savedRevision = revision
                if (sync !is SyncState.ReadOnly) {
                    sync = SyncState.Saved
                }
            } else {
                updateDirtySyncAfterCommit()
            }
            refreshHistoryFlags()
        }
    }

    /** Records that the writer persisted [persistedRevision] to disk. */
    fun markSaved(persistedRevision: Long) {
        mutateState {
            if (persistedRevision > savedRevision) {
                savedRevision = persistedRevision
            }
            if (sync !is SyncState.ReadOnly && sync !is SyncState.Conflict) {
                sync = if (revision > savedRevision) SyncState.Dirty else SyncState.Saved
            }
        }
    }

    /** Updates [sync] from the persistence layer (`DeckFileSync`). */
    fun setSyncState(newSync: SyncState) {
        mutateState {
            sync = newSync
        }
    }

    /** Returns a copy of the undo stack labels oldest -> newest (useful for diagnostics & tests). */
    fun undoHistoryLabels(): List<String> = withDocumentLock(stateLock) {
        undoStack.map { it.label }
    }

    private fun <T> mutateState(block: () -> T): T {
        val result = withDocumentLock(stateLock) {
            Snapshot.withMutableSnapshot {
                block()
            }
        }
        Snapshot.sendApplyNotifications()
        return result
    }

    private fun invalidateActiveTransactionLocked() {
        activeTransaction?.markInactive()
        activeTransaction = null
        previewDocumentState = null
    }

    private fun updateDirtySyncAfterCommit() {
        when (sync) {
            is SyncState.ReadOnly,
            is SyncState.Conflict,
            is SyncState.ExternalInvalid,
            is SyncState.WriteFailed,
            -> Unit // Stay in conflict/external-invalid/write-failed/read-only until explicitly resolved; revision > savedRevision tracks dirty.
            is SyncState.Saved,
            is SyncState.Dirty,
            -> sync = if (revision > savedRevision) SyncState.Dirty else SyncState.Saved
        }
    }

    private fun pushCapped(stack: ArrayDeque<HistoryEntry>, entry: HistoryEntry) {
        if (maxHistorySize <= 0) return
        while (stack.size >= maxHistorySize) {
            stack.removeFirst()
        }
        stack.addLast(entry)
    }

    private fun refreshHistoryFlags() {
        canUndo = undoStack.isNotEmpty() || activeTransaction != null
        canRedo = redoStack.isNotEmpty() && activeTransaction == null
    }

    private inner class ActiveEditTransaction(
        override val label: String,
        private val snapshotAtBegin: DeckDocument,
    ) : EditTransaction {
        override var isActive: Boolean = true
            private set

        fun markInactive() {
            isActive = false
        }

        override fun update(transform: (DeckDocument) -> DeckDocument) {
            val active = withDocumentLock(stateLock) { isActive }
            if (!active) return
            val nextPreview = transform(snapshotAtBegin)
            mutateState {
                if (!isActive) return@mutateState
                previewDocumentState = nextPreview
            }
        }

        override fun commit(): Boolean {
            val candidate = withDocumentLock(stateLock) {
                if (!isActive) return@withDocumentLock null
                previewDocumentState ?: snapshotAtBegin
            } ?: return false

            if (candidate == snapshotAtBegin) {
                return mutateState {
                    if (!isActive) return@mutateState false
                    isActive = false
                    activeTransaction = null
                    previewDocumentState = null
                    lastRejectedEditErrors = emptyList()
                    refreshHistoryFlags()
                    true
                }
            }

            val issues = validateDocument(candidate)
            val loadErrors = issues.filter { it.severity == IssueSeverity.LoadError }
            return mutateState {
                if (!isActive) return@mutateState false
                isActive = false
                activeTransaction = null
                previewDocumentState = null
                if (loadErrors.isNotEmpty()) {
                    lastRejectedEditErrors = loadErrors
                    refreshHistoryFlags()
                    return@mutateState false
                }
                lastRejectedEditErrors = emptyList()
                pushCapped(undoStack, HistoryEntry(label, snapshotAtBegin))
                redoStack.clear()
                committedDocumentState = candidate
                lintIssues = issues
                revision++
                committedPair = committedDocumentState to revision
                updateDirtySyncAfterCommit()
                refreshHistoryFlags()
                true
            }
        }

        override fun cancel() {
            mutateState {
                if (!isActive) return@mutateState
                isActive = false
                activeTransaction = null
                previewDocumentState = null
                refreshHistoryFlags()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_HISTORY: Int = 200
    }
}

@Immutable
data class NodeReferenceReport(
    val nodeId: String,
    val connectorIds: List<String>,
    val loupeIds: List<String>,
    val afterBuildOwners: List<String>,
) {
    val isEmpty: Boolean
        get() = connectorIds.isEmpty() && loupeIds.isEmpty() && afterBuildOwners.isEmpty()
}

/**
 * Pure document structure and provenance editing helpers.
 */
object DeckStructureEdits {

    /**
     * Inserts an empty beat at [atBeat] in slide [slideId]:
     * every build and specimen track with `beat >= atBeat` shifts by `+1`.
     */
    fun insertBeat(document: DeckDocument, slideId: String, atBeat: Int): DeckDocument =
        updateSlide(document, slideId) { slide ->
            val clamped = atBeat.coerceIn(0, slide.beats.size)
            val newBeats = slide.beats.toMutableList().apply { add(clamped, BeatSpec()) }
            val newNodes = slide.nodes.map { shiftNodeBeatsOnInsert(it, clamped) }
            slide.copy(beats = newBeats, nodes = newNodes)
        }

    /**
     * Deletes beat [beatIndex] (`>= 1`) in slide [slideId]:
     * - Builds on [beatIndex] move to `beatIndex - 1`, preserving order after existing builds on `beatIndex - 1`.
     * - Higher beats (`> beatIndex`) shift down by 1.
     * - Deleted beat's notes are appended to beat `beatIndex - 1`.
     * - Nothing is dropped.
     */
    fun deleteBeat(document: DeckDocument, slideId: String, beatIndex: Int): DeckDocument =
        updateSlide(document, slideId) { slide ->
            require(beatIndex in 1..slide.beats.lastIndex) {
                "Cannot delete beat $beatIndex on slide '$slideId' (valid range 1..${slide.beats.lastIndex})."
            }
            val prevBeat = slide.beats[beatIndex - 1]
            val deletedBeat = slide.beats[beatIndex]
            val mergedNotes = when {
                prevBeat.notes.isNullOrBlank() -> deletedBeat.notes
                deletedBeat.notes.isNullOrBlank() -> prevBeat.notes
                else -> "${prevBeat.notes}\n${deletedBeat.notes}"
            }
            val newBeats = slide.beats.toMutableList().apply {
                set(beatIndex - 1, prevBeat.copy(notes = mergedNotes))
                removeAt(beatIndex)
            }
            val newNodes = slide.nodes.map { shiftNodeBeatsOnDelete(it, beatIndex) }
            slide.copy(beats = newBeats, nodes = newNodes)
        }

    /**
     * Deletes build [buildIndex] from node [nodeId] on slide [slideId]:
     * any dependent build on the same beat whose `after` pointed at the deleted build takes over
     * the deleted build's own `after`.
     */
    fun deleteBuild(
        document: DeckDocument,
        slideId: String,
        nodeId: String,
        buildIndex: Int,
    ): DeckDocument = updateSlide(document, slideId) { slide ->
        val targetNode = findNode(slide.nodes, nodeId)
            ?: error("Node '$nodeId' not found on slide '$slideId'.")
        require(buildIndex in targetNode.builds.indices) {
            "Build index $buildIndex out of bounds for node '$nodeId'."
        }
        val deleted = targetNode.builds[buildIndex]
        val remainingSameBeat = targetNode.builds.withIndex().filter { (idx, b) ->
            idx != buildIndex && b.beat == deleted.beat
        }
        val wasLastOnBeat = targetNode.builds.indexOfLast { it.beat == deleted.beat } == buildIndex
        val replacementAfter: String? = when {
            deleted.after != null -> deleted.after
            wasLastOnBeat && remainingSameBeat.isNotEmpty() -> {
                val prev = remainingSameBeat.last().value
                prev.id?.let { "$nodeId#$it" } ?: nodeId
            }
            else -> null
        }

        val deletedExactRef = deleted.id?.let { "$nodeId#$it" }
        val rewiringNodes = slide.nodes.map { node ->
            rewriteNodeTree(node) { n ->
                val baseBuilds = if (n.id == nodeId) {
                    n.builds.filterIndexed { idx, _ -> idx != buildIndex }
                } else {
                    n.builds
                }
                val rewired = baseBuilds.map { b ->
                    if (b.beat != deleted.beat || b.after == null) {
                        b
                    } else {
                        val matchesExact = deletedExactRef != null && b.after == deletedExactRef
                        val matchesNodeLast = wasLastOnBeat && remainingSameBeat.isEmpty() && b.after == nodeId
                        if (matchesExact || matchesNodeLast) {
                            b.withAfter(replacementAfter)
                        } else {
                            b
                        }
                    }
                }
                n.copy(builds = rewired)
            }
        }
        slide.copy(nodes = rewiringNodes)
    }

    /**
     * Reports all connectors, loupes, and `after` build references pointing to [nodeId] on [slide].
     */
    fun findNodeReferences(slide: SlideSpec, nodeId: String): NodeReferenceReport {
        val connectors = mutableListOf<String>()
        val loupes = mutableListOf<String>()
        val afterOwners = mutableListOf<String>()

        fun visit(nodes: List<NodeSpec>) {
            for (n in nodes) {
                if (n.id != nodeId) {
                    n.connector?.let { conn ->
                        if (conn.from == nodeId || conn.to == nodeId) connectors += n.id
                    }
                    n.loupe?.let { loupe ->
                        if (loupe.target == nodeId) loupes += n.id
                    }
                    if (n.builds.any { b -> b.after == nodeId || b.after?.startsWith("$nodeId#") == true }) {
                        afterOwners += n.id
                    }
                }
                visit(n.children)
            }
        }
        visit(slide.nodes)
        return NodeReferenceReport(nodeId, connectors, loupes, afterOwners)
    }

    /**
     * Deletes node [nodeId] from slide [slideId]. When [removeReferences] is `true`,
     * removes connectors referencing [nodeId], unwraps/removes referencing loupes, and clears
     * `after` pointers to [nodeId] in the same edit.
     */
    fun deleteNode(
        document: DeckDocument,
        slideId: String,
        nodeId: String,
        removeReferences: Boolean = false,
    ): DeckDocument = updateSlide(document, slideId) { slide ->
        fun filterTree(nodes: List<NodeSpec>): List<NodeSpec> = nodes.mapNotNull { n ->
            if (n.id == nodeId) return@mapNotNull null
            if (removeReferences && n.connector != null && (n.connector.from == nodeId || n.connector.to == nodeId)) {
                return@mapNotNull null
            }
            if (removeReferences && n.loupe != null && n.loupe.target == nodeId) {
                return@mapNotNull null
            }
            val cleanedBuilds = if (removeReferences) {
                n.builds.map { b ->
                    if (b.after == nodeId || b.after?.startsWith("$nodeId#") == true) b.withAfter(null) else b
                }
            } else {
                n.builds
            }
            val cleaned = n.copy(builds = cleanedBuilds)
            cleaned.withChildren(filterTree(cleaned.children))
        }
        slide.copy(nodes = filterTree(slide.nodes))
    }

    /**
     * Applies an inline range edit to a text node at [beat] and remaps its `stagger.manual`
     * entries using [FragmentProvenance.remapForRangeEdit].
     */
    fun applyTextRangeEdit(
        document: DeckDocument,
        slideId: String,
        nodeId: String,
        beat: Int,
        edit: TextRangeEdit,
    ): Pair<DeckDocument, List<ManualDelayRemapNotice>> {
        val notices = mutableListOf<ManualDelayRemapNotice>()
        val updated = updateSlide(document, slideId) { slide ->
            slide.copy(
                nodes = slide.nodes.map { root ->
                    rewriteNodeTree(root) { node ->
                        if (node.id != nodeId || node.text == null) return@rewriteNodeTree node
                        val resolvedBefore = JsonMergePatch.resolveNodeAtBeat(node, beat)
                        val oldText = resolvedBefore.text?.value?.plainText ?: return@rewriteNodeTree node
                        val newText = edit.applyTo(oldText)
                        val patch = JsonMergePatch.nestedPatch(
                            listOf("text", "value"),
                            kotlinx.serialization.json.JsonPrimitive(newText),
                        )
                        val textPatched = JsonMergePatch.applyEditAtBeat(node, beat, patch)
                        remapTextNodeManualDelays(
                            node = textPatched,
                            beat = beat,
                            remap = { manual, by, isExit ->
                                FragmentProvenance.remapForRangeEdit(oldText, edit, manual, by, isExit)
                            },
                            noticesOut = notices,
                        )
                    }
                },
            )
        }
        return updated to notices
    }

    /**
     * Applies a whole-text replacement (no edit range) to a text node at [beat] and remaps its
     * `stagger.manual` entries using [FragmentProvenance.remapForWholeReplacement].
     */
    fun applyWholeTextReplacement(
        document: DeckDocument,
        slideId: String,
        nodeId: String,
        beat: Int,
        newText: String,
    ): Pair<DeckDocument, List<ManualDelayRemapNotice>> {
        val notices = mutableListOf<ManualDelayRemapNotice>()
        val updated = updateSlide(document, slideId) { slide ->
            slide.copy(
                nodes = slide.nodes.map { root ->
                    rewriteNodeTree(root) { node ->
                        if (node.id != nodeId || node.text == null) return@rewriteNodeTree node
                        val resolvedBefore = JsonMergePatch.resolveNodeAtBeat(node, beat)
                        val oldText = resolvedBefore.text?.value?.plainText ?: return@rewriteNodeTree node
                        val patch = JsonMergePatch.nestedPatch(
                            listOf("text", "value"),
                            kotlinx.serialization.json.JsonPrimitive(newText),
                        )
                        val textPatched = JsonMergePatch.applyEditAtBeat(node, beat, patch)
                        remapTextNodeManualDelays(
                            node = textPatched,
                            beat = beat,
                            remap = { manual, by, isExit ->
                                FragmentProvenance.remapForWholeReplacement(oldText, newText, manual, by, isExit)
                            },
                            noticesOut = notices,
                        )
                    }
                },
            )
        }
        return updated to notices
    }

    private fun remapTextNodeManualDelays(
        node: NodeSpec,
        beat: Int,
        remap: (manual: Map<String, Int>, by: MorphBy, isExit: Boolean) -> ManualDelayRemapResult,
        noticesOut: MutableList<ManualDelayRemapNotice>,
    ): NodeSpec {
        val updatedText = node.text?.let { tp ->
            val morph = tp.morph
            val stagger = morph?.stagger
            if (morph != null && stagger != null && stagger.manual.isNotEmpty()) {
                val res = remap(stagger.manual, morph.by, false)
                noticesOut += res.notices
                tp.copy(morph = morph.copy(stagger = stagger.copy(manual = res.updatedManual)))
            } else {
                tp
            }
        }

        val updatedBuilds = node.builds.map { build ->
            when (build) {
                is Build.Change -> {
                    val morph = build.morph
                    val stagger = morph?.stagger
                    if (morph != null && stagger != null && stagger.manual.isNotEmpty()) {
                        val isExit = build.beat > beat
                        val res = remap(stagger.manual, morph.by, isExit)
                        noticesOut += res.notices
                        build.copy(morph = morph.copy(stagger = stagger.copy(manual = res.updatedManual)))
                    } else {
                        build
                    }
                }
                is Build.Enter -> {
                    val stagger = build.stagger
                    val by = build.by.toMorphByOrNull()
                    if (stagger != null && by != null && stagger.manual.isNotEmpty()) {
                        val res = remap(stagger.manual, by, false)
                        noticesOut += res.notices
                        build.copy(stagger = stagger.copy(manual = res.updatedManual))
                    } else {
                        build
                    }
                }
                is Build.Exit -> {
                    val stagger = build.stagger
                    val by = build.by.toMorphByOrNull()
                    if (stagger != null && by != null && stagger.manual.isNotEmpty()) {
                        val res = remap(stagger.manual, by, false)
                        noticesOut += res.notices
                        build.copy(stagger = stagger.copy(manual = res.updatedManual))
                    } else {
                        build
                    }
                }
                is Build.Emphasis -> {
                    val stagger = build.stagger
                    val by = build.by.toMorphByOrNull()
                    if (stagger != null && by != null && stagger.manual.isNotEmpty()) {
                        val res = remap(stagger.manual, by, false)
                        noticesOut += res.notices
                        build.copy(stagger = stagger.copy(manual = res.updatedManual))
                    } else {
                        build
                    }
                }
            }
        }
        return node.copy(text = updatedText, builds = updatedBuilds)
    }

    private fun DeliveryBy.toMorphByOrNull(): MorphBy? = when (this) {
        DeliveryBy.Paragraph -> MorphBy.Paragraph
        DeliveryBy.Word -> MorphBy.Word
        DeliveryBy.Character -> MorphBy.Character
        DeliveryBy.Whole, DeliveryBy.Child -> null
    }

    private fun shiftNodeBeatsOnInsert(node: NodeSpec, atBeat: Int): NodeSpec {
        val shiftedBuilds = node.builds.map { b ->
            if (b.beat >= atBeat) b.withBeat(b.beat + 1) else b
        }
        val shiftedSpecimen = node.specimen?.let { sp ->
            if (sp.tracks.isEmpty()) sp else sp.copy(
                tracks = sp.tracks.mapValues { (_, tr) ->
                    if (tr.beat != null && tr.beat >= atBeat) tr.copy(beat = tr.beat + 1) else tr
                },
            )
        }
        val updated = node.copy(builds = shiftedBuilds, specimen = shiftedSpecimen)
        return updated.withChildren(updated.children.map { shiftNodeBeatsOnInsert(it, atBeat) })
    }

    private fun shiftNodeBeatsOnDelete(node: NodeSpec, deletedBeat: Int): NodeSpec {
        val targetBeat = deletedBeat - 1
        // Builds already on targetBeat stay first, then builds moved from deletedBeat keep their order.
        val alreadyOnTarget = node.builds.filter { it.beat == targetBeat }
        val movedFromDeleted = node.builds.filter { it.beat == deletedBeat }.map { it.withBeat(targetBeat) }
        val othersBefore = node.builds.filter { it.beat < targetBeat }
        val othersAfter = node.builds.filter { it.beat > deletedBeat }.map { it.withBeat(it.beat - 1) }

        val mergedBuilds = buildList(node.builds.size) {
            addAll(othersBefore)
            addAll(alreadyOnTarget)
            addAll(movedFromDeleted)
            addAll(othersAfter)
        }
        val shiftedSpecimen = node.specimen?.let { sp ->
            if (sp.tracks.isEmpty()) sp else sp.copy(
                tracks = sp.tracks.mapValues { (_, tr) ->
                    when {
                        tr.beat == null -> tr
                        tr.beat >= deletedBeat -> tr.copy(beat = tr.beat - 1)
                        else -> tr
                    }
                },
            )
        }
        val updated = node.copy(builds = mergedBuilds, specimen = shiftedSpecimen)
        return updated.withChildren(updated.children.map { shiftNodeBeatsOnDelete(it, deletedBeat) })
    }

    private fun updateSlide(
        document: DeckDocument,
        slideId: String,
        transform: (SlideSpec) -> SlideSpec,
    ): DeckDocument {
        val idx = document.slides.indexOfFirst { it.id == slideId }
        require(idx >= 0) { "Slide '$slideId' not found." }
        val updated = document.slides.toMutableList()
        updated[idx] = transform(updated[idx])
        return document.copy(slides = updated)
    }

    private fun findNode(nodes: List<NodeSpec>, nodeId: String): NodeSpec? {
        for (n in nodes) {
            if (n.id == nodeId) return n
            findNode(n.children, nodeId)?.let { return it }
        }
        return null
    }

    private fun rewriteNodeTree(node: NodeSpec, transform: (NodeSpec) -> NodeSpec): NodeSpec {
        val rewrittenChildren = node.children.map { rewriteNodeTree(it, transform) }
        return transform(node.withChildren(rewrittenChildren))
    }

    private fun NodeSpec.withChildren(newChildren: List<NodeSpec>): NodeSpec = when {
        stack != null -> copy(stack = stack.copy(children = newChildren))
        row != null -> copy(row = row.copy(children = newChildren))
        column != null -> copy(column = column.copy(children = newChildren))
        loupe != null -> copy(loupe = loupe.copy(children = newChildren))
        else -> this
    }

    private fun Build.withBeat(newBeat: Int): Build = when (this) {
        is Build.Enter -> copy(beat = newBeat)
        is Build.Exit -> copy(beat = newBeat)
        is Build.Emphasis -> copy(beat = newBeat)
        is Build.Change -> copy(beat = newBeat)
    }

    private fun Build.withAfter(newAfter: String?): Build = when (this) {
        is Build.Enter -> copy(after = newAfter)
        is Build.Exit -> copy(after = newAfter)
        is Build.Emphasis -> copy(after = newAfter)
        is Build.Change -> copy(after = newAfter)
    }
}
