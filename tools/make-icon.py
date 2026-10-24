"""Builds the Android launcher icon layers from a transparent PNG of the logo.

Usage: python tools/make-icon.py <transparent-logo.png>

Writes app/src/main/res/mipmap-<density>/ic_launcher_foreground.png (the coloured logo) and
ic_launcher_monochrome.png (its silhouette, for Android 13+ themed icons). The logo is scaled so
that every visible pixel stays inside the circle that launchers may crop the icon to.
"""
import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108  # adaptive icon layer
RADIUS_DP = 35  # visible circle on Pixel is 36 dp; 35 keeps a thin margin around the loop


def main(src: str) -> None:
    logo = Image.open(src).convert("RGBA")
    alpha = logo.getchannel("A")
    box = alpha.getbbox()
    logo = logo.crop(box)
    alpha = logo.getchannel("A")
    w, h = logo.size
    # Centre and radius of the smallest circle holding every visible pixel (searched on a grid),
    # so that the logo is as big as possible inside the launcher's circle.
    px = alpha.load()
    points = [(x + 0.5, y + 0.5) for y in range(0, h, 2) for x in range(0, w, 2) if px[x, y] > 40]

    def reach(c):
        return max(math.hypot(x - c[0], y - c[1]) for x, y in points)

    best = (w / 2, h / 2)
    step = max(w, h) / 8
    while step > 0.5:
        candidates = [(best[0] + dx * step, best[1] + dy * step) for dx in (-1, 0, 1) for dy in (-1, 0, 1)]
        nxt = min(candidates, key=reach)
        if nxt == best:
            step /= 2
        best = nxt
    cx, cy = best
    radius = reach(best) + 1
    for name, factor in DENSITIES.items():
        canvas = round(CANVAS_DP * factor)
        scale = (RADIUS_DP * factor) / radius
        size = (max(1, round(w * scale)), max(1, round(h * scale)))
        art = logo.resize(size, Image.LANCZOS)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        # Place the circle's centre at the canvas centre.
        layer.paste(art, (round(canvas / 2 - cx * scale), round(canvas / 2 - cy * scale)), art)
        mono = Image.new("RGBA", layer.size, (255, 255, 255, 0))
        mono.putalpha(layer.getchannel("A"))
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        layer.save(out / "ic_launcher_foreground.png", optimize=True)
        mono.save(out / "ic_launcher_monochrome.png", optimize=True)
        print(f"{name}: {canvas}px, logo {size[0]}x{size[1]}")


if __name__ == "__main__":
    main(sys.argv[1])
