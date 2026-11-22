"""Keynote KPF effect, action-build, timing curve, and transition mapper."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple


# 8-way Keynote direction codes (verified against raw KPF position deltas)
DIRECTION_8WAY: Dict[int, str] = {
    11: "right",
    12: "left",
    13: "down",
    14: "up",
    21: "downRight",
    22: "downLeft",
    23: "upRight",
    24: "upLeft",
}

IRIS_DIRECTION: Dict[int, str] = {
    41: "out",
    42: "in",
}

LINEDRAW_DIRECTION: Dict[int, str] = {
    31: "clockwise",
    32: "counterClockwise",
    51: "startToEnd",
    52: "endToStart",
    53: "fromCenter",
}

ACCELERATION_CURVES: Dict[int, str] = {
    1: "easeIn",
    2: "easeOut",
    3: "easeInOut",
}

MOVIE_EFFECT_NAMES = frozenset({"apple:movie-start", "renderMovie"})

# Canonical Apple cubic-bezier presets (used when KPF omits explicit control points on named transitions)
APPLE_TRANSITION_DEFAULT_CURVES: Dict[str, Any] = {
    "apple:push": {"bezier": [0.497, 0, 0.498, 0.996]},
    "apple:reveal": {"bezier": [0.375, 0, 0.475, 0.088]},
    "apple:slide": {"bezier": [0.553, 0.802, 0.602, 1]},
}


def _clean_num(v: float, decimals: int = 2) -> int | float:
    r = round(float(v), decimals)
    if abs(r) < 1e-9:
        return 0
    if float(int(round(r))) == r:
        return int(round(r))
    return r


def _ms(seconds: Optional[float]) -> int:
    if seconds is None:
        return 0
    v = float(seconds)
    if v < 1e-6 or v > 1e12:
        return 0
    return max(0, int(round(v * 1000.0)))


def extract_leaf_animations(base_layer: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Recursively collect all leaf animations (property != None) from a KPF effect baseLayer."""
    if not isinstance(base_layer, dict):
        return []
    out: List[Dict[str, Any]] = []

    def walk_anim(anim: Dict[str, Any], parent_begin: float) -> None:
        bt = anim.get("beginTime", 0.0)
        if not isinstance(bt, (int, float)) or bt < 1e-12:
            bt = 0.0
        eff_begin = parent_begin + float(bt)
        subs = anim.get("animations")
        if subs:
            for sub in subs:
                walk_anim(sub, eff_begin)
        elif anim.get("property"):
            rec = dict(anim)
            rec["_effBeginTime"] = eff_begin
            out.append(rec)

    def walk_layer(layer: Dict[str, Any]) -> None:
        for anim in layer.get("animations") or []:
            walk_anim(anim, 0.0)
        for child in layer.get("layers") or []:
            walk_layer(child)

    walk_layer(base_layer)
    return out


def extract_curve(anim: Dict[str, Any]) -> Optional[Any]:
    """Convert KPF timingFunction to a curve string or {'bezier': [x1,y1,x2,y2]}."""
    tf = anim.get("timingFunction")
    if not tf:
        return None
    if tf == "EaseInEaseOut":
        return "easeInOut"
    if tf == "EaseIn":
        return "easeIn"
    if tf == "EaseOut":
        return "easeOut"
    if tf == "Linear":
        return "linear"
    if tf == "custom":
        for keys in (
            ("timingControlPoint1x", "timingControlPoint1y", "timingControlPoint2x", "timingControlPoint2y"),
            ("timingControlPoint1X", "timingControlPoint1Y", "timingControlPoint2X", "timingControlPoint2Y"),
        ):
            if all(k in anim for k in keys):
                pts = [_clean_num(float(anim[k]), 3) for k in keys]
                return {"bezier": pts}
        # Some KPF exports store controlPoints array
        cp = anim.get("timingControlPoints")
        if isinstance(cp, list) and len(cp) == 4:
            return {"bezier": [_clean_num(float(v), 3) for v in cp]}
    return None


def _pick_primary_curve(
    anims: List[Dict[str, Any]],
    attrs: Dict[str, Any],
    preferred_props: Tuple[str, ...] = ("position", "transform.scale.xy", "transform.scale.x", "transform.rotation.z", "opacity"),
) -> Optional[Any]:
    for prop in preferred_props:
        for a in anims:
            if a.get("property") == prop:
                c = extract_curve(a)
                if c is not None:
                    return c
    for a in anims:
        c = extract_curve(a)
        if c is not None:
            return c
    acc = attrs.get("sfx-action-acceleration")
    if isinstance(acc, int) and acc in ACCELERATION_CURVES:
        return ACCELERATION_CURVES[acc]
    return None


def _extract_char_stagger_ms(base_layer: Optional[Dict[str, Any]]) -> Optional[int]:
    """Detect uniform per-character stagger interval from multi-sublayer KPF text animations."""
    if not isinstance(base_layer, dict):
        return None
    starts: List[float] = []

    def walk(layer: Dict[str, Any]) -> None:
        for anim in layer.get("animations") or []:
            subs = anim.get("animations") or [anim]
            for s in subs:
                prop = s.get("property")
                if prop in ("opacity", "position", "transform.scale.xy"):
                    bt = s.get("beginTime", 0.0)
                    if isinstance(bt, (int, float)):
                        starts.append(0.0 if bt < 1e-12 else float(bt))
                        break
        for ch in layer.get("layers") or []:
            walk(ch)

    walk(base_layer)
    uniq = sorted({round(t, 4) for t in starts})
    if len(uniq) >= 3:
        deltas = [round(uniq[i + 1] - uniq[i], 4) for i in range(len(uniq) - 1)]
        pos_deltas = [d for d in deltas if d > 0.005]
        if pos_deltas:
            median_d = sorted(pos_deltas)[len(pos_deltas) // 2]
            return max(10, int(round(median_d * 1000.0)))
    return None


def _extract_fade_thru_color(base_layer: Optional[Dict[str, Any]]) -> str:
    """Extract opaque #RRGGBB color from BLTFadeThruColor KPF baseLayer (defaults to #000000)."""
    if not isinstance(base_layer, dict):
        return "#000000"

    def walk(layer: Dict[str, Any]) -> Optional[str]:
        bg = (layer.get("initialState") or {}).get("backgroundColor")
        if isinstance(bg, list) and len(bg) >= 3:
            r = max(0, min(255, round(float(bg[0]) * 255)))
            g = max(0, min(255, round(float(bg[1]) * 255)))
            b = max(0, min(255, round(float(bg[2]) * 255)))
            return f"#{r:02X}{g:02X}{b:02X}"
        for ch in layer.get("layers") or []:
            found = walk(ch)
            if found:
                return found
        return None

    return walk(base_layer) or "#000000"


def map_transition(ef: Dict[str, Any]) -> Dict[str, Any]:
    """Map any of the 9 Keynote KPF transition effects to a Transition descriptor."""
    name = ef.get("name") or "none"
    attrs = ef.get("attributes") or {}
    direction_code = attrs.get("direction", 0)
    dur_ms = _ms(ef.get("duration"))
    anims = extract_leaf_animations(ef.get("baseLayer"))
    curve = _pick_primary_curve(anims, attrs)

    if name == "none":
        return {"type": "none"}

    tr: Dict[str, Any] = {}
    if name == "apple:magic-move-implied-motion-path":
        tr["type"] = "magicMove"
        tr["durationMs"] = dur_ms if dur_ms > 0 else 500
        tr["curve"] = curve or "easeInOut"
        return tr

    if name == "apple:dissolve":
        tr["type"] = "dissolve"
        tr["durationMs"] = dur_ms if dur_ms > 0 else 500
        if curve:
            tr["curve"] = curve
        return tr

    if name == "apple:push":
        tr["type"] = "push"
        tr["direction"] = DIRECTION_8WAY.get(direction_code, "left")
        tr["durationMs"] = dur_ms if dur_ms > 0 else 500
        tr["curve"] = curve or APPLE_TRANSITION_DEFAULT_CURVES["apple:push"]
        return tr

    if name == "apple:slide":
        tr["type"] = "slide"
        tr["direction"] = DIRECTION_8WAY.get(direction_code, "right")
        tr["durationMs"] = dur_ms if dur_ms > 0 else 500
        tr["curve"] = curve or APPLE_TRANSITION_DEFAULT_CURVES["apple:slide"]
        return tr

    if name == "apple:reveal":
        tr["type"] = "reveal"
        tr["direction"] = DIRECTION_8WAY.get(direction_code, "left")
        tr["durationMs"] = dur_ms if dur_ms > 0 else 500
        tr["curve"] = curve or APPLE_TRANSITION_DEFAULT_CURVES["apple:reveal"]
        return tr

    if name == "apple:wipe":
        tr["type"] = "wipe"
        tr["direction"] = DIRECTION_8WAY.get(direction_code, "right")
        tr["durationMs"] = dur_ms if dur_ms > 0 else 500
        if curve:
            tr["curve"] = curve
        return tr

    if name == "apple:wipe-iris":
        tr["type"] = "iris"
        tr["direction"] = IRIS_DIRECTION.get(direction_code, "out")
        tr["center"] = {"x": 0.5, "y": 0.5}
        tr["durationMs"] = dur_ms if dur_ms > 0 else 1000
        if curve:
            tr["curve"] = curve
        return tr

    if name == "com.apple.iWork.Keynote.BLTFadeThruColor":
        tr["type"] = "fadeThroughColor"
        tr["color"] = _extract_fade_thru_color(ef.get("baseLayer"))
        tr["durationMs"] = dur_ms if dur_ms > 0 else 300
        if curve:
            tr["curve"] = curve
        return tr

    # Unknown future Keynote transition falls back safely to dissolve
    tr["type"] = "dissolve"
    if dur_ms > 0:
        tr["durationMs"] = dur_ms
    if curve:
        tr["curve"] = curve
    return tr


def _fmt_svg_num(v: float) -> str:
    c = _clean_num(v, 2)
    return str(c)


def _rdp_simplify(pts: List[Tuple[float, float]], eps: float) -> List[Tuple[float, float]]:
    """Ramer-Douglas-Peucker polyline simplification keeping exact start & end points."""
    if len(pts) <= 2:
        return pts
    x0, y0 = pts[0]
    x1, y1 = pts[-1]
    dx, dy = x1 - x0, y1 - y0
    seg_len = math.hypot(dx, dy)
    max_dist = -1.0
    max_idx = 0
    for i in range(1, len(pts) - 1):
        px, py = pts[i]
        if seg_len < 1e-9:
            dist = math.hypot(px - x0, py - y0)
        else:
            dist = abs(dy * px - dx * py + x1 * y0 - y1 * x0) / seg_len
        if dist > max_dist:
            max_dist = dist
            max_idx = i
    if max_dist > eps:
        left = _rdp_simplify(pts[: max_idx + 1], eps)
        right = _rdp_simplify(pts[max_idx:], eps)
        return left[:-1] + right
    return [pts[0], pts[-1]]


def _build_relative_motion_path_svg(
    values: List[Dict[str, Any]],
    target_dx: float,
    target_dy: float,
) -> Optional[str]:
    """
    Build SVG path data relative to (0, 0) ending at exact (target_dx, target_dy).
    Returns None if the path is essentially a straight line (max deviation <= 2.0 units).
    """
    raw_pts: List[Tuple[float, float]] = []
    for v in values:
        if isinstance(v, dict) and "pointX" in v and "pointY" in v:
            raw_pts.append((float(v["pointX"]), float(v["pointY"])))
    if len(raw_pts) < 3:
        return None
    p0x, p0y = raw_pts[0]
    rel_pts = [(x - p0x, y - p0y) for x, y in raw_pts]
    # Check straight-line deviation
    end_x, end_y = rel_pts[-1]
    seg_len = math.hypot(end_x, end_y)
    max_dev = 0.0
    for px, py in rel_pts[1:-1]:
        if seg_len < 1e-6:
            d = math.hypot(px, py)
        else:
            d = abs(end_y * px - end_x * py) / seg_len
        if d > max_dev:
            max_dev = d
    if max_dev <= 2.0:
        return None

    # Snap exact endpoints to (0, 0) and (target_dx, target_dy) to satisfy motion-path endpoint validation
    rel_pts[0] = (0.0, 0.0)
    rel_pts[-1] = (float(target_dx), float(target_dy))
    simplified = _rdp_simplify(rel_pts, eps=1.5)
    if len(simplified) > 24:
        simplified = _rdp_simplify(rel_pts, eps=3.0)
    simplified[0] = (0.0, 0.0)
    simplified[-1] = (float(target_dx), float(target_dy))

    parts = [f"M {_fmt_svg_num(simplified[0][0])} {_fmt_svg_num(simplified[0][1])}"]
    for px, py in simplified[1:]:
        parts.append(f"L {_fmt_svg_num(px)} {_fmt_svg_num(py)}")
    return " ".join(parts)


@dataclass
class NodeAnimState:
    """Tracks cumulative transformed state of a node across beats for chained actionBuilds."""

    x: float
    y: float
    w: float
    h: float
    opacity: float = 1.0
    scale_x: float = 1.0
    scale_y: float = 1.0
    rotation_deg: float = 0.0


def map_single_effect(
    ef: Dict[str, Any],
    node_kind: str,
    node_state: NodeAnimState,
    beat: int,
    text_char_count: int = 0,
    delay_ms: int = 0,
) -> Optional[Dict[str, Any]]:
    """
    Map one Keynote KPF buildIn / buildOut / actionBuild to a Build dict.
    Returns None for movie-start / renderMovie wrappers (which live specimens replace).
    """
    etype = ef.get("type") or ""
    name = ef.get("name") or ""
    if name in MOVIE_EFFECT_NAMES:
        return None

    attrs = ef.get("attributes") or {}
    direction_code = attrs.get("direction", 0)
    dur_ms = _ms(ef.get("duration"))
    anims = extract_leaf_animations(ef.get("baseLayer"))
    curve = _pick_primary_curve(anims, attrs)

    # ------------------------------------------------------------------
    # Action builds (5 types -> 4 change + 1 emphasis)
    # ------------------------------------------------------------------
    if etype == "actionBuild":
        if name == "apple:action-jiggle":
            amp_deg = 5.0
            cycles = 5
            for a in anims:
                if a.get("property") == "transform.rotation.z":
                    fr = (a.get("from") or {}).get("scalar")
                    if isinstance(fr, (int, float)) and abs(fr) > 1e-6:
                        amp_deg = abs(math.degrees(float(fr)))
                    half_dur = float(a.get("duration") or 0.0)
                    total_s = float(ef.get("duration") or 0.8)
                    if half_dur > 0.005:
                        cycles = max(1, int(round(total_s / (2.0 * half_dur))))
                    break
            build: Dict[str, Any] = {
                "kind": "emphasis",
                "beat": beat,
                "effect": "jiggle",
                "args": {
                    "amplitude": _clean_num(amp_deg, 2),
                    "cycles": cycles,
                },
            }
            if delay_ms > 0:
                build["delayMs"] = delay_ms
            if dur_ms > 0:
                build["durationMs"] = dur_ms
            if curve:
                build["curve"] = curve
            return build

        if name == "apple:action-motion-path":
            dx = 0.0
            dy = 0.0
            path_svg: Optional[str] = None
            for a in anims:
                if a.get("property") == "position":
                    vals = a.get("values") or []
                    if vals and isinstance(vals[0], dict) and isinstance(vals[-1], dict):
                        dx = float(vals[-1].get("pointX", 0.0)) - float(vals[0].get("pointX", 0.0))
                        dy = float(vals[-1].get("pointY", 0.0)) - float(vals[0].get("pointY", 0.0))
                        new_x = _clean_num(node_state.x + dx, 2)
                        new_y = _clean_num(node_state.y + dy, 2)
                        exact_dx = float(new_x) - float(_clean_num(node_state.x, 2))
                        exact_dy = float(new_y) - float(_clean_num(node_state.y, 2))
                        path_svg = _build_relative_motion_path_svg(vals, exact_dx, exact_dy)
                        node_state.x = float(new_x)
                        node_state.y = float(new_y)
                    else:
                        fr = a.get("from") or {}
                        to = a.get("to") or {}
                        if "pointX" in fr and "pointX" in to:
                            dx = float(to["pointX"]) - float(fr["pointX"])
                            dy = float(to["pointY"]) - float(fr["pointY"])
                            node_state.x = float(_clean_num(node_state.x + dx, 2))
                            node_state.y = float(_clean_num(node_state.y + dy, 2))
                    break
            build = {
                "kind": "change",
                "beat": beat,
                "set": {
                    "frame": {
                        "x": _clean_num(node_state.x, 2),
                        "y": _clean_num(node_state.y, 2),
                    }
                },
            }
            if path_svg:
                build["path"] = path_svg
            if delay_ms > 0:
                build["delayMs"] = delay_ms
            if dur_ms > 0:
                build["durationMs"] = dur_ms
            if curve:
                build["curve"] = curve
            return build

        if name == "apple:action-opacity":
            target_op = node_state.opacity
            for a in anims:
                if a.get("property") == "opacity":
                    to = a.get("to") or {}
                    if "scalar" in to:
                        target_op = max(0.0, min(1.0, float(to["scalar"])))
                        break
                    vals = a.get("values") or []
                    if vals and isinstance(vals[-1], dict) and "scalar" in vals[-1]:
                        target_op = max(0.0, min(1.0, float(vals[-1]["scalar"])))
                        break
            node_state.opacity = target_op
            build = {
                "kind": "change",
                "beat": beat,
                "set": {"opacity": _clean_num(target_op, 3)},
            }
            if delay_ms > 0:
                build["delayMs"] = delay_ms
            if dur_ms > 0:
                build["durationMs"] = dur_ms
            if curve:
                build["curve"] = curve
            return build

        if name == "apple:action-scale":
            sx_mult = 1.0
            sy_mult = 1.0
            for a in anims:
                prop = a.get("property")
                to = a.get("to") or {}
                fr = a.get("from") or {}
                if prop == "transform.scale.xy" and "scalar" in to:
                    f0 = float(fr.get("scalar", 1.0)) or 1.0
                    ratio = float(to["scalar"]) / f0
                    sx_mult = sy_mult = ratio
                elif prop == "transform.scale.x" and "scalar" in to:
                    f0 = float(fr.get("scalar", 1.0)) or 1.0
                    sx_mult = float(to["scalar"]) / f0
                elif prop == "transform.scale.y" and "scalar" in to:
                    f0 = float(fr.get("scalar", 1.0)) or 1.0
                    sy_mult = float(to["scalar"]) / f0
            node_state.scale_x *= sx_mult
            node_state.scale_y *= sy_mult
            if abs(node_state.scale_x - node_state.scale_y) < 1e-3:
                scale_val: Any = _clean_num(node_state.scale_x, 3)
            else:
                scale_val = {
                    "x": _clean_num(node_state.scale_x, 3),
                    "y": _clean_num(node_state.scale_y, 3),
                }
            build = {
                "kind": "change",
                "beat": beat,
                "set": {"scale": scale_val},
            }
            if delay_ms > 0:
                build["delayMs"] = delay_ms
            if dur_ms > 0:
                build["durationMs"] = dur_ms
            if curve:
                build["curve"] = curve
            return build

        if name == "apple:action-rotation":
            delta_deg = 0.0
            for a in anims:
                if a.get("property") == "transform.rotation.z":
                    fr = float((a.get("from") or {}).get("scalar", 0.0))
                    to = float((a.get("to") or {}).get("scalar", 0.0))
                    delta_deg = math.degrees(to - fr)
                    break
            node_state.rotation_deg += delta_deg
            build = {
                "kind": "change",
                "beat": beat,
                "set": {"rotation": _clean_num(node_state.rotation_deg, 2)},
            }
            if delay_ms > 0:
                build["delayMs"] = delay_ms
            if dur_ms > 0:
                build["durationMs"] = dur_ms
            if curve:
                build["curve"] = curve
            return build

    # ------------------------------------------------------------------
    # Build-in / Build-out (38 pairs -> 15 typed effects)
    # ------------------------------------------------------------------
    kind = "exit" if etype == "buildOut" else "enter"
    is_char = "character" in name
    build = {"kind": kind, "beat": beat}
    args: Dict[str, Any] = {}

    if name in ("apple:appear", "apple:bc-appear"):
        build["effect"] = "appear"
        dur_ms = 0
        curve = None
    elif name in ("apple:dissolve", "apple:dissolve character"):
        build["effect"] = "dissolve"
        if is_char and node_kind == "text":
            stagger_ms = _extract_char_stagger_ms(ef.get("baseLayer"))
            if stagger_ms is not None:
                build["by"] = "character"
                build["stagger"] = {"ms": stagger_ms}
    elif name in ("apple:fade and move", "apple:fade and move character"):
        build["effect"] = "fadeMove"
        d_str = DIRECTION_8WAY.get(direction_code)
        if d_str:
            build["direction"] = d_str
        # Extract travel distance if position animation specifies one
        for a in anims:
            if a.get("property") == "position":
                fr = a.get("from") or {}
                to = a.get("to") or {}
                if "pointX" in fr and "pointX" in to:
                    dist = math.hypot(float(to["pointX"]) - float(fr["pointX"]), float(to["pointY"]) - float(fr["pointY"]))
                    if dist >= 1.0:
                        args["distance"] = _clean_num(dist, 1)
                    break
        if is_char and node_kind == "text":
            stagger_ms = _extract_char_stagger_ms(ef.get("baseLayer"))
            if stagger_ms is not None:
                build["by"] = "character"
                build["stagger"] = {"ms": stagger_ms}
    elif name in ("apple:move in", "apple:move in character"):
        build["effect"] = "moveIn"
        d_str = DIRECTION_8WAY.get(direction_code)
        if d_str:
            build["direction"] = d_str
        # Detect bounce (if keyframes overshoot target)
        if any(a.get("property") == "position" and len(a.get("values") or []) > 2 for a in anims):
            args["bounce"] = True
        if is_char and node_kind == "text":
            stagger_ms = _extract_char_stagger_ms(ef.get("baseLayer"))
            if stagger_ms is not None:
                build["by"] = "character"
                build["stagger"] = {"ms": stagger_ms}
    elif name == "apple:wipe":
        build["effect"] = "wipe"
        d_str = DIRECTION_8WAY.get(direction_code)
        if d_str:
            build["direction"] = d_str
    elif name == "apple:wipe-iris":
        build["effect"] = "iris"
        build["direction"] = IRIS_DIRECTION.get(direction_code, "out")
    elif name in ("apple:zoom", "apple:zoom character", "apple:bc-zoom-big character"):
        build["effect"] = "zoom"
        for a in anims:
            if a.get("property") in ("transform.scale.xy", "transform.scale.x"):
                fr = (a.get("from") or {}).get("scalar")
                to = (a.get("to") or {}).get("scalar")
                if kind == "exit":
                    if isinstance(to, (int, float)) and abs(float(to) - 1.0) > 1e-3:
                        args["toScale"] = _clean_num(max(0.01, min(20.0, float(to))), 3)
                    if isinstance(fr, (int, float)) and abs(float(fr) - 1.0) > 1e-3:
                        args["fromScale"] = _clean_num(max(0.01, min(20.0, float(fr))), 3)
                else:
                    if isinstance(fr, (int, float)) and abs(float(fr) - 1.0) > 1e-3:
                        args["fromScale"] = _clean_num(max(0.01, min(20.0, float(fr))), 3)
                    if isinstance(to, (int, float)) and abs(float(to) - 1.0) > 1e-3:
                        args["toScale"] = _clean_num(max(0.01, min(20.0, float(to))), 3)
                # Extract relative scaleDelay fraction if scale starts after opacity
                bt = float(a.get("beginTime") or 0.0)
                ef_dur = float(ef.get("duration") or 0.0)
                if bt > 1e-4 and ef_dur > 1e-4:
                    args["scaleDelay"] = _clean_num(min(0.85, max(0.0, bt / ef_dur)), 3)
                break
        if name == "apple:bc-zoom-big character":
            if kind == "exit" and "toScale" not in args:
                args["toScale"] = 10
            elif kind != "exit" and "fromScale" not in args:
                args["fromScale"] = 10
        if is_char and node_kind == "text":
            stagger_ms = _extract_char_stagger_ms(ef.get("baseLayer"))
            if stagger_ms is not None:
                build["by"] = "character"
                build["stagger"] = {"ms": stagger_ms}
    elif name in ("apple:pop", "apple:bc-pop"):
        build["effect"] = "pop"
    elif name == "com.apple.iWork.Keynote.Blur":
        build["effect"] = "blur"
    elif name == "com.apple.iWork.Keynote.FromDarkness":
        # fromDarkness takes no extra args
        build["effect"] = "fromDarkness"
    elif name == "apple:keyboard":
        if node_kind == "text":
            build["effect"] = "keyboard"
            # Derive deterministic seed from objectID
            oid = str(ef.get("objectID") or "0")
            seed = sum((i + 1) * ord(ch) for i, ch in enumerate(oid)) % 10000
            args["seed"] = seed
            args["caret"] = True
            if text_char_count > 0 and dur_ms > 0:
                cps = max(4.0, min(60.0, text_char_count / (dur_ms / 1000.0)))
                args["charsPerSec"] = _clean_num(cps, 1)
        else:
            build["effect"] = "dissolve"
    elif name in ("com.apple.iWork.Keynote.LineDraw", "com.apple.iWork.Keynote.LineDrawForLine"):
        if node_kind in ("shape", "connector"):
            build["effect"] = "lineDraw"
            default_dir = "startToEnd" if name.endswith("LineDrawForLine") else "clockwise"
            build["direction"] = LINEDRAW_DIRECTION.get(direction_code, default_dir)
            if "KNBuildCustomAttributesIncludeEndpoints" in attrs:
                args["includeEndpoints"] = bool(attrs["KNBuildCustomAttributesIncludeEndpoints"])
        else:
            # Safety fallback: lineDraw is only valid on shape/connector nodes
            build["effect"] = "wipe" if direction_code in DIRECTION_8WAY else "dissolve"
    elif name == "com.apple.iWork.Keynote.Trace":
        build["effect"] = "trace"
        d_str = DIRECTION_8WAY.get(direction_code)
        if d_str:
            build["direction"] = d_str
    elif name == "com.apple.iWork.Keynote.KLNSparkle":
        build["effect"] = "sparkle"
        d_str = DIRECTION_8WAY.get(direction_code)
        if d_str:
            build["direction"] = d_str
    elif name == "com.apple.iWork.Keynote.KLNShimmer":
        build["effect"] = "shimmer"
    else:
        build["effect"] = "dissolve"

    if args:
        build["args"] = args
    if delay_ms > 0:
        build["delayMs"] = delay_ms
    if dur_ms > 0:
        build["durationMs"] = dur_ms
    if curve:
        build["curve"] = curve
    return build


def _deep_merge_patch(dst: Dict[str, Any], src: Dict[str, Any]) -> None:
    for k, v in src.items():
        if isinstance(v, dict) and isinstance(dst.get(k), dict):
            _deep_merge_patch(dst[k], v)
        else:
            dst[k] = v


def attach_event_effects(
    event: Dict[str, Any],
    beat: int,
    oid_to_node: Dict[str, Dict[str, Any]],
    oid_to_kind: Dict[str, str],
    oid_to_state: Dict[str, NodeAnimState],
    oid_to_text_len: Dict[str, int],
) -> None:
    """
    Walk one KPF event's nested `effects` tree and append Build records
    to the corresponding nodes in `oid_to_node`, faithfully preserving:
      - sibling "With Build" parallelism (`delayMs`),
      - nested "After Build" sequencing (`after` across nodes, list order on same node),
      - simultaneous same-node action merging (e.g. NJPT S057 rotation + motion-path),
      - timing propagation when `movie-start` / `renderMovie` parents are dropped.
    """
    # Track per-node build records added during THIS beat so we can merge simultaneous
    # same-node change builds and assign `id`s cleanly when another node references a build.
    beat_builds_by_nid: Dict[str, List[Dict[str, Any]]] = {}
    nid_to_node: Dict[str, Dict[str, Any]] = {n["id"]: n for n in oid_to_node.values()}
    after_links: List[Tuple[Dict[str, Any], str, Dict[str, Any]]] = []

    def ensure_build_id(nid: str, b: Dict[str, Any]) -> str:
        if "id" not in b:
            used = {x.get("id") for x in nid_to_node[nid].get("builds", []) if "id" in x}
            idx = 1
            while f"b{beat}-{idx}" in used:
                idx += 1
            b["id"] = f"b{beat}-{idx}"
        return f"{nid}#{b['id']}"

    def walk_siblings(
        ef_list: List[Dict[str, Any]],
        parent_nid: Optional[str],
        parent_build: Optional[Dict[str, Any]],
        inherited_delay_ms: int,
    ) -> None:
        # Group simultaneous same-node actionBuilds at the same tree level (e.g. NJPT S057)
        processed_indices: set[int] = set()
        for idx, ef in enumerate(ef_list):
            if idx in processed_indices:
                continue
            oid = str(ef.get("objectID") or "")
            name = ef.get("name") or ""
            ef_delay_ms = inherited_delay_ms + _ms(ef.get("beginTime"))
            ef_dur_ms = _ms(ef.get("duration"))

            if name in MOVIE_EFFECT_NAMES or oid not in oid_to_node:
                # Propagate timing to children even if this wrapper effect is dropped
                sub_effects = ef.get("effects") or []
                if sub_effects:
                    walk_siblings(sub_effects, parent_nid, parent_build, ef_delay_ms + ef_dur_ms)
                continue

            node = oid_to_node[oid]
            nid = node["id"]
            nkind = oid_to_kind[oid]
            nstate = oid_to_state[oid]
            tlen = oid_to_text_len.get(oid, 0)

            build = map_single_effect(ef, nkind, nstate, beat, tlen, delay_ms=ef_delay_ms)
            if build is None:
                sub_effects = ef.get("effects") or []
                if sub_effects:
                    walk_siblings(sub_effects, parent_nid, parent_build, ef_delay_ms + ef_dur_ms)
                continue

            sub_effects_to_walk = list(ef.get("effects") or [])

            # Merge simultaneous sibling actionBuild changes on the SAME objectID
            if ef.get("type") == "actionBuild" and build.get("kind") == "change":
                for j in range(idx + 1, len(ef_list)):
                    if j in processed_indices:
                        continue
                    sib = ef_list[j]
                    if (
                        str(sib.get("objectID") or "") == oid
                        and sib.get("type") == "actionBuild"
                        and abs(_ms(sib.get("beginTime")) - _ms(ef.get("beginTime"))) <= 10
                    ):
                        sib_build = map_single_effect(sib, nkind, nstate, beat, tlen, delay_ms=ef_delay_ms)
                        if sib_build and sib_build.get("kind") == "change":
                            _deep_merge_patch(build["set"], sib_build["set"])
                            if "path" in sib_build and "path" not in build:
                                build["path"] = sib_build["path"]
                            if sib_build.get("durationMs", 0) > build.get("durationMs", 0):
                                build["durationMs"] = sib_build["durationMs"]
                            sub_effects_to_walk.extend(sib.get("effects") or [])
                            processed_indices.add(j)

            # Record `after` dependency when following a DIFFERENT node's build on this beat
            if parent_nid is not None and parent_build is not None and parent_nid != nid:
                after_links.append((build, parent_nid, parent_build))

            node.setdefault("builds", []).append(build)
            beat_builds_by_nid.setdefault(nid, []).append(build)

            if sub_effects_to_walk:
                walk_siblings(sub_effects_to_walk, nid, build, 0)

    walk_siblings(event.get("effects") or [], None, None, 0)

    # Resolve all `after` pointers now that every build on this beat has been emitted.
    # Use concise `parent_nid` when `parent_build` is the last build of `parent_nid` on
    # this beat; otherwise assign an explicit build id and emit `parent_nid#buildId`.
    for dep_build, p_nid, p_build in after_links:
        p_list = beat_builds_by_nid.get(p_nid, [])
        if p_list and p_build is p_list[-1]:
            dep_build["after"] = p_nid
        else:
            dep_build["after"] = ensure_build_id(p_nid, p_build)
