package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.RubySyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.RubyOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class RubySyntaxLexerTest {

    @Test
    fun classifiesCommentsEmbeddedDocsAndEndDataSection() {
        val code = """
            #!/usr/bin/env ruby
            # Line comment
            =begin
            Embedded documentation
            =ending does not close
            =end trailing note
            x =begin_var
            y = 1
            __END__
            arbitrary / unclosed " data %w[not ruby]
        """.trimIndent()

        val tokens = assertLosslessInvariants(RubySyntaxLexer, code, "ruby-comments-embdoc-end")

        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "#!/usr/bin/env ruby" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "# Line comment" }.kind)

        val embDoc = tokens.first { it.slice(code).startsWith("=begin") }
        assertEquals(SyntaxTokenKind.Comment, embDoc.kind)
        assertTrue(embDoc.slice(code).contains("=ending does not close"))
        assertTrue(embDoc.slice(code).endsWith("=end trailing note"))

        // Non-column-0 `=begin_var` must NOT trigger embedded documentation.
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "=" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "begin_var" }.kind)

        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "__END__" }.kind)
        val dataTok = tokens.last()
        assertEquals(SyntaxTokenKind.Plain, dataTok.kind)
        assertTrue(dataTok.slice(code).contains("arbitrary / unclosed"))
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun handlesStringsBackticksAndDeepNestedInterpolations() {
        val code = """
            s1 = 'it\'s \\ literal #{not_interp} #@x'
            s2 = "hello #{user.name}, ivar=#@foo, cvar=#@@bar, gvar=#${'$'}baz, back=#${'$'}1"
            s3 = "nested #{{ a: 1, b: [1, 2].map { |x| x + 1 } }} and #{"inner #{/re\/1/i}"}"
            s4 = "comment inside #{
              # ruby comment
              %w[a b].join(',')
            } done"
            cmd = `echo #{tag} && ls`
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-strings-interp")

        assertEquals(
            SyntaxTokenKind.String,
            tokens.first { it.slice(code) == "'it\\'s \\\\ literal #{not_interp} #@x'" }.kind,
        )
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "name" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "@foo" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "@@bar" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "\$baz" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "\$1" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "# ruby comment" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "%w[" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "`" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "echo " }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "tag" }.kind)

        // Ensure interpolation braces match `Punctuation`.
        assertTrue(tokens.any { it.slice(code) == "#{" && it.kind == SyntaxTokenKind.Punctuation })
        assertTrue(tokens.any { it.slice(code) == "}" && it.kind == SyntaxTokenKind.Punctuation })
    }

    @Test
    fun handlesPercentLiteralsAndDisambiguatesModulo() {
        val code = """
            q_str = %q(hello (nested) #{not_interp})
            Q_str = %Q/hi #{x}/
            w_arr = %w[alpha [beta] gamma]
            W_arr = %W<one #{two} three>
            i_arr = %i(foo bar)
            I_arr = %I{a #{b} c}
            s_sym = %s[my_sym]
            r_lit = %r|a/b|imxouesn
            x_cmd = %x:echo ok:
            raw_p = %!bang!
            hash_delim = %Q#color code#
            puts %w[arg1 arg2]
            m1 = a % b
            m2 = a % 2
            m3 = 10 % (x + 1)
            m1 %= 2
            m4 = a%b
            eq_delim = %=equal=
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-percent-literals")

        // Non-interpolating %q keeps `#{not_interp}` as String text, not Punctuation.
        assertTrue(tokens.any { it.slice(code).contains("#{not_interp}") && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%Q/" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%w[" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code).contains("alpha [beta] gamma") && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%W<" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%i(" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(tokens.any { it.slice(code) == "%I{" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(tokens.any { it.slice(code) == "%s[" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(tokens.any { it.slice(code) == "%r|" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "|imxouesn" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%x:" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%!" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%Q#" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "%=" && it.kind == SyntaxTokenKind.String })

        // Modulo `%` and `%=` must stay Operator.
        val modOps = tokens.filter { it.slice(code) == "%" }
        assertEquals(4, modOps.size)
        assertTrue(modOps.all { it.kind == SyntaxTokenKind.Operator })
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "%=" }.kind)
    }

    @Test
    fun handlesFifoHeredocsAndDisambiguatesLeftShift() {
        val code = """
            a = <<EOF
            hello #{name}
            EOF

            b = <<-INDENT
              indented #{val}
              INDENT

            c = <<~'LITERAL'
              literal #{not_interp}
              LITERAL

            d = <<`CMD`
            echo #{x}
            CMD

            foo(<<~SQL, <<-'TEXT')
              SELECT #{id}
            SQL
              plain #{text}
              TEXT

            arr << item
            bits = 1 << 4
            arr<<EOF
            class << self
              def bar; end
            end
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-heredocs-lshift")

        // Expanding heredoc interpolates `#{name}`.
        assertTrue(tokens.any { it.slice(code) == "<<EOF" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "name" && it.kind == SyntaxTokenKind.Plain })

        // Single-quoted heredoc does NOT interpolate `#{not_interp}`.
        assertTrue(tokens.any { it.slice(code) == "<<~'LITERAL'" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code).contains("#{not_interp}") && it.kind == SyntaxTokenKind.String })

        // Multiple FIFO heredocs on the same line: SQL expands, TEXT is literal.
        assertTrue(tokens.any { it.slice(code) == "<<~SQL" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "<<-'TEXT'" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "id" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(tokens.any { it.slice(code).contains("#{text}") && it.kind == SyntaxTokenKind.String })

        // `<<` in shift, append, no-space, and `class << self` must be Operator.
        val lshifts = tokens.filter { it.slice(code) == "<<" }
        assertEquals(4, lshifts.size)
        assertTrue(lshifts.all { it.kind == SyntaxTokenKind.Operator })
    }

    @Test
    fun disambiguatesRegexLiteralsFromDivisionAndHandlesCharClasses() {
        val code = """
            r1 = /a\/b[c\/d][e\/f]#{x}/imxouesn
            r2 = /=bar/
            puts /foo/
            def check
              return /re/
            end
            d1 = a / b
            d1 /= 2
            d2 = arr[0] / 2
            d3 = foo() / 2
            d4 = 1.0 / 2
            d5 = foo / 2
            d6 = a/b/c
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-regex-vs-div")

        assertTrue(tokens.any { it.slice(code) == "/imxouesn" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "=bar" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "foo" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "re" && it.kind == SyntaxTokenKind.String })

        val slashes = tokens.filter { it.slice(code) == "/" }
        // Regex opens/closes are String; division slashes (7 of them) are Operator.
        assertEquals(7, slashes.count { it.kind == SyntaxTokenKind.Operator })
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "/=" }.kind)
    }

    @Test
    fun classifiesSymbolsHashKeysCharLiteralsVariablesNumbersAndKeywords() {
        val code = """
            syms = [
              :foo, :foo?, :foo!, :foo=, :@ivar, :@@cvar, :${'$'}gvar,
              :+, :-@, :<=>, :[], :[]=, :==, :=~, :===, :!~, :**, :<<, :>>, :<=, :>=,
              :|, :&, :%, :/, :*, :-, :~, :^, :!, :!=, :"interp #{x}", :'single'
            ]
            eq_sym = (:foo==:bar && :foo!=:bar)
            hash = { foo: 1, bar?: true, baz!: 2, "str": 3, 'sq': 4 }
            scope = ActiveRecord::Base::MAX_SIZE
            tern = cond ? :b : :c
            tern2 = empty? ? 1 : 2
            tern3 = 1 ?a : b
            tern4 = cond ? "x":"y"
            chars = [?a, ?\n, ?\C-a, ?\M-x, ?\M-\C-a, ?\u{1F600}]
            gvars = [${'$'}0, ${'$'}1, ${'$'}?, ${'$'}!, ${'$'}@, ${'$'}_, ${'$'}~, ${'$'}&, ${'$'}`, ${'$'}', ${'$'}+, ${'$'}*, ${'$'}${'$'}, ${'$'}:, ${'$'}", ${'$'};, ${'$'},, ${'$'}/, ${'$'}\, ${'$'}., ${'$'}<, ${'$'}>, ${'$'}=, ${'$'}-w, ${'$'}-0]
            nums = [42, 1_000, 0xFF_00, 0b1010, 0o755, 0755, 0d123, 1.5, 1.to_s, 1..10, 1...10, 1e-3, 1r, 1.5r, 2i, 1.5i, 2ri]
            class Demo
              attr_reader :name
              def self.square(x) = x * x
              def []=(k, v); @map[k] = v; end
              def +@; self; end
              def check
                foo!=bar
                [1, 2].map { _1 + 1 }
                [1, 2].map { it * 2 }
                case [1, 2]
                in [a, b]
                  __ENCODING__
                end
              end
            end
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-symbols-chars-nums")

        for (sym in listOf(":foo", ":foo?", ":foo!", ":foo=", ":@ivar", ":@@cvar", ":\$gvar", ":+", ":-@", ":<=>", ":[]", ":[]=", ":==", ":=~", ":===", ":!~", ":**", ":<<", ":>>", ":<=", ":>=", ":|", ":&", ":%", ":/", ":*", ":-", ":~", ":^", ":!", ":!=", ":'single'")) {
            assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == sym }.kind, "Symbol $sym")
        }

        // `:foo==:bar` and `:foo!=:bar` must split cleanly into `:foo`, `==`/`!=`, `:bar`.
        assertTrue(tokens.any { it.slice(code) == ":foo" && it.kind == SyntaxTokenKind.Constant })
        assertTrue(tokens.any { it.slice(code) == "==" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "!=" && it.kind == SyntaxTokenKind.Operator })

        // Hash shorthand keys & `::` scope operator.
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "foo" && it.startIndex > code.indexOf("hash =") }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "bar?" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "baz!" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "ActiveRecord" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "Base" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "MAX_SIZE" }.kind)
        assertTrue(tokens.any { it.slice(code) == "::" && it.kind == SyntaxTokenKind.Punctuation })

        // Ternary vs character literals.
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "?" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == ":b" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "empty?" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "?a" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "?\\C-a" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "?\\M-\\C-a" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "?\\u{1F600}" }.kind)

        // Numbers & `1.to_s` / `1..10`.
        for (num in listOf("42", "1_000", "0xFF_00", "0b1010", "0o755", "0755", "0d123", "1.5", "1e-3", "1r", "1.5r", "2i", "1.5i", "2ri")) {
            assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == num }.kind, "Number $num")
        }
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "to_s" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == ".." }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "..." }.kind)

        // Endless method, `foo!=bar`, `__ENCODING__`.
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "square" }.kind)
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "+@" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "__ENCODING__" }.kind)
        assertFalse(tokens.any { it.slice(code) == "foo!" })
    }

    @Test
    fun handlesBomCrlfCrUnicodeEmojiAndLoneSurrogatesLosslessly() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val inputs = listOf(
            "\uFEFF# BOM comment\r\nx = 'café 🎉'\rputs x\r\n",
            "café_𠮷 = \"emoji 🎉 🚀\"\n",
            "x = \"$loneHigh\" + '${loneLow}y'\n$loneHigh$loneLow",
        )
        for ((idx, input) in inputs.withIndex()) {
            assertLosslessInvariants(RubySyntaxLexer, input, "ruby-unicode#$idx")
        }
    }

    @Test
    fun enforcesPrefixTruncationFuzzAndLinearPerformance() {
        val sample = """
            #!/usr/bin/env ruby
            =begin
            doc
            =end
            class A < B::C
              def run(x, k: :ok)
                re = /a[b\/c]#{x}/i
                arr = %w[one [two] three]
                msg = <<~EOS
                  hello #{k}
                EOS
                return x ? ?a : 1_000.to_s
              end
            end
        """.trimIndent()

        assertPrefixTruncationSafety(RubySyntaxLexer, sample)
        assertMalformedAndFuzzSafety(RubySyntaxLexer)

        val deepInterpOpen = "#{".repeat(2_000)
        val deepInterpClose = "}".repeat(2_000)
        val deepRegexOpen = "/#\n#{".repeat(1_000)
        val deepRegexClose = "}/x".repeat(1_000)
        val pathological = listOf(
            "unclosed-dquote" to ("\"abc \\\" " + "x".repeat(25_000)),
            "unclosed-squote" to ("'abc \\' " + "x".repeat(25_000)),
            "unclosed-backtick" to ("`echo " + "x".repeat(25_000)),
            "unclosed-percent" to ("%w[foo " + "bar ".repeat(6_000)),
            "unclosed-heredoc" to ("puts <<EOF\n" + "line\n".repeat(5_000)),
            "unclosed-embdoc" to ("=begin\n" + "line\n".repeat(5_000)),
            "unclosed-regex" to ("x = /" + "a[b/c]".repeat(4_000)),
            "unclosed-regex-hash" to ("x = /\n" + "# comment [unclosed\n".repeat(5_000)),
            "deep-regex-interp" to ("x = $deepRegexOpen 1 $deepRegexClose"),
            "division-chain" to ("a" + " / b".repeat(6_000)),
            "ternary-chain" to ("a" + " ? b : c".repeat(4_000)),
            "deep-interp" to ("\"$deepInterpOpen 1 $deepInterpClose\""),
            "false-embdoc" to ("x " + "=begin_id + ".repeat(4_000) + "1"),
        )
        assertLinearPerformance(RubySyntaxLexer, pathological, maxMillisPerInput = 2_000L)
    }

    @Test
    fun handlesMultiHeredocWithMultilineInterpolationAndEscapedNewlines() {
        val code = """
            pair = [<<~FIRST, <<~SECOND]
              hello #{
                1 + 2
              }
            FIRST
              second body
            SECOND
            x = 42

            s = <<~SQL
              hello \
            SQL
              still_heredoc
            SQL

            crlf = <<~EOS${"\r\n"}  line1 ${'\\'}${"\r\n"}EOS${"\r\n"}  line2${"\r\n"}EOS${"\r\n"}
            cr = <<~CRDOC${"\r"}  line1 ${'\\'}${"\r"}CRDOC${"\r"}  line2${"\r"}CRDOC${"\r"}
            after = true
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-heredoc-interp-escape")

        // `x = 42` after both heredocs must be lexed as Ruby code, not inside `SECOND`.
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "42" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "x" }.kind)

        // First `SQL` after `hello \` is escaped continuation -> stays String body; final `SQL` closes.
        val sqlTokens = tokens.filter { it.slice(code) == "SQL" }
        assertEquals(1, sqlTokens.size)
        assertTrue(tokens.any { it.slice(code).contains("still_heredoc") && it.kind == SyntaxTokenKind.String })

        // CRLF and CR escaped newlines must not prematurely close the heredoc.
        assertTrue(tokens.any { it.slice(code).contains("line2") && it.kind == SyntaxTokenKind.String })
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "true" }.kind)
    }

    @Test
    fun disambiguatesLocalVariablesFromCommandCalls() {
        val code = """
            arr = []
            item = 1
            arr <<item

            x = 10
            y = 2
            z = x /y
            q = [3]
            w = x %q[0]

            flag = true
            msg = flag ?"yes" : "no"

            def inner_scope(puts)
              puts + 1
            end
            def endless_shadow(puts) = puts + 2
            puts <<EOF
            dsl_ok
            EOF
            puts /foo/
            puts %q[hi]
            puts ?a
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-local-vars")

        // `arr <<item`, `x /y`, `x %q[0]`, `flag ?"yes" : "no"` must lex as operators, not literals.
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "<<" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "/" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "%" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "?" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "yes" }.kind)

        // Outer `puts` is not shadowed by `inner_scope(puts)` or `endless_shadow(puts)`.
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "<<EOF" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "foo" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "%q[" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "?a" }.kind)
    }

    @Test
    fun handlesMultiCharAfterQuestionAndExtendedRegexCommentsAndBacktickDefAndCompactTernary() {
        val code = """
            ab = 1
            cd = 2
            t1 = [1].empty? ?ab : cd
            t2 = empty? ?ab : cd

            re_ext = /
              \d+ # range [0, 10)
            /x
            msg = "hello"

            re_lit = /a # [b]/
            re_cc = /[a
            b]/
            skip = line =~ /^#/
            path = "src/xml"
            after_re = 99

            class M
              def `(cmd)
                cmd
              end
            end
            def self.`(cmd)
              cmd
            end
            shell_out = `echo ok`

            cond = true
            a = 1
            b = 2
            x = cond ? a:b
            h = cond ? { a: 1 } : { b: 2 }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-audit-regressions")

        // `?ab` must be Operator `?` + Plain `ab`, not CHAR_LITERAL `?a` + `b`.
        assertFalse(tokens.any { it.slice(code) == "?a" })
        assertTrue(tokens.count { it.slice(code) == "ab" && it.kind == SyntaxTokenKind.Plain } >= 3)

        // Multiline `/.../x` with `# range [0, 10)` closes cleanly at `/x`, and `/^#/` closes on line 1.
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "/x" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "hello" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "path" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "src/xml" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "99" }.kind)

        // `def `(cmd)` and `def self.`(cmd)` lex `` ` `` as Function and do not swallow `shell_out`.
        val backtickDefs = tokens.filter { it.slice(code) == "`" && it.kind == SyntaxTokenKind.Function }
        assertEquals(2, backtickDefs.size)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "echo ok" }.kind)

        // Compact ternary `cond ? a:b` lexes `a` as Plain and `:` as Punctuation, while `{ a: 1 }` keeps hash key Constant.
        val aAfterCond = tokens.first { it.slice(code) == "a" && it.startIndex > code.indexOf("x = cond ?") }
        assertEquals(SyntaxTokenKind.Plain, aAfterCond.kind)
        val aInHash = tokens.first { it.slice(code) == "a" && it.startIndex > code.indexOf("h = cond ?") }
        assertEquals(SyntaxTokenKind.Constant, aInHash.kind)
        val bInHash = tokens.first { it.slice(code) == "b" && it.startIndex > code.indexOf("{ b: 2 }") }
        assertEquals(SyntaxTokenKind.Constant, bInHash.kind)
    }

    @Test
    fun preservesLocalVariableScopesAcrossModifiersAndLoopDoHeaders() {
        val code = """
            count = 10
            def log(m)
              return if m.empty?
            end
            half = count /2

            def loop_test(xs)
              while false do
                break unless xs.empty?
              end
              until true do
                next if xs.nil?
              end
              for x in xs do
                next if x.nil?
              end
              return /foo/
            end
            quarter = count /4
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-scope-balance")

        val halfSlash = tokens.first { it.slice(code) == "/" && it.startIndex > code.indexOf("half =") }
        assertEquals(SyntaxTokenKind.Operator, halfSlash.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "2" }.kind)

        val quarterSlash = tokens.first { it.slice(code) == "/" && it.startIndex > code.indexOf("quarter =") }
        assertEquals(SyntaxTokenKind.Operator, quarterSlash.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "4" }.kind)

        // `return /foo/` inside `loop_test` must still lex `/foo/` as a regex literal.
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "foo" }.kind)
    }

    @Test
    fun isolatesParameterlessBlocksWithoutIsolatingHashLiterals() {
        val code = """
            1.times { puts = 1 }; puts /a/
            1.times do warn = 1 end; warn /b/
            1.times { || print = 1 }; print /c/
            puts { local_p = 1 }; local_p /e/
            h = { a: (y = 1) }; z = y /2
            puts({ a: (hash_v = 1) }); r1 = hash_v /2
            def m; return { a: (ret_v = 1) }; ret_v /2; end
            outer_v = 1; 1.times { outer_v = 2; inner_v = 3 }; r = outer_v /2; inner_v /d/
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-parameterless-blocks")

        // Assignments inside parameterless blocks (including bare method calls) must not leak.
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "a" && it.startIndex > code.indexOf("; puts") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "b" && it.startIndex > code.indexOf("; warn") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "c" && it.startIndex > code.indexOf("; print") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "e" && it.startIndex > code.indexOf("; local_p") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "d" && it.startIndex > code.indexOf("; inner_v") }.kind)

        // Assignments inside hash literals and outer vars modified inside blocks stay bound in outer scope.
        val ySlash = tokens.first { it.slice(code) == "/" && it.startIndex > code.indexOf("z = y") }
        assertEquals(SyntaxTokenKind.Operator, ySlash.kind)
        val hashSlash = tokens.first { it.slice(code) == "/" && it.startIndex > code.indexOf("r1 = hash_v") }
        assertEquals(SyntaxTokenKind.Operator, hashSlash.kind)
        val retSlash = tokens.first { it.slice(code) == "/" && it.startIndex > code.indexOf("; ret_v") }
        assertEquals(SyntaxTokenKind.Operator, retSlash.kind)
        val outerSlash = tokens.first { it.slice(code) == "/" && it.startIndex > code.indexOf("r = outer_v") }
        assertEquals(SyntaxTokenKind.Operator, outerSlash.kind)
    }

    @Test
    fun scopesStabbyLambdaParamsBothBareAndParenthesized() {
        val code = """
            f1 = ->x { x /2/3 }; x /rx1/
            f2 = ->x, y = 1 { x /y }; y /rx2/
            f3 = ->x do x /2/3 end; x /rx3/
            f4 = ->*a { a /2 }; a /rx4/
            f5 = ->**kw { kw /2 }; kw /rx5/
            f6 = ->&b { b /2 }; b /rx6/
            f7 = ->k: { k /2 }; k /rx7/
            f8 = ->k: 1 { k /2 }; k /rx8/
            f9 = ->puts { puts /2 }; puts /rx9/
            f10 = ->(puts) { puts /2 }; puts /rx10/
            f11 = ->(x = { m: (z = 1) }) { x /z }; z /rx11/
            f12 = ->(x = ->y { y /2 }) { x /3 }; y /rx12/
            f13 = ->(x; local_b) { x /local_b }; local_b /rx13/
            f14 = -> { p = 1 }; p /rx14/
            f15 = -> do p = 1 end; p /rx15/
            f16 = ->x = [{ a: 1 }] { x /2 }; x /rx16/
            f17 = ->x = if cond then 1 else 2 end { x /2 }; x /rx17/
            f18 = -> a, (b, c) { a /b /c }; b /rx18/
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-stabby-lambda-scopes")

        // Inside every lambda body, every `/` is division (Operator), never a regex delimiter.
        val bodyLines = listOf("f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12", "f13", "f16", "f17", "f18")
        for (prefix in bodyLines) {
            val lineStart = code.indexOf("$prefix = ")
            val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
            val bodyEnd = listOf(code.indexOf("}; ", lineStart), code.indexOf("end; ", lineStart))
                .filter { it in lineStart until lineEnd }
                .minOrNull()
                ?: lineEnd
            val bodySlashes = tokens.filter { it.startIndex in lineStart until bodyEnd && it.slice(code) == "/" }
            assertTrue(bodySlashes.isNotEmpty(), "Expected division slash in $prefix body")
            for (slash in bodySlashes) {
                assertEquals(SyntaxTokenKind.Operator, slash.kind, "Expected Operator '/' inside $prefix body")
            }
        }

        // After every lambda body closes, its params and body assignments do not leak to outer scope.
        for (rx in (1..18).map { "rx$it" }) {
            val rxToken = tokens.first { it.slice(code) == rx }
            assertEquals(SyntaxTokenKind.String, rxToken.kind, "Expected regex literal /$rx/ after lambda closes")
        }
    }

    @Test
    fun handlesMultilineLambdaParamsComplexPipeDefaultsAndMalformedRecovery() {
        val multilineCode = """
            m1 = -> a,
              b { a /b }; a /mr1/; b /mr2/
            m2 = -> a = 1 +
              2 { a /2 }; a /mr3/
            m3 = ->(
              a,
              b
            ) { a /b }; a /mr4/; b /mr5/
            1.times { |pa; pb| pa /pb }; pb /mr6/
            1.times do |pa = (begin; 1; end), pb| pa /pb end; pb /mr7/
            1.times { |pa = ->{ 0 }, pb| pa /pb }; pb /mr8/
            1.times { |pa = bar { |px| px /2 }, pb| pa /pb }; px /mr9/; pb /mr10/
            1.times { |pa = (1 | 2), pb| pa /pb }; pb /mr11/
            def with_lambda_default(cb = ->(x) { x }, y = 1)
              y /2
            end
            def endless_lambda = ->(x) { x }
            puts /mr12/
        """.trimIndent()

        val multiTokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, multilineCode, "ruby-multiline-and-pipe-params")
        for (marker in listOf("{ a /b }", "{ a /2 }", "pa /pb", "px /2", "y /2")) {
            var pos = 0
            var count = 0
            while (true) {
                val idx = multilineCode.indexOf(marker, pos)
                if (idx < 0) break
                val slashIdx = multilineCode.indexOf('/', idx)
                val slashToken = multiTokens.first { it.startIndex == slashIdx }
                assertEquals(SyntaxTokenKind.Operator, slashToken.kind, "Expected division in '$marker'")
                pos = idx + marker.length
                count++
            }
            assertTrue(count > 0, "Expected at least one occurrence of '$marker'")
        }
        for (rx in (1..12).map { "mr$it" }) {
            assertEquals(SyntaxTokenKind.String, multiTokens.first { it.slice(multilineCode) == rx }.kind, "Expected regex /$rx/")
        }

        // Malformed stabby lambdas and mismatched delimiters must unwind their lambda scopes cleanly.
        val malformedCases = listOf(
            "v = 1\n-> x\nx /rx/\nv /2",
            "v = 1\n->(x\n)\nx /rx/\nv /2",
            "v = 1\nfoo(->)\nv /2",
            "v = 1\n-> end\nv /2",
            "v = 1\nf = ->(x = foo { ))\n}\nx /rx/\nv /2",
        )
        for ((index, sample) in malformedCases.withIndex()) {
            val tokens = assertLosslessInvariants(RubySyntaxLexer, sample, "ruby-malformed-lambda-$index")
            val slashIdx = sample.lastIndexOf('/')
            val slashToken = tokens.first { it.startIndex == slashIdx }
            assertEquals(SyntaxTokenKind.Operator, slashToken.kind, "Outer local `v` should survive malformed case $index")
            if ("/rx/" in sample) {
                assertEquals(
                    SyntaxTokenKind.String,
                    tokens.first { it.slice(sample) == "rx" }.kind,
                    "Aborted lambda parameter `x` must not leak in malformed case $index",
                )
            }
        }
    }

    @Test
    fun isolatesDefHeaderDefaultsEndlessDoDefsAndSetterDefs() {
        val code = """
            def with_nested_defaults(cb = ->(a, b) { puts /dn1/ }, opts = { k: 1, j: 2 }, z = 1)
              j /dn2/
              z /2
            end
            def with_proc_default(cb = proc { |a, b| puts /dn3/ }, w = 1)
              w /2
            end
            def with_block_local_semi(cb = ->(x; y) { y /2 }, p1 = proc { |a; b| b /2 }, z = 1)
              z /2
            end
            def foo=(val)
              val /2
            end
            val /dn4/
            outer_endless = 1
            def endless_lambda_do = -> do
              1
            end
            r1 = outer_endless /2
            def endless_map_do = xs.map do |item|
              item /2
            end
            r2 = outer_endless /2
            def outer_nested_endless = items.each do |i|
              def inner_nested_endless = i
            end
            r3 = outer_endless /2
            def chained_outer = def chained_inner = 1
            r4 = outer_endless /2
            class InlineEndlessInIf
              if cond then def inline_x = 1 end
            end
            r5 = outer_endless /2
            def outer_inline_do = items.each do |i| def inner_inline_do = i end
            r6 = outer_endless /2
            def bare_kw k:
              puts /dn5/
            end
            def bare_empty_arr a = []
              puts /dn6/
            end
            def bare_empty_hash a = {}
              puts /dn7/
            end
            def bare_backtick_default cmd = `whoami`
              puts a_arg, b_arg
              b_arg /dn8/
            end
            def bare_cont a,
              # comment line between bare params

              b
              b /2
            end
            def bare_op_cont a = 1 +
              # comment line after operator

              2, b_op
              b_op /2
            end
            def bare_pipe_cont a = FLAG_A |
              FLAG_B, b_pipe
              b_pipe /2
            end
            1.times { `ls` | pipe_rhs; pipe_rhs /dn9/ }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(RubySyntaxLexer, code, "ruby-def-header-and-endless-do")

        for (rx in listOf("dn1", "dn2", "dn3", "dn4", "dn5", "dn6", "dn7", "dn8", "dn9")) {
            assertEquals(
                SyntaxTokenKind.String,
                tokens.first { it.slice(code) == rx }.kind,
                "Expected regex literal /$rx/",
            )
        }
        for (marker in listOf(
            "z /2",
            "w /2",
            "y /2",
            "b /2",
            "b_op /2",
            "b_pipe /2",
            "val /2",
            "r1 = outer_endless /2",
            "item /2",
            "r2 = outer_endless /2",
            "r3 = outer_endless /2",
            "r4 = outer_endless /2",
            "r5 = outer_endless /2",
            "r6 = outer_endless /2",
        )) {
            var pos = 0
            var count = 0
            while (true) {
                val idx = code.indexOf(marker, pos)
                if (idx < 0) break
                val slashIdx = code.indexOf('/', idx)
                assertEquals(
                    SyntaxTokenKind.Operator,
                    tokens.first { it.startIndex == slashIdx }.kind,
                    "Expected division operator in '$marker'",
                )
                pos = idx + marker.length
                count++
            }
            assertTrue(count > 0, "Expected marker '$marker'")
        }

        // Anonymous `*` / `**` / `&` at the end of a paren-less `def` must not keep collecting into the body.
        for (sig in listOf("*", "**", "&")) {
            val sample = "def anon_prefix $sig\n  puts /rx/\nend"
            val sampleTokens = assertLosslessInvariants(RubySyntaxLexer, sample, "ruby-anon-prefix-$sig")
            assertEquals(
                SyntaxTokenKind.String,
                sampleTokens.first { it.slice(sample) == "rx" }.kind,
                "Expected /rx/ regex in body after `def anon_prefix $sig`",
            )
        }

        // Closing a `def` while an inner `{` is left unclosed must not leak the def's locals past `end`.
        val unclosedBraceInDef = "v = 1\ndef m\n  x = 1\n  foo {\nend\nx /rx/\nv /2"
        val unclosedTokens = assertLosslessInvariants(RubySyntaxLexer, unclosedBraceInDef, "ruby-unclosed-brace-in-def")
        assertEquals(SyntaxTokenKind.String, unclosedTokens.first { it.slice(unclosedBraceInDef) == "rx" }.kind)
        assertEquals(SyntaxTokenKind.Operator, unclosedTokens.first { it.startIndex == unclosedBraceInDef.lastIndexOf('/') }.kind)
    }

    @Test
    fun staysLinearOnDeepUnclosedBlocks() {
        assertLinearScaling(label = "deep-unclosed-blocks") { count -> "a { ".repeat(count) }
    }

    @Test
    fun staysLinearOnOrphanedInterpHeredocs() {
        assertLinearScaling(label = "orphaned-interp-heredocs") { count -> "\"#{<<A}\"\n".repeat(count) }
    }

    private fun assertLinearScaling(
        label: String,
        smallCount: Int = 4_000,
        largeCount: Int = 16_000,
        maxLargeMillis: Long = 1_500L,
        buildInput: (Int) -> String,
    ) {
        // Warm up JIT/runtime before timing the 4x growth ratio (O(N) ~4x vs O(N^2) ~16x).
        RubySyntaxLexer.lex(buildInput(500))

        val smallInput = buildInput(smallCount)
        val smallMark = TimeSource.Monotonic.markNow()
        val smallTokens = RubySyntaxLexer.lex(smallInput)
        val smallMs = smallMark.elapsedNow().inWholeMilliseconds
        assertLosslessInvariants(code = smallInput, tokens = smallTokens, label = "ruby:scale-small:$label")

        val largeInput = buildInput(largeCount)
        val largeMark = TimeSource.Monotonic.markNow()
        val largeTokens = RubySyntaxLexer.lex(largeInput)
        val largeMs = largeMark.elapsedNow().inWholeMilliseconds
        assertLosslessInvariants(code = largeInput, tokens = largeTokens, label = "ruby:scale-large:$label")

        assertTrue(
            largeMs <= maxLargeMillis,
            "[$label] Lexing $largeCount repeats took ${largeMs}ms (limit: ${maxLargeMillis}ms)",
        )
        val maxScaledMillis = maxOf(smallMs * 10L, 250L)
        assertTrue(
            largeMs <= maxScaledMillis,
            "[$label] 4x input growth ($smallCount -> $largeCount) scaled superlinearly: ${smallMs}ms -> ${largeMs}ms (limit: ${maxScaledMillis}ms)",
        )
    }

    @Test
    fun matchesDualOracleFixtures() {
        assertAllOracleFixtures(RubyOracleFixtures.all)
    }
}
