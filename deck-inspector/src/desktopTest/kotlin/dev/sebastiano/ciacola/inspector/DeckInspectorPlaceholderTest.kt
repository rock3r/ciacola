package dev.sebastiano.ciacola.inspector

import dev.sebastiano.ciacola.inspector.design.DesignInspectorContracts
import dev.sebastiano.ciacola.inspector.element.ElementEditorContracts
import dev.sebastiano.ciacola.inspector.lint.DeckLinterContracts
import kotlin.test.Test
import kotlin.test.assertNotNull

class DeckInspectorPlaceholderTest {
    @Test
    fun inspectorPackagesCompileAndLinkAgainstCoreAndEffects() {
        assertNotNull(DesignInspectorContracts)
        assertNotNull(ElementEditorContracts)
        assertNotNull(DeckLinterContracts)
        assertNotNull(DeckInspectorDesktop)
    }
}
