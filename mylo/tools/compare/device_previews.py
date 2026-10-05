#!/usr/bin/env python3
"""Side-by-side previews of the CI emulator's approved-screen captures (scope "screens") against the targets.

  python3 tools/compare/device_previews.py EVIDENCE_DIR OUT_DIR

EVIDENCE_DIR is the `device/screens` folder of a mylo-ci-evidence-screens checkout (or the Mylo-Android-preview
artifact's `screens` folder). Writes JPEGs named for design/previews/claude-ui/.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HOME_TARGET = os.path.join(ROOT, "design", "reference", "home-reference.jpg")
BROWSER_TARGET = os.path.join(ROOT, "design", "reference", "browser-reference.jpg")
BROWSER_SCREEN = (17, 31, 672, 1510)  # the phone screen inside the browser target
BG = (12, 14, 24)


def font(size):
    try:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", size)
    except OSError:
        return ImageFont.load_default()


def board(panels, labels, title, out, width=620):
    scaled = [p.resize((width, round(p.height * width / p.width)), Image.LANCZOS) for p in panels]
    h = max(p.height for p in scaled)
    gap, head = 28, 96
    img = Image.new("RGB", (len(scaled) * width + (len(scaled) + 1) * gap, h + head + gap), BG)
    d = ImageDraw.Draw(img)
    d.text((gap, 18), title, fill=(240, 240, 250), font=font(30))
    for i, (p, label) in enumerate(zip(scaled, labels)):
        x = gap + i * (width + gap)
        img.paste(p, (x, head))
        d.text((x, head - 34), label, fill=(200, 205, 230), font=font(20))
    img.save(out, quality=88)
    print("wrote", out)


def diff(a, b):
    b = b.resize(a.size, Image.LANCZOS)
    d = np.abs(np.asarray(a, np.float32) - np.asarray(b, np.float32)).mean(axis=2)
    return Image.fromarray(np.clip(d * 3, 0, 255).astype(np.uint8)).convert("RGB"), float(d.mean())


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    def load(*parts):
        path = os.path.join(src, *parts)
        return Image.open(path).convert("RGB") if os.path.exists(path) else None

    home = load("home", "home-target-1080x1920.png")
    if home:
        target = Image.open(HOME_TARGET).convert("RGB")
        d, mean = diff(target, home)
        board([target, home, d], ["Approved target (864×1536)", "Android 35 emulator, 1080×1920 @ 420 dpi", f"Difference (mean {mean:.1f}/255)"],
              "Home: approved target vs real Android (same 411×731 dp phone)", os.path.join(out, "19-home-target-vs-device.jpg"))
    browser = load("browser", "browser-pixel6-1080x2400.png")
    if browser:
        target = Image.open(BROWSER_TARGET).convert("RGB").crop(BROWSER_SCREEN)
        editing = load("browser", "browser-editing-pixel6-1080x2400.png")
        panels = [target, browser] + ([editing] if editing else [])
        labels = ["Approved target (phone screen)", "Emulator: real Google results in Mylo", "Address tapped: full editable URL"][:len(panels)]
        board(panels, labels, "Browser: Mylo's toolbar and navigation around the provider's real page (Pixel 6, 411×914 dp)",
              os.path.join(out, "20-browser-target-vs-device.jpg"))
    entry = [load("voice-entry", f) for f in ["01-home.png", "02-voice-mode-1.png", "03-home-after-close-1.png", "04-voice-mode-2.png", "05-home-after-close-2.png"]]
    if all(entry):
        board(entry, ["1. Launch: Home", "2. Bottom Mylo → Voice Mode", "3. Close voice mode → Home", "4. Mylo again → Voice Mode", "5. Close → Home"],
              "Voice Mode entry on the Android 35 emulator (no Mylo AI service configured)", os.path.join(out, "21-voice-mode-entry-device.jpg"), width=420)
    sizes = [load("home", f) for f in ["home-small-360x640.png", "home-target-1080x1920.png", "home-pixel6-1080x2400.png", "home-large-412x915.png"]]
    present = [(img, label) for img, label in zip(sizes, ["360×640 dp (scrolls)", "411×731 dp (target)", "411×914 dp (Pixel 6)", "412×915 dp @ 560 dpi"]) if img]
    if present:
        board([p for p, _ in present], [l for _, l in present], "Home on other Android sizes (real emulator rendering)",
              os.path.join(out, "22-home-sizes-device.jpg"), width=440)


if __name__ == "__main__":
    main()
