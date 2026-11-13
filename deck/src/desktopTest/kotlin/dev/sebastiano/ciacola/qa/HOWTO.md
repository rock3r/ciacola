# Ciacola QA Spectre Harness — Explorer HOWTO

Package: `dev.sebastiano.ciacola.qa` (`QaStudio.kt`, `QaEvidence.kt`).

All `*SpectreTest` suites run on JetBrains Runtime 25 with `apple.awt.UIElement=true`, `window.isAutoRequestFocus = false`, and Spectre's synthetic AWT driver (`RobotDriver.synthetic()`), so running tests **never steals focus or pops windows in front of the active app**, and captures **Ciacola window surfaces only** (`SkiaLayer.screenshot()`).

## 1. Writing a scenario (`*SpectreTest.kt`)

Name the class `*SpectreTest` (only `*SpectreTest` runs in `:deck:spectreTest`, and it is excluded from `:deck:desktopTest`).

```kotlin
package dev.sebastiano.ciacola.qa

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule

class MyTimelineExplorerSpectreTest {

    @get:Rule
    val qa = QaStudioRule() // manages synthetic ComposeAutomator + automatic window teardown

    @Test
    fun scrubAndInspect(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.StageDark, // or DeckThemePreset.KeynoteLight
            windowSize = DpSize(1360.dp, 900.dp),
            timelineDocked = false,             // or true, or timelineDetached = true
            worker = "qa-explorer-1",           // defaults to env CIACOLA_QA_WORKER
        )
        with(studio) {
            val canvas = openTimeline()         // or val detached = openDetachedTimeline()
            restOnBeat(sceneIndex = 1, frameIndex = 0)

            // Window-only PNG screenshot -> $QA/evidence/<worker>/...
            val shot = saveScreenshot("F-qa-explorer-1-001-before-scrub.png")

            // Window-only MP4 (H.264 yuv420p) + GIF (<= 960 px) while block runs:
            val rec = record(name = "F-qa-explorer-1-001-scrub", fps = 20) {
                val y = rulerPoint(canvas, 0).y
                automator.swipe(
                    startX = rulerPoint(canvas, 200).x, startY = y,
                    endX = rulerPoint(canvas, 2_400).x, endY = y,
                    steps = 24, duration = 600.milliseconds,
                )
                settle()
            }

            // Append schema-validated JSONL finding -> $QA/findings/<worker>.jsonl
            finding(
                id = "F-qa-explorer-1-001", // optional; auto-increments F-<worker>-NNN if omitted
                title = "Short, concrete defect title",
                area = QaArea.TimelineScrub,
                type = QaFindingType.Bug,
                severity = QaSeverity.Major,
                steps = listOf("Open docked timeline (F5)", "Drag playhead from 200 ms to 2400 ms"),
                expected = "Stage updates continuously",
                actual = "Observed behaviour...",
                evidence = listOf(evidencePath(shot), rec.gifEvidencePath, rec.mp4EvidencePath),
                repro = QaRepro.Always,
                confidence = QaConfidence.High,
                notes = "Root-cause pointer (File.kt:123)",
            )
        }
    }
}
```

## 2. Key `QaStudio` handles & helpers

- **State handles**: `window` (Story `ComposeWindow`), `detachedTimelineWindow`, `reviewPanelWindow`, `controller` (`TimelineStudioController`), `overridesStore` (`TimelineOverridesStore`), `review` (`ReviewStageCoordinator`), `reviewStore` (`ReviewCommentStore`), `animatic`, `loopClock`, `workspaceRoot` (isolated temp `.storyboard/`).
- **UI & sync**: `onUi { ... }`, `settle()`, `waitForWindowVisualIdle()`, `setThemePreset(DeckThemePreset.KeynoteLight)`, `openTimeline()`, `openDetachedTimeline()`, `closeDetachedTimeline()`, `openReviewPanel()`, `restOnBeat(scene, frame)`, `stageText("...")`, `rulerPoint(canvas, timeMs)`.
  - **Important**: under `apple.awt.UIElement=true` + synthetic input, `ScreenCaptureKit` ignores accessory windows, so **never** call `automator.waitForVisualIdle()` or `automator.screenshot(...)` — always use `settle()` / `waitForWindowVisualIdle()` and `screenshot()` / `saveScreenshot()`.
- **Synthetic AWT gestures** (no OS mouse/keyboard contention):
  - `awtWheel(point, KeyEvent.VK_ALT, -1)` (modifier + wheel)
  - `awtDrag(from, to, InputEvent.BUTTON2_DOWN_MASK)` (middle-drag, or pass `modifierKeys = listOf(KeyEvent.VK_CONTROL)` for Ctrl+drag review marquee)
  - `awtClick(point, modifierKeys = listOf(KeyEvent.VK_CONTROL))`
  - `withHeldKey(KeyEvent.VK_SPACE) { awtDrag(from, to) }`
- **Capturing secondary windows**: pass `targetWindow = detachedWin` to `screenshot(detachedWin)`, `saveScreenshot("...", targetWindow = detachedWin)`, or `record("...", targetWindow = detachedWin) { ... }`.

## 3. Running your scenario

Always run through `./gradlew`:

```bash
CIACOLA_QA_WORKER=qa-explorer-1 ./gradlew :deck:spectreTest --tests '*MyTimelineExplorerSpectreTest*'
```

## 4. Where evidence and findings land

`$QA` defaults to `build/qa` (override with `CIACOLA_QA_ROOT` or `-Dciacola.qa.root=...`):

- **Evidence** (`saveScreenshot`, `record`): `$QA/evidence/<worker>/<name>.{png,mp4,gif}`
- **Findings** (`finding`): `$QA/findings/<worker>.jsonl` (evidence entries are automatically stored relative to `$QA`, e.g. `evidence/<worker>/F-...gif`)
