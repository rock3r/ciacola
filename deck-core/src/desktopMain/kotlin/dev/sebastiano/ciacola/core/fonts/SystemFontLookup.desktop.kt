package dev.sebastiano.ciacola.core.fonts

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import java.io.File
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant

/**
 * Result of matching a host OS system font family (`system = true`).
 * System families are never character-safe and are always accompanied by an acyclic bundled fallback.
 */
data class SystemFontMatch(
    val matchedFamilyName: String,
    val fontFamily: FontFamily,
    val faces: List<FontFaceKey>,
    val sourceFiles: List<File> = emptyList(),
)

/**
 * Abstraction for discovering installed OS system fonts, allowing deterministic testing without
 * depending on what commercial fonts happen to be installed on the host machine.
 */
fun interface SystemFontLookup {
    fun findSystemFamily(familyName: String): SystemFontMatch?
}

/**
 * macOS / desktop system font lookup.
 *
 * Queries Skia's [FontMgr.default] first (verifying an exact or normalized family-name match so
 * CoreText's default Helvetica/SF fallback is never mistaken for a hit) and also scans standard
 * macOS font directories (`/System/Library/Fonts`, `/System/Library/Fonts/Supplemental`,
 * `/Library/Fonts`, `~/Library/Fonts`) for directly installed font files.
 */
class MacOsSystemFontLookup(
    private val searchDirectories: List<File> = defaultMacOsFontDirectories(),
) : SystemFontLookup {

    override fun findSystemFamily(familyName: String): SystemFontMatch? {
        if (familyName.isBlank()) return null
        val targetNorm = normalizeFamilyName(familyName)

        matchViaSkia(familyName, targetNorm)?.let { return it }
        return matchViaDirectories(familyName, targetNorm)
    }

    @OptIn(ExperimentalTextApi::class)
    private fun matchViaSkia(requestedName: String, targetNorm: String): SystemFontMatch? {
        val fontMgr = FontMgr.default
        val candidates = linkedSetOf(requestedName)
        // Also scan registered Skia family names for normalized matches (e.g. "Novecento sans" vs "Novecentosans").
        val count = fontMgr.familiesCount
        for (i in 0 until count) {
            val registered = fontMgr.getFamilyName(i)
            if (normalizeFamilyName(registered) == targetNorm) {
                candidates += registered
            }
        }

        for (candidate in candidates) {
            val styleSet = fontMgr.matchFamily(candidate)
            try {
                val styleCount = styleSet.count()
                if (styleCount <= 0) continue

                val faceKeys = linkedSetOf<FontFaceKey>()
                var confirmedFamilyName: String? = null

                for (i in 0 until styleCount) {
                    val typeface = styleSet.getTypeface(i) ?: continue
                    try {
                        val tfFamily = typeface.familyName
                        val tfMatches = tfFamily.equals(requestedName, ignoreCase = true) ||
                            normalizeFamilyName(tfFamily) == targetNorm ||
                            typeface.familyNames.any { fn ->
                                fn.name.equals(requestedName, ignoreCase = true) ||
                                    normalizeFamilyName(fn.name) == targetNorm
                            }
                        if (!tfMatches) continue
                        if (confirmedFamilyName == null) {
                            confirmedFamilyName = tfFamily
                        }
                        val skiaStyle = typeface.fontStyle
                        val weight = skiaStyle.weight.value.coerceIn(1, 1000)
                        val italic = skiaStyle.slant != FontSlant.UPRIGHT
                        faceKeys += FontFaceKey(weight = weight, italic = italic)
                    } finally {
                        typeface.close()
                    }
                }

                if (confirmedFamilyName != null && faceKeys.isNotEmpty()) {
                    val sortedFaces = faceKeys.sortedWith(compareBy<FontFaceKey> { it.italic }.thenBy { it.weight })
                    val composeFonts: List<Font> = sortedFaces.map { key ->
                        SystemFont(
                            identity = confirmedFamilyName,
                            weight = FontWeight(key.weight),
                            style = if (key.italic) FontStyle.Italic else FontStyle.Normal,
                        )
                    }
                    return SystemFontMatch(
                        matchedFamilyName = confirmedFamilyName,
                        fontFamily = FontFamily(composeFonts),
                        faces = sortedFaces,
                    )
                }
            } finally {
                styleSet.close()
            }
        }
        return null
    }

    private fun matchViaDirectories(requestedName: String, targetNorm: String): SystemFontMatch? {
        val matchedFiles = mutableListOf<Pair<File, FontFaceKey>>()
        var confirmedName: String? = null
        val fontMgr = FontMgr.default

        for (dir in searchDirectories) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val ext = file.extension.lowercase()
                if (ext != "ttf" && ext != "otf") continue
                val baseNorm = normalizeFamilyName(file.nameWithoutExtension)
                if (!baseNorm.startsWith(targetNorm)) continue

                val typeface = runCatching { fontMgr.makeFromFile(file.absolutePath, 0) }.getOrNull() ?: continue
                try {
                    val tfFamily = typeface.familyName
                    val tfMatches = tfFamily.equals(requestedName, ignoreCase = true) ||
                        normalizeFamilyName(tfFamily) == targetNorm ||
                        typeface.familyNames.any { normalizeFamilyName(it.name) == targetNorm }
                    if (!tfMatches) continue
                    if (confirmedName == null) confirmedName = tfFamily
                    val skiaStyle = typeface.fontStyle
                    val weight = skiaStyle.weight.value.coerceIn(1, 1000)
                    val italic = skiaStyle.slant != FontSlant.UPRIGHT
                    matchedFiles += file to FontFaceKey(weight = weight, italic = italic)
                } finally {
                    typeface.close()
                }
            }
        }

        if (confirmedName == null || matchedFiles.isEmpty()) return null
        val distinctFaces = linkedMapOf<FontFaceKey, File>()
        for ((file, key) in matchedFiles.sortedWith(compareBy<Pair<File, FontFaceKey>> { it.second.italic }.thenBy { it.second.weight })) {
            distinctFaces.putIfAbsent(key, file)
        }
        val composeFonts = distinctFaces.map { (key, file) ->
            androidx.compose.ui.text.platform.Font(
                file = file,
                weight = FontWeight(key.weight),
                style = if (key.italic) FontStyle.Italic else FontStyle.Normal,
            )
        }
        return SystemFontMatch(
            matchedFamilyName = confirmedName,
            fontFamily = FontFamily(composeFonts),
            faces = distinctFaces.keys.toList(),
            sourceFiles = distinctFaces.values.toList(),
        )
    }

    companion object {
        fun defaultMacOsFontDirectories(): List<File> {
            val home = System.getProperty("user.home").orEmpty()
            return buildList {
                add(File("/System/Library/Fonts"))
                add(File("/System/Library/Fonts/Supplemental"))
                add(File("/Library/Fonts"))
                if (home.isNotBlank()) {
                    add(File(home, "Library/Fonts"))
                }
            }
        }

        internal fun normalizeFamilyName(name: String): String =
            name.lowercase().filter { it.isLetterOrDigit() }
    }
}
