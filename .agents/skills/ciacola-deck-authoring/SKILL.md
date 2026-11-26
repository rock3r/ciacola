---
name: ciacola-deck-authoring
description: Create, edit, lint, visually inspect, and export Ciacola presentation decks using `.deck.json` and the headless `:deck:*` Gradle CLI tools. Use when authoring new slides or talks, fixing visual/contrast/layout review comments, or verifying slide PNGs and MP4 exports without writing Kotlin boilerplate.
---

# Ciacola Deck Authoring

Author presentations directly as declarative `<deckId>.deck.json` files (Format v1) rather than writing Kotlin slide code. Ciacola dynamically discovers any `*.deck.json` placed in `deck/src/desktopMain/resources/decks/` (or `-Dciacola.decks.dir`), reloads edits live via `DeckFileSync`, and provides dedicated headless CLI tasks for linting, PNG rendering, and MP4 export.

## Core References

- Full JSON schema, themes, builds, curves, effects, and specimen catalog: [docs/DECK-SCHEMA.md](../../../docs/DECK-SCHEMA.md)
- Quick effect & specimen cheat sheet: [references/effects-and-specimens.md](references/effects-and-specimens.md)

## End-to-End Agent Workflow

1. **Create or edit `<deckId>.deck.json`**
   - Place tracked decks in `deck/src/desktopMain/resources/decks/<deckId>.deck.json` with optional assets in `deck/src/desktopMain/resources/decks/<deckId>.assets/` (or open any external `.deck.json` + sibling `<stem>.assets/` directory).
   - Use logical stage coordinates `1920x1080` with `>= 28 px` safe-area margin (typically `x: 128`, `w: 1664`).
   - Give every slide a readable `title` and every node a stable unique `id` (e.g. `s01:title`, `s01:body`).
   - Prefer theme color tokens (`"background"`, `"foreground"`, `"accent"`, `"elevatedSurface"`, `"muted"`, `"border"`) and typography roles (`"title"`, `"heading"`, `"subheading"`, `"body"`, `"caption"`, `"code"`) over hardcoded colors.
   - Always set explicit `lineHeight` on custom text sizes (`>= size * 1.15`).
   - Screen recordings: use an animated WebP/GIF `image` node. It autoplays when it has no `playback` builds; with them it holds `posterMs` until `{ "kind": "playback", "beat": N, "action": "play" }`. Other actions: `pause` (optional `atMs`), `seek` (`atMs`, or the trim start), `rate` (`rate`). Clip fields: `loop` (default `false`), `playbackRate`, `startMs`/`endMs`, `posterMs`, `maxPixels` (raise it when lint reports `CLIP_OVER_BUDGET`). See "Animated images" in the schema doc.

2. **Format canonically (when working on tracked `decks/`):**
   ```bash
   ./gradlew :deck:formatDecks
   ```

3. **Lint schema, layout, bounds, and WCAG contrast (`DocumentDeckLintEngine`):**
   ```bash
   ./gradlew :deck:lintDeck -Pciacola.deck=<deckId-or-path>
   # Machine-readable JSON output + fail on warnings (-q suppresses Gradle task headers from stdout):
   ./gradlew -q :deck:lintDeck -Pciacola.deck=<deckId-or-path> -Pciacola.json=true -Pciacola.failOn=warning
   ```
   - Fix all `ERROR` findings (unknown keys, missing assets, out-of-bounds text, low WCAG contrast) and check warnings (`SAFE_AREA_BLEED`, `TINY_STAGE_TEXT`, `TEXT_OVERLAP`, `CODE_NATURAL_SIZE_EXCEEDS_BOX`).

4. **Render deterministic slide PNGs & inspect visually:**
   ```bash
   ./gradlew :deck:renderSlides -Pciacola.deck=<deckId-or-path> -Pciacola.slides=1,3-5 -Pciacola.out=/tmp/deck-stills -Pciacola.size=1280x720
   ```
   - Inspect `/tmp/deck-stills/manifest.json` and open the generated PNG files directly with `read_file` to verify alignment, typography wrapping, spacing, overlap, and dark/light theme readability!
   - Pass `-Pciacola.theme=light` to verify the light theme as well.

5. **Live preview in Ciacola Studio:**
   ```bash
   ./gradlew :deck:run -Pciacola.deck=<deckId>
   ```
   - Edits saved to `<deckId>.deck.json` auto-reload live via `DeckFileSync` (with an actionable error banner if invalid JSON is written mid-edit).

6. **Export stage-ready MP4 video (when requested):**
   ```bash
   ./gradlew :deck:exportVideo -Pciacola.deck=<deckId-or-path> -Pciacola.out=/tmp/deck-video -Pciacola.fps=60 -Pciacola.size=1920x1080
   ```

## Handling `Ctrl+Click` Review Comments

When a user sends or copies a `Ctrl+Click` review prompt from Ciacola:
1. Read the `Deck file` (`.deck.json`), `slideId`, `beatIndex`, `nodeId`, and `Annotated slide PNG` path from the prompt.
2. Inspect the annotated PNG via `read_file` to see the exact pin location and highlighted element.
3. Edit the target node/build/slide directly in `<deckId>.deck.json` (remembering `beats[0]` is base state and `beats[k]` changes use `builds` with `"kind": "change"`, `"enter"`, `"exit"`, `"emphasis"`, or `"playback"` for animated images).
4. Run `./gradlew :deck:lintDeck -Pciacola.deck=<deckId>` and `./gradlew :deck:renderSlides -Pciacola.deck=<deckId> -Pciacola.slides=<N>` to verify the fix visually.
5. Close each comment with `./gradlew :deck:reviewResolve -Pciacola.deck=<deckId> -Pciacola.comment=<commentId> -Pciacola.resolution="<what you changed>"`. This takes the queue lock and turns the live viewer's pin green. Never edit `.storyboard/.../review-comments.json` by hand: the deck writes that file too, and an unlocked write can erase a comment queued at the same moment. Pass `-Pciacola.reviewStatus=queued` instead to hand a comment back if you cannot act on it.

### Waiting for comments instead of being pushed to

`./gradlew :deck:reviewWait -Pciacola.deck=<deckId> -Pciacola.timeout=30m` blocks until the author queues a comment, prints it and records it taken, so the notes land in the conversation that called it. It exits non-zero on timeout — run it again to keep waiting. `./gradlew :deck:reviewExport -Pciacola.deck=<deckId> -Pciacola.json=true` prints what is queued without taking it (add `-Pciacola.includeTaken=true` to see what other agents hold).

## Minimal Starter Template

```json
{
  "format": 1,
  "id": "my-talk",
  "title": "My Talk",
  "stage": { "width": 1920, "height": 1080 },
  "defaultTheme": "dark",
  "themes": {
    "dark": {
      "preset": "stageDark",
      "colors": {
        "background": "#0B0D12",
        "foreground": "#F4F1EA",
        "muted": "#A9B0C0",
        "accent": "#FF6B5E",
        "surface": "#171B24",
        "elevatedSurface": "#202634",
        "border": "#2B3345"
      }
    }
  },
  "slides": [
    {
      "id": "s01",
      "title": "Title Slide",
      "beats": [
        { "notes": "Open with the core thesis." },
        { "notes": "Bring in the subtitle." }
      ],
      "nodes": [
        {
          "id": "s01:title",
          "frame": { "x": 128, "y": 340, "w": 1664, "h": 180 },
          "text": { "value": "Declarative Presentations", "style": "title" }
        },
        {
          "id": "s01:subtitle",
          "frame": { "x": 128, "y": 540, "w": 1400, "h": 100 },
          "text": { "value": "Built with Ciacola", "style": "subheading", "color": "muted" },
          "builds": [
            { "kind": "enter", "beat": 1, "effect": "fadeMove", "direction": "up", "durationMs": 450 }
          ]
        }
      ],
      "transition": { "type": "magicMove", "durationMs": 550 }
    }
  ]
}
```
