# Deck foundation

This repository starts from Storyboard and adds the talk-specific `:deck` module. The scene copy is
deliberately labelled **PROVISIONAL**: it proves the foundation and is not a proposed talk narrative
or a generic slide editor.

## Architecture

### Module DAG

`:deck` is organized as an acyclic multi-module Gradle DAG (enforced by `:ciacolaStaticCheck`, wired into `check`):

| Module | Role | Direct `:deck*` dependencies |
|---|---|---|
| `:deck-core` | `DeckTheme`, `DeckDesign`, `DesignSystemOverrides`, `StageElementContracts`, `ReducedMotion`, `SpeakerNotes`, review/timeline runtime contracts, `AgentBakeDispatcher`, Jewel theme/a11y helpers | — |
| `:deck-effects` | `DeckTextRenderer`, `AnimatedMeshBackdrop`, Keynote effects kit, `DeckWarpCodeCard`, Skia `trimMultiContourPath` | `:deck-core` |
| `:deck-presenter` | `PresenterWindow`, `PresenterController`, dashboard, top bar, stage curtains & quick jump | `:deck-core` |
| `:deck-review` | `SlideReviewWindow`, `ReviewStageCoordinator`, `ReviewCommentStore`, anchored popups & feed cards | `:deck-core` |
| `:deck-timeline` | `TimelineWindow`, `TimelineStudioController`, offscreen indexer, track canvas, loop sub-comp, inspector | `:deck-core` |
| `:deck-inspector` | Live Design System Inspector, Direct Element Editor, and Deck Linter | `:deck-core`, `:deck-effects` |
| `:deck` | Composition root (`Main.kt`, `FoundationDeck.kt`, `HotReloadStoryboard.kt`) and cross-seam / Spectre E2E tests | all `:deck-*` modules |

`createFoundationDeck()` is the composition root. Storyboard owns scene/state ordering, forward and
backward navigation, scene transitions, shared-transition scopes, and the fixed 1920×1080 render
space. `DeckDesign` owns the palette, type roles, spacing conventions, and `SceneFormat`. Five scenes
with fourteen base states form the executable spike (`Main.kt` passes
`includeKeynoteEffectsLab = true`; `createFoundationDeck()` alone builds the first four):

| Scene | States | Evidence |
|---|---:|---|
| `Beats` | 3 | deterministic state order and explanatory-prose MagicText |
| `SharedMove` | 2 | a Storyboard-provided `sharedBounds` Magic-Move-style transition |
| `AmbientLoop` | 1 | an infinite Compose animation that does not mutate scene state, plus the adapted mesh visual |
| `KotlinCode` | 3 | syntax-highlighted `AnnotatedString` values animated by MagicText |
| `KeynoteEffectsLab` | 5 | every Keynote build effect, `DeckWarpCodeCard` morphs, and the `MagnifierLoupeStage` |

Timeline `+ Beat` overrides add virtual beats on top of these base states.

`DeckTextRenderer` is the only deck-to-text-engine dependency. Its implementation is installed once
by the storyboard decorator. Scenes call `DeckMagicText`; they neither select nor know an engine.
The only supplied implementation is `MagicTextV1Renderer`.

Run the desktop deck (or launch with Compose Hot Reload on JetBrains Runtime 25) with:

```shell
./gradlew :deck:run
./gradlew :deck:hotRunDesktopAsync   # background launch; apply edits with `./gradlew :deck:reload`
CIACOLA_THEME=light ./gradlew :deck:hotRunDesktopAsync
CIACOLA_REDUCE_MOTION=true ./gradlew :deck:hotRunDesktopAsync
CIACOLA_TIMELINE_OPEN=true ./gradlew :deck:hotRunDesktopAsync   # opens with the timeline docked
./gradlew :deck:spectreTest          # real-window Spectre 0.9.0 E2E suites on JBR 25 (focus-free)
```

The first launch opens a 16:9 1280×720 Story window. Secondary windows (Timeline, Review,
Presenter, Theme) only write `.storyboard/<window>.json` once they have been opened, and minimized
window state is normalized on save/load so relaunching never starts hidden in the Dock.

### Shortcuts

| Key | Action |
|---|---|
| `→` `↓` `PgDn` `Space` `Enter` | Next beat |
| `←` `↑` `PgUp` `Backspace` | Previous beat |
| `B` / `W` | Toggle blackout / whiteout curtain in Show Mode (`Esc`, `B`/`W`, click, or advancing clears it) |
| `⌘G` | Jump to slide (`1..N`) |
| `Esc` | Dismiss active popup/curtain, exit sub-comp / docked Studio, or toggle the Overview grid (**Easel ▸ Overview (Esc)**) |
| `⌥⌘P` | Toggle fullscreen |
| `F2` | Presenter window (`T` start/pause timer, `R` reset, `⌘-` / `⌘=` zoom speaker notes) |
| `F3` / hold `Ctrl` | Review Inspect mode; `Ctrl+Click` / `Ctrl+Drag` captures a comment |
| `F4` | Review window |
| `F5` | Timeline Studio docked in the Story window |
| `F6` | Timeline Studio in a detached window |

## Theme system, Keynote effects kit, timeline studio, and hot reload

- **Runtime baseline:** `:deck` targets JetBrains Runtime 25 (`JVM_25`) on Compose Multiplatform
  `1.13.0-beta02` and Jewel Standalone `0.42.0-263.7307.200`.
- **`DeckTheme` (`dev.sebastiano.ciacola.theme`):** `LocalDeckTheme` supplies `@Immutable` tokens for
  `DeckColors` (including a single `DeckCodePalette` shared by `MagicText` `CodeStyle` and
  `DeckWarpCodeCard` TextMate scopes), `DeckTypography` (bundled `Inter` and `JetBrains Mono` cached
  once via `rememberBundledDeckTheme` so `FontFamily` and `TextStyle` references never churn across
  recomposition), `DeckStyles` (code cards, dashed callouts, leader arrows, strikeouts, stamps,
  detour badges, and loupe geometry), and `DeckMotion` (300–380 ms Keynote curves, 55 ms stagger
  step, and spring pop parameters). Two presets ship out of the box (`StageDark` and `KeynoteLight`,
  toggleable live from the `Theme` menu), alongside **Reduced Motion** (`System` macOS `reduceMotion`
  detection, `Reduced`, `Full`, and `Pause Ambient Loops`, exposed via `LocalReducedMotion` and
  `LocalAmbientLoopsPaused`).
- **Presenter View & Live Stage Aids (`dev.sebastiano.ciacola.presenter`, Jewel standalone):**
  `F2` opens `PresenterWindow` with per-slide and per-beat `DeckSpeakerNotes` (`⌘-` / `⌘=` zoom),
  live 16:9 Current and clickable Next previews (`SceneMode.Preview` so Story retains ownership of
  `renderFrameClock` and `freezeCapture`), wall clock + elapsed talk timer (`T` / `R`) + target
  duration pace pill, AWT `GraphicsDevice` display routing (`Present on…` / `Swap Displays`),
  pre-show authoring hazard check (`Enter Clean Show Mode`), Show Mode `B` / `W` curtains, and
  `⌘G` Quick Slide Jump.
- **Selectable PDF & Deterministic MP4 Export (`dev.bnorm.storyboard.easel.export`):**
  `ExportCoordinator` serializes exports with a cancellable `ExportProgressPopup` (`Esc` / `Cancel`).
  `StoryboardPdfExporter` pairs full-fidelity slide rasters with an invisible `RenderingMode.NEITHER`
  Unicode text layer extracted from Compose semantics in reading order; `StoryboardVideoExporter`
  pre-flights `ffmpeg`, steps a reused offscreen `ImageComposeScene` deterministically at 60 fps
  (`400 ms` beat holds + animated transitions), and streams RGBA frames off-EDT to `libx264` MP4.
- **Deterministic state switching (`DeterministicStateHost`):** `Easel`, `sharedMoveScene`, and
  `DeckWarpCodeCard` mount `setOf(currentState, targetState)` through
  `Transition.DeterministicStateHost` (`key(contentKey) { AnimatedVisibility(...) }`) instead of
  `AnimatedContent`, eliminating internal `currentlyVisible` queue drift during random-access
  `Animatic.seekToSegment` scrubbing while preserving `SharedTransitionScope` / `AnimatedVisibilityScope`
  contracts.
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
- **After Effects Timeline Studio (`dev.sebastiano.ciacola.timeline`, Jewel standalone):**
  - Toggleable docked in the Story window (`F5` or top-right `Timeline` HUD button) or detached as
    a separate window (`F6`).
  - Offscreen `indexDeckTimeline` harvests every slide's `Build`, `StaggerGroup` + staircase
    `StaggerItem` rows, `MagicText` diffs, `WarpCode` morphs, `LineDraw` strokes, cross-slide
    `deckSharedBounds` span bars, and `rememberDeckLoopMillis` Loop Sub-Compositions onto a single
    contiguous timeline with diagonal-hatched cross-slide transition overlap zones and full-height
    vertical dotted Event Keyframe lines.
  - Zero-latency hybrid overrides (`.storyboard/timeline-overrides.json`): `+ Beat` / `- Beat`
    virtualizes event keyframes in `0 ms` without waiting for an agent turn; dragging block edges or
    bodies updates `delayMs`, `durationMs`, and `enterBeat`; the right-hand Inspector provides
    numeric steppers, an interactive 2D Cubic-Bezier curve editor + Spring presets, spatial slide
    offsets, live quadratic-Bezier `arcBend` trajectories rendered on stage via
    `MotionArcStageOverlay`, Loop Sub-Composition harmonic wave scrubbing (`Sub-Comp ▸` /
    `← Master Deck`), and optional `Bake to Kotlin` agent dispatch.
  - **One playback engine.** `Animatic.advance` (arrow keys), timeline `Play`, and scrubbing all
    drive the same `SeekableTransitionState` by seeking: a segment is first composed at play time
    0 for a few settle frames (Compose delays any transition child created at a non-zero play time
    by that play time, so nested content would otherwise lag), then its fraction is advanced frame
    by frame. Presenting therefore renders exactly the frames a scrub renders. A newer navigation
    supersedes an in-flight one, so rapid arrow presses skip ahead instead of queueing. Frames are
    counted on the clock of the window that renders the animatic (`Easel` registers it), because
    callers such as the timeline controller live in the application composition, whose clock ticks
    independently. While a segment settles, `Easel` presents a pixel snapshot of the last frame
    (a recorded layer would still show its children's live, half-set-up content).
  - **Effective durations.** A segment lasts as long as something on stage actually changes: the
    longest running animation whose start and end values differ. Plain `Transition` totals also
    count the full `tween` of every unchanged animation, which used to leave up to 900 ms of dead
    time after a click (for example before a slide transition). Animations whose keyframes return
    to their start value are treated as static.
  - **Path-independent scrubbing.** Every scrub step bumps `Animatic.scrubEpoch`, which re-keys the
    scene subtree so no transition, shared-bounds anchor, or text plan leaks from the scrub path.
    The fresh subtree settles on the segment's `from` state, then seeks. Resting exactly on a beat
    line shows the settled beat. `ScrubPathIndependenceTest` checks forward, backward, jittering,
    and unawaited bursts against a fresh composition, compares every scrubbed frame and segment
    length against live arrow-key playback, and checks that rapid presses skip ahead.
  - **Measured timing.** The timeline is laid out from the effective durations measured by playing
    the whole deck with the real engine in an offscreen `ImageComposeScene` (resting on each beat
    like a presenter), plus a 400 ms click hold per resting beat; time `t` on the ruler is the frame
    playback shows at `t`. Offscreen scenes set `LocalTimelineOffscreen`, so live studio state
    (selection, loop clock) never leaks into them.
  - **After Effects navigation.** Timeline: wheel scrolls rows, `Shift`+wheel scrolls time,
    `Alt`/`Ctrl`+wheel zooms at the cursor, `=`/`-` zoom around the playhead, `;` toggles fit and
    frame level, `Space`+drag or middle-drag pans (a `Space` tap toggles playback), scrollbars on
    both axes (`Modifier.scrollable2D`). Preview (while the timeline is open): wheel zooms at the
    cursor, `Space`/middle-drag pans, `.`/`,` zoom, `/` fits. Ctrl-click review keeps working on a
    zoomed preview.
  - **Selection both ways.** Elements register with `Modifier.timelineTarget(trackId…)`. Selecting a
    timeline row or block outlines the element(s) it animates on the slide; with the timeline open,
    a plain click on a slide element selects its most specific track and scrolls its row into view
    (disabled while review Inspect mode or a pending review selection is active).
- **Hot-reload state retention:** `Main.kt` drives the desktop app through
  `rememberHotReloadStoryboard` (`AfterHotReloadEffect`, with Compose Hot Reload instrumenting
  window UI compositions automatically), while `rememberAnimatic` and `AnimaticPersistenceEffect`
  retain and scene-clamp `(sceneIndex, frameIndex)` across `Storyboard` rebuilds without
  overwriting `.storyboard/animatic.json`.
- **Interactive Ctrl-click slide review (`dev.sebastiano.ciacola.review`, Jewel standalone):**
  Holding `Ctrl` (or toggling `F3` Inspect Mode) highlights slide targets in `1920×1080` stage
  space; `Ctrl+Click` or `Ctrl+Drag` captures a clean annotated `GraphicsLayer` PNG
  (`.storyboard/review-captures/`), opens an anchored Jewel `PopupContainer` (`IntUiTheme` +
  `TextArea`), persists `.storyboard/review-comments.json`, and dispatches feedback via
  `agentapi send-message` so edits hot-reload in place via `:deck:reload`. Each comment is shown
  as a numbered stage pin; its status (queued, applying, applied, failed) is tracked in the Review
  window.

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
- plans are pure (`MagicTextPlanner.plan(from, to)`): the source keeps canonical keys and
  `findShared` no longer mutates the source plan, so a scrub that arrives at a segment from any
  direction gets the exact same shared-bounds plan and keys. `MagicTextInternal` mounts only the
  `from`/`to` token sets (`SeekSafeTokenHost`) instead of an internal `AnimatedContent`, whose queue
  drifts under seeking;
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
- `:deck:desktopTest`: passed (adds `ScrubPathIndependenceTest`, `TimelineViewportAndSelectionTest`,
  and `TimelineOffscreenIsolationTest` to `FoundationDeckTest`, `DeckThemeTest`, `KeynoteEffectsTest`,
  `SlideReviewSystemTest`, `TimelineModelAndOverridesTest`, and `DeterministicEaselScrubAndLoopTest`).
- `:deck:spectreTest`: runs every `*SpectreTest` class (`CiacolaStudioSpectreTest` with six
  real-window Spectre `0.9.0` tests, plus the `qa/` suites). `CiacolaStudioSpectreTest` covers mouse
  scrubbing across a beat and back, compared pixel-for-pixel with a direct scrub; After Effects
  zoom/pan/scroll gestures in the timeline and the preview, including Ctrl-click review on a zoomed
  preview; selection in both directions with compact Inspector steppers; beats, loop
  sub-composition, and the review composer with the timeline open. The task runs on JBR 25 with
  `apple.awt.UIElement=true` and synthetic AWT input, so it never steals focus.
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
