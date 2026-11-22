"""Canonical JSON printer and strict DeckDocument schema validator."""

from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

# Typed descriptor key orders matching the DeckDocument schema
TYPED_ORDERS: Dict[str, List[str]] = {
    "DeckDocument": [
        "format", "id", "title", "stage", "themes", "defaultTheme",
        "fonts", "palettes", "loopPeriodsMs", "slides",
    ],
    "StageSize": ["width", "height"],
    "DeckThemeSpec": ["preset", "colors", "typography", "motion"],
    "ThemeMotionDefaults": ["durationMs", "curve", "transition"],
    "TypographyStyle": ["font", "size", "weight", "italic", "color", "lineHeight", "tracking", "align"],
    "SlidePaletteSpec": ["canvas", "ink", "mutedInk", "accent", "surface", "border"],
    "FontSpec": ["family", "src", "weight", "italic", "variable", "system", "fallback", "role"],
    "Slide": ["id", "title", "skipped", "palette", "background", "beats", "nodes", "transition"],
    "Beat": ["notes", "phases", "stagger", "autoAdvanceMs"],
    "FillWrapper": ["gradient", "image"],
    "Gradient": ["type", "angle", "center", "radius", "stops"],
    "GradientStop": ["at", "color"],
    "Point": ["x", "y"],
    "Frame": ["x", "y", "w", "h"],
    "Transition": [
        "type", "direction", "color", "center",
        "delayMs", "durationMs", "curve", "phases", "stagger", "text",
    ],
    "Phases": ["exit", "move", "enter"],
    "Timing": ["delayMs", "durationMs", "curve"],
    "Stagger": ["ms", "order", "reverse", "manual"],
    "CurveWrapper": ["bezier", "spring"],
    "Spring": ["damping", "stiffness"],
    "Node": [
        "id", "frame", "align", "weight", "offset", "opacity", "rotation",
        "scale", "origin", "interactive",
        "text", "code", "shape", "image", "connector", "specimen",
        "stack", "row", "column", "loupe",
        "builds",
    ],
    "TextPayload": [
        "value", "style", "font", "size", "weight", "italic", "color",
        "lineHeight", "tracking", "align", "surface", "morph",
    ],
    "TextRun": [
        "text", "font", "size", "weight", "italic", "color",
        "tracking", "underline", "strike", "baselineShift", "background",
    ],
    "TextMorph": ["by", "pruneStopwords", "phases", "stagger"],
    "CodePayload": ["value", "language", "highlightLines", "fit", "surface"],
    "ShapePayload": ["geometry", "fill", "stroke", "cornerRadius", "heads"],
    "PathGeometry": ["path"],
    "Stroke": ["color", "width", "dash", "cap", "join"],
    "Heads": ["start", "end"],
    "ImagePayload": ["src", "fit", "tint"],
    "ConnectorPayload": ["from", "to", "fromAnchor", "toAnchor", "style", "stroke", "heads", "gap"],
    "SpecimenPayload": ["ref", "params", "tracks"],
    "SpecimenTrackOverride": ["beat", "delayMs", "durationMs", "curve"],
    "ContainerPayload": ["children", "gap", "padding", "alignItems", "justify", "surface", "clip"],
    "Padding": ["bottom", "left", "right", "top"],
    "LoupePayload": ["children", "target", "zoom", "diameter", "align", "lensFill"],
    "Surface": ["fill", "stroke", "cornerRadius", "padding"],
    "Build": [
        "kind", "beat", "id", "effect", "direction", "args", "by", "stagger",
        "set", "path", "morph", "after", "delayMs", "durationMs", "curve",
    ],
}

PAYLOAD_KEYS = frozenset({
    "text", "code", "shape", "image", "connector", "specimen",
    "stack", "row", "column", "loupe",
})

VALID_EFFECTS = frozenset({
    "appear", "dissolve", "fadeMove", "moveIn", "wipe", "iris", "zoom",
    "pop", "blur", "fromDarkness", "keyboard", "lineDraw",
    "trace", "sparkle", "shimmer", "jiggle",
})

VALID_TRANSITIONS = frozenset({
    "none", "dissolve", "magicMove", "push", "slide", "reveal",
    "wipe", "iris", "fadeThroughColor",
})

EFFECT_ALLOWED_ARGS: Dict[str, Set[str]] = {
    "appear": set(),
    "dissolve": set(),
    "fadeMove": {"distance"},
    "moveIn": {"bounce"},
    "wipe": set(),
    "iris": {"center"},
    "zoom": {"fromScale", "toScale", "scaleDelay"},
    "pop": set(),
    "blur": {"radius"},
    "fromDarkness": set(),
    "keyboard": {"seed", "caret", "charsPerSec"},
    "lineDraw": {"includeEndpoints"},
    "trace": set(),
    "sparkle": set(),
    "shimmer": set(),
    "jiggle": {"amplitude", "cycles"},
}

CHILD_SCHEMA: Dict[Tuple[str, str], str] = {
    ("DeckDocument", "stage"): "StageSize",
    ("DeckDocument", "themes"): "Map:DeckThemeSpec",
    ("DeckDocument", "fonts"): "List:FontSpec",
    ("DeckDocument", "palettes"): "Map:SlidePaletteSpec",
    ("DeckDocument", "loopPeriodsMs"): "Map",
    ("DeckDocument", "slides"): "List:Slide",
    ("DeckThemeSpec", "colors"): "Map",
    ("DeckThemeSpec", "typography"): "Map:TypographyStyle",
    ("DeckThemeSpec", "motion"): "ThemeMotionDefaults",
    ("ThemeMotionDefaults", "curve"): "CurveWrapper",
    ("ThemeMotionDefaults", "transition"): "Transition",
    ("Slide", "background"): "Fill",
    ("Slide", "beats"): "List:Beat",
    ("Slide", "nodes"): "List:Node",
    ("Slide", "transition"): "Transition",
    ("Beat", "stagger"): "Stagger",
    ("Beat", "phases"): "Phases",
    ("FillWrapper", "gradient"): "Gradient",
    ("Gradient", "center"): "Point",
    ("Gradient", "stops"): "List:GradientStop",
    ("Transition", "center"): "Point",
    ("Transition", "curve"): "CurveWrapper",
    ("Transition", "phases"): "Phases",
    ("Transition", "stagger"): "Stagger",
    ("Transition", "text"): "Map:TextMorph",
    ("Phases", "exit"): "Timing",
    ("Phases", "move"): "Timing",
    ("Phases", "enter"): "Timing",
    ("Timing", "curve"): "CurveWrapper",
    ("Stagger", "manual"): "Map",
    ("CurveWrapper", "spring"): "Spring",
    ("Node", "frame"): "Frame",
    ("Node", "offset"): "Point",
    ("Node", "scale"): "Point",
    ("Node", "origin"): "Point",
    ("Node", "text"): "TextPayload",
    ("Node", "code"): "CodePayload",
    ("Node", "shape"): "ShapePayload",
    ("Node", "image"): "ImagePayload",
    ("Node", "connector"): "ConnectorPayload",
    ("Node", "specimen"): "SpecimenPayload",
    ("Node", "stack"): "ContainerPayload",
    ("Node", "row"): "ContainerPayload",
    ("Node", "column"): "ContainerPayload",
    ("Node", "loupe"): "LoupePayload",
    ("Node", "builds"): "List:Build",
    ("TextPayload", "value"): "List:TextRun",
    ("TextPayload", "surface"): "Surface",
    ("TextPayload", "morph"): "TextMorph",
    ("TextMorph", "phases"): "Phases",
    ("TextMorph", "stagger"): "Stagger",
    ("CodePayload", "surface"): "Surface",
    ("ShapePayload", "geometry"): "PathGeometry",
    ("ShapePayload", "fill"): "Fill",
    ("ShapePayload", "stroke"): "Stroke",
    ("ShapePayload", "heads"): "Heads",
    ("ConnectorPayload", "stroke"): "Stroke",
    ("ConnectorPayload", "heads"): "Heads",
    ("SpecimenPayload", "params"): "Map",
    ("SpecimenPayload", "tracks"): "Map:SpecimenTrackOverride",
    ("SpecimenTrackOverride", "curve"): "CurveWrapper",
    ("ContainerPayload", "children"): "List:Node",
    ("ContainerPayload", "padding"): "Padding",
    ("ContainerPayload", "surface"): "Surface",
    ("LoupePayload", "children"): "List:Node",
    ("LoupePayload", "lensFill"): "Fill",
    ("Surface", "fill"): "Fill",
    ("Surface", "stroke"): "Stroke",
    ("Surface", "padding"): "Padding",
    ("Build", "args"): "Map",
    ("Build", "stagger"): "Stagger",
    ("Build", "set"): "Node",
    ("Build", "morph"): "TextMorph",
    ("Build", "curve"): "CurveWrapper",
}


def format_number(val: int | float) -> str:
    """Format number as shortest round-tripping decimal without trailing .0."""
    if isinstance(val, bool):
        return "true" if val else "false"
    if isinstance(val, int):
        return str(val)
    if math.isnan(val) or math.isinf(val):
        return "0"
    if val == 0.0:
        return "0"
    if float(int(round(val))) == val and abs(val) < 1e15:
        return str(int(round(val)))
    s = repr(float(val))
    if "e" in s or "E" in s:
        s = f"{val:.10f}".rstrip("0").rstrip(".")
        if s in ("", "-0"):
            return "0"
    if s.endswith(".0"):
        s = s[:-2]
    return s


def _is_primitive(v: Any) -> bool:
    return v is None or isinstance(v, (bool, int, float, str))


def _format_primitive(v: Any) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return format_number(v)
    if isinstance(v, str):
        return json.dumps(v, ensure_ascii=False)
    raise TypeError(f"Not primitive: {type(v)}")


def _ordered_items(obj: Dict[str, Any], schema: Optional[str]) -> List[Tuple[str, Any]]:
    if not schema or schema == "Map" or schema.startswith("Map:"):
        return sorted(obj.items(), key=lambda kv: kv[0])
    actual = "FillWrapper" if schema == "Fill" else schema
    order = TYPED_ORDERS.get(actual)
    if not order:
        return sorted(obj.items(), key=lambda kv: kv[0])
    rank = {k: i for i, k in enumerate(order)}
    return sorted(obj.items(), key=lambda kv: (rank.get(kv[0], 10_000), kv[0]))


def _child_schema(parent_schema: Optional[str], key: str) -> Optional[str]:
    if not parent_schema:
        return None
    if parent_schema.startswith("Map:"):
        return parent_schema[4:]
    actual = "FillWrapper" if parent_schema == "Fill" else parent_schema
    return CHILD_SCHEMA.get((actual, key))


def _print_value(val: Any, schema: Optional[str], indent: int, current_col: Optional[int] = None) -> str:
    if _is_primitive(val):
        return _format_primitive(val)
    col = indent if current_col is None else current_col
    pad = " " * indent
    next_pad = " " * (indent + 2)

    if isinstance(val, list):
        if not val:
            return "[]"
        elem_schema = schema[5:] if (schema and schema.startswith("List:")) else None
        if all(_is_primitive(x) for x in val):
            inline = "[" + ", ".join(_format_primitive(x) for x in val) + "]"
            if col + len(inline) <= 100:
                return inline
        lines = ["["]
        for i, item in enumerate(val):
            comma = "," if i + 1 < len(val) else ""
            rendered = _print_value(item, elem_schema, indent + 2, indent + 2)
            lines.append(f"{next_pad}{rendered}{comma}")
        lines.append(f"{pad}]")
        return "\n".join(lines)

    if isinstance(val, dict):
        if not val:
            return "{}"
        items = _ordered_items(val, schema)
        if all(_is_primitive(v) for _, v in items):
            parts = [f"{json.dumps(k, ensure_ascii=False)}: {_format_primitive(v)}" for k, v in items]
            inline = "{ " + ", ".join(parts) + " }"
            if col + len(inline) <= 100:
                return inline
        lines = ["{"]
        for i, (k, v) in enumerate(items):
            comma = "," if i + 1 < len(items) else ""
            cs = _child_schema(schema, k)
            quoted_k = json.dumps(k, ensure_ascii=False)
            val_col = indent + 2 + len(quoted_k) + 2
            rendered = _print_value(v, cs, indent + 2, val_col)
            lines.append(f"{next_pad}{quoted_k}: {rendered}{comma}")
        lines.append(f"{pad}}}")
        return "\n".join(lines)

    raise TypeError(f"Unsupported JSON value: {type(val)}")


def canonical_dumps(doc: Dict[str, Any]) -> str:
    """Serialize a DeckDocument in canonical format."""
    return _print_value(doc, "DeckDocument", 0) + "\n"


def validate_deck_document(
    doc: Dict[str, Any],
    assets_dir: Optional[Path] = None,
    fonts_root: Optional[Path] = None,
) -> Tuple[List[str], List[str]]:
    """
    Validate a DeckDocument.
    Returns (errors, warnings). Any load/schema violation is an error.
    """
    errors: List[str] = []
    warnings: List[str] = []

    if doc.get("format") != 1:
        errors.append(f"Top-level format must be 1, got {doc.get('format')!r}")
    for req in ("id", "title", "themes", "defaultTheme", "fonts", "slides"):
        if req not in doc:
            errors.append(f"Missing top-level key: {req}")
    top_unknown = set(doc.keys()) - set(TYPED_ORDERS["DeckDocument"])
    if top_unknown:
        errors.append(f"Unknown top-level keys: {sorted(top_unknown)}")
    if isinstance(doc.get("stage"), dict):
        st_unknown = set(doc["stage"].keys()) - set(TYPED_ORDERS["StageSize"])
        if st_unknown:
            errors.append(f"stage: unknown keys {sorted(st_unknown)}")

    # Validate fonts & acyclic fallback chain
    fonts = doc.get("fonts", [])
    declared_families: Set[str] = set()
    bundled_families: Set[str] = set()
    fallback_of: Dict[str, str] = {}
    for fi, f in enumerate(fonts):
        f_unknown = set(f.keys()) - set(TYPED_ORDERS["FontSpec"])
        if f_unknown:
            errors.append(f"fonts[{fi}]: unknown keys {sorted(f_unknown)}")
        fam = f.get("family")
        if not fam:
            errors.append(f"fonts[{fi}]: missing family")
            continue
        declared_families.add(fam)
        if f.get("src"):
            bundled_families.add(fam)
            if fonts_root is not None:
                fp = fonts_root / f["src"]
                if not fp.exists():
                    errors.append(f"fonts[{fi}]: bundled font missing on disk: {f['src']}")
        elif f.get("system"):
            fb = f.get("fallback")
            if not fb:
                errors.append(f"fonts[{fi}] ({fam}): system font missing fallback")
            else:
                fallback_of[fam] = fb
        else:
            errors.append(f"fonts[{fi}] ({fam}): must have src or system=true")

    for fam in fallback_of:
        seen: Set[str] = set()
        cur = fam
        while cur in fallback_of:
            if cur in seen:
                errors.append(f"Font fallback cycle involving {fam}")
                break
            seen.add(cur)
            cur = fallback_of[cur]
        if cur not in bundled_families:
            errors.append(f"Font fallback for {fam} does not terminate in a bundled family (ended at {cur})")

    palettes = set((doc.get("palettes") or {}).keys())
    slides = doc.get("slides", [])
    seen_slide_ids: Set[str] = set()

    def check_node(
        node: Dict[str, Any],
        slide_id: str,
        beat_count: int,
        seen_node_ids: Set[str],
        slide_index: int,
        all_node_ids: Set[str],
        node_build_ids: Dict[str, Set[str]],
    ) -> None:
        nid = node.get("id")
        addr = f"{slide_id}/{nid}"
        if not nid or not isinstance(nid, str):
            errors.append(f"{slide_id}: node missing string id")
            return
        if nid in seen_node_ids:
            errors.append(f"{addr}: duplicate node id")
        seen_node_ids.add(nid)

        unknown = set(node.keys()) - set(TYPED_ORDERS["Node"])
        if unknown:
            errors.append(f"{addr}: unknown node keys {sorted(unknown)}")

        payloads = [k for k in PAYLOAD_KEYS if k in node]
        if len(payloads) != 1:
            errors.append(f"{addr}: expected exactly 1 payload key, got {payloads}")
            return
        pkey = payloads[0]
        pval = node[pkey]

        if "frame" in node and isinstance(node["frame"], dict):
            fr_unknown = set(node["frame"].keys()) - set(TYPED_ORDERS["Frame"])
            if fr_unknown:
                errors.append(f"{addr}: unknown frame keys {sorted(fr_unknown)}")

        payload_schema_name = CHILD_SCHEMA.get(("Node", pkey))
        if payload_schema_name and payload_schema_name in TYPED_ORDERS and isinstance(pval, dict):
            p_unknown = set(pval.keys()) - set(TYPED_ORDERS[payload_schema_name])
            if p_unknown:
                errors.append(f"{addr}.{pkey}: unknown keys {sorted(p_unknown)}")

        if "opacity" in node:
            op = node["opacity"]
            if not isinstance(op, (int, float)) or op < 0 or op > 1:
                errors.append(f"{addr}: opacity out of range [0,1]: {op}")

        if pkey == "image":
            src = pval.get("src", "")
            if not src:
                errors.append(f"{addr}: image missing src")
            elif assets_dir is not None and not (assets_dir / src).exists():
                errors.append(f"{addr}: image asset missing on disk: {src}")
            low = src.lower()
            if any(bad in low for bad in ("pasted-movie", "posterimage", ".mov", ".mp4", ".gif")):
                errors.append(f"{addr}: forbidden video/poster/pasted-movie asset in image src: {src}")

        if pkey == "text":
            tf = pval.get("font")
            if tf and tf not in declared_families:
                errors.append(f"{addr}: undeclared font family {tf!r}")
            val = pval.get("value")
            if isinstance(val, list):
                for ri, run in enumerate(val):
                    r_unknown = set(run.keys()) - set(TYPED_ORDERS["TextRun"])
                    if r_unknown:
                        errors.append(f"{addr}: run[{ri}] unknown keys {sorted(r_unknown)}")
                    rf = run.get("font")
                    if rf and rf not in declared_families:
                        errors.append(f"{addr}: run[{ri}] undeclared font family {rf!r}")
                    if "shadow" in run:
                        errors.append(f"{addr}: text shadows not supported in v1")
            if "shadow" in pval:
                errors.append(f"{addr}: text shadows not supported in v1")

        if pkey == "code":
            # Changing code nodes must declare explicit w and h so bounds stay fixed across beats
            changes_value = any(
                b.get("kind") == "change" and isinstance((b.get("set") or {}).get("code"), dict)
                and "value" in b["set"]["code"]
                for b in node.get("builds", [])
            )
            if changes_value:
                fr = node.get("frame") or {}
                if "w" not in fr or "h" not in fr:
                    errors.append(f"{addr}: changing code node missing explicit frame.w/h")

        if pkey == "specimen":
            ref = pval.get("ref", "")
            if not ref:
                errors.append(f"{addr}: specimen missing ref")
            if ref == "todo.video":
                warnings.append(f"{addr}: unmapped todo.video specimen")
            fr = node.get("frame") or {}
            if not all(k in fr for k in ("x", "y", "w", "h")):
                warnings.append(f"{addr}: specimen without full explicit frame")

        # Validate builds
        seen_bids: Set[str] = set()
        code_value_beats: Set[int] = set()
        for bi, b in enumerate(node.get("builds", [])):
            baddr = f"{addr}#build[{bi}]"
            kind = b.get("kind")
            if kind not in ("enter", "exit", "emphasis", "change"):
                errors.append(f"{baddr}: invalid build kind {kind!r}")
                continue
            b_unknown = set(b.keys()) - set(TYPED_ORDERS["Build"])
            if b_unknown:
                errors.append(f"{baddr}: unknown build keys {sorted(b_unknown)}")
            beat = b.get("beat")
            if not isinstance(beat, int) or beat < 0 or beat >= beat_count:
                errors.append(f"{baddr}: beat {beat!r} out of range 0..{beat_count - 1}")
            if slide_index == 0 and kind == "enter" and beat == 0:
                errors.append(f"{baddr}: slide 0 cannot have beat-0 enter build")
            bid = b.get("id")
            if bid is not None:
                if bid in seen_bids:
                    errors.append(f"{baddr}: duplicate build id {bid!r}")
                seen_bids.add(bid)
            for num_k in ("delayMs", "durationMs"):
                if num_k in b and (not isinstance(b[num_k], (int, float)) or b[num_k] < 0):
                    errors.append(f"{baddr}: {num_k} must be >= 0")

            if kind in ("enter", "exit", "emphasis"):
                eff = b.get("effect")
                if eff not in VALID_EFFECTS:
                    errors.append(f"{baddr}: unknown effect {eff!r}")
                else:
                    if eff == "keyboard" and pkey != "text":
                        errors.append(f"{baddr}: keyboard effect only valid on text nodes")
                    if eff == "lineDraw" and pkey not in ("shape", "connector"):
                        errors.append(f"{baddr}: lineDraw effect only valid on shape/connector nodes")
                    args = b.get("args") or {}
                    bad_args = set(args.keys()) - EFFECT_ALLOWED_ARGS[eff]
                    if bad_args:
                        errors.append(f"{baddr}: unknown args {sorted(bad_args)} for effect {eff}")
            else:
                patch = b.get("set")
                if not isinstance(patch, dict) or not patch:
                    errors.append(f"{baddr}: change build missing non-empty set")
                else:
                    forbidden = set(patch.keys()) & {"id", "children", "builds"}
                    if forbidden:
                        errors.append(f"{baddr}: set cannot modify {sorted(forbidden)}")
                    other_payloads = (set(patch.keys()) & PAYLOAD_KEYS) - {pkey}
                    if other_payloads:
                        errors.append(f"{baddr}: set introduces different payload {sorted(other_payloads)}")
                    if pkey == "code" and isinstance(patch.get("code"), dict) and "value" in patch["code"]:
                        if beat in code_value_beats:
                            errors.append(f"{baddr}: two code.value changes on same beat {beat}")
                        code_value_beats.add(beat)

            after = b.get("after")
            if after is not None:
                if "#" in after:
                    t_nid, t_bid = after.split("#", 1)
                    if t_nid not in all_node_ids:
                        errors.append(f"{baddr}: dangling after node {after!r}")
                    elif t_bid not in node_build_ids.get(t_nid, set()):
                        errors.append(f"{baddr}: dangling after build {after!r}")
                else:
                    if after not in all_node_ids:
                        errors.append(f"{baddr}: dangling after node {after!r}")

        if pkey in ("stack", "row", "column", "loupe"):
            for ch in pval.get("children", []):
                check_node(ch, slide_id, beat_count, seen_node_ids, slide_index, all_node_ids, node_build_ids)

    for si, slide in enumerate(slides):
        sid = slide.get("id", f"<slide-{si}>")
        if sid in seen_slide_ids:
            errors.append(f"Duplicate slide id: {sid}")
        seen_slide_ids.add(sid)
        s_unknown = set(slide.keys()) - set(TYPED_ORDERS["Slide"])
        if s_unknown:
            errors.append(f"{sid}: unknown slide keys {sorted(s_unknown)}")
        pal = slide.get("palette")
        if pal and pal not in palettes:
            errors.append(f"{sid}: unknown palette {pal!r}")
        beats = slide.get("beats") if "beats" in slide else [{}]
        if not isinstance(beats, list) or not beats:
            errors.append(f"{sid}: beats must have length >= 1")
            beats = [{}]
        for bti, bt in enumerate(beats):
            if not isinstance(bt, dict):
                errors.append(f"{sid}/beats[{bti}]: beat must be object")
                continue
            bt_unknown = set(bt.keys()) - set(TYPED_ORDERS["Beat"])
            if bt_unknown:
                errors.append(f"{sid}/beats[{bti}]: unknown beat keys {sorted(bt_unknown)}")
            if "autoAdvanceMs" in bt:
                aa = bt["autoAdvanceMs"]
                if not isinstance(aa, (int, float)) or aa < 0:
                    errors.append(f"{sid}/beats[{bti}]: autoAdvanceMs must be >= 0, got {aa!r}")
        tr = slide.get("transition")
        if tr is not None:
            if si == len(slides) - 1:
                errors.append(f"{sid}: last slide should not carry a transition")
            tt = tr.get("type", "dissolve")
            if tt not in VALID_TRANSITIONS:
                errors.append(f"{sid}: invalid transition type {tt!r}")
            t_unknown = set(tr.keys()) - set(TYPED_ORDERS["Transition"])
            if t_unknown:
                errors.append(f"{sid}: unknown transition keys {sorted(t_unknown)}")

        # Collect all node IDs and build IDs on slide for `after` checks
        all_nids: Set[str] = set()
        n_bids: Dict[str, Set[str]] = {}

        def gather(n: Dict[str, Any]) -> None:
            nid = n.get("id")
            if nid:
                all_nids.add(nid)
                n_bids[nid] = {b["id"] for b in n.get("builds", []) if "id" in b}
            for pk in ("stack", "row", "column", "loupe"):
                if pk in n:
                    for ch in n[pk].get("children", []):
                        gather(ch)

        for n in slide.get("nodes", []):
            gather(n)

        seen_nids: Set[str] = set()
        for n in slide.get("nodes", []):
            check_node(n, sid, len(beats), seen_nids, si, all_nids, n_bids)

        # Verify `after` dependency graph is acyclic on each beat
        beat_edges: Dict[int, Dict[str, List[str]]] = {}

        def collect_after(n: Dict[str, Any]) -> None:
            nid = n.get("id")
            if nid:
                for b in n.get("builds", []):
                    bt = b.get("beat")
                    af = b.get("after")
                    if isinstance(bt, int) and isinstance(af, str):
                        src_key = f"{nid}#{b['id']}" if "id" in b else nid
                        beat_edges.setdefault(bt, {}).setdefault(src_key, []).append(af)
            for pk in ("stack", "row", "column", "loupe"):
                if pk in n:
                    for ch in n[pk].get("children", []):
                        collect_after(ch)

        for n in slide.get("nodes", []):
            collect_after(n)

        for bt, adj in beat_edges.items():
            visiting: Set[str] = set()
            visited: Set[str] = set()

            def dfs(u: str) -> bool:
                if u in visiting:
                    return True
                if u in visited:
                    return False
                visiting.add(u)
                for v in adj.get(u, []):
                    if dfs(v):
                        return True
                visiting.remove(u)
                visited.add(u)
                return False

            for node_key in adj:
                if dfs(node_key):
                    errors.append(f"{sid}/beat[{bt}]: cycle detected in build `after` chain involving {node_key!r}")
                    break

    return errors, warnings
