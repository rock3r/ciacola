#!/usr/bin/env python3
"""
Apple Keynote KPF -> Ciacola `.deck.json` Converter CLI.

Pure Python 3 stdlib (zero external pip dependencies).
Converts a Keynote HTML (KPF) export + optional `.key` archive into a canonical
`<deckId>.deck.json` document and clean static `<deckId>.assets/` directory
with 100% live-code specimens (zero `.mov`/`.mp4`/`.gif` video playback).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from kpf_converter.canonical import canonical_dumps, validate_deck_document  # noqa: E402
from kpf_converter.converter import convert_kpf_to_deck_doc  # noqa: E402

DEFAULT_TALKS_DIR = Path(os.environ.get("CIACOLA_TALKS_DIR", str(Path.home() / "Downloads" / "Talks")))

CANONICAL_TALKS: List[Dict[str, Any]] = [
    {
        "talk": "shaders",
        "deck_id": "shaders-quickie-2025",
        "title": "Shaders Quickie",
        "kpf": DEFAULT_TALKS_DIR / "2025 — Shaders quickie" / "Shaders Quickie 2025" / "assets",
        "key": DEFAULT_TALKS_DIR / "2025 — Shaders quickie" / "Shaders Quickie 2025.key",
        "extra_svg_dir": DEFAULT_TALKS_DIR / "2025 — Shaders quickie",
    },
    {
        "talk": "jewel",
        "deck_id": "jewel-talk-2024",
        "title": "Project Sparkles: How Compose Is Changing Android Studio",
        "kpf": DEFAULT_TALKS_DIR / "2024 Jewel talk" / "Jewel talk 2024" / "assets",
        "key": DEFAULT_TALKS_DIR / "2024 Jewel talk" / "Jewel talk 2024.key",
        "extra_svg_dir": None,
    },
    {
        "talk": "njpt",
        "deck_id": "not-just-plain-text-2024",
        "title": "Not Just Plain Text: Rendering Markdown in Compose",
        "kpf": DEFAULT_TALKS_DIR / "2024 Markdown rendering" / "Not Just Plain Text" / "assets",
        "key": DEFAULT_TALKS_DIR / "2024 Markdown rendering" / "Not Just Plain Text.key",
        "extra_svg_dir": None,
    },
]


def summarize_deck(doc: Dict[str, Any]) -> Dict[str, int]:
    slides = doc.get("slides") or []
    beats = sum(len(s.get("beats") or []) for s in slides)
    node_count = 0
    specimen_count = 0
    image_count = 0
    build_count = 0
    mm_transitions = sum(1 for s in slides if (s.get("transition") or {}).get("type") == "magicMove")

    def walk(n: Dict[str, Any]) -> None:
        nonlocal node_count, specimen_count, image_count, build_count
        node_count += 1
        if "specimen" in n:
            specimen_count += 1
        if "image" in n:
            image_count += 1
        build_count += len(n.get("builds") or [])
        for pk in ("stack", "row", "column", "loupe"):
            if pk in n:
                for ch in n[pk].get("children") or []:
                    walk(ch)

    for s in slides:
        for n in s.get("nodes") or []:
            walk(n)

    return {
        "slides": len(slides),
        "beats": beats,
        "nodes": node_count,
        "builds": build_count,
        "specimens": specimen_count,
        "images": image_count,
        "magicMoveTransitions": mm_transitions,
    }


def _collect_referenced_images(doc: Dict[str, Any]) -> set[str]:
    refs: set[str] = set()

    def walk(n: Dict[str, Any]) -> None:
        if "image" in n and n["image"].get("src"):
            refs.add(n["image"]["src"])
        for pk in ("stack", "row", "column", "loupe"):
            if pk in n:
                for ch in n[pk].get("children") or []:
                    walk(ch)

    for s in doc.get("slides") or []:
        for n in s.get("nodes") or []:
            walk(n)
    return refs


def run_single(
    kpf_dir: Path,
    deck_id: str,
    title: str,
    talk_id: str,
    out_json: Path,
    assets_dir: Path,
    key_path: Optional[Path] = None,
    extra_svg_dir: Optional[Path] = None,
    notes_json: Optional[Path] = None,
    clean_assets: bool = True,
) -> Tuple[bool, Dict[str, int]]:
    if clean_assets and assets_dir.exists():
        shutil.rmtree(assets_dir)

    notes_map: Optional[Dict[int, str]] = None
    if notes_json and notes_json.exists():
        raw_notes = json.loads(notes_json.read_text(encoding="utf-8"))
        notes_map = {int(k): str(v) for k, v in raw_notes.items()}

    doc = convert_kpf_to_deck_doc(
        kpf_assets_dir=kpf_dir,
        deck_id=deck_id,
        title=title,
        talk_id=talk_id,
        assets_out_dir=assets_dir,
        key_path=key_path,
        extra_svg_dir=extra_svg_dir,
        notes_by_slide=notes_map,
    )

    # Prune any unreferenced asset files just in case
    if assets_dir.exists():
        used_imgs = _collect_referenced_images(doc)
        for fp in list(assets_dir.iterdir()):
            if fp.is_file() and fp.name not in used_imgs:
                fp.unlink()

    serialized = canonical_dumps(doc)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(serialized, encoding="utf-8", newline="\n")

    # Verify strict canonical round-trip byte-identity
    reparsed = json.loads(serialized)
    reserialized = canonical_dumps(reparsed)
    if reserialized != serialized:
        print(f"ERROR [{deck_id}]: canonical JSON round-trip mismatch!", file=sys.stderr)
        return (False, summarize_deck(doc))

    errors, warnings = validate_deck_document(reparsed, assets_dir=assets_dir if assets_dir.exists() else None)
    stats = summarize_deck(reparsed)
    asset_files = sorted(p.name for p in assets_dir.iterdir() if p.is_file()) if assets_dir.exists() else []
    print(
        f"[{deck_id}] slides={stats['slides']} beats={stats['beats']} nodes={stats['nodes']} "
        f"builds={stats['builds']} specimens={stats['specimens']} images={stats['images']} "
        f"mm={stats['magicMoveTransitions']} assets={len(asset_files)} "
        f"errors={len(errors)} warnings={len(warnings)}"
    )
    for w in warnings[:20]:
        print(f"  WARN: {w}")
    for e in errors[:30]:
        print(f"  ERR:  {e}", file=sys.stderr)
    if len(errors) > 30:
        print(f"  ... ({len(errors) - 30} more errors)", file=sys.stderr)

    return (len(errors) == 0, stats)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Convert Keynote KPF export to canonical .deck.json")
    parser.add_argument("--all", action="store_true", help="Convert all 3 canonical public talks into deck resources")
    parser.add_argument("--validate-only", type=Path, help="Validate an existing .deck.json file and exit")
    parser.add_argument("--kpf", type=Path, help="Path to KPF assets/ directory (containing header.json)")
    parser.add_argument("--key", type=Path, default=None, help="Optional path to source .key archive for SVG/PNG extraction")
    parser.add_argument("--extra-svg-dir", type=Path, default=None, help="Optional extra folder for SVG assets")
    parser.add_argument("--notes", type=Path, default=None, help="Optional JSON file mapping slide number -> speaker notes")
    parser.add_argument("--deck-id", type=str, help="Deck id (e.g. shaders-quickie-2025)")
    parser.add_argument("--title", type=str, help="Deck display title")
    parser.add_argument("--talk", type=str, default="generic", help="Talk profile (shaders|jewel|njpt|generic)")
    parser.add_argument("--out", type=Path, help="Output .deck.json path")
    parser.add_argument("--assets-dir", type=Path, help="Output <deckId>.assets directory")
    parser.add_argument(
        "--decks-root",
        type=Path,
        default=REPO_ROOT / "deck" / "src" / "desktopMain" / "resources" / "decks",
        help="Destination directory for --all mode",
    )
    args = parser.parse_args(argv)

    if args.validate_only:
        text = args.validate_only.read_text(encoding="utf-8")
        doc = json.loads(text)
        canonical = canonical_dumps(doc)
        if canonical != text:
            print(f"FAIL: {args.validate_only} is NOT canonical JSON", file=sys.stderr)
            return 1
        stem = args.validate_only.name.removesuffix(".deck.json")
        assets_dir = args.validate_only.parent / f"{stem}.assets"
        errors, warnings = validate_deck_document(doc, assets_dir=assets_dir if assets_dir.exists() else None)
        stats = summarize_deck(doc)
        print(
            f"[validate:{doc.get('id')}] slides={stats['slides']} beats={stats['beats']} "
            f"nodes={stats['nodes']} builds={stats['builds']} errors={len(errors)} warnings={len(warnings)}"
        )
        for w in warnings[:20]:
            print(f"  WARN: {w}")
        for e in errors[:30]:
            print(f"  ERR:  {e}", file=sys.stderr)
        return 0 if not errors else 1

    if args.all:
        ok_all = True
        for spec in CANONICAL_TALKS:
            out_json = args.decks_root / f"{spec['deck_id']}.deck.json"
            assets_dir = args.decks_root / f"{spec['deck_id']}.assets"
            ok, _ = run_single(
                kpf_dir=spec["kpf"],
                deck_id=spec["deck_id"],
                title=spec["title"],
                talk_id=spec["talk"],
                out_json=out_json,
                assets_dir=assets_dir,
                key_path=spec["key"],
                extra_svg_dir=spec["extra_svg_dir"],
            )
            ok_all = ok_all and ok
        return 0 if ok_all else 1

    if not args.kpf or not args.deck_id or not args.out:
        parser.error("Provide --all, --validate-only, or --kpf + --deck-id + --out")

    assets_dir = args.assets_dir or (args.out.parent / f"{args.deck_id}.assets")
    ok, _ = run_single(
        kpf_dir=args.kpf,
        deck_id=args.deck_id,
        title=args.title or args.deck_id,
        talk_id=args.talk,
        out_json=args.out,
        assets_dir=assets_dir,
        key_path=args.key,
        extra_svg_dir=args.extra_svg_dir,
        notes_json=args.notes,
    )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
