#!/usr/bin/env python3
"""Generates and verifies bundled Ciacola Syntax Color Scheme v1 (*.scheme.json) files.

Sources:
  - 6 IntelliJ Platform color schemes (Apache-2.0) from JetBrains/intellij-community
  - 5 GitHub Primer themes (MIT) from shikijs/textmate-grammars-themes

Usage:
    python3 scripts/update-syntax-schemes.py --ijpl-dir /path/to/intellij-community
    python3 scripts/update-syntax-schemes.py --check
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Any

FORMAT_ID = "ciacola-syntax-scheme"
FORMAT_VERSION = 1

IJPL_REPO_URL = "https://github.com/JetBrains/intellij-community"
IJPL_RAW_BASE = "https://raw.githubusercontent.com/JetBrains/intellij-community/{ref}/platform/platform-resources/src/"
DEFAULT_IJPL_REF = "60eb770dd92040f3f7d99dcd4359a1003ccd48c7"
GITHUB_THEMES_REPO_URL = "https://github.com/shikijs/textmate-grammars-themes"
GITHUB_RAW_BASE = "https://raw.githubusercontent.com/shikijs/textmate-grammars-themes/{ref}/packages/tm-themes/themes/"
DEFAULT_GITHUB_REF = "8f1828ebccc57930cf0adde9b35ae929584fe191"

TOKEN_ORDER = [
    "plain",
    "whitespace",
    "keyword",
    "string",
    "comment",
    "number",
    "constant",
    "function",
    "type",
    "property",
    "annotation",
    "attribute",
    "tag",
    "operator",
    "preprocessor",
    "punctuation",
    "invalid",
]

IJPL_TARGETS = [
    ("intellij-islands-dark", "Islands Dark", "themes/islands/IslandSchemeDark.xml", "Islands Dark"),
    ("intellij-dark", "IntelliJ Dark", "themes/expUI/expUI_darkScheme.xml", "Dark"),
    ("intellij-light", "IntelliJ Light", "themes/expUI/expUI_lightScheme.xml", "Light"),
    ("intellij-darcula", "Darcula", "DefaultColorSchemesManager.xml", "Darcula"),
    ("intellij-darcula-contrast", "Darcula Contrast", "themes/expUI/expUI_darculaContrastScheme.xml", "Darcula Contrast"),
    ("intellij-high-contrast", "IntelliJ High Contrast", "themes/islands/highContrastScheme.xml", "High contrast"),
]

GITHUB_TARGETS = [
    ("github-light", "GitHub Light", "github-light-default.json"),
    ("github-dark", "GitHub Dark", "github-dark-default.json"),
    ("github-dark-dimmed", "GitHub Dark Dimmed", "github-dark-dimmed.json"),
    ("github-light-high-contrast", "GitHub Light High Contrast", "github-light-high-contrast.json"),
    ("github-dark-high-contrast", "GitHub Dark High Contrast", "github-dark-high-contrast.json"),
]


@dataclass(frozen=True)
class StyleEntry:
    fg: str | None = None
    bg: str | None = None
    bold: bool = False
    italic: bool = False
    underline: bool = False
    strikethrough: bool = False

    def to_json(self) -> Any:
        if (
            self.fg is not None
            and self.bg is None
            and not self.bold
            and not self.italic
            and not self.underline
            and not self.strikethrough
        ):
            return self.fg
        out: dict[str, Any] = {}
        if self.fg is not None:
            out["fg"] = self.fg
        if self.bg is not None:
            out["bg"] = self.bg
        if self.bold:
            out["bold"] = True
        if self.italic:
            out["italic"] = True
        if self.underline:
            out["underline"] = True
        if self.strikethrough:
            out["strikethrough"] = True
        return out


def normalize_ij_hex(raw: str | None) -> str | None:
    if raw is None:
        return None
    s = raw.strip()
    if s.startswith("#"):
        s = s[1:]
    if not s or len(s) == 7 or len(s) > 8:
        return None
    if not all(c in "0123456789abcdefABCDEF" for c in s):
        return None
    s = s.upper()
    if len(s) <= 6:
        return "#" + s.zfill(6)
    if s[6:8] == "FF":
        return "#" + s[:6]
    return "#" + s


def normalize_css_hex(raw: str | None) -> str | None:
    if raw is None:
        return None
    s = raw.strip()
    if not s.startswith("#"):
        return None
    body = s[1:]
    if not all(c in "0123456789abcdefABCDEF" for c in body):
        return None
    body = body.upper()
    if len(body) == 3:
        return "#" + "".join(ch * 2 for ch in body)
    if len(body) == 4:
        r, g, b, a = (ch * 2 for ch in body)
        return "#" + r + g + b + ("" if a == "FF" else a)
    if len(body) == 6:
        return "#" + body
    if len(body) == 8:
        return "#" + (body[:6] if body[6:8] == "FF" else body)
    return None


def _srgb_to_linear(channel: int) -> float:
    c = channel / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def relative_luminance(hex_color: str) -> float:
    h = hex_color.lstrip("#")
    r = int(h[0:2], 16)
    g = int(h[2:4], 16)
    b = int(h[4:6], 16)
    return 0.2126 * _srgb_to_linear(r) + 0.7152 * _srgb_to_linear(g) + 0.0722 * _srgb_to_linear(b)


def composite_over(fg_hex: str, bg_hex: str) -> str:
    fg = fg_hex.lstrip("#")
    if len(fg) != 8:
        return fg_hex
    alpha = int(fg[6:8], 16)
    if alpha == 0xFF:
        return "#" + fg[:6]
    if alpha == 0:
        return bg_hex
    bg = bg_hex.lstrip("#")
    a = alpha / 255.0
    inv = 1.0 - a
    r = min(255, max(0, int(int(fg[0:2], 16) * a + int(bg[0:2], 16) * inv + 0.5)))
    g = min(255, max(0, int(int(fg[2:4], 16) * a + int(bg[2:4], 16) * inv + 0.5)))
    b = min(255, max(0, int(int(fg[4:6], 16) * a + int(bg[4:6], 16) * inv + 0.5)))
    return f"#{r:02X}{g:02X}{b:02X}"


def contrast_ratio(c1: str, c2: str) -> float:
    l1 = relative_luminance(c1)
    l2 = relative_luminance(c2)
    hi = max(l1, l2)
    lo = min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def strip_jsonc(text: str) -> str:
    """Strips // line comments, /* */ block comments, and trailing commas in O(N)."""
    out: list[str] = []
    n = len(text)
    i = 0
    in_string = False
    while i < n:
        ch = text[i]
        if in_string:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            i += 2
            while i < n and text[i] not in ("\n", "\r"):
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            closed = False
            while i + 1 < n:
                if text[i] == "*" and text[i + 1] == "/":
                    i += 2
                    closed = True
                    break
                i += 1
            if not closed:
                raise ValueError("Unterminated block comment")
            continue
        out.append(ch)
        i += 1

    cleaned = "".join(out)
    # Remove trailing commas outside strings
    final: list[str] = []
    m = len(cleaned)
    j = 0
    in_string = False
    while j < m:
        ch = cleaned[j]
        if in_string:
            final.append(ch)
            if ch == "\\" and j + 1 < m:
                final.append(cleaned[j + 1])
                j += 2
                continue
            if ch == '"':
                in_string = False
            j += 1
            continue
        if ch == '"':
            in_string = True
            final.append(ch)
            j += 1
            continue
        if ch == ",":
            k = j + 1
            while k < m and cleaned[k] in " \t\r\n":
                k += 1
            if k < m and cleaned[k] in ("}", "]"):
                j += 1
                continue
        final.append(ch)
        j += 1
    return "".join(final)


def parse_jsonc(text: str) -> dict[str, Any]:
    return json.loads(strip_jsonc(text))


def fetch_url(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "ciacola-scheme-updater/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode("utf-8-sig")
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Failed to fetch {url}: {exc}") from exc


def git_short_sha(directory: pathlib.Path) -> str | None:
    try:
        res = subprocess.run(
            ["git", "-C", str(directory), "rev-parse", "--short=12", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        sha = res.stdout.strip()
        return sha or None
    except (OSError, subprocess.CalledProcessError):
        return None


def format_revision(ref: str) -> str:
    s = ref.strip()
    if len(s) >= 12 and all(c in "0123456789abcdefABCDEF" for c in s):
        return s[:12].lower()
    return s


@dataclass
class IjAttributeEntry:
    base_attributes: str | None
    value: dict[str, str | None] | None


@dataclass
class IjScheme:
    name: str
    parent_scheme: str | None
    colors: dict[str, str]
    attributes: dict[str, IjAttributeEntry]


def parse_ij_schemes(xml_text: str) -> list[IjScheme]:
    root = ET.fromstring(xml_text)
    elements = [root] if root.tag == "scheme" else root.findall(".//scheme")
    parsed: list[IjScheme] = []
    for el in elements:
        raw_name = el.attrib.get("name")
        name = raw_name.strip() if raw_name is not None else ""
        if not name:
            raise ValueError("IntelliJ <scheme> element is missing a non-empty 'name' attribute")
        raw_parent = el.attrib.get("parent_scheme")
        parent = raw_parent.strip() if raw_parent is not None and raw_parent.strip() else None
        colors: dict[str, str] = {}
        for opt in el.findall("./colors/option"):
            cname = opt.attrib.get("name")
            cval = opt.attrib.get("value")
            if cname and cval:
                colors[cname] = cval
        attributes: dict[str, IjAttributeEntry] = {}
        for opt in el.findall("./attributes/option"):
            aname = opt.attrib.get("name")
            if not aname:
                continue
            base_attr = opt.attrib.get("baseAttributes")
            val_el = opt.find("value")
            val_dict: dict[str, str | None] | None = None
            if val_el is not None:
                val_dict = {}
                for vopt in val_el.findall("option"):
                    vname = vopt.attrib.get("name")
                    if vname:
                        val_dict[vname] = vopt.attrib.get("value")
            attributes[aname] = IjAttributeEntry(base_attributes=base_attr, value=val_dict)
        parsed.append(IjScheme(name=name, parent_scheme=parent, colors=colors, attributes=attributes))
    return parsed


def _effective_ij_parent(scheme: IjScheme) -> str | None:
    if scheme.parent_scheme is not None:
        return scheme.parent_scheme
    if scheme.name.lower() != "default":
        return "Default"
    return None


def resolve_ij_attr(
    schemes: dict[str, IjScheme],
    scheme_name: str,
    attr_name: str,
    visited: set[tuple[str, str]] | None = None,
) -> dict[str, str | None] | None:
    if visited is None:
        visited = set()
    key = (scheme_name, attr_name)
    if key in visited:
        return None
    visited.add(key)

    cur_name: str | None = scheme_name
    chain_seen: set[str] = set()
    while cur_name and cur_name not in chain_seen:
        chain_seen.add(cur_name)
        scheme = schemes.get(cur_name)
        if scheme is None:
            break
        entry = scheme.attributes.get(attr_name)
        if entry is not None:
            if entry.value is not None:
                return entry.value
            if entry.base_attributes:
                # baseAttributes resolves against the active scheme first, then its parent chain.
                resolved = resolve_ij_attr(schemes, scheme_name, entry.base_attributes, visited)
                if resolved is not None:
                    return resolved
        cur_name = _effective_ij_parent(scheme)
    return None


def resolve_ij_color(schemes: dict[str, IjScheme], scheme_name: str, color_name: str) -> str | None:
    cur_name: str | None = scheme_name
    seen: set[str] = set()
    while cur_name and cur_name not in seen:
        seen.add(cur_name)
        scheme = schemes.get(cur_name)
        if scheme is None:
            break
        raw = scheme.colors.get(color_name)
        if raw:
            hex_val = normalize_ij_hex(raw)
            if hex_val is not None:
                return hex_val
        cur_name = _effective_ij_parent(scheme)
    return None


def _has_high_contrast_in_chain(schemes: dict[str, IjScheme], scheme_name: str, display_name: str) -> bool:
    if "high contrast" in display_name.lower():
        return True
    cur_name: str | None = scheme_name
    seen: set[str] = set()
    while cur_name and cur_name not in seen:
        if "high contrast" in cur_name.lower():
            return True
        seen.add(cur_name)
        scheme = schemes.get(cur_name)
        if scheme is None:
            break
        if scheme.parent_scheme and "high contrast" in scheme.parent_scheme.lower():
            return True
        cur_name = _effective_ij_parent(scheme)
    return False


def _parse_uint32_or_zero(raw: str | None) -> int:
    if raw and raw.isascii() and raw.isdigit():
        val = int(raw)
        if 0 <= val <= 0x7FFFFFFF:
            return val
    return 0


def _pick_ij_style(
    schemes: dict[str, IjScheme],
    scheme_name: str,
    bg: str,
    candidates: list[str],
    *,
    min_contrast: float | None = None,
    diff_from: str | None = None,
    require_fg: bool = True,
    fg_only: bool = False,
) -> StyleEntry | None:
    for attr_name in candidates:
        opts = resolve_ij_attr(schemes, scheme_name, attr_name)
        if opts is None:
            continue
        fg = normalize_ij_hex(opts.get("FOREGROUND"))
        if require_fg and fg is None:
            continue
        if diff_from is not None and fg == diff_from:
            continue
        if min_contrast is not None and fg is not None:
            effective_fg = composite_over(fg, bg)
            if contrast_ratio(effective_fg, bg) < min_contrast:
                continue
        bg_color = None if (fg_only or attr_name == "TEXT") else normalize_ij_hex(opts.get("BACKGROUND"))
        if bg_color == bg:
            bg_color = None
        font_type = _parse_uint32_or_zero(opts.get("FONT_TYPE"))
        effect_type = _parse_uint32_or_zero(opts.get("EFFECT_TYPE"))
        effect_color = normalize_ij_hex(opts.get("EFFECT_COLOR"))
        has_effect = effect_color is not None
        return StyleEntry(
            fg=fg,
            bg=bg_color,
            bold=bool(font_type & 1),
            italic=bool(font_type & 2),
            underline=has_effect and effect_type in (1, 2, 4, 5),
            strikethrough=has_effect and effect_type == 3,
        )
    return None


def convert_ij_scheme(
    schemes: dict[str, IjScheme],
    scheme_id: str,
    display_name: str,
    xml_scheme_name: str,
    revision: str,
) -> dict[str, Any]:
    text_opts = resolve_ij_attr(schemes, xml_scheme_name, "TEXT") or {}
    bg = normalize_ij_hex(text_opts.get("BACKGROUND")) or "#FFFFFF"
    fg = normalize_ij_hex(text_opts.get("FOREGROUND")) or "#000000"

    is_light = relative_luminance(bg) >= 0.5
    is_hc = _has_high_contrast_in_chain(schemes, xml_scheme_name, display_name)
    if is_hc:
        appearance = "high-contrast-light" if is_light else "high-contrast-dark"
    else:
        appearance = "light" if is_light else "dark"

    plain_style = StyleEntry(fg=fg)
    whitespace_fg = resolve_ij_color(schemes, xml_scheme_name, "WHITESPACES")
    if whitespace_fg is None:
        comment_style = _pick_ij_style(schemes, xml_scheme_name, bg, ["DEFAULT_LINE_COMMENT", "DEFAULT_BLOCK_COMMENT"])
        whitespace_fg = comment_style.fg if comment_style and comment_style.fg else fg

    keyword_style = _pick_ij_style(schemes, xml_scheme_name, bg, ["DEFAULT_KEYWORD"]) or plain_style
    string_style = _pick_ij_style(schemes, xml_scheme_name, bg, ["DEFAULT_STRING"]) or plain_style
    comment_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["DEFAULT_LINE_COMMENT", "DEFAULT_BLOCK_COMMENT", "DEFAULT_DOC_COMMENT"],
        )
        or plain_style
    )
    number_style = _pick_ij_style(schemes, xml_scheme_name, bg, ["DEFAULT_NUMBER"]) or plain_style
    constant_style = (
        _pick_ij_style(schemes, xml_scheme_name, bg, ["DEFAULT_CONSTANT", "DEFAULT_STATIC_FIELD"]) or plain_style
    )
    fn_decl_style = _pick_ij_style(schemes, xml_scheme_name, bg, ["DEFAULT_FUNCTION_DECLARATION"])
    function_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            [
                "DEFAULT_FUNCTION_DECLARATION",
                "DEFAULT_INSTANCE_METHOD",
                "DEFAULT_STATIC_METHOD",
                "DEFAULT_FUNCTION_CALL",
            ],
        )
        or plain_style
    )
    type_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["DEFAULT_CLASS_NAME", "CLASS_NAME_ATTRIBUTES", "DEFAULT_INTERFACE_NAME"],
            diff_from=fg,
        )
        or _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["TYPE_PARAMETER_NAME_ATTRIBUTES", "DEFAULT_CLASS_NAME", "TEXT"],
        )
        or plain_style
    )
    property_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["DEFAULT_INSTANCE_FIELD", "DEFAULT_STATIC_FIELD", "KOTLIN_NAMED_ARGUMENT"],
        )
        or plain_style
    )
    annotation_style = (
        _pick_ij_style(schemes, xml_scheme_name, bg, ["ANNOTATION_NAME_ATTRIBUTES", "DEFAULT_METADATA"]) or plain_style
    )
    attribute_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["HTML_ATTRIBUTE_NAME", "XML_ATTRIBUTE_NAME", "DEFAULT_ATTRIBUTE"],
            min_contrast=2.5,
        )
        or plain_style
    )
    tag_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["XML_TAG_NAME", "HTML_TAG_NAME", "DEFAULT_TAG", "DEFAULT_KEYWORD"],
            fg_only=True,
        )
        or keyword_style
    )
    operator_style = (
        _pick_ij_style(schemes, xml_scheme_name, bg, ["DEFAULT_OPERATION_SIGN", "TEXT"]) or plain_style
    )
    preprocessor_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["OC.DIRECTIVE", "PREPROCESSOR_DIRECTIVE", "DEFAULT_METADATA", "DEFAULT_KEYWORD"],
        )
        or keyword_style
    )
    punctuation_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["DEFAULT_BRACES", "DEFAULT_PARENTHS", "DEFAULT_OPERATION_SIGN", "TEXT"],
        )
        or plain_style
    )
    invalid_style = (
        _pick_ij_style(
            schemes,
            xml_scheme_name,
            bg,
            ["BAD_CHARACTER", "WRONG_REFERENCES_ATTRIBUTES", "CONSOLE_ERROR_OUTPUT", "LOG_ERROR_OUTPUT"],
        )
        or _pick_ij_style(schemes, xml_scheme_name, bg, ["ERRORS_ATTRIBUTES"], require_fg=False)
        or plain_style
    )

    tokens: dict[str, Any] = {
        "plain": plain_style.to_json(),
        "whitespace": StyleEntry(fg=whitespace_fg).to_json(),
        "keyword": keyword_style.to_json(),
        "string": string_style.to_json(),
        "comment": comment_style.to_json(),
        "number": number_style.to_json(),
        "constant": constant_style.to_json(),
        "function": function_style.to_json(),
        "type": type_style.to_json(),
        "property": property_style.to_json(),
        "annotation": annotation_style.to_json(),
        "attribute": attribute_style.to_json(),
        "tag": tag_style.to_json(),
        "operator": operator_style.to_json(),
        "preprocessor": preprocessor_style.to_json(),
        "punctuation": punctuation_style.to_json(),
        "invalid": invalid_style.to_json(),
    }
    if fn_decl_style is not None:
        tokens["functionDeclaration"] = fn_decl_style.to_json()

    out: dict[str, Any] = {
        "format": FORMAT_ID,
        "version": FORMAT_VERSION,
        "id": scheme_id,
        "name": display_name,
        "appearance": appearance,
        "background": bg,
        "foreground": fg,
    }
    chrome_map = [
        ("selectionBackground", "SELECTION_BACKGROUND"),
        ("lineHighlightBackground", "CARET_ROW_COLOR"),
        ("lineNumberForeground", "LINE_NUMBERS_COLOR"),
        ("diffInserted", "ADDED_LINES_COLOR"),
        ("diffDeleted", "DELETED_LINES_COLOR"),
        ("diffChanged", "MODIFIED_LINES_COLOR"),
    ]
    for json_key, ij_key in chrome_map:
        cval = resolve_ij_color(schemes, xml_scheme_name, ij_key)
        if cval is not None:
            out[json_key] = cval

    out["tokens"] = tokens
    out["source"] = {
        "family": "IntelliJ",
        "upstream": IJPL_REPO_URL,
        "license": "Apache-2.0",
        "revision": revision,
    }
    return out


def _match_textmate_scopes(
    candidates: list[str],
    token_colors: list[dict[str, Any]],
    *,
    exact_only: bool = False,
) -> StyleEntry | None:
    for target in candidates:
        matches: list[tuple[int, int, str | None, str | None, bool, str]] = []
        for idx, rule in enumerate(token_colors):
            raw_scope = rule.get("scope")
            if not raw_scope:
                continue
            scopes = [s.strip() for s in raw_scope.split(",")] if isinstance(raw_scope, str) else raw_scope
            if not isinstance(scopes, list):
                continue
            settings = rule.get("settings")
            if not isinstance(settings, dict):
                continue
            fg = normalize_css_hex(settings.get("foreground"))
            bg = normalize_css_hex(settings.get("background"))
            raw_font_style = settings.get("fontStyle")
            has_font_style = isinstance(raw_font_style, str)
            if fg is None and bg is None and not has_font_style:
                continue
            max_depth = -1
            for s in scopes:
                if not isinstance(s, str):
                    continue
                scope = s.strip()
                if not scope or " " in scope:
                    continue
                is_match = (target == scope) if exact_only else (target == scope or target.startswith(scope + "."))
                if is_match:
                    depth = len(scope.split("."))
                    if depth > max_depth:
                        max_depth = depth
            if max_depth >= 0:
                matches.append((max_depth, idx, fg, bg, has_font_style, raw_font_style if has_font_style else ""))
        if not matches:
            continue
        matches.sort(key=lambda item: (item[0], item[1]))
        eff_fg: str | None = None
        eff_bg: str | None = None
        bold = False
        italic = False
        underline = False
        strikethrough = False
        for _, _, m_fg, m_bg, m_has_fs, m_fs in matches:
            if m_fg is not None:
                eff_fg = m_fg
            if m_bg is not None:
                eff_bg = m_bg
            if m_has_fs:
                fs_tokens = set(m_fs.lower().split())
                bold = "bold" in fs_tokens
                italic = "italic" in fs_tokens
                underline = "underline" in fs_tokens
                strikethrough = "strikethrough" in fs_tokens
        return StyleEntry(
            fg=eff_fg,
            bg=eff_bg,
            bold=bold,
            italic=italic,
            underline=underline,
            strikethrough=strikethrough,
        )
    return None


def _match_semantic_tokens(candidates: list[str], semantic_colors: dict[str, Any]) -> StyleEntry | None:
    for key in candidates:
        val = semantic_colors.get(key)
        if isinstance(val, str):
            fg = normalize_css_hex(val)
            if fg is not None:
                return StyleEntry(fg=fg)
        elif isinstance(val, dict):
            fg = normalize_css_hex(val.get("foreground"))
            raw_fs = val.get("fontStyle")
            has_fs = isinstance(raw_fs, str)
            bold_prop = val.get("bold") if isinstance(val.get("bold"), bool) else None
            italic_prop = val.get("italic") if isinstance(val.get("italic"), bool) else None
            underline_prop = val.get("underline") if isinstance(val.get("underline"), bool) else None
            strikethrough_prop = val.get("strikethrough") if isinstance(val.get("strikethrough"), bool) else None
            has_style = has_fs or any(p is not None for p in (bold_prop, italic_prop, underline_prop, strikethrough_prop))
            if fg is None and not has_style:
                continue
            fs = set(raw_fs.lower().split()) if has_fs else set()
            bold = bold_prop if bold_prop is not None else ("bold" in fs)
            italic = italic_prop if italic_prop is not None else ("italic" in fs)
            underline = underline_prop if underline_prop is not None else ("underline" in fs)
            strikethrough = strikethrough_prop if strikethrough_prop is not None else ("strikethrough" in fs)
            return StyleEntry(
                fg=fg,
                bold=bold,
                italic=italic,
                underline=underline,
                strikethrough=strikethrough,
            )
    return None


def convert_vscode_theme(
    raw_theme: dict[str, Any],
    scheme_id: str,
    display_name: str,
    revision: str,
) -> dict[str, Any]:
    colors: dict[str, str] = {}
    for k, v in (raw_theme.get("colors") or {}).items():
        if isinstance(k, str) and isinstance(v, str):
            norm = normalize_css_hex(v)
            if norm is not None:
                colors[k] = norm

    bg = colors.get("editor.background") or "#1E1E1E"
    fg = colors.get("editor.foreground") or "#D4D4D4"

    raw_type = str(raw_theme.get("type") or "").lower()
    raw_name = str(raw_theme.get("name") or display_name).lower()
    is_hc = raw_type in ("hclight", "hcdark", "hc", "hc-black", "hc-light") or "high contrast" in raw_name or "high-contrast" in raw_name
    if raw_type in ("light", "hclight", "hc-light"):
        is_light = True
    elif raw_type in ("dark", "hcdark", "hc", "hc-black"):
        is_light = False
    else:
        is_light = relative_luminance(bg) >= 0.5

    if is_hc:
        appearance = "high-contrast-light" if is_light else "high-contrast-dark"
    else:
        appearance = "light" if is_light else "dark"

    token_colors = raw_theme.get("tokenColors") or []
    semantic_colors = raw_theme.get("semanticTokenColors") or {}

    plain_style = StyleEntry(fg=fg)
    ws_fg = colors.get("editorWhitespace.foreground")
    if ws_fg is None:
        c = _match_textmate_scopes(["comment", "punctuation.definition.comment"], token_colors)
        ws_fg = c.fg if c and c.fg else fg

    scope_map: dict[str, tuple[list[str], list[str], list[str]]] = {
        "keyword": (["keyword.control", "keyword", "storage.type", "storage"], ["keyword"], []),
        "string": (["string.quoted", "string"], ["string"], []),
        "comment": (["comment.line", "comment.block", "comment", "punctuation.definition.comment"], ["comment"], []),
        "number": (["constant.numeric", "constant"], ["number"], []),
        "constant": (["constant.language", "variable.other.constant", "constant"], ["variable.readonly", "enumMember"], []),
        "function": (["entity.name.function", "support.function"], ["function", "method"], []),
        "type": (
            ["entity.name.type", "entity.name.class", "entity.name.struct", "entity.name.interface", "support.type", "support.class", "entity.name"],
            ["type", "class", "interface", "struct", "enum", "typeParameter"],
            [],
        ),
        "property": (
            ["meta.property-name", "support.type.property-name", "variable.other.property", "variable.other.member", "entity.name.variable.field"],
            ["property"],
            [],
        ),
        "annotation": (
            ["meta.decorator", "entity.name.decorator", "punctuation.decorator", "entity.name.function.decorator", "meta.annotation"],
            ["decorator"],
            ["storage.type.annotation", "keyword"],
        ),
        "attribute": (["entity.other.attribute-name", "meta.tag.attributes"], [], []),
        "tag": (["entity.name.tag", "support.class.component"], [], []),
        "operator": (["keyword.operator", "punctuation.accessor"], ["operator"], []),
        "preprocessor": (["meta.preprocessor", "keyword.control.directive", "keyword.other.directive", "entity.name.function.preprocessor"], ["macro"], ["keyword"]),
        "punctuation": (
            ["punctuation.separator", "punctuation.terminator", "punctuation.section", "punctuation", "meta.brace", "brackethighlighter.round", "brackethighlighter.curly"],
            [],
            [],
        ),
        "invalid": (
            ["invalid.illegal", "invalid.broken", "invalid.deprecated", "invalid.unimplemented", "invalid", "message.error"],
            [],
            [],
        ),
    }

    tokens: dict[str, Any] = {
        "plain": plain_style.to_json(),
        "whitespace": StyleEntry(fg=ws_fg).to_json(),
    }
    fn_base_entry: StyleEntry | None = None
    for kind_name in TOKEN_ORDER[2:]:
        tm_cands, sem_cands, tm_fallback = scope_map[kind_name]
        matched = (
            _match_textmate_scopes(tm_cands, token_colors)
            or _match_semantic_tokens(sem_cands, semantic_colors)
            or _match_textmate_scopes(tm_fallback, token_colors)
            or plain_style
        )
        if kind_name == "function":
            fn_base_entry = matched
        tokens[kind_name] = matched.to_json()

    tm_fn_decl = _match_textmate_scopes(
        ["entity.name.function.declaration"],
        token_colors,
        exact_only=True,
    )
    sem_fn_decl = _match_semantic_tokens(["function.declaration"], semantic_colors)
    fn_decl_style = tm_fn_decl or sem_fn_decl
    if fn_decl_style is not None and fn_decl_style.fg is None:
        inherited_fg = (sem_fn_decl.fg if sem_fn_decl is not None else None) or (
            fn_base_entry.fg if fn_base_entry is not None else None
        )
        if inherited_fg is not None:
            fn_decl_style = StyleEntry(
                fg=inherited_fg,
                bg=fn_decl_style.bg,
                bold=fn_decl_style.bold,
                italic=fn_decl_style.italic,
                underline=fn_decl_style.underline,
                strikethrough=fn_decl_style.strikethrough,
            )
    if fn_decl_style is not None:
        tokens["functionDeclaration"] = fn_decl_style.to_json()

    out: dict[str, Any] = {
        "format": FORMAT_ID,
        "version": FORMAT_VERSION,
        "id": scheme_id,
        "name": display_name,
        "appearance": appearance,
        "background": bg,
        "foreground": fg,
    }
    chrome_keys = [
        ("selectionBackground", ["editor.selectionBackground"]),
        ("lineHighlightBackground", ["editor.lineHighlightBackground"]),
        ("lineNumberForeground", ["editorLineNumber.foreground"]),
        ("diffInserted", ["editorGutter.addedBackground", "diffEditor.insertedTextBackground"]),
        ("diffDeleted", ["editorGutter.deletedBackground", "diffEditor.removedTextBackground"]),
        ("diffChanged", ["editorGutter.modifiedBackground"]),
    ]
    for out_key, candidates in chrome_keys:
        for ck in candidates:
            if ck in colors:
                out[out_key] = colors[ck]
                break

    out["tokens"] = tokens
    out["source"] = {
        "family": "GitHub",
        "upstream": GITHUB_THEMES_REPO_URL,
        "license": "MIT",
        "revision": revision,
    }
    return out


def generate_intellij_schemes(ijpl_dir: pathlib.Path | None, ijpl_ref: str) -> dict[str, dict[str, Any]]:
    if ijpl_dir is not None:
        base_dir = ijpl_dir / "platform" / "platform-resources" / "src"
        if not base_dir.is_dir():
            raise RuntimeError(f"Expected IntelliJ platform-resources directory at {base_dir}")
        revision = git_short_sha(ijpl_dir) or "local"

        def read_ij(rel: str) -> str:
            path = base_dir / rel
            if not path.is_file():
                raise RuntimeError(f"Missing IntelliJ scheme file: {path}")
            return path.read_text(encoding="utf-8-sig")
    else:
        revision = format_revision(ijpl_ref)
        base_url = IJPL_RAW_BASE.format(ref=ijpl_ref)

        def read_ij(rel: str) -> str:
            return fetch_url(base_url + rel)

    schemes_by_name: dict[str, IjScheme] = {}
    loaded_files: set[str] = set()
    rel_files = ["DefaultColorSchemesManager.xml"] + [rel for _, _, rel, _ in IJPL_TARGETS]
    for rel in rel_files:
        if rel in loaded_files:
            continue
        loaded_files.add(rel)
        for s in parse_ij_schemes(read_ij(rel)):
            schemes_by_name[s.name] = s

    results: dict[str, dict[str, Any]] = {}
    for scheme_id, display_name, _, xml_name in IJPL_TARGETS:
        if xml_name not in schemes_by_name:
            raise RuntimeError(f"Scheme '{xml_name}' not found in IntelliJ XML sources")
        results[scheme_id] = convert_ij_scheme(
            schemes=schemes_by_name,
            scheme_id=scheme_id,
            display_name=display_name,
            xml_scheme_name=xml_name,
            revision=revision,
        )
    return results


def generate_github_schemes(github_dir: pathlib.Path | None, github_ref: str) -> dict[str, dict[str, Any]]:
    if github_dir is not None:
        if not github_dir.is_dir():
            raise RuntimeError(f"GitHub themes directory does not exist: {github_dir}")
        revision = git_short_sha(github_dir) or "local"

        def read_gh(filename: str) -> str:
            p = github_dir / filename
            if not p.is_file():
                p = github_dir / "packages" / "tm-themes" / "themes" / filename
            if not p.is_file():
                raise RuntimeError(f"Missing GitHub theme file: {filename}")
            return p.read_text(encoding="utf-8-sig")
    else:
        revision = format_revision(github_ref)
        base_url = GITHUB_RAW_BASE.format(ref=github_ref)

        def read_gh(filename: str) -> str:
            return fetch_url(base_url + filename)

    results: dict[str, dict[str, Any]] = {}
    for scheme_id, display_name, filename in GITHUB_TARGETS:
        parsed = parse_jsonc(read_gh(filename))
        results[scheme_id] = convert_vscode_theme(
            raw_theme=parsed,
            scheme_id=scheme_id,
            display_name=display_name,
            revision=revision,
        )
    return results


def serialize_compact(scheme: dict[str, Any]) -> str:
    return json.dumps(scheme, separators=(",", ":"), ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Update or verify bundled syntax color schemes.")
    parser.add_argument("--ijpl-dir", type=pathlib.Path, default=None, help="Path to local intellij-community checkout")
    parser.add_argument(
        "--ijpl-ref",
        default=DEFAULT_IJPL_REF,
        help=f"GitHub ref for JetBrains/intellij-community (default: {DEFAULT_IJPL_REF})",
    )
    parser.add_argument("--github-dir", type=pathlib.Path, default=None, help="Path to local GitHub VS Code themes directory")
    parser.add_argument(
        "--github-ref",
        default=DEFAULT_GITHUB_REF,
        help=f"GitHub ref for shikijs/textmate-grammars-themes (default: {DEFAULT_GITHUB_REF})",
    )
    parser.add_argument("--source", choices=["all", "intellij", "github"], default="all", help="Which scheme family to update/check")
    parser.add_argument(
        "--out-dir",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parent.parent / "syntax-highlight" / "src" / "commonMain" / "schemes",
        help="Output directory for *.scheme.json files",
    )
    parser.add_argument("--check", action="store_true", help="Verify checked-in files match generated output without writing")
    args = parser.parse_args(argv)

    try:
        generated: dict[str, dict[str, Any]] = {}
        if args.source in ("all", "intellij"):
            generated.update(generate_intellij_schemes(args.ijpl_dir, args.ijpl_ref))
        if args.source in ("all", "github"):
            generated.update(generate_github_schemes(args.github_dir, args.github_ref))

        if args.check:
            mismatches: list[str] = []
            for scheme_id, payload in sorted(generated.items()):
                target = args.out_dir / f"{scheme_id}.scheme.json"
                expected = serialize_compact(payload)
                if not target.is_file():
                    mismatches.append(f"Missing scheme file: {target}")
                    continue
                actual = target.read_text(encoding="utf-8")
                if actual != expected:
                    mismatches.append(f"Out-of-date scheme file: {target}")
            if mismatches:
                for msg in mismatches:
                    print(msg, file=sys.stderr)
                return 1
            print(f"Verified {len(generated)} syntax color scheme(s) in {args.out_dir}")
            return 0

        args.out_dir.mkdir(parents=True, exist_ok=True)
        for scheme_id, payload in sorted(generated.items()):
            target = args.out_dir / f"{scheme_id}.scheme.json"
            content = serialize_compact(payload)
            target.write_text(content, encoding="utf-8")
            print(f"Wrote {target.name} ({len(content.encode('utf-8'))} bytes)")
        return 0
    except (RuntimeError, ValueError, OSError, ET.ParseError, json.JSONDecodeError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
