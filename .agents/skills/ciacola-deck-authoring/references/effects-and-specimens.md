# Quick Cheat Sheet: Effects, Curves, Layouts & Specimens

## Standard Build Effects (`BuildEffectRegistry.Standard`)

- **Simple entrances/exits**: `appear` (0ms), `dissolve` (500ms), `fadeMove` (600ms, `direction`, `args.distance`), `moveIn` (650ms, `direction`, `args.bounce`), `pop` (450ms), `zoom` (500ms, `args.fromScale`, `args.toScale`, `args.scaleDelay`), `wipe` (600ms, `direction`), `iris` (600ms, `direction`: `in`|`out`), `blur` (500ms, `args.radius`), `fromDarkness` (700ms).
- **Text typing**: `keyboard` (on `text` nodes, `by`: `character`, `args.charsPerSec`, `args.caret`, `args.seed`).
- **Vector path draw**: `lineDraw` (on `shape`/`connector`, `direction`: `startToEnd`|`endToStart`|`fromCenter`|`clockwise`|`counterClockwise`).
- **Content shimmer/sparkle**: `trace`, `sparkle`, `shimmer`.
- **Emphasis**: `jiggle` (`kind: "emphasis"`, `args.amplitude`, `args.cycles`).
- **Motion presets** (typographic, best with `by: "character"` or `"word"`; lengths in em): `rise` (enter/exit, `direction`, `args.distance`, `args.reveal`, `args.revealPadding`, `args.fadeWindow`), `focusPull` (enter/exit, or emphasis to sweep a focal plane: `args.radius`, `args.restOpacity`, `args.focusFrom`, `args.focusTo`, `args.focusWidth`, `args.settle`), `wave` (emphasis, `args.amplitude`, `args.rotation`, `args.scale`, `args.cycles`, `args.phasePerPiece`, `args.shape`, `args.fade`), `trackingBreathe` (emphasis, `args.amplitude`, `args.cycles`, `args.phasePerLine`), `axisWave` (emphasis on a variable font axis: `args.axis` tag, `args.amplitude` and `args.offset` in axis units, `args.cycles`, `args.phasePerPiece`) and `axisRise` (enter/exit rise that settles the axis from `args.axisFrom`). Any preset also takes `axis.<tag>` channels (for example `"axis.wght"`); axis motion needs a declared variable font and draws only on plain single-style left-to-right pieces. To morph a whole node's axes, use a `change` build that sets `text.axes` (declare the starting coordinates on the node so they interpolate instead of switching at the midpoint). Override any channel with `"channels": { "offsetY": { "curve": ... } }` (see `docs/DECK-SCHEMA.md` §5.1); a worked example lives in `docs/decks/motion-presets.deck.json`. Declare the node's font in `fonts` (and set `font`) for per-character delivery; with no declared fonts `by: "character"` falls back to word pieces, and `trackingBreathe` on a one-word line does nothing.
- **Stagger shapes**: `"stagger": { "ms": 40, "distribution": "fromCenter" | "fromEdges" | "random", "curve": "easeOut", "totalMs": 600, "seed": 3 }`.

## Standard Curves

- Presets: `"linear"`, `"ease"`, `"easeIn"`, `"easeOut"`, `"easeInOut"`, `"fastOutSlowIn"`, `"keynote.magicMove"`, `"keynote.pop"`, `"keynote.moveIn"`, `"keynote.fadeMovePos"`, `"keynote.fadeMoveAlpha"`
- Custom cubic bezier: `{ "bezier": [0.2, 0.0, 0.0, 1.0] }`
- Spring physics: `{ "spring": { "damping": 0.72, "stiffness": 380.0 } }`

## Cross-Slide Magic Move Tips

- Keep matching `node.id` values across consecutive slides (`s01` -> `s02`) when `s01.transition.type == "magicMove"`.
- For smooth cross-slide text morphs on a shared text node ID, attach `"morph": { "by": "word" }` on `text` or `transition.text["<nodeId>"]`.

## Registered Specimen Refs (`AllDocumentDecks.allSpecimens`)

- **Foundation**: `foundation.ambientMesh` (6000ms), `foundation.dottedLeaderRow`
- **Shaders**: `shaders.trippyKaleidoscope` (12000ms), `shaders.journeyDunes` (14000ms), `shaders.hazeScrollHeader` (8000ms), `shaders.liquidGlassLens` (8000ms), `shaders.raindropsWindow` (10000ms), `shaders.colorEvolution`, `shaders.liveUvStatic`, `shaders.liveUvAnimated` (6283ms), `shaders.sdfGoalCircle`, `shaders.sdfRawDistance`, `shaders.sdfStepCircle`, `shaders.sdfCsgSubtract`, `shaders.sdfCsgUnionSubtract`, `shaders.plaidHyperspaceStarfield` (16000ms)
- **Jewel**: `jewel.taskBasedProfilers`, `jewel.addDeviceDialog` (12000ms), `jewel.geminiAuthCard` (4000ms), `jewel.geminiPromptInput` (12000ms), `jewel.geminiStreamingChat` (10000ms), `jewel.geminiChatComposer` (8000ms), `jewel.referencesBouncyExpand` (3500ms), `jewel.geminiToolWindowFull` (6000ms), `jewel.confettiPartyPopper` (1400ms), `jewel.markdownStandaloneSample` (14000ms), `jewel.swingComposeInterop`, `jewel.microinteractionScrollFab` (8500ms), `jewel.microinteractionEnabledButton` (2400ms), `jewel.adaptiveDemoWizard` (5400ms), `jewel.adaptiveWireframePanes` (6400ms), `jewel.m3ExpressiveShowcase` (9400ms), `jewel.springTokenTree`, `jewel.geminiLoadingChoreography` (2400ms), `jewel.referencesBouncySpringV2` (2200ms), `jewel.cohesionIdeShowcase`
- **Markdown**: `njpt.jewelMarkdownSplitDemo` (14000ms), `njpt.geminiDarkMarkdownChat` (12000ms)

> Note: When using an animated specimen with a loop period, declare `"<ref>/time": <periodMs>` in root `loopPeriodsMs`.
