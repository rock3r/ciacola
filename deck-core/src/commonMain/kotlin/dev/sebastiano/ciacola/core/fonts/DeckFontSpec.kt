package dev.sebastiano.ciacola.core.fonts

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Font entry declared in a deck document's top-level `fonts` array.
 *
 * Examples:
 * - Static bundled face: `DeckFontDeclaration(family = "Fredoka", src = "fonts/Fredoka-Bold.ttf", weight = 700)`
 * - Variable bundled face: `DeckFontDeclaration(family = "Hanken Grotesk", src = "fonts/HankenGrotesk-VariableFont_wght.ttf", variable = true)`
 * - Monochrome emoji fallback: `DeckFontDeclaration(family = "Noto Emoji", src = "fonts/NotoEmoji-VariableFont_wght.ttf", variable = true, role = "emoji")`
 * - System font with bundled fallback: `DeckFontDeclaration(family = "Oscine", system = true, fallback = "Plus Jakarta Sans")`
 */
@Immutable
@Serializable
data class DeckFontDeclaration(
    val family: String,
    val src: String? = null,
    val weight: Int? = null,
    val italic: Boolean = false,
    val variable: Boolean = false,
    val system: Boolean = false,
    val fallback: String? = null,
    val role: String? = null,
) {
    val isBundled: Boolean
        get() = !system && !src.isNullOrBlank()

    companion object {
        const val ROLE_EMOJI: String = "emoji"
    }
}

enum class FontValidationSeverity {
    Error,
    Warning,
}

@Immutable
data class FontValidationIssue(
    val family: String,
    val severity: FontValidationSeverity,
    val code: String,
    val message: String,
)

/**
 * Validates a deck's `fonts` declarations, including checking that every `system` family specifies
 * a fallback whose chain is acyclic and terminates in a bundled family.
 */
fun validateFontDeclarations(declarations: List<DeckFontDeclaration>): List<FontValidationIssue> {
    val issues = mutableListOf<FontValidationIssue>()
    val bundledFamilies = mutableSetOf<String>()
    val systemByFamily = linkedMapOf<String, DeckFontDeclaration>()
    val seenStaticFaces = mutableSetOf<Triple<String, Int, Boolean>>()
    val seenVariableStyles = mutableSetOf<Pair<String, Boolean>>()
    val emojiFamilies = mutableSetOf<String>()

    for (decl in declarations) {
        if (decl.family.isBlank()) {
            issues += FontValidationIssue(
                family = decl.family,
                severity = FontValidationSeverity.Error,
                code = "FONT_BLANK_FAMILY",
                message = "Font declaration must have a non-blank family name.",
            )
            continue
        }

        if (decl.weight != null && decl.weight !in 1..1000) {
            issues += FontValidationIssue(
                family = decl.family,
                severity = FontValidationSeverity.Error,
                code = "FONT_INVALID_WEIGHT",
                message = "Font weight ${decl.weight} for '${decl.family}' must be in 1..1000.",
            )
        }

        if (decl.role != null && decl.role != DeckFontDeclaration.ROLE_EMOJI) {
            issues += FontValidationIssue(
                family = decl.family,
                severity = FontValidationSeverity.Warning,
                code = "FONT_UNKNOWN_ROLE",
                message = "Unknown font role '${decl.role}' on '${decl.family}'.",
            )
        }

        if (decl.role == DeckFontDeclaration.ROLE_EMOJI) {
            emojiFamilies += decl.family
        }

        if (decl.system) {
            if (!decl.src.isNullOrBlank()) {
                issues += FontValidationIssue(
                    family = decl.family,
                    severity = FontValidationSeverity.Error,
                    code = "FONT_SYSTEM_WITH_SRC",
                    message = "System font '${decl.family}' must not also declare 'src'.",
                )
            }
            if (decl.fallback.isNullOrBlank()) {
                issues += FontValidationIssue(
                    family = decl.family,
                    severity = FontValidationSeverity.Error,
                    code = "FONT_SYSTEM_MISSING_FALLBACK",
                    message = "System font '${decl.family}' must declare a bundled 'fallback' family.",
                )
            }
            val previous = systemByFamily.put(decl.family, decl)
            if (previous != null) {
                issues += FontValidationIssue(
                    family = decl.family,
                    severity = FontValidationSeverity.Warning,
                    code = "FONT_DUPLICATE_SYSTEM",
                    message = "Duplicate system font declaration for '${decl.family}'.",
                )
            }
        } else {
            if (decl.src.isNullOrBlank()) {
                issues += FontValidationIssue(
                    family = decl.family,
                    severity = FontValidationSeverity.Error,
                    code = "FONT_MISSING_SRC",
                    message = "Non-system font '${decl.family}' must declare 'src'.",
                )
            } else {
                bundledFamilies += decl.family
                if (decl.variable) {
                    if (!seenVariableStyles.add(decl.family to decl.italic)) {
                        issues += FontValidationIssue(
                            family = decl.family,
                            severity = FontValidationSeverity.Warning,
                            code = "FONT_DUPLICATE_VARIABLE",
                            message = "Duplicate variable declaration for '${decl.family}' (italic=${decl.italic}).",
                        )
                    }
                } else if (decl.weight != null) {
                    val key = Triple(decl.family, decl.weight, decl.italic)
                    if (!seenStaticFaces.add(key)) {
                        issues += FontValidationIssue(
                            family = decl.family,
                            severity = FontValidationSeverity.Warning,
                            code = "FONT_DUPLICATE_FACE",
                            message = "Duplicate face for '${decl.family}' weight=${decl.weight} italic=${decl.italic}.",
                        )
                    }
                }
            }
        }
    }

    if (emojiFamilies.size > 1) {
        issues += FontValidationIssue(
            family = emojiFamilies.first(),
            severity = FontValidationSeverity.Warning,
            code = "FONT_MULTIPLE_EMOJI_ROLES",
            message = "Multiple families declare role='emoji': ${emojiFamilies.joinToString(", ")}.",
        )
    }

    // Validate fallback chains: every fallback must be acyclic and terminate in a bundled family.
    val allDeclaredFallbacks = declarations.filter { !it.fallback.isNullOrBlank() }
    val fallbackMap = linkedMapOf<String, String>()
    for (decl in allDeclaredFallbacks) {
        fallbackMap[decl.family] = decl.fallback!!
    }

    for ((startFamily, firstFallback) in fallbackMap) {
        val visited = linkedSetOf(startFamily)
        var current: String? = firstFallback
        var terminatedInBundled = false
        while (current != null) {
            if (current in visited) {
                issues += FontValidationIssue(
                    family = startFamily,
                    severity = FontValidationSeverity.Error,
                    code = "FONT_FALLBACK_CYCLE",
                    message = "Cyclic font fallback chain for '$startFamily': " +
                        "${visited.joinToString(" -> ")} -> $current.",
                )
                break
            }
            visited += current
            if (current in bundledFamilies) {
                terminatedInBundled = true
                break
            }
            val next = fallbackMap[current]
            if (next == null) {
                issues += FontValidationIssue(
                    family = startFamily,
                    severity = FontValidationSeverity.Error,
                    code = "FONT_FALLBACK_NOT_BUNDLED",
                    message = "Fallback chain for '$startFamily' ends at '$current', which is not a bundled family.",
                )
                break
            }
            current = next
        }
        if (terminatedInBundled && startFamily in bundledFamilies && startFamily in systemByFamily) {
            issues += FontValidationIssue(
                family = startFamily,
                severity = FontValidationSeverity.Error,
                code = "FONT_MIXED_SYSTEM_AND_BUNDLED",
                message = "Family '$startFamily' cannot be declared both as bundled and as system.",
            )
        }
    }

    for (family in bundledFamilies) {
        if (family in systemByFamily && family !in fallbackMap) {
            issues += FontValidationIssue(
                family = family,
                severity = FontValidationSeverity.Error,
                code = "FONT_MIXED_SYSTEM_AND_BUNDLED",
                message = "Family '$family' cannot be declared both as bundled and as system.",
            )
        }
    }

    return issues
}

/**
 * Canonical catalog of all open-source fonts bundled in `deck/src/desktopMain/resources/fonts/`
 * plus standard macOS system-font declarations with certified bundled OFL fallbacks.
 */
object BundledFontCatalog {
    val Inter: List<DeckFontDeclaration> = listOf(
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-Thin.ttf", weight = 100),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-ExtraLight.ttf", weight = 200),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-Light.ttf", weight = 300),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-Regular.ttf", weight = 400),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-Medium.ttf", weight = 500),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-SemiBold.ttf", weight = 600),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-Bold.ttf", weight = 700),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-ExtraBold.ttf", weight = 800),
        DeckFontDeclaration(family = "Inter", src = "fonts/Inter-Black.ttf", weight = 900),
    )

    val JetBrainsMono: List<DeckFontDeclaration> = listOf(
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-Thin.ttf", weight = 100),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-ThinItalic.ttf", weight = 100, italic = true),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-ExtraLight.ttf", weight = 200),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-ExtraLightItalic.ttf", weight = 200, italic = true),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-Light.ttf", weight = 300),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-LightItalic.ttf", weight = 300, italic = true),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-Regular.ttf", weight = 400),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-Italic.ttf", weight = 400, italic = true),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-Medium.ttf", weight = 500),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-MediumItalic.ttf", weight = 500, italic = true),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-SemiBold.ttf", weight = 600),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-SemiBoldItalic.ttf", weight = 600, italic = true),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-Bold.ttf", weight = 700),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-BoldItalic.ttf", weight = 700, italic = true),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-ExtraBold.ttf", weight = 800),
        DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-ExtraBoldItalic.ttf", weight = 800, italic = true),
    )

    val HankenGrotesk: List<DeckFontDeclaration> = listOf(
        DeckFontDeclaration(
            family = "Hanken Grotesk",
            src = "fonts/HankenGrotesk-VariableFont_wght.ttf",
            variable = true,
        ),
        DeckFontDeclaration(
            family = "Hanken Grotesk",
            src = "fonts/HankenGrotesk-Italic-VariableFont_wght.ttf",
            variable = true,
            italic = true,
        ),
    )

    val NotoEmoji: DeckFontDeclaration = DeckFontDeclaration(
        family = "Noto Emoji",
        src = "fonts/NotoEmoji-VariableFont_wght.ttf",
        variable = true,
        role = DeckFontDeclaration.ROLE_EMOJI,
    )

    val Fredoka: DeckFontDeclaration = DeckFontDeclaration(
        family = "Fredoka",
        src = "fonts/Fredoka-VariableFont_wdth_wght.ttf",
        variable = true,
    )

    val GoogleSans: List<DeckFontDeclaration> = listOf(
        DeckFontDeclaration(
            family = "Google Sans",
            src = "fonts/GoogleSans-VariableFont_GRAD_opsz_wght.ttf",
            variable = true,
        ),
        DeckFontDeclaration(
            family = "Google Sans",
            src = "fonts/GoogleSans-Italic-VariableFont_GRAD_opsz_wght.ttf",
            variable = true,
            italic = true,
        ),
    )

    val Assistant: DeckFontDeclaration = DeckFontDeclaration(
        family = "Assistant",
        src = "fonts/Assistant-VariableFont_wght.ttf",
        variable = true,
    )

    val NanumPenScript: DeckFontDeclaration = DeckFontDeclaration(
        family = "Nanum Pen Script",
        src = "fonts/NanumPenScript-Regular.ttf",
        weight = 400,
    )

    val PlusJakartaSans: List<DeckFontDeclaration> = listOf(
        DeckFontDeclaration(
            family = "Plus Jakarta Sans",
            src = "fonts/PlusJakartaSans-VariableFont_wght.ttf",
            variable = true,
        ),
        DeckFontDeclaration(
            family = "Plus Jakarta Sans",
            src = "fonts/PlusJakartaSans-Italic-VariableFont_wght.ttf",
            variable = true,
            italic = true,
        ),
    )

    val Montserrat: List<DeckFontDeclaration> = listOf(
        DeckFontDeclaration(
            family = "Montserrat",
            src = "fonts/Montserrat-VariableFont_wght.ttf",
            variable = true,
        ),
        DeckFontDeclaration(
            family = "Montserrat",
            src = "fonts/Montserrat-Italic-VariableFont_wght.ttf",
            variable = true,
            italic = true,
        ),
    )

    val Roboto: DeckFontDeclaration = DeckFontDeclaration(
        family = "Roboto",
        src = "fonts/Roboto-VariableFont_wdth_wght.ttf",
        variable = true,
    )

    val StandardSystemFallbacks: List<DeckFontDeclaration> = listOf(
        DeckFontDeclaration(family = "Oscine", system = true, fallback = "Plus Jakarta Sans"),
        DeckFontDeclaration(family = "Novecento sans", system = true, fallback = "Montserrat"),
        DeckFontDeclaration(family = "Google Sans Display", system = true, fallback = "Google Sans"),
        DeckFontDeclaration(family = "Snell Roundhand", system = true, fallback = "Nanum Pen Script"),
        DeckFontDeclaration(family = "Georgia", system = true, fallback = "Inter"),
    )

    val AllBundled: List<DeckFontDeclaration> = buildList {
        addAll(Inter)
        addAll(JetBrainsMono)
        addAll(HankenGrotesk)
        add(NotoEmoji)
        add(Fredoka)
        addAll(GoogleSans)
        add(Assistant)
        add(NanumPenScript)
        addAll(PlusJakartaSans)
        addAll(Montserrat)
        add(Roboto)
    }

    val AllWithSystemFallbacks: List<DeckFontDeclaration> = AllBundled + StandardSystemFallbacks
}
