# Deck foundation

This repository starts from Storyboard and adds the document-driven `:deck` presentation studio and
runtime. Foundation scene copy is deliberately labelled **PROVISIONAL**: it exercises every core
primitive alongside the three full Keynote talk replications.

## Architecture

### Module DAG

`:deck` is organized as an acyclic 7-module Gradle DAG (enforced by `:ciacolaStaticCheck`, wired into `check`):

| Module | Role | Direct `:deck*` dependencies |
|---|---|---|
| `:deck-core` | `DeckDocument` schema & validation, `CanonicalJson`, `DeckDocumentStore` (explicit `EditTransaction` + unified undo/redo), crash-safe `DeckFileSync` (atomic file exchange + journal + quarantine), `StudioSessionStore`, pure analytical `SegmentPlanner` & `CrossSlideGeometry`, `DeckClock` & `RenderSession`, `DeckSpecimenRegistry`, `FontTableInspector` & `DeckFontResolver`, `DeckTheme`, `StageInspectionRegistry`, `SpeakerNotes`, review runtime contracts, Jewel theme/button helpers | — |
| `:deck-effects` | `DocumentStoryboardHost`, `DocumentNodeRenderer`, `DocumentMotionEvaluator`, `MagicText v2` (`dev.sebastiano.ciacola.text.v2`) bound behind `DeckTextRenderer`, 3-tier `BuildEffects` (`Layer`, `Path`, `Content` SkSL) + 15 Apple `KeynoteCurves` + all 9 slide transitions (`SlideTransitions.kt`), `WarpCodeAdapter` & `DeckWarpCodeCard`, `DeckSkslShader`, `ElementConnector`, `AnimatedMeshBackdrop`, `MagnifierLoupeStage`, Skia `trimMultiContourPath` | `:deck-core` |
| `:deck-presenter` | `PresenterWindow`, `PresenterController`, dashboard, top bar, stage curtains & quick jump | `:deck-core` |
| `:deck-review` | `SlideReviewWindow`, `ReviewStageCoordinator`, `ReviewCommentStore`, anchored popups & feed cards | `:deck-core` |
| `:deck-timeline` | `DocumentTimelineWindow`, `DocumentTimelineStudioPanel`, `DocumentTimelineController`, `DocumentTimelinePlannerCache`, `scrubStepPolicy`, virtualized analytical track rows, stagger & fragment phase sculpting | `:deck-core` |
| `:deck-inspector` | `DocumentElementInspectorUi`, `DocumentStageDirectEditOverlay` (drag/snap + inline text range provenance), `DocumentThemeInspectorUi`, `DocumentSyncBannerUi` (conflict/recovery/close-hold), and time-sliced `DocumentDeckLintController` (`F8`) | `:deck-core`, `:deck-effects` |
| `:deck` | Composition root (`Main.kt`, `DeckCatalog.kt`, `AllDocumentDecks`, `DeckSource`, `StudioWorkspace.desktop.kt`), 39 live `@Composable` / SkSL specimens across 4 tracked `<deckId>.deck.json` decks, `:deck:formatDecks` & `:deck:verifyDecksCanonical`, and Spectre E2E suites | all `:deck-*` modules |

### Document-first model (`<deckId>.deck.json` + `deckSpecimens`)

Every deck is authored as a canonical tracked `<deckId>.deck.json` file paired with a static
`DeckSpecimenRegistry` (`deckSpecimens { ... }`) for live `@Composable` and SkSL specimens. There are
no sidecar override JSON files, no bake/snippet generators, and no Kotlin slide DSL:
`<deckId>.deck.json` is the single source of truth for slides, beats, speaker notes, themes,
palettes, fonts, node trees, builds, transitions, and loop periods. `.storyboard/` holds only local
ephemeral window/session preferences (`session.json` via `StudioSessionStore`) and slide review
comments (`deck-review`).

Four canonical document decks ship in `DeckCatalog` (`264` slides, `516` beats, `39` live specimens):

| Deck ID | Slides | Beats | Specimens | Summary |
|---|---:|---:|---:|---|
| `foundation` | 5 | 14 | 1 | Executable spike: `Beats` (`MagicText v2` word morphs), `SharedMove` (cross-slide `magicMove`), `AmbientLoop` (`foundation.ambientMesh`), `Highlighted Code` (`WarpCodeAdapter`), and `KeynoteEffectsLab` (builds, `lineDraw`, `loupe`) |
| `shaders-quickie-2025` | 45 | 86 | 19 | Full replication of *Shaders Quickie* with live interactive SkSL teaching shaders, step graphs, UV visualizers, and `zoom` fly-past-camera transitions |
| `jewel-talk-2024` | 73 | 122 | 6 | Full replication of *Jewel Talk* with live interactive Jewel Swing vs Compose theme showcases, Swing Bridge demos, and custom component specimens |
| `not-just-plain-text-2024` | 141 | 294 | 13 | Full replication of *Not Just Plain Text* with `MagicText v2` character/word/line morphs, `keyboard` builds, `trace`/`sparkle`/`shimmer` SkSL content effects, and live rich-text/Markdown specimens |

### Source resolution, file sync, and isolated `RenderSession`s

- **`DeckSource.File` vs `DeckSource.Resource`:** When `-Dciacola.decks.dir` points to a writable
  directory containing `<deckId>.deck.json` (`:deck:run` configures
  `-Dciacola.decks.dir=${project.file("src/desktopMain/resources/decks").absolutePath}` by default),
  `Main.kt` opens the file via `DeckFileSync` (advisory lock, native atomic file exchange
  `renamex_np(RENAME_SWAP)` / `renameat2(RENAME_EXCHANGE)`, write-ahead journal, 10-minute dual-clock
  quarantine retention, external-edit hot reload, conflict banner, and close-hold guard). Otherwise it
  opens `DeckSource.Resource` from the classpath in read-only mode.
- **Canonical formatting gates:** `:deck:formatDecks` formats tracked `*.deck.json` files through
  `CanonicalJson.print` (80-column inline packing, 2-space indent, trailing LF); `:deck:verifyDecksCanonical`
  (wired into `:deck:check`) verifies byte-for-byte canonical formatting and zero validation errors.
- **Six isolated `RenderSession`s:** `Main.kt` mounts independent `RenderSession` instances so clocks,
  retained specimen state, and `sceneZIndex` never cross-contaminate:
  1. **Live Stage** (`RenderSession.forPresent` — driven by `freeClockMs` in Show Mode or
     `timelineController.playheadMs` while Studio is open)
  2. **Presenter Current & Next Previews** (`RenderSession.forStill`)
  3. **Overview Grid (`Esc`)** (`RenderSession.forStill`)
  4. **Deck Linter (`F8`)** (dedicated offscreen `RenderSession.forStill` inside `DocumentDeckLintEngine`)
  5. **PDF Export** (fresh `RenderSession.forStill` per export)
  6. **Video Export** (fresh `RenderSession.forVideo(fps = 60)` per export, wired with
     `holdDurationForState` for `autoAdvanceMs` holds)

Run the desktop deck or verification gates on JetBrains Runtime 25 with:

```shell
./gradlew :deck:run
./gradlew :deck:run -Pciacola.deck=shaders-quickie-2025
./gradlew :deck:formatDecks
./gradlew :deck:verifyDecksCanonical
./gradlew :deck:spectreTest --offline -Dapple.awt.UIElement=true
```

### Shortcuts

| Key | Action |
|---|---|
| `→` `↓` `PgDn` `Space` `Enter` | Next beat (respecting `"interactive": "keyboard"` specimen capture) |
| `←` `↑` `PgUp` `Backspace` | Previous beat |
| `B` / `W` | Toggle blackout / whiteout curtain in Show Mode (`Esc`, `B`/`W`, click, or advancing clears it) |
| `⌘G` | Jump to slide (`1..N`) |
| `⌘⌥1`..`⌘⌥4` | Switch active catalog deck (`foundation`, `shaders-quickie-2025`, `jewel-talk-2024`, `not-just-plain-text-2024`) |
| `Esc` | Exit interactive specimen capture, cancel active gesture transaction, dismiss popup/curtain, close docked Studio, or toggle Overview |
| `⌥⌘P` | Toggle fullscreen |
| `F2` | Presenter window (`T` start/pause timer, `R` reset, `⌘-` / `⌘=` zoom speaker notes) |
| `F3` / hold `Ctrl` | Review Inspect mode; `Ctrl+Click` / `Ctrl+Drag` captures a comment |
| `F4` | Review window |
| `F5` | Studio (`Motion \| Element \| Theme \| Lint`) docked in the Story window |
| `F6` | Studio in a detached window |
| `F7` | Focus `Theme` tab in Studio (or toggle Theme sidecar window) |
| `F8` | Focus `Lint` tab in Studio (or toggle Deck Linter sidecar window) |
| `⌘Z` / `⇧⌘Z` | Unified Undo / Redo on `DeckDocumentStore` across Motion, Element, and Theme (yields to focused text inputs) |

## Rendering, MagicText v2, motion planning, and Studio authoring

- **Pure analytical `SegmentPlanner` & `DeckClock` (`:deck-core`):** Every intra-slide beat and
  cross-slide transition is planned purely from `DeckDocument` + `DeckSpecimenRegistry` without
  offscreen frame-stepping (`DocumentTimelinePlannerCache` invalidates only the edited slide and its
  adjacent transitions, planning 300-slide decks in single-digit milliseconds). `rememberDocIntraCarrier`
  and `rememberDocCrossCarrier` drive exact `segmentMs` durations across forward play, backward
  mirroring, mid-flight reversals, and random-access scrubbing.
- **`MagicText v2` (`dev.sebastiano.ciacola.text.v2` behind `DeckTextRenderer`):** Single-`DrawScope`
  text engine with zero per-token composables or layout nodes, backed by pure `MagicTextDiff` in
  `:storyboard-text`. Enforces Two-Condition Admission (load-time OpenType `cmap`/`GSUB`/`fvar`
  inspection + opacity-normalized `<= 2/255` pixel equivalence with 1 px outer-ring ink guard and
  3-round region coarsening), Skia `BlendMode.PLUS` additive cross-fades, `TrueAscent` Direct
  Handover size morphs over a power-of-8 3-level mipmap pyramid, seeded `keyboard` typewriter builds,
  monochrome `NotoEmoji` `SpanStyle` routing, and a single merged semantics node (`text` +
  `getTextLayoutResult`) for selectable PDF export and linting. See
  [MAGIC-TEXT-V2-SEAM.md](MAGIC-TEXT-V2-SEAM.md) for the full design and empirical measurements.
- **3-tier `BuildEffects` & 9 Slide Transitions (`dev.sebastiano.ciacola.effects.v2`):** Supports 52
  Keynote build effects across `Layer` (`appear`, `dissolve`, `fadeMove`, `moveIn`, `wipe`, `iris`,
  `zoom`, `pop`, `blur`, `fromDarkness`, `jiggle`), `Path` (`lineDraw` multi-contour trimming with
  tangent arrowheads), and `Content` (SkSL `trace`, `sparkle`, `shimmer`), 15 Apple `KeynoteCurves`
  presets, and 9 slide transitions (`none`, `dissolve`, `push`, `slide`, `reveal`, `wipe`, `iris`,
  `fadeThroughColor`, and cross-slide `magicMove` rendered in the Root Transition Overlay inside
  `Easel`'s fixed-stage and freeze boundary, including single-instance `movableContentOf` specimen
  continuity).
- **Unified Studio (`Motion | Element | Theme | Lint`):**
  - **Motion (`DocumentTimelineStudioPanel`):** Master time ruler scrubbing, slide minimap, beat &
    transition stagger/phase sculpting, intra-text fragment ticks, draggable/resizable track bars,
    `+ Beat` / `- Beat`, and loop period sliders — all transactional on `DeckDocumentStore`.
  - **Element (`DocumentElementInspectorUi` + `DocumentStageDirectEditOverlay`):** Stage picking
    (yielding cleanly to interactive specimens while supporting `Alt+Click` container selection),
    placement-only drag preview snapping to `0.5` stage units on release, beat-aware RFC 7386 merge
    patches (`Beat 0` base vs `Beat k > 0` `change` builds), inline text editing with UTF-16
    edit-range fragment provenance (`FragmentProvenance`), and declared specimen parameter controls.
  - **Theme (`DocumentThemeInspectorUi`):** Live theme color tokens, typography role editors, motion
    defaults, slide palettes, and cross-deck theme copying.
  - **Lint (`DocumentDeckLintController`):** Time-sliced cancellable single-slide and full-deck
    audits checking schema/asset/font issues, text clipping/overflow, out-of-bounds/safe-area bleed,
    WCAG contrast, tiny stage text, and overlapping text across dark and light themes.

## Source orientation and attribution

- Storyboard, `499cda890a7591c8fc56044726531f6f38a82e35` (Apache-2.0; `LICENSE.txt` and `NOTICE`
  preserved at repository root). Upstream `MagicText.kt`, `SharedText.kt`, and `Words.kt` in
  `:storyboard-text`, `StoryboardPdfExporter` and `StoryboardVideoExporter` in `:storyboard-easel`,
  and `:storyboard-warp` remain intact; additive hooks support `sceneZIndex` and per-state video hold
  durations (`holdDurationForState`).
- Brian Norman's Magic Move deck (`dcnyc25`, `f153f7cb0f4142c3d11bf282e1589a94b04eb523`) and
  ChromaDecks (`e1758512bf0591532bbfab74f4f276455120b8e6`) served as orientation references only;
  no source or assets were copied (`AnimatedMeshBackdrop.kt` is an original indexed-vertex-grid
  implementation).
- Bundled open-source fonts under `deck/src/desktopMain/resources/fonts/` (`Inter`, `JetBrains Mono`,
  `Hanken Grotesk`, `Plus Jakarta Sans`, `Roboto`, `Montserrat`, `Fredoka`, `Assistant`,
  `Nanum Pen Script`, `Noto Emoji`) carry their full OFL-1.1 / Apache-2.0 license texts under
  `licenses/fonts/` and attribution in `NOTICE`.

## Verification record

- `check --no-parallel -Pkotlin.daemon.jvmargs=-Xmx4g -x wasmJsBrowserTest`: passes `:ciacolaStaticCheck`,
  `:deck:verifyDecksCanonical` across all 4 canonical `.deck.json` decks, all `:storyboard*` JVM/Wasm
  checks, and all `:deck*` desktop test suites (including `FoundationDocumentMigrationTest`
  and `LegacyAuthoringRemovalTest`).
- `:deck:spectreTest`: passes all real-window Spectre E2E suites on JetBrains Runtime 25 (`QaStudio`,
  `CiacolaStudioSpectreTest`, `EditorOverhaulSpectreTest`, `HumanRealisticInteractionSpectreTest`,
  `InlineEditorSpaceKeySpectreTest`, `ShowModeNavigationFocusSpectreTest`,
  `StageFocusRestoreSpectreTest`, `CloseStudioCleanupSpectreTest`, `QaHarnessSmokeSpectreTest`, and
  `QaDeckFixesSpectreTest`).
- `python3 -m unittest discover -s tools -p 'test_*.py'`: passes all KPF converter unit and fixture
  tests.
