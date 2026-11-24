package dev.sebastiano.ciacola.syntax.oracle

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind

internal object SwiftOracleFixtures {
    private val actorsRegexStringsAndMacrosCode =
        """
        import Foundation

        /// Documentation comment
        /* outer /* nested */ still comment */
        @MainActor
        @available(*, deprecated, message: "use v2")
        public actor Cache<Key: Hashable & Sendable, Value> {
            private var storage: [Key: Value] = [:]
            let raw = #"escaped \#(storage.count) \#n"#
            let multi = ""${'"'}
                line \(1 + 2)
                ""${'"'}
            let re = /\d+[a-z]+/
            let extRe = #/a / b/#
            let hexFloat = 0x1.fp2
            let binNum = 0b1010_0101
            let octNum = 0o755
            let decNum = 1_000_000

            #if os(macOS)
            public func lookup(_ key: Key, fallback: Value? = nil) async throws -> some Equatable {
                let `class` = try await fetch(key) ?? fallback
                defer { storage[key] = `class` }
                let ratio = decNum / 2
                let kp = \Cache.storage
                guard let result = `class` else { return false }
                return result == true ? .ok : self.defaultValue()
            }
            #endif
        }
        """.trimIndent() + "\n"

    val actorsRegexStringsAndMacros: OracleFixture = OracleFixture(
        name = "swift-actors-regex-strings-macros",
        language = SyntaxLanguage.Swift,
        code = actorsRegexStringsAndMacrosCode,
        minAgreementRatio = 0.95,
        maxOverrideShare = 0.15,
        expectations = listOf(
            OracleTokenExpectation("import", 0, 6, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: Swift import keyword"),
            OracleTokenExpectation("Foundation", 7, 17, SyntaxTokenKind.Type, SyntaxTokenKind.Type, note = "STRICT: Swift module name"),
            OracleTokenExpectation("/// Documentation comment", 19, 44, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment, note = "STRICT: doc line comment"),
            OracleTokenExpectation("/* outer /* nested */ still comment */", 45, 83, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment, note = "STRICT: nested block comment"),
            OracleTokenExpectation("@MainActor", 84, 94, SyntaxTokenKind.Annotation, SyntaxTokenKind.Plain, note = "STRICT: Swift attribute"),
            OracleTokenExpectation("@available", 95, 105, SyntaxTokenKind.Annotation, SyntaxTokenKind.Plain, note = "STRICT: Swift attribute"),
            OracleTokenExpectation("(", 105, 106, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("*", 106, 107, SyntaxTokenKind.Keyword, SyntaxTokenKind.Operator),
            OracleTokenExpectation(",", 107, 108, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("deprecated", 109, 119, SyntaxTokenKind.Keyword, SyntaxTokenKind.Plain),
            OracleTokenExpectation(",", 119, 120, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("message", 121, 128, SyntaxTokenKind.Keyword, SyntaxTokenKind.Plain),
            OracleTokenExpectation(":", 128, 129, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\"", 130, 131, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: string open"),
            OracleTokenExpectation("use v2", 131, 137, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: string text"),
            OracleTokenExpectation("\"", 137, 138, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: string close"),
            OracleTokenExpectation(")", 138, 139, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("public", 140, 146, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("actor", 147, 152, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: Swift actor keyword"),
            OracleTokenExpectation("Cache", 153, 158, SyntaxTokenKind.Type, SyntaxTokenKind.Plain, note = "STRICT: declared actor type"),
            OracleTokenExpectation("<", 158, 159, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Key", 159, 162, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation(":", 162, 163, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Hashable", 164, 172, SyntaxTokenKind.Type, SyntaxTokenKind.Type),
            OracleTokenExpectation("&", 173, 174, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("Sendable", 175, 183, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation(",", 183, 184, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Value", 185, 190, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation(">", 190, 191, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("{", 192, 193, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("private", 198, 205, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("var", 206, 209, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("storage", 210, 217, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(":", 217, 218, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("[", 219, 220, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Key", 220, 223, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation(":", 223, 224, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Value", 225, 230, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation("]", 230, 231, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("=", 232, 233, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("[", 234, 235, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(":", 235, 236, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("]", 236, 237, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("let", 242, 245, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("raw", 246, 249, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 250, 251, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("#\"", 252, 254, SyntaxTokenKind.String, SyntaxTokenKind.Punctuation, note = "STRICT: extended raw string open"),
            OracleTokenExpectation("escaped ", 254, 262, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: extended raw string body"),
            OracleTokenExpectation("\\#(", 262, 265, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String, note = "STRICT: extended interpolation open"),
            OracleTokenExpectation("storage", 265, 272, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "Swift extended interpolation variable"),
            OracleTokenExpectation(".", 272, 273, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Swift extended interpolation dot"),
            OracleTokenExpectation("count", 273, 278, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Swift extended interpolation member"),
            OracleTokenExpectation(")", 278, 279, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("\\#n", 280, 283, SyntaxTokenKind.Constant, SyntaxTokenKind.String, note = "STRICT: extended escape sequence"),
            OracleTokenExpectation("\"#", 283, 285, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: extended raw string close"),
            OracleTokenExpectation("let", 290, 293, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("multi", 294, 299, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 300, 301, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\"\"\"", 302, 305, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: multiline string open"),
            OracleTokenExpectation("line ", 314, 319, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\\(", 319, 321, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("1", 321, 322, SyntaxTokenKind.String, SyntaxTokenKind.Number),
            OracleTokenExpectation("+", 323, 324, SyntaxTokenKind.String, SyntaxTokenKind.Operator),
            OracleTokenExpectation("2", 325, 326, SyntaxTokenKind.String, SyntaxTokenKind.Number),
            OracleTokenExpectation(")", 326, 327, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\"\"\"", 336, 339, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: multiline string close"),
            OracleTokenExpectation("let", 344, 347, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("re", 348, 350, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 351, 352, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("/\\d+[a-z]+/", 353, 364, SyntaxTokenKind.String, SyntaxTokenKind.Operator, note = "STRICT: Swift bare regex literal"),
            OracleTokenExpectation("let", 369, 372, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("extRe", 373, 378, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 379, 380, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("#/a / b/#", 381, 390, SyntaxTokenKind.String, SyntaxTokenKind.Operator, note = "STRICT: Swift extended regex literal"),
            OracleTokenExpectation("let", 395, 398, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("hexFloat", 399, 407, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 408, 409, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("0x1.fp2", 410, 417, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: Swift hex float literal"),
            OracleTokenExpectation("let", 422, 425, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("binNum", 426, 432, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 433, 434, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("0b1010_0101", 435, 446, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: Swift binary literal"),
            OracleTokenExpectation("let", 451, 454, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("octNum", 455, 461, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 462, 463, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("0o755", 464, 469, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: Swift octal literal"),
            OracleTokenExpectation("let", 474, 477, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("decNum", 478, 484, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 485, 486, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("1_000_000", 487, 496, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: Swift decimal literal"),
            OracleTokenExpectation("#if", 502, 505, SyntaxTokenKind.Keyword, SyntaxTokenKind.Preprocessor, note = "STRICT: compiler conditional directive"),
            OracleTokenExpectation("os", 506, 508, SyntaxTokenKind.Keyword, SyntaxTokenKind.Preprocessor, allowedKinds = setOf(SyntaxTokenKind.Function), note = "Swift platform condition call"),
            OracleTokenExpectation("(", 508, 509, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("macOS", 509, 514, SyntaxTokenKind.Plain, SyntaxTokenKind.Preprocessor),
            OracleTokenExpectation(")", 514, 515, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("public", 520, 526, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("func", 527, 531, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("lookup", 532, 538, SyntaxTokenKind.Function, SyntaxTokenKind.Function, note = "STRICT: declared function"),
            OracleTokenExpectation("(", 538, 539, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("_", 539, 540, SyntaxTokenKind.Function, SyntaxTokenKind.Constant, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "Swift omitted parameter label"),
            OracleTokenExpectation("key", 541, 544, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(":", 544, 545, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Key", 546, 549, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation(",", 549, 550, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("fallback", 551, 559, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation(":", 559, 560, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Value", 561, 566, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation("?", 566, 567, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("=", 568, 569, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("nil", 570, 573, SyntaxTokenKind.Constant, SyntaxTokenKind.Constant, note = "STRICT: nil literal"),
            OracleTokenExpectation(")", 573, 574, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("async", 575, 580, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: async keyword"),
            OracleTokenExpectation("throws", 581, 587, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("->", 588, 590, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("some", 591, 595, SyntaxTokenKind.Keyword, SyntaxTokenKind.Plain, note = "STRICT: opaque some keyword"),
            OracleTokenExpectation("Equatable", 596, 605, SyntaxTokenKind.Type, SyntaxTokenKind.Type),
            OracleTokenExpectation("{", 606, 607, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("let", 616, 619, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("`class`", 620, 627, SyntaxTokenKind.Plain, SyntaxTokenKind.Keyword, note = "STRICT: backtick identifier"),
            OracleTokenExpectation("=", 628, 629, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("try", 630, 633, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("await", 634, 639, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("fetch", 640, 645, SyntaxTokenKind.Function, SyntaxTokenKind.Plain, note = "STRICT: function call"),
            OracleTokenExpectation("(", 645, 646, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("key", 646, 649, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 649, 650, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("??", 651, 653, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("fallback", 654, 662, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("defer", 671, 676, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("{", 677, 678, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("storage", 679, 686, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("[", 686, 687, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("key", 687, 690, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("]", 690, 691, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("=", 692, 693, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("`class`", 694, 701, SyntaxTokenKind.Plain, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("}", 702, 703, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("let", 712, 715, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("ratio", 716, 721, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 722, 723, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("decNum", 724, 730, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("/", 731, 732, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator, note = "STRICT: division operator"),
            OracleTokenExpectation("2", 733, 734, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("let", 743, 746, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("kp", 747, 749, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 750, 751, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\\", 752, 753, SyntaxTokenKind.Plain, SyntaxTokenKind.Invalid, allowedKinds = setOf(SyntaxTokenKind.Operator), note = "Swift key path operator"),
            OracleTokenExpectation("Cache", 753, 758, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Type), note = "Swift key path root type"),
            OracleTokenExpectation(".", 758, 759, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("storage", 759, 766, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Swift key path member property"),
            OracleTokenExpectation("guard", 775, 780, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("let", 781, 784, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("result", 785, 791, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 792, 793, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("`class`", 794, 801, SyntaxTokenKind.Plain, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("else", 802, 806, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("{", 807, 808, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("return", 809, 815, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("false", 816, 821, SyntaxTokenKind.Constant, SyntaxTokenKind.Constant),
            OracleTokenExpectation("}", 822, 823, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("return", 832, 838, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("result", 839, 845, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("==", 846, 848, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("true", 849, 853, SyntaxTokenKind.Constant, SyntaxTokenKind.Constant),
            OracleTokenExpectation("?", 854, 855, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(".", 856, 857, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("ok", 857, 859, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Swift implicit member"),
            OracleTokenExpectation(":", 860, 861, SyntaxTokenKind.Operator, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("self", 862, 866, SyntaxTokenKind.Plain, SyntaxTokenKind.Constant, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "Swift self keyword"),
            OracleTokenExpectation(".", 866, 867, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("defaultValue", 867, 879, SyntaxTokenKind.Function, SyntaxTokenKind.Plain, note = "STRICT: method call"),
            OracleTokenExpectation("(", 879, 880, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(")", 880, 881, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("}", 886, 887, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("#endif", 892, 898, SyntaxTokenKind.Keyword, SyntaxTokenKind.Preprocessor, note = "STRICT: conditional compilation directive"),
            OracleTokenExpectation("}", 899, 900, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
        ),
    )

    val all: List<OracleFixture> = listOf(actorsRegexStringsAndMacros)
}
