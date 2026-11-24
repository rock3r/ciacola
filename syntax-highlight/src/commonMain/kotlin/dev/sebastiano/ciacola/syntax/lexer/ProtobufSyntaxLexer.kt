package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.protobuf.ProtobufLexer
import dev.sebastiano.ciacola.syntax.antlr.protobuf.ProtobufLexer.Tokens as ProtoTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Protocol Buffers (proto2, proto3, and Editions).
 */
internal object ProtobufSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Protobuf

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = ProtobufLexer(antlrCharStream(source))
        val raw = ArrayList<RawProtoToken>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawProtoToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) ProtoTokens.WS else ProtoTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end ->
                val (kind, isHidden) = baseKindFor(type)
                raw += RawProtoToken(type, start, end, kind, isHidden)
            },
        )

        classifyProtobufTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawProtoToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun baseKindFor(type: Int): Pair<SyntaxTokenKind, Boolean> = when (type) {
    ProtoTokens.WS -> SyntaxTokenKind.Whitespace to true
    ProtoTokens.LINE_COMMENT,
    ProtoTokens.BLOCK_COMMENT,
    -> SyntaxTokenKind.Comment to true

    ProtoTokens.STRING,
    ProtoTokens.SINGLE_STRING,
    -> SyntaxTokenKind.String to false

    ProtoTokens.HEX_INT,
    ProtoTokens.FLOAT,
    ProtoTokens.OCT_INT,
    ProtoTokens.INT,
    -> SyntaxTokenKind.Number to false

    ProtoTokens.LBRACE,
    ProtoTokens.RBRACE,
    ProtoTokens.LPAREN,
    ProtoTokens.RPAREN,
    ProtoTokens.LBRACK,
    ProtoTokens.RBRACK,
    ProtoTokens.LANGLE,
    ProtoTokens.RANGLE,
    ProtoTokens.COMMA,
    ProtoTokens.SEMI,
    ProtoTokens.DOT,
    -> SyntaxTokenKind.Punctuation to false

    ProtoTokens.EQ,
    ProtoTokens.COLON,
    ProtoTokens.MINUS,
    ProtoTokens.PLUS,
    ProtoTokens.SLASH,
    ProtoTokens.STAR,
    -> SyntaxTokenKind.Operator to false

    ProtoTokens.IDENT -> SyntaxTokenKind.Plain to false
    else -> SyntaxTokenKind.Invalid to false
}

private val SCALAR_TYPES = setOf(
    "double",
    "float",
    "int32",
    "int64",
    "uint32",
    "uint64",
    "sint32",
    "sint64",
    "fixed32",
    "fixed64",
    "sfixed32",
    "sfixed64",
    "bool",
    "string",
    "bytes",
)

private val CONSTANT_LITERALS = setOf("true", "false", "inf", "nan")

private enum class ProtoScopeKind {
    TOP_LEVEL,
    MESSAGE_BODY,
    ENUM_BODY,
    SERVICE_BODY,
    RPC_BODY,
    ONEOF_BODY,
    EXTEND_BODY,
    GROUP_BODY,
    OPTION_AGGREGATE,
    BRACK_FIELD_OPTS,
    PAREN_CUSTOM_OPT,
    PAREN_RPC_PARAM,
    ANGLE_MAP,
}

private enum class ProtoStmtState {
    START,
    AFTER_VISIBILITY,
    IN_SYNTAX_OR_EDITION,
    IN_PACKAGE,
    IN_IMPORT_MODIFIER,
    IN_IMPORT_PATH,
    AWAITING_DECL_NAME,
    IN_EXTEND_TARGET,
    AWAITING_GROUP_NAME,
    AWAITING_RPC_NAME,
    IN_RPC_HEADER,
    AWAITING_RPC_RETURNS_PAREN,
    IN_RESERVED_OR_EXTENSIONS,
    IN_OPTION_NAME,
    IN_OPTION_VALUE,
    AWAITING_FIELD_TYPE,
    IN_FIELD_TYPE_QUALIFIED,
    AWAITING_FIELD_NAME,
    IN_FIELD_AFTER_NAME,
    IN_ENUM_VALUE,
    REST_OF_STMT,
}

private class ProtoScope(
    val kind: ProtoScopeKind,
    var stmtState: ProtoStmtState = ProtoStmtState.START,
    var pendingBraceScope: ProtoScopeKind? = null,
    var isOptionValueContext: Boolean = false,
    val isAngleDelimited: Boolean = false,
)

private fun classifyProtobufTokens(source: String, raw: List<RawProtoToken>) {
    // Collect indices of non-hidden tokens for O(1) syntactic lookahead.
    val visible = IntArray(raw.size)
    var visibleCount = 0
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) {
            visible[visibleCount++] = idx
        }
    }

    fun slice(visIdx: Int): String {
        val t = raw[visible[visIdx]]
        return source.substring(t.start, t.end)
    }

    fun tokType(visIdx: Int): Int = raw[visible[visIdx]].type

    val scopeStack = ArrayList<ProtoScope>().apply {
        add(ProtoScope(ProtoScopeKind.TOP_LEVEL))
    }

    fun currentScope(): ProtoScope = scopeStack.last()

    var vi = 0
    while (vi < visibleCount) {
        val tok = raw[visible[vi]]
        val type = tok.type
        val text = slice(vi)
        val scope = currentScope()
        val nextType = if (vi + 1 < visibleCount) tokType(vi + 1) else -1
        val nextText = if (vi + 1 < visibleCount) slice(vi + 1) else ""

        // Structural delimiters first.
        when (type) {
            ProtoTokens.LBRACE -> {
                val childKind = when {
                    scope.kind == ProtoScopeKind.OPTION_AGGREGATE -> ProtoScopeKind.OPTION_AGGREGATE
                    scope.stmtState == ProtoStmtState.IN_OPTION_VALUE ||
                        scope.stmtState == ProtoStmtState.IN_FIELD_AFTER_NAME -> ProtoScopeKind.OPTION_AGGREGATE
                    scope.pendingBraceScope != null -> {
                        val pending = scope.pendingBraceScope!!
                        scope.pendingBraceScope = null
                        pending
                    }
                    else -> ProtoScopeKind.MESSAGE_BODY
                }
                scopeStack.add(
                    ProtoScope(
                        kind = childKind,
                        stmtState = ProtoStmtState.START,
                        isOptionValueContext = childKind == ProtoScopeKind.OPTION_AGGREGATE,
                    ),
                )
                vi++
                continue
            }

            ProtoTokens.RBRACE -> {
                if (scopeStack.size > 1 && !(scope.kind == ProtoScopeKind.OPTION_AGGREGATE && scope.isAngleDelimited)) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                }
                val parent = currentScope()
                if (parent.kind != ProtoScopeKind.OPTION_AGGREGATE &&
                    parent.kind != ProtoScopeKind.BRACK_FIELD_OPTS
                ) {
                    parent.stmtState = ProtoStmtState.START
                }
                vi++
                continue
            }

            ProtoTokens.LBRACK -> {
                if (scope.kind != ProtoScopeKind.OPTION_AGGREGATE) {
                    scopeStack.add(
                        ProtoScope(
                            kind = ProtoScopeKind.BRACK_FIELD_OPTS,
                            stmtState = ProtoStmtState.IN_OPTION_NAME,
                        ),
                    )
                }
                vi++
                continue
            }

            ProtoTokens.RBRACK -> {
                if (scopeStack.size > 1 && scope.kind == ProtoScopeKind.BRACK_FIELD_OPTS) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                }
                vi++
                continue
            }

            ProtoTokens.LPAREN -> {
                val childKind = if (scope.stmtState == ProtoStmtState.IN_RPC_HEADER ||
                    scope.stmtState == ProtoStmtState.AWAITING_RPC_RETURNS_PAREN
                ) {
                    ProtoScopeKind.PAREN_RPC_PARAM
                } else {
                    ProtoScopeKind.PAREN_CUSTOM_OPT
                }
                scopeStack.add(ProtoScope(kind = childKind, stmtState = ProtoStmtState.START))
                vi++
                continue
            }

            ProtoTokens.RPAREN -> {
                val closedKind = scope.kind
                if (scopeStack.size > 1 &&
                    (closedKind == ProtoScopeKind.PAREN_RPC_PARAM || closedKind == ProtoScopeKind.PAREN_CUSTOM_OPT)
                ) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                }
                val parent = currentScope()
                if (closedKind == ProtoScopeKind.PAREN_RPC_PARAM) {
                    if (parent.stmtState == ProtoStmtState.IN_RPC_HEADER) {
                        parent.stmtState = ProtoStmtState.AWAITING_RPC_RETURNS_PAREN
                    } else if (parent.stmtState == ProtoStmtState.AWAITING_RPC_RETURNS_PAREN) {
                        parent.pendingBraceScope = ProtoScopeKind.RPC_BODY
                        parent.stmtState = ProtoStmtState.REST_OF_STMT
                    }
                }
                vi++
                continue
            }

            ProtoTokens.LANGLE -> {
                if (scope.kind == ProtoScopeKind.OPTION_AGGREGATE ||
                    scope.stmtState == ProtoStmtState.IN_OPTION_VALUE ||
                    scope.stmtState == ProtoStmtState.IN_FIELD_AFTER_NAME
                ) {
                    scopeStack.add(
                        ProtoScope(
                            kind = ProtoScopeKind.OPTION_AGGREGATE,
                            stmtState = ProtoStmtState.START,
                            isOptionValueContext = true,
                            isAngleDelimited = true,
                        ),
                    )
                    vi++
                    continue
                } else if (scope.kind != ProtoScopeKind.BRACK_FIELD_OPTS &&
                    (scope.stmtState == ProtoStmtState.AWAITING_FIELD_TYPE || scope.stmtState == ProtoStmtState.START)
                ) {
                    scopeStack.add(
                        ProtoScope(
                            kind = ProtoScopeKind.ANGLE_MAP,
                            stmtState = ProtoStmtState.AWAITING_FIELD_TYPE,
                        ),
                    )
                    vi++
                    continue
                }
            }

            ProtoTokens.RANGLE -> {
                if (scopeStack.size > 1 && scope.kind == ProtoScopeKind.OPTION_AGGREGATE && scope.isAngleDelimited) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                    vi++
                    continue
                } else if (scopeStack.size > 1 && scope.kind == ProtoScopeKind.ANGLE_MAP) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                    currentScope().stmtState = ProtoStmtState.AWAITING_FIELD_NAME
                    vi++
                    continue
                }
            }

            ProtoTokens.SEMI -> {
                if (scope.kind != ProtoScopeKind.OPTION_AGGREGATE) {
                    scope.stmtState = ProtoStmtState.START
                    scope.pendingBraceScope = null
                    scope.isOptionValueContext = false
                }
                vi++
                continue
            }
        }

        // Scope-specific handling for aggregates, field options, map<K,V>, and rpc params.
        when (scope.kind) {
            ProtoScopeKind.OPTION_AGGREGATE -> {
                if (type == ProtoTokens.IDENT) {
                    if (nextType == ProtoTokens.COLON || nextType == ProtoTokens.LBRACE || nextType == ProtoTokens.LANGLE) {
                        tok.kind = SyntaxTokenKind.Property
                    } else if (text in CONSTANT_LITERALS) {
                        tok.kind = SyntaxTokenKind.Constant
                    } else if (looksLikeTypeName(text) || text.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                        tok.kind = SyntaxTokenKind.Constant
                    }
                }
                vi++
                continue
            }

            ProtoScopeKind.BRACK_FIELD_OPTS -> {
                if (type == ProtoTokens.COMMA) {
                    scope.stmtState = ProtoStmtState.IN_OPTION_NAME
                    scope.isOptionValueContext = false
                } else if (type == ProtoTokens.EQ) {
                    scope.stmtState = ProtoStmtState.IN_OPTION_VALUE
                    scope.isOptionValueContext = true
                } else if (type == ProtoTokens.IDENT) {
                    if (scope.stmtState == ProtoStmtState.IN_OPTION_NAME &&
                        nextType != ProtoTokens.DOT &&
                        nextType != ProtoTokens.RPAREN
                    ) {
                        tok.kind = SyntaxTokenKind.Property
                    } else if (scope.stmtState == ProtoStmtState.IN_OPTION_VALUE) {
                        if (text in CONSTANT_LITERALS || text.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                            tok.kind = SyntaxTokenKind.Constant
                        }
                    }
                }
                vi++
                continue
            }

            ProtoScopeKind.PAREN_CUSTOM_OPT -> {
                if (type == ProtoTokens.IDENT && nextType != ProtoTokens.DOT) {
                    tok.kind = SyntaxTokenKind.Property
                }
                vi++
                continue
            }

            ProtoScopeKind.PAREN_RPC_PARAM -> {
                if (type == ProtoTokens.IDENT) {
                    if (scope.stmtState == ProtoStmtState.START &&
                        text == "stream" &&
                        (nextType == ProtoTokens.IDENT || nextType == ProtoTokens.DOT)
                    ) {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.AWAITING_FIELD_TYPE
                    } else if (text in SCALAR_TYPES || (nextType != ProtoTokens.DOT)) {
                        tok.kind = SyntaxTokenKind.Type
                        scope.stmtState = ProtoStmtState.REST_OF_STMT
                    }
                }
                vi++
                continue
            }

            ProtoScopeKind.ANGLE_MAP -> {
                if (type == ProtoTokens.COMMA) {
                    scope.stmtState = ProtoStmtState.AWAITING_FIELD_TYPE
                } else if (type == ProtoTokens.IDENT) {
                    if (text in SCALAR_TYPES || nextType != ProtoTokens.DOT) {
                        tok.kind = SyntaxTokenKind.Type
                    }
                }
                vi++
                continue
            }

            else -> Unit
        }

        if (scope.kind == ProtoScopeKind.ENUM_BODY && scope.stmtState == ProtoStmtState.START) {
            if (type == ProtoTokens.IDENT) {
                when {
                    text == "option" && nextType != ProtoTokens.EQ -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.IN_OPTION_NAME
                    }
                    text == "reserved" && nextType != ProtoTokens.EQ -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.IN_RESERVED_OR_EXTENSIONS
                    }
                    else -> {
                        tok.kind = SyntaxTokenKind.Constant
                        scope.stmtState = ProtoStmtState.IN_ENUM_VALUE
                    }
                }
            }
            vi++
            continue
        }

        when (scope.stmtState) {
            ProtoStmtState.START,
            ProtoStmtState.AFTER_VISIBILITY,
            -> {
                if (type != ProtoTokens.IDENT) {
                    if (type == ProtoTokens.DOT) {
                        scope.stmtState = ProtoStmtState.IN_FIELD_TYPE_QUALIFIED
                    }
                    vi++
                    continue
                }
                val isAtStmtStart = scope.stmtState == ProtoStmtState.START
                when {
                    isAtStmtStart && (text == "syntax" || text == "edition") && nextType == ProtoTokens.EQ -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.IN_SYNTAX_OR_EDITION
                    }
                    isAtStmtStart && text == "package" && (nextType == ProtoTokens.IDENT || nextType == ProtoTokens.DOT) -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.IN_PACKAGE
                    }
                    isAtStmtStart && text == "import" &&
                        (nextType == ProtoTokens.STRING || nextType == ProtoTokens.SINGLE_STRING ||
                            nextText == "weak" || nextText == "public") -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.IN_IMPORT_MODIFIER
                    }
                    isAtStmtStart && text == "option" && nextType != ProtoTokens.EQ -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.IN_OPTION_NAME
                    }
                    isAtStmtStart && (text == "export" || text == "local") &&
                        (nextText == "message" || nextText == "enum" || nextText == "service" || nextText == "extend") -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.AFTER_VISIBILITY
                    }
                    text == "message" && nextType == ProtoTokens.IDENT -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.pendingBraceScope = ProtoScopeKind.MESSAGE_BODY
                        scope.stmtState = ProtoStmtState.AWAITING_DECL_NAME
                    }
                    text == "enum" && nextType == ProtoTokens.IDENT -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.pendingBraceScope = ProtoScopeKind.ENUM_BODY
                        scope.stmtState = ProtoStmtState.AWAITING_DECL_NAME
                    }
                    text == "service" && nextType == ProtoTokens.IDENT -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.pendingBraceScope = ProtoScopeKind.SERVICE_BODY
                        scope.stmtState = ProtoStmtState.AWAITING_DECL_NAME
                    }
                    text == "extend" && (nextType == ProtoTokens.IDENT || nextType == ProtoTokens.DOT) -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.pendingBraceScope = ProtoScopeKind.EXTEND_BODY
                        scope.stmtState = ProtoStmtState.IN_EXTEND_TARGET
                    }
                    isAtStmtStart && text == "oneof" && nextType == ProtoTokens.IDENT -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.pendingBraceScope = ProtoScopeKind.ONEOF_BODY
                        scope.stmtState = ProtoStmtState.REST_OF_STMT
                    }
                    isAtStmtStart && (text == "reserved" || text == "extensions") && nextType != ProtoTokens.EQ -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.IN_RESERVED_OR_EXTENSIONS
                    }
                    isAtStmtStart && scope.kind == ProtoScopeKind.SERVICE_BODY &&
                        text == "rpc" && nextType == ProtoTokens.IDENT -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.AWAITING_RPC_NAME
                    }
                    isAtStmtStart && (text == "repeated" || text == "optional" || text == "required") &&
                        (nextType == ProtoTokens.IDENT || nextType == ProtoTokens.DOT) -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.stmtState = ProtoStmtState.AWAITING_FIELD_TYPE
                    }
                    isAtStmtStart && text == "group" && nextType == ProtoTokens.IDENT &&
                        vi + 2 < visibleCount && tokType(vi + 2) == ProtoTokens.EQ -> {
                        tok.kind = SyntaxTokenKind.Keyword
                        scope.pendingBraceScope = ProtoScopeKind.GROUP_BODY
                        scope.stmtState = ProtoStmtState.AWAITING_GROUP_NAME
                    }
                    text == "map" && nextType == ProtoTokens.LANGLE -> {
                        tok.kind = SyntaxTokenKind.Type
                        scope.stmtState = ProtoStmtState.AWAITING_FIELD_TYPE
                    }
                    text in SCALAR_TYPES -> {
                        tok.kind = SyntaxTokenKind.Type
                        scope.stmtState = ProtoStmtState.AWAITING_FIELD_NAME
                    }
                    nextType == ProtoTokens.DOT -> {
                        scope.stmtState = ProtoStmtState.IN_FIELD_TYPE_QUALIFIED
                    }
                    else -> {
                        tok.kind = SyntaxTokenKind.Type
                        scope.stmtState = ProtoStmtState.AWAITING_FIELD_NAME
                    }
                }
            }

            ProtoStmtState.IN_IMPORT_MODIFIER -> {
                if (type == ProtoTokens.IDENT && (text == "weak" || text == "public")) {
                    tok.kind = SyntaxTokenKind.Keyword
                }
                scope.stmtState = ProtoStmtState.IN_IMPORT_PATH
            }

            ProtoStmtState.AWAITING_DECL_NAME -> {
                if (type == ProtoTokens.IDENT) {
                    tok.kind = SyntaxTokenKind.Type
                }
                scope.stmtState = ProtoStmtState.REST_OF_STMT
            }

            ProtoStmtState.IN_EXTEND_TARGET -> {
                if (type == ProtoTokens.IDENT && nextType != ProtoTokens.DOT) {
                    tok.kind = SyntaxTokenKind.Type
                    scope.stmtState = ProtoStmtState.REST_OF_STMT
                }
            }

            ProtoStmtState.AWAITING_GROUP_NAME -> {
                if (type == ProtoTokens.IDENT) {
                    tok.kind = SyntaxTokenKind.Type
                }
                scope.stmtState = ProtoStmtState.REST_OF_STMT
            }

            ProtoStmtState.AWAITING_RPC_NAME -> {
                if (type == ProtoTokens.IDENT) {
                    tok.kind = SyntaxTokenKind.Function
                }
                scope.stmtState = ProtoStmtState.IN_RPC_HEADER
            }

            ProtoStmtState.AWAITING_RPC_RETURNS_PAREN -> {
                if (type == ProtoTokens.IDENT && text == "returns") {
                    tok.kind = SyntaxTokenKind.Keyword
                }
            }

            ProtoStmtState.IN_RESERVED_OR_EXTENSIONS -> {
                if (type == ProtoTokens.IDENT && (text == "to" || text == "max")) {
                    tok.kind = SyntaxTokenKind.Keyword
                }
            }

            ProtoStmtState.IN_OPTION_NAME -> {
                if (type == ProtoTokens.EQ) {
                    scope.stmtState = ProtoStmtState.IN_OPTION_VALUE
                    scope.isOptionValueContext = true
                } else if (type == ProtoTokens.IDENT && nextType != ProtoTokens.DOT) {
                    tok.kind = SyntaxTokenKind.Property
                }
            }

            ProtoStmtState.IN_OPTION_VALUE,
            ProtoStmtState.IN_ENUM_VALUE,
            -> {
                if (type == ProtoTokens.IDENT) {
                    if (text in CONSTANT_LITERALS || text.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                        tok.kind = SyntaxTokenKind.Constant
                    }
                }
            }

            ProtoStmtState.AWAITING_FIELD_TYPE -> {
                if (type == ProtoTokens.IDENT) {
                    when {
                        text == "group" && nextType == ProtoTokens.IDENT &&
                            vi + 2 < visibleCount && tokType(vi + 2) == ProtoTokens.EQ -> {
                            tok.kind = SyntaxTokenKind.Keyword
                            scope.pendingBraceScope = ProtoScopeKind.GROUP_BODY
                            scope.stmtState = ProtoStmtState.AWAITING_GROUP_NAME
                        }
                        text == "map" && nextType == ProtoTokens.LANGLE -> {
                            tok.kind = SyntaxTokenKind.Type
                        }
                        text in SCALAR_TYPES -> {
                            tok.kind = SyntaxTokenKind.Type
                            scope.stmtState = ProtoStmtState.AWAITING_FIELD_NAME
                        }
                        nextType == ProtoTokens.DOT -> {
                            scope.stmtState = ProtoStmtState.IN_FIELD_TYPE_QUALIFIED
                        }
                        else -> {
                            tok.kind = SyntaxTokenKind.Type
                            scope.stmtState = ProtoStmtState.AWAITING_FIELD_NAME
                        }
                    }
                } else if (type == ProtoTokens.DOT) {
                    scope.stmtState = ProtoStmtState.IN_FIELD_TYPE_QUALIFIED
                }
            }

            ProtoStmtState.IN_FIELD_TYPE_QUALIFIED -> {
                if (type == ProtoTokens.IDENT && nextType != ProtoTokens.DOT) {
                    tok.kind = SyntaxTokenKind.Type
                    scope.stmtState = ProtoStmtState.AWAITING_FIELD_NAME
                }
            }

            ProtoStmtState.AWAITING_FIELD_NAME -> {
                if (type == ProtoTokens.IDENT) {
                    tok.kind = SyntaxTokenKind.Plain
                }
                scope.stmtState = ProtoStmtState.IN_FIELD_AFTER_NAME
            }

            ProtoStmtState.IN_FIELD_AFTER_NAME -> {
                if (type == ProtoTokens.IDENT && text in CONSTANT_LITERALS) {
                    tok.kind = SyntaxTokenKind.Constant
                }
            }

            ProtoStmtState.IN_SYNTAX_OR_EDITION,
            ProtoStmtState.IN_PACKAGE,
            ProtoStmtState.IN_IMPORT_PATH,
            ProtoStmtState.IN_RPC_HEADER,
            ProtoStmtState.REST_OF_STMT,
            -> Unit
        }

        vi++
    }
}
