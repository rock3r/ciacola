"""Pure-Python Keynote PDF texture atlas parser + RGBA PNG extractor."""

from __future__ import annotations

import math
import re
import struct
import zlib
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


# ---------------------------------------------------------------------------
# Color space helpers (Display P3 -> sRGB, CMYK/Gray -> sRGB, alpha hex)
# ---------------------------------------------------------------------------

def _srgb_to_linear(c: float) -> float:
    c = max(0.0, min(1.0, c))
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(c: float) -> float:
    c = max(0.0, min(1.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1.0 / 2.4)) - 0.055


def p3_to_srgb(r: float, g: float, b: float) -> Tuple[float, float, float]:
    rl, gl, bl = _srgb_to_linear(r), _srgb_to_linear(g), _srgb_to_linear(b)
    rs = 1.2249401 * rl - 0.2249401 * gl
    gs = -0.0420569 * rl + 1.0420569 * gl
    bs = -0.0196376 * rl - 0.0786361 * gl + 1.0982737 * bl
    return (_linear_to_srgb(rs), _linear_to_srgb(gs), _linear_to_srgb(bs))


def rgb_to_hex(r: float, g: float, b: float, alpha: float = 1.0) -> str:
    ri = max(0, min(255, round(r * 255.0)))
    gi = max(0, min(255, round(g * 255.0)))
    bi = max(0, min(255, round(b * 255.0)))
    ai = max(0, min(255, round(alpha * 255.0)))
    if ai < 255:
        return f"#{ai:02X}{ri:02X}{gi:02X}{bi:02X}"
    return f"#{ri:02X}{gi:02X}{bi:02X}"


def apply_alpha_hex(hex_color: str, alpha: float) -> str:
    if alpha >= 0.996:
        return hex_color
    ai = max(0, min(255, round(alpha * 255.0)))
    raw = hex_color.lstrip("#")
    if len(raw) == 8:
        old_a = int(raw[:2], 16) / 255.0
        ai = max(0, min(255, round(old_a * alpha * 255.0)))
        raw = raw[2:]
    return f"#{ai:02X}{raw}"


def color_to_info(vals: List[float], cs_kind: str) -> Dict[str, Any]:
    if cs_kind == "Gray" or len(vals) == 1:
        g = max(0.0, min(1.0, vals[0]))
        return {"hex": rgb_to_hex(g, g, g), "colorSpace": "Gray"}
    if cs_kind == "CMYK" or len(vals) == 4:
        c, m, y, k = [max(0.0, min(1.0, v)) for v in vals[:4]]
        r = (1.0 - c) * (1.0 - k)
        g = (1.0 - m) * (1.0 - k)
        b = (1.0 - y) * (1.0 - k)
        return {"hex": rgb_to_hex(r, g, b), "colorSpace": "CMYK"}
    r, g, b = vals[0], vals[1], vals[2]
    if cs_kind == "DisplayP3":
        sr, sg, sb = p3_to_srgb(r, g, b)
        return {"hex": rgb_to_hex(sr, sg, sb), "colorSpace": "DisplayP3", "p3Hex": rgb_to_hex(r, g, b)}
    return {"hex": rgb_to_hex(r, g, b), "colorSpace": cs_kind or "sRGB"}


# ---------------------------------------------------------------------------
# 2D Affine matrix helpers
# ---------------------------------------------------------------------------

def mat_mul(m1: List[float], m2: List[float]) -> List[float]:
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return [
        a2 * a1 + b2 * c1,
        a2 * b1 + b2 * d1,
        c2 * a1 + d2 * c1,
        c2 * b1 + d2 * d1,
        e2 * a1 + f2 * c1 + e1,
        e2 * b1 + f2 * d1 + f1,
    ]


def mat_apply(m: List[float], x: float, y: float) -> Tuple[float, float]:
    a, b, c, d, e, f = m
    return (a * x + c * y + e, b * x + d * y + f)


# ---------------------------------------------------------------------------
# Minimal PNG writer (pure Python stdlib)
# ---------------------------------------------------------------------------

def write_png(path: Path, width: int, height: int, rgba_bytes: bytes, has_alpha: bool = True) -> None:
    """Write 8-bit RGB or RGBA PNG cleanly using pure stdlib zlib."""
    color_type = 6 if has_alpha else 2
    stride = width * (4 if has_alpha else 3)
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type None
        row_start = y * stride
        raw.extend(rgba_bytes[row_start : row_start + stride])
    compressed = zlib.compress(bytes(raw), level=6)

    def chunk(tag: bytes, payload: bytes) -> bytes:
        crc = zlib.crc32(tag + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", compressed) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


# ---------------------------------------------------------------------------
# PDF low-level parser
# ---------------------------------------------------------------------------

_OBJ_RE = re.compile(rb"(\d+)\s+(\d+)\s+obj\b(.*?)\bendobj\b", re.S)
_STREAM_RE = re.compile(rb"stream\r?\n(.*?)\r?\n?endstream", re.S)
_STREAM_START_RE = re.compile(rb"stream(?:\r\n|\n|\r)")
_DIRECT_LENGTH_RE = re.compile(rb"/Length\s+(\d+)(?!\s+\d+\s+R)\b")


def _decode_pdf_stream(body: bytes) -> bytes:
    is_flate = b"/FlateDecode" in body
    candidates: List[bytes] = []

    # Prefer exact /Length slice first: regex endstream matching can strip a
    # trailing 0x0A/0x0D byte belonging to a FlateDecode Adler-32 checksum.
    sm = _STREAM_START_RE.search(body)
    if sm:
        lm = _DIRECT_LENGTH_RE.search(body[: sm.start()])
        if lm:
            length = int(lm.group(1))
            end_pos = sm.end() + length
            if length >= 0 and end_pos <= len(body):
                exact_raw = body[sm.end() : end_pos]
                if not is_flate:
                    return exact_raw
                candidates.append(exact_raw)

    m = _STREAM_RE.search(body)
    if m:
        raw = m.group(1)
        if not is_flate:
            return raw
        candidates.append(raw)
        stripped = raw.rstrip(b"\r\n")
        if stripped != raw:
            candidates.append(stripped)

    if not candidates:
        return b""

    for cand in candidates:
        try:
            return zlib.decompress(cand)
        except Exception:
            pass
    for cand in candidates:
        try:
            return zlib.decompress(cand, -zlib.MAX_WBITS)
        except Exception:
            pass
    return b""


_LIGATURE_TABLE = str.maketrans({
    "\ufb00": "ff",
    "\ufb01": "fi",
    "\ufb02": "fl",
    "\ufb03": "ffi",
    "\ufb04": "ffl",
})


def _parse_tounicode_cmap(raw: bytes) -> Dict[int, str]:
    text = raw.decode("latin1", errors="ignore")
    cmap: Dict[int, str] = {}

    def decode_u16(hex_str: str) -> str:
        clean = re.sub(r"\s+", "", hex_str)
        if not clean:
            return ""
        if len(clean) % 2 == 1:
            clean = "0" + clean
        b = bytes.fromhex(clean)
        s = b.decode("utf-16-be", errors="replace") if len(b) >= 2 else b.decode("latin1", errors="replace")
        return s.translate(_LIGATURE_TABLE)

    for block in re.findall(r"beginbfchar(.*?)endbfchar", text, re.S):
        for src_h, dst_h in re.findall(r"<([0-9A-Fa-f\s]+)>\s*<([0-9A-Fa-f\s]+)>", block):
            src_clean = re.sub(r"\s+", "", src_h)
            if src_clean:
                cmap[int(src_clean, 16)] = decode_u16(dst_h)

    for block in re.findall(r"beginbfrange(.*?)endbfrange", text, re.S):
        for m in re.finditer(
            r"<([0-9A-Fa-f\s]+)>\s*<([0-9A-Fa-f\s]+)>\s*(\[[^\]]+\]|<[0-9A-Fa-f\s]+>)",
            block,
            re.S,
        ):
            lo_s = re.sub(r"\s+", "", m.group(1))
            hi_s = re.sub(r"\s+", "", m.group(2))
            if not lo_s or not hi_s:
                continue
            lo, hi, rhs = int(lo_s, 16), int(hi_s, 16), m.group(3)
            if rhs.startswith("["):
                vals = re.findall(r"<([0-9A-Fa-f\s]+)>", rhs)
                for idx, vh in enumerate(vals):
                    if lo + idx <= hi:
                        cmap[lo + idx] = decode_u16(vh)
            else:
                dst_clean = re.sub(r"\s+", "", rhs.strip("<>"))
                if not dst_clean:
                    continue
                if lo == hi:
                    cmap[lo] = decode_u16(dst_clean)
                else:
                    base_val = int(dst_clean, 16)
                    byte_len = max(2, len(dst_clean) // 2)
                    for code in range(lo, min(hi + 1, lo + 4096)):
                        offset = code - lo
                        try:
                            b = (base_val + offset).to_bytes(byte_len, "big")
                            cmap[code] = b.decode("utf-16-be", errors="replace").translate(_LIGATURE_TABLE)
                        except Exception:
                            pass
    return cmap


def _parse_pdf_dict_refs(body: bytes, key: bytes) -> Dict[str, int]:
    m = re.search(rb"/" + key + rb"\s*<<(.*?)>>", body, re.S)
    if not m:
        return {}
    return {
        k.decode("latin1"): int(v)
        for k, v in re.findall(rb"/([^\s/<>\[\]()]+)\s+(\d+)\s+\d+\s+R", m.group(1))
    }


def _tokenize_content_stream(data: bytes) -> List[Tuple[str, Any]]:
    tokens: List[Tuple[str, Any]] = []
    n = len(data)
    i = 0
    ws = b" \t\r\n\x0c\x00"
    delims = b" \t\r\n\x0c\x00()<>[]{}/%"

    while i < n:
        c = data[i]
        if c in ws:
            i += 1
            continue
        if c == 37:  # %
            while i < n and data[i] not in (10, 13):
                i += 1
            continue
        if c == 40:  # (
            depth = 1
            i += 1
            buf = bytearray()
            while i < n and depth > 0:
                ch = data[i]
                if ch == 92 and i + 1 < n:
                    i += 1
                    nxt = data[i]
                    esc = {110: 10, 114: 13, 116: 9, 98: 8, 102: 12}
                    if nxt in esc:
                        buf.append(esc[nxt])
                    elif nxt in (10, 13):
                        if nxt == 13 and i + 1 < n and data[i + 1] == 10:
                            i += 1
                    elif 48 <= nxt <= 55:
                        oct_s = bytes([nxt])
                        for _ in range(2):
                            if i + 1 < n and 48 <= data[i + 1] <= 55:
                                i += 1
                                oct_s += bytes([data[i]])
                            else:
                                break
                        buf.append(int(oct_s, 8) & 0xFF)
                    else:
                        buf.append(nxt)
                elif ch == 40:
                    depth += 1
                    buf.append(ch)
                elif ch == 41:
                    depth -= 1
                    if depth > 0:
                        buf.append(ch)
                else:
                    buf.append(ch)
                i += 1
            tokens.append(("str", bytes(buf)))
            continue
        if c == 60:  # <
            if i + 1 < n and data[i + 1] == 60:
                tokens.append(("dict_start", "<<"))
                i += 2
                continue
            j = data.find(b">", i + 1)
            if j == -1:
                break
            hx = re.sub(rb"\s+", b"", data[i + 1 : j])
            if len(hx) % 2 == 1:
                hx += b"0"
            try:
                tokens.append(("str", bytes.fromhex(hx.decode("ascii"))))
            except Exception:
                tokens.append(("str", b""))
            i = j + 1
            continue
        if c == 62 and i + 1 < n and data[i + 1] == 62:
            tokens.append(("dict_end", ">>"))
            i += 2
            continue
        if c == 91:
            tokens.append(("arr_start", "["))
            i += 1
            continue
        if c == 93:
            tokens.append(("arr_end", "]"))
            i += 1
            continue
        if c == 47:
            j = i + 1
            while j < n and data[j] not in delims:
                j += 1
            tokens.append(("name", data[i + 1 : j].decode("latin1")))
            i = j
            continue
        j = i
        while j < n and data[j] not in delims:
            j += 1
        word = data[i:j].decode("latin1")
        i = j
        if word:
            try:
                if "." in word:
                    tokens.append(("num", float(word)))
                else:
                    tokens.append(("num", int(word)))
            except ValueError:
                tokens.append(("op", word))
    return tokens


def _fmt_coord(v: float) -> str:
    r = round(v, 2)
    if r == 0.0:
        return "0"
    if float(int(round(r))) == r:
        return str(int(round(r)))
    return f"{r:.2f}".rstrip("0").rstrip(".")


def _classify_svg_path(
    cmds: List[Tuple[str, List[float]]],
    min_x: float,
    min_y: float,
    w: float,
    h: float,
) -> Tuple[str, Optional[float], str]:
    """
    Classify normalized path commands into ('rect'|'ellipse'|'line'|'path', corner_radius, svg_d).
    Commands are already in top-down stage coordinates; we shift by (-min_x, -min_y).
    """
    # Build normalized SVG path string relative to (min_x, min_y)
    parts: List[str] = []
    for op, pts in cmds:
        if op == "Z":
            parts.append("Z")
        elif op in ("M", "L"):
            parts.append(f"{op} {_fmt_coord(pts[0] - min_x)} {_fmt_coord(pts[1] - min_y)}")
        elif op == "C":
            parts.append(
                f"C {_fmt_coord(pts[0] - min_x)} {_fmt_coord(pts[1] - min_y)} "
                f"{_fmt_coord(pts[2] - min_x)} {_fmt_coord(pts[3] - min_y)} "
                f"{_fmt_coord(pts[4] - min_x)} {_fmt_coord(pts[5] - min_y)}"
            )
    svg_d = " ".join(parts)

    non_z = [c for c in cmds if c[0] != "Z"]
    has_z = any(c[0] == "Z" for c in cmds)
    ops = [c[0] for c in non_z]

    # Axis-aligned rect: M + 3..4 L on corners
    if ops in (["M", "L", "L", "L"], ["M", "L", "L", "L", "L"]) and has_z:
        verts = [(c[1][0], c[1][1]) for c in non_z[:4]]
        xs_ok = all(min(abs(vx - min_x), abs(vx - (min_x + w))) <= 0.5 for vx, _ in verts)
        ys_ok = all(min(abs(vy - min_y), abs(vy - (min_y + h))) <= 0.5 for _, vy in verts)
        if xs_ok and ys_ok:
            return ("rect", None, svg_d)

    # Ellipse: M + 4 C (+ optional Z) touching all 4 midpoints of bbox
    if ops == ["M", "C", "C", "C", "C"] and w > 0.5 and h > 0.5:
        cx, cy = min_x + w / 2.0, min_y + h / 2.0
        endpoints = [(non_z[0][1][0], non_z[0][1][1])] + [(c[1][4], c[1][5]) for c in non_z[1:]]
        mid_ok = all(
            (abs(px - cx) <= 1.2 and min(abs(py - min_y), abs(py - (min_y + h))) <= 1.2)
            or (abs(py - cy) <= 1.2 and min(abs(px - min_x), abs(px - (min_x + w))) <= 1.2)
            for px, py in endpoints
        )
        if mid_ok:
            return ("ellipse", None, svg_d)

    # Rounded rect: M + axis-aligned L edges + 4..12 C corners (including Apple Keynote squircles & pills)
    c_cmds = [c for c in non_z if c[0] == "C"]
    l_cmds = [c for c in non_z if c[0] == "L"]
    if 4 <= len(c_cmds) <= 12 and 2 <= len(l_cmds) <= 5 and has_z and w > 1 and h > 1:
        # Check all L segments are horizontal or vertical on bbox boundary
        prev = (non_z[0][1][0], non_z[0][1][1])
        all_axis = True
        radii: List[float] = []
        for op, pts in non_z[1:]:
            if op == "L":
                nx, ny = pts[0], pts[1]
                if not (
                    (abs(ny - prev[1]) <= 0.6 and min(abs(ny - min_y), abs(ny - (min_y + h))) <= 0.8)
                    or (abs(nx - prev[0]) <= 0.6 and min(abs(nx - min_x), abs(nx - (min_x + w))) <= 0.8)
                ):
                    all_axis = False
                    break
                prev = (nx, ny)
            elif op == "C":
                nx, ny = pts[4], pts[5]
                if len(c_cmds) == 4:
                    rx, ry = abs(nx - prev[0]), abs(ny - prev[1])
                    if abs(rx - ry) > 1.5 or rx < 0.5:
                        all_axis = False
                        break
                    radii.append((rx + ry) / 2.0)
                prev = (nx, ny)
        if all_axis and len(c_cmds) == 4 and len(radii) == 4 and max(radii) - min(radii) <= 1.5:
            r_avg = round(sum(radii) / 4.0, 2)
            if r_avg <= min(w, h) / 2.0 + 1.0:
                return ("rect", min(r_avg, round(min(w, h) / 2.0, 2)), svg_d)
        elif all_axis and len(c_cmds) > 4 and non_z[0][0] == "M" and len(non_z) > 1 and non_z[1][0] == "L":
            m0x, m0y = non_z[0][1][0], non_z[0][1][1]
            lead = m0x - min_x
            if abs(m0y - min_y) <= 0.6 and abs(non_z[1][1][1] - min_y) <= 0.6 and lead > 1.0:
                r_squircle = round(lead / 1.5287, 1)
                if 0.5 <= r_squircle <= min(w, h) / 2.0 + 1.5:
                    return ("rect", min(r_squircle, round(min(w, h) / 2.0, 2)), svg_d)

    # Simple axis-aligned horizontal or vertical line (left->right or top->bottom)
    if ops == ["M", "L"] and not has_z:
        x0, y0 = non_z[0][1]
        x1, y1 = non_z[1][1]
        if (abs(y1 - y0) <= 0.5 and x1 >= x0) or (abs(x1 - x0) <= 0.5 and y1 >= y0):
            return ("line", None, svg_d)

    return ("path", None, svg_d)


class KeynotePdfAtlas:
    """Parse multi-page Keynote KPF PDF texture atlases and extract embedded images."""

    def __init__(self, pdf_path: str):
        self.pdf_path = pdf_path
        data = Path(pdf_path).read_bytes()
        self.objs: Dict[int, bytes] = {}
        for m in _OBJ_RE.finditer(data):
            self.objs[int(m.group(1))] = m.group(3)
        self.page_obj_nums = self._resolve_pages()
        self._font_cache: Dict[int, Dict[str, Any]] = {}
        self._cs_cache: Dict[int, str] = {}
        self._gs_cache: Dict[int, Tuple[float, float]] = {}
        self._page_cache: Dict[int, Dict[str, Any]] = {}

    def _resolve_pages(self) -> List[int]:
        pages: List[int] = []
        pages_roots = [
            num for num, body in self.objs.items()
            if re.search(rb"/Type\s*/Pages\b", body) and b"/Parent" not in body
        ]
        if not pages_roots:
            for num, body in sorted(self.objs.items()):
                if re.search(rb"/Type\s*/Page\b", body) and not re.search(rb"/Type\s*/Pages\b", body):
                    pages.append(num)
            return pages

        def walk(node_num: int) -> None:
            body = self.objs.get(node_num, b"")
            if re.search(rb"/Type\s*/Pages\b", body):
                km = re.search(rb"/Kids\s*\[([^\]]+)\]", body, re.S)
                if km:
                    for ref in re.findall(rb"(\d+)\s+\d+\s+R", km.group(1)):
                        walk(int(ref))
            elif re.search(rb"/Type\s*/Page\b", body):
                pages.append(node_num)

        walk(pages_roots[0])
        return pages

    def _classify_colorspace(self, obj_num: int) -> str:
        if obj_num in self._cs_cache:
            return self._cs_cache[obj_num]
        body = self.objs.get(obj_num, b"")
        kind = "sRGB"
        if b"/ICCBased" in body:
            ref = re.search(rb"/ICCBased\s+(\d+)\s+\d+\s+R", body)
            if ref:
                icc_body = self.objs.get(int(ref.group(1)), b"")
                nm = re.search(rb"/N\s+(\d+)", icc_body)
                n_comp = int(nm.group(1)) if nm else 3
                if n_comp == 1:
                    kind = "Gray"
                elif n_comp == 4:
                    kind = "CMYK"
                else:
                    icc = _decode_pdf_stream(icc_body)
                    if b"Display P3" in icc or b"D\x00i\x00s\x00p\x00l\x00a\x00y\x00 \x00P\x003" in icc or b"P3" in icc[:512]:
                        kind = "DisplayP3"
                    elif b"Generic Gray" in icc or b"Gray" in icc[:256]:
                        kind = "Gray"
                    elif b"CMYK" in icc[:256]:
                        kind = "CMYK"
                    elif b"sRGB" in icc:
                        kind = "sRGB"
        elif b"/DeviceGray" in body or b"/CalGray" in body:
            kind = "Gray"
        elif b"/DeviceCMYK" in body:
            kind = "CMYK"
        self._cs_cache[obj_num] = kind
        return kind

    def _parse_extgstate(self, obj_num: int) -> Tuple[Optional[float], Optional[float]]:
        if obj_num in self._gs_cache:
            return self._gs_cache[obj_num]
        body = self.objs.get(obj_num, b"")
        ca_m = re.search(rb"/ca\s+([0-9.eE+-]+)", body)
        CA_m = re.search(rb"/CA\s+([0-9.eE+-]+)", body)
        res = (
            float(ca_m.group(1)) if ca_m else None,
            float(CA_m.group(1)) if CA_m else None,
        )
        self._gs_cache[obj_num] = res
        return res

    def _parse_font(self, obj_num: int) -> Dict[str, Any]:
        if obj_num in self._font_cache:
            return self._font_cache[obj_num]
        body = self.objs.get(obj_num, b"")
        bf_m = re.search(rb"/BaseFont\s*/([^\s/<>\[\]()]+)", body)
        base_font = bf_m.group(1).decode("latin1") if bf_m else "Unknown"
        if "+" in base_font and len(base_font.split("+", 1)[0]) == 6:
            base_font = base_font.split("+", 1)[1]
        enc_m = re.search(rb"/Encoding\s*/([^\s/<>\[\]()]+)", body)
        is_type0 = bool(re.search(rb"/Subtype\s*/Type0\b", body))
        has_encoding = enc_m is not None
        encoding = enc_m.group(1).decode("latin1") if enc_m else ("Identity-H" if is_type0 else "")
        to_uni: Dict[int, str] = {}
        tu_m = re.search(rb"/ToUnicode\s+(\d+)\s+\d+\s+R", body)
        if tu_m:
            to_uni = _parse_tounicode_cmap(_decode_pdf_stream(self.objs.get(int(tu_m.group(1)), b"")))
        info = {
            "baseFont": base_font,
            "encoding": encoding,
            "hasEncoding": has_encoding,
            "isType0": is_type0,
            "toUnicode": to_uni,
        }
        self._font_cache[obj_num] = info
        return info

    def _decode_text_bytes(self, raw: bytes, font_info: Dict[str, Any]) -> str:
        cmap = font_info["toUnicode"]
        if cmap:
            is_2byte = any(k > 255 for k in cmap) or (font_info.get("isType0") and font_info["encoding"] == "Identity-H")
            if is_2byte and len(raw) % 2 == 0:
                out2 = [cmap.get((raw[idx] << 8) | raw[idx + 1], "") for idx in range(0, len(raw), 2)]
                if any(out2) or not raw:
                    return "".join(out2)
            out1 = []
            has_enc = bool(font_info.get("hasEncoding"))
            for b in raw:
                if b in cmap:
                    out1.append(cmap[b])
                elif has_enc:
                    try:
                        out1.append(bytes([b]).decode("mac_roman").translate(_LIGATURE_TABLE))
                    except Exception:
                        out1.append(chr(b) if 32 <= b < 127 else "")
                # Else: Quartz custom glyph-indexed subset font with ToUnicode and no /Encoding;
                # unmapped byte (e.g. 0x21) is a ligature spacer glyph -> emit empty string.
            return "".join(out1)
        if font_info["encoding"] in ("MacRomanEncoding", ""):
            return raw.decode("mac_roman", errors="replace").translate(_LIGATURE_TABLE)
        return raw.decode("latin1", errors="replace").translate(_LIGATURE_TABLE)

    def _parse_shading(self, obj_num: int) -> Dict[str, Any]:
        body = self.objs.get(obj_num, b"")
        st_m = re.search(rb"/ShadingType\s+(\d+)", body)
        stype = int(st_m.group(1)) if st_m else 2
        cs_kind = "sRGB"
        cs_m = re.search(rb"/ColorSpace\s+(\d+)\s+\d+\s+R", body)
        if cs_m:
            cs_kind = self._classify_colorspace(int(cs_m.group(1)))
        coords_m = re.search(rb"/Coords\s*\[([^\]]+)\]", body)
        coords = [float(x) for x in coords_m.group(1).decode("latin1").split()] if coords_m else []

        stops: List[Dict[str, Any]] = []
        fn_m = re.search(rb"/Function\s+(\d+)\s+\d+\s+R", body)
        if fn_m:
            fbody = self.objs.get(int(fn_m.group(1)), b"")
            c0_m = re.search(rb"/C0\s*\[([^\]]+)\]", fbody)
            c1_m = re.search(rb"/C1\s*\[([^\]]+)\]", fbody)
            if c0_m and c1_m:
                c0 = color_to_info([float(x) for x in c0_m.group(1).decode("latin1").split()], cs_kind)
                c1 = color_to_info([float(x) for x in c1_m.group(1).decode("latin1").split()], cs_kind)
                stops = [{"at": 0, "color": c0["hex"]}, {"at": 1, "color": c1["hex"]}]
            elif b"/FunctionType 3" in fbody:
                # Stitched multi-stop gradient
                fns_m = re.search(rb"/Functions\s*\[([^\]]+)\]", fbody)
                bnds_m = re.search(rb"/Bounds\s*\[([^\]]*)\]", fbody)
                sub_refs = [int(x) for x in re.findall(rb"(\d+)\s+\d+\s+R", fns_m.group(1))] if fns_m else []
                bounds = [float(x) for x in bnds_m.group(1).decode("latin1").split()] if bnds_m else []
                pts = [0.0] + bounds + [1.0]
                for idx, ref in enumerate(sub_refs):
                    sb = self.objs.get(ref, b"")
                    sc0 = re.search(rb"/C0\s*\[([^\]]+)\]", sb)
                    sc1 = re.search(rb"/C1\s*\[([^\]]+)\]", sb)
                    if sc0 and idx == 0:
                        c = color_to_info([float(x) for x in sc0.group(1).decode("latin1").split()], cs_kind)
                        stops.append({"at": round(pts[idx], 4), "color": c["hex"]})
                    if sc1 and idx + 1 < len(pts):
                        c = color_to_info([float(x) for x in sc1.group(1).decode("latin1").split()], cs_kind)
                        stops.append({"at": round(pts[idx + 1], 4), "color": c["hex"]})
            else:
                # Sampled Function (FunctionType 0): Keynote's primary gradient representation!
                raw_fn = _decode_pdf_stream(fbody)
                sm = re.search(rb"/Size\s*\[\s*(\d+)\s*\]", fbody)
                comp = 1 if cs_kind == "Gray" else (4 if cs_kind == "CMYK" else 3)
                n_samples = int(sm.group(1)) if sm else (len(raw_fn) // comp if comp else 0)
                if n_samples > 0 and len(raw_fn) >= n_samples * comp:
                    sample_fracs = [0.0, 0.25, 0.5, 0.75, 1.0]
                    pts = []
                    for frac in sample_fracs:
                        idx = min(n_samples - 1, round(frac * (n_samples - 1)))
                        off = idx * comp
                        vals = [raw_fn[off + k] / 255.0 for k in range(comp)]
                        ci = color_to_info(vals, cs_kind)
                        pts.append((frac, ci["hex"], vals))
                    stops.append({"at": 0, "color": pts[0][1]})
                    for frac, hx, vals in pts[1:-1]:
                        lin = [pts[0][2][k] * (1.0 - frac) + pts[-1][2][k] * frac for k in range(len(vals))]
                        if max(abs(vals[k] - lin[k]) for k in range(len(vals))) > 0.02:
                            stops.append({"at": round(frac, 4), "color": hx})
                    stops.append({"at": 1, "color": pts[-1][1]})
        return {"shadingType": stype, "colorSpace": cs_kind, "coords": coords, "stops": stops}

    def inspect_image_obj(self, obj_num: int) -> Dict[str, Any]:
        """Inspect pixel statistics & decode RGB/RGBA bytes of an Image XObject."""
        body = self.objs.get(obj_num, b"")
        wm = re.search(rb"/Width\s+(\d+)", body)
        hm = re.search(rb"/Height\s+(\d+)", body)
        w = int(wm.group(1)) if wm else 0
        h = int(hm.group(1)) if hm else 0
        bpc_m = re.search(rb"/BitsPerComponent\s+(\d+)", body)
        bpc = int(bpc_m.group(1)) if bpc_m else 8
        smask_m = re.search(rb"/SMask\s+(\d+)\s+\d+\s+R", body)
        smask_num = int(smask_m.group(1)) if smask_m else None
        is_jpeg = b"/DCTDecode" in body
        is_flate = b"/FlateDecode" in body

        cs_kind = "sRGB"
        cs_ref = re.search(rb"/ColorSpace\s+(\d+)\s+\d+\s+R", body)
        if cs_ref:
            cs_kind = self._classify_colorspace(int(cs_ref.group(1)))
        elif b"/DeviceGray" in body:
            cs_kind = "Gray"

        raw = _decode_pdf_stream(body) if is_flate else b""
        smask_raw = _decode_pdf_stream(self.objs.get(smask_num, b"")) if smask_num else b""

        # Sample unique RGB values to distinguish Quartz black drop-shadows from real graphics
        uniq_rgb = set()
        if raw and w > 0 and h > 0 and bpc == 8:
            px_count = w * h
            step = max(1, px_count // 400)
            if len(raw) >= px_count * 3:
                for i in range(0, px_count, step):
                    uniq_rgb.add((raw[i * 3], raw[i * 3 + 1], raw[i * 3 + 2]))
            elif len(raw) >= px_count:
                for i in range(0, px_count, step):
                    g = raw[i]
                    uniq_rgb.add((g, g, g))

        is_solid_black_mask = (
            smask_num is not None
            and len(uniq_rgb) == 1
            and next(iter(uniq_rgb)) == (0, 0, 0)
        )
        return {
            "width": w,
            "height": h,
            "bpc": bpc,
            "csKind": cs_kind,
            "isJpeg": is_jpeg,
            "isFlate": is_flate,
            "hasSMask": smask_num is not None,
            "smaskNum": smask_num,
            "uniqueSampleColors": len(uniq_rgb),
            "isSolidBlackMask": is_solid_black_mask,
            "raw": raw,
            "smaskRaw": smask_raw,
        }

    def export_image_obj_as_png(self, obj_num: int, out_path: Path) -> bool:
        """Export a PDF Image XObject (Flate RGB/Gray + optional SMask) as a crisp PNG."""
        info = self.inspect_image_obj(obj_num)
        w, h = info["width"], info["height"]
        if w <= 0 or h <= 0 or not info["raw"]:
            return False
        raw = info["raw"]
        smask = info["smaskRaw"]
        px_count = w * h
        is_rgb = len(raw) >= px_count * 3
        is_gray = (not is_rgb) and len(raw) >= px_count
        if not is_rgb and not is_gray:
            return False
        has_alpha = bool(smask) and len(smask) >= px_count
        out = bytearray(px_count * (4 if has_alpha else 3))
        if is_rgb:
            if has_alpha:
                for i in range(px_count):
                    out[i * 4] = raw[i * 3]
                    out[i * 4 + 1] = raw[i * 3 + 1]
                    out[i * 4 + 2] = raw[i * 3 + 2]
                    out[i * 4 + 3] = smask[i]
            else:
                out[:] = raw[: px_count * 3]
        else:
            if has_alpha:
                for i in range(px_count):
                    g = raw[i]
                    out[i * 4] = g
                    out[i * 4 + 1] = g
                    out[i * 4 + 2] = g
                    out[i * 4 + 3] = smask[i]
            else:
                for i in range(px_count):
                    g = raw[i]
                    out[i * 3] = g
                    out[i * 3 + 1] = g
                    out[i * 3 + 2] = g
        write_png(out_path, w, h, bytes(out), has_alpha=has_alpha)
        return True

    def export_image_obj(self, obj_num: int, out_dir: Path, stem: str) -> Optional[str]:
        """Export a PDF Image XObject as PNG (Flate) or JPG (DCTDecode); return filename."""
        body = self.objs.get(obj_num, b"")
        if b"/DCTDecode" in body:
            raw = _decode_pdf_stream(body)
            if raw:
                out_dir.mkdir(parents=True, exist_ok=True)
                fname = f"{stem}.jpg"
                (out_dir / fname).write_bytes(raw)
                return fname
            return None
        fname = f"{stem}.png"
        if self.export_image_obj_as_png(obj_num, out_dir / fname):
            return fname
        return None

    def parse_page(self, page_index: int) -> Dict[str, Any]:
        if page_index in self._page_cache:
            return self._page_cache[page_index]
        if page_index < 0 or page_index >= len(self.page_obj_nums):
            return {"width": 0.0, "height": 0.0, "textRuns": [], "shapes": [], "images": [], "gradients": [], "fonts": []}

        pnum = self.page_obj_nums[page_index]
        pbody = self.objs[pnum]
        mb_m = re.search(rb"/MediaBox\s*\[([^\]]+)\]", pbody)
        mb = [float(x) for x in mb_m.group(1).decode("latin1").split()] if mb_m else [0.0, 0.0, 1920.0, 1080.0]
        page_w, page_h = mb[2] - mb[0], mb[3] - mb[1]

        res_body = pbody
        res_ref = re.search(rb"/Resources\s+(\d+)\s+\d+\s+R", pbody)
        if res_ref:
            res_body = self.objs.get(int(res_ref.group(1)), b"")

        content_bytes = b""
        cm_single = re.search(rb"/Contents\s+(\d+)\s+\d+\s+R", pbody)
        cm_arr = re.search(rb"/Contents\s*\[([^\]]+)\]", pbody)
        if cm_single:
            content_bytes = _decode_pdf_stream(self.objs.get(int(cm_single.group(1)), b""))
        elif cm_arr:
            parts = []
            for ref in re.findall(rb"(\d+)\s+\d+\s+R", cm_arr.group(1)):
                parts.append(_decode_pdf_stream(self.objs.get(int(ref), b"")))
            content_bytes = b"\n".join(parts)

        out: Dict[str, Any] = {
            "width": round(page_w, 2),
            "height": round(page_h, 2),
            "textRuns": [],
            "shapes": [],
            "images": [],
            "gradients": [],
            "fonts": set(),
            "drawOrder": [],
        }
        self._interpret_stream(content_bytes, res_body, [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], page_w, page_h, out)
        out["fonts"] = sorted(out["fonts"])
        self._page_cache[page_index] = out
        return out

    def _interpret_stream(
        self,
        stream_bytes: bytes,
        res_body: bytes,
        init_ctm: List[float],
        page_w: float,
        page_h: float,
        out: Dict[str, Any],
        depth: int = 0,
    ) -> None:
        if depth > 4 or not stream_bytes:
            return
        cs_map = {k: self._classify_colorspace(v) for k, v in _parse_pdf_dict_refs(res_body, b"ColorSpace").items()}
        font_refs = _parse_pdf_dict_refs(res_body, b"Font")
        xobj_refs = _parse_pdf_dict_refs(res_body, b"XObject")
        shading_refs = _parse_pdf_dict_refs(res_body, b"Shading")
        gs_refs = _parse_pdf_dict_refs(res_body, b"ExtGState")

        tokens = _tokenize_content_stream(stream_bytes)

        state: Dict[str, Any] = {
            "ctm": list(init_ctm),
            "fill_cs": "sRGB",
            "stroke_cs": "sRGB",
            "fill_color": {"hex": "#000000", "colorSpace": "sRGB"},
            "stroke_color": {"hex": "#000000", "colorSpace": "sRGB"},
            "fill_alpha": 1.0,
            "stroke_alpha": 1.0,
            "line_width": 1.0,
            "dash": None,
            "font_info": {"baseFont": "Unknown", "encoding": "MacRomanEncoding", "toUnicode": {}},
            "font_size": 12.0,
            "tc": 0.0,
            "tl": 0.0,
            "clip_bbox": None,
        }
        stack: List[Dict[str, Any]] = []
        tm = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]
        tlm = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]

        path_pts: List[Tuple[float, float]] = []
        path_cmds: List[Tuple[str, List[float]]] = []
        cur_pt: Tuple[float, float] = (0.0, 0.0)
        pending_clip = False

        operands: List[Any] = []
        arr_stack: List[List[Any]] = []
        in_dict_depth = 0
        dict_tokens: List[Tuple[str, Any]] = []

        def push_op(val: Any) -> None:
            if arr_stack:
                arr_stack[-1].append(val)
            else:
                operands.append(val)

        def to_stage(px: float, py: float) -> Tuple[float, float]:
            sx, sy = mat_apply(state["ctm"], px, py)
            return (sx, page_h - sy)

        def emit_text(raw_str: str) -> None:
            if not raw_str:
                return
            eff = mat_mul(state["ctm"], tm)
            scale_y = math.hypot(eff[2], eff[3])
            font_pt = round(abs(state["font_size"]) * scale_y, 2)
            stage_x = round(eff[4], 2)
            stage_y = round(page_h - eff[5], 2)
            bf = state["font_info"]["baseFont"]
            out["fonts"].add(bf)
            col = apply_alpha_hex(state["fill_color"]["hex"], state["fill_alpha"])
            rec = {
                "text": raw_str,
                "font": bf,
                "fontSize": font_pt,
                "colorHex": col,
                "colorSpace": state["fill_color"]["colorSpace"],
                "x": stage_x,
                "baselineY": stage_y,
                "tracking": round(state["tc"], 4) if state["tc"] else 0.0,
                "drawIndex": len(out["drawOrder"]),
            }
            out["textRuns"].append(rec)
            out["drawOrder"].append(("text", len(out["textRuns"]) - 1))

        def flush_path(fill: bool, stroke: bool) -> None:
            nonlocal path_pts, path_cmds, pending_clip
            if not path_pts:
                path_cmds = []
                pending_clip = False
                return
            xs = [p[0] for p in path_pts]
            ys = [p[1] for p in path_pts]
            min_x, max_x = min(xs), max(xs)
            min_y, max_y = min(ys), max(ys)
            w, h = max_x - min_x, max_y - min_y
            geom_kind, corner_r, svg_d = _classify_svg_path(path_cmds, min_x, min_y, w, h)
            entry: Dict[str, Any] = {
                "kind": geom_kind,
                "x": round(min_x, 2),
                "y": round(min_y, 2),
                "width": round(w, 2),
                "height": round(h, 2),
                "svgPath": svg_d,
                "drawIndex": len(out["drawOrder"]),
            }
            if corner_r is not None:
                entry["cornerRadius"] = corner_r
            if fill:
                entry["fillHex"] = apply_alpha_hex(state["fill_color"]["hex"], state["fill_alpha"])
                entry["fillColorSpace"] = state["fill_color"]["colorSpace"]
            if stroke:
                entry["strokeHex"] = apply_alpha_hex(state["stroke_color"]["hex"], state["stroke_alpha"])
                ctm_scale = math.hypot(state["ctm"][0], state["ctm"][1])
                entry["strokeWidth"] = round(state["line_width"] * ctm_scale, 2)
                if state["dash"]:
                    entry["dash"] = state["dash"]
            out["shapes"].append(entry)
            out["drawOrder"].append(("shape", len(out["shapes"]) - 1))
            if pending_clip:
                state["clip_bbox"] = (min_x, min_y, w, h, geom_kind, corner_r, svg_d)
                pending_clip = False
            path_pts = []
            path_cmds = []

        for kind, val in tokens:
            if kind == "dict_start":
                in_dict_depth += 1
                dict_tokens = []
                continue
            if kind == "dict_end":
                in_dict_depth = max(0, in_dict_depth - 1)
                if in_dict_depth == 0:
                    push_op(("dict", dict_tokens))
                    dict_tokens = []
                continue
            if in_dict_depth > 0:
                dict_tokens.append((kind, val))
                continue
            if kind == "arr_start":
                arr_stack.append([])
                continue
            if kind == "arr_end":
                if arr_stack:
                    push_op(("arr", arr_stack.pop()))
                continue
            if kind in ("num", "name", "str"):
                push_op((kind, val))
                continue

            op = val
            nums = [v for k, v in operands if k == "num"]
            names = [v for k, v in operands if k == "name"]

            if op == "q":
                stack.append({
                    **state,
                    "ctm": list(state["ctm"]),
                    "fill_color": dict(state["fill_color"]),
                    "stroke_color": dict(state["stroke_color"]),
                })
            elif op == "Q":
                if stack:
                    state = stack.pop()
            elif op == "cm" and len(nums) >= 6:
                state["ctm"] = mat_mul(state["ctm"], [float(x) for x in nums[-6:]])
            elif op == "gs" and names:
                gref = gs_refs.get(names[-1])
                if gref is not None:
                    ca, CA = self._parse_extgstate(gref)
                    if ca is not None:
                        state["fill_alpha"] = ca
                    if CA is not None:
                        state["stroke_alpha"] = CA
            elif op == "cs" and names:
                n0 = names[-1]
                state["fill_cs"] = cs_map.get(n0, "Gray" if n0 == "DeviceGray" else ("CMYK" if n0 == "DeviceCMYK" else "sRGB"))
            elif op == "CS" and names:
                n0 = names[-1]
                state["stroke_cs"] = cs_map.get(n0, "Gray" if n0 == "DeviceGray" else ("CMYK" if n0 == "DeviceCMYK" else "sRGB"))
            elif op in ("sc", "scn") and nums:
                state["fill_color"] = color_to_info([float(x) for x in nums], state["fill_cs"])
            elif op in ("SC", "SCN") and nums:
                state["stroke_color"] = color_to_info([float(x) for x in nums], state["stroke_cs"])
            elif op == "rg" and len(nums) >= 3:
                state["fill_cs"] = "sRGB"
                state["fill_color"] = color_to_info([float(x) for x in nums[-3:]], "sRGB")
            elif op == "RG" and len(nums) >= 3:
                state["stroke_cs"] = "sRGB"
                state["stroke_color"] = color_to_info([float(x) for x in nums[-3:]], "sRGB")
            elif op == "g" and nums:
                state["fill_cs"] = "Gray"
                state["fill_color"] = color_to_info([float(nums[-1])], "Gray")
            elif op == "G" and nums:
                state["stroke_cs"] = "Gray"
                state["stroke_color"] = color_to_info([float(nums[-1])], "Gray")
            elif op == "k" and len(nums) >= 4:
                state["fill_cs"] = "CMYK"
                state["fill_color"] = color_to_info([float(x) for x in nums[-4:]], "CMYK")
            elif op == "K" and len(nums) >= 4:
                state["stroke_cs"] = "CMYK"
                state["stroke_color"] = color_to_info([float(x) for x in nums[-4:]], "CMYK")
            elif op == "w" and nums:
                state["line_width"] = float(nums[-1])
            elif op == "d":
                arrs = [v for k, v in operands if k == "arr"]
                if arrs:
                    dv = [round(float(v), 2) for k, v in arrs[0] if k == "num"]
                    state["dash"] = dv if dv else None
            elif op == "BT":
                tm = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]
                tlm = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]
            elif op == "Tf" and names and nums:
                state["font_size"] = float(nums[-1])
                if names[-1] in font_refs:
                    state["font_info"] = self._parse_font(font_refs[names[-1]])
            elif op == "Tm" and len(nums) >= 6:
                tm = [float(x) for x in nums[-6:]]
                tlm = list(tm)
            elif op in ("Td", "TD") and len(nums) >= 2:
                tx, ty = float(nums[-2]), float(nums[-1])
                if op == "TD":
                    state["tl"] = -ty
                tlm = mat_mul(tlm, [1.0, 0.0, 0.0, 1.0, tx, ty])
                tm = list(tlm)
            elif op == "T*":
                tlm = mat_mul(tlm, [1.0, 0.0, 0.0, 1.0, 0.0, -state["tl"]])
                tm = list(tlm)
            elif op == "TL" and nums:
                state["tl"] = float(nums[-1])
            elif op == "Tc" and nums:
                state["tc"] = float(nums[-1])
            elif op == "Tj":
                strs = [v for k, v in operands if k == "str"]
                if strs:
                    emit_text(self._decode_text_bytes(strs[-1], state["font_info"]))
            elif op == "TJ":
                arrs = [v for k, v in operands if k == "arr"]
                if arrs:
                    parts = []
                    for ak, av in arrs[-1]:
                        if ak == "str":
                            parts.append(self._decode_text_bytes(av, state["font_info"]))
                        elif ak == "num" and av < -220:
                            parts.append(" ")
                    emit_text("".join(parts))
            elif op in ("'", '"'):
                tlm = mat_mul(tlm, [1.0, 0.0, 0.0, 1.0, 0.0, -state["tl"]])
                tm = list(tlm)
                strs = [v for k, v in operands if k == "str"]
                if strs:
                    emit_text(self._decode_text_bytes(strs[-1], state["font_info"]))
            elif op == "re" and len(nums) >= 4:
                x, y, rw, rh = [float(v) for v in nums[-4:]]
                c0 = to_stage(x, y)
                c1 = to_stage(x + rw, y)
                c2 = to_stage(x + rw, y + rh)
                c3 = to_stage(x, y + rh)
                path_pts.extend([c0, c1, c2, c3])
                path_cmds.extend([
                    ("M", [c0[0], c0[1]]),
                    ("L", [c1[0], c1[1]]),
                    ("L", [c2[0], c2[1]]),
                    ("L", [c3[0], c3[1]]),
                    ("Z", []),
                ])
                cur_pt = c0
            elif op == "m" and len(nums) >= 2:
                p = to_stage(float(nums[-2]), float(nums[-1]))
                path_pts.append(p)
                path_cmds.append(("M", [p[0], p[1]]))
                cur_pt = p
            elif op == "l" and len(nums) >= 2:
                p = to_stage(float(nums[-2]), float(nums[-1]))
                path_pts.append(p)
                path_cmds.append(("L", [p[0], p[1]]))
                cur_pt = p
            elif op == "c" and len(nums) >= 6:
                p1 = to_stage(float(nums[-6]), float(nums[-5]))
                p2 = to_stage(float(nums[-4]), float(nums[-3]))
                p3 = to_stage(float(nums[-2]), float(nums[-1]))
                path_pts.extend([p1, p2, p3])
                path_cmds.append(("C", [p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]]))
                cur_pt = p3
            elif op == "v" and len(nums) >= 4:
                p2 = to_stage(float(nums[-4]), float(nums[-3]))
                p3 = to_stage(float(nums[-2]), float(nums[-1]))
                path_pts.extend([p2, p3])
                path_cmds.append(("C", [cur_pt[0], cur_pt[1], p2[0], p2[1], p3[0], p3[1]]))
                cur_pt = p3
            elif op == "y" and len(nums) >= 4:
                p1 = to_stage(float(nums[-4]), float(nums[-3]))
                p3 = to_stage(float(nums[-2]), float(nums[-1]))
                path_pts.extend([p1, p3])
                path_cmds.append(("C", [p1[0], p1[1], p3[0], p3[1], p3[0], p3[1]]))
                cur_pt = p3
            elif op == "h":
                path_cmds.append(("Z", []))
            elif op in ("W", "W*"):
                pending_clip = True
            elif op in ("f", "F", "f*"):
                flush_path(fill=True, stroke=False)
            elif op in ("S", "s"):
                if op == "s":
                    path_cmds.append(("Z", []))
                flush_path(fill=False, stroke=True)
            elif op in ("B", "B*", "b", "b*"):
                if op in ("b", "b*"):
                    path_cmds.append(("Z", []))
                flush_path(fill=True, stroke=True)
            elif op == "n":
                if pending_clip and path_pts:
                    xs = [p[0] for p in path_pts]
                    ys = [p[1] for p in path_pts]
                    min_x, max_x, min_y, max_y = min(xs), max(xs), min(ys), max(ys)
                    w, h = max_x - min_x, max_y - min_y
                    gk, cr, sd = _classify_svg_path(path_cmds, min_x, min_y, w, h)
                    state["clip_bbox"] = (min_x, min_y, w, h, gk, cr, sd)
                pending_clip = False
                path_pts = []
                path_cmds = []
            elif op == "sh" and names and names[-1] in shading_refs:
                sh = dict(self._parse_shading(shading_refs[names[-1]]))
                coords = sh.get("coords", [])
                if sh.get("shadingType") == 3 and len(coords) >= 6:
                    x0, y0 = to_stage(coords[0], coords[1])
                    x1, y1 = to_stage(coords[3], coords[4])
                    ctm_scale = math.hypot(state["ctm"][0], state["ctm"][1])
                    r0 = round(coords[2] * ctm_scale, 2)
                    r1 = round(coords[5] * ctm_scale, 2)
                    sh["stageCoords"] = [round(x0, 2), round(y0, 2), r0, round(x1, 2), round(y1, 2), r1]
                elif len(coords) >= 4:
                    x0, y0 = to_stage(coords[0], coords[1])
                    x1, y1 = to_stage(coords[2], coords[3])
                    sh["stageCoords"] = [round(x0, 2), round(y0, 2), round(x1, 2), round(y1, 2)]
                if state["clip_bbox"] is not None:
                    sh["clipBox"] = state["clip_bbox"]
                sh["drawIndex"] = len(out["drawOrder"])
                out["gradients"].append(sh)
                out["drawOrder"].append(("gradient", len(out["gradients"]) - 1))
            elif op == "Do" and names and names[-1] in xobj_refs:
                xnum = xobj_refs[names[-1]]
                xbody = self.objs.get(xnum, b"")
                if b"/Subtype /Image" in xbody or re.search(rb"/Subtype\s*/Image\b", xbody):
                    wm = re.search(rb"/Width\s+(\d+)", xbody)
                    hm = re.search(rb"/Height\s+(\d+)", xbody)
                    nw = int(wm.group(1)) if wm else 0
                    nh = int(hm.group(1)) if hm else 0
                    corners = [to_stage(u, v) for u, v in ((0, 0), (1, 0), (1, 1), (0, 1))]
                    xs = [p[0] for p in corners]
                    ys = [p[1] for p in corners]
                    rec = {
                        "xobj": names[-1],
                        "objNum": xnum,
                        "nativeWidth": nw,
                        "nativeHeight": nh,
                        "hasAlphaMask": b"/SMask" in xbody,
                        "x": round(min(xs), 2),
                        "y": round(min(ys), 2),
                        "width": round(max(xs) - min(xs), 2),
                        "height": round(max(ys) - min(ys), 2),
                        "drawIndex": len(out["drawOrder"]),
                    }
                    out["images"].append(rec)
                    out["drawOrder"].append(("image", len(out["images"]) - 1))
                elif re.search(rb"/Subtype\s*/Form\b", xbody):
                    f_mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]
                    mm = re.search(rb"/Matrix\s*\[([^\]]+)\]", xbody)
                    if mm:
                        f_mat = [float(x) for x in mm.group(1).decode("latin1").split()[:6]]
                    f_res = xbody
                    frm = re.search(rb"/Resources\s+(\d+)\s+\d+\s+R", xbody)
                    if frm:
                        f_res = self.objs.get(int(frm.group(1)), b"")
                    f_stream = _decode_pdf_stream(xbody)
                    self._interpret_stream(
                        f_stream, f_res, mat_mul(state["ctm"], f_mat), page_w, page_h, out, depth + 1
                    )

            operands.clear()
