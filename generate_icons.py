#!/usr/bin/env python3
"""
Generate Android launcher icons from aigentik_app_icon.jpg
Outputs ic_launcher.png, ic_launcher_round.png, and ic_launcher_foreground.png
at all mipmap densities.
"""
from PIL import Image, ImageDraw
import os

SRC = "docs/images/aigentik_app_icon.jpg"
RES = "app/src/main/res"

DENSITIES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}

# Adaptive icon foreground is typically 108dp
ADAPTIVE_DENSITIES = {
    "mipmap-mdpi":    108,
    "mipmap-hdpi":    162,
    "mipmap-xhdpi":   216,
    "mipmap-xxhdpi":  324,
    "mipmap-xxxhdpi": 432,
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
    if not os.path.exists(SRC):
        print(f"Error: {SRC} not found")
        return

    print(f"Opening source: {SRC}")
    src = Image.open(SRC).convert("RGBA")
    src = make_square(src)
    print(f"Source cropped to {src.size[0]}x{src.size[1]}")

    for density, size in DENSITIES.items():
        out_dir = os.path.join(RES, density)
        os.makedirs(out_dir, exist_ok=True)

        # Legacy Square icon (Full size)
        square = src.resize((size, size), Image.LANCZOS)
        out_path = os.path.join(out_dir, "ic_launcher.png")
        square.save(out_path, "PNG")
        print(f"  Wrote {out_path} ({size}x{size})")

        # Legacy Round icon
        round_img = make_round(src.copy(), size)
        round_path = os.path.join(out_dir, "ic_launcher_round.png")
        round_img.save(round_path, "PNG")
        print(f"  Wrote {round_path} ({size}x{size} round)")

    # Generate Adaptive Foreground
    # For adaptive icons, we often want the foreground centered and slightly scaled
    # so it's within the safe zone (72/108 = 66.6%).
    for density, size in ADAPTIVE_DENSITIES.items():
        out_dir = os.path.join(RES, density)
        
        # Adaptive icon foreground layer (108x108 dp)
        # Content should ideally be centered in 108x108
        foreground = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        # Scale the icon to ~70% of 108dp to fit well in the safe zone
        icon_size = int(size * 0.7)
        icon_resized = src.resize((icon_size, icon_size), Image.LANCZOS)
        
        pos = (size - icon_size) // 2
        foreground.paste(icon_resized, (pos, pos))
        
        out_path = os.path.join(out_dir, "ic_launcher_foreground.png")
        foreground.save(out_path, "PNG")
        print(f"  Wrote adaptive foreground: {out_path} ({size}x{size})")

    print("\nDone! All icons generated.")

if __name__ == "__main__":
    main()
