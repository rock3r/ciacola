#!/usr/bin/env python3
"""
Generates per-spec shared Unicode ANTLR4 lexer fragments under
syntax-highlight/src/commonMain/antlr/shared/ from official Unicode Character Database files:
  - UnicodeIdent.g4:     UNICODE_XID_START, UNICODE_XID_CONTINUE (UAX #31; Rust, Python, C/C++, HTML)
  - UnicodeJsIdent.g4:   UNICODE_ID_START, UNICODE_ID_CONTINUE (ECMA-262; JavaScript, TypeScript)
  - UnicodeJavaIdent.g4: UNICODE_JAVA_IDENT_START, UNICODE_JAVA_IDENT_PART (JLS 3.8; Java)
  - UnicodeGoIdent.g4:   UNICODE_LETTER, UNICODE_DIGIT (Go spec L / Nd; Go)

Usage:
    python3 syntax-highlight/scripts/generate_unicode_ident.py \\
        /path/to/DerivedCoreProperties.txt /path/to/UnicodeData.txt [--allow-unpinned]
"""

from __future__ import annotations

import hashlib
import pathlib
import re
import sys

PINNED_UNICODE_VERSION = "16.0.0"
PINNED_DERIVED_CORE_SHA256 = "39d35161f2954497f69e08bdb9e701493f476a3d30222de20028feda36c1dabd"
PINNED_UNICODE_DATA_SHA256 = "ff58e5823bd095166564a006e47d111130813dcf8bf234ef79fa51a870edb48f"

UNICODE_LICENSE_HEADER = """// UNICODE LICENSE V3
//
// COPYRIGHT AND PERMISSION NOTICE
//
// Copyright © 1991-2026 Unicode, Inc.
//
// NOTICE TO USER: Carefully read the following legal agreement. BY
// DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
// SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
// TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
// DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of data files and any associated documentation (the "Data Files") or
// software and any associated documentation (the "Software") to deal in the
// Data Files or Software without restriction, including without limitation
// the rights to use, copy, modify, merge, publish, distribute, and/or sell
// copies of the Data Files or Software, and to permit persons to whom the
// Data Files or Software are furnished to do so, provided that either (a)
// this copyright and permission notice appear with all copies of the Data
// Files or Software, or (b) this copyright and permission notice appear in
// associated Documentation.
//
// THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
// KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
// THIRD PARTY RIGHTS.
//
// IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
// BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
// OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
// WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
// ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
// FILES OR SOFTWARE.
//
// Except as contained in this notice, the name of a copyright holder shall
// not be used in advertising or otherwise to promote the sale, use or other
// dealings in these Data Files or Software without prior written
// authorization of the copyright holder."""


def sha256_file(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_property(path: pathlib.Path, prop: str) -> set[int]:
    codepoints: set[int] = set()
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = [p.strip() for p in line.split(";")]
        if len(parts) < 2 or parts[1] != prop:
            continue
        span = parts[0]
        if ".." in span:
            start_s, end_s = span.split("..", 1)
            start, end = int(start_s, 16), int(end_s, 16)
        else:
            start = end = int(span, 16)
        codepoints.update(range(start, end + 1))
    return codepoints


def parse_unicode_data_categories(path: pathlib.Path) -> dict[str, set[int]]:
    by_cat: dict[str, set[int]] = {}
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    i = 0
    while i < len(lines):
        parts = lines[i].split(";")
        cp = int(parts[0], 16)
        name = parts[1]
        cat = parts[2]
        if name.endswith(", First>"):
            if i + 1 >= len(lines):
                raise SystemExit(f"Unclosed UnicodeData range starting at U+{cp:04X}")
            next_parts = lines[i + 1].split(";")
            end_cp = int(next_parts[0], 16)
            if not next_parts[1].endswith(", Last>") or next_parts[2] != cat:
                raise SystemExit(f"Mismatched UnicodeData range at U+{cp:04X}..U+{end_cp:04X}")
            by_cat.setdefault(cat, set()).update(range(cp, end_cp + 1))
            i += 2
        else:
            by_cat.setdefault(cat, set()).add(cp)
            i += 1
    return by_cat


def to_merged_ranges(codepoints: set[int]) -> list[tuple[int, int]]:
    merged: list[tuple[int, int]] = []
    for cp in sorted(codepoints):
        if merged and merged[-1][1] + 1 == cp:
            merged[-1] = (merged[-1][0], cp)
        else:
            merged.append((cp, cp))
    return merged


def fmt_cp(cp: int) -> str:
    if cp <= 0xFFFF:
        return f"\\u{cp:04X}"
    return f"\\u{{{cp:X}}}"


def fmt_range(start: int, end: int) -> str:
    if start == end:
        return fmt_cp(start)
    return f"{fmt_cp(start)}-{fmt_cp(end)}"


def format_charset_lines(ranges: list[tuple[int, int]], per_line: int = 8) -> list[str]:
    lines: list[str] = []
    for i in range(0, len(ranges), per_line):
        chunk = "".join(fmt_range(a, b) for a, b in ranges[i : i + per_line])
        lines.append(f"[{chunk}]")
    return lines


def append_fragment(
    out_lines: list[str],
    name: str,
    ranges: list[tuple[int, int]],
    prefix_alt: str | None = None,
) -> None:
    charset_lines = format_charset_lines(ranges)
    out_lines.append(f"fragment {name}")
    if prefix_alt is not None:
        out_lines.append(f"    : {prefix_alt}")
        for line in charset_lines:
            out_lines.append(f"    | {line}")
    else:
        out_lines.append(f"    : {charset_lines[0]}")
        for line in charset_lines[1:]:
            out_lines.append(f"    | {line}")
    out_lines.append("    ;")
    out_lines.append("")


def write_grammar_file(
    out_dir: pathlib.Path,
    grammar_name: str,
    version: str,
    derived_sha: str,
    unicode_data_sha: str,
    fragments: list[tuple[str, list[tuple[int, int]], str | None]],
) -> pathlib.Path:
    out_lines: list[str] = [
        f"// Generated from Unicode {version} DerivedCoreProperties.txt and UnicodeData.txt.",
        f"// DerivedCoreProperties-{version}.txt SHA-256: {derived_sha}",
        f"// UnicodeData-{version}.txt SHA-256: {unicode_data_sha}",
        "// Do not edit by hand; regenerate via syntax-highlight/scripts/generate_unicode_ident.py.",
        "//",
        *UNICODE_LICENSE_HEADER.splitlines(),
        f"lexer grammar {grammar_name};",
        "",
    ]
    for frag_name, ranges, prefix_alt in fragments:
        append_fragment(out_lines, frag_name, ranges, prefix_alt=prefix_alt)

    out_path = out_dir / f"{grammar_name}.g4"
    out_path.write_text("\n".join(out_lines), encoding="utf-8")
    return out_path


def detect_unicode_version(path: pathlib.Path) -> str:
    first_line = path.read_text(encoding="utf-8").splitlines()[0]
    m = re.search(r"DerivedCoreProperties-([0-9.]+)\.txt", first_line)
    return m.group(1) if m else "unknown"


def main(argv: list[str]) -> int:
    args = argv[1:]
    allow_unpinned = False
    if "--allow-unpinned" in args:
        allow_unpinned = True
        args = [a for a in args if a != "--allow-unpinned"]

    if len(args) != 2:
        print(
            "Usage: generate_unicode_ident.py <DerivedCoreProperties.txt> <UnicodeData.txt> [--allow-unpinned]",
            file=sys.stderr,
        )
        return 2

    derived_path = pathlib.Path(args[0])
    unicode_data_path = pathlib.Path(args[1])

    version = detect_unicode_version(derived_path)
    derived_sha = sha256_file(derived_path)
    unicode_data_sha = sha256_file(unicode_data_path)

    if not allow_unpinned:
        if version != PINNED_UNICODE_VERSION:
            raise SystemExit(
                f"Expected pinned Unicode {PINNED_UNICODE_VERSION}, got {version} "
                "(pass --allow-unpinned to upgrade intentionally)"
            )
        if derived_sha != PINNED_DERIVED_CORE_SHA256:
            raise SystemExit(
                f"DerivedCoreProperties.txt SHA-256 mismatch: expected {PINNED_DERIVED_CORE_SHA256}, got {derived_sha}"
            )
        if unicode_data_sha != PINNED_UNICODE_DATA_SHA256:
            raise SystemExit(
                f"UnicodeData.txt SHA-256 mismatch: expected {PINNED_UNICODE_DATA_SHA256}, got {unicode_data_sha}"
            )

    xid_start = parse_property(derived_path, "XID_Start")
    xid_continue = parse_property(derived_path, "XID_Continue")
    id_start = parse_property(derived_path, "ID_Start")
    id_continue = parse_property(derived_path, "ID_Continue")
    if not xid_start or not xid_start.issubset(xid_continue):
        raise SystemExit("Invalid DerivedCoreProperties.txt: XID_Start must be a non-empty subset of XID_Continue")
    if not id_start or not id_start.issubset(id_continue):
        raise SystemExit("Invalid DerivedCoreProperties.txt: ID_Start must be a non-empty subset of ID_Continue")

    categories = parse_unicode_data_categories(unicode_data_path)
    unicode_letter = set().union(*(categories.get(c, set()) for c in ("Lu", "Ll", "Lt", "Lm", "Lo")))
    unicode_digit = categories.get("Nd", set())
    java_start = set().union(*(categories.get(c, set()) for c in ("Lu", "Ll", "Lt", "Lm", "Lo", "Nl", "Sc", "Pc")))
    # JLS 3.8 Character.isJavaIdentifierPart (excluding U+FEFF BOM which JavaLexer matches as WS,
    # and excluding non-printing ISO control chars 0x00..0x1F / 0x7F..0x9F).
    java_part = java_start | unicode_digit | categories.get("Mc", set()) | categories.get("Mn", set()) | (
        categories.get("Cf", set()) - {0xFEFF}
    )
    if not unicode_letter or not unicode_digit or not java_start or not java_start.issubset(java_part):
        raise SystemExit("Invalid UnicodeData.txt: missing expected General Category sets")

    out_dir = pathlib.Path(__file__).resolve().parent.parent / "src/commonMain/antlr/shared"

    write_grammar_file(
        out_dir=out_dir,
        grammar_name="UnicodeIdent",
        version=version,
        derived_sha=derived_sha,
        unicode_data_sha=unicode_data_sha,
        fragments=[
            ("UNICODE_XID_START", to_merged_ranges(xid_start), None),
            ("UNICODE_XID_CONTINUE", to_merged_ranges(xid_continue - xid_start), "UNICODE_XID_START"),
        ],
    )
    write_grammar_file(
        out_dir=out_dir,
        grammar_name="UnicodeJsIdent",
        version=version,
        derived_sha=derived_sha,
        unicode_data_sha=unicode_data_sha,
        fragments=[
            ("UNICODE_ID_START", to_merged_ranges(id_start), None),
            ("UNICODE_ID_CONTINUE", to_merged_ranges(id_continue - id_start), "UNICODE_ID_START"),
        ],
    )
    write_grammar_file(
        out_dir=out_dir,
        grammar_name="UnicodeJavaIdent",
        version=version,
        derived_sha=derived_sha,
        unicode_data_sha=unicode_data_sha,
        fragments=[
            ("UNICODE_JAVA_IDENT_START", to_merged_ranges(java_start), None),
            ("UNICODE_JAVA_IDENT_PART", to_merged_ranges(java_part - java_start), "UNICODE_JAVA_IDENT_START"),
        ],
    )
    write_grammar_file(
        out_dir=out_dir,
        grammar_name="UnicodeGoIdent",
        version=version,
        derived_sha=derived_sha,
        unicode_data_sha=unicode_data_sha,
        fragments=[
            ("UNICODE_LETTER", to_merged_ranges(unicode_letter), None),
            ("UNICODE_DIGIT", to_merged_ranges(unicode_digit), None),
        ],
    )

    print(
        f"Generated Unicode {version} shared grammars: "
        f"XID=({len(xid_start)}/{len(xid_continue)}), "
        f"JS ID=({len(id_start)}/{len(id_continue)}), "
        f"Java=({len(java_start)}/{len(java_part)}), "
        f"Go=(L:{len(unicode_letter)}/Nd:{len(unicode_digit)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
