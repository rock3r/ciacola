package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.PhpSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.PhpOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhpSyntaxLexerTest {

    @Test
    fun classifiesPurePhpKeywordsTypesConstantsAttributesNumbersAndOperators() {
        val code = """
            <?php
            declare(strict_types=1);

            namespace App\Http;

            use DateTimeImmutable;

            /** PHPDoc comment */
            // Line comment
            # Hash comment
            #[Route('/api', methods: ['GET'])]
            #[Override]
            final readonly class Controller extends BaseController implements Handler {
                use LoggingTrait;

                private const int LIMIT = 1_000_000;
                public int|false ${'$'}status = false;

                PUBLIC FUNCTION handle(int|string|null ${'$'}id, array ${'$'}items, callable ${'$'}cb): string|true|never {
                    ${'$'}hex = 0xFF_00;
                    ${'$'}bin = 0b1010;
                    ${'$'}oct1 = 0755;
                    ${'$'}oct2 = 0o755;
                    ${'$'}flt = 3.14e-2;
                    ${'$'}dyn = ${'$'}${'$'}id;
                    ${'$'}cast = (int)${'$'}id;
                    ${'$'}arrCast = (array)${'$'}items;
                    ${'$'}v = @${'$'}this->cache?->get('k') ?? parent::DEFAULT_KEY;
                    ${'$'}v ??= self::class;
                    ${'$'}pow = 2 ** 3;
                    ${'$'}pow **= 2;
                    ${'$'}cmp = ${'$'}hex <=> ${'$'}bin;
                    ${'$'}eq = (${'$'}hex === 1 && ${'$'}bin !== 2 && ${'$'}oct1 <> 0);
                    ${'$'}meta = __LINE__ . __FILE__ . __DIR__ . __FUNCTION__ . __CLASS__ . __TRAIT__ . __METHOD__ . __NAMESPACE__;
                    ${'$'}fn = fn(int ${'$'}n): int => ${'$'}n + 1;
                    ${'$'}list = array(1, 2, ...${'$'}items);
                    if (${'$'}eq and TRUE xor FALSE or NULL === null) {
                        echo match (${'$'}hex) {
                            0 => 'zero',
                            default => 'other',
                        };
                    } elseif (isset(${'$'}v) && !empty(${'$'}items)) {
                        yield from ${'$'}items;
                    }
                    throw new \RuntimeException('fail');
                }
            }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(PhpSyntaxLexer, code, "php-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("<?php"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("declare"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("namespace"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("use"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/** PHPDoc comment */"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("// Line comment"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("# Hash comment"))

        assertEquals(SyntaxTokenKind.Annotation, firstKind("Route"))
        assertEquals(SyntaxTokenKind.Property, firstKind("methods"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Override"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("final"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("readonly"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("class"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Controller"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("PUBLIC"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("FUNCTION"))
        assertEquals(SyntaxTokenKind.Function, firstKind("handle"))
        assertEquals(SyntaxTokenKind.Type, firstKind("int"))
        assertEquals(SyntaxTokenKind.Type, firstKind("false"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("false") { it.startIndex > code.indexOf("= false") - 1 })
        assertEquals(SyntaxTokenKind.Type, firstKind("array"))
        assertEquals(SyntaxTokenKind.Type, firstKind("callable"))
        assertEquals(SyntaxTokenKind.Type, firstKind("null") { it.startIndex > code.indexOf("string|") })
        assertEquals(SyntaxTokenKind.Type, firstKind("true") { it.startIndex > code.indexOf("): string|") })
        assertEquals(SyntaxTokenKind.Type, firstKind("never"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("TRUE"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("FALSE"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("NULL"))

        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.14e-2"))

        assertEquals(SyntaxTokenKind.Type, firstKind("int") { it.startIndex > code.indexOf("= (int)") - 1 })
        assertEquals(SyntaxTokenKind.Type, firstKind("array") { it.startIndex > code.indexOf("= (array)") - 1 })
        assertEquals(SyntaxTokenKind.Keyword, firstKind("array") { it.startIndex > code.indexOf("array(1") - 1 })

        assertEquals(SyntaxTokenKind.Operator, firstKind("?->"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("->"))
        assertEquals(SyntaxTokenKind.Property, firstKind("cache"))
        assertEquals(SyntaxTokenKind.Function, firstKind("get"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("**"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("**="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("<=>"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("==="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("!=="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("<>"))
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("..."))
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("\\"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("@"))

        for (magic in listOf("__LINE__", "__FILE__", "__DIR__", "__FUNCTION__", "__CLASS__", "__TRAIT__", "__METHOD__", "__NAMESPACE__")) {
            assertEquals(SyntaxTokenKind.Constant, firstKind(magic), "Expected $magic to be Constant")
        }
    }

    @Test
    fun handlesStringsInterpolationsHeredocNowdocAndBackticks() {
        val code = """
            <?php
            ${'$'}s1 = 'it\'s \\ literal ${'$'}var';
            ${'$'}s2 = "hello ${'$'}name, {${'$'}obj->prop}, ${'$'}{expr}, \n \x41 \u{1F600}";
            ${'$'}s3 = "member ${'$'}user->name and index ${'$'}arr[0]";
            ${'$'}cmd = `ls -la ${'$'}dir`;
            ${'$'}h1 = <<<EOT
            Line 1 ${'$'}name and {${'$'}obj->prop}
                EOT;
            ${'$'}h2 = <<<"EOF"
            Empty-able ${'$'}x
            EOF;
            ${'$'}h3 = <<<EMPTY
            EMPTY;
            ${'$'}n1 = <<<'NOW'
            Literal ${'$'}var and \n not escaped
                NOW;
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(PhpSyntaxLexer, code, "php-strings")

        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "'it\\'s \\\\ literal \$var'" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "\$name" }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tokens.first { it.slice(code) == "{" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "\$obj" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "->" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "prop" }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tokens.first { it.slice(code) == "\${" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "expr" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "name" && it.startIndex > code.indexOf("\$user->") }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "\$dir" }.kind)
        assertTrue(tokens.any { it.slice(code).contains("EOT") && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code).contains("Literal \$var") && it.kind == SyntaxTokenKind.String })
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).startsWith("<<<EMPTY") }.kind)
    }

    @Test
    fun handlesMixedHtmlAndPhpTemplatesWithoutInvalidTokens() {
        val code = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html lang="en">
              <body>
                <div class="<?= ${'$'}cardClass ?>">
                  <?php if (${'$'}user !== null): // ok ?>
                    <h1><?= ${'$'}user->name ?></h1>
                  <? else: ?>
                    <p>Guest</p>
                  <?php endif; ?>
                </div>
              </body>
            </html>
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(PhpSyntaxLexer, code, "php-html-template")
        assertEquals(SyntaxTokenKind.Tag, tokens.first { it.slice(code) == "html" && it.startIndex > code.indexOf("<html") - 1 }.kind)
        assertEquals(SyntaxTokenKind.Tag, tokens.first { it.slice(code) == "div" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == "class" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, tokens.first { it.slice(code) == "<?=" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, tokens.first { it.slice(code) == "<?php" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, tokens.first { it.slice(code) == "?>" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, tokens.first { it.slice(code) == "<?" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "// ok " }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "if" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "endif" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "name" }.kind)
    }

    @Test
    fun prefixTruncationFuzzAndLinearPerformance() {
        val sample = """
            <?php
            #[Route('/x')]
            function f(int ${'$'}n): string {
                return "n=${'$'}n";
            }
        """.trimIndent()
        assertPrefixTruncationSafety(PhpSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(PhpSyntaxLexer, seed = 0x50485038L, iterations = 60)

        val manyPhp = buildString {
            append("<?php\n")
            repeat(1_000) { idx ->
                append("\$v").append(idx).append(" = \"item {${'$'}a->b").append(idx).append("}\";\n")
            }
        }
        val longNowdoc = "<?php\n\$s = <<<'NOW'\n" + "line \$var text\n".repeat(3_500) + "NOW;\n"
        val unclosedComment = "<?php\n/* " + "star * slash / ".repeat(2_000)
        assertLinearPerformance(
            lexer = PhpSyntaxLexer,
            pathologicalInputs = listOf(
                "many-php-strings" to manyPhp,
                "long-nowdoc" to longNowdoc,
                "unclosed-comment" to unclosedComment,
            ),
        )
    }

    @Test
    fun handlesUnclosedSimpleIndexBracketsAndPreservesHtmlAndScriptStateAcrossPhpIslands() {
        val unclosedIndexCode = """
            <?php
            ${'$'}s = "val=${'$'}a[";
            ${'$'}next = "hello";
            ${'$'}cmd = `echo ${'$'}a[`;
            ${'$'}afterCmd = 42;
            ${'$'}h = <<<EOT
            ${'$'}a[
            EOT;
            ${'$'}afterHeredoc = 123;
        """.trimIndent()

        val unclosedTokens = assertValidSnippetHasNoInvalidTokens(PhpSyntaxLexer, unclosedIndexCode, "php-unclosed-simple-index")
        assertEquals(SyntaxTokenKind.String, unclosedTokens.first { it.slice(unclosedIndexCode) == "val=" }.kind)
        assertEquals(SyntaxTokenKind.Plain, unclosedTokens.first { it.slice(unclosedIndexCode) == "\$next" }.kind)
        assertEquals(SyntaxTokenKind.String, unclosedTokens.first { it.slice(unclosedIndexCode) == "hello" }.kind)
        assertEquals(SyntaxTokenKind.Plain, unclosedTokens.first { it.slice(unclosedIndexCode) == "\$afterCmd" }.kind)
        assertEquals(SyntaxTokenKind.Number, unclosedTokens.first { it.slice(unclosedIndexCode) == "42" }.kind)
        assertEquals(SyntaxTokenKind.Plain, unclosedTokens.first { it.slice(unclosedIndexCode) == "\$afterHeredoc" }.kind)
        assertEquals(SyntaxTokenKind.Number, unclosedTokens.first { it.slice(unclosedIndexCode) == "123" }.kind)

        val mixedTemplate = """
            <div class="<?= ${'$'}cls ?>" id="main" data-x="1">Hello</div>
            <script>
            const userId = <?= ${'$'}id ?>;
            const name = "hello";
            </script>
        """.trimIndent()

        val mixedTokens = assertValidSnippetHasNoInvalidTokens(PhpSyntaxLexer, mixedTemplate, "php-html-script-islands")
        assertEquals(SyntaxTokenKind.Attribute, mixedTokens.first { it.slice(mixedTemplate) == "id" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, mixedTokens.first { it.slice(mixedTemplate) == "data-x" }.kind)
        assertEquals(SyntaxTokenKind.String, mixedTokens.first { it.slice(mixedTemplate) == "\"main\"" }.kind)
        assertEquals(SyntaxTokenKind.String, mixedTokens.first { it.slice(mixedTemplate) == "\"1\"" }.kind)
        assertEquals(SyntaxTokenKind.Plain, mixedTokens.first { it.slice(mixedTemplate) == "Hello" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, mixedTokens.first { it.slice(mixedTemplate) == "const" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, mixedTokens.last { it.slice(mixedTemplate) == "const" }.kind)
        assertEquals(SyntaxTokenKind.String, mixedTokens.first { it.slice(mixedTemplate) == "\"hello\"" }.kind)
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(PhpOracleFixtures.modernClassesAttributesAndStrings)
    }
}
