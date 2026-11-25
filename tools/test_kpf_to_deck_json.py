#!/usr/bin/env python3
"""Comprehensive unit & integration tests for the KPF -> .deck.json pipeline."""

from __future__ import annotations

import json
import math
import re
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple
from unittest.mock import patch

TOOLS_DIR = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_DIR.parent
DECKS_DIR = REPO_ROOT / "deck" / "src" / "desktopMain" / "resources" / "decks"

if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import kpf_to_deck_json  # noqa: E402
from kpf_converter.canonical import (  # noqa: E402
    VALID_EFFECTS,
    VALID_TRANSITIONS,
    canonical_dumps,
    format_number,
    validate_deck_document,
)
from kpf_converter.effects import (  # noqa: E402
    NodeAnimState,
    attach_event_effects,
    extract_curve,
    map_single_effect,
    map_transition,
)
from kpf_converter.converter import (  # noqa: E402
    _build_pure_shape_slot_node,
    _merge_shapes_to_single_shape,
    _node_present_at_beat0,
    _unify_magic_move_ids,
    try_convert_arrow_shape_payload,
)
from kpf_converter.pdf_atlas import (  # noqa: E402
    _classify_svg_path,
    _parse_tounicode_cmap,
    apply_alpha_hex,
    p3_to_srgb,
    rgb_to_hex,
)
from kpf_converter.registry import (  # noqa: E402
    build_font_specs,
    classify_jewel_static_specimen,
    classify_shaders_preview_specimen,
    resolve_movie_specimen,
)


def _walk_nodes(nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for n in nodes:
        out.append(n)
        for pk in ("stack", "row", "column", "loupe"):
            if pk in n:
                out.extend(_walk_nodes(n[pk].get("children") or []))
    return out


def _plain_text(node: Dict[str, Any]) -> str:
    if "text" not in node:
        return ""
    val = node["text"].get("value")
    if isinstance(val, str):
        return val
    if isinstance(val, list):
        return "".join(str(r.get("text") or "") for r in val)
    return ""


def _minimal_valid_doc() -> Dict[str, Any]:
    return {
        "format": 1,
        "id": "unit-test-deck",
        "title": "Unit Test Deck",
        "stage": {"width": 1920, "height": 1080},
        "themes": {
            "dark": {
                "preset": "dark",
                "colors": {"canvas": "#111111", "ink": "#FFFFFF"},
                "typography": {"body": {"font": "Inter", "size": 32, "color": "ink"}},
            }
        },
        "defaultTheme": "dark",
        "fonts": [
            {"family": "Inter", "src": "fonts/inter/Inter-Regular.ttf", "weight": 400},
        ],
        "slides": [
            {
                "id": "s01",
                "title": "Title",
                "background": "#111111",
                "beats": [{}],
                "nodes": [
                    {
                        "id": "hello",
                        "frame": {"x": 100, "y": 100, "w": 400, "h": 80},
                        "text": {"value": "Hello", "font": "Inter", "size": 48, "color": "#FFFFFF"},
                    }
                ],
            }
        ],
    }


class CanonicalJsonTests(unittest.TestCase):
    def test_number_formatting_no_trailing_dot_zero(self) -> None:
        self.assertEqual(format_number(0.0), "0")
        self.assertEqual(format_number(-0.0), "0")
        self.assertEqual(format_number(42.0), "42")
        self.assertEqual(format_number(1920.0), "1920")
        self.assertEqual(format_number(0.311), "0.311")
        self.assertEqual(format_number(12.5), "12.5")

    def test_canonical_key_order_inline_and_idempotency(self) -> None:
        doc = _minimal_valid_doc()
        # Scramble key insertion order deliberately
        doc["slides"][0]["nodes"][0]["frame"] = {"h": 80.0, "w": 400.0, "y": 100.0, "x": 100.0}
        out = canonical_dumps(doc)
        self.assertNotIn("\r\n", out)
        self.assertTrue(out.endswith("\n"))
        # Frame keys must appear in canonical order x, y, w, h inline without .0
        self.assertIn('{ "x": 100, "y": 100, "w": 400, "h": 80 }', out)
        # DeckDocument top keys in exact order
        self.assertLess(out.index('"format"'), out.index('"id"'))
        self.assertLess(out.index('"id"'), out.index('"title"'))
        self.assertLess(out.index('"stage"'), out.index('"themes"'))
        self.assertLess(out.index('"fonts"'), out.index('"slides"'))
        # Strict byte-identical round-trip
        self.assertEqual(canonical_dumps(json.loads(out)), out)

    def test_long_primitive_object_wraps_past_100_columns(self) -> None:
        doc = _minimal_valid_doc()
        doc["slides"][0]["nodes"][0]["text"]["value"] = "A" * 120
        out = canonical_dumps(doc)
        # The text object exceeds 100 cols so it must expand multi-line
        self.assertIn('"text": {\n', out)
        self.assertEqual(canonical_dumps(json.loads(out)), out)


class PdfColorAndCmapTests(unittest.TestCase):
    def test_p3_to_srgb_and_alpha_hex(self) -> None:
        r, g, b = p3_to_srgb(0.34, 0.27, 0.28)
        hx = rgb_to_hex(r, g, b)
        self.assertRegex(hx, r"^#[0-9A-F]{6}$")
        self.assertEqual(apply_alpha_hex("#112233", 0.5), "#80112233")
        self.assertEqual(apply_alpha_hex("#80112233", 0.5), "#40112233")

    def test_tounicode_cmap_spaced_hex_emojis_and_ligatures(self) -> None:
        sample_cmap = b"""
        1 beginbfchar
        <21><d83d dc7b>
        <22><0066 0066 0069>
        <23><002f 002f>
        <24><003d 003d>
        <25><fb01>
        endbfchar
        1 beginbfrange
        <30><30><23e9>
        endbfrange
        """
        cmap = _parse_tounicode_cmap(sample_cmap)
        self.assertEqual(cmap[0x21], "👻")
        self.assertEqual(cmap[0x22], "ffi")
        self.assertEqual(cmap[0x23], "//")
        self.assertEqual(cmap[0x24], "==")
        self.assertEqual(cmap[0x25], "fi")
        self.assertEqual(cmap[0x30], "⏩")


class EffectsAndBuildsTests(unittest.TestCase):
    def test_all_nine_transitions_map_cleanly(self) -> None:
        samples = [
            ({"type": "transition", "name": "none", "duration": 0}, "none"),
            ({"type": "transition", "name": "apple:magic-move-implied-motion-path", "duration": 0.5}, "magicMove"),
            ({"type": "transition", "name": "apple:dissolve", "duration": 0.4}, "dissolve"),
            ({"type": "transition", "name": "apple:push", "duration": 0.6, "attributes": {"direction": 10}}, "push"),
            ({"type": "transition", "name": "apple:slide", "duration": 0.6, "attributes": {"direction": 11}}, "slide"),
            ({"type": "transition", "name": "apple:reveal", "duration": 0.6, "attributes": {"direction": 10}}, "reveal"),
            ({"type": "transition", "name": "apple:wipe", "duration": 0.7, "attributes": {"direction": 11}}, "wipe"),
            ({"type": "transition", "name": "apple:wipe-iris", "duration": 0.8}, "iris"),
            (
                {
                    "type": "transition",
                    "name": "com.apple.iWork.Keynote.BLTFadeThruColor",
                    "duration": 0.3,
                },
                "fadeThroughColor",
            ),
        ]
        seen: Set[str] = set()
        for ef, expected_type in samples:
            tr = map_transition(ef)
            self.assertEqual(tr["type"], expected_type)
            self.assertIn(tr["type"], VALID_TRANSITIONS)
            seen.add(tr["type"])
        self.assertEqual(seen, set(VALID_TRANSITIONS))

    def test_apple_cubic_bezier_lowercase_and_uppercase(self) -> None:
        c_low = extract_curve({
            "timingFunction": "custom",
            "timingControlPoint1x": 0.311,
            "timingControlPoint1y": 0.208,
            "timingControlPoint2x": 0.201,
            "timingControlPoint2y": 1.0,
        })
        self.assertEqual(c_low, {"bezier": [0.311, 0.208, 0.201, 1]})
        c_up = extract_curve({
            "timingFunction": "custom",
            "timingControlPoint1X": 0.406,
            "timingControlPoint1Y": 0.5,
            "timingControlPoint2X": 0.602,
            "timingControlPoint2Y": 1.0,
        })
        self.assertEqual(c_up, {"bezier": [0.406, 0.5, 0.602, 1]})

    def test_all_keynote_build_effects_and_node_kind_guards(self) -> None:
        kpf_effect_names = [
            ("buildIn", "apple:appear", "text", "appear"),
            ("buildIn", "apple:bc-appear", "text", "appear"),
            ("buildOut", "apple:appear", "text", "appear"),
            ("buildIn", "apple:dissolve", "text", "dissolve"),
            ("buildIn", "apple:dissolve character", "text", "dissolve"),
            ("buildIn", "apple:fade and move", "text", "fadeMove"),
            ("buildIn", "apple:fade and move character", "text", "fadeMove"),
            ("buildIn", "apple:move in", "image", "moveIn"),
            ("buildIn", "apple:move in character", "text", "moveIn"),
            ("buildIn", "apple:wipe", "specimen", "wipe"),
            ("buildIn", "apple:wipe-iris", "shape", "iris"),
            ("buildIn", "apple:zoom", "shape", "zoom"),
            ("buildIn", "apple:bc-zoom-big character", "text", "zoom"),
            ("buildOut", "apple:bc-zoom-big character", "text", "zoom"),
            ("buildIn", "apple:pop", "text", "pop"),
            ("buildIn", "apple:bc-pop", "text", "pop"),
            ("buildIn", "com.apple.iWork.Keynote.Blur", "specimen", "blur"),
            ("buildIn", "com.apple.iWork.Keynote.FromDarkness", "text", "fromDarkness"),
            ("buildIn", "apple:keyboard", "text", "keyboard"),
            ("buildOut", "apple:keyboard", "text", "keyboard"),
            ("buildIn", "com.apple.iWork.Keynote.LineDraw", "shape", "lineDraw"),
            ("buildIn", "com.apple.iWork.Keynote.LineDrawForLine", "shape", "lineDraw"),
            ("buildOut", "com.apple.iWork.Keynote.LineDrawForLine", "shape", "lineDraw"),
            ("buildIn", "com.apple.iWork.Keynote.Trace", "shape", "trace"),
            ("buildIn", "com.apple.iWork.Keynote.KLNSparkle", "shape", "sparkle"),
            ("buildIn", "com.apple.iWork.Keynote.KLNShimmer", "text", "shimmer"),
        ]
        for et, name, kind, expected_eff in kpf_effect_names:
            st = NodeAnimState(x=0, y=0, w=200, h=100)
            mapped = map_single_effect({"type": et, "name": name, "duration": 0.5}, kind, st, beat=1, text_char_count=12)
            self.assertIsNotNone(mapped, f"Failed to map {(et, name)}")
            self.assertEqual(mapped["effect"], expected_eff)
            self.assertIn(mapped["effect"], VALID_EFFECTS)

        # Guard: keyboard on non-text degrades cleanly to dissolve; lineDraw on non-shape degrades to wipe/dissolve
        st = NodeAnimState(x=0, y=0, w=200, h=100)
        kb_non_text = map_single_effect(
            {"type": "buildIn", "name": "apple:keyboard", "duration": 0.4}, "shape", st, beat=1
        )
        self.assertEqual(kb_non_text["effect"], "dissolve")
        ld_non_shape = map_single_effect(
            {"type": "buildIn", "name": "com.apple.iWork.Keynote.LineDrawForLine", "duration": 0.4, "attributes": {"direction": 11}},
            "text",
            st,
            beat=1,
        )
        self.assertEqual(ld_non_shape["effect"], "wipe")

    def test_build_stack_merged_actions_curved_path_and_after_chain(self) -> None:
        # Simulate enter + simultaneous motion+rotation + curved motion + opacity + jiggle + exit + after chain
        node_a: Dict[str, Any] = {
            "id": "actor-a",
            "frame": {"x": 100, "y": 200, "w": 80, "h": 80},
            "text": {"value": "🤌", "font": "Noto Emoji", "size": 48, "color": "#000000"},
        }
        node_b: Dict[str, Any] = {
            "id": "actor-b",
            "frame": {"x": 500, "y": 200, "w": 20, "h": 50},
            "text": {"value": ")", "font": "JetBrains Mono", "size": 40, "color": "#000000"},
        }
        oid_to_node = {"A": node_a, "B": node_b}
        oid_to_kind = {"A": "text", "B": "text"}
        oid_to_state = {
            "A": NodeAnimState(x=100, y=200, w=80, h=80),
            "B": NodeAnimState(x=500, y=200, w=20, h=50),
        }
        oid_to_len = {"A": 1, "B": 1}

        # Beat 1: A enters -> after A, A simultaneously moves + rotates -> after movie-start (0.2s), B follows curved path
        ev1 = {
            "effects": [
                {
                    "type": "buildIn",
                    "name": "apple:dissolve",
                    "objectID": "A",
                    "beginTime": 0.0,
                    "duration": 0.2,
                    "effects": [
                        {
                            "type": "actionBuild",
                            "name": "apple:action-motion-path",
                            "objectID": "A",
                            "beginTime": 0.0,
                            "duration": 0.3,
                            "baseLayer": {
                                "animations": [
                                    {
                                        "property": "position",
                                        "from": {"pointX": 140, "pointY": 240},
                                        "to": {"pointX": 155, "pointY": 240},
                                        "duration": 0.3,
                                    }
                                ]
                            },
                        },
                        {
                            "type": "actionBuild",
                            "name": "apple:action-rotation",
                            "objectID": "A",
                            "beginTime": 0.0,
                            "duration": 0.3,
                            "baseLayer": {
                                "animations": [
                                    {
                                        "property": "transform.rotation.z",
                                        "from": {"scalar": 0.0},
                                        "to": {"scalar": math.radians(19.0)},
                                        "duration": 0.3,
                                    }
                                ]
                            },
                        },
                        {
                            "type": "actionBuild",
                            "name": "apple:movie-start",
                            "objectID": "M",
                            "beginTime": 0.2,
                            "duration": 0.0,
                            "effects": [
                                {
                                    "type": "actionBuild",
                                    "name": "apple:action-motion-path",
                                    "objectID": "B",
                                    "beginTime": 0.1,
                                    "duration": 0.4,
                                    "baseLayer": {
                                        "animations": [
                                            {
                                                "property": "position",
                                                "values": [
                                                    {"pointX": 510, "pointY": 225},
                                                    {"pointX": 610, "pointY": 225},
                                                    {"pointX": 660, "pointY": 275},
                                                    {"pointX": 660, "pointY": 375},
                                                ],
                                            }
                                        ]
                                    },
                                }
                            ],
                        },
                    ],
                }
            ]
        }
        attach_event_effects(ev1, 1, oid_to_node, oid_to_kind, oid_to_state, oid_to_len)

        # Beat 2: A jiggles, then exits
        ev2 = {
            "effects": [
                {
                    "type": "actionBuild",
                    "name": "apple:action-jiggle",
                    "objectID": "A",
                    "beginTime": 0.0,
                    "duration": 0.8,
                    "attributes": {"jiggleDistance": 5.0},
                    "effects": [
                        {
                            "type": "buildOut",
                            "name": "apple:dissolve",
                            "objectID": "A",
                            "beginTime": 0.1,
                            "duration": 0.3,
                        }
                    ],
                }
            ]
        }
        attach_event_effects(ev2, 2, oid_to_node, oid_to_kind, oid_to_state, oid_to_len)

        a_builds = node_a["builds"]
        self.assertEqual([b["kind"] for b in a_builds], ["enter", "change", "emphasis", "exit"])
        # Simultaneous move + rotation merged into ONE change build!
        self.assertEqual(a_builds[1]["set"], {"frame": {"x": 115, "y": 200}, "rotation": 19})
        # Same-node sequential builds on the same beat follow list order (no redundant `after`)
        self.assertNotIn("after", a_builds[1])
        self.assertEqual(a_builds[2]["effect"], "jiggle")
        self.assertEqual(a_builds[3]["kind"], "exit")

        # B inherited dropped movie-start delay (200 + 100 = 300ms), disambiguates `after` with `#buildId`
        # because `actor-a` has 2 builds on beat 1, and curved SVG path ends at exact (dx, dy) = (150, 150)
        b_build = node_b["builds"][0]
        self.assertEqual(b_build["kind"], "change")
        self.assertEqual(b_build["after"], "actor-a#b1-1")
        self.assertEqual(b_build["delayMs"], 300)
        self.assertEqual(b_build["set"]["frame"], {"x": 650, "y": 350})
        self.assertIn("path", b_build)
        self.assertTrue(b_build["path"].startswith("M 0 0 "))
        self.assertTrue(b_build["path"].endswith(" 150 150"))


class SchemaValidatorNegativeTests(unittest.TestCase):
    def test_rejects_unknown_keys(self) -> None:
        doc = _minimal_valid_doc()
        doc["bogusTopKey"] = True
        doc["slides"][0]["bogusSlideKey"] = 1
        doc["slides"][0]["nodes"][0]["bogusNodeKey"] = "nope"
        errs, _ = validate_deck_document(doc)
        self.assertTrue(any("bogusTopKey" in e for e in errs))
        self.assertTrue(any("bogusSlideKey" in e for e in errs))
        self.assertTrue(any("bogusNodeKey" in e for e in errs))

    def test_rejects_dangling_and_cyclic_after(self) -> None:
        doc = _minimal_valid_doc()
        doc["slides"][0]["beats"] = [{}, {}]
        doc["slides"][0]["nodes"] = [
            {
                "id": "n1",
                "frame": {"x": 0, "y": 0, "w": 100, "h": 50},
                "text": {"value": "1", "font": "Inter", "size": 24, "color": "#FFFFFF"},
                "builds": [{"kind": "enter", "beat": 1, "effect": "appear", "after": "n2"}],
            },
            {
                "id": "n2",
                "frame": {"x": 0, "y": 60, "w": 100, "h": 50},
                "text": {"value": "2", "font": "Inter", "size": 24, "color": "#FFFFFF"},
                "builds": [{"kind": "enter", "beat": 1, "effect": "appear", "after": "n1"}],
            },
            {
                "id": "n3",
                "frame": {"x": 0, "y": 120, "w": 100, "h": 50},
                "text": {"value": "3", "font": "Inter", "size": 24, "color": "#FFFFFF"},
                "builds": [{"kind": "enter", "beat": 1, "effect": "appear", "after": "missing-id"}],
            },
        ]
        errs, _ = validate_deck_document(doc)
        self.assertTrue(any("cycle" in e for e in errs))
        self.assertTrue(any("dangling after node" in e for e in errs))

    def test_rejects_changing_code_node_without_explicit_wh_and_slide0_enter_and_last_transition(self) -> None:
        doc = _minimal_valid_doc()
        doc["slides"][0]["transition"] = {"type": "dissolve", "durationMs": 400}
        doc["slides"][0]["nodes"] = [
            {
                "id": "code1",
                "frame": {"x": 50, "y": 50},
                "code": {"value": "val x = 1", "language": "kotlin"},
                "builds": [
                    {"kind": "enter", "beat": 0, "effect": "appear"},
                    {"kind": "change", "beat": 0, "set": {"code": {"value": "val x = 2"}}},
                ],
            }
        ]
        errs, _ = validate_deck_document(doc)
        self.assertTrue(any("frame.w/h" in e for e in errs))
        self.assertTrue(any("slide 0 cannot have beat-0 enter" in e for e in errs))
        self.assertTrue(any("last slide should not carry a transition" in e for e in errs))

    def test_rejects_forbidden_video_image_and_font_cycle(self) -> None:
        doc = _minimal_valid_doc()
        doc["fonts"].extend([
            {"family": "A", "system": True, "fallback": "B"},
            {"family": "B", "system": True, "fallback": "A"},
        ])
        doc["slides"][0]["nodes"].append({
            "id": "bad-img",
            "frame": {"x": 0, "y": 0, "w": 100, "h": 100},
            "image": {"src": "pasted-movie.png", "fit": "contain"},
        })
        errs, _ = validate_deck_document(doc)
        self.assertTrue(any("Font fallback cycle" in e for e in errs))
        self.assertTrue(any("forbidden video/poster/pasted-movie" in e for e in errs))

    def test_specimen_classifiers_and_font_specs(self) -> None:
        # Shaders BEFORE vs AFTER SDF preview squares
        self.assertEqual(classify_shaders_preview_specimen(39, 1351, 336, 408, 408, is_raster=True, is_build_in=False)[0], "shaders.sdfRawDistance")
        self.assertEqual(classify_shaders_preview_specimen(39, 1351, 336, 408, 408, is_raster=True, is_build_in=True)[0], "shaders.sdfStepCircle")
        self.assertEqual(classify_shaders_preview_specimen(42, 1351, 336, 408, 408, is_raster=True, is_build_in=False)[0], "shaders.sdfStepCircle")
        self.assertEqual(classify_shaders_preview_specimen(42, 1351, 336, 408, 408, is_raster=True, is_build_in=True)[0], "shaders.sdfCsgSubtract")
        self.assertEqual(classify_shaders_preview_specimen(44, 1351, 336, 408, 408, is_raster=True, is_build_in=False)[0], "shaders.sdfCsgSubtract")
        self.assertEqual(classify_shaders_preview_specimen(44, 1351, 336, 408, 408, is_raster=True, is_build_in=True)[0], "shaders.sdfCsgUnionSubtract")
        # Jewel mirrored confetti popper
        ref_l, p_l, id_l = resolve_movie_specimen("tada.gif", "jewel", 31, (-40, 590, 512, 512))
        ref_r, p_r, id_r = resolve_movie_specimen("tada.gif", "jewel", 31, (1451, 590, 512, 512))
        self.assertEqual((ref_l, p_l, id_l), ("jewel.confettiPartyPopper", {}, "specimen-confetti-left"))
        self.assertEqual((ref_r, p_r, id_r), ("jewel.confettiPartyPopper", {"mirrored": True}, "specimen-confetti-right"))
        # Jewel cohesion panel classifier
        self.assertEqual(classify_jewel_static_specimen(61, 406, 795, 924, 142, 406, 795)[1], {"panel": "emulator"})
        # Font specs always declare acyclic fallbacks
        fonts = build_font_specs({"Fredoka", "Google Sans Display", "Oscine", "Snell Roundhand", "Hanken Grotesk"})
        families = {f["family"] for f in fonts}
        self.assertIn("Inter", families)
        self.assertIn("Google Sans", families)
        self.assertIn("Plus Jakarta Sans", families)
        self.assertIn("Nanum Pen Script", families)


class CanonicalThreeTalkIntegrationTests(unittest.TestCase):
    EXPECTED_COUNTS = {
        "shaders-quickie-2025": 48,
        "jewel-talk-2024": 75,
        "not-just-plain-text-2024": 136,
    }

    @classmethod
    def setUpClass(cls) -> None:
        cls.raw_texts: Dict[str, str] = {}
        cls.docs: Dict[str, Dict[str, Any]] = {}
        for deck_id in cls.EXPECTED_COUNTS:
            path = DECKS_DIR / f"{deck_id}.deck.json"
            assert path.exists(), f"Missing generated deck JSON: {path}"
            raw = path.read_text(encoding="utf-8")
            cls.raw_texts[deck_id] = raw
            cls.docs[deck_id] = json.loads(raw)

    def test_exact_slide_counts_total_259(self) -> None:
        total = 0
        for deck_id, expected in self.EXPECTED_COUNTS.items():
            actual = len(self.docs[deck_id]["slides"])
            self.assertEqual(actual, expected, f"{deck_id} slide count mismatch")
            total += actual
        self.assertEqual(total, 259)

    def test_strict_schema_zero_errors_zero_warnings_and_canonical_idempotency(self) -> None:
        for deck_id, doc in self.docs.items():
            raw = self.raw_texts[deck_id]
            self.assertNotIn("\r\n", raw, f"{deck_id} must use LF line endings")
            self.assertTrue(raw.endswith("\n"), f"{deck_id} must end with trailing LF")
            self.assertEqual(canonical_dumps(doc), raw, f"{deck_id} is not canonical JSON")
            assets_dir = DECKS_DIR / f"{deck_id}.assets"
            errs, warns = validate_deck_document(doc, assets_dir=assets_dir)
            self.assertEqual(errs, [], f"{deck_id} validation errors: {errs[:10]}")
            self.assertEqual(warns, [], f"{deck_id} validation warnings: {warns[:10]}")

    def test_zero_video_or_poster_files_and_no_orphaned_assets(self) -> None:
        forbidden_re = re.compile(r"(pasted-movie|posterimage|\.(mov|mp4|m4v|gif|webm)\b)", re.IGNORECASE)
        for deck_id, doc in self.docs.items():
            assets_dir = DECKS_DIR / f"{deck_id}.assets"
            self.assertTrue(assets_dir.is_dir(), f"Missing assets dir {assets_dir}")
            disk_files = {p.name for p in assets_dir.iterdir() if p.is_file()}
            referenced: Set[str] = set()
            for slide in doc["slides"]:
                for node in _walk_nodes(slide.get("nodes") or []):
                    if "image" in node:
                        src = node["image"]["src"]
                        self.assertFalse(forbidden_re.search(src), f"Forbidden asset in {deck_id}/{slide['id']}: {src}")
                        referenced.add(src)
            for fname in disk_files:
                self.assertFalse(forbidden_re.search(fname), f"Forbidden file on disk: {assets_dir / fname}")
            self.assertEqual(referenced, disk_files, f"{deck_id} asset mismatch (referenced vs on-disk)")

    def test_all_nine_transitions_represented_across_three_talks(self) -> None:
        found: Set[str] = set()
        for doc in self.docs.values():
            for slide in doc["slides"]:
                tr = slide.get("transition")
                if tr is not None:
                    found.add(tr.get("type", "dissolve"))
        self.assertEqual(found, set(VALID_TRANSITIONS))

    def test_all_expected_live_specimens_present_with_explicit_frames(self) -> None:
        expected_refs = {
            "shaders-quickie-2025": {
                "shaders.trippyKaleidoscope",
                "shaders.journeyDunes",
                "shaders.hazeScrollHeader",
                "shaders.liquidGlassLens",
                "shaders.raindropsWindow",
                "shaders.colorEvolution",
                "shaders.liveUvStatic",
                "shaders.liveUvAnimated",
                "shaders.sdfGoalCircle",
                "shaders.sdfRawDistance",
                "shaders.sdfStepCircle",
                "shaders.sdfCsgSubtract",
                "shaders.sdfCsgUnionSubtract",
                "shaders.plaidHyperspaceStarfield",
            },
            "jewel-talk-2024": {
                "jewel.taskBasedProfilers",
                "jewel.addDeviceDialog",
                "jewel.geminiAuthCard",
                "jewel.geminiPromptInput",
                "jewel.geminiStreamingChat",
                "jewel.geminiChatComposer",
                "jewel.referencesBouncyExpand",
                "jewel.geminiToolWindowFull",
                "jewel.confettiPartyPopper",
                "jewel.markdownStandaloneSample",
                "jewel.swingComposeInterop",
                "jewel.microinteractionScrollFab",
                "jewel.microinteractionEnabledButton",
                "jewel.adaptiveDemoWizard",
                "jewel.adaptiveWireframePanes",
                "jewel.m3ExpressiveShowcase",
                "jewel.springTokenTree",
                "jewel.geminiLoadingChoreography",
                "jewel.referencesBouncySpringV2",
                "jewel.cohesionIdeShowcase",
            },
            "not-just-plain-text-2024": {
                "njpt.jewelMarkdownSplitDemo",
                "njpt.geminiDarkMarkdownChat",
            },
        }
        for deck_id, required in expected_refs.items():
            seen_refs: Set[str] = set()
            for slide in self.docs[deck_id]["slides"]:
                for node in _walk_nodes(slide.get("nodes") or []):
                    if "specimen" in node:
                        ref = node["specimen"]["ref"]
                        self.assertNotEqual(ref, "todo.video")
                        seen_refs.add(ref)
                        fr = node.get("frame") or {}
                        for k in ("x", "y", "w", "h"):
                            self.assertIn(k, fr, f"{deck_id}/{slide['id']}/{node['id']} specimen missing frame.{k}")
                            self.assertGreater(fr["w"], 0)
                            self.assertGreater(fr["h"], 0)
            self.assertEqual(seen_refs, required, f"{deck_id} specimen ref set mismatch")

    def test_all_45_magic_move_transitions_and_28_chains(self) -> None:
        expected_mm_counts = {
            "shaders-quickie-2025": 13,
            "jewel-talk-2024": 26,
            "not-just-plain-text-2024": 6,
        }
        # Jewel S43->S44 and S47->S48 are pure cross-fade Magic Moves in Keynote itself (0 moving objects)
        allowed_crossfade_mm = {("jewel-talk-2024", "s43"), ("jewel-talk-2024", "s47")}

        total_chains = 0
        for deck_id, expected_mm in expected_mm_counts.items():
            slides = self.docs[deck_id]["slides"]
            mm_count = 0
            in_chain = False
            for i, slide in enumerate(slides):
                is_mm = (slide.get("transition") or {}).get("type") == "magicMove"
                if is_mm:
                    mm_count += 1
                    if not in_chain:
                        total_chains += 1
                        in_chain = True
                    nxt = slides[i + 1]
                    root_a = {n["id"]: n for n in slide.get("nodes") or []}
                    root_b = {n["id"]: n for n in nxt.get("nodes") or []}
                    shared = set(root_a.keys()) & set(root_b.keys())
                    if (deck_id, slide["id"]) not in allowed_crossfade_mm:
                        self.assertGreater(
                            len(shared),
                            0,
                            f"{deck_id} Magic Move {slide['id']}->{nxt['id']} has 0 shared root node IDs",
                        )
                    # Every matched Magic Move node must have explicit x, y, w, h
                    for sid in shared:
                        for side, n in ((slide["id"], root_a[sid]), (nxt["id"], root_b[sid])):
                            fr = n.get("frame") or {}
                            for k in ("x", "y", "w", "h"):
                                self.assertIn(k, fr, f"{deck_id}/{side}/{sid} missing frame.{k} for Magic Move")
                else:
                    in_chain = False
            self.assertEqual(mm_count, expected_mm, f"{deck_id} magicMove count mismatch")
        self.assertEqual(total_chains, 28, "Expected exactly 28 Magic Move chains across all 3 talks")

    def test_key_hero_slides_and_choreography_spot_checks(self) -> None:
        # Shaders S01: character dissolve on 'awesome', sparkle + after chain, custom Apple cubic-bezier
        sh_slides = {s["id"]: s for s in self.docs["shaders-quickie-2025"]["slides"]}
        s01_nodes = {n["id"]: n for n in sh_slides["s01"]["nodes"]}
        self.assertEqual(sh_slides["s01"]["background"], "#594548")
        self.assertEqual(s01_nodes["awesome"]["builds"][0]["by"], "character")
        self.assertEqual(s01_nodes["awesome"]["builds"][0]["after"], "shaders-are")
        self.assertEqual(s01_nodes["rect-1"]["builds"][0]["effect"], "sparkle")
        self.assertIn("bezier", s01_nodes["shaders-are"]["builds"][0]["curve"])

        # Shaders S39, S42, S44: distinct BEFORE -> AFTER SDF specimens
        for sid, before_ref, after_ref in [
            ("s39", "shaders.sdfRawDistance", "shaders.sdfStepCircle"),
            ("s42", "shaders.sdfStepCircle", "shaders.sdfCsgSubtract"),
            ("s44", "shaders.sdfCsgSubtract", "shaders.sdfCsgUnionSubtract"),
        ]:
            specs = [n["specimen"]["ref"] for n in _walk_nodes(sh_slides[sid]["nodes"]) if "specimen" in n]
            self.assertEqual(specs, [before_ref, after_ref], f"Shaders {sid} SDF before/after mismatch")

        # Shaders S45 & S46: plaid-starfield backdrop is first node & shared across Magic Move + qr-code.svg
        self.assertEqual(sh_slides["s45"]["nodes"][0]["id"], "plaid-starfield")
        self.assertEqual(sh_slides["s46"]["nodes"][0]["id"], "plaid-starfield")
        self.assertTrue(any(n.get("image", {}).get("src") == "qr-code.svg" for n in sh_slides["s46"]["nodes"]))
        self.assertEqual(sh_slides["s44"]["transition"]["type"], "fadeThroughColor")
        self.assertEqual(sh_slides["s47"]["transition"]["type"], "iris")

        # Jewel S01, S21-S30, S31, S73, S74
        jw_slides = {s["id"]: s for s in self.docs["jewel-talk-2024"]["slides"]}
        self.assertEqual(jw_slides["s01"]["background"], "#EF5350")
        self.assertEqual(jw_slides["s01"]["beats"][-1]["autoAdvanceMs"], 100)
        for k in range(21, 31):
            sid = f"s{k:02d}"
            self.assertTrue(
                any(n["id"] == "logo-gemini-lockup" and n.get("image", {}).get("src") == "gemini-lockup.svg"
                    for n in jw_slides[sid]["nodes"]),
                f"Jewel {sid} missing logo-gemini-lockup",
            )
        s31_specs = {n["id"]: n["specimen"] for n in jw_slides["s31"]["nodes"] if "specimen" in n}
        self.assertEqual(s31_specs["specimen-confetti-right"].get("params"), {"mirrored": True})
        s73_imgs = sorted(n["image"]["src"] for n in jw_slides["s73"]["nodes"] if "image" in n)
        self.assertEqual(s73_imgs, ["speaker-avatar-1.png", "speaker-avatar-2.png", "speaker-avatar-3.png"])
        self.assertTrue(any("office hours!" in _plain_text(n) for n in jw_slides["s74"]["nodes"]))

        # NJPT S057 (👨‍🍳 + 🤌 merged move+rotation), S063 (XKCD), S075 (jewel-logo.svg), S090 (👻 jiggle), S093 (curved path)
        nj_slides = {s["id"]: s for s in self.docs["not-just-plain-text-2024"]["slides"]}
        s57_texts = [_plain_text(n) for n in nj_slides["s057"]["nodes"] if "text" in n]
        self.assertIn("👨‍🍳", s57_texts)
        self.assertIn("🤌", s57_texts)
        hand_node = next(n for n in nj_slides["s057"]["nodes"] if _plain_text(n) == "🤌")
        self.assertTrue(
            any(b["kind"] == "change" and "rotation" in (b.get("set") or {}) and "frame" in (b.get("set") or {})
                for b in hand_node.get("builds") or [])
        )
        self.assertTrue(any(n.get("image", {}).get("src") == "xkcd-standards.png" for n in nj_slides["s063"]["nodes"]))
        self.assertTrue(any(n.get("image", {}).get("src") == "jewel-logo.svg" for n in nj_slides["s075"]["nodes"]))
        self.assertEqual(nj_slides["s090"]["beats"][0].get("autoAdvanceMs"), 0)
        ghost = next(n for n in nj_slides["s090"]["nodes"] if _plain_text(n) == "👻")
        self.assertEqual(ghost["builds"][0]["kind"], "emphasis")
        self.assertEqual(ghost["builds"][0]["effect"], "jiggle")
        # NJPT S093 curved SVG path endpoint matches frame delta to 0.05px
        curved_nodes = [
            (n, b)
            for n in nj_slides["s093"]["nodes"]
            for b in n.get("builds") or []
            if "path" in b
        ]
        self.assertGreaterEqual(len(curved_nodes), 1)
        cn, cb = curved_nodes[0]
        nums = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?", cb["path"])]
        dx = float(cb["set"]["frame"]["x"]) - float(cn["frame"]["x"])
        dy = float(cb["set"]["frame"]["y"]) - float(cn["frame"]["y"])
        self.assertAlmostEqual(nums[-2], dx, delta=0.05)
        self.assertAlmostEqual(nums[-1], dy, delta=0.05)

    def test_parity_regressions_across_all_three_talks(self) -> None:
        # 1. Document-unit lineHeight (never raw < 6.0 multiplier)
        for deck_id, doc in self.docs.items():
            for slide in doc["slides"]:
                for node in _walk_nodes(slide.get("nodes") or []):
                    if "text" in node and "lineHeight" in node["text"]:
                        lh = float(node["text"]["lineHeight"])
                        self.assertGreaterEqual(
                            lh,
                            6.0,
                            f"{deck_id}/{slide['id']}/{node['id']} has raw multiplier lineHeight={lh}",
                        )

        # 2. Shaders Quickie 2025 fixes
        sh_slides = {s["id"]: s for s in self.docs["shaders-quickie-2025"]["slides"]}
        s02_nodes = {n["id"]: n for n in _walk_nodes(sh_slides["s02"]["nodes"])}
        self.assertEqual(_plain_text(s02_nodes["what-are"]).strip(), "what are")
        self.assertEqual(_plain_text(s02_nodes["shaders"]).strip(), "shaders")
        self.assertEqual(_plain_text(s02_nodes["question-mark"]).strip(), "?")
        self.assertGreaterEqual(float(s02_nodes["question-mark"]["frame"]["x"]), 1360.0)
        for sid in ("s14", "s15", "s16", "s17", "s18"):
            specs = [n for n in _walk_nodes(sh_slides[sid]["nodes"]) if n.get("specimen", {}).get("ref") == "shaders.colorEvolution"]
            self.assertEqual(len(specs), 1, f"Shaders {sid} expected 1 colorEvolution specimen")
            self.assertEqual(specs[0]["specimen"]["params"].get("a"), 0, f"Shaders {sid} half4(0.0) must pass a=0")

        kotlin_svg = (DECKS_DIR / "shaders-quickie-2025.assets" / "kotlin-logo.svg").read_text(encoding="utf-8")
        self.assertNotIn("<style", kotlin_svg)
        self.assertIn('fill="url(#', kotlin_svg)

        s09_nodes = {n["id"]: n for n in _walk_nodes(sh_slides["s09"]["nodes"])}
        self.assertLess(float(s09_nodes["shower-label"]["frame"]["x"]), 300.0)
        for sid in ("s10", "s11", "s12"):
            n_map = {n["id"]: n for n in _walk_nodes(sh_slides[sid]["nodes"])}
            self.assertEqual(n_map["glsl-label"]["text"].get("align"), "center")
            self.assertEqual(n_map["glsl-label"].get("align"), "center")
            self.assertNotIn("frame", n_map["glsl-label"])

        s28_nodes = {n["id"]: n for n in _walk_nodes(sh_slides["s28"]["nodes"])}
        s28_code = next((n["code"] for n in s28_nodes.values() if "code" in n), None)
        if s28_code is not None:
            self.assertIn("val brush", s28_code.get("value", ""))
            self.assertIn("drawBehind", s28_code.get("value", ""))
        else:
            self.assertTrue(any(_plain_text(n).strip() == "val" for n in s28_nodes.values()), "Shaders s28 missing 'val'")
            self.assertNotEqual(s28_nodes["drawbehind"]["text"].get("align"), "center")
        s35_texts = [
            _plain_text(n) if "text" in n else n["code"].get("value", "")
            for n in _walk_nodes(sh_slides["s35"]["nodes"])
            if "text" in n or "code" in n
        ]
        self.assertTrue(any(");" in t for t in s35_texts), "Shaders s35 missing ');'")
        for sid in ("s45", "s46"):
            url_node = next(n for n in _walk_nodes(sh_slides[sid]["nodes"]) if "wlBfD1" in _plain_text(n))
            self.assertGreaterEqual(float(url_node["frame"]["w"]), 530.0, f"Shaders {sid} URL pill too narrow for wlBfD1")

        # 3. Jewel Talk 2024 fixes
        jw_slides = {s["id"]: s for s in self.docs["jewel-talk-2024"]["slides"]}
        s03_map = {n["id"]: n for n in jw_slides["s03"]["nodes"]}
        for pid in ("portrait-chris", "portrait-seb"):
            self.assertIn(pid, s03_map)
            self.assertTrue(s03_map[pid]["stack"].get("clip"))
            self.assertEqual(
                s03_map[pid]["stack"].get("surface", {}).get("fill"),
                "#E3F272",
                f"Jewel s03/{pid} missing #E3F272 backing card",
            )
            self.assertEqual(s03_map[pid]["stack"].get("surface", {}).get("cornerRadius"), 29)
        for sid in ("s06", "s07", "s08"):
            self.assertTrue(
                any("Better UX" in _plain_text(n) for n in _walk_nodes(jw_slides[sid]["nodes"])),
                f"Jewel {sid} missing 'Better UX' footer",
            )
        s06_vel = next(n for n in _walk_nodes(jw_slides["s06"]["nodes"]) if "Velocity to produce" in _plain_text(n))
        for run in s06_vel["text"]["value"]:
            if run.get("font") == "Noto Emoji":
                self.assertFalse(run["text"].endswith(" "), "Jewel s06 space after emoji must not use Noto Emoji")
        s09_map = {n["id"]: n for n in jw_slides["s09"]["nodes"]}
        for qid in ("node-2", "node-3", "node-4"):
            self.assertEqual(s09_map[qid].get("opacity"), 0, f"Jewel s09/{qid} must start hidden at beat 0")
        for sid in ("s10", "s11"):
            ids = [n["id"] for n in jw_slides[sid]["nodes"]]
            s_map = {n["id"]: n for n in jw_slides[sid]["nodes"]}
            four_three = next(n for n in jw_slides[sid]["nodes"] if _plain_text(n) == "4\n3")
            # Odometer masks rect-3/rect-4 sit above 4-3 in z-order; 4-3 uses document-unit lineHeight (~72.64)
            # so the active digit sits cleanly inside the y=330..382 window while the inactive digit is masked.
            self.assertLess(ids.index(four_three["id"]), ids.index("rect-3"))
            self.assertGreater(float(four_three["text"]["lineHeight"]), 60.0)
            self.assertGreater(float(s_map["droidcon-london-202"]["frame"]["w"]), 850.0)
        s19_nodes = _walk_nodes(jw_slides["s19"]["nodes"])
        self.assertTrue(any("🐞" in _plain_text(n) for n in s19_nodes), "Jewel s19 missing 🐞 emoji")
        self.assertFalse(any(n["id"] == "rect-6" and n.get("shape", {}).get("fill") == "#000000" for n in s19_nodes))
        s30_dimmed = [n for n in jw_slides["s30"]["nodes"] if n.get("opacity") == 0.25]
        self.assertGreaterEqual(len(s30_dimmed), 5, "Jewel s30 background tiled cards must be dimmed to opacity 0.25")
        s36_md = next(n for n in _walk_nodes(jw_slides["s36"]["nodes"]) if n.get("specimen", {}).get("ref") == "jewel.markdownStandaloneSample")
        self.assertEqual(s36_md.get("interactive"), "keyboard")
        s45_map = {n["id"]: n for n in jw_slides["s45"]["nodes"]}
        self.assertEqual(_plain_text(s45_map["easier-to-learn"]), "Easier to learn")
        s64_comp = next(n for n in _walk_nodes(jw_slides["s64"]["nodes"]) if n["id"] == "specimen-cohesion-comparison")
        self.assertEqual(s64_comp["specimen"].get("ref"), "jewel.cohesionIdeShowcase")
        self.assertEqual(s64_comp["specimen"].get("params"), {"panel": "comparison"})
        self.assertEqual(jw_slides["s56"].get("transition", {}).get("type"), "push")

        # 4. Not Just Plain Text 2024 fixes
        nj_slides = {s["id"]: s for s in self.docs["not-just-plain-text-2024"]["slides"]}
        s19_map = {n["id"]: n for n in _walk_nodes(nj_slides["s019"]["nodes"])}
        if "frame" in s19_map.get("hacks", {}) and "frame" in s19_map.get("if-you-guessed", {}):
            self.assertGreaterEqual(
                float(s19_map["hacks"]["frame"]["x"]) - float(s19_map["if-you-guessed"]["frame"]["x"]),
                655.0,
            )
        else:
            self.assertIn("if-you-guessed-row", s19_map)
            self.assertGreaterEqual(float(s19_map["if-you-guessed-row"]["row"]["gap"]), 20.0)
        s01_texts = [_plain_text(n).strip() for n in _walk_nodes(nj_slides["s001"]["nodes"]) if "text" in n]
        self.assertIn("NOT", s01_texts)
        self.assertIn("JUST", s01_texts)
        self.assertNotIn("NOTJUST", s01_texts)
        s46_pill = next(n for n in _walk_nodes(nj_slides["s046"]["nodes"]) if "Intuitive expectations" in _plain_text(n))
        self.assertEqual(s46_pill.get("align"), "center")
        self.assertNotIn("frame", s46_pill)
        s21_nodes = _walk_nodes(nj_slides["s021"]["nodes"])
        self.assertTrue(
            any("shape" in n and n["shape"].get("fill") == "#D9A404" and abs(float(n["frame"]["x"]) - 1324.0) < 5.0 for n in s21_nodes),
            "NJPT s021 missing 3rd golden triangle shape at x~1324",
        )
        self.assertTrue(
            any("codewiththeitalians" in _plain_text(n) for n in s21_nodes),
            "NJPT s021 missing continuous 'codewiththeitalians' URL",
        )
        self.assertTrue(
            any(_plain_text(n).strip() == "NOT" for n in _walk_nodes(nj_slides["s024"]["nodes"])),
            "NJPT s024 missing 'NOT' node",
        )
        s96_texts = [_plain_text(n) for n in _walk_nodes(nj_slides["s096"]["nodes"]) if "text" in n]
        self.assertNotIn("Image\n", s96_texts)
        self.assertEqual(s96_texts.count("render("), 1)
        s125_md = next(n for n in _walk_nodes(nj_slides["s125"]["nodes"]) if n.get("specimen", {}).get("ref") == "njpt.jewelMarkdownSplitDemo")
        self.assertEqual(s125_md.get("interactive"), "keyboard")
        for sid in ("s133", "s134", "s135"):
            self.assertTrue(
                any("Especially for i18n and server-driven UI" in _plain_text(n) for n in _walk_nodes(nj_slides[sid]["nodes"])),
                f"NJPT {sid} missing 'Especially for i18n and server-driven UI' subtitle",
            )

        # 5. Geometry, line-head, and rounded-rect invariants across all talks
        sh_slides = {s["id"]: s for s in self.docs["shaders-quickie-2025"]["slides"]}
        s16_map = {n["id"]: n for n in _walk_nodes(sh_slides["s16"]["nodes"])}
        if "half4-main-float2-fragcoord" in s16_map:
            self.assertAlmostEqual(float(s16_map["half4-main-float2-fragcoord"]["frame"]["y"]), 480.09, places=2)
        else:
            for nid in ("half", "4", "float", "2"):
                self.assertAlmostEqual(float(s16_map[nid]["frame"]["y"]), 321.92, places=2)
            self.assertGreaterEqual(float(s16_map["half"]["frame"]["w"]), 106.0)
            self.assertGreaterEqual(float(s16_map["float"]["frame"]["w"]), 132.0)
        self.assertEqual(s16_map["line-7"]["shape"].get("heads"), {"end": "filled"})
        self.assertIsNone(s16_map["line-7"]["shape"].get("fill"))

        for sid in ("s22", "s23"):
            s_map = {n["id"]: n for n in _walk_nodes(sh_slides[sid]["nodes"])}
            self.assertEqual(s_map["path-1"]["shape"]["geometry"], "rect")
            self.assertGreater(float(s_map["path-1"]["shape"]["cornerRadius"]), 10.0)

        # 6. Cross-slide shape fill, Magic Move ID, and single-contour path invariants
        jw_s18 = {n["id"]: n for n in jw_slides["s18"]["nodes"]}
        jw_s19 = {n["id"]: n for n in jw_slides["s19"]["nodes"]}
        for pid in ("path-4", "path-5", "path-6"):
            self.assertEqual(jw_s18[pid]["shape"]["fill"], jw_s19[pid]["shape"]["fill"])
            self.assertEqual(jw_s18[pid]["frame"]["w"], jw_s19[pid]["frame"]["w"])
        s28_ids = {n["id"] for n in sh_slides["s28"]["nodes"]}
        s29_ids = {n["id"] for n in sh_slides["s29"]["nodes"]}
        if any("code" in n for n in sh_slides["s28"]["nodes"]):
            self.assertIn("val-brush-remember-compileds", s28_ids)
        else:
            self.assertIn("open-paren", s28_ids & s29_ids)
        for bad in ("node", "brush", "node-5", "compiledshader-setfloatunifo"):
            self.assertNotIn(bad, s28_ids & s29_ids)
        s089_line5 = next(n for n in nj_slides["s089"]["nodes"] if n["id"] == "line-5")
        self.assertEqual(s089_line5["shape"]["geometry"]["path"].count("M "), 1)


class VisualFixesConverterUnitTest(unittest.TestCase):
    def test_node_present_at_beat0_false_for_beat0_enter(self) -> None:
        self.assertFalse(_node_present_at_beat0({"id": "a", "builds": [{"kind": "enter", "beat": 0}]}))
        self.assertFalse(_node_present_at_beat0({"id": "a", "builds": [{"kind": "enter", "beat": 1}]}))
        self.assertTrue(_node_present_at_beat0({"id": "a", "builds": []}))

    def test_unify_magic_move_ids_prevents_generic_collisions_and_text_steals(self) -> None:
        slides = [
            {
                "id": "s0",
                "transition": {"type": "magicMove", "durationMs": 500},
                "nodes": [
                    {"id": "node", "frame": {"x": 340, "y": 340, "w": 60, "h": 40}, "text": {"value": "//"}},
                    {"id": "node-9", "frame": {"x": 396, "y": 460, "w": 40, "h": 40}, "text": {"value": "("}},
                    {"id": "brush", "frame": {"x": 341, "y": 380, "w": 136, "h": 48}, "text": {"value": "brush"}},
                ],
            },
            {
                "id": "s1",
                "nodes": [
                    {"id": "node", "frame": {"x": 595, "y": 460, "w": 40, "h": 40}, "text": {"value": "("}},
                    {"id": "brush", "frame": {"x": 341, "y": 460, "w": 335, "h": 48}, "text": {"value": "AnimatedCanvas"}},
                    {"id": "node-other", "frame": {"x": 340, "y": 340, "w": 50, "h": 40}, "text": {"value": "val"}},
                ],
            },
        ]
        # Trajectory pairs "(" (s0/node-9) -> "(" (s1/node), with mismatched close-bounds text for AnimatedCanvas
        trajs = {
            0: [
                {
                    "fromBounds": (396, 460, 40, 40),
                    "toBounds": (595, 460, 40, 40),
                    "fromText": "(",
                    "toText": "(",
                },
                {
                    "fromBounds": (341, 380, 136, 48),
                    "toBounds": (341, 460, 335, 48),
                    "fromText": "brush",
                    "toText": "val brush = AnimatedCanvas",
                },
            ]
        }
        _unify_magic_move_ids(slides, trajs)
        s0_by_text = {n["text"]["value"]: n["id"] for n in slides[0]["nodes"]}
        s1_by_text = {n["text"]["value"]: n["id"] for n in slides[1]["nodes"]}
        self.assertEqual(s0_by_text["("], s1_by_text["("])
        self.assertNotEqual(s0_by_text["//"], s1_by_text["("])
        self.assertNotEqual(s0_by_text["brush"], s1_by_text["AnimatedCanvas"])

    def test_merge_shapes_deduplicates_identical_subpaths(self) -> None:
        dup_path = "M 10 0 L 90 0 L 90 40 L 10 40 Z"
        sp = _merge_shapes_to_single_shape(
            [
                {"x": 0, "y": 0, "svgPath": dup_path, "fillHex": "#F2F2F0"},
                {"x": 0, "y": 0, "svgPath": dup_path, "strokeHex": "#626972", "strokeWidth": 9},
            ],
            box_x=0.0,
            box_y=0.0,
        )
        assert sp is not None
        self.assertEqual(sp["geometry"]["path"].count("M "), 1)

    def test_try_convert_arrow_shape_payload_end_and_start_heads(self) -> None:
        end_arrow = {
            "geometry": {"path": "M 0 10 L 80 10 M 78 2 L 94 10 L 78 18 Z"},
            "fill": "#FF6B35",
            "stroke": {"color": "#FF6B35", "width": 4, "dash": [0, 12]},
        }
        self.assertTrue(try_convert_arrow_shape_payload(end_arrow))
        self.assertNotIn("fill", end_arrow)
        self.assertEqual(end_arrow["heads"], {"end": "filled"})
        self.assertEqual(end_arrow["stroke"].get("cap"), "round")
        self.assertTrue(end_arrow["geometry"]["path"].startswith("M 0 10 L "))
        self.assertNotIn("Z", end_arrow["geometry"]["path"])

        start_arrow = {
            "geometry": {"path": "M 20 10 L 100 10 M 22 2 L 6 10 L 22 18 Z"},
            "fill": "#FF6B35",
            "stroke": {"color": "#FF6B35", "width": 4},
        }
        self.assertTrue(try_convert_arrow_shape_payload(start_arrow))
        self.assertEqual(start_arrow["heads"], {"start": "filled"})
        self.assertNotIn("fill", start_arrow)

    def test_classify_svg_path_detects_keynote_squircle_rounded_rect(self) -> None:
        cmds = [
            ("M", [19.51, 0.0]),
            ("L", [882.94, 0.0]),
            ("C", [888.66, 0.0, 892.1, 0.0, 894.39, 0.96]),
            ("C", [897.69, 2.16, 900.29, 4.76, 901.49, 8.06]),
            ("C", [902.45, 10.35, 902.45, 13.78, 902.45, 19.51]),
            ("L", [902.45, 142.49]),
            ("C", [902.45, 148.22, 902.45, 151.65, 901.49, 153.94]),
            ("C", [900.29, 157.24, 897.69, 159.84, 894.39, 161.04]),
            ("C", [892.1, 162.0, 888.66, 162.0, 882.94, 162.0]),
            ("L", [19.51, 162.0]),
            ("C", [13.78, 162.0, 10.35, 162.0, 8.06, 161.04]),
            ("C", [4.76, 159.84, 2.16, 157.24, 0.96, 153.94]),
            ("C", [0.0, 151.65, 0.0, 148.22, 0.0, 142.49]),
            ("L", [0.0, 19.51]),
            ("C", [0.0, 13.78, 0.0, 10.35, 0.96, 8.06]),
            ("C", [2.16, 4.76, 4.76, 2.16, 8.06, 0.96]),
            ("C", [10.35, 0.0, 13.78, 0.0, 19.51, 0.0]),
            ("Z", []),
        ]
        kind, radius, _ = _classify_svg_path(cmds, 0.0, 0.0, 902.45, 162.0)
        self.assertEqual(kind, "rect")
        self.assertIsNotNone(radius)
        self.assertAlmostEqual(float(radius), 12.8, places=1)

    def test_pure_shape_slot_uses_exact_path_bbox_for_single_and_surviving_rect(self) -> None:
        alloc = lambda base: base  # noqa: E731
        rect_sh = {
            "kind": "rect",
            "x": 1623.0,
            "y": 957.3,
            "width": 231.48,
            "height": 57.4,
            "cornerRadius": 28.7,
            "fillHex": "#E3F272",
        }
        # 1. Single rect shape inside padded texture leaf bounds (1622, 956, 235, 60)
        single_node = _build_pure_shape_slot_node(
            slot_shapes=[rect_sh],
            slot_gradients=[],
            slot_i=3,
            min_x=1622.0,
            min_y=956.0,
            bw=235.0,
            bh=60.0,
            alloc_id=alloc,
        )
        assert single_node is not None
        self.assertEqual(single_node["frame"], {"x": 1623, "y": 957.3, "w": 231.48, "h": 57.4})
        self.assertEqual(single_node["shape"]["geometry"], "rect")
        self.assertEqual(single_node["shape"]["cornerRadius"], 28.7)

        # 2. Multi-shape slot where a transparent/no-stroke helper drops out leaving 1 surviving rect
        invisible_sh = {"kind": "rect", "x": 1622.0, "y": 956.0, "width": 235.0, "height": 60.0}
        surviving_node = _build_pure_shape_slot_node(
            slot_shapes=[invisible_sh, rect_sh],
            slot_gradients=[],
            slot_i=4,
            min_x=1622.0,
            min_y=956.0,
            bw=235.0,
            bh=60.0,
            alloc_id=alloc,
        )
        assert surviving_node is not None
        self.assertEqual(surviving_node["frame"], {"x": 1623, "y": 957.3, "w": 231.48, "h": 57.4})
        self.assertEqual(surviving_node["shape"]["geometry"], "rect")

    def test_s39_first_styling_and_exact_rect_extents(self) -> None:
        docs = {}
        for did in ("jewel-talk-2024", "not-just-plain-text-2024", "shaders-quickie-2025"):
            docs[did] = json.loads((DECKS_DIR / f"{did}.deck.json").read_text(encoding="utf-8"))

        # Across all 3 decks, every pill/capsule rect (cornerRadius >= 0.45 * min(w, h)) must match min(w, h)/2
        for did, doc in docs.items():
            for slide in doc["slides"]:
                for node in _walk_nodes(slide.get("nodes") or []):
                    sp = node.get("shape") or {}
                    fr = node.get("frame") or {}
                    if sp.get("geometry") == "rect" and sp.get("cornerRadius") and "w" in fr and "h" in fr:
                        cr = float(sp["cornerRadius"])
                        min_dim = min(float(fr["w"]), float(fr["h"]))
                        if min_dim > 0.0 and cr >= 0.45 * min_dim:
                            self.assertAlmostEqual(
                                cr,
                                min_dim / 2.0,
                                delta=0.5,
                                msg=f"{did}/{slide['id']}/{node['id']}: pill cornerRadius={cr} != min(w,h)/2 ({fr})",
                            )

        # Verify all 119 normalized Path->rect exact subpixel extents against golden fixture
        fixture_path = TOOLS_DIR / "fixtures" / "exact_rect_frames.json"
        golden_entries = json.loads(fixture_path.read_text(encoding="utf-8"))
        self.assertEqual(len(golden_entries), 119)
        slides_by_deck = {did: {s["id"]: s for s in doc["slides"]} for did, doc in docs.items()}
        for entry in golden_entries:
            slide = slides_by_deck[entry["deck"]][entry["slide"]]
            node = next(n for n in _walk_nodes(slide.get("nodes") or []) if n["id"] == entry["nodeId"])
            self.assertEqual(
                [node["frame"]["w"], node["frame"]["h"]],
                [entry["w"], entry["h"]],
                f"{entry['deck']}/{entry['slide']}/{entry['nodeId']} extent mismatch",
            )

        jw_slides = {s["id"]: s for s in docs["jewel-talk-2024"]["slides"]}
        s39_first = next(n for n in jw_slides["s39"]["nodes"] if n["id"] == "first")
        self.assertEqual(s39_first["text"].get("weight"), 600)
        self.assertEqual(s39_first["text"].get("color"), "#A74EBF")

        s16_p3 = next(n for n in jw_slides["s16"]["nodes"] if n["id"] == "path-3")
        self.assertEqual(s16_p3["frame"], {"x": 1622, "y": 956, "w": 231.48, "h": 57.4})
        self.assertEqual(s16_p3["shape"].get("cornerRadius"), 28.7)
        s72_p1 = next(n for n in jw_slides["s72"]["nodes"] if n["id"] == "path-1")
        self.assertEqual(s72_p1["frame"], {"x": 653, "y": 567, "w": 610.38, "h": 100})

        nj_slides = {s["id"]: s for s in docs["not-just-plain-text-2024"]["slides"]}
        nj_s17_code = next(n for n in nj_slides["s017"]["nodes"] if n["id"] == "overflow-wrap-break-word")
        self.assertEqual(nj_s17_code["frame"], {"x": 651.2, "y": 805, "w": 656, "h": 110})
        nj_s89_p2 = next(n for n in nj_slides["s089"]["nodes"] if n["id"] == "path-2")
        self.assertEqual(nj_s89_p2["frame"], {"x": 572, "y": 303, "w": 422.16, "h": 427.1})

        sh_slides = {s["id"]: s for s in docs["shaders-quickie-2025"]["slides"]}
        sh_s14_p1 = next(n for n in sh_slides["s14"]["nodes"] if n["id"] == "path-1")
        self.assertEqual(sh_s14_p1["frame"], {"x": 225, "y": 459, "w": 902.45, "h": 159.12})
        sh_s28_p1 = next(n for n in sh_slides["s28"]["nodes"] if n["id"] == "path-1")
        self.assertEqual(sh_s28_p1["frame"], {"x": 303, "y": 308, "w": 1310.46, "h": 462})


class RunSingleAtomicOutputTests(unittest.TestCase):
    def _seed_existing_outputs(self, root: Path) -> Tuple[Path, Path]:
        out_json = root / "sample.deck.json"
        assets_dir = root / "sample.assets"
        assets_dir.mkdir(parents=True, exist_ok=True)
        out_json.write_text('{"sentinel": true}\n', encoding="utf-8")
        (assets_dir / "keep.png").write_bytes(b"existing-png-bytes")
        return out_json, assets_dir

    def test_missing_and_malformed_kpf_preserve_existing_json_and_assets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_json, assets_dir = self._seed_existing_outputs(root)

            with self.assertRaises(FileNotFoundError):
                kpf_to_deck_json.run_single(
                    kpf_dir=root / "does-not-exist",
                    deck_id="sample",
                    title="Sample",
                    talk_id="generic",
                    out_json=out_json,
                    assets_dir=assets_dir,
                )

            self.assertEqual(out_json.read_text(encoding="utf-8"), '{"sentinel": true}\n')
            self.assertEqual((assets_dir / "keep.png").read_bytes(), b"existing-png-bytes")

            bad_kpf = root / "bad-kpf"
            bad_kpf.mkdir()
            (bad_kpf / "header.json").write_text("{not valid json", encoding="utf-8")

            with self.assertRaises(json.JSONDecodeError):
                kpf_to_deck_json.run_single(
                    kpf_dir=bad_kpf,
                    deck_id="sample",
                    title="Sample",
                    talk_id="generic",
                    out_json=out_json,
                    assets_dir=assets_dir,
                )

            self.assertEqual(out_json.read_text(encoding="utf-8"), '{"sentinel": true}\n')
            self.assertEqual((assets_dir / "keep.png").read_bytes(), b"existing-png-bytes")

    def test_validation_failure_preserves_existing_json_and_assets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_json, assets_dir = self._seed_existing_outputs(root)
            invalid_doc = _minimal_valid_doc()
            invalid_doc["slides"][0]["nodes"].append({
                "id": "missing-img",
                "frame": {"x": 0, "y": 0, "w": 100, "h": 100},
                "image": {"src": "missing.png", "fit": "contain"},
            })

            with patch.object(kpf_to_deck_json, "convert_kpf_to_deck_doc", return_value=invalid_doc):
                ok, _ = kpf_to_deck_json.run_single(
                    kpf_dir=root / "unused",
                    deck_id="sample",
                    title="Sample",
                    talk_id="generic",
                    out_json=out_json,
                    assets_dir=assets_dir,
                )

            self.assertFalse(ok)
            self.assertEqual(out_json.read_text(encoding="utf-8"), '{"sentinel": true}\n')
            self.assertEqual((assets_dir / "keep.png").read_bytes(), b"existing-png-bytes")

    def test_successful_conversion_replaces_outputs_and_prunes_stale_assets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_json, assets_dir = self._seed_existing_outputs(root)
            valid_doc = _minimal_valid_doc()
            valid_doc["slides"][0]["nodes"].append({
                "id": "hero-img",
                "frame": {"x": 0, "y": 0, "w": 100, "h": 100},
                "image": {"src": "fresh.png", "fit": "contain"},
            })

            def fake_convert(**kwargs: Any) -> Dict[str, Any]:
                staged_dir: Path = kwargs["assets_out_dir"]
                staged_dir.mkdir(parents=True, exist_ok=True)
                (staged_dir / "fresh.png").write_bytes(b"fresh-png")
                (staged_dir / "orphan.png").write_bytes(b"orphan-png")
                return valid_doc

            with patch.object(kpf_to_deck_json, "convert_kpf_to_deck_doc", side_effect=fake_convert):
                ok, _ = kpf_to_deck_json.run_single(
                    kpf_dir=root / "unused",
                    deck_id="sample",
                    title="Sample",
                    talk_id="generic",
                    out_json=out_json,
                    assets_dir=assets_dir,
                )

            self.assertTrue(ok)
            self.assertEqual(out_json.read_text(encoding="utf-8"), canonical_dumps(valid_doc))
            self.assertEqual(sorted(p.name for p in assets_dir.iterdir()), ["fresh.png"])
            self.assertEqual((assets_dir / "fresh.png").read_bytes(), b"fresh-png")
            # No leftover temp files or staging dirs next to targets
            self.assertEqual(sorted(p.name for p in root.iterdir()), ["sample.assets", "sample.deck.json"])

            # Converting to a deck with zero image assets cleans up stale assets_dir
            no_img_doc = _minimal_valid_doc()
            with patch.object(kpf_to_deck_json, "convert_kpf_to_deck_doc", return_value=no_img_doc):
                ok_empty, _ = kpf_to_deck_json.run_single(
                    kpf_dir=root / "unused",
                    deck_id="sample",
                    title="Sample",
                    talk_id="generic",
                    out_json=out_json,
                    assets_dir=assets_dir,
                )

            self.assertTrue(ok_empty)
            self.assertFalse(assets_dir.exists())
            self.assertEqual(out_json.read_text(encoding="utf-8"), canonical_dumps(no_img_doc))

    def test_commit_failure_rolls_back_replaced_assets_and_cleans_temp_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_json, assets_dir = self._seed_existing_outputs(root)
            valid_doc = _minimal_valid_doc()
            valid_doc["slides"][0]["nodes"].append({
                "id": "hero-img",
                "frame": {"x": 0, "y": 0, "w": 100, "h": 100},
                "image": {"src": "fresh.png", "fit": "contain"},
            })

            def fake_convert(**kwargs: Any) -> Dict[str, Any]:
                staged_dir: Path = kwargs["assets_out_dir"]
                staged_dir.mkdir(parents=True, exist_ok=True)
                (staged_dir / "fresh.png").write_bytes(b"fresh-png")
                return valid_doc

            with (
                patch.object(kpf_to_deck_json, "convert_kpf_to_deck_doc", side_effect=fake_convert),
                patch.object(kpf_to_deck_json.os, "replace", side_effect=OSError("simulated replace failure")),
                self.assertRaises(OSError),
            ):
                kpf_to_deck_json.run_single(
                    kpf_dir=root / "unused",
                    deck_id="sample",
                    title="Sample",
                    talk_id="generic",
                    out_json=out_json,
                    assets_dir=assets_dir,
                )

            self.assertEqual(out_json.read_text(encoding="utf-8"), '{"sentinel": true}\n')
            self.assertEqual(sorted(p.name for p in assets_dir.iterdir()), ["keep.png"])
            self.assertEqual((assets_dir / "keep.png").read_bytes(), b"existing-png-bytes")
            self.assertEqual(sorted(p.name for p in root.iterdir()), ["sample.assets", "sample.deck.json"])

    def test_validate_only_fails_when_referenced_assets_dir_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            doc = _minimal_valid_doc()
            doc["slides"][0]["nodes"].append({
                "id": "hero-img",
                "frame": {"x": 0, "y": 0, "w": 100, "h": 100},
                "image": {"src": "missing.png", "fit": "contain"},
            })
            deck_path = root / "orphan.deck.json"
            deck_path.write_text(canonical_dumps(doc), encoding="utf-8")

            rc = kpf_to_deck_json.main(["--validate-only", str(deck_path)])
            self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
