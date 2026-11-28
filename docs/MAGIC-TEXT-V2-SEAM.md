# MagicText v2 Seam and Two-Condition Admission Architecture

This document defines the integration boundary and implemented architecture of **MagicText v2**
(`dev.sebastiano.ciacola.text.v2` in `:deck-effects`, backed by the character-safety
engine in `:deck-core` `dev.sebastiano.ciacola.core.fonts` and `MagicTextDiff` in
`:storyboard-text`).

## Existing separation and deferred rendering boundary

The original upstream Storyboard pipeline has two conceptual stages:

1. `findShared` in `SharedText.kt` computes token correspondence, stable keys, and whether a matched
   token needs a style cross-fade. It has no composable or drawing scope.
2. `MagicTextInternal` in `MagicText.kt` consumes that plan with rows of `Text` composables and
   Compose shared bounds.

MagicText v2 preserves and tightens that boundary:

- **`MagicTextDiff`** (`dev.bnorm.storyboard.text.magic.MagicTextDiff` in `:storyboard-text`) is a
  pure correspondence planner over UTF-16 `TextRange`s with `MorphBy` (`Word`, `Character`, `Line`),
  grapheme-cluster segmentation (`Character`), `\n`-delimited logical lines (`Line`), switchable
  stopword pruning (`pruneStopwords`), and positional fragment addresses (`"token#n"`, `"-token#n"`).
  `plan(from, to, by, pruneStopwords)` is purely functional and history-independent.
- **`DeckTextRenderer`** (`dev.sebastiano.ciacola.DeckTextRenderer` in `:deck-effects`) sits behind
  one root renderer binding so scenes never select text engines directly. Its primary entry point is
  a **deferred lambda** read only during layout and draw:
  ```kotlin
  @Composable
  fun Text(content: () -> TextContent, modifier: Modifier = Modifier)
  ```
  `MagicTextV2Renderer` reads `content()` exclusively in layout and `drawBehind` phases (never in
  composition), keeping animated sweeps at `recompositionCount == 1` and achieving **0 B/frame**
  managed heap allocation across all warm-cache draws (CPU and Metal).

## Two-Condition Admission

Compose Desktop / Skiko `0.156.1` does not expose SkParagraph's internal glyph runs or cluster maps
via public API without reflection. Instead of adopting reflection or a compatibility shim returning
guessed clusters or v1 token boxes (**which remain explicitly rejected**), MagicText v2 measures
whole paragraphs with Compose `TextMeasurer` and admits piece partitions only when **both**
conditions below hold.

### Condition 1 — Authoritative Character-Safety Engine (checked before drawing)

Every proposed piece boundary is validated structurally (never inferred solely from pixels):

- **`Line`:** Split only at visual line breaks (`TextLayoutResult.getLineStart` / `getLineEnd`).
  Words wrapped across multiple lines are never split mid-line into single-line pieces.
- **`Word`:** Split only at whitespace inside a single bidi run (`getBidiRunDirection`). Adjacent
  tokens bridged by an active continuous `Underline` or `LineThrough` decoration across intervening
  spaces are merged into a single piece so continuous decoration strokes stay unbroken.
- **`Character`:** Governed by **one single production predicate**
  (`CharacterSafetyEngine.certifyParagraph` in `:deck-core`, shared identically by
  `DeckFontResolver` and `MagicTextV2` — no parallel test-only predicate exists):
  1. **Configuration guard:** Only two effective font-feature configurations exist, returned
     by one shared helper `effectiveFeatureConfigForTextNode(characterDeliveryNode)`:
     - `ShaperDefaults` (`fontFeatureSettings = null`) — **immediately returns not character-safe**
       before inspecting any font.
     - `CharacterForcedOff` (`fontFeatureSettings = "liga 0, clig 0, dlig 0, calt 0"`, unquoted
       because Skiko `0.156.1` `FontFeature.parseW3` silently returns `[]` for CSS-quoted strings).
     The same configuration reaches `Rest`, `Morph`, `Build`, word-level tracks on character-delivery
     nodes, and cross-slide overlays so ligatures never re-enable mid-transition.
  2. **Paragraph preconditions and shared face assignment:**
     - **Allow-list only:** Every scalar in the paragraph must lie in Unicode general categories
       `L*`, `N*`, `P*`, `S*`, `Zs`, or `U+000A` (`\n`), and in scripts `{Latin, Greek, Cyrillic,
       Han, Hiragana, Katakana, Common}`. Combining marks (`Mn`/`Mc`/`Me`), non-NFC strings,
       unpaired surrogates, variation selectors (`U+FE00..U+FE0F`, `U+E0100..U+E01EF`), default
       ignorables (including `U+200D` ZWJ and `U+200C` ZWNJ), Hangul syllables and Jamo,
       Arabic/Hebrew/Indic/Thai/complex scripts, and fraction slashes `U+2044` and `U+2215` refuse
       character certification for the **entire paragraph** (falling back to `Word`).
     - **Layout-resolved LTR direction:** Every offset must resolve to `ResolvedTextDirection.Ltr`
       for both paragraph direction and bidi run direction on `wholeLayout`.
     - **Single shared face-assignment function:** `DeckFontResolver.assignParagraphFaces` routes
       every scalar (including monochrome `Noto Emoji` `role = "emoji"` spans for primary-uncovered
       plain emoji, keeping `U+FE0F`/`U+200D` verbatim in the document) and feeds **both** drawing
       (`SpanStyle` generation) and certification. Nominal glyph lookup uses the rendering Skia
       `Typeface` / `SkiaFont.getUTF32Glyph(codePoint)` directly (no independent `cmap` subtable
       guessing). Returning glyph `0` or `>= maxp.numGlyphs` refuses the **entire paragraph** across
       all faces; `\n` (`U+000A`) seeds `{nominal, 0, space}` into the face's paragraph universe `U`.
  3. **Tier A face inspection & 5-level record precedence:**
     - Validates `maxp.numGlyphs`; glyph domain is `G = {0 .. maxp.numGlyphs - 1}`,
       `Class0 = G \ {g | ClassDef(g) > 0}` (explicit zero entries and ranges remain in `Class0`),
       and Format-2 first-position match is `Coverage ∩ Class`. Out-of-domain glyph IDs, unknown
       subtable formats, bad offsets, recursive LookupType `7` cycles, missing/unreadable tables,
       system fonts, `morx`/`mort`/`Silf` tables, or parser budget exhaustion **fail closed**.
     - Feature records are classified in strict precedence order:
       1. `LangSys` required features and all `FeatureVariations` alternatives → **Always-On**.
       2. `{liga, clig, dlig, calt}` (only when `CharacterForcedOff`) → **Forced-Off**.
       3. `ccmp`, `locl` → **Tier B**.
       4. Allow-listed off-by-default opt-in tags (`smcp`, `c2sc`, `ss01..ss20`, `cv01..cv99`,
          `tnum`, `onum`, `pnum`, `lnum`, `zero`, `frac`, `sups`, `subs`, `ordn`, `case`, `titl`,
          `salt`, `swsh`, `hist`, `hlig`, `pcap`, `c2pc`, `cpsp`, `aalt`, `sinf`, `numr`, `dnom`,
          `nalt`, `fwid`, `pwid`, `hwid`, `qwid`, `twid`, `pkna`, `hkna`, `vkna`, `jp78`, `jp83`,
          `jp90`, `jp04`, `nlck`, `trad`, `smpl`, `expt`, `hojo`, `ruby`, `mgrk`, `unic`, `ital`,
          `ornm`, `flac`, `dtls`, `ssty`, `rand`, `opbd`, `lfbd`, `rtbd`, `size`) → **Inactive**.
       5. Everything else / unknown tags → **Always-On**.
     - Tier A rejects a face if any **Always-On** record reaches LookupTypes `4`, `5`, `6`, `8`,
       empty Type `2` (deletion), or LookupType `7`-wrapped equivalents. Cached strictly on
       `(fontHash, effectiveConfig)`.
  4. **Tier B fixed-point closure (`ccmp` & `locl`):**
     - Per-face paragraph universe `U` and per-word universe `W` are closed to fixed point over
       **all** output-producing lookups (`1`, non-empty `2`, `3`, `4`, context-free and contextual
       `8`, and nested `5`/`6` actions, including empty Type `2` deletion triggers).
     - If the fixed-point step budget is exhausted, **all words of the paragraph are refused**.
     - A word is character-safe iff every face covering it passes Tier A and no satisfiable
       multi-glyph or deletion rule (all backtrack, input, and lookahead positions satisfiable in
       `U`) has an input position intersecting `W`. A paragraph mixing a Tier-A-rejected face and
       a Tier-A-safe face still allows words set wholly in the safe face to certify (unlike a
       paragraph precondition failure, which refuses the entire paragraph).

### Condition 2 — Visual equivalence, subpixel bin alignment, and region coarsening

Each candidate piece is measured as a single-line `MultiParagraph` with **line-matched
`LineHeightStyle.Trim`** (`FirstLineTop` on line 0, `None` on interior lines, `LastLineBottom` on
the last line) and placed at `(wholeLayout.getHorizontalPosition(start, true), baseline - piece.firstBaseline)`.

Both the whole paragraph and the unclipped pieces are rasterized into offscreen surfaces sized to
`layoutBox + 2 × lineHeight` margin on all four sides, with **opacity normalization** (translucent
spans/text colors promoted to opaque deterministic contrast colors so low-alpha discrepancies
cannot hide under channel rounding). If offscreen pixel readback fails or returns `null`, Condition 2
**fails closed** (`admitted = false`, falling back to whole-paragraph rendering).

1. Every pixel in the compared region must agree within **`<= 2/255` per RGBA channel**.
2. The **outermost 1 px border ring** must contain **zero ink** (`alpha == 0`) in both renders.
3. **Subpixel bin-edge ULP guard (`PieceInkBounds`):** When `<= 32` pieces fail solely because a
   glyph already matching `wholeLayout` within `0.0025 px` sits on Skia's `k/4 + 0.125` (`0.25 px`)
   horizontal subpixel quantizer boundary (where SkParagraph `OneLineShaper`'s `float32` subtraction
   `x_paragraph - x_line_start` differs by 1 ULP from piece-local shaping), Condition 2 tests
   sub-thousandth-pixel nudges (`±0.0005`, `±0.0015 px`) locally and still validates the full
   paragraph bitmap under the strict `<= 2/255` gate. Real kerning, ligature, or shaping differences
   are never masked.
4. If either check fails, failing regions are coarsened (`Character -> Word -> Line`, up to **3
   passes**). If the partition still fails after 3 passes (or ink crosses the outermost 1 px ring),
   **the endpoint is refused**: it is never split, and transitions fall back cleanly to an unclipped
   whole-paragraph cross-fade.

### Stated supported domain and limits

- **Ink beyond `2 × lineHeight`:** Extreme shadows or baseline shifts projecting ink wholly beyond
  `2 × lineHeight` from the layout box are outside the supported split domain; the outermost 1 px
  border-ring guard detects overflow crossing the comparison margin and refuses splitting. Nothing is
  ever clipped at draw time.
- **Affine transforms:** Condition 2 runs untransformed. Rotated, scaled, and fractionally offset
  nodes draw through the same canvas transform; `MagicTextV2TextSafetyAndLayoutTest` measures
  agreement across affine transforms (`+0.35/+0.65 px`, `±12°`, `1.25×`, combined).
- **Rejection of v1 shims:** No per-token composables, no per-token layout nodes, and no guessed
  cluster boxes are permitted.

## Symmetric joint grouping, unclipped fades, and drawing

1. **Joint grouping (`JointGroupingPlanner`):**
   - **Morphs:** Because `from` and `to` endpoints coarsen independently, ownership is computed
     symmetrically over both admitted partitions via connected components of the `piece–match–piece`
     intersection graph (`Move`, `Exit`, `Enter`, `CrossFadeInPlace`). Each group's timing is the
     union `[minStart..maxEnd]` of its member planner slots with the first member's curve.
   - **Builds:** `BuildPlanV2` joins planner-generated `PieceScheduleV2` slots to admitted pieces by
     **UTF-16 range intersection** (never positional `"#$idx"` re-keying), taking `[minStart..maxEnd]`
     with the first intersecting slot's curve across both entering and exiting builds. The renderer
     never invents fallback stagger, `keyboard` jitter, or `@size` schedules.
2. **Unclipped fades & zero-allocation offscreen layers:**
   - Condition 2 measures each admitted piece's exact non-zero alpha bounding box once at admission
     time (`PieceInkBounds.measureNormalizedPieces`, expanded by a `4 px` AA/subpixel halo; blur
     effects additionally expand by `ceil(3.5 * sigma) + 4 px`). Additive cross-fade groups union
     their source and target verified ink bounds.
   - Native-paragraph fallback (`wholeParagraphCrossFade`) and any unverified piece fail closed to
     an **unbounded `saveLayer(null, paint)`** so overflowing shadows or overhanging ink are never
     clipped during fades.
   - All `saveLayer` paints set opacity via `paint.color = aByte shl 24` (`_nSetColor`, avoiding
     Skiko `Paint.setAlphaf`'s 64 B/call `Color4f` allocations), and `dissolve` builds whose
     admitted pieces have strictly pairwise-disjoint ink coalesce consecutive equal-alpha runs into
     one `saveLayer(uL, uT, uR, uB, paint)` (mathematically bit-for-bit identical while collapsing
     GPU render passes on Metal).
3. **Single-`DrawScope` drawing (`MagicTextV2Drawing` & `MagicTextV2Renderer`):**
   - **`Rest`:** One `drawText(wholeLayout)` — **100% bit-exact (`0/255`)** to `BasicText`.
   - **Color/weight cross-fade:** Isolated offscreen layer using Skia `BlendMode.PLUS`
     (`TextPainter.paint`, avoiding Compose's shared-`MultiParagraph` stale-color trap) with
     complementary integer alpha bytes (`fromByte = 255 - toByte`) so shared glyph ink suffers
     `0.00%` luminance dip.
   - **Size morphs:** Kept pieces changing size are laid out at `max(fromSp, toSp)`,
     anchored to integer-rounded ink-midpoint-calibrated `truePaintedBaselinePx(typeface, px)`
     (Direct Handover; no `<= 1.1x` blend window), and scaled down via a cached 3-level power-of-8
     aligned pre-rendered glyph pyramid (`1x / 0.5x / 0.25x`, `SamplingMode.MITCHELL`) so moving
     fractional baselines never suffer macOS `SkAxisAlignment::kX` integer-Y snapping or CoreGraphics
     per-frame re-hinting.
   - **Builds (`dissolve`, `fadeMove`, `keyboard`):** Drawn in the same single `DrawScope` via
     `LayerState`; `keyboard` uses planner-provided keystroke slots verbatim, draws a caret after the
     last visible piece, and backspaces right-to-left on exit.
   - **Container size (`@size`) & reduced motion:** Measured size interpolates strictly over the
     planner's `@size` slot during in-beat morphs (never cross-slide moves); reduced motion falls
     back to whole-paragraph cross-fade.
   - **Semantics:** Single merged semantics node exposing `text` and `getTextLayoutResult` for
     `StoryboardPdfExporter` and slide linting.
4. **Bounded LRU caches (`MagicTextV2Caches` + per-node `NodeHotCache`):** Keyed by `(text, style,
   width, density, fontScale, layoutDirection, localeList, fontResolver, characterDeliveryNode)` (+
   `granularity` and `stageSize` for admitted partitions; both endpoints + morph options for
   `MorphPlanV2`), with `===` reference fast paths in `NodeHotCache` eliminating per-frame cache-key
   allocations on animated sweeps.

Two architectural ideas are borrowed: isolating cross-fades in an offscreen layer follows the
open-source `torph-compose` library, and keeping per-frame rendering to cached draws without
shaping or per-token layout nodes avoids reflective access to paragraph internals.

## Representative fixtures and expected logical plans

Ranges below use half-open UTF-16 offsets. `↔` means retained correspondence; `+` and `−` mean
unmatched entrance and exit.

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
plan:   before[6,13) ↔ after[0,7)    // one semantic emoji token; single grapheme cluster
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

The logical plan says nothing about screen order; bidi runs and visual coordinates come exclusively
from `TextMeasurer`'s complete-paragraph layout.

### Line movement and style-only change

```text
before: "val answer = 42\nprintln(answer)"
after:  "println(answer)\nval answer = 42" (answer styled differently)
plan:   retained logical tokens map across the newline;
        both occurrences of "answer" use context to disambiguate;
        matching ranges whose AnnotatedString styles differ set styleChanged=true.
```

## Empirical verification record

Reference machine: **MacBook Pro (`MacBookPro18,2`, Apple M1 Max 10-core CPU / 32-core GPU)**,
macOS 27.2.1 (`26B131`, `aarch64`), Metal 4, JDK `25.0.4.1`, Skiko `0.156.1`, `Density(2f)`.

### Skiko `0.156.1` rendering invariants

| Area | Verified behavior / fix |
|---|---|
| Line trim & subpixel alignment | Line-matched `LineHeightStyle.Trim` (`FirstLineTop` / `None` / `LastLineBottom`) + `PieceInkBounds` subpixel bin-edge ULP guard yields `0/255` piece equivalence across multi-line paragraphs (including 300-word dense wrapped paragraphs). |
| OpenType character safety | `CharacterSafetyEngine` (configuration guard, allow-list preconditions, Skia `Typeface.getUTF32Glyph`, 5-level record precedence, `maxp.numGlyphs` domain & Class 0, Tier B `ccmp`/`locl` fixed-point closure) certifies `"awesome"`, `"function"`, and `"Shaders"` across all 9 bundled Latin families (`Inter`, `JetBrains Mono`, `Hanken Grotesk`, `Fredoka`, `Assistant`, `Plus Jakarta Sans`, `Montserrat`, `Roboto`, `Nanum Pen Script`) under unquoted `"liga 0, clig 0, dlig 0, calt 0"` with `0/255` equivalence. |
| Mipmap size morphing | Integer-rounded ink-midpoint-calibrated `truePaintedBaselinePx` + 3-level power-of-8 pre-rendered mipmap pyramid (`SamplingMode.MITCHELL`) achieves `<= 0.481 px` endpoint handovers and `<= 0.309 px` 60-sample max edge deviation across single-line and 4-line wrapped mixed-style `22.sp <-> 56.sp` morphs via Direct Handover. |
| Deferred draw allocation | Warm-cache single-`DrawScope` deferred `() -> TextContent` rendering achieves `recomps = 1`, `0 B/frame` managed heap allocation, zero cache/surface growth, and `p95 < 16.67 ms` across all 5 benchmark fixtures on both CPU and Metal on Metal-capable hosts. Hosts without Metal only exercise software CPU rasterization, held to `33.33 ms` locally and `100 ms` on CI. |
| Monochrome emoji routing | Explicit `SpanStyle` routing via `DeckFontResolver.assignParagraphFaces` to bundled `NotoEmoji` renders monochrome emoji with `0` colored Apple Color Emoji pixels while preserving `U+FE0F`/`U+200D`. |
| Color-cache & paint allocation | `TextPainter.paint(canvas, layout)` avoids Compose's shared-`MultiParagraph` color-cache trap; `Paint.color = aByte shl 24` avoids Skiko `Paint.setAlphaf`'s 64 B/call `Color4f` allocation. |

### 24-fixture corpus & character-safety matrix (`MagicTextV2TextSafetyAndLayoutTest`)

All 24 difficult fixtures executed in both directions with real mid-morph frames (`p = 0.35, 0.5, 0.7`)
and independent pixel-delta verification:

| # | Fixture | From (admitted / level / rounds / maxΔ) | To (admitted / level / rounds / maxΔ) | Groups |
|---:|---|---|---|---:|
| 1 | `seam-code-refactor` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 8 |
| 2 | `seam-bullet-reorder` | admitted / `Line` / 0 / `0/255` | admitted / `Line` / 0 / `0/255` | 3 |
| 3 | `seam-mixed-style-span` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 4 |
| 4 | `seam-prose-stopword-rewrite` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 15 |
| 5 | `proportional-ligature-char` (`HankenGrotesk`) | admitted / `Character` / 0 / `0/255` | admitted / `Character` / 0 / `0/255` | 22 |
| 6 | `coding-ligature-char` (`JetBrainsMono`) | admitted / `Character` / 0 / `0/255` | admitted / `Character` / 0 / `0/255` | 31 |
| 7 | `low-alpha-ligature` (`#20FFFFFF`) | admitted / `Character` / 0 / `0/255` | admitted / `Character` / 0 / `0/255` | 8 |
| 8 | `arabic-rtl` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 2 |
| 9 | `hebrew-rtl` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 4 |
| 10 | `devanagari-indic` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 4 |
| 11 | `thai-script` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 1 |
| 12 | `emoji-zwj-monochrome` (`NotoEmoji`, `0` color px) | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 8 |
| 13 | `combining-marks` (refuses paragraph -> `Word`) | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 3 |
| 14 | `italic-overhang` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 4 |
| 15 | `negative-tracking` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 3 |
| 16 | `baseline-shift` | admitted / `Line` / 1 / `0/255` | admitted / `Word` / 0 / `0/255` | 3 |
| 17 | `tight-line-height` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 10 |
| 18 | `continuous-underline-across-space` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 3 |
| 19 | `low-alpha-underline-and-run-bg` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 5 |
| 20 | `different-wrap-and-widths` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 10 |
| 21 | `shadow-overflow-within-margin` | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 3 |
| 22 | `overflow-beyond-2-lineheight-refused` | **refused** / `Paragraph` / `outerRingInk=true` | admitted / `Word` / 0 / `0/255` | 0 (whole-para) |
| 23 | `fallback-uncovered-codepoint-paragraph` (refuses all words -> `Word`) | admitted / `Word` / 0 / `0/255` | admitted / `Word` / 0 / `0/255` | 3 |
| 24 | `plain-emoji-char-eligible` (monochrome `NotoEmoji`, no joiners/VS) | admitted / `Character` / 0 / `0/255` | admitted / `Character` / 0 / `0/255` | 10 |

Additional character-safety and layout assertions verified via the **single production predicate** (`FontTableInspectorTest`,
`DeckFontResolverTest`, `MagicTextV2TextSafetyAndLayoutTest`):
- **Configuration & precedence:** `ShaperDefaults` refuses immediately; alternating `ShaperDefaults`
  vs `CharacterForcedOff` on the same `liga` font does not pollute Tier A cache; `LangSys` required
  features and `FeatureVariations` alternatives override `liga`, `ccmp`, and opt-in `ss01`.
- **GSUB coverage & Tier B closure:** LookupType `2` expansion and empty Type `2` deletion,
  paragraph-context Type `4` feeding `ccmp`, context-free Type `8` feeding `ccmp`, Formats 1/2/3,
  explicit-zero `ClassDef` entries and ranges, boundary glyph `numGlyphs - 1` vs out-of-domain
  `numGlyphs`, fixed-point step-budget exhaustion refusal, mixed Tier-A-safe + Tier-A-unsafe faces,
  disagreeing `cmap` subtables resolved via Skia `getUTF32Glyph`, `1⁄2` (`U+2044`) & `1∕2`
  (`U+2215`), Hangul/Jamo, combining marks, non-NFC, unpaired surrogates, variation selectors, and
  emoji ZWJ refusal.
- **All 9 bundled Latin families:** `"awesome"`, `"function"`, and `"Shaders"` certify character-safe
  across `Inter`, `JetBrains Mono`, `Hanken Grotesk`, `Fredoka`, `Assistant`, `Plus Jakarta Sans`,
  `Montserrat`, `Roboto`, and `Nanum Pen Script`.
- **Asymmetric coarsening, fail-closed C2, & unclipped fades:** `ab -> ba` and real `ccmp` `fi -> fx`
  coarsen symmetrically into 1 `CrossFadeInPlace` group; null offscreen readback fails closed to
  `admitted = false`; mid-fade overhanging shadows suffer zero clipping.

### Production Composable Path Benchmark (`1920×1080`, CPU & Metal, Warm Caches)

Measured through `MagicTextV2Renderer.Text(content = { ... })` (`CanvasLayersComposeScene`,
`Density(2f)`, 48 warmup + 90 measured frames + 20 live-Surface leak frames):

| Backend | Fixture | Recomps | p50 | p95 | p99 | max | Managed Alloc | Resource Cache | Font Cache | Live Surfaces |
|---|---|---:|---:|---:|---:|---:|---:|---|---|---|
| CPU | `300-move (layer-free)` | 1 | 1.503 ms | 1.682 ms | 2.508 ms | 2.508 ms | **0 B/f** | 0 → 0 B | 2,048,319 → 2,048,319 B | 0 → 0 |
| CPU | `50-fade (layered)` | 1 | 0.817 ms | 1.021 ms | 1.276 ms | 1.276 ms | **0 B/f** | 0 → 0 B | 1,630,015 → 1,630,015 B | 0 → 0 |
| CPU | `300-fade (layered)` | 1 | 2.380 ms | 2.571 ms | 2.659 ms | 2.659 ms | **0 B/f** | 0 → 0 B | 1,829,350 → 1,829,350 B | 0 → 0 |
| CPU | `300-morph + 32 additive` | 1 | 2.424 ms | 2.699 ms | 2.997 ms | 2.997 ms | **0 B/f** | 0 → 0 B | 1,135,195 → 1,135,195 B | 0 → 0 |
| CPU | `overflow-fallback` | 1 | 4.528 ms | 5.775 ms | 6.993 ms | 6.993 ms | **0 B/f** | 0 → 0 B | 1,575,455 → 1,575,455 B | 0 → 0 |
| Metal | `300-move (layer-free)` | 1 | 1.582 ms | 2.017 ms | 2.939 ms | 2.939 ms | **0 B/f** | 0 → 0 B | 1,789,961 → 1,789,961 B | 0 → 0 |
| Metal | `50-fade (layered)` | 1 | 0.526 ms | 0.851 ms | 1.033 ms | 1.033 ms | **0 B/f** | 0 → 0 B | 1,897,819 → 1,897,819 B | 0 → 0 |
| Metal | `300-fade (layered)` | 1 | 1.039 ms | 1.503 ms | 1.688 ms | 1.688 ms | **0 B/f** | 0 → 0 B | 1,571,633 → 1,571,633 B | 0 → 0 |
| Metal | `300-morph + 32 additive` | 1 | 12.101 ms | 13.069 ms | 13.425 ms | 13.425 ms | **0 B/f** | 0 → 0 B | 1,853,769 → 1,853,769 B | 0 → 0 |
| Metal | `overflow-fallback` | 1 | 0.807 ms | 1.034 ms | 1.349 ms | 1.349 ms | **0 B/f** | 0 → 0 B | 1,842,760 → 1,842,760 B | 0 → 0 |

All 10 runs stay well below the `16.67 ms` 60 Hz frame budget with `0` cache misses, `0` live
`Surface` growth, non-growing native resource/font caches, and preserved overflowing shadow ink on
the mid-fade native-paragraph fallback.

### Rendering, semantics, build, and size-morph results (`MagicTextV2EffectsAndRenderingTest`)

- **Pixel-exact rest & additive cross-fade:** `Rest` matches `BasicText` at **`0/255`** across 100% of pixels; `BlendMode.PLUS`
  color/weight cross-fade exhibits **`0.00%`** luminance dip on shared ink (`<= 2/255` max alpha dip).
- **Semantics & selectable PDF export:** Single merged semantics node per `TextNode` exposing `text` and `getTextLayoutResult`;
  real `StoryboardPdfExporter` + PDFBox `PDFTextStripper` verifies selectable text for both
  unrotated and `15°`-rotated nodes.
- **Build schedules:** Real `SegmentPlanner`-generated `PieceSchedule` slots for `dissolve`, `fadeMove`, and
  `keyboard` (Enter & Exit) verified for UTF-16 range-union joining (including coarsened `Word`
  pieces), deterministic seeded `keyboard` timing, caret rendering, reverse-backspace exit, exact
  completion at track end (`<= 2/255`), and reduced-motion whole-paragraph fallback.
- **Size-morph handover (`22.sp <-> 56.sp`, `Density(2f)`, 60 samples):**
  - `Inter` single-line: handover `0.292 px` fwd/rev, 60-sample max edge deviation `0.189 px`,
    `minPeakGrad = 0.878`, `symMaxΔ = 1/255`.
  - `HankenGrotesk` single-line: handover `0.481 px` fwd/rev, 60-sample max edge deviation
    `0.302 px`, `minPeakGrad = 0.851`, `symMaxΔ = 0/255`.
  - `Inter` wrapped mixed-style (4 lines): worst line handover `0.456 px`, 60-sample max edge
    deviation `0.274 px`, `minPeakGrad = 0.875`, `@size` widths `[420, 745, 1070]`.
  - `HankenGrotesk` wrapped mixed-style (4 lines): worst line handover `0.342 px`, 60-sample max
    edge deviation `0.309 px`, `minPeakGrad = 0.851`, `@size` widths `[420, 745, 1070]`.
