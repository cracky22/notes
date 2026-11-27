#!/usr/bin/env python3
"""Erzeugt das App-Icon (adaptiv + Legacy + Splash) aus einer SVG.

Aufruf (im Projektordner Notizen/):
    pip install cairosvg pillow
    python tools/make_icon.py pfad/zu/static/logo.svg [--bg "#FBBC04"]

--bg ist die Hintergrundfarbe des adaptiven Icons (Standard: transparent-weiss -> #FFFFFF).
Das Skript schreibt PNGs nach app/src/main/res/mipmap-*dpi/, setzt
mipmap-anydpi-v26/ic_launcher.xml neu und stellt den Splash auf das Logo um.
"""
import argparse, io, re, sys
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def render(svg: Path, size: int) -> Image.Image:
    png = cairosvg.svg2png(url=str(svg), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def fit(svg: Path, canvas: int, inner: float) -> Image.Image:
    """Logo zentriert in einer quadratischen Canvas, Logo-Kantenlänge = inner * canvas."""
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    logo = render(svg, int(canvas * inner))
    off = (canvas - logo.width) // 2
    img.alpha_composite(logo, (off, off))
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("svg", type=Path)
    ap.add_argument("--bg", default="#FFFFFF")
    a = ap.parse_args()
    if not a.svg.exists():
        sys.exit(f"SVG nicht gefunden: {a.svg}")
    bg = a.bg.lstrip("#")
    bg_rgb = tuple(int(bg[i:i + 2], 16) for i in (0, 2, 4))

    for name, f in DENS.items():
        d = RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        # Adaptiver Vordergrund: 108dp, Logo in der 66dp-Sicherheitszone
        fit(a.svg, round(108 * f), 66 / 108).save(d / "ic_launcher_fg.png")
        # Legacy-Icons: 48dp, abgerundetes Quadrat bzw. Kreis
        px = round(48 * f)
        base = Image.new("RGBA", (px, px), bg_rgb + (255,))
        base.alpha_composite(fit(a.svg, px, 0.78))
        for fname, shape in (("ic_launcher.png", "round_rect"), ("ic_launcher_round.png", "circle")):
            mask = Image.new("L", (px * 4, px * 4), 0)
            dr = ImageDraw.Draw(mask)
            box = (0, 0, px * 4 - 1, px * 4 - 1)
            if shape == "circle":
                dr.ellipse(box, fill=255)
            else:
                dr.rounded_rectangle(box, radius=px * 4 // 5, fill=255)
            mask = mask.resize((px, px), Image.LANCZOS)
            out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
            out.paste(base, (0, 0), mask)
            out.save(d / fname)

    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/launcher_bg" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_fg" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_fg" />\n'
        '</adaptive-icon>\n', encoding="utf-8")
    (RES / "mipmap-anydpi-v26/ic_launcher_round.xml").write_text(
        (RES / "mipmap-anydpi-v26/ic_launcher.xml").read_text(encoding="utf-8"), encoding="utf-8")

    colors = RES / "values/colors.xml"
    txt = colors.read_text(encoding="utf-8")
    txt = re.sub(r'(<color name="launcher_bg">)[^<]*(</color>)', rf"\g<1>#FF{bg.upper()}\g<2>", txt)
    colors.write_text(txt, encoding="utf-8")

    # Splash auf das Logo umstellen
    th = RES / "values/themes.xml"
    t = th.read_text(encoding="utf-8")
    t = t.replace("@drawable/ic_splash", "@mipmap/ic_launcher_fg")
    th.write_text(t, encoding="utf-8")
    print("Fertig: Icons erzeugt, Splash umgestellt. Projekt neu bauen (Clean Project).")


if __name__ == "__main__":
    main()
