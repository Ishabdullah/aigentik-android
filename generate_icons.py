#!/usr/bin/env python3
"""
Generate Android launcher icons from aigentik_app_icon.jpg
Outputs ic_launcher.png and ic_launcher_round.png at all mipmap densities.
"""
from PIL import Image, ImageDraw
import os
import shutil

SRC = os.path.expanduser("~/storage/downloads/aigentik_app_icon.jpg")
RES = os.path.expanduser("~/aigentik-android/app/src/main/res")

DENSITIES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}

def make_square(img):
    """Crop to square from center."""
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top  = (h - side) // 2
    return img.crop((left, top, left + side, top + side))

def make_round(img, size):
    """Resize then apply circular mask."""
    img = img.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    result.paste(img, (0, 0), mask)
    return result

def main():
    print(f"Opening source: {SRC}")
    src = Image.open(SRC).convert("RGBA")
    src = make_square(src)
    print(f"Source cropped to {src.size[0]}x{src.size[1]}")

    for density, size in DENSITIES.items():
        out_dir = os.path.join(RES, density)
        os.makedirs(out_dir, exist_ok=True)

        # Regular icon
        square = src.resize((size, size), Image.LANCZOS)
        out_path = os.path.join(out_dir, "ic_launcher.png")
        square.save(out_path, "PNG")
        print(f"  Wrote {out_path} ({size}x{size})")

        # Round icon
        round_img = make_round(src.copy(), size)
        round_path = os.path.join(out_dir, "ic_launcher_round.png")
        round_img.save(round_path, "PNG")
        print(f"  Wrote {round_path} ({size}x{size} round)")

    # Remove old drawable XML (replaced by mipmap references in manifest)
    xml_path = os.path.join(RES, "drawable", "ic_launcher.xml")
    if os.path.exists(xml_path):
        os.remove(xml_path)
        print(f"\nRemoved old {xml_path}")

    print("\nDone! All icons generated.")
    print("Remember: manifest now uses @mipmap/ic_launcher")

if __name__ == "__main__":
    main()
