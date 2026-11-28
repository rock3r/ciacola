# Ciacola `.deck.json` Schema Reference (Format v1)

This document is the authoritative schema reference for `<deckId>.deck.json` files (`CURRENT_DECK_FORMAT_VERSION = 1`), derived from `DeckDocumentModels.kt`, `EffectDeclarations.kt`, `CurveEvaluator.kt`, and `AllDocumentDecks.desktop.kt`.

---

## 1. Root `DeckDocument`

```json
{
  "format": 1,
  "id": "my-talk",
  "title": "My Talk Title",
  "stage": { "width": 1920, "height": 1080 },
  "themes": { "dark": { ... }, "light": { ... } },
  "defaultTheme": "dark",
  "fonts": [ ... ],
  "palettes": { "highlight": { ... } },
  "loopPeriodsMs": { "shaders.trippyKaleidoscope/time": 12000 },
  "slides": [ ... ]
}
```

| Field | Type | Default | Notes |
|---|---|---|---|
| `format` | `Int` | `1` | Must be `1`. Newer format versions open read-only. |
| `id` | `String` | required | Deck identifier; matches `<deckId>.deck.json` and `<deckId>.assets/`. |
| `title` | `String` | `""` | Human-readable deck title shown in Studio App Bar & Presenter View. |
| `stage` | `StageSize` | `{"width":1920,"height":1080}` | Logical stage coordinate space in pixels. |
| `themes` | `Map<String, DeckThemeSpec>` | `{}` | Named themes (typically `"dark"` and `"light"`). |
| `defaultTheme` | `String?` | `null` | Key in `themes` used by default when opening/rendering. |
| `fonts` | `List<DeckFontDeclaration>` | `[]` | Bundled or system font declarations with fallback families. |
| `palettes` | `Map<String, SlidePaletteSpec>` | `{}` | Optional per-slide palette presets referenced by `slide.palette`. |
| `loopPeriodsMs` | `Map<String, Long>` | `{}` | Loop clock periods in ms (`"<specimenRef>/time"` or `"<slideId>/<nodeId>/<name>"`). |
| `slides` | `List<SlideSpec>` | `[]` | Ordered list of slides in the deck. |

### `DeckThemeSpec` & `SlidePaletteSpec`

- `DeckThemeSpec`:
  - `preset`: `"stageDark"` (default), `"stageLight"`, or `"keynoteLight"`
  - `codeScheme`: optional `String?` bundled syntax color scheme id (e.g. `"intellij-islands-dark"`, `"intellij-light"`, `"github-dark"`, `"github-light"`; `null` = preset default)
  - `customCodeScheme`: optional embedded `CodeSchemeSpec?` representing an object-form syntax scheme (`id`, `name`, `appearance`, `background`, `foreground`, optional `selectionBackground`/`lineHighlightBackground`/`lineNumberForeground`/`diffInserted`/`diffDeleted`/`diffChanged`, `tokens` map of object-form `CodeTokenStyleSpec` entries `{ fg, bg, bold, italic, underline, strikethrough }` including optional `"functionDeclaration"`, and optional `source` metadata — without `format`/`version` wrapper fields or string-shorthand token values). Uses CSS `#RRGGBB` / `#RRGGBBAA` hex channel order (matching `*.scheme.json` v1), whereas `DeckThemeSpec.colors` uses Compose `#RRGGBB` / `#AARRGGBB`.
  - Code scheme resolution precedence: `customCodeScheme` > `codeScheme` > preset default (`"intellij-islands-dark"` for dark presets, `"intellij-light"` for light presets), with explicit `colors` overrides (`codeBackground`/`code.background`, `codeForeground`/`code.foreground`, `code.keyword`, `code.string`, `code.comment`, `code.number`, `code.constant`, `code.type`, `code.functionDeclaration`, `code.functionCall`/`code.function`, `code.property`, `code.annotation`, `code.attribute`, `code.tag`, `code.operator`, `code.preprocessor`, `code.punctuation`, `code.invalid`) layered on top.
  - Validation lint codes on `DeckThemeSpec`: `UNKNOWN_CODE_SCHEME` (unrecognized `codeScheme` when `customCodeScheme` is absent), `INVALID_CUSTOM_CODE_SCHEME` (blank `id`/`name`, unknown `appearance`, or invalid CSS hex color), and `UNKNOWN_CODE_SCHEME_TOKEN` (warning for unrecognized key in `customCodeScheme.tokens`).
  - `colors`: `Map<String, String>` (Compose hex `#RRGGBB` or `#AARRGGBB`, e.g. `accent`, `background`, `border`, `cardScrim`, `chipBackground`, `codeBackground`, `codeForeground`, `danger`, `elevatedSurface`, `foreground`, `muted`, `onAccent`, `positive`, `secondary`, `stampFill`, `subtle`, `surface`, `warning`)
  - `typography`: `Map<String, TextStyleOverride>` (`font`, `size`, `weight`, `italic`, `color`, `lineHeight`, `tracking`, `align`, `axes`)
  - `motion`: `ThemeMotionDefaults?` (`durationMs`, `curve`, `transition`)
- `SlidePaletteSpec`:
  - `canvas`, `ink`, `mutedInk`, `accent`, `surface`, `border` (all optional `String?` hex or theme token)

### Bundled Fonts (`BundledFontCatalog`)

Bundled families available in `deck/src/desktopMain/resources/fonts/`:
- `Inter` (weights 100..900)
- `JetBrains Mono` (weights 100..800, normal & italic)
- `Hanken Grotesk` (variable `wght`, normal & italic)
- `Google Sans` (variable `GRAD`, `opsz`, `wght`, normal & italic)
- `Plus Jakarta Sans` (variable `wght`, normal & italic)
- `Montserrat` (variable `wght`, normal & italic)
- `Fredoka` (variable `wdth`, `wght`)
- `Assistant` (variable `wght`)
- `Roboto` (variable `wdth`, `wght`)
- `Nanum Pen Script` (weight 400)
- `Noto Emoji` (variable `wght`, emoji role)

---

## 2. `SlideSpec` & `BeatSpec`

```json
{
  "id": "s01",
  "title": "Opening Hook",
  "skipped": false,
  "palette": null,
  "background": "#0B0D12",
  "beats": [
    { "notes": "Welcome everyone.", "autoAdvanceMs": null },
    { "notes": "Reveal the key insight.", "stagger": { "ms": 60, "order": "readingOrder" } }
  ],
  "nodes": [ ... ],
  "transition": { "type": "magicMove", "durationMs": 550 }
}
```

| Field | Type | Default | Notes |
|---|---|---|---|
| `id` | `String` | required | Unique slide ID across the document. |
| `title` | `String?` | `null` | Plain slide title used in Overview, Timeline, Studio, and Presenter View. |
| `skipped` | `Boolean` | `false` | Skipped slides are omitted from Show Mode, Overview, and video export. |
| `palette` | `String?` | `null` | Optional key in root `palettes`. |
| `background` | `Fill?` | `null` | Overrides theme `background` for this slide. |
| `beats` | `List<BeatSpec>` | `[{}]` | Beat 0 is `"Base"`; beat `1..n` are `"Build 1..n"`. |
| `nodes` | `List<NodeSpec>` | `[]` | Top-level scene graph nodes (painted in order). |
| `transition` | `TransitionSpec?` | `null` | Outgoing transition from this slide to the next non-skipped slide. |

`BeatSpec` fields: `notes: String?`, `phases: Phases?` (`exit`, `move`, `enter` each `Timing?`), `stagger: Stagger?`, `autoAdvanceMs: Int?`.

---

## 3. `NodeSpec` & Payloads

Every `NodeSpec` has `id: String` (unique within the slide or document) and **exactly one** payload object (`text`, `code`, `shape`, `image`, `connector`, `specimen`, `stack`, `row`, `column`, or `loupe`).

Common layout/transform properties on `NodeSpec`:
- `frame`: `{ "x": 128, "y": 240, "w": 1664, "h": 420 }` (any field nullable for flow sizing inside `row`/`column`/`stack`)
- `align`: alignment inside a parent `stack`/`row`/`column` (`"topStart"`, `"topCenter"`, `"topEnd"`, `"centerStart"`, `"center"`, `"centerEnd"`, `"bottomStart"`, `"bottomCenter"`, `"bottomEnd"`)
- `weight`: `Double?` (flex weight inside `row` or `column`)
- `offset`: `{ "x": 0, "y": 0 }`
- `opacity`: `Double` (default `1.0`)
- `rotation`: `Double` (degrees, default `0.0`)
- `scale`: `Double` (uniform) or `{ "x": 1.0, "y": 1.0 }`
- `origin`: `{ "x": 0.5, "y": 0.5 }` (normalized pivot, default center)
- `interactive`: `String?` (e.g. `"keyboard"` for keyboard-captured specimens)
- `builds`: `List<Build>` (mutations/animations at beats `>= 1`)

### Shared Styling Types

- **Color strings**: `#RRGGBB`, `#AARRGGBB`, or a theme/palette token name (e.g. `"accent"`, `"foreground"`, `"elevatedSurface"`, `"@palette.ink"`).
- **`Fill`**:
  - Solid string: `"#171B24"` or `"surface"`
  - Gradient: `{ "gradient": { "type": "linear"|"radial", "angle": 45, "center": {"x":0.5,"y":0.5}, "radius": 0.8, "stops": [{"at":0.0,"color":"#..."},{"at":1.0,"color":"#..."}] } }`
  - Image: `{ "image": { "src": "backdrop.png", "fit": "cover"|"contain"|"fill", "tint": "#..." } }`
- **`PaddingSpec`**: uniform number `24` or `{ "left": 24, "top": 16, "right": 24, "bottom": 16 }` (also accepts `"horizontal"` / `"vertical"` on decode).
- **`StrokeSpec`**: `{ "color": "border", "width": 2.0, "dash": [8, 6], "cap": "round", "join": "round" }`
- **`SurfaceSpec`**: `{ "fill": ..., "stroke": ..., "cornerRadius": 16, "padding": 24 }`

### Payload Reference

1. **`text` (`TextPayload`)**:
   - `value`: `"Plain text"` or array of `TextRun` objects (`{ "text": "...", "font": ..., "size": ..., "weight": 700, "italic": true, "color": "accent", "tracking": ..., "underline": true, "strike": true, "baselineShift": ..., "background": "#...", "axes": { "wdth": 85 } }`)
   - `style`: theme typography role (e.g. `"hero"`, `"title"`, `"heading"`, `"subheading"`, `"body"`, `"bodyEmphasis"`, `"callout"`, `"caption"`, `"eyebrow"`, `"cardTitle"`, `"cardBody"`, `"stamp"`)
   - `font`, `size`, `weight`, `italic`, `color`, `lineHeight`, `tracking`, `align` (`"start"|"center"|"end"|"justify"`), `surface`, `axes`, `morph` (`{ "by": "word"|"character"|"line"|"paragraph", "pruneStopwords": true, "phases": ..., "stagger": ... }`)
   - Note: Multi-line text should explicitly set `lineHeight >= size * 1.15` to avoid collapsed lines.
   - `axes`: static variable font axis coordinates keyed by 4-character OpenType tag, e.g. `{ "wdth": 85, "GRAD": -25, "opsz": 24 }`. Also accepted on theme `typography` roles and on `TextRun`s. Coordinates merge per tag from role to node to run, so a node can override one axis and inherit the rest. Values outside the face's `fvar` range clamp to `[min, max]` and tags the face lacks are ignored. `axes.wght` overrides the `wght` instance picked from `weight`. Lint codes: `INVALID_FONT_AXIS_TAG` (error, malformed tag), `UNSUPPORTED_FONT_AXIS`, `FONT_AXIS_OUT_OF_RANGE`, and `FONT_AXES_IGNORED` (warnings from the font-aware deck linter). Because `GRAD` does not change advances, per-theme `GRAD` on typography roles tunes dark and light themes without reflowing text.
2. **`code` (`CodePayload`)**:
   - `value`: source string
   - `language`: e.g. `"kotlin"`, `"glsl"`, `"sksl"`, `"json"`
   - `highlightLines`: `List<Int>` (1-based line numbers)
   - `fit`: `"contain"` etc.
   - `surface`: `SurfaceSpec?`
3. **`shape` (`ShapePayload`)**:
   - `geometry`: `"rect"`, `"ellipse"`, `"line"`, or `{ "path": "M 0 0 L 100 0 ..." }` (SVG path data)
   - `fill`, `stroke`, `cornerRadius`, `heads` (`{ "start": "none"|"open"|"filled", "end": "none"|"open"|"filled" }`)
4. **`image` (`ImagePayload`)**:
   - `src`: relative asset path resolved inside `<deckId>.assets/` (supports PNG, JPG, WebP, SVG, and animated WebP/GIF)
   - `fit`: `"contain"` (default), `"cover"`, `"fill"`, `"fitWidth"`, `"fitHeight"`, `"none"`
   - `tint`: optional color token or hex
   - Animated WebP/GIF only (see [Animated images](#animated-images)):
     - `loop`: `Boolean` (default `false`: stop on the last frame of the trim)
     - `playbackRate`: `Double` (default `1.0`, must be `> 0`)
     - `startMs`, `endMs`: trim window in source milliseconds (defaults: `0` and the clip's duration)
     - `posterMs`: frame shown before the first `play`/`seek` and in stills, PDF and video export (default: `startMs`, so `0` when untrimmed)
     - `maxPixels`: decode budget as total full-canvas pixels, `width × height × frames` (default `25165824`, 24 × 1024 × 1024; larger clips show only their first frame)
5. **`connector` (`ConnectorPayload`)**:
   - `from`, `to`: target node IDs on the same slide
   - `fromAnchor`, `toAnchor`: optional anchor side/name
   - `style`: optional line style (`"straight"`, `"elbow"`, `"curved"`)
   - `stroke`, `heads`, `gap`
6. **`specimen` (`SpecimenPayload`)**:
   - `ref`: registered specimen ID (see [Specimen Catalog](#6-registered-specimens-alldocumentdecks))
   - `params`: `Map<String, JsonElement>` typed parameters declared by that specimen
   - `tracks`: `Map<String, SpecimenTrackOverride>` (`beat`, `delayMs`, `durationMs`, `curve`)
7. **Containers — `stack`, `row`, `column` (`ContainerPayload`)**:
   - `children`: `List<NodeSpec>`
   - `gap`: `Double?` (spacing between children in `row`/`column`)
   - `padding`: `PaddingSpec?`
   - `alignItems`: cross-axis alignment (`"start"|"center"|"end"|"stretch"`)
   - `justify`: main-axis distribution (`"start"|"center"|"end"|"spaceBetween"|"spaceAround"|"spaceEvenly"`)
   - `surface`: `SurfaceSpec?`
   - `clip`: `Boolean` (default `false`)
8. **`loupe` (`LoupePayload`)**:
   - `children`: `List<NodeSpec>`, `target`: node ID to magnify, `zoom`: `Double` (default `2.0`), `diameter`: `Double?`, `align`: `String?`, `lensFill`: `Fill?`

---

## 4. Builds, Timing, Curves & Transitions

### `Build` (`builds` array on `NodeSpec`)

Discriminated by `"kind"`:
- `"enter"`: reveals the node at `beat` using `effect`, optional `direction`, `args`, `channels` (motion presets, see §5.1), `by` (`"whole"|"child"|"paragraph"|"word"|"character"`), `stagger`, `after`, `delayMs`, `durationMs`, `curve`.
- `"exit"`: hides/exits the node at `beat` (same timing/effect fields as `"enter"`).
- `"emphasis"`: in-place emphasis at `beat` (e.g. `"jiggle"`).
- `"change"`: RFC 7396 JSON Merge Patch applied at `beat` via `"set": { ... }` (plus optional `path` for motion path, `morph` for `TextMorphSpec`, `after`, `delayMs`, `durationMs`, `curve`).
  - Setting `text.axes` animates variable font axes along the build's curve: a tag set both before and after the change interpolates smoothly (declare the starting coordinate on the node, e.g. `"axes": { "wght": 700 }`), while a tag set on only one side switches at the midpoint. `magicMove` interpolates node `axes` the same way. For per-letter axis motion use the `axisWave`/`axisRise` presets or `axis.<tag>` channels (§5.1).
- `"playback"`: controls an animated `image` node's clip at `beat` with `action` and optional `atMs` (source milliseconds) and `rate`:
  - `"play"`: start playing, from `atMs` when given, otherwise from where the clip is.
  - `"pause"`: stop, holding the frame at `atMs` when given, otherwise the current frame.
  - `"seek"`: move to `atMs` (the trim start when omitted), keeping the play/pause state.
  - `"rate"`: change the playback rate to `rate` (required). `rate` is also accepted on the other actions.
  - Playback builds take no `effect`, `after`, `delayMs`, `durationMs` or `curve`, and add no time to the beat's segment.

### Animated images

An `image` whose `src` is an animated WebP or GIF plays in the live deck. All frames are decoded up front in the background, within the `maxPixels` budget; until then, and for clips over the budget, the first frame shows.

- **Without `playback` builds** the clip plays as soon as its slide is on screen.
- **With `playback` builds** it waits on its poster frame until a `play` or `seek` build, and its state at any beat is folded from every `playback` build up to that beat. Stepping forward one beat continues from the live position, unless that beat sets `atMs`. Arriving any other way (stepping back, jumping, re-entering the slide) restores the position the beat was entered at, so stepping back and forward again replays the same segment.
- **Show mode:** `R` restarts the clips on the current slide from the current beat's entry position without changing beat, and `P` pauses or resumes them (on a clip that stopped on its last frame, `P` plays it again). Holding either key acts once. Hovering a clip shows a thin progress bar along the bottom of the drawn image; a plain click or drag seeks (`Ctrl`/`Cmd`-click and right-click are left to review pins and menus). The frame under the pointer holds while the button is down, playback carries on from there on release, and the next beat continues from the scrubbed position. The bar fades out about a second after the pointer leaves. A clip hidden before its `enter` or after its `exit` holds its frame and takes no clicks, and a clip on a slide you step back from holds its frame while the slide leaves. Scrubbing is off in Edit and Review modes.
- **Presenter window:** `P` pauses or resumes the clips and `Shift+R` restarts them (`R` there resets the talk timer). The `Current` preview mirrors the stage clips, including pauses, scrubbing and restarts; the `Next` preview shows the poster frame.
- **`magicMove` transitions** carry a clip on the frame it is on in show mode (its poster frame in exports).
- **Stills, PDF, video export and lint** always show the `posterMs` frame and never the progress bar.
- `lintDeck` warns with `CLIP_SOURCE_MISSING` (playback builds on a missing asset), `CLIP_SOURCE_NOT_ANIMATED` (playback builds or clip fields on a still image, or on a source that is not `.gif`/`.webp`) and `CLIP_OVER_BUDGET` (with the `maxPixels` value the clip needs, or a note that a side is over the 8192 px limit).

```json
{
  "id": "s05:demo",
  "frame": { "x": 960, "y": 300, "w": 832, "h": 468 },
  "image": { "src": "demo.webp", "posterMs": 0, "maxPixels": 40000000 },
  "builds": [
    { "kind": "playback", "beat": 1, "action": "play" },
    { "kind": "playback", "beat": 2, "action": "pause", "atMs": 4500 },
    { "kind": "playback", "beat": 3, "action": "play", "rate": 2.0 }
  ]
}
```

### `Stagger` & `Timing`

- `Stagger`: `{ "ms": 45, "order": "document"|"topToBottom"|"leftToRight"|"readingOrder", "reverse": false, "manual": { "<fragmentKey>": 120 }, "distribution": "linear"|"fromCenter"|"fromEdges"|"random", "curve": ..., "totalMs": 600, "seed": 1 }`
  - `distribution` spreads offsets across ranks: `fromCenter` starts the middle piece first, `fromEdges` the outer ones, `random` shuffles deterministically by `seed`.
  - `curve` shapes the offsets over the normalized rank (a Cavalry-style falloff); `totalMs` sets the last offset directly instead of `ms * (count - 1)`.
  - With the defaults (`linear`, no `curve`, no `totalMs`) offsets are exactly `rank * ms`. `manual` entries always win.
- `Timing`: `{ "delayMs": 0, "durationMs": 350, "curve": ... }`

### `Curve`

- Preset string: `"linear"`, `"ease"`, `"easeIn"`, `"easeOut"`, `"easeInOut"`, `"fastOutSlowIn"`, `"easeInCubic"`, `"easeOutCubic"`, `"easeInOutCubic"`, or `"keynote.<name>"` (`"keynote.none"`, `"keynote.easeIn"`, `"keynote.easeOut"`, `"keynote.easeInOut"`, `"keynote.fadeMoveAlpha"`, `"keynote.fadeMovePos"`, `"keynote.fadeMoveExit"`, `"keynote.moveIn"`, `"keynote.moveOut"`, `"keynote.moveOutDecel"`, `"keynote.moveOutAccel"`, `"keynote.push"`, `"keynote.reveal"`, `"keynote.slide"`, `"keynote.zoomBigExit"`, `"keynote.magicMove"`, `"keynote.pop"`, etc.)
- Cubic bezier object: `{ "bezier": [0.2, 0.0, 0.0, 1.0] }`
- Analytical spring object: `{ "spring": { "damping": 0.72, "stiffness": 380.0 } }`

### `TransitionSpec` (`slide.transition`)

- `type`: `"none"`, `"dissolve"` (default), `"magicMove"`, `"push"`, `"slide"`, `"reveal"`, `"wipe"`, `"iris"`, `"fadeThroughColor"`
- `direction`: `"left"`, `"right"`, `"up"`, `"down"`, etc. (for directional transitions)
- `color`: hex/token (for `"fadeThroughColor"`)
- `center`: `{"x":0.5,"y":0.5}` (for `"iris"`)
- `delayMs`, `durationMs`, `curve`, `phases` (`exit`, `move`, `enter`), `stagger`, `text` (`Map<String, TextMorphSpec>`)

---

## 5. Build Effects (`BuildEffectRegistry.Standard`)

| Effect ID | Tier | Supported Kinds | Piece Delivery | Supported Directions | Default Duration | Args (`ParamSpec`) |
|---|---|---|---|---|---|---|
| `appear` | Layer | All | Yes | — | `0 ms` | — |
| `dissolve` | Layer | All | Yes | — | `500 ms` | — |
| `fadeMove` | Layer | All | Yes | 8-way (`left`,`right`,`up`,`down`,`upLeft`,`upRight`,`downLeft`,`downRight`) | `600 ms` | `distance` (`Float`, default `48`) |
| `moveIn` | Layer | All | Yes | 8-way | `650 ms` | `bounce` (`Boolean`, default `false`) |
| `wipe` | Layer | All | No | 8-way | `600 ms` | — |
| `iris` | Layer | All | No | `in`, `out` | `600 ms` | `centerX` (`0..1`, `0.5`), `centerY` (`0..1`, `0.5`) |
| `zoom` | Layer | All | Yes | — | `500 ms` | `fromScale` (`0.8`), `toScale` (`1.0`), `scaleDelay` (`0.0`) |
| `pop` | Layer | All | Yes | — | `450 ms` | — |
| `blur` | Layer | All | No | — | `500 ms` | `radius` (`Float`, default `24`) |
| `fromDarkness` | Layer | All | No | — | `700 ms` | — |
| `keyboard` | Text | `text` | Yes | — | `50 ms`/char | `seed` (`1`), `caret` (`true`), `charsPerSec` (`22`) |
| `lineDraw` | Path | `shape`, `connector` | No | `startToEnd`, `endToStart`, `fromCenter`, `clockwise`, `counterClockwise` | `800 ms` | `includeEndpoints` (`true`) |
| `trace` | Content | All | No | 8-way | `1000 ms` | — |
| `sparkle` | Content | All | No | 8-way | `900 ms` | — |
| `shimmer` | Content | All | No | — | `900 ms` | — |
| `jiggle` (emphasis) | Layer | All | Yes | — | `500 ms` | `amplitude` (`6`), `cycles` (`3`) |
| `rise` | Layer | All | Yes | `up`, `down`, `left`, `right` | `750 ms` | `distance` (em, `0.9`), `reveal` (`true`), `revealPadding` (em, `0.12`), `fadeWindow` (`0.35`) |
| `focusPull` | Layer | All (+ emphasis) | Yes | — | `900 ms` | `radius` (em, `0.3`), `restOpacity` (`0.45`), `fromScale` (`1.06`), `focusFrom` (`0`), `focusTo` (`1`), `focusWidth` (em, `1.2`), `settle` (`true`) |
| `wave` (emphasis) | Layer | All | Yes | — | `2000 ms` | `amplitude` (em, `0.18`), `rotation` (deg, `0`), `scale` (`0`), `cycles` (`2`), `phasePerPiece` (`0.08`), `shape` (`sine`), `fade` (`0.15`) |
| `trackingBreathe` (emphasis) | Layer | All | Yes | — | `3000 ms` | `amplitude` (em per glyph, `0.12`), `cycles` (`1`), `phasePerLine` (`0.15`), `shape` (`sine`), `fade` (`0.12`) |
| `axisWave` (emphasis) | Layer | All | Yes | — | `2400 ms` | `axis` (tag, `wght`), `amplitude` (axis units, `200`), `offset` (`0`), `cycles` (`2`), `phasePerPiece` (`0.08`), `phasePerLine` (`0`), `shape` (`sine`), `fade` (`0.15`) |
| `axisRise` | Layer | All | Yes | `up`, `down`, `left`, `right` | `900 ms` | `axis` (tag, `wght`), `axisFrom` (axis units, `-300`), `distance` (em, `0.5`), `reveal` (`true`), `revealPadding` (em, `0.12`), `fadeWindow` (`0.35`) |

### 5.1 Motion presets and channels

`rise`, `focusPull`, `wave`, `trackingBreathe`, `axisWave` and `axisRise` are **motion presets**: typographic effects built
from per-piece animatable **channels**. Each preset turns its `args` into default channels, and a
build's `channels` map overrides any of them field by field, so you can change one curve, window or
amplitude and keep the rest. Presets work on any node, and shine with `by: "character"` or `"word"`.
Per-character delivery needs the node's font declared in the deck's `fonts`: without a font
resolver the text cannot be certified safe to split, so `by: "character"` quietly falls back to
word pieces. `trackingBreathe` spreads pieces within a line, so a one-word line then does not move.

Channels and units (lengths are in em of the piece's font size; for whole-node delivery 1 em is one
line of the node's height):

| Channel | Rest | Unit |
|---|---|---|
| `opacity` | `1` | 0..1 |
| `offsetX`, `offsetY` | `0` | em |
| `scale`, `scaleX`, `scaleY` | `1` | factor (`scale` multiplies the axis scales) |
| `rotation` | `0` | degrees |
| `blur` | `0` | em |
| `tracking` | `0` | em per glyph, spread around the centre of each line |
| `axis.<tag>` | `0` | offset in the axis's own units (for example `axis.wght`, `axis.wdth`) |

An `axis.<tag>` channel varies a variable font axis per piece. Its value is added to the piece's
resting coordinate and clamped to the axis range; an axis the face does not have is ignored. The
tag is the four-character OpenType tag. Axis motion needs the node's variable font declared in the
deck's `fonts` (with `"variable": true`), and it draws only for plain single-style left-to-right
pieces (no brush, shadow, background, decoration or baseline shift); other pieces keep their normal
paint and still play the other channels. Each moving piece keeps its resting centre as its advance
changes.

A channel value is `base + wave` over the piece's progress (`0..1`):

```json
"channels": {
  "offsetY": { "from": 1.1, "to": 0, "curve": { "bezier": [0.2, 0.9, 0.1, 1] }, "window": [0, 0.8] },
  "rotation": { "keyframes": [{ "at": 0, "value": 6 }, { "at": 0.7, "value": -1, "curve": "easeOut" }, { "at": 1, "value": 0 }] },
  "scale": { "wave": { "amplitude": 0.05, "cycles": 2, "phasePerPiece": 0.1, "shape": "sine", "fade": 0.2 } }
}
```

- **Tween**: `from`, `to` (a missing end uses the rest value), `curve`, and `window` (`[start, end]`
  slice of the progress the tween spans, for example to finish a fade early).
- **Keyframes**: sorted `{ "at", "value", "curve" }` stops; each `curve` shapes the segment that ends
  at that stop. Keyframes take precedence over `from`/`to`.
- **Wave**: `amplitude`, `offset`, `shape` (`sine`, `triangle`, `square`, `saw`, `noise` with
  `seed`), `cycles` over the progress, `phase`, `phasePerPiece` and `phasePerLine` (fractions of a
  cycle, for travelling waves), and `fade` (share of the progress used to ease the wave in and out).

Timing: the build `curve` remaps each piece's progress before the channels apply their own curves
(presets default to `linear`), and `stagger` (with its `distribution`, `curve` and `totalMs`) sets
when each piece starts. `focusPull` used as an emphasis computes blur and opacity from a focal point
travelling across the text; an explicit `blur` or `opacity` channel replaces that sweep.

Lint: `UNKNOWN_MOTION_CHANNEL` (warning), `INVALID_MOTION_WINDOW`, `EMPTY_MOTION_KEYFRAMES`,
`MOTION_KEYFRAME_OUT_OF_RANGE`, `MOTION_KEYFRAMES_UNSORTED`, `INVALID_MOTION_WAVE` and
`NEGATIVE_STAGGER_TOTAL_MS` (errors). A worked example deck lives in
[`docs/decks/motion-presets.deck.json`](decks/motion-presets.deck.json).

---

## 6. Registered Specimens (`AllDocumentDecks`)

Custom and discovered decks have access to `AllDocumentDecks.allSpecimens` (38 registered specimens):

### Foundation (`foundation.*`)
- `foundation.ambientMesh` — Ambient Vertex Mesh (`loop("time", 6000 ms)`)
- `foundation.dottedLeaderRow` — Dotted Leader Row

### Shaders Quickie 2025 (`shaders.*`)
- `shaders.trippyKaleidoscope` (`12000 ms`)
- `shaders.journeyDunes` (`14000 ms`)
- `shaders.hazeScrollHeader` (`8000 ms`)
- `shaders.liquidGlassLens` (`8000 ms`)
- `shaders.raindropsWindow` (`10000 ms`)
- `shaders.colorEvolution`
- `shaders.liveUvStatic`
- `shaders.liveUvAnimated` (`6283 ms`)
- `shaders.sdfGoalCircle`
- `shaders.sdfRawDistance`
- `shaders.sdfStepCircle`
- `shaders.sdfCsgSubtract`
- `shaders.sdfCsgUnionSubtract`
- `shaders.plaidHyperspaceStarfield` (`16000 ms`)

### Jewel Talk 2024 (`jewel.*`)
- `jewel.taskBasedProfilers`
- `jewel.addDeviceDialog` (`12000 ms`)
- `jewel.geminiAuthCard` (`4000 ms`)
- `jewel.geminiPromptInput` (`12000 ms`)
- `jewel.geminiStreamingChat` (`10000 ms`)
- `jewel.geminiChatComposer` (`8000 ms`)
- `jewel.referencesBouncyExpand` (`3500 ms`)
- `jewel.geminiToolWindowFull` (`6000 ms`)
- `jewel.confettiPartyPopper` (`1400 ms`, param `mirrored: Boolean`)
- `jewel.markdownStandaloneSample` (`14000 ms`)
- `jewel.swingComposeInterop`
- `jewel.microinteractionScrollFab` (`8500 ms`)
- `jewel.microinteractionEnabledButton` (`2400 ms`)
- `jewel.adaptiveDemoWizard` (`5400 ms`)
- `jewel.adaptiveWireframePanes` (`6400 ms`, param `widthFraction: Float` `0.35..0.96`)
- `jewel.m3ExpressiveShowcase` (`9400 ms`)
- `jewel.springTokenTree`
- `jewel.geminiLoadingChoreography` (`2400 ms`)
- `jewel.referencesBouncySpringV2` (`2200 ms`)
- `jewel.cohesionIdeShowcase` (param `panel: Choice`)

### Not Just Plain Text 2024 (`njpt.*`)
- `njpt.jewelMarkdownSplitDemo` (`14000 ms`)
- `njpt.geminiDarkMarkdownChat` (`12000 ms`)
