#!/usr/bin/env python3
"""
Generates syntax-highlight/src/commonMain/antlr/shared/UnicodeIdent.g4
from official Unicode Character Database files:
  - DerivedCoreProperties.txt (XID_Start, XID_Continue)
  - UnicodeData.txt (General Category L, Nd, Sc, Pc)

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
    if not xid_start or not xid_start.issubset(xid_continue):
        raise SystemExit("Invalid DerivedCoreProperties.txt: XID_Start must be a non-empty subset of XID_Continue")

    categories = parse_unicode_data_categories(unicode_data_path)
    unicode_letter = set().union(*(categories.get(c, set()) for c in ("Lu", "Ll", "Lt", "Lm", "Lo")))
    unicode_digit = categories.get("Nd", set())
    unicode_currency = categories.get("Sc", set())
    unicode_connector = categories.get("Pc", set())
    if not unicode_letter or not unicode_digit or not unicode_currency or not unicode_connector:
        raise SystemExit("Invalid UnicodeData.txt: missing expected L, Nd, Sc, or Pc categories")

    xid_continue_extra = xid_continue - xid_start
    start_ranges = to_merged_ranges(xid_start)
    extra_ranges = to_merged_ranges(xid_continue_extra)
    letter_ranges = to_merged_ranges(unicode_letter)
    digit_ranges = to_merged_ranges(unicode_digit)
    currency_ranges = to_merged_ranges(unicode_currency)
    connector_ranges = to_merged_ranges(unicode_connector)

    out_path = pathlib.Path(__file__).resolve().parent.parent / "src/commonMain/antlr/shared/UnicodeIdent.g4"
    out_lines: list[str] = [
        f"// Generated from Unicode {version} DerivedCoreProperties.txt and UnicodeData.txt.",
        f"// DerivedCoreProperties-{version}.txt SHA-256: {derived_sha}",
        f"// UnicodeData-{version}.txt SHA-256: {unicode_data_sha}",
        "// Do not edit by hand; regenerate via syntax-highlight/scripts/generate_unicode_ident.py.",
        "//",
        *UNICODE_LICENSE_HEADER.splitlines(),
        "lexer grammar UnicodeIdent;",
        "",
    ]

    append_fragment(out_lines, "UNICODE_XID_START", start_ranges)
    append_fragment(out_lines, "UNICODE_XID_CONTINUE", extra_ranges, prefix_alt="UNICODE_XID_START")
    append_fragment(out_lines, "UNICODE_LETTER", letter_ranges)
    append_fragment(out_lines, "UNICODE_DIGIT", digit_ranges)
    append_fragment(out_lines, "UNICODE_CURRENCY_SYMBOL", currency_ranges)
    append_fragment(out_lines, "UNICODE_CONNECTOR_PUNCTUATION", connector_ranges)

    out_path.write_text("\n".join(out_lines), encoding="utf-8")
    print(
        f"Wrote {out_path.name} (Unicode {version}): "
        f"XID_Start={len(xid_start)} ({len(start_ranges)}r), "
        f"XID_Continue_Extra={len(xid_continue_extra)} ({len(extra_ranges)}r), "
        f"L={len(unicode_letter)} ({len(letter_ranges)}r), "
        f"Nd={len(unicode_digit)} ({len(digit_ranges)}r), "
        f"Sc={len(unicode_currency)} ({len(currency_ranges)}r), "
        f"Pc={len(unicode_connector)} ({len(connector_ranges)}r)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
