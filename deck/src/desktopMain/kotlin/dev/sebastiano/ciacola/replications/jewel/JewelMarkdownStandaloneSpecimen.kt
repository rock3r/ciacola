package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private const val JEWEL_S36_INSERT_MARKER = "[[TYPED]]"

private val JEWEL_S36_BASE_MARKDOWN = """
# Jewel: a Compose for Desktop theme

Jewel provides an implementation of the IntelliJ Platform themes that can be used in any Compose for Desktop application.

$JEWEL_S36_INSERT_MARKER

> [!TIP]
> If you want to learn more about Jewel and Compose for Desktop, check out [this talk](https://www.droidcon.com) by Jewel contributors Sebastiano and Chris.

## Getting started

Add the custom repository in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/kpm/public")
        mavenCentral()
    }
}
```

> [!WARNING]
> Writing 3rd party IntelliJ Plugins in Compose for Desktop is currently **not officially supported**. Binary compatibility is not guaranteed across releases.

| Module | Purpose |
|---|---|
| `jewel-int-ui-standalone` | Standalone Compose Desktop theme |
| `jewel-markdown-core` | CommonMark + GFM Compose renderer |
""".trimIndent()

private const val JEWEL_S36_TYPED_ADDITION =
    "Live Markdown preview powered by **CommonMark** + `Jewel`!"

/**
 * Specimen #10 (`jewel.markdownStandaloneSample`, Jewel Talk S36, `periodMs = 14_000`):
 * Jewel Standalone sample window showcasing a live split-pane CommonMark/GFM source editor
 * on the left and live AST-to-Compose Jewel Markdown blocks on the right.
 * Shares [JewelMarkdownSplitWindow] with `njpt.jewelMarkdownSplitDemo`.
 */
@Composable
fun MarkdownStandaloneSampleSpecimen(
    loopMillis: Long = 3_500L,
    periodMs: Long = 14_000L,
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    JewelMarkdownSplitWindow(
        loopMillis = loopMillis,
        periodMs = periodMs,
        presetName = "Load preset ▾",
        baseMarkdown = JEWEL_S36_BASE_MARKDOWN,
        typedAddition = JEWEL_S36_TYPED_ADDITION,
        insertionMarker = JEWEL_S36_INSERT_MARKER,
        interactiveId = "specimen-markdown-standalone",
        isInteractive = isInteractive,
        initialDarkTheme = false,
        modifier = modifier,
    )
}
