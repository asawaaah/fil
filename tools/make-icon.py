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
    # The globe is centred, not the whole drawing: the loop at the bottom right would pull it
    # off-centre. Its extremes are read where the loop can't be: left and top edges, the right
    # edge in the upper half, the bottom edge in the left half.
    px = alpha.load()
    visible = [(x, y) for y in range(h) for x in range(w) if px[x, y] > 40]
    left = min(x for x, _ in visible)
    top = min(y for _, y in visible)
    right = max(x for x, y in visible if y < h / 2)
    bottom = max(y for x, y in visible if x < w / 2)
    cx, cy = (left + right + 1) / 2, (top + bottom + 1) / 2
    # Scale so that every pixel, loop included, stays inside the visible circle.
    radius = max(math.hypot(x + 0.5 - cx, y + 0.5 - cy) for x, y in visible[::3]) + 1
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
