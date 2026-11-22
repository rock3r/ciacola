package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable

/**
 * Describes a single UTF-16 range edit emitted by an inline text editor.
 */
@Immutable
data class TextRangeEdit(
    val start: Int,
    val removedLength: Int,
    val insertedText: String,
) {
    val oldEnd: Int get() = start + removedLength
    val deltaLength: Int get() = insertedText.length - removedLength

    fun applyTo(oldText: String): String {
        require(start in 0..oldText.length) { "Edit start $start out of bounds for length ${oldText.length}." }
        require(removedLength >= 0 && oldEnd <= oldText.length) {
            "Edit removedLength $removedLength out of bounds at start=$start for length ${oldText.length}."
        }
        return buildString(oldText.length + deltaLength) {
            append(oldText, 0, start)
            append(insertedText)
            append(oldText, oldEnd, oldText.length)
        }
    }
}

@Immutable
data class ManualDelayRemapNotice(
    val oldAddress: String,
    val reason: String,
)

@Immutable
data class ManualDelayRemapResult(
    val updatedManual: Map<String, Int>,
    val notices: List<ManualDelayRemapNotice>,
)

@Immutable
data class ParsedFragmentAddress(
    val isExit: Boolean,
    val tokenText: String,
    val occurrence: Int,
) {
    fun formatWithOccurrence(newOccurrence: Int): String =
        if (isExit) "-$tokenText#$newOccurrence" else "$tokenText#$newOccurrence"
}

/**
 * Fragment manual-delay edit-provenance remapper.
 *
 * Uses the exact same [TextTokenizer] as the planner so that no surviving manual-delay entry can
 * ever silently slide onto a different occurrence of a repeated token.
 */
object FragmentProvenance {

    /**
     * Parses a manual-delay key such as `"function#0"` or `"-shader#1"`.
     * Returns `null` if the string does not match the `<token>#<nonNegativeInt>` format.
     */
    fun parseAddress(address: String): ParsedFragmentAddress? {
        val hashIdx = address.lastIndexOf('#')
        if (hashIdx <= 0 || hashIdx == address.lastIndex) return null
        val occ = address.substring(hashIdx + 1).toIntOrNull() ?: return null
        if (occ < 0) return null
        val rawPrefix = address.substring(0, hashIdx)
        val isExit = rawPrefix.startsWith('-') && rawPrefix.length > 1
        val tokenText = if (isExit) rawPrefix.substring(1) else rawPrefix
        if (tokenText.isEmpty()) return null
        return ParsedFragmentAddress(isExit = isExit, tokenText = tokenText, occurrence = occ)
    }

    /**
     * Remaps `stagger.manual` entries for a known inline range edit `(start, removedLength, insertedText)`
     * applied to [oldText]:
     * 1. Locates the addressed fragment's UTF-16 `[start, endExclusive)` range in [oldText] and
     *    maps it through [edit] (ranges strictly after the edit shift by `deltaLength`; ranges
     *    strictly before stay unchanged; any range touched/overlapped by the edit is unmapped).
     * 2. Re-tokenizes the new text with [TextTokenizer.tokenize].
     * 3. Keeps the entry ONLY if the mapped range equals the exact UTF-16 range of one token in
     *    the new tokenization AND that token's text is unchanged, rewriting the address to the
     *    token's new occurrence index.
     * 4. Otherwise drops the entry and emits a [ManualDelayRemapNotice].
     *
     * Entries belonging to the other side (e.g. exit `-...` addresses when editing the target text,
     * or non-exit addresses when [editingExitSource] is `true`) are preserved untouched.
     */
    fun remapForRangeEdit(
        oldText: String,
        edit: TextRangeEdit,
        manual: Map<String, Int>,
        by: MorphBy,
        editingExitSource: Boolean = false,
    ): ManualDelayRemapResult {
        if (manual.isEmpty()) return ManualDelayRemapResult(emptyMap(), emptyList())
        val newText = edit.applyTo(oldText)
        val oldTokens = TextTokenizer.tokenize(oldText, by)
        val newTokens = TextTokenizer.tokenize(newText, by)

        val oldByKey = oldTokens.associateBy { it.address }
        val newByRange = newTokens.associateBy { it.start to it.endExclusive }

        val updated = LinkedHashMap<String, Int>()
        val notices = mutableListOf<ManualDelayRemapNotice>()

        for ((rawKey, delayMs) in manual) {
            val parsed = parseAddress(rawKey)
            if (parsed == null || parsed.isExit != editingExitSource) {
                updated[rawKey] = delayMs
                continue
            }

            val lookupKey = "${parsed.tokenText}#${parsed.occurrence}"
            val oldToken = oldByKey[lookupKey]
            if (oldToken == null) {
                notices += ManualDelayRemapNotice(
                    oldAddress = rawKey,
                    reason = "Fragment '$rawKey' was not present in the previous text and was removed.",
                )
                continue
            }

            val mappedRange = mapRangeThroughEdit(oldToken.start, oldToken.endExclusive, edit)
            if (mappedRange == null) {
                notices += ManualDelayRemapNotice(
                    oldAddress = rawKey,
                    reason = "Edit overlapped fragment '$rawKey'; manual delay removed.",
                )
                continue
            }

            val exactNewToken = newByRange[mappedRange]
            if (exactNewToken != null && exactNewToken.text == oldToken.text) {
                val newAddress = parsed.formatWithOccurrence(exactNewToken.occurrence)
                updated[newAddress] = delayMs
            } else {
                notices += ManualDelayRemapNotice(
                    oldAddress = rawKey,
                    reason = "Re-tokenization changed fragment boundaries or text for '$rawKey'; manual delay removed.",
                )
            }
        }

        return ManualDelayRemapResult(updatedManual = updated, notices = notices)
    }

    /**
     * Remaps `stagger.manual` entries for a whole-text replacement without edit-range provenance:
     * - An entry survives ONLY if its token text occurs **exactly once** in [oldText] AND
     *   **exactly once** in [newText].
     * - Every other entry for that side is removed with a [ManualDelayRemapNotice].
     */
    fun remapForWholeReplacement(
        oldText: String,
        newText: String,
        manual: Map<String, Int>,
        by: MorphBy,
        editingExitSource: Boolean = false,
    ): ManualDelayRemapResult {
        if (manual.isEmpty()) return ManualDelayRemapResult(emptyMap(), emptyList())
        val oldCounts = TextTokenizer.tokenize(oldText, by).groupingBy { it.text }.eachCount()
        val newCounts = TextTokenizer.tokenize(newText, by).groupingBy { it.text }.eachCount()

        val updated = LinkedHashMap<String, Int>()
        val notices = mutableListOf<ManualDelayRemapNotice>()

        for ((rawKey, delayMs) in manual) {
            val parsed = parseAddress(rawKey)
            if (parsed == null || parsed.isExit != editingExitSource) {
                updated[rawKey] = delayMs
                continue
            }

            val oldCount = oldCounts[parsed.tokenText] ?: 0
            val newCount = newCounts[parsed.tokenText] ?: 0
            if (oldCount == 1 && newCount == 1 && parsed.occurrence == 0) {
                updated[parsed.formatWithOccurrence(0)] = delayMs
            } else {
                notices += ManualDelayRemapNotice(
                    oldAddress = rawKey,
                    reason = "Whole-text replacement cannot preserve provenance for '$rawKey' " +
                        "(occurrences: old=$oldCount, new=$newCount); manual delay removed.",
                )
            }
        }

        return ManualDelayRemapResult(updatedManual = updated, notices = notices)
    }

    /**
     * Evaluates an **external** edit to a file (which Studio does not rewrite automatically) and
     * produces lint warnings for any manual-delay entry whose token text is not unique in both
     * [oldText] and [newText].
     */
    fun lintExternalEdit(
        oldText: String,
        newText: String,
        manual: Map<String, Int>,
        by: MorphBy,
        addressPrefix: String,
        editingExitSource: Boolean = false,
    ): List<DocumentIssue> {
        if (manual.isEmpty() || oldText == newText) return emptyList()
        val oldCounts = TextTokenizer.tokenize(oldText, by).groupingBy { it.text }.eachCount()
        val newCounts = TextTokenizer.tokenize(newText, by).groupingBy { it.text }.eachCount()

        val issues = mutableListOf<DocumentIssue>()
        for (rawKey in manual.keys) {
            val parsed = parseAddress(rawKey) ?: continue
            if (parsed.isExit != editingExitSource) continue
            val oldCount = oldCounts[parsed.tokenText] ?: 0
            val newCount = newCounts[parsed.tokenText] ?: 0
            if (oldCount != 1 || newCount != 1) {
                issues += DocumentIssue(
                    severity = IssueSeverity.LintWarning,
                    code = "MANUAL_DELAY_EXTERNAL_EDIT_AMBIGUOUS",
                    address = addressPrefix,
                    message = "External text edit changed a node with manual delay '$rawKey' whose token " +
                        "'${parsed.tokenText}' is not unique in both texts (old=$oldCount, new=$newCount); " +
                        "it may now address a different fragment.",
                )
            }
        }
        return issues
    }

    /**
     * Inspects all text nodes across [oldDocument] and [newDocument] after an external file edit
     * and collects [lintExternalEdit] warnings for any node whose text changed while carrying
     * `stagger.manual` entries.
     */
    fun lintExternalDocumentEdit(
        oldDocument: DeckDocument,
        newDocument: DeckDocument,
    ): List<DocumentIssue> {
        val oldSlides = oldDocument.slides.associateBy { it.id }
        val issues = mutableListOf<DocumentIssue>()
        for (newSlide in newDocument.slides) {
            val oldSlide = oldSlides[newSlide.id] ?: continue
            val oldNodes = collectNodesById(oldSlide.nodes)
            val newNodes = collectNodesById(newSlide.nodes)
            for ((nodeId, newNode) in newNodes) {
                val oldNode = oldNodes[nodeId] ?: continue
                if (newNode.kind != NodeKind.Text || oldNode.kind != NodeKind.Text) continue
                val baseOldText = oldNode.text?.value?.plainText.orEmpty()
                val baseNewText = newNode.text?.value?.plainText.orEmpty()

                for ((bIdx, build) in newNode.builds.withIndex()) {
                    val buildAddr = build.id?.let { "${newSlide.id}/$nodeId#$it" } ?: "${newSlide.id}/$nodeId#builds[$bIdx]"
                    when (build) {
                        is Build.Enter -> {
                            val manual = build.stagger?.manual.orEmpty()
                            val by = deliveryToMorphBy(build.by) ?: continue
                            if (manual.isEmpty()) continue
                            val oldText = JsonMergePatch.resolveNodeAtBeat(oldNode, build.beat).text?.value?.plainText.orEmpty()
                            val newText = JsonMergePatch.resolveNodeAtBeat(newNode, build.beat).text?.value?.plainText.orEmpty()
                            issues += lintExternalEdit(oldText, newText, manual, by, buildAddr, editingExitSource = false)
                        }
                        is Build.Exit -> {
                            val manual = build.stagger?.manual.orEmpty()
                            val by = deliveryToMorphBy(build.by) ?: continue
                            if (manual.isEmpty()) continue
                            val prevBeat = (build.beat - 1).coerceAtLeast(0)
                            val oldText = JsonMergePatch.resolveNodeAtBeat(oldNode, prevBeat).text?.value?.plainText.orEmpty()
                            val newText = JsonMergePatch.resolveNodeAtBeat(newNode, prevBeat).text?.value?.plainText.orEmpty()
                            issues += lintExternalEdit(oldText, newText, manual, by, buildAddr, editingExitSource = false)
                        }
                        is Build.Emphasis -> {
                            val manual = build.stagger?.manual.orEmpty()
                            val by = deliveryToMorphBy(build.by) ?: continue
                            if (manual.isEmpty()) continue
                            val oldText = JsonMergePatch.resolveNodeAtBeat(oldNode, build.beat).text?.value?.plainText.orEmpty()
                            val newText = JsonMergePatch.resolveNodeAtBeat(newNode, build.beat).text?.value?.plainText.orEmpty()
                            issues += lintExternalEdit(oldText, newText, manual, by, buildAddr, editingExitSource = false)
                        }
                        is Build.Change -> {
                            val morph = build.morph ?: newNode.text?.morph ?: continue
                            val manual = morph.stagger?.manual.orEmpty()
                            if (manual.isEmpty()) continue
                            val oldTarget = JsonMergePatch.resolveNodeAtBeat(oldNode, build.beat).text?.value?.plainText.orEmpty()
                            val newTarget = JsonMergePatch.resolveNodeAtBeat(newNode, build.beat).text?.value?.plainText.orEmpty()
                            issues += lintExternalEdit(oldTarget, newTarget, manual, morph.by, buildAddr, editingExitSource = false)
                            if (build.beat > 0) {
                                val oldSrc = JsonMergePatch.resolveNodeAtBeat(oldNode, build.beat - 1).text?.value?.plainText.orEmpty()
                                val newSrc = JsonMergePatch.resolveNodeAtBeat(newNode, build.beat - 1).text?.value?.plainText.orEmpty()
                                issues += lintExternalEdit(oldSrc, newSrc, manual, morph.by, buildAddr, editingExitSource = true)
                            }
                        }
                    }
                }
                // Also lint node-level text.morph.stagger.manual against beat-0 text changes if unused above.
                val nodeMorph = newNode.text?.morph
                val nodeManual = nodeMorph?.stagger?.manual.orEmpty()
                if (nodeManual.isNotEmpty() && newNode.builds.none { it is Build.Change }) {
                    issues += lintExternalEdit(baseOldText, baseNewText, nodeManual, nodeMorph!!.by, "${newSlide.id}/$nodeId", editingExitSource = false)
                }
            }
        }

        // Also lint cross-slide transition.text[nodeId].stagger.manual across active slide pairs.
        val activeNewSlides = newDocument.slides.filter { !it.skipped }
        for (i in 0 until activeNewSlides.lastIndex) {
            val newFrom = activeNewSlides[i]
            val newTo = activeNewSlides[i + 1]
            val transText = newFrom.transition?.text.orEmpty()
            if (transText.isEmpty()) continue
            val oldFrom = oldSlides[newFrom.id]
            val oldTo = oldSlides[newTo.id]
            val oldFromNodes = oldFrom?.let { collectNodesById(it.nodes) }.orEmpty()
            val newFromNodes = collectNodesById(newFrom.nodes)
            val oldToNodes = oldTo?.let { collectNodesById(it.nodes) }.orEmpty()
            val newToNodes = collectNodesById(newTo.nodes)
            val oldFromLastBeat = oldFrom?.beats?.lastIndex?.coerceAtLeast(0) ?: 0
            val newFromLastBeat = newFrom.beats.lastIndex.coerceAtLeast(0)
            for ((nodeId, morph) in transText) {
                val manual = morph.stagger?.manual.orEmpty()
                if (manual.isEmpty()) continue
                val addr = "${newFrom.id}/transition/text/$nodeId"
                val oldFromNode = oldFromNodes[nodeId]
                val newFromNode = newFromNodes[nodeId]
                if (oldFromNode?.kind == NodeKind.Text && newFromNode?.kind == NodeKind.Text) {
                    val oldSrc = JsonMergePatch.resolveNodeAtBeat(oldFromNode, oldFromLastBeat).text?.value?.plainText.orEmpty()
                    val newSrc = JsonMergePatch.resolveNodeAtBeat(newFromNode, newFromLastBeat).text?.value?.plainText.orEmpty()
                    issues += lintExternalEdit(oldSrc, newSrc, manual, morph.by, addr, editingExitSource = true)
                }
                val oldToNode = oldToNodes[nodeId]
                val newToNode = newToNodes[nodeId]
                if (oldToNode?.kind == NodeKind.Text && newToNode?.kind == NodeKind.Text) {
                    val oldDst = JsonMergePatch.resolveNodeAtBeat(oldToNode, 0).text?.value?.plainText.orEmpty()
                    val newDst = JsonMergePatch.resolveNodeAtBeat(newToNode, 0).text?.value?.plainText.orEmpty()
                    issues += lintExternalEdit(oldDst, newDst, manual, morph.by, addr, editingExitSource = false)
                }
            }
        }
        return issues
    }

    private fun collectNodesById(nodes: List<NodeSpec>): Map<String, NodeSpec> {
        val map = LinkedHashMap<String, NodeSpec>()
        fun visit(list: List<NodeSpec>) {
            for (n in list) {
                map[n.id] = n
                if (n.children.isNotEmpty()) visit(n.children)
            }
        }
        visit(nodes)
        return map
    }

    private fun deliveryToMorphBy(by: DeliveryBy): MorphBy? = when (by) {
        DeliveryBy.Paragraph -> MorphBy.Paragraph
        DeliveryBy.Word -> MorphBy.Word
        DeliveryBy.Character -> MorphBy.Character
        DeliveryBy.Whole, DeliveryBy.Child -> null
    }

    /**
     * Maps `[tokStart, tokEnd)` through [edit].
     *
     * Rules:
     * - Strictly before edit (`tokEnd <= edit.start`): unchanged, PROVIDED a zero-length insertion
     *   at `tokEnd` does not break boundaries (which step 3 re-tokenization catches!). Wait: an
     *   insertion strictly inside `(tokStart, tokEnd)` overlaps and returns `null`. An insertion at
     *   `edit.start == tokStart` shifts the token right by `insertedText.length` (so typing `"item "`
     *   at offset 0 shifts `"item"@0..4` to `5..9`, and typing `'x'` at offset 0 produces `"xitem"@0..5`
     *   which fails step 3 because mapped range `1..5` != `0..5`!).
     * - An insertion at `edit.start == tokEnd` leaves `[tokStart, tokEnd)` unshifted (so typing `" item"`
     *   at offset 4 leaves `"item"@0..4` matching `0..4`, while typing `'x'` or `\u0301` at offset 4
     *   produces `"itemx"@0..5` or `"item\u0301"@0..5`, failing step 3!).
     * - Any non-empty removal overlapping `[tokStart, tokEnd)` (`edit.removedLength > 0 && edit.start < tokEnd && edit.oldEnd > tokStart`)
     *   unmaps the token (`null`).
     */
    private fun mapRangeThroughEdit(tokStart: Int, tokEnd: Int, edit: TextRangeEdit): Pair<Int, Int>? {
        if (edit.removedLength == 0) {
            // Pure insertion at edit.start
            return when {
                edit.start <= tokStart -> (tokStart + edit.deltaLength) to (tokEnd + edit.deltaLength)
                edit.start >= tokEnd -> tokStart to tokEnd
                else -> null // Insertion strictly inside the token
            }
        }
        // Deletion / replacement of [edit.start, edit.oldEnd)
        return when {
            edit.oldEnd <= tokStart -> (tokStart + edit.deltaLength) to (tokEnd + edit.deltaLength)
            edit.start >= tokEnd -> tokStart to tokEnd
            else -> null // Overlaps token
        }
    }

    /**
     * Remaps `stagger.manual` entries across [nodeAfter]'s text morph and builds, comparing each
     * build's actual before/after text states against [nodeBefore] so unaffected beats and builds
     * keep their fragment delays untouched.
     */
    internal fun remapTextNodeManualDelays(
        nodeBefore: NodeSpec,
        nodeAfter: NodeSpec,
        editedBeat: Int,
        oldText: String,
        newText: String,
        remap: (manual: Map<String, Int>, by: MorphBy, isExit: Boolean) -> ManualDelayRemapResult,
        noticesOut: MutableList<ManualDelayRemapNotice>,
    ): NodeSpec {
        fun textAtBeat(n: NodeSpec, b: Int): String =
            JsonMergePatch.resolveNodeAtBeat(n, b).text?.value?.plainText.orEmpty()

        fun textBeforeBuild(n: NodeSpec, idx: Int): String =
            JsonMergePatch.resolveNodeBeforeBuild(n, idx).text?.value?.plainText.orEmpty()

        fun textAfterBuild(n: NodeSpec, idx: Int): String =
            JsonMergePatch.resolveNodeAfterBuild(n, idx).text?.value?.plainText.orEmpty()

        fun remapSide(
            manual: Map<String, Int>,
            by: MorphBy,
            beforeText: String,
            afterText: String,
            isExit: Boolean,
        ): Map<String, Int> {
            if (manual.isEmpty() || beforeText == afterText) return manual
            val res = if (beforeText == oldText && afterText == newText) {
                remap(manual, by, isExit)
            } else {
                remapForWholeReplacement(beforeText, afterText, manual, by, isExit)
            }
            noticesOut += res.notices
            return res.updatedManual
        }

        val oldIndexForNewBuild: List<Int?> = mapOldBuildIndices(nodeBefore, nodeAfter, editedBeat)
        var inheritedMorphTargetChange: Pair<String, String>? = null
        var inheritedMorphExitChange: Pair<String, String>? = null

        val updatedBuilds = nodeAfter.builds.mapIndexed { newIdx, build ->
            val oldIdx = oldIndexForNewBuild[newIdx]
            when (build) {
                is Build.Change -> {
                    val beforeSrc = if (oldIdx != null) {
                        textBeforeBuild(nodeBefore, oldIdx)
                    } else {
                        textAtBeat(nodeBefore, (build.beat - 1).coerceAtLeast(0))
                    }
                    val afterSrc = textBeforeBuild(nodeAfter, newIdx)
                    val beforeDst = if (oldIdx != null) {
                        textAfterBuild(nodeBefore, oldIdx)
                    } else {
                        textAtBeat(nodeBefore, build.beat)
                    }
                    val afterDst = textAfterBuild(nodeAfter, newIdx)

                    val morph = build.morph
                    if (morph == null) {
                        if (beforeDst != afterDst && inheritedMorphTargetChange == null) {
                            inheritedMorphTargetChange = beforeDst to afterDst
                        }
                        if (beforeSrc != afterSrc && inheritedMorphExitChange == null) {
                            inheritedMorphExitChange = beforeSrc to afterSrc
                        }
                        build
                    } else {
                        val stagger = morph.stagger
                        if (stagger != null && stagger.manual.isNotEmpty()) {
                            var manual = remapSide(stagger.manual, morph.by, beforeDst, afterDst, isExit = false)
                            manual = remapSide(manual, morph.by, beforeSrc, afterSrc, isExit = true)
                            if (manual == stagger.manual) {
                                build
                            } else {
                                build.copy(morph = morph.copy(stagger = stagger.copy(manual = manual)))
                            }
                        } else {
                            build
                        }
                    }
                }
                is Build.Enter -> {
                    val stagger = build.stagger
                    val by = deliveryToMorphBy(build.by)
                    if (stagger != null && by != null && stagger.manual.isNotEmpty()) {
                        val before = textAtBeat(nodeBefore, build.beat)
                        val after = textAtBeat(nodeAfter, build.beat)
                        val manual = remapSide(stagger.manual, by, before, after, isExit = false)
                        if (manual == stagger.manual) build else build.copy(stagger = stagger.copy(manual = manual))
                    } else {
                        build
                    }
                }
                is Build.Exit -> {
                    val stagger = build.stagger
                    val by = deliveryToMorphBy(build.by)
                    if (stagger != null && by != null && stagger.manual.isNotEmpty()) {
                        val exitBeat = if (build.beat > 0) build.beat - 1 else build.beat
                        val before = textAtBeat(nodeBefore, exitBeat)
                        val after = textAtBeat(nodeAfter, exitBeat)
                        val manual = remapSide(stagger.manual, by, before, after, isExit = false)
                        if (manual == stagger.manual) build else build.copy(stagger = stagger.copy(manual = manual))
                    } else {
                        build
                    }
                }
                is Build.Emphasis -> {
                    val stagger = build.stagger
                    val by = deliveryToMorphBy(build.by)
                    if (stagger != null && by != null && stagger.manual.isNotEmpty()) {
                        val before = textAtBeat(nodeBefore, build.beat)
                        val after = textAtBeat(nodeAfter, build.beat)
                        val manual = remapSide(stagger.manual, by, before, after, isExit = false)
                        if (manual == stagger.manual) build else build.copy(stagger = stagger.copy(manual = manual))
                    } else {
                        build
                    }
                }
            }
        }

        // Also inspect pruned Build.Change entries that inherited `node.text.morph` so stale
        // inherited target/exit delays are still remapped when the change build disappears.
        val matchedOldIndices = oldIndexForNewBuild.filterNotNullTo(HashSet())
        for ((oldIdx, oldBuild) in nodeBefore.builds.withIndex()) {
            if (oldIdx in matchedOldIndices || oldBuild !is Build.Change || oldBuild.morph != null) continue
            val beforeSrc = textBeforeBuild(nodeBefore, oldIdx)
            val afterSrc = textAtBeat(nodeAfter, (oldBuild.beat - 1).coerceAtLeast(0))
            val beforeDst = textAfterBuild(nodeBefore, oldIdx)
            val afterDst = textAtBeat(nodeAfter, oldBuild.beat)
            if (beforeDst != afterDst && inheritedMorphTargetChange == null) {
                inheritedMorphTargetChange = beforeDst to afterDst
            }
            if (beforeSrc != afterSrc && inheritedMorphExitChange == null) {
                inheritedMorphExitChange = beforeSrc to afterSrc
            }
        }

        val updatedText = nodeAfter.text?.let { tp ->
            val morph = tp.morph
            val stagger = morph?.stagger
            if (morph != null && stagger != null && stagger.manual.isNotEmpty()) {
                val baseBefore = textAtBeat(nodeBefore, 0)
                val baseAfter = textAtBeat(nodeAfter, 0)
                var manual = stagger.manual
                val targetPair = if (baseBefore != baseAfter) baseBefore to baseAfter else inheritedMorphTargetChange
                if (targetPair != null) {
                    manual = remapSide(manual, morph.by, targetPair.first, targetPair.second, isExit = false)
                }
                val exitPair = inheritedMorphExitChange
                if (exitPair != null) {
                    manual = remapSide(manual, morph.by, exitPair.first, exitPair.second, isExit = true)
                }
                if (manual == stagger.manual) tp else tp.copy(morph = morph.copy(stagger = stagger.copy(manual = manual)))
            } else {
                tp
            }
        }

        return nodeAfter.copy(text = updatedText, builds = updatedBuilds)
    }

    /**
     * Remaps cross-slide `transition.text[nodeId].stagger.manual` (and inherited cross-slide
     * `text.morph` delays when no transition override is present) whenever editing [nodeId] on
     * [slideId] changes the slide's outgoing last-beat text or incoming beat-0 text.
     */
    internal fun remapCrossSlideTransitionManualDelays(
        documentBefore: DeckDocument,
        documentAfter: DeckDocument,
        slideId: String,
        nodeId: String,
        oldText: String,
        newText: String,
        remap: (manual: Map<String, Int>, by: MorphBy, isExit: Boolean) -> ManualDelayRemapResult,
        noticesOut: MutableList<ManualDelayRemapNotice>,
    ): DeckDocument {
        val slideIdx = documentAfter.slides.indexOfFirst { it.id == slideId }
        if (slideIdx < 0) return documentAfter
        val slideBefore = documentBefore.slides.firstOrNull { it.id == slideId } ?: return documentAfter
        val slideAfter = documentAfter.slides[slideIdx]
        val nodeBefore = collectNodesById(slideBefore.nodes)[nodeId] ?: return documentAfter
        val nodeAfter = collectNodesById(slideAfter.nodes)[nodeId] ?: return documentAfter
        if (nodeBefore.kind != NodeKind.Text || nodeAfter.kind != NodeKind.Text) return documentAfter

        fun remapManual(
            manual: Map<String, Int>,
            by: MorphBy,
            before: String,
            after: String,
            isExit: Boolean,
        ): Map<String, Int> {
            if (manual.isEmpty() || before == after) return manual
            val res = if (before == oldText && after == newText) {
                remap(manual, by, isExit)
            } else {
                remapForWholeReplacement(before, after, manual, by, isExit)
            }
            noticesOut += res.notices
            return res.updatedManual
        }

        fun updateTransitionMorph(
            slide: SlideSpec,
            before: String,
            after: String,
            isExit: Boolean,
        ): SlideSpec {
            val tr = slide.transition ?: return slide
            val morph = tr.text[nodeId] ?: return slide
            val stagger = morph.stagger ?: return slide
            val updatedManual = remapManual(stagger.manual, morph.by, before, after, isExit)
            if (updatedManual == stagger.manual) return slide
            val updatedTextMap = LinkedHashMap(tr.text).apply {
                put(nodeId, morph.copy(stagger = stagger.copy(manual = updatedManual)))
            }
            return slide.copy(transition = tr.copy(text = updatedTextMap))
        }

        fun updateNodeInheritedMorph(
            slide: SlideSpec,
            targetNodeId: String,
            before: String,
            after: String,
            isExit: Boolean,
            onlyWhenNoChangeBuilds: Boolean = false,
        ): SlideSpec {
            fun rewrite(nodes: List<NodeSpec>): List<NodeSpec> = nodes.map { n ->
                val updatedNode = if (n.id == targetNodeId && n.text != null && (!onlyWhenNoChangeBuilds || n.builds.none { it is Build.Change })) {
                    val morph = n.text.morph
                    val stagger = morph?.stagger
                    if (morph != null && stagger != null && stagger.manual.isNotEmpty()) {
                        val updatedManual = remapManual(stagger.manual, morph.by, before, after, isExit)
                        if (updatedManual == stagger.manual) {
                            n
                        } else {
                            n.copy(text = n.text.copy(morph = morph.copy(stagger = stagger.copy(manual = updatedManual))))
                        }
                    } else {
                        n
                    }
                } else {
                    n
                }
                when {
                    updatedNode.stack != null -> updatedNode.copy(stack = updatedNode.stack.copy(children = rewrite(updatedNode.children)))
                    updatedNode.row != null -> updatedNode.copy(row = updatedNode.row.copy(children = rewrite(updatedNode.children)))
                    updatedNode.column != null -> updatedNode.copy(column = updatedNode.column.copy(children = rewrite(updatedNode.children)))
                    updatedNode.loupe != null -> updatedNode.copy(loupe = updatedNode.loupe.copy(children = rewrite(updatedNode.children)))
                    else -> updatedNode
                }
            }
            return slide.copy(nodes = rewrite(slide.nodes))
        }

        val updatedSlides = documentAfter.slides.toMutableList()
        var changed = false

        // 1. Outgoing transition from `slideId` (uses `slideId`'s last beat as exit source).
        val lastBeatBefore = slideBefore.beats.lastIndex.coerceAtLeast(0)
        val lastBeatAfter = slideAfter.beats.lastIndex.coerceAtLeast(0)
        val beforeFromText = JsonMergePatch.resolveNodeAtBeat(nodeBefore, lastBeatBefore).text?.value?.plainText.orEmpty()
        val afterFromText = JsonMergePatch.resolveNodeAtBeat(nodeAfter, lastBeatAfter).text?.value?.plainText.orEmpty()
        if (beforeFromText != afterFromText) {
            val remappedOut = updateTransitionMorph(updatedSlides[slideIdx], beforeFromText, afterFromText, isExit = true)
            if (remappedOut !== updatedSlides[slideIdx]) {
                updatedSlides[slideIdx] = remappedOut
                changed = true
            } else if (!slideAfter.skipped && slideAfter.transition?.text?.containsKey(nodeId) != true) {
                val nextIdx = ((slideIdx + 1) until updatedSlides.size).firstOrNull { !updatedSlides[it].skipped }
                if (nextIdx != null) {
                    val nextNode = collectNodesById(updatedSlides[nextIdx].nodes)[nodeId]
                    if (nextNode?.kind == NodeKind.Text) {
                        if (nextNode.text?.morph != null) {
                            val remappedNext = updateNodeInheritedMorph(
                                slide = updatedSlides[nextIdx],
                                targetNodeId = nodeId,
                                before = beforeFromText,
                                after = afterFromText,
                                isExit = true,
                            )
                            if (remappedNext !== updatedSlides[nextIdx]) {
                                updatedSlides[nextIdx] = remappedNext
                                changed = true
                            }
                        } else {
                            val remappedSelf = updateNodeInheritedMorph(
                                slide = updatedSlides[slideIdx],
                                targetNodeId = nodeId,
                                before = beforeFromText,
                                after = afterFromText,
                                isExit = true,
                                onlyWhenNoChangeBuilds = true,
                            )
                            if (remappedSelf !== updatedSlides[slideIdx]) {
                                updatedSlides[slideIdx] = remappedSelf
                                changed = true
                            }
                        }
                    }
                }
            }
        }

        // 2. Incoming transition into `slideId` from nearest preceding active slide (uses `slideId` beat 0 as target).
        if (!slideAfter.skipped) {
            val prevIdx = (slideIdx - 1 downTo 0).firstOrNull { !updatedSlides[it].skipped }
            if (prevIdx != null) {
                val beforeToText = JsonMergePatch.resolveNodeAtBeat(nodeBefore, 0).text?.value?.plainText.orEmpty()
                val afterToText = JsonMergePatch.resolveNodeAtBeat(nodeAfter, 0).text?.value?.plainText.orEmpty()
                if (beforeToText != afterToText) {
                    val remappedPrev = updateTransitionMorph(updatedSlides[prevIdx], beforeToText, afterToText, isExit = false)
                    if (remappedPrev !== updatedSlides[prevIdx]) {
                        updatedSlides[prevIdx] = remappedPrev
                        changed = true
                    } else if (updatedSlides[prevIdx].transition?.text?.containsKey(nodeId) != true && nodeAfter.text?.morph == null) {
                        val remappedPrevNode = updateNodeInheritedMorph(
                            slide = updatedSlides[prevIdx],
                            targetNodeId = nodeId,
                            before = beforeToText,
                            after = afterToText,
                            isExit = false,
                            onlyWhenNoChangeBuilds = true,
                        )
                        if (remappedPrevNode !== updatedSlides[prevIdx]) {
                            updatedSlides[prevIdx] = remappedPrevNode
                            changed = true
                        }
                    }
                }
            }
        }

        return if (changed) documentAfter.copy(slides = updatedSlides) else documentAfter
    }

    private fun mapOldBuildIndices(
        nodeBefore: NodeSpec,
        nodeAfter: NodeSpec,
        editedBeat: Int,
    ): List<Int?> {
        if (editedBeat > 0) {
            if (nodeAfter.builds.size == nodeBefore.builds.size) {
                return List(nodeAfter.builds.size) { it }
            }
            val insertIdx = nodeBefore.builds.indexOfFirst { it is Build.Change && it.beat > editedBeat }
                .let { if (it >= 0) it else nodeBefore.builds.size }
            return List(nodeAfter.builds.size) { newIdx ->
                when {
                    newIdx < insertIdx -> newIdx
                    newIdx == insertIdx -> null
                    else -> newIdx - 1
                }
            }
        }
        val result = ArrayList<Int?>(nodeAfter.builds.size)
        var oldCursor = 0
        for (afterBuild in nodeAfter.builds) {
            if (afterBuild !is Build.Change) {
                while (oldCursor < nodeBefore.builds.size && nodeBefore.builds[oldCursor] != afterBuild) {
                    oldCursor++
                }
            } else {
                while (oldCursor < nodeBefore.builds.size) {
                    val cand = nodeBefore.builds[oldCursor]
                    if (
                        cand is Build.Change &&
                        cand.beat == afterBuild.beat &&
                        cand.id == afterBuild.id &&
                        cand.path == afterBuild.path &&
                        cand.morph == afterBuild.morph &&
                        cand.after == afterBuild.after &&
                        cand.delayMs == afterBuild.delayMs &&
                        cand.durationMs == afterBuild.durationMs &&
                        cand.curve == afterBuild.curve &&
                        afterBuild.set.keys.all { it in cand.set }
                    ) {
                        break
                    }
                    oldCursor++
                }
            }
            result += oldCursor.takeIf { it < nodeBefore.builds.size }
            if (oldCursor < nodeBefore.builds.size) oldCursor++
        }
        return result
    }
}
