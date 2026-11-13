package dev.bnorm.storyboard.easel

import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val REPOSITORY_ROOT_MARKERS = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "gradlew",
    "gradlew.bat",
)

/**
 * Optional override for the directory holding persisted `.storyboard/` JSON state files.
 * When `null` (the default), [resolveDefaultStoryboardDir] anchors `.storyboard/` at the
 * repository root discovered from `user.dir`.
 */
val LocalStoryboardDir = staticCompositionLocalOf<Path?> { null }

/**
 * Resolves the workspace/repository root starting from [startDir] (defaulting to JVM `user.dir`)
 * by walking ancestor directories for a Gradle root marker (`settings.gradle.kts`,
 * `settings.gradle`, `gradlew`, or `gradlew.bat`).
 *
 * When Ciacola is launched from a Gradle subproject directory such as `<repo>/deck` (the default
 * working directory of `./gradlew :deck:run`), this anchors `.storyboard/` state files and review
 * agent commands at `<repo>` so CLI runs and IDE runs share one directory. Falls back to
 * normalized [startDir] when launched outside a Gradle checkout.
 */
fun resolveStoryboardWorkspaceRoot(
    startDir: Path = Paths.get(System.getProperty("user.dir", ".")),
): Path {
    val normalized = startDir.toAbsolutePath().normalize()
    return generateSequence(normalized) { it.parent }
        .firstOrNull { candidate ->
            REPOSITORY_ROOT_MARKERS.any { marker -> Files.exists(candidate.resolve(marker)) }
        }
        ?: normalized
}

fun resolveDefaultStoryboardDir(
    startDir: Path = Paths.get(System.getProperty("user.dir", ".")),
): Path = resolveStoryboardWorkspaceRoot(startDir).resolve(".storyboard")
