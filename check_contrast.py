#!/usr/bin/env python3
"""
Vérificateur de contraste WCAG pour les layouts Android.
Parse les XMLs de ressources et calcule les ratios texte/fond.
Usage : python3 check_contrast.py
"""

import xml.etree.ElementTree as ET
import re
import os

RES = "/mnt/sdcard/claude_code/claude-android/app/src/main/res"

# ── Parsing des couleurs ─────────────────────────────────────────────────────

def load_colors():
    colors = {}
    path = f"{RES}/values/colors.xml"
    if not os.path.exists(path):
        return colors
    for el in ET.parse(path).getroot():
        if el.tag == "color" and el.text:
            colors[el.attrib["name"]] = el.text.strip()
    return colors

def load_styles():
    """Retourne {style_name: {attr: value}}"""
    styles = {}
    path = f"{RES}/values/styles.xml"
    if not os.path.exists(path):
        return styles
    for el in ET.parse(path).getroot():
        if el.tag == "style":
            name = el.attrib.get("name", "")
            parent = el.attrib.get("parent", "")
            items = {i.attrib["name"]: (i.text or "").strip() for i in el}
            styles[name] = {"parent": parent, **items}
    return styles

COLORS  = load_colors()
STYLES  = load_styles()

def load_drawable_colors():
    """Extrait la couleur de fond des shape drawables XML."""
    result = {}
    ddir = f"{RES}/drawable"
    if not os.path.exists(ddir):
        return result
    for fname in os.listdir(ddir):
        if not fname.endswith(".xml"):
            continue
        name = fname[:-4]
        try:
            root = ET.parse(f"{ddir}/{fname}").getroot()
            # <shape><solid android:color="..."/></shape>
            solid = root.find(".//{http://schemas.android.com/apk/res/android}color/../..")
            for child in root.iter():
                if child.tag.split("}")[-1] == "solid":
                    c = child.attrib.get("{http://schemas.android.com/apk/res/android}color")
                    if c:
                        result[name] = c
                        break
        except Exception:
            pass
    return result

DRAWABLE_COLORS = load_drawable_colors()

# ── Résolution des références couleur ────────────────────────────────────────

ANDROID_HARDCODED = {
    "@android:color/white":        "#FFFFFF",
    "@android:color/black":        "#000000",
    "@android:color/transparent":  "#00000000",
    "@android:color/darker_gray":  "#444444",
    "?attr/colorOnPrimary":        "#FFFFFF",  # approximation Material
    "?attr/colorOnSurface":        "#000000",
}

def resolve_color(ref: str) -> str | None:
    if not ref:
        return None
    ref = ref.strip()
    if ref.startswith("#"):
        return ref
    if ref in ANDROID_HARDCODED:
        return ANDROID_HARDCODED[ref]
    # @color/name
    m = re.match(r"@(?:color|android:color)/(\w+)", ref)
    if m:
        c = COLORS.get(m.group(1))
        return resolve_color(c) if c and not c.startswith("#") else c
    # @drawable/name → cherche une solid color dans le drawable
    m = re.match(r"@drawable/(\w+)", ref)
    if m:
        raw = DRAWABLE_COLORS.get(m.group(1))
        return resolve_color(raw) if raw else None
    return None

# ── Calcul WCAG ──────────────────────────────────────────────────────────────

def hex_to_rgb(h: str):
    h = h.lstrip("#")
    if len(h) == 8:
        h = h[2:]  # strip alpha
    if len(h) == 3:
        h = "".join(c*2 for c in h)
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def linearize(c: float) -> float:
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def luminance(hex_color: str) -> float:
    r, g, b = hex_to_rgb(hex_color)
    return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)

def contrast_ratio(c1: str, c2: str) -> float:
    l1, l2 = sorted([luminance(c1), luminance(c2)], reverse=True)
    return (l1 + 0.05) / (l2 + 0.05)

def wcag_level(ratio: float, large_text=False) -> str:
    aa  = 3.0 if large_text else 4.5
    aaa = 4.5 if large_text else 7.0
    if ratio >= aaa: return "AAA ✓"
    if ratio >= aa:  return "AA  ✓"
    if ratio >= 3.0: return "AA large ✓" if not large_text else "AA  ✓"
    return "FAIL ✗"

# ── Analyse des layouts ───────────────────────────────────────────────────────

NS = {"android": "http://schemas.android.com/apk/res/android"}

TEXT_TAGS = {"TextView", "EditText", "Button", "ImageButton",
             "androidx.appcompat.widget.AppCompatTextView"}

def tag_short(tag: str) -> str:
    return tag.split(".")[-1].split("}")[-1]

def analyze_layout(path: str):
    issues = []
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        return [f"  ⚠ Parse error: {e}"]

    for el in tree.iter():
        tag = tag_short(el.tag)

        bg_ref   = el.attrib.get("{http://schemas.android.com/apk/res/android}background")
        text_ref = el.attrib.get("{http://schemas.android.com/apk/res/android}textColor")

        bg_color   = resolve_color(bg_ref)   if bg_ref   else None
        text_color = resolve_color(text_ref) if text_ref else None

        # Valeur par défaut si textColor non spécifié sur un TextView
        if tag in TEXT_TAGS and not text_color:
            text_color = "#212121"  # Material dark text

        if bg_color and text_color:
            try:
                ratio = contrast_ratio(bg_color, text_color)
                level = wcag_level(ratio)
                line = (f"  {tag:<28} bg={bg_color} fg={text_color} "
                        f"→ {ratio:4.2f}:1  {level}")
                issues.append((ratio, line))
            except Exception:
                pass

    return issues

# ── Main ─────────────────────────────────────────────────────────────────────

LAYOUT_DIR = f"{RES}/layout"

print("=" * 65)
print("WCAG Contrast Check — Android layouts")
print("=" * 65)
print(f"Seuils : AA texte normal ≥ 4.5 | AA grand texte ≥ 3.0 | AAA ≥ 7.0\n")

all_issues = []

for fname in sorted(os.listdir(LAYOUT_DIR)):
    if not fname.endswith(".xml"):
        continue
    path = f"{LAYOUT_DIR}/{fname}"
    results = analyze_layout(path)
    if results:
        print(f"📄 {fname}")
        for ratio, line in sorted(results):
            marker = " ◄◄ LOW CONTRAST" if ratio < 4.5 else ""
            print(line + marker)
            if ratio < 4.5:
                all_issues.append((fname, ratio, line))
        print()

print("=" * 65)
if all_issues:
    print(f"⚠  {len(all_issues)} problème(s) de contraste détecté(s) :\n")
    for fname, ratio, line in sorted(all_issues, key=lambda x: x[1]):
        print(f"  [{fname}] ratio={ratio:.2f}  {line.strip()}")
else:
    print("✓ Aucun problème de contraste détecté.")
print()

# Résumé des couleurs définies
print("Couleurs définies dans colors.xml :")
for name, val in COLORS.items():
    try:
        l = luminance(val)
        print(f"  {name:<35} {val}  L={l:.3f}")
    except Exception:
        print(f"  {name:<35} {val}  (non parseable)")
