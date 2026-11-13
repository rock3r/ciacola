package dev.bnorm.storyboard.easel

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator

// TODO name is not consistent with storyboard theme
// TODO mostly specific to desktop, but not exclusive?
interface EaselWindow {
    val name: String
    var visible: Boolean

    val decorator: ContentDecorator get() = ContentDecorator.None

    /**
     * Size of the window the first time it is opened (before it has persisted state). Roomy by
     * default so tool windows such as a timeline or review panel are not cramped; compact
     * windows should override it.
     */
    val initialSize: DpSize get() = DpSize(1280.dp, 800.dp)

    /**
     * Optional minimum size enforced on the AWT window so resizing cannot crush tool panes to 0 px.
     */
    val minimumSize: DpSize? get() = null

    @Composable
    fun MenuScope.Menu()

    @Composable
    fun Content()
}
