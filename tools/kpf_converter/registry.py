"""Font catalog, .key asset extraction, specimen registry, and theme/palette builders."""

from __future__ import annotations

import re
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple


# ---------------------------------------------------------------------------
# Bundled OFL-1.1 Font Catalog (exact paths matching deck/src/desktopMain/resources/fonts/)
# ---------------------------------------------------------------------------

INTER_WEIGHTS: List[Tuple[int, str]] = [
    (100, "fonts/Inter-Thin.ttf"),
    (200, "fonts/Inter-ExtraLight.ttf"),
    (300, "fonts/Inter-Light.ttf"),
    (400, "fonts/Inter-Regular.ttf"),
    (500, "fonts/Inter-Medium.ttf"),
    (600, "fonts/Inter-SemiBold.ttf"),
    (700, "fonts/Inter-Bold.ttf"),
    (800, "fonts/Inter-ExtraBold.ttf"),
    (900, "fonts/Inter-Black.ttf"),
]

JB_MONO_WEIGHTS: List[Tuple[int, str, bool]] = [
    (100, "fonts/JetBrainsMono-Thin.ttf", False),
    (100, "fonts/JetBrainsMono-ThinItalic.ttf", True),
    (200, "fonts/JetBrainsMono-ExtraLight.ttf", False),
    (200, "fonts/JetBrainsMono-ExtraLightItalic.ttf", True),
    (300, "fonts/JetBrainsMono-Light.ttf", False),
    (300, "fonts/JetBrainsMono-LightItalic.ttf", True),
    (400, "fonts/JetBrainsMono-Regular.ttf", False),
    (400, "fonts/JetBrainsMono-Italic.ttf", True),
    (500, "fonts/JetBrainsMono-Medium.ttf", False),
    (500, "fonts/JetBrainsMono-MediumItalic.ttf", True),
    (600, "fonts/JetBrainsMono-SemiBold.ttf", False),
    (600, "fonts/JetBrainsMono-SemiBoldItalic.ttf", True),
    (700, "fonts/JetBrainsMono-Bold.ttf", False),
    (700, "fonts/JetBrainsMono-BoldItalic.ttf", True),
    (800, "fonts/JetBrainsMono-ExtraBold.ttf", False),
    (800, "fonts/JetBrainsMono-ExtraBoldItalic.ttf", True),
]

BUNDLED_VARIABLE_FONTS: Dict[str, List[Dict[str, Any]]] = {
    "Hanken Grotesk": [
        {"family": "Hanken Grotesk", "src": "fonts/HankenGrotesk-VariableFont_wght.ttf", "variable": True},
        {"family": "Hanken Grotesk", "src": "fonts/HankenGrotesk-Italic-VariableFont_wght.ttf", "italic": True, "variable": True},
    ],
    "Noto Emoji": [
        {"family": "Noto Emoji", "src": "fonts/NotoEmoji-VariableFont_wght.ttf", "variable": True, "role": "emoji"},
    ],
    "Fredoka": [
        {"family": "Fredoka", "src": "fonts/Fredoka-VariableFont_wdth_wght.ttf", "variable": True},
    ],
    "Google Sans": [
        {"family": "Google Sans", "src": "fonts/GoogleSans-VariableFont_GRAD_opsz_wght.ttf", "variable": True},
        {"family": "Google Sans", "src": "fonts/GoogleSans-Italic-VariableFont_GRAD_opsz_wght.ttf", "italic": True, "variable": True},
    ],
    "Assistant": [
        {"family": "Assistant", "src": "fonts/Assistant-VariableFont_wght.ttf", "variable": True},
    ],
    "Nanum Pen Script": [
        {"family": "Nanum Pen Script", "src": "fonts/NanumPenScript-Regular.ttf", "weight": 400},
    ],
    "Plus Jakarta Sans": [
        {"family": "Plus Jakarta Sans", "src": "fonts/PlusJakartaSans-VariableFont_wght.ttf", "variable": True},
        {"family": "Plus Jakarta Sans", "src": "fonts/PlusJakartaSans-Italic-VariableFont_wght.ttf", "italic": True, "variable": True},
    ],
    "Montserrat": [
        {"family": "Montserrat", "src": "fonts/Montserrat-VariableFont_wght.ttf", "variable": True},
        {"family": "Montserrat", "src": "fonts/Montserrat-Italic-VariableFont_wght.ttf", "italic": True, "variable": True},
    ],
    "Roboto": [
        {"family": "Roboto", "src": "fonts/Roboto-VariableFont_wdth_wght.ttf", "variable": True},
    ],
}

SYSTEM_FONT_FALLBACKS: Dict[str, str] = {
    "Oscine": "Plus Jakarta Sans",
    "Google Sans Display": "Google Sans",
    "Novecento Sans": "Montserrat",
    "Snell Roundhand": "Nanum Pen Script",
    "Georgia": "Inter",
    "Helvetica Neue": "Inter",
}

# Exact weight clamps per family to avoid Skia synthetic bold
FAMILY_WEIGHT_BOUNDS: Dict[str, Tuple[int, int]] = {
    "Noto Emoji": (300, 700),
    "Fredoka": (300, 700),
    "Google Sans": (400, 700),
    "Assistant": (200, 800),
    "Nanum Pen Script": (400, 400),
    "Plus Jakarta Sans": (200, 800),
    "JetBrains Mono": (100, 800),
    "Hanken Grotesk": (100, 900),
    "Montserrat": (100, 900),
    "Roboto": (100, 900),
    "Inter": (100, 900),
}

# Exact mapping of all 34 PDF PostScript BaseFont names across the 3 talks
PDF_FONT_MAP: Dict[str, Tuple[str, int, bool]] = {
    "AppleColorEmoji": ("Noto Emoji", 400, False),
    "AssistantRoman-Regular": ("Assistant", 400, False),
    "AssistantRoman-SemiBold": ("Assistant", 600, False),
    "AssistantRoman-Bold": ("Assistant", 700, False),
    "Fredoka-Regular": ("Fredoka", 400, False),
    "Fredoka-Medium": ("Fredoka", 500, False),
    "Fredoka-SemiBold": ("Fredoka", 600, False),
    "Fredoka-Bold": ("Fredoka", 700, False),
    "Georgia": ("Georgia", 400, False),
    "Georgia-Bold": ("Georgia", 700, False),
    "GoogleSansDisplay-Regular": ("Google Sans", 400, False),
    "GoogleSansDisplay-Medium": ("Google Sans", 500, False),
    "GoogleSansDisplay-Bold": ("Google Sans", 700, False),
    "HankenGrotesk-Regular": ("Hanken Grotesk", 400, False),
    "HankenGrotesk-Italic": ("Hanken Grotesk", 400, True),
    "HankenGrotesk-Regular_SemiBold": ("Hanken Grotesk", 600, False),
    "HankenGrotesk-Regular_Bold": ("Hanken Grotesk", 700, False),
    "HankenGrotesk-Regular_ExtraBold": ("Hanken Grotesk", 800, False),
    "HankenGrotesk-Regular_Black": ("Hanken Grotesk", 900, False),
    "JetBrainsMono-Regular": ("JetBrains Mono", 400, False),
    "JetBrainsMono-Italic": ("JetBrains Mono", 400, True),
    "JetBrainsMono-Regular_Medium": ("JetBrains Mono", 500, False),
    "JetBrainsMono-Regular_SemiBold": ("JetBrains Mono", 600, False),
    "JetBrainsMono-Regular_Bold": ("JetBrains Mono", 700, False),
    "NanumPen": ("Nanum Pen Script", 400, False),
    "NotoEmoji-Regular": ("Noto Emoji", 400, False),
    "NotoEmoji-Regular_Bold": ("Noto Emoji", 700, False),
    "Novecentosans-Normal": ("Novecento Sans", 400, False),
    "Novecentosans-Medium": ("Novecento Sans", 500, False),
    "Novecentosans-DemiBold": ("Novecento Sans", 600, False),
    "Novecentosans-Bold": ("Novecento Sans", 700, False),
    "Oscine-Regular": ("Oscine", 400, False),
    "Oscine-Italic": ("Oscine", 400, True),
    "Oscine-Bold": ("Oscine", 700, False),
    "Oscine-BoldItalic": ("Oscine", 700, True),
    "Oscine-XBold": ("Oscine", 800, False),
    "Roboto-Regular": ("Roboto", 400, False),
    "Roboto-Medium": ("Roboto", 500, False),
    "Roboto-Bold": ("Roboto", 700, False),
    "SnellRoundhand": ("Snell Roundhand", 400, False),
    "ZapfDingbatsITC": ("Inter", 400, False),
}


def map_pdf_font(base_font: str) -> Tuple[str, int, bool]:
    """Map a PDF PostScript font name to (declaredFamily, clampedWeight, isItalic)."""
    if base_font in PDF_FONT_MAP:
        fam, w, it = PDF_FONT_MAP[base_font]
    else:
        low = base_font.lower()
        it = "italic" in low or "oblique" in low
        w = 400
        if "black" in low:
            w = 900
        elif "xbold" in low or "extrabold" in low:
            w = 800
        elif "semibold" in low or "demibold" in low:
            w = 600
        elif "bold" in low:
            w = 700
        elif "medium" in low:
            w = 500
        elif "light" in low:
            w = 300

        if "hanken" in low:
            fam = "Hanken Grotesk"
        elif "fredoka" in low:
            fam = "Fredoka"
        elif "assistant" in low:
            fam = "Assistant"
        elif "jetbrains" in low or "mono" in low:
            fam = "JetBrains Mono"
        elif "emoji" in low:
            fam = "Noto Emoji"
        elif "oscine" in low:
            fam = "Oscine"
        elif "novecento" in low:
            fam = "Novecento Sans"
        elif "nanum" in low:
            fam = "Nanum Pen Script"
        elif "snell" in low:
            fam = "Snell Roundhand"
        elif "roboto" in low:
            fam = "Roboto"
        elif "georgia" in low:
            fam = "Georgia"
        elif "googlesans" in low:
            fam = "Google Sans"
        else:
            fam = "Inter"

    lo, hi = FAMILY_WEIGHT_BOUNDS.get(fam, (100, 900))
    w = max(lo, min(hi, w))
    return (fam, w, it)


def build_font_specs(used_families: Set[str]) -> List[Dict[str, Any]]:
    """Build DeckDocument.fonts[] declaring all used families and acyclic fallbacks."""
    needed: Set[str] = set(used_families)
    needed.add("Inter")  # Always available for theme defaults

    # Pull in bundled fallbacks for any system font
    for fam in list(needed):
        if fam in SYSTEM_FONT_FALLBACKS:
            needed.add(SYSTEM_FONT_FALLBACKS[fam])

    specs: List[Dict[str, Any]] = []
    # Emit bundled variable families in deterministic order
    for fam in sorted(BUNDLED_VARIABLE_FONTS.keys()):
        if fam in needed:
            for entry in BUNDLED_VARIABLE_FONTS[fam]:
                specs.append(dict(entry))

    if "Inter" in needed:
        for w, src in INTER_WEIGHTS:
            specs.append({"family": "Inter", "src": src, "weight": w})

    if "JetBrains Mono" in needed:
        for w, src, it in JB_MONO_WEIGHTS:
            entry: Dict[str, Any] = {"family": "JetBrains Mono", "src": src, "weight": w}
            if it:
                entry["italic"] = True
            specs.append(entry)

    # Emit system families with bundled acyclic fallback
    for fam in sorted(SYSTEM_FONT_FALLBACKS.keys()):
        if fam in needed:
            specs.append({
                "family": fam,
                "system": True,
                "fallback": SYSTEM_FONT_FALLBACKS[fam],
            })

    return specs


# ---------------------------------------------------------------------------
# Live Specimen Registry (100% Live Code — Zero Video Files!)
# ---------------------------------------------------------------------------

# Maps normalized movie filename substring -> (specimenRef, defaultParams, optionalStableId)
MOVIE_TO_SPECIMEN: List[Tuple[str, str, Dict[str, Any], Optional[str]]] = [
    # Shaders Quickie 2025
    ("trippy.mp4", "shaders.trippyKaleidoscope", {}, "specimen-trippy-kaleidoscope"),
    ("journey.mp4", "shaders.journeyDunes", {}, "specimen-journey-dunes"),
    ("halo.mp4", "shaders.hazeScrollHeader", {}, "specimen-haze-scroll-header"),
    ("liquid ass.mp4", "shaders.liquidGlassLens", {}, "specimen-liquid-glass-lens"),
    ("rain.mp4", "shaders.raindropsWindow", {}, "specimen-raindrops-window"),
    ("screen recording 2025-10-20 at 10.14.54", "shaders.liveUvAnimated", {"speed": 1}, "specimen-live-uv-animated"),
    ("plaid.mp4", "shaders.plaidHyperspaceStarfield", {"uPlaidMix": 0.65}, "plaid-starfield"),
    # Jewel Talk 2024
    ("screen recording 2024-10-30 at 16.42.02", "jewel.addDeviceDialog", {}, "specimen-add-device-dialog"),
    ("screen recording 2024-10-30 at 14.46.48", "jewel.geminiAuthCard", {}, "specimen-gemini-auth-card"),
    ("screen recording 2024-10-30 at 15.05.00", "jewel.geminiPromptInput", {}, "specimen-gemini-prompt-input"),
    ("screen recording 2024-10-30 at 15.22.32", "jewel.geminiStreamingChat", {}, "specimen-gemini-streaming-chat"),
    ("screen recording 2024-10-30 at 15.34.09", "jewel.geminiChatComposer", {}, "specimen-gemini-chat-composer"),
    ("bouncy.mov", "jewel.referencesBouncyExpand", {}, "specimen-references-bouncy-expand"),
    ("screen recording 2024-10-30 at 15.55.44", "jewel.geminiToolWindowFull", {}, "specimen-gemini-tool-window-full"),
    ("tada.gif", "jewel.confettiPartyPopper", {}, None),
    ("screen recording 2024-10-26 at 19.15.41", "jewel.markdownStandaloneSample", {}, "specimen-markdown-standalone"),
    ("screen recording 2024-10-30 at 14.35.40", "jewel.microinteractionScrollFab", {}, "specimen-microinteraction-scroll-fab"),
    ("enabled-button.mov", "jewel.microinteractionEnabledButton", {}, "specimen-microinteraction-enabled-button"),
    ("adaptive-wizard.mov", "jewel.adaptiveDemoWizard", {}, "specimen-adaptive-demo-wizard"),
    ("screen recording 2024-10-29 at 18.11.59", "jewel.adaptiveWireframePanes", {"widthFraction": 0.72}, "specimen-adaptive-wireframe-panes"),
    ("m3components.mov", "jewel.m3ExpressiveShowcase", {}, "specimen-m3-expressive-showcase"),
    ("loading-gemini4.mov", "jewel.geminiLoadingChoreography", {}, "specimen-gemini-loading-choreography"),
    ("bouncy2.mov", "jewel.referencesBouncySpringV2", {}, "specimen-references-bouncy-spring-v2"),
    # Not Just Plain Text 2024
    ("screen recording 2024-11-29 at 12.59.36", "njpt.jewelMarkdownSplitDemo", {}, "specimen-njpt-markdown-split"),
    ("screen recording 2024-11-29 at 13.08.29", "njpt.geminiDarkMarkdownChat", {}, "specimen-njpt-gemini-dark-chat"),
]

# Deterministic loop periods (ms) registered at DeckDocument.loopPeriodsMs
SPECIMEN_LOOP_PERIODS_MS: Dict[str, int] = {
    "shaders.trippyKaleidoscope": 12000,
    "shaders.journeyDunes": 14000,
    "shaders.hazeScrollHeader": 8000,
    "shaders.liquidGlassLens": 8000,
    "shaders.raindropsWindow": 10000,
    "shaders.liveUvAnimated": 6283,
    "shaders.plaidHyperspaceStarfield": 16000,
    "jewel.addDeviceDialog": 12000,
    "jewel.geminiAuthCard": 4000,
    "jewel.geminiPromptInput": 12000,
    "jewel.geminiStreamingChat": 10000,
    "jewel.geminiChatComposer": 8000,
    "jewel.referencesBouncyExpand": 3500,
    "jewel.geminiToolWindowFull": 6000,
    "jewel.confettiPartyPopper": 1400,
    "jewel.markdownStandaloneSample": 14000,
    "jewel.microinteractionScrollFab": 8500,
    "jewel.microinteractionEnabledButton": 2400,
    "jewel.adaptiveDemoWizard": 5400,
    "jewel.adaptiveWireframePanes": 6400,
    "jewel.m3ExpressiveShowcase": 9400,
    "jewel.geminiLoadingChoreography": 2400,
    "jewel.referencesBouncySpringV2": 2200,
    "njpt.jewelMarkdownSplitDemo": 14000,
    "njpt.geminiDarkMarkdownChat": 12000,
}


def resolve_movie_specimen(
    movie_filename: str,
    talk_id: str,
    slide_num: int,
    bounds: Tuple[float, float, float, float],
) -> Tuple[str, Dict[str, Any], Optional[str]]:
    """
    Map an embedded KPF movie/GIF filename (or known talk/slide fallback) to
    (specimen_ref, params_dict, optional_stable_id).
    """
    low = (movie_filename or "").lower()
    x, y, w, h = bounds

    for needle, ref, default_params, stable_id in MOVIE_TO_SPECIMEN:
        if needle in low:
            params = dict(default_params)
            if ref == "jewel.confettiPartyPopper":
                # Left popper is near x=-40, right popper is near x=1440 (mirrored)
                if x > 900:
                    params["mirrored"] = True
                    stable_id = "specimen-confetti-right"
                else:
                    stable_id = "specimen-confetti-left"
            return (ref, params, stable_id)

    # Positional fallback by (talk_id, slide_num, size) when KPF movie name is obfuscated UUID
    if talk_id == "shaders":
        if slide_num == 5:
            return ("shaders.trippyKaleidoscope", {}, "specimen-trippy-kaleidoscope")
        if slide_num == 6:
            return ("shaders.journeyDunes", {}, "specimen-journey-dunes")
        if slide_num == 7:
            return ("shaders.hazeScrollHeader", {}, "specimen-haze-scroll-header")
        if slide_num == 8:
            return ("shaders.liquidGlassLens", {}, "specimen-liquid-glass-lens")
        if slide_num == 9:
            return ("shaders.raindropsWindow", {}, "specimen-raindrops-window")
        if slide_num == 27:
            return ("shaders.liveUvAnimated", {"speed": 1}, "specimen-live-uv-animated")
        if slide_num in (45, 46):
            return ("shaders.plaidHyperspaceStarfield", {"uPlaidMix": 0.65}, "plaid-starfield")

    if talk_id == "jewel":
        if slide_num in (18, 19):
            return ("jewel.addDeviceDialog", {}, "specimen-add-device-dialog")
        if slide_num in (25, 26, 27, 28, 29, 30):
            # Distinguish the 6 Gemini specimens by aspect ratio & bounds
            aspect = w / max(h, 1.0)
            if slide_num == 30 and h > 700 and 500 <= w <= 650:
                return ("jewel.geminiToolWindowFull", {}, "specimen-gemini-tool-window-full")
            if aspect > 2.2:
                return ("jewel.geminiChatComposer", {}, "specimen-gemini-chat-composer")
            if aspect < 0.9:
                return ("jewel.geminiStreamingChat", {}, "specimen-gemini-streaming-chat")
            if 1.15 <= aspect <= 1.38:
                return ("jewel.referencesBouncyExpand", {}, "specimen-references-bouncy-expand")
            if slide_num == 25 or (w > 700 and aspect > 1.55) or (y < 150 and x > 1100):
                return ("jewel.geminiAuthCard", {}, "specimen-gemini-auth-card")
            return ("jewel.geminiPromptInput", {}, "specimen-gemini-prompt-input")
        if slide_num == 31:
            if x > 900:
                return ("jewel.confettiPartyPopper", {"mirrored": True}, "specimen-confetti-right")
            return ("jewel.confettiPartyPopper", {}, "specimen-confetti-left")
        if slide_num == 36:
            return ("jewel.markdownStandaloneSample", {}, "specimen-markdown-standalone")
        if slide_num in (49, 50):
            if w > 700:
                return ("jewel.microinteractionScrollFab", {}, "specimen-microinteraction-scroll-fab")
            return ("jewel.microinteractionEnabledButton", {}, "specimen-microinteraction-enabled-button")
        if slide_num == 52:
            return ("jewel.adaptiveDemoWizard", {}, "specimen-adaptive-demo-wizard")
        if slide_num == 53:
            return ("jewel.adaptiveWireframePanes", {"widthFraction": 0.72}, "specimen-adaptive-wireframe-panes")
        if slide_num == 55:
            return ("jewel.m3ExpressiveShowcase", {}, "specimen-m3-expressive-showcase")
        if slide_num == 57:
            if x < 700:
                return ("jewel.geminiLoadingChoreography", {}, "specimen-gemini-loading-choreography")
            return ("jewel.referencesBouncySpringV2", {}, "specimen-references-bouncy-spring-v2")

    if talk_id == "njpt":
        if slide_num == 125:
            return ("njpt.jewelMarkdownSplitDemo", {}, "specimen-njpt-markdown-split")
        if slide_num == 128:
            return ("njpt.geminiDarkMarkdownChat", {}, "specimen-njpt-gemini-dark-chat")

    return ("todo.video", {"source": movie_filename or f"slide-{slide_num}-movie"}, None)


# Specimens that accept live keyboard input on stage (StageInteraction).
INTERACTIVE_SPECIMEN_MODES: Dict[str, str] = {
    "jewel.markdownStandaloneSample": "keyboard",
    "njpt.jewelMarkdownSplitDemo": "keyboard",
}


def classify_shaders_preview_specimen(
    slide_num: int,
    x: float,
    y: float,
    w: float,
    h: float,
    fill_hex: Optional[str] = None,
    is_raster: bool = False,
    is_build_in: bool = False,
) -> Optional[Tuple[str, Dict[str, Any], str]]:
    """
    Identify live SkSL preview squares on Shaders Quickie S14..S44.
    Returns (specimen_ref, params, stable_id) or None if not a preview square.
    """
    # Preview squares sit on the right half (x ~ 1250..1450) with size ~ 395..425 x 395..425
    # (except S33 where the curtain group starts at x ~ 760 before sliding right to x ~ 1353).
    min_x_allowed = 650 if slide_num == 33 else 1200
    is_preview_box = (
        min_x_allowed <= x <= 1500
        and 250 <= y <= 450
        and 380 <= w <= 440
        and 380 <= h <= 440
        and abs(w - h) <= 20
    )
    if not is_preview_box:
        return None

    # S14..S23 solid-color shader evolution squares (non-raster shapes)
    if not is_raster and 14 <= slide_num <= 23 and fill_hex:
        hx = fill_hex.upper()
        if hx in ("#000000", "#111111"):
            # S14..S18 demonstrate `return half4(0.0);` (fully transparent rgba=(0,0,0,0)),
            # so emit a=0 to reveal the alpha checkerboard vs opaque black.
            alpha = 0 if 14 <= slide_num <= 18 else 1
            return ("shaders.colorEvolution", {"r": 0, "g": 0, "b": 0, "a": alpha}, "specimen-color-preview")
        if hx in ("#FFFFFF", "#FEFEFE", "#FDFDFD"):
            return ("shaders.colorEvolution", {"r": 1, "g": 1, "b": 1, "a": 1}, "specimen-color-preview")
        if hx in ("#EE220C", "#FF0000", "#ED220D", "#F0240E", "#FF2600"):
            return ("shaders.colorEvolution", {"r": 1, "g": 0, "b": 0, "a": 1}, "specimen-color-preview")

    if is_raster:
        if slide_num in (22, 23, 26, 27):
            return ("shaders.liveUvStatic", {}, "specimen-live-uv-static")
        if slide_num in (33, 34):
            return ("shaders.sdfGoalCircle", {}, "specimen-sdf-goal")
        if 35 <= slide_num <= 38:
            return ("shaders.sdfRawDistance", {}, "specimen-sdf-raw")
        if slide_num == 39:
            # S39 shows raw distance BEFORE, then wipes in step circle AFTER
            if is_build_in:
                return ("shaders.sdfStepCircle", {}, "specimen-sdf-step")
            return ("shaders.sdfRawDistance", {}, "specimen-sdf-raw")
        if slide_num in (40, 41):
            return ("shaders.sdfStepCircle", {}, "specimen-sdf-step")
        if slide_num == 42:
            # S42 shows step circle BEFORE, then wipes in CSG subtract AFTER
            if is_build_in:
                return ("shaders.sdfCsgSubtract", {}, "specimen-sdf-csg-sub")
            return ("shaders.sdfStepCircle", {}, "specimen-sdf-step")
        if slide_num == 43:
            return ("shaders.sdfCsgSubtract", {}, "specimen-sdf-csg-sub")
        if slide_num == 44:
            # S44 shows CSG subtract BEFORE, then wipes in CSG union-subtract AFTER
            if is_build_in:
                return ("shaders.sdfCsgUnionSubtract", {}, "specimen-sdf-csg-union")
            return ("shaders.sdfCsgSubtract", {}, "specimen-sdf-csg-sub")

    return None


def classify_jewel_static_specimen(
    slide_num: int,
    native_w: int,
    native_h: int,
    x: float,
    y: float,
    w: float,
    h: float,
) -> Optional[Tuple[str, Dict[str, Any], str]]:
    """Replace Jewel UI screenshots (non-video) with their exact live Compose/Jewel specimens."""
    if slide_num in (16, 17) and native_w >= 1100 and native_h >= 600:
        return ("jewel.taskBasedProfilers", {}, "specimen-task-based-profilers")
    if slide_num in (42, 43) and native_w >= 1100 and native_h >= 850:
        return ("jewel.swingComposeInterop", {}, "specimen-swing-compose-interop")
    if slide_num == 56 and native_w >= 1200 and native_h >= 950:
        return ("jewel.springTokenTree", {}, "specimen-spring-token-tree")
    if 59 <= slide_num <= 64:
        if 380 <= native_w <= 430 and 760 <= native_h <= 830:
            return ("jewel.cohesionIdeShowcase", {"panel": "emulator"}, "specimen-cohesion-emulator")
        if slide_num == 64 and 780 <= native_w <= 850 and 620 <= native_h <= 680:
            return ("jewel.cohesionIdeShowcase", {"panel": "comparison"}, "specimen-cohesion-comparison")
        if 790 <= native_w <= 850 and 620 <= native_h <= 680:
            return ("jewel.cohesionIdeShowcase", {"panel": "settings"}, "specimen-cohesion-settings")
        if 1120 <= native_w <= 1200 and 840 <= native_h <= 920:
            return ("jewel.cohesionIdeShowcase", {"panel": "idePreview"}, "specimen-cohesion-ide-preview")
        if 1500 <= native_w <= 1600 and 920 <= native_h <= 1000:
            return ("jewel.cohesionIdeShowcase", {"panel": "ideFull"}, "specimen-cohesion-ide-full")
    return None


# ---------------------------------------------------------------------------
# .key Archive Asset Extractor (SVGs + genuine static portraits/illustrations)
# ---------------------------------------------------------------------------

_SVG_STYLE_BLOCK_RE = re.compile(r"<style\b[^>]*>(.*?)</style>", re.IGNORECASE | re.S)
_CSS_CLASS_RULE_RE = re.compile(r"\.([A-Za-z_][A-Za-z0-9_-]*)\s*\{([^}]*)\}")
_SVG_PRESENTATION_PROPS = frozenset({
    "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity",
    "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "opacity",
    "stop-color", "stop-opacity",
})


def _inline_svg_css_styles(svg_text: str) -> str:
    """
    Rewrite Adobe Illustrator `<style>.st0{fill:url(#...);}</style>` class rules and
    `<stop style="stop-color:..."/>` into direct SVG presentation attributes so Skia
    SVGDOM renders gradients and fills faithfully instead of falling back to black.
    """
    class_rules: Dict[str, Dict[str, str]] = {}

    def _collect_style(match: re.Match[str]) -> str:
        css = match.group(1)
        for cls_name, decls in _CSS_CLASS_RULE_RE.findall(css):
            props: Dict[str, str] = {}
            for item in decls.split(";"):
                if ":" not in item:
                    continue
                k, v = item.split(":", 1)
                k = k.strip().lower()
                v = v.strip()
                if k in _SVG_PRESENTATION_PROPS and v:
                    props[k] = v
            if props:
                class_rules.setdefault(cls_name, {}).update(props)
        return ""

    if "<style" in svg_text.lower():
        svg_text = _SVG_STYLE_BLOCK_RE.sub(_collect_style, svg_text)

    if class_rules:
        def _replace_class_attr(match: re.Match[str]) -> str:
            classes = match.group(1).split()
            inlined: Dict[str, str] = {}
            remaining: List[str] = []
            for cls in classes:
                if cls in class_rules:
                    inlined.update(class_rules[cls])
                else:
                    remaining.append(cls)
            if not inlined:
                return match.group(0)
            parts = [f'{k}="{v}"' for k, v in inlined.items()]
            if remaining:
                parts.append(f'class="{" ".join(remaining)}"')
            return " ".join(parts)

        svg_text = re.sub(r'class="([^"]+)"', _replace_class_attr, svg_text)

    # Also inline `style="stop-color:...;stop-opacity:..."` on `<stop>` elements
    def _replace_stop_style(match: re.Match[str]) -> str:
        tag = match.group(0)
        sm = re.search(r'style="([^"]+)"', tag)
        if not sm:
            return tag
        inlined: List[str] = []
        leftover: List[str] = []
        for item in sm.group(1).split(";"):
            item = item.strip()
            if not item or ":" not in item:
                continue
            k, v = item.split(":", 1)
            k_low = k.strip().lower()
            v_str = v.strip()
            if k_low in ("stop-color", "stop-opacity") and v_str:
                inlined.append(f'{k_low}="{v_str}"')
            else:
                leftover.append(item)
        if not inlined:
            return tag
        repl = " ".join(inlined)
        if leftover:
            repl += f' style="{"; ".join(leftover)}"'
        return tag[: sm.start()] + repl + tag[sm.end() :]

    svg_text = re.sub(r"<stop\b[^>]*/?>", _replace_stop_style, svg_text)
    return svg_text


class KeyAssetExtractor:
    """Extracts crisp SVG and genuine static PNG assets from a Keynote .key ZIP archive."""

    def __init__(self, key_path: Optional[Path], assets_out_dir: Path, extra_svg_dir: Optional[Path] = None):
        self.key_path = key_path
        self.assets_out_dir = assets_out_dir
        self.extra_svg_dir = extra_svg_dir
        self._entries: Dict[str, str] = {}
        self._extracted: Set[str] = set()
        if key_path and key_path.exists():
            with zipfile.ZipFile(key_path) as zf:
                for name in zf.namelist():
                    if name.startswith("Data/") and not name.endswith("/"):
                        self._entries[name.lower()] = name

    @staticmethod
    def _normalize_asset_bytes(target_filename: str, data: bytes) -> bytes:
        if target_filename.lower().endswith(".svg"):
            text = data.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
            text = _inline_svg_css_styles(text)
            lines = [ln.rstrip(" \t") for ln in text.split("\n")]
            return ("\n".join(lines).rstrip("\n") + "\n").encode("utf-8")
        return data

    def extract_matching(self, pattern: str, target_filename: str) -> Optional[str]:
        """Extract first Data/* entry matching regex `pattern` as `target_filename`."""
        if target_filename in self._extracted:
            return target_filename
        # Check extra_svg_dir first (e.g. qr-code.svg beside the .key file)
        if self.extra_svg_dir:
            cand = self.extra_svg_dir / target_filename
            if cand.exists():
                self.assets_out_dir.mkdir(parents=True, exist_ok=True)
                data = self._normalize_asset_bytes(target_filename, cand.read_bytes())
                (self.assets_out_dir / target_filename).write_bytes(data)
                self._extracted.add(target_filename)
                return target_filename
        if not self.key_path or not self.key_path.exists():
            return None
        rx = re.compile(pattern, re.IGNORECASE)
        match_name = None
        for low, orig in sorted(self._entries.items()):
            if rx.search(orig):
                match_name = orig
                break
        if not match_name:
            return None
        with zipfile.ZipFile(self.key_path) as zf:
            data = zf.read(match_name)
        self.assets_out_dir.mkdir(parents=True, exist_ok=True)
        (self.assets_out_dir / target_filename).write_bytes(
            self._normalize_asset_bytes(target_filename, data)
        )
        self._extracted.add(target_filename)
        return target_filename


def match_key_svg_replacement(
    talk_id: str,
    slide_num: int,
    bounds: Tuple[float, float, float, float],
    acc_desc: str = "",
) -> Optional[Tuple[str, str, str]]:
    """
    Check if a KPF layer corresponds to a known SVG vector graphic in `.key`
    (so we emit a crisp single `image` node instead of hundreds of raw PDF vector paths!).
    Returns (regex_in_key_zip, clean_asset_filename, stable_id) or None.
    """
    x, y, w, h = bounds
    low = (acc_desc or "").lower()

    if talk_id == "shaders":
        if slide_num == 11:
            if "jetpack-compose" in low or (1240 <= x <= 1360 and 660 <= y <= 760 and 120 <= w <= 220):
                return (r"Data/jetpack-compose-\d+\.svg$", "jetpack-compose.svg", "logo-jetpack-compose")
            if "compose-logo" in low or (400 <= x <= 510 and 690 <= y <= 780 and 90 <= w <= 180):
                return (r"Data/compose-logo-\d+\.svg$", "compose-logo.svg", "logo-compose-multiplatform")
        if slide_num == 16:
            if 30 <= w <= 65 and 30 <= h <= 65 and ("kotlin" in low or (350 <= x <= 410 and 690 <= y <= 750)):
                return (r"Data/Kotlin Full Color Logo Mark RGB-\d+\.svg$", "kotlin-logo.svg", "logo-kotlin")
        if slide_num == 46:
            if "qr-code" in low or (800 <= x <= 880 and 340 <= y <= 410 and 200 <= w <= 280):
                return (r"Data/qr-code-\d+\.svg$", "qr-code.svg", "qr-code")

    if talk_id == "jewel":
        if 21 <= slide_num <= 30:
            is_hero_gemini = slide_num == 21 and 760 <= x <= 940 and 350 <= y <= 500 and 180 <= w <= 320
            is_corner_gemini = 22 <= slide_num <= 30 and x <= 140 and y >= 880 and 60 <= w <= 150
            if "gemini-lockup" in low or is_hero_gemini or is_corner_gemini:
                return (r"Data/gemini-lockup-\d+\.svg$", "gemini-lockup.svg", "logo-gemini-lockup")
        if slide_num == 24:
            if 80 <= w <= 180 and 80 <= h <= 180 and ("compose-multiplatform" in low or (580 <= x <= 700 and 200 <= y <= 340)):
                return (r"Data/compose-multiplatform-min-\d+\.svg$", "compose-multiplatform.svg", "logo-compose-multiplatform")

    if talk_id == "njpt":
        if slide_num in (3, 111) and w <= 120 and h <= 120:
            if "checkboxselected" in low:
                return (r"Data/checkBoxSelected-\d+\.svg$", "check-box-selected.svg", "icon-checkbox-selected")
            if "externallink" in low:
                return (r"Data/externalLink-\d+\.svg$", "external-link.svg", "icon-external-link")
        if slide_num == 75:
            if "jewel-logo" in low or (w >= 100 and h >= 100 and abs(w - h) <= 30 and y < 500):
                return (r"Data/jewel-logo-\d+\.svg$", "jewel-logo.svg", "logo-jewel")

    return None


def match_static_raster_asset(
    talk_id: str,
    slide_num: int,
    native_w: int,
    native_h: int,
    x: float,
    y: float,
    w: float,
    h: float,
    acc_desc: str = "",
    avatar_counter: Optional[List[int]] = None,
) -> Optional[Tuple[Optional[str], str, Optional[str]]]:
    """
    Classify genuine non-demo static raster images (portraits, illustrations, comics).
    Returns (optional_key_zip_regex, clean_asset_filename, optional_stable_id).
    If key_zip_regex is None, export directly from the PDF Image XObject!
    """
    low = (acc_desc or "").lower()

    if talk_id == "jewel":
        if slide_num in (1, 2, 3) and ("sparkles" in low or (155 <= native_w <= 190 and 160 <= native_h <= 195)):
            return (r"Data/sparkles-\d+\.png$", "sparkles.png", "sparkles-icon")
        if slide_num == 3:
            if "chris" in low or (240 <= native_w <= 270 and 300 <= native_h <= 340):
                return (r"Data/chris-v2-\d+\.png$", "chris-portrait.png", "portrait-chris")
            if "seb" in low or (260 <= native_w <= 290 and 200 <= native_h <= 230):
                return (r"Data/seb-no-bg-\d+\.png$", "seb-portrait.png", "portrait-seb")
        if slide_num == 9 and 360 <= native_w <= 410 and 150 <= native_h <= 200:
            return (None, "s09-technical-difficulties.png", "img-technical-difficulties")
        if slide_num == 11 and 660 <= native_w <= 710:
            if native_h >= 420:
                return (None, "droidcon-2023-thumb-full.png", "img-droidcon-2023-thumb")
            return (None, "droidcon-2023-thumb-crop.png", "img-droidcon-2023-thumb")
        if slide_num == 73 and 130 <= native_w <= 165 and 130 <= native_h <= 165:
            # 3 speaker headshots ordered left-to-right by x (~686, ~886, ~1085)
            if x < 780:
                idx = 1
            elif x < 980:
                idx = 2
            else:
                idx = 3
            return (None, f"speaker-avatar-{idx}.png", f"speaker-avatar-{idx}")

    if talk_id == "njpt":
        if slide_num == 11 and 450 <= native_w <= 510 and 220 <= native_h <= 270:
            return (None, "html-css-mark.png", "img-html-css-mark")
        if slide_num in (15, 18, 20) and 400 <= native_w <= 440 and 240 <= native_h <= 275:
            return (None, "url-overflow-sample.png", "img-url-overflow-sample")
        if slide_num == 63 and (460 <= native_w <= 540 or "pasted-image" in low):
            return (None, "xkcd-standards.png", "img-xkcd-standards")
        if 89 <= slide_num <= 96 and 200 <= native_w <= 230 and 115 <= native_h <= 140:
            return (None, "nested-blocks-fade.png", "img-nested-blocks-fade")

    return None


# ---------------------------------------------------------------------------
# Deck Themes & Palettes Builder
# ---------------------------------------------------------------------------

def build_deck_themes(talk_id: str) -> Tuple[Dict[str, Any], str, Dict[str, Dict[str, str]]]:
    """Return (themes, defaultTheme, palettes) tuned to each talk's verified Display P3 -> sRGB palette."""
    if talk_id == "shaders":
        themes = {
            "dark": {
                "preset": "dark",
                "colors": {
                    "canvas": "#594548",
                    "surface": "#3F3134",
                    "ink": "#EBEDEC",
                    "muted": "#C8C2C0",
                    "accent": "#F4B942",
                },
                "typography": {
                    "title": {"font": "Fredoka", "size": 96, "weight": 700, "color": "ink"},
                    "heading": {"font": "Fredoka", "size": 56, "weight": 600, "color": "ink"},
                    "body": {"font": "Assistant", "size": 36, "weight": 400, "color": "ink"},
                    "code": {"font": "JetBrains Mono", "size": 24, "weight": 400, "color": "ink"},
                },
            },
            "light": {
                "preset": "light",
                "colors": {
                    "canvas": "#EBEDEC",
                    "surface": "#FFFFFF",
                    "ink": "#594548",
                    "muted": "#7A6A6D",
                    "accent": "#C95D63",
                },
                "typography": {
                    "title": {"font": "Fredoka", "size": 96, "weight": 700, "color": "ink"},
                    "heading": {"font": "Fredoka", "size": 56, "weight": 600, "color": "ink"},
                    "body": {"font": "Assistant", "size": 36, "weight": 400, "color": "ink"},
                    "code": {"font": "JetBrains Mono", "size": 24, "weight": 400, "color": "ink"},
                },
            },
        }
        palettes = {
            "espresso": {"canvas": "#594548", "ink": "#EBEDEC", "accent": "#F4B942"},
            "paper": {"canvas": "#EBEDEC", "ink": "#594548", "accent": "#C95D63"},
            "void": {"canvas": "#000000", "ink": "#FFFFFF", "accent": "#F4B942"},
        }
        return (themes, "dark", palettes)

    if talk_id == "jewel":
        themes = {
            "dark": {
                "preset": "dark",
                "colors": {
                    "canvas": "#29223A",
                    "surface": "#372E4D",
                    "ink": "#FFFFFF",
                    "muted": "#C6BBD9",
                    "accent": "#EF5350",
                },
                "typography": {
                    "title": {"font": "Hanken Grotesk", "size": 96, "weight": 800, "color": "ink"},
                    "heading": {"font": "Hanken Grotesk", "size": 56, "weight": 700, "color": "ink"},
                    "body": {"font": "Hanken Grotesk", "size": 36, "weight": 400, "color": "ink"},
                    "code": {"font": "JetBrains Mono", "size": 24, "weight": 400, "color": "ink"},
                },
            },
            "light": {
                "preset": "light",
                "colors": {
                    "canvas": "#FAF7F5",
                    "surface": "#FFFFFF",
                    "ink": "#29223A",
                    "muted": "#5E5570",
                    "accent": "#EF5350",
                },
                "typography": {
                    "title": {"font": "Hanken Grotesk", "size": 96, "weight": 800, "color": "ink"},
                    "heading": {"font": "Hanken Grotesk", "size": 56, "weight": 700, "color": "ink"},
                    "body": {"font": "Hanken Grotesk", "size": 36, "weight": 400, "color": "ink"},
                    "code": {"font": "JetBrains Mono", "size": 24, "weight": 400, "color": "ink"},
                },
            },
        }
        palettes = {
            "coral": {"canvas": "#EF5350", "ink": "#FFFFFF", "accent": "#FFF176"},
            "eggplant": {"canvas": "#29223A", "ink": "#FFFFFF", "accent": "#E3F574"},
            "olive": {"canvas": "#313325", "ink": "#E3F574", "accent": "#EF5350"},
        }
        return (themes, "dark", palettes)

    # Default / NJPT
    themes = {
        "light": {
            "preset": "light",
            "colors": {
                "canvas": "#F5F2EB",
                "surface": "#FFFFFF",
                "ink": "#222429",
                "muted": "#5D626E",
                "accent": "#3B82F6",
            },
            "typography": {
                "title": {"font": "Oscine", "size": 84, "weight": 700, "color": "ink"},
                "heading": {"font": "Novecento Sans", "size": 48, "weight": 600, "color": "ink"},
                "body": {"font": "Oscine", "size": 34, "weight": 400, "color": "ink"},
                "code": {"font": "JetBrains Mono", "size": 24, "weight": 400, "color": "ink"},
            },
        },
        "dark": {
            "preset": "dark",
            "colors": {
                "canvas": "#1C1E26",
                "surface": "#282B36",
                "ink": "#F5F2EB",
                "muted": "#A3A8B8",
                "accent": "#60A5FA",
            },
            "typography": {
                "title": {"font": "Oscine", "size": 84, "weight": 700, "color": "ink"},
                "heading": {"font": "Novecento Sans", "size": 48, "weight": 600, "color": "ink"},
                "body": {"font": "Oscine", "size": 34, "weight": 400, "color": "ink"},
                "code": {"font": "JetBrains Mono", "size": 24, "weight": 400, "color": "ink"},
            },
        },
    }
    palettes = {
        "paper": {"canvas": "#F5F2EB", "ink": "#222429", "accent": "#3B82F6"},
        "midnight": {"canvas": "#1C1E26", "ink": "#F5F2EB", "accent": "#60A5FA"},
    }
    return (themes, "light", palettes)
