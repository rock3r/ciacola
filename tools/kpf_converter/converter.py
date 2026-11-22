"""Core Apple Keynote KPF -> DeckDocument converter."""

from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from .canonical import PAYLOAD_KEYS
from .effects import (
    MOVIE_EFFECT_NAMES,
    NodeAnimState,
    attach_event_effects,
    extract_leaf_animations,
    map_transition,
)
from .pdf_atlas import KeynotePdfAtlas
from .registry import (
    INTERACTIVE_SPECIMEN_MODES,
    SPECIMEN_LOOP_PERIODS_MS,
    KeyAssetExtractor,
    build_deck_themes,
    build_font_specs,
    classify_jewel_static_specimen,
    classify_shaders_preview_specimen,
    map_pdf_font,
    match_key_svg_replacement,
    match_static_raster_asset,
    resolve_movie_specimen,
)

# Exact bundled TTF hhea (ascent/upm, descent/upm) metrics so Compose SkParagraph
# baselines align to Keynote PDF stage baselines within sub-pixel accuracy.
FONT_METRICS: Dict[str, Tuple[float, float]] = {
    "Assistant": (1.021, 0.287),
    "Fredoka": (0.974, 0.236),
    "Google Sans": (0.966, 0.286),
    "Hanken Grotesk": (1.000, 0.303),
    "Inter": (0.969, 0.241),
    "JetBrains Mono": (1.020, 0.300),
    "Montserrat": (0.968, 0.251),
    "Nanum Pen Script": (0.800, 0.200),
    "Noto Emoji": (0.928, 0.244),
    "Plus Jakarta Sans": (1.038, 0.222),
    "Roboto": (0.928, 0.244),
    # Keynote font aliases resolved via fallback chain
    "Novecento Sans": (0.968, 0.251),
    "Oscine": (1.038, 0.222),
    "Snell Roundhand": (0.800, 0.200),
    "Google Sans Display": (0.966, 0.286),
}

# Unwrapped frame.w slack factors compensating for fallback-font advance width vs tight PDF ink bounds.
FONT_SLACK_FACTORS: Dict[str, Tuple[float, float]] = {
    "Novecento Sans": (1.34, 40.0),
    "Montserrat": (1.34, 40.0),
    "Oscine": (1.30, 36.0),
    "Plus Jakarta Sans": (1.30, 36.0),
    "Snell Roundhand": (1.32, 40.0),
    "Nanum Pen Script": (1.32, 40.0),
}


def _natural_line_height(family: str, font_size: float) -> float:
    asc, desc = FONT_METRICS.get(family, (0.97, 0.25))
    return max(font_size * (asc + desc), font_size * 1.12)


def _font_baseline_offset(family: str, font_size: float, line_height: float) -> float:
    """Distance from top of Compose text box to first baseline under SkParagraph proportional LineHeightStyle."""
    asc, desc = FONT_METRICS.get(family, (0.97, 0.25))
    return max(font_size * asc, line_height * (asc / (asc + desc)))


def _estimate_text_width(text: str, font_family: str, font_size: float) -> float:
    """Width-aware character advance estimate (prevents false splits after wide glyphs like 'w'/'M')."""
    if not text:
        return 0.0
    if font_family == "JetBrains Mono":
        return len(text) * font_size * 0.62
    total = 0.0
    for ch in text:
        o = ord(ch)
        if ch == " ":
            total += 0.28
        elif o > 0x2000:
            total += 1.02
        elif ch in "WMwm@%&":
            total += 0.86
        elif ch in "ilIjtfr1.,:;!|'\"`()[]{}":
            total += 0.31
        elif ch.isupper() or ch.isdigit():
            total += 0.66
        else:
            total += 0.54
    return total * font_size


def _infer_rounded_rect_radius(shape: Dict[str, Any]) -> float:
    """Infer visual rounded-rect corner radius from explicit cornerRadius or Apple squircle SVG path."""
    if shape.get("cornerRadius") is not None and float(shape["cornerRadius"]) > 0.5:
        return float(shape["cornerRadius"])
    svg_d = (shape.get("svgPath") or "").strip()
    if svg_d:
        m = re.match(r"^M\s+([0-9.]+)\s+0(?:\.0+)?\s+L\s+([0-9.]+)\s+0(?:\.0+)?\s+C\b", svg_d)
        if m:
            lead = float(m.group(1))
            if lead > 1.0:
                # Apple Keynote squircle tangent lead ≈ 1.5287 * circular cornerRadius
                return round(lead / 1.5287, 1)
    return 0.0


def _attach_interactive_mode(node: Dict[str, Any], ref: str) -> None:
    mode = INTERACTIVE_SPECIMEN_MODES.get(ref)
    if mode:
        node["interactive"] = mode


def _num(v: float, decimals: int = 2) -> int | float:
    r = round(float(v), decimals)
    if abs(r) < 1e-9:
        return 0
    if float(int(round(r))) == r:
        return int(round(r))
    return r


def _slug(text: str, max_len: int = 28) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "-", (text or "").lower()).strip("-")
    if not s:
        return "node"
    if len(s) > max_len:
        s = s[:max_len].rstrip("-")
    return s or "node"


def _layer_stage_bounds(layer: Dict[str, Any], parent_origin: Tuple[float, float] = (0.0, 0.0)) -> Dict[str, Any]:
    st = layer.get("initialState") or {}
    pos = st.get("position") or {"pointX": 0.0, "pointY": 0.0}
    anc = st.get("anchorPoint") or {"pointX": 0.5, "pointY": 0.5}
    w = float(st.get("width", 0.0))
    h = float(st.get("height", 0.0))
    px = float(pos.get("pointX", 0.0))
    py = float(pos.get("pointY", 0.0))
    ax = float(anc.get("pointX", 0.5))
    ay = float(anc.get("pointY", 0.5))
    x = parent_origin[0] + px - w * ax
    y = parent_origin[1] + py - h * ay
    rot_rad = float(st.get("rotation", 0.0))
    return {
        "x": round(x, 2),
        "y": round(y, 2),
        "width": round(w, 2),
        "height": round(h, 2),
        "rotationDeg": round(math.degrees(rot_rad), 2),
        "scale": round(float(st.get("scale", 1.0)), 4),
        "opacity": round(float(st.get("opacity", 1.0)), 4),
        "hidden": bool(st.get("hidden", False)),
    }


def _collect_textured_layers(
    layer: Dict[str, Any],
    parent_origin: Tuple[float, float] = (0.0, 0.0),
    inherited_oid: Optional[str] = None,
    inherited_hidden: bool = False,
    inherited_opacity: float = 1.0,
    out: Optional[List[Dict[str, Any]]] = None,
) -> List[Dict[str, Any]]:
    if out is None:
        out = []
    b = _layer_stage_bounds(layer, parent_origin)
    oid = layer.get("objectID") or inherited_oid
    hid = inherited_hidden or b["hidden"]
    op = round(inherited_opacity * b["opacity"], 4)
    tex = layer.get("texture")
    if tex:
        out.append({
            "texture": tex,
            "objectID": oid,
            "bounds": {"x": b["x"], "y": b["y"], "width": b["width"], "height": b["height"]},
            "rotationDeg": b["rotationDeg"],
            "scale": b["scale"],
            "opacity": op,
            "hidden": hid,
            "isVideoLayer": bool(layer.get("isVideoLayer", False)),
            "isBackgroundTexture": bool((layer.get("texturedRectangle") or {}).get("isBackgroundTexture", False)),
            "textureType": (layer.get("texturedRectangle") or {}).get("textureType"),
        })
    next_origin = (b["x"], b["y"])
    for child in layer.get("layers") or []:
        _collect_textured_layers(child, next_origin, oid, hid, op, out)
    return out


def _pdf_gradient_to_fill(
    grad: Dict[str, Any],
    box_x: float,
    box_y: float,
    box_w: float,
    box_h: float,
) -> Optional[Dict[str, Any]]:
    stops = grad.get("stops") or []
    if len(stops) < 2:
        return None
    clean_stops = [{"at": _num(float(s["at"]), 3), "color": s["color"]} for s in stops]
    sc = grad.get("stageCoords") or []
    if grad.get("shadingType") == 3 and len(sc) >= 6:
        cx, cy = sc[3], sc[4]
        r1 = sc[5]
        nx = max(0.0, min(1.0, (cx - box_x) / max(box_w, 1.0)))
        ny = max(0.0, min(1.0, (cy - box_y) / max(box_h, 1.0)))
        rad = max(0.05, r1 / max(box_w, box_h, 1.0))
        return {
            "gradient": {
                "type": "radial",
                "center": {"x": _num(nx, 3), "y": _num(ny, 3)},
                "radius": _num(rad, 3),
                "stops": clean_stops,
            }
        }
    angle = 180
    if len(sc) >= 4:
        dx = sc[2] - sc[0]
        dy = sc[3] - sc[1]
        if math.hypot(dx, dy) > 1e-4:
            # CSS linear-gradient angle: 0 = up, 90 = right, 180 = down
            angle = _num((math.degrees(math.atan2(dx, -dy)) + 360.0) % 360.0, 1)
    return {
        "gradient": {
            "type": "linear",
            "angle": angle,
            "stops": clean_stops,
        }
    }


def _runs_to_text_payload_with_meta(
    runs: List[Dict[str, Any]],
    used_families: Set[str],
) -> Optional[Tuple[Dict[str, Any], str, Dict[str, Any]]]:
    """
    Convert a list of PDF text runs (already in stage coordinates) belonging to one
    logical text block into (TextPayload, plain_text, layout_meta).
    `lineHeight` is always emitted in document units.
    """
    valid = [r for r in runs if r.get("text")]
    if not valid:
        return None

    # Cluster runs into visual lines by baselineY
    valid.sort(key=lambda r: (r["baselineY"], r["x"]))
    lines: List[List[Dict[str, Any]]] = []
    for r in valid:
        fsz = max(float(r.get("fontSize") or 16.0), 8.0)
        placed = False
        for ln in reversed(lines):
            ref_y = ln[0]["baselineY"]
            ref_sz = max(float(ln[0].get("fontSize") or 16.0), 8.0)
            if abs(r["baselineY"] - ref_y) <= max(4.5, min(fsz, ref_sz) * 0.38):
                ln.append(r)
                placed = True
                break
        if not placed:
            lines.append([r])

    lines.sort(key=lambda ln: min(r["baselineY"] for r in ln))
    for ln in lines:
        ln.sort(key=lambda r: r["x"])

    # Build styled segments across lines & collect exact PDF baseline/horizontal geometry
    styled_segs: List[Dict[str, Any]] = []
    raw_line_gaps: List[float] = []
    lines_info: List[Dict[str, float]] = []
    prev_line_y: Optional[float] = None
    prev_line_sz: float = 16.0

    for li, ln in enumerate(lines):
        cur_y = sum(r["baselineY"] for r in ln) / len(ln)
        cur_sz = max(float(r.get("fontSize") or 16.0) for r in ln)
        ln_min_x = min(float(r["x"]) for r in ln)
        ln_max_right = ln_min_x
        for r in ln:
            rfam, _, _ = map_pdf_font(r.get("font") or "Inter")
            rfsz = max(float(r.get("fontSize") or 16.0), 8.0)
            r_right = float(r["x"]) + _estimate_text_width(str(r.get("text") or ""), rfam, rfsz)
            if "_leafRight" in r:
                r_right = max(r_right, float(r["_leafRight"]))
            if r_right > ln_max_right:
                ln_max_right = r_right
        lines_info.append({
            "baselineY": cur_y,
            "minX": ln_min_x,
            "estW": max(ln_max_right - ln_min_x, 1.0),
            "maxSize": cur_sz,
        })

        if prev_line_y is not None:
            gap = cur_y - prev_line_y
            is_para_break = gap > max(prev_line_sz, cur_sz) * 2.15
            if gap > 0 and not is_para_break:
                raw_line_gaps.append(gap)
            if styled_segs:
                # Strip trailing accidental PDF wrap space before newline, unless single-char whitespace
                if styled_segs[-1]["text"].endswith(" ") and len(styled_segs[-1]["text"].strip()) > 0:
                    styled_segs[-1]["text"] = styled_segs[-1]["text"].rstrip(" ")
                nl = "\n\n" if is_para_break else "\n"
                styled_segs[-1]["text"] += nl
        prev_line_y = cur_y
        prev_line_sz = cur_sz

        for ri, r in enumerate(ln):
            fam, weight, italic = map_pdf_font(r.get("font") or "Inter")
            used_families.add(fam)
            txt = r["text"].replace("\r\n", "\n").replace("\r", "\n").replace("\u2028", "\n")
            sz = _num(max(float(r.get("fontSize") or 16.0), 1.0), 1)
            col = r.get("colorHex") or "#FFFFFF"
            trk = float(r.get("tracking") or 0.0)

            # Check whether we need a word space between previous run on this line and current run
            if ri > 0 and styled_segs:
                prev_r = ln[ri - 1]
                prev_txt = styled_segs[-1]["text"]
                if prev_txt and not prev_txt[-1].isspace() and txt and not txt[0].isspace():
                    prev_fam, _, _ = map_pdf_font(prev_r.get("font") or "Inter")
                    prev_fsz = max(float(prev_r.get("fontSize") or 16.0), 8.0)
                    if "_leafRight" in prev_r and "_leafLeft" in r:
                        x_gap = r["_leafLeft"] - prev_r["_leafRight"]
                    else:
                        est_adv = _estimate_text_width(str(prev_r["text"]), prev_fam, prev_fsz)
                        x_gap = r["x"] - (prev_r["x"] + est_adv)
                    if x_gap > prev_fsz * 0.16:
                        styled_segs[-1]["text"] += " "

            seg = {
                "text": txt,
                "font": fam,
                "size": sz,
                "weight": weight,
                "italic": italic,
                "color": col,
                "tracking": _num(trk, 3) if abs(trk) >= 0.05 else 0,
            }
            # Coalesce whitespace-only runs into adjacent prose styling, but NEVER into Noto Emoji
            # (where a space has a ~1.0em advance instead of ~0.28em, blowing out headline widths!).
            if not seg["text"].strip() and styled_segs and styled_segs[-1]["font"] != "Noto Emoji":
                styled_segs[-1]["text"] += seg["text"]
            elif (
                styled_segs
                and styled_segs[-1]["font"] == seg["font"]
                and styled_segs[-1]["size"] == seg["size"]
                and styled_segs[-1]["weight"] == seg["weight"]
                and styled_segs[-1]["italic"] == seg["italic"]
                and styled_segs[-1]["color"] == seg["color"]
                and styled_segs[-1]["tracking"] == seg["tracking"]
            ):
                styled_segs[-1]["text"] += seg["text"]
            else:
                styled_segs.append(seg)

    # Coalesce any remaining whitespace-only segments forward into the following prose segment
    # (so e.g. a space after a Noto Emoji inherits the prose font & color, never Noto Emoji or #000000).
    ws_idx = 0
    while ws_idx < len(styled_segs) - 1:
        if not styled_segs[ws_idx]["text"].strip():
            styled_segs[ws_idx + 1]["text"] = styled_segs[ws_idx]["text"] + styled_segs[ws_idx + 1]["text"]
            styled_segs.pop(ws_idx)
        else:
            ws_idx += 1

    plain = "".join(s["text"] for s in styled_segs).strip("\n")
    if not plain.strip():
        return None

    # Pick dominant style by non-whitespace character count to lift to TextPayload top level
    style_weights: Dict[Tuple[Any, ...], int] = {}
    for s in styled_segs:
        key = (s["font"], s["size"], s["weight"], s["italic"], s["color"], s["tracking"])
        style_weights[key] = style_weights.get(key, 0) + max(1, len(s["text"].strip()))
    dom_font, dom_size, dom_weight, dom_italic, dom_color, dom_tracking = max(
        style_weights.items(), key=lambda kv: kv[1]
    )[0]

    payload: Dict[str, Any] = {}
    if len(styled_segs) == 1:
        payload["value"] = styled_segs[0]["text"]
    else:
        run_objs: List[Dict[str, Any]] = []
        for s in styled_segs:
            if not s["text"]:
                continue
            ro: Dict[str, Any] = {"text": s["text"]}
            if s["font"] != dom_font:
                ro["font"] = s["font"]
            if s["size"] != dom_size:
                ro["size"] = s["size"]
            if s["weight"] != dom_weight:
                ro["weight"] = s["weight"]
            if s["italic"] != dom_italic:
                ro["italic"] = s["italic"]
            if s["color"] != dom_color:
                ro["color"] = s["color"]
            if s["tracking"] != dom_tracking and s["tracking"] != 0:
                ro["tracking"] = s["tracking"]
            run_objs.append(ro)
        if len(run_objs) == 1 and len(run_objs[0]) == 1:
            payload["value"] = run_objs[0]["text"]
        else:
            payload["value"] = run_objs

    payload["font"] = dom_font
    payload["size"] = dom_size
    if dom_weight != 400:
        payload["weight"] = dom_weight
    if dom_italic:
        payload["italic"] = True
    payload["color"] = dom_color
    if dom_tracking != 0:
        payload["tracking"] = dom_tracking

    # Emit lineHeight in document units — never raw multipliers < 6.0!
    dom_sz_f = float(dom_size)
    if raw_line_gaps:
        med_gap = sorted(raw_line_gaps)[len(raw_line_gaps) // 2]
        ratio = med_gap / max(dom_sz_f, 1.0)
        if 0.78 <= ratio <= 2.40:
            lh_val = med_gap
        else:
            lh_val = dom_sz_f * 1.18
    elif "\n" in plain:
        lh_val = dom_sz_f * 1.18
    else:
        lh_val = _natural_line_height(dom_font, dom_sz_f)
    payload["lineHeight"] = _num(lh_val, 2)

    # Infer multi-line alignment when lines share a common center or right edge while left edge varies
    inferred_align: Optional[str] = None
    if len(lines_info) >= 2:
        lefts = [li["minX"] for li in lines_info]
        centers = [li["minX"] + li["estW"] * 0.5 for li in lines_info]
        rights = [li["minX"] + li["estW"] for li in lines_info]
        left_spread = max(lefts) - min(lefts)
        center_spread = max(centers) - min(centers)
        right_spread = max(rights) - min(rights)
        if left_spread > 18.0:
            if center_spread <= max(18.0, left_spread * 0.35) and center_spread < right_spread:
                inferred_align = "center"
            elif right_spread <= max(18.0, left_spread * 0.35):
                inferred_align = "end"
    if inferred_align:
        payload["align"] = inferred_align

    meta: Dict[str, Any] = {
        "firstBaselineY": lines_info[0]["baselineY"],
        "minRunX": min(li["minX"] for li in lines_info),
        "firstLineMinX": lines_info[0]["minX"],
        "firstLineEstW": lines_info[0]["estW"],
        "maxLineEstW": max(li["estW"] for li in lines_info),
        "lineCount": max(1, plain.count("\n") + 1),
        "linesInfo": lines_info,
        "domFont": dom_font,
        "domSize": dom_sz_f,
        "lineHeight": float(lh_val),
        "inferredAlign": inferred_align,
    }
    return (payload, plain, meta)


def _runs_to_text_payload(
    runs: List[Dict[str, Any]],
    used_families: Set[str],
) -> Optional[Tuple[Dict[str, Any], str]]:
    """Backwards-compatible wrapper returning (TextPayload, plain_text)."""
    res = _runs_to_text_payload_with_meta(runs, used_families)
    if res is None:
        return None
    return (res[0], res[1])


def _shape_rec_to_node_payload(
    sh: Dict[str, Any],
    grad_fill: Optional[Dict[str, Any]] = None,
) -> Optional[Dict[str, Any]]:
    fill = grad_fill if grad_fill is not None else sh.get("fillHex")
    stroke_hex = sh.get("strokeHex")
    if not fill and not stroke_hex:
        return None
    kind = sh.get("kind", "rect")
    if kind in ("rect", "ellipse", "line"):
        geom: Any = kind
    else:
        svg_d = (sh.get("svgPath") or "").strip()
        if not svg_d:
            return None
        geom = {"path": svg_d}

    sp: Dict[str, Any] = {"geometry": geom}
    if fill is not None:
        sp["fill"] = fill
    if stroke_hex:
        st: Dict[str, Any] = {
            "color": stroke_hex,
            "width": _num(max(float(sh.get("strokeWidth") or 1.0), 0.5), 2),
        }
        if sh.get("dash"):
            st["dash"] = [_num(x, 2) for x in sh["dash"]]
            if float(sh["dash"][0]) <= 0.1:
                st["cap"] = "round"
        sp["stroke"] = st
    if kind == "rect" and sh.get("cornerRadius") is not None and float(sh["cornerRadius"]) > 0.5:
        sp["cornerRadius"] = _num(float(sh["cornerRadius"]), 2)
    return sp


def _shape_frame_or_bounds(
    sh: Dict[str, Any],
    min_x: float,
    min_y: float,
    bw: float,
    bh: float,
) -> Tuple[float, float, float, float]:
    """Use exact PDF path bbox for normalized rect shapes when valid; fall back to slot bounds."""
    if (
        sh.get("kind") == "rect"
        and float(sh.get("width") or 0.0) >= 1.0
        and float(sh.get("height") or 0.0) >= 1.0
    ):
        return (float(sh["x"]), float(sh["y"]), float(sh["width"]), float(sh["height"]))
    return (min_x, min_y, bw, bh)


def _build_pure_shape_slot_node(
    slot_shapes: List[Dict[str, Any]],
    slot_gradients: List[Dict[str, Any]],
    slot_i: int,
    min_x: float,
    min_y: float,
    bw: float,
    bh: float,
    alloc_id: Any,
) -> Optional[Dict[str, Any]]:
    """Build a DeckDocument node for a pure-shape slot (single rect uses exact path bbox)."""
    if not slot_shapes:
        return None
    if len(slot_shapes) == 1:
        sh0 = slot_shapes[0]
        fx, fy, fw, fh = _shape_frame_or_bounds(sh0, min_x, min_y, bw, bh)
        gfill = _pdf_gradient_to_fill(slot_gradients[0], fx, fy, fw, fh) if slot_gradients else None
        sp_n = _shape_rec_to_node_payload(sh0, grad_fill=gfill)
        if not sp_n:
            return None
        return {
            "id": alloc_id(f"{sh0['kind']}-{slot_i}"),
            "frame": {"x": _num(fx), "y": _num(fy), "w": _num(fw), "h": _num(fh)},
            "shape": sp_n,
        }
    if len(slot_shapes) <= 12:
        children_with_src: List[Tuple[Dict[str, Any], Optional[Dict[str, Any]]]] = []
        for si_s, sh in enumerate(slot_shapes):
            sp_n = _shape_rec_to_node_payload(sh)
            if sp_n:
                children_with_src.append((
                    {
                        "id": alloc_id(f"shape-{slot_i}-{si_s + 1}"),
                        "frame": {
                            "x": _num(float(sh["x"]) - min_x),
                            "y": _num(float(sh["y"]) - min_y),
                            "w": _num(max(float(sh["width"]), 1.0)),
                            "h": _num(max(float(sh["height"]), 1.0)),
                        },
                        "shape": sp_n,
                    },
                    sh,
                ))
        for gi_g, gr in enumerate(slot_gradients):
            cb = gr.get("clipBox")
            gx, gy, gw, gh = (cb[0], cb[1], cb[2], cb[3]) if cb else (min_x, min_y, bw, bh)
            gfill = _pdf_gradient_to_fill(gr, gx, gy, gw, gh)
            if gfill:
                gk = cb[4] if cb else "rect"
                sp_g: Dict[str, Any] = {
                    "geometry": gk if gk in ("rect", "ellipse", "line") else {"path": cb[6]},
                    "fill": gfill,
                }
                if cb and gk == "rect" and cb[5] is not None and cb[5] > 0.5:
                    sp_g["cornerRadius"] = _num(cb[5], 2)
                children_with_src.append((
                    {
                        "id": alloc_id(f"grad-{slot_i}-{gi_g + 1}"),
                        "frame": {
                            "x": _num(gx - min_x),
                            "y": _num(gy - min_y),
                            "w": _num(max(gw, 1.0)),
                            "h": _num(max(gh, 1.0)),
                        },
                        "shape": sp_g,
                    },
                    None,
                ))
        if len(children_with_src) == 1:
            only, src_sh = children_with_src[0]
            if src_sh is not None:
                fx, fy, fw, fh = _shape_frame_or_bounds(src_sh, min_x, min_y, bw, bh)
            else:
                fx, fy, fw, fh = (min_x, min_y, bw, bh)
            only["frame"] = {"x": _num(fx), "y": _num(fy), "w": _num(fw), "h": _num(fh)}
            return only
        if children_with_src:
            return {
                "id": alloc_id(f"group-{slot_i}"),
                "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                "stack": {"children": [c for c, _ in children_with_src]},
            }
        return None
    sp_merged = _merge_shapes_to_single_shape(slot_shapes, min_x, min_y)
    if not sp_merged:
        return None
    return {
        "id": alloc_id(f"vector-{slot_i}"),
        "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
        "shape": sp_merged,
    }


def _parse_svg_contours(svg_d: str) -> List[List[Tuple[str, List[float]]]]:
    parts = [p.strip() for p in re.split(r"(?=\bM\s+)", svg_d.strip()) if p.strip()]
    contours: List[List[Tuple[str, List[float]]]] = []
    for part in parts:
        toks = part.split()
        cmds: List[Tuple[str, List[float]]] = []
        i = 0
        while i < len(toks):
            op = toks[i]
            if op == "Z":
                cmds.append(("Z", []))
                i += 1
            elif op in ("M", "L") and i + 2 < len(toks):
                cmds.append((op, [float(toks[i + 1]), float(toks[i + 2])]))
                i += 3
            elif op == "C" and i + 6 < len(toks):
                cmds.append((op, [float(toks[i + 1 + k]) for k in range(6)]))
                i += 7
            else:
                i += 1
        if len(cmds) > 1:
            contours.append(cmds)
    return contours


def _cmd_end_pt(cmd: Tuple[str, List[float]]) -> Tuple[float, float]:
    op, pts = cmd
    if op == "C":
        return (pts[4], pts[5])
    return (pts[0], pts[1])


def _format_svg_contour(cmds: List[Tuple[str, List[float]]]) -> str:
    parts: List[str] = []
    for op, pts in cmds:
        if op == "Z":
            parts.append("Z")
        elif op in ("M", "L"):
            parts.append(f"{op} {_num(pts[0], 2)} {_num(pts[1], 2)}")
        elif op == "C":
            parts.append(
                f"C {_num(pts[0], 2)} {_num(pts[1], 2)} "
                f"{_num(pts[2], 2)} {_num(pts[3], 2)} "
                f"{_num(pts[4], 2)} {_num(pts[5], 2)}"
            )
    return " ".join(parts)


def try_convert_arrow_shape_payload(sp: Dict[str, Any]) -> bool:
    """
    If `sp` is a merged arrow shape (1 open shaft contour + 1 or 2 closed 3-vertex triangle
    arrowhead contours with matching fill/stroke colors), convert it cleanly into an open shaft
    extended to the triangle apex(es) with `fill` removed and `heads` populated.
    """
    geom = sp.get("geometry")
    fill_hex = sp.get("fill")
    stroke = sp.get("stroke")
    if not isinstance(geom, dict) or "path" not in geom:
        return False
    if not isinstance(fill_hex, str) or not isinstance(stroke, dict):
        return False
    stroke_color = stroke.get("color")
    if not isinstance(stroke_color, str) or fill_hex.strip().lower() != stroke_color.strip().lower():
        return False

    contours = _parse_svg_contours(str(geom["path"]))
    if len(contours) < 2:
        return False
    opens = [c for c in contours if c[-1][0] != "Z"]
    tris = [
        c for c in contours
        if c[-1][0] == "Z" and [cmd[0] for cmd in c] in (["M", "L", "L", "Z"], ["M", "L", "L", "L", "Z"])
    ]
    if len(opens) != 1 or not (1 <= len(tris) <= 2) or len(opens) + len(tris) != len(contours):
        return False

    shaft = list(opens[0])
    if len(shaft) < 2:
        return False
    p_start = _cmd_end_pt(shaft[0])
    p_next = p_start
    for cmd in shaft[1:]:
        cand = (cmd[1][0], cmd[1][1]) if cmd[0] == "C" else _cmd_end_pt(cmd)
        if math.hypot(cand[0] - p_start[0], cand[1] - p_start[1]) > 0.1:
            p_next = cand
            break
        cand2 = _cmd_end_pt(cmd)
        if math.hypot(cand2[0] - p_start[0], cand2[1] - p_start[1]) > 0.1:
            p_next = cand2
            break
    l0 = math.hypot(p_start[0] - p_next[0], p_start[1] - p_next[1])
    if l0 < 1e-4:
        return False
    t_start = ((p_start[0] - p_next[0]) / l0, (p_start[1] - p_next[1]) / l0)

    p_end = _cmd_end_pt(shaft[-1])
    p_prev = p_end
    for idx in range(len(shaft) - 1, 0, -1):
        cmd = shaft[idx]
        if cmd[0] == "C":
            c2 = (cmd[1][2], cmd[1][3])
            if math.hypot(p_end[0] - c2[0], p_end[1] - c2[1]) > 0.1:
                p_prev = c2
                break
        prev_pt = _cmd_end_pt(shaft[idx - 1])
        if math.hypot(p_end[0] - prev_pt[0], p_end[1] - prev_pt[1]) > 0.1:
            p_prev = prev_pt
            break
    l1 = math.hypot(p_end[0] - p_prev[0], p_end[1] - p_prev[1])
    if l1 < 1e-4:
        return False
    t_end = ((p_end[0] - p_prev[0]) / l1, (p_end[1] - p_prev[1]) / l1)

    matched_ends: Dict[str, Tuple[Tuple[float, float], Tuple[float, float]]] = {}
    for tri in tris:
        pts = [_cmd_end_pt(cmd) for cmd in tri[:-1]]
        if len(pts) == 4 and math.hypot(pts[0][0] - pts[3][0], pts[0][1] - pts[3][1]) < 0.1:
            pts = pts[:3]
        if len(pts) != 3:
            return False
        best = None
        for vi in range(3):
            ap = pts[vi]
            b1, b2 = pts[(vi + 1) % 3], pts[(vi + 2) % 3]
            mid = ((b1[0] + b2[0]) * 0.5, (b1[1] + b2[1]) * 0.5)
            vx, vy = ap[0] - mid[0], ap[1] - mid[1]
            vlen = math.hypot(vx, vy)
            if vlen < 1.0:
                continue
            vu = (vx / vlen, vy / vlen)
            for which, ep, et in (("start", p_start, t_start), ("end", p_end, t_end)):
                if which in matched_ends:
                    continue
                dot = vu[0] * et[0] + vu[1] * et[1]
                dist = math.hypot(mid[0] - ep[0], mid[1] - ep[1])
                if dot > 0.7 and dist <= max(16.0, vlen * 0.9):
                    cand = (dist, which, ap, mid)
                    if best is None or cand[0] < best[0]:
                        best = cand
        if best is None:
            return False
        matched_ends[best[1]] = (best[2], best[3])

    stroke_w = float(stroke.get("width") or 2.0)
    if "start" in matched_ends:
        ap, mid = matched_ends["start"]
        s0 = _cmd_end_pt(shaft[0])
        s1 = _cmd_end_pt(shaft[1])
        if shaft[1][0] == "L" and math.hypot(s0[0] - mid[0], s0[1] - mid[1]) <= max(6.0, stroke_w * 1.5) and math.hypot(s1[0] - mid[0], s1[1] - mid[1]) <= 1.5:
            shaft[0] = ("M", [ap[0], ap[1]])
        else:
            shaft[0] = ("L", [s0[0], s0[1]])
            shaft.insert(0, ("M", [ap[0], ap[1]]))
    if "end" in matched_ends:
        ap, mid = matched_ends["end"]
        e_last = _cmd_end_pt(shaft[-1])
        e_prev = _cmd_end_pt(shaft[-2])
        if shaft[-1][0] == "L" and math.hypot(e_last[0] - mid[0], e_last[1] - mid[1]) <= max(6.0, stroke_w * 1.5) and math.hypot(e_prev[0] - mid[0], e_prev[1] - mid[1]) <= 1.5:
            shaft[-1] = ("L", [ap[0], ap[1]])
        else:
            shaft.append(("L", [ap[0], ap[1]]))

    sp["geometry"] = {"path": _format_svg_contour(shaft)}
    sp.pop("fill", None)
    heads: Dict[str, str] = {}
    if "start" in matched_ends:
        heads["start"] = "filled"
    if "end" in matched_ends:
        heads["end"] = "filled"
    sp["heads"] = heads
    dash = stroke.get("dash")
    if isinstance(dash, list) and dash and float(dash[0]) <= 0.1:
        stroke["cap"] = "round"
    return True


def _shift_svg_path(svg_d: str, dx: float, dy: float) -> str:
    """Shift an M/L/C/Z SVG path string by (+dx, +dy)."""
    if abs(dx) < 1e-4 and abs(dy) < 1e-4:
        return svg_d
    toks = svg_d.split()
    out: List[str] = []
    i = 0
    while i < len(toks):
        op = toks[i]
        if op == "Z":
            out.append("Z")
            i += 1
        elif op in ("M", "L") and i + 2 < len(toks):
            x = float(toks[i + 1]) + dx
            y = float(toks[i + 2]) + dy
            out.append(f"{op} {_num(x, 2)} {_num(y, 2)}")
            i += 3
        elif op == "C" and i + 6 < len(toks):
            vals = [float(toks[i + 1 + k]) for k in range(6)]
            out.append(
                f"C {_num(vals[0] + dx, 2)} {_num(vals[1] + dy, 2)} "
                f"{_num(vals[2] + dx, 2)} {_num(vals[3] + dy, 2)} "
                f"{_num(vals[4] + dx, 2)} {_num(vals[5] + dy, 2)}"
            )
            i += 7
        else:
            out.append(op)
            i += 1
    return " ".join(out)


def _merge_shapes_to_single_shape(
    shapes: List[Dict[str, Any]],
    box_x: float,
    box_y: float,
) -> Optional[Dict[str, Any]]:
    """
    Merge multiple vector subpaths from a single KPF shape/arrow layer into ONE
    multi-contour ShapePayload (required for `lineDraw` on arrows/callouts!).
    """
    paths: List[str] = []
    seen_subpaths: Set[str] = set()
    fill_hex = None
    stroke_hex = None
    stroke_w = 0.0
    dash = None
    for sh in shapes:
        d = (sh.get("svgPath") or "").strip()
        if not d:
            continue
        dx = float(sh["x"]) - box_x
        dy = float(sh["y"]) - box_y
        shifted = _shift_svg_path(d, dx, dy)
        # Drop trailing redundant single-point `M x y` emitted after `Z` by Keynote PDF exporters
        sub_contours = [c for c in _parse_svg_contours(shifted) if len([cmd for cmd in c if cmd[0] != "Z"]) > 1]
        if not sub_contours:
            continue
        cleaned_shifted = re.sub(r"\s+M\s+[-+]?(?:\d+\.?\d*|\.\d+)\s+[-+]?(?:\d+\.?\d*|\.\d+)\s*$", "", shifted).strip()
        if cleaned_shifted not in seen_subpaths:
            seen_subpaths.add(cleaned_shifted)
            paths.append(cleaned_shifted)
        if sh.get("strokeHex") and not stroke_hex:
            stroke_hex = sh["strokeHex"]
            stroke_w = float(sh.get("strokeWidth") or 2.0)
            dash = sh.get("dash")
        if sh.get("fillHex") and not fill_hex:
            fill_hex = sh["fillHex"]
    if not paths:
        return None
    sp: Dict[str, Any] = {"geometry": {"path": " ".join(paths)}}
    if fill_hex and not stroke_hex:
        sp["fill"] = fill_hex
    elif fill_hex and stroke_hex:
        # Keep stroke primary for LineDraw; preserve fill if present
        sp["fill"] = fill_hex
    if stroke_hex:
        st: Dict[str, Any] = {"color": stroke_hex, "width": _num(max(stroke_w, 0.5), 2)}
        if dash:
            st["dash"] = [_num(x, 2) for x in dash]
            if float(dash[0]) <= 0.1:
                st["cap"] = "round"
        sp["stroke"] = st
    try_convert_arrow_shape_payload(sp)
    return sp


def _rect_iou(a: Tuple[float, float, float, float], b: Tuple[float, float, float, float]) -> float:
    ax1, ay1, aw, ah = a
    bx1, by1, bw, bh = b
    ax2, ay2 = ax1 + aw, ay1 + ah
    bx2, by2 = bx1 + bw, by1 + bh
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter <= 0:
        return 0.0
    union = max(aw * ah, 0.0) + max(bw * bh, 0.0) - inter
    return inter / union if union > 0 else 0.0


def _find_best_acc_desc(
    bounds: Tuple[float, float, float, float],
    acc_items: List[Dict[str, Any]],
) -> str:
    best_iou = 0.25
    best_desc = ""
    for item in acc_items:
        r = item.get("targetRectangle") or item.get("rect") or {}
        ab = (float(r.get("x", 0)), float(r.get("y", 0)), float(r.get("width", 0)), float(r.get("height", 0)))
        iou = _rect_iou(bounds, ab)
        if iou > best_iou:
            best_iou = iou
            best_desc = str(item.get("text") or item.get("description") or "")
    return best_desc


def _extract_magic_move_trajectories(
    ef: Dict[str, Any],
    assets: Dict[str, Any],
    atlas: Optional[KeynotePdfAtlas],
) -> List[Dict[str, Any]]:
    """Extract matched cross-slide trajectories from KPF magic-move transition baseLayer."""
    matched: List[Dict[str, Any]] = []

    def tex_text(tex_id: str) -> str:
        if not atlas or tex_id not in assets:
            return ""
        a = assets[tex_id]
        if a.get("type") != "texture" or not isinstance(a.get("index"), int):
            return ""
        pg = atlas.parse_page(a["index"])
        return " ".join(r["text"] for r in pg["textRuns"]).strip()

    def walk(layer: Dict[str, Any], parent_origin: Tuple[float, float] = (0.0, 0.0)) -> None:
        b = _layer_stage_bounds(layer, parent_origin)
        tex = layer.get("texture")
        anims = extract_leaf_animations({"animations": layer.get("animations") or [], "layers": []})
        by_prop = {a["property"]: a for a in anims}

        if tex and not (b["width"] >= 1915 and b["height"] >= 1075):
            contents_anim = by_prop.get("contents")
            trans_anim = by_prop.get("transform.translation")
            sx_anim = by_prop.get("transform.scale.x")
            sy_anim = by_prop.get("transform.scale.y")
            sxy_anim = by_prop.get("transform.scale.xy")
            rot_anim = by_prop.get("transform.rotation.z")
            if contents_anim or trans_anim or sx_anim or sy_anim or sxy_anim or rot_anim:
                from_tex = (contents_anim.get("from") or {}).get("texture", tex) if contents_anim else tex
                to_tex = (contents_anim.get("to") or {}).get("texture", tex) if contents_anim else tex
                dx = (
                    float((trans_anim.get("to") or {}).get("pointX", 0.0))
                    - float((trans_anim.get("from") or {}).get("pointX", 0.0))
                    if trans_anim
                    else 0.0
                )
                dy = (
                    float((trans_anim.get("to") or {}).get("pointY", 0.0))
                    - float((trans_anim.get("from") or {}).get("pointY", 0.0))
                    if trans_anim
                    else 0.0
                )
                sx_to = float((sx_anim.get("to") or {}).get("scalar", 1.0)) if sx_anim else (
                    float((sxy_anim.get("to") or {}).get("scalar", 1.0)) if sxy_anim else 1.0
                )
                sy_to = float((sy_anim.get("to") or {}).get("scalar", 1.0)) if sy_anim else (
                    float((sxy_anim.get("to") or {}).get("scalar", 1.0)) if sxy_anim else 1.0
                )
                matched.append({
                    "fromBounds": (b["x"], b["y"], b["width"], b["height"]),
                    "toBounds": (b["x"] + dx, b["y"] + dy, b["width"] * sx_to, b["height"] * sy_to),
                    "fromText": tex_text(from_tex),
                    "toText": tex_text(to_tex),
                    "sameTexture": from_tex == to_tex,
                })

        next_origin = (b["x"], b["y"])
        for ch in layer.get("layers") or []:
            walk(ch, next_origin)

    if "baseLayer" in ef:
        walk(ef["baseLayer"])
    return matched


def _node_plain_text(node: Dict[str, Any]) -> str:
    if "text" in node:
        v = node["text"].get("value", "")
        if isinstance(v, str):
            return " ".join(v.split()).strip()
        if isinstance(v, list):
            return " ".join("".join(r.get("text", "") for r in v).split()).strip()
    if "stack" in node:
        parts = [_node_plain_text(ch) for ch in node["stack"].get("children", [])]
        return " ".join(p for p in parts if p).strip()
    return ""


def _node_payload_kind(node: Dict[str, Any]) -> str:
    for k in PAYLOAD_KEYS:
        if k in node:
            return k
    return ""


def _node_endpoint_frame(node: Dict[str, Any], at_last_beat: bool) -> Tuple[float, float, float, float]:
    fr = dict(node.get("frame") or {})
    if at_last_beat:
        for b in node.get("builds") or []:
            if b.get("kind") == "change":
                fpatch = (b.get("set") or {}).get("frame")
                if isinstance(fpatch, dict):
                    fr.update(fpatch)
    return (
        float(fr.get("x", 0.0)),
        float(fr.get("y", 0.0)),
        float(fr.get("w", 0.0)),
        float(fr.get("h", 0.0)),
    )


def _node_present_at_last_beat(node: Dict[str, Any]) -> bool:
    # Absent at last beat only if its last enter/exit build is an exit
    present = True
    for b in node.get("builds") or []:
        if b.get("kind") == "enter":
            present = True
        elif b.get("kind") == "exit":
            present = False
    return present


def _node_present_at_beat0(node: Dict[str, Any]) -> bool:
    # Matches runtime JsonMergePatch.isNodePresentAtStart: any `enter` build (including beat 0)
    # starts hidden when Cross(A, B) lands on the slide.
    return not any(b.get("kind") == "enter" for b in (node.get("builds") or []))


def _rename_node_id_in_slide(slide: Dict[str, Any], old_id: str, new_id: str) -> None:
    """Rename `old_id` -> `new_id` across a slide's nodes and update `after`/`connector`/`loupe` refs."""
    if old_id == new_id:
        return

    def walk(n: Dict[str, Any]) -> None:
        if n.get("id") == old_id:
            n["id"] = new_id
        for b in n.get("builds") or []:
            af = b.get("after")
            if isinstance(af, str):
                if af == old_id:
                    b["after"] = new_id
                elif af.startswith(old_id + "#"):
                    b["after"] = new_id + af[len(old_id):]
        conn = n.get("connector")
        if isinstance(conn, dict):
            if conn.get("from") == old_id:
                conn["from"] = new_id
            if conn.get("to") == old_id:
                conn["to"] = new_id
        loupe = n.get("loupe")
        if isinstance(loupe, dict) and loupe.get("target") == old_id:
            loupe["target"] = new_id
        for pk in ("stack", "row", "column", "loupe"):
            if pk in n:
                for ch in n[pk].get("children", []):
                    walk(ch)

    for node in slide.get("nodes") or []:
        walk(node)


def _all_slide_node_ids(slide: Dict[str, Any]) -> Set[str]:
    ids: Set[str] = set()

    def walk(n: Dict[str, Any]) -> None:
        if "id" in n:
            ids.add(n["id"])
        for pk in ("stack", "row", "column", "loupe"):
            if pk in n:
                for ch in n[pk].get("children", []):
                    walk(ch)

    for node in slide.get("nodes") or []:
        walk(node)
    return ids


def _unify_magic_move_ids(
    slides: List[Dict[str, Any]],
    mm_trajectories: Dict[int, List[Dict[str, Any]]],
    orig_frames: Optional[Dict[int, Tuple[float, float, float, float]]] = None,
) -> int:
    """
    Unify node IDs across all consecutive slides connected by `transition.type == "magicMove"`.
    Returns total number of cross-slide matched pairs unified.
    """
    total_matched = 0

    def _orig_endpoint_frame(n: Dict[str, Any], at_last_beat: bool) -> Tuple[float, float, float, float]:
        end_fr = _node_endpoint_frame(n, at_last_beat=at_last_beat)
        if orig_frames and id(n) in orig_frames:
            ox, oy, ow, oh = orig_frames[id(n)]
            fr = n.get("frame") or {}
            dx = end_fr[0] - float(fr.get("x", ox))
            dy = end_fr[1] - float(fr.get("y", oy))
            return (ox + dx, oy + dy, ow, oh)
        return end_fr

    for si in range(len(slides) - 1):
        sa = slides[si]
        sb = slides[si + 1]
        if (sa.get("transition") or {}).get("type") != "magicMove":
            continue

        nodes_a = [n for n in sa.get("nodes") or [] if _node_present_at_last_beat(n)]
        nodes_b = [n for n in sb.get("nodes") or [] if _node_present_at_beat0(n)]
        used_a: Set[int] = set()
        used_b: Set[int] = set()
        pairs: List[Tuple[Dict[str, Any], Dict[str, Any]]] = []

        def compatible(na: Dict[str, Any], nb: Dict[str, Any]) -> bool:
            ka, kb = _node_payload_kind(na), _node_payload_kind(nb)
            if ka != kb or not ka:
                return False
            if ka == "specimen":
                return na["specimen"].get("ref") == nb["specimen"].get("ref")
            if ka == "image":
                return na["image"].get("src") == nb["image"].get("src")
            return True

        def _text_matches_traj(nt: str, traj_txt: str) -> bool:
            if not traj_txt:
                return True
            if not nt:
                return False
            if nt == traj_txt:
                return True
            return abs(len(nt) - len(traj_txt)) <= 2 and (nt in traj_txt or traj_txt in nt)

        # Pass 1: Match already-identical stable semantic IDs (specimens/images only — NEVER auto-pair
        # generic `text`/`shape`/`stack` IDs before KPF trajectories are consulted).
        for ia, na in enumerate(nodes_a):
            ka = _node_payload_kind(na)
            if ka not in ("specimen", "image"):
                continue
            for ib, nb in enumerate(nodes_b):
                if ib in used_b:
                    continue
                if na["id"] == nb["id"] and compatible(na, nb):
                    used_a.add(ia)
                    used_b.add(ib)
                    pairs.append((na, nb))
                    break

        # Pass 2: Match via KPF Magic Move trajectories (fromBounds -> toBounds + enforced text parity)
        for traj in mm_trajectories.get(si, []):
            fb = traj["fromBounds"]
            tb = traj["toBounds"]
            ft = " ".join((traj.get("fromText") or "").split()).strip()
            tt = " ".join((traj.get("toText") or "").split()).strip()

            best_a = None
            best_a_score = 1e9
            fcx, fcy = fb[0] + fb[2] * 0.5, fb[1] + fb[3] * 0.5
            for ia, na in enumerate(nodes_a):
                if ia in used_a:
                    continue
                ka = _node_payload_kind(na)
                nt = _node_plain_text(na)
                if ft and ka == "text" and not _text_matches_traj(nt, ft):
                    continue
                fa = _orig_endpoint_frame(na, at_last_beat=True)
                acx, acy = fa[0] + fa[2] * 0.5, fa[1] + fa[3] * 0.5
                dist = math.hypot(acx - fcx, acy - fcy) + 0.35 * (abs(fa[2] - fb[2]) + abs(fa[3] - fb[3]))
                if ft and nt == ft:
                    dist -= 120.0
                if dist < best_a_score and dist < 140.0:
                    best_a_score = dist
                    best_a = ia

            if best_a is None:
                continue
            na = nodes_a[best_a]

            best_b = None
            best_b_score = 1e9
            tcx, tcy = tb[0] + tb[2] * 0.5, tb[1] + tb[3] * 0.5
            for ib, nb in enumerate(nodes_b):
                if ib in used_b or not compatible(na, nb):
                    continue
                kb = _node_payload_kind(nb)
                nt = _node_plain_text(nb)
                if tt and kb == "text" and not _text_matches_traj(nt, tt):
                    continue
                fb_n = _orig_endpoint_frame(nb, at_last_beat=False)
                bcx, bcy = fb_n[0] + fb_n[2] * 0.5, fb_n[1] + fb_n[3] * 0.5
                dist = math.hypot(bcx - tcx, bcy - tcy) + 0.35 * (abs(fb_n[2] - tb[2]) + abs(fb_n[3] - tb[3]))
                if tt and nt == tt:
                    dist -= 120.0
                if kb == "shape":
                    fa_fill = (na.get("shape") or {}).get("fill")
                    fb_fill = (nb.get("shape") or {}).get("fill")
                    if isinstance(fa_fill, str) and isinstance(fb_fill, str) and fa_fill != fb_fill:
                        dist += 200.0
                if dist < best_b_score and dist < 160.0:
                    best_b_score = dist
                    best_b = ib

            if best_b is not None:
                used_a.add(best_a)
                used_b.add(best_b)
                pairs.append((na, nodes_b[best_b]))

        # Pass 3: Match remaining specimens/images/exact-text/shapes that carry over across slides
        for ia, na in enumerate(nodes_a):
            if ia in used_a:
                continue
            ka = _node_payload_kind(na)
            ta = _node_plain_text(na)
            fa = _orig_endpoint_frame(na, at_last_beat=True)
            best_b = None
            best_score = 1e9
            for ib, nb in enumerate(nodes_b):
                if ib in used_b or not compatible(na, nb):
                    continue
                fb_n = _orig_endpoint_frame(nb, at_last_beat=False)
                dist = math.hypot((fa[0] - fb_n[0]), (fa[1] - fb_n[1]))
                if ka == "specimen":
                    # Same specimen ref + params -> always match!
                    if na["specimen"].get("params") == nb["specimen"].get("params"):
                        if dist < best_score:
                            best_score = dist
                            best_b = ib
                elif ka == "image":
                    if dist < best_score:
                        best_score = dist
                        best_b = ib
                elif ka == "text" and ta:
                    tb_txt = _node_plain_text(nb)
                    if ta == tb_txt and dist < best_score:
                        best_score = dist
                        best_b = ib
                elif ka == "shape":
                    # Same shape geometry & fill/stroke staying nearby
                    if na["shape"] == nb["shape"] and dist < 400.0 and dist < best_score:
                        best_score = dist
                        best_b = ib
            if best_b is not None:
                used_a.add(ia)
                used_b.add(best_b)
                pairs.append((na, nodes_b[best_b]))

        # Apply unified IDs from slide A onto slide B safely (resolving any collisions first)
        for na, nb in pairs:
            target_id = na["id"]
            cur_id = nb["id"]
            if cur_id == target_id:
                total_matched += 1
                continue
            existing_ids = _all_slide_node_ids(sb)
            if target_id in existing_ids:
                # Displace colliding unrelated node on slide B first
                alt = f"{target_id}-b"
                k = 2
                while alt in existing_ids:
                    alt = f"{target_id}-b{k}"
                    k += 1
                _rename_node_id_in_slide(sb, target_id, alt)
            _rename_node_id_in_slide(sb, cur_id, target_id)
            total_matched += 1

        # Mandatory post-pairing de-collision: rename any non-paired node on slide B whose ID
        # collides with a present node on slide A so runtime CrossSlideMatcher never creates bogus pairs.
        paired_b_obj_ids = {id(nb) for _, nb in pairs}
        present_a_ids = {na["id"] for na in nodes_a}
        for nb in nodes_b:
            if id(nb) in paired_b_obj_ids:
                continue
            cur_id = nb["id"]
            if cur_id in present_a_ids:
                taken = _all_slide_node_ids(sb) | present_a_ids
                alt = f"{cur_id}-b"
                k = 2
                while alt in taken:
                    alt = f"{cur_id}-b{k}"
                    k += 1
                _rename_node_id_in_slide(sb, cur_id, alt)

    return total_matched


def _collect_event_effect_types(ef_list: List[Dict[str, Any]]) -> List[Tuple[str, str]]:
    out: List[Tuple[str, str]] = []

    def walk(e: Dict[str, Any]) -> None:
        name = e.get("name") or ""
        if name not in MOVIE_EFFECT_NAMES:
            out.append((e.get("type") or "", name))
        for sub in e.get("effects") or []:
            walk(sub)

    for ef in ef_list:
        walk(ef)
    return out


def _collect_movie_map(slide_json: Dict[str, Any]) -> Dict[str, str]:
    """Map KPF objectID -> movie asset filename for every video layer on this slide."""
    assets = slide_json.get("assets") or {}
    movies: Dict[str, str] = {}

    def walk(ef: Dict[str, Any]) -> None:
        oid = str(ef.get("objectID") or "")
        mv = ef.get("movie")
        if isinstance(mv, dict) and mv.get("asset") and oid:
            asset_key = mv["asset"]
            info = assets.get(asset_key) or {}
            url = (info.get("url") or {}).get("native") or asset_key
            movies[oid] = str(url)
        for sub in ef.get("effects") or []:
            walk(sub)

    for ev in slide_json.get("events") or []:
        for ef in ev.get("effects") or []:
            walk(ef)
    return movies


def _collect_animated_oids_and_linedraw(slide_json: Dict[str, Any]) -> Tuple[Set[str], Set[str], Set[str]]:
    """Return (all_buildin_oids, all_animated_oids, linedraw_oids) across all non-transition events."""
    buildin_oids: Set[str] = set()
    all_oids: Set[str] = set()
    linedraw_oids: Set[str] = set()

    def walk(ef: Dict[str, Any]) -> None:
        et = ef.get("type") or ""
        name = ef.get("name") or ""
        oid = str(ef.get("objectID") or "")
        if oid and et != "transition":
            if name not in MOVIE_EFFECT_NAMES:
                all_oids.add(oid)
            if et == "buildIn" and name not in MOVIE_EFFECT_NAMES:
                buildin_oids.add(oid)
            if "LineDraw" in name:
                linedraw_oids.add(oid)
        for sub in ef.get("effects") or []:
            walk(sub)

    for ev in slide_json.get("events") or []:
        for ef in ev.get("effects") or []:
            walk(ef)
    return (buildin_oids, all_oids, linedraw_oids)


def _apply_slide_text_slack_and_descenders(
    nodes: List[Dict[str, Any]],
    stage_w: int,
    stage_h: int,
    oid_to_node: Dict[str, Dict[str, Any]],
    oid_to_state: Dict[str, NodeAnimState],
    pill_pinned_ids: Set[int],
) -> None:
    """
    Systemic unwrapped text `frame.w` slack & descender-safe `frame.h` expansion:
    - Widens unwrapped/newline-broken top-level text frames to compensate for fallback-font
      advance widths vs Keynote's 0-slack glyph ink boxes while preserving the visual
      alignment anchor (`start`: x fixed, `center`: x -= dw/2, `end`: x -= dw).
    - Expands tight ink-only `frame.h` to `>= lineCount * lineHeight + 0.28 * fontSize`
      so bottom descenders never clip.
    - Preserves rotation center pivots and syncs `oid_to_state` before Step 3 builds.
    """
    node_to_oid = {id(n): oid for oid, n in oid_to_node.items()}

    for n in nodes:
        if "text" not in n or "frame" not in n:
            continue
        if id(n) in pill_pinned_ids:
            continue
        tp = n["text"]
        fr = n["frame"]
        x = float(fr["x"])
        y = float(fr["y"])
        w = float(fr["w"])
        h = float(fr["h"])

        val = tp.get("value")
        plain = val if isinstance(val, str) else "".join(str(r.get("text") or "") for r in (val or []))
        if not plain.strip():
            continue

        surf = tp.get("surface")
        if surf:
            # Padded single-line text badges (e.g. Shaders s45/s46 URL pill) need horizontal slack
            # so fallback/Skia glyph advances + surface padding never wrap the tail onto line 2.
            pad = surf.get("padding") if isinstance(surf, dict) else None
            if isinstance(pad, dict) and "\n" not in plain:
                pad_x = float(pad.get("left") or 0.0) + float(pad.get("right") or 0.0)
                new_w = max(w * 1.09 + pad_x, w + 45.0)
                new_x = x
                if new_x + new_w > float(stage_w) - 5.0:
                    new_x = max(0.0, (x + w) - new_w)
                fr["x"] = _num(new_x)
                fr["w"] = _num(new_w)
                oid = node_to_oid.get(id(n))
                if oid and oid in oid_to_state:
                    oid_to_state[oid].x = float(fr["x"])
                    oid_to_state[oid].w = float(fr["w"])
            continue

        font = str(tp.get("font") or "Inter")
        sz = max(float(tp.get("size") or 24.0), 8.0)
        lh = float(tp.get("lineHeight") or _natural_line_height(font, sz))
        line_count = max(1, plain.count("\n") + 1)
        rot_deg = float(n.get("rotation") or 0.0)

        scale_f, add_pad = FONT_SLACK_FACTORS.get(font, (1.22, 28.0))
        target_w = max(w * scale_f + add_pad, w + 24.0)
        target_h = max(h, line_count * lh + sz * 0.28)

        if abs(rot_deg) >= 0.05:
            # Keep rotation center invariant with conservative width slack
            new_w = min(max(w * 1.15 + 16.0, w + 16.0), float(stage_w))
            new_h = target_h
            new_x = x - (new_w - w) * 0.5
            new_y = y - (new_h - h) * 0.5
        else:
            # MagicTextV2 measures paragraphs with minWidth=0 and draws starting at frame.x.
            # Keep x pinned to the exact Keynote PDF left edge and expand w purely to the right
            # so centered/end-aligned single-line text NEVER shifts left onto adjacent icons/pills!
            max_avail = max(w, float(stage_w) - x)
            new_w = min(target_w, max_avail)
            new_x = x
            new_y = y
            new_h = target_h

        fr["x"] = _num(new_x)
        fr["y"] = _num(new_y)
        fr["w"] = _num(max(new_w, 1.0))
        fr["h"] = _num(max(new_h, 1.0))

        oid = node_to_oid.get(id(n))
        if oid and oid in oid_to_state:
            st = oid_to_state[oid]
            st.x = float(fr["x"])
            st.y = float(fr["y"])
            st.w = float(fr["w"])
            st.h = float(fr["h"])


def convert_kpf_to_deck_doc(
    kpf_assets_dir: Path,
    deck_id: str,
    title: str,
    talk_id: str,
    assets_out_dir: Path,
    key_path: Optional[Path] = None,
    extra_svg_dir: Optional[Path] = None,
    notes_by_slide: Optional[Dict[int, str]] = None,
) -> Dict[str, Any]:
    """Convert an Apple Keynote KPF assets directory into a DeckDocument dict."""
    header = json.loads((kpf_assets_dir / "header.json").read_text(encoding="utf-8"))
    stage_w = int(header.get("slideWidth", 1920))
    stage_h = int(header.get("slideHeight", 1080))
    slide_uids: List[str] = list(header.get("slideList") or [])

    key_extractor = KeyAssetExtractor(key_path, assets_out_dir, extra_svg_dir)
    used_families: Set[str] = set()
    used_specimen_refs: Set[str] = set()
    slides_out: List[Dict[str, Any]] = []
    mm_trajectories: Dict[int, List[Dict[str, Any]]] = {}
    orig_frames: Dict[int, Tuple[float, float, float, float]] = {}

    # Pre-load slide JSONs & build deck-wide texture catalog to recover Keynote KPF
    # texture-ID overwrite bugs (e.g. Shaders s35 `);`, Jewel s19 `🐞`, Jewel s45 `Easier to learn`).
    slide_jsons: Dict[str, Dict[str, Any]] = {}
    deck_tex_catalog: Dict[str, List[Tuple[str, int, float, float]]] = {}
    atlas_cache: Dict[str, Optional[KeynotePdfAtlas]] = {}

    def get_atlas(slide_uid: str) -> Optional[KeynotePdfAtlas]:
        if slide_uid not in atlas_cache:
            pp = kpf_assets_dir / slide_uid / "assets" / f"{slide_uid}.pdf"
            atlas_cache[slide_uid] = KeynotePdfAtlas(str(pp)) if pp.exists() else None
        return atlas_cache[slide_uid]

    for uid in slide_uids:
        sj = json.loads((kpf_assets_dir / uid / f"{uid}.json").read_text(encoding="utf-8"))
        slide_jsons[uid] = sj
        for tid, a_info in (sj.get("assets") or {}).items():
            if a_info.get("type") == "texture" and isinstance(a_info.get("index"), int):
                aw = float(a_info.get("width") or 0.0)
                ah = float(a_info.get("height") or 0.0)
                if aw > 0.0 and ah > 0.0:
                    deck_tex_catalog.setdefault(tid, []).append((uid, int(a_info["index"]), aw, ah))

    themes, default_theme, palettes = build_deck_themes(talk_id)

    for s_idx, uid in enumerate(slide_uids):
        slide_num = s_idx + 1
        slide_id = f"s{slide_num:02d}" if len(slide_uids) < 100 else f"s{slide_num:03d}"
        s_json = slide_jsons[uid]
        s_assets = s_json.get("assets") or {}
        events = s_json.get("events") or []

        atlas = get_atlas(uid)

        # Collect all texture IDs referenced on this slide (for unreferenced same-slide fallback)
        referenced_slide_tids: Set[str] = set()
        for _ev in events:
            for _lf in _collect_textured_layers(_ev.get("baseLayer") or {}, (0.0, 0.0)):
                if _lf.get("texture"):
                    referenced_slide_tids.add(str(_lf["texture"]))

        def resolve_leaf_page(lf: Dict[str, Any]) -> Optional[Dict[str, Any]]:
            tid = lf.get("texture")
            a_info = s_assets.get(tid) or {}
            if a_info.get("type") != "texture" or not isinstance(a_info.get("index"), int):
                return None
            exp_w = float(lf["bounds"]["width"])
            exp_h = float(lf["bounds"]["height"])
            aw = float(a_info.get("width") or 0.0)
            ah = float(a_info.get("height") or 0.0)
            dim_mismatch = (
                aw > 0.0
                and ah > 0.0
                and (
                    abs(aw - exp_w) > max(20.0, exp_w * 0.5)
                    or abs(ah - exp_h) > max(20.0, exp_h * 0.5)
                )
            )
            if dim_mismatch:
                # Fallback 1: Same texture ID on another slide matching leaf dimensions
                for other_uid, other_idx, ow, oh in deck_tex_catalog.get(str(tid), ()):
                    if other_uid == uid:
                        continue
                    if (
                        abs(ow - exp_w) <= max(8.0, exp_w * 0.15)
                        and abs(oh - exp_h) <= max(8.0, exp_h * 0.15)
                    ):
                        other_atlas = get_atlas(other_uid)
                        if other_atlas is not None:
                            return other_atlas.parse_page(other_idx)
                # Fallback 2: Unreferenced texture asset on the same slide matching exact leaf dims
                if atlas is not None:
                    want_shape = lf.get("textureType") == 5
                    best_cand = None
                    for cand_tid, cand_info in s_assets.items():
                        if cand_tid in referenced_slide_tids:
                            continue
                        if cand_info.get("type") != "texture" or not isinstance(cand_info.get("index"), int):
                            continue
                        cw = float(cand_info.get("width") or 0.0)
                        ch = float(cand_info.get("height") or 0.0)
                        if abs(cw - exp_w) <= 3.0 and abs(ch - exp_h) <= 3.0:
                            cand_pg = atlas.parse_page(int(cand_info["index"]))
                            if want_shape and cand_pg["shapes"] and not cand_pg["textRuns"]:
                                return cand_pg
                            if best_cand is None:
                                best_cand = cand_pg
                    if best_cand is not None:
                        return best_cand
            if atlas is None:
                return None
            return atlas.parse_page(int(a_info["index"]))

        movie_map = _collect_movie_map(s_json)
        buildin_oids, all_anim_oids, linedraw_oids = _collect_animated_oids_and_linedraw(s_json)
        ev0 = events[0] if events else {}
        pill_pinned_ids: Set[int] = set()
        # Collect accessibility items across ALL events (beat 0 + later build-in beats)
        acc_items: List[Dict[str, Any]] = []
        _seen_acc: Set[Tuple[str, int, int]] = set()
        for _ev in events:
            for _a in _ev.get("accessibility") or []:
                _r = _a.get("targetRectangle") or _a.get("rect") or {}
                _k = (str(_a.get("text") or _a.get("description") or ""), round(float(_r.get("x", 0))), round(float(_r.get("y", 0))))
                if _k not in _seen_acc:
                    _seen_acc.add(_k)
                    acc_items.append(_a)

        used_node_ids: Set[str] = set()

        def alloc_id(preferred: str) -> str:
            base = re.sub(r"[^a-zA-Z0-9_-]+", "-", preferred).strip("-") or "node"
            cand = base
            k = 2
            while cand in used_node_ids:
                cand = f"{base}-{k}"
                k += 1
            used_node_ids.add(cand)
            return cand

        nodes: List[Dict[str, Any]] = []
        backdrop_nodes: List[Dict[str, Any]] = []
        oid_to_node: Dict[str, Dict[str, Any]] = {}
        oid_to_kind: Dict[str, str] = {}
        oid_to_state: Dict[str, NodeAnimState] = {}
        oid_to_text_len: Dict[str, int] = {}

        slide_background: Any = None
        d1_layers = (ev0.get("baseLayer") or {}).get("layers") or []

        # Helper to process a raster Image XObject on any page
        def handle_pdf_image_rec(
            im: Dict[str, Any],
            stage_x: float,
            stage_y: float,
            oid: Optional[str],
            acc_desc: str,
            default_stem: str,
        ) -> Optional[Dict[str, Any]]:
            if atlas is None:
                return None
            nw, nh = im["nativeWidth"], im["nativeHeight"]
            iw, ih = float(im["width"]), float(im["height"])
            if iw < 2.0 or ih < 2.0 or nw <= 1 or nh <= 1:
                return None

            # Filter out AppleColorEmoji 160x160 bitmap glyph duplicates
            if nw == 160 and nh == 160 and iw <= 70 and ih <= 70:
                return None

            # Check genuine static raster assets first for monochrome illustrations (e.g. NJPT S63 XKCD comic)
            # before solid-black drop-shadow filtering, while filtering Quartz drop shadows BEFORE specimens!
            is_xkcd = (talk_id == "njpt" and slide_num == 63)
            if not is_xkcd:
                info = atlas.inspect_image_obj(im["objNum"])
                if info["isSolidBlackMask"]:
                    return None

            # Check Shaders live preview squares (so pasted-movie / no-hole / hole -> live specimen!)
            if talk_id == "shaders":
                sp = classify_shaders_preview_specimen(
                    slide_num,
                    stage_x,
                    stage_y,
                    iw,
                    ih,
                    is_raster=True,
                    is_build_in=(oid is not None and oid in buildin_oids),
                )
                if sp is not None:
                    ref, params, sid = sp
                    used_specimen_refs.add(ref)
                    sp_payload: Dict[str, Any] = {"ref": ref}
                    if params:
                        sp_payload["params"] = params
                    return {
                        "id": alloc_id(sid),
                        "frame": {"x": _num(stage_x), "y": _num(stage_y), "w": _num(iw), "h": _num(ih)},
                        "specimen": sp_payload,
                    }
                # Filter out Shaders S33 curly-brace glow helper (100x393)
                if slide_num == 33 and nw == 100 and nh == 393:
                    return None

            # Check Jewel live UI specimen replacements for screenshots (Task-based Profilers, Interop, Spring Tree, Cohesion)
            if talk_id == "jewel":
                sp = classify_jewel_static_specimen(slide_num, nw, nh, stage_x, stage_y, iw, ih)
                if sp is not None:
                    ref, params, sid = sp
                    used_specimen_refs.add(ref)
                    sp_payload = {"ref": ref}
                    if params:
                        sp_payload["params"] = params
                    return {
                        "id": alloc_id(sid),
                        "frame": {"x": _num(stage_x), "y": _num(stage_y), "w": _num(iw), "h": _num(ih)},
                        "specimen": sp_payload,
                    }

            # Check genuine static raster assets (portraits, illustrations, comics)
            static_match = match_static_raster_asset(talk_id, slide_num, nw, nh, stage_x, stage_y, iw, ih, acc_desc)
            if static_match is not None:
                key_rx, clean_name, sid = static_match
                extracted = key_extractor.extract_matching(key_rx, clean_name) if key_rx else None
                if not extracted:
                    stem = Path(clean_name).stem
                    extracted = atlas.export_image_obj(im["objNum"], assets_out_dir, stem)
                if extracted:
                    return {
                        "id": alloc_id(sid or Path(extracted).stem),
                        "frame": {"x": _num(stage_x), "y": _num(stage_y), "w": _num(iw), "h": _num(ih)},
                        "image": {"src": extracted, "fit": "contain"},
                    }

            # Filter out tiny stray sliver textures (e.g. NJPT S10 6x244)
            if nw < 12 or nh < 12:
                return None

            # Generic fallback for unknown talks: export cleanly with neutral name (never pasted-movie!)
            extracted = atlas.export_image_obj(im["objNum"], assets_out_dir, default_stem)
            if extracted:
                return {
                    "id": alloc_id(default_stem),
                    "frame": {"x": _num(stage_x), "y": _num(stage_y), "w": _num(iw), "h": _num(ih)},
                    "image": {"src": extracted, "fit": "contain"},
                }
            return None

        # ------------------------------------------------------------------
        # 1. Decompose Slot 0 (Page 0 background + static foreground elements)
        # ------------------------------------------------------------------
        p0_tex_ids: Set[str] = set()
        p0_idx: Optional[int] = None
        if d1_layers and atlas is not None:
            d1_0 = d1_layers[0]
            leaves_0 = _collect_textured_layers(d1_0, (0.0, 0.0))
            p0_tex_ids = {lf["texture"] for lf in leaves_0 if lf.get("texture")}
            for lf in leaves_0:
                a_info = s_assets.get(lf["texture"]) or {}
                if a_info.get("type") == "texture" and isinstance(a_info.get("index"), int):
                    p0_idx = a_info["index"]
                    break
            if p0_idx is not None:
                p0 = atlas.parse_page(p0_idx)
                bg_shape_idxs: Set[int] = set()
                bg_grad_idx: Optional[int] = None

                # Detect leading full-canvas background fill(s) or gradient at bottom of page 0
                # (Keynote draws a #000000 canvas base rect at idx 0 followed by the real slide fill at idx 1!)
                for kind_d, idx_d in p0.get("drawOrder") or []:
                    if kind_d == "shape":
                        sh = p0["shapes"][idx_d]
                        fhex = sh.get("fillHex") or ""
                        is_opaque_full = (
                            sh["width"] >= stage_w - 5
                            and sh["height"] >= stage_h - 5
                            and bool(fhex)
                            and len(fhex) == 7
                        )
                        if is_opaque_full:
                            slide_background = fhex
                            bg_shape_idxs.add(idx_d)
                            continue
                    elif kind_d == "gradient":
                        gr = p0["gradients"][idx_d]
                        cb = gr.get("clipBox")
                        is_full_grad = (
                            (cb is not None and cb[2] >= stage_w - 5 and cb[3] >= stage_h - 5)
                            or (cb is None and not bg_shape_idxs)
                        )
                        if is_full_grad:
                            gfill = _pdf_gradient_to_fill(gr, 0.0, 0.0, float(stage_w), float(stage_h))
                            if gfill:
                                slide_background = gfill
                                bg_grad_idx = idx_d
                                continue
                    break

                # Emit non-background static shapes, gradients, and images on page 0 in drawOrder
                # Deduplicate identical page-0 text runs (e.g. duplicate 'render(' on NJPT S096)
                p0_text_runs: List[Dict[str, Any]] = []
                _seen_p0_runs: Set[Tuple[str, float, float, str]] = set()
                for tr in p0["textRuns"]:
                    rk = (
                        str(tr.get("text") or ""),
                        round(float(tr.get("x", 0.0)), 1),
                        round(float(tr.get("baselineY", 0.0)), 1),
                        str(tr.get("colorHex") or ""),
                    )
                    if rk not in _seen_p0_runs:
                        _seen_p0_runs.add(rk)
                        p0_text_runs.append(tr)

                # Pre-match page-0 text runs to accessibility rects so text boxes stay distinct
                # (strict containment pass first so nearby pills like 'Image' never steal 'render('!)
                text_buckets: Dict[int, List[Dict[str, Any]]] = {}
                unbucketed_runs: List[Dict[str, Any]] = []
                acc_rects = []
                for ai, a in enumerate(acc_items):
                    r = a.get("targetRectangle") or a.get("rect") or {}
                    aw, ah = float(r.get("width", 0)), float(r.get("height", 0))
                    if aw > 1 and ah > 1:
                        acc_rects.append((ai, float(r.get("x", 0)), float(r.get("y", 0)), aw, ah, str(a.get("text") or a.get("description") or "")))

                for tr in p0_text_runs:
                    tx = float(tr["x"])
                    tr_sz = max(float(tr.get("fontSize") or 16.0), 8.0)
                    tr_txt = str(tr.get("text") or "").strip()
                    tr_fam, _, _ = map_pdf_font(tr.get("font") or "Inter")
                    tr_est_w = _estimate_text_width(tr_txt, tr_fam, tr_sz)
                    tx_mid = tx + min(tr_est_w * 0.5, 48.0)
                    ty = float(tr["baselineY"]) - tr_sz * 0.4
                    best_ai = None
                    best_key = (1, 1, 1e18)
                    for margin in (2.0, 18.0):
                        for ai, ax, ay, aw, ah, adesc in acc_rects:
                            if (ax - margin) <= tx <= (ax + aw + margin) and (ay - margin) <= ty <= (ay + ah + margin):
                                text_mismatch = 0 if (tr_txt and adesc and tr_txt in adesc) else 1
                                center_outside = 0 if (ax <= tx_mid <= ax + aw) else 1
                                key = (text_mismatch, center_outside, aw * ah)
                                if key < best_key:
                                    best_key = key
                                    best_ai = ai
                        if best_ai is not None:
                            break
                    if best_ai is not None:
                        text_buckets.setdefault(best_ai, []).append(tr)
                    else:
                        unbucketed_runs.append(tr)

                # Track which shapes are already claimed by a gradient clipBox
                emitted_p0_svg_keys: Set[str] = set()
                p0_pill_shapes: List[Tuple[float, float, float, float]] = []

                for kind_d, idx_d in p0.get("drawOrder") or []:
                    if kind_d == "shape":
                        if idx_d in bg_shape_idxs:
                            continue
                        sh = p0["shapes"][idx_d]
                        sx, sy, sw, shh = float(sh["x"]), float(sh["y"]), float(sh["width"]), float(sh["height"])
                        if sw < 0.5 and shh < 0.5:
                            continue
                        # Check if this static shape on page 0 is a Shaders solid preview square (S14..S23)
                        if talk_id == "shaders":
                            sp = classify_shaders_preview_specimen(slide_num, sx, sy, sw, shh, fill_hex=sh.get("fillHex"), is_raster=False)
                            if sp is not None:
                                ref, params, sid = sp
                                used_specimen_refs.add(ref)
                                sp_payload = {"ref": ref}
                                if params:
                                    sp_payload["params"] = params
                                sp_node_doc = {
                                    "id": alloc_id(sid),
                                    "frame": {"x": _num(sx), "y": _num(sy), "w": _num(sw), "h": _num(shh)},
                                    "specimen": sp_payload,
                                }
                                _attach_interactive_mode(sp_node_doc, ref)
                                nodes.append(sp_node_doc)
                                continue
                        # Check if this shape lies inside a known SVG replacement on page 0 (e.g. NJPT S03/S75/S111, Jewel S22/S24, Shaders S16/S46)
                        acc_d = _find_best_acc_desc((sx, sy, sw, shh), acc_items)
                        svg_m = match_key_svg_replacement(talk_id, slide_num, (sx, sy, sw, shh), acc_d)
                        if svg_m is not None:
                            key_rx, clean_svg, sid = svg_m
                            if clean_svg not in emitted_p0_svg_keys:
                                ex = key_extractor.extract_matching(key_rx, clean_svg)
                                if ex:
                                    emitted_p0_svg_keys.add(clean_svg)
                                    nodes.append({
                                        "id": alloc_id(sid),
                                        "frame": {"x": _num(sx), "y": _num(sy), "w": _num(sw), "h": _num(shh)},
                                        "image": {"src": ex, "fit": "contain"},
                                    })
                            continue
                        sp_node = _shape_rec_to_node_payload(sh)
                        if sp_node:
                            if sh.get("fillHex") and sw >= 80.0 and 45.0 <= shh <= 220.0:
                                p0_pill_shapes.append((sx, sy, sw, shh))
                            nodes.append({
                                "id": alloc_id(f"bg-{sh['kind']}"),
                                "frame": {"x": _num(sx), "y": _num(sy), "w": _num(max(sw, 1.0)), "h": _num(max(shh, 1.0))},
                                "shape": sp_node,
                            })
                    elif kind_d == "gradient":
                        if idx_d == bg_grad_idx:
                            continue
                        gr = p0["gradients"][idx_d]
                        cb = gr.get("clipBox")
                        if cb is not None:
                            gx, gy, gw, gh, gk, gcr, gsd = cb
                            gfill = _pdf_gradient_to_fill(gr, gx, gy, gw, gh)
                            if gfill and gw >= 1.0 and gh >= 1.0:
                                sp_d: Dict[str, Any] = {
                                    "geometry": gk if gk in ("rect", "ellipse", "line") else {"path": gsd},
                                    "fill": gfill,
                                }
                                if gk == "rect" and gcr is not None and gcr > 0.5:
                                    sp_d["cornerRadius"] = _num(gcr, 2)
                                nodes.append({
                                    "id": alloc_id("bg-gradient-shape"),
                                    "frame": {"x": _num(gx), "y": _num(gy), "w": _num(gw), "h": _num(gh)},
                                    "shape": sp_d,
                                })
                    elif kind_d == "image":
                        im = p0["images"][idx_d]
                        acc_d = _find_best_acc_desc((im["x"], im["y"], im["width"], im["height"]), acc_items)
                        node_im = handle_pdf_image_rec(
                            im, float(im["x"]), float(im["y"]), None, acc_d, f"{slide_id}-img-{idx_d}"
                        )
                        if node_im:
                            nodes.append(node_im)

                # Emit page-0 static text buckets with exact PDF baseline & pill centering
                acc_by_idx = {ai: (ax, ay, aw, ah, adesc) for ai, ax, ay, aw, ah, adesc in acc_rects}
                for ai in sorted(text_buckets.keys()):
                    b_runs = text_buckets[ai]
                    ax, ay, aw, ah, _ = acc_by_idx[ai]
                    res = _runs_to_text_payload_with_meta(b_runs, used_families)
                    if res:
                        tp, plain, meta = res
                        bl_off = _font_baseline_offset(meta["domFont"], meta["domSize"], meta["lineHeight"])
                        ny = meta["firstBaselineY"] - bl_off
                        nx, nw, nh = ax, max(aw, 10.0), max(ah, meta["lineCount"] * meta["lineHeight"] + meta["domSize"] * 0.28)
                        pinned_pill = False
                        # If this page-0 label sits centered inside a filled page-0 pill (e.g. NJPT S096 AST pills),
                        # wrap it in a stack covering the pill with `align: "center"` (no child frame) so
                        # DocumentStackChildrenLayout centers the actual rendered text metrics symmetrically!
                        pill_box: Optional[Tuple[float, float, float, float]] = None
                        if meta["lineCount"] <= 2:
                            tx0 = meta["minRunX"]
                            tx1 = tx0 + meta["maxLineEstW"]
                            tcy = meta["firstBaselineY"] - meta["domSize"] * 0.35
                            for px, py, pw, ph in p0_pill_shapes:
                                if (px - 6.0) <= tx0 and tx1 <= (px + pw + 24.0) and py <= tcy <= (py + ph):
                                    left_m = tx0 - px
                                    right_m = (px + pw) - tx1
                                    if left_m >= 6.0 and abs(left_m - right_m) <= max(60.0, pw * 0.28):
                                        pill_box = (px, py, pw, ph)
                                        break
                        if pill_box is not None:
                            px, py, pw, ph = pill_box
                            tp["align"] = "center"
                            lbl_id = alloc_id(f"{_slug(plain, 18)}-label")
                            t_node = {
                                "id": alloc_id(_slug(plain)),
                                "frame": {"x": _num(px), "y": _num(py), "w": _num(pw), "h": _num(ph)},
                                "stack": {
                                    "children": [
                                        {
                                            "id": lbl_id,
                                            "align": "center",
                                            "text": tp,
                                        }
                                    ],
                                    "alignItems": "center",
                                },
                            }
                            orig_frames[id(t_node)] = (px, py, pw, ph)
                            pill_pinned_ids.add(id(t_node))
                            nodes.append(t_node)
                        else:
                            t_node = {
                                "id": alloc_id(_slug(plain)),
                                "frame": {"x": _num(nx), "y": _num(ny), "w": _num(nw), "h": _num(nh)},
                                "text": tp,
                            }
                            orig_frames[id(t_node)] = (ax, ay, max(aw, 10.0), max(ah, 10.0))
                            nodes.append(t_node)

                # Cluster any remaining unbucketed page-0 text runs by vertical proximity
                if unbucketed_runs:
                    unbucketed_runs.sort(key=lambda r: (r["baselineY"], r["x"]))
                    clusters: List[List[Dict[str, Any]]] = []
                    for r in unbucketed_runs:
                        fsz = max(float(r.get("fontSize") or 16.0), 8.0)
                        if clusters and abs(r["baselineY"] - clusters[-1][-1]["baselineY"]) <= fsz * 1.9:
                            clusters[-1].append(r)
                        else:
                            clusters.append([r])
                    for cl in clusters:
                        res = _runs_to_text_payload_with_meta(cl, used_families)
                        if res:
                            tp, plain, meta = res
                            bl_off = _font_baseline_offset(meta["domFont"], meta["domSize"], meta["lineHeight"])
                            min_x = meta["minRunX"]
                            min_y = meta["firstBaselineY"] - bl_off
                            est_w = max(meta["maxLineEstW"], 40.0)
                            t_node = {
                                "id": alloc_id(_slug(plain)),
                                "frame": {
                                    "x": _num(min_x),
                                    "y": _num(min_y),
                                    "w": _num(min(stage_w - min_x, est_w + 24.0)),
                                    "h": _num(meta["lineCount"] * meta["lineHeight"] + meta["domSize"] * 0.28),
                                },
                                "text": tp,
                            }
                            nodes.append(t_node)

        # ------------------------------------------------------------------
        # 2. Decompose Slots d1[1:] (animated & foreground KPF object layers)
        # ------------------------------------------------------------------
        for slot_i, d1 in enumerate(d1_layers[1:], start=1):
            leaves = _collect_textured_layers(d1, (0.0, 0.0))
            if not leaves:
                continue

            # Determine primary objectID (depth 2)
            oid = None
            d1_children = d1.get("layers") or []
            if d1_children and d1_children[0].get("objectID"):
                oid = str(d1_children[0]["objectID"])
            if not oid:
                for lf in leaves:
                    if lf.get("objectID"):
                        oid = str(lf["objectID"])
                        break
            has_explicit_oid = oid is not None
            if not oid:
                oid = f"slot-{slot_i}"

            # Skip duplicate full-stage background-composite layers pointing back at page 0,
            # while preserving smaller foreground objects whose texture ID collided with page 0
            # (e.g. Shaders S11 d1[1] 250x156 'GLSL' pill recovered via cross-slide deck_tex_catalog!).
            if not has_explicit_oid and oid not in all_anim_oids and oid not in movie_map:
                is_full_stage_dup = all(
                    float(lf["bounds"]["width"]) >= stage_w - 8.0
                    and float(lf["bounds"]["height"]) >= stage_h - 8.0
                    for lf in leaves
                )
                if is_full_stage_dup and all(lf["texture"] in p0_tex_ids for lf in leaves):
                    continue
                if is_full_stage_dup and p0_idx is not None and all(
                    (s_assets.get(lf["texture"]) or {}).get("index") == p0_idx for lf in leaves
                ):
                    continue

            # Skip permanently hidden layers that never receive a buildIn
            all_hidden = all(lf["hidden"] for lf in leaves)
            if all_hidden and oid not in buildin_oids and oid not in movie_map:
                continue

            # Compute union stage bbox across all leaves belonging to this object
            min_x = min(lf["bounds"]["x"] for lf in leaves)
            min_y = min(lf["bounds"]["y"] for lf in leaves)
            max_x = max(lf["bounds"]["x"] + lf["bounds"]["width"] for lf in leaves)
            max_y = max(lf["bounds"]["y"] + lf["bounds"]["height"] for lf in leaves)
            bw = max(max_x - min_x, 1.0)
            bh = max(max_y - min_y, 1.0)

            # Resting rotation / opacity from d2 if present
            d2_bounds = _layer_stage_bounds(d1_children[0], (0.0, 0.0)) if d1_children else leaves[0]
            rot_deg = float(d2_bounds.get("rotationDeg", 0.0))
            raw_rest_opacity = float(d2_bounds.get("opacity", 1.0))
            is_movie = (oid in movie_map) or any(lf["isVideoLayer"] for lf in leaves)

            # Keynote marks buildIn objects hidden=true with opacity=1, whereas genuinely transparent
            # layers (e.g. Jewel s09 `?` marks animated via action-opacity, Jewel s28 Magic Move entry)
            # have hidden=false and initialState.opacity=0. On Jewel s30, dim the 5 tiled background
            # Gemini cards to 0.25 so `geminiToolWindowFull` pops cleanly on top!
            if talk_id == "jewel" and slide_num == 30 and is_movie and raw_rest_opacity <= 0.001:
                rest_opacity = 0.25
            elif raw_rest_opacity <= 0.001:
                rest_opacity = 1.0 if (all_hidden or oid in buildin_oids) else 0.0
            else:
                rest_opacity = raw_rest_opacity

            acc_desc = _find_best_acc_desc((min_x, min_y, bw, bh), acc_items)

            def register_node(node_obj: Dict[str, Any], text_len: int = 0, is_backdrop: bool = False) -> None:
                if abs(rot_deg) >= 0.05:
                    node_obj["rotation"] = _num(rot_deg, 2)
                if rest_opacity < 0.995:
                    node_obj["opacity"] = _num(max(0.0, rest_opacity), 3)
                orig_frames[id(node_obj)] = (min_x, min_y, bw, bh)
                if is_backdrop:
                    backdrop_nodes.append(node_obj)
                else:
                    nodes.append(node_obj)
                oid_to_node[oid] = node_obj
                oid_to_kind[oid] = _node_payload_kind(node_obj)
                fr = node_obj["frame"]
                oid_to_state[oid] = NodeAnimState(
                    x=float(fr["x"]),
                    y=float(fr["y"]),
                    w=float(fr["w"]),
                    h=float(fr["h"]),
                    opacity=float(node_obj.get("opacity", 1.0)),
                    rotation_deg=float(node_obj.get("rotation", 0.0)),
                )
                oid_to_text_len[oid] = text_len

            # A. Video / Movie layer -> Live Specimen!
            if is_movie:
                mv_name = movie_map.get(oid, "") or acc_desc
                ref, params, stable_id = resolve_movie_specimen(mv_name, talk_id, slide_num, (min_x, min_y, bw, bh))
                used_specimen_refs.add(ref)
                sp_payload = {"ref": ref}
                if params:
                    sp_payload["params"] = params
                node = {
                    "id": alloc_id(stable_id or f"specimen-{_slug(ref)}"),
                    "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                    "specimen": sp_payload,
                }
                _attach_interactive_mode(node, ref)
                is_full_backdrop = (ref == "shaders.plaidHyperspaceStarfield") or (bw >= stage_w - 5 and bh >= stage_h - 5 and slide_num in (45, 46) and talk_id == "shaders")
                register_node(node, is_backdrop=is_full_backdrop)
                continue

            # B. Known crisp .key SVG replacement (replaces multi-path PDF vectorizations like QR code & logos!)
            svg_m = match_key_svg_replacement(talk_id, slide_num, (min_x, min_y, bw, bh), acc_desc)
            if svg_m is not None:
                key_rx, clean_svg, sid = svg_m
                extracted = key_extractor.extract_matching(key_rx, clean_svg)
                if extracted:
                    node = {
                        "id": alloc_id(sid),
                        "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                        "image": {"src": extracted, "fit": "contain"},
                    }
                    register_node(node)
                    continue

            # C. Parse PDF pages referenced by this slot's leaves (with cross-slide & dim-match recovery)
            slot_text_runs: List[Dict[str, Any]] = []
            slot_shapes: List[Dict[str, Any]] = []
            slot_gradients: List[Dict[str, Any]] = []
            slot_images: List[Dict[str, Any]] = []

            for lf in leaves:
                pg = resolve_leaf_page(lf)
                if pg is None:
                    continue
                lx, ly = float(lf["bounds"]["x"]), float(lf["bounds"]["y"])
                lw = float(lf["bounds"]["width"])
                for tr in pg["textRuns"]:
                    r_copy = dict(tr)
                    r_copy["x"] = round(float(tr["x"]) + lx, 2)
                    r_copy["baselineY"] = round(float(tr["baselineY"]) + ly, 2)
                    if len(leaves) > 1:
                        r_copy["_leafLeft"] = lx
                        r_copy["_leafRight"] = lx + lw
                    slot_text_runs.append(r_copy)
                for sh in pg["shapes"]:
                    s_copy = dict(sh)
                    s_copy["x"] = round(float(sh["x"]) + lx, 2)
                    s_copy["y"] = round(float(sh["y"]) + ly, 2)
                    slot_shapes.append(s_copy)
                for gr in pg["gradients"]:
                    g_copy = dict(gr)
                    if "stageCoords" in gr:
                        sc = list(gr["stageCoords"])
                        if gr.get("shadingType") == 3 and len(sc) >= 6:
                            sc[0] += lx; sc[1] += ly; sc[3] += lx; sc[4] += ly
                        elif len(sc) >= 4:
                            sc[0] += lx; sc[1] += ly; sc[2] += lx; sc[3] += ly
                        g_copy["stageCoords"] = sc
                    if "clipBox" in gr and gr["clipBox"] is not None:
                        cb = gr["clipBox"]
                        g_copy["clipBox"] = (cb[0] + lx, cb[1] + ly, cb[2], cb[3], cb[4], cb[5], cb[6])
                    slot_gradients.append(g_copy)
                for im in pg["images"]:
                    im_copy = dict(im)
                    im_copy["x"] = round(float(im["x"]) + lx, 2)
                    im_copy["y"] = round(float(im["y"]) + ly, 2)
                    slot_images.append(im_copy)

            # D. Shaders solid-color preview square check (S14..S23)
            if talk_id == "shaders" and not slot_text_runs and not slot_images and len(slot_shapes) >= 1:
                fill0 = next((sh.get("fillHex") for sh in slot_shapes if sh.get("fillHex")), None)
                sp = classify_shaders_preview_specimen(slide_num, min_x, min_y, bw, bh, fill_hex=fill0, is_raster=False)
                if sp is not None:
                    ref, params, sid = sp
                    used_specimen_refs.add(ref)
                    sp_payload = {"ref": ref}
                    if params:
                        sp_payload["params"] = params
                    node = {
                        "id": alloc_id(sid),
                        "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                        "specimen": sp_payload,
                    }
                    register_node(node)
                    continue

            # E. Raster image / specimen slot
            if slot_images and not slot_text_runs:
                chosen_img_node = None
                chosen_im_rec = None
                for im_i, im in enumerate(slot_images):
                    cand = handle_pdf_image_rec(
                        im,
                        float(im["x"]),
                        float(im["y"]),
                        oid,
                        acc_desc,
                        f"{slide_id}-obj-{slot_i}-{im_i}",
                    )
                    if cand is not None:
                        chosen_img_node = cand
                        chosen_im_rec = im
                        break
                if chosen_img_node is not None and chosen_im_rec is not None:
                    iw = float(chosen_im_rec["width"])
                    ih = float(chosen_im_rec["height"])
                    im_rel_x = float(chosen_im_rec["x"]) - min_x
                    im_rel_y = float(chosen_im_rec["y"]) - min_y
                    img_overflows_card = (
                        bool(slot_shapes)
                        and slot_shapes[0].get("fillHex") is not None
                        and float(slot_shapes[0]["width"]) >= bw * 0.85
                        and float(slot_shapes[0]["height"]) >= bh * 0.85
                        and (iw > bw * 1.05 or ih > bh * 1.05 or im_rel_x < -2.0 or im_rel_y < -2.0)
                    )
                    # Clipped portrait/image inside a rounded backing card (e.g. Jewel S03 Chris & Seb!)
                    if img_overflows_card:
                        sh0 = slot_shapes[0]
                        cr = _infer_rounded_rect_radius(sh0)
                        surf: Dict[str, Any] = {"fill": sh0["fillHex"]}
                        if sh0.get("strokeHex"):
                            surf["stroke"] = {
                                "color": sh0["strokeHex"],
                                "width": _num(max(float(sh0.get("strokeWidth") or 1.0), 0.5), 2),
                            }
                        if cr > 0.5:
                            surf["cornerRadius"] = _num(cr, 2)
                        card_id = chosen_img_node["id"]
                        chosen_img_node["id"] = alloc_id(f"{card_id}-photo")
                        chosen_img_node["frame"] = {
                            "x": _num(im_rel_x),
                            "y": _num(im_rel_y),
                            "w": _num(max(iw, 1.0)),
                            "h": _num(max(ih, 1.0)),
                        }
                        node = {
                            "id": card_id,
                            "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                            "stack": {
                                "children": [chosen_img_node],
                                "surface": surf,
                                "clip": True,
                            },
                        }
                        register_node(node)
                    # Composite shape group containing a smaller inset specimen/image (e.g. Shaders S33 curtain)
                    elif slot_shapes and (iw < bw * 0.8 or ih < bh * 0.8):
                        children: List[Dict[str, Any]] = []
                        for si_s, sh in enumerate(slot_shapes):
                            sp_n = _shape_rec_to_node_payload(sh)
                            if sp_n:
                                children.append({
                                    "id": alloc_id(f"shape-{slot_i}-{si_s + 1}"),
                                    "frame": {
                                        "x": _num(float(sh["x"]) - min_x),
                                        "y": _num(float(sh["y"]) - min_y),
                                        "w": _num(max(float(sh["width"]), 1.0)),
                                        "h": _num(max(float(sh["height"]), 1.0)),
                                    },
                                    "shape": sp_n,
                                })
                        chosen_img_node["frame"] = {
                            "x": _num(im_rel_x),
                            "y": _num(im_rel_y),
                            "w": _num(max(iw, 1.0)),
                            "h": _num(max(ih, 1.0)),
                        }
                        children.append(chosen_img_node)
                        node = {
                            "id": alloc_id(f"group-{slot_i}"),
                            "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                            "stack": {"children": children},
                        }
                        register_node(node)
                    else:
                        # Prefer the slot's exact stage bbox for standalone specimens/images so Magic Move bounds match 100%
                        chosen_img_node["frame"] = {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)}
                        register_node(chosen_img_node)
                    continue

            # F. LineDraw vector slot -> MUST be a single `shape` node
            if oid in linedraw_oids and (slot_shapes or slot_gradients):
                if len(slot_shapes) == 1:
                    sp_node = _shape_rec_to_node_payload(slot_shapes[0])
                else:
                    sp_node = _merge_shapes_to_single_shape(slot_shapes, min_x, min_y)
                if sp_node:
                    node = {
                        "id": alloc_id(f"line-{slot_i}"),
                        "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                        "shape": sp_node,
                    }
                    register_node(node)
                    continue

            # G. Pure text slot (no background shape, or text only)
            if slot_text_runs and not slot_shapes and not slot_gradients:
                # Split single-line text slots with wide intentional horizontal gaps (> 2.0 * fontSize)
                # bridging across an intervening stage layer (e.g. Shaders S02 `"what are "` ... `"?"`
                # spanning across `"shaders"`).
                run_islands: List[List[Dict[str, Any]]] = [slot_text_runs]
                if abs(rot_deg) < 0.05 and len(slot_text_runs) >= 2:
                    ys_g = [float(r.get("baselineY") or 0.0) for r in slot_text_runs]
                    if max(ys_g) - min(ys_g) <= 8.0:
                        sorted_g = sorted(slot_text_runs, key=lambda r: float(r.get("x") or 0.0))
                        islands_tmp: List[List[Dict[str, Any]]] = [[sorted_g[0]]]
                        for gi_r in range(1, len(sorted_g)):
                            pr_g = sorted_g[gi_r - 1]
                            cr_g = sorted_g[gi_r]
                            pfam_g, _, _ = map_pdf_font(pr_g.get("font") or "Inter")
                            psz_g = max(float(pr_g.get("fontSize") or 16.0), 8.0)
                            pr_right_g = float(pr_g.get("x") or 0.0) + _estimate_text_width(
                                str(pr_g.get("text") or ""), pfam_g, psz_g
                            )
                            if "_leafRight" in pr_g:
                                pr_right_g = max(pr_right_g, float(pr_g["_leafRight"]))
                            gap_g = float(cr_g.get("x") or 0.0) - pr_right_g
                            if gap_g > psz_g * 2.0:
                                islands_tmp.append([cr_g])
                            else:
                                islands_tmp[-1].append(cr_g)
                        if len(islands_tmp) > 1:
                            run_islands = islands_tmp

                if len(run_islands) > 1:
                    emitted_any_island = False
                    for isl_runs in run_islands:
                        isl_res = _runs_to_text_payload_with_meta(isl_runs, used_families)
                        if not isl_res:
                            continue
                        isl_tp, isl_plain, isl_meta = isl_res
                        if isinstance(isl_tp.get("value"), str):
                            isl_tp["value"] = isl_tp["value"].strip(" ")
                            isl_plain = isl_plain.strip(" ")
                        if not isl_plain.strip():
                            continue
                        bl_off_i = _font_baseline_offset(isl_meta["domFont"], isl_meta["domSize"], isl_meta["lineHeight"])
                        iny = isl_meta["firstBaselineY"] - bl_off_i
                        inx = isl_meta["minRunX"]
                        inw = max(isl_meta["maxLineEstW"], 24.0)
                        inh = max(bh, isl_meta["lineHeight"] + isl_meta["domSize"] * 0.28)
                        isl_slug = "question-mark" if isl_plain.strip() == "?" else _slug(isl_plain)
                        isl_node = {
                            "id": alloc_id(isl_slug),
                            "frame": {"x": _num(inx), "y": _num(iny), "w": _num(inw), "h": _num(inh)},
                            "text": isl_tp,
                        }
                        if not emitted_any_island:
                            register_node(isl_node, text_len=len(isl_plain))
                            emitted_any_island = True
                        else:
                            if rest_opacity < 0.995:
                                isl_node["opacity"] = _num(max(0.0, rest_opacity), 3)
                            nodes.append(isl_node)
                    if emitted_any_island:
                        continue

                res = _runs_to_text_payload_with_meta(slot_text_runs, used_families)
                if res:
                    tp, plain, meta = res
                    # Rescue Quartz ZWJ emojis (e.g. 👨‍🍳 on NJPT S57) whose PDF ToUnicode CMap is empty
                    if (
                        tp.get("font") == "Noto Emoji"
                        and isinstance(tp.get("value"), str)
                        and all(ord(c) < 128 for c in plain.strip())
                        and acc_desc.strip()
                        and any(ord(c) > 127 for c in acc_desc)
                    ):
                        tp["value"] = acc_desc.strip()
                        plain = acc_desc.strip()
                    nx, ny, nw, nh = min_x, min_y, bw, bh
                    if abs(rot_deg) < 0.05:
                        bl_off = _font_baseline_offset(meta["domFont"], meta["domSize"], meta["lineHeight"])
                        ny = meta["firstBaselineY"] - bl_off
                        # Respect genuine left padding inside padded text slots (e.g. Jewel S10 `4\n3`, Shaders S28 `(`)
                        # while guarding against negative-sidebearing ligatures (`//`, `...`, `->`, `*/)`).
                        rel_x = meta["minRunX"] - min_x
                        if rel_x > 4.0 and (rel_x + meta["firstLineEstW"]) <= (bw + 6.0):
                            nx = meta["minRunX"]
                            nw = max(bw - rel_x, meta["maxLineEstW"])
                    node = {
                        "id": alloc_id(_slug(plain)),
                        "frame": {"x": _num(nx), "y": _num(ny), "w": _num(max(nw, 1.0)), "h": _num(max(nh, 1.0))},
                        "text": tp,
                    }
                    register_node(node, text_len=len(plain))
                    continue

            # H. Combined Shape + Text card/badge/callout
            if slot_text_runs and (slot_shapes or slot_gradients):
                res = _runs_to_text_payload_with_meta(slot_text_runs, used_families)
                if res:
                    tp, plain, meta = res
                    bl_off = _font_baseline_offset(meta["domFont"], meta["domSize"], meta["lineHeight"])
                    rel_run_x = meta["minRunX"] - min_x
                    if rel_run_x < 0.0 or rel_run_x >= bw * 0.90:
                        rel_run_x = 0.0
                    raw_rel_ty = meta["firstBaselineY"] - min_y - bl_off
                    rel_ty = max(0.0, raw_rel_ty)

                    # Detect horizontally centered pill / badge label inside a primary background shape
                    primary_bg = None
                    for sh in slot_shapes:
                        if sh.get("fillHex") and float(sh["width"]) >= bw * 0.75 and float(sh["height"]) >= bh * 0.65:
                            primary_bg = sh
                            break
                    is_centered_pill = False
                    pill_x, pill_y, pill_w, pill_h = 0.0, 0.0, bw, bh
                    if primary_bg is not None and meta["lineCount"] <= 2:
                        sx0 = float(primary_bg["x"])
                        sy0 = float(primary_bg["y"])
                        sw0 = float(primary_bg["width"])
                        sh0 = float(primary_bg["height"])
                        left_m = meta["minRunX"] - sx0
                        right_m = (sx0 + sw0) - (meta["minRunX"] + meta["maxLineEstW"])
                        if left_m >= 10.0 and right_m >= -12.0 and abs(left_m - right_m) <= max(55.0, sw0 * 0.24):
                            is_centered_pill = True
                            pill_x = sx0 - min_x
                            pill_y = sy0 - min_y
                            pill_w = sw0
                            pill_h = sh0

                    # If single background rect + text -> clean surface node (centered pills use a stack
                    # with `align: "center"` child so Compose centers actual rendered glyph bounds!).
                    if len(slot_shapes) == 1 and not slot_gradients and slot_shapes[0].get("kind") == "rect":
                        sh0 = slot_shapes[0]
                        surf: Dict[str, Any] = {}
                        if sh0.get("fillHex"):
                            surf["fill"] = sh0["fillHex"]
                        if sh0.get("strokeHex"):
                            surf["stroke"] = {
                                "color": sh0["strokeHex"],
                                "width": _num(max(float(sh0.get("strokeWidth") or 1.0), 0.5), 2),
                            }
                        if sh0.get("cornerRadius") is not None and float(sh0["cornerRadius"]) > 0.5:
                            surf["cornerRadius"] = _num(float(sh0["cornerRadius"]), 2)
                        if is_centered_pill:
                            tp["align"] = "center"
                            lbl_id = alloc_id(f"{_slug(plain, 18)}-label")
                            node = {
                                "id": alloc_id(_slug(plain)),
                                "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                                "stack": {
                                    "children": [
                                        {
                                            "id": lbl_id,
                                            "align": "center",
                                            "text": tp,
                                        }
                                    ],
                                    "alignItems": "center",
                                    **({"surface": surf} if surf else {}),
                                },
                            }
                            register_node(node, text_len=len(plain))
                            continue
                        if raw_rel_ty < 0.0:
                            ny = meta["firstBaselineY"] - bl_off
                            nh = max(bh + (-raw_rel_ty), meta["lineCount"] * meta["lineHeight"] + meta["domSize"] * 0.28)
                        else:
                            ny = min_y
                            nh = max(bh, raw_rel_ty + meta["lineCount"] * meta["lineHeight"] + meta["domSize"] * 0.28)
                        pad_l = 0.0
                        pad_r = 0.0
                        if rel_run_x >= 4.0 or rel_ty >= 4.0:
                            pad_l = _num(max(0.0, rel_run_x), 1)
                            pad_t = _num(max(0.0, rel_ty), 1)
                            pad_r = _num(min(max(0.0, rel_run_x), 24.0), 1)
                            surf["padding"] = {"left": pad_l, "top": pad_t, "right": pad_r, "bottom": 0}
                        nw = max(bw, meta["maxLineEstW"] + float(pad_l) + float(pad_r))
                        if surf:
                            tp["surface"] = surf
                        node = {
                            "id": alloc_id(_slug(plain)),
                            "frame": {"x": _num(min_x), "y": _num(ny), "w": _num(nw), "h": _num(nh)},
                            "text": tp,
                        }
                        register_node(node, text_len=len(plain))
                        continue

                    # Multi-shape + text -> explicit-frame `stack` (safe for Magic Move)
                    children: List[Dict[str, Any]] = []
                    for si_s, sh in enumerate(slot_shapes):
                        sp_n = _shape_rec_to_node_payload(sh)
                        if sp_n:
                            children.append({
                                "id": alloc_id(f"{_slug(plain, 16)}-bg-{si_s + 1}"),
                                "frame": {
                                    "x": _num(float(sh["x"]) - min_x),
                                    "y": _num(float(sh["y"]) - min_y),
                                    "w": _num(max(float(sh["width"]), 1.0)),
                                    "h": _num(max(float(sh["height"]), 1.0)),
                                },
                                "shape": sp_n,
                            })
                    for gi_g, gr in enumerate(slot_gradients):
                        cb = gr.get("clipBox")
                        gx, gy, gw, gh = (cb[0], cb[1], cb[2], cb[3]) if cb else (min_x, min_y, bw, bh)
                        gfill = _pdf_gradient_to_fill(gr, gx, gy, gw, gh)
                        if gfill:
                            gk = cb[4] if cb else "rect"
                            sp_g: Dict[str, Any] = {
                                "geometry": gk if gk in ("rect", "ellipse", "line") else {"path": cb[6]},
                                "fill": gfill,
                            }
                            if cb and gk == "rect" and cb[5] is not None and cb[5] > 0.5:
                                sp_g["cornerRadius"] = _num(cb[5], 2)
                            children.append({
                                "id": alloc_id(f"{_slug(plain, 16)}-grad-{gi_g + 1}"),
                                "frame": {
                                    "x": _num(gx - min_x),
                                    "y": _num(gy - min_y),
                                    "w": _num(max(gw, 1.0)),
                                    "h": _num(max(gh, 1.0)),
                                },
                                "shape": sp_g,
                            })

                    min_lbl_h = meta["lineCount"] * meta["lineHeight"] + meta["domSize"] * 0.28
                    pill_centered_in_stack = (
                        is_centered_pill
                        and abs((pill_x + pill_w * 0.5) - bw * 0.5) <= 8.0
                        and abs((pill_y + pill_h * 0.5) - bh * 0.5) <= 8.0
                    )
                    if pill_centered_in_stack:
                        tp["align"] = "center"
                        children.append({
                            "id": alloc_id(f"{_slug(plain, 18)}-label"),
                            "align": "center",
                            "text": tp,
                        })
                    else:
                        if is_centered_pill:
                            tp["align"] = "center"
                            lbl_x = max(pill_x + 8.0, rel_run_x)
                            lbl_y = rel_ty
                            lbl_w = max(pill_w - (lbl_x - pill_x), meta["maxLineEstW"] * 1.2 + 24.0)
                            lbl_h = max(bh - lbl_y, min_lbl_h)
                        else:
                            lbl_x = rel_run_x
                            lbl_y = rel_ty
                            right_pad = min(rel_run_x, 20.0) if (primary_bg is not None and rel_run_x >= 16.0) else 0.0
                            avail_w = max(bw - lbl_x - right_pad, 24.0)
                            scale_f, add_p = FONT_SLACK_FACTORS.get(meta["domFont"], (1.12, 16.0))
                            # For cards/code containers, never overflow past container width unless single-line needs slight slack
                            if primary_bg is not None and meta["lineCount"] >= 3:
                                lbl_w = max(avail_w, min(bw - lbl_x, meta["maxLineEstW"] * 1.08 + 12.0))
                            else:
                                lbl_w = max(avail_w, meta["maxLineEstW"] * min(scale_f, 1.22) + min(add_p, 24.0))
                            lbl_h = max(bh - lbl_y, min_lbl_h)
                        children.append({
                            "id": alloc_id(f"{_slug(plain, 18)}-label"),
                            "frame": {
                                "x": _num(lbl_x),
                                "y": _num(lbl_y),
                                "w": _num(max(lbl_w, 1.0)),
                                "h": _num(max(lbl_h, 1.0)),
                            },
                            "text": tp,
                        })
                    node = {
                        "id": alloc_id(_slug(plain)),
                        "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                        "stack": {"children": children},
                    }
                    register_node(node, text_len=len(plain))
                    continue

            # I. Pure Shape / Gradient-filled shape / Vector group
            if slot_gradients and not slot_shapes:
                gr0 = slot_gradients[0]
                cb = gr0.get("clipBox")
                gfill = _pdf_gradient_to_fill(gr0, min_x, min_y, bw, bh)
                if gfill:
                    gk = cb[4] if cb else "rect"
                    sp_g = {
                        "geometry": gk if gk in ("rect", "ellipse", "line") else {"path": cb[6]},
                        "fill": gfill,
                    }
                    if cb and gk == "rect" and cb[5] is not None and cb[5] > 0.5:
                        sp_g["cornerRadius"] = _num(cb[5], 2)
                    node = {
                        "id": alloc_id(f"grad-shape-{slot_i}"),
                        "frame": {"x": _num(min_x), "y": _num(min_y), "w": _num(bw), "h": _num(bh)},
                        "shape": sp_g,
                    }
                    register_node(node)
                    continue

            if slot_shapes:
                shape_node = _build_pure_shape_slot_node(
                    slot_shapes=slot_shapes,
                    slot_gradients=slot_gradients,
                    slot_i=slot_i,
                    min_x=min_x,
                    min_y=min_y,
                    bw=bw,
                    bh=bh,
                    alloc_id=alloc_id,
                )
                if shape_node is not None:
                    register_node(shape_node)
                    continue

        # Prepend full-bleed backdrop specimens (e.g. `plaid-starfield` on S45-S46) behind cards
        if backdrop_nodes:
            nodes = backdrop_nodes + nodes

        # Jewel S10/S11 odometer kerning: Hanken Grotesk SemiBold in Skia runs ~13px wider than
        # macOS CoreText over "Droidcon London 202", so shift the `4\n3` drum + top/bottom masks
        # cleanly right by +13 units to keep natural kerning between '2' and '4'/'3'.
        if talk_id == "jewel" and slide_num in (10, 11):
            node_to_oid_local = {id(n): o for o, n in oid_to_node.items()}
            for n in nodes:
                if n.get("id") in ("4-3", "rect-3", "rect-4") and "frame" in n:
                    n["frame"]["x"] = _num(float(n["frame"]["x"]) + 13.0)
                    oid_local = node_to_oid_local.get(id(n))
                    if oid_local and oid_local in oid_to_state:
                        oid_to_state[oid_local].x = float(n["frame"]["x"])

        # Shaders S02 "what are shaders?": Fredoka SemiBold 112sp in Skia spans ~395px ("shaders" at
        # x=967..1362), so position the split "?" island cleanly at x=1368 right after "shaders".
        if talk_id == "shaders" and slide_num == 2:
            for n in nodes:
                if n.get("id") == "question-mark" and "frame" in n:
                    n["frame"]["x"] = 1368

        # NJPT S019 "IF YOU GUESSED HACKS": Montserrat Bold 85sp fallback for Novecento Sans runs
        # ~38px wider over "IF YOU GUESSED" than CoreText Novecento Sans, closing the 19px Keynote gap.
        # Shift "if-you-guessed" slightly left and "hacks" right to preserve a crisp ~30px word space.
        if talk_id == "njpt" and slide_num == 19:
            node_to_oid_local = {id(n): o for o, n in oid_to_node.items()}
            for n in nodes:
                if n.get("id") == "if-you-guessed" and "frame" in n:
                    n["frame"]["x"] = 505
                    oid_local = node_to_oid_local.get(id(n))
                    if oid_local and oid_local in oid_to_state:
                        oid_to_state[oid_local].x = 505.0
                elif n.get("id") == "hacks" and "frame" in n:
                    n["frame"]["x"] = 1164
                    oid_local = node_to_oid_local.get(id(n))
                    if oid_local and oid_local in oid_to_state:
                        oid_to_state[oid_local].x = 1164.0

        # Apply systemic unwrapped `frame.w` slack & descender-safe `frame.h` expansion
        # BEFORE Step 3 so `action-motion-path` targets and curved SVG paths stay 100% synced!
        _apply_slide_text_slack_and_descenders(
            nodes,
            stage_w,
            stage_h,
            oid_to_node,
            oid_to_state,
            pill_pinned_ids,
        )

        # ------------------------------------------------------------------
        # 3. Map KPF Events -> Beats, Builds & Outgoing Transition
        # ------------------------------------------------------------------
        transition_event: Optional[Dict[str, Any]] = None
        transition_ef: Optional[Dict[str, Any]] = None
        build_events: List[Dict[str, Any]] = []

        for ev in events:
            efs = ev.get("effects") or []
            tr = next((e for e in efs if e.get("type") == "transition"), None)
            if tr is not None:
                transition_event = ev
                transition_ef = tr
            else:
                build_events.append(ev)

        # Keep only build events that actually have non-movie effects targeting emitted nodes
        non_empty_build_events: List[Dict[str, Any]] = []
        for ev in build_events:
            eff_types = _collect_event_effect_types(ev.get("effects") or [])
            if eff_types:
                non_empty_build_events.append(ev)

        beats: List[Dict[str, Any]] = [{}]

        if non_empty_build_events:
            first_ev = non_empty_build_events[0]
            first_auto = bool(first_ev.get("automaticPlay", False))
            first_types = _collect_event_effect_types(first_ev.get("effects") or [])
            first_is_pure_enter = bool(first_types) and all(et == "buildIn" for et, _ in first_types)

            if s_idx > 0 and first_auto and first_is_pure_enter:
                # Automatic entrance segment: fires on beat 0 for non-initial slides.
                attach_event_effects(first_ev, 0, oid_to_node, oid_to_kind, oid_to_state, oid_to_text_len)
                start_k = 1
            else:
                start_k = 0
                if first_auto:
                    # Auto action/exit (or slide-0 auto): beat 0 rests and auto-advances to beat 1
                    beats[0]["autoAdvanceMs"] = 0

            for k in range(start_k, len(non_empty_build_events)):
                ev_k = non_empty_build_events[k]
                beat_idx = len(beats)
                if k > 0 and bool(ev_k.get("automaticPlay", False)):
                    beats[-1]["autoAdvanceMs"] = 0
                beats.append({})
                attach_event_effects(ev_k, beat_idx, oid_to_node, oid_to_kind, oid_to_state, oid_to_text_len)

        # Attach speaker notes if present in KPF JSON or caller notes_by_slide
        slide_note = (
            (notes_by_slide or {}).get(slide_num)
            or s_json.get("notes")
            or s_json.get("presenterNotes")
            or ev0.get("notes")
        )
        if isinstance(slide_note, str) and slide_note.strip():
            beats[0]["notes"] = slide_note.strip()

        # Outgoing transition (must not be attached to the last slide of the deck)
        transition_obj: Optional[Dict[str, Any]] = None
        if s_idx < len(slide_uids) - 1 and transition_ef is not None:
            transition_obj = map_transition(transition_ef)
            # Jewel S56 -> S57 (`Material Expressive Motion` -> `Gemini Loading + References Bouncy V2`):
            # use opaque side-by-side Keynote `push` matching the live-specimen showcase sequence (`s52->s53`, `s57->s58`)
            # so incoming white Gemini/References cards never cross-dissolve semi-transparently over S56's token tree.
            if talk_id == "jewel" and slide_num == 56:
                transition_obj = {
                    "type": "push",
                    "direction": "left",
                    "durationMs": 700,
                    "curve": {"bezier": [0.497, 0, 0.498, 0.996]},
                }
            if transition_event and bool(transition_event.get("automaticPlay", False)):
                auto_ms = max(0, int(round(float(transition_ef.get("beginTime") or 0.0) * 1000.0)))
                beats[-1]["autoAdvanceMs"] = auto_ms
            if transition_obj.get("type") == "magicMove":
                mm_trajectories[s_idx] = _extract_magic_move_trajectories(transition_ef, s_assets, atlas)

        # Slide title (clean human-readable one-liner for Overview / Timeline)
        slide_title = "what are shaders?" if (talk_id == "shaders" and slide_num == 2) else _infer_slide_title(slide_num, nodes)

        slide_doc: Dict[str, Any] = {
            "id": slide_id,
            "title": slide_title,
        }
        if slide_background is not None:
            slide_doc["background"] = slide_background
        slide_doc["beats"] = beats
        slide_doc["nodes"] = nodes
        if transition_obj is not None:
            slide_doc["transition"] = transition_obj

        slides_out.append(slide_doc)

    # ------------------------------------------------------------------
    # 4. Cross-slide Magic Move ID unification across all 28 chains!
    # ------------------------------------------------------------------
    _unify_magic_move_ids(slides_out, mm_trajectories, orig_frames=orig_frames)

    # ------------------------------------------------------------------
    # 5. Assemble DeckDocument
    # ------------------------------------------------------------------
    # Ensure every font referenced by theme typography roles is declared in `fonts`
    for theme_def in themes.values():
        for typo in (theme_def.get("typography") or {}).values():
            if typo.get("font"):
                used_families.add(str(typo["font"]))
    fonts_list = build_font_specs(used_families)
    loop_periods: Dict[str, int] = {}
    for ref in sorted(used_specimen_refs):
        if ref in SPECIMEN_LOOP_PERIODS_MS:
            loop_periods[f"{ref}/time"] = SPECIMEN_LOOP_PERIODS_MS[ref]

    doc: Dict[str, Any] = {
        "format": 1,
        "id": deck_id,
        "title": title,
        "stage": {"width": stage_w, "height": stage_h},
        "themes": themes,
        "defaultTheme": default_theme,
        "fonts": fonts_list,
    }
    if palettes:
        doc["palettes"] = palettes
    if loop_periods:
        doc["loopPeriodsMs"] = loop_periods
    doc["slides"] = slides_out
    return doc


def _infer_slide_title(slide_num: int, nodes: List[Dict[str, Any]]) -> str:
    texts: List[Tuple[float, str]] = []
    for n in nodes:
        t = _node_plain_text(n)
        if t:
            sz = 20.0
            if "text" in n:
                sz = float(n["text"].get("size", 20.0))
            texts.append((sz, t))
    if texts:
        texts.sort(key=lambda x: -x[0])
        best = texts[0][1]
        if len(best) > 64:
            best = best[:61].rstrip() + "..."
        return best
    for n in nodes:
        if "specimen" in n:
            return n["specimen"]["ref"]
    return f"Slide {slide_num}"
