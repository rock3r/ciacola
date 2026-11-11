# MagicText v2 seam (design only)

This document defines a future integration boundary. It does **not** implement MagicText v2,
shaping, cluster extraction, glyph drawing, Canvas text rendering, or gradual entrance.

## Existing separation

The current upstream implementation already has two conceptual stages:

1. `findShared` in `SharedText.kt` computes token correspondence, stable keys, and whether a matched
   token needs a style cross-fade. It has no composable or drawing scope.
2. `MagicTextInternal` in `MagicText.kt` consumes that plan with rows of `Text` composables and
   Compose shared bounds.

`MagicTextPlanner` sits between them. `plan(from, to)` is a pure function of the two texts: the
source keeps canonical keys and the target is matched against it, so the same pair always yields
the same shared-bounds plan regardless of how a scrub or an interrupted transition arrived there
(no settled baseline, no cached interrupted plans). It does not render. The deck adds a second,
intentionally narrow seam:
`DeckTextRenderer`, installed once at the application root. A future implementation replaces that
single binding. There is no scene-level v1/v2 switch and no presentation beat that changes engines.

## Future complete-paragraph layout boundary

The future renderer must accept an entire `AnnotatedString` paragraph plus semantic token ranges.
It must request a complete-paragraph layout from an engine with a contract equivalent to the
following design model (names are illustrative, not checked-in API):

```text
ParagraphLayoutInput
  paragraph: AnnotatedString
  constraints: width, maxLines, overflow
  style/environment: paragraph style, density, font resolver, locale list, layout direction
  semanticRanges: [TextRange]  // UTF-16 ranges used by correspondence

ParagraphLayoutResult
  lines: baseline, ascent, descent, top, bottom, logical UTF-16 range
  bidiRuns: direction, embedding level, logical range, visual order
  clusters: indivisible logical range, glyph range, caret edges, owning line/run
  glyphs: font identity, glyph id, position, advance, bounds
  draw(canvas, origin, paintOverrides)  // engine-owned paragraph drawing operation
```

The engine owns shaping, fallback, line breaking, bidi resolution, cluster boundaries, and drawing.
MagicText must not infer any of those from code points and must not split the paragraph into one
`Text` composable per semantic token.

## Mapping semantic matches to layout

Correspondence remains logical. Each match is `(beforeRange, afterRange, styleChanged)` in UTF-16
paragraph coordinates. After both complete paragraphs are laid out:

1. Intersect a semantic range with the layout engine's cluster ranges.
2. Expand to whole clusters if the semantic boundary lands inside a cluster; never divide a
   cluster, ligature, emoji ZWJ sequence, or combining sequence for drawing.
3. Partition the resulting clusters by line and bidi run, retaining the engine's visual order.
4. Animate geometry between corresponding fragments. Multiple visual fragments may represent one
   logical match when it crosses a line or bidi boundary.
5. Draw unchanged and changed fragments through the paragraph engine. A style-only match uses the
   same logical correspondence with a paint/style cross-fade.

This mapping preserves whole-paragraph shaping and permits semantic token matching without making
tokens into layout owners.

## Representative fixtures and expected logical plans

Ranges below use half-open UTF-16 offsets. `↔` means retained correspondence; `+` and `−` mean
unmatched entrance and exit. Layout fragments are intentionally absent because only a real future
paragraph engine can provide them.

### Repeated token with context

```text
before: "item left item right"
after:  "item right item left"
plan:   before[10,14) ↔ after[0,4)   // item adjacent to right
        before[15,20) ↔ after[5,10)  // right
        before[0,4)   ↔ after[11,15) // item adjacent to left
        before[5,9)   ↔ after[16,20) // left
```

### Combining and emoji

```text
before: "Cafe\u0301 👩🏽‍💻"
after:  "👩🏽‍💻 — Cafe\u0301"
plan:   before[6,13) ↔ after[0,7)    // one semantic emoji token; layout must confirm clusters
        before[0,5)  ↔ after[10,15)  // base plus combining mark
        + after[8,9)                 // em dash
```

### Mixed directionality

```text
before: "status שלום 42"
after:  "42 — שלום status"
plan:   before[12,14) ↔ after[0,2)
        before[7,11)  ↔ after[5,9)
        before[0,6)   ↔ after[10,16)
```

The logical plan says nothing about screen order. `bidiRuns` and their visual order come only from
the complete-paragraph layout result.

### Line movement and style-only change

```text
before: "val answer = 42\nprintln(answer)"
after:  "println(answer)\nval answer = 42" (answer styled differently)
plan:   retained logical tokens map across the newline;
        both occurrences of "answer" use context to disambiguate;
        matching ranges whose AnnotatedString styles differ set styleChanged=true.
```

## Acceptance gate for a future implementation

A future v2 is not real until an actual paragraph engine supplies all structures above and tests
prove line wrapping, bidi, fallback, ligatures, clusters, caret edges, and drawing. A compatibility
shim returning guessed clusters or v1 token boxes must be rejected. The app-wide binding may change
only after the full difficult-text fixture corpus renders and transitions in both directions.
