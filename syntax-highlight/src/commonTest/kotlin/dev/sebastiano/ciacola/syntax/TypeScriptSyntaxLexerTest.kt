package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.TypeScriptSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.TypeScriptOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeScriptSyntaxLexerTest {

    @Test
    fun matchesOracleFixtures() {
        for (fixture in TypeScriptOracleFixtures.all) {
            assertMatchesOracleFixture(fixture)
        }
    }

    @Test
    fun classifiesTypeScriptKeywordsTypesGenericsDecoratorsAndTsx() {
        val code = """
            import type { FC } from "react";

            interface Box<out T extends Record<string, unknown>> {
              readonly value: Array<Map<string, number>>;
            }

            type Result<T> = T extends Promise<infer U> ? U : never;

            enum Mode { Fast = 1, Safe = 2 }

            @Injectable()
            export abstract class Service implements Disposable {
              private accessor count: number = 0;
              protected items: Partial<Record<string, any>> = {};
              public override toString(): string {
                const cmp = a < b && c > d;
                const val = (this.count satisfies number) as number;
                return `${'$'}{cmp}:${'$'}{val}`;
              }
            }

            export function assertBox(x: unknown): asserts x is Box<object> {
              if (!x) throw new Error("bad");
            }

            declare namespace App {
              export type Keys<T> = keyof T;
            }

            const id = <T,>(x: T): T => x;
            const pair = <K extends string, V = number>(k: K, v: V) => ({ k, v });

            export const View: FC<{ label: string }> = ({ label }) => (
              <section data-active={true}>
                <h1>{label}</h1>
              </section>
            );
        """.trimIndent()

        val tokens = assertLosslessInvariants(TypeScriptSyntaxLexer, code, "ts-features")

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        for (kw in listOf(
            "import", "type", "interface", "out", "extends", "readonly", "enum",
            "export", "abstract", "class", "implements", "private", "accessor",
            "protected", "public", "override", "satisfies", "as", "asserts", "is",
            "declare", "namespace", "keyof", "infer",
        )) {
            assertEquals(SyntaxTokenKind.Keyword, firstKind(kw), "TS keyword $kw")
        }

        for (ty in listOf("Box", "Record", "string", "unknown", "Array", "Map", "number", "Promise", "never", "Partial", "any", "object")) {
            assertEquals(SyntaxTokenKind.Type, firstKind(ty), "TS type $ty")
        }
        assertEquals(SyntaxTokenKind.Plain, firstKind("FC"))
        assertEquals(SyntaxTokenKind.Type, firstKind("FC", after = code.indexOf("View")))

        assertEquals(SyntaxTokenKind.Plain, firstKind("Fast"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("Safe"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Injectable"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("<", after = code.indexOf("cmp =")))
        assertEquals(SyntaxTokenKind.Operator, firstKind(">", after = code.indexOf("cmp =")))
        assertEquals(SyntaxTokenKind.Plain, firstKind("k", after = code.indexOf("(k: K")))
        assertEquals(SyntaxTokenKind.Plain, firstKind("v", after = code.indexOf("k: K,")))
        assertEquals(SyntaxTokenKind.Plain, firstKind("View"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("section"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("data-active"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("h1"))
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            interface Pair<A, B> { left: A; right: B; }
            const fn = <T,>(x: T): Array<T> => [x];
            const el = <Badge active={a < b && c > d}>{fn("ok")}</Badge>;
        """.trimIndent()
        assertPrefixTruncationSafety(TypeScriptSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(TypeScriptSyntaxLexer, seed = 0x54535831L, iterations = 70)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val deepGenerics = "type Deep = " + "Array<".repeat(400) + "string" + ">".repeat(400) + ";"
        val manyInterfaces = buildString {
            repeat(1_000) { idx ->
                append("interface I").append(idx).append(" { id: number; name: string; }\n")
            }
        }
        val alternatingComparisons = "const ok = " + (1..3_000).joinToString(" && ") { "a$it < b$it" } + ";"
        val largeTsx = "export const List: FC = () => (<ul>" + "<li data-id=\"1\">item</li>".repeat(800) + "</ul>);"

        assertLinearPerformance(
            lexer = TypeScriptSyntaxLexer,
            pathologicalInputs = listOf(
                "deep-generics" to deepGenerics,
                "many-interfaces" to manyInterfaces,
                "alternating-comparisons" to alternatingComparisons,
                "large-tsx" to largeTsx,
            ),
        )
    }

    @Test
    fun handlesConstTypeParamsMappedTemplateTypesConstEnumsAndAmbientModules() {
        val code = """
            const freeze = <const T,>(x: T) => x;
            type Mapped<T> = { readonly [K in keyof T]: T[K] };
            type Ev<T extends string> = `on${'$'}{Capitalize<T>}`;
            const arr = [1, 2] as const;
            const enum Dir { Up, Down }
            declare module "*.css" {
              const styles: Record<string, string>;
              export default styles;
            }
            abstract class Base { abstract override accessor v: number; }
        """.trimIndent()

        val tokens = assertLosslessInvariants(TypeScriptSyntaxLexer, code, "ts-edge-cases")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("const", after = code.indexOf("<const")))
        assertEquals(SyntaxTokenKind.Type, firstKind("T", after = code.indexOf("<const")))
        assertEquals(SyntaxTokenKind.Type, firstKind("K", after = code.indexOf("Mapped")))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("in", after = code.indexOf("Mapped")))
        assertEquals(SyntaxTokenKind.Type, firstKind("Capitalize"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("const", after = code.indexOf("as const")))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("enum"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Dir"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("Up"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("module"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("abstract"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("override"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("accessor"))
    }
}
