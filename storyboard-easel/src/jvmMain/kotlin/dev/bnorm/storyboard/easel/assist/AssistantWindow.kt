package dev.bnorm.storyboard.easel.assist

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.DefaultWindowSize
import dev.bnorm.storyboard.easel.EaselWindow

@Composable
fun rememberAssistantWindow(animatic: Animatic, captions: List<Caption> = emptyList()): AssistantWindow {
    // Keep one window across storyboard rebuilds (hot reload, theme switches, timeline edits each
    // produce a new Animatic): recreating it would reset `visible` and close the Assistant.
    return remember(captions) { AssistantWindow(animatic, captions) }.also { it.animatic = animatic }
}

class AssistantWindow(
    animatic: Animatic,
    initialCaptions: List<Caption> = emptyList(),
) : EaselWindow {
    internal var animatic: Animatic by mutableStateOf(animatic)

    private val captions = SnapshotStateList<Caption>().apply {
        addAll(initialCaptions)
    }

    override val name: String
        get() = "Assistant"

    override var visible: Boolean by mutableStateOf(false)

    // Presenter notes and the next-slide preview fit the classic compact size.
    override val initialSize: DpSize get() = DefaultWindowSize

    override val decorator = ContentDecorator { content ->
        CompositionLocalProvider(
            value = LocalCaptions provides this.captions,
            content = content
        )
    }

    @Composable
    override fun MenuScope.Menu() {
        CheckboxItem(
            text = "Show",
            checked = visible,
            shortcut = KeyShortcut(Key.F2),
        ) {
            visible = it
        }
    }

    @Composable
    override fun Content() {
        EaselAssistant(animatic = animatic, captions = captions)
    }
}
