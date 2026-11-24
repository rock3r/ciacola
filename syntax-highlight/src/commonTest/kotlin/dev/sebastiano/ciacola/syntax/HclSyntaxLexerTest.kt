package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.HclSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.HclOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HclSyntaxLexerTest {

    @Test
    fun classifiesHcl2BlocksIdentifiersHyphensOperatorsAndTypes() {
        val code = """
            # Hash comment
            // Slash comment
            /* Block comment */
            terraform {
              required_providers {
                aws = { source = "hashicorp/aws" }
              }
              backend "s3" {}
            }
            provider "aws" {
              region = "us-east-1"
            }
            variable "cfg" {
              type = object({
                s = string
                n = number
                b = bool
                a = any
                l = list(string)
                st = set(number)
                m = map(bool)
                t = tuple([string, number])
              })
            }
            data "aws_ami" "ubuntu" {}
            module "vpc" {
              source = "./vpc"
            }
            resource "aws_s3_bucket" "my-bucket" {
              bucket = "my-bucket-name"
              lifecycle { create_before_destroy = true }
              dynamic "tag" {
                for_each = var.tags
                content {}
              }
              connection { type = "ssh" }
              provisioner "local-exec" { command = "true" }
            }
            ephemeral "random_password" "db" {}
            action "aws_lambda_invoke" "notify" {}
            moved {
              from = aws_s3_bucket.old
              to   = aws_s3_bucket.my-bucket
            }
            import {
              to = aws_s3_bucket.my-bucket
              id = "bucket-id"
            }
            removed {
              from = aws_s3_bucket.legacy
              lifecycle { destroy = false }
            }
            check "health" {
              assert {
                condition     = var.ok == true && 1 != 2 && 1 <= 2 && 3 >= 2 || !false
                error_message = "bad"
              }
            }
            output "calc" {
              value = [
                1 + 2 - 3 * 4 / 5 % 2,
                1e10,
                2.5e-3,
                a - b,
                1-2,
                my-hyphen-ident,
                provider::aws::arn_parse(var.arn),
                merge(var.a, var.b...),
              ]
            }
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(HclSyntaxLexer, code, "hcl-blocks-and-ops")

        fun firstKind(text: String): SyntaxTokenKind =
            tokens.first { it.slice(code) == text }.kind

        for (kw in listOf(
            "terraform", "required_providers", "backend", "provider", "variable",
            "data", "module", "resource", "lifecycle", "dynamic", "connection",
            "provisioner", "ephemeral", "action", "moved", "import", "removed",
            "check", "assert", "output",
        )) {
            assertEquals(SyntaxTokenKind.Keyword, firstKind(kw), "Expected HCL block keyword '$kw' to be Keyword")
        }

        for (ty in listOf("object", "string", "number", "bool", "any", "list", "set", "map", "tuple")) {
            assertEquals(SyntaxTokenKind.Type, firstKind(ty), "Expected HCL type '$ty' to be Type")
        }

        assertEquals(SyntaxTokenKind.Plain, firstKind("my-hyphen-ident"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1e10"))
        assertEquals(SyntaxTokenKind.Number, firstKind("2.5e-3"))
        assertEquals(SyntaxTokenKind.Function, firstKind("arn_parse"))
        assertEquals(SyntaxTokenKind.Function, firstKind("merge"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("..."))

        // `a - b` keeps `-` as Operator.
        val subStart = code.indexOf("a - b")
        val subTokens = tokens.filter { it.startIndex >= subStart && it.endIndex <= subStart + 5 && !it.slice(code).isBlank() }
        assertEquals(listOf("a", "-", "b"), subTokens.map { it.slice(code) })
        assertEquals(SyntaxTokenKind.Operator, subTokens[1].kind)

        // `1-2` keeps `1`, `-`, `2` separate.
        val numSubStart = code.indexOf("1-2")
        val numSubTokens = tokens.filter { it.startIndex >= numSubStart && it.endIndex <= numSubStart + 3 }
        assertEquals(listOf("1", "-", "2"), numSubTokens.map { it.slice(code) })
        assertEquals(listOf(SyntaxTokenKind.Number, SyntaxTokenKind.Operator, SyntaxTokenKind.Number), numSubTokens.map { it.kind })
    }

    @Test
    fun handlesNestedInterpolationsDirectivesAndHeredocs() {
        val code = """
            locals {
              esc = "quote \" \\ \n \r \t \u00E9 \U0001F600 ${'$'}${'$'}lit %%dir"
              nested = "outer ${'$'}{merge({ k = "inner ${'$'}{1 + 2}" }, { x = [<<-INNER
                hello ${'$'}{var.name}
                %{ if var.flag ~}
                yes
                %{ else ~}
                no
                %{ endif ~}
              INNER
              ])}"
              plain_heredoc = <<EOF
            line 1 ${'$'}${'$'}escaped
            ${'$'}{var.x}
            EOF
            }
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(HclSyntaxLexer, code, "hcl-nested-templates")

        val innerIdent = tokens.first { it.slice(code) == "name" }
        assertEquals(SyntaxTokenKind.Property, innerIdent.kind)

        val ifKw = tokens.first { it.slice(code) == "if" }
        val elseKw = tokens.first { it.slice(code) == "else" }
        val endifKw = tokens.first { it.slice(code) == "endif" }
        assertEquals(SyntaxTokenKind.Keyword, ifKw.kind)
        assertEquals(SyntaxTokenKind.Keyword, elseKw.kind)
        assertEquals(SyntaxTokenKind.Keyword, endifKw.kind)

        // Indented closing `INNER` splits leading spaces as Whitespace.
        val innerClosePos = code.indexOf("  INNER")
        val wsTok = tokens.first { it.startIndex == innerClosePos }
        assertEquals(SyntaxTokenKind.Whitespace, wsTok.kind)
        val delimTok = tokens.first { it.startIndex == innerClosePos + 2 }
        assertEquals(SyntaxTokenKind.Keyword, delimTok.kind)
        assertEquals("INNER", delimTok.slice(code))
    }

    @Test
    fun handlesBomCrlfUnicodeIdentifiersUnclosedConstructsAndSurrogates() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val unicodeIdent = "变量-名前 = \"𠮷 🎉\"\r\n"
        val unicodeTokens = assertValidSnippetHasNoInvalidTokens(HclSyntaxLexer, unicodeIdent, "hcl-unicode-ident")
        assertEquals(SyntaxTokenKind.Property, unicodeTokens.first().kind)
        assertEquals("变量-名前", unicodeTokens.first().slice(unicodeIdent))

        val samples = listOf(
            "\uFEFF# bom\r\n/* unclosed block comment",
            "x = \"unclosed ${'$'}{a + 1",
            "x = <<EOF\nunclosed heredoc ${'$'}{foo\n",
            "a = \"𠮷$loneHigh\" + \"${loneLow}b\"",
        )
        for ((idx, sample) in samples.withIndex()) {
            assertLosslessInvariants(HclSyntaxLexer, sample, "hcl-malformed#$idx")
        }
    }

    @Test
    fun satisfiesPrefixTruncationAndFuzzSafety() {
        assertPrefixTruncationSafety(HclSyntaxLexer, HclOracleFixtures.terraformBlocksAndTemplates.code, maxStep = 4)
        assertPrefixTruncationSafety(HclSyntaxLexer, HclOracleFixtures.expressionsSplatsAndFunctions.code, maxStep = 4)
        assertMalformedAndFuzzSafety(HclSyntaxLexer, seed = 0x4C12026L, iterations = 40)
    }

    @Test
    fun lexesPathologicalTemplatesAndHeredocsInLinearTime() {
        assertLinearPerformance(
            lexer = HclSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-heredoc-4k" to ("x = <<EOF\n" + "body \${var.a} %{ if true }y%{ endif }\n".repeat(1_000)),
                "unclosed-interp-4k" to ("x = \"" + "\${{ a = ".repeat(1_000)),
                "unclosed-comment-8k" to ("/* " + "/* unclosed ".repeat(2_000)),
                "deep-braces-4k" to ("x = " + "{ a = ".repeat(1_000) + "1" + " }".repeat(1_000)),
            ),
            maxMillisPerInput = 300L,
        )
    }
}
