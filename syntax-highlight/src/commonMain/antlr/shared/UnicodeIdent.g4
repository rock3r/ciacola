lexer grammar UnicodeIdent;

fragment UNICODE_XID_START
    : [a-zA-Z]
    | [\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02AF\u0370-\u037D\u037F-\u1FFF\u2100-\u218F\u2C00-\uD7FF\uF900-\uFDCF\uFDF0-\uFEFE\uFF00-\uFFEF]
    | [\u{10000}-\u{1EFFF}\u{20000}-\u{323AF}]
    ;

fragment UNICODE_XID_CONTINUE
    : UNICODE_XID_START
    | [0-9_]
    | [\u0300-\u036F\u200C-\u200D]
    ;
