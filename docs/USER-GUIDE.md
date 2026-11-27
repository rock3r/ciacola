# Ciacola User Guide

This guide covers presenting, authoring, reviewing, and exporting decks in Ciacola. For the `.deck.json` file format see [DECK-SCHEMA.md](DECK-SCHEMA.md), and for internal architecture see [DECK-FOUNDATION.md](DECK-FOUNDATION.md).

---

## 1. Running Ciacola and Choosing a Deck

Launch the desktop app from the repository root (Gradle auto-provisions JetBrains Runtime 25 on first run):

```shell
./gradlew :deck:run
```

By default, Ciacola opens the **Ciacola Foundation** deck (`foundation`) in a 1280×720 window (remembering window size, open tools, and slide position in `.storyboard/session.json`).

Four canonical decks are included out of the box:

| Deck ID | Slides | Summary |
|---|---|---|
| `foundation` | 5 | Provisional showcase covering MagicText v2, Magic Move, shaders, syntax-highlighted code morphs, and Keynote effects |
| `shaders-quickie-2025` | 45 | *Shaders Quickie* talk with interactive SkSL teaching shaders |
| `jewel-talk-2024` | 73 | *Project Sparkles* talk with live Jewel Swing/Compose component specimens |
| `not-just-plain-text-2024` | 141 | *Not Just Plain Text* talk with typography morphs, SkSL text effects, and Markdown specimens |

Switch decks at any time using any of these:
- **Launch flag:** `./gradlew :deck:run -Pciacola.deck=<deck-id>` (e.g. `shaders-quickie-2025`)
- **App Bar switcher:** click the leftmost deck selector in the Studio top bar (`Open .deck.json…` loads any external deck file)
- **Menu bar / shortcuts:** **Deck** menu or `⌘⌥1`..`⌘⌥4` (`⌘⌥1`..`⌘⌥9` when additional `.deck.json` files are discovered in `-Dciacola.decks.dir=<path>`)

### Compose Hot Reload

While iterating on Kotlin code or specimens, keep the app running and apply edits in place:

```shell
./gradlew :deck:hotRunDesktopAsync   # starts the app in the background
./gradlew :deck:reload               # hot-reloads current source changes
```

---

## 2. Presenting and Presenter View

Switch to clean audience presentation via the centered **`Present`** mode segment in the App Bar (or **`⇧⌘P`** on macOS).

- **Overview Grid / Quick Jump (`Esc`):** toggles the overview of all non-skipped slides. On larger decks, press **`⌘K`** (`Ctrl+K` on Linux/Windows) inside Overview to filter and jump by slide title or number.
- **Presenter View (`F2`):** opens the companion window with current/next slide previews, speaker notes, talk timer, and stage controls:
  - **`T`**: start or pause the talk timer (`R` resets it)
  - **`⌘-` / `⌘=`**: zoom speaker notes text size
  - **`⌘G`** (or `G` outside text fields): quick-jump to slide `1..N`
  - **Present on Display** menu / toolbar: route the audience stage to a secondary display
- **Stage curtains (`B` / `W`):** toggle a solid blackout or whiteout screen over the audience stage (`Esc`, repeating `B`/`W`, clicking the stage, or advancing clears the curtain).

---

## 3. Authoring in Studio

The Studio shell organizes deck editing around three modes in the centered App Bar selector (**`Edit` | `Review` | `Present`**) plus standalone/overlay tools:

### Direct Stage & Selection Inspector (`Design | Animate`)
- Click elements on the stage to select them (container-first selection; double-click enters a container or opens inline text editing on text elements; `Enter` / `⌘Enter` saves inline edits, `Esc` cancels or pops selection).
- Drag elements on the stage with viewport-scaled alignment guides (`0.5` stage-unit snapping on release), or nudge with `Arrow` (`1 dp`) / `Shift+Arrow` (`10 dp`). Delete selected elements with `⌘⌫`.
- **`Design` tab:** edit placement, alignment, styling, text payload properties, code language/fit, and custom parameters for live specimens.
- **`Animate` tab:** inspect and sculpt enter, exit, emphasis, and `change` builds, curves, stagger, and intra-text morph settings.

### Timeline & Issues Dock (`F5` / `F6` / `F8`)
- **`Timeline` (`F5`):** opens the bottom Timeline track editor (switch between **`Timeline`** and **`Issues`** tabs in the bottom header). Detach the Timeline into its own window with **`F6`**.
- **`Issues` (`F8`):** runs live and full-deck checks for schema/asset/font issues, text clipping or overflow, safe-area violations, overlapping text, and WCAG contrast across dark and light themes. Clicking an issue jumps straight to the slide, beat, and element.

### Deck Theme & Motion (`F7`)
- **`Deck Theme` (`F7`):** opens the right-aligned Theme sheet to switch themes (e.g. Dark / Light), edit semantic theme colors and typography roles, configure theme motion defaults, manage slide palettes, or copy themes from another `.deck.json`.
- **Theme ▸ Motion menu:** choose **`System`**, **`Full Motion`**, **`Reduced Motion`**, or **`Pause Ambient Loops`** (also controllable on launch via `CIACOLA_REDUCE_MOTION=true|false`).

### Undo / Redo & Live File Sync
- **`⌘Z` / `⇧⌘Z`** (`Ctrl+Z` / `Ctrl+Y`): unified undo/redo across stage edits, Timeline changes, and Deck Theme changes (yields automatically while typing in a text input).
- When running against a writable `-Dciacola.decks.dir` folder (the default for `:deck:run`), edits save safely back to `<deckId>.deck.json` and external edits hot-reload cleanly. External format versions or classpath-only resources open read-only.

---

## 4. Reviewing Slides

- Enter review inspect mode with **`F3`** or by holding **`Ctrl`**, then **`Ctrl+Click`** (or **`Ctrl+Drag`**) anywhere on the stage to pin a comment to that slide and position.
- Open the companion Review feed window with **`F4`** to triage comments, copy structured prompts, or dispatch them via `agentapi`.

---

## 5. Keyboard Shortcuts

| Shortcut | Context | Action |
|---|---|---|
| `→` `↓` `PgDn` `Space` `Enter` | Navigation | Next beat / slide (yields to interactive keyboard specimens & text fields) |
| `←` `↑` `PgUp` `Backspace` | Navigation | Previous beat / slide |
| `Esc` | Global | Dismiss popup/curtain, exit inline edit/selection/Review Inspect, close Studio panels, or toggle Overview |
| `B` / `W` | Show / Presenter | Toggle Blackout / Whiteout stage curtain |
| `⌘K` / `Ctrl+K` | Overview | Quick-jump filter across slides |
| `⌘G` | Presenter | Jump to slide `1..N` |
| `T` / `R` | Presenter | Start/pause talk timer / reset timer |
| `R` | Show | Restart the animated images on the current slide (same beat) |
| `P` | Show | Pause / resume the animated images on the current slide |
| `⌘-` / `⌘=` | Presenter | Decrease / increase speaker notes font size |
| `⌥⌘P` | Window | Toggle Easel fullscreen |
| `⌘⌥1`..`⌘⌥9` | Global | Switch catalog deck |
| `⇧⌘E` / `⇧⌘R` / `⇧⌘P` | Studio | Switch to `Edit` / `Review` / `Present` mode |
| `F2` | Global | Toggle Presenter window |
| `F3` / hold `Ctrl` | Global | Toggle / hold Review Inspect mode |
| `F4` | Global | Toggle Review window |
| `F5` / `F6` | Studio | Toggle docked Timeline / detached Timeline window |
| `F7` / `F8` | Studio | Toggle Deck Theme sheet / Issues dock |
| `Arrow` / `Shift+Arrow` | Stage Edit | Nudge selected element (`1 dp` / `10 dp`) |
| `⌘⌫` | Stage Edit | Delete selected element |
| `⌘Z` / `⇧⌘Z` | Studio | Unified Undo / Redo (`Ctrl+Z` / `Ctrl+Y` outside macOS) |

---

## 6. Exporting (UI & Headless CLI)

### From the Menu Bar
- **Export ▸ PDF…:** exports one high-resolution page per beat with an invisible selectable Unicode text layer.
- **Export ▸ Video (MP4)…:** exports deterministic 60 fps H.264 video via `ffmpeg` (press `Esc` to cancel an active export).

### From the Command Line

```shell
# Lint a deck (optional --slides / -Pciacola.theme / -Pciacola.json=true)
./gradlew :deck:lintDeck -Pciacola.deck=foundation

# Render PNG stills + manifest.json headlessly
./gradlew :deck:renderSlides -Pciacola.deck=foundation -Pciacola.out=build/stills

# Export CFR H.264 MP4 headlessly
./gradlew :deck:exportVideo -Pciacola.deck=foundation -Pciacola.out=build/out.mp4

# Canonicalize or verify tracked *.deck.json files
./gradlew :deck:formatDecks
./gradlew :deck:verifyDecksCanonical
```

---

## 7. Importing from Apple Keynote (`.kpf`)

`tools/kpf_to_deck_json.py` is a zero-dependency Python 3 tool that converts Apple Keynote HTML (`KPF`) exports (plus optional `.key` archives) into canonical `<deckId>.deck.json` documents and `<deckId>.assets/` directories.

```shell
python3 tools/kpf_to_deck_json.py --help
python3 -m unittest discover -s tools -p 'test_*.py'
```
