package dev.sebastiano.ciacola.inspector.element

import dev.sebastiano.ciacola.document.MorphBy

/** Text morph granularity offered by the Design inspector's Text section (full labels, never truncated). */
enum class TextMorphGranularity(val label: String, val morphBy: MorphBy?) {
    Whole("Whole", null),
    Paragraph("Paragraph", MorphBy.Paragraph),
    Word("Word", MorphBy.Word),
    Character("Character", MorphBy.Character),
    Line("Line", MorphBy.Line),
}
