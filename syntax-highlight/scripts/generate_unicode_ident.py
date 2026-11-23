#!/usr/bin/env python3
"""
Generates syntax-highlight/src/commonMain/antlr/shared/UnicodeIdent.g4
directly from Unicode DerivedCoreProperties.txt (XID_Start and XID_Continue).

Usage:
    python3 syntax-highlight/scripts/generate_unicode_ident.py /path/to/DerivedCoreProperties.txt
"""

from __future__ import annotations

import pathlib
import re
import sys


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


def detect_unicode_version(path: pathlib.Path) -> str:
    first_line = path.read_text(encoding="utf-8").splitlines()[0]
    m = re.search(r"DerivedCoreProperties-([0-9.]+)\.txt", first_line)
    return m.group(1) if m else "unknown"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: generate_unicode_ident.py <DerivedCoreProperties.txt>", file=sys.stderr)
        return 2

    ucd_path = pathlib.Path(argv[1])
    version = detect_unicode_version(ucd_path)

    xid_start = parse_property(ucd_path, "XID_Start")
    xid_continue = parse_property(ucd_path, "XID_Continue")
    if not xid_start or not xid_start.issubset(xid_continue):
        raise SystemExit("Invalid DerivedCoreProperties.txt: XID_Start must be a non-empty subset of XID_Continue")

    xid_continue_extra = xid_continue - xid_start
    start_ranges = to_merged_ranges(xid_start)
    extra_ranges = to_merged_ranges(xid_continue_extra)

    start_lines = format_charset_lines(start_ranges)
    extra_lines = format_charset_lines(extra_ranges)

    out_path = pathlib.Path(__file__).resolve().parent.parent / "src/commonMain/antlr/shared/UnicodeIdent.g4"
    out_lines: list[str] = [
        f"// Generated from Unicode {version} DerivedCoreProperties.txt (XID_Start / XID_Continue).",
        "// Do not edit by hand; regenerate via syntax-highlight/scripts/generate_unicode_ident.py.",
        "lexer grammar UnicodeIdent;",
        "",
        "fragment UNICODE_XID_START",
        f"    : {start_lines[0]}",
    ]
    for line in start_lines[1:]:
        out_lines.append(f"    | {line}")
    out_lines.extend([
        "    ;",
        "",
        "fragment UNICODE_XID_CONTINUE",
        "    : UNICODE_XID_START",
    ])
    for line in extra_lines:
        out_lines.append(f"    | {line}")
    out_lines.append("    ;")
    out_lines.append("")

    out_path.write_text("\n".join(out_lines), encoding="utf-8")
    print(
        f"Wrote {out_path.name} (Unicode {version}): "
        f"XID_Start={len(xid_start)} cps ({len(start_ranges)} ranges), "
        f"XID_Continue_Extra={len(xid_continue_extra)} cps ({len(extra_ranges)} ranges)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
