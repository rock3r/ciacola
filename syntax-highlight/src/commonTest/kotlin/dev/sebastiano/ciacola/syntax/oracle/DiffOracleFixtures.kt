package dev.sebastiano.ciacola.syntax.oracle

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind

internal object DiffOracleFixtures {
    private val unifiedContextAndNormalCode =
        "diff --git a/docs/notes.txt b/docs/notes.txt\n" +
            "index 1234567..abcdef0 100644\n" +
            "--- a/docs/notes.txt\n" +
            "+++ b/docs/notes.txt\n" +
            "@@ -1,4 +1,5 @@ section header\n" +
            " unchanged line\n" +
            "-removed line\n" +
            "+added line\n" +
            "\\ No newline at end of file\n" +
            "# patch note\n" +
            "Index: docs/readme.txt\n" +
            "===================================================================\n" +
            "*** a/ctx.txt\t2026-11-23 10:00:00\n" +
            "--- b/ctx.txt\t2026-11-23 10:01:00\n" +
            "***************\n" +
            "*** 1,3 ****\n" +
            "  same\n" +
            "! changed\n" +
            "- gone\n" +
            "--- 1,3 ----\n" +
            "  same\n" +
            "! updated\n" +
            "+ added\n" +
            "1,2c1,2\n" +
            "< old 1\n" +
            "---\n" +
            "> new 1\n" +
            "3a4\n" +
            "> appended\n"

    val unifiedContextAndNormal: OracleFixture = OracleFixture(
        name = "diff-unified-context-normal",
        language = SyntaxLanguage.Diff,
        code = unifiedContextAndNormalCode,
        minAgreementRatio = 0.95,
        maxOverrideShare = 0.10,
        expectations = listOf(
            OracleTokenExpectation("diff --git a/docs/notes.txt b/docs/notes.txt", 0, 44, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: git diff header"),
            OracleTokenExpectation("index 1234567..abcdef0 100644", 45, 74, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("---", 75, 78, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword, note = "STRICT: from-file prefix"),
            OracleTokenExpectation("a/docs/notes.txt", 79, 95, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("+++", 96, 99, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String, note = "STRICT: to-file prefix"),
            OracleTokenExpectation("b/docs/notes.txt", 100, 116, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("@@", 117, 119, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Number, note = "STRICT: unified hunk open delimiter"),
            OracleTokenExpectation("-1,4 +1,5", 120, 129, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: unified hunk range"),
            OracleTokenExpectation("@@", 130, 132, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Number, note = "STRICT: unified hunk close delimiter"),
            OracleTokenExpectation("section header", 133, 147, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("unchanged line", 149, 163, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("-", 164, 165, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword, note = "STRICT: deleted line marker"),
            OracleTokenExpectation("removed line", 165, 177, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: deleted line body"),
            OracleTokenExpectation("+", 178, 179, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String, note = "STRICT: added line marker"),
            OracleTokenExpectation("added line", 179, 189, SyntaxTokenKind.String, SyntaxTokenKind.String, note = "STRICT: added line body"),
            OracleTokenExpectation("\\ No newline at end of file", 190, 217, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Comment), note = "No-newline marker classified as Comment"),
            OracleTokenExpectation("# patch note", 218, 230, SyntaxTokenKind.Comment, SyntaxTokenKind.Plain, note = "STRICT: diff comment"),
            OracleTokenExpectation("Index", 231, 236, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation(":", 236, 237, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("docs/readme.txt", 238, 253, SyntaxTokenKind.Plain, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("===================================================================", 254, 321, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("***", 322, 325, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Plain),
            OracleTokenExpectation("a/ctx.txt\t2026-11-23 10:00:00", 326, 355, SyntaxTokenKind.Keyword, SyntaxTokenKind.Plain),
            OracleTokenExpectation("---", 356, 359, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("b/ctx.txt\t2026-11-23 10:01:00", 360, 389, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("***************", 390, 405, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Plain),
            OracleTokenExpectation("***", 406, 409, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Plain),
            OracleTokenExpectation("1,3", 410, 413, SyntaxTokenKind.Number, SyntaxTokenKind.Plain),
            OracleTokenExpectation("****", 414, 418, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Plain),
            OracleTokenExpectation("same", 421, 425, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("! ", 426, 428, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Type, note = "STRICT: context changed marker"),
            OracleTokenExpectation("changed", 428, 435, SyntaxTokenKind.Type, SyntaxTokenKind.Type, note = "STRICT: context changed body"),
            OracleTokenExpectation("- ", 436, 438, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword, note = "STRICT: context deleted marker"),
            OracleTokenExpectation("gone", 438, 442, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("---", 443, 446, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("1,3", 447, 450, SyntaxTokenKind.Number, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("----", 451, 455, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("same", 458, 462, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("! ", 463, 465, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Type),
            OracleTokenExpectation("updated", 465, 472, SyntaxTokenKind.Type, SyntaxTokenKind.Type),
            OracleTokenExpectation("+ ", 473, 475, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("added", 475, 480, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("1,2c1,2", 481, 488, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: normal diff range"),
            OracleTokenExpectation("< ", 489, 491, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword, note = "STRICT: normal diff deleted marker"),
            OracleTokenExpectation("old 1", 491, 496, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("---", 497, 500, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Type),
            OracleTokenExpectation("> ", 501, 503, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String, note = "STRICT: normal diff added marker"),
            OracleTokenExpectation("new 1", 503, 508, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("3a4", 509, 512, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("> ", 513, 515, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("appended", 515, 523, SyntaxTokenKind.String, SyntaxTokenKind.String),
        ),
    )

    private val gitEmailRenameAndBinaryCode =
        "From 1234567890abcdef1234567890abcdef12345678 Mon Sep 17 00:00:00 2001\n" +
            "From: Alice <alice@example.com>\n" +
            "Date: Mon, 23 Nov 2026 10:00:00 +0100\n" +
            "Subject: [PATCH] Rename notes\n" +
            "\n" +
            "diff --git a/old.txt b/new.txt\n" +
            "similarity index 95%\n" +
            "rename from old.txt\n" +
            "rename to new.txt\n" +
            "old mode 100644\n" +
            "new mode 100755\n" +
            "--- a/old.txt\n" +
            "+++ b/new.txt\n" +
            "@@ -1,2 +1,2 @@\n" +
            " keep\n" +
            "-old\n" +
            "+new\n" +
            "diff --git a/extra.txt b/extra.txt\n" +
            "index 0001111..2223333 100644\n" +
            "--- a/extra.txt\n" +
            "+++ b/extra.txt\n" +
            "@@ -1,3 +1,3 @@ header\n" +
            " line one\n" +
            "-line two old\n" +
            "+line two new\n" +
            " line three\n" +
            "diff --git a/img.png b/img.png\n" +
            "Binary files a/img.png and b/img.png differ\n"

    val gitEmailRenameAndBinary: OracleFixture = OracleFixture(
        name = "diff-git-email-rename-binary",
        language = SyntaxLanguage.Diff,
        code = gitEmailRenameAndBinaryCode,
        minAgreementRatio = 0.92,
        maxOverrideShare = 0.25,
        expectations = listOf(
            OracleTokenExpectation("From", 0, 4, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "Mbox From envelope keyword"),
            OracleTokenExpectation("1234567890abcdef1234567890abcdef12345678", 5, 45, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Constant), note = "Mbox commit SHA"),
            OracleTokenExpectation("From", 71, 75, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Email header name"),
            OracleTokenExpectation(":", 75, 76, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Punctuation), note = "Email header separator"),
            OracleTokenExpectation("Alice <alice@example.com>", 77, 102, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("Date", 103, 107, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Email header name"),
            OracleTokenExpectation("Mon, 23 Nov 2026 10:00:00 +0100", 109, 140, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("Subject", 141, 148, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Property), note = "Email header name"),
            OracleTokenExpectation("[PATCH] Rename notes", 150, 170, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("diff --git a/old.txt b/new.txt", 172, 202, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: git diff header"),
            OracleTokenExpectation("similarity index 95%", 203, 223, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "Git extended header"),
            OracleTokenExpectation("rename from old.txt", 224, 243, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "Git rename header"),
            OracleTokenExpectation("rename to new.txt", 244, 261, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "Git rename header"),
            OracleTokenExpectation("---", 294, 297, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword, note = "STRICT: from-file prefix"),
            OracleTokenExpectation("a/old.txt", 298, 307, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("+++", 308, 311, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String, note = "STRICT: to-file prefix"),
            OracleTokenExpectation("b/new.txt", 312, 321, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("@@", 322, 324, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Number),
            OracleTokenExpectation("-1,2 +1,2", 325, 334, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("@@", 335, 337, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Number),
            OracleTokenExpectation("keep", 339, 343, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("-", 344, 345, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("old", 345, 348, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("+", 349, 350, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("new", 350, 353, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("diff --git a/extra.txt b/extra.txt", 354, 388, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: git diff header"),
            OracleTokenExpectation("index 0001111..2223333 100644", 389, 418, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("---", 419, 422, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("a/extra.txt", 423, 434, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("+++", 435, 438, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("b/extra.txt", 439, 450, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("@@", 451, 453, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Number),
            OracleTokenExpectation("-1,3 +1,3", 454, 463, SyntaxTokenKind.Number, SyntaxTokenKind.Number),
            OracleTokenExpectation("@@", 464, 466, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Number),
            OracleTokenExpectation("header", 467, 473, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("line one", 475, 483, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("-", 484, 485, SyntaxTokenKind.Punctuation, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("line two old", 485, 497, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("+", 498, 499, SyntaxTokenKind.Punctuation, SyntaxTokenKind.String),
            OracleTokenExpectation("line two new", 499, 511, SyntaxTokenKind.String, SyntaxTokenKind.String),
            OracleTokenExpectation("line three", 513, 523, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
            OracleTokenExpectation("diff --git a/img.png b/img.png", 524, 554, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword),
            OracleTokenExpectation("Binary files a/img.png and b/img.png differ", 555, 598, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain, allowedKinds = setOf(SyntaxTokenKind.Keyword), note = "Git binary files notice"),
        ),
    )

    val all: List<OracleFixture> = listOf(
        unifiedContextAndNormal,
        gitEmailRenameAndBinary,
    )
}
