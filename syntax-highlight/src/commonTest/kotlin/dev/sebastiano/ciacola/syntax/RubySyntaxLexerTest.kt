package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.RubySyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.RubyOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun matchesDualOracleFixtures() {
        assertAllOracleFixtures(RubyOracleFixtures.all)
    }
}
