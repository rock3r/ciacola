lexer grammar UnicodeIdent;

fragment UNICODE_XID_START
    : [a-zA-Z]
    | [\u00AA\u00B5\u00BA]
    | [\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02AF\u0370-\u037D\u037F-\u167F\u1681-\u1FFF\u2100-\u218F\u2C00-\u2FFF\u3040-\uD7FF\uF900-\uFDCF\uFDF0-\uFEFE\uFF21-\uFF3A\uFF41-\uFF5A\uFF66-\uFFEF]
    | [\u{10000}-\u{1EFFF}\u{20000}-\u{323AF}]
    ;

fragment UNICODE_XID_CONTINUE
    : UNICODE_XID_START
    | [0-9_]
    | [\u0300-\u036F\u200C-\u200D]
    ;
