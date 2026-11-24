package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.SwiftSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.SwiftOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwiftSyntaxLexerTest {

    @Test
    fun classifiesKeywordsAttributesDirectivesNumbersOperatorsAndComments() {
        val code = """
            import Foundation
            /// Doc line comment
            /** Doc block /* nested */ comment */
            /* outer /* inner */ end */

            #if os(macOS) && !targetEnvironment(simulator)
            #elseif os(iOS)
            #else
            #endif
            #warning("check")
            #error("unused")
            #sourceLocation(file: "A.swift", line: 1)
            #Preview { EmptyView() }
            #expect(1 == 1)

            indirect enum Tree { case leaf }
            @MainActor
            @Observable
            @available(*, deprecated)
            @propertyWrapper
            @freestanding(expression)
            @attached(member)
            package open class Wrapper<T: Sendable> {
                fileprivate nonisolated(unsafe) var raw = 0 {
                    willSet {}
                    didSet {}
                }
                private weak var delegate: AnyObject?
                internal lazy var items: [String] = []
                static let hexFloat = 0x1.fp2 + 0xAp-3
                static let hex = 0xFF_00
                static let bin = 0b1010_0101
                static let oct = 0o755
                static let dec = 1_000_000
                static let sci = 1.5e-3

                convenience required init(value: consuming String, extra: borrowing Int) rethrows {
                    self.init()
                    let x = #selector(NSObject.description)
                    let kpExpr = #keyPath(NSObject.description)
                    let fileId = #fileID
                    let lineNum = #line
                    if #unavailable(iOS 16) {}
                    let kp = \Wrapper<T>.items
                    let kpShort = \.items
                    let typeMeta = Wrapper<T>.self
                    let metaType = Wrapper<T>.Type
                    let `class` = "kw"
                    let `default` = true
                    let `init` = false
                    let nilVal: Int? = nil
                    let range1 = 0...10
                    let range2 = 0..<10
                    let coalesced = nilVal ?? 0
                    let fn: (Int) -> Int = { ${'$'}0 + 1 }
                    let projected = ${'$'}value
                    if #available(iOS 17, *) {
                        guard let v = nilVal else { return }
                        defer { print(v) }
                    }
                    switch coalesced {
                    case 0: fallthrough
                    default: break
                    }
                    _ = fn(1)
                }

                deinit {}
                subscript(index: Int) -> String {
                    get { items[index] }
                    set { items[index] = newValue }
                }
            }

            precedencegroup PipePrecedence {
                associativity: left
                higherThan: AssignmentPrecedence
            }
            infix operator |> : PipePrecedence
            func |><A, B>(value: A, transform: (A) -> B) -> B { transform(value) }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(SwiftSyntaxLexer, code, "swift-features")
        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("/// Doc line comment"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/** Doc block /* nested */ comment */"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* outer /* inner */ end */"))

        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#if"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#elseif"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#else"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#endif"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#warning"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#error"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#sourceLocation"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#Preview"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#expect"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#selector"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#keyPath"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#fileID"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#line"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#available"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#unavailable"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("indirect"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("willSet"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("didSet"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("rethrows"))

        assertEquals(SyntaxTokenKind.Annotation, firstKind("@MainActor"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@Observable"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@available"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@propertyWrapper"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@freestanding"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@attached"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("package"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("open"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("class"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Wrapper"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("fileprivate"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("nonisolated"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("weak"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("lazy"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("convenience"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("required"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("init"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("consuming"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("borrowing"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("deinit"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("subscript"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("get"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("set"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("precedencegroup"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("associativity"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("left"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("higherThan"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("infix"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("operator"))

        assertEquals(SyntaxTokenKind.Number, firstKind("0x1.fp2"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xAp-3"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010_0101"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.5e-3"))

        assertEquals(SyntaxTokenKind.Constant, firstKind("true"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("false"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("nil"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("\$0"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("\$value"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("`class`"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("`default`"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("`init`"))

        assertEquals(SyntaxTokenKind.Operator, firstKind("..."))
        assertEquals(SyntaxTokenKind.Operator, firstKind("..<"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("->"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("|>"))
    }

    @Test
    fun handlesExtendedRawStringsNestedInterpolationsAndEscapes() {
        val code = """
            let a = "hello \(name) \n \"world\""
            let b = "nested \("inner \(1 + 2)") tail"
            let c = #"raw \n \(notInterp) \#n \#(actualInterp) end"#
            let d = ##"two hashes \#(literal) \#"still" \##(real + 1) \##n"##
            let e = ###"quoted "#" inside"###
            let f = #""${'"'}
                multi \(stillLiteral)
                \#(interpolated + { ${'$'}0 }())
                ""${'"'}#
            let g = ##""${'"'}
                \#""${'"'}not closing
                \##(ok)
                ""${'"'}##
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(SwiftSyntaxLexer, code, "swift-strings")
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "\\n" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "\\#n" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "\\##n" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "actualInterp" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "real" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "interpolated" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "ok" }.kind)
        // Verify `\#(literal)` inside `##"..."##` stayed String text, not Plain/Punctuation.
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("notInterp") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("literal") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("stillLiteral") }.kind)
    }

    @Test
    fun disambiguatesBareAndExtendedRegexLiteralsFromDivisionAndComments() {
        val code = """
            let r1 = /\d+[a-z]+/
            let r2 = /[a/b]+\/end/
            let r3 = #/foo / bar/#
            let r4 = ##/a /# b/##
            let r5 = #/
                \d+ # multiline extended regex
                [a-z]+
            /#
            let div1 = a / b / c
            let div2 = (a + b) / 2
            let div3 = arr[0] / 3
            let notLeadingWs = a / 2 / 1
            if /ok/.wholeMatch(in: s) != nil {}
            let list = [/a/, /b/]
            // line comment
            /* block comment */
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(SwiftSyntaxLexer, code, "swift-regex")
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "/\\d+[a-z]+/" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "/[a/b]+\\/end/" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "#/foo / bar/#" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "##/a /# b/##" }.kind)
        assertTrue(tokens.any { it.slice(code).startsWith("#/\n") && it.slice(code).endsWith("/#") && it.kind == SyntaxTokenKind.String })
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "/ok/" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "/a/" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "/b/" }.kind)

        val div1Pos = code.indexOf("let div1")
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "/" && it.startIndex > div1Pos }.kind)
        val div2Pos = code.indexOf("let div2")
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "/" && it.startIndex > div2Pos }.kind)
        val div3Pos = code.indexOf("let div3")
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "/" && it.startIndex > div3Pos }.kind)
    }

    @Test
    fun distinguishesContextualKeywordsFromShadowedIdentifiers() {
        val code = """
            let actor = 1
            let async = "no"
            let await = true
            let some = 2
            let any = 3
            let package = 4
            let macro = 5
            let get = 6
            let set = 7
            let consume = 8
            let copy = 9
            func f() async {
                let x = await g()
                let y = consume x
                let z: some Equatable = copy y
                _ = obj.default
                _ = Type.self
            }
            private(set) var prop: Int = 0
            func pack<each T>(_ item: repeat each T) {}
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(SwiftSyntaxLexer, code, "swift-contextual")
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "actor" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "async" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "await" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "some" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "any" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "package" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "macro" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "get" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "set" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "consume" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "copy" }.kind)

        val fnStart = code.indexOf("func f()")
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "async" && it.startIndex > fnStart }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "await" && it.startIndex > fnStart }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "consume" && it.startIndex > fnStart }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "some" && it.startIndex > fnStart }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "copy" && it.startIndex > fnStart }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "default" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "self" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "set" && it.startIndex > fnStart }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "each" && it.startIndex > fnStart }.kind)
    }

    @Test
    fun recoversFromUnterminatedStringsCommentsAndHandlesBomCrlfUnicodeAndSurrogates() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val recovery = "\uFEFFlet s = \"unclosed \\(x\r\nlet next = 42\rlet café🎉 = \"hi $loneHigh$loneLow\"\n/* /* unclosed"
        val tokens = assertLosslessInvariants(SwiftSyntaxLexer, recovery, "swift-recovery-unicode")
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(recovery) == "let" && it.startIndex > recovery.indexOf("42") - 15 }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(recovery) == "42" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.last().kind)
    }

    @Test
    fun prefixTruncationFuzzAndLinearPerformance() {
        val sample = """
            @MainActor
            actor Box<T: Sendable> {
                let raw = #"a \#(1 + 2) \#n"#
                let re = /[a/b]+/
                /* outer /* inner */ end */
            }
        """.trimIndent()
        assertPrefixTruncationSafety(SwiftSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(SwiftSyntaxLexer, seed = 0x53574946L, iterations = 60)

        val unclosedStrings = "let s = \"unclosed \\(x\n".repeat(1_500)
        val unclosedExtStrings = "let s = ###\"unclosed \\###(x\n".repeat(1_000)
        val unclosedNestedComments = "/* ".repeat(5_000) + "body"
        val unclosedInterps = "\"\\(".repeat(500) + "x"
        val pathologicalRegex1 = "= " + "/ [ / ] ".repeat(2_000)
        val pathologicalRegex2 = "= " + "/ [ ".repeat(2_000)
        val unclosedExtRegex = "###/".repeat(1_500) + "abc"
        val repeatedDiv = "let x = a" + " / b".repeat(4_000)

        assertLinearPerformance(
            lexer = SwiftSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-strings" to unclosedStrings,
                "unclosed-ext-strings" to unclosedExtStrings,
                "unclosed-nested-comments" to unclosedNestedComments,
                "unclosed-interps" to unclosedInterps,
                "pathological-regex-brackets" to pathologicalRegex1,
                "pathological-regex-unclosed-brackets" to pathologicalRegex2,
                "unclosed-ext-regex" to unclosedExtRegex,
                "repeated-division" to repeatedDiv,
            ),
        )
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(SwiftOracleFixtures.actorsRegexStringsAndMacros)
    }
}
