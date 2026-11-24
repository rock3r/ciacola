![Ciacola mascot presenting the project name on stage](artwork/ciacola-banner.jpg)

**Ciacola** is an experimental, document-driven Compose Desktop presentation app and authoring studio built on [Storyboard](https://github.com/bnorm/storyboard). Its name comes from Venetian *ciàcola* — a chat or conversation — and is pronounced roughly “CHAH-koh-lah”.

Decks live in human-readable [`.deck.json`](docs/DECK-SCHEMA.md) files paired with optional live `@Composable` and SkSL shader specimens, so you can edit slides directly in the visual Studio or in JSON without recompiling, while keeping hardware-accelerated animations, Keynote-style transitions, and crisp code morphs.

---

## Quick start

Launch the desktop app from the repository root (Gradle automatically provisions JetBrains Runtime 25):

```shell
./gradlew :deck:run
```

The app opens **Ciacola Foundation** (`foundation`) by default and remembers window geometry, open panels, and slide position in `.storyboard/`. Four canonical decks are included out of the box:

- `foundation` — 5-slide sandbox exercising core typography, shaders, code morphs, and transitions
- `shaders-quickie-2025` — 45 slides with live interactive SkSL teaching shaders
- `jewel-talk-2024` — 73 slides with live JetBrains Jewel UI & Swing Bridge specimens
- `not-just-plain-text-2024` — 141 slides exploring typography morphs, text effects, and Markdown

Pass `-Pciacola.deck=<id>` (for example `./gradlew :deck:run -Pciacola.deck=shaders-quickie-2025`), use the App Bar switcher (`Open .deck.json…` for external files), or press `⌘⌥1`..`⌘⌥4` to switch decks.

For live Kotlin specimen editing without restarting the app:

```shell
./gradlew :deck:hotRunDesktopAsync   # launches in the background
./gradlew :deck:reload               # applies current source changes
```

---

## Highlights

- **Present like Keynote:** Clean audience Show Mode, companion **Presenter View** (`F2`) with speaker notes, timer, Quick Jump, display routing, **Blackout / Whiteout** stage curtains (`B` / `W`), and an **Overview** grid (`Esc`, filterable via `⌘K` / `Ctrl+K`).
- **Visual Studio authoring:** Centered **`Edit` | `Review` | `Present`** modes with direct stage drag/snap/nudge, inline text editing, **`Design | Animate`** inspector tabs, after-effects-style **Timeline** (`F5` / `F6`), live **Deck Theme** editor (`F7`), full-deck **Issues** linter (`F8`), and unified Undo/Redo (`⌘Z` / `⇧⌘Z`) synced atomically to `.deck.json`.
- **Single-canvas text & code motion:** Paragraph-measured **`MagicText v2`** behind the application-level `DeckTextRenderer` boundary (emoji/grapheme-safe morphs and builds, zero per-token composables) alongside animated Heckel-diff code morphs built on Rahul Ravikumar's **[Warp](https://github.com/tikurahul/warp)** (`storyboard-warp`) and **`:syntax-highlight`** (pure-KMP ANTLR4 lexers across 32 languages with zero extra runtime dependencies).
- **Shaders & Keynote effects:** 3-tier build effects (Layer, multi-contour SVG Path trimming, SkSL Content shaders), 9 slide transitions including cross-slide Magic Move, interactive SkSL specimens, and reduced-motion / paused-loop modes.
- **Export & CLI workflows:** Interactive and headless export to **selectable-text PDF** and **60 fps H.264 MP4 video** (via `ffmpeg`), headless deck linting/still rendering, and a zero-dependency Python 3 Apple Keynote (`.kpf`) converter (`tools/kpf_to_deck_json.py`).

## Screenshots

| Studio — Dark theme, Timeline & Animate inspector | Studio — Light theme & Design inspector |
|---|---|
| ![Ciacola Studio in dark theme showing Timeline tracks and the Animate inspector](https://static.sebastiano.dev/public/2c7712cc-3f29-4c99-bf71-6fff0ad27eb1.png) | ![Ciacola Studio in light theme showing the stage canvas and Design inspector](https://static.sebastiano.dev/public/51217aa0-4e64-401c-aeb4-ab9d1c4234d3.png) |

| Presenter View (`F2`) | Slide Overview & Quick Jump (`Esc`, `⌘K` / `Ctrl+K`) |
|---|---|
| ![Ciacola Presenter View showing current and next slide previews, speaker notes, timer, and stage controls](https://static.sebastiano.dev/public/6abbf902-3ec9-4d8a-b3aa-d78b0be19041.png) | ![Ciacola Overview strip view with jump-to-slide search bar](https://static.sebastiano.dev/public/76410e1d-6069-4cf7-8436-c62c5945e3ac.png) |

---

## Documentation

| Guide | Audience & Contents |
|---|---|
| [User Guide](docs/USER-GUIDE.md) | Presenting, Studio controls, keyboard shortcuts, review workflow, PDF/MP4 export, CLI tasks, and Keynote `.kpf` import |
| [Deck Schema Reference](docs/DECK-SCHEMA.md) | Authoritative `<deckId>.deck.json` v1 schema, builds, transitions, curves, and specimen payloads |
| [Architecture & Foundation Notes](docs/DECK-FOUNDATION.md) | Module DAG, `DeckDocumentStore` & atomic sync, isolated `RenderSession`s, source attribution, and verification record |
| [MagicText v2 Seam](docs/MAGIC-TEXT-V2-SEAM.md) | `DeckTextRenderer` contract, `MagicTextDiff`, and Two-Condition Admission character-safety design |

---

## Repository structure

| Module / Path | Role |
|---|---|
| [`syntax-highlight/`](syntax-highlight/) | Standalone zero-extra-dependency KMP ANTLR4 lexers (`commonMain`: JVM, JS, WasmJs) covering 32 languages, including 1-level sub-highlighting for Diff and Markdown |
| [`deck/`](deck/) | Desktop application root (`Main.kt`, `DeckCatalog`, `AllDocumentDecks`), 4 tracked `.deck.json` talks + live `@Composable`/SkSL specimens, headless CLI (`lintDeck`, `renderSlides`, `exportVideo`), and Spectre E2E tests |
| [`deck-core/`](deck-core/) | `.deck.json` schema & validation, `CanonicalJson`, `DeckDocumentStore`, atomic file sync, analytical `SegmentPlanner`, `DeckClock`, `DeckTheme`, `DeckFontResolver` |
| [`deck-effects/`](deck-effects/) | `MagicText v2` behind `DeckTextRenderer`, 3-tier build effects & transitions, SkSL shaders, animated mesh backdrop, `WarpCodeAdapter`, Skia path trimming |
| [`deck-presenter/`](deck-presenter/) | Jewel Presenter View window, speaker notes, timer, display routing, Quick Jump, and stage curtains |
| [`deck-review/`](deck-review/) | `Ctrl+Click` slide review overlay, comment store, and `agentapi` dispatch |
| [`deck-timeline/`](deck-timeline/) | Docked/detached Timeline Studio, analytical track planner cache, stagger and phase sculpting |
| [`deck-inspector/`](deck-inspector/) | Unified Studio inspector (`Design | Animate`), direct stage overlay, Deck Theme sheet, and Deck Linter |
| [`storyboard-text/`](storyboard-text/), [`storyboard-warp/`](storyboard-warp/), [`storyboard-easel/`](storyboard-easel/), [`storyboard/`](storyboard/), [`storyboard-layout/`](storyboard-layout/) | Storyboard core modules with `MagicTextDiff`, [Warp](https://github.com/tikurahul/warp)-derived code-morph diffing wired to 32 `:syntax-highlight` languages, and deterministic PDF/video export hooks |
| [`tools/`](tools/) | Apple Keynote `.kpf` -> `.deck.json` Python 3 converter (`kpf_to_deck_json.py`) |

The [`examples/`](examples/) modules are retained from upstream Storyboard.

---

## Verification (for contributors)

```shell
# Full non-browser verification gate (module DAG static check, canonical JSON check, JVM/desktop/Wasm compile & unit tests)
./gradlew check --no-parallel -Pkotlin.daemon.jvmargs=-Xmx4g -x wasmJsBrowserTest

# Real-window end-to-end Spectre suite (runs headless-friendly as an accessory app on macOS)
./gradlew :deck:spectreTest

# KMP syntax-highlighter browser tests (requires ChromeHeadless)
./gradlew :syntax-highlight:wasmJsBrowserTest
```

See [Architecture & Foundation Notes](docs/DECK-FOUNDATION.md#verification-record) for module-level gates.

---

## Upstream and licence

Ciacola is derived from Brian Norman's [Storyboard](https://github.com/bnorm/storyboard) (revision `499cda890a7591c8fc56044726531f6f38a82e35`, which incorporates Rahul Ravikumar's [Warp](https://github.com/tikurahul/warp) for animated code snippet transitions in `:storyboard-warp`). This repository retains Storyboard's Git history and is distributed under the [Apache License 2.0](LICENSE.txt). See [NOTICE](NOTICE) for attribution.

### Open-source licences and bundled fonts

All third-party libraries, adapted grammars (`KotlinLexer.g4`, `XMLLexer.g4`), Unicode Character Database identifier tables, build tooling, and bundled open-source fonts (SIL Open Font License 1.1 in `deck/src/desktopMain/resources/fonts/` and `examples/shared/src/commonMain/composeResources/font/`) are documented with their copyright notices, Reserved Font Names, upstream URLs, and full verbatim licence texts in [`OPEN_SOURCE_LICENSES.md`](OPEN_SOURCE_LICENSES.md).

The vertex-mesh technique was studied in [Sinasamaki's ChromaDecks](https://github.com/sinasamaki/ChromaDecks), and the shared-text approach was compared with [Brian Norman's Magic Move deck](https://github.com/bnorm/deck.bnorm.dev/tree/dcnyc25/dcnyc25/story). Neither reference repository exposed a licence file at the revisions studied, so no source or assets were copied from them. Exact revisions and source locations are recorded in [`docs/DECK-FOUNDATION.md`](docs/DECK-FOUNDATION.md).
