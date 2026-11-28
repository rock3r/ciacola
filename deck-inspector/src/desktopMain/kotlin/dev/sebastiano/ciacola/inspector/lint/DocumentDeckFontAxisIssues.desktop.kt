package dev.sebastiano.ciacola.inspector.lint

import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckFontAxisLint
import dev.sebastiano.ciacola.document.DocumentIssue
import dev.sebastiano.ciacola.document.render.createDocumentFontResolver
import java.nio.file.Path

/**
 * Checks static `axes` against the `fvar` axes of the faces they resolve to, using the same
 * shared font resolver the renderer uses for [deckDir].
 */
internal fun fontAxisIssues(document: DeckDocument, deckDir: Path?): List<DocumentIssue> {
    if (!DeckFontAxisLint.declaresAxes(document)) return emptyList()
    val resolver = createDocumentFontResolver(document, deckDir?.toAbsolutePath()?.toString())
    return DeckFontAxisLint.validate(document, resolver)
}
