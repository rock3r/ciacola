package dev.sebastiano.ciacola.syntax.oracle

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind

internal object RubyOracleFixtures {
    private val classesMethodsSymbolsAndNumbersCode =
        "#!/usr/bin/env ruby\n" +
            "# Ruby 3.3 service module\n" +
            "module Billing\n" +
            "  MAX_RETRIES = 3\n" +
            "\n" +
            "  class Invoice < BaseRecord\n" +
            "    attr_reader :total, :status\n" +
            "    include Comparable\n" +
            "\n" +
            "    def initialize(total, status: :pending)\n" +
            "      @total = total\n" +
            "      @@count += 1\n" +
            "      \$last_status = status\n" +
            "    end\n" +
            "\n" +
            "    def self.build(amount) = new(amount)\n" +
            "\n" +
            "    def paid?\n" +
            "      @status == :paid\n" +
            "    end\n" +
            "\n" +
            "    def process!(items, &block)\n" +
            "      return nil if items.nil? || false\n" +
            "      total = 0xFF_00 + 0b1010 + 0o755 + 1_000\n" +
            "      ratio = 3.14\n" +
            "      label = total > 10 ? :high : :low\n" +
            "      items.each do |item|\n" +
            "        puts item&.name\n" +
            "      end\n" +
            "      fn = ->(x) { x + 1 }\n" +
            "      case label\n" +
            "      in :high\n" +
            "        fn.call(ratio)\n" +
            "      else\n" +
            "        raise ArgumentError, \"invalid\"\n" +
            "      end\n" +
            "    end\n" +
            "\n" +
            "    private\n" +
            "\n" +
            "    def helper_id\n" +
            "      __LINE__\n" +
            "    end\n" +
            "  end\n" +
            "end\n"

    val classesMethodsSymbolsAndNumbers: OracleFixture = OracleFixture(
        name = "ruby-classes-methods-symbols-numbers",
        language = SyntaxLanguage.Ruby,
        code = classesMethodsSymbolsAndNumbersCode,
        minAgreementRatio = 0.95,
        maxOverrideShare = 0.15,
        expectations = listOf(
            OracleTokenExpectation("#!/usr/bin/env ruby", 0, 19, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment),
            OracleTokenExpectation("# Ruby 3.3 service module", 20, 45, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment),
            OracleTokenExpectation("module", 46, 52, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("Billing", 53, 60, SyntaxTokenKind.Type, SyntaxTokenKind.Type),
            OracleTokenExpectation("MAX_RETRIES", 63, 74, SyntaxTokenKind.Constant, SyntaxTokenKind.Constant),
            OracleTokenExpectation("=", 75, 76, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("3", 77, 78, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("class", 82, 87, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("Invoice", 88, 95, SyntaxTokenKind.Type, SyntaxTokenKind.Type),
            OracleTokenExpectation("<", 96, 97, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("BaseRecord", 98, 108, SyntaxTokenKind.Type, SyntaxTokenKind.Constant),
            OracleTokenExpectation("attr_reader", 113, 124, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation(":total", 125, 131, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation(",", 131, 132, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(":status", 133, 140, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("include", 145, 152, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("Comparable", 153, 163, SyntaxTokenKind.Constant, SyntaxTokenKind.Constant),
            OracleTokenExpectation("def", 169, 172, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("initialize", 173, 183, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation("(", 183, 184, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("total", 184, 189, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(",", 189, 190, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("status", 191, 197, SyntaxTokenKind.Plain, SyntaxTokenKind.String),
            OracleTokenExpectation(":", 197, 198, SyntaxTokenKind.Constant, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(":pending", 199, 207, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation(")", 207, 208, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("@total", 215, 221, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 222, 223, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("total", 224, 229, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("@@count", 236, 243, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("+=", 244, 246, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("1", 247, 248, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("\$last_status", 255, 267, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 268, 269, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("status", 270, 276, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("end", 281, 284, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("def", 290, 293, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("self", 294, 298, SyntaxTokenKind.Function, SyntaxTokenKind.Type),
            OracleTokenExpectation(".", 298, 299, SyntaxTokenKind.Function, SyntaxTokenKind.Operator),
            OracleTokenExpectation("build", 299, 304, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation("(", 304, 305, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("amount", 305, 311, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 311, 312, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("=", 313, 314, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("new", 315, 318, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("(", 318, 319, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("amount", 319, 325, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 325, 326, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("def", 332, 335, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("paid?", 336, 341, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation("@status", 348, 355, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("==", 356, 358, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation(":paid", 359, 364, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("end", 369, 372, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("def", 378, 381, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("process!", 382, 390, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation("(", 390, 391, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("items", 391, 396, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(",", 396, 397, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("&", 398, 399, SyntaxTokenKind.Keyword, SyntaxTokenKind.Operator),
            OracleTokenExpectation("block", 399, 404, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 404, 405, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("return", 412, 418, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("nil", 419, 422, SyntaxTokenKind.Constant, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("if", 423, 425, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("items", 426, 431, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(".", 431, 432, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("nil?", 432, 436, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("||", 437, 439, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("false", 440, 445, SyntaxTokenKind.Constant, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("total", 452, 457, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 458, 459, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("0xFF_00", 460, 467, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("+", 468, 469, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("0b1010", 470, 476, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("+", 477, 478, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("0o755", 479, 484, SyntaxTokenKind.Number, SyntaxTokenKind.Plain, note = "STRICT: octal literal"),
            OracleTokenExpectation("+", 485, 486, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("1_000", 487, 492, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("ratio", 499, 504, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 505, 506, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("3.14", 507, 511, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: float literal"),
            OracleTokenExpectation("label", 518, 523, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 524, 525, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("total", 526, 531, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(">", 532, 533, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("10", 534, 536, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("?", 537, 538, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation(":high", 539, 544, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation(":", 545, 546, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(":low", 547, 551, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("items", 558, 563, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(".", 563, 564, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("each", 564, 568, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("do", 569, 571, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("|", 572, 573, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("item", 573, 577, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("|", 577, 578, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("puts", 587, 591, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation("item", 592, 596, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("&.", 596, 598, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator, note = "STRICT: safe navigation operator"),
            OracleTokenExpectation("name", 598, 602, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("end", 609, 612, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("fn", 619, 621, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 622, 623, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("->", 624, 626, SyntaxTokenKind.Function, SyntaxTokenKind.Operator, note = "STRICT: stabby lambda operator"),
            OracleTokenExpectation("(", 626, 627, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("x", 627, 628, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 628, 629, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("{", 630, 631, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("x", 632, 633, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("+", 634, 635, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("1", 636, 637, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("}", 638, 639, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("case", 646, 650, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("label", 651, 656, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("in", 663, 665, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation(":high", 666, 671, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("fn", 680, 682, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(".", 682, 683, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("call", 683, 687, SyntaxTokenKind.Function, SyntaxTokenKind.Plain),
            OracleTokenExpectation("(", 687, 688, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("ratio", 688, 693, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 693, 694, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("else", 701, 705, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("raise", 714, 719, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("ArgumentError", 720, 733, SyntaxTokenKind.Constant, SyntaxTokenKind.Constant),
            OracleTokenExpectation(",", 733, 734, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\"", 735, 736, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("invalid", 736, 743, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\"", 743, 744, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("end", 751, 754, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("end", 759, 762, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("private", 768, 775, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("def", 781, 784, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("helper_id", 785, 794, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation("__LINE__", 801, 809, SyntaxTokenKind.Keyword, SyntaxTokenKind.Plain),
            OracleTokenExpectation("end", 814, 817, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("end", 820, 823, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("end", 824, 827, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
        ),
    )

    private val stringsRegexPercentAndHeredocsCode =
        "=begin\n" +
            "Multi-line embedded documentation.\n" +
            "=end\n" +
            "re = /hello\\s+#{@name}\\/world/im\n" +
            "ratio = 10 / 2\n" +
            "rem = 10 % 3\n" +
            "words = %w[alpha [beta] gamma]\n" +
            "raw = %q(literal 'text')\n" +
            "interp = %Q{hello #{name} and #@tag}\n" +
            "rx = %r|foo/bar|i\n" +
            "cmd = `echo #{user}`\n" +
            "ch = ?a\n" +
            "ctrl = ?\\C-a\n" +
            "s1 = 'it\\'s \\\\ ok'\n" +
            "s2 = \"line1\\n#{1 + 2} #\$!\"\n" +
            "sym1 = :hello?\n" +
            "sym2 = :<<\n" +
            "sym3 = :\"dyn_#{key}\"\n" +
            "sql = <<~SQL\n" +
            "  SELECT * FROM users WHERE id = #{user_id}\n" +
            "SQL\n" +
            "lit = <<'TXT'\n" +
            "  Literal #{not_interp}\n" +
            "TXT\n" +
            "arr << \"item\"\n" +
            "class << self\n" +
            "  def singleton_ok?; true; end\n" +
            "end\n" +
            "__END__\n" +
            "arbitrary data text\n"

    val stringsRegexPercentAndHeredocs: OracleFixture = OracleFixture(
        name = "ruby-strings-regex-percent-heredocs",
        language = SyntaxLanguage.Ruby,
        code = stringsRegexPercentAndHeredocsCode,
        minAgreementRatio = 0.95,
        maxOverrideShare = 0.15,
        expectations = listOf(
            OracleTokenExpectation("=begin\nMulti-line embedded documentation.\n=end", 0, 46, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment),
            OracleTokenExpectation("re", 47, 49, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 50, 51, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("/", 52, 53, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("hello", 53, 58, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\\", 58, 59, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("s+", 59, 61, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("#{", 61, 63, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("@name", 63, 68, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("}", 68, 69, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("\\/", 69, 71, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("world", 71, 76, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("/im", 76, 79, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("ratio", 80, 85, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 86, 87, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("10", 88, 90, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("/", 91, 92, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("2", 93, 94, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("rem", 95, 98, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 99, 100, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("10", 101, 103, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("%", 104, 105, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("3", 106, 107, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("words", 108, 113, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 114, 115, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("%w[", 116, 119, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("alpha ", 119, 125, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("[", 125, 126, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("beta", 126, 130, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("]", 130, 131, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation(" gamma", 131, 137, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("]", 137, 138, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("raw", 139, 142, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 143, 144, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("%q(", 145, 148, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("literal 'text'", 148, 162, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation(")", 162, 163, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("interp", 164, 170, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 171, 172, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("%Q{", 173, 176, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("hello ", 176, 182, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("#{", 182, 184, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("name", 184, 188, SyntaxTokenKind.String, SyntaxTokenKind.Function),
            OracleTokenExpectation("}", 188, 189, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation(" and ", 189, 194, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("#@tag", 194, 199, SyntaxTokenKind.Plain, SyntaxTokenKind.String),
            OracleTokenExpectation("}", 199, 200, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("rx", 201, 203, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 204, 205, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("%r|", 206, 209, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("foo/bar", 209, 216, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("|i", 216, 218, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("cmd", 219, 222, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 223, 224, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("`", 225, 226, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("echo ", 226, 231, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("#{", 231, 233, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("user", 233, 237, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
            OracleTokenExpectation("}", 237, 238, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("`", 238, 239, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("ch", 240, 242, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 243, 244, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("?a", 245, 247, SyntaxTokenKind.Number, SyntaxTokenKind.String),
            OracleTokenExpectation("ctrl", 248, 252, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 253, 254, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("?\\C-a", 255, 260, SyntaxTokenKind.Number, SyntaxTokenKind.String),
            OracleTokenExpectation("s1", 261, 263, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 264, 265, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("'", 266, 267, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("it", 267, 269, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\\'", 269, 271, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("s ", 271, 273, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\\\\", 273, 275, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation(" ok", 275, 278, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("'", 278, 279, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("s2", 280, 282, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 283, 284, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\"", 285, 286, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("line1", 286, 291, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\\n", 291, 293, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("#{", 293, 295, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("1", 295, 296, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("+", 297, 298, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("2", 299, 300, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("}", 300, 301, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("#", 302, 303, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$!", 303, 305, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\"", 305, 306, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("sym1", 307, 311, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 312, 313, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation(":hello?", 314, 321, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("sym2", 322, 326, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 327, 328, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation(":<<", 329, 332, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("sym3", 333, 337, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 338, 339, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation(":\"", 340, 342, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("dyn_", 342, 346, SyntaxTokenKind.Constant, SyntaxTokenKind.String),
            OracleTokenExpectation("#{", 346, 348, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("key", 348, 351, SyntaxTokenKind.Constant, SyntaxTokenKind.Plain),
            OracleTokenExpectation("}", 351, 352, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("\"", 352, 353, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("sql", 354, 357, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 358, 359, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("<<~SQL", 360, 366, SyntaxTokenKind.String, SyntaxTokenKind.Operator),
            OracleTokenExpectation("  SELECT * FROM users WHERE id = #{user_id}\n", 367, 411, SyntaxTokenKind.Plain, SyntaxTokenKind.String),
            OracleTokenExpectation("SQL", 411, 414, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("lit", 415, 418, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 419, 420, SyntaxTokenKind.String, SyntaxTokenKind.Operator),
            OracleTokenExpectation("<<'TXT'", 421, 428, SyntaxTokenKind.String, SyntaxTokenKind.Operator),
            OracleTokenExpectation("  Literal #{not_interp}\n", 429, 453, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("TXT", 453, 456, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("arr", 457, 460, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("<<", 461, 463, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\"", 464, 465, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("item", 465, 469, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\"", 469, 470, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("class", 471, 476, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("<<", 477, 479, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("self", 480, 484, SyntaxTokenKind.Keyword, SyntaxTokenKind.Function),
            OracleTokenExpectation("def", 487, 490, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("singleton_ok?", 491, 504, SyntaxTokenKind.Function, SyntaxTokenKind.Function),
            OracleTokenExpectation(";", 504, 505, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("true", 506, 510, SyntaxTokenKind.Constant, SyntaxTokenKind.Keyword),
            OracleTokenExpectation(";", 510, 511, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("end", 512, 515, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("end", 516, 519, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("__END__", 520, 527, SyntaxTokenKind.Constant, SyntaxTokenKind.Comment),
            OracleTokenExpectation("arbitrary data text", 528, 547, SyntaxTokenKind.Plain, SyntaxTokenKind.Comment),
        ),
    )

    val all: List<OracleFixture> = listOf(
        classesMethodsSymbolsAndNumbers,
        stringsRegexPercentAndHeredocs,
    )
}
