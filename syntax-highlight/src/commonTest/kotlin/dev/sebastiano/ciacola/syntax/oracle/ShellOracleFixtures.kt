package dev.sebastiano.ciacola.syntax.oracle

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind

internal val shellOracleFixtures: List<OracleFixture> = listOf(
    OracleFixture(
        name = "shell-control-flow-and-builtins",
        language = SyntaxLanguage.Shell,
        code = """
            #!/usr/bin/env bash
            # Deploy helper
            export APP_ENV="prod"
            declare -A map=([key]=val)
            arr+=(alpha 42 0xFF)

            check_item() {
                local -r item="${'$'}1"
                if [[ -f "${'$'}item" && ${'$'}# -gt 0 ]]; then
                    echo "${'$'}item"
                elif [ -n "${'$'}item" ]; then
                    printf '%s\n' "${'$'}item"
                else
                    return 1
                fi
            }

            function run_loop {
                for i in 1 2 3; do
                    case "${'$'}i" in
                        1|2) echo ${'$'}((i + 16#FF)) ;;
                        *) break ;;
                    esac
                done
                while (( ${'$'}# > 0 )); do
                    shift
                done
            }

            cat <<< "ready" 2>&1 >/dev/null
            """.trimIndent() + "\n",
        minAgreementRatio = 0.92,
        expectations = listOf(
            OracleTokenExpectation("#!/usr/bin/env bash\n", 0, 20, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment, note = "STRICT: shebang"),
            OracleTokenExpectation("# Deploy helper\n", 20, 36, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment, note = "STRICT: comment"),
            OracleTokenExpectation("export", 36, 42, SyntaxTokenKind.Keyword, SyntaxTokenKind.Function, note = "STRICT: declaration keyword"),
            OracleTokenExpectation("APP_ENV", 43, 50, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 50, 51, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\"prod\"", 51, 57, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: string"),
            OracleTokenExpectation("declare", 58, 65, SyntaxTokenKind.Keyword, SyntaxTokenKind.Function, note = "STRICT: declaration keyword"),
            OracleTokenExpectation("-A", 66, 68, SyntaxTokenKind.Constant, SyntaxTokenKind.Plain),
            OracleTokenExpectation("map", 69, 72, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 72, 73, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("(", 73, 74, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("[", 74, 75, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("key", 75, 78, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
            OracleTokenExpectation("]", 78, 79, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("=", 79, 80, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("val", 80, 83, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 83, 84, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("arr", 85, 88, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("+=", 88, 90, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("(", 90, 91, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("alpha", 91, 96, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
            OracleTokenExpectation("42", 97, 99, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: integer"),
            OracleTokenExpectation("0xFF", 100, 104, SyntaxTokenKind.Number, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 104, 105, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("check_item", 107, 117, SyntaxTokenKind.Function, SyntaxTokenKind.Plain, note = "STRICT: function declaration"),
            OracleTokenExpectation("(", 117, 118, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation(")", 118, 119, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("{", 120, 121, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("local", 126, 131, SyntaxTokenKind.Keyword, SyntaxTokenKind.Function),
            OracleTokenExpectation("-r", 132, 134, SyntaxTokenKind.Constant, SyntaxTokenKind.Plain),
            OracleTokenExpectation("item", 135, 139, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 139, 140, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\"", 140, 141, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$1", 141, 143, SyntaxTokenKind.Constant, SyntaxTokenKind.Property),
            OracleTokenExpectation("\"", 143, 144, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("if", 149, 151, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("[[", 152, 154, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("-f", 155, 157, SyntaxTokenKind.Operator, SyntaxTokenKind.Plain),
            OracleTokenExpectation("\"", 158, 159, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$item", 159, 164, SyntaxTokenKind.String, SyntaxTokenKind.Property),
            OracleTokenExpectation("\"", 164, 165, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("&&", 166, 168, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\$#", 169, 171, SyntaxTokenKind.Constant, SyntaxTokenKind.Property),
            OracleTokenExpectation("-gt", 172, 175, SyntaxTokenKind.Operator, SyntaxTokenKind.Plain),
            OracleTokenExpectation("0", 176, 177, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: number"),
            OracleTokenExpectation("]]", 178, 180, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation(";", 180, 181, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("then", 182, 186, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("echo", 195, 199, SyntaxTokenKind.Function, SyntaxTokenKind.Function, note = "STRICT: builtin"),
            OracleTokenExpectation("\"", 200, 201, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$item", 201, 206, SyntaxTokenKind.String, SyntaxTokenKind.Property),
            OracleTokenExpectation("\"", 206, 207, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("elif", 212, 216, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("[", 217, 218, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("-n", 219, 221, SyntaxTokenKind.Operator, SyntaxTokenKind.Plain),
            OracleTokenExpectation("\"", 222, 223, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$item", 223, 228, SyntaxTokenKind.String, SyntaxTokenKind.Property),
            OracleTokenExpectation("\"", 228, 229, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("]", 230, 231, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation(";", 231, 232, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("then", 233, 237, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("printf", 246, 252, SyntaxTokenKind.Function, SyntaxTokenKind.Function, note = "STRICT: builtin"),
            OracleTokenExpectation("'%s\\n'", 253, 259, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: single-quoted string"),
            OracleTokenExpectation("\"", 260, 261, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$item", 261, 266, SyntaxTokenKind.String, SyntaxTokenKind.Property),
            OracleTokenExpectation("\"", 266, 267, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("else", 272, 276, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("return", 285, 291, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("1", 292, 293, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: number"),
            OracleTokenExpectation("fi", 298, 300, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("}", 301, 302, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("function", 304, 312, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("run_loop", 313, 321, SyntaxTokenKind.Function, SyntaxTokenKind.Plain, note = "STRICT: function name"),
            OracleTokenExpectation("{", 322, 323, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("for", 328, 331, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("i", 332, 333, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("in", 334, 336, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("1", 337, 338, SyntaxTokenKind.String, SyntaxTokenKind.Number),
            OracleTokenExpectation("2", 339, 340, SyntaxTokenKind.String, SyntaxTokenKind.Number),
            OracleTokenExpectation("3", 341, 342, SyntaxTokenKind.String, SyntaxTokenKind.Number),
            OracleTokenExpectation(";", 342, 343, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("do", 344, 346, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("case", 355, 359, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("\"", 360, 361, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$i", 361, 363, SyntaxTokenKind.String, SyntaxTokenKind.Property),
            OracleTokenExpectation("\"", 363, 364, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("in", 365, 367, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("1", 380, 381, SyntaxTokenKind.String, SyntaxTokenKind.Number),
            OracleTokenExpectation("|", 381, 382, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("2", 382, 383, SyntaxTokenKind.String, SyntaxTokenKind.Number),
            OracleTokenExpectation(")", 383, 384, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Case pattern delimiter classified as Punctuation"),
            OracleTokenExpectation("echo", 385, 389, SyntaxTokenKind.Function, SyntaxTokenKind.Function, note = "STRICT: builtin"),
            OracleTokenExpectation("\$((", 390, 393, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("i", 393, 394, SyntaxTokenKind.Function, SyntaxTokenKind.Property),
            OracleTokenExpectation("+", 395, 396, SyntaxTokenKind.String, SyntaxTokenKind.Operator),
            OracleTokenExpectation("16#FF", 397, 402, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: base-16 literal"),
            OracleTokenExpectation("))", 402, 404, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation(";;", 405, 407, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("*", 420, 421, SyntaxTokenKind.Operator, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 421, 422, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Case pattern delimiter classified as Punctuation"),
            OracleTokenExpectation("break", 423, 428, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation(";;", 429, 431, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("esac", 440, 444, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("done", 449, 453, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("while", 458, 463, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("((", 464, 466, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\$#", 467, 469, SyntaxTokenKind.Constant, SyntaxTokenKind.Property),
            OracleTokenExpectation(">", 470, 471, SyntaxTokenKind.Operator, SyntaxTokenKind.Plain),
            OracleTokenExpectation("0", 472, 473, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("))", 474, 476, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation(";", 476, 477, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("do", 478, 480, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("shift", 489, 494, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation("done", 499, 503, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("}", 504, 505, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("cat", 507, 510, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("<<<", 511, 514, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\"ready\"", 515, 522, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: string"),
            OracleTokenExpectation("2", 523, 524, SyntaxTokenKind.Operator, SyntaxTokenKind.Number),
            OracleTokenExpectation(">&", 524, 526, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("1", 526, 527, SyntaxTokenKind.Operator, SyntaxTokenKind.Number),
            OracleTokenExpectation(">", 528, 529, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("/dev/null", 529, 538, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
        ),
    ),
    OracleFixture(
        name = "shell-quotes-heredocs-expansions",
        language = SyntaxLanguage.Shell,
        code = """
            #!/bin/zsh
            echo foo#not_comment
            msg=$'line1\n\t\x41\u00E9'
            loc=$"hello ${'$'}{USER:-guest} ${'$'}(echo "nested")"
            len=${'$'}{#arr[@]}
            sub=${'$'}{path##*/}
            zsh_lines=${'$'}{(f)raw}
            lower=${'$'}{msg:l}

            cat <<'EOF' | grep -E "pat"
            literal ${'$'}USER ${'$'}(not_run)
            EOF

            cat <<-INDENT
            ${"\t"}value=${'$'}loc
            ${"\t"}INDENT

            diff <(cat <<A) <(cat <<B)
            first
            A
            second
            B
            """.trimIndent() + "\n",
        minAgreementRatio = 0.92,
        expectations = listOf(
            OracleTokenExpectation("#!/bin/zsh\n", 0, 11, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment, note = "STRICT: shebang"),
            OracleTokenExpectation("echo", 11, 15, SyntaxTokenKind.Function, SyntaxTokenKind.Function, note = "STRICT: builtin"),
            OracleTokenExpectation("foo#not_comment", 16, 31, SyntaxTokenKind.String, SyntaxTokenKind.Plain, note = "Word containing '#' must not be treated as comment"),
            OracleTokenExpectation("msg", 32, 35, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 35, 36, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\$'line1\\n\\t\\x41\\u00E9'", 36, 58, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: ANSI-C quoted string"),
            OracleTokenExpectation("loc", 59, 62, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 62, 63, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\$\"", 63, 65, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: locale double-quoted opener"),
            OracleTokenExpectation("hello ", 65, 71, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\${", 71, 73, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Parameter expansion opener classified as Punctuation"),
            OracleTokenExpectation("USER", 73, 77, SyntaxTokenKind.String, SyntaxTokenKind.Property),
            OracleTokenExpectation(":-", 77, 79, SyntaxTokenKind.Operator, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("guest", 79, 84, SyntaxTokenKind.String, SyntaxTokenKind.Property, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "Default text inside parameter expansion is Plain"),
            OracleTokenExpectation("}", 84, 85, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Parameter expansion closer classified as Punctuation"),
            OracleTokenExpectation("\$(", 86, 88, SyntaxTokenKind.String, SyntaxTokenKind.Keyword, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Command substitution opener classified as Punctuation"),
            OracleTokenExpectation("echo", 88, 92, SyntaxTokenKind.Function, SyntaxTokenKind.Function, note = "STRICT: nested command substitution builtin"),
            OracleTokenExpectation("\"nested\"", 93, 101, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: nested double-quoted string"),
            OracleTokenExpectation(")", 101, 102, SyntaxTokenKind.String, SyntaxTokenKind.Keyword, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Command substitution closer classified as Punctuation"),
            OracleTokenExpectation("\"", 102, 103, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("len", 104, 107, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 107, 108, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\${", 108, 110, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("#", 110, 111, SyntaxTokenKind.Operator, SyntaxTokenKind.String),
            OracleTokenExpectation("arr", 111, 114, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("[", 114, 115, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("@", 115, 116, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("]", 116, 117, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("}", 117, 118, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("sub", 119, 122, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 122, 123, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\${", 123, 125, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("path", 125, 129, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("##", 129, 131, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("}", 133, 134, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("zsh_lines", 135, 144, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 144, 145, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\${", 145, 147, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("(", 147, 148, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("f", 148, 149, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(")", 149, 150, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("raw", 150, 153, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Zsh parameter expansion variable"),
            OracleTokenExpectation("}", 153, 154, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("lower", 155, 160, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("=", 160, 161, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\${", 161, 163, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("msg", 163, 166, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation(":", 166, 167, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("l", 167, 168, SyntaxTokenKind.Plain, SyntaxTokenKind.Property),
            OracleTokenExpectation("}", 168, 169, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("cat", 171, 174, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("<<", 175, 177, SyntaxTokenKind.Operator, SyntaxTokenKind.String),
            OracleTokenExpectation("'EOF'", 177, 182, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("|", 183, 184, SyntaxTokenKind.Operator, SyntaxTokenKind.String),
            OracleTokenExpectation("grep", 185, 189, SyntaxTokenKind.Function, SyntaxTokenKind.String),
            OracleTokenExpectation("-E", 190, 192, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("\"pat\"", 193, 198, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("literal \$USER \$(not_run)", 199, 223, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: quoted heredoc body stays literal"),
            OracleTokenExpectation("EOF", 224, 227, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("cat", 229, 232, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("<<-", 233, 236, SyntaxTokenKind.Operator, SyntaxTokenKind.String),
            OracleTokenExpectation("INDENT", 236, 242, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("value=", 244, 250, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$loc", 250, 254, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Unquoted heredoc interpolates variable"),
            OracleTokenExpectation("INDENT", 256, 262, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("diff", 264, 268, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("<(", 269, 271, SyntaxTokenKind.String, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Process substitution opener classified as Punctuation"),
            OracleTokenExpectation("cat", 271, 274, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("<<", 275, 277, SyntaxTokenKind.Operator, SyntaxTokenKind.String),
            OracleTokenExpectation("A", 277, 278, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation(")", 278, 279, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Process substitution closer"),
            OracleTokenExpectation("first", 291, 296, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("A", 297, 298, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("second", 299, 305, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
            OracleTokenExpectation("B", 306, 307, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
        ),
    ),
)
