package dev.bnorm.storyboard.text.highlight

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage

enum class Language {
    Kotlin,
}

fun String.highlight(
    codeStyle: CodeStyle,
    language: Language,
    scope: CodeScope = CodeScope.File,
    identifierStyle: (String) -> SpanStyle? = { null },
): AnnotatedString {
    when (language) {
        Language.Kotlin -> return highlightKotlin(this, codeStyle, scope, identifierStyle)
    }
}

/**
 * Highlights [this] string using [codeStyle] and the `:syntax-highlight` lexer for [language].
 * Falls back to [CodeStyle.simple] when no lexer is registered for [language].
 */
fun String.highlight(
    codeStyle: CodeStyle,
    language: SyntaxLanguage,
): AnnotatedString = buildAnnotatedString {
    withStyle(codeStyle.simple) { append(this@highlight) }
    val tokens = SyntaxHighlighter.lexerOrNull(language)?.lex(this@highlight) ?: return@buildAnnotatedString
    for (token in tokens) {
        val spanStyle = when (token.kind) {
            SyntaxTokenKind.Plain,
            SyntaxTokenKind.Whitespace,
            -> null
            SyntaxTokenKind.Keyword,
            SyntaxTokenKind.Tag,
            SyntaxTokenKind.Constant,
            -> codeStyle.keyword
            SyntaxTokenKind.String -> codeStyle.string
            SyntaxTokenKind.Comment -> codeStyle.comment
            SyntaxTokenKind.Number -> codeStyle.number
            SyntaxTokenKind.Function -> codeStyle.staticFunctionCall
            SyntaxTokenKind.Type -> codeStyle.typeParameters
            SyntaxTokenKind.Property -> codeStyle.property
            SyntaxTokenKind.Annotation,
            SyntaxTokenKind.Attribute,
            SyntaxTokenKind.Preprocessor,
            -> codeStyle.annotation
            SyntaxTokenKind.Operator,
            SyntaxTokenKind.Punctuation,
            -> codeStyle.punctuation
            SyntaxTokenKind.Invalid -> codeStyle.simple
        }
        if (spanStyle != null && spanStyle != codeStyle.simple && token.length > 0) {
            addStyle(spanStyle, token.startIndex, token.endIndex)
        }
    }
}
