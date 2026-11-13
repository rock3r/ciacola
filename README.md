![Ciacola mascot presenting the project name on stage](artwork/ciacola-banner.jpg)

**Ciacola** is an experimental foundation for one Compose Desktop conference talk. Its name comes
from Venetian *ciàcola* — a chat or conversation — and is pronounced roughly “CHAH-koh-lah”.

The deck uses [Storyboard](https://github.com/bnorm/storyboard) for scenes, state, navigation,
transitions, shared elements, and fixed-resolution rendering. The `deck` module adds the visual
language and a small set of provisional scenes. It is a spike, not a presentation framework and
not the talk's narrative.

## What is here

The desktop app presents five scenes with fourteen base states (beats). Together they exercise:

- deterministic forward and backward navigation;
- a shared-bounds, Magic-Move-style transition;
- animation that keeps running without advancing the scene;
- an animated mesh made from indexed Compose `Vertices`;
- MagicText transitions for prose;
- MagicText transitions for syntax-highlighted Kotlin;
- a Keynote effects lab (builds, staggers, line draws, code morphs, and a magnifier loupe); and
- a single 1920×1080 design system for type, colour, and spacing.

Around the stage it adds an Easel overview grid, a Jewel Presenter window with speaker notes and
display routing, a Ctrl-click slide review flow, a live theme and reduced-motion switcher
(`Stage Dark` / `Keynote Light`, `System` / `Reduced` / `Full` motion), selectable-text PDF and
60 fps MP4 video export, and an After Effects-style Timeline Studio for scrubbing and tuning
animations. See [`docs/DECK-FOUNDATION.md`](docs/DECK-FOUNDATION.md) for how each piece works.

All visible copy is marked provisional on purpose.

## Run it

The build uses a JetBrains Runtime 25 toolchain for the desktop application. Gradle downloads it
automatically when necessary:

```shell
./gradlew :deck:run
```

The first launch opens a 16:9 1280×720 Story window; window sizes and the current slide are then
remembered in `.storyboard/` at the repository root.

### Shortcuts

| Key | Action |
|---|---|
| `→` `↓` `PgDn` `Space` `Enter` | Next beat (presentation clickers send `PgDn`/`PgUp`) |
| `←` `↑` `PgUp` `Backspace` | Previous beat |
| `B` / `W` | Toggle blackout / whiteout curtain in Show Mode (`Esc`, `B`/`W`, click, or advancing clears it) |
| `⌘G` | Jump to slide (`1..N`) |
| `Esc` | Clear active popup/curtain, exit sub-comp / docked Timeline Studio, or toggle the Overview grid |
| `⌥⌘P` | Toggle fullscreen (**Easel ▸ Fullscreen**) |
| `F2` | Show the Presenter window (`T` start/pause timer, `R` reset, `⌘-` / `⌘=` zoom speaker notes) |
| `F3` or hold `Ctrl` | Inspect mode for slide review; `Ctrl+Click` / `Ctrl+Drag` captures a comment |
| `F4` | Show the Review window |
| `F5` | Toggle the Timeline Studio docked in the Story window |
| `F6` | Open the Timeline Studio in a detached window |

The **Theme** menu switches between `Stage Dark` and `Keynote Light` live, configures **Motion**
(`System`, `Reduced Motion`, `Full Motion`, or `Pause Ambient Loops`, also via
`CIACOLA_REDUCE_MOTION`), **Export ▸ PDF…** writes one high-resolution page per beat with an
invisible selectable Unicode text layer, and **Export ▸ Video (MP4)…** renders deterministic 60 fps
offscreen video via `ffmpeg` (cancellable at any time with `Esc`).

### Hot reload

Run the deck with Compose Hot Reload and apply code edits without restarting:

```shell
./gradlew :deck:hotRunDesktopAsync   # launches in the background
./gradlew :deck:reload               # applies the current source edits
```

The current slide and build step survive a reload.

## Project layout

| Path | Purpose |
|---|---|
| [`deck/`](deck/) | Desktop application, scenes, design system, mesh effect, and text-engine binding |
| [`storyboard-text/`](storyboard-text/) | Upstream MagicText with focused hardening and tests |
| [`docs/DECK-FOUNDATION.md`](docs/DECK-FOUNDATION.md) | Architecture, source notes, attribution, retained TODOs, and verification record |
| [`docs/MAGIC-TEXT-V2-SEAM.md`](docs/MAGIC-TEXT-V2-SEAM.md) | Design boundary and fixtures for a future paragraph-layout renderer |

The other modules are the original Storyboard checkout and examples.

## MagicText work

The existing renderer remains in use. This branch tightens a few parts of its behaviour:

- Unicode tokenization no longer splits surrogate pairs, common emoji sequences, or combining
  marks from their base word;
- unchanged whitespace keeps stable correspondence keys;
- settled transition state is recorded as a Compose side effect;
- returning to an interrupted target reuses its shared-bounds plan; and
- repeated tokens, line breaks, style changes, large inputs, annotations, and representative
  difficult scripts are covered by tests.

The deck sees text rendering through one application-level `DeckTextRenderer` binding. That is the
replacement point for future work; scenes do not choose between engines.

MagicText v2 is not implemented here. Neither are text shaping, glyph extraction, Canvas text
rendering, or the unfinished gradual-entrance renderer. The intended boundary is documented in
[`MAGIC-TEXT-V2-SEAM.md`](docs/MAGIC-TEXT-V2-SEAM.md).

## Checks

Run the focused suites with:

```shell
./gradlew :storyboard-text:jvmTest :storyboard-easel:jvmTest :deck:desktopTest
```

Real-window end-to-end tests drive the app with Spectre's synthetic AWT input. They run as a
background accessory app (`apple.awt.UIElement=true`, no auto focus), so they never steal focus
from what you are doing:

```shell
./gradlew :deck:spectreTest
```

The full non-browser gate is:

```shell
./gradlew check --no-parallel -Pkotlin.daemon.jvmargs=-Xmx4g -x wasmJsBrowserTest
```

The browser tests inherited from Storyboard expect ChromeHeadless. They are excluded above so the
gate also works in environments without Chrome; JVM tests, Wasm compilation, and API checks still
run.

## Upstream and licence

Ciacola is a fork of Brian Norman's
[Storyboard](https://github.com/bnorm/storyboard), based on revision
`499cda890a7591c8fc56044726531f6f38a82e35`. This repository retains Storyboard's Git history and is
distributed under the same [Apache License 2.0](LICENSE.txt). See [NOTICE](NOTICE) for attribution.

The vertex-mesh technique was studied in
[Sina Samaki's ChromaDecks](https://github.com/sinasamaki/ChromaDecks), and the shared-text approach
was compared with
[Brian Norman's Magic Move deck](https://github.com/bnorm/deck.bnorm.dev/tree/dcnyc25/dcnyc25/story).
Neither reference repository exposed a licence file at the revisions studied, so no source or assets
were copied from them. Exact revisions and source locations are recorded in
[`DECK-FOUNDATION.md`](docs/DECK-FOUNDATION.md).
