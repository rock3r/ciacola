package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for C (C11/C23).
 */
object CSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.C

    override fun lex(source: String): List<SyntaxToken> =
        lexCFamily(source, C_DIALECT)
}

private val C_DIALECT = CFamilyDialect(
    isCpp = false,
    keywords = setOf(
        "alignas", "alignof", "auto", "break", "case", "const",
        "constexpr", "continue", "default", "do", "else", "enum",
        "extern", "for", "goto", "if", "inline", "register",
        "restrict", "return", "sizeof", "static", "static_assert",
        "struct", "switch", "thread_local", "typedef", "typeof",
        "typeof_unqual", "union", "volatile", "while",
        "_Alignas", "_Alignof", "_Atomic", "_Generic", "_Noreturn",
        "_Static_assert", "_Thread_local",
    ),
    constants = setOf("true", "false", "NULL", "nullptr"),
    types = setOf(
        "void", "bool", "_Bool", "char", "char8_t", "char16_t", "char32_t", "wchar_t",
        "short", "int", "long", "signed", "unsigned", "float", "double",
        "_Complex", "_Imaginary", "_BitInt", "_Decimal32", "_Decimal64", "_Decimal128",
        "size_t", "ssize_t", "ptrdiff_t", "intptr_t", "uintptr_t",
        "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "FILE",
    ),
    typeIntroducers = setOf("struct", "enum", "union"),
)
