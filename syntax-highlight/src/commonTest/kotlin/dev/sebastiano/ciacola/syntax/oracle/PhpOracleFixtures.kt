package dev.sebastiano.ciacola.syntax.oracle

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind

internal object PhpOracleFixtures {
    private val modernClassesAttributesAndStringsCode =
        "<?php\n" +
            "declare(strict_types=1);\n\n" +
            "namespace App\\Demo;\n\n" +
            "use DateTimeImmutable;\n\n" +
            "/**\n" +
            " * PHPDoc block.\n" +
            " */\n" +
            "#[Route('/api', methods: ['GET'])]\n" +
            "final readonly class UserService {\n" +
            "    public function __construct(\n" +
            "        private int \$id,\n" +
            "        private string \$name = 'guest',\n" +
            "    ) {}\n\n" +
            "    // Line comment\n" +
            "    # Hash comment\n" +
            "    public function find(int|false \$flag): string|null {\n" +
            "        \$hex = 0xFF_00;\n" +
            "        \$bin = 0b1010;\n" +
            "        \$oct = 0o755;\n" +
            "        \$num = 1_000_000;\n" +
            "        \$flt = 3.14e-2;\n" +
            "        \$x = (int)\$flag;\n" +
            "        \$s = \"hello {\$this->name}, \$hex\\n\";\n" +
            "        \$h = <<<EOT\n" +
            "        Hello \$this->id\n" +
            "        EOT;\n" +
            "        \$n = <<<'NOW'\n" +
            "        Literal \$flag\n" +
            "        NOW;\n" +
            "        \$cmd = `echo \$x`;\n" +
            "        \$c = __LINE__ . self::class . PHP_EOL;\n" +
            "        return match (\$x) {\n" +
            "            0 => 'zero',\n" +
            "            default => \$this->name ?? null,\n" +
            "        };\n" +
            "    }\n" +
            "}\n"

    val modernClassesAttributesAndStrings: OracleFixture = OracleFixture(
        name = "php-modern-classes-attributes-strings",
        language = SyntaxLanguage.Php,
        code = modernClassesAttributesAndStringsCode,
        minAgreementRatio = 0.94,
        maxOverrideShare = 0.15,
        expectations = listOf(
            OracleTokenExpectation("<?php", 0, 5, SyntaxTokenKind.Operator, SyntaxTokenKind.Preprocessor, allowedKinds = setOf(SyntaxTokenKind.Preprocessor), note = "STRICT: PHP open tag"),
            OracleTokenExpectation("declare", 6, 13, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
            OracleTokenExpectation("(", 13, 14, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("strict_types", 14, 26, SyntaxTokenKind.Constant, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 26, 27, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("1", 27, 28, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: number"),
            OracleTokenExpectation(")", 28, 29, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(";", 29, 30, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("namespace", 32, 41, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("App", 42, 45, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation("\\", 45, 46, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "PHP namespace separator"),
            OracleTokenExpectation("Demo", 46, 50, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation(";", 50, 51, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("use", 53, 56, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("DateTimeImmutable", 57, 74, SyntaxTokenKind.Type, SyntaxTokenKind.Plain),
            OracleTokenExpectation(";", 74, 75, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("/**\n * PHPDoc block.\n */", 77, 101, SyntaxTokenKind.Comment, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Comment), note = "STRICT: PHPDoc block comment"),
            OracleTokenExpectation("#[", 102, 104, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("Route", 104, 109, SyntaxTokenKind.Annotation, SyntaxTokenKind.Annotation, note = "STRICT: PHP 8 attribute"),
            OracleTokenExpectation("(", 109, 110, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("'/api'", 110, 116, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: string"),
            OracleTokenExpectation(",", 116, 117, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("methods", 118, 125, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Property), note = "PHP 8 named argument"),
            OracleTokenExpectation(":", 125, 126, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("[", 127, 128, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("'GET'", 128, 133, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("]", 133, 134, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(")", 134, 135, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("]", 135, 136, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("final", 137, 142, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("readonly", 143, 151, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("class", 152, 157, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("UserService", 158, 169, SyntaxTokenKind.Type, SyntaxTokenKind.Type, note = "STRICT: declared class"),
            OracleTokenExpectation("{", 170, 171, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("public", 176, 182, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("function", 183, 191, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("__construct", 192, 203, SyntaxTokenKind.Function, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Function), note = "STRICT: method name"),
            OracleTokenExpectation("(", 203, 204, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("private", 213, 220, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("int", 221, 224, SyntaxTokenKind.Type, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Type), note = "STRICT: built-in type"),
            OracleTokenExpectation("\$id", 225, 228, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, note = "STRICT: variable"),
            OracleTokenExpectation(",", 228, 229, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("private", 238, 245, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("string", 246, 252, SyntaxTokenKind.Type, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Type), note = "STRICT: built-in type"),
            OracleTokenExpectation("\$name", 253, 258, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 259, 260, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("'guest'", 261, 268, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: single-quoted string"),
            OracleTokenExpectation(",", 268, 269, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(")", 274, 275, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("{", 276, 277, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("}", 277, 278, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("// Line comment", 284, 299, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment, note = "STRICT: line comment"),
            OracleTokenExpectation("# Hash comment", 304, 318, SyntaxTokenKind.Comment, SyntaxTokenKind.Comment, note = "STRICT: hash comment"),
            OracleTokenExpectation("public", 323, 329, SyntaxTokenKind.Keyword, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "STRICT: keyword"),
            OracleTokenExpectation("function", 330, 338, SyntaxTokenKind.Keyword, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "STRICT: keyword"),
            OracleTokenExpectation("find", 339, 343, SyntaxTokenKind.Function, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Function), note = "STRICT: function name"),
            OracleTokenExpectation("(", 343, 344, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("int", 344, 347, SyntaxTokenKind.Type, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Type), note = "STRICT: built-in type"),
            OracleTokenExpectation("|", 347, 348, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("false", 348, 353, SyntaxTokenKind.Type, SyntaxTokenKind.Keyword, allowedKinds = setOf(SyntaxTokenKind.Type), note = "STRICT: PHP 8.2 standalone false type"),
            OracleTokenExpectation("\$flag", 354, 359, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 359, 360, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(":", 360, 361, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("string", 362, 368, SyntaxTokenKind.Type, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Type), note = "STRICT: return type"),
            OracleTokenExpectation("|", 368, 369, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Operator),
            OracleTokenExpectation("null", 369, 373, SyntaxTokenKind.Type, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Type), note = "STRICT: union null type"),
            OracleTokenExpectation("{", 374, 375, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$hex", 384, 388, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 389, 390, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("0xFF_00", 391, 398, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: hex number"),
            OracleTokenExpectation(";", 398, 399, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$bin", 408, 412, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 413, 414, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("0b1010", 415, 421, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: binary number"),
            OracleTokenExpectation(";", 421, 422, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$oct", 431, 435, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 436, 437, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("0o755", 438, 443, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: PHP 8.1 octal number"),
            OracleTokenExpectation(";", 443, 444, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$num", 453, 457, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 458, 459, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("1_000_000", 460, 469, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: underscore number"),
            OracleTokenExpectation(";", 469, 470, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$flt", 479, 483, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 484, 485, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("3.14e-2", 486, 493, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: scientific float"),
            OracleTokenExpectation(";", 493, 494, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$x", 503, 505, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 506, 507, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("(", 508, 509, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("int", 509, 512, SyntaxTokenKind.Keyword, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Type), note = "PHP cast type"),
            OracleTokenExpectation(")", 512, 513, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$flag", 513, 518, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "STRICT: variable"),
            OracleTokenExpectation(";", 518, 519, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$s", 528, 530, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 531, 532, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\"", 533, 534, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("hello ", 534, 540, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("{", 540, 541, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "PHP curly interpolation open"),
            OracleTokenExpectation("\$this", 541, 546, SyntaxTokenKind.String, SyntaxTokenKind.Plain),
            OracleTokenExpectation("->", 546, 548, SyntaxTokenKind.String, SyntaxTokenKind.Operator),
            OracleTokenExpectation("name", 548, 552, SyntaxTokenKind.String, SyntaxTokenKind.Attribute, allowedKinds = setOf(SyntaxTokenKind.Property), note = "PHP interpolated property access"),
            OracleTokenExpectation("}", 552, 553, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "PHP curly interpolation close"),
            OracleTokenExpectation(", ", 553, 555, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$hex", 555, 559, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "PHP simple string variable interpolation"),
            OracleTokenExpectation("\\n", 559, 561, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\"", 561, 562, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation(";", 562, 563, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$h", 572, 574, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 575, 576, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("<<<EOT\n", 577, 584, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: heredoc start"),
            OracleTokenExpectation("        Hello ", 584, 598, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$this", 598, 603, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "PHP heredoc interpolated variable"),
            OracleTokenExpectation("->", 603, 605, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Operator), note = "PHP heredoc interpolated arrow"),
            OracleTokenExpectation("id", 605, 607, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Property), note = "PHP heredoc interpolated property"),
            OracleTokenExpectation("\n        EOT", 607, 619, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: flexible indented heredoc close"),
            OracleTokenExpectation(";", 619, 620, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$n", 629, 631, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 632, 633, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("<<<'NOW'\n        Literal \$flag\n        NOW", 634, 676, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: nowdoc"),
            OracleTokenExpectation(";", 676, 677, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$cmd", 686, 690, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 691, 692, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("`echo ", 693, 699, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("\$x", 699, 701, SyntaxTokenKind.String, SyntaxTokenKind.String, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "PHP backtick interpolated variable"),
            OracleTokenExpectation("`", 701, 702, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation(";", 702, 703, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$c", 712, 714, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("=", 715, 716, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("__LINE__", 717, 725, SyntaxTokenKind.Constant, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Constant), note = "STRICT: magic constant"),
            OracleTokenExpectation(".", 726, 727, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("self", 728, 732, SyntaxTokenKind.Keyword, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "STRICT: self keyword"),
            OracleTokenExpectation("::", 732, 734, SyntaxTokenKind.Keyword, SyntaxTokenKind.Operator),
            OracleTokenExpectation("class", 734, 739, SyntaxTokenKind.Keyword, SyntaxTokenKind.Attribute),
            OracleTokenExpectation(".", 740, 741, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("PHP_EOL", 742, 749, SyntaxTokenKind.Plain, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Constant), note = "PHP predefined constant"),
            OracleTokenExpectation(";", 749, 750, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("return", 759, 765, SyntaxTokenKind.Keyword, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "STRICT: keyword"),
            OracleTokenExpectation("match", 766, 771, SyntaxTokenKind.Keyword, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "STRICT: PHP 8 match keyword"),
            OracleTokenExpectation("(", 772, 773, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("\$x", 773, 775, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation(")", 775, 776, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("{", 777, 778, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("0", 791, 792, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: number"),
            OracleTokenExpectation("=>", 793, 795, SyntaxTokenKind.Keyword, SyntaxTokenKind.Operator),
            OracleTokenExpectation("'zero'", 796, 802, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation(",", 802, 803, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("default", 816, 823, SyntaxTokenKind.Keyword, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "STRICT: keyword"),
            OracleTokenExpectation("=>", 824, 826, SyntaxTokenKind.Keyword, SyntaxTokenKind.Operator),
            OracleTokenExpectation("\$this", 827, 832, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Plain), note = "STRICT: variable"),
            OracleTokenExpectation("->", 832, 834, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("name", 834, 838, SyntaxTokenKind.Property, SyntaxTokenKind.Attribute, allowedKinds = setOf(SyntaxTokenKind.Property), note = "STRICT: property access"),
            OracleTokenExpectation("??", 839, 841, SyntaxTokenKind.Operator, SyntaxTokenKind.Operator),
            OracleTokenExpectation("null", 842, 846, SyntaxTokenKind.Constant, SyntaxTokenKind.Annotation, allowedKinds = setOf(SyntaxTokenKind.Constant), note = "STRICT: null constant"),
            OracleTokenExpectation(",", 846, 847, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("}", 856, 857, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation(";", 857, 858, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("}", 863, 864, SyntaxTokenKind.Plain, SyntaxTokenKind.Punctuation),
            OracleTokenExpectation("}", 865, 866, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Punctuation),
        ),
    )

    val all: List<OracleFixture> = listOf(modernClassesAttributesAndStrings)
}
