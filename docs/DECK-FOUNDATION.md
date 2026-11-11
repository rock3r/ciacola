# Deck foundation

This repository starts from Storyboard and adds the talk-specific `:deck` module. The scene copy is
deliberately labelled **PROVISIONAL**: it proves the foundation and is not a proposed talk narrative
or a generic slide editor.

## Architecture

`createFoundationDeck()` is the composition root. Storyboard owns scene/state ordering, forward and
backward navigation, scene transitions, shared-transition scopes, and the fixed 1920×1080 render
space. `DeckDesign` owns the palette, type roles, spacing conventions, and `SceneFormat`. Four scenes
form the executable spike:

| Scene | States | Evidence |
|---|---:|---|
| `Beats` | 3 | deterministic state order and explanatory-prose MagicText |
| `SharedMove` | 2 | a Storyboard-provided `sharedBounds` Magic-Move-style transition |
| `AmbientLoop` | 1 | an infinite Compose animation that does not mutate scene state, plus the adapted mesh visual |
| `KotlinCode` | 3 | syntax-highlighted `AnnotatedString` values animated by MagicText |

`DeckTextRenderer` is the only deck-to-text-engine dependency. Its implementation is installed once
by the storyboard decorator. Scenes call `DeckMagicText`; they neither select nor know an engine.
The only supplied implementation is `MagicTextV1Renderer`.

Run the desktop deck (or launch with Compose Hot Reload on JetBrains Runtime 25) with:

```shell
./gradlew :deck:run
./gradlew :deck:hotRunDesktop        # explicit reload via `./gradlew :deck:reload`
CIACOLA_THEME=light ./gradlew :deck:hotRunDesktop --auto
```

## Theme system, Keynote effects kit, and hot reload

- **`DeckTheme` (`dev.sebastiano.ciacola.theme`):** `LocalDeckTheme` supplies `@Immutable` tokens for
  `DeckColors` (including a single `DeckCodePalette` shared by `MagicText` `CodeStyle` and
  `DeckWarpCodeCard` TextMate scopes), `DeckTypography` (bundled `Inter` and `JetBrains Mono` cached
  once via `rememberBundledDeckTheme` so `FontFamily` and `TextStyle` references never churn across
  recomposition), `DeckStyles` (code cards, dashed callouts, leader arrows, strikeouts, stamps,
  detour badges, and loupe geometry), and `DeckMotion` (300–380 ms Keynote curves, 55 ms stagger
  step, and spring pop parameters). Two presets ship out of the box (`StageDark` and `KeynoteLight`,
  toggleable live from the `Theme` menu).
- **Reusable Keynote effects (`dev.sebastiano.ciacola.effects`):**
  - Zero-relayout `graphicsLayer` / clip build modifiers (`keynoteDissolve`, `keynoteFadeMove`,
    `keynoteFromDarkness`, `keynotePop`, `keynoteWipe`, `keynoteSpotlight`) driven by
    `Transition.animateBuildProgress` and direction-symmetric `Transition.animateStaggerGroup`
    (forward cascade `0..N-1`, backward reverse cascade `N-1..0`, with layout space reserved so
    centered stacks never jump).
  - Multi-contour Skia `PathMeasure` `LineDraw` decorations on `DecorationLayout`: `AnimatedLine`
    and `AnimatedCubicLine` (with tangent `ArrowHead`), `AnimatedCalloutOutline` (dashed rounded
    focus boxes), and `AnimatedStrikeout`.
  - Stage components: `DottedLeaderRow`, `KeynoteStamp`, `DetourSign`, `DeckWarpCodeCard`, and
    `MagnifierLoupeStage` (live `GraphicsLayer` zoomed iris loupe with animated dotted connectors).
- **Hot-reload state retention:** `Main.kt` drives the desktop app through
  `rememberHotReloadStoryboard` (`AfterHotReloadEffect`, with Compose Hot Reload instrumenting
  window UI compositions automatically), while `rememberAnimatic` and `AnimaticPersistenceEffect`
  retain and scene-clamp `(sceneIndex, frameIndex)` across `Storyboard` rebuilds without
  overwriting `.storyboard/animatic.json`.

## Source orientation and attribution

The foundation was prepared against these exact revisions:

- Storyboard, `499cda890a7591c8fc56044726531f6f38a82e35` (the implementation checkout later retained
  full upstream history). Its `Storyboard.build`, `StoryboardBuilder.scene`, `SceneScope.transition`,
  `SceneFormat`, `DesktopEasel`, `rememberSharedContentState`, and `Modifier.sharedBounds`
  declarations are used directly. The source locations are respectively
  `storyboard/.../Storyboard.kt`, `StoryboardBuilder.kt`, `SceneScope.kt`, `SceneFormat.kt`,
  `storyboard-easel/.../DesktopEasel.kt`, and `storyboard-easel/.../interop.kt`.
- Storyboard Text at the same revision. `MagicText.kt`, `SharedText.kt`, and `Words.kt` are the
  upstream declarations characterized and hardened here; `highlight/Highlighting.kt`,
  `highlight/Kotlin.kt`, and `highlight/CodeStyle.kt` supply Kotlin syntax highlighting.
- Brian Norman's Magic Move deck, branch `dcnyc25`, revision
  `f153f7cb0f4142c3d11bf282e1589a94b04eb523`. The orientation references were
  `dcnyc25/story/.../samples/MagicText.kt` (`SharedTransitionLayout`, `AnimatedContent`,
  `sharedElement`/`sharedBounds`) and `samples/MagicDiff.kt` (match/add/delete separation). No deck
  source was copied.
- ChromaDecks, revision `e1758512bf0591532bbfab74f4f276455120b8e6`. The adapted technique is
  the normalized 2D colored grid rendered as indexed `Vertices(VertexMode.Triangles)` via
  `Canvas.drawVertices`, studied in
  `composeApp/src/desktopMain/kotlin/com/sinasamaki/chromadecks/_001_MeshGradients/MeshGradient.kt`
  and its animated point orchestration in `_001_MeshGradients/slides/TitleSlide.kt`.

Storyboard is Apache-2.0 and its `LICENSE.txt` remains at repository root. Neither ChromaDecks nor
the referenced Magic Move deck exposed a license file at the studied revisions. Consequently their
source and assets were not copied: `AnimatedMeshBackdrop.kt` is a small original implementation of
the general indexed-vertex-grid technique, with different data structures, interpolation, colors,
and animation. This document preserves source credit without asserting a redistribution right.

## MagicText hardening

The tests characterize default and supplied tokenization; insertion, deletion, and movement;
style-only cross-fades; repeats; explicit line breaks; transition direction and interruption;
empty/replaced text; a 2,000-token mostly unchanged input; annotation spans; combining marks,
emoji sequences, mixed LTR/RTL text, and non-Latin scripts.

Changes made from those tests:

- default tokenization no longer divides surrogate pairs or common extended emoji sequences and
  keeps combining marks with their base word;
- unchanged whitespace receives stable correspondence keys instead of becoming a new shared bound
  every time;
- transition settlement is now a `SideEffect`, not a state mutation hidden inside `remember`;
- interrupted targets are cached until a state settles, so returning to an in-flight target reuses
  the exact shared-bounds plan and keys;
- the old large-run TODO is resolved as an explicit v1 invariant: adjacent items cannot be merged
  without changing their independently moving geometry. Paragraph batching belongs after the v2
  layout boundary, not inside v1.

The remaining TODOs in `SharedText.kt` concern performance strategy inside repeated-token matching:
priority-queue selection, avoiding repeated grouping, and limiting/ordering the small Cartesian
search. They are deliberately retained because current deterministic behavior is covered, the large
mostly-unchanged fixture passes, and changing the heuristic without benchmark evidence could alter
repeat disambiguation. They are optimization work, not known correctness defects.

See [MAGIC-TEXT-V2-SEAM.md](MAGIC-TEXT-V2-SEAM.md) for the future boundary and fixtures.

## Verification record

- `:storyboard-text:jvmTest --tests '*MagicText*Test'`: passed (12 focused tests).
- `:storyboard-easel:jvmTest --tests '*AnimaticHotReloadPersistenceTest'`: passed (hot-reload slide
  and build retention + clamping without clobbering disk state).
- `:deck:desktopTest`: passed (11 tests across `FoundationDeckTest`, `DeckThemeTest`, and
  `KeynoteEffectsTest`, including pixel-verified `LineDraw` decorations and 5-build lab navigation).
- `check --no-parallel -Pkotlin.daemon.jvmargs=-Xmx4g -x wasmJsBrowserTest`: passed, including
  all JVM suites, Wasm compilation, and API checks. The unmodified upstream browser task cannot run
  in this image because it requests ChromeHeadless and only Firefox is installed; a literal `check`
  reached that launcher and failed solely with “No binary for ChromeHeadless”.
- Desktop visual smoke under Xvfb: passed with 35 960×540 captures in
  `deck/build/visual-smoke`. They cover every settled state and every forward/backward transition.
  Forward and backward shared-move transition hashes differ from their settled frames. `loop-a.png`
  and `loop-b.png`, captured one second apart at the same scene index, also have different hashes.
- Source audit: no MagicText v2 implementation, custom text-shaping engine, or per-scene text-engine
  toggle exists (`DeckTextRenderer` remains the sole prose-engine seam). Canvas drawing in `:deck`
  covers the vertex-mesh backdrop and the `DecorationLayout` / `GraphicsLayer` Keynote presentation
  effects (`LineDraw`, `DottedLeaderRow`, and `MagnifierLoupeStage`).
