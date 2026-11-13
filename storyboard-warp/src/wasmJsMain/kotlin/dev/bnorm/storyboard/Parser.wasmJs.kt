@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.bnorm.storyboard

import androidx.collection.mutableScatterMapOf
import dev.bnorm.storyboard.ShikiCore.createHighlighterCoreSync
import dev.bnorm.storyboard.ShikiEngine.createJavaScriptRegexEngine

// Be very careful to **only** use APIs from `shiki-core`.

internal val HIGHLIGHTERS = mutableScatterMapOf<Language, HighlighterCore>()

// Use the browser's Regex engine.
internal val ENGINE = createJavaScriptRegexEngine()

actual fun parse(code: String, language: Language): List<Token> {
    return when (language) {
        is Custom -> language.parser.parse(code)
        else -> {
            val name = requireNotNull(LANGUAGE_NAMES[language]) {
                "Unable to find name for $language. Did you register it?"
            }
            val options = codeToTokenOptions(name = name.toJsString())
            val highlighterCore = getOrCreateHighlighter(language)
            val result = highlighterCore.codeToTokens(code = code.toJsString(), options = options)
            val themedTokens: JsArray<JsArray<ThemedToken>> = result.tokens
            val tokens = mutableListOf<Token>()
            for (i in 0 until themedTokens.length) {
                val row = themedTokens[i]!!
                if (row.length <= 0) {
                    // ShikiJs does not emit tokens for pure whitespace or empty lines.
                    // To preserve compat and to render the correct code in slides we create a synthetic token.
                    // This is what T4ME does.
                    tokens += Token(
                        content = "",
                        scope = language.scopeName(),
                        depth = 1,
                        lineNumber = i,
                        startIndex = 0,
                        endIndex = 0,
                        language = language,
                        allScopes = listOf(language.scopeName())
                    )
                } else {
                    for (j in 0 until row.length) {
                        val themedToken = row[j]!!
                        val token = themedToken.asToken(language, lineNumber = i)
                        tokens += token
                    }
                }
            }
            tokens.splitIntoLexicalTokens()
        }
    }
}

private fun ThemedToken.asToken(language: Language, lineNumber: Int): Token {
    val allScopes = mutableSetOf<String>()
    val explanation = explanation
    if (explanation != null) {
        for (i in 0 until explanation.length) {
            val scopes = explanation[i]!!.scopes
            for (j in 0 until scopes.length) {
                val scope = scopes[j]
                if (scope != null) {
                    allScopes += scope.scopeName.toString()
                }
            }
        }
    }
    val token = Token(
        content = content.toString(),
        scope = allScopes.last(),
        depth = allScopes.size,
        language = language,
        allScopes = allScopes.toList(),
        lineNumber = lineNumber,
        startIndex = offset.toInt(),
        // We don't really use endIndex for anything. So it's okay.
        endIndex = offset.toInt() + content.toString().length,
    )
    return token
}

// Helpers
private fun highlighterOptions(
    grammar: JsAny,
    theme: JsAny = THEME,
    engine: RegexEngine = ENGINE
): HighlighterCoreOptions = js("({ langs: [grammar], themes: [theme], engine: engine })")

private fun codeToTokenOptions(
    name: JsString,
    theme: JsString = THEME_NAME.toJsString()
): CodeToTokensOptions = js("({ lang: name, theme: theme, includeExplanation: 'scopeName' })")

internal fun getOrCreateHighlighter(language: Language): HighlighterCore {
    return HIGHLIGHTERS.getOrPut(language) {
        val grammar = requireNotNull(LANGUAGE_REGISTRY[language]) {
            "Unable to find language $language. Did you register it?"
        }
        val options = highlighterOptions(grammar)
        createHighlighterCoreSync(options = options)
    }
}
