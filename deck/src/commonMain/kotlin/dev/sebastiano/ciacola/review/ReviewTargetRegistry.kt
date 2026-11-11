package dev.sebastiano.ciacola.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

interface ReviewStageBridge {
    fun updateSceneHeader(header: String)
    fun registerTarget(
        key: Any,
        kind: String,
        label: String,
        textSnippet: String,
        isContainer: Boolean,
        coordinates: LayoutCoordinates,
    )
    fun unregisterTarget(key: Any)
}

val LocalReviewStageBridge = staticCompositionLocalOf<ReviewStageBridge?> { null }

@Composable
fun ReportSceneHeader(header: String) {
    val bridge = LocalReviewStageBridge.current ?: return
    SideEffect {
        bridge.updateSceneHeader(header)
    }
}

@Composable
fun Modifier.reviewTarget(
    kind: String,
    label: String,
    textSnippet: String = "",
    isContainer: Boolean = false,
): Modifier {
    val bridge = LocalReviewStageBridge.current ?: return this
    val targetKey = remember { Any() }
    return this then ReviewTargetElement(
        bridge = bridge,
        targetKey = targetKey,
        kind = kind,
        label = label,
        textSnippet = textSnippet,
        isContainer = isContainer,
    )
}

private data class ReviewTargetElement(
    val bridge: ReviewStageBridge,
    val targetKey: Any,
    val kind: String,
    val label: String,
    val textSnippet: String,
    val isContainer: Boolean,
) : ModifierNodeElement<ReviewTargetNode>() {
    override fun create(): ReviewTargetNode = ReviewTargetNode(
        bridge = bridge,
        targetKey = targetKey,
        kind = kind,
        label = label,
        textSnippet = textSnippet,
        isContainer = isContainer,
    )

    override fun update(node: ReviewTargetNode) {
        node.bridge = bridge
        node.targetKey = targetKey
        node.kind = kind
        node.label = label
        node.textSnippet = textSnippet
        node.isContainer = isContainer
        node.publishCurrentCoordinates()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "reviewTarget"
        properties["kind"] = kind
        properties["label"] = label
    }
}

private class ReviewTargetNode(
    var bridge: ReviewStageBridge,
    var targetKey: Any,
    var kind: String,
    var label: String,
    var textSnippet: String,
    var isContainer: Boolean,
) : LayoutAwareModifierNode, Modifier.Node() {
    private var lastCoordinates: LayoutCoordinates? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        lastCoordinates = coordinates
        publishCurrentCoordinates()
    }

    fun publishCurrentCoordinates() {
        val coords = lastCoordinates ?: return
        if (isAttached && coords.isAttached) {
            bridge.registerTarget(
                key = targetKey,
                kind = kind,
                label = label,
                textSnippet = textSnippet,
                isContainer = isContainer,
                coordinates = coords,
            )
        }
    }

    override fun onDetach() {
        lastCoordinates = null
        bridge.unregisterTarget(targetKey)
    }
}
